package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Drawn over every SHUFFLE game: the 3-2-1 card naming the next game before a switch, the banner
 * announcing the one just dealt, and the "+points" that float up from each scored stroke. It also
 * carries the round furniture - the wildcard badge this game was dealt, the boss and its health bar
 * at the end of a round, and the flash for a combo that survived a switch.
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

    /* The wildcard this game was dealt with: a badge that stays for the whole game. */
    static final int WILD_NONE = 0;
    static final int WILD_DOUBLE = 1;
    static final int WILD_RATE_CAP = 2;
    static final int WILD_NO_REST = 3;
    private int wildKind = WILD_NONE;
    private String wildName = "";
    private String wildDetail = "";
    private float wildAge = 99f;

    /* The boss round: the last game of a round, worth double, with a health bar to empty. */
    private boolean boss;
    private String bossTitle = "";
    private int bossHp;
    private int bossMaxHp = 1;
    private float bossShown = 1f;
    private float bossHitAge = 99f;
    private float bossDownAge = 99f;
    private String bossHpText = "";
    private int bossHpKey = Integer.MIN_VALUE;
    private String bossDownText = "";
    private String bossHeading = "";

    /* The combo carried across a switch. */
    private float carryAge = 99f;
    private String carryText = "";
    private String carryMultText = "";

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

    /** The wildcard dealt with this game, or {@link #WILD_NONE} for a clean one. */
    void setWildcard(int kind, String name, String detail) {
        wildKind = kind;
        wildName = name == null ? "" : name;
        wildDetail = detail == null ? "" : detail;
        wildAge = 0f;
        wake();
    }

    /** Starts (or clears) the boss round. */
    void setBoss(boolean on, String title, int maxHp) {
        boss = on;
        bossTitle = title == null ? "" : title;
        bossMaxHp = Math.max(1, maxHp);
        bossHp = bossMaxHp;
        bossShown = 1f;
        bossHitAge = 99f;
        bossDownAge = 99f;
        bossHpKey = Integer.MIN_VALUE;
        bossHeading = "BOSS ROUND  \u00b7  " + bossTitle;
        wake();
    }

    /** The boss takes the points just scored. */
    void bossDamage(int hp) {
        bossHp = Math.max(0, hp);
        bossHitAge = 0f;
        wake();
    }

    void bossDown(int bonus) {
        bossHp = 0;
        bossDownAge = 0f;
        bossDownText = "BOSS DOWN   +" + bonus;
        wake();
    }

    /** A combo that survived a switch, and what it paid. */
    void carry(int bonus, int multiplier) {
        carryAge = 0f;
        carryText = "COMBO CARRIED   +" + bonus;
        carryMultText = "x" + multiplier + " STILL RUNNING";
        wake();
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
        wildAge += dt;
        bossHitAge += dt;
        bossDownAge += dt;
        carryAge += dt;

        // The wildcard badge and the boss sit at the top of the game area, clear of the vitals
        // strip above it. Both are drawn under the countdown card, which takes the middle.
        if (wildKind != WILD_NONE) {
            drawWildcard(c, w);
            again = true;
        }
        if (boss || bossDownAge < 3f) {
            drawBoss(c, w, dt);
            again = true;
        }
        if (carryAge < 2.2f) {
            drawCarry(c, w, h);
            again = true;
        }

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

    private int wildColour() {
        switch (wildKind) {
            case WILD_DOUBLE:
                return warn;
            case WILD_RATE_CAP:
                return Color.parseColor("#6F8CFF");
            case WILD_NO_REST:
                return Color.parseColor("#F0655D");
            default:
                return purple;
        }
    }

    /** The wildcard badge, top left: what this game is being played under. */
    private void drawWildcard(Canvas c, float w) {
        int colour = wildColour();
        // It arrives with a flick and then breathes, so it does not read as a static label.
        float in = Math.min(1f, wildAge / 0.35f);
        float pop = 1f + 0.25f * Math.max(0f, 1f - wildAge * 3f);
        float breathe = 0.6f + 0.4f * (float) Math.sin(wildAge * 3.0);
        float bw = dp(250f) * pop;
        float bh = dp(50f);
        float left = dp(14f) - (1f - in) * dp(40f);
        rect.set(left, dp(10f), left + bw, dp(10f) + bh);
        card.setAlpha((int) (230 * in));
        c.drawRoundRect(rect, dp(10f), dp(10f), card);
        ring.setStrokeWidth(dp(2f));
        ring.setColor(colour);
        ring.setAlpha((int) (255 * in * (0.45f + 0.55f * breathe)));
        c.drawRoundRect(rect, dp(10f), dp(10f), ring);
        small.setTextAlign(Paint.Align.LEFT);
        small.setColor(dim);
        small.setAlpha((int) (255 * in));
        small.setTextSize(dp(8.5f));
        c.drawText("WILDCARD", rect.left + dp(12f), rect.top + dp(14f), small);
        small.setColor(colour);
        small.setTextSize(dp(13f));
        c.drawText(wildName, rect.left + dp(12f), rect.top + dp(29f), small);
        small.setColor(dim);
        small.setTextSize(dp(9f));
        c.drawText(wildDetail, rect.left + dp(12f), rect.top + dp(41f), small);
        small.setTextAlign(Paint.Align.CENTER);
    }

    /**
     * The boss: a health bar across the top that only empties when points land, and a face that
     * flinches on every hit and tumbles away when it is beaten.
     */
    private void drawBoss(Canvas c, float w, float dt) {
        float wantShare = bossMaxHp > 0 ? bossHp / (float) bossMaxHp : 0f;
        bossShown += (wantShare - bossShown) * Math.min(1f, dt * 5f);
        boolean down = bossDownAge < 3f;
        // Clamped: on a narrow layout w - 300dp goes negative, which inverts the bar's rect and
        // draws nothing at all.
        float bw = Math.max(dp(140f), Math.min(w - dp(300f), dp(460f)));
        float cx = w / 2f;
        float top = dp(14f);
        // The whole boss shakes for a moment after a hit.
        float shake = bossHitAge < 0.3f ? (1f - bossHitAge / 0.3f) * dp(5f) : 0f;
        float jitter = shake * (float) Math.sin(bossHitAge * 90.0);

        // Head, bobbing; it falls off the top when beaten.
        float headR = dp(22f);
        float bob = (float) Math.sin(System.currentTimeMillis() / 420.0) * dp(3f);
        float fall = down ? bossDownAge * bossDownAge * dp(220f) : 0f;
        float hx = cx + jitter;
        float hy = top + headR + bob - fall;
        int flesh = bossHitAge < 0.18f ? 0xFFFFFFFF : 0xFFF0655D;
        card.setAlpha(255);
        popPaint.setColor(flesh);
        popPaint.setAlpha(down ? (int) (255 * Math.max(0f, 1f - bossDownAge / 2.5f)) : 255);
        c.save();
        if (down) {
            c.rotate(bossDownAge * 240f, hx, hy);
        }
        c.drawCircle(hx, hy, headR, popPaint);
        popPaint.setColor(0xFF120A0A);
        c.drawCircle(hx - headR * 0.35f, hy - headR * 0.15f, headR * 0.13f, popPaint);
        c.drawCircle(hx + headR * 0.35f, hy - headR * 0.15f, headR * 0.13f, popPaint);
        // The scowl straightens out as its health goes, then turns over when it is down.
        float mouth = down ? -headR * 0.3f : headR * (0.30f - 0.22f * (1f - bossShown));
        ring.setColor(0xFF120A0A);
        ring.setAlpha(255);
        ring.setStrokeWidth(dp(3f));
        rect.set(hx - headR * 0.45f, hy + headR * 0.15f - mouth, hx + headR * 0.45f, hy + headR * 0.15f + mouth);
        c.drawArc(rect, down ? 180f : 0f, 180f, false, ring);
        c.restore();

        // Health bar.
        float barTop = top + headR * 2f + dp(8f);
        rect.set(cx - bw / 2f + jitter, barTop, cx + bw / 2f + jitter, barTop + dp(16f));
        card.setAlpha(230);
        c.drawRoundRect(rect, dp(6f), dp(6f), card);
        float inner = (bw - dp(6f)) * Math.max(0f, Math.min(1f, bossShown));
        popPaint.setColor(bossShown > 0.5f ? 0xFFF0655D : bossShown > 0.2f ? warn : good);
        popPaint.setAlpha(255);
        c.drawRect(rect.left + dp(3f), barTop + dp(3f), rect.left + dp(3f) + inner, barTop + dp(13f), popPaint);

        int hpKey = bossHp;
        if (hpKey != bossHpKey) {
            bossHpKey = hpKey;
            bossHpText = bossHp + " / " + bossMaxHp;
        }
        small.setColor(warn);
        small.setAlpha(255);
        small.setTextSize(dp(11f));
        // bossHeading is built once in setBoss: this runs every frame for the whole boss round.
        c.drawText(bossHeading, cx, barTop - dp(4f), small);
        small.setColor(text);
        small.setTextSize(dp(10f));
        c.drawText(bossHpText, cx, barTop + dp(28f), small);

        if (down) {
            float t = Math.min(1f, bossDownAge / 0.4f);
            big.setColor(good);
            big.setAlpha((int) (255 * Math.max(0f, 1f - bossDownAge / 3f)));
            big.setTextSize(dp(34f) * (0.7f + 0.3f * t));
            c.drawText(bossDownText, cx, barTop + dp(76f), big);
        }
    }

    /** The combo that survived a switch, floating up from the middle. */
    private void drawCarry(Canvas c, float w, float h) {
        float t = Math.min(1f, carryAge / 2.2f);
        float y = h * 0.44f - t * dp(60f);
        int alpha = (int) (255 * (1f - t * t));
        big.setColor(warn);
        big.setAlpha(alpha);
        big.setTextSize(dp(30f) * (1f + 0.2f * Math.max(0f, 1f - carryAge * 4f)));
        c.drawText(carryText, w / 2f, y, big);
        small.setColor(text);
        small.setAlpha(alpha);
        small.setTextSize(dp(12f));
        c.drawText(carryMultText, w / 2f, y + dp(22f), small);
    }
}
