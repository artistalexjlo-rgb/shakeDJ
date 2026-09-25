package com.shakedj.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/** Decodes any format the phone's MediaCodec supports into a {@link Track}, on its own thread. */
final class TrackDecoder {
    interface Listener {
        /** Called as soon as the first audio is decoded; playback can start right away. */
        void onReady(Track track);

        /** Tempo and the position of a downbeat ("one"); bpm is 0 if it couldn't be detected. */
        void onComplete(Track track, float bpm, double downbeatSec);

        void onError(String message);
    }

    private static final int MAX_SECONDS = 600;

    private volatile boolean cancelled;

    void cancel() {
        cancelled = true;
    }

    void start(final Context ctx, final Uri uri, final String name, final Listener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    decode(ctx, uri, name, listener);
                } catch (OutOfMemoryError e) {
                    listener.onError("Трек слишком длинный для памяти телефона");
                } catch (Exception e) {
                    listener.onError("Не удалось открыть файл: " + e.getMessage());
                }
            }
        }, "ShakeDJ-decoder").start();
    }

    private void decode(Context ctx, Uri uri, String name, Listener listener) throws Exception {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(ctx, uri, null);
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    ex.selectTrack(i);
                    fmt = f;
                    break;
                }
            }
            if (fmt == null) {
                listener.onError("В файле нет аудиодорожки");
                return;
            }
            long durUs = fmt.containsKey(MediaFormat.KEY_DURATION) ? fmt.getLong(MediaFormat.KEY_DURATION) : -1;

            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inEos = false, outEos = false;
            Track track = null;
            int channels = 2, encoding = AudioFormat.ENCODING_PCM_16BIT;

            while (!outEos && !cancelled) {
                if (!inEos) {
                    int ii = codec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer b = codec.getInputBuffer(ii);
                        int size = ex.readSampleData(b, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(ii, 0, size, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = codec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (oi >= 0 && track == null)) {
                    MediaFormat of = codec.getOutputFormat();
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding = of.containsKey(MediaFormat.KEY_PCM_ENCODING)
                            ? of.getInteger(MediaFormat.KEY_PCM_ENCODING) : AudioFormat.ENCODING_PCM_16BIT;
                    if (track == null) {
                        int rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int expected = durUs > 0 ? (int) (durUs * rate / 1_000_000L) : 0;
                        int cap = Math.min(MAX_SECONDS * rate, durUs > 0 ? expected + rate * 3 : MAX_SECONDS * rate);
                        track = new Track(name, rate, cap, expected);
                        listener.onReady(track);
                    }
                }
                if (oi >= 0) {
                    ByteBuffer ob = codec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        append(track, ob.slice().order(ByteOrder.nativeOrder()), channels, encoding);
                    }
                    codec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outEos = true;
                    if (track.truncated) outEos = true;
                }
            }
            if (track != null && !cancelled) {
                track.complete = true;
                double[] grid = estimateGrid(track);
                listener.onComplete(track, (float) grid[0], grid[1]);
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                }
                codec.release();
            }
            ex.release();
        }
    }

    private static void append(Track t, ByteBuffer b, int ch, int encoding) {
        int w = t.decodedFrames;
        short[] d = t.data;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            FloatBuffer fb = b.asFloatBuffer();
            int frames = fb.remaining() / ch;
            for (int f = 0; f < frames; f++) {
                if (w >= t.capacityFrames) {
                    t.truncated = true;
                    break;
                }
                float l = fb.get(f * ch), r = ch > 1 ? fb.get(f * ch + 1) : l;
                d[w * 2] = toShort(l);
                d[w * 2 + 1] = toShort(r);
                w++;
            }
        } else {
            ShortBuffer sb = b.asShortBuffer();
            int frames = sb.remaining() / ch;
            for (int f = 0; f < frames; f++) {
                if (w >= t.capacityFrames) {
                    t.truncated = true;
                    break;
                }
                short l = sb.get(f * ch);
                d[w * 2] = l;
                d[w * 2 + 1] = ch > 1 ? sb.get(f * ch + 1) : l;
                w++;
            }
        }
        t.decodedFrames = w;
    }

    private static short toShort(float v) {
        int s = Math.round(v * 32767f);
        return (short) Math.max(-32768, Math.min(32767, s));
    }

    /** How many hops the detected onset peak runs ahead of the audible attack, given the window. */
    private static final double ONSET_DELAY_HOPS = 4; // measured on synthetic tracks: ~20 ms

    /**
     * Beat grid of the track (first two minutes are analysed): {bpm, downbeatSec}, or {0, 0}.
     * Tempo comes from the autocorrelation of an onset envelope, the beat phase from the offset
     * that best lines up with onsets, and the "one" from the beat of the bar with the strongest
     * bass attacks. The user can re-mark the "one" in the app if the guess is wrong.
     */
    static double[] estimateGrid(Track t) {
        int rate = t.sampleRate;
        int hop = Math.max(1, rate / 200); // 5 ms
        int frames = Math.min(t.decodedFrames, rate * 120);
        int win = 5; // 25 ms windows, so the rectified bass doesn't ripple the envelope
        int nh = frames / hop - win;
        if (nh < 800) return new double[]{0, 0};
        long[] hopSum = new long[nh + win];
        double[] bassSum = new double[nh + win];
        short[] d = t.data;
        double lpA = 1 - Math.exp(-2 * Math.PI * 120.0 / rate), lp = 0;
        for (int h = 0; h < nh + win; h++) {
            long sum = 0;
            double bass = 0;
            int base = h * hop * 2;
            for (int j = 0; j < hop; j++) {
                int l = d[base + 2 * j], r = d[base + 2 * j + 1];
                sum += Math.abs(l) + Math.abs(r);
                lp += lpA * ((l + r) - lp);
                bass += Math.abs(lp);
            }
            hopSum[h] = sum;
            bassSum[h] = bass;
        }
        double[] all = new double[nh + win];
        for (int h = 0; h < nh + win; h++) all[h] = hopSum[h];
        float[] onset = onsets(all, nh, win, hop);
        float[] bassOnset = onsets(bassSum, nh, win, hop);

        int minLag = 200 * 60 / 185, maxLag = 200 * 60 / 65;
        double[] raw = new double[maxLag + 2];
        int best = -1;
        double bestScore = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double s = 0;
            for (int h = 0; h + lag < nh; h++) s += onset[h] * onset[h + lag];
            raw[lag] = s / (nh - lag);
            // Prefer common dance tempos when choosing between octaves...
            double oct = Math.log(12000.0 / lag / 120.0) / Math.log(2);
            double weighted = raw[lag] * Math.exp(-0.5 * (oct / 0.9) * (oct / 0.9));
            if (best < 0 || weighted > bestScore) {
                best = lag;
                bestScore = weighted;
            }
        }
        // ...but locate the exact peak on the unweighted curve, which the weighting would skew.
        for (int lag = Math.max(minLag, best - 3); lag <= Math.min(maxLag, best + 3); lag++) {
            if (raw[lag] > raw[best]) best = lag;
        }
        double lag = best;
        if (best > minLag && best < maxLag) {
            double a = raw[best - 1], b = raw[best], c = raw[best + 1];
            double den = a - 2 * b + c;
            if (den != 0) lag = best + 0.5 * (a - c) / den;
        }
        double bpm = 60.0 * rate / hop / lag;
        while (bpm < 80) bpm *= 2;
        while (bpm > 180) bpm /= 2;
        bpm = Math.round(bpm * 10) / 10.0;

        // Beat phase: the offset whose comb of beats collects the most onset energy.
        double period = 60.0 * rate / hop / bpm;
        int phase = 0;
        double phaseScore = -1;
        for (int o = 0; o < (int) Math.ceil(period); o++) {
            double s = 0;
            for (double x = o; x < nh - 1; x += period) s += onset[(int) Math.round(x)];
            if (s > phaseScore) {
                phaseScore = s;
                phase = o;
            }
        }
        // Downbeat: which of the four beats gets the heaviest bass attacks.
        int one = 0;
        double oneScore = -1;
        for (int j = 0; j < 4; j++) {
            double s = 0;
            for (double x = phase + j * period; x < nh - 1; x += 4 * period) {
                int i = (int) Math.round(x);
                s += bassOnset[i] + 0.5 * onset[i];
            }
            if (s > oneScore) {
                oneScore = s;
                one = j;
            }
        }
        double downbeatHop = phase + one * period + ONSET_DELAY_HOPS;
        return new double[]{bpm, downbeatHop * hop / rate};
    }


    /** Positive, lightly smoothed slope of the log energy over sliding windows of {@code win} hops. */
    private static float[] onsets(double[] hopSums, int nh, int win, int hop) {
        float[] env = new float[nh];
        for (int h = 0; h < nh; h++) {
            double sum = 0;
            for (int j = 0; j < win; j++) sum += hopSums[h + j];
            env[h] = (float) Math.log1p(sum / (hop * win) / 100.0);
        }
        float[] diff = new float[nh];
        for (int h = 1; h < nh; h++) diff[h] = Math.max(0f, env[h] - env[h - 1]);
        float[] onset = new float[nh];
        for (int h = 1; h + 1 < nh; h++) onset[h] = 0.25f * diff[h - 1] + 0.5f * diff[h] + 0.25f * diff[h + 1];
        return onset;
    }
}
