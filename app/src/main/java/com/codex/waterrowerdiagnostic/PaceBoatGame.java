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

    /** Where the crew is, water included. */
    protected final double crewDistance(int i) {
        return crewMeters(i) + crewWater[i];
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
            delta = "LEAD";
            color = ACCENT;
        } else {
            double d = youSplit[k] - crew;
            delta = String.format(java.util.Locale.US, "%s%.1f s", d <= 0 ? "−" : "+", Math.abs(d));
            color = d <= 0 ? ACCENT : BAD;
        }
        splitRows[k] = String.format(java.util.Locale.US, "%5d m   %s   %s", k * 500,
                PersonalBests.formatPace((float) seg), delta);
        splitRowColor[k] = color;
        if (k == splitBannerSplit && sessionSeconds < splitBannerUntil) {
            splitBannerSub = crew < 0 ? "FIRST THROUGH THE MARKER"
                    : (youSplit[k] <= crew ? "AHEAD OF " : "BEHIND ") + crewName(whoScratch[0]) + " BY "
                    + String.format(java.util.Locale.US, "%.1f s", Math.abs(youSplit[k] - crew));
        }
    }

    /* ---------- input ---------- */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            if (state == State.FINISHED) {
                start();
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
        drawLaneTags(c, waterTop, waterBottom);
        if (sessionSeconds < calloutUntil) {
            bold(c, callout, w / 2f, waterTop + span * 0.5f, 30f, calloutColor, Paint.Align.CENTER);
        }
        if (sprinting && state == State.RACING) {
            drawSprintWater(c, w, waterTop, waterBottom, speed);
        }

        drawHud(c, w, h, hudH, speed, gap, lead);

        // Progress bar along the top of the water.
        float pct = Math.min(1f, (float) (raceDistance() / raceMeters));
        accentPaint.setColor(ACCENT);
        accentPaint.setStrokeWidth(dp(3f));
        c.drawLine(0, waterTop, w * pct, waterTop, accentPaint);

        if (state == State.FINISHED) {
            drawFinish(c, w, h);
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
        }
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
        } else if (sessionSeconds < splitBannerUntil && state == State.RACING) {
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
    }

    private void drawFinish(Canvas c, float w, float h) {
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
        label(c, "tap to row again", w / 2f, h * 0.90f, 11f, FAINT, Paint.Align.CENTER);
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
