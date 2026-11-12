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
 *
 * <p>And the five the rower approved after those:
 * <ul>
 *   <li><b>A shop.</b> Coins earned in a run are banked between sessions and spent on runner skins,
 *   a better pet and the worlds - buy DESERT, SNOWFIELDS or CASTLE and the whole run is themed to
 *   it instead of cycling. Opened from the start screen or from game over; it is drawn on the game's
 *   own canvas, so it needs no chrome from the activity.</li>
 *   <li><b>Daily run.</b> A 500 m course seeded from today's date - the same for every attempt
 *   today, different tomorrow - timed on the rowing clock. Today's best is kept and the finish card
 *   offers RETRY, so the loop is: run it, see the gap, run it again.</li>
 *   <li><b>Combo chain.</b> Enemies stomped inside five seconds of each other chain up; the chain
 *   multiplies the coins they pay, to x5. A hit breaks it. The chain and its draining window sit
 *   over the runner's head, so the next enemy is worth something in the next few seconds.</li>
 *   <li><b>A pet.</b> A little companion flies alongside and darts at coins the runner is going to
 *   miss - the high coins, the far side of an arc. PIP is free; a gull and a drone in the shop reach
 *   further.</li>
 *   <li><b>Endless.</b> A fresh seed every time, course generated ahead of the runner and pruned
 *   behind, difficulty climbing with distance, and one tap anywhere to run again the instant you
 *   die.</li>
 * </ul>
 */
final class RowRunnerGame extends GameView {

    private enum Kind { PIT, BLOCK, WALL, COIN, ARC_COIN, HIGH_COIN, CRAB, SLIME, SPIKY, BOSS, FLAG, FINISH }

    private enum Mode { TOUR, DAILY, ENDLESS }

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

    /* Shop, daily, combo, pet, endless. */
    private static final String BANK_KEY = "runner.bank";
    private static final String OWNED_KEY = "runner.owned";
    private static final String SKIN_KEY = "runner.skin";
    private static final String PET_KEY = "runner.pet";
    private static final String THEME_KEY = "runner.theme";
    private static final String MODE_KEY = "runner.mode";
    private static final String COMBO_KEY = "runner.combo";
    private static final String ENDLESS_KEY = "runner.endless";
    /** "<day>|<seconds>": stamped, so yesterday's time is not shown as today's best. */
    private static final String DAILY_BEST_KEY = "runner.daily.best";
    private static final String DAILY_RUNS_KEY = "runner.daily.runs";
    private static final String GHOST_ENDLESS_KEY = "runner.ghost.endless";
    private static final String GHOST_DAILY_KEY = "runner.ghost.daily";

    /** The daily course, short enough to re-run: about two and a half minutes at a typical pace. */
    private static final float DAILY_METRES = 500f;
    /** Floor for the chain window; the real one is {@link #comboWindow()}, sized to the rower. */
    private static final double COMBO_WINDOW = 5.0;
    private static final int COMBO_MAX_MULT = 5;
    /** Metres of course kept generated ahead of the runner, and kept behind before pruning. */
    private static final float BUILD_AHEAD = 280f;
    private static final float KEEP_BEHIND = 90f;

    /* Shop stock. Prices are against a run: a decent tour run banks 150-400 coins. */
    private static final String[] SKIN_ID = {"red", "aqua", "ninja", "robot", "knight"};
    private static final String[] SKIN_NAME = {"ROOKIE", "AQUA", "NINJA", "ROBOT", "KNIGHT"};
    private static final int[] SKIN_COST = {0, 120, 300, 600, 1000};
    private static final int[] SKIN_SHIRT = {0xFFE84C3D, 0xFF2FA8C8, 0xFF232833, 0xFF9AA6B8, 0xFFD8D3C0};
    private static final int[] SKIN_LEGS = {0xFF2E5BBA, 0xFF1E6E8C, 0xFF12161F, 0xFF5C6676, 0xFF8A6A3A};
    private static final int[] SKIN_FACE = {0xFFF1C27D, 0xFFF1C27D, 0xFFE8C9A0, 0xFFCFE3FF, 0xFFF1C27D};

    private static final String[] PET_ID = {"pip", "gull", "drone"};
    private static final String[] PET_NAME = {"PIP", "GULL", "DRONE"};
    private static final int[] PET_COST = {0, 350, 700};
    /** How far ahead, in metres, the pet will go for a coin. */
    private static final float[] PET_RANGE = {2.4f, 4.0f, 6.5f};
    private static final int[] PET_BODY = {0xFF6BCB5A, 0xFFF2F7FB, 0xFF9AD8F0};
    private static final int[] PET_WING = {0xFF3F9A34, 0xFFDCEBF7, 0xFF3A6A8C};

    private static final String[] MODE_NAMES = {"TOUR", "DAILY", "ENDLESS"};
    private static final String[] SHOP_HEADS = {"RUNNERS", "PETS", "WORLDS"};

    /** A world you may lock the whole run to. Index -1 is the tour, which cycles as before. */
    private static final String[] THEME_ID = {"tour", "world.desert", "world.snow", "world.castle"};
    private static final int[] THEME_COST = {0, 250, 500, 900};

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
    private final RectF shopBtnRect = new RectF();
    private final RectF closeRect = new RectF();
    private final RectF retryRect = new RectF();
    private final RectF[] modeRect = {new RectF(), new RectF(), new RectF()};

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

    /* The world's layers are baked per world (see ensureBaked), so a theme change needs no rebuild. */
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

    /* Mode, course generation. */
    private Mode mode = Mode.TOUR;
    private java.util.Random gen;
    private long courseSeed = 1234L;
    private float buildAt;
    private float buildFlag;
    private float buildBoss;
    private float courseEnd;
    private double nextPrune;
    private boolean finished;
    private float dailyTime;
    private boolean dailyIsBest;
    private long dailyDay;
    /**
     * Today's best, cached. {@link #dailyBest()} reads SharedPreferences and parses a stamped
     * string; three draw paths were calling it on every frame, which is a prefs read plus four
     * allocations per frame in the game loop. Refreshed on start and whenever it is written.
     */
    private float dailyBestSeconds;
    private String dailyBestText;
    private String dailyModeNote = "today's seed  ·  500 m, timed";
    /** Best endless distance, cached for the same reason: game over redrew it from prefs each frame. */
    private float bestEndless;

    /* Combo chain. */
    private int combo;
    private int bestCombo;
    private double comboUntil;
    private double comboBrokeAt = -10;

    /* Shop: the coin bank, what is owned and what is worn. */
    private int bank;
    private int coinsBanked;
    private final java.util.HashSet<String> owned = new java.util.HashSet<>();
    private int skinIndex;
    private int petIndex;
    /** -1 for the tour (the world cycles every kilometre), else the world the whole run is locked to. */
    private int themeIndex = -1;
    private boolean shopOpen;
    private final float[] shopRowY = new float[3];
    private String shopNote = "";
    private double shopNoteUntil;

    /* The pet, in screen pixels: it lags behind the runner and darts at coins. */
    private float petPx;
    private float petPy;
    private boolean petPlaced;
    private int petGrabs;

    /** One row of the shop. Built once; the rectangles are only re-set while it is drawn. */
    private static final class Item {
        final int group;    // 0 skin, 1 pet, 2 theme
        final int index;    // index into that group's arrays; for a theme, the world (-1 = tour)
        final String id;
        final String name;
        final int cost;
        /** Built once: the card's sub-line and its price, so the shop draws without allocating. */
        final String note;
        final String price;
        final RectF rect = new RectF();

        Item(int group, int index, String id, String name, int cost, String note) {
            this.group = group;
            this.index = index;
            this.id = id;
            this.name = name;
            this.cost = cost;
            this.note = note;
            this.price = "● " + cost;
        }
    }

    private final Item[] shop = buildShop();

    private static Item[] buildShop() {
        Item[] items = new Item[SKIN_ID.length + PET_ID.length + THEME_ID.length];
        int n = 0;
        for (int i = 0; i < SKIN_ID.length; i++) {
            items[n++] = new Item(0, i, SKIN_ID[i], SKIN_NAME[i], SKIN_COST[i], "runner skin");
        }
        for (int i = 0; i < PET_ID.length; i++) {
            items[n++] = new Item(1, i, PET_ID[i], PET_NAME[i], PET_COST[i],
                    String.format(java.util.Locale.US, "reach %.1f m", PET_RANGE[i]));
        }
        items[n++] = new Item(2, -1, THEME_ID[0], "TOUR", 0, "all four, a kilometre each");
        for (int i = 1; i < THEME_ID.length; i++) {
            items[n++] = new Item(2, i, THEME_ID[i], WORLD_NAMES[i], THEME_COST[i],
                    "every kilometre is " + WORLD_NAMES[i]);
        }
        return items;
    }

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
        finished = false;
        combo = 0;
        bestCombo = 0;
        comboUntil = 0;
        comboBrokeAt = -10;
        coinsBanked = 0;
        petPlaced = false;
        petGrabs = 0;
        nextPrune = 0;
        shopOpen = false;
        shopNoteUntil = 0;
        loadShop();
        dailyDay = RegattaGame.today();
        refreshDailyBest();
        bestEndless = bests.get(ENDLESS_KEY, 0f);
        resetCourse(true);
        loadGhost();
        shownWorld = worldAt(0);
    }

    @Override
    protected void onStop() {
        // Leaving mid-run still counts: the furthest you got, and the ghost if it is your best.
        if (started && !over && !finished && x > 50) {
            recordRun();
            saveGhostIfBest();
        }
        bankRun();
    }

    /* ---------- the course ---------- */

    /**
     * Starts the course over for the current mode. The seed is the mode's own: fixed for the tour,
     * today's date for the daily, a fresh one per endless run - and it is kept, so CONTINUE can
     * regenerate exactly the same ground.
     */
    private void resetCourse(boolean freshSeed) {
        things.clear();
        if (mode == Mode.TOUR) {
            courseSeed = 1234L;
        } else if (mode == Mode.DAILY) {
            courseSeed = dailyDay * 8191L + 7L;
        } else if (freshSeed) {
            courseSeed = System.currentTimeMillis();
        }
        gen = new java.util.Random(courseSeed);
        buildAt = 30f;
        buildFlag = FLAG_EVERY;
        buildBoss = BOSS_EVERY;
        courseEnd = mode == Mode.DAILY ? DAILY_METRES - 14f
                : mode == Mode.ENDLESS ? Float.MAX_VALUE : LEVEL_LENGTH;
        if (mode == Mode.DAILY) {
            things.add(new Thing(Kind.FINISH, DAILY_METRES, 0, 0));
        }
        extendCourse((float) x + BUILD_AHEAD);
    }

    /**
     * Generates course up to {@code upTo} metres. A dense course sized to the rower's typical jump,
     * harder with distance. Generated a few hundred metres ahead rather than all at once, so endless
     * can run for as long as the rower does.
     */
    private void extendCourse(float upTo) {
        if (gen == null) {
            return;
        }
        float limit = Math.min(upTo, courseEnd);
        float typicalSpeed = (float) profile.typicalSpeed();
        float typicalWatts = (float) profile.typicalWatts();
        float reach = Math.max(4f, typicalSpeed * JUMP_FACTOR);
        while (buildAt < limit) {
            if (buildAt + 30f > buildBoss) {
                // A clear run-in, the King Crab on the kilometre, and a flag just past it.
                things.add(new Thing(Kind.BOSS, buildBoss, 0, 0));
                things.add(new Thing(Kind.FLAG, buildBoss + 12f, 0, 0));
                buildAt = buildBoss + 25f;
                buildFlag = buildBoss + FLAG_EVERY;
                buildBoss += BOSS_EVERY;
                continue;
            }
            if (buildAt >= buildFlag) {
                things.add(new Thing(Kind.FLAG, buildAt, 0, 0));
                buildAt += 6f;
                buildFlag += FLAG_EVERY;
                continue;
            }
            // Endless keeps climbing past the tour's ceiling, so a long endless run really does bite.
            float difficulty = Math.min(mode == Mode.ENDLESS ? 1.35f : 1f, buildAt / 2500f);
            int roll = gen.nextInt(100);
            if (roll < 20) {
                float width = 2f + gen.nextFloat() * (reach * 0.7f - 2f) * (0.4f + 0.6f * difficulty);
                things.add(new Thing(Kind.PIT, buildAt, width, 0));
                coinArc(buildAt - 0.5f, width + 1f, 1.6f);
                buildAt += width + reach + 2f;
            } else if (roll < 40) {
                int crates = 1 + (int) (gen.nextFloat() * (1 + difficulty * 2.2f));
                things.add(new Thing(Kind.BLOCK, buildAt, crates, 0));
                coinArc(buildAt - 1.5f, 4f, crates + 1.2f);
                buildAt += 1f + reach + 2f;
            } else if (roll < 62) {
                Kind enemy = gen.nextInt(3) == 0 ? Kind.SPIKY : gen.nextBoolean() ? Kind.SLIME : Kind.CRAB;
                things.add(new Thing(enemy, buildAt + 18f, 0, 0));
                buildAt += reach + 3f;
            } else if (roll < 70) {
                float watts = typicalWatts * (0.7f + gen.nextFloat() * (0.35f + difficulty * 0.5f));
                things.add(new Thing(Kind.WALL, buildAt, watts, 0));
                buildAt += 6f;
            } else if (roll < 90) {
                for (int i = 0; i < 5; i++) {
                    things.add(new Thing(Kind.COIN, buildAt + i * 1.5f, 0, 0));
                }
                buildAt += 8f;
            } else {
                things.add(new Thing(Kind.HIGH_COIN, buildAt, 0, 0));
                buildAt += 4f;
            }
            buildAt += 3f + gen.nextFloat() * 6f;
        }
    }

    /** Endless would grow the list forever; drop what is well behind the runner. */
    private void pruneCourse() {
        java.util.Iterator<Thing> it = things.iterator();
        while (it.hasNext()) {
            Thing t = it.next();
            if (t.at < x - KEEP_BEHIND) {
                it.remove();
            }
        }
    }

    /** Coins along the path of a jump, so a clean jump collects them. */
    private void coinArc(float start, float span, float peak) {
        for (int i = 0; i < 5; i++) {
            float f = (i + 0.5f) / 5f;
            things.add(new Thing(Kind.ARC_COIN, start + span * f, 0, 4f * f * (1f - f) * peak));
        }
    }

    /* ---------- the shop: bank, ownership, what is worn ---------- */

    private void loadShop() {
        bank = Math.round(bests.get(BANK_KEY, 0f));
        owned.clear();
        owned.add(SKIN_ID[0]);
        owned.add(PET_ID[0]);
        owned.add(THEME_ID[0]);
        String csv = bests.getString(OWNED_KEY);
        if (csv != null) {
            for (String part : csv.split(",")) {
                String id = part.trim();
                if (!id.isEmpty()) {
                    owned.add(id);
                }
            }
        }
        skinIndex = indexOf(SKIN_ID, bests.getString(SKIN_KEY));
        if (!owned.contains(SKIN_ID[skinIndex])) {
            skinIndex = 0;
        }
        petIndex = indexOf(PET_ID, bests.getString(PET_KEY));
        if (!owned.contains(PET_ID[petIndex])) {
            petIndex = 0;
        }
        String theme = bests.getString(THEME_KEY);
        themeIndex = -1;
        for (int i = 1; i < THEME_ID.length; i++) {
            if (THEME_ID[i].equals(theme) && owned.contains(THEME_ID[i])) {
                themeIndex = i;
            }
        }
        String saved = bests.getString(MODE_KEY);
        mode = "DAILY".equals(saved) ? Mode.DAILY : "ENDLESS".equals(saved) ? Mode.ENDLESS : Mode.TOUR;
    }

    private static int indexOf(String[] ids, String id) {
        if (id != null) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i].equals(id)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private void saveOwned() {
        StringBuilder sb = new StringBuilder();
        for (String id : owned) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        bests.putString(OWNED_KEY, sb.toString());
    }

    /** The run's coins go into the bank once, whether the run ended in a death, a finish or an exit. */
    private void bankRun() {
        int fresh = coins - coinsBanked;
        if (fresh <= 0) {
            return;
        }
        coinsBanked = coins;
        bank += fresh;
        bests.putFloat(BANK_KEY, bank);
    }

    private boolean equipped(Item it) {
        return it.group == 0 ? skinIndex == it.index
                : it.group == 1 ? petIndex == it.index
                : themeIndex == it.index;
    }

    /** Buys an item if it can be afforded, then wears it. Owned items are simply worn. */
    private void tapShopItem(Item it) {
        if (!owned.contains(it.id)) {
            if (bank < it.cost) {
                shopNote = "NEEDS " + (it.cost - bank) + " MORE COINS";
                shopNoteUntil = sessionSeconds + 2.0;
                return;
            }
            bank -= it.cost;
            bests.putFloat(BANK_KEY, bank);
            owned.add(it.id);
            saveOwned();
            shopNote = "BOUGHT " + it.name;
        } else {
            shopNote = it.name + " ON";
        }
        shopNoteUntil = sessionSeconds + 1.6;
        if (it.group == 0) {
            skinIndex = it.index;
            bests.putString(SKIN_KEY, it.id);
        } else if (it.group == 1) {
            petIndex = it.index;
            bests.putString(PET_KEY, it.id);
            petPlaced = false;
        } else {
            themeIndex = it.index;
            bests.putString(THEME_KEY, it.id);
            shownWorld = worldAt(x);
        }
    }

    /* ---------- modes ---------- */

    private void setMode(Mode m) {
        if (mode == m) {
            return;
        }
        mode = m;
        bests.putString(MODE_KEY, m.name());
        restartRun();
    }

    /** A clean run in the current mode, keeping the bank and the shop as they are. */
    private void restartRun() {
        bankRun();
        start();
    }

    /** Today's best time for the daily course, or 0 when today has not been run. */
    private float dailyBest() {
        String stored = bests.getString(DAILY_BEST_KEY);
        if (stored == null) {
            return 0f;
        }
        int bar = stored.indexOf('|');
        if (bar < 0 || !stored.substring(0, bar).equals(Long.toString(dailyDay))) {
            return 0f;
        }
        try {
            return Float.parseFloat(stored.substring(bar + 1));
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    /** Reads today's best once and keeps the strings the cards draw, so no frame parses prefs. */
    private void refreshDailyBest() {
        dailyBestSeconds = dailyBest();
        dailyBestText = dailyBestSeconds > 0f ? clock(dailyBestSeconds) : null;
        dailyModeNote = dailyBestText != null
                ? "today's seed  ·  best " + dailyBestText
                : "today's seed  ·  500 m, timed";
    }

    /** The 500 m daily is done: time it on the rowing clock and keep today's best. */
    private void finishDaily() {
        finished = true;
        fighting = false;
        starUntil = -1;
        dailyTime = (float) activeSeconds;
        float prev = dailyBestSeconds;
        dailyIsBest = prev <= 0f || dailyTime < prev;
        if (dailyIsBest) {
            bests.putString(DAILY_BEST_KEY, dailyDay + "|" + String.format(java.util.Locale.US, "%.1f", dailyTime));
            saveGhost(GHOST_DAILY_KEY, dailyDay + "|");
            refreshDailyBest();
        }
        bests.putFloat(DAILY_RUNS_KEY, bests.get(DAILY_RUNS_KEY, 0f) + 1f);
        bests.recordHighest("runner.coins", coins);
        bests.recordHighest(COMBO_KEY, bestCombo);
        bankRun();
        shake.kick(dp(10f));
        fx.burst(getWidth() * 0.3f, getHeight() * 0.6f, 60, dp(260f), 0.9f, dp(4f), 0xFFF5C518, true);
        say(dailyIsBest ? "NEW DAILY BEST!" : "FINISH!");
    }

    /** Records for the mode just run. The tour and endless keep separate distances. */
    private void recordRun() {
        if (mode == Mode.TOUR) {
            bests.recordHighest("runner.distance", (float) x);
        } else if (mode == Mode.ENDLESS) {
            bests.recordHighest(ENDLESS_KEY, (float) x);
            bestEndless = Math.max(bestEndless, (float) x);
        }
        bests.recordHighest("runner.coins", coins);
        bests.recordHighest(BOSSES_KEY, bossesBeaten);
        bests.recordHighest(COMBO_KEY, bestCombo);
    }

    /* ---------- the combo chain ---------- */

    /**
     * How long a chain stays alive, from the rower's own pace rather than a constant.
     *
     * <p>The generator spaces enemies at {@code typicalSpeed * JUMP_FACTOR + 6..12 m}, so the time
     * between two of them is {@code 1.9 + 6..12 / typicalSpeed} seconds - about 3.5-5.0 s for this
     * machine's 3.85 m/s, but 4.3-6.7 s for someone holding 2.5. A flat five seconds meant a slower
     * rower could essentially never chain two stomps, which is the "it never moves" mistake this
     * project keeps making with a hard-coded threshold.
     */
    private double comboWindow() {
        return Math.max(COMBO_WINDOW, 2.0 + 14.0 / Math.max(1.5, profile.typicalSpeed()));
    }

    /** Coins a stomp pays: the chain multiplies them, to x5. */
    private int comboMultiplier() {
        return Math.max(1, Math.min(COMBO_MAX_MULT, 1 + combo / 3));
    }

    private void addCombo() {
        combo = sessionSeconds < comboUntil ? combo + 1 : 1;
        comboUntil = sessionSeconds + comboWindow();
        bestCombo = Math.max(bestCombo, combo);
    }

    /** A hit ends the chain. Silent on purpose: the hit has its own callout, and the badge vanishing
     * over the runner's head is the feedback. */
    private void breakCombo() {
        if (combo >= 2) {
            comboBrokeAt = sessionSeconds;
        }
        combo = 0;
        comboUntil = 0;
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
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(e);
        }
        float tx = e.getX();
        float ty = e.getY();
        if (shopOpen) {
            if (closeRect.contains(tx, ty)) {
                shopOpen = false;
                return true;
            }
            for (Item it : shop) {
                if (it.rect.contains(tx, ty)) {
                    tapShopItem(it);
                    return true;
                }
            }
            return true;   // the shop swallows taps so nothing behind it moves
        }
        if (finished) {
            if (retryRect.contains(tx, ty)) {
                restartRun();
            } else if (shopBtnRect.contains(tx, ty)) {
                shopOpen = true;
            }
            return true;
        }
        if (over) {
            if (shopBtnRect.contains(tx, ty)) {
                shopOpen = true;
                return true;
            }
            // Endless is about the next run, not the last one: a tap anywhere runs again.
            if (mode == Mode.ENDLESS) {
                restartRun();
                return true;
            }
            if (checkpoint > 0 && continueRect.contains(tx, ty)) {
                continueRun();
                return true;
            }
            if (checkpoint <= 0 || restartRect.contains(tx, ty)) {
                restartRun();
            }
            return true;
        }
        if (!started) {
            // The start screen: pick a mode, or open the shop.
            for (int i = 0; i < modeRect.length; i++) {
                if (modeRect[i].contains(tx, ty)) {
                    setMode(i == 0 ? Mode.TOUR : i == 1 ? Mode.DAILY : Mode.ENDLESS);
                    return true;
                }
            }
            if (shopBtnRect.contains(tx, ty)) {
                shopOpen = true;
                return true;
            }
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

    /** The world a point on the course is in - the kilometre it sits in, or the bought theme. */
    private int worldAt(double metres) {
        if (themeIndex >= 0) {
            return themeIndex;
        }
        if (metres < 0) {
            return 0;
        }
        return ((int) (metres / BOSS_EVERY)) % WORLD_NAMES.length;
    }

    /** The King Crab stands on the kilometre but guards the world it is fought in, 8 m short of it. */
    private int bossWorld(float at) {
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
        boolean holding = over || finished || bonked || fighting || shopOpen;
        if (started && !holding) {
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

        if (started && !over && !finished && !shopOpen) {
            runClock += dt;
            while (runClock >= nextGhostSample && ghostRecLen < MAX_GHOST) {
                ghostRec[ghostRecLen++] = (int) Math.round(x);
                nextGhostSample += 1.0;
            }
            extendCourse((float) x + BUILD_AHEAD);
            if (sessionSeconds > nextPrune) {
                nextPrune = sessionSeconds + 3.0;
                pruneCourse();
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
        if (!overPit()) {
            // Contact shadow: a stepped oval that shrinks as the runner rises.
            float shW = dp(17f) * Math.max(0.35f, 1f - jy / dp(120f));
            float hx = Math.round(youX + lunge);
            blk.setColor(0x38000000);
            c.drawRect(hx - shW, groundY - dp(1.5f), hx + shW, groundY + dp(3f), blk);
            c.drawRect(hx - shW * 0.65f, groundY - dp(3f), hx + shW * 0.65f, groundY + dp(4.5f), blk);
        }
        if (!hurt || starNow || ((int) (sessionSeconds * 10) % 2 == 0)) {
            drawHero(c, youX + lunge, groundY - jy, speed, airNow, 0xFF, starNow);
        }
        drawPet(c);
        drawCombo(c, youX, groundY - jy);
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
        if (shopOpen) {
            drawShop(c, w, h);
        }
    }

    /** Whether the runner is over a gap, so no shadow is drawn on the dark. */
    private boolean overPit() {
        for (int i = 0, n = things.size(); i < n; i++) {
            Thing t = things.get(i);
            if (t.kind == Kind.PIT && x >= t.at && x <= t.at + t.size) {
                return true;
            }
        }
        return false;
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

        if (combo > 0 && sessionSeconds >= comboUntil) {
            combo = 0;
        }
        updatePet(dt, speed, groundY, youX, w / 40f, metre);

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
                case FINISH:
                    if (x >= t.at) {
                        t.done = true;
                        finishDaily();
                        return;
                    }
                    break;
                default:   // enemies
                    if (Math.abs((float) x - t.at) < 0.6f) {
                        t.done = true;
                        if (hNow > 0.7f || starNow) {
                            addCombo();
                            int mult = comboMultiplier();
                            coins += 2 * mult;
                            stomps++;
                            fx.burst(youX, groundY - dp(20f), 18 + combo * 3, dp(130f), 0.5f, dp(3.5f),
                                    enemyColour(t.kind, worldAt(t.at)), true);
                            if (combo >= 2) {
                                fx.burst(youX, groundY - dp(46f), 10, dp(110f), 0.5f, dp(3f),
                                        rainbow(combo * 0.4), false);
                                say("CHAIN x" + combo + "  +" + (2 * mult));
                            } else {
                                say(stomps % 5 == 0 ? stomps + " STOMPS!" : starNow ? "BOP!" : "STOMP");
                            }
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
        breakCombo();
        hearts--;
        shake.kick(dp(12f));
        hurtUntil = sessionSeconds + 1.5;
        say(what);
        checkOver();
    }

    private void fall(Thing pit) {
        breakCombo();
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
            recordRun();
            saveGhostIfBest();
            bankRun();
        }
    }

    /** Back to the last flag: hearts refilled, coins and stomps as they were when it was raised. */
    private void continueRun() {
        x = checkpoint;
        resetCourse(false);
        extendCourse(checkpoint + BUILD_AHEAD);
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

    /** Each mode races its own ghost: the tour's course, an endless run, today's daily. */
    private String ghostKey() {
        return mode == Mode.ENDLESS ? GHOST_ENDLESS_KEY : mode == Mode.DAILY ? GHOST_DAILY_KEY : GHOST_KEY;
    }

    private void loadGhost() {
        ghost = null;
        ghostEnd = 0;
        storedBest = 0;
        String s = bests.getString(ghostKey());
        if (s == null || s.isEmpty()) {
            return;
        }
        if (mode == Mode.DAILY) {
            // Day-stamped: yesterday's daily was a different course, so it is not a ghost for today.
            int bar = s.indexOf('|');
            if (bar < 0 || !s.substring(0, bar).equals(Long.toString(dailyDay))) {
                return;
            }
            s = s.substring(bar + 1);
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
        if (mode == Mode.DAILY || ghostRecLen < 2 || x <= storedBest + 0.5f) {
            return;   // the daily's ghost is its fastest run, saved by finishDaily
        }
        saveGhost(ghostKey(), "");
        storedBest = (float) x;
        newBestRun = true;
    }

    /** Writes this run's recording under {@code key}, after an optional stamp such as the day. */
    private void saveGhost(String key, String prefix) {
        if (ghostRecLen < 2) {
            return;
        }
        StringBuilder sb = new StringBuilder(ghostRecLen * 5 + prefix.length());
        sb.append(prefix);
        for (int i = 0; i < ghostRecLen; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ghostRec[i]);
        }
        if (ghostRecLen < MAX_GHOST) {
            sb.append(',').append((int) Math.round(x));
        }
        bests.putString(key, sb.toString());
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
            float q = dp(PIX);
            px = Math.round(px);
            blk.setColor(INK);
            c.drawRect(px - q * 2, groundY - dp(110f) - q, px + q, groundY, blk);
            c.drawRect(px + q, groundY - dp(110f) - q, px + dp(46f) + q, groundY - dp(88f) + q, blk);
            blk.setColor(0xFFDDE4EE);
            c.drawRect(px - q, groundY - dp(110f), px, groundY, blk);
            blk.setColor(0xFF35D0BA);
            c.drawRect(px + q, groundY - dp(110f), px + dp(46f), groundY - dp(88f), blk);
            blk.setColor(0xFF8AF0E0);
            c.drawRect(px + q, groundY - dp(110f), px + dp(46f), groundY - dp(110f) + q, blk);
            blk.setColor(0xFF1F9A88);
            c.drawRect(px + q, groundY - dp(88f) - q, px + dp(46f), groundY - dp(88f), blk);
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
            label(c, "BEST", sx, groundY - dp(76f), 8f, 0xAAFFFFFF, Paint.Align.CENTER);
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

    /*
     * 3.25.0 graphics pass. Row Runner is pixel art, so it is pixel art all the way through: one
     * pixel size for every layer, a few shades per material, ordered dithering where one shade
     * meets the next, one light (the sun, upper right) and a dark outline on everything in the
     * foreground. The big layers - sky, two mountain ranges, hills, a row of trees / cacti / pines
     * and the ground with its strata - are painted once per world into small bitmaps at one texel
     * per pixel and drawn scaled up with filtering off. The whole backdrop is about a dozen bitmap
     * draws a frame however much is painted into it, and nothing is allocated per frame.
     */
    /** One art pixel, in dp. About 3 mm on the tablet: visible as a pixel from the seat. */
    private static final float PIX = 3f;
    /** Rows of the ground tile above the ground line: grass blades, flowers, drifts. */
    private static final int GROUND_TOP_ROWS = 5;
    /** The one outline colour every sprite shares, as a real pixel-art palette would. */
    private static final int INK = 0xFF1B1626;

    /** Hard-edged fills for pixel art: no anti-aliasing, never given a shader. */
    private final Paint blk = new Paint();
    private final Paint pixPaint = newPixelPaint();
    private final Pix pix = new Pix();
    private final Sprite runner = new Sprite();
    /* Layer indices into a world's baked set. */
    private static final int L_SKY = 0;
    private static final int L_FAR = 1;
    private static final int L_MID = 2;
    private static final int L_HILL = 3;
    private static final int L_PROP = 4;
    private static final int L_GROUND = 5;
    private static final int L_CLOUD = 6;   // three clouds, 6..8
    private static final int LAYERS = 9;

    /** Every world's baked layers; guarded by itself, because worlds are also baked ahead off-thread. */
    private final android.graphics.Bitmap[][] baked = new android.graphics.Bitmap[4][];
    /** The layers of the world being drawn this frame. */
    private android.graphics.Bitmap[] layers;
    private int bakeGen;
    private BakeSize bakeSize;
    private float bakedW = -1f;
    private float bakedH = -1f;
    private float bakedGround = -1f;

    private static Paint newPixelPaint() {
        Paint p = new Paint();
        p.setFilterBitmap(false);
        p.setAntiAlias(false);
        p.setDither(false);
        return p;
    }

    /** Everything a bake needs, measured on the UI thread so a background bake never touches the view. */
    private static final class BakeSize {
        int cols;
        int tile;
        int skyRows;
        float osRows;
        float groundRows;
        int farRows;
        int midRows;
        int hillRows;
        int propRows;
        int groundCols;
        int groundRows2;
    }

    private BakeSize measureBake(float w, float h, float groundY) {
        BakeSize z = new BakeSize();
        float px = dp(PIX);
        float os = dp(OVERSCAN);
        z.cols = (int) Math.ceil((w + 2 * os) / px) + 2;
        z.tile = z.cols + 40;
        z.skyRows = (int) Math.ceil((groundY + os) / px) + 2;
        z.osRows = os / px;
        z.groundRows = groundY / px;
        z.farRows = Math.round(dp(210f) / px);
        z.midRows = Math.round(dp(160f) / px);
        z.hillRows = Math.round(dp(130f) / px);
        z.propRows = Math.round(dp(150f) / px);
        // The ground scrolls at full speed, so its tile is long - about 100 m of running - and a
        // multiple of the castle's brick length so the courses meet across the seam.
        z.groundCols = ((Math.max(z.tile, 1600) + 15) / 16) * 16;
        z.groundRows2 = (int) Math.ceil((h - groundY + os) / px) + GROUND_TOP_ROWS + 2;
        return z;
    }

    private static android.graphics.Bitmap toBitmap(Pix p) {
        return android.graphics.Bitmap.createBitmap(p.buf, 0, p.cols, p.cols, p.rows,
                android.graphics.Bitmap.Config.ARGB_8888);
    }

    /** Paints one world's layers. Plain integer work plus bitmap creation: safe on any thread. */
    private static android.graphics.Bitmap[] bakeWorld(Pix p, int world, BakeSize z) {
        android.graphics.Bitmap[] out = new android.graphics.Bitmap[LAYERS];
        p.begin(z.cols, z.skyRows);
        p.sky(SKY_TOP[world], SKY_BOT[world], z.osRows, z.groundRows, world == 3);
        out[L_SKY] = toBitmap(p);
        p.begin(z.tile, z.farRows);
        p.range(MOUNT[world], CAP[world], SKY_BOT[world], true, world * 97 + 11);
        out[L_FAR] = toBitmap(p);
        p.begin(z.tile, z.midRows);
        p.range(MOUNT[world], CAP[world], SKY_BOT[world], false, world * 97 + 5);
        out[L_MID] = toBitmap(p);
        p.begin(z.tile, z.hillRows);
        p.hills(world, HILL[world], world * 53 + 17);
        out[L_HILL] = toBitmap(p);
        p.begin(z.tile, z.propRows);
        p.props(world, world * 71 + 3);
        out[L_PROP] = toBitmap(p);
        p.begin(z.groundCols, z.groundRows2);
        p.ground(world, TOP[world], SOIL_A[world], SOIL_B[world], TUFT_A[world], TUFT_B[world], world * 131 + 7);
        out[L_GROUND] = toBitmap(p);
        for (int k = 0; k < 3; k++) {
            p.begin(Pix.cloudCols(k), Pix.cloudRows(k));
            p.cloud(k, CLOUD[world], SKY_TOP[world], world == 3);
            out[L_CLOUD + k] = toBitmap(p);
        }
        return out;
    }

    /**
     * Makes this world's layers current, painting them now if they are not ready. The first world
     * is painted when the game opens; the others are painted ahead on a background thread, because
     * on the tablet's CPU a world painted at the moment of the km change would be a visible stall.
     */
    private void ensureBaked(int world, float w, float h, float groundY) {
        if (w != bakedW || h != bakedH || groundY != bakedGround) {
            synchronized (baked) {
                for (int i = 0; i < 4; i++) {
                    baked[i] = null;
                }
                bakeGen++;
            }
            bakedW = w;
            bakedH = h;
            bakedGround = groundY;
            bakeSize = measureBake(w, h, groundY);
        }
        android.graphics.Bitmap[] b;
        synchronized (baked) {
            b = baked[world];
        }
        if (b == null) {
            b = bakeWorld(pix, world, bakeSize);
            synchronized (baked) {
                baked[world] = b;
            }
            if (themeIndex < 0) {
                bakeAhead();
            }
        }
        layers = b;
    }

    private void bakeAhead() {
        final BakeSize z = bakeSize;
        final int gen;
        synchronized (baked) {
            gen = bakeGen;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                Pix p = new Pix();
                for (int wld = 0; wld < 4; wld++) {
                    synchronized (baked) {
                        if (bakeGen != gen) {
                            return;
                        }
                        if (baked[wld] != null) {
                            continue;
                        }
                    }
                    android.graphics.Bitmap[] r = bakeWorld(p, wld, z);
                    synchronized (baked) {
                        if (bakeGen == gen && baked[wld] == null) {
                            baked[wld] = r;
                        }
                    }
                }
            }
        }, "runner-bake");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

    /** Draws a horizontally repeating layer: two copies always cover the overscanned screen. */
    private void drawTiled(Canvas c, android.graphics.Bitmap b, float scrollPx, float topY, float px) {
        float tileW = b.getWidth() * px;
        float off = scrollPx % tileW;
        if (off < 0) {
            off += tileW;
        }
        c.save();
        c.translate(Math.round(-dp(OVERSCAN) - off), Math.round(topY));
        c.scale(px, px);
        c.drawBitmap(b, 0, 0, pixPaint);
        c.drawBitmap(b, b.getWidth(), 0, pixPaint);
        c.restore();
    }

    private void drawWorld(Canvas c, float w, float h, float groundY, float ppm, float youX, float metre, int world) {
        float camX = (float) x;
        float px = dp(PIX);
        float os = dp(OVERSCAN);
        ensureBaked(world, w, h, groundY);
        // Back to front: banded sky, sun, far clouds, two ranges of mountains (the far one hazed
        // toward the horizon colour), the landmark, hills, sky traffic, near clouds, the row of
        // trees, weather, then the ground the runner is on.
        c.save();
        c.translate(-os, -os);
        c.scale(px, px);
        c.drawBitmap(layers[L_SKY], 0, 0, pixPaint);
        c.restore();
        drawSun(c, w, h, world);
        drawClouds(c, w, h, camX, ppm, world, true);
        android.graphics.Bitmap far = layers[L_FAR];
        drawTiled(c, far, camX * ppm * 0.03f, groundY + dp(4f) - far.getHeight() * px, px);
        android.graphics.Bitmap mid = layers[L_MID];
        drawTiled(c, mid, camX * ppm * 0.06f, groundY + dp(8f) - mid.getHeight() * px, px);
        drawLandmark(c, w, groundY, ppm, camX, world);
        android.graphics.Bitmap hill = layers[L_HILL];
        drawTiled(c, hill, camX * ppm * 0.12f, groundY + dp(12f) - hill.getHeight() * px, px);
        drawSkyLife(c, w, h, world);
        drawClouds(c, w, h, camX, ppm, world, false);
        drawProps(c, w, groundY, ppm, camX, world);
        drawWeather(c, w, groundY, world);
        drawGround(c, w, groundY, ppm, youX, camX, world);

        for (int i = 0, n = things.size(); i < n; i++) {
            Thing t = things.get(i);
            float sx = youX + (t.at - camX) * ppm;
            if (sx < -dp(240f) || sx > w + dp(160f)) {
                continue;
            }
            int tw = worldAt(t.at);
            switch (t.kind) {
                case PIT:
                    drawPit(c, t, sx, w, h, groundY, ppm, tw);
                    break;
                case BLOCK: {
                    if (t.done) {
                        break;
                    }
                    for (int k = 0; k < (int) t.size; k++) {
                        drawCrate(c, Math.round(sx), Math.round(groundY - (k + 1) * metre), metre, tw);
                    }
                    break;
                }
                case WALL: {
                    if (t.done) {
                        break;
                    }
                    float wh = dp(40f) + Math.max(0f, t.size / (float) profile.typicalWatts() - 0.7f) * dp(55f);
                    drawBrickWall(c, Math.round(sx), groundY, wh);
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
                    drawCoin(c, sx, cy, t.kind == Kind.HIGH_COIN ? 4 : 3, sessionSeconds * 5 + t.at * 0.9);
                    if (t.kind == Kind.HIGH_COIN) {
                        label(c, highCoinLabel(), sx, cy - dp(13f), 7.5f, FAINT, Paint.Align.CENTER);
                    }
                    break;
                }
                case FLAG:
                    drawFlag(c, t, sx, groundY);
                    break;
                case FINISH:
                    drawFinishGate(c, sx, groundY);
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

    /* The high coin's speed caption, rebuilt only when its value changes (it was a format per frame). */
    private float highCoinLabelFor = -1f;
    private String highCoinLabelText = "";

    private String highCoinLabel() {
        float v = Math.round(highCoinSpeed() * 10f) / 10f;
        if (v != highCoinLabelFor) {
            highCoinLabelFor = v;
            highCoinLabelText = String.format(java.util.Locale.US, "%.1f m/s", v);
        }
        return highCoinLabelText;
    }

    /** A pixel sun with rays that tick round, or the castle's cratered moon. */
    private void drawSun(Canvas c, float w, float h, int world) {
        float q = dp(PIX);
        float sx = Math.round(w * 0.85f);
        float sy = Math.round(h * 0.14f);
        if (world == 3) {
            Fx.glow(c, sx, sy, dp(70f), 0x44D8D0FF);
            pixelDisc(c, sx, sy, 9, q, 0xFFB8B0CC);
            pixelDisc(c, sx + q, sy, 8, q, 0xFFE8E4F0);
            blk.setColor(0xFFFFFFFF);
            c.drawRect(sx + q * 3, sy - q * 6, sx + q * 5, sy - q * 5, blk);
            blk.setColor(0xFFC8C2D8);
            c.drawRect(sx - q * 3, sy - q * 3, sx, sy, blk);
            c.drawRect(sx + q * 2, sy + q * 2, sx + q * 4, sy + q * 4, blk);
            c.drawRect(sx - q * 1, sy + q * 4, sx, sy + q * 5, blk);
            blk.setColor(0xFFA8A0BE);
            c.drawRect(sx - q * 3, sy - q * 1, sx - q * 1, sy, blk);
            return;
        }
        int glow = world == 1 ? 0x88FFD27A : world == 2 ? 0x55FFFFFF : 0x66FFE28A;
        Fx.glow(c, sx, sy, dp(world == 1 ? 90f : 60f), glow);
        int r = world == 1 ? 10 : 8;
        int rim = world == 1 ? 0xFFFFB04A : world == 2 ? 0xFFFFE6B0 : 0xFFFFC94A;
        int body = world == 1 ? 0xFFFFD27A : world == 2 ? 0xFFFFF4D6 : 0xFFFFE28A;
        int core = world == 2 ? 0xFFFFFFFF : 0xFFFFF6C8;
        // Eight stubby rays that step round by 45 degrees twice a second.
        boolean diag = ((int) (sessionSeconds * 2)) % 2 == 0;
        blk.setColor(rim);
        for (int k = 0; k < 8; k += 2) {
            double a = (k + (diag ? 1 : 0)) * Math.PI / 4;
            float rx = sx + (float) Math.round(Math.cos(a) * (r + 3)) * q;
            float ry = sy + (float) Math.round(Math.sin(a) * (r + 3)) * q;
            c.drawRect(rx - q, ry - q, rx + q, ry + q, blk);
        }
        pixelDisc(c, sx, sy, r, q, rim);
        pixelDisc(c, sx, sy, r - 1, q, body);
        pixelDisc(c, sx + q, sy - q, r - 4, q, core);
    }

    /** A disc of pixel rows. */
    private void pixelDisc(Canvas c, float cx, float cy, int r, float q, int colour) {
        blk.setColor(colour);
        for (int j = -r; j < r; j++) {
            float yy = j + 0.5f;
            int half = Math.round((float) Math.sqrt(r * r - yy * yy));
            c.drawRect(cx - half * q, cy + j * q, cx + half * q, cy + (j + 1) * q, blk);
        }
    }

    /** Two layers of pixel clouds: small, pale and slow behind the mountains; big ones in front. */
    private void drawClouds(Canvas c, float w, float h, float camX, float ppm, int world, boolean far) {
        float px = dp(PIX);
        double t = sessionSeconds;
        int count = far ? 5 : 4;
        float par = far ? 0.04f : 0.2f;
        float drift = far ? dp(3f) : dp(9f);
        float span = w + dp(300f);
        pixPaint.setAlpha(far ? 190 : 255);
        for (int i = 0; i < count; i++) {
            android.graphics.Bitmap b = layers[L_CLOUD + (far ? (i % 2 == 0 ? 2 : 0) : (i % 2 == 0 ? 1 : 0))];
            float base = i * span / count + (i % 3) * dp(far ? 50f : 110f);
            float cx = (float) ((base - camX * ppm * par - t * drift * (1 + (i % 2) * 0.4)) % span);
            if (cx < 0) {
                cx += span;
            }
            cx -= dp(150f);
            float cy = far ? h * 0.17f + (i % 3) * dp(28f) : h * 0.10f + (i % 2) * dp(44f);
            c.save();
            c.translate(Math.round(cx), Math.round(cy));
            c.scale(px, px);
            c.drawBitmap(b, 0, 0, pixPaint);
            c.restore();
        }
        pixPaint.setAlpha(255);
    }

    /** A landmark on the horizon every so often - a castle, or a stepped pyramid in the desert. */
    private void drawLandmark(Canvas c, float w, float groundY, float ppm, float camX, int world) {
        double t = sessionSeconds;
        float cSpan = w * 1.6f;
        float cx = Math.round((float) (((w * 0.7f - camX * ppm * 0.08f) % cSpan + cSpan) % cSpan) - dp(100f));
        float cBase = groundY - dp(60f);
        int haze = SKY_BOT[world];
        float q = dp(PIX);
        if (world == 1) {
            // Stepped pyramid, lit on its right-hand faces, each step with a bright lip.
            int lit = Pix.mix(0xFFE7B86E, haze, 0.25f);
            int shade = Pix.mix(0xFFB98544, haze, 0.25f);
            int lip = Pix.mix(0xFFF7DCA6, haze, 0.2f);
            int under = Pix.mix(0xFF9A6A34, haze, 0.25f);
            for (int s = 0; s < 8; s++) {
                float half = dp(110f) - s * dp(13f);
                float y0 = cBase + dp(40f) - (s + 1) * dp(16f);
                float y1 = cBase + dp(40f) - s * dp(16f);
                blk.setColor(shade);
                c.drawRect(cx - half, y0, cx, y1, blk);
                blk.setColor(lit);
                c.drawRect(cx, y0, cx + half, y1, blk);
                blk.setColor(lip);
                c.drawRect(cx, y0, cx + half, y0 + q, blk);
                blk.setColor(under);
                c.drawRect(cx - half, y0, cx, y0 + q, blk);
            }
            blk.setColor(0xFF6E4A22);
            c.drawRect(cx - dp(8f), cBase + dp(18f), cx + dp(8f), cBase + dp(40f), blk);
            blk.setColor(0xFF4A2E12);
            c.drawRect(cx - dp(8f), cBase + dp(18f), cx - dp(4f), cBase + dp(40f), blk);
            return;
        }
        int stone = world == 0 ? 0xFF5F7FA6 : world == 2 ? 0xFFB8D4EE : 0xFF221A36;
        stone = Pix.mix(stone, haze, 0.12f);
        int lit = world == 3 ? 0xFF33284E : Pix.shade(stone, 1.16f);
        int dark = Pix.shade(stone, 0.8f);
        int slit = world == 3 ? 0xFF0A0612 : Pix.shade(stone, 0.55f);
        float big = world == 3 ? 1.5f : 1f;
        float bl = cx - dp(48f) * big;
        float br = cx + dp(48f) * big;
        float bottom = cBase + dp(40f);
        // Keep, then the two towers, each lit down its right side and shaded down its left.
        blk.setColor(stone);
        c.drawRect(bl, cBase - dp(48f) * big, br, bottom, blk);
        blk.setColor(lit);
        c.drawRect(br - q * 2, cBase - dp(48f) * big, br, bottom, blk);
        for (int side = 0; side < 2; side++) {
            float tl = side == 0 ? cx - dp(66f) * big : cx + dp(38f) * big;
            float tr = tl + dp(28f) * big;
            float tt = cBase - dp(84f) * big;
            blk.setColor(stone);
            c.drawRect(tl, tt, tr, bottom, blk);
            blk.setColor(dark);
            c.drawRect(tl, tt, tl + q * 2, bottom, blk);
            blk.setColor(lit);
            c.drawRect(tr - q * 2, tt, tr, bottom, blk);
            for (int b = 0; b < 3; b++) {
                float bw = dp(10f) * big;
                float mx = tl + b * bw;
                blk.setColor(stone);
                c.drawRect(mx, cBase - dp(96f) * big, mx + bw * 0.6f, tt, blk);
                blk.setColor(lit);
                c.drawRect(mx, cBase - dp(96f) * big, mx + bw * 0.6f, cBase - dp(96f) * big + q, blk);
            }
            blk.setColor(slit);
            float wx = (tl + tr) / 2f - q * 0.5f;
            c.drawRect(wx, tt + dp(14f) * big, wx + q, tt + dp(26f) * big, blk);
            c.drawRect(wx, tt + dp(40f) * big, wx + q, tt + dp(52f) * big, blk);
        }
        blk.setColor(0xFFE0582E);
        c.drawRect(cx - dp(53f) * big, cBase - dp(120f) * big, cx - dp(51f) * big, cBase - dp(96f) * big, blk);
        c.drawRect(cx - dp(51f) * big, cBase - dp(120f) * big, cx - dp(37f) * big + (float) Math.sin(t * 5) * dp(2f),
                cBase - dp(110f) * big, blk);
        blk.setColor(world == 3 ? 0xFF0A0612 : 0xFF2E4466);
        c.drawRect(cx - dp(10f), cBase + dp(10f), cx + dp(10f), bottom, blk);
        c.drawRect(cx - dp(7f), cBase + dp(7f), cx + dp(7f), cBase + dp(10f), blk);
        if (world == 3) {
            // Lit windows that flicker.
            for (int i = 0; i < 6; i++) {
                boolean on = ((int) (t * 2 + i * 1.7)) % 5 != 0;
                float wx = cx - dp(40f) + (i % 3) * dp(34f);
                float wy = cBase - dp(50f) + (i / 3) * dp(30f);
                blk.setColor(on ? 0xFFFFC04A : 0xFF3A2A10);
                c.drawRect(wx, wy, wx + dp(8f), wy + dp(12f), blk);
                if (on) {
                    blk.setColor(0xFFFFE8A8);
                    c.drawRect(wx + dp(5f), wy, wx + dp(8f), wy + dp(4f), blk);
                }
            }
        }
    }

    /** Pixel birds (vultures in the desert, bats at the castle), and an airship drifting the other way. */
    private void drawSkyLife(Canvas c, float w, float h, int world) {
        double t = sessionSeconds;
        float px = dp(6f);
        blk.setColor(BIRD[world]);
        float fx0 = (float) (w - ((t * dp(world == 3 ? 70f : 45f)) % (w + dp(300f))));
        for (int b = 0; b < 4; b++) {
            float bx = Math.round(fx0 + b * dp(28f));
            float by = Math.round(h * 0.26f + (b % 2) * dp(14f) + (world == 3 ? (float) Math.sin(t * 7 + b) * dp(8f) : 0f));
            boolean up = ((int) (t * (world == 3 ? 12 : 6) + b)) % 2 == 0;
            c.drawRect(bx - px * 2, by + (up ? -px : 0), bx - px, by + (up ? 0 : px), blk);
            c.drawRect(bx - px, by, bx + px, by + px, blk);
            c.drawRect(bx + px, by + (up ? -px : 0), bx + px * 2, by + (up ? 0 : px), blk);
        }
        if (world == 3) {
            return;   // no airships over the castle - the moon has the sky
        }
        float q = dp(PIX);
        float ax = Math.round((float) (((t * dp(18f)) % (w + dp(400f))) - dp(200f)));
        float ay = Math.round(h * 0.20f);
        // Envelope: outline, body, a shaded belly and a highlight along the top.
        blk.setColor(INK);
        c.drawRect(ax - dp(60f) - q, ay - dp(14f) - q, ax + dp(60f) + q, ay + dp(14f) + q, blk);
        c.drawRect(ax - dp(48f) - q, ay - dp(20f) - q, ax + dp(48f) + q, ay + dp(20f) + q, blk);
        blk.setColor(0xFFE8E3D3);
        c.drawRect(ax - dp(60f), ay - dp(14f), ax + dp(60f), ay + dp(14f), blk);
        c.drawRect(ax - dp(48f), ay - dp(20f), ax + dp(48f), ay + dp(20f), blk);
        blk.setColor(0xFFC4BCA6);
        c.drawRect(ax - dp(60f), ay + dp(6f), ax + dp(60f), ay + dp(14f), blk);
        c.drawRect(ax - dp(48f), ay + dp(14f), ax + dp(48f), ay + dp(20f), blk);
        blk.setColor(0xFFFFFCF0);
        c.drawRect(ax - dp(30f), ay - dp(20f), ax + dp(44f), ay - dp(17f), blk);
        c.drawRect(ax + dp(44f), ay - dp(14f), ax + dp(57f), ay - dp(11f), blk);
        blk.setColor(0xFFE0582E);
        c.drawRect(ax - dp(48f), ay - dp(3f), ax + dp(48f), ay + dp(3f), blk);
        blk.setColor(0xFFA83A1E);
        c.drawRect(ax - dp(48f), ay + dp(1f), ax + dp(48f), ay + dp(3f), blk);
        // Gondola on two struts.
        blk.setColor(INK);
        c.drawRect(ax - dp(14f), ay + dp(20f), ax - dp(12f), ay + dp(24f), blk);
        c.drawRect(ax + dp(12f), ay + dp(20f), ax + dp(14f), ay + dp(24f), blk);
        c.drawRect(ax - dp(18f) - q, ay + dp(24f) - q, ax + dp(18f) + q, ay + dp(34f) + q, blk);
        blk.setColor(0xFF8B5A2B);
        c.drawRect(ax - dp(18f), ay + dp(24f), ax + dp(18f), ay + dp(34f), blk);
        blk.setColor(0xFFFFE28A);
        c.drawRect(ax - dp(12f), ay + dp(27f), ax - dp(6f), ay + dp(31f), blk);
        c.drawRect(ax + dp(6f), ay + dp(27f), ax + dp(12f), ay + dp(31f), blk);
        blk.setColor(0xFFDCEBF7);
        if (((int) (t * 8)) % 2 == 0) {
            c.drawRect(ax - dp(70f), ay - dp(12f), ax - dp(62f), ay + dp(12f), blk);
        } else {
            c.drawRect(ax - dp(70f), ay - dp(4f), ax - dp(62f), ay + dp(4f), blk);
        }
    }

    /**
     * The row of trees nearest the runner, at half speed: oaks, bushes and fences in the meadow,
     * cacti and boulders in the desert, snowy pines and the odd snowman in the snowfields - baked
     * with an outline. The castle's torches stay live, because they flicker.
     */
    private void drawProps(Canvas c, float w, float groundY, float ppm, float camX, int world) {
        float px = dp(PIX);
        if (world != 3) {
            android.graphics.Bitmap b = layers[L_PROP];
            drawTiled(c, b, camX * ppm * 0.5f, groundY + dp(3f) - b.getHeight() * px, px);
            return;
        }
        float q = px;
        float span = dp(230f);
        float scroll = camX * ppm * 0.5f;
        float o = scroll % span;
        for (float sx = -o - span; sx < w + span; sx += span) {
            int k = (int) Math.floor((sx + scroll) / span + 0.5f);
            float jitter = (Math.abs(k * 53) % 5) * dp(20f);
            float bx = Math.round(sx + jitter);
            if ((k & 3) == 1) {
                // A banner on a pole between the torches.
                blk.setColor(INK);
                c.drawRect(bx - q * 2, groundY - dp(118f), bx + q * 2, groundY, blk);
                c.drawRect(bx + q, groundY - dp(110f) - q, bx + dp(36f) + q, groundY - dp(58f), blk);
                blk.setColor(0xFF4A4058);
                c.drawRect(bx - q, groundY - dp(118f) + q, bx + q, groundY, blk);
                blk.setColor(0xFF8E2A3A);
                c.drawRect(bx + q * 2, groundY - dp(110f), bx + dp(36f), groundY - dp(64f), blk);
                blk.setColor(0xFFB8404E);
                c.drawRect(bx + dp(36f) - q * 2, groundY - dp(110f), bx + dp(36f), groundY - dp(64f), blk);
                blk.setColor(0xFFF5C518);
                c.drawRect(bx + q * 2, groundY - dp(110f), bx + dp(36f), groundY - dp(110f) + q, blk);
                c.drawRect(bx + dp(15f), groundY - dp(96f), bx + dp(24f), groundY - dp(84f), blk);
                // Swallowtail: two points at the bottom.
                blk.setColor(0xFF8E2A3A);
                c.drawRect(bx + q * 2, groundY - dp(64f), bx + dp(14f), groundY - dp(58f), blk);
                c.drawRect(bx + dp(24f), groundY - dp(64f), bx + dp(36f), groundY - dp(58f), blk);
                continue;
            }
            // Torch on a post: the flame flickers, the glow breathes.
            float flick = (float) Math.sin(sessionSeconds * 13 + k * 2.1) * 0.5f + 0.5f;
            blk.setColor(INK);
            c.drawRect(bx - q * 2, groundY - dp(72f), bx + q * 2, groundY, blk);
            c.drawRect(bx - q * 3, groundY - dp(74f), bx + q * 3, groundY - dp(66f), blk);
            blk.setColor(0xFF3A3040);
            c.drawRect(bx - q, groundY - dp(70f), bx + q, groundY, blk);
            blk.setColor(0xFF5A5068);
            c.drawRect(bx, groundY - dp(70f), bx + q, groundY, blk);
            blk.setColor(0xFF6A5A48);
            c.drawRect(bx - q * 2, groundY - dp(72f), bx + q * 2, groundY - dp(68f), blk);
            Fx.glow(c, bx, groundY - dp(80f), dp(46f), flick > 0.5f ? 0x66FF9A2A : 0x44FF9A2A);
            float lift = flick > 0.5f ? q : 0f;
            blk.setColor(0xFFE0501A);
            c.drawRect(bx - q * 2, groundY - dp(82f) - lift, bx + q * 2, groundY - dp(72f), blk);
            blk.setColor(0xFFFF9A2A);
            c.drawRect(bx - q, groundY - dp(86f) - lift, bx + q, groundY - dp(73f), blk);
            blk.setColor(0xFFFFE28A);
            c.drawRect(bx - q * 0.5f, groundY - dp(79f), bx + q * 0.5f, groundY - dp(73f), blk);
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
            blk.setColor(world == 2 ? 0xDDFFFFFF : ((i & 1) == 0 ? 0xCCFF8A2A : 0xAAFFD27A));
            float s = dp(world == 2 ? 1.5f + (i % 3) : 1.2f + (i % 2));
            c.drawRect(fxp - s, fy - s, fxp + s, fy + s, blk);
        }
    }

    /**
     * The ground, one baked tile: grass with a lit edge and blades and flowers, a lip that drips
     * over the soil, wavy strata dithered into each other, rocks, ore, roots, fossils and the odd
     * buried coin. The castle is brick. Pits are cut out of the blades above the line, so no grass
     * floats over a gap.
     */
    private void drawGround(Canvas c, float w, float groundY, float ppm, float youX, float camX, int world) {
        float px = dp(PIX);
        c.save();
        for (int i = 0, n = things.size(); i < n; i++) {
            Thing t = things.get(i);
            if (t.kind != Kind.PIT) {
                continue;
            }
            float sx = youX + (t.at - camX) * ppm;
            float ex = sx + t.size * ppm;
            if (ex < -dp(OVERSCAN) || sx > w + dp(OVERSCAN)) {
                continue;
            }
            c.clipRect(sx, groundY - GROUND_TOP_ROWS * px - dp(2f), ex, groundY, android.graphics.Region.Op.DIFFERENCE);
        }
        drawTiled(c, layers[L_GROUND], camX * ppm, groundY - GROUND_TOP_ROWS * px, px);
        c.restore();
    }

    /** A gap in the ground: lit and shaded walls, the strata darkening into the hole, a grass lip. */
    private void drawPit(Canvas c, Thing t, float sx, float w, float h, float groundY, float ppm, int tw) {
        float ex = sx + t.size * ppm;
        // Clamped to the screen: the loop keeps things up to 240dp off the left edge, and a pit
        // straddling the viewport painted its near-black fill as a bar at the edge.
        float l = Math.max(0f, sx);
        float r = Math.min(w, ex);
        if (r <= l) {
            return;
        }
        float q = dp(PIX);
        float bottom = h + dp(OVERSCAN);
        int fill = PIT_FILL[tw];
        blk.setColor(fill);
        c.drawRect(l, groundY, r, bottom, blk);
        float start = sx + q * (float) Math.ceil((l - sx) / q);
        if (tw == 3) {
            // Lava: a bright crust, a glowing skin dithered into the melt, bubbles that rise and pop.
            blk.setColor(0xFFFFE6A0);
            c.drawRect(l, groundY + q * 2, r, groundY + q * 3, blk);
            blk.setColor(0xFFFF8A2A);
            c.drawRect(l, groundY + q * 3, r, groundY + q * 6, blk);
            blk.setColor(0xFFFF8A2A);
            int k = 0;
            for (float xx = start; xx < r; xx += q, k++) {
                if ((k & 1) == 0) {
                    c.drawRect(xx, groundY + q * 6, Math.min(r, xx + q), groundY + q * 7, blk);
                }
            }
            Fx.glow(c, (l + r) / 2f, groundY + q * 4, dp(70f), 0x55FF7A1A);
            blk.setColor(0xFFFFD27A);
            for (int b = 0; b < 3; b++) {
                double ph = (sessionSeconds * 0.8 + b * 0.37 + t.at * 0.1) % 1.0;
                float bx = Math.round(sx + (b + 0.5f) / 3f * t.size * ppm);
                if (bx > l && bx < r) {
                    float s = q * (ph < 0.5 ? 1 : 2);
                    float by = Math.round(groundY + dp(40f) - (float) ph * dp(30f));
                    c.drawRect(bx - s, by - s, bx + s, by + s, blk);
                }
            }
            return;
        }
        int soil = SOIL_A[tw];
        int wallTop = Pix.mix(Pix.shade(soil, 0.55f), fill, 0.25f);
        int wallMid = Pix.mix(Pix.shade(soil, 0.4f), fill, 0.55f);
        blk.setColor(wallTop);
        c.drawRect(l, groundY, r, groundY + q * 4, blk);
        blk.setColor(wallMid);
        c.drawRect(l, groundY + q * 4, r, groundY + q * 8, blk);
        // Checkerboard seams, one pixel high, where each band meets the next.
        int k = 0;
        for (float xx = start; xx < r; xx += q, k++) {
            float xr = Math.min(r, xx + q);
            if ((k & 1) == 0) {
                blk.setColor(wallTop);
                c.drawRect(xx, groundY + q * 4, xr, groundY + q * 5, blk);
            } else {
                blk.setColor(wallMid);
                c.drawRect(xx, groundY + q * 8, xr, groundY + q * 9, blk);
            }
        }
        // The left wall faces the sun; the right one is in its own shadow.
        boolean leftEdge = sx >= l - 0.5f;
        boolean rightEdge = ex <= r + 0.5f;
        if (leftEdge) {
            blk.setColor(Pix.shade(soil, 0.72f));
            c.drawRect(sx, groundY, sx + q * 2, bottom, blk);
            blk.setColor(Pix.shade(soil, 0.5f));
            c.drawRect(sx + q * 2, groundY + q * 2, sx + q * 3, bottom, blk);
        }
        if (rightEdge) {
            blk.setColor(Pix.shade(SOIL_B[tw], 0.36f));
            c.drawRect(ex - q * 2, groundY, ex, bottom, blk);
        }
        // The grass lip curls over both edges.
        int lip = tw == 2 ? 0xFFF7FBFF : Pix.shade(TOP[tw], 0.9f);
        int lipDark = tw == 2 ? 0xFFB4C8DE : Pix.shade(TOP[tw], 0.6f);
        if (leftEdge) {
            blk.setColor(lip);
            c.drawRect(sx, groundY, sx + q * 2, groundY + q * 2, blk);
            blk.setColor(lipDark);
            c.drawRect(sx, groundY + q * 2, sx + q, groundY + q * 4, blk);
        }
        if (rightEdge) {
            blk.setColor(lip);
            c.drawRect(ex - q * 2, groundY, ex, groundY + q * 2, blk);
            blk.setColor(lipDark);
            c.drawRect(ex - q, groundY + q * 2, ex, groundY + q * 4, blk);
        }
        if (tw == 2) {
            // Icicles hanging from both rims.
            blk.setColor(0xFFCFE8F8);
            for (int i = 0; i < 3; i++) {
                float len = q * (i == 1 ? 5 : 3 - i);
                if (leftEdge) {
                    float ix = sx + q * (2 + i * 2);
                    c.drawRect(ix, groundY + q * 2, ix + q, groundY + q * 2 + len, blk);
                }
                if (rightEdge) {
                    float ix = ex - q * (3 + i * 2);
                    c.drawRect(ix, groundY + q * 2, ix + q, groundY + q * 2 + len, blk);
                }
            }
        }
    }

    /**
     * One crate (or sandstone, ice or castle block), lit from the right: a dark outline, bright top
     * and right rims, shaded bottom and left, and each world's own face.
     */
    private void drawCrate(Canvas c, float l, float top, float m, int tw) {
        float q = Math.max(1f, Math.round(m / 14f));
        float r = l + m;
        float b = top + m;
        int body = CRATE[tw];
        int line = CRATE_LINE[tw];
        int hi = Pix.shade(body, 1.2f);
        int lo = Pix.shade(body, 0.78f);
        blk.setColor(Pix.shade(line, 0.5f));
        c.drawRect(l, top, r, b, blk);
        blk.setColor(body);
        c.drawRect(l + q, top + q, r - q, b - q, blk);
        blk.setColor(hi);
        c.drawRect(l + q, top + q, r - q, top + 2 * q, blk);
        c.drawRect(r - 2 * q, top + q, r - q, b - q, blk);
        blk.setColor(lo);
        c.drawRect(l + q, b - 2 * q, r - q, b - q, blk);
        c.drawRect(l + q, top + 2 * q, l + 2 * q, b - 2 * q, blk);
        switch (tw) {
            case 0: {
                // Wooden crate: a recessed panel, a stepped diagonal brace, nails at the corners.
                blk.setColor(line);
                c.drawRect(l + 3 * q, top + 3 * q, r - 3 * q, b - 3 * q, blk);
                blk.setColor(lo);
                c.drawRect(l + 4 * q, top + 4 * q, r - 4 * q, b - 4 * q, blk);
                int steps = Math.max(1, Math.round((m - 8 * q) / q) - 1);
                for (int i = 0; i < steps; i++) {
                    float bx = l + 4 * q + i * q;
                    float by = b - 4 * q - (i + 1) * q;
                    blk.setColor(body);
                    c.drawRect(bx, by, Math.min(r - 4 * q, bx + 2 * q), by + q, blk);
                }
                blk.setColor(INK);
                c.drawRect(l + 2 * q, top + 2 * q, l + 3 * q, top + 3 * q, blk);
                c.drawRect(r - 3 * q, top + 2 * q, r - 2 * q, top + 3 * q, blk);
                c.drawRect(l + 2 * q, b - 3 * q, l + 3 * q, b - 2 * q, blk);
                c.drawRect(r - 3 * q, b - 3 * q, r - 2 * q, b - 2 * q, blk);
                break;
            }
            case 1: {
                // Sandstone: a course line, a joint, and a crack.
                blk.setColor(line);
                float mid = Math.round(top + m / 2f);
                c.drawRect(l + q, mid, r - q, mid + q, blk);
                c.drawRect(l + m * 0.55f, top + q, l + m * 0.55f + q, mid, blk);
                c.drawRect(l + m * 0.3f, mid + q, l + m * 0.3f + q, b - q, blk);
                blk.setColor(hi);
                c.drawRect(l + q, mid + q, r - q, mid + 2 * q, blk);
                blk.setColor(lo);
                c.drawRect(r - 5 * q, top + 3 * q, r - 4 * q, top + 5 * q, blk);
                c.drawRect(r - 4 * q, top + 5 * q, r - 3 * q, top + 6 * q, blk);
                break;
            }
            case 2: {
                // Ice: a two-stroke shine at the top right and bubbles frozen inside.
                blk.setColor(0xEEFFFFFF);
                c.drawRect(r - 5 * q, top + 2 * q, r - 3 * q, top + 3 * q, blk);
                c.drawRect(r - 4 * q, top + 3 * q, r - 3 * q, top + 6 * q, blk);
                c.drawRect(r - 7 * q, top + 2 * q, r - 6 * q, top + 3 * q, blk);
                blk.setColor(0xAAFFFFFF);
                c.drawRect(l + 4 * q, b - 5 * q, l + 5 * q, b - 4 * q, blk);
                c.drawRect(l + 6 * q, b - 7 * q, l + 7 * q, b - 6 * q, blk);
                blk.setColor(line);
                c.drawRect(l + 3 * q, top + 6 * q, l + 4 * q, top + 9 * q, blk);
                break;
            }
            default: {
                // Castle stone: four blocks with mortar between, each with its own lit top.
                blk.setColor(line);
                float mid = Math.round(top + m / 2f);
                c.drawRect(l + q, mid, r - q, mid + q, blk);
                c.drawRect(l + m * 0.6f, top + q, l + m * 0.6f + q, mid, blk);
                c.drawRect(l + m * 0.35f, mid + q, l + m * 0.35f + q, b - q, blk);
                blk.setColor(hi);
                c.drawRect(l + q, mid + q, r - q, mid + 2 * q, blk);
                break;
            }
        }
    }

    /** A brick wall with a capstone: staggered courses, mortar, lit tops and a lit right edge. */
    private void drawBrickWall(Canvas c, float sx, float groundY, float wh) {
        float q = dp(2f);
        float l = sx - dp(10f);
        float r = sx + dp(10f);
        float top = Math.round(groundY - wh);
        blk.setColor(0xFF3A0E10);
        c.drawRect(l, top, r, groundY, blk);
        blk.setColor(0xFFB33A3A);
        c.drawRect(l + q, top + q, r - q, groundY, blk);
        float course = dp(10f);
        int row = 0;
        for (float y = top + q; y < groundY; y += course, row++) {
            float yb = Math.min(groundY, y + course - q);
            blk.setColor(0xFFD65A4E);
            c.drawRect(l + q, y, r - q, Math.min(yb, y + q), blk);
            blk.setColor(0xFFC84A40);
            c.drawRect(r - 2 * q, y + q, r - q, yb, blk);
            blk.setColor(0xFF6A1C1C);
            c.drawRect(l + q, yb, r - q, Math.min(groundY, yb + q), blk);
            float jx = (row & 1) == 0 ? sx - dp(3f) : sx + dp(3f);
            c.drawRect(jx, y, jx + q, yb, blk);
            c.drawRect(l + q, y + q, l + 2 * q, yb, blk);
        }
        blk.setColor(0xFF3A0E10);
        c.drawRect(l - q, top - q * 2, r + q, top + q, blk);
        blk.setColor(0xFF9A8A80);
        c.drawRect(l, top - q, r, top, blk);
        blk.setColor(0xFFC8BCB0);
        c.drawRect(r - q * 4, top - q, r, top - q * 0.5f, blk);
    }

    /** A pixel coin that spins: four widths from face-on to edge-on, outlined, lit on the right. */
    private void drawCoin(Canvas c, float cx, float cy, int rc, double phase) {
        float q = dp(2f);
        float spin = Math.abs((float) Math.cos(phase));
        float squeeze = spin > 0.8f ? 1f : spin > 0.5f ? 0.7f : spin > 0.22f ? 0.42f : 0.16f;
        cx = Math.round(cx);
        cy = Math.round(cy);
        int topHalf = Math.max(1, Math.round((float) Math.sqrt(rc * rc - (rc - 0.5f) * (rc - 0.5f)) * squeeze));
        blk.setColor(0xFF7A5400);
        c.drawRect(cx - topHalf * q, cy - (rc + 1) * q, cx + topHalf * q, cy - rc * q, blk);
        c.drawRect(cx - topHalf * q, cy + rc * q, cx + topHalf * q, cy + (rc + 1) * q, blk);
        for (int j = -rc; j < rc; j++) {
            float yy = j + 0.5f;
            int half = Math.max(1, Math.round((float) Math.sqrt(rc * rc - yy * yy) * squeeze));
            float y0 = cy + j * q;
            float y1 = y0 + q;
            blk.setColor(0xFF7A5400);
            c.drawRect(cx - (half + 1) * q, y0, cx + (half + 1) * q, y1, blk);
            blk.setColor(0xFFF5C518);
            c.drawRect(cx - half * q, y0, cx + half * q, y1, blk);
            if (half >= 2) {
                blk.setColor(0xFFC99A10);
                c.drawRect(cx - half * q, y0, cx - (half - 1) * q, y1, blk);
                blk.setColor(0xFFFFE680);
                c.drawRect(cx + (half - 1) * q, y0, cx + half * q, y1, blk);
            }
        }
        if (squeeze >= 0.7f) {
            blk.setColor(0xFFB8890B);
            c.drawRect(cx - q * 0.5f, cy - (rc - 2) * q, cx + q * 0.5f, cy + (rc - 1) * q, blk);
            blk.setColor(0xFFFFFFFF);
            c.drawRect(cx + q, cy - (rc - 1) * q, cx + 2 * q, cy - (rc - 2) * q, blk);
        }
    }
    /** The daily's finish: a chequered banner on two posts, with bunting that sways. */
    private void drawFinishGate(Canvas c, float sx, float groundY) {
        float top = groundY - dp(180f);
        paint.setColor(0xFFDDDDDD);
        c.drawRect(sx - dp(56f), top, sx - dp(46f), groundY, paint);
        c.drawRect(sx + dp(46f), top, sx + dp(56f), groundY, paint);
        float band = dp(26f);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 8; col++) {
                paint.setColor(((row + col) & 1) == 0 ? 0xFFFFFFFF : 0xFF10171F);
                float cx0 = sx - dp(56f) + col * dp(14f);
                c.drawRect(cx0, top + row * band * 0.5f, cx0 + dp(14f), top + (row + 1) * band * 0.5f, paint);
            }
        }
        bold(c, "FINISH", sx, top + dp(48f), 16f, 0xFFFFE28A, Paint.Align.CENTER);
        for (int i = 0; i < 6; i++) {
            float bx = sx - dp(46f) + i * dp(18f);
            float sway = (float) Math.sin(sessionSeconds * 3 + i) * dp(4f);
            paint.setColor(i % 2 == 0 ? ACCENT : 0xFFF5C518);
            path.reset();
            path.moveTo(bx, top + band + sway);
            path.lineTo(bx + dp(14f), top + band + sway);
            path.lineTo(bx + dp(7f), top + band + dp(16f) + sway);
            path.close();
            c.drawPath(path, paint);
        }
        Fx.glow(c, sx, groundY - dp(40f), dp(70f), 0x3335D0BA);
    }

    /** A checkpoint: an outlined pixel pole on a stone, and a stepped pennant that runs up and waves. */
    private void drawFlag(Canvas c, Thing t, float sx, float groundY) {
        float q = dp(PIX);
        float pole = dp(100f);
        sx = Math.round(sx);
        blk.setColor(INK);
        c.drawRect(sx - q * 2, groundY - pole, sx + q, groundY, blk);
        c.drawRect(sx - q * 4, groundY - q * 3, sx + q * 3, groundY, blk);
        blk.setColor(0xFF8A8898);
        c.drawRect(sx - q * 3, groundY - q * 2, sx + q * 2, groundY, blk);
        blk.setColor(0xFFB8B6C4);
        c.drawRect(sx - q * 3, groundY - q * 2, sx + q * 2, groundY - q, blk);
        blk.setColor(0xFFDDE4EE);
        c.drawRect(sx - q, groundY - pole + q, sx, groundY - q * 3, blk);
        blk.setColor(INK);
        c.drawRect(sx - q * 3, groundY - pole - q * 3, sx + q * 2, groundY - pole + q, blk);
        blk.setColor(0xFFF5C518);
        c.drawRect(sx - q * 2, groundY - pole - q * 2, sx + q, groundY - pole, blk);
        blk.setColor(0xFFFFF2A8);
        c.drawRect(sx, groundY - pole - q * 2, sx + q, groundY - pole - q, blk);
        // Down at the foot of the pole until reached, then run up and wave.
        float up = 0f;
        if (t.raisedAt >= 0) {
            up = (float) Math.min(1.0, (sessionSeconds - t.raisedAt) / 0.6);
        }
        float top = Math.round(groundY - dp(28f) - up * (pole - dp(34f)));
        boolean raised = t.raisedAt >= 0;
        int cloth = raised ? ACCENT : 0xFFB0B8C4;
        int clothDark = Pix.shade(cloth, 0.72f);
        int clothLit = Pix.mix(cloth, 0xFFFFFFFF, 0.4f);
        // Seven rows, widest in the middle; each row slides with the wave so the cloth ripples.
        for (int r = 0; r < 7; r++) {
            int len = 12 - Math.abs(r - 3) * 3;
            float wave = Math.round((float) Math.sin(sessionSeconds * 8 + t.at - r * 0.5) * (0.3f + up)) * q;
            float y0 = top + r * q * 1.05f;
            float x0 = sx + q;
            float x1 = x0 + len * q + (r == 3 ? wave : wave * 0.5f);
            blk.setColor(INK);
            c.drawRect(x0, y0 - (r == 0 ? q : 0), x1 + q, y0 + q * 1.05f + (r == 6 ? q : 0), blk);
            blk.setColor(r <= 1 ? clothLit : r >= 5 ? clothDark : cloth);
            c.drawRect(x0, y0, x1, y0 + q * 1.05f, blk);
        }
        if (raised && sessionSeconds - t.raisedAt < 0.8) {
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

        if (!dead) {
            blk.setColor(0x40000000);
            c.drawRect(cx - 90f * s, groundY - 2f * s, cx + 100f * s, groundY + 5f * s, blk);
            c.drawRect(cx - 60f * s, groundY - 4f * s, cx + 70f * s, groundY + 7f * s, blk);
        }
        // Legs, scuttling.
        paint.setColor(INK);
        paint.setStrokeWidth(10f * s);
        for (int i = 0; i < 3; i++) {
            float ph = (float) Math.sin(now * 8 + i * 1.3) * 6f * s;
            float lx = cx - 30f * s + i * 30f * s;
            c.drawLine(lx, cy + 20f * s, lx - 22f * s, groundY + ph * 0.3f, paint);
            c.drawLine(lx + 10f * s, cy + 20f * s, lx + 34f * s, groundY - ph * 0.3f, paint);
        }
        paint.setColor(dark);
        paint.setStrokeWidth(6f * s);
        for (int i = 0; i < 3; i++) {
            float ph = (float) Math.sin(now * 8 + i * 1.3) * 6f * s;
            float lx = cx - 30f * s + i * 30f * s;
            c.drawLine(lx, cy + 20f * s, lx - 22f * s, groundY + ph * 0.3f, paint);
            c.drawLine(lx + 10f * s, cy + 20f * s, lx + 34f * s, groundY - ph * 0.3f, paint);
        }
        // Back claw, then the shell, each over a dark outline like the rest of the sprites.
        paint.setColor(INK);
        c.drawCircle(cx + 78f * s, cy - 6f * s, 21f * s, paint);
        c.drawOval(cx - 78f * s, cy - 43f * s, cx + 78f * s, cy + 39f * s, paint);
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
        paint.setColor(INK);
        paint.setStrokeWidth(18f * s);
        c.drawLine(cx - 60f * s, cy, clawX + 12f * s, clawY, paint);
        c.drawCircle(clawX, clawY, 31f * s, paint);
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

    /* Palette for drawMap: outline, body, dark, highlight, eye white, pupil, spike, wing, beak. */
    private int pO = INK;
    private int pB;
    private int pD;
    private int pH;
    private int pW = 0xFFFFFFFF;
    private int pE = INK;
    private int pS;
    private int pG;
    private int pY;

    private int mapColour(char ch) {
        switch (ch) {
            case 'o': return pO;
            case 'B': return pB;
            case 'D': return pD;
            case 'H': return pH;
            case 'W': return pW;
            case 'e': return pE;
            case 's': return pS;
            case 'G': return pG;
            case 'Y': return pY;
            default: return 0;
        }
    }

    /** Draws a small pixel map, one rectangle per run of a colour along each row. */
    private void drawMap(Canvas c, String[] map, float left, float top, float cell) {
        left = Math.round(left);
        top = Math.round(top);
        for (int r = 0; r < map.length; r++) {
            String row = map[r];
            int n = row.length();
            int start = 0;
            int cur = 0;
            for (int i = 0; i <= n; i++) {
                int col = i < n ? mapColour(row.charAt(i)) : 0;
                if (col != cur) {
                    if (cur != 0) {
                        blk.setColor(cur);
                        c.drawRect(left + start * cell, top + r * cell, left + i * cell, top + (r + 1) * cell, blk);
                    }
                    cur = col;
                    start = i;
                }
            }
        }
    }

    private void drawEnemy(Canvas c, Thing t, float sx, float groundY, int world) {
        float wobble = (float) Math.sin(sessionSeconds * 12 + t.at);
        float q = dp(PIX);
        float cx = Math.round(sx);
        // Contact shadow: a flattened, stepped oval.
        blk.setColor(0x33000000);
        c.drawRect(cx - dp(16f), groundY - dp(1f), cx + dp(16f), groundY + dp(3f), blk);
        c.drawRect(cx - dp(10f), groundY - dp(2f), cx + dp(10f), groundY + dp(4f), blk);
        pO = INK;
        pW = 0xFFFFFFFF;
        pE = INK;
        switch (t.kind) {
            case CRAB: {
                int body = CRAB_COL[world];
                pB = body;
                pD = Pix.shade(body, 0.66f);
                pH = Pix.mix(body, 0xFFFFFFFF, 0.4f);
                drawMap(c, wobble > 0 ? Sprite.CRAB_A : Sprite.CRAB_B, cx - 6 * q, groundY - 8 * q, q);
                break;
            }
            case SLIME: {
                int body = SLIME_COL[world];
                pB = body;
                pD = Pix.shade(body, 0.68f);
                pH = Pix.mix(body, 0xFFFFFFFF, 0.6f);
                String[] m = wobble > 0.25f ? Sprite.SLIME_B : Sprite.SLIME_A;
                drawMap(c, m, cx - m[0].length() * q / 2f, groundY - m.length * q, q);
                break;
            }
            default: {
                int body = SPIKY_COL[world];
                pB = body;
                pD = Pix.shade(body, 0.66f);
                pH = Pix.mix(body, 0xFFFFFFFF, 0.45f);
                pS = world == 2 ? 0xFF7F96BA : 0xFFE8EEF5;
                boolean diag = ((int) (sessionSeconds * 6 / (Math.PI / 4))) % 2 == 1;
                drawMap(c, diag ? Sprite.SPIKY_B : Sprite.SPIKY_A, cx - 5.5f * q, groundY - 11 * q, q);
                break;
            }
        }
    }

    /**
     * The runner. {@code alpha} below 0xFF draws the ghost; {@code star} cycles the colours.
     * The bought skin sets the three colours and adds its own piece of headgear.
     *
     * <p>3.25.0: a pixel sprite with an outline, a lit side and a shaded side, a real run cycle
     * (knees, elbows, the body dipping as both feet leave the line) and a tucked jump pose, posed
     * into a scratch grid and emitted as runs of one colour - no per-frame allocation.
     */
    private void drawHero(Canvas c, float x0, float feetY, float speed, boolean inAir, int alpha, boolean star) {
        drawHero(c, x0, feetY, speed, inAir, alpha, star, skinIndex, dp(PIX));
    }

    private void drawHero(Canvas c, float x0, float feetY, float speed, boolean inAir, int alpha, boolean star,
                          int wearing, float cell) {
        int skin = alpha < 0xFF ? 0 : wearing;
        int legs = star ? rainbow(sessionSeconds * 3 + 0.5) : SKIN_LEGS[skin];
        int shirt = star ? rainbow(sessionSeconds * 3) : SKIN_SHIRT[skin];
        int face = SKIN_FACE[skin];
        Sprite r = runner;
        r.o = INK;
        r.boot = 0xFF3B2A22;
        if (alpha < 0xFF) {
            legs = 0xFFCFE3FF;
            shirt = 0xFFCFE3FF;
            face = 0xFFEAF2FF;
            r.o = 0xFF7F96BA;
            r.boot = 0xFFB8CCE8;
        }
        r.palette(shirt, legs, face, skin == 4);
        r.pose(speed, inAir, legPhase, skin, sessionSeconds);
        float left = Math.round(x0 - (7 + Sprite.OX) * cell);
        float top = Math.round(feetY - (22 + Sprite.OY) * cell);
        int[] g = r.grid;
        for (int gy = 0; gy < Sprite.H; gy++) {
            int row = gy * Sprite.W;
            int start = 0;
            int cur = 0;
            for (int gx = 0; gx <= Sprite.W; gx++) {
                int col = gx < Sprite.W ? g[row + gx] : 0;
                if (col != cur) {
                    if (cur != 0) {
                        blk.setColor(alpha < 0xFF ? withAlpha(cur, alpha) : cur);
                        c.drawRect(left + start * cell, top + gy * cell, left + gx * cell, top + (gy + 1) * cell, blk);
                    }
                    cur = col;
                    start = gx;
                }
            }
        }
    }

    /**
     * Pixel painting with nothing Android in it: one colour buffer and the stamps the scenery is
     * made of. Everything is deterministic from a hash, so the same world always looks the same.
     */
    static final class Pix {
        private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
        private static final int[] FLOWERS = {0xFFF0655D, 0xFFFFE28A, 0xFFFFFFFF, 0xFFB98CFF};
        private static final int[] CACTUS = {0xFF2E6B2A, 0xFF3F8A36, 0xFF5DA846, 0xFF7FC25A};
        static final int TOP_ROWS = 5;

        int[] buf = new int[0];
        int cols;
        int rows;

        void begin(int c, int r) {
            cols = c;
            rows = r;
            int n = c * r;
            if (buf.length < n) {
                buf = new int[n];
            } else {
                java.util.Arrays.fill(buf, 0, n, 0);
            }
        }

        /** Sets a pixel; x wraps, so anything stamped across a tile's edge meets itself on the other side. */
        void put(int x, int y, int col) {
            if (y < 0 || y >= rows) {
                return;
            }
            int xx = x % cols;
            if (xx < 0) {
                xx += cols;
            }
            buf[y * cols + xx] = col;
        }

        int get(int x, int y) {
            if (y < 0 || y >= rows) {
                return 0;
            }
            int xx = x % cols;
            if (xx < 0) {
                xx += cols;
            }
            return buf[y * cols + xx];
        }

        static int shade(int c, float f) {
            int r = Math.min(255, Math.max(0, (int) (((c >> 16) & 0xFF) * f)));
            int g = Math.min(255, Math.max(0, (int) (((c >> 8) & 0xFF) * f)));
            int b = Math.min(255, Math.max(0, (int) ((c & 0xFF) * f)));
            return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
        }

        static int mix(int a, int b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int aa = a >>> 24;
            int ab = b >>> 24;
            int r = (int) (((a >> 16) & 0xFF) + ((((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t));
            int g = (int) (((a >> 8) & 0xFF) + ((((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t));
            int bl = (int) ((a & 0xFF) + (((b & 0xFF) - (a & 0xFF)) * t));
            int al = (int) (aa + (ab - aa) * t);
            return (al << 24) | (r << 16) | (g << 8) | bl;
        }

        static int hash(int x, int y) {
            int h = x * 374761393 + y * 668265263;
            h = (h ^ (h >>> 13)) * 1274126177;
            return h ^ (h >>> 16);
        }

        static float hash01(int x, int y) {
            return (hash(x, y) & 0xFFFFFF) / 16777216f;
        }

        /** Ordered (Bayer 4x4) dither threshold for a pixel, 0..1. */
        static float bayer(int x, int y) {
            return (BAYER[((y & 3) << 2) | (x & 3)] + 0.5f) / 16f;
        }

        /** A sine that comes round exactly at the tile edge, so a tiled layer has no seam. */
        float wave(int x, int cycles, float phase) {
            return (float) Math.sin(6.2831853 * x * cycles / cols + phase);
        }

        /** Banded sky: nine flat bands from zenith to horizon, each dithered into the next. */
        void sky(int top, int bot, float osRows, float skyRows, boolean stars) {
            final int bands = 9;
            for (int y = 0; y < rows; y++) {
                float t = Math.max(0f, Math.min(0.9999f, (y - osRows) / skyRows));
                t = t * 0.7f + t * t * 0.3f;
                float f = t * (bands - 1);
                int lo = (int) f;
                float frac = f - lo;
                int cLo = mix(top, bot, lo / (float) (bands - 1));
                int cHi = mix(top, bot, Math.min(1f, (lo + 1) / (float) (bands - 1)));
                float d = frac < 0.55f ? 0f : (frac - 0.55f) / 0.45f;
                int row = y * cols;
                for (int x = 0; x < cols; x++) {
                    buf[row + x] = bayer(x, y) < d ? cHi : cLo;
                }
            }
            if (!stars) {
                return;
            }
            int starRows = (int) (rows * 0.7f);
            for (int y = 0; y < starRows; y++) {
                for (int x = 0; x < cols; x++) {
                    float p = hash01(x, y * 7 + 3);
                    if (p < 0.0022f) {
                        buf[y * cols + x] = 0xFFF4F0FF;
                        if (p < 0.0005f) {
                            put(x - 1, y, 0xFF8E84B8);
                            put(x + 1, y, 0xFF8E84B8);
                            put(x, y - 1, 0xFF8E84B8);
                            put(x, y + 1, 0xFF8E84B8);
                        }
                    } else if (p < 0.006f) {
                        buf[y * cols + x] = 0xFF6E64A0;
                    }
                }
            }
        }

        /**
         * A mountain range: jagged peaks, lit faces to the right of each summit and shaded faces to
         * the left with a wandering crest line, snow caps, gullies, and haze dithered in at the foot.
         * The far range is pulled most of the way to the horizon colour.
         */
        void range(int base, int cap, int haze, boolean far, int seed) {
            float hz = far ? 0.55f : 0.2f;
            int lit = mix(shade(base, far ? 1.06f : 1.12f), haze, hz);
            int dark = mix(shade(base, far ? 0.84f : 0.7f), haze, hz);
            int rim = mix(shade(base, 1.35f), haze, hz * 0.7f);
            int capLit = mix(cap, haze, hz * 0.5f);
            int capDark = mix(mix(shade(cap, 0.8f), 0xFF7F96C8, 0.2f), haze, hz * 0.5f);
            int capRim = mix(shade(cap, 1.12f), 0xFFFFFFFF, 0.3f);
            int peaks = Math.max(3, cols / (far ? 64 : 50));
            float[] pxs = new float[peaks];
            float[] phs = new float[peaks];
            float[] psl = new float[peaks];
            for (int i = 0; i < peaks; i++) {
                pxs[i] = (i + 0.15f + 0.7f * hash01(i, seed)) * cols / peaks;
                phs[i] = rows * ((far ? 0.58f : 0.42f) + (far ? 0.4f : 0.5f) * hash01(i, seed + 1));
                psl[i] = 0.7f + 0.6f * hash01(i, seed + 2);
            }
            float half = cols / 2f;
            int ridgeCycles = Math.max(1, cols / 27);
            for (int x = 0; x < cols; x++) {
                float best = -1e9f;
                float sdx = 0f;
                int bi = 0;
                for (int i = 0; i < peaks; i++) {
                    float dx = x - pxs[i];
                    if (dx > half) {
                        dx -= cols;
                    } else if (dx < -half) {
                        dx += cols;
                    }
                    float hgt = phs[i] - Math.abs(dx) * psl[i];
                    if (hgt > best) {
                        best = hgt;
                        sdx = dx;
                        bi = i;
                    }
                }
                float ridge = best + wave(x, ridgeCycles, seed) * 1.3f + ((hash(x, seed) & 3) - 1.5f) * 0.5f;
                int rTop = rows - Math.max(0, Math.round(ridge));
                float snow = phs[bi] * (far ? 0.64f : 0.7f) + ((hash(x, seed + 9) & 3) - 1.5f);
                for (int y = Math.max(0, rTop); y < rows; y++) {
                    int hy = rows - 1 - y;
                    int fromTop = y - rTop;
                    float edge = sdx + (float) Math.sin(hy * 0.45f + bi * 1.7f) * 1.6f;
                    boolean litSide = edge >= 0f;
                    boolean snowy = hy > snow;
                    int col = snowy ? (litSide ? capLit : capDark) : (litSide ? lit : dark);
                    if (!snowy && !far && (hash(x >> 1, y >> 1) & 15) == 0) {
                        col = shade(col, 0.9f);
                    }
                    if (!snowy && !litSide && !far && ((((int) -edge) + hy) % 7) == 0) {
                        col = shade(col, 0.86f);
                    }
                    if (fromTop == 0 && litSide) {
                        col = snowy ? capRim : rim;
                    }
                    float fog = 1f - hy / (rows * (far ? 0.6f : 0.4f));
                    if (fog > 0f && bayer(x, y) < fog * (far ? 0.95f : 0.7f)) {
                        col = mix(col, haze, far ? 0.55f : 0.35f);
                    }
                    buf[y * cols + x] = col;
                }
            }
        }

        /**
         * Rolling hills lit from the upper right in four dithered shades with a bright rim; trees
         * and bushes on the meadow, long dunes in the desert, pines in the snow, dead trees and
         * graves by the castle.
         */
        void hills(int kind, int base, int seed) {
            int light = kind == 2 ? 0xFFFFFFFF : kind == 0 ? mix(shade(base, 1.2f), 0xFFE8F08A, 0.2f) : shade(base, 1.15f);
            int rim = kind == 2 ? 0xFFFFFFFF : shade(light, 1.12f);
            int dark = kind == 2 ? mix(base, 0xFF9AB4D8, 0.4f) : shade(base, 0.8f);
            int deep = kind == 2 ? mix(base, 0xFF7F9CC8, 0.6f) : shade(base, 0.64f);
            float stretch = kind == 1 ? 1.9f : 1f;
            int n = Math.max(4, (int) (cols / (34f * stretch)));
            float[] hx = new float[n];
            float[] hr = new float[n];
            for (int i = 0; i < n; i++) {
                hx[i] = (i + 0.6f * hash01(i, seed)) * cols / n;
                hr[i] = rows * (0.42f + 0.48f * hash01(i, seed + 1));
            }
            int[] topAt = new int[cols];
            float half = cols / 2f;
            for (int x = 0; x < cols; x++) {
                float best = -1e9f;
                float bdx = 0f;
                float br = 1f;
                for (int i = 0; i < n; i++) {
                    float dx = x - hx[i];
                    if (dx > half) {
                        dx -= cols;
                    } else if (dx < -half) {
                        dx += cols;
                    }
                    float ex = dx / stretch;
                    if (Math.abs(ex) >= hr[i]) {
                        continue;
                    }
                    float hgt = (float) Math.sqrt(hr[i] * hr[i] - ex * ex) - hr[i] * 0.3f;
                    if (hgt > best) {
                        best = hgt;
                        bdx = ex;
                        br = hr[i];
                    }
                }
                if (best <= 0f) {
                    topAt[x] = rows;
                    continue;
                }
                int rTop = rows - Math.round(best);
                topAt[x] = rTop;
                for (int y = Math.max(0, rTop); y < rows; y++) {
                    int hy = rows - 1 - y;
                    float nx = bdx / br;
                    float ny = (hy + br * 0.3f) / br;
                    float v = nx * 0.55f + ny * 0.83f + (bayer(x, y) - 0.5f) * 0.18f;
                    int col = v > 0.8f ? light : v > 0.45f ? base : v > 0.12f ? dark : deep;
                    if (y == rTop && nx > -0.35f) {
                        col = rim;
                    }
                    if (kind != 2 && (hash(x, y + seed) & 31) == 0) {
                        col = shade(col, 0.86f);
                    }
                    buf[y * cols + x] = col;
                }
            }
            for (int i = 0; i < n; i++) {
                float roll = hash01(i, seed + 5);
                int tx = Math.round(hx[i] + (hash01(i, seed + 4) - 0.5f) * hr[i] * 0.8f * stretch);
                tx = ((tx % cols) + cols) % cols;
                int ty = topAt[tx];
                if (ty >= rows - 3) {
                    continue;
                }
                ty += 1;
                switch (kind) {
                    case 0:
                        if (roll < 0.6f) {
                            tree(tx, ty, 3 + (int) (roll * 4));
                        } else {
                            bush(tx, ty, 3);
                        }
                        if (roll > 0.3f) {
                            int t2 = ((tx + 6 + (int) (roll * 4)) % cols + cols) % cols;
                            if (topAt[t2] < rows - 3) {
                                tree(t2, topAt[t2] + 1, 3);
                            }
                        }
                        break;
                    case 1:
                        if (roll < 0.5f) {
                            boulder(tx, ty, 2, 0xFFB98A52);
                        }
                        break;
                    case 2:
                        pine(tx, ty, 8 + (int) (roll * 6), true);
                        break;
                    default:
                        if (roll < 0.5f) {
                            deadTree(tx, ty, 7 + (int) (roll * 6));
                        } else {
                            grave(tx, ty);
                        }
                        break;
                }
            }
        }

        /** The row of props in front of the hills, outlined like sprites. */
        void props(int kind, int seed) {
            int slot = 77;
            int base = rows - 2;
            for (int i = 0; i * slot < cols; i++) {
                int hh = hash(i, seed);
                int x = i * slot + ((hh >>> 4) & 31);
                float p = (hh & 0xFF) / 256f;
                switch (kind) {
                    case 0:
                        if (p < 0.55f) {
                            tree(x, base, 7 + ((hh >>> 10) & 3));
                            if ((hh & 0x100) != 0) {
                                bush(x + 11, base, 3);
                            }
                        } else if (p < 0.8f) {
                            bush(x, base, 4);
                            bush(x + 7, base, 3);
                        } else {
                            fence(x, base, 22);
                        }
                        break;
                    case 1:
                        if (p < 0.7f) {
                            cactus(x, base, 14 + ((hh >>> 10) & 7), hh);
                        } else {
                            boulder(x, base, 4, 0xFFB07E4A);
                            boulder(x + 7, base, 2, 0xFFB07E4A);
                        }
                        break;
                    case 2:
                        if (p < 0.78f) {
                            pine(x, base, 22 + ((hh >>> 10) & 7), true);
                        } else {
                            snowman(x, base);
                        }
                        break;
                    default:
                        break;
                }
            }
            outline(0xFF1B1626);
        }

        /**
         * The ground tile. Rows 0..TOP_ROWS-1 sit above the ground line (blades, flowers, drifts);
         * then a five-row top band with a lit edge and a dark lip, grass dripping over the lip, and
         * four wavy strata dithered into each other, darkening with depth; then the finds.
         */
        void ground(int kind, int top, int soilA, int soilB, int tuftA, int tuftB, int seed) {
            final int t0 = TOP_ROWS;
            int depth = rows - t0;
            int topLight = kind == 2 ? 0xFFFFFFFF : shade(top, 1.18f);
            int topDark = kind == 2 ? 0xFFB4C8DE : shade(top, 0.74f);
            int topDeep = kind == 2 ? 0xFF94AECB : shade(top, 0.56f);
            int l0 = shade(soilA, 1.06f);
            int l1 = shade(soilB, 0.9f);
            int l2 = shade(mix(soilA, soilB, 0.5f), 0.76f);
            int l3 = shade(soilB, 0.6f);
            for (int x = 0; x < cols; x++) {
                float b1 = 7 + depth * 0.16f + wave(x, 9, seed) * 2.2f + wave(x, 23, 1f) * 1.1f;
                float b2 = depth * 0.42f + wave(x, 7, seed + 2.1f) * 2.6f + wave(x, 31, 1f) * 0.9f;
                float b3 = depth * 0.7f + wave(x, 5, 4.2f) * 3f;
                for (int gy = 0; gy < depth; gy++) {
                    int y = gy + t0;
                    int col;
                    if (gy == 0) {
                        col = topLight;
                    } else if (gy <= 3) {
                        col = (hash(x, gy + seed) & 7) == 0 ? (gy == 1 ? topLight : topDark) : top;
                        if (kind == 3 && x % 12 == 0) {
                            col = topDark;   // seams between the flagstones
                        }
                    } else if (gy == 4) {
                        col = topDark;
                    } else {
                        float g = gy + (bayer(x, y) - 0.5f) * 1.8f;
                        int li = g < b1 ? 0 : g < b2 ? 1 : g < b3 ? 2 : 3;
                        col = li == 0 ? l0 : li == 1 ? l1 : li == 2 ? l2 : l3;
                        int hs = hash(x, y + seed);
                        int bits = hs & 63;
                        if (bits < 3) {
                            col = shade(col, 1.16f);
                        } else if (bits < 7) {
                            col = shade(col, 0.84f);
                        }
                        if ((Math.abs(gy - b1) < 0.7f || Math.abs(gy - b2) < 0.7f || Math.abs(gy - b3) < 0.7f)
                                && ((hs >>> 8) & 3) == 0) {
                            col = mix(col, 0xFFA89C8C, 0.5f);   // a pebbly seam between strata
                        }
                        float deep = (gy - depth * 0.62f) / (depth * 0.38f);
                        if (deep > 0f && bayer(x + 2, y + 1) < deep * 0.55f) {
                            col = shade(col, 0.85f);
                        }
                    }
                    buf[y * cols + x] = col;
                }
            }
            if (kind == 3) {
                bricks(soilA, soilB, seed);
            } else {
                finds(kind, seed, depth);
            }
            for (int x = 0; x < cols; x++) {
                int hh = hash(x, seed + 21);
                float p = (hh & 0xFFFF) / 65536f;
                if (kind == 0) {
                    if (p < 0.5f) {
                        int hgt = 1 + ((hh >>> 16) & 3);
                        int col = (x & 1) == 0 ? tuftA : tuftB;
                        for (int j = 0; j < hgt; j++) {
                            put(x, t0 - 1 - j, j == hgt - 1 ? shade(tuftB, 1.15f) : col);
                        }
                    } else if (p < 0.535f) {
                        int fc = FLOWERS[(hh >>> 18) & 3];
                        put(x, t0 - 1, tuftA);
                        put(x, t0 - 2, tuftA);
                        put(x - 1, t0 - 3, fc);
                        put(x + 1, t0 - 3, fc);
                        put(x, t0 - 4, fc);
                        put(x, t0 - 3, 0xFFFFC84A);
                    }
                } else if (kind == 1) {
                    if (p < 0.12f) {
                        put(x, t0 - 1, shade(top, 1.1f));
                    } else if (p < 0.15f) {
                        put(x, t0 - 1, 0xFF9A7048);
                    } else if (p < 0.2f) {
                        int hgt = 1 + ((hh >>> 16) & 1);
                        for (int j = 0; j < hgt; j++) {
                            put(x, t0 - 1 - j, j == hgt - 1 ? tuftB : tuftA);
                        }
                    }
                } else if (kind == 2) {
                    float d = 1.3f + wave(x, 11, seed) * 1.0f + wave(x, 29, 0.5f) * 0.6f;
                    int hgt = Math.max(0, Math.min(3, Math.round(d)));
                    for (int j = 0; j < hgt; j++) {
                        put(x, t0 - 1 - j, j == hgt - 1 ? 0xFFFFFFFF : 0xFFE6EEF8);
                    }
                    if (p < 0.03f) {
                        put(x, t0 - 1 - hgt, 0xFF8A8060);
                        put(x, t0 - 2 - hgt, 0xFF8A8060);
                    }
                } else {
                    if (p < 0.06f) {
                        put(x, t0 - 1, 0xFF3E5A34);
                        if (p < 0.03f) {
                            put(x, t0 - 2, 0xFF4E6E3A);
                        }
                    } else if (p < 0.09f) {
                        put(x, t0 - 1, shade(top, 0.8f));
                    }
                }
                if (kind != 3) {
                    int dl = (hh >>> 24) & 7;
                    int len = dl == 0 ? 3 : dl == 1 ? 2 : dl == 2 ? 1 : 0;
                    for (int j = 0; j < len; j++) {
                        put(x, t0 + 5 + j, j == len - 1 ? topDeep : topDark);
                    }
                }
            }
        }

        /** Rocks, ore, fossils, buried coins, ice and roots, scattered through the strata. */
        private void finds(int kind, int seed, int depth) {
            final int t0 = TOP_ROWS;
            int cw = 22;
            int ch = 12;
            int ncx = cols / cw;
            int rockCol = kind == 1 ? 0xFFA0784A : kind == 2 ? 0xFF6E7F94 : 0xFF857868;
            for (int cy = 0; t0 + 10 + cy * ch < rows - 4; cy++) {
                for (int ci = 0; ci < ncx; ci++) {
                    int hh = hash(ci * 31 + seed, cy * 17 + 5);
                    float p = (hh & 0xFFFF) / 65536f;
                    int fx = ci * cw + 3 + ((hh >>> 16) & 15);
                    int fy = t0 + 10 + cy * ch + ((hh >>> 20) & 7);
                    if (fy > rows - 4) {
                        continue;
                    }
                    float frac = (fy - t0) / (float) depth;
                    if (p < 0.2f) {
                        rock(fx, fy, 1 + ((hh >>> 24) & 3), rockCol);
                    } else if (p < 0.27f && frac > 0.3f) {
                        ore(fx, fy, frac > 0.65f ? 1 + ((hh >>> 24) & 1) : 0, kind);
                    } else if (p < 0.285f) {
                        fossil(fx, fy, (hh >>> 24) & 1);
                    } else if (p < 0.315f) {
                        buriedCoin(fx, fy);
                    } else if (p < 0.37f && kind == 2) {
                        ice(fx, fy);
                    }
                }
            }
            if (kind > 1) {
                return;
            }
            int root = kind == 0 ? 0xFF5E3E1C : 0xFF8A6A3A;
            int rootLit = shade(root, 1.3f);
            for (int rx = 0; rx < cols; rx += 29) {
                int hh = hash(rx, seed + 11);
                if ((hh & 3) == 0) {
                    continue;
                }
                int len = 6 + ((hh >>> 4) & 15);
                int x = rx + ((hh >>> 8) & 15);
                int y = t0 + 4;
                int dir = (hh & 16) != 0 ? 1 : -1;
                for (int j = 0; j < len; j++) {
                    put(x, y, root);
                    if (j < len / 2) {
                        put(x + 1, y, rootLit);
                    }
                    int st = hash(x, y + j) & 3;
                    if (st == 0) {
                        x--;
                    } else if (st == 1) {
                        x++;
                    }
                    y++;
                    if (j == len / 2) {
                        int bx = x;
                        int by = y;
                        for (int k = 0; k < 4; k++) {
                            bx += dir;
                            if ((k & 1) == 0) {
                                by++;
                            }
                            put(bx, by, root);
                        }
                    }
                }
            }
        }

        /** Castle floor: staggered brick courses, lit on top and right, mortar, moss along the top. */
        private void bricks(int a, int b, int seed) {
            final int t0 = TOP_ROWS;
            int mortar = shade(b, 0.55f);
            int perRow = Math.max(1, cols / 16);
            for (int y = t0 + 5; y < rows; y++) {
                int gy = y - t0 - 5;
                int course = gy / 8;
                int inC = gy % 8;
                int off = (course & 1) * 8;
                for (int x = 0; x < cols; x++) {
                    int bx = (x + off) % 16;
                    int id = ((x + off) / 16) % perRow;
                    int col;
                    if (inC == 7 || bx == 15) {
                        col = mortar;
                    } else {
                        col = shade(mix(a, b, hash01(id, course + seed)), 0.92f + 0.14f * hash01(id + 7, course));
                        if (inC == 0) {
                            col = shade(col, 1.16f);
                        } else if (inC == 6) {
                            col = shade(col, 0.84f);
                        }
                        if (bx == 14) {
                            col = shade(col, 1.08f);
                        } else if (bx == 0) {
                            col = shade(col, 0.88f);
                        }
                        if ((hash(x, y) & 31) == 0) {
                            col = shade(col, 0.88f);
                        }
                    }
                    float deep = (gy - (rows - t0) * 0.55f) / ((rows - t0) * 0.45f);
                    if (deep > 0f && bayer(x + 1, y) < deep * 0.6f) {
                        col = shade(col, 0.84f);
                    }
                    buf[y * cols + x] = col;
                }
            }
            for (int x = 0; x < cols; x++) {
                int hh = hash(x, seed + 3);
                if ((hh & 7) == 0) {
                    put(x, t0 + 5, 0xFF4E6E3A);
                    if ((hh & 8) != 0) {
                        put(x, t0 + 6, 0xFF3E5A34);
                    }
                }
            }
        }

        /** A pixel cloud: three puffs on a flat base, lit on top, shaded underneath. */
        void cloud(int k, int base, int skyTop, boolean night) {
            int light = night ? shade(base, 1.35f) : 0xFFFFFFFF;
            int shadeC = mix(base, skyTop, 0.3f);
            int deep = mix(base, skyTop, 0.5f);
            float[] bx = {0.24f, 0.5f, 0.77f};
            float[] br = {0.5f, 0.85f, 0.56f};
            float[] cx = new float[3];
            float[] cy = new float[3];
            float[] rr = new float[3];
            for (int i = 0; i < 3; i++) {
                rr[i] = br[i] * rows * (0.58f + 0.1f * hash01(k, i));
                cx[i] = bx[i] * cols + (hash01(k + 5, i) - 0.5f) * 2f;
                cy[i] = rows - 1 - rr[i] * 0.82f;
            }
            float left = cx[0] - rr[0] * 0.8f;
            float right = cx[2] + rr[2] * 0.8f;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    int bi = -1;
                    float bestIn = 0f;
                    for (int i = 0; i < 3; i++) {
                        float dx = x + 0.5f - cx[i];
                        float dy = y + 0.5f - cy[i];
                        float in = 1f - (float) Math.sqrt(dx * dx + dy * dy) / rr[i];
                        if (in > bestIn) {
                            bestIn = in;
                            bi = i;
                        }
                    }
                    boolean flat = y >= rows - 3 && x >= left && x <= right;
                    if (bi < 0 && !flat) {
                        continue;
                    }
                    int col;
                    if (y == rows - 1) {
                        col = deep;
                    } else if (y == rows - 2) {
                        col = shadeC;
                    } else if (bi < 0) {
                        col = base;
                    } else {
                        float v = ((x - cx[bi]) * 0.45f + (cy[bi] - y) * 0.9f) / rr[bi] + (bayer(x, y) - 0.5f) * 0.3f;
                        col = v > 0.42f ? light : v > -0.2f ? base : shadeC;
                    }
                    buf[y * cols + x] = col;
                }
            }
        }

        static int cloudCols(int k) {
            return k == 1 ? 46 : k == 0 ? 32 : 22;
        }

        static int cloudRows(int k) {
            return k == 1 ? 14 : k == 0 ? 11 : 8;
        }

        /** Any empty pixel touching a filled one becomes {@code col}: a one-pixel outline. */
        void outline(int col) {
            final int mark = 0x00000001;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    int i = y * cols + x;
                    if ((buf[i] >>> 24) != 0) {
                        continue;
                    }
                    if (solid(x - 1, y) || solid(x + 1, y) || solid(x, y - 1) || solid(x, y + 1)) {
                        buf[i] = mark;
                    }
                }
            }
            int n = cols * rows;
            for (int i = 0; i < n; i++) {
                if (buf[i] == mark) {
                    buf[i] = col;
                }
            }
        }

        private boolean solid(int x, int y) {
            return (get(x, y) >>> 24) != 0;
        }

        private static boolean inCanopy(int dx, int dy, int r, float r2, float ox, float oy) {
            if (dx * dx + dy * dy <= r * r + r * 0.6f) {
                return true;
            }
            float ax = dx + ox;
            float ay = dy - oy;
            if (ax * ax + ay * ay <= r2 * r2) {
                return true;
            }
            float bx = dx - ox;
            return bx * bx + ay * ay <= r2 * r2;
        }

        /** A round-topped oak: a trunk and a three-lump canopy in three greens with a dark rim. */
        void tree(int cx, int baseY, int r) {
            int ccy = baseY - r - Math.max(2, r / 2);
            for (int y = ccy; y <= baseY; y++) {
                put(cx, y, 0xFF6E4622);
                if (r >= 5) {
                    put(cx - 1, y, 0xFF4A2E16);
                }
            }
            if (r >= 6) {
                put(cx - 2, baseY, 0xFF4A2E16);
                put(cx + 1, baseY, 0xFF6E4622);
            }
            float r2 = r * 0.65f;
            float ox = r * 0.62f;
            float oy = r * 0.35f;
            for (int dy = -r - 1; dy <= r + 1; dy++) {
                for (int dx = -r - 3; dx <= r + 3; dx++) {
                    if (!inCanopy(dx, dy, r, r2, ox, oy)) {
                        continue;
                    }
                    boolean edge = !inCanopy(dx - 1, dy, r, r2, ox, oy) || !inCanopy(dx + 1, dy, r, r2, ox, oy)
                            || !inCanopy(dx, dy - 1, r, r2, ox, oy) || !inCanopy(dx, dy + 1, r, r2, ox, oy);
                    float v = (dx * 0.55f - dy * 0.83f) / r + (bayer(cx + dx, ccy + dy) - 0.5f) * 0.35f;
                    int col = edge ? (v > 0.2f ? 0xFF3F9442 : 0xFF1E4A26)
                            : v > 0.4f ? 0xFF74C85E : v > -0.2f ? 0xFF46A043 : 0xFF2F7A35;
                    put(cx + dx, ccy + dy, col);
                }
            }
            int hh = hash(cx, baseY);
            put(cx + r / 3, ccy - r / 2, 0xFFA8E07A);
            if ((hh & 1) == 0) {
                put(cx + r / 2, ccy - r / 4, 0xFFA8E07A);
            }
        }

        void bush(int cx, int baseY, int r) {
            for (int dy = -r; dy <= 0; dy++) {
                for (int dx = -r - 1; dx <= r + 1; dx++) {
                    float e = dx * dx * 0.7f + dy * dy;
                    if (e > r * r + 0.5f) {
                        continue;
                    }
                    boolean edge = e > (r - 1) * (r - 1) + 0.5f;
                    float v = (dx * 0.55f - dy * 0.83f) / r + (bayer(cx + dx, baseY + dy) - 0.5f) * 0.3f;
                    int col = edge ? 0xFF24582C : v > 0.5f ? 0xFF74C85E : v > 0f ? 0xFF46A043 : 0xFF2F7A35;
                    put(cx + dx, baseY + dy, col);
                }
            }
        }

        /** A stacked-tier pine, lit on the right, with snow on each tier when {@code snow}. */
        void pine(int cx, int baseY, int hgt, boolean snow) {
            put(cx, baseY, 0xFF4A3020);
            put(cx, baseY - 1, 0xFF4A3020);
            put(cx + 1, baseY, 0xFF6A4630);
            put(cx + 1, baseY - 1, 0xFF6A4630);
            int top = baseY - hgt;
            int cap = hgt / 3 + 1;
            for (int j = 0; j < hgt - 1; j++) {
                int y = top + j;
                int within = j % 4;
                int halfW = Math.min(j / 4 + within, cap);
                for (int dx = -halfW; dx <= halfW + 1; dx++) {
                    boolean lit = dx > 0;
                    int col = lit ? 0xFF2F6B4A : 0xFF1F4A3A;
                    if (dx == -halfW || dx == halfW + 1) {
                        col = 0xFF173A2E;
                    }
                    if (snow && within <= 1 && dx >= -halfW / 2 && dx <= halfW) {
                        col = lit ? 0xFFFFFFFF : 0xFFCFE0F0;
                    }
                    put(cx + dx, y, col);
                }
            }
        }

        void cactus(int cx, int baseY, int hgt, int hh) {
            int top = baseY - hgt;
            for (int y = top; y <= baseY; y++) {
                for (int i = 0; i < 4; i++) {
                    if (y == top && (i == 0 || i == 3)) {
                        continue;
                    }
                    int col = CACTUS[i];
                    if (i == 1 && (y % 3) == 0) {
                        col = CACTUS[0];
                    }
                    put(cx - 1 + i, y, col);
                }
                if (y % 4 == 0 && y > top + 1) {
                    put(cx - 2, y, 0xFFE8F0C0);
                    put(cx + 3, y, 0xFFE8F0C0);
                }
            }
            int a1 = baseY - (int) (hgt * 0.45f);
            for (int i = 0; i < 3; i++) {
                put(cx - 2 - i, a1, CACTUS[1]);
                put(cx - 2 - i, a1 + 1, CACTUS[0]);
            }
            for (int y = a1 - 5; y <= a1 + 1; y++) {
                put(cx - 5, y, CACTUS[0]);
                put(cx - 4, y, y == a1 - 5 ? CACTUS[1] : CACTUS[2]);
            }
            int a2 = baseY - (int) (hgt * 0.62f);
            for (int i = 0; i < 2; i++) {
                put(cx + 3 + i, a2, CACTUS[2]);
                put(cx + 3 + i, a2 + 1, CACTUS[1]);
            }
            for (int y = a2 - 4; y <= a2 + 1; y++) {
                put(cx + 5, y, CACTUS[2]);
                put(cx + 6, y, y == a2 - 4 ? CACTUS[2] : CACTUS[3]);
            }
            if ((hh & 1) == 0) {
                put(cx, top - 1, 0xFFF06AA0);
                put(cx + 1, top - 1, 0xFFFFB0D0);
            }
        }

        void boulder(int cx, int baseY, int r, int base) {
            int lit = shade(base, 1.25f);
            int dark = shade(base, 0.7f);
            int rx = r + 2;
            for (int dy = -r; dy <= 0; dy++) {
                for (int dx = -rx; dx <= rx; dx++) {
                    float nx = dx / (float) rx;
                    float ny = (dy + 0.5f) / (float) r;
                    if (nx * nx + ny * ny > 1.05f) {
                        continue;
                    }
                    float v = nx * 0.6f - ny * 0.8f + (bayer(cx + dx, baseY + dy) - 0.5f) * 0.3f;
                    put(cx + dx, baseY + dy, v > 0.45f ? lit : v > -0.15f ? base : dark);
                }
            }
        }

        void snowman(int cx, int baseY) {
            for (int part = 0; part < 2; part++) {
                int r = part == 0 ? 4 : 3;
                int ccy = part == 0 ? baseY - 3 : baseY - 9;
                for (int dy = -r; dy <= r; dy++) {
                    for (int dx = -r; dx <= r; dx++) {
                        if (dx * dx + dy * dy > r * r + 1) {
                            continue;
                        }
                        float v = (dx * 0.6f - dy * 0.8f) / r;
                        put(cx + dx, ccy + dy, v > -0.2f ? 0xFFFFFFFF : 0xFFC4D6EA);
                    }
                }
            }
            put(cx - 1, baseY - 10, 0xFF1B1626);
            put(cx + 1, baseY - 10, 0xFF1B1626);
            put(cx + 1, baseY - 9, 0xFFF08A2A);
            put(cx + 2, baseY - 9, 0xFFF08A2A);
            for (int dx = -3; dx <= 3; dx++) {
                put(cx + dx, baseY - 6, 0xFFE84C3D);
            }
            put(cx - 3, baseY - 5, 0xFFE84C3D);
            for (int dx = -2; dx <= 2; dx++) {
                put(cx + dx, baseY - 13, 0xFF1B1626);
            }
            for (int dy = 14; dy <= 16; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    put(cx + dx, baseY - dy, 0xFF2A2436);
                }
            }
        }

        void fence(int x0, int baseY, int len) {
            for (int x = x0; x <= x0 + len; x++) {
                put(x, baseY - 5, 0xFFB07A45);
                put(x, baseY - 2, 0xFF9A6A38);
            }
            for (int x = x0; x <= x0 + len; x += 5) {
                for (int y = baseY - 7; y <= baseY; y++) {
                    put(x, y, 0xFF8A5A2E);
                    put(x + 1, y, 0xFFC08A52);
                }
            }
        }

        void deadTree(int cx, int baseY, int hgt) {
            int col = 0xFF120D1C;
            for (int y = baseY - hgt; y <= baseY; y++) {
                put(cx, y, col);
            }
            put(cx + 1, baseY, col);
            put(cx - 1, baseY, col);
            int b1 = baseY - hgt / 2;
            for (int k = 1; k <= 3; k++) {
                put(cx - k, b1 - k, col);
            }
            int b2 = baseY - hgt * 3 / 4;
            for (int k = 1; k <= 3; k++) {
                put(cx + k, b2 - k + 1, col);
            }
            put(cx + 3, b2 - 3, col);
        }

        void grave(int cx, int baseY) {
            for (int y = baseY - 4; y <= baseY; y++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (y == baseY - 4 && dx != 0) {
                        continue;
                    }
                    put(cx + dx, y, dx == 1 ? 0xFF6A6488 : 0xFF4A4460);
                }
            }
        }

        void rock(int cx, int cy, int r, int base) {
            int lit = shade(base, 1.22f);
            int dark = shade(base, 0.62f);
            int rx = r + 1;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -rx; dx <= rx; dx++) {
                    float nx = dx / (float) rx;
                    float ny = dy / (float) Math.max(1, r);
                    float e = nx * nx + ny * ny;
                    if (e > 1.05f) {
                        continue;
                    }
                    int col = e > 0.6f && (nx < 0.2f || ny > 0.2f) ? dark : (nx > 0.1f && ny < -0.1f) ? lit : base;
                    put(cx + dx, cy + dy, col);
                }
            }
            put(cx + rx / 2, cy - r / 2, 0xFFE8E0D4);
        }

        /** A gold nugget (shallow), or a cut gem (deep): a glint, a dark underside. */
        void ore(int cx, int cy, int kind, int world) {
            if (kind == 0) {
                put(cx, cy, 0xFFE8B830);
                put(cx + 1, cy, 0xFFF5C518);
                put(cx - 1, cy + 1, 0xFFC99A10);
                put(cx, cy + 1, 0xFFE8B830);
                put(cx + 1, cy + 1, 0xFF8A6410);
                put(cx + 2, cy - 1, 0xFFE8B830);
                put(cx + 1, cy - 1, 0xFFFFF4B0);
                return;
            }
            int body = world == 2 ? 0xFF6F8CFF : kind == 1 ? 0xFF5FD8F0 : 0xFFF0607A;
            int dark = shade(body, 0.6f);
            put(cx, cy - 1, body);
            put(cx - 1, cy, dark);
            put(cx, cy, body);
            put(cx + 1, cy, shade(body, 1.2f));
            put(cx, cy + 1, dark);
            put(cx + 1, cy - 1, 0xFFFFFFFF);
        }

        void fossil(int cx, int cy, int which) {
            String[] map = which == 0 ? Sprite.FOSSIL_FISH : Sprite.AMMONITE;
            for (int r = 0; r < map.length; r++) {
                String row = map[r];
                for (int i = 0; i < row.length(); i++) {
                    char ch = row.charAt(i);
                    if (ch == 'b') {
                        put(cx + i, cy + r, 0xFFE6DCC4);
                    } else if (ch == 'd') {
                        put(cx + i, cy + r, 0xFFB0A486);
                    }
                }
            }
        }

        void buriedCoin(int cx, int cy) {
            put(cx, cy - 1, 0xFFC9A227);
            put(cx + 1, cy - 1, 0xFFC9A227);
            put(cx - 1, cy, 0xFFC9A227);
            put(cx, cy, 0xFFF5C518);
            put(cx + 1, cy, 0xFFFFE680);
            put(cx + 2, cy, 0xFFC9A227);
            put(cx - 1, cy + 1, 0xFF8A6410);
            put(cx, cy + 1, 0xFFF5C518);
            put(cx + 1, cy + 1, 0xFFF5C518);
            put(cx + 2, cy + 1, 0xFF8A6410);
            put(cx, cy + 2, 0xFF8A6410);
            put(cx + 1, cy + 2, 0xFF8A6410);
        }

        void ice(int cx, int cy) {
            put(cx, cy, 0xFFEAF6FF);
            put(cx - 1, cy, 0xFFA8D8F0);
            put(cx + 1, cy, 0xFFA8D8F0);
            put(cx, cy - 1, 0xFFA8D8F0);
            put(cx, cy + 1, 0xFFA8D8F0);
            put(cx - 2, cy, 0xFF7FB8D8);
            put(cx + 2, cy, 0xFF7FB8D8);
            put(cx, cy - 2, 0xFF7FB8D8);
            put(cx, cy + 2, 0xFF7FB8D8);
        }
    }

    /**
     * The runner's pixel sprite and the small maps for the enemies, pet, hearts and fossils. The
     * runner is 14 x 22 pixels: a head and torso from maps, limbs rasterised from a pose so the run
     * cycle has knees and elbows. Posed into {@link #grid} with margins for raised arms, trailing
     * scarves and antennae.
     */
    static final class Sprite {
        static final int W = 26;
        static final int H = 30;
        static final int OX = 8;
        static final int OY = 5;

        static final String[] HEAD = {
                ".....oooo.....",
                "....occwcoo...",
                "....oCCCCCCCo.",
                "....oFfffffo..",
                "....oFffeffo..",
                "....oFFffffo..",
                ".....oFfffo...",
        };
        static final String[] TORSO = {
                ".....offo.....",
                "....oSssswo...",
                "....oSssswo...",
                "....oSssswo...",
                "....oSSssso...",
                "....obbbbbo...",
                "....oLllllo...",
        };

        /* Enemies face the runner. Wobble picks the leg frame, squash or spin. */
        static final String[] CRAB_A = {
                ".oWo....oWo.",
                ".oeo....oeo.",
                "oo.oooooo.oo",
                "oBooBHHBooBo",
                "oBBBBHBBBBBo",
                ".oBBBBBBBDo.",
                "..oDDDDDDo..",
                ".o.o.oo.o.o.",
        };
        static final String[] CRAB_B = {
                ".oWo....oWo.",
                ".oeo....oeo.",
                "oo.oooooo.oo",
                "oBooBHHBooBo",
                "oBBBBHBBBBBo",
                ".oBBBBBBBDo.",
                "..oDDDDDDo..",
                "o.o..oo..o.o",
        };
        static final String[] SLIME_A = {
                "...oooo...",
                "..oHHBBo..",
                ".oHBBBBBo.",
                ".oBeWBeWo.",
                ".oBBBBBBo.",
                "oBBBDDBBBo",
                "oDDDDDDDDo",
                ".oooooooo.",
        };
        static final String[] SLIME_B = {
                "....oooo....",
                "..ooHHBBoo..",
                ".oHBeWBeWBo.",
                "oBBBBBBBBBBo",
                "oDDDBBBBDDDo",
                ".oooooooooo.",
        };
        static final String[] SPIKY_A = {
                ".....s.....",
                "...ooooo...",
                "..oHHBBBo..",
                ".oHBBBBBBo.",
                ".oBeWBeWBo.",
                "soBBBBBBBos",
                ".oBBBBBBDo.",
                ".oDBBBBBDo.",
                "..oDDDDDo..",
                "...ooooo...",
                ".....s.....",
        };
        static final String[] SPIKY_B = {
                "...........",
                ".s.ooooo.s.",
                "..oHHBBBo..",
                ".oHBBBBBBo.",
                ".oBeWBeWBo.",
                ".oBBBBBBBo.",
                ".oBBBBBBDo.",
                ".oDBBBBBDo.",
                "..oDDDDDo..",
                ".s.ooooo.s.",
                "...........",
        };
        /* The pet, wings up and wings down. G wing, B body, H belly, Y beak. */
        static final String[] PET_A = {
                ".oo...oooo..",
                "oGGo.oBBWeo.",
                "oGGGoBBBBBYY",
                ".oGGBBBBBBo.",
                "..ooBHBBBBo.",
                "....oHHHBo..",
                ".....oooo...",
        };
        static final String[] PET_B = {
                "......oooo..",
                ".....oBBWeo.",
                "....oBBBBBYY",
                ".oooBBBBBBo.",
                "oGGGGoHBBBo.",
                ".oGGooHHBo..",
                "..oo.oooo...",
        };
        static final String[] HEART = {
                ".ooo.ooo.",
                "oHBBoBBBo",
                "oHBBBBBDo",
                "oBBBBBBDo",
                ".oBBBBDo.",
                "..oBBDo..",
                "...oDo...",
                "....o....",
        };
        static final String[] FOSSIL_FISH = {
                "....b.b.b...",
                ".bb.b.b.b..b",
                "bdbbbbbbbbbd",
                ".bb.b.b.b..b",
                "....b.b.b...",
        };
        static final String[] AMMONITE = {
                "..bbb..",
                ".bdddb.",
                "bdbbbdb",
                "bdb.bdb",
                "bdbb.db",
                ".bd.db.",
                "..bbb..",
        };

        final int[] grid = new int[W * H];
        /* Palette: outline, cap, cap shade, highlight, face, face shade, eye, shirt, shirt shade,
         * belt, legs, legs shade, boots. */
        int o;
        int c;
        int cs;
        int w;
        int f;
        int fs;
        int e;
        int s;
        int ss;
        int b;
        int l;
        int ls;
        int boot;

        void palette(int shirt, int legs, int face, boolean helmet) {
            s = shirt;
            ss = Pix.shade(shirt, 0.74f);
            w = Pix.mix(shirt, 0xFFFFFFFF, 0.35f);
            c = shirt;
            cs = Pix.shade(shirt, 0.7f);
            f = face;
            fs = Pix.shade(face, 0.8f);
            e = o;
            l = legs;
            ls = Pix.shade(legs, 0.72f);
            b = Pix.shade(legs, 0.5f);
            if (helmet) {
                c = 0xFFB8BCC8;
                cs = 0xFF8A8E9C;
                f = 0xFFB8BCC8;
                fs = 0xFF8A8E9C;
            }
        }

        private int colour(char ch) {
            switch (ch) {
                case 'o': return o;
                case 'c': return c;
                case 'C': return cs;
                case 'w': return w;
                case 'f': return f;
                case 'F': return fs;
                case 'e': return e;
                case 's': return s;
                case 'S': return ss;
                case 'b': return b;
                case 'l': return l;
                case 'L': return ls;
                default: return 0;
            }
        }

        void set(int x, int y, int col) {
            int gx = x + OX;
            int gy = y + OY;
            if (gx >= 0 && gx < W && gy >= 0 && gy < H) {
                grid[gy * W + gx] = col;
            }
        }

        private void block(int x, int y, int size, int col) {
            for (int j = 0; j < size; j++) {
                for (int i = 0; i < size; i++) {
                    set(x + i, y + j, col);
                }
            }
        }

        private void stamp(String[] map, int x0, int y0) {
            for (int r = 0; r < map.length; r++) {
                String row = map[r];
                for (int i = 0; i < row.length(); i++) {
                    int col = colour(row.charAt(i));
                    if (col != 0) {
                        set(x0 + i, y0 + r, col);
                    }
                }
            }
        }

        /** A thick line of pixels; {@code ring} stamps one pixel bigger all round, for the outline. */
        private void seg(float ax, float ay, float bx, float by, int thick, int col, boolean ring) {
            float dx = bx - ax;
            float dy = by - ay;
            int n = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)) * 2f));
            float half = thick * 0.5f;
            for (int i = 0; i <= n; i++) {
                float fx = ax + dx * i / n;
                float fy = ay + dy * i / n;
                int cx = (int) Math.floor(fx - half + 0.5f);
                int cy = (int) Math.floor(fy - half + 0.5f);
                if (ring) {
                    block(cx - 1, cy - 1, thick + 2, col);
                } else {
                    block(cx, cy, thick, col);
                }
            }
        }

        /** Two bones and an optional foot from joint (jx, jy); angles from straight down, + forward. */
        private void limb(float jx, float jy, float a1, float l1, float a2, float l2, float foot,
                          int c1, int c2, int c3, int thick) {
            float kx = jx + (float) Math.sin(a1) * l1;
            float ky = jy + (float) Math.cos(a1) * l1;
            float ex = kx + (float) Math.sin(a2) * l2;
            float ey = ky + (float) Math.cos(a2) * l2;
            float tx = ex + (float) Math.cos(a2) * foot;
            float ty = ey - (float) Math.sin(a2) * foot;
            seg(jx, jy, kx, ky, thick, o, true);
            seg(kx, ky, ex, ey, thick, o, true);
            if (foot > 0f) {
                seg(ex, ey, tx, ty, thick, o, true);
            }
            seg(jx, jy, kx, ky, thick, c1, false);
            seg(kx, ky, ex, ey, thick, c2, false);
            if (foot > 0f) {
                seg(ex, ey, tx, ty, thick, c3, false);
            }
        }

        private static float footLow(float hipY, float th, float sh, float l1, float l2) {
            float ky = hipY + (float) Math.cos(th) * l1;
            float ey = ky + (float) Math.cos(sh) * l2;
            float ty = ey - (float) Math.sin(sh) * 1.6f;
            return Math.max(ey, ty);
        }

        /** Poses the runner into the grid. {@code phase} is the leg phase the old sprite swung on. */
        void pose(float speed, boolean inAir, float phase, int skin, double time) {
            java.util.Arrays.fill(grid, 0);
            float th1;
            float sh1;
            float th2;
            float sh2;
            float ar1;
            float fo1;
            float ar2;
            float fo2;
            if (inAir) {
                // Tuck: near knee up, far leg trailing, near arm reaching forward, far arm back.
                th1 = 1.2f;
                sh1 = -0.35f;
                th2 = -0.45f;
                sh2 = -1.35f;
                ar1 = 2.45f;
                fo1 = 2.85f;
                ar2 = -2.2f;
                fo2 = -1.7f;
            } else if (speed > 0.2f) {
                float amp = Math.min(1f, 0.45f + speed / 3.5f);
                float s1 = (float) Math.sin(phase);
                float c1 = (float) Math.cos(phase);
                th1 = 0.68f * amp * s1;
                sh1 = th1 - (0.2f + 1.3f * amp * Math.max(0f, c1));
                th2 = -th1;
                sh2 = th2 - (0.2f + 1.3f * amp * Math.max(0f, -c1));
                ar1 = -0.8f * amp * s1;
                fo1 = ar1 + 1.4f;
                ar2 = -ar1;
                fo2 = ar2 + 1.4f;
            } else {
                th1 = 0.1f;
                sh1 = 0.05f;
                th2 = -0.1f;
                sh2 = -0.05f;
                ar1 = 0.15f;
                fo1 = 0.45f;
                ar2 = -0.15f;
                fo2 = 0.2f;
            }
            float hipY = 13.6f;
            float thigh = 3.8f;
            float shin = 3.8f;
            int drop = 0;
            if (!inAir) {
                // Drop the body when the stride lifts both feet, so one is always on the line.
                float low = Math.max(footLow(hipY, th1, sh1, thigh, shin), footLow(hipY, th2, sh2, thigh, shin));
                drop = Math.max(0, Math.min(2, Math.round(21.2f - low)));
            }
            int lean = !inAir && speed > 2.5f ? 1 : 0;
            float dy = drop;
            // Far side first, in shade; then the body; then the near side over it, outlined.
            limb(7.4f, 8.6f + dy, ar2, 2.6f, fo2, 2.4f, 0f, ss, fs, fs, 2);
            limb(7f, hipY + dy, th2, thigh, sh2, shin, 1.6f, ls, ls, Pix.shade(boot, 0.8f), 2);
            stamp(TORSO, 0, 7 + drop);
            stamp(HEAD, lean, drop);
            extras(skin, lean, drop, speed, phase, time);
            limb(7f, hipY + dy, th1, thigh, sh1, shin, 1.6f, l, l, boot, 2);
            limb(7.4f, 8.6f + dy, ar1, 2.6f, fo1, 2.4f, 0f, s, f, f, 2);
        }

        /** The bit that makes a skin worth 600 coins: goggles, a headband, an antenna, a plume. */
        private void extras(int skin, int hx, int y0, float speed, float phase, double time) {
            switch (skin) {
                case 1: {   // AQUA: goggles and a trailing scarf
                    for (int gx = 4; gx <= 11; gx++) {
                        set(gx + hx, 4 + y0, 0xFF10171F);
                    }
                    set(7 + hx, 4 + y0, 0xFF7FE8FF);
                    set(8 + hx, 4 + y0, 0xFFD8FAFF);
                    set(10 + hx, 4 + y0, 0xFF7FE8FF);
                    float len = Math.min(4f, 1.5f + speed * 0.7f);
                    float wave = (float) Math.sin(phase * 0.7) * 0.8f;
                    float sag = 2.5f - Math.min(1.5f, speed * 0.4f);
                    seg(4f, 8f + y0, 4f - len, 8f + y0 + sag + wave, 1, 0xFF2FA8C8, false);
                    seg(4f, 9f + y0, 4f - len * 0.75f, 9f + y0 + sag - wave, 1, 0xFF1E7E9A, false);
                    break;
                }
                case 2: {   // NINJA: headband with tails that stream behind, and a mask
                    float tail = Math.min(4f, 1.5f + speed * 0.7f);
                    float wave = (float) Math.sin(phase * 0.7) * 0.9f;
                    float sag = 2f - Math.min(1.2f, speed * 0.35f);
                    seg(3f + hx, 3f + y0, 3f + hx - tail, 3f + y0 + sag + wave, 1, 0xFFE84C3D, false);
                    seg(3f + hx, 3f + y0, 3f + hx - tail * 0.7f, 4f + y0 + sag - wave, 1, 0xFFB8302A, false);
                    for (int gx = 4; gx <= 11; gx++) {
                        set(gx + hx, 3 + y0, 0xFFE84C3D);
                    }
                    set(10 + hx, 3 + y0, 0xFFFF8A7A);
                    for (int gx = 5; gx <= 10; gx++) {
                        set(gx + hx, 5 + y0, 0xFF12161F);
                    }
                    for (int gx = 6; gx <= 9; gx++) {
                        set(gx + hx, 6 + y0, 0xFF12161F);
                    }
                    break;
                }
                case 3: {   // ROBOT: antenna with a blinking light, and a visor
                    for (int yy = -2; yy <= -1; yy++) {
                        set(7 + hx, yy + y0, o);
                        set(8 + hx, yy + y0, 0xFF5C6676);
                        set(9 + hx, yy + y0, o);
                    }
                    boolean on = ((int) (time * 4)) % 2 == 0;
                    set(8 + hx, -3 + y0, on ? 0xFFF0655D : 0xFF7A3A36);
                    set(7 + hx, -3 + y0, o);
                    set(9 + hx, -3 + y0, o);
                    set(8 + hx, -4 + y0, o);
                    for (int gx = 5; gx <= 10; gx++) {
                        set(gx + hx, 4 + y0, 0xFF35D0BA);
                    }
                    set(9 + hx, 4 + y0, 0xFFB8FFF0);
                    break;
                }
                case 4: {   // KNIGHT: helmet slit and a plume that sways
                    for (int gx = 5; gx <= 10; gx++) {
                        set(gx + hx, 4 + y0, 0xFF10171F);
                    }
                    set(9 + hx, 1 + y0, 0xFFE8ECF4);
                    int lean = Math.round((float) Math.sin(phase * 0.5) * 0.8f);
                    for (int yy = -3; yy <= 0; yy++) {
                        set(5 + hx + lean, yy + y0, o);
                        set(6 + hx + lean, yy + y0, 0xFFE84C3D);
                        set(7 + hx + lean, yy + y0, 0xFFFF7A6A);
                        set(8 + hx + lean, yy + y0, o);
                    }
                    set(6 + hx + lean, -4 + y0, o);
                    set(7 + hx + lean, -4 + y0, o);
                    break;
                }
                default:
                    break;
            }
        }
    }
    /* ---------- the pet ---------- */

    /** Where a coin sits on screen, so the pet can fly at it. */
    private float coinY(Thing t, float groundY, float metre) {
        return t.kind == Kind.HIGH_COIN ? groundY - dp(90f)
                : t.kind == Kind.ARC_COIN ? groundY - dp(20f) - t.height * metre
                : groundY - dp(20f);
    }

    private static boolean isCoin(Kind k) {
        return k == Kind.COIN || k == Kind.ARC_COIN || k == Kind.HIGH_COIN;
    }

    /**
     * The pet flies just behind and above the runner, and darts at the nearest coin within its
     * reach - which is how the coins a jump misses still get collected. A better pet reaches further.
     */
    private void updatePet(float dt, float speed, float groundY, float youX, float ppm, float metre) {
        float homeX = youX - dp(50f);
        float homeY = groundY - dp(76f) - height() * metre * 0.5f
                + (float) Math.sin(sessionSeconds * 2.6) * dp(7f);
        if (!petPlaced) {
            petPx = homeX;
            petPy = homeY;
            petPlaced = true;
        }
        Thing target = null;
        float best = Float.MAX_VALUE;
        float range = PET_RANGE[petIndex];
        // The pet reaches a high coin well before the runner does (2.4 m of reach against 0.6 s of
        // running), so left alone it took every one of them for 2 coins and the 5-coin reward for
        // running fast enough could never be collected. It only salvages the ones you are too slow for.
        boolean fastEnough = speed >= highCoinSpeed();
        for (Thing t : things) {
            if (t.done || !isCoin(t.kind) || (fastEnough && t.kind == Kind.HIGH_COIN)) {
                continue;
            }
            float d = t.at - (float) x;
            if (d < -1.5f || d > range) {
                continue;
            }
            float away = Math.abs(d);
            if (away < best) {
                best = away;
                target = t;
            }
        }
        float tx = homeX;
        float ty = homeY;
        if (target != null) {
            tx = youX + (target.at - (float) x) * ppm;
            ty = coinY(target, groundY, metre);
        }
        float k = Math.min(1f, dt * (target != null ? 10f : 5f));
        petPx += (tx - petPx) * k;
        petPy += (ty - petPy) * k;
        if (target != null && Math.abs(petPx - tx) < dp(15f) && Math.abs(petPy - ty) < dp(15f)) {
            target.done = true;
            // A high coin is worth 5 to a runner going fast enough; the pet can only carry some of it.
            coins += target.kind == Kind.HIGH_COIN ? 2 : 1;
            petGrabs++;
            fx.burst(petPx, petPy, 8, dp(90f), 0.4f, dp(3f), 0xFFF5C518, true);
        }
    }

    /** A round little companion: two flapping wings, an eye, and a sparkle trail while it dives. */
    private void drawPet(Canvas c) {
        if (!started || !petPlaced) {
            return;
        }
        drawPetAt(c, petPx, petPy, petIndex);
    }

    /**
     * Drawn at an explicit point so the shop can preview one without touching the live pet. A pixel
     * bird with two wing frames; the drone keeps its rotor and blinking light.
     */
    private void drawPetAt(Canvas c, float px, float py, int kind) {
        float q = dp(2.5f);
        float flap = (float) Math.sin(sessionSeconds * 13);
        int body = PET_BODY[kind];
        int wing = PET_WING[kind];
        Fx.glow(c, px, py, dp(20f), withAlpha(body, 0x44));
        pO = INK;
        pB = body;
        pD = Pix.shade(body, 0.72f);
        pH = Pix.mix(body, 0xFFFFFFFF, 0.55f);
        pG = wing;
        pW = 0xFFFFFFFF;
        pE = INK;
        pY = kind == 2 ? Pix.shade(body, 0.8f) : kind == 1 ? 0xFFF08A2A : 0xFFF5C518;
        drawMap(c, flap > 0 ? Sprite.PET_A : Sprite.PET_B, px - 6 * q, py - 3.5f * q, q);
        if (kind == 2) {
            // The drone: a rotor that blurs between two lengths, and a blinking underside light.
            boolean longBlade = ((int) (sessionSeconds * 20)) % 2 == 0;
            float half = longBlade ? dp(14f) : dp(9f);
            blk.setColor(INK);
            c.drawRect(Math.round(px - q * 0.5f), Math.round(py - 3.5f * q - dp(4f)), Math.round(px + q * 0.5f),
                    Math.round(py - 3.5f * q), blk);
            blk.setColor(0xFF3A4658);
            c.drawRect(Math.round(px - half), Math.round(py - 3.5f * q - dp(6f)), Math.round(px + half),
                    Math.round(py - 3.5f * q - dp(3.5f)), blk);
            blk.setColor(((int) (sessionSeconds * 5)) % 2 == 0 ? 0xFFF0655D : 0xFF5A2A28);
            c.drawRect(Math.round(px - q * 0.5f), Math.round(py + 3.5f * q), Math.round(px + q * 0.5f + q),
                    Math.round(py + 3.5f * q + q), blk);
        }
    }

    /** The chain over the runner's head: how many in a row, what they are worth, and how long left. */
    private void drawCombo(Canvas c, float youX, float headY) {
        double since = sessionSeconds - comboBrokeAt;
        if (combo < 2) {
            if (since >= 0 && since < 0.5) {
                bold(c, "CHAIN LOST", youX, headY - dp(84f), 13f, BAD, Paint.Align.CENTER);
            }
            return;
        }
        float left = (float) Math.max(0, Math.min(1, (comboUntil - sessionSeconds) / comboWindow()));
        float bx = youX - dp(34f);
        float by = headY - dp(110f);
        hudRect.set(bx, by, bx + dp(68f), by + dp(30f));
        paint.setColor(0xB3060B12);
        c.drawRoundRect(hudRect, dp(5f), dp(5f), paint);
        paint.setColor(0x26FFFFFF);
        c.drawRect(bx + dp(4f), by, bx + dp(64f), by + dp(1f), paint);
        paint.setColor(rainbow(combo * 0.35));
        hudRect.set(bx + dp(3f), by + dp(25f), bx + dp(3f) + (dp(62f)) * left, by + dp(28f));
        c.drawRoundRect(hudRect, dp(1.5f), dp(1.5f), paint);
        bold(c, "x" + combo + "  ·  x" + comboMultiplier(), bx + dp(34f), by + dp(19f), 12f,
                left > 0.3f ? 0xFFFFE28A : BAD, Paint.Align.CENTER);
    }

    private void drawHud(Canvas c, float w, float h, float speed, float groundY, float youX, int world) {
        // Scrims at the top and bottom so the HUD text reads over a bright sky and over the dirt.
        if (topScrim == null || topScrimW != w) {
            topScrim = new android.graphics.LinearGradient(0, 0, 0, dp(86f), 0x99060B12, 0x00060B12,
                    android.graphics.Shader.TileMode.CLAMP);
            topScrimW = w;
        }
        if (bottomScrim == null || bottomScrimH != h) {
            bottomScrim = new android.graphics.LinearGradient(0, h - dp(48f), 0, h, 0x00060B12, 0xB3060B12,
                    android.graphics.Shader.TileMode.CLAMP);
            bottomScrimH = h;
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(topScrim);
        c.drawRect(0, 0, w, dp(86f), paint);
        paint.setShader(bottomScrim);
        c.drawRect(0, h - dp(48f), w, h, paint);
        paint.setShader(null);
        hudPanel(c, dp(4f), dp(4f), dp(198f), dp(76f), false);
        float hc = dp(2.2f);
        for (int i = 0; i < HEARTS; i++) {
            float hx = dp(18f) + i * dp(22f);
            boolean full = i < hearts;
            pO = full ? INK : 0x66000000;
            pB = full ? BAD : 0x33FFFFFF;
            pD = full ? 0xFFA83A35 : 0x22FFFFFF;
            pH = full ? 0xFFFFC8C0 : 0x22FFFFFF;
            drawMap(c, Sprite.HEART, hx - 4.5f * hc, dp(15f), hc);
        }
        // Star meter under the hearts: a rounded track and a glossy fill.
        float mx = dp(8f);
        float my = dp(44f);
        float mw = dp(170f);
        hudRect.set(mx, my, mx + mw, my + dp(10f));
        paint.setColor(0x66000000);
        c.drawRoundRect(hudRect, dp(5f), dp(5f), paint);
        boolean starNow = star();
        float fill = starNow ? (float) ((starUntil - sessionSeconds) / STAR_SECONDS) : starCharge;
        float fw = mw * Math.max(0f, Math.min(1f, fill));
        if (fw > dp(1f)) {
            paint.setColor(starNow ? rainbow(sessionSeconds * 3) : peakWatts >= starWatts() ? 0xFFFFE28A : 0xFFB8A050);
            hudRect.set(mx, my, mx + fw, my + dp(10f));
            c.drawRoundRect(hudRect, dp(5f), dp(5f), paint);
            if (fw > dp(8f)) {
                paint.setColor(0x50FFFFFF);
                hudRect.set(mx + dp(3f), my + dp(1.5f), mx + fw - dp(3f), my + dp(4.5f));
                c.drawRoundRect(hudRect, dp(1.5f), dp(1.5f), paint);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x33FFFFFF);
        hudRect.set(mx, my, mx + mw, my + dp(10f));
        c.drawRoundRect(hudRect, dp(5f), dp(5f), paint);
        paint.setStyle(Paint.Style.FILL);
        label(c, starNow ? "INVINCIBLE" : "STAR - HOLD " + Math.round(starWatts()) + " W",
                mx, my + dp(24f), 9f, starNow ? 0xFFFFE28A : DIM, Paint.Align.LEFT);

        bold(c, "● " + coins, w - dp(16f), dp(30f), 20f, 0xFFF5C518, Paint.Align.RIGHT);
        label(c, "bank " + (bank + coins - coinsBanked) + (petGrabs > 0 ? "   ·   " + PET_NAME[petIndex] + " got " + petGrabs : ""),
                w - dp(16f), dp(48f), 9f, DIM, Paint.Align.RIGHT);
        if (bossesBeaten > 0) {
            label(c, bossesBeaten + (bossesBeaten == 1 ? " crab king beaten" : " crab kings beaten"),
                    w - dp(16f), dp(64f), 9f, DIM, Paint.Align.RIGHT);
        }
        if (mode == Mode.DAILY) {
            float left = Math.max(0f, DAILY_METRES - (float) x);
            // Once the gate is crossed the run is timed: hold the finish time rather than letting
            // the header clock run on past the time the card is showing.
            bold(c, clock(finished ? dailyTime : activeSeconds), w / 2f, dp(30f), 20f, TEXT, Paint.Align.CENTER);
            label(c, "DAILY " + Math.round(DAILY_METRES) + " m  ·  " + Math.round(left) + " m to go"
                            + (dailyBestText != null ? "  ·  today's best " + dailyBestText : "  ·  no time today yet"),
                    w / 2f, dp(48f), 10f, DIM, Paint.Align.CENTER);
        } else {
            bold(c, Math.round(x) + " m", w / 2f, dp(30f), 20f, TEXT, Paint.Align.CENTER);
        }
        String sub = (mode == Mode.ENDLESS ? "ENDLESS  ·  " : "") + WORLD_NAMES[world]
                + (checkpoint > 0 && mode != Mode.ENDLESS ? "   ·   flag " + Math.round(checkpoint) + " m" : "");
        if (ghost != null && started) {
            int gap = Math.round((float) x - ghostAt(runClock));
            sub += "   ·   " + (gap >= 0 ? "+" + gap + " m on BEST" : gap + " m on BEST");
        }
        if (mode != Mode.DAILY) {
            label(c, sub, w / 2f, dp(48f), 10f, DIM, Paint.Align.CENTER);
        }

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
                    || flash.startsWith("BLOCKED") || flash.startsWith("BOP") || flash.startsWith("FROM")
                    || flash.startsWith("CHAIN") || flash.startsWith("FINISH") || flash.startsWith("NEW DAILY");
            bold(c, flash, w / 2f, h * 0.36f, 24f, good ? ACCENT : WARN, Paint.Align.CENTER);
        }
        String cap;
        int col = FAINT;
        if (!started) {
            drawStartCard(c, w, h);
            cap = ghost != null
                    ? "TAKE A STROKE TO RUN  ·  YOUR BEST RUN (" + Math.round(ghostEnd) + " m) RUNS WITH YOU AS A GHOST"
                    : "TAKE A STROKE TO RUN  ·  JUMPS ARE AUTOMATIC - ROW FASTER TO RUN FASTER";
        } else if (finished) {
            cap = "";
            drawDailyCard(c, w, h);
        } else if (over) {
            cap = "";
            drawGameOver(c, w, h);
        } else if (fighting) {
            cap = "SURGE ABOVE " + Math.round(surgeWatts()) + " W TO HIT  ·  SURGE WHILE THE CLAW GLOWS TO COUNTER x2";
            col = WARN;
        } else {
            cap = runningCaption(speed);
            col = DIM;
        }
        if (!cap.isEmpty()) {
            bold(c, cap, w / 2f, h - dp(16f), 11f, col, Paint.Align.CENTER);
        }
    }

    /* HUD furniture: cached gradients, one reusable rectangle, and the running caption's cache. */
    private final RectF hudRect = new RectF();
    private android.graphics.LinearGradient topScrim;
    private float topScrimW = -1f;
    private android.graphics.LinearGradient bottomScrim;
    private float bottomScrimH = -1f;
    private android.graphics.LinearGradient hudShade;
    private android.graphics.LinearGradient cardShade;
    private float cardShadeTop = -1f;
    private float cardShadeBottom = -1f;
    private int capSpeedTenths = -1;
    private int capStomps = -1;
    private int capPeak = -1;
    private String capText = "";

    /** The running caption, rebuilt only when one of its numbers changes (it was a format per frame). */
    private String runningCaption(float speed) {
        int tenths = Math.round(speed * 10f);
        if (tenths != capSpeedTenths || stomps != capStomps || peakWatts != capPeak) {
            capSpeedTenths = tenths;
            capStomps = stomps;
            capPeak = peakWatts;
            capText = String.format(java.util.Locale.US,
                    "ROW FASTER = RUN FASTER, JUMP FURTHER   ·   %.1f m/s   ·   %d stomps   ·   peak %d W",
                    tenths / 10f, stomps, peakWatts);
        }
        return capText;
    }

    /**
     * A HUD panel in the app's family: rounded, a subtle vertical gradient, a hairline border and a
     * lighter top edge. {@code card} panels (start, game over, daily) are a little more opaque.
     */
    private void hudPanel(Canvas c, float l, float t, float r, float b, boolean card) {
        float rad = dp(card ? 12f : 8f);
        android.graphics.LinearGradient g;
        if (card) {
            if (cardShade == null || cardShadeTop != t || cardShadeBottom != b) {
                cardShade = new android.graphics.LinearGradient(0, t, 0, b, 0xF0162030, 0xF00A0F17,
                        android.graphics.Shader.TileMode.CLAMP);
                cardShadeTop = t;
                cardShadeBottom = b;
            }
            g = cardShade;
        } else {
            if (hudShade == null) {
                hudShade = new android.graphics.LinearGradient(0, t, 0, b, 0xCC111A28, 0xB3060B12,
                        android.graphics.Shader.TileMode.CLAMP);
            }
            g = hudShade;
        }
        hudRect.set(l, t, r, b);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(g);
        c.drawRoundRect(hudRect, rad, rad, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0xFF1C2736);
        c.drawRoundRect(hudRect, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x38FFFFFF);
        c.drawRect(l + rad, t + dp(1f), r - rad, t + dp(2f), paint);
    }

    /** A button: rounded, a darker lip underneath, and a gloss over its top half. */
    private void button(Canvas c, RectF rect, int colour) {
        float rad = dp(8f);
        paint.setColor(Pix.shade(colour, 0.62f));
        hudRect.set(rect.left, rect.top + dp(3f), rect.right, rect.bottom + dp(3f));
        c.drawRoundRect(hudRect, rad, rad, paint);
        paint.setColor(colour);
        c.drawRoundRect(rect, rad, rad, paint);
        paint.setColor(0x2EFFFFFF);
        hudRect.set(rect.left + dp(3f), rect.top + dp(3f), rect.right - dp(3f), rect.centerY());
        c.drawRoundRect(hudRect, rad * 0.7f, rad * 0.7f, paint);
    }

    /**
     * The shop, drawn over the game. Three columns - RUNNERS, PETS, WORLDS - each item a card that
     * buys on the first tap and is worn on the next. Coins come from runs and are banked between
     * sessions, so a run is worth something even when it ends badly.
     */
    private void drawShop(Canvas c, float w, float h) {
        paint.setColor(0xE60A0F16);
        c.drawRect(0, 0, w, h, paint);
        bold(c, "SHOP", dp(24f), dp(44f), 26f, TEXT, Paint.Align.LEFT);
        bold(c, "● " + bank, dp(140f), dp(44f), 26f, 0xFFF5C518, Paint.Align.LEFT);
        label(c, "coins are banked between sessions  ·  tap to buy, tap again to wear",
                dp(24f), dp(66f), 10f, DIM, Paint.Align.LEFT);

        closeRect.set(w - dp(130f), dp(18f), w - dp(24f), dp(62f));
        button(c, closeRect, 0xFF3A4658);
        bold(c, "CLOSE", closeRect.centerX(), closeRect.centerY() + dp(6f), 13f, TEXT, Paint.Align.CENTER);

        float colW = (w - dp(48f) - dp(24f) * 2) / 3f;
        float top = dp(96f);
        float cardH = dp(66f);
        for (int g = 0; g < 3; g++) {
            float cx0 = dp(24f) + g * (colW + dp(24f));
            bold(c, SHOP_HEADS[g], cx0, top + dp(14f), 13f, ACCENT, Paint.Align.LEFT);
            if (g == 1) {
                label(c, "a pet grabs the coins you jump past", cx0 + dp(96f), top + dp(14f), 9f, DIM,
                        Paint.Align.LEFT);
            } else if (g == 2) {
                label(c, "lock every kilometre to one world", cx0 + dp(96f), top + dp(14f), 9f, DIM,
                        Paint.Align.LEFT);
            }
        }
        // One running Y per column; the shop's stock is fixed, so no list allocation in the frame.
        shopRowY[0] = top + dp(26f);
        shopRowY[1] = shopRowY[0];
        shopRowY[2] = shopRowY[0];
        for (Item it : shop) {
            float cx0 = dp(24f) + it.group * (colW + dp(24f));
            float y0 = shopRowY[it.group];
            shopRowY[it.group] = y0 + cardH + dp(10f);
            it.rect.set(cx0, y0, cx0 + colW, y0 + cardH);
            boolean have = owned.contains(it.id);
            boolean on = have && equipped(it);
            paint.setColor(on ? 0xFF15584F : have ? 0xFF222B38 : bank >= it.cost ? 0xFF2A3342 : 0xFF1A202A);
            c.drawRoundRect(it.rect, dp(8f), dp(8f), paint);
            paint.setColor(0x24FFFFFF);
            c.drawRect(cx0 + dp(8f), y0 + dp(1f), cx0 + colW - dp(8f), y0 + dp(2f), paint);
            if (on) {
                paint.setColor(ACCENT);
                hudRect.set(cx0, y0, cx0 + dp(5f), y0 + cardH);
                c.drawRoundRect(hudRect, dp(2.5f), dp(2.5f), paint);
            }
            drawShopPreview(c, it, cx0 + dp(44f), y0 + cardH - dp(14f));
            bold(c, it.name, cx0 + dp(84f), y0 + dp(28f), 14f, have ? TEXT : 0xFFB9C4D4, Paint.Align.LEFT);
            label(c, it.note, cx0 + dp(84f), y0 + dp(46f), 9f, DIM, Paint.Align.LEFT);
            String right = on ? "WEARING" : have ? "TAP TO WEAR" : it.price;
            bold(c, right, cx0 + colW - dp(12f), y0 + dp(34f), 12f,
                    on ? ACCENT : have ? 0xFFB9C4D4 : bank >= it.cost ? 0xFFF5C518 : FAINT, Paint.Align.RIGHT);
        }
        if (sessionSeconds < shopNoteUntil) {
            bold(c, shopNote, w / 2f, h - dp(24f), 16f,
                    shopNote.startsWith("NEEDS") ? WARN : ACCENT, Paint.Align.CENTER);
        } else {
            label(c, "earn coins by running: stomps chain up, the crab kings pay by the kilometre",
                    w / 2f, h - dp(24f), 10f, FAINT, Paint.Align.CENTER);
        }
    }

    /** A little live preview on each shop card: the runner, the pet, or a slice of the world's sky. */
    private void drawShopPreview(Canvas c, Item it, float cx, float feetY) {
        if (it.group == 0) {
            drawHero(c, cx, feetY, 3.5f, false, 0xFF, false, it.index, dp(2.3f));
        } else if (it.group == 1) {
            drawPetAt(c, cx, feetY - dp(24f), it.index);
        } else {
            int world = it.index < 0 ? (int) ((sessionSeconds / 1.2) % WORLD_NAMES.length) : it.index;
            paint.setColor(SKY_TOP[world]);
            c.drawRect(cx - dp(22f), feetY - dp(42f), cx + dp(22f), feetY - dp(14f), paint);
            paint.setColor(HILL[world]);
            c.drawCircle(cx - dp(6f), feetY - dp(12f), dp(16f), paint);
            paint.setColor(TOP[world]);
            c.drawRect(cx - dp(22f), feetY - dp(14f), cx + dp(22f), feetY - dp(8f), paint);
            paint.setColor(SOIL_A[world]);
            c.drawRect(cx - dp(22f), feetY - dp(8f), cx + dp(22f), feetY, paint);
        }
    }

    private void drawBossHud(Canvas c, float w, float groundY, float youX) {
        // HP bar across the top.
        float bw = dp(420f);
        float bx = w / 2f - bw / 2f;
        float by = dp(62f);
        hudRect.set(bx - dp(4f), by - dp(4f), bx + bw + dp(4f), by + dp(20f));
        paint.setColor(0x99060B12);
        c.drawRoundRect(hudRect, dp(6f), dp(6f), paint);
        float seg = bw / bossMaxHp;
        for (int i = 0; i < bossMaxHp; i++) {
            paint.setColor(i < bossHp ? BOSS_BODY[bossWorld(boss.at)] : 0x33FFFFFF);
            c.drawRect(bx + i * seg + dp(1f), by, bx + (i + 1) * seg - dp(1f), by + dp(16f), paint);
            if (i < bossHp) {
                paint.setColor(0x40FFFFFF);
                c.drawRect(bx + i * seg + dp(1f), by, bx + (i + 1) * seg - dp(1f), by + dp(4f), paint);
            }
        }
        bold(c, "KING CRAB", w / 2f, by + dp(38f), 12f, TEXT, Paint.Align.CENTER);
        // Surge meter beside the runner: fill is your peak power, the line is a hit.
        float mx = youX - dp(60f);
        float mTop = groundY - dp(130f);
        float mH = dp(110f);
        float target = surgeWatts();
        float f = Math.min(1f, peakWatts / (target * 1.25f));
        hudRect.set(mx - dp(9f), mTop - dp(1f), mx + dp(9f), mTop + mH + dp(1f));
        paint.setColor(0x80060B12);
        c.drawRoundRect(hudRect, dp(4f), dp(4f), paint);
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

    /**
     * The card before the first stroke: pick TOUR, DAILY or ENDLESS, and open the shop. Drawn on the
     * game's own canvas and handled in {@link #onTouchEvent}, since a game cannot add header chips.
     */
    private void drawStartCard(Canvas c, float w, float h) {
        float pw = Math.min(w - dp(24f), dp(860f));
        float ph = dp(178f);
        float left = w / 2f - pw / 2f;
        float top = h * 0.30f;
        hudPanel(c, left, top, left + pw, top + ph, true);
        bold(c, "ROW RUNNER", w / 2f, top + dp(36f), 22f, TEXT, Paint.Align.CENTER);
        label(c, "bank ● " + bank + "   ·   " + SKIN_NAME[skinIndex] + " + " + PET_NAME[petIndex]
                        + "   ·   " + (themeIndex >= 0 ? WORLD_NAMES[themeIndex] : "WORLD TOUR"),
                w / 2f, top + dp(58f), 11f, DIM, Paint.Align.CENTER);

        float bw = dp(190f);
        float bh = dp(70f);
        float by = top + dp(76f);
        float gap = dp(14f);
        float x0 = w / 2f - (bw * 2 + gap * 1.5f);
        for (int i = 0; i < 3; i++) {
            modeRect[i].set(x0 + i * (bw + gap), by, x0 + i * (bw + gap) + bw, by + bh);
            boolean on = mode.ordinal() == i;
            String note = i == 0 ? "the fixed course, kings and worlds"
                    : i == 1 ? dailyModeNote
                    : "fresh seed, instant restart";
            button(c, modeRect[i], on ? ACCENT : 0xFF2A3342);
            bold(c, MODE_NAMES[i], modeRect[i].centerX(), modeRect[i].centerY() - dp(2f), 15f,
                    on ? 0xFF10171F : TEXT, Paint.Align.CENTER);
            label(c, note, modeRect[i].centerX(), modeRect[i].centerY() + dp(20f), 8f,
                    on ? 0xCC10171F : DIM, Paint.Align.CENTER);
        }
        shopBtnRect.set(x0 + 3 * (bw + gap), by, x0 + 3 * (bw + gap) + bw, by + bh);
        button(c, shopBtnRect, 0xFFF5C518);
        bold(c, "SHOP", shopBtnRect.centerX(), shopBtnRect.centerY() - dp(2f), 15f, 0xFF10171F, Paint.Align.CENTER);
        label(c, "● " + bank + " to spend", shopBtnRect.centerX(), shopBtnRect.centerY() + dp(20f), 8f,
                0xCC10171F, Paint.Align.CENTER);
    }

    /** The daily's finish card: this run's time against today's best, and RETRY on the same seed. */
    private void drawDailyCard(Canvas c, float w, float h) {
        float pw = dp(640f);
        float ph = dp(250f);
        float left = w / 2f - pw / 2f;
        float top = h * 0.28f;
        hudPanel(c, left, top, left + pw, top + ph, true);
        bold(c, dailyIsBest ? "NEW DAILY BEST" : "DAILY DONE", w / 2f, top + dp(44f), 28f,
                dailyIsBest ? ACCENT : TEXT, Paint.Align.CENTER);
        bold(c, clock(dailyTime), w / 2f, top + dp(86f), 30f, 0xFFFFE28A, Paint.Align.CENTER);
        String against = dailyIsBest || dailyBestText == null
                ? "your fastest run of today's course"
                : "today's best " + dailyBestText + "   ·   "
                        + String.format(java.util.Locale.US, "%+.1f s", dailyTime - dailyBestSeconds);
        label(c, against + "   ·   " + coins + " coins   ·   " + stomps + " stomps   ·   best chain x" + bestCombo,
                w / 2f, top + dp(112f), 11f, DIM, Paint.Align.CENTER);

        float btnTop = top + dp(136f);
        float btnH = dp(84f);
        retryRect.set(left + dp(24f), btnTop, w / 2f - dp(12f), btnTop + btnH);
        shopBtnRect.set(w / 2f + dp(12f), btnTop, left + pw - dp(24f), btnTop + btnH);
        button(c, retryRect, ACCENT);
        bold(c, "RETRY", retryRect.centerX(), retryRect.centerY() - dp(2f), 18f, 0xFF10171F, Paint.Align.CENTER);
        label(c, "same course, beat that time", retryRect.centerX(), retryRect.centerY() + dp(22f), 10f,
                0xCC10171F, Paint.Align.CENTER);
        button(c, shopBtnRect, 0xFFF5C518);
        bold(c, "SHOP", shopBtnRect.centerX(), shopBtnRect.centerY() - dp(2f), 18f, 0xFF10171F, Paint.Align.CENTER);
        label(c, "● " + bank + " banked", shopBtnRect.centerX(), shopBtnRect.centerY() + dp(22f), 10f,
                0xCC10171F, Paint.Align.CENTER);
    }

    private void drawGameOver(Canvas c, float w, float h) {
        float pw = dp(640f);
        float ph = dp(250f);
        float left = w / 2f - pw / 2f;
        float top = h * 0.30f;
        hudPanel(c, left, top, left + pw, top + ph, true);
        bold(c, "GAME OVER", w / 2f, top + dp(44f), 28f, BAD, Paint.Align.CENTER);
        label(c, Math.round(x) + " m   ·   " + coins + " coins   ·   " + stomps + " stomps   ·   chain x" + bestCombo
                        + "   ·   " + bossesBeaten + " crab kings"
                        + (continues > 0 ? "   ·   " + continues + " continues" : ""),
                w / 2f, top + dp(76f), 12f, TEXT, Paint.Align.CENTER);
        if (newBestRun) {
            label(c, "NEW BEST RUN - it is your ghost next time", w / 2f, top + dp(100f), 11f, ACCENT, Paint.Align.CENTER);
        }
        float btnTop = top + dp(130f);
        float btnH = dp(84f);
        if (mode == Mode.ENDLESS) {
            // Endless is about the next run: one tap anywhere and you are off again.
            continueRect.setEmpty();
            restartRect.set(left + dp(24f), btnTop, w / 2f + dp(140f), btnTop + btnH);
            shopBtnRect.set(w / 2f + dp(164f), btnTop, left + pw - dp(24f), btnTop + btnH);
            button(c, restartRect, ACCENT);
            bold(c, ((int) (sessionSeconds * 2)) % 2 == 0 ? "TAP ANYWHERE TO RUN AGAIN" : "TAP TO RUN AGAIN",
                    restartRect.centerX(), restartRect.centerY() - dp(2f), 17f, 0xFF10171F, Paint.Align.CENTER);
            label(c, "best endless " + Math.round(bestEndless) + " m   ·   a new course every run",
                    restartRect.centerX(), restartRect.centerY() + dp(22f), 10f, 0xCC10171F, Paint.Align.CENTER);
            button(c, shopBtnRect, 0xFFF5C518);
            bold(c, "SHOP", shopBtnRect.centerX(), shopBtnRect.centerY() - dp(2f), 17f, 0xFF10171F, Paint.Align.CENTER);
            label(c, "● " + bank, shopBtnRect.centerX(), shopBtnRect.centerY() + dp(22f), 10f, 0xCC10171F,
                    Paint.Align.CENTER);
            return;
        }
        shopBtnRect.set(left + pw - dp(150f), top + dp(14f), left + pw - dp(24f), top + dp(56f));
        button(c, shopBtnRect, 0xFFF5C518);
        bold(c, "SHOP ● " + bank, shopBtnRect.centerX(), shopBtnRect.centerY() + dp(6f), 12f, 0xFF10171F,
                Paint.Align.CENTER);
        if (checkpoint > 0) {
            continueRect.set(left + dp(24f), btnTop, w / 2f - dp(12f), btnTop + btnH);
            restartRect.set(w / 2f + dp(12f), btnTop, left + pw - dp(24f), btnTop + btnH);
            button(c, continueRect, ACCENT);
            bold(c, "CONTINUE", continueRect.centerX(), continueRect.centerY() - dp(2f), 18f, 0xFF10171F, Paint.Align.CENTER);
            label(c, "from the " + Math.round(checkpoint) + " m flag", continueRect.centerX(),
                    continueRect.centerY() + dp(22f), 10f, 0xFF10171F, Paint.Align.CENTER);
        } else {
            continueRect.setEmpty();
            restartRect.set(left + dp(24f), btnTop, left + pw - dp(24f), btnTop + btnH);
        }
        button(c, restartRect, 0xFF3A4658);
        bold(c, "RESTART", restartRect.centerX(), restartRect.centerY() - dp(2f), 18f, TEXT, Paint.Align.CENTER);
        label(c, "from 0 m", restartRect.centerX(), restartRect.centerY() + dp(22f), 10f, DIM, Paint.Align.CENTER);
    }
}
