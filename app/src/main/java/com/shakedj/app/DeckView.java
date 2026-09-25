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

/**
 * The "deck": a spinning record that visualizes playback and takes the gestures.
 * Tap = kick, extra finger = clap, vertical drag = tempo bend (returns on release),
 * horizontal drag = scratch, hold still = beat loop.
 */
final class DeckView extends View {
    interface Callbacks {
        void onDeckTap();

        void onExtraFingerTap();

        /** Speed factor and how quickly the engine glides to it. */
        void onSpeed(float speed, float glideSec);

        void onHoldStart();

        void onHoldEnd();
    }

    private static final int NONE = 0, BEND = 1, SCRATCH = 2, HOLD = 3;
    private static final long HOLD_MS = 450;

    private final AudioEngine engine;
    private final Callbacks cb;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;

    String title = "Трек не загружен";
    String hint = "";
    float bpmShown = 120f;

    private int mode = NONE;
    private float downX, downY, lastX;
    private long downT, lastMoveT;
    private float scratchVel;
    private float bendAmount;

    DeckView(Context ctx, AudioEngine engine, Callbacks cb) {
        super(ctx);
        this.engine = engine;
        this.cb = cb;
        dp = ctx.getResources().getDisplayMetrics().density;
        setHapticFeedbackEnabled(true);
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long now = SystemClock.uptimeMillis();
        tick(now);

        c.drawColor(Color.rgb(20, 18, 28));
        float cx = w / 2f, cy = h / 2f + 10 * dp;
        float rad = Math.min(w, h) * 0.36f;

        // Record.
        float angle = (float) ((engine.positionSec * 200.0) % 360.0);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(12, 12, 14));
        c.drawCircle(cx, cy, rad, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1 * dp);
        paint.setColor(Color.rgb(40, 40, 46));
        for (float gr = rad * 0.45f; gr < rad * 0.97f; gr += 5 * dp) c.drawCircle(cx, cy, gr, paint);

        float filter = engine.filterNow;
        int accent = filter < -0.05f ? Color.rgb(64, 156, 255)
                : filter > 0.05f ? Color.rgb(255, 150, 40) : Color.rgb(255, 90, 60);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        c.drawCircle(cx, cy, rad * 0.32f, paint);
        c.save();
        c.rotate(angle, cx, cy);
        paint.setColor(Color.rgb(250, 250, 250));
        c.drawRect(cx - 2 * dp, cy - rad * 0.95f, cx + 2 * dp, cy - rad * 0.36f, paint);
        c.restore();
        paint.setColor(Color.rgb(20, 18, 28));
        c.drawCircle(cx, cy, 4 * dp, paint);

        // Texts.
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(Color.rgb(230, 230, 235));
        paint.setTextSize(15 * dp);
        c.drawText(ellipsize(title, w - 24 * dp), cx, 26 * dp, paint);

        paint.setTextSize(13 * dp);
        paint.setColor(Color.rgb(160, 160, 175));
        Track t = engine.currentTrack();
        String time = t != null ? fmt(engine.positionSec) + " / " + fmt(t.durationSec()) : "режим поверх стриминга";
        c.drawText(time + "   ·   " + String.format("%.1f", bpmShown) + " BPM", cx, 46 * dp, paint);

        float sp = engine.speedNow;
        if (Math.abs(sp - 1f) > 0.01f) {
            paint.setColor(Color.WHITE);
            paint.setTextSize(22 * dp);
            c.drawText(String.format("%+d%%", Math.round((sp - 1f) * 100)), cx, cy + rad + 30 * dp, paint);
        }

        int fx = engine.fxNow;
        long since = System.currentTimeMillis() - engine.fxStartedAtMs;
        if (fx != AudioEngine.FX_NONE || since < 600) {
            int shown = fx != AudioEngine.FX_NONE ? fx : lastFx;
            if (shown >= 0) {
                lastFx = shown;
                paint.setColor(Color.WHITE);
                paint.setTextSize(34 * dp);
                paint.setFakeBoldText(true);
                c.drawText(AudioEngine.FX_NAMES[shown], cx, cy + 12 * dp, paint);
                paint.setFakeBoldText(false);
            }
        }

        if (!hint.isEmpty()) {
            paint.setTextSize(12 * dp);
            paint.setColor(Color.rgb(140, 140, 155));
            c.drawText(ellipsize(hint, w - 24 * dp), cx, h - 30 * dp, paint);
        }

        // Filter meter: blue to the left for low-pass, orange to the right for high-pass.
        float barY = h - 14 * dp, barW = w * 0.7f;
        paint.setColor(Color.rgb(45, 45, 55));
        rect.set(cx - barW / 2, barY - 3 * dp, cx + barW / 2, barY + 3 * dp);
        c.drawRoundRect(rect, 3 * dp, 3 * dp, paint);
        paint.setColor(accent);
        float fx0 = cx, fx1 = cx + filter * barW / 2;
        rect.set(Math.min(fx0, fx1), barY - 3 * dp, Math.max(fx0, fx1), barY + 3 * dp);
        c.drawRect(rect, paint);
        paint.setColor(Color.WHITE);
        c.drawRect(cx - 1 * dp, barY - 6 * dp, cx + 1 * dp, barY + 6 * dp, paint);

        postInvalidateOnAnimation();
    }

    private int lastFx = -1;

    private void tick(long now) {
        if (mode == NONE && downT > 0 && now - downT > HOLD_MS && engine.currentTrack() != null && engine.playing) {
            mode = HOLD;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            cb.onHoldStart();
        }
        if (mode == SCRATCH && now - lastMoveT > 40) {
            // Finger is resting on the record: it stops.
            scratchVel = 0;
            cb.onSpeed(0f, 0.03f);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = e.getX();
                downY = e.getY();
                downT = lastMoveT = now;
                mode = NONE;
                scratchVel = 0;
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                cb.onExtraFingerTap();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX(), y = e.getY();
                float slop = 14 * dp;
                if (mode == NONE) {
                    float dx = x - downX, dy = y - downY;
                    if (Math.hypot(dx, dy) > slop) {
                        if (engine.currentTrack() == null) {
                            hint = "Темп и скретч работают с загруженным треком";
                            downT = 0;
                            mode = -1;
                        } else {
                            mode = Math.abs(dy) >= Math.abs(dx) ? BEND : SCRATCH;
                            lastX = x;
                            lastMoveT = now;
                        }
                    }
                }
                if (mode == BEND) {
                    float travel = getHeight() * 0.45f;
                    bendAmount = Math.max(-1f, Math.min(1f, (y - downY) / travel));
                    float speed = bendAmount > 0 ? 1f - 0.6f * bendAmount : 1f - 0.5f * bendAmount;
                    cb.onSpeed(speed, 0.08f);
                } else if (mode == SCRATCH) {
                    long dt = Math.max(1, now - lastMoveT);
                    float v = (x - lastX) / (dt / 1000f);
                    scratchVel += (v - scratchVel) * 0.5f;
                    // A quarter of the screen width per second equals normal speed.
                    cb.onSpeed(Math.max(-6f, Math.min(6f, scratchVel / (getWidth() * 0.25f))), 0.02f);
                    lastX = x;
                    lastMoveT = now;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean tap = mode == NONE && now - downT < 300 && e.getActionMasked() == MotionEvent.ACTION_UP;
                if (tap) cb.onDeckTap();
                if (mode == BEND) cb.onSpeed(1f, 0.35f);
                if (mode == SCRATCH) cb.onSpeed(1f, 0.05f);
                if (mode == HOLD) cb.onHoldEnd();
                mode = NONE;
                downT = 0;
                return true;
            }
            default:
                return true;
        }
    }

    private String ellipsize(String s, float max) {
        if (paint.measureText(s) <= max) return s;
        int n = s.length();
        while (n > 1 && paint.measureText(s, 0, n) + paint.measureText("…") > max) n--;
        return s.substring(0, n) + "…";
    }

    private static String fmt(double sec) {
        int s = (int) Math.max(0, sec);
        return String.format("%d:%02d", s / 60, s % 60);
    }
}
