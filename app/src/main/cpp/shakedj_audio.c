/*
 * Thin JNI bridge to AAudio, Android's low-latency audio API (the one Oboe uses).
 *
 * libaaudio.so is loaded with dlopen at runtime, so this file needs no NDK headers or libraries:
 * the few AAudio declarations it uses are written out below. It uses no libc functions either,
 * which lets it be built with a plain clang (scripts/build-apk-without-gradle.sh) as well as with
 * the NDK (CMakeLists.txt, used by Gradle).
 *
 * Java renders audio and hands it over with blocking writes, exactly like AudioTrack.write,
 * so the engine logic stays the same for both outputs.
 */
#include <jni.h>
#include <stdint.h>

void *dlopen(const char *filename, int flags);
void *dlsym(void *handle, const char *symbol);

#define RTLD_NOW 2

typedef int32_t aaudio_result_t;
typedef struct AAudioStreamStruct AAudioStream;
typedef struct AAudioStreamBuilderStruct AAudioStreamBuilder;

enum {
    AAUDIO_DIRECTION_OUTPUT = 0,
    AAUDIO_FORMAT_PCM_FLOAT = 2,
    AAUDIO_SHARING_MODE_SHARED = 1,
    AAUDIO_PERFORMANCE_MODE_LOW_LATENCY = 12,
    AAUDIO_CONTENT_TYPE_MUSIC = 2,
    CLOCK_MONOTONIC_ID = 1,
};

static aaudio_result_t (*p_createStreamBuilder)(AAudioStreamBuilder **);
static void (*p_setDirection)(AAudioStreamBuilder *, int32_t);
static void (*p_setSharingMode)(AAudioStreamBuilder *, int32_t);
static void (*p_setPerformanceMode)(AAudioStreamBuilder *, int32_t);
static void (*p_setFormat)(AAudioStreamBuilder *, int32_t);
static void (*p_setChannelCount)(AAudioStreamBuilder *, int32_t);
static void (*p_setSampleRate)(AAudioStreamBuilder *, int32_t);
static void (*p_setUsage)(AAudioStreamBuilder *, int32_t);             /* API 28+, optional */
static void (*p_setContentType)(AAudioStreamBuilder *, int32_t);       /* API 28+, optional */
static aaudio_result_t (*p_openStream)(AAudioStreamBuilder *, AAudioStream **);
static aaudio_result_t (*p_builderDelete)(AAudioStreamBuilder *);
static aaudio_result_t (*p_requestStart)(AAudioStream *);
static aaudio_result_t (*p_requestStop)(AAudioStream *);
static aaudio_result_t (*p_close)(AAudioStream *);
static aaudio_result_t (*p_write)(AAudioStream *, const void *, int32_t, int64_t);
static int32_t (*p_getFramesPerBurst)(AAudioStream *);
static int32_t (*p_getSampleRate)(AAudioStream *);
static int32_t (*p_getSharingMode)(AAudioStream *);
static int32_t (*p_getPerformanceMode)(AAudioStream *);
static int32_t (*p_getDeviceId)(AAudioStream *);
static aaudio_result_t (*p_setBufferSizeInFrames)(AAudioStream *, int32_t);
static int32_t (*p_getBufferSizeInFrames)(AAudioStream *);
static int32_t (*p_getBufferCapacityInFrames)(AAudioStream *);
static int32_t (*p_getXRunCount)(AAudioStream *);
static aaudio_result_t (*p_getTimestamp)(AAudioStream *, int32_t, int64_t *, int64_t *);

#define MAX_SAMPLES 16384
static float g_buf[MAX_SAMPLES];

#define LOAD(var, name) if (!(*(void **) &(var) = dlsym(lib, name))) return JNI_FALSE

JNIEXPORT jboolean JNICALL
Java_com_shakedj_app_NativeAudio_nInit(JNIEnv *env, jclass cls) {
    void *lib = dlopen("libaaudio.so", RTLD_NOW);
    if (!lib) return JNI_FALSE;
    LOAD(p_createStreamBuilder, "AAudio_createStreamBuilder");
    LOAD(p_setDirection, "AAudioStreamBuilder_setDirection");
    LOAD(p_setSharingMode, "AAudioStreamBuilder_setSharingMode");
    LOAD(p_setPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
    LOAD(p_setFormat, "AAudioStreamBuilder_setFormat");
    LOAD(p_setChannelCount, "AAudioStreamBuilder_setChannelCount");
    LOAD(p_setSampleRate, "AAudioStreamBuilder_setSampleRate");
    LOAD(p_openStream, "AAudioStreamBuilder_openStream");
    LOAD(p_builderDelete, "AAudioStreamBuilder_delete");
    LOAD(p_requestStart, "AAudioStream_requestStart");
    LOAD(p_requestStop, "AAudioStream_requestStop");
    LOAD(p_close, "AAudioStream_close");
    LOAD(p_write, "AAudioStream_write");
    LOAD(p_getFramesPerBurst, "AAudioStream_getFramesPerBurst");
    LOAD(p_getSampleRate, "AAudioStream_getSampleRate");
    LOAD(p_getSharingMode, "AAudioStream_getSharingMode");
    LOAD(p_getPerformanceMode, "AAudioStream_getPerformanceMode");
    LOAD(p_getDeviceId, "AAudioStream_getDeviceId");
    LOAD(p_setBufferSizeInFrames, "AAudioStream_setBufferSizeInFrames");
    LOAD(p_getBufferSizeInFrames, "AAudioStream_getBufferSizeInFrames");
    LOAD(p_getBufferCapacityInFrames, "AAudioStream_getBufferCapacityInFrames");
    LOAD(p_getXRunCount, "AAudioStream_getXRunCount");
    LOAD(p_getTimestamp, "AAudioStream_getTimestamp");
    *(void **) &p_setUsage = dlsym(lib, "AAudioStreamBuilder_setUsage");
    *(void **) &p_setContentType = dlsym(lib, "AAudioStreamBuilder_setContentType");
    return JNI_TRUE;
}

static AAudioStream *S(jlong h) {
    return (AAudioStream *) (intptr_t) h;
}

/** Opens a shared, low-latency float stereo output stream. sampleRate 0 = device's native rate. */
JNIEXPORT jlong JNICALL
Java_com_shakedj_app_NativeAudio_nOpen(JNIEnv *env, jclass cls, jint sampleRate, jint usage) {
    AAudioStreamBuilder *b = 0;
    AAudioStream *s = 0;
    if (p_createStreamBuilder(&b) != 0 || !b) return 0;
    p_setDirection(b, AAUDIO_DIRECTION_OUTPUT);
    p_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    p_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    p_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    p_setChannelCount(b, 2);
    if (sampleRate > 0) p_setSampleRate(b, sampleRate);
    if (p_setUsage) p_setUsage(b, usage);
    if (p_setContentType) p_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    aaudio_result_t r = p_openStream(b, &s);
    p_builderDelete(b);
    if (r != 0 || !s) return 0;
    return (jlong) (intptr_t) s;
}

JNIEXPORT jint JNICALL
Java_com_shakedj_app_NativeAudio_nStart(JNIEnv *env, jclass cls, jlong h) {
    return p_requestStart(S(h));
}

JNIEXPORT void JNICALL
Java_com_shakedj_app_NativeAudio_nClose(JNIEnv *env, jclass cls, jlong h) {
    p_requestStop(S(h));
    p_close(S(h));
}

/** Blocking write of interleaved stereo frames. Returns frames written or a negative AAudio error. */
JNIEXPORT jint JNICALL
Java_com_shakedj_app_NativeAudio_nWrite(JNIEnv *env, jclass cls, jlong h, jfloatArray data, jint frames) {
    if (frames * 2 > MAX_SAMPLES) frames = MAX_SAMPLES / 2;
    (*env)->GetFloatArrayRegion(env, data, 0, frames * 2, g_buf);
    return p_write(S(h), g_buf, frames, (int64_t) 1000000000);
}

/** info[0..5] = sample rate, frames per burst, sharing mode, performance mode, device id, capacity. */
JNIEXPORT void JNICALL
Java_com_shakedj_app_NativeAudio_nInfo(JNIEnv *env, jclass cls, jlong h, jintArray info) {
    jint v[6];
    v[0] = p_getSampleRate(S(h));
    v[1] = p_getFramesPerBurst(S(h));
    v[2] = p_getSharingMode(S(h));
    v[3] = p_getPerformanceMode(S(h));
    v[4] = p_getDeviceId(S(h));
    v[5] = p_getBufferCapacityInFrames(S(h));
    (*env)->SetIntArrayRegion(env, info, 0, 6, v);
}

JNIEXPORT jint JNICALL
Java_com_shakedj_app_NativeAudio_nSetBufferSize(JNIEnv *env, jclass cls, jlong h, jint frames) {
    return p_setBufferSizeInFrames(S(h), frames);
}

JNIEXPORT jint JNICALL
Java_com_shakedj_app_NativeAudio_nGetBufferSize(JNIEnv *env, jclass cls, jlong h) {
    return p_getBufferSizeInFrames(S(h));
}

JNIEXPORT jint JNICALL
Java_com_shakedj_app_NativeAudio_nXRuns(JNIEnv *env, jclass cls, jlong h) {
    return p_getXRunCount(S(h));
}

/** out[0] = frame position, out[1] = CLOCK_MONOTONIC nanos when it was presented. */
JNIEXPORT jboolean JNICALL
Java_com_shakedj_app_NativeAudio_nTimestamp(JNIEnv *env, jclass cls, jlong h, jlongArray out) {
    int64_t pos = 0, nanos = 0;
    if (p_getTimestamp(S(h), CLOCK_MONOTONIC_ID, &pos, &nanos) != 0) return JNI_FALSE;
    jlong v[2];
    v[0] = (jlong) pos;
    v[1] = (jlong) nanos;
    (*env)->SetLongArrayRegion(env, out, 0, 2, v);
    return JNI_TRUE;
}
