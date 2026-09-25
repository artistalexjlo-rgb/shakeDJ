package com.shakedj.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Real-time mixer: plays the loaded track with varispeed, DJ filter and break effects,
 * and layers synthesized drums on top. Without a track it only plays drums, which mix
 * with whatever other app (a streaming service) is playing.
 *
 * All render state is owned by the audio thread; the UI talks to it through volatile
 * fields and commands queued with {@link #post(Runnable)}.
 */
final class AudioEngine {
    static final int FX_NONE = -1, FX_ROLL = 0, FX_DROP = 1, FX_STOP = 2, FX_SPIN = 3, FX_LOOP = 4, FX_FILL = 5;
    static final int BREAK_TYPES = 4;
    static final String[] FX_NAMES = {"РОЛЛ", "ДРОП", "СТОП", "СПИН", "ЛУП", "ФИЛЛ"};

    private static final int MAX_VOICES = 24;
    private static final int MAX_EVENTS = 128;
    private static final float TRACK_LEVEL = 0.85f;
    private static final float FILTER_K = 0.8f;

    final int sampleRate;
    private final int blockFrames;
    private final AudioTrack out;
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
    volatile float drumLevel = 0.9f;

    // Readouts for the UI.
    volatile double positionSec;
    volatile float speedNow = 1f;
    volatile float filterNow;
    volatile int fxNow = FX_NONE;
    volatile long fxStartedAtMs;

    // Audio-thread state.
    private volatile Track track;
    private double pos;
    private double lastReadPos;
    private float speed = 1f;
    private long clock;
    private int fx = FX_NONE;
    private long fxStart, fxLen;
    private double fxAnchor, slipPos, fxBeat;
    private int fadeIn, fadeLen;
    private float famt, ic1l, ic2l, ic1r, ic2r;

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
        int burst = parseOr(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 256);
        blockFrames = Math.max(64, Math.min(burst, 512));
        kit = DrumKit.build(sampleRate);

        int minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT);
        out = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(Math.max(minBytes, blockFrames * 4 * 2 * 4))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        fadeLen = (int) (0.004 * sampleRate);
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
                fxNow = FX_NONE;
                speed = 1f;
            }
        });
    }

    void seek(final double sec) {
        post(new Runnable() {
            @Override
            public void run() {
                if (track == null) return;
                pos = Math.max(0, sec * track.sampleRate);
                if (fx != FX_NONE) endFx();
                fadeIn = fadeLen;
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

    /** Shake gesture. Returns immediately; the effect label shows up in {@link #fxNow}. */
    void triggerBreak(final int type) {
        post(new Runnable() {
            @Override
            public void run() {
                startFx(type);
            }
        });
    }

    void startLoop() {
        post(new Runnable() {
            @Override
            public void run() {
                if (fx == FX_NONE && track != null && playing) startFx(FX_LOOP);
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
        float[] buf = new float[blockFrames * 2];
        out.play();
        while (running) {
            Runnable r;
            while ((r = commands.poll()) != null) r.run();
            render(buf, blockFrames);
            out.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
        }
        out.pause();
        out.flush();
        out.stop();
    }

    private void startFx(int type) {
        Track t = track;
        double beat = 60.0 / Math.max(40f, Math.min(bpm, 240f)) * sampleRate;
        if (t == null || !playing) {
            // Nothing of ours is playing (e.g. overlaying a streaming app): answer with a drum fill.
            if (fx != FX_NONE) return;
            fx = FX_FILL;
            fxStart = clock;
            fxLen = (long) (4 * beat);
            scheduleFill(clock, beat);
            schedule(clock + fxLen, DrumKit.KICK, 1f);
            schedule(clock + fxLen, DrumKit.CRASH, 1f);
            publishFx();
            return;
        }
        if (fx != FX_NONE) return;
        fx = type;
        fxStart = clock;
        fxBeat = beat;
        fxAnchor = pos;
        slipPos = pos;
        switch (type) {
            case FX_ROLL:
                fxLen = (long) (3 * beat);
                break;
            case FX_DROP:
                fxLen = (long) (4 * beat);
                scheduleFill(clock, beat);
                break;
            case FX_STOP:
            case FX_SPIN:
                fxLen = (long) (2 * beat);
                break;
            case FX_LOOP:
                fxLen = Long.MAX_VALUE;
                break;
            default:
                fx = FX_NONE;
                return;
        }
        if (type != FX_LOOP) {
            schedule(clock + fxLen, DrumKit.KICK, 1f);
            schedule(clock + fxLen, DrumKit.CRASH, 0.9f);
        }
        publishFx();
    }

    private void publishFx() {
        fxNow = fx;
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

    /** One bar of drums on a 16th-note grid: kick pattern, backbeat, then a snare roll. */
    private void scheduleFill(long start, double beat) {
        double step = beat / 4;
        int[] kicks = {0, 3, 8, 10};
        for (int s : kicks) schedule(start + (long) (s * step), DrumKit.KICK, 1f);
        schedule(start + (long) (4 * step), DrumKit.SNARE, 0.9f);
        schedule(start + (long) (12 * step), DrumKit.CLAP, 0.8f);
        for (int s = 0; s < 12; s += 2) schedule(start + (long) (s * step), DrumKit.HAT, s % 4 == 0 ? 0.9f : 0.6f);
        schedule(start + (long) (13 * step), DrumKit.SNARE, 0.55f);
        schedule(start + (long) (14 * step), DrumKit.SNARE, 0.7f);
        schedule(start + (long) (15 * step), DrumKit.SNARE, 0.9f);
        schedule(start + (long) (14 * step), DrumKit.PERC, 0.6f);
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
                        } else {
                            pos = Math.max(0, avail - 1);
                        }
                    }
                } else {
                    slipPos += ratio;
                    double m = clock - fxStart;
                    double b = fxBeat;
                    switch (fx) {
                        case FX_ROLL: {
                            double sl, st;
                            if (m < b) { sl = b / 2; st = 0; }
                            else if (m < 2 * b) { sl = b / 4; st = b; }
                            else if (m < 2.5 * b) { sl = b / 8; st = 2 * b; }
                            else { sl = b / 16; st = 2.5 * b; }
                            double loc = (m - st) % sl;
                            rp = fxAnchor + loc * ratio;
                            gain = edge(loc, sl);
                            break;
                        }
                        case FX_LOOP: {
                            double loc = m % b;
                            rp = fxAnchor + loc * ratio;
                            gain = edge(loc, b);
                            break;
                        }
                        case FX_DROP: {
                            rp = fxAnchor + m * ratio;
                            gain = (float) Math.max(0, 1 - m / (0.012 * sampleRate));
                            break;
                        }
                        case FX_STOP: {
                            double y = 1 - Math.min(1, m / b);
                            rp = fxAnchor + ratio * b * (1 - y * y * y) / 3.0;
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
                        float s = gain * TRACK_LEVEL / 32768f;
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

            buf[2 * i] = softClip(l + dL * dl);
            buf[2 * i + 1] = softClip(r + dR * dl);
            clock++;
        }

        if (t != null) positionSec = lastReadPos / t.sampleRate;
        speedNow = speed;
    }

    /** Cubic soft clipper with unity small-signal gain; saturates smoothly at ±1.5 input. */
    private static float softClip(float x) {
        x *= 0.6666667f;
        if (x > 1f) x = 1f;
        else if (x < -1f) x = -1f;
        return x * (1.5f - 0.5f * x * x);
    }
}
