package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Boss Fight: a sea monster with a health bar. Every stroke is a hit that does your watts in
 * damage. Every so often it winds up an attack - you have three seconds to get your boat speed
 * above the dodge line or lose a heart. Beat it and a bigger one surfaces.
 *
 * <p>Stop rowing and it regenerates, so there is no resting between attacks. Bursts win.
 */
final class BossFightGame extends GameView {

    private enum Phase { READY, FIGHT, WON, LOST }

    private static final int HEARTS = 3;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Phase phase = Phase.READY;
    private int level;
    private float maxHp;
    private float hp;
    private int hearts;
    private double nextAttackAt;
    private double windupStart = -1;
    private float dodgeSpeed;
    private double lastStrokeAt;
    private double hitFlash;
    private double dodgeFlash;
    private double hurtFlash;
    private String floatText = "";
    private double floatUntil;
    private float floatY;
    private double fightSeconds;
    private double rollingPeak;
    private final Fx.Shake shake = new Fx.Shake();
    private final Fx.Particles fx = new Fx.Particles();
    private float lastBossX;
    private float lastBossY;
    private float lastBossSize;
    private final android.graphics.Path tentacle = new android.graphics.Path();
    private final Paint tentaclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    BossFightGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        level = 1;
        hearts = HEARTS;
        beginBoss();
    }

    private void beginBoss() {
        maxHp = 5000f * (float) Math.pow(1.5, level - 1);
        hp = maxHp;
        windupStart = -1;
        nextAttackAt = sessionSeconds + attackInterval() + 6;
        fightSeconds = 0;
    }

    private double attackInterval() {
        float f = hp / maxHp;
        return f > 0.66f ? 18 : f > 0.33f ? 14 : 11;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.FIGHT;
            lastStrokeAt = sessionSeconds;
            nextAttackAt = sessionSeconds + 8;
        }
    }

    @Override
    protected void onStroke(int watts) {
        if (phase != Phase.FIGHT) {
            return;
        }
        int dmg = Math.max(20, Math.min(400, watts));
        // Strokes during a windup hit for double: reward the burst, since that is the dodge.
        if (windupStart >= 0) {
            dmg *= 2;
        }
        hp -= dmg;
        lastStrokeAt = sessionSeconds;
        hitFlash = 0.25;
        shake.kick(dp(3f) + dmg / 400f * dp(6f));
        fx.burst(lastBossX + dp(20f), lastBossY - lastBossSize * 0.2f, 14 + dmg / 20, dp(180f), 0.5f,
                dp(3.5f), 0xFFFFE28A, true);
        floatText = "-" + dmg + (windupStart >= 0 ? "  x2" : "");
        floatUntil = sessionSeconds + 0.9;
        floatY = 0f;
        if (hp <= 0) {
            hp = 0;
            bests.recordHighest("boss.level", level);
            level++;
            if (level > 6) {
                phase = Phase.WON;
            } else {
                beginBoss();
                hearts = Math.min(HEARTS, hearts + 1);   // a heart back between bosses
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && (phase == Phase.WON || phase == Phase.LOST)) {
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
        float speed = boat.value();
        rollingPeak = speed > rollingPeak ? speed : Math.max(0, rollingPeak - 0.03 * dt);

        if (phase == Phase.FIGHT) {
            fightSeconds += dt;
            // Regenerate if you stop hitting it.
            if (sessionSeconds - lastStrokeAt > 6) {
                hp = Math.min(maxHp, hp + maxHp * 0.02f * dt);
            }
            if (windupStart < 0 && sessionSeconds >= nextAttackAt) {
                windupStart = sessionSeconds;
                dodgeSpeed = Math.max(2.0f, (float) rollingPeak * 0.85f);
            }
            if (windupStart >= 0 && sessionSeconds - windupStart >= 3.0) {
                if (speed >= dodgeSpeed) {
                    dodgeFlash = 0.6;
                    fx.burst(lastBossX + dp(40f), lastBossY, 20, dp(140f), 0.6f, dp(4f), 0xCCBFE3FF, true);
                } else {
                    hearts--;
                    hurtFlash = 0.6;
                    shake.kick(dp(16f));
                    fx.burst(getWidth() * 0.76f, getHeight() * 0.62f, 30, dp(200f), 0.7f, dp(4f), 0xFFB3122E, true);
                    if (hearts <= 0) {
                        phase = Phase.LOST;
                        bests.recordHighest("boss.level", level - 1);
                    }
                }
                windupStart = -1;
                nextAttackAt = sessionSeconds + attackInterval();
            }
        }
        hitFlash = Math.max(0, hitFlash - dt);
        dodgeFlash = Math.max(0, dodgeFlash - dt);
        hurtFlash = Math.max(0, hurtFlash - dt);
        shake.step(dt);
        fx.step(dt, dp(220f));

        // Deep water: a gradient, a surface line, and slow bubbles.
        c.save();
        c.translate(shake.dx, shake.dy);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h,
                hurtFlash > 0 ? 0xFF4A1414 : 0xFF0E2238, 0xFF03080F, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        float surfaceY = h * 0.44f;
        paint.setColor(0x55BFE3FF);
        paint.setStrokeWidth(dp(2f));
        for (float x = 0; x < w; x += dp(12f)) {
            float y = surfaceY + (float) Math.sin(x / dp(40f) + sessionSeconds * 2.5) * dp(3f);
            c.drawLine(x, y, x + dp(7f), y, paint);
        }
        paint.setColor(0x33BFE3FF);
        for (int i = 0; i < 8; i++) {
            float bx0 = (i * 137f) % w;
            float by0 = h - (float) ((sessionSeconds * (25 + i * 6) * dp(1f) + i * 90f) % h);
            c.drawCircle(bx0, by0, dp(2f + i % 3), paint);
        }

        // Boss: a mound of arcs with eyes, on the left. Bigger each level; recoils when hit.
        float bx = w * 0.30f;
        float by = h * 0.58f;
        float size = dp(70f) + level * dp(10f);
        float recoil = (float) hitFlash * dp(20f);
        float lunge = windupStart >= 0 ? (float) (sessionSeconds - windupStart) / 3f * dp(60f) : 0f;
        float ox = bx - recoil + lunge;
        lastBossX = ox;
        lastBossY = by;
        lastBossSize = size;
        int bodyColor = hitFlash > 0 ? 0xFFFFFFFF : windupStart >= 0 ? 0xFF8A2C4A : 0xFF3F2A6B;
        // Tentacles: undulating curves that reach further and faster during a wind-up.
        tentaclePaint.setStyle(Paint.Style.STROKE);
        tentaclePaint.setStrokeCap(Paint.Cap.ROUND);
        tentaclePaint.setColor(bodyColor);
        float reach = windupStart >= 0 ? 1.6f : 1f;
        double rate = windupStart >= 0 ? 7 : 2.5;
        for (int i = 0; i < 6; i++) {
            float base = ox + (i - 2.5f) * size * 0.3f;
            float len = size * (0.9f + (i % 3) * 0.25f) * reach;
            float sway = (float) Math.sin(sessionSeconds * rate + i * 1.1) * size * 0.35f;
            float sway2 = (float) Math.cos(sessionSeconds * rate * 0.7 + i) * size * 0.25f;
            tentacle.reset();
            tentacle.moveTo(base, by + size * 0.1f);
            tentacle.cubicTo(base + sway, by - len * 0.5f, base + sway2 + (i - 2.5f) * dp(14f),
                    by - len * 0.8f, base + sway * 1.4f + (i - 2.5f) * dp(22f), by - len);
            tentaclePaint.setStrokeWidth(dp(12f) - i % 3 * dp(2f));
            c.drawPath(tentacle, tentaclePaint);
            paint.setColor(0xFFF5C518);
            paint.setAlpha(160);
            c.drawCircle(base + sway * 1.4f + (i - 2.5f) * dp(22f), by - len, dp(4f), paint);
        }
        // Body.
        Fx.glow(c, ox, by, size * 1.4f, windupStart >= 0 ? 0x55FF3B5C : 0x3355A0FF);
        paint.setColor(bodyColor);
        rect.set(ox - size, by - size * 0.7f, ox + size, by + size * 0.5f);
        c.drawOval(rect, paint);
        paint.setColor(0x33FFFFFF);
        rect.set(ox - size * 0.7f, by - size * 0.6f, ox + size * 0.1f, by - size * 0.1f);
        c.drawOval(rect, paint);
        // Mouth: opens through the wind-up.
        float open = windupStart >= 0 ? (float) Math.min(1.0, (sessionSeconds - windupStart) / 3.0) : 0.12f;
        paint.setColor(0xFF0A0E14);
        rect.set(ox - size * 0.45f, by + size * 0.05f, ox + size * 0.45f, by + size * (0.05f + 0.4f * open));
        c.drawOval(rect, paint);
        if (open > 0.3f) {
            paint.setColor(0xFFF1F5F9);
            for (int t = 0; t < 5; t++) {
                float tx = ox - size * 0.35f + t * size * 0.175f;
                c.drawRect(tx - dp(3f), by + size * 0.06f, tx + dp(3f), by + size * (0.06f + 0.12f * open), paint);
            }
        }
        // Eyes with a glow that turns red on a wind-up.
        Fx.glow(c, ox - size * 0.3f, by - size * 0.2f, dp(20f), windupStart >= 0 ? 0x88FF3B5C : 0x66F5C518);
        Fx.glow(c, ox + size * 0.3f, by - size * 0.2f, dp(20f), windupStart >= 0 ? 0x88FF3B5C : 0x66F5C518);
        paint.setColor(windupStart >= 0 ? 0xFFFF3B5C : 0xFFF5C518);
        c.drawCircle(ox - size * 0.3f, by - size * 0.2f, dp(9f), paint);
        c.drawCircle(ox + size * 0.3f, by - size * 0.2f, dp(9f), paint);
        paint.setColor(0xFF0A0E14);
        c.drawCircle(ox - size * 0.3f + (windupStart >= 0 ? dp(3f) : 0), by - size * 0.2f, dp(4f), paint);
        c.drawCircle(ox + size * 0.3f + (windupStart >= 0 ? dp(3f) : 0), by - size * 0.2f, dp(4f), paint);

        // Your boat on the right.
        float yx = w * 0.76f;
        float yy = h * 0.62f + (float) Math.sin(sessionSeconds * 2) * dp(4f);
        paint.setColor(ACCENT);
        rect.set(yx - dp(50f), yy - dp(8f), yx + dp(50f), yy + dp(8f));
        c.drawRoundRect(rect, dp(8f), dp(8f), paint);
        paint.setColor(TEXT);
        c.drawCircle(yx - dp(4f), yy - dp(12f), dp(6f), paint);
        Fx.glow(c, yx, yy, dp(60f), 0x3335D0BA);
        fx.draw(c);
        c.restore();
        if (windupStart >= 0) {
            Fx.vignette(c, w, h, 0.4f + (float) ((sessionSeconds - windupStart) / 3.0) * 0.5f, 0x8A1010);
        } else if (hurtFlash > 0) {
            Fx.vignette(c, w, h, (float) hurtFlash, 0x8A1010);
        }

        // Boss HP bar.
        float barY = dp(18f);
        rect.set(dp(16f), barY, w - dp(16f), barY + dp(14f));
        paint.setColor(0xFF18202C);
        c.drawRoundRect(rect, dp(7f), dp(7f), paint);
        float frac = maxHp > 0 ? hp / maxHp : 0f;
        rect.set(dp(16f), barY, dp(16f) + (w - dp(32f)) * frac, barY + dp(14f));
        paint.setColor(frac > 0.5f ? BAD : frac > 0.25f ? WARN : ACCENT);
        c.drawRoundRect(rect, dp(7f), dp(7f), paint);
        label(c, "BOSS " + level + "  ·  " + Math.round(hp) + " / " + Math.round(maxHp), w / 2f,
                barY + dp(30f), 9f, FAINT, Paint.Align.CENTER);

        // Hearts.
        for (int i = 0; i < HEARTS; i++) {
            paint.setColor(i < hearts ? BAD : 0x33FFFFFF);
            c.drawCircle(w - dp(24f) - i * dp(22f), barY + dp(50f), dp(7f), paint);
        }

        // Floating damage.
        if (sessionSeconds < floatUntil) {
            floatY += dt * dp(40f);
            bold(c, floatText, ox, by - size - dp(10f) - floatY, 16f, 0xFFF5C518, Paint.Align.CENTER);
        }

        // Centre message.
        String big;
        String cap;
        int col;
        if (phase == Phase.READY) {
            big = "IT SURFACES";
            cap = "every stroke hits for your watts - take a stroke";
            col = DIM;
        } else if (phase == Phase.WON) {
            big = "YOU CLEARED THE SEA";
            cap = "all six bosses down in " + clock(sessionSeconds) + "  ·  tap to go again";
            col = ACCENT;
        } else if (phase == Phase.LOST) {
            big = "DRAGGED UNDER";
            cap = "reached boss " + level + "  ·  tap to fight again";
            col = BAD;
        } else if (windupStart >= 0) {
            double left = 3.0 - (sessionSeconds - windupStart);
            big = "DODGE  " + String.format(java.util.Locale.US, "%.1f", Math.max(0, left));
            cap = String.format(java.util.Locale.US, "GET ABOVE %.1f m/s  ·  you are at %.1f",
                    dodgeSpeed, speed);
            col = speed >= dodgeSpeed ? ACCENT : BAD;
        } else if (dodgeFlash > 0) {
            big = "DODGED";
            cap = "keep hitting";
            col = ACCENT;
        } else if (hurtFlash > 0) {
            big = "HIT";
            cap = "too slow";
            col = BAD;
        } else {
            big = "";
            double until = nextAttackAt - sessionSeconds;
            cap = sessionSeconds - lastStrokeAt > 5 ? "IT'S HEALING - HIT IT"
                    : "next attack in " + Math.max(0, Math.round(until)) + "s";
            col = FAINT;
        }
        if (!big.isEmpty()) {
            bold(c, big, w / 2f, h * 0.30f, 40f, col, Paint.Align.CENTER);
        }
        bold(c, cap, w / 2f, h * 0.30f + dp(22f), 11f, windupStart >= 0 ? col : FAINT, Paint.Align.CENTER);

        // Dodge line gauge.
        if (phase == Phase.FIGHT) {
            float gy = h - dp(40f);
            float scaleMax = Math.max(4f, (float) rollingPeak * 1.2f);
            rect.set(dp(16f), gy, w - dp(16f), gy + dp(10f));
            paint.setColor(0xFF18202C);
            c.drawRoundRect(rect, dp(5f), dp(5f), paint);
            rect.set(dp(16f), gy, dp(16f) + (w - dp(32f)) * Math.min(1f, speed / scaleMax), gy + dp(10f));
            paint.setColor(windupStart >= 0 && speed < dodgeSpeed ? BAD : ACCENT);
            c.drawRoundRect(rect, dp(5f), dp(5f), paint);
            if (windupStart >= 0) {
                float lx = dp(16f) + (w - dp(32f)) * Math.min(1f, dodgeSpeed / scaleMax);
                paint.setColor(TEXT);
                c.drawRect(lx - dp(2f), gy - dp(6f), lx + dp(2f), gy + dp(16f), paint);
            }
            label(c, "BOAT SPEED", dp(16f), gy - dp(6f), 8.5f, FAINT, Paint.Align.LEFT);
            label(c, (status == null ? 0 : status.watts) + " W per hit", w - dp(16f), gy - dp(6f), 8.5f,
                    FAINT, Paint.Align.RIGHT);
        }
        label(c, bests.has("boss.level") ? "best: boss " + Math.round(bests.get("boss.level", 0)) + " beaten"
                : "", w / 2f, h - dp(10f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
