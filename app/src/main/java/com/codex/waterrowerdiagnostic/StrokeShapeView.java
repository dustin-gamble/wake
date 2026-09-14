package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The shape of each stroke, from the pulse meter: the last four drives overlaid, newest brightest,
 * and one bar per stroke underneath.
 *
 * <p>Drives are stretched to the same width so their shapes compare directly - a stroke whose
 * peak arrives late, or that dumps speed at the finish, stands out against the ones before it.
 * Bars show measured average power once energy is available, paddle peak rate until then.
 */
final class StrokeShapeView extends View {

    private static final int SHAPES = 4;
    private static final int BARS = 24;

    private final PulseMeter.Stroke[] shapes = new PulseMeter.Stroke[SHAPES];
    private int shapeCount;
    private final float[] bars = new float[BARS];
    private int barCount;
    private int barHead;
    private boolean barsArePower;
    private PulseMeter.Stroke lastSeen;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int accent = Color.parseColor("#35D0BA");
    private final int blue = Color.parseColor("#6F8CFF");
    private final int warn = Color.parseColor("#F0B132");

    StrokeShapeView(Context context) {
        super(context);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setColor(Color.parseColor("#5D6B80"));
    }

    void update(PulseMeter.Reading reading) {
        PulseMeter.Stroke s = reading.lastStroke;
        if (s == null || s == lastSeen) {
            return;
        }
        lastSeen = s;
        System.arraycopy(shapes, 0, shapes, 1, SHAPES - 1);
        shapes[0] = s;
        shapeCount = Math.min(SHAPES, shapeCount + 1);
        boolean power = !Double.isNaN(s.averagePowerW);
        if (power != barsArePower) {
            barCount = 0;
            barsArePower = power;
        }
        bars[barHead] = (float) (power ? s.averagePowerW : s.peakRate);
        barHead = (barHead + 1) % BARS;
        barCount = Math.min(BARS, barCount + 1);
        postInvalidateOnAnimation();
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
        text.setTextSize(dp(9f));
        float shapeBottom = h * 0.62f;
        if (shapeCount == 0) {
            c.drawText("Take a few strokes", dp(4f), shapeBottom / 2f, text);
            return;
        }
        float max = 1f;
        for (int i = 0; i < shapeCount; i++) {
            for (float v : shapes[i].driveRates) {
                max = Math.max(max, v);
            }
        }
        float top = dp(4f);
        for (int i = shapeCount - 1; i >= 0; i--) {
            float[] r = shapes[i].driveRates;
            if (r.length < 2) {
                continue;
            }
            path.rewind();
            for (int k = 0; k < r.length; k++) {
                float x = w * k / (r.length - 1f);
                float y = shapeBottom - (shapeBottom - top) * r[k] / max;
                if (k == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            line.setColor(accent);
            line.setAlpha(i == 0 ? 255 : 130 - i * 30);
            line.setStrokeWidth(dp(i == 0 ? 2.6f : 1.6f));
            c.drawPath(path, line);
        }
        PulseMeter.Stroke now = shapes[0];
        String caption = String.format(java.util.Locale.US, "drive %.1f s  ·  ratio 1:%.1f",
                now.driveSeconds, now.driveSeconds > 0 ? now.recoverySeconds / now.driveSeconds : 0);
        c.drawText(caption, dp(4f), shapeBottom + dp(12f), text);

        float barTop = shapeBottom + dp(18f);
        float barBottom = h - dp(2f);
        if (barCount == 0 || barBottom - barTop < dp(10f)) {
            return;
        }
        float barMax = 1f;
        float sum = 0f;
        for (int i = 0; i < barCount; i++) {
            barMax = Math.max(barMax, bars[i]);
            sum += bars[i];
        }
        float avg = sum / barCount;
        float slot = w / BARS;
        for (int i = 0; i < barCount; i++) {
            int idx = (barHead - barCount + i + BARS) % BARS;
            float v = bars[idx];
            float x = w - (barCount - i) * slot;
            fill.setColor(v >= avg * 1.03f ? accent : v <= avg * 0.93f ? warn : blue);
            c.drawRect(x + slot * 0.15f, barBottom - (barBottom - barTop) * v / barMax, x + slot * 0.85f, barBottom, fill);
        }
        String label = barsArePower ? Math.round(avg) + " W avg per stroke" : "paddle peak per stroke";
        c.drawText(label, w - text.measureText(label) - dp(2f), barTop - dp(2f), text);
    }
}
