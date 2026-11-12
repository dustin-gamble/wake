package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Region;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Zombie Run: a horde chases you at a pace you choose. Hold above it or get eaten.
 *
 * <p>The horde creeps faster every minute and surges every so often, so you cannot settle. A
 * safe house every 500 m makes them fall back - a rest interval you have to earn by reaching it.
 * The gap is the game: it sits huge in the middle and every stroke moves it.
 *
 * <p>3.19.2, on the rower's direction ("funny speak boxes"): the horde talks. Every few seconds a
 * zombie says something that fits the moment - lurching at the start, closing in, surging, sulking at
 * a safe house, gloating when it has you - and when you are well clear, you answer back. Figures are
 * drawn twice the size they first shipped, which on the tablet were barely readable.
 *
 * <p>The five upgrades the rower approved:
 * <ul>
 * <li><b>Zombie types.</b> Runners break out of the pack in a dash that scales with how far ahead
 * you are, then tire and drop back. A brute sits in the pack, winds up with a roar you can see
 * coming, then charges. Either one reaching you is a grab, so the real distance is the gap to the
 * nearest zombie, not to the pack.</li>
 * <li><b>Safe houses</b> every 500 m now also take in the survivors you are escorting (the SAVED
 * score) and hand you a flare.</li>
 * <li><b>Flares</b>, earned three hard strokes at a time (per-stroke power from the pulse meter,
 * three quarters of the way up the rower's own profile from low to high watts). Tap FLARE, or one fires itself when you are
 * grabbed: the horde is pushed back, stunned, and the runners and brute flee.</li>
 * <li><b>Night mode</b> (the NIGHT button): black except your torch beam and the lights of safe
 * houses and survivors, with fog rolling through. The torch glances back at the horde when
 * something is closing, and in the dark you see them by their eyes.</li>
 * <li><b>Survivors</b> wait on wrecked cars along the route. Reach one with the nearest zombie at
 * least 12 m back and they join you; closer than that and they hide. They run behind you, so the
 * horde reaches them first - lose one and the horde stops for them, lose none and deliver them to
 * the next safe house.</li>
 * </ul>
 *
 * <p>3.23.0, the five the rower approved next - the run is now a campaign rather than an endless
 * treadmill:
 * <ul>
 * <li><b>Waves that escalate into a final chase.</b> A district is four 500 m waves, each ending at
 * a safe house; every wave the horde is faster and the brute, the runners and the surges come round
 * sooner. Clearing the fourth opens the <b>FINAL CHASE</b>: no more safe houses, the horde winding
 * up to a third faster again over 700 m, with a helicopter at the end of it. Reach the chopper and
 * the district is cleared and the next one starts, harder.</li>
 * <li><b>Supply drops that need a sprint.</b> A crate comes down on a parachute ahead of you with a
 * countdown on it: the distance is set so reaching it in time needs the pace 85% of the way up your
 * own profile, so it is a genuine sprint, not a jog. Flares, planks or a medkit inside. Miss it and
 * the horde takes it and surges.</li>
 * <li><b>Barricades.</b> Every stroke inside a safe house nails up a plank (a hard one puts up two,
 * and planks from a supply drop go up the moment you arrive). When the rest ends the wall stands
 * where you left it, and the horde has to smash through it - about 0.7 s a plank - while you row
 * away. That turns the rest into work with something at stake.</li>
 * <li><b>The limp.</b> Being caught no longer ends the run the first time: they take a bite, you are
 * shoved clear, and you run wounded - slower against the horde, with a visible limp and blood on
 * the road. Only <b>steady</b> rowing inside your own range closes it up; sprinting or stopping
 * stalls the recovery. Get caught again while still limping and it is over.</li>
 * <li><b>The district map</b>, kept between sessions as {@code zrun.district}: eight districts, the
 * cleared ones flagged, the one you are in lit up with your marker crawling along it. Shown before
 * the first stroke, whenever a district falls, and on the MAP button.</li>
 * </ul>
 */
final class ZombieRunGame extends GameView {

    private enum Phase { READY, RUNNING, CAUGHT }

    private static final float START_GAP = 40f;
    private static final float MAX_GAP = 140f;
    private static final int SAFE_EVERY = 500;
    private static final double SAFE_SECONDS = 15;

    /** Nearest zombie must be at least this far back for a survivor to dare run to you. */
    private static final double RESCUE_CLEAR = 12;
    /** Metres between survivors running behind you. */
    private static final double SURV_SPACING = 1.8;
    private static final int MAX_GROUP = 4;
    private static final int MAX_FLARES = 3;
    private static final int PIPS_PER_FLARE = 3;
    private static final double FLARE_PUSH = 16;
    private static final double FLARE_STUN = 3.0;
    private static final double FLARE_FLIGHT = 0.45;
    /** Pb key: most survivors delivered to safe houses in one run. Not "zombie." - that prefix is a pace. */
    static final String SAVED_KEY = "zrun.saved";
    /** Pb key: districts cleared for good, counted across every session. The map is drawn from it. */
    static final String DISTRICT_KEY = "zrun.district";
    /** Pb key: most waves survived in one run. */
    static final String WAVE_KEY = "zrun.wave";

    /* Waves, the final chase and the districts they belong to. */
    private static final int WAVES_PER_DISTRICT = 4;
    private static final double FINAL_METRES = 700;
    private static final String[] DISTRICTS = {"RIVERSIDE", "OLD TOWN", "THE DOCKS", "MILL ROW",
            "NORTHGATE", "THE STACKS", "GREENWAY", "HARBOUR END"};

    /* Supply drops. */
    /** Seconds you get to cover the drop's distance; the distance is set from your own sprint pace. */
    private static final double DROP_WINDOW = 14.0;
    private static final int LOOT_FLARES = 0;
    private static final int LOOT_PLANKS = 1;
    private static final int LOOT_MEDKIT = 2;

    /* Barricades. */
    private static final int MAX_PLANKS = 14;
    private static final double SEC_PER_PLANK = 0.7;

    /* The limp. */
    private static final double LIMP_RECOVER = 22.0;
    /**
     * Ground a full wound costs you, as a fraction of the horde's base speed.
     *
     * <p>Deliberately not a fraction of <i>your</i> speed: at the gentlest horde pace (2:25 /500 =
     * 3.45 m/s) this rower's typical 3.85 m/s leaves a margin of 0.4 m/s, and taking 30% off your own
     * speed would remove it several times over - a bite would have been an unescapable death spiral
     * rather than a handicap. Taken off the horde's pace instead it scales with the difficulty chosen
     * and a harder pull always covers it; the cost is that a harder pull is not steady, so it does
     * not heal.
     */
    private static final double LIMP_COST = 0.08;
    /** Caught again with the leg this bad and the run is over. */
    private static final double LIMP_FATAL = 0.30;

    /* Figure kinds for drawFigure. */
    private static final int K_YOU = 0;
    private static final int K_SURVIVOR = 1;
    private static final int K_WALKER = 2;
    private static final int K_RUNNER = 3;
    private static final int K_BRUTE = 4;

    /* Who is speaking: -1 you, 0-5 a walker, and these. */
    private static final int WHO_YOU = -1;
    private static final int WHO_BRUTE = 10;
    private static final int WHO_RUNNER = 20;
    private static final int WHO_GROUP = 30;
    private static final int WHO_WAITING = 40;

    private static final int WALKERS = 6;
    private static final int RUNNER_SLOTS = 2;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path darkPath = new Path();

    private float hordePaceSec = 150f;    // 2:30 /500
    private Phase phase = Phase.READY;
    private double gap;
    /** Seconds spent in the horde's grip; it ends the run only once this runs out. */
    private double grabbedSeconds;
    private static final double GRAB_LIMIT = 6.0;
    private double runStartMeters;
    private double runStartSeconds;
    private double nextSurgeAt;
    private double surgeUntil;
    private double safeUntil;
    private double nextSafeHouse = SAFE_EVERY;
    private double creep;

    /* Waves and districts. */
    private int wave = 1;
    private int wavesCleared;
    private double districtStart;
    private boolean finalChase;
    private double evacAt;
    private int districtsCleared;
    /** World metres of the haven you are resting at, and whether it is the evac rather than a house. */
    private double havenWorld = -1;
    private boolean havenIsEvac;
    private boolean prevSafe;
    private double clearedAt = -10;
    private float confettiAccum;

    /* Supply drops. */
    private boolean dropActive;
    private double dropAt;
    private double dropDeadline;
    private double dropSpawnedAt;
    private double nextDropAt;
    private int dropLoot;
    private int plankStock;
    private double dropGoneAt = -10;
    private float dropSmokeAccum;

    /* Barricade: planks nailed up in the safe house, then a wall the horde has to smash. */
    private int barricadePlanks;
    private double barricadeWorld = -1;
    private double barricadeHp;
    private double barricadeHitAt = -10;
    private double hammerAt = -10;
    private boolean wallHolding;

    /* The limp. */
    private double limp;
    private int steadyState;   // 0 not rowing / too slow, 1 steady, 2 thrashing
    private double woundedAt = -10;
    private float bloodAccum;

    /* District map overlay. */
    private boolean mapShown;
    private double mapAutoHideAt;
    private float mapL, mapT, mapR, mapB;
    private final Fx.Shake shake = new Fx.Shake();
    private final Fx.Particles dust = new Fx.Particles();
    private final Fx.Particles sparks = new Fx.Particles();
    private float dustAccum;
    private boolean caughtFx;

    /* Brute: 0 in the pack, 1 winding up, 2 charging, 3 lumbering back. */
    private int bruteState;
    private double bruteLead;
    private double bruteUntil;
    private double bruteBoost;
    private double nextBruteAt;

    /* Runners: 0 off, 1 dashing, 2 tired and dropping back, 3 fleeing a flare. */
    private final int[] runState = new int[RUNNER_SLOTS];
    private final double[] runLead = new double[RUNNER_SLOTS];
    private final double[] runUntil = new double[RUNNER_SLOTS];
    private final double[] runBoost = new double[RUNNER_SLOTS];
    private double nextRunnerAt;

    /* Flares. */
    private int flares;
    private int chargePips;
    private double flareFiredAt = -10;
    private float flareFromX;
    private float flareToX;
    /** Set on firing, consumed by the next frame: a tap fires between frames, so aim by flag, not by time. */
    private boolean flareAimPending;
    private double pushLeft;
    private double stunUntil;
    private double hardStrokeAt = -10;
    private int lastMeterStroke = -1;
    private boolean meterPowered;
    private int peakWatts;
    /** The counter fallback already judged a stroke in this status; the meter's first stroke is that one. */
    private boolean judgedByCounter;

    /* Night. */
    private boolean night;
    private float lookBack;

    /* Survivors: one waiting ahead, up to four running with you. */
    private static final String[] NAMES = {"MAYA", "DEV", "ROSA", "OTTO", "JUNE", "SAM", "KOFI", "LENA", "IVY", "BEN"};
    private static final int[] SHIRTS = {0xFF6F8CFF, 0xFFE0A33A, 0xFFB86FE0, 0xFF4FB86A, 0xFFE06F9A, 0xFF4FC3E0};
    private double survivorAt;
    private int survivorName;
    private int survivorShirt;
    private int nameCursor;
    private int groupCount;
    private final int[] groupName = new int[MAX_GROUP];
    private final int[] groupShirt = new int[MAX_GROUP];
    private int savedThisRun;

    /* Zombie eyes recorded while drawing, redrawn glowing over the dark in night mode. */
    private final float[] eyeX = new float[24];
    private final float[] eyeY = new float[24];
    private int eyeCount;

    /* Screen x of the crate and the wall, or -1: night mode has to cut light holes for them. */
    private float nightDropX = -1;
    private float nightDropY;
    private float nightWallX = -1;

    /* On-canvas buttons (no header chips can be added from here). */
    private float nightL, nightT, nightR, nightB;
    private float flareL, flareT, flareR, flareB;

    /* Speech bubbles. */
    // 3.19.6: 2.0 left the runner and horde as specks on the tablet's 1920-wide screen.
    private static final float FIGURE_SCALE = 3.2f;
    private static final String[] LINES_START = {"Is he... rowing?", "Lunch is getting away!", "Walk faster, Gary!", "Fresh legs!"};
    private static final String[] LINES_CHASE = {"BRAAAINS", "Mmm, cardio-flavoured", "We never skip leg day", "Wait up!", "Is that a rowing machine?"};
    private static final String[] LINES_CLOSE = {"Just a little nibble!", "Almost... there...", "I can smell the sweat!", "So close!", "Nom nom nom?"};
    private static final String[] LINES_SURGE = {"CARDIO DAY!", "CHAAARGE!", "Sprint intervals!", "Faster, team!"};
    private static final String[] LINES_SAFE = {"Aww, not the safe house", "We'll wait. We have time.", "No fair!", "Snack break over there?"};
    private static final String[] LINES_GRABBED = {"Hold still, snack!", "Gotcha!", "Dinner is served!", "Don't wriggle!"};
    private static final String[] LINES_FAR = {"Slow down!", "No fair, you have a boat!", "My legs fell off", "Are we there yet?", "I need a nap"};
    private static final String[] LINES_YOU = {"Not today!", "Catch me if you can!", "Row, row, row your... bye!", "Is that all you've got?"};
    private static final String[] LINES_BRUTE = {"HNNNNGH!", "BRUTE... SMASH... BOAT!", "Me go FAST now", "Out of my way, Gary!"};
    private static final String[] LINES_RUNNER = {"I used to do marathons!", "Zoom zoom!", "Can't stop, won't stop!", "Personal best incoming!"};
    private static final String[] LINES_TIRED = {"Stitch... stitch...", "Need... a... sit down", "Carry me, Gary"};
    private static final String[] LINES_FLARE = {"MY EYES!", "Too bright! Too bright!", "Who turned on the sun?", "Aaah, fireworks!"};
    private static final String[] LINES_HELP = {"HELP! Over here!", "Wait for me!", "Don't leave me!"};
    private static final String[] LINES_RESCUED = {"You came back for me!", "Thank you! Go go go!", "I can row too, you know", "Don't let them get us!"};
    private static final String[] LINES_GROUP = {"Faster!!", "They're right behind us!", "Keep pulling!", "Is it much further?"};
    private static final String[] LINES_LOST = {"Got one!", "Yoink!", "One for the road!"};
    private static final String[] LINES_HOME = {"We made it!", "Home sweet safe house", "Bolt the door!"};
    private static final String[] LINES_WALL = {"Open UP!", "Who built this?!", "It's load-bearing!", "Gary, fetch the axe"};
    private static final String[] LINES_WALL_DOWN = {"TIMBERRR!", "Door's open!", "We're in!"};
    private static final String[] LINES_DROP = {"Supplies! GO!", "That crate is ours!", "Sprint for it!"};
    private static final String[] LINES_WAVE = {"MORE OF US NOW", "Wave two, baby!", "We brought friends"};
    private static final String[] LINES_FINAL = {"NO MORE HIDING", "LAST ONE TO THE CHOPPER", "ALL OF US. NOW."};
    private static final String[] LINES_WOUND = {"Tastes like cardio", "Got a bite!", "He's limping now..."};
    private final java.util.Random chatter = new java.util.Random();
    private String bubble = "";
    private int bubbleWho;
    private double bubbleUntil;
    private double nextBubbleAt;
    private boolean wasSurging;
    private boolean wasSafe;
    private boolean wasGrabbed;
    private float spkX;
    private float spkY;

    /* Scenery that moves on its own, so the night is never still. */
    private final float[] batX = new float[5];
    private final float[] batY = new float[5];
    private final float[] batSpeed = new float[5];
    private double lightningAt = -10;
    private double nextLightning;
    private int bestMilestone;
    private String popup = "";
    private int popupColour = ACCENT;
    private double popupUntil;
    private final Fx.Particles smoke = new Fx.Particles();
    private float smokeAccum;

    /* Sky gradient, rebuilt only when its colour step or the height changes. */
    private LinearGradient skyShader;
    private int skyShaderTop;
    private float skyShaderH;

    /*
     * 3.25.0 graphics pass. Everything below is built once per view size in ensureScene() and only
     * read in the frame loop: shapes are cached Paths drawn through a canvas transform, gradients are
     * cached shaders, and nothing in render() allocates.
     */
    /** Limbs, rails and grass: round-capped strokes. Kept off {@code paint} so its style never leaks. */
    private final Paint limb = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Shader fills (fog, moon, heads, ground). Colour is set to opaque white before every use. */
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Additive light: the torch beam brightens whatever it falls on rather than painting over it. */
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float sceneW = -1;
    private float sceneH = -1;
    private static final int STARS = 90;
    private final float[] starX = new float[STARS];
    private final float[] starY = new float[STARS];
    private final float[] starR = new float[STARS];
    private final int[] starA = new int[STARS];
    private int starCount;
    private final Path[] treeShape = new Path[3];
    private final Path[] stoneShape = new Path[4];
    private final Path[] torsoShape = new Path[5];
    private final RadialGradient[] headShader = new RadialGradient[5];
    private final Path beamCone = new Path();
    private final Path beamCore = new Path();
    private final Path batPath = new Path();
    private RadialGradient fogShader;
    private RadialGradient moonShader;
    private RadialGradient poolShader;
    private LinearGradient groundShader;
    private LinearGradient pathShader;
    private LinearGradient bottomShader;
    private LinearGradient beamShader;
    private LinearGradient beamCoreShader;
    private LinearGradient glossShader;
    private float fogR;
    private float poolR;
    private float beamLen;
    private static final double BEAM_SPREAD = 0.26;
    private static final float GLOSS_UNIT = 100f;
    private final float[] fenceLines = new float[4 * 220];
    /* Distance each part of the horde has walked, so their legs move with their own pace, not yours. */
    private float walkStride;
    private float bruteStride;
    private final float[] runStride = new float[RUNNER_SLOTS];
    /* Where your headlamp is this frame: the beam and the night's light hole start from it. */
    private float lampX = -1;
    private float lampY;
    /* Props are drawn at the figures' scale (they were half their size beside the 3.2x figures). */
    private static final float HOUSE_K = 2.1f;
    private static final float WALL_K = 1.6f;
    private static final float CAR_K = 2.0f;
    private static final float CHOPPER_K = 1.5f;
    private static final float CRATE_K = 1.35f;

    ZombieRunGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        limb.setStyle(Paint.Style.STROKE);
        limb.setStrokeCap(Paint.Cap.ROUND);
        limb.setStrokeJoin(Paint.Join.ROUND);
        light.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    void setHordePace(float secondsPer500) {
        hordePaceSec = secondsPer500;
        phase = Phase.READY;
    }

    float hordePace() {
        return hordePaceSec;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        caughtFx = false;
        gap = START_GAP;
        grabbedSeconds = 0;
        nextSafeHouse = SAFE_EVERY;
        creep = 0;
        // The map is the one thing here that outlives a session.
        districtsCleared = Math.max(0, Math.round(bests.get(DISTRICT_KEY, 0)));
        wave = 1;
        wavesCleared = 0;
        districtStart = 0;
        finalChase = false;
        evacAt = 0;
        havenWorld = -1;
        havenIsEvac = false;
        prevSafe = false;
        clearedAt = -10;
        dropActive = false;
        nextDropAt = 35;
        dropLoot = LOOT_FLARES;
        plankStock = 0;
        dropGoneAt = -10;
        barricadePlanks = 0;
        barricadeWorld = -1;
        barricadeHp = 0;
        barricadeHitAt = -10;
        hammerAt = -10;
        wallHolding = false;
        limp = 0;
        steadyState = 0;
        woundedAt = -10;
        bloodAccum = 0;
        mapShown = true;
        mapAutoHideAt = 0;
        surgeUntil = 0;
        safeUntil = 0;
        bubbleUntil = 0;
        nextBubbleAt = 1.5;
        wasSurging = false;
        wasSafe = false;
        wasGrabbed = false;
        bestMilestone = 0;
        bruteState = 0;
        bruteLead = 0;
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            runState[i] = 0;
            runLead[i] = 0;
        }
        flares = 0;
        chargePips = 0;
        flareFiredAt = -10;
        flareAimPending = false;
        judgedByCounter = false;
        lostFxPending = false;
        // sessionSeconds restarts at 0: stale times from the last run would hold off the lightning.
        lightningAt = -10;
        nextLightning = 0;
        pushLeft = 0;
        stunUntil = 0;
        hardStrokeAt = -10;
        lastMeterStroke = -1;
        meterPowered = false;
        peakWatts = 0;
        lookBack = 0;
        groupCount = 0;
        savedThisRun = 0;
        survivorAt = 180;
        nameCursor = chatter.nextInt(NAMES.length);
        pickSurvivor();
        popupUntil = 0;
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < batX.length; i++) {
            batX[i] = r.nextFloat();
            batY[i] = 0.12f + r.nextFloat() * 0.3f;
            batSpeed[i] = 0.03f + r.nextFloat() * 0.05f;
        }
    }

    private void pickSurvivor() {
        survivorName = nameCursor;
        nameCursor = (nameCursor + 1) % NAMES.length;
        survivorShirt = SHIRTS[chatter.nextInt(SHIRTS.length)];
    }

    /** Sets the next survivor ahead, clear of the next safe house so the two never stack. */
    private void placeNextSurvivor() {
        double at = survivorAt + 200 + chatter.nextDouble() * 120;
        double house = nextSafeHouse;
        while (at > house) {
            house += SAFE_EVERY;
        }
        if (house - at < 70) {
            at = house + 90;
        } else if (at - (house - SAFE_EVERY) < 70) {
            at = house - SAFE_EVERY + 90;
        }
        survivorAt = at;
        pickSurvivor();
    }

    private void say(String[] lines, int who) {
        bubble = lines[chatter.nextInt(lines.length)];
        bubbleWho = who;
        bubbleUntil = sessionSeconds + 2.8;
        nextBubbleAt = sessionSeconds + 4.0 + chatter.nextDouble() * 3.0;
    }

    private void pop(String text, int colour) {
        popup = text;
        popupColour = colour;
        popupUntil = sessionSeconds + 2.0;
    }

    /** Picks a line for the moment and who says it. Surges, safe houses and grabs speak at once. */
    private void chatter(double threat) {
        boolean surge = surging() && phase == Phase.RUNNING;
        boolean inSafe = safe();
        boolean grabbed = grabbedSeconds > 0;
        boolean event = (surge && !wasSurging) || (inSafe && !wasSafe) || (grabbed && !wasGrabbed);
        wasSurging = surge;
        wasSafe = inSafe;
        wasGrabbed = grabbed;
        if (!event && sessionSeconds < nextBubbleAt) {
            return;
        }
        String[] lines;
        int who = chatter.nextInt(WALKERS);
        if (phase == Phase.CAUGHT) {
            return;
        } else if (grabbed) {
            lines = LINES_GRABBED;
        } else if (surge) {
            lines = LINES_SURGE;
        } else if (inSafe) {
            lines = LINES_SAFE;
        } else if (phase == Phase.READY) {
            lines = LINES_START;
        } else if (threat < 14) {
            lines = LINES_CLOSE;
        } else if (groupCount > 0 && chatter.nextInt(3) == 0) {
            lines = LINES_GROUP;
            who = WHO_GROUP + chatter.nextInt(groupCount);
        } else if (threat > 60 && chatter.nextInt(3) == 0) {
            lines = LINES_YOU;
            who = WHO_YOU;
        } else if (threat > 60) {
            lines = LINES_FAR;
        } else {
            lines = LINES_CHASE;
        }
        say(lines, who);
    }

    /** A comic speech bubble with a tail, pointing down at the speaker's head. */
    private void drawBubble(Canvas c, float headX, float headY, float w) {
        double left = bubbleUntil - sessionSeconds;
        if (left <= 0 || bubble.isEmpty()) {
            return;
        }
        float alpha = (float) Math.min(1.0, Math.min(left / 0.3, (2.8 - left) / 0.2 + 0.1));
        // Measured at the size bold() draws it: at the raw 15dp the text ran out of its own bubble.
        textPaint.setTextSize(dp(textDp(15f)));
        float tw = textPaint.measureText(bubble);
        float padX = dp(14f);
        float bh = dp(38f);
        float bx = Math.max(dp(10f), Math.min(w - tw - padX * 2 - dp(10f), headX - tw / 2f - padX));
        float by = Math.max(dp(4f), headY - dp(22f) - bh);
        float br = bx + tw + padX * 2;
        float tailX = Math.max(bx + dp(16f), Math.min(br - dp(16f), headX));
        path.rewind();
        path.addRoundRect(bx, by, br, by + bh, dp(16f), dp(16f), Path.Direction.CW);
        path.moveTo(tailX - dp(9f), by + bh - dp(2f));
        path.lineTo(headX, headY - dp(6f));
        path.lineTo(tailX + dp(9f), by + bh - dp(2f));
        path.close();
        c.save();
        c.translate(dp(3f), dp(4f));
        paint.setColor(0x000000 | ((int) (90 * alpha) << 24));
        c.drawPath(path, paint);
        c.restore();
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (240 * alpha));
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(bubbleWho == WHO_YOU ? 0xFF35D0BA : bubbleWho >= WHO_GROUP ? 0xFF8FB4FF : 0xFFB0453A);
        paint.setAlpha((int) (220 * alpha));
        c.drawRoundRect(bx, by, br, by + bh, dp(16f), dp(16f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        int textColour = bubbleWho == WHO_YOU ? 0xFF0E6E60
                : bubbleWho >= WHO_GROUP ? 0xFF1E3A8A : 0xFF3A1010;
        bold(c, bubble, bx + padX + tw / 2f, by + bh * 0.68f, 15f,
                (((int) (255 * alpha)) << 24) | (textColour & 0x00FFFFFF), Paint.Align.CENTER);
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.RUNNING;
            mapShown = false;
            runStartMeters = sessionMeters;
            runStartSeconds = sessionSeconds;
            nextSurgeAt = sessionSeconds + 40;
            nextBruteAt = sessionSeconds + 30;
            nextRunnerAt = sessionSeconds + 50;
        }
        // Peak power is read here, never in onStroke (which fires ~1 s late, after power collapses).
        peakWatts = Math.max(peakWatts, s.watts);
        PulseMeter.Stroke st = s.meter.lastStroke;
        if (st != null && st.index != lastMeterStroke) {
            boolean first = lastMeterStroke < 0;
            lastMeterStroke = st.index;
            if (!first && !Double.isNaN(st.averagePowerW)) {
                // The pulse meter judges each stroke on its own, the moment it ends.
                boolean handover = !meterPowered && judgedByCounter;
                meterPowered = true;
                if (!handover) {
                    judgeStroke(st.averagePowerW);
                }
            }
        }
        judgedByCounter = false;
    }

    @Override
    protected void onStroke(int watts) {
        // Fallback before the meter has measured a coast: the peak reading since the last stroke.
        if (!meterPowered) {
            judgeStroke(peakWatts);
            judgedByCounter = true;
        }
        peakWatts = 0;
    }

    /** A hard stroke: three quarters of the way from the rower's typical watts toward their high (p90) watts. */
    private double hardWatts() {
        return profile.wattsAt(0.75);
    }

    private void judgeStroke(double watts) {
        if (phase != Phase.RUNNING) {
            return;
        }
        boolean hard = watts >= hardWatts();
        // Inside a safe house every stroke is a plank on the door; a hard one is two.
        if (safe() && barricadePlanks < MAX_PLANKS) {
            barricadePlanks = Math.min(MAX_PLANKS, barricadePlanks + (hard ? 2 : 1));
            hammerAt = sessionSeconds;
            if (barricadePlanks >= MAX_PLANKS) {
                pop("WALL FULL - " + MAX_PLANKS + " PLANKS", ACCENT);
            }
        }
        if (!hard) {
            return;
        }
        hardStrokeAt = sessionSeconds;
        if (flares >= MAX_FLARES) {
            chargePips = PIPS_PER_FLARE;
            return;
        }
        chargePips++;
        if (chargePips >= PIPS_PER_FLARE) {
            chargePips = 0;
            flares++;
            pop("FLARE READY", 0xFFFF7A4D);
        }
    }

    private void fireFlare() {
        if (flares <= 0 || phase != Phase.RUNNING) {
            return;
        }
        flares--;
        if (chargePips >= PIPS_PER_FLARE) {
            // Hard strokes banked while the pouch was full become the replacement flare.
            chargePips = 0;
            flares++;
        }
        flareFiredAt = sessionSeconds;
        flareAimPending = true;
        pushLeft += FLARE_PUSH;
        stunUntil = sessionSeconds + FLARE_STUN;
        grabbedSeconds = 0;
        scatterSpecials();
        shake.kick(dp(8f));
        say(LINES_FLARE, chatter.nextInt(WALKERS));
    }

    /** Runners flee back into the pack and the brute abandons its charge. */
    private void scatterSpecials() {
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] != 0) {
                runState[i] = 3;
            }
        }
        if (bruteState == 1 || bruteState == 2) {
            bruteState = 3;
            nextBruteAt = sessionSeconds + (35 + chatter.nextDouble() * 20) * spawnScale();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float x = e.getX();
            float y = e.getY();
            if (x >= mapL && x <= mapR && y >= mapT && y <= mapB) {
                mapShown = !mapShown;
                mapAutoHideAt = 0;
                return true;
            }
            if (mapShown) {
                // The map is a full panel: anywhere else puts it away rather than firing a flare.
                mapShown = false;
                mapAutoHideAt = 0;
                return true;
            }
            if (x >= nightL && x <= nightR && y >= nightT && y <= nightB) {
                night = !night;
                return true;
            }
            if (phase == Phase.RUNNING && x >= flareL && x <= flareR && y >= flareT && y <= flareB) {
                if (flares > 0) {
                    fireFlare();
                } else {
                    pop("NO FLARES - PULL HARD", WARN);
                }
                return true;
            }
            if (phase == Phase.CAUGHT) {
                start();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private double runMeters() {
        return phase == Phase.READY ? 0 : sessionMeters - runStartMeters;
    }

    private boolean surging() {
        return sessionSeconds < surgeUntil;
    }

    private boolean safe() {
        return sessionSeconds < safeUntil;
    }

    private boolean stunned() {
        return sessionSeconds < stunUntil;
    }

    /**
     * Horde speed: base pace, +0.5% per minute of creep, the wave and district escalation, the final
     * chase's ramp, +30% in a surge, stunned by a flare, stopped at a safe house.
     */
    private float hordeSpeed() {
        if (safe()) {
            return 0f;
        }
        float base = 500f / hordePaceSec;
        return base * (float) (1 + creep) * (float) waveMul()
                * (surging() ? 1.3f : 1f) * (stunned() ? 0.4f : 1f);
    }

    /** What the wave, the districts already cleared and the final chase add to the horde's speed. */
    private double waveMul() {
        double m = 1 + 0.05 * (wave - 1) + Math.min(0.24, 0.08 * districtsCleared);
        if (finalChase) {
            m *= 1 + 0.20 * finalProgress();
        }
        return m;
    }

    /** 0 at the start of the final chase, 1 at the chopper. */
    private double finalProgress() {
        if (!finalChase) {
            return 0;
        }
        double from = districtStart + WAVES_PER_DISTRICT * SAFE_EVERY;
        return Math.max(0, Math.min(1, (runMeters() - from) / FINAL_METRES));
    }

    /** How much sooner the brute, the runners and the surges come round as the waves stack up. */
    private double spawnScale() {
        double s = 1 - 0.12 * (wave - 1) - Math.min(0.18, 0.06 * districtsCleared);
        if (finalChase) {
            s *= 0.55;
        }
        return Math.max(0.35, s);
    }

    /** Your speed as the chase sees it: the wound costs you ground against the horde's own pace. */
    private float chaseSpeed(float speed) {
        return Math.max(0f, speed - (float) (LIMP_COST * limp * (500.0 / hordePaceSec)));
    }

    /** True while nothing can break out of the pack: resting in a safe house, or stuck at a wall. */
    private boolean held() {
        return safe() || wallHolding;
    }

    private String districtName() {
        return districtName(0);
    }

    /** How far the brute or a runner has broken out ahead of the pack, metres. */
    private double maxLead() {
        double lead = bruteLead;
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] != 0) {
                lead = Math.max(lead, runLead[i]);
            }
        }
        return lead;
    }

    /** Distance from you to the nearest zombie. */
    private double threat() {
        return gap - maxLead();
    }

    private boolean runnerDashing() {
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] == 1) {
                return true;
            }
        }
        return false;
    }

    private void stepSpecials(float dt, float hs) {
        double t = sessionSeconds;
        // Brute.
        if (bruteState == 0 && !held() && t >= nextBruteAt) {
            bruteState = 1;
            bruteUntil = t + 1.4 + 1.1 * spawnScale();
            say(LINES_BRUTE, WHO_BRUTE);
        } else if (bruteState == 1) {
            shake.kick(dp(2f));
            if (held()) {
                bruteState = 3;
            } else if (t >= bruteUntil) {
                bruteState = 2;
                bruteUntil = t + 4;
                // A charge that closes further the further ahead you are, so a banked lead still has to be defended.
                bruteBoost = (2.4 + gap * 0.02) * waveMul();
            }
        } else if (bruteState == 2) {
            bruteLead += bruteBoost * dt;
            if (t >= bruteUntil || held()) {
                bruteState = 3;
            }
        } else if (bruteState == 3) {
            bruteLead -= 1.5 * dt;
            if (bruteLead <= 0) {
                bruteLead = 0;
                bruteState = 0;
                if (nextBruteAt <= t) {
                    nextBruteAt = t + (35 + chatter.nextDouble() * 20) * spawnScale();
                }
            }
        }
        // Runners.
        if (!held() && t >= nextRunnerAt) {
            for (int i = 0; i < RUNNER_SLOTS; i++) {
                if (runState[i] == 0) {
                    runState[i] = 1;
                    runLead[i] = 0;
                    runUntil[i] = t + 9;
                    runBoost[i] = (1.6 + gap * 0.03) * waveMul();
                    say(LINES_RUNNER, WHO_RUNNER + i);
                    break;
                }
            }
            nextRunnerAt = t + (30 + chatter.nextDouble() * 15) * spawnScale();
        }
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] == 1) {
                runLead[i] += runBoost[i] * dt;
                if (t >= runUntil[i] || held()) {
                    runState[i] = 2;
                    if (bubbleUntil < t) {
                        say(LINES_TIRED, WHO_RUNNER + i);
                    }
                }
            } else if (runState[i] == 2 || runState[i] == 3) {
                double back = runState[i] == 3 ? 6.0 : Math.max(0.8, 0.25 * hs);
                runLead[i] -= back * dt;
                if (runLead[i] <= 0) {
                    runLead[i] = 0;
                    runState[i] = 0;
                }
            }
        }
        // Nobody passes you: a zombie that reaches you stops at you.
        double cap = Math.max(0, gap);
        bruteLead = Math.min(bruteLead, cap);
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            runLead[i] = Math.min(runLead[i], cap);
        }
    }

    /** Out-rowed: whoever broke out and caught you is knocked back and gives up the chase. */
    private void shoveSpecialsBack(double maxLead) {
        double limit = Math.max(0, maxLead);
        if (bruteLead > limit) {
            bruteLead = limit;
            if (bruteState == 1 || bruteState == 2) {
                bruteState = 3;
                nextBruteAt = sessionSeconds + (35 + chatter.nextDouble() * 20) * spawnScale();
            }
        }
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] != 0 && runLead[i] > limit) {
                runLead[i] = limit;
                if (runState[i] == 1) {
                    runState[i] = 2;
                }
            }
        }
    }

    private void loseSurvivor() {
        groupCount--;
        int name = groupName[groupCount];
        pop(NAMES[name] + " WAS GRABBED", BAD);
        gap += 8;   // more than a full group's spacing, so one grab never cascades into the next
        stunUntil = Math.max(stunUntil, sessionSeconds + 2.5);
        scatterSpecials();
        shake.kick(dp(9f));
        say(LINES_LOST, chatter.nextInt(WALKERS));
        lostFxPending = true;
    }

    private boolean lostFxPending;
    private boolean woundFxPending;

    private void reachSafeHouse() {
        safeUntil = sessionSeconds + SAFE_SECONDS;
        havenWorld = nextSafeHouse;
        havenIsEvac = false;
        nextSafeHouse += SAFE_EVERY;
        boolean restock = flares < MAX_FLARES;
        if (restock) {
            flares++;
        }
        // Planks carried from a supply drop go up the moment you get through the door.
        barricadePlanks = Math.min(MAX_PLANKS, plankStock);
        plankStock = 0;
        wavesCleared++;
        bests.recordHighest(WAVE_KEY, wavesCleared);
        deliverSurvivors();
        wave++;
        if (wave > WAVES_PER_DISTRICT) {
            // The last house of the district: the rest you get is the calm before the final chase.
            finalChase = true;
            evacAt = districtStart + WAVES_PER_DISTRICT * SAFE_EVERY + FINAL_METRES;
            // Every flare they can carry: modelled out, a typical row reaches the chopper only by
            // spending them, so sending you into the last 700 m empty-handed would settle it early.
            flares = MAX_FLARES;
            chargePips = 0;
            pop("WAVE " + WAVES_PER_DISTRICT + " CLEARED  ·  FINAL CHASE  ·  FLARES FULL", 0xFFFF7A4D);
            say(LINES_FINAL, WHO_BRUTE);
        } else {
            pop("WAVE " + (wave - 1) + " CLEARED  ·  BUILD THE WALL", ACCENT);
            say(LINES_WAVE, chatter.nextInt(WALKERS));
        }
    }

    /** Hands the group over at a haven and banks the record. */
    private void deliverSurvivors() {
        if (groupCount <= 0) {
            return;
        }
        savedThisRun += groupCount;
        bests.recordHighest(SAVED_KEY, savedThisRun);
        pop("+" + groupCount + " SAVED", 0xFF8FB4FF);
        say(LINES_HOME, WHO_GROUP);
        groupCount = 0;
    }

    /** The chopper at the end of the final chase: the district falls and the next one starts. */
    private void clearDistrict() {
        deliverSurvivors();
        districtsCleared++;
        bests.recordHighest(DISTRICT_KEY, districtsCleared);
        clearedAt = sessionSeconds;
        pop(districtName(-1) + " CLEARED", ACCENT);
        flares = MAX_FLARES;
        limp = 0;                       // patched up on board before they drop you at the next one
        gap = Math.min(MAX_GAP, gap + 50);
        safeUntil = sessionSeconds + SAFE_SECONDS;
        havenWorld = evacAt;
        havenIsEvac = true;
        barricadeWorld = -1;
        barricadeHp = 0;
        barricadePlanks = Math.min(MAX_PLANKS, plankStock);
        plankStock = 0;
        finalChase = false;
        wave = 1;
        districtStart = runMeters();
        nextSafeHouse = districtStart + SAFE_EVERY;
        evacAt = 0;
        scatterSpecials();
        stunUntil = Math.max(stunUntil, sessionSeconds + 3);
        shake.kick(dp(12f));
        mapShown = true;
        // Short: the rest is also when the next district's wall goes up, and the map covers it.
        mapAutoHideAt = sessionSeconds + 4.0;
    }

    /** Offset, because a clear has already advanced the count by the time the banner is written. */
    private String districtName(int offset) {
        int i = districtsCleared + offset;
        return DISTRICTS[((i % DISTRICTS.length) + DISTRICTS.length) % DISTRICTS.length];
    }

    /* ---------- supply drops ---------- */

    /**
     * Puts a crate down far enough ahead that reaching it inside {@link #DROP_WINDOW} needs the pace
     * 85% of the way up the rower's own profile - a sprint for this rower, whoever they are.
     */
    private void spawnDrop() {
        double need = profile.speedAt(0.85);
        dropAt = runMeters() + need * DROP_WINDOW;
        dropDeadline = sessionSeconds + DROP_WINDOW;
        dropSpawnedAt = sessionSeconds;
        dropActive = true;
        dropLoot = (dropLoot + 1) % 3;
        if (limp > 0.2) {
            dropLoot = LOOT_MEDKIT;     // a wounded leg makes the medkit the one worth sprinting for
        }
        pop("SUPPLY DROP  ·  SPRINT " + Math.round(need * DROP_WINDOW) + " m", WARN);
        say(LINES_DROP, WHO_GROUP);
    }

    /** Schedules a crate, and settles the one in the air: reached in time, or gone. */
    private void stepDrop() {
        if (!dropActive) {
            if (!safe() && !wallHolding && sessionSeconds >= nextDropAt) {
                spawnDrop();
            }
            return;
        }
        if (runMeters() >= dropAt) {
            collectDrop();
        } else if (sessionSeconds > dropDeadline) {
            missDrop();
        }
    }

    private void collectDrop() {
        dropActive = false;
        nextDropAt = sessionSeconds + 50 + chatter.nextDouble() * 25;
        switch (dropLoot) {
            case LOOT_PLANKS:
                plankStock += 6;
                pop("+6 PLANKS FOR THE WALL", 0xFFD6A46A);
                break;
            case LOOT_MEDKIT:
                if (limp > 0) {
                    limp = 0;
                    pop("MEDKIT  ·  LEG PATCHED UP", ACCENT);
                } else {
                    flares = Math.min(MAX_FLARES, flares + 1);
                    plankStock += 3;
                    pop("MEDKIT  ·  +1 FLARE, +3 PLANKS", ACCENT);
                }
                break;
            default:
                flares = Math.min(MAX_FLARES, flares + 2);
                pop("+2 FLARES", 0xFFFF7A4D);
                break;
        }
        shake.kick(dp(5f));
    }

    private void missDrop() {
        dropActive = false;
        dropGoneAt = sessionSeconds;
        nextDropAt = sessionSeconds + 45 + chatter.nextDouble() * 20;
        // They get it instead, and it puts a spring in their step.
        surgeUntil = Math.max(surgeUntil, sessionSeconds + 6);
        pop("DROP LOST - THEY HAVE IT", BAD);
    }

    /* ---------- the limp ---------- */

    /** Caught, but not finished: a bite, a shove clear, and a leg that no longer works properly. */
    private void wound() {
        limp = 1;
        woundedAt = sessionSeconds;
        grabbedSeconds = 0;
        gap = Math.max(gap, 20);
        pushLeft += 8;
        stunUntil = Math.max(stunUntil, sessionSeconds + 2.0);
        scatterSpecials();
        // Whoever had hold of you is back in the pack: without this the runner that bit you is
        // still 20 m out in front, and the next grab - fatal now - lands on the very next frame.
        shoveSpecialsBack(0);
        shake.kick(dp(16f));
        pop("BITTEN - ROW STEADY TO RECOVER", BAD);
        say(LINES_WOUND, chatter.nextInt(WALKERS));
    }

    /**
     * Steady rowing closes the wound; sprinting or stopping does not. "Steady" is read off the
     * rower's own profile, not a constant: inside their low-to-high speed band and near their rate.
     */
    private void stepLimp(float dt, float speed) {
        if (limp <= 0) {
            steadyState = 0;
            return;
        }
        double rateNow = status != null ? status.strokeRate : 0;
        boolean movingRight = speed >= profile.lowSpeed() && speed <= profile.highSpeed() * 1.06;
        boolean ratedRight = rateNow >= profile.typicalRate() - 5 && rateNow <= profile.typicalRate() + 6;
        if (speed > profile.highSpeed() * 1.06) {
            steadyState = 2;            // thrashing: the leg does not get a chance
        } else if (movingRight && ratedRight) {
            steadyState = 1;
            limp = Math.max(0, limp - dt / LIMP_RECOVER);
            if (limp == 0) {
                pop("LEG HOLDING AGAIN", ACCENT);
            }
        } else {
            steadyState = 0;
        }
    }

    private void reachSurvivor(double threat) {
        if (threat >= RESCUE_CLEAR && groupCount < MAX_GROUP) {
            groupName[groupCount] = survivorName;
            groupShirt[groupCount] = survivorShirt;
            groupCount++;
            pop("RESCUED " + NAMES[survivorName], 0xFF8FB4FF);
            say(LINES_RESCUED, WHO_GROUP + groupCount - 1);
        } else if (groupCount >= MAX_GROUP) {
            pop("GROUP FULL - " + NAMES[survivorName] + " HIDES", WARN);
        } else {
            pop("TOO CLOSE - " + NAMES[survivorName] + " HID", WARN);
        }
        placeNextSurvivor();
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();

        // Wounded, you run slower against the horde than the boat says. This is the speed the chase
        // is settled on; the vitals strip above still shows the true one.
        float chaseSpeed = chaseSpeed(speed);

        if (phase == Phase.RUNNING) {
            // 0.5%/min, down from 1.5%: the waves now carry the escalation the creep used to carry
            // on its own, and stacked on top of them the horde passed this rower's p90 speed by
            // wave 3 and no district could be finished. Modelled against the measured envelope, a
            // typical row now reaches the chopper on banked flares and a p90 row reaches it clear.
            creep = (sessionSeconds - runStartSeconds) / 60.0 * 0.005;
            if (!surging() && !safe() && sessionSeconds >= nextSurgeAt) {
                surgeUntil = sessionSeconds + 10;
                nextSurgeAt = sessionSeconds + (45 + Math.random() * 30) * spawnScale();
            }
            if (!finalChase && runMeters() >= nextSafeHouse) {
                reachSafeHouse();
            }
            if (finalChase && runMeters() >= evacAt) {
                clearDistrict();
            }
            if (runMeters() >= survivorAt) {
                reachSurvivor(threat());
            }
            stepDrop();
            stepLimp(dt, speed);
            // The wall goes up when the rest ends, where the safe house was.
            boolean safeNow = safe();
            if (prevSafe && !safeNow && barricadePlanks > 0 && havenWorld >= 0) {
                barricadeWorld = havenWorld;
                barricadeHp = barricadePlanks;
                pop("BARRICADE UP  ·  " + barricadePlanks + " PLANKS", 0xFFD6A46A);
                barricadePlanks = 0;
            }
            prevSafe = safeNow;

            float hs = hordeSpeed();
            gap += (chaseSpeed - hs) * dt;
            if (pushLeft > 0) {
                double d = Math.min(pushLeft, 14.0 * dt);
                gap += d;
                pushLeft -= d;
            }
            // A standing barricade pins the pack where it is: they cannot pass until it is smashed.
            wallHolding = false;
            if (barricadeHp > 0 && barricadeWorld >= 0) {
                if (runMeters() - gap > barricadeWorld) {
                    wallHolding = true;
                    gap = runMeters() - barricadeWorld;
                    barricadeHp -= dt / SEC_PER_PLANK;
                    if (sessionSeconds - barricadeHitAt > 0.25) {
                        barricadeHitAt = sessionSeconds;
                        shake.kick(dp(3f));
                        if (bubbleUntil < sessionSeconds) {
                            say(LINES_WALL, chatter.nextInt(WALKERS));
                        }
                    }
                    if (barricadeHp <= 0) {
                        barricadeHp = 0;
                        wallHolding = false;
                        pop("BARRICADE DOWN - RUN", BAD);
                        say(LINES_WALL_DOWN, chatter.nextInt(WALKERS));
                        shake.kick(dp(10f));
                    }
                }
            }
            gap = Math.max(0, Math.min(MAX_GAP, gap));
            stepSpecials(dt, hs);
            double threat = threat();
            if (groupCount > 0 && threat <= groupCount * SURV_SPACING) {
                // They reach the survivor at the back of your group before they reach you.
                loseSurvivor();
                grabbedSeconds = 0;
            } else if (threat <= 0) {
                // Grabbed, not gone. Out-row them and you break the grip; only staying slower than
                // the horde finishes it.
                if (chaseSpeed > hs) {
                    grabbedSeconds = Math.max(0, grabbedSeconds - dt * 2.5);
                    // A shove of daylight, so the escape reads on screen. The zombie holding you is
                    // shoved back - moving the whole pack instead (gap = lead + 2) handed you +2 m of
                    // pack gap every time a dashing runner re-caught you, so a runner sped you up.
                    if (gap < 2.0) {
                        gap = 2.0;
                    }
                    shoveSpecialsBack(gap - 2.0);
                } else {
                    grabbedSeconds += dt;
                }
                // Hands are on the handle: a flare in the pocket fires itself.
                if (flares > 0 && grabbedSeconds > 0.5) {
                    fireFlare();
                }
                if (grabbedSeconds >= GRAB_LIMIT) {
                    if (limp <= LIMP_FATAL) {
                        // First bite: they take a piece and you are shoved clear, limping.
                        wound();
                        woundFxPending = true;
                    } else {
                        phase = Phase.CAUGHT;
                        bests.recordHighest("zombie." + Math.round(hordePaceSec), (float) runMeters());
                        if (savedThisRun > 0) {
                            bests.recordHighest(SAVED_KEY, savedThisRun);
                        }
                        if (wavesCleared > 0) {
                            bests.recordHighest(WAVE_KEY, wavesCleared);
                        }
                    }
                }
            } else {
                grabbedSeconds = 0;
            }
        }
        // A district falling gets its own moment: confetti off the top of the screen for a second
        // and a half, over the map that has just lit another node.
        double sinceClear = sessionSeconds - clearedAt;
        if (sinceClear >= 0 && sinceClear < 1.5) {
            // Rate-limited rather than per-frame: the pool holds 240 and the flare needs its share.
            confettiAccum += dt * 40f;
            while (confettiAccum >= 1f) {
                confettiAccum -= 1f;
                int k = (int) (Math.random() * 3);
                sparks.spawn((float) Math.random() * w, -dp(10f), (float) (Math.random() - 0.5) * dp(60f),
                        dp(60f) + (float) Math.random() * dp(90f), 1.6f, dp(3f),
                        k == 0 ? 0xFF35D0BA : k == 1 ? 0xFFFFD27A : 0xFF8FB4FF, true);
            }
        }
        if (mapShown && mapAutoHideAt > 0 && sessionSeconds > mapAutoHideAt) {
            mapShown = false;
            mapAutoHideAt = 0;
        }
        double threat = threat();
        boolean surgeStarting = surging() && phase == Phase.RUNNING && !wasSurging;
        chatter(threat);
        if (surgeStarting || (surging() && sessionSeconds > nextLightning)) {
            lightningAt = sessionSeconds;
            nextLightning = sessionSeconds + 2.5 + Math.random() * 3;
            shake.kick(dp(6f));
        }
        if (phase == Phase.RUNNING) {
            int milestone = (int) (gap / 50) * 50;
            if (milestone >= 50 && milestone > bestMilestone) {
                bestMilestone = milestone;
                pop("+" + milestone + " m CLEAR!", ACCENT);
            }
        }
        float danger = (float) Math.max(0, 1 - threat / 30.0);
        if (danger > 0.3f) {
            shake.kick(danger * dp(5f));
        }
        if (surging() && phase == Phase.RUNNING) {
            shake.kick(dp(1.5f));
        }
        shake.step(dt);
        dust.step(dt, dp(60f));
        sparks.step(dt, dp(90f));
        boolean closing = phase == Phase.RUNNING
                && (threat < 28 || bruteState == 1 || bruteState == 2 || runnerDashing());
        lookBack += ((closing ? 1f : 0f) - lookBack) * Math.min(1f, dt * 3f);

        // Sky reddens as they close in. Quantised so the gradient is rebuilt a handful of times, not every frame.
        float dq = Math.round(danger * 12f) / 12f;
        float groundY = h * 0.70f;
        ensureScene(w, h);
        drawSky(c, w, h, groundY, danger, dq);

        c.save();
        c.translate(shake.dx, shake.dy);
        eyeCount = 0;
        float ppm = w / 90f;   // 90 m across the screen
        double scroll = sessionMeters;
        drawBackdrop(c, w, h, groundY, ppm, scroll, dt);

        float youX = w * 0.62f;
        spkX = youX;
        spkY = groundY - 46f * FIGURE_SCALE * dp(1f);

        // The haven: the safe house you are resting at, the next one up the road, or - in the final
        // chase - the helicopter waiting at the end of it.
        float houseX = -1e6f;
        boolean houseVisible = false;
        boolean chopperShown = false;
        if (phase == Phase.RUNNING) {
            if (safe() && havenWorld >= 0) {
                houseX = youX + (float) (havenWorld - runMeters()) * ppm;
                houseVisible = houseX > -dp(80f);
                chopperShown = havenIsEvac;
            } else if (finalChase) {
                houseX = youX + (float) (evacAt - runMeters()) * ppm;
                houseVisible = houseX < w + dp(80f);
                chopperShown = true;
            } else {
                houseX = youX + (float) (nextSafeHouse - runMeters()) * ppm;
                houseVisible = houseX < w + dp(60f);
            }
        }
        if (houseVisible) {
            if (chopperShown) {
                drawChopper(c, houseX, groundY, dt);
            } else {
                drawSafeHouse(c, houseX, groundY, dt);
            }
        }
        smoke.step(dt, -dp(4f));
        smoke.draw(c);

        // The barricade, standing or being smashed, where you nailed it up.
        nightWallX = -1;
        if (barricadeWorld >= 0 && phase == Phase.RUNNING) {
            float bxw = youX + (float) (barricadeWorld - runMeters()) * ppm;
            if (bxw > -dp(120f) && bxw < w + dp(120f)) {
                nightWallX = bxw;
                drawBarricade(c, bxw, groundY, (float) barricadeHp);
            } else if (bxw <= -dp(120f) && barricadeHp <= 0) {
                barricadeWorld = -1;
            }
        }
        // While the rest lasts, the wall you are nailing up rises at the house itself.
        if (safe() && barricadePlanks > 0 && houseVisible && !chopperShown) {
            drawBarricade(c, houseX + dp(112f), groundY, barricadePlanks);
        }

        // The supply crate: parachuting in, then sitting on its beacon with the clock running.
        float dropX = youX + (float) (dropAt - runMeters()) * ppm;
        nightDropX = dropActive && dropX < w + dp(60f) && dropX > -dp(60f) ? dropX : -1;
        if (dropActive) {
            drawDrop(c, dropX, groundY, h, dt);
            if (dropX > w - dp(46f)) {
                drawDropMarker(c, w, groundY);
            }
        } else if (sessionSeconds - dropGoneAt < 1.2) {
            float k = (float) ((sessionSeconds - dropGoneAt) / 1.2);
            Fx.glow(c, dropX, groundY - dp(24f), dp(70f), (((int) (120 * (1 - k))) << 24) | 0xFF3A2A);
        }

        // A survivor waiting on a wrecked car ahead.
        float survX = youX + (float) (survivorAt - runMeters()) * ppm;
        boolean survVisible = phase == Phase.RUNNING && survX < w + dp(80f) && survX > youX + dp(20f);
        if (survVisible) {
            drawWreck(c, survX, groundY, threat);
        }

        // You, running, with a glow and dust off your heels.
        // blend() forces opaque, so the wounded glow is picked rather than mixed.
        Fx.glow(c, youX, groundY - dp(70f), dp(110f), limp > 0.15 ? 0x40FF4A4A : 0x3035D0BA);
        Fx.glow(c, youX, groundY - dp(10f), dp(60f), limp > 0.15 ? 0x50FF4A4A : 0x4035D0BA);
        if (speed > 0.5f && phase == Phase.RUNNING) {
            dustAccum += dt * speed * 6f;
            while (dustAccum >= 1f) {
                dustAccum -= 1f;
                dust.spawn(youX - dp(6f), groundY, -dp(30f) - (float) Math.random() * dp(40f),
                        -dp(10f) - (float) Math.random() * dp(30f), 0.5f, dp(2.5f), 0xAA6B5A3A, true);
            }
            // A wounded leg leaves a trail on the road behind you.
            if (limp > 0.05) {
                bloodAccum += dt * (float) limp * 3.5f;
                while (bloodAccum >= 1f) {
                    bloodAccum -= 1f;
                    dust.spawn(youX + dp(4f), groundY - dp(10f), -dp(10f), dp(30f), 1.4f, dp(2.2f),
                            0xCC8E1B2A, true);
                }
            }
        }
        if (woundFxPending) {
            woundFxPending = false;
            sparks.burst(youX, groundY - dp(30f), 34, dp(150f), 0.9f, dp(3f), 0xFFB3122E, true);
        }
        dust.draw(c);

        // Survivors running behind you, between you and the horde.
        for (int i = groupCount - 1; i >= 0; i--) {
            float gx = youX - (float) ((i + 1) * SURV_SPACING) * ppm;
            drawFigure(c, gx, groundY, groupShirt[i], speed, (float) (scroll * 3.0 + i * 1.7), K_SURVIVOR, false);
            if (bubbleWho == WHO_GROUP + i) {
                spkX = gx;
                spkY = groundY - 46f * FIGURE_SCALE * 0.9f * dp(1f);
            }
        }
        if (lostFxPending) {
            lostFxPending = false;
            float lx = youX - (float) ((groupCount + 1) * SURV_SPACING) * ppm;
            sparks.burst(lx, groundY - dp(40f), 30, dp(140f), 0.8f, dp(3f), 0xFF8FB4FF, true);
        }
        drawFigure(c, youX, groundY, ACCENT, speed, (float) (scroll * 3.0), K_YOU, false);
        // In a safe house the stroke is a hammer blow: the mallet swings on every plank you nail up.
        if (safe() && phase == Phase.RUNNING) {
            double sinceHit = sessionSeconds - hammerAt;
            float swing = sinceHit < 0.35 ? (float) (1 - sinceHit / 0.35) : 0f;
            float hy = groundY - dp(120f) - swing * dp(26f);
            paint.setColor(0xFF6B5636);
            paint.setStrokeWidth(dp(4f));
            c.drawLine(youX + dp(16f), groundY - dp(96f), youX + dp(34f), hy, paint);
            paint.setColor(0xFF9AA4B4);
            c.drawRect(youX + dp(26f), hy - dp(6f), youX + dp(46f), hy + dp(6f), paint);
            if (sinceHit < 0.12) {
                sparks.spawn(youX + dp(36f), hy, dp(50f), -dp(30f), 0.4f, dp(2f), 0xFFE8D6A0, true);
            }
        }

        // The horde: walkers, then the brute, then any runners out in front.
        float hs = hordeSpeed();
        float bruteSpeed = bruteState == 2 ? hs + (float) bruteBoost : hs;
        walkStride += hs * dt;
        bruteStride += bruteSpeed * dt;
        float hordeX = youX - (float) gap * ppm;
        for (int i = 0; i < WALKERS; i++) {
            float zx = hordeX - (i + 1) * dp(46f) - (i % 3) * dp(14f);
            drawFigure(c, zx, groundY, i % 2 == 0 ? BAD : 0xFF9A3B32, hs,
                    walkStride * 1.7f + i * 1.9f, K_WALKER, false);
            if (i == bubbleWho) {
                spkX = zx;
            }
        }
        float bruteX = hordeX - dp(10f) + (float) bruteLead * ppm;
        float bruteShake = bruteState == 1 ? (float) Math.sin(sessionSeconds * 60) * dp(3f) : 0f;
        if (bruteState == 1 || bruteState == 2) {
            Fx.glow(c, bruteX, groundY - dp(90f), dp(110f), 0x55FF2A1A);
        }
        drawFigure(c, bruteX + bruteShake, groundY, 0xFF4A3A5A, bruteSpeed,
                bruteStride * 1.25f, K_BRUTE, bruteState == 1);
        if (bubbleWho == WHO_BRUTE) {
            spkX = bruteX;
            spkY = groundY - 46f * FIGURE_SCALE * 1.5f * dp(1f);
        }
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] == 0) {
                continue;
            }
            float rx = hordeX + (float) runLead[i] * ppm + i * dp(12f);
            float rs = runState[i] == 1 ? hs + (float) runBoost[i] : Math.max(0.5f, hs * 0.75f);
            runStride[i] += rs * dt;
            drawFigure(c, rx, groundY, 0xFFC7A23A, rs, runStride[i] * 2.4f + i * 3f, K_RUNNER, false);
            if (bubbleWho == WHO_RUNNER + i) {
                spkX = rx;
            }
        }
        if (bubbleWho >= 0 && bubbleWho < WALKERS) {
            spkX = Math.max(dp(40f), spkX);
        }
        // Ground fog rolling over everyone's feet, so the horde wades out of it.
        fogPuffs(c, w, groundY + dp(10f), 6, dp(14f), 3.4f, 0.34f, 52, dp(16f), scroll, ppm, 0.9f, 700);
        // The torch, drawn after the horde: when it swings back it lights the faces it lands on.
        if (!night) {
            drawBeamLight(c, groundY, 1f);
        }

        // The flare: an arc from your hand into the horde, then a burst of red light.
        double sinceFlare = sessionSeconds - flareFiredAt;
        float burstY = groundY - dp(70f);
        if (sinceFlare >= 0 && sinceFlare < FLARE_FLIGHT + 3.0) {
            if (flareAimPending) {
                flareAimPending = false;
                flareFromX = youX;
                flareToX = Math.max(dp(40f), hordeX - dp(40f));
            }
            if (sinceFlare < FLARE_FLIGHT) {
                float k = (float) (sinceFlare / FLARE_FLIGHT);
                float fx = flareFromX + (flareToX - flareFromX) * k;
                float fy = groundY - dp(90f) - (float) Math.sin(k * Math.PI) * dp(160f);
                Fx.glow(c, fx, fy, dp(40f), 0xCCFF6A3A);
                paint.setColor(0xFFFFE0B0);
                c.drawCircle(fx, fy, dp(5f), paint);
                sparks.spawn(fx, fy, (float) (Math.random() - 0.5) * dp(60f), dp(20f), 0.5f, dp(2.5f), 0xFFFF9A4A, true);
            } else if (sinceFlare - dt < FLARE_FLIGHT) {
                sparks.burst(flareToX, burstY, 60, dp(260f), 1.2f, dp(3.5f), 0xFFFF7A3A, true);
                sparks.burst(flareToX, burstY, 25, dp(160f), 0.9f, dp(2.5f), 0xFFFFF0B0, true);
                shake.kick(dp(10f));
            }
        }
        sparks.draw(c);

        if (night) {
            drawNight(c, w, h, youX, groundY, houseVisible ? houseX : -1, survVisible ? survX : -1, sinceFlare, burstY);
        } else if (sinceFlare >= FLARE_FLIGHT && sinceFlare < FLARE_FLIGHT + 2.0) {
            float a = (float) (1 - (sinceFlare - FLARE_FLIGHT) / 2.0);
            Fx.glow(c, flareToX, burstY, dp(260f), ((int) (150 * a) << 24) | 0xFF4A2A);
        }

        drawBubble(c, spkX, spkY, w);
        if (phase == Phase.CAUGHT && !caughtFx) {
            caughtFx = true;
            dust.burst(youX, groundY - dp(24f), 40, dp(120f), 0.9f, dp(3.5f), 0xFFB3122E, true);
            shake.kick(dp(14f));
        }
        c.restore();
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        Fx.vignette(c, w, h, 0.35f + danger * 0.65f, danger > 0.2f ? 0x7A0A0A : 0x000000);
        double sinceFlash = sessionSeconds - lightningAt;
        if (sinceFlash >= 0 && sinceFlash < 0.35) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (150 * (1 - sinceFlash / 0.35)));
            c.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
            paint.setColor(0xFFEAF2FF);
            paint.setStrokeWidth(dp(3f));
            float lx = w * (0.3f + (float) ((lightningAt * 37) % 1.0) * 0.5f);
            float ly = 0;
            for (int k = 0; k < 6; k++) {
                float nx = lx + (float) Math.sin(lightningAt * 50 + k * 3) * dp(26f);
                float ny = ly + h * 0.1f;
                c.drawLine(lx, ly, nx, ny, paint);
                lx = nx;
                ly = ny;
            }
        }
        if (sinceFlare >= FLARE_FLIGHT && sinceFlare < FLARE_FLIGHT + 0.5) {
            // The whole screen flushes red as it goes off.
            paint.setColor(0xFFFF3A1A);
            paint.setAlpha((int) (90 * (1 - (sinceFlare - FLARE_FLIGHT) / 0.5)));
            c.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }
        // The bite: a red flush and a wound-coloured frame that lingers while the leg is bad.
        double sinceWound = sessionSeconds - woundedAt;
        if (sinceWound >= 0 && sinceWound < 0.7) {
            paint.setColor(0xFFB3122E);
            paint.setAlpha((int) (150 * (1 - sinceWound / 0.7)));
            c.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }
        if (limp > 0) {
            float edge = dp(10f) * (float) limp * (0.8f + 0.2f * (float) Math.sin(sessionSeconds * 3));
            paint.setColor(0xFF8E1B2A);
            paint.setAlpha((int) (110 * limp));
            c.drawRect(0, 0, w, edge, paint);
            c.drawRect(0, h - edge, w, h, paint);
            paint.setAlpha(255);
        }
        if (sessionSeconds < popupUntil) {
            float rise = (float) (2.0 - (popupUntil - sessionSeconds)) * dp(30f);
            bold(c, popup, w * 0.62f, h * 0.45f - rise, 26f, popupColour, Paint.Align.CENTER);
        }
        if (sessionSeconds - hardStrokeAt < 0.9) {
            float rise = (float) (sessionSeconds - hardStrokeAt) * dp(40f);
            bold(c, "HARD STROKE", youX, groundY - dp(175f) - rise, 14f, 0xFFFF9A5A, Paint.Align.CENTER);
        }

        drawHud(c, w, h, speed, threat);
    }

    private void drawHud(Canvas c, float w, float h, float speed, double threat) {
        String big;
        int col;
        if (phase == Phase.READY) {
            big = "THEY'RE " + Math.round(START_GAP) + " M BACK";
            col = DIM;
        } else if (phase == Phase.CAUGHT) {
            big = "EATEN";
            col = BAD;
        } else if (grabbedSeconds > 0) {
            big = limp > LIMP_FATAL ? "GRABBED - LAST CHANCE!" : "GRABBED - PULL!";
            col = BAD;
        } else {
            big = Math.round(threat) + " m";
            col = threat > 30 ? ACCENT : threat > 12 ? WARN : BAD;
        }
        bold(c, big, w / 2f, h * 0.17f, phase == Phase.RUNNING ? 54f : 30f, col, Paint.Align.CENTER);
        String cap;
        int capCol = FAINT;
        double toSurvivor = survivorAt - runMeters();
        if (phase == Phase.READY) {
            cap = "horde runs " + PersonalBests.formatPace(hordePaceSec) + " /500 - take a stroke";
        } else if (phase == Phase.CAUGHT) {
            cap = "survived " + Math.round(runMeters()) + " m  ·  " + wavesCleared + " waves  ·  saved "
                    + savedThisRun + "  ·  tap to run again";
        } else if (safe()) {
            cap = (havenIsEvac ? "DROPPED AT " + districtName() + " - " : "SAFE HOUSE - ")
                    + "PULL TO NAIL UP PLANKS  ·  "
                    + barricadePlanks + "/" + MAX_PLANKS + "  ·  " + Math.round(safeUntil - sessionSeconds) + "s";
            capCol = ACCENT;
        } else if (dropActive) {
            cap = "SUPPLY DROP - " + Math.max(0, Math.round(dropAt - runMeters())) + " m in "
                    + tenths(dropDeadline - sessionSeconds) + "s - SPRINT";
            capCol = WARN;
        } else if (wallHolding) {
            cap = "THE WALL IS HOLDING - GO! " + PersonalBests.formatTime((float) (barricadeHp * SEC_PER_PLANK));
            capCol = 0xFFD6A46A;
        } else if (limp > 0) {
            cap = steadyState == 1 ? "STEADY - THE LEG IS COMING BACK"
                    : steadyState == 2 ? "TOO FAST TO HEAL - SETTLE INTO YOUR RHYTHM"
                    : "WOUNDED - FIND A STEADY RHYTHM TO RECOVER";
            capCol = steadyState == 1 ? ACCENT : BAD;
        } else if (grabbedSeconds > 0) {
            cap = "out-row them to break free - " + Math.round(GRAB_LIMIT - grabbedSeconds) + "s";
            capCol = BAD;
        } else if (bruteState == 1) {
            cap = "THE BRUTE IS WINDING UP - PULL!";
            capCol = BAD;
        } else if (bruteState == 2) {
            cap = "BRUTE CHARGING - " + Math.max(0, Math.round(bruteUntil - sessionSeconds)) + "s";
            capCol = BAD;
        } else if (runnerDashing()) {
            cap = "RUNNER BREAKING OUT - HOLD THE GAP";
            capCol = WARN;
        } else if (surging()) {
            cap = "THEY'RE SURGING - " + Math.round(surgeUntil - sessionSeconds) + "s";
            capCol = BAD;
        } else if (finalChase) {
            // Above the survivor line and "something's stirring": in the final chase the surges come
            // round on spawnScale (~0.35), so the stirring line would have masked the one number
            // that matters here for three seconds in every twenty.
            cap = "FINAL CHASE - CHOPPER IN " + Math.max(0, Math.round(evacAt - runMeters())) + " m";
            capCol = 0xFFFF7A4D;
        } else if (sessionSeconds > nextSurgeAt - 3) {
            cap = "SOMETHING'S STIRRING...";
        } else if (toSurvivor < 90) {
            cap = "SURVIVOR IN " + Math.round(toSurvivor) + " m - arrive " + Math.round(RESCUE_CLEAR)
                    + " m clear or they hide";
            capCol = threat >= RESCUE_CLEAR ? 0xFF8FB4FF : WARN;
        } else {
            cap = "nearest zombie  ·  safe house in " + Math.round(nextSafeHouse - runMeters()) + " m";
        }
        // 3.25.0: the caption, the district line and the wave pips used to sit 6dp apart and printed
        // on top of each other at the text sizes the seat needs. Same information, spaced out.
        bold(c, cap, w / 2f, h * 0.17f + dp(25f), 11f, capCol, Paint.Align.CENTER);

        // Route bar: to the next safe house, or - in the final chase - to the chopper, with the
        // survivor waiting on it and the wave pips above.
        if (phase == Phase.RUNNING) {
            float bw = w * 0.30f;
            float bx = w / 2f - bw / 2f;
            float by = h * 0.17f + dp(64f);
            double segStart = finalChase ? districtStart + WAVES_PER_DISTRICT * SAFE_EVERY
                    : nextSafeHouse - SAFE_EVERY;
            double segLen = finalChase ? FINAL_METRES : SAFE_EVERY;
            float f = (float) Math.max(0, Math.min(1, (runMeters() - segStart) / segLen));
            int fillCol = finalChase ? 0xFFFF7A4D : ACCENT;
            paint.setColor(0x66000000);
            c.drawRoundRect(bx - dp(1.5f), by - dp(1.5f), bx + bw + dp(1.5f), by + dp(7.5f), dp(4.5f), dp(4.5f), paint);
            paint.setColor(0x2EFFFFFF);
            c.drawRoundRect(bx, by, bx + bw, by + dp(6f), dp(3f), dp(3f), paint);
            if (f > 0.005f) {
                paint.setColor(fillCol);
                c.drawRoundRect(bx, by, bx + bw * f, by + dp(6f), dp(3f), dp(3f), paint);
                paint.setColor(0x60FFFFFF);
                c.drawRoundRect(bx + dp(1f), by + dp(0.8f), bx + Math.max(dp(2f), bw * f - dp(1f)), by + dp(2.4f),
                        dp(1f), dp(1f), paint);
                Fx.glow(c, bx + bw * f, by + dp(3f), dp(16f), 0x70000000 | (fillCol & 0x00FFFFFF));
            }
            if (survivorAt > segStart && survivorAt < segStart + segLen) {
                float sf = (float) ((survivorAt - segStart) / segLen);
                paint.setColor(0xFF0B1017);
                c.drawCircle(bx + bw * sf, by + dp(3f), dp(6.5f), paint);
                paint.setColor(0xFF8FB4FF);
                c.drawCircle(bx + bw * sf, by + dp(3f), dp(5f), paint);
            }
            if (dropActive && dropAt > segStart && dropAt < segStart + segLen) {
                float df = (float) ((dropAt - segStart) / segLen);
                paint.setColor(WARN);
                c.drawRect(bx + bw * df - dp(3f), by - dp(4f), bx + bw * df + dp(3f), by + dp(9f), paint);
            }
            // The end of the leg: a little house, or the chopper's flag in the final chase.
            float ex = bx + bw + dp(2f);
            paint.setColor(finalChase ? 0xFFFF7A4D : 0xFFFFD27A);
            if (finalChase) {
                c.drawRect(ex - dp(1f), by - dp(10f), ex + dp(1f), by + dp(8f), paint);
                path.rewind();
                path.moveTo(ex + dp(1f), by - dp(10f));
                path.lineTo(ex + dp(13f), by - dp(6f));
                path.lineTo(ex + dp(1f), by - dp(2f));
                path.close();
                c.drawPath(path, paint);
            } else {
                c.drawRect(ex - dp(6f), by - dp(2f), ex + dp(6f), by + dp(8f), paint);
                path.rewind();
                path.moveTo(ex - dp(8.5f), by - dp(1.5f));
                path.lineTo(ex, by - dp(9f));
                path.lineTo(ex + dp(8.5f), by - dp(1.5f));
                path.close();
                c.drawPath(path, paint);
                paint.setColor(0xFF0B1017);
                c.drawRect(ex - dp(1.8f), by + dp(2.5f), ex + dp(1.8f), by + dp(8f), paint);
            }
            // Wave pips: one per wave in this district, filled as they fall.
            for (int i = 0; i < WAVES_PER_DISTRICT; i++) {
                float px = bx + bw / 2f - (WAVES_PER_DISTRICT - 1) * dp(12f) / 2f + i * dp(12f);
                boolean done = finalChase || i < wave - 1;
                if (done) {
                    Fx.glow(c, px, by - dp(10f), dp(9f), 0x8035D0BA);
                }
                paint.setColor(done ? ACCENT : 0x44FFFFFF);
                c.drawCircle(px, by - dp(10f), dp(3.4f), paint);
            }
            label(c, finalChase ? "FINAL CHASE  ·  " + districtName()
                            : districtName() + "  ·  WAVE " + wave + " OF " + WAVES_PER_DISTRICT,
                    bx + bw / 2f, by - dp(19f), 9f, finalChase ? 0xFFFF7A4D : DIM, Paint.Align.CENTER);
        }

        // The wound meter: how much leg you have back, and whether this rhythm is mending it.
        if (limp > 0 && phase == Phase.RUNNING) {
            float bw = w * 0.22f;
            float bx = w / 2f - bw / 2f;
            float by = h * 0.17f + dp(82f);
            paint.setColor(0x66000000);
            c.drawRoundRect(bx - dp(1.5f), by - dp(1.5f), bx + bw + dp(1.5f), by + dp(10.5f), dp(6f), dp(6f), paint);
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(bx, by, bx + bw, by + dp(9f), dp(4.5f), dp(4.5f), paint);
            float lf = (float) (1 - limp);
            if (lf > 0.01f) {
                paint.setColor(steadyState == 1 ? ACCENT : BAD);
                c.drawRoundRect(bx, by, bx + bw * lf, by + dp(9f), dp(4.5f), dp(4.5f), paint);
                paint.setColor(0x55FFFFFF);
                c.drawRoundRect(bx + dp(2f), by + dp(1.2f), bx + Math.max(dp(3f), bw * lf - dp(2f)), by + dp(3.4f),
                        dp(1.2f), dp(1.2f), paint);
            }
            if (steadyState == 1) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6);
                Fx.glow(c, bx + bw * (float) (1 - limp), by + dp(4.5f), dp(26f),
                        (((int) (60 + 70 * pulse)) << 24) | 0x35D0BA);
            }
            label(c, "LEG  " + Math.round((1 - limp) * 100) + "%  ·  " + Math.round(profile.lowSpeed() * 10) / 10f
                            + "-" + Math.round(profile.highSpeed() * 10) / 10f + " m/s",
                    bx + bw / 2f, by + dp(21f), 9f, steadyState == 1 ? ACCENT : DIM, Paint.Align.CENTER);
        }

        // The chase is settled on the limping pace, so that is the figure shown against the horde's.
        float chasePace = chaseSpeed(speed);
        bold(c, pace(chasePace), dp(16f), h * 0.15f, 22f, limp > 0 ? BAD : TEXT, Paint.Align.LEFT);
        label(c, limp > 0 ? "YOU /500 - LIMPING" : "YOU /500", dp(16f), h * 0.15f + dp(16f), 9f,
                limp > 0 ? BAD : FAINT, Paint.Align.LEFT);
        float hs = hordeSpeed();
        bold(c, hs > 0 ? PersonalBests.formatPace(500f / hs) : "--:--",
                w - dp(16f), h * 0.15f, 22f, BAD, Paint.Align.RIGHT);
        label(c, stunned() ? "HORDE /500 - STUNNED" : "HORDE /500", w - dp(16f), h * 0.15f + dp(16f), 9f,
                stunned() ? 0xFFFF9A5A : FAINT, Paint.Align.RIGHT);

        if (wallHolding && barricadeHp > 0) {
            drawWallCam(c, w, h);
        }
        drawNightButton(c);
        drawMapButton(c);
        drawFlareButton(c, w, h);

        float fy = h - dp(12f);
        float col5 = w / 5f;
        if (bottomShader != null) {
            shade.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
            shade.setShader(bottomShader);
            c.drawRect(0, h - dp(78f), w, h, shade);
            shade.setShader(null);
        }
        stat(c, col5 * 0.5f, fy, Math.round(runMeters()) + " m", "SURVIVED");
        stat(c, col5 * 1.5f, fy, wavesCleared + (finalChase ? "  (FINAL)" : ""),
                bests.has(WAVE_KEY) ? "WAVES  ·  BEST " + Math.round(bests.get(WAVE_KEY, 0)) : "WAVES");
        stat(c, col5 * 2.5f, fy, savedThisRun + (groupCount > 0 ? "  (+" + groupCount + ")" : ""),
                bests.has(SAVED_KEY) ? "SAVED  ·  BEST " + Math.round(bests.get(SAVED_KEY, 0)) : "SAVED");
        stat(c, col5 * 3.5f, fy, plankStock > 0 ? plankStock + " planks"
                        : status == null ? "0" : status.strokeRate + " spm",
                plankStock > 0 ? "CARRIED" : "RATE");
        String key = "zombie." + Math.round(hordePaceSec);
        stat(c, col5 * 4.5f, fy, bests.has(key) ? Math.round(bests.get(key, 0)) + " m" : "--", "BEST");
        if (mapShown) {
            drawDistrictMap(c, w, h);
        }
    }

    private void drawNightButton(Canvas c) {
        nightL = dp(16f);
        nightT = dp(10f);
        nightR = dp(128f);
        nightB = dp(44f);
        int fill = night ? 0xFF22304E : 0xFF1A2230;
        panel(c, nightL, nightT, nightR, nightB, dp(17f), fill, night ? 0xAA6F8CFF : 0xFF2A3A50);
        float mx = nightL + dp(20f);
        float my = (nightT + nightB) / 2f;
        if (night) {
            Fx.glow(c, mx, my, dp(16f), 0x60FFE9A8);
        }
        paint.setColor(night ? 0xFFFFE9A8 : DIM);
        c.drawCircle(mx, my, dp(8f), paint);
        paint.setColor(fill);
        c.drawCircle(mx + dp(4f), my - dp(3f), dp(7f), paint);
        bold(c, night ? "NIGHT ON" : "NIGHT", nightL + dp(36f), my + dp(5f), 12f, night ? TEXT : DIM,
                Paint.Align.LEFT);
    }

    /**
     * A rear-view panel of the wall being smashed.
     *
     * <p>Needed because the world view cannot show this: 90 m spans the screen, the pack is off the
     * left edge past about 55 m, and by the time they reach the wall you are further ahead than
     * that - so the whole point of the barricade would have happened out of sight. Here you watch
     * them hammer it, plank by plank, with the head start it is buying you counting down.
     */
    private void drawWallCam(Canvas c, float w, float h) {
        float pw = dp(230f);
        float ph = dp(126f);
        float l = dp(16f);
        float t = h * 0.30f;
        panel(c, l, t, l + pw, t + ph, dp(12f), 0xE00B1017, 0x99D6A46A);
        label(c, "BEHIND YOU", l + pw / 2f, t + dp(15f), 9f, 0xFFD6A46A, Paint.Align.CENTER);

        float floor = t + ph - dp(22f);
        float wallX = l + pw * 0.68f;
        float jitter = (float) Math.sin(sessionSeconds * 60) * dp(2f);
        // Planks left, stacked.
        int whole = (int) Math.ceil(barricadeHp);
        paint.setColor(0xFF5A452E);
        c.drawRect(wallX - dp(3f) + jitter, floor - dp(54f), wallX + dp(3f) + jitter, floor, paint);
        for (int i = 0; i < whole; i++) {
            float py = floor - dp(4f) - i * dp(3.6f);
            paint.setColor(i % 2 == 0 ? 0xFF9A7B4F : 0xFF876A42);
            c.drawRect(wallX - dp(22f) + jitter, py - dp(3f), wallX + dp(22f) + jitter, py, paint);
        }
        // Three of them pounding on it - the same figures as the chase, at a third of the size.
        for (int i = 2; i >= 0; i--) {
            float zx = wallX - dp(40f) - i * dp(26f);
            c.save();
            c.translate(zx, floor);
            c.scale(0.3f, 0.3f);
            drawFigure(c, 0, 0, i % 2 == 0 ? BAD : 0xFF9A3B32, 0f, i * 1.7f, K_WALKER, true);
            c.restore();
        }
        // Splinters flying off on each blow.
        paint.setColor(0xFFD9C89A);
        for (int i = 0; i < 4; i++) {
            float k = (float) ((sessionSeconds * 1.6 + i * 0.25) % 1.0);
            paint.setAlpha((int) (200 * (1 - k)));
            c.drawCircle(wallX - dp(18f) - k * dp(40f), floor - dp(30f) - k * dp(26f), dp(2f), paint);
        }
        paint.setAlpha(255);
        paint.setColor(0xFF1A2416);
        c.drawRect(l + dp(4f), floor, l + pw - dp(4f), t + ph - dp(4f), paint);
        bold(c, PersonalBests.formatTime((float) (barricadeHp * SEC_PER_PLANK)) + "  ·  " + whole + " LEFT",
                l + pw / 2f, t + ph - dp(7f), 12f, 0xFFD6A46A, Paint.Align.CENTER);
    }

    private void drawMapButton(Canvas c) {
        mapL = nightR + dp(10f);
        mapT = nightT;
        mapR = mapL + dp(104f);
        mapB = nightB;
        int mapFill = mapShown ? 0xFF1E3E34 : 0xFF1A2230;
        panel(c, mapL, mapT, mapR, mapB, dp(17f), mapFill, mapShown ? 0xAA35D0BA : 0xFF2A3A50);
        float mx = mapL + dp(20f);
        float my = (mapT + mapB) / 2f;
        // A little folded map with a route on it.
        paint.setColor(mapShown ? 0xFFBFEADD : DIM);
        c.drawRect(mx - dp(9f), my - dp(7f), mx + dp(9f), my + dp(7f), paint);
        paint.setColor(mapFill);
        c.drawRect(mx - dp(3f), my - dp(7f), mx - dp(1f), my + dp(7f), paint);
        c.drawRect(mx + dp(3f), my - dp(7f), mx + dp(5f), my + dp(7f), paint);
        paint.setColor(ACCENT);
        c.drawCircle(mx + dp(6f), my + dp(4f), dp(2f), paint);
        bold(c, "MAP", mapL + dp(36f), my + dp(5f), 12f, mapShown ? TEXT : DIM, Paint.Align.LEFT);
    }

    /**
     * The district map, the one thing here that is kept between sessions ({@code zrun.district}).
     * Eight districts on a winding road: the ones you have cleared flagged and joined by a solid
     * route, the one you are in lit and pulsing with your marker crawling along it, the rest dark
     * with the horde still drifting about in them.
     */
    private void drawDistrictMap(Canvas c, float w, float h) {
        float pad = dp(40f);
        float top = h * 0.24f;
        float bottom = h * 0.78f;
        panel(c, pad, top, w - pad, bottom, dp(18f), 0xF00B1017, 0x6635D0BA);

        // A searchlight sweeping across the paper, so the panel is never still.
        float sweep = (float) ((sessionSeconds * 0.28) % 1.0);
        float sx = pad + (w - pad * 2) * sweep;
        paint.setColor(0x0E9FD8FF);
        c.drawRect(sx - dp(60f), top + dp(2f), sx + dp(60f), bottom - dp(2f), paint);

        int cleared = districtsCleared;
        int current = cleared % DISTRICTS.length;
        int lap = cleared / DISTRICTS.length;
        bold(c, "DISTRICT MAP", w / 2f, top + dp(30f), 20f, TEXT, Paint.Align.CENTER);
        label(c, cleared + " cleared" + (lap > 0 ? "  ·  lap " + (lap + 1) : "")
                        + "  ·  4 waves then the chopper",
                w / 2f, top + dp(50f), 11f, DIM, Paint.Align.CENTER);

        float ny = (top + bottom) / 2f + dp(14f);
        float span = w - pad * 2 - dp(120f);
        float step = span / (DISTRICTS.length - 1);
        float x0 = pad + dp(60f);
        // The road between them.
        paint.setStrokeWidth(dp(3f));
        for (int i = 0; i < DISTRICTS.length - 1; i++) {
            float ax = x0 + i * step;
            float ay = ny + (float) Math.sin(i * 1.1) * dp(34f);
            float bx = x0 + (i + 1) * step;
            float by = ny + (float) Math.sin((i + 1) * 1.1) * dp(34f);
            boolean walked = i < current;
            paint.setColor(walked ? 0xFF35D0BA : 0x33FFFFFF);
            c.drawLine(ax, ay, bx, by, paint);
            if (!walked) {
                // Dashes crawling the other way: the road not taken yet.
                for (int k = 0; k < 4; k++) {
                    float t = (float) (((k * 0.25f) + (sessionSeconds * 0.25) % 0.25) % 1.0);
                    paint.setColor(0x33FF6A5A);
                    c.drawCircle(ax + (bx - ax) * t, ay + (by - ay) * t, dp(2f), paint);
                }
            }
        }
        for (int i = 0; i < DISTRICTS.length; i++) {
            float nx = x0 + i * step;
            float nodeY = ny + (float) Math.sin(i * 1.1) * dp(34f);
            boolean done = i < current;
            boolean here = i == current;
            if (here) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 3);
                Fx.glow(c, nx, nodeY, dp(40f), (((int) (70 + 90 * pulse)) << 24) | 0xFFD27A);
            }
            paint.setColor(done ? 0xFF35D0BA : here ? 0xFFFFD27A : 0xFF2A3446);
            c.drawCircle(nx, nodeY, dp(11f), paint);
            paint.setColor(0xFF0B1017);
            c.drawCircle(nx, nodeY, dp(6f), paint);
            if (done) {
                // A flag planted on a cleared district.
                paint.setColor(0xFF35D0BA);
                c.drawRect(nx - dp(1f), nodeY - dp(26f), nx + dp(1f), nodeY - dp(9f), paint);
                path.reset();
                path.moveTo(nx + dp(1f), nodeY - dp(26f));
                path.lineTo(nx + dp(13f), nodeY - dp(22f));
                path.lineTo(nx + dp(1f), nodeY - dp(18f));
                path.close();
                c.drawPath(path, paint);
            } else if (!here) {
                // Zombies still drifting about in the districts ahead.
                for (int k = 0; k < 3; k++) {
                    float a = (float) (sessionSeconds * 0.6 + k * 2.1 + i);
                    paint.setColor(0x99FF4A4A);
                    c.drawCircle(nx + (float) Math.cos(a) * dp(16f), nodeY + (float) Math.sin(a * 0.7) * dp(9f),
                            dp(2f), paint);
                }
            }
            label(c, DISTRICTS[i], nx, nodeY + dp(30f), 8.5f,
                    done ? ACCENT : here ? TEXT : FAINT, Paint.Align.CENTER);
        }
        // Your marker, crawling through the district you are in.
        float progress = 0f;
        if (phase == Phase.RUNNING) {
            double through = finalChase
                    ? (WAVES_PER_DISTRICT * SAFE_EVERY + finalProgress() * FINAL_METRES)
                    : (runMeters() - districtStart);
            progress = (float) Math.max(0, Math.min(1, through
                    / (WAVES_PER_DISTRICT * SAFE_EVERY + FINAL_METRES)));
        }
        float cx = x0 + (current + Math.min(0.92f, progress)) * step;
        float cy = ny + (float) Math.sin((current + progress) * 1.1) * dp(34f);
        if (current < DISTRICTS.length) {
            float bob = (float) Math.abs(Math.sin(sessionSeconds * 5)) * dp(4f);
            Fx.glow(c, cx, cy - dp(16f) - bob, dp(26f), 0x6635D0BA);
            paint.setColor(ACCENT);
            c.drawCircle(cx, cy - dp(18f) - bob, dp(5f), paint);
            c.drawRect(cx - dp(2f), cy - dp(15f) - bob, cx + dp(2f), cy - dp(6f) - bob, paint);
        }
        label(c, phase == Phase.READY ? "tap anywhere to close  ·  take a stroke to run"
                        : "tap anywhere to close", w / 2f, bottom - dp(16f), 10f, FAINT, Paint.Align.CENTER);
    }

    private void drawFlareButton(Canvas c, float w, float h) {
        flareR = w - dp(16f);
        flareL = flareR - dp(200f);
        flareB = h - dp(56f);
        flareT = flareB - dp(64f);
        boolean ready = flares > 0 && phase == Phase.RUNNING;
        if (ready) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 5);
            Fx.glow(c, (flareL + flareR) / 2f, (flareT + flareB) / 2f, dp(120f),
                    ((int) (60 + 60 * pulse) << 24) | 0xFF5A2A);
        }
        panel(c, flareL, flareT, flareR, flareB, dp(14f), ready ? 0xFF6E2016 : 0xE00D1420,
                ready ? 0xFFFF7A4D : 0xFF2A3A50);
        bold(c, "FLARE", flareL + dp(14f), flareT + dp(26f), 18f, ready ? 0xFFFFE0C0 : DIM, Paint.Align.LEFT);
        // Flares held: little red sticks.
        for (int i = 0; i < MAX_FLARES; i++) {
            float sx = flareL + dp(100f) + i * dp(22f);
            if (i < flares) {
                paint.setColor(0xFFE8432A);
                c.drawRoundRect(sx, flareT + dp(8f), sx + dp(10f), flareT + dp(30f), dp(3f), dp(3f), paint);
                paint.setColor(0xFF8A1E12);
                c.drawRect(sx, flareT + dp(17f), sx + dp(10f), flareT + dp(20f), paint);
                paint.setColor(0x66FFFFFF);
                c.drawRect(sx + dp(1.5f), flareT + dp(10f), sx + dp(3.5f), flareT + dp(28f), paint);
                float sp = 0.7f + 0.3f * (float) Math.sin(sessionSeconds * 11 + i * 2);
                Fx.glow(c, sx + dp(5f), flareT + dp(7f), dp(10f), ((int) (0xB0 * sp) << 24) | 0xFFC060);
                paint.setColor(0xFFFFE0A0);
                c.drawCircle(sx + dp(5f), flareT + dp(7f), dp(3f), paint);
            } else {
                paint.setColor(0x26FFFFFF);
                c.drawRoundRect(sx, flareT + dp(8f), sx + dp(10f), flareT + dp(30f), dp(3f), dp(3f), paint);
            }
        }
        // Charge pips: three hard strokes make a flare.
        for (int i = 0; i < PIPS_PER_FLARE; i++) {
            float px = flareL + dp(20f) + i * dp(16f);
            if (i < chargePips) {
                Fx.glow(c, px, flareB - dp(15f), dp(11f), 0x80FF9A5A);
            }
            paint.setColor(i < chargePips ? 0xFFFF9A5A : 0x33FFFFFF);
            c.drawCircle(px, flareB - dp(15f), dp(5f), paint);
        }
        label(c, "hard strokes > " + Math.round(hardWatts()) + " W", flareL + dp(66f), flareB - dp(11f), 9f,
                FAINT, Paint.Align.LEFT);
    }

    /** Pitch dark but for the torch, the lights of the living, and the flare. Zombies are their eyes. */
    private void drawNight(Canvas c, float w, float h, float youX, float groundY, float houseX, float survX,
                           double sinceFlare, float burstY) {
        float lx = lampX >= 0 ? lampX : youX;
        float ly = lampX >= 0 ? lampY : groundY - 40f * FIGURE_SCALE * dp(1f);
        darkPath.reset();
        darkPath.setFillType(Path.FillType.WINDING);
        buildBeam(darkPath, lx, ly, beamLen);
        darkPath.addCircle(youX, groundY - dp(60f), dp(105f), Path.Direction.CW);
        // Light holes sized to the props, which are drawn at the figures' scale since 3.25.0.
        if (houseX >= 0) {
            darkPath.addCircle(houseX, groundY - dp(60f), dp(150f), Path.Direction.CW);
        }
        if (survX >= 0) {
            darkPath.addCircle(survX, groundY - dp(150f), dp(140f), Path.Direction.CW);
        }
        if (nightDropX >= 0) {
            // The crate burns a beacon, and the parachute is white against the sky.
            darkPath.addCircle(nightDropX, Math.min(groundY - dp(50f), nightDropY - dp(30f)),
                    dp(130f), Path.Direction.CW);
        }
        if (nightWallX >= 0) {
            darkPath.addCircle(nightWallX, groundY - dp(50f), dp(120f), Path.Direction.CW);
        }
        float flareLight = 0f;
        if (sinceFlare >= FLARE_FLIGHT && sinceFlare < FLARE_FLIGHT + 3.0) {
            flareLight = (float) (1 - (sinceFlare - FLARE_FLIGHT) / 3.0);
            darkPath.addCircle(flareToX, burstY, dp(80f) + dp(520f) * flareLight, Path.Direction.CW);
        }
        double sinceFlash = sessionSeconds - lightningAt;
        int darkAlpha = sinceFlash >= 0 && sinceFlash < 0.5 ? 120 : 240;
        c.save();
        c.clipPath(darkPath, Region.Op.DIFFERENCE);
        paint.setColor((darkAlpha << 24) | 0x030509);
        c.drawRect(-dp(40f), -dp(40f), w + dp(40f), h + dp(40f), paint);
        c.restore();

        // Warm light inside the beam, and a red wash from the flare.
        drawBeamLight(c, groundY, 1.25f);
        if (flareLight > 0) {
            Fx.glow(c, flareToX, burstY, dp(300f), ((int) (130 * flareLight) << 24) | 0xFF3A1A);
        }

        // Fog, thicker at night, drifting through the light and the dark alike.
        float ppm = w / 90f;
        fogPuffs(c, w, groundY - dp(20f), 7, dp(40f), 3.0f, 0.5f, 58, dp(18f), sessionMeters, ppm, 0.5f, 900);
        fogPuffs(c, w, groundY - dp(95f), 5, dp(50f), 3.6f, 0.45f, 34, dp(12f), sessionMeters, ppm, 0.35f, 950);

        // Eyes in the dark.
        for (int i = 0; i < eyeCount; i++) {
            Fx.glow(c, eyeX[i], eyeY[i], dp(12f), 0x88FF2A2A);
            paint.setColor(0xFFFF4A4A);
            c.drawCircle(eyeX[i], eyeY[i], dp(3.2f), paint);
        }
    }

    /** The torch cone: forward along the ground, swinging back over your shoulder when something closes. */
    private void buildBeam(Path p, float x, float headY, float len) {
        double a = beamAngle();
        double spread = BEAM_SPREAD;
        float ox = x;
        p.moveTo(ox, headY);
        p.lineTo(ox + (float) Math.cos(a - spread) * len, headY + (float) Math.sin(a - spread) * len);
        p.lineTo(ox + (float) Math.cos(a + spread) * len, headY + (float) Math.sin(a + spread) * len);
        p.close();
    }

    /**
     * The safe house, at the figures' scale: weatherboarded walls with a moonlit corner, a roof with
     * a lit ridge, warm flickering windows, a boarded door with light at the jamb and a porch lamp.
     */
    private void drawSafeHouse(Canvas c, float houseX, float groundY, float dt) {
        boolean in = safe();
        float k = HOUSE_K;
        Fx.glow(c, houseX + dp(10f), groundY - dp(40f), dp(180f), in ? 0x5035D0BA : 0x40FFB45A);
        paint.setColor(0x70000000);
        c.drawOval(houseX - dp(30f) * k, groundY - dp(4f), houseX + dp(36f) * k, groundY + dp(8f), paint);
        c.save();
        c.scale(k, k, houseX, groundY);
        float x = houseX;
        float g = groundY;
        paint.setColor(in ? 0xFF24655C : 0xFF323D4F);
        c.drawRect(x - dp(22f), g - dp(46f), x + dp(22f), g, paint);
        paint.setColor(0x2A000000);
        for (int i = 1; i < 9; i++) {
            float ry = g - dp(46f) + i * dp(5f);
            c.drawRect(x - dp(22f), ry, x + dp(22f), ry + dp(0.8f), paint);
        }
        paint.setColor(in ? 0x6035D0BA : 0x406F8CB8);
        c.drawRect(x + dp(19.5f), g - dp(46f), x + dp(22f), g, paint);
        paint.setColor(0xFF2A3342);
        c.drawRect(x + dp(10f), g - dp(66f), x + dp(17f), g - dp(50f), paint);
        paint.setColor(0xFF3A4558);
        c.drawRect(x + dp(9f), g - dp(68f), x + dp(18f), g - dp(65f), paint);
        path.rewind();
        path.moveTo(x - dp(29f), g - dp(45f));
        path.lineTo(x, g - dp(67f));
        path.lineTo(x + dp(29f), g - dp(45f));
        path.close();
        paint.setColor(0xFF1C2330);
        c.drawPath(path, paint);
        paint.setColor(0x55000000);
        c.drawRect(x - dp(22f), g - dp(46f), x + dp(22f), g - dp(43f), paint);
        limb.setStrokeWidth(dp(1.6f));
        limb.setColor(0xFF51627E);
        c.drawLine(x, g - dp(67f), x + dp(29f), g - dp(45f), limb);
        float fl = 0.85f + 0.15f * (float) Math.sin(sessionSeconds * 7.3);
        int win = ((int) (0x90 * fl) << 24) | 0xFFC05A;
        Fx.glow(c, x - dp(12.5f), g - dp(34f), dp(14f), win);
        Fx.glow(c, x + dp(12.5f), g - dp(34f), dp(14f), win);
        paint.setColor(0xFFFFD27A);
        c.drawRect(x - dp(16f), g - dp(38f), x - dp(9f), g - dp(30f), paint);
        c.drawRect(x + dp(9f), g - dp(38f), x + dp(16f), g - dp(30f), paint);
        paint.setColor(0xFF6A4A2A);
        c.drawRect(x - dp(13f), g - dp(38f), x - dp(12f), g - dp(30f), paint);
        c.drawRect(x - dp(16f), g - dp(34.5f), x - dp(9f), g - dp(33.5f), paint);
        c.drawRect(x + dp(12f), g - dp(38f), x + dp(13f), g - dp(30f), paint);
        c.drawRect(x + dp(9f), g - dp(34.5f), x + dp(16f), g - dp(33.5f), paint);
        paint.setColor(0xFF140F0C);
        c.drawRect(x - dp(6f), g - dp(24f), x + dp(6f), g, paint);
        paint.setColor(in ? 0xFF7FE8D8 : 0xFFFFC870);
        c.drawRect(x - dp(6f), g - dp(24f), x - dp(4.6f), g, paint);
        paint.setColor(0xFF6B5636);
        c.drawRect(x - dp(7f), g - dp(18f), x + dp(7f), g - dp(16f), paint);
        c.drawRect(x - dp(7f), g - dp(9f), x + dp(7f), g - dp(7f), paint);
        Fx.glow(c, x + dp(9f), g - dp(22f), dp(10f), 0xB0FFD27A);
        paint.setColor(0xFFFFF0C0);
        c.drawCircle(x + dp(9f), g - dp(22f), dp(1.6f), paint);
        c.restore();
        label(c, "SAFE HOUSE", houseX, groundY - dp(67f) * k - dp(10f), 8f, in ? ACCENT : DIM, Paint.Align.CENTER);
        smokeAccum += dt * 4f;
        while (smokeAccum >= 1f) {
            smokeAccum -= 1f;
            smoke.spawn(houseX + dp(13.5f) * k, groundY - dp(68f) * k, dp(6f), -dp(20f) - (float) Math.random() * dp(10f),
                    1.8f, dp(8f), 0x557A8494, false);
        }
    }

    /**
     * The extraction helicopter at the end of the final chase: rotor turning, spotlight sweeping the
     * road, a rope ladder swinging. It sits low over a landing pad so it reads as somewhere to reach.
     */
    private void drawChopper(Canvas c, float x, float groundY, float dt) {
        c.save();
        c.scale(CHOPPER_K, CHOPPER_K, x, groundY);
        float hover = (float) Math.sin(sessionSeconds * 1.6) * dp(5f);
        float bodyY = groundY - dp(150f) + hover;
        // Spotlight down onto the road.
        path.reset();
        path.moveTo(x - dp(8f), bodyY + dp(12f));
        path.lineTo(x - dp(70f), groundY + dp(6f));
        path.lineTo(x + dp(70f), groundY + dp(6f));
        path.lineTo(x + dp(8f), bodyY + dp(12f));
        path.close();
        paint.setColor(0x33FFF0C0);
        c.drawPath(path, paint);
        Fx.glow(c, x, groundY - dp(10f), dp(90f), 0x40FFE8A0);
        // Landing pad.
        paint.setColor(0xFF2A3446);
        c.drawOval(x - dp(60f), groundY - dp(12f), x + dp(60f), groundY + dp(8f), paint);
        paint.setColor(0xFFFFD27A);
        c.drawRect(x - dp(3f), groundY - dp(8f), x + dp(3f), groundY + dp(4f), paint);
        c.drawRect(x - dp(12f), groundY - dp(4f), x + dp(12f), groundY, paint);
        // Body, tail and skids.
        paint.setColor(0xFF3E5A48);
        c.drawOval(x - dp(38f), bodyY - dp(20f), x + dp(26f), bodyY + dp(16f), paint);
        c.drawRect(x + dp(20f), bodyY - dp(6f), x + dp(78f), bodyY + dp(2f), paint);
        paint.setColor(0xFF2E4436);
        c.drawRect(x + dp(70f), bodyY - dp(26f), x + dp(76f), bodyY + dp(2f), paint);
        paint.setColor(0xFF2E4436);
        c.drawOval(x - dp(30f), bodyY + dp(2f), x + dp(24f), bodyY + dp(16f), paint);
        paint.setColor(0x665E8A70);
        c.drawOval(x - dp(26f), bodyY - dp(19f), x + dp(16f), bodyY - dp(12f), paint);
        paint.setColor(0xFF9FD8FF);
        c.drawOval(x - dp(36f), bodyY - dp(12f), x - dp(8f), bodyY + dp(8f), paint);
        paint.setColor(0xCCFFFFFF);
        c.drawOval(x - dp(31f), bodyY - dp(9f), x - dp(22f), bodyY - dp(4f), paint);
        paint.setColor(0xFF22303E);
        c.drawRect(x - dp(30f), bodyY + dp(20f), x + dp(18f), bodyY + dp(23f), paint);
        // Rotor: a blur that actually turns.
        float spin = (float) (sessionSeconds * 22);
        paint.setStrokeWidth(dp(3f));
        for (int i = 0; i < 3; i++) {
            float a = spin + i * 2.094f;
            paint.setColor(0xCCC8D4E2);
            c.drawLine(x - dp(6f), bodyY - dp(24f),
                    x - dp(6f) + (float) Math.cos(a) * dp(74f),
                    bodyY - dp(24f) + (float) Math.sin(a) * dp(12f), paint);
        }
        paint.setColor(0xFF1A2230);
        c.drawCircle(x - dp(6f), bodyY - dp(24f), dp(5f), paint);
        // Rope ladder swinging under the door.
        float sway = (float) Math.sin(sessionSeconds * 2.2) * dp(6f);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xFFC9B284);
        c.drawLine(x - dp(24f), bodyY + dp(16f), x - dp(24f) + sway, groundY - dp(30f), paint);
        c.drawLine(x - dp(12f), bodyY + dp(16f), x - dp(12f) + sway, groundY - dp(30f), paint);
        for (int i = 0; i < 5; i++) {
            float t = (i + 1) / 6f;
            float ry = bodyY + dp(16f) + (groundY - dp(30f) - bodyY - dp(16f)) * t;
            c.drawLine(x - dp(24f) + sway * t, ry, x - dp(12f) + sway * t, ry, paint);
        }
        // Beacon.
        if (((int) (sessionSeconds * 3)) % 2 == 0) {
            Fx.glow(c, x + dp(24f), bodyY + dp(12f), dp(20f), 0xAAFF4A4A);
        }
        label(c, safe() ? "EXTRACTION" : "EVAC  ·  " + Math.max(0, Math.round(evacAt - runMeters())) + " m",
                x, bodyY - dp(44f), 11f, ACCENT, Paint.Align.CENTER);
        c.restore();
        smokeAccum += dt * 3f;
        while (smokeAccum >= 1f) {
            smokeAccum -= 1f;
            smoke.spawn(x, groundY - dp(4f) * CHOPPER_K, -dp(40f) - (float) Math.random() * dp(30f), -dp(8f),
                    1.2f, dp(6f), 0x44B0BCCC, false);
        }
    }

    /**
     * The barricade: planks across the road. Draws what is left of it, shaking and shedding splinters
     * while the horde is smashing through, and rubble once it is down.
     */
    private void drawBarricade(Canvas c, float x, float groundY, float planks) {
        int whole = (int) Math.ceil(planks);
        boolean smashing = wallHolding && barricadeHp > 0 && sessionSeconds - barricadeHitAt < 0.25;
        float jitter = smashing ? (float) Math.sin(sessionSeconds * 70) * dp(3f) : 0f;
        paint.setColor(0x66000000);
        c.drawOval(x - dp(34f) * WALL_K, groundY - dp(4f), x + dp(34f) * WALL_K, groundY + dp(6f), paint);
        c.save();
        c.scale(WALL_K, WALL_K, x, groundY);
        // Posts, lit on the moon side.
        paint.setColor(0xFF5A452E);
        c.drawRect(x - dp(26f) + jitter, groundY - dp(62f), x - dp(19f) + jitter, groundY, paint);
        c.drawRect(x + dp(19f) + jitter, groundY - dp(62f), x + dp(26f) + jitter, groundY, paint);
        paint.setColor(0xFF8A6E48);
        c.drawRect(x - dp(21f) + jitter, groundY - dp(62f), x - dp(19f) + jitter, groundY, paint);
        c.drawRect(x + dp(24f) + jitter, groundY - dp(62f), x + dp(26f) + jitter, groundY, paint);
        for (int i = 0; i < whole; i++) {
            float py = groundY - dp(6f) - i * dp(4.2f);
            float tilt = ((i * 7) % 5 - 2) * dp(1.2f);
            float pl = x - dp(30f) + jitter + tilt;
            float pr = x + dp(30f) + jitter + tilt;
            paint.setColor(i % 2 == 0 ? 0xFF9A7B4F : 0xFF876A42);
            c.drawRect(pl, py - dp(4f), pr, py, paint);
            paint.setColor(0x40FFE8C0);
            c.drawRect(pl, py - dp(4f), pr, py - dp(3.3f), paint);
            paint.setColor(0x40000000);
            c.drawRect(pl, py - dp(1.2f), pr, py, paint);
            c.drawRect(pl + dp(12f + (i * 5) % 9), py - dp(2.6f), pl + dp(22f + (i * 5) % 9), py - dp(2f), paint);
        }
        if (whole > 0) {
            // Nail heads catch what light there is.
            paint.setColor(0xFFD9C89A);
            c.drawCircle(x - dp(22f) + jitter, groundY - dp(20f), dp(1.8f), paint);
            c.drawCircle(x + dp(22f) + jitter, groundY - dp(34f), dp(1.8f), paint);
        } else {
            paint.setColor(0xFF6B5636);
            c.drawRect(x - dp(30f), groundY - dp(6f), x + dp(30f), groundY - dp(2f), paint);
            c.drawRect(x - dp(14f), groundY - dp(12f), x + dp(20f), groundY - dp(8f), paint);
        }
        c.restore();
        if (smashing) {
            sparks.spawn(x - dp(26f) * WALL_K, groundY - (dp(20f) + (float) Math.random() * dp(30f)) * WALL_K,
                    -dp(40f) - (float) Math.random() * dp(80f), -dp(60f) - (float) Math.random() * dp(60f),
                    0.7f, dp(2.4f), 0xFFB08A50, true);
        }
        if (wallHolding && barricadeHp > 0) {
            label(c, "HOLDING  " + PersonalBests.formatTime((float) (barricadeHp * SEC_PER_PLANK)),
                    x, groundY - dp(70f) * WALL_K, 10f, 0xFFD6A46A, Paint.Align.CENTER);
        } else if (whole > 0) {
            label(c, whole + " PLANKS", x, groundY - dp(70f) * WALL_K, 9f, 0xFFD6A46A, Paint.Align.CENTER);
        }
    }

    /** A chevron at the right edge while the crate is still beyond it, with the distance to run. */
    private void drawDropMarker(Canvas c, float w, float groundY) {
        float x = w - dp(30f);
        float y = groundY - dp(120f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 7);
        Fx.glow(c, x, y, dp(46f), (((int) (60 + 70 * pulse)) << 24) | 0xFFB132);
        paint.setColor(WARN);
        path.reset();
        path.moveTo(x - dp(12f), y - dp(14f));
        path.lineTo(x + dp(12f), y);
        path.lineTo(x - dp(12f), y + dp(14f));
        path.close();
        c.drawPath(path, paint);
        bold(c, Math.max(0, Math.round(dropAt - runMeters())) + " m", x - dp(6f), y + dp(34f), 13f,
                WARN, Paint.Align.RIGHT);
        label(c, "SUPPLY", x - dp(6f), y - dp(24f), 9f, WARN, Paint.Align.RIGHT);
    }

    /**
     * The supply crate: a parachute for the first couple of seconds, then a crate on a smoke beacon
     * with a countdown ring burning down around it.
     */
    private void drawDrop(Canvas c, float x, float groundY, float h, float dt) {
        double since = sessionSeconds - dropSpawnedAt;
        float fall = (float) Math.min(1.0, since / 2.5);
        float y = groundY - (1 - fall) * h * 0.85f;
        nightDropY = y;
        float left = (float) Math.max(0, dropDeadline - sessionSeconds);
        float frac = (float) Math.max(0, Math.min(1, left / DROP_WINDOW));
        int tint = dropLoot == LOOT_MEDKIT ? 0xFFE04A4A : dropLoot == LOOT_PLANKS ? 0xFFD6A46A : 0xFFFF7A4D;
        if (fall >= 1f) {
            paint.setColor(0x66000000);
            c.drawOval(x - dp(30f) * CRATE_K, groundY - dp(4f), x + dp(32f) * CRATE_K, groundY + dp(6f), paint);
        }
        c.save();
        c.scale(CRATE_K, CRATE_K, x, y);
        if (fall < 1f) {
            // Canopy.
            paint.setColor(0xFFE8EDF5);
            c.drawArc(x - dp(42f), y - dp(76f), x + dp(42f), y - dp(8f), 180, 180, true, paint);
            paint.setColor(0xFFB9C4D4);
            c.drawArc(x - dp(14f), y - dp(76f), x + dp(14f), y - dp(8f), 180, 180, true, paint);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(0xFFDCE4EF);
            c.drawLine(x - dp(40f), y - dp(40f), x - dp(10f), y - dp(14f), paint);
            c.drawLine(x + dp(40f), y - dp(40f), x + dp(10f), y - dp(14f), paint);
        } else {
            // Beacon smoke and a pulse on the ground.
            float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6);
            Fx.glow(c, x, groundY - dp(10f), dp(70f), (((int) (70 + 80 * pulse)) << 24) | (tint & 0x00FFFFFF));
            dropSmokeAccum += dt * 8f;
            while (dropSmokeAccum >= 1f) {
                dropSmokeAccum -= 1f;
                smoke.spawn(x + dp(20f) * CRATE_K, groundY - dp(12f) * CRATE_K, dp(6f), -dp(26f), 1.6f, dp(7f),
                        0x55FF9A6A, false);
            }
        }
        // Crate: planks, a lit top edge, iron corners.
        paint.setColor(0xFF7E6136);
        c.drawRect(x - dp(20f), y - dp(28f), x + dp(20f), y, paint);
        paint.setColor(0x30000000);
        c.drawRect(x - dp(20f), y - dp(21.5f), x + dp(20f), y - dp(21f), paint);
        c.drawRect(x - dp(20f), y - dp(7f), x + dp(20f), y - dp(6.5f), paint);
        paint.setColor(0xFFA88850);
        c.drawRect(x - dp(20f), y - dp(28f), x + dp(20f), y - dp(26.5f), paint);
        paint.setColor(0xFF5E4726);
        c.drawRect(x - dp(20f), y - dp(16f), x + dp(20f), y - dp(12f), paint);
        c.drawRect(x - dp(3f), y - dp(28f), x + dp(3f), y, paint);
        paint.setColor(0xFF3A3F48);
        c.drawRect(x - dp(20f), y - dp(28f), x - dp(16f), y - dp(24f), paint);
        c.drawRect(x + dp(16f), y - dp(28f), x + dp(20f), y - dp(24f), paint);
        c.drawRect(x - dp(20f), y - dp(4f), x - dp(16f), y, paint);
        c.drawRect(x + dp(16f), y - dp(4f), x + dp(20f), y, paint);
        paint.setColor(tint);
        c.drawRect(x - dp(12f), y - dp(24f), x - dp(6f), y - dp(18f), paint);
        if (dropLoot == LOOT_MEDKIT) {
            paint.setColor(0xFFFFFFFF);
            c.drawRect(x + dp(4f), y - dp(24f), x + dp(16f), y - dp(21f), paint);
            c.drawRect(x + dp(8.5f), y - dp(28f), x + dp(11.5f), y - dp(17f), paint);
        }
        // Countdown ring.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3.5f));
        paint.setColor(0x33FFFFFF);
        c.drawArc(x - dp(34f), y - dp(48f), x + dp(34f), y + dp(20f), 0, 360, false, paint);
        paint.setColor(frac > 0.4f ? ACCENT : frac > 0.18f ? WARN : BAD);
        c.drawArc(x - dp(34f), y - dp(48f), x + dp(34f), y + dp(20f), -90, 360 * frac, false, paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, tenths(left) + "s", x, y - dp(58f), 14f,
                frac > 0.18f ? TEXT : BAD, Paint.Align.CENTER);
        label(c, dropLoot == LOOT_MEDKIT ? "MEDKIT" : dropLoot == LOOT_PLANKS ? "PLANKS" : "FLARES",
                x, y - dp(70f), 9f, tint, Paint.Align.CENTER);
        c.restore();
    }

    /** A wrecked car with a survivor on the roof, waving, a lighter held up in the dark. */
    private void drawWreck(Canvas c, float sx, float groundY, double threat) {
        paint.setColor(0x70000000);
        c.drawOval(sx - dp(50f) * CAR_K, groundY - dp(4f), sx + dp(52f) * CAR_K, groundY + dp(7f), paint);
        c.save();
        c.scale(CAR_K, CAR_K, sx, groundY);
        // A rusted-out car: body, cabin, a moonlit roofline, rust, smashed glass and a flat.
        paint.setColor(0xFF4A3A34);
        c.drawRoundRect(sx - dp(46f), groundY - dp(28f), sx + dp(46f), groundY - dp(8f), dp(6f), dp(6f), paint);
        paint.setColor(0xFF3A2E2A);
        c.drawRoundRect(sx - dp(26f), groundY - dp(44f), sx + dp(20f), groundY - dp(26f), dp(6f), dp(6f), paint);
        paint.setColor(0x60A9B8CC);
        c.drawRect(sx - dp(22f), groundY - dp(44f), sx + dp(16f), groundY - dp(43f), paint);
        c.drawRect(sx - dp(44f), groundY - dp(28f), sx + dp(44f), groundY - dp(27f), paint);
        paint.setColor(0xFF6A3E26);
        c.drawCircle(sx - dp(34f), groundY - dp(16f), dp(4f), paint);
        c.drawCircle(sx + dp(12f), groundY - dp(13f), dp(3f), paint);
        c.drawCircle(sx + dp(30f), groundY - dp(20f), dp(2.5f), paint);
        paint.setColor(0x40000000);
        c.drawRect(sx - dp(4f), groundY - dp(26f), sx - dp(3f), groundY - dp(9f), paint);
        paint.setColor(0xFF1C2230);
        c.drawRect(sx - dp(21f), groundY - dp(40f), sx - dp(4f), groundY - dp(29f), paint);
        c.drawRect(sx, groundY - dp(40f), sx + dp(15f), groundY - dp(29f), paint);
        limb.setStrokeWidth(dp(0.8f));
        limb.setColor(0x99A9C1E6);
        c.drawLine(sx + dp(3f), groundY - dp(38f), sx + dp(9f), groundY - dp(32f), limb);
        c.drawLine(sx + dp(9f), groundY - dp(32f), sx + dp(13f), groundY - dp(36f), limb);
        c.drawLine(sx - dp(18f), groundY - dp(31f), sx - dp(10f), groundY - dp(38f), limb);
        paint.setColor(0xFF111111);
        c.drawCircle(sx - dp(28f), groundY - dp(7f), dp(8f), paint);
        c.drawOval(sx + dp(19f), groundY - dp(13f), sx + dp(37f), groundY, paint);
        paint.setColor(0xFF3A3A3A);
        c.drawCircle(sx - dp(28f), groundY - dp(7f), dp(3.5f), paint);
        c.drawCircle(sx + dp(28f), groundY - dp(6f), dp(3f), paint);
        if (((int) (sessionSeconds * 2)) % 2 == 0) {
            Fx.glow(c, sx + dp(44f), groundY - dp(22f), dp(22f), 0xAAFFB02A);
            paint.setColor(0xFFFFD27A);
            c.drawCircle(sx + dp(44f), groundY - dp(22f), dp(2f), paint);
        }
        c.restore();
        float roof = groundY - dp(44f) * CAR_K;
        drawFigure(c, sx - dp(4f) * CAR_K, roof, survivorShirt, 0f, 0f, K_SURVIVOR, true);
        boolean clear = threat >= RESCUE_CLEAR;
        label(c, NAMES[survivorName] + (clear ? " - COMING!" : " - TOO CLOSE, HIDING?"), sx,
                roof - 50f * FIGURE_SCALE * 0.9f * dp(1f), 10f, clear ? 0xFF8FB4FF : WARN, Paint.Align.CENTER);
        if (bubbleUntil < sessionSeconds && sessionSeconds >= nextBubbleAt - 1.5) {
            say(LINES_HELP, WHO_WAITING);
        }
        if (bubbleWho == WHO_WAITING) {
            spkX = sx;
            spkY = roof - 58f * FIGURE_SCALE * 0.9f * dp(1f);
        }
    }

    /* ---------- 3.25.0 graphics: the torch ---------- */

    /** Beam direction: forward along the road, swinging back over your shoulder when something closes. */
    private double beamAngle() {
        return 0.20 + (Math.PI - 0.40) * lookBack;
    }

    /**
     * The headlamp's light, added to the scene rather than painted over it: a warm cone that fades
     * with distance, a brighter core, dust motes drifting in it, and a pool where it meets the road.
     * Anything inside it - a zombie, a gravestone, the fog - comes out brighter, which is what a
     * torch does and what the flat grey wedge it replaces did not.
     */
    private void drawBeamLight(Canvas c, float groundY, float strength) {
        if (lampX < 0 || beamShader == null) {
            return;
        }
        double a = beamAngle();
        float flicker = 0.9f + 0.06f * (float) Math.sin(sessionSeconds * 17) + 0.04f * (float) Math.sin(sessionSeconds * 5.3);
        int alpha = (int) Math.min(255f, 255f * flicker * strength);
        c.save();
        // The road is seen side-on: the light stops at its near edge instead of spilling down the screen.
        c.clipRect(-dp(80f), -dp(80f), sceneW + dp(80f), groundY + dp(34f));
        c.translate(lampX, lampY);
        c.rotate((float) Math.toDegrees(a));
        light.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        light.setAlpha(alpha);
        light.setShader(beamShader);
        c.drawPath(beamCone, light);
        light.setShader(beamCoreShader);
        c.drawPath(beamCore, light);
        light.setShader(null);
        float spreadT = (float) Math.tan(BEAM_SPREAD);
        light.setColor(0xFFFFF0C8);
        for (int i = 0; i < 12; i++) {
            float u = (float) ((hash(i * 17 + 3) + sessionSeconds * (0.05 + 0.04 * hash(i * 5 + 1))) % 1.0);
            float mx = beamLen * (0.1f + 0.85f * u);
            float my = mx * spreadT * (hash(i * 11 + 7) * 2f - 1f) * 0.85f
                    + (float) Math.sin(sessionSeconds * 1.3 + i) * dp(6f);
            light.setAlpha((int) (alpha * 0.55f * (1f - u)));
            c.drawCircle(mx, my, dp(1.8f), light);
        }
        c.restore();
        // The pool of light on the road, along the lower edge of the cone.
        double r = a + (Math.cos(a) >= 0 ? 0.6 : -0.6) * BEAM_SPREAD;
        double sinR = Math.sin(r);
        if (sinR > 0.05) {
            float dist = (float) Math.min(beamLen * 0.85, (groundY - lampY) / sinR);
            float px = lampX + (float) Math.cos(r) * dist;
            float k = 1f - 0.6f * dist / beamLen;
            light.setColor(0xFFFFFFFF);
            light.setAlpha((int) (alpha * 0.6f * k));
            light.setShader(poolShader);
            c.save();
            c.translate(px, groundY + dp(8f));
            c.scale(1.9f, 0.24f);
            c.drawCircle(0, 0, poolR, light);
            c.restore();
            light.setShader(null);
        }
    }

    /* ---------- 3.25.0 graphics: the scene cache ---------- */

    /** Builds every cached shape and gradient for this view size. Runs once, not per frame. */
    private void ensureScene(float w, float h) {
        if (w == sceneW && h == sceneH) {
            return;
        }
        sceneW = w;
        sceneH = h;
        float groundY = h * 0.70f;
        Shader.TileMode clamp = Shader.TileMode.CLAMP;

        // Stars, kept clear of the moon's halo.
        float mx = w * 0.80f;
        float my = h * 0.20f;
        float keep = dp(160f);
        int n = 0;
        for (int k = 0; n < STARS && k < STARS * 4; k++) {
            float sx = hash(k * 3 + 1) * w;
            float sy = hash(k * 3 + 2) * groundY * 0.72f;
            float dx = sx - mx;
            float dy = sy - my;
            if (dx * dx + dy * dy < keep * keep) {
                continue;
            }
            float r = hash(k * 3 + 3);
            starX[n] = sx;
            starY[n] = sy;
            starR[n] = dp(1.0f) + r * r * dp(1.8f);
            starA[n] = 90 + (int) (hash(k * 7 + 5) * 150);
            n++;
        }
        starCount = n;

        fogR = dp(100f);
        fogShader = new RadialGradient(0, 0, fogR, new int[] {0xFFC9D4E6, 0x8CC9D4E6, 0x00C9D4E6},
                new float[] {0f, 0.45f, 1f}, clamp);
        float mr = dp(30f);
        moonShader = new RadialGradient(mr * 0.35f, -mr * 0.35f, mr * 1.5f,
                new int[] {0xFFFFFFFF, 0xFFE6ECF4, 0xFFA2B0C4}, new float[] {0f, 0.5f, 1f}, clamp);
        groundShader = new LinearGradient(0, groundY, 0, h, new int[] {0xFF1D2B20, 0xFF121B15, 0xFF060A08},
                new float[] {0f, 0.35f, 1f}, clamp);
        pathShader = new LinearGradient(0, groundY - dp(3f), 0, groundY + dp(32f), 0xFF3B3B37, 0xFF1D1F1C, clamp);
        bottomShader = new LinearGradient(0, h - dp(78f), 0, h, 0x00060B12, 0xD0060B12, clamp);
        glossShader = new LinearGradient(0, 0, 0, GLOSS_UNIT,
                new int[] {0x30FFFFFF, 0x0AFFFFFF, 0x00000000, 0x2E000000},
                new float[] {0f, 0.48f, 0.52f, 1f}, clamp);

        beamLen = dp(560f);
        float spreadT = (float) Math.tan(BEAM_SPREAD);
        beamCone.rewind();
        beamCone.moveTo(0, -dp(5f));
        beamCone.lineTo(beamLen, -beamLen * spreadT);
        beamCone.lineTo(beamLen, beamLen * spreadT);
        beamCone.lineTo(0, dp(5f));
        beamCone.close();
        beamCore.rewind();
        beamCore.moveTo(0, -dp(3f));
        beamCore.lineTo(beamLen * 0.8f, -beamLen * 0.8f * spreadT * 0.42f);
        beamCore.lineTo(beamLen * 0.8f, beamLen * 0.8f * spreadT * 0.42f);
        beamCore.lineTo(0, dp(3f));
        beamCore.close();
        beamShader = new LinearGradient(0, 0, beamLen, 0, new int[] {0x5CFFEBB8, 0x26FFE2A6, 0x00FFE2A6},
                new float[] {0f, 0.4f, 1f}, clamp);
        beamCoreShader = new LinearGradient(0, 0, beamLen * 0.8f, 0, 0x5AFFF4D6, 0x00FFF0C8, clamp);
        poolR = dp(110f);
        poolShader = new RadialGradient(0, 0, poolR, new int[] {0x99FFE6B0, 0x40FFE6B0, 0x00FFE6B0},
                new float[] {0f, 0.5f, 1f}, clamp);

        buildTrees();
        buildStones();
        buildTorsos();
        int[] skins = {0xFFF1C27D, 0xFFE8B98A, 0xFF8DB386, 0xFFB4C85A, 0xFF5E8F66};
        for (int k = 0; k < 5; k++) {
            float r = headUnits(k) * figureUnit(k);
            headShader[k] = new RadialGradient(0.35f * r, -0.4f * r, 1.45f * r,
                    new int[] {tone(skins[k], 1.35f), skins[k], tone(skins[k], 0.5f)},
                    new float[] {0f, 0.45f, 1f}, clamp);
        }
    }

    /** One figure unit in pixels: the brute is half as big again, survivors a little smaller. */
    private float figureUnit(int kind) {
        return dp(FIGURE_SCALE) * (kind == K_BRUTE ? 1.5f : kind == K_SURVIVOR ? 0.9f : 1f);
    }

    private static float headUnits(int kind) {
        return kind == K_BRUTE ? 4.8f : kind == K_RUNNER ? 5.2f : kind == K_WALKER ? 5.8f : 5.6f;
    }

    private static float torsoUnits(int kind) {
        return kind == K_BRUTE ? 17.8f : kind == K_WALKER ? 16.2f : kind == K_RUNNER ? 15.5f : 15.6f;
    }

    /** Dead trees: a tapered trunk that forks into gnarled branches, deterministic per variant. */
    private void buildTrees() {
        float tall = dp(150f);
        for (int k = 0; k < 3; k++) {
            Path p = new Path();
            p.moveTo(-0.11f * tall, 0);
            p.lineTo(-0.035f * tall, -0.10f * tall);
            p.lineTo(0.035f * tall, -0.10f * tall);
            p.lineTo(0.11f * tall, 0);
            p.close();
            branch(p, 0, 0, (hash(k * 31 + 1) - 0.5f) * 0.16f, tall * (0.36f + 0.08f * hash(k * 31 + 2)),
                    0.085f * tall, 4, k * 97 + 11);
            treeShape[k] = p;
        }
    }

    /** One limb as a tapered quad, then two or three children off its tip. Same winding throughout. */
    private void branch(Path p, float x, float y, float ang, float len, float wid, int depth, int seed) {
        float ex = x + (float) Math.sin(ang) * len;
        float ey = y - (float) Math.cos(ang) * len;
        float nx = (float) Math.cos(ang);
        float ny = (float) Math.sin(ang);
        float w0 = wid * 0.5f;
        float w1 = wid * 0.32f;
        p.moveTo(x - nx * w0, y - ny * w0);
        p.lineTo(ex - nx * w1, ey - ny * w1);
        p.lineTo(ex + nx * w1, ey + ny * w1);
        p.lineTo(x + nx * w0, y + ny * w0);
        p.close();
        if (depth == 0) {
            return;
        }
        int kids = hash(seed) > 0.6f ? 3 : 2;
        for (int i = 0; i < kids; i++) {
            float spread = 0.42f + hash(seed * 5 + i) * 0.5f;
            float a = ang + (i == 0 ? -spread : i == 1 ? spread : (hash(seed * 9 + i) - 0.5f) * 0.4f);
            a = Math.max(-1.35f, Math.min(1.35f, a));   // never past horizontal: dead trees reach, they do not droop
            branch(p, ex, ey, a, len * (0.55f + hash(seed * 7 + i) * 0.25f), wid * 0.62f, depth - 1,
                    seed * 4 + i + 1);
        }
    }

    /** Headstone, cross, obelisk and slab, base centred on the origin. */
    private void buildStones() {
        float u = dp(1f);
        Path p0 = new Path();
        p0.moveTo(-15 * u, 0);
        p0.lineTo(-15 * u, -30 * u);
        p0.quadTo(-15 * u, -45 * u, 0, -45 * u);
        p0.quadTo(15 * u, -45 * u, 15 * u, -30 * u);
        p0.lineTo(15 * u, 0);
        p0.close();
        Path p1 = new Path();
        p1.addRect(-4 * u, -58 * u, 4 * u, -4 * u, Path.Direction.CW);
        p1.addRect(-15 * u, -46 * u, 15 * u, -38 * u, Path.Direction.CW);
        p1.addRect(-11 * u, -7 * u, 11 * u, 0, Path.Direction.CW);
        Path p2 = new Path();
        p2.moveTo(-9 * u, -6 * u);
        p2.lineTo(-7 * u, -52 * u);
        p2.lineTo(0, -63 * u);
        p2.lineTo(7 * u, -52 * u);
        p2.lineTo(9 * u, -6 * u);
        p2.close();
        p2.addRect(-13 * u, -7 * u, 13 * u, 0, Path.Direction.CW);
        Path p3 = new Path();
        p3.moveTo(-21 * u, 0);
        p3.lineTo(-21 * u, -22 * u);
        p3.quadTo(-21 * u, -29 * u, -13 * u, -29 * u);
        p3.quadTo(0, -37 * u, 13 * u, -29 * u);
        p3.quadTo(21 * u, -29 * u, 21 * u, -22 * u);
        p3.lineTo(21 * u, 0);
        p3.close();
        stoneShape[0] = p0;
        stoneShape[1] = p1;
        stoneShape[2] = p2;
        stoneShape[3] = p3;
    }

    /** Torsos in hip-local coordinates (up is -y, the direction of travel +x), per figure kind. */
    private void buildTorsos() {
        for (int k = 0; k < 5; k++) {
            float s = figureUnit(k);
            Path p = new Path();
            if (k == K_YOU || k == K_SURVIVOR) {
                p.moveTo(-4.4f * s, 0.5f * s);
                p.lineTo(-5.0f * s, -12f * s);
                p.quadTo(-5.2f * s, -15.5f * s, -1.5f * s, -15.6f * s);
                p.lineTo(2.5f * s, -15.6f * s);
                p.quadTo(5.8f * s, -15.4f * s, 5.4f * s, -12f * s);
                p.lineTo(4.6f * s, 0.5f * s);
            } else if (k == K_RUNNER) {
                p.moveTo(-3.6f * s, 1f * s);
                p.lineTo(-2f * s, -0.6f * s);
                p.lineTo(-0.5f * s, 1.6f * s);
                p.lineTo(1.2f * s, -0.4f * s);
                p.lineTo(3.8f * s, 1.2f * s);
                p.lineTo(4.4f * s, -12f * s);
                p.quadTo(4.6f * s, -15.4f * s, 1.5f * s, -15.5f * s);
                p.lineTo(-2f * s, -15.5f * s);
                p.quadTo(-4.8f * s, -15f * s, -4.6f * s, -11.5f * s);
            } else if (k == K_BRUTE) {
                p.moveTo(-6.2f * s, 1.5f * s);
                p.lineTo(-4f * s, -0.5f * s);
                p.lineTo(-2f * s, 2f * s);
                p.lineTo(0.5f * s, -0.2f * s);
                p.lineTo(3f * s, 2.2f * s);
                p.lineTo(5f * s, 0f);
                p.lineTo(6.8f * s, 1.6f * s);
                p.lineTo(9.6f * s, -11f * s);
                p.quadTo(10.6f * s, -17.4f * s, 4.5f * s, -17.8f * s);
                p.lineTo(-4f * s, -17.8f * s);
                p.quadTo(-10.6f * s, -17.2f * s, -9f * s, -10.5f * s);
            } else {
                // Walker: a hunched back and a hem torn into rags.
                p.moveTo(-5.4f * s, 1.2f * s);
                p.lineTo(-3.4f * s, -0.8f * s);
                p.lineTo(-1.8f * s, 2.2f * s);
                p.lineTo(0.2f * s, -0.4f * s);
                p.lineTo(2.2f * s, 2.4f * s);
                p.lineTo(4f * s, -0.2f * s);
                p.lineTo(5.6f * s, 1.6f * s);
                p.lineTo(5.6f * s, -12f * s);
                p.quadTo(5.2f * s, -16.2f * s, 1f * s, -16.2f * s);
                p.lineTo(-2.5f * s, -16.2f * s);
                p.quadTo(-7.4f * s, -15.6f * s, -6.6f * s, -10.5f * s);
            }
            p.close();
            torsoShape[k] = p;
        }
    }

    /* ---------- 3.25.0 graphics: sky and scenery ---------- */

    /** Night sky: deep at the top, a moonlit haze at the horizon, stars, the moon and drifting cloud. */
    private void drawSky(Canvas c, float w, float h, float groundY, float danger, float dq) {
        int skyTop = blend(0xFF070D1A, 0xFF2E0709, dq);
        if (skyShader == null || skyTop != skyShaderTop || h != skyShaderH) {
            int mid = blend(0xFF111B30, 0xFF3A0F12, dq);
            int horizon = blend(0xFF2B3C5E, 0xFF5E2220, dq);
            skyShader = new LinearGradient(0, 0, 0, groundY, new int[] {skyTop, mid, horizon},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            skyShaderTop = skyTop;
            skyShaderH = h;
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Stars, fading as the sky reddens.
        float t = (float) sessionSeconds;
        float starFade = 1f - 0.7f * dq;
        paint.setColor(0xFFDCE6F5);
        for (int i = 0; i < starCount; i++) {
            float tw = 0.55f + 0.45f * (float) Math.sin(t * (1.1f + (i % 5) * 0.37f) + i * 2.17f);
            paint.setAlpha((int) (starA[i] * tw * starFade));
            c.drawCircle(starX[i], starY[i], starR[i], paint);
        }
        paint.setAlpha(255);

        // The moon: a wide halo, a shaded disc with craters, and a blood tint as the horde closes.
        float mx = w * 0.80f;
        float my = h * 0.20f;
        float mr = dp(30f);
        int halo = blend(0xFFAFC4E8, 0xFFFF6A4D, dq) & 0x00FFFFFF;
        Fx.glow(c, mx, my, dp(230f), 0x20000000 | halo);
        Fx.glow(c, mx, my, dp(95f), 0x50000000 | halo);
        c.save();
        c.translate(mx, my);
        shade.setColor(0xFFFFFFFF);
        shade.setShader(moonShader);
        c.drawCircle(0, 0, mr, shade);
        shade.setShader(null);
        paint.setColor(0x2A304866);
        c.drawCircle(-mr * 0.30f, -mr * 0.15f, mr * 0.22f, paint);
        c.drawCircle(mr * 0.25f, mr * 0.30f, mr * 0.16f, paint);
        c.drawCircle(-mr * 0.05f, mr * 0.47f, mr * 0.12f, paint);
        c.drawCircle(mr * 0.38f, -mr * 0.38f, mr * 0.10f, paint);
        if (danger > 0.02f) {
            paint.setColor(0xFFFF5A3A);
            paint.setAlpha((int) (danger * 130));
            c.drawCircle(0, 0, mr, paint);
            paint.setAlpha(255);
        }
        c.restore();

        // Thin cloud drifting across, now and then over the moon itself.
        shade.setColor(0xFFFFFFFF);
        shade.setShader(fogShader);
        float span = w + dp(900f);
        for (int i = 0; i < 4; i++) {
            float cx = span - (float) ((hash(i * 7 + 400) * span + sessionSeconds * dp(5f + i * 2.5f)) % span)
                    - dp(450f);
            float cy = h * (0.08f + 0.22f * hash(i * 5 + 401));
            shade.setAlpha(40 + (int) (30 * hash(i * 3 + 402)));
            c.save();
            c.translate(cx, cy);
            c.scale(3.6f + hash(i * 11 + 403) * 1.6f, 0.40f);
            c.drawCircle(0, 0, fogR, shade);
            c.restore();
        }
        shade.setShader(null);
    }

    private float ridgeY(float x, double scroll, float ppm, float groundY) {
        float wx = (float) ((x + scroll * ppm * 0.06) / dp(260f));
        return groundY - dp(100f) - (0.5f + 0.5f * (float) Math.sin(wx)) * dp(55f)
                - (0.5f + 0.5f * (float) Math.sin(wx * 2.3f + 1.3f)) * dp(20f);
    }

    /**
     * Everything between the sky and the road, back to front, each layer slower and hazier than the
     * one in front of it: bats, a far ridge with a church, hills with eyes in them, a far wood, the
     * graveyard, a fog bank, near trees, the cemetery railing, the road, and the field in front.
     */
    private void drawBackdrop(Canvas c, float w, float h, float groundY, float ppm, double scroll, float dt) {
        float t = (float) sessionSeconds;

        // Bats.
        paint.setColor(0xFF04060B);
        for (int i = 0; i < batX.length; i++) {
            batX[i] -= batSpeed[i] * dt;
            if (batX[i] < -0.05f) {
                batX[i] = 1.05f;
                batY[i] = 0.10f + (float) Math.random() * 0.3f;
            }
            float bx = batX[i] * w;
            float by = batY[i] * h + (float) Math.sin(t * 2 + i) * dp(10f);
            float flap = (float) Math.sin(t * 14 + i * 2) * dp(8f);
            float sp = dp(15f);
            batPath.rewind();
            batPath.moveTo(bx, by - dp(2f));
            batPath.quadTo(bx - sp * 0.5f, by - flap - dp(5f), bx - sp, by - flap);
            batPath.lineTo(bx - sp * 0.72f, by - flap * 0.4f + dp(1f));
            batPath.lineTo(bx - sp * 0.45f, by - flap * 0.25f + dp(3f));
            batPath.lineTo(bx - sp * 0.2f, by + dp(1.5f));
            batPath.lineTo(bx, by + dp(3f));
            batPath.lineTo(bx + sp * 0.2f, by + dp(1.5f));
            batPath.lineTo(bx + sp * 0.45f, by - flap * 0.25f + dp(3f));
            batPath.lineTo(bx + sp * 0.72f, by - flap * 0.4f + dp(1f));
            batPath.lineTo(bx + sp, by - flap);
            batPath.quadTo(bx + sp * 0.5f, by - flap - dp(5f), bx, by - dp(2f));
            batPath.close();
            c.drawPath(batPath, paint);
            c.drawCircle(bx, by, dp(3.2f), paint);
        }

        // Far ridge, palest: it sits deepest in the haze.
        float step = dp(40f);
        path.rewind();
        path.moveTo(-step, groundY);
        for (float x = -step; x <= w + step; x += step) {
            path.lineTo(x, ridgeY(x, scroll, ppm, groundY));
        }
        path.lineTo(w + step, groundY);
        path.close();
        paint.setColor(0xFF19233A);
        c.drawPath(path, paint);
        float churchGap = dp(1900f);
        float cOff = (float) ((scroll * ppm * 0.06) % churchGap);
        for (float cx = dp(700f) - cOff; cx < w + dp(100f); cx += churchGap) {
            if (cx > -dp(100f)) {
                drawChurch(c, cx, ridgeY(cx, scroll, ppm, groundY) + dp(10f));
            }
        }

        // Nearer hills.
        path.rewind();
        path.moveTo(-step, groundY);
        for (float x = -step; x <= w + step; x += step) {
            float wx = (float) ((x + scroll * ppm * 0.15) / dp(160f));
            path.lineTo(x, groundY - dp(40f) - (float) Math.abs(Math.sin(wx) * dp(50f)));
        }
        path.lineTo(w + step, groundY);
        path.close();
        paint.setColor(0xFF111A2A);
        c.drawPath(path, paint);

        // Glowing eyes blinking in the dark between the hills.
        for (int i = 0; i < 6; i++) {
            float ex = (float) (((i * 311 + 90) - scroll * ppm * 0.25) % (w + dp(200f)));
            if (ex < 0) {
                ex += w + dp(200f);
            }
            boolean open = ((int) (t * 1.3 + i * 0.7)) % 4 != 0;
            if (open) {
                float ey = groundY - dp(28f) - (i % 3) * dp(12f);
                int col = i % 2 == 0 ? 0xFFFFD23A : 0xFFFF4D4D;
                Fx.glow(c, ex + dp(4f), ey, dp(12f), 0x60000000 | (col & 0x00FFFFFF));
                paint.setColor(col);
                c.drawCircle(ex, ey, dp(2.2f), paint);
                c.drawCircle(ex + dp(8f), ey, dp(2.2f), paint);
            }
        }

        // A far wood, hazy.
        drawTreeRow(c, w, groundY - dp(2f), scroll * ppm * 0.25, dp(240f), 0.55f, 0xFF121A2A, 101, false);

        // The graveyard: stones of four shapes, leaning, lit on the moon's side, a candle here and there.
        float sGap = dp(118f);
        double so = scroll * ppm * 0.3;
        float sOff = (float) (so % sGap);
        int s0 = (int) Math.floor(so / sGap);
        for (int j = -1; ; j++) {
            float x = j * sGap - sOff;
            if (x > w + sGap) {
                break;
            }
            int k = s0 + j;
            float sx = x + (hash(k * 17 + 5) - 0.5f) * dp(50f);
            float sc = 0.85f + 0.4f * hash(k * 19 + 3);
            float tilt = (hash(k * 23 + 9) - 0.5f) * 12f;
            int type = Math.min(3, (int) (hash(k * 29 + 1) * 4));
            float base = groundY - dp(3f) + hash(k * 31) * dp(4f);
            paint.setColor(0x55000000);
            c.drawOval(sx - dp(20f) * sc, base - dp(3f), sx + dp(24f) * sc, base + dp(3f), paint);
            c.save();
            c.translate(sx, base);
            c.rotate(tilt);
            c.scale(sc, sc);
            c.translate(dp(2.4f), -dp(1.4f));
            paint.setColor(0xFF4E5B72);
            c.drawPath(stoneShape[type], paint);
            c.translate(-dp(2.4f), dp(1.4f));
            paint.setColor(0xFF262F3E);
            c.drawPath(stoneShape[type], paint);
            c.restore();
            if (hash(k * 37 + 2) < 0.16f) {
                float fx = sx + dp(22f) * sc;
                float flick = 0.7f + 0.3f * (float) Math.sin(t * 9 + k);
                Fx.glow(c, fx, base - dp(12f), dp(24f), ((int) (0x70 * flick) << 24) | 0xFFB050);
                paint.setColor(0xFFD8CFB0);
                c.drawRect(fx - dp(1.6f), base - dp(9f), fx + dp(1.6f), base, paint);
                paint.setColor(0xFFFFD27A);
                c.drawCircle(fx, base - dp(11.5f) - flick * dp(0.8f), dp(1.8f), paint);
            }
        }

        // A fog bank between the graves and the near trees.
        fogPuffs(c, w, groundY - dp(26f), 6, dp(26f), 3.2f, 0.52f, 58, dp(10f), scroll, ppm, 0.35f, 300);

        // Near trees, darkest, swaying a little.
        drawTreeRow(c, w, groundY + dp(2f), scroll * ppm * 0.45, dp(380f), 1.05f, 0xFF070A11, 211, true);

        // The cemetery railing: bars, two rails, posts with ball finials, a gap where it has fallen.
        float bar = dp(15f);
        double fo = scroll * ppm * 0.6;
        float fOff = (float) (fo % bar);
        int f0 = (int) Math.floor(fo / bar);
        float fb = groundY - dp(1f);
        float ft = groundY - dp(36f);
        int nl = 0;
        int j0 = 0;
        for (float x = -fOff; x < w + bar && nl <= fenceLines.length - 4; x += bar, j0++) {
            if (hash((f0 + j0) / 9 * 13 + 5) < 0.12f) {
                continue;
            }
            fenceLines[nl++] = x;
            fenceLines[nl++] = fb;
            fenceLines[nl++] = x;
            fenceLines[nl++] = ft;
        }
        limb.setStrokeWidth(dp(2.2f));
        limb.setColor(0xFF0A0E16);
        c.drawLines(fenceLines, 0, nl, limb);
        paint.setColor(0xFF0A0E16);
        c.drawRect(-dp(20f), ft + dp(5f), w + dp(20f), ft + dp(8f), paint);
        c.drawRect(-dp(20f), fb - dp(9f), w + dp(20f), fb - dp(6f), paint);
        paint.setColor(0x4A5A6E8E);
        c.drawRect(-dp(20f), ft + dp(5f), w + dp(20f), ft + dp(6f), paint);
        float pGap = bar * 7;
        float pOff = (float) (fo % pGap);
        for (float x = -pOff; x < w + pGap; x += pGap) {
            paint.setColor(0xFF0A0E16);
            c.drawRect(x - dp(3.5f), ft - dp(4f), x + dp(3.5f), fb, paint);
            c.drawCircle(x, ft - dp(7f), dp(4.5f), paint);
            paint.setColor(0x665A6E8E);
            c.drawRect(x + dp(2f), ft - dp(4f), x + dp(3.5f), fb, paint);
            c.drawCircle(x + dp(1.5f), ft - dp(8.5f), dp(1.6f), paint);
        }

        // The ground, receding into the dark, and the road you run on.
        shade.setColor(0xFFFFFFFF);
        shade.setShader(groundShader);
        c.drawRect(-dp(20f), groundY, w + dp(20f), h + dp(20f), shade);
        shade.setShader(pathShader);
        c.drawRect(-dp(20f), groundY - dp(2f), w + dp(20f), groundY + dp(30f), shade);
        shade.setShader(null);
        paint.setColor(0x5A8FA6C8);
        c.drawRect(-dp(20f), groundY - dp(2f), w + dp(20f), groundY - dp(0.5f), paint);
        paint.setColor(0x66000000);
        c.drawRect(-dp(20f), groundY + dp(30f), w + dp(20f), groundY + dp(33f), paint);

        // Pebbles and tufts on the road at your own speed: the one layer that moves exactly with you.
        float pg = dp(44f);
        double po = scroll * ppm;
        float poOff = (float) (po % pg);
        int p0 = (int) Math.floor(po / pg);
        limb.setStrokeWidth(dp(2f));
        limb.setColor(0xFF33452F);
        for (int j = -1; ; j++) {
            float x = j * pg - poOff;
            if (x > w + pg) {
                break;
            }
            int k = p0 + j;
            float hx = hash(k * 41 + 7);
            float px = x + hx * pg * 0.8f;
            float py = groundY + dp(5f) + hash(k * 43 + 1) * dp(22f);
            if (hx < 0.55f) {
                float r = dp(2.5f) + hash(k * 47) * dp(4f);
                paint.setColor(0xFF242522);
                c.drawOval(px - r * 1.4f, py - r * 0.8f, px + r * 1.4f, py + r * 0.6f, paint);
                paint.setColor(0xFF575A5C);
                c.drawOval(px - r * 0.5f, py - r * 0.8f, px + r * 1.1f, py - r * 0.15f, paint);
            } else {
                c.drawLine(px, py, px - dp(5f), py - dp(10f), limb);
                c.drawLine(px, py, px + dp(1f), py - dp(13f), limb);
                c.drawLine(px, py, px + dp(6f), py - dp(9f), limb);
            }
        }

        // The field in front of the road, fastest of all: grass clumps, the odd grave, a moonlit puddle.
        float fg = dp(170f);
        double fgo = scroll * ppm * 1.5;
        float fgOff = (float) (fgo % fg);
        int g0 = (int) Math.floor(fgo / fg);
        for (int j = -1; ; j++) {
            float x = j * fg - fgOff;
            if (x > w + fg) {
                break;
            }
            int k = g0 + j;
            float hk = hash(k * 53 + 11);
            float gx = x + hash(k * 59 + 3) * fg * 0.7f;
            float gy = groundY + dp(62f) + hash(k * 61 + 5) * dp(55f);
            if (hk < 0.22f) {
                int type = Math.min(3, (int) (hash(k * 67) * 4));
                float sc = 1.4f + 0.3f * hash(k * 71);
                c.save();
                c.translate(gx, gy);
                c.rotate((hash(k * 73) - 0.5f) * 16f);
                c.scale(sc, sc);
                c.translate(dp(1.6f), -dp(1f));
                paint.setColor(0xFF1E2A2C);
                c.drawPath(stoneShape[type], paint);
                c.translate(-dp(1.6f), dp(1f));
                paint.setColor(0xFF0A100D);
                c.drawPath(stoneShape[type], paint);
                c.restore();
            } else if (hk < 0.34f) {
                paint.setColor(0x26A9C1E6);
                c.drawOval(gx - dp(46f), gy - dp(6f), gx + dp(46f), gy + dp(6f), paint);
                paint.setColor(0x40DCE6F5);
                c.drawOval(gx - dp(14f), gy - dp(1.5f), gx + dp(22f), gy + dp(1.5f), paint);
            } else {
                float sway = (float) Math.sin(t * 1.2 + k) * dp(4f);
                limb.setStrokeWidth(dp(3f));
                limb.setColor(0xFF0C150F);
                for (int b = 0; b < 6; b++) {
                    float bxo = (b - 2.5f) * dp(5f);
                    float len = dp(22f) + hash(k * 7 + b) * dp(24f);
                    c.drawLine(gx + bxo, gy, gx + bxo * 1.8f + sway, gy - len, limb);
                }
            }
        }
    }

    /** A row of dead trees from the cached shapes, mirrored and scaled per tree, with gaps. */
    private void drawTreeRow(Canvas c, float w, float base, double offsetPx, float gap, float scale, int colour,
                             int seed, boolean sway) {
        float off = (float) (offsetPx % gap);
        int k0 = (int) Math.floor(offsetPx / gap);
        paint.setColor(colour);
        for (int j = -1; ; j++) {
            float x = j * gap - off;
            if (x > w + gap) {
                break;
            }
            int k = k0 + j;
            if (hash(k * 7 + seed) < 0.25f) {
                continue;
            }
            float jx = x + (hash(k * 13 + seed) - 0.5f) * gap * 0.6f;
            float sc = scale * (0.8f + 0.45f * hash(k * 5 + seed));
            Path shape = treeShape[Math.min(2, (int) (hash(k * 3 + seed) * 3))];
            c.save();
            c.translate(jx, base);
            if (sway) {
                c.rotate((float) Math.sin(sessionSeconds * 0.7 + k) * 0.8f);
            }
            c.scale(hash(k * 11 + seed) < 0.5f ? -sc : sc, sc);
            c.drawPath(shape, paint);
            c.restore();
        }
    }

    /** A ruined church on the far ridge, one window still lit. */
    private void drawChurch(Canvas c, float x, float base) {
        paint.setColor(0xFF19233A);
        c.drawRect(x - dp(34f), base - dp(30f), x + dp(20f), base, paint);
        path.rewind();
        path.moveTo(x - dp(37f), base - dp(30f));
        path.lineTo(x - dp(8f), base - dp(45f));
        path.lineTo(x + dp(22f), base - dp(30f));
        path.close();
        c.drawPath(path, paint);
        c.drawRect(x + dp(14f), base - dp(62f), x + dp(30f), base, paint);
        path.rewind();
        path.moveTo(x + dp(12f), base - dp(62f));
        path.lineTo(x + dp(22f), base - dp(94f));
        path.lineTo(x + dp(32f), base - dp(62f));
        path.close();
        c.drawPath(path, paint);
        c.drawRect(x + dp(21f), base - dp(106f), x + dp(23f), base - dp(93f), paint);
        c.drawRect(x + dp(17.5f), base - dp(102f), x + dp(26.5f), base - dp(100f), paint);
        float flick = 0.75f + 0.25f * (float) Math.sin(sessionSeconds * 3.1 + x * 0.01);
        Fx.glow(c, x + dp(22f), base - dp(48f), dp(18f), ((int) (0x90 * flick) << 24) | 0xFFC060);
        paint.setColor(0xFFE8B060);
        c.drawRect(x + dp(20f), base - dp(53f), x + dp(24f), base - dp(44f), paint);
    }

    /**
     * Soft fog: one cached radial gradient drawn as stretched ellipses, drifting with the wind and
     * scrolling with the ground at {@code par}. Breathes in and out so the bank is never still.
     */
    private void fogPuffs(Canvas c, float w, float baseY, int count, float spanY, float sx, float sy, int alpha,
                          float drift, double scroll, float ppm, float par, int seed) {
        if (fogShader == null) {
            return;
        }
        float wrap = w + fogR * sx * 2.4f;
        shade.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        shade.setShader(fogShader);
        for (int i = 0; i < count; i++) {
            float x = (float) ((hash(seed + i * 13) * wrap + scroll * ppm * par
                    + sessionSeconds * drift * (0.7 + 0.6 * hash(seed + i * 7))) % wrap);
            x = wrap - x - fogR * sx * 1.2f;
            float y = baseY + (hash(seed + i * 5) - 0.5f) * spanY + (float) Math.sin(sessionSeconds * 0.4 + i) * dp(4f);
            float k = 0.75f + 0.5f * hash(seed + i * 3);
            shade.setAlpha((int) (alpha * (0.7f + 0.3f * (float) Math.sin(sessionSeconds * 0.3 + i * 1.7))));
            c.save();
            c.translate(x, y);
            c.scale(sx * k, sy * k);
            c.drawCircle(0, 0, fogR, shade);
            c.restore();
        }
        shade.setShader(null);
    }

    /* ---------- 3.25.0 graphics: helpers ---------- */

    /** Deterministic 0..1 from an integer: scenery detail that never changes and never allocates. */
    private static float hash(int k) {
        int x = k * 0x27D4EB2D + 0x165667B1;
        x ^= x >>> 15;
        x *= 0x85EBCA6B;
        x ^= x >>> 13;
        x *= 0xC2B2AE35;
        x ^= x >>> 16;
        return (x & 0xFFFFFF) / 16777216f;
    }

    /** Darker below 1, toward white above it; keeps the alpha. */
    private static int tone(int col, float f) {
        int a = col >>> 24;
        int r = (col >> 16) & 0xFF;
        int g = (col >> 8) & 0xFF;
        int b = col & 0xFF;
        if (f <= 1f) {
            r = (int) (r * f);
            g = (int) (g * f);
            b = (int) (b * f);
        } else {
            float k = Math.min(1f, f - 1f);
            r = (int) (r + (255 - r) * k);
            g = (int) (g + (255 - g) * k);
            b = (int) (b + (255 - b) * k);
        }
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * A HUD panel: rounded fill, a vertical gloss (one cached gradient, stretched to the panel by the
     * canvas so the corners stay round), a lighter top edge and an optional border.
     */
    private void panel(Canvas c, float l, float t, float r, float b, float rad, int fill, int edge) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        float hgt = b - t;
        if (hgt > 1f && glossShader != null) {
            float k = hgt / GLOSS_UNIT;
            c.save();
            c.translate(0, t);
            c.scale(1f, k);
            shade.setColor(0xFFFFFFFF);
            shade.setShader(glossShader);
            c.drawRoundRect(l, 0, r, GLOSS_UNIT, rad, rad / k, shade);
            shade.setShader(null);
            c.restore();
        }
        if (r - l > rad * 2f) {
            paint.setColor(0x40FFFFFF);
            c.drawRect(l + rad, t + dp(1f), r - rad, t + dp(2f), paint);
        }
        if (edge != 0) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(edge);
            c.drawRoundRect(l, t, r, b, rad, rad, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }


    /**
     * A jointed figure: thighs, shins, upper arms and forearms as round-capped strokes, a cached torso
     * silhouette, a shaded head. The legs are a real gait - each thigh swings on {@code phaseIn}, the
     * knee folds through the swing and straightens through the stance - and the hip is set so the
     * lower foot is on the road, which gives the run its bounce for free.
     *
     * <p>Walkers shamble: stiff knees, a dragged far leg, a hunched lurch and both arms out in front.
     * Runners are pitched almost flat with their arms flailing. The brute is half as big again, with
     * arms that hang to its knees, fists overhead when it winds up and swept back when it charges.
     * {@code wave}: a survivor waving for help, the brute roaring, or a walker pounding on the wall.
     * Far limbs are drawn darker than near ones; the moon side of the torso carries a highlight.
     */
    private void drawFigure(Canvas c, float x, float groundY, int color, float speed, float phaseIn,
                            int kind, boolean wave) {
        float s = figureUnit(kind);
        boolean zombie = kind >= K_WALKER;
        boolean moving = speed > 0.2f;
        boolean charge = kind == K_BRUTE && bruteState == 2;
        float t = (float) sessionSeconds;
        float lam = kind == K_YOU ? (float) limp : 0f;
        final float pi = (float) Math.PI;

        // Gait, in figure units; angles in radians from straight down, forward positive.
        float l1;
        float l2;
        float legW;
        float thighA;
        float flexBase;
        float flexSwing;
        float lean;
        float armA;
        float upper;
        float fore;
        float armW;
        float farAmp;
        if (kind == K_WALKER) {
            l1 = 10f;
            l2 = 10f;
            legW = 4.4f;
            thighA = moving ? 0.34f : 0.05f;
            flexBase = 0.08f;
            flexSwing = moving ? 0.35f : 0f;
            lean = 0.42f + 0.07f * (float) Math.sin(moving ? phaseIn : t * 1.5f + phaseIn);
            armA = 0f;
            upper = 8.5f;
            fore = 8f;
            armW = 3.4f;
            farAmp = 0.5f;      // the leg it drags
        } else if (kind == K_RUNNER) {
            l1 = 10.5f;
            l2 = 10.5f;
            legW = 3.8f;
            thighA = moving ? 0.85f : 0.05f;
            flexBase = 0.2f;
            flexSwing = moving ? 1.6f : 0f;
            lean = moving ? 0.72f : 0.3f;
            armA = moving ? 1.1f : 0.1f;
            upper = 8f;
            fore = 7.5f;
            armW = 3f;
            farAmp = 1f;
        } else if (kind == K_BRUTE) {
            l1 = 8.5f;
            l2 = 8.5f;
            legW = 5.6f;
            thighA = moving ? (charge ? 0.5f : 0.28f) : 0.05f;
            flexBase = 0.15f;
            flexSwing = moving ? (charge ? 0.9f : 0.4f) : 0f;
            lean = wave ? 0.02f : charge ? 0.5f : 0.22f + 0.04f * (float) Math.sin(t * 1.3f);
            armA = 0f;
            upper = 10f;
            fore = 10.5f;
            armW = 5.2f;
            farAmp = 1f;
        } else {
            float amp = moving ? Math.max(0.35f, Math.min(1f, 0.35f + speed / 5f)) : 0f;
            l1 = 10.5f;
            l2 = 10.5f;
            legW = 4.2f;
            thighA = moving ? 0.62f * amp : 0.05f;
            flexBase = 0.12f;
            flexSwing = 1.5f * amp;
            lean = 0.05f + 0.26f * amp;
            armA = 0.85f * amp;
            upper = 8f;
            fore = 7.5f;
            armW = 3.3f;
            farAmp = 1f;
        }

        // Legs. The near leg carries the bite: it barely swings, and you drop onto it every step.
        float pA = phaseIn;
        float pB = phaseIn + pi;
        float nearAmp = 1f - 0.85f * lam;
        float tA;
        float tB;
        float fA;
        float fB;
        if (moving) {
            tA = thighA * nearAmp * (float) Math.sin(pA);
            fA = flexBase + flexSwing * nearAmp * Math.max(0f, (float) Math.cos(pA));
            tB = thighA * farAmp * (float) Math.sin(pB);
            fB = flexBase + flexSwing * farAmp * Math.max(0f, (float) Math.cos(pB));
        } else {
            tA = thighA;
            tB = -thighA;
            fA = flexBase;
            fB = flexBase;
        }
        float kAx = l1 * (float) Math.sin(tA);
        float kAy = l1 * (float) Math.cos(tA);
        float ftAx = kAx + l2 * (float) Math.sin(tA - fA);
        float ftAy = kAy + l2 * (float) Math.cos(tA - fA);
        float kBx = l1 * (float) Math.sin(tB);
        float kBy = l1 * (float) Math.cos(tB);
        float ftBx = kBx + l2 * (float) Math.sin(tB - fB);
        float ftBy = kBy + l2 * (float) Math.cos(tB - fB);
        // Gated on movement: phaseIn comes from distance, so a stopped rower would freeze mid-drop.
        float hop = lam > 0 && moving ? Math.max(0f, (float) Math.sin(phaseIn)) * 4f * lam : 0f;
        float breathe = moving ? 0f : (float) Math.sin(t * 2.2f + phaseIn) * 0.25f;
        float hipY = groundY - (Math.max(ftAy, ftBy) - hop - breathe) * s;

        int cloth;
        int skin;
        int legCol;
        int hair;
        if (kind == K_YOU) {
            cloth = color;
            skin = 0xFFF1C27D;
            legCol = 0xFF343B4C;
            hair = 0xFF3A2A1A;
        } else if (kind == K_SURVIVOR) {
            cloth = color;
            skin = 0xFFE8B98A;
            legCol = 0xFF3A3F55;
            hair = 0xFF6A3A1A;
        } else if (kind == K_WALKER) {
            cloth = tone(color, 0.62f);
            skin = 0xFF8DB386;
            legCol = 0xFF2E2B36;
            hair = 0xFF3E4A36;
        } else if (kind == K_RUNNER) {
            cloth = tone(color, 0.8f);
            skin = 0xFFB4C85A;
            legCol = 0xFF3A3424;
            hair = 0xFF3A3A2A;
        } else {
            cloth = color;
            skin = 0xFF5E8F66;
            legCol = 0xFF2A2430;
            hair = 0xFF223322;
        }

        // Contact shadow.
        float shW = (kind == K_BRUTE ? 9f : 7f) * s;
        paint.setColor(0x5A000000);
        c.drawOval(x - shW, groundY - 1.3f * s, x + shW * 1.15f, groundY + 1.3f * s, paint);

        // Torso frame.
        float tu = torsoUnits(kind);
        float sinL = (float) Math.sin(lean);
        float cosL = (float) Math.cos(lean);
        float shX = x + sinL * (tu - 2.2f) * s;
        float shY = hipY - cosL * (tu - 2.2f) * s;
        float topX = x + sinL * tu * s;
        float topY = hipY - cosL * tu * s;

        // Arm angles: near (N) and far (F), upper arm then forearm.
        float uN;
        float fN;
        float uF;
        float fF;
        if (kind == K_WALKER) {
            if (wave) {
                uN = 1.3f + 1.3f * (0.5f + 0.5f * (float) Math.sin(t * 9 + phaseIn));
                uF = 1.2f + 1.3f * (0.5f + 0.5f * (float) Math.sin(t * 9 + phaseIn + 1.6f));
                fN = uN + 0.35f;
                fF = uF + 0.35f;
            } else {
                uN = 1.42f + 0.10f * (float) Math.sin(t * 2f + phaseIn);
                fN = uN - 0.12f + 0.08f * (float) Math.sin(t * 2.6f + phaseIn);
                uF = 1.30f + 0.10f * (float) Math.sin(t * 2.2f + phaseIn + 1f);
                fF = uF - 0.05f;
            }
        } else if (kind == K_BRUTE) {
            if (wave) {
                float jolt = (float) Math.sin(t * 30) * 0.08f;
                uN = pi - 0.3f + jolt;
                fN = uN + 0.25f;
                uF = pi - 0.45f - jolt;
                fF = uF + 0.3f;
            } else if (charge) {
                uN = -0.45f;
                fN = -0.2f;
                uF = -0.35f;
                fF = -0.1f;
            } else {
                uN = 0.35f + 0.18f * (float) Math.sin(phaseIn);
                fN = uN + 0.15f;
                uF = 0.25f + 0.18f * (float) Math.sin(phaseIn + pi);
                fF = uF + 0.12f;
            }
        } else if (kind == K_SURVIVOR && wave) {
            uN = pi - 0.35f + 0.3f * (float) Math.sin(t * 9);
            fN = uN + 0.25f * (float) Math.sin(t * 9 + 1);
            uF = 0.5f;
            fF = 2.4f;
        } else {
            // Running: each arm swings against its own leg.
            uN = -armA * (float) Math.sin(pA) + lean * 0.4f;
            uF = -armA * (float) Math.sin(pB) + lean * 0.4f;
            fN = uN + (moving ? 1.5f : 0.3f);
            fF = uF + (moving ? 1.5f : 0.3f);
        }

        // Far arm, far leg: in shadow.
        int sleeve = kind == K_BRUTE ? skin : cloth;
        arm(c, shX, shY, uF, fF, upper * s, fore * s, armW * s, tone(sleeve, 0.66f), tone(skin, 0.66f), zombie);
        if (kind == K_SURVIVOR && wave) {
            // The lighter they hold up.
            Fx.glow(c, handX, handY - dp(4f), dp(22f), 0xAAFFC45A);
            paint.setColor(0xFFFFE08A);
            c.drawCircle(handX, handY - dp(5f) - (float) Math.abs(Math.sin(t * 13)) * dp(1.5f), dp(2.4f), paint);
        }
        leg(c, x, hipY, kBx * s, kBy * s, ftBx * s, ftBy * s, legW * s, tone(legCol, 0.68f));

        // Torso, in its own leaning frame.
        c.save();
        c.translate(x, hipY);
        c.rotate((float) Math.toDegrees(lean));
        paint.setColor(cloth);
        c.drawPath(torsoShape[kind], paint);
        float frontU = kind == K_BRUTE ? 9.2f : kind == K_WALKER ? 5.3f : kind == K_RUNNER ? 4.2f : 5.0f;
        float backU = kind == K_BRUTE ? 8.8f : kind == K_WALKER ? 6.2f : kind == K_RUNNER ? 4.4f : 4.8f;
        limb.setStrokeWidth(1.4f * s);
        limb.setColor(tone(cloth, 1.35f));
        limb.setAlpha(160);
        c.drawLine((frontU - 1.1f) * s, -1.5f * s, (frontU - 1.4f) * s, -(tu - 2.6f) * s, limb);
        limb.setColor(tone(cloth, 0.55f));
        limb.setAlpha(170);
        c.drawLine(-(backU - 1.1f) * s, -1.5f * s, -(backU - 1.3f) * s, -(tu - 3f) * s, limb);
        if (kind == K_YOU || kind == K_SURVIVOR) {
            paint.setColor(0x55000000);
            c.drawRect(-4.6f * s, -1.8f * s, 4.8f * s, 0.4f * s, paint);
            if (kind == K_YOU) {
                // A racing stripe on the vest.
                paint.setColor(tone(cloth, 1.5f));
                c.drawRect(-5.0f * s, -11.2f * s, 5.4f * s, -9.8f * s, paint);
            }
        } else if (kind == K_WALKER) {
            paint.setColor(0xAA5A1414);
            c.drawCircle(1.2f * s, -8f * s, 1.6f * s, paint);
            c.drawCircle(-2.6f * s, -12f * s, 1.1f * s, paint);
            limb.setStrokeWidth(0.7f * s);
            limb.setColor(0x99000000);
            c.drawLine(-3f * s, -5f * s, 0.5f * s, -3.5f * s, limb);
        } else if (kind == K_BRUTE) {
            limb.setStrokeWidth(1.8f * s);
            limb.setColor(0xFF2A2018);
            c.drawLine(-7f * s, -16f * s, 8.5f * s, -2f * s, limb);
            paint.setColor(0xAA4A1414);
            c.drawCircle(-2f * s, -7f * s, 2f * s, paint);
        } else {
            limb.setStrokeWidth(0.9f * s);
            limb.setColor(0x88FFFFFF);
            c.drawLine(3.3f * s, -1f * s, 3.4f * s, -14f * s, limb);
        }
        c.restore();

        leg(c, x, hipY, kAx * s, kAy * s, ftAx * s, ftAy * s, legW * s, legCol);
        if (lam > 0) {
            // Bandage and a stain on the bitten shin.
            float bx = x + (kAx + (ftAx - kAx) * 0.4f) * s;
            float by = hipY + (kAy + (ftAy - kAy) * 0.4f) * s;
            limb.setStrokeWidth(legW * s * 1.08f);
            limb.setColor(0xFFE6E0CE);
            c.drawLine(bx, by - 1.2f * s, bx, by + 1.2f * s, limb);
            paint.setColor(0xFF9E1B2E);
            paint.setAlpha((int) (120 + 135 * Math.min(1f, lam)));
            c.drawCircle(bx + 0.4f * s, by, 1.1f * s, paint);
            paint.setAlpha(255);
        }

        // Neck and head.
        float hr = headUnits(kind) * s;
        float tilt = zombie ? lean * 1.25f + 0.12f : lean * 0.7f;
        float neck = (kind == K_BRUTE ? 2.4f : 3.4f) * s + hr * 0.55f;
        float hcx = topX + (float) Math.sin(tilt) * neck;
        float hcy = topY - (float) Math.cos(tilt) * neck;
        limb.setStrokeWidth((kind == K_BRUTE ? 4.4f : 2.6f) * s);
        limb.setColor(tone(skin, 0.8f));
        c.drawLine(topX, topY + 1f * s, hcx, hcy, limb);
        c.save();
        c.translate(hcx, hcy);
        if (zombie) {
            shade.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
            shade.setShader(headShader[kind]);
            c.drawCircle(0, 0, hr, shade);
            shade.setShader(null);
            if (kind == K_WALKER) {
                limb.setStrokeWidth(0.18f * hr);
                limb.setColor(hair);
                c.drawLine(-0.3f * hr, -0.9f * hr, -0.65f * hr, -1.35f * hr, limb);
                c.drawLine(0.05f * hr, -0.96f * hr, 0.12f * hr, -1.42f * hr, limb);
                limb.setStrokeWidth(0.1f * hr);
                limb.setColor(0xAA1A2418);
                c.drawLine(-0.55f * hr, -0.35f * hr, 0.15f * hr, -0.62f * hr, limb);
            } else if (kind == K_RUNNER) {
                float flut = (float) Math.sin(t * 16) * 0.2f * hr;
                limb.setStrokeWidth(0.28f * hr);
                limb.setColor(hair);
                c.drawLine(-0.2f * hr, -0.85f * hr, -1.7f * hr, -0.75f * hr + flut, limb);
                c.drawLine(-0.5f * hr, -0.6f * hr, -1.9f * hr, -0.25f * hr - flut, limb);
                c.drawLine(-0.7f * hr, -0.2f * hr, -1.6f * hr, 0.25f * hr + flut, limb);
            } else {
                limb.setStrokeWidth(0.34f * hr);
                limb.setColor(hair);
                c.drawLine(0.05f * hr, -0.48f * hr, 0.95f * hr, -0.32f * hr, limb);
            }
            // A sunken socket with the eye a hot red point in it, the far eye dimmer.
            paint.setColor(0xD0141A12);
            c.drawOval(0.2f * hr, -0.4f * hr, 0.88f * hr, 0.1f * hr, paint);
            paint.setColor(0xFFFF3B3B);
            c.drawCircle(0.56f * hr, -0.15f * hr, 0.17f * hr, paint);
            paint.setColor(0xAAB02020);
            c.drawCircle(0.04f * hr, -0.17f * hr, 0.1f * hr, paint);
            // The jaw hanging open.
            paint.setColor(0xFF2A1414);
            c.drawOval(0.34f * hr, 0.3f * hr, 0.98f * hr, 0.74f * hr, paint);
            paint.setColor(0xFFEDE6C8);
            c.drawRect(0.46f * hr, 0.3f * hr, 0.6f * hr, 0.44f * hr, paint);
            if (kind == K_BRUTE) {
                c.drawRect(0.72f * hr, 0.3f * hr, 0.86f * hr, 0.44f * hr, paint);
            }
        } else {
            paint.setColor(hair);
            c.drawCircle(-0.2f * hr, -0.22f * hr, hr, paint);
            shade.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
            shade.setShader(headShader[kind]);
            c.drawCircle(0.12f * hr, 0.1f * hr, hr * 0.9f, shade);
            shade.setShader(null);
            paint.setColor(tone(skin, 0.78f));
            c.drawCircle(-0.12f * hr, 0.2f * hr, 0.2f * hr, paint);
            paint.setColor(0xFF1A1A1A);
            c.drawCircle(0.6f * hr, -0.02f * hr, 0.13f * hr, paint);
            paint.setColor(skin);
            c.drawCircle(0.98f * hr, 0.16f * hr, 0.15f * hr, paint);
            if (kind == K_YOU) {
                // Headband, its tails streaming, and the headlamp on the front of it.
                float flut = (float) Math.sin(t * 14) * 0.25f * hr * (moving ? 1f : 0.3f);
                limb.setColor(ACCENT);
                limb.setStrokeWidth(0.3f * hr);
                c.drawLine(-0.95f * hr, -0.3f * hr, 0.9f * hr, -0.44f * hr, limb);
                limb.setStrokeWidth(0.2f * hr);
                c.drawLine(-0.95f * hr, -0.3f * hr, -1.8f * hr, -0.12f * hr + flut, limb);
                c.drawLine(-0.95f * hr, -0.3f * hr, -1.62f * hr, 0.16f * hr - flut, limb);
                paint.setColor(0xFF2A3038);
                c.drawCircle(0.9f * hr, -0.44f * hr, 0.3f * hr, paint);
                paint.setColor(0xFFFFF6D8);
                c.drawCircle(0.98f * hr, -0.44f * hr, 0.18f * hr, paint);
            }
        }
        c.restore();
        if (kind == K_YOU) {
            lampX = hcx + 0.98f * hr;
            lampY = hcy - 0.44f * hr;
            Fx.glow(c, lampX, lampY, dp(18f), 0xC0FFF0C0);
        }
        if (zombie) {
            float ex = hcx + 0.56f * hr;
            float ey = hcy - 0.15f * hr;
            Fx.glow(c, ex, ey, dp(12f), 0x70FF2A2A);
            if (eyeCount < eyeX.length) {
                eyeX[eyeCount] = ex;
                eyeY[eyeCount] = ey;
                eyeCount++;
            }
        }

        // Near arm last, over everything.
        arm(c, shX, shY, uN, fN, upper * s, fore * s, armW * s, sleeve, skin, zombie);
    }

    /* Where the last arm() put its hand. */
    private float handX;
    private float handY;

    /** Upper arm in the sleeve colour, forearm and hand in skin; zombies get two clawed fingers. */
    private void arm(Canvas c, float sx, float sy, float u, float f, float upper, float fore, float w,
                     int sleeve, int skin, boolean claws) {
        float ex = sx + (float) Math.sin(u) * upper;
        float ey = sy + (float) Math.cos(u) * upper;
        float hx = ex + (float) Math.sin(f) * fore;
        float hy = ey + (float) Math.cos(f) * fore;
        limb.setStrokeWidth(w * 0.9f);
        limb.setColor(skin);
        c.drawLine(ex, ey, hx, hy, limb);
        limb.setStrokeWidth(w);
        limb.setColor(sleeve);
        c.drawLine(sx, sy, ex, ey, limb);
        paint.setColor(skin);
        c.drawCircle(hx, hy, w * 0.62f, paint);
        if (claws) {
            limb.setStrokeWidth(w * 0.28f);
            limb.setColor(skin);
            for (int i = -1; i <= 1; i += 2) {
                float a = f + i * 0.45f;
                c.drawLine(hx, hy, hx + (float) Math.sin(a) * w * 1.3f, hy + (float) Math.cos(a) * w * 1.3f, limb);
            }
        }
        handX = hx;
        handY = hy;
    }

    /** Thigh and shin from the hip, with a shoe at the end. */
    private void leg(Canvas c, float hx, float hy, float kx, float ky, float fx, float fy, float w, int col) {
        limb.setStrokeWidth(w);
        limb.setColor(col);
        c.drawLine(hx, hy, hx + kx, hy + ky, limb);
        limb.setStrokeWidth(w * 0.9f);
        c.drawLine(hx + kx, hy + ky, hx + fx, hy + fy, limb);
        limb.setStrokeWidth(w * 0.8f);
        limb.setColor(tone(col, 0.55f));
        c.drawLine(hx + fx - w * 0.15f, hy + fy, hx + fx + w * 0.75f, hy + fy + w * 0.05f, limb);
    }

    /**
     * One decimal place without {@code String.format}.
     *
     * <p>The crate's countdown is drawn twice a frame for the whole 14 s window, and a
     * {@code Formatter} plus its {@code StringBuilder} on every one of those is exactly the
     * per-frame allocation the frame loop is not allowed to do.
     */
    private static String tenths(double v) {
        long t = Math.max(0, Math.round(v * 10));
        return (t / 10) + "." + (t % 10);
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
