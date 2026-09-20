package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Collector: buoys drift toward you in three lanes - slow, steady, fast. Each lane's buoy is only
 * caught if your boat speed is in that lane's band when it reaches you. Makes variable-pace work
 * playful rather than tedious: the fast lane pays most, and you have to change gear to reach it.
 *
 * <p>3.19.4, from the first emulator screenshots (a dark screen with one ring on it): a sky and a
 * bank with a crowd, reeds along the near shore, coins / gems / stars with glow and spin instead of
 * rings, a burst and a floating "+N" on every catch, ducks and jumping fish, and speed lines in the
 * fast lane.
 *
 * <p>The five upgrades the rower approved:
 * <ul>
 * <li><b>Logs</b> float down the lane you are in. Change gear to change lane before one reaches
 * you, or it costs points and the chain. Dodging one pays a little.</li>
 * <li><b>Magnet</b>: strong drives (per-stroke peak watts near the rower's own p90) charge a meter;
 * three of them release a magnet pickup in your lane. Catch it and for ten seconds prizes in every
 * lane are pulled to the boat.</li>
 * <li><b>Golden fish</b>: rare, fast, leaping, worth 15 - and it always turns up in a lane you are
 * not in, so it is a lane change with a deadline.</li>
 * <li><b>Places</b>: the three-minute game rows through the river, a mountain lake, the open sea
 * and a jungle river, 45 s each, each with its own sky, water, banks and wildlife.</li>
 * <li><b>Multiplier</b>: every five catches in a chain raise it (x1 to x5), drawn as a badge that
 * grows with it and a ring that drains - catch again before the ring empties or the chain breaks.
 * This replaces the old flat +5 every fifth catch.</li>
 * </ul>
 *
 * <p>3.23.0 adds five more, all of which outlive a single run:
 * <ul>
 * <li><b>The collection book</b>: twenty species, five in each place - the three lane prizes, the
 * golden fish and the rare. A species you have never caught pays a finder's bonus and a card
 * flies up with its name; completing a place's five is a set, worth {@value #SET_BONUS} and a
 * permanent +1 on every lane prize caught there afterwards. Tap the BOOK badge to read it.</li>
 * <li><b>The golden minute</b>: once a run the water turns gold for sixty seconds - prizes come
 * thick and fast, logs stop, and everything pays double. Five seconds of warning first, a
 * countdown while it runs, and the points it paid are their own record.</li>
 * <li><b>Upgrading the boat</b>: a run's points are banked rather than spent, and the bank buys
 * three levels each of NET (a wider catch and a longer magnet reach), PROW (a narrower hull to
 * the logs, and less lost when one lands) and CREEL (a longer chain before it breaks). Tap the
 * BOAT badge for the yard.</li>
 * <li><b>Rare spawns</b>: hold one speed band without leaving it - {@value #HOLD_EASY}s in EASY
 * down to {@value #HOLD_FAST}s in FAST - and that place's rare surfaces in the lane you are
 * holding. It only comes to a boat still in the band, so the hold has to last through the catch.
 * The rare is the fifth slot of every set, so the book cannot be finished without them.</li>
 * <li><b>The daily route</b>: every log, golden fish and the golden minute come from a layout
 * seeded by the date, the same for every attempt today, drawn as a ribbon across the top with a
 * cursor running along it. Today's best is on the ribbon to beat. Tap the pill to swap to FREE
 * ROW, which rolls a fresh layout each run.</li>
 * </ul>
 */
final class CollectorGame extends GameView {

    private static final int LANES = 3;
    // Measured speed while rowing: p10 3.0, median 3.85, p90 4.06 m/s. The old bands topped
    // out at 3.4, so the boat sat in the fast lane permanently and changing lane meant nearly
    // stopping - which is what read as lag. These three straddle the real working range.
    // 3.15.0: set from the rower's profile in onStart - the same three bands for a rower at
    // 3.0 / 3.85 / 4.06 m/s (low / typical / high), and the right ones for anyone else.
    private final float[] bandLo = {2.5f, 3.4f, 4.0f};
    private final float[] bandHi = {3.4f, 4.0f, 9.0f};
    /** Lane boundaries are sticky, so a speed sitting on a band edge cannot flicker. */
    private static final float BAND_STICK = 0.08f;
    private static final int[] POINTS = {1, 2, 4};
    private static final String[] NAMES = {"EASY", "STEADY", "FAST"};
    private static final String[] PLUS = {"+1", "+2", "+4"};

    /* ---------- chain and multiplier ---------- */
    /** A catch must follow the last within this long or the chain breaks. A lane's prizes come
     *  every ~6.5-15 s (the EASY lane's slowest respawn is ~15 s), so staying in any lane keeps a
     *  chain alive; stopping does not. 12 s broke chains in the EASY lane while sitting in it. */
    private static final double CHAIN_SECONDS = 16;
    private static final int MAX_MULT = 5;
    private static final String[] MULT_TEXT = {"x0", "x1", "x2", "x3", "x4", "x5"};

    /* ---------- golden fish ---------- */
    private static final int GOLD_POINTS = 15;
    private static final int GOLD = 0xFFFFC21A;

    /* ---------- logs ---------- */
    private static final int LOG_PENALTY = 3;
    private static final int DODGE_POINTS = 2;
    private static final float LOG_SPEED = 0.13f; // screen widths per second: ~6.7 s to reach you

    /* ---------- magnet ---------- */
    private static final int CHARGE_NEEDED = 3;
    private static final double MAGNET_SECONDS = 10;

    /* ---------- places ---------- */
    private static final int RIVER = 0;
    private static final int LAKE = 1;
    private static final int SEA = 2;
    private static final int JUNGLE = 3;
    private static final double PLACE_SECONDS = 45;
    private static final String[] PLACE_NAMES = {"THE RIVER", "MOUNTAIN LAKE", "OPEN SEA", "JUNGLE RIVER"};
    private static final int[] SKY_TOP = {0xFF3C7BC0, 0xFF4F86C6, 0xFF2A86D8, 0xFF3F7F5E};
    private static final int[] SKY_BOTTOM = {0xFFBFE1F5, 0xFFE6F0F6, 0xFFD2F0FF, 0xFFD9E6A8};
    /** Laid over the shared dark water so each place has its own colour. */
    private static final int[] WATER_TINT = {0x00000000, 0x3340A6B4, 0x55127FC4, 0x55506A1E};
    private static final int[] LOG_COLOR = {0xFF7A4E2A, 0xFF6E4A2C, 0xFF9C9486, 0xFF5E5A2A};

    /* ---------- the collection book ---------- */
    /** Five species in every place: the three lane prizes, the golden fish, the rare. */
    private static final int SLOTS = 5;
    private static final int SPECIES = 4 * SLOTS;
    private static final int NEW_FIND_BONUS = 10;
    private static final int SET_BONUS = 50;
    private static final int SH_COIN = 0;
    private static final int SH_GEM = 1;
    private static final int SH_STAR = 2;
    private static final int SH_SHELL = 3;
    private static final int SH_FLOWER = 4;
    private static final int SH_FISH = 5;
    private static final int SH_RELIC = 6;
    private static final String[] SPECIES_NAME = {
            "RIVER PENNY", "MOSS GEM", "RIVER STAR", "GOLDEN TROUT", "BRASS LANTERN",
            "SILVER COIN", "ICE GEM", "SNOW STAR", "GOLDEN CHAR", "LOST LOCKET",
            "CONCH SHELL", "SEA GLASS", "STARFISH", "GOLDEN MARLIN", "SHIP'S CHEST",
            "JUNGLE ORCHID", "JADE BEAD", "SUN STAR", "GOLDEN PIRANHA", "STONE IDOL"};
    private static final int[] SPECIES_COLOR = {
            0xFFFFD34D, 0xFF4FE3FF, 0xFFFF7AE0, GOLD, 0xFFC98A3A,
            0xFFDCE6F0, 0xFFA8E4FF, 0xFFFFFFFF, GOLD, 0xFFB9C6D6,
            0xFFF6E7D0, 0xFF7FF0D8, 0xFFF07A4A, GOLD, 0xFF8FB8D8,
            0xFFFF6B6B, 0xFF6FE07A, 0xFFFFE066, GOLD, 0xFFB9A06A};
    private static final int[] SPECIES_SHAPE = {
            SH_COIN, SH_GEM, SH_STAR, SH_FISH, SH_RELIC,
            SH_COIN, SH_GEM, SH_STAR, SH_FISH, SH_RELIC,
            SH_SHELL, SH_GEM, SH_STAR, SH_FISH, SH_RELIC,
            SH_FLOWER, SH_GEM, SH_STAR, SH_FISH, SH_RELIC};

    /* ---------- the golden minute ---------- */
    private static final double RUSH_SECONDS = 60;

    /* ---------- rare spawns ---------- */
    private static final int RARE_POINTS = 10;
    private static final int HOLD_EASY = 22;
    private static final int HOLD_FAST = 14;
    /** Seconds the boat must stay inside one band before that place's rare surfaces in it. The
     *  fast band is the hardest to sit in, so it asks for the least. */
    private static final double[] HOLD_NEEDED = {HOLD_EASY, 18, HOLD_FAST};
    private static final double RARE_COOLDOWN = 30;
    private static final float RARE_SPEED = 0.09f;

    /* ---------- the boat yard ---------- */
    private static final int UPGRADES = 3;
    private static final int MAX_LEVEL = 3;
    private static final int[] UPGRADE_COST = {120, 300, 600};
    private static final String[] UP_NAME = {"NET", "PROW", "CREEL"};
    private static final String[] UP_BLURB = {
            "A wider catch, and the magnet reaches further ahead.",
            "A narrower hull to a log, and less lost when one lands.",
            "A longer chain: more seconds before the multiplier breaks."};

    /* ---------- overlays ---------- */
    private static final int OV_NONE = 0;
    private static final int OV_BOOK = 1;
    private static final int OV_SHOP = 2;

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final android.graphics.Path shape = new android.graphics.Path();
    private final RectF arc = new RectF();
    private final android.graphics.LinearGradient[] skyShaders = new android.graphics.LinearGradient[4];
    private float skyHeight;
    private int combo;
    private int bestCombo;
    private double lastCatchAt;
    private int mult = 1;
    private double multPopAt = -10;
    private String popup = "";
    private int popupColor = TEXT;
    private float popupX;
    private float popupY;
    private double popupUntil;
    private String banner = "";
    private int bannerColor = TEXT;
    private double bannerUntil;
    private double fishAt = 6;
    private float fishX;
    private float fishY;

    private final float[] buoyX = new float[LANES];   // 0..1 across the screen, moving left
    private final float[] buoySpeed = new float[LANES];
    private final boolean[] caught = new boolean[LANES];
    private final boolean[] gold = new boolean[LANES];
    /** 0..1: how far the magnet has pulled each lane's prize toward the boat. */
    private final float[] pull = new float[LANES];
    private int score;
    private int logsHit;
    private int dodged;
    private int goldCaught;
    private double gameSeconds;
    private boolean started;
    /** Speed the lane follows: averaged over ~1.2 s, so a single stroke does not change lanes. */
    private float laneSpeed;
    private int lane = -1;
    private static final double GAME_LENGTH = 180;

    private boolean logLive;
    private float logX;
    private int logLane;
    private boolean logHitThis;

    /** Peak watts seen since the last stroke landed, gathered in onStatusChanged. */
    private int strokePeak;
    private float strongWatts = 157f;
    private float charge;
    /** When the last strong stroke landed; the charge only fades after a spell without one. */
    private double lastStrongAt = -100;
    private static final double CHARGE_HOLD_SECONDS = 10;
    /** Lane captions, built when the bands are set rather than formatted every frame. */
    private final String[] laneCaption = new String[LANES];
    private boolean magLive;
    private float magX;
    private int magLane;
    private double magnetUntil;

    private int place;
    private double placeChangedAt = -10;

    private float boatY;

    /* ---------- the collection book ---------- */
    /** One bit per species, kept across sessions as {@code collector.book}. */
    private int found;
    /** One bit per place whose five species are all found. */
    private int sets;
    private int newFinds;
    private int newFindIndex = -1;
    private double newFindAt = -10;

    /* ---------- the golden minute ---------- */
    private double rushAt;
    private double rushUntil = -1;
    private boolean rushActive;
    private boolean rushWarned;
    private boolean rushEnded;
    private int rushScore;
    private float sparkleTimer;

    /* ---------- rare spawns ---------- */
    private int holdLane = -1;
    private double holdSeconds;
    private boolean rareLive;
    private float rareX;
    private int rareLane;
    /** Fixed when it surfaces, so a place change while it drifts in cannot rename it. */
    private int rareIndex;
    private int rareCaught;
    private double rareCooldownUntil;

    /* ---------- the boat yard ---------- */
    private final int[] up = new int[UPGRADES];
    private float bank;
    private boolean banked;

    /* ---------- the daily route ---------- */
    private boolean dailyMode = true;
    private long routeDay;
    private int routeBest;
    private boolean routeBeaten;
    private long rngState;
    private final double[] logSchedule = new double[24];
    private int logCount;
    private int logIndex;
    private final double[] goldSchedule = new double[8];
    private final int[] goldLaneSchedule = new int[8];
    private int goldCount;
    private int goldIndex;

    /* ---------- HUD text, rebuilt only when it changes ----------
     * Everything below is drawn every frame but changes at most a few times a second, so it is
     * built once on change rather than concatenated in render. */
    private final String[] prizeLabel = new String[LANES];
    private int prizeLabelKey = -1;
    private String badgeBook = "";
    private String badgeBookSub = "";
    private String badgeBoat = "";
    private String badgeBoatSub = "";
    private int badgeFound = -1;
    private int badgeSets = -1;
    private int badgeNew = -1;
    private int badgeBank = -1;
    private int badgeUp = -1;
    private String holdText = "";
    private int holdTextLane = -2;
    private int holdTextSecs = -1;
    private String rareText = "";
    private String routeBestText = "FIRST RUN";
    private int routeBestShown = -1;
    private String newFindSub = "";

    /* ---------- overlays and their tap targets ---------- */
    private int overlay = OV_NONE;
    private final RectF bookBtn = new RectF();
    private final RectF shopBtn = new RectF();
    private final RectF modeBtn = new RectF();
    private final RectF panel = new RectF();
    private final RectF[] cardRect = {new RectF(), new RectF(), new RectF()};

    CollectorGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        score = 0;
        logsHit = 0;
        dodged = 0;
        goldCaught = 0;
        gameSeconds = 0;
        started = false;
        float low = (float) profile.lowSpeed();
        float typical = (float) profile.typicalSpeed();
        float high = (float) profile.highSpeed();
        bandLo[0] = low - 0.5f;
        bandHi[0] = (low + typical) / 2f;
        bandLo[1] = bandHi[0];
        bandHi[1] = (typical + high) / 2f + 0.05f;
        bandLo[2] = bandHi[1];
        bandHi[2] = 9f;
        // A "strong" drive: the stroke's peak reading near the rower's own p90 watts (162 W on the
        // measured envelope, so ~157 W). Steady rowing at the median does not reach it; a push does.
        strongWatts = (float) Math.max(profile.typicalWatts() * 1.15, profile.highWatts() * 0.97);
        for (int i = 0; i < LANES; i++) {
            laneCaption[i] = NAMES[i] + "  " + String.format(java.util.Locale.US, "%.1f-%s m/s",
                    bandLo[i], i == LANES - 1 ? "" : String.format(java.util.Locale.US, "%.1f", bandHi[i]));
        }
        lastStrongAt = -100;
        laneSpeed = 0f;
        lane = -1;
        combo = 0;
        bestCombo = 0;
        mult = 1;
        lastCatchAt = 0;
        popupUntil = 0;
        bannerUntil = 0;
        logLive = false;
        strokePeak = 0;
        charge = 0;
        magLive = false;
        magnetUntil = 0;
        place = RIVER;
        placeChangedAt = -10;
        overlay = OV_NONE;
        newFinds = 0;
        newFindIndex = -1;
        newFindAt = -10;
        rushUntil = -1;
        rushActive = false;
        rushWarned = false;
        rushEnded = false;
        rushScore = 0;
        sparkleTimer = 0f;
        holdLane = -1;
        holdSeconds = 0;
        rareLive = false;
        rareIndex = 4;
        rareCaught = 0;
        rareCooldownUntil = 0;
        banked = false;
        routeBeaten = false;
        logIndex = 0;
        goldIndex = 0;
        // sessionSeconds restarts at 0 here, so the jumping fish's next-jump time has to as well;
        // left alone it kept a value from the previous run and nothing ever jumped again.
        fishAt = 6;
        prizeLabelKey = -1;
        badgeFound = -1;
        holdTextLane = -2;
        routeBestShown = -1;
        loadSaved();
        buildRoute();
        for (int i = 0; i < LANES; i++) {
            respawn(i, 1.2f + i * 0.35f);
        }
    }

    /* =====================================================================
     * Saved state: the book, the boat, the bank and today's route
     * ===================================================================== */

    /** Local day number, the seed for the daily route. */
    private static long today() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    private void loadSaved() {
        found = 0;
        String book = bests.getString("collector.book");
        if (book != null) {
            try {
                found = (int) (Long.parseLong(book, 16) & ((1L << SPECIES) - 1));
            } catch (NumberFormatException ignored) {
                found = 0;
            }
        }
        sets = 0;
        for (int p = 0; p < 4; p++) {
            if ((found & setMask(p)) == setMask(p)) {
                sets |= 1 << p;
            }
        }
        for (int i = 0; i < UPGRADES; i++) {
            up[i] = 0;
        }
        String boat = bests.getString("collector.boat");
        if (boat != null) {
            String[] parts = boat.split(",");
            for (int i = 0; i < UPGRADES && i < parts.length; i++) {
                try {
                    up[i] = Math.max(0, Math.min(MAX_LEVEL, Integer.parseInt(parts[i].trim())));
                } catch (NumberFormatException ignored) {
                    up[i] = 0;
                }
            }
        }
        bank = Math.max(0f, bests.get("collector.bank", 0f));
        dailyMode = !"free".equals(bests.getString("collector.mode"));
        routeDay = today();
        routeBest = 0;
        String route = bests.getString("collector.route");
        if (route != null) {
            int sep = route.indexOf(':');
            if (sep > 0) {
                try {
                    if (Long.parseLong(route.substring(0, sep)) == routeDay) {
                        routeBest = Integer.parseInt(route.substring(sep + 1));
                    }
                } catch (NumberFormatException ignored) {
                    routeBest = 0;
                }
            }
        }
    }

    private static int setMask(int place) {
        return 0x1F << (place * SLOTS);
    }

    private void saveBook() {
        bests.putString("collector.book", Integer.toHexString(found));
        bests.recordHighest("collector.sets", Integer.bitCount(sets));
    }

    private void saveBoat() {
        bests.putString("collector.boat", up[0] + "," + up[1] + "," + up[2]);
        bests.putFloat("collector.bank", bank);
    }

    /** A run's points are banked, not spent: the yard buys upgrades out of the bank. */
    private void bankRun() {
        if (banked || !started) {
            return;
        }
        banked = true;
        bank += score;
        saveBoat();
        saveBook();
        if (dailyMode && score > routeBest) {
            routeBest = score;
            bests.putString("collector.route", routeDay + ":" + routeBest);
        }
    }

    @Override
    protected void onStop() {
        bankRun();
    }

    /* =====================================================================
     * The route: a fixed layout for the day
     * ===================================================================== */

    /** A small LCG, so a day's layout is the same on every attempt. */
    private float rnd() {
        rngState = rngState * 6364136223846793005L + 1442695040888963407L;
        return (int) (rngState >>> 41) / (float) (1 << 23);
    }

    private void buildRoute() {
        long seed = dailyMode ? routeDay * 7919L + 13L : System.nanoTime();
        rngState = seed * 6364136223846793005L + 1442695040888963407L;
        logCount = 0;
        double t = 9 + rnd() * 3;
        while (t < GAME_LENGTH - 6 && logCount < logSchedule.length) {
            logSchedule[logCount++] = t;
            t += 7 + rnd() * 7;
        }
        goldCount = 0;
        double g = 18 + rnd() * 10;
        while (g < GAME_LENGTH - 8 && goldCount < goldSchedule.length) {
            goldLaneSchedule[goldCount] = (int) Math.min(LANES - 1, rnd() * LANES);
            goldSchedule[goldCount++] = g;
            g += 26 + rnd() * 18;
        }
        // The golden minute lands late enough that the run has warmed up and early enough to end
        // inside it: 80 s to (length - 60) s.
        rushAt = 80 + rnd() * (float) Math.max(1, GAME_LENGTH - RUSH_SECONDS - 80);
    }

    private void respawn(int lane, float at) {
        buoyX[lane] = at;
        buoySpeed[lane] = (0.09f + lane * 0.03f + rnd() * 0.03f) * (rushActive ? 1.4f : 1f);
        caught[lane] = false;
        gold[lane] = false;
        pull[lane] = 0f;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
        }
        // Magnitudes come from readings, never from onStroke (which lands ~1 s late).
        strokePeak = Math.max(strokePeak, s.watts);
    }

    @Override
    protected void onStroke(int watts) {
        // Only counting here: judge the peak gathered since the previous stroke, then reset it.
        if (started && gameSeconds < GAME_LENGTH && strokePeak >= strongWatts && !magLive
                && sessionSeconds >= magnetUntil) {
            // Whole pips: a partly faded pip is topped back up, so three strong strokes in a row
            // always fill it (a continuous fade had made it take four).
            charge = Math.min(CHARGE_NEEDED, (float) Math.floor(charge + 0.001f) + 1f);
            lastStrongAt = sessionSeconds;
        }
        strokePeak = 0;
    }

    /**
     * There are no header chips on this screen, so the book, the boat yard and the route mode are
     * all tap targets drawn on the canvas. An open overlay swallows the tap.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(event);
        }
        float x = event.getX();
        float y = event.getY();
        if (overlay != OV_NONE) {
            if (overlay == OV_SHOP) {
                for (int i = 0; i < UPGRADES; i++) {
                    if (cardRect[i].contains(x, y)) {
                        buy(i);
                        return true;
                    }
                }
            }
            if (!panel.contains(x, y)) {
                overlay = OV_NONE;
            }
            return true;
        }
        if (bookBtn.contains(x, y)) {
            overlay = OV_BOOK;
            return true;
        }
        if (shopBtn.contains(x, y)) {
            overlay = OV_SHOP;
            return true;
        }
        if (modeBtn.contains(x, y)) {
            bankRun();
            dailyMode = !dailyMode;
            bests.putString("collector.mode", dailyMode ? "daily" : "free");
            start();
            return true;
        }
        if (gameSeconds >= GAME_LENGTH) {
            start();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void buy(int track) {
        if (up[track] >= MAX_LEVEL) {
            return;
        }
        int cost = UPGRADE_COST[up[track]];
        if (bank < cost) {
            showBanner("NEED " + (cost - (int) bank) + " MORE POINTS", BAD);
            return;
        }
        bank -= cost;
        up[track]++;
        saveBoat();
        showBanner(UP_NAME[track] + " " + up[track] + " FITTED", ACCENT);
    }

    /* ---------- scoring ---------- */

    private int points(int base) {
        return base * mult * (rushActive ? 2 : 1);
    }

    /** Every point added goes through here, so the golden minute can keep its own total. */
    private void addScore(int pts) {
        score += pts;
        if (rushActive) {
            rushScore += pts;
        }
    }

    /** A lane prize is worth one more once this place's set is complete in the book. */
    private int lanePoints(int lane) {
        return POINTS[lane] + ((sets & (1 << place)) != 0 ? 1 : 0);
    }

    private float catchRadius() {
        return dp(18f + up[0] * 7f);
    }

    private double chainSeconds() {
        return CHAIN_SECONDS + 3 * up[2];
    }

    /** Marks a species found the first time it is caught: a bonus, a card, and maybe a set. */
    private void markFind(int index, float x, float y) {
        int bit = 1 << index;
        if ((found & bit) != 0) {
            return;
        }
        found |= bit;
        newFinds++;
        newFindIndex = index;
        newFindSub = "NEW IN THE BOOK  ·  " + PLACE_NAMES[index / SLOTS];
        newFindAt = sessionSeconds;
        addScore(NEW_FIND_BONUS * mult);
        fx.burst(x, y, 44, dp(240f), 1.1f, dp(3f), SPECIES_COLOR[index], true);
        showBanner("NEW!  " + SPECIES_NAME[index] + "  +" + (NEW_FIND_BONUS * mult), 0xFFFFFFFF);
        int p = index / SLOTS;
        if ((found & setMask(p)) == setMask(p) && (sets & (1 << p)) == 0) {
            sets |= 1 << p;
            addScore(SET_BONUS);
            shake.kick(dp(7f));
            fx.burst(x, y, 60, dp(300f), 1.4f, dp(4f), GOLD, true);
            showBanner(PLACE_NAMES[p] + " SET COMPLETE  +" + SET_BONUS, GOLD);
        }
        saveBook();
    }

    /** Shared by every kind of catch: the chain, the multiplier step and its pop. */
    private void registerCatch(float x, float y) {
        combo++;
        bestCombo = Math.max(bestCombo, combo);
        lastCatchAt = gameSeconds;
        int newMult = Math.min(MAX_MULT, 1 + combo / 5);
        if (newMult > mult) {
            multPopAt = sessionSeconds;
            fx.burst(x, y, 40, dp(240f), 1.1f, dp(3.5f), 0xFFF5C518, true);
            showBanner("MULTIPLIER " + MULT_TEXT[newMult] + "!", 0xFFF5C518);
        }
        mult = newMult;
    }

    private void showPopup(String text, int color, float x, float y) {
        popup = text;
        popupColor = color;
        popupX = x;
        popupY = y;
        popupUntil = sessionSeconds + 1.0;
    }

    private void showBanner(String text, int color) {
        banner = text;
        bannerColor = color;
        bannerUntil = sessionSeconds + 2.4;
    }

    /**
     * Rebuilds the handful of HUD strings that change a few times a second at most - the prize
     * values, the two shore badges, the hold meter and the route's target. Called once a frame
     * before anything draws them, so {@link #render} itself concatenates nothing.
     */
    private void updateLabels() {
        int key = place * 4 + ((sets & (1 << place)) != 0 ? 2 : 0) + (rushActive ? 1 : 0);
        if (key != prizeLabelKey) {
            prizeLabelKey = key;
            for (int i = 0; i < LANES; i++) {
                // The set bonus is real points, so it belongs on the label too: before this the
                // water said "+1" while the catch paid 2.
                prizeLabel[i] = "+" + (lanePoints(i) * (rushActive ? 2 : 1));
            }
        }
        int upKey = up[0] | (up[1] << 2) | (up[2] << 4);
        int bankKey = (int) bank;
        if (found != badgeFound || sets != badgeSets || newFinds != badgeNew
                || bankKey != badgeBank || upKey != badgeUp) {
            badgeFound = found;
            badgeSets = sets;
            badgeNew = newFinds;
            badgeBank = bankKey;
            badgeUp = upKey;
            badgeBook = "BOOK  " + Integer.bitCount(found) + "/" + SPECIES;
            badgeBookSub = newFinds > 0 ? "+" + newFinds + " NEW THIS RUN  ·  TAP"
                    : Integer.bitCount(sets) + " OF 4 SETS  ·  TAP";
            badgeBoat = "BOAT  " + bankKey + " PTS";
            badgeBoatSub = "NET " + up[0] + " · PROW " + up[1] + " · CREEL " + up[2] + "  ·  TAP";
        }
        int secs = (int) holdSeconds;
        if (holdLane != holdTextLane || secs != holdTextSecs) {
            holdTextLane = holdLane;
            holdTextSecs = secs;
            holdText = holdLane >= 0 ? "HELD " + NAMES[holdLane] + "  " + secs + "/"
                    + (int) HOLD_NEEDED[holdLane] + "s" : "";
        }
        if (routeBest != routeBestShown) {
            routeBestShown = routeBest;
            routeBestText = routeBest > 0 ? "BEAT " + routeBest : "FIRST RUN";
        }
    }

    private void breakChain() {
        if (combo >= 5) {
            showBanner("CHAIN BROKEN", BAD);
        }
        combo = 0;
        mult = 1;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();
        boolean over = gameSeconds >= GAME_LENGTH;
        boolean playing = started && !over;
        if (playing) {
            gameSeconds += dt;
            if (gameSeconds >= GAME_LENGTH) {
                bests.recordHighest("collector.score", score);
                bests.recordHighest("collector.gold", goldCaught);
                rushActive = false;
                boolean beat = dailyMode && score > routeBest;
                bankRun();
                over = true;
                playing = false;
                routeBeaten = beat;
                showBanner(beat ? "NEW ROUTE BEST - " + score + " POINTS"
                        : "TIME - " + score + " POINTS", beat ? GOLD : ACCENT);
            }
        }
        if (playing) {
            int p = (int) Math.min(3, gameSeconds / PLACE_SECONDS);
            if (p != place) {
                place = p;
                placeChangedAt = sessionSeconds;
                showBanner("ENTERING " + PLACE_NAMES[place], TEXT);
            }
            if (sessionSeconds - lastStrongAt > CHARGE_HOLD_SECONDS) {
                charge = Math.max(0f, charge - dt * 0.1f); // a charge fades if the pushing stops
            }
        }
        // The golden minute: announced five seconds out, then sixty seconds of double points and
        // fast water with no logs in it. Its own points are their own record.
        if (playing && rushUntil < 0) {
            if (!rushWarned && rushAt - gameSeconds <= 5) {
                rushWarned = true;
                showBanner("GOLDEN MINUTE IN 5", GOLD);
            }
            if (gameSeconds >= rushAt) {
                rushUntil = gameSeconds + RUSH_SECONDS;
                shake.kick(dp(8f));
                showBanner("GOLDEN MINUTE - EVERYTHING PAYS DOUBLE", GOLD);
            }
        }
        rushActive = playing && rushUntil > 0 && gameSeconds < rushUntil;
        if (rushUntil > 0 && !rushActive && !rushEnded) {
            rushEnded = true;
            if (rushScore > 0) {
                bests.recordHighest("collector.rush", rushScore);
            }
            if (playing) {
                showBanner("GOLDEN MINUTE OVER  +" + rushScore, GOLD);
            }
        }
        shake.step(dt);

        float waterTop = h * 0.30f;
        float waterBottom = h * 0.84f;
        float bankTop = waterTop - dp(46f);
        float ppm = w / 60f;

        c.save();
        c.translate(shake.dx, shake.dy);
        drawSky(c, w, bankTop);
        river.advance(speed, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        if (WATER_TINT[place] != 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(WATER_TINT[place]);
            c.drawRect(0, waterTop, w, waterBottom, paint);
        }
        drawBank(c, w, bankTop, waterTop, ppm);
        drawNearShore(c, w, h, waterBottom, ppm);
        fx.step(dt, dp(300f));
        drawFish(c, w, waterTop, waterBottom);

        float boatX = w * 0.22f;
        float laneH = (waterBottom - waterTop) / LANES;
        // Lanes follow an averaged speed with sticky edges ("smoothly move between zones, leveraging
        // some averaging"): the boat glides as the average moves, and only changes lane once the
        // average is clearly past a band edge.
        laneSpeed += (speed - laneSpeed) * Math.min(1f, dt / 1.2f);
        if (lane < 0 || laneSpeed < bandLo[lane] - BAND_STICK || laneSpeed >= bandHi[lane] + BAND_STICK) {
            lane = -1;
            for (int i = 0; i < LANES; i++) {
                if (laneSpeed >= bandLo[i] && laneSpeed < bandHi[i]) {
                    lane = i;
                }
            }
        }
        int inLane = lane;
        if (boatY == 0f) {
            boatY = waterTop + laneH * 0.5f;
        }
        // The lane the hull is physically in - what a log hits.
        int boatLane = Math.max(0, Math.min(LANES - 1, (int) ((boatY - waterTop) / laneH)));
        boolean magnet = sessionSeconds < magnetUntil;

        // Chain timer: a chain lives only while catches keep coming.
        if (playing && combo > 0 && gameSeconds - lastCatchAt > chainSeconds()) {
            breakChain();
        }

        // The golden fish, off the route's clock. On the daily route the lane is part of the
        // layout; rowing free it still turns up in a lane you are not in.
        if (playing && goldIndex < goldCount && gameSeconds >= goldSchedule[goldIndex]) {
            int from = inLane >= 0 ? inLane : boatLane;
            int g = dailyMode ? goldLaneSchedule[goldIndex]
                    : (from + 1 + (int) (Math.random() * 2)) % LANES;
            goldIndex++;
            respawn(g, 1.08f);
            gold[g] = true;
            buoySpeed[g] = 0.14f;
            showBanner("GOLDEN FISH IN THE " + NAMES[g] + " LANE!", GOLD);
        }

        // A log, aimed at the lane you are in - but never during the golden minute.
        if (playing && !logLive && logIndex < logCount && gameSeconds >= logSchedule[logIndex]) {
            logIndex++;
            if (!rushActive) {
                logLive = true;
                logX = 1.1f;
                logLane = boatLane;
                logHitThis = false;
            }
        }

        // Rare spawns: the reward for sitting in one band rather than drifting across them.
        if (playing && inLane >= 0) {
            if (inLane == holdLane) {
                holdSeconds += dt;
            } else {
                holdLane = inLane;
                holdSeconds = 0;
            }
        } else {
            holdLane = -1;
            holdSeconds = 0;
        }
        if (playing && holdLane >= 0 && !rareLive && gameSeconds >= rareCooldownUntil
                && holdSeconds >= HOLD_NEEDED[holdLane]) {
            rareLive = true;
            rareX = 1.1f;
            rareLane = holdLane;
            rareIndex = place * SLOTS + 4;
            holdSeconds = 0;
            rareCooldownUntil = gameSeconds + RARE_COOLDOWN;
            rareText = "RARE IN THE " + NAMES[rareLane];
            shake.kick(dp(4f));
            showBanner("RARE! " + SPECIES_NAME[rareIndex] + " - HOLD THE "
                    + NAMES[rareLane] + " BAND", 0xFF9AE6FF);
        }

        // A full charge releases the magnet into your lane.
        if (playing && charge >= CHARGE_NEEDED && !magLive) {
            charge = 0f;
            magLive = true;
            magX = 0.8f;
            magLane = boatLane;
            showBanner("MAGNET! CATCH IT", 0xFFFF5A5A);
        }

        updateLabels();
        for (int i = 0; i < LANES; i++) {
            float ly = waterTop + laneH * (i + 0.5f);
            // Lane band label and highlight when you are in it; red pulse if a log is coming down it.
            boolean danger = logLive && i == logLane && i == boatLane && logX * w > boatX;
            if (danger) {
                int a = 30 + (int) (40 * Math.abs(Math.sin(sessionSeconds * 8)));
                paint.setColor((a << 24) | 0x00F0655D);
            } else {
                paint.setColor(i == inLane ? 0x2235D0BA : 0x00000000);
            }
            paint.setStyle(Paint.Style.FILL);
            c.drawRect(0, waterTop + laneH * i, w, waterTop + laneH * (i + 1), paint);
            label(c, laneCaption[i] != null ? laneCaption[i] : NAMES[i],
                    w - dp(14f), waterTop + laneH * i + dp(20f), 11f, i == inLane ? ACCENT : DIM,
                    Paint.Align.RIGHT);
            if (i > 0) {
                scenery.drawBuoys(c, w, waterTop + laneH * i, sessionMeters, ppm, sessionSeconds, 8f);
            }

            if (playing) {
                buoyX[i] -= buoySpeed[i] * dt;
                float bx = buoyX[i] * w;
                // The magnet reaches ahead of the boat and draws prizes across the lanes to it.
                if (magnet && !caught[i] && bx > boatX - dp(10f)
                        && bx < boatX + w * (0.35f + up[0] * 0.07f)) {
                    pull[i] = Math.min(1f, pull[i] + dt * 1.6f);
                }
                if (!caught[i] && Math.abs(bx - boatX) < catchRadius()
                        && (i == inLane || pull[i] > 0.5f)) {
                    caught[i] = true;
                    float cy = ly + (boatY - ly) * pull[i];
                    registerCatch(boatX, boatY);
                    if (gold[i]) {
                        int got = points(GOLD_POINTS);
                        addScore(got);
                        goldCaught++;
                        fx.burst(bx, cy, 60, dp(260f), 1.2f, dp(4f), GOLD, true);
                        fx.burst(bx, cy, 20, dp(120f), 0.9f, dp(2.5f), 0xFFFFFFFF, false);
                        shake.kick(dp(5f));
                        showPopup("GOLDEN FISH +" + got, GOLD, bx, cy - dp(24f));
                        markFind(place * SLOTS + 3, bx, cy);
                    } else {
                        int got = points(lanePoints(i));
                        addScore(got);
                        int colour = SPECIES_COLOR[place * SLOTS + i];
                        fx.burst(bx, cy, 18 + i * 8, dp(150f), 0.7f, dp(3f), colour, false);
                        showPopup(mult > 1 || rushActive ? "+" + got + "  " + MULT_TEXT[mult]
                                : PLUS[i], colour, bx, cy - dp(20f));
                        markFind(place * SLOTS + i, bx, cy);
                    }
                }
                if (buoyX[i] < -0.05f) {
                    if (gold[i] && !caught[i]) {
                        showBanner("THE GOLDEN FISH GOT AWAY", DIM);
                    }
                    respawn(i, rushActive ? 1.0f + rnd() * 0.1f : 1.02f + rnd() * 0.3f);
                }
            }
            float bx = buoyX[i] * w;
            if (!caught[i] && bx > -dp(40f) && bx < w + dp(40f)) {
                float py = ly + (boatY - ly) * pull[i];
                if (gold[i]) {
                    drawGoldFish(c, bx, py, i);
                    bold(c, "+" + GOLD_POINTS, bx, py - dp(46f), 14f, GOLD, Paint.Align.CENTER);
                } else {
                    drawPrize(c, i, bx, py + (float) Math.sin(sessionSeconds * 3 + i) * dp(3f));
                    bold(c, prizeLabel[i] != null ? prizeLabel[i] : PLUS[i], bx, py - dp(30f), 12f,
                            rushActive ? GOLD : TEXT, Paint.Align.CENTER);
                }
            }
        }

        // The rare, once a hold has earned it: slow, glowing, and only caught by a boat still in
        // the band it surfaced in.
        if (rareLive) {
            float rx = rareX * w;
            float ry = waterTop + laneH * (rareLane + 0.5f);
            if (playing) {
                rareX -= RARE_SPEED * dt;
                if (Math.abs(rx - boatX) < catchRadius() * 1.4f && inLane == rareLane) {
                    rareLive = false;
                    rareCaught++;
                    registerCatch(boatX, boatY);
                    int got = points(RARE_POINTS);
                    addScore(got);
                    int idx = rareIndex;
                    fx.burst(rx, ry, 70, dp(280f), 1.3f, dp(4f), SPECIES_COLOR[idx], true);
                    shake.kick(dp(8f));
                    showPopup(SPECIES_NAME[idx] + " +" + got, 0xFF9AE6FF, rx, ry - dp(30f));
                    markFind(idx, rx, ry);
                } else if (rareX < -0.06f) {
                    rareLive = false;
                    showBanner("THE RARE SANK AWAY", DIM);
                }
            }
            if (rareLive) {
                drawRare(c, rx, ry, rareIndex);
            }
        }

        // The magnet pickup travels down its lane; only the lane you are in can take it.
        if (magLive) {
            if (playing) {
                magX -= 0.15f * dt;
            }
            float mx = magX * w;
            float my = waterTop + laneH * (magLane + 0.5f) + (float) Math.sin(sessionSeconds * 4) * dp(4f);
            Fx.glow(c, mx, my, dp(46f), 0x66FF5A5A);
            drawMagnet(c, mx, my, dp(16f), (float) Math.sin(sessionSeconds * 3) * 15f);
            if (Math.abs(mx - boatX) < dp(24f) && Math.abs(boatY - my) < laneH * 0.55f) {
                magLive = false;
                magnetUntil = sessionSeconds + MAGNET_SECONDS;
                fx.burst(mx, my, 36, dp(220f), 0.9f, dp(3f), 0xFFFF5A5A, true);
                showBanner("MAGNET ON - EVERY LANE COMES TO YOU", 0xFFFF5A5A);
            } else if (magX < -0.05f) {
                magLive = false;
                showBanner("MISSED THE MAGNET", DIM);
            }
        }

        // The log.
        if (logLive) {
            if (playing) {
                logX -= LOG_SPEED * dt;
            }
            float lx = logX * w;
            float ly = waterTop + laneH * (logLane + 0.5f);
            // A fitted PROW is a narrower hull to a log, and it costs less when one lands.
            if (playing && !logHitThis && Math.abs(lx - boatX) < dp(58f - up[1] * 9f)
                    && Math.abs(boatY - ly) < laneH * 0.55f) {
                logHitThis = true;
                logsHit++;
                int cost = Math.max(1, LOG_PENALTY - up[1]);
                score = Math.max(0, score - cost);
                if (rushActive) {
                    rushScore = Math.max(0, rushScore - cost);
                }
                breakChain();
                shake.kick(dp(14f));
                fx.burst(boatX + dp(40f), ly, 30, dp(200f), 0.9f, dp(3.5f), LOG_COLOR[place], true);
                fx.burst(boatX + dp(40f), ly, 24, dp(160f), 0.7f, dp(2.5f), 0xDDDDF2FF, true);
                showPopup("LOG!  -" + cost, BAD, boatX, boatY - dp(50f));
            }
            drawLog(c, lx, ly, logHitThis);
            if (lx > w - dp(10f)) {
                // Warning chevron at the edge while it is still coming on.
                float a = (float) Math.abs(Math.sin(sessionSeconds * 9));
                bold(c, "LOG >", w - dp(20f), ly + dp(6f), 16f,
                        ((int) (120 + 135 * a) << 24) | 0x00F0655D, Paint.Align.RIGHT);
            }
            if (logX < -0.1f) {
                logLive = false;
                if (!logHitThis && playing) {
                    dodged++;
                    addScore(DODGE_POINTS);
                    showPopup("DODGED  +" + DODGE_POINTS, ACCENT, boatX, boatY - dp(50f));
                }
            }
        }

        // The boat's height is continuous in the averaged speed - part-way through a band it sits
        // part-way across the lane - so it drifts rather than jumping lane to lane.
        float laneCoord = 0.5f;
        for (int i = 0; i < LANES; i++) {
            float hi = i == LANES - 1 ? bandLo[i] + 0.6f : bandHi[i];
            if (laneSpeed >= bandLo[i]) {
                laneCoord = i + Math.min(1f, (laneSpeed - bandLo[i]) / (hi - bandLo[i]));
            }
        }
        laneCoord = Math.max(0.5f, Math.min(LANES - 0.5f, laneCoord));
        float targetLaneY = waterTop + laneH * laneCoord;
        boatY += (targetLaneY - boatY) * Math.min(1f, 3f * dt);
        if (inLane == LANES - 1) {
            paint.setColor(0x55FFFFFF);
            Fx.speedLines(c, paint, w, waterBottom, speed, sessionSeconds, getResources().getDisplayMetrics().density);
        }
        if (combo >= 3) {
            Fx.glow(c, boatX, boatY, dp(70f + mult * 12f), 0x40F5C518);
        }
        if (magnet) {
            drawField(c, boatX, boatY, w);
        }
        river.setStrokePhase(strokePhase());
        river.bowSpray(boatX + dp(56f), boatY, speed, dt);
        river.drawBoat(c, boatX, boatY, dp(116f), ACCENT, speed, false);
        river.drawSpray(c);
        if (magnet) {
            drawMagnet(c, boatX, boatY - dp(40f), dp(11f), 0f);
        }
        fx.draw(c);
        if (combo >= 2) {
            bold(c, combo + " IN A ROW", boatX, boatY - (magnet ? dp(62f) : dp(34f)), 13f,
                    0xFFF5C518, Paint.Align.CENTER);
        }
        // The golden minute washes the whole scene gold and drops motes down the water.
        if (rushActive) {
            float edge = (float) Math.min(1.0, (rushUntil - gameSeconds) / 2.0);
            float fade = (float) Math.min(1.0, (gameSeconds - (rushUntil - RUSH_SECONDS)) / 1.5);
            int a = (int) (44 * Math.min(edge, fade));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((a << 24) | 0x00FFC21A);
            c.drawRect(0, 0, w, h, paint);
            sparkleTimer += dt;
            while (sparkleTimer > 0.05f) {
                sparkleTimer -= 0.05f;
                float sx = (float) Math.random() * w;
                float sy = waterTop + (float) Math.random() * (waterBottom - waterTop);
                fx.spawn(sx, sy, -dp(40f) - (float) Math.random() * dp(60f), -dp(20f), 1.1f,
                        dp(2.2f), GOLD, false);
            }
        }
        c.restore();

        if (sessionSeconds < popupUntil) {
            float rise = (float) (1.0 - (popupUntil - sessionSeconds)) * dp(50f);
            bold(c, popup, popupX, popupY - rise, 20f, popupColor, Paint.Align.CENTER);
        }
        // Brief white wash when a new place comes into view.
        double since = sessionSeconds - placeChangedAt;
        if (since >= 0 && since < 0.7) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (150 * (1 - since / 0.7)) << 24) | 0x00FFFFFF);
            c.drawRect(0, 0, w, h, paint);
        }

        // 3.19.5: the sky is light now (the shader-alpha fix), so the HUD sits on dark pills.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRoundRect(w / 2f - dp(90f), h * 0.15f - dp(40f), w / 2f + dp(90f), h * 0.15f + dp(28f), dp(14f), dp(14f), paint);
        c.drawRoundRect(dp(6f), h * 0.15f - dp(28f), dp(190f), h * 0.15f + dp(24f), dp(12f), dp(12f), paint);
        c.drawRoundRect(dp(6f), h * 0.15f + dp(30f), dp(190f), h * 0.15f + dp(62f), dp(12f), dp(12f), paint);
        c.drawRoundRect(w - dp(150f), h * 0.15f - dp(28f), w - dp(6f), h * 0.15f + dp(24f), dp(12f), dp(12f), paint);
        bold(c, String.valueOf(score), w / 2f, h * 0.15f, 44f, ACCENT, Paint.Align.CENTER);
        label(c, !started ? "TAKE A STROKE TO START" : over ? "TIME - TAP TO PLAY AGAIN"
                : PLACE_NAMES[place] + "  ·  " + clock(GAME_LENGTH - gameSeconds) + " LEFT",
                w / 2f, h * 0.15f + dp(20f), 10f, DIM, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.1f m/s", speed), dp(16f), h * 0.15f, 22f,
                inLane >= 0 ? ACCENT : DIM, Paint.Align.LEFT);
        label(c, inLane >= 0 ? NAMES[inLane] + " LANE" : "TOO SLOW FOR A LANE", dp(16f),
                h * 0.15f + dp(16f), 9f, DIM, Paint.Align.LEFT);
        drawChargeHud(c, h * 0.15f + dp(46f), magnet);
        drawHoldHud(c, h * 0.15f + dp(80f));
        bold(c, String.valueOf(logsHit), w - dp(16f), h * 0.15f, 22f, logsHit > 0 ? BAD : DIM, Paint.Align.RIGHT);
        label(c, "LOGS HIT  ·  " + dodged + " DODGED", w - dp(16f), h * 0.15f + dp(16f), 9f, DIM, Paint.Align.RIGHT);
        drawMultiplier(c, w / 2f + dp(190f), h * 0.15f - dp(6f));
        drawRouteRibbon(c, w, h);
        drawBadges(c, w, h);
        drawFindCard(c, w, h);
        if (rushActive) {
            drawRushClock(c, w, h);
        }

        if (sessionSeconds < bannerUntil) {
            float left = (float) (bannerUntil - sessionSeconds);
            float pop = Math.min(1f, (2.4f - left) * 6f);
            // Long banners are scaled to stay inside the pill rather than running off it.
            float fit = banner.length() > 34 ? 34f / banner.length() : 1f;
            float size = (18f + 10f * pop) * fit;
            paint.setColor(0xAA0A1420);
            c.drawRoundRect(w / 2f - dp(300f), waterTop + dp(8f), w / 2f + dp(300f), waterTop + dp(52f),
                    dp(12f), dp(12f), paint);
            bold(c, banner, w / 2f, waterTop + dp(40f), Math.min(size, 26f), bannerColor, Paint.Align.CENTER);
        }

        float fy = h - dp(14f);
        float col = w / 4f;
        // The footer sits on bright grass since the sky/shore pass: give it a dark band to read on.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRect(0, fy - dp(34f), w, h, paint);
        stat(c, col * 0.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 1.5f, fy, status == null ? "0" : String.valueOf(status.watts), "WATTS");
        stat(c, col * 2.5f, fy, goldCaught + " / " + rareCaught, "GOLD / RARE");
        stat(c, col * 3.5f, fy, bests.has("collector.score")
                ? String.valueOf(Math.round(bests.get("collector.score", 0))) : "--", "BEST");

        if (overlay == OV_BOOK) {
            drawBookOverlay(c, w, h);
        } else if (overlay == OV_SHOP) {
            drawShopOverlay(c, w, h);
        }
    }

    /* =====================================================================
     * The route ribbon, the badges and the overlays
     * ===================================================================== */

    /** The day's layout, drawn: logs, golden fish, the golden minute band, and where you are. */
    private void drawRouteRibbon(Canvas c, float w, float h) {
        float y = h * 0.055f;
        paint.setStyle(Paint.Style.FILL);
        modeBtn.set(dp(16f), y - dp(20f), dp(208f), y + dp(20f));
        paint.setColor(dailyMode ? 0xCC13324A : 0xCC0A1420);
        c.drawRoundRect(modeBtn, dp(12f), dp(12f), paint);
        bold(c, dailyMode ? "DAILY ROUTE" : "FREE ROW", dp(28f), y - dp(1f), 14f,
                dailyMode ? GOLD : TEXT, Paint.Align.LEFT);
        label(c, "TAP TO SWAP", dp(28f), y + dp(14f), 9f, DIM, Paint.Align.LEFT);

        float x0 = dp(224f);
        float x1 = w - dp(230f);
        if (x1 <= x0) {
            return;
        }
        paint.setColor(0x990A1420);
        c.drawRoundRect(x0, y - dp(15f), x1, y + dp(15f), dp(10f), dp(10f), paint);
        // The golden minute, as a band along the route.
        float rs = x0 + (x1 - x0) * (float) (rushAt / GAME_LENGTH);
        float re = x0 + (x1 - x0) * (float) Math.min(1.0, (rushAt + RUSH_SECONDS) / GAME_LENGTH);
        paint.setColor(0x66FFC21A);
        c.drawRoundRect(rs, y - dp(13f), re, y + dp(13f), dp(8f), dp(8f), paint);
        label(c, "GOLDEN MINUTE", (rs + re) / 2f, y + dp(4f), 8.5f, 0xFF3A2A00, Paint.Align.CENTER);
        for (int i = 0; i < logCount; i++) {
            float lx = x0 + (x1 - x0) * (float) (logSchedule[i] / GAME_LENGTH);
            paint.setColor(i < logIndex ? 0x667A4E2A : 0xFF9C6A38);
            c.drawRoundRect(lx - dp(2f), y - dp(8f), lx + dp(2f), y + dp(8f), dp(2f), dp(2f), paint);
        }
        for (int i = 0; i < goldCount; i++) {
            float gx = x0 + (x1 - x0) * (float) (goldSchedule[i] / GAME_LENGTH);
            paint.setColor(i < goldIndex ? 0x66FFC21A : GOLD);
            shape.rewind();
            shape.moveTo(gx, y - dp(10f));
            shape.lineTo(gx + dp(6f), y);
            shape.lineTo(gx, y + dp(10f));
            shape.lineTo(gx - dp(6f), y);
            shape.close();
            c.drawPath(shape, paint);
        }
        // Where the four places change hands.
        for (int p = 1; p < 4; p++) {
            float px = x0 + (x1 - x0) * (float) (p * PLACE_SECONDS / GAME_LENGTH);
            paint.setColor(0x44FFFFFF);
            c.drawRect(px - dp(0.8f), y - dp(15f), px + dp(0.8f), y + dp(15f), paint);
        }
        float cx = x0 + (x1 - x0) * (float) Math.min(1.0, gameSeconds / GAME_LENGTH);
        paint.setColor(0xFFFFFFFF);
        c.drawRect(cx - dp(1.6f), y - dp(19f), cx + dp(1.6f), y + dp(19f), paint);
        c.drawCircle(cx, y - dp(19f), dp(3.5f), paint);
        if (dailyMode) {
            bold(c, routeBestText, w - dp(16f), y - dp(1f), 13f,
                    routeBeaten ? GOLD : routeBest > 0 ? TEXT : DIM, Paint.Align.RIGHT);
            label(c, routeBest > 0 ? "TODAY'S BEST" : "TODAY'S ROUTE", w - dp(16f), y + dp(14f), 9f,
                    DIM, Paint.Align.RIGHT);
        } else {
            bold(c, "FRESH", w - dp(16f), y - dp(1f), 13f, DIM, Paint.Align.RIGHT);
            label(c, "LAYOUT PER RUN", w - dp(16f), y + dp(14f), 9f, DIM, Paint.Align.RIGHT);
        }
    }

    /** BOOK and BOAT: the two tap targets that open the overlays, on the near shore. */
    private void drawBadges(Canvas c, float w, float h) {
        float y = h * 0.875f;
        bookBtn.set(dp(16f), y - dp(22f), dp(196f), y + dp(22f));
        shopBtn.set(dp(208f), y - dp(22f), dp(392f), y + dp(22f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC0A1420);
        c.drawRoundRect(bookBtn, dp(12f), dp(12f), paint);
        c.drawRoundRect(shopBtn, dp(12f), dp(12f), paint);
        boolean afford = affordable();
        bold(c, badgeBook, dp(30f), y - dp(2f), 14f,
                Integer.bitCount(found) == SPECIES ? GOLD : TEXT, Paint.Align.LEFT);
        label(c, badgeBookSub, dp(30f), y + dp(14f), 9f, newFinds > 0 ? GOLD : DIM, Paint.Align.LEFT);
        bold(c, badgeBoat, dp(222f), y - dp(2f), 14f, afford ? ACCENT : TEXT, Paint.Align.LEFT);
        label(c, badgeBoatSub, dp(222f), y + dp(14f), 9f, afford ? ACCENT : DIM, Paint.Align.LEFT);
    }

    private boolean affordable() {
        for (int i = 0; i < UPGRADES; i++) {
            if (up[i] < MAX_LEVEL && bank >= UPGRADE_COST[up[i]]) {
                return true;
            }
        }
        return false;
    }

    /** The card that flies up when a species is written into the book for the first time. */
    private void drawFindCard(Canvas c, float w, float h) {
        float t = (float) (sessionSeconds - newFindAt);
        if (newFindIndex < 0 || t < 0 || t > 3f) {
            return;
        }
        float in = Math.min(1f, t * 4f);
        float out = Math.max(0f, Math.min(1f, (3f - t) * 2.5f));
        int a = (int) (255 * Math.min(in, out));
        float cx = w * 0.5f;
        // Below the banner pill (which sits at waterTop + 8..52dp and is drawn on top of this):
        // at 0.42h the card's top edge ran under it and lost its border.
        float cy = h * 0.52f - in * dp(30f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(((a * 0xCC / 255) << 24) | 0x000A1420);
        c.drawRoundRect(cx - dp(150f), cy - dp(70f), cx + dp(150f), cy + dp(66f), dp(16f), dp(16f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor((a << 24) | (SPECIES_COLOR[newFindIndex] & 0x00FFFFFF));
        c.drawRoundRect(cx - dp(150f), cy - dp(70f), cx + dp(150f), cy + dp(66f), dp(16f), dp(16f), paint);
        paint.setStyle(Paint.Style.FILL);
        drawSpecies(c, newFindIndex, cx, cy - dp(16f), dp(26f));
        bold(c, SPECIES_NAME[newFindIndex], cx, cy + dp(34f), 16f,
                (a << 24) | (SPECIES_COLOR[newFindIndex] & 0x00FFFFFF), Paint.Align.CENTER);
        label(c, newFindSub, cx, cy + dp(54f), 9f, (a << 24) | (TEXT & 0x00FFFFFF),
                Paint.Align.CENTER);
    }

    /** The golden minute's own clock, big enough to row to. */
    private void drawRushClock(Canvas c, float w, float h) {
        float left = (float) Math.max(0, rushUntil - gameSeconds);
        float cx = w * 0.5f;
        float cy = h * 0.245f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC3A2A00);
        c.drawRoundRect(cx - dp(150f), cy - dp(20f), cx + dp(150f), cy + dp(20f), dp(12f), dp(12f), paint);
        float frac = left / (float) RUSH_SECONDS;
        paint.setColor(0x99FFC21A);
        c.drawRoundRect(cx - dp(148f), cy - dp(18f), cx - dp(148f) + dp(296f) * frac, cy + dp(18f),
                dp(10f), dp(10f), paint);
        bold(c, "GOLDEN MINUTE  " + (int) Math.ceil(left) + "s  ·  x2  ·  +" + rushScore, cx,
                cy + dp(6f), 15f, 0xFF20180A, Paint.Align.CENTER);
    }

    /** The collection book: four places down, five species across. */
    private void drawBookOverlay(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC000814);
        c.drawRect(0, 0, w, h, paint);
        panel.set(w * 0.5f - dp(430f), h * 0.5f - dp(280f), w * 0.5f + dp(430f), h * 0.5f + dp(280f));
        paint.setColor(0xF20E1A28);
        c.drawRoundRect(panel, dp(18f), dp(18f), paint);
        bold(c, "THE COLLECTION BOOK", panel.centerX(), panel.top + dp(44f), 24f, TEXT,
                Paint.Align.CENTER);
        label(c, Integer.bitCount(found) + " of " + SPECIES + " species  ·  "
                        + Integer.bitCount(sets) + " of 4 sets  ·  a finished set pays +1 on every "
                        + "lane prize in that place  ·  tap outside to close",
                panel.centerX(), panel.top + dp(66f), 10f, DIM, Paint.Align.CENTER);
        float cellW = (panel.width() - dp(200f)) / SLOTS;
        float rowH = dp(104f);
        float top = panel.top + dp(92f);
        for (int p = 0; p < 4; p++) {
            float ry = top + p * rowH;
            boolean done = (sets & (1 << p)) != 0;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(done ? 0x33FFC21A : 0x18FFFFFF);
            c.drawRoundRect(panel.left + dp(20f), ry, panel.right - dp(20f), ry + rowH - dp(10f),
                    dp(12f), dp(12f), paint);
            bold(c, PLACE_NAMES[p], panel.left + dp(36f), ry + dp(40f), 14f,
                    done ? GOLD : TEXT, Paint.Align.LEFT);
            label(c, done ? "SET COMPLETE" : (Integer.bitCount(found & setMask(p)) + " / " + SLOTS),
                    panel.left + dp(36f), ry + dp(60f), 9.5f, done ? GOLD : DIM, Paint.Align.LEFT);
            for (int s = 0; s < SLOTS; s++) {
                int idx = p * SLOTS + s;
                float cx = panel.left + dp(180f) + cellW * (s + 0.5f);
                float cy = ry + dp(38f);
                boolean got = (found & (1 << idx)) != 0;
                if (got) {
                    drawSpecies(c, idx, cx, cy, dp(19f));
                    bold(c, SPECIES_NAME[idx], cx, ry + dp(74f), 9.5f, SPECIES_COLOR[idx],
                            Paint.Align.CENTER);
                } else {
                    paint.setStyle(Paint.Style.FILL);
                    paint.setColor(0x33FFFFFF);
                    c.drawCircle(cx, cy, dp(19f), paint);
                    bold(c, "?", cx, cy + dp(9f), 20f, 0x66FFFFFF, Paint.Align.CENTER);
                    label(c, s == 4 ? "HOLD A BAND" : s == 3 ? "GOLDEN FISH" : NAMES[s] + " LANE",
                            cx, ry + dp(74f), 9f, FAINT, Paint.Align.CENTER);
                }
            }
        }
    }

    /** The boat yard: three tracks, three levels each, bought out of the bank. */
    private void drawShopOverlay(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC000814);
        c.drawRect(0, 0, w, h, paint);
        panel.set(w * 0.5f - dp(430f), h * 0.5f - dp(240f), w * 0.5f + dp(430f), h * 0.5f + dp(240f));
        paint.setColor(0xF20E1A28);
        c.drawRoundRect(panel, dp(18f), dp(18f), paint);
        bold(c, "THE BOAT YARD", panel.centerX(), panel.top + dp(46f), 24f, TEXT, Paint.Align.CENTER);
        bold(c, (int) bank + " POINTS BANKED", panel.centerX(), panel.top + dp(74f), 15f, ACCENT,
                Paint.Align.CENTER);
        label(c, "every run's points go into the bank  ·  tap a card to fit it  ·  tap outside to close",
                panel.centerX(), panel.top + dp(94f), 10f, DIM, Paint.Align.CENTER);
        float cardW = (panel.width() - dp(120f)) / 3f;
        float cardTop = panel.top + dp(116f);
        float cardBottom = panel.bottom - dp(40f);
        for (int i = 0; i < UPGRADES; i++) {
            float left = panel.left + dp(30f) + i * (cardW + dp(30f));
            cardRect[i].set(left, cardTop, left + cardW, cardBottom);
            boolean maxed = up[i] >= MAX_LEVEL;
            int cost = maxed ? 0 : UPGRADE_COST[up[i]];
            boolean can = !maxed && bank >= cost;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(maxed ? 0x33FFC21A : can ? 0x3335D0BA : 0x18FFFFFF);
            c.drawRoundRect(cardRect[i], dp(14f), dp(14f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(maxed ? GOLD : can ? ACCENT : 0x33FFFFFF);
            c.drawRoundRect(cardRect[i], dp(14f), dp(14f), paint);
            paint.setStyle(Paint.Style.FILL);
            float cx = cardRect[i].centerX();
            drawUpgradeIcon(c, i, cx, cardTop + dp(62f));
            bold(c, UP_NAME[i], cx, cardTop + dp(124f), 20f, maxed ? GOLD : TEXT, Paint.Align.CENTER);
            for (int k = 0; k < MAX_LEVEL; k++) {
                paint.setColor(k < up[i] ? (maxed ? GOLD : ACCENT) : 0x44FFFFFF);
                c.drawCircle(cx - dp(22f) + k * dp(22f), cardTop + dp(146f), dp(6f), paint);
            }
            drawWrapped(c, UP_BLURB[i], cx, cardTop + dp(180f), cardW - dp(30f));
            bold(c, maxed ? "FULLY FITTED" : can ? "FIT FOR " + cost : "NEEDS " + cost, cx,
                    cardBottom - dp(24f), 16f, maxed ? GOLD : can ? ACCENT : DIM, Paint.Align.CENTER);
        }
    }

    /** Wraps a blurb inside a card, measured with the label paint. */
    private void drawWrapped(Canvas c, String text, float cx, float y, float maxWidth) {
        dimPaint.setColor(DIM);
        dimPaint.setTextSize(dp(11f));
        dimPaint.setTextAlign(Paint.Align.CENTER);
        dimPaint.setFakeBoldText(false);
        int start = 0;
        float line = y;
        while (start < text.length()) {
            int n = dimPaint.breakText(text, start, text.length(), true, maxWidth, null);
            int end = start + Math.max(1, n);
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end);
                if (space > start) {
                    end = space;
                }
            }
            c.drawText(text, start, end, cx, line, dimPaint);
            start = end < text.length() && text.charAt(end) == ' ' ? end + 1 : end;
            line += dp(16f);
        }
    }

    /** A net, a prow or a creel, drawn small for the yard's cards. */
    private void drawUpgradeIcon(Canvas c, int track, float x, float y) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ACCENT);
        float r = dp(28f);
        if (track == 0) {
            arc.set(x - r, y - r, x + r, y + r);
            c.drawArc(arc, 0, 180, false, paint);
            for (int k = -2; k <= 2; k++) {
                c.drawLine(x + k * r * 0.4f, y, x + k * r * 0.25f, y + r * 0.9f, paint);
            }
            c.drawLine(x - r, y, x + r, y, paint);
            c.drawLine(x - r * 0.75f, y + r * 0.45f, x + r * 0.75f, y + r * 0.45f, paint);
        } else if (track == 1) {
            shape.rewind();
            shape.moveTo(x - r, y + r * 0.4f);
            shape.lineTo(x + r, y);
            shape.lineTo(x - r, y - r * 0.4f);
            c.drawPath(shape, paint);
            c.drawLine(x - r, y, x + r * 0.2f, y, paint);
        } else {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF7A4E2A);
            c.drawRoundRect(x - r * 0.8f, y - r * 0.4f, x + r * 0.8f, y + r * 0.8f, dp(6f), dp(6f), paint);
            paint.setColor(ACCENT);
            paint.setStyle(Paint.Style.STROKE);
            arc.set(x - r * 0.5f, y - r * 0.9f, x + r * 0.5f, y - dp(1f));
            c.drawArc(arc, 180, 180, false, paint);
            for (int k = 0; k < 3; k++) {
                c.drawLine(x - r * 0.6f + k * r * 0.6f, y - r * 0.4f, x - r * 0.6f + k * r * 0.6f,
                        y + r * 0.8f, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** The rare specimen in the water: slow, haloed, and trailing sparks. */
    private void drawRare(Canvas c, float x, float y, int index) {
        float bob = (float) Math.sin(sessionSeconds * 2.4) * dp(5f);
        float cy = y + bob;
        Fx.glow(c, x, cy, dp(64f), 0x889AE6FF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        for (int k = 0; k < 2; k++) {
            float t = (float) ((sessionSeconds * 0.7 + k * 0.5) % 1.0);
            paint.setColor(((int) (170 * (1 - t)) << 24) | 0x009AE6FF);
            c.drawCircle(x, cy, dp(28f) + t * dp(40f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        drawSpecies(c, index, x, cy, dp(22f));
        bold(c, "RARE  +" + RARE_POINTS, x, cy - dp(46f), 13f, 0xFF9AE6FF, Paint.Align.CENTER);
        // Sparks rising off it, so it reads as something worth chasing.
        paint.setColor(0xFFFFFFFF);
        for (int k = 0; k < 4; k++) {
            float tw = (float) Math.abs(Math.sin(sessionSeconds * 5 + k * 1.7));
            float sx = x + (float) Math.cos(k * 1.7 + sessionSeconds * 1.4) * dp(30f);
            float sy = cy + (float) Math.sin(k * 1.7 + sessionSeconds * 1.4) * dp(18f);
            c.drawCircle(sx, sy, dp(2f) * tw, paint);
        }
    }

    /** The hold meter: how close the current band is to giving up its rare. */
    private void drawHoldHud(Canvas c, float y) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRoundRect(dp(6f), y - dp(16f), dp(230f), y + dp(16f), dp(10f), dp(10f), paint);
        if (rareLive) {
            bold(c, rareText, dp(16f), y - dp(1f), 13f, 0xFF9AE6FF, Paint.Align.LEFT);
            label(c, "stay in the band to take it", dp(16f), y + dp(13f), 9f, DIM, Paint.Align.LEFT);
            return;
        }
        if (holdLane < 0) {
            label(c, "HOLD ONE BAND FOR A RARE", dp(16f), y + dp(3f), 10f, DIM, Paint.Align.LEFT);
            return;
        }
        float need = (float) HOLD_NEEDED[holdLane];
        float frac = Math.max(0f, Math.min(1f, (float) holdSeconds / need));
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(dp(16f), y + dp(3f), dp(220f), y + dp(11f), dp(4f), dp(4f), paint);
        paint.setColor(frac >= 1f ? GOLD : 0xFF9AE6FF);
        c.drawRoundRect(dp(16f), y + dp(3f), dp(16f) + dp(204f) * frac, y + dp(11f), dp(4f), dp(4f), paint);
        label(c, holdText, dp(16f), y - dp(3f), 10f, 0xFF9AE6FF, Paint.Align.LEFT);
    }

    /** The multiplier badge: bigger at each step, a pop when it rises, a ring that drains. */
    private void drawMultiplier(Canvas c, float x, float y) {
        float popT = (float) (sessionSeconds - multPopAt);
        float pop = popT >= 0 && popT < 0.6f ? 1f + 0.5f * (1f - popT / 0.6f) : 1f;
        float r = dp(20f + mult * 6f) * pop;
        int color = mult >= 4 ? 0xFFFF7AE0 : mult >= 2 ? 0xFFF5C518 : DIM;
        if (mult >= 2) {
            Fx.glow(c, x, y, dp(40f + mult * 12f), (color & 0x00FFFFFF) | 0x66000000);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC0A1420);
        c.drawCircle(x, y, r, paint);
        // Remaining chain time, as a ring.
        if (combo > 0) {
            float frac = (float) Math.max(0, 1 - (gameSeconds - lastCatchAt) / chainSeconds());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5f));
            paint.setColor(0x33FFFFFF);
            arc.set(x - r, y - r, x + r, y + r);
            c.drawArc(arc, 0, 360, false, paint);
            paint.setColor(frac < 0.3f ? BAD : color);
            c.drawArc(arc, -90, 360 * frac, false, paint);
            // Progress to the next step, as pips beneath.
            paint.setStyle(Paint.Style.FILL);
            if (mult < MAX_MULT) {
                int have = combo % 5;
                for (int k = 0; k < 5; k++) {
                    paint.setColor(k < have ? color : 0x44FFFFFF);
                    c.drawCircle(x - dp(20f) + k * dp(10f), y + r + dp(10f), dp(3f), paint);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
        bold(c, MULT_TEXT[mult], x, y + r * 0.3f, 12f + mult * 5f * pop, mult >= 2 ? color : TEXT,
                Paint.Align.CENTER);
    }

    /** Magnet charge under the speed pill: three pips, or the seconds left once it is on. */
    private void drawChargeHud(Canvas c, float y, boolean magnet) {
        drawMagnet(c, dp(26f), y, dp(8f), 0f);
        if (magnet) {
            bold(c, "MAGNET " + (int) Math.ceil(magnetUntil - sessionSeconds) + "s", dp(44f), y + dp(5f),
                    13f, 0xFFFF5A5A, Paint.Align.LEFT);
            return;
        }
        if (magLive) {
            label(c, "CATCH THE MAGNET!", dp(44f), y + dp(4f), 10f, 0xFFFF5A5A, Paint.Align.LEFT);
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < CHARGE_NEEDED; k++) {
            float fill = Math.max(0f, Math.min(1f, charge - k));
            float x0 = dp(46f) + k * dp(22f);
            paint.setColor(0x44FFFFFF);
            c.drawRoundRect(x0, y - dp(5f), x0 + dp(18f), y + dp(5f), dp(3f), dp(3f), paint);
            if (fill > 0f) {
                paint.setColor(0xFFFF5A5A);
                c.drawRoundRect(x0, y - dp(5f), x0 + dp(18f) * fill, y + dp(5f), dp(3f), dp(3f), paint);
            }
        }
        label(c, "PUSH " + Math.round(strongWatts) + "W", dp(116f), y + dp(4f), 9f, DIM, Paint.Align.LEFT);
    }

    /** A horseshoe magnet, red with silver tips, opening upward. */
    private void drawMagnet(Canvas c, float x, float y, float r, float tiltDeg) {
        c.save();
        c.rotate(tiltDeg, x, y);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(r * 0.7f);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(0xFFE53935);
        arc.set(x - r, y - r, x + r, y + r);
        c.drawArc(arc, 0, 180, false, paint);
        c.drawLine(x - r, y, x - r, y - r * 0.5f, paint);
        c.drawLine(x + r, y, x + r, y - r * 0.5f, paint);
        paint.setColor(0xFFE0E6EE);
        c.drawLine(x - r, y - r * 0.5f, x - r, y - r * 1.1f, paint);
        c.drawLine(x + r, y - r * 0.5f, x + r, y - r * 1.1f, paint);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** Pulsing field lines around the boat while the magnet is on. */
    private void drawField(Canvas c, float x, float y, float w) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        for (int k = 0; k < 3; k++) {
            float t = (float) ((sessionSeconds * 0.8 + k / 3.0) % 1.0);
            float r = dp(60f) + t * w * 0.25f;
            paint.setColor(((int) (140 * (1 - t)) << 24) | 0x00FF7A7A);
            arc.set(x - r * 0.5f, y - r * 0.6f, x + r, y + r * 0.6f);
            c.drawArc(arc, -60, 120, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** A floating log, bobbing and rolling a little; splintered once hit. */
    private void drawLog(Canvas c, float x, float y, boolean hit) {
        float bob = (float) Math.sin(sessionSeconds * 2.2 + x * 0.01) * dp(3f);
        float half = dp(46f);
        float th = dp(10f);
        c.save();
        c.rotate((float) Math.sin(sessionSeconds * 1.3) * 4f + (hit ? 12f : 0f), x, y + bob);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        c.drawOval(x - half - dp(6f), y + bob + dp(4f), x + half + dp(6f), y + bob + th + dp(8f), paint);
        paint.setColor(LOG_COLOR[place]);
        c.drawRoundRect(x - half, y + bob - th, x + half, y + bob + th, th, th, paint);
        paint.setColor(0x33FFFFFF);
        c.drawRect(x - half + th, y + bob - th * 0.7f, x + half - th, y + bob - th * 0.35f, paint);
        // Branch stub and knot.
        paint.setColor(LOG_COLOR[place]);
        paint.setStrokeWidth(dp(4f));
        c.drawLine(x + dp(10f), y + bob - th, x + dp(22f), y + bob - th - dp(14f), paint);
        paint.setColor(0x55000000);
        c.drawCircle(x - dp(14f), y + bob, dp(3f), paint);
        if (place == JUNGLE || place == LAKE) {
            paint.setColor(0xFF4E8F3A); // moss
            c.drawOval(x - dp(30f), y + bob - th - dp(2f), x - dp(6f), y + bob - th + dp(4f), paint);
        }
        // End rings.
        paint.setColor(0xFFD8B27A);
        c.drawOval(x + half - th * 1.1f, y + bob - th, x + half + th * 0.3f, y + bob + th, paint);
        paint.setColor(0xFFA57E48);
        c.drawOval(x + half - th * 0.8f, y + bob - th * 0.5f, x + half, y + bob + th * 0.5f, paint);
        c.restore();
        // Water breaking against its upstream side.
        paint.setColor(0x88DDF2FF);
        float foam = (float) Math.abs(Math.sin(sessionSeconds * 5)) * dp(3f);
        c.drawOval(x - half - dp(10f) - foam, y + bob + th * 0.2f, x - half + dp(6f), y + bob + th + dp(3f), paint);
    }

    /** The rare golden fish: leaps along its lane in arcs, glinting. */
    private void drawGoldFish(Canvas c, float x, float laneY, int laneIndex) {
        double hop = (sessionSeconds * 1.6 + laneIndex) % 1.0;
        float lift = (float) Math.sin(hop * Math.PI) * dp(34f);
        float y = laneY - lift;
        float tilt = (float) Math.cos(hop * Math.PI) * -25f; // nose up rising, down falling
        Fx.glow(c, x, y, dp(52f), 0x88FFD24A);
        c.save();
        c.rotate(tilt, x, y);
        // Fish faces left, toward the boat.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(GOLD);
        c.drawOval(x - dp(20f), y - dp(9f), x + dp(16f), y + dp(9f), paint);
        float flick = (float) Math.sin(sessionSeconds * 14) * dp(4f);
        shape.rewind();
        shape.moveTo(x + dp(12f), y);
        shape.lineTo(x + dp(28f), y - dp(11f) + flick);
        shape.lineTo(x + dp(28f), y + dp(11f) + flick);
        shape.close();
        c.drawPath(shape, paint);
        paint.setColor(0xFFFFE9A0);
        c.drawOval(x - dp(14f), y - dp(6f), x + dp(8f), y - dp(1f), paint);
        paint.setColor(0xFFE09A00);
        shape.rewind();
        shape.moveTo(x - dp(4f), y - dp(8f));
        shape.lineTo(x + dp(6f), y - dp(17f));
        shape.lineTo(x + dp(10f), y - dp(7f));
        shape.close();
        c.drawPath(shape, paint);
        paint.setColor(0xFF1A1A1A);
        c.drawCircle(x - dp(12f), y - dp(2f), dp(2.2f), paint);
        c.restore();
        // Sparkles and the splash where it re-enters.
        paint.setColor(0xFFFFFFFF);
        for (int k = 0; k < 3; k++) {
            float tw = (float) Math.abs(Math.sin(sessionSeconds * 6 + k * 2.1));
            float sx = x + (float) Math.cos(k * 2.1 + sessionSeconds) * dp(26f);
            float sy = y + (float) Math.sin(k * 2.1 + sessionSeconds) * dp(16f);
            c.drawRect(sx - dp(1f), sy - dp(5f) * tw, sx + dp(1f), sy + dp(5f) * tw, paint);
            c.drawRect(sx - dp(5f) * tw, sy - dp(1f), sx + dp(5f) * tw, sy + dp(1f), paint);
        }
        if (lift < dp(6f)) {
            paint.setColor(0x99DDF2FF);
            c.drawOval(x - dp(22f), laneY + dp(4f), x + dp(22f), laneY + dp(10f), paint);
        }
    }

    /** This place's prize for a lane: the species the book knows it by, spinning and glowing. */
    private void drawPrize(Canvas c, int lane, float x, float y) {
        int index = place * SLOTS + lane;
        float r = dp(14f + lane * 3f);
        Fx.glow(c, x, y, r * 2.4f, (SPECIES_COLOR[index] & 0x00FFFFFF) | 0x55000000);
        drawSpecies(c, index, x, y, r);
    }

    /**
     * One species, drawn from its shape and colour: used in the water, on the find card and in the
     * book, so a page in the book is the thing you actually caught.
     */
    private void drawSpecies(Canvas c, int index, float x, float y, float r) {
        int colour = SPECIES_COLOR[index];
        int shapeId = SPECIES_SHAPE[index];
        float spin = (float) Math.abs(Math.cos(sessionSeconds * 3 + index));
        paint.setStyle(Paint.Style.FILL);
        switch (shapeId) {
            case SH_COIN: {
                float rx = r * (0.25f + 0.75f * spin);
                paint.setColor(0xFF8A6A18 | (colour & 0x00202020));
                c.drawOval(x - rx, y - r, x + rx, y + r, paint);
                paint.setColor(colour);
                c.drawOval(x - rx * 0.8f, y - r * 0.8f, x + rx * 0.8f, y + r * 0.8f, paint);
                break;
            }
            case SH_GEM: {
                float rx = r * (0.5f + 0.5f * spin);
                shape.rewind();
                shape.moveTo(x, y - r);
                shape.lineTo(x + rx, y - r * 0.25f);
                shape.lineTo(x, y + r);
                shape.lineTo(x - rx, y - r * 0.25f);
                shape.close();
                paint.setColor(colour);
                c.drawPath(shape, paint);
                paint.setColor(0x99FFFFFF);
                c.drawRect(x - rx * 0.5f, y - r * 0.45f, x, y - r * 0.2f, paint);
                break;
            }
            case SH_STAR: {
                double a0 = sessionSeconds * 2;
                shape.rewind();
                for (int k = 0; k < 10; k++) {
                    double a = a0 + k * Math.PI / 5;
                    float rr = (k & 1) == 0 ? r : r * 0.45f;
                    float px = x + (float) Math.cos(a) * rr;
                    float py = y + (float) Math.sin(a) * rr;
                    if (k == 0) {
                        shape.moveTo(px, py);
                    } else {
                        shape.lineTo(px, py);
                    }
                }
                shape.close();
                paint.setColor(colour);
                c.drawPath(shape, paint);
                paint.setColor(0xFFFFFFFF);
                c.drawCircle(x, y, r * 0.2f, paint);
                break;
            }
            case SH_SHELL: {
                paint.setColor(colour);
                arc.set(x - r, y - r * 0.9f, x + r, y + r * 1.1f);
                c.drawArc(arc, 180, 180, true, paint);
                paint.setColor(0x66000000);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, r * 0.08f));
                for (int k = -2; k <= 2; k++) {
                    c.drawLine(x, y + r * 0.1f, x + k * r * 0.38f, y - r * 0.8f, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case SH_FLOWER: {
                paint.setColor(colour);
                for (int k = 0; k < 5; k++) {
                    double a = sessionSeconds * 1.2 + k * Math.PI * 0.4;
                    float px = x + (float) Math.cos(a) * r * 0.55f;
                    float py = y + (float) Math.sin(a) * r * 0.55f;
                    c.drawCircle(px, py, r * 0.45f, paint);
                }
                paint.setColor(0xFFFFE066);
                c.drawCircle(x, y, r * 0.32f, paint);
                break;
            }
            case SH_FISH: {
                paint.setColor(colour);
                c.drawOval(x - r, y - r * 0.5f, x + r * 0.6f, y + r * 0.5f, paint);
                float flick = (float) Math.sin(sessionSeconds * 10 + index) * r * 0.2f;
                shape.rewind();
                shape.moveTo(x + r * 0.4f, y);
                shape.lineTo(x + r * 1.1f, y - r * 0.55f + flick);
                shape.lineTo(x + r * 1.1f, y + r * 0.55f + flick);
                shape.close();
                c.drawPath(shape, paint);
                paint.setColor(0xFF1A1A1A);
                c.drawCircle(x - r * 0.55f, y - r * 0.1f, Math.max(1f, r * 0.12f), paint);
                break;
            }
            default: {
                // The rare: a small chest, with one gem on the lid per place so the four differ.
                int p = index / SLOTS;
                paint.setColor(0xFF3A2A18);
                c.drawRoundRect(x - r, y - r * 0.15f, x + r, y + r * 0.8f, r * 0.2f, r * 0.2f, paint);
                paint.setColor(colour);
                arc.set(x - r, y - r * 0.85f, x + r, y + r * 0.35f);
                c.drawArc(arc, 180, 180, true, paint);
                paint.setColor(0xFFF5E0A0);
                c.drawRect(x - r, y - r * 0.2f, x + r, y - r * 0.05f, paint);
                c.drawRect(x - r * 0.12f, y - r * 0.2f, x + r * 0.12f, y + r * 0.45f, paint);
                paint.setColor(0xFF9AE6FF);
                for (int k = 0; k <= p; k++) {
                    c.drawCircle(x - r * 0.6f + k * r * 0.4f, y - r * 0.5f, r * 0.13f, paint);
                }
                break;
            }
        }
    }

    private void drawSky(Canvas c, float w, float bottom) {
        if (skyHeight != bottom) {
            skyHeight = bottom;
            for (int i = 0; i < skyShaders.length; i++) {
                skyShaders[i] = null;
            }
        }
        if (skyShaders[place] == null) {
            skyShaders[place] = new android.graphics.LinearGradient(0, 0, 0, bottom, SKY_TOP[place],
                    SKY_BOTTOM[place], android.graphics.Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShaders[place]);
        c.drawRect(0, 0, w, bottom, paint);
        paint.setShader(null);
        float sunX = w * 0.8f;
        float sunY = bottom * (place == JUNGLE ? 0.25f : 0.35f);
        Fx.glow(c, sunX, sunY, dp(90f), place == JUNGLE ? 0x66FFF4C0 : 0x88FFF4C0);
        paint.setColor(0xFFFFF4C0);
        c.drawCircle(sunX, sunY, dp(26f), paint);
        paint.setColor(place == JUNGLE ? 0xAAF2F6E6 : 0xDDFFFFFF);
        int clouds = place == SEA ? 5 : 4;
        for (int i = 0; i < clouds; i++) {
            float span = w + dp(300f);
            float cx = (float) (((i * 530 + 120) - sessionMeters * 1.5) % span);
            if (cx < -dp(150f)) {
                cx += span;
            }
            float cy = bottom * (0.28f + (i % 2) * 0.3f);
            c.drawOval(cx - dp(60f), cy - dp(12f), cx + dp(60f), cy + dp(12f), paint);
            c.drawOval(cx - dp(28f), cy - dp(24f), cx + dp(30f), cy + dp(4f), paint);
        }
        if (place == SEA) {
            drawGulls(c, w, bottom);
        } else if (place == JUNGLE) {
            drawParrots(c, w, bottom);
        }
    }

    /** The far side: a crowded river bank, mountains over a lake, the open horizon, or jungle. */
    private void drawBank(Canvas c, float w, float top, float bottom, float ppm) {
        switch (place) {
            case LAKE:
                drawLakeBank(c, w, top, bottom, ppm);
                break;
            case SEA:
                drawSeaHorizon(c, w, top, bottom, ppm);
                break;
            case JUNGLE:
                drawJungleBank(c, w, top, bottom, ppm);
                break;
            default:
                scenery.drawBank(c, w, top, bottom, sessionMeters, ppm, sessionSeconds, Math.min(1f, combo / 10f));
                break;
        }
    }

    private void drawLakeBank(Canvas c, float w, float top, float bottom, float ppm) {
        paint.setStyle(Paint.Style.FILL);
        // Mountains, far and slow, rising into the sky.
        float gap = dp(240f);
        double scroll = sessionMeters * ppm * 0.06;
        float off = (float) (scroll % gap);
        for (float x = -off - gap; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5);
            float tall = dp(70f) + (Math.abs(k * 5) % 4) * dp(18f);
            float half = tall * 1.3f;
            float base = bottom - dp(10f);
            shape.rewind();
            shape.moveTo(x - half, base);
            shape.lineTo(x, base - tall);
            shape.lineTo(x + half, base);
            shape.close();
            paint.setColor((k & 1) == 0 ? 0xFF6F87A6 : 0xFF7F95B0);
            c.drawPath(shape, paint);
            shape.rewind();
            shape.moveTo(x - half * 0.28f, base - tall * 0.72f);
            shape.lineTo(x, base - tall);
            shape.lineTo(x + half * 0.28f, base - tall * 0.72f);
            shape.lineTo(x + half * 0.1f, base - tall * 0.66f);
            shape.lineTo(x - half * 0.08f, base - tall * 0.75f);
            shape.close();
            paint.setColor(0xFFF4F8FC);
            c.drawPath(shape, paint);
        }
        // Pine forest along the shore.
        float pgap = dp(20f);
        double pscroll = sessionMeters * ppm * 0.35;
        float poff = (float) (pscroll % pgap);
        for (float x = -poff - pgap; x < w + pgap; x += pgap) {
            int k = (int) Math.floor((x + pscroll) / pgap + 0.5);
            float tall = dp(22f) + (Math.abs(k * 7) % 3) * dp(7f);
            float base = bottom - dp(6f);
            shape.rewind();
            shape.moveTo(x - dp(9f), base);
            shape.lineTo(x, base - tall);
            shape.lineTo(x + dp(9f), base);
            shape.close();
            paint.setColor((k & 1) == 0 ? 0xFF1F4A33 : 0xFF2A5C3E);
            c.drawPath(shape, paint);
            if (Math.abs(k) % 17 == 5) {
                // A cabin with a lit window.
                paint.setColor(0xFF7A4E2A);
                c.drawRect(x + dp(4f), base - dp(16f), x + dp(30f), base, paint);
                shape.rewind();
                shape.moveTo(x + dp(1f), base - dp(16f));
                shape.lineTo(x + dp(17f), base - dp(28f));
                shape.lineTo(x + dp(33f), base - dp(16f));
                shape.close();
                paint.setColor(0xFF8E3B2E);
                c.drawPath(shape, paint);
                paint.setColor(0xFFFFD86B);
                c.drawRect(x + dp(10f), base - dp(11f), x + dp(16f), base - dp(5f), paint);
            }
        }
        paint.setColor(0xFF3F7A45);
        c.drawRect(0, bottom - dp(6f), w, bottom, paint);
    }

    private void drawSeaHorizon(Canvas c, float w, float top, float bottom, float ppm) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1E5E93);
        c.drawRect(0, top, w, bottom, paint);
        paint.setColor(0xFF2B78B0);
        c.drawRect(0, top + (bottom - top) * 0.5f, w, bottom, paint);
        paint.setColor(0x66FFFFFF);
        c.drawRect(0, top, w, top + dp(1.5f), paint);
        // Glints on the open water.
        for (int k = 0; k < 12; k++) {
            float gx = (float) ((k * 173 + sessionSeconds * 20) % w);
            float gy = top + dp(8f) + (k % 4) * dp(9f);
            float tw = (float) Math.abs(Math.sin(sessionSeconds * 3 + k));
            paint.setColor(((int) (120 * tw) << 24) | 0x00FFFFFF);
            c.drawRect(gx, gy, gx + dp(14f), gy + dp(1.5f), paint);
        }
        // A lighthouse on its rock, far off.
        float span = w + dp(600f);
        float lx = (float) (w + dp(200f) - ((sessionMeters * ppm * 0.12) % span));
        float base = top + dp(26f);
        paint.setColor(0xFF4F5B52);
        c.drawOval(lx - dp(60f), base - dp(12f), lx + dp(60f), base + dp(10f), paint);
        paint.setColor(0xFFF4F4F0);
        c.drawRect(lx - dp(7f), base - dp(58f), lx + dp(7f), base - dp(6f), paint);
        paint.setColor(0xFFD84A3A);
        c.drawRect(lx - dp(7f), base - dp(46f), lx + dp(7f), base - dp(38f), paint);
        c.drawRect(lx - dp(7f), base - dp(26f), lx + dp(7f), base - dp(18f), paint);
        paint.setColor(0xFF2E3A40);
        c.drawRect(lx - dp(9f), base - dp(66f), lx + dp(9f), base - dp(58f), paint);
        float beam = (float) (0.5 + 0.5 * Math.sin(sessionSeconds * 2.5));
        Fx.glow(c, lx, base - dp(62f), dp(34f), ((int) (60 + 140 * beam) << 24) | 0x00FFF2A0);
        // Sailboats on the horizon.
        float sgap = dp(460f);
        double sscroll = sessionMeters * ppm * 0.22;
        float soff = (float) (sscroll % sgap);
        for (float x = -soff; x < w + sgap; x += sgap) {
            int k = (int) Math.floor((x + sscroll) / sgap + 0.5);
            float sy = top + dp(30f) + (Math.abs(k) % 2) * dp(8f)
                    + (float) Math.sin(sessionSeconds * 1.5 + k) * dp(1.5f);
            float sx = x + dp(130f);
            paint.setColor(0xFF3A2A20);
            c.drawRect(sx - dp(14f), sy - dp(4f), sx + dp(14f), sy, paint);
            shape.rewind();
            shape.moveTo(sx - dp(1f), sy - dp(5f));
            shape.lineTo(sx - dp(1f), sy - dp(32f));
            shape.lineTo(sx - dp(16f), sy - dp(5f));
            shape.close();
            paint.setColor((k & 1) == 0 ? 0xFFFFFFFF : 0xFFFFE0A0);
            c.drawPath(shape, paint);
            shape.rewind();
            shape.moveTo(sx + dp(1f), sy - dp(5f));
            shape.lineTo(sx + dp(1f), sy - dp(26f));
            shape.lineTo(sx + dp(12f), sy - dp(5f));
            shape.close();
            c.drawPath(shape, paint);
        }
    }

    private void drawJungleBank(Canvas c, float w, float top, float bottom, float ppm) {
        paint.setStyle(Paint.Style.FILL);
        // Dense canopy spilling up into the sky.
        float gap = dp(46f);
        double scroll = sessionMeters * ppm * 0.3;
        float off = (float) (scroll % gap);
        for (float x = -off - gap; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5);
            float r = dp(26f) + (Math.abs(k * 7) % 3) * dp(9f);
            paint.setColor((k % 3) == 0 ? 0xFF1B4726 : (k & 1) == 0 ? 0xFF245C2E : 0xFF2E6E35);
            c.drawCircle(x, bottom - dp(20f) - r * 0.5f, r, paint);
            c.drawCircle(x + r * 0.8f, bottom - dp(8f) - r * 0.2f, r * 0.75f, paint);
        }
        // Palms and vines in front of the canopy.
        float pgap = dp(170f);
        double pscroll = sessionMeters * ppm * 0.45;
        float poff = (float) (pscroll % pgap);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (float x = -poff - pgap; x < w + pgap; x += pgap) {
            int k = (int) Math.floor((x + pscroll) / pgap + 0.5);
            float base = bottom - dp(4f);
            float tall = dp(70f) + (Math.abs(k * 3) % 3) * dp(14f);
            float lean = ((k & 1) == 0 ? 1 : -1) * dp(12f);
            float sway = (float) Math.sin(sessionSeconds * 1.2 + k) * dp(3f);
            float tx = x + lean + sway;
            float ty = base - tall;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(6f));
            paint.setColor(0xFF6B4E2E);
            c.drawLine(x, base, tx, ty, paint);
            paint.setStrokeWidth(dp(5f));
            paint.setColor(0xFF3D8A3A);
            for (int f = 0; f < 5; f++) {
                double a = Math.PI * (0.95 + f * 0.27) + Math.sin(sessionSeconds * 1.6 + k + f) * 0.06;
                float fx2 = tx + (float) Math.cos(a) * dp(34f);
                float fy2 = ty + (float) Math.sin(a) * dp(-20f) + dp(14f);
                c.drawLine(tx, ty, fx2, fy2, paint);
            }
            // A vine hanging to the water.
            paint.setStrokeWidth(dp(2f));
            paint.setColor(0xFF2F6B2A);
            float vx = x + pgap * 0.5f;
            float swing = (float) Math.sin(sessionSeconds * 0.9 + k) * dp(6f);
            c.drawLine(vx, top - dp(20f), vx + swing, bottom - dp(2f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF3D8A3A);
            c.drawOval(vx + swing * 0.5f - dp(4f), top + dp(6f), vx + swing * 0.5f + dp(4f), top + dp(14f), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF4A3B22);
        c.drawRect(0, bottom - dp(5f), w, bottom, paint);
    }

    /** Gulls wheeling over the open sea. */
    private void drawGulls(Canvas c, float w, float bottom) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0xFFFFFFFF);
        for (int k = 0; k < 4; k++) {
            float gx = (float) ((w + dp(100f)) - ((sessionSeconds * (40 + k * 12) + k * 400) % (w + dp(200f))));
            float gy = bottom * (0.2f + k * 0.14f) + (float) Math.sin(sessionSeconds + k) * dp(10f);
            float flap = (float) Math.sin(sessionSeconds * 6 + k) * dp(6f);
            c.drawLine(gx - dp(12f), gy - flap, gx, gy, paint);
            c.drawLine(gx, gy, gx + dp(12f), gy - flap, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Parrots crossing the jungle sky. */
    private void drawParrots(Canvas c, float w, float bottom) {
        paint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 2; k++) {
            float px = (float) (((sessionSeconds * (70 + k * 25) + k * 700) % (w + dp(200f))) - dp(100f));
            float py = bottom * (0.3f + k * 0.25f) + (float) Math.sin(sessionSeconds * 2 + k) * dp(8f);
            float flap = (float) Math.sin(sessionSeconds * 10 + k) * dp(9f);
            paint.setColor(k == 0 ? 0xFFE53935 : 0xFF1E88E5);
            c.drawOval(px - dp(10f), py - dp(5f), px + dp(10f), py + dp(5f), paint);
            c.drawCircle(px + dp(10f), py - dp(3f), dp(5f), paint);
            paint.setColor(0xFFFFC21A);
            shape.rewind();
            shape.moveTo(px - dp(4f), py);
            shape.lineTo(px + dp(2f), py - dp(4f) - flap);
            shape.lineTo(px + dp(6f), py);
            shape.close();
            c.drawPath(shape, paint);
            paint.setColor(k == 0 ? 0xFF1E88E5 : 0xFFE53935);
            c.drawRect(px - dp(20f), py - dp(1.5f), px - dp(8f), py + dp(1.5f), paint);
            paint.setColor(0xFF2A2A2A);
            c.drawCircle(px + dp(15f), py - dp(2f), dp(1.8f), paint);
        }
    }

    /** The near bank, scrolling faster than the far one: reeds, a pebble shore, a beach, ferns. */
    private void drawNearShore(Canvas c, float w, float h, float top, float ppm) {
        paint.setStyle(Paint.Style.FILL);
        int ground = place == SEA ? 0xFFE2C58A : place == JUNGLE ? 0xFF24461F : 0xFF2F5E33;
        int lip = place == SEA ? 0xCCFFFFFF : place == JUNGLE ? 0xFF33602A : 0xFF3F7A45;
        paint.setColor(ground);
        c.drawRect(0, top, w, h, paint);
        paint.setColor(lip);
        float foam = place == SEA ? (float) Math.abs(Math.sin(sessionSeconds * 1.5)) * dp(4f) : 0f;
        c.drawRect(0, top, w, top + dp(6f) + foam, paint);
        float gap = dp(22f);
        double scroll = sessionMeters * ppm * 1.2;
        float off = (float) (scroll % gap);
        paint.setStrokeWidth(dp(3f));
        paint.setStrokeCap(Paint.Cap.BUTT);
        for (float x = -off - gap; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5);
            if (place == SEA) {
                if (Math.abs(k) % 7 == 3) {
                    drawStarfish(c, x, top + dp(22f) + (Math.abs(k) % 3) * dp(6f), k);
                } else if (Math.abs(k) % 5 == 1) {
                    paint.setColor(0xFFF6E7D0);
                    c.drawOval(x - dp(5f), top + dp(16f), x + dp(5f), top + dp(23f), paint);
                    paint.setColor(0xFFD9B99A);
                    c.drawRect(x - dp(0.8f), top + dp(16f), x + dp(0.8f), top + dp(23f), paint);
                }
                continue;
            }
            float tall = dp(16f) + (Math.abs(k * 7) % 4) * dp(6f);
            float sway = (float) Math.sin(sessionSeconds * 2 + k) * dp(3f);
            if (place == JUNGLE) {
                // Ferns: a fan of fronds.
                if ((k & 1) == 0) {
                    paint.setColor(0xFF3F9A42);
                    for (int f = -2; f <= 2; f++) {
                        c.drawLine(x, top + dp(12f), x + f * dp(7f) + sway, top + dp(12f) - tall * (1f - Math.abs(f) * 0.15f), paint);
                    }
                }
                continue;
            }
            paint.setColor((k & 1) == 0 ? 0xFF4E8F4F : 0xFF6BAA5C);
            c.drawLine(x, top + dp(10f), x + sway, top + dp(10f) - tall, paint);
            if (place == LAKE && Math.abs(k) % 3 == 1) {
                paint.setColor(0xFF8A9199);
                c.drawOval(x - dp(6f), top + dp(14f), x + dp(6f), top + dp(21f), paint);
            } else if (Math.abs(k) % 5 == 2) {
                paint.setColor(0xFF7A4E2A);
                c.drawRoundRect(x + sway - dp(2.5f), top + dp(8f) - tall - dp(10f), x + sway + dp(2.5f),
                        top + dp(8f) - tall + dp(2f), dp(2f), dp(2f), paint);
            }
        }
    }

    private void drawStarfish(Canvas c, float x, float y, int k) {
        shape.rewind();
        double a0 = k * 0.7;
        for (int i = 0; i < 10; i++) {
            double a = a0 + i * Math.PI / 5;
            float rr = (i & 1) == 0 ? dp(8f) : dp(3.5f);
            float px = x + (float) Math.cos(a) * rr;
            float py = y + (float) Math.sin(a) * rr;
            if (i == 0) {
                shape.moveTo(px, py);
            } else {
                shape.lineTo(px, py);
            }
        }
        shape.close();
        paint.setColor(0xFFF07A4A);
        c.drawPath(shape, paint);
    }

    /** Now and then something jumps across a lane, and something drifts by on the surface. */
    private void drawFish(Canvas c, float w, float top, float bottom) {
        if (sessionSeconds >= fishAt) {
            fishAt = sessionSeconds + 5 + Math.random() * 6;
            fishX = w * (0.4f + (float) Math.random() * 0.5f);
            fishY = top + (bottom - top) * (0.2f + (float) Math.random() * 0.6f);
        }
        double jump = 1 - (fishAt - sessionSeconds);
        paint.setStyle(Paint.Style.FILL);
        if (jump > 0 && jump < 1) {
            // At sea it is a dolphin: bigger, grey, a higher arc.
            boolean dolphin = place == SEA;
            float s = dolphin ? 2.2f : 1f;
            float px = fishX + (float) jump * dp(90f) * s;
            float py = fishY - (float) Math.sin(jump * Math.PI) * dp(50f) * (dolphin ? 1.5f : 1f);
            c.save();
            c.rotate((float) Math.cos(jump * Math.PI) * -30f, px, py);
            paint.setColor(dolphin ? 0xFF7C8FA3 : place == JUNGLE ? 0xFF9FB86A : 0xFFE8A04A);
            c.drawOval(px - dp(12f) * s, py - dp(5f) * s, px + dp(12f) * s, py + dp(5f) * s, paint);
            shape.rewind();
            shape.moveTo(px - dp(10f) * s, py);
            shape.lineTo(px - dp(20f) * s, py - dp(7f) * s);
            shape.lineTo(px - dp(20f) * s, py + dp(7f) * s);
            shape.close();
            c.drawPath(shape, paint);
            if (dolphin) {
                shape.rewind();
                shape.moveTo(px - dp(2f) * s, py - dp(4f) * s);
                shape.lineTo(px - dp(6f) * s, py - dp(11f) * s);
                shape.lineTo(px + dp(4f) * s, py - dp(4f) * s);
                shape.close();
                c.drawPath(shape, paint);
                paint.setColor(0xFFDDE4EA);
                c.drawOval(px - dp(8f) * s, py, px + dp(10f) * s, py + dp(4f) * s, paint);
            }
            c.restore();
            if (jump < 0.1 || jump > 0.9) {
                fx.burst(px, fishY, dolphin ? 6 : 3, dp(60f), 0.4f, dp(2f), 0xCCDDF2FF, true);
            }
        }
        // Something drifting with the current, so the water always has something on it.
        float span = w + dp(200f);
        float dx = (float) (w - ((sessionMeters * w / 60f * 0.9 + sessionSeconds * dp(10f)) % span));
        float dy = top + (bottom - top) * 0.62f + (float) Math.sin(sessionSeconds * 2) * dp(2f);
        if (place == JUNGLE) {
            // A crocodile, eyes and back just above the surface.
            paint.setColor(0xFF3C5A2A);
            c.drawOval(dx - dp(40f), dy - dp(4f), dx + dp(34f), dy + dp(5f), paint);
            for (int k = 0; k < 5; k++) {
                c.drawRect(dx - dp(22f) + k * dp(10f), dy - dp(7f), dx - dp(17f) + k * dp(10f), dy - dp(3f), paint);
            }
            c.drawOval(dx - dp(52f), dy - dp(9f), dx - dp(40f), dy - dp(2f), paint);
            paint.setColor(0xFFF5E050);
            c.drawCircle(dx - dp(46f), dy - dp(6f), dp(2.2f), paint);
            paint.setColor(0x66DDF2FF);
            c.drawRect(dx - dp(56f), dy + dp(4f), dx + dp(40f), dy + dp(6f), paint);
        } else if (place == SEA) {
            // A gull riding the swell.
            paint.setColor(0xFFF4F4F4);
            c.drawOval(dx - dp(14f), dy - dp(7f), dx + dp(12f), dy + dp(5f), paint);
            c.drawCircle(dx - dp(12f), dy - dp(10f), dp(5.5f), paint);
            paint.setColor(0xFF9AA5B1);
            c.drawOval(dx - dp(4f), dy - dp(9f), dx + dp(14f), dy - dp(2f), paint);
            paint.setColor(0xFFF0B132);
            c.drawRect(dx - dp(22f), dy - dp(11f), dx - dp(16f), dy - dp(8f), paint);
        } else {
            // A duck (or on the lake, a swan).
            boolean swan = place == LAKE;
            paint.setColor(swan ? 0xFFF7F7F2 : 0xFF6B4A2B);
            c.drawOval(dx - dp(14f), dy - dp(7f), dx + dp(12f), dy + dp(6f), paint);
            if (swan) {
                paint.setStrokeWidth(dp(4f));
                c.drawLine(dx - dp(8f), dy - dp(4f), dx - dp(12f), dy - dp(20f), paint);
                c.drawCircle(dx - dp(14f), dy - dp(21f), dp(4.5f), paint);
                paint.setColor(0xFFF07A2A);
                c.drawRect(dx - dp(24f), dy - dp(22f), dx - dp(17f), dy - dp(19f), paint);
            } else {
                paint.setColor(0xFF2E7D4F);
                c.drawCircle(dx - dp(12f), dy - dp(10f), dp(6f), paint);
                paint.setColor(0xFFF0B132);
                c.drawRect(dx - dp(22f), dy - dp(11f), dx - dp(16f), dy - dp(8f), paint);
            }
        }
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
