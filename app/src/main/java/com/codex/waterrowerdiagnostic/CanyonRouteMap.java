package com.codex.waterrowerdiagnostic;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * The parts of CANYON that both modes share: the medal maths, the route tiers unlocked by score,
 * the tappable route map, the rival's taunts and the photo-finish card.
 *
 * <p>It owns its paints and paths as fields and allocates nothing while drawing, like every other
 * game surface here. Nothing in it touches the protocol; the two games hand it numbers.
 *
 * <p><b>Medals are the rower's own speeds, never a constant.</b> A split is gold when it was rowed
 * at or above the profile's high speed, silver at typical, bronze at low - so the same run is a
 * fair test for whoever is on the machine.
 */
final class CanyonRouteMap {

    static final int GOLD = 0;
    static final int SILVER = 1;
    static final int BRONZE = 2;
    static final int NO_MEDAL = 3;

    static final String[] MEDAL_NAME = {"GOLD", "SILVER", "BRONZE", "NO MEDAL"};
    static final int[] MEDAL_COLOR = {0xFFF5C518, 0xFFD6DEE8, 0xFFCE8A4E, 0xFF5D6B80};

    /** Score at which each route tier unlocks, and what it actually changes. */
    static final int[] TIER_SCORE = {30, 80, 150};
    static final String[] TIER_NAME = {"SLOT ROUTE", "TURBO CHARGE", "NIGHT RUN"};
    static final String[] TIER_BLURB = {
            "a third road through the middle of every fork",
            "a fourth boost charge, and boost pushes harder",
            "start at golden hour - prizes count double",
    };

    /** What the rival says once it is in front of you. */
    private static final String[] TAUNT_AHEAD = {
            "SEE YOU AT THE EXIT",
            "IS THAT YOUR PACE?",
            "I'LL WAIT AT THE LINE",
            "NICE OF YOU TO COME",
            "TOO SLOW",
    };
    /** And what it says while it is eating your wake. */
    private static final String[] TAUNT_BEHIND = {
            "LUCKY GUST",
            "ENJOY IT WHILE IT LASTS",
            "I'M ONLY WARMING UP",
            "THAT WON'T HOLD",
    };
    private static final String[] TAUNT_SURGE = {
            "WATCH THIS",
            "OPENING IT UP",
            "HOLD ON",
    };

    static final int TAUNT_AHEAD_KIND = 0;
    static final int TAUNT_BEHIND_KIND = 1;
    static final int TAUNT_SURGE_KIND = 2;

    static String taunt(int kind, int n) {
        String[] pool = kind == TAUNT_AHEAD_KIND ? TAUNT_AHEAD
                : kind == TAUNT_BEHIND_KIND ? TAUNT_BEHIND : TAUNT_SURGE;
        return pool[Math.abs(n) % pool.length];
    }

    /** How many route tiers this rower's best score has opened. */
    static int tiers(float bestScore) {
        int n = 0;
        for (int i = 0; i < TIER_SCORE.length; i++) {
            if (bestScore >= TIER_SCORE[i]) {
                n++;
            }
        }
        return n;
    }

    /** The next tier's index, or -1 when everything is open. */
    static int nextTier(float bestScore) {
        int t = tiers(bestScore);
        return t >= TIER_SCORE.length ? -1 : t;
    }

    /**
     * The medal for covering {@code metres} in {@code seconds}, against the rower's own speeds.
     */
    static int medal(double seconds, double metres, RowerProfile p) {
        if (seconds <= 0.01 || metres <= 0) {
            return NO_MEDAL;
        }
        double v = metres / seconds;
        if (v >= p.highSpeed()) {
            return GOLD;
        }
        if (v >= p.typicalSpeed()) {
            return SILVER;
        }
        if (v >= p.lowSpeed()) {
            return BRONZE;
        }
        return NO_MEDAL;
    }

    /** Target seconds for a medal over a distance, for showing the rower what to beat. */
    static double target(int medal, double metres, RowerProfile p) {
        double v = medal == GOLD ? p.highSpeed() : medal == SILVER ? p.typicalSpeed() : p.lowSpeed();
        return metres / Math.max(0.5, v);
    }

    static String time(double seconds) {
        int total = (int) Math.floor(seconds);
        int tenth = (int) Math.floor((seconds - total) * 10);
        return String.format(java.util.Locale.US, "%d:%02d.%d", total / 60, total % 60, tenth);
    }

    /* ---------- drawing ---------- */

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private boolean expanded;
    /** Cached "TAKEN: ..." line and the number of forks it was built from. */
    private String takenText;
    private int takenCount = -1;
    /** Bounds of the last drawn map, for the tap test. */
    private float hx;
    private float hy;
    private float hw;
    private float hh;

    CanyonRouteMap(float density) {
        this.density = density;
    }

    private float dp(float v) {
        return v * density;
    }

    void collapse() {
        expanded = false;
        takenText = null;
        takenCount = -1;
    }

    /** True when the tap landed on the map, which toggles the branch panel. */
    boolean tap(float tx, float ty) {
        if (hw <= 0) {
            return false;
        }
        if (tx >= hx - dp(8f) && tx <= hx + hw + dp(8f) && ty >= hy - dp(10f) && ty <= hy + hh + dp(8f)) {
            expanded = !expanded;
            return true;
        }
        return false;
    }

    private void text(Canvas c, String s, float x, float y, float sizeDp, int color, Paint.Align align,
                      boolean boldFace) {
        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(color);
        p.setTextSize(dp(sizeDp));
        p.setTextAlign(align);
        p.setFakeBoldText(boldFace);
        c.drawText(s, x, y, p);
        p.setFakeBoldText(false);
    }

    /**
     * The route as a ribbon: checkpoints as ticks, forks as diamonds coloured by the road taken,
     * you as a marker, the exit as a flag. Tapping it opens the branch list with what is still
     * locked and what it costs.
     *
     * @param forkChoice road taken at each fork so far (-1 none yet), {@code forkCount} entries
     * @param roadNames  this mode's names for road 0, 1, 2
     */
    void draw(Canvas c, float ax, float ay, float aw, float progress01, int[] forkChoice, int forkCount,
              String[] roadNames, float bestScore, int checkpoints, int checkpointsDone, int golds) {
        float rowY = ay + dp(26f);
        float panelH = expanded ? dp(158f) : 0f;
        hx = ax;
        hy = ay;
        hw = aw;
        hh = dp(40f) + panelH;

        p.setStyle(Paint.Style.FILL);
        p.setShader(null);
        p.setColor(0xAA0A0E14);
        c.drawRoundRect(ax - dp(8f), ay - dp(10f), ax + aw + dp(8f), ay + hh, dp(6f), dp(6f), p);

        text(c, "ROUTE", ax, ay + dp(4f), 8.5f, 0xFF8D9BB0, Paint.Align.LEFT, true);
        int open = tiers(bestScore);
        int next = nextTier(bestScore);
        String right = next < 0 ? "ALL BRANCHES OPEN"
                : TIER_NAME[next] + " at " + TIER_SCORE[next];
        text(c, right, ax + aw, ay + dp(4f), 8.5f, next < 0 ? 0xFFF5C518 : 0xFF5D6B80, Paint.Align.RIGHT, false);

        // The line of the run.
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(3f));
        p.setColor(0xFF2A3648);
        c.drawLine(ax, rowY, ax + aw, rowY, p);
        p.setColor(0xFF35D0BA);
        c.drawLine(ax, rowY, ax + aw * Math.max(0f, Math.min(1f, progress01)), rowY, p);
        p.setStyle(Paint.Style.FILL);

        // Checkpoint ticks.
        for (int i = 1; i <= checkpoints; i++) {
            float tx = ax + aw * (i / (float) (checkpoints + 1));
            p.setColor(i <= checkpointsDone ? 0xFFF5C518 : 0x552A3648);
            c.drawRect(tx - dp(1f), rowY - dp(6f), tx + dp(1f), rowY + dp(6f), p);
        }
        // Forks as diamonds: filled in the colour of the road taken.
        for (int i = 0; i < forkCount && i < forkChoice.length; i++) {
            float tx = ax + aw * ((i + 0.5f) / Math.max(1, forkCount + 1));
            int road = forkChoice[i];
            int col = road == 2 ? 0xFFFF8A3D : road == 1 ? 0xFFF5C518 : road == 0 ? 0xFF35D0BA : 0xFF3A4757;
            p.setColor(col);
            path.reset();
            path.moveTo(tx, rowY - dp(7f));
            path.lineTo(tx + dp(6f), rowY);
            path.lineTo(tx, rowY + dp(7f));
            path.lineTo(tx - dp(6f), rowY);
            path.close();
            c.drawPath(path, p);
        }
        // You.
        float px = ax + aw * Math.max(0f, Math.min(1f, progress01));
        p.setColor(0xFFE6EDF7);
        c.drawCircle(px, rowY, dp(5f), p);
        p.setColor(0xFF35D0BA);
        c.drawCircle(px, rowY, dp(2.5f), p);
        // The exit flag.
        p.setColor(0xFFE6EDF7);
        c.drawRect(ax + aw, rowY - dp(10f), ax + aw + dp(1.5f), rowY + dp(6f), p);
        for (int i = 0; i < 4; i++) {
            p.setColor((i % 2 == 0) ? 0xFFE6EDF7 : 0xFF121820);
            float qx = ax + aw + dp(1.5f) + (i % 2) * dp(5f);
            float qy = rowY - dp(10f) + (i / 2) * dp(5f);
            c.drawRect(qx, qy, qx + dp(5f), qy + dp(5f), p);
        }

        text(c, golds + " gold  ·  tap for branches", ax, ay + dp(38f), 8f, 0xFF5D6B80, Paint.Align.LEFT, false);

        if (!expanded) {
            return;
        }
        float y = ay + dp(56f);
        text(c, "BRANCHES", ax, y, 9.5f, 0xFFE6EDF7, Paint.Align.LEFT, true);
        y += dp(6f);
        for (int i = 0; i < TIER_SCORE.length; i++) {
            y += dp(26f);
            boolean got = i < open;
            int col = got ? 0xFFF5C518 : 0xFF5D6B80;
            p.setColor(got ? 0xFFF5C518 : 0xFF3A4757);
            c.drawCircle(ax + dp(5f), y - dp(5f), dp(4f), p);
            if (!got) {
                // A little padlock shackle, so a locked branch reads as locked at a glance.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(dp(1.5f));
                p.setColor(0xFF5D6B80);
                c.drawArc(ax + dp(1.5f), y - dp(12f), ax + dp(8.5f), y - dp(5f), 180, 180, false, p);
                p.setStyle(Paint.Style.FILL);
            }
            text(c, TIER_NAME[i], ax + dp(16f), y - dp(2f), 10f, col, Paint.Align.LEFT, true);
            text(c, got ? "OPEN" : TIER_SCORE[i] + " pts", ax + aw, y - dp(2f), 9f, col, Paint.Align.RIGHT, false);
            text(c, TIER_BLURB[i], ax + dp(16f), y + dp(10f), 8f, 0xFF5D6B80, Paint.Align.LEFT, false);
        }
        y += dp(28f);
        // Rebuilt only when a fork is actually decided, so the open panel allocates nothing a frame.
        int taken = 0;
        for (int i = 0; i < forkCount && i < forkChoice.length; i++) {
            if (forkChoice[i] >= 0) {
                taken++;
            }
        }
        if (takenText == null || taken != takenCount) {
            takenCount = taken;
            StringBuilder sb = null;
            for (int i = 0; i < forkCount && i < forkChoice.length; i++) {
                int road = forkChoice[i];
                if (road < 0 || road >= roadNames.length) {
                    continue;
                }
                if (sb == null) {
                    sb = new StringBuilder("TAKEN:  ");
                } else {
                    sb.append("  >  ");
                }
                sb.append(roadNames[road]);
            }
            takenText = sb == null ? "TAKEN:  nothing yet" : sb.toString();
        }
        text(c, takenText, ax, y, 8.5f, 0xFF8D9BB0, Paint.Align.LEFT, false);
    }

    /**
     * The photo at the canyon exit: the shutter flash, then a bordered print of the line with both
     * craft where they actually were, the margin, the split and the medal.
     *
     * @param margin metres you crossed ahead of the rival; negative means it beat you
     */
    void photoFinish(Canvas c, float w, float h, float flash, String title, int medal, String bigTime,
                     String line1, String line2, int youColor, int rivalColor, float margin,
                     double seconds) {
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);
        if (flash > 0.01f) {
            p.setColor(((int) (Math.min(1f, flash) * 235) << 24) | 0xFFFFFF);
            c.drawRect(0, 0, w, h, p);
        }
        float cw = Math.min(w * 0.72f, dp(560f));
        float ch = Math.min(h * 0.78f, dp(400f));
        float cx = w / 2f - cw / 2f;
        float cy = h / 2f - ch / 2f;
        // The print: white border, dark image, caption strip at the foot.
        p.setColor(0xFFF2F2EE);
        c.drawRoundRect(cx, cy, cx + cw, cy + ch, dp(6f), dp(6f), p);
        float ix = cx + dp(14f);
        float iy = cy + dp(14f);
        float iw = cw - dp(28f);
        float ih = ch - dp(86f);
        p.setColor(0xFF1B1526);
        c.drawRect(ix, iy, ix + iw, iy + ih, p);
        // Canyon walls in silhouette either side of the line.
        p.setColor(0xFF2E1D28);
        path.reset();
        path.moveTo(ix, iy + ih);
        path.lineTo(ix, iy + ih * 0.35f);
        path.lineTo(ix + iw * 0.22f, iy + ih * 0.55f);
        path.lineTo(ix + iw * 0.3f, iy + ih);
        path.close();
        c.drawPath(path, p);
        path.reset();
        path.moveTo(ix + iw, iy + ih);
        path.lineTo(ix + iw, iy + ih * 0.3f);
        path.lineTo(ix + iw * 0.76f, iy + ih * 0.52f);
        path.lineTo(ix + iw * 0.7f, iy + ih);
        path.close();
        c.drawPath(path, p);
        // The line itself: a chequered post down the middle of the frame.
        float lx = ix + iw * 0.5f;
        for (int i = 0; i < 12; i++) {
            p.setColor((i % 2 == 0) ? 0xFFF2F2EE : 0xFF121820);
            float qy = iy + i * (ih / 12f);
            c.drawRect(lx - dp(5f), qy, lx + dp(5f), qy + ih / 12f, p);
        }
        // Both craft, offset by the real margin, with motion streaks so the print reads as a photo.
        float scale = Math.max(dp(3f), Math.min(dp(9f), iw * 0.012f));
        float half = Math.max(-iw * 0.34f, Math.min(iw * 0.34f, margin * scale * 0.5f));
        float youX = lx + half;
        float rivalX = lx - half;
        drawFinisher(c, youX, iy + ih * 0.48f, ih * 0.16f, youColor, true);
        drawFinisher(c, rivalX, iy + ih * 0.72f, ih * 0.13f, rivalColor, false);
        // Caption strip.
        int col = MEDAL_COLOR[Math.max(0, Math.min(3, medal))];
        text(c, title, cx + cw / 2f, cy + ch - dp(52f), 17f, 0xFF12161C, Paint.Align.CENTER, true);
        text(c, bigTime, cx + dp(20f), cy + ch - dp(24f), 24f, 0xFF12161C, Paint.Align.LEFT, true);
        // Medal ribbon.
        float mxp = cx + cw - dp(22f);
        p.setColor(col);
        c.drawCircle(mxp - dp(34f), cy + ch - dp(34f), dp(15f), p);
        p.setColor(0x33000000);
        c.drawCircle(mxp - dp(34f), cy + ch - dp(34f), dp(9f), p);
        text(c, MEDAL_NAME[Math.max(0, Math.min(3, medal))], mxp, cy + ch - dp(38f), 13f, 0xFF12161C,
                Paint.Align.RIGHT, true);
        text(c, medal == NO_MEDAL ? "no medal this time" : "split " + time(seconds), mxp, cy + ch - dp(22f), 9f,
                0xFF4A5462, Paint.Align.RIGHT, false);
        text(c, line1, cx + dp(20f), cy + ch - dp(10f), 9.5f, 0xFF4A5462, Paint.Align.LEFT, false);
        text(c, line2, cx + cw / 2f, cy + ch + dp(20f), 11f, 0xFFE6EDF7, Paint.Align.CENTER, false);
    }

    /** A chevron with speed streaks, for the photo print. */
    private void drawFinisher(Canvas c, float x, float y, float size, int color, boolean big) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        path.reset();
        path.moveTo(x + size, y);
        path.lineTo(x - size * 0.8f, y - size * 0.6f);
        path.lineTo(x - size * 0.35f, y);
        path.lineTo(x - size * 0.8f, y + size * 0.6f);
        path.close();
        c.drawPath(path, p);
        p.setColor((color & 0x00FFFFFF) | 0x66000000);
        for (int i = 0; i < 3; i++) {
            float sy = y - size * 0.35f + i * size * 0.35f;
            c.drawRect(x - size * (2.6f + i * 0.5f), sy - size * 0.06f, x - size * 0.8f, sy + size * 0.06f, p);
        }
        if (big) {
            p.setColor(0xFFF2F2EE);
            c.drawCircle(x + size * 0.35f, y - size * 0.12f, size * 0.14f, p);
        }
    }
}
