package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Mega Pull: a carnival high-striker. Five strokes, hardest you have got; the puck flies up the
 * tower to your peak watts and rings the bell if you clear the target. The target creeps up as
 * your record does. Pure peak, ten seconds, good between sets.
 */
final class MegaPullGame extends GameView {

    private enum Phase { READY, PULLING, RESULT }

    private static final int STROKES = 5;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();

    private Phase phase = Phase.READY;
    private int strokesLeft;
    private int peak;
    private float puck;          // 0..1 shown
    private float puckTarget;
    private int bell;            // watts to ring it
    private boolean rang;
    private double resultAt;

    MegaPullGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        strokesLeft = STROKES;
        peak = 0;
        puck = 0f;
        puckTarget = 0f;
        rang = false;
        float best = bests.get("megapull.peak", 0f);
        bell = best > 0 ? Math.round(best * 1.03f) : 180;
    }

    /**
     * The peak has to be sampled from every reading, not from the stroke tick.
     *
     * <p>This game used to read watts inside {@link #onStroke}, which fires when the stroke
     * <em>counter</em> increments - about a second after the drive, by which time instantaneous
     * power has usually collapsed back toward zero (it reads exactly zero in 27% of samples taken
     * mid-row). The tower was therefore being built from the troughs between pulls, and looked
     * dead however hard the handle was moved.
     */
    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (s == null || phase != Phase.PULLING) {
            return;
        }
        if (s.watts > peak) {
            peak = s.watts;
            puckTarget = Math.min(1f, peak / (bell * 1.15f));
            fx.burst(getWidth() * 0.5f, getHeight() * 0.85f, 16, dp(150f), 0.5f, dp(3f), 0xFFF5C518, true);
            shake.kick(dp(4f) + s.watts / 300f * dp(6f));
        }
        if (peak >= bell && !rang) {
            rang = true;
            fx.burst(getWidth() * 0.5f, getHeight() * 0.12f, 60, dp(260f), 1.2f, dp(4f), 0xFFF5C518, true);
            shake.kick(dp(16f));
            bests.recordHighest("megapull.peak", peak);
        }
    }

    @Override
    protected void onStroke(int watts) {
        if (phase == Phase.READY) {
            phase = Phase.PULLING;
        }
        if (phase != Phase.PULLING) {
            return;
        }
        strokesLeft--;
        if (strokesLeft <= 0) {
            phase = Phase.RESULT;
            resultAt = sessionSeconds;
            bests.recordHighest("megapull.peak", peak);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.RESULT) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        shake.step(dt);
        fx.step(dt, dp(400f));
        puck += (puckTarget - puck) * Math.min(1f, 5f * dt);

        c.save();
        c.translate(shake.dx, shake.dy);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF2A1040, 0xFF0A0E14,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Tower.
        float cx = w * 0.5f;
        float top = h * 0.12f;
        float base = h * 0.86f;
        paint.setColor(0xFF3B2A5E);
        c.drawRect(cx - dp(14f), top, cx + dp(14f), base, paint);
        paint.setColor(0xFF5A3A8A);
        c.drawRect(cx - dp(60f), base, cx + dp(60f), base + dp(16f), paint);
        // Scale marks with watts.
        for (int i = 0; i <= 10; i++) {
            float f = i / 10f;
            float y = base - (base - top) * f;
            paint.setColor(0x66FFFFFF);
            c.drawRect(cx + dp(16f), y - dp(1f), cx + dp(28f), y + dp(1f), paint);
            label(c, Math.round(bell * 1.15f * f) + "", cx + dp(34f), y + dp(4f), 8.5f, FAINT,
                    Paint.Align.LEFT);
        }
        // Bell line.
        float bellY = base - (base - top) * (bell / (bell * 1.15f));
        paint.setColor(rang ? 0xFFF5C518 : 0xFFF0B132);
        c.drawRect(cx - dp(40f), bellY - dp(2f), cx + dp(40f), bellY + dp(2f), paint);
        label(c, "BELL  " + bell + " W", cx - dp(44f), bellY + dp(4f), 9f, TEXT, Paint.Align.RIGHT);
        // Bell itself.
        Fx.glow(c, cx, top - dp(6f), dp(40f), rang ? 0x99F5C518 : 0x33F5C518);
        paint.setColor(rang ? 0xFFFFE28A : 0xFFB8890B);
        c.drawCircle(cx, top - dp(6f), dp(16f), paint);
        // Puck.
        float py = base - (base - top) * puck;
        Fx.glow(c, cx, py, dp(30f), 0x66FF3B5C);
        paint.setColor(0xFFFF3B5C);
        c.drawRoundRect(cx - dp(20f), py - dp(7f), cx + dp(20f), py + dp(7f), dp(5f), dp(5f), paint);
        fx.draw(c);
        c.restore();

        // HUD.
        String big;
        String cap;
        int col;
        if (phase == Phase.READY) {
            big = "MEGA PULL";
            cap = STROKES + " strokes, hardest you've got - ring the bell at " + bell + " W";
            col = DIM;
        } else if (phase == Phase.RESULT) {
            big = peak + " W";
            cap = (rang ? "DING! bell rung" : "not this time - bell was " + bell + " W") + "  ·  tap to go again";
            col = rang ? 0xFFF5C518 : DIM;
        } else {
            big = peak + " W";
            cap = strokesLeft + " stroke" + (strokesLeft == 1 ? "" : "s") + " left";
            col = peak >= bell ? 0xFFF5C518 : TEXT;
        }
        bold(c, big, w * 0.22f, h * 0.30f, phase == Phase.READY ? 26f : 44f, col, Paint.Align.CENTER);
        bold(c, cap, w * 0.22f, h * 0.30f + dp(22f), 10.5f, FAINT, Paint.Align.CENTER);
        label(c, bests.has("megapull.peak") ? "record " + Math.round(bests.get("megapull.peak", 0)) + " W" : "",
                w * 0.22f, h * 0.30f + dp(40f), 9f, FAINT, Paint.Align.CENTER);
        bold(c, (status == null ? 0 : status.watts) + " W", w * 0.78f, h * 0.30f, 26f, ACCENT, Paint.Align.CENTER);
        label(c, "NOW", w * 0.78f, h * 0.30f + dp(16f), 9f, FAINT, Paint.Align.CENTER);
    }
}
