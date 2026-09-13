package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/** Rolling two-series trace of power and stroke rate. */
final class SparklineView extends View {

    /** 60 seconds at 5Hz. Long history matters less than resolving individual strokes. */
    private static final int CAPACITY = 300;

    private final float[] speed = new float[CAPACITY];
    private final float[] power = new float[CAPACITY];
    private int count;
    private int head;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint powerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ratePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private int powerColor = Color.parseColor("#35D0BA");
    private int rateColor = Color.parseColor("#6F8CFF");

    SparklineView(Context context) {
        super(context);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f);
        gridPaint.setColor(Color.parseColor("#1F2937"));

        powerPaint.setStyle(Paint.Style.STROKE);
        powerPaint.setStrokeWidth(dp(1.6f));
        powerPaint.setStrokeJoin(Paint.Join.ROUND);
        powerPaint.setStrokeCap(Paint.Cap.ROUND);
        powerPaint.setColor(rateColor);

        ratePaint.setStyle(Paint.Style.STROKE);
        ratePaint.setStrokeWidth(dp(2.4f));
        ratePaint.setStrokeJoin(Paint.Join.ROUND);
        ratePaint.setColor(powerColor);
        ratePaint.setStrokeCap(Paint.Cap.ROUND);

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

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float padBottom = dp(14f);
        float plotH = h - padBottom;

        for (int i = 0; i <= 3; i++) {
            float y = plotH * i / 3f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        if (count < 2) {
            canvas.drawText("Waiting for rowing data", dp(6f), plotH / 2f, labelPaint);
            return;
        }

        float maxSpeed = maxOf(speed, 2f);
        float maxPower = maxOf(power, 60f);
        float stepX = w / (CAPACITY - 1f);
        float firstX = w - (count - 1) * stepX;

        // Speed leads: it is the boat's run, and the reason to take the next stroke.
        path.reset();
        for (int i = 0; i < count; i++) {
            float x = firstX + i * stepX;
            float y = plotH * (1f - valueAt(speed, i) / maxSpeed);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        Path fill = new Path(path);
        fill.lineTo(firstX + (count - 1) * stepX, plotH);
        fill.lineTo(firstX, plotH);
        fill.close();
        fillPaint.setShader(new LinearGradient(0, 0, 0, plotH,
                (powerColor & 0x00FFFFFF) | 0x59000000,
                powerColor & 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(path, ratePaint);

        path.reset();
        for (int i = 0; i < count; i++) {
            float x = firstX + i * stepX;
            float y = plotH * (1f - valueAt(power, i) / maxPower);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        canvas.drawPath(path, powerPaint);

        canvas.drawText(String.format(java.util.Locale.US, "%.1f m/s peak", maxSpeed),
                dp(4f), h - dp(3f), labelPaint);
        String powerLabel = (int) maxPower + "W peak";
        canvas.drawText(powerLabel, w - labelPaint.measureText(powerLabel) - dp(4f),
                h - dp(3f), labelPaint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
