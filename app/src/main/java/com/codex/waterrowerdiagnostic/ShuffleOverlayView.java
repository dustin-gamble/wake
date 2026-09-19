package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Drawn over every SHUFFLE game: the 3-2-1 card naming the next game before a switch, the banner
 * announcing the one just dealt, and the "+points" that float up from each scored stroke.
 *
 * <p>Never takes a touch - it is not clickable, so taps fall through to the game underneath.
 * Draws nothing and schedules no frames when there is nothing to show.
 */
final class ShuffleOverlayView extends View {

    /** What the overlay asks the shuffle each frame. */
    interface Source {
        /** Rowing-clock seconds until the switch; large or negative when no switch is coming. */
        double secondsLeft();

        String nextTitle();
    }

    private static final int POPS = 6;
    private static final float POP_LIFE = 1.3f;

    private final Source source;
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint popPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int purple = Color.parseColor("#B48CFF");
    private final int good = Color.parseColor("#35D0BA");
    private final int warn = Color.parseColor("#F0B132");
    private final int text = Color.parseColor("#E6EDF7");
    private final int dim = Color.parseColor("#8D9BB0");

    private static final String[] DIGITS = {"0", "1", "2", "3"};
    private final String[] popText = new String[POPS];
    private final String[] popComboText = new String[POPS];
    private final int[] popMult = new int[POPS];
    private final float[] popAge = new float[POPS];
    private final float[] popX = new float[POPS];
    private int popNext;

    private String announceTitle = "";
    private String announceSub = "";
    private float announceAge = 99f;
    private long lastFrameMs;

    ShuffleOverlayView(Context context, Source source) {
        super(context);
        this.source = source;
        setClickable(false);
        setFocusable(false);
        for (int i = 0; i < POPS; i++) {
            popAge[i] = 99f;
        }
        card.setColor(Color.argb(230, 17, 23, 34));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        big.setFakeBoldText(true);
        big.setTextAlign(Paint.Align.CENTER);
        small.setFakeBoldText(true);
        small.setLetterSpacing(0.14f);
        small.setTextAlign(Paint.Align.CENTER);
        popPaint.setFakeBoldText(true);
        popPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** A scored stroke: "+points", with the combo multiplier when it is above one. */
    void pop(int points, int multiplier) {
        int i = popNext;
        popNext = (popNext + 1) % POPS;
        popText[i] = "+" + points;
        popComboText[i] = "x" + multiplier + " COMBO";
        popMult[i] = multiplier;
        popAge[i] = 0f;
        // Spread successive pops a little so a fast rate does not stack them into one blur.
        popX[i] = 0.5f + ((i % 3) - 1) * 0.06f;
        wake();
    }

    /** The game just dealt, shown as a banner for a moment. */
    void announce(String title, String sub) {
        announceTitle = title;
        announceSub = sub;
        announceAge = 0f;
        wake();
    }

    /** Called by the shuffle's once-a-second tick so the countdown card appears on time. */
    void wake() {
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
        long now = System.currentTimeMillis();
        // After an idle spell the first frame counts as a fresh start, not a 0.1 s jump.
        float dt = lastFrameMs > 0 && now - lastFrameMs < 250 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;
        boolean again = false;

        // Score pops rise from just under the vitals strip and fade.
        for (int i = 0; i < POPS; i++) {
            if (popAge[i] >= POP_LIFE) {
                continue;
            }
            popAge[i] += dt;
            float t = Math.min(1f, popAge[i] / POP_LIFE);
            float y = dp(70f) - t * dp(46f);
            int alpha = (int) (255 * (1f - t * t));
            popPaint.setColor(popMult[i] > 1 ? warn : good);
            popPaint.setAlpha(alpha);
            popPaint.setTextSize(dp(26f) * (1f + 0.25f * Math.max(0f, 1f - t * 5f)));
            c.drawText(popText[i], w * popX[i], y, popPaint);
            if (popMult[i] > 1) {
                popPaint.setTextSize(dp(12f));
                c.drawText(popComboText[i], w * popX[i], y + dp(16f), popPaint);
            }
            again = true;
        }

        // Banner for the game just dealt.
        if (announceAge < 2.2f) {
            announceAge += dt;
            float t = announceAge;
            float in = Math.min(1f, t / 0.25f);
            float out = t > 1.8f ? Math.max(0f, 1f - (t - 1.8f) / 0.4f) : 1f;
            float a = in * out;
            float bw = Math.min(w - dp(40f), dp(620f));
            float by = h * 0.28f;
            rect.set(w / 2f - bw / 2f, by - dp(46f), w / 2f + bw / 2f, by + dp(40f));
            card.setAlpha((int) (230 * a));
            c.drawRoundRect(rect, dp(14f), dp(14f), card);
            small.setColor(purple);
            small.setAlpha((int) (255 * a));
            small.setTextSize(dp(12f));
            c.drawText("SHUFFLE  ·  NOW PLAYING", w / 2f, by - dp(22f), small);
            big.setColor(text);
            big.setAlpha((int) (255 * a));
            big.setTextSize(dp(30f) * (0.85f + 0.15f * in));
            c.drawText(announceTitle, w / 2f, by + dp(10f), big);
            if (!announceSub.isEmpty()) {
                small.setColor(dim);
                small.setAlpha((int) (255 * a));
                small.setTextSize(dp(10f));
                c.drawText(announceSub, w / 2f, by + dp(30f), small);
            }
            again = true;
        }

        // The 3-2-1: from five seconds out, a card names what is coming; the last three count down.
        double left = source.secondsLeft();
        if (left > 0 && left <= 5.0) {
            float cw = Math.min(w - dp(40f), dp(420f));
            float chH = dp(200f);
            float cx = w / 2f;
            float cy = h * 0.55f;
            float in = (float) Math.min(1.0, (5.0 - left) / 0.4);
            rect.set(cx - cw / 2f, cy - chH / 2f, cx + cw / 2f, cy + chH / 2f);
            card.setAlpha((int) (230 * in));
            c.drawRoundRect(rect, dp(16f), dp(16f), card);
            small.setColor(purple);
            small.setAlpha(255);
            small.setTextSize(dp(12f));
            c.drawText("NEXT UP", cx, rect.top + dp(26f), small);
            big.setColor(text);
            big.setAlpha(255);
            big.setTextSize(dp(24f));
            c.drawText(source.nextTitle(), cx, rect.top + dp(58f), big);

            int digit = (int) Math.ceil(left);
            float frac = (float) (digit - left);       // 0 at the start of each second, 1 at its end
            ring.setStrokeWidth(dp(6f));
            float r = dp(46f);
            float rcx = cx;
            float rcy = rect.bottom - dp(64f);
            rect.set(rcx - r, rcy - r, rcx + r, rcy + r);
            ring.setColor(Color.argb(60, 180, 140, 255));
            c.drawArc(rect, 0f, 360f, false, ring);
            ring.setColor(digit <= 3 ? warn : purple);
            c.drawArc(rect, -90f, 360f * (1f - frac), false, ring);
            if (digit <= 3) {
                big.setColor(warn);
                big.setTextSize(dp(52f) * (1.35f - 0.35f * Math.min(1f, frac * 4f)));
                c.drawText(DIGITS[Math.max(0, Math.min(3, digit))], rcx, rcy + dp(18f), big);
            } else {
                small.setColor(dim);
                small.setTextSize(dp(11f));
                c.drawText("KEEP ROWING", rcx, rcy + dp(4f), small);
            }
            again = true;
        }

        if (again) {
            postInvalidateOnAnimation();
        } else {
            lastFrameMs = 0;
        }
    }
}
