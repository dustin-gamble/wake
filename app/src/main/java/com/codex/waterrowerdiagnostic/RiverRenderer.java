package com.codex.waterrowerdiagnostic;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Draws water and boats. Shared by Pace Boat, Ghost Race and anything else set on a river.
 *
 * <p>The water scrolls at the player's speed so effort reads as motion. Boats are drawn as hull
 * paths with a wake whose length follows speed; there are no image assets in this app.
 */
final class RiverRenderer {

    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hullPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hullEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wakePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hull = new Path();
    private final RectF rect = new RectF();

    private float scroll;
    private final float density;
    private final Fx.Particles spray = new Fx.Particles();
    private float sprayAccum;

    RiverRenderer(float density) {
        this.density = density;
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setColor(Color.parseColor("#2A4A6B"));
        hullEdge.setStyle(Paint.Style.STROKE);
        hullEdge.setStrokeWidth(dp(1.5f));
        wakePaint.setStyle(Paint.Style.STROKE);
        wakePaint.setStrokeCap(Paint.Cap.ROUND);
        rowerPaint.setColor(Color.parseColor("#E6EDF7"));
    }

    private float dp(float v) {
        return v * density;
    }

    /** Advances the scrolling water. Call once per frame with the player's speed. */
    void advance(float metresPerSecond, float dt, float pixelsPerMetre) {
        scroll += metresPerSecond * dt * pixelsPerMetre;
        spray.step(dt, dp(40f));
    }

    /** Throws spray off a bow; call each frame for the player's boat. */
    void bowSpray(float bowX, float waterY, float speed, float dt) {
        if (speed < 1.2f) {
            return;
        }
        sprayAccum += dt * speed * 14f;
        while (sprayAccum >= 1f) {
            sprayAccum -= 1f;
            spray.spawn(bowX, waterY, -dp(20f) - (float) Math.random() * dp(60f) * speed / 4f,
                    -dp(30f) - (float) Math.random() * dp(40f), 0.5f, dp(2.2f), 0xCCBFE3FF, true);
        }
    }

    void drawSpray(Canvas c) {
        spray.draw(c);
    }

    void drawWater(Canvas c, float top, float bottom, float width) {
        rect.set(0, top, width, bottom);
        waterPaint.setShader(new LinearGradient(0, top, 0, bottom,
                Color.parseColor("#0F2438"), Color.parseColor("#0A1826"), Shader.TileMode.CLAMP));
        c.drawRect(rect, waterPaint);

        // Rolling ripples: sine ridges that drift left as the boat moves right, brighter near the
        // surface, with a faint highlight line so they read as light on water.
        ripplePaint.setStrokeWidth(dp(1.4f));
        for (int row = 0; row < 5; row++) {
            float y0 = top + (bottom - top) * (0.15f + row * 0.18f);
            float speedMul = 0.5f + row * 0.25f;
            float wave = dp(52f) + row * dp(11f);
            float amp = dp(2.5f) + row * dp(0.8f);
            float phase = (scroll * speedMul) / wave * (float) Math.PI * 2f;
            ripplePaint.setColor(0xFF2A4A6B);
            ripplePaint.setAlpha(50 + row * 18);
            float prevX = 0, prevY = y0;
            for (float x = 0; x <= width; x += dp(10f)) {
                float y = y0 + (float) Math.sin(x / wave * Math.PI * 2 - phase) * amp;
                if (x > 0) {
                    c.drawLine(prevX, prevY, x, y, ripplePaint);
                }
                prevX = x;
                prevY = y;
            }
            ripplePaint.setColor(0xFF6FA6D6);
            ripplePaint.setAlpha(18 + row * 6);
            for (float x = 0; x <= width; x += dp(10f)) {
                float y = y0 + (float) Math.sin(x / wave * Math.PI * 2 - phase) * amp - dp(1.5f);
                if (Math.sin(x / wave * Math.PI * 2 - phase) > 0.6) {
                    c.drawLine(x, y, x + dp(6f), y, ripplePaint);
                }
            }
        }
    }

    /**
     * Draws a boat pointing right.
     *
     * @param cx     centre x of the hull
     * @param cy     waterline y
     * @param length hull length in px
     * @param color  hull colour
     * @param speed  m/s, drives wake length and rower lean
     * @param ghost  draw translucent
     */
    void drawBoat(Canvas c, float cx, float cy, float length, int color, float speed,
                  boolean ghost) {
        float half = length / 2f;
        float beam = length * 0.11f;
        int alpha = ghost ? 120 : 255;

        // Wake: two trailing lines fanning out, longer and brighter with speed.
        float wakeLen = Math.min(length * 2.2f, dp(40f) + speed * dp(34f));
        if (wakeLen > dp(6f)) {
            float strength = Math.min(1f, speed / 4f);
            int segs = 7;
            for (int i = 0; i < segs; i++) {
                float f0 = i / (float) segs;
                float f1 = (i + 1) / (float) segs;
                int a = (int) (strength * 170 * (1f - f0)) * alpha / 255;
                wakePaint.setColor(0xFFBFE3FF);
                wakePaint.setAlpha(a);
                wakePaint.setStrokeWidth(dp(3.5f) * (1f - f0 * 0.7f));
                float x0 = cx - half * 0.7f - wakeLen * f0;
                float x1 = cx - half * 0.7f - wakeLen * f1;
                float spread0 = beam * (0.4f + 1.6f * f0);
                float spread1 = beam * (0.4f + 1.6f * f1);
                c.drawLine(x0, cy + spread0, x1, cy + spread1, wakePaint);
                c.drawLine(x0, cy - spread0, x1, cy - spread1, wakePaint);
            }
            // Foam patch right behind the stern.
            wakePaint.setColor(0xFFDCEBF7);
            wakePaint.setAlpha((int) (strength * 90) * alpha / 255);
            wakePaint.setStrokeWidth(beam * 1.2f);
            c.drawLine(cx - half * 0.75f, cy, cx - half * 0.75f - dp(10f) - speed * dp(4f), cy, wakePaint);
        }

        hull.reset();
        hull.moveTo(cx + half, cy);
        hull.quadTo(cx + half * 0.55f, cy - beam, cx - half * 0.2f, cy - beam);
        hull.lineTo(cx - half * 0.8f, cy - beam * 0.6f);
        hull.quadTo(cx - half, cy, cx - half * 0.8f, cy + beam * 0.6f);
        hull.lineTo(cx - half * 0.2f, cy + beam);
        hull.quadTo(cx + half * 0.55f, cy + beam, cx + half, cy);
        hull.close();

        hullPaint.setColor(color);
        hullPaint.setAlpha(alpha);
        c.drawPath(hull, hullPaint);
        hullEdge.setColor(Color.parseColor("#0A0E14"));
        hullEdge.setAlpha(alpha);
        c.drawPath(hull, hullEdge);

        // Rower: a dot that leans forward with speed. Enough to read as a person.
        float lean = Math.min(1f, speed / 4f) * beam * 0.8f;
        rowerPaint.setAlpha(alpha);
        c.drawCircle(cx - half * 0.15f + lean, cy - beam * 0.15f, beam * 0.55f, rowerPaint);
    }
}
