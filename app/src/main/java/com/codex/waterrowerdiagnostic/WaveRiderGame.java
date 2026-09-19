package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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
 */
final class WaveRiderGame extends GameView {

    private enum Phase { WAITING, RIDING, WIPEOUT, KICKOUT }

    /* ---------- surf spots ---------- */
    private static final String[] SPOT_NAMES = {"GLASS COVE", "POINT BREAK", "REEF PASS", "BOMBORA"};
    private static final String[] SPOT_KEYS = {"cove", "point", "reef", "bombora"};
    private static final String[] SPOT_BLURB = {
            "slow, friendly wave", "the classic", "quick wave, barrels often", "fastest wave, big sets"};
    /** Multiplies the whole wave speed. Modest on purpose: base 0.90 x typical is the tuned ride. */
    private static final float[] SPOT_SPEED = {0.95f, 1.0f, 1.04f, 1.07f};
    /** Mean seconds between big sets. */
    private static final float[] SPOT_SET_GAP = {60f, 48f, 48f, 34f};
    /** Minimum seconds between barrels. */
    private static final float[] SPOT_BARREL_GAP = {30f, 22f, 15f, 24f};
    private static final int[][] SPOT_SKY = {
            {0xFF3B5C8A, 0xFFF7C98B}, {0xFF2B3F70, 0xFFF3A469},
            {0xFF1F4E79, 0xFF9FE3F0}, {0xFF2A2440, 0xFFD9786A}};
    private static final int[][] SPOT_SEA = {
            {0xFF2A7FA0, 0xFF0E3A52}, {0xFF1E5C86, 0xFF0A2A44},
            {0xFF1C8FA6, 0xFF0A3D4E}, {0xFF18476B, 0xFF081E33}};
    private static final int[][] SPOT_FACE = {
            {0xFF2A86B8, 0xFF10496A}, {0xFF1B6FA8, 0xFF0D3C5E},
            {0xFF1FA3B8, 0xFF0B5566}, {0xFF16547E, 0xFF08263C}};
    private static final int[] SPOT_LIP = {0xFF93D3F2, 0xFF7FC6EE, 0xFF8EE8F0, 0xFF6FA8CF};

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
    private final float[] spotBest = new float[4];
    private float spotLeft, spotTop, spotRight, spotBottom;

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
    private final float[] npcBest = new float[4];
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
        spot = Math.max(0, Math.min(SPOT_NAMES.length - 1, Math.round(bests.get("surf.spot", 1f))));
        for (int i = 0; i < spotBest.length; i++) {
            spotBest[i] = 0f;
        }
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
        // Everyone in the lineup has already had one wave today, so there is a board to beat.
        for (int i = 0; i < npcBest.length; i++) {
            npcBest[i] = npcRideLength();
            queue[i] = i;
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
        for (int i = 0; i < spotBest.length; i++) {
            if (spotBest[i] > 0f) {
                bests.recordHighest("surf.ride." + SPOT_KEYS[i], spotBest[i]);
            }
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.WAITING && driving && boat.value() > 1.0f) {
            phase = Phase.RIDING;
            position = 0f;
            rideSeconds = 0;
            rides++;
            rideTricks = 0;
            rideMedal = -1;
            float gap = SPOT_BARREL_GAP[spot];
            nextBarrelAt = sessionSeconds + gap * 0.64 + Math.random() * 12;
            finishNpcRide();
        }
        // Surge at the top of the wave: a reading well above this rower's typical power while
        // riding high on the face launches a trick. Magnitudes come from here, never onStroke.
        // The S4 holds a reading until the next one, so a single held surge must not fire twice.
        int watts = s.watts;
        int surge = surgeWatts();
        if (watts < surge) {
            surgeArmed = true;
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
                spot = (spot + 1) % SPOT_NAMES.length;
                bests.putFloat("surf.spot", spot);
                nextBarrelAt = sessionSeconds + SPOT_BARREL_GAP[spot];
                if (!setActive) {
                    nextSetAt = Math.max(nextSetAt, sessionSeconds + SET_WARN + 4);
                }
                showPopup(SPOT_NAMES[spot] + " - " + SPOT_BLURB[spot], ACCENT, 2.0);
                return true;
            }
            if (phase == Phase.WIPEOUT || phase == Phase.KICKOUT) {
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
        int place = 0;
        for (int i = 0; i < npcBest.length; i++) {
            if (npcBest[i] > rideSeconds) {
                place++;
            }
        }
        endNote = (rideMedal >= 0 ? MEDAL_NAMES[rideMedal] + " MEDAL  ·  " : "")
                + PLACES[place] + " IN THE LINEUP";
        npcRider = queue[0];
        for (int i = 0; i < queue.length - 1; i++) {
            queue[i] = queue[i + 1];
        }
        queue[queue.length - 1] = npcRider;
        npcRideStart = sessionSeconds;
        npcRideLen = npcRideLength();
    }

    /** The surfer on the wave finishes - early if you paddle into the next one. */
    private void finishNpcRide() {
        if (npcRider < 0) {
            return;
        }
        boolean record = npcRideLen > npcBest[npcRider];
        npcBest[npcRider] = Math.max(npcBest[npcRider], npcRideLen);
        showPopup(NPC_NAMES[npcRider] + " RODE " + clock(npcRideLen) + (record ? " - NEW BEST" : ""),
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
            position -= position * 0.12f * dt;
            // With the handle sensor, leaning carves along the face - a small correction, not a
            // substitute for matching the wave's pace.
            if (hasSteering()) {
                position += steering() * 0.18f * dt;
            }
            score += dt * (inPocket() ? 2.0 : 1.0) * (inBarrel() ? 3.0 : 1.0) * (inSet ? 1.5 : 1.0);
            if (!inBarrel() && sessionSeconds >= nextBarrelAt) {
                barrelUntil = sessionSeconds + 6;
                nextBarrelAt = sessionSeconds + SPOT_BARREL_GAP[spot] + Math.random() * 14;
            }
            if (inBarrel() && inPocket()) {
                // Counted once per barrel, on the way out.
                if (sessionSeconds > barrelUntil - dt * 2) {
                    barrels++;
                }
            }
            // Medals as the ride clock passes each mark.
            while (rideMedal + 1 < MEDAL_AT.length && rideSeconds >= MEDAL_AT[rideMedal + 1]) {
                rideMedal++;
                showPopup(MEDAL_NAMES[rideMedal] + " MEDAL!", MEDAL_COLORS[rideMedal], 2.0);
                spray.burst(w * 0.5f, h * 0.30f, 40, dp(220f), 1.0f, dp(3.5f),
                        MEDAL_COLORS[rideMedal], false);
                cheerUntil = sessionSeconds + 2.0;
            }
            if (position <= -1f) {
                endRide(Phase.WIPEOUT);
                shake.kick(dp(18f));
                spray.burst(w * 0.62f, h * 0.55f, 60, dp(260f), 1.0f, dp(4f), 0xDDFFFFFF, true);
            } else if (position >= 1f) {
                endRide(Phase.KICKOUT);
            }
        }
        // A trick lands - or does not, if the ride ended mid-air.
        if (trickPending && sessionSeconds - trickStart >= TRICK_LEN) {
            trickPending = false;
            if (phase == Phase.RIDING) {
                rideTricks++;
                sessionTricks++;
                int pts = (10 + 5 * Math.min(rideTricks - 1, 4)) * (inSet ? 2 : 1);
                score += pts;
                showPopup(TRICKS[trickName] + "  +" + pts + (inSet ? "  SET BONUS" : ""), 0xFFF5C518, 1.8);
                cheerUntil = sessionSeconds + 2.0;
                shake.kick(dp(6f));
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
        float baseCrest = h * 0.30f;
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
        float throwOver = (inBarrel() ? w * 0.30f : w * 0.10f) + setLift * w * 0.05f;
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
        if (phase != Phase.WIPEOUT) {
            if (tricking()) {
                drawTrick(c, surfX, surfY);
            } else {
                drawSurfer(c, surfX, surfY, inPocket());
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
        switch (phase) {
            case WAITING:
                big = "PADDLE FOR IT";
                cap = npcRider >= 0
                        ? NPC_NAMES[npcRider] + " has this one - get above 1.0 m/s to take the next"
                        : "get above 1.0 m/s to catch the wave";
                col = DIM;
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
                if (tricking()) {
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

        float fy = h - dp(12f);
        float colw = w / 6f;
        stat(c, colw * 0.5f, fy, String.valueOf(Math.round(score)), "POINTS");
        stat(c, colw * 1.5f, fy, clock(bestRide), "LONGEST RIDE");
        stat(c, colw * 2.5f, fy, String.valueOf(barrels), "BARRELS");
        stat(c, colw * 3.5f, fy, String.valueOf(sessionTricks), "TRICKS");
        drawMedalTally(c, colw * 4.5f, fy);
        stat(c, colw * 5.5f, fy, bests.has("surf.score")
                ? String.valueOf(Math.round(bests.get("surf.score", 0))) : "--", "BEST");
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

    /** Spot picker, top left. Only live between rides, so a switch can never end one. */
    private void drawSpotChip(Canvas c) {
        boolean live = phase != Phase.RIDING;
        spotLeft = dp(10f);
        spotTop = dp(10f);
        spotRight = spotLeft + dp(190f);
        spotBottom = spotTop + dp(38f);
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
    }

    /** The lineup's board: everyone's longest ride today, you included. */
    private void drawBoard(Canvas c) {
        for (int i = 0; i < board.length; i++) {
            board[i] = i;
        }
        // Insertion sort, longest first - five entries, no allocation.
        for (int i = 1; i < board.length; i++) {
            int v = board[i];
            float key = boardRide(v);
            int j = i - 1;
            while (j >= 0 && boardRide(board[j]) < key) {
                board[j + 1] = board[j];
                j--;
            }
            board[j + 1] = v;
        }
        float x = dp(10f);
        float y = dp(66f);
        paint.setColor(0x880A1A26);
        c.drawRoundRect(x, y - dp(14f), x + dp(150f), y + dp(14f) * board.length + dp(4f),
                dp(8f), dp(8f), paint);
        label(c, "LINEUP - LONGEST RIDE", x + dp(8f), y - dp(2f), 7.5f, FAINT, Paint.Align.LEFT);
        for (int i = 0; i < board.length; i++) {
            int who = board[i];
            float ry = y + dp(12f) + i * dp(14f);
            boolean you = who == 4;
            int color = you ? ACCENT : NPC_COLORS[who];
            label(c, PLACES[i], x + dp(8f), ry, 8.5f, you ? ACCENT : DIM, Paint.Align.LEFT);
            if (you) {
                bold(c, "YOU", x + dp(34f), ry, 9f, color, Paint.Align.LEFT);
            } else {
                label(c, NPC_NAMES[who], x + dp(34f), ry, 9f, color, Paint.Align.LEFT);
            }
            float ride = boardRide(who);
            label(c, ride > 0 ? clock(ride) : "--", x + dp(142f), ry, 9f, you ? ACCENT : TEXT,
                    Paint.Align.RIGHT);
        }
    }

    private float boardRide(int who) {
        if (who == 4) {
            return (float) Math.max(bestRide, phase == Phase.RIDING ? rideSeconds : 0);
        }
        return npcBest[who];
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
        if (rideSeconds > 0 && Math.random() < 0.9) {
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
