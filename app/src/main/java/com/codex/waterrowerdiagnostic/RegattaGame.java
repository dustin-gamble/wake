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

    private enum Phase { READY, RACING, RESULTS, DONE }
    private enum Stage { HEAT, A_FINAL, B_FINAL }
    private enum Overlay { NONE, LADDER, CABINET }

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF btnLadder = new RectF();
    private final RectF btnCabinet = new RectF();
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
    private static final String[] SHELF_NAMES = {"GOLD", "SILVER", "BRONZE", "B FINAL WINS", "SEASON TITLES", "PROMOTIONS"};
    private static final int[] SHELF_TINT = {0xFFF5C518, 0xFFC9D2DC, 0xFFCD7F32, 0xFF6F8CFF, 0xFF35D0BA, 0xFFB48CFF};
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

    /* ---------- records and trophies ---------- */

    private final int[] h2hWins = new int[NCREWS];
    private final int[] h2hLosses = new int[NCREWS];
    /** gold, silver, bronze, B-final wins, season titles, promotions, then A-final wins per division. */
    private final int[] cabinet = new int[6 + 6];

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
        seasonNews = "";
        loadSeason();
        ranked = Math.round(bests.get("regatta.day", -1f)) != today();
        seedBase = today() * 7919L + division * 31L + (ranked ? 0 : 1000L + practiceRun * 97L);
        overlay = Overlay.NONE;
        outcome = "";
        outcome2 = "";
        callout = "";
        calloutUntil = 0;
        confettiUntil = 0;
        fireworksUntil = 0;
        nextFirework = 0;
        if (!seasonNews.isEmpty()) {
            confettiUntil = seasonNewsColor == ACCENT ? 4.0 : 0;
        }
        drawDay();
        if (ranked && resumeFinal()) {
            outcome = "HEAT DONE EARLIER - " + ordinal(heatPlacing) + ". ON TO THE FINAL";
        } else {
            setupRace(Stage.HEAT);
        }
        recomputeLadder();
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
        double speed = profile.typicalSpeed() * FACTOR[division] * (1 + STRENGTH[crew])
                * (0.985 + r.nextDouble() * 0.03) * (s == Stage.A_FINAL ? 1.01 : 1.0);
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
        for (int i = 0; i < 3; i++) {
            finishTimes[i] = crewTime(laneCrew[i], s);
            rivalX[i] = 0f;
            int k = laneCrew[i];
            laneName[i] = CREWS[k] + "  " + h2hWins[k] + "-" + h2hLosses[k];
        }
        if (s != Stage.HEAT) {
            // The crew with the best record against you gets the pre-race line.
            int nemesis = laneCrew[0];
            for (int i = 1; i < 3; i++) {
                int k = laneCrew[i];
                if (h2hLosses[k] - h2hWins[k] > h2hLosses[nemesis] - h2hWins[nemesis]) {
                    nemesis = k;
                }
            }
            if (h2hLosses[nemesis] > 0) {
                callout = CREWS[nemesis] + " HAVE BEATEN YOU " + h2hLosses[nemesis] + "x";
                calloutColor = CREW_COLORS[nemesis];
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
        boolean a = stage == Stage.A_FINAL;
        int dayPlace = (a ? 0 : 4) + placing;   // 1..8
        int pts = POINTS[dayPlace - 1];
        finishTime = t;
        bests.recordLowest("time." + RACE_METERS, (float) t);
        if (ranked) {
            recordHeadToHead(t);
            bests.putFloat("regatta.day", today());
            // Season points for everyone who raced today: you and all seven crews.
            int dow = dayOfWeek(today());
            if ((seasonMask & (1 << dow)) == 0) {
                seasonMask |= 1 << dow;
                seasonPoints += pts;
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
                    seasonRivalActual[laneCrew[i]] += POINTS[(a ? 0 : 4) + place - 1];
                }
                double[] ot = new double[4];
                int[] oc = otherFinal.clone();
                for (int i = 0; i < 4; i++) {
                    ot[i] = crewTime(oc[i], a ? Stage.B_FINAL : Stage.A_FINAL);
                }
                sortByTime(oc, ot);
                for (int i = 0; i < 4; i++) {
                    seasonRivalActual[oc[i]] += POINTS[(a ? 4 : 0) + i];
                }
                saveSeason();
            }
            // The cabinet.
            if (a && placing <= 3) {
                cabinet[placing - 1]++;
            }
            if (a && placing == 1) {
                cabinet[6 + division]++;
                bests.recordHighest("regatta.golds", cabinet[0]);
            }
            if (!a && placing == 1) {
                cabinet[3]++;
            }
            saveCabinet();
            bests.recordHighest("regatta.best", division + 1);
            recomputeLadder();
            outcome = (a ? "A FINAL " : "B FINAL ") + ordinal(placing) + "  ·  +" + pts + " PTS  ·  "
                    + ordinal(ladderRank) + " ON THE WEEK'S LADDER";
            outcome2 = ladderRank <= 2 ? "IN THE PROMOTION ZONE" : ladderRank >= 7 ? "IN THE RELEGATION ZONE" : "";
        } else {
            outcome = "PRACTICE - TODAY'S RANKED REGATTA IS DONE";
            outcome2 = "";
        }
        boolean gold = a && placing == 1;
        confettiUntil = sessionSeconds + (placing == 1 ? 5.0 : 1.2);
        fireworksUntil = gold ? sessionSeconds + 6.0 : 0;
        callout = gold ? "REGATTA CHAMPIONS!" : placing == 1 ? "B FINAL WON" : a && placing <= 3 ? "ON THE PODIUM"
                : placing == 4 ? "LAST PLACE" : "FINISHED";
        calloutColor = placing == 1 ? 0xFFF5C518 : placing == 4 ? BAD : WARN;
        calloutUntil = sessionSeconds + 3.5;
        phase = Phase.DONE;
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

    /** Loads the week's season, first settling any week that has ended since it was last seen. */
    private void loadSeason() {
        long week = weekOf(today());
        seasonWeek = week;
        seasonPoints = 0;
        seasonMask = 0;
        java.util.Arrays.fill(seasonRivalActual, 0);
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
                if (storedWeek == week) {
                    seasonPoints = storedPoints;
                    seasonMask = storedMask;
                    System.arraycopy(actual, 0, seasonRivalActual, 0, NCREWS);
                } else if (storedWeek < week) {
                    settleSeason(storedWeek, storedPoints, storedMask, actual);
                }
            } catch (RuntimeException ignored) {
                // A damaged season starts fresh this week.
            }
        }
        saveSeason();
    }

    private void saveSeason() {
        StringBuilder sb = new StringBuilder();
        sb.append(seasonWeek).append('|').append(seasonPoints).append('|').append(seasonMask).append('|');
        for (int k = 0; k < NCREWS; k++) {
            if (k > 0) {
                sb.append(',');
            }
            sb.append(seasonRivalActual[k]);
        }
        bests.putString("regatta.season", sb.toString());
    }

    /** The end of a week: top two up, bottom two down, a title for first. A week not raced holds. */
    private void settleSeason(long week, int you, int mask, int[] actual) {
        if (mask == 0) {
            seasonNews = "LAST WEEK NOT RACED - STAYING IN " + DIVISIONS[division];
            seasonNewsColor = DIM;
            return;
        }
        int rank = 1;
        for (int k = 0; k < NCREWS; k++) {
            int total = actual[k];
            for (int d = 0; d < 7; d++) {
                if ((mask & (1 << d)) == 0) {
                    total += simulatedPoints(week, d, k);
                }
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
            int total = seasonRivalActual[k];
            for (int d = 0; d < dow; d++) {
                if ((seasonMask & (1 << d)) == 0) {
                    total += simulatedPoints(seasonWeek, d, k);
                }
            }
            ladderPts[k] = total;
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
        if (phase != Phase.RACING) {
            if (btnLadder.contains(x, y)) {
                overlay = overlay == Overlay.LADDER ? Overlay.NONE : Overlay.LADDER;
                recomputeLadder();
                return true;
            }
            if (btnCabinet.contains(x, y)) {
                overlay = overlay == Overlay.CABINET ? Overlay.NONE : Overlay.CABINET;
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
        boolean racing = phase == Phase.RACING;
        double t = racing || phase == Phase.DONE || phase == Phase.RESULTS ? sessionSeconds - raceStart : 0;
        double you = racing ? sessionMeters - startMeters : 0;
        if (phase == Phase.DONE || phase == Phase.RESULTS) {
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
        boolean grand = stage == Stage.A_FINAL;

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
        for (int i = 0; i < 3; i++) {
            double d = crewDistance(i, t);
            sortScratch[i] = d;
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

        float cheerTarget = phase == Phase.DONE ? (placing == 1 ? 1f : 0.3f)
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
        if (phase == Phase.RESULTS) {
            drawHeatResults(c, w, h);
        }
        if (phase != Phase.RACING) {
            drawButtons(c, w);
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
        return stage == Stage.HEAT ? "HEAT" : stage == Stage.A_FINAL ? "A FINAL" : "B FINAL";
    }

    private void drawHud(Canvas c, float w, float h, float waterTop, double t, double you, int ahead,
                         double d2, double nextAhead) {
        bold(c, DIVISIONS[division] + "  ·  " + stageName(), dp(18f), dp(34f), 20f,
                stage == Stage.A_FINAL ? 0xFFF5C518 : ACCENT, Paint.Align.LEFT);
        String sub = (ranked ? "TODAY'S RANKED REGATTA" : "PRACTICE") + "  ·  " + RACE_METERS + " m  ·  "
                + (stage == Stage.HEAT ? "top two reach the A final" : stage == Stage.A_FINAL
                ? "12 / 9 / 7 / 5 season points" : "4 / 3 / 2 / 1 season points");
        label(c, sub, dp(18f), dp(52f), 10f, FAINT, Paint.Align.LEFT);
        String week = "WEEK: " + ordinal(ladderRank) + " OF 8  ·  " + seasonPoints + " PTS  ·  "
                + (6 - dayOfWeek(today())) + " DAYS LEFT";
        label(c, week, dp(18f), dp(68f), 10f, ladderRank <= 2 ? ACCENT : ladderRank >= 7 ? BAD : DIM, Paint.Align.LEFT);

        String big;
        int col;
        int pos = phase == Phase.DONE ? placing : ahead + 1;
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

    private void drawLadder(Canvas c, float w, float h) {
        float pw = Math.min(w - dp(40f), dp(560f));
        float ph = Math.min(h - dp(40f), dp(380f));
        panel(c, w, h, pw, ph);
        float cx = w / 2f;
        float y = rect.top + dp(32f);
        bold(c, DIVISIONS[division] + " DIVISION  ·  THIS WEEK", cx, y, 17f, ACCENT, Paint.Align.CENTER);
        label(c, "top two promoted, bottom two relegated on Monday  ·  " + (6 - dayOfWeek(today())) + " days left",
                cx, y + dp(18f), 10f, FAINT, Paint.Align.CENTER);
        float row = Math.min(dp(32f), (rect.bottom - y - dp(40f)) / 8f);
        for (int i = 0; i <= NCREWS; i++) {
            int e = ladderOrder[i];
            float ry = y + dp(38f) + i * row;
            boolean youRow = e == NCREWS;
            int zone = i < 2 ? 0x2235D0BA : i >= 6 ? 0x22F0655D : 0;
            if (zone != 0 || youRow) {
                paint.setColor(youRow ? 0x4435D0BA : zone);
                c.drawRect(rect.left + dp(16f), ry, rect.right - dp(16f), ry + row - dp(3f), paint);
            }
            float ty = ry + row * 0.66f;
            bold(c, String.valueOf(i + 1), rect.left + dp(34f), ty, 13f, i < 2 ? ACCENT : i >= 6 ? BAD : DIM, Paint.Align.CENTER);
            if (youRow) {
                bold(c, "YOU", rect.left + dp(60f), ty, 13f, ACCENT, Paint.Align.LEFT);
            } else {
                paint.setColor(CREW_COLORS[e]);
                c.drawCircle(rect.left + dp(64f), ty - dp(4.5f), dp(5f), paint);
                bold(c, CREWS[e], rect.left + dp(78f), ty, 13f, TEXT, Paint.Align.LEFT);
                label(c, "you " + h2hWins[e] + "-" + h2hLosses[e], rect.left + pw * 0.62f, ty, 10.5f,
                        h2hWins[e] >= h2hLosses[e] ? DIM : BAD, Paint.Align.LEFT);
            }
            bold(c, ladderPts[e] + " pts", rect.right - dp(28f), ty, 13f, youRow ? ACCENT : TEXT, Paint.Align.RIGHT);
        }
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
        // Shelf two: medals, B-final plates, season titles and promotions.
        for (int i = 0; i < 6; i++) {
            float x = rect.left + dp(24f) + slot * (i + 0.5f);
            int n = cabinet[i];
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
            } else {
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
            }
            label(c, SHELF_NAMES[i], x, shelf2 + dp(22f), 9f, n > 0 ? TEXT : FAINT, Paint.Align.CENTER);
            bold(c, String.valueOf(n), x + dp(26f), shelf2 - dp(46f), 12f, n > 0 ? col : FAINT, Paint.Align.LEFT);
        }
        label(c, "A-final wins by division above  ·  tap anywhere to close", cx, rect.bottom - dp(10f), 9.5f,
                FAINT, Paint.Align.CENTER);
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
        String text = grand ? "A FINAL" : "B FINAL";
        String text2 = DIVISIONS[division] + " REGATTA";
        for (float x = -off; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5);
            String t = (k & 1) == 0 ? text : text2;
            float half = dp((k & 1) == 0 ? 60f : 86f);
            float top = bankTop - dp(38f);
            float bottom = bankTop - dp(14f);
            paint.setColor(0xFFDDDDDD);
            c.drawRect(x - half - dp(2f), top - dp(4f), x - half + dp(1f), bankTop + dp(8f), paint);
            c.drawRect(x + half - dp(1f), top - dp(4f), x + half + dp(2f), bankTop + dp(8f), paint);
            float billow = (float) Math.sin(sessionSeconds * 2.2 + k) * dp(2f);
            paint.setColor(grand ? 0xFFF5C518 : 0xFF6F8CFF);
            c.drawRect(x - half, top, x + half, bottom + billow, paint);
            paint.setColor(grand ? 0xFFB33A3A : 0xFFFFFFFF);
            c.drawRect(x - half, top, x + half, top + dp(3f), paint);
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
