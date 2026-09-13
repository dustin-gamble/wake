package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/**
 * Base for every game screen.
 *
 * <p>The contract is deliberately narrow: a game receives {@link #onStatus} and draws itself. It
 * never touches the protocol or the serial link, so a broken game cannot break the connection.
 *
 * <p>Two things are provided because every game needs them and getting them wrong is the mistake
 * this project keeps making: a {@link BoatSpeedModel} that coasts properly, and a smoothed
 * session distance that moves every frame rather than stepping once a second.
 */
abstract class GameView extends View {

    protected S4Protocol.Status status;
    protected boolean driving;
    protected final BoatSpeedModel boat = new BoatSpeedModel();

    /** Metres rowed since the game started, smoothed so a boat on screen moves continuously. */
    protected double sessionMeters;
    /** Seconds since the game started. */
    protected double sessionSeconds;
    /**
     * The rowing clock: starts on the first stroke, pauses after 15s without one, resumes on the
     * next. This is the time a game should show; {@link #sessionSeconds} keeps running and is
     * for internal timers that must not pause (an interval's rest, a boss's attack cadence).
     */
    protected double activeSeconds;
    private long lastDrivingMs;
    private static final long CLOCK_GRACE_MS = 15000;

    private int startDistance = -1;
    private int lastStrokeCount = -1;
    /** Strokes taken since {@link #start()}. */
    protected int strokesSinceStart;
    private long lastFrameMs;
    private long startedAtMs;
    private boolean running;

    protected final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    protected static final int ACCENT = Color.parseColor("#35D0BA");
    protected static final int BLUE = Color.parseColor("#6F8CFF");
    protected static final int WARN = Color.parseColor("#F0B132");
    protected static final int BAD = Color.parseColor("#F0655D");
    protected static final int TEXT = Color.parseColor("#E6EDF7");
    protected static final int DIM = Color.parseColor("#8D9BB0");
    protected static final int FAINT = Color.parseColor("#5D6B80");

    GameView(Context context) {
        super(context);
        textPaint.setColor(TEXT);
        textPaint.setFakeBoldText(true);
        dimPaint.setColor(DIM);
        accentPaint.setColor(ACCENT);
        accentPaint.setFakeBoldText(true);
    }

    /** Called by the activity whenever a fresh status lands. Runs on the UI thread. */
    void onStatus(S4Protocol.Status s, boolean driving) {
        this.status = s;
        this.driving = driving;
        long now = System.currentTimeMillis();
        boat.setTarget(s.waterSpeedMps, driving, now);
        if (driving && running) {
            lastDrivingMs = now;
        }
        int real = s.distanceMeters > 0 ? s.distanceMeters : s.derivedDistanceMeters;
        if (startDistance < 0 && real >= 0 && running) {
            startDistance = real;
        }
        // The stroke counter is exact; each increment is one stroke landing. Polled every
        // ~0.95s, so a hit can register up to a second late - fine for damage, not for rhythm.
        if (lastStrokeCount >= 0 && s.strokes > lastStrokeCount && running) {
            int n = Math.min(3, s.strokes - lastStrokeCount);
            for (int i = 0; i < n; i++) {
                strokesSinceStart++;
                onStroke(s.watts);
            }
        }
        lastStrokeCount = s.strokes;
        onStatusChanged(s);
        postInvalidateOnAnimation();
    }

    /** Hook for games that need to react to a reading rather than just redraw. */
    protected void onStatusChanged(S4Protocol.Status s) {
    }

    /** A stroke landed. {@code watts} is the power reading at the time. */
    protected void onStroke(int watts) {
    }

    void setDrag(float k) {
        boat.setDrag(k);
    }

    void start() {
        running = true;
        startedAtMs = System.currentTimeMillis();
        lastFrameMs = 0;
        sessionMeters = 0;
        sessionSeconds = 0;
        activeSeconds = 0;
        lastDrivingMs = 0;
        startDistance = -1;
        lastStrokeCount = -1;
        strokesSinceStart = 0;
        boat.reset();
        onStart();
        postInvalidateOnAnimation();
    }

    void stop() {
        running = false;
        onStop();
    }

    protected void onStart() {
    }

    protected void onStop() {
    }

    boolean isRunning() {
        return running;
    }

    /** True from the first stroke until 15s have passed without one. */
    boolean isClockRunning() {
        return lastDrivingMs > 0 && System.currentTimeMillis() - lastDrivingMs < CLOCK_GRACE_MS;
    }

    /** True once the clock has started at all, even if currently paused. */
    boolean hasClockStarted() {
        return lastDrivingMs > 0;
    }

    double activeSeconds() {
        return activeSeconds;
    }

    /** Coasted boat speed, for the shared vitals strip. */
    float boatSpeed() {
        return boat.value();
    }

    @Override
    protected final void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.currentTimeMillis();
        float dt = lastFrameMs > 0 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;

        if (running) {
            sessionSeconds = (now - startedAtMs) / 1000.0;
            if (isClockRunning()) {
                activeSeconds += dt;
            }
            boat.step(dt, now);
            // Dead-reckon from the coasted speed, then pull gently toward the monitor's real
            // distance whenever it is ahead. The real figure only steps every ~2s; without this
            // the boat on screen would lurch.
            sessionMeters += boat.value() * dt;
            if (status != null && startDistance >= 0) {
                int real = status.distanceMeters > 0
                        ? status.distanceMeters : status.derivedDistanceMeters;
                double realSession = real - startDistance;
                if (realSession > sessionMeters) {
                    sessionMeters += (realSession - sessionMeters) * Math.min(1f, 1.5f * dt);
                }
            }
        }

        render(canvas, dt);
        if (running) {
            postInvalidateOnAnimation();
        }
    }

    protected abstract void render(Canvas canvas, float dt);

    /* ---------- helpers ---------- */

    protected float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    protected void label(Canvas c, String text, float x, float y, float sizeDp, int color,
                         Paint.Align align) {
        dimPaint.setColor(color);
        dimPaint.setTextSize(dp(sizeDp));
        dimPaint.setTextAlign(align);
        dimPaint.setFakeBoldText(false);
        c.drawText(text, x, y, dimPaint);
    }

    protected void bold(Canvas c, String text, float x, float y, float sizeDp, int color,
                        Paint.Align align) {
        textPaint.setColor(color);
        textPaint.setTextSize(dp(sizeDp));
        textPaint.setTextAlign(align);
        c.drawText(text, x, y, textPaint);
    }

    protected static String pace(double mps) {
        if (mps < 0.5) {
            return "--:--";
        }
        return PersonalBests.formatPace((float) (500.0 / mps));
    }

    protected static String clock(double seconds) {
        return PersonalBests.formatTime((float) seconds);
    }
}
