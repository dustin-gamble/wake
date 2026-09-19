package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Drive-to-recovery ratio on a half dial, aiming at 1:2.
 *
 * <p>Fed one stroke at a time from the pulse meter: drive length is the meter's measured drive,
 * and the stroke interval is catch to catch (drive plus recovery). Rushing the slide shows as the
 * needle dropping into the red at the left; a lazy hang at the finish runs it off to the right.
 * The last eight strokes sit as dots on the arc so a drift is visible, not just the latest one.
 */
final class GaugeRatioDialView extends View {

    private static final float MIN = 0.5f;
    private static final float MAX = 3.5f;
    private static final int HISTORY = 8;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zone = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final int good = Color.parseColor("#35D0BA");
    private final int warn = Color.parseColor("#F0B132");
    private final int bad = Color.parseColor("#F0655D");
    private final int blue = Color.parseColor("#6F8CFF");
    private final int dimColor = Color.parseColor("#8D9BB0");
    private String detailText = "";
    private String ratioText = "";
    private int ratioTenths = Integer.MIN_VALUE;

    private final float[] history = new float[HISTORY];
    private int historyCount;
    private float target = Float.NaN;
    private float shown = Float.NaN;
    private double driveSeconds;
    private double intervalSeconds;
    private PulseMeter.Stroke lastSeen;
    private long lastFrameMs;
    /** Seconds since the latest stroke landed, for the pulse on the needle hub. */
    private float sinceStroke = 99f;

    GaugeRatioDialView(Context context) {
        super(context);
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.BUTT);
        track.setColor(Color.parseColor("#18202C"));
        zone.setStyle(Paint.Style.STROKE);
        zone.setStrokeCap(Paint.Cap.BUTT);
        needle.setStrokeCap(Paint.Cap.ROUND);
        needle.setColor(Color.parseColor("#E6EDF7"));
        big.setColor(Color.parseColor("#E6EDF7"));
        big.setFakeBoldText(true);
        big.setTextAlign(Paint.Align.CENTER);
        small.setColor(Color.parseColor("#8D9BB0"));
        small.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * @param reading     the pulse meter's latest reading; only a new stroke changes anything
     * @param strokeRate  the smoothed stroke rate, used for the interval when the meter has no
     *                    recovery measured yet
     */
    void update(PulseMeter.Reading reading, double strokeRate) {
        PulseMeter.Stroke s = reading.lastStroke;
        if (s == null || s == lastSeen) {
            return;
        }
        lastSeen = s;
        double drive = s.driveSeconds;
        double interval = s.recoverySeconds > 0 && !Double.isNaN(s.recoverySeconds)
                ? drive + s.recoverySeconds
                : strokeRate > 5 ? 60.0 / strokeRate : Double.NaN;
        if (!(drive > 0.2) || Double.isNaN(interval) || interval <= drive) {
            return;
        }
        driveSeconds = drive;
        intervalSeconds = interval;
        detailText = String.format(java.util.Locale.US, "drive %.1f s  ·  stroke %.1f s", drive, interval);
        float ratio = (float) ((interval - drive) / drive);
        target = Math.max(MIN, Math.min(MAX, ratio));
        System.arraycopy(history, 0, history, 1, HISTORY - 1);
        history[0] = target;
        historyCount = Math.min(HISTORY, historyCount + 1);
        sinceStroke = 0f;
        postInvalidateOnAnimation();
    }

    private int colorFor(float r) {
        if (r < 1.4f) return bad;
        if (r < 1.7f) return warn;
        if (r <= 2.4f) return good;
        if (r <= 2.9f) return warn;
        return blue;
    }

    private String verdict(float r) {
        if (r < 1.4f) return "RUSHING THE SLIDE";
        if (r < 1.7f) return "SLOW THE RECOVERY";
        if (r <= 2.4f) return "ON RHYTHM";
        if (r <= 2.9f) return "QUICKEN THE CATCH";
        return "LONG PAUSE";
    }

    private float angleFor(float r) {
        float f = (Math.max(MIN, Math.min(MAX, r)) - MIN) / (MAX - MIN);
        return 180f + 180f * f;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        float dt = lastFrameMs > 0 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;
        sinceStroke += dt;

        float textBand = dp(34f);
        float radius = Math.min(w / 2f - dp(8f), h - textBand - dp(8f));
        if (radius < dp(20f)) {
            return;
        }
        float cx = w / 2f;
        float cy = dp(6f) + radius;
        float stroke = radius * 0.16f;
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        track.setStrokeWidth(stroke);
        zone.setStrokeWidth(stroke);
        c.drawArc(oval, 180f, 180f, false, track);

        // Coloured bands: the 1:2 window in the middle, amber either side, red for rushing.
        drawZone(c, MIN, 1.4f, bad, 90);
        drawZone(c, 1.4f, 1.7f, warn, 110);
        drawZone(c, 1.7f, 2.4f, good, 150);
        drawZone(c, 2.4f, 2.9f, warn, 110);
        drawZone(c, 2.9f, MAX, blue, 90);

        // The aim: a white notch at exactly 1:2.
        double aim = Math.toRadians(angleFor(2f));
        needle.setStrokeWidth(dp(2f));
        c.drawLine(cx + (float) Math.cos(aim) * (radius - stroke * 0.6f), cy + (float) Math.sin(aim) * (radius - stroke * 0.6f),
                cx + (float) Math.cos(aim) * (radius + stroke * 0.6f), cy + (float) Math.sin(aim) * (radius + stroke * 0.6f), needle);

        if (Float.isNaN(target)) {
            small.setTextSize(dp(10f));
            c.drawText("aim for 1 : 2", cx, cy - radius * 0.35f, small);
            c.drawText("take a few strokes", cx, cy + dp(14f), small);
            return;
        }

        // Recent strokes as dots riding the arc, older ones fainter.
        for (int i = historyCount - 1; i >= 1; i--) {
            double a = Math.toRadians(angleFor(history[i]));
            dot.setColor(colorFor(history[i]));
            dot.setAlpha(200 - i * 20);
            c.drawCircle(cx + (float) Math.cos(a) * radius, cy + (float) Math.sin(a) * radius, dp(3f), dot);
        }

        shown = Float.isNaN(shown) ? target : shown + (target - shown) * Math.min(1f, 5f * dt);
        int col = colorFor(shown);
        double a = Math.toRadians(angleFor(shown));
        needle.setStrokeWidth(dp(3f));
        float len = radius - stroke * 0.9f;
        c.drawLine(cx, cy, cx + (float) Math.cos(a) * len, cy + (float) Math.sin(a) * len, needle);
        // The hub flashes on each new stroke, so a reading visibly belongs to a stroke.
        float pulse = Math.max(0f, 1f - sinceStroke / 0.6f);
        dot.setColor(col);
        dot.setAlpha(255);
        c.drawCircle(cx, cy, dp(5f) + dp(5f) * pulse, dot);

        big.setColor(col);
        big.setTextSize(Math.min(dp(22f), radius * 0.42f));
        // Re-format only when the shown tenth changes, not on every eased frame.
        int tenths = Math.round(shown * 10f);
        if (tenths != ratioTenths) {
            ratioTenths = tenths;
            ratioText = "1 : " + (tenths / 10) + "." + (tenths % 10);
        }
        c.drawText(ratioText, cx, cy - radius * 0.3f, big);
        small.setTextSize(dp(9.5f));
        small.setColor(col);
        c.drawText(verdict(shown), cx, cy + dp(14f), small);
        small.setColor(dimColor);
        c.drawText(detailText, cx, cy + dp(27f), small);

        if (Math.abs(target - shown) > 0.005f || pulse > 0f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawZone(Canvas c, float from, float to, int color, int alpha) {
        zone.setColor(color);
        zone.setAlpha(alpha);
        float a0 = angleFor(from);
        c.drawArc(oval, a0, angleFor(to) - a0, false, zone);
    }
}
