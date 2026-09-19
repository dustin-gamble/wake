package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Rocket Launch: sustained power against gravity.
 *
 * <p>Your watts are thrust. Hold above the hover threshold and you climb; drop below and gravity
 * takes it back. Each stage you reach sheds mass, so the threshold falls and the rocket that was
 * barely flying starts to leap - the reward for a long hard effort is that it gets easier.
 *
 * <p>3.20: a five-mission campaign. Every mission carries a fuel budget counted in strokes, so a
 * stronger, longer stroke at a lower rate gets further on the same tank - efficiency scores, and a
 * mission's record is the fuel left in it. After the first stage separates the booster flies home,
 * and the rower lands it by easing off into a band of power (land it and it refunds fuel). Wind
 * shear layers along the climb shake the rocket apart unless the stroke rhythm holds steady. The
 * satellite missions end with an orbit insertion - arrive at the target altitude and ease off until
 * the climb stops - and the campaign ends with a powered descent onto the moon.
 *
 * <p>The chip in the header still switches to the 60-second power test, which is unchanged.
 */
final class RocketLaunchGame extends GameView {

    private static final float KARMAN = 100_000f;      // metres
    private static final float[] STAGE_ALT = {0f, 12_000f, 35_000f, 70_000f};
    // Was {130, 105, 80, 58} W, then {95, 82, 68, 54} W tuned to one rower's 129 W median. Now a
    // share of each rower's own typical power (74% is that 95 W for the original rower), so lift-off
    // wants a firm pull from anyone, and the ladder still eases as it climbs.
    private static final float[] STAGE_HOVER_SHARE = {0.74f, 0.64f, 0.53f, 0.42f};
    /** The power test: how high can you get in one minute. */
    private static final float TEST_SECONDS = 60f;
    private static final String[] LAYER = {"TROPOSPHERE", "STRATOSPHERE", "MESOSPHERE", "THERMOSPHERE"};

    /* ---------- the campaign ---------- */

    private static final int MISSIONS = 5;
    private static final int MOON = 4;
    private static final String[] MISSION_NAME = {"KARMAN HOP", "WEATHER SAT", "COMMS SAT", "STATION RUN", "MOON LANDING"};
    /** Best fuel left per mission, as a percentage. Built once, not per frame. */
    private static final String[] MISSION_KEY = {"rocket.m1", "rocket.m2", "rocket.m3", "rocket.m4", "rocket.m5"};
    private static final String[] MISSION_BRIEF = {
            "reach space at 100 km",
            "put a weather satellite in a 150 km orbit",
            "put a comms satellite in a 250 km orbit",
            "resupply the station at 400 km",
            "set the lander down on the moon"};
    /** Target altitude, metres. For the moon this is unused. */
    private static final float[] MISSION_TARGET = {KARMAN, 150_000f, 250_000f, 400_000f, 0f};
    /** Half-width of the orbit window; 0 means "just get there". */
    private static final float[] MISSION_TOL = {0f, 9_000f, 11_000f, 12_000f, 0f};
    /**
     * How long the climb should take at the rower's typical power. The physics are simulated at
     * that power when the mission starts and the altitude rate is scaled to fit, so a mission takes
     * about this long whoever is rowing - and longer if you row below your usual.
     */
    private static final float[] MISSION_SECONDS = {240f, 270f, 300f, 330f, 75f};
    /** Where the wind shear layers sit, as fractions of the nominal climb time. */
    private static final float[][] SHEAR_AT = {{0.60f}, {0.10f, 0.55f}, {0.40f, 0.70f}, {0.30f, 0.55f, 0.80f}, {}};
    /** Each shear layer lasts about this long at typical power. */
    private static final float SHEAR_SECONDS = 18f;
    /** Stroke-interval error allowed inside a shear layer (13% is about 3 spm at 25). */
    private static final float SHEAR_TOL = 0.13f;
    /** Fuel is this much more than a typical-power, typical-rate flight needs. */
    private static final float FUEL_MARGIN = 1.3f;
    /** A landed booster refunds this share of the tank. */
    private static final float BOOSTER_REFUND = 0.15f;
    /** Ease band for the booster landing, as shares of typical watts. */
    private static final float EASE_LO = 0.35f;
    private static final float EASE_HI = 0.70f;
    private static final float BOOSTER_START_M = 450f;
    private static final float BOOSTER_SAFE_MPS = 17f;
    /** Orbit insertion: smoothed climb (internal units) must be below this, for this long. */
    private static final float V_INSERT = 2.5f;
    private static final float INSERT_SECONDS = 3f;
    /** Moon: the lander hovers at this share of typical power, and must touch down slower than this. */
    private static final float MOON_HOVER_SHARE = 0.9f;
    private static final float MOON_START_M = 900f;
    private static final float MOON_SAFE_MPS = 5.5f;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    // 3.19.5: smoke trail puffs, and the spent booster tumbling away after a stage.
    private final float[] smokeX = new float[40];
    private final float[] smokeY = new float[40];
    private final float[] smokeLife = new float[40];
    private int smokeNext;
    private double smokeClock;
    private float boosterY;
    private float boosterSpin;
    private double boosterUntil;
    private final float[] starX = new float[70];
    private final float[] starY = new float[70];
    private final float[] starR = new float[70];

    private boolean started;
    private boolean over;
    private boolean orbit;            // mission accomplished (orbit, Karman, or touchdown)
    private float altitude;
    private float velocity;           // internal climb units; displayed as velocity * 26 * pace m/s
    private int stage;
    private double stageFlash;
    private float plume;
    private double maxAltitude;
    private boolean testMode;
    private boolean testDone;
    private double testSeconds;
    private double testWattSeconds;

    // Sky gradient, rebuilt only when the colour step or height changes (was allocated every frame).
    private LinearGradient skyShader;
    private int skyKey = -1;

    // Power, smoothed: thrust follows within a stroke, the booster and lander over a few strokes.
    private float thrustW;
    private float smoothW;

    // Campaign state.
    private int mission;
    private int cleared;              // missions ever completed, from rocket.campaign
    private float pace = 1f;
    private final float[] simAlt = new float[4001];
    private int simSeconds;
    private final float[] shearLo = new float[3];
    private final float[] shearHi = new float[3];
    private int shearCount;
    private float fuel;               // strokes left in the tank
    private float fuelBudget;
    private boolean fuelOut;
    private float fuelOutTimer;
    private double fuelFlash;
    private float insertion;          // seconds held in the orbit window
    private float vSmooth;
    private String failReason = "CRASHED";
    private float fallDrop;
    private double wonAt;
    private int stars;
    private float fuelLeftPct;
    private boolean newBest;
    private int flightStrokes;        // strokes used this flight, for metres per stroke
    private float climbedAtStart;

    // Stroke rhythm, from the catches the pulse meter sees (not the ~1 s polled counter).
    private float prevPhase;
    private boolean phaseRising;
    private double lastCatchAt;
    private float lastInterval;
    private float rhythmRef;
    private boolean inShear;
    private float shearTarget;
    private float stress;
    private float shearVis;
    private double steadyFlash;
    private double gustFlash;
    private int shearsCleared;
    private double shearClearedFlash;

    // Booster return: 0 none, 1 descending, 2 landed, 3 lost.
    private int boosterState;
    private float bAlt;
    private float bVel;
    private float bFuel;
    private double boosterResultUntil;
    private boolean boosterBurst;

    // Moon lander.
    private float landerAlt;
    private float landerV;

    // Mission cards on the pre-launch screen, hit-tested in onTouchEvent.
    private final float[] cardL = new float[MISSIONS];
    private float cardT;
    private float cardB;
    private float cardW;

    RocketLaunchGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        java.util.Random r = new java.util.Random(9);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = r.nextFloat();
            starY[i] = r.nextFloat();
            starR[i] = 0.6f + r.nextFloat() * 1.6f;
        }
        cleared = Math.max(0, Math.min(MISSIONS, Math.round(bests.get("rocket.campaign", 0f))));
        mission = Math.min(cleared, MISSIONS - 1);
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        orbit = false;
        altitude = 0f;
        velocity = 0f;
        stage = 0;
        maxAltitude = 0;
        testDone = false;
        testSeconds = 0;
        testWattSeconds = 0;
        thrustW = 0f;
        smoothW = 0f;
        fuelOut = false;
        fuelOutTimer = 0f;
        insertion = 0f;
        vSmooth = 0f;
        failReason = "CRASHED";
        fallDrop = 0f;
        stars = 0;
        newBest = false;
        flightStrokes = 0;
        climbedAtStart = 0f;
        lastCatchAt = 0;
        lastInterval = 0f;
        rhythmRef = 60f / (float) Math.max(16.0, profile.typicalRate());
        inShear = false;
        stress = 0f;
        shearVis = 0f;
        shearsCleared = 0;
        boosterState = 0;
        boosterUntil = 0;
        landerAlt = MOON_START_M;
        landerV = -15f;
        planFlight();
    }

    /** A 60-second power test instead of the campaign. Restarts the launch. */
    void setTestMode(boolean test) {
        testMode = test;
        start();
    }

    boolean testMode() {
        return testMode;
    }

    private boolean campaign() {
        return !testMode;
    }

    private float typW() {
        return (float) Math.max(40.0, profile.typicalWatts());
    }

    /**
     * Simulates the climb at the rower's typical power to size this mission: the altitude pace, the
     * fuel budget and where the shear layers sit. Runs once per launch, not per frame.
     */
    private void planFlight() {
        pace = 1f;
        shearCount = 0;
        float rate = (float) Math.max(16.0, profile.typicalRate());
        if (!campaign()) {
            fuelBudget = 0f;
            fuel = 0f;
            return;
        }
        fuelBudget = Math.round(MISSION_SECONDS[mission] * rate / 60f * FUEL_MARGIN);
        fuel = fuelBudget;
        if (mission == MOON) {
            return;
        }
        float target = MISSION_TARGET[mission] + MISSION_TOL[mission] * 0.5f;
        float watts = typW();
        float alt = 0f;
        float v = 0f;
        float step = 1f / 30f;
        float damp = (float) Math.pow(0.985, step * 60f);
        int sec = 0;
        simAlt[0] = 0f;
        while (alt < target && sec < simAlt.length - 1) {
            for (int k = 0; k < 30; k++) {
                int st = stageAt(alt);
                float thin = 1f + Math.min(1.2f, alt / 60_000f);
                float accel = (watts - watts * STAGE_HOVER_SHARE[st]) * 0.055f * thin;
                v += accel * step;
                v *= damp;
                alt = Math.max(0f, alt + v * step * 26f);
            }
            sec++;
            simAlt[sec] = alt;
        }
        simSeconds = Math.max(1, sec);
        pace = Math.max(1f, Math.min(12f, simSeconds / MISSION_SECONDS[mission]));
        float[] at = SHEAR_AT[mission];
        for (int k = 0; k < at.length && k < shearLo.length; k++) {
            int a = Math.min(simSeconds, (int) (at[k] * simSeconds));
            int b = Math.min(simSeconds, (int) (at[k] * simSeconds + SHEAR_SECONDS * pace));
            if (simAlt[b] > simAlt[a]) {
                shearLo[shearCount] = simAlt[a];
                shearHi[shearCount] = simAlt[b];
                shearCount++;
            }
        }
    }

    private static int stageAt(float alt) {
        for (int i = STAGE_ALT.length - 1; i >= 0; i--) {
            if (alt >= STAGE_ALT[i]) {
                return i;
            }
        }
        return 0;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && s.watts > 0) {
            started = true;
        }
    }

    /** One stroke burns one stroke of fuel. Counting only - magnitudes never come from here. */
    @Override
    protected void onStroke(int watts) {
        if (!campaign() || !started || over || orbit) {
            return;
        }
        flightStrokes++;
        if (fuel > 0f) {
            fuel = Math.max(0f, fuel - 1f);
            fuelFlash = 0.35;
            if (fuel <= 0f) {
                fuelOut = true;
                fuelOutTimer = 0f;
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(e);
        }
        if (over || orbit || testDone) {
            if (orbit && campaign() && mission < MISSIONS - 1 && mission + 1 <= cleared) {
                mission++;
            }
            start();
            return true;
        }
        if (!started && campaign() && cardW > 0) {
            float x = e.getX();
            float y = e.getY();
            if (y >= cardT && y <= cardB) {
                for (int i = 0; i < MISSIONS; i++) {
                    if (x >= cardL[i] && x <= cardL[i] + cardW && i <= cleared) {
                        mission = i;
                        start();
                        return true;
                    }
                }
            }
        }
        return super.onTouchEvent(e);
    }

    private float hoverWatts() {
        // typW(), not the raw profile figure, so the hover matches the flight planFlight() simulated.
        return typW() * STAGE_HOVER_SHARE[Math.min(stage, STAGE_HOVER_SHARE.length - 1)];
    }

    /* ---------- the frame ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        thrustW += (watts - thrustW) * Math.min(1f, dt / 0.8f);
        smoothW += (watts - smoothW) * Math.min(1f, dt / 2.5f);
        trackCatches();
        fuelFlash = Math.max(0, fuelFlash - dt);
        steadyFlash = Math.max(0, steadyFlash - dt);
        gustFlash = Math.max(0, gustFlash - dt);
        shearClearedFlash = Math.max(0, shearClearedFlash - dt);
        shake.step(dt);
        fx.step(dt, dp(160f));

        if (campaign() && mission == MOON) {
            renderMoon(c, w, h, dt);
            if (!started) {
                drawMissionCards(c, w, h);
            }
            return;
        }

        if (started && !over && !orbit && testMode) {
            testSeconds += dt;
            testWattSeconds += watts * dt;
            if (testSeconds >= TEST_SECONDS) {
                over = true;
                testDone = true;
                bests.recordHighest("rocket.test60", (float) maxAltitude);
            }
        }
        if (started && !over && !orbit) {
            stepAscent(w, h, dt);
        }
        if (over && "OUT OF FUEL".equals(failReason)) {
            fallDrop += dp(140f) * dt;
        }
        stageFlash = Math.max(0, stageFlash - dt);
        float throttle = fuelOut ? 0f : Math.max(0f, Math.min(1.4f, thrustW / Math.max(1f, hoverWatts())));
        plume += ((throttle) - plume) * Math.min(1f, 8f * dt);
        shearVis += ((inShear ? 1f : 0f) - shearVis) * Math.min(1f, 2f * dt);

        drawAscentWorld(c, w, h, dt);
        if (stageFlash > 0) {
            Fx.vignette(c, w, h, (float) stageFlash * 0.5f, 0x1A4A8A);
        }
        if (stress > 0.35f && !over) {
            Fx.vignette(c, w, h, (stress - 0.35f) * 1.2f, 0xD83A2A);
        }
        drawLadder(c, w, h);
        drawAscentHud(c, w, h, watts);
        if (campaign() && boosterState != 0 && (boosterState == 1 || sessionSeconds < boosterResultUntil)) {
            drawBoosterPanel(c, w, h);
        }
        drawThrustBar(c, w, h, watts, hoverWatts(), "HOVER");
        if (!started && campaign()) {
            drawMissionCards(c, w, h);
        }
    }

    /** Physics for the climb: thrust, shear, staging, booster, fuel and the objective. */
    private void stepAscent(float w, float h, float dt) {
        float engine = fuelOut ? 0f : thrustW;
        if (campaign()) {
            updateShear(dt);
            engine *= 1f - 0.4f * stress;
        }
        // Net thrust in altitude m/s^2. Thin air above 40 km helps, so the top is a payoff.
        float thin = 1f + Math.min(1.2f, altitude / 60_000f);
        float accel = (engine - hoverWatts()) * 0.055f * thin;
        velocity += accel * dt;
        velocity *= (float) Math.pow(0.985, dt * 60f);   // drag, stops runaway (0.985 a frame at 60 fps)
        altitude = Math.max(0f, altitude + velocity * dt * 26f * pace);
        vSmooth += (velocity - vSmooth) * Math.min(1f, dt / 2.5f);
        maxAltitude = Math.max(maxAltitude, altitude);
        if (over) {
            return;   // updateShear broke it up
        }
        // Fell back after a real launch. (The old -6 threshold sat below the fall speed the drag allows,
        // so a rocket that fell back just sat on the pad.)
        if (altitude <= 0f && velocity < -3f && maxAltitude > 500) {
            fail("CRASHED", w, h);
            return;
        }
        int newStage = stageAt(altitude);
        if (newStage > stage) {
            stage = newStage;
            stageFlash = 1.4;
            shake.kick(dp(10f));
            fx.burst(w * 0.5f, h * 0.62f, 34, dp(200f), 0.9f, dp(4f), 0xFFBFE3FF, false);
            if (campaign() && stage == 1 && boosterState == 0) {
                // The first stage flies home instead of tumbling away.
                boosterState = 1;
                bAlt = BOOSTER_START_M;
                bVel = 45f;
                bFuel = 1f;
            } else {
                boosterY = h * 0.58f + dp(44f);
                boosterSpin = 0f;
                boosterUntil = sessionSeconds + 3.0;
            }
        }
        if (!campaign()) {
            if (altitude >= KARMAN) {
                orbit = true;
                wonAt = sessionSeconds;
                bests.recordHighest("rocket.altitude", KARMAN);
            }
            return;
        }
        updateBooster(dt);
        float target = MISSION_TARGET[mission];
        float tol = MISSION_TOL[mission];
        if (tol <= 0f) {
            if (altitude >= target) {
                succeed(w, h);
                return;
            }
        } else {
            boolean inWindow = Math.abs(altitude - target) < tol && Math.abs(vSmooth) < V_INSERT;
            insertion = inWindow ? insertion + dt : Math.max(0f, insertion - dt * 0.5f);
            if (insertion >= INSERT_SECONDS) {
                succeed(w, h);
                return;
            }
        }
        if (fuelOut) {
            fuelOutTimer += dt;
            if (fuelOutTimer > 3f) {
                fail("OUT OF FUEL", w, h);
            }
        }
    }

    private void succeed(float w, float h) {
        orbit = true;
        inShear = false;
        wonAt = sessionSeconds;
        boosterState = boosterState == 1 ? 0 : boosterState;
        fuelLeftPct = fuelBudget > 0 ? 100f * fuel / fuelBudget : 0f;
        stars = fuelLeftPct >= 35f ? 3 : fuelLeftPct >= 20f ? 2 : 1;
        newBest = bests.recordHighest(MISSION_KEY[mission], fuelLeftPct);
        bests.recordHighest("rocket.campaign", mission + 1);
        cleared = Math.max(cleared, mission + 1);
        if (mission != MOON) {
            bests.recordHighest("rocket.altitude", (float) maxAltitude);
        }
        shake.kick(dp(8f));
        fx.burst(w * 0.5f, h * 0.45f, 50, dp(240f), 1.4f, dp(4f), 0xFF35D0BA, true);
        fx.burst(w * 0.5f, h * 0.45f, 40, dp(200f), 1.4f, dp(3.5f), 0xFFF5C518, true);
    }

    private void fail(String reason, float w, float h) {
        over = true;
        inShear = false;   // stop the wind streaks once the flight has ended
        failReason = reason;
        boosterState = boosterState == 1 ? 0 : boosterState;
        if (mission != MOON || !campaign()) {
            bests.recordHighest("rocket.altitude", (float) maxAltitude);
        }
        if (!"OUT OF FUEL".equals(reason)) {
            shake.kick(dp(20f));
            float y = campaign() && mission == MOON ? h * 0.42f : h * 0.58f;
            fx.burst(w * 0.5f, y, 60, dp(260f), 1.2f, dp(4.5f), 0xFFFF7A3D, true);
            fx.burst(w * 0.5f, y, 30, dp(160f), 1.0f, dp(6f), 0xFF5A5A62, true);
        }
    }

    /* ---------- stroke rhythm and wind shear ---------- */

    /**
     * A catch is where the stroke phase starts rising from low - taken from the pulse stream, so it
     * lands at the real catch, where the stroke counter would be up to a second late.
     */
    private void trackCatches() {
        float phase = strokePhase();
        boolean rising = phase > prevPhase + 0.001f;
        if (rising && !phaseRising && prevPhase < 0.5f) {
            double t = sessionSeconds;
            double interval = t - lastCatchAt;
            if (interval >= 1.1) {
                lastCatchAt = t;
                if (interval <= 6.0) {
                    lastInterval = (float) interval;
                    onCatch((float) interval);
                }
            }
        }
        phaseRising = rising;
        prevPhase = phase;
    }

    private void onCatch(float interval) {
        if (inShear && started && !over && !orbit) {
            float dev = Math.abs(interval - shearTarget) / shearTarget;
            // An interval of about two strokes is one catch the pulse detector missed, not an
            // off-rhythm stroke: neither reward nor punish it.
            float missed = Math.abs(interval - 2f * shearTarget) / (2f * shearTarget);
            if (dev > SHEAR_TOL && missed <= SHEAR_TOL) {
                return;
            }
            if (dev <= SHEAR_TOL) {
                stress = Math.max(0f, stress - 0.12f);
                steadyFlash = 0.7;
            } else {
                stress += Math.min(0.35f, 0.1f + (dev - SHEAR_TOL) * 2.5f);
                gustFlash = 0.8;
                shake.kick(dp(7f));
            }
        } else if (!inShear) {
            rhythmRef += (interval - rhythmRef) * 0.3f;
        }
    }

    private void updateShear(float dt) {
        boolean in = false;
        for (int k = 0; k < shearCount; k++) {
            if (altitude >= shearLo[k] && altitude <= shearHi[k]) {
                in = true;
                break;
            }
        }
        if (in && !inShear) {
            inShear = true;
            shearTarget = Math.max(1.4f, Math.min(4.5f, rhythmRef));
        } else if (!in && inShear) {
            inShear = false;
            shearsCleared++;
            shearClearedFlash = 2.0;
        }
        if (in) {
            stress += dt * 0.015f;   // the gusts never stop pushing
            // 2.3 intervals, so a single missed catch detection is not read as stopping.
            if (sessionSeconds - lastCatchAt > shearTarget * 2.3f) {
                stress += dt * 0.25f;   // stopping in the shear loses control
            }
            if (stress >= 1f) {
                stress = 1f;
                fail("BROKE UP IN THE SHEAR", getWidth(), getHeight());
            }
        } else {
            stress = Math.max(0f, stress - dt * 0.3f);
        }
    }

    /** The layer we are in or climbing towards within ~10 s, or -1. */
    private int shearAhead() {
        float climb = velocity * 26f * pace;
        for (int k = 0; k < shearCount; k++) {
            if (altitude < shearLo[k] && climb > 0 && shearLo[k] - altitude < climb * 10f) {
                return k;
            }
        }
        return -1;
    }

    /* ---------- the booster ---------- */

    /** Descent speed the booster settles to for a given ease ratio (share of typical watts). */
    private static float boosterSink(float e) {
        if (e <= EASE_LO) {
            return 70f - (70f - 16f) * (e / EASE_LO);
        }
        if (e <= EASE_HI) {
            return 16f - 8f * (e - EASE_LO) / (EASE_HI - EASE_LO);
        }
        return Math.max(-14f, 8f - 60f * (e - EASE_HI));
    }

    private void updateBooster(float dt) {
        if (boosterState != 1) {
            return;
        }
        float e = smoothW / typW();
        boolean lit = bFuel > 0f && e > 0.12f;
        float vt = lit ? boosterSink(e) : 70f;
        bVel += (vt - bVel) * Math.min(1f, dt / 1.5f);
        if (lit) {
            bFuel -= e * 0.022f * dt;
        }
        bAlt = Math.min(650f, bAlt - bVel * dt);
        if (bAlt <= 0f) {
            bAlt = 0f;
            boosterResultUntil = sessionSeconds + 4.0;
            boosterBurst = true;
            if (bVel <= BOOSTER_SAFE_MPS) {
                boosterState = 2;
                fuel = Math.min(fuelBudget, fuel + fuelBudget * BOOSTER_REFUND);
                if (fuel > 0f) {
                    fuelOut = false;
                }
                fuelFlash = 1.0;
                bests.putFloat("rocket.landings", bests.get("rocket.landings", 0f) + 1f);
            } else {
                boosterState = 3;
            }
        }
    }

    /* ---------- drawing the climb ---------- */

    private void drawAscentWorld(Canvas c, float w, float h, float dt) {
        // Sky: blue at sea level through to black at the Karman line.
        float space = Math.min(1f, altitude / 80_000f);
        c.save();
        c.translate(shake.dx, shake.dy);
        int key = (int) (space * 48) * 10_000 + (int) h;
        if (key != skyKey) {
            skyKey = key;
            float s = (int) (space * 48) / 48f;
            skyShader = new LinearGradient(0, 0, 0, h,
                    blend(0xFF2F7FD0, 0xFF000308, s), blend(0xFF9ED2F5, 0xFF040A14, s),
                    Shader.TileMode.CLAMP);
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // Stars fade in with altitude.
        if (space > 0.05f) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (space * 230));
            for (int i = 0; i < starX.length; i++) {
                float sy = (starY[i] * h + altitude * 0.0016f) % h;
                c.drawCircle(starX[i] * w, sy, dp(starR[i]), paint);
            }
            paint.setAlpha(255);
        }

        float rocketY = h * 0.58f;
        // Ground and the curve of the earth receding.
        // 3.19.5: the ground drops away within the first few km. At 0.012 px/m the rocket still sat
        // beside its launch tower at 2.4 km on the emulator.
        float groundY = rocketY + dp(60f) + Math.min(h, altitude * 0.25f);
        if (groundY < h + dp(400f)) {
            paint.setColor(0xFF2E6B35);
            if (altitude < 20_000f) {
                c.drawRect(0, groundY, w, h + dp(400f), paint);
                paint.setColor(0xFF20532A);
                float scroll = (altitude * 0.6f) % dp(60f);
                for (float gx = -scroll; gx < w; gx += dp(60f)) {
                    c.drawRect(gx, groundY, gx + dp(3f), h, paint);
                }
                // Launch pad and its tower, which the rocket leaves behind.
                paint.setColor(0xFF4A4A55);
                c.drawRect(w * 0.5f - dp(40f), groundY - dp(6f), w * 0.5f + dp(40f), groundY, paint);
                float towerX = w * 0.5f - dp(48f);
                paint.setColor(0xFFB8452F);
                c.drawRect(towerX - dp(6f), groundY - dp(120f), towerX, groundY - dp(6f), paint);
                c.drawRect(towerX - dp(14f), groundY - dp(120f), towerX - dp(10f), groundY - dp(6f), paint);
                paint.setStrokeWidth(dp(1.5f));
                for (float ty = groundY - dp(114f); ty < groundY - dp(8f); ty += dp(14f)) {
                    c.drawLine(towerX - dp(14f), ty, towerX, ty + dp(14f), paint);
                }
                c.drawRect(towerX - dp(14f), groundY - dp(80f), w * 0.5f - dp(16f), groundY - dp(76f), paint);
                if (((int) (sessionSeconds * 2)) % 2 == 0) {
                    paint.setColor(0xFFFF3B3B);
                    c.drawCircle(towerX - dp(7f), groundY - dp(126f), dp(3f), paint);
                }
            } else {
                // High enough for the horizon to curve.
                paint.setColor(0xFF2E6B35);
                c.drawOval(-w * 1.2f, groundY, w * 2.2f, groundY + h * 2.4f, paint);
                paint.setColor(0x5599D8FF);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(6f));
                c.drawOval(-w * 1.2f, groundY, w * 2.2f, groundY + h * 2.4f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        // Clouds drifting down past you while in the troposphere: puffy, several sizes.
        if (altitude < 16_000f) {
            int cloudA = (int) (190 * (1f - altitude / 16_000f));
            for (int i = 0; i < 7; i++) {
                float cy = ((i * 151f * dp(1f)) + altitude * 0.05f) % (h + dp(160f)) - dp(80f);
                if (cy > groundY - dp(30f)) {
                    continue;   // clouds belong in the sky, not drifting across the field
                }
                float cx = ((i * 0.29f + 0.1f) % 1f) * w + (float) Math.sin(i + sessionSeconds * 0.2) * dp(20f)
                        + shearVis * (float) ((sessionSeconds * dp(260f) + i * dp(200f)) % w) * 0.4f;
                float sc = 0.7f + (i % 3) * 0.35f;
                paint.setColor((cloudA << 24) | 0xFFFFFF);
                c.drawOval(cx - dp(70f) * sc, cy - dp(12f) * sc, cx + dp(70f) * sc, cy + dp(14f) * sc, paint);
                c.drawOval(cx - dp(40f) * sc, cy - dp(30f) * sc, cx + dp(20f) * sc, cy + dp(6f) * sc, paint);
                c.drawOval(cx - dp(4f) * sc, cy - dp(22f) * sc, cx + dp(46f) * sc, cy + dp(8f) * sc, paint);
            }
        }
        drawAltitudeLife(c, w, h);
        if (campaign()) {
            drawOrbitWindow(c, w, h, rocketY);
        }
        drawSmoke(c, w, h, dt);
        if (shearVis > 0.02f) {
            drawShearWind(c, w, h);
        }
        if (sessionSeconds < boosterUntil) {
            // The spent stage falls away, tumbling.
            boosterY += dp(160f) * dt;
            boosterSpin += 220f * dt;
            c.save();
            c.rotate(boosterSpin, w * 0.5f + dp(26f), boosterY);
            paint.setColor(0xFFCFD6DE);
            c.drawRoundRect(w * 0.5f + dp(18f), boosterY - dp(22f), w * 0.5f + dp(34f), boosterY + dp(22f), dp(4f), dp(4f), paint);
            paint.setColor(0xFFD8453C);
            c.drawRect(w * 0.5f + dp(18f), boosterY + dp(12f), w * 0.5f + dp(34f), boosterY + dp(22f), paint);
            c.restore();
        }

        // Exhaust plume, then the rocket - tilted and swaying when the shear gets hold of it.
        float rx = w * 0.5f + stress * (float) Math.sin(sessionSeconds * 1.3) * dp(34f);
        float ry = rocketY + fallDrop;
        float tilt = stress * 24f * (float) Math.sin(sessionSeconds * 2.3)
                + shearVis * 3f * (float) Math.sin(sessionSeconds * 7.1)
                + (float) gustFlash * 10f * (float) Math.sin(sessionSeconds * 13.0);
        c.save();
        c.rotate(tilt, rx, ry);
        if (plume > 0.05f && !over && !testDone) {
            float len = dp(20f) + plume * dp(90f);
            paint.setColor(0xFFFFD36A);
            path.reset();
            path.moveTo(rx - dp(11f), ry + dp(30f));
            path.lineTo(rx + dp(11f), ry + dp(30f));
            path.lineTo(rx + dp(4f), ry + dp(30f) + len);
            path.lineTo(rx - dp(4f), ry + dp(30f) + len);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFFFF7A3D);
            path.reset();
            path.moveTo(rx - dp(6f), ry + dp(30f));
            path.lineTo(rx + dp(6f), ry + dp(30f));
            path.lineTo(rx, ry + dp(30f) + len * 0.62f);
            path.close();
            c.drawPath(path, paint);
            Fx.glow(c, rx, ry + dp(40f), len * 0.8f, 0x55FF9A4D);
        }
        boolean fuelFail = over && "OUT OF FUEL".equals(failReason);
        boolean deployed = orbit && campaign() && MISSION_TOL[mission] > 0f;
        if (!over || testDone || fuelFail) {
            drawRocket(c, rx, ry, !deployed);
        }
        c.restore();
        if (deployed) {
            drawSatellite(c, w, rx, ry);
        }
        fx.draw(c);
        c.restore();
    }

    private void drawRocket(Canvas c, float rx, float rocketY, boolean withNose) {
        paint.setColor(0xFFE6EDF7);
        path.reset();
        if (withNose) {
            path.moveTo(rx, rocketY - dp(40f));
            path.lineTo(rx + dp(13f), rocketY - dp(4f));
        } else {
            // The fairing has opened and the satellite has gone.
            path.moveTo(rx - dp(13f), rocketY - dp(10f));
            path.lineTo(rx + dp(13f), rocketY - dp(10f));
        }
        path.lineTo(rx + dp(13f), rocketY + dp(30f));
        path.lineTo(rx - dp(13f), rocketY + dp(30f));
        path.lineTo(rx - dp(13f), rocketY - dp(4f));
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFFD8453C);
        path.reset();
        path.moveTo(rx - dp(13f), rocketY + dp(14f));
        path.lineTo(rx - dp(26f), rocketY + dp(34f));
        path.lineTo(rx - dp(13f), rocketY + dp(30f));
        path.close();
        c.drawPath(path, paint);
        path.reset();
        path.moveTo(rx + dp(13f), rocketY + dp(14f));
        path.lineTo(rx + dp(26f), rocketY + dp(34f));
        path.lineTo(rx + dp(13f), rocketY + dp(30f));
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFF6FA6D6);
        c.drawCircle(rx, rocketY - dp(8f), dp(6f), paint);
    }

    /** After insertion the satellite leaves the fairing and unfolds its panels along the orbit. */
    private void drawSatellite(Canvas c, float w, float rx, float ry) {
        float t = (float) (sessionSeconds - wonAt);
        float sx = rx + dp(10f) + t * dp(55f);
        float sy = ry - dp(40f) - t * dp(6f) + (float) Math.sin(t * 1.5f) * dp(3f);
        if (sx > w + dp(60f)) {
            return;
        }
        float open = Math.min(1f, t / 1.6f);
        paint.setColor(0xFFC8D2DC);
        c.drawRect(sx - dp(8f), sy - dp(8f), sx + dp(8f), sy + dp(8f), paint);
        paint.setColor(0xFFF5C518);
        c.drawRect(sx - dp(8f), sy - dp(2f), sx + dp(8f), sy + dp(2f), paint);
        paint.setColor(0xFF3A6EA5);
        float pw = dp(4f) + open * dp(34f);
        c.drawRect(sx - dp(10f) - pw, sy - dp(5f), sx - dp(10f), sy + dp(5f), paint);
        c.drawRect(sx + dp(10f), sy - dp(5f), sx + dp(10f) + pw, sy + dp(5f), paint);
        paint.setColor(0xFFFFFFFF);
        paint.setStrokeWidth(dp(1.5f));
        c.drawLine(sx, sy - dp(8f), sx, sy - dp(18f), paint);
        if (((int) (t * 3)) % 2 == 0) {
            paint.setColor(0xFF35D0BA);
            c.drawCircle(sx, sy - dp(20f), dp(3f), paint);
        }
    }

    /** The target orbit, drawn as a band across the sky once it is close enough to see. */
    private void drawOrbitWindow(Canvas c, float w, float h, float rocketY) {
        float tol = MISSION_TOL[mission];
        if (tol <= 0f) {
            return;
        }
        float target = MISSION_TARGET[mission];
        float gap = target - altitude;
        if (Math.abs(gap) > tol * 4f && !orbit) {
            return;
        }
        float px = h * 0.12f / tol;
        float y = rocketY - gap * px;
        float half = tol * px;
        boolean inside = Math.abs(gap) < tol;
        paint.setColor(inside ? 0x3335D0BA : 0x2235D0BA);
        c.drawRect(0, y - half, w, y + half, paint);
        paint.setColor(0xAA35D0BA);
        paint.setStrokeWidth(dp(2f));
        float off = (float) ((sessionSeconds * dp(40f)) % dp(24f));
        for (float x = -off; x < w; x += dp(24f)) {
            c.drawLine(x, y, x + dp(12f), y, paint);
        }
        label(c, "TARGET ORBIT " + Math.round(target / 1000f) + " km", dp(20f), y - dp(6f), 9f,
                0xFF35D0BA, Paint.Align.LEFT);
    }

    /** Horizontal streaks of wind racing across the screen inside a shear layer. */
    private void drawShearWind(Canvas c, float w, float h) {
        paint.setStrokeWidth(dp(2f));
        int a = (int) (shearVis * (90 + 100 * stress));
        paint.setColor((Math.min(255, a) << 24) | 0xFFFFFF);
        for (int i = 0; i < 18; i++) {
            float y = h * ((i * 0.057f + 0.04f) % 1f) + (float) Math.sin(sessionSeconds * 3 + i) * dp(6f);
            float len = dp(60f) + (i % 4) * dp(40f);
            float speed = dp(900f) + i * dp(60f);
            float x = (float) ((sessionSeconds * speed + i * dp(170f)) % (w + len)) - len;
            c.drawLine(x, y, x + len, y + dp(4f) * (float) Math.sin(i), paint);
        }
    }

    /** Altitude ladder on the right with the named layers, shear bands and the target. */
    private void drawLadder(Canvas c, float w, float h) {
        float ladderTop = dp(70f);
        float ladderBottom = h - dp(60f);
        float lx = w - dp(26f);
        float top = KARMAN;
        if (campaign() && MISSION_TARGET[mission] > KARMAN) {
            top = (MISSION_TARGET[mission] + MISSION_TOL[mission] * 2f) * 1.05f;
        }
        float span = ladderBottom - ladderTop;
        paint.setColor(0x33FFFFFF);
        c.drawRect(lx - dp(2f), ladderTop, lx + dp(2f), ladderBottom, paint);
        if (campaign()) {
            for (int k = 0; k < shearCount; k++) {
                float y0 = ladderBottom - span * Math.min(1f, shearLo[k] / top);
                float y1 = ladderBottom - span * Math.min(1f, shearHi[k] / top);
                paint.setColor(0xAAF0B132);
                c.drawRect(lx - dp(6f), y1 - dp(1f), lx + dp(6f), y0 + dp(1f), paint);
                label(c, "SHEAR", lx + dp(8f) - dp(16f), (y0 + y1) / 2f + dp(3f), 6.5f, WARN, Paint.Align.RIGHT);
            }
            float tol = MISSION_TOL[mission];
            if (tol > 0f) {
                float y0 = ladderBottom - span * ((MISSION_TARGET[mission] - tol) / top);
                float y1 = ladderBottom - span * ((MISSION_TARGET[mission] + tol) / top);
                paint.setColor(0xAA35D0BA);
                c.drawRect(lx - dp(10f), y1, lx + dp(10f), y0, paint);
                label(c, "ORBIT " + Math.round(MISSION_TARGET[mission] / 1000f) + " km", lx - dp(12f),
                        (y0 + y1) / 2f + dp(4f), 7.5f, ACCENT, Paint.Align.RIGHT);
            }
        }
        for (int i = 0; i < STAGE_ALT.length; i++) {
            float f = STAGE_ALT[i] / top;
            float y = ladderBottom - span * f;
            paint.setColor(altitude >= STAGE_ALT[i] ? ACCENT : 0x66FFFFFF);
            c.drawRect(lx - dp(8f), y - dp(1.5f), lx + dp(8f), y + dp(1.5f), paint);
            if (top <= KARMAN * 1.6f || i == 0) {
                label(c, LAYER[i], lx - dp(12f), y + dp(4f), 7.5f,
                        altitude >= STAGE_ALT[i] ? ACCENT : FAINT, Paint.Align.RIGHT);
            }
        }
        float ky = ladderBottom - span * (KARMAN / top);
        paint.setColor(0xFFF5C518);
        c.drawRect(lx - dp(10f), ky - dp(2f), lx + dp(10f), ky + dp(2f), paint);
        label(c, "KARMAN 100 km", lx - dp(12f), ky + dp(4f), 7.5f, 0xFFF5C518, Paint.Align.RIGHT);
        float py = ladderBottom - span * Math.min(1f, altitude / top);
        paint.setColor(ACCENT);
        c.drawCircle(lx, py, dp(6f), paint);
    }

    private void drawAscentHud(Canvas c, float w, float h, int watts) {
        float cx = w * 0.30f;
        float by = h * 0.20f;
        if (campaign()) {
            label(c, "MISSION " + (mission + 1) + " OF " + MISSIONS + "  ·  " + MISSION_NAME[mission],
                    cx, by - dp(50f), 10f, DIM, Paint.Align.CENTER);
        }
        String big;
        int col;
        boolean flying = started && !over && !orbit;
        if (!started) {
            big = testMode ? "60-SECOND POWER TEST" : "HOLD " + Math.round(hoverWatts()) + " W TO LIFT OFF";
            col = DIM;
        } else if (testDone) {
            big = String.format(java.util.Locale.US, "%.1f km", maxAltitude / 1000f);
            col = ACCENT;
        } else if (orbit) {
            big = !campaign() || MISSION_TOL[mission] <= 0f ? "SPACE" : "IN ORBIT";
            col = ACCENT;
        } else if (over) {
            big = failReason;
            col = BAD;
        } else {
            big = altitude < 1000 ? Math.round(altitude) + " m"
                    : String.format(java.util.Locale.US, "%.1f km", altitude / 1000f);
            col = fuelOut ? BAD : thrustW >= hoverWatts() ? ACCENT : BAD;
        }
        bold(c, big, cx, by, flying ? 44f : 24f, col, Paint.Align.CENTER);

        String cap;
        int capCol = FAINT;
        float tol = campaign() ? MISSION_TOL[mission] : 0f;
        float target = campaign() ? MISSION_TARGET[mission] : KARMAN;
        int climb = Math.round(velocity * 26f * pace);
        if (!started) {
            cap = testMode ? "climb as high as you can in one minute - lift-off at " + Math.round(hoverWatts()) + " W"
                    : MISSION_BRIEF[mission] + "  ·  " + Math.round(fuelBudget) + " strokes of fuel  ·  row to launch";
        } else if (testDone) {
            cap = "TEST COMPLETE  ·  average " + Math.round(testWattSeconds / TEST_SECONDS) + " W"
                    + (bests.has("rocket.test60") ? "  ·  best " + String.format(java.util.Locale.US, "%.1f km", bests.get("rocket.test60", 0f) / 1000f) : "")
                    + "  ·  tap to test again";
        } else if (orbit && campaign()) {
            cap = String.format(java.util.Locale.US, "%s  ·  fuel left %.0f%%%s  ·  %s", MISSION_NAME[mission],
                    fuelLeftPct, newBest ? "  ·  NEW BEST" : "",
                    mission + 1 < MISSIONS ? "tap for mission " + (mission + 2) : "tap to fly again");
            capCol = ACCENT;
        } else if (orbit) {
            cap = "you made the Karman line  ·  tap to launch again";
        } else if (over) {
            cap = "peak " + String.format(java.util.Locale.US, "%.1f km", maxAltitude / 1000f)
                    + "  ·  tap to try again";
        } else if (fuelOut) {
            cap = "OUT OF FUEL - ENGINE CUT";
            capCol = BAD;
        } else if (stageFlash > 0) {
            cap = "STAGE " + (stage + 1) + " - MASS SHED, HOVER NOW " + Math.round(hoverWatts()) + " W";
            capCol = BLUE;
        } else if (inShear) {
            int want = Math.round(60f / shearTarget);
            cap = "WIND SHEAR - HOLD " + want + " SPM"
                    + (lastInterval > 0 ? "  ·  last stroke " + Math.round(60f / lastInterval) : "")
                    + (steadyFlash > 0 ? "  ·  STEADY" : gustFlash > 0 ? "  ·  OFF RHYTHM" : "");
            capCol = gustFlash > 0 ? BAD : steadyFlash > 0 ? ACCENT : WARN;
        } else if (boosterState == 1) {
            cap = "EASE OFF TO " + Math.round(typW() * EASE_LO) + "-" + Math.round(typW() * EASE_HI)
                    + " W - LAND THE BOOSTER";
            capCol = WARN;
        } else if (tol > 0f && altitude > target - tol * 4f) {
            if (Math.abs(altitude - target) < tol && Math.abs(vSmooth) < V_INSERT) {
                cap = "CIRCULARISING " + Math.round(100f * insertion / INSERT_SECONDS) + "%  ·  hold it there";
                capCol = ACCENT;
            } else if (altitude > target + tol) {
                cap = "TOO HIGH - EASE OFF AND DROP INTO THE WINDOW";
                capCol = WARN;
            } else if (vSmooth > V_INSERT) {
                cap = "EASE TO ~" + Math.round(hoverWatts()) + " W TO STOP THE CLIMB IN THE WINDOW";
                capCol = WARN;
            } else {
                cap = "CLIMB INTO THE WINDOW  ·  " + climb + " m/s";
            }
        } else if (shearClearedFlash > 0) {
            cap = "SHEAR CLEARED";
            capCol = ACCENT;
        } else if (campaign() && shearAhead() >= 0) {
            cap = "SHEAR AHEAD - SETTLE YOUR RHYTHM AT " + Math.round(60f / Math.max(1.4f, rhythmRef)) + " SPM";
            capCol = WARN;
        } else if (testMode) {
            cap = clock(Math.max(0, TEST_SECONDS - testSeconds)) + " LEFT  ·  " + (thrustW >= hoverWatts() ? "CLIMBING" : "NEED " + Math.round(hoverWatts()) + " W");
        } else if (thrustW >= hoverWatts()) {
            cap = "CLIMBING  ·  " + climb + " m/s";
        } else {
            cap = "FALLING - NEED " + Math.round(hoverWatts()) + " W";
            capCol = BAD;
        }
        bold(c, cap, cx, by + dp(22f), 11f, capCol, Paint.Align.CENTER);

        if (campaign()) {
            drawFuelBar(c, cx, by + dp(40f), altitude / 1000f, "km");
            if ((inShear || stress > 0.02f) && flying) {
                drawStressBar(c, cx, by + dp(84f));
            }
            if (tol > 0f && flying && altitude > target - tol * 4f) {
                drawInsertionBar(c, cx, by + (inShear || stress > 0.02f ? dp(116f) : dp(84f)));
            }
            if (orbit) {
                drawStars(c, cx, by + dp(96f));
            }
        }
    }

    private void drawFuelBar(Canvas c, float cx, float y, float gained, String unit) {
        float bw = dp(360f);
        float l = cx - bw / 2f;
        float f = fuelBudget > 0 ? fuel / fuelBudget : 0f;
        paint.setColor(0x44000000);
        c.drawRoundRect(l - dp(2f), y - dp(2f), l + bw + dp(2f), y + dp(14f), dp(8f), dp(8f), paint);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(l, y, l + bw, y + dp(12f), dp(6f), dp(6f), paint);
        int col = f > 0.35f ? 0xFFF5C518 : f > 0.15f ? WARN : BAD;
        if (fuelFlash > 0) {
            col = blend(col, 0xFFFFFFFF, (float) fuelFlash);
        }
        paint.setColor(col);
        c.drawRoundRect(l, y, l + bw * Math.max(0f, Math.min(1f, f)), y + dp(12f), dp(6f), dp(6f), paint);
        // 20% and 35% marks: the two- and three-star lines.
        paint.setColor(0xAAFFFFFF);
        c.drawRect(l + bw * 0.20f - dp(1f), y - dp(3f), l + bw * 0.20f + dp(1f), y + dp(15f), paint);
        c.drawRect(l + bw * 0.35f - dp(1f), y - dp(3f), l + bw * 0.35f + dp(1f), y + dp(15f), paint);
        String eff = flightStrokes > 0
                ? String.format(java.util.Locale.US, "  ·  %.2f %s per stroke", gained / flightStrokes, unit) : "";
        label(c, "FUEL " + Math.round(fuel) + " / " + Math.round(fuelBudget) + " strokes" + eff
                + "  ·  long strong strokes go further", cx, y + dp(28f), 8.5f, TEXT, Paint.Align.CENTER);
    }

    private void drawStressBar(Canvas c, float cx, float y) {
        float bw = dp(360f);
        float l = cx - bw / 2f;
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(l, y, l + bw, y + dp(8f), dp(4f), dp(4f), paint);
        paint.setColor(stress > 0.6f ? BAD : WARN);
        c.drawRoundRect(l, y, l + bw * Math.min(1f, stress), y + dp(8f), dp(4f), dp(4f), paint);
        label(c, "AIRFRAME STRESS " + Math.round(stress * 100f) + "%", cx, y + dp(22f), 8.5f,
                stress > 0.6f ? BAD : WARN, Paint.Align.CENTER);
    }

    private void drawInsertionBar(Canvas c, float cx, float y) {
        float bw = dp(360f);
        float l = cx - bw / 2f;
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(l, y, l + bw, y + dp(8f), dp(4f), dp(4f), paint);
        paint.setColor(ACCENT);
        c.drawRoundRect(l, y, l + bw * Math.min(1f, insertion / INSERT_SECONDS), y + dp(8f), dp(4f), dp(4f), paint);
        label(c, "ORBIT INSERTION  ·  climb " + Math.round(vSmooth * 26f * pace) + " m/s  ·  needs under "
                + Math.round(V_INSERT * 26f * pace) + " m/s", cx, y + dp(22f), 8.5f, ACCENT, Paint.Align.CENTER);
    }

    private void drawStars(Canvas c, float cx, float y) {
        for (int i = 0; i < 3; i++) {
            float x = cx + (i - 1) * dp(34f);
            boolean lit = i < stars;
            float pop = lit ? Math.min(1f, (float) (sessionSeconds - wonAt) * 2f - i * 0.4f) : 1f;
            if (pop <= 0f) {
                pop = 0f;
            }
            if (lit && pop > 0f) {
                Fx.glow(c, x, y, dp(22f), 0x66F5C518);
            }
            paint.setColor(lit ? 0xFFF5C518 : 0x44FFFFFF);
            drawStar(c, x, y, dp(12f) * (lit ? pop : 1f));
        }
    }

    private void drawStar(Canvas c, float x, float y, float r) {
        if (r <= 0.5f) {
            return;
        }
        path.reset();
        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5;
            float rr = (k % 2 == 0) ? r : r * 0.45f;
            float px = x + (float) Math.cos(a) * rr;
            float py = y + (float) Math.sin(a) * rr;
            if (k == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        c.drawPath(path, paint);
    }

    /** The returning first stage, in a picture-in-picture over the ocean. */
    private void drawBoosterPanel(Canvas c, float w, float h) {
        float l = dp(16f);
        float r = l + dp(300f);
        float b = h - dp(64f);
        float t = b - dp(230f);
        paint.setColor(0xE00B1830);
        c.drawRoundRect(l, t, r, b, dp(10f), dp(10f), paint);
        c.save();
        c.clipRect(l, t, r, b);
        float seaY = b - dp(34f);
        paint.setColor(0xFF12385A);
        c.drawRect(l, seaY, r, b, paint);
        paint.setColor(0x5599D8FF);
        paint.setStrokeWidth(dp(1.5f));
        float wave = (float) ((sessionSeconds * dp(30f)) % dp(26f));
        for (float x = l - wave; x < r; x += dp(26f)) {
            c.drawLine(x, seaY + dp(8f), x + dp(12f), seaY + dp(8f), paint);
            c.drawLine(x + dp(10f), seaY + dp(20f), x + dp(20f), seaY + dp(20f), paint);
        }
        // Drone ship, bobbing.
        float mx = (l + r) / 2f;
        float bob = (float) Math.sin(sessionSeconds * 1.4) * dp(2f);
        float deck = seaY - dp(6f) + bob;
        paint.setColor(0xFF4A4F5A);
        c.drawRect(mx - dp(64f), deck, mx + dp(64f), deck + dp(12f), paint);
        paint.setColor(0xFFF5C518);
        paint.setStrokeWidth(dp(2f));
        c.drawLine(mx - dp(12f), deck + dp(2f), mx + dp(12f), deck + dp(10f), paint);
        c.drawLine(mx + dp(12f), deck + dp(2f), mx - dp(12f), deck + dp(10f), paint);

        // The booster.
        float fall = (t + dp(46f)) - deck;
        float by = deck + fall * Math.min(1f, bAlt / BOOSTER_START_M);
        float e = smoothW / typW();
        boolean lit = boosterState == 1 && bFuel > 0f && e > 0.12f;
        if (boosterState == 3) {
            if (boosterBurst) {
                boosterBurst = false;
                fx.burst(mx, deck, 40, dp(160f), 1.1f, dp(4f), 0xFFFF7A3D, true);
            }
            paint.setColor(0xFF2A2A30);
            c.drawRect(mx - dp(30f), deck - dp(6f), mx + dp(22f), deck, paint);
        } else {
            if (boosterState == 2 && boosterBurst) {
                boosterBurst = false;
                fx.burst(mx, deck, 26, dp(120f), 0.9f, dp(3.5f), 0xFF35D0BA, true);
            }
            if (lit) {
                float len = dp(10f) + Math.min(1.2f, e) * dp(40f);
                paint.setColor(0xFFFFD36A);
                c.drawRect(mx - dp(4f), by, mx + dp(4f), by + len, paint);
                paint.setColor(0xFFFF7A3D);
                c.drawRect(mx - dp(2f), by, mx + dp(2f), by + len * 0.7f, paint);
            }
            paint.setColor(0xFFE6EDF7);
            c.drawRoundRect(mx - dp(7f), by - dp(58f), mx + dp(7f), by, dp(3f), dp(3f), paint);
            paint.setColor(0xFF1A2230);
            c.drawRect(mx - dp(7f), by - dp(46f), mx + dp(7f), by - dp(40f), paint);
            if (bAlt < 150f || boosterState == 2) {
                paint.setColor(0xFFCFD6DE);
                paint.setStrokeWidth(dp(2f));
                float spread = boosterState == 2 ? 1f : Math.min(1f, (150f - bAlt) / 60f);
                c.drawLine(mx - dp(6f), by - dp(10f), mx - dp(6f) - spread * dp(10f), by + dp(2f), paint);
                c.drawLine(mx + dp(6f), by - dp(10f), mx + dp(6f) + spread * dp(10f), by + dp(2f), paint);
            }
            // Grid fins.
            paint.setColor(0xFF8D9BB0);
            c.drawRect(mx - dp(11f), by - dp(56f), mx - dp(7f), by - dp(50f), paint);
            c.drawRect(mx + dp(7f), by - dp(56f), mx + dp(11f), by - dp(50f), paint);
        }
        c.restore();

        // Readouts: height and speed, and the ease band.
        String head;
        int hc;
        if (boosterState == 2) {
            head = "BOOSTER LANDED  +" + Math.round(BOOSTER_REFUND * 100f) + "% FUEL";
            hc = ACCENT;
        } else if (boosterState == 3) {
            head = "BOOSTER LOST";
            hc = BAD;
        } else {
            head = "BOOSTER  " + Math.round(bAlt) + " m  ·  " + Math.round(bVel) + " m/s"
                    + (bVel > BOOSTER_SAFE_MPS ? "  TOO FAST" : bVel < 0 ? "  CLIMBING" : "");
            hc = bVel > BOOSTER_SAFE_MPS || bVel < 0 ? WARN : TEXT;
        }
        bold(c, head, l + dp(12f), t + dp(20f), 10f, hc, Paint.Align.LEFT);
        if (boosterState == 1) {
            float bl = l + dp(12f);
            float bw = dp(276f);
            float yy = t + dp(30f);
            float scale = 1.2f;
            paint.setColor(0x33FFFFFF);
            c.drawRect(bl, yy, bl + bw, yy + dp(8f), paint);
            paint.setColor(0x8835D0BA);
            c.drawRect(bl + bw * EASE_LO / scale, yy, bl + bw * EASE_HI / scale, yy + dp(8f), paint);
            float mxk = bl + bw * Math.min(1f, e / scale);
            boolean inBand = e >= EASE_LO && e <= EASE_HI;
            paint.setColor(inBand ? ACCENT : e > EASE_HI ? WARN : BAD);
            c.drawRect(mxk - dp(2.5f), yy - dp(4f), mxk + dp(2.5f), yy + dp(12f), paint);
            label(c, inBand ? "IN THE BAND - HOLD IT" : e > EASE_HI ? "EASE OFF" : "A LITTLE MORE",
                    bl, yy + dp(22f), 8f, inBand ? ACCENT : WARN, Paint.Align.LEFT);
            label(c, "booster fuel " + Math.round(Math.max(0f, bFuel) * 100f) + "%", bl + bw, yy + dp(22f), 8f,
                    bFuel < 0.25f ? BAD : FAINT, Paint.Align.RIGHT);
        }
    }

    private void drawThrustBar(Canvas c, float w, float h, float watts, float hover, String hoverLabel) {
        float barY = h - dp(34f);
        float barL = dp(16f);
        float barR = w - dp(60f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, barY, barR, barY + dp(12f), dp(6f), dp(6f), paint);
        float scaleMax = hover * 2f;
        paint.setColor(fuelOut ? FAINT : watts >= hover ? ACCENT : BAD);
        c.drawRoundRect(barL, barY, barL + (barR - barL) * Math.min(1f, watts / scaleMax), barY + dp(12f),
                dp(6f), dp(6f), paint);
        float hoverX = barL + (barR - barL) * (hover / scaleMax);
        paint.setColor(TEXT);
        c.drawRect(hoverX - dp(2f), barY - dp(5f), hoverX + dp(2f), barY + dp(17f), paint);
        label(c, hoverLabel + " " + Math.round(hover) + " W", hoverX, barY - dp(9f), 8f, TEXT,
                Paint.Align.CENTER);
        label(c, Math.round(watts) + " W", barL, barY - dp(9f), 8.5f, FAINT, Paint.Align.LEFT);
    }

    /** Five mission cards along the bottom before launch; tap an unlocked one to fly it. */
    private void drawMissionCards(Canvas c, float w, float h) {
        float gap = dp(12f);
        // Fit five cards to the width left of the ladder on narrower screens.
        cardW = Math.max(dp(90f), Math.min(dp(176f), (w - dp(60f) - (MISSIONS - 1) * gap) / MISSIONS));
        float total = MISSIONS * cardW + (MISSIONS - 1) * gap;
        float l0 = (w - dp(60f) - total) / 2f;
        cardB = h - dp(62f);
        cardT = cardB - dp(84f);
        for (int i = 0; i < MISSIONS; i++) {
            float l = l0 + i * (cardW + gap);
            cardL[i] = l;
            boolean open = i <= cleared;
            boolean sel = i == mission;
            paint.setColor(sel ? 0xF0123A4A : 0xD00B1830);
            c.drawRoundRect(l, cardT, l + cardW, cardB, dp(10f), dp(10f), paint);
            if (sel) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(ACCENT);
                c.drawRoundRect(l, cardT, l + cardW, cardB, dp(10f), dp(10f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            bold(c, (i + 1) + "  " + MISSION_NAME[i], l + dp(12f), cardT + dp(24f), 11f,
                    open ? (sel ? ACCENT : TEXT) : FAINT, Paint.Align.LEFT);
            if (!open) {
                label(c, "LOCKED - clear mission " + i, l + dp(12f), cardT + dp(46f), 8.5f, FAINT, Paint.Align.LEFT);
                continue;
            }
            String key = MISSION_KEY[i];
            if (bests.has(key)) {
                float pct = bests.get(key, 0f);
                int s = pct >= 35f ? 3 : pct >= 20f ? 2 : 1;
                for (int k = 0; k < 3; k++) {
                    paint.setColor(k < s ? 0xFFF5C518 : 0x44FFFFFF);
                    drawStar(c, l + dp(20f) + k * dp(22f), cardT + dp(46f), dp(8f));
                }
                label(c, "best " + Math.round(pct) + "% fuel left", l + dp(12f), cardT + dp(72f), 8.5f, DIM, Paint.Align.LEFT);
            } else {
                label(c, i == MOON ? "the finale" : "not flown yet", l + dp(12f), cardT + dp(50f), 8.5f, DIM, Paint.Align.LEFT);
                label(c, "tap to select", l + dp(12f), cardT + dp(70f), 8f, FAINT, Paint.Align.LEFT);
            }
        }
    }

    /* ---------- the moon ---------- */

    /**
     * Mission 5: a powered descent. The lander starts high and sinking; power holds it up. Hovering
     * forever burns the tank, so the way down is to ease off and fall, then pull hard near the ground
     * - timed right, it kisses the surface.
     */
    private void renderMoon(Canvas c, float w, float h, float dt) {
        float hover = typW() * MOON_HOVER_SHARE;
        float e = fuelOut ? 0f : smoothW / typW();
        if (started && !over && !orbit) {
            float vt = Math.max(-45f, Math.min(12f, -45f + 50f * e));
            landerV += (vt - landerV) * Math.min(1f, dt / 1.2f);
            landerAlt = Math.min(1400f, landerAlt + landerV * dt);
            climbedAtStart = MOON_START_M - landerAlt;
            if (landerAlt <= 0f) {
                landerAlt = 0f;
                if (-landerV <= MOON_SAFE_MPS) {
                    succeed(w, h);
                } else {
                    fail("CRASHED ON THE MOON", w, h);
                }
                landerV = 0f;
            }
        }
        float throttle = fuelOut || orbit ? 0f : Math.min(1.4f, e / MOON_HOVER_SHARE);
        plume += (throttle - plume) * Math.min(1f, 8f * dt);

        c.save();
        c.translate(shake.dx, shake.dy);
        paint.setShader(null);
        paint.setColor(0xFF03050A);
        c.drawRect(0, 0, w, h, paint);
        paint.setColor(0xFFFFFFFF);
        for (int i = 0; i < starX.length; i++) {
            paint.setAlpha(120 + (i * 37) % 120);
            c.drawCircle(starX[i] * w, starY[i] * h * 0.7f, dp(starR[i]), paint);
        }
        paint.setAlpha(255);
        // Earth, hanging over the horizon.
        float ex = w * 0.2f;
        float ey = h * 0.2f;
        Fx.glow(c, ex, ey, dp(70f), 0x553A7BD5);
        paint.setColor(0xFF2F6FC0);
        c.drawCircle(ex, ey, dp(34f), paint);
        paint.setColor(0xFF3E9A55);
        c.drawOval(ex - dp(20f), ey - dp(14f), ex + dp(2f), ey + dp(8f), paint);
        c.drawOval(ex + dp(6f), ey + dp(2f), ex + dp(22f), ey + dp(20f), paint);
        paint.setColor(0xCCFFFFFF);
        c.drawOval(ex - dp(26f), ey - dp(28f), ex + dp(10f), ey - dp(20f), paint);
        paint.setColor(0x88000000);
        c.drawOval(ex - dp(8f), ey - dp(36f), ex + dp(60f), ey + dp(36f), paint);

        float landerY = h * 0.42f;
        float zoom = h * 0.5f / MOON_START_M;
        float surfaceY = landerY + dp(30f) + landerAlt * zoom;
        // Distant ridge, then the grey plain with craters that grow as you come down.
        float near = 1f + (MOON_START_M - landerAlt) / 400f;
        paint.setColor(0xFF4E5057);
        path.reset();
        path.moveTo(0, surfaceY);
        for (int k = 0; k <= 12; k++) {
            float x = w * k / 12f;
            float peak = dp(18f + ((k * 53) % 40)) * Math.min(2f, near * 0.8f);
            path.lineTo(x, surfaceY - peak);
        }
        path.lineTo(w, surfaceY);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFF8A8C92);
        c.drawRect(0, surfaceY, w, h + dp(40f), paint);
        for (int k = 0; k < 9; k++) {
            float cxr = w * ((k * 0.37f + 0.05f) % 1f);
            cxr = w * 0.5f + (cxr - w * 0.5f) * near;
            float cyr = surfaceY + dp(14f + (k % 3) * 22f) * near;
            float rw = dp(22f + (k % 4) * 12f) * near;
            if (cyr > h + rw) {
                continue;
            }
            paint.setColor(0xFF6E7077);
            c.drawOval(cxr - rw, cyr - rw * 0.28f, cxr + rw, cyr + rw * 0.28f, paint);
            paint.setColor(0xFF9EA0A6);
            c.drawOval(cxr - rw * 0.8f, cyr - rw * 0.14f, cxr + rw * 0.8f, cyr + rw * 0.26f, paint);
        }
        // Landing pad marker.
        paint.setColor(0xAA35D0BA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        c.drawOval(w * 0.5f - dp(40f) * near, surfaceY + dp(2f), w * 0.5f + dp(40f) * near, surfaceY + dp(2f) + dp(10f) * near, paint);
        paint.setStyle(Paint.Style.FILL);

        // Dust kicked up by the engine near the ground.
        if (landerAlt < 70f && plume > 0.3f && !over) {
            for (int k = 0; k < 2; k++) {
                float dir = k == 0 ? -1f : 1f;
                fx.spawn(w * 0.5f, surfaceY - dp(2f), dir * dp(200f + (float) Math.random() * 180f),
                        -dp((float) Math.random() * 30f), 0.8f, dp(3f), 0xAACFCFD4, false);
            }
        }

        // The lander.
        float lx = w * 0.5f;
        float ly = over ? surfaceY - dp(30f) : landerY;
        if (!over) {
            if (plume > 0.05f) {
                float len = dp(10f) + plume * dp(60f);
                paint.setColor(0xFFBFE3FF);
                path.reset();
                path.moveTo(lx - dp(7f), ly + dp(12f));
                path.lineTo(lx + dp(7f), ly + dp(12f));
                path.lineTo(lx, ly + dp(12f) + len);
                path.close();
                c.drawPath(path, paint);
                Fx.glow(c, lx, ly + dp(20f), dp(40f), 0x557FB6FF);
            }
            paint.setColor(0xFFD9A93A);   // gold foil descent stage
            c.drawRect(lx - dp(22f), ly - dp(8f), lx + dp(22f), ly + dp(10f), paint);
            paint.setColor(0xFFB78A28);
            c.drawRect(lx - dp(22f), ly + dp(2f), lx + dp(22f), ly + dp(4f), paint);
            paint.setColor(0xFFC8CCD2);   // ascent stage
            c.drawRect(lx - dp(15f), ly - dp(30f), lx + dp(15f), ly - dp(8f), paint);
            paint.setColor(0xFF1A2230);
            c.drawRect(lx - dp(9f), ly - dp(25f), lx - dp(1f), ly - dp(17f), paint);
            paint.setColor(0xFFE6EDF7);
            paint.setStrokeWidth(dp(1.5f));
            c.drawLine(lx + dp(8f), ly - dp(30f), lx + dp(14f), ly - dp(40f), paint);
            c.drawCircle(lx + dp(14f), ly - dp(42f), dp(4f), paint);
            paint.setColor(0xFFCFD6DE);
            paint.setStrokeWidth(dp(2.5f));
            c.drawLine(lx - dp(18f), ly + dp(8f), lx - dp(32f), ly + dp(28f), paint);
            c.drawLine(lx + dp(18f), ly + dp(8f), lx + dp(32f), ly + dp(28f), paint);
            c.drawRect(lx - dp(38f), ly + dp(27f), lx - dp(26f), ly + dp(30f), paint);
            c.drawRect(lx + dp(26f), ly + dp(27f), lx + dp(38f), ly + dp(30f), paint);
            if (orbit) {
                // A flag goes up beside it.
                float t = (float) (sessionSeconds - wonAt);
                float pole = Math.min(1f, Math.max(0f, t - 0.8f) / 1.2f) * dp(56f);
                float fxp = lx + dp(80f);
                paint.setColor(0xFFE6EDF7);
                paint.setStrokeWidth(dp(2f));
                c.drawLine(fxp, ly + dp(30f), fxp, ly + dp(30f) - pole, paint);
                if (pole > dp(40f)) {
                    float wave = (float) Math.sin(t * 5f) * dp(2f);
                    paint.setColor(0xFF35D0BA);
                    c.drawRect(fxp, ly + dp(30f) - pole, fxp + dp(30f), ly + dp(30f) - pole + dp(18f) + wave, paint);
                    paint.setColor(0xFFF5C518);
                    c.drawCircle(fxp + dp(15f), ly + dp(39f) - pole + wave * 0.5f, dp(4f), paint);
                }
            }
        }
        fx.draw(c);
        c.restore();

        // HUD.
        float cx = w * 0.30f;
        float by = h * 0.20f;
        label(c, "MISSION 5 OF 5  ·  MOON LANDING", cx, by - dp(50f), 10f, DIM, Paint.Align.CENTER);
        float sink = -landerV;
        float safe = Math.max(MOON_SAFE_MPS - 0.5f, 3f + landerAlt * 0.07f);
        String big;
        int col;
        if (!started) {
            big = "ROW TO BEGIN DESCENT";
            col = DIM;
        } else if (orbit) {
            big = "TOUCHDOWN";
            col = ACCENT;
        } else if (over) {
            big = failReason;
            col = BAD;
        } else {
            big = Math.round(landerAlt) + " m";
            col = sink > safe ? BAD : ACCENT;
        }
        bold(c, big, cx, by, started && !over && !orbit ? 44f : 24f, col, Paint.Align.CENTER);
        String cap;
        int capCol = FAINT;
        if (!started) {
            cap = "ease off to fall, pull to brake  ·  touch down under " + Math.round(MOON_SAFE_MPS) + " m/s  ·  "
                    + Math.round(fuelBudget) + " strokes of fuel";
        } else if (orbit) {
            cap = String.format(java.util.Locale.US, "campaign complete  ·  fuel left %.0f%%%s  ·  tap to land again",
                    fuelLeftPct, newBest ? "  ·  NEW BEST" : "");
            capCol = ACCENT;
        } else if (over) {
            cap = "hit at " + Math.round(Math.abs(sink)) + " m/s  ·  tap to try again";
        } else if (fuelOut) {
            cap = "OUT OF FUEL - FALLING";
            capCol = BAD;
        } else if (sink > safe) {
            cap = "SINK " + Math.round(sink) + " m/s - TOO FAST, PULL";
            capCol = BAD;
        } else if (sink < safe * 0.4f && landerAlt > 60f) {
            cap = "SINK " + Math.round(sink) + " m/s - EASE OFF, SAVE FUEL";
            capCol = WARN;
        } else {
            cap = "SINK " + Math.round(sink) + " m/s  ·  safe " + Math.round(safe) + " m/s";
            capCol = ACCENT;
        }
        bold(c, cap, cx, by + dp(22f), 11f, capCol, Paint.Align.CENTER);
        drawFuelBar(c, cx, by + dp(40f), Math.max(0f, climbedAtStart), "m down");
        if (orbit) {
            drawStars(c, cx, by + dp(96f));
        }

        // Sink gauge on the right: the needle against the safe speed for this height.
        float gTop = dp(70f);
        float gBot = h - dp(60f);
        float gx = w - dp(26f);
        float span = gBot - gTop;
        paint.setColor(0x33FFFFFF);
        c.drawRect(gx - dp(2f), gTop, gx + dp(2f), gBot, paint);
        float sy = gTop + span * Math.min(1f, Math.max(0f, safe) / 45f);
        paint.setColor(0x6635D0BA);
        c.drawRect(gx - dp(8f), gTop, gx + dp(8f), sy, paint);
        label(c, "SAFE", gx - dp(12f), sy + dp(4f), 7.5f, ACCENT, Paint.Align.RIGHT);
        float ny = gTop + span * Math.min(1f, Math.max(0f, sink) / 45f);
        paint.setColor(sink > safe ? BAD : ACCENT);
        c.drawCircle(gx, ny, dp(6f), paint);
        label(c, "SINK m/s", gx - dp(12f), gTop - dp(8f), 7.5f, DIM, Paint.Align.RIGHT);

        drawThrustBar(c, w, h, smoothW, hover, "HOVER");
    }

    /** Birds and a jet low down, a weather balloon higher, then satellites, the moon and a station. */
    private void drawAltitudeLife(Canvas c, float w, float h) {
        double t = sessionSeconds;
        if (altitude < 6_000f) {
            float by = h * 0.35f + (altitude * 0.08f) % (h * 0.6f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(0xAA1A2230);
            float bx0 = (float) (w - ((t * dp(50f)) % (w + dp(300f))));
            for (int b = 0; b < 5; b++) {
                float bx = bx0 + b * dp(22f);
                float yy = by + (b % 2) * dp(9f);
                float flap = (float) Math.sin(t * 8 + b) * dp(4f);
                c.drawLine(bx - dp(7f), yy - flap, bx, yy, paint);
                c.drawLine(bx, yy, bx + dp(7f), yy - flap, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if (altitude > 3_000f && altitude < 14_000f) {
            float jy = h * 0.2f + ((altitude - 3_000f) * 0.06f) % (h * 0.7f);
            float jx = (float) (((t * dp(120f)) % (w + dp(400f))) - dp(200f));
            paint.setStrokeWidth(dp(3f));
            paint.setColor(0x88FFFFFF);
            c.drawLine(jx - dp(220f), jy, jx - dp(20f), jy, paint);
            paint.setColor(0xFFE9EEF5);
            c.drawRoundRect(jx - dp(22f), jy - dp(4f), jx + dp(22f), jy + dp(4f), dp(4f), dp(4f), paint);
            c.drawRect(jx - dp(6f), jy - dp(14f), jx + dp(4f), jy + dp(14f), paint);
        }
        if (altitude > 18_000f && altitude < 40_000f) {
            float gy = h * 0.3f + ((altitude - 18_000f) * 0.02f) % (h * 0.6f);
            float gx = w * 0.2f + (float) Math.sin(t * 0.3) * dp(20f);
            paint.setColor(0xDDF4F4F4);
            c.drawCircle(gx, gy, dp(18f), paint);
            paint.setStrokeWidth(dp(1f));
            paint.setColor(0x99FFFFFF);
            c.drawLine(gx, gy + dp(18f), gx, gy + dp(50f), paint);
            paint.setColor(0xFFF5C518);
            c.drawRect(gx - dp(4f), gy + dp(50f), gx + dp(4f), gy + dp(58f), paint);
        }
        float space = Math.min(1f, altitude / 80_000f);
        if (space > 0.5f) {
            int a = (int) (255 * Math.min(1f, (space - 0.5f) * 3f));
            Fx.glow(c, w * 0.18f, h * 0.18f, dp(70f), (Math.min(a, 90) << 24) | 0xE9EEF5);
            paint.setColor((a << 24) | 0xE9EEF5);
            c.drawCircle(w * 0.18f, h * 0.18f, dp(30f), paint);
            paint.setColor((Math.min(a, 60) << 24) | 0x9AA5B1);
            c.drawCircle(w * 0.18f - dp(8f), h * 0.18f - dp(6f), dp(6f), paint);
            c.drawCircle(w * 0.18f + dp(10f), h * 0.18f + dp(8f), dp(4f), paint);
            for (int k = 0; k < 2; k++) {
                float sx = (float) (((t * dp(26f + k * 18f) + k * 700) % (w + dp(200f))) - dp(100f));
                float sy = h * (0.3f + k * 0.25f);
                paint.setColor((a << 24) | 0xC8D2DC);
                c.drawRect(sx - dp(5f), sy - dp(5f), sx + dp(5f), sy + dp(5f), paint);
                paint.setColor((a << 24) | 0x3A6EA5);
                c.drawRect(sx - dp(24f), sy - dp(3f), sx - dp(7f), sy + dp(3f), paint);
                c.drawRect(sx + dp(7f), sy - dp(3f), sx + dp(24f), sy + dp(3f), paint);
                if (((int) (t * 2 + k)) % 2 == 0) {
                    paint.setColor((a << 24) | 0xFF4A4A);
                    c.drawCircle(sx, sy - dp(8f), dp(2f), paint);
                }
            }
        }
        // The station itself, waiting at the top of mission 4.
        if (campaign() && mission == 3 && altitude > MISSION_TARGET[3] - MISSION_TOL[3] * 4f) {
            float gap = MISSION_TARGET[3] - altitude;
            float sy = h * 0.58f - gap * (h * 0.12f / MISSION_TOL[3]) - dp(10f);
            float sx = w * 0.5f + dp(150f);
            paint.setColor(0xFFC8D2DC);
            c.drawRect(sx - dp(34f), sy - dp(4f), sx + dp(34f), sy + dp(4f), paint);
            c.drawRect(sx - dp(8f), sy - dp(12f), sx + dp(8f), sy + dp(12f), paint);
            paint.setColor(0xFF3A6EA5);
            for (int k = -1; k <= 1; k += 2) {
                c.drawRect(sx + k * dp(40f) - dp(6f), sy - dp(30f), sx + k * dp(40f) + dp(6f), sy - dp(6f), paint);
                c.drawRect(sx + k * dp(40f) - dp(6f), sy + dp(6f), sx + k * dp(40f) + dp(6f), sy + dp(30f), paint);
            }
            if (((int) (t * 2)) % 2 == 0) {
                paint.setColor(0xFF35D0BA);
                c.drawCircle(sx, sy - dp(16f), dp(3f), paint);
            }
        }
    }

    /** Smoke puffs shed from the nozzle while thrusting, growing and fading as they fall behind. */
    private void drawSmoke(Canvas c, float w, float h, float dt) {
        float rocketY = h * 0.58f;
        smokeClock += dt;
        if (started && !over && plume > 0.2f && smokeClock > 0.06) {
            smokeClock = 0;
            smokeX[smokeNext] = w * 0.5f + (float) (Math.random() - 0.5) * dp(10f);
            smokeY[smokeNext] = rocketY + dp(40f) + plume * dp(60f);
            smokeLife[smokeNext] = 1f;
            smokeNext = (smokeNext + 1) % smokeX.length;
        }
        float space = Math.min(1f, altitude / 80_000f);
        for (int i = 0; i < smokeX.length; i++) {
            if (smokeLife[i] <= 0) {
                continue;
            }
            smokeLife[i] -= dt * 0.6f;
            smokeY[i] += dp(120f) * dt * (0.5f + velocity * 0.02f);
            smokeX[i] += (float) Math.sin(i + smokeLife[i] * 6) * dp(10f) * dt
                    - shearVis * dp(220f) * dt;   // the shear blows the trail sideways
            float r = dp(8f) + (1f - smokeLife[i]) * dp(34f);
            int a = (int) (smokeLife[i] * 150 * (1f - space));
            paint.setColor((Math.max(0, a) << 24) | 0xE6E6EA);
            c.drawCircle(smokeX[i], smokeY[i], r, paint);
        }
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
