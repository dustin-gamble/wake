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

    /* ---------- the 3.25.0 graphics pass: caches, so the frame allocates nothing ---------- */

    // Glass panels, one cached vertical gradient per slot; rebuilt only when a slot's edges move.
    private static final int P_GAP = 0;
    private static final int P_GAP_BANNER = 1;
    private static final int P_GAP_SPRINT = 2;
    private static final int P_PACE_L = 3;
    private static final int P_PACE_R = 4;
    private static final int P_SPLITS = 5;
    private static final int P_STANDINGS = 6;
    private static final int P_FOOTER = 7;
    private static final int P_CHIP = 8;
    private static final int P_PUSH = 9;
    private static final int P_FINISH = 10;
    private static final int P_ANALYSIS = 11;
    private static final int P_REPLAY = 12;
    private static final int P_LANE = 13;
    private static final int PANEL_SLOTS = P_LANE + LANES;
    private static final int GLASS_TOP = 0xC81A2638;
    private static final int GLASS_BOT = 0xD80A111C;
    private static final int GLASS_EDGE = 0x66324460;
    private final android.graphics.LinearGradient[] panelShader = new android.graphics.LinearGradient[PANEL_SLOTS];
    private final float[] panelT = new float[PANEL_SLOTS];
    private final float[] panelB = new float[PANEL_SLOTS];
    private final int[] panelC0 = new int[PANEL_SLOTS];
    private final int[] panelC1 = new int[PANEL_SLOTS];
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF prect = new RectF();

    // Sky: a dusk gradient, three ranges of hills at different depths, and the near bank.
    private static final int[] SKY_COLORS = {0xFF060E20, 0xFF112447, 0xFF2A4473, 0xFF5E6390, 0xFFC4846F, 0xFFEFB07A};
    private static final float[] SKY_STOPS = {0f, 0.28f, 0.50f, 0.66f, 0.82f, 1f};
    private final android.graphics.Path hillFar = new android.graphics.Path();
    private final android.graphics.Path hillMid = new android.graphics.Path();
    private android.graphics.LinearGradient hillFarShader;
    private android.graphics.LinearGradient hillMidShader;
    private float hillW = -1f;
    private android.graphics.LinearGradient shoreShader;
    private float shoreTop = -1f;
    private float shoreBottom = -1f;
    private android.graphics.LinearGradient overlayShader;
    private float overlayH = -1f;

    // Boats: one hull outline at the origin (drawn by translation so its mask is cached), a flag.
    private final android.graphics.Path hullShape = new android.graphics.Path();
    private float hullShapeLen = -1f;
    private final android.graphics.Path flagPath = new android.graphics.Path();
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] ghostWisp = new float[MAX_CREWS];

    // Text widths measured as each line is drawn, so the panel behind it fits next frame.
    private final float[] crewLabelW = new float[MAX_CREWS];
    private float youLabelW;
    private float gapPanelW;
    private float splitsW;
    private float standGapW;
    private float paceLW;
    private float paceRW;
    private float finishW;
    private float pushW;

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
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeWidth(dp(1.6f));
        dashPaint.setPathEffect(new android.graphics.DashPathEffect(new float[] {dp(6f), dp(4f)}, 0f));
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

    /** A crew with a personality: drawn with its colours flying. Presentation only. */
    protected boolean crewIsRival(int i) {
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
        double you = raceDistance();
        drawSky(c, w, bankTop, you, ppm);
        river.advance(state == State.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        float span = waterBottom - waterTop;
        scenery.drawBank(c, w, bankTop, waterTop, you, ppm, sessionSeconds, cheer);
        // After the bank, which lays its reflection on the far water: the sun's glints sit on top.
        drawWaterLight(c, w, waterTop, waterBottom, laneY(youLanePos, waterTop, waterBottom));
        for (int b = 0; b < LANES - 1; b++) {
            scenery.drawBuoys(c, w, waterTop + span * (0.26f + 0.24f * b), you, ppm, sessionSeconds, 10f);
        }
        scenery.drawWaterLife(c, w, waterTop, waterBottom, you, ppm, sessionSeconds);
        drawLaneWater(c, w, waterTop, waterBottom, you, ppm, dt);
        drawNearShore(c, w, h, waterBottom, you, ppm);
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
            // A recording drifts a little on the water it no longer feels.
            float boatY = crewIsRecording(i)
                    ? laneC + (float) Math.sin(sessionSeconds * 1.6 + i * 2.0) * dp(1.4f) : laneC;
            drawCrewUnder(c, i, crewX[i], boatY, boatLen, cs, dt);
            river.setStrokePhase((float) (0.5 + 0.5 * Math.sin(crewStrokeAngle[i])));
            river.drawBoat(c, crewX[i], boatY, boatLen, crewColor(i), cs, crewIsRecording(i));
            drawCrewOver(c, i, crewX[i], boatY, boatLen, cs);
            if (targetX != clampedX) {
                // Off the edge: an arrow says which way and how far.
                String far = String.format(java.util.Locale.US, "%s %.0f m",
                        targetX > clampedX ? "→" : "←", Math.abs(cGap));
                bold(c, far, targetX > clampedX ? w - dp(14f) : dp(70f), laneC - dp(22f), 13f,
                        crewColor(i), targetX > clampedX ? Paint.Align.RIGHT : Paint.Align.LEFT);
            }
        }
        river.bowSpray(yourX + boatLen / 2f, laneYou, speed, dt);
        // The hero: a soft accent pool on the water under the hull (RiverRenderer casts the shadow).
        Fx.glow(c, yourX, laneYou, dp(84f), 0x2235D0BA);
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
        // Name plates: a pill under each label, sized from the width measured last frame.
        float youTagY = laneYou + dp(36f);
        namePlate(c, yourX, youTagY, youLabelW, 0xD00A1A1E, ACCENT, false);
        bold(c, "YOU", yourX, youTagY, 11f, ACCENT, Paint.Align.CENTER);
        youLabelW = textPaint.measureText("YOU");
        for (int i = 0; i < crews; i++) {
            float laneC = laneY(crewLanePos[i], waterTop, waterBottom);
            String tagText = crewLabel(i);
            float lx = Math.max(dp(130f), Math.min(w - dp(110f), crewX[i]));
            float ly = laneC - dp(24f);
            int col = crewColor(i);
            boolean ghost = crewIsRecording(i);
            if (ghost) {
                // A recording's plate is see-through with a broken edge, like the boat it names.
                namePlate(c, lx, ly, crewLabelW[i], (0x2E << 24) | (col & 0x00FFFFFF), col, true);
            } else {
                namePlate(c, lx, ly, crewLabelW[i], 0xB80A111C, col, false);
            }
            label(c, tagText, lx, ly, 11f, ghost ? ghostInk(col) : col, Paint.Align.CENTER);
            crewLabelW[i] = dimPaint.measureText(tagText);
        }
        drawBubble(c, w, waterTop, waterBottom);
        drawMateBubble(c, yourX, laneYou);
        drawLaneTags(c, waterTop, waterBottom);
        if (sessionSeconds < calloutUntil) {
            float cy = waterTop + span * 0.5f;
            bold(c, callout, w / 2f + dp(2f), cy + dp(3f), 30f, 0xA0000000, Paint.Align.CENTER);
            bold(c, callout, w / 2f, cy, 30f, calloutColor, Paint.Align.CENTER);
        }
        if (sprinting && state == State.RACING) {
            drawSprintWater(c, w, waterTop, waterBottom, speed);
        }

        drawHud(c, w, h, hudH, speed, gap, lead);
        drawRaceChips(c, w, dt);
        // Last in the HUD band, so nothing draws over a live push call.
        drawPush(c, w, hudH);

        // Progress bar along the top of the water.
        float pct = Math.max(0f, Math.min(1f, (float) (raceDistance() / raceMeters)));
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x66000000);
        c.drawRect(0, waterTop - dp(2f), w, waterTop + dp(2f), scenePaint);
        scenePaint.setColor(ACCENT);
        c.drawRect(0, waterTop - dp(2f), w * pct, waterTop + dp(2f), scenePaint);
        scenePaint.setColor(0x70FFFFFF);
        c.drawRect(0, waterTop - dp(2f), w * pct, waterTop - dp(1f), scenePaint);
        if (pct > 0f) {
            Fx.glow(c, w * pct, waterTop, dp(16f), 0xA035D0BA);
            scenePaint.setColor(0xFFE8FFFA);
            c.drawCircle(w * pct, waterTop, dp(3.5f), scenePaint);
        }

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

    /* ---------- scenery (3.25.0) ---------- */

    /**
     * A dusk sky over the course: a six-stop gradient warming to the horizon, a few stars still up,
     * a low sun with its halo, lit-from-below clouds and birds drifting across, then two hazy ranges
     * scrolling at their own depths behind the bank's ridge. All gradients and the hill outlines are built once per size and drawn by translation.
     */
    private void drawSky(Canvas c, float w, float bankTop, double you, float ppm) {
        if (skyShader == null || skyHeight != bankTop) {
            skyHeight = bankTop;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, bankTop, SKY_COLORS, SKY_STOPS,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        if (hillW != w) {
            buildHills(w);
        }
        double t = sessionSeconds;
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0xFFFFFFFF);
        scenePaint.setShader(skyShader);
        c.drawRect(0, 0, w, bankTop, scenePaint);
        scenePaint.setShader(null);

        // Stars in the darkest band, twinkling on their own clocks.
        for (int s = 0; s < 26; s++) {
            float sx = (float) frac(s * 0.6180339 + 0.11) * w;
            float sy = (float) frac(s * 0.4142136 + 0.37) * bankTop * 0.40f;
            float tw = 0.5f + 0.5f * (float) Math.sin(t * (1.1 + (s % 5) * 0.35) + s * 1.7);
            scenePaint.setColor(((int) (35 + 120 * tw) << 24) | 0x00E9F0FF);
            c.drawCircle(sx, sy, s % 7 == 0 ? dp(1.7f) : dp(1.1f), scenePaint);
        }

        // The sun, low over the hills; the ranges drawn next set it behind them.
        float sunX = w * 0.20f;
        float sunY = bankTop - dp(58f);
        Fx.glow(c, sunX, sunY, dp(190f), 0x38FFA66A);
        Fx.glow(c, sunX, sunY, dp(70f), 0x80FFD49A);
        scenePaint.setColor(0xFFFFE6B8);
        c.drawCircle(sunX, sunY, dp(19f), scenePaint);
        scenePaint.setColor(0xFFFFF6E2);
        c.drawCircle(sunX - dp(3f), sunY - dp(3f), dp(12f), scenePaint);

        // Clouds: a cool body with a warm underside, lit by the low sun. They drift on their own and
        // slide a touch with your metres, slower than anything on the ground.
        float cloudSpan = w + dp(360f);
        for (int k = 0; k < 5; k++) {
            double travel = k * 0.37 * cloudSpan + t * dp(5f + k * 2f) + you * ppm * 0.03;
            float cx = cloudSpan - (float) (travel % cloudSpan) - dp(180f);
            float cy = bankTop * (0.14f + 0.12f * ((k * 3) % 5));
            float cw = dp(110f + 36f * (k % 3));
            float ch = dp(13f + 4f * (k % 2));
            scenePaint.setColor(0x24B4C2E4);
            c.drawOval(cx - cw * 0.5f, cy - ch * 0.5f, cx + cw * 0.5f, cy + ch * 0.5f, scenePaint);
            c.drawOval(cx - cw * 0.28f, cy - ch * 1.15f, cx + cw * 0.18f, cy + ch * 0.2f, scenePaint);
            c.drawOval(cx + cw * 0.02f, cy - ch * 0.9f, cx + cw * 0.40f, cy + ch * 0.3f, scenePaint);
            scenePaint.setColor(0x2CFFB58C);
            c.drawOval(cx - cw * 0.44f, cy + ch * 0.05f, cx + cw * 0.44f, cy + ch * 0.62f, scenePaint);
        }

        // Three birds heading upstream.
        scenePaint.setStyle(Paint.Style.STROKE);
        scenePaint.setStrokeCap(Paint.Cap.ROUND);
        scenePaint.setStrokeWidth(dp(1.8f));
        scenePaint.setColor(0xB0141C2C);
        float birdSpan = w + dp(200f);
        for (int b = 0; b < 3; b++) {
            float bx = (float) ((t * dp(38f) + b * dp(46f) + 0.6 * w) % birdSpan) - dp(100f);
            float by = bankTop * 0.5f + b * dp(9f) + (float) Math.sin(t * 0.7 + b) * dp(5f);
            float flap = (float) Math.sin(t * 7.5 + b * 1.3) * dp(4f);
            c.drawLine(bx - dp(8f), by - flap, bx, by, scenePaint);
            c.drawLine(bx, by, bx + dp(8f), by - flap, scenePaint);
        }
        scenePaint.setStrokeCap(Paint.Cap.BUTT);
        scenePaint.setStyle(Paint.Style.FILL);

        // Two ranges behind the bank's own ridge, palest and slowest first, so the haze deepens with
        // distance. RiverScenery paints the nearer ridge and treeline (up to ~20dp above the bank),
        // and these rise well above it. Each outline repeats exactly every screen width.
        drawRange(c, hillFar, hillFarShader, w, bankTop, you * ppm * 0.015);
        drawRange(c, hillMid, hillMidShader, w, bankTop, you * ppm * 0.035);
        // A few lit windows high on the mid range: someone is home.
        double midOff = (you * ppm * 0.035) % w;
        for (int j = 0; j < 9; j++) {
            float hx = (float) frac(j * 0.7310 + 0.05) * w;
            float top = midHill(hx, w);
            if (top > -dp(30f)) {
                continue;       // too low: the bank's ridge would cover it
            }
            float x = (float) (((hx - midOff) % w + w) % w);
            float y = bankTop + top + dp(7f);
            float blink = (j % 3 == 0) ? 0.6f + 0.4f * (float) Math.sin(t * 0.9 + j) : 1f;
            scenePaint.setColor(((int) (190 * blink) << 24) | 0x00FFD58A);
            c.drawRect(x - dp(1.5f), y - dp(1.5f), x + dp(1.5f), y + dp(1.5f), scenePaint);
        }
    }

    private void drawRange(Canvas c, android.graphics.Path p, android.graphics.LinearGradient g, float w,
                           float base, double scroll) {
        float off = (float) (scroll % w);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0xFFFFFFFF);
        scenePaint.setShader(g);
        c.save();
        c.translate(-off, base);
        c.drawPath(p, scenePaint);
        c.translate(w, 0);
        c.drawPath(p, scenePaint);
        c.restore();
        scenePaint.setShader(null);
    }

    private float farHill(float x, float w) {
        double a = 2 * Math.PI * x / w;
        return -dp(64f) * (float) (0.6 + 0.2 * Math.sin(2 * a + 0.7) + 0.15 * Math.sin(5 * a + 2.1)
                + 0.08 * Math.sin(13 * a + 0.3));
    }

    private float midHill(float x, float w) {
        double a = 2 * Math.PI * x / w;
        return -dp(44f) * (float) (0.62 + 0.2 * Math.sin(3 * a + 1.9) + 0.12 * Math.sin(7 * a + 0.4)
                + 0.06 * Math.sin(17 * a + 2.6));
    }

    /** Builds the two outlines and their haze gradients, in coordinates with the bank top at y = 0. */
    private void buildHills(float w) {
        hillW = w;
        float step = dp(8f);
        hillFar.rewind();
        hillMid.rewind();
        hillFar.moveTo(0, dp(4f));
        hillMid.moveTo(0, dp(4f));
        for (float x = 0; x <= w + 0.5f; x += step) {
            hillFar.lineTo(x, farHill(x, w));
            hillMid.lineTo(x, midHill(x, w));
        }
        hillFar.lineTo(w, dp(4f));
        hillMid.lineTo(w, dp(4f));
        hillFar.close();
        hillMid.close();
        // Atmospheric perspective: the far range nearly the colour of the dusk haze, the mid range a
        // step bluer, both paler at the foot where the mist lies - and both paler than the bank's
        // own ridge in front of them.
        hillFarShader = new android.graphics.LinearGradient(0, -dp(64f), 0, 0, 0xFF7A7FA6, 0xFFB09AA6,
                android.graphics.Shader.TileMode.CLAMP);
        hillMidShader = new android.graphics.LinearGradient(0, -dp(44f), 0, 0, 0xFF5E6E94, 0xFF8792AA,
                android.graphics.Shader.TileMode.CLAMP);
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /**
     * Light on the water: the sun's reflection as a column of glints under it on the far water, and a
     * faint carpet down your own lane so the eye finds the hero first.
     */
    private void drawWaterLight(Canvas c, float w, float waterTop, float waterBottom, float laneYou) {
        double t = sessionSeconds;
        float span = waterBottom - waterTop;
        float sunX = w * 0.20f;
        scenePaint.setStyle(Paint.Style.FILL);
        for (int j = 0; j < 14; j++) {
            float f = j / 14f;
            float y = waterTop + dp(5f) + span * 0.55f * f * f + dp(6f) * f;
            float hw = dp(9f) + dp(46f) * f;
            float flick = 0.45f + 0.55f * (float) Math.abs(Math.sin(t * (2.3 + (j % 4) * 0.6) + j * 2.1));
            float jitter = (float) Math.sin(t * 1.3 + j * 0.9) * dp(7f) * (0.3f + f);
            int a = (int) ((1f - f) * 120 * flick);
            scenePaint.setColor((a << 24) | 0x00FFD9A0);
            c.drawRect(sunX - hw + jitter, y, sunX + hw * 0.6f + jitter, y + dp(1.6f) + dp(1.6f) * f, scenePaint);
        }
        // Your lane: a soft accent band with brighter edges.
        scenePaint.setColor(0x0F35D0BA);
        c.drawRect(0, laneYou - dp(36f), w, laneYou + dp(36f), scenePaint);
        scenePaint.setColor(0x1C35D0BA);
        c.drawRect(0, laneYou - dp(36f), w, laneYou - dp(35f), scenePaint);
        c.drawRect(0, laneYou + dp(35f), w, laneYou + dp(36f), scenePaint);
    }

    /**
     * The near bank, which used to be a 26dp green strip over a black void: a wet edge, a grass slope
     * that darkens toward you, reeds and cattails at the water, two rows of tufts and some flowers
     * scrolling faster than anything else on screen, and a glass footer for the race figures.
     */
    private void drawNearShore(Canvas c, float w, float h, float waterBottom, double you, float ppm) {
        if (shoreShader == null || shoreTop != waterBottom || shoreBottom != h) {
            shoreTop = waterBottom;
            shoreBottom = h;
            shoreShader = new android.graphics.LinearGradient(0, waterBottom, 0, h,
                    new int[] {0xFF3F7D3E, 0xFF285A2E, 0xFF0F2216}, new float[] {0f, 0.3f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        double t = sessionSeconds;
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0xFFFFFFFF);
        scenePaint.setShader(shoreShader);
        c.drawRect(0, waterBottom, w, h, scenePaint);
        scenePaint.setShader(null);
        // Mud lip and the wet glint where the water meets it.
        scenePaint.setColor(0xFF22301F);
        c.drawRect(0, waterBottom, w, waterBottom + dp(5f), scenePaint);
        scenePaint.setColor(0x55CFE6FF);
        c.drawRect(0, waterBottom - dp(1.5f), w, waterBottom, scenePaint);

        float footTop = h - dp(58f);
        scenePaint.setStrokeCap(Paint.Cap.ROUND);
        // Two rows of tufts, the nearer bigger, darker and faster: the strongest parallax on screen.
        for (int row = 0; row < 2; row++) {
            float baseY = waterBottom + (row == 0 ? dp(46f) : dp(86f));
            if (baseY > footTop - dp(4f)) {
                break;
            }
            float gapT = row == 0 ? dp(38f) : dp(56f);
            double scroll = you * ppm * (row == 0 ? 1.45 : 1.8);
            float off = (float) (scroll % gapT);
            scenePaint.setStrokeWidth(row == 0 ? dp(2.2f) : dp(3f));
            for (float x = -off - gapT; x < w + gapT; x += gapT) {
                int k = (int) Math.floor((x + scroll) / gapT + 0.5);
                int hsh = Math.abs(k * 2654435 + row * 977) % 1000;
                float tx = x + (hsh % 13) * dp(1.2f);
                float tall = (row == 0 ? dp(11f) : dp(16f)) + (hsh % 5) * dp(2.5f);
                float sway = (float) Math.sin(t * 1.7 + k * 0.8) * dp(2f);
                scenePaint.setColor(row == 0 ? 0xFF4A8C48 : 0xFF1F4A26);
                c.drawLine(tx, baseY, tx - dp(5f) + sway, baseY - tall * 0.75f, scenePaint);
                c.drawLine(tx, baseY, tx + sway, baseY - tall, scenePaint);
                c.drawLine(tx, baseY, tx + dp(5f) + sway, baseY - tall * 0.7f, scenePaint);
                if (row == 0 && hsh % 4 == 0) {
                    scenePaint.setColor(hsh % 8 == 0 ? 0xFFF5D36A : 0xFFE98AB0);
                    c.drawCircle(tx + sway, baseY - tall - dp(2f), dp(2.6f), scenePaint);
                }
            }
        }
        // Reeds and cattails at the waterline.
        float reedGap = dp(22f);
        double reedScroll = you * ppm * 1.2;
        float reedOff = (float) (reedScroll % reedGap);
        scenePaint.setStrokeWidth(dp(3f));
        for (float rx = -reedOff - reedGap; rx < w + reedGap; rx += reedGap) {
            int k = (int) Math.floor((rx + reedScroll) / reedGap + 0.5);
            float tall = dp(14f) + (Math.abs(k * 7) % 4) * dp(5f);
            float sway = (float) Math.sin(t * 2 + k) * dp(3f);
            float baseY = waterBottom + dp(8f);
            scenePaint.setColor((k & 1) == 0 ? 0xFF4E8F4F : 0xFF6BAA5C);
            c.drawLine(rx, baseY, rx + sway, baseY - tall, scenePaint);
            if (Math.abs(k) % 3 == 1) {
                scenePaint.setStrokeWidth(dp(5f));
                scenePaint.setColor(0xFF6B4A2E);
                c.drawLine(rx + sway * 0.9f, baseY - tall + dp(3f), rx + sway, baseY - tall - dp(5f), scenePaint);
                scenePaint.setStrokeWidth(dp(3f));
            }
        }
        scenePaint.setStrokeCap(Paint.Cap.BUTT);
    }

    /** The hull outline at the origin; drawn by translation so its rasterised mask is reused. */
    private void ensureHull(float len) {
        if (hullShapeLen == len) {
            return;
        }
        hullShapeLen = len;
        float half = len / 2f;
        float beam = len * 0.11f;
        hullShape.rewind();
        hullShape.moveTo(half, 0);
        hullShape.quadTo(half * 0.55f, -beam, -half * 0.2f, -beam);
        hullShape.lineTo(-half * 0.8f, -beam * 0.6f);
        hullShape.quadTo(-half, 0, -half * 0.8f, beam * 0.6f);
        hullShape.lineTo(-half * 0.2f, beam);
        hullShape.quadTo(half * 0.55f, beam, half, 0);
        hullShape.close();
    }

    /**
     * Under a crew's hull. A recording gets a cold aura and afterimages trailing behind it, so it
     * reads as a ghost before you read its name. A rival gets its team colour glowing on the water,
     * hot and pulsing while it is making a move, with streaks off its stern.
     */
    private void drawCrewUnder(Canvas c, int i, float x, float y, float len, float cs, float dt) {
        int rgb = crewColor(i) & 0x00FFFFFF;
        double t = sessionSeconds;
        float half = len / 2f;
        if (crewIsRecording(i)) {
            ensureHull(len);
            float breathe = 0.5f + 0.5f * (float) Math.sin(t * 2.2 + i * 1.9);
            Fx.glow(c, x, y, dp(100f), ((int) (0x1C + 0x20 * breathe) << 24) | rgb);
            float trail = dp(15f) * (0.45f + Math.min(1f, cs / 4f));
            scenePaint.setStyle(Paint.Style.FILL);
            for (int k = 3; k >= 1; k--) {
                float ex = x - k * trail;
                float ey = y + (float) Math.sin(t * 3.1 + k * 1.3 + i) * dp(2f);
                scenePaint.setColor(((0x44 - k * 0x10) << 24) | rgb);
                c.save();
                c.translate(ex, ey);
                c.drawPath(hullShape, scenePaint);
                c.restore();
            }
            // Wisps lifting off it and falling behind, a few a second.
            ghostWisp[i] += dt * 7f;
            while (ghostWisp[i] >= 1f) {
                ghostWisp[i] -= 1f;
                fx.spawn(x - half * 0.8f + (float) Math.random() * len * 0.9f,
                        y + (float) (Math.random() - 0.5) * dp(10f),
                        -dp(25f) - (float) Math.random() * dp(25f), -dp(14f) - (float) Math.random() * dp(18f),
                        1.3f, dp(2.6f), 0x70000000 | rgb, false);
            }
            return;
        }
        if (crewIsRival(i)) {
            boolean moving = pushCrew == i && state == State.RACING;
            float pulse = 0.5f + 0.5f * (float) Math.sin(t * (moving ? 10 : 2.0) + i);
            int a = moving ? (int) (0x40 + 0x50 * pulse) : (int) (0x22 + 0x10 * pulse);
            Fx.glow(c, x, y, dp(86f), (a << 24) | rgb);
            if (moving) {
                scenePaint.setStyle(Paint.Style.STROKE);
                scenePaint.setStrokeCap(Paint.Cap.ROUND);
                scenePaint.setStrokeWidth(dp(2.2f));
                float run = (float) ((t * dp(260f)) % dp(70f));
                for (int k = -2; k <= 2; k++) {
                    float sy = y + k * len * 0.07f;
                    float sx = x - half - dp(8f) - run - Math.abs(k) * dp(12f);
                    scenePaint.setColor((0x90 << 24) | rgb);
                    c.drawLine(sx, sy, sx - dp(34f), sy, scenePaint);
                }
                scenePaint.setStrokeCap(Paint.Cap.BUTT);
                scenePaint.setStyle(Paint.Style.FILL);
            }
        }
    }

    /**
     * Over a crew's hull. A recording shimmers: a flickering outline, scanlines across the hull and a
     * light sweeping stern to bow. A rival flies its pennant from the stern and wears a chevron on its
     * bow deck. The plain pace boat carries a blinking pacer light on its bow.
     */
    private void drawCrewOver(Canvas c, int i, float x, float y, float len, float cs) {
        int col = crewColor(i);
        int rgb = col & 0x00FFFFFF;
        double t = sessionSeconds;
        float half = len / 2f;
        float beam = len * 0.11f;
        if (crewIsRecording(i)) {
            ensureHull(len);
            float flick = 0.5f + 0.5f * (float) Math.sin(t * 6.3 + i * 2.0);
            scenePaint.setStyle(Paint.Style.STROKE);
            scenePaint.setStrokeWidth(dp(1.6f));
            scenePaint.setColor(((int) (70 + 110 * flick) << 24) | (ghostInk(col) & 0x00FFFFFF));
            c.save();
            c.translate(x, y);
            c.drawPath(hullShape, scenePaint);
            c.restore();
            // Scanlines across the hull, each flickering on its own.
            scenePaint.setStrokeWidth(dp(1.1f));
            for (int k = 0; k < 4; k++) {
                float dy = beam * (-0.72f + 0.48f * k);
                float r = dy / beam;
                float hl = half * 0.78f * (float) Math.sqrt(Math.max(0f, 1f - r * r));
                float jit = (float) Math.sin(t * 11 + k * 1.9 + i) * dp(3f);
                int a = (int) (35 + 70 * (0.5f + 0.5f * (float) Math.sin(t * 9 + k * 2.4 + i)));
                scenePaint.setColor((a << 24) | 0x00FFFFFF);
                c.drawLine(x - hl + jit, y + dy, x + hl * 0.9f + jit, y + dy, scenePaint);
            }
            scenePaint.setStyle(Paint.Style.FILL);
            float sweep = (float) frac(t * 0.55 + i * 0.33);
            Fx.glow(c, x - half * 0.85f + sweep * half * 1.8f, y, dp(20f), 0x60FFFFFF);
            return;
        }
        if (crewIsRival(i)) {
            int dark = 0xFF000000 | ((col >>> 1) & 0x007F7F7F);
            // Team chevron on the bow deck.
            scenePaint.setStyle(Paint.Style.STROKE);
            scenePaint.setStrokeCap(Paint.Cap.ROUND);
            scenePaint.setStrokeWidth(dp(2.6f));
            scenePaint.setColor(dark);
            c.drawLine(x + half * 0.38f, y - beam * 0.55f, x + half * 0.6f, y, scenePaint);
            c.drawLine(x + half * 0.6f, y, x + half * 0.38f, y + beam * 0.55f, scenePaint);
            // Pennant on a staff at the stern, streaming back harder the faster the crew rows.
            float sx = x - half * 0.74f;
            float top = y - dp(24f);
            scenePaint.setStrokeWidth(dp(1.6f));
            scenePaint.setColor(0xFFE6EDF7);
            c.drawLine(sx, y - beam * 0.2f, sx, top, scenePaint);
            scenePaint.setStrokeCap(Paint.Cap.BUTT);
            float stream = 0.5f + Math.min(1f, cs / 4f) * 0.5f;
            float wave = (float) Math.sin(t * 8 + i * 1.7) * dp(2.6f);
            flagPath.rewind();
            flagPath.moveTo(sx, top);
            flagPath.quadTo(sx - dp(10f) * stream, top + dp(1f) - wave, sx - dp(20f) * stream, top + dp(5f) + wave);
            flagPath.quadTo(sx - dp(10f) * stream, top + dp(8f) - wave * 0.5f, sx, top + dp(10f));
            flagPath.close();
            scenePaint.setStyle(Paint.Style.FILL);
            scenePaint.setColor(col);
            c.drawPath(flagPath, scenePaint);
            scenePaint.setStyle(Paint.Style.STROKE);
            scenePaint.setStrokeWidth(dp(1f));
            scenePaint.setColor(dark);
            c.drawPath(flagPath, scenePaint);
            scenePaint.setStyle(Paint.Style.FILL);
            return;
        }
        // The pace boat: a strobe on the bow, two quick flashes a second.
        float ph = (float) frac(t * 1.0);
        boolean lit = ph < 0.08f || (ph > 0.18f && ph < 0.26f);
        float bx = x + half * 0.9f;
        if (lit) {
            Fx.glow(c, bx, y, dp(22f), 0xC0FFFFFF);
        }
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(lit ? 0xFFFFFFFF : 0xFF8FA6FF);
        c.drawCircle(bx, y, dp(3f), scenePaint);
    }

    /** A recording's label ink: its colour washed halfway to white, then made translucent. */
    private static int ghostInk(int col) {
        int r = (((col >> 16) & 0xFF) + 255) / 2;
        int g = (((col >> 8) & 0xFF) + 255) / 2;
        int b = ((col & 0xFF) + 255) / 2;
        return 0xD0000000 | (r << 16) | (g << 8) | b;
    }

    /** The pill under a boat's name. {@code textW} is the label's width from the last frame. */
    private void namePlate(Canvas c, float cx, float baseline, float textW, int fill, int edge, boolean dashed) {
        if (textW <= 0f) {
            return;
        }
        float half = textW / 2f + dp(10f);
        prect.set(cx - half, baseline - dp(15f), cx + half, baseline + dp(5f));
        float r = dp(10f);
        scenePaint.setStyle(Paint.Style.FILL);
        if (!dashed) {
            scenePaint.setColor(0x40000000);
            prect.offset(0, dp(2f));
            c.drawRoundRect(prect, r, r, scenePaint);
            prect.offset(0, -dp(2f));
        }
        scenePaint.setColor(fill);
        c.drawRoundRect(prect, r, r, scenePaint);
        if (dashed) {
            dashPaint.setColor((0xA0 << 24) | (edge & 0x00FFFFFF));
            c.drawRoundRect(prect, r, r, dashPaint);
        } else {
            scenePaint.setStyle(Paint.Style.STROKE);
            scenePaint.setStrokeWidth(dp(1.5f));
            scenePaint.setColor((0xC0 << 24) | (edge & 0x00FFFFFF));
            c.drawRoundRect(prect, r, r, scenePaint);
            scenePaint.setStyle(Paint.Style.FILL);
        }
    }

    /* ---------- HUD furniture (3.25.0) ---------- */

    /**
     * A glass panel: drop shadow, a vertical gradient cached per slot, a hairline border and a lighter
     * top edge. Slots exist so every panel's gradient is built once for its position, not per frame.
     */
    private void panel(Canvas c, int slot, float l, float t, float r, float b, float radius, int top, int bottom,
                       int edge) {
        android.graphics.LinearGradient g = panelShader[slot];
        if (g == null || panelT[slot] != t || panelB[slot] != b || panelC0[slot] != top || panelC1[slot] != bottom) {
            g = new android.graphics.LinearGradient(0, t, 0, b, top, bottom, android.graphics.Shader.TileMode.CLAMP);
            panelShader[slot] = g;
            panelT[slot] = t;
            panelB[slot] = b;
            panelC0[slot] = top;
            panelC1[slot] = bottom;
        }
        panelPaint.setShader(null);
        panelPaint.setStyle(Paint.Style.FILL);
        panelPaint.setColor(0x3A000000);
        prect.set(l, t + dp(3f), r, b + dp(3f));
        c.drawRoundRect(prect, radius, radius, panelPaint);
        prect.set(l, t, r, b);
        panelPaint.setColor(0xFFFFFFFF);
        panelPaint.setShader(g);
        c.drawRoundRect(prect, radius, radius, panelPaint);
        panelPaint.setShader(null);
        panelPaint.setStyle(Paint.Style.STROKE);
        panelPaint.setStrokeWidth(dp(1f));
        panelPaint.setColor(edge);
        c.drawRoundRect(prect, radius, radius, panelPaint);
        panelPaint.setColor(0x2EFFFFFF);
        c.drawLine(l + radius, t + dp(1f), r - radius, t + dp(1f), panelPaint);
        panelPaint.setStyle(Paint.Style.FILL);
    }

    private void glass(Canvas c, int slot, float l, float t, float r, float b) {
        panel(c, slot, l, t, r, b, dp(10f), GLASS_TOP, GLASS_BOT, GLASS_EDGE);
    }

    /** A rounded track with a glossy fill: the bars in the push, sprint and analysis panels. */
    private void bar(Canvas c, float l, float t, float r, float b, float fill, int color) {
        float rad = (b - t) / 2f;
        panelPaint.setShader(null);
        panelPaint.setStyle(Paint.Style.FILL);
        prect.set(l, t, r, b);
        panelPaint.setColor(0x66000000);
        c.drawRoundRect(prect, rad, rad, panelPaint);
        panelPaint.setStyle(Paint.Style.STROKE);
        panelPaint.setStrokeWidth(dp(1f));
        panelPaint.setColor(0x30FFFFFF);
        c.drawRoundRect(prect, rad, rad, panelPaint);
        panelPaint.setStyle(Paint.Style.FILL);
        fill = Math.max(0f, Math.min(1f, fill));
        if (fill <= 0f) {
            return;
        }
        float fr = l + (r - l) * fill;
        float fRad = Math.min(rad, (fr - l) / 2f);
        prect.set(l, t, fr, b);
        panelPaint.setColor(color);
        c.drawRoundRect(prect, fRad, fRad, panelPaint);
        prect.set(l + fRad * 0.5f, t + dp(1f), fr - fRad * 0.5f, t + (b - t) * 0.48f);
        if (prect.right > prect.left) {
            panelPaint.setColor(0x55FFFFFF);
            c.drawRoundRect(prect, fRad * 0.5f, fRad * 0.5f, panelPaint);
        }
    }

    /** A horizontal gain/loss bar from {@code x0} to {@code x1} (either order), rounded and glossy. */
    private void slab(Canvas c, float x0, float x1, float t, float b, int color) {
        float l = Math.min(x0, x1);
        float r = Math.max(x0, x1);
        if (r - l < 0.5f) {
            return;
        }
        float rad = Math.min((b - t) / 2f, (r - l) / 2f);
        panelPaint.setShader(null);
        panelPaint.setStyle(Paint.Style.FILL);
        prect.set(l, t, r, b);
        panelPaint.setColor(color);
        c.drawRoundRect(prect, rad, rad, panelPaint);
        prect.set(l + rad * 0.5f, t + dp(1f), r - rad * 0.5f, t + (b - t) * 0.45f);
        if (prect.right > prect.left) {
            panelPaint.setColor(0x50FFFFFF);
            c.drawRoundRect(prect, rad * 0.5f, rad * 0.5f, panelPaint);
        }
    }

    /** A full-screen dimming for the finish pages, darker at the foot; cached per height. */
    private void finishOverlay(Canvas c, float w, float h) {
        if (overlayShader == null || overlayH != h) {
            overlayH = h;
            overlayShader = new android.graphics.LinearGradient(0, 0, 0, h,
                    new int[] {0xD8081020, 0xE6060B12, 0xF4040810}, new float[] {0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        panelPaint.setStyle(Paint.Style.FILL);
        panelPaint.setColor(0xFFFFFFFF);
        panelPaint.setShader(overlayShader);
        c.drawRect(0, 0, w, h, panelPaint);
        panelPaint.setShader(null);
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
            // A glass tag tall enough to hold its LANE caption, which used to sit on the box's edge.
            boolean mine = l == youLane;
            panel(c, P_LANE + l, dp(6f), y - dp(27f), dp(64f), y + dp(12f), dp(8f),
                    mine ? 0xD8153A3A : 0xC0172234, mine ? 0xE00A2224 : 0xC80A0E14,
                    mine ? 0x9935D0BA : GLASS_EDGE);
            if (mine) {
                scenePaint.setStyle(Paint.Style.FILL);
                scenePaint.setColor(ACCENT);
                rect.set(dp(6f), y - dp(19f), dp(9f), y + dp(4f));
                c.drawRoundRect(rect, dp(1.5f), dp(1.5f), scenePaint);
            }
            rect.set(dp(6f), y - dp(27f), dp(64f), y + dp(12f));
            scenePaint.setStyle(Paint.Style.FILL);
            if (worthIt && l == best) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 8);
                scenePaint.setStyle(Paint.Style.STROKE);
                scenePaint.setStrokeWidth(dp(2f));
                scenePaint.setColor(((int) (120 + 135 * pulse) << 24) | 0x00F5C518);
                rect.inset(-dp(2f), -dp(2f));
                c.drawRoundRect(rect, dp(10f), dp(10f), scenePaint);
                scenePaint.setStyle(Paint.Style.FILL);
                bold(c, "TAP", dp(70f), y + dp(4f), 10f, WARN, Paint.Align.LEFT);
            }
            bold(c, boostText(b), dp(35f), y + dp(4f), 11f, b >= 0 ? ACCENT : BAD, Paint.Align.CENTER);
            label(c, LANE_NAME[l], dp(35f), y - dp(15f), 8f, DIM, Paint.Align.CENTER);
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
        scenePaint.setColor(0x50000000);
        rect.offset(dp(2f), dp(3f));
        c.drawRoundRect(rect, dp(9f), dp(9f), scenePaint);
        rect.offset(-dp(2f), -dp(3f));
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
        rect.set(bx - half, by - dp(22f), bx + half, by);
        scenePaint.setColor(0x50000000);
        rect.offset(dp(2f), dp(3f));
        c.drawRoundRect(rect, dp(9f), dp(9f), scenePaint);
        rect.offset(-dp(2f), -dp(3f));
        scenePaint.setColor(0xF2E8FBF6);
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
                bold(c, pushResult, cx + dp(1.5f), y + dp(22f), 16f, 0xB0000000, Paint.Align.CENTER);
                bold(c, pushResult, cx, y + dp(20f), 16f, pushResultGood ? ACCENT : BAD, Paint.Align.CENTER);
            }
            return;
        }
        int crew = Math.min(pushCrew, crewCount() - 1);
        float left = (float) Math.max(0, pushUntil - sessionSeconds);
        float avg = pushTime > 0.3 ? (float) (pushSum / pushTime) : 0f;
        float fill = Math.max(0f, Math.min(1f, (float) ((avg / pushBase - 1.0) / 0.04)));
        float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 9);
        int crewRgb = crewColor(crew) & 0x00FFFFFF;
        float halfW = Math.max(dp(165f), pushW / 2f + dp(18f));
        // The crew's colour bleeds into the glass while its move is live.
        Fx.glow(c, cx, y + dp(27f), dp(200f), ((int) (0x20 + 0x30 * pulse) << 24) | crewRgb);
        panel(c, P_PUSH, cx - halfW, y, cx + halfW, y + dp(54f), dp(12f), GLASS_TOP, GLASS_BOT,
                (((int) (130 + 120 * pulse)) << 24) | crewRgb);
        scenePaint.setStyle(Paint.Style.STROKE);
        scenePaint.setStrokeWidth(dp(2f));
        scenePaint.setColor((((int) (130 + 120 * pulse)) << 24) | crewRgb);
        rect.set(cx - halfW, y, cx + halfW, y + dp(54f));
        c.drawRoundRect(rect, dp(12f), dp(12f), scenePaint);
        scenePaint.setStyle(Paint.Style.FILL);
        bold(c, pushTitle, cx, y + dp(17f), 15f * (1f + 0.03f * pulse), crewColor(crew), Paint.Align.CENTER);
        pushW = textPaint.measureText(pushTitle);
        float bw = dp(290f);
        float bx = cx - bw / 2f;
        float by = y + dp(25f);
        bar(c, bx, by, bx + bw, by + dp(8f), fill, fill >= 1f ? 0xFF35D0BA : fill > 0.4f ? 0xFFF5C518 : 0xFFF0655D);
        // The window running out, under the response bar.
        scenePaint.setColor(0x66FFFFFF);
        float run = bw * (left / PUSH_SECONDS);
        if (run > dp(2f)) {
            rect.set(bx, by + dp(11f), bx + run, by + dp(13f));
            c.drawRoundRect(rect, dp(1f), dp(1f), scenePaint);
        }
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
        panel(c, P_CHIP, cx - dp(78f), y0, cx + dp(78f), y0 + dp(30f), dp(9f),
                handicapOn ? 0xD8173A44 : 0xC0202634, handicapOn ? 0xE00A1E26 : 0xC8121620,
                handicapOn ? 0x9935D0BA : 0x664A5568);
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
                } else {
                    // A glint on a won pip, so it reads as a medal rather than a dot.
                    scenePaint.setColor(0x80FFFFFF);
                    c.drawCircle(x - rr * 0.3f, y - rr * 0.32f, rr * 0.36f, scenePaint);
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
        String under;
        if (state == State.READY) {
            under = "the clock starts when you do  ·  tap a lane to choose your water";
        } else if (crewCount() > 1) {
            under = gap >= 0 ? "LEADING THE FIELD" : "BEHIND " + crewName(lead);
        } else {
            under = gap >= 0 ? "AHEAD" : "BEHIND";
        }
        boolean sprintHud = sprinting && state == State.RACING;
        boolean bannerHud = !sprintHud && sessionSeconds < splitBannerUntil && state == State.RACING
                // The push panel is drawn last and lands in this same band: a live call owns it.
                && pushCrew < 0 && sessionSeconds >= pushResultUntil;

        // The gap card: one glass panel that grows to hold the split banner or the sprint call, edged
        // and lit in the colour of the gap so it reads from the seat before the number does.
        float gy = hudH * 0.55f;
        float cardW = Math.max(dp(170f), gapPanelW + dp(44f));
        float cardBottom = sprintHud ? gy + dp(82f) : bannerHud ? gy + dp(70f) : gy + dp(28f);
        int gapRgb = gapColor & 0x00FFFFFF;
        panel(c, sprintHud ? P_GAP_SPRINT : bannerHud ? P_GAP_BANNER : P_GAP, w / 2f - cardW / 2f, gy - dp(48f),
                w / 2f + cardW / 2f, cardBottom, dp(14f), GLASS_TOP, GLASS_BOT, (0x88 << 24) | gapRgb);
        if (state != State.READY) {
            Fx.glow(c, w / 2f, gy - dp(16f), dp(90f), 0x30000000 | gapRgb);
            // A coloured bar under the top edge: green when you lead, red when you trail.
            scenePaint.setStyle(Paint.Style.FILL);
            scenePaint.setColor((0xC0 << 24) | gapRgb);
            rect.set(w / 2f - cardW * 0.3f, gy - dp(48f), w / 2f + cardW * 0.3f, gy - dp(45f));
            c.drawRoundRect(rect, dp(1.5f), dp(1.5f), scenePaint);
        }
        float gapSize = state == State.READY ? 22f : 44f;
        bold(c, gapText, w / 2f + dp(2f), gy + dp(3f), gapSize, 0x90000000, Paint.Align.CENTER);
        bold(c, gapText, w / 2f, gy, gapSize, gapColor, Paint.Align.CENTER);
        float widest = textPaint.measureText(gapText);
        label(c, under, w / 2f, gy + dp(20f), 10f, DIM, Paint.Align.CENTER);
        widest = Math.max(widest, dimPaint.measureText(under));

        if (sprintHud) {
            widest = Math.max(widest, drawSprintHud(c, w, hudH, speed));
        } else if (bannerHud) {
            bold(c, splitBanner, w / 2f, gy + dp(46f), 16f, WARN, Paint.Align.CENTER);
            widest = Math.max(widest, textPaint.measureText(splitBanner));
            label(c, splitBannerSub, w / 2f, gy + dp(62f), 10f, TEXT, Paint.Align.CENTER);
            widest = Math.max(widest, dimPaint.measureText(splitBannerSub));
        }
        gapPanelW = widest;

        // Your pace on the left, the field's on the right, each on its own card.
        float py = hudH * 0.5f;
        String yourPace = pace(speed);
        glass(c, P_PACE_L, dp(6f), py - dp(30f), dp(16f) + Math.max(dp(80f), paceLW) + dp(12f), py + dp(23f));
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(ACCENT);
        rect.set(dp(6f), py - dp(20f), dp(9f), py + dp(13f));
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), scenePaint);
        bold(c, yourPace, dp(16f), py, 22f, TEXT, Paint.Align.LEFT);
        float lw = textPaint.measureText(yourPace);
        label(c, "YOUR PACE /500", dp(16f), py + dp(16f), 9f, DIM, Paint.Align.LEFT);
        paceLW = Math.max(lw, dimPaint.measureText("YOUR PACE /500"));
        String rv = rightHudValue();
        String rc = rightHudCaption();
        glass(c, P_PACE_R, w - dp(16f) - Math.max(dp(80f), paceRW) - dp(12f), py - dp(30f), w - dp(6f), py + dp(23f));
        scenePaint.setColor(BLUE);
        rect.set(w - dp(9f), py - dp(20f), w - dp(6f), py + dp(13f));
        c.drawRoundRect(rect, dp(1.5f), dp(1.5f), scenePaint);
        bold(c, rv, w - dp(16f), py, 22f, BLUE, Paint.Align.RIGHT);
        float rw = textPaint.measureText(rv);
        label(c, rc, w - dp(16f), py + dp(16f), 9f, DIM, Paint.Align.RIGHT);
        paceRW = Math.max(rw, dimPaint.measureText(rc));

        drawSplitTable(c, w, hudH);
        if (crewCount() > 1) {
            drawStandings(c, w, hudH);
        }

        // Footer stats, on a glass strip with dividers between the figures.
        float fy = h - dp(14f);
        float col = w / 5f;
        glass(c, P_FOOTER, dp(10f), h - dp(56f), w - dp(10f), h - dp(4f));
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x22FFFFFF);
        for (int k = 1; k < 5; k++) {
            c.drawRect(col * k - dp(0.5f), h - dp(46f), col * k + dp(0.5f), h - dp(14f), scenePaint);
        }
        stat(c, col * 0.5f, fy, String.format(java.util.Locale.US, "%.0f", raceDistance()),
                "OF " + raceMeters + " M");
        stat(c, col * 1.5f, fy, clock(raceTime()), "TIME");
        stat(c, col * 2.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 3.5f, fy, status == null ? "0" : String.valueOf(status.watts), "WATTS");
        stat(c, col * 4.5f, fy, boostText(boostAt(youLanePos)), LANE_WATER[youLane]);
    }

    /** Countdown, how hard you are pushing against your own range, and the one boat in reach. */
    private float drawSprintHud(Canvas c, float w, float hudH, float speed) {
        float left = (float) Math.max(0, raceMeters - raceDistance());
        float y = hudH * 0.55f + dp(46f);
        float pulse = 1f + 0.06f * (float) Math.sin(sessionSeconds * 8);
        String head = String.format(java.util.Locale.US, "SPRINT  ·  %.0f m TO GO", left);
        bold(c, head, w / 2f, y, 17f * pulse, WARN, Paint.Align.CENTER);
        float widest = textPaint.measureText(head);
        // The bar fills from your usual speed to a little past your top end.
        double lo = profile.typicalSpeed();
        double hi = profile.highSpeed() * 1.03;
        float fill = (float) Math.max(0, Math.min(1, (speed - lo) / Math.max(0.1, hi - lo)));
        float bw = dp(220f);
        float bx = w / 2f - bw / 2f;
        float by = y + dp(8f);
        bar(c, bx, by, bx + bw, by + dp(7f), fill, fill >= 1f ? 0xFFF5C518 : fill > 0.5f ? 0xFF35D0BA : 0xFF6F8CFF);
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
        return Math.max(widest, Math.max(bw, dimPaint.measureText(target)));
    }

    private void drawSplitTable(Canvas c, float w, float hudH) {
        int n = splitCount();
        if (n < 2) {
            return;
        }
        float x = w * 0.14f;
        float y = dp(22f);
        // Six rows ending at the marker you are rowing toward, so the live 'to go' row is always shown.
        int first = Math.max(1, Math.min(youNextSplit, n) - 5);
        int rows = Math.min(6, n - first + 1);
        glass(c, P_SPLITS, x - dp(12f), y - dp(16f), x + Math.max(dp(150f), splitsW) + dp(14f),
                y + dp(16f) * rows + dp(8f));
        label(c, "SPLITS  /500   vs FASTEST", x, y, 9f, DIM, Paint.Align.LEFT);
        float widest = dimPaint.measureText("SPLITS  /500   vs FASTEST");
        int shown = 0;
        for (int k = first; k <= n && shown < 6; k++, shown++) {
            float ry = y + dp(16f) * (shown + 1);
            String row;
            if (splitRows[k] != null) {
                row = splitRows[k];
                label(c, row, x, ry, 11f, splitRowColor[k], Paint.Align.LEFT);
            } else if (k == youNextSplit && state == State.RACING) {
                double toGo = k * 500.0 - raceDistance();
                // The live row: a faint accent bar marks the 500 you are rowing.
                scenePaint.setStyle(Paint.Style.FILL);
                scenePaint.setColor(0x1C35D0BA);
                rect.set(x - dp(6f), ry - dp(12f), x + Math.max(dp(150f), splitsW) + dp(6f), ry + dp(4f));
                c.drawRoundRect(rect, dp(4f), dp(4f), scenePaint);
                row = String.format(java.util.Locale.US, "%5d m   %.0f m to go", k * 500, toGo);
                label(c, row, x, ry, 11f, DIM, Paint.Align.LEFT);
            } else {
                row = String.format(java.util.Locale.US, "%5d m", k * 500);
                label(c, row, x, ry, 11f, FAINT, Paint.Align.LEFT);
            }
            widest = Math.max(widest, dimPaint.measureText(row));
        }
        splitsW = widest;
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
        float right = x + dp(150f) + Math.max(dp(40f), standGapW) + dp(14f);
        glass(c, P_STANDINGS, x - dp(12f), y - dp(16f), right, y + dp(17f) * (n + 1) + dp(8f));
        label(c, "STANDINGS", x, y, 9f, DIM, Paint.Align.LEFT);
        float gapW = 0f;
        int row = 1;
        boolean youDrawn = false;
        for (int r = 0; r <= n; r++) {
            float ry = y + dp(17f) * row;
            if (!youDrawn && (r == n || you >= crewDistance(order[r]))) {
                // Your row carries a highlight, so your place reads at a glance.
                scenePaint.setStyle(Paint.Style.FILL);
                scenePaint.setColor(0x2635D0BA);
                rect.set(x - dp(6f), ry - dp(13f), right - dp(6f), ry + dp(4f));
                c.drawRoundRect(rect, dp(4f), dp(4f), scenePaint);
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
            String gs = String.format(java.util.Locale.US, "%s%.0f m", g >= 0 ? "+" : "−", Math.abs(g));
            label(c, gs, x + dp(150f), ry, 11f, g >= 0 ? BAD : DIM, Paint.Align.LEFT);
            gapW = Math.max(gapW, dimPaint.measureText(gs));
            row++;
        }
        standGapW = gapW;
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 18f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, DIM, Paint.Align.CENTER);
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
        finishOverlay(c, w, h);
        accentPaint.setColor(ACCENT);
        int crews = Math.min(MAX_CREWS, crewCount());
        int resultCol = resultWon ? ACCENT : BAD;
        int resultRgb = resultCol & 0x00FFFFFF;
        // The result card: glass, edged in the result's colour, with a lit band across its head.
        float sy = h * 0.70f;
        float cardW = Math.max(w * 0.44f, finishW + dp(64f));
        float cardTop = h * 0.20f - dp(44f);
        float cardBottom = sy + (pushesCalled > 0 ? dp(78f) : dp(62f));
        Fx.glow(c, w / 2f, h * 0.20f - dp(8f), dp(220f), 0x30000000 | resultRgb);
        panel(c, P_FINISH, w / 2f - cardW / 2f, cardTop, w / 2f + cardW / 2f, cardBottom, dp(18f),
                0xE41A2638, 0xF00A111C, (0x99 << 24) | resultRgb);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor((0xD0 << 24) | resultRgb);
        rect.set(w / 2f - cardW * 0.32f, cardTop, w / 2f + cardW * 0.32f, cardTop + dp(4f));
        c.drawRoundRect(rect, dp(2f), dp(2f), scenePaint);
        // A divider between the race and the week.
        scenePaint.setColor(0x24FFFFFF);
        c.drawRect(w / 2f - cardW * 0.38f, sy - dp(24f), w / 2f + cardW * 0.38f, sy - dp(23f), scenePaint);

        bold(c, resultTitle, w / 2f + dp(2f), h * 0.20f + dp(2f), 24f, 0x90000000, Paint.Align.CENTER);
        bold(c, resultTitle, w / 2f, h * 0.20f, 24f, resultCol, Paint.Align.CENTER);
        float widest = textPaint.measureText(resultTitle);
        bold(c, clock(finishTime), w / 2f, h * 0.32f, 46f, TEXT, Paint.Align.CENTER);
        label(c, resultMargin, w / 2f, h * 0.32f + dp(24f), 12f, DIM, Paint.Align.CENTER);
        widest = Math.max(widest, dimPaint.measureText(resultMargin));
        float y = h * 0.32f + dp(52f);
        if (crews > 1) {
            for (int i = 0; i < crews; i++) {
                label(c, resultRows[i], w / 2f, y, 12f, crewColor(i), Paint.Align.CENTER);
                widest = Math.max(widest, dimPaint.measureText(resultRows[i]));
                y += dp(18f);
            }
        }
        if (!resultSplits.isEmpty()) {
            label(c, resultSplits, w / 2f, y + dp(8f), 12f, WARN, Paint.Align.CENTER);
            widest = Math.max(widest, dimPaint.measureText(resultSplits));
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
            widest = Math.max(widest, dimPaint.measureText(resultQuote));
        }

        // The week's series, then the handicap the next race will carry.
        drawSeriesPips(c, w / 2f, sy, true);
        bold(c, seriesLine, w / 2f, sy + dp(30f), seriesJustDecided() ? 20f : 14f,
                seriesDecided ? (seriesYou >= 2 ? ACCENT : BAD) : WARN, Paint.Align.CENTER);
        String hcp = String.format(java.util.Locale.US, "NEXT RACE: THE FIELD STARTS %.0f m %s%s",
                Math.abs(nextFieldMetres), nextFieldMetres >= 0 ? "UP" : "BACK",
                handicapOn ? "" : "  (HANDICAP OFF)");
        label(c, hcp, w / 2f, sy + dp(48f), 11f, DIM, Paint.Align.CENTER);
        finishW = Math.max(widest, dimPaint.measureText(hcp));
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
        finishOverlay(c, w, h);
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x60040810);
        c.drawRect(0, 0, w, h, scenePaint);
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
        glass(c, P_ANALYSIS, w * 0.08f, top - dp(24f), w * 0.92f, top + rowH * n + dp(62f));
        // Alternate rows lightly banded, so a row reads straight across at arm's length.
        scenePaint.setStyle(Paint.Style.FILL);
        scenePaint.setColor(0x0CFFFFFF);
        for (int k = 1; k <= n; k += 2) {
            float y = top + rowH * k + dp(8f);
            c.drawRect(w * 0.08f + dp(8f), y - rowH * 0.5f - dp(4f), w * 0.92f - dp(8f), y + rowH * 0.5f - dp(4f),
                    scenePaint);
        }
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
            slab(c, midX, d >= 0 ? midX + len : midX - len, y - dp(10f), y, d >= 0 ? 0xFF35D0BA : 0xFFF0655D);
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
        glass(c, P_REPLAY, x0 - dp(20f), ry - dp(48f), x1 + dp(20f),
                ry + dp(30f));
        label(c, "REPLAY  ·  12x", x0, ry - dp(30f), 9f, DIM, Paint.Align.LEFT);
        bar(c, x0, ry - dp(2.5f), x1, ry + dp(2.5f), 0f, 0);
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
        Fx.glow(c, x, y, dp(22f), 0x50000000 | (color & 0x00FFFFFF));
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
