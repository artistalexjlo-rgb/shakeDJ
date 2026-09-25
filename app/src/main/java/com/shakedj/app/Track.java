package com.shakedj.app;

/** Decoded audio kept in memory as interleaved stereo 16-bit PCM. Filled progressively by TrackDecoder. */
final class Track {
    final String name;
    final int sampleRate;
    final short[] data;
    final int capacityFrames;
    final int expectedFrames;

    /** Frames already decoded; the audio thread never reads past this. */
    volatile int decodedFrames;
    volatile boolean complete;
    volatile boolean truncated;

    Track(String name, int sampleRate, int capacityFrames, int expectedFrames) {
        this.name = name;
        this.sampleRate = sampleRate;
        this.capacityFrames = capacityFrames;
        this.expectedFrames = expectedFrames;
        this.data = new short[capacityFrames * 2];
    }

    double durationSec() {
        int frames = complete ? decodedFrames : Math.max(expectedFrames, decodedFrames);
        return frames / (double) sampleRate;
    }
}
