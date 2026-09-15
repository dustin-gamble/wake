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
 *
 * <p>3.19.4 (the emulator screenshot was a line and a dot): an evening field with a crowd, a mud pit
 * under the middle of the rope, two teams of three leaning back and heaving on every stroke, dust
 * kicked up by whoever is losing ground, and the losing team tumbling into the mud.
 */
final class TugOfWarGame extends GameView {

    private enum Phase { READY, PULLING, WON, LOST }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private final android.graphics.Path rope = new android.graphics.Path();
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;
    private Phase lastPhase = Phase.READY;
    private double endedAt;
    private float heave;
    private int lastStrokes = -1;

    private int level = 2;          // 1 easy .. 5 brutal
    private Phase phase = Phase.READY;
    private double position;        // -1 = they win, +1 = you win
    private double pullSeconds;
    private double rounds;

    TugOfWarGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
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
        // The rope only travels a fifth of the width each way, so both teams stay on screen.
        float travel = w * 0.2f;
        float mx = mid + travel * (float) position;
        drawField(c, w, h, ropeY, mid, mx, watts, them, dt);
        paint.setStyle(Paint.Style.FILL);
        // Win lines.
        paint.setStrokeWidth(dp(3f));
        paint.setColor(ACCENT);
        c.drawLine(mid + travel, ropeY - dp(30f), mid + travel, ropeY + dp(30f), paint);
        paint.setColor(BAD);
        c.drawLine(mid - travel, ropeY - dp(30f), mid - travel, ropeY + dp(30f), paint);
        paint.setColor(0xFF2A3648);
        c.drawLine(mid, ropeY - dp(16f), mid, ropeY + dp(16f), paint);
        // Marker: a flag tied to the middle of the rope.
        paint.setColor(0xFFE0E0E0);
        c.drawRect(mx - dp(1.5f), ropeY - dp(40f), mx + dp(1.5f), ropeY + dp(4f), paint);
        rope.rewind();
        float flap = (float) Math.sin(sessionSeconds * 7) * dp(4f);
        rope.moveTo(mx + dp(1.5f), ropeY - dp(40f));
        rope.lineTo(mx + dp(32f), ropeY - dp(32f) + flap);
        rope.lineTo(mx + dp(1.5f), ropeY - dp(22f));
        rope.close();
        paint.setColor(position >= 0 ? ACCENT : BAD);
        c.drawPath(rope, paint);
        fx.draw(c);

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

    /** Sky, crowd, grass, mud pit, the rope with its sag, and both teams heaving. */
    private void drawField(Canvas c, float w, float h, float ropeY, float mid, float mx, int watts, float them, float dt) {
        float ground = ropeY + dp(60f);
        float horizon = ropeY - dp(112f);
        if (skyShader == null || skyHeight != horizon) {
            skyHeight = horizon;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, horizon, 0xFF1B1036, 0xFFE0875A,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, horizon, paint);
        paint.setShader(null);
        Fx.glow(c, w * 0.5f, horizon, dp(160f), 0x66FFB36B);
        float cheer = phase == Phase.PULLING ? (float) Math.min(1, Math.abs(position) * 1.5) : phase == Phase.WON ? 1f : 0.2f;
        scenery.drawBank(c, w, horizon - dp(46f), horizon, sessionSeconds * 0.3, dp(20f), sessionSeconds, cheer);
        paint.setColor(0xFF3E6B35);
        c.drawRect(0, horizon, w, h, paint);
        paint.setColor(0xFF4C7F40);
        for (int i = 0; i < 9; i++) {
            float y = horizon + (h - horizon) * (i / 9f);
            c.drawRect(0, y, w, y + dp(1.5f) + i * dp(0.4f), paint);
        }
        // Mud pit under the centre line.
        paint.setColor(0xFF5A3B22);
        c.drawOval(mid - dp(110f), ground - dp(14f), mid + dp(110f), ground + dp(26f), paint);
        paint.setColor(0xFF6E4A2B);
        c.drawOval(mid - dp(80f), ground - dp(8f), mid + dp(70f), ground + dp(14f), paint);

        // A heave on every stroke, easing off between.
        if (status != null && status.strokes != lastStrokes) {
            if (lastStrokes >= 0 && phase == Phase.PULLING) {
                heave = 1f;
            }
            lastStrokes = status.strokes;
        }
        heave = Math.max(0f, heave - dt * 1.6f);
        double typical = Math.max(1.0, profile.typicalWatts());
        float yourLean = 0.35f + 0.35f * (float) Math.min(1.2, watts / typical) + heave * 0.2f;
        float theirLean = 0.35f + 0.35f * (float) Math.min(1.2, them / typical)
                + 0.12f * (float) Math.abs(Math.sin(sessionSeconds * 2.6));

        if (phase != lastPhase) {
            if (phase == Phase.WON || phase == Phase.LOST) {
                endedAt = sessionSeconds;
                fx.burst(phase == Phase.WON ? mid - dp(60f) : mid + dp(60f), ground, 50, dp(220f), 1.2f, dp(4f), 0xFF6E4A2B, true);
            }
            lastPhase = phase;
        }
        double sinceEnd = sessionSeconds - endedAt;

        // Rope: hands on each side, sagging a little between the teams.
        float yourHands = mx + dp(90f);
        float theirHands = mx - dp(90f);
        rope.rewind();
        rope.moveTo(theirHands - dp(260f), ropeY + dp(2f));
        rope.quadTo((theirHands + mx) / 2f, ropeY + dp(6f), mx, ropeY);
        rope.quadTo((yourHands + mx) / 2f, ropeY + dp(6f), yourHands + dp(260f), ropeY + dp(2f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5f));
        paint.setColor(0xFFB89A62);
        c.drawPath(rope, paint);
        paint.setStyle(Paint.Style.FILL);

        for (int k = 0; k < 3; k++) {
            float yx = yourHands + dp(27f) + k * dp(78f);
            float tx = theirHands - dp(27f) - k * dp(78f);
            float fall = phase == Phase.LOST ? (float) Math.min(1, sinceEnd * 2) : 0f;
            drawPuller(c, yx, ground, 1, yourLean * (1 - fall) - fall * 0.9f, ACCENT, k, fall);
            fall = phase == Phase.WON ? (float) Math.min(1, sinceEnd * 2) : 0f;
            drawPuller(c, tx, ground, -1, theirLean * (1 - fall) - fall * 0.9f, BAD, k + 3, fall);
        }
        // Dust from the feet of whoever is being dragged.
        if (phase == Phase.PULLING && Math.random() < 0.5) {
            boolean youSlip = watts < them;
            float fxX = youSlip ? yourHands + dp(10f) : theirHands - dp(10f);
            fx.spawn(fxX + (float) (Math.random() * dp(120f)) * (youSlip ? 1 : -1), ground,
                    (float) (Math.random() - 0.5) * dp(40f), -dp(30f) - (float) Math.random() * dp(30f),
                    0.8f, dp(5f), 0x88C8A878, false);
        }
        fx.step(dt, dp(200f));
    }

    /** A stick puller leaning back from the rope; {@code facing} +1 pulls right. */
    private void drawPuller(Canvas c, float x, float ground, int facing, float lean, int color, int seed, float fall) {
        // Drawn at 1.5x around the feet: hands land at ground - 60 dp, which is rope height.
        c.save();
        c.scale(1.5f, 1.5f, x, ground);
        float len = dp(38f);
        float hipX = x;
        float hipY = ground - dp(30f) + fall * dp(22f);
        float sx = hipX + facing * (float) Math.sin(lean) * len;
        float sy = hipY - (float) Math.cos(lean) * len;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(7f));
        paint.setColor(0xFF2A2F3A);
        // Legs braced toward the rope.
        c.drawLine(hipX, hipY, hipX - facing * dp(16f), ground, paint);
        c.drawLine(hipX, hipY, hipX - facing * dp(4f), ground, paint);
        paint.setColor(color);
        c.drawLine(hipX, hipY, sx, sy, paint);
        paint.setStrokeWidth(dp(5f));
        paint.setColor(0xFFF1C27D);
        float handX = hipX - facing * dp(20f);
        float handY = ground - dp(40f) + fall * dp(20f);
        c.drawLine(sx, sy, handX, handY, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(sx + facing * dp(6f) * (float) Math.sin(lean), sy - dp(9f), dp(9f), paint);
        paint.setColor(seed % 2 == 0 ? 0xFF222222 : 0xFF6B3E1E);
        c.drawCircle(sx + facing * dp(6f) * (float) Math.sin(lean), sy - dp(13f), dp(6f), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        c.restore();
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
