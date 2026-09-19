package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
 * <p>The five upgrades the rower approved:
 * <ul>
 *   <li><b>King Crab every kilometre.</b> The run stops at the boss; it is beaten with power surges
 *   above the rower's own high watts. Its claw glows before it strikes - surge during the glow to
 *   counter for double damage, or lose a heart. Each claw that lands tires it, so the surge line
 *   comes down toward typical and nobody is stuck at a boss forever.</li>
 *   <li><b>Worlds.</b> Each kilometre is a world - meadow, desert, snowfields, castle - with its own
 *   sky, ground, props, crates, pits (lava in the castle) and enemy colours. Beating the boss is the
 *   gate into the next.</li>
 *   <li><b>Star power.</b> Hold power above the star line for a few seconds and the runner is
 *   invincible for six: crates, walls and enemies smash, gaps cannot drop you, claws bounce off.</li>
 *   <li><b>Checkpoint flags</b> every 250 m and after each boss. Game over offers CONTINUE from the
 *   last flag (hearts refilled, coins as they were at the flag) as well as RESTART.</li>
 *   <li><b>Ghost of your best run.</b> The furthest run is recorded once a second and replayed as a
 *   translucent runner on the same run clock, with a BEST post where it ended.</li>
 * </ul>
 *
 * <p>The course is generated from a fixed seed, so a run is repeatable and a best means something,
 * and it is sized to the rower's own typical speed: gaps are clearable at a normal pace, not at a
 * crawl.
 */
final class RowRunnerGame extends GameView {

    private enum Kind { PIT, BLOCK, WALL, COIN, ARC_COIN, HIGH_COIN, CRAB, SLIME, SPIKY, BOSS, FLAG }

    private static final class Thing {
        final Kind kind;
        float at;             // metres; enemies walk, so not final
        final float size;     // pit width, crate count, wall watts
        final float height;   // arc coin height in metres
        boolean done;
        /** When a checkpoint flag was raised, for its animation. */
        double raisedAt = -1;

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
    private static final float LEVEL_LENGTH = 12000f;

    /* Boss, checkpoints, star, ghost. */
    private static final float BOSS_EVERY = 1000f;
    private static final float FLAG_EVERY = 250f;
    /** The run holds this many metres short of the boss while fighting it. */
    private static final float BOSS_STAND = 8f;
    /** How long the claw glows before it strikes: about one stroke at 25 spm. */
    private static final double WINDUP = 2.4;
    private static final double BOSS_REST = 3.5;
    private static final float STAR_CHARGE_SECONDS = 4f;
    private static final double STAR_SECONDS = 6.0;
    private static final String GHOST_KEY = "runner.ghost";
    private static final String BOSSES_KEY = "runner.bosses";
    /** One sample a second: an hour of running. */
    private static final int MAX_GHOST = 3600;

    /* Worlds: one per kilometre, in this order, then round again. */
    private static final String[] WORLD_NAMES = {"MEADOW", "DESERT", "SNOWFIELDS", "CASTLE"};
    private static final int[] SKY_TOP = {0xFF3C8EE0, 0xFFE58A3C, 0xFF7FA6CF, 0xFF140F26};
    private static final int[] SKY_BOT = {0xFF9CD1F7, 0xFFF7D9A0, 0xFFE3EEF7, 0xFF4A2E5E};
    private static final int[] HILL = {0xFF4E9E4A, 0xFFD9A55B, 0xFFEAF2FA, 0xFF2B2440};
    private static final int[] CLOUD = {0xFFDCEBF7, 0xFFFBE9C8, 0xFFF4F8FC, 0xFF3A3050};
    private static final int[] MOUNT = {0xFF7FA3C8, 0xFFC98A5A, 0xFF9AB0C8, 0xFF2E2545};
    private static final int[] CAP = {0xFFF2F7FB, 0xFFE3B07A, 0xFFFFFFFF, 0xFF4A3C66};
    private static final int[] TOP = {0xFF6BBF59, 0xFFE9C27A, 0xFFF7FBFF, 0xFF8A8898};
    private static final int[] SOIL_A = {0xFF7E5127, 0xFFC99A55, 0xFF8FA6BF, 0xFF4F4B5C};
    private static final int[] SOIL_B = {0xFF6E461F, 0xFFB88A48, 0xFF7D94AD, 0xFF454152};
    private static final int[] TUFT_A = {0xFF4E9E4A, 0xFF8FA34A, 0xFFFFFFFF, 0xFF6A6878};
    private static final int[] TUFT_B = {0xFF83D06A, 0xFFB5A05A, 0xFFD8E6F2, 0xFF5A5868};
    private static final int[] CRATE = {0xFFB07A45, 0xFFD2B278, 0xFFA8DCF0, 0xFF8C8C9C};
    private static final int[] CRATE_LINE = {0xFF7A5230, 0xFFA0804A, 0xFF6FAACB, 0xFF5C5C6C};
    private static final int[] PIT_FILL = {0xFF10171F, 0xFF2A1A0C, 0xFF0E1A2A, 0xFFB8360C};
    private static final int[] CRAB_COL = {0xFFE0582E, 0xFFC9853A, 0xFF5FB6E0, 0xFFB03A5A};
    private static final int[] SLIME_COL = {0xFF6BCB5A, 0xFFB0C94A, 0xFFBFE6F5, 0xFF8E5AE0};
    private static final int[] SPIKY_COL = {0xFF9A6BB0, 0xFF8A5A3A, 0xFFE8F2FA, 0xFF505060};
    private static final int[] BOSS_BODY = {0xFFD43A2A, 0xFFC98A3A, 0xFF4FA8D8, 0xFF7A2E9A};
    private static final int[] BOSS_DARK = {0xFF8E2218, 0xFF8A5A22, 0xFF2E6E9A, 0xFF4A1A62};
    private static final int[] BIRD = {0xFF1F2A3A, 0xFF5A3A1A, 0xFF1F2A3A, 0xFF0A0612};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final java.util.List<Thing> things = new java.util.ArrayList<>();
    private final Fx.Shake shake = new Fx.Shake();
    private final Fx.Particles fx = new Fx.Particles();
    private final float[] hsv = new float[3];
    private final RectF continueRect = new RectF();
    private final RectF restartRect = new RectF();

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

    /* Sky gradient, rebuilt only when the world or the screen changes. */
    private android.graphics.LinearGradient skyShader;
    private int skyWorld = -1;
    private float skyGround = -1;
    private int shownWorld;
    private double worldBannerUntil;
    private float whiteFlash;

    /* Boss fight. */
    private boolean fighting;
    private Thing boss;
    private int bossHp;
    private int bossMaxHp;
    private double windupStart = -1;
    private double nextWindupAt;
    private double bossHitAt = -10;
    private double bossStruckAt = -10;
    private double heroLungeAt = -10;
    private double bossDefeatedAt = -10;
    private float bossDefeatedAtMetres;
    private float bossSurgeScale = 1f;
    private boolean surgeArmed = true;
    private int surgeStrokes = -1;
    private double surgeAt = -10;
    private int pendingSurges;
    private int bossesBeaten;

    /* Star power. */
    private float starCharge;
    private double starUntil = -1;

    /* Checkpoints. */
    private float checkpoint;
    private int checkpointCoins;
    private int checkpointStomps;
    private int checkpointBosses;
    private int continues;

    /* Ghost: this run's recording, and the best run replayed. */
    private double runClock;
    private final int[] ghostRec = new int[MAX_GHOST];
    private int ghostRecLen;
    private double nextGhostSample;
    private int[] ghost;
    private float ghostEnd;
    private float storedBest;
    private float ghostLegPhase;
    private boolean newBestRun;

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
        hurtUntil = 0;
        bonkUntil = 0;
        fighting = false;
        boss = null;
        pendingSurges = 0;
        bossesBeaten = 0;
        bossDefeatedAt = -10;
        // start() puts sessionSeconds back to 0, so every timestamp from the last run must go too:
        // a stale bossHitAt left the next King Crab drawn white and knocked far right, a stale
        // bossStruckAt held its claw slammed down, and a stale peakWattsAt froze the 2 s peak so the
        // star meter charged by itself.
        bossHitAt = -10;
        bossStruckAt = -10;
        heroLungeAt = -10;
        surgeAt = -10;
        windupStart = -1;
        nextWindupAt = 0;
        peakWattsAt = 0;
        flashUntil = 0;
        flash = "";
        starCharge = 0;
        starUntil = -1;
        checkpoint = 0;
        checkpointCoins = 0;
        checkpointStomps = 0;
        checkpointBosses = 0;
        continues = 0;
        runClock = 0;
        ghostRecLen = 0;
        nextGhostSample = 0;
        newBestRun = false;
        shownWorld = 0;
        worldBannerUntil = 0;
        whiteFlash = 0;
        loadGhost();
        things.clear();
        buildLevel();
    }

    @Override
    protected void onStop() {
        // Leaving mid-run still counts: the furthest you got, and the ghost if it is your best.
        if (started && !over && x > 50) {
            bests.recordHighest("runner.distance", (float) x);
            bests.recordHighest("runner.coins", coins);
            bests.recordHighest(BOSSES_KEY, bossesBeaten);
            saveGhostIfBest();
        }
    }

    /** A dense course sized to the rower's typical jump, harder with distance. */
    private void buildLevel() {
        java.util.Random r = new java.util.Random(1234);
        float typicalSpeed = (float) profile.typicalSpeed();
        float typicalWatts = (float) profile.typicalWatts();
        float reach = Math.max(4f, typicalSpeed * JUMP_FACTOR);
        float at = 30f;
        float nextFlag = FLAG_EVERY;
        float nextBoss = BOSS_EVERY;
        while (at < LEVEL_LENGTH) {
            if (at + 30f > nextBoss) {
                // A clear run-in, the King Crab on the kilometre, and a flag just past it.
                things.add(new Thing(Kind.BOSS, nextBoss, 0, 0));
                things.add(new Thing(Kind.FLAG, nextBoss + 12f, 0, 0));
                at = nextBoss + 25f;
                nextFlag = nextBoss + FLAG_EVERY;
                nextBoss += BOSS_EVERY;
                continue;
            }
            if (at >= nextFlag) {
                things.add(new Thing(Kind.FLAG, at, 0, 0));
                at += 6f;
                nextFlag += FLAG_EVERY;
                continue;
            }
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
        // Surges against the boss are read here, where power is current - never in onStroke.
        if (fighting && !over) {
            if (!surgeArmed && (s.watts < profile.typicalWatts() * 0.8
                    || (s.strokes != surgeStrokes && sessionSeconds - surgeAt > 1.8))) {
                surgeArmed = true;
            }
            if (surgeArmed && s.watts >= surgeWatts()) {
                surgeArmed = false;
                surgeStrokes = s.strokes;
                surgeAt = sessionSeconds;
                pendingSurges++;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && over) {
            float tx = e.getX();
            float ty = e.getY();
            if (checkpoint > 0 && continueRect.contains(tx, ty)) {
                continueRun();
                return true;
            }
            if (checkpoint <= 0 || restartRect.contains(tx, ty)) {
                start();
            }
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

    /** Power to hold for star: a quarter of the way from typical to the rower's own high watts. */
    private float starWatts() {
        return (float) Math.max(profile.typicalWatts() * 1.08, profile.wattsAt(0.75));
    }

    /** One boss hit: near the rower's high watts, eased down by every claw that lands. */
    private float surgeWatts() {
        float base = (float) Math.max(profile.typicalWatts() * 1.12, profile.wattsAt(0.85));
        return Math.max((float) profile.typicalWatts(), base * bossSurgeScale);
    }

    private boolean star() {
        return sessionSeconds < starUntil;
    }

    private static int worldAt(double metres) {
        if (metres < 0) {
            return 0;
        }
        return ((int) (metres / BOSS_EVERY)) % WORLD_NAMES.length;
    }

    /** The King Crab stands on the kilometre but guards the world it is fought in, 8 m short of it. */
    private static int bossWorld(float at) {
        return worldAt(at - BOSS_STAND);
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
        if (started && !over && !bonked && !fighting) {
            x = sessionMeters - runStart;
        } else if (started) {
            // Held in place (a bonk, the boss, game over): keep the anchor moving so the run does
            // not leap forward by the metres rowed while held.
            runStart = sessionMeters - x;
        }
        float groundY = h * 0.72f;
        float ppm = w / 40f;
        float youX = w * 0.30f;
        float metre = dp(METRE_PX_DP);

        if (started && !over) {
            runClock += dt;
            while (runClock >= nextGhostSample && ghostRecLen < MAX_GHOST) {
                ghostRec[ghostRecLen++] = (int) Math.round(x);
                nextGhostSample += 1.0;
            }
            update(dt, speed, w, groundY, youX, metre);
        }
        int world = worldAt(x);
        if (world != shownWorld) {
            shownWorld = world;
            worldBannerUntil = sessionSeconds + 2.8;
            whiteFlash = 1f;
        }
        whiteFlash = Math.max(0f, whiteFlash - dt * 1.4f);

        shake.step(dt);
        fx.step(dt, dp(300f));
        boolean airNow = airborne();
        if (wasAirborne && !airNow && started && !over) {
            fx.burst(youX, groundY, 12, dp(80f), 0.4f, dp(2.5f), world == 2 ? 0xCCFFFFFF : 0xCC8B5A2B, true);
        }
        wasAirborne = airNow;

        c.save();
        c.translate(shake.dx, shake.dy);
        drawWorld(c, w, h, groundY, ppm, youX, metre, world);
        drawGhost(c, w, groundY, ppm, youX, dt);
        legPhase += speed * dt * 10f;
        float jy = height() * metre;
        boolean hurt = sessionSeconds < hurtUntil;
        boolean starNow = star();
        float lunge = 0f;
        double lp = (sessionSeconds - heroLungeAt) / 0.35;
        if (lp >= 0 && lp < 1) {
            // A dash at the crab: nearly the whole stand-off distance, then back.
            lunge = (float) Math.sin(Math.PI * lp) * Math.max(dp(60f), BOSS_STAND * ppm - dp(130f));
        }
        if (starNow) {
            Fx.glow(c, youX + lunge, groundY - jy - dp(26f), dp(70f), 0x88FFE28A);
            if (started && !over && Math.random() < 0.6) {
                fx.spawn(youX + lunge - dp(6f), groundY - jy - (float) Math.random() * dp(50f),
                        -dp(120f), (float) (Math.random() - 0.5) * dp(60f), 0.5f, dp(3f),
                        rainbow(sessionSeconds * 3 + Math.random()), false);
            }
        } else {
            Fx.glow(c, youX, groundY - jy - dp(24f), dp(40f), 0x33FFFFFF);
        }
        if (!hurt || starNow || ((int) (sessionSeconds * 10) % 2 == 0)) {
            drawHero(c, youX + lunge, groundY - jy, speed, airNow, 0xFF, starNow);
        }
        fx.draw(c);
        c.restore();
        if (whiteFlash > 0f) {
            paint.setColor(Color.argb((int) (whiteFlash * 200), 255, 255, 255));
            c.drawRect(0, 0, w, h, paint);
        }
        Fx.speedLines(c, paint, w, h, starNow ? Math.max(speed, 4.5f) : speed, sessionSeconds, dp(1f));
        if (hurt && !starNow) {
            Fx.vignette(c, w, h, 0.7f, 0x8A1010);
        }
        drawHud(c, w, h, speed, groundY, youX, world);
    }

    private void update(float dt, float speed, float w, float groundY, float youX, float metre) {
        boolean starNow = star();
        // Star power: hold the line for a few seconds. Drains slowly, so one soft stroke is not a reset.
        if (!starNow) {
            if (starUntil > 0 && sessionSeconds >= starUntil) {
                starUntil = -1;
                say("STAR OVER");
            }
            boolean pushing = peakWatts >= starWatts();
            starCharge += pushing ? dt / STAR_CHARGE_SECONDS : -dt / 8f;
            starCharge = Math.max(0f, Math.min(1f, starCharge));
            if (starCharge >= 1f) {
                starCharge = 0f;
                starUntil = sessionSeconds + STAR_SECONDS;
                starNow = true;
                shake.kick(dp(6f));
                fx.burst(youX, groundY - dp(30f), 40, dp(220f), 0.8f, dp(4f), 0xFFFFE28A, false);
                say("INVINCIBLE!");
            }
        }

        if (fighting) {
            updateBoss(groundY, youX, w);
            return;
        }

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
            if (t.done || t.at > x + (t.kind == Kind.BOSS ? BOSS_STAND + 0.1f : 1.5f)) {
                continue;
            }
            switch (t.kind) {
                case PIT:
                    if (x >= t.at + t.size) {
                        t.done = true;
                    } else if (x >= t.at && hNow <= 0f && !starNow) {
                        fall(t);
                    }
                    break;
                case BLOCK:
                    if (x >= t.at + 1f) {
                        t.done = true;
                        coins++;
                    } else if (x >= t.at - 0.3f && hNow < t.size) {
                        t.done = true;
                        fx.burst(youX + dp(10f), groundY - t.size * metre / 2f, 24, dp(150f), 0.6f, dp(4f),
                                CRATE[worldAt(t.at)], true);
                        if (starNow) {
                            coins++;
                            say("SMASH!");
                        } else {
                            hit("CRATE!");
                        }
                    }
                    break;
                case WALL:
                    if (x >= t.at) {
                        if (peakWatts >= t.size || starNow) {
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
                case FLAG:
                    if (x >= t.at) {
                        t.done = true;
                        t.raisedAt = sessionSeconds;
                        checkpoint = t.at;
                        checkpointCoins = coins;
                        checkpointStomps = stomps;
                        checkpointBosses = bossesBeaten;
                        fx.burst(youX, groundY - dp(80f), 20, dp(120f), 0.6f, dp(3f), ACCENT, true);
                        say("CHECKPOINT " + Math.round(t.at) + " m");
                    }
                    break;
                case BOSS:
                    if (x >= t.at - BOSS_STAND) {
                        startFight(t);
                        return;
                    }
                    break;
                default:   // enemies
                    if (Math.abs((float) x - t.at) < 0.6f) {
                        t.done = true;
                        if (hNow > 0.7f || starNow) {
                            coins += 2;
                            stomps++;
                            fx.burst(youX, groundY - dp(20f), 18, dp(130f), 0.5f, dp(3.5f),
                                    enemyColour(t.kind, worldAt(t.at)), true);
                            say(stomps % 5 == 0 ? stomps + " STOMPS!" : starNow ? "BOP!" : "STOMP");
                        } else {
                            hit("OUCH!");
                        }
                    }
                    break;
            }
        }
    }

    /* ---------- the King Crab ---------- */

    private void startFight(Thing t) {
        fighting = true;
        boss = t;
        int km = Math.max(1, Math.round(t.at / BOSS_EVERY));
        bossMaxHp = Math.min(8, 3 + km);
        bossHp = bossMaxHp;
        bossSurgeScale = 1f;
        windupStart = -1;
        nextWindupAt = sessionSeconds + 3.0;
        pendingSurges = 0;
        surgeArmed = true;
        surgeStrokes = -1;
        x = t.at - BOSS_STAND;
        runStart = sessionMeters - x;
        jumpFrom = -1;
        // Small fry scatter when the king arrives, so nothing walks into a runner held in place.
        for (Thing e : things) {
            if (e.isEnemy() && !e.done && e.at < t.at + 2f) {
                e.done = true;
            }
        }
        shake.kick(dp(10f));
        say("KING CRAB!  SURGE TO HIT");
    }

    private void updateBoss(float groundY, float youX, float w) {
        double now = sessionSeconds;
        float bossX = youX + BOSS_STAND * (w / 40f);
        while (pendingSurges > 0 && fighting) {
            pendingSurges--;
            boolean counter = windupStart > 0;
            int dmg = (counter ? 2 : 1) + (star() ? 1 : 0);
            bossHp -= dmg;
            bossHitAt = now;
            heroLungeAt = now;
            shake.kick(dp(counter ? 14f : 8f));
            fx.burst(bossX - dp(40f), groundY - dp(70f), counter ? 40 : 22, dp(200f), 0.6f, dp(4f),
                    BOSS_BODY[bossWorld(boss.at)], true);
            fx.burst(bossX - dp(40f), groundY - dp(70f), 10, dp(160f), 0.4f, dp(3f), 0xFFFFFFFF, false);
            if (counter) {
                windupStart = -1;
                nextWindupAt = now + BOSS_REST;
                say("COUNTER!  x" + dmg);
            } else {
                say("HIT!  x" + dmg);
            }
            if (bossHp <= 0) {
                defeatBoss(groundY, bossX);
            }
        }
        if (!fighting) {
            return;
        }
        if (windupStart > 0 && now >= windupStart + WINDUP) {
            windupStart = -1;
            bossStruckAt = now;
            nextWindupAt = now + BOSS_REST;
            if (star()) {
                shake.kick(dp(6f));
                fx.burst(youX, groundY - dp(30f), 16, dp(140f), 0.4f, dp(3f), 0xFFFFE28A, false);
                say("BLOCKED!");
            } else {
                // Each claw that lands tires the crab: the surge line eases toward typical.
                bossSurgeScale = Math.max(0.8f, bossSurgeScale * 0.95f);
                hit("CLAW!  SURGE WHEN IT GLOWS");
            }
        } else if (windupStart < 0 && now >= nextWindupAt) {
            windupStart = now;
        }
    }

    private void defeatBoss(float groundY, float bossX) {
        int km = Math.max(1, Math.round(boss.at / BOSS_EVERY));
        int prize = 10 * km;
        coins += prize;
        bossesBeaten++;
        boss.done = true;
        bossDefeatedAt = sessionSeconds;
        bossDefeatedAtMetres = boss.at;
        fighting = false;
        windupStart = -1;
        pendingSurges = 0;
        shake.kick(dp(18f));
        fx.burst(bossX, groundY - dp(70f), 70, dp(300f), 1.0f, dp(5f), 0xFFF5C518, true);
        fx.burst(bossX, groundY - dp(70f), 40, dp(260f), 0.9f, dp(4f), BOSS_BODY[bossWorld(boss.at)], true);
        say("BOSS DOWN!  +" + prize);
        boss = null;
    }

    private void hit(String what) {
        if (sessionSeconds < hurtUntil || star()) {
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
            fighting = false;
            starUntil = -1;
            bests.recordHighest("runner.distance", (float) x);
            bests.recordHighest("runner.coins", coins);
            bests.recordHighest(BOSSES_KEY, bossesBeaten);
            saveGhostIfBest();
        }
    }

    /** Back to the last flag: hearts refilled, coins and stomps as they were when it was raised. */
    private void continueRun() {
        things.clear();
        buildLevel();
        for (Thing t : things) {
            if (t.at <= checkpoint + 0.01f) {
                t.done = true;
                if (t.kind == Kind.FLAG) {
                    t.raisedAt = sessionSeconds - 10;
                }
            }
        }
        over = false;
        fighting = false;
        boss = null;
        pendingSurges = 0;
        hearts = HEARTS;
        coins = checkpointCoins;
        stomps = checkpointStomps;
        bossesBeaten = checkpointBosses;
        x = checkpoint;
        runStart = sessionMeters - x;
        jumpFrom = -1;
        starCharge = 0;
        starUntil = -1;
        bonkUntil = 0;
        hurtUntil = sessionSeconds + 1.5;
        shownWorld = worldAt(x);
        continues++;
        say("FROM THE " + Math.round(checkpoint) + " m FLAG");
    }

    /* ---------- ghost ---------- */

    private void loadGhost() {
        ghost = null;
        ghostEnd = 0;
        storedBest = 0;
        String s = bests.getString(GHOST_KEY);
        if (s == null || s.isEmpty()) {
            return;
        }
        try {
            String[] p = s.split(",");
            int[] g = new int[p.length];
            for (int i = 0; i < p.length; i++) {
                g[i] = Integer.parseInt(p[i].trim());
            }
            if (g.length >= 2) {
                ghost = g;
                for (int v : g) {
                    ghostEnd = Math.max(ghostEnd, v);
                }
                storedBest = ghostEnd;
            }
        } catch (NumberFormatException ignored) {
            ghost = null;
        }
    }

    /** Stores this run as the ghost if it went further. The replayed ghost changes on the next start. */
    private void saveGhostIfBest() {
        if (ghostRecLen < 2 || x <= storedBest + 0.5f) {
            return;
        }
        StringBuilder sb = new StringBuilder(ghostRecLen * 5);
        for (int i = 0; i < ghostRecLen; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ghostRec[i]);
        }
        if (ghostRecLen < MAX_GHOST) {
            sb.append(',').append((int) Math.round(x));
        }
        bests.putString(GHOST_KEY, sb.toString());
        storedBest = (float) x;
        newBestRun = true;
    }

    /** Where the best run was at this point on the run clock. */
    private float ghostAt(double t) {
        if (ghost == null) {
            return -1f;
        }
        int i = (int) Math.floor(t);
        if (i >= ghost.length - 1) {
            return ghost[ghost.length - 1];
        }
        if (i < 0) {
            return ghost[0];
        }
        float f = (float) (t - i);
        return ghost[i] + (ghost[i + 1] - ghost[i]) * f;
    }

    private void drawGhost(Canvas c, float w, float groundY, float ppm, float youX, float dt) {
        if (ghost == null) {
            return;
        }
        // The post where the best run ended.
        float px = youX + (ghostEnd - (float) x) * ppm;
        if (px > -dp(40f) && px < w + dp(40f)) {
            paint.setColor(0xCCFFFFFF);
            c.drawRect(px - dp(2f), groundY - dp(110f), px + dp(2f), groundY, paint);
            paint.setColor(0xCC35D0BA);
            c.drawRect(px + dp(2f), groundY - dp(110f), px + dp(46f), groundY - dp(88f), paint);
            bold(c, "BEST", px + dp(24f), groundY - dp(94f), 9f, 0xFF10171F, Paint.Align.CENTER);
        }
        if (!started) {
            return;
        }
        float gx = ghostAt(runClock);
        float gSpeed = Math.max(0f, gx - ghostAt(runClock - 1.0));
        ghostLegPhase += gSpeed * dt * 10f;
        float sx = youX + (gx - (float) x) * ppm;
        if (sx > -dp(20f) && sx < w + dp(20f)) {
            float keep = legPhase;
            legPhase = ghostLegPhase;
            drawHero(c, sx, groundY, gSpeed, false, 0x70, false);
            legPhase = keep;
            label(c, "BEST", sx, groundY - dp(58f), 8f, 0xAAFFFFFF, Paint.Align.CENTER);
        }
    }

    private static int enemyColour(Kind k, int world) {
        return k == Kind.CRAB ? CRAB_COL[world] : k == Kind.SLIME ? SLIME_COL[world] : SPIKY_COL[world];
    }

    private int rainbow(double t) {
        hsv[0] = (float) ((t * 120.0) % 360.0);
        hsv[1] = 0.65f;
        hsv[2] = 1f;
        return Color.HSVToColor(hsv);
    }

    private static int withAlpha(int colour, int alpha) {
        return (colour & 0x00FFFFFF) | (alpha << 24);
    }

    /* ---------- drawing ---------- */

    /** The world is drawn through the screen shake, so everything full-width bleeds past the edges. */
    private static final float OVERSCAN = 26f;

    private void drawWorld(Canvas c, float w, float h, float groundY, float ppm, float youX, float metre, int world) {
        float camX = (float) x;
        if (skyShader == null || skyWorld != world || skyGround != groundY) {
            skyShader = new android.graphics.LinearGradient(0, 0, 0, groundY, SKY_TOP[world], SKY_BOT[world],
                    android.graphics.Shader.TileMode.CLAMP);
            skyWorld = world;
            skyGround = groundY;
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(-dp(OVERSCAN), -dp(OVERSCAN), w + dp(OVERSCAN), groundY, paint);
        paint.setShader(null);
        drawSun(c, w, h, world);
        drawPixelSky(c, w, h, groundY, ppm, camX, world);
        paint.setColor(HILL[world]);
        for (int i = 0; i < 6; i++) {
            float hx = ((i * 260f * dp(1f)) - (camX * ppm * 0.12f)) % (w + dp(300f));
            if (hx < -dp(150f)) {
                hx += w + dp(300f);
            }
            c.drawCircle(hx, groundY + dp(30f), dp(90f) + (i % 3) * dp(25f), paint);
        }
        paint.setColor(CLOUD[world]);
        for (int i = 0; i < 4; i++) {
            float cx = ((i * 231f + 60f) - (camX * ppm * 0.2f)) % (w + dp(120f));
            if (cx < 0) {
                cx += w + dp(120f);
            }
            float cy = h * 0.12f + (i % 2) * dp(40f);
            c.drawRect(cx - dp(30f), cy, cx + dp(30f), cy + dp(14f), paint);
            c.drawRect(cx - dp(16f), cy - dp(10f), cx + dp(16f), cy + dp(14f), paint);
        }
        drawProps(c, w, groundY, ppm, camX, world);
        drawWeather(c, w, groundY, world);
        // 3.19.6: underground was a third of the tablet screen and empty. Soil layers, buried rocks,
        // roots and the odd fossil scroll past; grass tufts run along the surface in front.
        drawUnderground(c, w, h, groundY, ppm, camX, world);

        for (Thing t : things) {
            float sx = youX + (t.at - camX) * ppm;
            if (sx < -dp(240f) || sx > w + dp(160f)) {
                continue;
            }
            int tw = worldAt(t.at);
            switch (t.kind) {
                case PIT: {
                    // Clamped to the screen: the loop keeps things up to 240dp off the left edge, and
                    // a pit straddling the viewport painted its near-black fill as a bar at the edge.
                    float ex = sx + t.size * ppm;
                    float l = Math.max(0f, sx);
                    float r = Math.min(w, ex);
                    paint.setColor(PIT_FILL[tw]);
                    c.drawRect(l, groundY, r, h, paint);
                    if (tw == 3 && r > l) {
                        // Lava: a bright crust and bubbles that rise and pop.
                        paint.setColor(0xFFFF8A2A);
                        c.drawRect(l, groundY + dp(6f), r, groundY + dp(18f), paint);
                        paint.setColor(0xFFFFD27A);
                        for (int b = 0; b < 3; b++) {
                            double ph = (sessionSeconds * 0.8 + b * 0.37 + t.at * 0.1) % 1.0;
                            float bx = sx + (b + 0.5f) / 3f * t.size * ppm;
                            if (bx > l && bx < r) {
                                c.drawCircle(bx, groundY + dp(40f) - (float) ph * dp(30f), dp(3f + 3f * (float) ph), paint);
                            }
                        }
                    } else if (tw == 2 && r > l) {
                        paint.setColor(0xFF3A5A7A);
                        c.drawRect(l, groundY, r, groundY + dp(6f), paint);
                    }
                    break;
                }
                case BLOCK: {
                    if (t.done) {
                        break;
                    }
                    for (int k = 0; k < (int) t.size; k++) {
                        float top = groundY - (k + 1) * metre;
                        paint.setColor(CRATE[tw]);
                        c.drawRect(sx, top, sx + metre, top + metre - dp(1f), paint);
                        paint.setColor(CRATE_LINE[tw]);
                        paint.setStrokeWidth(dp(3f));
                        if (tw == 0) {
                            c.drawLine(sx + dp(3f), top + dp(3f), sx + metre - dp(3f), top + metre - dp(4f), paint);
                            c.drawLine(sx + metre - dp(3f), top + dp(3f), sx + dp(3f), top + metre - dp(4f), paint);
                        } else {
                            // Sandstone, ice and castle stone: a block with a bevel instead of a crate cross.
                            c.drawLine(sx + dp(2f), top + metre - dp(3f), sx + metre - dp(2f), top + metre - dp(3f), paint);
                            c.drawLine(sx + metre - dp(3f), top + dp(2f), sx + metre - dp(3f), top + metre - dp(3f), paint);
                            paint.setColor(0x55FFFFFF);
                            c.drawRect(sx + dp(3f), top + dp(3f), sx + metre * 0.45f, top + dp(7f), paint);
                        }
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
                            peakWatts >= t.size || star() ? ACCENT : TEXT, Paint.Align.CENTER);
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
                case FLAG:
                    drawFlag(c, t, sx, groundY);
                    break;
                case BOSS:
                    if (!t.done) {
                        drawBoss(c, sx, groundY, bossWorld(t.at), false);
                    }
                    break;
                default:
                    if (!t.done) {
                        drawEnemy(c, t, sx, groundY, tw);
                    }
                    break;
            }
        }
        // A beaten King Crab flips over and falls out of the world.
        double since = sessionSeconds - bossDefeatedAt;
        if (since >= 0 && since < 1.6) {
            float sx = youX + (bossDefeatedAtMetres - camX) * ppm;
            c.save();
            c.translate(sx + (float) since * dp(160f), (float) (since * since) * dp(500f) - (float) since * dp(200f));
            c.rotate((float) since * 220f, 0, groundY - dp(60f));
            drawBoss(c, 0, groundY, bossWorld(bossDefeatedAtMetres), true);
            c.restore();
        }
    }

    private void drawSun(Canvas c, float w, float h, int world) {
        float sx = w * 0.85f;
        float sy = h * 0.14f;
        if (world == 3) {
            Fx.glow(c, sx, sy, dp(70f), 0x44D8D0FF);
            paint.setColor(0xFFE8E4F0);
            c.drawCircle(sx, sy, dp(26f), paint);
            paint.setColor(0xFFC8C2D8);
            c.drawCircle(sx - dp(8f), sy - dp(6f), dp(6f), paint);
            c.drawCircle(sx + dp(9f), sy + dp(8f), dp(4f), paint);
            return;
        }
        int glow = world == 1 ? 0x88FFD27A : world == 2 ? 0x55FFFFFF : 0x66FFE28A;
        Fx.glow(c, sx, sy, dp(world == 1 ? 90f : 60f), glow);
        paint.setColor(world == 1 ? 0xFFFFD27A : world == 2 ? 0xFFFFF4D6 : 0xFFFFE28A);
        c.drawCircle(sx, sy, dp(world == 1 ? 30f : 22f), paint);
    }

    /**
     * 3.19.5: the sky was two-thirds of the screen and nearly empty. In the same blocky style as the
     * clouds: far mountains with snow caps, a landmark on the horizon now and then (a castle, a
     * pyramid in the desert), pixel birds, and an airship drifting the other way. All background.
     */
    private void drawPixelSky(Canvas c, float w, float h, float groundY, float ppm, float camX, int world) {
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
                paint.setColor(MOUNT[world]);
                c.drawRect(mx - half, base - step - px * 2, mx + half, base - step, paint);
            }
            paint.setColor(CAP[world]);
            for (float step = peakH - dp(36f); step < peakH; step += px * 2) {
                float half = (peakH - step) * 0.9f;
                c.drawRect(mx - half, base - step - px * 2, mx + half, base - step, paint);
            }
        }
        // A landmark on the horizon every so often.
        float cSpan = w * 1.6f;
        float cx = (float) (((w * 0.7f - camX * ppm * 0.08f) % cSpan + cSpan) % cSpan) - dp(100f);
        float cBase = groundY - dp(60f);
        if (world == 1) {
            // Stepped pyramid.
            for (int s = 0; s < 8; s++) {
                float half = dp(110f) - s * dp(13f);
                paint.setColor(s % 2 == 0 ? 0xFFD9A55B : 0xFFC9954B);
                c.drawRect(cx - half, cBase + dp(40f) - (s + 1) * dp(16f), cx + half, cBase + dp(40f) - s * dp(16f), paint);
            }
            paint.setColor(0xFF6E4A22);
            c.drawRect(cx - dp(8f), cBase + dp(18f), cx + dp(8f), cBase + dp(40f), paint);
        } else {
            int stone = world == 0 ? 0xFF5F7FA6 : world == 2 ? 0xFFB8D4EE : 0xFF221A36;
            float big = world == 3 ? 1.5f : 1f;
            paint.setColor(stone);
            c.drawRect(cx - dp(48f) * big, cBase - dp(48f) * big, cx + dp(48f) * big, cBase + dp(40f), paint);
            c.drawRect(cx - dp(66f) * big, cBase - dp(84f) * big, cx - dp(38f) * big, cBase + dp(40f), paint);
            c.drawRect(cx + dp(38f) * big, cBase - dp(84f) * big, cx + dp(66f) * big, cBase + dp(40f), paint);
            for (int b = 0; b < 3; b++) {
                float bw = dp(10f) * big;
                c.drawRect(cx - dp(66f) * big + b * bw, cBase - dp(96f) * big, cx - dp(66f) * big + b * bw + bw * 0.6f,
                        cBase - dp(84f) * big, paint);
                c.drawRect(cx + dp(38f) * big + b * bw, cBase - dp(96f) * big, cx + dp(38f) * big + b * bw + bw * 0.6f,
                        cBase - dp(84f) * big, paint);
            }
            paint.setColor(0xFFE0582E);
            c.drawRect(cx - dp(53f) * big, cBase - dp(120f) * big, cx - dp(51f) * big, cBase - dp(96f) * big, paint);
            c.drawRect(cx - dp(51f) * big, cBase - dp(120f) * big, cx - dp(37f) * big + (float) Math.sin(t * 5) * dp(2f),
                    cBase - dp(110f) * big, paint);
            paint.setColor(world == 3 ? 0xFF0A0612 : 0xFF2E4466);
            c.drawRect(cx - dp(10f), cBase + dp(10f), cx + dp(10f), cBase + dp(40f), paint);
            if (world == 3) {
                // Lit windows that flicker.
                for (int i = 0; i < 6; i++) {
                    boolean lit = ((int) (t * 2 + i * 1.7)) % 5 != 0;
                    paint.setColor(lit ? 0xFFFFC04A : 0xFF3A2A10);
                    float wx = cx - dp(40f) + (i % 3) * dp(34f);
                    float wy = cBase - dp(50f) + (i / 3) * dp(30f);
                    c.drawRect(wx, wy, wx + dp(8f), wy + dp(12f), paint);
                }
            }
        }
        // Pixel birds (vultures in the desert, bats at the castle): two-block wings that flap.
        paint.setColor(BIRD[world]);
        float fx0 = (float) (w - ((t * dp(world == 3 ? 70f : 45f)) % (w + dp(300f))));
        for (int b = 0; b < 4; b++) {
            float bx = fx0 + b * dp(28f);
            float by = h * 0.26f + (b % 2) * dp(14f) + (world == 3 ? (float) Math.sin(t * 7 + b) * dp(8f) : 0f);
            boolean up = ((int) (t * (world == 3 ? 12 : 6) + b)) % 2 == 0;
            c.drawRect(bx - px * 2, by + (up ? -px : 0), bx - px, by + (up ? 0 : px), paint);
            c.drawRect(bx - px, by, bx + px, by + px, paint);
            c.drawRect(bx + px, by + (up ? -px : 0), bx + px * 2, by + (up ? 0 : px), paint);
        }
        if (world == 3) {
            return;   // no airships over the castle - the moon has the sky
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

    /** Mid-distance props per world: cacti, snowy pines, castle torches. The meadow keeps its hills. */
    private void drawProps(Canvas c, float w, float groundY, float ppm, float camX, int world) {
        if (world == 0) {
            return;
        }
        float px = dp(6f);
        float span = dp(230f);
        float scroll = camX * ppm * 0.5f;
        float o = scroll % span;
        for (float sx = -o - span; sx < w + span; sx += span) {
            int k = (int) Math.floor((sx + scroll) / span + 0.5f);
            float jitter = (Math.abs(k * 53) % 5) * dp(20f);
            float bx = sx + jitter;
            if (world == 1) {
                float tall = dp(50f) + (Math.abs(k * 31) % 3) * dp(18f);
                paint.setColor(0xFF4F8A3A);
                c.drawRect(bx - px, groundY - tall, bx + px, groundY, paint);
                c.drawRect(bx - px * 4, groundY - tall * 0.6f, bx - px, groundY - tall * 0.6f + px * 1.5f, paint);
                c.drawRect(bx - px * 4, groundY - tall * 0.85f, bx - px * 2.5f, groundY - tall * 0.6f, paint);
                c.drawRect(bx + px, groundY - tall * 0.45f, bx + px * 4, groundY - tall * 0.45f + px * 1.5f, paint);
                c.drawRect(bx + px * 2.5f, groundY - tall * 0.7f, bx + px * 4, groundY - tall * 0.45f, paint);
            } else if (world == 2) {
                float tall = dp(80f) + (Math.abs(k * 31) % 3) * dp(24f);
                paint.setColor(0xFF5A3E26);
                c.drawRect(bx - px * 0.8f, groundY - px * 3, bx + px * 0.8f, groundY, paint);
                for (int tier = 0; tier < 4; tier++) {
                    float half = (4 - tier) * px * 1.6f;
                    float top = groundY - px * 3 - (tier + 1) * tall / 4.5f;
                    paint.setColor(0xFF2F6B4A);
                    c.drawRect(bx - half, top, bx + half, top + tall / 4.5f, paint);
                    paint.setColor(0xFFF7FBFF);
                    c.drawRect(bx - half, top, bx + half, top + px * 0.8f, paint);
                }
            } else {
                // Torch on a post: the flame flickers, the glow breathes.
                paint.setColor(0xFF3A3040);
                c.drawRect(bx - px * 0.6f, groundY - dp(70f), bx + px * 0.6f, groundY, paint);
                float flick = (float) Math.sin(sessionSeconds * 13 + k * 2.1) * 0.5f + 0.5f;
                Fx.glow(c, bx, groundY - dp(78f), dp(46f), flick > 0.5f ? 0x66FF9A2A : 0x44FF9A2A);
                paint.setColor(0xFFFF7A1A);
                c.drawRect(bx - px, groundY - dp(84f) - flick * px, bx + px, groundY - dp(70f), paint);
                paint.setColor(0xFFFFE28A);
                c.drawRect(bx - px * 0.4f, groundY - dp(80f), bx + px * 0.4f, groundY - dp(72f), paint);
            }
        }
    }

    /** Falling snow in the snowfields, rising embers at the castle. Positions are from the clock. */
    private void drawWeather(Canvas c, float w, float groundY, int world) {
        if (world != 2 && world != 3) {
            return;
        }
        double t = sessionSeconds;
        for (int i = 0; i < 48; i++) {
            float speed = 1f + (i % 3) * 0.45f;
            float fxp = (float) (((i * 97.31 + Math.sin(t * 0.9 + i) * 18) + t * dp(world == 2 ? 25f : 10f) * speed)
                    % (w + dp(20f)));
            float fall = (float) ((i * 53.7 + t * dp(world == 2 ? 55f : 40f) * speed) % groundY);
            float fy = world == 2 ? fall : groundY - fall;
            paint.setColor(world == 2 ? 0xDDFFFFFF : ((i & 1) == 0 ? 0xCCFF8A2A : 0xAAFFD27A));
            c.drawCircle(fxp, fy, dp(world == 2 ? 1.5f + (i % 3) : 1.2f + (i % 2)), paint);
        }
    }

    /** Soil bands, rocks, roots and grass tufts - the ground is no longer a brown slab. */
    private void drawUnderground(Canvas c, float w, float h, float groundY, float ppm, float camX, int world) {
        float px = dp(6f);
        // Soil bands, each a slightly different shade, with a pebbly seam.
        int bands = 4;
        for (int b = 0; b < bands; b++) {
            float y0 = groundY + dp(14f) + (h - groundY - dp(14f)) * b / bands;
            float y1 = groundY + dp(14f) + (h - groundY - dp(14f)) * (b + 1) / bands;
            paint.setColor(b % 2 == 0 ? SOIL_A[world] : SOIL_B[world]);
            c.drawRect(-dp(OVERSCAN), y0, w + dp(OVERSCAN), y1 + dp(1f), paint);
            paint.setColor(0x33000000);
            c.drawRect(-dp(OVERSCAN), y0, w + dp(OVERSCAN), y0 + px * 0.5f, paint);
        }
        float scroll = camX * ppm;
        if (world == 3) {
            // Castle floor: staggered brick courses.
            paint.setColor(0x55000000);
            float course = dp(24f);
            float brick = dp(48f);
            int row = 0;
            for (float y = groundY + dp(14f); y < h; y += course, row++) {
                c.drawRect(-dp(OVERSCAN), y, w + dp(OVERSCAN), y + dp(2f), paint);
                float o = (scroll + (row % 2) * brick / 2f) % brick;
                for (float bx = -o; bx < w; bx += brick) {
                    c.drawRect(bx, y, bx + dp(2f), y + course, paint);
                }
            }
        }
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
                if (world == 3) {
                    continue;   // no roots through castle stone
                }
                // Root reaching down from the surface.
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
        // Tufts along the surface, scrolling at full speed in front of the runner.
        float tuft = dp(26f);
        float to = scroll % tuft;
        paint.setColor(TOP[world]);
        c.drawRect(-dp(OVERSCAN), groundY, w + dp(OVERSCAN), groundY + dp(14f), paint);
        for (float x = -to - tuft; x < w + tuft; x += tuft) {
            int k = (int) Math.floor((x + scroll) / tuft + 0.5f);
            float tall = dp(6f) + (Math.abs(k * 17) % 3) * dp(4f);
            paint.setColor((k & 1) == 0 ? TUFT_A[world] : TUFT_B[world]);
            if (world == 2) {
                // Snow drifts: low mounds rather than blades.
                c.drawRect(x, groundY - tall * 0.5f, x + px * 2f, groundY, paint);
            } else if (world == 3) {
                // Battlement-style flagstones.
                c.drawRect(x, groundY - px * 0.6f, x + px * 2.5f, groundY, paint);
            } else {
                c.drawRect(x, groundY - tall, x + px * 0.6f, groundY, paint);
                c.drawRect(x + px, groundY - tall * 0.6f, x + px * 1.6f, groundY, paint);
            }
        }
    }

    private void drawFlag(Canvas c, Thing t, float sx, float groundY) {
        float pole = dp(100f);
        paint.setColor(0xFFDDDDDD);
        c.drawRect(sx - dp(2f), groundY - pole, sx + dp(2f), groundY, paint);
        paint.setColor(0xFFF5C518);
        c.drawCircle(sx, groundY - pole, dp(4f), paint);
        // Down at the foot of the pole until reached, then run up and wave.
        float up = 0f;
        if (t.raisedAt >= 0) {
            up = (float) Math.min(1.0, (sessionSeconds - t.raisedAt) / 0.6);
        }
        float top = groundY - dp(28f) - up * (pole - dp(34f));
        float wave = (float) Math.sin(sessionSeconds * 8 + t.at) * dp(3f) * (0.3f + up);
        path.reset();
        path.moveTo(sx + dp(2f), top);
        path.lineTo(sx + dp(38f), top + dp(10f) + wave);
        path.lineTo(sx + dp(2f), top + dp(22f));
        path.close();
        paint.setColor(t.raisedAt >= 0 ? ACCENT : 0xFFB0B8C4);
        c.drawPath(path, paint);
        if (t.raisedAt >= 0 && sessionSeconds - t.raisedAt < 0.8) {
            Fx.glow(c, sx + dp(14f), top + dp(10f), dp(40f), 0x6635D0BA);
        }
        label(c, Math.round(t.at) + " m", sx, groundY + dp(28f), 8f, 0xCCFFFFFF, Paint.Align.CENTER);
    }

    /** The King Crab, facing left at the runner. {@code dead} draws it plainly for the fall-away. */
    private void drawBoss(Canvas c, float sx, float groundY, int world, boolean dead) {
        float s = dp(1f);
        double now = sessionSeconds;
        boolean active = fighting && !dead;
        float bob = (float) Math.sin(now * 3) * 4f * s;
        double sinceHit = now - bossHitAt;
        float knock = active && sinceHit < 0.3 ? (float) (1 - sinceHit / 0.3) * 24f * s : 0f;
        float cx = sx + knock;
        float cy = groundY - 62f * s + bob;
        boolean white = active && sinceHit < 0.12;
        int body = white ? 0xFFFFFFFF : BOSS_BODY[world];
        int dark = white ? 0xFFFFFFFF : BOSS_DARK[world];

        // Legs, scuttling.
        paint.setColor(dark);
        paint.setStrokeWidth(6f * s);
        for (int i = 0; i < 3; i++) {
            float ph = (float) Math.sin(now * 8 + i * 1.3) * 6f * s;
            float lx = cx - 30f * s + i * 30f * s;
            c.drawLine(lx, cy + 20f * s, lx - 22f * s, groundY + ph * 0.3f, paint);
            c.drawLine(lx + 10f * s, cy + 20f * s, lx + 34f * s, groundY - ph * 0.3f, paint);
        }
        // Back claw.
        paint.setColor(body);
        c.drawCircle(cx + 78f * s, cy - 6f * s, 18f * s, paint);
        // Shell.
        c.drawOval(cx - 75f * s, cy - 40f * s, cx + 75f * s, cy + 36f * s, paint);
        paint.setColor(dark);
        c.drawOval(cx - 60f * s, cy + 6f * s, cx + 60f * s, cy + 30f * s, paint);
        paint.setColor(0x33FFFFFF);
        c.drawOval(cx - 40f * s, cy - 34f * s, cx + 10f * s, cy - 18f * s, paint);

        // Eyes on stalks, with angry brows.
        paint.setColor(dark);
        paint.setStrokeWidth(5f * s);
        c.drawLine(cx - 30f * s, cy - 36f * s, cx - 36f * s, cy - 62f * s, paint);
        c.drawLine(cx - 4f * s, cy - 36f * s, cx - 2f * s, cy - 62f * s, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(cx - 36f * s, cy - 66f * s, 9f * s, paint);
        c.drawCircle(cx - 2f * s, cy - 66f * s, 9f * s, paint);
        paint.setColor(0xFF10171F);
        c.drawCircle(cx - 40f * s, cy - 65f * s, 4f * s, paint);
        c.drawCircle(cx - 6f * s, cy - 65f * s, 4f * s, paint);
        paint.setStrokeWidth(4f * s);
        c.drawLine(cx - 46f * s, cy - 80f * s, cx - 28f * s, cy - 74f * s, paint);
        c.drawLine(cx - 12f * s, cy - 74f * s, cx + 6f * s, cy - 80f * s, paint);

        // Crown.
        paint.setColor(0xFFF5C518);
        c.drawRect(cx - 34f * s, cy - 104f * s, cx + 4f * s, cy - 92f * s, paint);
        for (int i = 0; i < 3; i++) {
            float tx = cx - 34f * s + i * 15f * s;
            path.reset();
            path.moveTo(tx, cy - 104f * s);
            path.lineTo(tx + 4f * s, cy - 118f * s);
            path.lineTo(tx + 8f * s, cy - 104f * s);
            path.close();
            c.drawPath(path, paint);
        }

        // The big claw: resting, raised and glowing during the wind-up, slammed down on a strike.
        float clawX = cx - 100f * s;
        float clawY = cy - 4f * s;
        boolean winding = active && windupStart > 0;
        double sinceStrike = now - bossStruckAt;
        if (winding) {
            float p = (float) Math.min(1.0, (now - windupStart) / WINDUP);
            clawX = cx - 80f * s;
            clawY = cy - 30f * s - p * 70f * s + (float) Math.sin(now * 30) * 2f * s * p;
            Fx.glow(c, clawX, clawY, 70f * s, p > 0.66f ? 0xAAFF3A2A : 0x66FF3A2A);
        } else if (active && sinceStrike < 0.35) {
            // Slams down on the runner, who stands BOSS_STAND metres in front.
            clawX = cx - BOSS_STAND * (getWidth() / 40f) + 30f * s;
            clawY = groundY - 22f * s;
        }
        paint.setColor(dark);
        paint.setStrokeWidth(12f * s);
        c.drawLine(cx - 60f * s, cy, clawX + 12f * s, clawY, paint);
        paint.setColor(winding ? 0xFFFF5A3A : body);
        c.drawCircle(clawX, clawY, 28f * s, paint);
        float open = (float) (Math.sin(now * 6) * 0.5 + 0.5) * 10f * s;
        paint.setColor(SKY_BOT[world]);
        path.reset();
        path.moveTo(clawX - 30f * s, clawY - 4f * s - open);
        path.lineTo(clawX - 4f * s, clawY);
        path.lineTo(clawX - 30f * s, clawY + 4f * s + open);
        path.close();
        c.drawPath(path, paint);

        if (winding) {
            bold(c, "!", clawX, clawY - 40f * s, 30f, BAD, Paint.Align.CENTER);
        }
        if (!active && !dead) {
            label(c, "KING CRAB", cx, cy - 126f * s, 10f, 0xDDFFFFFF, Paint.Align.CENTER);
        }
    }

    private void drawEnemy(Canvas c, Thing t, float sx, float groundY, int world) {
        float wobble = (float) Math.sin(sessionSeconds * 12 + t.at);
        switch (t.kind) {
            case CRAB: {
                paint.setColor(CRAB_COL[world]);
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
                paint.setColor(SLIME_COL[world]);
                c.drawOval(sx - dp(15f) * squash, groundY - dp(22f) / squash, sx + dp(15f) * squash, groundY, paint);
                paint.setColor(0x66FFFFFF);
                c.drawCircle(sx - dp(6f), groundY - dp(15f) / squash, dp(3f), paint);
                eyes(c, sx, groundY - dp(12f) / squash);
                break;
            }
            default: {
                float r = dp(13f);
                float cy = groundY - r;
                paint.setColor(SPIKY_COL[world]);
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

    /** The runner. {@code alpha} below 0xFF draws the ghost; {@code star} cycles the colours. */
    private void drawHero(Canvas c, float x0, float feetY, float speed, boolean inAir, int alpha, boolean star) {
        float s = dp(1f);
        float swing = inAir ? 6 * s : (speed > 0.2f ? (float) Math.sin(legPhase) * 7f * s : 0f);
        int legs = star ? rainbow(sessionSeconds * 3 + 0.5) : 0xFF2E5BBA;
        int shirt = star ? rainbow(sessionSeconds * 3) : 0xFFE84C3D;
        if (alpha < 0xFF) {
            legs = 0xFFCFE3FF;
            shirt = 0xFFCFE3FF;
        }
        paint.setColor(withAlpha(legs, alpha));
        c.drawRect(x0 - 6 * s + swing, feetY - 16 * s, x0 - 1 * s + swing, feetY, paint);
        c.drawRect(x0 + 1 * s - swing, feetY - 16 * s, x0 + 6 * s - swing, feetY, paint);
        paint.setColor(withAlpha(shirt, alpha));
        c.drawRect(x0 - 8 * s, feetY - 36 * s, x0 + 8 * s, feetY - 16 * s, paint);
        paint.setColor(withAlpha(alpha < 0xFF ? 0xFFEAF2FF : 0xFFF1C27D, alpha));
        c.drawRect(x0 - 6 * s, feetY - 48 * s, x0 + 6 * s, feetY - 36 * s, paint);
        paint.setColor(withAlpha(shirt, alpha));
        c.drawRect(x0 - 7 * s, feetY - 52 * s, x0 + 9 * s, feetY - 46 * s, paint);
    }

    private void drawHud(Canvas c, float w, float h, float speed, float groundY, float youX, int world) {
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
        // Star meter under the hearts.
        float mx = dp(8f);
        float my = dp(44f);
        float mw = dp(170f);
        paint.setColor(0x44000000);
        c.drawRect(mx, my, mx + mw, my + dp(10f), paint);
        boolean starNow = star();
        float fill = starNow ? (float) ((starUntil - sessionSeconds) / STAR_SECONDS) : starCharge;
        paint.setColor(starNow ? rainbow(sessionSeconds * 3) : peakWatts >= starWatts() ? 0xFFFFE28A : 0xFFB8A050);
        c.drawRect(mx, my, mx + mw * Math.max(0f, Math.min(1f, fill)), my + dp(10f), paint);
        label(c, starNow ? "INVINCIBLE" : "STAR - HOLD " + Math.round(starWatts()) + " W",
                mx, my + dp(24f), 9f, starNow ? 0xFFFFE28A : DIM, Paint.Align.LEFT);

        bold(c, "● " + coins, w - dp(16f), dp(30f), 20f, 0xFFF5C518, Paint.Align.RIGHT);
        if (bossesBeaten > 0) {
            label(c, bossesBeaten + (bossesBeaten == 1 ? " crab king beaten" : " crab kings beaten"),
                    w - dp(16f), dp(48f), 9f, DIM, Paint.Align.RIGHT);
        }
        bold(c, Math.round(x) + " m", w / 2f, dp(30f), 20f, TEXT, Paint.Align.CENTER);
        String sub = WORLD_NAMES[world] + (checkpoint > 0 ? "   ·   flag " + Math.round(checkpoint) + " m" : "");
        if (ghost != null && started) {
            int gap = Math.round((float) x - ghostAt(runClock));
            sub += "   ·   " + (gap >= 0 ? "+" + gap + " m on BEST" : gap + " m on BEST");
        }
        label(c, sub, w / 2f, dp(48f), 10f, DIM, Paint.Align.CENTER);

        // The ghost when it is off screen: an arrow at the edge with the gap.
        if (ghost != null && started && !over) {
            float gx = ghostAt(runClock);
            float gap = gx - (float) x;
            float sx = youX + gap * (w / 40f);
            if (sx > w + dp(10f)) {
                label(c, "BEST " + Math.round(gap) + " m ahead ▶", w - dp(12f), groundY - dp(70f), 10f, 0xCCFFFFFF,
                        Paint.Align.RIGHT);
            } else if (sx < -dp(10f)) {
                label(c, "◀ BEST " + Math.round(-gap) + " m behind", dp(12f), groundY - dp(70f), 10f, 0xCCFFFFFF,
                        Paint.Align.LEFT);
            }
        }

        if (fighting && boss != null) {
            drawBossHud(c, w, groundY, youX);
        }
        if (sessionSeconds < worldBannerUntil) {
            float a = (float) Math.min(1.0, (worldBannerUntil - sessionSeconds) / 0.6);
            bold(c, "WORLD " + (world + 1) + "  ·  " + WORLD_NAMES[world], w / 2f, h * 0.24f, 34f,
                    withAlpha(0xFFFFE28A, (int) (255 * a)), Paint.Align.CENTER);
        }
        if (sessionSeconds < flashUntil) {
            boolean good = flash.startsWith("STOMP") || flash.startsWith("+") || flash.startsWith("SMASH")
                    || flash.endsWith("STOMPS!") || flash.startsWith("CHECKPOINT") || flash.startsWith("INVINCIBLE")
                    || flash.startsWith("COUNTER") || flash.startsWith("HIT") || flash.startsWith("BOSS DOWN")
                    || flash.startsWith("BLOCKED") || flash.startsWith("BOP") || flash.startsWith("FROM");
            bold(c, flash, w / 2f, h * 0.36f, 24f, good ? ACCENT : WARN, Paint.Align.CENTER);
        }
        String cap;
        int col = FAINT;
        if (!started) {
            cap = ghost != null
                    ? "TAKE A STROKE TO RUN  ·  YOUR BEST RUN (" + Math.round(ghostEnd) + " m) RUNS WITH YOU AS A GHOST"
                    : "TAKE A STROKE TO RUN  ·  JUMPS ARE AUTOMATIC - ROW FASTER TO RUN FASTER";
        } else if (over) {
            cap = "";
            drawGameOver(c, w, h);
        } else if (fighting) {
            cap = "SURGE ABOVE " + Math.round(surgeWatts()) + " W TO HIT  ·  SURGE WHILE THE CLAW GLOWS TO COUNTER x2";
            col = WARN;
        } else {
            cap = String.format(java.util.Locale.US, "ROW FASTER = RUN FASTER, JUMP FURTHER   ·   %.1f m/s   ·   %d stomps   ·   peak %d W",
                    speed, stomps, peakWatts);
        }
        if (!cap.isEmpty()) {
            bold(c, cap, w / 2f, h - dp(16f), 11f, col, Paint.Align.CENTER);
        }
    }

    private void drawBossHud(Canvas c, float w, float groundY, float youX) {
        // HP bar across the top.
        float bw = dp(420f);
        float bx = w / 2f - bw / 2f;
        float by = dp(62f);
        paint.setColor(0x66000000);
        c.drawRect(bx - dp(3f), by - dp(3f), bx + bw + dp(3f), by + dp(19f), paint);
        float seg = bw / bossMaxHp;
        for (int i = 0; i < bossMaxHp; i++) {
            paint.setColor(i < bossHp ? BOSS_BODY[bossWorld(boss.at)] : 0x33FFFFFF);
            c.drawRect(bx + i * seg + dp(1f), by, bx + (i + 1) * seg - dp(1f), by + dp(16f), paint);
        }
        bold(c, "KING CRAB", w / 2f, by + dp(38f), 12f, TEXT, Paint.Align.CENTER);
        // Surge meter beside the runner: fill is your peak power, the line is a hit.
        float mx = youX - dp(60f);
        float mTop = groundY - dp(130f);
        float mH = dp(110f);
        float target = surgeWatts();
        float f = Math.min(1f, peakWatts / (target * 1.25f));
        paint.setColor(0x55000000);
        c.drawRect(mx - dp(8f), mTop, mx + dp(8f), mTop + mH, paint);
        paint.setColor(peakWatts >= target ? ACCENT : WARN);
        c.drawRect(mx - dp(8f), mTop + mH * (1f - f), mx + dp(8f), mTop + mH, paint);
        paint.setColor(0xFFFFFFFF);
        float ly = mTop + mH * (1f - 1f / 1.25f);
        c.drawRect(mx - dp(12f), ly - dp(1.5f), mx + dp(12f), ly + dp(1.5f), paint);
        label(c, Math.round(target) + " W", mx, mTop - dp(6f), 9f, TEXT, Paint.Align.CENTER);
        if (windupStart > 0 && ((int) (sessionSeconds * 6)) % 2 == 0) {
            bold(c, "SURGE NOW!", w / 2f, groundY - dp(170f), 30f, BAD, Paint.Align.CENTER);
        }
    }

    private void drawGameOver(Canvas c, float w, float h) {
        float pw = dp(640f);
        float ph = dp(250f);
        float left = w / 2f - pw / 2f;
        float top = h * 0.30f;
        paint.setColor(0xCC10171F);
        c.drawRect(left, top, left + pw, top + ph, paint);
        bold(c, "GAME OVER", w / 2f, top + dp(44f), 28f, BAD, Paint.Align.CENTER);
        label(c, Math.round(x) + " m   ·   " + coins + " coins   ·   " + stomps + " stomps   ·   "
                        + bossesBeaten + " crab kings" + (continues > 0 ? "   ·   " + continues + " continues" : ""),
                w / 2f, top + dp(76f), 12f, TEXT, Paint.Align.CENTER);
        if (newBestRun) {
            label(c, "NEW BEST RUN - it is your ghost next time", w / 2f, top + dp(100f), 11f, ACCENT, Paint.Align.CENTER);
        }
        float btnTop = top + dp(130f);
        float btnH = dp(84f);
        if (checkpoint > 0) {
            continueRect.set(left + dp(24f), btnTop, w / 2f - dp(12f), btnTop + btnH);
            restartRect.set(w / 2f + dp(12f), btnTop, left + pw - dp(24f), btnTop + btnH);
            paint.setColor(ACCENT);
            c.drawRect(continueRect, paint);
            bold(c, "CONTINUE", continueRect.centerX(), continueRect.centerY() - dp(2f), 18f, 0xFF10171F, Paint.Align.CENTER);
            label(c, "from the " + Math.round(checkpoint) + " m flag", continueRect.centerX(),
                    continueRect.centerY() + dp(22f), 10f, 0xFF10171F, Paint.Align.CENTER);
        } else {
            continueRect.setEmpty();
            restartRect.set(left + dp(24f), btnTop, left + pw - dp(24f), btnTop + btnH);
        }
        paint.setColor(0xFF3A4658);
        c.drawRect(restartRect, paint);
        bold(c, "RESTART", restartRect.centerX(), restartRect.centerY() - dp(2f), 18f, TEXT, Paint.Align.CENTER);
        label(c, "from 0 m", restartRect.centerX(), restartRect.centerY() + dp(22f), 10f, DIM, Paint.Align.CENTER);
    }
}
