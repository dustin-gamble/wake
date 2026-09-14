package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Row Runner: a side-scrolling platformer where rowing is running.
 *
 * <p>Your boat speed is your run speed and the world is measured in real metres rowed. Pits need
 * speed to jump - the jump fires itself at the edge and its length follows your speed. Walls need
 * a power burst - your peak watts over the last two seconds must beat the wall's number or you
 * bounce. Coins are just coins. Five hearts; lose them all and it is over, with the distance kept.
 *
 * <p>Levels are generated from a fixed seed so a run is repeatable and a best is meaningful.
 *
 * <p>3.15.0, after "needs to be more fun": <b>every stroke is a jump</b>. The start of each drive,
 * as the pulse meter sees it, launches the runner, so rhythm matters - time the pull and you sail
 * over a pit for a bonus, or clear a crab walking at you, or scoop an arc of coins in the air. A
 * stroke-jump that would land in a pit stretches to clear it rather than punish a near miss; the
 * old jump at the edge still fires if no stroke comes. Walls and the high-coin speed come from the
 * rower's own profile.
 */
final class RowRunnerGame extends GameView {

    private enum Kind { PIT, WALL, COIN, HIGH_COIN, ARC_COIN, CRAB }

    private static final class Thing {
        final Kind kind;
        float at;             // metres; crabs walk, so not final
        final float size;     // pit width, wall watts, or an arc coin's height (0..1 of a jump)
        boolean done;

        Thing(Kind kind, float at, float size) {
            this.kind = kind;
            this.at = at;
            this.size = size;
        }
    }

    private static final int HEARTS = 5;
    private static final float JUMP_FACTOR = 1.9f;   // jump length = speed x this, in metres

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.List<Thing> things = new java.util.ArrayList<>();

    private boolean started;
    private boolean over;
    private int hearts;
    private int coins;
    private double runStart;
    private double x;                 // player world metres
    private float jumpFrom = -1;      // world x where the current jump began
    private float jumpLen;
    private double hurtUntil;
    private double bonkUntil;
    private double peakWattsAt;
    private int peakWatts;
    private float legPhase;
    private long seed = 1234;
    private final Fx.Shake shake = new Fx.Shake();
    private final Fx.Particles fx = new Fx.Particles();
    private boolean wasAirborne;
    private PulseMeter.Stroke lastStrokeSeen;
    private double lastJumpAt = -10;
    /** The current jump came from a stroke, so clearing a pit with it earns the bonus. */
    private boolean strokeJump;
    private int strokeJumps;
    /** When the last stroke landed and the rhythm between strokes, to predict the next jump. */
    private double lastStrokeAt = -1;
    private double strokeInterval = 2.4;

    RowRunnerGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        hearts = HEARTS;
        coins = 0;
        x = 0;
        jumpFrom = -1;
        peakWatts = 0;
        strokeJump = false;
        strokeJumps = 0;
        lastJumpAt = -10;
        lastStrokeAt = -1;
        strokeInterval = 60.0 / Math.max(14, profile.typicalRate());
        things.clear();
        buildLevel();
    }

    /** Deterministic course: gets harder with distance. */
    private void buildLevel() {
        java.util.Random r = new java.util.Random(seed);
        float typicalWatts = (float) profile.typicalWatts();
        float at = 40f;
        while (at < 6000f) {
            float difficulty = Math.min(1f, at / 3000f);
            int roll = r.nextInt(100);
            if (roll < 28) {
                float width = 2.5f + r.nextFloat() * (2.5f + difficulty * 4f);   // 2.5..9 m
                things.add(new Thing(Kind.PIT, at, width));
                at += width;
            } else if (roll < 46) {
                // 70% of typical power rising to ~160% with distance: was a fixed 90..260 W.
                float watts = typicalWatts * (0.7f + r.nextFloat() * (0.35f + difficulty * 0.55f));
                things.add(new Thing(Kind.WALL, at, watts));
            } else if (roll < 58) {
                things.add(new Thing(Kind.CRAB, at + 20f, 0));
            } else if (roll < 72) {
                float[] arc = {0.25f, 0.6f, 0.85f, 0.6f, 0.25f};
                for (int i = 0; i < arc.length; i++) {
                    things.add(new Thing(Kind.ARC_COIN, at + i * 1.4f, arc[i]));
                }
                at += 6;
            } else if (roll < 86) {
                things.add(new Thing(Kind.COIN, at, 0));
                things.add(new Thing(Kind.COIN, at + 3, 0));
                things.add(new Thing(Kind.COIN, at + 6, 0));
                at += 6;
            } else {
                things.add(new Thing(Kind.HIGH_COIN, at, 0));
            }
            at += 12f + r.nextFloat() * 18f;
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
            runStart = sessionMeters;
        }
        if (s.watts > peakWatts || sessionSeconds - peakWattsAt > 2.0) {
            peakWatts = s.watts;
            peakWattsAt = sessionSeconds;
        }
        // A new stroke from the pulse meter is the start of a drive: jump.
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastStrokeSeen) {
            lastStrokeSeen = stroke;
            jumpOnStroke();
        }
    }

    /** Fallback when the pulse meter is not counting strokes: the monitor's counter still jumps. */
    @Override
    protected void onStroke(int watts) {
        if (status != null && status.meter.strokes == 0) {
            jumpOnStroke();
        }
    }

    private void jumpOnStroke() {
        if (lastStrokeAt >= 0) {
            double gap = sessionSeconds - lastStrokeAt;
            if (gap > 0.9 && gap < 6) {
                strokeInterval += (gap - strokeInterval) * 0.3;
            }
        }
        lastStrokeAt = sessionSeconds;
        if (!started || over || airborne() || sessionSeconds - lastJumpAt < 0.8) {
            return;
        }
        float len = Math.max(2.5f, boat.value() * JUMP_FACTOR);
        // Forgiving: a jump that would land inside a pit stretches to clear it, up to half again.
        for (Thing t : things) {
            if (t.kind == Kind.PIT && !t.done && t.at > x && t.at < x + len && t.at + t.size > x + len) {
                float need = t.at + t.size + 0.3f - (float) x;
                if (need <= len * 1.5f) {
                    len = need;
                }
                break;
            }
        }
        jumpFrom = (float) x;
        jumpLen = len;
        lastJumpAt = sessionSeconds;
        strokeJump = true;
        strokeJumps++;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && over) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    /** The high coin needs a little under your typical speed: was a fixed 3.4 m/s. */
    private float highCoinSpeed() {
        return (float) profile.typicalSpeed() * 0.9f;
    }

    private boolean airborne() {
        return jumpFrom >= 0 && x < jumpFrom + jumpLen;
    }

    /** Height above ground during a jump, 0..1 of max, a parabola. */
    private float jumpHeight() {
        if (!airborne()) {
            return 0f;
        }
        float f = (float) ((x - jumpFrom) / jumpLen);
        return 4f * f * (1f - f);
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();
        boolean bonked = sessionSeconds < bonkUntil;

        if (started && !over && !bonked) {
            x = sessionMeters - runStart;
        }

        // World checks.
        if (started && !over) {
            for (Thing t : things) {
                if (t.done || t.at > x + 1.5f) {
                    continue;
                }
                switch (t.kind) {
                    case PIT:
                        if (!airborne() && x >= t.at - 0.5f && x < t.at + t.size) {
                            // At the edge: jump. Length follows speed, so a slow approach falls in.
                            if (jumpFrom < 0) {
                                jumpFrom = (float) x;
                                jumpLen = Math.max(1f, speed * JUMP_FACTOR);
                                strokeJump = false;
                            }
                        }
                        if (x >= t.at + t.size) {
                            t.done = true;
                            if (strokeJump && jumpFrom >= 0 && jumpFrom < t.at) {
                                coins += 2;   // cleared on a stroke
                                fx.burst(w * 0.30f, h * 0.72f - dp(60f), 16, dp(120f), 0.5f, dp(3f), 0xFF35D0BA, true);
                            }
                        } else if (airborne() && x >= jumpFrom + jumpLen - 0.01f && x < t.at + t.size) {
                            // Landed short: in the pit.
                            fall(t);
                            t.done = true;
                        }
                        break;
                    case WALL:
                        if (x >= t.at) {
                            if (peakWatts >= t.size) {
                                t.done = true;   // smashed through
                                fx.burst(w * 0.30f + dp(10f), h * 0.72f - dp(40f), 30, dp(160f), 0.8f, dp(4f), 0xFFB33A3A, true);
                                shake.kick(dp(8f));
                            } else if (sessionSeconds > hurtUntil) {
                                bonk(t);
                            }
                        }
                        break;
                    case COIN:
                        if (x >= t.at) {
                            t.done = true;
                            coins++;
                            fx.burst(w * 0.30f, h * 0.72f - dp(24f), 10, dp(90f), 0.5f, dp(3f), 0xFFF5C518, true);
                        }
                        break;
                    case ARC_COIN:
                        if (x >= t.at) {
                            t.done = true;
                            if (Math.abs(jumpHeight() - t.size) < 0.3f) {
                                coins += 2;
                                fx.burst(w * 0.30f, h * 0.72f - dp(24f) - t.size * dp(60f), 10, dp(90f), 0.5f, dp(3f), 0xFFF5C518, true);
                            }
                        }
                        break;
                    case CRAB:
                        if (x >= t.at - 0.3f) {
                            t.done = true;
                            if (jumpHeight() > 0.35f) {
                                coins += 2;
                                fx.burst(w * 0.30f, h * 0.72f - dp(30f), 14, dp(110f), 0.5f, dp(3f), 0xFFFF7A3D, true);
                            } else if (sessionSeconds > hurtUntil) {
                                hearts--;
                                shake.kick(dp(10f));
                                hurtUntil = sessionSeconds + 1.5;
                                checkOver();
                            }
                        }
                        break;
                    case HIGH_COIN:
                        if (x >= t.at) {
                            t.done = true;
                            if (speed >= highCoinSpeed()) {
                                coins += 5;
                                fx.burst(w * 0.30f, h * 0.72f - dp(70f), 24, dp(140f), 0.7f, dp(3.5f), 0xFFF5C518, true);
                            }
                        }
                        break;
                    default:
                }
            }
            if (jumpFrom >= 0 && !airborne()) {
                jumpFrom = -1;
                strokeJump = false;
            }
            // Crabs scuttle toward you once they are in view.
            for (Thing t : things) {
                if (t.kind == Kind.CRAB && !t.done && t.at > x && t.at < x + 40f) {
                    t.at -= 1.2f * dt;
                }
            }
        }

        // ---------- draw ----------
        float groundY = h * 0.72f;
        float ppm = w / 40f;   // 40 m across
        float youX = w * 0.30f;
        float camX = (float) x;
        shake.step(dt);
        fx.step(dt, dp(300f));
        boolean airNow = airborne();
        if (wasAirborne && !airNow && started && !over) {
            fx.burst(youX, groundY, 12, dp(80f), 0.4f, dp(2.5f), 0xCC8B5A2B, true);
        }
        wasAirborne = airNow;

        c.save();
        c.translate(shake.dx, shake.dy);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, groundY, 0xFF3C8EE0, 0xFF9CD1F7,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, groundY, paint);
        paint.setShader(null);
        // Sun with a glow.
        Fx.glow(c, w * 0.85f, h * 0.14f, dp(60f), 0x66FFE28A);
        paint.setColor(0xFFFFE28A);
        c.drawCircle(w * 0.85f, h * 0.14f, dp(22f), paint);
        // Far hills, slow parallax.
        paint.setColor(0xFF4E9E4A);
        for (int i = 0; i < 6; i++) {
            float hx = ((i * 260f * dp(1f)) - (camX * ppm * 0.12f)) % (w + dp(300f));
            if (hx < -dp(150f)) hx += w + dp(300f);
            c.drawCircle(hx, groundY + dp(30f), dp(90f) + (i % 3) * dp(25f), paint);
        }
        // Clouds, slow.
        paint.setColor(0xFFDCEBF7);
        for (int i = 0; i < 4; i++) {
            float cx = ((i * 231f + 60f) - (camX * ppm * 0.2f)) % (w + dp(120f));
            if (cx < 0) cx += w + dp(120f);
            float cy = h * 0.12f + (i % 2) * dp(40f);
            c.drawRect(cx - dp(30f), cy, cx + dp(30f), cy + dp(14f), paint);
            c.drawRect(cx - dp(16f), cy - dp(10f), cx + dp(16f), cy + dp(14f), paint);
        }
        // Ground blocks.
        paint.setColor(0xFF6BBF59);
        c.drawRect(0, groundY, w, groundY + dp(14f), paint);
        paint.setColor(0xFF8B5A2B);
        c.drawRect(0, groundY + dp(14f), w, h, paint);
        paint.setColor(0xFF74461F);
        float block = dp(28f);
        float off = (camX * ppm) % block;
        for (float bx = -off; bx < w; bx += block) {
            c.drawRect(bx, groundY + dp(14f), bx + dp(2f), h, paint);
        }

        // Things in view.
        for (Thing t : things) {
            float sx = youX + (t.at - camX) * ppm;
            if (sx < -dp(200f) || sx > w + dp(60f)) {
                continue;
            }
            switch (t.kind) {
                case PIT: {
                    float ex = sx + t.size * ppm;
                    paint.setColor(0xFF10171F);
                    c.drawRect(sx, groundY, ex, h, paint);
                    label(c, Math.round(t.size) + " m", (sx + ex) / 2f, groundY - dp(8f), 8f,
                            0xFF2A3648, Paint.Align.CENTER);
                    break;
                }
                case WALL: {
                    if (t.done) {
                        break;
                    }
                    float wh = dp(40f) + Math.max(0f, t.size / (float) profile.typicalWatts() - 0.7f) * dp(55f);
                    paint.setColor(0xFFB33A3A);
                    c.drawRect(sx - dp(8f), groundY - wh, sx + dp(8f), groundY, paint);
                    paint.setColor(0xFF7A2020);
                    for (float y = groundY - wh + dp(8f); y < groundY; y += dp(10f)) {
                        c.drawRect(sx - dp(8f), y, sx + dp(8f), y + dp(2f), paint);
                    }
                    bold(c, Math.round(t.size) + "W", sx, groundY - wh - dp(6f), 9f,
                            peakWatts >= t.size ? ACCENT : TEXT, Paint.Align.CENTER);
                    break;
                }
                case COIN:
                case HIGH_COIN: {
                    if (t.done) {
                        break;
                    }
                    float cy = t.kind == Kind.HIGH_COIN ? groundY - dp(70f) : groundY - dp(24f);
                    Fx.glow(c, sx, cy, dp(16f), 0x55FFE28A);
                    paint.setColor(0xFFF5C518);
                    c.drawCircle(sx, cy, dp(7f), paint);
                    paint.setColor(0xFFB8890B);
                    c.drawCircle(sx, cy, dp(3f), paint);
                    if (t.kind == Kind.HIGH_COIN) {
                        label(c, String.format(java.util.Locale.US, "%.1f m/s", highCoinSpeed()), sx, cy - dp(12f), 7.5f, FAINT, Paint.Align.CENTER);
                    }
                    break;
                }
                case ARC_COIN: {
                    if (t.done) {
                        break;
                    }
                    float cy = groundY - dp(24f) - t.size * dp(60f);
                    Fx.glow(c, sx, cy, dp(14f), 0x55FFE28A);
                    paint.setColor(0xFFF5C518);
                    c.drawCircle(sx, cy, dp(6f), paint);
                    break;
                }
                case CRAB: {
                    if (t.done) {
                        break;
                    }
                    float scuttle = (float) Math.sin(sessionSeconds * 14 + t.at) * dp(2f);
                    paint.setColor(0xFFE0582E);
                    c.drawOval(sx - dp(14f), groundY - dp(16f), sx + dp(14f), groundY - dp(2f), paint);
                    paint.setStrokeWidth(dp(2f));
                    for (int leg = -1; leg <= 1; leg += 2) {
                        c.drawLine(sx + leg * dp(10f), groundY - dp(6f), sx + leg * dp(18f), groundY + scuttle, paint);
                        c.drawLine(sx + leg * dp(6f), groundY - dp(6f), sx + leg * dp(12f), groundY - scuttle, paint);
                    }
                    paint.setColor(0xFFFFFFFF);
                    c.drawCircle(sx - dp(5f), groundY - dp(19f), dp(3f), paint);
                    c.drawCircle(sx + dp(5f), groundY - dp(19f), dp(3f), paint);
                    paint.setColor(0xFF10171F);
                    c.drawCircle(sx - dp(5f), groundY - dp(19f), dp(1.4f), paint);
                    c.drawCircle(sx + dp(5f), groundY - dp(19f), dp(1.4f), paint);
                    break;
                }
                default:
            }
        }

        // Where the next stroke will launch you, and where that jump lands: a dotted arc ahead, from
        // your rhythm and speed. Pull when the arc spans the pit or the crab, and you clear it.
        if (started && !over && lastStrokeAt >= 0 && speed > 0.8f) {
            double untilNext = Math.max(0, strokeInterval - (sessionSeconds - lastStrokeAt));
            float takeoff = youX + (float) (speed * untilNext) * ppm;
            float span = Math.max(2.5f, speed * JUMP_FACTOR) * ppm;
            if (takeoff < w - dp(20f)) {
                paint.setColor(0x88FFFFFF);
                for (int k = 0; k <= 10; k++) {
                    float f = k / 10f;
                    float ax = takeoff + span * f;
                    float ay = groundY - 4f * f * (1f - f) * dp(60f) - dp(24f);
                    c.drawCircle(ax, ay, dp(2.2f), paint);
                }
                paint.setColor(0xCC35D0BA);
                c.drawRect(takeoff - dp(1.5f), groundY - dp(10f), takeoff + dp(1.5f), groundY, paint);
            }
        }

        // Player.
        legPhase += speed * dt * 10f;
        float jy = jumpHeight() * dp(60f);
        boolean hurt = sessionSeconds < hurtUntil;
        Fx.glow(c, youX, groundY - jy - dp(24f), dp(40f), 0x33FFFFFF);
        if (!hurt || ((int) (sessionSeconds * 10) % 2 == 0)) {
            drawHero(c, youX, groundY - jy, speed, airborne());
        }
        fx.draw(c);
        c.restore();
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        if (hurt) {
            Fx.vignette(c, w, h, 0.7f, 0x8A1010);
        }

        // HUD.
        for (int i = 0; i < HEARTS; i++) {
            paint.setColor(i < hearts ? BAD : 0x33FFFFFF);
            float hx = dp(18f) + i * dp(22f);
            c.drawCircle(hx - dp(4f), dp(20f), dp(6f), paint);
            c.drawCircle(hx + dp(4f), dp(20f), dp(6f), paint);
            float[] tri = {hx - dp(10f), dp(22f), hx + dp(10f), dp(22f), hx, dp(34f)};
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(tri[0], tri[1]);
            p.lineTo(tri[2], tri[3]);
            p.lineTo(tri[4], tri[5]);
            p.close();
            c.drawPath(p, paint);
        }
        bold(c, "● " + coins, w - dp(16f), dp(30f), 20f, 0xFFF5C518, Paint.Align.RIGHT);
        bold(c, Math.round(x) + " m", w / 2f, dp(30f), 20f, TEXT, Paint.Align.CENTER);

        String cap;
        int col = FAINT;
        if (!started) {
            cap = "TAKE A STROKE TO RUN";
        } else if (over) {
            cap = "GAME OVER  ·  " + Math.round(x) + " m, " + coins + " coins  ·  tap to retry";
            col = BAD;
        } else if (bonked) {
            cap = "BONK - NEEDED MORE POWER";
            col = BAD;
        } else if (airborne()) {
            cap = "JUMP!";
            col = ACCENT;
        } else {
            cap = String.format(java.util.Locale.US, "PULL = JUMP   ·   %.1f m/s   ·   jump %.1f m   ·   peak %d W   ·   %d stroke jumps",
                    speed, Math.max(2.5f, speed * JUMP_FACTOR), peakWatts, strokeJumps);
        }
        bold(c, cap, w / 2f, h - dp(16f), 11f, col, Paint.Align.CENTER);
    }

    private void drawHero(Canvas c, float x0, float feetY, float speed, boolean inAir) {
        float s = dp(1f);
        float swing = inAir ? 6 * s : (speed > 0.2f ? (float) Math.sin(legPhase) * 7f * s : 0f);
        paint.setColor(0xFF2E5BBA);   // trousers
        c.drawRect(x0 - 6 * s + swing, feetY - 16 * s, x0 - 1 * s + swing, feetY, paint);
        c.drawRect(x0 + 1 * s - swing, feetY - 16 * s, x0 + 6 * s - swing, feetY, paint);
        paint.setColor(0xFFE84C3D);   // shirt
        c.drawRect(x0 - 8 * s, feetY - 36 * s, x0 + 8 * s, feetY - 16 * s, paint);
        paint.setColor(0xFFF1C27D);   // face
        c.drawRect(x0 - 6 * s, feetY - 48 * s, x0 + 6 * s, feetY - 36 * s, paint);
        paint.setColor(0xFFE84C3D);   // cap
        c.drawRect(x0 - 7 * s, feetY - 52 * s, x0 + 9 * s, feetY - 46 * s, paint);
    }

    private void fall(Thing pit) {
        hearts--;
        shake.kick(dp(10f));
        hurtUntil = sessionSeconds + 1.5;
        jumpFrom = -1;
        // Respawn on the far side so the same pit is not fatal twice.
        runStart = sessionMeters - (pit.at + pit.size + 1);
        x = pit.at + pit.size + 1;
        checkOver();
    }

    private void bonk(Thing wall) {
        hearts--;
        shake.kick(dp(12f));
        hurtUntil = sessionSeconds + 1.5;
        bonkUntil = sessionSeconds + 1.0;
        wall.done = true;   // it crumbles after taking a heart; the run goes on
        checkOver();
    }

    private void checkOver() {
        if (hearts <= 0) {
            over = true;
            bests.recordHighest("runner.distance", (float) x);
            bests.recordHighest("runner.coins", coins);
        }
    }
}
