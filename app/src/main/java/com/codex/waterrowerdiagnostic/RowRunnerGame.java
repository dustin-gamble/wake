package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;

/**
 * Row Runner: a side-scrolling platformer where rowing is running.
 *
 * <p>The rower's direction (3.19.1): "the character auto runs, jumps, and rows just increase speed,
 * lots of enemies, jump over blocks, coins". So the runner runs and jumps by itself, just before
 * every gap, crate stack and enemy. Rowing is the one control: boat speed is run speed, and a faster
 * run is a longer, higher jump. Ease off and the jumps fall short - into a gap, into a tall stack of
 * crates, onto a crab. Walls still need a burst of power to smash through.
 *
 * <p>Enemies walk toward you: crabs, bouncing slimes and rolling spiky balls. Clear one at speed and
 * you stomp it for coins. Coin arcs sit over every jump, so a good run collects them without trying;
 * high coins need a little under your typical speed.
 *
 * <p>The course is generated from a fixed seed, so a run is repeatable and a best means something,
 * and it is sized to the rower's own typical speed: gaps are clearable at a normal pace, not at a
 * crawl.
 */
final class RowRunnerGame extends GameView {

    private enum Kind { PIT, BLOCK, WALL, COIN, ARC_COIN, HIGH_COIN, CRAB, SLIME, SPIKY }

    private static final class Thing {
        final Kind kind;
        float at;             // metres; enemies walk, so not final
        final float size;     // pit width, crate count, wall watts
        final float height;   // arc coin height in metres
        boolean done;

        Thing(Kind kind, float at, float size, float height) {
            this.kind = kind;
            this.at = at;
            this.size = size;
            this.height = height;
        }

        boolean isEnemy() {
            return kind == Kind.CRAB || kind == Kind.SLIME || kind == Kind.SPIKY;
        }

        boolean needsJump() {
            return kind == Kind.PIT || kind == Kind.BLOCK || isEnemy();
        }
    }

    private static final int HEARTS = 5;
    /** Jump length in metres per m/s of speed. */
    private static final float JUMP_FACTOR = 1.9f;
    /** One crate, and one metre of jump height, on screen. */
    private static final float METRE_PX_DP = 28f;
    private static final float ENEMY_SPEED = 1.5f;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final java.util.List<Thing> things = new java.util.ArrayList<>();
    private final Fx.Shake shake = new Fx.Shake();
    private final Fx.Particles fx = new Fx.Particles();

    private boolean started;
    private boolean over;
    private int hearts;
    private int coins;
    private int stomps;
    private double runStart;
    private double x;
    private float jumpFrom = -1;
    private float jumpLen;
    private float jumpApex;
    private double hurtUntil;
    private double bonkUntil;
    private double peakWattsAt;
    private int peakWatts;
    private float legPhase;
    private boolean wasAirborne;
    private String flash = "";
    private double flashUntil;

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
        stomps = 0;
        x = 0;
        jumpFrom = -1;
        peakWatts = 0;
        things.clear();
        buildLevel();
    }

    /** A dense course sized to the rower's typical jump, harder with distance. */
    private void buildLevel() {
        java.util.Random r = new java.util.Random(1234);
        float typicalSpeed = (float) profile.typicalSpeed();
        float typicalWatts = (float) profile.typicalWatts();
        float reach = Math.max(4f, typicalSpeed * JUMP_FACTOR);
        float at = 30f;
        while (at < 8000f) {
            float difficulty = Math.min(1f, at / 2500f);
            int roll = r.nextInt(100);
            if (roll < 20) {
                float width = 2f + r.nextFloat() * (reach * 0.7f - 2f) * (0.4f + 0.6f * difficulty);
                things.add(new Thing(Kind.PIT, at, width, 0));
                coinArc(at - 0.5f, width + 1f, 1.6f);
                at += width + reach + 2f;
            } else if (roll < 40) {
                int crates = 1 + (int) (r.nextFloat() * (1 + difficulty * 2.2f));
                things.add(new Thing(Kind.BLOCK, at, crates, 0));
                coinArc(at - 1.5f, 4f, crates + 1.2f);
                at += 1f + reach + 2f;
            } else if (roll < 62) {
                Kind enemy = r.nextInt(3) == 0 ? Kind.SPIKY : r.nextBoolean() ? Kind.SLIME : Kind.CRAB;
                things.add(new Thing(enemy, at + 18f, 0, 0));
                at += reach + 3f;
            } else if (roll < 70) {
                float watts = typicalWatts * (0.7f + r.nextFloat() * (0.35f + difficulty * 0.5f));
                things.add(new Thing(Kind.WALL, at, watts, 0));
                at += 6f;
            } else if (roll < 90) {
                for (int i = 0; i < 5; i++) {
                    things.add(new Thing(Kind.COIN, at + i * 1.5f, 0, 0));
                }
                at += 8f;
            } else {
                things.add(new Thing(Kind.HIGH_COIN, at, 0, 0));
                at += 4f;
            }
            at += 3f + r.nextFloat() * 6f;
        }
    }

    /** Coins along the path of a jump, so a clean jump collects them. */
    private void coinArc(float start, float span, float peak) {
        for (int i = 0; i < 5; i++) {
            float f = (i + 0.5f) / 5f;
            things.add(new Thing(Kind.ARC_COIN, start + span * f, 0, 4f * f * (1f - f) * peak));
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
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && over) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private boolean airborne() {
        return jumpFrom >= 0 && x < jumpFrom + jumpLen;
    }

    /** Height above the ground right now, in metres. */
    private float height() {
        if (!airborne()) {
            return 0f;
        }
        float f = (float) ((x - jumpFrom) / jumpLen);
        return 4f * f * (1f - f) * jumpApex;
    }

    private float highCoinSpeed() {
        return (float) profile.typicalSpeed() * 0.9f;
    }

    private void say(String text) {
        flash = text;
        flashUntil = sessionSeconds + 1.2;
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
        float groundY = h * 0.72f;
        float ppm = w / 40f;
        float youX = w * 0.30f;
        float metre = dp(METRE_PX_DP);

        if (started && !over) {
            update(dt, speed, w, groundY, youX, metre);
        }

        shake.step(dt);
        fx.step(dt, dp(300f));
        boolean airNow = airborne();
        if (wasAirborne && !airNow && started && !over) {
            fx.burst(youX, groundY, 12, dp(80f), 0.4f, dp(2.5f), 0xCC8B5A2B, true);
        }
        wasAirborne = airNow;

        c.save();
        c.translate(shake.dx, shake.dy);
        drawWorld(c, w, h, groundY, ppm, youX, metre);
        legPhase += speed * dt * 10f;
        float jy = height() * metre;
        boolean hurt = sessionSeconds < hurtUntil;
        Fx.glow(c, youX, groundY - jy - dp(24f), dp(40f), 0x33FFFFFF);
        if (!hurt || ((int) (sessionSeconds * 10) % 2 == 0)) {
            drawHero(c, youX, groundY - jy, speed, airNow);
        }
        fx.draw(c);
        c.restore();
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        if (hurt) {
            Fx.vignette(c, w, h, 0.7f, 0x8A1010);
        }
        drawHud(c, w, h, speed);
    }

    private void update(float dt, float speed, float w, float groundY, float youX, float metre) {
        // Enemies walk toward you once they are in view.
        for (Thing t : things) {
            if (t.isEnemy() && !t.done && t.at > x && t.at < x + 40f) {
                t.at -= (t.kind == Kind.SPIKY ? ENEMY_SPEED * 1.4f : ENEMY_SPEED) * dt;
            }
        }
        // Jump automatically just before whatever needs jumping. Speed sets how far and how high.
        if (!airborne()) {
            jumpFrom = -1;
            for (Thing t : things) {
                if (t.done || !t.needsJump() || t.at < x - 0.2f) {
                    continue;
                }
                float trigger = 0.8f + speed * 0.18f + (t.isEnemy() ? 0.6f : 0f);
                if (t.at - x <= trigger) {
                    jumpFrom = (float) x;
                    jumpLen = Math.max(1.6f, speed * JUMP_FACTOR);
                    jumpApex = 1.1f + speed * 0.5f;
                }
                break;
            }
        }
        float hNow = height();
        for (Thing t : things) {
            if (t.done || t.at > x + 1.5f) {
                continue;
            }
            switch (t.kind) {
                case PIT:
                    if (x >= t.at + t.size) {
                        t.done = true;
                    } else if (x >= t.at && hNow <= 0f) {
                        fall(t);
                    }
                    break;
                case BLOCK:
                    if (x >= t.at + 1f) {
                        t.done = true;
                        coins++;
                    } else if (x >= t.at - 0.3f && hNow < t.size) {
                        t.done = true;
                        hit("CRATE!");
                        fx.burst(youX + dp(10f), groundY - t.size * metre / 2f, 24, dp(150f), 0.6f, dp(4f), 0xFFB07A45, true);
                    }
                    break;
                case WALL:
                    if (x >= t.at) {
                        if (peakWatts >= t.size) {
                            t.done = true;
                            fx.burst(youX + dp(10f), groundY - dp(40f), 30, dp(160f), 0.8f, dp(4f), 0xFFB33A3A, true);
                            shake.kick(dp(8f));
                            say("SMASH!");
                        } else if (sessionSeconds > hurtUntil) {
                            t.done = true;
                            bonkUntil = sessionSeconds + 1.0;
                            hit("BONK - PULL HARDER");
                        }
                    }
                    break;
                case COIN:
                    if (x >= t.at) {
                        t.done = true;
                        if (hNow < 1.2f) {
                            coins++;
                            fx.burst(youX, groundY - dp(24f), 8, dp(80f), 0.4f, dp(3f), 0xFFF5C518, true);
                        }
                    }
                    break;
                case ARC_COIN:
                    if (x >= t.at) {
                        t.done = true;
                        if (Math.abs(hNow - t.height) < 0.9f) {
                            coins++;
                            fx.burst(youX, groundY - hNow * metre - dp(20f), 8, dp(80f), 0.4f, dp(3f), 0xFFF5C518, true);
                        }
                    }
                    break;
                case HIGH_COIN:
                    if (x >= t.at) {
                        t.done = true;
                        if (speed >= highCoinSpeed()) {
                            coins += 5;
                            fx.burst(youX, groundY - dp(90f), 24, dp(140f), 0.7f, dp(3.5f), 0xFFF5C518, true);
                            say("+5");
                        }
                    }
                    break;
                default:   // enemies
                    if (Math.abs((float) x - t.at) < 0.6f) {
                        t.done = true;
                        if (hNow > 0.7f) {
                            coins += 2;
                            stomps++;
                            fx.burst(youX, groundY - dp(20f), 18, dp(130f), 0.5f, dp(3.5f), enemyColour(t.kind), true);
                            say(stomps % 5 == 0 ? stomps + " STOMPS!" : "STOMP");
                        } else {
                            hit("OUCH!");
                        }
                    }
                    break;
            }
        }
    }

    private void hit(String what) {
        if (sessionSeconds < hurtUntil) {
            return;
        }
        hearts--;
        shake.kick(dp(12f));
        hurtUntil = sessionSeconds + 1.5;
        say(what);
        checkOver();
    }

    private void fall(Thing pit) {
        hearts--;
        shake.kick(dp(10f));
        hurtUntil = sessionSeconds + 1.5;
        jumpFrom = -1;
        say("ROW FASTER TO CLEAR THE GAPS");
        // Back on the far side, so the same gap is not fatal twice.
        runStart = sessionMeters - (pit.at + pit.size + 1);
        x = pit.at + pit.size + 1;
        pit.done = true;
        checkOver();
    }

    private void checkOver() {
        if (hearts <= 0) {
            over = true;
            bests.recordHighest("runner.distance", (float) x);
            bests.recordHighest("runner.coins", coins);
        }
    }

    private static int enemyColour(Kind k) {
        return k == Kind.CRAB ? 0xFFE0582E : k == Kind.SLIME ? 0xFF6BCB5A : 0xFF9A6BB0;
    }

    /* ---------- drawing ---------- */

    /** The world is drawn through the screen shake, so everything full-width bleeds past the edges. */
    private static final float OVERSCAN = 26f;

    private void drawWorld(Canvas c, float w, float h, float groundY, float ppm, float youX, float metre) {
        float camX = (float) x;
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, groundY, 0xFF3C8EE0, 0xFF9CD1F7,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(-dp(OVERSCAN), -dp(OVERSCAN), w + dp(OVERSCAN), groundY, paint);
        paint.setShader(null);
        Fx.glow(c, w * 0.85f, h * 0.14f, dp(60f), 0x66FFE28A);
        paint.setColor(0xFFFFE28A);
        c.drawCircle(w * 0.85f, h * 0.14f, dp(22f), paint);
        drawPixelSky(c, w, h, groundY, ppm, camX);
        paint.setColor(0xFF4E9E4A);
        for (int i = 0; i < 6; i++) {
            float hx = ((i * 260f * dp(1f)) - (camX * ppm * 0.12f)) % (w + dp(300f));
            if (hx < -dp(150f)) {
                hx += w + dp(300f);
            }
            c.drawCircle(hx, groundY + dp(30f), dp(90f) + (i % 3) * dp(25f), paint);
        }
        paint.setColor(0xFFDCEBF7);
        for (int i = 0; i < 4; i++) {
            float cx = ((i * 231f + 60f) - (camX * ppm * 0.2f)) % (w + dp(120f));
            if (cx < 0) {
                cx += w + dp(120f);
            }
            float cy = h * 0.12f + (i % 2) * dp(40f);
            c.drawRect(cx - dp(30f), cy, cx + dp(30f), cy + dp(14f), paint);
            c.drawRect(cx - dp(16f), cy - dp(10f), cx + dp(16f), cy + dp(14f), paint);
        }
        paint.setColor(0xFF6BBF59);
        c.drawRect(-dp(OVERSCAN), groundY, w + dp(OVERSCAN), groundY + dp(14f), paint);
        paint.setColor(0xFF8B5A2B);
        c.drawRect(-dp(OVERSCAN), groundY + dp(14f), w + dp(OVERSCAN), h + dp(OVERSCAN), paint);
        paint.setColor(0xFF74461F);
        float block = dp(28f);
        float off = (camX * ppm) % block;
        for (float bx = -off; bx < w; bx += block) {
            c.drawRect(bx, groundY + dp(14f), bx + dp(2f), h, paint);
        }
        // 3.19.6: underground was a third of the tablet screen and empty. Soil layers, buried rocks,
        // roots and the odd fossil scroll past; grass tufts run along the surface in front.
        drawUnderground(c, w, h, groundY, ppm, camX);

        for (Thing t : things) {
            float sx = youX + (t.at - camX) * ppm;
            if (sx < -dp(240f) || sx > w + dp(60f)) {
                continue;
            }
            switch (t.kind) {
                case PIT: {
                    // Clamped to the screen: the loop keeps things up to 240dp off the left edge, and
                    // a pit straddling the viewport painted its near-black fill as a bar at the edge.
                    float ex = sx + t.size * ppm;
                    paint.setColor(0xFF10171F);
                    c.drawRect(Math.max(0f, sx), groundY, Math.min(w, ex), h, paint);
                    break;
                }
                case BLOCK: {
                    if (t.done) {
                        break;
                    }
                    for (int k = 0; k < (int) t.size; k++) {
                        float top = groundY - (k + 1) * metre;
                        paint.setColor(0xFFB07A45);
                        c.drawRect(sx, top, sx + metre, top + metre - dp(1f), paint);
                        paint.setColor(0xFF7A5230);
                        paint.setStrokeWidth(dp(3f));
                        c.drawLine(sx + dp(3f), top + dp(3f), sx + metre - dp(3f), top + metre - dp(4f), paint);
                        c.drawLine(sx + metre - dp(3f), top + dp(3f), sx + dp(3f), top + metre - dp(4f), paint);
                    }
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
                case ARC_COIN:
                case HIGH_COIN: {
                    if (t.done) {
                        break;
                    }
                    float cy = t.kind == Kind.HIGH_COIN ? groundY - dp(90f)
                            : t.kind == Kind.ARC_COIN ? groundY - dp(20f) - t.height * metre : groundY - dp(20f);
                    Fx.glow(c, sx, cy, dp(14f), 0x55FFE28A);
                    paint.setColor(0xFFF5C518);
                    c.drawCircle(sx, cy, dp(t.kind == Kind.HIGH_COIN ? 8f : 6f), paint);
                    paint.setColor(0xFFB8890B);
                    c.drawCircle(sx, cy, dp(2.5f), paint);
                    if (t.kind == Kind.HIGH_COIN) {
                        label(c, String.format(java.util.Locale.US, "%.1f m/s", highCoinSpeed()), sx, cy - dp(13f), 7.5f,
                                FAINT, Paint.Align.CENTER);
                    }
                    break;
                }
                default:
                    if (!t.done) {
                        drawEnemy(c, t, sx, groundY);
                    }
                    break;
            }
        }
    }

    /**
     * 3.19.5: the sky was two-thirds of the screen and nearly empty. In the same blocky style as the
     * clouds: far mountains with snow caps, a castle on the horizon now and then, pixel birds, and an
     * airship drifting the other way. All pure background - nothing up here can be hit.
     */
    private void drawPixelSky(Canvas c, float w, float h, float groundY, float ppm, float camX) {
        double t = sessionSeconds;
        float px = dp(6f);
        // Far mountains: stepped pixel triangles at a very slow parallax.
        float mSpan = dp(360f);
        float mOff = (camX * ppm * 0.05f) % mSpan;
        for (float mx = -mOff - mSpan; mx < w + mSpan; mx += mSpan) {
            int k = (int) Math.floor((mx + camX * ppm * 0.05f) / mSpan + 0.5f);
            float peakH = dp(150f) + (Math.abs(k * 7) % 3) * dp(40f);
            float base = groundY + dp(10f);
            for (float step = 0; step < peakH; step += px * 2) {
                float half = (peakH - step) * 0.9f;
                paint.setColor(0xFF7FA3C8);
                c.drawRect(mx - half, base - step - px * 2, mx + half, base - step, paint);
            }
            paint.setColor(0xFFF2F7FB);
            for (float step = peakH - dp(36f); step < peakH; step += px * 2) {
                float half = (peakH - step) * 0.9f;
                c.drawRect(mx - half, base - step - px * 2, mx + half, base - step, paint);
            }
        }
        // A castle on the horizon every so often.
        float cSpan = w * 1.6f;
        float cx = (float) (((w * 0.7f - camX * ppm * 0.08f) % cSpan + cSpan) % cSpan) - dp(100f);
        float cBase = groundY - dp(60f);
        paint.setColor(0xFF5F7FA6);
        c.drawRect(cx - dp(48f), cBase - dp(48f), cx + dp(48f), cBase + dp(40f), paint);
        c.drawRect(cx - dp(66f), cBase - dp(84f), cx - dp(38f), cBase + dp(40f), paint);
        c.drawRect(cx + dp(38f), cBase - dp(84f), cx + dp(66f), cBase + dp(40f), paint);
        for (int b = 0; b < 3; b++) {
            c.drawRect(cx - dp(66f) + b * dp(10f), cBase - dp(96f), cx - dp(60f) + b * dp(10f), cBase - dp(84f), paint);
            c.drawRect(cx + dp(38f) + b * dp(10f), cBase - dp(96f), cx + dp(44f) + b * dp(10f), cBase - dp(84f), paint);
        }
        paint.setColor(0xFFE0582E);
        c.drawRect(cx - dp(53f), cBase - dp(120f), cx - dp(51f), cBase - dp(96f), paint);
        c.drawRect(cx - dp(51f), cBase - dp(120f), cx - dp(37f) + (float) Math.sin(t * 5) * dp(2f), cBase - dp(110f), paint);
        paint.setColor(0xFF2E4466);
        c.drawRect(cx - dp(10f), cBase + dp(10f), cx + dp(10f), cBase + dp(40f), paint);
        // Pixel birds: two-block wings that flap.
        paint.setColor(0xFF1F2A3A);
        float fx = (float) (w - ((t * dp(45f)) % (w + dp(300f))));
        for (int b = 0; b < 4; b++) {
            float bx = fx + b * dp(28f);
            float by = h * 0.26f + (b % 2) * dp(14f);
            boolean up = ((int) (t * 6 + b)) % 2 == 0;
            c.drawRect(bx - px * 2, by + (up ? -px : 0), bx - px, by + (up ? 0 : px), paint);
            c.drawRect(bx - px, by, bx + px, by + px, paint);
            c.drawRect(bx + px, by + (up ? -px : 0), bx + px * 2, by + (up ? 0 : px), paint);
        }
        // An airship drifting the other way, slowly.
        float ax = (float) (((t * dp(18f)) % (w + dp(400f))) - dp(200f));
        float ay = h * 0.20f;
        paint.setColor(0xFFE8E3D3);
        c.drawRect(ax - dp(60f), ay - dp(14f), ax + dp(60f), ay + dp(14f), paint);
        c.drawRect(ax - dp(48f), ay - dp(20f), ax + dp(48f), ay + dp(20f), paint);
        paint.setColor(0xFFE0582E);
        c.drawRect(ax - dp(48f), ay - dp(3f), ax + dp(48f), ay + dp(3f), paint);
        paint.setColor(0xFF8B5A2B);
        c.drawRect(ax - dp(18f), ay + dp(24f), ax + dp(18f), ay + dp(34f), paint);
        paint.setColor(0xFFFFE28A);
        c.drawRect(ax - dp(12f), ay + dp(27f), ax - dp(6f), ay + dp(31f), paint);
        c.drawRect(ax + dp(6f), ay + dp(27f), ax + dp(12f), ay + dp(31f), paint);
        paint.setColor(0xFFDCEBF7);
        if (((int) (t * 8)) % 2 == 0) {
            c.drawRect(ax - dp(70f), ay - dp(12f), ax - dp(62f), ay + dp(12f), paint);
        } else {
            c.drawRect(ax - dp(70f), ay - dp(4f), ax - dp(62f), ay + dp(4f), paint);
        }
    }

    /** Soil bands, rocks, roots and grass tufts - the ground is no longer a brown slab. */
    private void drawUnderground(Canvas c, float w, float h, float groundY, float ppm, float camX) {
        float px = dp(6f);
        // Soil bands, each a slightly different brown, with a pebbly seam.
        int bands = 4;
        for (int b = 0; b < bands; b++) {
            float y0 = groundY + dp(14f) + (h - groundY - dp(14f)) * b / bands;
            float y1 = groundY + dp(14f) + (h - groundY - dp(14f)) * (b + 1) / bands;
            paint.setColor(b % 2 == 0 ? 0xFF7E5127 : 0xFF6E461F);
            c.drawRect(-dp(OVERSCAN), y0, w + dp(OVERSCAN), y1 + dp(1f), paint);
            paint.setColor(0x33000000);
            c.drawRect(-dp(OVERSCAN), y0, w + dp(OVERSCAN), y0 + px * 0.5f, paint);
        }
        float scroll = camX * ppm;
        float gap = dp(90f);
        float o = scroll % gap;
        for (float x = -o - gap; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5f);
            int kind = Math.abs(k * 7919) % 4;
            float depth = groundY + dp(40f) + (Math.abs(k * 131) % 5) * dp(34f);
            if (depth > h - dp(20f)) {
                continue;
            }
            if (kind == 0) {
                // Rock.
                paint.setColor(0xFF5A4636);
                c.drawRect(x - px * 3, depth - px * 2, x + px * 3, depth + px * 2, paint);
                paint.setColor(0x44FFFFFF);
                c.drawRect(x - px * 2, depth - px * 2, x, depth - px, paint);
            } else if (kind == 1) {
                // Root reaching down from the grass.
                paint.setColor(0xFF5E3E1C);
                c.drawRect(x, groundY + dp(14f), x + px, depth, paint);
                c.drawRect(x - px * 2, depth - px * 3, x + px, depth - px * 2, paint);
                c.drawRect(x + px, depth - px * 6, x + px * 3, depth - px * 5, paint);
            } else if (kind == 2) {
                // Buried coin, a wink of gold.
                paint.setColor(0xFFC9A227);
                c.drawOval(x - px, depth - px, x + px, depth + px, paint);
            } else {
                // Fossil: three little bones.
                paint.setColor(0xFFCFC3A8);
                for (int i = 0; i < 3; i++) {
                    c.drawRect(x - px * 2 + i * px * 2, depth, x - px + i * px * 2, depth + px, paint);
                }
            }
        }
        // Grass tufts along the surface, scrolling at full speed in front of the runner.
        float tuft = dp(26f);
        float to = scroll % tuft;
        paint.setColor(0xFF6BBF59);
        c.drawRect(-dp(OVERSCAN), groundY, w + dp(OVERSCAN), groundY + dp(14f), paint);
        for (float x = -to - tuft; x < w + tuft; x += tuft) {
            int k = (int) Math.floor((x + scroll) / tuft + 0.5f);
            float tall = dp(6f) + (Math.abs(k * 17) % 3) * dp(4f);
            paint.setColor((k & 1) == 0 ? 0xFF4E9E4A : 0xFF83D06A);
            c.drawRect(x, groundY - tall, x + px * 0.6f, groundY, paint);
            c.drawRect(x + px, groundY - tall * 0.6f, x + px * 1.6f, groundY, paint);
        }
    }

    private void drawEnemy(Canvas c, Thing t, float sx, float groundY) {
        float wobble = (float) Math.sin(sessionSeconds * 12 + t.at);
        switch (t.kind) {
            case CRAB: {
                paint.setColor(0xFFE0582E);
                c.drawOval(sx - dp(14f), groundY - dp(16f), sx + dp(14f), groundY - dp(2f), paint);
                paint.setStrokeWidth(dp(2f));
                for (int leg = -1; leg <= 1; leg += 2) {
                    c.drawLine(sx + leg * dp(10f), groundY - dp(6f), sx + leg * dp(18f), groundY + wobble * dp(2f), paint);
                    c.drawLine(sx + leg * dp(6f), groundY - dp(6f), sx + leg * dp(12f), groundY - wobble * dp(2f), paint);
                }
                eyes(c, sx, groundY - dp(19f));
                break;
            }
            case SLIME: {
                float squash = 1f + 0.18f * wobble;
                paint.setColor(0xFF6BCB5A);
                c.drawOval(sx - dp(15f) * squash, groundY - dp(22f) / squash, sx + dp(15f) * squash, groundY, paint);
                paint.setColor(0x66FFFFFF);
                c.drawCircle(sx - dp(6f), groundY - dp(15f) / squash, dp(3f), paint);
                eyes(c, sx, groundY - dp(12f) / squash);
                break;
            }
            default: {
                float r = dp(13f);
                float cy = groundY - r;
                paint.setColor(0xFF9A6BB0);
                c.drawCircle(sx, cy, r, paint);
                paint.setStrokeWidth(dp(2.5f));
                double spin = -sessionSeconds * 6;
                for (int s = 0; s < 8; s++) {
                    double a = spin + s * Math.PI / 4;
                    c.drawLine(sx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r,
                            sx + (float) Math.cos(a) * (r + dp(6f)), cy + (float) Math.sin(a) * (r + dp(6f)), paint);
                }
                eyes(c, sx, cy - dp(2f));
                break;
            }
        }
    }

    private void eyes(Canvas c, float cx, float cy) {
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(cx - dp(5f), cy, dp(3f), paint);
        c.drawCircle(cx + dp(5f), cy, dp(3f), paint);
        paint.setColor(0xFF10171F);
        c.drawCircle(cx - dp(6f), cy, dp(1.4f), paint);
        c.drawCircle(cx + dp(4f), cy, dp(1.4f), paint);
    }

    private void drawHero(Canvas c, float x0, float feetY, float speed, boolean inAir) {
        float s = dp(1f);
        float swing = inAir ? 6 * s : (speed > 0.2f ? (float) Math.sin(legPhase) * 7f * s : 0f);
        paint.setColor(0xFF2E5BBA);
        c.drawRect(x0 - 6 * s + swing, feetY - 16 * s, x0 - 1 * s + swing, feetY, paint);
        c.drawRect(x0 + 1 * s - swing, feetY - 16 * s, x0 + 6 * s - swing, feetY, paint);
        paint.setColor(0xFFE84C3D);
        c.drawRect(x0 - 8 * s, feetY - 36 * s, x0 + 8 * s, feetY - 16 * s, paint);
        paint.setColor(0xFFF1C27D);
        c.drawRect(x0 - 6 * s, feetY - 48 * s, x0 + 6 * s, feetY - 36 * s, paint);
        paint.setColor(0xFFE84C3D);
        c.drawRect(x0 - 7 * s, feetY - 52 * s, x0 + 9 * s, feetY - 46 * s, paint);
    }

    private void drawHud(Canvas c, float w, float h, float speed) {
        for (int i = 0; i < HEARTS; i++) {
            paint.setColor(i < hearts ? BAD : 0x33FFFFFF);
            float hx = dp(18f) + i * dp(22f);
            c.drawCircle(hx - dp(4f), dp(20f), dp(6f), paint);
            c.drawCircle(hx + dp(4f), dp(20f), dp(6f), paint);
            path.reset();
            path.moveTo(hx - dp(10f), dp(22f));
            path.lineTo(hx + dp(10f), dp(22f));
            path.lineTo(hx, dp(34f));
            path.close();
            c.drawPath(path, paint);
        }
        bold(c, "● " + coins, w - dp(16f), dp(30f), 20f, 0xFFF5C518, Paint.Align.RIGHT);
        bold(c, Math.round(x) + " m", w / 2f, dp(30f), 20f, TEXT, Paint.Align.CENTER);
        if (sessionSeconds < flashUntil) {
            bold(c, flash, w / 2f, h * 0.36f, 24f, flash.startsWith("STOMP") || flash.startsWith("+") || flash.startsWith("SMASH")
                    || flash.endsWith("STOMPS!") ? ACCENT : WARN, Paint.Align.CENTER);
        }
        String cap;
        int col = FAINT;
        if (!started) {
            cap = "TAKE A STROKE TO RUN  ·  JUMPS ARE AUTOMATIC - ROW FASTER TO RUN FASTER";
        } else if (over) {
            cap = "GAME OVER  ·  " + Math.round(x) + " m, " + coins + " coins, " + stomps + " stomps  ·  tap to retry";
            col = BAD;
        } else {
            cap = String.format(java.util.Locale.US, "ROW FASTER = RUN FASTER, JUMP FURTHER   ·   %.1f m/s   ·   %d stomps   ·   peak %d W",
                    speed, stomps, peakWatts);
        }
        bold(c, cap, w / 2f, h - dp(16f), 11f, col, Paint.Align.CENTER);
    }
}
