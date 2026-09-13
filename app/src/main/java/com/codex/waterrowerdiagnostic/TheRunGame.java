package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * The Run: keep the boat running.
 *
 * <p>A run line sits at 70% of your rolling peak speed. While the coasted boat speed stays above
 * it your streak grows and run-metres accrue; let the boat die below it and the streak resets.
 * The coast is the whole mechanic - stop pulling and the decay ends the streak - which is exactly
 * the feedback a real boat gives. Scores are relative to this machine's drag setting, so they
 * are for beating yourself, not comparing across machines.
 */
final class TheRunGame extends GameView {

    private static final float RUN_FRACTION = 0.70f;
    private static final int TRACE = 300;

    private final PersonalBests bests;
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tracePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tracePath = new Path();
    private final RectF rect = new RectF();

    private final float[] trace = new float[TRACE];
    private int traceHead;
    private int traceCount;
    private float traceAccum;

    private float rollingPeak;
    private double streakSeconds;
    private double bestStreak;
    private double runMeters;
    private double diedFlash;
    private int breaks;
    private boolean above;

    TheRunGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        bandPaint.setStyle(Paint.Style.FILL);
        tracePaint.setStyle(Paint.Style.STROKE);
        tracePaint.setStrokeWidth(dp(2.2f));
        tracePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    protected void onStart() {
        rollingPeak = 0f;
        streakSeconds = 0;
        bestStreak = 0;
        runMeters = 0;
        breaks = 0;
        above = false;
        traceHead = 0;
        traceCount = 0;
        diedFlash = 0;
    }

    @Override
    protected void onStop() {
        commitBests();
    }

    private void commitBests() {
        if (bestStreak > 5) {
            bests.recordHighest("run.streak", (float) bestStreak);
        }
        if (runMeters > 50) {
            bests.recordHighest("run.score", (float) runMeters);
        }
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();

        // Rolling peak: rises instantly, bleeds off slowly so a single sprint does not set an
        // impossible line for the rest of the piece.
        if (speed > rollingPeak) {
            rollingPeak = speed;
        } else {
            rollingPeak = Math.max(0f, rollingPeak - 0.04f * dt);
        }
        float runLine = rollingPeak * RUN_FRACTION;

        boolean nowAbove = speed > 0.4f && speed >= runLine;
        if (nowAbove) {
            streakSeconds += dt;
            runMeters += speed * dt;
            bestStreak = Math.max(bestStreak, streakSeconds);
        } else if (above && streakSeconds > 1.0) {
            breaks++;
            diedFlash = 1.0;
            streakSeconds = 0;
        } else if (!nowAbove) {
            streakSeconds = 0;
        }
        above = nowAbove;
        diedFlash = Math.max(0, diedFlash - dt * 1.4);

        traceAccum += dt;
        if (traceAccum >= 0.2f) {
            traceAccum = 0f;
            trace[traceHead] = speed;
            traceHead = (traceHead + 1) % TRACE;
            if (traceCount < TRACE) {
                traceCount++;
            }
        }

        // Layout: HUD top third, speed band middle, trace bottom.
        float hudBottom = h * 0.34f;
        float bandTop = h * 0.36f;
        float bandBottom = h * 0.52f;
        float traceTop = h * 0.56f;
        float traceBottom = h - dp(34f);

        if (diedFlash > 0) {
            bandPaint.setColor(BAD);
            bandPaint.setAlpha((int) (diedFlash * 70));
            c.drawRect(0, 0, w, h, bandPaint);
        }

        // HUD.
        bold(c, clock(streakSeconds), w / 2f, hudBottom * 0.62f, 52f,
                nowAbove ? ACCENT : DIM, Paint.Align.CENTER);
        label(c, nowAbove ? "BOAT RUNNING" : (rollingPeak > 0.5f ? "BOAT DIED - PULL" : "TAKE A STROKE"),
                w / 2f, hudBottom * 0.62f + dp(20f), 10f, nowAbove ? FAINT : BAD, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.0f", runMeters), dp(16f), hudBottom * 0.55f,
                24f, TEXT, Paint.Align.LEFT);
        label(c, "RUN METRES", dp(16f), hudBottom * 0.55f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, clock(bestStreak), w - dp(16f), hudBottom * 0.55f, 24f, BLUE, Paint.Align.RIGHT);
        label(c, "BEST STREAK", w - dp(16f), hudBottom * 0.55f + dp(16f), 9f, FAINT,
                Paint.Align.RIGHT);

        // Speed band: track, run line, and the boat's current speed as a filled bar.
        float scaleMax = Math.max(2f, rollingPeak * 1.15f);
        rect.set(dp(16f), bandTop, w - dp(16f), bandBottom);
        bandPaint.setColor(0xFF18202C);
        c.drawRoundRect(rect, dp(8f), dp(8f), bandPaint);
        float fillX = dp(16f) + (w - dp(32f)) * Math.min(1f, speed / scaleMax);
        rect.set(dp(16f), bandTop, Math.max(dp(24f), fillX), bandBottom);
        bandPaint.setColor(nowAbove ? ACCENT : BAD);
        c.drawRoundRect(rect, dp(8f), dp(8f), bandPaint);
        float lineX = dp(16f) + (w - dp(32f)) * Math.min(1f, runLine / scaleMax);
        accentPaint.setColor(TEXT);
        accentPaint.setStrokeWidth(dp(3f));
        c.drawLine(lineX, bandTop - dp(6f), lineX, bandBottom + dp(6f), accentPaint);
        label(c, "RUN LINE " + String.format(java.util.Locale.US, "%.1f", runLine), lineX,
                bandBottom + dp(18f), 9f, FAINT, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.1f m/s", speed), w - dp(20f),
                bandTop + (bandBottom - bandTop) / 2f + dp(5f), 13f, TEXT, Paint.Align.RIGHT);

        // Trace of the coasted speed with the run line drawn through it.
        rect.set(dp(16f), traceTop, w - dp(16f), traceBottom);
        bandPaint.setColor(0xFF111722);
        c.drawRect(rect, bandPaint);
        if (traceCount > 1) {
            float stepX = (w - dp(32f)) / (TRACE - 1f);
            float firstX = w - dp(16f) - (traceCount - 1) * stepX;
            int start = (traceHead - traceCount + TRACE) % TRACE;
            tracePath.reset();
            for (int i = 0; i < traceCount; i++) {
                float v = trace[(start + i) % TRACE];
                float x = firstX + i * stepX;
                float y = traceBottom - (traceBottom - traceTop) * Math.min(1f, v / scaleMax);
                if (i == 0) {
                    tracePath.moveTo(x, y);
                } else {
                    tracePath.lineTo(x, y);
                }
            }
            tracePaint.setColor(ACCENT);
            c.drawPath(tracePath, tracePaint);
        }
        float runY = traceBottom - (traceBottom - traceTop) * Math.min(1f, runLine / scaleMax);
        accentPaint.setColor(TEXT);
        accentPaint.setAlpha(120);
        accentPaint.setStrokeWidth(dp(1f));
        c.drawLine(dp(16f), runY, w - dp(16f), runY, accentPaint);
        accentPaint.setAlpha(255);

        // Footer.
        float fy = h - dp(12f);
        float col = w / 4f;
        stat(c, col * 0.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 1.5f, fy, status == null ? "0" : String.valueOf(status.watts), "WATTS");
        stat(c, col * 2.5f, fy, String.valueOf(breaks), "TIMES DIED");
        stat(c, col * 3.5f, fy, bests.has("run.streak") ? clock(bests.get("run.streak", 0)) : "--",
                "PB STREAK");
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
