package com.shakedj.app;

import android.os.Build;

/** JNI entry points of libshakedj_audio.so (app/src/main/cpp/shakedj_audio.c). */
final class NativeAudio {
    static final int SHARING_EXCLUSIVE = 0;
    static final int PERFORMANCE_LOW_LATENCY = 12;
    static final int USAGE_MEDIA = 1;
    static final int ERROR_DISCONNECTED = -899;

    private static Boolean available;

    private NativeAudio() {}

    /** AAudio is usable from Android 8.1; on 8.0 it has known bugs (Oboe avoids it there too). */
    static synchronized boolean available() {
        if (available == null) {
            boolean ok = false;
            if (Build.VERSION.SDK_INT >= 27) {
                try {
                    System.loadLibrary("shakedj_audio");
                    ok = nInit();
                } catch (Throwable ignored) {
                }
            }
            available = ok;
        }
        return available;
    }

    static native boolean nInit();

    static native long nOpen(int sampleRate, int usage);

    static native int nStart(long h);

    static native void nClose(long h);

    static native int nWrite(long h, float[] data, int frames);

    static native void nInfo(long h, int[] info);

    static native int nSetBufferSize(long h, int frames);

    static native int nGetBufferSize(long h);

    static native int nXRuns(long h);

    static native boolean nTimestamp(long h, long[] out);
}
