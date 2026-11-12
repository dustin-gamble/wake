package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * RIVER EXPLORER: row up a river that never ends, seen over your own bow, with a map that fills in.
 *
 * <p>The rower asked for "a game which simulates you actually rowing, first person view, with a mini
 * map, match row with speed". The river is generated, but deterministically: every stretch is
 * seeded by the choices that led to it, so the same branch is the same river next week. Every
 * {@link #SEGS_PER_FORK} stretches of {@link #SEG_LEN} metres it forks - tap a side (or lean the
 * handle sensor) to choose, and an unexplored branch is picked by default so rowing on discovers.
 *
 * <p>Persistent: the route taken, how far along it you are, and every stretch ever explored (drawn
 * on the minimap) are kept, so the journey spans sessions. Replaces the Journey card.
 *
 * <p>The view is a classic scanline projection: each horizontal strip of the screen is a distance
 * ahead, the river's centre at that distance comes from integrating the curvature, and banks, trees
 * and landmarks are placed on that frame. No 3D engine, so it runs on the tablet's Canvas.
 *
 * <p>3.20 additions, all at the rower's request:
 * <ul>
 *   <li>Wildlife at landmarks: the heron takes off as you close on it, the otters dive and surface
 *       and scatter when the boat gets near.</li>
 *   <li>Light and weather move with the rowing clock: a session starts in the morning, reaches
 *       golden hour and sunset around half an hour, night with stars and fireflies after 50 minutes,
 *       and dawn again at an hour. Cloud, rain and mist roll through in six-minute spells.</li>
 *   <li>Rapids on some stretches: six rocks, and the boat holds the safe line only while your stroke
 *       rate stays in a band around your typical rate. Drift out of it and the current carries you
 *       onto a rock, which hangs you up for a moment.</li>
 *   <li>A postcard album: every landmark found becomes a postcard, in the light you found it in.
 *       Tap ALBUM to leaf through; the row of eight shows which kinds you still lack.</li>
 *   <li>Kayaks, rowboats and scullers share the river. Most you will catch; a sculler rows near
 *       your own typical pace and gets away if you ease off.</li>
 * </ul>
 *
 * <p>3.23 additions, again at the rower's request:
 * <ul>
 *   <li><b>A map that fills in.</b> Tap the minimap for the whole surveyed river - every branch
 *       within {@link #MAP_FORKS} forks of the source, the ones you have rowed drawn solid and the
 *       rest as faint stubs showing where there is still river to find, with a percent explored.</li>
 *   <li><b>Expeditions that span sessions.</b> "Reach the lake, 3 km upstream": a named destination
 *       placed a rowing-time's worth of river ahead, drawn on the water as you close on it, and
 *       kept in the prefs so it is still waiting next week. Arriving offers the cruise home or
 *       pressing on to the next one.</li>
 *   <li><b>A weir portage.</b> A stone sill across the river stops the boat dead. The only way over
 *       is a short burst above your own typical rate - {@link #WEIR_SECONDS} seconds of it - and
 *       the charge bleeds back the moment you drop off.</li>
 *   <li><b>Seasons.</b> The real calendar month sets the river: spring runs in flood and costs you
 *       a metre a second, summer is low and slow, and the valley is coloured to match. See
 *       {@link RiverExplorerSeason}.</li>
 *   <li><b>The return cruise.</b> Turn downstream at a destination and the current carries you:
 *       no forks to judge, no rapids, no weirs, the river running the other way past the bow.</li>
 * </ul>
 */
final class RiverExplorerGame extends GameView {

    static final float SEG_LEN = 400f;
    static final int SEGS_PER_FORK = 3;
    private static final float HALF_WIDTH = 15f;
    private static final int LOOK = 300;
    private static final float CAM_H = 2.4f;
    private static final float TREE_STEP = 16f;
    private static final int MAX_EXPLORED = 1500;
    private static final float FORK_ANGLE = 0.45f;

    /* Rapids: six rocks 22 m apart inside a 152 m stretch. */
    private static final int ROCKS = 6;
    private static final float ROCK_FIRST = 20f;
    private static final float ROCK_GAP = 22f;
    private static final float RAPIDS_LEN = ROCK_FIRST + ROCKS * ROCK_GAP;
    /** How close (metres, sideways) the boat may pass a rock before it strikes it. */
    private static final float ROCK_HIT = 3.2f;
    /** Where the safe line runs, metres from the centre on the side away from the rock. */
    private static final float SAFE_LINE = 5f;

    /* Light: one day every hour of rowing. Minutes of the rowing clock at each keyframe. */
    private static final float DAY_MINUTES = 60f;
    private static final float[] KF_MIN = {0f, 14f, 28f, 38f, 45f, 50f, 57f, 60f};
    private static final int[] KF_TOP = {
            0xFF4A86C0, 0xFF2F6FB8, 0xFF3A6EA5, 0xFF3B3F78, 0xFF1B2146, 0xFF060A1A, 0xFF2E3A6A, 0xFF4A86C0};
    private static final int[] KF_BOT = {
            0xFFCFE3EE, 0xFFBFDDF2, 0xFFF2C39A, 0xFFF08A5A, 0xFF6A4E7A, 0xFF1A2440, 0xFFE8A88A, 0xFFCFE3EE};
    private static final float[] KF_LIGHT = {1.0f, 1.0f, 0.92f, 0.72f, 0.45f, 0.24f, 0.6f, 1.0f};
    private static final String[] KF_NAME = {
            "MORNING", "MIDDAY", "GOLDEN HOUR", "SUNSET", "DUSK", "NIGHT", "DAWN", "MORNING"};
    private static final int NIGHT_TINT = 0xFF081226;
    /* Weather spells: clear, overcast, rain, mist. */
    private static final float WEATHER_SPELL_S = 360f;
    private static final float[][] WEATHER = {
            {0.1f, 0f, 0f}, {0.85f, 0f, 0.05f}, {1f, 1f, 0.2f}, {0.4f, 0f, 1f}};

    private static final int TRAFFIC = 4;
    private static final int KAYAK = 0;
    private static final int ROWBOAT = 1;
    private static final int SCULL = 2;
    private static final String[] TRAFFIC_NAME = {"KAYAK", "ROWBOAT", "SCULLER"};
    private static final int[] HULLS = {0xFFF5C518, 0xFFE8573C, 0xFF35D0BA, 0xFF6F8CFF};

    private static final String[] LANDMARKS = {
            "HERON", "STONE BRIDGE", "WATERFALL", "LIGHTHOUSE", "WINDMILL", "OTTERS", "BOATHOUSE", "OLD MILL"};
    private static final String[] NAME_A = {
            "Heron", "Willow", "Mill", "Kingfisher", "Otter", "Alder", "Reed", "Swan", "Fern", "Bramble", "Lantern", "Salmon"};
    private static final String[] NAME_B = {
            "Reach", "Bend", "Run", "Cut", "Water", "Narrows", "Pool", "Stream", "Channel", "Race"};
    private static final int MAX_POSTCARDS = 80;
    private static final int CARDS_PER_PAGE = 8;

    /* Weir: a sill across the river that only a burst above your own rate will get you over. */
    /** How long the burst has to be held, at exactly the target rate. */
    private static final float WEIR_SECONDS = 5f;
    /** Metres short of the sill the boat is stopped. */
    private static final float WEIR_STOP = 14f;
    /**
     * How far above the rower's own typical rate the burst has to sit. Deliberately +2 and not
     * more: this machine's measured envelope is a p50 of 25 spm with a p90 of 26 and a max of 27,
     * so a target of 27 is a real lift that is still inside what the rower has actually produced.
     * Anything at +4 would be Rocket Launch's 130 W hover all over again - a wall, not a challenge.
     */
    private static final float WEIR_RATE_OVER = 2f;
    /** Rating well above the target gets over faster; this is the cap on that. */
    private static final float WEIR_MAX_GAIN = 1.6f;
    /** Seconds for a full charge to bleed away once the rate drops. */
    private static final float WEIR_DECAY_SECONDS = 8f;
    /**
     * Seconds held at a sill before the target eases by a beat. A weir is the only thing in the
     * game that stops the boat, and upstream there is nowhere to go round it, so a rower who cannot
     * reach the target would be stuck on the water with no way out. After a minute of being held
     * the demand has come back to their own typical rate, which they are by definition able to row.
     */
    private static final float WEIR_MERCY_SECONDS = 25f;

    /* The surveyed map: every branch within this many forks of the source. */
    private static final int MAP_FORKS = 5;
    /** 3 stretches per branch, 2^f branches at fork level f, levels 0..MAP_FORKS. */
    private static final int MAP_SEGS = SEGS_PER_FORK * ((1 << (MAP_FORKS + 1)) - 1);

    /* Expeditions: a named place a rowing-time's worth of river upstream. */
    private static final String[] DEST_NAME = {
            "SILVER LAKE", "THE HIGH FALLS", "RAVEN GORGE", "MOONLIT TARN", "THUNDER FALLS",
            "THE NARROWS", "LAKE ALDER", "KINGFISHER FALLS", "STONE GORGE"};
    private static final int DEST_LAKE = 0;
    private static final int DEST_FALLS = 1;
    private static final int DEST_GORGE = 2;
    /** Minutes of rowing the first expedition is meant to take; each one after is longer. */
    private static final float EXP_FIRST_MINUTES = 14f;
    private static final float EXP_STEP_MINUTES = 4f;
    private static final float EXP_MAX_MINUTES = 40f;
    /** The current may push you back this far below your furthest point, and no further. */
    private static final float DRIFT_LIMIT = 80f;

    /** One stretch of river: where it starts, how it bends, and what stands beside it. */
    private static final class Seg {
        final String prefix;
        final int index;
        final long seed;
        final float h0;
        final float curvA;
        final float curvB;
        final int landmark;
        final float landmarkAt;
        final boolean landmarkLeft;
        final boolean rapids;
        final float rapidsAt;
        final boolean weir;
        final float weirAt;
        /** Sideways position of each rock, metres from the river's centre. */
        final float[] rocks = new float[ROCKS];
        final float[] xs = new float[21];
        final float[] ys = new float[21];
        float h1;

        Seg(String prefix, int index, long seed, float x0, float y0, float h0) {
            this.prefix = prefix;
            this.index = index;
            this.seed = seed;
            this.h0 = h0;
            java.util.Random r = new java.util.Random(seed);
            curvA = (r.nextFloat() - 0.5f) * 0.005f;
            curvB = (r.nextFloat() - 0.5f) * 0.004f;
            landmark = r.nextFloat() < 0.55f ? r.nextInt(LANDMARKS.length) : -1;
            landmarkAt = 80f + r.nextFloat() * (SEG_LEN - 160f);
            landmarkLeft = r.nextBoolean();
            // Rapids come from their own stream so every river rowed before 3.20 keeps its bends and
            // its landmarks exactly where they were.
            java.util.Random rr = new java.util.Random(seed * 0x9E3779B97F4A7C15L + 7);
            rapids = index > 0 && index % SEGS_PER_FORK != 0 && rr.nextFloat() < 0.4f;
            rapidsAt = 40f + rr.nextFloat() * (SEG_LEN - RAPIDS_LEN - 60f);
            float side = rr.nextBoolean() ? 1f : -1f;
            for (int k = 0; k < ROCKS; k++) {
                rocks[k] = side * (3.5f + rr.nextFloat() * 2.5f);
                if (rr.nextFloat() < 0.7f) {
                    side = -side;
                }
            }
            // The weir gets its own stream again, for the same reason: a river rowed before 3.23
            // keeps every bend, landmark and rock exactly where it was, and simply gains a sill.
            java.util.Random rw = new java.util.Random(seed * 0x2545F4914F6CDD1DL + 13);
            weir = index > 0 && !rapids && rw.nextFloat() < 0.28f;
            weirAt = 130f + rw.nextFloat() * (SEG_LEN - 240f);
            float x = x0;
            float y = y0;
            float h = h0;
            xs[0] = x;
            ys[0] = y;
            for (int i = 1; i <= 20; i++) {
                float s = (i - 0.5f) * 20f;
                h += curvature(s) * 20f;
                x += (float) Math.sin(h) * 20f;
                y += (float) Math.cos(h) * 20f;
                xs[i] = x;
                ys[i] = y;
            }
            h1 = h;
        }

        float curvature(float s) {
            return curvA * (float) Math.sin(Math.PI * s / SEG_LEN) + curvB * (float) Math.sin(2 * Math.PI * s / SEG_LEN);
        }
    }

    /** A landmark found, kept as a postcard in the album. */
    private static final class Postcard {
        final int type;
        final String reach;
        final long when;
        final float km;
        /** Index into the light keyframes: the time of day it was found in. */
        final int phase;
        final String caption;

        Postcard(int type, String reach, long when, float km, int phase) {
            this.type = type;
            this.reach = reach;
            this.when = when;
            this.km = km;
            this.phase = phase;
            String date = when > 0
                    ? new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US).format(new java.util.Date(when))
                    : "an earlier row";
            this.caption = String.format(java.util.Locale.US, "Found %s  ·  %.1f km up", date, km);
        }

        String encode() {
            return type + "|" + reach + "|" + when + "|" + km + "|" + phase;
        }
    }

    /** Another boat on the river. Preallocated; never created in the frame loop. */
    private static final class Traffic {
        boolean active;
        boolean passed;
        int kind;
        double pos;
        float lane;
        float speed;
        float phase;
        int colour;
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final java.util.HashMap<String, Seg> cache = new java.util.HashMap<>();
    private final java.util.LinkedHashSet<String> explored = new java.util.LinkedHashSet<>();
    private final float[] offsets = new float[LOOK + 2];
    private final float[] angles = new float[LOOK + 2];
    private final java.util.ArrayList<Postcard> album = new java.util.ArrayList<>();
    private final boolean[] kindsFound = new boolean[LANDMARKS.length];
    private final Traffic[] traffic = new Traffic[TRAFFIC];

    private String route = "";
    private double along;
    private double prevAlong;
    private int landmarksFound;
    private char pendingChoice;
    private float lateral;
    private float oarPhase = 2f;
    private PulseMeter.Stroke lastStrokeSeen;
    private double lastMeters = -1;
    private String banner = "";
    private double bannerUntil;
    private String toast = "";
    private int toastColour = TEXT;
    private double toastUntil;
    private double lastSaveAt;
    private boolean started;

    /* Light and weather, recomputed each frame from the rowing clock. */
    private int skyTop;
    private int skyBot;
    private int fogColour;
    private float light = 1f;
    private float litLevel = 1f;
    private int dayPhase;
    private float dayMinute;
    private float cloud = 0.1f;
    private float rain;
    private float mist;
    private long weatherSeed;
    private LinearGradient skyShader;
    private int skyShaderKey;
    private float skyShaderHorizon;

    /*
     * 3.25 graphics pass. The ground loop now records each strip's river geometry so the water and
     * the banks can be drawn as smooth polygons (the per-strip rectangles stepped 19 px at a time
     * near the boat) and the water can carry a gradient. All preallocated; grown only on a resize.
     */
    private float[] stripY = new float[0];
    private float[] stripTop = new float[0];
    private float[] stripCx = new float[0];
    private float[] stripHalf = new float[0];
    private float[] stripSpread = new float[0];
    private float[] stripFog = new float[0];
    private int[] stripFoam = new int[0];
    private int stripCount;
    /** Strips [0, stripMain) are the main channel; beyond it the fork's two branches. */
    private int stripMain;
    private final Path path2 = new Path();
    /** Where the sun (or the moon) is, for its glitter path on the water and the scene's shading. */
    private float glintX;
    private float glintA;
    private int glintCol = 0xFFFFF4D0;
    /** -1 lit from the left, +1 from the right, 0 from straight ahead. */
    private float lightSide = -0.6f;
    private static final float RIPPLE_ROW = 3f;
    private static final int G_WATER = 0;
    private static final int G_BANK = 1;
    private static final int G_REFL = 2;
    private static final int G_HAZE = 3;
    private static final int G_HILL = 4; // 4, 5, 6
    private static final int G_HULL = 7;
    private static final int G_DECK = 8;
    private static final int G_SLOTS = 9;
    private final LinearGradient[] grads = new LinearGradient[G_SLOTS];
    private final long[] gradKeys = new long[G_SLOTS];
    private final int[] cols2 = new int[2];
    private final float[] pos2 = {0f, 1f};
    private final int[] cols3 = new int[3];
    private final float[] pos3 = new float[3];
    private final int[] cols5 = new int[5];
    private final float[] pos5 = new float[5];

    /* Wildlife. */
    private Seg heronSeg;
    private double heronStart;
    private boolean heronSplashed;
    private Seg otterSeg;
    private double otterStart;
    private boolean otterSplashed;

    /* Rapids. */
    private Seg rapSeg;
    private double rapBase;
    private float rhythm = 0.6f;
    private boolean inRapids;
    private int runCleared;
    private int runHits;
    /** Bit k set when rock k of this run was struck. */
    private int runHitMask;
    private int dodgeStreak;
    private float hitFlash;
    private float hitStall;
    private int rateLo;
    private int rateHi;
    private float rateNow;

    /* Traffic. */
    private double nextSpawnAt = 8;
    private int passedToday;
    private float passedLifetime;

    /* Postcards. */
    private boolean albumOpen;
    private int albumPage;
    private Postcard flyIn;
    private double flyInAt;
    private float albumBtnL;
    private float albumBtnT;
    private float albumBtnR;
    private float albumBtnB;

    /* Direction of travel: +1 rowing up the river, -1 the cruise home. */
    private int dir = 1;
    /** The furthest up the river this route has ever been. Expeditions are scored against it. */
    private double frontier;
    /** The furthest point of the current push upstream, which the drift floor is measured from. */
    private double pushHigh;
    private double cruiseFrom;
    private double cruiseHome;
    private float cruiseKm;

    /* The expedition. */
    private int expNumber;
    private double expStart;
    private double expTarget;
    private String expName = DEST_NAME[0];
    private int expKind;
    private boolean expArrived;
    private double arrivedAt;
    /* The place just reached, kept while the arrival panel is up: arriving sets the NEXT target at
     * once, so without these the lake or the falls would blink out of the river the instant you got
     * there and the panel would be sitting over plain water. */
    private double arrivedTarget;
    private int arrivedKind;
    private String arrivedName = DEST_NAME[0];
    private float cruiseBtnL;
    private float cruiseBtnT;
    private float cruiseBtnR;
    private float cruiseBtnB;
    private float pressBtnL;
    private float pressBtnT;
    private float pressBtnR;
    private float pressBtnB;
    private float turnBtnL;
    private float turnBtnT;
    private float turnBtnR;
    private float turnBtnB;

    /* The weir. */
    private final java.util.LinkedHashSet<String> weirsDone = new java.util.LinkedHashSet<>();
    private Seg weirSeg;
    private double weirBase;
    private String weirKey = "";
    private boolean weirBlocking;
    private float weirCharge;
    private float weirLift;
    private int weirTargetRate = 29;
    private int weirsThisSession;
    /** Seconds this sill has held the boat, which is what eases its target rate. */
    private float weirHeld;
    private String weirHeldKey = "";

    /* The season. */
    private RiverExplorerSeason season = RiverExplorerSeason.forMonth(0);
    /** Metres per second the river runs, already scaled to this rower. */
    private float current;

    private Bitmap map;
    private int mapExploredCount = -1;
    private boolean mapOpen;
    private Bitmap bigMap;
    private int bigMapCount = -1;
    private int bigMapW;
    private int bigMapH;
    private float bigMapMinX;
    private float bigMapMinY;
    private float bigMapScale;
    private float explorePct;
    private float mapBtnL;
    private float mapBtnT;
    private float mapBtnR;
    private float mapBtnB;
    private float mapMinX;
    private float mapMaxX;
    private float mapMinY;
    private float mapMaxY;
    private float mapScale;
    private int mapW;
    private int mapH;

    RiverExplorerGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        for (int i = 0; i < TRAFFIC; i++) {
            traffic[i] = new Traffic();
        }
    }

    @Override
    protected void onStart() {
        String saved = bests.getString("river.route");
        route = saved == null ? "" : saved;
        along = bests.get("river.along", 0f);
        landmarksFound = Math.round(bests.get("river.landmarks", 0f));
        passedLifetime = bests.get("river.passed", 0f);
        explored.clear();
        String keys = bests.getString("river.explored");
        if (keys != null && !keys.isEmpty()) {
            for (String k : keys.split(",")) {
                if (!k.isEmpty()) {
                    explored.add(k);
                }
            }
        }
        // A saved position beyond the choices made (an older save) is pulled back to the last fork.
        int maxIndex = (route.length() + 1) * SEGS_PER_FORK - 1;
        along = Math.min(along, (maxIndex + 1) * SEG_LEN - 1);
        prevAlong = along;
        frontier = Math.max(along, bests.get("river.frontier", (float) along));
        dir = bests.get("river.dir", 1f) < 0 ? -1 : 1;
        cruiseHome = bests.get("river.home", 0f);
        cruiseFrom = along;
        cruiseKm = 0f;
        pushHigh = along;
        expArrived = false;
        season = RiverExplorerSeason.at(System.currentTimeMillis());
        // The flood is set against the rower, not against a constant: a metre a second matters very
        // differently to someone holding 3.0 than to someone holding 4.2.
        double typicalSpeed = profile.typicalSpeed() > 1 ? profile.typicalSpeed() : 3.85;
        current = (float) (season.current * typicalSpeed / 3.85);
        loadExpedition();
        weirsDone.clear();
        String done = bests.getString("river.weirs.done");
        if (done != null) {
            for (String k : done.split(",")) {
                if (!k.isEmpty()) {
                    weirsDone.add(k);
                }
            }
        }
        weirSeg = null;
        weirBlocking = false;
        weirCharge = 0f;
        weirLift = 0f;
        weirHeld = 0f;
        weirHeldKey = "";
        weirKey = "";
        weirsThisSession = 0;
        mapOpen = false;
        bigMapCount = -1;
        pendingChoice = 0;
        lastMeters = -1;
        started = false;
        mapExploredCount = -1;
        markExplored(segIndex(along));
        loadAlbum();
        weatherSeed = System.currentTimeMillis() / 1000L;
        cloud = 0.1f;
        rain = 0f;
        mist = 0f;
        heronSeg = null;
        otterSeg = null;
        rapSeg = null;
        inRapids = false;
        rhythm = 0.6f;
        runCleared = 0;
        runHits = 0;
        runHitMask = 0;
        hitFlash = 0f;
        hitStall = 0f;
        lateral = 0f;
        dodgeStreak = 0;
        passedToday = 0;
        nextSpawnAt = 8;
        albumOpen = false;
        flyIn = null;
        for (Traffic t : traffic) {
            t.active = false;
        }
    }

    @Override
    protected void onStop() {
        save();
    }

    private void save() {
        bests.putString("river.route", route);
        bests.putFloat("river.along", (float) along);
        StringBuilder sb = new StringBuilder();
        for (String k : explored) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k);
        }
        bests.putString("river.explored", sb.toString());
        bests.putFloat("river.landmarks", landmarksFound);
        bests.putFloat("river.passed", passedLifetime);
        bests.recordHighest("river.km", explored.size() * SEG_LEN / 1000f);
        bests.putFloat("river.frontier", (float) frontier);
        bests.putFloat("river.dir", dir);
        bests.putFloat("river.home", (float) cruiseHome);
        saveExpedition();
        StringBuilder wb = new StringBuilder();
        for (String k : weirsDone) {
            if (wb.length() > 0) {
                wb.append(',');
            }
            wb.append(k);
        }
        bests.putString("river.weirs.done", wb.toString());
        if (explorePct > 0) {
            bests.recordHighest("river.pct", explorePct);
        }
    }

    /* ---------- expeditions ---------- */

    private void loadExpedition() {
        expNumber = Math.round(bests.get("river.exp", 0f));
        String name = bests.getString("river.exp.name");
        expStart = bests.get("river.exp.start", -1f);
        expTarget = bests.get("river.exp.target", -1f);
        if (name == null || expTarget <= expStart || expStart < 0) {
            newExpedition();
            return;
        }
        expName = name;
        expKind = Math.round(bests.get("river.exp.kind", 0f)) % 3;
        // A save from a route that has since been pulled back cannot have a goal behind it.
        if (expTarget <= frontier) {
            newExpedition();
        }
    }

    private void saveExpedition() {
        bests.putFloat("river.exp", expNumber);
        bests.putString("river.exp.name", expName);
        bests.putFloat("river.exp.start", (float) expStart);
        bests.putFloat("river.exp.target", (float) expTarget);
        bests.putFloat("river.exp.kind", expKind);
    }

    /**
     * Sets the next destination: a place named for the rower to aim at, placed a rowing-time's worth
     * of river above their furthest point. The span is net of the season's current and of the
     * rower's own typical speed, so "reach the lake" is about the same piece of work whoever rows it
     * and whatever the river is doing.
     */
    private void newExpedition() {
        double typical = profile.typicalSpeed() > 1 ? profile.typicalSpeed() : 3.85;
        double net = Math.max(0.9, typical - current);
        float minutes = Math.min(EXP_MAX_MINUTES, EXP_FIRST_MINUTES + EXP_STEP_MINUTES * expNumber);
        double span = net * minutes * 60.0;
        // Rounded to a tidy 100 m so the HUD reads "3.0 km" rather than "2.87 km".
        span = Math.max(1200, Math.round(span / 100.0) * 100.0);
        expStart = Math.max(0, frontier);
        expTarget = expStart + span;
        int pick = expNumber % DEST_NAME.length;
        expName = expNumber < DEST_NAME.length
                ? DEST_NAME[pick]
                : DEST_NAME[pick] + " " + (expNumber / DEST_NAME.length + 1);
        expKind = pick % 3;
        expArrived = false;
        saveExpedition();
    }

    /** How far along the expedition the rower is, 0..1, scored on the frontier so a cruise home keeps it. */
    private float expProgress() {
        double span = expTarget - expStart;
        if (span <= 0) {
            return 0f;
        }
        return (float) Math.max(0, Math.min(1, (frontier - expStart) / span));
    }

    private void arriveAtDestination() {
        expArrived = true;
        arrivedAt = sessionSeconds;
        arrivedTarget = expTarget;
        arrivedKind = expKind;
        arrivedName = expName;
        expNumber++;
        bests.recordHighest("river.exped", expNumber);
        showBanner("ARRIVED  ·  " + expName);
        fx.burst(getWidth() * 0.5f, getHeight() * 0.42f, 70, dp(320f), 1.4f, dp(4f), 0xFFF5C518, true);
        fx.burst(getWidth() * 0.5f, getHeight() * 0.42f, 40, dp(240f), 1.2f, dp(3.5f), 0xFF35D0BA, true);
        shake.kick(dp(10f));
        cruiseHome = expStart;
        // The next one is set at once, from here, so the progress bar never goes blank.
        newExpedition();
    }

    private void beginCruise() {
        dir = -1;
        expArrived = false;
        cruiseFrom = along;
        weirBlocking = false;
        weirSeg = null;
        rapSeg = null;
        inRapids = false;
        lateral = 0f;
        for (Traffic t : traffic) {
            t.active = false;
        }
        showBanner("TURNING FOR HOME  ·  THE CURRENT IS WITH YOU");
        save();
    }

    private void turnUpstream(String why) {
        if (dir > 0) {
            return;
        }
        cruiseKm += (float) Math.max(0, cruiseFrom - along) / 1000f;
        bests.recordHighest("river.cruise", cruiseKm);
        dir = 1;
        // A fresh push upstream: the drift floor starts again from here, not from the frontier.
        pushHigh = along;
        // Banked above, so the next cruise measures from where it actually starts. Without this a
        // second cruise would count the first one's metres all over again.
        cruiseFrom = along;
        for (Traffic t : traffic) {
            t.active = false;
        }
        lateral = 0f;
        showBanner(why);
        save();
    }

    /* ---------- the album ---------- */

    private void loadAlbum() {
        album.clear();
        java.util.Arrays.fill(kindsFound, false);
        String saved = bests.getString("river.album");
        if (saved != null) {
            for (String e : saved.split(";")) {
                String[] f = e.split("\\|");
                if (f.length < 5) {
                    continue;
                }
                try {
                    int type = Integer.parseInt(f[0]);
                    if (type < 0 || type >= LANDMARKS.length) {
                        continue;
                    }
                    album.add(new Postcard(type, f[1], Long.parseLong(f[2]), Float.parseFloat(f[3]),
                            Math.max(0, Math.min(KF_NAME.length - 1, Integer.parseInt(f[4])))));
                } catch (NumberFormatException ignored) {
                    // a damaged entry is skipped rather than losing the album
                }
            }
        } else {
            // First run of the album: every landmark found before it existed gets its card back.
            for (String key : bests.all().keySet()) {
                if (!key.startsWith("river.found.")) {
                    continue;
                }
                String k = key.substring("river.found.".length());
                int colon = k.lastIndexOf(':');
                if (colon < 0) {
                    continue;
                }
                try {
                    int index = Integer.parseInt(k.substring(colon + 1));
                    String prefix = k.substring(0, colon);
                    Seg s = geom(prefix, index);
                    if (s.landmark >= 0) {
                        album.add(new Postcard(s.landmark, reachName(prefix, index), 0L,
                                (index * SEG_LEN + s.landmarkAt) / 1000f, 2));
                    }
                } catch (RuntimeException ignored) {
                    // a malformed key from an older save is skipped
                }
            }
            if (!album.isEmpty()) {
                saveAlbum();
            }
        }
        for (Postcard p : album) {
            kindsFound[p.type] = true;
        }
    }

    private void saveAlbum() {
        while (album.size() > MAX_POSTCARDS) {
            album.remove(0);
        }
        StringBuilder sb = new StringBuilder();
        for (Postcard p : album) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(p.encode());
        }
        bests.putString("river.album", sb.toString());
    }

    /* ---------- the river as data ---------- */

    private static int segIndex(double s) {
        return (int) Math.floor(Math.max(0, s) / SEG_LEN);
    }

    /** The fork choices governing a stretch, using the pending choice for the next fork. */
    private String prefixFor(int index) {
        int forks = index / SEGS_PER_FORK;
        StringBuilder p = new StringBuilder(route.length() >= forks ? route.substring(0, forks) : route);
        while (p.length() < forks) {
            p.append(p.length() == route.length() ? choiceForNextFork() : 'L');
        }
        return p.toString();
    }

    private static long seedFor(String prefix, int index) {
        long h = 1125899906842597L;
        for (int i = 0; i < prefix.length(); i++) {
            h = 31 * h + prefix.charAt(i);
        }
        h = 31 * h + index;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }

    private Seg geom(String prefix, int index) {
        String key = prefix + ":" + index;
        Seg s = cache.get(key);
        if (s != null) {
            return s;
        }
        if (index == 0) {
            s = new Seg(prefix, 0, seedFor(prefix, 0), 0f, 0f, 0f);
        } else if (index % SEGS_PER_FORK == 0) {
            Seg parent = geom(prefix.substring(0, prefix.length() - 1), index - 1);
            float turn = prefix.charAt(prefix.length() - 1) == 'L' ? -FORK_ANGLE : FORK_ANGLE;
            s = new Seg(prefix, index, seedFor(prefix, index), parent.xs[20], parent.ys[20], parent.h1 + turn);
        } else {
            Seg parent = geom(prefix, index - 1);
            s = new Seg(prefix, index, seedFor(prefix, index), parent.xs[20], parent.ys[20], parent.h1);
        }
        if (cache.size() > 4000) {
            cache.clear();
        }
        cache.put(key, s);
        return s;
    }

    /** The stretch at an index, clamped at the source so the cruise home cannot run off the end. */
    private Seg segAt(int index) {
        int i = Math.max(0, index);
        return geom(prefixFor(i), i);
    }

    /** Default for the next fork: a branch not yet explored, else keep left. */
    private char choiceForNextFork() {
        if (pendingChoice != 0) {
            return pendingChoice;
        }
        int nextIndex = (route.length() + 1) * SEGS_PER_FORK;
        String left = route + "L:" + nextIndex;
        String right = route + "R:" + nextIndex;
        if (!explored.contains(left)) {
            return 'L';
        }
        if (!explored.contains(right)) {
            return 'R';
        }
        return 'L';
    }

    private String reachName(String prefix, int index) {
        long seed = seedFor(prefix, (index / SEGS_PER_FORK) * SEGS_PER_FORK);
        int a = (int) ((seed >>> 8) & 0x7fffffff) % NAME_A.length;
        int b = (int) ((seed >>> 24) & 0x7fffffff) % NAME_B.length;
        return NAME_A[a] + " " + NAME_B[b];
    }

    /** @return true when this stretch had never been rowed before */
    private boolean markExplored(int index) {
        String key = prefixFor(index) + ":" + index;
        if (explored.contains(key)) {
            return false;
        }
        if (explored.size() >= MAX_EXPLORED) {
            java.util.Iterator<String> it = explored.iterator();
            it.next();
            it.remove();
        }
        explored.add(key);
        return true;
    }

    /* ---------- live ---------- */

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastStrokeSeen) {
            lastStrokeSeen = stroke;
            oarPhase = 0f;
        }
    }

    @Override
    protected void onStroke(int watts) {
        if (status != null && status.meter.strokes == 0) {
            oarPhase = 0f;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX();
            float y = e.getY();
            float w = getWidth();
            float h = getHeight();
            if (albumOpen) {
                int pages = Math.max(1, (album.size() + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE);
                if (y > h - dp(64f) && x < w / 3f) {
                    albumPage = (albumPage + pages - 1) % pages;
                } else if (y > h - dp(64f) && x > w * 2f / 3f) {
                    albumPage = (albumPage + 1) % pages;
                } else {
                    albumOpen = false;
                }
            } else if (mapOpen) {
                mapOpen = false;
            } else if (expArrived && cruiseBtnR > 0 && hit(x, y, cruiseBtnL, cruiseBtnT, cruiseBtnR, cruiseBtnB)) {
                beginCruise();
            } else if (expArrived && pressBtnR > 0 && hit(x, y, pressBtnL, pressBtnT, pressBtnR, pressBtnB)) {
                expArrived = false;
                showBanner("PRESSING ON  ·  " + expName);
            } else if (dir < 0 && turnBtnR > 0 && hit(x, y, turnBtnL, turnBtnT, turnBtnR, turnBtnB)) {
                turnUpstream("TURNED UPSTREAM  ·  "
                        + String.format(java.util.Locale.US, "%.1f km cruised", cruiseKmSoFar()));
            } else if (hit(x, y, albumBtnL, albumBtnT, albumBtnR, albumBtnB)) {
                albumOpen = true;
                albumPage = 0;
            } else if (mapBtnR > 0 && hit(x, y, mapBtnL, mapBtnT, mapBtnR, mapBtnB)) {
                mapOpen = true;
            } else if (distanceToFork() < 450f) {
                pendingChoice = x < w / 2f ? 'L' : 'R';
            }
            postInvalidateOnAnimation();
        }
        super.onTouchEvent(e);
        return true;
    }

    /** A tap inside a drawn control, with a finger-sized margin round it. */
    private boolean hit(float x, float y, float l, float t, float r, float b) {
        float m = dp(8f);
        return x >= l - m && x <= r + m && y >= t - m && y <= b + m;
    }

    private double distanceToFork() {
        if (dir < 0) {
            // Downstream the branches merge behind you; there is nothing to choose.
            return Double.MAX_VALUE;
        }
        double forkAt = (route.length() + 1) * SEGS_PER_FORK * SEG_LEN;
        return forkAt - along;
    }

    private void advance(float dt) {
        if (lastMeters < 0) {
            lastMeters = sessionMeters;
        }
        double moved = Math.max(0, sessionMeters - lastMeters);
        lastMeters = sessionMeters;
        if (!started) {
            return;
        }
        // Hung up on a rock: the boat barely makes way until it slides off.
        if (hitStall > 0) {
            moved *= 0.25;
        }
        if (dir < 0) {
            cruiseDownstream(moved, dt);
            return;
        }
        // Upstream: the river runs against you, so a rest loses ground. Not much - the drift stops
        // DRIFT_LIMIT below the furthest point of THIS push upstream - but enough that easing off
        // is felt. Measured against pushHigh rather than the lifetime frontier, or a boat that had
        // cruised home would be shoved back up the river the instant it stopped rowing.
        double step = moved - current * dt + (weirLift > 0 ? 15.0 * dt : 0.0);
        double alongBefore = along;
        int before = segIndex(along);
        along += step;
        pushHigh = Math.max(pushHigh, along);
        double floor = pushHigh - DRIFT_LIMIT;
        // The branch you are in must not unwind underneath you - but only once THIS push is above
        // its start. After a cruise home the boat sits far below the branch start, and an
        // unconditional branch floor shoved it straight back up the river on the first frame after
        // turning upstream, undoing the whole run home in one jump. Capping the floor at pushHigh
        // makes it impossible for the floor to move the boat forwards under any circumstances.
        double branchStart = route.length() * (double) SEGS_PER_FORK * SEG_LEN;
        if (pushHigh >= branchStart) {
            floor = Math.max(floor, branchStart);
        }
        floor = Math.min(floor, pushHigh);
        along = Math.max(Math.max(0, floor), along);
        // The weir stops the boat. Not stone dead, though: the charge claws the bow up the face and
        // losing it slides you back down, so the stake is visible on the water rather than only on
        // a bar. A frozen scene reads as a hung app.
        if (weirBlocking && weirLift <= 0) {
            along = Math.min(along, weirBase - WEIR_STOP + weirCharge * 9f);
        }
        frontier = Math.max(frontier, along);
        if (!expArrived && frontier >= expTarget - 0.5) {
            arriveAtDestination();
        } else if (expArrived && frontier > arrivedAtPoint() + 150) {
            // Rowed on without picking: pressing on is the obvious reading of that.
            expArrived = false;
        }
        int after = segIndex(along);
        // What the boat ACTUALLY made, after the drift floor and the weir: the clamps mean the
        // intended step and the real one are not the same thing.
        double advanced = along - alongBefore;
        if (advanced <= 0) {
            return;
        }
        for (int idx = before + 1; idx <= after; idx++) {
            if (idx % SEGS_PER_FORK == 0 && idx / SEGS_PER_FORK > route.length()) {
                char choice = choiceForNextFork();
                route += choice;
                pendingChoice = 0;
                showBanner("ENTERING " + reachName(route, idx).toUpperCase(java.util.Locale.US));
            }
            markExplored(idx);
        }
        // Landmarks are found the first time a stretch is rowed past them.
        int idx = segIndex(along);
        Seg seg = geom(prefixFor(idx), idx);
        double inSeg = along - idx * SEG_LEN;
        String foundKey = "river.found." + prefixFor(idx) + ":" + idx;
        if (seg.landmark >= 0 && inSeg >= seg.landmarkAt && inSeg - advanced < seg.landmarkAt
                && bests.getString(foundKey) == null) {
            bests.putString(foundKey, "1");
            landmarksFound++;
            String reach = reachName(prefixFor(idx), idx);
            boolean newKind = !kindsFound[seg.landmark];
            showBanner((newKind ? "NEW POSTCARD  " : "DISCOVERED  ") + LANDMARKS[seg.landmark] + "  ·  "
                    + reach.toUpperCase(java.util.Locale.US));
            Postcard card = new Postcard(seg.landmark, reach, System.currentTimeMillis(), (float) (along / 1000.0), dayPhase);
            album.add(card);
            kindsFound[seg.landmark] = true;
            saveAlbum();
            flyIn = card;
            flyInAt = sessionSeconds;
            fx.burst(getWidth() * 0.5f, getHeight() * 0.45f, 40, dp(220f), 1.0f, dp(3.5f), 0xFFF5C518, true);
        }
        if (sessionSeconds - lastSaveAt > 10) {
            lastSaveAt = sessionSeconds;
            save();
        }
    }

    /**
     * The reward leg. Downstream the current is with you rather than against you, there are no
     * forks to judge and the hazards are switched off - the point is the river going past. Every
     * metre banks toward {@code river.cruise}.
     */
    private void cruiseDownstream(double moved, float dt) {
        double step = moved + current * dt;
        along -= step;
        if (along <= cruiseHome) {
            along = Math.max(0, cruiseHome);
            turnUpstream("HOME  ·  " + String.format(java.util.Locale.US, "%.1f km cruised", cruiseKmSoFar()));
        }
        if (sessionSeconds - lastSaveAt > 10) {
            lastSaveAt = sessionSeconds;
            save();
        }
    }

    private float cruiseKmSoFar() {
        return cruiseKm + (float) Math.max(0, cruiseFrom - along) / 1000f;
    }

    /**
     * Where the boat was when the destination was reached. {@link #arriveAtDestination()} sets the
     * next expedition's start to exactly that point, so this is it - used for the "rowed straight
     * on without picking" auto-choice.
     */
    private double arrivedAtPoint() {
        return expStart;
    }

    private void showBanner(String text) {
        banner = text;
        bannerUntil = sessionSeconds + 4;
    }

    private void showToast(String text, int colour) {
        toast = text;
        toastColour = colour;
        toastUntil = sessionSeconds + 3;
    }

    /* ---------- light and weather ---------- */

    /**
     * Time of day from the rowing clock (so resting does not burn daylight), and weather spells that
     * ease in over half a minute. Sets the sky colours, the fog colour and how lit the world is.
     */
    private void updateSky(float dt) {
        dayMinute = (float) ((activeSeconds / 60.0) % DAY_MINUTES);
        int k = 0;
        while (k < KF_MIN.length - 2 && dayMinute >= KF_MIN[k + 1]) {
            k++;
        }
        dayPhase = k;
        float f = (dayMinute - KF_MIN[k]) / (KF_MIN[k + 1] - KF_MIN[k]);
        // Ease across each keyframe so the light lingers at golden hour rather than sliding through.
        f = f * f * (3 - 2 * f);
        int top = blend(KF_TOP[k], KF_TOP[k + 1], f);
        int bot = blend(KF_BOT[k], KF_BOT[k + 1], f);
        float baseLight = KF_LIGHT[k] + (KF_LIGHT[k + 1] - KF_LIGHT[k]) * f;

        // The first spell of a session is always fair; later ones are dealt from this session's seed.
        int spell = (int) (activeSeconds / WEATHER_SPELL_S);
        int kind = 0;
        if (spell > 0) {
            long hsh = (weatherSeed + spell * 0x9E3779B97F4A7C15L) * 0xff51afd7ed558ccdL;
            int roll = (int) ((hsh >>> 40) % 100);
            kind = roll < 40 ? 0 : roll < 65 ? 1 : roll < 85 ? 2 : 3;
        }
        float ease = Math.min(1f, dt / 30f);
        cloud += (WEATHER[kind][0] - cloud) * ease;
        rain += (WEATHER[kind][1] - rain) * ease;
        mist += (WEATHER[kind][2] - mist) * ease;

        float dark = 1f - baseLight;
        int grey = blend(0xFF8A949E, 0xFF10141A, dark);
        skyTop = blend(top, grey, cloud * 0.55f);
        skyBot = blend(bot, grey, cloud * 0.45f);
        light = baseLight * (1f - 0.18f * cloud - 0.08f * rain);
        litLevel = light;
        fogColour = blend(blend(skyBot, 0xFFB9C7B0, 0.4f * light), blend(0xFFC4CCD0, NIGHT_TINT, dark), mist * 0.6f);
    }

    private String lightName() {
        String w = rain > 0.4f ? "RAIN" : mist > 0.45f ? "MIST" : cloud > 0.55f ? "OVERCAST" : "CLEAR";
        return KF_NAME[dayPhase] + ", " + w;
    }

    /** A colour as the current light shows it: toward a blue-black as the day goes. */
    private int lit(int colour) {
        if (litLevel >= 0.999f) {
            return colour;
        }
        int a = colour & 0xFF000000;
        return a | (blend(colour, NIGHT_TINT, (1f - litLevel) * 0.85f) & 0x00FFFFFF);
    }

    /** A world colour: lit, then faded into the distance haze. */
    private int world(int colour, float fog) {
        return blend(lit(colour), fogColour, fog);
    }

    /* ---------- the weir ---------- */

    /**
     * A stone sill across the river. The boat cannot make way past it at all: the only way over is
     * {@link #WEIR_SECONDS} seconds of rating above the rower's own typical cadence, and the charge
     * bleeds back at once when the rate drops, so it has to be one burst rather than a dozen
     * half-hearted ones. Switched off on the cruise home - downstream you simply shoot it.
     */
    private void updateWeir(Seg near, Seg far, int firstIdx, float dt) {
        weirLift = Math.max(0f, weirLift - dt);
        if (dir < 0) {
            weirSeg = null;
            weirBlocking = false;
            weirCharge = 0f;
            return;
        }
        weirSeg = null;
        double nearStart = firstIdx * SEG_LEN;
        if (near.weir && nearStart + near.weirAt > along - 30) {
            weirSeg = near;
            weirBase = nearStart + near.weirAt;
            weirKey = near.prefix + ":" + near.index;
        } else if (far.weir) {
            weirSeg = far;
            weirBase = nearStart + SEG_LEN + far.weirAt;
            weirKey = far.prefix + ":" + far.index;
        }
        boolean open = weirSeg == null || weirsDone.contains(weirKey);
        boolean wasBlocking = weirBlocking;
        weirBlocking = !open && weirLift <= 0 && along >= weirBase - WEIR_STOP - 2f;
        // The rate to beat comes from the rower's own cadence, never a constant - and it eases by a
        // beat every WEIR_MERCY_SECONDS of being held, down to that cadence, so a sill can never be
        // a dead end. Held time is per sill, so a weir cleared quickly leaves the next one full.
        double centre = profile.typicalRate() > 10 ? profile.typicalRate() : 25.0;
        if (!weirKey.equals(weirHeldKey)) {
            weirHeldKey = weirKey;
            weirHeld = 0f;
        }
        if (weirBlocking) {
            weirHeld += dt;
        }
        float eased = Math.min(WEIR_RATE_OVER, weirHeld / WEIR_MERCY_SECONDS);
        weirTargetRate = Math.max(14, (int) Math.round(centre + WEIR_RATE_OVER - eased));
        if (weirBlocking && !wasBlocking) {
            showToast("WEIR!  BURST ABOVE " + weirTargetRate + " spm TO GET OVER", WARN);
        }
        if (!weirBlocking) {
            weirCharge = Math.max(0f, weirCharge - dt * 0.6f);
            return;
        }
        boolean hard = driving && rateNow >= weirTargetRate;
        if (hard) {
            // Rating well over the target gets the boat up faster, so a big lift is rewarded
            // rather than merely tolerated.
            float gain = Math.min(WEIR_MAX_GAIN, 1f + (rateNow - weirTargetRate) / 5f);
            weirCharge += gain * dt / WEIR_SECONDS;
        } else {
            weirCharge -= dt / WEIR_DECAY_SECONDS;
        }
        weirCharge = Math.max(0f, Math.min(1f, weirCharge));
        if (hard) {
            // Spray off the sill while the boat is being driven at it.
            if (Math.random() < 0.35) {
                fx.burst(getWidth() * (0.35f + (float) Math.random() * 0.3f), getHeight() * 0.62f,
                        4, dp(150f), 0.5f, dp(3f), 0xCCD8ECF2, true);
            }
        }
        if (weirCharge >= 1f) {
            weirsDone.add(weirKey);
            while (weirsDone.size() > 60) {
                java.util.Iterator<String> it = weirsDone.iterator();
                it.next();
                it.remove();
            }
            weirBlocking = false;
            weirCharge = 0f;
            weirHeld = 0f;
            weirLift = 1.6f;
            weirsThisSession++;
            bests.recordHighest("river.weirs", weirsThisSession);
            shake.kick(dp(14f));
            fx.burst(getWidth() * 0.5f, getHeight() * 0.66f, 46, dp(300f), 1.0f, dp(4f), 0xEEFFFFFF, true);
            showBanner("OVER THE WEIR!  ·  " + weirsThisSession + " THIS ROW");
        }
    }

    /* ---------- rapids ---------- */

    /** The rate the rapids and the weir are both judged against, read once a frame. */
    private void updateRate() {
        rateNow = status == null ? 0f
                : (float) (status.strokeRatePrecise > 0 ? status.strokeRatePrecise : status.strokeRate);
    }

    /** Picks the rapids in play, scores rocks crossed this frame, and steers the boat's line. */
    private void updateRapids(Seg near, Seg far, int firstIdx, float dt) {
        hitFlash = Math.max(0f, hitFlash - dt * 1.5f);
        hitStall = Math.max(0f, hitStall - dt);
        if (dir < 0) {
            // Nothing is at stake on the cruise home: the rapids are simply not run.
            rapSeg = null;
            inRapids = false;
            return;
        }
        double nearStart = firstIdx * SEG_LEN;
        rapSeg = null;
        if (near.rapids && prevAlong < nearStart + near.rapidsAt + RAPIDS_LEN) {
            rapSeg = near;
            rapBase = nearStart + near.rapidsAt;
        } else if (far.rapids) {
            rapSeg = far;
            rapBase = nearStart + SEG_LEN + far.rapidsAt;
        }
        double center = profile.typicalRate() > 10 ? profile.typicalRate() : 25.0;
        rateLo = (int) Math.floor(center - 2);
        rateHi = (int) Math.ceil(center + 2);
        boolean inBand = driving && rateNow >= rateLo && rateNow <= rateHi;
        rhythm += inBand ? dt / 1.2f : -dt / 3.5f;
        rhythm = Math.max(0f, Math.min(1f, rhythm));

        inRapids = false;
        if (rapSeg == null) {
            return;
        }
        double end = rapBase + RAPIDS_LEN;
        if (prevAlong < rapBase && along >= rapBase) {
            runCleared = 0;
            runHits = 0;
            runHitMask = 0;
            showToast("RAPIDS!  HOLD " + rateLo + "-" + rateHi + " spm", WARN);
        }
        inRapids = along >= rapBase && along < end;
        for (int k = 0; k < ROCKS; k++) {
            double rockAt = rapBase + ROCK_FIRST + k * ROCK_GAP;
            if (prevAlong < rockAt && along >= rockAt) {
                if (Math.abs(lateral - rapSeg.rocks[k]) < ROCK_HIT) {
                    runHits++;
                    runHitMask |= 1 << k;
                    dodgeStreak = 0;
                    hitFlash = 1f;
                    hitStall = 1.5f;
                    shake.kick(dp(16f));
                    fx.burst(getWidth() * 0.5f, getHeight() * 0.72f, 30, dp(260f), 0.8f, dp(4f), 0xEEFFFFFF, true);
                    showToast("CRUNCH!  BACK TO " + rateLo + "-" + rateHi + " spm", BAD);
                } else {
                    runCleared++;
                    dodgeStreak++;
                    bests.recordHighest("river.rapids", dodgeStreak);
                    fx.burst(getWidth() * (rapSeg.rocks[k] < lateral ? 0.3f : 0.7f), getHeight() * 0.6f,
                            12, dp(140f), 0.5f, dp(3f), 0xCCBFE3FF, true);
                }
            }
        }
        if (prevAlong < end && along >= end) {
            if (runHits == 0) {
                showBanner("CLEAN RUN  ·  " + ROCKS + " OF " + ROCKS + " ROCKS");
                fx.burst(getWidth() * 0.5f, getHeight() * 0.4f, 50, dp(260f), 1.1f, dp(3.5f), 0xFF35D0BA, true);
            } else {
                showBanner("THROUGH THE RAPIDS  ·  " + runCleared + " OF " + ROCKS + " CLEAN");
            }
        }
    }

    /** Where the current puts the boat: on the safe line while the rate holds, onto the rock if not. */
    private float rapidsTarget() {
        if (rapSeg == null) {
            return 0f;
        }
        for (int k = 0; k < ROCKS; k++) {
            double rockAt = rapBase + ROCK_FIRST + k * ROCK_GAP;
            if (rockAt > along && rockAt - along < ROCK_GAP + 8f) {
                float rock = rapSeg.rocks[k];
                float safe = rock > 0 ? -SAFE_LINE : SAFE_LINE;
                return rock + (safe - rock) * rhythm;
            }
        }
        return 0f;
    }

    /* ---------- traffic ---------- */

    private void updateTraffic(float dt) {
        double typical = profile.typicalSpeed() > 1 ? profile.typicalSpeed() : 3.85;
        int active = 0;
        for (Traffic t : traffic) {
            if (!t.active) {
                continue;
            }
            t.pos += t.speed * dt;
            t.phase += dt * (t.kind == KAYAK ? 2.4f : t.kind == SCULL ? 1.7f : 1.3f);
            double z = (t.pos - along) * dir;
            if (!t.passed && z < 0) {
                t.passed = true;
                passedToday++;
                passedLifetime++;
                showToast((dir > 0 ? "OVERTOOK A " : "PASSED A ") + TRAFFIC_NAME[t.kind] + "  ·  "
                        + passedToday + " THIS ROW", ACCENT);
                fx.burst(getWidth() * (t.lane < lateral ? 0.12f : 0.88f), getHeight() * 0.7f, 18, dp(160f),
                        0.7f, dp(3f), 0xFFF5C518, true);
            }
            if (z < -12) {
                t.active = false;
            } else if (z > (t.kind == SCULL ? 160 : LOOK + 30)) {
                t.active = false;
                if (!t.passed) {
                    showToast("THE " + TRAFFIC_NAME[t.kind] + " GOT AWAY", BAD);
                }
            } else {
                active++;
            }
        }
        boolean rapidsNear = rapSeg != null && rapBase + RAPIDS_LEN > along - 20 && rapBase - along < LOOK + 60;
        boolean weirNear = weirSeg != null && weirBase - along < LOOK + 60 && weirBase > along - 20;
        if (!started || rapidsNear || weirNear || active >= 2 || sessionSeconds < nextSpawnAt
                || boat.value() < 1f) {
            return;
        }
        for (Traffic t : traffic) {
            if (t.active) {
                continue;
            }
            double roll = Math.random();
            t.kind = roll < 0.45 ? KAYAK : roll < 0.8 ? ROWBOAT : SCULL;
            // Kayaks and rowboats are caught in a couple of minutes at any honest pace. The sculler
            // sits at 80-90% of your typical speed, 90 m up: hold your pace and you reel it in, ease
            // off to your low end and it pulls away.
            double frac = t.kind == KAYAK ? 0.55 + Math.random() * 0.2
                    : t.kind == ROWBOAT ? 0.45 + Math.random() * 0.15
                    : 0.8 + Math.random() * 0.1;
            t.speed = (float) (typical * frac);
            t.pos = along + dir * (t.kind == SCULL ? 90 : LOOK * 0.6);
            t.lane = (Math.random() < 0.5 ? -1f : 1f) * (6f + (float) Math.random() * 4f);
            t.phase = (float) (Math.random() * 6);
            t.passed = false;
            t.colour = HULLS[(int) (Math.random() * HULLS.length)];
            t.active = true;
            break;
        }
        nextSpawnAt = sessionSeconds + 10 + Math.random() * 12;
    }

    /* ---------- drawing ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        prevAlong = along;
        advance(dt);
        fx.step(dt, dp(300f));
        shake.step(dt);
        updateSky(dt);
        float speed = boat.value();

        // Curvature ahead, integrated to a centre-line offset relative to where the boat points.
        // The view spans at most two stretches (LOOK < SEG_LEN), so look each up once per frame. The
        // first version called curvatureAt() for every metre - 300 string keys and map lookups a
        // frame, thousands of short-lived objects a second on a tablet that stutters on garbage.
        angles[0] = 0f;
        offsets[0] = 0f;
        int firstIdx = segIndex(along);
        Seg near = segAt(firstIdx);
        Seg far = segAt(firstIdx + dir);
        double nearStart = firstIdx * (double) SEG_LEN;
        double nearEnd = nearStart + SEG_LEN;
        double farStart = Math.max(0, firstIdx + dir) * (double) SEG_LEN;
        for (int i = 0; i <= LOOK; i++) {
            // Downstream the boat's heading is reversed, so the bend at a given point reads the
            // other way round: the curvature is sampled behind the boat and negated.
            double s = along + dir * i;
            float k;
            if (s >= nearStart && s < nearEnd) {
                k = near.curvature((float) (s - nearStart));
            } else {
                k = far.curvature((float) Math.max(0, Math.min(SEG_LEN, s - farStart)));
            }
            k *= dir;
            angles[i + 1] = angles[i] + k;
            offsets[i + 1] = offsets[i] + (float) Math.sin(angles[i]);
        }
        updateRate();
        updateRapids(near, far, firstIdx, dt);
        updateWeir(near, far, firstIdx, dt);
        updateTraffic(dt);
        if (inRapids || (rapSeg != null && rapBase - along < ROCK_FIRST && rapBase - along > 0)) {
            lateral += (rapidsTarget() - lateral) * Math.min(1f, dt * 1.4f);
            lateral += steering() * 7f * dt;
        } else if (hasSteering()) {
            lateral += steering() * 7f * dt;
        } else {
            lateral += (0f - lateral) * Math.min(1f, dt);
        }
        lateral = Math.max(-HALF_WIDTH * 0.7f, Math.min(HALF_WIDTH * 0.7f, lateral));
        float yaw = angles[25] * 0.6f;

        float horizon = h * 0.40f;
        float focal = h * 0.9f;
        // Heading of this stretch where it began: enough to slide the far hills as the river turns.
        float worldHeading = near.h0;

        // Sky, rebuilt only when its colours have moved: a shader per frame is garbage on the tablet.
        int key = (skyTop & 0xFFF8F8F8) * 31 + (skyBot & 0xFFF8F8F8);
        if (skyShader == null || key != skyShaderKey || skyShaderHorizon != horizon) {
            skyShader = new LinearGradient(0, 0, 0, horizon, skyTop, skyBot, Shader.TileMode.CLAMP);
            skyShaderKey = key;
            skyShaderHorizon = horizon;
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, horizon + 1, paint);
        paint.setShader(null);
        drawSunMoonStars(c, w, horizon);
        float hillShift = (float) ((worldHeading + yaw) * w * 0.8f);
        // 3.25: the clouds sit behind the hills, and the hills are three layers deep.
        drawSkyLife(c, w, horizon, hillShift);
        drawHills(c, w, horizon, hillShift);

        double forkDist = distanceToFork();
        char choice = choiceForNextFork();
        float fogReach = LOOK * (1f - 0.55f * mist);
        // The hard black line across the horizon: the strips start LOOK metres out, which is ~7 px
        // below the horizon, and the sky and hills stop 1 px below it, so the view's own black
        // background showed through. Fill that band with the farthest meadow colour - outside the
        // shake, so a jolt cannot open the gap again.
        paint.setColor(world(blend(season.grassA, season.grassB, 0.5f), 0.7f * Math.min(1f, LOOK / fogReach)));
        c.drawRect(0, horizon, w, horizon + dp(16f), paint);

        c.save();
        c.translate(shake.dx, shake.dy);
        drawGround(c, w, h, horizon, focal, yaw, forkDist, choice, fogReach);
        drawBankLife(c, w, horizon, focal, yaw, forkDist, fogReach);
        drawTrees(c, w, horizon, focal, yaw, fogReach);
        drawLandmarks(c, w, horizon, focal);
        // Far to near: the destination closes the river off at the top of the reach, and a weir can
        // stand between you and it.
        drawDestination(c, w, horizon, focal, yaw);
        drawWeir(c, w, horizon, focal, yaw);
        drawRocks(c, w, horizon, focal, yaw);
        drawTraffic(c, w, horizon, focal, yaw);
        drawRainRings(c, w, h, horizon);
        c.restore();

        drawSeasonAir(c, w, h, horizon);
        drawRain(c, w, h);
        drawBow(c, w, h, speed, dt);
        if (hitFlash > 0) {
            paint.setColor(((int) (hitFlash * 90) << 24) | 0x00F0655D);
            c.drawRect(0, 0, w, h, paint);
        }
        if (dir < 0) {
            // Running with the river: speed lines sell the free ride home.
            Fx.speedLines(c, paint, w, h, speed + current, sessionSeconds, dp(1f));
        }
        fx.draw(c);
        drawMinimap(c, w, h);
        drawHud(c, w, h, speed, forkDist, choice);
        drawExpeditionBar(c, w, h);
        drawRapidsPanel(c, w);
        drawWeirPanel(c, w, h);
        drawArrivalPanel(c, w, h);
        drawFlyIn(c, w, h);
        if (albumOpen) {
            drawAlbum(c, w, h);
        } else if (mapOpen) {
            drawBigMap(c, w, h);
        }
    }

    /* ---------- 3.25 scenery: hills, ground, water, banks, trees ---------- */

    /**
     * A vertical gradient kept in slot {@code slot} and rebuilt only when its inputs move. Colours
     * are quantised to 5 bits a channel first, so a sky easing through the day rebuilds a handful of
     * shaders a minute rather than one a frame. The arrays are copied by LinearGradient.
     */
    private LinearGradient grad(int slot, float y0, float y1, int[] cols, float[] pos) {
        long key = 0x9E3779B97F4A7C15L;
        key = mixKey(key, Float.floatToIntBits(y0));
        key = mixKey(key, Float.floatToIntBits(y1));
        for (int i = 0; i < cols.length; i++) {
            cols[i] &= 0xFFF8F8F8;
            key = mixKey(key, cols[i]);
            key = mixKey(key, Float.floatToIntBits(pos[i]));
        }
        if (grads[slot] == null || gradKeys[slot] != key) {
            grads[slot] = new LinearGradient(0, y0, 0, y1, cols, pos, Shader.TileMode.CLAMP);
            gradKeys[slot] = key;
        }
        return grads[slot];
    }

    private static long mixKey(long h, int v) {
        h ^= v & 0xFFFFFFFFL;
        h *= 0xff51afd7ed558ccdL;
        return h ^ (h >>> 33);
    }

    private void ensureStrips(int n) {
        if (stripY.length >= n) {
            return;
        }
        stripY = new float[n];
        stripTop = new float[n];
        stripCx = new float[n];
        stripHalf = new float[n];
        stripSpread = new float[n];
        stripFog = new float[n];
        stripFoam = new int[n];
    }

    /** Broken water: the rapids and the pour below a weir. Purely how it is drawn. */
    private boolean whiteAt(double s) {
        return (rapSeg != null && s > rapBase - 8 && s < rapBase + RAPIDS_LEN + 8)
                || (weirSeg != null && s > weirBase - 3 && s < weirBase + 26);
    }

    /**
     * The meadow on one bank ({@code side} -1 or +1 in world terms) at distance {@code s}: a
     * patchwork of fields 64 m long, each its own shade, some ripening toward the bank colour, with
     * a slow roll inside each so the ground recedes rather than reading as one flat card. Replaces
     * the 10 m light/dark bands, which read as stripes.
     */
    private int fieldColour(double s, int side, float fog) {
        long f = (long) Math.floor((s + (side > 0 ? 29.0 : 0.0)) / 64.0);
        long hsh = (f * 0x9E3779B97F4A7C15L) ^ (side > 0 ? 0x632BE59BD9B4E019L : 0x2545F4914F6CDD1DL);
        hsh *= 0xff51afd7ed558ccdL;
        hsh ^= hsh >>> 29;
        float shade = ((hsh >>> 40) & 0xFF) / 255f;
        int base = blend(season.grassA, season.grassB, shade);
        base = blend(base, 0xFF16300F, 0.05f + 0.16f * (((hsh >>> 24) & 0xFF) / 255f));
        if (((hsh >>> 16) & 3) == 0) {
            base = blend(base, season.bank, 0.26f);
        }
        float roll = (float) Math.sin(s * 0.11 + side * 1.7) * 0.5f + 0.5f;
        base = blend(base, 0xFF0E1E0A, roll * 0.07f);
        return world(base, fog * 0.7f);
    }

    private float chCentre(int j, int ch) {
        return stripCx[j] + ch * stripSpread[j];
    }

    private float chHalf(int j, int ch) {
        return ch == 0 ? stripHalf[j] : stripHalf[j] * 0.8f;
    }

    /**
     * A band of channel {@code ch} (0 main, -1/+1 the fork's branches) between strips from..to,
     * bounded by offsets {@code a} and {@code b} from its centre in units of its half-width: -1..1
     * is the water, 1..1.09 the right bank. Returns false when there is nothing to draw.
     */
    private boolean bandPath(Path p, int ch, int from, int to, float a, float b, float horizon) {
        p.rewind();
        if (to - from < 2) {
            return false;
        }
        for (int j = from; j < to; j++) {
            float x = chCentre(j, ch) + a * chHalf(j, ch);
            if (j == from) {
                p.moveTo(x, stripY[j]);
            } else {
                p.lineTo(x, stripY[j]);
            }
        }
        if (to == stripCount) {
            p.lineTo(chCentre(to - 1, ch) + a * chHalf(to - 1, ch), horizon + 1f);
            p.lineTo(chCentre(to - 1, ch) + b * chHalf(to - 1, ch), horizon + 1f);
        }
        for (int j = to - 1; j >= from; j--) {
            p.lineTo(chCentre(j, ch) + b * chHalf(j, ch), stripY[j]);
        }
        p.close();
        return true;
    }

    /** The line at offset {@code a} along channel {@code ch}, for stroking a bank's edge. */
    private boolean edgePath(Path p, int ch, int from, int to, float a, float horizon) {
        p.rewind();
        if (to - from < 2) {
            return false;
        }
        for (int j = from; j < to; j++) {
            float x = chCentre(j, ch) + a * chHalf(j, ch);
            if (j == from) {
                p.moveTo(x, stripY[j]);
            } else {
                p.lineTo(x, stripY[j]);
            }
        }
        if (to == stripCount) {
            p.lineTo(chCentre(to - 1, ch) + a * chHalf(to - 1, ch), horizon + 1f);
        }
        return true;
    }

    /**
     * Three ranges, far to near: blue mountains, the valley's own hills, and the far bank's woods
     * as a scalloped tree line. Each slides with the river's heading at its own rate, so the view
     * has parallax, and each fades into the haze at its foot.
     */
    private void drawHills(Canvas c, float w, float horizon, float hillShift) {
        for (int layer = 0; layer < 3; layer++) {
            float step = dp(layer == 0 ? 120f : layer == 1 ? 72f : 30f);
            float par = layer == 0 ? 0.3f : layer == 1 ? 0.55f : 0.85f;
            float maxH = dp(layer == 0 ? 92f : layer == 1 ? 52f : 18f);
            float minH = dp(layer == 0 ? 36f : layer == 1 ? 16f : 7f);
            int colour;
            float haze;
            if (layer == 0) {
                colour = blend(0xFF7890AE, skyBot, 0.3f);
                haze = 0.32f;
            } else if (layer == 1) {
                colour = blend(0xFF4E6B5C, season.grassB, 0.45f);
                haze = 0.16f;
            } else {
                colour = blend(blend(season.foliageA, season.foliageB, 0.5f), 0xFF14201A, 0.5f);
                haze = 0.08f;
            }
            int top = blend(lit(colour), fogColour, haze + mist * 0.5f);
            cols2[0] = top;
            cols2[1] = blend(top, fogColour, 0.42f + mist * 0.3f);
            paint.setColor(0xFFFFFFFF);
            paint.setShader(grad(G_HILL + layer, horizon - maxH, horizon, cols2, pos2));
            float period = step * 16;
            float shift = ((hillShift * par) % period + period) % period;
            int k = -(int) Math.ceil(shift / step) - 2;
            float px = k * step + shift;
            float py = horizon - hillHeight(k, layer, minH, maxH);
            path.rewind();
            path.moveTo(px, horizon + 1f);
            path.lineTo(px, py);
            while (px < w + step) {
                k++;
                float nx = k * step + shift;
                float ny = horizon - hillHeight(k, layer, minH, maxH);
                if (layer == 0) {
                    // Mountains keep their peaks, with a shoulder between each pair.
                    path.lineTo((px + nx) / 2f + step * 0.12f, Math.max(py, ny) + (horizon - Math.max(py, ny)) * 0.3f);
                    path.lineTo(nx, ny);
                } else if (layer == 1) {
                    path.quadTo(px, py, (px + nx) / 2f, (py + ny) / 2f);
                } else {
                    // A crown per tree.
                    path.quadTo((px + nx) / 2f, Math.min(py, ny) - step * 0.45f, nx, ny);
                }
                px = nx;
                py = ny;
            }
            path.lineTo(px, horizon + 1f);
            path.close();
            c.drawPath(path, paint);
            paint.setShader(null);
        }
    }

    private static float hillHeight(int k, int layer, float minH, float maxH) {
        int m = ((k % 16) + 16) % 16;
        long hsh = (m * 0x9E3779B97F4A7C15L + layer * 0x632BE59BD9B4E019L) * 0xff51afd7ed558ccdL;
        hsh ^= hsh >>> 31;
        return minH + (maxH - minH) * (((hsh >>> 40) & 0xFFFF) / 65535f);
    }

    /**
     * The ground, near to far, one strip per 3 px as before: each strip is a distance ahead and
     * paints the meadows. The river is then drawn over them as smooth polygons from the recorded
     * strips - water with the sky's reflection, the banks' reflections, ripples and glitter, then
     * the banks themselves - and the haze is laid along the horizon last.
     */
    private void drawGround(Canvas c, float w, float h, float horizon, float focal, float yaw,
                            double forkDist, char choice, float fogReach) {
        boolean fork = dir > 0 && forkDist < LOOK;
        int cap = (int) ((h - horizon) / dp(3f)) + 4;
        ensureStrips(cap);
        double t = sessionSeconds;
        int n = 0;
        int main = -1;
        for (float y = h; y > horizon + 2 && n < cap; y -= dp(3f)) {
            float z = CAM_H * focal / (y - horizon);
            if (z > LOOK) {
                break;
            }
            int zi = (int) z;
            float fog = Math.min(1f, z / fogReach);
            float cx = w / 2f + (offsets[zi] - lateral - yaw * z) * focal / z;
            float half = HALF_WIDTH * focal / z;
            double s = along + dir * z;
            float split = Math.max(0f, Math.min(w, cx));
            paint.setColor(fieldColour(s, -dir, fog));
            c.drawRect(0, y - dp(3f), split, y + 1, paint);
            paint.setColor(fieldColour(s, dir, fog));
            c.drawRect(split, y - dp(3f), w, y + 1, paint);
            boolean beyond = fork && z > forkDist;
            if (beyond && main < 0) {
                main = n;
            }
            stripY[n] = n == 0 ? h + 2f : y - dp(1f);
            stripTop[n] = y - dp(3f);
            stripCx[n] = cx;
            stripHalf[n] = half;
            stripSpread[n] = beyond ? (float) (z - forkDist) * FORK_ANGLE * focal / z : 0f;
            stripFog[n] = fog;
            int foam = 0;
            if (!beyond && whiteAt(s)) {
                // Broken water: pale, streaked with foam that races toward the boat.
                int band = (int) Math.floor(s * 0.45 + t * 2.2) % 3;
                foam = band == 0 ? 0xB8 : band == 1 ? 0x70 : 0x38;
            }
            stripFoam[n] = foam;
            n++;
        }
        stripCount = n;
        stripMain = main < 0 ? n : main;
        if (n < 2) {
            return;
        }
        int bFrom = Math.max(0, stripMain - 1);
        int channels = fork ? 3 : 1;

        // The water. Its edges are aliased on purpose - they sit under the banks' anti-aliased lips,
        // and an aliased fill is far cheaper for the GPU than an anti-aliased one this size.
        paint.setColor(0xFFFFFFFF);
        paint.setShader(waterShader(horizon, h, focal, fogReach));
        paint.setAntiAlias(false);
        for (int k = 0; k < channels; k++) {
            int ch = k == 0 ? 0 : k == 1 ? -1 : 1;
            if (bandPath(path, ch, k == 0 ? 0 : bFrom, k == 0 ? stripMain : n, -1f, 1f, horizon)) {
                c.drawPath(path, paint);
            }
        }
        paint.setShader(null);
        paint.setAntiAlias(true);
        if (fork) {
            // The branch not taken lies in shadow.
            paint.setColor(0x591A2A33);
            if (bandPath(path, choice == 'L' ? 1 : -1, bFrom, n, -1f, 1f, horizon)) {
                c.drawPath(path, paint);
            }
        }
        // Broken water, narrowed to each strip's top edge so it never steps past the bank.
        for (int j = 0; j < stripMain; j++) {
            if (stripFoam[j] == 0) {
                continue;
            }
            float top = stripTop[j];
            float bot = (j == 0 ? h : stripTop[j - 1]) + 1f;
            float ht = stripHalf[j] * (top - horizon) / Math.max(1f, top + dp(3f) - horizon);
            paint.setColor((stripFoam[j] << 24) | (world(0xFFE4F3F7, stripFog[j] * 0.6f) & 0x00FFFFFF));
            c.drawRect(stripCx[j] - ht, top, stripCx[j] + ht, bot, paint);
        }
        // The banks and their woods reflected along each edge of the water.
        int refl = blend(lit(blend(season.foliageA, season.foliageB, 0.5f)), 0xFF050C08, 0.62f);
        cols3[0] = 0x30000000 | (blend(refl, fogColour, 0.5f) & 0x00FFFFFF);
        cols3[1] = 0x60000000 | (refl & 0x00FFFFFF);
        cols3[2] = 0x80000000 | (refl & 0x00FFFFFF);
        pos3[0] = 0f;
        pos3[1] = 0.25f;
        pos3[2] = 1f;
        paint.setColor(0xFFFFFFFF);
        paint.setShader(grad(G_REFL, horizon, h, cols3, pos3));
        for (int k = 0; k < channels; k++) {
            int ch = k == 0 ? 0 : k == 1 ? -1 : 1;
            int from = k == 0 ? 0 : bFrom;
            int to = k == 0 ? stripMain : n;
            for (int side = -1; side <= 1; side += 2) {
                if (bandPath(path, ch, from, to, side * 1f, side * 0.86f, horizon)) {
                    c.drawPath(path, paint);
                }
            }
        }
        paint.setShader(null);

        drawWaterMarks(c, w, h, horizon, focal, yaw, forkDist, fork, fogReach);

        // The banks: earth fading into the distance, a dark wet line at the water and a dry lip
        // where they meet the meadow.
        int bank = lit(season.bank);
        cols3[0] = blend(bank, fogColour, Math.min(1f, LOOK / fogReach) * 0.7f);
        cols3[1] = blend(bank, fogColour, Math.min(1f, 50f / fogReach) * 0.7f);
        cols3[2] = blend(bank, 0xFF000000, 0.12f);
        pos3[0] = 0f;
        pos3[1] = Math.min(0.95f, CAM_H * focal / 50f / (h - horizon));
        pos3[2] = 1f;
        paint.setColor(0xFFFFFFFF);
        paint.setShader(grad(G_BANK, horizon, h, cols3, pos3));
        for (int k = 0; k < channels; k++) {
            int ch = k == 0 ? 0 : k == 1 ? -1 : 1;
            int from = k == 0 ? 0 : bFrom;
            int to = k == 0 ? stripMain : n;
            for (int side = -1; side <= 1; side += 2) {
                if (bandPath(path, ch, from, to, side * 1f, side * 1.09f, horizon)) {
                    c.drawPath(path, paint);
                }
            }
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        int wet = 0x99000000 | (blend(bank, 0xFF0A1014, 0.6f) & 0x00FFFFFF);
        int lip = 0x66000000 | (blend(bank, 0xFFFFF4DC, 0.3f) & 0x00FFFFFF);
        for (int k = 0; k < channels; k++) {
            int ch = k == 0 ? 0 : k == 1 ? -1 : 1;
            int from = k == 0 ? 0 : bFrom;
            int to = k == 0 ? stripMain : n;
            for (int side = -1; side <= 1; side += 2) {
                paint.setStrokeWidth(dp(2.2f));
                paint.setColor(wet);
                if (edgePath(path, ch, from, to, side * 1f, horizon)) {
                    c.drawPath(path, paint);
                }
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(lip);
                if (edgePath(path, ch, from, to, side * 1.09f, horizon)) {
                    c.drawPath(path, paint);
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);

        // Haze along the horizon, thicker in mist: it is what makes the far end of the valley far.
        int hazeA = ((int) Math.min(0xC0, 0x50 + 0x70 * mist)) & 0xF0;
        int fogRgb = fogColour & 0x00FFFFFF;
        cols3[0] = fogRgb;
        cols3[1] = (hazeA << 24) | fogRgb;
        cols3[2] = fogRgb;
        pos3[0] = 0f;
        pos3[1] = 0.6f;
        pos3[2] = 1f;
        float hazeTop = horizon - dp(48f);
        float hazeBot = horizon + dp(32f);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(grad(G_HAZE, hazeTop, hazeBot, cols3, pos3));
        c.drawRect(-dp(40f), hazeTop, w + dp(40f), hazeBot, paint);
        paint.setShader(null);
    }

    /**
     * The water's colour down the screen: near the horizon it is mostly sky, seen at a grazing
     * angle; near the boat you look down into it and it goes deep. Stops at 300, 60, 20 and 8 m.
     */
    private LinearGradient waterShader(float horizon, float h, float focal, float fogReach) {
        int wA = lit(season.waterA);
        int wB = lit(season.waterB);
        float span = Math.max(1f, h - horizon);
        cols5[0] = blend(blend(wA, skyBot, 0.62f), fogColour, Math.min(1f, LOOK / fogReach) * 0.6f);
        cols5[1] = blend(blend(wA, skyBot, 0.42f), fogColour, Math.min(1f, 60f / fogReach) * 0.6f);
        cols5[2] = blend(blend(wA, skyTop, 0.18f), fogColour, Math.min(1f, 20f / fogReach) * 0.6f);
        cols5[3] = blend(wA, wB, 0.5f);
        cols5[4] = blend(wB, 0xFF04101A, 0.3f);
        pos5[0] = 0f;
        pos5[1] = Math.min(0.9f, CAM_H * focal / 60f / span);
        pos5[2] = Math.min(0.93f, CAM_H * focal / 20f / span);
        pos5[3] = Math.min(0.96f, CAM_H * focal / 8f / span);
        pos5[4] = 1f;
        return grad(G_WATER, horizon, h, cols5, pos5);
    }

    /**
     * Ripples: short crests (sky-coloured) over troughs (deep water), scattered on rows every
     * {@link #RIPPLE_ROW} m of the water's own frame, so they shrink and crowd toward the horizon
     * and drift downstream at the season's current - a spring flood visibly runs. In broken water
     * they become longer white foam streaks that boil. Then the sun's glitter.
     */
    private void drawWaterMarks(Canvas c, float w, float h, float horizon, float focal, float yaw,
                                double forkDist, boolean fork, float fogReach) {
        double t = sessionSeconds;
        double ct = current * t;
        float zNear = CAM_H * focal / Math.max(1f, h - horizon);
        float zFar = 110f;
        double sA = along + dir * zNear;
        double sB = along + dir * zFar;
        long r0 = (long) Math.ceil((Math.min(sA, sB) + ct) / RIPPLE_ROW) - 1;
        long r1 = (long) Math.floor((Math.max(sA, sB) + ct) / RIPPLE_ROW);
        int crest = blend(skyBot, 0xFFFFFFFF, 0.3f) & 0x00FFFFFF;
        int trough = blend(lit(season.waterB), 0xFF02080E, 0.55f) & 0x00FFFFFF;
        int foamRgb = lit(0xFFF2FAFC) & 0x00FFFFFF;
        for (long r = r0; r <= r1; r++) {
            long hsh = r * 0x9E3779B97F4A7C15L;
            for (int k = 0; k < 4; k++) {
                hsh = hsh * 6364136223846793005L + 1442695040888963407L;
                double s = r * RIPPLE_ROW - ct + (((hsh >>> 52) & 0xFF) / 255f) * RIPPLE_ROW;
                float z = (float) ((s - along) * dir);
                if (z < zNear || z > zFar || (fork && z > forkDist)) {
                    continue;
                }
                boolean white = whiteAt(s);
                if (!white && k == 3) {
                    continue;
                }
                int zi = (int) z;
                float u = focal / z;
                float y = horizon + CAM_H * u;
                float fade = Math.min(1f, (zFar - z) / 35f) * (1f - 0.75f * Math.min(1f, z / fogReach));
                float lane = ((((hsh >>> 33) & 0xFFFF) / 65535f) * 1.8f - 0.9f) * HALF_WIDTH;
                float len = 0.7f + (((hsh >>> 20) & 0xFF) / 255f) * 1.6f;
                float wob = (float) Math.sin(t * (white ? 4.0 : 1.3) + r * 0.7 + k * 2.3) * (white ? 0.6f : 0.25f);
                float x = w / 2f + (offsets[zi] + lane + wob - lateral - yaw * z) * u;
                float hl = len * 0.5f * u;
                if (white) {
                    paint.setStrokeWidth(Math.max(1.5f, 0.08f * u));
                    paint.setColor(((int) (0xC8 * fade) << 24) | foamRgb);
                    c.drawLine(x - hl * 1.3f, y, x + hl * 1.3f, y, paint);
                } else {
                    float th = Math.max(1f, 0.045f * u);
                    paint.setStrokeWidth(th);
                    paint.setColor(((int) (0x60 * fade) << 24) | crest);
                    c.drawLine(x - hl, y, x + hl, y, paint);
                    paint.setColor(((int) (0x48 * fade) << 24) | trough);
                    c.drawLine(x - hl * 0.75f, y + th, x + hl * 0.75f, y + th, paint);
                }
            }
        }
        drawGlitter(c, w, h, horizon, focal, yaw, forkDist, fork, zNear);
    }

    /** The sun's (or moon's) path of glitter on the water, under it and widening toward the boat. */
    private void drawGlitter(Canvas c, float w, float h, float horizon, float focal, float yaw,
                             double forkDist, boolean fork, float zNear) {
        float a = glintA * (1f - cloud * 0.85f) * (1f - rain * 0.7f);
        if (a < 0.04f) {
            return;
        }
        double t = sessionSeconds;
        int rgb = glintCol & 0x00FFFFFF;
        for (int i = 0; i < 40; i++) {
            double cyc = t * 1.6 + i * 0.618;
            long cycle = (long) cyc;
            float ph = (float) (cyc - cycle);
            long hsh = ((i + 1) * 0x9E3779B97F4A7C15L + cycle * 0xBF58476D1CE4E5B9L) * 0x94D049BB133111EBL;
            hsh ^= hsh >>> 31;
            float r1 = ((hsh >>> 40) & 0xFFFF) / 65535f;
            float r2 = ((hsh >>> 20) & 0xFFFF) / 65535f;
            float y = horizon + dp(3f) + r1 * r1 * (h - horizon - dp(3f));
            float z = CAM_H * focal / (y - horizon);
            if (z > LOOK || z < zNear || (fork && z > forkDist)) {
                continue;
            }
            int zi = (int) z;
            float u = focal / z;
            float cx = w / 2f + (offsets[zi] - lateral - yaw * z) * u;
            float spread = dp(18f) + (y - horizon) * 0.55f;
            float x = glintX + (r2 - 0.5f) * 2f * spread;
            if (Math.abs(x - cx) > HALF_WIDTH * u * 0.9f) {
                continue;
            }
            float tw = (float) Math.sin(Math.PI * ph);
            float len = Math.min(dp(46f), dp(3f) + u * 0.6f) * (0.5f + tw * 0.5f);
            paint.setStrokeWidth(Math.max(1.2f, Math.min(dp(3.5f), u * 0.04f)));
            paint.setColor(((int) (235 * a * tw) << 24) | rgb);
            c.drawLine(x - len, y, x + len, y, paint);
        }
    }

    /**
     * Reeds at the water's edge, with bulrush heads, and tufts and flowers up the meadow near the
     * boat. Every 5 m of bank, from its own hash. Nothing is drawn on the fork's island.
     */
    private void drawBankLife(Canvas c, float w, float horizon, float focal, float yaw,
                              double forkDist, float fogReach) {
        boolean fork = dir > 0 && forkDist < LOOK;
        float step = 5f;
        double sNear = along + dir * 3.5;
        double sFar = along + dir * 90.0;
        long a = (long) Math.ceil(Math.min(sNear, sFar) / step);
        long b = (long) Math.floor(Math.max(sNear, sFar) / step);
        boolean winter = season.index == RiverExplorerSeason.WINTER;
        int reed = winter ? 0xFFB8A27A : blend(season.grassB, 0xFF4E5A22, 0.45f);
        int head = winter ? 0xFF8A7650 : 0xFF6B4A2B;
        int tuft = blend(season.grassB, 0xFF13260F, 0.35f);
        int flower = season.index == RiverExplorerSeason.SPRING ? 0xFFFFE3EF
                : season.index == RiverExplorerSeason.SUMMER ? 0xFFF5D547 : 0;
        double t = sessionSeconds;
        for (long n = dir > 0 ? b : a; dir > 0 ? n >= a : n <= b; n -= dir) {
            float z = (float) ((n * step - along) * dir);
            if (z < 3.5f || z > 90f || (fork && z > forkDist - 4f)) {
                continue;
            }
            int zi = (int) z;
            float u = focal / z;
            float gy = horizon + CAM_H * u;
            float fog = Math.min(1f, z / fogReach) * 0.7f;
            long hsh = (n * 0xD1B54A32D192ED03L) ^ 0x2545F4914F6CDD1DL;
            hsh *= 0xff51afd7ed558ccdL;
            hsh ^= hsh >>> 29;
            float centre = offsets[zi] - lateral - yaw * z;
            for (int side = -1; side <= 1; side += 2) {
                long hs = hsh >>> (side < 0 ? 0 : 20);
                if ((hs & 3) != 0) {
                    float out = HALF_WIDTH + 0.5f + ((hs >>> 3) & 7) * 0.12f;
                    float bx = w / 2f + (centre + side * out) * u;
                    float rh = (1.1f + ((hs >>> 6) & 7) * 0.08f) * u;
                    float sway = (float) Math.sin(t * 1.4 + n * 0.9 + side) * 0.12f * u * (1f + rain);
                    paint.setStrokeWidth(Math.max(1f, 0.05f * u));
                    paint.setColor(world(reed, fog));
                    for (int k = -2; k <= 2; k++) {
                        float tip = bx + k * 0.24f * u + sway * (1f + Math.abs(k) * 0.2f);
                        c.drawLine(bx + k * 0.05f * u, gy, tip, gy - rh * (1f - Math.abs(k) * 0.14f), paint);
                    }
                    if ((hs & 4) != 0) {
                        paint.setStrokeWidth(Math.max(1.5f, 0.11f * u));
                        paint.setColor(world(head, fog));
                        c.drawLine(bx + sway, gy - rh * 1.02f, bx + sway, gy - rh * 0.8f, paint);
                    }
                }
                if (z < 45f) {
                    float tout = HALF_WIDTH + 2.5f + ((hs >>> 9) & 31) * 0.5f;
                    float tx = w / 2f + (centre + side * tout) * u;
                    float th = 0.35f * u;
                    paint.setStrokeWidth(Math.max(1f, 0.04f * u));
                    paint.setColor(world(tuft, fog));
                    c.drawLine(tx, gy, tx - 0.15f * u, gy - th, paint);
                    c.drawLine(tx, gy, tx + 0.02f * u, gy - th * 1.2f, paint);
                    c.drawLine(tx, gy, tx + 0.16f * u, gy - th * 0.9f, paint);
                    if (flower != 0 && ((hs >>> 14) & 1) != 0) {
                        paint.setColor(world(flower, fog));
                        c.drawCircle(tx + 0.02f * u, gy - th * 1.25f, Math.max(1.2f, 0.07f * u), paint);
                    }
                }
            }
        }
    }

    /**
     * Trees along both banks, far to near. The run of tree numbers is taken in the direction of
     * travel, so the cruise home sees the same trees. 3.25: a second, deeper row of woodland stands
     * behind the first, from its own hash, so the original row is exactly where it always was.
     */
    private void drawTrees(Canvas c, float w, float horizon, float focal, float yaw, float fogReach) {
        long treeA = (long) Math.ceil((along + dir * 3.0) / TREE_STEP);
        long treeB = (long) Math.floor((along + dir * (LOOK - 1.0)) / TREE_STEP);
        long firstTree = Math.min(treeA, treeB);
        long lastTree = Math.max(treeA, treeB);
        for (long n = dir > 0 ? lastTree : firstTree; dir > 0 ? n >= firstTree : n <= lastTree; n -= dir) {
            float z = (float) ((n * TREE_STEP - along) * dir);
            if (z < 3f || z > LOOK) {
                continue;
            }
            int zi = Math.max(0, Math.min(LOOK, (int) z));
            float sy = horizon + CAM_H * focal / z;
            float fog = Math.min(1f, z / fogReach);
            long back = (n * 0x9E3779B97F4A7C15L) ^ 0x632BE59BD9B4E019L;
            back *= 0xff51afd7ed558ccdL;
            back ^= back >>> 31;
            if (((back >>> 9) & 3) != 0) {
                boolean bl = ((back >>> 7) & 1) == 0;
                float bout = HALF_WIDTH + 22f + ((back >>> 12) & 31);
                float bxw = offsets[zi] + dir * (bl ? -bout : bout);
                float bsx = w / 2f + (bxw - lateral - yaw * z) * focal / z;
                drawTree(c, bsx, sy, (9.5f + ((back >>> 40) & 7) * 0.6f) * focal / z, back, Math.min(1f, fog * 1.1f));
            }
            long hsh = n * 2654435761L;
            boolean left = ((hsh >>> 7) & 1) == 0;
            float out = HALF_WIDTH + 5f + ((hsh >>> 12) & 15);
            float xw = offsets[zi] + dir * (left ? -out : out);
            float sx = w / 2f + (xw - lateral - yaw * z) * focal / z;
            drawTree(c, sx, sy, 9f * focal / z, hsh, fog);
        }
    }

    /**
     * One tree standing at (sx, sy), {@code size} px tall: a broadleaf with a lobed crown, a
     * two-tone conifer or a poplar, shaded away from the light and with a soft contact shadow. In
     * winter the broadleaves are bare and snowed on. Far trees drop to a trunk and one crown.
     */
    private void drawTree(Canvas c, float sx, float sy, float size, long hsh, float fog) {
        long m = hsh * 0xff51afd7ed558ccdL;
        m ^= m >>> 32;
        int kind = (int) ((m >>> 8) & 0xFFFF) % 6; // 0-2 broadleaf, 3-4 conifer, 5 poplar
        // Winter strips the broadleaves and poplars; the conifers stay green under snow.
        boolean bare = season.bareness > 0.6f && (kind < 3 || kind == 5);
        if (bare) {
            kind = 0;
        }
        float ls = lightSide;
        float f = fog * 0.7f;
        float wide = 0.9f + ((m >>> 28) & 15) / 15f * 0.3f;
        float sway = (float) Math.sin(sessionSeconds * (0.8 + (m & 7) * 0.05) + (m & 63)) * size * (0.012f + rain * 0.02f);
        int leaf = ((hsh >>> 20) & 1) == 0 ? season.foliageA : season.foliageB;
        if (kind >= 3) {
            leaf = blend(0xFF2C5233, season.foliageA, season.index == RiverExplorerSeason.WINTER ? 0f : 0.15f);
        }
        int shA = (int) (0x40 * litLevel * (1f - fog));
        if (shA > 4) {
            paint.setColor(shA << 24);
            c.drawOval(sx - size * 0.30f - ls * size * 0.14f, sy - size * 0.045f,
                    sx + size * 0.30f - ls * size * 0.14f, sy + size * 0.035f, paint);
        }
        int bark = world(0xFF5A3E2B, f);
        if (size < dp(22f)) {
            paint.setColor(bark);
            c.drawRect(sx - size * 0.05f, sy - size * 0.45f, sx + size * 0.05f, sy, paint);
            paint.setColor(world(bare ? season.foliageB : leaf, f));
            if (kind >= 3 && kind <= 4) {
                path2.rewind();
                path2.moveTo(sx - size * 0.24f, sy - size * 0.2f);
                path2.lineTo(sx, sy - size * 1.0f);
                path2.lineTo(sx + size * 0.24f, sy - size * 0.2f);
                path2.close();
                c.drawPath(path2, paint);
            } else {
                c.drawCircle(sx, sy - size * 0.62f, size * (bare ? 0.22f : 0.32f) * wide, paint);
            }
            return;
        }
        int barkDark = world(0xFF2E2016, f);
        int shade = world(blend(leaf, 0xFF0B140C, 0.4f), f);
        int main = world(leaf, f);
        int high = world(blend(leaf, 0xFFFFF0B8, 0.3f), f);
        float lsSign = ls >= 0f ? 1f : -1f;
        if (kind >= 3 && kind <= 4) {
            // Conifer: three tiers, the shadowed side dark and the lit side bright.
            paint.setColor(bark);
            c.drawRect(sx - size * 0.04f, sy - size * 0.22f, sx + size * 0.04f, sy, paint);
            path2.rewind();
            for (int k = 0; k < 3; k++) {
                float base = sy - (0.16f + 0.25f * k) * size;
                float hw = (0.27f - 0.065f * k) * size * wide;
                float ax = sx + sway * (k + 1) * 0.6f;
                path2.moveTo(sx - hw, base);
                path2.lineTo(ax, base - 0.44f * size);
                path2.lineTo(sx + hw, base);
                path2.close();
            }
            paint.setColor(shade);
            c.drawPath(path2, paint);
            path2.rewind();
            for (int k = 0; k < 3; k++) {
                float base = sy - (0.16f + 0.25f * k) * size;
                float hw = (0.27f - 0.065f * k) * size * wide;
                float ax = sx + sway * (k + 1) * 0.6f;
                path2.moveTo(ax, base - 0.44f * size);
                path2.lineTo(sx + lsSign * hw, base);
                path2.lineTo(sx, base + 0.02f * size);
                path2.close();
            }
            paint.setColor(main);
            c.drawPath(path2, paint);
            if (season.index == RiverExplorerSeason.WINTER) {
                paint.setColor(world(0xFFF2F6FA, f));
                for (int k = 0; k < 3; k++) {
                    float base = sy - (0.16f + 0.25f * k) * size;
                    float ax = sx + sway * (k + 1) * 0.6f;
                    c.drawOval(ax - size * 0.07f, base - 0.44f * size, ax + size * 0.07f, base - 0.34f * size, paint);
                }
            }
            return;
        }
        if (kind == 5) {
            // Poplar: a tall flame of a crown.
            paint.setColor(bark);
            c.drawRect(sx - size * 0.035f, sy - size * 0.3f, sx + size * 0.035f, sy, paint);
            paint.setColor(shade);
            c.drawOval(sx - size * 0.15f * wide + sway, sy - size * 1.12f, sx + size * 0.15f * wide + sway, sy - size * 0.18f, paint);
            paint.setColor(main);
            c.drawOval(sx - size * 0.12f * wide + sway + ls * size * 0.025f, sy - size * 1.1f,
                    sx + size * 0.1f * wide + sway + ls * size * 0.025f, sy - size * 0.26f, paint);
            paint.setColor(high);
            c.drawOval(sx + sway + ls * size * 0.05f - size * 0.04f, sy - size * 1.0f,
                    sx + sway + ls * size * 0.05f + size * 0.04f, sy - size * 0.55f, paint);
            return;
        }
        // Broadleaf: a trunk lit on one side, then a crown of lobes.
        paint.setColor(bark);
        c.drawRect(sx - size * 0.055f, sy - size * 0.56f, sx + size * 0.055f, sy, paint);
        paint.setColor(barkDark);
        if (lsSign > 0) {
            c.drawRect(sx - size * 0.055f, sy - size * 0.56f, sx - size * 0.01f, sy, paint);
        } else {
            c.drawRect(sx + size * 0.01f, sy - size * 0.56f, sx + size * 0.055f, sy, paint);
        }
        float cy = sy - size * 0.64f;
        if (bare) {
            paint.setStrokeWidth(Math.max(1f, size * 0.035f));
            paint.setColor(world(season.foliageB, f));
            for (int b = -2; b <= 2; b++) {
                float bx = sx + b * size * 0.13f * wide + sway;
                float by = sy - size * (0.86f - Math.abs(b) * 0.08f);
                c.drawLine(sx, sy - size * 0.42f, bx, by, paint);
                c.drawLine(bx, by, bx + b * size * 0.05f - size * 0.04f, by - size * 0.12f, paint);
            }
            paint.setColor(world(0xFFF2F6FA, f));
            c.drawOval(sx - size * 0.1f, sy - size * 0.47f, sx + size * 0.1f, sy - size * 0.41f, paint);
            c.drawCircle(sx - size * 0.13f * wide + sway, sy - size * 0.79f, size * 0.035f, paint);
            c.drawCircle(sx + size * 0.13f * wide + sway, sy - size * 0.79f, size * 0.035f, paint);
            return;
        }
        float r = size * wide;
        paint.setColor(shade);
        c.drawCircle(sx + sway - ls * 0.03f * size, cy + 0.03f * size, 0.33f * r, paint);
        c.drawCircle(sx - 0.2f * r + sway * 0.6f, cy + 0.1f * size, 0.22f * r, paint);
        c.drawCircle(sx + 0.2f * r + sway * 0.6f, cy + 0.09f * size, 0.23f * r, paint);
        paint.setColor(main);
        c.drawCircle(sx + sway + ls * 0.02f * size, cy - 0.03f * size, 0.29f * r, paint);
        c.drawCircle(sx - 0.17f * r + sway * 0.6f + ls * 0.02f * size, cy + 0.05f * size, 0.18f * r, paint);
        c.drawCircle(sx + 0.17f * r + sway * 0.6f + ls * 0.02f * size, cy + 0.04f * size, 0.19f * r, paint);
        paint.setColor(high);
        c.drawCircle(sx + sway + ls * 0.11f * size, cy - 0.13f * size, 0.14f * r, paint);
        c.drawCircle(sx + sway * 0.6f + ls * 0.2f * r, cy + 0.0f * size, 0.08f * r, paint);
    }

    /** The sun's arc through the day, the moon and stars at night. */
    private void drawSunMoonStars(Canvas c, float w, float horizon) {
        double t = sessionSeconds;
        float dark = 1f - light;
        glintA = 0f;
        // Stars, dimmed by cloud.
        float starA = Math.max(0f, Math.min(1f, (dark - 0.4f) / 0.35f)) * (1f - cloud * 0.9f);
        if (starA > 0.02f) {
            for (int i = 0; i < 50; i++) {
                long hsh = (i + 1) * 2654435761L;
                float sx = ((hsh >>> 8) & 0xFFFF) / 65535f * w;
                float sy = ((hsh >>> 24) & 0xFFFF) / 65535f * horizon * 0.85f;
                float tw = 0.6f + 0.4f * (float) Math.sin(t * 2 + i * 1.7);
                paint.setColor(((int) (starA * tw * 230) << 24) | 0x00FFFFFF);
                c.drawCircle(sx, sy, dp(i % 7 == 0 ? 1.8f : 1.1f), paint);
            }
        }
        float mm = dayMinute >= 50f ? dayMinute - DAY_MINUTES : dayMinute;
        float e = (float) Math.sin(Math.PI * (mm + 4f) / 48f);
        if (mm > -4f && mm < 44f && e > 0f) {
            float sx = w * (0.1f + 0.8f * (mm + 4f) / 48f);
            float sy = horizon - e * horizon * 0.8f + dp(8f);
            int warm = blend(0xFFFFF4D0, 0xFFFF9A50, 1f - Math.min(1f, e * 2.5f));
            float a = 1f - cloud * 0.75f;
            // Colour quantised so the cached glow is reused as it warms toward evening. 3.25: a wide
            // faint halo first, so the sky round the sun is lit and not just the disc.
            Fx.glow(c, sx, sy, dp(210f), ((int) (a * 0x30) << 24) | (warm & 0x00F0F0F0));
            Fx.glow(c, sx, sy, dp(90f), ((int) (a * 0x66) << 24) | (warm & 0x00F0F0F0));
            // The glitter on the water is strongest with the sun low.
            glintX = sx;
            glintA = a * Math.min(1f, 0.55f + (1f - e) * 0.6f);
            glintCol = blend(0xFFFFF8E0, warm, 0.6f);
            paint.setColor(((int) (a * 255) << 24) | (warm & 0x00FFFFFF));
            c.drawCircle(sx, sy, dp(18f), paint);
        }
        if (dayMinute > 43f && dayMinute < 58f) {
            float p = (dayMinute - 43f) / 15f;
            float mx = w * (0.2f + 0.6f * p);
            float my = horizon * (0.55f - 0.35f * (float) Math.sin(Math.PI * p));
            float a = Math.min(1f, (float) Math.sin(Math.PI * p) * 2f) * (1f - cloud * 0.7f);
            Fx.glow(c, mx, my, dp(60f), ((int) (a * 0x44) << 24) | 0x00D0E0F0);
            paint.setColor(((int) (a * 255) << 24) | 0x00EEF2F8);
            c.drawCircle(mx, my, dp(14f), paint);
            paint.setColor(skyTop);
            c.drawCircle(mx + dp(6f), my - dp(3f), dp(12f), paint);
            if (a * 0.6f > glintA) {
                glintX = mx;
                glintA = a * 0.6f;
                glintCol = 0xFFDDE6F2;
            }
        }
        lightSide = glintA > 0.05f ? Math.max(-1f, Math.min(1f, (glintX - w / 2f) / (w * 0.3f))) : -0.6f;
    }

    /**
     * 3.19.5: a still sky read as a painting. Clouds drift and slide with the river's heading, a
     * flock crosses, and a hot-air balloon hangs over the far hills. 3.20: the cloud thickens with
     * the weather, and the balloon and birds go home at night and in the rain.
     */
    private void drawSkyLife(Canvas c, float w, float horizon, float hillShift) {
        double t = sessionSeconds;
        float span = w + dp(500f);
        int clouds = 5 + Math.round(cloud * 5f);
        int cloudColour = lit(blend(0xFFFFFFFF, 0xFF7C8591, cloud * 0.7f));
        int cloudShade = blend(cloudColour, skyTop, 0.4f);
        int cloudRim = 0x70000000 | (blend(cloudColour, glintCol, 0.35f) & 0x00FFFFFF);
        for (int i = 0; i < clouds; i++) {
            float cx = (float) ((((i * 523 + hillShift * 0.25f + t * dp(5f + i + rain * 8f)) % span) + span) % span) - dp(250f);
            float cy = horizon * (0.18f + (i % 3) * 0.17f) - (i >= 5 ? dp(20f) : 0f);
            float sc = (0.6f + (i % 3) * 0.3f) * (1f + cloud * 0.5f);
            // 3.25: a cumulus rather than two flat ovals - lit puffs over a flat base in the shadow
            // colour of the sky, and a warm rim on the side toward the sun.
            paint.setColor(cloudColour);
            c.drawCircle(cx - dp(40f) * sc, cy - dp(4f) * sc, dp(22f) * sc, paint);
            c.drawCircle(cx + dp(2f) * sc, cy - dp(14f) * sc, dp(30f) * sc, paint);
            c.drawCircle(cx + dp(42f) * sc, cy - dp(2f) * sc, dp(19f) * sc, paint);
            c.drawOval(cx - dp(84f) * sc, cy - dp(10f) * sc, cx + dp(84f) * sc, cy + dp(10f) * sc, paint);
            paint.setColor(cloudShade);
            c.drawOval(cx - dp(76f) * sc, cy + dp(1f) * sc, cx + dp(76f) * sc, cy + dp(11f) * sc, paint);
            paint.setColor(cloudRim);
            c.drawCircle(cx + dp(2f) * sc + lightSide * dp(9f) * sc, cy - dp(21f) * sc, dp(17f) * sc, paint);
        }
        if (rain > 0.3f || light < 0.5f) {
            return;
        }
        float bx = (float) ((((w * 0.3f + hillShift * 0.3f + t * dp(4f)) % span) + span) % span) - dp(250f);
        float by = horizon * 0.42f + (float) Math.sin(t * 0.4) * dp(8f);
        paint.setColor(lit(0xFFB83A26));
        c.drawOval(bx - dp(16f), by - dp(20f), bx + dp(16f), by + dp(16f), paint);
        paint.setColor(lit(0xFFE8573C));
        c.drawOval(bx - dp(14f) + lightSide * dp(2f), by - dp(19f), bx + dp(12f) + lightSide * dp(2f), by + dp(13f), paint);
        paint.setColor(lit(0xFFF5C518));
        c.drawOval(bx - dp(5f), by - dp(20f), bx + dp(5f), by + dp(16f), paint);
        paint.setColor(0x55FFFFFF);
        c.drawOval(bx + lightSide * dp(7f) - dp(3f), by - dp(15f), bx + lightSide * dp(7f) + dp(3f), by - dp(4f), paint);
        paint.setColor(lit(0xFF6B4A2B));
        c.drawRect(bx - dp(4f), by + dp(22f), bx + dp(4f), by + dp(28f), paint);
        paint.setStrokeWidth(dp(1f));
        c.drawLine(bx - dp(10f), by + dp(12f), bx - dp(4f), by + dp(22f), paint);
        c.drawLine(bx + dp(10f), by + dp(12f), bx + dp(4f), by + dp(22f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xAA2A3340);
        float fx = (float) (w - ((t * dp(35f)) % (w + dp(300f))));
        for (int b = 0; b < 5; b++) {
            float x0 = fx + b * dp(24f);
            float y0 = horizon * 0.30f + (b % 2) * dp(10f);
            float flap = (float) Math.sin(t * 8 + b) * dp(4f);
            c.drawLine(x0 - dp(7f), y0 - flap, x0, y0, paint);
            c.drawLine(x0, y0, x0 + dp(7f), y0 - flap, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Falling rain in screen space, slanting with the boat's speed. */
    private void drawRain(Canvas c, float w, float h) {
        if (rain < 0.05f) {
            return;
        }
        double t = sessionSeconds;
        int drops = (int) (90 * rain);
        float slant = dp(6f) + boat.value() * dp(3f);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(((int) (rain * 120) << 24) | 0x00D8E4F0);
        for (int i = 0; i < drops; i++) {
            long hsh = (i + 7) * 2654435761L;
            float x = ((hsh >>> 8) & 0xFFFF) / 65535f * (w + dp(60f));
            float speedY = dp(900f + ((hsh >>> 30) & 255));
            float y = (float) ((((hsh >>> 16) & 0xFFFF) + t * speedY) % (h + dp(40f))) - dp(20f);
            c.drawLine(x, y, x - slant, y + dp(22f), paint);
        }
    }

    /** Rain rings spreading on the river. */
    private void drawRainRings(Canvas c, float w, float h, float horizon) {
        if (rain < 0.1f) {
            return;
        }
        double t = sessionSeconds;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        for (int i = 0; i < 16; i++) {
            long hsh = (i + 3) * 40503L * 2654435761L;
            float life = (float) ((t * 1.3 + i * 0.37) % 1.0);
            long cycle = (long) (t * 1.3 + i * 0.37);
            long hc = (hsh + cycle * 0x9E3779B9L) * 2654435761L;
            float x = w * (0.28f + 0.44f * (((hc >>> 8) & 0xFFFF) / 65535f));
            float depth = ((hc >>> 24) & 0xFFFF) / 65535f;
            float y = horizon + dp(30f) + depth * (h * 0.78f - horizon - dp(30f));
            float r = dp(4f + 16f * life) * (0.4f + depth);
            paint.setColor(((int) ((1f - life) * rain * 150) << 24) | 0x00D8ECF2);
            c.drawOval(x - r, y - r * 0.3f, x + r, y + r * 0.3f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLandmarks(Canvas c, float w, float horizon, float focal) {
        for (int k = 1; k >= 0; k--) {
            int idx = segIndex(along) + dir * k;
            if (idx < 0) {
                continue;
            }
            Seg seg = geom(prefixFor(idx), idx);
            if (seg.landmark < 0) {
                continue;
            }
            float z = (float) ((idx * SEG_LEN + seg.landmarkAt - along) * dir);
            if (z > LOOK || z < 0) {
                continue;
            }
            // Left and right swap over when the boat is pointed the other way.
            float side = (seg.landmarkLeft ? -1f : 1f) * dir;
            if (seg.landmark == 0) {
                drawHeronLandmark(c, w, horizon, focal, seg, z, side);
                continue;
            }
            if (seg.landmark == 5) {
                drawOtterLandmark(c, w, horizon, focal, seg, z, side);
                continue;
            }
            if (z < 6f) {
                continue;
            }
            int zi = (int) z;
            float baseX = w / 2f + (offsets[zi] - lateral - (angles[25] * 0.6f) * z) * focal / z;
            float sy = horizon + CAM_H * focal / z;
            float u = focal / z;   // pixels per metre at that distance
            float bank = baseX + side * (HALF_WIDTH + 4f) * u;
            drawLandmarkShape(c, seg.landmark, bank, baseX, sy, u, side, (HALF_WIDTH + 3f) * u);
        }
    }

    /** The heron stands fishing until the boat is within 55 m, then lifts off and flies up the river. */
    private void drawHeronLandmark(Canvas c, float w, float horizon, float focal, Seg seg, float z, float side) {
        if (heronSeg != seg && z < 55f && z > 0f) {
            heronSeg = seg;
            heronStart = sessionSeconds;
            heronSplashed = false;
        }
        boolean flying = heronSeg == seg;
        float ft = flying ? (float) (sessionSeconds - heronStart) : 0f;
        if (flying && ft > 9f) {
            return;
        }
        float zf = z + (flying ? 6f * ft + 0.3f * ft * ft : 0f);
        if (zf < 4f || zf > LOOK) {
            return;
        }
        int zi = (int) zf;
        float u = focal / zf;
        float out = HALF_WIDTH + 4f + (flying ? 1.5f * ft : 0f);
        float x = w / 2f + (offsets[zi] + side * out - lateral - (angles[25] * 0.6f) * zf) * u;
        float ground = horizon + CAM_H * u;
        if (!flying) {
            // Head dips to strike at a fish every few seconds.
            float strike = (float) Math.max(0, Math.sin(sessionSeconds * 1.3) - 0.85) * 6f;
            drawHeron(c, x, ground, u, 0f, false, strike);
        } else {
            float lift = Math.min(1f, ft / 0.6f);
            float height = 1.2f * lift + 2.8f * ft;
            float flap = (float) Math.sin(ft * (ft < 1f ? 16 : 9));
            drawHeron(c, x, ground - height * u, u, flap, true, 0f);
            if (!heronSplashed) {
                // Once per take-off: at 60 fps a time window fired this on several frames.
                heronSplashed = true;
                fx.burst(x, ground, 10, dp(90f), 0.5f, dp(2.5f), 0xCCBFE3FF, true);
            }
        }
    }

    private void drawHeron(Canvas c, float x, float y, float u, float flap, boolean flying, float strike) {
        int body = lit(0xFFDDE3E8);
        int dark = lit(0xFF7C8894);
        int beak = lit(0xFFE8B83C);
        paint.setStrokeWidth(Math.max(1f, 0.08f * u));
        if (!flying) {
            paint.setColor(dark);
            c.drawLine(x - 0.1f * u, y - 0.8f * u, x - 0.1f * u, y, paint);
            c.drawLine(x + 0.1f * u, y - 0.8f * u, x + 0.15f * u, y, paint);
            paint.setColor(body);
            c.drawOval(x - 0.6f * u, y - 1.6f * u, x + 0.6f * u, y - 0.8f * u, paint);
            float hx = x + (0.3f + strike * 0.12f) * u;
            float hy = y - (2.4f - strike * 0.25f) * u;
            c.drawLine(x + 0.2f * u, y - 1.4f * u, hx, hy, paint);
            c.drawCircle(hx, hy, 0.15f * u, paint);
            paint.setColor(beak);
            c.drawLine(hx, hy, hx + 0.45f * u, hy + 0.1f * u + strike * 0.05f * u, paint);
            return;
        }
        // In flight, side on: long body, neck tucked, legs trailing, great wings beating.
        paint.setColor(dark);
        c.drawLine(x - 0.6f * u, y, x - 1.5f * u, y + 0.15f * u, paint);
        paint.setColor(lit(0xFFA9B4BE));
        path.reset();
        path.moveTo(x - 0.35f * u, y);
        path.lineTo(x + 0.35f * u, y);
        path.lineTo(x - 0.1f * u, y - 1.7f * u * flap);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(body);
        c.drawOval(x - 0.75f * u, y - 0.22f * u, x + 0.65f * u, y + 0.22f * u, paint);
        c.drawCircle(x + 0.85f * u, y - 0.2f * u, 0.16f * u, paint);
        paint.setColor(beak);
        c.drawLine(x + 0.95f * u, y - 0.2f * u, x + 1.4f * u, y - 0.12f * u, paint);
        paint.setColor(lit(0xFFC9D2DA));
        path.reset();
        path.moveTo(x - 0.3f * u, y - 0.05f * u);
        path.lineTo(x + 0.4f * u, y - 0.05f * u);
        path.lineTo(x + 0.1f * u, y - 1.9f * u * flap - 0.2f * u);
        path.close();
        c.drawPath(path, paint);
    }

    /**
     * Three otters by the bank: one floats on its back with a fish, the others dive and surface in
     * turn. When the boat is within 30 m they all duck under at once and leave rings.
     */
    private void drawOtterLandmark(Canvas c, float w, float horizon, float focal, Seg seg, float z, float side) {
        if (z < 4f) {
            return;
        }
        if (otterSeg != seg && z < 30f) {
            otterSeg = seg;
            otterStart = sessionSeconds;
            otterSplashed = false;
        }
        boolean startled = otterSeg == seg;
        float since = startled ? (float) (sessionSeconds - otterStart) : 0f;
        int zi = (int) z;
        float u = focal / z;
        float baseX = w / 2f + (offsets[zi] - lateral - (angles[25] * 0.6f) * z) * u;
        float sy = horizon + CAM_H * u;
        for (int o = 0; o < 3; o++) {
            float ox = baseX + side * (HALF_WIDTH * 0.5f + o * 1.6f) * u;
            float bob = (float) Math.sin(sessionSeconds * 3 + o) * 0.12f * u;
            float state;
            if (startled) {
                state = Math.min(1f, 0.7f + since * 0.3f + o * 0.02f);
                if (since > 0.6f) {
                    state = 0.95f;
                }
            } else {
                state = (float) ((sessionSeconds * 0.18 + o * 0.33) % 1.0);
                if (o == 1) {
                    state = 0.2f; // the one on its back stays up, eating
                }
            }
            drawOtter(c, ox, sy + bob, u, state, o == 1 && !startled, side);
        }
        if (startled && !otterSplashed) {
            otterSplashed = true;
            fx.burst(baseX + side * HALF_WIDTH * 0.6f * u, sy, 22, dp(150f), 0.6f, dp(3f), 0xDDBFE3FF, true);
        }
    }

    /** {@code state}: under 0.7 surfaced, to 0.8 diving tail-up, beyond that under with a ring. */
    private void drawOtter(Canvas c, float x, float y, float u, float state, boolean onBack, float side) {
        int fur = lit(0xFF5C4430);
        int belly = lit(0xFFB08A60);
        if (state < 0.7f) {
            if (onBack) {
                paint.setColor(belly);
                c.drawOval(x - 0.6f * u, y - 0.3f * u, x + 0.6f * u, y + 0.05f * u, paint);
                paint.setColor(fur);
                c.drawCircle(x - side * 0.65f * u, y - 0.25f * u, 0.22f * u, paint);
                // A silver fish held on its chest, tail flicking.
                paint.setColor(lit(0xFFC8D6E0));
                float flick = (float) Math.sin(sessionSeconds * 6) * 0.08f * u;
                c.drawOval(x - 0.25f * u, y - 0.45f * u, x + 0.2f * u, y - 0.3f * u, paint);
                c.drawLine(x + 0.2f * u, y - 0.38f * u, x + 0.35f * u, y - 0.45f * u + flick, paint);
                return;
            }
            paint.setColor(fur);
            c.drawOval(x - 0.5f * u, y - 0.3f * u, x + 0.3f * u, y + 0.05f * u, paint);
            float hx = x + side * -0.35f * u;
            c.drawCircle(hx, y - 0.32f * u, 0.24f * u, paint);
            paint.setColor(belly);
            c.drawCircle(hx, y - 0.24f * u, 0.12f * u, paint);
            paint.setColor(0xFF111111);
            c.drawCircle(hx - 0.08f * u, y - 0.4f * u, Math.max(1f, 0.04f * u), paint);
            c.drawCircle(hx + 0.08f * u, y - 0.4f * u, Math.max(1f, 0.04f * u), paint);
        } else if (state < 0.8f) {
            float p = (state - 0.7f) / 0.1f;
            paint.setColor(fur);
            c.drawOval(x - 0.35f * u, y - 0.3f * u * (1 - p), x + 0.35f * u, y + 0.05f * u, paint);
            paint.setStrokeWidth(Math.max(1f, 0.12f * u));
            c.drawLine(x, y - 0.1f * u, x + side * 0.2f * u, y - (0.6f - 0.4f * p) * u, paint);
        } else {
            float p = (state - 0.8f) / 0.2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 0.05f * u));
            paint.setColor(((int) ((1f - p) * 200) << 24) | 0x00D8ECF2);
            float r = (0.3f + 0.8f * p) * u;
            c.drawOval(x - r, y - r * 0.3f, x + r, y + r * 0.3f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** The standing landmarks, also used to paint the postcards. */
    private void drawLandmarkShape(Canvas c, int type, float bank, float baseX, float sy, float u, float side, float bridgeHalf) {
        float night = 1f - litLevel;
        float ls = lightSide;
        float shadeSide = ls >= 0f ? -1f : 1f;
        int shadeA = (int) (0x30 + 0x20 * Math.abs(ls)) << 24;
        if (type != 1 && type != 2) {
            // A soft contact shadow on the bank, thrown away from the light.
            paint.setColor(((int) (0x38 * litLevel)) << 24);
            c.drawOval(bank - 4.6f * u - ls * 1.2f * u, sy - 0.35f * u, bank + 4.6f * u - ls * 1.2f * u, sy + 0.4f * u, paint);
        }
        switch (type) {
            case 1: { // stone bridge across the river, three arches with their reflections
                float top = sy - 5f * u;
                float l = baseX - bridgeHalf - 1.2f * u;
                float r = baseX + bridgeHalf + 1.2f * u;
                float span = (r - l) / 3f;
                path.rewind();
                path.moveTo(l, top);
                path.lineTo(r, top);
                path.lineTo(r, sy);
                for (int a = 2; a >= 0; a--) {
                    float ar = l + span * (a + 1) - span * 0.13f;
                    float al = l + span * a + span * 0.13f;
                    float archH = Math.min(3.3f * u, (ar - al) * 0.5f);
                    path.lineTo(ar, sy);
                    rect.set(al, sy - archH, ar, sy + archH);
                    path.arcTo(rect, 0f, -180f, false);
                }
                path.lineTo(l, sy);
                path.close();
                // The reflection first, so the arches and their mirror images make rings.
                c.save();
                c.scale(1f, -0.7f, 0f, sy);
                paint.setColor(0x44000000 | (lit(0xFF4A453C) & 0x00FFFFFF));
                c.drawPath(path, paint);
                c.restore();
                paint.setColor(lit(0xFF8C8577));
                c.drawPath(path, paint);
                // Coping along the top, and a course line under it.
                paint.setColor(lit(0xFFA9A291));
                c.drawRect(l - 0.3f * u, top - 0.4f * u, r + 0.3f * u, top + 0.15f * u, paint);
                paint.setColor(lit(0xFF5E584D));
                c.drawRect(l, top + 0.15f * u, r, top + 0.35f * u, paint);
                paint.setStrokeWidth(Math.max(1f, 0.06f * u));
                paint.setColor(0x40000000);
                c.drawLine(l, top + 1.1f * u, r, top + 1.1f * u, paint);
                if (night > 0.4f) {
                    paint.setColor(0xFFFFD27A);
                    c.drawCircle(baseX - bridgeHalf * 0.5f, sy - 5.4f * u, 0.25f * u, paint);
                    c.drawCircle(baseX + bridgeHalf * 0.5f, sy - 5.4f * u, 0.25f * u, paint);
                }
                break;
            }
            case 2: { // waterfall on the bank
                path.rewind();
                path.moveTo(bank - 3.8f * u, sy);
                path.lineTo(bank - 3.1f * u, sy - 7.3f * u);
                path.lineTo(bank - 0.9f * u, sy - 7.9f * u);
                path.lineTo(bank + 1.4f * u, sy - 7.6f * u);
                path.lineTo(bank + 3.3f * u, sy - 7.0f * u);
                path.lineTo(bank + 3.9f * u, sy);
                path.close();
                paint.setColor(lit(0xFF5E625A));
                c.drawPath(path, paint);
                paint.setColor(shadeA);
                c.drawRect(shadeSide < 0 ? bank - 3.6f * u : bank + 1.6f * u, sy - 7.2f * u,
                        shadeSide < 0 ? bank - 1.6f * u : bank + 3.7f * u, sy, paint);
                paint.setColor(lit(blend(season.foliageA, 0xFF1C2A1E, 0.3f)));
                c.drawOval(bank - 3.3f * u, sy - 8.3f * u, bank + 3.5f * u, sy - 7.1f * u, paint);
                paint.setColor(lit(0xEEFFFFFF));
                c.drawRect(bank - 1.6f * u, sy - 7.6f * u, bank + 1.6f * u, sy, paint);
                paint.setColor(lit(0xFFBFE3FF));
                c.drawRect(bank - 0.9f * u, sy - 7.6f * u, bank - 0.3f * u, sy, paint);
                c.drawRect(bank + 0.6f * u, sy - 7.6f * u, bank + 1.0f * u, sy, paint);
                // Falling streaks so the water visibly pours.
                paint.setColor(lit(0xFFE8F6FF));
                paint.setStrokeWidth(Math.max(1f, 0.12f * u));
                for (int i = 0; i < 4; i++) {
                    float f = (float) ((sessionSeconds * 1.4 + i * 0.25) % 1.0);
                    float lx = bank - 1.2f * u + i * 0.8f * u;
                    c.drawLine(lx, sy - 7f * u + f * 6f * u, lx, sy - 7f * u + f * 6f * u + 1f * u, paint);
                }
                // Spray boiling at the foot.
                float boil = (float) Math.sin(sessionSeconds * 3.2) * 0.25f * u;
                paint.setColor(0x99FFFFFF);
                c.drawOval(bank - 2.6f * u, sy - 0.9f * u - boil, bank + 2.6f * u, sy + 0.6f * u, paint);
                paint.setColor(0x55FFFFFF);
                c.drawOval(bank - 3.6f * u, sy - 1.6f * u + boil, bank + 3.6f * u, sy + 0.2f * u, paint);
                break;
            }
            case 3: { // lighthouse: a tapered, banded tower with a lantern room and a cap
                float tall = 12f * u;
                float bw = 1.1f * u;
                float tw = 0.7f * u;
                paint.setColor(lit(0xFF8C8577));
                c.drawRect(bank - 1.9f * u, sy - 1.0f * u, bank + 1.9f * u, sy, paint);
                path.rewind();
                path.moveTo(bank - bw, sy - 0.9f * u);
                path.lineTo(bank - tw, sy - tall);
                path.lineTo(bank + tw, sy - tall);
                path.lineTo(bank + bw, sy - 0.9f * u);
                path.close();
                paint.setColor(lit(0xFFF2F2F2));
                c.drawPath(path, paint);
                path2.rewind();
                for (int b = 1; b < 12; b += 3) {
                    float y0 = sy - (b + 1.5f) * u;
                    float y1 = sy - b * u;
                    float w0 = bw + (tw - bw) * ((b + 1.5f) * u / tall);
                    float w1 = bw + (tw - bw) * (b * u / tall);
                    path2.moveTo(bank - w1, y1);
                    path2.lineTo(bank - w0, y0);
                    path2.lineTo(bank + w0, y0);
                    path2.lineTo(bank + w1, y1);
                    path2.close();
                }
                paint.setColor(lit(0xFFD8453C));
                c.drawPath(path2, paint);
                // The shadowed half of the tower.
                path2.rewind();
                path2.moveTo(bank, sy - 0.9f * u);
                path2.lineTo(bank, sy - tall);
                path2.lineTo(bank + shadeSide * tw, sy - tall);
                path2.lineTo(bank + shadeSide * bw, sy - 0.9f * u);
                path2.close();
                paint.setColor(shadeA);
                c.drawPath(path2, paint);
                paint.setColor(lit(0xFF2A3340));
                c.drawRect(bank - tw * 1.45f, sy - tall - 0.3f * u, bank + tw * 1.45f, sy - tall + 0.1f * u, paint);
                paint.setColor(night > 0.4f ? 0xFFFFF2A0 : lit(0xFFE8E0B0));
                c.drawRect(bank - 0.5f * u, sy - tall - 1.4f * u, bank + 0.5f * u, sy - tall - 0.3f * u, paint);
                path2.rewind();
                path2.moveTo(bank - 0.8f * u, sy - tall - 1.4f * u);
                path2.lineTo(bank, sy - tall - 2.4f * u);
                path2.lineTo(bank + 0.8f * u, sy - tall - 1.4f * u);
                path2.close();
                paint.setColor(lit(0xFFB8352E));
                c.drawPath(path2, paint);
                float lampY = sy - tall - 0.85f * u;
                Fx.glow(c, bank, lampY, 3f * u, night > 0.4f ? 0xDDFFF2A0 : 0x88FFF2A0);
                if (night > 0.4f) {
                    // A sweeping beam once the light is needed.
                    double a = sessionSeconds * 1.5;
                    paint.setColor(0x44FFF2A0);
                    path.reset();
                    path.moveTo(bank, lampY);
                    path.lineTo(bank + (float) Math.cos(a) * 40f * u, lampY - 3f * u);
                    path.lineTo(bank + (float) Math.cos(a) * 40f * u, lampY + 3f * u);
                    path.close();
                    c.drawPath(path, paint);
                }
                break;
            }
            case 4: { // windmill: a tapered tower, a cap, and four cloth sails
                path.rewind();
                path.moveTo(bank - 1.6f * u, sy);
                path.lineTo(bank - 0.95f * u, sy - 7f * u);
                path.lineTo(bank + 0.95f * u, sy - 7f * u);
                path.lineTo(bank + 1.6f * u, sy);
                path.close();
                paint.setColor(lit(0xFFC2AF92));
                c.drawPath(path, paint);
                path2.rewind();
                path2.moveTo(bank, sy);
                path2.lineTo(bank, sy - 7f * u);
                path2.lineTo(bank + shadeSide * 0.95f * u, sy - 7f * u);
                path2.lineTo(bank + shadeSide * 1.6f * u, sy);
                path2.close();
                paint.setColor(shadeA);
                c.drawPath(path2, paint);
                paint.setColor(night > 0.4f ? 0xFFFFD27A : lit(0xFF3A2A1A));
                c.drawRect(bank - 0.45f * u, sy - 1.6f * u, bank + 0.45f * u, sy, paint);
                c.drawRect(bank - 0.3f * u, sy - 4.6f * u, bank + 0.3f * u, sy - 3.9f * u, paint);
                paint.setColor(lit(0xFF5A4B3A));
                c.drawOval(bank - 1.15f * u, sy - 7.9f * u, bank + 1.15f * u, sy - 6.6f * u, paint);
                double rot = sessionSeconds * (1.2 + rain * 1.5);
                float hx = bank;
                float hy = sy - 7f * u;
                path2.rewind();
                for (int b = 0; b < 4; b++) {
                    double a = rot + b * Math.PI / 2;
                    float dx = (float) Math.cos(a);
                    float dy = (float) Math.sin(a);
                    path2.moveTo(hx + dx * 1.0f * u, hy + dy * 1.0f * u);
                    path2.lineTo(hx + dx * 5f * u, hy + dy * 5f * u);
                    path2.lineTo(hx + dx * 5f * u - dy * 0.9f * u, hy + dy * 5f * u + dx * 0.9f * u);
                    path2.lineTo(hx + dx * 1.4f * u - dy * 0.9f * u, hy + dy * 1.4f * u + dx * 0.9f * u);
                    path2.close();
                }
                paint.setColor(lit(0xFFEDE3CF));
                c.drawPath(path2, paint);
                paint.setColor(lit(0xFF5A4B3A));
                paint.setStrokeWidth(Math.max(1f, 0.3f * u));
                for (int b = 0; b < 4; b++) {
                    double a = rot + b * Math.PI / 2;
                    c.drawLine(hx, hy, hx + (float) Math.cos(a) * 5f * u, hy + (float) Math.sin(a) * 5f * u, paint);
                }
                c.drawCircle(hx, hy, 0.35f * u, paint);
                break;
            }
            case 6: { // boathouse: planked, a deep eave, a boat door and a jetty into the river
                paint.setColor(lit(0xFF7A5230));
                c.drawRect(bank - 4f * u, sy - 4f * u, bank + 4f * u, sy, paint);
                paint.setStrokeWidth(Math.max(1f, 0.07f * u));
                paint.setColor(0x40000000);
                for (int k = -4; k <= 4; k++) {
                    c.drawLine(bank + k * 0.9f * u, sy - 4f * u, bank + k * 0.9f * u, sy, paint);
                }
                paint.setColor(shadeA);
                c.drawRect(shadeSide < 0 ? bank - 4f * u : bank, sy - 4f * u, shadeSide < 0 ? bank : bank + 4f * u, sy, paint);
                paint.setColor(lit(0xFF4A2F1B));
                path.reset();
                path.moveTo(bank - 4.6f * u, sy - 3.9f * u);
                path.lineTo(bank, sy - 6.6f * u);
                path.lineTo(bank + 4.6f * u, sy - 3.9f * u);
                path.close();
                c.drawPath(path, paint);
                paint.setColor(0x55000000);
                c.drawRect(bank - 4f * u, sy - 3.9f * u, bank + 4f * u, sy - 3.5f * u, paint);
                paint.setColor(lit(0xFF24160C));
                c.drawRect(bank - 1.4f * u, sy - 2.6f * u, bank + 1.4f * u, sy, paint);
                paint.setColor(night > 0.4f ? 0xFFFFD27A : lit(0xFF2A1A10));
                c.drawRect(bank - 3.2f * u, sy - 3.2f * u, bank - 2.0f * u, sy - 2.2f * u, paint);
                c.drawRect(bank + 2.0f * u, sy - 3.2f * u, bank + 3.2f * u, sy - 2.2f * u, paint);
                // The jetty reaches toward the river, which is on the far side from the bank's.
                float j0 = bank - side * 4f * u;
                float j1 = bank - side * 7.5f * u;
                paint.setColor(lit(0xFF8A6A44));
                c.drawRect(Math.min(j0, j1), sy - 0.35f * u, Math.max(j0, j1), sy, paint);
                paint.setColor(lit(0xFF3A2A1A));
                c.drawRect(j1 - 0.15f * u, sy - 0.7f * u, j1 + 0.15f * u, sy + 0.3f * u, paint);
                break;
            }
            default: { // old mill with a roof and a turning, splashing wheel
                paint.setColor(lit(0xFFB09B7C));
                c.drawRect(bank - 3f * u, sy - 5f * u, bank + 3f * u, sy, paint);
                paint.setColor(shadeA);
                c.drawRect(shadeSide < 0 ? bank - 3f * u : bank, sy - 5f * u, shadeSide < 0 ? bank : bank + 3f * u, sy, paint);
                paint.setColor(lit(0xFF5A3A2A));
                path.rewind();
                path.moveTo(bank - 3.5f * u, sy - 4.9f * u);
                path.lineTo(bank - 2.2f * u, sy - 7f * u);
                path.lineTo(bank + 2.2f * u, sy - 7f * u);
                path.lineTo(bank + 3.5f * u, sy - 4.9f * u);
                path.close();
                c.drawPath(path, paint);
                paint.setColor(night > 0.4f ? 0xFFFFD27A : lit(0xFF3A2A1A));
                c.drawRect(bank - 1.8f * u, sy - 3.8f * u, bank - 0.8f * u, sy - 2.8f * u, paint);
                c.drawRect(bank + 0.8f * u, sy - 3.8f * u, bank + 1.8f * u, sy - 2.8f * u, paint);
                float wx = bank - side * 3.6f * u;
                float wy = sy - 1.8f * u;
                paint.setColor(0x88FFFFFF);
                float splash = (float) Math.sin(sessionSeconds * 4) * 0.2f * u;
                c.drawOval(wx - 2.3f * u, sy - 0.5f * u - splash, wx + 2.3f * u, sy + 0.4f * u, paint);
                paint.setColor(lit(0xFF4A3A2A));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(1f, 0.25f * u));
                c.drawCircle(wx, wy, 1.8f * u, paint);
                double wr = sessionSeconds * 0.8;
                for (int b = 0; b < 4; b++) {
                    double a = wr + b * Math.PI / 4;
                    float dx = (float) Math.cos(a) * 1.8f * u;
                    float dy = (float) Math.sin(a) * 1.8f * u;
                    c.drawLine(wx - dx, wy - dy, wx + dx, wy + dy, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                break;
            }
        }
    }

    /** Rocks in the rapids, with white water breaking round them. */
    private void drawRocks(Canvas c, float w, float horizon, float focal, float yaw) {
        if (rapSeg == null) {
            return;
        }
        for (int k = ROCKS - 1; k >= 0; k--) {
            float z = (float) (rapBase + ROCK_FIRST + k * ROCK_GAP - along);
            if (z < 3f || z > LOOK) {
                continue;
            }
            int zi = (int) z;
            float u = focal / z;
            float x = w / 2f + (offsets[zi] + rapSeg.rocks[k] - lateral - yaw * z) * u;
            float y = horizon + CAM_H * u;
            float fog = Math.min(1f, z / (LOOK * (1f - 0.55f * mist)));
            float surge = (float) Math.sin(sessionSeconds * 5 + k) * 0.15f;
            float ls = lightSide;
            // 3.25: dark water in the rock's lee, the pillow of white water piled against it, the
            // stone lit from the sun's side with a wet sheen, and a V of foam peeling back at you.
            paint.setColor(0x66000000 | (world(0xFF0E2430, fog * 0.6f) & 0x00FFFFFF));
            c.drawOval(x - 1.6f * u, y - 0.05f * u, x + 1.6f * u, y + 0.6f * u, paint);
            paint.setColor(world(0xFFE8F4F8, fog * 0.6f));
            c.drawOval(x - (1.7f + surge) * u, y - 0.35f * u, x + (1.7f + surge) * u, y + 0.35f * u, paint);
            paint.setColor(world(0xFF3A3A38, fog * 0.6f));
            c.drawOval(x - 1.1f * u, y - 1.3f * u, x + 1.1f * u, y + 0.15f * u, paint);
            paint.setColor(world(0xFF5E5D58, fog * 0.6f));
            c.drawOval(x - 1.0f * u + ls * 0.15f * u, y - 1.28f * u, x + 0.95f * u + ls * 0.15f * u, y - 0.05f * u, paint);
            paint.setColor(world(0xFF83817A, fog * 0.6f));
            c.drawOval(x - 0.55f * u + ls * 0.35f * u, y - 1.25f * u, x + 0.35f * u + ls * 0.35f * u, y - 0.6f * u, paint);
            paint.setColor(0x55FFFFFF);
            c.drawOval(x - 0.2f * u + ls * 0.4f * u, y - 1.18f * u, x + 0.15f * u + ls * 0.4f * u, y - 0.98f * u, paint);
            paint.setColor(0xB0000000 | (world(0xFFF2FAFC, fog * 0.6f) & 0x00FFFFFF));
            paint.setStrokeWidth(Math.max(1f, 0.14f * u));
            for (int s = -1; s <= 1; s += 2) {
                c.drawLine(x + s * 1.2f * u, y + 0.15f * u, x + s * (2.3f + surge) * u, y + 0.6f * u, paint);
            }
            // The next rock is ringed in the colour of how things stand.
            if (inRapids || rapBase - along < ROCK_FIRST + 30) {
                double rockAt = rapBase + ROCK_FIRST + k * ROCK_GAP;
                boolean next = rockAt > along && rockAt - along < ROCK_GAP;
                if (next) {
                    boolean danger = Math.abs(rapidsTarget() - rapSeg.rocks[k]) < ROCK_HIT;
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(3f));
                    paint.setColor(danger ? BAD : ACCENT);
                    c.drawOval(x - 1.5f * u, y - 1.6f * u, x + 1.5f * u, y + 0.4f * u, paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    /**
     * The weir: a stone sill right across the river with the water pouring over it toward you, and
     * a marker post on each bank. While the boat is held below it the sill is ringed in amber, and
     * as the burst charges a ramp of white water builds up the face.
     */
    private void drawWeir(Canvas c, float w, float horizon, float focal, float yaw) {
        if (weirSeg == null || dir < 0) {
            return;
        }
        float z = (float) (weirBase - along);
        if (z < 2f || z > LOOK) {
            return;
        }
        boolean done = weirsDone.contains(weirKey);
        int zi = Math.max(0, Math.min(LOOK, (int) z));
        float u = focal / z;
        float x = w / 2f + (offsets[zi] - lateral - yaw * z) * u;
        float y = horizon + CAM_H * u;
        float fog = Math.min(1f, z / (LOOK * (1f - 0.55f * mist)));
        float half = (HALF_WIDTH + 2f) * u;
        float sill = 1.7f * u;
        // The pour: pale water falling down the face, racing as the charge builds.
        paint.setColor(world(0xFFD8ECF2, fog * 0.6f));
        c.drawRect(x - half, y - sill, x + half, y + 0.25f * u, paint);
        paint.setColor(world(0xFF9FC8D8, fog * 0.6f));
        paint.setStrokeWidth(Math.max(1f, 0.1f * u));
        double t = sessionSeconds * (1.5 + weirCharge * 2.5);
        for (int i = 0; i < 9; i++) {
            float f = (float) ((t + i * 0.31) % 1.0);
            float lx = x - half + (i + 0.5f) * (half * 2f / 9f);
            c.drawLine(lx, y - sill + f * sill, lx, y - sill + Math.min(sill, f * sill + 0.4f * u), paint);
        }
        // The stone crest, and the coping on top.
        paint.setColor(world(0xFF6E6A60, fog * 0.6f));
        c.drawRect(x - half, y - sill - 0.45f * u, x + half, y - sill + 0.1f * u, paint);
        paint.setColor(world(0xFF8D8A80, fog * 0.6f));
        c.drawRect(x - half, y - sill - 0.45f * u, x + half, y - sill - 0.25f * u, paint);
        // Marker posts, striped, one on each bank.
        for (int s = -1; s <= 1; s += 2) {
            float px = x + s * half;
            paint.setColor(world(0xFFF2F2F2, fog * 0.6f));
            c.drawRect(px - 0.22f * u, y - sill - 3.4f * u, px + 0.22f * u, y - sill, paint);
            paint.setColor(world(done ? 0xFF35D0BA : 0xFFF0B132, fog * 0.6f));
            c.drawRect(px - 0.22f * u, y - sill - 3.0f * u, px + 0.22f * u, y - sill - 2.2f * u, paint);
            c.drawRect(px - 0.22f * u, y - sill - 1.4f * u, px + 0.22f * u, y - sill - 0.6f * u, paint);
        }
        if (!done && z < 90f) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3f));
            paint.setColor(weirBlocking ? (weirCharge > 0.05f ? ACCENT : BAD) : WARN);
            c.drawRect(x - half, y - sill - 0.6f * u, x + half, y + 0.3f * u, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // Going over: a bow-wave of white water climbing the face.
        if (weirLift > 0) {
            float p = 1f - weirLift / 1.6f;
            paint.setColor((((int) (200 * (1f - p))) << 24) | 0x00FFFFFF);
            c.drawOval(x - half * 0.7f, y - sill - p * 2f * u, x + half * 0.7f, y + 0.4f * u, paint);
        }
    }

    /**
     * The expedition's destination, drawn on the water as you close on it: a lake opening out, a
     * wall of falls, or a gorge closing in. A pair of carved posts carries the name so there is no
     * doubt what you are rowing at.
     */
    private void drawDestination(Canvas c, float w, float horizon, float focal, float yaw) {
        if (dir < 0) {
            return;
        }
        // While the arrival panel is up the place you just reached is still the one on the water;
        // the new target is kilometres away and would draw nothing.
        double point = expArrived ? arrivedTarget : expTarget;
        int kind = expArrived ? arrivedKind : expKind;
        String name = expArrived ? arrivedName : expName;
        float z = (float) (point - along);
        if (z < 4f || z > LOOK) {
            return;
        }
        int zi = Math.max(0, Math.min(LOOK, (int) z));
        float u = focal / z;
        float x = w / 2f + (offsets[zi] - lateral - yaw * z) * u;
        float y = horizon + CAM_H * u;
        float fog = Math.min(1f, z / (LOOK * (1f - 0.55f * mist)));
        float half = (HALF_WIDTH + 3f) * u;
        switch (kind) {
            case DEST_FALLS: {
                paint.setColor(world(0xFF5E6A6E, fog * 0.55f));
                c.drawRect(x - half * 1.4f, y - 13f * u, x + half * 1.4f, y, paint);
                paint.setColor(world(0xFFEFF8FC, fog * 0.5f));
                c.drawRect(x - half, y - 12.5f * u, x + half, y + 0.4f * u, paint);
                paint.setStrokeWidth(Math.max(1f, 0.12f * u));
                paint.setColor(world(0xFFBFE3FF, fog * 0.5f));
                for (int i = 0; i < 10; i++) {
                    float f = (float) ((sessionSeconds * 1.1 + i * 0.21) % 1.0);
                    float lx = x - half + (i + 0.5f) * (half * 2f / 10f);
                    c.drawLine(lx, y - 12.5f * u + f * 12f * u, lx, y - 12.5f * u + f * 12f * u + 1.4f * u, paint);
                }
                // Spray boiling at the foot.
                paint.setColor(world(0xFFFFFFFF, fog * 0.5f) & 0xAAFFFFFF);
                float boil = (float) Math.sin(sessionSeconds * 3) * 0.3f * u;
                c.drawOval(x - half * 0.9f, y - 1.2f * u - boil, x + half * 0.9f, y + 0.9f * u, paint);
                break;
            }
            case DEST_GORGE: {
                for (int s = -1; s <= 1; s += 2) {
                    paint.setColor(world(s < 0 ? 0xFF4A4038 : 0xFF3A322C, fog * 0.55f));
                    path.reset();
                    path.moveTo(x + s * half * 0.6f, y);
                    path.lineTo(x + s * half * 0.9f, y - 22f * u);
                    path.lineTo(x + s * half * 2.6f, y - 24f * u);
                    path.lineTo(x + s * half * 2.6f, y + 1f * u);
                    path.close();
                    c.drawPath(path, paint);
                }
                paint.setColor(world(0xFF11161C, fog * 0.4f));
                c.drawRect(x - half * 0.6f, y - 22f * u, x + half * 0.6f, y, paint);
                break;
            }
            default: {
                // A lake: the river opens into a broad sheet with a far shore and low hills.
                paint.setColor(world(0xFF9FC4D8, fog * 0.4f));
                c.drawRect(x - half * 4f, y - 1.2f * u, x + half * 4f, y + 0.6f * u, paint);
                paint.setColor(world(0xFF4E6B5C, fog * 0.5f));
                c.drawRect(x - half * 4f, y - 2.6f * u, x + half * 4f, y - 1.2f * u, paint);
                paint.setColor(world(0xFF5F7A88, fog * 0.5f));
                for (int i = -2; i <= 2; i++) {
                    path.reset();
                    path.moveTo(x + i * half * 1.5f - half, y - 2.4f * u);
                    path.lineTo(x + i * half * 1.5f, y - (4.5f + (i & 1) * 1.6f) * u);
                    path.lineTo(x + i * half * 1.5f + half, y - 2.4f * u);
                    path.close();
                    c.drawPath(path, paint);
                }
                // Glitter on the open water.
                paint.setColor(world(0xFFFFFFFF, fog * 0.4f) & 0x99FFFFFF);
                for (int i = 0; i < 7; i++) {
                    float gx = x + (i - 3) * half * 0.9f + (float) Math.sin(sessionSeconds * 1.3 + i) * 0.4f * u;
                    c.drawOval(gx - 0.5f * u, y - 0.25f * u, gx + 0.5f * u, y - 0.05f * u, paint);
                }
                break;
            }
        }
        // The name posts: always drawn, whatever the destination is.
        for (int s = -1; s <= 1; s += 2) {
            float px = x + s * half;
            paint.setColor(world(0xFF6B4A2B, fog * 0.5f));
            c.drawRect(px - 0.3f * u, y - 5f * u, px + 0.3f * u, y, paint);
        }
        paint.setColor(world(0xFFF5C518, fog * 0.5f));
        c.drawRect(x - half, y - 5.4f * u, x + half, y - 4.2f * u, paint);
        if (z < 160f) {
            bold(c, name, x, y - 5.9f * u, Math.min(26f, Math.max(10f, 1.4f * u / dp(1f))), 0xFFF5C518,
                    Paint.Align.CENTER);
        }
    }

    /**
     * What the season puts in the air: blossom in spring, heat shimmer and dragonflies in summer,
     * leaves in autumn, snow in winter. Screen space, no allocation, and it drifts with the boat.
     */
    private void drawSeasonAir(Canvas c, float w, float h, float horizon) {
        double t = sessionSeconds;
        int n = season.airKind == RiverExplorerSeason.AIR_SNOW ? 60
                : season.airKind == RiverExplorerSeason.AIR_SHIMMER ? 16 : 34;
        float drift = dp(30f) + boat.value() * dp(14f);
        for (int i = 0; i < n; i++) {
            long hsh = (i + 23) * 2654435761L;
            float bx = ((hsh >>> 8) & 0xFFFF) / 65535f * (w + dp(120f)) - dp(60f);
            float sway = (float) Math.sin(t * (0.6 + (i % 5) * 0.2) + i) * dp(22f);
            switch (season.airKind) {
                case RiverExplorerSeason.AIR_SNOW: {
                    float fall = dp(70f) + ((hsh >>> 26) & 63) * dp(2.2f);
                    float y = (float) (((((hsh >>> 16) & 0xFFFF) / 65535f) * h + t * fall) % h);
                    paint.setColor(0xCCFFFFFF);
                    c.drawCircle(bx + sway, y, dp(1.4f + (i % 3) * 0.9f), paint);
                    break;
                }
                case RiverExplorerSeason.AIR_BLOSSOM: {
                    float fall = dp(45f) + ((hsh >>> 26) & 63) * dp(1.4f);
                    float y = (float) (((((hsh >>> 16) & 0xFFFF) / 65535f) * h + t * fall) % h);
                    float sp = (float) Math.sin(t * 3 + i) * dp(3.5f);
                    paint.setColor(0xDDFFD6E8);
                    c.drawOval(bx + sway - dp(3.5f), y - Math.abs(sp) - dp(1.5f),
                            bx + sway + dp(3.5f), y + Math.abs(sp) + dp(1.5f), paint);
                    break;
                }
                case RiverExplorerSeason.AIR_LEAVES: {
                    float fall = dp(55f) + ((hsh >>> 26) & 63) * dp(1.8f);
                    float y = (float) (((((hsh >>> 16) & 0xFFFF) / 65535f) * h + t * fall) % h);
                    float spin = (float) Math.sin(t * 2.4 + i * 1.3);
                    paint.setColor(((i & 1) == 0 ? 0xEEE0922B : 0xEEC2661F));
                    c.drawOval(bx + sway - dp(6f) * Math.abs(spin) - dp(1f), y - dp(3f),
                            bx + sway + dp(6f) * Math.abs(spin) + dp(1f), y + dp(3f), paint);
                    break;
                }
                default: {
                    // Dragonflies skimming the water in the summer heat.
                    float y = horizon + dp(40f) + (((hsh >>> 16) & 0xFF) / 255f) * (h * 0.4f);
                    // Wrapped both ways: bx starts at -60dp, and a bare % on a negative number
                    // parks the first few dragonflies permanently off the left edge.
                    float span = w + dp(120f);
                    float dx = (float) (((((bx + dp(60f)) + t * drift * (0.4f + (i % 3) * 0.3f)) % span)
                            + span) % span) - dp(60f);
                    float wing = (float) Math.sin(t * 22 + i) * dp(4f);
                    paint.setColor(0x9935D0BA);
                    c.drawOval(dx - dp(6f), y - dp(1.2f), dx + dp(6f), y + dp(1.2f), paint);
                    paint.setColor(0x66E6EDF7);
                    c.drawOval(dx - dp(2f), y - dp(1f) - Math.abs(wing), dx + dp(2f), y + dp(1f), paint);
                    break;
                }
            }
        }
    }

    /** Kayaks, rowboats and scullers ahead, seen from astern as you close on them. */
    private void drawTraffic(Canvas c, float w, float horizon, float focal, float yaw) {
        Traffic nearest = null;
        for (Traffic t : traffic) {
            if (!t.active) {
                continue;
            }
            float z = (float) ((t.pos - along) * dir);
            if (z < 4f || z > LOOK) {
                continue;
            }
            if (!t.passed && (nearest == null || z < (nearest.pos - along) * dir)) {
                nearest = t;
            }
        }
        // Draw far to near: at most four boats, a tiny selection sort needs no allocation.
        float lastZ = Float.MAX_VALUE;
        for (int n = 0; n < TRAFFIC; n++) {
            Traffic pick = null;
            float pickZ = -1f;
            for (Traffic t : traffic) {
                float z = (float) ((t.pos - along) * dir);
                if (t.active && z >= 4f && z <= LOOK && z < lastZ && z > pickZ) {
                    pick = t;
                    pickZ = z;
                }
            }
            if (pick == null) {
                break;
            }
            lastZ = pickZ;
            drawOneBoat(c, w, horizon, focal, yaw, pick, pickZ, pick == nearest);
        }
    }

    private void drawOneBoat(Canvas c, float w, float horizon, float focal, float yaw, Traffic t, float z, boolean tagged) {
        int zi = (int) z;
        float u = focal / z;
        float x = w / 2f + (offsets[zi] + dir * t.lane - lateral - yaw * z) * u;
        float y = horizon + CAM_H * u;
        float fog = Math.min(1f, z / (LOOK * (1f - 0.55f * mist)));
        int hull = world(t.colour, fog * 0.6f);
        int skin = world(0xFFE0B090, fog * 0.6f);
        int shirt = world(t.kind == SCULL ? 0xFFF2F2F2 : 0xFF2A3340, fog * 0.6f);
        float hullW = t.kind == ROWBOAT ? 1.5f : t.kind == SCULL ? 0.55f : 0.75f;
        float bob = (float) Math.sin(t.phase * 1.3f) * 0.05f * u;
        // Wake trailing behind toward us.
        paint.setColor(world(0xFFBFE3FF, fog * 0.6f) & 0x99FFFFFF);
        c.drawOval(x - hullW * 1.3f * u, y - 0.05f * u, x + hullW * 1.3f * u, y + 0.35f * u, paint);
        paint.setColor(0x55000000 | (world(0xFF0A1820, fog * 0.6f) & 0x00FFFFFF));
        c.drawOval(x - hullW * 1.1f * u, y + 0.02f * u, x + hullW * 1.1f * u, y + 0.32f * u, paint);
        paint.setColor(blend(hull, 0xFF000000, 0.3f));
        c.drawOval(x - hullW * u, y - 0.35f * u + bob, x + hullW * u, y + 0.15f * u + bob, paint);
        paint.setColor(hull);
        c.drawOval(x - hullW * 0.97f * u, y - 0.36f * u + bob, x + hullW * 0.97f * u, y + 0.02f * u + bob, paint);
        paint.setStrokeWidth(Math.max(1f, 0.05f * u));
        paint.setColor(blend(hull, 0xFFFFFFFF, 0.45f));
        c.drawLine(x - hullW * 0.8f * u, y - 0.3f * u + bob, x + hullW * 0.8f * u, y - 0.3f * u + bob, paint);
        // The crew: body, head, and what they pull with.
        paint.setColor(shirt);
        c.drawRect(x - 0.25f * u, y - 1.05f * u + bob, x + 0.25f * u, y - 0.3f * u + bob, paint);
        paint.setColor(skin);
        c.drawCircle(x, y - 1.25f * u + bob, 0.2f * u, paint);
        paint.setStrokeWidth(Math.max(1f, 0.07f * u));
        paint.setColor(world(0xFFD8C39A, fog * 0.6f));
        if (t.kind == KAYAK) {
            // Double-bladed paddle rocking side to side.
            float a = (float) Math.sin(t.phase * 2f) * 0.6f;
            float dx = (float) Math.cos(a) * 1.4f * u;
            float dy = (float) Math.sin(a) * 1.4f * u;
            float cy = y - 0.7f * u + bob;
            c.drawLine(x - dx, cy - dy, x + dx, cy + dy, paint);
            paint.setColor(hull);
            c.drawOval(x - dx - 0.2f * u, cy - dy - 0.1f * u, x - dx + 0.2f * u, cy - dy + 0.1f * u, paint);
            c.drawOval(x + dx - 0.2f * u, cy + dy - 0.1f * u, x + dx + 0.2f * u, cy + dy + 0.1f * u, paint);
        } else {
            // Oars sweeping together, blades dipping on the drive.
            float sweep = (float) Math.sin(t.phase * 2f);
            float span = t.kind == SCULL ? 2.6f : 2f;
            float dip = sweep > 0 ? 0.1f : -0.3f;
            float oy = y - 0.45f * u + bob;
            for (int s = -1; s <= 1; s += 2) {
                float bx = x + s * span * u;
                float by = y + dip * u + sweep * 0.1f * u;
                c.drawLine(x + s * 0.3f * u, oy, bx, by, paint);
            }
        }
        if (tagged) {
            float gap = z;
            String tag = TRAFFIC_NAME[t.kind] + "  " + Math.round(gap) + " m";
            float ty = y - 1.6f * u - dp(14f);
            label(c, tag, x, ty, 11f, t.kind == SCULL ? WARN : TEXT, Paint.Align.CENTER);
        }
    }

    /** The bow and two oars, sweeping back on the drive and forward on the recovery. */
    private void drawBow(Canvas c, float w, float h, float speed, float dt) {
        oarPhase = Math.min(2f, oarPhase + dt / (oarPhase < 1f ? 0.8f : 1.6f));
        float cx = w / 2f;
        float deckTop = h * 0.80f;
        float sp = Math.max(0f, Math.min(1f, speed / 4.2f));
        float ls = lightSide;
        // 3.25: the bow wave - white water peeling off the stem, wider and brighter the harder you
        // row, with foam streaming past the hull carried by the distance actually rowed.
        if (sp > 0.04f) {
            int foamA = (int) (0x28 + 0x80 * sp);
            paint.setColor((foamA << 24) | 0x00EAF6FA);
            for (int side = -1; side <= 1; side += 2) {
                path.rewind();
                path.moveTo(cx + side * dp(4f), deckTop + dp(2f));
                path.quadTo(cx + side * (w * 0.10f + w * 0.05f * sp), deckTop + (h - deckTop) * 0.45f,
                        cx + side * (w * 0.17f + w * 0.13f * sp), h + dp(2f));
                path.lineTo(cx + side * w * 0.165f, h + dp(2f));
                path.close();
                c.drawPath(path, paint);
            }
            paint.setStrokeWidth(dp(3f));
            for (int i = 0; i < 8; i++) {
                float p = (float) ((sessionMeters * 0.45 + i / 8.0) % 1.0);
                float side = (i & 1) == 0 ? -1f : 1f;
                float reach = w * 0.165f + w * 0.12f * sp;
                float x0 = cx + side * reach * p;
                float y0 = deckTop + (h - deckTop) * p;
                float len = dp(8f) + dp(26f) * p;
                paint.setColor(((int) Math.min(255.0, foamA * 1.6f * Math.sin(Math.PI * p)) << 24) | 0x00FFFFFF);
                c.drawLine(x0, y0, x0 + side * len * 0.55f, y0 + len * 0.45f, paint);
            }
        }
        // The hull: dark planking under a lighter deck, both with the light falling down them.
        float hw = w * 0.17f;
        cols2[0] = lit(0xFF5A3618);
        cols2[1] = lit(0xFF8B5A2B);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(grad(G_HULL, deckTop, h, cols2, pos2));
        path.rewind();
        path.moveTo(cx, deckTop - dp(4f));
        path.quadTo(cx + hw * 0.55f, deckTop + (h - deckTop) * 0.30f, cx + hw, h + dp(2f));
        path.lineTo(cx - hw, h + dp(2f));
        path.quadTo(cx - hw * 0.55f, deckTop + (h - deckTop) * 0.30f, cx, deckTop - dp(4f));
        path.close();
        c.drawPath(path, paint);
        float dw = w * 0.12f;
        cols2[0] = lit(0xFFCB9C64);
        cols2[1] = lit(0xFF9A683A);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(grad(G_DECK, deckTop, h, cols2, pos2));
        path.rewind();
        path.moveTo(cx, deckTop + dp(9f));
        path.quadTo(cx + dw * 0.55f, deckTop + (h - deckTop) * 0.35f, cx + dw, h + dp(2f));
        path.lineTo(cx - dw, h + dp(2f));
        path.quadTo(cx - dw * 0.55f, deckTop + (h - deckTop) * 0.35f, cx, deckTop + dp(9f));
        path.close();
        c.drawPath(path, paint);
        paint.setShader(null);
        // The half of the deck turned from the light.
        float shadeSide = ls >= 0f ? -1f : 1f;
        path2.rewind();
        path2.moveTo(cx, deckTop + dp(12f));
        path2.quadTo(cx + shadeSide * dw * 0.5f, deckTop + (h - deckTop) * 0.36f, cx + shadeSide * dw * 0.97f, h + dp(2f));
        path2.lineTo(cx, h + dp(2f));
        path2.close();
        paint.setColor(((int) (0x18 + 0x18 * Math.abs(ls)) << 24));
        c.drawPath(path2, paint);
        // Seams converging on the stem, and the lit rim of the gunwale.
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0x55000000 | (lit(0xFF4A2C14) & 0x00FFFFFF));
        c.drawLine(cx, deckTop + dp(16f), cx, h, paint);
        for (int side = -1; side <= 1; side += 2) {
            c.drawLine(cx + side * dp(1f), deckTop + dp(18f), cx + side * dw * 0.36f, h, paint);
            c.drawLine(cx + side * dp(2f), deckTop + dp(18f), cx + side * dw * 0.7f, h, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(0x80000000 | (lit(0xFFFFE6C0) & 0x00FFFFFF));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        // The stem post with a brass cap, and a pennant on its staff that streams harder with speed.
        float staffTop = deckTop - dp(40f);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(lit(0xFF3A2A1A));
        c.drawLine(cx, deckTop + dp(2f), cx, staffTop, paint);
        float flap = (float) Math.sin(sessionSeconds * (5 + 7 * sp)) * dp(2f + 4f * sp);
        float flagLen = dp(22f + 18f * sp);
        path2.rewind();
        path2.moveTo(cx, staffTop);
        path2.quadTo(cx + flagLen * 0.5f, staffTop + dp(3f) + flap, cx + flagLen, staffTop + dp(6f) + flap * 1.4f);
        path2.quadTo(cx + flagLen * 0.5f, staffTop + dp(10f) - flap * 0.4f, cx, staffTop + dp(13f));
        path2.close();
        paint.setColor(lit(ACCENT));
        c.drawPath(path2, paint);
        paint.setColor(lit(0xFF4A2E16));
        rect.set(cx - dp(4f), deckTop - dp(4f), cx + dp(4f), deckTop + dp(14f));
        c.drawRoundRect(rect, dp(3f), dp(3f), paint);
        paint.setColor(lit(0xFFD9A441));
        c.drawCircle(cx, deckTop - dp(3f), dp(5.5f), paint);
        paint.setColor(0xAAFFFFFF);
        c.drawCircle(cx + ls * dp(2f), deckTop - dp(5f), dp(2f), paint);
        if (light < 0.55f) {
            // A lantern on the bow once it gets dark.
            float a = Math.min(1f, (0.55f - light) / 0.25f);
            Fx.glow(c, cx, deckTop + dp(10f), dp(110f), ((int) (a * 0x88) << 24) | 0x00FFD27A);
            paint.setColor(0xFFFFE2A0);
            c.drawCircle(cx, deckTop + dp(12f), dp(6f), paint);
        }
        // Oar sweep: 0 at the catch (blades forward), 1 at the finish (blades back).
        float sweep = oarPhase < 1f ? oarPhase : 2f - oarPhase;
        boolean inWater = oarPhase < 1f;
        int waterTint = lit(season.waterA);
        for (int side = -1; side <= 1; side += 2) {
            float pivotX = cx + side * w * 0.14f;
            float pivotY = h * 0.93f;
            float angle = (float) Math.toRadians(-60 + sweep * 50);
            float bladeX = pivotX + side * (float) Math.cos(angle) * w * 0.3f;
            float bladeY = pivotY + (float) Math.sin(angle) * h * 0.12f + (inWater ? dp(10f) : -dp(14f));
            if (inWater) {
                // The puddle the blade leaves, spreading through the drive.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(((int) (0xB0 * (1f - oarPhase)) << 24) | 0x00E4F3F7);
                float rx = dp(28f) + dp(40f) * oarPhase;
                float ry = dp(8f) + dp(10f) * oarPhase;
                c.drawOval(bladeX - rx, bladeY - ry, bladeX + rx, bladeY + ry, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x66E4F3F7);
                c.drawOval(bladeX - dp(30f), bladeY - dp(7f), bladeX + dp(30f), bladeY + dp(9f), paint);
            } else {
                // Drips falling off the blade on the recovery.
                for (int d = 0; d < 3; d++) {
                    float dph = (float) ((sessionSeconds * 1.8 + d * 0.33) % 1.0);
                    paint.setColor(((int) (0xC0 * (1f - dph)) << 24) | 0x00D8ECF2);
                    c.drawCircle(bladeX + side * dp(6f) * (d - 1), bladeY + dp(8f) + dph * dp(28f), dp(2.2f), paint);
                }
            }
            // The loom: a shadow under it, the wood, a highlight along the top, a leather collar.
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(7f));
            paint.setColor(0x55000000);
            c.drawLine(pivotX, pivotY + dp(3f), bladeX, bladeY + dp(3f), paint);
            paint.setStrokeWidth(dp(6f));
            paint.setColor(lit(0xFFD9BE90));
            c.drawLine(pivotX, pivotY, bladeX, bladeY, paint);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(0x88FFFFFF);
            c.drawLine(pivotX, pivotY - dp(1.5f), bladeX, bladeY - dp(1.5f), paint);
            paint.setStrokeWidth(dp(9f));
            paint.setColor(lit(0xFF3A2A1A));
            float cxl = pivotX + (bladeX - pivotX) * 0.08f;
            float cyl = pivotY + (bladeY - pivotY) * 0.08f;
            c.drawLine(pivotX, pivotY, cxl, cyl, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            // The blade, turned along the loom: white with the WAKE stripe at its tip, and greyed by
            // the water while it is buried.
            float deg = (float) Math.toDegrees(Math.atan2(bladeY - pivotY, bladeX - pivotX));
            c.save();
            c.rotate(deg, bladeX, bladeY);
            rect.set(bladeX - dp(8f), bladeY - dp(10f), bladeX + dp(38f), bladeY + dp(10f));
            paint.setColor(inWater ? blend(lit(0xFFF2F2F2), waterTint, 0.45f) : lit(0xFFF2F2F2));
            c.drawRoundRect(rect, dp(9f), dp(9f), paint);
            paint.setColor(0x33000000);
            rect.set(bladeX - dp(6f), bladeY + dp(1f), bladeX + dp(36f), bladeY + dp(10f));
            c.drawRoundRect(rect, dp(8f), dp(8f), paint);
            paint.setColor(inWater ? blend(lit(ACCENT), waterTint, 0.45f) : lit(ACCENT));
            c.drawRect(bladeX + dp(24f), bladeY - dp(9.5f), bladeX + dp(30f), bladeY + dp(9.5f), paint);
            c.restore();
            if (inWater && oarPhase < 0.1f && speed > 0.5f) {
                fx.burst(bladeX, bladeY, 8, dp(90f), 0.4f, dp(3f), 0xCCBFE3FF, true);
            }
        }
        // Fireflies over the banks at night.
        float dark = 1f - light;
        if (dark > 0.5f && rain < 0.3f) {
            double t = sessionSeconds;
            for (int i = 0; i < 14; i++) {
                long hsh = (i + 11) * 2654435761L;
                float fx0 = ((hsh >>> 8) & 0xFFFF) / 65535f;
                float px = (fx0 < 0.5f ? fx0 * 0.5f : 0.75f + (fx0 - 0.5f) * 0.5f) * w + (float) Math.sin(t * 0.7 + i) * dp(30f);
                float py = h * (0.46f + (((hsh >>> 24) & 0xFF) / 255f) * 0.22f) + (float) Math.cos(t * 0.9 + i * 2) * dp(14f);
                float blink = Math.max(0f, (float) Math.sin(t * 2.3 + i * 1.9));
                paint.setColor(((int) (blink * (dark - 0.5f) * 2f * 230) << 24) | 0x00E8FF8A);
                c.drawCircle(px, py, dp(2.5f), paint);
            }
        }
    }

    private void drawMinimap(Canvas c, float w, float h) {
        int mw = (int) Math.min(w * 0.24f, dp(300f));
        int mh = (int) Math.min(h * 0.34f, dp(240f));
        float left = w - mw - dp(12f);
        float top = dp(12f);
        mapBtnL = left;
        mapBtnT = top;
        mapBtnR = left + mw;
        mapBtnB = top + mh + dp(18f);
        if (map == null || map.getWidth() != mw || map.getHeight() != mh || mapExploredCount != explored.size()) {
            rebuildMap(mw, mh);
            explorePct = surveyedPercent();
        }
        c.drawBitmap(map, left, top, null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0xFF243246);
        c.drawRect(left, top, left + mw, top + mh, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x40FFFFFF);
        c.drawRect(left + dp(1f), top, left + mw - dp(1f), top + dp(1f), paint);
        // You: a dot and a heading tick.
        int idx = segIndex(along);
        Seg seg = geom(prefixFor(idx), idx);
        float f = (float) ((along - idx * SEG_LEN) / 20f);
        int i0 = Math.max(0, Math.min(19, (int) f));
        float t = Math.max(0f, Math.min(1f, f - i0));
        float wx = seg.xs[i0] + (seg.xs[i0 + 1] - seg.xs[i0]) * t;
        float wy = seg.ys[i0] + (seg.ys[i0 + 1] - seg.ys[i0]) * t;
        float px = left + dp(8f) + (wx - mapMinX) * mapScale;
        float py = top + mh - dp(8f) - (wy - mapMinY) * mapScale;
        Fx.glow(c, px, py, dp(14f), 0x8835D0BA);
        paint.setColor(dir > 0 ? ACCENT : 0xFFF5C518);
        c.drawCircle(px, py, dp(4.5f), paint);
        label(c, String.format(java.util.Locale.US, "TAP MAP  ·  %.0f%% surveyed  ·  %.1f km",
                        explorePct, explored.size() * SEG_LEN / 1000f),
                left + dp(8f), top + mh + dp(14f), 9f, FAINT, Paint.Align.LEFT);
    }

    /* ---------- the surveyed map ---------- */

    /**
     * How much of the river system has been rowed, as a percent. The river never ends, so the
     * denominator is the surveyed part of it: every branch within {@link #MAP_FORKS} forks of the
     * source, {@link #MAP_SEGS} stretches in all. That is a number that can actually be filled in.
     */
    private float surveyedPercent() {
        int found = 0;
        for (String key : explored) {
            int colon = key.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            if (colon <= MAP_FORKS) {
                found++;
            }
        }
        return Math.min(100f, found * 100f / MAP_SEGS);
    }

    /**
     * The whole surveyed river, rowed branches solid and the rest as faint stubs so you can see
     * where there is still river to find. Rendered to a Bitmap and kept: 189 stretches is ~3,800
     * line segments, which is not something to draw every frame on this tablet.
     */
    private void rebuildBigMap(int mw, int mh) {
        if (bigMap == null || bigMap.getWidth() != mw || bigMap.getHeight() != mh) {
            bigMap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
        }
        bigMapCount = explored.size();
        bigMapW = mw;
        bigMapH = mh;
        Canvas mc = new Canvas(bigMap);
        mc.drawColor(0xFF070C14);
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        // Two passes: measure the whole tree, then draw it. The Segs are cached after the first.
        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                float spanX = Math.max(600f, maxX - minX);
                float spanY = Math.max(600f, maxY - minY);
                bigMapScale = Math.min((mw - dp(40f)) / spanX, (mh - dp(40f)) / spanY);
                bigMapMinX = minX - (spanX - (maxX - minX)) / 2f;
                bigMapMinY = minY - (spanY - (maxY - minY)) / 2f;
            }
            for (int f = 0; f <= MAP_FORKS; f++) {
                int branches = 1 << f;
                for (int b = 0; b < branches; b++) {
                    String prefix = branchPrefix(f, b);
                    for (int k = 0; k < SEGS_PER_FORK; k++) {
                        int index = f * SEGS_PER_FORK + k;
                        Seg s = geom(prefix, index);
                        if (pass == 0) {
                            for (int i = 0; i <= 20; i += 5) {
                                minX = Math.min(minX, s.xs[i]);
                                maxX = Math.max(maxX, s.xs[i]);
                                minY = Math.min(minY, s.ys[i]);
                                maxY = Math.max(maxY, s.ys[i]);
                            }
                        } else {
                            drawMapSeg(mc, s, explored.contains(prefix + ":" + index), mh);
                        }
                    }
                }
            }
        }
    }

    /** The fork choices for branch {@code b} at fork level {@code f}, bit 0 being the first fork. */
    private static String branchPrefix(int f, int b) {
        StringBuilder sb = new StringBuilder(f);
        for (int i = 0; i < f; i++) {
            sb.append(((b >> i) & 1) == 0 ? 'L' : 'R');
        }
        return sb.toString();
    }

    private final Paint mapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawMapSeg(Canvas mc, Seg s, boolean known, int mh) {
        mapPaint.setStyle(Paint.Style.STROKE);
        mapPaint.setStrokeCap(Paint.Cap.ROUND);
        mapPaint.setStrokeWidth(known ? dp(3.5f) : dp(1.6f));
        mapPaint.setColor(known ? 0xFF5AA7D6 : 0x33465A6E);
        int step = known ? 1 : 3;   // unexplored water is dashed: a stub, not a route
        for (int i = 0; i + step <= 20; i += step * (known ? 1 : 2)) {
            mc.drawLine(dp(20f) + (s.xs[i] - bigMapMinX) * bigMapScale,
                    mh - dp(20f) - (s.ys[i] - bigMapMinY) * bigMapScale,
                    dp(20f) + (s.xs[i + step] - bigMapMinX) * bigMapScale,
                    mh - dp(20f) - (s.ys[i + step] - bigMapMinY) * bigMapScale, mapPaint);
        }
        if (!known) {
            return;
        }
        mapPaint.setStyle(Paint.Style.FILL);
        if (s.rapids) {
            mapPaint.setColor(0xCCFFFFFF);
            int k = Math.min(20, (int) ((s.rapidsAt + RAPIDS_LEN / 2) / 20f));
            mc.drawCircle(dp(20f) + (s.xs[k] - bigMapMinX) * bigMapScale,
                    mh - dp(20f) - (s.ys[k] - bigMapMinY) * bigMapScale, dp(2.5f), mapPaint);
        }
        if (s.weir) {
            mapPaint.setColor(0xFFF0B132);
            int k = Math.min(20, (int) (s.weirAt / 20f));
            float wx = dp(20f) + (s.xs[k] - bigMapMinX) * bigMapScale;
            float wy = mh - dp(20f) - (s.ys[k] - bigMapMinY) * bigMapScale;
            mc.drawRect(wx - dp(4f), wy - dp(1.6f), wx + dp(4f), wy + dp(1.6f), mapPaint);
        }
        if (s.landmark >= 0 && bests.getString("river.found." + s.prefix + ":" + s.index) != null) {
            mapPaint.setColor(0xFFF5C518);
            int k = (int) (s.landmarkAt / 20f);
            mc.drawCircle(dp(20f) + (s.xs[k] - bigMapMinX) * bigMapScale,
                    mh - dp(20f) - (s.ys[k] - bigMapMinY) * bigMapScale, dp(3.5f), mapPaint);
        }
    }

    private void drawBigMap(Canvas c, float w, float h) {
        paint.setColor(0xF204070C);
        c.drawRect(0, 0, w, h, paint);
        int mw = (int) Math.min(w - dp(48f), dp(1100f));
        int mh = (int) Math.min(h - dp(150f), dp(640f));
        if (mw < 40 || mh < 40) {
            return;
        }
        if (bigMap == null || bigMapW != mw || bigMapH != mh || bigMapCount != explored.size()) {
            rebuildBigMap(mw, mh);
            explorePct = surveyedPercent();
        }
        float left = (w - mw) / 2f;
        float top = dp(96f);
        c.drawBitmap(bigMap, left, top, null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0xFF23303F);
        c.drawRect(left, top, left + mw, top + mh, paint);
        paint.setStyle(Paint.Style.FILL);

        // Where you are now, and where the expedition is headed.
        int idx = segIndex(along);
        Seg seg = geom(prefixFor(idx), idx);
        float f = (float) ((along - idx * SEG_LEN) / 20f);
        int i0 = Math.max(0, Math.min(19, (int) f));
        float ft = Math.max(0f, Math.min(1f, f - i0));
        float px = left + dp(20f) + (seg.xs[i0] + (seg.xs[i0 + 1] - seg.xs[i0]) * ft - bigMapMinX) * bigMapScale;
        float py = top + mh - dp(20f) - (seg.ys[i0] + (seg.ys[i0 + 1] - seg.ys[i0]) * ft - bigMapMinY) * bigMapScale;
        int tIdx = segIndex(expTarget);
        if (tIdx <= MAP_FORKS * SEGS_PER_FORK + 2) {
            Seg ts = segAt(tIdx);
            int tk = Math.min(20, (int) ((expTarget - tIdx * SEG_LEN) / 20f));
            float tx = left + dp(20f) + (ts.xs[tk] - bigMapMinX) * bigMapScale;
            float ty = top + mh - dp(20f) - (ts.ys[tk] - bigMapMinY) * bigMapScale;
            Fx.glow(c, tx, ty, dp(22f), 0x66F5C518);
            paint.setColor(0xFFF5C518);
            drawStar(c, tx, ty, dp(8f));
            label(c, expName, tx, ty - dp(14f), 10f, 0xFFF5C518, Paint.Align.CENTER);
        }
        Fx.glow(c, px, py, dp(20f), 0x8835D0BA);
        paint.setColor(ACCENT);
        c.drawCircle(px, py, dp(6f), paint);

        bold(c, "THE RIVER", dp(30f), dp(50f), 26f, TEXT, Paint.Align.LEFT);
        bold(c, String.format(java.util.Locale.US, "%.0f%% SURVEYED", explorePct), dp(30f), dp(80f), 30f,
                0xFFF5C518, Paint.Align.LEFT);
        // The fill-in bar: the whole surveyed system, and how much of it you have rowed.
        float bl = dp(280f);
        float br = Math.min(w - dp(30f), dp(900f));
        rect.set(bl, dp(58f), br, dp(80f));
        paint.setColor(0xFF16202C);
        c.drawRoundRect(rect, dp(6f), dp(6f), paint);
        rect.set(bl, dp(58f), bl + (br - bl) * explorePct / 100f, dp(80f));
        paint.setColor(ACCENT);
        c.drawRoundRect(rect, dp(6f), dp(6f), paint);
        label(c, explored.size() + " of " + MAP_SEGS + " stretches  ·  " + landmarksFound
                        + " landmarks  ·  " + season.name + ", " + season.flow,
                bl, dp(96f), 11f, DIM, Paint.Align.LEFT);
        // The year's wheel: what the river does in each season, with this month's one lit. The
        // rower asked for spring to run faster and summer slower - this is where that is legible.
        float cellW = Math.min(dp(150f), (w - dp(60f)) / RiverExplorerSeason.count());
        float cellsL = w - dp(30f) - cellW * RiverExplorerSeason.count();
        for (int i = 0; i < RiverExplorerSeason.count(); i++) {
            RiverExplorerSeason s = RiverExplorerSeason.byIndex(i);
            boolean now = s.index == season.index;
            rect.set(cellsL + cellW * i + dp(3f), dp(48f), cellsL + cellW * (i + 1) - dp(3f), dp(96f));
            paint.setColor(now ? 0xFF223142 : 0xFF121A24);
            c.drawRoundRect(rect, dp(6f), dp(6f), paint);
            paint.setColor(now ? ACCENT : blend(s.grassA, 0xFF121A24, 0.45f));
            c.drawRect(rect.left, dp(48f), rect.right, dp(52f), paint);
            label(c, s.name, rect.centerX(), dp(70f), 11f, now ? TEXT : DIM, Paint.Align.CENTER);
            label(c, String.format(java.util.Locale.US, "%.2f m/s", s.current), rect.centerX(), dp(87f), 10f,
                    now ? ACCENT : FAINT, Paint.Align.CENTER);
        }
        label(c, "solid = rowed   ·   faint = river you have not found yet   ·   ● landmark   ▬ weir   ○ rapids",
                w / 2f, h - dp(46f), 11f, FAINT, Paint.Align.CENTER);
        label(c, "tap to close", w / 2f, h - dp(24f), 12f, DIM, Paint.Align.CENTER);
    }

    private void drawStar(Canvas c, float cx, float cy, float r) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = Math.PI * i / 5 - Math.PI / 2;
            float rr = (i & 1) == 0 ? r : r * 0.45f;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        c.drawPath(path, paint);
    }

    private void rebuildMap(int mw, int mh) {
        if (map == null || map.getWidth() != mw || map.getHeight() != mh) {
            map = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
        }
        mapExploredCount = explored.size();
        Canvas mc = new Canvas(map);
        mc.drawColor(0xCC0A121C);
        java.util.List<Seg> segs = new java.util.ArrayList<>();
        mapMinX = Float.MAX_VALUE;
        mapMaxX = -Float.MAX_VALUE;
        mapMinY = Float.MAX_VALUE;
        mapMaxY = -Float.MAX_VALUE;
        for (String key : explored) {
            int colon = key.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            try {
                Seg s = geom(key.substring(0, colon), Integer.parseInt(key.substring(colon + 1)));
                segs.add(s);
                for (int i = 0; i <= 20; i += 5) {
                    mapMinX = Math.min(mapMinX, s.xs[i]);
                    mapMaxX = Math.max(mapMaxX, s.xs[i]);
                    mapMinY = Math.min(mapMinY, s.ys[i]);
                    mapMaxY = Math.max(mapMaxY, s.ys[i]);
                }
            } catch (NumberFormatException ignored) {
                // a malformed key from an older save is skipped
            }
        }
        if (segs.isEmpty()) {
            mapMinX = -500;
            mapMaxX = 500;
            mapMinY = -100;
            mapMaxY = 900;
        }
        float spanX = Math.max(800f, mapMaxX - mapMinX);
        float spanY = Math.max(800f, mapMaxY - mapMinY);
        mapScale = Math.min((mw - dp(16f)) / spanX, (mh - dp(16f)) / spanY);
        mapMinX -= (spanX - (mapMaxX - mapMinX)) / 2f;
        mapMinY -= (spanY - (mapMaxY - mapMinY)) / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(2.5f));
        p.setColor(0xFF5AA7D6);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        for (Seg s : segs) {
            for (int i = 0; i < 20; i++) {
                mc.drawLine(dp(8f) + (s.xs[i] - mapMinX) * mapScale, mh - dp(8f) - (s.ys[i] - mapMinY) * mapScale,
                        dp(8f) + (s.xs[i + 1] - mapMinX) * mapScale, mh - dp(8f) - (s.ys[i + 1] - mapMinY) * mapScale, p);
            }
            if (s.rapids) {
                // Rapids marked in white on the map, so you know where they wait.
                dot.setColor(0xCCFFFFFF);
                int k = Math.min(20, (int) ((s.rapidsAt + RAPIDS_LEN / 2) / 20f));
                mc.drawCircle(dp(8f) + (s.xs[k] - mapMinX) * mapScale, mh - dp(8f) - (s.ys[k] - mapMinY) * mapScale, dp(2f), dot);
            }
            if (s.landmark >= 0 && bests.getString("river.found." + s.prefix + ":" + s.index) != null) {
                dot.setColor(0xFFF5C518);
                int k = (int) (s.landmarkAt / 20f);
                mc.drawCircle(dp(8f) + (s.xs[k] - mapMinX) * mapScale, mh - dp(8f) - (s.ys[k] - mapMinY) * mapScale, dp(3f), dot);
            }
        }
    }

    /* ---------- 3.25 HUD styling ---------- */

    /**
     * A HUD panel in the app's family: rounded, a faint sheen over its top half, a hairline border
     * and a lighter top edge. Leaves {@link #rect} set to the panel's bounds.
     */
    private void panel(Canvas c, float l, float t, float r, float b, float radius, int fill) {
        int a = fill >>> 24;
        rect.set(l, t, r, b);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        c.drawRoundRect(rect, radius, radius, paint);
        rect.set(l + dp(1f), t + dp(1f), r - dp(1f), t + (b - t) * 0.48f);
        paint.setColor(((a * 0x14 / 0xFF) << 24) | 0x00FFFFFF);
        c.drawRoundRect(rect, Math.max(0f, radius - dp(1f)), Math.max(0f, radius - dp(1f)), paint);
        rect.set(l, t, r, b);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(((a * 0xE0 / 0xFF) << 24) | 0x00243246);
        c.drawRoundRect(rect, radius, radius, paint);
        paint.setColor(((a * 0x48 / 0xFF) << 24) | 0x00FFFFFF);
        c.drawLine(l + radius, t + dp(0.5f), r - radius, t + dp(0.5f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A rounded bar track with a glossy fill to fraction {@code p}. */
    private void bar(Canvas c, float l, float t, float r, float b, float p, int colour) {
        float rad = (b - t) / 2f;
        rect.set(l, t, r, b);
        paint.setColor(0xFF152131);
        c.drawRoundRect(rect, rad, rad, paint);
        p = Math.max(0f, Math.min(1f, p));
        if (p <= 0.001f) {
            return;
        }
        float fr = Math.max(l + (b - t), l + (r - l) * p);
        rect.set(l, t, fr, b);
        paint.setColor(colour);
        c.drawRoundRect(rect, rad, rad, paint);
        rect.set(l + rad * 0.6f, t + (b - t) * 0.16f, fr - rad * 0.6f, t + (b - t) * 0.46f);
        if (rect.right > rect.left) {
            paint.setColor(0x44FFFFFF);
            c.drawRoundRect(rect, rad * 0.3f, rad * 0.3f, paint);
        }
    }

    private void drawHud(Canvas c, float w, float h, float speed, double forkDist, char choice) {
        int idx = segIndex(along);
        String reach = reachName(prefixFor(idx), idx).toUpperCase(java.util.Locale.US);
        String stats = String.format(java.util.Locale.US, "%.2f km up the river  ·  %d landmarks  ·  %d boats passed  ·  %s /500",
                along / 1000.0, landmarksFound, passedToday, pace(speed));
        String sky = lightName() + "   ·   " + season.name + ", " + season.flow
                + String.format(java.util.Locale.US, " (%.1f m/s %s you)", current, dir > 0 ? "against" : "with");
        // 3.25: the header sits on its own panel. Over a bright sky the 10dp lines were unreadable.
        // Drawing an empty string sets each paint to its size so the lines can be measured first.
        bold(c, "", 0f, 0f, 22f, TEXT, Paint.Align.LEFT);
        float tw = textPaint.measureText(reach);
        label(c, "", 0f, 0f, 10f, DIM, Paint.Align.LEFT);
        tw = Math.max(tw, Math.max(dimPaint.measureText(stats), dimPaint.measureText(sky)));
        panel(c, dp(8f), dp(8f), dp(18f) + tw + dp(14f), dp(88f), dp(10f), 0xB30A121C);
        bold(c, reach, dp(18f), dp(36f), 22f, TEXT, Paint.Align.LEFT);
        label(c, stats, dp(18f), dp(58f), 10f, DIM, Paint.Align.LEFT);
        label(c, sky, dp(18f), dp(80f), 10f, light < 0.5f ? 0xFFB9C4FF : 0xFFF2C39A, Paint.Align.LEFT);

        // The album button: a small postcard stack with a count.
        albumBtnL = dp(18f);
        albumBtnT = dp(92f);
        albumBtnR = dp(158f);
        albumBtnB = dp(128f);
        panel(c, albumBtnL, albumBtnT, albumBtnR, albumBtnB, dp(8f), 0xCC0A121C);
        paint.setColor(0xFFF4EBD8);
        c.save();
        c.rotate(-8f, albumBtnL + dp(20f), albumBtnT + dp(18f));
        c.drawRect(albumBtnL + dp(8f), albumBtnT + dp(8f), albumBtnL + dp(32f), albumBtnT + dp(26f), paint);
        c.restore();
        paint.setColor(0xFFE8E0CC);
        c.drawRect(albumBtnL + dp(12f), albumBtnT + dp(11f), albumBtnL + dp(36f), albumBtnT + dp(29f), paint);
        paint.setColor(0xFF5AA7D6);
        c.drawRect(albumBtnL + dp(14f), albumBtnT + dp(13f), albumBtnL + dp(34f), albumBtnT + dp(22f), paint);
        bold(c, "ALBUM " + album.size(), albumBtnL + dp(44f), albumBtnT + dp(23f), 12f, TEXT, Paint.Align.LEFT);

        if (!started) {
            bold(c, "ROW TO SET OFF", w / 2f, h * 0.30f, 26f, ACCENT, Paint.Align.CENTER);
        }
        if (forkDist < 450f) {
            int nextIndex = (route.length() + 1) * SEGS_PER_FORK;
            String leftName = reachName(route + "L", nextIndex);
            String rightName = reachName(route + "R", nextIndex);
            boolean leftNew = !explored.contains(route + "L:" + nextIndex);
            boolean rightNew = !explored.contains(route + "R:" + nextIndex);
            float y = h * 0.20f;
            bold(c, "FORK IN " + Math.max(0, Math.round(forkDist)) + " m  ·  TAP A SIDE" + (hasSteering() ? " OR LEAN" : ""),
                    w / 2f, y, 14f, WARN, Paint.Align.CENTER);
            bold(c, "◀  " + leftName.toUpperCase(java.util.Locale.US) + (leftNew ? "  (NEW)" : ""), w * 0.30f, y + dp(26f), 15f,
                    choice == 'L' ? ACCENT : FAINT, Paint.Align.CENTER);
            bold(c, rightName.toUpperCase(java.util.Locale.US) + (rightNew ? "  (NEW)" : "") + "  ▶", w * 0.62f, y + dp(26f), 15f,
                    choice == 'R' ? ACCENT : FAINT, Paint.Align.CENTER);
            if (hasSteering() && Math.abs(steering()) > 0.6f) {
                pendingChoice = steering() < 0 ? 'L' : 'R';
            }
        }
        if (sessionSeconds < bannerUntil) {
            bold(c, banner, w / 2f, h * 0.36f, 20f, 0xFFF5C518, Paint.Align.CENTER);
        }
        if (sessionSeconds < toastUntil) {
            bold(c, toast, w / 2f, h * 0.42f, 15f, toastColour, Paint.Align.CENTER);
        }
    }

    /**
     * The expedition: where you are going, how far is left, and a bar that fills across sessions.
     * Scored on the frontier, so a cruise home never costs you any of it. On the cruise it turns
     * into the run home, with the way back upstream one tap away.
     */
    private void drawExpeditionBar(Canvas c, float w, float h) {
        float left = dp(18f);
        float right = Math.min(w * 0.46f, dp(520f));
        float top = dp(126f);
        panel(c, left, top, right, top + dp(58f), dp(8f), 0xCC0A121C);
        if (dir < 0) {
            float togo = (float) Math.max(0, along - cruiseHome);
            bold(c, "CRUISING HOME", left + dp(12f), top + dp(22f), 15f, 0xFFF5C518, Paint.Align.LEFT);
            label(c, String.format(java.util.Locale.US, "%.2f km cruised  ·  %.0f m to go", cruiseKmSoFar(), togo),
                    right - dp(12f), top + dp(22f), 11f, DIM, Paint.Align.RIGHT);
            float bl = left + dp(12f);
            float br = right - dp(120f);
            float p = cruiseFrom > cruiseHome
                    ? (float) ((cruiseFrom - along) / (cruiseFrom - cruiseHome)) : 1f;
            p = Math.max(0f, Math.min(1f, p));
            bar(c, bl, top + dp(34f), br, top + dp(46f), p, 0xFFF5C518);
            turnBtnL = right - dp(112f);
            turnBtnT = top + dp(30f);
            turnBtnR = right - dp(10f);
            turnBtnB = top + dp(52f);
            panel(c, turnBtnL, turnBtnT, turnBtnR, turnBtnB, dp(6f), 0xFF1B3A46);
            bold(c, "TURN UPSTREAM", (turnBtnL + turnBtnR) / 2f, turnBtnB - dp(7f), 10f, ACCENT, Paint.Align.CENTER);
            return;
        }
        turnBtnR = -1f;
        float togo = (float) Math.max(0, expTarget - frontier);
        bold(c, "EXPEDITION  ·  " + expName, left + dp(12f), top + dp(22f), 15f, 0xFFF5C518, Paint.Align.LEFT);
        label(c, togo > 0
                        ? String.format(java.util.Locale.US, "%.2f km to go", togo / 1000f)
                        : "ARRIVED",
                right - dp(12f), top + dp(22f), 12f, togo > 0 ? DIM : ACCENT, Paint.Align.RIGHT);
        float bl = left + dp(12f);
        float br = right - dp(12f);
        float p = expProgress();
        bar(c, bl, top + dp(32f), br, top + dp(46f), p, p >= 1f ? ACCENT : 0xFF5AA7D6);
        // The boat's own mark, which can sit behind the frontier after a cruise.
        double span = expTarget - expStart;
        if (span > 0) {
            float q = (float) Math.max(0, Math.min(1, (along - expStart) / span));
            paint.setColor(0xFFE6EDF7);
            c.drawRect(bl + (br - bl) * q - dp(1.5f), top + dp(28f), bl + (br - bl) * q + dp(1.5f), top + dp(50f), paint);
        }
        label(c, String.format(java.util.Locale.US, "%.0f%% of the way  ·  expedition %d", p * 100f, expNumber + 1),
                bl, top + dp(56f), 9f, FAINT, Paint.Align.LEFT);
    }

    /**
     * The weir panel: the rate you have to hold, the rate you are holding, and the charge. It only
     * appears when there is a sill to get over, and the charge visibly bleeds back the moment the
     * rate drops - the whole point is that it has to be one burst.
     */
    private void drawWeirPanel(Canvas c, float w, float h) {
        if (weirSeg == null || dir < 0 || weirsDone.contains(weirKey)) {
            return;
        }
        double ahead = weirBase - along;
        if (!(weirBlocking || weirLift > 0 || (ahead > 0 && ahead < 140))) {
            return;
        }
        float pw = dp(460f);
        float left = w / 2f - pw / 2f;
        float top = dp(10f);
        panel(c, left, top, left + pw, top + dp(96f), dp(10f), 0xDD0A121C);
        String title = weirBlocking ? "WEIR  ·  THE BOAT IS HELD"
                : weirLift > 0 ? "OVER!" : "WEIR IN " + Math.round(ahead) + " m";
        bold(c, title, left + dp(14f), top + dp(24f), 16f, weirBlocking ? BAD : WARN, Paint.Align.LEFT);
        label(c, "BURST ABOVE " + weirTargetRate + " spm", left + pw - dp(14f), top + dp(24f), 12f, TEXT,
                Paint.Align.RIGHT);
        // The rate scale, with everything above the target shaded as the water you want.
        float sl = left + dp(14f);
        float sr = left + pw - dp(14f);
        float sy = top + dp(40f);
        float lo = 14f;
        float hi = Math.max(40f, weirTargetRate + 8f);
        bar(c, sl, sy, sr, sy + dp(14f), 0f, 0);
        paint.setColor(0x99F0B132);
        rect.set(sl + (sr - sl) * (weirTargetRate - lo) / (hi - lo), sy, sr, sy + dp(14f));
        c.drawRoundRect(rect, dp(7f), dp(7f), paint);
        float rn = Math.max(lo, Math.min(hi, rateNow));
        float nx = sl + (sr - sl) * (rn - lo) / (hi - lo);
        boolean hard = rateNow >= weirTargetRate;
        paint.setColor(hard ? ACCENT : BAD);
        c.drawRect(nx - dp(2.5f), sy - dp(4f), nx + dp(2.5f), sy + dp(18f), paint);
        label(c, String.format(java.util.Locale.US, "%.1f", rateNow), nx, sy - dp(6f), 10f, hard ? ACCENT : BAD,
                Paint.Align.CENTER);
        // The charge: seconds of burst banked, out of WEIR_SECONDS.
        float cy = top + dp(70f);
        label(c, "LIFT", sl, cy + dp(11f), 10f, DIM, Paint.Align.LEFT);
        float bl = sl + dp(40f);
        float br = sr - dp(60f);
        bar(c, bl, cy, br, cy + dp(14f), weirCharge, weirCharge > 0.66f ? ACCENT : weirCharge > 0.25f ? WARN : BAD);
        if (weirCharge > 0.02f && weirCharge < 1f) {
            // A pip that rides the bar's end, so a charge that is bleeding back is obvious.
            paint.setColor(0xFFE6EDF7);
            c.drawCircle(bl + (br - bl) * weirCharge, cy + dp(7f), dp(4f) + (hard ? dp(2f) : 0f), paint);
        }
        label(c, String.format(java.util.Locale.US, "%.1f s", (1f - weirCharge) * WEIR_SECONDS),
                sr, cy + dp(11f), 11f, TEXT, Paint.Align.RIGHT);
    }

    /**
     * At a destination: the choice between the cruise home and pressing on. Rowing straight past it
     * counts as pressing on, so there is no way to be stuck here.
     */
    private void drawArrivalPanel(Canvas c, float w, float h) {
        if (!expArrived) {
            cruiseBtnR = -1f;
            pressBtnR = -1f;
            return;
        }
        float pop = Math.min(1f, (float) (sessionSeconds - arrivedAt) / 0.35f);
        float pw = dp(620f);
        float ph = dp(190f);
        float left = w / 2f - pw / 2f;
        float top = h * 0.44f - ph / 2f + (1f - pop) * dp(40f);
        panel(c, left, top, left + pw, top + ph, dp(14f), (((int) (0xE8 * pop)) << 24) | 0x000A121C);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xFFF5C518);
        c.drawRoundRect(rect, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, "YOU REACHED " + expName, w / 2f, top + dp(40f), 24f, 0xFFF5C518, Paint.Align.CENTER);
        label(c, "Turn for home with the current, or press on to " + expName + "'s successor upstream.",
                w / 2f, top + dp(66f), 12f, DIM, Paint.Align.CENTER);
        float by = top + dp(90f);
        float bh = dp(64f);
        cruiseBtnL = left + dp(30f);
        cruiseBtnT = by;
        cruiseBtnR = w / 2f - dp(12f);
        cruiseBtnB = by + bh;
        panel(c, cruiseBtnL, cruiseBtnT, cruiseBtnR, cruiseBtnB, dp(10f), 0xFF12313B);
        bold(c, "CRUISE HOME", (cruiseBtnL + cruiseBtnR) / 2f, by + dp(28f), 18f, ACCENT, Paint.Align.CENTER);
        label(c, String.format(java.util.Locale.US, "%.1f km downstream, the current with you",
                        Math.max(0, along - cruiseHome) / 1000.0),
                (cruiseBtnL + cruiseBtnR) / 2f, by + dp(48f), 10f, DIM, Paint.Align.CENTER);
        pressBtnL = w / 2f + dp(12f);
        pressBtnT = by;
        pressBtnR = left + pw - dp(30f);
        pressBtnB = by + bh;
        panel(c, pressBtnL, pressBtnT, pressBtnR, pressBtnB, dp(10f), 0xFF2A2413);
        bold(c, "PRESS ON", (pressBtnL + pressBtnR) / 2f, by + dp(28f), 18f, 0xFFF5C518, Paint.Align.CENTER);
        label(c, "next: " + expName + String.format(java.util.Locale.US, ", %.1f km further",
                        (expTarget - expStart) / 1000.0),
                (pressBtnL + pressBtnR) / 2f, by + dp(48f), 10f, DIM, Paint.Align.CENTER);
    }

    /**
     * Shown from 120 m before rapids to their end: the rate band, where your rate sits in it, how
     * well the line is held, and the rocks cleared.
     */
    private void drawRapidsPanel(Canvas c, float w) {
        if (rapSeg == null) {
            return;
        }
        // Both panels sit top centre. A weir is the nearer problem, so it takes the slot.
        if (weirSeg != null && !weirsDone.contains(weirKey) && weirBase - along < 140) {
            return;
        }
        double ahead = rapBase - along;
        if (!(inRapids || (ahead > 0 && ahead < 120))) {
            return;
        }
        float pw = dp(440f);
        float left = w / 2f - pw / 2f;
        float top = dp(10f);
        panel(c, left, top, left + pw, top + dp(92f), dp(10f), 0xCC0A121C);
        String title = inRapids
                ? "RAPIDS  ·  ROCK " + Math.min(ROCKS, runCleared + runHits + 1) + " OF " + ROCKS
                : "RAPIDS IN " + Math.round(ahead) + " m";
        bold(c, title, left + dp(14f), top + dp(22f), 14f, WARN, Paint.Align.LEFT);
        label(c, "HOLD " + rateLo + "-" + rateHi + " spm", left + pw - dp(14f), top + dp(22f), 12f, TEXT, Paint.Align.RIGHT);
        // Rate scale 14..36 spm with the band shaded and a needle for your rate now.
        float sl = left + dp(14f);
        float sr = left + pw - dp(14f);
        float sy = top + dp(40f);
        float lo = 14f;
        float hi = 36f;
        bar(c, sl, sy, sr, sy + dp(14f), 0f, 0);
        paint.setColor(0x9935D0BA);
        rect.set(sl + (sr - sl) * (rateLo - lo) / (hi - lo), sy, sl + (sr - sl) * (rateHi - lo) / (hi - lo), sy + dp(14f));
        c.drawRoundRect(rect, dp(7f), dp(7f), paint);
        float rn = Math.max(lo, Math.min(hi, rateNow));
        float nx = sl + (sr - sl) * (rn - lo) / (hi - lo);
        boolean inBand = rateNow >= rateLo && rateNow <= rateHi;
        paint.setColor(inBand ? TEXT : BAD);
        c.drawRect(nx - dp(2.5f), sy - dp(4f), nx + dp(2.5f), sy + dp(18f), paint);
        label(c, String.format(java.util.Locale.US, "%.1f", rateNow), nx, sy - dp(6f), 10f, inBand ? TEXT : BAD, Paint.Align.CENTER);
        // The line: how firmly the boat holds the safe water.
        float ly = top + dp(66f);
        label(c, "LINE", sl, ly + dp(10f), 10f, DIM, Paint.Align.LEFT);
        float bl = sl + dp(40f);
        float br = sr - dp(110f);
        bar(c, bl, ly, br, ly + dp(12f), rhythm, rhythm > 0.5f ? ACCENT : rhythm > 0.3f ? WARN : BAD);
        for (int k = 0; k < ROCKS; k++) {
            float dx = br + dp(14f) + k * dp(15f);
            int done = runCleared + runHits;
            paint.setColor(!inRapids || k >= done ? 0xFF3A4656 : (runHitMask & (1 << k)) != 0 ? BAD : ACCENT);
            c.drawCircle(dx, ly + dp(6f), dp(5f), paint);
        }
        if (inRapids && runHits > 0) {
            label(c, runHits + " hit", br + dp(14f) + ROCKS * dp(15f) - dp(8f), ly + dp(24f), 9f, BAD, Paint.Align.RIGHT);
        }
    }

    /** A newly found postcard sails in, pauses, and drops into the album button. */
    private void drawFlyIn(Canvas c, float w, float h) {
        if (flyIn == null) {
            return;
        }
        float t = (float) (sessionSeconds - flyInAt);
        if (t > 4.4f || albumOpen) {
            flyIn = null;
            return;
        }
        float cw = dp(360f);
        float ch = dp(240f);
        float x;
        float y;
        float sc;
        float rot;
        float restX = w / 2f;
        float restY = h * 0.62f;
        if (t < 0.5f) {
            float p = t / 0.5f;
            p = 1 - (1 - p) * (1 - p);
            x = w + cw - (w + cw - restX) * p;
            y = restY;
            sc = 1f;
            rot = 12f * (1 - p) - 3f;
        } else if (t < 3.5f) {
            x = restX;
            y = restY;
            sc = 1f;
            rot = -3f + (float) Math.sin(t * 2) * 1f;
        } else {
            float p = (t - 3.5f) / 0.9f;
            p = p * p;
            x = restX + ((albumBtnL + albumBtnR) / 2f - restX) * p;
            y = restY + ((albumBtnT + albumBtnB) / 2f - restY) * p;
            sc = 1f - 0.9f * p;
            rot = -3f - 20f * p;
        }
        c.save();
        c.translate(x, y);
        c.rotate(rot);
        c.scale(sc, sc);
        drawPostcard(c, 0f, 0f, cw, ch, flyIn);
        c.restore();
    }

    /** The album overlay: which of the eight kinds you have, then the cards, newest first. */
    private void drawAlbum(Canvas c, float w, float h) {
        paint.setColor(0xEE05080C);
        c.drawRect(0, 0, w, h, paint);
        int kinds = 0;
        for (boolean k : kindsFound) {
            if (k) {
                kinds++;
            }
        }
        bold(c, "POSTCARD ALBUM", dp(24f), dp(40f), 24f, 0xFFF5C518, Paint.Align.LEFT);
        label(c, album.size() + " cards  ·  " + kinds + " of " + LANDMARKS.length + " landmarks collected",
                dp(260f), dp(40f), 12f, DIM, Paint.Align.LEFT);
        // The eight kinds, lit when collected.
        float slot = (w - dp(48f)) / LANDMARKS.length;
        for (int i = 0; i < LANDMARKS.length; i++) {
            float cx = dp(24f) + slot * (i + 0.5f);
            rect.set(cx - slot * 0.46f, dp(54f), cx + slot * 0.46f, dp(84f));
            paint.setColor(kindsFound[i] ? 0xFF3A2F12 : 0xFF141C26);
            c.drawRoundRect(rect, dp(6f), dp(6f), paint);
            label(c, kindsFound[i] ? LANDMARKS[i] : "?  " + LANDMARKS[i].charAt(0) + "...", cx, dp(74f), 10f,
                    kindsFound[i] ? 0xFFF5C518 : FAINT, Paint.Align.CENTER);
        }
        int pages = Math.max(1, (album.size() + CARDS_PER_PAGE - 1) / CARDS_PER_PAGE);
        albumPage = Math.min(albumPage, pages - 1);
        if (album.isEmpty()) {
            bold(c, "No postcards yet - row past a landmark to find your first.", w / 2f, h * 0.5f, 16f, TEXT, Paint.Align.CENTER);
        } else {
            float gridTop = dp(100f);
            float gridBottom = h - dp(70f);
            int cols = 4;
            int rows = 2;
            float cellW = (w - dp(48f)) / cols;
            float cellH = (gridBottom - gridTop) / rows;
            float cw = Math.min(cellW * 0.9f, cellH * 0.9f * 1.5f);
            float ch = cw / 1.5f;
            for (int i = 0; i < CARDS_PER_PAGE; i++) {
                int n = album.size() - 1 - (albumPage * CARDS_PER_PAGE + i);
                if (n < 0) {
                    break;
                }
                float cx = dp(24f) + cellW * (i % cols + 0.5f);
                float cy = gridTop + cellH * (i / cols + 0.5f);
                c.save();
                c.translate(cx, cy);
                c.rotate(((n * 37) % 7) - 3f);
                drawPostcard(c, 0f, 0f, cw, ch, album.get(n));
                c.restore();
            }
        }
        float ny = h - dp(28f);
        label(c, pages > 1 ? "◀  PREV" : "", dp(40f), ny, 14f, TEXT, Paint.Align.LEFT);
        label(c, "page " + (albumPage + 1) + " of " + pages + "  ·  tap to close", w / 2f, ny, 12f, DIM, Paint.Align.CENTER);
        label(c, pages > 1 ? "NEXT  ▶" : "", w - dp(40f), ny, 14f, TEXT, Paint.Align.RIGHT);
    }

    /** One postcard centred on (cx, cy): a painted view in the light it was found in, and a caption. */
    private void drawPostcard(Canvas c, float cx, float cy, float cw, float ch, Postcard p) {
        float l = cx - cw / 2f;
        float t = cy - ch / 2f;
        paint.setColor(0x66000000);
        c.drawRect(l + dp(4f), t + dp(5f), l + cw + dp(4f), t + ch + dp(5f), paint);
        paint.setColor(0xFFF4EBD8);
        c.drawRect(l, t, l + cw, t + ch, paint);
        float pad = cw * 0.045f;
        float pl = l + pad;
        float pt = t + pad;
        float pr = l + cw - pad;
        float pb = t + ch * 0.70f;
        int top = KF_TOP[p.phase];
        int bot = KF_BOT[p.phase];
        float horizon = pt + (pb - pt) * 0.5f;
        for (int b = 0; b < 4; b++) {
            paint.setColor(blend(top, bot, b / 3f));
            c.drawRect(pl, pt + (horizon - pt) * b / 4f, pr, pt + (horizon - pt) * (b + 1) / 4f + 1, paint);
        }
        float saved = litLevel;
        litLevel = Math.max(0.35f, KF_LIGHT[p.phase]);
        paint.setColor(lit(0xFF4E6B5C));
        c.drawRect(pl, horizon - (pb - pt) * 0.06f, pr, horizon, paint);
        paint.setColor(lit(0xFF4F8A3C));
        c.drawRect(pl, horizon, pr, pb, paint);
        paint.setColor(lit(0xFF2F6E93));
        path.reset();
        float mid = (pl + pr) / 2f;
        path.moveTo(mid - (pr - pl) * 0.08f, horizon);
        path.lineTo(mid + (pr - pl) * 0.08f, horizon);
        path.lineTo(mid + (pr - pl) * 0.45f, pb);
        path.lineTo(mid - (pr - pl) * 0.45f, pb);
        path.close();
        c.drawPath(path, paint);
        c.save();
        c.clipRect(pl, pt, pr, pb);
        float u = (pb - pt) / 14f;
        float sy = horizon + (pb - horizon) * 0.55f;
        switch (p.type) {
            case 0:
                drawHeron(c, mid + (pr - pl) * 0.18f, sy, u * 1.6f, 0f, false, 0f);
                break;
            case 5:
                for (int o = 0; o < 3; o++) {
                    drawOtter(c, mid - u * 2f + o * u * 2.2f, sy + u * 0.8f, u * 1.4f, o == 1 ? 0.2f : 0.1f + o * 0.2f, o == 1, 1f);
                }
                break;
            default:
                drawLandmarkShape(c, p.type, mid + (pr - pl) * 0.25f, mid, sy, u, -1f, (pr - pl) * 0.3f);
                break;
        }
        c.restore();
        litLevel = saved;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0xFFBFB29A);
        c.drawRect(pl, pt, pr, pb, paint);
        paint.setStyle(Paint.Style.FILL);
        // A stamp in the corner.
        paint.setColor(0xFFD8453C);
        c.drawRect(pr - cw * 0.12f, pt + dp(4f), pr - dp(4f), pt + cw * 0.12f, paint);
        float text = ch / dp(240f);
        bold(c, LANDMARKS[p.type], pl, pb + ch * 0.12f, 17f * text, 0xFF3A2A1A, Paint.Align.LEFT);
        label(c, p.reach + "  ·  " + KF_NAME[p.phase].toLowerCase(java.util.Locale.US), pl, pb + ch * 0.20f, 11f * text,
                0xFF5A4A3A, Paint.Align.LEFT);
        label(c, p.caption, pl, pb + ch * 0.27f, 10f * text, 0xFF7A6A5A, Paint.Align.LEFT);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
