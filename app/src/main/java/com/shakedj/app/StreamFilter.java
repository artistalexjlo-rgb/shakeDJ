package com.shakedj.app;

import android.media.audiofx.Equalizer;

/**
 * Experimental: approximates the tilt filter on the phone's whole output mix (session 0),
 * so it also colors a streaming app. Android deprecated global effects, so many phones refuse it;
 * {@link #open()} reports whether this one allows it.
 */
final class StreamFilter {
    private Equalizer eq;
    private float[] centers;
    private short minLevel;
    private float lastAmount = Float.NaN;

    boolean open() {
        try {
            eq = new Equalizer(0, 0);
            short bands = eq.getNumberOfBands();
            centers = new float[bands];
            for (short b = 0; b < bands; b++) centers[b] = eq.getCenterFreq(b) / 1000f;
            minLevel = eq.getBandLevelRange()[0];
            eq.setEnabled(true);
            return true;
        } catch (Throwable t) {
            close();
            return false;
        }
    }

    boolean isOpen() {
        return eq != null;
    }

    void apply(float amount) {
        if (eq == null || Math.abs(amount - lastAmount) < 0.02f) return;
        lastAmount = amount;
        try {
            for (short b = 0; b < centers.length; b++) {
                double octaves = 0;
                if (amount < -0.02f) {
                    double cutoff = 20000 * Math.pow(250.0 / 20000.0, -amount);
                    octaves = Math.log(centers[b] / cutoff) / Math.log(2);
                } else if (amount > 0.02f) {
                    double cutoff = 25 * Math.pow(4000.0 / 25.0, amount);
                    octaves = Math.log(cutoff / centers[b]) / Math.log(2);
                }
                int level = octaves > 0 ? (int) (-octaves * 900) : 0;
                eq.setBandLevel(b, (short) Math.max(minLevel, level));
            }
        } catch (Throwable ignored) {
        }
    }

    void close() {
        if (eq != null) {
            try {
                eq.setEnabled(false);
                eq.release();
            } catch (Throwable ignored) {
            }
        }
        eq = null;
        lastAmount = Float.NaN;
    }
}
