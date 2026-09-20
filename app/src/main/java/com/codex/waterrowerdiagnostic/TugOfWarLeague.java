package com.codex.waterrowerdiagnostic;

/**
 * Everything in Tug of War that outlives a session: the league pyramid with its promotion and
 * relegation, the head-to-head ledger against each team, and the strength rating.
 *
 * <p>Pure state and simulation - it draws nothing and knows nothing about the canvas.
 * {@link TugOfWarGame} owns the drawing and calls in here for names, points and awards.
 *
 * <p>Three divisions of four sides: you and three teams. A season is three matchdays, so each side
 * plays the others once; the winner of a match takes three points. Finish top and you go up, finish
 * bottom and you go down, and the division sets how hard the whole division pulls.
 */
final class TugOfWarLeague {

    static final int DIVISIONS = 3;
    /** Matchdays in a season - a single round robin between four sides. */
    static final int FIXTURES = 3;
    static final String[] DIV_NAMES = {"PREMIER", "DIVISION TWO", "DIVISION THREE"};

    /** Team indexes into {@link TugOfWarGame}'s table, strongest division first. Neighbours overlap. */
    private static final int[][] ROSTERS = {
            {4, 3, 2},   // STORM CREW, IRON OXEN, RIVER RATS
            {2, 6, 1},   // RIVER RATS, HARBOUR SEALS, MILL LADS
            {1, 5, 0},   // MILL LADS, BARN OWLS, DAISY CHAIN
    };
    /**
     * A whole division pulls harder than the one below it. Set against the measured envelope, not
     * by feel: the top division's strongest side is 1.22 of typical watts, so 1.10 puts it at
     * 1.34 x 129 W = 173 W - above this rower's p90 of 162 W and under their 205 W peak, which is
     * where a division you have to fight your way out of belongs. An earlier 1.18 put it at 186 W,
     * a wall rather than a climb.
     */
    private static final float[] DIV_STRENGTH = {1.10f, 1.0f, 0.88f};
    /** Per matchday: who you play, then the two sides who play each other. Table indexes, you are 0. */
    private static final int[][] TIES = {
            {1, 2, 3},
            {2, 1, 3},
            {3, 1, 2},
    };

    static final String STATE_KEY = "tugleague.state";
    static final String RIVALS_KEY = "tugrivals.state";
    static final String RATING_KEY = "tugrating.strength";
    static final String TOP_KEY = "tugleague.top";
    static final String ANCHOR_KEY = "tuganchor.held";

    private static final String[] RANKS = {"ROOKIE", "DECKHAND", "HAULER", "BARGEMAN", "STRONGMAN", "TITAN"};
    private static final float[] RANK_AT = {0f, 150f, 320f, 580f, 950f, 1500f};
    private static final String[] ORDINALS = {"1st", "2nd", "3rd", "4th"};

    private final PersonalBests bests;
    private final java.util.Random rng = new java.util.Random();

    private int division = DIVISIONS - 1;
    private int matchday;
    private int season = 1;
    private final int[] points = new int[4];
    private final int[] played = new int[4];
    private final int[] diff = new int[4];
    private final int[] order = new int[4];
    private float rating = 100f;
    private int anchorsHeld;
    private final int[] rivalWin;
    private final int[] rivalLoss;
    /** Set the moment a season ends, so the table can say what happened; cleared on the next tap. */
    private String seasonMessage;

    TugOfWarLeague(PersonalBests bests) {
        this.bests = bests;
        int n = TugOfWarGame.teamCount();
        rivalWin = new int[n];
        rivalLoss = new int[n];
        load();
    }

    /* ---------- the fixture in front of you ---------- */

    String divisionName() {
        return DIV_NAMES[Math.max(0, Math.min(DIVISIONS - 1, division))];
    }

    int matchday() {
        return matchday;
    }

    int season() {
        return season;
    }

    /** Index into the game's team table for this matchday's opponent. */
    int opponentTeam() {
        return ROSTERS[division][TIES[matchday][0] - 1];
    }

    /** Multiplier on every team's share in this division. */
    float divisionStrength() {
        return DIV_STRENGTH[division];
    }

    /** Table row names: row 0 is you. */
    String rowName(int row) {
        return row == 0 ? "YOU" : TugOfWarGame.teamName(ROSTERS[division][row - 1]);
    }

    int rowTeam(int row) {
        return row == 0 ? -1 : ROSTERS[division][row - 1];
    }

    int points(int row) {
        return points[row];
    }

    int played(int row) {
        return played[row];
    }

    int diff(int row) {
        return diff[row];
    }

    /**
     * Table rows, best first: points, then pull difference, then the stronger side. Written into a
     * field rather than a fresh array, because the table is drawn every frame it is on screen.
     *
     * <p>The sort is deterministic and idempotent, so a caller holding the array while
     * {@link #promotionPlace} recomputes it still sees the same order.
     */
    int[] standings() {
        for (int i = 0; i < 4; i++) {
            order[i] = i;
        }
        for (int i = 1; i < 4; i++) {
            int v = order[i];
            int j = i - 1;
            while (j >= 0 && better(v, order[j])) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
        return order;
    }

    private boolean better(int a, int b) {
        if (points[a] != points[b]) {
            return points[a] > points[b];
        }
        if (diff[a] != diff[b]) {
            return diff[a] > diff[b];
        }
        return strengthOf(a) > strengthOf(b);
    }

    private float strengthOf(int row) {
        return row == 0 ? 1f : TugOfWarGame.teamShare(ROSTERS[division][row - 1]);
    }

    /** Where you sit right now, 0 = top. */
    int yourPlace() {
        int[] o = standings();
        for (int i = 0; i < 4; i++) {
            if (o[i] == 0) {
                return i;
            }
        }
        return 3;
    }

    boolean promotionPlace(int row) {
        return division > 0 && row == standings()[0];
    }

    boolean relegationPlace(int row) {
        return division < DIVISIONS - 1 && row == standings()[3];
    }

    /* ---------- results ---------- */

    /**
     * A match of yours is over. Scores it, plays out the other tie of the matchday, and rolls the
     * season over when the last matchday is done.
     */
    void recordMatch(boolean youWon, int yourPulls, int theirPulls) {
        int opp = TIES[matchday][0];
        played[0]++;
        played[opp]++;
        points[youWon ? 0 : opp] += 3;
        diff[0] += yourPulls - theirPulls;
        diff[opp] += theirPulls - yourPulls;
        noteRival(opponentTeam(), youWon);
        simulateTie(TIES[matchday][1], TIES[matchday][2]);
        matchday++;
        if (matchday >= FIXTURES) {
            endSeason();
        }
        save();
    }

    /** A match you are not in: the stronger side usually wins, 2-0 or 2-1. */
    private void simulateTie(int a, int b) {
        float sa = strengthOf(a);
        float sb = strengthOf(b);
        boolean aWins = rng.nextFloat() < sa / (sa + sb);
        int win = aWins ? a : b;
        int lose = aWins ? b : a;
        int margin = rng.nextFloat() < 0.55f ? 2 : 1;
        points[win] += 3;
        played[win]++;
        played[lose]++;
        diff[win] += margin;
        diff[lose] -= margin;
    }

    private void endSeason() {
        int place = yourPlace();
        if (place == 0 && division > 0) {
            division--;
            seasonMessage = "WON THE DIVISION - PROMOTED TO " + divisionName();
            award(30f);
        } else if (place == 3 && division < DIVISIONS - 1) {
            division++;
            seasonMessage = "BOTTOM OF THE TABLE - RELEGATED TO " + divisionName();
        } else if (place == 0) {
            seasonMessage = "CHAMPIONS OF THE " + divisionName() + " - NOWHERE HIGHER TO GO";
            award(30f);
        } else {
            seasonMessage = "FINISHED " + ORDINALS[place] + " IN THE " + divisionName();
        }
        bests.recordHighest(TOP_KEY, DIVISIONS - division);
        season++;
        matchday = 0;
        for (int i = 0; i < 4; i++) {
            points[i] = 0;
            played[i] = 0;
            diff[i] = 0;
        }
    }

    String seasonMessage() {
        return seasonMessage;
    }

    void clearSeasonMessage() {
        seasonMessage = null;
    }

    /* ---------- the rivalry ledger ---------- */

    void noteRival(int team, boolean youWon) {
        if (team < 0 || team >= rivalWin.length) {
            return;
        }
        if (youWon) {
            rivalWin[team]++;
        } else {
            rivalLoss[team]++;
        }
    }

    int rivalWins(int team) {
        return team >= 0 && team < rivalWin.length ? rivalWin[team] : 0;
    }

    int rivalLosses(int team) {
        return team >= 0 && team < rivalLoss.length ? rivalLoss[team] : 0;
    }

    /** A side that has beaten you more than you have beaten them has earned the right to gloat. */
    boolean nemesis(int team) {
        return rivalLosses(team) >= rivalWins(team) + 2;
    }

    /* ---------- the strength rating ---------- */

    float rating() {
        return rating;
    }

    void award(float v) {
        if (v <= 0) {
            return;
        }
        rating += v;
        bests.putFloat(RATING_KEY, rating);
    }

    int rankIndex() {
        int idx = 0;
        for (int i = 0; i < RANK_AT.length; i++) {
            if (rating >= RANK_AT[i]) {
                idx = i;
            }
        }
        return idx;
    }

    String rank() {
        return RANKS[rankIndex()];
    }

    String nextRank() {
        int i = rankIndex();
        return i + 1 < RANKS.length ? RANKS[i + 1] : null;
    }

    /** How far through the current rank, 0..1; 1 at the top rank. */
    float rankProgress() {
        int i = rankIndex();
        if (i + 1 >= RANK_AT.length) {
            return 1f;
        }
        float lo = RANK_AT[i];
        float hi = RANK_AT[i + 1];
        return Math.max(0f, Math.min(1f, (rating - lo) / (hi - lo)));
    }

    float ratingToNext() {
        int i = rankIndex();
        return i + 1 < RANK_AT.length ? Math.max(0f, RANK_AT[i + 1] - rating) : 0f;
    }

    /**
     * What the rating is worth on the rope: 2% of pull per rank, capped at 10%. The divisions above
     * pull 18% harder, so getting stronger opens the way up rather than flattening the game.
     */
    float rankBonus() {
        return Math.min(0.10f, rankIndex() * 0.02f);
    }

    int anchorsHeld() {
        return anchorsHeld;
    }

    void noteAnchorHeld() {
        anchorsHeld++;
        bests.recordHighest(ANCHOR_KEY, anchorsHeld);
    }

    /* ---------- persistence ---------- */

    private void load() {
        rating = bests.get(RATING_KEY, 100f);
        anchorsHeld = Math.round(bests.get(ANCHOR_KEY, 0f));
        String s = bests.getString(STATE_KEY);
        if (s != null) {
            try {
                String[] parts = s.split("\\|");
                if (parts.length >= 6 && "1".equals(parts[0])) {
                    season = Math.max(1, Integer.parseInt(parts[1]));
                    division = Math.max(0, Math.min(DIVISIONS - 1, Integer.parseInt(parts[2])));
                    matchday = Math.max(0, Math.min(FIXTURES - 1, Integer.parseInt(parts[3])));
                    readInts(parts[4], points);
                    readInts(parts[5], diff);
                    if (parts.length >= 7) {
                        readInts(parts[6], played);
                    }
                }
            } catch (RuntimeException ignored) {
                // A half-written or older state is not worth a crash; start the season again.
                resetSeason();
            }
        }
        String r = bests.getString(RIVALS_KEY);
        if (r != null) {
            try {
                String[] pairs = r.split(",");
                for (int i = 0; i < pairs.length && i < rivalWin.length; i++) {
                    int colon = pairs[i].indexOf(':');
                    if (colon > 0) {
                        rivalWin[i] = Integer.parseInt(pairs[i].substring(0, colon));
                        rivalLoss[i] = Integer.parseInt(pairs[i].substring(colon + 1));
                    }
                }
            } catch (RuntimeException ignored) {
                // Same again: the ledger is a nicety, not worth failing over.
            }
        }
    }

    private void resetSeason() {
        season = 1;
        division = DIVISIONS - 1;
        matchday = 0;
        for (int i = 0; i < 4; i++) {
            points[i] = 0;
            played[i] = 0;
            diff[i] = 0;
        }
    }

    private static void readInts(String csv, int[] into) {
        String[] bits = csv.split(",");
        for (int i = 0; i < bits.length && i < into.length; i++) {
            into[i] = Integer.parseInt(bits[i]);
        }
    }

    private static String writeInts(int[] from) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < from.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(from[i]);
        }
        return sb.toString();
    }

    void save() {
        bests.putString(STATE_KEY, "1|" + season + "|" + division + "|" + matchday + "|"
                + writeInts(points) + "|" + writeInts(diff) + "|" + writeInts(played));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rivalWin.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(rivalWin[i]).append(':').append(rivalLoss[i]);
        }
        bests.putString(RIVALS_KEY, sb.toString());
        bests.putFloat(RATING_KEY, rating);
    }
}
