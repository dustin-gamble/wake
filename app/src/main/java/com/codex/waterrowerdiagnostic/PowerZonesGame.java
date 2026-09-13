package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Power Zones: time accumulated in each of five colour bands, shown as a stacked bar you try
 * to shape. A session profile rather than a race - good for structured training.
 *
 * <p>Zones are fractions of a reference power (your "threshold"), so the same session reads the
 * same whether you are fresh or tired: it is about where you spent the time.
 */
final class PowerZonesGame extends GameView {

    private static final String[] NAMES = {"EASY", "STEADY", "TEMPO", "HARD", "MAX"};
    private static final float[] LOWER = {0f, 0.55f, 0.75f, 0.90f, 1.05f};
    private static final int[] COLORS = {0xFF3B4A5E, 0xFF35D0BA, 0xFF6F8CFF, 0xFFF0B132, 0xFFF0655D};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double[] seconds = new double[5];
    private int threshold = 150;
    private int zone;
    private double total;

    PowerZonesGame(Context context) {
        super(context);
    }

    void setThreshold(int watts) {
        this.threshold = watts;
    }

    int threshold() {
        return threshold;
    }

    @Override
    protected void onStart() {
        java.util.Arrays.fill(seconds, 0);
        total = 0;
        zone = 0;
    }

    private int zoneFor(int watts) {
        float f = threshold > 0 ? (float) watts / threshold : 0f;
        int z = 0;
        for (int i = 0; i < LOWER.length; i++) {
            if (f >= LOWER[i]) {
                z = i;
            }
        }
        return z;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        if (watts > 0 && driving) {
            zone = zoneFor(watts);
            seconds[zone] += dt;
            total += dt;
        }

        // Current zone, big.
        paint.setColor(COLORS[zone]);
        paint.setAlpha(watts > 0 ? 40 : 0);
        c.drawRect(0, 0, w, h * 0.34f, paint);
        bold(c, NAMES[zone], w / 2f, h * 0.17f, 40f, watts > 0 ? COLORS[zone] : DIM, Paint.Align.CENTER);
        label(c, watts > 0 ? watts + " W  ·  " + Math.round(100f * watts / threshold) + "% of " + threshold
                : "take a stroke", w / 2f, h * 0.17f + dp(20f), 11f, FAINT, Paint.Align.CENTER);

        // Zone ladder with the current watts marker.
        float ladderTop = h * 0.40f;
        float ladderH = dp(22f);
        float left = dp(16f);
        float right = w - dp(16f);
        float scaleMax = threshold * 1.4f;
        for (int i = 0; i < 5; i++) {
            float x0 = left + (right - left) * Math.min(1f, LOWER[i] * threshold / scaleMax);
            float x1 = i < 4 ? left + (right - left) * Math.min(1f, LOWER[i + 1] * threshold / scaleMax) : right;
            paint.setColor(COLORS[i]);
            paint.setAlpha(i == zone && watts > 0 ? 255 : 110);
            c.drawRect(x0, ladderTop, x1 - dp(2f), ladderTop + ladderH, paint);
            label(c, NAMES[i], (x0 + x1) / 2f, ladderTop + ladderH + dp(14f), 8f, FAINT,
                    Paint.Align.CENTER);
        }
        float mx = left + (right - left) * Math.min(1f, watts / scaleMax);
        paint.setColor(TEXT);
        c.drawRect(mx - dp(2f), ladderTop - dp(6f), mx + dp(2f), ladderTop + ladderH + dp(6f), paint);

        // Time in each zone: stacked bar plus a readout per zone.
        float barTop = h * 0.60f;
        float barH = dp(30f);
        if (total > 0) {
            float x = left;
            for (int i = 0; i < 5; i++) {
                float wz = (right - left) * (float) (seconds[i] / total);
                paint.setColor(COLORS[i]);
                paint.setAlpha(230);
                c.drawRect(x, barTop, x + wz, barTop + barH, paint);
                x += wz;
            }
        } else {
            paint.setColor(0xFF18202C);
            c.drawRect(left, barTop, right, barTop + barH, paint);
        }
        label(c, "TIME IN ZONE", left, barTop - dp(8f), 8.5f, FAINT, Paint.Align.LEFT);
        label(c, clock(total) + " ROWING", right, barTop - dp(8f), 8.5f, FAINT, Paint.Align.RIGHT);

        float fy = h - dp(14f);
        float col = (right - left) / 5f;
        for (int i = 0; i < 5; i++) {
            float cx = left + col * (i + 0.5f);
            bold(c, clock(seconds[i]), cx, fy - dp(12f), 15f, COLORS[i], Paint.Align.CENTER);
            label(c, NAMES[i], cx, fy + dp(2f), 8f, FAINT, Paint.Align.CENTER);
        }
    }
}
