package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Skyline: you row, a city gets built.
 *
 * <p>Power buys concrete; when enough has accumulated a block is craned in over the lowest plot
 * and falls, at a speed set by how hard you are pulling. Blocks stack into towers, towers gain
 * windows that light as evening comes in, and the whole thing is drawn in isometric with the
 * camera panning slowly around it so you see the skyline from every side.
 *
 * <p>No fail state on purpose - this is the one to row to when a chase would be exhausting. The
 * city is persistent: every block you have ever placed is still standing.
 *
 * <p>3.15.0, the block factory: each drive fills the crane's hopper, and a strong stroke - 10% or
 * 30% above the rower's typical power - drops a two- or three-floor block at a discount, so pulling
 * hard visibly builds faster. At night the city's windows light up in proportion to the energy put
 * in this session, and a "today" tower on the right grows with this session's floors.
 *
 * <p>Towers are drawn as single extruded prisms rather than stacks of cubes: 25 plots of up to 14
 * blocks would be 350 sorted quads a frame, where 25 prisms is nothing.
 *
 * <p>3.20, five additions the rower approved:
 * <ul>
 *   <li><b>Neighbourhoods.</b> A park opens at {@link #PARK_AT} lifetime blocks (lawn, pond with a
 *   fountain, trees that grow in, lamps, strollers) and a river with a suspension bridge at
 *   {@link #BRIDGE_AT} (cars crossing, walkers, and a rowing shell on the water that moves with the
 *   rower's own metres and strokes). A rower already past a milestone gets the opening ceremony once,
 *   tracked by {@code city.districts}.</li>
 *   <li><b>Night lights.</b> After dusk each tower switches on in a staggered wave: floors built this
 *   session burn gold, older floors light by session energy, roofs get a lit crown and a glow.</li>
 *   <li><b>Residents.</b> Every block that lands sends a mover with a box walking in from the street,
 *   the park or over the bridge to that tower's door; on arrival the population rises and the door
 *   glows. Walkers faster when you row harder.</li>
 *   <li><b>Weekly landmark.</b> One of four (clock tower, ferris wheel, lighthouse, observatory) per
 *   Monday-start week rises on the plaza, built from this week's rowing work across sessions;
 *   target is 40 minutes at the rower's typical watts, hard strokes lay a gold stone bonus.</li>
 *   <li><b>Photo mode.</b> Hides the HUD, stops the auto-pan, drag to turn the city, shutter renders
 *   the city at half resolution with a caption into the app's own files and an in-game album.</li>
 * </ul>
 *
 * <p>3.23, five more the rower approved. The theme is that the city now asks things of the rower
 * rather than only accepting whatever they give it:
 * <ul>
 *   <li><b>The citizens' request.</b> A petitioner walks in from the street with a placard and asks
 *   for one building - a hospital, a school, a library. Grant it by putting up the floors they ask
 *   for in this session (about 75 s of the rower's own typical rowing, half again for each one
 *   after) and the building is real: a civic sign that stays over that tower for good, counted in
 *   {@code city.civic} and in the rating.</li>
 *   <li><b>Rush hour.</b> Two minutes of real time and a floor count the rower starts themselves
 *   from the HUD, set at {@link #RUSH_DEMAND} of their typical watts, with a pace line that is
 *   either ahead of you or behind you this second. Cleared runs bank {@code city.rushwins} and the
 *   best haul {@code city.rush}.</li>
 *   <li><b>The city rating.</b> Everything ever done here in one score, five tiers, and each one
 *   buys a way to clad a tower - brick and tile, glass, spires, golden crowns - and four more
 *   floors of height limit, so a city that has filled its grid has somewhere to go again. Towers
 *   carry whatever tier they last grew at, so an old city reads as layers of its own history.</li>
 *   <li><b>This week against last.</b> Floors are banked per Monday-start week ({@code
 *   city.week.*}) and drawn as two stacks that grow while you row, with last week's line across
 *   this week's column and a banner the moment you pass it.</li>
 *   <li><b>Storms and repairs.</b> A front rolls in every four minutes of the rowing clock: cloud,
 *   rain, and a bolt every few seconds at the tallest towers. The city's shield is powered by the
 *   rower's live watts across their own low-to-high band, so the ten seconds before a strike
 *   genuinely matter. What gets through breaks floors, and while anything is broken every
 *   watt-second goes into repairs and the skyline does not grow at all.</li>
 * </ul>
 */
final class SkylineGame extends GameView {

    private static final int GRID = 5;
    private static final int MAX_HEIGHT = 14;
    /** Watt-seconds per block. About one block every few strokes at a steady 130 W. */
    private static final float BLOCK_COST = 260f;

    /**
     * Lifetime blocks at which the neighbourhoods open. At the median 129 W a floor lands about every
     * two seconds of rowing, so the park is two or three sessions' work for a new city and the
     * bridge roughly the halfway point of the 350-floor grid.
     */
    private static final int PARK_AT = 60;
    private static final int BRIDGE_AT = 180;
    /** Minutes of rowing at the rower's typical watts that one weekly landmark takes. */
    private static final double LANDMARK_MINUTES = 40.0;

    private static final String[] LANDMARK_NAMES = {
            "CLOCK TOWER", "FERRIS WHEEL", "LIGHTHOUSE", "OBSERVATORY",
    };
    /** Landmark heights in tile widths. */
    private static final float[] LANDMARK_H = {4.4f, 4.6f, 4.4f, 3.0f};
    private static final float PLAZA_X = 5.4f;
    private static final float PLAZA_Y = 2.0f;
    private static final float BRIDGE_X = 2.0f;

    /** Trees: the first six stand in the park, the rest on the river's far bank. */
    private static final float[] TREE_X = {-1.8f, -1.6f, -1.85f, -1.55f, -0.8f, -0.8f,
            -0.2f, 0.9f, 3.3f, 4.4f, 5.6f};
    private static final float[] TREE_Y = {0.1f, 1.1f, 3.2f, 4.1f, 2.6f, 4.3f,
            6.1f, 6.15f, 6.1f, 6.15f, 6.1f};
    private static final int PARK_TREES = 6;
    private static final float[] LAMP_Y = {1.0f, 3.6f};

    private static final int K_TOWER = 0;
    private static final int K_TREE = 1;
    private static final int K_LAMP = 2;
    private static final int K_FOUNTAIN = 3;
    private static final int K_LANDMARK = 4;
    private static final int K_BRIDGE = 5;
    private static final int K_SHELL = 6;
    private static final int K_RESIDENT = 7;

    private static final int R_MOVER = 0;
    private static final int R_PARK = 1;
    private static final int R_BRIDGE = 2;
    private static final int R_PLAZA = 3;
    private static final int R_PETITION = 4;

    /* ------------------------------------------------------------------ 3.22, five more ------- */

    /**
     * Watt-seconds to repair one storm-damaged floor. Deliberately dearer than building it fresh
     * (1.2x {@link #BLOCK_COST}): a storm that took eight floors is about half a minute of rowing
     * back, which is the whole point of it - the city asks for strokes it had not asked for.
     */
    private static final float REPAIR_COST = BLOCK_COST * 1.2f;
    /** A rush hour runs two minutes of real time, running clock or not. That is the stake. */
    private static final float RUSH_SECONDS = 120f;
    /** Floors asked for in a rush hour, as a multiple of what the rower's typical watts would lay. */
    private static final float RUSH_DEMAND = 1.12f;

    private static final String[] RATING_NAMES = {
            "OUTPOST", "TOWN", "CITY", "METROPOLIS", "MEGACITY",
    };
    /** What each rating tier unlocks, in the order the city learns to build it. */
    private static final String[] STYLE_NAMES = {
            "CONCRETE BLOCKS", "BRICK AND TILE", "GLASS TOWERS", "SPIRES", "GOLDEN CROWNS",
    };
    /** Rating score at which each tier begins. Tier 1 lands with the park, so they arrive together. */
    private static final float[] RATING_AT = {0f, 90f, 260f, 560f, 1000f};

    private static final String[] CIVIC_NAMES = {
            "HOSPITAL", "SCHOOL", "LIBRARY", "MARKET", "FIRE STATION", "THEATRE", "CLINIC", "MUSEUM",
    };
    private static final int[] CIVIC_COLORS = {
            0xFFE0584E, 0xFF35D0BA, 0xFF6F8CFF, 0xFFF0B132,
            0xFFE0582E, 0xFFE06BA8, 0xFF8BD05A, 0xFFB98CFF,
    };

    private static final int STORM_NONE = 0;
    private static final int STORM_WARNING = 1;
    private static final int STORM_OVERHEAD = 2;
    private static final int STORM_CLEARING = 3;

    private static final class Falling {
        int gx;
        int gy;
        float z;          // current height in block units
        float target;
        int color;
        int units = 1;    // floors in this block
    }

    private static final class Resident {
        boolean active;
        int kind;
        float x;
        float y;
        final float[] wx = new float[4];
        final float[] wy = new float[4];
        int legs;
        int leg;
        float wait;
        float speed;
        float phase;
        int shirt;
        int skin;
        int plotX;
        int plotY;
        int family;
    }

    private static final class Photo {
        File file;
        Bitmap thumb;
        Bitmap full;
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final java.util.Random rng = new java.util.Random();

    private final int[][] height = new int[GRID][GRID];
    private final int[][] tint = new int[GRID][GRID];
    /** Floors on each plot that were built this session: they burn gold after dark. */
    private final int[][] sessionFloors = new int[GRID][GRID];
    /** A warm glow at a tower's door when a family has just moved in, decaying to 0. */
    private final float[][] doorGlow = new float[GRID][GRID];
    private final java.util.List<Falling> falling = new java.util.ArrayList<>();
    private final long[] windowSeed = new long[GRID * GRID];
    private final Resident[] residents = new Resident[18];

    private float angle;
    private float concrete;
    private int placedThisSession;
    private int lifetime;
    private int tallest;
    /** Floors the next block will have, from the last stroke's power. */
    private int nextUnits = 1;
    private PulseMeter.Stroke lastStrokeSeen;
    private double workAtStart = -1;
    /** Share of windows lit tonight: the energy put in this session. */
    private float litShare;
    private int population;

    // Neighbourhoods.
    private boolean parkOpen;
    private boolean bridgeOpen;
    private float parkReveal;
    private float bridgeReveal;
    private int districtsSeen;
    private float ceremonyDelay;
    private String banner;
    private float bannerT;

    // Weekly landmark.
    private long weekIndex;
    private int landmarkKind;
    private double landmarkWork;
    private double landmarkTarget = 1;
    private boolean landmarkDone;
    private int landmarksBuilt;
    private boolean stoneFlash;
    private float wheelAngle;
    private float beamAngle;
    private float hourAngle;
    private float minuteAngle;
    private double clockUpdatedAt = -10;
    private java.util.TimeZone zone = java.util.TimeZone.getDefault();

    // Geometry for this frame.
    private float gCx;
    private float gCy;
    private float gTw;
    private float gTh;
    private float gBh;
    private float gCos;
    private float gSin;
    private float ctrX;
    private float ctrY;
    private float minX;
    private float maxX;
    private float maxY;
    private float px;
    private float py;
    private int lowX;
    private int lowY;
    private float shellX;

    // Depth sort, preallocated.
    private final int[] itemKind = new int[80];
    private final int[] itemRef = new int[80];
    private final float[] itemDepth = new float[80];
    private int itemCount;

    // Cached sky.
    private LinearGradient skyShader;
    private int skyKey = -1;

    // Photo mode.
    private boolean photoMode;
    private final java.util.ArrayList<Photo> album = new java.util.ArrayList<>();
    private boolean albumLoaded;
    private Bitmap viewing;
    private float flash;
    private String photoNote;
    private float photoNoteT;
    private final RectF photoBtn = new RectF();
    private final RectF doneBtn = new RectF();
    private final RectF viewRect = new RectF();
    private final RectF[] thumbRects = new RectF[6];
    private float snapX;
    private float snapY;
    private float snapR;
    private float downX;
    private float lastTouchX;
    private boolean dragging;

    // ---- 1. the citizens' request -------------------------------------------------------------
    /** -1 on a plot with no civic building, else an index into {@link #CIVIC_NAMES}. */
    private final int[][] civic = new int[GRID][GRID];
    private boolean requestOpen;
    private int requestKind;
    private int requestNeed;
    private int requestBase;
    private int requestsThisSession;
    private int civicCount;
    private float requestCooldown;
    private float requestPulse;
    private int petitioner = -1;

    // ---- 2. rush hour --------------------------------------------------------------------------
    private boolean rushRunning;
    private float rushLeft;
    private int rushTarget;
    private int rushBase;
    private int rushWins;
    private int rushBestFloors;
    private float rushCooldown;
    private float rushTint;
    private final RectF rushBtn = new RectF();

    // ---- 3. the city rating --------------------------------------------------------------------
    /** Build style per plot: the tier the city could build at when that tower last grew. */
    private final int[][] style = new int[GRID][GRID];
    private float ratingScore;
    private int ratingTier;
    private int bestTier;
    private float ratingShown;
    private float ratingFlash;

    // ---- 4. this week against last -------------------------------------------------------------
    private int weekFloorsAtStart;
    private int weekFloors;
    private int lastWeekFloors;
    private float weekBarA;
    private float weekBarB;
    private boolean passedLastWeek;

    // ---- 5. the storm and the repairs ----------------------------------------------------------
    private int stormPhase;
    private float stormT;
    private double nextStormAt = 170;
    private float stormIntensity;
    private float strikeIn;
    private float shield;
    private float boltT;
    private int boltPlotX;
    private int boltPlotY;
    private boolean boltDeflected;
    private final float[] boltJag = new float[7];
    private final float[] rainX = new float[70];
    private final float[] rainY = new float[70];
    private final float[] rainV = new float[70];
    private boolean rainSeeded;
    /** Broken floors sitting at the top of each plot's stack, waiting on concrete. */
    private final int[][] damage = new int[GRID][GRID];
    private final float[][] repairFlash = new float[GRID][GRID];
    private int damageTotal;
    private int repairsLifetime;
    private int repairsThisSession;
    private int stormsWeathered;
    private String stormNote;
    private float stormNoteT;
    private int wattsNow;

    private static final int[] PALETTE = {
            0xFF4C6EA8, 0xFF3E8C7E, 0xFF8A6BB0, 0xFF9A6B4F, 0xFF5E7A90, 0xFF7A8A4F,
    };
    private static final int[] SHIRTS = {
            0xFFE0582E, 0xFF35D0BA, 0xFF6F8CFF, 0xFFF0B132, 0xFFE06BA8, 0xFF8BD05A, 0xFFE6EDF7,
    };
    private static final int[] SKINS = {0xFFF1C27D, 0xFFC68642, 0xFF8D5524, 0xFFFFDBAC};

    SkylineGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        java.util.Random r = new java.util.Random(31);
        for (int i = 0; i < windowSeed.length; i++) {
            windowSeed[i] = r.nextLong();
        }
        for (int i = 0; i < residents.length; i++) {
            residents[i] = new Resident();
        }
        for (int i = 0; i < thumbRects.length; i++) {
            thumbRects[i] = new RectF();
        }
        paint.setFilterBitmap(true);
    }

    @Override
    protected void onStart() {
        concrete = 0f;
        placedThisSession = 0;
        nextUnits = 1;
        workAtStart = -1;
        litShare = 0f;
        falling.clear();
        angle = 0.6f;
        photoMode = false;
        viewing = null;
        banner = null;
        bannerT = 0f;
        zone = java.util.TimeZone.getDefault();
        lifetime = Math.round(bests.get("city.blocks", 0f));

        // Everything the rating is made of has to be loaded before the rebuild, because the rating
        // sets how tall a tower this city is allowed to raise.
        districtsSeen = Math.round(bests.get("city.districts", 0f));
        landmarksBuilt = Math.round(bests.get("city.landmarks", 0f));
        civicCount = Math.round(bests.get("city.civic", 0f));
        rushWins = Math.round(bests.get("city.rushwins", 0f));
        rushBestFloors = Math.round(bests.get("city.rush", 0f));
        repairsLifetime = Math.round(bests.get("city.repairs", 0f));
        stormsWeathered = Math.round(bests.get("city.storms", 0f));
        // Clamped: every tier name, style name and threshold is read by this index, so a stored
        // value outside 1..5 (an older build, a hand-edited pref) would be an out-of-bounds read
        // on the very first frame rather than a wrong star count.
        bestTier = Math.max(0, Math.min(RATING_NAMES.length - 1,
                Math.round(bests.get("city.rating", 1f)) - 1));
        ratingTier = bestTier;
        population = lifetime * 4;
        damageTotal = 0;
        repairsThisSession = 0;
        requestsThisSession = 0;
        recomputeRating();
        ratingShown = ratingTier;

        // Rebuild the standing city from the lifetime total, filling plots evenly.
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                height[gx][gy] = 0;
                sessionFloors[gx][gy] = 0;
                doorGlow[gx][gy] = 0f;
                damage[gx][gy] = 0;
                repairFlash[gx][gy] = 0f;
                civic[gx][gy] = -1;
                tint[gx][gy] = PALETTE[(gx * GRID + gy) % PALETTE.length];
                // An old city is a mixture: each plot was clad at whatever tier it last grew at.
                // Unsigned shift, not Math.abs: abs(Long.MIN_VALUE) is still negative, which would
                // have put a negative index in style[][] and quietly dropped that tower's cladding.
                style[gx][gy] = (int) ((windowSeed[gx * GRID + gy] >>> 8) % (ratingTier + 1));
            }
        }
        for (int i = 0; i < lifetime && i < GRID * GRID * maxHeight(); i++) {
            lowestPlot();
            height[lowX][lowY]++;
        }
        recomputeTallest();
        assignCivic();

        // Districts: open silently what has already been celebrated, stage a ceremony for the rest.
        parkOpen = lifetime >= PARK_AT && districtsSeen >= 1;
        bridgeOpen = lifetime >= BRIDGE_AT && districtsSeen >= 2;
        parkReveal = parkOpen ? 1f : 0f;
        bridgeReveal = bridgeOpen ? 1f : 0f;
        ceremonyDelay = 1.5f;

        // This week's landmark.
        long now = System.currentTimeMillis();
        long localDays = (now + zone.getOffset(now)) / 86400000L;
        weekIndex = (localDays + 3) / 7;   // 1970-01-01 was a Thursday: weeks start on Monday
        landmarkKind = (int) (weekIndex % LANDMARK_NAMES.length);
        landmarkTarget = Math.max(1.0, profile.typicalWatts() * 60.0 * LANDMARK_MINUTES);
        if (Math.round(bests.get("city.landmark.week", -1f)) == weekIndex) {
            landmarkWork = bests.get("city.landmark.work", 0f);
        } else {
            landmarkWork = 0;
        }
        landmarkDone = landmarkWork >= landmarkTarget;

        loadWeek();

        // The citizens' request waits for the first stroke, so the card is not already ticking while
        // the rower is still settling onto the seat.
        requestOpen = false;
        requestCooldown = 0f;
        petitioner = -1;
        rushRunning = false;
        rushCooldown = 0f;
        rushTint = 0f;
        stormPhase = STORM_NONE;
        stormIntensity = 0f;
        stormNote = null;
        stormNoteT = 0f;
        boltT = 0f;
        shield = 0f;
        wattsNow = 0;
        // First storm at about three minutes of rowing, then one every four.
        nextStormAt = 170;

        for (Resident r : residents) {
            r.active = false;
        }
        for (int i = 0; i < 2; i++) {
            spawnResident(R_PLAZA);
        }
        if (parkOpen) {
            spawnStrollers(R_PARK, 3);
        }
        if (bridgeOpen) {
            spawnStrollers(R_BRIDGE, 2);
        }
    }

    @Override
    protected void onStop() {
        bests.recordHighest("city.blocks", lifetime);
        bests.recordHighest("city.tallest", tallest);
        saveLandmark();
        saveWeek();
        bests.recordHighest("city.civic", civicCount);
        bests.recordHighest("city.rating", ratingTier + 1);
        bests.recordHighest("city.rushwins", rushWins);
        bests.recordHighest("city.rush", rushBestFloors);
        bests.recordHighest("city.repairs", repairsLifetime + repairsThisSession);
        bests.recordHighest("city.storms", stormsWeathered);
    }

    private void saveLandmark() {
        bests.putFloat("city.landmark.week", weekIndex);
        bests.putFloat("city.landmark.work", (float) landmarkWork);
    }

    /* ---------------------------------------------------------------- rating ---------- */

    /**
     * The city's rating: everything the rower has ever done here, in one number. It buys two things
     * that are visible immediately - a new way to clad a tower, and permission to build higher, so
     * an established city never runs out of plots to fill.
     */
    private void recomputeRating() {
        ratingScore = lifetime + population / 8f + landmarksBuilt * 40f + civicCount * 25f
                + districtsSeen * 30f + rushWins * 20f - damageTotal * 3f;
        int t = 0;
        for (int i = RATING_AT.length - 1; i >= 0; i--) {
            if (ratingScore >= RATING_AT[i]) {
                t = i;
                break;
            }
        }
        // A rating is a lifetime achievement and must never fall. Storm damage subtracts from the
        // score, and a tier lost mid-storm would take four floors of height limit with it: every
        // plot would then be over the limit, lowestPlot() would find nothing, and the rower's
        // strokes would pour into a city with nowhere to put them. It also re-clads towers at the
        // lower tier and re-fires the unlock banner once the repairs are done.
        if (t < bestTier) {
            t = bestTier;
        }
        if (t > ratingTier) {
            ratingTier = t;
            if (isRunning() && hasClockStarted()) {
                showBanner(RATING_NAMES[t] + " - " + STYLE_NAMES[t] + " UNLOCKED");
                ratingFlash = 1.6f;
                shake.kick(dp(5f));
                for (int k = 0; k < 4; k++) {
                    fx.burst(getWidth() * (0.3f + k * 0.14f), getHeight() * 0.35f, 18, dp(170f), 1.1f,
                            dp(3f), k % 2 == 0 ? 0xFFFFD24A : ACCENT, true);
                }
            }
        } else {
            ratingTier = t;
        }
        if (ratingTier > bestTier) {
            bestTier = ratingTier;
            bests.recordHighest("city.rating", ratingTier + 1);
        }
    }

    /** Floors a tower may reach: four more per rating tier, so the grid grows with the city. */
    private int maxHeight() {
        return MAX_HEIGHT + ratingTier * 4;
    }

    /** Score still to go before the next tier, or -1 at the top. */
    private float ratingToNext() {
        if (ratingTier >= RATING_AT.length - 1) {
            return -1f;
        }
        return RATING_AT[ratingTier + 1] - ratingScore;
    }

    /** Marks the tallest plots as the civic buildings the citizens have asked for over time. */
    private void assignCivic() {
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                civic[gx][gy] = -1;
            }
        }
        for (int n = 0; n < civicCount && n < CIVIC_NAMES.length; n++) {
            int bx = -1;
            int by = -1;
            int best = 0;
            for (int gx = 0; gx < GRID; gx++) {
                for (int gy = 0; gy < GRID; gy++) {
                    if (civic[gx][gy] < 0 && height[gx][gy] > best) {
                        best = height[gx][gy];
                        bx = gx;
                        by = gy;
                    }
                }
            }
            if (bx < 0) {
                return;
            }
            civic[bx][by] = n % CIVIC_NAMES.length;
        }
    }

    /* ---------------------------------------------------------------- the week ---------- */

    private void loadWeek() {
        long stored = Math.round(bests.get("city.week.index", -1f));
        weekFloors = Math.round(bests.get("city.week.floors", 0f));
        lastWeekFloors = Math.round(bests.get("city.week.last", 0f));
        if (stored != weekIndex) {
            // A new week: last week's column is whatever the previous one finished on.
            lastWeekFloors = stored == weekIndex - 1 ? weekFloors : 0;
            weekFloors = 0;
            saveWeek();
        }
        weekFloorsAtStart = weekFloors;
        passedLastWeek = lastWeekFloors > 0 && weekFloors >= lastWeekFloors;
        weekBarA = weekFloors;
        weekBarB = lastWeekFloors;
    }

    private void saveWeek() {
        bests.putFloat("city.week.index", weekIndex);
        bests.putFloat("city.week.floors", weekFloors);
        bests.putFloat("city.week.last", lastWeekFloors);
    }

    /** Sets {@link #lowX}/{@link #lowY} to the plot the next block should go on. */
    private void lowestPlot() {
        int best = Integer.MAX_VALUE;
        lowX = 0;
        lowY = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                int hh = height[gx][gy] + pendingFor(gx, gy);
                // Centre plots are favoured slightly so the skyline peaks in the middle.
                int bias = Math.abs(gx - GRID / 2) + Math.abs(gy - GRID / 2);
                int score = hh * 4 + bias;
                if (hh < maxHeight() && score < best) {
                    best = score;
                    lowX = gx;
                    lowY = gy;
                }
            }
        }
    }

    private int pendingFor(int gx, int gy) {
        int n = 0;
        for (int i = 0; i < falling.size(); i++) {
            Falling f = falling.get(i);
            if (f.gx == gx && f.gy == gy) {
                n += f.units;
            }
        }
        return n;
    }

    /** Watt-seconds for a block of this many floors: bigger blocks are cheaper per floor. */
    private static float cost(int units) {
        return BLOCK_COST * (1f + 0.75f * (units - 1));
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (workAtStart < 0) {
            workAtStart = s.meter.workJoules;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastStrokeSeen) {
            lastStrokeSeen = stroke;
            double power = !Double.isNaN(stroke.averagePowerW) ? stroke.averagePowerW : s.watts;
            double ratio = power / Math.max(1.0, profile.typicalWatts());
            nextUnits = ratio >= 1.3 ? 3 : ratio >= 1.1 ? 2 : 1;
            // A hard stroke lays a gold stone on this week's landmark: 1.5 s of its own power extra.
            if (ratio >= 1.1 && !landmarkDone && isClockRunning()) {
                landmarkWork += power * 1.5;
                stoneFlash = true;
            }
        }
        // Ten minutes of typical work lights every window.
        double work = Math.max(0, s.meter.workJoules - Math.max(0, workAtStart));
        litShare = (float) Math.min(1.0, work / Math.max(1.0, profile.typicalWatts() * 600.0));
        // Power for the storm shield is read here, never in onStroke: by the time a stroke counts,
        // instantaneous watts have already collapsed toward zero.
        wattsNow = s.watts;
    }

    private void recomputeTallest() {
        tallest = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                tallest = Math.max(tallest, height[gx][gy]);
            }
        }
    }

    /* ---------------------------------------------------------------- frame ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        step(dt, w, h);

        c.save();
        if (!photoMode) {
            c.translate(shake.dx, shake.dy);
        }
        drawWorld(c, w, h, false);
        c.restore();

        if (photoMode) {
            drawPhotoUi(c, w, h);
        } else {
            drawHud(c, w, h);
        }
        drawBanner(c, w, h);
        if (flash > 0f) {
            paint.setColor(((int) (Math.min(1f, flash) * 230) << 24) | 0xFFFFFF);
            c.drawRect(0, 0, w, h, paint);
        }
        if (viewing != null) {
            drawViewer(c, w, h);
        }
    }

    private void step(float dt, float w, float h) {
        int watts = status == null ? 0 : status.watts;
        float speed = boat.value();

        // The camera pans a little faster while you are working, so effort feels like momentum.
        // Photo mode holds it still so the rower can frame the shot by dragging.
        if (!photoMode) {
            angle += dt * (0.10f + speed * 0.035f);
        }
        shake.step(dt);
        fx.step(dt, dp(220f));
        flash = Math.max(0f, flash - dt * 3f);
        photoNoteT = Math.max(0f, photoNoteT - dt);
        bannerT = Math.max(0f, bannerT - dt);

        // Build whenever the rowing clock runs. The first version needed `driving`, which is only true
        // briefly after each speed reading changes - on the tablet the crane said "idle" mid-row and
        // placed nothing.
        boolean building = isClockRunning() && watts > 0;
        if (building) {
            concrete += watts * dt;
            // Repairs come first: while anything is broken every stroke goes into putting it back,
            // and the skyline does not grow an inch until the city is whole again.
            while (damageTotal > 0 && concrete >= REPAIR_COST) {
                concrete -= REPAIR_COST;
                repairOne();
            }
            while (damageTotal == 0 && concrete >= cost(nextUnits)) {
                lowestPlot();
                int room = maxHeight() - height[lowX][lowY] - pendingFor(lowX, lowY);
                int units = Math.min(nextUnits, room);
                concrete -= cost(Math.max(1, units));
                if (units > 0) {
                    Falling f = new Falling();
                    f.gx = lowX;
                    f.gy = lowY;
                    f.target = height[lowX][lowY] + pendingFor(lowX, lowY);
                    f.z = f.target + 9f;
                    f.color = tint[lowX][lowY];
                    f.units = units;
                    falling.add(f);
                }
            }
            if (!landmarkDone) {
                landmarkWork += watts * dt;
            }
        }

        computeGeometry(w, h);

        // Falling blocks, and landings.
        for (int i = falling.size() - 1; i >= 0; i--) {
            Falling f = falling.get(i);
            float fall = 4.5f + speed * 2.6f;       // rowing harder brings them down faster
            f.z -= fall * dt;
            if (f.z <= f.target) {
                int before = lifetime;
                height[f.gx][f.gy] = Math.min(maxHeight(), height[f.gx][f.gy] + f.units);
                sessionFloors[f.gx][f.gy] += f.units;
                lifetime += f.units;
                placedThisSession += f.units;
                // A tower that grows is re-clad in whatever the city can build today.
                style[f.gx][f.gy] = ratingTier;
                weekFloors = weekFloorsAtStart + placedThisSession;
                recomputeTallest();
                falling.remove(i);
                project(f.gx, f.gy, f.target + 1);
                fx.burst(px, py, 14, dp(90f), 0.45f, dp(3f), 0xCCD8C9A8, true);
                shake.kick(dp(2.5f));
                spawnMover(f.gx, f.gy, f.units);
                if (before < PARK_AT && lifetime >= PARK_AT) {
                    ceremonyDelay = Math.min(ceremonyDelay, 0.4f);
                }
                if (before < BRIDGE_AT && lifetime >= BRIDGE_AT) {
                    ceremonyDelay = Math.min(ceremonyDelay, 0.4f);
                }
            }
        }

        // District opening ceremonies, one at a time.
        ceremonyDelay -= dt;
        if (ceremonyDelay <= 0f) {
            if (!parkOpen && lifetime >= PARK_AT) {
                openPark();
                ceremonyDelay = 4.5f;
            } else if (parkOpen && !bridgeOpen && lifetime >= BRIDGE_AT) {
                openBridge();
                ceremonyDelay = 4.5f;
            } else {
                ceremonyDelay = 1f;
            }
        }
        if (parkOpen) {
            parkReveal = Math.min(1f, parkReveal + dt / 2.5f);
        }
        if (bridgeOpen) {
            bridgeReveal = Math.min(1f, bridgeReveal + dt / 3f);
        }

        // Landmark.
        if (!landmarkDone && landmarkWork >= landmarkTarget) {
            landmarkDone = true;
            landmarksBuilt++;
            bests.recordHighest("city.landmarks", landmarksBuilt);
            saveLandmark();
            showBanner(LANDMARK_NAMES[landmarkKind] + " COMPLETE");
            project(PLAZA_X, PLAZA_Y, 0f);
            float top = py - gTw * LANDMARK_H[landmarkKind];
            for (int k = 0; k < 5; k++) {
                fx.burst(px + (k - 2) * gTw * 0.6f, top + (k % 2) * gTw * 0.4f, 22, dp(170f), 1.1f,
                        dp(3f), SHIRTS[k % SHIRTS.length], true);
            }
            shake.kick(dp(7f));
        }
        if (stoneFlash) {
            stoneFlash = false;
            project(PLAZA_X, PLAZA_Y, 0f);
            float top = py - gTw * 0.12f - (gTw * LANDMARK_H[landmarkKind] - gTw * 0.12f)
                    * landmarkFraction();
            fx.burst(px, top, 12, dp(70f), 0.6f, dp(2.5f), 0xFFFFD24A, true);
        }
        if (landmarkDone) {
            wheelAngle += dt * (0.15f + speed * 0.09f);
        }
        beamAngle += dt * 1.1f;
        if (sessionSeconds - clockUpdatedAt >= 1.0) {
            clockUpdatedAt = sessionSeconds;
            long now = System.currentTimeMillis();
            long local = now + zone.getOffset(now);
            float minutes = (local / 60000L) % 60 + ((local / 1000L) % 60) / 60f;
            float hours = (local / 3600000L) % 12 + minutes / 60f;
            minuteAngle = (float) (minutes / 60.0 * Math.PI * 2);
            hourAngle = (float) (hours / 12.0 * Math.PI * 2);
        }

        // The rower's shell on the river covers the metres actually rowed.
        float span = Math.max(1f, (maxX - minX) - 0.8f);
        shellX = minX + 0.4f + (float) ((sessionMeters * 0.03) % span);

        stepResidents(dt, speed);
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                if (doorGlow[gx][gy] > 0f) {
                    doorGlow[gx][gy] = Math.max(0f, doorGlow[gx][gy] - dt * 0.4f);
                }
                if (repairFlash[gx][gy] > 0f) {
                    repairFlash[gx][gy] = Math.max(0f, repairFlash[gx][gy] - dt * 1.1f);
                }
            }
        }

        stepRequest(dt);
        stepRush(dt);
        stepStorm(dt);
        stepWeek(dt);
        recomputeRating();
        ratingShown += (ratingTier - ratingShown) * Math.min(1f, dt * 3f);
        ratingFlash = Math.max(0f, ratingFlash - dt);
        requestPulse += dt;
    }

    /* ------------------------------------------------- 1. the citizens' request ---------- */

    /**
     * The citizens come and ask for one building a session: a petitioner walks in from the street,
     * stands at the kerb with a placard and waits until the floors they asked for have gone up. The
     * building they get is a real one - it keeps a civic sign over its roof for good, and counts
     * toward the rating - so a request is not a scoreboard line, it is a thing in the city.
     */
    private void stepRequest(float dt) {
        if (!hasClockStarted()) {
            return;
        }
        if (requestOpen) {
            int done = placedThisSession - requestBase;
            if (done >= requestNeed) {
                fulfilRequest();
            }
            return;
        }
        requestCooldown -= dt;
        if (requestCooldown <= 0f && roomLeft() >= 8) {
            openRequest();
        }
    }

    private int roomLeft() {
        int room = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                room += maxHeight() - height[gx][gy];
            }
        }
        return room;
    }

    private void openRequest() {
        requestOpen = true;
        requestKind = (civicCount + requestsThisSession) % CIVIC_NAMES.length;
        requestBase = placedThisSession;
        // 75 seconds of the rower's own typical rowing for the first, half again for each after it.
        double floors = profile.typicalWatts() * 75.0 / BLOCK_COST
                * Math.pow(1.45, requestsThisSession);
        requestNeed = (int) Math.max(6, Math.min(Math.min(120, roomLeft()), Math.round(floors)));
        showBanner("THE CITIZENS WANT A " + CIVIC_NAMES[requestKind]);
        spawnPetitioner();
    }

    private void fulfilRequest() {
        requestOpen = false;
        requestsThisSession++;
        requestCooldown = 25f;
        // The building goes on whatever tower this session raised highest: the one they watched go up.
        int bx = -1;
        int by = -1;
        int best = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                if (civic[gx][gy] < 0 && sessionFloors[gx][gy] > best) {
                    best = sessionFloors[gx][gy];
                    bx = gx;
                    by = gy;
                }
            }
        }
        if (bx < 0) {
            // Nothing new went up on a free plot: put it on the tallest tower without a sign.
            for (int gx = 0; gx < GRID; gx++) {
                for (int gy = 0; gy < GRID; gy++) {
                    if (civic[gx][gy] < 0 && height[gx][gy] > best) {
                        best = height[gx][gy];
                        bx = gx;
                        by = gy;
                    }
                }
            }
        }
        if (bx >= 0) {
            civic[bx][by] = requestKind;
            civicCount++;
            bests.recordHighest("city.civic", civicCount);
            project(bx, by, height[bx][by] + 1);
            fx.burst(px, py, 26, dp(150f), 1.1f, dp(3f), CIVIC_COLORS[requestKind], true);
            doorGlow[bx][by] = 1f;
        }
        population += 30 + requestNeed * 2;
        shake.kick(dp(5f));
        showBanner(CIVIC_NAMES[requestKind] + " OPENS - THE CITY THANKS YOU");
        if (petitioner >= 0 && residents[petitioner].active
                && residents[petitioner].kind == R_PETITION) {
            Resident r = residents[petitioner];
            project(r.x, r.y, 0.4f);
            fx.burst(px, py - gTw * 0.3f, 14, dp(80f), 0.8f, dp(2.5f), 0xFFFFD24A, true);
            r.active = false;
        }
        petitioner = -1;
        recomputeRating();
    }

    private void spawnPetitioner() {
        Resident r = freeResident();
        if (r == null) {
            petitioner = -1;
            return;
        }
        petitioner = -1;
        for (int i = 0; i < residents.length; i++) {
            if (residents[i] == r) {
                petitioner = i;
            }
        }
        r.active = true;
        r.kind = R_PETITION;
        r.shirt = CIVIC_COLORS[requestKind];
        r.skin = SKINS[rng.nextInt(SKINS.length)];
        r.speed = 0.5f;
        r.phase = 0f;
        r.wait = 0f;
        r.leg = 0;
        r.legs = 1;
        r.x = -0.45f;
        r.y = -0.62f;
        r.wx[0] = 1.6f;
        r.wy[0] = -0.62f;
    }

    /* ------------------------------------------------- 2. rush hour ---------- */

    /**
     * Two minutes, a number of floors, and a pace line that is either ahead of you or behind you
     * right now. The rower starts it themselves from the HUD, because a demand that arrives
     * unannounced in the middle of a warm-up is just an interruption.
     */
    private void stepRush(float dt) {
        rushCooldown = Math.max(0f, rushCooldown - dt);
        if (!rushRunning) {
            rushTint = Math.max(0f, rushTint - dt * 1.5f);
            return;
        }
        rushTint = Math.min(1f, rushTint + dt * 1.5f);
        rushLeft -= dt;
        int done = placedThisSession - rushBase;
        if (done >= rushTarget) {
            endRush(true, done);
        } else if (rushLeft <= 0f) {
            endRush(false, done);
        }
    }

    private boolean rushAvailable() {
        return !rushRunning && rushCooldown <= 0f && stormPhase == STORM_NONE && damageTotal == 0
                && hasClockStarted() && roomLeft() >= 16;
    }

    private void startRush() {
        rushRunning = true;
        rushLeft = RUSH_SECONDS;
        rushBase = placedThisSession;
        double floors = profile.typicalWatts() * RUSH_DEMAND * RUSH_SECONDS / BLOCK_COST;
        rushTarget = (int) Math.max(10, Math.min(roomLeft() - 4, Math.round(floors)));
        showBanner("RUSH HOUR - " + rushTarget + " FLOORS IN TWO MINUTES");
        shake.kick(dp(4f));
    }

    private void endRush(boolean cleared, int done) {
        rushRunning = false;
        rushCooldown = cleared ? 40f : 60f;
        if (cleared) {
            rushWins++;
            bests.recordHighest("city.rushwins", rushWins);
            population += rushTarget * 3;
            showBanner("RUSH HOUR CLEARED - " + done + " FLOORS");
            shake.kick(dp(8f));
            for (int k = 0; k < 5; k++) {
                fx.burst(getWidth() * (0.25f + k * 0.13f), getHeight() * (0.28f + (k % 2) * 0.12f),
                        24, dp(190f), 1.2f, dp(3.5f), k % 2 == 0 ? 0xFFFFD24A : ACCENT, true);
            }
        } else {
            showBanner("RUSH HOUR OVER - " + done + " OF " + rushTarget);
        }
        if (done > rushBestFloors) {
            rushBestFloors = done;
            bests.recordHighest("city.rush", rushBestFloors);
        }
        recomputeRating();
    }

    /* ------------------------------------------------- 5. the storm ---------- */

    /**
     * Weather with teeth. A warning, then a front overhead that throws lightning at the tallest
     * towers every few seconds; the city's shield is powered by whatever the rower is pulling right
     * now, so the ten seconds before a strike genuinely matter. What gets through breaks floors,
     * and broken floors take every watt-second until they are back - which is the point of it.
     */
    private void stepStorm(float dt) {
        // Shield strength is the rower's live power across their own low-to-high band.
        double lo = profile.lowWatts();
        double hi = Math.max(lo + 1, profile.highWatts());
        float want = (float) Math.max(0, Math.min(1, (wattsNow - lo) / (hi - lo)));
        // Rises with the drive, bleeds away like the boat does. A single rate cannot work here:
        // 088 is instantaneous power and reads a true zero in 27% of samples taken while actively
        // rowing, in runs measured up to 9.7 s, so a symmetric follow would show THE SHIELD IS
        // DOWN to a rower who is pulling hard. The decay is still a decay - about three seconds to
        // the floor - so easing off really does open the city up.
        float rate = want > shield ? 3f : 0.35f;
        shield += (want - shield) * Math.min(1f, dt * rate);
        boltT = Math.max(0f, boltT - dt * 1.6f);
        stormNoteT = Math.max(0f, stormNoteT - dt);

        switch (stormPhase) {
            case STORM_NONE:
                stormIntensity = Math.max(0f, stormIntensity - dt * 0.5f);
                if (activeSeconds >= nextStormAt && !rushRunning && lifetime >= 12
                        && damageTotal == 0 && isClockRunning()) {
                    stormPhase = STORM_WARNING;
                    stormT = 9f;
                    showBanner("STORM WARNING - KEEP THE POWER UP");
                }
                break;
            case STORM_WARNING:
                stormIntensity = Math.min(0.55f, stormIntensity + dt * 0.12f);
                stormT -= dt;
                if (stormT <= 0f) {
                    stormPhase = STORM_OVERHEAD;
                    stormT = 45f;
                    strikeIn = 4f;
                }
                break;
            case STORM_OVERHEAD:
                stormIntensity = Math.min(1f, stormIntensity + dt * 0.5f);
                stormT -= dt;
                strikeIn -= dt;
                if (strikeIn <= 0f) {
                    strike();
                    strikeIn = 4.5f + rng.nextFloat() * 2f;
                }
                if (stormT <= 0f) {
                    stormPhase = STORM_CLEARING;
                    stormT = 8f;
                    stormsWeathered++;
                    bests.recordHighest("city.storms", stormsWeathered);
                    showBanner(damageTotal > 0
                            ? "STORM PASSED - " + damageTotal + " FLOORS NEED REPAIR"
                            : "STORM PASSED - NOT A SCRATCH");
                }
                break;
            default:
                stormIntensity = Math.max(0f, stormIntensity - dt * 0.14f);
                stormT -= dt;
                if (stormT <= 0f) {
                    stormPhase = STORM_NONE;
                    nextStormAt = activeSeconds + 240;
                }
                break;
        }
        if (stormIntensity > 0.02f) {
            stepRain(dt);
        }
    }

    private void stepRain(float dt) {
        if (!rainSeeded) {
            rainSeeded = true;
            for (int i = 0; i < rainX.length; i++) {
                rainX[i] = rng.nextFloat();
                rainY[i] = rng.nextFloat();
                rainV[i] = 0.9f + rng.nextFloat() * 0.8f;
            }
        }
        for (int i = 0; i < rainX.length; i++) {
            rainY[i] += rainV[i] * dt * (0.5f + stormIntensity);
            rainX[i] -= rainV[i] * dt * 0.16f;
            if (rainY[i] > 1f) {
                rainY[i] -= 1f;
                rainX[i] = rng.nextFloat();
            }
            if (rainX[i] < 0f) {
                rainX[i] += 1f;
            }
        }
    }

    /** One bolt at one tower: deflected if the shield is up, floors off it if not. */
    private void strike() {
        int bx = -1;
        int by = -1;
        int best = -1;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                // Lightning goes for height, with a little randomness so it is not always one tower.
                int score = height[gx][gy] * 3 - damage[gx][gy] * 4 + rng.nextInt(7);
                if (height[gx][gy] - damage[gx][gy] > 0 && score > best) {
                    best = score;
                    bx = gx;
                    by = gy;
                }
            }
        }
        if (bx < 0) {
            return;
        }
        boltPlotX = bx;
        boltPlotY = by;
        boltT = 1f;
        for (int i = 0; i < boltJag.length; i++) {
            boltJag[i] = (rng.nextFloat() - 0.5f) * 2f;
        }
        boltDeflected = rng.nextFloat() < shield;
        project(bx, by, height[bx][by]);
        if (boltDeflected) {
            fx.burst(px, py, 18, dp(130f), 0.6f, dp(3f), 0xFF7FD8FF, false);
            stormNote = "DEFLECTED";
            stormNoteT = 1.6f;
            shake.kick(dp(3f));
        } else {
            int hit = Math.min(height[bx][by] - damage[bx][by], 1 + rng.nextInt(2));
            damage[bx][by] += hit;
            damageTotal += hit;
            fx.burst(px, py, 22, dp(150f), 0.9f, dp(3.5f), 0xFFF0655D, true);
            stormNote = "-" + hit + (hit == 1 ? " FLOOR" : " FLOORS");
            stormNoteT = 2f;
            shake.kick(dp(9f));
            flash = 0.5f;
            recomputeRating();
        }
    }

    /** Puts one broken floor back. Repairs work down from the worst-hit tower. */
    private void repairOne() {
        int bx = -1;
        int by = -1;
        int best = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                if (damage[gx][gy] > best) {
                    best = damage[gx][gy];
                    bx = gx;
                    by = gy;
                }
            }
        }
        if (bx < 0) {
            damageTotal = 0;
            return;
        }
        damage[bx][by]--;
        damageTotal = Math.max(0, damageTotal - 1);
        repairsThisSession++;
        repairFlash[bx][by] = 1f;
        project(bx, by, height[bx][by] - damage[bx][by]);
        fx.burst(px, py, 10, dp(80f), 0.5f, dp(2.5f), 0xFFFFE8A8, true);
        if (damageTotal == 0) {
            showBanner("THE CITY IS WHOLE AGAIN");
            bests.recordHighest("city.repairs", repairsLifetime + repairsThisSession);
            recomputeRating();
        }
    }

    /* ------------------------------------------------- 4. this week ---------- */

    private void stepWeek(float dt) {
        weekBarA += (weekFloors - weekBarA) * Math.min(1f, dt * 2.5f);
        weekBarB += (lastWeekFloors - weekBarB) * Math.min(1f, dt * 2.5f);
        if (!passedLastWeek && lastWeekFloors > 0 && weekFloors >= lastWeekFloors) {
            passedLastWeek = true;
            showBanner("AHEAD OF LAST WEEK - " + weekFloors + " FLOORS");
            shake.kick(dp(4f));
            fx.burst(getWidth() * 0.18f, getHeight() * 0.14f, 22, dp(150f), 1.1f, dp(3f),
                    0xFFFFD24A, true);
        }
    }

    private void computeGeometry(float w, float h) {
        minX = parkOpen ? -2.2f : -0.6f;
        maxX = 6.2f;
        float minY = -0.6f;
        maxY = bridgeOpen ? 6.4f : GRID - 0.4f;
        ctrX = (minX + maxX) / 2f;
        ctrY = (minY + maxY) / 2f;
        float hx = (maxX - minX) / 2f;
        float hy = (maxY - minY) / 2f;
        // Worst-case projected half-extent over a full turn, so the zoom does not breathe as it pans.
        float ext = 1.414f * (float) Math.sqrt(hx * hx + hy * hy);
        float fit = Math.min(1f, 5.8f / ext);
        // Tile size shrinks as the city grows so it stays on screen.
        float tallScale = 1f - Math.min(0.42f, tallest / (float) maxHeight() * 0.42f);
        gTw = Math.min(w, h) * 0.105f * tallScale * fit;
        gTh = gTw * 0.52f;
        gBh = gTw * 0.62f;
        gCx = w * 0.5f;
        gCy = h * 0.64f;
        gCos = (float) Math.cos(angle);
        gSin = (float) Math.sin(angle);
    }

    /* ---------------------------------------------------------------- districts ---------- */

    private void openPark() {
        parkOpen = true;
        parkReveal = 0f;
        showBanner("THE PARK IS OPEN");
        project(-1.4f, 2f, 0f);
        fx.burst(px, py, 30, dp(160f), 1.0f, dp(3f), 0xFF8BD05A, true);
        fx.burst(px, py, 20, dp(120f), 1.0f, dp(3f), 0xFFE06BA8, true);
        shake.kick(dp(4f));
        spawnStrollers(R_PARK, 3);
        if (districtsSeen < 1) {
            districtsSeen = 1;
            bests.recordHighest("city.districts", 1);
        }
    }

    private void openBridge() {
        bridgeOpen = true;
        bridgeReveal = 0f;
        showBanner("THE BRIDGE IS OPEN");
        project(BRIDGE_X, 5.2f, 1f);
        fx.burst(px, py, 30, dp(160f), 1.0f, dp(3f), 0xFF6F8CFF, true);
        fx.burst(px, py, 20, dp(120f), 1.0f, dp(3f), 0xFFF0B132, true);
        shake.kick(dp(4f));
        spawnStrollers(R_BRIDGE, 2);
        if (districtsSeen < 2) {
            districtsSeen = 2;
            bests.recordHighest("city.districts", 2);
        }
    }

    private void showBanner(String text) {
        banner = text;
        bannerT = 3.5f;
    }

    private float landmarkFraction() {
        return (float) Math.max(0.0, Math.min(1.0, landmarkWork / landmarkTarget));
    }

    /* ---------------------------------------------------------------- residents ---------- */

    private Resident freeResident() {
        for (Resident r : residents) {
            if (!r.active) {
                return r;
            }
        }
        return null;
    }

    private void spawnStrollers(int kind, int n) {
        for (int i = 0; i < n; i++) {
            spawnResident(kind);
        }
    }

    private void spawnResident(int kind) {
        Resident r = freeResident();
        if (r == null) {
            return;
        }
        r.active = true;
        r.kind = kind;
        r.shirt = SHIRTS[rng.nextInt(SHIRTS.length)];
        r.skin = SKINS[rng.nextInt(SKINS.length)];
        r.speed = 0.22f + rng.nextFloat() * 0.1f;
        r.phase = rng.nextFloat() * 6f;
        r.wait = rng.nextFloat() * 2f;
        if (kind == R_PARK) {
            r.x = -2.0f + rng.nextFloat() * 1.2f;
            r.y = -0.4f + rng.nextFloat() * 4.8f;
        } else if (kind == R_BRIDGE) {
            r.x = BRIDGE_X + (rng.nextBoolean() ? 0.1f : -0.1f);
            r.y = 4.4f + rng.nextFloat() * 1.7f;
        } else {
            double a = rng.nextDouble() * Math.PI * 2;
            r.x = PLAZA_X + (float) Math.cos(a) * 0.62f;
            r.y = PLAZA_Y + (float) Math.sin(a) * 0.62f;
        }
        pickStrollTarget(r);
    }

    private void pickStrollTarget(Resident r) {
        r.legs = 1;
        r.leg = 0;
        if (r.kind == R_PARK) {
            r.wx[0] = -2.05f + rng.nextFloat() * 1.3f;
            r.wy[0] = -0.45f + rng.nextFloat() * 4.9f;
        } else if (r.kind == R_BRIDGE) {
            r.wx[0] = r.x;
            r.wy[0] = r.y > 5.2f ? 4.35f : 6.15f;
        } else {
            double a = rng.nextDouble() * Math.PI * 2;
            r.wx[0] = PLAZA_X + (float) Math.cos(a) * 0.62f;
            r.wy[0] = PLAZA_Y + (float) Math.sin(a) * 0.62f;
        }
    }

    /**
     * A family moves into the floors that just landed: a walker with a box comes in from the street,
     * the park or over the bridge and walks the gap between plots to the tower. If every walker is
     * busy the family is counted straight away, so the population never loses anyone.
     */
    private void spawnMover(int gx, int gy, int units) {
        Resident r = freeResident();
        if (r == null) {
            population += units * 4;
            doorGlow[gx][gy] = 1f;
            return;
        }
        r.active = true;
        r.kind = R_MOVER;
        r.shirt = SHIRTS[rng.nextInt(SHIRTS.length)];
        r.skin = SKINS[rng.nextInt(SKINS.length)];
        r.speed = 0.55f + rng.nextFloat() * 0.15f;
        r.phase = 0f;
        r.wait = 0f;
        r.plotX = gx;
        r.plotY = gy;
        r.family = units * 4;
        r.leg = 0;
        float lane = gx + 0.5f;
        int entries = 1 + (parkOpen ? 1 : 0) + (bridgeOpen ? 1 : 0);
        int pick = rng.nextInt(entries);
        if (pick == 1 && parkOpen) {
            // From the park, along the gap between rows.
            float row = gy + 0.5f;
            r.x = -1.6f;
            r.y = row;
            r.wx[0] = -0.55f;
            r.wy[0] = row;
            r.wx[1] = gx;
            r.wy[1] = row;
            r.legs = 2;
        } else if (pick >= 1 && bridgeOpen) {
            // Over the bridge from the far bank.
            r.x = BRIDGE_X;
            r.y = 6.25f;
            r.wx[0] = BRIDGE_X;
            r.wy[0] = GRID - 0.45f;
            r.wx[1] = lane;
            r.wy[1] = GRID - 0.45f;
            r.wx[2] = lane;
            r.wy[2] = gy;
            r.legs = 3;
        } else {
            // In from the street on the near edge.
            r.x = lane;
            r.y = -0.58f;
            r.wx[0] = lane;
            r.wy[0] = gy;
            r.legs = 1;
        }
    }

    private void stepResidents(float dt, float speed) {
        // The city bustles when you row: walkers pick up with boat speed.
        float hurry = 1f + Math.max(0f, speed) * 0.12f;
        for (Resident r : residents) {
            if (!r.active) {
                continue;
            }
            if (r.wait > 0f) {
                r.wait -= dt;
                continue;
            }
            float tx = r.wx[r.leg];
            float ty = r.wy[r.leg];
            float dx = tx - r.x;
            float dy = ty - r.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float stepLen = r.speed * hurry * dt;
            r.phase += dt * 9f * hurry;
            if (dist > stepLen) {
                r.x += dx / dist * stepLen;
                r.y += dy / dist * stepLen;
                continue;
            }
            r.x = tx;
            r.y = ty;
            r.leg++;
            if (r.leg < r.legs) {
                continue;
            }
            if (r.kind == R_PETITION) {
                // The petitioner reaches the kerb and stays there with the placard until the
                // building they came for is standing.
                r.wait = 9999f;
            } else if (r.kind == R_MOVER) {
                r.active = false;
                population += r.family;
                doorGlow[r.plotX][r.plotY] = 1f;
                project(r.x, r.y, 0.3f);
                fx.burst(px, py - gTw * 0.25f, 10, dp(55f), 0.7f, dp(2.5f), 0xFFFF7AA8, false);
            } else {
                r.wait = r.kind == R_BRIDGE ? 0.4f + rng.nextFloat() : 0.6f + rng.nextFloat() * 2.4f;
                pickStrollTarget(r);
            }
        }
    }

    /* ---------------------------------------------------------------- world ---------- */

    /** Everything but the HUD: this is also what photo mode renders into a bitmap. */
    private void drawWorld(Canvas c, float w, float h, boolean forPhoto) {
        // Sky: a slow day-night cycle so a long row visibly passes time. A storm front pulls the
        // whole scene down toward night, so the city darkens with the weather and not just the sky.
        float tod = skyTod();
        int key = (int) (tod * 48) * 100000 + (int) h;
        if (skyShader == null || key != skyKey) {
            float q = (int) (tod * 48) / 48f;
            skyShader = new LinearGradient(0, 0, 0, h,
                    blend(0xFF0B1430, 0xFF2E6FB0, q), blend(0xFF2A1C38, 0xFFBFD9EE, q),
                    Shader.TileMode.CLAMP);
            skyKey = key;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        if (rushTint > 0.02f) {
            // Rush hour puts the city under a hard amber light for its two minutes.
            paint.setColor(((int) (rushTint * 54) << 24) | 0xFFB347);
            c.drawRect(0, 0, w, h, paint);
        }
        if (tod < 0.5f) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) ((0.5f - tod) * 2 * 200));
            for (int i = 0; i < 40; i++) {
                c.drawCircle((i * 197 % Math.max(1, (int) w)), (i * 131 % Math.max(1, (int) (h * 0.5f))), dp(1.1f), paint);
            }
            paint.setAlpha(255);
        }
        // Sun or moon arcing across.
        float sunX = w * (0.15f + 0.7f * ((float) ((activeSeconds / 150.0) % 1.0)));
        float sunY = h * (0.40f - 0.26f * (float) Math.sin(Math.PI * ((activeSeconds / 150.0) % 1.0)));
        Fx.glow(c, sunX, sunY, dp(60f), tod > 0.5f ? 0x66FFE8A8 : 0x55BFD9EE);
        paint.setColor(tod > 0.5f ? 0xFFFFE8A8 : 0xFFE9EEF5);
        c.drawCircle(sunX, sunY, dp(20f), paint);
        drawSkyTraffic(c, w, h, tod);
        if (stormIntensity > 0.02f) {
            drawStormSky(c, w, h);
        }
        drawSiteLife(c, w, h, tod);

        drawGround(c, tod, forPhoto);

        // Everything that stands up, far to near.
        itemCount = 0;
        for (int idx = 0; idx < GRID * GRID; idx++) {
            int gx = idx % GRID;
            int gy = idx / GRID;
            if (height[gx][gy] > 0) {
                addItem(K_TOWER, idx, gx, gy);
            }
        }
        if (parkOpen) {
            for (int i = 0; i < PARK_TREES; i++) {
                addItem(K_TREE, i, TREE_X[i], TREE_Y[i]);
            }
            for (int i = 0; i < LAMP_Y.length; i++) {
                addItem(K_LAMP, i, -0.95f, LAMP_Y[i]);
            }
            addItem(K_FOUNTAIN, 0, -1.7f, 2.1f);
        }
        if (bridgeOpen) {
            for (int i = PARK_TREES; i < TREE_X.length; i++) {
                if (TREE_X[i] >= minX) {
                    addItem(K_TREE, i, TREE_X[i], TREE_Y[i]);
                }
            }
            addItem(K_BRIDGE, 0, BRIDGE_X, 5.2f);
            addItem(K_SHELL, 0, shellX, 5.35f);
        }
        addItem(K_LANDMARK, 0, PLAZA_X, PLAZA_Y);
        float bridgeDepth = depth(BRIDGE_X, 5.2f);
        for (int i = 0; i < residents.length; i++) {
            Resident r = residents[i];
            if (r.active) {
                addItem(K_RESIDENT, i, r.x, r.y);
                // The bridge is one sorted item, so a walker on its far half would sort behind the
                // deck and vanish mid-crossing. Anyone on the deck draws after it.
                if (bridgeOpen && onBridge(r) && itemCount > 0 && itemRef[itemCount - 1] == i
                        && itemKind[itemCount - 1] == K_RESIDENT) {
                    itemDepth[itemCount - 1] = Math.max(itemDepth[itemCount - 1], bridgeDepth + 0.05f);
                }
            }
        }
        // Insertion sort: under 80 items, no allocation.
        for (int i = 1; i < itemCount; i++) {
            float d = itemDepth[i];
            int k = itemKind[i];
            int r = itemRef[i];
            int j = i - 1;
            while (j >= 0 && itemDepth[j] > d) {
                itemDepth[j + 1] = itemDepth[j];
                itemKind[j + 1] = itemKind[j];
                itemRef[j + 1] = itemRef[j];
                j--;
            }
            itemDepth[j + 1] = d;
            itemKind[j + 1] = k;
            itemRef[j + 1] = r;
        }
        for (int i = 0; i < itemCount; i++) {
            int ref = itemRef[i];
            switch (itemKind[i]) {
                case K_TOWER:
                    drawTower(c, ref % GRID, ref / GRID, tod, ref);
                    break;
                case K_TREE:
                    drawTree(c, ref, tod);
                    break;
                case K_LAMP:
                    drawLamp(c, ref, tod);
                    break;
                case K_FOUNTAIN:
                    drawFountain(c, tod);
                    break;
                case K_LANDMARK:
                    drawLandmark(c, tod);
                    break;
                case K_BRIDGE:
                    drawBridge(c, tod);
                    break;
                case K_SHELL:
                    drawShell(c, tod);
                    break;
                default:
                    drawResident(c, residents[ref]);
                    break;
            }
        }

        // Falling blocks, with a guide line down to the plot so you can see where each is going.
        for (int i = falling.size() - 1; i >= 0; i--) {
            Falling f = falling.get(i);
            for (int u = f.units - 1; u >= 0; u--) {
                drawBlock(c, f.gx, f.gy, f.z + u, f.color);
            }
            project(f.gx, f.gy, f.z);
            float fx0 = px;
            float fy0 = py;
            project(f.gx, f.gy, f.target);
            paint.setColor(0x55FFE28A);
            paint.setStrokeWidth(dp(1.5f));
            c.drawLine(fx0, fy0, px, py, paint);
        }
        fx.draw(c);
        if (stormIntensity > 0.02f) {
            drawStormFront(c, w, h);
        }
    }

    private float timeOfDay() {
        return (float) ((Math.sin(activeSeconds / 150.0 - Math.PI / 2) + 1) / 2);   // 0 night..1 day
    }

    /** Daylight as the city actually sees it: the storm front takes most of it away. */
    private float skyTod() {
        return timeOfDay() * (1f - 0.72f * Math.min(1f, stormIntensity));
    }

    /* ---------------------------------------------------------------- storm ---------- */

    /** The front itself: low cloud running across the top of the sky, fast and dark. */
    private void drawStormSky(Canvas c, float w, float h) {
        double t = sessionSeconds;
        paint.setStyle(Paint.Style.FILL);
        int a = (int) (Math.min(1f, stormIntensity) * 170);
        for (int i = 0; i < 6; i++) {
            float span = w + dp(520f);
            float cx = (float) (((i * 353 + 40) + t * dp(52f + i * 9f)) % span) - dp(260f);
            float cy = h * (0.04f + (i % 3) * 0.055f);
            float sc = 1.1f + (i % 2) * 0.5f;
            paint.setColor((a << 24) | 0x1B2230);
            c.drawOval(cx - dp(150f) * sc, cy - dp(26f) * sc, cx + dp(150f) * sc, cy + dp(26f) * sc, paint);
            paint.setColor(((a * 3 / 4) << 24) | 0x2A3448);
            c.drawOval(cx - dp(70f) * sc, cy - dp(52f) * sc, cx + dp(80f) * sc, cy + dp(8f) * sc, paint);
        }
    }

    /**
     * Rain, the bolt, and the shield the rower is holding up with their own power. The shield dome
     * is the feedback that makes the storm a game rather than a cutscene: it visibly thickens while
     * you pull and thins the moment you ease off.
     */
    private void drawStormFront(Canvas c, float w, float h) {
        float v = Math.min(1f, stormIntensity);
        // This is the last thing drawn in the world pass, and both blocks below are conditional:
        // without this the shared paint could reach the HUD (and the photo bitmap) still in STROKE.
        paint.setStyle(Paint.Style.FILL);
        // Shield: a dome over the city, brighter the harder you are pulling.
        float dome = shield * v;
        if (dome > 0.04f) {
            float r = Math.max(dp(120f), gTw * 6.2f);
            paint.setStyle(Paint.Style.STROKE);
            for (int k = 0; k < 2; k++) {
                float rr = r * (1f + k * 0.07f);
                paint.setStrokeWidth(dp(k == 0 ? 3f : 1.4f));
                paint.setColor(((int) (dome * (k == 0 ? 150 : 80)) << 24) | 0x7FD8FF);
                c.drawArc(gCx - rr, gCy - rr * 0.92f, gCx + rr, gCy + rr * 0.92f, 190, 160, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            // A pulse running around the rim while the shield is strong.
            float u = (float) ((sessionSeconds * 0.6) % 1.0);
            double ang = Math.toRadians(190 + 160 * u);
            paint.setColor(((int) (dome * 220) << 24) | 0xDFF3FF);
            c.drawCircle(gCx + (float) Math.cos(ang) * r, gCy + (float) Math.sin(ang) * r * 0.92f,
                    dp(4f), paint);
        }
        // Rain.
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(((int) (v * 120) << 24) | 0xC8DCF0);
        float len = dp(26f) * (0.5f + v);
        for (int i = 0; i < rainX.length; i++) {
            float x = rainX[i] * w;
            float y = rainY[i] * h;
            c.drawLine(x, y, x - len * 0.28f, y + len, paint);
        }
        // The bolt, redrawn each frame from the plot it hit so it stays on the tower as we pan.
        if (boltT > 0f) {
            project(boltPlotX, boltPlotY, height[boltPlotX][boltPlotY]);
            float tx = px;
            float ty = py;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3.5f) * boltT);
            paint.setColor(((int) (Math.min(1f, boltT) * 255) << 24)
                    | (boltDeflected ? 0x7FD8FF : 0xFFF1C4));
            path.reset();
            path.moveTo(tx + boltJag[0] * dp(60f), 0);
            for (int i = 1; i < boltJag.length; i++) {
                float f = i / (float) (boltJag.length - 1);
                path.lineTo(tx + boltJag[i] * dp(46f) * (1f - f), ty * f);
            }
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            Fx.glow(c, tx, ty, dp(70f), ((int) (boltT * 0x99) << 24)
                    | (boltDeflected ? 0x7FD8FF : 0xFFC0A0));
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void addItem(int kind, int ref, float gx, float gy) {
        if (itemCount >= itemKind.length) {
            return;
        }
        itemKind[itemCount] = kind;
        itemRef[itemCount] = ref;
        // Small things sort a touch forward so a walker at a tower's foot is not swallowed by it.
        itemDepth[itemCount] = depth(gx, gy) + (kind == K_RESIDENT ? 0.3f : 0f);
        itemCount++;
    }

    private void drawGround(Canvas c, float tod, boolean forPhoto) {
        paint.setStyle(Paint.Style.FILL);
        plate(c, -0.6f, -0.6f, GRID - 0.4f, GRID - 0.4f, blend(0xFF15202E, 0xFF3F4A56, tod));
        // The plaza where the weekly landmark goes up.
        plate(c, GRID - 0.4f, 1.2f, 6.2f, 2.8f, blend(0xFF1E2532, 0xFF7C7F88, tod));
        ellipse(c, PLAZA_X, PLAZA_Y, 0.62f, blend(0xFF283040, 0xFF9A9CA4, tod));

        if (parkOpen) {
            plate(c, -2.2f, -0.6f, -0.6f, GRID - 0.4f, blend(0xFF10261A, 0xFF4E8A4A, tod));
            plate(c, -1.2f, -0.6f, -1.0f, GRID - 0.4f, blend(0xFF3A3526, 0xFFC9B98A, tod));
            ellipse(c, -1.7f, 2.1f, 0.34f * Math.max(0.2f, parkReveal), blend(0xFF0E2238, 0xFF4C8FC4, tod));
        } else if (!forPhoto) {
            outline(c, -2.2f, -0.6f, -0.6f, GRID - 0.4f);
            project(-1.4f, 2f, 0f);
            bold(c, "PARK", px, py, 11f, 0x88E6EDF7, Paint.Align.CENTER);
            label(c, "AT " + PARK_AT + " BLOCKS", px, py + dp(13f), 8.5f, 0x88E6EDF7, Paint.Align.CENTER);
        }

        if (bridgeOpen) {
            plate(c, minX, GRID - 0.4f, maxX, 5.8f, blend(0xFF0A1A2C, 0xFF3F7FB8, tod));
            plate(c, minX, 5.8f, maxX, 6.4f, blend(0xFF13241A, 0xFF5B7F4C, tod));
            // Ripples drifting downstream.
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(tod > 0.4f ? 0x66FFFFFF : 0x33BFD9EE);
            float span = (maxX - minX) - 0.35f;
            for (int i = 0; i < 10; i++) {
                float x = minX + (float) ((i * 0.83 + sessionSeconds * 0.15) % span);
                float y = 4.75f + (i % 4) * 0.28f;
                project(x, y, 0f);
                float x0 = px;
                float y0 = py;
                project(x + 0.3f, y, 0f);
                c.drawLine(x0, y0, px, py, paint);
            }
        } else if (!forPhoto) {
            outline(c, -0.6f, GRID - 0.4f, maxX, 5.8f);
            project(BRIDGE_X, 5.2f, 0f);
            bold(c, "RIVER AND BRIDGE", px, py, 11f, 0x88E6EDF7, Paint.Align.CENTER);
            label(c, "AT " + BRIDGE_AT + " BLOCKS", px, py + dp(13f), 8.5f, 0x88E6EDF7, Paint.Align.CENTER);
        }
    }

    private void plate(Canvas c, float x0, float y0, float x1, float y1, int color) {
        quadPath(x0, y0, x1, y1, 0f);
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private void outline(Canvas c, float x0, float y0, float x1, float y1) {
        quadPath(x0, y0, x1, y1, 0f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0x44E6EDF7);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void quadPath(float x0, float y0, float x1, float y1, float z) {
        path.reset();
        project(x0, y0, z);
        path.moveTo(px, py);
        project(x1, y0, z);
        path.lineTo(px, py);
        project(x1, y1, z);
        path.lineTo(px, py);
        project(x0, y1, z);
        path.lineTo(px, py);
        path.close();
    }

    /** A flat circle on the ground, drawn in the rotating plane so it turns with the city. */
    private void ellipse(Canvas c, float gx, float gy, float r, int color) {
        path.reset();
        for (int k = 0; k < 14; k++) {
            double a = k * Math.PI * 2 / 14;
            project(gx + (float) Math.cos(a) * r, gy + (float) Math.sin(a) * r, 0f);
            if (k == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private void drawTree(Canvas c, int i, float tod) {
        float grow = i < PARK_TREES ? parkReveal : bridgeReveal;
        // Staggered so the park fills in tree by tree.
        float g = Math.max(0f, Math.min(1f, grow * 1.6f - (i % PARK_TREES) * 0.1f));
        if (g <= 0f) {
            return;
        }
        project(TREE_X[i], TREE_Y[i], 0f);
        float s = gTw * g;
        float sway = (float) Math.sin(sessionSeconds * 1.3 + i) * s * 0.03f;
        paint.setColor(blend(0xFF2A1E14, 0xFF6B4A2E, tod));
        c.drawRect(px - s * 0.05f, py - s * 0.32f, px + s * 0.05f, py, paint);
        int leaf = blend(0xFF12301C, (i % 2 == 0) ? 0xFF4FA048 : 0xFF6BB45A, tod);
        paint.setColor(leaf);
        c.drawCircle(px + sway, py - s * 0.45f, s * 0.22f, paint);
        paint.setColor(blend(leaf, 0xFFFFFFFF, 0.12f));
        c.drawCircle(px + sway - s * 0.07f, py - s * 0.55f, s * 0.13f, paint);
    }

    private void drawLamp(Canvas c, int i, float tod) {
        if (parkReveal < 0.5f) {
            return;
        }
        project(-0.95f, LAMP_Y[i], 0f);
        float s = gTw;
        paint.setColor(0xFF2A3446);
        c.drawRect(px - s * 0.02f, py - s * 0.55f, px + s * 0.02f, py, paint);
        boolean night = tod < 0.45f;
        if (night) {
            Fx.glow(c, px, py - s * 0.58f, s * 0.45f, 0x66FFE6A8);
        }
        paint.setColor(night ? 0xFFFFE6A8 : 0xFF9AA5B1);
        c.drawCircle(px, py - s * 0.58f, s * 0.06f, paint);
    }

    private void drawFountain(Canvas c, float tod) {
        if (parkReveal < 0.6f) {
            return;
        }
        project(-1.7f, 2.1f, 0f);
        float s = gTw;
        paint.setColor(blend(0xFF3A4052, 0xFFB9BCC4, tod));
        c.drawRect(px - s * 0.06f, py - s * 0.18f, px + s * 0.06f, py, paint);
        // Jets arc up and fall back into the pond.
        paint.setColor(tod > 0.4f ? 0xCCE9F4FF : 0x99BFD9EE);
        double t = sessionSeconds;
        for (int k = 0; k < 10; k++) {
            float life = (float) ((t * 0.9 + k * 0.1) % 1.0);
            float dir = (k % 2 == 0 ? 1f : -1f) * (0.3f + (k % 5) * 0.08f);
            float x = px + dir * s * 0.45f * life;
            float y = py - s * 0.2f - s * 0.55f * (life * (1 - life)) * 4f * 0.5f;
            c.drawCircle(x, y, s * 0.028f, paint);
        }
        if (tod < 0.45f) {
            Fx.glow(c, px, py - s * 0.25f, s * 0.5f, 0x3380C8FF);
        }
    }

    private void drawBridge(Canvas c, float tod) {
        float g = bridgeReveal;
        float x0 = BRIDGE_X - 0.26f;
        float x1 = BRIDGE_X + 0.26f;
        float y0 = GRID - 0.55f;
        float y1 = 5.95f;
        float deck = 0.18f;
        // Deck.
        quadPath(x0, y0, x1, y1, deck);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(0xFF2A303C, 0xFF8D939E, tod));
        c.drawPath(path, paint);
        // Pylons, rising in as the bridge opens.
        float top = deck + 1.6f * g;
        paint.setStrokeWidth(Math.max(dp(2f), gTw * 0.05f));
        paint.setColor(blend(0xFF5A2E2A, 0xFFC0503E, tod));
        for (int side = 0; side < 2; side++) {
            float sx = side == 0 ? x0 : x1;
            for (int p = 0; p < 2; p++) {
                float sy = p == 0 ? 4.85f : 5.55f;
                project(sx, sy, 0f);
                float bx = px;
                float by = py;
                project(sx, sy, top);
                c.drawLine(bx, by, px, py, paint);
            }
        }
        // Cables: pylon tops to the deck ends and a sag between the pylons, bulbs along them at night.
        paint.setStrokeWidth(dp(1.2f));
        boolean night = tod < 0.45f;
        for (int side = 0; side < 2; side++) {
            float sx = side == 0 ? x0 : x1;
            paint.setColor(blend(0xFF6A3A34, 0xFFD8705C, tod));
            project(sx, y0, deck);
            float ax = px;
            float ay = py;
            project(sx, 4.85f, top);
            float bx = px;
            float by = py;
            project(sx, 5.55f, top);
            float cx2 = px;
            float cy2 = py;
            project(sx, y1, deck);
            float dx = px;
            float dy = py;
            project(sx, 5.2f, deck + 0.35f * g);
            float mx = px;
            float my = py;
            c.drawLine(ax, ay, bx, by, paint);
            c.drawLine(cx2, cy2, dx, dy, paint);
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            path.moveTo(bx, by);
            path.quadTo(2 * mx - (bx + cx2) / 2f, 2 * my - (by + cy2) / 2f, cx2, cy2);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            if (night && g >= 1f) {
                paint.setColor(0xFFFFE6A8);
                for (int k = 0; k <= 6; k++) {
                    float u = k / 6f;
                    // Point on the quadratic sag.
                    float qx = (1 - u) * (1 - u) * bx + 2 * (1 - u) * u * (2 * mx - (bx + cx2) / 2f)
                            + u * u * cx2;
                    float qy = (1 - u) * (1 - u) * by + 2 * (1 - u) * u * (2 * my - (by + cy2) / 2f)
                            + u * u * cy2;
                    c.drawCircle(qx, qy, dp(1.8f), paint);
                }
            }
        }
        // Traffic both ways.
        if (g >= 1f) {
            for (int k = 0; k < 3; k++) {
                float u = (float) ((sessionSeconds * 0.11 + k * 0.37) % 1.0);
                boolean north = k % 2 == 0;
                float cy3 = north ? y0 + u * (y1 - y0) : y1 - u * (y1 - y0);
                project(north ? BRIDGE_X + 0.1f : BRIDGE_X - 0.1f, cy3, deck + 0.05f);
                float s = gTw * 0.08f;
                paint.setColor(SHIRTS[(k * 3) % SHIRTS.length]);
                c.drawRoundRect(px - s * 1.3f, py - s, px + s * 1.3f, py + s * 0.4f, s * 0.4f, s * 0.4f,
                        paint);
                if (night) {
                    Fx.glow(c, px, py, gTw * 0.18f, 0x55FFF1C4);
                }
            }
        }
    }

    /** The rower's own shell on the river: it covers the metres rowed and the oars follow the stroke. */
    private void drawShell(Canvas c, float tod) {
        float y = 5.35f;
        project(shellX - 0.28f, y, 0f);
        float ax = px;
        float ay = py;
        project(shellX + 0.28f, y, 0f);
        float bx = px;
        float by = py;
        float mx = (ax + bx) / 2f;
        float my = (ay + by) / 2f;
        // Wake.
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0x55FFFFFF);
        c.drawLine(ax, ay, ax - (bx - ax) * 0.6f, ay - (by - ay) * 0.6f + dp(2f), paint);
        paint.setStrokeWidth(Math.max(dp(2.5f), gTw * 0.06f));
        paint.setColor(tod > 0.4f ? 0xFFF4F1E8 : 0xFFB9BCC4);
        c.drawLine(ax, ay, bx, by, paint);
        // Oars sweep with the real stroke: back at the catch, through at the finish.
        float sweep = (strokePhase() - 0.5f) * 0.22f;
        paint.setStrokeWidth(dp(1.3f));
        paint.setColor(0xFFE0B060);
        project(shellX + sweep, y - 0.24f, 0f);
        c.drawLine(mx, my, px, py, paint);
        project(shellX + sweep, y + 0.24f, 0f);
        c.drawLine(mx, my, px, py, paint);
        paint.setColor(0xFFE0582E);
        c.drawCircle(mx, my - gTw * 0.05f, gTw * 0.045f, paint);
    }

    private static boolean onBridge(Resident r) {
        return Math.abs(r.x - BRIDGE_X) < 0.3f && r.y > GRID - 0.55f && r.y < 5.95f;
    }

    private void drawResident(Canvas c, Resident r) {
        project(r.x, r.y, bridgeOpen && onBridge(r) ? 0.18f : 0f);
        float s = gTw * 0.1f;
        float x = px;
        float y = py;
        boolean moving = r.wait <= 0f;
        float swing = moving ? (float) Math.sin(r.phase) * s * 0.35f : 0f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A2F3A);
        c.drawRect(x - s * 0.35f + swing, y - s * 1.2f, x - s * 0.05f + swing, y, paint);
        c.drawRect(x + s * 0.05f - swing, y - s * 1.2f, x + s * 0.35f - swing, y, paint);
        paint.setColor(r.shirt);
        c.drawRect(x - s * 0.45f, y - s * 2.4f, x + s * 0.45f, y - s * 1.1f, paint);
        paint.setColor(r.skin);
        c.drawCircle(x, y - s * 2.85f, s * 0.42f, paint);
        if (r.kind == R_MOVER) {
            // Carrying a box overhead.
            float bob = moving ? Math.abs((float) Math.sin(r.phase)) * s * 0.2f : 0f;
            paint.setColor(0xFFC89B62);
            c.drawRect(x - s * 0.6f, y - s * 4.4f - bob, x + s * 0.6f, y - s * 3.35f - bob, paint);
            paint.setColor(0xFF8A6238);
            c.drawRect(x - s * 0.6f, y - s * 3.95f - bob, x + s * 0.6f, y - s * 3.8f - bob, paint);
        } else if (r.kind == R_PETITION) {
            // A placard waved over the head, and the ask spelled out above it.
            float wave = (float) Math.sin(sessionSeconds * 3.2) * 0.22f;
            c.save();
            c.rotate(wave * 24f, x, y - s * 2.6f);
            paint.setColor(0xFF8A6238);
            c.drawRect(x - s * 0.08f, y - s * 4.6f, x + s * 0.08f, y - s * 2.2f, paint);
            paint.setColor(r.shirt);
            c.drawRect(x - s * 1.05f, y - s * 6.1f, x + s * 1.05f, y - s * 4.4f, paint);
            paint.setColor(0x66FFFFFF);
            c.drawRect(x - s * 0.8f, y - s * 5.7f, x + s * 0.8f, y - s * 5.45f, paint);
            c.drawRect(x - s * 0.8f, y - s * 5.2f, x + s * 0.35f, y - s * 4.95f, paint);
            c.restore();
            if (requestOpen) {
                int left = Math.max(0, requestNeed - (placedThisSession - requestBase));
                bold(c, CIVIC_NAMES[requestKind] + "  " + left, x, y - s * 7.2f, 9f,
                        CIVIC_COLORS[requestKind], Paint.Align.CENTER);
            }
        }
    }

    /* ---------------------------------------------------------------- landmark ---------- */

    private void drawLandmark(Canvas c, float tod) {
        project(PLAZA_X, PLAZA_Y, 0f);
        float bx = px;
        float by = py;
        float s = gTw;
        float H = s * LANDMARK_H[landmarkKind];
        float f = landmarkFraction();
        boolean night = tod < 0.45f;
        // Plinth, always there: the site is staked out from the first day of the week.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(0xFF3A3F4C, 0xFFB0B3BC, tod));
        c.drawRect(bx - s * 0.55f, by - s * 0.12f, bx + s * 0.55f, by + s * 0.06f, paint);
        float builtTop = by - s * 0.12f - (H - s * 0.12f) * f;
        if (f > 0f) {
            c.save();
            c.clipRect(bx - s * 2.6f, builtTop, bx + s * 2.6f, by + s * 0.2f);
            switch (landmarkKind) {
                case 0:
                    drawClockTower(c, bx, by, s, H, tod, night);
                    break;
                case 1:
                    drawFerrisWheel(c, bx, by, s, H, tod, night);
                    break;
                case 2:
                    drawLighthouse(c, bx, by, s, H, tod, night);
                    break;
                default:
                    drawObservatory(c, bx, by, s, H, tod, night);
                    break;
            }
            c.restore();
        }
        if (!landmarkDone) {
            // Scaffolding over the part still to build, with a work light at the build line.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0x99F0B132);
            float half = s * (landmarkKind == 1 ? 0.9f : 0.5f);
            c.drawLine(bx - half, builtTop, bx - half, by - H, paint);
            c.drawLine(bx + half, builtTop, bx + half, by - H, paint);
            for (float yy = builtTop; yy > by - H; yy -= s * 0.35f) {
                c.drawLine(bx - half, yy, bx + half, yy, paint);
                c.drawLine(bx - half, yy, bx + half, Math.max(by - H, yy - s * 0.35f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            boolean blink = ((int) (sessionSeconds * 2)) % 2 == 0;
            paint.setColor(blink ? 0xFFFFD24A : 0x66FFD24A);
            c.drawCircle(bx + half, builtTop, dp(3f), paint);
        } else if (night) {
            Fx.glow(c, bx, by - H * 0.5f, s * 1.6f, 0x33FFE6A8);
        }
    }

    private void drawClockTower(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float bodyTop = by - H * 0.72f;
        paint.setColor(blend(0xFF4A4034, 0xFFB9A58A, tod));
        c.drawRect(bx - s * 0.32f, bodyTop, bx, by - s * 0.12f, paint);
        paint.setColor(blend(0xFF3A3228, 0xFF9C8A70, tod));
        c.drawRect(bx, bodyTop, bx + s * 0.32f, by - s * 0.12f, paint);
        path.reset();
        path.moveTo(bx - s * 0.42f, bodyTop);
        path.lineTo(bx + s * 0.42f, bodyTop);
        path.lineTo(bx, by - H);
        path.close();
        paint.setColor(blend(0xFF28324A, 0xFF5E6E8C, tod));
        c.drawPath(path, paint);
        float fy = by - H * 0.6f;
        float r = s * 0.24f;
        if (night) {
            Fx.glow(c, bx, fy, r * 2.2f, 0x66FFF1C4);
        }
        paint.setColor(night ? 0xFFFFF1C4 : 0xFFF4EEDC);
        c.drawCircle(bx, fy, r, paint);
        // The real time of day.
        paint.setColor(0xFF20242C);
        paint.setStrokeWidth(Math.max(dp(1.5f), r * 0.12f));
        c.drawLine(bx, fy, bx + (float) Math.sin(hourAngle) * r * 0.55f,
                fy - (float) Math.cos(hourAngle) * r * 0.55f, paint);
        paint.setStrokeWidth(Math.max(dp(1f), r * 0.07f));
        c.drawLine(bx, fy, bx + (float) Math.sin(minuteAngle) * r * 0.85f,
                fy - (float) Math.cos(minuteAngle) * r * 0.85f, paint);
    }

    private void drawFerrisWheel(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float R = H * 0.45f;
        float hubY = by - H + R;
        paint.setStrokeWidth(Math.max(dp(2f), s * 0.06f));
        paint.setColor(blend(0xFF3A4052, 0xFFB9BCC4, tod));
        c.drawLine(bx - s * 0.7f, by - s * 0.1f, bx, hubY, paint);
        c.drawLine(bx + s * 0.7f, by - s * 0.1f, bx, hubY, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.5f), s * 0.04f));
        paint.setColor(blend(0xFF6A5A8A, 0xFFE06BA8, tod));
        c.drawCircle(bx, hubY, R, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(dp(1f));
        for (int k = 0; k < 10; k++) {
            double a = wheelAngle + k * Math.PI * 2 / 10;
            float ex = bx + (float) Math.cos(a) * R;
            float ey = hubY + (float) Math.sin(a) * R;
            paint.setColor(blend(0xFF4A4A5A, 0xFFDDDDE4, tod));
            c.drawLine(bx, hubY, ex, ey, paint);
            // Cabins hang below the rim.
            int col = SHIRTS[k % SHIRTS.length];
            paint.setColor(col);
            c.drawRoundRect(ex - s * 0.1f, ey, ex + s * 0.1f, ey + s * 0.16f, s * 0.04f, s * 0.04f, paint);
            if (night && landmarkDone) {
                paint.setColor(k % 2 == 0 ? 0xFFFFE6A8 : 0xFFFF7AA8);
                c.drawCircle(ex, ey, dp(2.2f), paint);
            }
        }
        paint.setColor(0xFF9AA5B1);
        c.drawCircle(bx, hubY, s * 0.08f, paint);
    }

    private void drawLighthouse(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float base = by - s * 0.12f;
        float towerTop = by - H * 0.78f;
        int bands = 5;
        for (int k = 0; k < bands; k++) {
            float t0 = k / (float) bands;
            float t1 = (k + 1) / (float) bands;
            float y0 = base + (towerTop - base) * t0;
            float y1 = base + (towerTop - base) * t1;
            float w0 = s * (0.36f - 0.14f * t0);
            float w1 = s * (0.36f - 0.14f * t1);
            path.reset();
            path.moveTo(bx - w0, y0);
            path.lineTo(bx + w0, y0);
            path.lineTo(bx + w1, y1);
            path.lineTo(bx - w1, y1);
            path.close();
            paint.setColor(k % 2 == 0 ? blend(0xFF5A2A26, 0xFFD2463A, tod) : blend(0xFF5A5C64, 0xFFF4F1E8, tod));
            c.drawPath(path, paint);
        }
        paint.setColor(0xFF2A3446);
        c.drawRect(bx - s * 0.3f, towerTop - s * 0.05f, bx + s * 0.3f, towerTop + s * 0.03f, paint);
        boolean lit = night && landmarkDone;
        paint.setColor(lit ? 0xFFFFF1C4 : 0xFF9FC4DA);
        c.drawRect(bx - s * 0.17f, towerTop - s * 0.34f, bx + s * 0.17f, towerTop - s * 0.05f, paint);
        path.reset();
        path.moveTo(bx - s * 0.24f, towerTop - s * 0.34f);
        path.lineTo(bx + s * 0.24f, towerTop - s * 0.34f);
        path.lineTo(bx, by - H);
        path.close();
        paint.setColor(0xFF2A3446);
        c.drawPath(path, paint);
        if (lit) {
            // A sweeping beam: its apparent length follows the turn of the lamp.
            float ly = towerTop - s * 0.2f;
            float reach = (float) Math.cos(beamAngle) * s * 5f;
            path.reset();
            path.moveTo(bx, ly);
            path.lineTo(bx + reach, ly - s * 0.35f);
            path.lineTo(bx + reach, ly + s * 0.35f);
            path.close();
            paint.setColor(0x33FFF1C4);
            c.drawPath(path, paint);
            Fx.glow(c, bx, ly, s * 0.7f, 0x88FFF1C4);
        }
    }

    private void drawObservatory(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float base = by - s * 0.12f;
        float drumTop = by - H * 0.6f;
        float r = s * 0.62f;
        paint.setColor(blend(0xFF3A3F4C, 0xFFD8D4CC, tod));
        c.drawRect(bx - r, drumTop, bx, base, paint);
        paint.setColor(blend(0xFF2E323C, 0xFFB5B0A6, tod));
        c.drawRect(bx, drumTop, bx + r, base, paint);
        paint.setColor(blend(0xFF4A5060, 0xFFE9EEF5, tod));
        c.drawArc(bx - r, drumTop - r, bx + r, drumTop + r, 180, 180, true, paint);
        // The dome turns: its slit drifts across and glows when the telescope is working at night.
        float slit = (float) Math.sin(sessionSeconds * 0.25) * r * 0.45f;
        boolean lit = night && landmarkDone;
        paint.setColor(lit ? 0xFFFFE6A8 : 0xFF20242C);
        c.drawRect(bx + slit - s * 0.07f, drumTop - r * 0.92f, bx + slit + s * 0.07f, drumTop, paint);
        if (lit) {
            paint.setColor(0xFF9AA5B1);
            paint.setStrokeWidth(Math.max(dp(2f), s * 0.06f));
            c.drawLine(bx + slit, drumTop - r * 0.5f, bx + slit + s * 0.3f, drumTop - r * 1.2f, paint);
        }
        paint.setColor(0xFF20242C);
        c.drawRect(bx - s * 0.1f, base - s * 0.3f, bx + s * 0.1f, base, paint);
    }

    /* ---------------------------------------------------------------- towers ---------- */

    private float depth(float gx, float gy) {
        float rx = (gx - ctrX) * gCos - (gy - ctrY) * gSin;
        float ry = (gx - ctrX) * gSin + (gy - ctrY) * gCos;
        return rx + ry;
    }

    /** Projects a world point into {@link #px}/{@link #py}; no allocation. */
    private void project(float gx, float gy, float z) {
        float rx = (gx - ctrX) * gCos - (gy - ctrY) * gSin;
        float ry = (gx - ctrX) * gSin + (gy - ctrY) * gCos;
        px = gCx + (rx - ry) * gTw;
        py = gCy + (rx + ry) * gTh - z * gBh;
    }

    /** One extruded prism from the ground to its height, with lit windows. */
    private void drawTower(Canvas c, int gx, int gy, float tod, int seedIdx) {
        int floors = height[gx][gy];
        int color = tint[gx][gy];
        float tw = gTw;
        float th = gTh;
        float bh = gBh;
        project(gx, gy, floors);
        float sx = px;
        float sy = py;
        float bodyH = floors * bh;

        int left = blend(color, 0xFF000000, 0.42f);
        int right = blend(color, 0xFF000000, 0.18f);
        int roof = blend(color, 0xFFFFFFFF, 0.16f);

        // Night lights switch on tower by tower as dusk falls rather than all at once.
        float night = 1f - tod;
        float stagger = ((seedIdx * 37) % 25) / 25f;
        float on = Math.max(0f, Math.min(1f, (night - 0.35f - stagger * 0.25f) * 5f));

        paint.setStyle(Paint.Style.FILL);
        if (doorGlow[gx][gy] > 0f) {
            Fx.glow(c, sx, sy + th + bodyH, tw * 0.6f, ((int) (doorGlow[gx][gy] * 0xAA) << 24) | 0xFFD27A);
        }
        // Left face.
        paint.setColor(left);
        path.reset();
        path.moveTo(sx - tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bodyH);
        path.lineTo(sx - tw, sy + bodyH);
        path.close();
        c.drawPath(path, paint);
        // Right face.
        paint.setColor(right);
        path.reset();
        path.moveTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bodyH);
        path.lineTo(sx + tw, sy + bodyH);
        path.close();
        c.drawPath(path, paint);
        // Roof.
        paint.setColor(roof);
        path.reset();
        path.moveTo(sx, sy - th);
        path.lineTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx - tw, sy);
        path.close();
        c.drawPath(path, paint);
        if (on > 0.05f) {
            // A lit crown on every tower you have built.
            int crown = blend(color, 0xFFFFFFFF, 0.65f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(((int) (on * 220) << 24) | (crown & 0xFFFFFF));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            Fx.glow(c, sx, sy, tw * 0.9f, ((int) (on * 0x50) << 24) | (crown & 0xFFFFFF));
        }

        drawStyle(c, gx, gy, sx, sy, tw, th, bh, bodyH, color, tod, on);

        // Windows: one row per floor. Floors built this session burn gold after dark; the older
        // city lights up by the energy put in this session.
        long seed = windowSeed[seedIdx];
        int todayFrom = floors - sessionFloors[gx][gy];
        int brokenFrom = floors - damage[gx][gy];
        boolean cool = (seed & 1L) == 0;
        int litOld = cool ? 0xFFDDEBFF : 0xFFFFE8A8;
        int dark = blend(color, 0xFF000000, 0.55f);
        for (int f = 0; f < floors; f++) {
            float wy = sy + bodyH - f * bh - bh * 0.55f;
            for (int k = 0; k < 2; k++) {
                long bits = seed >>> ((f * 3 + k * 5) % 56);
                int wcol;
                if (f >= brokenFrom) {
                    wcol = 0xFF14100C;                   // blown out by the storm
                } else if (f >= todayFrom) {
                    wcol = blend(dark, 0xFFFFC94A, Math.max(on, 0.15f));
                } else {
                    boolean lit = ((bits & 0xFFL) / 255f) < 0.45f + 0.55f * litShare;
                    wcol = lit ? blend(dark, litOld, on) : dark;
                }
                paint.setColor(wcol);
                float ox = tw * (0.32f + k * 0.34f);
                float wtop = wy + th * (1 - ox / tw) * 0.5f;
                c.drawRect(sx - ox - tw * 0.1f, wtop, sx - ox + tw * 0.1f, wtop + bh * 0.28f, paint);
                c.drawRect(sx + ox - tw * 0.1f, wtop, sx + ox + tw * 0.1f, wtop + bh * 0.28f, paint);
            }
        }
        // A mast with a blinking light on the tall ones.
        if (floors >= 8) {
            paint.setColor(0xFF9AA5B1);
            c.drawRect(sx - dp(1.5f), sy - th - dp(22f), sx + dp(1.5f), sy - th, paint);
            boolean blink = ((int) (activeSeconds * 1.5) % 2) == 0;
            paint.setColor(blink ? 0xFFFF4D4D : 0x66FF4D4D);
            c.drawCircle(sx, sy - th - dp(24f), dp(3f), paint);
        }
        if (damage[gx][gy] > 0) {
            drawDamage(c, gx, gy, sx, sy, tw, th, bh);
        }
        if (repairFlash[gx][gy] > 0f) {
            Fx.glow(c, sx, sy + th, tw * 1.1f,
                    ((int) (repairFlash[gx][gy] * 0x88) << 24) | 0xFFE8A8);
        }
        if (civic[gx][gy] >= 0) {
            drawCivicSign(c, gx, gy, sx, sy, th, tod);
        }
    }

    /**
     * What the rating unlocked, drawn on the tower itself: tile roofs, glass, spires, gold crowns.
     * A plot is clad at whatever tier the city could build at when that tower last grew, so a old
     * city reads as layers of its own history.
     */
    private void drawStyle(Canvas c, int gx, int gy, float sx, float sy, float tw, float th,
                           float bh, float bodyH, int color, float tod, float on) {
        switch (style[gx][gy]) {
            case 1: {
                // Brick and tile: a hipped roof over the prism.
                float apex = sy - tw * 0.5f;
                int tile = blend(0xFF6A2E24, 0xFFC2553F, tod);
                paint.setColor(blend(tile, 0xFF000000, 0.3f));
                path.reset();
                path.moveTo(sx - tw, sy);
                path.lineTo(sx, sy + th);
                path.lineTo(sx, apex);
                path.close();
                c.drawPath(path, paint);
                paint.setColor(tile);
                path.reset();
                path.moveTo(sx + tw, sy);
                path.lineTo(sx, sy + th);
                path.lineTo(sx, apex);
                path.close();
                c.drawPath(path, paint);
                break;
            }
            case 2: {
                // Glass: a mirrored band running the full height of each face. It has to be bodyH,
                // not bh - one floor's worth is a sliver at the roof line that reads as nothing on
                // a ten-storey tower, which is the whole point of the tier.
                paint.setColor(0x33FFFFFF);
                bandOnFace(c, sx, sy, tw, th, bodyH, 0.34f, 0.56f, true);
                paint.setColor(0x22FFFFFF);
                bandOnFace(c, sx, sy, tw, th, bodyH, 0.42f, 0.62f, false);
                break;
            }
            case 3: {
                // Spires: a tapered cap and a needle.
                paint.setColor(blend(color, 0xFFFFFFFF, 0.42f));
                path.reset();
                path.moveTo(sx - tw * 0.5f, sy - th * 0.1f);
                path.lineTo(sx + tw * 0.5f, sy - th * 0.1f);
                path.lineTo(sx, sy - tw * 0.95f);
                path.close();
                c.drawPath(path, paint);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(0xFFCBD5E4);
                c.drawLine(sx, sy - tw * 0.95f, sx, sy - tw * 1.45f, paint);
                if (on > 0.05f) {
                    Fx.glow(c, sx, sy - tw * 1.45f, dp(16f), ((int) (on * 0xAA) << 24) | 0xBFE4FF);
                }
                break;
            }
            case 4: {
                // Golden crowns: a gold band at the parapet and a helipad on the roof.
                paint.setColor(0xFFE8C05A);
                path.reset();
                path.moveTo(sx - tw, sy);
                path.lineTo(sx, sy + th);
                path.lineTo(sx, sy + th + bh * 0.3f);
                path.lineTo(sx - tw, sy + bh * 0.3f);
                path.close();
                c.drawPath(path, paint);
                paint.setColor(0xFFC79A33);
                path.reset();
                path.moveTo(sx + tw, sy);
                path.lineTo(sx, sy + th);
                path.lineTo(sx, sy + th + bh * 0.3f);
                path.lineTo(sx + tw, sy + bh * 0.3f);
                path.close();
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setColor(0xFFF4F1E8);
                c.drawOval(sx - tw * 0.36f, sy - th * 0.34f, sx + tw * 0.36f, sy + th * 0.34f, paint);
                paint.setStyle(Paint.Style.FILL);
                bold(c, "H", sx, sy + th * 0.22f, 9f, 0xFFF4F1E8, Paint.Align.CENTER);
                if (on > 0.05f) {
                    Fx.glow(c, sx, sy, tw * 1.2f, ((int) (on * 0x66) << 24) | 0xFFD24A);
                }
                break;
            }
            default:
                break;
        }
    }

    /** A vertical band down one face of the prism, between two fractions of its top edge. */
    private void bandOnFace(Canvas c, float sx, float sy, float tw, float th, float bh,
                            float u0, float u1, boolean rightFace) {
        float s = rightFace ? 1f : -1f;
        float x0 = sx + s * tw * u0;
        float y0 = sy + th * (1f - u0);
        float x1 = sx + s * tw * u1;
        float y1 = sy + th * (1f - u1);
        path.reset();
        path.moveTo(x0, y0);
        path.lineTo(x1, y1);
        path.lineTo(x1, y1 + bh);
        path.lineTo(x0, y0 + bh);
        path.close();
        c.drawPath(path, paint);
    }

    /** Broken floors: a charred, scaffolded band at the top of the stack, sparking. */
    private void drawDamage(Canvas c, int gx, int gy, float sx, float sy, float tw, float th,
                            float bh) {
        float broken = damage[gx][gy] * bh;
        float top = sy + th;
        float bottom = top + broken;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA100C08);
        path.reset();
        path.moveTo(sx - tw, sy);
        path.lineTo(sx, top);
        path.lineTo(sx, bottom);
        path.lineTo(sx - tw, sy + broken);
        path.close();
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(sx + tw, sy);
        path.lineTo(sx, top);
        path.lineTo(sx, bottom);
        path.lineTo(sx + tw, sy + broken);
        path.close();
        c.drawPath(path, paint);
        // Hazard tape across the break line, and a blinking marker above it.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xFFF0B132);
        c.drawLine(sx - tw, sy + broken, sx, bottom, paint);
        c.drawLine(sx, bottom, sx + tw, sy + broken, paint);
        paint.setStyle(Paint.Style.FILL);
        boolean blink = ((int) (sessionSeconds * 2.5)) % 2 == 0;
        paint.setColor(blink ? 0xFFF0655D : 0x55F0655D);
        c.drawCircle(sx, sy - th - dp(8f), dp(4f), paint);
        // Embers drifting off the break, one every few frames so nothing accumulates.
        if (rng.nextFloat() < 0.08f) {
            fx.spawn(sx + (rng.nextFloat() - 0.5f) * tw, sy + broken * 0.5f,
                    (rng.nextFloat() - 0.5f) * dp(20f), -dp(24f), 0.9f, dp(2f), 0xAAFFA05A, false);
        }
    }

    /** The sign over a building the citizens asked for. It stays for good. */
    private void drawCivicSign(Canvas c, int gx, int gy, float sx, float sy, float th, float tod) {
        int k = civic[gx][gy];
        int col = CIVIC_COLORS[k];
        float y = sy - th - dp(14f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(col);
        c.drawRoundRect(sx - dp(9f), y - dp(9f), sx + dp(9f), y + dp(9f), dp(3f), dp(3f), paint);
        paint.setColor(0xFF10141C);
        // A plus for a hospital, otherwise the building's initial.
        if (k == 0 || k == 6) {
            c.drawRect(sx - dp(5f), y - dp(1.6f), sx + dp(5f), y + dp(1.6f), paint);
            c.drawRect(sx - dp(1.6f), y - dp(5f), sx + dp(1.6f), y + dp(5f), paint);
        } else {
            bold(c, CIVIC_NAMES[k].substring(0, 1), sx, y + dp(4f), 11f, 0xFF10141C,
                    Paint.Align.CENTER);
        }
        if (tod < 0.45f) {
            Fx.glow(c, sx, y, dp(26f), 0x66000000 | (col & 0xFFFFFF));
        }
        label(c, CIVIC_NAMES[k], sx, y - dp(14f), 7.5f, 0xCC000000 | (col & 0xFFFFFF),
                Paint.Align.CENTER);
    }

    private void drawBlock(Canvas c, int gx, int gy, float z, int color) {
        project(gx, gy, z);
        float sx = px;
        float sy = py;
        float tw = gTw;
        float th = gTh;
        float bh = gBh;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(color, 0xFF000000, 0.42f));
        path.reset();
        path.moveTo(sx - tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bh);
        path.lineTo(sx - tw, sy + bh);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(blend(color, 0xFF000000, 0.18f));
        path.reset();
        path.moveTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bh);
        path.lineTo(sx + tw, sy + bh);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(blend(color, 0xFFFFFFFF, 0.22f));
        path.reset();
        path.moveTo(sx, sy - th);
        path.lineTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx - tw, sy);
        path.close();
        c.drawPath(path, paint);
    }

    /* ---------------------------------------------------------------- scenery ---------- */

    /**
     * 3.19.5: the sky was empty. Clouds drift by day, a plane crosses with blinking lights and a
     * contrail, a flock passes, and at night searchlights sweep and the odd shooting star falls.
     */
    private void drawSkyTraffic(Canvas c, float w, float h, float tod) {
        double t = sessionSeconds;
        int cloudAlpha = (int) (60 + 150 * tod);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {
            float span = w + dp(400f);
            float cx = (float) (((i * 431 + 60) + t * dp(6f + i * 2f)) % span) - dp(200f);
            float cy = h * (0.10f + (i % 3) * 0.08f);
            float sc = 0.7f + (i % 2) * 0.4f;
            paint.setColor((cloudAlpha << 24) | 0xFFFFFF);
            c.drawOval(cx - dp(80f) * sc, cy - dp(12f) * sc, cx + dp(80f) * sc, cy + dp(12f) * sc, paint);
            c.drawOval(cx - dp(38f) * sc, cy - dp(28f) * sc, cx + dp(40f) * sc, cy + dp(4f) * sc, paint);
        }
        // A plane every 24 s, left to right, high up.
        double lap = (t % 24) / 24;
        float px0 = (float) (-dp(80f) + (w + dp(160f)) * lap);
        float py0 = h * 0.08f + (float) lap * h * 0.04f;
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0x55FFFFFF);
        c.drawLine(px0 - dp(160f), py0 + dp(3f), px0 - dp(20f), py0, paint);
        paint.setColor(tod > 0.4f ? 0xFFE9EEF5 : 0xFF3A4660);
        c.drawRoundRect(px0 - dp(18f), py0 - dp(3f), px0 + dp(18f), py0 + dp(3f), dp(3f), dp(3f), paint);
        c.drawRect(px0 - dp(4f), py0 - dp(10f), px0 + dp(4f), py0 + dp(10f), paint);
        if (((int) (t * 2)) % 2 == 0) {
            paint.setColor(0xFFFF4A4A);
            c.drawCircle(px0 - dp(1f), py0 - dp(10f), dp(2.5f), paint);
            paint.setColor(0xFF4AFF7A);
            c.drawCircle(px0 - dp(1f), py0 + dp(10f), dp(2.5f), paint);
        }
        if (tod > 0.3f) {
            // Birds by day.
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xAA1A2230);
            float fx0 = (float) (w - ((t * dp(40f)) % (w + dp(300f))));
            for (int b = 0; b < 6; b++) {
                float bx = fx0 + b * dp(22f) + (b % 2) * dp(8f);
                float by = h * 0.22f + (b % 3) * dp(9f);
                float flap = (float) Math.sin(t * 8 + b) * dp(4f);
                c.drawLine(bx - dp(7f), by - flap, bx, by, paint);
                c.drawLine(bx, by, bx + dp(7f), by - flap, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else {
            // Searchlights from the city, and a shooting star now and then.
            for (int k = 0; k < 2; k++) {
                double a = -Math.PI / 2 + Math.sin(t * 0.5 + k * 2.2) * 0.5;
                float ox = w * (k == 0 ? 0.38f : 0.62f);
                float oy = h * 0.72f;
                float len = h * 0.9f;
                float tx = ox + (float) Math.cos(a) * len;
                float ty = oy + (float) Math.sin(a) * len;
                float nx = (float) -Math.sin(a) * dp(40f);
                float ny = (float) Math.cos(a) * dp(40f);
                path.reset();
                path.moveTo(ox, oy);
                path.lineTo(tx + nx, ty + ny);
                path.lineTo(tx - nx, ty - ny);
                path.close();
                paint.setColor(0x1ADFEBFF);
                c.drawPath(path, paint);
            }
            double star = (t % 9) / 9;
            if (star < 0.12) {
                float f = (float) (star / 0.12);
                float sx = w * 0.2f + f * w * 0.35f;
                float sy = h * 0.06f + f * h * 0.14f;
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (255 * (1 - f)) << 24) | 0xFFFFFF);
                c.drawLine(sx - dp(60f), sy - dp(24f), sx, sy, paint);
            }
        }
    }

    /**
     * 3.19.7: the left third of the tablet screen was bare beside the crane. A site fence with a
     * gate, a parked truck and a site hut fill it, a foreman walks the line, and floodlights wash
     * the ground at night - the building site the crane obviously belongs to.
     */
    private void drawSiteLife(Canvas c, float w, float h, float tod) {
        double t = activeSeconds;
        float ground = h * 0.86f;
        float left = w * 0.02f;
        float right = w * 0.30f;
        // Floodlights after dark.
        if (tod < 0.5f) {
            for (int i = 0; i < 2; i++) {
                float lx = left + dp(40f) + i * dp(120f);
                Fx.glow(c, lx, ground - dp(10f), dp(90f), 0x33FFE6A8);
                paint.setColor(0xFF2A3446);
                c.drawRect(lx - dp(2f), ground - dp(70f), lx + dp(2f), ground, paint);
                paint.setColor(0xFFFFE6A8);
                c.drawRect(lx - dp(9f), ground - dp(78f), lx + dp(9f), dp(6f) + ground - dp(78f), paint);
            }
        }
        // Site hut.
        paint.setColor(0xFF6E7684);
        c.drawRect(left, ground - dp(46f), left + dp(86f), ground, paint);
        paint.setColor(0xFF4C535E);
        c.drawRect(left, ground - dp(52f), left + dp(86f), ground - dp(44f), paint);
        paint.setColor(tod < 0.5f ? 0xFFFFE6A8 : 0xFF2A3446);
        c.drawRect(left + dp(12f), ground - dp(36f), left + dp(30f), ground - dp(20f), paint);
        paint.setColor(0xFF35404E);
        c.drawRect(left + dp(52f), ground - dp(34f), left + dp(72f), ground, paint);
        // Fence with a gap for the gate.
        paint.setColor(0xFF5A6472);
        for (float fx0 = left + dp(96f); fx0 < right; fx0 += dp(18f)) {
            if (fx0 > left + dp(150f) && fx0 < left + dp(196f)) {
                continue;
            }
            c.drawRect(fx0, ground - dp(30f), fx0 + dp(3f), ground, paint);
        }
        c.drawRect(left + dp(96f), ground - dp(30f), right, ground - dp(27f), paint);
        // Parked truck with a tipper bed.
        float tx = right - dp(74f);
        paint.setColor(0xFFE0582E);
        c.drawRoundRect(tx, ground - dp(30f), tx + dp(34f), ground - dp(8f), dp(3f), dp(3f), paint);
        paint.setColor(0xFF9AA5B1);
        c.drawRect(tx + dp(34f), ground - dp(24f), tx + dp(72f), ground - dp(8f), paint);
        paint.setColor(0xFF2A3446);
        c.drawCircle(tx + dp(12f), ground - dp(6f), dp(6f), paint);
        c.drawCircle(tx + dp(56f), ground - dp(6f), dp(6f), paint);
        // Foreman pacing the fence line, turning at each end.
        float span = right - left - dp(130f);
        float walk = (float) ((t * dp(26f)) % (span * 2));
        float fxp = left + dp(104f) + (walk < span ? walk : span * 2 - walk);
        boolean facing = walk < span;
        float step = (float) Math.sin(t * 6) * dp(4f);
        paint.setColor(0xFFF5C518);
        c.drawRect(fxp - dp(5f), ground - dp(26f), fxp + dp(5f), ground - dp(12f), paint);
        paint.setColor(0xFF2A2F3A);
        c.drawRect(fxp - dp(4f) + step, ground - dp(12f), fxp - dp(1f) + step, ground, paint);
        c.drawRect(fxp + dp(1f) - step, ground - dp(12f), fxp + dp(4f) - step, ground, paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(fxp, ground - dp(31f), dp(5f), paint);
        paint.setColor(0xFFF5C518);
        c.drawArc(fxp - dp(6f), ground - dp(39f), fxp + dp(6f), ground - dp(27f), 180, 180, true, paint);
        paint.setColor(0xFF2A2F3A);
        c.drawCircle(fxp + (facing ? dp(2f) : -dp(2f)), ground - dp(31f), dp(1.2f), paint);
    }

    /* ---------------------------------------------------------------- HUD ---------- */

    private void drawHud(Canvas c, float w, float h) {
        bold(c, String.valueOf(lifetime), w * 0.5f, dp(34f), 34f, ACCENT, Paint.Align.CENTER);
        label(c, "BLOCKS IN THE CITY", w * 0.5f, dp(48f), 9f, FAINT, Paint.Align.CENTER);
        String next = null;
        if (lifetime < PARK_AT) {
            next = "PARK OPENS IN " + (PARK_AT - lifetime) + " BLOCKS";
        } else if (lifetime < BRIDGE_AT) {
            next = "RIVER AND BRIDGE OPEN IN " + (BRIDGE_AT - lifetime) + " BLOCKS";
        } else if (lifetime >= GRID * GRID * maxHeight()) {
            next = "EVERY PLOT IS BUILT - ROW FOR THE LANDMARK";
        }
        if (next != null) {
            bold(c, next, w * 0.5f, dp(66f), 10f, WARN, Paint.Align.CENTER);
        }

        drawCrane(c, w, h);
        drawTodayTower(c, w, h);
        drawLandmarkPanel(c, w);
        drawPhotoButton(c);
        drawRatingPanel(c, w);
        drawWeekPanel(c);
        drawRequestCard(c, w);
        drawCentrePanel(c, w);

        float fy = h - dp(12f);
        float col = w / 5f;
        stat(c, col * 0.5f, fy, String.valueOf(placedThisSession), "THIS SESSION");
        stat(c, col * 1.5f, fy, tallest + " floors", "TALLEST");
        stat(c, col * 2.5f, fy, String.valueOf(population), "RESIDENTS");
        stat(c, col * 3.5f, fy, String.valueOf(civicCount), "CIVIC BUILDINGS");
        stat(c, col * 4.5f, fy, damageTotal > 0 ? damageTotal + " broken"
                : (falling.isEmpty() ? "--" : String.valueOf(falling.size())),
                damageTotal > 0 ? "REPAIRS DUE" : "IN THE AIR");
    }

    /* ---------------------------------------------------------------- HUD panels ---------- */

    /** Stars, the tier's name, and what the next one unlocks. */
    private void drawRatingPanel(Canvas c, float w) {
        float x0 = dp(132f);
        float y = dp(26f);
        paint.setStyle(Paint.Style.FILL);
        float starX = x0;
        for (int i = 0; i < RATING_NAMES.length; i++) {
            float fill = Math.max(0f, Math.min(1f, ratingShown - i + 1f));
            float pulse = i == ratingTier ? 1f + ratingFlash * 0.25f : 1f;
            star(c, starX + i * dp(22f), y, dp(9f) * pulse, fill);
        }
        float tx = x0 + RATING_NAMES.length * dp(22f) + dp(6f);
        bold(c, RATING_NAMES[ratingTier], tx, y + dp(5f), 14f,
                ratingFlash > 0f ? 0xFFFFD24A : TEXT, Paint.Align.LEFT);
        float next = ratingToNext();
        String line = next < 0
                ? "TOP RATING - " + STYLE_NAMES[ratingTier] + ", " + maxHeight() + " FLOOR LIMIT"
                : STYLE_NAMES[ratingTier] + "  -  " + (int) Math.ceil(next) + " TO "
                        + RATING_NAMES[ratingTier + 1] + " (" + STYLE_NAMES[ratingTier + 1] + ")";
        label(c, line, tx, y + dp(20f), 8.5f, next < 0 ? ACCENT : DIM, Paint.Align.LEFT);
    }

    private void star(Canvas c, float cx, float cy, float r, float fill) {
        path.reset();
        for (int i = 0; i < 10; i++) {
            double a = -Math.PI / 2 + i * Math.PI / 5;
            float rr = (i % 2 == 0) ? r : r * 0.45f;
            float x = cx + (float) Math.cos(a) * rr;
            float y = cy + (float) Math.sin(a) * rr;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(0xFF2A3446, 0xFFFFD24A, fill));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x66E6EDF7);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** This week's floors beside last week's, as two stacks that grow while you row. */
    private void drawWeekPanel(Canvas c) {
        float x0 = dp(206f);
        float y0 = dp(58f);
        float wpx = dp(268f);
        float hpx = dp(96f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44000000);
        c.drawRoundRect(x0, y0, x0 + wpx, y0 + hpx, dp(8f), dp(8f), paint);
        label(c, "THIS WEEK", x0 + dp(10f), y0 + dp(14f), 8.5f, FAINT, Paint.Align.LEFT);
        String verdict;
        int colr;
        if (lastWeekFloors <= 0) {
            verdict = "FIRST WEEK";
            colr = DIM;
        } else if (weekFloors >= lastWeekFloors) {
            verdict = "+" + (weekFloors - lastWeekFloors) + " AHEAD";
            colr = ACCENT;
        } else {
            verdict = (lastWeekFloors - weekFloors) + " TO BEAT";
            colr = WARN;
        }
        bold(c, verdict, x0 + wpx - dp(10f), y0 + dp(15f), 10f, colr, Paint.Align.RIGHT);

        float base = y0 + hpx - dp(20f);
        float top = y0 + dp(24f);
        float scale = Math.max(1f, Math.max(weekBarA, weekBarB));
        float bw = dp(54f);
        for (int i = 0; i < 2; i++) {
            float v = i == 0 ? weekBarA : weekBarB;
            float bx = x0 + dp(34f) + i * dp(96f);
            float bh2 = (base - top) * Math.min(1f, v / scale);
            paint.setColor(0x22FFFFFF);
            c.drawRect(bx, top, bx + bw, base, paint);
            paint.setColor(i == 0 ? (passedLastWeek ? 0xFF35D0BA : 0xFF6F8CFF) : 0xFF5D6B80);
            c.drawRect(bx, base - bh2, bx + bw, base, paint);
            // Floor lines, so the column reads as a stack of storeys rather than a bar.
            paint.setColor(0x33000000);
            for (float yy = base - dp(6f); yy > base - bh2; yy -= dp(6f)) {
                c.drawRect(bx, yy, bx + bw, yy + dp(1f), paint);
            }
            if (i == 0 && passedLastWeek) {
                Fx.glow(c, bx + bw / 2f, base - bh2, dp(30f), 0x66FFD24A);
            }
            bold(c, String.valueOf(i == 0 ? weekFloors : lastWeekFloors), bx + bw / 2f,
                    base - bh2 - dp(5f), 11f, i == 0 ? TEXT : DIM, Paint.Align.CENTER);
            label(c, i == 0 ? "NOW" : "LAST WEEK", bx + bw / 2f, base + dp(13f), 8f, FAINT,
                    Paint.Align.CENTER);
        }
        // Last week's line drawn across this week's column: the bar to clear.
        if (lastWeekFloors > 0) {
            float ly = base - (base - top) * Math.min(1f, weekBarB / scale);
            paint.setColor(0x99FFD24A);
            c.drawRect(x0 + dp(28f), ly - dp(1f), x0 + dp(96f), ly + dp(1f), paint);
        }
    }

    /** The citizens' ask, top right under the landmark. */
    private void drawRequestCard(Canvas c, float w) {
        float x0 = w - dp(392f);
        float x1 = w - dp(96f);
        float y0 = dp(122f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44000000);
        c.drawRoundRect(x0, y0, x1, y0 + dp(80f), dp(8f), dp(8f), paint);
        if (!requestOpen) {
            label(c, "THE CITIZENS", x0 + dp(12f), y0 + dp(20f), 8.5f, FAINT, Paint.Align.LEFT);
            String line = !hasClockStarted() ? "A DELEGATION IS ON ITS WAY"
                    : requestCooldown > 0f ? "TALKING IT OVER - " + (int) Math.ceil(requestCooldown) + "s"
                    : "NOTHING ASKED FOR";
            bold(c, line, x0 + dp(12f), y0 + dp(42f), 12f, DIM, Paint.Align.LEFT);
            label(c, requestsThisSession + " GRANTED TODAY  -  " + civicCount + " IN THE CITY",
                    x0 + dp(12f), y0 + dp(64f), 8.5f, FAINT, Paint.Align.LEFT);
            return;
        }
        int done = Math.max(0, placedThisSession - requestBase);
        float f = Math.min(1f, done / (float) Math.max(1, requestNeed));
        int col = CIVIC_COLORS[requestKind];
        label(c, "THE CITIZENS ASK FOR", x0 + dp(12f), y0 + dp(20f), 8.5f, FAINT, Paint.Align.LEFT);
        float bob = (float) Math.sin(requestPulse * 3.0) * dp(1.5f);
        bold(c, CIVIC_NAMES[requestKind], x0 + dp(12f), y0 + dp(42f) + bob, 17f, col, Paint.Align.LEFT);
        bold(c, done + " / " + requestNeed, x1 - dp(12f), y0 + dp(42f), 15f,
                f >= 1f ? ACCENT : TEXT, Paint.Align.RIGHT);
        float by = y0 + dp(54f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(x0 + dp(12f), by, x1 - dp(12f), by + dp(9f), dp(4.5f), dp(4.5f), paint);
        paint.setColor(col);
        c.drawRoundRect(x0 + dp(12f), by, x0 + dp(12f) + (x1 - x0 - dp(24f)) * f, by + dp(9f),
                dp(4.5f), dp(4.5f), paint);
        label(c, "FLOORS THIS SESSION - IT GOES ON YOUR TALLEST NEW TOWER", x0 + dp(12f),
                by + dp(22f), 8f, FAINT, Paint.Align.LEFT);
    }

    /**
     * The middle of the top bar: a rush hour if one is running or offered, the storm if one is
     * overhead, and the repair queue while anything is broken. They never overlap - a storm cannot
     * start during a rush, and a rush cannot be started during a storm or with repairs outstanding.
     */
    private void drawCentrePanel(Canvas c, float w) {
        float cx = w * 0.5f;
        float x0 = cx - dp(200f);
        float x1 = cx + dp(200f);
        float y0 = dp(80f);
        rushBtn.setEmpty();
        paint.setStyle(Paint.Style.FILL);

        if (rushRunning) {
            int done = Math.max(0, placedThisSession - rushBase);
            float f = Math.min(1f, done / (float) Math.max(1, rushTarget));
            float elapsed = 1f - Math.max(0f, rushLeft) / RUSH_SECONDS;
            boolean behind = f < elapsed;
            paint.setColor(0x66000000);
            c.drawRoundRect(x0, y0, x1, y0 + dp(78f), dp(8f), dp(8f), paint);
            int secs = (int) Math.ceil(Math.max(0f, rushLeft));
            boolean urgent = secs <= 10;
            float beat = urgent ? 1f + 0.12f * (float) Math.abs(Math.sin(sessionSeconds * 6)) : 1f;
            bold(c, "RUSH HOUR", x0 + dp(14f), y0 + dp(24f), 13f, 0xFFFFD24A, Paint.Align.LEFT);
            bold(c, clock(secs), x1 - dp(14f), y0 + dp(26f), 18f * beat,
                    urgent ? BAD : TEXT, Paint.Align.RIGHT);
            bold(c, done + " / " + rushTarget + " FLOORS", cx, y0 + dp(24f), 14f,
                    behind ? WARN : ACCENT, Paint.Align.CENTER);
            float by = y0 + dp(36f);
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(x0 + dp(14f), by, x1 - dp(14f), by + dp(14f), dp(7f), dp(7f), paint);
            paint.setColor(behind ? WARN : ACCENT);
            c.drawRoundRect(x0 + dp(14f), by, x0 + dp(14f) + (x1 - x0 - dp(28f)) * f, by + dp(14f),
                    dp(7f), dp(7f), paint);
            // The pace line: where the city expects you to be this second.
            float px2 = x0 + dp(14f) + (x1 - x0 - dp(28f)) * Math.min(1f, elapsed);
            paint.setColor(0xFFFFFFFF);
            c.drawRect(px2 - dp(1.5f), by - dp(4f), px2 + dp(1.5f), by + dp(18f), paint);
            label(c, behind ? "BEHIND THE PACE - PULL" : "AHEAD OF THE PACE", cx, y0 + dp(66f), 9f,
                    behind ? WARN : ACCENT, Paint.Align.CENTER);
            return;
        }

        if (stormPhase != STORM_NONE) {
            paint.setColor(0x66000000);
            c.drawRoundRect(x0, y0, x1, y0 + dp(78f), dp(8f), dp(8f), paint);
            String title = stormPhase == STORM_WARNING ? "STORM INCOMING"
                    : stormPhase == STORM_OVERHEAD ? "STORM OVERHEAD" : "STORM CLEARING";
            bold(c, title, x0 + dp(14f), y0 + dp(24f), 13f, stormPhase == STORM_OVERHEAD ? BAD : WARN,
                    Paint.Align.LEFT);
            if (stormPhase == STORM_OVERHEAD) {
                bold(c, "NEXT BOLT " + (int) Math.ceil(Math.max(0f, strikeIn)) + "s", x1 - dp(14f),
                        y0 + dp(24f), 13f, strikeIn < 2f ? BAD : TEXT, Paint.Align.RIGHT);
            } else {
                bold(c, (int) Math.ceil(Math.max(0f, stormT)) + "s", x1 - dp(14f), y0 + dp(24f), 13f,
                        TEXT, Paint.Align.RIGHT);
            }
            float by = y0 + dp(36f);
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(x0 + dp(14f), by, x1 - dp(14f), by + dp(14f), dp(7f), dp(7f), paint);
            paint.setColor(shield > 0.55f ? 0xFF7FD8FF : shield > 0.25f ? WARN : BAD);
            c.drawRoundRect(x0 + dp(14f), by, x0 + dp(14f) + (x1 - x0 - dp(28f)) * shield,
                    by + dp(14f), dp(7f), dp(7f), paint);
            label(c, "STORM SHIELD - YOUR POWER RIGHT NOW", x0 + dp(14f), y0 + dp(66f), 9f,
                    shield > 0.55f ? ACCENT : WARN, Paint.Align.LEFT);
            if (stormNoteT > 0f && stormNote != null) {
                bold(c, stormNote, x1 - dp(14f), y0 + dp(68f), 14f,
                        stormNote.startsWith("-") ? BAD : 0xFF7FD8FF, Paint.Align.RIGHT);
            }
            return;
        }

        if (damageTotal > 0) {
            paint.setColor(0x66000000);
            c.drawRoundRect(x0, y0, x1, y0 + dp(60f), dp(8f), dp(8f), paint);
            bold(c, "REPAIRS", x0 + dp(14f), y0 + dp(24f), 13f, WARN, Paint.Align.LEFT);
            bold(c, damageTotal + (damageTotal == 1 ? " FLOOR" : " FLOORS"), x1 - dp(14f),
                    y0 + dp(24f), 14f, WARN, Paint.Align.RIGHT);
            float by = y0 + dp(32f);
            float f = Math.min(1f, concrete / REPAIR_COST);
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(x0 + dp(14f), by, x1 - dp(14f), by + dp(10f), dp(5f), dp(5f), paint);
            paint.setColor(WARN);
            c.drawRoundRect(x0 + dp(14f), by, x0 + dp(14f) + (x1 - x0 - dp(28f)) * f, by + dp(10f),
                    dp(5f), dp(5f), paint);
            label(c, "EVERY STROKE GOES INTO THE REPAIRS - NOTHING NEW UNTIL THEY ARE DONE",
                    cx, y0 + dp(54f), 8.5f, FAINT, Paint.Align.CENTER);
            return;
        }

        // Idle: the offer.
        rushBtn.set(cx - dp(120f), y0, cx + dp(120f), y0 + dp(44f));
        boolean ready = rushAvailable();
        float pulse = ready ? 0.5f + 0.5f * (float) Math.abs(Math.sin(sessionSeconds * 2.2)) : 0f;
        paint.setColor(ready ? (((int) (40 + 60 * pulse)) << 24) | 0xFFD24A : 0x33000000);
        c.drawRoundRect(rushBtn, dp(10f), dp(10f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(ready ? 0xFFFFD24A : 0x44E6EDF7);
        c.drawRoundRect(rushBtn, dp(10f), dp(10f), paint);
        paint.setStyle(Paint.Style.FILL);
        String offer = ready ? "START RUSH HOUR"
                : !hasClockStarted() ? "ROW TO WAKE THE CITY"
                : rushCooldown > 0f ? "RUSH HOUR IN " + (int) Math.ceil(rushCooldown) + "s"
                : "RUSH HOUR UNAVAILABLE";
        bold(c, offer, cx, y0 + dp(27f), 14f, ready ? 0xFFFFD24A : DIM, Paint.Align.CENTER);
        label(c, rushWins + " CLEARED  -  BEST " + rushBestFloors + " FLOORS IN TWO MINUTES", cx,
                y0 + dp(58f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    private void drawLandmarkPanel(Canvas c, float w) {
        float x0 = w - dp(360f);
        float x1 = w - dp(100f);
        float y = dp(22f);
        label(c, "THIS WEEK'S LANDMARK", x0, y, 8.5f, FAINT, Paint.Align.LEFT);
        label(c, landmarksBuilt + " BUILT", x1, y, 8.5f, FAINT, Paint.Align.RIGHT);
        bold(c, LANDMARK_NAMES[landmarkKind], x0, y + dp(18f), 14f, TEXT, Paint.Align.LEFT);
        float f = landmarkFraction();
        float by = y + dp(26f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(x0, by, x1, by + dp(9f), dp(4.5f), dp(4.5f), paint);
        paint.setColor(landmarkDone ? ACCENT : WARN);
        c.drawRoundRect(x0, by, x0 + (x1 - x0) * f, by + dp(9f), dp(4.5f), dp(4.5f), paint);
        long now = System.currentTimeMillis();
        long localDays = (now + zone.getOffset(now)) / 86400000L;
        int daysLeft = (int) (7 - (localDays + 3) % 7);
        String line;
        if (landmarkDone) {
            line = "BUILT - A NEW ONE STARTS MONDAY";
        } else {
            double minutes = (landmarkTarget - landmarkWork) / Math.max(1.0, profile.typicalWatts()) / 60.0;
            line = Math.round(f * 100) + "%  -  ~" + (int) Math.ceil(minutes) + " MIN OF ROWING  -  "
                    + daysLeft + (daysLeft == 1 ? " DAY LEFT" : " DAYS LEFT");
        }
        label(c, line, x0, by + dp(22f), 8.5f, landmarkDone ? ACCENT : DIM, Paint.Align.LEFT);
        if (!landmarkDone) {
            label(c, "hard strokes lay gold stones", x0, by + dp(35f), 8f, FAINT, Paint.Align.LEFT);
        }
    }

    private void drawPhotoButton(Canvas c) {
        photoBtn.set(dp(14f), dp(12f), dp(118f), dp(46f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        c.drawRoundRect(photoBtn, dp(8f), dp(8f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0x88E6EDF7);
        c.drawRoundRect(photoBtn, dp(8f), dp(8f), paint);
        // Camera glyph.
        float gx = photoBtn.left + dp(22f);
        float gy = photoBtn.centerY();
        c.drawRoundRect(gx - dp(10f), gy - dp(7f), gx + dp(10f), gy + dp(8f), dp(2f), dp(2f), paint);
        c.drawCircle(gx, gy + dp(0.5f), dp(4f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, "PHOTO", photoBtn.left + dp(40f), gy + dp(5f), 12f, TEXT, Paint.Align.LEFT);
    }

    private void drawBanner(Canvas c, float w, float h) {
        if (banner == null || bannerT <= 0f) {
            return;
        }
        float a = Math.min(1f, bannerT / 0.6f) * Math.min(1f, (3.5f - bannerT) / 0.3f + 0.2f);
        int alpha = (int) (255 * Math.max(0f, Math.min(1f, a)));
        float y = h * 0.30f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(((alpha * 150 / 255) << 24));
        c.drawRect(0, y - dp(38f), w, y + dp(16f), paint);
        bold(c, banner, w * 0.5f, y, 30f, (alpha << 24) | (WARN & 0xFFFFFF), Paint.Align.CENTER);
    }

    /** The crane and its hopper, top left: every drive pours concrete in. */
    private void drawCrane(Canvas c, float w, float h) {
        float baseX = dp(40f);
        float baseY = h * 0.62f;
        float topY = h * 0.14f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF0B132);
        c.drawRect(baseX - dp(4f), topY, baseX + dp(4f), baseY, paint);
        for (float y = topY + dp(12f); y < baseY; y += dp(18f)) {
            paint.setStrokeWidth(dp(1.5f));
            c.drawLine(baseX - dp(4f), y, baseX + dp(4f), y + dp(12f), paint);
        }
        c.drawRect(baseX - dp(10f), topY - dp(4f), baseX + dp(150f), topY + dp(4f), paint);
        float hookX = baseX + dp(120f);
        paint.setColor(0xFF9AA5B1);
        paint.setStrokeWidth(dp(1.5f));
        c.drawLine(hookX, topY + dp(4f), hookX, topY + dp(46f), paint);
        // Hopper: fills with concrete toward the next block.
        float hopW = dp(64f);
        float hopH = dp(54f);
        float hx = hookX - hopW / 2f;
        float hy = topY + dp(46f);
        paint.setColor(0x55FFFFFF);
        c.drawRect(hx, hy, hx + hopW, hy + hopH, paint);
        boolean repairing = damageTotal > 0;
        float full = Math.min(1f, concrete / (repairing ? REPAIR_COST : cost(nextUnits)));
        paint.setColor(repairing ? WARN : nextUnits >= 3 ? ACCENT : nextUnits == 2 ? BLUE : WARN);
        c.drawRect(hx, hy + hopH * (1f - full), hx + hopW, hy + hopH, paint);
        bold(c, repairing ? "REPAIR" : nextUnits + (nextUnits == 1 ? " FLOOR" : " FLOORS"), hookX,
                hy + hopH + dp(16f), 11f,
                repairing ? WARN : nextUnits > 1 ? ACCENT : TEXT, Paint.Align.CENTER);
        boolean working = isClockRunning();
        String hint;
        int hintCol;
        if (!working) {
            hint = "CRANES IDLE - ROW TO BUILD";
            hintCol = WARN;
        } else if (repairing) {
            hint = damageTotal + " floors to put back";
            hintCol = WARN;
        } else if (stormPhase == STORM_OVERHEAD && shield < 0.5f) {
            hint = "THE SHIELD IS DOWN - PULL HARD";
            hintCol = BAD;
        } else {
            hint = "pull harder for bigger blocks";
            hintCol = FAINT;
        }
        label(c, hint, hookX, hy + hopH + dp(30f), 8.5f, hintCol, Paint.Align.CENTER);
    }

    /** This session's floors as their own tower on the right, windows lit by today's energy. */
    private void drawTodayTower(Canvas c, float w, float h) {
        float floorH = dp(9f);
        float bw = dp(48f);
        float right = w - dp(22f);
        float bottom = h - dp(56f);
        int floors = Math.min(placedThisSession, (int) ((bottom - h * 0.12f) / floorH));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A3648);
        c.drawRect(right - bw - dp(6f), bottom, right + dp(6f), bottom + dp(4f), paint);
        for (int f = 0; f < floors; f++) {
            float y = bottom - (f + 1) * floorH;
            paint.setColor(0xFF3E5A7E);
            c.drawRect(right - bw, y, right, y + floorH - dp(1f), paint);
            boolean lit = ((f * 7919) % 100) / 100f < 0.2f + 0.8f * litShare;
            paint.setColor(lit ? 0xFFFFE8A8 : 0xFF22324A);
            c.drawRect(right - bw + dp(8f), y + dp(2f), right - bw + dp(18f), y + floorH - dp(3f), paint);
            c.drawRect(right - dp(18f), y + dp(2f), right - dp(8f), y + floorH - dp(3f), paint);
        }
        bold(c, "+" + placedThisSession, right - bw / 2f, bottom - floors * floorH - dp(8f), 13f, ACCENT,
                Paint.Align.CENTER);
        label(c, "TODAY", right - bw / 2f, bottom + dp(16f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    /* ---------------------------------------------------------------- photo mode ---------- */

    private void drawPhotoUi(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        bold(c, "PHOTO MODE", dp(18f), dp(30f), 14f, TEXT, Paint.Align.LEFT);
        label(c, "drag to turn the city, tap the shutter to snap it", dp(18f), dp(46f), 9f, DIM,
                Paint.Align.LEFT);

        float barTop = h - dp(100f);
        paint.setColor(0x88000000);
        c.drawRect(0, barTop, w, h, paint);

        // Album thumbnails, newest first.
        float tw = dp(112f);
        float th = dp(50f);
        float x = dp(18f);
        float ty = barTop + (h - barTop - th) / 2f;
        int shown = Math.min(album.size(), thumbRects.length);
        for (int i = 0; i < thumbRects.length; i++) {
            thumbRects[i].setEmpty();
        }
        float maxRight = w * 0.5f - dp(60f);
        for (int i = 0; i < shown; i++) {
            if (x + tw > maxRight) {
                break;
            }
            Photo p = album.get(i);
            thumbRects[i].set(x, ty, x + tw, ty + th);
            paint.setColor(0xFFFFFFFF);
            c.drawBitmap(p.thumb, null, thumbRects[i], paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1f));
            paint.setColor(0x88E6EDF7);
            c.drawRect(thumbRects[i], paint);
            paint.setStyle(Paint.Style.FILL);
            x += tw + dp(8f);
        }
        if (shown == 0) {
            label(c, "YOUR ALBUM IS EMPTY", dp(18f), barTop + dp(54f), 9f, FAINT, Paint.Align.LEFT);
        }

        // Shutter.
        snapX = w * 0.5f;
        snapY = barTop + (h - barTop) / 2f;
        snapR = dp(32f);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(snapX, snapY, snapR, paint);
        paint.setColor(0xFF20242C);
        c.drawCircle(snapX, snapY, snapR - dp(4f), paint);
        paint.setColor(flash > 0f ? ACCENT : 0xFFFFFFFF);
        c.drawCircle(snapX, snapY, snapR - dp(7f), paint);

        doneBtn.set(w - dp(150f), snapY - dp(20f), w - dp(24f), snapY + dp(20f));
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(doneBtn, dp(8f), dp(8f), paint);
        bold(c, "DONE", doneBtn.centerX(), doneBtn.centerY() + dp(5f), 14f, TEXT, Paint.Align.CENTER);

        if (photoNoteT > 0f && photoNote != null) {
            bold(c, photoNote, w * 0.5f, barTop - dp(14f), 12f, ACCENT, Paint.Align.CENTER);
        }
    }

    private void drawViewer(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6000000);
        c.drawRect(0, 0, w, h, paint);
        float maxW = w * 0.9f;
        float maxH = h * 0.82f;
        float scale = Math.min(maxW / viewing.getWidth(), maxH / viewing.getHeight());
        float vw = viewing.getWidth() * scale;
        float vh = viewing.getHeight() * scale;
        viewRect.set((w - vw) / 2f, (h - vh) / 2f - dp(10f), (w + vw) / 2f, (h + vh) / 2f - dp(10f));
        paint.setColor(0xFFFFFFFF);
        c.drawBitmap(viewing, null, viewRect, paint);
        label(c, "tap to close", w * 0.5f, viewRect.bottom + dp(22f), 10f, DIM, Paint.Align.CENTER);
    }

    private File photoDir() {
        File dir = new File(getContext().getFilesDir(), "skyline-photos");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Renders the city (no HUD) at half resolution with a caption, into the album and a PNG file. */
    private void snap() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        final Bitmap full;
        try {
            full = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            photoNote = "NOT ENOUGH MEMORY FOR A PHOTO";
            photoNoteT = 2.5f;
            return;
        }
        Canvas pc = new Canvas(full);
        pc.scale(0.5f, 0.5f);
        drawWorld(pc, w, h, true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        pc.drawRect(0, h - dp(44f), w, h, paint);
        String date = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
                .format(new java.util.Date());
        bold(pc, "SKYLINE", dp(18f), h - dp(15f), 18f, ACCENT, Paint.Align.LEFT);
        label(pc, lifetime + " blocks  -  " + population + " residents  -  tallest " + tallest
                + " floors  -  " + date, dp(120f), h - dp(15f), 13f, TEXT, Paint.Align.LEFT);

        Photo p = new Photo();
        p.full = full;
        p.thumb = Bitmap.createScaledBitmap(full, Math.max(1, w / 8), Math.max(1, h / 8), true);
        p.file = new File(photoDir(), "skyline-" + System.currentTimeMillis() + ".png");
        for (Photo old : album) {
            old.full = null;
        }
        album.add(0, p);
        while (album.size() > thumbRects.length) {
            album.remove(album.size() - 1);
        }
        flash = 1f;
        photoNote = "SNAPPED - " + album.size() + (album.size() == 1 ? " PHOTO" : " PHOTOS") + " IN THE ALBUM";
        photoNoteT = 2.5f;
        final File file = p.file;
        final File dir = file.getParentFile();
        new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream(file)) {
                full.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
                // The in-game album still holds it for this session.
            }
            // Keep the newest dozen on the tablet.
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null && files.length > 12) {
                java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                for (int i = 12; i < files.length; i++) {
                    files[i].delete();
                }
            }
        }, "skyline-photo").start();
    }

    /** Loads the saved album once, off the UI thread. */
    private void loadAlbum() {
        if (albumLoaded) {
            return;
        }
        albumLoaded = true;
        final File dir = photoDir();
        new Thread(() -> {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
            final java.util.ArrayList<Photo> loaded = new java.util.ArrayList<>();
            for (int i = 0; i < files.length && loaded.size() < thumbRects.length; i++) {
                if (!files[i].getName().endsWith(".png")) {
                    continue;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap b = BitmapFactory.decodeFile(files[i].getAbsolutePath(), o);
                if (b != null) {
                    Photo p = new Photo();
                    p.file = files[i];
                    p.thumb = b;
                    loaded.add(p);
                }
            }
            post(() -> {
                for (Photo p : loaded) {
                    boolean dup = false;
                    for (Photo q : album) {
                        if (q.file != null && q.file.equals(p.file)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup && album.size() < thumbRects.length) {
                        album.add(p);
                    }
                }
            });
        }, "skyline-album").start();
    }

    private void openPhoto(Photo p) {
        if (p.full != null) {
            viewing = p.full;
            return;
        }
        Bitmap b = p.file != null && p.file.exists() ? BitmapFactory.decodeFile(p.file.getAbsolutePath()) : null;
        viewing = b != null ? b : p.thumb;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                lastTouchX = x;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (photoMode && viewing == null) {
                    if (Math.abs(x - downX) > dp(10f)) {
                        dragging = true;
                    }
                    if (dragging) {
                        angle += (x - lastTouchX) / dp(240f);
                    }
                }
                lastTouchX = x;
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    handleTap(x, y);
                    performClick();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void handleTap(float x, float y) {
        if (viewing != null) {
            viewing = null;
            return;
        }
        if (!photoMode) {
            if (photoBtn.contains(x, y)) {
                photoMode = true;
                loadAlbum();
            } else if (!rushBtn.isEmpty() && rushBtn.contains(x, y) && rushAvailable()) {
                startRush();
            }
            return;
        }
        float dx = x - snapX;
        float dy = y - snapY;
        if (dx * dx + dy * dy <= snapR * snapR * 1.7f) {
            snap();
            return;
        }
        if (doneBtn.contains(x, y)) {
            photoMode = false;
            return;
        }
        for (int i = 0; i < thumbRects.length && i < album.size(); i++) {
            if (thumbRects[i].contains(x, y)) {
                openPhoto(album.get(i));
                return;
            }
        }
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
