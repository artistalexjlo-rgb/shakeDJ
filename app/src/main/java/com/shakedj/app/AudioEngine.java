package com.shakedj.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Process;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Real-time mixer: plays the loaded track with varispeed, DJ filter and break effects,
 * and layers synthesized drums on top. Without a track it only plays drums, which mix
 * with whatever other app (a streaming service) is playing.
 *
 * Breaks are quantized to a beat grid: they start on the next beat and hand back to the
 * track exactly on the next "one" of a bar. The grid lives in track time when a track
 * plays (auto-detected or set by the user) and in engine-clock time otherwise (set by
 * tapping the tempo), so fills over a streaming app can land on the one too.
 *
 * All render state is owned by the audio thread; the UI talks to it through volatile
 * fields and commands queued with {@link #post(Runnable)}.
 */
final class AudioEngine {
    static final int FX_NONE = -1, FX_ROLL = 0, FX_DROP = 1, FX_STOP = 2, FX_SPIN = 3, FX_LOOP = 4, FX_FILL = 5,
            FX_SCRATCH = 6;
    static final int BREAK_TYPES = 4;
    static final String[] FX_NAMES = {"РОЛЛ", "ДРОП", "СТОП", "СПИН", "ЛУП", "ФИЛЛ", "СКРЕТЧ"};
    /** A full-width scratch stroke moves the record by this many beats. */
    static final double SCRATCH_BEATS_PER_WIDTH = 0.5;

    private static final int MAX_VOICES = 24;
    private static final int MAX_EVENTS = 128;
    private static final int HISTORY = 512;
    private static final float TRACK_LEVEL = 0.75f;
    private static final float LIMIT = 0.95f;
    /** How hard each drum pushes the sidechain (kick fully, hats barely). */
    private static final float[] SIDECHAIN_KEY = {1f, 0.7f, 0.7f, 0.15f, 0.3f, 0.5f, 0.4f};
    private static final float FILTER_K = 0.8f;

    final int sampleRate;
    private final int burst;
    private AudioTrack out;
    private boolean floatOut;
    private short[] shortBuf;
    /** Engine clock at the moment the current AudioTrack started (its frame counter starts at 0). */
    private long clockBase;
    private int routedId = -1;
    private final float[][] kit;
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<Runnable>();
    private volatile boolean running;
    private Thread thread;

    // Controls written by the UI.
    volatile boolean playing;
    volatile float bpm = 120f;
    volatile float filterTarget;
    volatile float speedTarget = 1f;
    volatile float speedGlideSec = 0.1f;
    volatile float drumLevel = 1.2f;
    /** 0..1: how deep drum hits duck the track. The track recovers over half a beat. */
    volatile float sidechain = 0.5f;
    /** Scratch stroke in beats from where the finger landed; written by the UI while scratching. */
    volatile double scratchBeats;

    // Readouts for the UI.
    volatile double positionSec;
    volatile float speedNow = 1f;
    volatile float filterNow;
    volatile int fxNow = FX_NONE;
    volatile long fxStartedAtMs;
    /** Beats since a downbeat at the moment that is audible now; NaN without a grid. */
    volatile double beatNow = Double.NaN;
    volatile int outputLatencyMs;
    /** Where the sound goes ("динамик", "Bluetooth", ...) and whether Android gave us its fast mixer path. */
    volatile String routeName = "";
    volatile boolean routeBluetooth;
    volatile boolean fastPath;

    // Audio-thread state.
    private volatile Track track;
    private double pos;
    private double lastReadPos;
    private float speed = 1f;
    private long clock;
    private int fx = FX_NONE;
    private long fxStart, fxLen;
    private int fxBeats;
    private double fxAnchor, slipPos, fxBeat, loopPhase;
    private int pendingFx = FX_NONE, pendingBeats, pendingBarBeat;
    private double pendingPos;
    private int fadeIn;
    private final int fadeLen;
    private float famt, ic1l, ic2l, ic1r, ic2r;
    private float scKey, scDuck, limGain = 1f;
    private double scratchPos;

    // Beat grids.
    private boolean trackGrid;
    private double gridOffsetSec;
    private boolean clockGrid;
    private long clockGridOffset;

    // Output timing: which rendered frame is audible when, and where the track timeline was.
    private final AudioTimestamp ts = new AudioTimestamp();
    private long tsFrame = -1, tsNanos;
    private final long[] hClock = new long[HISTORY];
    private final double[] hPos = new double[HISTORY];
    private int hCount;

    private final float[][] vSmp = new float[MAX_VOICES][];
    private final int[] vPos = new int[MAX_VOICES];
    private final int[] vDrum = new int[MAX_VOICES];
    private final float[] vGl = new float[MAX_VOICES];
    private final float[] vGr = new float[MAX_VOICES];

    private final long[] evT = new long[MAX_EVENTS];
    private final int[] evD = new int[MAX_EVENTS];
    private final float[] evG = new float[MAX_EVENTS];
    private int evN;

    AudioEngine(Context ctx) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        sampleRate = parseOr(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE), 48000);
        burst = Math.max(32, Math.min(parseOr(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 192), 2048));
        kit = DrumKit.build(sampleRate);

        shortBuf = new short[burst * 2];
        openOutput();
        fadeLen = (int) (0.004 * sampleRate);
    }

    /**
     * Opens the output, preferring a track that Android puts on its fast (low-latency) mixer path.
     * Some phones only grant it for 16-bit PCM or for game audio, so those are tried in turn; without
     * the fast path the sound goes through the deep "music" buffer, which adds 100+ ms.
     */
    private void openOutput() {
        int[][] configs = {
                {AudioFormat.ENCODING_PCM_FLOAT, AudioAttributes.USAGE_MEDIA},
                {AudioFormat.ENCODING_PCM_16BIT, AudioAttributes.USAGE_MEDIA},
                {AudioFormat.ENCODING_PCM_16BIT, AudioAttributes.USAGE_GAME},
        };
        AudioTrack chosen = null;
        boolean chosenFloat = false;
        RuntimeException error = null;
        for (int[] c : configs) {
            AudioTrack t;
            try {
                t = buildTrack(c[0], c[1]);
            } catch (RuntimeException e) {
                error = e;
                continue;
            }
            boolean fast = t.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
            if (chosen == null || fast) {
                if (chosen != null) chosen.release();
                chosen = t;
                chosenFloat = c[0] == AudioFormat.ENCODING_PCM_FLOAT;
            } else {
                t.release();
            }
            if (fast) break;
        }
        if (chosen == null) throw error != null ? error : new IllegalStateException("no audio output");
        out = chosen;
        floatOut = chosenFloat;
        fastPath = out.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        // Blocking writes keep the buffer full, so its fill level *is* the latency: start at two bursts.
        out.setBufferSizeInFrames(burst * 2);
    }

    private AudioTrack buildTrack(int encoding, int usage) {
        int bytesPerFrame = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 8 : 4;
        int minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, encoding);
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(usage)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                // Capacity leaves room for the latency tuner to grow into.
                .setBufferSizeInBytes(Math.max(minBytes, burst * 8 * bytesPerFrame))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    /** Re-opens the output after a route change, so returning from Bluetooth regains the fast path. */
    private void reopenOutput() {
        out.pause();
        out.flush();
        out.release();
        openOutput();
        clockBase = clock;
        tsFrame = -1;
        out.play();
    }

    private void pollRoute(boolean mayReopen) {
        AudioDeviceInfo dev = out.getRoutedDevice();
        if (dev == null || dev.getId() == routedId) return;
        boolean changed = routedId != -1;
        routedId = dev.getId();
        if (changed && mayReopen) {
            reopenOutput();
            dev = out.getRoutedDevice();
            if (dev != null) routedId = dev.getId();
        }
        int type = dev != null ? dev.getType() : AudioDeviceInfo.TYPE_UNKNOWN;
        routeBluetooth = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST || type == AudioDeviceInfo.TYPE_HEARING_AID;
        routeName = routeName(type);
    }

    private static String routeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "динамик";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
                return "провод";
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                return "USB";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return "Bluetooth";
            default:
                return "выход " + type;
        }
    }

    private static int parseOr(String s, int def) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "ShakeDJ-audio");
        thread.start();
    }

    void release() {
        running = false;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
            }
        }
        out.release();
    }

    void post(Runnable r) {
        commands.add(r);
    }

    // ---- Commands -------------------------------------------------------------------------

    Track currentTrack() {
        return track;
    }

    void setTrack(final Track t) {
        post(new Runnable() {
            @Override
            public void run() {
                track = t;
                pos = 0;
                lastReadPos = 0;
                fx = FX_NONE;
                pendingFx = FX_NONE;
                fxNow = FX_NONE;
                speed = 1f;
                trackGrid = false;
                hCount = 0;
            }
        });
    }

    /** Beat grid of the track: {@code downbeatSec} is the position of any "one". */
    void setTrackGrid(final Track t, final double downbeatSec) {
        post(new Runnable() {
            @Override
            public void run() {
                if (track != t) return;
                trackGrid = true;
                gridOffsetSec = downbeatSec;
            }
        });
    }

    /**
     * The user marked a downbeat at {@code nanoTime} (System.nanoTime / CLOCK_MONOTONIC).
     * Anchors the clock grid and, when a track is playing, the track grid too.
     */
    void markDownbeat(final long nanoTime) {
        post(new Runnable() {
            @Override
            public void run() {
                long heard = heardClock(nanoTime);
                clockGrid = true;
                clockGridOffset = heard;
                Track t = track;
                if (t != null && playing) {
                    double p = timelineAt(heard);
                    if (!Double.isNaN(p)) {
                        trackGrid = true;
                        gridOffsetSec = p / t.sampleRate;
                    }
                }
            }
        });
    }

    void seek(final double sec) {
        post(new Runnable() {
            @Override
            public void run() {
                if (track == null) return;
                pos = Math.max(0, sec * track.sampleRate);
                pendingFx = FX_NONE;
                if (fx != FX_NONE) endFx();
                fxNow = FX_NONE;
                fadeIn = fadeLen;
                hCount = 0;
            }
        });
    }

    void hit(final int drum, final float gain) {
        post(new Runnable() {
            @Override
            public void run() {
                startVoice(drum, gain);
            }
        });
    }

    /** Shake gesture. The break starts on the next beat and ends on the next downbeat. */
    void triggerBreak(final int type) {
        post(new Runnable() {
            @Override
            public void run() {
                requestFx(type);
            }
        });
    }

    void startLoop() {
        post(new Runnable() {
            @Override
            public void run() {
                if (fx == FX_NONE && pendingFx == FX_NONE && track != null && playing) startLoopNow();
            }
        });
    }

    /** Finger lands on the record: it stops under the finger, the timeline keeps running (slip). */
    void startScratch() {
        scratchBeats = 0;
        post(new Runnable() {
            @Override
            public void run() {
                if (fx != FX_NONE || track == null || !playing) return;
                pendingFx = FX_NONE;
                fx = FX_SCRATCH;
                fxStart = clock;
                fxLen = Long.MAX_VALUE;
                fxBeat = beatFrames();
                fxAnchor = pos;
                slipPos = pos;
                scratchPos = pos;
                publishFx(FX_SCRATCH);
            }
        });
    }

    /** Finger lifted: the track drops back in exactly where it would have been, so it stays on the beat. */
    void stopScratch() {
        post(new Runnable() {
            @Override
            public void run() {
                if (fx == FX_SCRATCH) endFx();
            }
        });
    }

    void stopLoop() {
        post(new Runnable() {
            @Override
            public void run() {
                if (fx == FX_LOOP) endFx();
            }
        });
    }

    // ---- Audio thread -----------------------------------------------------------------------

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        float[] buf = new float[burst * 2];
        out.play();
        pollRoute(false);
        int blocks = 0, underruns = 0, openedAt = 0;
        while (running) {
            Runnable r;
            while ((r = commands.poll()) != null) r.run();
            render(buf, burst);
            if (floatOut) {
                out.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
            } else {
                for (int i = 0; i < buf.length; i++) shortBuf[i] = (short) (buf[i] * 32767f);
                out.write(shortBuf, 0, shortBuf.length, AudioTrack.WRITE_BLOCKING);
            }
            if ((++blocks & 63) == 0) {
                int before = routedId;
                pollRoute(true);
                if (routedId != before && before != -1) openedAt = blocks;
            }
            if ((blocks & 15) == 0) {
                if (out.getTimestamp(ts)) {
                    tsFrame = ts.framePosition;
                    tsNanos = ts.nanoTime;
                }
                // Latency tuner: grow the buffer one burst at a time only if the device glitches.
                // Underruns while the stream is starting up don't count.
                int u = out.getUnderrunCount();
                if (blocks - openedAt < 128) {
                    underruns = u;
                } else if (u > underruns) {
                    underruns = u;
                    int size = out.getBufferSizeInFrames();
                    if (size + burst <= out.getBufferCapacityInFrames()) out.setBufferSizeInFrames(size + burst);
                }
            }
        }
        out.pause();
        out.flush();
        out.stop();
    }

    /** Engine-clock frame that is (or was) coming out of the speaker at {@code nanoTime}. */
    private long heardClock(long nanoTime) {
        if (tsFrame >= 0) return clockBase + tsFrame + (long) ((nanoTime - tsNanos) * (double) sampleRate / 1e9);
        return clock - out.getBufferSizeInFrames();
    }

    /** Track timeline position (ignoring effects) at engine-clock frame {@code c}; NaN if unknown. */
    private double timelineAt(long c) {
        if (hCount == 0) return Double.NaN;
        int newest = (hCount - 1) % HISTORY;
        if (c >= hClock[newest]) return hPos[newest];
        int oldest = hCount > HISTORY ? hCount - HISTORY : 0;
        for (int n = hCount - 1; n > oldest; n--) {
            int i = n % HISTORY, j = (n - 1) % HISTORY;
            if (hClock[j] <= c) {
                double f = (c - hClock[j]) / (double) (hClock[i] - hClock[j]);
                return hPos[j] + (hPos[i] - hPos[j]) * f;
            }
        }
        return Double.NaN;
    }

    private double beatFrames() {
        return 60.0 / Math.max(40f, Math.min(bpm, 240f)) * sampleRate;
    }

    /** Beats until the next downbeat when starting at bar position {@code barBeat}; at least two. */
    private static int beatsToOne(int barBeat) {
        int n = 4 - barBeat;
        return n < 2 ? n + 4 : n;
    }

    private void requestFx(int type) {
        if (fx != FX_NONE || pendingFx != FX_NONE) return;
        Track t = track;
        double beat = beatFrames();
        if (t == null || !playing) {
            // Nothing of ours is playing (e.g. overlaying a streaming app): answer with a drum fill,
            // on the tapped grid if there is one.
            long start = clock;
            int barBeat = 0, beats = 4;
            if (clockGrid) {
                long nb = (long) Math.ceil((clock - clockGridOffset) / beat);
                start = clockGridOffset + Math.round(nb * beat);
                barBeat = (int) Math.floorMod(nb, 4L);
                beats = beatsToOne(barBeat);
            }
            fx = FX_FILL;
            fxStart = start;
            fxLen = Math.round(beats * beat);
            scheduleFill(start, beat, barBeat, beats);
            scheduleReturn(start + fxLen);
            publishFx(FX_FILL);
            return;
        }
        if (trackGrid) {
            double beatT = beat * t.sampleRate / sampleRate;
            double off = gridOffsetSec * t.sampleRate;
            long nb = (long) Math.ceil((pos - off) / beatT);
            pendingFx = type;
            pendingPos = off + nb * beatT;
            pendingBarBeat = (int) Math.floorMod(nb, 4L);
            pendingBeats = beatsToOne(pendingBarBeat);
        } else {
            beginFx(type, pos, 4, 0, clock);
        }
        publishFx(type);
    }

    private void beginFx(int type, double anchor, int beats, int barBeat, long start) {
        fx = type;
        fxStart = start;
        fxBeat = beatFrames();
        fxBeats = beats;
        fxAnchor = anchor;
        slipPos = pos;
        fxLen = Math.round(beats * fxBeat);
        switch (type) {
            case FX_DROP:
                scheduleFill(start, fxBeat, barBeat, beats);
                break;
            case FX_STOP:
            case FX_SPIN:
                scheduleRoll(start + Math.round((beats - 1) * fxBeat), fxBeat);
                break;
            default:
                break;
        }
        scheduleReturn(start + fxLen);
    }

    /** Hold-to-loop: loops the current beat, starting seamlessly from where the track is now. */
    private void startLoopNow() {
        Track t = track;
        double ratio = (double) t.sampleRate / sampleRate;
        fx = FX_LOOP;
        fxStart = clock;
        fxBeat = beatFrames();
        fxLen = Long.MAX_VALUE;
        slipPos = pos;
        if (trackGrid) {
            double beatT = fxBeat * ratio;
            double off = gridOffsetSec * t.sampleRate;
            fxAnchor = off + Math.floor((pos - off) / beatT) * beatT;
        } else {
            fxAnchor = pos;
        }
        loopPhase = (pos - fxAnchor) / ratio;
        publishFx(FX_LOOP);
    }

    private void publishFx(int type) {
        fxNow = type;
        fxStartedAtMs = System.currentTimeMillis();
    }

    private void endFx() {
        if (fx != FX_FILL) {
            pos = slipPos;
            fadeIn = fadeLen;
        }
        fx = FX_NONE;
        fxNow = FX_NONE;
        speed = speedTarget;
    }

    /**
     * Drums over {@code beats} beats starting at bar position {@code barBeat}, on a 16th grid:
     * a groove, then a snare roll on the last beat leading into the downbeat.
     */
    private void scheduleFill(long start, double beat, int barBeat, int beats) {
        double step = beat / 4;
        for (int k = 0; k < beats; k++) {
            long base = start + Math.round(k * beat);
            if (k == beats - 1) {
                scheduleRoll(base, beat);
                continue;
            }
            int bb = (barBeat + k) % 4;
            for (int j = 0; j < 4; j++) {
                int s = bb * 4 + j;
                long at = base + Math.round(j * step);
                if (s == 0 || s == 3 || s == 8 || s == 10) schedule(at, DrumKit.KICK, 1f);
                if (s == 4) schedule(at, DrumKit.SNARE, 0.9f);
                if (s == 12) schedule(at, DrumKit.CLAP, 0.85f);
                if (j % 2 == 0) schedule(at, DrumKit.HAT, j == 0 ? 0.9f : 0.6f);
            }
        }
    }

    private void scheduleRoll(long start, double beat) {
        float[] gains = {0.45f, 0.6f, 0.75f, 0.95f};
        for (int j = 0; j < 4; j++) schedule(start + Math.round(j * beat / 4), DrumKit.SNARE, gains[j]);
        schedule(start + Math.round(beat / 2), DrumKit.PERC, 0.6f);
    }

    private void scheduleReturn(long at) {
        schedule(at, DrumKit.KICK, 1f);
        schedule(at, DrumKit.CRASH, 0.9f);
    }

    private void schedule(long t, int drum, float gain) {
        if (evN >= MAX_EVENTS) return;
        int i = evN;
        while (i > 0 && evT[i - 1] > t) {
            evT[i] = evT[i - 1];
            evD[i] = evD[i - 1];
            evG[i] = evG[i - 1];
            i--;
        }
        evT[i] = t;
        evD[i] = drum;
        evG[i] = gain;
        evN++;
    }

    private void popEvent() {
        startVoice(evD[0], evG[0]);
        evN--;
        System.arraycopy(evT, 1, evT, 0, evN);
        System.arraycopy(evD, 1, evD, 0, evN);
        System.arraycopy(evG, 1, evG, 0, evN);
    }

    private void startVoice(int drum, float gain) {
        if (drum < 0 || drum >= DrumKit.COUNT) return;
        if (drum == DrumKit.HAT) {
            // A closed hi-hat chokes the open one, like on a real kit.
            for (int v = 0; v < MAX_VOICES; v++) if (vSmp[v] != null && vDrum[v] == DrumKit.OPEN_HAT) vSmp[v] = null;
        }
        int slot = -1, oldest = -1;
        for (int v = 0; v < MAX_VOICES; v++) {
            if (vSmp[v] == null) {
                slot = v;
                break;
            }
            if (oldest < 0 || vPos[v] > vPos[oldest]) oldest = v;
        }
        if (slot < 0) slot = oldest;
        scKey = Math.max(scKey, SIDECHAIN_KEY[drum] * Math.min(1f, gain));
        float pan = DrumKit.PAN[drum];
        vSmp[slot] = kit[drum];
        vPos[slot] = 0;
        vDrum[slot] = drum;
        vGl[slot] = gain * Math.min(1f, 1f - pan);
        vGr[slot] = gain * Math.min(1f, 1f + pan);
    }

    private float edge(double loc, double len) {
        double f = 0.002 * sampleRate;
        return (float) Math.max(0, Math.min(1, Math.min(loc / f, (len - loc) / f)));
    }

    private void render(float[] buf, int n) {
        final Track t = track;
        boolean play = playing && t != null;

        famt += (filterTarget - famt) * 0.15f;
        filterNow = famt;
        int fmode;
        double fc;
        if (famt < -0.02f) {
            fmode = 1;
            fc = 20000 * Math.pow(250.0 / 20000.0, -famt);
        } else if (famt > 0.02f) {
            fmode = 2;
            fc = 25 * Math.pow(4000.0 / 25.0, famt);
        } else {
            fmode = 0;
            fc = 20000;
        }
        fc = Math.min(fc, sampleRate * 0.45);
        float g = (float) Math.tan(Math.PI * fc / sampleRate);
        float a1 = 1f / (1f + g * (g + FILTER_K)), a2 = g * a1, a3 = g * a2;

        float coef = (float) (1.0 - Math.exp(-1.0 / (Math.max(0.004, speedGlideSec) * sampleRate)));
        float tgt = speedTarget;
        double ratio = t != null ? (double) t.sampleRate / sampleRate : 1;
        int avail = t != null ? t.decodedFrames : 0;
        float dl = drumLevel;
        short[] d = t != null ? t.data : null;
        float scDepth = 0.85f * Math.max(0f, Math.min(1f, sidechain));
        // Duck release: down to 5% of the duck within half a beat, so the pump breathes in tempo.
        float scRelease = (float) Math.exp(Math.log(0.05) / (0.5 * beatFrames()));
        float scAttack = (float) (1 - Math.exp(-1.0 / (0.003 * sampleRate)));
        float limRelease = (float) (1 - Math.exp(-1.0 / (0.08 * sampleRate)));
        double scratchTarget = fx == FX_SCRATCH
                ? fxAnchor + scratchBeats * beatFrames() * ratio : 0;
        float scratchCoef = (float) (1 - Math.exp(-1.0 / (0.006 * sampleRate)));

        for (int i = 0; i < n; i++) {
            while (evN > 0 && evT[0] <= clock) popEvent();
            if (fx != FX_NONE && clock - fxStart >= fxLen) endFx();

            float l = 0, r = 0;
            if (play) {
                double rp;
                float gain = 1f;
                if (fx == FX_NONE || fx == FX_FILL) {
                    speed += (tgt - speed) * coef;
                    rp = pos;
                    pos += speed * ratio;
                    if (pos < 0) pos = 0;
                    if (pos >= avail - 1) {
                        if (t.complete) {
                            playing = false;
                            play = false;
                            pos = 0;
                            pendingFx = FX_NONE;
                        } else {
                            pos = Math.max(0, avail - 1);
                        }
                    }
                    if (pendingFx != FX_NONE && pos >= pendingPos) {
                        int type = pendingFx;
                        pendingFx = FX_NONE;
                        beginFx(type, pendingPos, pendingBeats, pendingBarBeat, clock + 1);
                    }
                } else {
                    slipPos += ratio;
                    double m = clock - fxStart;
                    if (m < 0) m = 0;
                    double b = fxBeat;
                    switch (fx) {
                        case FX_ROLL: {
                            // Slices get shorter as the downbeat approaches: 1/2, 1/4, 1/8, 1/16 beat.
                            double left = fxBeats - m / b;
                            double sl = left > 2 ? b / 2 : left > 1 ? b / 4 : left > 0.5 ? b / 8 : b / 16;
                            double loc = m % sl;
                            rp = fxAnchor + loc * ratio;
                            gain = edge(loc, sl);
                            break;
                        }
                        case FX_SCRATCH: {
                            // The record follows the finger smoothly (no zipper noise from touch steps).
                            scratchPos += (scratchTarget - scratchPos) * scratchCoef;
                            if (scratchPos < 0) scratchPos = 0;
                            rp = scratchPos;
                            break;
                        }
                        case FX_LOOP: {
                            double loc = (m + loopPhase) % b;
                            rp = fxAnchor + loc * ratio;
                            // No fade-in on the first pass: it continues seamlessly from the track.
                            gain = m + loopPhase < b
                                    ? (float) Math.min(1, (b - loc) / (0.002 * sampleRate))
                                    : edge(loc, b);
                            break;
                        }
                        case FX_DROP: {
                            rp = fxAnchor + m * ratio;
                            gain = (float) Math.max(0, 1 - m / (0.012 * sampleRate));
                            break;
                        }
                        case FX_STOP: {
                            double len = (fxBeats >= 3 ? 2 : 1) * b;
                            double y = 1 - Math.min(1, m / len);
                            rp = fxAnchor + ratio * len * (1 - y * y * y) / 3.0;
                            gain = (float) Math.min(1, y * 6);
                            break;
                        }
                        default: { // FX_SPIN
                            double y = 1 - Math.min(1, m / b);
                            rp = fxAnchor - 4 * ratio * b * (1 - y * y * y) / 3.0;
                            gain = (float) Math.min(1, y * 4);
                            break;
                        }
                    }
                }
                if (fadeIn > 0) {
                    gain *= 1f - fadeIn / (float) fadeLen;
                    fadeIn--;
                }
                lastReadPos = rp;
                if (gain > 0 && rp >= 0) {
                    int i0 = (int) rp;
                    if (i0 + 1 < avail) {
                        float fr = (float) (rp - i0);
                        int k = i0 * 2;
                        float s = gain * TRACK_LEVEL * (1f - scDepth * scDuck) / 32768f;
                        l = (d[k] + (d[k + 2] - d[k]) * fr) * s;
                        r = (d[k + 1] + (d[k + 3] - d[k + 1]) * fr) * s;
                    }
                }
            }

            // DJ filter on the track only (drums stay punchy). Runs always to keep its state warm.
            float v0 = l + 1e-18f;
            float v3 = v0 - ic2l, v1 = a1 * ic1l + a2 * v3, v2 = ic2l + a2 * ic1l + a3 * v3;
            ic1l = 2 * v1 - ic1l;
            ic2l = 2 * v2 - ic2l;
            if (fmode == 1) l = v2;
            else if (fmode == 2) l = v0 - FILTER_K * v1 - v2;
            v0 = r + 1e-18f;
            v3 = v0 - ic2r;
            v1 = a1 * ic1r + a2 * v3;
            v2 = ic2r + a2 * ic1r + a3 * v3;
            ic1r = 2 * v1 - ic1r;
            ic2r = 2 * v2 - ic2r;
            if (fmode == 1) r = v2;
            else if (fmode == 2) r = v0 - FILTER_K * v1 - v2;

            float dL = 0, dR = 0;
            for (int v = 0; v < MAX_VOICES; v++) {
                float[] s = vSmp[v];
                if (s == null) continue;
                int p = vPos[v];
                float x = s[p];
                dL += x * vGl[v];
                dR += x * vGr[v];
                if (++p >= s.length) vSmp[v] = null;
                else vPos[v] = p;
            }

            // Sidechain envelope: fast attack towards the key, release in tempo.
            if (scKey > scDuck) scDuck += (scKey - scDuck) * scAttack;
            else scDuck = scKey;
            scKey *= scRelease;

            // Peak limiter so loud pads get louder without crackling; the clamp is only a safety net.
            float oL = l + dL * dl, oR = r + dR * dl;
            float peak = Math.max(Math.abs(oL), Math.abs(oR)) * limGain;
            if (peak > LIMIT) limGain *= LIMIT / peak;
            else limGain += (1f - limGain) * limRelease;
            buf[2 * i] = clamp(oL * limGain);
            buf[2 * i + 1] = clamp(oR * limGain);
            clock++;
        }

        if (t != null) {
            positionSec = lastReadPos / t.sampleRate;
            int h = hCount % HISTORY;
            hClock[h] = clock;
            hPos[h] = fx == FX_NONE || fx == FX_FILL ? pos : slipPos;
            hCount++;
        }
        speedNow = speed;
        publishBeat(t);
    }

    private void publishBeat(Track t) {
        long heard = heardClock(System.nanoTime());
        outputLatencyMs = (int) Math.max(0, (clock - heard) * 1000L / sampleRate);
        double beats = Double.NaN;
        if (t != null && playing && trackGrid) {
            double p = timelineAt(heard);
            if (!Double.isNaN(p)) beats = (p / t.sampleRate - gridOffsetSec) * bpm / 60.0;
        } else if (clockGrid) {
            beats = (heard - clockGridOffset) / beatFrames();
        }
        beatNow = beats;
    }

    private static float clamp(float x) {
        return x > 1f ? 1f : x < -1f ? -1f : x;
    }
}
