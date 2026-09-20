package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * REGATTA: a regatta day of two 500 m races - a heat, then a final - inside a weekly season.
 *
 * <p>Seven named rival crews share your division. Each day they are split into two heats; you race
 * three of them in yours. Finish in the top two and you row the A final against the best four; miss
 * and you row the B final. Where you finish in the final earns season points (A: 12/9/7/5,
 * B: 4/3/2/1). The rivals collect points all week whether you row or not, so a day off costs
 * ground. When the week (Monday to Sunday, local) rolls over, the top two of the eight-crew ladder
 * are promoted a division and the bottom two relegated; a week with no racing holds you where you
 * are. Every crew keeps a head-to-head record against you, and podiums, cups, season titles and
 * promotions go in a trophy cabinet. Finals are rowed past moored spectator boats under bunting.
 *
 * <p>One ranked regatta day per local day ({@code regatta.day}), as before. Leaving between the
 * heat and the final resumes at the final. After that, regattas are practice: nothing is recorded.
 *
 * <p>Crew speeds come from the rower's own typical speed, the division factor and each crew's
 * strength, so the field is always within reach of whoever is rowing.
 *
 * <p>3.23.0 adds five things on top of that week:
 * <ul>
 *   <li><b>The points table</b> is a real table: every crew's Monday-to-Sunday day grid, a form
 *       strip of their last five results, days raced, the head-to-head, and bars that count up
 *       when the table opens.</li>
 *   <li><b>Qualification.</b> Race {@value #QUALIFY_DAYS} ranked days in the week and Sunday
 *       becomes the GRAND FINAL - one 500 m race against the top three of the ladder for double
 *       points. Miss the qualification and Sunday is an ordinary regatta day. A progress strip of
 *       seven day pips sits in the HUD from Monday, so the target is always visible, and an
 *       EXHIBITION pill rows the grand-final course unranked once the day's regatta is done.</li>
 *   <li><b>A medal ceremony</b> after every ranked final: the podium rises, the crews walk on, a
 *       medal comes down on its ribbon, flags climb their poles and the sky goes up in fireworks.
 *       Tap to skip.</li>
 *   <li><b>A grudge meter</b> against your nemesis - the crew with the best record over you. Its
 *       needle sits on the head-to-head and swings live with the metres between you whenever they
 *       are in your race. Lead them by {@value #GRUDGE_TARGET} and the grudge is settled.</li>
 *   <li><b>Off-season training.</b> Metres rowed outside the ranked races bank toward pre-season
 *       points that start you ahead on next week's ladder.</li>
 * </ul>
 */
final class RegattaGame extends GameView {

    static final String[] DIVISIONS = {"CLUB", "COUNTY", "REGIONAL", "NATIONAL", "INTERNATIONAL", "OLYMPIC"};
    private static final float[] FACTOR = {0.90f, 0.95f, 1.00f, 1.04f, 1.08f, 1.12f};
    private static final int RACE_METERS = 500;

    /* ---------- the rival crews ---------- */

    private static final String[] CREWS = {"KINGFISHER", "IRON OARS", "BLUE HERON", "RED LANTERN",
            "NORTHSIDE", "STORM PETRELS", "OLD BRIDGE"};
    private static final int[] CREW_COLORS = {0xFF3FA9F5, 0xFF9AA7B8, 0xFF6F8CFF, 0xFFF0655D,
            0xFFF0B132, 0xFFB48CFF, 0xFF8BC34A};
    /** Race plan per crew: 0 fast start, 1 even, 2 sprinter. */
    private static final int[] CREW_PLAN = {0, 1, 2, 0, 1, 2, 2};
    private static final String[] PLAN_NAMES = {"fast starters", "even pacers", "sprinters"};
    private static final float[] SHAPE = {0.25f, 0f, -0.25f};
    /** Speed offset from the division's pace. Kingfisher and Iron Oars are the crews to beat. */
    private static final float[] STRENGTH = {0.025f, 0.018f, 0.008f, 0.002f, -0.006f, -0.014f, -0.022f};
    private static final int NCREWS = 7;

    /** Season points by overall day placing: A final 1-4, then B final 1-4. */
    private static final int[] POINTS = {12, 9, 7, 5, 4, 3, 2, 1};
    /** The grand final pays double: it is one race, and only qualifiers are in it. */
    private static final int[] GRAND_POINTS = {24, 18, 14, 10};
    /** Ranked days needed inside the week to be entered for Sunday's grand final. */
    static final int QUALIFY_DAYS = 3;
    /** Lead a crew by this many head-to-head wins and the grudge is settled. */
    static final int GRUDGE_TARGET = 3;
    /** Most pre-season points a week of training can carry, so training never replaces racing. */
    private static final int TRAIN_CAP = 6;
    /** Seconds of rowing at the rower's typical speed that earn one pre-season point. */
    private static final double TRAIN_SECONDS_PER_POINT = 420;

    private enum Phase { READY, RACING, RESULTS, CEREMONY, DONE }
    private enum Stage { HEAT, A_FINAL, B_FINAL, GRAND }
    private enum Overlay { NONE, LADDER, CABINET }

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF btnLadder = new RectF();
    private final RectF btnCabinet = new RectF();
    private final RectF btnGrand = new RectF();
    private final RectF barRect = new RectF();
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private android.graphics.LinearGradient skyShader;
    private android.graphics.LinearGradient finalSkyShader;
    private float skyHeight;
    private String callout = "";
    private int calloutColor = ACCENT;
    private double calloutUntil;
    private double confettiUntil;
    private double fireworksUntil;
    private double nextFirework;
    private float cheer;
    private static final String[] SHELF_NAMES = {"GOLD", "SILVER", "BRONZE", "B FINAL WINS",
            "SEASON TITLES", "PROMOTIONS", "GRAND FINALS"};
    private static final int[] SHELF_TINT = {0xFFF5C518, 0xFFC9D2DC, 0xFFCD7F32, 0xFF6F8CFF,
            0xFF35D0BA, 0xFFB48CFF, 0xFFFF8A4C};
    /** Where each shelf-two trophy lives in {@link #cabinet}; grand finals were appended at 12. */
    private static final int[] SHELF_SLOT = {0, 1, 2, 3, 4, 5, 12};
    private static final int[] PARTY = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};

    /* ---------- the race on the water ---------- */

    private Phase phase = Phase.READY;
    private Stage stage = Stage.HEAT;
    private Overlay overlay = Overlay.NONE;
    private int division;
    private boolean ranked;
    private int practiceRun;
    private long seedBase;
    private final int[] laneCrew = new int[3];
    private final double[] finishTimes = new double[3];
    private final float[] rivalX = new float[3];
    private final boolean[] crewAhead = new boolean[3];
    private final String[] laneName = new String[3];
    private final double[] sortScratch = new double[3];
    private boolean aheadKnown;
    private double raceStart;
    private double startMeters;
    private double finishTime;
    private int placing;
    private String outcome = "";
    private String outcome2 = "";
    private double resultsFrom;

    /* ---------- the day's draw ---------- */

    /** Your heat's crews sorted by finish, and the other heat's four, set when the heat ends. */
    private final int[] heatOrder = new int[4];     // -1 is you
    private final int[] otherHeat = new int[4];
    private final double[] otherHeatTimes = new double[4];
    private final int[] otherFinal = new int[4];
    private int heatPlacing;

    /* ---------- the season ---------- */

    private long seasonWeek;
    private int seasonPoints;
    private int seasonMask;
    private final int[] seasonRivalActual = new int[NCREWS];
    private String seasonNews = "";
    private int seasonNewsColor = ACCENT;
    private final int[] ladderPts = new int[NCREWS + 1];   // index NCREWS is you
    private final int[] ladderOrder = new int[NCREWS + 1];
    private int ladderRank;
    /** Points won on each day of this week, so the table can show a day grid and a form strip. */
    private final int[] seasonDayPts = new int[7];
    private final int[][] seasonRivalDay = new int[NCREWS][7];
    /** Counting up bars in the table, eased from zero each time it is opened. */
    private final float[] ladderBar = new float[NCREWS + 1];
    /** Every crew's points per day of this week, you at index NCREWS; built off the frame loop. */
    private final int[][] dayGrid = new int[NCREWS + 1][7];
    private double ladderOpenedAt;

    /* ---------- qualification for the grand final ---------- */

    private int daysRaced;
    private boolean qualified;
    private boolean grandDay;
    /** A practice run of the grand-final course, chosen from the pill once the day is done. */
    private boolean exhibition;

    /* ---------- the grudge ---------- */

    private int nemesis = -1;
    private int grudgeSettledMask;
    private int grudgeLane = -1;     // the nemesis's lane in this race, or -1
    private float grudgeNeedle;
    private float grudgeLive;
    private double grudgeFlashUntil;
    private String grudgeNote = "";

    /* ---------- off-season training ---------- */

    private long trainWeek;
    private double trainMetres;
    private int carriedPoints;
    private double lastTrainMark;
    private double trainSaveAt;

    /* ---------- the medal ceremony ---------- */

    private double ceremonyFrom;
    private int ceremonyMedal = -1;         // 0 gold, 1 silver, 2 bronze, -1 none
    private final int[] podium = new int[3];  // crew per place, -1 is you
    private boolean ceremonyBurst;
    private double ceremonyFirework;

    /* ---------- records and trophies ---------- */

    private final int[] h2hWins = new int[NCREWS];
    private final int[] h2hLosses = new int[NCREWS];
    /**
     * gold, silver, bronze, B-final wins, season titles, promotions, then A-final wins per
     * division (6..11), then grand-final wins (12). Appended, never reordered - the stored string
     * is read by index and an older one simply stops short.
     */
    private final int[] cabinet = new int[6 + 6 + 1];

    RegattaGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
        boardText.setTextSize(dp(11f));
        boardText.setFakeBoldText(true);
    }

    static long today() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    /** Monday-start week number. Day 0 of the epoch was a Thursday. */
    private static long weekOf(long day) {
        long d = day + 3;
        return d >= 0 ? d / 7 : (d - 6) / 7;
    }

    /** 0 on Monday to 6 on Sunday. */
    private static int dayOfWeek(long day) {
        return (int) (((day + 3) % 7 + 7) % 7);
    }

    /* =====================================================================
     * Lifecycle and the day's draw
     * ===================================================================== */

    @Override
    protected void onStart() {
        division = Math.max(0, Math.min(DIVISIONS.length - 1, Math.round(bests.get("regatta.division", 0f))));
        loadRecords();
        loadCabinet();
        loadGrudge();
        seasonNews = "";
        loadTraining();
        loadSeason();
        ranked = Math.round(bests.get("regatta.day", -1f)) != today();
        if (ranked) {
            // The exhibition pill only exists once the day's ranked regatta is rowed, and the flag
            // outlives start(). Left set across a midnight rollover it would have sent a ranked,
            // unqualified day straight to the grand final for double points, with no heat.
            exhibition = false;
        }
        seedBase = today() * 7919L + division * 31L + (ranked ? 0 : 1000L + practiceRun * 97L);
        overlay = Overlay.NONE;
        outcome = "";
        outcome2 = "";
        callout = "";
        calloutUntil = 0;
        confettiUntil = 0;
        fireworksUntil = 0;
        nextFirework = 0;
        grudgeNote = "";
        grudgeFlashUntil = 0;
        lastTrainMark = 0;
        trainSaveAt = 0;
        if (!seasonNews.isEmpty()) {
            confettiUntil = seasonNewsColor == ACCENT ? 4.0 : 0;
        }
        daysRaced = Integer.bitCount(seasonMask & 0x7F);
        qualified = daysRaced >= QUALIFY_DAYS;
        grandDay = dayOfWeek(today()) == 6 && qualified;
        recomputeLadder();
        pickNemesis();
        grudgeNeedle = grudgeTarget();
        drawDay();
        if (ranked && grandDay) {
            setupGrand();
        } else if (ranked && resumeFinal()) {
            outcome = "HEAT DONE EARLIER - " + ordinal(heatPlacing) + ". ON TO THE FINAL";
        } else if (exhibition) {
            setupGrand();
        } else {
            setupRace(Stage.HEAT);
        }
    }

    @Override
    protected void onStop() {
        saveTraining();
    }

    /** The grand final: you against the three crews leading this week's ladder. */
    private void setupGrand() {
        int n = 0;
        for (int i = 0; i <= NCREWS && n < 3; i++) {
            int e = ladderOrder[i];
            if (e != NCREWS) {
                laneCrew[n++] = e;
            }
        }
        setupRace(Stage.GRAND);
    }

    /** Splits the seven crews into your heat (three) and the other heat (four), and times the other heat. */
    private void drawDay() {
        java.util.Random r = new java.util.Random(seedBase);
        int[] order = {0, 1, 2, 3, 4, 5, 6};
        for (int i = NCREWS - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        for (int i = 0; i < 3; i++) {
            laneCrew[i] = order[i];
        }
        for (int i = 0; i < 4; i++) {
            otherHeat[i] = order[3 + i];
            otherHeatTimes[i] = crewTime(otherHeat[i], Stage.HEAT);
        }
        sortByTime(otherHeat, otherHeatTimes);
    }

    /** A crew's 500 m time in a given race of today's regatta: deterministic, so the day is fixed. */
    private double crewTime(int crew, Stage s) {
        java.util.Random r = new java.util.Random(seedBase * 13 + s.ordinal() * 101 + crew * 7);
        double lift = s == Stage.A_FINAL ? 1.01 : s == Stage.GRAND ? 1.025 : 1.0;
        double speed = profile.typicalSpeed() * FACTOR[division] * (1 + STRENGTH[crew])
                * (0.985 + r.nextDouble() * 0.03) * lift;
        return RACE_METERS / Math.max(1.0, speed);
    }

    private static void sortByTime(int[] crews, double[] times) {
        for (int i = 1; i < crews.length; i++) {
            for (int j = i; j > 0 && times[j] < times[j - 1]; j--) {
                double t = times[j];
                times[j] = times[j - 1];
                times[j - 1] = t;
                int c = crews[j];
                crews[j] = crews[j - 1];
                crews[j - 1] = c;
            }
        }
    }

    private void setupRace(Stage s) {
        stage = s;
        phase = Phase.READY;
        aheadKnown = false;
        grudgeLane = -1;
        for (int i = 0; i < 3; i++) {
            finishTimes[i] = crewTime(laneCrew[i], s);
            rivalX[i] = 0f;
            int k = laneCrew[i];
            laneName[i] = CREWS[k] + "  " + h2hWins[k] + "-" + h2hLosses[k];
            if (k == nemesis) {
                grudgeLane = i;
            }
        }
        if (grudgeLane >= 0) {
            // The grudge outranks every other pre-race line: this is the crew you came for.
            callout = "GRUDGE RACE  ·  " + CREWS[nemesis] + "  " + h2hWins[nemesis] + "-" + h2hLosses[nemesis];
            calloutColor = CREW_COLORS[nemesis];
            calloutUntil = sessionSeconds + 3.2;
        } else if (s != Stage.HEAT) {
            // The crew with the best record against you gets the pre-race line.
            int worst = laneCrew[0];
            for (int i = 1; i < 3; i++) {
                int k = laneCrew[i];
                if (h2hLosses[k] - h2hWins[k] > h2hLosses[worst] - h2hWins[worst]) {
                    worst = k;
                }
            }
            if (h2hLosses[worst] > 0) {
                callout = CREWS[worst] + " HAVE BEATEN YOU " + h2hLosses[worst] + "x";
                calloutColor = CREW_COLORS[worst];
                calloutUntil = sessionSeconds + 4.0;
            }
        }
    }

    /** Builds both finals from the heat result, then puts you in yours. */
    private void setupFinals() {
        lineUpFinals(laneCrew, otherFinal);
        setupRace(heatPlacing <= 2 ? Stage.A_FINAL : Stage.B_FINAL);
    }

    /** Your final's three crews into {@code lanes}, the other final's four into {@code others}. */
    private void lineUpFinals(int[] lanes, int[] others) {
        boolean aFinal = heatPlacing <= 2;
        int[] heatCrews = new int[3];
        int n = 0;
        for (int k : heatOrder) {
            if (k >= 0) {
                heatCrews[n++] = k;
            }
        }
        if (aFinal) {
            lanes[0] = heatCrews[0];
            lanes[1] = otherHeat[0];
            lanes[2] = otherHeat[1];
            others[0] = heatCrews[1];
            others[1] = heatCrews[2];
            others[2] = otherHeat[2];
            others[3] = otherHeat[3];
        } else {
            lanes[0] = heatCrews[2];
            lanes[1] = otherHeat[2];
            lanes[2] = otherHeat[3];
            others[0] = heatCrews[0];
            others[1] = heatCrews[1];
            others[2] = otherHeat[0];
            others[3] = otherHeat[1];
        }
    }

    private boolean resumeFinal() {
        String s = bests.getString("regatta.heat");
        if (s == null) {
            return false;
        }
        try {
            String[] p = s.split("\\|");
            if (Long.parseLong(p[0]) != today() || Integer.parseInt(p[1]) != division) {
                return false;
            }
            heatPlacing = Integer.parseInt(p[2]);
            String[] lanes = p[3].split(",");
            String[] others = p[4].split(",");
            for (int i = 0; i < 3; i++) {
                laneCrew[i] = Integer.parseInt(lanes[i]);
            }
            for (int i = 0; i < 4; i++) {
                otherFinal[i] = Integer.parseInt(others[i]);
            }
            setupRace(heatPlacing <= 2 ? Stage.A_FINAL : Stage.B_FINAL);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /* =====================================================================
     * Finishing a race
     * ===================================================================== */

    private void finishHeat(double t) {
        heatPlacing = 1;
        for (double ft : finishTimes) {
            if (ft < t) {
                heatPlacing++;
            }
        }
        // Your heat's order, you as -1.
        int[] crews = {laneCrew[0], laneCrew[1], laneCrew[2], -1};
        double[] times = {finishTimes[0], finishTimes[1], finishTimes[2], t};
        sortByTime(crews, times);
        System.arraycopy(crews, 0, heatOrder, 0, 4);
        if (ranked) {
            recordHeadToHead(t);
        }
        boolean through = heatPlacing <= 2;
        callout = through ? "THROUGH TO THE A FINAL!" : "INTO THE B FINAL";
        calloutColor = through ? ACCENT : WARN;
        calloutUntil = sessionSeconds + 3.0;
        confettiUntil = through ? sessionSeconds + 2.0 : 0;
        phase = Phase.RESULTS;
        resultsFrom = sessionSeconds;
        finishTime = t;
        if (ranked) {
            // Resume point, so leaving between the races does not cost the final.
            int[] lanesAfter = new int[3];
            int[] othersAfter = new int[4];
            lineUpFinals(lanesAfter, othersAfter);
            bests.putString("regatta.heat", today() + "|" + division + "|" + heatPlacing + "|"
                    + lanesAfter[0] + "," + lanesAfter[1] + "," + lanesAfter[2] + "|"
                    + othersAfter[0] + "," + othersAfter[1] + "," + othersAfter[2] + "," + othersAfter[3]);
        }
    }

    private void beginFinal() {
        setupFinals();
    }

    private void finishFinal(double t) {
        placing = 1;
        for (double ft : finishTimes) {
            if (ft < t) {
                placing++;
            }
        }
        boolean grand = stage == Stage.GRAND;
        boolean a = stage == Stage.A_FINAL || grand;
        int pts = grand ? GRAND_POINTS[placing - 1] : POINTS[(a ? 0 : 4) + placing - 1];
        finishTime = t;
        buildPodium(t);
        bests.recordLowest("time." + RACE_METERS, (float) t);
        if (ranked) {
            recordHeadToHead(t);
            resolveGrudge(t);
            bests.putFloat("regatta.day", today());
            // Season points for everyone who raced today: you and all seven crews.
            int dow = dayOfWeek(today());
            if ((seasonMask & (1 << dow)) == 0) {
                seasonMask |= 1 << dow;
                seasonPoints += pts;
                seasonDayPts[dow] = pts;
                for (int i = 0; i < 3; i++) {
                    int place = 1;
                    for (int j = 0; j < 3; j++) {
                        if (finishTimes[j] < finishTimes[i]) {
                            place++;
                        }
                    }
                    if (t < finishTimes[i]) {
                        place++;
                    }
                    seasonRivalDay[laneCrew[i]][dow] +=
                            grand ? GRAND_POINTS[place - 1] : POINTS[(a ? 0 : 4) + place - 1];
                }
                // The four crews not in your race row the other final of the day.
                double[] ot = new double[4];
                int[] oc = new int[4];
                if (grand) {
                    // Everyone outside the grand final rows the consolation, in ladder order.
                    int n = 0;
                    for (int i = 0; i <= NCREWS && n < 4; i++) {
                        int e = ladderOrder[i];
                        if (e != NCREWS && e != laneCrew[0] && e != laneCrew[1] && e != laneCrew[2]) {
                            oc[n++] = e;   // already in ladder order, so no sort is needed
                        }
                    }
                } else {
                    System.arraycopy(otherFinal, 0, oc, 0, 4);
                    for (int i = 0; i < 4; i++) {
                        ot[i] = crewTime(oc[i], a ? Stage.B_FINAL : Stage.A_FINAL);
                    }
                    sortByTime(oc, ot);
                }
                // You rowed the A final or the grand, so they rowed the lesser one, and vice versa.
                for (int i = 0; i < 4; i++) {
                    seasonRivalDay[oc[i]][dow] += POINTS[(a ? 4 : 0) + i];
                }
                syncRivalTotals();
                saveSeason();
                daysRaced = Integer.bitCount(seasonMask & 0x7F);
                qualified = daysRaced >= QUALIFY_DAYS;
            }
            // The cabinet.
            if (a && placing <= 3) {
                cabinet[placing - 1]++;
            }
            if (a && placing == 1) {
                cabinet[6 + division]++;
                bests.recordHighest("regatta.golds", cabinet[0]);
            }
            if (grand && placing == 1) {
                cabinet[12]++;
                bests.recordHighest("regatta.grands", cabinet[12]);
            }
            if (!a && placing == 1) {
                cabinet[3]++;
            }
            saveCabinet();
            bests.recordHighest("regatta.best", division + 1);
            recomputeLadder();
            outcome = (grand ? "GRAND FINAL " : a ? "A FINAL " : "B FINAL ") + ordinal(placing)
                    + "  ·  +" + pts + " PTS  ·  " + ordinal(ladderRank) + " ON THE WEEK'S LADDER";
            outcome2 = ladderRank <= 2 ? "IN THE PROMOTION ZONE" : ladderRank >= 7 ? "IN THE RELEGATION ZONE" : "";
        } else {
            outcome = (exhibition ? "EXHIBITION GRAND FINAL - " : "PRACTICE - ") + "TODAY'S RANKED REGATTA IS DONE";
            outcome2 = "";
        }
        boolean gold = a && placing == 1;
        confettiUntil = sessionSeconds + (placing == 1 ? 5.0 : 1.2);
        fireworksUntil = gold ? sessionSeconds + 6.0 : 0;
        callout = grand && placing == 1 ? "SEASON CHAMPIONS!" : gold ? "REGATTA CHAMPIONS!"
                : placing == 1 ? "B FINAL WON" : a && placing <= 3 ? "ON THE PODIUM"
                : placing == 4 ? "LAST PLACE" : "FINISHED";
        calloutColor = placing == 1 ? 0xFFF5C518 : placing == 4 ? BAD : WARN;
        calloutUntil = sessionSeconds + 3.5;
        // The ceremony: every final is worth standing on the pontoon for, ranked or exhibition.
        ceremonyMedal = a && placing <= 3 ? placing - 1 : -1;
        ceremonyFrom = sessionSeconds;
        ceremonyBurst = false;
        ceremonyFirework = 0;
        phase = Phase.CEREMONY;
    }

    /** Places 1-3 of the final just rowed, as crew indexes with -1 for you. */
    private void buildPodium(double t) {
        int[] crews = {laneCrew[0], laneCrew[1], laneCrew[2], -1};
        double[] times = {finishTimes[0], finishTimes[1], finishTimes[2], t};
        sortByTime(crews, times);
        System.arraycopy(crews, 0, podium, 0, 3);
    }

    private void recordHeadToHead(double t) {
        for (int i = 0; i < 3; i++) {
            if (t <= finishTimes[i]) {
                h2hWins[laneCrew[i]]++;
            } else {
                h2hLosses[laneCrew[i]]++;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < NCREWS; k++) {
            if (k > 0) {
                sb.append(',');
            }
            sb.append(h2hWins[k]).append(':').append(h2hLosses[k]);
        }
        bests.putString("regatta.rivals", sb.toString());
    }

    /* =====================================================================
     * Persistence: records, cabinet, season
     * ===================================================================== */

    private void loadRecords() {
        java.util.Arrays.fill(h2hWins, 0);
        java.util.Arrays.fill(h2hLosses, 0);
        String s = bests.getString("regatta.rivals");
        if (s == null) {
            return;
        }
        String[] parts = s.split(",");
        for (int k = 0; k < Math.min(NCREWS, parts.length); k++) {
            String[] wl = parts[k].split(":");
            try {
                h2hWins[k] = Integer.parseInt(wl[0]);
                h2hLosses[k] = Integer.parseInt(wl[1]);
            } catch (RuntimeException ignored) {
                // A damaged entry just starts that rivalry again.
            }
        }
    }

    private void loadCabinet() {
        java.util.Arrays.fill(cabinet, 0);
        String s = bests.getString("regatta.cabinet");
        if (s == null) {
            return;
        }
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(cabinet.length, parts.length); i++) {
            try {
                cabinet[i] = Integer.parseInt(parts[i]);
            } catch (RuntimeException ignored) {
                // Leave it at zero.
            }
        }
    }

    private void saveCabinet() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cabinet.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(cabinet[i]);
        }
        bests.putString("regatta.cabinet", sb.toString());
    }

    /**
     * Loads the week's season, first settling any week that has ended since it was last seen.
     *
     * <p>Format: {@code week|yourPoints|daysMask|rivalTotals(7)|yourDayPoints(7)|rivalDayPoints(49)}.
     * The last two fields were appended in 3.23.0 for the day grid in the table; a season written
     * by an older build stops after the totals and its days are spread evenly over the days raced.
     */
    private void loadSeason() {
        long week = weekOf(today());
        seasonWeek = week;
        seasonPoints = carriedPoints;   // pre-season training starts a new week ahead
        seasonMask = 0;
        java.util.Arrays.fill(seasonRivalActual, 0);
        java.util.Arrays.fill(seasonDayPts, 0);
        for (int k = 0; k < NCREWS; k++) {
            java.util.Arrays.fill(seasonRivalDay[k], 0);
        }
        String s = bests.getString("regatta.season");
        if (s != null) {
            try {
                String[] p = s.split("\\|");
                long storedWeek = Long.parseLong(p[0]);
                int storedPoints = Integer.parseInt(p[1]);
                int storedMask = Integer.parseInt(p[2]);
                String[] r = p[3].split(",");
                int[] actual = new int[NCREWS];
                for (int k = 0; k < NCREWS; k++) {
                    actual[k] = Integer.parseInt(r[k]);
                }
                int[] yourDays = new int[7];
                int[][] rivalDays = new int[NCREWS][7];
                readDayGrid(p, storedMask, actual, yourDays, rivalDays, storedPoints);
                if (storedWeek == week) {
                    seasonPoints = storedPoints;
                    seasonMask = storedMask;
                    System.arraycopy(actual, 0, seasonRivalActual, 0, NCREWS);
                    System.arraycopy(yourDays, 0, seasonDayPts, 0, 7);
                    for (int k = 0; k < NCREWS; k++) {
                        System.arraycopy(rivalDays[k], 0, seasonRivalDay[k], 0, 7);
                    }
                    syncRivalTotals();   // the grid is the truth; the totals field follows it
                } else if (storedWeek < week) {
                    settleSeason(storedWeek, storedPoints, storedMask, rivalDays);
                }
            } catch (RuntimeException ignored) {
                // A damaged season starts fresh this week.
            }
        }
        saveSeason();
    }

    /** Fills the day grids from the stored string, or spreads the totals when it predates them. */
    private void readDayGrid(String[] p, int mask, int[] actual, int[] yourDays, int[][] rivalDays,
                             int storedPoints) {
        int days = Math.max(1, Integer.bitCount(mask & 0x7F));
        if (p.length >= 6) {
            String[] y = p[4].split(",");
            String[] g = p[5].split(",");
            if (y.length >= 7 && g.length >= NCREWS * 7) {
                for (int d = 0; d < 7; d++) {
                    yourDays[d] = Integer.parseInt(y[d]);
                }
                for (int k = 0; k < NCREWS; k++) {
                    for (int d = 0; d < 7; d++) {
                        rivalDays[k][d] = Integer.parseInt(g[k * 7 + d]);
                    }
                }
                return;
            }
        }
        for (int d = 0; d < 7; d++) {
            if ((mask & (1 << d)) == 0) {
                continue;
            }
            yourDays[d] = storedPoints / days;
            for (int k = 0; k < NCREWS; k++) {
                rivalDays[k][d] = actual[k] / days;
            }
        }
    }

    /** Rival week totals are the sum of their day grid; kept for the older field in the string. */
    private void syncRivalTotals() {
        for (int k = 0; k < NCREWS; k++) {
            int sum = 0;
            for (int d = 0; d < 7; d++) {
                sum += seasonRivalDay[k][d];
            }
            seasonRivalActual[k] = sum;
        }
    }

    private void saveSeason() {
        syncRivalTotals();
        StringBuilder sb = new StringBuilder();
        sb.append(seasonWeek).append('|').append(seasonPoints).append('|').append(seasonMask).append('|');
        for (int k = 0; k < NCREWS; k++) {
            if (k > 0) {
                sb.append(',');
            }
            sb.append(seasonRivalActual[k]);
        }
        sb.append('|');
        for (int d = 0; d < 7; d++) {
            if (d > 0) {
                sb.append(',');
            }
            sb.append(seasonDayPts[d]);
        }
        sb.append('|');
        for (int k = 0; k < NCREWS; k++) {
            for (int d = 0; d < 7; d++) {
                if (k + d > 0) {
                    sb.append(',');
                }
                sb.append(seasonRivalDay[k][d]);
            }
        }
        bests.putString("regatta.season", sb.toString());
    }

    /** The end of a week: top two up, bottom two down, a title for first. A week not raced holds. */
    private void settleSeason(long week, int you, int mask, int[][] rivalDays) {
        if (mask == 0) {
            seasonNews = "LAST WEEK NOT RACED - STAYING IN " + DIVISIONS[division]
                    + (carriedPoints > 0 ? "  ·  PRE-SEASON +" + carriedPoints : "");
            seasonNewsColor = DIM;
            return;
        }
        int rank = 1;
        for (int k = 0; k < NCREWS; k++) {
            int total = 0;
            for (int d = 0; d < 7; d++) {
                total += (mask & (1 << d)) != 0 ? rivalDays[k][d] : simulatedPoints(week, d, k);
            }
            if (total > you) {
                rank++;
            }
        }
        String title = "";
        if (rank == 1) {
            cabinet[4]++;
            bests.recordHighest("regatta.titles", cabinet[4]);
            title = "SEASON CHAMPIONS  ·  ";
        }
        if (rank <= 2 && division < DIVISIONS.length - 1) {
            division++;
            cabinet[5]++;
            seasonNews = title + "FINISHED " + ordinal(rank) + " - PROMOTED TO " + DIVISIONS[division];
            seasonNewsColor = ACCENT;
        } else if (rank >= 7 && division > 0) {
            division--;
            seasonNews = "FINISHED " + ordinal(rank) + " - RELEGATED TO " + DIVISIONS[division];
            seasonNewsColor = BAD;
        } else {
            seasonNews = title + "LAST WEEK: " + ordinal(rank) + " OF 8 - STAYING IN " + DIVISIONS[division];
            seasonNewsColor = rank == 1 ? ACCENT : WARN;
        }
        if (carriedPoints > 0) {
            seasonNews += "  ·  PRE-SEASON +" + carriedPoints;
        }
        bests.putFloat("regatta.division", division);
        bests.recordHighest("regatta.best", division + 1);
        saveCabinet();
    }

    /**
     * A rival's points on a day you did not race: they race other regattas about three days in five,
     * and the stronger crews place higher. Deterministic, so the ladder never changes behind you.
     */
    private int simulatedPoints(long week, int dow, int crew) {
        java.util.Random r = new java.util.Random(week * 131L + dow * 17L + crew * 7L + division * 1009L);
        if (r.nextDouble() >= 0.6) {
            return 0;
        }
        double u = r.nextDouble() - STRENGTH[crew] * 8;
        int idx = Math.max(0, Math.min(7, (int) (u * 8)));
        return POINTS[idx];
    }

    /** This week's ladder so far. Rivals' points for today count once you have raced today. */
    private void recomputeLadder() {
        int dow = dayOfWeek(today());
        for (int k = 0; k < NCREWS; k++) {
            int total = 0;
            for (int d = 0; d < 7; d++) {
                int pts = (seasonMask & (1 << d)) != 0 ? seasonRivalDay[k][d]
                        : d < dow ? simulatedPoints(seasonWeek, d, k) : 0;
                dayGrid[k][d] = pts;
                total += pts;
            }
            ladderPts[k] = total;
        }
        for (int d = 0; d < 7; d++) {
            dayGrid[NCREWS][d] = (seasonMask & (1 << d)) != 0 ? seasonDayPts[d] : 0;
        }
        ladderPts[NCREWS] = seasonPoints;
        for (int i = 0; i <= NCREWS; i++) {
            ladderOrder[i] = i;
        }
        for (int i = 1; i <= NCREWS; i++) {
            for (int j = i; j > 0 && (ladderPts[ladderOrder[j]] > ladderPts[ladderOrder[j - 1]]
                    || (ladderPts[ladderOrder[j]] == ladderPts[ladderOrder[j - 1]] && ladderOrder[j] == NCREWS)); j--) {
                int t = ladderOrder[j];
                ladderOrder[j] = ladderOrder[j - 1];
                ladderOrder[j - 1] = t;
            }
        }
        // Ties go your way, as at settlement (only a crew strictly ahead outranks you), and the table sorts you above tied crews to match.
        ladderRank = 1;
        for (int k = 0; k < NCREWS; k++) {
            if (ladderPts[k] > seasonPoints) {
                ladderRank++;
            }
        }
    }

    private static String ordinal(int n) {
        return n == 1 ? "1ST" : n == 2 ? "2ND" : n == 3 ? "3RD" : n + "TH";
    }

    /* =====================================================================
     * The grudge
     * ===================================================================== */

    /**
     * Your nemesis: the crew furthest ahead of you head to head, among those whose grudge is still
     * open. With nothing to go on yet it is whoever you have met most, and failing that the crew at
     * the top of the division - there is always someone to chase.
     */
    private void pickNemesis() {
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int k = 0; k < NCREWS; k++) {
            if ((grudgeSettledMask & (1 << k)) != 0) {
                continue;
            }
            int meetings = h2hWins[k] + h2hLosses[k];
            // Deficit first, then the crew you have raced most, then the strongest.
            int score = (h2hLosses[k] - h2hWins[k]) * 100 + meetings * 4 + (NCREWS - k);
            if (score > bestScore) {
                bestScore = score;
                best = k;
            }
        }
        nemesis = best < 0 ? 0 : best;
    }

    /**
     * True while the current nemesis is still a live rivalry. Once every crew has been settled
     * {@link #pickNemesis()} has nobody left and falls back to a crew whose grudge is already won,
     * so the settle path must ask this before banking another lifetime grudge.
     */
    private boolean grudgeOpen() {
        return nemesis >= 0 && (grudgeSettledMask & (1 << nemesis)) == 0;
    }

    /** Where the needle belongs: -1 all theirs, +1 all yours, from the head-to-head. */
    private float grudgeTarget() {
        if (nemesis < 0) {
            return 0f;
        }
        int d = h2hWins[nemesis] - h2hLosses[nemesis];
        return Math.max(-1f, Math.min(1f, d / (float) GRUDGE_TARGET));
    }

    /** After a ranked race the nemesis was in: settle it, reopen it, or let it run on. */
    private void resolveGrudge(double t) {
        if (grudgeLane < 0 || nemesis < 0) {
            return;
        }
        boolean beat = t <= finishTimes[grudgeLane];
        int d = h2hWins[nemesis] - h2hLosses[nemesis];
        grudgeFlashUntil = sessionSeconds + 3.0;
        if (beat && d >= GRUDGE_TARGET && grudgeOpen()) {
            grudgeSettledMask |= 1 << nemesis;
            bests.putFloat("regatta.grudges", bests.get("regatta.grudges", 0f) + 1f);
            grudgeNote = "GRUDGE SETTLED:  " + CREWS[nemesis] + "  " + h2hWins[nemesis] + "-" + h2hLosses[nemesis];
            saveGrudge();
            grudgeLane = -1;   // the meter now belongs to whoever comes next
            pickNemesis();
            grudgeNeedle = grudgeTarget();
        } else {
            if (!beat) {
                // They beat you again, so the rivalry is live whatever was settled before.
                grudgeSettledMask &= ~(1 << nemesis);
                saveGrudge();
            }
            grudgeNote = (beat ? "GRUDGE:  YOU LEAD " : "GRUDGE:  THEY LEAD ")
                    + CREWS[nemesis] + "  " + h2hWins[nemesis] + "-" + h2hLosses[nemesis];
        }
    }

    private void saveGrudge() {
        bests.putString("regatta.settled", String.valueOf(grudgeSettledMask));
    }

    private void loadGrudge() {
        grudgeSettledMask = 0;
        String s = bests.getString("regatta.settled");
        if (s != null) {
            try {
                grudgeSettledMask = Integer.parseInt(s.trim()) & 0x7F;
            } catch (RuntimeException ignored) {
                // A damaged mask just reopens every grudge.
            }
        }
    }

    /* =====================================================================
     * Off-season training
     * ===================================================================== */

    /**
     * Metres rowed outside the ranked races bank toward next week's ladder. A point costs
     * {@value #TRAIN_SECONDS_PER_POINT} seconds of rowing at the rower's own typical speed, so it
     * is the same seven minutes of work whoever is on the machine, and the week's carry is capped
     * at {@link #TRAIN_CAP} - training feeds a season, it never replaces racing it.
     */
    private double trainMetresPerPoint() {
        return Math.max(600.0, profile.typicalSpeed() * TRAIN_SECONDS_PER_POINT);
    }

    private void loadTraining() {
        long week = weekOf(today());
        trainWeek = week;
        trainMetres = 0;
        carriedPoints = 0;
        String s = bests.getString("regatta.training");
        if (s != null) {
            try {
                String[] p = s.split("\\|");
                long storedWeek = Long.parseLong(p[0]);
                double metres = Double.parseDouble(p[1]);
                int storedCarry = p.length > 2 ? Integer.parseInt(p[2]) : 0;
                if (storedWeek == week) {
                    trainMetres = metres;
                    carriedPoints = storedCarry;
                } else {
                    // The week turned over: last week's training becomes this week's head start.
                    carriedPoints = Math.min(TRAIN_CAP, (int) (metres / trainMetresPerPoint()));
                }
            } catch (RuntimeException ignored) {
                // Damaged training just starts the bank again.
            }
        }
        saveTraining();
    }

    private void saveTraining() {
        bests.putString("regatta.training",
                trainWeek + "|" + Math.round(trainMetres) + "|" + carriedPoints);
    }

    /** Called every frame: metres rowed while not inside a ranked race go into the bank. */
    private void accrueTraining() {
        if (lastTrainMark <= 0) {
            lastTrainMark = sessionMeters;
            return;
        }
        double delta = sessionMeters - lastTrainMark;
        lastTrainMark = sessionMeters;
        if (delta <= 0 || (ranked && phase == Phase.RACING)) {
            return;   // a ranked race is the season, not the off-season
        }
        trainMetres += delta;
        if (sessionSeconds > trainSaveAt) {
            trainSaveAt = sessionSeconds + 20.0;
            saveTraining();
        }
    }

    /** Points this week's training will carry into next week, and the metres toward the next one. */
    private int trainingPoints() {
        return Math.min(TRAIN_CAP, (int) (trainMetres / trainMetresPerPoint()));
    }

    /* =====================================================================
     * Input
     * ===================================================================== */

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!driving || boat.value() <= 0.3f) {
            return;
        }
        if (phase == Phase.RESULTS && sessionSeconds - resultsFrom > 6.0) {
            // Rowing on after the heat results starts the final.
            beginFinal();
        }
        if (phase == Phase.READY) {
            overlay = Overlay.NONE;
            phase = Phase.RACING;
            raceStart = sessionSeconds;
            startMeters = sessionMeters;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(e);
        }
        float x = e.getX();
        float y = e.getY();
        if (phase == Phase.CEREMONY) {
            // Skippable from a second in, so a stray tap at the finish cannot swallow it.
            if (sessionSeconds - ceremonyFrom > 1.0) {
                phase = Phase.DONE;
            }
            return true;
        }
        if (phase != Phase.RACING) {
            if (btnLadder.contains(x, y)) {
                overlay = overlay == Overlay.LADDER ? Overlay.NONE : Overlay.LADDER;
                recomputeLadder();
                ladderOpenedAt = overlay == Overlay.LADDER ? sessionSeconds : 0;
                java.util.Arrays.fill(ladderBar, 0f);
                return true;
            }
            if (btnCabinet.contains(x, y)) {
                overlay = overlay == Overlay.CABINET ? Overlay.NONE : Overlay.CABINET;
                return true;
            }
            if (!ranked && !btnGrand.isEmpty() && btnGrand.contains(x, y)) {
                // The grand-final course as an exhibition, so it can be rowed without waiting a week.
                exhibition = !exhibition;
                practiceRun++;
                start();
                return true;
            }
        }
        if (overlay != Overlay.NONE) {
            overlay = Overlay.NONE;
            return true;
        }
        if (phase == Phase.RESULTS) {
            beginFinal();
            return true;
        }
        if (phase == Phase.DONE) {
            practiceRun++;
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    /* =====================================================================
     * Drawing
     * ===================================================================== */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        accrueTraining();
        boolean racing = phase == Phase.RACING;
        boolean ended = phase == Phase.DONE || phase == Phase.RESULTS || phase == Phase.CEREMONY;
        double t = racing || ended ? sessionSeconds - raceStart : 0;
        double you = racing ? sessionMeters - startMeters : 0;
        if (ended) {
            t = finishTime;
            you = RACE_METERS;
        }
        if (racing && you >= RACE_METERS) {
            you = RACE_METERS;
            if (stage == Stage.HEAT) {
                finishHeat(t);
            } else {
                finishFinal(t);
            }
            racing = false;
        }
        if (phase == Phase.READY) {
            t = 0;
            you = 0;
        }
        boolean finalsDay = stage != Stage.HEAT;
        boolean grand = stage == Stage.A_FINAL || stage == Stage.GRAND;

        float waterTop = h * 0.24f;
        float waterBottom = h * 0.86f;
        float lanesTop = finalsDay ? waterTop + dp(40f) : waterTop;
        float ppm = w / 70f;
        float speed = boat.value();
        river.advance(racing ? speed : 0f, dt, ppm);
        float bankTop = waterTop - dp(46f);
        if (skyShader == null || skyHeight != bankTop) {
            skyHeight = bankTop;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, bankTop, 0xFF0B1322, 0xFF2A4E74,
                    android.graphics.Shader.TileMode.CLAMP);
            finalSkyShader = new android.graphics.LinearGradient(0, 0, 0, bankTop, 0xFF1D4F86, 0xFF7FB4DE,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(finalsDay ? finalSkyShader : skyShader);
        c.drawRect(0, 0, w, bankTop, paint);
        paint.setShader(null);
        if (finalsDay) {
            Fx.glow(c, w * 0.82f, bankTop * 0.35f, dp(120f), 0x55FFF1C0);
            // Below the HUD lines, above the bank banners.
            drawBunting(c, w, Math.min(dp(80f), bankTop - dp(70f)), dp(12f), you * ppm * 0.3, 0);
        }
        river.drawWater(c, waterTop, waterBottom, w);
        fx.step(dt, dp(260f));
        float laneH = (waterBottom - lanesTop) / 4f;
        float yourX = w * 0.42f;
        scenery.drawBank(c, w, bankTop, waterTop, you, ppm, sessionSeconds, cheer);
        if (finalsDay) {
            drawBanners(c, w, bankTop, you, ppm, grand);
            drawSpectatorFleet(c, w, waterTop + dp(22f), you, ppm, grand);
        }
        for (int lane = 1; lane < 4; lane++) {
            scenery.drawBuoys(c, w, lanesTop + laneH * lane, you, ppm, sessionSeconds, 10f);
        }
        scenery.drawWaterLife(c, w, lanesTop, waterBottom, you, ppm, sessionSeconds);
        for (int mark = 100; mark < RACE_METERS; mark += 100) {
            float mx = yourX + (float) (mark - you) * ppm;
            if (mx > -dp(40f) && mx < w + dp(40f)) {
                scenery.drawBoard(c, mx, waterTop, (RACE_METERS - mark) + " m", boardText);
            }
        }
        float finishX = yourX + dp(66f) + (float) (RACE_METERS - you) * ppm;
        if (finishX < w + dp(40f)) {
            scenery.drawFinishLine(c, finishX, lanesTop, waterBottom, sessionSeconds);
        }

        // The crews.
        int ahead = 0;
        double d2 = 0;          // the 2nd-furthest crew: the qualifying line in a heat
        double nextAhead = Double.MAX_VALUE;
        double grudgeGap = 0;   // metres you are up on the nemesis, when they are in this race
        for (int i = 0; i < 3; i++) {
            double d = crewDistance(i, t);
            sortScratch[i] = d;
            if (i == grudgeLane) {
                grudgeGap = you - d;
            }
            if (d > you) {
                ahead++;
                nextAhead = Math.min(nextAhead, d);
            }
            boolean isAhead = d > you;
            if (racing && aheadKnown && isAhead != crewAhead[i] && Math.abs(d - you) > 0.3) {
                int k = laneCrew[i];
                callout = isAhead ? CREWS[k] + " GO PAST YOU" : "YOU PASS " + CREWS[k] + "!";
                calloutColor = isAhead ? BAD : ACCENT;
                calloutUntil = sessionSeconds + 1.8;
                if (!isAhead) {
                    fx.burst(yourX, lanesTop + laneH * 3.5f, 26, dp(160f), 0.9f, dp(3f), CREW_COLORS[k], true);
                }
            }
            if (!racing || Math.abs(d - you) > 0.3 || !aheadKnown) {
                crewAhead[i] = isAhead;
            }
            float target = Math.max(dp(40f), Math.min(w - dp(40f), yourX + (float) (d - you) * ppm));
            if (rivalX[i] == 0f) {
                rivalX[i] = target;
            }
            rivalX[i] += (target - rivalX[i]) * Math.min(1f, 6f * dt);
            float ly = lanesTop + laneH * (i + 0.5f);
            int k = laneCrew[i];
            river.setStrokePhase((float) (0.5 + 0.5 * Math.sin(sessionSeconds * (2.4 + k * 0.09) + k * 1.9)));
            river.drawBoat(c, rivalX[i], ly, dp(150f), CREW_COLORS[k],
                    racing ? (float) (RACE_METERS / finishTimes[i]) : 0f, true);
            float lx = Math.max(dp(90f), Math.min(w - dp(90f), rivalX[i]));
            bold(c, laneName[i], lx, ly - dp(40f), 11f, CREW_COLORS[k], Paint.Align.CENTER);
            if (racing) {
                label(c, String.format(java.util.Locale.US, "%s  %+.0f m", PLAN_NAMES[CREW_PLAN[k]], d - you),
                        lx, ly - dp(27f), 9.5f, DIM, Paint.Align.CENTER);
            }
        }
        if (racing) {
            aheadKnown = true;
        }
        // Middle of three sorted distances = the crew in 2nd among them.
        double a0 = sortScratch[0], a1 = sortScratch[1], a2 = sortScratch[2];
        d2 = Math.max(Math.min(a0, a1), Math.min(Math.max(a0, a1), a2));

        float cheerTarget = phase == Phase.CEREMONY ? 1f
                : phase == Phase.DONE ? (placing == 1 ? 1f : 0.3f)
                : racing ? (ahead == 0 ? 1f : ahead == 1 ? 0.6f : 0.15f) : finalsDay ? 0.35f : 0f;
        cheer += (cheerTarget - cheer) * Math.min(1f, 2f * dt);
        float yourY = lanesTop + laneH * 3.5f;
        if (phase != Phase.READY && ahead == 0) {
            Fx.glow(c, yourX, yourY, dp(90f), 0x44F5C518);
        }
        river.bowSpray(yourX + dp(54f), yourY, speed, dt);
        river.setStrokePhase(strokePhase());
        river.drawBoat(c, yourX, yourY, dp(156f), ACCENT, speed, false);
        river.drawSpray(c);
        bold(c, "YOU", yourX, yourY + dp(48f), 11f, ACCENT, Paint.Align.CENTER);

        if (sessionSeconds < confettiUntil && Math.random() < 0.7) {
            fx.spawn((float) Math.random() * w, waterTop - dp(40f), (float) (Math.random() - 0.5) * dp(80f),
                    dp(20f), 2.2f, dp(3f), PARTY[(int) (Math.random() * PARTY.length)], true);
        }
        if (sessionSeconds < fireworksUntil && sessionSeconds > nextFirework) {
            nextFirework = sessionSeconds + 0.35 + Math.random() * 0.3;
            fx.burst(w * (0.1f + (float) Math.random() * 0.8f), bankTop * (0.2f + (float) Math.random() * 0.5f),
                    34, dp(190f), 1.1f, dp(2.6f), PARTY[(int) (Math.random() * PARTY.length)], true);
        }
        fx.draw(c);
        if (sessionSeconds < calloutUntil && !callout.isEmpty()) {
            bold(c, callout, w / 2f, lanesTop + (waterBottom - lanesTop) * 0.5f, 30f, calloutColor, Paint.Align.CENTER);
        }

        drawHud(c, w, h, waterTop, t, you, ahead, d2, nextAhead);
        drawGrudge(c, w, h, dt, grudgeGap, racing);
        drawTraining(c, w, h);
        if (phase == Phase.RESULTS) {
            drawHeatResults(c, w, h);
        }
        if (phase != Phase.RACING && phase != Phase.CEREMONY) {
            drawButtons(c, w);
        }
        if (phase == Phase.CEREMONY) {
            drawCeremony(c, w, h, dt);
        }
        if (overlay == Overlay.LADDER) {
            drawLadder(c, w, h);
        } else if (overlay == Overlay.CABINET) {
            drawCabinet(c, w, h);
        }
    }

    /** Distance a crew has covered: speed = base x (1 + shape x (0.5 - t/T)), integrating to exactly D. */
    private double crewDistance(int i, double t) {
        double T = finishTimes[i];
        double base = RACE_METERS / T;
        double tt = Math.min(t, T);
        return base * (tt + SHAPE[CREW_PLAN[laneCrew[i]]] * (0.5 * tt - tt * tt / (2 * T)));
    }

    private String stageName() {
        return stage == Stage.HEAT ? "HEAT" : stage == Stage.A_FINAL ? "A FINAL"
                : stage == Stage.GRAND ? (ranked ? "GRAND FINAL" : "GRAND FINAL (EXHIBITION)") : "B FINAL";
    }

    private void drawHud(Canvas c, float w, float h, float waterTop, double t, double you, int ahead,
                         double d2, double nextAhead) {
        boolean gold = stage == Stage.A_FINAL || stage == Stage.GRAND;
        bold(c, DIVISIONS[division] + "  ·  " + stageName(), dp(18f), dp(34f), 20f,
                stage == Stage.GRAND ? 0xFFFF8A4C : gold ? 0xFFF5C518 : ACCENT, Paint.Align.LEFT);
        String sub = (ranked ? "TODAY'S RANKED REGATTA" : "PRACTICE") + "  ·  " + RACE_METERS + " m  ·  "
                + (stage == Stage.GRAND ? "24 / 18 / 14 / 10 season points"
                : stage == Stage.HEAT ? "top two reach the A final" : stage == Stage.A_FINAL
                ? "12 / 9 / 7 / 5 season points" : "4 / 3 / 2 / 1 season points");
        label(c, sub, dp(18f), dp(52f), 10f, FAINT, Paint.Align.LEFT);
        String week = "WEEK: " + ordinal(ladderRank) + " OF 8  ·  " + seasonPoints + " PTS  ·  "
                + (6 - dayOfWeek(today())) + " DAYS LEFT";
        label(c, week, dp(18f), dp(68f), 10f, ladderRank <= 2 ? ACCENT : ladderRank >= 7 ? BAD : DIM, Paint.Align.LEFT);
        drawQualifyStrip(c, dp(18f), dp(80f));

        String big;
        int col;
        int pos = phase == Phase.DONE || phase == Phase.CEREMONY ? placing : ahead + 1;
        if (phase == Phase.READY) {
            big = "TAKE A STROKE";
            col = DIM;
        } else if (phase == Phase.RESULTS) {
            big = ordinal(heatPlacing);
            col = heatPlacing <= 2 ? ACCENT : WARN;
        } else {
            big = ordinal(pos);
            col = pos == 1 ? ACCENT : pos == 4 ? BAD : WARN;
        }
        bold(c, big, w * 0.5f, dp(44f), 38f, col, Paint.Align.CENTER);

        // What is at stake right now.
        String stake = "";
        int stakeCol = TEXT;
        if (phase == Phase.RACING) {
            if (stage == Stage.HEAT) {
                if (you >= d2) {
                    stake = String.format(java.util.Locale.US, "QUALIFYING  ·  %.0f m clear of 3rd", you - d2);
                    stakeCol = ACCENT;
                } else {
                    stake = String.format(java.util.Locale.US, "OUT  ·  %.0f m to a qualifying place", d2 - you);
                    stakeCol = BAD;
                }
            } else if (stage == Stage.GRAND) {
                if (ahead == 0) {
                    stake = "HOLD 1ST  ·  " + GRAND_POINTS[0] + " PTS AND THE SEASON";
                    stakeCol = 0xFFFF8A4C;
                } else {
                    stake = String.format(java.util.Locale.US, "%.0f m to %s  ·  %d PTS", nextAhead - you,
                            ordinal(ahead), GRAND_POINTS[ahead - 1]);
                    stakeCol = WARN;
                }
            } else {
                int base = stage == Stage.A_FINAL ? 0 : 4;
                if (ahead == 0) {
                    stake = "HOLD 1ST  ·  " + POINTS[base] + " PTS";
                    stakeCol = ACCENT;
                } else {
                    stake = String.format(java.util.Locale.US, "%.0f m to %s  ·  %d PTS", nextAhead - you,
                            ordinal(ahead), POINTS[base + ahead - 1]);
                    stakeCol = WARN;
                }
            }
        } else if (phase == Phase.READY && !seasonNews.isEmpty() && stage == Stage.HEAT) {
            stake = seasonNews;
            stakeCol = seasonNewsColor;
        } else if (phase == Phase.READY && stage == Stage.GRAND) {
            stake = "THE TOP THREE: " + CREWS[laneCrew[0]] + ", " + CREWS[laneCrew[1]] + ", " + CREWS[laneCrew[2]];
            stakeCol = 0xFFFF8A4C;
        } else if (phase == Phase.READY) {
            stake = stage == Stage.HEAT ? "HEAT: " + CREWS[laneCrew[0]] + ", " + CREWS[laneCrew[1]] + ", " + CREWS[laneCrew[2]]
                    : stageName() + ": row to start" + (outcome.isEmpty() ? "" : "  ·  " + outcome);
            stakeCol = DIM;
        }
        bold(c, stake, w * 0.5f, dp(66f), 13f, stakeCol, Paint.Align.CENTER);

        String foot = phase == Phase.DONE ? clock(finishTime) + "  ·  " + outcome + "  ·  tap to race again"
                : phase == Phase.RACING ? clock(t) + "  ·  " + Math.round(you) + " of " + RACE_METERS + " m" : "";
        bold(c, foot, w * 0.5f, h - dp(14f), 12f, phase == Phase.DONE ? col : TEXT, Paint.Align.CENTER);
        if (phase == Phase.DONE && !outcome2.isEmpty()) {
            bold(c, outcome2, w * 0.5f, h - dp(32f), 12f, ladderRank <= 2 ? ACCENT : BAD, Paint.Align.CENTER);
        }
        float pct = (float) Math.min(1, you / RACE_METERS);
        paint.setColor(ACCENT);
        c.drawRect(0, waterTop - dp(3f), w * pct, waterTop, paint);
    }

    private void drawButtons(Canvas c, float w) {
        float bw = dp(96f);
        float bh = dp(30f);
        float top = dp(14f);
        btnCabinet.set(w - dp(16f) - bw, top, w - dp(16f), top + bh);
        btnLadder.set(btnCabinet.left - dp(10f) - bw, top, btnCabinet.left - dp(10f), top + bh);
        drawPill(c, btnLadder, "LADDER", overlay == Overlay.LADDER);
        drawPill(c, btnCabinet, "TROPHIES", overlay == Overlay.CABINET);
        if (!ranked) {
            // Once the day's ranked regatta is rowed, the grand-final course is open as a practice.
            float gw = dp(140f);
            btnGrand.set(btnLadder.left - dp(10f) - gw, top, btnLadder.left - dp(10f), top + bh);
            drawPill(c, btnGrand, exhibition ? "EXHIBITION ON" : "GRAND FINAL", exhibition);
        } else {
            btnGrand.setEmpty();
        }
    }

    /**
     * The week's seven days as pips: filled for a day raced, and a ring on Sunday once the
     * {@value #QUALIFY_DAYS} qualifying days are in and the grand final is on.
     */
    private void drawQualifyStrip(Canvas c, float x, float y) {
        float pip = dp(11f);
        float gap = dp(5f);
        int dow = dayOfWeek(today());
        for (int d = 0; d < 7; d++) {
            float px = x + d * (pip + gap);
            boolean raced = (seasonMask & (1 << d)) != 0;
            boolean isToday = d == dow;
            boolean sunday = d == 6;
            rect.set(px, y, px + pip, y + pip);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(raced ? ACCENT : isToday ? 0x552F4055 : 0x33202B3A);
            c.drawRoundRect(rect, dp(3f), dp(3f), paint);
            if (raced) {
                // The points won that day, so the strip doubles as your own results row.
                bold(c, num(seasonDayPts[d]), px + pip / 2f, y + pip - dp(2.5f), 7.5f,
                        0xFF0A0E14, Paint.Align.CENTER);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(sunday && qualified ? 0xFFFF8A4C : isToday ? ACCENT : 0x445D6B80);
            c.drawRoundRect(rect, dp(3f), dp(3f), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float tx = x + 7 * (pip + gap) + dp(8f);
        String text;
        int col;
        if (grandDay) {
            text = ranked ? "QUALIFIED  ·  THIS IS THE GRAND FINAL"
                    : "QUALIFIED  ·  TODAY'S GRAND FINAL IS ROWED";
            col = 0xFFFF8A4C;
        } else if (dow == 6) {
            // Sunday with no grand final: either the days never came in, or the third one only
            // landed today, which is too late. Saying "qualified" here would be a lie either way.
            text = "NO GRAND FINAL THIS WEEK  ·  " + daysRaced + " of " + QUALIFY_DAYS + " days by Sunday";
            col = BAD;
        } else if (qualified) {
            text = "QUALIFIED  ·  SUNDAY IS THE GRAND FINAL";
            col = 0xFFFF8A4C;
        } else {
            int need = QUALIFY_DAYS - daysRaced;
            text = "QUALIFYING  ·  " + daysRaced + " of " + QUALIFY_DAYS + " days  ·  " + need
                    + (need == 1 ? " more day" : " more days") + " for the grand final";
            col = WARN;
        }
        label(c, text, tx, y + pip - dp(1.5f), 10f, col, Paint.Align.LEFT);
    }

    /**
     * The grudge meter: the nemesis's colours against yours, a needle sitting on the head-to-head
     * and leaning live with the metres between you whenever they are in this race.
     */
    private void drawGrudge(Canvas c, float w, float h, float dt, double gap, boolean racing) {
        if (nemesis < 0 || phase == Phase.CEREMONY) {
            return;
        }
        // Full swing at about three seconds of the rower's own boat speed, so a stroke moves it.
        double swing = Math.max(4.0, profile.typicalSpeed() * 3.0);
        float live = grudgeLane >= 0 ? (float) Math.max(-1, Math.min(1, gap / swing)) : 0f;
        grudgeLive += (live - grudgeLive) * Math.min(1f, 5f * dt);
        float target = grudgeLane >= 0 && racing
                ? grudgeTarget() * 0.55f + grudgeLive * 0.45f : grudgeTarget();
        grudgeNeedle += (target - grudgeNeedle) * Math.min(1f, 4f * dt);

        float bw = dp(330f);
        float bh = dp(16f);
        float x0 = dp(18f);
        float y0 = h - dp(74f);
        int col = CREW_COLORS[nemesis];
        boolean flash = sessionSeconds < grudgeFlashUntil;
        float pulse = flash ? 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 9) : 0f;

        bold(c, "GRUDGE  ·  " + CREWS[nemesis], x0, y0 - dp(6f), 11f,
                flash && pulse > 0.5f ? 0xFFFFFFFF : col, Paint.Align.LEFT);
        label(c, "you " + h2hWins[nemesis] + " - " + h2hLosses[nemesis] + " them"
                        + (grudgeLane >= 0 ? String.format(java.util.Locale.US, "   ·   %+.0f m now", gap) : ""),
                x0 + bw, y0 - dp(6f), 10f, grudgeLane >= 0 ? TEXT : DIM, Paint.Align.RIGHT);

        barRect.set(x0, y0, x0 + bw, y0 + bh);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC16202E);
        c.drawRoundRect(barRect, bh / 2f, bh / 2f, paint);
        // Their half in their colour, yours in the boat's teal, meeting at the needle.
        float mid = x0 + bw / 2f;
        float nx = mid + grudgeNeedle * (bw / 2f - dp(10f));
        paint.setColor((col & 0x00FFFFFF) | 0x66000000);
        c.drawRect(x0 + dp(2f), y0 + dp(3f), Math.max(x0 + dp(2f), nx), y0 + bh - dp(3f), paint);
        paint.setColor(0x6635D0BA);
        c.drawRect(Math.min(x0 + bw - dp(2f), nx), y0 + dp(3f), x0 + bw - dp(2f), y0 + bh - dp(3f), paint);
        // The target marks: settle the grudge by leading them by GRUDGE_TARGET.
        paint.setColor(0x66FFFFFF);
        c.drawRect(mid - dp(0.6f), y0 + dp(1f), mid + dp(0.6f), y0 + bh - dp(1f), paint);
        paint.setColor(0x88F5C518);
        float winX = mid + (bw / 2f - dp(10f));
        c.drawRect(winX - dp(1.5f), y0, winX + dp(1.5f), y0 + bh, paint);
        // The needle itself.
        if (flash) {
            Fx.glow(c, nx, y0 + bh / 2f, dp(34f), (0x40 + (int) (pulse * 0x60)) << 24 | 0x00F5C518);
        }
        paint.setColor(grudgeNeedle >= 0 ? ACCENT : col);
        path.rewind();
        path.moveTo(nx, y0 - dp(4f));
        path.lineTo(nx + dp(6f), y0 + bh / 2f);
        path.lineTo(nx, y0 + bh + dp(4f));
        path.lineTo(nx - dp(6f), y0 + bh / 2f);
        path.close();
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.4f));
        paint.setColor(0x66FFFFFF);
        c.drawRoundRect(barRect, bh / 2f, bh / 2f, paint);
        paint.setStyle(Paint.Style.FILL);

        String note = !grudgeNote.isEmpty() && sessionSeconds < grudgeFlashUntil ? grudgeNote
                : grudgeLane >= 0 ? "THEY ARE IN THIS RACE - BEAT THEM"
                : !grudgeOpen() ? "every grudge in this division is settled"
                : "lead them by " + GRUDGE_TARGET + " to settle it";
        label(c, note, x0, y0 + bh + dp(14f), 10f,
                !grudgeNote.isEmpty() && flash ? 0xFFF5C518 : grudgeLane >= 0 ? col : FAINT, Paint.Align.LEFT);
    }

    /** Off-season training: what this week's extra metres will be worth on next week's ladder. */
    private void drawTraining(Canvas c, float w, float h) {
        float per = (float) trainMetresPerPoint();
        int pts = trainingPoints();
        float frac = pts >= TRAIN_CAP ? 1f : (float) ((trainMetres % per) / per);
        float bw = dp(250f);
        float x1 = w - dp(18f);
        float x0 = x1 - bw;
        float y0 = h - dp(74f);
        float bh = dp(10f);
        bold(c, "OFF-SEASON TRAINING", x0, y0 - dp(6f), 11f, BLUE, Paint.Align.LEFT);
        label(c, pts > 0 ? "+" + pts + " PTS NEXT WEEK" : "keep rowing", x1, y0 - dp(6f), 10f,
                pts > 0 ? ACCENT : DIM, Paint.Align.RIGHT);
        barRect.set(x0, y0, x1, y0 + bh);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC16202E);
        c.drawRoundRect(barRect, bh / 2f, bh / 2f, paint);
        paint.setColor(pts >= TRAIN_CAP ? 0xFFF5C518 : BLUE);
        barRect.set(x0, y0, x0 + Math.max(dp(2f), bw * frac), y0 + bh);
        c.drawRoundRect(barRect, bh / 2f, bh / 2f, paint);
        // Ticks for each point banked, so progress is countable at a glance.
        paint.setColor(0x66FFFFFF);
        for (int i = 1; i < TRAIN_CAP; i++) {
            float tx = x0 + bw * (i / (float) TRAIN_CAP);
            c.drawRect(tx - dp(0.5f), y0, tx + dp(0.5f), y0 + bh, paint);
        }
        String text = pts >= TRAIN_CAP
                ? "banked in full  ·  " + Math.round(trainMetres) + " m this week"
                : Math.round(trainMetres) + " m  ·  " + Math.round(per - (trainMetres % per)) + " m to the next point";
        label(c, text + (carriedPoints > 0 ? "   ·   brought in +" + carriedPoints : ""),
                x1, y0 + bh + dp(14f), 10f, FAINT, Paint.Align.RIGHT);
    }

    private void drawPill(Canvas c, RectF r, String text, boolean on) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? ACCENT : 0xCC16202E);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(ACCENT);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, text, r.centerX(), r.centerY() + dp(4.5f), 12f, on ? 0xFF0A0E14 : ACCENT, Paint.Align.CENTER);
    }

    private void panel(Canvas c, float w, float h, float pw, float ph) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB00A0E14);
        c.drawRect(0, 0, w, h, paint);
        rect.set((w - pw) / 2f, (h - ph) / 2f, (w + pw) / 2f, (h + ph) / 2f);
        paint.setColor(0xF2121A26);
        c.drawRoundRect(rect, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0x6635D0BA);
        c.drawRoundRect(rect, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Heat results and the draw for the finals, shown between the two races. */
    private void drawHeatResults(Canvas c, float w, float h) {
        if (overlay != Overlay.NONE) {
            return;
        }
        float pw = Math.min(w - dp(40f), dp(720f));
        float ph = Math.min(h * 0.62f, dp(300f));
        panel(c, w, h, pw, ph);
        float cx = w / 2f;
        float y = rect.top + dp(34f);
        bold(c, "HEAT RESULTS", cx, y, 18f, ACCENT, Paint.Align.CENTER);
        float colL = rect.left + pw * 0.27f;
        float colR = rect.left + pw * 0.73f;
        label(c, "YOUR HEAT", colL, y + dp(28f), 10f, FAINT, Paint.Align.CENTER);
        label(c, "OTHER HEAT", colR, y + dp(28f), 10f, FAINT, Paint.Align.CENTER);
        for (int i = 0; i < 4; i++) {
            float ry = y + dp(54f) + i * dp(26f);
            int k = heatOrder[i];
            boolean q = i < 2;
            String nm = (i + 1) + ".  " + (k < 0 ? "YOU" : CREWS[k]) + (q ? "   Q" : "");
            bold(c, nm, colL, ry, 13f, k < 0 ? ACCENT : q ? TEXT : DIM, Paint.Align.CENTER);
            int o = otherHeat[i];
            bold(c, (i + 1) + ".  " + CREWS[o] + (q ? "   Q" : ""), colR, ry, 13f, q ? TEXT : DIM, Paint.Align.CENTER);
        }
        boolean a = heatPlacing <= 2;
        bold(c, a ? "YOU ROW THE A FINAL - FOR THE PODIUM" : "YOU ROW THE B FINAL - WIN IT FOR 4 PTS",
                cx, rect.bottom - dp(44f), 15f, a ? 0xFFF5C518 : WARN, Paint.Align.CENTER);
        double wait = 6.0 - (sessionSeconds - resultsFrom);
        label(c, wait > 0 ? String.format(java.util.Locale.US, "tap to go to the start  ·  rowing starts it in %.0f s", Math.ceil(wait))
                : "row to start the final, or tap", cx, rect.bottom - dp(20f), 11f, DIM, Paint.Align.CENTER);
    }

    private static final String[] DAY_LETTER = {"M", "T", "W", "T", "F", "S", "S"};

    /**
     * Small counts as ready-made strings. The day grid prints up to 56 numbers a frame while the
     * table is open, and the qualify strip prints seven on every frame of a race; {@code
     * String.valueOf} on each of those is garbage the tablet does not need to collect.
     */
    private static final String[] SMALL_NUM = new String[25];

    static {
        for (int i = 0; i < SMALL_NUM.length; i++) {
            SMALL_NUM[i] = Integer.toString(i);
        }
    }

    private static String num(int n) {
        return n >= 0 && n < SMALL_NUM.length ? SMALL_NUM[n] : Integer.toString(n);
    }

    /**
     * The season points table: every crew's Monday-to-Sunday day grid (which doubles as their form
     * strip), the head-to-head, and a bar that counts up from zero each time the table is opened.
     */
    private void drawLadder(Canvas c, float w, float h) {
        float pw = Math.min(w - dp(40f), dp(900f));
        float ph = Math.min(h - dp(30f), dp(470f));
        panel(c, w, h, pw, ph);
        float cx = w / 2f;
        float y = rect.top + dp(30f);
        int dow = dayOfWeek(today());
        bold(c, DIVISIONS[division] + " DIVISION  ·  SEASON POINTS", cx, y, 17f, ACCENT, Paint.Align.CENTER);
        label(c, "top two promoted, bottom two relegated on Monday  ·  " + (6 - dow) + " days left"
                        + "  ·  " + QUALIFY_DAYS + " race days qualify you for Sunday's grand final",
                cx, y + dp(16f), 10f, FAINT, Paint.Align.CENTER);

        float xRank = rect.left + dp(30f);
        float xName = rect.left + dp(52f);
        float cell = Math.min(dp(24f), pw * 0.022f);
        float xDays = rect.left + pw * 0.40f;
        float xH2h = xDays + cell * 7 + dp(18f);
        float xBar0 = xH2h + dp(72f);
        float xBar1 = rect.right - dp(56f);
        float head = y + dp(34f);
        for (int d = 0; d < 7; d++) {
            label(c, DAY_LETTER[d], xDays + cell * (d + 0.5f), head, 9f,
                    d == dow ? ACCENT : d == 6 && qualified ? 0xFFFF8A4C : FAINT, Paint.Align.CENTER);
        }
        label(c, "H2H", xH2h, head, 9f, FAINT, Paint.Align.LEFT);
        label(c, "POINTS", xBar1 + dp(48f), head, 9f, FAINT, Paint.Align.RIGHT);

        int best = 1;
        for (int i = 0; i <= NCREWS; i++) {
            best = Math.max(best, ladderPts[i]);
        }
        float open = ladderOpenedAt > 0 ? (float) Math.min(1.0, (sessionSeconds - ladderOpenedAt) / 0.7) : 1f;
        float row = Math.min(dp(34f), (rect.bottom - head - dp(48f)) / 8f);
        for (int i = 0; i <= NCREWS; i++) {
            int e = ladderOrder[i];
            float ry = head + dp(10f) + i * row;
            boolean youRow = e == NCREWS;
            int zone = i < 2 ? 0x2235D0BA : i >= 6 ? 0x22F0655D : 0;
            if (zone != 0 || youRow) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(youRow ? 0x4435D0BA : zone);
                c.drawRect(rect.left + dp(16f), ry, rect.right - dp(16f), ry + row - dp(3f), paint);
            }
            float ty = ry + row * 0.66f;
            bold(c, num(i + 1), xRank, ty, 13f, i < 2 ? ACCENT : i >= 6 ? BAD : DIM, Paint.Align.CENTER);
            int tint = youRow ? ACCENT : CREW_COLORS[e];
            if (!youRow) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(tint);
                c.drawCircle(xName + dp(6f), ty - dp(4.5f), dp(5f), paint);
            }
            bold(c, youRow ? "YOU" : CREWS[e], xName + (youRow ? 0 : dp(16f)), ty, 13f,
                    youRow ? ACCENT : e == nemesis ? tint : TEXT, Paint.Align.LEFT);
            if (!youRow && e == nemesis) {
                // Measure with the paint that just drew the name: textPaint still carries the 13dp
                // bold the row was drawn at, where boardText is 11dp and put the tag on top of
                // the longest crew name.
                label(c, "NEMESIS", xName + dp(16f) + textPaint.measureText(CREWS[e]) + dp(14f), ty, 8.5f,
                        tint, Paint.Align.LEFT);
            }
            // The day grid, which is also the form strip: filled where they scored, hollow where not.
            int daysOn = 0;
            for (int d = 0; d < 7; d++) {
                int pts = dayPointsFor(e, d);
                boolean known = d < dow || (seasonMask & (1 << d)) != 0;
                if (pts > 0) {
                    daysOn++;
                }
                float px = xDays + cell * d;
                // barRect, never rect: panel() left the panel's bounds in rect and they are still needed.
                barRect.set(px + dp(1.5f), ry + dp(5f), px + cell - dp(1.5f), ry + row - dp(8f));
                paint.setStyle(Paint.Style.FILL);
                if (!known) {
                    paint.setColor(0x22202B3A);
                } else if (pts <= 0) {
                    paint.setColor(0x33202B3A);
                } else {
                    // Brighter with more points: 1 pt barely shows, a win is solid.
                    int alpha = 0x44 + Math.min(0xBB, pts * 12);
                    paint.setColor((alpha << 24) | (tint & 0x00FFFFFF));
                }
                c.drawRoundRect(barRect, dp(2f), dp(2f), paint);
                if (known && pts > 0) {
                    bold(c, num(pts), barRect.centerX(), barRect.bottom - dp(3f), 8f,
                            pts >= 9 ? 0xFF0A0E14 : TEXT, Paint.Align.CENTER);
                }
            }
            if (youRow) {
                label(c, daysOn + "/" + QUALIFY_DAYS + (qualified ? " QUAL" : " days"), xH2h, ty, 10f,
                        qualified ? 0xFFFF8A4C : WARN, Paint.Align.LEFT);
            } else {
                label(c, h2hWins[e] + "-" + h2hLosses[e], xH2h, ty, 10.5f,
                        h2hWins[e] >= h2hLosses[e] ? DIM : BAD, Paint.Align.LEFT);
            }
            // The bar counts up when the table opens, so the order arrives rather than just sits there.
            float full = (xBar1 - xBar0) * (ladderPts[e] / (float) best);
            ladderBar[e] += (full * open - ladderBar[e]) * 0.25f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x33202B3A);
            barRect.set(xBar0, ty - dp(9f), xBar1, ty - dp(1f));
            c.drawRoundRect(barRect, dp(4f), dp(4f), paint);
            paint.setColor((0xCC << 24) | (tint & 0x00FFFFFF));
            barRect.set(xBar0, ty - dp(9f), xBar0 + Math.max(dp(3f), ladderBar[e]), ty - dp(1f));
            c.drawRoundRect(barRect, dp(4f), dp(4f), paint);
            bold(c, num(ladderPts[e]), xBar1 + dp(48f), ty, 13f, youRow ? ACCENT : TEXT, Paint.Align.RIGHT);
        }
        // What the week still hangs on.
        int promoGap = ladderPts[ladderOrder[1]] - seasonPoints;
        int relGap = seasonPoints - ladderPts[ladderOrder[6]];
        String foot = ladderRank <= 2
                ? "IN THE PROMOTION ZONE  ·  " + Math.max(0, seasonPoints - ladderPts[ladderOrder[2]]) + " pts of cushion"
                : promoGap > 0 ? promoGap + " pts from promotion" : "level on points for promotion";
        if (ladderRank >= 7) {
            foot = "IN THE RELEGATION ZONE  ·  " + Math.max(0, ladderPts[ladderOrder[5]] - seasonPoints)
                    + " pts to safety";
        } else if (relGap >= 0 && ladderRank == 6) {
            foot += "  ·  " + relGap + " pts above the drop";
        }
        bold(c, foot, cx, rect.bottom - dp(24f), 12f,
                ladderRank <= 2 ? ACCENT : ladderRank >= 7 ? BAD : WARN, Paint.Align.CENTER);
        label(c, "off-season training banked: " + Math.round(trainMetres) + " m  ·  +" + trainingPoints()
                        + " pts start next season" + (carriedPoints > 0 ? "  ·  +" + carriedPoints + " brought into this one" : "")
                        + "  ·  tap anywhere to close",
                cx, rect.bottom - dp(8f), 9.5f, FAINT, Paint.Align.CENTER);
    }

    /**
     * Points a crew scored on day {@code d} of this week, read from the grid built by
     * {@link #recomputeLadder()}. It is read every frame while the table is open, so it must not
     * call {@link #simulatedPoints} - that allocates a Random, which has no business in a frame.
     */
    private int dayPointsFor(int e, int d) {
        return dayGrid[e][d];
    }

    private void drawCabinet(Canvas c, float w, float h) {
        float pw = Math.min(w - dp(40f), dp(820f));
        float ph = Math.min(h - dp(40f), dp(400f));
        panel(c, w, h, pw, ph);
        float cx = w / 2f;
        float y = rect.top + dp(32f);
        bold(c, "TROPHY CABINET", cx, y, 18f, 0xFFF5C518, Paint.Align.CENTER);
        // Shelf one: an A-final cup per division.
        float shelf1 = rect.top + ph * 0.46f;
        float shelf2 = rect.top + ph * 0.82f;
        float glint = (float) ((sessionSeconds * 0.25) % 1.0);
        for (int s = 0; s < 2; s++) {
            float sy = s == 0 ? shelf1 : shelf2;
            paint.setColor(0xFF5A3E26);
            c.drawRect(rect.left + dp(24f), sy, rect.right - dp(24f), sy + dp(8f), paint);
            paint.setColor(0x33FFFFFF);
            float gx = rect.left + dp(24f) + (pw - dp(48f)) * glint;
            c.drawRect(gx - dp(20f), sy, gx + dp(20f), sy + dp(3f), paint);
        }
        float slot = (pw - dp(48f)) / DIVISIONS.length;
        for (int d = 0; d < DIVISIONS.length; d++) {
            float x = rect.left + dp(24f) + slot * (d + 0.5f);
            int n = cabinet[6 + d];
            float bob = n > 0 ? (float) Math.sin(sessionSeconds * 2 + d) * dp(1.5f) : 0f;
            if (n > 0) {
                Fx.glow(c, x, shelf1 - dp(34f), dp(44f), 0x44F5C518);
            }
            drawCup(c, x, shelf1, dp(1f) * (0.8f + d * 0.08f), n > 0 ? 0xFFF5C518 : 0x33FFFFFF);
            label(c, DIVISIONS[d], x, shelf1 + dp(24f), 9f, n > 0 ? TEXT : FAINT, Paint.Align.CENTER);
            if (n > 0) {
                bold(c, "x" + n, x, shelf1 - dp(74f) + bob, 12f, 0xFFF5C518, Paint.Align.CENTER);
            }
        }
        // Shelf two: medals, B-final plates, season titles, promotions and grand finals.
        float slot2 = (pw - dp(48f)) / SHELF_NAMES.length;
        for (int i = 0; i < SHELF_NAMES.length; i++) {
            float x = rect.left + dp(24f) + slot2 * (i + 0.5f);
            int n = cabinet[SHELF_SLOT[i]];
            int col = n > 0 ? SHELF_TINT[i] : 0x33FFFFFF;
            float swing = n > 0 ? (float) Math.sin(sessionSeconds * 1.6 + i) * dp(3f) : 0f;
            if (i < 3) {
                paint.setStrokeWidth(dp(3f));
                paint.setColor(n > 0 ? 0xFFB33A3A : 0x22FFFFFF);
                c.drawLine(x - dp(8f), shelf2 - dp(58f), x + swing, shelf2 - dp(26f), paint);
                c.drawLine(x + dp(8f), shelf2 - dp(58f), x + swing, shelf2 - dp(26f), paint);
                paint.setColor(col);
                c.drawCircle(x + swing, shelf2 - dp(16f), dp(13f), paint);
                paint.setColor(0x33000000);
                c.drawCircle(x + swing, shelf2 - dp(16f), dp(8f), paint);
            } else if (i == 3) {
                paint.setColor(col);
                c.drawRoundRect(x - dp(20f), shelf2 - dp(40f), x + dp(20f), shelf2, dp(3f), dp(3f), paint);
                paint.setColor(0x44000000);
                c.drawRect(x - dp(14f), shelf2 - dp(33f), x + dp(14f), shelf2 - dp(7f), paint);
            } else if (i == 4) {
                path.rewind();
                path.moveTo(x - dp(18f), shelf2 - dp(48f));
                path.lineTo(x + dp(18f), shelf2 - dp(48f));
                path.lineTo(x + dp(18f), shelf2 - dp(22f));
                path.lineTo(x, shelf2 - dp(2f));
                path.lineTo(x - dp(18f), shelf2 - dp(22f));
                path.close();
                paint.setColor(col);
                c.drawPath(path, paint);
                paint.setColor(0x55000000);
                c.drawCircle(x, shelf2 - dp(28f), dp(7f), paint);
            } else if (i == 5) {
                // A promotion pennant on a stick, flying.
                paint.setColor(0xFF8D9BB0);
                c.drawRect(x - dp(1.5f), shelf2 - dp(52f), x + dp(1.5f), shelf2, paint);
                path.rewind();
                path.moveTo(x + dp(1.5f), shelf2 - dp(52f));
                path.lineTo(x + dp(30f), shelf2 - dp(44f) + swing);
                path.lineTo(x + dp(1.5f), shelf2 - dp(36f));
                path.close();
                paint.setColor(col);
                c.drawPath(path, paint);
            } else {
                // The grand final: a star on a plinth, turning slowly when it has been won.
                paint.setColor(0xFF6E7A8C);
                c.drawRect(x - dp(14f), shelf2 - dp(8f), x + dp(14f), shelf2, paint);
                float spin = n > 0 ? (float) (sessionSeconds * 0.8) : 0f;
                paint.setColor(col);
                path.rewind();
                for (int v = 0; v < 10; v++) {
                    double ang = spin + v * Math.PI / 5 - Math.PI / 2;
                    float rr = (v & 1) == 0 ? dp(22f) : dp(9f);
                    float sx = x + (float) Math.cos(ang) * rr;
                    float sy = shelf2 - dp(30f) + (float) Math.sin(ang) * rr;
                    if (v == 0) {
                        path.moveTo(sx, sy);
                    } else {
                        path.lineTo(sx, sy);
                    }
                }
                path.close();
                if (n > 0) {
                    Fx.glow(c, x, shelf2 - dp(30f), dp(40f), 0x44FF8A4C);
                }
                c.drawPath(path, paint);
            }
            label(c, SHELF_NAMES[i], x, shelf2 + dp(22f), 9f, n > 0 ? TEXT : FAINT, Paint.Align.CENTER);
            bold(c, num(n), x + dp(26f), shelf2 - dp(46f), 12f, n > 0 ? col : FAINT, Paint.Align.LEFT);
        }
        label(c, "A-final wins by division above  ·  tap anywhere to close", cx, rect.bottom - dp(10f), 9.5f,
                FAINT, Paint.Align.CENTER);
    }

    /* =====================================================================
     * The medal ceremony
     * ===================================================================== */

    private static final String[] MEDAL_NAMES = {"GOLD", "SILVER", "BRONZE"};
    private static final int[] MEDAL_TINT = {0xFFF5C518, 0xFFC9D2DC, 0xFFCD7F32};

    /** Smoothstep from 0 at {@code from} to 1 at {@code to}. */
    private static float ease(double from, double to, double t) {
        double p = (t - from) / (to - from);
        p = p < 0 ? 0 : p > 1 ? 1 : p;
        return (float) (p * p * (3 - 2 * p));
    }

    /**
     * The ceremony: the pontoon goes dark, the podium rises, the three crews walk on and step up,
     * a medal comes down on its ribbon, the flags climb and the sky goes up. About ten seconds,
     * skippable with a tap after the first one.
     */
    private void drawCeremony(Canvas c, float w, float h, float dt) {
        double t = sessionSeconds - ceremonyFrom;
        if (t > 11.0) {
            phase = Phase.DONE;
            return;
        }
        boolean gold = ceremonyMedal == 0;
        float lights = ease(0, 0.6, t);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((int) (0xE2 * lights) << 24);
        c.drawRect(0, 0, w, h, paint);

        float deck = h * 0.80f;
        float cx = w / 2f;

        // Two spotlights sweeping the pontoon from the roof of the boathouse.
        for (int s = 0; s < 2; s++) {
            float sx = s == 0 ? w * 0.18f : w * 0.82f;
            float aim = cx + (float) Math.sin(sessionSeconds * (0.7 + s * 0.23) + s * 2.1) * w * 0.20f;
            path.rewind();
            path.moveTo(sx, 0);
            path.lineTo(aim - dp(120f), deck);
            path.lineTo(aim + dp(120f), deck);
            path.close();
            paint.setColor((int) (0x18 * lights) << 24 | 0x00FFF3D0);
            c.drawPath(path, paint);
        }

        // The stand behind: a crowd on its feet the whole way through.
        drawCeremonyStand(c, w, deck - dp(150f), lights);

        // The pontoon deck.
        paint.setColor(0xFF23303F);
        c.drawRect(0, deck, w, deck + dp(16f), paint);
        paint.setColor(0xFF16202E);
        c.drawRect(0, deck + dp(16f), w, h, paint);

        // Three flagpoles, flags climbing once the medal has landed.
        float raise = ease(5.0, 6.6, t);
        for (int p = 0; p < 3; p++) {
            int place = p == 0 ? 1 : p == 1 ? 0 : 2;     // silver, gold, bronze across
            float px = cx + (place == 0 ? 0 : place == 1 ? -dp(215f) : dp(215f));
            float poleTop = deck - dp(250f);
            paint.setColor(0xFF9AA7B8);
            c.drawRect(px - dp(2f), poleTop, px + dp(2f), deck, paint);
            int who = podium[place];
            int col = who < 0 ? ACCENT : CREW_COLORS[who];
            float height = place == 0 ? 1f : place == 1 ? 0.72f : 0.5f;
            float fy = deck - dp(60f) - (deck - dp(60f) - poleTop) * raise * height;
            float ripple = (float) Math.sin(sessionSeconds * 3 + p) * dp(3f) * raise;
            path.rewind();
            path.moveTo(px + dp(2f), fy);
            path.lineTo(px + dp(56f), fy + dp(4f) + ripple);
            path.lineTo(px + dp(56f), fy + dp(30f) + ripple);
            path.lineTo(px + dp(2f), fy + dp(34f));
            path.close();
            paint.setColor((((int) (0xFF * raise)) << 24) | (col & 0x00FFFFFF));
            c.drawPath(path, paint);
        }

        // The podium: three blocks sliding up out of the deck.
        float rise = ease(0.4, 1.7, t);
        float bw = dp(150f);
        for (int place = 0; place < 3; place++) {
            float px = cx + (place == 0 ? 0 : place == 1 ? -bw - dp(14f) : bw + dp(14f));
            float bh = place == 0 ? dp(104f) : place == 1 ? dp(72f) : dp(52f);
            float top = deck - bh * rise;
            boolean mine = podium[place] < 0;
            paint.setColor(mine ? 0xFF1E4F49 : 0xFF2B3A4D);
            c.drawRect(px - bw / 2f, top, px + bw / 2f, deck, paint);
            paint.setColor(mine ? 0x8835D0BA : 0x33FFFFFF);
            c.drawRect(px - bw / 2f, top, px + bw / 2f, top + dp(5f), paint);
            if (rise > 0.5f) {
                bold(c, num(place + 1), px, deck - bh * rise * 0.45f, 26f,
                        mine ? ACCENT : 0x66FFFFFF, Paint.Align.CENTER);
            }
        }

        // The crews walk on from the wings, then step up onto their block.
        float walk = ease(1.5, 2.8, t);
        float step = ease(2.8, 3.3, t);
        for (int place = 0; place < 3; place++) {
            float px = cx + (place == 0 ? 0 : place == 1 ? -bw - dp(14f) : bw + dp(14f));
            float bh = place == 0 ? dp(104f) : place == 1 ? dp(72f) : dp(52f);
            float fromX = place == 1 ? -dp(120f) : w + dp(120f);
            float x = fromX + (px - fromX) * walk;
            float feet = deck - bh * rise * step;
            // A little bounce as they come on, and a hop onto the block.
            float bounce = walk < 1f ? Math.abs((float) Math.sin(t * 9)) * dp(6f) : 0f;
            int who = podium[place];
            boolean mine = who < 0;
            drawCeremonyRower(c, x, feet - bounce, mine ? 1.12f : 1f, mine ? ACCENT : CREW_COLORS[who],
                    place == 0 && t > 3.4);
            if (step > 0.9f) {
                bold(c, mine ? "YOU" : CREWS[who], x, feet - dp(86f), 11f,
                        mine ? ACCENT : CREW_COLORS[who], Paint.Align.CENTER);
            }
        }

        // The medal comes down on its ribbon and settles round your neck.
        if (ceremonyMedal >= 0) {
            int myPlace = 0;
            for (int p = 0; p < 3; p++) {
                if (podium[p] < 0) {
                    myPlace = p;
                }
            }
            float mx = cx + (myPlace == 0 ? 0 : myPlace == 1 ? -bw - dp(14f) : bw + dp(14f));
            float mbh = myPlace == 0 ? dp(104f) : myPlace == 1 ? dp(72f) : dp(52f);
            float neck = deck - mbh * rise - dp(54f);
            float drop = ease(3.4, 4.8, t);
            float my = -dp(40f) + (neck + dp(40f)) * drop;
            int tint = MEDAL_TINT[Math.min(2, ceremonyMedal)];
            paint.setColor(0xFFB33A3A);
            paint.setStrokeWidth(dp(3f));
            paint.setStyle(Paint.Style.STROKE);
            c.drawLine(mx - dp(9f), my - dp(26f), mx, my, paint);
            c.drawLine(mx + dp(9f), my - dp(26f), mx, my, paint);
            paint.setStyle(Paint.Style.FILL);
            float glint = 0.6f + 0.4f * (float) Math.sin(sessionSeconds * 4);
            Fx.glow(c, mx, my, dp(46f), ((int) (0x50 * glint) << 24) | (tint & 0x00FFFFFF));
            paint.setColor(tint);
            c.drawCircle(mx, my, dp(15f), paint);
            paint.setColor(0x44000000);
            c.drawCircle(mx, my, dp(9f), paint);
            if (drop >= 1f && !ceremonyBurst) {
                ceremonyBurst = true;
                fx.burst(mx, my, 44, dp(230f), 1.3f, dp(3f), tint, true);
            }
        }

        // Fireworks over the water for a win, confetti for anything else.
        if (t > 4.8 && sessionSeconds > ceremonyFirework) {
            ceremonyFirework = sessionSeconds + (gold ? 0.35 : 0.9) + Math.random() * 0.3;
            fx.burst(w * (0.1f + (float) Math.random() * 0.8f), h * (0.1f + (float) Math.random() * 0.3f),
                    gold ? 40 : 22, dp(210f), 1.2f, dp(2.6f), PARTY[(int) (Math.random() * PARTY.length)], true);
        }
        fx.draw(c);   // again, because the scrim above was drawn over the pass in render()

        // The words, last so nothing draws over them.
        float words = ease(4.9, 5.6, t);
        if (words > 0.01f) {
            int alpha = (int) (0xFF * words) << 24;
            String head = ceremonyMedal >= 0 ? MEDAL_NAMES[ceremonyMedal] + " MEDAL"
                    : stage == Stage.B_FINAL ? "B FINAL PRESENTATION" : "PRESENTATION";
            bold(c, head, cx, h * 0.14f, 34f,
                    alpha | ((ceremonyMedal >= 0 ? MEDAL_TINT[ceremonyMedal] : TEXT) & 0x00FFFFFF), Paint.Align.CENTER);
            bold(c, DIVISIONS[division] + "  ·  " + stageName() + "  ·  " + clock(finishTime), cx, h * 0.19f, 14f,
                    alpha | (TEXT & 0x00FFFFFF), Paint.Align.CENTER);
            if (!outcome.isEmpty()) {
                bold(c, outcome, cx, h * 0.235f, 13f, alpha | (ACCENT & 0x00FFFFFF), Paint.Align.CENTER);
            }
            if (!grudgeNote.isEmpty()) {
                bold(c, grudgeNote, cx, h * 0.28f, 12f, alpha | (0xFFF5C518 & 0x00FFFFFF), Paint.Align.CENTER);
            }
        }
        if (t > 6.5) {
            label(c, "tap to continue", cx, h - dp(16f), 11f, DIM, Paint.Align.CENTER);
        }
    }

    /** A standing crowd behind the podium, on its feet from the first second. */
    private void drawCeremonyStand(Canvas c, float w, float baseY, float lights) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((int) (0xFF * lights) << 24 | 0x00101A26);
        c.drawRect(0, baseY - dp(10f), w, baseY + dp(150f), paint);
        float gap = dp(19f);
        for (int row = 0; row < 3; row++) {
            float y = baseY + row * dp(16f);
            for (float x = gap * 0.5f + (row & 1) * gap * 0.5f; x < w; x += gap) {
                int k = (int) (x / gap) + row * 31;
                float jump = (float) Math.abs(Math.sin(sessionSeconds * 6.5 + k * 1.3)) * dp(5f);
                paint.setColor((int) (0xFF * lights) << 24 | (PARTY[k % PARTY.length] & 0x00FFFFFF));
                c.drawRect(x - dp(4f), y - dp(13f) - jump, x + dp(4f), y - jump, paint);
                paint.setColor((int) (0xFF * lights) << 24 | 0x00F1C27D);
                c.drawCircle(x, y - dp(17f) - jump, dp(3.4f), paint);
            }
        }
    }

    /** One rower on the podium: a figure with an oar, arms up when they have won. */
    private void drawCeremonyRower(Canvas c, float x, float feet, float s, int col, boolean armsUp) {
        paint.setStyle(Paint.Style.FILL);
        float lift = armsUp ? (float) Math.abs(Math.sin(sessionSeconds * 2.4)) * dp(3f) * s : 0f;
        float y = feet - lift;
        // Legs.
        paint.setColor(0xFF1B2533);
        c.drawRect(x - dp(9f) * s, y - dp(26f) * s, x - dp(3f) * s, y, paint);
        c.drawRect(x + dp(3f) * s, y - dp(26f) * s, x + dp(9f) * s, y, paint);
        // Body.
        paint.setColor(col);
        c.drawRect(x - dp(12f) * s, y - dp(58f) * s, x + dp(12f) * s, y - dp(24f) * s, paint);
        paint.setColor(0x33000000);
        c.drawRect(x - dp(12f) * s, y - dp(40f) * s, x + dp(12f) * s, y - dp(36f) * s, paint);
        // Arms: raised for the winner, otherwise down at the sides.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4f) * s);
        paint.setColor(0xFFF1C27D);
        if (armsUp) {
            float sway = (float) Math.sin(sessionSeconds * 3.2) * dp(4f) * s;
            c.drawLine(x - dp(10f) * s, y - dp(54f) * s, x - dp(20f) * s + sway, y - dp(84f) * s, paint);
            c.drawLine(x + dp(10f) * s, y - dp(54f) * s, x + dp(20f) * s + sway, y - dp(84f) * s, paint);
        } else {
            c.drawLine(x - dp(11f) * s, y - dp(54f) * s, x - dp(15f) * s, y - dp(30f) * s, paint);
            c.drawLine(x + dp(11f) * s, y - dp(54f) * s, x + dp(15f) * s, y - dp(30f) * s, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        // Head.
        paint.setColor(0xFFF1C27D);
        c.drawCircle(x, y - dp(66f) * s, dp(9f) * s, paint);
        paint.setColor(col);
        c.drawRect(x - dp(9f) * s, y - dp(74f) * s, x + dp(9f) * s, y - dp(70f) * s, paint);
    }

    /** A two-handled cup standing on {@code base}. */
    private void drawCup(Canvas c, float x, float base, float s, int col) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(col);
        c.drawRect(x - dp(14f) * s, base - dp(6f) * s, x + dp(14f) * s, base, paint);
        c.drawRect(x - dp(3f) * s, base - dp(22f) * s, x + dp(3f) * s, base - dp(6f) * s, paint);
        path.rewind();
        path.moveTo(x - dp(20f) * s, base - dp(58f) * s);
        path.lineTo(x + dp(20f) * s, base - dp(58f) * s);
        path.quadTo(x + dp(20f) * s, base - dp(22f) * s, x, base - dp(22f) * s);
        path.quadTo(x - dp(20f) * s, base - dp(22f) * s, x - dp(20f) * s, base - dp(58f) * s);
        path.close();
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3f) * s);
        c.drawCircle(x - dp(22f) * s, base - dp(46f) * s, dp(7f) * s, paint);
        c.drawCircle(x + dp(22f) * s, base - dp(46f) * s, dp(7f) * s, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /* ---------- finals day dressing ---------- */

    /** A string of pennants sagging between poles across the sky, scrolling at {@code scroll} px. */
    private void drawBunting(Canvas c, float w, float y0, float sag, double scroll, int colorShift) {
        float span = dp(340f);
        float off = (float) (scroll % span);
        float step = dp(22f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        for (float sx = -off - span; sx < w + span; sx += span) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xAAFFFFFF);
            path.rewind();
            path.moveTo(sx, y0);
            path.quadTo(sx + span / 2f, y0 + sag * 2f, sx + span, y0);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            int k = (int) Math.floor((sx + scroll) / span + 0.5);
            int n = 0;
            for (float px = sx + step / 2f; px < sx + span - step / 4f; px += step, n++) {
                float f = (px - sx) / span;
                float py = y0 + sag * 4f * f * (1 - f);
                float flutter = (float) Math.sin(sessionSeconds * 4 + n + k * 3) * dp(2f);
                path.rewind();
                path.moveTo(px - dp(7f), py);
                path.lineTo(px + dp(7f), py);
                path.lineTo(px + flutter, py + dp(15f));
                path.close();
                paint.setColor(PARTY[(((n + colorShift + k) % PARTY.length) + PARTY.length) % PARTY.length]);
                c.drawPath(path, paint);
            }
        }
    }

    /** Cloth banners on poles along the far bank. The A final gets gold ones. */
    private void drawBanners(Canvas c, float w, float bankTop, double you, float ppm, boolean grand) {
        float gap = dp(420f);
        double scroll = you * ppm * 0.55;
        float off = (float) (scroll % gap);
        boolean top = stage == Stage.GRAND;
        String text = top ? "GRAND FINAL" : grand ? "A FINAL" : "B FINAL";
        String text2 = top ? "SEASON DECIDER" : DIVISIONS[division] + " REGATTA";
        for (float x = -off; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5);
            String t = (k & 1) == 0 ? text : text2;
            float half = dp((k & 1) == 0 ? 60f : 86f);
            float bannerTop = bankTop - dp(38f);
            float bottom = bankTop - dp(14f);
            paint.setColor(0xFFDDDDDD);
            c.drawRect(x - half - dp(2f), bannerTop - dp(4f), x - half + dp(1f), bankTop + dp(8f), paint);
            c.drawRect(x + half - dp(1f), bannerTop - dp(4f), x + half + dp(2f), bankTop + dp(8f), paint);
            float billow = (float) Math.sin(sessionSeconds * 2.2 + k) * dp(2f);
            paint.setColor(top ? 0xFFFF8A4C : grand ? 0xFFF5C518 : 0xFF6F8CFF);
            c.drawRect(x - half, bannerTop, x + half, bottom + billow, paint);
            paint.setColor(grand ? 0xFFB33A3A : 0xFFFFFFFF);
            c.drawRect(x - half, bannerTop, x + half, bannerTop + dp(3f), paint);
            bold(c, t, x, bottom - dp(6f) + billow * 0.5f, 12f, grand ? 0xFF1A1A1A : 0xFFFFFFFF, Paint.Align.CENTER);
        }
    }

    /** Spectator boats moored along the far side of the course, crews waving as you pass. */
    private void drawSpectatorFleet(Canvas c, float w, float y, double you, float ppm, boolean grand) {
        float gap = dp(grand ? 150f : 230f);
        double scroll = you * ppm * 0.9;
        float off = (float) (scroll % gap);
        for (float x = -off - gap; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5);
            float jitter = ((k * 7919) & 31) * dp(1.2f);
            drawSpectatorBoat(c, x + jitter, y, k);
        }
    }

    private void drawSpectatorBoat(Canvas c, float x, float y, int k) {
        int kind = ((k % 3) + 3) % 3;
        float bob = (float) Math.sin(sessionSeconds * 1.6 + k) * dp(2f);
        float yy = y + bob;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        c.drawOval(x - dp(38f), y + dp(4f), x + dp(40f), y + dp(10f), paint);
        int hullCol = kind == 0 ? 0xFFF4F4F4 : kind == 1 ? 0xFF24425E : 0xFF8A5A33;
        path.rewind();
        path.moveTo(x - dp(34f), yy - dp(6f));
        path.lineTo(x + dp(40f), yy - dp(6f));
        path.lineTo(x + dp(30f), yy + dp(6f));
        path.lineTo(x - dp(30f), yy + dp(6f));
        path.close();
        paint.setColor(hullCol);
        c.drawPath(path, paint);
        paint.setColor(PARTY[((k % PARTY.length) + PARTY.length) % PARTY.length]);
        c.drawRect(x - dp(33f), yy - dp(3f), x + dp(37f), yy - dp(1f), paint);
        if (kind == 0) {
            paint.setColor(0xFFE8E8E8);
            c.drawRect(x - dp(14f), yy - dp(18f), x + dp(14f), yy - dp(6f), paint);
            paint.setColor(0xFF2A3C50);
            c.drawRect(x - dp(10f), yy - dp(15f), x + dp(10f), yy - dp(11f), paint);
        } else if (kind == 1) {
            paint.setColor(0xFFCCCCCC);
            c.drawRect(x - dp(1f), yy - dp(50f), x + dp(1f), yy - dp(6f), paint);
            path.rewind();
            path.moveTo(x + dp(1.5f), yy - dp(48f));
            path.lineTo(x + dp(1.5f), yy - dp(9f));
            path.lineTo(x + dp(26f) + bob, yy - dp(9f));
            path.close();
            paint.setColor(0xFFF8F4E8);
            c.drawPath(path, paint);
        } else {
            paint.setColor(0xFFF0655D);
            c.drawRect(x - dp(18f), yy - dp(20f), x + dp(18f), yy - dp(17f), paint);
            paint.setColor(0xFFDDDDDD);
            c.drawRect(x - dp(17f), yy - dp(17f), x - dp(16f), yy - dp(6f), paint);
            c.drawRect(x + dp(16f), yy - dp(17f), x + dp(17f), yy - dp(6f), paint);
        }
        // Stern flag.
        paint.setColor(0xFFBBBBBB);
        c.drawRect(x - dp(33f), yy - dp(20f), x - dp(32f), yy - dp(6f), paint);
        path.rewind();
        path.moveTo(x - dp(32f), yy - dp(20f));
        path.lineTo(x - dp(20f), yy - dp(17f) + (float) Math.sin(sessionSeconds * 5 + k) * dp(1.5f));
        path.lineTo(x - dp(32f), yy - dp(14f));
        path.close();
        paint.setColor(CREW_COLORS[((k % NCREWS) + NCREWS) % NCREWS]);
        c.drawPath(path, paint);
        // Spectators, waving harder the more there is to cheer.
        paint.setStrokeWidth(dp(1.6f));
        for (int p = 0; p < 3; p++) {
            float px = x + (kind == 1 ? -dp(22f) : dp(20f)) + p * dp(6f) - (kind == 1 ? 0 : dp(6f));
            float jump = cheer * (float) Math.abs(Math.sin(sessionSeconds * 6 + k + p * 1.3)) * dp(3f);
            float py = yy - dp(9f) - jump;
            paint.setColor(PARTY[(p + k * 2 + PARTY.length * 4) % PARTY.length]);
            c.drawRect(px - dp(2.2f), py - dp(2f), px + dp(2.2f), py + dp(3f), paint);
            paint.setColor(0xFFE8C4A0);
            c.drawCircle(px, py - dp(4.5f), dp(2.4f), paint);
            float wave = (float) Math.sin(sessionSeconds * 8 + p + k) * dp(2.5f) * cheer;
            c.drawLine(px + dp(2f), py - dp(1f), px + dp(4f) + wave, py - dp(8f) * (0.4f + cheer * 0.6f), paint);
        }
    }
}
