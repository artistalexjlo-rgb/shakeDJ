package com.shakedj.app;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_OPEN = 1;
    private static final float SHAKE_THRESHOLD = 14f;
    private static final float TILT_DEAD_ZONE_DEG = 15f;
    private static final float TILT_FULL_DEG = 55f;

    // The engine outlives configuration changes so the music doesn't stop.
    private static AudioEngine sEngine;
    private static TrackDecoder sDecoder;

    private AudioEngine engine;
    private final StreamFilter streamFilter = new StreamFilter();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private DeckView deck;
    private Button btnPlay, btnBpm, btnBreak;
    private SeekBar seek;
    private boolean seeking;

    private SensorManager sensors;
    private boolean hasGravity, hasLinear;
    private final float[] grav = {0f, 9.81f, 0f};
    private final long[] peaks = new long[4];
    private int peakIdx;
    private long lastPeakMs, cooldownUntilMs, lastStreamFilterMs;
    private boolean tiltEnabled = true;

    /** -1 = cycle through all break types. */
    private int breakMode = -1;
    private int autoBreak;
    private float trackBpm;
    private double trackDownbeatSec;
    private boolean userBpm;
    private final long[] taps = new long[5];
    private int tapCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (sEngine == null) {
            sEngine = new AudioEngine(getApplicationContext());
            sEngine.start();
        }
        engine = sEngine;
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        buildUi();
        handleIntent(getIntent());
        ui.post(uiTick);
    }

    // ---- UI ---------------------------------------------------------------------------------

    private void buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(20, 18, 28));
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                int l, t, r, b;
                if (Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets i = in.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    l = i.left;
                    t = i.top;
                    r = i.right;
                    b = i.bottom;
                } else {
                    l = in.getSystemWindowInsetLeft();
                    t = in.getSystemWindowInsetTop();
                    r = in.getSystemWindowInsetRight();
                    b = in.getSystemWindowInsetBottom();
                }
                int p = dp(6);
                v.setPadding(l + p, t + p, r + p, b + p);
                return in;
            }
        });

        LinearLayout row1 = row();
        Button btnOpen = button("Трек…");
        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickTrack();
            }
        });
        btnPlay = button("▶");
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (engine.currentTrack() == null) {
                    pickTrack();
                    return;
                }
                engine.playing = !engine.playing;
                refreshPlay();
            }
        });
        btnBpm = button("120 BPM");
        // Taps are timed on touch-down with the event's own timestamp, not on the later click.
        btnBpm.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    unbuffered(v, e);
                    tapTempo(eventNanos(e));
                }
                return false;
            }
        });
        btnBpm.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                userBpm = false;
                tapCount = 0;
                setBpm(trackBpm > 0 ? trackBpm : 120f);
                Track t = engine.currentTrack();
                if (t != null && trackBpm > 0) engine.setTrackGrid(t, trackDownbeatSec);
                toast("Темп и сетка сброшены на автоопределение");
                return true;
            }
        });
        Button btnOne = button("1");
        btnOne.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    unbuffered(v, e);
                    engine.markDownbeat(eventNanos(e));
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                }
                return false;
            }
        });
        btnBreak = button("Брейк: авто");
        btnBreak.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                breakMode = breakMode + 1 >= AudioEngine.BREAK_TYPES ? -1 : breakMode + 1;
                btnBreak.setText("Брейк: " + (breakMode < 0 ? "авто" : AudioEngine.FX_NAMES[breakMode].toLowerCase()));
            }
        });
        row1.addView(btnOpen, weight(1f));
        row1.addView(btnPlay, weight(0.6f));
        row1.addView(btnBpm, weight(1f));
        row1.addView(btnOne, weight(0.5f));
        row1.addView(btnBreak, weight(1.3f));
        root.addView(row1);

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {}

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                Track t = engine.currentTrack();
                if (t != null) engine.seek(s.getProgress() / 1000.0 * t.durationSec());
            }
        });
        root.addView(seek);

        LinearLayout row2 = row();
        CheckBox tilt = check("Фильтр наклоном", true);
        tilt.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                tiltEnabled = on;
                if (!on) setFilter(0f);
            }
        });
        CheckBox stream = check("…и на стриминг (эксп.)", false);
        stream.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                if (!on) {
                    streamFilter.close();
                } else if (!streamFilter.open()) {
                    b.setChecked(false);
                    toast("Этот телефон не даёт фильтровать звук других приложений");
                }
            }
        });
        row2.addView(tilt, weight(1f));
        row2.addView(stream, weight(1f));
        root.addView(row2);

        TextView help = new TextView(this);
        help.setTextColor(Color.rgb(150, 150, 165));
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        help.setText("Тряхни — брейк (с доли до «раза») · наклон вбок — фильтр, экраном вниз — «под водой» · "
                + "тап — бочка, второй палец — клэп · тяни вниз/вверх — темп · вбок — скретч · держи — луп · "
                + "«1» — отметить первую долю, BPM — стучи темп начиная с «раза»");
        help.setPadding(dp(4), 0, dp(4), dp(4));
        root.addView(help);

        deck = new DeckView(this, engine, new DeckView.Callbacks() {
            @Override
            public void onDeckTap() {
                engine.hit(DrumKit.KICK, 1f);
            }

            @Override
            public void onExtraFingerTap() {
                engine.hit(DrumKit.CLAP, 0.9f);
            }

            @Override
            public void onSpeed(float speed, float glideSec) {
                engine.speedGlideSec = glideSec;
                engine.speedTarget = speed;
            }

            @Override
            public void onHoldStart() {
                engine.startLoop();
            }

            @Override
            public void onHoldEnd() {
                engine.stopLoop();
            }
        });
        root.addView(deck, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        PadsView pads = new PadsView(this, new PadsView.Callbacks() {
            @Override
            public void onPad(int drum) {
                engine.hit(drum, 1f);
            }
        });
        root.addView(pads, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.62f));

        setContentView(root);
        setBpm(engine.bpm);
        Track t = engine.currentTrack();
        if (t != null) deck.title = t.name;
        refreshPlay();
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setSingleLine(true);
        return b;
    }

    private CheckBox check(String text, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(text);
        c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        c.setTextColor(Color.rgb(220, 220, 228));
        c.setChecked(on);
        return c;
    }

    private static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshPlay() {
        btnPlay.setText(engine.playing ? "❚❚" : "▶");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private final Runnable uiTick = new Runnable() {
        @Override
        public void run() {
            Track t = engine.currentTrack();
            if (t != null && !seeking) {
                double dur = t.durationSec();
                seek.setProgress(dur > 0 ? (int) (engine.positionSec / dur * 1000) : 0);
                seek.setSecondaryProgress((int) (t.decodedFrames / (double) t.sampleRate / Math.max(1e-3, dur) * 1000));
            }
            refreshPlay();
            ui.postDelayed(this, 200);
        }
    };

    // ---- Tempo ------------------------------------------------------------------------------

    private void setBpm(float bpm) {
        engine.bpm = bpm;
        deck.bpmShown = bpm;
        btnBpm.setText(String.format("%.0f BPM", bpm));
    }

    /**
     * Tap tempo. The first tap of a series marks the "one" (start tapping on it), the following
     * taps set the tempo, so the grid stays anchored to where the user started.
     */
    private void tapTempo(long nanos) {
        if (tapCount > 0 && nanos - taps[(tapCount - 1) % taps.length] > 2_000_000_000L) tapCount = 0;
        if (tapCount == 0) engine.markDownbeat(nanos);
        taps[tapCount % taps.length] = nanos;
        tapCount++;
        int n = Math.min(tapCount, taps.length);
        if (n >= 2) {
            long first = taps[(tapCount - n) % taps.length];
            float bpm = 60e9f * (n - 1) / (nanos - first);
            if (bpm >= 50 && bpm <= 220) {
                userBpm = true;
                setBpm(bpm);
            }
        }
        engine.hit(DrumKit.HAT, 0.7f);
    }

    /** Touch-down time on the CLOCK_MONOTONIC timeline shared with System.nanoTime and audio timestamps. */
    private static long eventNanos(MotionEvent e) {
        if (Build.VERSION.SDK_INT >= 34) return e.getEventTimeNanos();
        return e.getEventTime() * 1_000_000L;
    }

    /** Asks for this gesture's events without waiting for the next display frame. */
    static void unbuffered(View v, MotionEvent e) {
        if (Build.VERSION.SDK_INT >= 30) v.requestUnbufferedDispatch(e);
    }

    // ---- Tracks -----------------------------------------------------------------------------

    private void pickTrack() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        startActivityForResult(i, REQ_OPEN);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OPEN && resultCode == RESULT_OK && data != null && data.getData() != null) {
            loadTrack(data.getData());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) uri = intent.getData();
        else if (Intent.ACTION_SEND.equals(intent.getAction())) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri != null) {
            intent.setAction(null);
            loadTrack(uri);
        }
    }

    private void loadTrack(Uri uri) {
        if (sDecoder != null) sDecoder.cancel();
        engine.playing = false;
        engine.setTrack(null);
        final String name = displayName(uri);
        deck.title = "Загрузка: " + name;
        final TrackDecoder dec = new TrackDecoder();
        sDecoder = dec;
        dec.start(getApplicationContext(), uri, name, new TrackDecoder.Listener() {
            @Override
            public void onReady(final Track track) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sDecoder != dec) return;
                        engine.setTrack(track);
                        engine.playing = true;
                        deck.title = name;
                        trackBpm = 0;
                        userBpm = false;
                        tapCount = 0;
                    }
                });
            }

            @Override
            public void onComplete(final Track track, final float bpm, final double downbeatSec) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (sDecoder != dec) return;
                        trackBpm = bpm;
                        trackDownbeatSec = downbeatSec;
                        if (!userBpm && bpm > 0) {
                            setBpm(bpm);
                            engine.setTrackGrid(track, downbeatSec);
                        }
                        if (track.truncated) toast("Трек длиннее 10 минут — загружено начало");
                    }
                });
            }

            @Override
            public void onError(final String message) {
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        deck.title = "Трек не загружен";
                        toast(message);
                    }
                });
            }
        });
    }

    private String displayName(Uri uri) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = uri.getLastPathSegment();
        if (name == null) name = "трек";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ---- Sensors ----------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        Sensor g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        Sensor lin = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        hasGravity = g != null;
        hasLinear = lin != null;
        if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        if (lin != null) sensors.registerListener(this, lin, SensorManager.SENSOR_DELAY_GAME);
        if (acc != null && (!hasGravity || !hasLinear)) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensors.unregisterListener(this);
        setFilter(0f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        streamFilter.close();
        if (isFinishing()) {
            if (sDecoder != null) sDecoder.cancel();
            sDecoder = null;
            sEngine.release();
            sEngine = null;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        switch (e.sensor.getType()) {
            case Sensor.TYPE_GRAVITY:
                grav[0] = v[0];
                grav[1] = v[1];
                grav[2] = v[2];
                updateTilt();
                break;
            case Sensor.TYPE_LINEAR_ACCELERATION:
                onMotion((float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
                break;
            case Sensor.TYPE_ACCELEROMETER:
                if (!hasGravity) {
                    for (int i = 0; i < 3; i++) grav[i] += (v[i] - grav[i]) * 0.1f;
                    updateTilt();
                }
                if (!hasLinear) {
                    float x = v[0] - grav[0], y = v[1] - grav[1], z = v[2] - grav[2];
                    onMotion((float) Math.sqrt(x * x + y * y + z * z));
                }
                break;
            default:
                break;
        }
    }

    private void updateTilt() {
        if (!tiltEnabled) return;
        float gx = grav[0], gy = grav[1], gz = grav[2];
        float amount;
        if (gz < -7f) {
            amount = -1f; // face down: everything muffled
        } else {
            double roll = Math.toDegrees(Math.atan2(-gx, Math.sqrt(gy * gy + gz * gz)));
            double a = (Math.abs(roll) - TILT_DEAD_ZONE_DEG) / (TILT_FULL_DEG - TILT_DEAD_ZONE_DEG);
            amount = (float) (Math.signum(roll) * Math.max(0, Math.min(1, a)));
        }
        setFilter(amount);
    }

    private void setFilter(float amount) {
        engine.filterTarget = amount;
        long now = SystemClock.uptimeMillis();
        if (streamFilter.isOpen() && (now - lastStreamFilterMs > 50 || amount == 0f)) {
            lastStreamFilterMs = now;
            streamFilter.apply(amount);
        }
    }

    /** Two strong jolts within 600 ms count as a shake. */
    private void onMotion(float magnitude) {
        if (magnitude < SHAKE_THRESHOLD) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastPeakMs < 90) return;
        lastPeakMs = now;
        peaks[peakIdx++ % peaks.length] = now;
        long prev = peaks[(peakIdx - 2 + peaks.length) % peaks.length];
        if (peakIdx >= 2 && now - prev < 600 && now > cooldownUntilMs) {
            cooldownUntilMs = now + 1500;
            peakIdx = 0;
            int type = breakMode >= 0 ? breakMode : (autoBreak++ % AudioEngine.BREAK_TYPES);
            engine.triggerBreak(type);
            deck.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        }
    }
}
