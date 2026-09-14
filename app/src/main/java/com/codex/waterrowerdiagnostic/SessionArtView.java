package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * SESSION ART: a finished session drawn as a poster.
 *
 * <p>Every stroke is a spoke on a slowly widening spiral - its length the stroke's power, its colour
 * the stroke rate, from cool blue for a gentle rating through teal and amber to coral for a sprint.
 * A steady session makes a calm, even rosette; intervals make bursts. The numbers sit in the middle.
 * No two sessions look the same, which is the point: it is something worth sharing.
 */
final class SessionArtView extends View {

    private float[] power = new float[0];
    private float[] rate = new float[0];
    private String date = "";
    private String distance = "";
    private String time = "";
    private String watts = "";
    private String kcal = "";

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    SessionArtView(Context context) {
        super(context);
        paint.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
    }

    void setSession(float[] strokePower, float[] strokeRate, String date, String distance, String time,
                    String watts, String kcal) {
        this.power = strokePower;
        this.rate = strokeRate;
        this.date = date;
        this.distance = distance;
        this.time = time;
        this.watts = watts;
        this.kcal = kcal;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private static int rateColour(float spm) {
        float t = Math.max(0f, Math.min(1f, (spm - 16f) / 18f));
        int[] stops = {0xFF6F8CFF, 0xFF35D0BA, 0xFFF0B132, 0xFFF0655D};
        float f = t * (stops.length - 1);
        int i = Math.min(stops.length - 2, (int) f);
        float u = f - i;
        int a = stops[i];
        int b = stops[i + 1];
        int r = (int) (((a >> 16) & 0xFF) * (1 - u) + ((b >> 16) & 0xFF) * u);
        int g = (int) (((a >> 8) & 0xFF) * (1 - u) + ((b >> 8) & 0xFF) * u);
        int bl = (int) ((a & 0xFF) * (1 - u) + (b & 0xFF) * u);
        return Color.rgb(r, g, bl);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        float maxR = Math.min(w, h) * 0.46f;
        paint.setShader(new RadialGradient(cx, cy, maxR * 1.4f, 0xFF10203A, 0xFF03070E, Shader.TileMode.CLAMP));
        paint.setStyle(Paint.Style.FILL);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        int n = Math.min(power.length, rate.length);
        if (n > 0) {
            float maxP = 1f;
            for (int i = 0; i < n; i++) {
                maxP = Math.max(maxP, power[i]);
            }
            float inner = maxR * 0.34f;
            float spokeMax = maxR * 0.22f;
            float turns = Math.max(1f, n / 110f);
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < n; i++) {
                float f = i / (float) Math.max(1, n - 1);
                double angle = -Math.PI / 2 + f * turns * 2 * Math.PI;
                float r0 = inner + f * (maxR - inner - spokeMax);
                float len = spokeMax * (0.15f + 0.85f * power[i] / maxP);
                float x0 = cx + (float) Math.cos(angle) * r0;
                float y0 = cy + (float) Math.sin(angle) * r0;
                float x1 = cx + (float) Math.cos(angle) * (r0 + len);
                float y1 = cy + (float) Math.sin(angle) * (r0 + len);
                paint.setColor(rateColour(rate[i]));
                paint.setAlpha(150 + (int) (105 * power[i] / maxP));
                paint.setStrokeWidth(dp(2.2f));
                c.drawLine(x0, y0, x1, y1, paint);
                paint.setAlpha(60);
                c.drawCircle(x1, y1, dp(1.6f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        text.setColor(0xFFE6EDF7);
        text.setFakeBoldText(true);
        text.setTextSize(dp(46f));
        c.drawText(distance, cx, cy - dp(6f), text);
        text.setFakeBoldText(false);
        text.setTextSize(dp(15f));
        text.setColor(0xFF8D9BB0);
        c.drawText(time + "  ·  " + watts + "  ·  " + kcal, cx, cy + dp(22f), text);
        text.setTextSize(dp(11f));
        text.setColor(0xFF5D6B80);
        c.drawText(date + "  ·  " + n + " strokes", cx, cy + dp(42f), text);
        text.setTextSize(dp(13f));
        text.setLetterSpacing(0.3f);
        text.setColor(0xFF35D0BA);
        c.drawText("WAKE", cx, h - dp(22f), text);
        text.setLetterSpacing(0f);
    }
}
