package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Tug of War: your power against a computer opponent whose strength ramps. The rope marker moves
 * toward whoever is pulling harder; drag it past your line to win, let it cross theirs and lose.
 *
 * <p>Direct and physical. The opponent starts weak and grows, so the first minute is winnable and
 * the question is how long you can hold it off.
 */
final class TugOfWarGame extends GameView {

    private enum Phase { READY, PULLING, WON, LOST }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int level = 2;          // 1 easy .. 5 brutal
    private Phase phase = Phase.READY;
    private double position;        // -1 = they win, +1 = you win
    private double pullSeconds;
    private double rounds;

    TugOfWarGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    void setLevel(int l) {
        level = Math.max(1, Math.min(5, l));
        phase = Phase.READY;
    }

    int level() {
        return level;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        position = 0;
        pullSeconds = 0;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.PULLING;
            pullSeconds = 0;
            position = 0;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && (phase == Phase.WON || phase == Phase.LOST)) {
            start();
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** Opponent strength per level, as a share of the rower's own typical watts. */
    private static final float[] LEVEL_SHARE = {0.75f, 0.88f, 1.0f, 1.1f, 1.22f};
    static final String[] LEVEL_NAMES = {"EASY", "STEADY", "EVEN", "STRONG", "BRUTAL"};

    /**
     * Opponent watts: a share of YOUR typical power by level, ramping 1 W every four seconds.
     * Was 60 + 25 W per level, which put level 3 at 135 W against a 129 W rower and made levels 4-5
     * unwinnable for most people - "we might need levels" really meant levels that fit the rower.
     */
    private float opponentWatts() {
        float base = (float) profile.typicalWatts() * LEVEL_SHARE[level - 1];
        return base + (float) pullSeconds * 0.25f;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        float them = opponentWatts();

        if (phase == Phase.PULLING) {
            pullSeconds += dt;
            // Marker velocity proportional to the power difference, scaled so a 40 W edge
            // covers the field in about 12 seconds.
            double diff = (watts - them) / 40.0;
            position += diff * dt / 12.0;
            position = Math.max(-1, Math.min(1, position));
            if (position >= 1) {
                phase = Phase.WON;
                bests.recordHighest("tug." + level, (float) pullSeconds);
            } else if (position <= -1) {
                phase = Phase.LOST;
            }
        }

        // Rope across the middle, marker on it.
        float ropeY = h * 0.52f;
        float left = dp(40f);
        float right = w - dp(40f);
        float mid = (left + right) / 2f;
        paint.setStrokeWidth(dp(6f));
        paint.setColor(0xFF6B5A3A);
        c.drawLine(left, ropeY, right, ropeY, paint);
        // Win lines.
        paint.setStrokeWidth(dp(3f));
        paint.setColor(ACCENT);
        c.drawLine(right, ropeY - dp(30f), right, ropeY + dp(30f), paint);
        paint.setColor(BAD);
        c.drawLine(left, ropeY - dp(30f), left, ropeY + dp(30f), paint);
        paint.setColor(0xFF2A3648);
        c.drawLine(mid, ropeY - dp(16f), mid, ropeY + dp(16f), paint);
        // Marker.
        float mx = mid + (right - mid) * (float) position;
        paint.setColor(position >= 0 ? ACCENT : BAD);
        c.drawCircle(mx, ropeY, dp(14f), paint);
        paint.setColor(0xFF0A0E14);
        c.drawCircle(mx, ropeY, dp(6f), paint);

        // Two pullers as blocks with their watts.
        bold(c, watts + " W", right - dp(10f), ropeY - dp(50f), 30f, ACCENT, Paint.Align.RIGHT);
        label(c, "YOU", right - dp(10f), ropeY - dp(50f) + dp(16f), 9f, FAINT, Paint.Align.RIGHT);
        bold(c, Math.round(them) + " W", left + dp(10f), ropeY - dp(50f), 30f, BAD, Paint.Align.LEFT);
        label(c, "THEM  ·  LEVEL " + level + " " + LEVEL_NAMES[level - 1] + "  ·  "
                        + Math.round(LEVEL_SHARE[level - 1] * 100) + "% OF YOUR " + Math.round(profile.typicalWatts()) + " W",
                left + dp(10f), ropeY - dp(50f) + dp(16f), 9f, FAINT, Paint.Align.LEFT);

        String big;
        String cap;
        int col;
        switch (phase) {
            case READY:
                big = "GRAB THE ROPE";
                cap = "take a stroke to start pulling";
                col = DIM;
                break;
            case WON:
                big = "YOU WON";
                cap = "held them off for " + clock(pullSeconds) + "  ·  tap to go again";
                col = ACCENT;
                break;
            case LOST:
                big = "PULLED OVER";
                cap = "lasted " + clock(pullSeconds) + "  ·  tap to go again";
                col = BAD;
                break;
            default:
                big = clock(pullSeconds);
                cap = watts > them ? "WINNING - KEEP IT UP" : "LOSING GROUND - PULL";
                col = watts > them ? ACCENT : WARN;
        }
        bold(c, big, w / 2f, h * 0.22f, phase == Phase.PULLING ? 48f : 30f, col, Paint.Align.CENTER);
        label(c, cap, w / 2f, h * 0.22f + dp(20f), 10f, FAINT, Paint.Align.CENTER);

        float fy = h - dp(14f);
        float col3 = w / 3f;
        stat(c, col3 * 0.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col3 * 1.5f, fy, String.format(java.util.Locale.US, "%+d", Math.round(watts - them)),
                "MARGIN W");
        stat(c, col3 * 2.5f, fy, bests.has("tug." + level) ? clock(bests.get("tug." + level, 0)) : "--",
                "BEST HOLD");
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
