package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Pace Boat: a target boat at a pace you choose. Stay level, fall behind, or pull ahead.
 *
 * <p>The race clock does not start until the first stroke, so sitting on the seat costs
 * nothing. The gap is the whole game: it is drawn large and every stroke moves it.
 *
 * <p>3.19.5 (the emulator screenshot was two boats on dark water): an evening sky, a crowd on the
 * bank that cheers while you lead, a buoy line between the lanes, 250 m boards, a finish line that
 * sails in, reeds on the near shore, bigger boats, a splash on every stroke, callouts when the lead
 * changes, and confetti when you win.
 *
 * <p>The RACE upgrades (five items the rower approved), all in this base so every opponent mode
 * gets them:
 * <ul>
 *   <li><b>Several crews at once.</b> Up to three opponents, one per lane ({@link #crewCount()} and
 *       the {@code crew*} hooks). The gap on the HUD is to whoever leads the field.</li>
 *   <li><b>Splits at every 500 m.</b> Your time for each 500 m, and how far ahead or behind the
 *       fastest crew through that marker you were, in a table and a banner as you cross.</li>
 *   <li><b>A sprint call for the last 250 m.</b> A countdown, a bar that fills as you push past your
 *       usual speed, and the one crew you can catch (or must hold off) named with the metres.</li>
 *   <li><b>Crews with personalities.</b> {@link #taunt(int, int)} gives a crew a line for a pass, a
 *       split, the sprint or a quiet stretch; it is shown in a speech bubble over its boat.</li>
 *   <li><b>Current and wind by lane.</b> Each of the four lanes has a current and a wind that drift
 *       over the race, worth up to about four percent. Tap a lane (or tilt the handle sensor) to move
 *       into it; a crew already there swaps into yours. Recordings do not feel today's water.</li>
 * </ul>
 *
 * <p>The second round of RACE upgrades (3.22.x), again in the base so every opponent gets them:
 * <ul>
 *   <li><b>A handicap that learns.</b> The field starts a few metres up the course (or behind it) so
 *       the race is decided in the last minute rather than in the first. The stagger is carried in
 *       {@code race.hcp.*} and moves by 60% of the margin of the last race, clamped to -15%/+30% of
 *       the distance. It is shown on a chip you can tap off, and it never touches your own metres, so
 *       {@code time.<m>} and the recordings stay comparable.</li>
 *   <li><b>A best-of-three series across the week.</b> Every finished race scores a point; first to
 *       two takes the week. Pips on the HUD, a scoreboard at the finish, and Monday clears it.</li>
 *   <li><b>Push prompts.</b> A crew lifting 5% above its own recent pace for more than a second is
 *       "making a move": an 18 s window opens with a bar that fills as you answer it. Matching the
 *       move counts; ignoring it does not.</li>
 *   <li><b>Split-by-split afterwards.</b> The finish has a second page: every 500 m against the
 *       reference crew with a gain/loss bar, where the race was won and lost, and a 12x replay of the
 *       two boats along the course.</li>
 *   <li><b>Beat a crew and they join yours.</b> Recruited crews row in your boat (drawn, and they
 *       shout at the pushes and the sprint) and each one hands the field another 12 m of stagger, so
 *       the racing stays honest as the crew grows. Kept in {@code race.crew}.</li>
 * </ul>
 */
class PaceBoatGame extends GameView {

    enum State { READY, RACING, FINISHED }

    protected static final int MAX_CREWS = 3;
    private static final int LANES = 4;
    private static final int MAX_SPLITS = 10;       // 5000 m / 500 m
    private static final float SPRINT_METRES = 250f;
    /**
     * The water's largest effect, as a fraction of boat speed: 2.5% from current, 2% from wind. Worth a
     * few seconds over 1000 m to a rower who keeps finding the fast lane - enough to be worth a tap,
     * not enough to decide a race the rowing did not.
     */
    private static final float CURRENT_MAX = 0.025f;
    private static final float WIND_MAX = 0.02f;

    // What a crew can be given a line for (see taunt()).
    protected static final int SAY_START = 0;
    protected static final int SAY_PASSED_YOU = 1;
    protected static final int SAY_YOU_PASSED = 2;
    protected static final int SAY_SPRINT = 3;
    protected static final int SAY_SPLIT = 4;
    protected static final int SAY_LANE = 5;
    protected static final int SAY_CHATTER = 6;
    protected static final int SAY_WON = 7;
    protected static final int SAY_LOST = 8;
    /** A crew announcing its own move, when a push prompt opens. */
    protected static final int SAY_MOVE = 9;

    /** "+2.8%" style tags for the lane boost, -5.0% to +5.0% in tenths, built once. */
    private static final String[] BOOST_TEXT = new String[101];
    private static final int[] CONFETTI = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};
    private static final String[] LANE_NAME = {"LANE 1", "LANE 2", "LANE 3", "LANE 4"};
    private static final String[] LANE_WATER = {"LANE 1 WATER", "LANE 2 WATER", "LANE 3 WATER", "LANE 4 WATER"};

    static {
        for (int i = 0; i < BOOST_TEXT.length; i++) {
            int tenths = i - 50;
            BOOST_TEXT[i] = (tenths >= 0 ? "+" : "−") + (Math.abs(tenths) / 10) + "." + (Math.abs(tenths) % 10) + "%";
        }
    }

    private final RiverRenderer river;
    protected final PersonalBests bests;

    // 2:06 /500m: just inside the measured median of 2:08, so holding it is a real contest.
    private float targetPaceSec = 126f;
    protected int raceMeters = 1000;
    protected State state = State.READY;

    private double raceStartSeconds;
    private double raceStartMeters;
    protected double finishTime;
    protected boolean newBest;
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private final Paint scenePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bubblePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;
    private float cheer;
    private int lastLeader;
    private String callout = "";
    private int calloutColor = ACCENT;
    private double calloutUntil;
    private float lastYouX;
    private float lastYouY;

    // Lanes and water.
    private int youLane = 2;
    private float youLanePos = 2f;
    private final int[] crewLane = new int[MAX_CREWS];
    private final float[] crewLanePos = new float[MAX_CREWS];
    private double youWater;
    private final double[] crewWater = new double[MAX_CREWS];
    private final double[] currentSeed = new double[LANES];
    private final float[] currentAmp = new float[LANES];
    private double windSeed;
    private final float[] chevronDrift = new float[LANES];
    private boolean laneHintShown;
    private double laneHintSince = -1;
    private boolean steerArmed = true;

    // Crews on screen.
    private final float[] crewX = new float[MAX_CREWS];
    private final double[] crewStrokeAngle = new double[MAX_CREWS];
    private final int[] crewSide = new int[MAX_CREWS];
    private final int[] order = new int[MAX_CREWS];

    // Splits: index k is the 500 m marker k (k = 1..splitCount()).
    private final double[] youSplit = new double[MAX_SPLITS + 1];
    private final double[][] crewSplit = new double[MAX_CREWS][MAX_SPLITS + 1];
    private final double[] crewFinish = new double[MAX_CREWS];
    private int youNextSplit;
    private final int[] crewNextSplit = new int[MAX_CREWS];
    private final String[] splitRows = new String[MAX_SPLITS + 1];
    private final int[] splitRowColor = new int[MAX_SPLITS + 1];
    private String splitBanner = "";
    private String splitBannerSub = "";
    private int splitBannerSplit;
    private double splitBannerUntil;

    // The sprint.
    private boolean sprinting;
    private float sprintFlash;

    // Taunts.
    private String bubble = "";
    private int bubbleCrew = -1;
    private double bubbleUntil;
    private double lastTauntAt = -100;

    /* ---------- handicap, series, pushes, crew ---------- */

    /** What one recruited crew is worth to the field's stagger. Three mates = 36 m, about 9 s. */
    private static final float MATE_METRES = 12f;
    private static final int MAX_MATES = 3;
    private static final float PUSH_SECONDS = 18f;
    private static final int[] MATE_COLORS = {0xFFB48CFF, 0xFFF0655D, 0xFFF5C518};
    private static final String[] MATE_LINES = {
            "WITH YOU!", "SEND IT!", "LENGTH! LENGTH!", "WE'VE GOT THIS.", "LEGS NOW!", "HOLD THE RATIO!"};

    private boolean handicapOn = true;
    /** Metres the field starts up the course. Negative puts them behind the line. */
    private float handicapMetres;
    private float mateMetres;
    private float handicapShown;
    private float handicapNext;
    private float nextFieldMetres;
    /**
     * The first 500 m marker the field actually rows through. A learned stagger at 2000 m or 5000 m
     * can be more than 500 m, and a marker behind the field's start line was never raced - timing it
     * at the gun made the first split read "+120 s" and the analysis blame a 500 nobody rowed.
     */
    private int fieldFirstSplit = 1;

    private long seriesWeek;
    private int seriesYou;
    private int seriesThem;
    private int seriesPipPop = -1;
    private boolean seriesPipMine;
    private boolean seriesDecided;
    private String seriesLine = "";

    private final double[] crewSpeedAvg = new double[MAX_CREWS];
    private final double[] crewMoveFor = new double[MAX_CREWS];
    private double yourSpeedAvg;
    private int pushCrew = -1;
    private double pushUntil;
    private double pushBase;
    private double pushSum;
    private double pushTime;
    private double pushCooldownUntil;
    private int pushesCalled;
    private int pushesAnswered;
    private String pushResult = "";
    private String pushTitle = "";
    private double pushResultUntil;
    private boolean pushResultGood;

    private final java.util.ArrayList<String> mates = new java.util.ArrayList<>();
    private String mateStrip = "";
    private String recruited;
    private double recruitAnim;
    private String mateBubble = "";
    private double mateBubbleUntil;

    // The finish has two pages: the result, then the split-by-split analysis.
    private int finishPage;
    private float analysisT;
    private double replayT;
    private int analysisCrew = -1;
    private final double[] analysisSplit = new double[MAX_SPLITS + 1];
    private final boolean[] analysisProjected = new boolean[MAX_SPLITS + 1];
    /** Segment k began behind the field's start line, so there is nothing to compare it with. */
    private final boolean[] analysisPreStart = new boolean[MAX_SPLITS + 1];
    private String analysisWon = "";
    private String analysisLost = "";

    // Results, built once at the finish.
    private int finishPlace;
    private final double[] resultTime = new double[MAX_CREWS];
    private final String[] resultRows = new String[MAX_CREWS];
    private String resultMargin = "";
    private String resultSplits = "";
    private String resultQuote = "";
    private boolean resultWon;
    private String resultTitle = "";

    PaceBoatGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
        boardText.setTextSize(dp(11f));
        boardText.setFakeBoldText(true);
        bubblePaint.setTextSize(dp(12f));
        bubblePaint.setFakeBoldText(true);
        bubblePaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onStroke(int watts) {
        if (state != State.RACING || lastYouX == 0f) {
            return;
        }
        // The catch: a splash off each blade.
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 9; k++) {
                fx.spawn(lastYouX - dp(10f) + (float) Math.random() * dp(20f), lastYouY + side * dp(22f),
                        (float) (Math.random() - 0.7) * dp(60f), -dp(40f) - (float) Math.random() * dp(70f),
                        0.7f, dp(3.2f), 0xEEDDF2FF, true);
            }
        }
    }

    void setTargetPace(float secondsPer500) {
        this.targetPaceSec = secondsPer500;
        if (state != State.RACING) {
            state = State.READY;
        }
    }

    void setRaceMeters(int meters) {
        this.raceMeters = meters;
        if (state != State.RACING) {
            state = State.READY;
        }
    }

    float targetPace() {
        return targetPaceSec;
    }

    int raceMeters() {
        return raceMeters;
    }

    @Override
    protected void onStart() {
        state = State.READY;
        newBest = false;
        youWater = 0;
        youNextSplit = 1;
        sprinting = false;
        sprintFlash = 0f;
        lastLeader = 0;
        calloutUntil = 0;
        splitBannerUntil = 0;
        bubbleUntil = 0;
        bubbleCrew = -1;
        lastTauntAt = -100;
        laneHintShown = false;
        laneHintSince = -1;
        for (int k = 0; k <= MAX_SPLITS; k++) {
            youSplit[k] = -1;
            splitRows[k] = null;
        }
        // Keep the lane you chose last race; the crews fill the others, nearest the middle first.
        int[] prefer = {1, 3, 0, 2};
        int n = 0;
        for (int p = 0; p < prefer.length && n < MAX_CREWS; p++) {
            if (prefer[p] != youLane) {
                crewLane[n++] = prefer[p];
            }
        }
        for (int i = 0; i < MAX_CREWS; i++) {
            crewLanePos[i] = crewLane[i];
            crewWater[i] = 0;
            crewFinish[i] = -1;
            crewNextSplit[i] = 1;
            crewX[i] = 0f;
            crewSide[i] = 0;
            crewStrokeAngle[i] = i * 2.1;
            for (int k = 0; k <= MAX_SPLITS; k++) {
                crewSplit[i][k] = -1;
            }
        }
        youLanePos = youLane;
        // Fresh water for every race: each lane's current on its own slow cycle, and a wind whose
        // gusts sweep across the lanes.
        for (int l = 0; l < LANES; l++) {
            currentSeed[l] = Math.random() * Math.PI * 2;
            currentAmp[l] = 0.6f + (float) Math.random() * 0.4f;
        }
        windSeed = Math.random() * Math.PI * 2;

        // The handicap, the crew and the week's series.
        loadMates();
        handicapMetres = clampHandicap(bests.get(handicapKey(), 0f));
        handicapNext = handicapMetres;
        mateMetres = mates.size() * MATE_METRES;
        handicapShown = handicap();
        syncHandicap();
        // The field's timing starts at the first marker ahead of its start line.
        for (int i = 0; i < MAX_CREWS; i++) {
            crewNextSplit[i] = fieldFirstSplit;
        }
        loadSeries();
        finishPage = 0;
        analysisT = 0f;
        replayT = 0;
        analysisCrew = -1;
        recruited = null;
        recruitAnim = 0;
        mateBubbleUntil = 0;
        pushCrew = -1;
        pushResultUntil = 0;
        pushCooldownUntil = 0;
        pushesCalled = 0;
        pushesAnswered = 0;
        yourSpeedAvg = profile.typicalSpeed();
        for (int i = 0; i < MAX_CREWS; i++) {
            crewSpeedAvg[i] = 0;
            crewMoveFor[i] = 0;
        }
        for (int k = 0; k <= MAX_SPLITS; k++) {
            analysisSplit[k] = -1;
            analysisProjected[k] = false;
            analysisPreStart[k] = false;
        }
    }

    /**
     * Re-derives everything that depends on the size of the head start. Called when the race is set
     * up and whenever the chip changes it, so nothing is left describing a stagger that is no longer
     * being raced.
     */
    private void syncHandicap() {
        float start = Math.max(0f, handicap());
        fieldFirstSplit = Math.min(MAX_SPLITS + 1, (int) Math.floor(start / 500.0) + 1);
        onHandicapChanged();
    }

    /** The head start changed: subclasses re-solve anything they worked out from it. */
    protected void onHandicapChanged() {
    }

    /** Where the learned stagger is kept. Subclasses split it per opponent. */
    protected String handicapKey() {
        return "race.hcp." + raceMeters;
    }

    /** Where the week's best-of-three is kept. Subclasses split it per opponent. */
    protected String seriesKey() {
        return "race.series";
    }

    /** True if beating this crew should recruit it. */
    protected boolean crewRecruitable(int i) {
        return false;
    }

    /**
     * Metres of stagger the field starts with: what the last races taught, plus 12 m for every crew
     * that has joined yours. It is added to the crews' distance only - your own metres are untouched,
     * so a handicapped race still sets an honest {@code time.<m>}.
     */
    protected final float handicap() {
        return handicapOn ? handicapMetres + mateMetres : 0f;
    }

    private float clampHandicap(float v) {
        return Math.max(-raceMeters * 0.15f, Math.min(raceMeters * 0.30f, v));
    }

    /** Monday-start week number. Epoch day 0 was a Thursday; plain arithmetic, as Android 9 has no floorMod(long,int). */
    private static long weekNow() {
        long now = System.currentTimeMillis();
        long day = (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
        long d = day + 3;
        return d >= 0 ? d / 7 : (d - 6) / 7;
    }

    private void loadMates() {
        mates.clear();
        String saved = bests.getString("race.crew");
        if (saved != null && !saved.isEmpty()) {
            for (String s : saved.split(",")) {
                if (!s.isEmpty() && mates.size() < MAX_MATES) {
                    mates.add(s);
                }
            }
        }
        buildMateStrip();
    }

    private void saveMates() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mates.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(mates.get(i));
        }
        bests.putString("race.crew", sb.toString());
        buildMateStrip();
    }

    /** Built when the crew changes, never in a frame. */
    private void buildMateStrip() {
        if (mates.isEmpty()) {
            mateStrip = "";
            return;
        }
        StringBuilder sb = new StringBuilder("YOUR CREW: ");
        for (int i = 0; i < mates.size(); i++) {
            if (i > 0) {
                sb.append("  ·  ");
            }
            sb.append(mates.get(i));
        }
        mateStrip = sb.toString();
    }

    private void loadSeries() {
        seriesWeek = weekNow();
        seriesYou = 0;
        seriesThem = 0;
        String s = bests.getString(seriesKey());
        if (s != null) {
            String[] p = s.split("\\|");
            if (p.length == 3) {
                try {
                    if (Long.parseLong(p[0]) == seriesWeek) {
                        seriesYou = Math.max(0, Math.min(3, Integer.parseInt(p[1])));
                        seriesThem = Math.max(0, Math.min(3, Integer.parseInt(p[2])));
                    }
                } catch (NumberFormatException ignored) {
                    // A key written by an older build: start the week fresh.
                }
            }
        }
        seriesDecided = seriesYou >= 2 || seriesThem >= 2;
        seriesPipPop = -1;
        seriesLine = seriesDecided
                ? (seriesYou >= 2 ? "SERIES WON THIS WEEK" : "SERIES LOST - MONDAY RESETS IT")
                : "BEST OF 3  ·  RACE " + Math.min(3, seriesYou + seriesThem + 1);
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (state == State.READY && driving && boat.value() > 0.3f) {
            state = State.RACING;
            raceStartSeconds = sessionSeconds;
            raceStartMeters = sessionMeters;
            // Someone always has something to say at the gun.
            int n = crewCount();
            if (n > 0) {
                say((int) (Math.random() * n), SAY_START, true);
            }
        }
    }

    protected double raceTime() {
        return state == State.READY ? 0 : sessionSeconds - raceStartSeconds;
    }

    /** Your race metres: what the monitor counted, plus what the lane's water gave or took. */
    protected double raceDistance() {
        return state == State.READY ? 0 : sessionMeters - raceStartMeters + youWater;
    }

    protected boolean sprinting() {
        return sprinting;
    }

    /* ---------- crew hooks: the default is one pace boat ---------- */

    protected int crewCount() {
        return 1;
    }

    /** Metres this crew has rowed by itself, before the lane's water is added. */
    protected double crewMeters(int i) {
        return paceBoatMeters();
    }

    /** A boat at the chosen pace since the gun. */
    protected final double paceBoatMeters() {
        return (500.0 / targetPaceSec) * raceTime();
    }

    protected float crewSpeed(int i) {
        return state == State.RACING ? 500f / targetPaceSec : 0f;
    }

    protected String crewName(int i) {
        return "PACE BOAT";
    }

    protected String crewLabel(int i) {
        return "PACE BOAT  " + PersonalBests.formatPace(targetPaceSec);
    }

    protected int crewColor(int i) {
        return BLUE;
    }

    /** A recording replays a race already rowed: drawn translucent, and today's water does not move it. */
    protected boolean crewIsRecording(int i) {
        return false;
    }

    protected float crewStrokeRate(int i) {
        return (float) profile.typicalRate();
    }

    /** A line for this crew to say about {@code event}, or null to stay quiet. */
    protected String taunt(int i, int event) {
        return null;
    }

    /** Where the crew is: its own metres, the water it has felt, and the handicap it started with. */
    protected final double crewDistance(int i) {
        return crewMeters(i) + crewWater[i] + handicap();
    }

    /** When the crew crossed (or will cross) the line. Recordings override with the recorded time. */
    protected double crewFinishTime(int i) {
        if (crewFinish[i] >= 0) {
            return crewFinish[i];
        }
        double left = raceMeters - crewDistance(i);
        return raceTime() + Math.max(0, left) / Math.max(0.5, crewSpeed(i));
    }

    protected String winText() {
        return crewCount() > 1 ? "YOU WON THE RACE" : "YOU BEAT THE PACE BOAT";
    }

    protected String loseText() {
        return "THE PACE BOAT WON";
    }

    protected String rightHudValue() {
        return PersonalBests.formatPace(targetPaceSec);
    }

    protected String rightHudCaption() {
        return "TARGET /500";
    }

    /** Called every frame while racing, for subclasses that record the run or move their crews. */
    protected void onRaceTick(double time, double meters) {
    }

    /** Called once when the finish line is crossed. */
    protected void onRaceFinished(double time, boolean won) {
    }

    /** The crew furthest down the course: the one the HUD gap is measured to. */
    protected final int leadingCrew() {
        int best = 0;
        for (int i = 1; i < crewCount(); i++) {
            if (crewDistance(i) > crewDistance(best)) {
                best = i;
            }
        }
        return best;
    }

    /* ---------- water ---------- */

    private float laneCurrent(int l) {
        return CURRENT_MAX * currentAmp[l] * (float) Math.sin(sessionSeconds * (2 * Math.PI / 75.0) + currentSeed[l]);
    }

    private float laneWind(int l) {
        // One gust band drifting across the lanes: the 1.1 rad offset per lane is what makes it sweep.
        return WIND_MAX * (float) Math.sin(sessionSeconds * (2 * Math.PI / 38.0) + windSeed + l * 1.1);
    }

    private float laneBoost(int l) {
        return laneCurrent(l) + laneWind(l);
    }

    /** Boost at a lane position part-way through a lane change. */
    private float boostAt(float lanePos) {
        int a = Math.max(0, Math.min(LANES - 1, (int) Math.floor(lanePos)));
        int b = Math.min(LANES - 1, a + 1);
        float f = Math.max(0f, Math.min(1f, lanePos - a));
        return laneBoost(a) + (laneBoost(b) - laneBoost(a)) * f;
    }

    private static String boostText(float boost) {
        int tenths = Math.round(boost * 1000f);
        return BOOST_TEXT[Math.max(0, Math.min(100, tenths + 50))];
    }

    private float laneY(float lanePos, float waterTop, float waterBottom) {
        return waterTop + (waterBottom - waterTop) * (0.14f + 0.24f * lanePos);
    }

    /** Move into {@code lane}; a crew already there takes the one you left. */
    private void changeLane(int lane) {
        lane = Math.max(0, Math.min(LANES - 1, lane));
        if (lane == youLane || state == State.FINISHED) {
            return;
        }
        for (int i = 0; i < crewCount(); i++) {
            if (crewLane[i] == lane) {
                crewLane[i] = youLane;
                say(i, SAY_LANE, false);
            }
        }
        youLane = lane;
    }

    /* ---------- taunts ---------- */

    /** Shows a crew's line in a bubble over its boat. Passes may cut in; chatter waits its turn. */
    private void say(int crew, int event, boolean urgent) {
        if (crew < 0 || crew >= crewCount()) {
            return;
        }
        String line = taunt(crew, event);
        if (line == null) {
            return;
        }
        boolean busy = sessionSeconds < bubbleUntil;
        if (busy && !(urgent && sessionSeconds - lastTauntAt > 1.2)) {
            return;
        }
        if (!urgent && sessionSeconds - lastTauntAt < 5) {
            return;
        }
        bubble = line;
        bubbleCrew = crew;
        bubbleUntil = sessionSeconds + 2.8;
        lastTauntAt = sessionSeconds;
    }

    /* ---------- splits ---------- */

    private int splitCount() {
        return Math.min(MAX_SPLITS, raceMeters / 500);
    }

    /** Fastest crew time through marker k, or -1 if none has crossed it. */
    private double fastestCrewSplit(int k, int[] who) {
        double best = -1;
        for (int i = 0; i < crewCount(); i++) {
            double t = crewSplit[i][k];
            if (t >= 0 && (best < 0 || t < best)) {
                best = t;
                if (who != null) {
                    who[0] = i;
                }
            }
        }
        return best;
    }

    private final int[] whoScratch = new int[1];

    /** Rebuilds the text for split k after you or a crew crossed its marker. */
    private void rebuildSplit(int k) {
        if (k < 1 || k > splitCount() || youSplit[k] < 0) {
            return;
        }
        double seg = youSplit[k] - (k > 1 ? youSplit[k - 1] : 0);
        double crew = fastestCrewSplit(k, whoScratch);
        String delta;
        int color;
        if (crew < 0) {
            // No crew time here is one of two different things: you are genuinely first through the
            // marker, or the field started past it and never raced this one.
            boolean headStart = k < fieldFirstSplit;
            delta = headStart ? "HEAD START" : "LEAD";
            color = headStart ? DIM : ACCENT;
        } else {
            double d = youSplit[k] - crew;
            delta = String.format(java.util.Locale.US, "%s%.1f s", d <= 0 ? "−" : "+", Math.abs(d));
            color = d <= 0 ? ACCENT : BAD;
        }
        splitRows[k] = String.format(java.util.Locale.US, "%5d m   %s   %s", k * 500,
                PersonalBests.formatPace((float) seg), delta);
        splitRowColor[k] = color;
        if (k == splitBannerSplit && sessionSeconds < splitBannerUntil) {
            splitBannerSub = crew < 0
                    ? (k < fieldFirstSplit ? "THE FIELD STARTED PAST THIS MARKER" : "FIRST THROUGH THE MARKER")
                    : (youSplit[k] <= crew ? "AHEAD OF " : "BEHIND ") + crewName(whoScratch[0]) + " BY "
                    + String.format(java.util.Locale.US, "%.1f s", Math.abs(youSplit[k] - crew));
        }
    }

    /* ---------- input ---------- */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (state == State.FINISHED) {
                // First tap opens the split-by-split page, the second starts the next race.
                if (finishPage == 0 && splitCount() >= 1) {
                    finishPage = 1;
                    analysisT = 0f;
                    replayT = 0;
                } else {
                    start();
                }
                return true;
            }
            // The handicap chip: tap to race level, tap again to put the stagger back.
            if (event.getY() < dp(42f) && Math.abs(event.getX() - getWidth() * 0.42f) < dp(80f)) {
                handicapOn = !handicapOn;
                // A recording's finish is solved from the head start, so it has to be solved again.
                syncHandicap();
                return true;
            }
            float h = getHeight();
            float waterTop = h * 0.30f;
            float waterBottom = h * 0.78f;
            float y = event.getY();
            if (y > waterTop && y < waterBottom + dp(20f)) {
                float pos = ((y - waterTop) / (waterBottom - waterTop) - 0.14f) / 0.24f;
                changeLane(Math.round(pos));
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    /* ---------- the frame ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int crews = Math.min(MAX_CREWS, crewCount());
        float speed = boat.value();

        // The handle sensor steers across lanes: a firm tilt moves one lane, then re-arms at centre.
        if (hasSteering()) {
            float s = steering();
            if (steerArmed && Math.abs(s) > 0.55f) {
                changeLane(youLane + (s > 0 ? 1 : -1));
                steerArmed = false;
            } else if (Math.abs(s) < 0.25f) {
                steerArmed = true;
            }
        }
        youLanePos += (youLane - youLanePos) * Math.min(1f, 3f * dt);
        for (int i = 0; i < crews; i++) {
            crewLanePos[i] += (crewLane[i] - crewLanePos[i]) * Math.min(1f, 3f * dt);
        }

        if (state == State.RACING) {
            stepRace(dt, crews, speed);
        }

        float hudH = h * 0.30f;
        float waterTop = hudH;
        float waterBottom = h * 0.78f;
        float ppm = w / 60f;   // 60 metres across the screen

        float bankTop = waterTop - dp(46f);
        if (skyShader == null || skyHeight != bankTop) {
            skyHeight = bankTop;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, bankTop, 0xFF0B1322, 0xFF35577E,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        scenePaint.setColor(0xFFFFFFFF);
        scenePaint.setShader(skyShader);
        c.drawRect(0, 0, w, bankTop, scenePaint);
        scenePaint.setShader(null);
        Fx.glow(c, w * 0.12f, bankTop - dp(14f), dp(110f), 0x44FFC98A);
        river.advance(state == State.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        double you = raceDistance();
        scenery.drawBank(c, w, bankTop, waterTop, you, ppm, sessionSeconds, cheer);
        float span = waterBottom - waterTop;
        for (int b = 0; b < LANES - 1; b++) {
            scenery.drawBuoys(c, w, waterTop + span * (0.26f + 0.24f * b), you, ppm, sessionSeconds, 10f);
        }
        scenery.drawWaterLife(c, w, waterTop, waterBottom, you, ppm, sessionSeconds);
        drawLaneWater(c, w, waterTop, waterBottom, you, ppm, dt);
        // Near shore: grass and reeds, scrolling fastest.
        scenePaint.setColor(0xFF2F5E33);
        c.drawRect(0, waterBottom, w, waterBottom + dp(26f), scenePaint);
        float reedGap = dp(22f);
        double reedScroll = you * ppm * 1.2;
        float reedOff = (float) (reedScroll % reedGap);
        scenePaint.setStrokeWidth(dp(3f));
        for (float rx = -reedOff - reedGap; rx < w + reedGap; rx += reedGap) {
            int k = (int) Math.floor((rx + reedScroll) / reedGap + 0.5);
            float tall = dp(14f) + (Math.abs(k * 7) % 4) * dp(5f);
            float sway = (float) Math.sin(sessionSeconds * 2 + k) * dp(3f);
            scenePaint.setColor((k & 1) == 0 ? 0xFF4E8F4F : 0xFF6BAA5C);
            c.drawLine(rx, waterBottom + dp(8f), rx + sway, waterBottom + dp(8f) - tall, scenePaint);
        }
        fx.step(dt, dp(260f));

        int lead = leadingCrew();
        double gap = raceDistance() - crewDistance(lead);
        float yourX = w * 0.38f;
        float boatLen = dp(140f);
        float laneYou = laneY(youLanePos, waterTop, waterBottom);

        for (int mark = 250; mark < raceMeters; mark += 250) {
            float mx = yourX + (float) (mark - you) * ppm;
            if (mx > -dp(40f) && mx < w + dp(40f)) {
                if (mark % 500 == 0) {
                    // A split marker: a gold timing line across every lane.
                    scenePaint.setColor(0x66F5C518);
                    scenePaint.setStrokeWidth(dp(2f));
                    for (float y = waterTop; y < waterBottom; y += dp(14f)) {
                        c.drawLine(mx, y, mx, Math.min(waterBottom, y + dp(8f)), scenePaint);
                    }
                }
                scenery.drawBoard(c, mx, waterTop, (raceMeters - mark) + " m", boardText);
            }
        }
        float finishX = yourX + boatLen / 2f + (float) (raceMeters - you) * ppm;
        if (finishX < w + dp(40f)) {
            scenery.drawFinishLine(c, finishX, waterTop, waterBottom, sessionSeconds);
        }

        // Who leads the field, and the callout when that changes hands.
        int leader = state != State.RACING ? 0 : gap >= 0 ? 1 : -1;
        if (state == State.RACING && lastLeader != 0 && leader != lastLeader && Math.abs(gap) > 0.5) {
            callout = leader > 0 ? "YOU TAKE THE LEAD!" : "YOU'VE BEEN PASSED";
            calloutColor = leader > 0 ? ACCENT : BAD;
            calloutUntil = sessionSeconds + 1.8;
            if (leader > 0) {
                fx.burst(yourX, laneYou, 30, dp(170f), 0.9f, dp(3f), 0xFFF5C518, true);
            }
        }
        if (state == State.RACING && Math.abs(gap) > 0.5) {
            lastLeader = leader;
        }
        float cheerTarget = sprinting ? 1f : state == State.RACING ? (gap >= 0 ? 1f : 0.25f)
                : state == State.FINISHED && resultWon ? 1f : 0.1f;
        cheer += (cheerTarget - cheer) * Math.min(1f, 2f * dt);
        lastYouX = yourX;
        lastYouY = laneYou;

        // The crews, each at its own gap and rowing to its own rhythm.
        for (int i = 0; i < crews; i++) {
            float laneC = laneY(crewLanePos[i], waterTop, waterBottom);
            double cGap = crewDistance(i) - you;
            float targetX = yourX + (float) cGap * ppm;
            float clampedX = Math.max(dp(40f), Math.min(w - dp(40f), targetX));
            crewX[i] += (clampedX - crewX[i]) * Math.min(1f, 6f * dt);
            if (crewX[i] == 0f) {
                crewX[i] = clampedX;
            }
            float cs = crewSpeed(i);
            if (cs > 0.3f) {
                double before = crewStrokeAngle[i];
                crewStrokeAngle[i] += dt * 2 * Math.PI * crewStrokeRate(i) / 60.0;
                // A splash at each of its catches, so a crew visibly rows rather than slides.
                if (Math.floor(before / (2 * Math.PI)) != Math.floor(crewStrokeAngle[i] / (2 * Math.PI))
                        && targetX == clampedX && !crewIsRecording(i)) {
                    for (int k = 0; k < 6; k++) {
                        fx.spawn(crewX[i] + (float) (Math.random() - 0.5) * dp(20f),
                                laneC + (k & 1) * dp(44f) - dp(22f), (float) (Math.random() - 0.7) * dp(50f),
                                -dp(30f) - (float) Math.random() * dp(50f), 0.6f, dp(2.6f), 0xCCDDF2FF, true);
                    }
                }
            }
            river.setStrokePhase((float) (0.5 + 0.5 * Math.sin(crewStrokeAngle[i])));
            river.drawBoat(c, crewX[i], laneC, boatLen, crewColor(i), cs, crewIsRecording(i));
            if (targetX != clampedX) {
                // Off the edge: an arrow says which way and how far.
                String far = String.format(java.util.Locale.US, "%s %.0f m",
                        targetX > clampedX ? "→" : "←", Math.abs(cGap));
                bold(c, far, targetX > clampedX ? w - dp(14f) : dp(70f), laneC - dp(22f), 13f,
                        crewColor(i), targetX > clampedX ? Paint.Align.RIGHT : Paint.Align.LEFT);
            }
        }
        river.bowSpray(yourX + boatLen / 2f, laneYou, speed, dt);
        // Your own oars follow your own stroke, set here rather than inherited.
        river.setStrokePhase(strokePhase());
        river.drawBoat(c, yourX, laneYou, boatLen, ACCENT, speed, false);
        drawMates(c, yourX, laneYou);
        river.drawSpray(c);
        Fx.glow(c, yourX, laneYou, dp(50f), 0x2A35D0BA);

        if (gap >= 0 && state == State.RACING) {
            Fx.glow(c, yourX, laneYou, dp(90f), 0x40F5C518);
        }
        fx.draw(c);
        bold(c, "YOU", yourX, laneYou + dp(36f), 11f, ACCENT, Paint.Align.CENTER);
        for (int i = 0; i < crews; i++) {
            float laneC = laneY(crewLanePos[i], waterTop, waterBottom);
            label(c, crewLabel(i), Math.max(dp(130f), Math.min(w - dp(110f), crewX[i])), laneC - dp(24f), 11f,
                    crewIsRecording(i) ? DIM : crewColor(i), Paint.Align.CENTER);
        }
        drawBubble(c, w, waterTop, waterBottom);
        drawMateBubble(c, yourX, laneYou);
        drawLaneTags(c, waterTop, waterBottom);
        if (sessionSeconds < calloutUntil) {
            bold(c, callout, w / 2f, waterTop + span * 0.5f, 30f, calloutColor, Paint.Align.CENTER);
        }
        if (sprinting && state == State.RACING) {
            drawSprintWater(c, w, waterTop, waterBottom, speed);
        }

        drawHud(c, w, h, hudH, speed, gap, lead);
        drawRaceChips(c, w, dt);
        // Last in the HUD band, so nothing draws over a live push call.
        drawPush(c, w, hudH);

        // Progress bar along the top of the water.
        float pct = Math.min(1f, (float) (raceDistance() / raceMeters));
        accentPaint.setColor(ACCENT);
        accentPaint.setStrokeWidth(dp(3f));
        c.drawLine(0, waterTop, w * pct, waterTop, accentPaint);

        if (state == State.FINISHED && finishPage == 1) {
            drawSplitAnalysis(c, w, h, dt);
        } else if (state == State.FINISHED) {
            drawFinish(c, w, h, dt);
            if (resultWon) {
                if (Math.random() < 0.8) {
                    fx.spawn((float) Math.random() * w, -dp(10f), (float) (Math.random() - 0.5) * dp(80f),
                            dp(40f), 3f, dp(3.5f), CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
                }
                fx.draw(c);
            }
        } else {
            lastLeader = state == State.READY ? 0 : lastLeader;
        }
    }

    /** Everything that moves the race on by one frame: water, markers, passes, the sprint, the line. */
    private void stepRace(float dt, int crews, float speed) {
        onRaceTick(raceTime(), raceDistance());
        double t = raceTime();
        // The water: a share of each boat's own speed, so a stopped boat does not drift home.
        youWater += boostAt(youLanePos) * speed * dt;
        for (int i = 0; i < crews; i++) {
            if (!crewIsRecording(i) && crewDistance(i) < raceMeters) {
                crewWater[i] += boostAt(crewLanePos[i]) * crewSpeed(i) * dt;
            }
        }

        double you = raceDistance();
        int splits = splitCount();
        // Crews through markers and the line.
        for (int i = 0; i < crews; i++) {
            double d = crewDistance(i);
            while (crewNextSplit[i] <= splits && d >= crewNextSplit[i] * 500.0) {
                crewSplit[i][crewNextSplit[i]] = t;
                rebuildSplit(crewNextSplit[i]);
                if (youSplit[crewNextSplit[i]] < 0 && crewNextSplit[i] < splits) {
                    say(i, SAY_SPLIT, false);
                }
                crewNextSplit[i]++;
            }
            if (crewFinish[i] < 0 && d >= raceMeters) {
                crewFinish[i] = t;
            }
        }
        // You through a marker: a banner with the split and the margin to the fastest crew.
        while (youNextSplit <= splits && you >= youNextSplit * 500.0) {
            int k = youNextSplit;
            youSplit[k] = t;
            if (k < splits) {
                double seg = t - (k > 1 ? youSplit[k - 1] : 0);
                splitBanner = (k * 500) + " m   ·   SPLIT " + PersonalBests.formatPace((float) seg);
                splitBannerSplit = k;
                splitBannerUntil = sessionSeconds + 4.5;
                fx.burst(lastYouX, lastYouY, 18, dp(140f), 0.8f, dp(2.6f), 0xFFF5C518, true);
            }
            rebuildSplit(k);
            youNextSplit++;
        }
        // The last 250 m.
        if (!sprinting && raceMeters > SPRINT_METRES && you >= raceMeters - SPRINT_METRES) {
            sprinting = true;
            sprintFlash = 1f;
            callout = "SPRINT!  250 TO GO";
            calloutColor = WARN;
            calloutUntil = sessionSeconds + 2.4;
            fx.burst(lastYouX, lastYouY, 40, dp(220f), 1f, dp(3f), 0xFFF5C518, true);
            int near = nearestCrew(you);
            if (near >= 0) {
                say(near, SAY_SPRINT, true);
            }
            sayMate(3);
        }
        stepPushes(dt, crews, speed, you);
        // Passes, one crew at a time, with hysteresis so a boat sitting level does not chatter.
        for (int i = 0; i < crews; i++) {
            double g = you - crewDistance(i);
            int side = g > 1 ? 1 : g < -1 ? -1 : crewSide[i];
            if (crewSide[i] != 0 && side != crewSide[i]) {
                say(i, side < 0 ? SAY_PASSED_YOU : SAY_YOU_PASSED, true);
            }
            crewSide[i] = side;
        }
        // A quiet stretch: the nearest crew fills it.
        if (sessionSeconds - lastTauntAt > 18) {
            say(nearestCrew(you), SAY_CHATTER, false);
        }
        // The finish.
        if (you >= raceMeters) {
            finishTime = t;
            newBest = bests.recordLowest("time." + raceMeters, (float) finishTime);
            // Built while still RACING: the unfinished crews' estimates need their live speeds.
            buildResults(crews);
            state = State.FINISHED;
            onRaceFinished(finishTime, resultWon);
        }
    }

    /**
     * A crew making a move, and whether you answer it.
     *
     * <p>A move is a crew holding 5% above its own eight-second average for more than a second -
     * measured against itself, so a fast crew does not read as permanently attacking. The window is
     * 18 s and is scored on your own speed over it against your twelve-second average at the moment
     * it opened, which is the only fair comparison when every rower's numbers are different.
     */
    private void stepPushes(float dt, int crews, float speed, double you) {
        yourSpeedAvg += (speed - yourSpeedAvg) * Math.min(1.0, dt / 12.0);
        for (int i = 0; i < crews; i++) {
            double cs = crewSpeed(i);
            if (crewSpeedAvg[i] <= 0) {
                crewSpeedAvg[i] = cs;
            }
            crewSpeedAvg[i] += (cs - crewSpeedAvg[i]) * Math.min(1.0, dt / 8.0);
            boolean moving = cs > 0.5 && cs > crewSpeedAvg[i] * 1.05 && crewFinish[i] < 0;
            // Capped: without it a crew that has been lifting through a long cooldown fires a push
            // the instant the cooldown lapses, however long ago the move actually was.
            crewMoveFor[i] = moving ? Math.min(3.0, crewMoveFor[i] + dt) : Math.max(0, crewMoveFor[i] - dt * 2);
            if (pushCrew < 0 && crewMoveFor[i] > 1.2 && sessionSeconds > pushCooldownUntil
                    && !sprinting && you > 60 && raceMeters - you > 150) {
                startPush(i);
            }
        }
        if (pushCrew >= 0) {
            pushSum += speed * dt;
            pushTime += dt;
            if (sessionSeconds >= pushUntil || sprinting || crewFinish[pushCrew] >= 0) {
                endPush();
            }
        }
    }

    private void startPush(int crew) {
        pushCrew = crew;
        pushUntil = sessionSeconds + PUSH_SECONDS;
        pushBase = Math.max(0.8, yourSpeedAvg);
        pushSum = 0;
        pushTime = 0;
        pushesCalled++;
        crewMoveFor[crew] = 0;
        pushResultUntil = 0;
        pushTitle = crewName(crew) + " ARE MOVING  -  PUSH!";
        say(crew, SAY_MOVE, true);
        sayMate(pushesCalled);
        if (crewX[crew] > 0f) {
            fx.burst(crewX[crew], lastYouY, 20, dp(150f), 0.8f, dp(2.8f), crewColor(crew), true);
        }
    }

    private void endPush() {
        // Cut short by the sprint or by the crew finishing: too little of the window to judge, so it
        // is withdrawn rather than scored as a miss.
        if (pushTime < 4.0) {
            pushCrew = -1;
            pushesCalled = Math.max(0, pushesCalled - 1);
            pushCooldownUntil = sessionSeconds + 10;
            return;
        }
        double avg = pushSum / pushTime;
        pushResultGood = avg >= pushBase * 1.02;
        if (pushResultGood) {
            pushesAnswered++;
            pushResult = "PUSH ANSWERED  ·  " + pace(avg);
            fx.burst(lastYouX, lastYouY, 26, dp(180f), 0.9f, dp(3f), 0xFF35D0BA, true);
            sayMate(pushesAnswered + 2);
        } else {
            pushResult = "THE MOVE WENT UNANSWERED";
        }
        pushResultUntil = sessionSeconds + 2.8;
        pushCooldownUntil = sessionSeconds + 22;
        pushCrew = -1;
    }

    /** One of your own crew shouting from your boat. Never talks over itself. */
    private void sayMate(int seed) {
        if (mates.isEmpty() || sessionSeconds < mateBubbleUntil) {
            return;
        }
        mateBubble = MATE_LINES[Math.abs(seed * 7 + mates.size() * 3) % MATE_LINES.length];
        mateBubbleUntil = sessionSeconds + 2.2;
    }

    private int nearestCrew(double you) {
        int best = -1;
        double bestGap = Double.MAX_VALUE;
        for (int i = 0; i < crewCount(); i++) {
            double g = Math.abs(crewDistance(i) - you);
            if (g < bestGap) {
                bestGap = g;
                best = i;
            }
        }
        return best;
    }

    /** Current chevrons and wind streaks in each lane: the water you are choosing between. */
    private void drawLaneWater(Canvas c, float w, float waterTop, float waterBottom, double you, float ppm, float dt) {
        scenePaint.setStrokeCap(Paint.Cap.ROUND);
        float step = dp(90f);
        double scroll = you * ppm;
        for (int l = 0; l < LANES; l++) {
            float y = laneY(l, waterTop, waterBottom);
            float cur = laneCurrent(l);
            float strength = Math.min(1f, Math.abs(cur) / CURRENT_MAX);
            // The chevrons drift with the current relative to the water, fast enough to read.
            chevronDrift[l] += (cur / CURRENT_MAX) * dp(70f) * dt;
            float off = (float) (((scroll - chevronDrift[l]) % step + step) % step);
            int alpha = (int) (30 + 110 * strength);
            scenePaint.setColor((alpha << 24) | (cur >= 0 ? 0x0035D0BA : 0x00F0655D));
            scenePaint.setStrokeWidth(dp(2.2f));
            float dir = cur >= 0 ? 1f : -1f;
            float arm = dp(6f);
            for (float x = -off; x < w + step; x += step) {
                for (int row = -1; row <= 1; row += 2) {
                    float cy = y + row * dp(46f);
                    c.drawLine(x - dir * arm, cy - arm * 0.8f, x, cy, scenePaint);
                    c.drawLine(x - dir * arm, cy + arm * 0.8f, x, cy, scenePaint);
                }
            }
            // Wind: streaks racing across the lane in its direction, brighter as it blows harder.
            float wind = laneWind(l);
            float ws = Math.min(1f, Math.abs(wind) / WIND_MAX);
            if (ws > 0.15f) {
                scenePaint.setColor(((int) (90 * ws) << 24) | 0x00E6EDF7);
                scenePaint.setStrokeWidth(dp(1.3f));
                float len = dp(26f) + dp(30f) * ws;
                for (int k = 0; k < 4; k++) {
                    double travel = sessionSeconds * dp(420f) * (0.5 + 0.5 * ws) + k * w * 0.29 + l * dp(173f);
                    float x = (float) (travel % (w + len));
                    if (wind < 0) {
                        x = w - x;
                    }
                    float sy = y + (k - 1.5f) * dp(16f) + (float) Math.sin(sessionSeconds * 3 + k + l) * dp(3f);
                    c.drawLine(x, sy, x - Math.signum(wind) * len, sy, scenePaint);
                }
            }
        }
        scenePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** The lane's water on the left: current plus wind as one figure, with a nudge toward the best lane. */
    private void drawLaneTags(Canvas c, float waterTop, float waterBottom) {
        if (state == State.FINISHED) {
            return;
        }
        int best = 0;
        for (int l = 1; l < LANES; l++) {
            if (laneBoost(l) > laneBoost(best)) {
                best = l;
            }
        }
        boolean worthIt = best != youLane && laneBoost(best) - laneBoost(youLane) > 0.02f;
        if (worthIt) {
            if (laneHintSince < 0) {
                laneHintSince = sessionSeconds;
            }
            if (!laneHintShown && sessionSeconds - laneHintSince > 3 && sessionSeconds > calloutUntil) {
                laneHintShown = true;
                callout = "FASTER WATER IN LANE " + (best + 1) + " - TAP IT";
                calloutColor = ACCENT;
                calloutUntil = sessionSeconds + 2.6;
            }
        } else {
            laneHintSince = -1;
        }
        for (int l = 0; l < LANES; l++) {
            float y = laneY(l, waterTop, waterBottom);
            float b = laneBoost(l);
            rect.set(dp(6f), y - dp(12f), dp(64f), y + dp(12f));
            scenePaint.setStyle(Paint.Style.FILL);
            scenePaint.setColor(l == youLane ? 0xCC0A2A2A : 0xAA0A0E14);
            c.drawRoundRect(rect, dp(6f), dp(6f), scenePaint);
            if (worthIt && l == best) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 8);
                scenePaint.setStyle(Paint.Style.STROKE);
                scenePaint.setStrokeWidth(dp(2f));
                scenePaint.setColor(((int) (120 + 135 * pulse) << 24) | 0x00F5C518);
                rect.inset(-dp(2f), -dp(2f));
                c.drawRoundRect(rect, dp(7f), dp(7f), scenePaint);
                scenePaint.setStyle(Paint.Style.FILL);
                bold(c, "TAP", dp(70f), y + dp(4f), 10f, WARN, Paint.Align.LEFT);
            }
            bold(c, boostText(b), dp(35f), y + dp(4f), 11f, b >= 0 ? ACCENT : BAD, Paint.Align.CENTER);
            label(c, LANE_NAME[l], dp(35f), y - dp(15f), 8f, FAINT, Paint.Align.CENTER);
        }
    }

    private void drawBubble(Canvas c, float w, float waterTop, float waterBottom) {
        if (bubbleCrew < 0 || bubbleCrew >= crewCount() || sessionSeconds >= bubbleUntil || state == State.FINISHED) {
            return;
        }
        float x = Math.max(dp(150f), Math.min(w - dp(150f), crewX[bubbleCrew]));
        float y = laneY(crewLanePos[bubbleCrew], waterTop, waterBottom) - dp(40f);
        float half = bubblePaint.measureText(bubble) / 2f + dp(10f);
        float life = (float) (bubbleUntil - sessionSeconds);
        float pop = Math.min(1f, (2.8f - life) * 8f);
        int col = crewColor(bubbleCrew);
        rect.set(x - half * pop, y - dp(24f), x + half * pop, y);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0xF2F4F4F4);
        c.drawRoundRect(rect, dp(9f), dp(9f), scenePaint);
        c.drawCircle(crewX[bubbleCrew] < x - dp(20f) ? x - dp(12f) : x + dp(8f), y + dp(4f), dp(4f), scenePaint);
        scenePaint.setStyle(Paint.Style.STROKE);
        scenePaint.setStrokeWidth(dp(2f));
        scenePaint.setColor(col);
        c.drawRoundRect(rect, dp(9f), dp(9f), scenePaint);
        scenePaint.setStyle(Paint.Style.FILL);
        if (pop >= 1f) {
            bubblePaint.setColor(0xFF111111);
            c.drawText(bubble, x, y - dp(8f), bubblePaint);
        }
    }

    /** The crews that have joined you, rowing behind you in your own boat. */
    private void drawMates(Canvas c, float x, float y) {
        int n = mates.size();
        if (n == 0) {
            return;
        }
        float ph = strokePhase();
        scenePaint.setStyle(Paint.Style.FILL);
        for (int m = 0; m < n; m++) {
            float mx = x - dp(24f) - m * dp(19f) + ph * dp(5f);
            float my = y - dp(1f) + (float) Math.sin(sessionSeconds * 3 + m) * dp(1.5f);
            // The oar first, so the rower sits over it.
            float a = (float) (Math.PI * (0.22 + 0.48 * ph));
            scenePaint.setStrokeWidth(dp(2f));
            scenePaint.setStyle(Paint.Style.STROKE);
            scenePaint.setColor(0xAAE6EDF7);
            c.drawLine(mx, my, mx - (float) Math.cos(a) * dp(17f), my + (float) Math.sin(a) * dp(17f), scenePaint);
            scenePaint.setStyle(Paint.Style.FILL);
            scenePaint.setColor(0xFF0B1322);
            c.drawCircle(mx, my, dp(6.5f), scenePaint);
            scenePaint.setColor(MATE_COLORS[m % MATE_COLORS.length]);
            c.drawCircle(mx, my, dp(5f), scenePaint);
        }
    }

    /** A shout from your own boat: your crew, at the pushes and the sprint. */
    private void drawMateBubble(Canvas c, float x, float y) {
        if (mates.isEmpty() || sessionSeconds >= mateBubbleUntil || state == State.FINISHED) {
            return;
        }
        float bx = x - dp(40f);
        float by = y - dp(46f);
        float half = bubblePaint.measureText(mateBubble) / 2f + dp(9f);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0xF2E8FBF6);
        rect.set(bx - half, by - dp(22f), bx + half, by);
        c.drawRoundRect(rect, dp(9f), dp(9f), scenePaint);
        c.drawCircle(bx + dp(10f), by + dp(4f), dp(4f), scenePaint);
        scenePaint.setStyle(Paint.Style.STROKE);
        scenePaint.setStrokeWidth(dp(2f));
        scenePaint.setColor(ACCENT);
        c.drawRoundRect(rect, dp(9f), dp(9f), scenePaint);
        scenePaint.setStyle(Paint.Style.FILL);
        bubblePaint.setColor(0xFF10312A);
        c.drawText(mateBubble, bx, by - dp(7f), bubblePaint);
    }

    /**
     * The push prompt: a crew is making a move, and you have eighteen seconds to answer it. The bar
     * fills from your own average at the moment it opened to four percent above it.
     */
    private void drawPush(Canvas c, float w, float hudH) {
        if (state != State.RACING) {
            return;
        }
        float cx = w / 2f;
        // Sits in the band between the gap figure and the water, clear of the boats.
        float y = hudH - dp(58f);
        if (pushCrew < 0) {
            if (sessionSeconds < pushResultUntil) {
                bold(c, pushResult, cx, y + dp(20f), 16f, pushResultGood ? ACCENT : BAD, Paint.Align.CENTER);
            }
            return;
        }
        int crew = Math.min(pushCrew, crewCount() - 1);
        float left = (float) Math.max(0, pushUntil - sessionSeconds);
        float avg = pushTime > 0.3 ? (float) (pushSum / pushTime) : 0f;
        float fill = Math.max(0f, Math.min(1f, (float) ((avg / pushBase - 1.0) / 0.04)));
        float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 9);
        rect.set(cx - dp(165f), y, cx + dp(165f), y + dp(54f));
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0xD90A0E14);
        c.drawRoundRect(rect, dp(10f), dp(10f), scenePaint);
        scenePaint.setStyle(Paint.Style.STROKE);
        scenePaint.setStrokeWidth(dp(2f));
        scenePaint.setColor((((int) (130 + 120 * pulse)) << 24) | (crewColor(crew) & 0x00FFFFFF));
        c.drawRoundRect(rect, dp(10f), dp(10f), scenePaint);
        scenePaint.setStyle(Paint.Style.FILL);
        bold(c, pushTitle, cx, y + dp(17f), 15f * (1f + 0.03f * pulse), crewColor(crew), Paint.Align.CENTER);
        float bw = dp(290f);
        float bx = cx - bw / 2f;
        float by = y + dp(25f);
        scenePaint.setColor(0x44FFFFFF);
        c.drawRect(bx, by, bx + bw, by + dp(8f), scenePaint);
        scenePaint.setColor(fill >= 1f ? 0xFF35D0BA : fill > 0.4f ? 0xFFF5C518 : 0xFFF0655D);
        c.drawRect(bx, by, bx + bw * fill, by + dp(8f), scenePaint);
        // The window running out, under the response bar.
        scenePaint.setColor(0x66FFFFFF);
        c.drawRect(bx, by + dp(11f), bx + bw * (left / PUSH_SECONDS), by + dp(13f), scenePaint);
        label(c, fill >= 1f ? "THAT'S IT - HOLD IT" : "LIFT YOUR SPEED  ·  " + Math.round(left) + " s",
              cx, y + dp(49f), 9.5f, TEXT, Paint.Align.CENTER);
    }

    /** The handicap chip, the week's series pips and the crew strip, across the top of the HUD. */
    private void drawRaceChips(Canvas c, float w, float dt) {
        if (state == State.FINISHED) {
            return;
        }
        float cx = w * 0.42f;
        float y0 = dp(8f);
        handicapShown += (handicap() - handicapShown) * Math.min(1f, 4f * dt);
        rect.set(cx - dp(78f), y0, cx + dp(78f), y0 + dp(30f));
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(handicapOn ? 0xCC0E2630 : 0xAA161A22);
        c.drawRoundRect(rect, dp(8f), dp(8f), scenePaint);
        scenePaint.setStyle(Paint.Style.STROKE);
        scenePaint.setStrokeWidth(dp(1.5f));
        scenePaint.setColor(handicapOn ? 0x8835D0BA : 0x554A5568);
        c.drawRoundRect(rect, dp(8f), dp(8f), scenePaint);
        scenePaint.setStyle(Paint.Style.FILL);
        String v = !handicapOn ? "LEVEL" : String.format(java.util.Locale.US, "%s%.0f m",
                handicapShown >= 0 ? "+" : "−", Math.abs(handicapShown));
        bold(c, v, cx, y0 + dp(15f), 13f, handicapOn ? (handicap() >= 0 ? WARN : ACCENT) : DIM, Paint.Align.CENTER);
        label(c, handicapOn ? "FIELD'S HANDICAP  ·  TAP" : "HANDICAP OFF  ·  TAP", cx, y0 + dp(26f), 8f,
                FAINT, Paint.Align.CENTER);

        // Best of three, this week.
        float sx = w * 0.58f;
        drawSeriesPips(c, sx, y0 + dp(13f), false);
        label(c, seriesLine, sx, y0 + dp(26f), 8f, seriesDecided ? (seriesYou >= 2 ? ACCENT : BAD) : FAINT,
                Paint.Align.CENTER);
        if (!mateStrip.isEmpty()) {
            label(c, mateStrip, cx, y0 + dp(38f), 8.5f, ACCENT, Paint.Align.CENTER);
        }
    }

    /** YOU ●●○ against THEM ●○○, with the pip just won popping at the finish. */
    private void drawSeriesPips(Canvas c, float cx, float y, boolean big) {
        float r = big ? dp(8f) : dp(4.5f);
        float step = big ? dp(22f) : dp(12f);
        float inset = big ? dp(46f) : dp(26f);
        label(c, "YOU", cx - inset - step * 2.6f, y + r * 0.6f, big ? 12f : 8f, DIM, Paint.Align.RIGHT);
        label(c, "FIELD", cx + inset + step * 2.6f, y + r * 0.6f, big ? 12f : 8f, DIM, Paint.Align.LEFT);
        scenePaint.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 3; k++) {
            for (int side = 0; side < 2; side++) {
                boolean won = side == 0 ? k < seriesYou : k < seriesThem;
                float x = side == 0 ? cx - inset - step * (2 - k) : cx + inset + step * k;
                boolean pop = state == State.FINISHED && seriesPipPop == k && (side == 0) == seriesPipMine
                        && won;
                float rr = pop ? r * (1f + 0.35f * (float) Math.abs(Math.sin(sessionSeconds * 6))) : r;
                scenePaint.setColor(won ? (side == 0 ? ACCENT : BAD) : 0x33FFFFFF);
                c.drawCircle(x, y, rr, scenePaint);
                if (!won) {
                    scenePaint.setStyle(Paint.Style.STROKE);
                    scenePaint.setStrokeWidth(dp(1.2f));
                    scenePaint.setColor(0x55FFFFFF);
                    c.drawCircle(x, y, rr, scenePaint);
                    scenePaint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    /** The last 250 m: gold pulses at the lane edges and speed lines once you are pushing. */
    private void drawSprintWater(Canvas c, float w, float waterTop, float waterBottom, float speed) {
        sprintFlash = Math.max(0f, sprintFlash - 0.02f);
        float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6);
        int a = (int) (70 + 100 * pulse + 80 * sprintFlash);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor((Math.min(255, a) << 24) | 0x00F5C518);
        c.drawRect(0, waterTop, w, waterTop + dp(5f), scenePaint);
        c.drawRect(0, waterBottom - dp(5f), w, waterBottom, scenePaint);
        if (speed > profile.typicalSpeed()) {
            c.save();
            c.clipRect(0, waterTop, w, waterBottom);
            Fx.speedLines(c, scenePaint, w, waterBottom, speed + 0.8f, sessionSeconds, dp(1f));
            c.restore();
        }
    }

    private void drawHud(Canvas c, float w, float h, float hudH, float speed, double gap, int lead) {
        String gapText;
        int gapColor;
        if (state == State.READY) {
            gapText = "TAKE A STROKE";
            gapColor = DIM;
        } else {
            gapText = String.format(java.util.Locale.US, "%s%.0f m", gap >= 0 ? "+" : "−", Math.abs(gap));
            gapColor = gap >= 0 ? ACCENT : BAD;
        }
        bold(c, gapText, w / 2f, hudH * 0.55f, state == State.READY ? 22f : 44f, gapColor, Paint.Align.CENTER);
        String under;
        if (state == State.READY) {
            under = "the clock starts when you do  ·  tap a lane to choose your water";
        } else if (crewCount() > 1) {
            under = gap >= 0 ? "LEADING THE FIELD" : "BEHIND " + crewName(lead);
        } else {
            under = gap >= 0 ? "AHEAD" : "BEHIND";
        }
        label(c, under, w / 2f, hudH * 0.55f + dp(20f), 10f, FAINT, Paint.Align.CENTER);

        if (sprinting && state == State.RACING) {
            drawSprintHud(c, w, hudH, speed);
        } else if (sessionSeconds < splitBannerUntil && state == State.RACING
                // The push panel is drawn last and lands in this same band: a live call owns it.
                && pushCrew < 0 && sessionSeconds >= pushResultUntil) {
            bold(c, splitBanner, w / 2f, hudH * 0.55f + dp(46f), 16f, WARN, Paint.Align.CENTER);
            label(c, splitBannerSub, w / 2f, hudH * 0.55f + dp(62f), 10f, TEXT, Paint.Align.CENTER);
        }

        bold(c, pace(speed), dp(16f), hudH * 0.5f, 22f, TEXT, Paint.Align.LEFT);
        label(c, "YOUR PACE /500", dp(16f), hudH * 0.5f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, rightHudValue(), w - dp(16f), hudH * 0.5f, 22f, BLUE, Paint.Align.RIGHT);
        label(c, rightHudCaption(), w - dp(16f), hudH * 0.5f + dp(16f), 9f, FAINT, Paint.Align.RIGHT);

        drawSplitTable(c, w, hudH);
        if (crewCount() > 1) {
            drawStandings(c, w, hudH);
        }

        // Footer stats.
        float fy = h - dp(14f);
        float col = w / 5f;
        stat(c, col * 0.5f, fy, String.format(java.util.Locale.US, "%.0f", raceDistance()),
                "OF " + raceMeters + " M");
        stat(c, col * 1.5f, fy, clock(raceTime()), "TIME");
        stat(c, col * 2.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 3.5f, fy, status == null ? "0" : String.valueOf(status.watts), "WATTS");
        stat(c, col * 4.5f, fy, boostText(boostAt(youLanePos)), LANE_WATER[youLane]);
    }

    /** Countdown, how hard you are pushing against your own range, and the one boat in reach. */
    private void drawSprintHud(Canvas c, float w, float hudH, float speed) {
        float left = (float) Math.max(0, raceMeters - raceDistance());
        float y = hudH * 0.55f + dp(46f);
        float pulse = 1f + 0.06f * (float) Math.sin(sessionSeconds * 8);
        bold(c, String.format(java.util.Locale.US, "SPRINT  ·  %.0f m TO GO", left), w / 2f, y, 17f * pulse, WARN,
                Paint.Align.CENTER);
        // The bar fills from your usual speed to a little past your top end.
        double lo = profile.typicalSpeed();
        double hi = profile.highSpeed() * 1.03;
        float fill = (float) Math.max(0, Math.min(1, (speed - lo) / Math.max(0.1, hi - lo)));
        float bw = dp(220f);
        float bx = w / 2f - bw / 2f;
        float by = y + dp(8f);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x55FFFFFF);
        c.drawRect(bx, by, bx + bw, by + dp(7f), scenePaint);
        scenePaint.setColor(fill >= 1f ? 0xFFF5C518 : fill > 0.5f ? 0xFF35D0BA : 0xFF6F8CFF);
        c.drawRect(bx, by, bx + bw * fill, by + dp(7f), scenePaint);
        // The target: the nearest crew ahead to catch, else the nearest behind to hold off.
        double you = raceDistance();
        int ahead = -1;
        int behind = -1;
        for (int i = 0; i < crewCount(); i++) {
            double g = crewDistance(i) - you;
            if (g >= 0 && crewFinish[i] < 0 && (ahead < 0 || g < crewDistance(ahead) - you)) {
                ahead = i;
            } else if (g < 0 && (behind < 0 || g > crewDistance(behind) - you)) {
                behind = i;
            }
        }
        String target = fill >= 1f ? "FLAT OUT!" : "PUSH PAST " + pace(hi);
        if (ahead >= 0 && crewDistance(ahead) - you < 40) {
            target = String.format(java.util.Locale.US, "CATCH %s  ·  %.0f m", crewName(ahead), crewDistance(ahead) - you);
        } else if (ahead < 0 && behind >= 0 && you - crewDistance(behind) < 30) {
            target = String.format(java.util.Locale.US, "HOLD OFF %s  ·  %.0f m", crewName(behind), you - crewDistance(behind));
        }
        label(c, target, w / 2f, by + dp(20f), 10f, TEXT, Paint.Align.CENTER);
    }

    private void drawSplitTable(Canvas c, float w, float hudH) {
        int n = splitCount();
        if (n < 2) {
            return;
        }
        float x = w * 0.14f;
        float y = dp(22f);
        label(c, "SPLITS  /500   vs FASTEST", x, y, 9f, FAINT, Paint.Align.LEFT);
        int shown = 0;
        // Six rows ending at the marker you are rowing toward, so the live 'to go' row is always shown.
        int first = Math.max(1, Math.min(youNextSplit, n) - 5);
        for (int k = first; k <= n && shown < 6; k++, shown++) {
            float ry = y + dp(16f) * (shown + 1);
            if (splitRows[k] != null) {
                label(c, splitRows[k], x, ry, 11f, splitRowColor[k], Paint.Align.LEFT);
            } else if (k == youNextSplit && state == State.RACING) {
                double toGo = k * 500.0 - raceDistance();
                label(c, String.format(java.util.Locale.US, "%5d m   %.0f m to go", k * 500, toGo), x, ry, 11f, DIM,
                        Paint.Align.LEFT);
            } else {
                label(c, String.format(java.util.Locale.US, "%5d m", k * 500), x, ry, 11f, FAINT, Paint.Align.LEFT);
            }
        }
    }

    /** The field in order, with each crew's gap to you. */
    private void drawStandings(Canvas c, float w, float hudH) {
        int n = Math.min(MAX_CREWS, crewCount());
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        for (int i = 1; i < n; i++) {
            int v = order[i];
            int j = i - 1;
            while (j >= 0 && crewDistance(order[j]) < crewDistance(v)) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
        double you = raceDistance();
        float x = w * 0.68f;
        float y = dp(22f);
        label(c, "STANDINGS", x, y, 9f, FAINT, Paint.Align.LEFT);
        int row = 1;
        boolean youDrawn = false;
        for (int r = 0; r <= n; r++) {
            float ry = y + dp(17f) * row;
            if (!youDrawn && (r == n || you >= crewDistance(order[r]))) {
                bold(c, row + "  YOU", x, ry, 12f, ACCENT, Paint.Align.LEFT);
                youDrawn = true;
                row++;
                r--;
                continue;
            }
            if (r == n) {
                break;
            }
            int i = order[r];
            double g = crewDistance(i) - you;
            bold(c, row + "  " + crewName(i), x, ry, 12f, crewColor(i), Paint.Align.LEFT);
            label(c, String.format(java.util.Locale.US, "%s%.0f m", g >= 0 ? "+" : "−", Math.abs(g)),
                    x + dp(150f), ry, 11f, g >= 0 ? BAD : DIM, Paint.Align.LEFT);
            row++;
        }
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 18f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    /** Places, margins, splits and a last word from the field - worked out once, drawn every frame. */
    private void buildResults(int crews) {
        finishPlace = 1;
        int lead = -1;
        int beaten = -1;
        for (int i = 0; i < crews; i++) {
            resultTime[i] = crewFinishTime(i);
            if (resultTime[i] < finishTime) {
                finishPlace++;
                if (lead < 0 || resultTime[i] < resultTime[lead]) {
                    lead = i;
                }
            } else if (beaten < 0 || resultTime[i] < resultTime[beaten]) {
                beaten = i;
            }
        }
        resultWon = finishPlace == 1;
        resultTitle = resultWon ? winText() : loseText();
        if (crews > 1 && !resultWon) {
            resultTitle = placeText(finishPlace) + " OF " + (crews + 1) + "  ·  " + resultTitle;
        }
        int ref = lead >= 0 ? lead : beaten;
        double margin = ref >= 0 ? finishTime - resultTime[ref] : 0;
        resultMargin = String.format(java.util.Locale.US, "%s %s by %.1f s   ·   %s /500 average",
                margin <= 0 ? "ahead of" : "behind", ref >= 0 ? crewName(ref) : "", Math.abs(margin),
                PersonalBests.formatPace((float) (finishTime / (raceMeters / 500.0))));
        for (int i = 0; i < crews; i++) {
            double d = resultTime[i] - finishTime;
            resultRows[i] = String.format(java.util.Locale.US, "%s   %s   (%s%.1f s)", crewName(i),
                    clock(resultTime[i]), d >= 0 ? "+" : "−", Math.abs(d));
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 1; k <= splitCount(); k++) {
            if (youSplit[k] >= 0) {
                if (sb.length() > 0) {
                    sb.append("  ·  ");
                }
                sb.append(PersonalBests.formatPace((float) (youSplit[k] - (k > 1 ? youSplit[k - 1] : 0))));
            }
        }
        resultSplits = sb.length() > 0 && splitCount() > 1 ? "SPLITS  " + sb : "";
        // The crew that beat you gloats; if nobody did, the closest one concedes.
        resultQuote = "";
        int speaker = lead >= 0 ? lead : beaten;
        if (speaker >= 0) {
            String line = taunt(speaker, lead >= 0 ? SAY_WON : SAY_LOST);
            if (line != null) {
                resultQuote = crewName(speaker) + ":  \"" + line + "\"";
            }
        }
        if (pushCrew >= 0) {
            endPush();
        }
        scoreSeries();
        adjustHandicap(crews);
        recruit(crews);
        // What the field will start with next time: the learned stagger plus the crew, the new mate
        // included. mateMetres itself is left alone so this race's positions and replay do not move.
        nextFieldMetres = handicapNext + mates.size() * MATE_METRES;
        buildAnalysis(crews);
    }

    /* ---------- the week's best-of-three ---------- */

    private void scoreSeries() {
        if (seriesDecided) {
            return;
        }
        if (resultWon) {
            seriesYou++;
            seriesPipPop = seriesYou - 1;
            seriesPipMine = true;
        } else {
            seriesThem++;
            seriesPipPop = seriesThem - 1;
            seriesPipMine = false;
        }
        bests.putString(seriesKey(), seriesWeek + "|" + seriesYou + "|" + seriesThem);
        seriesDecided = seriesYou >= 2 || seriesThem >= 2;
        if (seriesDecided) {
            if (seriesYou >= 2) {
                bests.putFloat("race.series.wins", bests.get("race.series.wins", 0f) + 1f);
                seriesLine = "SERIES WON  " + seriesYou + " - " + seriesThem;
            } else {
                seriesLine = "SERIES LOST  " + seriesYou + " - " + seriesThem;
            }
        } else {
            seriesLine = "SERIES  " + seriesYou + " - " + seriesThem + "  ·  RACE "
                    + Math.min(3, seriesYou + seriesThem + 1) + " OF 3";
        }
    }

    /** True while the last race decided the week, for the finish screen's banner. */
    private boolean seriesJustDecided() {
        return seriesDecided && seriesPipPop >= 0;
    }

    /* ---------- the learning handicap ---------- */

    /**
     * Moves the stagger by 60% of the margin of this race, converted to metres at the speed you
     * actually rowed. Winning by ten seconds hands the field about six seconds of it back next time,
     * so the race converges on a finish you can see rather than on a procession either way.
     */
    private void adjustHandicap(int crews) {
        // A race run level measured the raw gap, not the gap the stagger leaves. Feeding that margin
        // into a stagger it was not rowed against would move it by the whole handicap twice over.
        if (!handicapOn) {
            handicapNext = handicapMetres;
            return;
        }
        double best = -1;
        for (int i = 0; i < crews; i++) {
            if (best < 0 || resultTime[i] < best) {
                best = resultTime[i];
            }
        }
        if (best < 0 || finishTime <= 0) {
            handicapNext = handicapMetres;
            return;
        }
        double yourSpeed = raceMeters / finishTime;
        handicapNext = clampHandicap((float) (handicapMetres + 0.6 * (best - finishTime) * yourSpeed));
        bests.putFloat(handicapKey(), handicapNext);
    }

    /* ---------- the crew ---------- */

    private void recruit(int crews) {
        recruited = null;
        recruitAnim = 0;
        if (mates.size() >= MAX_MATES) {
            return;
        }
        // One at a time: a joining is a moment, not a list.
        for (int i = 0; i < crews; i++) {
            if (crewRecruitable(i) && resultTime[i] > finishTime && !mates.contains(crewName(i))) {
                mates.add(crewName(i));
                recruited = crewName(i);
                saveMates();
                return;
            }
        }
    }

    /* ---------- the split-by-split page ---------- */

    /**
     * The reference crew and its 500 m times. A recording is preferred - that is the boat you are
     * really measuring yourself against - otherwise the fastest crew. Markers a crew had not reached
     * when you finished are projected from where it was and how fast it was going, and flagged.
     */
    private void buildAnalysis(int crews) {
        analysisCrew = -1;
        for (int i = 0; i < crews; i++) {
            if (crewIsRecording(i)) {
                analysisCrew = i;
                break;
            }
        }
        if (analysisCrew < 0) {
            for (int i = 0; i < crews; i++) {
                if (analysisCrew < 0 || resultTime[i] < resultTime[analysisCrew]) {
                    analysisCrew = i;
                }
            }
        }
        analysisWon = "";
        analysisLost = "";
        if (analysisCrew < 0) {
            return;
        }
        int n = splitCount();
        double now = finishTime;
        double dNow = crewDistance(analysisCrew);
        double sNow = Math.max(0.5, crewSpeed(analysisCrew));
        float fieldStart = Math.max(0f, handicap());
        for (int k = 1; k <= n; k++) {
            // Segment k runs from (k-1)*500 to k*500. If it starts behind the field's start line the
            // crew never rowed it, so it is not a segment either boat can be judged on.
            analysisPreStart[k] = (k - 1) * 500.0 < fieldStart;
            double t = crewSplit[analysisCrew][k];
            if (t >= 0) {
                analysisSplit[k] = t;
                analysisProjected[k] = false;
            } else if (k * 500.0 <= fieldStart) {
                analysisSplit[k] = 0;             // already past it at the gun
                analysisProjected[k] = false;
            } else {
                analysisSplit[k] = now + (k * 500.0 - dNow) / sNow;
                analysisProjected[k] = true;
            }
        }
        // Where the race turned: the segment you took most out of them, and the one that cost most.
        double bestGain = 0;
        double worstLoss = 0;
        for (int k = 1; k <= n; k++) {
            if (youSplit[k] < 0 || analysisPreStart[k]) {
                continue;
            }
            double yours = youSplit[k] - (k > 1 ? youSplit[k - 1] : 0);
            double theirs = analysisSplit[k] - (k > 1 ? analysisSplit[k - 1] : 0);
            double d = theirs - yours;
            if (d > bestGain) {
                bestGain = d;
                analysisWon = String.format(java.util.Locale.US, "BEST 500: %d–%d m, %.1f s taken",
                        (k - 1) * 500, k * 500, d);
            }
            if (-d > worstLoss) {
                worstLoss = -d;
                analysisLost = String.format(java.util.Locale.US, "WORST 500: %d–%d m, %.1f s lost",
                        (k - 1) * 500, k * 500, -d);
            }
        }
    }

    private void drawFinish(Canvas c, float w, float h, float dt) {
        accentPaint.setColor(0xCC0A0E14);
        c.drawRect(0, 0, w, h, accentPaint);
        accentPaint.setColor(ACCENT);
        int crews = Math.min(MAX_CREWS, crewCount());
        bold(c, resultTitle, w / 2f, h * 0.20f, 24f, resultWon ? ACCENT : BAD, Paint.Align.CENTER);
        bold(c, clock(finishTime), w / 2f, h * 0.32f, 46f, TEXT, Paint.Align.CENTER);
        label(c, resultMargin, w / 2f, h * 0.32f + dp(24f), 12f, DIM, Paint.Align.CENTER);
        float y = h * 0.32f + dp(52f);
        if (crews > 1) {
            for (int i = 0; i < crews; i++) {
                label(c, resultRows[i], w / 2f, y, 12f, crewColor(i), Paint.Align.CENTER);
                y += dp(18f);
            }
        }
        if (!resultSplits.isEmpty()) {
            label(c, resultSplits, w / 2f, y + dp(8f), 12f, WARN, Paint.Align.CENTER);
            y += dp(26f);
        }
        if (newBest) {
            bold(c, "NEW PERSONAL BEST", w / 2f, y + dp(18f), 16f, WARN, Paint.Align.CENTER);
        } else if (bests.has("time." + raceMeters)) {
            label(c, "best " + clock(bests.get("time." + raceMeters, 0)), w / 2f, y + dp(18f), 12f,
                    FAINT, Paint.Align.CENTER);
        }
        if (!resultQuote.isEmpty()) {
            label(c, resultQuote, w / 2f, y + dp(44f), 13f, TEXT, Paint.Align.CENTER);
        }

        // The week's series, then the handicap the next race will carry.
        float sy = h * 0.70f;
        drawSeriesPips(c, w / 2f, sy, true);
        bold(c, seriesLine, w / 2f, sy + dp(30f), seriesJustDecided() ? 20f : 14f,
                seriesDecided ? (seriesYou >= 2 ? ACCENT : BAD) : WARN, Paint.Align.CENTER);
        String hcp = String.format(java.util.Locale.US, "NEXT RACE: THE FIELD STARTS %.0f m %s%s",
                Math.abs(nextFieldMetres), nextFieldMetres >= 0 ? "UP" : "BACK",
                handicapOn ? "" : "  (HANDICAP OFF)");
        label(c, hcp, w / 2f, sy + dp(48f), 11f, DIM, Paint.Align.CENTER);
        if (pushesCalled > 0) {
            label(c, "PUSHES ANSWERED  " + pushesAnswered + " OF " + pushesCalled, w / 2f, sy + dp(64f), 11f,
                    pushesAnswered * 2 >= pushesCalled ? ACCENT : BAD, Paint.Align.CENTER);
        }

        // A crew joining: their boat pulls alongside and the rower comes aboard.
        if (recruited != null) {
            recruitAnim = Math.min(1.0, recruitAnim + dt * 0.7);
            float ry = h * 0.885f;
            float ease = (float) (1 - Math.pow(1 - recruitAnim, 3));
            float mx = w / 2f - dp(150f) + dp(120f) * ease;
            accentPaint.setColor(ACCENT);
            Fx.glow(c, mx, ry, dp(34f), 0x5535D0BA);
            scenePaint.setStyle(Paint.Style.FILL);
            scenePaint.setColor(MATE_COLORS[(mates.size() - 1) % MATE_COLORS.length]);
            c.drawCircle(mx, ry - (float) Math.abs(Math.sin(recruitAnim * Math.PI)) * dp(22f), dp(7f), scenePaint);
            bold(c, recruited + " JOIN YOUR CREW", w / 2f + dp(30f), ry + dp(5f), 17f, ACCENT, Paint.Align.LEFT);
        }
        label(c, splitCount() >= 1 ? "tap for the split-by-split" : "tap to row again", w / 2f, h * 0.955f,
                11f, FAINT, Paint.Align.CENTER);
    }

    /* ---------- page two: split by split, and a replay ---------- */

    /**
     * Every 500 m against the reference crew, with a bar for the seconds taken or lost, the segment
     * that won it and the segment that cost it, and a 12x replay of the two boats down the course.
     */
    private void drawSplitAnalysis(Canvas c, float w, float h, float dt) {
        analysisT += dt;
        accentPaint.setColor(0xF20A0E14);
        c.drawRect(0, 0, w, h, accentPaint);
        String who = analysisCrew >= 0 ? crewName(analysisCrew) : "THE FIELD";
        bold(c, "SPLIT BY SPLIT  vs  " + who, w / 2f, h * 0.085f, 21f, ACCENT, Paint.Align.CENTER);
        label(c, String.format(java.util.Locale.US, "%s  ·  %s   your %s, theirs %s", raceMeters + " m",
                        handicapOn ? "handicap " + Math.round(handicap()) + " m" : "level start",
                        clock(finishTime), analysisCrew >= 0 ? clock(resultTime[analysisCrew]) : "--:--"),
                w / 2f, h * 0.085f + dp(18f), 11f, DIM, Paint.Align.CENTER);

        int n = splitCount();
        float top = h * 0.20f;
        float rowH = Math.min(dp(30f), (h * 0.46f) / Math.max(1, n));
        float midX = w * 0.64f;
        float perSecond = dp(26f);      // a second of margin is 26dp of bar
        label(c, "YOURS", w * 0.30f, top, 9f, FAINT, Paint.Align.RIGHT);
        label(c, "THEIRS", w * 0.42f, top, 9f, FAINT, Paint.Align.RIGHT);
        label(c, "LOST", midX - dp(40f), top, 9f, FAINT, Paint.Align.RIGHT);
        label(c, "TAKEN", midX + dp(40f), top, 9f, FAINT, Paint.Align.LEFT);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x33FFFFFF);
        c.drawRect(midX - dp(0.5f), top + dp(6f), midX + dp(0.5f), top + rowH * n + dp(14f), scenePaint);

        for (int k = 1; k <= n; k++) {
            float reveal = Math.max(0f, Math.min(1f, (analysisT - 0.13f * (k - 1)) * 3.5f));
            if (reveal <= 0f) {
                continue;
            }
            float y = top + rowH * k + dp(8f);
            double yours = youSplit[k] >= 0 ? youSplit[k] - (k > 1 ? youSplit[k - 1] : 0) : -1;
            double theirs = analysisCrew >= 0 && !analysisPreStart[k]
                    ? analysisSplit[k] - (k > 1 ? analysisSplit[k - 1] : 0) : -1;
            label(c, (k * 500) + " m", w * 0.18f, y, 11f, FAINT, Paint.Align.RIGHT);
            bold(c, yours > 0 ? PersonalBests.formatPace((float) yours) : "--:--", w * 0.30f, y, 13f, TEXT,
                    Paint.Align.RIGHT);
            String theirsText = analysisCrew < 0 ? "--:--"
                    : analysisPreStart[k] ? "HEAD START"
                    : theirs > 0 ? PersonalBests.formatPace((float) theirs) + (analysisProjected[k] ? "~" : "")
                    : "--:--";
            label(c, theirsText, w * 0.42f, y, 12f,
                    analysisCrew >= 0 && !analysisPreStart[k] ? crewColor(analysisCrew) : DIM, Paint.Align.RIGHT);
            if (yours <= 0 || theirs <= 0) {
                continue;
            }
            float d = (float) (theirs - yours);            // positive: you took time out of them
            float len = Math.min(w * 0.30f, Math.abs(d) * perSecond) * reveal;
            scenePaint.setColor(d >= 0 ? 0xFF35D0BA : 0xFFF0655D);
            if (d >= 0) {
                c.drawRect(midX, y - dp(9f), midX + len, y - dp(1f), scenePaint);
            } else {
                c.drawRect(midX - len, y - dp(9f), midX, y - dp(1f), scenePaint);
            }
            if (reveal > 0.6f) {
                label(c, String.format(java.util.Locale.US, "%s%.1f s", d >= 0 ? "+" : "−", Math.abs(d)),
                        d >= 0 ? midX + len + dp(6f) : midX - len - dp(6f), y, 10f, d >= 0 ? ACCENT : BAD,
                        d >= 0 ? Paint.Align.LEFT : Paint.Align.RIGHT);
            }
        }

        float sy = top + rowH * n + dp(34f);
        if (!analysisWon.isEmpty()) {
            label(c, analysisWon, w / 2f, sy, 12f, ACCENT, Paint.Align.CENTER);
        }
        if (!analysisLost.isEmpty()) {
            label(c, analysisLost, w / 2f, sy + dp(16f), 12f, BAD, Paint.Align.CENTER);
        }

        drawReplay(c, w, h, dt);
        label(c, "tap to row again", w / 2f, h * 0.965f, 11f, FAINT, Paint.Align.CENTER);
    }

    /** The two boats replayed along the course from the split times, twelve times faster. */
    private void drawReplay(Canvas c, float w, float h, float dt) {
        float ry = h * 0.855f;
        float x0 = w * 0.12f;
        float x1 = w * 0.88f;
        double crewFinishT = analysisCrew >= 0 ? resultTime[analysisCrew] : finishTime;
        double total = Math.max(finishTime, crewFinishT);
        replayT += dt * 12.0;
        if (replayT > total + 1.6) {
            replayT = 0;
        }
        label(c, "REPLAY  ·  12x", x0, ry - dp(30f), 9f, FAINT, Paint.Align.LEFT);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x33FFFFFF);
        c.drawRect(x0, ry - dp(1f), x1, ry + dp(1f), scenePaint);
        // 500 m marks and the finish post.
        for (int k = 1; k <= splitCount(); k++) {
            float mx = x0 + (x1 - x0) * (k * 500f / raceMeters);
            scenePaint.setColor(k * 500 == raceMeters ? 0xAAF5C518 : 0x44FFFFFF);
            c.drawRect(mx - dp(1f), ry - dp(9f), mx + dp(1f), ry + dp(9f), scenePaint);
        }
        double youM = replayMetres(youSplit, 0, finishTime, replayT);
        double crewM = analysisCrew >= 0 ? replayMetres(analysisSplit, handicap(), crewFinishT, replayT) : 0;
        float yx = x0 + (x1 - x0) * (float) Math.min(1, youM / raceMeters);
        float cxp = x0 + (x1 - x0) * (float) Math.min(1, crewM / raceMeters);
        // Your boat above the line, theirs below, each with a small wake.
        drawReplayBoat(c, yx, ry - dp(13f), ACCENT, "YOU");
        if (analysisCrew >= 0) {
            drawReplayBoat(c, cxp, ry + dp(13f), crewColor(analysisCrew), crewName(analysisCrew));
        }
        double leadM = youM - crewM;
        label(c, String.format(java.util.Locale.US, "%s   %s%.0f m", clock(replayT), leadM >= 0 ? "+" : "−",
                Math.abs(leadM)), x1, ry - dp(30f), 10f, leadM >= 0 ? ACCENT : BAD, Paint.Align.RIGHT);
    }

    private void drawReplayBoat(Canvas c, float x, float y, int color, String tag) {
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x44FFFFFF);
        c.drawRect(x - dp(22f), y - dp(1f), x, y + dp(1f), scenePaint);
        scenePaint.setColor(color);
        rect.set(x - dp(11f), y - dp(3.5f), x + dp(11f), y + dp(3.5f));
        c.drawRoundRect(rect, dp(3.5f), dp(3.5f), scenePaint);
        label(c, tag, x, y - dp(8f), 8f, color, Paint.Align.CENTER);
    }

    /**
     * Where a boat was at {@code t}, interpolated between its 500 m times - the only positions the
     * race actually recorded, so the replay is the race rather than an animation of it.
     */
    private double replayMetres(double[] splits, double startM, double finish, double t) {
        int n = splitCount();
        double prevT = 0;
        double prevM = startM;
        for (int k = 1; k <= n; k++) {
            double m = k * 500.0;
            if (m <= startM) {
                continue;       // behind this boat's start line: it never passed through here
            }
            double st = splits[k];
            if (st < 0) {
                break;
            }
            if (t <= st) {
                return prevM + (m - prevM) * (t - prevT) / Math.max(0.01, st - prevT);
            }
            prevT = st;
            prevM = m;
        }
        if (t < finish && finish > prevT) {
            return prevM + (raceMeters - prevM) * (t - prevT) / (finish - prevT);
        }
        return raceMeters;
    }

    private static String placeText(int place) {
        switch (place) {
            case 1:
                return "1ST";
            case 2:
                return "2ND";
            case 3:
                return "3RD";
            default:
                return place + "TH";
        }
    }
}
