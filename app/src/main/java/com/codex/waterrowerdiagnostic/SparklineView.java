package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * Rolling trace of boat speed (filled) over power.
 *
 * <p>Scrolls continuously rather than in steps. Samples arrive about five times a second, and the
 * first version redrew only when one did, so the whole trace jumped a column left every 200 ms.
 * Now the newest sample slides in: between samples everything is offset by the fraction of a
 * sample period that has elapsed, and the view redraws every frame while it is on screen.
 *
 * <p>No allocation in {@link #onDraw}. The first version built a new {@link Path} and a new
 * {@link LinearGradient} on every draw, which on this tablet means garbage-collection pauses -
 * hitches in exactly the motion that is meant to look smooth.
 */
final class SparklineView extends View {

    /** 60 seconds at 5Hz. Long history matters less than resolving individual strokes. */
    private static final int CAPACITY = 300;

    private final float[] speed = new float[CAPACITY];
    private final float[] power = new float[CAPACITY];
    private int count;
    private int head;

    /** When the newest sample landed, and the measured gap between samples. */
    private long lastSampleAtMs;
    private float samplePeriodMs = 200f;
    private long lastFrameMs;

    /**
     * Scales ease toward their targets. A peak scrolling out of the window used to rescale the
     * whole plot in one frame, which reads as a jolt even when the scrolling is smooth.
     */
    private float shownMaxSpeed = 2f;
    private float shownMaxPower = 60f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint powerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint speedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path speedPath = new Path();
    private final Path powerPath = new Path();
    private final Path fillPath = new Path();
    private float shaderHeight = -1f;

    private final int speedColor = Color.parseColor("#35D0BA");
    private final int powerColor = Color.parseColor("#6F8CFF");

    SparklineView(Context context) {
        super(context);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(Color.parseColor("#1F2937"));

        powerPaint.setStyle(Paint.Style.STROKE);
        powerPaint.setStrokeWidth(dp(1.6f));
        powerPaint.setStrokeJoin(Paint.Join.ROUND);
        powerPaint.setStrokeCap(Paint.Cap.ROUND);
        powerPaint.setColor(powerColor);

        speedPaint.setStyle(Paint.Style.STROKE);
        speedPaint.setStrokeWidth(dp(2.4f));
        speedPaint.setStrokeJoin(Paint.Join.ROUND);
        speedPaint.setStrokeCap(Paint.Cap.ROUND);
        speedPaint.setColor(speedColor);

        fillPaint.setStyle(Paint.Style.FILL);

        labelPaint.setColor(Color.parseColor("#5D6B80"));
        labelPaint.setTextSize(dp(9f));
    }

    /**
     * @param speedMps the COASTED speed, as the needle shows it - not the raw monitor reading.
     *                 Sampled at display rate this traces the boat's run: the rise through each
     *                 drive and the decay after it. Plotting the raw reading gives a staircase,
     *                 because the monitor only refreshes about once a second.
     */
    void addSample(double speedMps, int watts) {
        long now = System.currentTimeMillis();
        if (lastSampleAtMs > 0) {
            // Measured, not assumed: the UI ticker drifts, and a wrong period makes the scroll
            // lurch at each sample instead of meeting it.
            float gap = Math.max(50f, Math.min(1000f, now - lastSampleAtMs));
            samplePeriodMs = samplePeriodMs * 0.8f + gap * 0.2f;
        }
        lastSampleAtMs = now;
        speed[head] = (float) speedMps;
        power[head] = watts;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        postInvalidateOnAnimation();
    }

    void clear() {
        count = 0;
        head = 0;
        lastSampleAtMs = 0;
        shownMaxSpeed = 2f;
        shownMaxPower = 60f;
        postInvalidateOnAnimation();
    }

    /** Oldest-to-newest value at {@code index}. */
    private float valueAt(float[] series, int index) {
        int start = (head - count + CAPACITY) % CAPACITY;
        return series[(start + index) % CAPACITY];
    }

    private float maxOf(float[] series, float floor) {
        float max = floor;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, valueAt(series, i));
        }
        return max;
    }

    /** Rise at once so nothing clips; fall gently so a peak leaving the window does not jolt. */
    private static float easeScale(float shown, float target, float dt) {
        if (target >= shown) {
            return target;
        }
        return shown + (target - shown) * Math.min(1f, 1.2f * dt);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float padBottom = dp(14f);
        float plotH = h - padBottom;
        if (w <= 0 || plotH <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        float dt = lastFrameMs > 0 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;

        for (int i = 0; i <= 3; i++) {
            float y = plotH * i / 3f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        if (count < 2) {
            canvas.drawText("Waiting for rowing data", dp(6f), plotH / 2f, labelPaint);
            return;
        }

        shownMaxSpeed = easeScale(shownMaxSpeed, maxOf(speed, 2f), dt);
        shownMaxPower = easeScale(shownMaxPower, maxOf(power, 60f), dt);

        float stepX = w / (CAPACITY - 1f);
        // How far through the gap to the next sample we are. Sliding by that fraction makes the
        // trace move continuously; at 1 it rests where the next sample will pick it up.
        float frac = lastSampleAtMs > 0
                ? Math.max(0f, Math.min(1f, (now - lastSampleAtMs) / samplePeriodMs))
                : 0f;
        float firstX = w - (count - 1) * stepX - frac * stepX;
        float lastX = firstX + (count - 1) * stepX;

        canvas.save();
        canvas.clipRect(0, 0, w, plotH);

        // Speed leads: it is the boat's run, and the reason to take the next stroke.
        speedPath.rewind();
        for (int i = 0; i < count; i++) {
            float x = firstX + i * stepX;
            float y = plotH * (1f - valueAt(speed, i) / shownMaxSpeed);
            if (i == 0) {
                speedPath.moveTo(x, y);
            } else {
                speedPath.lineTo(x, y);
            }
        }
        fillPath.set(speedPath);
        fillPath.lineTo(lastX, plotH);
        fillPath.lineTo(firstX, plotH);
        fillPath.close();
        if (shaderHeight != plotH) {
            fillPaint.setShader(new LinearGradient(0, 0, 0, plotH,
                    (speedColor & 0x00FFFFFF) | 0x59000000,
                    speedColor & 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            shaderHeight = plotH;
        }
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(speedPath, speedPaint);

        powerPath.rewind();
        for (int i = 0; i < count; i++) {
            float x = firstX + i * stepX;
            float y = plotH * (1f - valueAt(power, i) / shownMaxPower);
            if (i == 0) {
                powerPath.moveTo(x, y);
            } else {
                powerPath.lineTo(x, y);
            }
        }
        canvas.drawPath(powerPath, powerPaint);
        canvas.restore();

        canvas.drawText(String.format(java.util.Locale.US, "%.1f m/s peak", shownMaxSpeed),
                dp(4f), h - dp(3f), labelPaint);
        String powerLabel = Math.round(shownMaxPower) + "W peak";
        canvas.drawText(powerLabel, w - labelPaint.measureText(powerLabel) - dp(4f),
                h - dp(3f), labelPaint);

        // Keep scrolling between samples. Only drawn while on screen, so this costs nothing when
        // the gauges are not showing.
        postInvalidateOnAnimation();
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
