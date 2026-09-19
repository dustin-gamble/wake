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
 * <p>Three ways to play, picked from pills drawn on the canvas (there are no header chips):
 * <ul>
 * <li><b>STRIKE</b> - the original five-stroke go, now for prizes on a shelf: a mini plush, a
 * bunny, a teddy at the bell and a giant bear above it. Tier heights are fractions of the bell, so
 * they follow the rower's own record. Best tier ever is {@code megapull.prize} (1-4).</li>
 * <li><b>RIVAL</b> - best of three against Bruno the strongman. He swings first and his puck sets
 * a mark; you get three strokes to beat it. His strength is set from your record (or your high
 * watts before there is one) and rubber-bands a little on the score. Match wins are counted in
 * {@code megapull.wins}.</li>
 * <li><b>STEADY</b> - the consistency test: land five strokes in a row within 9% of the mark. The
 * first stroke sets the mark, and a miss re-sets it to that stroke. Twenty strokes to do it; the
 * record is the fewest strokes it took, {@code megapull.steady}.</li>
 * </ul>
 * Fireworks go up when a record falls, and the crowd fills in and gets louder the closer you are
 * to the bell (or Bruno's mark, or five in a row).
 */
final class MegaPullGame extends GameView {

    private enum Phase { READY, RIVAL_TURN, PULLING, ROUND_END, RESULT }

    private enum Mode { STRIKE, RIVAL, STEADY }

    private static final Mode[] MODES = {Mode.STRIKE, Mode.RIVAL, Mode.STEADY};
    private static final String[] MODE_NAMES = {"STRIKE", "RIVAL", "STEADY"};
    /** Remembered for the life of the process, so going again keeps the mode you picked. */
    private static Mode lastMode = Mode.STRIKE;

    private static final int STROKES = 5;
    private static final int RIVAL_STROKES = 3;
    private static final int STEADY_TARGET = 5;
    private static final int STEADY_LIMIT = 20;
    private static final float STEADY_TOLERANCE = 0.09f;
    /** Seconds a result stays up before the next go arms itself, so nobody has to let go of the handle. */
    private static final double REARM_SECONDS = 8.0;
    private static final String RIVAL_NAME = "BRUNO";

    /** Prize tiers as fractions of the bell: mini plush, bunny, teddy (the bell itself), giant bear. */
    private static final float[] PRIZE_AT = {0.72f, 0.86f, 1.0f, 1.08f};
    private static final String[] PRIZE_NAME = {"MINI PLUSH", "BUNNY", "TEDDY", "GIANT BEAR"};
    private static final int[] PRIZE_COLOR = {0xFFFF9EC4, 0xFF8FC8FF, 0xFFC8915A, 0xFF8A5A33};
    private static final float[] PRIZE_X = {0.645f, 0.715f, 0.795f, 0.895f};
    private static final float[] PRIZE_SIZE = {30f, 42f, 56f, 90f};

    private static final int[] FIREWORK = {0xFFFF5A7A, 0xFFF5C518, 0xFF6BD8FF, 0xFF35D0BA,
            0xFFB78CFF, 0xFFFFFFFF};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Particles sparks = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final android.graphics.Path tent = new android.graphics.Path();
    private final RectF oval = new RectF();
    private final RectF[] pills = {new RectF(), new RectF(), new RectF()};
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

    // Rival (RIVAL).
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
        resetGo();
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
        if (mode == Mode.STEADY) {
            scaleMax = (float) Math.max(30.0, profile.highWatts() * 1.35);
        } else if (mode == Mode.RIVAL) {
            startRivalTurn();
        }
    }

    /** Bruno steps up: sets his mark for this round, then swings (animated in render). */
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
        // Just under your best, so a real effort wins; leading makes him dig deeper, trailing
        // eases him off - the match should usually go the distance.
        double base = recordAtStart > 0 ? recordAtStart * 0.92 : profile.highWatts() * 1.1;
        double adj = 1.0 + 0.05 * (youWins - rivalWins);
        double m = base * adj * (0.95 + Math.random() * 0.1);
        rivalMark = (int) Math.round(Math.max(profile.typicalWatts(), m));
        scaleMax = Math.max(bell, rivalMark) * 1.15f;
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
        if (mode == Mode.STEADY) {
            return; // the steady puck follows windowPeak in render and is judged per stroke
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
        if (mode == Mode.STEADY) {
            judgeSteady(strokeW);
            return;
        }
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

    private void endStrike() {
        phase = Phase.RESULT;
        resultAt = sessionSeconds;
        success = prizeTier > 0;
        bests.recordHighest("megapull.peak", peak);
        if (prizeTier > 0) {
            boolean had = bests.has("megapull.prize");
            boolean better = bests.recordHighest("megapull.prize", prizeTier);
            if ((better && had) || prizeTier == PRIZE_AT.length) {
                launchFireworks(5.0);
            }
            hopTier = prizeTier;
        }
    }

    private void endRound() {
        roundYours = peak > rivalMark;
        if (roundYours) {
            youWins++;
            fx.burst(getWidth() * 0.5f, getHeight() * 0.45f, 50, dp(260f), 1.0f, dp(4f), ACCENT, true);
            say("ROUND YOURS  " + peak + " vs " + rivalMark + " W", ACCENT);
        } else {
            rivalWins++;
            shake.kick(dp(8f));
            say(RIVAL_NAME + " TAKES IT  " + peak + " vs " + rivalMark + " W", BAD);
        }
        phase = Phase.ROUND_END;
        roundEndAt = sessionSeconds;
        bests.recordHighest("megapull.peak", peak);
    }

    private void judgeSteady(int sw) {
        if (sw <= 0) {
            return; // no reading landed for that stroke; do not judge it on nothing
        }
        steadyStrokes++;
        lastStrokeW = sw;
        puckHoldUntil = sessionSeconds + 0.9;
        if (mark <= 0) {
            mark = sw;
            streak = 1;
            say("MARK SET  " + sw + " W - now match it", ACCENT);
        } else if (Math.abs(sw - mark) <= steadyBand()) {
            streak++;
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
        } else if (steadyStrokes >= STEADY_LIMIT) {
            phase = Phase.RESULT;
            resultAt = sessionSeconds;
            success = false;
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
            boolean won = prizeTier > k;
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

    /** Bruno: striped singlet, handlebar moustache, a sledgehammer, and opinions about losing. */
    private void drawStrongman(Canvas c, float x, float feet, float padX, float padY) {
        double t = sessionSeconds;
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
        paint.setColor(0xFFC62F3A);
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
                    launchFireworks(4.0);
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
        if (mode == Mode.STEADY && (phase == Phase.READY || phase == Phase.PULLING)) {
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
        if (mode == Mode.STRIKE) {
            drawPrizes(c, w, h);
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
        if (mode != Mode.STEADY) {
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
        } else if (mark > 0) {
            // The mark band: land inside it five times running.
            float band = steadyBand();
            float y0 = base - (base - top) * Math.min(1f, (mark + band) / scaleMax);
            float y1 = base - (base - top) * Math.min(1f, (mark - band) / scaleMax);
            float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 4);
            paint.setColor(((int) (0x30 + 0x30 * pulse) << 24) | 0x0035D0BA);
            c.drawRect(cx - dp(70f), y0, cx + dp(70f), y1, paint);
            paint.setColor(0xFF35D0BA);
            c.drawRect(cx - dp(70f), y0 - dp(1f), cx + dp(70f), y0 + dp(1f), paint);
            c.drawRect(cx - dp(70f), y1 - dp(1f), cx + dp(70f), y1 + dp(1f), paint);
            label(c, "MARK  " + mark + " W  ±" + Math.round(band), cx - dp(76f), (y0 + y1) * 0.5f + dp(4f),
                    9.5f, 0xFF35D0BA, Paint.Align.RIGHT);
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
            label(c, RIVAL_NAME + "  " + rivalMark + " W", cx - dp(76f), ly, 9.5f, 0xFF8FA6FF, Paint.Align.RIGHT);
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
                big = RIVAL_NAME + "'S TURN";
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
                big = roundYours ? "ROUND YOURS" : RIVAL_NAME + " TAKES IT";
                cap = peak + " W against " + rivalMark + " W";
                col = roundYours ? ACCENT : BAD;
            } else {
                big = success ? "YOU BEAT " + RIVAL_NAME + "!" : RIVAL_NAME + " WINS";
                cap = youWins + " - " + rivalWins + again;
                col = success ? 0xFFF5C518 : DIM;
            }
            bold(c, "YOU  " + youWins + "  -  " + rivalWins + "  " + RIVAL_NAME, hx, hy - dp(56f), 14f,
                    TEXT, Paint.Align.CENTER);
            label(c, "best of 3  ·  round " + rivalRound, hx, hy - dp(42f), 9f, FAINT, Paint.Align.CENTER);
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
            rec = wins > 0 ? "matches won " + Math.round(wins) : "";
        } else if (bests.has("megapull.peak")) {
            rec = "record " + Math.round(bests.get("megapull.peak", 0)) + " W";
        }
        label(c, rec, hx, hy + dp(40f), 9f, FAINT, Paint.Align.CENTER);

        if (pillsVisible()) {
            float pw = dp(104f);
            float ph = dp(34f);
            float gap = dp(10f);
            float x0 = hx - (3 * pw + 2 * gap) / 2f;
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
        bold(c, (status == null ? 0 : status.watts) + " W", w * 0.78f, h * 0.30f, 26f, ACCENT, Paint.Align.CENTER);
        label(c, "NOW", w * 0.78f, h * 0.30f + dp(16f), 9f, FAINT, Paint.Align.CENTER);
    }
}
