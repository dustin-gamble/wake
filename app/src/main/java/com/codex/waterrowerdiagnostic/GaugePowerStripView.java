package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.view.View;

/**
 * One bar per stroke across the full width, coloured against this session's average: green a
 * stroke that beat it, amber one that fell short, blue one that held it. The newest bar grows in
 * and the whole strip slides left as it arrives, so every stroke visibly lands.
 *
 * <p>The average is the session's, not the visible window's - a tired last minute should read as
 * amber against the whole row, not be excused by averaging only itself.
 */
final class GaugePowerStripView extends View {

    private static final int CAPACITY = 72;

    private final float[] bars = new float[CAPACITY];
    private int count;
    private int head;
    private double sessionSum;
    private int sessionStrokes;
    private float lastBar;
    /** 0 to 1 while the newest bar grows in and the strip slides one slot left. */
    private float arrive = 1f;
    private float shownMax = 150f;
    private long lastFrameMs;
    /** Captions, rebuilt once per stroke rather than on every frame of the slide. */
    private String lastText = "";
    private String avgText = "";

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avgLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strong = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final int good = Color.parseColor("#35D0BA");
    private final int blue = Color.parseColor("#6F8CFF");
    private final int warn = Color.parseColor("#F0B132");

    GaugePowerStripView(Context context) {
        super(context);
        avgLine.setStyle(Paint.Style.STROKE);
        avgLine.setStrokeWidth(dp(1.2f));
        avgLine.setColor(Color.argb(170, 230, 237, 247));
        avgLine.setPathEffect(new DashPathEffect(new float[]{dp(6f), dp(4f)}, 0f));
        text.setColor(Color.parseColor("#8D9BB0"));
        text.setTextSize(dp(10f));
        strong.setFakeBoldText(true);
        strong.setTextSize(dp(15f));
    }

    /** A stroke's average power, in watts. */
    void addStroke(float watts) {
        if (!(watts > 0f) || Float.isInfinite(watts)) {
            return;
        }
        bars[head] = watts;
        head = (head + 1) % CAPACITY;
        count = Math.min(CAPACITY, count + 1);
        sessionSum += watts;
        sessionStrokes++;
        lastBar = watts;
        float avg = average();
        float delta = avg > 0 ? (lastBar - avg) / avg * 100f : 0f;
        lastText = Math.round(lastBar) + " W  " + (delta >= 0 ? "+" : "") + Math.round(delta) + "%";
        avgText = "avg " + Math.round(avg) + " W  \u00b7  " + sessionStrokes + " strokes";
        arrive = 0f;
        postInvalidateOnAnimation();
    }

    float average() {
        return sessionStrokes > 0 ? (float) (sessionSum / sessionStrokes) : 0f;
    }

    private int colorFor(float v, float avg) {
        if (v >= avg * 1.05f) return good;
        if (v <= avg * 0.93f) return warn;
        return blue;
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

        float labelW = dp(150f);
        float plotW = w - labelW;
        float top = dp(4f);
        float bottom = h - dp(3f);
        if (count == 0) {
            c.drawText("One bar per stroke, coloured against your average - start rowing", dp(4f), h / 2f + dp(4f), text);
            return;
        }
        float avg = average();
        float max = avg * 1.6f;
        for (int i = 0; i < count; i++) {
            max = Math.max(max, bars[i]);
        }
        shownMax += (max - shownMax) * Math.min(1f, 3f * dt);
        if (shownMax < 1f) {
            shownMax = max;
        }
        arrive = Math.min(1f, arrive + dt / 0.35f);
        float ease = 1f - (1f - arrive) * (1f - arrive);

        float slot = plotW / CAPACITY;
        // Slide: until the newest bar has arrived, everything sits one slot further right.
        float slide = (1f - ease) * slot;
        for (int i = 0; i < count; i++) {
            int idx = (head - count + i + CAPACITY) % CAPACITY;
            float v = bars[idx];
            boolean newest = i == count - 1;
            float x = plotW - (count - i) * slot + slide;
            if (x + slot < 0) {
                continue;
            }
            float barH = (bottom - top) * Math.min(1f, v / shownMax) * (newest ? ease : 1f);
            bar.setColor(colorFor(v, avg));
            bar.setAlpha(newest ? 255 : 150 + (int) (90f * i / count));
            c.drawRect(x + slot * 0.14f, bottom - barH, x + slot * 0.86f, bottom, bar);
        }
        float avgY = bottom - (bottom - top) * Math.min(1f, avg / shownMax);
        c.drawLine(0, avgY, plotW, avgY, avgLine);

        // The last stroke against the average, big enough to read mid-drive.
        strong.setColor(colorFor(lastBar, avg));
        c.drawText(lastText, plotW + dp(10f), top + dp(16f), strong);
        c.drawText(avgText, plotW + dp(10f), top + dp(32f), text);

        if (arrive < 1f || Math.abs(max - shownMax) > 0.5f) {
            postInvalidateOnAnimation();
        }
    }
}
