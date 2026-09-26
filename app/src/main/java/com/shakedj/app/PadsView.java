package com.shakedj.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/** Multi-touch drum pads that fire on touch-down for the lowest latency. */
final class PadsView extends View {
    interface Callbacks {
        /** {@code nanos}: touch-down time, CLOCK_MONOTONIC. */
        void onPad(int drum, long nanos);
    }

    private static final int COLS = 3, ROWS = 2;
    private static final int[] DRUMS = {
            DrumKit.KICK, DrumKit.SNARE, DrumKit.CLAP,
            DrumKit.HAT, DrumKit.OPEN_HAT, DrumKit.PERC};
    private static final String[] LABELS = {"БОЧКА", "СНЕЙР", "КЛЭП", "ХЭТ", "ОТКР. ХЭТ", "ПЕРК"};
    private static final int[] COLORS = {
            Color.rgb(255, 90, 60), Color.rgb(255, 170, 40), Color.rgb(250, 220, 70),
            Color.rgb(70, 200, 180), Color.rgb(64, 156, 255), Color.rgb(180, 110, 255)};

    private final Callbacks cb;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final long[] flash = new long[DRUMS.length];
    private final float dp;

    PadsView(Context ctx, Callbacks cb) {
        super(ctx);
        this.cb = cb;
        dp = ctx.getResources().getDisplayMetrics().density;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) MainActivity.unbuffered(this, e);
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            int pad = padAt(e.getX(idx), e.getY(idx));
            if (pad >= 0) {
                cb.onPad(DRUMS[pad], MainActivity.eventNanos(e));
                flash[pad] = SystemClock.uptimeMillis();
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                invalidate();
            }
        }
        return true;
    }

    private int padAt(float x, float y) {
        int col = (int) (x / (getWidth() / (float) COLS));
        int row = (int) (y / (getHeight() / (float) ROWS));
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;
        return row * COLS + col;
    }

    @Override
    protected void onDraw(Canvas c) {
        float cw = getWidth() / (float) COLS, ch = getHeight() / (float) ROWS, gap = 5 * dp;
        long now = SystemClock.uptimeMillis();
        boolean animating = false;
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(14 * dp);
        paint.setFakeBoldText(true);
        for (int i = 0; i < DRUMS.length; i++) {
            int col = i % COLS, row = i / COLS;
            rect.set(col * cw + gap, row * ch + gap, (col + 1) * cw - gap, (row + 1) * ch - gap);
            float k = Math.max(0f, 1f - (now - flash[i]) / 180f);
            if (k > 0) animating = true;
            int base = COLORS[i];
            int r = (int) (Color.red(base) * (0.25f + 0.75f * k));
            int g = (int) (Color.green(base) * (0.25f + 0.75f * k));
            int b = (int) (Color.blue(base) * (0.25f + 0.75f * k));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(r, g, b));
            c.drawRoundRect(rect, 12 * dp, 12 * dp, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * dp);
            paint.setColor(base);
            c.drawRoundRect(rect, 12 * dp, 12 * dp, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            c.drawText(LABELS[i], rect.centerX(), rect.centerY() + 5 * dp, paint);
        }
        if (animating) postInvalidateOnAnimation();
    }
}
