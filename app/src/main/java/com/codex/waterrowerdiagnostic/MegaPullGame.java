package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Mega Pull: a carnival high-striker. Five strokes, hardest you have got; the puck flies up the
 * tower to your peak watts and rings the bell if you clear the target. The target creeps up as
 * your record does. Pure peak, ten seconds, good between sets.
 *
 * <p>3.19.4 (the emulator screenshot was a pole on purple): a night fairground - striped tents, a
 * string of twinkling bulbs, sweeping spotlights, a crowd that jumps when the bell rings - and a
 * tower whose bulbs light up behind the puck and chase when you ring it.
 */
final class MegaPullGame extends GameView {

    private enum Phase { READY, PULLING, RESULT }

    private static final int STROKES = 5;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final android.graphics.Path tent = new android.graphics.Path();
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;

    private Phase phase = Phase.READY;
    private int strokesLeft;
    private int peak;
    private float puck;          // 0..1 shown
    private float puckTarget;
    private int bell;            // watts to ring it
    private boolean rang;
    private double resultAt;
    /** The record when this go started; beating it mid-pull fires the NEW BEST burst once. */
    private float recordAtStart;
    private double newBestUntil;

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
        recordAtStart = best;
        newBestUntil = 0;
        // No record yet: set the bell a fifth above the rower's high power, not a fixed 180 W.
        bell = best > 0 ? Math.round(best * 1.03f) : (int) Math.round(profile.highWatts() * 1.2);
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
            // Shake scaled to the rower's own power: a typical pull is a solid thump for anyone.
            shake.kick(dp(4f) + (float) (s.watts / Math.max(1.0, profile.typicalWatts())) * dp(5f));
            if (recordAtStart > 0 && peak > recordAtStart && newBestUntil == 0) {
                newBestUntil = sessionSeconds + 2.5;
                fx.burst(getWidth() * 0.5f, getHeight() * 0.5f, 80, dp(320f), 1.4f, dp(4.5f), 0xFF35D0BA, true);
                shake.kick(dp(18f));
            }
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

    /** Tents, a string of bulbs, sweeping spotlights and a crowd along the bottom. */
    private void drawFairground(Canvas c, float w, float h) {
        double t = sessionSeconds;
        // Spotlights from the bottom corners.
        for (int side = 0; side < 2; side++) {
            float ox = side == 0 ? w * 0.08f : w * 0.92f;
            double a = -Math.PI / 2 + Math.sin(t * 0.6 + side * 2) * 0.55;
            float len = h * 1.1f;
            float tx = ox + (float) Math.cos(a) * len;
            float ty = h + (float) Math.sin(a) * len;
            float nx = (float) -Math.sin(a) * dp(70f);
            float ny = (float) Math.cos(a) * dp(70f);
            tent.rewind();
            tent.moveTo(ox, h);
            tent.lineTo(tx + nx, ty + ny);
            tent.lineTo(tx - nx, ty - ny);
            tent.close();
            paint.setColor(rang ? 0x30FFE28A : 0x1ABFD8FF);
            c.drawPath(tent, paint);
        }
        // Stars.
        paint.setColor(0x88FFFFFF);
        for (int i = 0; i < 40; i++) {
            float sx = (i * 197 % 1000) / 1000f * w;
            float sy = (i * 331 % 1000) / 1000f * h * 0.45f;
            float tw = 0.5f + 0.5f * (float) Math.sin(t * 2 + i);
            c.drawCircle(sx, sy, dp(1f) + tw * dp(1f), paint);
        }
        // Two striped tents.
        for (int k = 0; k < 2; k++) {
            float tcx = k == 0 ? w * 0.17f : w * 0.83f;
            float tw = w * 0.13f;
            float roofTop = h * 0.50f;
            float eave = h * 0.66f;
            float floor = h * 0.9f;
            int stripes = 8;
            for (int i = 0; i < stripes; i++) {
                float x0 = tcx - tw + 2 * tw * i / stripes;
                float x1 = tcx - tw + 2 * tw * (i + 1) / stripes;
                paint.setColor((i & 1) == 0 ? 0xFFB8323A : 0xFFEDE3D0);
                tent.rewind();
                tent.moveTo(tcx, roofTop);
                tent.lineTo(x0, eave);
                tent.lineTo(x1, eave);
                tent.close();
                c.drawPath(tent, paint);
                c.drawRect(x0, eave, x1, floor, paint);
            }
            paint.setColor(0xFF1A1020);
            c.drawRect(tcx - tw * 0.22f, eave + dp(20f), tcx + tw * 0.22f, floor, paint);
            paint.setColor(0xFFF5C518);
            c.drawRect(tcx - dp(1.5f), roofTop - dp(30f), tcx + dp(1.5f), roofTop, paint);
            float flap = (float) Math.sin(t * 5 + k) * dp(4f);
            tent.rewind();
            tent.moveTo(tcx + dp(1.5f), roofTop - dp(30f));
            tent.lineTo(tcx + dp(26f), roofTop - dp(24f) + flap);
            tent.lineTo(tcx + dp(1.5f), roofTop - dp(18f));
            tent.close();
            c.drawPath(tent, paint);
        }
        // A string of bulbs swinging across the top.
        int n = 26;
        for (int i = 0; i < n; i++) {
            float f = i / (n - 1f);
            float x = w * f;
            float y = h * 0.08f + (float) Math.sin(f * Math.PI) * h * 0.1f;
            boolean on = rang ? ((int) (t * 10) + i) % 2 == 0 : ((int) (t * 3) + i) % 5 != 0;
            int col = i % 3 == 0 ? 0xFFFF5A7A : i % 3 == 1 ? 0xFFF5C518 : 0xFF6BD8FF;
            paint.setColor(on ? col : 0xFF3A2A48);
            c.drawCircle(x, y, dp(5f), paint);
            if (on) {
                Fx.glow(c, x, y, dp(16f), (col & 0x00FFFFFF) | 0x55000000);
            }
        }
        // Crowd along the bottom, jumping when the bell rings.
        float ground = h;
        for (int i = 0; i < 60; i++) {
            float x = w * (i + 0.5f) / 60f;
            float jump = rang ? (float) Math.abs(Math.sin(t * 9 + i)) * dp(12f) : (float) Math.abs(Math.sin(t * 1.5 + i)) * dp(2f);
            paint.setColor(0xFF3A2858);
            c.drawRoundRect(x - dp(11f), ground - dp(34f) - jump, x + dp(11f), ground + dp(10f), dp(8f), dp(8f), paint);
            c.drawCircle(x, ground - dp(44f) - jump, dp(9f), paint);
            if (rang && i % 3 == 0) {
                c.drawRect(x - dp(13f), ground - dp(66f) - jump, x - dp(9f), ground - dp(34f) - jump, paint);
            }
        }
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
        if (skyShader == null || skyHeight != h) {
            skyHeight = h;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, h, 0xFF2A1040, 0xFF0A0E14,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        paint.setShader(skyShader);
        c.drawRect(-dp(30f), -dp(30f), w + dp(30f), h + dp(30f), paint);
        paint.setShader(null);
        drawFairground(c, w, h);

        // Tower.
        float cx = w * 0.5f;
        float top = h * 0.12f;
        float base = h * 0.86f;
        paint.setColor(0xFF3B2A5E);
        c.drawRect(cx - dp(14f), top, cx + dp(14f), base, paint);
        paint.setColor(0xFF5A3A8A);
        c.drawRect(cx - dp(60f), base, cx + dp(60f), base + dp(16f), paint);
        // Bulbs up both edges of the tower: lit behind the puck, chasing once the bell rings.
        int bulbs = 24;
        for (int i = 0; i < bulbs; i++) {
            float f = (i + 0.5f) / bulbs;
            float y = base - (base - top) * f;
            boolean lit = rang ? ((int) (sessionSeconds * 12) - i) % 4 == 0 || f <= puck : f <= puck;
            int col = f > 0.85f ? 0xFFFF5A5A : f > 0.6f ? 0xFFF5C518 : 0xFF6BFFB8;
            paint.setColor(lit ? col : 0xFF2A2140);
            c.drawCircle(cx - dp(22f), y, dp(4f), paint);
            c.drawCircle(cx + dp(22f), y, dp(4f), paint);
            if (lit) {
                Fx.glow(c, cx - dp(22f), y, dp(12f), (col & 0x00FFFFFF) | 0x66000000);
                Fx.glow(c, cx + dp(22f), y, dp(12f), (col & 0x00FFFFFF) | 0x66000000);
            }
        }
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
        // Record line: the best ever pull, so every go is measured against it.
        if (recordAtStart > 0) {
            float recY = base - (base - top) * Math.min(1f, recordAtStart / (bell * 1.15f));
            paint.setColor(0xFF35D0BA);
            for (float dx = cx - dp(70f); dx < cx + dp(70f); dx += dp(12f)) {
                c.drawRect(dx, recY - dp(1.5f), dx + dp(7f), recY + dp(1.5f), paint);
            }
            label(c, "RECORD  " + Math.round(recordAtStart) + " W", cx + dp(74f), recY + dp(4f), 9f,
                    0xFF35D0BA, Paint.Align.LEFT);
        }
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
        if (sessionSeconds < newBestUntil && ((int) (sessionSeconds * 6) % 2 == 0)) {
            bold(c, "NEW BEST!", w * 0.5f, h * 0.52f, 40f, 0xFF35D0BA, Paint.Align.CENTER);
        }
        bold(c, (status == null ? 0 : status.watts) + " W", w * 0.78f, h * 0.30f, 26f, ACCENT, Paint.Align.CENTER);
        label(c, "NOW", w * 0.78f, h * 0.30f + dp(16f), 9f, FAINT, Paint.Align.CENTER);
    }
}
