package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Wave Rider: match the wave, not beat it.
 *
 * <p>Every other game here rewards pulling harder. This one punishes both ends. Your position on
 * the face is the running integral of your speed minus the wave's, so matching its pace holds you
 * still: fall behind and the lip breaks over you, push too far ahead and you run out onto the flat
 * shoulder and lose the wave. The pocket - just ahead of the curl - scores double, and the wave
 * changes pace in sets so the target keeps moving.
 *
 * <p>Barrel sections throw the lip right over you; hold the pocket through one for a big score.
 *
 * <p>3.22 additions, all at the rower's request:
 * <ul>
 *   <li><b>Tricks.</b> Let the wave carry you up toward the lip (the TRICK zone), then surge past
 *   the profile's strong-stroke watts and the surfer launches off the top. A surge also pushes you
 *   down the face, so it is a real trade: ride high for the trick, recover the pocket after.</li>
 *   <li><b>Big sets.</b> Every so often a set of three bigger waves is announced six seconds out,
 *   swells marching in from the horizon. The wave stands taller and runs a little quicker; points
 *   and tricks are worth more, and riding the whole set through is a bonus.</li>
 *   <li><b>The lineup.</b> Four other surfers sit out back waiting their turn. When your ride ends
 *   the next one in line takes the wave, and their best rides are the board you are ranked on.</li>
 *   <li><b>Medals</b> for ride length: bronze 0:30, silver 0:45 (a median rower's ride), gold 1:10.</li>
 *   <li><b>Surf spots</b> with different wave speeds, picked with the chip at top left between
 *   rides. The speeds scale the retuned wave by only -5% .. +7%, so the ride stays winnable.</li>
 * </ul>
 *
 * <p>3.23 turns it into a contest, again at the rower's request:
 * <ul>
 *   <li><b>Judged waves and a heat score.</b> Every ride is scored 0-10 by three judges - length,
 *   tricks, barrels, time in the pocket, the stack you built, the take-off - and your <b>best
 *   three</b> waves add up to the heat score, exactly as a real heat is scored.</li>
 *   <li><b>Heats against the lineup.</b> A heat is five of your waves. KAI, MAYA, DUKE and LANI
 *   surf it too - the one on the wave after your ride is scored in front of you, the rest catch
 *   waves out the back - and at the end the heat is placed 1st to 5th.</li>
 *   <li><b>Bigger spots unlocked by tricks.</b> REEF PASS, BOMBORA and MAVERICK BAY open at 6, 16
 *   and 30 lifetime landed tricks (persisted). Bigger means a taller wave the judges pay more for,
 *   never a faster one.</li>
 *   <li><b>Priority.</b> After a ride you paddle back out and wait your turn; the countdown ring
 *   shows it. Take off with priority and the wave gets a clean-take-off bonus <i>and</i> eight
 *   seconds of extra rail grip, which is how you recover a ride that is sliding. Surge while the
 *   light is red and you drop in on the lineup: interference, half score.</li>
 *   <li><b>The stack.</b> Tricks and barrels stack into a multiplier that drains in twelve
 *   seconds - and alternating them (a trick after a barrel, or the reverse) counts double.</li>
 *   <li><b>Saving it.</b> Getting caught by the lip no longer ends the ride outright: you hang in
 *   the curl for 1.6 s and a hard pull drives you back down the face. Twice per ride.</li>
 * </ul>
 */
final class WaveRiderGame extends GameView {

    private enum Phase { WAITING, RIDING, WIPEOUT, KICKOUT }

    /* ---------- surf spots ---------- */
    private static final String[] SPOT_NAMES =
            {"GLASS COVE", "POINT BREAK", "REEF PASS", "BOMBORA", "MAVERICK BAY"};
    private static final String[] SPOT_KEYS = {"cove", "point", "reef", "bombora", "maverick"};
    private static final String[] SPOT_BLURB = {
            "slow, friendly wave", "the classic", "quick wave, barrels often",
            "fastest wave, big sets", "the biggest wave on the coast"};
    /** Multiplies the whole wave speed. Modest on purpose: base 0.90 x typical is the tuned ride. */
    private static final float[] SPOT_SPEED = {0.95f, 1.0f, 1.04f, 1.07f, 1.05f};
    /** Mean seconds between big sets. */
    private static final float[] SPOT_SET_GAP = {60f, 48f, 48f, 34f, 30f};
    /** Minimum seconds between barrels. */
    private static final float[] SPOT_BARREL_GAP = {30f, 22f, 15f, 24f, 16f};
    /**
     * How much taller the wave stands at each spot. An unlocked spot is <b>bigger</b>, never
     * faster - the ride is already ending at the lip often enough without a quicker wave.
     */
    private static final float[] SPOT_SIZE = {1.0f, 1.0f, 1.06f, 1.14f, 1.28f};
    /** The judges pay more for a bigger wave. Multiplies the wave score and the flowing points. */
    private static final float[] SPOT_POINTS = {1.0f, 1.06f, 1.12f, 1.2f, 1.32f};
    /** Lifetime landed tricks needed to unlock each spot; persisted as {@code surf.tricksTotal}. */
    private static final int[] SPOT_UNLOCK = {0, 0, 6, 16, 30};
    private static final int[][] SPOT_SKY = {
            {0xFF3B5C8A, 0xFFF7C98B}, {0xFF2B3F70, 0xFFF3A469},
            {0xFF1F4E79, 0xFF9FE3F0}, {0xFF2A2440, 0xFFD9786A},
            {0xFF223142, 0xFFB9CBD6}};
    private static final int[][] SPOT_SEA = {
            {0xFF2A7FA0, 0xFF0E3A52}, {0xFF1E5C86, 0xFF0A2A44},
            {0xFF1C8FA6, 0xFF0A3D4E}, {0xFF18476B, 0xFF081E33},
            {0xFF16394F, 0xFF05121F}};
    private static final int[][] SPOT_FACE = {
            {0xFF2A86B8, 0xFF10496A}, {0xFF1B6FA8, 0xFF0D3C5E},
            {0xFF1FA3B8, 0xFF0B5566}, {0xFF16547E, 0xFF08263C},
            {0xFF14607F, 0xFF061C2E}};
    private static final int[] SPOT_LIP = {0xFF93D3F2, 0xFF7FC6EE, 0xFF8EE8F0, 0xFF6FA8CF, 0xFFCFEAF6};

    /* ---------- big sets ---------- */
    private static final double SET_WARN = 6;
    private static final double SET_WAVE = 4;       // three waves of four seconds
    private static final double SET_LEN = SET_WAVE * 3;

    /* ---------- tricks ---------- */
    private static final String[] TRICKS = {"AIR", "360 AIR", "ALLEY-OOP", "SUPERMAN", "RODEO FLIP"};
    private static final double TRICK_LEN = 1.3;
    private static final double TRICK_COOLDOWN = 1.5;

    /* ---------- medals ---------- */
    private static final float[] MEDAL_AT = {30f, 45f, 70f};
    private static final String[] MEDAL_NAMES = {"BRONZE", "SILVER", "GOLD"};
    private static final int[] MEDAL_COLORS = {0xFFCD7F32, 0xFFC9D3DD, 0xFFF5C518};

    /* ---------- the lineup ---------- */
    private static final String[] NPC_NAMES = {"KAI", "MAYA", "DUKE", "LANI"};
    private static final int[] NPC_COLORS = {0xFFFF8A5B, 0xFFB38CFF, 0xFF7CE38B, 0xFFFF6FA8};
    private static final double NPC_SHOW = 7;
    /** A median rower's ride on the tuned wave (CLAUDE.md: ~45 s); the lineup rides around it. */
    private static final float NPC_TYPICAL_RIDE = 45f;
    private static final String[] PLACES = {"1ST", "2ND", "3RD", "4TH", "5TH"};
    /**
     * Mean wave score each of the lineup gives the judges, set against the scoring below rather
     * than by feel. Each surfs about five waves a heat (one seeded, one in front of you, the rest
     * out the back), and only their best three count, so a mean of 6.0 with the +/-38% spread here
     * totals about 20 - not 18. Simulated over 20,000 heats at four and five waves each: DUKE
     * lands 19.4-20.4, KAI 18.1-19.0, MAYA 15.9-16.6, LANI 14.9-15.6.
     *
     * <p>Against that: a 45 s ride with a trick, a barrel and half of it in the pocket scores 8.0,
     * so three of those wins the heat at 24. A 45 s ride ending in a wipeout scores 6.2 (18.6 - a
     * podium), and three 25 s wipeouts - what the demo rower produces - score 11 and come last.
     * That is the intended gradient: the heat is winnable by riding well, never by turning up.
     */
    private static final float[] NPC_SKILL = {5.6f, 4.9f, 6.0f, 4.6f};

    /* ---------- the heat ---------- */
    /** Waves you are given in a heat. Five rides at ~40 s plus the paddle back is a real piece. */
    private static final int HEAT_WAVES = 5;
    /** Only your best three count, as in a real heat. */
    private static final int COUNTED = 3;
    private static final double JUDGE_CARD = 0.42;      // seconds between the judges' cards
    private static final double JUDGE_SHOW = 2.6;       // how long the judging panel holds

    /* ---------- priority ---------- */
    /** Seconds you paddle back out before the wave is yours. The lineup's ride is 7 s. */
    private static final double PRIORITY_WAIT = 8;
    /** Extra pull toward the pocket for the first seconds of a ride taken with priority. */
    private static final double GRIP_SECONDS = 8;

    /* ---------- saving it ---------- */
    /** How long you hang in the lip with a chance to pull out of it. */
    private static final double SAVE_WINDOW = 1.6;
    private static final int SAVES_PER_RIDE = 2;

    /* ---------- the stack ---------- */
    private static final double STACK_WINDOW = 12;
    private static final int STACK_MAX = 8;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles spray = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();

    private Phase phase = Phase.WAITING;
    private float position;          // -1 caught by the lip .. +1 out on the shoulder
    private double rideSeconds;
    private double bestRide;
    private double score;
    private int rides;
    private int barrels;
    private double barrelUntil;
    private double nextBarrelAt;
    private double endedAt;
    private float sprayAccum;
    /* Life around the wave. */
    private final float[] gullX = {0.2f, 0.55f, 0.8f};
    /**
     * The open ocean between the horizon and the wave is the one band on this screen that was
     * genuinely empty: over a 10-frame mid-ride burst it showed 13 changed pixels, against 147
     * for the gulls and 1171 for the wave face. (The gulls, sailboats and glitter all animate
     * already - thin sparse sprites just do not move a cell average.)
     */
    private float swellPhase;
    private float dolphinT = -6f;
    private double dolphinAt = 8;
    private boolean wasPocket;
    private final Fx.Particles wash = new Fx.Particles();
    private String popup = "";
    private int popupColor = 0xFFF5C518;
    private double popupUntil;
    private int lastScoreMark;

    /* Spot. */
    private int spot = 1;
    private final float[] spotBest = new float[SPOT_NAMES.length];
    private float spotLeft, spotTop, spotRight, spotBottom;
    /** Lifetime landed tricks - the unlock currency, persisted so spots stay open. */
    private int lifetimeTricks;

    /* Judging and the heat. */
    private final float[] judge = new float[3];
    /** Your best three waves this heat, and the lineup's. Index 4 of the counts is you. */
    private final float[] heatMine = new float[COUNTED];
    private final float[][] heatNpc = new float[4][COUNTED];
    private final int[] heatWaves = new int[5];
    private final float[] lastHeat = new float[5];
    private int heatNumber = 1;
    private int heatRides;
    private float waveScore;
    private float bestWave;
    private float bestHeat;
    private int heatsWon;
    private double judgeAt = -100;
    private double heatShowFrom;
    private double heatShowUntil;
    private int heatPlace = -1;
    private final int[] heatOrder = new int[5];

    /* This ride. */
    private int rideBarrels;
    private double ridePocketSeconds;
    private double barrelPocketSeconds;
    private boolean wasBarrel;
    private boolean burnedRide;
    private boolean priorityRide;
    private double gripUntil;
    private int rideSaves;
    private int sessionSaves;
    private double saveUntil;

    /* Priority. */
    private double priorityAt;
    private float paddleOut;

    /* The stack. */
    private int stack = 1;
    private int peakStack = 1;
    private double stackUntil;
    private boolean stackHasTrick;
    private boolean stackHasBarrel;
    private double comboUntil;
    private final RectF ring = new RectF();

    /* Sets. */
    private double nextSetAt;
    private float setLift;
    private int setWaveShown;
    private boolean setClean;
    private boolean setActive;
    private int setsRidden;

    /* Tricks. */
    private double trickStart = -100;
    private boolean trickPending;
    private int trickName;
    private int rideTricks;
    private int sessionTricks;
    /** Re-armed once power drops back under the surge line, so one held reading is one trick. */
    private boolean surgeArmed = true;

    /* Medals. */
    private final int[] medalCount = new int[3];
    private int rideMedal = -1;

    /* Lineup. */
    private final int[] queue = {0, 1, 2, 3};
    private int npcRider = -1;
    private double npcRideStart;
    private float npcRideLen;
    private double cheerUntil;
    private final int[] board = new int[5];      // leaderboard order, 4 = you
    private String endNote = "";

    /* Cached gradients - rebuilt only when the size or the spot changes. */
    private LinearGradient skyGrad;
    private LinearGradient seaGrad;
    private LinearGradient faceGrad;
    private float gradW = -1;
    private float gradH = -1;
    private int gradSpot = -1;

    WaveRiderGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        phase = Phase.WAITING;
        position = 0f;
        rideSeconds = 0;
        bestRide = 0;
        score = 0;
        rides = 0;
        barrels = 0;
        barrelUntil = 0;
        nextBarrelAt = 18;
        lastScoreMark = 0;
        wasPocket = false;
        lifetimeTricks = Math.max(0, Math.round(bests.get("surf.tricksTotal", 0f)));
        spot = Math.max(0, Math.min(SPOT_NAMES.length - 1, Math.round(bests.get("surf.spot", 1f))));
        if (!unlocked(spot)) {
            spot = 1;
        }
        for (int i = 0; i < spotBest.length; i++) {
            spotBest[i] = 0f;
        }
        heatNumber = 1;
        heatRides = 0;
        heatPlace = -1;
        heatsWon = 0;
        bestWave = 0f;
        bestHeat = 0f;
        waveScore = 0f;
        judgeAt = -100;
        heatShowFrom = 0;
        heatShowUntil = 0;
        clearHeat();
        rideBarrels = 0;
        ridePocketSeconds = 0;
        barrelPocketSeconds = 0;
        wasBarrel = false;
        burnedRide = false;
        priorityRide = false;
        gripUntil = 0;
        rideSaves = 0;
        sessionSaves = 0;
        saveUntil = 0;
        // A short first wait, so the priority light is something the rower sees before ride one.
        priorityAt = 4;
        paddleOut = 0f;
        stack = 1;
        peakStack = 1;
        stackUntil = 0;
        stackHasTrick = false;
        stackHasBarrel = false;
        comboUntil = 0;
        nextSetAt = 30 + Math.random() * 12;
        setLift = 0f;
        setWaveShown = 0;
        setActive = false;
        setClean = false;
        setsRidden = 0;
        trickStart = -100;
        trickPending = false;
        rideTricks = 0;
        sessionTricks = 0;
        surgeArmed = true;
        for (int i = 0; i < medalCount.length; i++) {
            medalCount[i] = 0;
        }
        rideMedal = -1;
        // Everyone in the lineup opens the heat with one wave already scored, so there is a board
        // to chase from your very first ride rather than an empty one.
        for (int i = 0; i < queue.length; i++) {
            queue[i] = i;
            scoreNpcWave(i);
        }
        npcRider = -1;
        endNote = "";
    }

    @Override
    protected void onStop() {
        bests.recordHighest("surf.ride", (float) bestRide);
        bests.recordHighest("surf.score", (float) score);
        if (sessionTricks > 0) {
            bests.recordHighest("surf.tricks", sessionTricks);
        }
        if (bestWave > 0f) {
            bests.recordHighest("surf.wave", bestWave);
        }
        // A heat abandoned part way still counts what it is worth so far - the rower is not
        // punished for the session ending mid-heat.
        float heatNow = Math.max(bestHeat, total(heatMine));
        if (heatNow > 0f) {
            bests.recordHighest("surf.heat", heatNow);
        }
        if (heatsWon > 0) {
            bests.putFloat("surf.heatsWon", bests.get("surf.heatsWon", 0f) + heatsWon);
        }
        bests.putFloat("surf.tricksTotal", lifetimeTricks);
        for (int i = 0; i < spotBest.length; i++) {
            if (spotBest[i] > 0f) {
                bests.recordHighest("surf.ride." + SPOT_KEYS[i], spotBest[i]);
            }
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        // Surge at the top of the wave: a reading well above this rower's typical power while
        // riding high on the face launches a trick. Magnitudes come from here, never onStroke.
        // The S4 holds a reading until the next one, so a single held surge must not fire twice.
        int watts = s.watts;
        int surge = surgeWatts();
        if (watts < surge) {
            surgeArmed = true;
        }
        if (phase == Phase.WAITING && driving && boat.value() > 1.0f) {
            if (sessionSeconds >= priorityAt) {
                startRide(false);
            } else if (surgeArmed && watts >= dropInWatts()) {
                // Priority is not yours yet and you sprinted anyway: a drop-in on the lineup.
                surgeArmed = false;
                startRide(true);
            }
            return;
        }
        // Caught by the lip but not over yet: a hard pull drives you back down the face.
        if (phase == Phase.RIDING && saveUntil > 0 && watts >= surge) {
            rescue();
            return;
        }
        if (phase == Phase.RIDING && surgeArmed && !trickPending && rideSeconds > 3
                && sessionSeconds - trickStart > TRICK_LEN + TRICK_COOLDOWN
                && inTrickZone() && watts >= surge) {
            surgeArmed = false;
            trickPending = true;
            trickStart = sessionSeconds;
            trickName = Math.min(rideTricks, TRICKS.length - 1);
            if (rideTricks >= TRICKS.length) {
                trickName = (int) (Math.random() * TRICKS.length);
            }
            shake.kick(dp(5f));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float x = e.getX();
            float y = e.getY();
            if (phase != Phase.RIDING && x >= spotLeft && x <= spotRight
                    && y >= spotTop && y <= spotBottom) {
                // Cycle only through what is unlocked; spot 0 always is, so this terminates.
                do {
                    spot = (spot + 1) % SPOT_NAMES.length;
                } while (!unlocked(spot));
                bests.putFloat("surf.spot", spot);
                nextBarrelAt = sessionSeconds + SPOT_BARREL_GAP[spot];
                if (!setActive) {
                    nextSetAt = Math.max(nextSetAt, sessionSeconds + SET_WARN + 4);
                }
                showPopup(SPOT_NAMES[spot] + " - " + SPOT_BLURB[spot], ACCENT, 2.0);
                return true;
            }
            // The judges' cards are the point of the ride ending - do not let a stray tap skip
            // them, nor the heat result: the auto-continue below waits for heatShowUntil, and a
            // tap that did not would drop the priority ring in underneath the result panel.
            if ((phase == Phase.WIPEOUT || phase == Phase.KICKOUT)
                    && sessionSeconds - endedAt > 1.8 && sessionSeconds >= heatShowUntil) {
                phase = Phase.WAITING;
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    /** Watts that count as a surge: above the profile's strong strokes, not a guessed constant. */
    private int surgeWatts() {
        double typical = profile.typicalWatts();
        double high = profile.highWatts();
        // Floor: an empty or corrupt profile must not make every reading (even 0 W) a surge.
        return (int) Math.max(60, Math.round(Math.max(typical * 1.2, high * 0.95)));
    }

    /**
     * Watts that count as deliberately dropping in without priority.
     *
     * <p>Deliberately well clear of {@link #surgeWatts()}: on this machine the surge line lands
     * near the rower's p90 (162 W against a 129 W median), and the S4 reports instantaneous power,
     * so a normal paddle back out crosses it several times in an eight-second wait. Burning the
     * lineup has to be a sprint the rower chose, not a stroke they happened to take.
     */
    private int dropInWatts() {
        return Math.round(surgeWatts() * 1.25f);
    }

    /** The upper face, from just above the pocket's centre to just short of the curl. */
    private boolean inTrickZone() {
        return position > -0.9f && position < -0.1f;
    }

    private boolean tricking() {
        return trickPending && sessionSeconds - trickStart < TRICK_LEN;
    }

    private float npcRideLength() {
        return NPC_TYPICAL_RIDE * (0.55f + (float) Math.random() * 0.8f);
    }

    /* ---------- unlocks ---------- */

    private boolean unlocked(int which) {
        return lifetimeTricks >= SPOT_UNLOCK[which];
    }

    /** The next spot still locked, or -1 when everything is open. */
    private int nextLocked() {
        for (int i = 0; i < SPOT_UNLOCK.length; i++) {
            if (!unlocked(i)) {
                return i;
            }
        }
        return -1;
    }

    /** One more trick in the bank; a spot may fall open because of it. */
    private void bankTrick() {
        lifetimeTricks++;
        for (int i = 0; i < SPOT_UNLOCK.length; i++) {
            if (SPOT_UNLOCK[i] == lifetimeTricks) {
                // Written through immediately: an unlock must survive the app dying mid-session.
                bests.putFloat("surf.tricksTotal", lifetimeTricks);
                showPopup(SPOT_NAMES[i] + " UNLOCKED - " + SPOT_BLURB[i], 0xFF9FE3F0, 3.0);
                spray.burst(getWidth() * 0.5f, getHeight() * 0.34f, 50, dp(240f), 1.1f, dp(3.5f),
                        0xFF9FE3F0, true);
                shake.kick(dp(8f));
                cheerUntil = sessionSeconds + 2.5;
            }
        }
    }

    /* ---------- the stack ---------- */

    /** Points multiplier from the stack: x1 at rest, x4.5 at the cap. */
    private float stackMul() {
        return 1f + (stack - 1) * 0.5f;
    }

    /**
     * A trick or a barrel goes on the stack. Alternating them is worth double - that is the whole
     * point of stacking the two rather than repeating one.
     *
     * @return true when this one completed a combo and took the banner, so the caller does not
     *         overwrite it with its own popup
     */
    private boolean addStack(boolean trick) {
        boolean combo = trick ? stackHasBarrel : stackHasTrick;
        stack = Math.min(STACK_MAX, stack + (combo ? 2 : 1));
        peakStack = Math.max(peakStack, stack);
        stackUntil = sessionSeconds + STACK_WINDOW;
        if (trick) {
            stackHasTrick = true;
        } else {
            stackHasBarrel = true;
        }
        if (combo) {
            comboUntil = sessionSeconds + 1.8;
            showPopup((trick ? "BARREL + TRICK" : "TRICK + BARREL") + " COMBO  x" + stack,
                    0xFFF5C518, 1.8);
            shake.kick(dp(5f));
        }
        return combo;
    }

    /* ---------- rides ---------- */

    /** Take off. {@code burned} means you went without priority: interference, half score. */
    private void startRide(boolean burned) {
        phase = Phase.RIDING;
        position = 0f;
        rideSeconds = 0;
        rides++;
        rideTricks = 0;
        rideBarrels = 0;
        ridePocketSeconds = 0;
        barrelPocketSeconds = 0;
        wasBarrel = false;
        rideMedal = -1;
        rideSaves = 0;
        saveUntil = 0;
        stack = 1;
        peakStack = 1;
        stackUntil = 0;
        stackHasTrick = false;
        stackHasBarrel = false;
        burnedRide = burned;
        priorityRide = !burned;
        gripUntil = burned ? 0 : sessionSeconds + GRIP_SECONDS;
        judgeAt = -100;
        paddleOut = 0f;
        float gap = SPOT_BARREL_GAP[spot];
        nextBarrelAt = sessionSeconds + gap * 0.64 + Math.random() * 12;
        finishNpcRide();
        if (burned) {
            showPopup("DROPPED IN ON THE LINEUP - INTERFERENCE", BAD, 2.4);
            shake.kick(dp(10f));
        } else {
            showPopup("PRIORITY - CLEAN TAKE-OFF", ACCENT, 1.6);
        }
    }

    /** Pull out of the lip: the recovery that keeps a sliding ride alive. */
    private void rescue() {
        saveUntil = 0;
        rideSaves++;
        sessionSaves++;
        position = -0.55f;
        shake.kick(dp(14f));
        spray.burst(getWidth() * 0.66f, getHeight() * 0.45f, 34, dp(220f), 0.8f, dp(3.5f),
                0xDDEAF6FF, true);
        showPopup("SAVED IT!", ACCENT, 1.6);
        cheerUntil = sessionSeconds + 2.0;
    }

    /* ---------- judging and the heat ---------- */

    private static float total(float[] top) {
        float t = 0f;
        for (int i = 0; i < top.length; i++) {
            t += top[i];
        }
        return t;
    }

    /** Keeps the best three: a new wave replaces the weakest of them if it beats it. */
    private static void addWave(float[] top, float v) {
        int worst = 0;
        for (int i = 1; i < top.length; i++) {
            if (top[i] < top[worst]) {
                worst = i;
            }
        }
        if (v > top[worst]) {
            top[worst] = v;
        }
    }

    private void clearHeat() {
        for (int i = 0; i < COUNTED; i++) {
            heatMine[i] = 0f;
            for (int n = 0; n < heatNpc.length; n++) {
                heatNpc[n][i] = 0f;
            }
        }
        for (int i = 0; i < heatWaves.length; i++) {
            heatWaves[i] = 0;
        }
    }

    /** A wave from one of the lineup, scored the way the judges score yours. */
    private float npcWave(int who) {
        float v = NPC_SKILL[who] * (0.62f + (float) Math.random() * 0.76f)
                * (0.94f + SPOT_POINTS[spot] * 0.06f);
        return Math.max(0.4f, Math.min(9.6f, Math.round(v * 10f) / 10f));
    }

    private void scoreNpcWave(int who) {
        addWave(heatNpc[who], npcWave(who));
        heatWaves[who]++;
    }

    /**
     * Three judges score the wave out of ten. Everything the ride was made of is in here, which is
     * what makes a short ride with a trick and a barrel worth more than a long flat one.
     */
    private void judgeRide(Phase how) {
        double dur = Math.min(5.0, rideSeconds / 14.0);
        double tricksPart = Math.min(3.0, rideTricks * 0.9);
        double barrelPart = Math.min(2.5, rideBarrels * 1.2);
        double pocketPart = rideSeconds > 1
                ? Math.min(1.5, (ridePocketSeconds / rideSeconds) * 1.5) : 0;
        double stackPart = Math.min(1.5, (peakStack - 1) * 0.25);
        double raw = (dur + tricksPart + barrelPart + pocketPart + stackPart) * SPOT_POINTS[spot];
        if (priorityRide) {
            raw += 1.0;                     // clean take-off
        }
        if (burnedRide) {
            raw *= 0.5;                     // interference
        } else if (how == Phase.WIPEOUT) {
            raw *= 0.78;                    // the lip got you
        }
        raw = Math.max(0, Math.min(10, raw));
        float sum = 0f;
        for (int i = 0; i < judge.length; i++) {
            float v = (float) (raw + (Math.random() - 0.5) * 0.8);
            v = Math.max(0f, Math.min(10f, Math.round(v * 10f) / 10f));
            judge[i] = v;
            sum += v;
        }
        waveScore = Math.round(sum / judge.length * 10f) / 10f;
        bestWave = Math.max(bestWave, waveScore);
        addWave(heatMine, waveScore);
        heatWaves[4]++;
        heatRides++;
        judgeAt = sessionSeconds;
    }

    /** Five waves surfed: place the heat, then reset for the next one. */
    private void finishHeat() {
        float mine = total(heatMine);
        int place = 0;
        for (int i = 0; i < heatNpc.length; i++) {
            lastHeat[i] = total(heatNpc[i]);
            if (lastHeat[i] > mine) {
                place++;
            }
        }
        lastHeat[4] = mine;
        heatPlace = place;
        bestHeat = Math.max(bestHeat, mine);
        if (place == 0) {
            heatsWon++;
            spray.burst(getWidth() * 0.5f, getHeight() * 0.32f, 70, dp(280f), 1.3f, dp(4f),
                    0xFFF5C518, true);
            cheerUntil = sessionSeconds + 4.5;
        }
        // The judging cards get their moment first, then the result board takes the screen.
        heatShowFrom = sessionSeconds + JUDGE_SHOW;
        heatShowUntil = heatShowFrom + 7;
        priorityAt = Math.max(priorityAt, heatShowUntil - 2.5);
        heatNumber++;
        heatRides = 0;
        clearHeat();
    }

    /**
     * The wave's own pace: a base that drifts, plus sets that push it along. Scaled to the rower's
     * typical speed (the old fixed 2.5 m/s base was 65% of the original rower's 3.85).
     */
    private float waveSpeed() {
        double t = sessionSeconds;
        float typical = (float) profile.typicalSpeed();
        // 0.65 put the wave permanently 35% slower than the rower's own typical pace, so anyone
        // rowing at or above the median ran onto the shoulder within seconds. Simulated across
        // this machine's envelope (p10 3.00, median 3.85, p90 4.06 m/s): at 0.65 the ride lasted
        // 7.5 s at the median and 6.7 s at the top, always to the shoulder.
        float base = typical * 0.90f;
        float drift = (float) Math.sin(t / 11.0) * typical * 0.12f;
        float set = (float) Math.sin(t / 37.0) * typical * 0.14f;
        // A big set runs a little quicker - the rower has to lift for it, briefly.
        float bigSet = setLift * typical * 0.05f;
        return (base + drift + set + bigSet + (inBarrel() ? typical * 0.09f : 0f)) * SPOT_SPEED[spot];
    }

    private boolean inBarrel() {
        return sessionSeconds < barrelUntil;
    }

    private boolean inPocket() {
        return Math.abs(position) < 0.35f;
    }

    private void showPopup(String text, int color, double seconds) {
        popup = text;
        popupColor = color;
        popupUntil = sessionSeconds + seconds;
    }

    /** Your ride is over: bank it, award the medal, and send the next surfer in the lineup. */
    private void endRide(Phase how) {
        phase = how;
        endedAt = sessionSeconds;
        bestRide = Math.max(bestRide, rideSeconds);
        spotBest[spot] = Math.max(spotBest[spot], (float) rideSeconds);
        if (rideMedal >= 0) {
            medalCount[rideMedal]++;
        }
        trickPending = false;
        setClean = false;
        saveUntil = 0;
        stack = 1;
        // Clear the two combo pips with the stack. Without this the drain never runs (it is gated
        // on stack > 1), so the chip sat at x1 with TRICK and BARREL still lit until the next
        // take-off, saying you were holding half a combo you no longer had.
        stackHasTrick = false;
        stackHasBarrel = false;
        judgeRide(how);
        endNote = (rideMedal >= 0 ? MEDAL_NAMES[rideMedal] + " MEDAL  ·  " : "")
                + "WAVE " + heatWaves[4] + " OF " + HEAT_WAVES + " THIS HEAT";
        // Paddle back out: the wave belongs to the lineup until the priority clock runs down.
        priorityAt = sessionSeconds + PRIORITY_WAIT;
        paddleOut = 0f;
        npcRider = queue[0];
        for (int i = 0; i < queue.length - 1; i++) {
            queue[i] = queue[i + 1];
        }
        queue[queue.length - 1] = npcRider;
        npcRideStart = sessionSeconds;
        npcRideLen = npcRideLength();
        // The rest of the lineup is surfing the same heat out the back; most rides land a wave.
        for (int i = 0; i < heatNpc.length; i++) {
            if (i != npcRider && Math.random() < 0.55) {
                scoreNpcWave(i);
            }
        }
        if (heatRides >= HEAT_WAVES) {
            finishHeat();
        }
    }

    /** The surfer on the wave finishes - early if you paddle into the next one. */
    private void finishNpcRide() {
        if (npcRider < 0) {
            return;
        }
        // They are in the heat too, so that wave gets a score in front of you.
        float v = npcWave(npcRider);
        addWave(heatNpc[npcRider], v);
        heatWaves[npcRider]++;
        showPopup(NPC_NAMES[npcRider] + " RODE " + clock(npcRideLen) + "  ·  SCORES " + score1(v),
                NPC_COLORS[npcRider], 1.8);
        npcRider = -1;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();
        float wave = waveSpeed();

        // ---------- big sets ----------
        double toSet = nextSetAt - sessionSeconds;
        boolean inSet = toSet <= 0 && toSet > -SET_LEN;
        if (inSet && !setActive) {
            setActive = true;
            setClean = phase == Phase.RIDING;
            setWaveShown = 0;
        }
        if (inSet) {
            int waveNo = 1 + (int) Math.min(2, (-toSet) / SET_WAVE);
            if (waveNo != setWaveShown) {
                setWaveShown = waveNo;
                showPopup("SET WAVE " + waveNo + " OF 3", 0xFF9FE3F0, 1.6);
                shake.kick(dp(7f));
            }
            if (phase != Phase.RIDING) {
                setClean = false;
            }
        } else if (setActive) {
            setActive = false;
            if (setClean && phase == Phase.RIDING) {
                score += 20;
                setsRidden++;
                showPopup("RODE THE WHOLE SET  +20", ACCENT, 2.0);
                cheerUntil = sessionSeconds + 2.5;
            }
            setClean = false;
            nextSetAt = sessionSeconds + SPOT_SET_GAP[spot] * (0.8 + Math.random() * 0.4);
        }
        // Each wave of the set swells up and settles; the lift eases so the face never snaps.
        float liftTarget = 0f;
        if (inSet) {
            float inWave = (float) (((-toSet) % SET_WAVE) / SET_WAVE);
            liftTarget = 0.65f + 0.35f * (float) Math.sin(Math.PI * inWave);
        }
        setLift += (liftTarget - setLift) * Math.min(1f, dt * 1.6f);

        if (phase == Phase.RIDING) {
            rideSeconds += dt;
        }
        if (phase == Phase.RIDING && saveUntil > 0) {
            // Hung up in the lip. The ride is not over: pull hard - or outrun the wave by 12% -
            // and you drive back down the face. Two of these per ride, then the lip wins.
            position = -1f;
            if (speed > wave * 1.12f) {
                rescue();
            } else if (sessionSeconds >= saveUntil) {
                saveUntil = 0;
                endRide(Phase.WIPEOUT);
                shake.kick(dp(18f));
                spray.burst(w * 0.62f, h * 0.55f, 60, dp(260f), 1.0f, dp(4f), 0xDDFFFFFF, true);
            }
        } else if (phase == Phase.RIDING) {
            // Position is the integral of the speed difference: match the wave and you hold. The first
            // seconds of a ride are forgiven - on the tablet a rower still getting up to speed was
            // wiped out after five seconds - and the drift is gentler than it first shipped (0.42).
            float gain = rideSeconds < 6 ? 0.08f : 0.3f;
            position += (speed - wave) * dt * gain;
            // The wave holds you in the pocket. Without this, position is a pure integral of the
            // speed difference: ANY standing mismatch saturates to +/-1 eventually, so a stable
            // pocket was impossible and the ride length was just "time until the integral ran
            // out" - raising the wave speed alone only changed which side you fell off. With the
            // restoring term the drift and set are what threaten you, which is the intended game.
            // A take-off with priority grips harder for its first seconds: that is the reward for
            // waiting your turn, and it is what pulls a ride back out of trouble.
            float grip = 0.12f + (sessionSeconds < gripUntil ? 0.16f : 0f);
            position -= position * grip * dt;
            // With the handle sensor, leaning carves along the face - a small correction, not a
            // substitute for matching the wave's pace.
            if (hasSteering()) {
                position += steering() * 0.18f * dt;
            }
            score += dt * (inPocket() ? 2.0 : 1.0) * (inBarrel() ? 3.0 : 1.0) * (inSet ? 1.5 : 1.0)
                    * stackMul() * SPOT_POINTS[spot];
            if (inPocket()) {
                ridePocketSeconds += dt;
            }
            if (!inBarrel() && sessionSeconds >= nextBarrelAt) {
                barrelUntil = sessionSeconds + 6;
                nextBarrelAt = sessionSeconds + SPOT_BARREL_GAP[spot] + Math.random() * 14;
                barrelPocketSeconds = 0;
            }
            // A barrel counts on the way out, and only if you actually held the pocket inside it.
            // The old test fired on whichever frames fell inside a dt-wide window, so it could
            // count the same barrel twice; this counts the transition instead.
            if (inBarrel()) {
                if (inPocket()) {
                    barrelPocketSeconds += dt;
                }
            } else if (wasBarrel) {
                if (barrelPocketSeconds > 2.0) {
                    barrels++;
                    rideBarrels++;
                    // The combo banner is the rarer, better message: only announce the plain
                    // barrel when this one did not complete a trick-barrel combo. (The trick
                    // path gets the same treatment by calling addStack after its own popup.)
                    if (!addStack(false)) {
                        showPopup("BARREL MADE  ·  STACK x" + stack, 0xFF9FE3F0, 1.8);
                    }
                    cheerUntil = sessionSeconds + 2.0;
                }
                barrelPocketSeconds = 0;
            }
            wasBarrel = inBarrel();
            // Medals as the ride clock passes each mark.
            while (rideMedal + 1 < MEDAL_AT.length && rideSeconds >= MEDAL_AT[rideMedal + 1]) {
                rideMedal++;
                showPopup(MEDAL_NAMES[rideMedal] + " MEDAL!", MEDAL_COLORS[rideMedal], 2.0);
                spray.burst(w * 0.5f, h * 0.30f, 40, dp(220f), 1.0f, dp(3.5f),
                        MEDAL_COLORS[rideMedal], false);
                cheerUntil = sessionSeconds + 2.0;
            }
            if (position <= -1f) {
                position = -1f;
                if (rideSaves < SAVES_PER_RIDE) {
                    saveUntil = sessionSeconds + SAVE_WINDOW;
                    shake.kick(dp(12f));
                    spray.burst(w * 0.7f, h * 0.42f, 24, dp(180f), 0.7f, dp(3f), 0xAAEAF6FF, true);
                } else {
                    endRide(Phase.WIPEOUT);
                    shake.kick(dp(18f));
                    spray.burst(w * 0.62f, h * 0.55f, 60, dp(260f), 1.0f, dp(4f), 0xDDFFFFFF, true);
                }
            } else if (position >= 1f) {
                endRide(Phase.KICKOUT);
            }
        }
        // The stack drains: keep landing tricks and barrels or it steps back down.
        if (stack > 1 && sessionSeconds >= stackUntil) {
            stack--;
            stackUntil = sessionSeconds + STACK_WINDOW * 0.6;
            if (stack <= 1) {
                stackHasTrick = false;
                stackHasBarrel = false;
            }
        }
        if (phase == Phase.WAITING) {
            paddleOut = Math.min(1f, paddleOut + dt * 0.35f);
        }
        // Back to paddling out on its own once the judges and any heat result have had their
        // moment. A rower mid-piece should never have to take a hand off the handle, and priority
        // still gates the next take-off, so this cannot skip the queue.
        if ((phase == Phase.WIPEOUT || phase == Phase.KICKOUT)
                && sessionSeconds - endedAt > 5.0 && sessionSeconds >= heatShowUntil) {
            phase = Phase.WAITING;
        }
        // A trick lands - or does not, if the ride ended mid-air.
        if (trickPending && sessionSeconds - trickStart >= TRICK_LEN) {
            trickPending = false;
            if (phase == Phase.RIDING) {
                rideTricks++;
                sessionTricks++;
                int pts = Math.round((10 + 5 * Math.min(rideTricks - 1, 4)) * (inSet ? 2 : 1)
                        * stackMul());
                score += pts;
                showPopup(TRICKS[trickName] + "  +" + pts + "  x" + stack
                        + (inSet ? "  SET BONUS" : ""), 0xFFF5C518, 1.8);
                cheerUntil = sessionSeconds + 2.0;
                shake.kick(dp(6f));
                // After the trick popup, so a combo or an unlock - both rarer - takes the banner.
                addStack(true);
                bankTrick();
            }
        }
        if (npcRider >= 0 && sessionSeconds - npcRideStart >= NPC_SHOW) {
            finishNpcRide();
        }
        shake.step(dt);
        spray.step(dt, dp(260f));

        // ---------- scene ----------
        float horizon = h * 0.26f;
        float lipX = w * 0.74f;
        // A bigger spot stands taller: the crest climbs toward (and at MAVERICK BAY just past)
        // the horizon, so an unlocked spot reads as a bigger wave without running any faster.
        float sizeMul = SPOT_SIZE[spot];
        float baseCrest = h * (0.30f - 0.055f * (sizeMul - 1f) / 0.28f);
        float crestY = baseCrest - setLift * h * 0.08f;
        float troughY = h * 0.86f;
        float faceRun = w * 0.62f;
        float seaBottom = h * 0.62f;
        ensureGradients(w, h, horizon, lipX, baseCrest, troughY, faceRun);

        c.save();
        c.translate(shake.dx, shake.dy);
        // Sky, coloured by the spot.
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyGrad);
        c.drawRect(0, 0, w, horizon + dp(2f), paint);
        paint.setShader(null);
        Fx.glow(c, w * 0.22f, horizon - dp(6f), dp(70f), 0x77FFD9A0);
        paint.setColor(0xFFFFE0A8);
        c.drawCircle(w * 0.22f, horizon - dp(6f), dp(24f), paint);

        // Seagulls gliding over, wings flexing.
        paint.setColor(0xFF2A2F3A);
        paint.setStrokeWidth(dp(2.2f));
        for (int i = 0; i < gullX.length; i++) {
            gullX[i] += dt * (0.018f + i * 0.006f);
            if (gullX[i] > 1.08f) {
                gullX[i] = -0.08f;
            }
            float gx = gullX[i] * w;
            float gy = horizon * (0.35f + i * 0.17f) + (float) Math.sin(sessionSeconds + i) * dp(8f);
            float flex = (float) Math.sin(sessionSeconds * 5 + i) * dp(4f);
            c.drawLine(gx - dp(12f), gy - flex, gx, gy, paint);
            c.drawLine(gx, gy, gx + dp(12f), gy - flex, paint);
        }

        // Open ocean beyond the wave.
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(seaGrad);
        c.drawRect(0, horizon, w, h, paint);
        paint.setShader(null);
        // Sailboats on the horizon, and the sun glinting on the water.
        for (int i = 0; i < 2; i++) {
            float sx = (float) ((w * (0.35f + i * 0.3f) + sessionSeconds * dp(4f) * (i + 1)) % w);
            paint.setColor(0xFFF2EAD8);
            path.reset();
            path.moveTo(sx, horizon - dp(18f));
            path.lineTo(sx + dp(10f), horizon - dp(2f));
            path.lineTo(sx, horizon - dp(2f));
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFF3A3F4A);
            c.drawRect(sx - dp(8f), horizon - dp(2f), sx + dp(12f), horizon + dp(1f), paint);
        }
        paint.setColor(0xFFFFF1C8);
        for (int i = 0; i < 26; i++) {
            float gx = w * 0.22f + (float) Math.sin(i * 12.9898) * w * 0.18f;
            float gy = horizon + dp(8f) + (i % 7) * dp(9f);
            if (Math.sin(sessionSeconds * 3 + i * 1.7) > 0.4) {
                c.drawRect(gx - dp(5f), gy, gx + dp(5f), gy + dp(1.6f), paint);
            }
        }

        drawOpenOcean(c, w, horizon, seaBottom, dt);
        drawIncomingSet(c, w, horizon, seaBottom, toSet);
        drawLineup(c, w, horizon, seaBottom);

        // The wave face: a curve from the lip down-left into the trough.
        path.reset();
        path.moveTo(lipX + w * 0.3f, crestY);
        path.lineTo(lipX, crestY);
        for (float px = lipX; px >= lipX - faceRun; px -= dp(10f)) {
            path.lineTo(px, faceY(px, lipX, crestY, troughY, faceRun));
        }
        path.lineTo(lipX - faceRun, h);
        path.lineTo(w, h);
        path.close();
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(faceGrad);
        c.drawPath(path, paint);
        paint.setShader(null);
        // 3.19.6: the face was one flat triangle on the tablet. Chop lines run down it, sun glitter
        // rides the upper face, and the base churns - so the wave is visibly moving under the board.
        c.save();
        c.clipPath(path);
        // Short chop that follows the face rather than long streaks across it.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 40; i++) {
            float phase = (float) ((i * 0.061 + sessionSeconds * 0.30) % 1.0);
            float px0 = lipX - faceRun * phase;
            float down = dp(18f) + (i % 5) * dp(26f);
            float py0 = faceY(px0, lipX, crestY, troughY, faceRun) + down;
            float len = dp(12f) + (i % 3) * dp(8f);
            paint.setStrokeWidth(dp(1.4f) + (i % 3) * dp(0.5f));
            int a = 30 + (int) (35 * (1 + Math.sin(i * 1.7 + sessionSeconds * 3)));
            paint.setColor((a << 24) | 0xFFFFFF);
            float py1 = faceY(px0 - len, lipX, crestY, troughY, faceRun) + down + dp(2f);
            c.drawLine(px0, py0, px0 - len, py1, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 18; i++) {
            float phase = (float) ((i * 0.09 + sessionSeconds * 0.5) % 1.0);
            float gx0 = lipX - faceRun * phase;
            float gy0 = faceY(gx0, lipX, crestY, troughY, faceRun) + dp(16f) + (i % 5) * dp(12f);
            float tw = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6 + i * 1.3);
            paint.setColor(((int) (70 * tw) << 24) | 0xFFF1C8);
            c.drawOval(gx0 - dp(14f), gy0 - dp(2f), gx0 + dp(14f), gy0 + dp(2f), paint);
        }
        // Churn where the face meets the trough: it rides the face itself, not open water.
        paint.setColor(0x55FFFFFF);
        for (int i = 0; i < 26; i++) {
            float phase = (float) ((i * 0.038 + sessionSeconds * 0.22) % 1.0);
            float bx0 = lipX - faceRun * (0.55f + 0.45f * phase);
            float by0 = faceY(bx0, lipX, crestY, troughY, faceRun) + dp(26f)
                    + (float) Math.sin(i * 2.1 + sessionSeconds * 7) * dp(5f);
            c.drawCircle(bx0, by0, dp(4f) + (i % 3) * dp(3f), paint);
        }
        c.restore();

        // Lip: the curl, thrown further over during a barrel and taller in a big set.
        float throwOver = (inBarrel() ? w * 0.30f : w * 0.10f) + setLift * w * 0.05f
                + (sizeMul - 1f) * w * 0.12f;
        paint.setColor(SPOT_LIP[spot]);
        path.reset();
        path.moveTo(lipX, crestY);
        path.cubicTo(lipX + dp(10f), crestY - dp(46f), lipX - throwOver * 0.5f, crestY - dp(54f),
                lipX - throwOver, crestY + dp(18f));
        path.cubicTo(lipX - throwOver * 0.6f, crestY - dp(16f), lipX - dp(6f), crestY + dp(12f),
                lipX, crestY + dp(30f));
        path.close();
        c.drawPath(path, paint);
        // Whitewater under the curl.
        paint.setColor(0xEEFFFFFF);
        for (int i = 0; i < 16; i++) {
            float fx0 = lipX - throwOver * (i / 16f);
            float fy0 = crestY + dp(20f) + (float) Math.sin(i * 1.7 + sessionSeconds * 8) * dp(7f);
            c.drawCircle(fx0, fy0, dp(7f) + (i % 3) * dp(3f), paint);
        }

        // Spray thrown off the board's rail, and a wake up the face behind it.
        drawBoardWash(c, lipX, crestY, troughY, faceRun, dt);

        // The lineup's surfer on the wave after your ride: works the pocket, pops once, kicks out.
        if (npcRider >= 0) {
            float f = (float) ((sessionSeconds - npcRideStart) / NPC_SHOW);
            float np = -0.15f + 0.35f * (float) Math.sin(f * 11f);
            if (f > 0.82f) {
                np += (f - 0.82f) / 0.18f * 1.2f;
            }
            float nt = (Math.max(-1f, Math.min(1.1f, np)) + 1f) / 2f;
            float nx = lipX - dp(26f) - nt * faceRun * 0.82f;
            float ny = faceY(nx, lipX, crestY, troughY, faceRun);
            float hop = f > 0.45f && f < 0.6f ? (float) Math.sin((f - 0.45f) / 0.15f * Math.PI) * dp(40f) : 0f;
            drawRider(c, nx, ny - hop, 0.85f, NPC_COLORS[npcRider], 0x22FFFFFF, false);
            label(c, NPC_NAMES[npcRider], nx, ny - hop - dp(38f), 9f, NPC_COLORS[npcRider], Paint.Align.CENTER);
        }

        // Surfer: position maps along the face, pocket sitting just left of the curl.
        float t = (position + 1f) / 2f;                 // 0 = in the curl, 1 = far shoulder
        float surfX = lipX - dp(26f) - t * faceRun * 0.82f;
        float surfY = faceY(surfX, lipX, crestY, troughY, faceRun);
        if (phase == Phase.RIDING && !tricking()) {
            sprayAccum += dt * (6f + speed * 8f);
            while (sprayAccum >= 1f) {
                sprayAccum -= 1f;
                spray.spawn(surfX, surfY, dp(30f) + (float) Math.random() * dp(70f),
                        -dp(20f) - (float) Math.random() * dp(50f), 0.45f, dp(2.4f), 0xCCEAF6FF, true);
            }
        }
        spray.draw(c);
        if (phase == Phase.WAITING) {
            // Paddling back out to the lineup while the priority clock runs down.
            drawPaddler(c, w, horizon, seaBottom);
        } else if (phase != Phase.WIPEOUT) {
            if (tricking()) {
                drawTrick(c, surfX, surfY);
            } else {
                drawSurfer(c, surfX, surfY, inPocket());
            }
            if (saveUntil > 0) {
                drawLipSave(c, surfX, surfY, w, h);
            }
        }
        // A dolphin leaps in the foreground now and then.
        double sinceDolphin = sessionSeconds - dolphinAt;
        if (sinceDolphin > 0 && sinceDolphin < 1.4) {
            float f = (float) (sinceDolphin / 1.4);
            float dx = w * (0.15f + 0.3f * f);
            float dy = h * 0.92f - (float) Math.sin(Math.PI * f) * h * 0.16f;
            c.save();
            c.rotate(-60 + 120 * f, dx, dy);
            paint.setColor(0xFF6E8FA8);
            c.drawOval(dx - dp(26f), dy - dp(8f), dx + dp(26f), dy + dp(8f), paint);
            path.reset();
            path.moveTo(dx - dp(2f), dy - dp(7f));
            path.lineTo(dx + dp(8f), dy - dp(18f));
            path.lineTo(dx + dp(10f), dy - dp(6f));
            path.close();
            c.drawPath(path, paint);
            c.restore();
            if (f < 0.08f || f > 0.92f) {
                spray.burst(dx, h * 0.92f, 6, dp(90f), 0.5f, dp(2.5f), 0xCCEAF6FF, true);
            }
        } else if (sinceDolphin >= 1.4) {
            dolphinAt = sessionSeconds + 12 + Math.random() * 14;
        }
        // Barrel light: rays through the curl.
        if (inBarrel() && phase == Phase.RIDING) {
            paint.setStrokeWidth(dp(14f));
            for (int k = 0; k < 5; k++) {
                paint.setColor(0x14FFFFFF);
                float ox = lipX - w * 0.05f * k;
                c.drawLine(ox, crestY, ox - w * 0.25f, h, paint);
            }
        }
        c.restore();

        // Barrel framing: the lip arcs right over the top of the view.
        if (inBarrel() && phase == Phase.RIDING) {
            paint.setColor(0xCC0A1A26);
            path.reset();
            path.moveTo(0, 0);
            path.lineTo(w, 0);
            path.lineTo(w, h * 0.16f);
            path.cubicTo(w * 0.6f, h * 0.34f, w * 0.3f, h * 0.10f, 0, h * 0.22f);
            path.close();
            c.drawPath(path, paint);
            Fx.vignette(c, w, h, 0.55f, 0x08243A);
        }
        // Pops: entering the pocket, and every 25 points.
        if (phase == Phase.RIDING) {
            if (inPocket() && !wasPocket && !tricking()) {
                showPopup(inBarrel() ? "3x IN THE BARREL!" : "2x POCKET!", 0xFFF5C518, 1.4);
                spray.burst(surfX, surfY, 26, dp(160f), 0.7f, dp(3f), 0xDDEAF6FF, true);
            }
            int mark = (int) (score / 25);
            if (mark > lastScoreMark) {
                lastScoreMark = mark;
                if (sessionSeconds >= popupUntil - 0.6) {
                    showPopup("+" + (mark * 25) + " POINTS", 0xFFF5C518, 1.4);
                }
            }
        }
        wasPocket = inPocket();
        if (sessionSeconds < popupUntil) {
            float rise = (float) (1.4 - Math.min(1.4, popupUntil - sessionSeconds)) * dp(40f);
            bold(c, popup, w * 0.45f, h * 0.42f - rise, 26f, popupColor, Paint.Align.CENTER);
        }
        if (!inPocket() && phase == Phase.RIDING) {
            Fx.vignette(c, w, h, 0.25f + Math.abs(position) * 0.5f,
                    position < 0 ? 0x8A1010 : 0x1A3A5A);
        }

        // ---------- HUD ----------
        // The band: where you are on the face, with the pocket and the trick zone marked.
        float bandW = w * 0.52f;
        float bx = w * 0.5f - bandW / 2f;
        float by = h - dp(52f);
        paint.setColor(0x44000000);
        c.drawRoundRect(bx, by, bx + bandW, by + dp(14f), dp(7f), dp(7f), paint);
        paint.setColor(0x3335D0BA);
        c.drawRect(bx + bandW * 0.325f, by, bx + bandW * 0.675f, by + dp(14f), paint);
        paint.setColor(0x66F5C518);
        c.drawRect(bx + bandW * 0.05f, by + dp(10f), bx + bandW * 0.45f, by + dp(14f), paint);
        float px = bx + bandW * ((position + 1f) / 2f);
        paint.setColor(inPocket() ? ACCENT : position < 0 ? BAD : WARN);
        c.drawRoundRect(px - dp(4f), by - dp(4f), px + dp(4f), by + dp(18f), dp(3f), dp(3f), paint);
        label(c, "LIP", bx, by + dp(28f), 8.5f, BAD, Paint.Align.LEFT);
        label(c, "TRICK ZONE", bx + bandW * 0.25f, by + dp(28f), 8.5f, 0xFFF5C518, Paint.Align.CENTER);
        label(c, "POCKET", w * 0.5f, by + dp(28f), 8.5f, ACCENT, Paint.Align.CENTER);
        label(c, "SHOULDER", bx + bandW, by + dp(28f), 8.5f, WARN, Paint.Align.RIGHT);

        // Sweet-spot bar up the right edge: where you are on the face, the pocket in green, and an
        // arrow for which way you are drifting - up when you are faster than the wave, down when
        // slower. Readable at a glance, mid-stroke, without looking for the surfer.
        float sx = w - dp(30f);
        float sTop = h * 0.16f;
        float sBottom = h * 0.70f;
        float sMid = (sTop + sBottom) / 2f;
        float half = (sBottom - sTop) / 2f;
        paint.setColor(0x66000000);
        c.drawRoundRect(sx - dp(10f), sTop, sx + dp(10f), sBottom, dp(10f), dp(10f), paint);
        paint.setColor(0x6635D0BA);
        c.drawRect(sx - dp(10f), sMid - half * 0.35f, sx + dp(10f), sMid + half * 0.35f, paint);
        paint.setColor(0x88F5C518);
        c.drawRect(sx - dp(10f), sMid + half * 0.1f, sx - dp(6f), sMid + half * 0.9f, paint);
        float marker = sMid - half * Math.max(-1f, Math.min(1f, position));
        paint.setColor(inPocket() ? ACCENT : position < 0 ? BAD : WARN);
        c.drawRoundRect(sx - dp(16f), marker - dp(5f), sx + dp(16f), marker + dp(5f), dp(4f), dp(4f), paint);
        if (phase == Phase.RIDING && Math.abs(speed - wave) > 0.08f) {
            boolean up = speed > wave;
            float ay = marker + (up ? -dp(14f) : dp(14f));
            path.reset();
            path.moveTo(sx - dp(8f), ay + (up ? dp(6f) : -dp(6f)));
            path.lineTo(sx + dp(8f), ay + (up ? dp(6f) : -dp(6f)));
            path.lineTo(sx, ay - (up ? dp(6f) : -dp(6f)));
            path.close();
            c.drawPath(path, paint);
        }
        label(c, "SHOULDER", sx, sTop - dp(8f), 8f, WARN, Paint.Align.CENTER);
        label(c, "LIP", sx, sBottom + dp(16f), 8f, BAD, Paint.Align.CENTER);
        label(c, "POCKET", sx - dp(18f), sMid + dp(3f), 8f, ACCENT, Paint.Align.RIGHT);
        label(c, "TRICK", sx - dp(18f), sMid + half * 0.62f, 8f, 0xFFF5C518, Paint.Align.RIGHT);

        String big;
        String cap;
        int col;
        boolean priority = sessionSeconds >= priorityAt;
        switch (phase) {
            case WAITING:
                big = priority ? "YOUR WAVE - GO" : "PADDLE BACK OUT";
                cap = priority
                        ? "you have priority - get above 1.0 m/s to take off"
                        : "wait your turn for the clean take-off bonus  ·  sprint past "
                                + dropInWatts() + " W to drop in early (half score)";
                col = priority ? ACCENT : DIM;
                break;
            case WIPEOUT:
                big = "WIPEOUT";
                cap = "the lip got you after " + clock(rideSeconds) + "  ·  " + endNote + "  ·  tap for another";
                col = BAD;
                break;
            case KICKOUT:
                big = "LOST THE WAVE";
                cap = "too far ahead - rode " + clock(rideSeconds) + "  ·  " + endNote + "  ·  tap for another";
                col = WARN;
                break;
            default:
                big = clock(rideSeconds);
                if (saveUntil > 0) {
                    cap = "IN THE LIP - PULL HARD TO SAVE IT!";
                    col = BAD;
                } else if (tricking()) {
                    cap = TRICKS[trickName] + "!";
                    col = 0xFFF5C518;
                } else if (inBarrel()) {
                    cap = inPocket() ? "IN THE BARREL - TRIPLE SCORE" : "BARREL - GET IN THE POCKET";
                    col = inPocket() ? ACCENT : WARN;
                } else if (position <= -0.6f) {
                    cap = String.format(java.util.Locale.US, "PULL HARDER - WAVE IS DOING %.1f m/s", wave);
                    col = BAD;
                } else if (inTrickZone() && rideSeconds > 3
                        && sessionSeconds - trickStart > TRICK_LEN + TRICK_COOLDOWN) {
                    cap = "TOP OF THE WAVE - SURGE PAST " + surgeWatts() + " W FOR A TRICK";
                    col = 0xFFF5C518;
                } else if (inPocket()) {
                    cap = "IN THE POCKET - DOUBLE SCORE";
                    col = ACCENT;
                } else if (position < 0) {
                    cap = String.format(java.util.Locale.US, "PULL HARDER - WAVE IS DOING %.1f m/s", wave);
                    col = BAD;
                } else {
                    cap = String.format(java.util.Locale.US, "EASE OFF - WAVE IS DOING %.1f m/s", wave);
                    col = WARN;
                }
        }
        bold(c, big, w * 0.5f, dp(36f), phase == Phase.RIDING ? 40f : 24f, col, Paint.Align.CENTER);
        bold(c, cap, w * 0.5f, dp(54f), 11f, col, Paint.Align.CENTER);

        if (phase == Phase.RIDING) {
            drawMedalProgress(c, w * 0.5f, dp(70f));
        }
        // Set announcement: counted down as the swells march in, then the wave number.
        if (toSet > 0 && toSet <= SET_WARN) {
            float pulse = 0.75f + 0.25f * (float) Math.sin(sessionSeconds * 8);
            int a = (int) (255 * pulse);
            bold(c, "BIG SET ROLLING IN  ·  " + (int) Math.ceil(toSet), w * 0.5f, dp(102f), 20f,
                    (a << 24) | 0x9FE3F0, Paint.Align.CENTER);
            label(c, "taller, faster wave - tricks and points worth more", w * 0.5f, dp(118f), 9.5f,
                    0xCC9FE3F0, Paint.Align.CENTER);
        } else if (inSet) {
            bold(c, "BIG SET  ·  WAVE " + setWaveShown + " OF 3", w * 0.5f, dp(102f), 16f,
                    0xFF9FE3F0, Paint.Align.CENTER);
        }

        drawSpotChip(c);
        drawBoard(c);
        drawStack(c, w);
        if (phase == Phase.WAITING) {
            drawPriority(c, w, h);
        }
        boolean heatShowing = sessionSeconds >= heatShowFrom && sessionSeconds < heatShowUntil;
        if (!heatShowing && (phase == Phase.WIPEOUT || phase == Phase.KICKOUT)
                && sessionSeconds - judgeAt < JUDGE_SHOW + 1.4) {
            drawJudging(c, w, h);
        }
        if (heatShowing) {
            drawHeatResult(c, w, h);
        }

        float fy = h - dp(12f);
        float colw = w / 6f;
        // While the result board is up it is the heat just finished that the figures belong to.
        stat(c, colw * 0.5f, fy,
                score1(heatShowing ? lastHeat[4] : total(heatMine)) + " / 30",
                heatShowing ? "HEAT " + (heatNumber - 1) + "  ·  FINAL"
                        : "HEAT " + heatNumber + "  ·  WAVE "
                                + Math.min(HEAT_WAVES, heatWaves[4] + 1) + " OF " + HEAT_WAVES);
        stat(c, colw * 1.5f, fy, String.valueOf(Math.round(score)), "POINTS");
        stat(c, colw * 2.5f, fy, barrels + " / " + sessionSaves, "BARRELS · SAVES");
        stat(c, colw * 3.5f, fy, sessionTricks + " / " + lifetimeTricks, "TRICKS · LIFETIME");
        drawMedalTally(c, colw * 4.5f, fy);
        float allTime = Math.max(bestHeat, bests.get("surf.heat", 0f));
        stat(c, colw * 5.5f, fy, allTime > 0f ? score1(allTime) : "--", "BEST HEAT");
    }

    /**
     * One decimal place, without {@code String.format}. This is called up to thirteen times a
     * frame - five board rows, five result rows, three judges' cards and the footer - and a
     * Formatter per call is exactly the kind of frame-loop garbage the older hardware stutters on.
     * Scores are never negative here, but clamp anyway so a stray value cannot print "-2.-4".
     */
    private static String score1(float v) {
        int t = Math.round(v * 10f);
        if (t < 0) {
            t = 0;
        }
        return (t / 10) + "." + (t % 10);
    }

    private void ensureGradients(float w, float h, float horizon, float lipX, float crestY,
                                 float troughY, float faceRun) {
        if (w == gradW && h == gradH && spot == gradSpot && skyGrad != null) {
            return;
        }
        gradW = w;
        gradH = h;
        gradSpot = spot;
        skyGrad = new LinearGradient(0, 0, 0, horizon, SPOT_SKY[spot][0], SPOT_SKY[spot][1],
                Shader.TileMode.CLAMP);
        seaGrad = new LinearGradient(0, horizon, 0, h, SPOT_SEA[spot][0], SPOT_SEA[spot][1],
                Shader.TileMode.CLAMP);
        faceGrad = new LinearGradient(lipX - faceRun, crestY, lipX, troughY,
                SPOT_FACE[spot][0], SPOT_FACE[spot][1], Shader.TileMode.CLAMP);
    }

    /**
     * The set's three swells march in from the horizon during the six-second warning, so the
     * announcement is something you can see coming, not just a banner.
     */
    private void drawIncomingSet(Canvas c, float w, float horizon, float seaBottom, double toSet) {
        if (toSet <= 0 || toSet > SET_WARN) {
            return;
        }
        float right = w * 0.62f;
        float prog0 = (float) (1.0 - toSet / SET_WARN);
        for (int k = 0; k < 3; k++) {
            float prog = prog0 * 1.4f - k * 0.2f;
            if (prog <= 0f || prog >= 1f) {
                continue;
            }
            float y = horizon + dp(6f) + (seaBottom - horizon) * prog;
            float thick = dp(4f) + prog * dp(12f);
            paint.setColor(0x88062238);
            c.drawRoundRect(0, y - thick, right, y + thick * 0.4f, thick, thick, paint);
            paint.setColor((Math.round(90 + 120 * prog) << 24) | 0x00E8F4FF);
            for (float x = dp(8f); x < right; x += dp(46f)) {
                float bob = (float) Math.sin(x / dp(60f) + sessionSeconds * 4 + k) * dp(2f);
                c.drawRoundRect(x, y - thick + bob, x + dp(22f) + prog * dp(14f),
                        y - thick + dp(2.5f) + bob, dp(1.5f), dp(1.5f), paint);
            }
        }
    }

    /** The other surfers sitting out back on their boards, in the order they will go. */
    private void drawLineup(Canvas c, float w, float horizon, float seaBottom) {
        float baseY = horizon + (seaBottom - horizon) * 0.42f;
        boolean cheer = sessionSeconds < cheerUntil;
        int slot = 0;
        for (int i = 0; i < queue.length; i++) {
            int who = queue[i];
            if (who == npcRider) {
                continue;
            }
            float x = w * (0.05f + slot * 0.075f);
            float bob = (float) Math.sin(sessionSeconds * 1.6 + who * 1.3) * dp(3f);
            float y = baseY + bob + (slot % 2) * dp(10f);
            drawSitter(c, x, y, NPC_COLORS[who], cheer && ((int) (sessionSeconds * 4) + who) % 2 == 0);
            label(c, slot == 0 ? NPC_NAMES[who] + " - NEXT" : NPC_NAMES[who], x, y - dp(34f), 8f,
                    NPC_COLORS[who], Paint.Align.CENTER);
            slot++;
        }
    }

    private void drawSitter(Canvas c, float x, float y, int color, boolean armsUp) {
        float s = dp(1f);
        paint.setColor(0xFFF2EAD8);
        c.drawOval(x - 18 * s, y - 2 * s, x + 18 * s, y + 4 * s, paint);          // board
        paint.setColor(0x44E8F4FF);
        c.drawOval(x - 22 * s, y + 2 * s, x + 22 * s, y + 7 * s, paint);          // ripple
        paint.setColor(color);
        c.drawRect(x - 4 * s, y - 16 * s, x + 4 * s, y - 1 * s, paint);          // torso
        if (armsUp) {
            c.drawRect(x - 9 * s, y - 28 * s, x - 5 * s, y - 13 * s, paint);
            c.drawRect(x + 5 * s, y - 28 * s, x + 9 * s, y - 13 * s, paint);
        } else {
            c.drawRect(x - 9 * s, y - 13 * s, x - 5 * s, y - 3 * s, paint);
            c.drawRect(x + 5 * s, y - 13 * s, x + 9 * s, y - 3 * s, paint);
        }
        paint.setColor(0xFFF1C27D);
        c.drawCircle(x, y - 20 * s, 4 * s, paint);
    }

    /** The player mid-air: launched off the lip, spinning for everything but a straight air. */
    private void drawTrick(Canvas c, float x, float y) {
        float f = (float) Math.min(1.0, (sessionSeconds - trickStart) / TRICK_LEN);
        float lift = (float) Math.sin(Math.PI * f) * dp(110f);
        float spin;
        switch (trickName) {
            case 0:
                spin = (float) Math.sin(Math.PI * f) * -30f;
                break;
            case 4:
                spin = -360f * f;
                break;
            case 3:
                spin = (float) Math.sin(Math.PI * f) * -75f;
                break;
            default:
                spin = 360f * f;
        }
        float cy = y - lift;
        if (f < 0.12f) {
            spray.spawn(x, y, (float) (Math.random() - 0.5) * dp(120f), -dp(120f),
                    0.6f, dp(3f), 0xDDEAF6FF, true);
        }
        Fx.glow(c, x, cy - dp(10f), dp(48f), 0x55F5C518);
        c.save();
        c.rotate(spin, x, cy - dp(12f));
        drawRider(c, x, cy, 1f, 0xFFF5C518, 0, trickName == 3);
        c.restore();
    }

    /** Progress to the next medal under the ride clock. */
    private void drawMedalProgress(Canvas c, float cx, float y) {
        int next = rideMedal + 1;
        float barW = dp(220f);
        float x0 = cx - barW / 2f;
        paint.setColor(0x55000000);
        c.drawRoundRect(x0, y, x0 + barW, y + dp(6f), dp(3f), dp(3f), paint);
        if (next >= MEDAL_AT.length) {
            paint.setColor(MEDAL_COLORS[2]);
            c.drawRoundRect(x0, y, x0 + barW, y + dp(6f), dp(3f), dp(3f), paint);
            label(c, "GOLD - KEEP RIDING", cx, y + dp(18f), 8.5f, MEDAL_COLORS[2], Paint.Align.CENTER);
            return;
        }
        float from = next == 0 ? 0f : MEDAL_AT[next - 1];
        float frac = (float) Math.max(0, Math.min(1, (rideSeconds - from) / (MEDAL_AT[next] - from)));
        paint.setColor(MEDAL_COLORS[next]);
        c.drawRoundRect(x0, y, x0 + barW * frac, y + dp(6f), dp(3f), dp(3f), paint);
        if (rideMedal >= 0) {
            drawMedal(c, x0 - dp(14f), y + dp(3f), dp(7f), MEDAL_COLORS[rideMedal]);
        }
        label(c, MEDAL_NAMES[next] + " IN " + clock(Math.max(0, MEDAL_AT[next] - rideSeconds)),
                cx, y + dp(18f), 8.5f, MEDAL_COLORS[next], Paint.Align.CENTER);
    }

    private void drawMedal(Canvas c, float x, float y, float r, int color) {
        paint.setColor(0xFF2E6FD8);
        c.drawRect(x - r * 0.5f, y - r * 2.1f, x + r * 0.5f, y - r * 0.6f, paint);   // ribbon
        paint.setColor(color);
        c.drawCircle(x, y, r, paint);
        paint.setColor(0x55FFFFFF);
        c.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.35f, paint);
    }

    private void drawMedalTally(Canvas c, float x, float y) {
        float gap = dp(34f);
        for (int i = 0; i < 3; i++) {
            float mx = x + (i - 1) * gap;
            drawMedal(c, mx - dp(8f), y - dp(17f), dp(6f), medalCount[i] > 0 ? MEDAL_COLORS[i] : 0x55808890);
            bold(c, String.valueOf(medalCount[i]), mx + dp(5f), y - dp(12f), 13f,
                    medalCount[i] > 0 ? TEXT : FAINT, Paint.Align.CENTER);
        }
        label(c, "MEDALS", x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    /**
     * Spot picker, top left. Only live between rides, so a switch can never end one. The third
     * line is the unlock ladder: how many more tricks open the next, bigger spot.
     */
    private void drawSpotChip(Canvas c) {
        boolean live = phase != Phase.RIDING;
        spotLeft = dp(10f);
        spotTop = dp(10f);
        spotRight = spotLeft + dp(210f);
        spotBottom = spotTop + dp(52f);
        paint.setColor(live ? 0xAA0A1A26 : 0x660A1A26);
        c.drawRoundRect(spotLeft, spotTop, spotRight, spotBottom, dp(10f), dp(10f), paint);
        if (live) {
            paint.setColor(0x6635D0BA);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            c.drawRoundRect(spotLeft, spotTop, spotRight, spotBottom, dp(10f), dp(10f), paint);
            paint.setStyle(Paint.Style.FILL);
        }
        bold(c, SPOT_NAMES[spot] + (live ? "  >" : ""), spotLeft + dp(10f), spotTop + dp(17f), 12f,
                live ? TEXT : DIM, Paint.Align.LEFT);
        label(c, live ? "tap to change spot  ·  " + SPOT_BLURB[spot] : SPOT_BLURB[spot],
                spotLeft + dp(10f), spotTop + dp(31f), 8f, live ? ACCENT : FAINT, Paint.Align.LEFT);
        int next = nextLocked();
        float barTop = spotTop + dp(38f);
        if (next < 0) {
            label(c, "EVERY SPOT UNLOCKED  ·  " + lifetimeTricks + " TRICKS LANDED",
                    spotLeft + dp(10f), barTop + dp(8f), 8f, 0xFF9FE3F0, Paint.Align.LEFT);
            return;
        }
        // Unlock ladder: a bar you can watch fill as tricks land.
        float barW = dp(190f);
        int from = 0;
        for (int i = next - 1; i >= 0; i--) {
            if (SPOT_UNLOCK[i] > from) {
                from = SPOT_UNLOCK[i];
            }
        }
        float frac = Math.max(0f, Math.min(1f, (lifetimeTricks - from)
                / (float) Math.max(1, SPOT_UNLOCK[next] - from)));
        paint.setColor(0x44000000);
        c.drawRoundRect(spotLeft + dp(10f), barTop, spotLeft + dp(10f) + barW, barTop + dp(4f),
                dp(2f), dp(2f), paint);
        paint.setColor(0xFF9FE3F0);
        c.drawRoundRect(spotLeft + dp(10f), barTop, spotLeft + dp(10f) + barW * frac,
                barTop + dp(4f), dp(2f), dp(2f), paint);
        label(c, "LOCKED: " + SPOT_NAMES[next] + "  ·  " + lifetimeTricks + " / "
                        + SPOT_UNLOCK[next] + " TRICKS",
                spotLeft + dp(10f), barTop + dp(12f), 7.5f, 0xCC9FE3F0, Paint.Align.LEFT);
    }

    /** The heat board: best three waves each, you included, live as the heat runs. */
    private void drawBoard(Canvas c) {
        sortHeat();
        float x = dp(10f);
        float y = dp(80f);
        float boardW = dp(210f);
        paint.setColor(0x880A1A26);
        c.drawRoundRect(x, y - dp(14f), x + boardW, y + dp(16f) * board.length + dp(6f),
                dp(8f), dp(8f), paint);
        label(c, "HEAT " + heatNumber + " - BEST 3 WAVES", x + dp(8f), y - dp(2f), 7.5f, FAINT,
                Paint.Align.LEFT);
        for (int i = 0; i < board.length; i++) {
            int who = board[i];
            float ry = y + dp(13f) + i * dp(16f);
            boolean you = who == 4;
            int color = you ? ACCENT : NPC_COLORS[who];
            // The leader's row glows a little so the board is never a still image.
            if (i == 0) {
                int a = (int) (20 + 22 * (1 + Math.sin(sessionSeconds * 3)));
                paint.setColor((a << 24) | (color & 0x00FFFFFF));
                c.drawRoundRect(x + dp(4f), ry - dp(11f), x + boardW - dp(4f), ry + dp(4f),
                        dp(4f), dp(4f), paint);
            }
            label(c, PLACES[i], x + dp(8f), ry, 8.5f, you ? ACCENT : DIM, Paint.Align.LEFT);
            if (you) {
                bold(c, "YOU", x + dp(34f), ry, 9f, color, Paint.Align.LEFT);
            } else {
                label(c, NPC_NAMES[who], x + dp(34f), ry, 9f, color, Paint.Align.LEFT);
            }
            // The three counting waves as little bars, so the board shows what is carrying you.
            float[] top = who == 4 ? heatMine : heatNpc[who];
            for (int k = 0; k < top.length; k++) {
                float bx0 = x + dp(84f) + k * dp(22f);
                paint.setColor(0x33FFFFFF);
                c.drawRoundRect(bx0, ry - dp(8f), bx0 + dp(18f), ry, dp(2f), dp(2f), paint);
                paint.setColor(color);
                float f = Math.max(0f, Math.min(1f, top[k] / 10f));
                c.drawRoundRect(bx0, ry - dp(8f), bx0 + dp(18f) * f, ry, dp(2f), dp(2f), paint);
            }
            label(c, score1(boardHeat(who)), x + boardW - dp(8f), ry, 9.5f, you ? ACCENT : TEXT,
                    Paint.Align.RIGHT);
        }
        label(c, "waves counted: you " + heatWaves[4] + " / " + HEAT_WAVES,
                x + dp(8f), y + dp(16f) * board.length + dp(18f), 7.5f, FAINT, Paint.Align.LEFT);
    }

    /** Insertion sort by heat total, best first - five entries, no allocation. */
    private void sortHeat() {
        for (int i = 0; i < board.length; i++) {
            board[i] = i;
        }
        for (int i = 1; i < board.length; i++) {
            int v = board[i];
            float key = boardHeat(v);
            int j = i - 1;
            while (j >= 0 && boardHeat(board[j]) < key) {
                board[j + 1] = board[j];
                j--;
            }
            board[j + 1] = v;
        }
    }

    private float boardHeat(int who) {
        return who == 4 ? total(heatMine) : total(heatNpc[who]);
    }

    /**
     * The stack: tricks and barrels multiplying together, with the drain ring that says how long
     * you have to add the next one. This is the ten-second stake while a ride is going well.
     */
    private void drawStack(Canvas c, float w) {
        float cx = w - dp(112f);
        float cy = dp(40f);
        float r = dp(26f);
        boolean live = stack > 1;
        float left = live ? (float) Math.max(0, Math.min(1, (stackUntil - sessionSeconds)
                / STACK_WINDOW)) : 0f;
        paint.setColor(live ? 0xAA0A1A26 : 0x550A1A26);
        c.drawRoundRect(cx - dp(34f), cy - dp(30f), cx + dp(92f), cy + dp(30f), dp(10f), dp(10f), paint);
        ring.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5f));
        paint.setColor(0x33FFFFFF);
        c.drawArc(ring, -90f, 360f, false, paint);
        if (live) {
            paint.setColor(0xFFF5C518);
            c.drawArc(ring, -90f, 360f * left, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        if (live && sessionSeconds < comboUntil) {
            Fx.glow(c, cx, cy, dp(40f), 0x66F5C518);
        }
        // The figure itself pulses with the beat when the stack is hot, so it reads as alive.
        float pulse = live ? 1f + 0.12f * (float) Math.sin(sessionSeconds * 7) : 1f;
        bold(c, "x" + stack, cx, cy + dp(7f), 22f * pulse, live ? 0xFFF5C518 : FAINT,
                Paint.Align.CENTER);
        label(c, "STACK", cx + dp(40f), cy - dp(8f), 8.5f, live ? TEXT : FAINT, Paint.Align.CENTER);
        // Two pips: which halves of the combo you are holding.
        paint.setColor(stackHasTrick ? 0xFFF5C518 : 0x44FFFFFF);
        c.drawCircle(cx + dp(28f), cy + dp(6f), dp(4f), paint);
        paint.setColor(stackHasBarrel ? 0xFF9FE3F0 : 0x44FFFFFF);
        c.drawCircle(cx + dp(52f), cy + dp(6f), dp(4f), paint);
        label(c, "TRICK", cx + dp(28f), cy + dp(20f), 7f, stackHasTrick ? 0xFFF5C518 : FAINT,
                Paint.Align.CENTER);
        label(c, "BARREL", cx + dp(52f), cy + dp(20f), 7f, stackHasBarrel ? 0xFF9FE3F0 : FAINT,
                Paint.Align.CENTER);
    }

    /** Priority: the countdown that turns the wave over to you, and the reward for waiting. */
    private void drawPriority(Canvas c, float w, float h) {
        float cx = w * 0.34f;
        float cy = h * 0.52f;
        float r = dp(52f);
        double left = priorityAt - sessionSeconds;
        boolean yours = left <= 0;
        paint.setColor(0xAA061726);
        c.drawCircle(cx, cy, r + dp(12f), paint);
        ring.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(8f));
        paint.setColor(0x33FFFFFF);
        c.drawArc(ring, -90f, 360f, false, paint);
        paint.setColor(yours ? ACCENT : WARN);
        float sweep = yours ? 360f
                : 360f * (float) Math.max(0, Math.min(1, 1 - left / PRIORITY_WAIT));
        c.drawArc(ring, -90f, sweep, false, paint);
        paint.setStyle(Paint.Style.FILL);
        if (yours) {
            Fx.glow(c, cx, cy, dp(74f), 0x5535D0BA);
            bold(c, "GO", cx, cy + dp(10f), 30f, ACCENT, Paint.Align.CENTER);
            label(c, "PRIORITY IS YOURS", cx, cy + dp(34f), 9f, ACCENT, Paint.Align.CENTER);
            label(c, "clean take-off  +1.0  ·  8 s of extra grip", cx, cy + dp(48f), 8.5f,
                    0xCC35D0BA, Paint.Align.CENTER);
        } else {
            bold(c, String.valueOf((int) Math.ceil(left)), cx, cy + dp(12f), 34f, WARN,
                    Paint.Align.CENTER);
            label(c, "PADDLING BACK OUT", cx, cy + dp(34f), 9f, WARN, Paint.Align.CENTER);
            label(c, npcRider >= 0 ? NPC_NAMES[npcRider] + " has this one"
                    : "the lineup has this one", cx, cy + dp(48f), 8.5f, DIM, Paint.Align.CENTER);
        }
    }

    /** You, paddling back out to the lineup while the priority clock runs. */
    private void drawPaddler(Canvas c, float w, float horizon, float seaBottom) {
        float x = w * (0.10f + paddleOut * 0.10f);
        float y = seaBottom + (1f - paddleOut) * dp(70f);
        float s = dp(1f);
        float stroke = (float) Math.sin(sessionSeconds * 3.4);
        paint.setColor(0x44E8F4FF);
        c.drawOval(x - 30 * s, y + 2 * s, x + 30 * s, y + 10 * s, paint);
        paint.setColor(0xFFF5C518);
        c.drawOval(x - 26 * s, y - 4 * s, x + 26 * s, y + 5 * s, paint);            // board
        paint.setColor(ACCENT);
        c.drawRect(x - 8 * s, y - 12 * s, x + 6 * s, y - 4 * s, paint);             // body, prone
        // Arms alternate over the rail - the paddle stroke.
        c.drawRect(x + 4 * s, y - 16 * s + stroke * 5 * s, x + 16 * s,
                y - 12 * s + stroke * 5 * s, paint);
        c.drawRect(x + 4 * s, y - 10 * s - stroke * 5 * s, x + 14 * s,
                y - 6 * s - stroke * 5 * s, paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(x + 10 * s, y - 14 * s, 4 * s, paint);
        if (stroke > 0.9f) {
            spray.spawn(x + 16 * s, y - 6 * s, dp(30f), -dp(30f), 0.4f, dp(2f), 0x99EAF6FF, true);
        }
        label(c, "YOU", x, y - dp(26f), 8f, ACCENT, Paint.Align.CENTER);
    }

    /** Hung in the lip: the prompt that says the ride is still savable, and for how long. */
    private void drawLipSave(Canvas c, float x, float y, float w, float h) {
        float left = (float) Math.max(0, (saveUntil - sessionSeconds) / SAVE_WINDOW);
        Fx.glow(c, x, y - dp(20f), dp(70f), 0x66F0655D);
        float barW = dp(200f);
        float bx = w * 0.5f - barW / 2f;
        float by = h * 0.30f;
        paint.setColor(0xAA000000);
        c.drawRoundRect(bx - dp(8f), by - dp(26f), bx + barW + dp(8f), by + dp(16f),
                dp(8f), dp(8f), paint);
        bold(c, "PULL HARD - SAVE IT!", w * 0.5f, by - dp(8f), 17f,
                (Math.sin(sessionSeconds * 14) > 0 ? 0xFFF0655D : 0xFFFFFFFF), Paint.Align.CENTER);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(bx, by, bx + barW, by + dp(8f), dp(4f), dp(4f), paint);
        paint.setColor(BAD);
        c.drawRoundRect(bx, by, bx + barW * left, by + dp(8f), dp(4f), dp(4f), paint);
        label(c, (SAVES_PER_RIDE - rideSaves) + " SAVE" + (SAVES_PER_RIDE - rideSaves == 1 ? "" : "S")
                + " LEFT THIS RIDE", w * 0.5f, by + dp(24f), 8.5f, DIM, Paint.Align.CENTER);
    }

    /** Three judges' cards, flipped up one at a time, then the wave score stamped on. */
    private void drawJudging(Canvas c, float w, float h) {
        double since = sessionSeconds - judgeAt;
        float cx = w * 0.5f;
        float cy = h * 0.44f;
        float cardW = dp(78f);
        float gap = dp(12f);
        float total = judge.length * cardW + (judge.length - 1) * gap;
        paint.setColor(0xCC061726);
        c.drawRoundRect(cx - total / 2f - dp(16f), cy - dp(70f), cx + total / 2f + dp(16f),
                cy + dp(56f), dp(12f), dp(12f), paint);
        label(c, "THE JUDGES", cx, cy - dp(52f), 9f, FAINT, Paint.Align.CENTER);
        for (int i = 0; i < judge.length; i++) {
            double at = JUDGE_CARD * (i + 1);
            if (since < at) {
                continue;
            }
            float f = (float) Math.min(1.0, (since - at) / 0.28);
            float x0 = cx - total / 2f + i * (cardW + gap);
            float rise = (1f - f) * dp(26f);
            paint.setColor(0xFFF2EAD8);
            c.drawRoundRect(x0, cy - dp(40f) + rise, x0 + cardW, cy + dp(16f) + rise,
                    dp(6f), dp(6f), paint);
            bold(c, score1(judge[i]), x0 + cardW / 2f, cy + rise, 26f * (0.7f + 0.3f * f),
                    0xFF11212E, Paint.Align.CENTER);
            label(c, "JUDGE " + (i + 1), x0 + cardW / 2f, cy + dp(11f) + rise, 7.5f,
                    0xFF5D6B80, Paint.Align.CENTER);
        }
        if (since >= JUDGE_CARD * judge.length + 0.3) {
            float f = (float) Math.min(1.0, (since - (JUDGE_CARD * judge.length + 0.3)) / 0.3);
            int col = waveScore >= 7f ? 0xFFF5C518 : waveScore >= 4f ? ACCENT : WARN;
            // Fixed radius: a glow whose radius moves every frame defeats the shader cache.
            Fx.glow(c, cx, cy + dp(38f), dp(60f), (col & 0x00FFFFFF) | (Math.round(0x44 * f) << 24));
            bold(c, "WAVE SCORE  " + score1(waveScore), cx, cy + dp(44f), 15f + 6f * f, col,
                    Paint.Align.CENTER);
        }
        if (burnedRide) {
            label(c, "INTERFERENCE - HALF SCORE", cx, cy - dp(62f), 9f, BAD, Paint.Align.CENTER);
        } else if (priorityRide) {
            label(c, "CLEAN TAKE-OFF  +1.0", cx, cy - dp(62f), 9f, ACCENT, Paint.Align.CENTER);
        }
    }

    /** The heat result: five totals, your row called out, the placing stamped over the top. */
    private void drawHeatResult(Canvas c, float w, float h) {
        float f = (float) Math.min(1.0, (sessionSeconds - heatShowFrom) / 0.5);
        float panelW = Math.min(w * 0.6f, dp(420f));
        float cx = w * 0.5f;
        float top = h * 0.24f;
        paint.setColor(0xEE061726);
        c.drawRoundRect(cx - panelW / 2f, top, cx + panelW / 2f, top + dp(196f), dp(14f), dp(14f),
                paint);
        int col = heatPlace == 0 ? 0xFFF5C518 : heatPlace <= 2 ? ACCENT : WARN;
        bold(c, heatPlace == 0 ? "HEAT WON" : "HEAT RESULT  ·  " + PLACES[Math.max(0, heatPlace)],
                cx, top + dp(30f), 24f * (0.8f + 0.2f * f), col, Paint.Align.CENTER);
        label(c, "best three waves", cx, top + dp(46f), 9f, FAINT, Paint.Align.CENTER);
        // Rows slide in one after another, ordered by total.
        sortLastHeat();
        for (int i = 0; i < heatOrder.length; i++) {
            int who = heatOrder[i];
            float in = (float) Math.max(0, Math.min(1, (sessionSeconds - heatShowFrom - i * 0.12) / 0.25));
            if (in <= 0f) {
                continue;
            }
            float ry = top + dp(70f) + i * dp(24f);
            float rx = cx - panelW / 2f + dp(18f) + (1f - in) * dp(40f);
            boolean you = who == 4;
            int rc = you ? ACCENT : NPC_COLORS[who];
            if (you) {
                paint.setColor(0x3335D0BA);
                c.drawRoundRect(cx - panelW / 2f + dp(10f), ry - dp(15f),
                        cx + panelW / 2f - dp(10f), ry + dp(5f), dp(5f), dp(5f), paint);
            }
            label(c, PLACES[i], rx, ry, 10f, you ? ACCENT : DIM, Paint.Align.LEFT);
            bold(c, you ? "YOU" : NPC_NAMES[who], rx + dp(44f), ry, 11f, rc, Paint.Align.LEFT);
            bold(c, score1(lastHeat[who]), cx + panelW / 2f - dp(18f), ry, 12f,
                    you ? ACCENT : TEXT, Paint.Align.RIGHT);
        }
        label(c, heatPlace == 0
                        ? "heats won this session: " + heatsWon + "  ·  paddle back out for heat "
                                + heatNumber
                        : "best three counted  ·  paddle back out for heat " + heatNumber,
                cx, top + dp(184f), 9f, DIM, Paint.Align.CENTER);
    }

    private void sortLastHeat() {
        for (int i = 0; i < heatOrder.length; i++) {
            heatOrder[i] = i;
        }
        for (int i = 1; i < heatOrder.length; i++) {
            int v = heatOrder[i];
            float key = lastHeat[v];
            int j = i - 1;
            while (j >= 0 && lastHeat[heatOrder[j]] < key) {
                heatOrder[j + 1] = heatOrder[j];
                j--;
            }
            heatOrder[j + 1] = v;
        }
    }

    /**
     * Swell on the open ocean: SHORT DASHES, not lines. A first attempt drew six full-width
     * strokes with only a few dp of amplitude and squared their spacing, which bunched them into
     * one band of straight horizontal streaks across the sea - scan-lines, not water. This uses
     * the shape that works in RiverScenery.drawWaterLife: scattered dashes, spread linearly.
     */
    private void drawOpenOcean(Canvas c, float w, float horizon, float seaBottom, float dt) {
        // 0.06 was too slow to read as movement: measured 0.05 in this band against 0.11 for
        // the empty water it replaced - texture, not travel. Swell should visibly march in.
        swellPhase += dt * 0.55f;
        if (swellPhase > 1f) {
            swellPhase -= 1f;
        }
        float top = horizon + dp(10f);
        // The crest sits at h*0.30 and the horizon at h*0.26, so that gap is only ~24 px and
        // clamped to the dp(40) floor: the swell compressed into a 60 px strip under the
        // horizon while the open ocean below it stayed as empty as before. The sea runs from
        // the horizon down to where the face meets it, so anchor the span there instead.
        float span = Math.max(dp(40f), seaBottom - top);
        float right = w * 0.60f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int row = 0; row < 7; row++) {
            float f = (row + 0.5f) / 7f;                  // linear: spread, not bunched at the top
            float y = top + span * f;
            float depth = 0.25f + f * 0.75f;
            paint.setStrokeWidth(dp(0.9f) + depth * dp(1.1f));
            paint.setColor((Math.round(34 + 66 * depth) << 24) | 0x00BFE3FF);
            float step = dp(96f) + depth * dp(54f);
            float off = (swellPhase * step * (1f + row * 0.35f)) % step;
            for (float x = -off; x < right; x += step) {
                float len = dp(14f) + depth * dp(22f);
                float bob = (float) Math.sin(x / dp(70f) + swellPhase * 6.3 + row) * dp(1.8f);
                c.drawLine(x, y + bob, x + len, y + bob, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {                      // a few distant whitecaps
            float f = ((i * 0.19f) + swellPhase * (0.8f + (i % 3) * 0.15f)) % 1f;
            float y = top + span * (0.2f + 0.7f * f);
            float x = (float) (right * (0.08f + 0.8f * Math.abs(Math.sin(i * 12.9898))));
            paint.setColor((Math.round(30 + 70 * f) << 24) | 0x00E8F4FF);
            c.drawRoundRect(x, y - dp(1.2f), x + dp(7f) + f * dp(9f), y + dp(1.2f), dp(1.2f), dp(1.2f), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);   // do not leak the round cap into later lines
        // A dolphin arcs out of the swell every few seconds - something living, not just texture.
        dolphinT += dt;
        if (dolphinT > 9f) {
            dolphinT = -2f - (float) Math.random() * 3f;
        }
        if (dolphinT > 0f && dolphinT < 1.6f) {
            float p = dolphinT / 1.6f;
            float arc = (float) Math.sin(p * Math.PI);
            float dx = w * 0.16f + p * w * 0.12f;
            float dy = top + span * 0.78f - arc * dp(30f);
            c.save();
            c.rotate((p - 0.5f) * 44f, dx, dy);
            paint.setColor(0xFF1B3E5E);
            c.drawOval(dx - dp(15f), dy - dp(5f), dx + dp(15f), dy + dp(5f), paint);
            path.reset();
            path.moveTo(dx - dp(13f), dy);
            path.lineTo(dx - dp(24f), dy - dp(8f));
            path.lineTo(dx - dp(21f), dy + dp(2f));
            path.close();
            c.drawPath(path, paint);
            path.reset();
            path.moveTo(dx - dp(2f), dy - dp(4f));
            path.lineTo(dx + dp(2f), dy - dp(13f));
            path.lineTo(dx + dp(6f), dy - dp(3f));
            path.close();
            c.drawPath(path, paint);
            c.restore();
            paint.setColor(0x55E8F4FF);
            c.drawOval(dx - dp(18f), dy + dp(5f), dx + dp(18f), dy + dp(10f), paint);
        }
    }

    /** Spray and a carve trail behind the board: the face now shows where you have been. */
    private void drawBoardWash(Canvas c, float lipX, float crestY, float troughY, float faceRun, float dt) {
        float t0 = (position + 1f) / 2f;
        float sx0 = lipX - dp(26f) - t0 * faceRun * 0.82f;
        float sy0 = faceY(sx0, lipX, crestY, troughY, faceRun);
        wash.step(dt, dp(120f));
        // Only while there is actually a board on the face. The test used to be rideSeconds > 0,
        // which stays true after the ride ends - and since 3.23 draws the paddler instead of the
        // surfer between rides, that left spray and a carve trail coming off empty water for the
        // whole eight-second paddle out. Existing particles still step and draw, so nothing freezes.
        if (phase != Phase.RIDING) {
            wash.draw(c);
            return;
        }
        if (Math.random() < 0.9) {
            wash.spawn(sx0 - dp(6f), sy0 + dp(6f), (float) (Math.random() - 0.35) * dp(90f),
                    -dp(20f) - (float) Math.random() * dp(60f), 0.5f, dp(3f), 0xCCFFFFFF, true);
        }
        wash.draw(c);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3f));
        paint.setColor(0x66FFFFFF);
        path.reset();
        path.moveTo(sx0, sy0 + dp(8f));
        for (int i = 1; i <= 8; i++) {
            float px0 = sx0 + i * dp(22f);
            float py0 = faceY(px0, lipX, crestY, troughY, faceRun) + dp(8f)
                    + (float) Math.sin(i * 0.9 + sessionSeconds * 4) * dp(4f);
            path.lineTo(px0, py0);
        }
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Height of the wave face at a given x. Exponent shapes the concave face. */
    private float faceY(float x, float lipX, float crestY, float troughY, float faceRun) {
        float f = Math.max(0f, Math.min(1f, (lipX - x) / faceRun));
        return crestY + (troughY - crestY) * (float) Math.pow(f, 0.72);
    }

    private void drawSurfer(Canvas c, float x, float y, boolean pocket) {
        drawRider(c, x, y, 1f, pocket ? ACCENT : 0xFFE6EDF7, pocket ? 0x4435D0BA : 0x22FFFFFF, false);
    }

    /**
     * A crouched rider on a board angled along the face. {@code glow} 0 skips the halo;
     * {@code superman} stretches the body out flat off the board, for that trick.
     */
    private void drawRider(Canvas c, float x, float y, float scale, int body, int glow, boolean superman) {
        float s = dp(1f) * scale;
        if (glow != 0) {
            Fx.glow(c, x, y, dp(34f), glow);
        }
        // Board, angled along the face.
        paint.setColor(0xFFF5C518);
        c.save();
        c.rotate(24f, x, y);
        c.drawRoundRect(x - 20 * s, y - 3 * s, x + 20 * s, y + 3 * s, 3 * s, 3 * s, paint);
        c.restore();
        paint.setColor(body);
        if (superman) {
            // Hands on the rail, body flying out behind.
            c.drawRect(x - 26 * s, y - 16 * s, x - 2 * s, y - 10 * s, paint);  // body
            c.drawRect(x - 4 * s, y - 12 * s, x + 2 * s, y - 2 * s, paint);    // arms to board
            paint.setColor(0xFFF1C27D);
            c.drawCircle(x + 3 * s, y - 15 * s, 4.5f * s, paint);
            return;
        }
        // Rider: a crouched figure leaning into the face.
        c.drawRect(x - 4 * s, y - 20 * s, x + 4 * s, y - 6 * s, paint);      // torso
        c.drawRect(x - 7 * s, y - 8 * s, x - 1 * s, y - 2 * s, paint);       // back leg
        c.drawRect(x + 2 * s, y - 8 * s, x + 8 * s, y - 2 * s, paint);       // front leg
        c.drawRect(x + 4 * s, y - 18 * s, x + 14 * s, y - 15 * s, paint);    // lead arm
        paint.setColor(0xFFF1C27D);
        c.drawCircle(x, y - 24 * s, 4.5f * s, paint);
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
