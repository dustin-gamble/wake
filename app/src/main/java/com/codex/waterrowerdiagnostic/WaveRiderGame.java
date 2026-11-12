package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
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

    /*
     * 3.25 graphics pass. Everything below is how the wave LOOKS - the face curve (faceY), the
     * crest height, the set lift and every position the ride uses are untouched.
     *
     * Gradients that must follow the crest as a set lifts it (lip, whitewater) are built at the
     * resting crest and drawn under a canvas translate, so a set never rebuilds a shader.
     */
    private LinearGradient horizonGlowGrad;
    private LinearGradient hazeGrad;
    private LinearGradient sunColumnGrad;
    private LinearGradient lipGrad;
    private LinearGradient soupGrad;
    /** Unit-height panel fill (0..1), drawn under a vertical scale: one shader for every panel. */
    private final LinearGradient panelGrad =
            new LinearGradient(0, 0, 0, 1, 0xFF17253B, 0xFF0A111B, Shader.TileMode.CLAMP);
    /** Vignettes cached per tint; strength goes on the paint's alpha, not into a new shader. */
    private RadialGradient vigDanger;
    private RadialGradient vigShoulder;
    private RadialGradient vigBarrel;
    private final Paint vigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Translucent backlit water near the crest, per spot. */
    private int glassColor;
    private int glassLight;
    private int cavityColor;
    private int farLandColor;
    private int nearLandColor;
    private int cloudBody;
    private int cloudRim;
    private final Path facePath = new Path();
    private final Path crestPath = new Path();
    private final Path lacePath = new Path();
    private final Path lipPath = new Path();
    private final Path rimPath = new Path();
    private final Path cavityPath = new Path();
    private final Path soupPath = new Path();
    private final Path headlandPath = new Path();
    private final Path islandPath = new Path();
    /** Unit-scale silhouettes, built once and drawn under translate/rotate/scale. */
    private final Path boardPath = new Path();
    private final Path dolphinPath = new Path();
    private final Path finPath = new Path();
    /** Spindrift blown back off the crest. */
    private final Fx.Particles mist = new Fx.Particles();
    private float mistAccum;
    private float curtainAccum;
    /** The lip's throw, eased for display only - inBarrel() still switches on the instant. */
    private float throwShown = -1f;
    private float cloudDrift;

    /* Stroke widths (dp) and alphas of the backlit band along the crest, widest first. */
    private static final float[] GLASS_W = {300f, 190f, 112f, 56f, 20f};
    private static final int[] GLASS_A = {0x1A, 0x22, 0x2E, 0x3A, 0x50};
    /* Low sunset cloud banks: x and y as fractions of the width and the sky, width in dp. */
    private static final float[] CLOUD_X = {0.06f, 0.30f, 0.47f, 0.66f, 0.88f};
    private static final float[] CLOUD_Y = {0.70f, 0.82f, 0.62f, 0.76f, 0.66f};
    private static final float[] CLOUD_W = {170f, 120f, 210f, 140f, 180f};

    WaveRiderGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        buildSilhouettes();
    }

    /** Board and dolphin outlines in dp units, nose to the right. Built once, never per frame. */
    private void buildSilhouettes() {
        // Surfboard: round tail at -20, pointed nose at +22, thickest just behind the middle.
        boardPath.moveTo(22f, -0.4f);
        boardPath.quadTo(12f, -4.2f, -4f, -4.0f);
        boardPath.quadTo(-17f, -3.8f, -20f, -1.6f);
        boardPath.quadTo(-21.5f, 0f, -20f, 1.6f);
        boardPath.quadTo(-17f, 3.8f, -4f, 4.0f);
        boardPath.quadTo(12f, 4.2f, 22f, 0.4f);
        boardPath.close();
        // Dolphin, 52 long: beak, rounded melon, tapering stock and a two-lobed fluke.
        dolphinPath.moveTo(26f, 0.5f);
        dolphinPath.quadTo(20f, -2.5f, 16f, -5f);
        dolphinPath.quadTo(6f, -9f, -6f, -7f);
        dolphinPath.quadTo(-15f, -5f, -20f, -1.5f);
        dolphinPath.lineTo(-26f, -8f);
        dolphinPath.quadTo(-24f, -2f, -23.5f, 0f);
        dolphinPath.quadTo(-24f, 2f, -27f, 6.5f);
        dolphinPath.lineTo(-20f, 1.8f);
        dolphinPath.quadTo(-8f, 8f, 6f, 6.5f);
        dolphinPath.quadTo(17f, 5f, 26f, 1.5f);
        dolphinPath.close();
        finPath.moveTo(-1f, -7.5f);
        finPath.quadTo(3f, -13f, 9f, -18f);
        finPath.quadTo(7.5f, -12f, 9.5f, -7f);
        finPath.close();
    }

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }

    private static int alpha(int rgb, float a) {
        int ai = Math.max(0, Math.min(255, Math.round(a)));
        return (ai << 24) | (rgb & 0x00FFFFFF);
    }

    /**
     * One HUD panel: soft drop shadow, a vertical gradient fill, a 1px rim and a lit top edge.
     * The gradient is a single unit-height shader drawn under a vertical scale, so panels of any
     * size share it and nothing is allocated.
     */
    private void panel(Canvas c, float l, float t, float r, float b, float rad, int alphaByte) {
        float ph = b - t;
        if (ph <= 1f || r <= l) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(alpha(0x000000, alphaByte * 0.35f));
        c.drawRoundRect(l, t + dp(3f), r, b + dp(3f), rad, rad, paint);
        c.save();
        c.translate(l, t);
        c.scale(1f, ph);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setAlpha(alphaByte);
        paint.setShader(panelGrad);
        c.drawRoundRect(0, 0, r - l, 1f, rad, rad / ph, paint);
        paint.setShader(null);
        c.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(alpha(0x2A3A50, alphaByte * 0.8f));
        c.drawRoundRect(l + 0.5f, t + 0.5f, r - 0.5f, b - 0.5f, rad, rad, paint);
        paint.setColor(alpha(0xFFFFFF, alphaByte * 0.22f));
        c.drawLine(l + rad, t + dp(1f), r - rad, t + dp(1f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Glossy highlight along the top third of a filled bar. */
    private void gloss(Canvas c, float l, float t, float r, float b) {
        if (r - l < dp(2f)) {
            return;
        }
        paint.setColor(0x40FFFFFF);
        float hh = Math.max(dp(1.5f), (b - t) * 0.38f);
        c.drawRoundRect(l + dp(1f), t + dp(0.5f), r - dp(1f), t + hh, hh / 2f, hh / 2f, paint);
    }

    private void vignette(Canvas c, float w, float h, float strength, RadialGradient g) {
        if (strength <= 0.01f || g == null) {
            return;
        }
        vigPaint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        vigPaint.setAlpha(Math.round(Math.min(1f, strength) * 255f));
        vigPaint.setShader(g);
        c.drawRect(0, 0, w, h, vigPaint);
        vigPaint.setShader(null);
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
        float sunX = w * 0.22f;
        drawSky(c, w, horizon, dt);

        // Open ocean beyond the wave: darker toward the viewer, hazed into the sky at the horizon.
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(seaGrad);
        c.drawRect(0, horizon, w, h, paint);
        paint.setShader(hazeGrad);
        c.drawRect(0, horizon, w, seaBottom, paint);
        paint.setShader(null);
        // The sun's road on the water: narrow at the horizon, widening and fading toward the viewer.
        c.save();
        c.clipRect(0, horizon, w, h);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(sunColumnGrad);
        float roadEnd = seaBottom + h * 0.08f;
        c.drawOval(sunX - dp(84f), horizon - dp(4f), sunX + dp(84f), horizon + (roadEnd - horizon) * 2f, paint);
        paint.setShader(null);
        c.restore();
        // Sailboats on the horizon, and the sun glinting on the water.
        for (int i = 0; i < 2; i++) {
            float sx = (float) ((w * (0.35f + i * 0.3f) + sessionSeconds * dp(4f) * (i + 1)) % w);
            paint.setColor(0xFFF2EAD8);
            path.rewind();
            path.moveTo(sx, horizon - dp(19f));
            path.lineTo(sx + dp(10f), horizon - dp(3f));
            path.lineTo(sx, horizon - dp(3f));
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFFC9BBA0);                    // jib, on the shadow side
            path.rewind();
            path.moveTo(sx - dp(1f), horizon - dp(16f));
            path.lineTo(sx - dp(1f), horizon - dp(3f));
            path.lineTo(sx - dp(8f), horizon - dp(3f));
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFF2E3440);
            path.rewind();
            path.moveTo(sx - dp(10f), horizon - dp(2.5f));
            path.lineTo(sx + dp(13f), horizon - dp(2.5f));
            path.lineTo(sx + dp(9f), horizon + dp(1.5f));
            path.lineTo(sx - dp(7f), horizon + dp(1.5f));
            path.close();
            c.drawPath(path, paint);
        }
        paint.setColor(0xFFFFF1C8);
        for (int i = 0; i < 26; i++) {
            float gx = sunX + (float) Math.sin(i * 12.9898) * w * 0.18f * (0.35f + (i % 7) / 9f);
            float gy = horizon + dp(8f) + (i % 7) * dp(9f);
            if (Math.sin(sessionSeconds * 3 + i * 1.7) > 0.4) {
                float half = dp(3f) + (i % 7) * dp(0.9f);    // glints widen toward the viewer
                c.drawRect(gx - half, gy, gx + half, gy + dp(1.6f), paint);
            }
        }

        drawOpenOcean(c, w, horizon, seaBottom, dt);
        drawIncomingSet(c, w, horizon, seaBottom, toSet);
        drawLineup(c, w, horizon, seaBottom);

        // ---------- the wave ----------
        // Lip: the curl, thrown further over during a barrel and taller in a big set. The throw is
        // eased for display only; inBarrel() and the scoring still switch on the instant.
        float throwOver = (inBarrel() ? w * 0.30f : w * 0.10f) + setLift * w * 0.05f
                + (sizeMul - 1f) * w * 0.12f;
        if (throwShown < 0f) {
            throwShown = throwOver;
        }
        throwShown += (throwOver - throwShown) * Math.min(1f, dt * 3.5f);
        float tipX = lipX - throwShown;
        // 0 = the ordinary curl, 1 = a fully open barrel with its curtain down.
        float open = Math.max(0f, Math.min(1f, (throwShown - w * 0.13f) / (w * 0.15f)));
        float lift = crestY - baseCrest;

        // The crest line the surfer rides, from the lip down to the shoulder.
        crestPath.rewind();
        // Starts off the right edge so the wide glow strokes have no butt end inside the face.
        crestPath.moveTo(w + dp(60f), crestY);
        crestPath.lineTo(lipX, crestY);
        for (float px = lipX - dp(10f); px >= lipX - faceRun; px -= dp(10f)) {
            crestPath.lineTo(px, faceY(px, lipX, crestY, troughY, faceRun));
        }
        // The wave face: a curve from the lip down-left into the trough.
        facePath.rewind();
        facePath.moveTo(lipX + w * 0.3f, crestY);
        facePath.lineTo(lipX, crestY);
        for (float px = lipX; px >= lipX - faceRun; px -= dp(10f)) {
            facePath.lineTo(px, faceY(px, lipX, crestY, troughY, faceRun));
        }
        facePath.lineTo(lipX - faceRun, h);
        facePath.lineTo(w, h);
        facePath.close();
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(faceGrad);
        c.drawPath(facePath, paint);
        paint.setShader(null);
        c.save();
        c.clipPath(facePath);
        // Backlit water: the top of the face is thin enough for the low sun to shine through, so
        // it glows green-blue along the crest and deepens away from it. Strokes of the crest
        // line, widest and faintest first, clipped to the face so only the inside half shows.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int k = 0; k < GLASS_W.length; k++) {
            paint.setStrokeWidth(dp(GLASS_W[k]));
            paint.setColor(alpha(glassColor, GLASS_A[k]));
            c.drawPath(crestPath, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        // The thinnest, brightest water is under the peak, where the face stands tallest.
        Fx.glow(c, lipX - faceRun * 0.16f, crestY + dp(46f), dp(230f), alpha(glassLight, 0x50));
        Fx.glow(c, lipX - dp(40f), crestY + dp(20f), dp(110f), 0x44FFE9C0);
        paint.setStyle(Paint.Style.STROKE);
        // Lit edge right along the crest.
        paint.setStrokeWidth(dp(6f));
        paint.setColor(alpha(glassLight, 0xA0));
        c.drawPath(crestPath, paint);
        // Flow lines: the face's contour, drawn up toward the crest as the wave stands up.
        paint.setStrokeWidth(dp(2.2f));
        float flowSpan = (troughY - crestY) * 0.6f;
        float flowPhase = (float) ((sessionSeconds * 0.16) % 1.0);
        for (int k = 0; k < 6; k++) {
            float f = ((k + 1f - flowPhase) % 6f) / 6f;     // 0 at the crest .. 1 deep on the face
            float off = dp(20f) + f * flowSpan;
            float a = 54f * (1f - f) * Math.min(1f, f * 5f);   // fades out at both ends: no pop on wrap
            paint.setColor(alpha(glassLight, a));
            c.save();
            c.translate(-off * 0.3f, off);
            c.drawPath(crestPath, paint);
            c.restore();
        }
        // Short chop that follows the face rather than long streaks across it.
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 40; i++) {
            float phase = (float) ((i * 0.061 + sessionSeconds * 0.30) % 1.0);
            float px0 = lipX - faceRun * phase;
            float down = dp(18f) + (i % 5) * dp(26f);
            float py0 = faceY(px0, lipX, crestY, troughY, faceRun) + down;
            float len = dp(12f) + (i % 3) * dp(8f);
            paint.setStrokeWidth(dp(1.4f) + (i % 3) * dp(0.5f));
            int a = 22 + (int) (28 * (1 + Math.sin(i * 1.7 + sessionSeconds * 3)));
            paint.setColor((a << 24) | 0xFFFFFF);
            float py1 = faceY(px0 - len, lipX, crestY, troughY, faceRun) + down + dp(2f);
            c.drawLine(px0, py0, px0 - len, py1, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 18; i++) {
            float phase = (float) ((i * 0.09 + sessionSeconds * 0.5) % 1.0);
            float gx0 = lipX - faceRun * phase;
            float gy0 = faceY(gx0, lipX, crestY, troughY, faceRun) + dp(16f) + (i % 5) * dp(12f);
            float tw = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6 + i * 1.3);
            paint.setColor(((int) (80 * tw) << 24) | 0xFFF1C8);
            c.drawOval(gx0 - dp(14f), gy0 - dp(2f), gx0 + dp(14f), gy0 + dp(2f), paint);
        }
        // Foam lace on the shoulder, where the face runs out: two wavering threads that ride the
        // face, plus a scatter of bubbles - not a row of beads.
        paint.setStyle(Paint.Style.STROKE);
        for (int k = 0; k < 2; k++) {
            lacePath.rewind();
            boolean first = true;
            for (float px = lipX - faceRun * 0.4f; px >= lipX - faceRun; px -= dp(14f)) {
                float py = faceY(px, lipX, crestY, troughY, faceRun) + dp(20f + k * 17f)
                        + (float) Math.sin(px / dp(38f) + sessionSeconds * (2.4 + k) + k * 2.0) * dp(5f);
                if (first) {
                    lacePath.moveTo(px, py);
                    first = false;
                } else {
                    lacePath.lineTo(px, py);
                }
            }
            paint.setStrokeWidth(dp(k == 0 ? 3.4f : 2.2f));
            paint.setColor(k == 0 ? 0x70FFFFFF : 0x40FFFFFF);
            c.drawPath(lacePath, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 16; i++) {
            float phase = (float) ((i * 0.061 + sessionSeconds * 0.22) % 1.0);
            float bx0 = lipX - faceRun * (0.5f + 0.5f * phase);
            float by0 = faceY(bx0, lipX, crestY, troughY, faceRun) + dp(28f) + (i % 4) * dp(9f)
                    + (float) Math.sin(i * 2.1 + sessionSeconds * 7) * dp(4f);
            paint.setColor(0x48FFFFFF);
            c.drawCircle(bx0, by0, dp(2.5f) + (i % 3) * dp(1.5f), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        c.restore();

        // Whitewater: the section to the right of the curl has already broken, so it is a churning
        // pile of foam that thins out down the face. Built at the resting crest, drawn lifted.
        c.save();
        c.translate(0, lift);
        float cb = baseCrest;
        float soupLeft = lipX - dp(12f);
        soupPath.rewind();
        soupPath.moveTo(soupLeft, cb + dp(24f));
        for (float px = soupLeft; px <= w + dp(24f); px += dp(16f)) {
            float grow = Math.min(1f, (px - soupLeft) / dp(46f));
            float bump = (float) (Math.sin(px / dp(29f) + sessionSeconds * 3.1) * 0.55
                    + Math.sin(px / dp(13f) - sessionSeconds * 4.7) * 0.45);
            soupPath.lineTo(px, cb + dp(24f) * (1f - grow) + grow * (-dp(5f) - bump * dp(7f)));
        }
        soupPath.lineTo(w + dp(24f), cb + h * 0.17f);
        soupPath.lineTo(lipX + dp(50f), cb + h * 0.17f);
        soupPath.close();
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(soupGrad);
        c.drawPath(soupPath, paint);
        paint.setShader(null);
        // Bubble holes in the foam, sliding down and fading as it thins.
        for (int i = 0; i < 10; i++) {
            float f = (float) ((i * 0.13 + sessionSeconds * 0.18) % 1.0);
            float hx = lipX + dp(30f) + (w - lipX) * ((i * 0.37f) % 1f);
            float hy = cb + dp(8f) + f * h * 0.11f;
            paint.setColor(alpha(SPOT_FACE[spot][0], 70 * (1f - f)));
            c.drawOval(hx - dp(9f), hy - dp(2.5f), hx + dp(9f), hy + dp(2.5f), paint);
        }
        // The crest of the foam pile: shaded blobs, lit from the upper left, churning.
        for (int i = 0; i < 13; i++) {
            float fx = lipX + dp(8f) + (w - lipX) * ((i + 0.3f) / 13f)
                    + (float) Math.sin(i * 3.1 + sessionSeconds * 1.3) * dp(6f);
            float fy = cb - dp(3f) + (float) Math.sin(i * 1.9 + sessionSeconds * 5.0) * dp(5f);
            float r = dp(9f) + (i % 3) * dp(4f);
            paint.setColor(0x669DB8CC);
            c.drawCircle(fx + r * 0.22f, fy + r * 0.3f, r, paint);
            paint.setColor(0xF4FFFFFF);
            c.drawCircle(fx - r * 0.05f, fy - r * 0.05f, r * 0.86f, paint);
        }
        c.restore();

        // The tube: the hollow under the lip, dark, with its wall spinning.
        float tipY = crestY + dp(18f);
        float landX = tipX - open * dp(40f);
        float landY = faceY(landX, lipX, crestY, troughY, faceRun);
        cavityPath.rewind();
        cavityPath.moveTo(lipX, crestY);
        for (float px = lipX - dp(10f); px > landX; px -= dp(10f)) {
            cavityPath.lineTo(px, faceY(px, lipX, crestY, troughY, faceRun));
        }
        cavityPath.lineTo(landX, landY);
        cavityPath.quadTo(tipX - dp(10f) * open, (tipY + landY) / 2f, tipX, tipY + dp(3f));
        cavityPath.cubicTo(lipX - throwShown * 0.6f, crestY - dp(16f), lipX - dp(6f), crestY + dp(12f),
                lipX, crestY + dp(30f));
        cavityPath.close();
        paint.setColor(alpha(cavityColor, 0xF2));
        c.drawPath(cavityPath, paint);
        c.save();
        c.clipPath(cavityPath);
        // Light through the thin back wall of the tube, brightest near the opening.
        Fx.glow(c, tipX + dp(30f), (tipY + landY) / 2f, dp(150f), alpha(glassColor, 0x70));
        Fx.glow(c, lipX - throwShown * 0.4f, crestY + dp(10f), dp(120f), alpha(glassColor, 0x40));
        // The wall spinning round the tube: arcs parallel to the lip, moving down and in.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        rimPath.rewind();
        rimPath.moveTo(lipX, crestY);
        rimPath.cubicTo(lipX + dp(10f), crestY - dp(46f), lipX - throwShown * 0.5f, crestY - dp(54f),
                tipX, crestY + dp(18f));
        float spin = (float) ((sessionSeconds * (0.9 + open * 0.8)) % 1.0);
        for (int k = 0; k < 4; k++) {
            float f = (k + spin) / 4f;
            paint.setStrokeWidth(dp(3f));
            paint.setColor(alpha(glassLight, 70 * (1f - f)));
            c.save();
            c.translate(-f * dp(10f), dp(22f) + f * (landY - crestY) * 0.55f);
            c.drawPath(rimPath, paint);
            c.restore();
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        c.restore();

        // The curtain: in a barrel the lip pours down in a translucent sheet and explodes on the
        // face ahead of you.
        if (open > 0.02f) {
            lacePath.rewind();
            lacePath.moveTo(tipX + dp(12f), tipY - dp(8f));
            lacePath.quadTo(tipX - dp(22f) * open, (tipY + landY) / 2f, landX - dp(16f) * open, landY);
            lacePath.lineTo(landX + dp(8f), landY);
            lacePath.quadTo(tipX - dp(10f) * open, (tipY + landY) / 2f, tipX, tipY + dp(3f));
            lacePath.close();
            paint.setColor(alpha(glassColor, 0xA8 * open));
            c.drawPath(lacePath, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(alpha(0xFFFFFF, 0x90 * open));
            c.drawPath(lacePath, paint);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 7; i++) {
                float fx = landX - dp(26f) + i * dp(9f) + (float) Math.sin(i * 2.3 + sessionSeconds * 9) * dp(4f);
                float fy = landY - dp(4f) + (float) Math.sin(i * 1.4 + sessionSeconds * 11) * dp(5f);
                float r = (dp(6f) + (i % 3) * dp(3f)) * open;
                paint.setColor(0x669DB8CC);
                c.drawCircle(fx + r * 0.25f, fy + r * 0.3f, r, paint);
                paint.setColor(0xF0FFFFFF);
                c.drawCircle(fx, fy, r * 0.85f, paint);
            }
            curtainAccum += dt * 34f * open;
            while (curtainAccum >= 1f) {
                curtainAccum -= 1f;
                spray.spawn(landX + (float) (Math.random() - 0.5) * dp(30f), landY,
                        (float) (Math.random() - 0.6) * dp(160f), -dp(60f) - (float) Math.random() * dp(120f),
                        0.6f, dp(2.6f), 0xCCEAF6FF, true);
            }
        }

        // The lip itself: glassy and lit from behind at its thin top, deeper in the throat.
        c.save();
        c.translate(0, lift);
        lipPath.rewind();
        lipPath.moveTo(lipX, cb);
        lipPath.cubicTo(lipX + dp(10f), cb - dp(46f), lipX - throwShown * 0.5f, cb - dp(54f),
                tipX, cb + dp(18f));
        lipPath.cubicTo(lipX - throwShown * 0.6f, cb - dp(16f), lipX - dp(6f), cb + dp(12f),
                lipX, cb + dp(30f));
        lipPath.close();
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(lipGrad);
        c.drawPath(lipPath, paint);
        paint.setShader(null);
        c.save();
        c.clipPath(lipPath);
        Fx.glow(c, lipX - throwShown * 0.28f, cb - dp(34f), dp(70f), 0x66FFE9C0);
        c.restore();
        // Feathering foam along the lip's leading edge.
        rimPath.rewind();
        rimPath.moveTo(lipX, cb);
        rimPath.cubicTo(lipX + dp(10f), cb - dp(46f), lipX - throwShown * 0.5f, cb - dp(54f),
                tipX, cb + dp(18f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(10f));
        paint.setColor(alpha(glassLight, 0x70));
        c.drawPath(rimPath, paint);
        paint.setStrokeWidth(dp(4f));
        paint.setColor(0xE8FFFFFF);
        c.drawPath(rimPath, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
        // Whitewater under the curl, gathered at its root once the barrel opens.
        for (int i = 0; i < 16; i++) {
            float along = (i / 16f) * (1f - 0.65f * open);
            float fx0 = lipX - throwShown * along;
            float fy0 = crestY + dp(20f) + (float) Math.sin(i * 1.7 + sessionSeconds * 8) * dp(7f);
            float r = dp(7f) + (i % 3) * dp(3f);
            paint.setColor(0x669DB8CC);
            c.drawCircle(fx0 + r * 0.22f, fy0 + r * 0.3f, r, paint);
            paint.setColor(0xEEFFFFFF);
            c.drawCircle(fx0 - r * 0.05f, fy0 - r * 0.05f, r * 0.86f, paint);
        }
        // Spindrift: the offshore breeze tears spray back off the top of the curl and the pile.
        // A little more of it when you are moving well and when a set stands the wave up.
        mistAccum += dt * (10f + speed * 5f + setLift * 16f);
        while (mistAccum >= 1f) {
            mistAccum -= 1f;
            float u = (float) Math.random();
            float mx;
            float my;
            if (Math.random() < 0.7) {
                mx = lipX + dp(8f) - throwShown * 0.5f * u;
                my = crestY - dp(40f) - (float) Math.random() * dp(10f);
            } else {
                mx = lipX + (w - lipX) * u;
                my = crestY - dp(8f);
            }
            mist.spawn(mx, my, dp(50f) + (float) Math.random() * dp(110f),
                    -dp(22f) - (float) Math.random() * dp(55f), 0.7f + (float) Math.random() * 0.6f,
                    dp(2f) + (float) Math.random() * dp(2.8f), 0x99F4FBFF, false);
        }
        mist.step(dt, 0f);
        mist.draw(c);

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
            label(c, NPC_NAMES[npcRider], nx, ny - hop - dp(42f), 9f, NPC_COLORS[npcRider], Paint.Align.CENTER);
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
            drawDolphin(c, dx, dy, -60 + 120 * f, 1f, 0xFF6E8FA8);
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

        // Barrel framing: the lip arcs right over the top of the view, its lit underside dripping.
        if (inBarrel() && phase == Phase.RIDING) {
            paint.setColor(alpha(cavityColor, 0xD8));
            path.rewind();
            path.moveTo(0, 0);
            path.lineTo(w, 0);
            path.lineTo(w, h * 0.16f);
            path.cubicTo(w * 0.6f, h * 0.34f, w * 0.3f, h * 0.10f, 0, h * 0.22f);
            path.close();
            c.drawPath(path, paint);
            lacePath.rewind();
            lacePath.moveTo(w, h * 0.16f);
            lacePath.cubicTo(w * 0.6f, h * 0.34f, w * 0.3f, h * 0.10f, 0, h * 0.22f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(14f));
            paint.setColor(alpha(glassColor, 0x70));
            c.drawPath(lacePath, paint);
            paint.setStrokeWidth(dp(3.5f));
            paint.setColor(0xC0FFFFFF);
            c.drawPath(lacePath, paint);
            paint.setStyle(Paint.Style.FILL);
            if (Math.random() < dt * 14f) {
                // A drip off the overhead lip, at a point on that same curve.
                float u = (float) Math.random();
                float v = 1f - u;
                float ex = v * v * v * w + 3 * v * v * u * w * 0.6f + 3 * v * u * u * w * 0.3f;
                float ey = v * v * v * h * 0.16f + 3 * v * v * u * h * 0.34f + 3 * v * u * u * h * 0.10f
                        + u * u * u * h * 0.22f;
                spray.spawn(ex, ey, (float) (Math.random() - 0.5) * dp(20f), dp(30f), 0.9f, dp(2.6f),
                        0xCCEAF6FF, true);
            }
            vignette(c, w, h, 0.55f, vigBarrel);
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
            vignette(c, w, h, 0.25f + Math.abs(position) * 0.5f,
                    position < 0 ? vigDanger : vigShoulder);
        }

        // ---------- HUD ----------
        // The band: where you are on the face, with the pocket and the trick zone marked.
        float bandW = w * 0.52f;
        float bx = w * 0.5f - bandW / 2f;
        // 3.25: raised, with its labels above the track. They used to sit on the footer's value
        // line, so LIP ran into POINTS and SHOULDER into MEDALS.
        float by = h - dp(64f);
        panel(c, bx - dp(16f), by - dp(26f), bx + bandW + dp(16f), by + dp(20f), dp(12f), 0x99);
        paint.setColor(0x66000000);
        c.drawRoundRect(bx, by, bx + bandW, by + dp(14f), dp(7f), dp(7f), paint);
        paint.setColor(0x4435D0BA);
        c.drawRect(bx + bandW * 0.325f, by, bx + bandW * 0.675f, by + dp(14f), paint);
        paint.setColor(0x66F5C518);
        c.drawRect(bx + bandW * 0.05f, by + dp(10f), bx + bandW * 0.45f, by + dp(14f), paint);
        gloss(c, bx, by, bx + bandW, by + dp(14f));
        float px = bx + bandW * ((position + 1f) / 2f);
        int markCol = inPocket() ? ACCENT : position < 0 ? BAD : WARN;
        Fx.glow(c, px, by + dp(7f), dp(22f), alpha(markCol, 0x60));
        paint.setColor(markCol);
        c.drawRoundRect(px - dp(4f), by - dp(4f), px + dp(4f), by + dp(18f), dp(3f), dp(3f), paint);
        gloss(c, px - dp(4f), by - dp(4f), px + dp(4f), by + dp(18f));
        label(c, "LIP", bx, by - dp(8f), 8.5f, BAD, Paint.Align.LEFT);
        label(c, "TRICK ZONE", bx + bandW * 0.25f, by - dp(8f), 8.5f, 0xFFF5C518, Paint.Align.CENTER);
        label(c, "POCKET", w * 0.5f, by - dp(8f), 8.5f, ACCENT, Paint.Align.CENTER);
        label(c, "SHOULDER", bx + bandW, by - dp(8f), 8.5f, WARN, Paint.Align.RIGHT);

        // Sweet-spot bar up the right edge: where you are on the face, the pocket in green, and an
        // arrow for which way you are drifting - up when you are faster than the wave, down when
        // slower. Readable at a glance, mid-stroke, without looking for the surfer.
        float sx = w - dp(30f);
        float sTop = h * 0.16f;
        float sBottom = h * 0.70f;
        float sMid = (sTop + sBottom) / 2f;
        float half = (sBottom - sTop) / 2f;
        // Backed by a panel now: the whitewater behind it is bright.
        panel(c, sx - dp(78f), sTop - dp(28f), w - dp(4f), sBottom + dp(26f), dp(12f), 0xA0);
        paint.setColor(0x66000000);
        c.drawRoundRect(sx - dp(10f), sTop, sx + dp(10f), sBottom, dp(10f), dp(10f), paint);
        paint.setColor(0x7735D0BA);
        c.drawRect(sx - dp(10f), sMid - half * 0.35f, sx + dp(10f), sMid + half * 0.35f, paint);
        paint.setColor(0x88F5C518);
        c.drawRect(sx - dp(10f), sMid + half * 0.1f, sx - dp(6f), sMid + half * 0.9f, paint);
        paint.setColor(0x26FFFFFF);
        c.drawRoundRect(sx - dp(9f), sTop + dp(2f), sx - dp(3f), sBottom - dp(2f), dp(3f), dp(3f), paint);
        float marker = sMid - half * Math.max(-1f, Math.min(1f, position));
        Fx.glow(c, sx, marker, dp(30f), alpha(markCol, 0x60));
        paint.setColor(markCol);
        c.drawRoundRect(sx - dp(16f), marker - dp(5f), sx + dp(16f), marker + dp(5f), dp(4f), dp(4f), paint);
        gloss(c, sx - dp(16f), marker - dp(5f), sx + dp(16f), marker + dp(5f));
        paint.setColor(markCol);
        if (phase == Phase.RIDING && Math.abs(speed - wave) > 0.08f) {
            boolean up = speed > wave;
            float ay = marker + (up ? -dp(14f) : dp(14f));
            path.rewind();
            path.moveTo(sx - dp(8f), ay + (up ? dp(6f) : -dp(6f)));
            path.lineTo(sx + dp(8f), ay + (up ? dp(6f) : -dp(6f)));
            path.lineTo(sx, ay - (up ? dp(6f) : -dp(6f)));
            path.close();
            c.drawPath(path, paint);
        }
        // Right-aligned inside the panel: centred on the bar, SHOULDER ran off the screen edge.
        label(c, "SHOULDER", w - dp(10f), sTop - dp(9f), 8f, WARN, Paint.Align.RIGHT);
        label(c, "LIP", sx, sBottom + dp(18f), 8f, BAD, Paint.Align.CENTER);
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
                    cap = "PULL HARDER - WAVE IS DOING " + score1(wave) + " m/s";
                    col = BAD;
                } else if (inTrickZone() && rideSeconds > 3
                        && sessionSeconds - trickStart > TRICK_LEN + TRICK_COOLDOWN) {
                    cap = "TOP OF THE WAVE - SURGE PAST " + surgeWatts() + " W FOR A TRICK";
                    col = 0xFFF5C518;
                } else if (inPocket()) {
                    cap = "IN THE POCKET - DOUBLE SCORE";
                    col = ACCENT;
                } else if (position < 0) {
                    cap = "PULL HARDER - WAVE IS DOING " + score1(wave) + " m/s";
                    col = BAD;
                } else {
                    cap = "EASE OFF - WAVE IS DOING " + score1(wave) + " m/s";
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
        int skyTop = SPOT_SKY[spot][0];
        int skyLow = SPOT_SKY[spot][1];
        int seaTop = SPOT_SEA[spot][0];
        int seaLow = SPOT_SEA[spot][1];
        int faceHi = SPOT_FACE[spot][0];
        int faceLo = SPOT_FACE[spot][1];
        float seaBottom = h * 0.62f;
        skyGrad = new LinearGradient(0, 0, 0, horizon,
                new int[] {mix(skyTop, 0xFF000000, 0.25f), skyTop, mix(skyTop, skyLow, 0.55f), skyLow},
                new float[] {0f, 0.35f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        horizonGlowGrad = new LinearGradient(0, horizon * 0.45f, 0, horizon,
                alpha(mix(skyLow, 0xFFFFF4E0, 0.35f), 0), alpha(mix(skyLow, 0xFFFFF4E0, 0.35f), 0x80),
                Shader.TileMode.CLAMP);
        // The sea darkens toward the viewer, and the far water takes the sky's colour (haze).
        seaGrad = new LinearGradient(0, horizon, 0, h,
                new int[] {mix(seaTop, skyLow, 0.18f), seaTop, seaLow, mix(seaLow, 0xFF000000, 0.3f)},
                new float[] {0f, 0.12f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        hazeGrad = new LinearGradient(0, horizon, 0, horizon + (seaBottom - horizon) * 0.55f,
                alpha(mix(skyLow, seaTop, 0.45f), 0x78), alpha(mix(skyLow, seaTop, 0.45f), 0),
                Shader.TileMode.CLAMP);
        sunColumnGrad = new LinearGradient(0, horizon, 0, seaBottom + h * 0.08f,
                0x88FFE3B0, 0x00FFE3B0, Shader.TileMode.CLAMP);
        // The face: lit near the crest, deep at the base, nearly black where it meets the frame.
        faceGrad = new LinearGradient(0, crestY, 0, h,
                new int[] {mix(faceHi, 0xFFFFFFFF, 0.06f), faceHi, faceLo, mix(faceLo, 0xFF000000, 0.35f)},
                new float[] {0f, 0.22f, 0.7f, 1f}, Shader.TileMode.CLAMP);
        glassColor = mix(SPOT_LIP[spot], 0xFF3FE6C0, 0.42f);
        glassLight = mix(glassColor, 0xFFFFFFFF, 0.55f);
        cavityColor = mix(faceLo, 0xFF000000, 0.45f);
        // Thin water at the top of the lip glows; the throat of the curl is deeper.
        lipGrad = new LinearGradient(0, crestY - dp(58f), 0, crestY + dp(34f),
                new int[] {glassLight, glassColor, SPOT_LIP[spot], faceHi},
                new float[] {0f, 0.3f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        soupGrad = new LinearGradient(0, crestY - dp(14f), 0, crestY + h * 0.17f,
                new int[] {0xF8FFFFFF, 0xD8EAF6FF, 0x70D8ECF8, 0x00D8ECF8},
                new float[] {0f, 0.3f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        float vr = Math.max(w, h) * 0.75f;
        float[] vStops = {0f, 0.55f, 1f};
        vigDanger = new RadialGradient(w / 2f, h / 2f, vr, new int[] {0, 0, 0xC88A1010}, vStops,
                Shader.TileMode.CLAMP);
        vigShoulder = new RadialGradient(w / 2f, h / 2f, vr, new int[] {0, 0, 0xC81A3A5A}, vStops,
                Shader.TileMode.CLAMP);
        vigBarrel = new RadialGradient(w / 2f, h / 2f, vr, new int[] {0, 0, 0xC808243A}, vStops,
                Shader.TileMode.CLAMP);
        // Land on the horizon: a nearer headland and a paler island beyond it (aerial perspective).
        nearLandColor = mix(skyLow, seaLow, 0.62f);
        farLandColor = mix(skyLow, seaTop, 0.32f);
        cloudBody = mix(mix(skyTop, skyLow, 0.6f), 0xFFFFFFFF, 0.12f);
        cloudRim = mix(skyLow, 0xFFFFF0D8, 0.45f);
        headlandPath.rewind();
        float hx0 = w * 0.29f;
        float hx1 = w * 0.52f;
        headlandPath.moveTo(hx0, horizon + dp(2f));
        for (int i = 0; i <= 28; i++) {
            float f = i / 28f;
            float env = (float) Math.sin(Math.PI * Math.pow(f, 0.62));
            float ridge = 0.78f + 0.14f * (float) Math.sin(i * 2.3) + 0.08f * (float) Math.sin(i * 5.1);
            headlandPath.lineTo(hx0 + (hx1 - hx0) * f, horizon - dp(30f) * env * ridge);
        }
        headlandPath.lineTo(hx1, horizon + dp(2f));
        headlandPath.close();
        islandPath.rewind();
        float ix0 = w * 0.50f;
        float ix1 = w * 0.63f;
        islandPath.moveTo(ix0, horizon + dp(2f));
        for (int i = 0; i <= 16; i++) {
            float f = i / 16f;
            float env = (float) Math.sin(Math.PI * f);
            float ridge = 0.8f + 0.2f * (float) Math.sin(i * 3.7);
            islandPath.lineTo(ix0 + (ix1 - ix0) * f, horizon - dp(14f) * env * ridge);
        }
        islandPath.lineTo(ix1, horizon + dp(2f));
        islandPath.close();
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
        paint.setColor(0x44E8F4FF);
        c.drawOval(x - 24 * s, y + 2 * s, x + 24 * s, y + 8 * s, paint);          // ripple
        paint.setColor(0x26E8F4FF);
        c.drawOval(x - 31 * s, y + 5 * s, x + 31 * s, y + 11 * s, paint);
        drawSurfboard(c, x, y + 1 * s, 0f, 0.95f, 0xFFF2EAD8, true);
        // Sitting astride the board: thigh, torso, arms - round-capped limbs over a dark outline.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            float extra = pass == 0 ? 2.4f * s : 0f;
            paint.setColor(pass == 0 ? 0xC0061420 : color);
            paint.setStrokeWidth(4.2f * s + extra);
            c.drawLine(x - 1 * s, y - 3 * s, x + 7 * s, y + 1 * s, paint);       // thigh
            paint.setStrokeWidth(6.2f * s + extra);
            c.drawLine(x - 1 * s, y - 3 * s, x, y - 14 * s, paint);              // torso
            paint.setStrokeWidth(3.2f * s + extra);
            path.rewind();
            if (armsUp) {
                path.moveTo(x - 7 * s, y - 28 * s);
                path.lineTo(x - 5 * s, y - 20 * s);
                path.lineTo(x, y - 13 * s);
                path.lineTo(x + 5 * s, y - 20 * s);
                path.lineTo(x + 7 * s, y - 28 * s);
            } else {
                path.moveTo(x - 6 * s, y - 2 * s);
                path.lineTo(x - 5 * s, y - 8 * s);
                path.lineTo(x, y - 13 * s);
                path.lineTo(x + 5 * s, y - 8 * s);
                path.lineTo(x + 6 * s, y - 2 * s);
            }
            c.drawPath(path, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStyle(Paint.Style.FILL);
        drawHead(c, x, y - 19 * s, 4 * s);
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
            gloss(c, x0, y, x0 + barW, y + dp(6f));
            label(c, "GOLD - KEEP RIDING", cx, y + dp(18f), 8.5f, MEDAL_COLORS[2], Paint.Align.CENTER);
            return;
        }
        float from = next == 0 ? 0f : MEDAL_AT[next - 1];
        float frac = (float) Math.max(0, Math.min(1, (rideSeconds - from) / (MEDAL_AT[next] - from)));
        paint.setColor(MEDAL_COLORS[next]);
        c.drawRoundRect(x0, y, x0 + barW * frac, y + dp(6f), dp(3f), dp(3f), paint);
        gloss(c, x0, y, x0 + barW * frac, y + dp(6f));
        if (rideMedal >= 0) {
            drawMedal(c, x0 - dp(14f), y + dp(3f), dp(7f), MEDAL_COLORS[rideMedal]);
        }
        label(c, MEDAL_NAMES[next] + " IN " + clock(Math.max(0, MEDAL_AT[next] - rideSeconds)),
                cx, y + dp(18f), 8.5f, MEDAL_COLORS[next], Paint.Align.CENTER);
    }

    private void drawMedal(Canvas c, float x, float y, float r, int color) {
        paint.setColor(0xFF2E6FD8);
        c.drawRect(x - r * 0.5f, y - r * 2.1f, x + r * 0.5f, y - r * 0.6f, paint);   // ribbon
        paint.setColor(mix(color, 0xFF000000, 0.3f));
        c.drawCircle(x, y, r, paint);
        paint.setColor(color);
        c.drawCircle(x - r * 0.08f, y - r * 0.08f, r * 0.8f, paint);
        paint.setColor(0x66FFFFFF);
        c.drawCircle(x - r * 0.3f, y - r * 0.3f, r * 0.32f, paint);
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
        panel(c, spotLeft, spotTop, spotRight, spotBottom, dp(10f), live ? 0xC8 : 0x80);
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
        gloss(c, spotLeft + dp(10f), barTop, spotLeft + dp(10f) + barW * frac, barTop + dp(4f));
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
        panel(c, x, y - dp(14f), x + boardW, y + dp(16f) * board.length + dp(6f), dp(10f), 0xB0);
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
                gloss(c, bx0, ry - dp(8f), bx0 + dp(18f) * f, ry);
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
        // 3.25: moved 28dp left and spread out - the STACK caption and the TRICK pip sat on the
        // ring, and TRICK and BARREL ran together into one word.
        float cx = w - dp(140f);
        float cy = dp(40f);
        float r = dp(26f);
        boolean live = stack > 1;
        float left = live ? (float) Math.max(0, Math.min(1, (stackUntil - sessionSeconds)
                / STACK_WINDOW)) : 0f;
        panel(c, cx - dp(34f), cy - dp(30f), cx + dp(116f), cy + dp(30f), dp(10f), live ? 0xC8 : 0x80);
        ring.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5f));
        paint.setColor(0x33FFFFFF);
        c.drawArc(ring, -90f, 360f, false, paint);
        if (live) {
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xFFF5C518);
            c.drawArc(ring, -90f, 360f * left, false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
        paint.setStyle(Paint.Style.FILL);
        if (live && sessionSeconds < comboUntil) {
            Fx.glow(c, cx, cy, dp(40f), 0x66F5C518);
        }
        // The figure itself pulses with the beat when the stack is hot, so it reads as alive.
        float pulse = live ? 1f + 0.12f * (float) Math.sin(sessionSeconds * 7) : 1f;
        bold(c, "x" + stack, cx, cy + dp(7f), 22f * pulse, live ? 0xFFF5C518 : FAINT,
                Paint.Align.CENTER);
        label(c, "STACK", cx + dp(72f), cy - dp(10f), 8.5f, live ? TEXT : FAINT, Paint.Align.CENTER);
        // Two pips: which halves of the combo you are holding.
        float pipT = cx + dp(52f);
        float pipB = cx + dp(92f);
        if (stackHasTrick) {
            Fx.glow(c, pipT, cy + dp(6f), dp(12f), 0x66F5C518);
        }
        if (stackHasBarrel) {
            Fx.glow(c, pipB, cy + dp(6f), dp(12f), 0x669FE3F0);
        }
        paint.setColor(stackHasTrick ? 0xFFF5C518 : 0x44FFFFFF);
        c.drawCircle(pipT, cy + dp(6f), dp(4.5f), paint);
        paint.setColor(stackHasBarrel ? 0xFF9FE3F0 : 0x44FFFFFF);
        c.drawCircle(pipB, cy + dp(6f), dp(4.5f), paint);
        label(c, "TRICK", pipT, cy + dp(22f), 7f, stackHasTrick ? 0xFFF5C518 : FAINT,
                Paint.Align.CENTER);
        label(c, "BARREL", pipB, cy + dp(22f), 7f, stackHasBarrel ? 0xFF9FE3F0 : FAINT,
                Paint.Align.CENTER);
    }

    /** Priority: the countdown that turns the wave over to you, and the reward for waiting. */
    private void drawPriority(Canvas c, float w, float h) {
        float cx = w * 0.34f;
        float cy = h * 0.52f;
        float r = dp(52f);
        double left = priorityAt - sessionSeconds;
        boolean yours = left <= 0;
        paint.setColor(0x55000000);
        c.drawCircle(cx, cy + dp(3f), r + dp(13f), paint);
        paint.setColor(0xCC0A111B);
        c.drawCircle(cx, cy, r + dp(12f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x802A3A50);
        c.drawCircle(cx, cy, r + dp(12f), paint);
        ring.set(cx - r, cy - r, cx + r, cy + r);
        paint.setStrokeWidth(dp(8f));
        paint.setColor(0x33FFFFFF);
        c.drawArc(ring, -90f, 360f, false, paint);
        int ringCol = yours ? ACCENT : WARN;
        paint.setColor(ringCol);
        paint.setStrokeCap(Paint.Cap.ROUND);
        float sweep = yours ? 360f
                : 360f * (float) Math.max(0, Math.min(1, 1 - left / PRIORITY_WAIT));
        c.drawArc(ring, -90f, sweep, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        // 3.25: the captions sit in a pill under the ring. Inside it they were wider than the
        // ring's hole and ran across the stroke.
        float capTop = cy + r + dp(16f);
        panel(c, cx - dp(150f), capTop, cx + dp(150f), capTop + dp(44f), dp(12f), 0xC0);
        if (yours) {
            Fx.glow(c, cx, cy, dp(74f), 0x5535D0BA);
            bold(c, "GO", cx, cy + dp(10f), 30f, ACCENT, Paint.Align.CENTER);
            label(c, "PRIORITY IS YOURS", cx, capTop + dp(18f), 9f, ACCENT, Paint.Align.CENTER);
            label(c, "clean take-off  +1.0  ·  8 s of extra grip", cx, capTop + dp(35f), 8.5f,
                    0xCC35D0BA, Paint.Align.CENTER);
        } else {
            bold(c, String.valueOf((int) Math.ceil(left)), cx, cy + dp(12f), 34f, WARN,
                    Paint.Align.CENTER);
            label(c, "PADDLING BACK OUT", cx, capTop + dp(18f), 9f, WARN, Paint.Align.CENTER);
            label(c, npcRider >= 0 ? NPC_NAMES[npcRider] + " has this one"
                    : "the lineup has this one", cx, capTop + dp(35f), 8.5f, DIM, Paint.Align.CENTER);
        }
    }

    /** You, paddling back out to the lineup while the priority clock runs. */
    private void drawPaddler(Canvas c, float w, float horizon, float seaBottom) {
        float x = w * (0.10f + paddleOut * 0.10f);
        float y = seaBottom + (1f - paddleOut) * dp(70f);
        float s = dp(1f);
        float stroke = (float) Math.sin(sessionSeconds * 3.4);
        paint.setColor(0x44E8F4FF);
        c.drawOval(x - 32 * s, y + 2 * s, x + 32 * s, y + 10 * s, paint);
        // A V of wake behind the tail.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0x50E8F4FF);
        c.drawLine(x - 26 * s, y + 2 * s, x - 52 * s, y - 3 * s, paint);
        c.drawLine(x - 26 * s, y + 4 * s, x - 52 * s, y + 10 * s, paint);
        paint.setStyle(Paint.Style.FILL);
        drawSurfboard(c, x, y, 0f, 1.25f, 0xFFF5C518, true);
        // Prone on the board, arms windmilling over the rail - the paddle stroke.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            float extra = pass == 0 ? 2.4f * s : 0f;
            paint.setColor(pass == 0 ? 0xC0061420 : ACCENT);
            paint.setStrokeWidth(4.2f * s + extra);
            c.drawLine(x - 12 * s, y - 6 * s, x - 24 * s, y - 5 * s, paint);       // legs
            paint.setStrokeWidth(6.4f * s + extra);
            c.drawLine(x - 12 * s, y - 6 * s, x + 5 * s, y - 8 * s, paint);        // body
            paint.setStrokeWidth(3.2f * s + extra);
            c.drawLine(x + 5 * s, y - 9 * s, x + 15 * s, y - 9 * s + stroke * 6 * s, paint);
            c.drawLine(x + 4 * s, y - 7 * s, x + 13 * s, y - 5 * s - stroke * 6 * s, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStyle(Paint.Style.FILL);
        drawHead(c, x + 10 * s, y - 13 * s, 4 * s);
        if (stroke > 0.9f) {
            spray.spawn(x + 16 * s, y - 6 * s, dp(30f), -dp(30f), 0.4f, dp(2f), 0x99EAF6FF, true);
        }
        label(c, "YOU", x, y - dp(28f), 8f, ACCENT, Paint.Align.CENTER);
    }

    /** Hung in the lip: the prompt that says the ride is still savable, and for how long. */
    private void drawLipSave(Canvas c, float x, float y, float w, float h) {
        float left = (float) Math.max(0, (saveUntil - sessionSeconds) / SAVE_WINDOW);
        Fx.glow(c, x, y - dp(20f), dp(70f), 0x66F0655D);
        float barW = dp(200f);
        float bx = w * 0.5f - barW / 2f;
        float by = h * 0.30f;
        panel(c, bx - dp(12f), by - dp(30f), bx + barW + dp(12f), by + dp(32f), dp(10f), 0xD0);
        bold(c, "PULL HARD - SAVE IT!", w * 0.5f, by - dp(8f), 17f,
                (Math.sin(sessionSeconds * 14) > 0 ? 0xFFF0655D : 0xFFFFFFFF), Paint.Align.CENTER);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(bx, by, bx + barW, by + dp(8f), dp(4f), dp(4f), paint);
        paint.setColor(BAD);
        c.drawRoundRect(bx, by, bx + barW * left, by + dp(8f), dp(4f), dp(4f), paint);
        gloss(c, bx, by, bx + barW * left, by + dp(8f));
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
        panel(c, cx - total / 2f - dp(16f), cy - dp(70f), cx + total / 2f + dp(16f),
                cy + dp(56f), dp(12f), 0xD8);
        label(c, "THE JUDGES", cx, cy - dp(52f), 9f, FAINT, Paint.Align.CENTER);
        for (int i = 0; i < judge.length; i++) {
            double at = JUDGE_CARD * (i + 1);
            if (since < at) {
                continue;
            }
            float f = (float) Math.min(1.0, (since - at) / 0.28);
            float x0 = cx - total / 2f + i * (cardW + gap);
            float rise = (1f - f) * dp(26f);
            paint.setColor(0x66000000);                    // card shadow
            c.drawRoundRect(x0 + dp(2f), cy - dp(37f) + rise, x0 + cardW + dp(2f), cy + dp(19f) + rise,
                    dp(6f), dp(6f), paint);
            paint.setColor(0xFFD9CFBB);
            c.drawRoundRect(x0, cy - dp(40f) + rise, x0 + cardW, cy + dp(16f) + rise,
                    dp(6f), dp(6f), paint);
            paint.setColor(0xFFF2EAD8);
            c.drawRoundRect(x0, cy - dp(40f) + rise, x0 + cardW, cy + dp(13f) + rise,
                    dp(6f), dp(6f), paint);
            paint.setColor(0xB0FFFFFF);
            c.drawRect(x0 + dp(6f), cy - dp(39f) + rise, x0 + cardW - dp(6f), cy - dp(38f) + rise, paint);
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
        panel(c, cx - panelW / 2f, top, cx + panelW / 2f, top + dp(196f), dp(14f), 0xF0);
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
            drawDolphin(c, dx, dy, (p - 0.5f) * 44f, 30f / 52f, 0xFF1B3E5E);
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
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        path.rewind();
        path.moveTo(sx0, sy0 + dp(8f));
        for (int i = 1; i <= 8; i++) {
            float px0 = sx0 + i * dp(22f);
            float py0 = faceY(px0, lipX, crestY, troughY, faceRun) + dp(8f)
                    + (float) Math.sin(i * 0.9 + sessionSeconds * 4) * dp(4f);
            path.lineTo(px0, py0);
        }
        // A soft foam wake under a bright carve line.
        paint.setStrokeWidth(dp(9f));
        paint.setColor(0x2AFFFFFF);
        c.drawPath(path, paint);
        paint.setStrokeWidth(dp(3f));
        paint.setColor(0x80FFFFFF);
        c.drawPath(path, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * Sky: a deepening gradient, a warm band at the horizon, the low sun with a two-layer halo,
     * sunset cloud banks lit from beneath, land on the horizon and the gulls.
     */
    private void drawSky(Canvas c, float w, float horizon, float dt) {
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyGrad);
        c.drawRect(0, 0, w, horizon + dp(2f), paint);
        paint.setShader(horizonGlowGrad);
        c.drawRect(0, horizon * 0.45f, w, horizon + dp(2f), paint);
        paint.setShader(null);
        float sunX = w * 0.22f;
        float sunY = horizon - dp(6f);
        Fx.glow(c, sunX, sunY, dp(170f), 0x40FFC890);
        Fx.glow(c, sunX, sunY, dp(70f), 0x88FFD9A0);
        paint.setColor(0xFFFFE0A8);
        c.drawCircle(sunX, sunY, dp(24f), paint);
        paint.setColor(0xFFFFF4DC);
        c.drawCircle(sunX - dp(2f), sunY - dp(2f), dp(17f), paint);
        // Cloud banks drift slowly; each is a flat base and three puffs, a warm underside showing
        // below a cooler body.
        cloudDrift += dt * dp(5f);
        for (int i = 0; i < CLOUD_X.length; i++) {
            float cw = dp(CLOUD_W[i]);
            float span = w + cw * 2f;
            float cx = ((CLOUD_X[i] * span + cloudDrift * (0.6f + i * 0.18f)) % span) - cw;
            float cy = horizon * CLOUD_Y[i];
            for (int pass = 0; pass < 2; pass++) {
                float dy = pass == 0 ? dp(3f) : 0f;
                paint.setColor(pass == 0 ? alpha(cloudRim, 0xA0) : alpha(cloudBody, 0xB4));
                c.drawOval(cx - cw * 0.5f, cy - cw * 0.05f + dy, cx + cw * 0.5f, cy + cw * 0.05f + dy, paint);
                c.drawCircle(cx - cw * 0.22f, cy - cw * 0.04f + dy, cw * 0.11f, paint);
                c.drawCircle(cx + cw * 0.02f, cy - cw * 0.07f + dy, cw * 0.15f, paint);
                c.drawCircle(cx + cw * 0.25f, cy - cw * 0.03f + dy, cw * 0.10f, paint);
            }
        }
        // Land on the horizon, the far island paler than the headland.
        paint.setColor(farLandColor);
        c.drawPath(islandPath, paint);
        paint.setColor(nearLandColor);
        c.drawPath(headlandPath, paint);
        // Seagulls gliding over, wings flexing.
        paint.setColor(0xFF2A2F3A);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2.4f));
        for (int i = 0; i < gullX.length; i++) {
            gullX[i] += dt * (0.018f + i * 0.006f);
            if (gullX[i] > 1.08f) {
                gullX[i] = -0.08f;
            }
            float gx = gullX[i] * w;
            float gy = horizon * (0.35f + i * 0.17f) + (float) Math.sin(sessionSeconds + i) * dp(8f);
            float flex = (float) Math.sin(sessionSeconds * 5 + i) * dp(4f);
            path.rewind();
            path.moveTo(gx - dp(13f), gy - flex);
            path.quadTo(gx - dp(6f), gy - dp(5f) - flex * 0.5f, gx, gy);
            path.quadTo(gx + dp(6f), gy - dp(5f) - flex * 0.5f, gx + dp(13f), gy - flex);
            c.drawPath(path, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A dolphin silhouette with a pale belly; {@code size} 1 is 52dp long. */
    private void drawDolphin(Canvas c, float x, float y, float degrees, float size, int color) {
        float s = dp(1f) * size;
        c.save();
        c.translate(x, y);
        c.rotate(degrees);
        c.scale(s, s);
        paint.setColor(color);
        c.drawPath(dolphinPath, paint);
        c.drawPath(finPath, paint);
        paint.setColor(alpha(mix(color, 0xFFFFFFFF, 0.45f), 0xB0));
        c.drawOval(-12f, 1.5f, 18f, 5f, paint);
        paint.setColor(0x55FFFFFF);                     // wet highlight along the back
        c.drawOval(-8f, -7.5f, 14f, -5f, paint);
        c.restore();
    }

    /** Head with a contact outline and a dark hair cap; the light is from the upper left. */
    private void drawHead(Canvas c, float x, float y, float r) {
        paint.setColor(0xD0061420);
        c.drawCircle(x, y, r + dp(1.3f), paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(x, y, r, paint);
        paint.setColor(0xFF3A2416);
        ring.set(x - r, y - r, x + r, y + r);
        c.drawArc(ring, 160f, 220f, false, paint);
    }

    /** A surfboard along +x, rotated, with a darker rail, a stringer and a soft contact shadow. */
    private void drawSurfboard(Canvas c, float x, float y, float degrees, float size, int deck, boolean shadow) {
        float s = dp(1f) * size;
        c.save();
        c.translate(x, y);
        c.rotate(degrees);
        c.scale(s, s);
        if (shadow) {
            paint.setColor(0x40031018);
            c.drawOval(-24f, 2f, 24f, 8f, paint);
        }
        paint.setColor(mix(deck, 0xFF000000, 0.35f));
        c.save();
        c.translate(0f, 1.3f);
        c.drawPath(boardPath, paint);
        c.restore();
        paint.setColor(deck);
        c.drawPath(boardPath, paint);
        paint.setColor(0x90FFFFFF);
        c.drawRect(-17f, -0.45f, 19f, 0.45f, paint);
        c.restore();
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
        // Board, angled along the face, with its shadow on the water.
        drawSurfboard(c, x, y, 24f, scale, 0xFFF5C518, true);
        // The figure: round-capped limbs over a dark outline, so it holds against bright foam and
        // dark water alike. Feet sit on the deck, knees bent, leaning into the face.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int pass = 0; pass < 2; pass++) {
            float extra = pass == 0 ? 2.6f * s : 0f;
            paint.setColor(pass == 0 ? 0xD0061420 : body);
            if (superman) {
                // Hands on the rail, body flying out flat behind.
                paint.setStrokeWidth(4.4f * s + extra);
                c.drawLine(x - 16 * s, y - 14 * s, x - 30 * s, y - 17 * s, paint); // legs
                paint.setStrokeWidth(6.4f * s + extra);
                c.drawLine(x - 2 * s, y - 12 * s, x - 16 * s, y - 14 * s, paint);  // body
                paint.setStrokeWidth(3.2f * s + extra);
                c.drawLine(x - 1 * s, y - 12 * s, x + 2 * s, y - 3 * s, paint);    // arms to board
                continue;
            }
            paint.setStrokeWidth(4.6f * s + extra);
            path.rewind();
            path.moveTo(x - 9 * s, y - 6 * s);                     // back foot
            path.lineTo(x - 7 * s, y - 13 * s);                    // back knee
            path.lineTo(x - 1 * s, y - 17 * s);                    // hip
            path.lineTo(x + 9 * s, y - 9 * s);                     // front knee
            path.lineTo(x + 8 * s, y + 0.5f * s);                  // front foot
            c.drawPath(path, paint);
            paint.setStrokeWidth(6.6f * s + extra);
            c.drawLine(x - 1 * s, y - 17 * s, x + 3.5f * s, y - 27 * s, paint);  // torso
            paint.setStrokeWidth(3.4f * s + extra);
            path.rewind();
            path.moveTo(x + 17 * s, y - 22 * s);                   // lead hand, reaching
            path.lineTo(x + 10 * s, y - 26 * s);
            path.lineTo(x + 3.5f * s, y - 26 * s);                 // shoulders
            path.lineTo(x - 3 * s, y - 23 * s);
            path.lineTo(x - 10 * s, y - 19 * s);                   // trailing hand
            c.drawPath(path, paint);
        }
        // Light from the upper left: a thin highlight down the lit side of the torso.
        if (!superman) {
            paint.setStrokeWidth(1.6f * s);
            paint.setColor(0x66FFFFFF);
            c.drawLine(x - 2.6f * s, y - 18 * s, x + 1.6f * s, y - 27 * s, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStyle(Paint.Style.FILL);
        if (superman) {
            drawHead(c, x + 3 * s, y - 15 * s, 4.5f * s);
        } else {
            drawHead(c, x + 5.5f * s, y - 32 * s, 4.5f * s);
        }
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
