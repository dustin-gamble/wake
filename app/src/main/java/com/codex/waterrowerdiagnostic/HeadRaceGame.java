package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Head Race: you against three boats with different race plans, all calibrated to your own best
 * time over the distance. The Flyer goes out hard and fades, the Metronome holds even splits,
 * the Closer negative-splits. Teaches race craft: where you lose the race is as telling as whether.
 *
 * <p>3.15.0 fills the empty space with instruments that move ("head race is good, but I need more
 * gauges to help show my efforts"): a gap graph to each crew over the last two minutes, one power
 * bar per stroke against your own average, a distance-to-go ribbon with every crew on it, and the
 * shape of your last drive from the pulse meter.
 *
 * <p>3.19.4 makes the course a place: a bank with a crowd that roars when you take the lead, lane
 * buoys, 250 m boards, a finish line that sails in over the last 80 m, a splash on every catch,
 * "PASSED" callouts as the order changes, and confetti when you win.
 *
 * <p>The head-race upgrade makes it a real head race rather than a lane race:
 * <ul>
 *   <li><b>Staggered starts.</b> The marshal sends the crews off six seconds apart - the Closer
 *   and the Metronome ahead of you, the Flyer behind. Everyone races the clock from their own
 *   start, so the boat you are chasing on the water is not the same thing as the place you hold on
 *   time. The big placing is on time; the boats on the water are who you have to get past.</li>
 *   <li><b>One shared river, no lanes.</b> Crews hold a line across the river, and catching one
 *   means finding water to pass it: sit on its stern and you are held up (you lose the metres you
 *   would have gained). Steer with the handle sensor (tilt is a rudder), or without one an auto-cox
 *   steers round traffic; tapping the water picks a line for six seconds.</li>
 *   <li><b>Bends and bridges.</b> The inside of a bend is shorter - up to 4% on the inside line,
 *   4% lost on the outside - and bridges put two stone piers in the river. Hitting one costs 4 m.</li>
 *   <li><b>A course-record board</b> per distance, top five with dates, and your pace against
 *   the record live in the corner.</li>
 *   <li><b>A commentary ticker</b> along the bottom calling the race.</li>
 * </ul>
 *
 * <p>3.23.0 turns the single race into a regatta:
 * <ul>
 *   <li><b>A two-day event.</b> DAY 1 is your heat - the first two crews on the clock go straight
 *   through, third and fourth get a repechage the same afternoon - and DAY 2 is the final, rowed
 *   against a field sharpened to the time you actually posted in the heat. The stage survives
 *   leaving the app, so the final is still waiting for you tomorrow.</li>
 *   <li><b>Four courses</b>, each with its own record board and its own character: the TIDEWAY's
 *   two long bends, the CITY REACH's four bridges, MILL CUT's short sharp bends worth more, and
 *   LOCH ERNE's open sweeping water with no bridges at all. Tap the course plate to change.</li>
 *   <li><b>Overtaking pays.</b> Row through a crew and you sit in its puddles: eight seconds of
 *   washed-down water worth up to 8% more distance made good, with the window draining on screen.
 *   Pass a second crew before it runs out and the chain builds.</li>
 *   <li><b>The cox on the radio</b> at every bend, arch and pass - a real call about the line you
 *   are actually holding, not a caption: wide through a bend and you get told about it.</li>
 *   <li><b>Your splits against the record</b> afterwards: every 250 m or 500 m against the splits
 *   the record holder actually rowed (stored when the record was set), where you won the race and
 *   where you gave it away.</li>
 * </ul>
 */
final class HeadRaceGame extends GameView {

    private enum Phase { READY, RACING, DONE }

    /** Speed = base x (1 + a x (0.5 - t/T)); a > 0 fades, a < 0 closes. Integrates to exactly D. */
    private static final class Rival {
        final String name;
        final int color;
        final float shape;
        final float finishFactor;
        /** Seconds this crew starts after you: negative is ahead of you on the water. */
        final double startOffset;
        /** Where it holds across the river, 0 far bank to 1 near bank. */
        final float home;
        double finishTime;

        Rival(String name, int color, float shape, float finishFactor, double startOffset, float home) {
            this.name = name;
            this.color = color;
            this.shape = shape;
            this.finishFactor = finishFactor;
            this.startOffset = startOffset;
            this.home = home;
        }

        double distanceAt(double t, int meters) {
            double T = finishTime;
            double base = meters / T;
            double tt = Math.max(0.0, Math.min(t, T));
            return base * (tt + shape * (0.5 * tt - tt * tt / (2 * T)));
        }

        float speedAt(double t) {
            double T = finishTime;
            if (t >= T) {
                return 0f;
            }
            return (float) ((finishTime > 0 ? 1 : 0) * (1 + shape * (0.5 - t / T)));
        }
    }

    private final PersonalBests bests;
    private final RiverRenderer river;
    private static final double START_INTERVAL = 6.0;
    /** When the marshal sends the first crew ahead of you away, from the moment the screen opens. */
    private static final double FIRST_OFF = 2.0;
    private final Rival[] rivals = {
            new Rival("FLYER", WARN, 0.30f, 0.99f, START_INTERVAL, 0.667f),
            new Rival("METRONOME", BLUE, 0f, 1.00f, -START_INTERVAL, 0.333f),
            new Rival("CLOSER", 0xFFB48CFF, -0.30f, 1.01f, -2 * START_INTERVAL, 0f),
    };
    private final float[] rivalX = new float[3];
    /** Session second each crew is sent off; +infinity until it is. */
    private final double[] rivalStartAt = new double[3];
    private final boolean[] rivalLaunchCalled = new boolean[3];
    private final boolean[] rivalFinishCalled = new boolean[3];
    private final double[] rivalWater = new double[3];
    private final float[] rivalSpeed = new float[3];
    private final float[] rivalLat = new float[3];
    private final float[] prevRel = new float[3];
    private final boolean[] waterAhead = new boolean[3];
    private double callAt;
    private boolean callMade;

    /* ---------- the courses ---------- */

    /**
     * A stretch of river with its own shape and its own record board. Everything is a fraction of
     * the race distance, so every course draws properly at 500 m and at 5 km.
     */
    private static final class Course {
        final String id;
        final String name;
        final String blurb;
        /** Bridge positions as fractions of the course. */
        final float[] bridges;
        final String[] bridgeNames;
        /** {start, end, inside bank: 0 far / 1 near} per bend, as fractions. */
        final float[][] bends;
        /** Metres made good per metre rowed on the perfect inside line. */
        final float bendGain;

        Course(String id, String name, String blurb, float[] bridges, String[] bridgeNames,
               float[][] bends, float bendGain) {
            this.id = id;
            this.name = name;
            this.blurb = blurb;
            this.bridges = bridges;
            this.bridgeNames = bridgeNames;
            this.bends = bends;
            this.bendGain = bendGain;
        }
    }

    /**
     * The first course keeps the original key space ({@code headrace.board.<m>}), so records set
     * before there were courses stay where they are rather than being orphaned.
     */
    private static final Course[] COURSES = {
            new Course("", "THE TIDEWAY", "two long bends, two bridges - the classic head course",
                    new float[]{0.33f, 0.78f}, new String[]{"MILL BRIDGE", "IRON BRIDGE"},
                    new float[][]{{0.12f, 0.24f, 0}, {0.52f, 0.66f, 1}}, 0.04f),
            new Course("city", "CITY REACH", "four arches and one long bend - traffic and stonework",
                    new float[]{0.16f, 0.38f, 0.58f, 0.84f},
                    new String[]{"KING'S BRIDGE", "FOUNDRY BRIDGE", "BLACKGATE", "STATION BRIDGE"},
                    new float[][]{{0.44f, 0.70f, 0}}, 0.035f),
            new Course("mill", "MILL CUT", "three sharp bends worth more, one low arch",
                    new float[]{0.62f}, new String[]{"LOW ARCH"},
                    new float[][]{{0.14f, 0.22f, 0}, {0.36f, 0.45f, 1}, {0.72f, 0.81f, 0}}, 0.055f),
            new Course("loch", "LOCH ERNE", "open water, no bridges, two huge sweeps",
                    new float[0], new String[]{"-"},
                    new float[][]{{0.10f, 0.40f, 1}, {0.55f, 0.88f, 0}}, 0.03f),
    };

    private int courseIndex;
    private float courseBendGain = 0.04f;

    /* ---------- the two-day event ---------- */

    private static final int EV_NONE = 0;
    private static final int EV_HEAT = 1;
    private static final int EV_REPECHAGE = 2;
    private static final int EV_FINAL = 3;
    private static final int EV_OUT = 4;
    private static final int EV_MEDAL = 5;

    private int eventStage = EV_NONE;
    /** Your heat time, which the final's field is tuned to. */
    private float eventHeatTime;
    private int eventHeatPlace;
    private String eventCourseId = "";
    private int eventMeters;
    /** Finals won, all time. */
    private int eventWins;
    /** True when a stage is live but you are on the wrong course or distance for it. */
    private boolean eventWaiting;
    /** Plate and banner text, rebuilt only when the event moves: the frame loop allocates nothing. */
    private String plateCourseTop = "";
    private String plateCourseSub = "";
    private String plateEventTop = "";
    private String plateEventSub = "";
    private String dayBanner;
    private int dayBannerColor = ACCENT;
    private float eventPillL;
    private float eventPillT;
    private float eventPillR;
    private float eventPillB;
    private float coursePillL;
    private float coursePillT;
    private float coursePillR;
    private float coursePillB;

    /* ---------- puddles: the overtaking boost ---------- */

    private static final float BOOST_GAIN = 0.08f;
    private static final double BOOST_SECONDS = 8.0;
    private double boostUntil;
    private float boostGain;
    private int boostChain;
    private String boostFrom = "";
    /** Per crew, when it last paid a boost: passing the same crew again has to be earned. */
    private final double[] boostedAt = {-100, -100, -100};

    /* ---------- splits against the record ---------- */

    private static final int MAX_SPLITS = 24;
    private int splitEveryM = 500;
    private final float[] mySplit = new float[MAX_SPLITS];
    private int mySplitCount;
    /** The splits the record holder actually rowed, when they were stored with the record. */
    private final float[] recSplit = new float[MAX_SPLITS];
    private int recSplitCount;
    /** The record you were chasing this race, captured before your own finish rewrites the board. */
    private final float[] cmpSplit = new float[MAX_SPLITS];
    private int cmpSplitCount;
    private float cmpRecord;
    /**
     * The finish panel's text, built once when the race ends. The panel is static for as long as
     * it is on screen, so formatting ten rows of it every frame was pure garbage.
     */
    private final String[] splitMetresText = new String[MAX_SPLITS];
    private final String[] splitClockText = new String[MAX_SPLITS];
    private final String[] splitSegText = new String[MAX_SPLITS];
    /** Null where there is nothing to compare that split with. */
    private final String[] splitDeltaText = new String[MAX_SPLITS];
    private final float[] splitDelta = new float[MAX_SPLITS];
    private String splitsHeading = "YOUR SPLITS";
    private String splitsFooter = "";

    /* ---------- the cox on the radio ---------- */

    private final char[] radioChars = new char[128];
    private int radioLen;
    private int radioBreak = -1;
    private String radioTag = "COX";
    private int radioColor = TEXT;
    private double radioStart;
    private double radioUntil;
    private static final int RADIO_Q = 5;
    private final String[] radioQText = new String[RADIO_Q];
    private final String[] radioQTag = new String[RADIO_Q];
    private final int[] radioQColor = new int[RADIO_Q];
    private final double[] radioQSeconds = new double[RADIO_Q];
    private int radioQCount;
    private boolean[] bendLineCalled = new boolean[0];
    private boolean[] bendOutCalled = new boolean[0];

    /* ---------- the course ---------- */

    /** Metres along the course, with bend gains and blocking and pier penalties applied. */
    private double course;
    private double lastSessionMeters;
    private float[] bridgeM = new float[0];
    private String[] bridgeName = new String[0];
    private boolean[] bridgeCalled = new boolean[0];
    private float[] bendStart = new float[0];
    private float[] bendEnd = new float[0];
    /** 0: the inside of the bend is the far bank (top); 1: the near bank (bottom). */
    private int[] bendInside = new int[0];
    private boolean[] bendCalled = new boolean[0];
    private int splitsCalled;
    private boolean finalCalled;
    private static final float[] PIER_L = {0.2f, 0.8f};
    private static final float PIER_HALF = 0.07f;
    private static final float PIER_PENALTY_M = 4f;
    private static final float LAT_CLEAR = 0.2f;

    /* ---------- steering ---------- */

    private float yourLat = 1f;
    private float tapTarget = -1f;
    private double tapUntil;
    private boolean blocked;
    private double blockedSaidAt = -100;
    private int blockedBy = -1;
    private float lastGain;
    private float lastWaterTop;
    private float lastWaterBottom;
    private float lastLaneH;
    private float yourY;
    private final Fx.Shake shake = new Fx.Shake();

    /** Gap to each rival, one sample a second, the last two minutes. */
    private static final int GAP_SAMPLES = 120;
    private final float[][] gaps = new float[3][GAP_SAMPLES];
    private int gapCount;
    private int gapHead;
    private int lastGapSecond = -1;

    /** Measured average power of each recent stroke. */
    private static final int STROKE_BARS = 28;
    private final float[] strokePower = new float[STROKE_BARS];
    private int strokeCount;
    private int strokeHead;
    private PulseMeter.Stroke lastSeenStroke;
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private int lastAhead = -1;
    private String callout = "";
    private int calloutColor = ACCENT;
    private double calloutUntil;
    private double confettiUntil;
    private float cheer;
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;
    private final android.graphics.Path trace = new android.graphics.Path();
    private final android.graphics.Path shape = new android.graphics.Path();
    private static final int[] CONFETTI = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};

    /* ---------- course-record board ---------- */

    private static final int BOARD_SIZE = 5;
    private final float[] boardTimes = new float[BOARD_SIZE];
    private final String[] boardDates = new String[BOARD_SIZE];
    private int boardCount;
    /** Where the race just finished landed on the board, or -1. */
    private int boardRank = -1;

    /* ---------- commentary ticker ---------- */

    private static final int TICK_MAX = 10;
    private final String[] tickText = new String[TICK_MAX];
    private final int[] tickColor = new int[TICK_MAX];
    private final float[] tickX = new float[TICK_MAX];
    private final float[] tickW = new float[TICK_MAX];
    private final boolean[] tickPlaced = new boolean[TICK_MAX];
    private int tickCount;
    private double lastSayAt;
    private int chatterIndex;
    private double bigStrokeSaidAt = -100;
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Its own paint: the ticker measures its text with tickPaint, so that size must not move. */
    private final Paint radioPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int raceMeters = 2000;
    private Phase phase = Phase.READY;
    private double raceStartSeconds;
    private double finishTime;
    private int placing;

    HeadRaceGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
        boardText.setTextSize(dp(11f));
        boardText.setFakeBoldText(true);
        tickPaint.setTextSize(dp(12f));
        tickPaint.setFakeBoldText(true);
        radioPaint.setTextSize(dp(11.5f));
        radioPaint.setFakeBoldText(true);
    }

    void setRaceMeters(int m) {
        raceMeters = m;
        phase = Phase.READY;
    }

    int raceMeters() {
        return raceMeters;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        placing = 0;
        gapCount = 0;
        gapHead = 0;
        lastGapSecond = -1;
        strokeCount = 0;
        strokeHead = 0;
        lastAhead = -1;
        calloutUntil = 0;
        confettiUntil = 0;
        course = 0;
        lastSessionMeters = 0;
        yourLat = 1f;
        tapTarget = -1f;
        blocked = false;
        blockedBy = -1;
        splitsCalled = 0;
        finalCalled = false;
        boardRank = -1;
        chatterIndex = 0;
        // sessionSeconds restarts at zero on every race, so stale stamps would mute these calls.
        blockedSaidAt = -100;
        bigStrokeSaidAt = -100;
        tapUntil = 0;
        boostUntil = 0;
        boostGain = 0f;
        boostChain = 0;
        mySplitCount = 0;
        radioQCount = 0;
        radioUntil = 0;
        radioLen = 0;
        for (int i = 0; i < 3; i++) {
            boostedAt[i] = -100;
        }
        loadCourse();
        loadEvent();
        buildCourse();
        loadBoard();
        // The field is set from your best; without one, from the pace you usually hold.
        double split = profile.typicalSplit();
        if (!(split > 90 && split < 240)) {
            split = 135;
        }
        float reference = bests.has("time." + raceMeters)
                ? bests.get("time." + raceMeters, 0f)
                : (float) (raceMeters / 500.0 * split);
        // The final is rowed against the time you actually posted in the heat, so a fast heat
        // buys you a harder final rather than an easier one.
        if (eventActive() && eventStage == EV_FINAL && eventHeatTime > 30f) {
            reference = Math.min(reference, eventHeatTime);
        }
        float stageFactor = !eventActive() ? 1f
                : eventStage == EV_FINAL ? 0.97f : eventStage == EV_REPECHAGE ? 1.03f : 1f;
        for (int i = 0; i < 3; i++) {
            Rival r = rivals[i];
            r.finishTime = Math.max(30.0, reference * r.finishFactor * stageFactor);
            rivalLat[i] = r.home;
            rivalLaunchCalled[i] = false;
            rivalFinishCalled[i] = false;
            waterAhead[i] = r.startOffset < 0;
            prevRel[i] = 0f;
            rivalX[i] = 0f;
        }
        // The marshal sends the crews ahead of you away first, six seconds apart, then calls you.
        // The crew behind you goes six seconds after you actually start.
        rivalStartAt[2] = FIRST_OFF;
        rivalStartAt[1] = FIRST_OFF + START_INTERVAL;
        rivalStartAt[0] = Double.POSITIVE_INFINITY;
        callAt = FIRST_OFF + 2 * START_INTERVAL;
        callMade = false;
        loadSplits();
        cmpSplitCount = recSplitCount;
        System.arraycopy(recSplit, 0, cmpSplit, 0, recSplitCount);
        cmpRecord = boardCount > 0 ? boardTimes[0] : 0f;
        tickCount = 0;
        say(stageTitle() + " over " + raceMeters + " m at " + COURSES[courseIndex].name + " - "
                + COURSES[courseIndex].blurb + ".", TEXT);
        say("Crews are sent off " + (int) START_INTERVAL + " seconds apart and race the clock - the"
                + " CLOSER and the METRONOME go ahead of you, the FLYER behind.", TEXT);
        if (eventActive()) {
            say(eventStage == EV_FINAL
                    ? "DAY 2: the final. The field is tuned to the time you rowed in the heat ("
                    + clock(eventHeatTime) + ") - they will not be slower today."
                    : eventStage == EV_REPECHAGE
                    ? "DAY 1, repechage: you were " + ordinal(eventHeatPlace).toLowerCase(java.util.Locale.US)
                    + " in the heat. Win this and you are in tomorrow's final - nothing else will do."
                    : "DAY 1, your heat. The first two on the clock go straight to tomorrow's final;"
                    + " third and fourth get a repechage this afternoon.", 0xFFF5C518);
        } else if (eventWaiting) {
            say("Your event is paused: it is over " + eventMeters + " m at " + eventCourseName()
                    + ". Tap the event plate to go back to it.", WARN);
        }
        if (boardCount > 0) {
            say("Course record here: " + clock(boardTimes[0]) + ", set " + boardDates[0] + ".", 0xFFF5C518);
        } else {
            say("No record yet at " + COURSES[courseIndex].name + " over " + raceMeters
                    + " m - the first finish sets it.", 0xFFF5C518);
        }
        say(hasSteering() ? "Steering is on the handle: tilt to take a line."
                : "No handle sensor - the cox steers you round traffic. Tap the water to pick a line.", DIM);
        radio("COX", "Sit ready. Row through a crew and you get eight seconds in its puddles.",
                ACCENT, 5.5);
        refreshPlates();
    }

    /** Bridges and bends as fractions of the chosen course, so every distance gets a full course. */
    private void buildCourse() {
        Course cs = COURSES[courseIndex];
        courseBendGain = cs.bendGain;
        splitEveryM = raceMeters <= 1000 ? 250 : 500;
        bridgeM = new float[cs.bridges.length];
        bridgeName = new String[cs.bridges.length];
        bridgeCalled = new boolean[cs.bridges.length];
        for (int i = 0; i < cs.bridges.length; i++) {
            bridgeM[i] = cs.bridges[i] * raceMeters;
            bridgeName[i] = cs.bridgeNames[i % cs.bridgeNames.length];
        }
        bendStart = new float[cs.bends.length];
        bendEnd = new float[cs.bends.length];
        bendInside = new int[cs.bends.length];
        bendCalled = new boolean[cs.bends.length];
        bendLineCalled = new boolean[cs.bends.length];
        bendOutCalled = new boolean[cs.bends.length];
        for (int i = 0; i < cs.bends.length; i++) {
            bendStart[i] = cs.bends[i][0] * raceMeters;
            bendEnd[i] = cs.bends[i][1] * raceMeters;
            bendInside[i] = (int) cs.bends[i][2];
        }
    }

    /* ---------- choosing a course ---------- */

    /** The first course keeps the pre-course key space so old records are not orphaned. */
    private String keyBase() {
        String id = COURSES[courseIndex].id;
        return id.isEmpty() ? "headrace." : "headrace." + id + ".";
    }

    private void loadCourse() {
        String id = bests.getString("headrace.course");
        courseIndex = 0;
        if (id == null) {
            return;
        }
        for (int i = 0; i < COURSES.length; i++) {
            if (COURSES[i].id.equals(id)) {
                courseIndex = i;
                return;
            }
        }
    }

    private void cycleCourse() {
        courseIndex = (courseIndex + 1) % COURSES.length;
        bests.putString("headrace.course", COURSES[courseIndex].id);
        start();
        if (eventWaiting) {
            say("Your event is still waiting at " + eventCourseName() + " over " + eventMeters + " m.", WARN);
        }
    }

    private String eventCourseName() {
        for (Course c : COURSES) {
            if (c.id.equals(eventCourseId)) {
                return c.name;
            }
        }
        return COURSES[0].name;
    }

    /* ---------- the two-day event ---------- */

    private void loadEvent() {
        eventStage = EV_NONE;
        eventHeatTime = 0f;
        eventHeatPlace = 0;
        eventCourseId = "";
        eventMeters = 0;
        eventWins = Math.round(bests.get("headrace.event.wins", 0f));
        String raw = bests.getString("headrace.event");
        if (raw != null && !raw.isEmpty()) {
            String[] p = raw.split("\\|", -1);
            if (p.length >= 5) {
                try {
                    eventStage = Integer.parseInt(p[0]);
                    eventHeatTime = Float.parseFloat(p[1]);
                    eventHeatPlace = Integer.parseInt(p[2]);
                    eventCourseId = p[3];
                    eventMeters = Integer.parseInt(p[4]);
                } catch (NumberFormatException ignored) {
                    eventStage = EV_NONE;
                }
            }
        }
        if (eventStage < EV_NONE || eventStage > EV_MEDAL) {
            eventStage = EV_NONE;
        }
        eventWaiting = eventLive() && !eventHere();
    }

    private void saveEvent() {
        bests.putString("headrace.event", eventStage + "|" + eventHeatTime + "|" + eventHeatPlace
                + "|" + eventCourseId + "|" + eventMeters);
    }

    /** A stage is still to be rowed. */
    private boolean eventLive() {
        return eventStage == EV_HEAT || eventStage == EV_REPECHAGE || eventStage == EV_FINAL;
    }

    /** That stage belongs to the course and distance now on screen. */
    private boolean eventHere() {
        return eventMeters == raceMeters && COURSES[courseIndex].id.equals(eventCourseId);
    }

    private boolean eventActive() {
        return eventLive() && eventHere();
    }

    private String stageTitle() {
        if (!eventActive()) {
            return "Time trial";
        }
        return eventStage == EV_FINAL ? "THE FINAL" : eventStage == EV_REPECHAGE ? "Repechage" : "Your heat";
    }

    /** The plate's two lines: what the event wants from you next. */
    private String eventPlateTop() {
        if (eventActive()) {
            return eventStage == EV_FINAL ? "DAY 2  ·  THE FINAL"
                    : eventStage == EV_REPECHAGE ? "DAY 1  ·  REPECHAGE" : "DAY 1  ·  HEAT";
        }
        if (eventWaiting) {
            return "EVENT PAUSED";
        }
        return "ENTER THE EVENT";
    }

    private String eventPlateSub() {
        if (eventActive()) {
            return eventStage == EV_FINAL ? "medals today - heat " + clock(eventHeatTime)
                    : eventStage == EV_REPECHAGE ? "win it or the event is over"
                    : "top two go through to the final";
        }
        if (eventWaiting) {
            return eventMeters + " m at " + eventCourseName() + " - tap to go back";
        }
        if (eventStage == EV_OUT) {
            return "knocked out last time - tap to enter again";
        }
        if (eventStage == EV_MEDAL) {
            return eventWins + (eventWins == 1 ? " final won" : " finals won") + " - tap to enter again";
        }
        return "two days: a heat, then the final";
    }

    /** Called whenever the event or the course moves - never from the frame loop. */
    private void refreshPlates() {
        // The hint lives on the short top line: with it on the sub-line the longest blurb ran
        // straight under the course glyph on the right of the plate.
        plateCourseTop = "COURSE  ·  " + COURSES[courseIndex].name + "  ·  TAP";
        plateCourseSub = COURSES[courseIndex].blurb;
        plateEventTop = eventPlateTop();
        plateEventSub = eventPlateSub();
        if (eventActive()) {
            dayBanner = plateEventTop;
            dayBannerColor = 0xFFF5C518;
        } else if (eventStage == EV_MEDAL) {
            dayBanner = "EVENT COMPLETE  ·  " + eventWins + (eventWins == 1 ? " FINAL WON" : " FINALS WON");
            dayBannerColor = ACCENT;
        } else if (eventStage == EV_OUT) {
            dayBanner = "EVENT OVER";
            dayBannerColor = BAD;
        } else {
            dayBanner = null;
        }
    }

    private void tapEvent() {
        if (eventActive()) {
            say(eventStage == EV_FINAL ? "The final is today - take a stroke and the clock starts."
                    : "Your heat is on this course. Take a stroke when you are ready.", 0xFFF5C518);
            radio("COX", eventStage == EV_FINAL ? "This is the one. Sit up, and go when you are ready."
                    : "Heat first. Two of us go straight through - be one of them.", 0xFFF5C518, 4.0);
            return;
        }
        if (eventLive()) {
            if (!COURSES[courseIndex].id.equals(eventCourseId)) {
                for (int i = 0; i < COURSES.length; i++) {
                    if (COURSES[i].id.equals(eventCourseId)) {
                        courseIndex = i;
                        bests.putString("headrace.course", COURSES[i].id);
                        break;
                    }
                }
                start();
                if (!eventHere()) {
                    say("Back at " + COURSES[courseIndex].name + ". Now set the distance chip to "
                            + eventMeters + " m and the event is on.", WARN);
                }
                return;
            }
            say("Your event is over " + eventMeters + " m - set the distance chip at the top to "
                    + eventMeters + " m.", WARN);
            return;
        }
        eventStage = EV_HEAT;
        eventHeatTime = 0f;
        eventHeatPlace = 0;
        eventCourseId = COURSES[courseIndex].id;
        eventMeters = raceMeters;
        saveEvent();
        start();
    }

    /* ---------- splits against the record ---------- */

    private String splitsKey() {
        return keyBase() + "splits." + raceMeters;
    }

    private void loadSplits() {
        recSplitCount = 0;
        String raw = bests.getString(splitsKey());
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String part : raw.split(",")) {
            if (recSplitCount >= MAX_SPLITS) {
                break;
            }
            try {
                float v = Float.parseFloat(part);
                if (v > 0f && !Float.isInfinite(v)) {
                    recSplit[recSplitCount++] = v;
                }
            } catch (NumberFormatException ignored) {
                // A corrupt split list is dropped rather than losing the board with it.
            }
        }
    }

    /** Stored only with a new record, so a later race compares against splits actually rowed. */
    private void saveSplits() {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < mySplitCount; k++) {
            if (k > 0) {
                sb.append(',');
            }
            sb.append(String.format(java.util.Locale.US, "%.1f", mySplit[k]));
        }
        bests.putString(splitsKey(), sb.toString());
    }

    /** The time the record holder was through {@code metres}, or 0 when nothing to compare with. */
    private float compareAt(int index, float metres) {
        if (index < cmpSplitCount) {
            return cmpSplit[index];
        }
        if (cmpRecord > 0f) {
            return cmpRecord * metres / raceMeters;   // no stored splits: the record at even pace
        }
        return 0f;
    }

    /* ---------- the cox on the radio ---------- */

    private void radio(String tag, String text, int color, double seconds) {
        if (sessionSeconds < radioUntil - 0.4) {
            if (radioQCount < RADIO_Q) {
                radioQText[radioQCount] = text;
                radioQTag[radioQCount] = tag;
                radioQColor[radioQCount] = color;
                radioQSeconds[radioQCount] = seconds;
                radioQCount++;
            }
            return;
        }
        setRadio(tag, text, color, seconds);
    }

    /** Urgent calls (a pier, a blocking crew) cut across whatever is being said. */
    private void radioNow(String tag, String text, int color, double seconds) {
        radioQCount = 0;
        setRadio(tag, text, color, seconds);
    }

    private void setRadio(String tag, String text, int color, double seconds) {
        radioLen = Math.min(text.length(), radioChars.length);
        text.getChars(0, radioLen, radioChars, 0);
        radioBreak = -1;
        if (radioLen > 38) {
            for (int i = Math.min(radioLen - 1, 38); i > 10; i--) {
                if (radioChars[i] == ' ') {
                    radioBreak = i;
                    break;
                }
            }
        }
        radioTag = tag;
        radioColor = color;
        radioStart = sessionSeconds;
        radioUntil = sessionSeconds + seconds;
    }

    private void pumpRadio() {
        if (radioQCount > 0 && sessionSeconds >= radioUntil) {
            setRadio(radioQTag[0], radioQText[0], radioQColor[0], radioQSeconds[0]);
            for (int k = 1; k < radioQCount; k++) {
                radioQText[k - 1] = radioQText[k];
                radioQTag[k - 1] = radioQTag[k];
                radioQColor[k - 1] = radioQColor[k];
                radioQSeconds[k - 1] = radioQSeconds[k];
            }
            radioQCount--;
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.RACING;
            raceStartSeconds = sessionSeconds;
            lastSessionMeters = sessionMeters;
            course = 0;
            // A rolling start before your call: the crews ahead still get their full interval.
            boolean early = false;
            for (int i = 0; i < 3; i++) {
                if (rivals[i].startOffset < 0) {
                    double due = sessionSeconds + rivals[i].startOffset;
                    if (rivalStartAt[i] > due) {
                        rivalStartAt[i] = due;
                        early = true;
                    }
                } else {
                    rivalStartAt[i] = sessionSeconds + rivals[i].startOffset;
                }
            }
            callMade = true;
            say(early ? "A rolling start - you're away early, and the crews ahead get their full"
                    + " interval. The clock is running!" : "And you're away! The clock is running.", ACCENT);
        }
        // A stroke's power is read when the pulse meter closes it - never from onStroke, which
        // lands a second late when instantaneous power has already collapsed.
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (phase == Phase.RACING && stroke != null && stroke != lastSeenStroke) {
            float power = !Double.isNaN(stroke.averagePowerW) ? (float) stroke.averagePowerW : s.watts;
            strokePower[strokeHead] = power;
            strokeHead = (strokeHead + 1) % STROKE_BARS;
            strokeCount = Math.min(STROKE_BARS, strokeCount + 1);
            if (power > profile.highWatts() * 1.05 && sessionSeconds - bigStrokeSaidAt > 25) {
                bigStrokeSaidAt = sessionSeconds;
                say("Big stroke - " + Math.round(power) + " W, above anything you usually pull.", ACCENT);
            }
            // The catch: a splash off each blade at your boat.
            float bx = getWidth() * 0.40f;
            float by = yourY > 0 ? yourY : getHeight() * (0.26f + 0.48f * 0.875f);
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k < 10; k++) {
                    fx.spawn(bx - dp(10f) + (float) Math.random() * dp(20f), by + side * dp(20f),
                            (float) (Math.random() - 0.7) * dp(60f), -dp(40f) - (float) Math.random() * dp(70f),
                            0.7f, dp(3.5f), 0xEEDDF2FF, true);
                }
            }
        }
        lastSeenStroke = stroke;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            float x = event.getX();
            float y = event.getY();
            // The plates are checked first: on the finish screen every other tap races again.
            if (phase != Phase.RACING && coursePillR > coursePillL
                    && x >= coursePillL && x <= coursePillR && y >= coursePillT && y <= coursePillB) {
                cycleCourse();
                return true;
            }
            if (phase != Phase.RACING && eventPillR > eventPillL
                    && x >= eventPillL && x <= eventPillR && y >= eventPillT && y <= eventPillB) {
                tapEvent();
                return true;
            }
            if (phase == Phase.DONE) {
                start();
                return true;
            }
            if (lastLaneH > 0 && y >= lastWaterTop && y <= lastWaterBottom) {
                // Pick a line: the boat heads there for six seconds, then the cox takes over again.
                tapTarget = clamp01((y - lastWaterTop - lastLaneH * 0.5f) / (lastLaneH * 3f));
                tapUntil = sessionSeconds + 6.0;
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private double raceTime() {
        return phase == Phase.READY ? 0 : sessionSeconds - raceStartSeconds;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private float latY(float lat) {
        return lastWaterTop + lastLaneH * (0.5f + 3f * lat);
    }

    /** 0..1 through bend {@code b} at course metres {@code m}, or -1 outside it. */
    private float bendFraction(int b, double m) {
        if (m < bendStart[b] || m > bendEnd[b]) {
            return -1f;
        }
        return (float) ((m - bendStart[b]) / (bendEnd[b] - bendStart[b]));
    }

    /** Extra distance made good per metre rowed at lateral {@code lat}: + inside, - outside. */
    private float bendGain(double m, float lat) {
        for (int b = 0; b < bendStart.length; b++) {
            float f = bendFraction(b, m);
            if (f >= 0f) {
                float side = bendInside[b] == 0 ? 1f - 2f * lat : 2f * lat - 1f;
                return courseBendGain * side * (float) Math.sin(Math.PI * f);
            }
        }
        return 0f;
    }

    /** Nudges a line out of the piers' water when a bridge is close ahead. */
    private float pierSafe(double m, float lat, float gapM) {
        for (float b : bridgeM) {
            double ahead = b - m;
            if (ahead > -gapM && ahead < 25) {
                for (float p : PIER_L) {
                    float edge = PIER_HALF + 0.07f;
                    if (Math.abs(lat - p) < edge) {
                        float out = lat < p ? p - edge : p + edge;
                        if (out < 0f || out > 1f) {
                            out = lat < p ? p + edge : p - edge;
                        }
                        lat = out;
                    }
                }
            }
        }
        return lat;
    }

    /**
     * The line a crew wants: its station, pulled toward the inside through a bend, out round any boat
     * it is closing on, and clear of bridge piers. Used for the rivals and for your auto-cox.
     */
    private float wantedLine(int self, double m, float home, float speed, float bendPull, float gapM) {
        float target = home;
        for (int b = 0; b < bendStart.length; b++) {
            if (m > bendStart[b] - 15 && m < bendEnd[b] - 10) {
                float inside = bendInside[b] == 0 ? 0.04f : 0.96f;
                target = home + (inside - home) * bendPull;
            }
        }
        for (int j = 0; j < 4; j++) {
            if (j == self) {
                continue;
            }
            double dj;
            float lj;
            float sj;
            if (j == 3) {
                if (phase == Phase.READY) {
                    continue;
                }
                dj = course;
                lj = yourLat;
                sj = boat.value();
            } else {
                if (!(sessionSeconds >= rivalStartAt[j])) {
                    continue;
                }
                dj = rivalWater[j];
                lj = rivalLat[j];
                sj = rivalSpeed[j];
            }
            double ahead = dj - m;
            if (ahead > -gapM * 0.6 && ahead < gapM * 3 && speed > sj - 0.05f
                    && Math.abs(lj - target) < 0.3f) {
                target = lj > 0.5f ? lj - 0.38f : lj + 0.38f;
            }
        }
        return clamp01(pierSafe(m, clamp01(target), gapM));
    }

    private void updateRace(float dt, float gapM, float ppm) {
        double now = sessionSeconds;
        for (int i = 0; i < 3; i++) {
            Rival r = rivals[i];
            boolean started = now >= rivalStartAt[i];
            double elapsed = started ? now - rivalStartAt[i] : 0;
            rivalWater[i] = started ? r.distanceAt(elapsed, raceMeters) : 0;
            rivalSpeed[i] = started ? r.speedAt(elapsed) * (raceMeters / (float) r.finishTime) : 0f;
            if (started && !rivalLaunchCalled[i]) {
                rivalLaunchCalled[i] = true;
                if (r.startOffset < 0) {
                    callout = r.name + " - GO!";
                    calloutColor = r.color;
                    calloutUntil = now + 1.4;
                    say("The " + r.name + " is sent off" + (r.startOffset < -START_INTERVAL
                            ? " - first crew away." : ", six seconds behind the CLOSER."), r.color);
                } else {
                    say("The FLYER is away six seconds behind you - and it always goes out hard.", r.color);
                }
            }
            if (started && !rivalFinishCalled[i] && elapsed >= r.finishTime) {
                rivalFinishCalled[i] = true;
                say("The " + r.name + " crosses the line: " + clock(r.finishTime) + ".", r.color);
            }
        }
        if (phase == Phase.READY && !callMade && now >= callAt) {
            callMade = true;
            callout = "YOUR CREW - GO!";
            calloutColor = ACCENT;
            calloutUntil = now + 2.0;
            say("Your crew is called. The clock starts on your first stroke.", ACCENT);
        }

        // Your distance along the course: what the monitor says you rowed, plus the bend.
        // Never backwards: a downward correction of the smoothed distance is not the boat reversing.
        double rowed = Math.max(0.0, sessionMeters - lastSessionMeters);
        lastSessionMeters = sessionMeters;
        lastGain = 0f;
        // Sitting in a beaten crew's puddles: washed-down water, worth real metres, draining away.
        boostGain = now < boostUntil
                ? BOOST_GAIN * (float) ((boostUntil - now) / BOOST_SECONDS) * Math.min(1.35f, 1f + 0.175f * (boostChain - 1))
                : 0f;
        if (boostGain <= 0f) {
            boostChain = 0;
        }
        if (phase == Phase.RACING) {
            lastGain = bendGain(course, yourLat);
            course += rowed * (1f + lastGain + boostGain);
        } else if (phase == Phase.DONE) {
            course += rowed;
        }
        pumpRadio();

        // Steering: the handle is a rudder; without it, a tapped line, else the auto-cox.
        float speed = boat.value();
        if (hasSteering() && Math.abs(steering()) > 0.15f) {
            tapTarget = -1f;
            yourLat += steering() * 0.6f * dt;
        } else if (tapTarget >= 0f && now < tapUntil) {
            yourLat += Math.max(-0.7f * dt, Math.min(0.7f * dt, tapTarget - yourLat));
        } else if (!hasSteering()) {
            tapTarget = -1f;
            // The cox keeps mid-river and only half-takes a bend: steering yourself onto the inside pays more.
            float want = phase == Phase.READY ? 1f : wantedLine(3, course, 0.5f, speed, 0.6f, gapM);
            yourLat += Math.max(-0.35f * dt, Math.min(0.35f * dt, want - yourLat));
        }
        yourLat = clamp01(yourLat);

        // Traffic: sit on a crew's stern and you are held there until you find water.
        boolean wasBlocked = blocked;
        blocked = false;
        if (phase == Phase.RACING) {
            for (int i = 0; i < 3; i++) {
                if (!(now >= rivalStartAt[i]) || rivalWater[i] >= raceMeters) {
                    continue;
                }
                float rel = (float) (rivalWater[i] - course);
                if (Math.abs(yourLat - rivalLat[i]) < LAT_CLEAR) {
                    if (prevRel[i] >= gapM - 0.3f && rel < gapM) {
                        course = rivalWater[i] - gapM;
                        rel = gapM;
                        blocked = true;
                        blockedBy = i;
                    } else if (rel >= 0f && rel < gapM) {
                        // Alongside and steering into it: the blades clash and push you off.
                        float away = yourLat >= rivalLat[i] ? 1f : -1f;
                        if ((away > 0 && rivalLat[i] + LAT_CLEAR > 1f) || (away < 0 && rivalLat[i] - LAT_CLEAR < 0f)) {
                            away = -away;
                        }
                        yourLat = clamp01(yourLat + away * 1.5f * dt);
                    }
                }
                // Tracked every frame, on any line: a value left stale while you were on another
                // line would read as "was astern" and snap you back a boat length on steering in.
                prevRel[i] = rel;
            }
            if (blocked && !wasBlocked) {
                shake.kick(dp(3f));
                if (now - blockedSaidAt > 8) {
                    blockedSaidAt = now;
                    callout = "BLOCKED - FIND WATER!";
                    calloutColor = BAD;
                    calloutUntil = now + 1.6;
                    say("Held up on the " + rivals[blockedBy].name + "'s stern - steer out for clear water!", BAD);
                    radioNow("COX", rivalLat[blockedBy] > yourLat
                            ? "We are on her stern - come up the far side, now!"
                            : "We are on her stern - take her down the near side, now!", BAD, 3.5);
                }
            }
            // Bridge piers.
            float bow = gapM * 0.5f;
            for (int b = 0; b < bridgeM.length; b++) {
                double before = course - rowed * (1f + lastGain) + bow;
                double after = course + bow;
                double face = bridgeM[b] - dp(30f) / ppm;   // the cutwater's nose
                if (before < face && after >= face) {
                    for (float p : PIER_L) {
                        if (Math.abs(yourLat - p) < PIER_HALF + 0.04f) {
                            course -= PIER_PENALTY_M;
                            float edge = PIER_HALF + 0.08f;
                            float out = yourLat < p ? p - edge : p + edge;
                            yourLat = clamp01(out < 0f || out > 1f ? (yourLat < p ? p + edge : p - edge) : out);
                            tapTarget = -1f;
                            shake.kick(dp(9f));
                            fx.burst(lastRowX(), yourY, 30, dp(180f), 0.8f, dp(3.5f), 0xEEDDF2FF, true);
                            callout = "HIT THE PIER!  -" + (int) PIER_PENALTY_M + " m";
                            calloutColor = BAD;
                            calloutUntil = now + 1.8;
                            say("Oh no - into the pier at " + bridgeName[b] + "! That costs about a second.", BAD);
                            radioNow("COX", "We clipped the stonework! Straighten her up and row on.", BAD, 3.0);
                            break;
                        }
                    }
                }
            }
        }

        // The rivals' lines.
        for (int i = 0; i < 3; i++) {
            if (!(now >= rivalStartAt[i])) {
                continue;
            }
            float want = wantedLine(i, rivalWater[i], rivals[i].home, rivalSpeed[i], 0.55f, gapM);
            rivalLat[i] += Math.max(-0.3f * dt, Math.min(0.3f * dt, want - rivalLat[i]));
            // Never through a boat: overlapping crews are pushed apart.
            for (int j = 0; j < 4; j++) {
                if (j == i) {
                    continue;
                }
                double dj;
                float lj;
                if (j == 3) {
                    if (phase == Phase.READY) {
                        continue;
                    }
                    dj = course;
                    lj = yourLat;
                } else {
                    if (!(now >= rivalStartAt[j])) {
                        continue;
                    }
                    dj = rivalWater[j];
                    lj = rivalLat[j];
                }
                if (Math.abs(dj - rivalWater[i]) < gapM && Math.abs(lj - rivalLat[i]) < LAT_CLEAR) {
                    float away = rivalLat[i] >= lj ? 1f : -1f;
                    if ((away > 0 && lj + LAT_CLEAR > 1f) || (away < 0 && lj - LAT_CLEAR < 0f)) {
                        away = -away;
                    }
                    rivalLat[i] += away * 1.2f * dt;
                }
            }
            rivalLat[i] = clamp01(rivalLat[i]);
        }

        if (phase != Phase.RACING) {
            return;
        }
        // Overtakes on the water, which are not the same as places on the clock.
        for (int i = 0; i < 3; i++) {
            if (!(now >= rivalStartAt[i]) || rivalWater[i] >= raceMeters || course >= raceMeters) {
                continue;
            }
            double rel = rivalWater[i] - course;
            if (waterAhead[i] && rel < -0.5) {
                waterAhead[i] = false;
                fx.burst(lastRowX(), yourY, 26, dp(160f), 0.9f, dp(3f), 0xFFF5C518, true);
                // The pass pays only once per crew per 20 s, so rocking past the same stern
                // cannot be farmed into a permanent 8%.
                if (now - boostedAt[i] > 20) {
                    boostedAt[i] = now;
                    boostChain = now < boostUntil ? boostChain + 1 : 1;
                    boostUntil = now + BOOST_SECONDS;
                    boostFrom = rivals[i].name;
                    shake.kick(dp(2.5f));
                    callout = boostChain > 1 ? "CHAIN x" + boostChain + " - IN THE PUDDLES!"
                            : "THROUGH THE " + rivals[i].name + " - PUDDLES!";
                    calloutColor = 0xFFF5C518;
                    calloutUntil = now + 1.8;
                    say("You row straight through the " + rivals[i].name + " and drop into its puddles"
                            + (boostChain > 1 ? " - that is " + boostChain + " crews in one run!" : "."), ACCENT);
                    radioNow("COX", boostChain > 1 ? "Chain of " + boostChain + "! Wash is carrying us - keep it long!"
                            : "In her puddles now - ten hard ones while the water is running!", 0xFFF5C518, 4.0);
                } else {
                    callout = "YOU ROW THROUGH THE " + rivals[i].name + "!";
                    calloutColor = ACCENT;
                    calloutUntil = now + 1.8;
                    say("Back through the " + rivals[i].name + " - no fresh water off that one yet.", ACCENT);
                }
            } else if (!waterAhead[i] && rel > 0.5) {
                waterAhead[i] = true;
                callout = "THE " + rivals[i].name + " COMES THROUGH";
                calloutColor = BAD;
                calloutUntil = now + 1.8;
                say("The " + rivals[i].name + " comes past you on the water.", rivals[i].color);
            }
        }
        commentary();
    }

    private float lastRowX() {
        return getWidth() * 0.40f;
    }

    /** Course features, splits and a running word when things go quiet. */
    private void commentary() {
        double t = raceTime();
        // The cox calls the bend three times: the warning, the line you are actually holding
        // halfway through it, and the wind-up out of the exit.
        for (int b = 0; b < bendStart.length; b++) {
            boolean near = bendInside[b] == 1;
            if (!bendCalled[b] && course > bendStart[b] - 40) {
                bendCalled[b] = true;
                say("Bend ahead - the inside is the " + (near ? "near" : "far")
                        + " bank. Take the inside line and it's shorter water.", WARN);
                radio("COX", "Bend in forty. Bring her over to the " + (near ? "near" : "far")
                        + " bank and hold it.", WARN, 4.5);
            }
            float f = bendFraction(b, course);
            if (!bendLineCalled[b] && f > 0.35f) {
                bendLineCalled[b] = true;
                float ideal = near ? 0.94f : 0.06f;
                float off = Math.abs(yourLat - ideal);
                radio("COX", off < 0.22f ? "That is the line - sitting right on the inside. Hold her there."
                        : off < 0.5f ? "We are drifting wide. Two firm on the " + (near ? "near" : "far")
                        + " side and straighten."
                        : "We are right out in the long water! Get her over, we are losing metres.",
                        off < 0.22f ? ACCENT : off < 0.5f ? WARN : BAD, 4.5);
            }
            if (!bendOutCalled[b] && course > bendEnd[b]) {
                bendOutCalled[b] = true;
                radio("COX", "Out of the bend, straight water ahead - wind it up now, ten strokes.",
                        ACCENT, 4.0);
            }
        }
        for (int b = 0; b < bridgeM.length; b++) {
            if (!bridgeCalled[b] && course > bridgeM[b] - 45) {
                bridgeCalled[b] = true;
                say("Coming up to " + bridgeName[b] + " - two piers in the river, go through an arch.", TEXT);
                radio("COX", bridgeName[b] + " coming: pick an arch and do not touch the stone.", TEXT, 4.0);
            }
        }
        int splitsDone = (int) (course / splitEveryM);
        if (splitsDone > splitsCalled && course < raceMeters) {
            splitsCalled = splitsDone;
            int metres = splitsDone * splitEveryM;
            // Index k must stay the (k+1)th mark, so a frame that crossed two fills both.
            while (mySplitCount < splitsDone && mySplitCount < MAX_SPLITS) {
                mySplit[mySplitCount++] = (float) t;
            }
            String s = metres + " m gone in " + clock(t);
            float against = compareAt(splitsDone - 1, metres);
            if (against > 0f) {
                double diff = against - t;
                s += String.format(java.util.Locale.US, " - %.1f s %s the record's split", Math.abs(diff),
                        diff >= 0 ? "up on" : "down on");
                radio("BANK PARTY", String.format(java.util.Locale.US, "%d gone, %.1f s %s the record here.",
                        metres, Math.abs(diff), diff >= 0 ? "up on" : "down on"),
                        diff >= 0 ? ACCENT : BAD, 4.0);
            }
            say(s + ".", TEXT);
        }
        double last = raceMeters >= 1000 ? 250 : 100;
        if (!finalCalled && course > raceMeters - last) {
            finalCalled = true;
            say("Final " + (int) last + " metres - everything you have left!", 0xFFF5C518);
            radioNow("COX", "Last " + (int) last + "! Everything you have, do not let them back.",
                    0xFFF5C518, 5.0);
        }
        if (sessionSeconds - lastSayAt > 15 && tickEnd() < getWidth() * 0.5f) {
            chatter(t);
        }
    }

    private void chatter(double t) {
        chatterIndex = (chatterIndex + 1) % 3;
        if (chatterIndex == 0) {
            // The closest crew on the clock.
            int best = 0;
            double bestGap = Double.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                double g = Math.abs(course - rivals[i].distanceAt(t, raceMeters));
                if (g < bestGap) {
                    bestGap = g;
                    best = i;
                }
            }
            double sec = timeGap(best, t);
            say(String.format(java.util.Locale.US, "On the clock you are %.1f s %s the %s.", Math.abs(sec),
                    sec >= 0 ? "ahead of" : "behind", rivals[best].name), rivals[best].color);
        } else if (chatterIndex == 1 && status != null) {
            int watts = status.watts;
            double typical = profile.typicalWatts();
            say("Rating " + status.strokeRate + ", " + watts + " W - "
                    + (watts > typical * 1.08 ? "pressing on hard." : watts < typical * 0.85
                    ? "that's easing off - the crews won't." : "right on your usual power."), DIM);
        } else {
            double next = Double.MAX_VALUE;
            String what = null;
            for (int b = 0; b < bendStart.length; b++) {
                if (bendStart[b] > course && bendStart[b] - course < next) {
                    next = bendStart[b] - course;
                    what = "the next bend";
                }
            }
            for (int b = 0; b < bridgeM.length; b++) {
                if (bridgeM[b] > course && bridgeM[b] - course < next) {
                    next = bridgeM[b] - course;
                    what = bridgeName[b];
                }
            }
            if (what != null) {
                say(Math.round(next) + " m to " + what + ", " + Math.round(raceMeters - course) + " m to the finish.", DIM);
            } else {
                say(Math.round(raceMeters - course) + " m to the finish line.", DIM);
            }
        }
    }

    /** Seconds you lead crew {@code i} by on the clock (negative: behind). */
    private double timeGap(int i, double t) {
        return (course - rivals[i].distanceAt(t, raceMeters)) / (raceMeters / rivals[i].finishTime);
    }

    /** Seconds up on an even-paced course record at your current distance (negative: down). */
    private double crSecondsAhead(double t) {
        if (boardCount == 0) {
            return 0;
        }
        return course * boardTimes[0] / raceMeters - t;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float waterTop = h * 0.26f;
        float waterBottom = h * 0.74f;
        float laneH = (waterBottom - waterTop) / 4f;
        lastWaterTop = waterTop;
        lastWaterBottom = waterBottom;
        lastLaneH = laneH;
        float ppm = w / 80f;
        float gapM = dp(150f) / ppm;
        updateRace(dt, gapM, ppm);

        double t = raceTime();
        double you = course;
        if (phase == Phase.RACING && you >= raceMeters) {
            finishRace(t);
        }

        if (phase == Phase.RACING) {
            int second = (int) t;
            if (second != lastGapSecond) {
                lastGapSecond = second;
                for (int i = 0; i < 3; i++) {
                    gaps[i][gapHead] = (float) (you - rivals[i].distanceAt(t, raceMeters));
                }
                gapHead = (gapHead + 1) % GAP_SAMPLES;
                gapCount = Math.min(GAP_SAMPLES, gapCount + 1);
            }
        }
        // Sky above the bank, so the header reads as a place rather than a dashboard.
        float bankTop = waterTop - dp(46f);
        if (skyShader == null || skyHeight != bankTop) {
            skyHeight = bankTop;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, bankTop, 0xFF0B1322, 0xFF2A4E74,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        panel.setShader(skyShader);
        c.drawRect(0, 0, w, bankTop, panel);
        panel.setShader(null);
        Fx.glow(c, w * 0.86f, bankTop - dp(10f), dp(120f), 0x44FFC98A);
        panel.setColor(0xFF1C3350);
        float hillScroll = (float) ((you * (w / 80f) * 0.08) % (w * 0.5f));
        for (int k = -1; k < 4; k++) {
            float hx = k * w * 0.5f - hillScroll;
            c.drawOval(hx - w * 0.3f, bankTop - dp(34f), hx + w * 0.3f, bankTop + dp(40f), panel);
        }
        float speed = boat.value();
        shake.step(dt);
        c.save();
        c.translate(shake.dx, shake.dy);
        river.advance(phase == Phase.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        fx.step(dt, dp(260f));

        float yourX = w * 0.40f;
        yourY = latY(yourLat);
        // The course, all of it scrolling with your metres.
        scenery.drawBank(c, w, waterTop - dp(46f), waterTop, you, ppm, sessionSeconds, cheer);
        // A head race has no lanes: buoys mark the edges of the course only.
        scenery.drawBuoys(c, w, waterTop + dp(6f), you, ppm, sessionSeconds, 10f);
        scenery.drawBuoys(c, w, waterBottom - dp(6f), you, ppm, sessionSeconds, 10f);
        scenery.drawWaterLife(c, w, waterTop, waterBottom, you, ppm, sessionSeconds);
        drawBends(c, w, waterTop, waterBottom, yourX, you, ppm);
        for (int mark = 250; mark < raceMeters; mark += 250) {
            float mx = yourX + (float) (mark - you) * ppm;
            if (mx > -dp(40f) && mx < w + dp(40f)) {
                scenery.drawBoard(c, mx, waterTop, (raceMeters - mark) + " m", boardText);
            }
        }
        float finishX = yourX + dp(62f) + (float) (raceMeters - you) * ppm;
        if (finishX < w + dp(40f)) {
            scenery.drawFinishLine(c, finishX, waterTop, waterBottom, sessionSeconds);
        }
        drawPiers(c, w, waterTop, waterBottom, yourX, you, ppm);
        // Live placing on the clock: rivals who had covered more water at your elapsed time.
        int ahead = 0;
        for (int i = 0; i < 3; i++) {
            Rival r = rivals[i];
            if (r.distanceAt(t, raceMeters) > you && phase != Phase.READY) {
                ahead++;
            }
            boolean started = sessionSeconds >= rivalStartAt[i];
            double d = rivalWater[i];
            float target = Math.max(dp(36f), Math.min(w - dp(36f), yourX + (float) (d - you) * ppm));
            if (rivalX[i] == 0f) {
                rivalX[i] = target;
            }
            rivalX[i] += (target - rivalX[i]) * Math.min(1f, 6f * dt);
            float ly = latY(rivalLat[i]);
            // Each crew rows to its own rhythm, a little off yours and off each other's. Sharing
            // one phase put all four boats at the catch on the same frame, which reads as four
            // identical bars through the fleet rather than as a race.
            river.setStrokePhase(started ? (float) (0.5 + 0.5 * Math.sin(sessionSeconds * (2.4 + i * 0.18)
                    + i * 1.9)) : 0f);
            river.drawBoat(c, rivalX[i], ly, dp(150f), r.color, started ? rivalSpeed[i] : 0f, true);
            if (!started) {
                double wait = rivalStartAt[i] - sessionSeconds;
                label(c, r.name + (Double.isInfinite(wait) ? "  AT THE START"
                        : String.format(java.util.Locale.US, "  OFF IN %.0f s", Math.ceil(wait))),
                        rivalX[i], ly - dp(40f), 11f, DIM, Paint.Align.CENTER);
            } else {
                label(c, r.name + String.format(java.util.Locale.US, "  %+.0f m", d - you), rivalX[i],
                        ly - dp(40f), 11f, d > you ? r.color : DIM, Paint.Align.CENTER);
                if (phase == Phase.RACING) {
                    double sec = timeGap(i, t);
                    label(c, String.format(java.util.Locale.US, "on time: you %s%.1f s", sec >= 0 ? "+" : "-",
                            Math.abs(sec)), rivalX[i], ly - dp(27f), 9f, sec >= 0 ? ACCENT : BAD, Paint.Align.CENTER);
                }
            }
        }
        if (phase == Phase.RACING) {
            if (lastAhead >= 0 && ahead != lastAhead && sessionSeconds > calloutUntil - 0.6) {
                boolean gained = ahead < lastAhead;
                callout = gained ? (ahead == 0 ? "YOU LEAD ON THE CLOCK!" : "UP A PLACE ON TIME")
                        : "DOWN A PLACE ON TIME";
                calloutColor = gained ? ACCENT : BAD;
                calloutUntil = sessionSeconds + 1.8;
                if (gained) {
                    fx.burst(yourX, yourY, 26, dp(160f), 0.9f, dp(3f), 0xFFF5C518, true);
                }
            }
            if (lastAhead >= 0 && ahead != lastAhead) {
                say(ahead == 0 ? "On the clock, you lead the race!" : "On the clock you're now "
                        + ordinal(ahead + 1).toLowerCase(java.util.Locale.US) + ".", ahead < lastAhead ? ACCENT : BAD);
            }
            lastAhead = ahead;
        }
        // The crowd follows the race: loud when you lead, quiet when you trail.
        float cheerTarget = phase == Phase.DONE ? (placing == 1 ? 1f : 0.3f)
                : phase == Phase.RACING ? (ahead == 0 ? 1f : ahead == 1 ? 0.5f : 0.15f) : 0f;
        cheer += (cheerTarget - cheer) * Math.min(1f, 2f * dt);
        if (phase != Phase.READY && ahead == 0) {
            Fx.glow(c, yourX, yourY, dp(90f), 0x44F5C518);
        }
        drawSteeringCues(c, yourX, yourY, you, gapM);
        if (boostGain > 0f) {
            // Washed-down water carrying the boat: a fixed-radius glow, so it stays in Fx's cache.
            Fx.glow(c, yourX, yourY, dp(96f), 0x55F5C518);
            if (Math.random() < 0.55) {
                fx.spawn(yourX - dp(70f) - (float) Math.random() * dp(40f),
                        yourY + (float) (Math.random() - 0.5) * dp(26f),
                        -dp(120f) - (float) Math.random() * dp(80f), (float) (Math.random() - 0.5) * dp(30f),
                        0.5f, dp(3f), 0xCCF5C518, false);
            }
        }
        river.bowSpray(yourX + dp(54f), yourY, speed, dt);
        // Your own oars follow your own stroke - this was inheriting whatever phase the last rival
        // was drawn with, so the one boat that should track the rower did not.
        river.setStrokePhase(strokePhase());
        river.drawBoat(c, yourX, yourY, dp(156f), ACCENT, speed, false);
        river.drawSpray(c);
        bold(c, "YOU", yourX, yourY + dp(40f), 11f, ACCENT, Paint.Align.CENTER);
        if (phase == Phase.RACING && Math.abs(lastGain) > 0.004f) {
            label(c, String.format(java.util.Locale.US, "%s %+.1f%%", lastGain > 0 ? "INSIDE" : "OUTSIDE",
                    lastGain * 100f), yourX, yourY - dp(36f), 11f, lastGain > 0 ? ACCENT : BAD, Paint.Align.CENTER);
        }
        drawBridgeDecks(c, w, bankTop, waterBottom, yourX, you, ppm);
        if (sessionSeconds < confettiUntil && Math.random() < 0.7) {
            fx.spawn((float) Math.random() * w, waterTop - dp(40f), (float) (Math.random() - 0.5) * dp(80f),
                    dp(20f), 2.2f, dp(3f), CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
        }
        fx.draw(c);
        c.restore();
        if (sessionSeconds < calloutUntil) {
            double left = calloutUntil - sessionSeconds;
            float pop = (float) Math.min(1.0, (1.8 - Math.min(1.8, left)) * 8 + 0.6);
            bold(c, callout, w / 2f, waterTop + (waterBottom - waterTop) * 0.5f, 30f * Math.min(1f, pop),
                    calloutColor, Paint.Align.CENTER);
        }
        label(c, hasSteering() ? "STEERING: HANDLE - tilt to change line"
                        : tapTarget >= 0f ? "STEERING: YOUR LINE (tap)" : "STEERING: AUTO COX - tap the water to pick a line",
                dp(16f), waterBottom - dp(14f), 9f, FAINT, Paint.Align.LEFT);

        String big;
        int col;
        if (phase == Phase.READY) {
            big = eventActive() ? stageTitle().toUpperCase(java.util.Locale.US) : raceMeters + " m";
            col = eventActive() ? 0xFFF5C518 : DIM;
        } else if (phase == Phase.DONE) {
            big = ordinal(placing);
            col = placing == 1 ? ACCENT : placing == 4 ? BAD : WARN;
        } else {
            big = ordinal(ahead + 1);
            col = ahead == 0 ? ACCENT : ahead == 3 ? BAD : WARN;
        }
        bold(c, big, w / 2f, h * 0.15f, 44f, col, Paint.Align.CENTER);
        String sub;
        if (phase == Phase.READY) {
            sub = sessionSeconds < callAt
                    ? String.format(java.util.Locale.US, "marshalling - your crew is called in %.0f s",
                    Math.ceil(callAt - sessionSeconds))
                    : "your crew is called - take a stroke to start the clock";
        } else if (phase == Phase.DONE) {
            sub = clock(finishTime) + "  ·  tap to race again";
        } else {
            sub = clock(t) + "  ·  " + Math.round(you) + " of " + raceMeters + " m  ·  place on the clock";
        }
        label(c, sub, w / 2f, h * 0.15f + dp(20f), 10f, FAINT, Paint.Align.CENTER);
        bold(c, pace(speed), dp(16f), h * 0.15f, 22f, TEXT, Paint.Align.LEFT);
        label(c, "PACE /500", dp(16f), h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, status == null ? "0" : status.strokeRate + " spm", w - dp(16f), h * 0.15f, 22f, TEXT,
                Paint.Align.RIGHT);
        label(c, status == null ? "" : status.watts + " W", w - dp(16f), h * 0.15f + dp(16f), 9f,
                FAINT, Paint.Align.RIGHT);
        drawRecordLine(c, t);
        drawDayBanner(c, w);
        float plateRight = dp(16f) + Math.min(w * 0.40f, dp(400f));
        if (phase == Phase.RACING) {
            // The plates belong to the marshalling area: mid-race a tap is for the line you row.
            coursePillR = coursePillL;
            eventPillR = eventPillL;
            drawRadio(c, dp(16f), dp(46f), plateRight, dp(116f));
            drawBoostMeter(c, w, waterTop);
        } else {
            drawPlates(c, dp(16f), dp(30f), plateRight);
            // Beside the plates, clear of the centre placing and the top-right drive panel.
            float radioLeft = plateRight + dp(10f);
            drawRadio(c, radioLeft, dp(46f), Math.min(radioLeft + dp(380f), w * 0.5f - dp(30f)), dp(116f));
        }

        drawRibbon(c, w, waterTop - dp(56f), t, you);
        // Anchored to the top-right corner: floating in the middle of the sky it read as a
        // sticker dropped on the scene rather than part of the instrument.
        drawStrokeShape(c, w - dp(208f), dp(6f), w - dp(16f), h * 0.15f - dp(30f));
        float tickTop = h - dp(28f);
        float panelTop = waterBottom + dp(10f);
        float panelBottom = tickTop - dp(6f);
        float panelMid = w * 0.5f;
        if (phase == Phase.RACING) {
            drawGapGraph(c, dp(10f), panelTop, panelMid - dp(6f), panelBottom);
            drawStrokeBars(c, panelMid + dp(6f), panelTop, w - dp(10f), panelBottom);
        } else if (phase == Phase.DONE) {
            // The debrief: where the record was won and lost, beside the board it was won on.
            drawSplits(c, dp(10f), panelTop, panelMid + dp(120f), panelBottom);
            drawBoard(c, panelMid + dp(132f), panelTop, w - dp(10f), panelBottom);
        } else {
            drawBoard(c, dp(10f), panelTop, panelMid - dp(6f), panelBottom);
            drawStrokeBars(c, panelMid + dp(6f), panelTop, w - dp(10f), panelBottom);
        }
        drawTicker(c, w, tickTop, h, dt);
    }

    private void finishRace(double t) {
        phase = Phase.DONE;
        finishTime = t;
        placing = 1;
        for (Rival r : rivals) {
            if (r.finishTime < finishTime) {
                placing++;
            }
        }
        int marks = Math.max(1, raceMeters / splitEveryM);
        while (mySplitCount < marks && mySplitCount < MAX_SPLITS) {
            mySplit[mySplitCount++] = (float) finishTime;
        }
        bests.recordLowest("time." + raceMeters, (float) finishTime);
        boardRank = addToBoard((float) finishTime);
        if (boardRank == 0) {
            // The record now owns these splits, so the next race is measured against water that
            // was actually rowed rather than an even-pace guess.
            saveSplits();
        }
        boostUntil = 0;
        boostGain = 0f;
        radioQCount = 0;
        confettiUntil = sessionSeconds + (placing == 1 || boardRank == 0 ? 4.0 : 1.5);
        if (boardRank == 0) {
            callout = "NEW COURSE RECORD!";
            calloutColor = 0xFFF5C518;
        } else {
            callout = placing == 1 ? "YOU WIN!" : ordinal(placing) + " PLACE";
            calloutColor = placing == 1 ? 0xFFF5C518 : WARN;
        }
        calloutUntil = sessionSeconds + 3.0;
        say("You cross the line in " + clock(finishTime) + " - " + (placing == 1 ? "the fastest time on the day!"
                : ordinal(placing).toLowerCase(java.util.Locale.US) + " on the clock."), placing == 1 ? ACCENT : WARN);
        if (boardRank == 0) {
            say("That's a new record at " + COURSES[courseIndex].name + " over " + raceMeters + " m!",
                    0xFFF5C518);
        } else if (boardRank > 0) {
            say("Onto the " + COURSES[courseIndex].name + " board at number " + (boardRank + 1) + ".",
                    0xFFF5C518);
        }
        // Built here, not in the draw: cmpSplit and cmpRecord were captured at the start and
        // addToBoard does not touch them, so the debrief is fixed from this moment on.
        buildSplitRows();
        settleEvent();
    }

    /** Heats send two crews through, the repechage one, and the final hands out the medal. */
    private void settleEvent() {
        if (!eventActive()) {
            radio("COX", boardRank == 0 ? "Course record. That is the best water we have ever rowed here."
                    : "Well rowed. Look at the splits - that is where the race was won.", ACCENT, 5.0);
            refreshPlates();
            return;
        }
        if (eventStage == EV_HEAT) {
            eventHeatTime = (float) finishTime;
            eventHeatPlace = placing;
            if (placing <= 2) {
                eventStage = EV_FINAL;
                callout = "THROUGH TO THE FINAL";
                calloutColor = 0xFFF5C518;
                calloutUntil = sessionSeconds + 3.5;
                say(ordinal(placing) + " in the heat - through to tomorrow's final. Come back and race"
                        + " it when you are ready.", 0xFFF5C518);
                radio("MARSHAL", "Qualified. The final is tomorrow, same course - go and recover.",
                        0xFFF5C518, 5.5);
            } else {
                eventStage = EV_REPECHAGE;
                callout = "REPECHAGE THIS AFTERNOON";
                calloutColor = WARN;
                calloutUntil = sessionSeconds + 3.5;
                say(ordinal(placing) + " in the heat - you get a repechage this afternoon. Win it and"
                        + " you are in the final.", WARN);
                radio("MARSHAL", "Third or worse - repechage this afternoon. One place, that is all.",
                        WARN, 5.5);
            }
        } else if (eventStage == EV_REPECHAGE) {
            if (placing == 1) {
                eventStage = EV_FINAL;
                eventHeatTime = (float) finishTime;
                callout = "WON THE REPECHAGE - FINAL TOMORROW";
                calloutColor = 0xFFF5C518;
                calloutUntil = sessionSeconds + 3.5;
                say("You win the repechage and take the last place in tomorrow's final.", 0xFFF5C518);
                radio("MARSHAL", "Won it. You are in the final - the hard way, but you are in.",
                        0xFFF5C518, 5.5);
            } else {
                eventStage = EV_OUT;
                callout = "OUT OF THE EVENT";
                calloutColor = BAD;
                calloutUntil = sessionSeconds + 3.5;
                say(ordinal(placing) + " in the repechage - the event is over. Tap the event plate to"
                        + " enter the next one.", BAD);
                radio("MARSHAL", "That is the event. Enter the next one when you are ready.", BAD, 5.0);
            }
        } else if (eventStage == EV_FINAL) {
            if (placing == 1) {
                eventWins++;
                bests.recordHighest("headrace.event.wins", eventWins);
                confettiUntil = sessionSeconds + 6.0;
                callout = "YOU WIN THE FINAL!";
                calloutColor = 0xFFF5C518;
                calloutUntil = sessionSeconds + 4.0;
                say("You win the final - that is the event, and win number " + eventWins + ".", 0xFFF5C518);
                radio("MARSHAL", "Champion. Straight to the podium - well rowed.", 0xFFF5C518, 5.5);
            } else {
                callout = ordinal(placing) + " IN THE FINAL";
                calloutColor = placing == 2 ? WARN : BAD;
                calloutUntil = sessionSeconds + 3.5;
                say(ordinal(placing) + " in the final. Tap the event plate to enter the next event.",
                        placing == 2 ? WARN : BAD);
                radio("MARSHAL", "A final is a final. Next event opens when you are ready.", WARN, 5.0);
            }
            eventStage = EV_MEDAL;
        }
        saveEvent();
        refreshPlates();
    }

    /* ---------- the course ---------- */

    /** The inside of each bend as a spit of sand and grass, a racing line, and a sign before it. */
    private void drawBends(Canvas c, float w, float waterTop, float waterBottom, float yourX, double you, float ppm) {
        double viewLo = you - yourX / ppm - 5;
        double viewHi = you + (w - yourX) / ppm + 5;
        for (int b = 0; b < bendStart.length; b++) {
            float signX = yourX + (float) (bendStart[b] - 20 - you) * ppm;
            if (signX > -dp(60f) && signX < w + dp(60f)) {
                scenery.drawBoard(c, signX, waterTop, bendInside[b] == 0 ? "BEND: FAR SIDE" : "BEND: NEAR SIDE",
                        boardText);
            }
            if (bendEnd[b] < viewLo || bendStart[b] > viewHi) {
                continue;
            }
            boolean top = bendInside[b] == 0;
            float edge = top ? waterTop : waterBottom;
            float dir = top ? 1f : -1f;
            float step = dp(12f);
            shape.rewind();
            float x0 = Math.max(-step, yourX + (float) (bendStart[b] - you) * ppm);
            float x1 = Math.min(w + step, yourX + (float) (bendEnd[b] - you) * ppm);
            shape.moveTo(x0, edge);
            for (float x = x0; x <= x1 + 0.5f; x += step) {
                float xx = Math.min(x, x1);
                double m = you + (xx - yourX) / ppm;
                float f = bendFraction(b, m);
                float bulge = f < 0 ? 0f : (float) Math.sin(Math.PI * f) * dp(20f);
                shape.lineTo(xx, edge + dir * bulge);
            }
            shape.lineTo(x1, edge);
            shape.close();
            panel.setStyle(Paint.Style.FILL);
            panel.setColor(0xFFB89B63);
            c.drawPath(shape, panel);
            c.save();
            c.translate(0, -dir * dp(4f));
            panel.setColor(0xFF3F7A45);
            c.drawPath(shape, panel);
            c.restore();
            // The racing line: gold dots hugging the inside.
            float lineLat = top ? 0.06f : 0.94f;
            float ly = latY(lineLat);
            panel.setColor(0x66F5C518);
            float first = (float) Math.ceil(Math.max(bendStart[b], viewLo) / 4.0) * 4f;
            for (float m = first; m <= bendEnd[b] && m <= viewHi; m += 4f) {
                float f = bendFraction(b, m);
                float x = yourX + (m - (float) you) * ppm;
                c.drawCircle(x, ly + dir * (float) Math.sin(Math.PI * f) * dp(8f), dp(2.6f), panel);
            }
        }
    }

    /** Pier stones and the bridge's shadow on the water: drawn under the boats. */
    private void drawPiers(Canvas c, float w, float waterTop, float waterBottom, float yourX, double you, float ppm) {
        for (int b = 0; b < bridgeM.length; b++) {
            float bx = yourX + (float) (bridgeM[b] - you) * ppm;
            if (bx < -dp(80f) || bx > w + dp(80f)) {
                continue;
            }
            panel.setStyle(Paint.Style.FILL);
            panel.setColor(0x44000000);
            c.drawRect(bx - dp(34f), waterTop, bx + dp(34f), waterBottom, panel);
            double ahead = bridgeM[b] - you;
            boolean danger = phase == Phase.RACING && ahead > 0 && ahead < 40;
            for (float p : PIER_L) {
                float py = latY(p);
                float half = lastLaneH * 3f * PIER_HALF;
                boolean onLine = danger && Math.abs(yourLat - p) < PIER_HALF + 0.04f;
                // Foam round the cutwater.
                panel.setColor(0x55DCEBF7);
                c.drawOval(bx - dp(46f), py - half - dp(3f), bx + dp(34f), py + half + dp(3f), panel);
                shape.rewind();
                shape.moveTo(bx + dp(30f), py - half);
                shape.lineTo(bx - dp(30f), py - half);
                shape.lineTo(bx - dp(44f), py);
                shape.lineTo(bx - dp(30f), py + half);
                shape.lineTo(bx + dp(30f), py + half);
                shape.quadTo(bx + dp(40f), py, bx + dp(30f), py - half);
                shape.close();
                panel.setColor(0xFF6E655A);
                c.drawPath(shape, panel);
                if (onLine) {
                    float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 12);
                    panel.setStyle(Paint.Style.STROKE);
                    panel.setStrokeWidth(dp(3f));
                    panel.setColor(((int) (140 + 115 * pulse) << 24) | (BAD & 0x00FFFFFF));
                    c.drawPath(shape, panel);
                    panel.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    /** The bridge decks, drawn over the boats so crews pass under them. */
    private void drawBridgeDecks(Canvas c, float w, float bankTop, float waterBottom, float yourX, double you, float ppm) {
        for (int b = 0; b < bridgeM.length; b++) {
            float bx = yourX + (float) (bridgeM[b] - you) * ppm;
            if (bx < -dp(80f) || bx > w + dp(80f)) {
                continue;
            }
            float half = dp(19f);
            panel.setStyle(Paint.Style.FILL);
            panel.setColor(0xF28A7E6E);
            c.drawRect(bx - half, bankTop - dp(4f), bx + half, waterBottom + dp(2f), panel);
            panel.setColor(0xFF4A423A);
            c.drawRect(bx - half, bankTop - dp(4f), bx - half + dp(4f), waterBottom + dp(2f), panel);
            c.drawRect(bx + half - dp(4f), bankTop - dp(4f), bx + half, waterBottom + dp(2f), panel);
            for (float y = bankTop + dp(20f); y < waterBottom; y += dp(70f)) {
                panel.setColor(0xFF2E2A26);
                c.drawRect(bx - half + dp(1f), y - dp(4f), bx - half + dp(5f), y + dp(4f), panel);
                panel.setColor(0xFFFFE3A0);
                c.drawCircle(bx - half + dp(3f), y - dp(6f), dp(2.5f), panel);
            }
            c.save();
            c.rotate(-90f, bx, (bankTop + waterBottom) / 2f);
            bold(c, bridgeName[b], bx, (bankTop + waterBottom) / 2f + dp(5f), 13f, 0xFFF2E6D0, Paint.Align.CENTER);
            c.restore();
        }
    }

    /** A hint toward clear water when a slower crew sits on your line, and your tapped line. */
    private void drawSteeringCues(Canvas c, float yourX, float yourY, double you, float gapM) {
        if (tapTarget >= 0f && sessionSeconds < tapUntil) {
            float ty = latY(tapTarget);
            panel.setStyle(Paint.Style.STROKE);
            panel.setStrokeWidth(dp(2f));
            panel.setColor(0xAA35D0BA);
            c.drawCircle(yourX + dp(120f), ty, dp(10f), panel);
            c.drawLine(yourX + dp(80f), yourY, yourX + dp(110f), ty, panel);
            panel.setStyle(Paint.Style.FILL);
        }
        if (phase != Phase.RACING) {
            return;
        }
        int threat = -1;
        for (int i = 0; i < 3; i++) {
            if (!(sessionSeconds >= rivalStartAt[i])) {
                continue;
            }
            double rel = rivalWater[i] - you;
            if (rel > 0 && rel < gapM * 3.5 && Math.abs(rivalLat[i] - yourLat) < LAT_CLEAR + 0.05f) {
                threat = i;
            }
        }
        if (threat < 0) {
            return;
        }
        float rl = rivalLat[threat];
        float dir = rl > 0.5f ? -1f : 1f;
        if (rl - yourLat > 0.02f) {
            dir = -1f;
        } else if (yourLat - rl > 0.02f) {
            dir = 1f;
        }
        if ((dir < 0 && yourLat < 0.12f) || (dir > 0 && yourLat > 0.88f)) {
            dir = -dir;
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 9);
        float ax = yourX + dp(96f);
        float ay = yourY + dir * dp(22f + 6f * pulse);
        shape.rewind();
        shape.moveTo(ax, ay + dir * dp(12f));
        shape.lineTo(ax - dp(10f), ay);
        shape.lineTo(ax + dp(10f), ay);
        shape.close();
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(blocked ? BAD : 0xFFF5C518);
        c.drawPath(shape, panel);
        label(c, blocked ? "BLOCKED" : "FIND WATER", ax + dp(16f), ay + dp(4f), 10f, blocked ? BAD : 0xFFF5C518,
                Paint.Align.LEFT);
    }

    /* ---------- course-record board ---------- */

    private String boardKey() {
        return keyBase() + "board." + raceMeters;
    }

    private void loadBoard() {
        boardCount = 0;
        String raw = bests.getString(boardKey());
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String entry : raw.split(";")) {
            int at = entry.indexOf('@');
            if (at <= 0 || boardCount >= BOARD_SIZE) {
                continue;
            }
            try {
                float secs = Float.parseFloat(entry.substring(0, at));
                if (!(secs > 0f) || Float.isInfinite(secs)) {
                    continue;
                }
                boardTimes[boardCount] = secs;
                boardDates[boardCount] = entry.substring(at + 1);
                boardCount++;
            } catch (NumberFormatException ignored) {
                // A corrupt entry is dropped rather than losing the whole board.
            }
        }
    }

    /** Inserts a finish, keeps the best five, saves; returns its rank (0 = record) or -1 if off the board. */
    private int addToBoard(float seconds) {
        int rank = 0;
        while (rank < boardCount && boardTimes[rank] <= seconds) {
            rank++;
        }
        if (rank >= BOARD_SIZE) {
            return -1;
        }
        for (int k = Math.min(boardCount, BOARD_SIZE - 1); k > rank; k--) {
            boardTimes[k] = boardTimes[k - 1];
            boardDates[k] = boardDates[k - 1];
        }
        boardTimes[rank] = seconds;
        boardDates[rank] = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
                .format(new java.util.Date());
        boardCount = Math.min(BOARD_SIZE, boardCount + 1);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < boardCount; k++) {
            if (k > 0) {
                sb.append(';');
            }
            sb.append(String.format(java.util.Locale.US, "%.1f", boardTimes[k])).append('@').append(boardDates[k]);
        }
        bests.putString(boardKey(), sb.toString());
        return rank;
    }

    /** Top five on this course; the finish you just rowed is lit. */
    private void drawBoard(Canvas c, float l, float t, float r, float b) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(10f), dp(10f), panel);
        label(c, "COURSE RECORDS  ·  " + raceMeters + " m HEAD", l + dp(10f), t + dp(16f), 8.5f, 0xFFF5C518,
                Paint.Align.LEFT);
        float top = t + dp(24f);
        float row = Math.min(dp(24f), (b - dp(6f) - top) / BOARD_SIZE);
        if (boardCount == 0) {
            label(c, "Empty - your first finish is the course record.", l + dp(14f), top + row, 11f, DIM,
                    Paint.Align.LEFT);
            return;
        }
        for (int k = 0; k < boardCount; k++) {
            float y = top + row * k;
            boolean mine = phase == Phase.DONE && k == boardRank;
            if (mine) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 5);
                panel.setColor(((int) (40 + 50 * pulse) << 24) | 0x00F5C518);
                c.drawRoundRect(l + dp(6f), y + dp(1f), r - dp(6f), y + row - dp(1f), dp(5f), dp(5f), panel);
            }
            int colour = k == 0 ? 0xFFF5C518 : mine ? TEXT : DIM;
            bold(c, (k + 1) + ".", l + dp(16f), y + row * 0.72f, 12f, colour, Paint.Align.LEFT);
            bold(c, clock(boardTimes[k]), l + dp(44f), y + row * 0.72f, 13f, colour, Paint.Align.LEFT);
            label(c, pace(raceMeters / boardTimes[k]) + " /500", l + dp(120f), y + row * 0.72f, 10f, FAINT,
                    Paint.Align.LEFT);
            label(c, boardDates[k] + (mine ? "  ·  THIS RACE" : ""), r - dp(14f), y + row * 0.72f, 10f,
                    mine ? 0xFFF5C518 : FAINT, Paint.Align.RIGHT);
        }
    }

    /** The record in the top-left corner, and how you stand against its pace right now. */
    private void drawRecordLine(Canvas c, double t) {
        if (boardCount == 0) {
            label(c, "COURSE RECORD  -  set it today", dp(16f), dp(20f), 10f, 0xFFF5C518, Paint.Align.LEFT);
            return;
        }
        label(c, "COURSE RECORD  " + clock(boardTimes[0]), dp(16f), dp(20f), 10f, 0xFFF5C518, Paint.Align.LEFT);
        if (phase == Phase.RACING && t > 3) {
            double diff = crSecondsAhead(t);
            bold(c, String.format(java.util.Locale.US, "%s%.1f s %s", diff >= 0 ? "+" : "-", Math.abs(diff),
                    diff >= 0 ? "UNDER RECORD PACE" : "OVER RECORD PACE"), dp(16f), dp(38f), 12f,
                    diff >= 0 ? ACCENT : BAD, Paint.Align.LEFT);
        }
    }

    /* ---------- commentary ticker ---------- */

    private void say(String text, int color) {
        if (tickCount == TICK_MAX) {
            dropFirstTick();
        }
        int i = tickCount++;
        tickText[i] = text;
        tickColor[i] = color;
        tickW[i] = tickPaint.measureText(text);
        tickPlaced[i] = false;
        lastSayAt = sessionSeconds;
    }

    private void dropFirstTick() {
        for (int k = 1; k < tickCount; k++) {
            tickText[k - 1] = tickText[k];
            tickColor[k - 1] = tickColor[k];
            tickX[k - 1] = tickX[k];
            tickW[k - 1] = tickW[k];
            tickPlaced[k - 1] = tickPlaced[k];
        }
        tickCount--;
    }

    /** Where the queued commentary ends on screen. */
    private float tickEnd() {
        float end = 0f;
        for (int k = 0; k < tickCount; k++) {
            if (tickPlaced[k]) {
                end = Math.max(end, tickX[k] + tickW[k]);
            } else {
                end += dp(60f) + tickW[k];
            }
        }
        return end;
    }

    private void drawTicker(Canvas c, float w, float top, float bottom, float dt) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0A0F18);
        c.drawRect(0, top, w, bottom, panel);
        float tagR = dp(62f);
        panel.setColor(BAD);
        c.drawRoundRect(dp(8f), top + dp(5f), tagR - dp(6f), bottom - dp(5f), dp(4f), dp(4f), panel);
        bold(c, "LIVE", (dp(8f) + tagR - dp(6f)) / 2f, (top + bottom) / 2f + dp(4f), 10f, 0xFFFFFFFF,
                Paint.Align.CENTER);
        // Place anything new off the right edge, behind whatever is still scrolling.
        float prevEnd = w - dp(60f);
        for (int k = 0; k < tickCount; k++) {
            if (!tickPlaced[k]) {
                tickX[k] = Math.max(w, prevEnd + dp(60f));
                tickPlaced[k] = true;
            }
            prevEnd = tickX[k] + tickW[k];
        }
        float backlog = prevEnd - w;
        float speed = backlog > w ? dp(260f) : backlog > w * 0.4f ? dp(170f) : dp(115f);
        for (int k = 0; k < tickCount; k++) {
            tickX[k] -= speed * dt;
        }
        while (tickCount > 0 && tickX[0] + tickW[0] < tagR) {
            dropFirstTick();
        }
        c.save();
        c.clipRect(tagR, top, w, bottom);
        float base = (top + bottom) / 2f + dp(4.5f);
        for (int k = 0; k < tickCount; k++) {
            panel.setColor(0xFF5D6B80);
            c.drawCircle(tickX[k] - dp(30f), (top + bottom) / 2f, dp(3f), panel);
            tickPaint.setColor(tickColor[k]);
            c.drawText(tickText[k], tickX[k], base, tickPaint);
        }
        c.restore();
    }

    /* ---------- instruments ---------- */

    /** Every crew on the water on one strip above it, with the bends, bridges and record pace. */
    private void drawRibbon(Canvas c, float w, float y, double t, double you) {
        float left = dp(16f);
        float right = w - dp(16f);
        float span = right - left;
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0x33FFFFFF);
        c.drawRoundRect(left, y - dp(3f), right, y + dp(3f), dp(3f), dp(3f), panel);
        panel.setColor(0x88F0B132);
        for (int b = 0; b < bendStart.length; b++) {
            c.drawRect(left + span * bendStart[b] / raceMeters, y - dp(3f), left + span * bendEnd[b] / raceMeters,
                    y + dp(3f), panel);
        }
        panel.setColor(0xFFB8AC9A);
        for (float bm : bridgeM) {
            float bx = left + span * bm / raceMeters;
            c.drawRect(bx - dp(2f), y - dp(7f), bx + dp(2f), y + dp(7f), panel);
        }
        for (int i = 0; i < 3; i++) {
            float f = (float) Math.min(1.0, rivalWater[i] / raceMeters);
            panel.setColor(rivals[i].color);
            c.drawCircle(left + span * f, y, dp(5f), panel);
        }
        if (phase == Phase.RACING && boardCount > 0) {
            float f = (float) Math.min(1.0, t / boardTimes[0]);
            float cx = left + span * f;
            shape.rewind();
            shape.moveTo(cx, y - dp(8f));
            shape.lineTo(cx + dp(6f), y);
            shape.lineTo(cx, y + dp(8f));
            shape.lineTo(cx - dp(6f), y);
            shape.close();
            panel.setColor(0xFFF5C518);
            c.drawPath(shape, panel);
        }
        float mine = (float) Math.min(1.0, you / raceMeters);
        panel.setColor(ACCENT);
        c.drawRoundRect(left, y - dp(3f), left + span * mine, y + dp(3f), dp(3f), dp(3f), panel);
        c.drawCircle(left + span * mine, y, dp(7f), panel);
        label(c, Math.max(0, Math.round(raceMeters - you)) + " m to go", right, y - dp(8f), 9f, TEXT, Paint.Align.RIGHT);
    }

    /** Your gap to each crew over the last two minutes: above the line you lead. */
    private void drawGapGraph(Canvas c, float l, float t, float r, float b) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(10f), dp(10f), panel);
        label(c, "GAP TO EACH CREW ON THE CLOCK  ·  LAST 2 MIN", l + dp(10f), t + dp(16f), 8.5f, FAINT, Paint.Align.LEFT);
        float top = t + dp(24f);
        float bottom = b - dp(8f);
        float mid = (top + bottom) / 2f;
        float max = 10f;
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < gapCount; k++) {
                max = Math.max(max, Math.abs(gaps[i][k]));
            }
        }
        panel.setColor(0x33FFFFFF);
        c.drawRect(l + dp(8f), mid - dp(0.5f), r - dp(8f), mid + dp(0.5f), panel);
        label(c, "+" + Math.round(max) + " m", r - dp(10f), top + dp(8f), 8f, FAINT, Paint.Align.RIGHT);
        label(c, "-" + Math.round(max) + " m", r - dp(10f), bottom - dp(2f), 8f, FAINT, Paint.Align.RIGHT);
        if (gapCount < 2) {
            return;
        }
        panel.setStyle(Paint.Style.STROKE);
        panel.setStrokeWidth(dp(2.2f));
        float step = (r - l - dp(16f)) / (GAP_SAMPLES - 1f);
        for (int i = 0; i < 3; i++) {
            trace.rewind();
            for (int k = 0; k < gapCount; k++) {
                int idx = (gapHead - gapCount + k + GAP_SAMPLES) % GAP_SAMPLES;
                float x = r - dp(8f) - (gapCount - 1 - k) * step;
                float y = mid - (bottom - top) / 2f * gaps[i][idx] / max;
                if (k == 0) {
                    trace.moveTo(x, y);
                } else {
                    trace.lineTo(x, y);
                }
            }
            panel.setColor(rivals[i].color);
            c.drawPath(trace, panel);
        }
        panel.setStyle(Paint.Style.FILL);
    }

    /** One bar per stroke, measured power, coloured against your own recent average. */
    private void drawStrokeBars(Canvas c, float l, float t, float r, float b) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(10f), dp(10f), panel);
        float sum = 0f;
        float max = 50f;
        for (int k = 0; k < strokeCount; k++) {
            sum += strokePower[k];
            max = Math.max(max, strokePower[k]);
        }
        float avg = strokeCount > 0 ? sum / strokeCount : 0f;
        label(c, strokeCount > 0 ? "POWER PER STROKE  ·  AVERAGE " + Math.round(avg) + " W" : "POWER PER STROKE",
                l + dp(10f), t + dp(16f), 8.5f, FAINT, Paint.Align.LEFT);
        float top = t + dp(24f);
        float bottom = b - dp(8f);
        float slot = (r - l - dp(16f)) / STROKE_BARS;
        for (int k = 0; k < strokeCount; k++) {
            int idx = (strokeHead - strokeCount + k + STROKE_BARS) % STROKE_BARS;
            float v = strokePower[idx];
            float x = r - dp(8f) - (strokeCount - k) * slot;
            float barTop = bottom - (bottom - top) * v / (max * 1.1f);
            panel.setColor(v >= avg * 1.03f ? ACCENT : v <= avg * 0.93f ? WARN : BLUE);
            c.drawRoundRect(x + slot * 0.15f, barTop, x + slot * 0.85f, bottom, dp(2f), dp(2f), panel);
        }
        if (strokeCount > 0) {
            float ay = bottom - (bottom - top) * avg / (max * 1.1f);
            panel.setColor(0x88FFFFFF);
            c.drawRect(l + dp(8f), ay - dp(0.75f), r - dp(8f), ay + dp(0.75f), panel);
        }
    }

    /** The shape of your last drive: paddle speed through the stroke, from the pulse meter. */
    private void drawStrokeShape(Canvas c, float l, float t, float r, float b) {
        if (status == null || status.meter.lastStroke == null || b - t < dp(30f)) {
            return;
        }
        float[] rates = status.meter.lastStroke.driveRates;
        if (rates.length < 2) {
            return;
        }
        float max = 1f;
        for (float v : rates) {
            max = Math.max(max, v);
        }
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(8f), dp(8f), panel);
        trace.rewind();
        float left = l + dp(8f);
        float width = r - l - dp(16f);
        float top = t + dp(16f);
        float bottom = b - dp(6f);
        trace.moveTo(left, bottom);
        for (int i = 0; i < rates.length; i++) {
            trace.lineTo(left + width * i / (rates.length - 1f), bottom - (bottom - top) * rates[i] / max);
        }
        trace.lineTo(left + width, bottom);
        trace.close();
        panel.setColor(0x6635D0BA);
        c.drawPath(trace, panel);
        label(c, "LAST DRIVE", l + dp(8f), t + dp(12f), 7.5f, FAINT, Paint.Align.LEFT);
    }

    /* ---------- the event, the courses and the radio, on screen ---------- */

    /** Which day of the event you are on, over the middle of the sky. */
    private void drawDayBanner(Canvas c, float w) {
        String text = dayBanner;
        if (text == null) {
            return;
        }
        int colour = dayBannerColor;
        float half = tickPaint.measureText(text) / 2f + dp(18f);
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xCC0D1420);
        c.drawRoundRect(w / 2f - half, dp(14f), w / 2f + half, dp(40f), dp(13f), dp(13f), panel);
        panel.setStyle(Paint.Style.STROKE);
        panel.setStrokeWidth(dp(1.5f));
        panel.setColor(colour);
        c.drawRoundRect(w / 2f - half, dp(14f), w / 2f + half, dp(40f), dp(13f), dp(13f), panel);
        panel.setStyle(Paint.Style.FILL);
        bold(c, text, w / 2f, dp(32f), 11f, colour, Paint.Align.CENTER);
    }

    /** The two tap plates in the marshalling area: which course, and where you are in the event. */
    private void drawPlates(Canvas c, float l, float t, float r) {
        coursePillL = l;
        coursePillT = t;
        coursePillR = r;
        coursePillB = t + dp(40f);
        plate(c, coursePillL, coursePillT, coursePillR, coursePillB, plateCourseTop, plateCourseSub, ACCENT);
        if (r - l > dp(340f)) {
            // On a narrow plate the blurb would run under the schematic; the blurb is the useful
            // half, so the schematic is what gets dropped.
            drawCourseGlyph(c, r - dp(46f), (coursePillT + coursePillB) / 2f, dp(64f));
        }

        eventPillL = l;
        eventPillT = coursePillB + dp(6f);
        eventPillR = r;
        eventPillB = eventPillT + dp(40f);
        int colour = eventActive() ? 0xFFF5C518 : eventWaiting ? WARN : DIM;
        plate(c, eventPillL, eventPillT, eventPillR, eventPillB, plateEventTop, plateEventSub, colour);
        if (eventActive()) {
            // Three stage pips: heat, repechage, final - the one you are about to row pulses.
            float px = r - dp(22f);
            for (int k = 2; k >= 0; k--) {
                int stage = k == 0 ? EV_HEAT : k == 1 ? EV_REPECHAGE : EV_FINAL;
                boolean here = stage == eventStage;
                boolean done = eventStage > stage;
                float pulse = here ? 0.6f + 0.4f * (float) Math.sin(sessionSeconds * 4) : 1f;
                panel.setColor(here ? (((int) (255 * pulse) << 24) | 0x00F5C518) : done ? 0xFF35D0BA : 0x553F4A5A);
                c.drawCircle(px, eventPillB - dp(10f), dp(3.6f), panel);
                px -= dp(11f);
            }
        }
    }

    private void plate(Canvas c, float l, float t, float r, float b, String top, String sub, int colour) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xE60D1420);
        c.drawRoundRect(l, t, r, b, dp(8f), dp(8f), panel);
        panel.setColor(colour);
        c.drawRoundRect(l, t, l + dp(4f), b, dp(2f), dp(2f), panel);
        bold(c, top, l + dp(12f), t + dp(17f), 11.5f, colour, Paint.Align.LEFT);
        label(c, sub, l + dp(12f), t + dp(31f), 9f, FAINT, Paint.Align.LEFT);
    }

    /** A schematic of the chosen course: the river's shape, its bends and its arches. */
    private void drawCourseGlyph(Canvas c, float cx, float cy, float width) {
        float left = cx - width / 2f;
        shape.rewind();
        shape.moveTo(left, cy);
        for (int k = 0; k <= 24; k++) {
            float f = k / 24f;
            float bump = 0f;
            Course cs = COURSES[courseIndex];
            for (float[] bend : cs.bends) {
                if (f >= bend[0] && f <= bend[1]) {
                    float p = (f - bend[0]) / Math.max(0.001f, bend[1] - bend[0]);
                    bump += (bend[2] == 0 ? -1f : 1f) * (float) Math.sin(Math.PI * p) * dp(7f);
                }
            }
            shape.lineTo(left + width * f, cy + bump);
        }
        panel.setStyle(Paint.Style.STROKE);
        panel.setStrokeWidth(dp(2.5f));
        panel.setColor(0xFF5E8BC0);
        c.drawPath(shape, panel);
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFFB8AC9A);
        for (float f : COURSES[courseIndex].bridges) {
            c.drawRect(left + width * f - dp(1.2f), cy - dp(9f), left + width * f + dp(1.2f), cy + dp(9f), panel);
        }
        panel.setColor(ACCENT);
        c.drawCircle(left, cy, dp(2.6f), panel);
        panel.setColor(0xFFF5C518);
        c.drawRect(left + width - dp(1.5f), cy - dp(6f), left + width + dp(1.5f), cy + dp(6f), panel);
    }

    /** The cox on the radio: a call about the water you are on right now, typed in as it is said. */
    private void drawRadio(Canvas c, float l, float t, float r, float b) {
        if (radioLen == 0 || sessionSeconds > radioUntil + 0.35 || r - l < dp(150f)) {
            return;
        }
        // On a narrow screen (SHUFFLE on a small tablet) the cox and the level meter are dropped
        // rather than squeezing the call itself down to nothing.
        boolean compact = r - l < dp(260f);
        double since = sessionSeconds - radioStart;
        float fade = (float) Math.min(1.0, Math.max(0.0, (radioUntil + 0.35 - sessionSeconds) / 0.35));
        int alpha = (int) (230 * fade);
        panel.setStyle(Paint.Style.FILL);
        panel.setColor((alpha << 24) | 0x000D1420);
        c.drawRoundRect(l, t, r, b, dp(9f), dp(9f), panel);
        panel.setColor(((int) (200 * fade) << 24) | (radioColor & 0x00FFFFFF));
        c.drawRoundRect(l, t, l + dp(4f), b, dp(2f), dp(2f), panel);

        // The cox: headset, and a mouth that moves while the call is still being spoken.
        int shown = (int) Math.min(radioLen, since / 0.018);
        boolean speaking = shown < radioLen;
        float hx = l + dp(30f);
        float hy = t + dp(30f);
        if (compact) {
            drawRadioText(c, l + dp(12f), t, fade, shown);
            return;
        }
        panel.setColor(0xFFF1C27D);
        c.drawCircle(hx, hy, dp(13f), panel);
        panel.setColor(0xFF2E3A4C);
        c.drawRect(hx - dp(15f), hy - dp(18f), hx + dp(15f), hy - dp(9f), panel);   // cap
        panel.setColor(0xFF1B2433);
        c.drawRect(hx - dp(16f), hy - dp(6f), hx - dp(10f), hy + dp(3f), panel);    // ear cup
        panel.setStyle(Paint.Style.STROKE);
        panel.setStrokeWidth(dp(2f));
        c.drawArc(hx - dp(16f), hy - dp(16f), hx + dp(16f), hy + dp(16f), 190f, 70f, false, panel);
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF1B2433);
        c.drawCircle(hx - dp(4f), hy - dp(2f), dp(1.8f), panel);
        c.drawCircle(hx + dp(5f), hy - dp(2f), dp(1.8f), panel);
        float mouth = speaking ? dp(1.5f) + dp(3f) * Math.abs((float) Math.sin(sessionSeconds * 17)) : dp(1.2f);
        c.drawOval(hx - dp(4f), hy + dp(5f) - mouth, hx + dp(4f), hy + dp(5f) + mouth, panel);
        // The boom mic, down in front of the mouth.
        panel.setColor(0xFF1B2433);
        c.drawRect(hx - dp(12f), hy + dp(2f), hx - dp(3f), hy + dp(3.5f), panel);
        panel.setColor(speaking ? BAD : 0xFF3F4A5A);
        c.drawCircle(hx - dp(2f), hy + dp(3f), dp(2.4f), panel);

        // A five-bar level meter that only moves while the cox is talking.
        float vx = r - dp(34f);
        for (int k = 0; k < 5; k++) {
            float lvl = speaking ? 0.25f + 0.75f * Math.abs((float) Math.sin(sessionSeconds * (9 + k * 2.3) + k)) : 0.12f;
            panel.setColor(((int) (200 * fade) << 24) | ((k > 3 ? BAD : k > 2 ? WARN : ACCENT) & 0x00FFFFFF));
            c.drawRect(vx + k * dp(6f), b - dp(8f) - dp(20f) * lvl, vx + k * dp(6f) + dp(4f), b - dp(8f), panel);
        }
        drawRadioText(c, l + dp(52f), t, fade, shown);
        if (since < 0.22) {
            // A burst of static as the set keys up.
            panel.setColor(0x99FFFFFF);
            for (int k = 0; k < 7; k++) {
                float sx = l + dp(52f) + (float) Math.random() * (r - l - dp(80f));
                float sy = t + dp(22f) + (float) Math.random() * (b - t - dp(30f));
                c.drawRect(sx, sy, sx + dp(5f), sy + dp(1.5f), panel);
            }
        }
    }

    /**
     * The call itself, typed in a character at a time. Drawn from a char buffer rather than
     * substring so a frame of the loop allocates nothing.
     */
    private void drawRadioText(Canvas c, float tx, float t, float fade, int shown) {
        label(c, radioTag + "  ·  RADIO", tx, t + dp(15f), 8.5f,
                ((int) (255 * fade) << 24) | (radioColor & 0x00FFFFFF), Paint.Align.LEFT);
        radioPaint.setColor(((int) (255 * fade) << 24) | 0x00E6EDF7);
        int line1 = radioBreak >= 0 ? Math.min(shown, radioBreak) : shown;
        c.drawText(radioChars, 0, Math.max(0, line1), tx, t + dp(34f), radioPaint);
        if (radioBreak >= 0) {
            int from = radioBreak + 1;
            int count = Math.max(0, Math.min(shown, radioLen) - from);
            c.drawText(radioChars, from, count, tx, t + dp(50f), radioPaint);
        }
    }

    /**
     * The overtaking window draining away: the reason to go now rather than in a minute.
     *
     * <p>Anchored to the water rather than to a fraction of the height. At {@code h * 0.15 + 30dp}
     * it sat on top of the distance ribbon ({@code waterTop - 56dp}) at every height this game is
     * actually drawn at - they only clear each other above about 1020dp, and the tablet's game view
     * is nearer 930dp. Hung under the ribbon it is clear of it, and of the sub-label above it, at
     * any size.
     */
    private void drawBoostMeter(Canvas c, float w, float waterTop) {
        if (boostGain <= 0f) {
            return;
        }
        float f = (float) Math.max(0.0, Math.min(1.0, (boostUntil - sessionSeconds) / BOOST_SECONDS));
        float half = Math.min(dp(150f), w * 0.42f);
        float top = waterTop - dp(42f);
        float bottom = top + dp(16f);
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xCC0D1420);
        c.drawRoundRect(w / 2f - half, top, w / 2f + half, bottom, dp(8f), dp(8f), panel);
        float barL = w / 2f - half + dp(2f);
        float barR = barL + (2 * half - dp(4f)) * f;
        panel.setColor(0xFFF5C518);
        c.drawRoundRect(barL, top + dp(2f), barR, bottom - dp(2f), dp(6f), dp(6f), panel);
        String text = String.format(java.util.Locale.US, "PUDDLES OF THE %s   +%.1f%%%s", boostFrom,
                boostGain * 100f, boostChain > 1 ? "   CHAIN x" + boostChain : "");
        // The label is centred and the bar drains from the left, so within a couple of seconds the
        // middle of the text is off the gold. Drawn once dark inside the bar and once gold outside
        // it, clipped, so it reads the whole way down instead of vanishing into the pill.
        float base = bottom - dp(4f);
        c.save();
        c.clipRect(barL, top, barR, bottom);
        bold(c, text, w / 2f, base, 10f, 0xFF10131A, Paint.Align.CENTER);
        c.restore();
        c.save();
        c.clipRect(barR, top, w / 2f + half, bottom);
        bold(c, text, w / 2f, base, 10f, 0xFFF5C518, Paint.Align.CENTER);
        c.restore();
    }

    /* ---------- the debrief: your splits against the record ---------- */

    /**
     * The finish panel's text, built once at the line. Everything it says is fixed from that
     * moment, so building it here keeps the frame loop free of formatting.
     */
    private void buildSplitRows() {
        splitsHeading = cmpSplitCount > 0 ? "YOUR SPLITS vs THE RECORD'S OWN SPLITS"
                : cmpRecord > 0f ? "YOUR SPLITS vs THE RECORD AT EVEN PACE" : "YOUR SPLITS";
        float worstLoss = 0f;
        int worstAt = -1;
        float bestGain = 0f;
        int bestAt = -1;
        for (int k = 0; k < mySplitCount; k++) {
            int metres = Math.min(raceMeters, (k + 1) * splitEveryM);
            float segment = k == 0 ? mySplit[0] : mySplit[k] - mySplit[k - 1];
            float against = compareAt(k, metres);
            splitMetresText[k] = metres + " m";
            splitClockText[k] = clock(mySplit[k]);
            splitSegText[k] = PersonalBests.formatTime(segment);
            if (against <= 0f) {
                splitDelta[k] = 0f;
                splitDeltaText[k] = null;
                continue;
            }
            float diff = against - mySplit[k];                          // + means you are up
            splitDelta[k] = diff;
            splitDeltaText[k] = String.format(java.util.Locale.US, "%s%.1f s", diff >= 0 ? "+" : "-",
                    Math.abs(diff));
            float segAgainst = k == 0 ? against : against - compareAt(k - 1, k * splitEveryM);
            float segDiff = segAgainst > 0f ? segAgainst - segment : 0f;
            if (segDiff < worstLoss) {
                worstLoss = segDiff;
                worstAt = k;
            }
            if (segDiff > bestGain) {
                bestGain = segDiff;
                bestAt = k;
            }
        }
        if (cmpRecord <= 0f) {
            splitsFooter = "First time down this course - these splits are the ones to beat now.";
            return;
        }
        float total = cmpRecord - (float) finishTime;
        String footer = String.format(java.util.Locale.US, "%.1f s %s the record overall", Math.abs(total),
                total >= 0 ? "up on" : "down on");
        if (worstAt >= 0 && worstLoss < -0.4f) {
            footer += String.format(java.util.Locale.US, "  ·  lost most in the %d m split (%.1f s)",
                    Math.min(raceMeters, (worstAt + 1) * splitEveryM), -worstLoss);
        } else if (bestAt >= 0 && bestGain > 0.4f) {
            footer += String.format(java.util.Locale.US, "  ·  won it in the %d m split (+%.1f s)",
                    Math.min(raceMeters, (bestAt + 1) * splitEveryM), bestGain);
        }
        splitsFooter = footer;
    }

    /** Every split of the race you just rowed against the record's own splits at the same marks. */
    private void drawSplits(Canvas c, float l, float t, float r, float b) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(10f), dp(10f), panel);
        label(c, splitsHeading, l + dp(10f), t + dp(16f), 8.5f, 0xFFF5C518, Paint.Align.LEFT);
        if (mySplitCount == 0) {
            label(c, "No splits - the race was too short to take one.", l + dp(14f), t + dp(40f), 11f,
                    DIM, Paint.Align.LEFT);
            return;
        }
        float top = t + dp(24f);
        // A short panel (SHUFFLE on a small screen) must not give a negative row height, which
        // would stack the splits upward out of the panel.
        float row = Math.max(dp(9f), Math.min(dp(22f), (b - dp(20f) - top) / mySplitCount));
        float mid = r - dp(120f);
        float scale = dp(52f);
        for (int k = 0; k < mySplitCount; k++) {
            float y = top + row * k;
            bold(c, splitMetresText[k], l + dp(14f), y + row * 0.74f, 11f, TEXT, Paint.Align.LEFT);
            bold(c, splitClockText[k], l + dp(74f), y + row * 0.74f, 11f, TEXT, Paint.Align.LEFT);
            label(c, splitSegText[k], l + dp(130f), y + row * 0.74f, 10f, DIM, Paint.Align.LEFT);
            if (splitDeltaText[k] == null) {
                continue;
            }
            // A bar out of the centre line: left of it you are down, right of it you are up.
            float diff = splitDelta[k];
            float bar = Math.max(-scale, Math.min(scale, diff * scale / 6f));
            panel.setColor(0x33FFFFFF);
            c.drawRect(mid - dp(0.5f), y + dp(2f), mid + dp(0.5f), y + row - dp(2f), panel);
            panel.setColor(diff >= 0 ? ACCENT : BAD);
            c.drawRect(Math.min(mid, mid + bar), y + row * 0.28f, Math.max(mid, mid + bar), y + row * 0.72f, panel);
            bold(c, splitDeltaText[k], r - dp(12f), y + row * 0.74f, 11f, diff >= 0 ? ACCENT : BAD,
                    Paint.Align.RIGHT);
        }
        label(c, splitsFooter, l + dp(14f), b - dp(7f), 9.5f, FAINT, Paint.Align.LEFT);
    }

    private static String ordinal(int n) {
        return n == 1 ? "1ST" : n == 2 ? "2ND" : n == 3 ? "3RD" : n + "TH";
    }
}
