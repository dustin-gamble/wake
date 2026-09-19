package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The card that comes up over the gauges when a piece ends: peak watts, best 500 m pace and the
 * energy it took, counting up into place, with distance, time and strokes underneath. Tap it to
 * close; taking a stroke closes it too, so it never sits over a new piece.
 */
final class GaugeSummaryView extends View {

    private final Paint scrim = new Paint();
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF tileRect = new RectF();

    private final int good = Color.parseColor("#35D0BA");
    private final int blue = Color.parseColor("#6F8CFF");
    private final int warn = Color.parseColor("#F0B132");
    private final int dim = Color.parseColor("#8D9BB0");

    private float peakWatts;
    private float bestPace;
    private float energyKj;
    private String kcalText = "";
    private String footer = "";
    private boolean newPeak;
    private float age;
    private long lastFrameMs;

    GaugeSummaryView(Context context) {
        super(context);
        scrim.setColor(Color.argb(170, 4, 7, 12));
        card.setColor(Color.parseColor("#111722"));
        tile.setColor(Color.parseColor("#161D2B"));
        title.setColor(Color.parseColor("#E6EDF7"));
        title.setFakeBoldText(true);
        title.setLetterSpacing(0.14f);
        title.setTextAlign(Paint.Align.CENTER);
        value.setFakeBoldText(true);
        value.setTypeface(android.graphics.Typeface.MONOSPACE);
        value.setTextAlign(Paint.Align.CENTER);
        caption.setColor(Color.parseColor("#8D9BB0"));
        caption.setFakeBoldText(true);
        caption.setLetterSpacing(0.1f);
        caption.setTextAlign(Paint.Align.CENTER);
        foot.setColor(Color.parseColor("#8D9BB0"));
        foot.setTextAlign(Paint.Align.CENTER);
        setVisibility(GONE);
        setOnClickListener(v -> hide());
    }

    /**
     * @param newPeak whether this piece's peak power beat every earlier piece this session
     */
    void show(float peakWatts, float bestPaceSec, float energyKj, String kcalText, String footer, boolean newPeak) {
        this.peakWatts = peakWatts;
        this.bestPace = bestPaceSec;
        this.energyKj = energyKj;
        this.kcalText = kcalText;
        this.footer = footer;
        this.newPeak = newPeak;
        age = 0f;
        lastFrameMs = 0;
        setVisibility(VISIBLE);
        setClickable(true);
        postInvalidateOnAnimation();
    }

    void hide() {
        if (getVisibility() != GONE) {
            setVisibility(GONE);
            setClickable(false);
        }
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
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
        age += dt;

        float in = Math.min(1f, age / 0.3f);
        scrim.setAlpha((int) (170 * in));
        c.drawRect(0, 0, w, h, scrim);

        float cw = Math.min(w - dp(40f), dp(760f));
        float ch = Math.min(h - dp(40f), dp(300f));
        float cx = w / 2f;
        float cy = h / 2f + (1f - in) * dp(40f);
        rect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f);
        c.drawRoundRect(rect, dp(14f), dp(14f), card);

        title.setTextSize(dp(18f));
        c.drawText("PIECE DONE", cx, rect.top + dp(34f), title);

        // Figures count up over the first second, one after another.
        float t0 = clamp01((age - 0.2f) / 0.8f);
        float t1 = clamp01((age - 0.45f) / 0.8f);
        float t2 = clamp01((age - 0.7f) / 0.8f);
        float tileW = (cw - dp(48f)) / 3f;
        float tileTop = rect.top + dp(54f);
        float tileH = ch - dp(54f) - dp(56f);
        drawTile(c, rect.left + dp(12f), tileTop, tileW, tileH, "PEAK POWER",
                Math.round(peakWatts * ease(t0)) + " W", newPeak && t0 >= 1f ? "SESSION BEST" : "", warn, t0);
        // Pace counts DOWN from 3:30 onto the best split, which reads as getting faster.
        float paceShown = bestPace > 0 ? 210f + (bestPace - 210f) * ease(t1) : 0f;
        drawTile(c, rect.left + dp(24f) + tileW, tileTop, tileW, tileH, "BEST PACE /500",
                bestPace > 0 ? PersonalBests.formatPace(paceShown) : "--:--", "", good, t1);
        drawTile(c, rect.left + dp(36f) + tileW * 2f, tileTop, tileW, tileH, "ENERGY",
                Math.round(energyKj * ease(t2)) + " kJ", kcalText, blue, t2);

        foot.setTextSize(dp(12f));
        c.drawText(footer, cx, rect.bottom - dp(30f), foot);
        foot.setTextSize(dp(10f));
        c.drawText("tap to close  ·  or just take a stroke", cx, rect.bottom - dp(12f), foot);

        if (age < 1.6f) {
            postInvalidateOnAnimation();
        }
    }

    private void drawTile(Canvas c, float x, float y, float tw, float th, String label, String text,
                          String sub, int color, float t) {
        tileRect.set(x, y, x + tw, y + th);
        c.drawRoundRect(tileRect, dp(10f), dp(10f), tile);
        caption.setTextSize(dp(10f));
        caption.setColor(dim);
        c.drawText(label, x + tw / 2f, y + dp(20f), caption);
        value.setColor(color);
        value.setTextSize(dp(34f) * (0.9f + 0.1f * ease(t)));
        c.drawText(text, x + tw / 2f, y + th / 2f + dp(14f), value);
        if (!sub.isEmpty()) {
            caption.setColor(color);
            c.drawText(sub, x + tw / 2f, y + th - dp(10f), caption);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float ease(float t) {
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }
}
