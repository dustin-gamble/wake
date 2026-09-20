package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Mega Pull: a carnival high-striker. Five strokes, hardest you have got; the puck flies up the
 * tower to your peak watts and rings the bell if you clear the target. The target creeps up as
 * your record does. Pure peak, ten seconds, good between sets.
 *
 * <p>3.19.4 (the emulator screenshot was a pole on purple): a night fairground - striped tents, a
 * string of twinkling bulbs, sweeping spotlights, a crowd that jumps when the bell rings - and a
 * tower whose bulbs light up behind the puck and chase when you ring it.
 *
 * <p>Five ways to play, picked from pills drawn on the canvas (there are no header chips):
 * <ul>
 * <li><b>STRIKE</b> - the original five-stroke go, now for prizes on a shelf: a mini plush, a
 * bunny, a teddy at the bell and a giant bear above it. Tier heights are fractions of the bell, so
 * they follow the rower's own record. Best tier ever is {@code megapull.prize} (1-4).</li>
 * <li><b>LADDER</b> - best of three against a named strongman, and there are five of them:
 * Bruno, Magda, Ivo, Olga and Titan, each stronger than the last as a multiple of your own record.
 * Beating the top one you have unlocked opens the next ({@code megapull.rung}); your best pull
 * against each is kept separately as {@code megapull.rival.1}..{@code .5}. Match wins are counted
 * in {@code megapull.wins}.</li>
 * <li><b>STEADY</b> - the consistency test: land five strokes in a row within 9% of the mark. The
 * first stroke sets the mark, and a miss re-sets it to that stroke. Twenty strokes to do it; the
 * record is the fewest strokes it took, {@code megapull.steady}.</li>
 * <li><b>CALL</b> - the barker calls a number and you have to hit it within +/-5 W on the next
 * stroke. Three lives, fourteen strokes; the record is the most numbers hit,
 * {@code megapull.called}.</li>
 * <li><b>TEN</b> - ten pulls, the score is the sum of all ten, so the fade at the end counts. The
 * fatigue (last three against first three) is drawn as you go. Record {@code megapull.ten}.</li>
 * </ul>
 *
 * <p><b>Tickets</b> ({@code megapull.tickets}) are paid out per stroke, by how high that stroke
 * went, and fly across the screen into the booth roll. They buy the prizes on the shelf outright -
 * tap one you can afford - so a bad night still moves you toward the giant bear. What you have
 * bought is a bitmask in {@code megapull.owned}.
 *
 * <p>A <b>weekly board</b> hangs on the left: the best pull of each day of the current week
 * ({@code megapull.week.0}..{@code .6}, cleared when {@code megapull.weekid} changes).
 *
 * <p>Fireworks go up when a record falls, and the crowd fills in and gets louder the closer you are
 * to the bell (or the strongman's mark, or five in a row).
 */
final class MegaPullGame extends GameView {

    private enum Phase { READY, RIVAL_TURN, PULLING, ROUND_END, RESULT }

    private enum Mode { STRIKE, RIVAL, STEADY, CALL, TEN }

    private static final Mode[] MODES = {Mode.STRIKE, Mode.RIVAL, Mode.STEADY, Mode.CALL, Mode.TEN};
    private static final String[] MODE_NAMES = {"STRIKE", "LADDER", "STEADY", "CALL", "TEN"};
    /** Remembered for the life of the process, so going again keeps the mode you picked. */
    private static Mode lastMode = Mode.STRIKE;

    private static final int STROKES = 5;
    private static final int RIVAL_STROKES = 3;
    private static final int STEADY_TARGET = 5;
    private static final int STEADY_LIMIT = 20;
    private static final float STEADY_TOLERANCE = 0.09f;
    /** The rower asked for this by name: hit the called number within five watts either way. */
    private static final int CALL_BAND = 5;
    private static final int CALL_LIVES = 3;
    private static final int CALL_LIMIT = 14;
    private static final int TEN_PULLS = 10;
    /** Seconds a result stays up before the next go arms itself, so nobody has to let go of the handle. */
    private static final double REARM_SECONDS = 8.0;

    /** The ladder, weakest first. Strength is a multiple of your own record (or high watts). */
    private static final String[] RIVALS = {"BRUNO", "MAGDA", "IVO", "OLGA", "TITAN"};
    private static final float[] RIVAL_STRENGTH = {0.88f, 0.97f, 1.05f, 1.14f, 1.25f};
    private static final int[] RIVAL_SINGLET = {0xFFC62F3A, 0xFF2E7DC4, 0xFF2FA05A, 0xFF8A4FC0, 0xFFD8891A};
    private static final float[] RIVAL_SCALE = {0.94f, 0.98f, 1.02f, 1.06f, 1.12f};

    /** Prize tiers as fractions of the bell: mini plush, bunny, teddy (the bell itself), giant bear. */
    private static final float[] PRIZE_AT = {0.72f, 0.86f, 1.0f, 1.08f};
    private static final String[] PRIZE_NAME = {"MINI PLUSH", "BUNNY", "TEDDY", "GIANT BEAR"};
    private static final int[] PRIZE_COLOR = {0xFFFF9EC4, 0xFF8FC8FF, 0xFFC8915A, 0xFF8A5A33};
    private static final float[] PRIZE_X = {0.645f, 0.715f, 0.795f, 0.895f};
    private static final float[] PRIZE_SIZE = {30f, 42f, 56f, 90f};
    /** Tickets each prize costs at the booth, if you would rather buy it than pull for it. */
    private static final int[] PRIZE_COST = {60, 150, 320, 700};

    private static final String[] WEEKDAY = {"M", "T", "W", "T", "F", "S", "S"};

    private static final int[] FIREWORK = {0xFFFF5A7A, 0xFFF5C518, 0xFF6BD8FF, 0xFF35D0BA,
            0xFFB78CFF, 0xFFFFFFFF};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Particles sparks = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final android.graphics.Path tent = new android.graphics.Path();
    private final RectF oval = new RectF();
    private final RectF[] pills = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    /** Tap targets on the prize shelf (buy with tickets) and on the strongman ladder. */
    private final RectF[] prizeHit = {new RectF(), new RectF(), new RectF(), new RectF()};
    private final RectF[] rivalHit = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;

    private Mode mode = Mode.STRIKE;
    private Phase phase = Phase.READY;
    private boolean goStarted;
    private int strokesLeft;
    private int peak;
    /** Highest watts since the last stroke tick - one stroke's peak, whatever the phase. */
    private int windowPeak;
    private float puck;          // 0..1 shown
    private float puckTarget;
    /** Watts at the top of the tower. */
    private float scaleMax;
    private int bell;            // watts to ring it
    private boolean rang;
    private boolean success;
    private double resultAt;
    /** The record when this go started; beating it mid-pull fires the NEW BEST burst once. */
    private float recordAtStart;
    private double newBestUntil;

    // Prizes (STRIKE).
    private int prizeTier;
    private int hopTier;
    private double hopAt = -10;

    /** The best single pull of this go, whatever the mode - what the records are written from. */
    private int bestPull;

    // Tickets.
    private int tickets;
    /** Bitmask of prizes bought at the booth, so the shelf keeps them between gos. */
    private int owned;
    private int ticketsThisGo;
    private double ticketPopAt = -10;
    private static final int TK = 16;
    private static final double TK_FLIGHT = 0.8;
    private final float[] tkX = new float[TK];
    private final float[] tkY = new float[TK];
    private final double[] tkAt = new double[TK];
    private final int[] tkVal = new int[TK];
    private final boolean[] tkLive = new boolean[TK];
    private float jarX;
    private float jarY;

    // Weekly bell-height board.
    private final float[] week = new float[7];
    private final float[] weekShown = new float[7];
    private int weekToday;
    private int weekId;

    // Rival ladder (RIVAL).
    private static int lastRivalIdx;
    private int rivalIdx;
    private int rungUnlocked = 1;
    private int matchBest;
    private int rivalRound;
    private int youWins;
    private int rivalWins;
    private int rivalMark;
    private float rivalPuck;
    private float rivalPuckTarget;
    private double rivalTurnAt;
    private boolean rivalSlammed;
    private boolean roundYours;
    private double roundEndAt;

    // Consistency (STEADY).
    private int mark;
    private int streak;
    private int bestStreak;
    private int steadyStrokes;
    private int lastStrokeW;
    private double puckHoldUntil;

    // Called number (CALL).
    private int called;
    private int callsHit;
    private int callStrokes;
    private int callLives;
    private double callAt;
    private int callMiss;

    // Ten pulls (TEN).
    private final int[] tenPull = new int[TEN_PULLS];
    private final float[] tenShown = new float[TEN_PULLS];
    private int tenCount;
    private int tenScore;

    // Feedback line under the big number.
    private String feedback = "";
    private int feedbackColor = TEXT;
    private double feedbackUntil;

    // Crowd: 0 = a few onlookers, 1 = packed and roaring.
    private static final int CROWD = 72;
    private final float[] crowdIn = new float[CROWD];
    private float crowd;
    private boolean celebrate;

    // Fireworks.
    private static final int ROCKETS = 10;
    private final float[] rkX = new float[ROCKETS];
    private final float[] rkY = new float[ROCKETS];
    private final float[] rkVy = new float[ROCKETS];
    private final float[] rkFuse = new float[ROCKETS];
    private final int[] rkCol = new int[ROCKETS];
    private final boolean[] rkLive = new boolean[ROCKETS];
    private final double[] rkBoomAt = new double[ROCKETS];
    private double fireworksUntil;
    private double nextRocketAt;

    MegaPullGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        fireworksUntil = 0;
        nextRocketAt = 0;
        for (int i = 0; i < ROCKETS; i++) {
            rkLive[i] = false;
            rkBoomAt[i] = -10;
        }
        crowd = 0f;
        for (int i = 0; i < TK; i++) {
            tkLive[i] = false;
        }
        tickets = Math.round(bests.get("megapull.tickets", 0f));
        owned = Math.round(bests.get("megapull.owned", 0f));
        loadWeek();
        for (int i = 0; i < 7; i++) {
            weekShown[i] = 0f;
        }
        resetGo();
    }

    /**
     * Leaving the screen banks anything still in the air, so tickets earned on the last stroke are
     * never lost. Saved here rather than per frame, the way Skyline saves its city.
     */
    @Override
    protected void onStop() {
        for (int i = 0; i < TK; i++) {
            if (tkLive[i]) {
                tkLive[i] = false;
                tickets += tkVal[i];
            }
        }
        bests.putFloat("megapull.tickets", tickets);
        bests.putFloat("megapull.owned", owned);
        // A go that is abandoned half way still happened: bank its best pull, or a record stroke
        // taken on the fourth of five would be thrown away by walking out of the screen.
        finishGo();
    }

    /** A fresh go in the current mode. Leaves the rowing clock alone, unlike {@link #start()}. */
    private void resetGo() {
        mode = lastMode;
        phase = Phase.READY;
        goStarted = false;
        strokesLeft = mode == Mode.RIVAL ? RIVAL_STROKES : STROKES;
        peak = 0;
        windowPeak = 0;
        puck = 0f;
        puckTarget = 0f;
        rang = false;
        success = false;
        float best = bests.get("megapull.peak", 0f);
        recordAtStart = best;
        newBestUntil = 0;
        // No record yet: set the bell a fifth above the rower's high power, not a fixed 180 W.
        bell = Math.max(20, best > 0 ? Math.round(best * 1.03f) : (int) Math.round(profile.highWatts() * 1.2));
        scaleMax = bell * 1.15f;
        prizeTier = 0;
        hopTier = 0;
        hopAt = -10;
        youWins = 0;
        rivalWins = 0;
        rivalRound = 1;
        rivalMark = 0;
        rivalPuck = 0f;
        rivalPuckTarget = 0f;
        mark = 0;
        streak = 0;
        bestStreak = 0;
        steadyStrokes = 0;
        lastStrokeW = 0;
        puckHoldUntil = 0;
        feedbackUntil = 0;
        bestPull = 0;
        ticketsThisGo = 0;
        matchBest = 0;
        callsHit = 0;
        callStrokes = 0;
        callLives = CALL_LIVES;
        called = 0;
        callMiss = 0;
        tenCount = 0;
        tenScore = 0;
        for (int i = 0; i < TEN_PULLS; i++) {
            tenPull[i] = 0;
            tenShown[i] = 0f;
        }
        if (mode == Mode.STEADY) {
            scaleMax = (float) Math.max(30.0, profile.highWatts() * 1.35);
        } else if (mode == Mode.TEN) {
            // The bell still means something here - every pull is measured against it.
            scaleMax = (float) Math.max(bell * 1.1, profile.highWatts() * 1.35);
        } else if (mode == Mode.CALL) {
            called = 0;
            newCall();
        } else if (mode == Mode.RIVAL) {
            rungUnlocked = Math.max(1, Math.min(RIVALS.length,
                    Math.round(bests.get("megapull.rung", 1f))));
            rivalIdx = Math.max(0, Math.min(rungUnlocked - 1, lastRivalIdx));
            startRivalTurn();
        }
    }

    /** Who you are up against on the ladder right now. */
    private String rivalName() {
        return RIVALS[Math.max(0, Math.min(RIVALS.length - 1, rivalIdx))];
    }

    /** The number the whole ladder is scaled from: your record, or your high power before there is one. */
    private double benchmark() {
        return recordAtStart > 0 ? recordAtStart : profile.highWatts() * 1.2;
    }

    /** The strongman steps up: sets his mark for this round, then swings (animated in render). */
    private void startRivalTurn() {
        phase = Phase.RIVAL_TURN;
        rivalTurnAt = sessionSeconds;
        rivalSlammed = false;
        peak = 0;
        puck = 0f;
        puckTarget = 0f;
        rang = false;
        strokesLeft = RIVAL_STROKES;
        rivalPuck = 0f;
        rivalPuckTarget = 0f;
        // Each rung is a multiple of your own best, so Bruno is beatable and Titan is not, yet.
        // Leading makes them dig deeper, trailing eases them off - matches should go the distance.
        double base = benchmark() * RIVAL_STRENGTH[rivalIdx];
        double adj = 1.0 + 0.05 * (youWins - rivalWins);
        double m = base * adj * (0.95 + Math.random() * 0.1);
        rivalMark = (int) Math.round(Math.max(profile.typicalWatts(), m));
        scaleMax = Math.max(bell, rivalMark) * 1.15f;
    }

    /** A fresh called number, in the rower's own band, on a 5 W grid and never the same twice. */
    private void newCall() {
        double lo = Math.max(30, profile.lowWatts() * 1.02);
        double hi = Math.max(lo + 30, profile.highWatts() * 1.08);
        int next = called;
        for (int guard = 0; guard < 8 && next == called; guard++) {
            next = (int) (Math.round((lo + Math.random() * (hi - lo)) / 5.0) * 5);
        }
        called = next;
        callAt = sessionSeconds;
        scaleMax = (float) Math.max(called * 1.5, profile.highWatts() * 1.3);
    }

    /**
     * The peak has to be sampled from every reading, not from the stroke tick.
     *
     * <p>This game used to read watts inside {@link #onStroke}, which fires when the stroke
     * <em>counter</em> increments - about a second after the drive, by which time instantaneous
     * power has usually collapsed back toward zero (it reads exactly zero in 27% of samples taken
     * mid-row). The tower was therefore being built from the troughs between pulls, and looked
     * dead however hard the handle was moved.
     */
    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (s == null) {
            return;
        }
        if (s.watts > windowPeak) {
            windowPeak = s.watts;
        }
        if (mode != Mode.STRIKE && mode != Mode.RIVAL) {
            // STEADY, CALL and TEN are judged one stroke at a time, so their puck rides the live
            // drive (windowPeak) in render rather than a running maximum.
            return;
        }
        // Before the first stroke only count readings taken while actually rowing: the S4 holds a
        // stale watts figure after a stop, which would otherwise fling the puck up on its own.
        boolean live = phase == Phase.PULLING || (phase == Phase.READY && driving);
        if (!live) {
            return;
        }
        raisePeak(s.watts);
    }

    /** A reading counts toward this go's peak: puck, bursts, NEW BEST, prize tiers, the bell. */
    private void raisePeak(int watts) {
        if (watts > peak) {
            peak = watts;
            bestPull = Math.max(bestPull, watts);
            matchBest = Math.max(matchBest, watts);
            puckTarget = Math.min(1f, peak / scaleMax);
            fx.burst(getWidth() * 0.5f, getHeight() * 0.85f, 16, dp(150f), 0.5f, dp(3f), 0xFFF5C518, true);
            // Shake scaled to the rower's own power: a typical pull is a solid thump for anyone.
            shake.kick(dp(4f) + (float) (watts / Math.max(1.0, profile.typicalWatts())) * dp(5f));
            if (recordAtStart > 0 && peak > recordAtStart && newBestUntil == 0) {
                newBestUntil = sessionSeconds + 2.5;
                fx.burst(getWidth() * 0.5f, getHeight() * 0.5f, 80, dp(320f), 1.4f, dp(4.5f), 0xFF35D0BA, true);
                shake.kick(dp(18f));
                launchFireworks(5.0);
            }
            if (mode == Mode.STRIKE) {
                int tier = tierFor(peak);
                if (tier > prizeTier) {
                    prizeTier = tier;
                    hopTier = tier;
                    hopAt = sessionSeconds;
                    fx.burst(getWidth() * PRIZE_X[tier - 1], getHeight() * 0.44f - dp(PRIZE_SIZE[tier - 1] * 0.5f),
                            30, dp(180f), 0.9f, dp(3.5f), PRIZE_COLOR[tier - 1], true);
                    say("PRIZE: " + PRIZE_NAME[tier - 1] + "!", PRIZE_COLOR[tier - 1]);
                }
            }
        }
        if (peak >= bell && !rang) {
            rang = true;
            fx.burst(getWidth() * 0.5f, getHeight() * 0.12f, 60, dp(260f), 1.2f, dp(4f), 0xFFF5C518, true);
            shake.kick(dp(16f));
            bests.recordHighest("megapull.peak", peak);
        }
    }

    @Override
    protected void onStroke(int watts) {
        // The tick lands about a second after the drive, so the stroke's own peak is the highest
        // reading since the previous tick, not the power right now.
        int strokeW = Math.max(windowPeak, watts);
        windowPeak = 0;
        if (phase == Phase.READY) {
            phase = Phase.PULLING;
            goStarted = true;
            if (mode == Mode.RIVAL) {
                // This tick's drive usually happened while Bruno was still swinging, when readings
                // are not live - but it spends one of the three strokes, so its peak must count.
                raisePeak(strokeW);
            }
        }
        if (phase != Phase.PULLING) {
            return;
        }
        bestPull = Math.max(bestPull, strokeW);
        if (mode == Mode.STEADY) {
            judgeSteady(strokeW);
            return;
        }
        if (mode == Mode.CALL) {
            judgeCall(strokeW);
            return;
        }
        if (mode == Mode.TEN) {
            judgeTen(strokeW);
            return;
        }
        payTickets(strokeW);
        strokesLeft--;
        if (strokesLeft <= 0) {
            if (mode == Mode.RIVAL) {
                endRound();
            } else {
                endStrike();
            }
        }
    }

    private int tierFor(int watts) {
        int t = 0;
        for (int k = 0; k < PRIZE_AT.length; k++) {
            if (watts >= PRIZE_AT[k] * bell) {
                t = k + 1;
            }
        }
        return t;
    }

    /* ---------------- tickets ---------------- */

    /**
     * Pays the tickets one stroke earned and sends them flying to the booth roll. Priced off the
     * bell, which is itself the rower's own record, so a hard pull is worth the same to anyone.
     */
    private void payTickets(int strokeW) {
        int n = (int) Math.round(6.0 * strokeW / Math.max(20, bell));
        if (strokeW >= bell) {
            n += 6;
        }
        if (n > 0) {
            awardTickets(n, getWidth() * 0.5f, puckY(strokeW));
        }
    }

    /** Spawns the flying tickets; the balance only goes up when one lands in the roll. */
    private void awardTickets(int n, float fromX, float fromY) {
        if (n <= 0) {
            return;
        }
        ticketsThisGo += n;
        int sprites = Math.max(1, Math.min(5, n / 4));
        int each = n / sprites;
        int rest = n - each * sprites;
        for (int k = 0; k < sprites; k++) {
            int value = each + (k == 0 ? rest : 0);
            if (value <= 0) {
                continue;
            }
            boolean flew = false;
            for (int i = 0; i < TK; i++) {
                if (!tkLive[i]) {
                    tkLive[i] = true;
                    tkX[i] = fromX + ((float) Math.random() - 0.5f) * dp(60f);
                    tkY[i] = fromY + ((float) Math.random() - 0.5f) * dp(30f);
                    tkAt[i] = sessionSeconds + k * 0.09;
                    tkVal[i] = value;
                    flew = true;
                    break;
                }
            }
            if (!flew) {
                // Every sprite is already in the air (a stroke payout landing on top of a NEW BEST
                // bonus). Credit it straight into the roll rather than dropping it on the floor -
                // ticketsThisGo has already counted it, so losing it here would not even show.
                tickets += value;
                ticketPopAt = sessionSeconds;
                bests.putFloat("megapull.tickets", tickets);
            }
        }
    }

    private void stepTickets() {
        for (int i = 0; i < TK; i++) {
            if (tkLive[i] && sessionSeconds - tkAt[i] >= TK_FLIGHT) {
                tkLive[i] = false;
                tickets += tkVal[i];
                ticketPopAt = sessionSeconds;
                bests.putFloat("megapull.tickets", tickets);
            }
        }
    }

    /** Buys a prize off the shelf. Tapping is the only way to spend, so it is never a surprise. */
    private boolean buyPrize(int k) {
        if ((owned & (1 << k)) != 0 || tickets < PRIZE_COST[k]) {
            return false;
        }
        tickets -= PRIZE_COST[k];
        owned |= 1 << k;
        bests.putFloat("megapull.tickets", tickets);
        bests.putFloat("megapull.owned", owned);
        // Deliberately NOT written to megapull.prize: that record means the highest tier you have
        // ever *pulled*, and buying the bear with tickets must not claim you rang for it.
        hopTier = k + 1;
        hopAt = sessionSeconds;
        say("BOUGHT: " + PRIZE_NAME[k] + "  -" + PRIZE_COST[k] + " tickets", PRIZE_COLOR[k]);
        fx.burst(getWidth() * PRIZE_X[k], getHeight() * 0.44f - dp(PRIZE_SIZE[k] * 0.5f), 40, dp(200f),
                1.0f, dp(3.5f), PRIZE_COLOR[k], true);
        launchFireworks(3.0);
        return true;
    }

    /* ---------------- the weekly board ---------------- */

    /** Reads this week's board, clearing it when the week has turned over. */
    private void loadWeek() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setFirstDayOfWeek(java.util.Calendar.MONDAY);
        // Monday first: Calendar counts Sunday as 1, so shift and wrap.
        weekToday = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
        int year = cal.get(java.util.Calendar.YEAR);
        int wk = cal.get(java.util.Calendar.WEEK_OF_YEAR);
        if (cal.get(java.util.Calendar.MONTH) == java.util.Calendar.DECEMBER && wk <= 2) {
            year++; // that last stub of December already belongs to next year's week 1
        }
        weekId = year * 100 + wk;
        if (Math.round(bests.get("megapull.weekid", 0f)) != weekId) {
            for (int i = 0; i < 7; i++) {
                bests.putFloat("megapull.week." + i, 0f);
            }
            bests.putFloat("megapull.weekid", weekId);
        }
        for (int i = 0; i < 7; i++) {
            week[i] = bests.get("megapull.week." + i, 0f);
        }
    }

    /** Puts a pull on today's column of the weekly board if it beats what is already there. */
    private void bankWeek(int watts) {
        if (watts <= 0) {
            return;
        }
        loadWeek();
        if (watts > week[weekToday]) {
            week[weekToday] = watts;
            bests.putFloat("megapull.week." + weekToday, watts);
        }
    }

    /** Every go ends here: the record, the weekly board and the ticket tally. */
    private void finishGo() {
        if (bestPull > 0) {
            bests.recordHighest("megapull.peak", bestPull);
            bankWeek(bestPull);
        }
    }

    /* ---------------- the called number ---------------- */

    /**
     * The per-stroke modes do not run through {@link #raisePeak}, so the all-time record has to be
     * celebrated here or a record pull in STEADY, CALL or TEN would pass unnoticed.
     */
    private void noteNewBest(int sw) {
        if (recordAtStart > 0 && sw > recordAtStart && newBestUntil == 0) {
            newBestUntil = sessionSeconds + 2.5;
            fx.burst(getWidth() * 0.5f, getHeight() * 0.5f, 80, dp(320f), 1.4f, dp(4.5f), 0xFF35D0BA, true);
            shake.kick(dp(18f));
            launchFireworks(5.0);
            awardTickets(25, getWidth() * 0.5f, getHeight() * 0.5f);
        }
    }

    private void judgeCall(int sw) {
        if (sw <= 0) {
            return;
        }
        noteNewBest(sw);
        callStrokes++;
        lastStrokeW = sw;
        puckHoldUntil = sessionSeconds + 0.9;
        int off = sw - called;
        callMiss = off;
        if (Math.abs(off) <= CALL_BAND) {
            callsHit++;
            awardTickets(8 + callsHit, getWidth() * 0.5f, puckY(sw));
            fx.burst(getWidth() * 0.5f, puckY(sw), 40, dp(220f), 0.9f, dp(3.5f), 0xFFF5C518, true);
            shake.kick(dp(10f));
            say("CALLED IT  " + sw + " W  -  " + callsHit + " hit", 0xFFF5C518);
            newCall();
        } else {
            callLives--;
            shake.kick(dp(12f));
            say((off > 0 ? "OVER BY " : "UNDER BY ") + Math.abs(off) + " W  -  "
                    + (callLives > 0 ? callLives + " left" : "out"), BAD);
            if (callLives > 0) {
                newCall();
            }
        }
        if (callLives <= 0 || callStrokes >= CALL_LIMIT) {
            phase = Phase.RESULT;
            resultAt = sessionSeconds;
            success = callsHit > 0;
            boolean had = bests.has("megapull.called");
            if (bests.recordHighest("megapull.called", callsHit) && had && callsHit > 0) {
                launchFireworks(5.0);
            }
            awardTickets(callsHit * 4, getWidth() * 0.5f, getHeight() * 0.5f);
            finishGo();
        }
    }

    /* ---------------- ten pulls ---------------- */

    private void judgeTen(int sw) {
        if (sw <= 0) {
            return;
        }
        noteNewBest(sw);
        lastStrokeW = sw;
        puckHoldUntil = sessionSeconds + 0.9;
        tenPull[tenCount] = sw;
        tenCount++;
        tenScore += sw;
        payTickets(sw);
        fx.burst(getWidth() * 0.5f, puckY(sw), 24, dp(180f), 0.7f, dp(3f), 0xFFF5C518, true);
        shake.kick(dp(4f) + (float) (sw / Math.max(1.0, profile.typicalWatts())) * dp(5f));
        scaleMax = Math.max(scaleMax, sw * 1.2f);
        int left = TEN_PULLS - tenCount;
        say(sw + " W  ·  total " + tenScore + (left > 0 ? "  ·  " + left + " to go" : ""),
                sw >= bell ? 0xFFF5C518 : TEXT);
        if (tenCount >= TEN_PULLS) {
            phase = Phase.RESULT;
            resultAt = sessionSeconds;
            boolean had = bests.has("megapull.ten");
            success = bests.recordHighest("megapull.ten", tenScore);
            if (success && had) {
                launchFireworks(5.0);
            }
            awardTickets(Math.round(tenScore / Math.max(20f, bell) * 5f), getWidth() * 0.5f,
                    getHeight() * 0.5f);
            finishGo();
        }
    }

    /** The fade: the last three pulls against the first three, 1.0 = no drop-off at all. */
    private float tenFade() {
        if (tenCount < 4) {
            return 1f;
        }
        int early = 0;
        int late = 0;
        for (int i = 0; i < 3; i++) {
            early += tenPull[i];
            late += tenPull[tenCount - 1 - i];
        }
        return early <= 0 ? 1f : late / (float) early;
    }

    private void endStrike() {
        phase = Phase.RESULT;
        resultAt = sessionSeconds;
        success = prizeTier > 0;
        finishGo();
        if (prizeTier > 0) {
            boolean had = bests.has("megapull.prize");
            boolean better = bests.recordHighest("megapull.prize", prizeTier);
            if ((better && had) || prizeTier == PRIZE_AT.length) {
                launchFireworks(5.0);
            }
            hopTier = prizeTier;
            awardTickets(prizeTier * 15, getWidth() * PRIZE_X[prizeTier - 1], getHeight() * 0.42f);
        }
    }

    private void endRound() {
        roundYours = peak > rivalMark;
        if (roundYours) {
            youWins++;
            fx.burst(getWidth() * 0.5f, getHeight() * 0.45f, 50, dp(260f), 1.0f, dp(4f), ACCENT, true);
            say("ROUND YOURS  " + peak + " vs " + rivalMark + " W", ACCENT);
            awardTickets(20, getWidth() * 0.5f, getHeight() * 0.45f);
        } else {
            rivalWins++;
            shake.kick(dp(8f));
            say(rivalName() + " TAKES IT  " + peak + " vs " + rivalMark + " W", BAD);
            awardTickets(6, getWidth() * 0.5f, getHeight() * 0.45f);
        }
        phase = Phase.ROUND_END;
        roundEndAt = sessionSeconds;
        // Your best pull against this particular strongman, kept per rung.
        bests.recordHighest("megapull.rival." + (rivalIdx + 1), matchBest);
        finishGo();
    }

    private void judgeSteady(int sw) {
        if (sw <= 0) {
            return; // no reading landed for that stroke; do not judge it on nothing
        }
        noteNewBest(sw);
        steadyStrokes++;
        lastStrokeW = sw;
        puckHoldUntil = sessionSeconds + 0.9;
        if (mark <= 0) {
            mark = sw;
            streak = 1;
            say("MARK SET  " + sw + " W - now match it", ACCENT);
        } else if (Math.abs(sw - mark) <= steadyBand()) {
            streak++;
            awardTickets(4 + streak * 2, getWidth() * 0.5f, puckY(sw));
            fx.burst(getWidth() * 0.5f, puckY(sw), 30, dp(200f), 0.8f, dp(3.5f), 0xFF6BFFB8, true);
            shake.kick(dp(6f));
            say("HIT  " + sw + " W  -  " + streak + " in a row", 0xFF6BFFB8);
        } else {
            say((sw > mark ? "TOO HARD  " : "TOO SOFT  ") + sw + " W - new mark", BAD);
            shake.kick(dp(10f));
            mark = sw;
            streak = 1;
        }
        scaleMax = Math.max(scaleMax, mark * 1.35f);
        bestStreak = Math.max(bestStreak, streak);
        if (streak >= STEADY_TARGET) {
            phase = Phase.RESULT;
            resultAt = sessionSeconds;
            success = true;
            fx.burst(getWidth() * 0.5f, getHeight() * 0.12f, 60, dp(260f), 1.2f, dp(4f), 0xFFF5C518, true);
            boolean had = bests.has("megapull.steady");
            if (bests.recordLowest("megapull.steady", steadyStrokes) && had) {
                launchFireworks(5.0);
            }
            awardTickets(30, getWidth() * 0.5f, getHeight() * 0.5f);
            finishGo();
        } else if (steadyStrokes >= STEADY_LIMIT) {
            phase = Phase.RESULT;
            resultAt = sessionSeconds;
            success = false;
            finishGo();
        }
    }

    private float steadyBand() {
        return Math.max(6f, mark * STEADY_TOLERANCE);
    }

    private void say(String text, int color) {
        feedback = text;
        feedbackColor = color;
        feedbackUntil = sessionSeconds + 2.2;
    }

    private float puckY(float watts) {
        float h = getHeight();
        float top = h * 0.12f;
        float base = h * 0.86f;
        return base - (base - top) * Math.min(1f, watts / scaleMax);
    }

    /** A pulsing target band across the tower, used by STEADY's mark and CALL's called number. */
    private void drawBand(Canvas c, float cx, float top, float base, float centre, float band,
                          int color, String text) {
        float y0 = base - (base - top) * Math.min(1f, (centre + band) / scaleMax);
        float y1 = base - (base - top) * Math.min(1f, (centre - band) / scaleMax);
        if (y1 - y0 < dp(5f)) {
            float mid = (y0 + y1) * 0.5f;
            y0 = mid - dp(2.5f);
            y1 = mid + dp(2.5f);
        }
        float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 4);
        paint.setColor(((int) (0x30 + 0x50 * pulse) << 24) | (color & 0x00FFFFFF));
        c.drawRect(cx - dp(70f), y0, cx + dp(70f), y1, paint);
        paint.setColor(color);
        c.drawRect(cx - dp(70f), y0 - dp(1f), cx + dp(70f), y0 + dp(1f), paint);
        c.drawRect(cx - dp(70f), y1 - dp(1f), cx + dp(70f), y1 + dp(1f), paint);
        label(c, text, cx - dp(76f), (y0 + y1) * 0.5f + dp(4f), 9.5f, color, Paint.Align.RIGHT);
    }

    private boolean pillsVisible() {
        return phase == Phase.RESULT || (!goStarted && rivalRound == 1 && phase != Phase.ROUND_END);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (pillsVisible()) {
                for (int k = 0; k < pills.length; k++) {
                    if (pills[k].contains(e.getX(), e.getY())) {
                        lastMode = MODES[k];
                        resetGo();
                        return true;
                    }
                }
            }
            // Buying a prize with tickets works at any point in a STRIKE go.
            if (mode == Mode.STRIKE) {
                for (int k = 0; k < prizeHit.length; k++) {
                    if (prizeHit[k].contains(e.getX(), e.getY())) {
                        if (buyPrize(k)) {
                            return true;
                        }
                        if ((owned & (1 << k)) == 0) {
                            say(PRIZE_NAME[k] + " costs " + PRIZE_COST[k] + " - you have " + tickets,
                                    FAINT);
                            return true;
                        }
                    }
                }
            }
            // Picking a strongman off the ladder, but never mid-match.
            if (mode == Mode.RIVAL && (!goStarted || phase == Phase.RESULT)) {
                for (int k = 0; k < rivalHit.length; k++) {
                    if (rivalHit[k].contains(e.getX(), e.getY())) {
                        if (k < rungUnlocked) {
                            lastRivalIdx = k;
                            resetGo();
                        } else {
                            say(RIVALS[k] + " is locked - beat " + RIVALS[rungUnlocked - 1] + " first",
                                    FAINT);
                        }
                        return true;
                    }
                }
            }
            if (phase == Phase.RESULT) {
                resetGo();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /* ---------------- fireworks ---------------- */

    private void launchFireworks(double seconds) {
        fireworksUntil = Math.max(fireworksUntil, sessionSeconds + seconds);
        if (nextRocketAt < sessionSeconds) {
            nextRocketAt = sessionSeconds;
        }
    }

    private void stepFireworks(float dt, float w, float h) {
        if (sessionSeconds < fireworksUntil && sessionSeconds >= nextRocketAt) {
            nextRocketAt = sessionSeconds + 0.22 + Math.random() * 0.22;
            for (int i = 0; i < ROCKETS; i++) {
                if (!rkLive[i]) {
                    rkLive[i] = true;
                    rkX[i] = w * (0.08f + 0.84f * (float) Math.random());
                    rkY[i] = h;
                    // Tuned so every burst lands between ~10% and ~55% of the height, never off the top.
                    rkVy[i] = -h * (0.9f + 0.3f * (float) Math.random());
                    rkFuse[i] = 0.65f + 0.3f * (float) Math.random();
                    rkCol[i] = FIREWORK[(int) (Math.random() * FIREWORK.length) % FIREWORK.length];
                    break;
                }
            }
        }
        for (int i = 0; i < ROCKETS; i++) {
            if (!rkLive[i]) {
                continue;
            }
            rkY[i] += rkVy[i] * dt;
            rkVy[i] += h * 0.55f * dt;
            rkFuse[i] -= dt;
            if (Math.random() < 0.5) {
                sparks.spawn(rkX[i], rkY[i], ((float) Math.random() - 0.5f) * dp(24f), dp(40f), 0.35f,
                        dp(2f), 0xFFFFD58A, false);
            }
            if (rkFuse[i] <= 0f) {
                rkLive[i] = false;
                rkBoomAt[i] = sessionSeconds;
                int n = 34;
                for (int j = 0; j < n; j++) {
                    double a = Math.PI * 2 * j / n;
                    float sp = dp(230f) * (0.85f + 0.3f * (float) Math.random());
                    sparks.spawn(rkX[i], rkY[i], (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                            1.1f + 0.5f * (float) Math.random(), dp(3.2f), rkCol[i], true);
                }
                for (int j = 0; j < 12; j++) {
                    double a = Math.PI * 2 * j / 12 + 0.2;
                    float sp = dp(100f);
                    sparks.spawn(rkX[i], rkY[i], (float) Math.cos(a) * sp, (float) Math.sin(a) * sp,
                            0.8f, dp(2.5f), 0xFFFFFFFF, true);
                }
            }
        }
        sparks.step(dt, dp(90f));
    }

    private void drawFireworks(Canvas c) {
        for (int i = 0; i < ROCKETS; i++) {
            if (rkLive[i]) {
                paint.setColor(0xFFFFF2C8);
                c.drawCircle(rkX[i], rkY[i], dp(3f), paint);
                Fx.glow(c, rkX[i], rkY[i], dp(14f), 0x88FFD58A);
            }
            double since = sessionSeconds - rkBoomAt[i];
            if (since >= 0 && since < 0.3) {
                int a = (int) (0xAA * (1 - since / 0.3));
                Fx.glow(c, rkX[i], rkY[i], dp(110f), (a << 24) | (rkCol[i] & 0x00FFFFFF));
            }
        }
        sparks.draw(c);
    }

    /* ---------------- scenery ---------------- */

    /** Tents, a string of bulbs, sweeping spotlights and a crowd along the bottom. */
    private void drawFairground(Canvas c, float w, float h, float dt) {
        double t = sessionSeconds;
        // Spotlights from the bottom corners: they sweep the sky when nothing is happening and
        // swing in onto the tower as the crowd builds.
        for (int side = 0; side < 2; side++) {
            float ox = side == 0 ? w * 0.08f : w * 0.92f;
            double sweep = -Math.PI / 2 + Math.sin(t * 0.6 + side * 2) * 0.55;
            double onTower = Math.atan2(h * 0.14f - h, w * 0.5f - ox) + Math.sin(t * 2.5 + side) * 0.05;
            double a = sweep + (onTower - sweep) * Math.min(1f, crowd * 1.2f);
            float len = h * 1.1f;
            float tx = ox + (float) Math.cos(a) * len;
            float ty = h + (float) Math.sin(a) * len;
            float nx = (float) -Math.sin(a) * dp(70f);
            float ny = (float) Math.cos(a) * dp(70f);
            tent.rewind();
            tent.moveTo(ox, h);
            tent.lineTo(tx + nx, ty + ny);
            tent.lineTo(tx - nx, ty - ny);
            tent.close();
            paint.setColor(celebrate ? 0x30FFE28A : crowd > 0.6f ? 0x24FFF0C8 : 0x1ABFD8FF);
            c.drawPath(tent, paint);
        }
        // Stars.
        paint.setColor(0x88FFFFFF);
        for (int i = 0; i < 40; i++) {
            float sx = (i * 197 % 1000) / 1000f * w;
            float sy = (i * 331 % 1000) / 1000f * h * 0.45f;
            float tw = 0.5f + 0.5f * (float) Math.sin(t * 2 + i);
            c.drawCircle(sx, sy, dp(1f) + tw * dp(1f), paint);
        }
        drawFireworks(c);
        // Two striped tents.
        for (int k = 0; k < 2; k++) {
            float tcx = k == 0 ? w * 0.17f : w * 0.83f;
            float tw = w * 0.13f;
            float roofTop = h * 0.50f;
            float eave = h * 0.66f;
            float floor = h * 0.9f;
            int stripes = 8;
            for (int i = 0; i < stripes; i++) {
                float x0 = tcx - tw + 2 * tw * i / stripes;
                float x1 = tcx - tw + 2 * tw * (i + 1) / stripes;
                paint.setColor((i & 1) == 0 ? 0xFFB8323A : 0xFFEDE3D0);
                tent.rewind();
                tent.moveTo(tcx, roofTop);
                tent.lineTo(x0, eave);
                tent.lineTo(x1, eave);
                tent.close();
                c.drawPath(tent, paint);
                c.drawRect(x0, eave, x1, floor, paint);
            }
            paint.setColor(0xFF1A1020);
            c.drawRect(tcx - tw * 0.22f, eave + dp(20f), tcx + tw * 0.22f, floor, paint);
            paint.setColor(0xFFF5C518);
            c.drawRect(tcx - dp(1.5f), roofTop - dp(30f), tcx + dp(1.5f), roofTop, paint);
            float flap = (float) Math.sin(t * 5 + k) * dp(4f);
            tent.rewind();
            tent.moveTo(tcx + dp(1.5f), roofTop - dp(30f));
            tent.lineTo(tcx + dp(26f), roofTop - dp(24f) + flap);
            tent.lineTo(tcx + dp(1.5f), roofTop - dp(18f));
            tent.close();
            c.drawPath(tent, paint);
        }
        // A string of bulbs swinging across the top; they flicker faster as the crowd builds.
        int n = 26;
        for (int i = 0; i < n; i++) {
            float f = i / (n - 1f);
            float x = w * f;
            float y = h * 0.08f + (float) Math.sin(f * Math.PI) * h * 0.1f;
            boolean on = celebrate ? ((int) (t * 10) + i) % 2 == 0
                    : ((int) (t * (3 + crowd * 5)) + i) % 5 != 0;
            int col = i % 3 == 0 ? 0xFFFF5A7A : i % 3 == 1 ? 0xFFF5C518 : 0xFF6BD8FF;
            paint.setColor(on ? col : 0xFF3A2A48);
            c.drawCircle(x, y, dp(5f), paint);
            if (on) {
                Fx.glow(c, x, y, dp(16f), (col & 0x00FFFFFF) | 0x55000000);
            }
        }
        drawCrowd(c, w, h, dt);
    }

    /**
     * The crowd builds as you close in: onlookers drift in from below until the front is packed,
     * the jumping gets higher, arms and pennants go up, and near the top they chant.
     */
    private void drawCrowd(Canvas c, float w, float h, float dt) {
        double t = sessionSeconds;
        float visible = 12 + crowd * (CROWD - 12);
        for (int i = 0; i < CROWD; i++) {
            int order = (i * 37) % CROWD;
            float want = order < visible ? 1f : 0f;
            crowdIn[i] += (want - crowdIn[i]) * Math.min(1f, 3f * dt);
        }
        float ground = h;
        int half = CROWD / 2;
        // Back row first, smaller and darker, then the front row over it.
        for (int row = 1; row >= 0; row--) {
            float scale = row == 1 ? 0.8f : 1f;
            float rowGround = row == 1 ? ground - dp(22f) : ground;
            for (int j = 0; j < half; j++) {
                int i = row * half + j;
                float in = crowdIn[i];
                if (in < 0.02f) {
                    continue;
                }
                float x = w * (j + (row == 1 ? 0f : 0.5f)) / half;
                float rise = (1f - in) * dp(70f);
                float jump = celebrate ? (float) Math.abs(Math.sin(t * 9 + i)) * dp(12f)
                        : (float) Math.abs(Math.sin(t * (1.5 + crowd * 6) + i))
                        * (dp(1.5f) + crowd * crowd * dp(11f));
                float gy = rowGround + rise - jump;
                float s = scale;
                paint.setColor(row == 1 ? 0xFF2A1E42 : 0xFF3A2858);
                c.drawRoundRect(x - dp(11f) * s, gy - dp(34f) * s, x + dp(11f) * s, gy + dp(10f) * s + rise,
                        dp(8f) * s, dp(8f) * s, paint);
                c.drawCircle(x, gy - dp(44f) * s, dp(9f) * s, paint);
                boolean armUp = (celebrate && i % 3 == 0) || (crowd > 0.7f && i % 4 == 1)
                        || (crowd > 0.9f && i % 2 == 0);
                if (armUp) {
                    c.drawRect(x - dp(13f) * s, gy - dp(66f) * s, x - dp(9f) * s, gy - dp(34f) * s, paint);
                }
                // Pennants come out once it gets exciting.
                if ((crowd > 0.55f || celebrate) && i % 5 == 0) {
                    float px = x + dp(11f) * s;
                    float top = gy - dp(72f) * s;
                    c.drawRect(px, top, px + dp(2.5f) * s, gy - dp(30f) * s, paint);
                    int col = FIREWORK[i % 4];
                    paint.setColor(col);
                    float wave = (float) Math.sin(t * 8 + i) * dp(5f) * s;
                    tent.rewind();
                    tent.moveTo(px + dp(2.5f) * s, top);
                    tent.lineTo(px + dp(26f) * s, top + dp(7f) * s + wave);
                    tent.lineTo(px + dp(2.5f) * s, top + dp(14f) * s);
                    tent.close();
                    c.drawPath(tent, paint);
                }
            }
        }
        if (!celebrate && crowd > 0.75f && (phase == Phase.PULLING || phase == Phase.READY)
                && ((int) (t * 3) % 2 == 0)) {
            int col = crowd > 0.92f ? 0xFFF5C518 : 0xCCFFFFFF;
            bold(c, "GO!  GO!", w * 0.33f, h * 0.80f, 16f, col, Paint.Align.CENTER);
            bold(c, "GO!  GO!", w * 0.67f, h * 0.80f, 16f, col, Paint.Align.CENTER);
        }
    }

    /* ---------------- ticket booth, weekly board, ladder, ten-pull row ---------------- */

    /** The booth roll: the ticket balance, which bounces as each ticket lands in it. */
    private void drawTicketRoll(Canvas c, float w, float h) {
        float x = jarX;
        float y = jarY;
        double since = sessionSeconds - ticketPopAt;
        float pop = since >= 0 && since < 0.4 ? (float) Math.sin(Math.PI * since / 0.4) : 0f;
        float r = dp(26f) + pop * dp(5f);
        // Fixed radius on purpose: Fx.glow caches by radius, and this one is drawn every frame.
        Fx.glow(c, x, y, dp(48f), 0x44F5C518);
        // A roll of tickets seen end-on.
        paint.setColor(0xFF3A2414);
        c.drawCircle(x, y, r, paint);
        paint.setColor(0xFFF5C518);
        c.drawCircle(x, y, r * 0.82f, paint);
        paint.setColor(0xFFCE9A0E);
        for (int i = 0; i < 8; i++) {
            double a = sessionSeconds * 0.6 + i * Math.PI / 4;
            c.drawCircle(x + (float) Math.cos(a) * r * 0.5f, y + (float) Math.sin(a) * r * 0.5f,
                    dp(2.4f), paint);
        }
        paint.setColor(0xFF1A1016);
        c.drawCircle(x, y, r * 0.22f, paint);
        bold(c, tickets + "", x + dp(44f), y + dp(6f), 20f + pop * 4f, 0xFFF5C518, Paint.Align.LEFT);
        label(c, "TICKETS" + (ticketsThisGo > 0 ? "   +" + ticketsThisGo + " this go" : ""),
                x + dp(44f), y + dp(20f), 9f, FAINT, Paint.Align.LEFT);
    }

    /** Tickets arcing from the pull into the roll. They are the payout, so they have to be seen. */
    private void drawFlyingTickets(Canvas c) {
        for (int i = 0; i < TK; i++) {
            if (!tkLive[i]) {
                continue;
            }
            float p = (float) Math.max(0, (sessionSeconds - tkAt[i]) / TK_FLIGHT);
            if (p <= 0f) {
                continue;
            }
            float x = tkX[i] + (jarX - tkX[i]) * p;
            float y = tkY[i] + (jarY - tkY[i]) * p - (float) Math.sin(Math.PI * p) * dp(120f);
            c.save();
            c.rotate(p * 540f, x, y);
            paint.setColor(0xFFF5C518);
            c.drawRoundRect(x - dp(13f), y - dp(7f), x + dp(13f), y + dp(7f), dp(2f), dp(2f), paint);
            paint.setColor(0xFF8A6A0A);
            c.drawCircle(x - dp(13f), y, dp(2.5f), paint);
            c.drawCircle(x + dp(13f), y, dp(2.5f), paint);
            c.drawRect(x - dp(7f), y - dp(1.5f), x + dp(7f), y + dp(1.5f), paint);
            c.restore();
        }
    }

    /**
     * The weekly board: the best pull of each day this week, hung on a post by the left tent. Today
     * is lit; the bars grow into place rather than appearing.
     */
    private void drawWeekBoard(Canvas c, float w, float h) {
        float x0 = w * 0.025f;
        float x1 = w * 0.195f;
        float y0 = h * 0.47f;
        float y1 = h * 0.66f;
        paint.setColor(0xFF2A1E12);
        c.drawRect((x0 + x1) * 0.5f - dp(5f), y1, (x0 + x1) * 0.5f + dp(5f), h * 0.9f, paint);
        paint.setColor(0xFF120D1C);
        c.drawRoundRect(x0 - dp(4f), y0 - dp(4f), x1 + dp(4f), y1 + dp(4f), dp(8f), dp(8f), paint);
        paint.setColor(0xFF5A3A8A);
        c.drawRoundRect(x0, y0, x1, y1, dp(6f), dp(6f), paint);
        paint.setColor(0xFF1B1428);
        c.drawRoundRect(x0 + dp(4f), y0 + dp(18f), x1 - dp(4f), y1 - dp(4f), dp(5f), dp(5f), paint);
        label(c, "THIS WEEK'S BEST PULL", (x0 + x1) * 0.5f, y0 + dp(13f), 8.5f, 0xFFF5C518,
                Paint.Align.CENTER);
        float top = y0 + dp(26f);
        float floor = y1 - dp(16f);
        float span = (x1 - x0 - dp(16f)) / 7f;
        float peakW = Math.max(1f, Math.max(bell, maxWeek()));
        for (int i = 0; i < 7; i++) {
            float bx = x0 + dp(8f) + i * span;
            float bw = span * 0.62f;
            float value = weekShown[i];
            float bh = Math.max(0f, Math.min(1f, value / peakW)) * (floor - top);
            boolean today = i == weekToday;
            paint.setColor(0xFF261B3A);
            c.drawRect(bx, top, bx + bw, floor, paint);
            if (bh > 0.5f) {
                paint.setColor(today ? 0xFFF5C518 : 0xFF35D0BA);
                c.drawRect(bx, floor - bh, bx + bw, floor, paint);
                if (today) {
                    Fx.glow(c, bx + bw * 0.5f, floor - bh, dp(18f), 0x55F5C518);
                }
            }
            label(c, WEEKDAY[i], bx + bw * 0.5f, y1 - dp(5f), 8f, today ? 0xFFF5C518 : FAINT,
                    Paint.Align.CENTER);
            if (week[i] > 0) {
                label(c, Math.round(week[i]) + "", bx + bw * 0.5f, floor - bh - dp(3f), 7.5f,
                        today ? 0xFFF5C518 : DIM, Paint.Align.CENTER);
            }
        }
        // The bell itself, drawn across the week: a bar reaching it rang it that day.
        float bellLine = floor - Math.min(1f, bell / peakW) * (floor - top);
        paint.setColor(0xFFF0B132);
        for (float dx = x0 + dp(6f); dx < x1 - dp(6f); dx += dp(9f)) {
            c.drawRect(dx, bellLine - dp(1f), dx + dp(5f), bellLine + dp(1f), paint);
        }
        label(c, "BELL " + bell, x1 - dp(8f), bellLine - dp(3f), 7.5f, 0xFFF0B132, Paint.Align.RIGHT);
    }

    private float maxWeek() {
        float m = 0f;
        for (int i = 0; i < 7; i++) {
            m = Math.max(m, week[i]);
        }
        return m;
    }

    /** The ladder of strongmen: who is unlocked, how hard they pull, your best against each. */
    private void drawLadder(Canvas c, float w, float h) {
        float x0 = w * 0.63f;
        float x1 = w * 0.965f;
        float y0 = h * 0.18f;
        float rowH = dp(34f);
        paint.setColor(0xCC120D1C);
        c.drawRoundRect(x0 - dp(8f), y0 - dp(24f), x1 + dp(8f), y0 + rowH * RIVALS.length + dp(8f),
                dp(10f), dp(10f), paint);
        label(c, "THE LADDER", (x0 + x1) * 0.5f, y0 - dp(8f), 9.5f, 0xFFF5C518, Paint.Align.CENTER);
        for (int k = 0; k < RIVALS.length; k++) {
            RectF r = rivalHit[k];
            r.set(x0, y0 + k * rowH, x1, y0 + (k + 1) * rowH - dp(4f));
            boolean unlocked = k < rungUnlocked;
            boolean current = k == rivalIdx;
            paint.setColor(current ? 0x33F5C518 : unlocked ? 0x18FFFFFF : 0x10FFFFFF);
            c.drawRoundRect(r, dp(6f), dp(6f), paint);
            paint.setColor(unlocked ? RIVAL_SINGLET[k] : 0xFF3A3050);
            c.drawRoundRect(r.left + dp(5f), r.top + dp(6f), r.left + dp(11f), r.bottom - dp(6f),
                    dp(3f), dp(3f), paint);
            int strength = (int) Math.round(benchmark() * RIVAL_STRENGTH[k]);
            bold(c, unlocked ? RIVALS[k] : "? ? ?", r.left + dp(18f), r.centerY() + dp(4f), 11.5f,
                    unlocked ? (current ? 0xFFF5C518 : TEXT) : FAINT, Paint.Align.LEFT);
            if (unlocked) {
                label(c, "~" + strength + " W", r.left + dp(92f), r.centerY() + dp(4f), 9f, DIM,
                        Paint.Align.LEFT);
                float mine = bests.get("megapull.rival." + (k + 1), 0f);
                label(c, mine > 0 ? "you " + Math.round(mine) + " W" : "not met",
                        r.right - dp(8f), r.centerY() + dp(4f), 9f,
                        mine > strength ? ACCENT : FAINT, Paint.Align.RIGHT);
            } else {
                label(c, "beat " + RIVALS[k - 1] + " to unlock", r.right - dp(8f),
                        r.centerY() + dp(4f), 9f, FAINT, Paint.Align.RIGHT);
            }
        }
        if (!goStarted || phase == Phase.RESULT) {
            label(c, "tap a name to take them on", (x0 + x1) * 0.5f,
                    y0 + rowH * RIVALS.length + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
        }
    }

    /** Ten pulls as ten bars, with the fade drawn across them. */
    private void drawTenBoard(Canvas c, float w, float h) {
        float x0 = w * 0.63f;
        float x1 = w * 0.965f;
        float y0 = h * 0.24f;
        float y1 = h * 0.44f;
        paint.setColor(0xCC120D1C);
        c.drawRoundRect(x0 - dp(8f), y0 - dp(24f), x1 + dp(8f), y1 + dp(26f), dp(10f), dp(10f), paint);
        label(c, "TEN PULLS", (x0 + x1) * 0.5f, y0 - dp(8f), 9.5f, 0xFFF5C518, Paint.Align.CENTER);
        float span = (x1 - x0) / TEN_PULLS;
        float ref = Math.max(1f, Math.max(bell, tenMax()));
        for (int i = 0; i < TEN_PULLS; i++) {
            float bx = x0 + i * span;
            float bw = span * 0.68f;
            float bh = Math.max(0f, Math.min(1f, tenShown[i] / ref)) * (y1 - y0);
            paint.setColor(i == tenCount && phase != Phase.RESULT ? 0x44F5C518 : 0xFF241A38);
            c.drawRect(bx, y0, bx + bw, y1, paint);
            if (bh > 0.5f) {
                int col = tenPull[i] >= bell ? 0xFFF5C518 : tenPull[i] >= bell * 0.8f
                        ? 0xFF35D0BA : 0xFF6F8CFF;
                paint.setColor(col);
                c.drawRect(bx, y1 - bh, bx + bw, y1, paint);
                label(c, tenPull[i] + "", bx + bw * 0.5f, y1 - bh - dp(3f), 7.5f, DIM,
                        Paint.Align.CENTER);
            }
            label(c, (i + 1) + "", bx + bw * 0.5f, y1 + dp(11f), 7.5f,
                    i < tenCount ? DIM : FAINT, Paint.Align.CENTER);
        }
        float fade = tenFade();
        int drop = Math.round((1f - fade) * 100);
        String fadeText = tenCount < 4 ? "fatigue shows after four pulls"
                : drop <= 0 ? "HOLDING - no fade at all"
                : "FADE " + drop + "%  (last three against first three)";
        label(c, fadeText, (x0 + x1) * 0.5f, y1 + dp(22f), 9f,
                tenCount < 4 ? FAINT : drop > 25 ? BAD : drop > 10 ? WARN : ACCENT,
                Paint.Align.CENTER);
    }

    private float tenMax() {
        float m = 0f;
        for (int i = 0; i < TEN_PULLS; i++) {
            m = Math.max(m, tenPull[i]);
        }
        return m;
    }

    /** The barker's board: the number you have to hit, and the lives you have left. */
    private void drawCallBoard(Canvas c, float w, float h) {
        float cx = w * 0.795f;
        float y0 = h * 0.20f;
        float bw = w * 0.15f;
        paint.setColor(0xFF120D1C);
        c.drawRoundRect(cx - bw, y0, cx + bw, y0 + dp(128f), dp(12f), dp(12f), paint);
        // Bulbs round the sign, chasing while a call is live.
        int n = 18;
        for (int i = 0; i < n; i++) {
            float f = i / (float) n;
            float bx;
            float by;
            if (f < 0.5f) {
                bx = cx - bw + 2 * bw * (f / 0.5f);
                by = y0;
            } else {
                bx = cx + bw - 2 * bw * ((f - 0.5f) / 0.5f);
                by = y0 + dp(128f);
            }
            boolean on = ((int) (sessionSeconds * 6) + i) % 3 != 0;
            paint.setColor(on ? 0xFFF5C518 : 0xFF3A2A48);
            c.drawCircle(bx, by, dp(3.5f), paint);
        }
        label(c, "THE BARKER CALLS", cx, y0 + dp(24f), 9f, FAINT, Paint.Align.CENTER);
        float since = (float) (sessionSeconds - callAt);
        float pop = since < 0.5f ? 1f + (0.5f - since) * 0.5f : 1f;
        bold(c, called + " W", cx, y0 + dp(72f), 34f * pop, 0xFFF5C518, Paint.Align.CENTER);
        label(c, "hit it within " + CALL_BAND + " W either way", cx, y0 + dp(92f), 9f, DIM,
                Paint.Align.CENTER);
        for (int i = 0; i < CALL_LIVES; i++) {
            float lx = cx - dp(24f) + i * dp(24f);
            float ly = y0 + dp(112f);
            boolean alive = i < callLives;
            if (alive) {
                Fx.glow(c, lx, ly, dp(16f), 0x66FF5A7A);
            }
            paint.setColor(alive ? 0xFFFF5A7A : 0xFF3A2A48);
            c.drawCircle(lx, ly, dp(7f), paint);
        }
        if (callStrokes > 0 && Math.abs(callMiss) > CALL_BAND && sessionSeconds < feedbackUntil) {
            // Which way you missed, on the sign itself.
            label(c, callMiss > 0 ? "LAST: " + callMiss + " W OVER" : "LAST: " + (-callMiss) + " W UNDER",
                    cx, y0 + dp(146f), 10f, BAD, Paint.Align.CENTER);
        }
    }

    /** The prize shelf for STRIKE: what you have won lights up and hops. */
    private void drawPrizes(Canvas c, float w, float h) {
        float shelfY = h * 0.44f;
        paint.setColor(0xFF5A3A22);
        c.drawRect(w * 0.60f, shelfY, w * 0.955f, shelfY + dp(9f), paint);
        paint.setColor(0xFF3A2414);
        c.drawRect(w * 0.62f, shelfY + dp(9f), w * 0.62f + dp(5f), shelfY + dp(26f), paint);
        c.drawRect(w * 0.935f, shelfY + dp(9f), w * 0.935f + dp(5f), shelfY + dp(26f), paint);
        label(c, "PRIZES", w * 0.7775f, shelfY + dp(24f), 9f, 0xFFF5C518, Paint.Align.CENTER);
        int best = (int) bests.get("megapull.prize", 0f);
        for (int k = 0; k < PRIZE_AT.length; k++) {
            float x = w * PRIZE_X[k];
            float size = dp(PRIZE_SIZE[k]);
            boolean bought = (owned & (1 << k)) != 0;
            boolean won = prizeTier > k || bought;
            prizeHit[k].set(x - size * 0.5f, shelfY - size - dp(10f), x + size * 0.5f, shelfY + dp(8f));
            float hop = 0f;
            double since = sessionSeconds - hopAt;
            if (hopTier == k + 1 && since >= 0 && since < 0.6) {
                hop = (float) Math.sin(Math.PI * since / 0.6) * dp(22f);
            }
            if (phase == Phase.RESULT && prizeTier == k + 1) {
                hop = (float) Math.abs(Math.sin(sessionSeconds * 5)) * dp(12f);
            }
            if (won) {
                Fx.glow(c, x, shelfY - size * 0.5f - hop, size * 0.9f, (PRIZE_COLOR[k] & 0x00FFFFFF) | 0x55000000);
            }
            drawPlush(c, x, shelfY - hop, size, PRIZE_COLOR[k], k, won ? 1f : 0.45f);
            if (best > k && !won) {
                // Won on an earlier go: a small star so the shelf shows your collection.
                paint.setColor(0xFFF5C518);
                c.drawCircle(x + size * 0.3f, shelfY - size - dp(4f), dp(3f), paint);
            }
            // The ticket price, and whether you can afford it right now.
            if (bought) {
                label(c, "YOURS", x, shelfY + dp(40f), 8.5f, PRIZE_COLOR[k], Paint.Align.CENTER);
            } else {
                boolean afford = tickets >= PRIZE_COST[k];
                if (afford) {
                    float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 5);
                    Fx.glow(c, x, shelfY - size * 0.5f, size * 0.85f,
                            ((int) (0x40 + 0x50 * pulse) << 24) | 0x00F5C518);
                }
                label(c, PRIZE_COST[k] + " tkts" + (afford ? "  ·  TAP" : ""), x, shelfY + dp(40f),
                        8.5f, afford ? 0xFFF5C518 : FAINT, Paint.Align.CENTER);
            }
        }
    }

    private static int shade(int color, float f) {
        int r = (int) (((color >> 16) & 0xFF) * f);
        int g = (int) (((color >> 8) & 0xFF) * f);
        int b = (int) ((color & 0xFF) * f);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int color, float f) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r += (int) ((255 - r) * f);
        g += (int) ((255 - g) * f);
        b += (int) ((255 - b) * f);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** A plush toy standing on {@code foot}: kind 0 mini bear, 1 bunny, 2 teddy, 3 giant bear. */
    private void drawPlush(Canvas c, float x, float foot, float size, int body, int kind, float dim) {
        int col = shade(body, dim);
        int light = shade(lighten(body, 0.45f), dim);
        float bodyCy = foot - size * 0.30f;
        float bodyRx = size * 0.27f;
        float bodyRy = size * 0.30f;
        float headCy = foot - size * 0.70f;
        float hr = size * 0.23f;
        paint.setColor(col);
        if (kind == 1) {
            oval.set(x - hr * 0.8f, headCy - hr * 2.4f, x - hr * 0.25f, headCy - hr * 0.4f);
            c.drawOval(oval, paint);
            oval.set(x + hr * 0.25f, headCy - hr * 2.4f, x + hr * 0.8f, headCy - hr * 0.4f);
            c.drawOval(oval, paint);
        } else {
            c.drawCircle(x - hr * 0.78f, headCy - hr * 0.72f, hr * 0.4f, paint);
            c.drawCircle(x + hr * 0.78f, headCy - hr * 0.72f, hr * 0.4f, paint);
        }
        c.drawCircle(x - bodyRx * 0.6f, foot - size * 0.07f, size * 0.09f, paint);
        c.drawCircle(x + bodyRx * 0.6f, foot - size * 0.07f, size * 0.09f, paint);
        oval.set(x - bodyRx, bodyCy - bodyRy, x + bodyRx, bodyCy + bodyRy);
        c.drawOval(oval, paint);
        c.drawCircle(x - bodyRx * 0.95f, bodyCy - bodyRy * 0.25f, size * 0.08f, paint);
        c.drawCircle(x + bodyRx * 0.95f, bodyCy - bodyRy * 0.25f, size * 0.08f, paint);
        c.drawCircle(x, headCy, hr, paint);
        paint.setColor(light);
        oval.set(x - bodyRx * 0.55f, bodyCy - bodyRy * 0.5f, x + bodyRx * 0.55f, bodyCy + bodyRy * 0.75f);
        c.drawOval(oval, paint);
        c.drawCircle(x, headCy + hr * 0.35f, hr * 0.42f, paint);
        if (kind == 1) {
            oval.set(x - hr * 0.66f, headCy - hr * 2.1f, x - hr * 0.4f, headCy - hr * 0.7f);
            c.drawOval(oval, paint);
            oval.set(x + hr * 0.4f, headCy - hr * 2.1f, x + hr * 0.66f, headCy - hr * 0.7f);
            c.drawOval(oval, paint);
        } else {
            c.drawCircle(x - hr * 0.78f, headCy - hr * 0.72f, hr * 0.2f, paint);
            c.drawCircle(x + hr * 0.78f, headCy - hr * 0.72f, hr * 0.2f, paint);
        }
        paint.setColor(shade(0xFF1A1016, 1f));
        float eye = Math.max(dp(1.2f), hr * 0.12f);
        c.drawCircle(x - hr * 0.38f, headCy - hr * 0.12f, eye, paint);
        c.drawCircle(x + hr * 0.38f, headCy - hr * 0.12f, eye, paint);
        c.drawCircle(x, headCy + hr * 0.22f, hr * 0.13f, paint);
        if (kind == 3) {
            // The giant bear wears a red bow.
            paint.setColor(shade(0xFFE0304A, dim));
            float by = headCy + hr * 1.0f;
            tent.rewind();
            tent.moveTo(x, by);
            tent.lineTo(x - hr * 0.6f, by - hr * 0.3f);
            tent.lineTo(x - hr * 0.6f, by + hr * 0.3f);
            tent.close();
            tent.moveTo(x, by);
            tent.lineTo(x + hr * 0.6f, by - hr * 0.3f);
            tent.lineTo(x + hr * 0.6f, by + hr * 0.3f);
            tent.close();
            c.drawPath(tent, paint);
            c.drawCircle(x, by, hr * 0.14f, paint);
        }
    }

    /**
     * The strongman of the moment: singlet in their own colour, handlebar moustache, a
     * sledgehammer, and opinions about losing. Higher rungs are drawn bigger.
     */
    private void drawStrongman(Canvas c, float x, float feet, float padX, float padY) {
        c.save();
        float s = RIVAL_SCALE[Math.max(0, Math.min(RIVAL_SCALE.length - 1, rivalIdx))];
        c.scale(s, s, x, feet);
        drawStrongmanBody(c, x, feet, padX, padY);
        c.restore();
        bold(c, rivalName(), x, feet + dp(22f), 11f,
                RIVAL_SINGLET[Math.max(0, Math.min(RIVALS.length - 1, rivalIdx))],
                Paint.Align.CENTER);
        label(c, "rung " + (rivalIdx + 1) + " of " + RIVALS.length, x, feet + dp(35f), 8.5f, FAINT,
                Paint.Align.CENTER);
    }

    private void drawStrongmanBody(Canvas c, float x, float feet, float padX, float padY) {
        double t = sessionSeconds;
        int singlet = RIVAL_SINGLET[Math.max(0, Math.min(RIVALS.length - 1, rivalIdx))];
        boolean over = phase == Phase.ROUND_END || (phase == Phase.RESULT && mode == Mode.RIVAL);
        boolean heLost = over && (phase == Phase.RESULT ? youWins >= 2 : roundYours);
        boolean flex = over && !heLost;
        float bob = (float) Math.sin(t * 2.2) * dp(1.5f);
        float slump = heLost ? dp(8f) : 0f;
        float headShake = heLost ? (float) Math.sin(t * 14) * dp(3f) : 0f;
        float hip = feet - dp(50f);
        float shoulderY = feet - dp(112f) + bob + slump * 0.5f;
        int skin = 0xFFE0A47A;

        // Legs and boots.
        paint.setColor(0xFF2A2233);
        c.drawRect(x - dp(18f), hip, x - dp(4f), feet, paint);
        c.drawRect(x + dp(4f), hip, x + dp(18f), feet, paint);
        paint.setColor(0xFF120C16);
        c.drawRoundRect(x - dp(23f), feet - dp(8f), x - dp(2f), feet + dp(2f), dp(3f), dp(3f), paint);
        c.drawRoundRect(x + dp(2f), feet - dp(8f), x + dp(23f), feet + dp(2f), dp(3f), dp(3f), paint);
        // Torso.
        paint.setColor(skin);
        tent.rewind();
        tent.moveTo(x - dp(20f), hip);
        tent.lineTo(x + dp(20f), hip);
        tent.lineTo(x + dp(36f), shoulderY);
        tent.lineTo(x - dp(36f), shoulderY);
        tent.close();
        c.drawPath(tent, paint);
        paint.setColor(singlet);
        tent.rewind();
        tent.moveTo(x - dp(20f), hip);
        tent.lineTo(x + dp(20f), hip);
        tent.lineTo(x + dp(24f), shoulderY + dp(16f));
        tent.lineTo(x - dp(24f), shoulderY + dp(16f));
        tent.close();
        c.drawPath(tent, paint);
        paint.setColor(0xFFEDE3D0);
        c.drawRect(x - dp(21f), hip - dp(22f), x + dp(21f), hip - dp(15f), paint);
        c.drawRect(x - dp(22f), hip - dp(38f), x + dp(22f), hip - dp(31f), paint);
        paint.setColor(0xFF120C16);
        c.drawRect(x - dp(21f), hip - dp(6f), x + dp(21f), hip + dp(2f), paint);
        paint.setColor(0xFFF5C518);
        c.drawRect(x - dp(5f), hip - dp(6f), x + dp(5f), hip + dp(2f), paint);
        // Head.
        float hx = x + headShake;
        float hy = shoulderY - dp(22f) + slump;
        paint.setColor(skin);
        c.drawRect(x - dp(7f), shoulderY - dp(8f), x + dp(7f), shoulderY + dp(2f), paint);
        c.drawCircle(hx, hy, dp(17f), paint);
        paint.setColor(singlet); // headband in their own colour, so the rungs are told apart
        c.drawRect(hx - dp(16f), hy - dp(11f), hx + dp(16f), hy - dp(5f), paint);
        paint.setColor(0xFF120C16);
        c.drawCircle(hx - dp(6f), hy - dp(3f), dp(2f), paint);
        c.drawCircle(hx + dp(6f), hy - dp(3f), dp(2f), paint);
        oval.set(hx - dp(13f), hy + dp(4f), hx - dp(1f), hy + dp(9f));
        c.drawOval(oval, paint);
        oval.set(hx + dp(1f), hy + dp(4f), hx + dp(13f), hy + dp(9f));
        c.drawOval(oval, paint);
        c.drawCircle(hx - dp(14f), hy + dp(3f), dp(2.5f), paint);
        c.drawCircle(hx + dp(14f), hy + dp(3f), dp(2.5f), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(11f));
        paint.setColor(skin);
        float slx = x - dp(30f);
        float srx = x + dp(30f);
        float sy = shoulderY + dp(6f);
        if (flex) {
            // Both arms up, biceps out; the hammer leans against his leg.
            float pump = (float) Math.abs(Math.sin(t * 4)) * dp(6f);
            c.drawLine(slx, sy, slx - dp(22f), sy - dp(18f), paint);
            c.drawLine(slx - dp(22f), sy - dp(18f), slx - dp(10f), sy - dp(46f) - pump, paint);
            c.drawLine(srx, sy, srx + dp(22f), sy - dp(18f), paint);
            c.drawLine(srx + dp(22f), sy - dp(18f), srx + dp(10f), sy - dp(46f) - pump, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawCircle(slx - dp(18f), sy - dp(24f), dp(8f), paint);
            c.drawCircle(srx + dp(18f), sy - dp(24f), dp(8f), paint);
            drawHammer(c, x + dp(50f), feet - dp(92f), 80f);
            return;
        }
        // Hammer angle: resting, wound up overhead, slammed onto the pad.
        float rest = (float) Math.toRadians(60) + (float) Math.sin(t * 2.2) * 0.04f;
        float overhead = (float) Math.toRadians(-120);
        float slam = (float) Math.atan2(padY - sy, padX - x);
        float theta = rest;
        if (phase == Phase.RIVAL_TURN) {
            float tt = (float) (sessionSeconds - rivalTurnAt) - 0.8f;
            if (tt >= 0 && tt < 0.9f) {
                float p = tt / 0.9f;
                theta = rest + (overhead - rest) * (p * p * (3 - 2 * p));
            } else if (tt >= 0.9f && tt < 1.08f) {
                theta = overhead + (slam - overhead) * ((tt - 0.9f) / 0.18f);
            } else if (tt >= 1.08f) {
                theta = slam;
            }
        }
        float gx = x + (float) Math.cos(theta) * dp(30f);
        float gy = sy + (float) Math.sin(theta) * dp(30f);
        c.drawLine(slx, sy, gx, gy, paint);
        c.drawLine(srx, sy, gx, gy, paint);
        paint.setStyle(Paint.Style.FILL);
        c.drawCircle(gx, gy, dp(6.5f), paint);
        drawHammer(c, gx, gy, (float) Math.toDegrees(theta));
    }

    private void drawHammer(Canvas c, float gx, float gy, float degrees) {
        c.save();
        c.rotate(degrees, gx, gy);
        paint.setColor(0xFF8A5A33);
        c.drawRect(gx - dp(8f), gy - dp(2.5f), gx + dp(100f), gy + dp(2.5f), paint);
        paint.setColor(0xFF9AA3B2);
        c.drawRoundRect(gx + dp(88f), gy - dp(17f), gx + dp(110f), gy + dp(17f), dp(3f), dp(3f), paint);
        paint.setColor(0xFF6A7382);
        c.drawRect(gx + dp(88f), gy - dp(17f), gx + dp(92f), gy + dp(17f), paint);
        c.restore();
    }

    /* ---------------- frame ---------------- */

    private float crowdTarget() {
        switch (phase) {
            case RIVAL_TURN:
                return 0.35f;
            case ROUND_END:
                return roundYours ? 1f : 0.2f;
            case RESULT:
                return success ? 1f : 0.15f;
            default:
                break;
        }
        if (mode == Mode.STEADY) {
            return streak / (float) STEADY_TARGET;
        }
        if (mode == Mode.CALL) {
            return Math.min(1f, callsHit / 5f);
        }
        if (mode == Mode.TEN) {
            float pace = tenCount == 0 ? 0f
                    : (tenScore / (float) tenCount) / Math.max(20f, bell);
            return Math.max(0f, Math.min(1f, (tenCount / (float) TEN_PULLS) * 0.5f + pace * 0.5f));
        }
        float goal = mode == Mode.RIVAL ? Math.max(1, rivalMark) : Math.max(1, bell);
        // Starts filling at half the goal, packed at the goal.
        return Math.max(0f, Math.min(1f, (peak / goal - 0.5f) / 0.5f));
    }

    private void stepPhases() {
        if (phase == Phase.RIVAL_TURN) {
            double tt = sessionSeconds - rivalTurnAt;
            if (!rivalSlammed && tt >= 1.88) {
                rivalSlammed = true;
                rivalPuckTarget = Math.min(1f, rivalMark / scaleMax);
                shake.kick(dp(12f));
                fx.burst(getWidth() * 0.5f - dp(45f), getHeight() * 0.86f, 30, dp(200f), 0.6f, dp(3.5f),
                        0xFF6F8CFF, true);
            }
            if (tt >= 3.8) {
                phase = Phase.READY;
                say("YOUR TURN - beat " + rivalMark + " W in " + RIVAL_STROKES + " strokes", TEXT);
            }
        } else if (phase == Phase.ROUND_END && sessionSeconds - roundEndAt > 3.0) {
            if (youWins >= 2 || rivalWins >= 2) {
                phase = Phase.RESULT;
                resultAt = sessionSeconds;
                success = youWins >= 2;
                if (success) {
                    bests.putFloat("megapull.wins", bests.get("megapull.wins", 0f) + 1f);
                    awardTickets(40 + rivalIdx * 20, getWidth() * 0.5f, getHeight() * 0.45f);
                    launchFireworks(4.0);
                    // Beat the top rung you have and the next strongman comes out of the tent.
                    if (rivalIdx + 1 >= rungUnlocked && rungUnlocked < RIVALS.length) {
                        rungUnlocked++;
                        bests.putFloat("megapull.rung", rungUnlocked);
                        say("UNLOCKED  " + RIVALS[rungUnlocked - 1] + " steps up next",
                                RIVAL_SINGLET[rungUnlocked - 1]);
                    }
                }
            } else {
                rivalRound++;
                startRivalTurn();
            }
        } else if (phase == Phase.RESULT && sessionSeconds - resultAt > REARM_SECONDS) {
            resetGo();
        }
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        stepPhases();
        shake.step(dt);
        fx.step(dt, dp(400f));
        stepFireworks(dt, w, h);
        stepTickets();
        jarX = w * 0.075f;
        jarY = h * 0.115f;
        for (int i = 0; i < 7; i++) {
            weekShown[i] += (week[i] - weekShown[i]) * Math.min(1f, 2.5f * dt);
        }
        for (int i = 0; i < TEN_PULLS; i++) {
            tenShown[i] += (tenPull[i] - tenShown[i]) * Math.min(1f, 6f * dt);
        }
        if (mode != Mode.STRIKE && mode != Mode.RIVAL
                && (phase == Phase.READY || phase == Phase.PULLING)) {
            // Live: the puck rides this stroke's drive, holds on the judged figure, then drops.
            puckTarget = sessionSeconds < puckHoldUntil ? Math.min(1f, lastStrokeW / scaleMax)
                    : Math.min(1f, windowPeak / scaleMax);
        }
        puck += (puckTarget - puck) * Math.min(1f, 5f * dt);
        rivalPuck += (rivalPuckTarget - rivalPuck) * Math.min(1f, 4f * dt);
        crowd += (crowdTarget() - crowd) * Math.min(1f, 1.5f * dt);
        celebrate = rang || (phase == Phase.RESULT && success) || (phase == Phase.ROUND_END && roundYours);

        c.save();
        c.translate(shake.dx, shake.dy);
        if (skyShader == null || skyHeight != h) {
            skyHeight = h;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, h, 0xFF2A1040, 0xFF0A0E14,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(-dp(30f), -dp(30f), w + dp(30f), h + dp(30f), paint);
        paint.setShader(null);
        drawFairground(c, w, h, dt);
        drawWeekBoard(c, w, h);
        if (mode == Mode.STRIKE) {
            drawPrizes(c, w, h);
        } else if (mode == Mode.RIVAL) {
            drawLadder(c, w, h);
        } else if (mode == Mode.CALL) {
            drawCallBoard(c, w, h);
        } else if (mode == Mode.TEN) {
            drawTenBoard(c, w, h);
        }

        // Tower.
        float cx = w * 0.5f;
        float top = h * 0.12f;
        float base = h * 0.86f;
        paint.setColor(0xFF3B2A5E);
        c.drawRect(cx - dp(14f), top, cx + dp(14f), base, paint);
        paint.setColor(0xFF5A3A8A);
        c.drawRect(cx - dp(60f), base, cx + dp(60f), base + dp(16f), paint);
        // Bulbs up both edges of the tower: lit behind the puck, chasing once the bell rings.
        int bulbs = 24;
        for (int i = 0; i < bulbs; i++) {
            float f = (i + 0.5f) / bulbs;
            float y = base - (base - top) * f;
            boolean lit = celebrate ? ((int) (sessionSeconds * 12) - i) % 4 == 0 || f <= puck : f <= puck;
            int col = f > 0.85f ? 0xFFFF5A5A : f > 0.6f ? 0xFFF5C518 : 0xFF6BFFB8;
            paint.setColor(lit ? col : 0xFF2A2140);
            c.drawCircle(cx - dp(22f), y, dp(4f), paint);
            c.drawCircle(cx + dp(22f), y, dp(4f), paint);
            if (lit) {
                Fx.glow(c, cx - dp(22f), y, dp(12f), (col & 0x00FFFFFF) | 0x66000000);
                Fx.glow(c, cx + dp(22f), y, dp(12f), (col & 0x00FFFFFF) | 0x66000000);
            }
        }
        // Scale marks with watts.
        for (int i = 0; i <= 10; i++) {
            float f = i / 10f;
            float y = base - (base - top) * f;
            paint.setColor(0x66FFFFFF);
            c.drawRect(cx + dp(16f), y - dp(1f), cx + dp(28f), y + dp(1f), paint);
            label(c, Math.round(scaleMax * f) + "", cx + dp(34f), y + dp(4f), 8.5f, FAINT,
                    Paint.Align.LEFT);
        }
        float bellY = base - (base - top) * Math.min(1f, bell / scaleMax);
        if (mode == Mode.STRIKE || mode == Mode.RIVAL || mode == Mode.TEN) {
            // Prize heights on the left of the tower (the teddy is the bell itself).
            if (mode == Mode.STRIKE) {
                for (int k = 0; k < PRIZE_AT.length; k++) {
                    if (k == 2) {
                        continue;
                    }
                    float y = base - (base - top) * Math.min(1f, PRIZE_AT[k] * bell / scaleMax);
                    paint.setColor(prizeTier > k ? PRIZE_COLOR[k] : shade(PRIZE_COLOR[k], 0.55f));
                    c.drawRect(cx - dp(40f), y - dp(1.5f), cx - dp(16f), y + dp(1.5f), paint);
                    label(c, PRIZE_NAME[k], cx - dp(44f), y + dp(4f), 8.5f,
                            prizeTier > k ? PRIZE_COLOR[k] : FAINT, Paint.Align.RIGHT);
                }
            }
            // Bell line.
            paint.setColor(rang ? 0xFFF5C518 : 0xFFF0B132);
            c.drawRect(cx - dp(40f), bellY - dp(2f), cx + dp(40f), bellY + dp(2f), paint);
            label(c, "BELL  " + bell + " W" + (mode == Mode.STRIKE ? "  =  TEDDY" : ""), cx - dp(44f),
                    bellY + dp(4f), 9f, TEXT, Paint.Align.RIGHT);
            // Record line: the best ever pull, so every go is measured against it.
            if (recordAtStart > 0) {
                float recY = base - (base - top) * Math.min(1f, recordAtStart / scaleMax);
                paint.setColor(0xFF35D0BA);
                for (float dx = cx - dp(70f); dx < cx + dp(70f); dx += dp(12f)) {
                    c.drawRect(dx, recY - dp(1.5f), dx + dp(7f), recY + dp(1.5f), paint);
                }
                label(c, "RECORD  " + Math.round(recordAtStart) + " W", cx + dp(74f), recY + dp(4f), 9f,
                        0xFF35D0BA, Paint.Align.LEFT);
            }
        } else if (mode == Mode.STEADY && mark > 0) {
            // The mark band: land inside it five times running.
            drawBand(c, cx, top, base, mark, steadyBand(), 0xFF35D0BA,
                    "MARK  " + mark + " W  ±" + Math.round(steadyBand()));
        } else if (mode == Mode.CALL && called > 0) {
            // The called band: five watts either side, and it is narrow on purpose.
            drawBand(c, cx, top, base, called, CALL_BAND, 0xFFF5C518,
                    "CALLED  " + called + " W  ±" + CALL_BAND);
        }
        // Bruno's mark and puck.
        if (mode == Mode.RIVAL && rivalSlammed) {
            float ry = base - (base - top) * rivalPuck;
            paint.setColor(0xFF6F8CFF);
            for (float dx = cx - dp(70f); dx < cx + dp(70f); dx += dp(12f)) {
                c.drawRect(dx, ry - dp(1.5f), dx + dp(7f), ry + dp(1.5f), paint);
            }
            float ly = ry + dp(4f);
            if (Math.abs(ry - bellY) < dp(14f)) {
                ly += dp(14f);
            }
            label(c, rivalName() + "  " + rivalMark + " W", cx - dp(76f), ly, 9.5f, 0xFF8FA6FF, Paint.Align.RIGHT);
            Fx.glow(c, cx, ry, dp(26f), 0x556F8CFF);
            paint.setColor(0xFF6F8CFF);
            c.drawRoundRect(cx - dp(18f), ry - dp(6f), cx + dp(18f), ry + dp(6f), dp(5f), dp(5f), paint);
        }
        // Bell itself.
        Fx.glow(c, cx, top - dp(6f), dp(40f), rang ? 0x99F5C518 : 0x33F5C518);
        paint.setColor(rang ? 0xFFFFE28A : 0xFFB8890B);
        c.drawCircle(cx, top - dp(6f), dp(16f), paint);
        if (mode == Mode.RIVAL) {
            drawStrongman(c, cx - dp(150f), base + dp(16f), cx - dp(45f), base);
        }
        // Puck.
        float py = base - (base - top) * puck;
        Fx.glow(c, cx, py, dp(30f), 0x66FF3B5C);
        paint.setColor(0xFFFF3B5C);
        c.drawRoundRect(cx - dp(20f), py - dp(7f), cx + dp(20f), py + dp(7f), dp(5f), dp(5f), paint);
        fx.draw(c);
        c.restore();

        // Outside the shake, because the roll they fly into is drawn outside it too - inside, a
        // ticket would land a few pixels off the roll on exactly the frames that shake hardest.
        drawFlyingTickets(c);
        drawTicketRoll(c, w, h);
        drawHud(c, w, h);
    }

    private void drawHud(Canvas c, float w, float h) {
        String big;
        String cap;
        int col;
        float hx = w * 0.22f;
        float hy = h * 0.30f;
        String again = "";
        if (phase == Phase.RESULT) {
            int left = (int) Math.max(0, Math.ceil(REARM_SECONDS - (sessionSeconds - resultAt)));
            again = "  ·  next go in " + left + " s, or tap";
        }
        if (mode == Mode.STRIKE) {
            if (phase == Phase.READY) {
                big = "MEGA PULL";
                cap = STROKES + " strokes, hardest you've got - ring the bell at " + bell + " W";
                col = DIM;
            } else if (phase == Phase.RESULT) {
                big = prizeTier > 0 ? PRIZE_NAME[prizeTier - 1] + "!" : peak + " W";
                cap = (prizeTier > 0 ? "you win it with " + peak + " W"
                        : "no prize - the mini plush was " + Math.round(PRIZE_AT[0] * bell) + " W") + again;
                col = prizeTier > 0 ? PRIZE_COLOR[prizeTier - 1] : DIM;
            } else {
                big = peak + " W";
                cap = strokesLeft + " stroke" + (strokesLeft == 1 ? "" : "s") + " left";
                if (prizeTier < PRIZE_AT.length) {
                    cap += "  ·  next: " + PRIZE_NAME[prizeTier] + " at " + Math.round(PRIZE_AT[prizeTier] * bell) + " W";
                }
                col = peak >= bell ? 0xFFF5C518 : TEXT;
            }
        } else if (mode == Mode.RIVAL) {
            if (phase == Phase.RIVAL_TURN) {
                big = rivalName() + "'S TURN";
                cap = "round " + rivalRound + " - watch his puck";
                col = 0xFF8FA6FF;
            } else if (phase == Phase.READY) {
                big = "YOUR TURN";
                cap = "beat " + rivalMark + " W in " + RIVAL_STROKES + " strokes";
                col = TEXT;
            } else if (phase == Phase.PULLING) {
                big = peak + " W";
                cap = strokesLeft + " stroke" + (strokesLeft == 1 ? "" : "s") + " left  ·  beat " + rivalMark + " W";
                col = peak > rivalMark ? ACCENT : TEXT;
            } else if (phase == Phase.ROUND_END) {
                big = roundYours ? "ROUND YOURS" : rivalName() + " TAKES IT";
                cap = peak + " W against " + rivalMark + " W";
                col = roundYours ? ACCENT : BAD;
            } else {
                big = success ? "YOU BEAT " + rivalName() + "!" : rivalName() + " WINS";
                cap = youWins + " - " + rivalWins + again;
                col = success ? 0xFFF5C518 : DIM;
            }
            bold(c, "YOU  " + youWins + "  -  " + rivalWins + "  " + rivalName(), hx, hy - dp(56f), 14f,
                    TEXT, Paint.Align.CENTER);
            label(c, "best of 3  ·  round " + rivalRound, hx, hy - dp(42f), 9f, FAINT, Paint.Align.CENTER);
        } else if (mode == Mode.CALL) {
            if (phase == Phase.READY) {
                big = called + " W";
                cap = "hit the called number within " + CALL_BAND + " W - " + CALL_LIVES + " lives";
                col = 0xFFF5C518;
            } else if (phase == Phase.RESULT) {
                big = callsHit + (callsHit == 1 ? " CALL HIT" : " CALLS HIT");
                cap = (callLives <= 0 ? "out of lives" : "out of strokes") + again;
                col = callsHit > 0 ? 0xFFF5C518 : DIM;
            } else {
                big = called + " W";
                cap = "last " + lastStrokeW + " W  ·  " + callsHit + " hit  ·  "
                        + (CALL_LIMIT - callStrokes) + " strokes left";
                col = 0xFFF5C518;
            }
            bold(c, "HIT " + callsHit + "   LIVES " + callLives, hx, hy - dp(56f), 14f, TEXT,
                    Paint.Align.CENTER);
            label(c, "stroke " + callStrokes + " of " + CALL_LIMIT, hx, hy - dp(42f), 9f, FAINT,
                    Paint.Align.CENTER);
        } else if (mode == Mode.TEN) {
            float recTen = bests.get("megapull.ten", 0f);
            if (phase == Phase.READY) {
                big = "TEN PULLS";
                cap = "ten strokes, every one counts - the score is the sum";
                col = DIM;
            } else if (phase == Phase.RESULT) {
                big = tenScore + "";
                int drop = Math.round((1f - tenFade()) * 100);
                cap = "ten pulls, " + (tenScore / TEN_PULLS) + " W average"
                        + (drop > 0 ? "  ·  faded " + drop + "%" : "  ·  no fade") + again;
                col = success ? 0xFFF5C518 : TEXT;
            } else {
                big = tenScore + "";
                int projected = tenCount > 0 ? tenScore * TEN_PULLS / tenCount : 0;
                cap = "pull " + (tenCount + 1) + " of " + TEN_PULLS + "  ·  on for " + projected
                        + (recTen > 0 ? "  ·  beat " + Math.round(recTen) : "");
                col = recTen > 0 && projected > recTen ? ACCENT : TEXT;
            }
            bold(c, "TOTAL", hx, hy - dp(56f), 14f, TEXT, Paint.Align.CENTER);
            label(c, recTen > 0 ? "best " + Math.round(recTen) : "no score yet", hx, hy - dp(42f), 9f,
                    FAINT, Paint.Align.CENTER);
        } else {
            if (phase == Phase.READY) {
                big = "STEADY";
                cap = "land " + STEADY_TARGET + " strokes in a row within "
                        + Math.round(STEADY_TOLERANCE * 100) + "% of your first";
                col = DIM;
            } else if (phase == Phase.RESULT) {
                big = success ? "FIVE IN A ROW!" : "best run " + bestStreak;
                cap = (success ? "in " + steadyStrokes + " strokes" : "out of strokes") + again;
                col = success ? 0xFFF5C518 : DIM;
            } else {
                big = lastStrokeW + " W";
                cap = "stroke " + steadyStrokes + " of " + STEADY_LIMIT;
                col = streak >= 3 ? ACCENT : TEXT;
            }
            // Five lamps: one per stroke in the current run.
            float lx = hx - dp(2 * 26f);
            for (int k = 0; k < STEADY_TARGET; k++) {
                boolean on = k < streak;
                float x = lx + k * dp(26f);
                float y = hy - dp(60f);
                if (on) {
                    Fx.glow(c, x, y, dp(20f), 0x6635D0BA);
                }
                paint.setColor(on ? 0xFF6BFFB8 : 0xFF2A2140);
                c.drawCircle(x, y, dp(8f), paint);
            }
        }
        bold(c, big, hx, hy, phase == Phase.READY && mode != Mode.RIVAL ? 26f
                : big.length() > 10 ? 30f : 44f, col, Paint.Align.CENTER);
        bold(c, cap, hx, hy + dp(22f), 10.5f, FAINT, Paint.Align.CENTER);
        String rec = "";
        if (mode == Mode.STEADY) {
            rec = bests.has("megapull.steady") ? "record: five in " + Math.round(bests.get("megapull.steady", 0)) + " strokes" : "";
        } else if (mode == Mode.RIVAL) {
            float wins = bests.get("megapull.wins", 0f);
            rec = "rung " + (rivalIdx + 1) + "/" + RIVALS.length
                    + (wins > 0 ? "  ·  matches won " + Math.round(wins) : "");
        } else if (mode == Mode.CALL) {
            rec = bests.has("megapull.called")
                    ? "record " + Math.round(bests.get("megapull.called", 0)) + " called" : "";
        } else if (mode == Mode.TEN) {
            rec = bests.has("megapull.peak")
                    ? "best single pull " + Math.round(bests.get("megapull.peak", 0)) + " W" : "";
        } else if (bests.has("megapull.peak")) {
            rec = "record " + Math.round(bests.get("megapull.peak", 0)) + " W";
        }
        label(c, rec, hx, hy + dp(40f), 9f, FAINT, Paint.Align.CENTER);

        if (pillsVisible()) {
            float pw = dp(96f);
            float ph = dp(34f);
            float gap = dp(8f);
            float x0 = hx - (pills.length * pw + (pills.length - 1) * gap) / 2f;
            float y0 = hy + dp(56f);
            for (int k = 0; k < pills.length; k++) {
                RectF r = pills[k];
                r.set(x0 + k * (pw + gap), y0, x0 + k * (pw + gap) + pw, y0 + ph);
                boolean sel = MODES[k] == mode;
                paint.setColor(sel ? ACCENT : 0x33FFFFFF);
                c.drawRoundRect(r, ph / 2f, ph / 2f, paint);
                bold(c, MODE_NAMES[k], r.centerX(), r.centerY() + dp(4.5f), 11.5f,
                        sel ? 0xFF0A0E14 : TEXT, Paint.Align.CENTER);
            }
        } else if (sessionSeconds < feedbackUntil) {
            bold(c, feedback, hx, hy + dp(70f), 14f, feedbackColor, Paint.Align.CENTER);
        }

        if (sessionSeconds < newBestUntil && ((int) (sessionSeconds * 6) % 2 == 0)) {
            bold(c, "NEW BEST!", w * 0.5f, h * 0.52f, 40f, 0xFF35D0BA, Paint.Align.CENTER);
        }
        // Live watts sit above the bell now: the right of the screen belongs to the prize shelf,
        // the ladder, the barker's sign or the ten-pull board depending on the mode.
        bold(c, (status == null ? 0 : status.watts) + " W", w * 0.5f, h * 0.055f, 26f, ACCENT,
                Paint.Align.CENTER);
        label(c, "NOW", w * 0.5f, h * 0.055f + dp(15f), 9f, FAINT, Paint.Align.CENTER);
    }
}
