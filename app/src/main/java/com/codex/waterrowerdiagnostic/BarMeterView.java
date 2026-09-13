package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Labelled horizontal bar that eases toward its target so live values do not jitter. */
final class BarMeterView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private String label = "";
    private String valueText = "0";
    private float target;
    private float shown;
    private float scaleMax = 100f;

    BarMeterView(Context context, String label, int color, float scaleMax) {
        super(context);
        this.label = label;
        this.scaleMax = scaleMax;

        trackPaint.setColor(Color.parseColor("#18202C"));
        barPaint.setColor(color);
        labelPaint.setColor(Color.parseColor("#8D9BB0"));
        labelPaint.setTextSize(dp(11f));
        valuePaint.setColor(Color.parseColor("#E6EDF7"));
        valuePaint.setTextSize(dp(15f));
        valuePaint.setFakeBoldText(true);
    }

    void setValue(float value, String text) {
        this.valueText = text;
        // Auto-range upward so a hard effort does not clip off the end of the bar.
        if (value > scaleMax) {
            scaleMax = value * 1.15f;
        }
        target = Math.max(0f, Math.min(1f, value / scaleMax));
        postInvalidateOnAnimation();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(
                resolveSize((int) dp(200f), widthMeasureSpec),
                resolveSize((int) dp(44f), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float barH = dp(8f);
        float barY = h - barH;

        canvas.drawText(label, 0, dp(12f), labelPaint);
        canvas.drawText(valueText, w - valuePaint.measureText(valueText), dp(14f), valuePaint);

        rect.set(0, barY, w, barY + barH);
        canvas.drawRoundRect(rect, barH / 2f, barH / 2f, trackPaint);

        // Ease toward the target, repainting until it settles.
        shown += (target - shown) * 0.25f;
        if (Math.abs(target - shown) < 0.002f) {
            shown = target;
        } else {
            postInvalidateOnAnimation();
        }

        if (shown > 0f) {
            rect.set(0, barY, Math.max(barH, w * shown), barY + barH);
            canvas.drawRoundRect(rect, barH / 2f, barH / 2f, barPaint);
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
