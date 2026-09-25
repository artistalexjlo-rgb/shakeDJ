package com.shakedj.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

/**
 * Where rendered audio goes. {@link #open} prefers AAudio (native, can use the MMAP path that
 * bypasses the system mixer) and falls back to AudioTrack. Both take blocking writes of
 * interleaved float stereo, so the engine doesn't care which one it got.
 */
abstract class AudioOutput {
    final int sampleRate;
    final int burst;

    AudioOutput(int sampleRate, int burst) {
        this.sampleRate = sampleRate;
        this.burst = burst;
    }

    abstract void start();

    /** Blocking write; returns false if the stream is gone (e.g. the route changed) and must be reopened. */
    abstract boolean write(float[] buf, int frames);

    /** {framePosition, CLOCK_MONOTONIC nanos} of the frame being presented; false if not available yet. */
    abstract boolean timestamp(long[] out);

    abstract int bufferFrames();

    abstract int capacityFrames();

    abstract void setBufferFrames(int frames);

    abstract int xruns();

    /** Output device id, or 0 if unknown. */
    abstract int deviceId();

    /** Whether Android put us on a low-latency path. */
    abstract boolean fast();

    /** Short name of the output API for the diagnostics line. */
    abstract String api();

    abstract void close();

    /**
     * @param sampleRate rate to request, or 0 for the device's native rate
     * @param preferNative try AAudio first
     */
    static AudioOutput open(Context ctx, int sampleRate, boolean preferNative) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int nativeRate = parseOr(am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE), 48000);
        int nativeBurst = Math.max(32, Math.min(parseOr(am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER), 192), 2048));
        if (preferNative && NativeAudio.available()) {
            AudioOutput o = AAudioOut.open(sampleRate);
            if (o != null) return o;
        }
        return TrackOut.open(sampleRate > 0 ? sampleRate : nativeRate, nativeBurst);
    }

    /** Human name of the output device type, and whether it is Bluetooth. */
    static String routeName(Context ctx, int deviceId) {
        int type = deviceType(ctx, deviceId);
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
            case AudioDeviceInfo.TYPE_UNKNOWN:
                return "";
            default:
                return isBluetooth(type) ? "Bluetooth" : "выход " + type;
        }
    }

    static boolean isBluetooth(Context ctx, int deviceId) {
        return isBluetooth(deviceType(ctx, deviceId));
    }

    private static boolean isBluetooth(int type) {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER
                || type == AudioDeviceInfo.TYPE_BLE_BROADCAST || type == AudioDeviceInfo.TYPE_HEARING_AID;
    }

    private static int deviceType(Context ctx, int deviceId) {
        if (deviceId <= 0) return AudioDeviceInfo.TYPE_UNKNOWN;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getId() == deviceId) return d.getType();
        }
        return AudioDeviceInfo.TYPE_UNKNOWN;
    }

    private static int parseOr(String s, int def) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : def;
        } catch (Exception e) {
            return def;
        }
    }

    // ---- AAudio -------------------------------------------------------------------------------

    private static final class AAudioOut extends AudioOutput {
        private final long h;
        private final int capacity;
        private final boolean fast;
        private final boolean exclusive;

        private AAudioOut(long h, int[] info) {
            super(info[0], info[1]);
            this.h = h;
            this.exclusive = info[2] == NativeAudio.SHARING_EXCLUSIVE;
            this.fast = info[3] == NativeAudio.PERFORMANCE_LOW_LATENCY;
            this.capacity = info[5];
        }

        static AudioOutput open(int sampleRate) {
            long h = NativeAudio.nOpen(sampleRate, NativeAudio.USAGE_MEDIA);
            if (h == 0) return null;
            int[] info = new int[6];
            NativeAudio.nInfo(h, info);
            if (info[0] <= 0 || info[1] <= 0) {
                NativeAudio.nClose(h);
                return null;
            }
            AAudioOut o = new AAudioOut(h, info);
            o.setBufferFrames(o.burst * 2);
            return o;
        }

        @Override
        void start() {
            NativeAudio.nStart(h);
        }

        @Override
        boolean write(float[] buf, int frames) {
            int r = NativeAudio.nWrite(h, buf, frames);
            return r >= 0;
        }

        @Override
        boolean timestamp(long[] out) {
            return NativeAudio.nTimestamp(h, out);
        }

        @Override
        int bufferFrames() {
            return NativeAudio.nGetBufferSize(h);
        }

        @Override
        int capacityFrames() {
            return capacity;
        }

        @Override
        void setBufferFrames(int frames) {
            NativeAudio.nSetBufferSize(h, frames);
        }

        @Override
        int xruns() {
            return NativeAudio.nXRuns(h);
        }

        @Override
        int deviceId() {
            int[] info = new int[6];
            NativeAudio.nInfo(h, info);
            return info[4];
        }

        @Override
        boolean fast() {
            return fast;
        }

        @Override
        String api() {
            return exclusive ? "AAudio excl" : "AAudio";
        }

        @Override
        void close() {
            NativeAudio.nClose(h);
        }
    }

    // ---- AudioTrack ---------------------------------------------------------------------------

    private static final class TrackOut extends AudioOutput {
        private final AudioTrack t;
        private final boolean isFloat;
        private final short[] shorts;
        private final AudioTimestamp ts = new AudioTimestamp();

        private TrackOut(AudioTrack t, boolean isFloat, int sampleRate, int burst) {
            super(sampleRate, burst);
            this.t = t;
            this.isFloat = isFloat;
            this.shorts = isFloat ? null : new short[4096 * 2];
        }

        /**
         * Some phones only grant the fast mixer path for 16-bit PCM or for game audio, so those are
         * tried in turn; the first track that gets it wins.
         */
        static AudioOutput open(int sampleRate, int burst) {
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
                    t = build(sampleRate, burst, c[0], c[1]);
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
            TrackOut o = new TrackOut(chosen, chosenFloat, sampleRate, burst);
            o.setBufferFrames(burst * 2);
            return o;
        }

        private static AudioTrack build(int sampleRate, int burst, int encoding, int usage) {
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

        @Override
        void start() {
            t.play();
        }

        @Override
        boolean write(float[] buf, int frames) {
            int r;
            if (isFloat) {
                r = t.write(buf, 0, frames * 2, AudioTrack.WRITE_BLOCKING);
            } else {
                int n = Math.min(frames * 2, shorts.length);
                for (int i = 0; i < n; i++) shorts[i] = (short) (buf[i] * 32767f);
                r = t.write(shorts, 0, n, AudioTrack.WRITE_BLOCKING);
            }
            return r != AudioTrack.ERROR_DEAD_OBJECT; // only a dead track needs reopening
        }

        @Override
        boolean timestamp(long[] out) {
            if (!t.getTimestamp(ts)) return false;
            out[0] = ts.framePosition;
            out[1] = ts.nanoTime;
            return true;
        }

        @Override
        int bufferFrames() {
            return t.getBufferSizeInFrames();
        }

        @Override
        int capacityFrames() {
            return t.getBufferCapacityInFrames();
        }

        @Override
        void setBufferFrames(int frames) {
            t.setBufferSizeInFrames(frames);
        }

        @Override
        int xruns() {
            return t.getUnderrunCount();
        }

        @Override
        int deviceId() {
            AudioDeviceInfo d = t.getRoutedDevice();
            return d != null ? d.getId() : 0;
        }

        @Override
        boolean fast() {
            return t.getPerformanceMode() == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY;
        }

        @Override
        String api() {
            return "AudioTrack";
        }

        @Override
        void close() {
            try {
                t.pause();
                t.flush();
                t.stop();
            } catch (IllegalStateException ignored) {
            }
            t.release();
        }
    }
}
