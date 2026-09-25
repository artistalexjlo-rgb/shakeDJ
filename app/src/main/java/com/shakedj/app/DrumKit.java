package com.shakedj.app;

import java.util.Random;

/** Procedurally synthesized drum one-shots, so the app needs no sample files. */
final class DrumKit {
    static final int KICK = 0, SNARE = 1, CLAP = 2, HAT = 3, OPEN_HAT = 4, PERC = 5, CRASH = 6;
    static final int COUNT = 7;

    /** Stereo placement, -1 = left, 1 = right. */
    static final float[] PAN = {0f, 0f, 0.08f, 0.3f, 0.22f, -0.3f, 0f};
    static final float[] LEVEL = {1.0f, 0.75f, 0.7f, 0.35f, 0.35f, 0.6f, 0.4f};

    private DrumKit() {}

    static float[][] build(int fs) {
        float[][] kit = new float[COUNT][];
        Random rnd = new Random(1234);
        kit[KICK] = kick(fs);
        kit[SNARE] = snare(fs, rnd);
        kit[CLAP] = clap(fs, rnd);
        kit[HAT] = hat(fs, rnd, 0.07, 55);
        kit[OPEN_HAT] = hat(fs, rnd, 0.5, 8);
        kit[PERC] = perc(fs, rnd);
        kit[CRASH] = crash(fs, rnd);
        for (int i = 0; i < COUNT; i++) normalize(kit[i], LEVEL[i]);
        return kit;
    }

    private static float[] kick(int fs) {
        int n = (int) (0.5 * fs);
        float[] o = new float[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            double f = 46 + 120 * Math.exp(-t * 30);
            ph += 2 * Math.PI * f / fs;
            double body = Math.sin(ph) * Math.exp(-t * 7);
            double click = Math.sin(ph * 6) * Math.exp(-t * 350) * 0.35;
            o[i] = (float) Math.tanh((body + click) * 1.8);
        }
        return o;
    }

    private static float[] snare(int fs, Random rnd) {
        int n = (int) (0.3 * fs);
        float[] o = new float[n];
        double p1 = 0, p2 = 0, prev = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            p1 += 2 * Math.PI * 185 / fs;
            p2 += 2 * Math.PI * 330 / fs;
            double tone = (Math.sin(p1) + 0.5 * Math.sin(p2)) * Math.exp(-t * 22) * 0.55;
            double w = rnd.nextDouble() * 2 - 1;
            double hp = w - prev;
            prev = w;
            double noise = hp * Math.exp(-t * 13) * 0.7;
            o[i] = (float) (tone + noise);
        }
        return o;
    }

    private static float[] clap(int fs, Random rnd) {
        int n = (int) (0.35 * fs);
        float[] o = new float[n];
        // Band-pass the noise around 1.1 kHz with a state-variable filter.
        double g = Math.tan(Math.PI * 1100 / fs), k = 1.2;
        double a1 = 1 / (1 + g * (g + k)), a2 = g * a1, a3 = g * a2, ic1 = 0, ic2 = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            double env;
            if (t < 0.03) {
                double local = t % 0.01;
                env = Math.exp(-local * 250);
            } else {
                env = Math.exp(-(t - 0.03) * 16) * 0.8;
            }
            double v0 = rnd.nextDouble() * 2 - 1;
            double v3 = v0 - ic2, v1 = a1 * ic1 + a2 * v3, v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1;
            ic2 = 2 * v2 - ic2;
            o[i] = (float) (v1 * env);
        }
        return o;
    }

    private static float[] hat(int fs, Random rnd, double len, double decay) {
        int n = (int) (len * fs);
        float[] o = new float[n];
        double x1 = 0, y1 = 0, x2 = 0, y2 = 0;
        double c = Math.exp(-2 * Math.PI * 7000.0 / fs);
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            double w = rnd.nextDouble() * 2 - 1;
            // Two cascaded one-pole high-passes.
            double h1 = c * (y1 + w - x1);
            x1 = w;
            y1 = h1;
            double h2 = c * (y2 + h1 - x2);
            x2 = h1;
            y2 = h2;
            o[i] = (float) (h2 * Math.exp(-t * decay));
        }
        return o;
    }

    private static float[] perc(int fs, Random rnd) {
        int n = (int) (0.28 * fs);
        float[] o = new float[n];
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            double f = 330 + 90 * Math.exp(-t * 45);
            ph += 2 * Math.PI * f / fs;
            double body = Math.sin(ph) * Math.exp(-t * 16);
            double slap = (rnd.nextDouble() * 2 - 1) * Math.exp(-t * 220) * 0.4;
            o[i] = (float) (body + slap);
        }
        return o;
    }

    private static float[] crash(int fs, Random rnd) {
        int n = (int) (1.8 * fs);
        float[] o = new float[n];
        double x1 = 0, y1 = 0;
        double c = Math.exp(-2 * Math.PI * 3500.0 / fs);
        double[] ph = new double[4];
        double[] fr = {540, 803, 1187, 1603};
        for (int i = 0; i < n; i++) {
            double t = i / (double) fs;
            double w = rnd.nextDouble() * 2 - 1;
            double h = c * (y1 + w - x1);
            x1 = w;
            y1 = h;
            double metal = 0;
            for (int j = 0; j < 4; j++) {
                ph[j] += fr[j] / fs;
                metal += (ph[j] % 1.0) < 0.5 ? 1 : -1;
            }
            o[i] = (float) ((h + metal * 0.04) * Math.exp(-t * 2.6));
        }
        return o;
    }

    private static void normalize(float[] s, float level) {
        float peak = 1e-6f;
        for (float v : s) peak = Math.max(peak, Math.abs(v));
        float g = level / peak;
        int fade = Math.min(s.length, 256);
        for (int i = 0; i < s.length; i++) {
            float v = s[i] * g;
            int left = s.length - 1 - i;
            if (left < fade) v *= left / (float) fade;
            s[i] = v;
        }
    }
}
