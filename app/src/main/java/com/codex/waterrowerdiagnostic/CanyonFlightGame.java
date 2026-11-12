package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Canyon Flight: your boat speed is your altitude. Ring gates come at you at different heights;
 * fly through them or lose a life. Gates step up and down, so you are changing gear every hundred
 * metres - it is intervals wearing a wingsuit.
 *
 * <p>Made easier in 3.14.0 after "too hard to get inside the ring given our method of capturing
 * row": altitude follows a rolling average of speed with a small dead zone, so one weak stroke no
 * longer drops you; rings are 60% taller and pull you toward their centre in the last 30 m; the
 * altitude scale spans the rower's own range; and each ring is placed around the speed you have
 * actually been holding, rather than on a fixed random walk.
 *
 * <p>3.19.5, from the emulator: the sunset sky was being drawn at the alpha of whatever colour the
 * HUD set last (a lost-life dot is 20% white), so the whole game looked dark grey. Also a proper
 * little plane that pitches with the climb, a sun, drifting clouds, a flock of birds, rings that
 * look like rings (front and back halves around the plane, a lit core), "+1" popups and a streak
 * counter that glows.
 *
 * <p>Canyon upgrades (the rower's list):
 * <ul>
 *   <li><b>Pickups in the rings.</b> Every ring carries a gem at its heart (+2), every sixth a heart
 *   (a life back, or +2 at full health). Take it by going through the middle. Clipping the outer
 *   edge of a ring and still getting through is a <b>close shave</b>, +2.</li>
 *   <li><b>The canyon narrows.</b> A rock roof comes down and the floor comes up over the first
 *   ~2 km of a run, closing to the rower's own low-to-high speed band (from {@link RowerProfile}).
 *   Touching rock throws sparks; staying on it for most of a second costs a life. So both easing
 *   off and overcooking it have a price.</li>
 *   <li><b>Forks.</b> Every ~600 m a rock spire splits the canyon. Be above its middle when you
 *   reach it and you take the HIGH ROAD (harder pace, two star rings worth +5 each); below it and
 *   you take the LOW ROAD (easy pace, a gem and a heart). Once in, the spire is rock like the rest.</li>
 *   <li><b>The light moves toward sunset</b> with distance flown: afternoon, golden hour, sunset,
 *   dusk with stars. {@link Daylight} is shared with DRIVE.</li>
 * </ul>
 *
 * <p>The race upgrades (this build):
 * <ul>
 *   <li><b>A run is a time trial now.</b> The canyon has an end - {@link #EXIT_METRES} - and a
 *   timing gate every {@link #CHECK_METRES}. Each split is medalled against the rower's own speeds
 *   ({@link CanyonRouteMap#medal}): gold at their high speed, silver at typical, bronze at low. The
 *   whole run is medalled the same way and the best time is kept as {@code canyon.exit}.</li>
 *   <li><b>A rival plane</b> flies the same canyon, rubber-bands so it is never out of reach,
 *   surges every ~20 s, and taunts every time the lead changes. It is drawn flying the rings when
 *   it is on screen and as an edge chevron with the gap when it is not.</li>
 *   <li><b>Boost.</b> A ring taken clean through the middle with no rock on your paint charges the
 *   boost. Spend it - tap the meter, or it fires itself when full - for a few seconds of thrust:
 *   real metres down the canyon (so it buys you the race and the clock) and rock immunity while it
 *   burns.</li>
 *   <li><b>A route map</b> along the top right: checkpoints, the forks you took, and the branches
 *   still locked. Higher tiers open with your best score - a third SLOT road through the middle of
 *   every fork, a fourth boost charge, and a night run where prizes count double.</li>
 *   <li><b>A photo finish</b> at the canyon exit: the shutter flash, then a print of the line with
 *   both planes where they actually were, the margin, your split and the medal.</li>
 * </ul>
 */
final class CanyonFlightGame extends GameView {

    private static final int LIVES = 3;
    /** Half-height of a ring as a share of the sky: was 0.10. */
    private static final float RING_BAND = 0.16f;
    /** Within this many metres of a ring, altitude is pulled toward its centre. */
    private static final float MAGNET_METRES = 30f;
    /** Metres of run over which the canyon closes to its narrowest. */
    private static final float NARROW_START = 150f;
    private static final float NARROW_SPAN = 1800f;
    private static final float FORK_LEN = 170f;
    /** Seconds of continuous rock contact that costs a life. */
    private static final float SCRAPE_LIMIT = 0.9f;
    /** Length of the canyon: the run ends at the exit, with a photo finish. */
    private static final float EXIT_METRES = 2000f;
    /** Metres between timing gates. */
    private static final float CHECK_METRES = 400f;
    /** Seconds of thrust per boost charge spent. */
    private static final float BOOST_PER_CHARGE = 1.3f;
    /** Metres the gap must actually swing before the lead is called changed. */
    private static final float LEAD_HYSTERESIS = 5f;

    private static final int NONE = -1;
    private static final int GEM = 0;
    private static final int HEART = 1;
    private static final int STAR = 2;

    /** Roads through a fork, in the order {@link CanyonRouteMap} colours them. */
    private static final int LOW_ROAD = 0;
    private static final int HIGH_ROAD = 1;
    private static final int SLOT_ROAD = 2;
    private static final String[] ROAD_NAMES = {"LOW", "HIGH", "SLOT"};

    private static final class Fork {
        final float start;
        final float end;
        /** Two spires with a slot between them, once the rower has unlocked it. */
        final boolean slot;
        /** -1 undecided, else LOW_ROAD / HIGH_ROAD / SLOT_ROAD. */
        int chosen = -1;

        Fork(float start, float end, boolean slot) {
            this.start = start;
            this.end = end;
            this.slot = slot;
        }
    }

    private static final class Gate {
        final float at;        // metres
        final float speed;     // centre of the band
        final int pickup;
        final Fork fork;
        final int branch;      // -1 not in a fork, else the road it belongs to
        boolean resolved;
        boolean passed;
        boolean collected;
        boolean skipped;       // on the road not taken

        Gate(float at, float speed, int pickup, Fork fork, int branch) {
            this.at = at;
            this.speed = speed;
            this.pickup = pickup;
            this.fork = fork;
            this.branch = branch;
        }
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final java.util.List<Gate> gates = new java.util.ArrayList<>();
    private final java.util.List<Fork> forks = new java.util.ArrayList<>();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final CanyonRouteMap map;
    /** Road taken at each fork, for the route map. */
    private final int[] forkChoices = new int[12];
    private int forksTaken;

    private boolean started;
    private boolean over;
    private int lives;
    private int passed;
    private int score;
    private int gems;
    private int shaves;
    private int ringCount;
    private double runStart;
    private double x;
    private float altitude;      // 0..1
    private double hurtUntil;
    private float minSpeed = 1.2f;
    private float maxSpeed = 4.6f;
    /** Speed the altitude follows: a rolling average with a dead zone. */
    private float flightSpeed;
    /** What the rower has been holding lately, for placing the next ring. */
    private float recentSpeed;
    private float nextGateAt;
    private float nextForkAt;
    private final java.util.Random rng = new java.util.Random();
    private LinearGradient skyShader;
    private float skyHeight;
    private float skyT = -1f;

    /* ---------- 3.25.0 graphics: cached light, rebuilt with the sky ---------- */

    /** Scratch paths, so layered rock is a handful of multi-contour draws and nothing allocates. */
    private final Path path2 = new Path();
    private final Path path3 = new Path();
    /** Haze over the far layers, and the lit gradients of each rock layer. */
    private LinearGradient hazeFar;
    private LinearGradient hazeNear;
    private LinearGradient farLitShader;
    private LinearGradient mesaShader;
    private LinearGradient nearLitShader;
    private LinearGradient nearShadowShader;
    private LinearGradient roofShader;
    private LinearGradient floorShader;
    private int rangeCol;
    private int farShadowCol;
    private int mesaShadowCol;
    private int mesaHiCol;
    private int nearRimCol;
    private int floorRimCol;
    private int roofRimCol;
    private int strataDark;
    private int strataLight;
    /** The planes' fuselage shading, one per livery, built once. */
    private LinearGradient planeShaderYou;
    private LinearGradient planeShaderRival;
    /** The boost meter's panel. */
    private LinearGradient panelShader;
    private float panelTop = -1f;
    private float panelBottom = -1f;
    /** How much bigger the hero plane is drawn than the 3.24 one; the rival is a little smaller. */
    private static final float YOU_SCALE = 1.55f;
    private static final float RIVAL_SCALE = 1.2f;
    private int streak;
    private double popupUntil;
    private float popupY;
    private String popupText = "+1";
    private int popupColor = 0xFFF5C518;
    private double bannerUntil;
    private String bannerText = "";
    private int bannerColor = TEXT;
    private int lastPhase;
    private float lastYouY;
    private float pitch;
    private float scrapeTime;
    /** Which way the rock being touched is: true roof or spire above, false floor or spire below. */
    private boolean scrapeHigh;
    private double graceUntil;
    private double sparkClock;
    /** Screen rows of the sky, set each frame. */
    private float skyTopY;
    private float skyBottomY;
    /** Output of {@link #corridor}: walls in speed units at one distance. */
    private float cFloor;
    private float cCeil;
    /** Spires at this distance, ascending in speed: one normally, two when the fork has a slot. */
    private final float[] rockLo = new float[2];
    private final float[] rockHi = new float[2];
    private int rockCount;
    private Fork cFork;

    /* ---------- the race ---------- */

    /** Route tiers this rower's best score has opened: 1 slot road, 2 turbo, 3 night run. */
    private int tier;
    /**
     * The score {@link #tier} was decided from, read once at the start of the run. The route map
     * is drawn from this and not from the live score: a point earned mid-run does not turn a
     * spire into a slot until the next run, so a padlock that opened on screen would be a lie.
     */
    private float unlockScore;
    private float prizeMul = 1f;
    /** Metres of daylight the run starts with, so a night run finishes under stars. */
    private float dayOffset;

    /** Timing gates. */
    private float nextCheckAt;
    private int checkIndex;
    private double lastCheckSeconds;
    private int golds;
    private int silvers;
    private int bronzes;

    /** Boost, earned by clean ring passes. */
    private int boostCharges;
    private int boostCapacity = 3;
    private double boostUntil;
    private double boostFullSince;
    private double boostMetres;
    private float boostGlow;
    /** Tap target for the boost meter, set when it is drawn. */
    private float boostHitX;
    private float boostHitY;
    private float boostHitW;
    private float boostHitH;

    /** The rival plane. */
    private double rivalX;
    private float rivalAlt;
    private float rivalSpeed;
    private double rivalSurgeUntil;
    private double rivalNextSurge;
    private String rivalTaunt = "";
    private double rivalTauntUntil;
    private boolean rivalWasAhead;
    private int tauntSeq;

    /** The finish. */
    private boolean finished;
    private double finishSeconds;
    private float finishMargin;
    private int finishMedal = CanyonRouteMap.NO_MEDAL;
    private double finishAt;
    private boolean photoFinish;
    /**
     * The finish card's text, built once when the run ends. It is a still picture the rower can
     * sit on for minutes; formatting it every frame would allocate a Formatter 60 times a second
     * for text that cannot change.
     */
    private String finishTitle = "";
    private String finishTimeText = "";
    private String finishLine1 = "";
    private String finishLine2 = "";
    /** The gold split for one checkpoint: fixed for the run, so it is formatted once. */
    private String goldSplitText = "";

    CanyonFlightGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.map = new CanyonRouteMap(context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        finished = false;
        streak = 0;
        lives = LIVES;
        passed = 0;
        score = 0;
        gems = 0;
        shaves = 0;
        ringCount = 0;
        x = 0;
        altitude = 0f;
        scrapeTime = 0f;
        graceUntil = 0;
        lastPhase = 0;
        bannerUntil = 0;
        gates.clear();
        forks.clear();
        forksTaken = 0;
        for (int i = 0; i < forkChoices.length; i++) {
            forkChoices[i] = -1;
        }
        // Branches open with the rower's best score, so the canyon grows as they get better.
        unlockScore = bests.get("canyon.score", 0f);
        tier = CanyonRouteMap.tiers(unlockScore);
        boostCapacity = tier >= 2 ? 4 : 3;
        prizeMul = tier >= 3 ? 2f : 1f;
        dayOffset = tier >= 3 ? 760f : 0f;
        boostCharges = 0;
        boostUntil = 0;
        boostFullSince = 0;
        boostMetres = 0;
        boostGlow = 0f;
        nextCheckAt = CHECK_METRES;
        checkIndex = 0;
        lastCheckSeconds = 0;
        golds = 0;
        silvers = 0;
        bronzes = 0;
        rivalX = -40;
        rivalAlt = 0.5f;
        rivalSpeed = (float) profile.typicalSpeed();
        rivalSurgeUntil = 0;
        rivalNextSurge = 18;
        rivalTauntUntil = 0;
        rivalWasAhead = false;
        tauntSeq = 0;
        photoFinish = false;
        finishTitle = "";
        finishTimeText = "";
        finishLine1 = "";
        finishLine2 = "";
        goldSplitText = "gold "
                + CanyonRouteMap.time(CanyonRouteMap.target(CanyonRouteMap.GOLD, CHECK_METRES, profile));
        map.collapse();
        // The altitude scale covers this rower: from well below their low speed to a little above
        // their high, so a change in effort is a visible change in height.
        minSpeed = (float) Math.max(0.8, profile.lowSpeed() - 1.2);
        maxSpeed = (float) profile.highSpeed() + 0.6f;
        flightSpeed = 0f;
        recentSpeed = (float) profile.typicalSpeed();
        nextGateAt = 80f;
        nextForkAt = 380f;
        rng.setSeed(77);
    }

    /* ---------- the canyon's shape ---------- */

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * The flyable corridor at {@code m} metres, in speed units (so it is tuned to this rower): the
     * roof and floor close from off-screen to the rower's own low/high band, and inside a fork one
     * spire - or two, with a slot between them - sits across the middle. Writes {@link #cFloor},
     * {@link #cCeil}, {@link #rockLo}, {@link #rockHi}, {@link #rockCount} and {@link #cFork} -
     * fields, so the per-column drawing allocates nothing.
     */
    private void corridor(double m) {
        float narrow = clamp01((float) (m - NARROW_START) / NARROW_SPAN);
        float lo = (float) profile.lowSpeed() - 0.3f;
        float hi = (float) profile.highSpeed() + 0.3f;
        cFloor = (minSpeed - 0.6f) + (lo - (minSpeed - 0.6f)) * narrow;
        cCeil = (maxSpeed + 0.6f) + (hi - (maxSpeed + 0.6f)) * narrow;
        cFork = null;
        rockCount = 0;
        for (int i = 0; i < forks.size(); i++) {
            Fork f = forks.get(i);
            if (m >= f.start && m <= f.end) {
                cFork = f;
                float ramp = clamp01((float) Math.min(m - f.start, f.end - m) / 25f);
                float vlo = Math.max(cFloor, minSpeed);
                float vhi = Math.min(cCeil, maxSpeed);
                float mid = (vlo + vhi) / 2f;
                float span = vhi - vlo;
                if (f.slot) {
                    // Placed so the outer roads stay where the single spire put them (2.3 and 4.1
                    // m/s for this rower's default profile) and the slot is the new, tight middle:
                    // a 0.2-0.36 m/s window at about 3.2-3.5 m/s, just under their typical pace.
                    float off = span * 0.125f;
                    float half = span * 0.062f * ramp;
                    rockLo[0] = mid - off - half;
                    rockHi[0] = mid - off + half;
                    rockLo[1] = mid + off - half;
                    rockHi[1] = mid + off + half;
                    rockCount = 2;
                } else {
                    float half = span * 0.13f * ramp;
                    rockLo[0] = mid - half;
                    rockHi[0] = mid + half;
                    rockCount = 1;
                }
                break;
            }
        }
    }

    /** Centre speed of a road through the fork {@link #corridor} last sampled. */
    private float roadSpeed(int road) {
        float vlo = Math.max(cFloor, minSpeed);
        float vhi = Math.min(cCeil, maxSpeed);
        if (rockCount == 0) {
            return (vlo + vhi) / 2f;
        }
        if (road == LOW_ROAD) {
            return (vlo + rockLo[0]) / 2f;
        }
        if (road == HIGH_ROAD) {
            return (rockHi[rockCount - 1] + vhi) / 2f;
        }
        return rockCount == 2 ? (rockHi[0] + rockLo[1]) / 2f : (vlo + vhi) / 2f;
    }

    /** Screen y of a speed, NOT clamped to the sky, so rock can sit partly off-screen. */
    private float yOf(float speed) {
        return skyBottomY - (skyBottomY - skyTopY) * (speed - minSpeed) / (maxSpeed - minSpeed);
    }

    /** Rings are made just ahead of the rower, around the speed they have been holding. */
    private void spawnGates() {
        float low = (float) profile.lowSpeed() - 0.4f;
        float high = (float) profile.highSpeed() + 0.2f;
        while (nextGateAt < x + 200f && nextGateAt < EXIT_METRES - 40f) {
            if (nextGateAt + 40f >= nextForkAt) {
                // A fork: rings on each road, placed in the middle of each branch.
                Fork f = new Fork(nextForkAt, nextForkAt + FORK_LEN, tier >= 1);
                forks.add(f);
                for (int k = 0; k < 2; k++) {
                    float at = f.start + (k == 0 ? 55f : 120f);
                    corridor(at);
                    gates.add(new Gate(at, roadSpeed(HIGH_ROAD), STAR, f, HIGH_ROAD));
                    gates.add(new Gate(at, roadSpeed(LOW_ROAD), k == 0 ? GEM : HEART, f, LOW_ROAD));
                    if (f.slot) {
                        gates.add(new Gate(at, roadSpeed(SLOT_ROAD), STAR, f, SLOT_ROAD));
                    }
                }
                nextGateAt = f.end + 60f + rng.nextFloat() * 40f;
                nextForkAt = nextGateAt + 420f + rng.nextFloat() * 200f;
                continue;
            }
            float centre = recentSpeed + ((float) profile.typicalSpeed() - recentSpeed) * 0.1f;
            float target = centre + (rng.nextFloat() - 0.5f) * 0.8f;
            target = Math.max(Math.max(minSpeed + 0.3f, low), Math.min(Math.min(maxSpeed - 0.3f, high), target));
            corridor(nextGateAt);
            float cLo = cFloor + 0.3f;
            float cHi = cCeil - 0.3f;
            if (cLo < cHi) {
                target = Math.max(cLo, Math.min(cHi, target));
            }
            ringCount++;
            gates.add(new Gate(nextGateAt, target, ringCount % 6 == 0 ? HEART : GEM, null, -1));
            nextGateAt += 90f + rng.nextFloat() * 60f;
        }
        // Forget rings well behind, so the list does not grow for a whole session.
        while (gates.size() > 12 && gates.get(0).resolved && gates.get(0).at < x - 80f) {
            gates.remove(0);
        }
        while (!forks.isEmpty() && forks.get(0).end < x - 80f) {
            forks.remove(0);
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
            runStart = sessionMeters;
            // Rings start from the speed actually being rowed, not the profile's typical: on the tablet
            // the first ring sat at 4.0 m/s against a 2.6 m/s row.
            recentSpeed = Math.max(boat.value(), (float) profile.lowSpeed() - 0.3f);
            gates.clear();
            forks.clear();
            ringCount = 0;
            boostMetres = 0;
            nextGateAt = (float) (sessionMeters - runStart) + 80f;
            nextForkAt = nextGateAt + 300f;
            rivalX = -40;
            rivalSpeed = (float) profile.typicalSpeed();
            rivalNextSurge = sessionSeconds + 18;
            lastCheckSeconds = activeSeconds;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            if (over || finished) {
                start();
                return true;
            }
            float tx = e.getX();
            float ty = e.getY();
            if (map.tap(tx, ty)) {
                invalidate();
                return true;
            }
            if (boostHitW > 0 && tx >= boostHitX - dp(10f) && tx <= boostHitX + boostHitW + dp(10f)
                    && ty >= boostHitY - dp(14f) && ty <= boostHitY + boostHitH + dp(14f)) {
                fireBoost();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private void popup(String text, int color, float y) {
        popupText = text;
        popupColor = color;
        popupY = y;
        popupUntil = sessionSeconds + 1.1;
    }

    private void banner(String text, int color) {
        bannerText = text;
        bannerColor = color;
        bannerUntil = sessionSeconds + 2.4;
    }

    private void loseLife(float youX, float youY) {
        lives--;
        streak = 0;
        boostCharges = Math.max(0, boostCharges - 1);
        hurtUntil = sessionSeconds + 1.2;
        shake.kick(dp(10f));
        fx.burst(youX, youY, 24, dp(160f), 0.6f, dp(3.5f), 0xFFF0655D, false);
        if (lives <= 0) {
            over = true;
            saveRecords();
        }
    }

    private void saveRecords() {
        bests.recordHighest("canyon.gates", passed);
        bests.recordHighest("canyon.score", score);
        bests.recordHighest("canyon.gold", golds);
    }

    /* ---------- boost ---------- */

    /** Spend every charge at once: thrust, real metres down the canyon and rock immunity. */
    private void fireBoost() {
        if (boostCharges <= 0 || sessionSeconds < boostUntil || !started || over || finished) {
            return;
        }
        float seconds = BOOST_PER_CHARGE * boostCharges;
        boostUntil = sessionSeconds + seconds;
        graceUntil = Math.max(graceUntil, boostUntil);
        scrapeTime = 0f;
        boostCharges = 0;
        boostFullSince = 0;
        shake.kick(dp(6f));
        banner("BOOST", 0xFFFF8A3D);
    }

    private float boostSpeed() {
        return (float) profile.typicalSpeed() * (tier >= 2 ? 1.5f : 1.1f);
    }

    private boolean boosting() {
        return sessionSeconds < boostUntil;
    }

    /* ---------- drawing ---------- */

    /** A spinning pickup at the heart of a ring. */
    private void drawPickup(Canvas c, float px, float py, int kind, float phase) {
        float s = dp(1f);
        float spin = Math.max(0.25f, Math.abs((float) Math.cos(sessionSeconds * 2.6 + phase)));
        float bob = (float) Math.sin(sessionSeconds * 3 + phase) * dp(3f);
        py += bob;
        paint.setStyle(Paint.Style.FILL);
        if (kind == GEM) {
            Fx.glow(c, px, py, dp(28f), 0x6635D0BA);
            path.reset();
            path.moveTo(px, py - 13 * s);
            path.lineTo(px + 10 * s * spin, py - 3 * s);
            path.lineTo(px, py + 13 * s);
            path.lineTo(px - 10 * s * spin, py - 3 * s);
            path.close();
            paint.setColor(0xFF4FE3D0);
            c.drawPath(path, paint);
            paint.setColor(0xAAFFFFFF);
            c.drawRect(px - 2 * s * spin, py - 9 * s, px + 2 * s * spin, py - 2 * s, paint);
        } else if (kind == HEART) {
            Fx.glow(c, px, py, dp(28f), 0x66F0655D);
            float r = 6 * s;
            float sx = spin;
            paint.setColor(0xFFF0655D);
            c.drawCircle(px - r * 0.9f * sx, py - r * 0.4f, r, paint);
            c.drawCircle(px + r * 0.9f * sx, py - r * 0.4f, r, paint);
            path.reset();
            path.moveTo(px - r * 1.85f * sx, py - r * 0.1f);
            path.lineTo(px + r * 1.85f * sx, py - r * 0.1f);
            path.lineTo(px, py + r * 2.1f);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0x88FFFFFF);
            c.drawCircle(px - r * 1.1f * sx, py - r * 0.7f, r * 0.3f, paint);
        } else if (kind == STAR) {
            Fx.glow(c, px, py, dp(34f), 0x77F5C518);
            path.reset();
            float outer = 15 * s;
            float inner = 6.5f * s;
            for (int k = 0; k < 10; k++) {
                double a = -Math.PI / 2 + k * Math.PI / 5;
                float rr = (k % 2 == 0) ? outer : inner;
                float vx = px + (float) Math.cos(a) * rr * spin;
                float vy = py + (float) Math.sin(a) * rr;
                if (k == 0) {
                    path.moveTo(vx, vy);
                } else {
                    path.lineTo(vx, vy);
                }
            }
            path.close();
            paint.setColor(0xFFF5C518);
            c.drawPath(path, paint);
            paint.setColor(0xAAFFFFFF);
            c.drawCircle(px - 3 * s * spin, py - 3 * s, 2.5f * s, paint);
        }
    }

    /** Half of every ring on screen: back halves before the plane, front halves after it. */
    private void drawGates(Canvas c, float w, float youX, float ppm, float band, boolean front) {
        for (Gate g : gates) {
            float gx = youX + (g.at - (float) x) * ppm;
            if (gx < -dp(60f) || gx > w + dp(60f)) {
                continue;
            }
            float gy = skyBottomY - (skyBottomY - skyTopY) * altFor(g.speed);
            int col = g.skipped ? 0x44FFFFFF
                    : g.resolved ? (g.passed ? ACCENT : BAD)
                    : g.pickup == STAR ? 0xFFFFB02E : 0xFFF5C518;
            float rx = dp(22f);
            if (!front) {
                if (!g.resolved) {
                    Fx.glow(c, gx, gy, band * 1.4f, 0x55F5C518);
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(7f));
                paint.setColor((col & 0x00FFFFFF) | (g.skipped ? 0x22000000 : 0x99000000));
                c.drawArc(gx - rx, gy - band, gx + rx, gy + band, 90, 180, false, paint);
                paint.setStyle(Paint.Style.FILL);
                // The prize sits inside the ring, between its back and front halves.
                if (!g.collected && !g.skipped && g.pickup != NONE) {
                    drawPickup(c, gx, gy, g.pickup, g.at);
                }
            } else {
                if (g.skipped) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(6f));
                    paint.setColor(col);
                    c.drawArc(gx - rx, gy - band, gx + rx, gy + band, -90, 180, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    continue;
                }
                paint.setStyle(Paint.Style.STROKE);
                // A dark keyline under the ring, so it separates from sunlit rock behind it.
                paint.setStrokeWidth(dp(12f));
                paint.setColor(0x55140A14);
                c.drawArc(gx - rx, gy - band, gx + rx, gy + band, -90, 180, false, paint);
                paint.setStrokeWidth(dp(8f));
                paint.setColor(col);
                c.drawArc(gx - rx, gy - band, gx + rx, gy + band, -90, 180, false, paint);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(0xCCFFFFFF);
                c.drawArc(gx - rx + dp(3f), gy - band + dp(3f), gx + rx - dp(3f), gy + band - dp(3f), -80, 160, false, paint);
                paint.setStyle(Paint.Style.FILL);
                // Pennants top and bottom.
                paint.setColor(col);
                c.drawRect(gx - dp(1.5f), gy - band - dp(22f), gx + dp(1.5f), gy - band, paint);
                float flap = (float) Math.sin(sessionSeconds * 6 + g.at) * dp(3f);
                path.reset();
                path.moveTo(gx + dp(1.5f), gy - band - dp(22f));
                path.lineTo(gx + dp(20f), gy - band - dp(17f) + flap);
                path.lineTo(gx + dp(1.5f), gy - band - dp(12f));
                path.close();
                c.drawPath(path, paint);
                if (!g.resolved) {
                    bold(c, String.format(java.util.Locale.US, "%.1f m/s", g.speed), gx, gy - band - dp(28f),
                            11f, TEXT, Paint.Align.CENTER);
                }
            }
        }
    }

    /**
     * The narrowing canyon: a rock roof and a rock floor, following {@link #corridor}, jagged by
     * a hash of world position so the rock stays put as you fly past it.
     */
    private void drawCorridor(Canvas c, float w, float h, float youX, float ppm, float dusk) {
        float stepPx = dp(36f);
        float stepM = stepPx / ppm;
        double left = x - youX / ppm - stepM;
        double right = x + (w - youX) / ppm + stepM;
        long k0 = (long) Math.floor(left / stepM);
        for (int side = 0; side < 2; side++) {
            path.reset();
            float edgeY = side == 0 ? -dp(40f) : h + dp(40f);
            path.moveTo(-dp(40f), edgeY);
            boolean visible = false;
            for (long k = k0; k * stepM <= right; k++) {
                double m = k * stepM;
                corridor(m);
                float px = youX + (float) (m - x) * ppm;
                int hash = (int) ((k * 2654435761L) >>> 7) & 7;
                float jag = hash * dp(2f);
                float py = side == 0 ? yOf(cCeil) + jag : yOf(cFloor) - jag;
                if (side == 0 ? py > 0 : py < h) {
                    visible = true;
                }
                path.lineTo(px, py);
            }
            path.lineTo(w + dp(40f), edgeY);
            path.close();
            if (!visible) {
                continue;
            }
            // The floor is lit from above along its edge; the roof only catches warm light bounced
            // up off the sunlit floor. Both carry the same level strata as the scenery.
            drawRockBody(c, w, h, side == 0 ? roofShader : floorShader, side == 0 ? roofRimCol : floorRimCol,
                    side == 0 ? 0f : h * 0.5f);
        }
    }

    /**
     * Fills {@link #path} as solid rock: a lit gradient, level strata and a lit band just inside
     * its edge. The edge band is a wide stroke clipped to the rock, so only its inner half shows -
     * one clip, four draws, no layer.
     */
    private void drawRockBody(Canvas c, float w, float h, LinearGradient body, int rim, float strataFrom) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(body);
        c.drawPath(path, paint);
        paint.setShader(null);
        c.save();
        c.clipPath(path);
        for (int j = 0; j < 7; j++) {
            float sy = strataFrom + h * 0.07f * j + dp(8f);
            paint.setColor((j & 1) == 0 ? strataDark : strataLight);
            c.drawRect(-dp(40f), sy, w + dp(40f), sy + dp(5f + (j % 3) * 4f), paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(16f));
        paint.setColor((rim & 0x00FFFFFF) | 0x66000000);
        c.drawPath(path, paint);
        paint.setStrokeWidth(dp(5f));
        paint.setColor(rim);
        c.drawPath(path, paint);
        paint.setStrokeJoin(Paint.Join.MITER);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** The spire (or the pair of them) that splits a fork, and the road signs at its mouth. */
    private void drawForks(Canvas c, float w, float youX, float ppm, float dusk) {
        double left = x - youX / ppm;
        double right = x + (w - youX) / ppm;
        for (int i = 0; i < forks.size(); i++) {
            Fork f = forks.get(i);
            if (f.end < left || f.start > right + 40) {
                continue;
            }
            // Sample points snapped to a world grid, so the jag pattern stays on the rock instead
            // of crawling with the camera.
            double step = 3.0;
            double a = Math.max(f.start, Math.floor((left - 4) / step) * step);
            double b = Math.min(f.end, Math.ceil((right + 4) / step) * step);
            int bands = f.slot ? 2 : 1;
            for (int band = 0; band < bands && b > a; band++) {
                path.reset();
                // Top edge forward, bottom edge back.
                boolean first = true;
                for (double m = a; m <= b + 0.001; m += step) {
                    corridor(m);
                    if (rockCount <= band) {
                        continue;
                    }
                    int hash = (int) ((Math.round(m / step) * 40503L) >>> 3) & 3;
                    float px = youX + (float) (m - x) * ppm;
                    float py = yOf(rockHi[band]) - hash * dp(2f);
                    if (first) {
                        path.moveTo(px, py);
                        first = false;
                    } else {
                        path.lineTo(px, py);
                    }
                }
                if (first) {
                    continue;
                }
                for (double m = b; m >= a - 0.001; m -= step) {
                    corridor(m);
                    if (rockCount <= band) {
                        continue;
                    }
                    int hash = (int) ((Math.round(m / step) * 7919L) >>> 3) & 3;
                    path.lineTo(youX + (float) (m - x) * ppm, yOf(rockLo[band]) + hash * dp(2f));
                }
                path.close();
                drawRockBody(c, w, getHeight(), floorShader, floorRimCol, skyTopY);
            }
            if (f.chosen < 0) {
                // Signs at the fork mouth, one per road.
                float sx = youX + (f.start - 6f - (float) x) * ppm;
                corridor(f.start + 60f);
                float upY = yOf(roadSpeed(HIGH_ROAD));
                float lowY = yOf(roadSpeed(LOW_ROAD));
                paint.setColor(0xFF5A3A28);
                c.drawRect(sx - dp(2f), upY, sx + dp(2f), lowY, paint);
                signBoard(c, sx, upY, "HIGH ROAD", "2 STARS  ·  +5 each", 0xFFF5C518);
                signBoard(c, sx, lowY, "LOW ROAD", "easy  ·  gem + heart", ACCENT);
                if (f.slot) {
                    signBoard(c, sx, yOf(roadSpeed(SLOT_ROAD)), "SLOT", "tight  ·  2 stars  ·  +8 each", 0xFFFF8A3D);
                } else {
                    paint.setColor(0xCCF5C518);
                    c.drawCircle(sx, yOf((rockLo[0] + rockHi[0]) / 2f), dp(4f), paint);
                }
            }
        }
    }

    private void signBoard(Canvas c, float sx, float sy, String title, String sub, int col) {
        float bw = dp(128f);
        float bh = dp(36f);
        // A soft drop shadow, so the board stands off the rock behind it.
        paint.setColor(0x44000000);
        c.drawRoundRect(sx - bw / 2f + dp(3f), sy - bh / 2f + dp(4f), sx + bw / 2f + dp(3f), sy + bh / 2f + dp(4f),
                dp(5f), dp(5f), paint);
        paint.setColor(0xEE1A1016);
        c.drawRoundRect(sx - bw / 2f, sy - bh / 2f, sx + bw / 2f, sy + bh / 2f, dp(5f), dp(5f), paint);
        paint.setColor(0x22FFFFFF);
        c.drawRect(sx - bw / 2f + dp(5f), sy - bh / 2f + dp(1f), sx + bw / 2f - dp(5f), sy - bh / 2f + dp(2f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(col);
        c.drawRoundRect(sx - bw / 2f, sy - bh / 2f, sx + bw / 2f, sy + bh / 2f, dp(5f), dp(5f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, title, sx, sy - dp(2f), 12f, col, Paint.Align.CENTER);
        label(c, sub, sx, sy + dp(12f), 8.5f, TEXT, Paint.Align.CENTER);
    }

    /**
     * The timing gates: a lit arch across the canyon every {@link #CHECK_METRES}, carrying the
     * split you have to beat for gold, and the finish arch at the canyon exit.
     */
    private void drawCheckpoints(Canvas c, float w, float h, float youX, float ppm) {
        for (int i = 1; i * CHECK_METRES <= EXIT_METRES; i++) {
            float at = i * CHECK_METRES;
            boolean exit = at >= EXIT_METRES - 0.5f;
            float gx = youX + (at - (float) x) * ppm;
            if (gx < -dp(80f) || gx > w + dp(120f)) {
                continue;
            }
            corridor(at);
            float top = Math.max(-dp(20f), yOf(cCeil));
            float bot = Math.min(h + dp(20f), yOf(cFloor));
            boolean done = at <= x;
            int col = exit ? 0xFFFFFFFF : done ? 0x5535D0BA : 0xFFF5C518;
            paint.setStyle(Paint.Style.FILL);
            // A light curtain you fly through.
            paint.setColor((col & 0x00FFFFFF) | (done ? 0x11000000 : 0x33000000));
            c.drawRect(gx - dp(4f), top, gx + dp(4f), bot, paint);
            // Posts.
            paint.setColor(col);
            c.drawRect(gx - dp(7f), top, gx + dp(7f), top + dp(16f), paint);
            c.drawRect(gx - dp(7f), bot - dp(16f), gx + dp(7f), bot, paint);
            if (exit) {
                // Chequered finish banner.
                for (int q = 0; q < 10; q++) {
                    paint.setColor((q % 2 == 0) ? 0xFFF2F2EE : 0xFF1B1526);
                    float qy = top + dp(18f) + q * dp(11f);
                    c.drawRect(gx - dp(11f), qy, gx + dp(11f), qy + dp(11f), paint);
                }
                Fx.glow(c, gx, (top + bot) / 2f, dp(120f), 0x55FFFFFF);
                bold(c, "CANYON EXIT", gx, top + dp(10f), 13f, 0xFFFFFFFF, Paint.Align.CENTER);
            } else if (!done) {
                bold(c, "CP " + i, gx, top + dp(32f), 12f, col, Paint.Align.CENTER);
                label(c, goldSplitText, gx, top + dp(46f), 8.5f, 0xFFFFD36A, Paint.Align.CENTER);
            }
        }
    }

    /**
     * A high-wing plane pointing right, pitched with the climb. 3.25.0: drawn in its own
     * coordinates and scaled, so it can be the hero of the frame - a shaded, tapered fuselage
     * (light from above), a wing with a lit leading edge, a glazed cabin with a glint, fixed gear,
     * a spinner and a translucent propeller disc with a blade blur that turns with speed.
     */
    private void drawPlane(Canvas c, float px, float py, float speed, float roll, int body, int trim,
                           float scale, boolean you) {
        float s = dp(1f);
        LinearGradient shade = you ? planeShaderYou : planeShaderRival;
        if (shade == null) {
            // Built once per livery: the colours never change, and the canvas scale moves it.
            shade = new LinearGradient(0, -8.5f * s, 0, 8f * s,
                    new int[] {Daylight.blend(body, 0xFFFFFFFF, 0.5f), body,
                            Daylight.blend(body, 0xFF000000, 0.25f), Daylight.blend(body, 0xFF1A1020, 0.55f)},
                    new float[] {0f, 0.32f, 0.7f, 1f}, Shader.TileMode.CLAMP);
            if (you) {
                planeShaderYou = shade;
            } else {
                planeShaderRival = shade;
            }
        }
        int dark = Daylight.blend(body, 0xFF1A1020, 0.45f);
        c.save();
        c.translate(px, py);
        c.rotate((float) Math.toDegrees(-roll * 0.6f));
        c.scale(scale, scale);
        paint.setStyle(Paint.Style.FILL);
        // Far-side tailplane, behind the fuselage.
        paint.setColor(dark);
        c.drawRoundRect(-45 * s, -3 * s, -27 * s, 1 * s, 2 * s, 2 * s, paint);
        // Fin, with a trim-coloured tip.
        path.rewind();
        path.moveTo(-26 * s, -6 * s);
        path.lineTo(-38 * s, -24 * s);
        path.lineTo(-45 * s, -24 * s);
        path.lineTo(-42 * s, -3 * s);
        path.close();
        paint.setColor(Daylight.blend(body, 0xFF000000, 0.12f));
        c.drawPath(path, paint);
        path.rewind();
        path.moveTo(-35.3f * s, -20 * s);
        path.lineTo(-38 * s, -24 * s);
        path.lineTo(-45 * s, -24 * s);
        path.lineTo(-44.4f * s, -20 * s);
        path.close();
        paint.setColor(trim);
        c.drawPath(path, paint);
        // Fuselage: round nose, tapering to the tail, shaded top to bottom.
        path.rewind();
        path.moveTo(31 * s, 0);
        path.cubicTo(31 * s, -5 * s, 27 * s, -8 * s, 21 * s, -8 * s);
        path.lineTo(-4 * s, -8.5f * s);
        path.lineTo(-42 * s, -4 * s);
        path.lineTo(-44 * s, -1 * s);
        path.lineTo(-40 * s, 2 * s);
        path.lineTo(-4 * s, 8 * s);
        path.lineTo(21 * s, 8 * s);
        path.cubicTo(27 * s, 8 * s, 31 * s, 5 * s, 31 * s, 0);
        path.close();
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(shade);
        c.drawPath(path, paint);
        paint.setShader(null);
        // Cheat line down the side.
        path.rewind();
        path.moveTo(28 * s, -1 * s);
        path.lineTo(-40 * s, -1.8f * s);
        path.lineTo(-40 * s, 0.2f * s);
        path.lineTo(28 * s, 2.4f * s);
        path.close();
        paint.setColor(trim);
        c.drawPath(path, paint);
        // Engine cowling: the nose, a shade darker, with a panel seam.
        path.rewind();
        path.moveTo(31 * s, 0);
        path.cubicTo(31 * s, -5 * s, 27 * s, -8 * s, 21 * s, -8 * s);
        path.lineTo(21 * s, 8 * s);
        path.cubicTo(27 * s, 8 * s, 31 * s, 5 * s, 31 * s, 0);
        path.close();
        paint.setColor(0x40000000);
        c.drawPath(path, paint);
        // Cabin glazing with a glint.
        path.rewind();
        path.moveTo(4 * s, -8.3f * s);
        path.lineTo(15 * s, -8.1f * s);
        path.lineTo(20 * s, -2.5f * s);
        path.lineTo(4 * s, -2.5f * s);
        path.close();
        paint.setColor(0xFF27445E);
        c.drawPath(path, paint);
        path.rewind();
        path.moveTo(6 * s, -7.6f * s);
        path.lineTo(9.5f * s, -7.5f * s);
        path.lineTo(7.5f * s, -3.4f * s);
        path.lineTo(5 * s, -3.4f * s);
        path.close();
        paint.setColor(0x99FFFFFF);
        c.drawPath(path, paint);
        paint.setColor(dark);
        c.drawRect(11 * s, -8.2f * s, 12.2f * s, -2.5f * s, paint);
        // High wing, edge-on: lit leading edge on top, shadowed underside.
        paint.setColor(Daylight.blend(body, 0xFF000000, 0.06f));
        c.drawRoundRect(-14 * s, -13.5f * s, 16 * s, -8 * s, 3 * s, 3 * s, paint);
        paint.setColor(0x99FFFFFF);
        c.drawRect(-12 * s, -13.5f * s, 14 * s, -12.3f * s, paint);
        paint.setColor(0x38000000);
        c.drawRect(-13 * s, -9.8f * s, 15 * s, -8 * s, paint);
        // Strut and fixed gear.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(1.4f * s);
        paint.setColor(dark);
        c.drawLine(4 * s, -9 * s, -3 * s, 5 * s, paint);
        paint.setStrokeWidth(2 * s);
        c.drawLine(12 * s, 7 * s, 15 * s, 14 * s, paint);
        // Rim light along the top of the fuselage.
        paint.setStrokeWidth(1.2f * s);
        paint.setColor(0x99FFFFFF);
        c.drawLine(20 * s, -7.4f * s, -4 * s, -7.9f * s, paint);
        c.drawLine(-4 * s, -7.9f * s, -40 * s, -3.6f * s, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1E1A22);
        c.drawCircle(15 * s, 16 * s, 3.6f * s, paint);
        c.drawCircle(-40 * s, 3.8f * s, 1.6f * s, paint);
        paint.setColor(0xFFB8B8C0);
        c.drawCircle(15 * s, 16 * s, 1.3f * s, paint);
        // Propeller: a translucent disc, a blade blur that swings with the spin, and the spinner.
        paint.setColor(0x30FFFFFF);
        c.drawOval(33.5f * s, -19 * s, 36.5f * s, 19 * s, paint);
        float blade = (float) Math.abs(Math.sin(sessionSeconds * (20 + speed * 10))) * 17 * s + 2 * s;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f * s);
        paint.setColor(0x99E8E8F0);
        c.drawLine(35 * s, -blade, 35 * s, blade, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        path.rewind();
        path.moveTo(31 * s, -3.6f * s);
        path.quadTo(38.5f * s, -1.4f * s, 38.5f * s, 0);
        path.quadTo(38.5f * s, 1.4f * s, 31 * s, 3.6f * s);
        path.close();
        paint.setColor(0xFF3A3F4A);
        c.drawPath(path, paint);
        paint.setColor(0x88FFFFFF);
        c.drawCircle(34 * s, -1.5f * s, 0.9f * s, paint);
        c.restore();
    }

    /**
     * The plane's shadow on whatever is under it - the canyon floor, a fork spire, or the near
     * wall - sharper and darker the lower you fly, and thrown left because the sun is high on the
     * right. Drawing only: nothing here is read by the rock or ring tests.
     */
    private void drawPlaneShadow(Canvas c, float h, float youX, float youY, float ppm) {
        corridor(x);
        float ground = Math.min(yOf(cFloor), nearWallY(youX, h, ppm));
        for (int i = 0; i < rockCount; i++) {
            float top = yOf(rockHi[i]);
            if (top > youY && top < ground) {
                ground = top;
            }
        }
        ground = Math.min(ground, h - dp(6f));
        float dist = ground - youY;
        if (dist < dp(26f)) {
            return;
        }
        float near = clamp01(1f - dist / (h * 0.75f));
        float sw = dp(52f) * YOU_SCALE * (1.3f - 0.45f * near);
        float sh = sw * 0.12f;
        float sx = youX - dist * 0.18f;
        int a = (int) (26 + 80 * near);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(((a / 2) << 24) | 0x1A0810);
        c.drawOval(sx - sw * 1.2f, ground - sh * 1.5f, sx + sw * 1.2f, ground + sh * 1.5f, paint);
        paint.setColor((a << 24) | 0x1A0810);
        c.drawOval(sx - sw * 0.8f, ground - sh, sx + sw * 0.8f, ground + sh, paint);
    }

    /**
     * The rival: its own plane in the canyon when it is on screen, an edge chevron with the gap
     * when it is not, and a speech bubble whenever it has something to say.
     */
    private void drawRival(Canvas c, float w, float youX, float ppm) {
        if (!started) {
            // It has not left the line yet; an edge chevron reading "40 m" before the rower has
            // taken a stroke is just noise.
            return;
        }
        float rx = youX + (float) (rivalX - x) * ppm;
        float ry = skyBottomY - (skyBottomY - skyTopY) * rivalAlt;
        boolean onScreen = rx > -dp(60f) && rx < w + dp(60f);
        if (onScreen) {
            Fx.glow(c, rx, ry, dp(58f), 0x44FF6FA8);
            // Contrail, from its tail.
            float tail = dp(48f) * RIVAL_SCALE;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(3.5f));
            paint.setColor(0x66FF9ABF);
            c.drawLine(rx - tail, ry, rx - tail - rivalSpeed * dp(18f), ry + dp(2f), paint);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(0x44FFFFFF);
            c.drawLine(rx - tail, ry - dp(1f), rx - tail - rivalSpeed * dp(12f), ry, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
            drawPlane(c, rx, ry, rivalSpeed, 0f, 0xFFE8557F, 0xFF2A1220, RIVAL_SCALE, false);
        } else {
            // Off screen: a chevron on the edge it went out of, with the gap.
            boolean ahead = rivalX > x;
            float ex = ahead ? w - dp(26f) : dp(26f);
            float ey = Math.max(skyTopY + dp(30f), Math.min(skyBottomY - dp(30f), ry));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFE8557F);
            path.reset();
            float dir = ahead ? 1f : -1f;
            path.moveTo(ex + dir * dp(16f), ey);
            path.lineTo(ex - dir * dp(10f), ey - dp(13f));
            path.lineTo(ex - dir * dp(10f), ey + dp(13f));
            path.close();
            c.drawPath(path, paint);
            bold(c, Math.round(Math.abs(rivalX - x)) + " m", ex, ey + dp(30f), 11f, 0xFFE8557F, Paint.Align.CENTER);
        }
        if (sessionSeconds < rivalTauntUntil) {
            float bx = onScreen ? rx : (rivalX > x ? w - dp(120f) : dp(120f));
            float by = onScreen ? ry - dp(56f) : skyTopY + dp(70f);
            paint.setColor(0xEE1A1016);
            paint.setTextSize(dp(11f));
            float tw = paint.measureText(rivalTaunt) + dp(22f);
            c.drawRoundRect(bx - tw / 2f, by - dp(15f), bx + tw / 2f, by + dp(9f), dp(11f), dp(11f), paint);
            path.reset();
            path.moveTo(bx - dp(6f), by + dp(8f));
            path.lineTo(bx + dp(4f), by + dp(8f));
            path.lineTo(bx - dp(1f), by + dp(19f));
            path.close();
            c.drawPath(path, paint);
            bold(c, rivalTaunt, bx, by + dp(2f), 11f, 0xFFFF9ABF, Paint.Align.CENTER);
        }
    }

    /** The boost meter: charges you earned, and what happens when you spend them. */
    private void drawBoostMeter(Canvas c, float w, float h) {
        float bw = dp(168f);
        float bh = dp(16f);
        float bx = dp(16f);
        float by = h - dp(66f);
        boostHitX = bx;
        boostHitY = by;
        boostHitW = bw;
        boostHitH = bh;
        panel(c, bx - dp(8f), by - dp(16f), bx + bw + dp(8f), by + bh + dp(8f));
        float cell = (bw - dp(4f) * (boostCapacity - 1)) / boostCapacity;
        boolean burning = boosting();
        for (int i = 0; i < boostCapacity; i++) {
            float cx = bx + i * (cell + dp(4f));
            boolean lit = i < boostCharges;
            if (lit) {
                Fx.glow(c, cx + cell / 2f, by + bh / 2f, dp(24f), 0x55FF8A3D);
            }
            paint.setColor(lit ? 0xFFFF8A3D : 0xFF151D29);
            c.drawRoundRect(cx, by, cx + cell, by + bh, dp(4f), dp(4f), paint);
            // Gloss on a lit cell, a recessed edge on an empty one.
            paint.setColor(lit ? 0x55FFFFFF : 0x22000000);
            c.drawRoundRect(cx + dp(1.5f), by + dp(1.5f), cx + cell - dp(1.5f), by + bh * 0.48f, dp(3f), dp(3f), paint);
            if (!lit) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1f));
                paint.setColor(0xFF243042);
                c.drawRoundRect(cx, by, cx + cell, by + bh, dp(4f), dp(4f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        if (burning) {
            float left = (float) ((boostUntil - sessionSeconds) / (BOOST_PER_CHARGE * boostCapacity));
            float fill = bw * Math.max(0f, Math.min(1f, left));
            paint.setColor(0xFFFFD36A);
            c.drawRoundRect(bx, by, bx + fill, by + bh, dp(4f), dp(4f), paint);
            paint.setColor(0x66FFFFFF);
            c.drawRoundRect(bx + dp(1.5f), by + dp(1.5f), bx + Math.max(dp(3f), fill - dp(1.5f)), by + bh * 0.48f,
                    dp(3f), dp(3f), paint);
        }
        String hint = burning ? "BOOSTING" : boostCharges > 0 ? "TAP TO BOOST" : "CLEAN RINGS CHARGE THE BOOST";
        label(c, hint, bx, by - dp(4f), 8.5f, burning ? 0xFFFFD36A : boostCharges > 0 ? 0xFFFF8A3D : FAINT,
                Paint.Align.LEFT);
    }

    /**
     * A HUD panel in the app's family: rounded, a vertical gradient, a hairline border and a 1px
     * lighter top edge. The gradient is cached by the panel's rows, which do not move.
     */
    private void panel(Canvas c, float l, float t, float r, float b) {
        if (panelShader == null || panelTop != t || panelBottom != b) {
            panelTop = t;
            panelBottom = b;
            panelShader = new LinearGradient(0, t, 0, b, 0xE8182232, 0xE80A0F17, Shader.TileMode.CLAMP);
        }
        float rad = dp(8f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(panelShader);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0xFF1C2736);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x30FFFFFF);
        c.drawRect(l + rad, t + dp(1f), r - rad, t + dp(2f), paint);
    }

    /** Sun (sinking with the route), stars at dusk, drifting clouds and a flock of birds. */
    private void drawSkyLife(Canvas c, float w, float h, float ppm, float t) {
        float stars = Daylight.stars(t);
        if (stars > 0f) {
            for (int i = 0; i < 40; i++) {
                float sx = ((i * 7919) % 1000) / 1000f * w;
                float sy = ((i * 104729) % 1000) / 1000f * h * 0.5f;
                float tw = 0.6f + 0.4f * (float) Math.sin(sessionSeconds * 2 + i);
                paint.setColor(((int) (stars * tw * 220) << 24) | 0xFFFFFF);
                c.drawCircle(sx, sy, dp(1.1f + (i % 3) * 0.5f), paint);
            }
        }
        float sunX = w * 0.78f;
        float sunY = h * (0.2f + 0.52f * Daylight.sunDrop(t));
        float sunR = dp(40f + 10f * Math.min(1f, t));
        int sun = Daylight.sun(t);
        // A wide soft bloom and a tight hot one, then the disc with a paler core.
        Fx.glow(c, sunX, sunY, dp(230f), (sun & 0x00FFFFFF) | 0x50000000);
        Fx.glow(c, sunX, sunY, dp(105f), (sun & 0x00FFFFFF) | 0x90000000);
        paint.setColor(sun);
        c.drawCircle(sunX, sunY, sunR, paint);
        paint.setColor(Daylight.blend(sun, 0xFFFFFFFF, 0.55f));
        c.drawCircle(sunX, sunY, sunR * 0.72f, paint);
        // Clouds: a shaded flat base under lit lobes, and a sun-side highlight on the crown. Opaque,
        // so the overlapping lobes do not stack into seams.
        int top = Daylight.top(t);
        int cloud = Daylight.blend(0xFFFFFFFF, 0xFFFF9A7A, Math.min(1f, t));
        int lit = Daylight.blend(Daylight.blend(cloud, top, 0.1f), top, stars * 0.6f);
        int under = Daylight.blend(Daylight.blend(cloud, top, 0.42f), top, stars * 0.6f);
        int crown = Daylight.blend(lit, Daylight.blend(sun, 0xFFFFFFFF, 0.6f), 0.6f);
        for (int i = 0; i < 6; i++) {
            float span = w + dp(400f);
            float cx = (float) (((i * 377 + 90) - x * ppm * (0.05 + i * 0.02)) % span);
            if (cx < -dp(200f)) {
                cx += span;
            }
            float cy = h * (0.12f + (i % 3) * 0.1f);
            float sc = (0.7f + (i % 3) * 0.25f) * dp(1f);
            paint.setColor(under);
            c.drawOval(cx - 72 * sc, cy - 6 * sc, cx + 72 * sc, cy + 14 * sc, paint);
            paint.setColor(lit);
            c.drawOval(cx - 62 * sc, cy - 12 * sc, cx + 62 * sc, cy + 5 * sc, paint);
            c.drawCircle(cx - 34 * sc, cy - 10 * sc, 16 * sc, paint);
            c.drawCircle(cx + 2 * sc, cy - 16 * sc, 22 * sc, paint);
            c.drawCircle(cx + 36 * sc, cy - 8 * sc, 14 * sc, paint);
            paint.setColor(crown);
            c.drawCircle(cx + 9 * sc, cy - 26 * sc, 9 * sc, paint);
            c.drawCircle(cx + 40 * sc, cy - 14 * sc, 6 * sc, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(0xCC2A1A2A);
        float flockX = (float) (w - ((x * ppm * 0.3 + sessionSeconds * dp(30f)) % (w + dp(300f))));
        for (int b = 0; b < 5; b++) {
            float bx = flockX + b * dp(26f);
            float by = h * 0.28f + (b % 2) * dp(12f) + (float) Math.sin(sessionSeconds * 1.3 + b) * dp(4f);
            float flap = (float) Math.sin(sessionSeconds * 9 + b) * dp(5f);
            c.drawLine(bx - dp(9f), by - flap, bx, by, paint);
            c.drawLine(bx, by, bx + dp(9f), by - flap, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    private static int land(int col, float dusk) {
        return Daylight.blend(col, 0xFF140A18, dusk);
    }

    /**
     * The scenery's light for one step of the day: haze, the lit gradient of every rock layer and
     * their flat colours. Called only when the quantised daylight changes, a few dozen times a run.
     * One light for the whole scene - the sun, high on the right - so every sunlit face is the
     * right-hand, down-sloping one and every shadow is violet.
     */
    private void buildLight(float h, float t) {
        float dusk = Daylight.shade(t);
        int hz = Daylight.horizon(t);
        int sun = Daylight.sun(t);
        Shader.TileMode clamp = Shader.TileMode.CLAMP;
        hazeFar = new LinearGradient(0, h * 0.46f, 0, h * 0.74f, hz & 0x00FFFFFF,
                (hz & 0x00FFFFFF) | 0x88000000, clamp);
        hazeNear = new LinearGradient(0, h * 0.58f, 0, h * 0.95f, hz & 0x00FFFFFF,
                (hz & 0x00FFFFFF) | 0x50000000, clamp);
        rangeCol = land(Daylight.blend(0xFFA0605A, hz, 0.58f), dusk);
        farShadowCol = land(Daylight.blend(0xFF6E3442, hz, 0.42f), dusk);
        int farLit = land(Daylight.blend(Daylight.blend(0xFFCC7448, sun, 0.25f), hz, 0.34f), dusk);
        farLitShader = new LinearGradient(0, h * 0.55f, 0, h * 0.98f, farLit, farShadowCol, clamp);
        int mesaTop = land(Daylight.blend(Daylight.blend(0xFFD8783E, sun, 0.3f), hz, 0.18f), dusk);
        int mesaMid = land(Daylight.blend(0xFFB04A30, hz, 0.2f), dusk);
        int mesaBase = land(Daylight.blend(0xFF6A2E3C, hz, 0.38f), dusk);
        mesaShader = new LinearGradient(0, h * 0.44f, 0, h, new int[] {mesaTop, mesaMid, mesaBase},
                new float[] {0f, 0.45f, 1f}, clamp);
        mesaShadowCol = land(Daylight.blend(0xFF55305A, hz, 0.22f), dusk);
        mesaHiCol = land(Daylight.blend(0xFFFFB878, sun, 0.35f), dusk);
        // Shadow faces still catch light bounced off the sunlit rock, most of all near the top.
        nearShadowShader = new LinearGradient(0, h * 0.45f, 0, h, land(Daylight.blend(0xFF6E3044, hz, 0.12f), dusk),
                land(0xFF2A1220, dusk), clamp);
        int nearLit = land(Daylight.blend(0xFFB8582E, sun, 0.2f), dusk);
        nearLitShader = new LinearGradient(0, h * 0.45f, 0, h, nearLit, land(0xFF4A2026, dusk), clamp);
        nearRimCol = (((int) (0xAA * (1f - dusk))) << 24) | (Daylight.blend(0xFFFFC890, sun, 0.3f) & 0x00FFFFFF);
        roofShader = new LinearGradient(0, 0, 0, h * 0.55f, land(0xFF1E0E18, dusk), land(0xFF5E2E34, dusk), clamp);
        floorShader = new LinearGradient(0, h * 0.35f, 0, h, land(0xFF9A4E34, dusk), land(0xFF26121A, dusk), clamp);
        floorRimCol = land(Daylight.blend(0xFFF0A060, sun, 0.3f), dusk);
        roofRimCol = land(0xFFB0604A, dusk);
        strataDark = 0x2A200814;
        strataLight = (((int) (0x1C * (1f - dusk))) << 24) | 0xFFE0B0;
    }

    /** A full-width haze band: everything drawn before it recedes into the horizon colour. */
    private void drawHaze(Canvas c, float w, float h, LinearGradient haze, float from) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(haze);
        c.drawRect(-dp(20f), from, w + dp(20f), h + dp(20f), paint);
        paint.setShader(null);
    }

    /** The farthest layer: a low range of buttes, mostly haze, scrolled at 0.12. */
    private void drawRange(Canvas c, float w, float h, float ppm) {
        float step = dp(170f);
        double scroll = x * ppm * 0.12;
        float off = (float) (scroll % step);
        int base = (int) Math.floor(scroll / step);
        float valley = h * 0.62f;
        path.rewind();
        path.moveTo(-off - step, h);
        path.lineTo(-off - step, valley);
        float end = -off;
        for (int n = -1; n * step - off <= w + step; n++) {
            float px = n * step - off;
            int seed = base + n;
            float top = h * (0.47f + ((seed * 2971) & 3) * 0.022f);
            path.lineTo(px + step * 0.16f, top + dp(7f));
            path.lineTo(px + step * 0.22f, top);
            path.lineTo(px + step * 0.58f, top);
            path.lineTo(px + step * 0.66f, top + dp(9f));
            path.lineTo(px + step * 0.84f, valley);
            path.lineTo(px + step, valley);
            end = px + step;
        }
        path.lineTo(end, h);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(rangeCol);
        c.drawPath(path, paint);
    }

    private static float peakY(int k, int layer, float h) {
        return h * (0.55f + ((k * 7919) % 5) * 0.08f) - layer * h * 0.1f;
    }

    /**
     * A jagged canyon wall, far (layer 0, scrolled at 0.25) or near (layer 1, at 0.6). Same
     * silhouette as 3.24; now the down-sloping face of every peak is a sunlit spur, the rest is
     * violet shadow, the ridgelines catch a rim of light and the near wall carries level strata.
     */
    private void drawWall(Canvas c, float w, float h, float ppm, int layer) {
        float step = dp(90f);
        double scroll = x * ppm * (layer == 0 ? 0.25 : 0.6);
        float off = (float) (scroll % step);
        int base = (int) Math.floor(scroll / step);
        path.rewind();
        path2.rewind();
        path3.rewind();
        path.moveTo(-off, h);
        for (int n = 0; -off + n * step <= w + step; n++) {
            float px = -off + n * step;
            float pk = peakY(base + n, layer, h);
            float vx = px + step * 0.5f;
            float vy = pk + h * 0.06f;
            path.lineTo(px, pk);
            path.lineTo(vx, vy);
            path2.moveTo(px, pk);
            path2.lineTo(vx, vy);
            path2.lineTo(px + step * 0.62f, h);
            path2.lineTo(px + step * 0.08f, h);
            path2.close();
            path3.moveTo(px, pk);
            path3.lineTo(vx, vy);
        }
        path.lineTo(w + step, h);
        path.close();
        paint.setStyle(Paint.Style.FILL);
        if (layer == 0) {
            paint.setColor(farShadowCol);
        } else {
            paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
            paint.setShader(nearShadowShader);
        }
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(layer == 0 ? farLitShader : nearLitShader);
        c.drawPath(path2, paint);
        paint.setShader(null);
        if (layer == 1) {
            c.save();
            c.clipPath(path);
            for (int j = 0; j < 6; j++) {
                float sy = h * (0.58f + j * 0.075f);
                paint.setColor((j & 1) == 0 ? strataDark : strataLight);
                c.drawRect(-dp(20f), sy, w + dp(20f), sy + dp(5f + (j % 3) * 4f), paint);
            }
            c.restore();
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(layer == 0 ? 1.5f : 2.5f));
        paint.setColor(layer == 0 ? (nearRimCol & 0x00FFFFFF) | ((nearRimCol >>> 25) << 24) : nearRimCol);
        c.drawPath(path3, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Screen y of the near wall's surface at {@code sx}: where the plane's shadow lands. */
    private float nearWallY(float sx, float h, float ppm) {
        float step = dp(90f);
        double scroll = x * ppm * 0.6;
        float off = (float) (scroll % step);
        int base = (int) Math.floor(scroll / step);
        float rel = sx + off;
        int n = (int) Math.floor(rel / step);
        float fx = rel - n * step;
        float pk = peakY(base + n, 1, h);
        float vy = pk + h * 0.06f;
        float half = step * 0.5f;
        if (fx <= half) {
            return pk + (vy - pk) * (fx / half);
        }
        return vy + (peakY(base + n + 1, 1, h) - vy) * ((fx - half) / half);
    }

    /** x of a mesa's slanted left side at height {@code y}. */
    private static float mesaLeft(float foot, float shoulder, float top, float h, float y) {
        return foot + (shoulder - foot) * (h - y) / (h - top);
    }

    /** x of a mesa's slanted right side at height {@code y}. */
    private static float mesaRight(float shoulder, float foot, float top, float h, float y) {
        return shoulder + (foot - shoulder) * (y - top) / (h - top);
    }

    /**
     * Mesas between the far wall and the near one. Flat-topped on purpose: the walls either side
     * are jagged, so repeating that shape a third time reads as one wall, not as distance. Scrolled
     * at 0.4 - between the far 0.25 and the near 0.6.
     *
     * <p>3.25.0: a sunlit face that hazes toward its foot, a violet shadow flank on the left, a
     * bright sun-facing flank on the right, a lit top under a cap-rock shadow, and level strata
     * cut to the slanted sides. Every mesa goes into the same few multi-contour paths, so the whole
     * layer is six draws however many are on screen.
     */
    private void drawMesas(Canvas c, float w, float h, float ppm) {
        float step = dp(210f);
        double scroll = x * ppm * 0.4;
        float off = (float) (scroll % step);
        int base = (int) (scroll / step);
        for (int pass = 0; pass < 2; pass++) {
            path.rewind();
            path2.rewind();
            path3.rewind();
            for (int k = -1; k * step - off <= w + step; k++) {
                float px = k * step - off;
                int seed = base + k;
                float top = h * (0.44f + ((seed * 6421) & 3) * 0.05f);
                float wide = step * (0.46f + ((seed * 3571) & 3) * 0.1f);
                float l1 = px + step * 0.1f;
                float r1 = px + wide;
                float r0 = r1 + step * 0.13f;
                float tall = h - top;
                if (pass == 0) {
                    path.moveTo(px, h);
                    path.lineTo(l1, top);
                    path.lineTo(r1, top);
                    path.lineTo(r0, h);
                    path.close();
                    float sw = (wide - step * 0.1f) * 0.2f;
                    path2.moveTo(px, h);
                    path2.lineTo(l1, top);
                    path2.lineTo(l1 + sw, top);
                    path2.lineTo(px + sw * 1.5f, h);
                    path2.close();
                    path3.moveTo(r1 - dp(7f), top);
                    path3.lineTo(r1, top);
                    path3.lineTo(r0, h);
                    path3.lineTo(r0 - dp(12f), h);
                    path3.close();
                } else {
                    for (int j = 0; j < 4; j++) {
                        float ya = top + tall * (0.14f + j * 0.18f);
                        float yb = ya + tall * (j == 0 ? 0.035f : 0.055f);
                        Path band = (j & 1) == 0 ? path : path2;
                        band.moveTo(mesaLeft(px, l1, top, h, ya), ya);
                        band.lineTo(mesaRight(r1, r0, top, h, ya), ya);
                        band.lineTo(mesaRight(r1, r0, top, h, yb), yb);
                        band.lineTo(mesaLeft(px, l1, top, h, yb), yb);
                        band.close();
                    }
                    // The shadow under the cap rock, and the sunlit top itself.
                    float ya = top + dp(3f);
                    float yb = top + dp(10f);
                    path.moveTo(mesaLeft(px, l1, top, h, ya), ya);
                    path.lineTo(mesaRight(r1, r0, top, h, ya), ya);
                    path.lineTo(mesaRight(r1, r0, top, h, yb), yb);
                    path.lineTo(mesaLeft(px, l1, top, h, yb), yb);
                    path.close();
                    path3.addRect(l1 - dp(2f), top - dp(2f), r1 + dp(2f), top + dp(2.5f), Path.Direction.CW);
                }
            }
            paint.setStyle(Paint.Style.FILL);
            if (pass == 0) {
                paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
                paint.setShader(mesaShader);
                c.drawPath(path, paint);
                paint.setShader(null);
                paint.setColor(mesaShadowCol);
                c.drawPath(path2, paint);
                paint.setColor(mesaHiCol);
                c.drawPath(path3, paint);
            } else {
                paint.setColor(0x38200814);
                c.drawPath(path, paint);
                paint.setColor(strataLight);
                c.drawPath(path2, paint);
                paint.setColor(mesaHiCol);
                c.drawPath(path3, paint);
            }
        }
    }

    private float altFor(float speed) {
        return Math.max(0f, Math.min(1f, (speed - minSpeed) / (maxSpeed - minSpeed)));
    }

    /** The rival flies the rings too, and never quite lets you go. */
    private void stepRival(float dt) {
        if (sessionSeconds > rivalNextSurge) {
            rivalSurgeUntil = sessionSeconds + 7;
            rivalNextSurge = sessionSeconds + 20 + rng.nextFloat() * 12f;
            rivalTaunt = CanyonRouteMap.taunt(CanyonRouteMap.TAUNT_SURGE_KIND, tauntSeq++);
            rivalTauntUntil = sessionSeconds + 2.6;
        }
        float base = (float) profile.typicalSpeed() * 0.97f;
        float target = base * (sessionSeconds < rivalSurgeUntil ? 1.2f : 1f);
        // Rubber band, bounded: it closes when it is behind and eases when far ahead, so the race
        // is still live at the exit whoever is having the better run.
        target += (float) Math.max(-0.7, Math.min(0.7, (x - rivalX) * 0.012));
        rivalSpeed += (target - rivalSpeed) * Math.min(1f, dt * 1.2f);
        rivalX += rivalSpeed * dt;
        // It flies the next ring's height.
        float wanted = altFor((float) profile.typicalSpeed());
        for (Gate g : gates) {
            if (g.at >= rivalX && !g.skipped) {
                wanted = altFor(g.speed);
                break;
            }
        }
        rivalAlt += (wanted - rivalAlt) * Math.min(1f, dt * 1.8f);
        // A Schmitt trigger, not a bare comparison: the rubber band holds the two of you within a
        // metre or two of each other, and your own distance comes from a coasted speed that dips
        // hard between strokes, so a plain `rivalX > x` flips several times a stroke near parity -
        // banner, taunt and +3 each time.
        boolean ahead = rivalWasAhead ? rivalX > x - LEAD_HYSTERESIS : rivalX > x + LEAD_HYSTERESIS;
        if (ahead != rivalWasAhead && started) {
            rivalWasAhead = ahead;
            rivalTaunt = CanyonRouteMap.taunt(ahead ? CanyonRouteMap.TAUNT_AHEAD_KIND
                    : CanyonRouteMap.TAUNT_BEHIND_KIND, tauntSeq++);
            rivalTauntUntil = sessionSeconds + 2.8;
            if (ahead) {
                banner("THE RIVAL HAS THE LEAD", 0xFFE8557F);
            } else {
                banner("YOU HAVE THE LEAD", ACCENT);
                score += 3;
            }
        }
    }

    /** Crossing the exit ends the run and takes the photo. */
    private void finishRun() {
        finished = true;
        finishSeconds = Math.max(0.1, activeSeconds);
        finishMedal = CanyonRouteMap.medal(finishSeconds, EXIT_METRES, profile);
        finishMargin = (float) (x - rivalX);
        photoFinish = Math.abs(finishMargin) <= 12f;
        finishAt = sessionSeconds;
        if (finishMargin > 0) {
            score += 20;
        }
        shake.kick(dp(8f));
        bests.recordLowest("canyon.exit", (float) finishSeconds);
        saveRecords();
        // The card's text, built once: it is a still picture, not an animation.
        boolean won = finishMargin > 0;
        finishTitle = photoFinish ? "PHOTO FINISH" : won ? "YOU TOOK THE CANYON" : "THE RIVAL TOOK IT";
        finishTimeText = CanyonRouteMap.time(finishSeconds);
        finishLine1 = passed + " gates  ·  " + gems + " prizes  ·  " + golds + " gold  ·  score " + score;
        String best = bests.has("canyon.exit")
                ? "  ·  best " + CanyonRouteMap.time(bests.get("canyon.exit", 0f)) : "";
        finishLine2 = (won ? "won by " : "lost by ")
                + String.format(java.util.Locale.US, "%.1f m", Math.abs(finishMargin))
                + best + "  ·  tap to fly it again";
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();
        boolean live = started && !over && !finished;
        if (live) {
            if (boosting()) {
                boostMetres += boostSpeed() * dt;
            }
            x = sessionMeters - runStart + boostMetres;
        }
        boostGlow += ((boosting() ? 1f : 0f) - boostGlow) * Math.min(1f, dt * 5f);
        skyTopY = h * 0.10f;
        skyBottomY = h * 0.86f;
        spawnGates();
        // Altitude follows a rolling average of speed (about 1.5 s) and ignores changes under
        // 0.1 m/s, so the dip between two strokes no longer sends the wing through the floor.
        float averaged = flightSpeed + (speed - flightSpeed) * Math.min(1f, dt / 1.5f);
        if (Math.abs(averaged - flightSpeed) > 0.1f * dt || flightSpeed == 0f) {
            flightSpeed = averaged;
        }
        if (live) {
            recentSpeed += (speed - recentSpeed) * Math.min(1f, dt / 8f);
        }
        altitude += (altFor(flightSpeed) - altitude) * Math.min(1f, 2.2f * dt);
        shake.step(dt);
        fx.step(dt, 0f);
        if (live) {
            stepRival(dt);
            // Full charges fire themselves after a few seconds, so the rower never has to reach
            // for the screen mid-piece to get the thing they earned.
            if (boostCharges >= boostCapacity && !boosting()) {
                if (boostFullSince == 0) {
                    boostFullSince = sessionSeconds;
                } else if (sessionSeconds - boostFullSince > 5) {
                    fireBoost();
                }
            } else if (boostCharges < boostCapacity) {
                boostFullSince = 0;
            }
        }

        float skyTop = skyTopY;
        float skyBottom = skyBottomY;
        float youX = w * 0.28f;
        float ppm = w / 60f;
        float youY = skyBottom - (skyBottom - skyTop) * altitude;
        float band = (skyBottom - skyTop) * RING_BAND;
        // Magnet: close to the next ring and roughly lined up, drift toward its centre.
        for (Gate g : gates) {
            if (g.resolved) {
                continue;
            }
            float ahead = g.at - (float) x;
            // Down to a little behind the ring: it is judged on the first frame past it, and a
            // magnet that let go on exactly that frame never helped the pass it was built for.
            if (ahead > -5f && ahead < MAGNET_METRES) {
                float gy = skyBottom - (skyBottom - skyTop) * altFor(g.speed);
                if (Math.abs(youY - gy) < band * 2.2f) {
                    float pull = 0.45f * (1f - Math.max(0f, ahead) / MAGNET_METRES);
                    youY += (gy - youY) * pull;
                }
            }
            break;
        }

        if (live) {
            // Forks: which road you are lined up with when you reach the spire is the one you take.
            for (int i = 0; i < forks.size(); i++) {
                Fork f = forks.get(i);
                if (f.chosen >= 0 || x < f.start) {
                    continue;
                }
                corridor(f.start);
                int roads = f.slot ? 3 : 2;
                int bestRoad = LOW_ROAD;
                float bestGap = Float.MAX_VALUE;
                for (int r = 0; r < roads; r++) {
                    float gap = Math.abs(yOf(roadSpeed(r)) - youY);
                    if (gap < bestGap) {
                        bestGap = gap;
                        bestRoad = r;
                    }
                }
                f.chosen = bestRoad;
                if (forksTaken < forkChoices.length) {
                    forkChoices[forksTaken++] = bestRoad;
                }
                for (Gate g : gates) {
                    if (g.fork == f && g.branch != f.chosen) {
                        g.resolved = true;
                        g.skipped = true;
                    }
                }
                if (f.chosen == SLOT_ROAD) {
                    banner("THE SLOT  -  THREAD IT AND THE STARS ARE YOURS", 0xFFFF8A3D);
                } else if (f.chosen == HIGH_ROAD) {
                    banner("HIGH ROAD  -  HOLD THE PACE FOR THE STARS", 0xFFF5C518);
                } else {
                    banner("LOW ROAD  -  EASY PACE, STAY UNDER THE SPIRE", ACCENT);
                }
            }
            for (Gate g : gates) {
                if (g.resolved || g.at > x) {
                    continue;
                }
                g.resolved = true;
                float gy = skyBottom - (skyBottom - skyTop) * altFor(g.speed);
                float dy = Math.abs(youY - gy);
                if (dy <= band) {
                    g.passed = true;
                    passed++;
                    streak++;
                    score++;
                    fx.burst(youX, youY, 18, dp(120f), 0.5f, dp(3f), 0xFF35D0BA, false);
                    String text = streak >= 3 ? "+1  STREAK x" + streak : "+1";
                    int col = 0xFFF5C518;
                    boolean clean = dy <= band * 0.45f && scrapeTime <= 0.05f;
                    if (g.pickup != NONE && dy <= band * 0.4f) {
                        g.collected = true;
                        if (g.pickup == STAR) {
                            int worth = (int) ((g.branch == SLOT_ROAD ? 8 : 5) * prizeMul);
                            score += worth;
                            gems++;
                            text = "STAR  +" + worth;
                            fx.burst(youX, youY, 30, dp(200f), 0.7f, dp(3.5f), 0xFFF5C518, false);
                        } else if (g.pickup == HEART && lives < LIVES) {
                            lives++;
                            text = "LIFE BACK";
                            col = 0xFFF0655D;
                            fx.burst(youX, youY, 24, dp(150f), 0.6f, dp(3.5f), 0xFFF0655D, false);
                        } else {
                            int worth = (int) (2 * prizeMul);
                            score += worth;
                            gems++;
                            text = (g.pickup == HEART ? "HEART  +" : "GEM  +") + worth;
                            col = 0xFF4FE3D0;
                            fx.burst(youX, youY, 22, dp(160f), 0.6f, dp(3f), 0xFF4FE3D0, false);
                        }
                    } else if (dy > band * 0.7f) {
                        // Through, but only just: the rower asked for near misses to pay.
                        score += 2;
                        shaves++;
                        text = "CLOSE SHAVE  +2";
                        col = 0xFFFF8A3D;
                        shake.kick(dp(4f));
                        fx.burst(youX, gy + Math.signum(youY - gy) * band, 16, dp(140f), 0.4f, dp(2.5f), 0xFFFFD36A, false);
                    }
                    if (clean && boostCharges < boostCapacity) {
                        boostCharges++;
                        text = text + "  ·  BOOST " + boostCharges + "/" + boostCapacity;
                        fx.burst(youX - dp(30f), youY, 10, dp(90f), 0.4f, dp(2.5f), 0xFFFF8A3D, false);
                    }
                    popup(text, col, youY);
                } else {
                    loseLife(youX, youY);
                    if (over) {
                        break;
                    }
                }
            }
            // Timing gates: each split medalled against this rower's own speeds.
            while (!over && x >= nextCheckAt && nextCheckAt < EXIT_METRES) {
                double split = Math.max(0.1, activeSeconds - lastCheckSeconds);
                lastCheckSeconds = activeSeconds;
                checkIndex++;
                int medal = CanyonRouteMap.medal(split, CHECK_METRES, profile);
                if (medal == CanyonRouteMap.GOLD) {
                    golds++;
                    score += 6;
                } else if (medal == CanyonRouteMap.SILVER) {
                    silvers++;
                    score += 4;
                } else if (medal == CanyonRouteMap.BRONZE) {
                    bronzes++;
                    score += 2;
                }
                banner("CP " + checkIndex + "  " + CanyonRouteMap.MEDAL_NAME[medal] + "  "
                        + CanyonRouteMap.time(split), CanyonRouteMap.MEDAL_COLOR[medal]);
                fx.burst(youX, youY, 20, dp(150f), 0.6f, dp(3f), CanyonRouteMap.MEDAL_COLOR[medal], false);
                nextCheckAt += CHECK_METRES;
            }
            // Rock: the roof, the floor, and inside a fork whichever spire you are on.
            if (!over) {
                corridor(x);
                float slack = dp(7f);
                boolean high = youY < yOf(cCeil) + slack;
                boolean low = youY > yOf(cFloor) - slack;
                boolean inSpire = false;
                if (cFork != null && cFork.chosen >= 0) {
                    for (int i = 0; i < rockCount; i++) {
                        if (youY > yOf(rockHi[i]) - slack && youY < yOf(rockLo[i]) + slack) {
                            inSpire = true;
                            break;
                        }
                    }
                    if (inSpire) {
                        // Point the rower at the road they chose rather than at "up" or "down".
                        high = yOf(roadSpeed(cFork.chosen)) > youY;
                        low = !high;
                    }
                }
                scrapeHigh = high;
                if ((high || low || inSpire) && sessionSeconds > graceUntil) {
                    scrapeTime += dt;
                    shake.kick(dp(3f));
                    sparkClock += dt;
                    if (sparkClock > 0.04) {
                        sparkClock = 0;
                        fx.spawn(youX + dp(10f), youY + (high ? -dp(13f) : dp(13f)),
                                -dp(80f) - (float) Math.random() * dp(120f),
                                (high ? -1 : 1) * (float) Math.random() * dp(60f),
                                0.4f, dp(2.5f), 0xFFFFD36A, false);
                    }
                    if (scrapeTime > SCRAPE_LIMIT) {
                        scrapeTime = 0f;
                        graceUntil = sessionSeconds + 2.5;
                        popup(high ? "HIT THE ROOF" : "HIT THE ROCK", BAD, youY);
                        loseLife(youX, youY);
                    }
                } else {
                    scrapeTime = Math.max(0f, scrapeTime - dt * 1.5f);
                }
            }
            if (!over && x >= EXIT_METRES) {
                finishRun();
            }
        }

        // The light: afternoon to dusk along the run.
        float dayT = Daylight.progress(x + dayOffset);
        float dusk = Daylight.shade(dayT);
        int phase = Daylight.phaseIndex(dayT);
        if (phase != lastPhase) {
            if (started && phase > lastPhase) {
                banner(Daylight.phaseName(phase), 0xFFFFC27A);
            }
            lastPhase = phase;
        }

        c.save();
        c.translate(shake.dx, shake.dy);
        if (skyShader == null || skyHeight != h || skyT != dayT) {
            skyHeight = h;
            skyT = dayT;
            skyShader = new LinearGradient(0, 0, 0, h, Daylight.top(dayT), Daylight.horizon(dayT),
                    Shader.TileMode.CLAMP);
            buildLight(h, dayT);
        }
        // Opaque first: a shader is drawn at the paint's alpha, and the HUD leaves it at 20%.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(skyShader);
        c.drawRect(-dp(20f), -dp(20f), w + dp(20f), h + dp(20f), paint);
        paint.setShader(null);
        drawSkyLife(c, w, h, ppm, dayT);
        // The canyon in four layers, far to near, each hazier than the one in front of it: a low
        // range, the far wall, the mesas, the near wall.
        drawRange(c, w, h, ppm);
        drawHaze(c, w, h, hazeFar, h * 0.46f);
        drawWall(c, w, h, ppm, 0);
        drawHaze(c, w, h, hazeNear, h * 0.58f);
        drawMesas(c, w, h, ppm);
        drawWall(c, w, h, ppm, 1);
        // Speed lanes on the left as a subtle altitude scale.
        for (float sp = (float) Math.ceil(minSpeed * 2f) / 2f; sp <= maxSpeed; sp += 0.5f) {
            float ly = skyBottom - (skyBottom - skyTop) * altFor(sp);
            paint.setColor(0x22FFFFFF);
            c.drawLine(0, ly, w, ly, paint);
            label(c, String.format(java.util.Locale.US, "%.1f", sp), dp(6f), ly - dp(3f), 8f, 0x66FFFFFF,
                    Paint.Align.LEFT);
        }
        // The closing rock and any fork spires, in front of the far scenery, behind the plane.
        drawCorridor(c, w, h, youX, ppm, dusk);
        drawForks(c, w, youX, ppm, dusk);
        drawPlaneShadow(c, h, youX, youY, ppm);
        drawCheckpoints(c, w, h, youX, ppm);
        // Gates: the back half of each ring now, the front half after the plane, so you fly through.
        drawGates(c, w, youX, ppm, band, false);
        drawRival(c, w, youX, ppm);
        // You: a little plane, pitched by the climb, with a contrail and prop blur.
        float climb = dt > 0 && lastYouY != 0f ? (lastYouY - youY) / dt : 0f;
        lastYouY = youY;
        pitch += (Math.max(-0.5f, Math.min(0.5f, climb / dp(160f))) - pitch) * Math.min(1f, 4f * dt);
        Fx.glow(c, youX, youY, dp(84f), boostGlow > 0.2f ? 0x88FF8A3D : streak >= 3 ? 0x66F5C518 : 0x4035D0BA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Contrails from the tail of the bigger plane.
        float tail = dp(50f) * YOU_SCALE * 0.9f;
        // The tail drops as the nose pitches up: its distance times the 0.6 rad-per-pitch rotation.
        float tailY = youY + pitch * tail * 0.6f;
        for (int k = 0; k < 3; k++) {
            int trail = boostGlow > 0.2f ? (k == 0 ? 0xCCFFD36A : k == 1 ? 0x88FF8A3D : 0x55FF6A2A)
                    : (k == 0 ? 0x88FFFFFF : k == 1 ? 0x55FFFFFF : 0x33FFFFFF);
            paint.setColor(trail);
            paint.setStrokeWidth(dp(5f - k * 1.3f) * (1f + boostGlow));
            float tx = youX - tail - dp(14f) - k * speed * dp(16f) - boostGlow * dp(100f);
            c.drawLine(youX - tail, tailY, tx - speed * dp(12f), tailY + k * dp(3.5f), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        boolean hurt = sessionSeconds < hurtUntil;
        boolean grace = sessionSeconds < graceUntil && !boosting();
        if ((!hurt && !grace) || ((int) (sessionSeconds * 10) % 2 == 0)) {
            drawPlane(c, youX, youY, speed, pitch, 0xFFF4F4F4, ACCENT, YOU_SCALE, true);
        }
        drawGates(c, w, youX, ppm, band, true);
        fx.draw(c);
        if (sessionSeconds < popupUntil) {
            // Clear of the bigger plane's nose and propeller.
            float rise = (float) (1.1 - (popupUntil - sessionSeconds)) * dp(50f);
            bold(c, popupText, youX + dp(62f), popupY - dp(34f) - rise, 20f, popupColor, Paint.Align.LEFT);
        }
        c.restore();
        Fx.speedLines(c, paint, w, h, speed + boostGlow * 3f, sessionSeconds, dp(1f));
        if (hurt) {
            Fx.vignette(c, w, h, 0.7f, 0x8A1010);
        } else if (boostGlow > 0.05f) {
            Fx.vignette(c, w, h, 0.25f + boostGlow * 0.35f, 0x8A4A10);
        } else if (scrapeTime > 0.05f) {
            Fx.vignette(c, w, h, 0.25f + scrapeTime / SCRAPE_LIMIT * 0.5f, 0x8A4A10);
        }

        // HUD.
        for (int i = 0; i < LIVES; i++) {
            paint.setColor(i < lives ? ACCENT : 0x33FFFFFF);
            c.drawCircle(dp(18f) + i * dp(18f), dp(20f), dp(6f), paint);
        }
        label(c, gems + " prizes  ·  " + shaves + " shaves  ·  " + golds + "G " + silvers + "S " + bronzes + "B",
                dp(12f), dp(42f), 8.5f, FAINT, Paint.Align.LEFT);
        bold(c, String.valueOf(score), w / 2f, dp(30f), 24f, TEXT, Paint.Align.CENTER);
        label(c, streak >= 2 ? "SCORE  ·  " + passed + " GATES  ·  STREAK x" + streak : "SCORE  ·  " + passed + " GATES",
                w / 2f, dp(44f), 8.5f, streak >= 3 ? 0xFFF5C518 : FAINT, Paint.Align.CENTER);
        // The race: who is ahead, and how far is left.
        float lead = (float) (x - rivalX);
        label(c, started ? (lead >= 0 ? "LEADING BY " + Math.round(lead) + " m"
                        : "RIVAL AHEAD BY " + Math.round(-lead) + " m") + "   ·   "
                        + Math.max(0, Math.round(EXIT_METRES - x)) + " m TO THE EXIT"
                        : "THE RIVAL IS WARMING UP",
                w / 2f, dp(58f), 9f, lead >= 0 ? ACCENT : 0xFFE8557F, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.1f m/s", speed), w - dp(16f), dp(30f), 18f, ACCENT,
                Paint.Align.RIGHT);
        label(c, Daylight.phaseName(phase) + "  ·  " + clock(activeSeconds), w - dp(16f), dp(44f), 8.5f,
                0xFFFFC27A, Paint.Align.RIGHT);
        float mapW = Math.min(dp(230f), w * 0.3f);
        map.draw(c, w - dp(16f) - mapW, dp(64f), mapW, (float) (x / EXIT_METRES), forkChoices,
                Math.max(5, forksTaken), ROAD_NAMES, unlockScore,
                (int) (EXIT_METRES / CHECK_METRES) - 1, checkIndex, golds);
        drawBoostMeter(c, w, h);
        if (sessionSeconds < bannerUntil) {
            bold(c, bannerText, w / 2f, h * 0.2f, 18f, bannerColor, Paint.Align.CENTER);
        }
        Gate next = null;
        for (Gate g : gates) {
            if (!g.resolved) {
                next = g;
                break;
            }
        }
        Fork nextFork = null;
        for (int i = 0; i < forks.size(); i++) {
            Fork f = forks.get(i);
            if (f.chosen < 0 && f.start - x < 70f) {
                nextFork = f;
                break;
            }
        }
        corridor(x);
        float roofGap = youY - yOf(cCeil);
        float floorGap = yOf(cFloor) - youY;
        String cap;
        int col = FAINT;
        if (!started) {
            cap = "TAKE A STROKE TO TAKE OFF  ·  " + Math.round(EXIT_METRES) + " m TO THE CANYON EXIT";
        } else if (over) {
            cap = "DOWN  ·  score " + score + "  ·  " + passed + " gates, " + Math.round(x) + " m  ·  tap to fly again";
            col = BAD;
        } else if (finished) {
            cap = "";
        } else if (boosting()) {
            cap = "BOOST  -  THE ROCK CANNOT TOUCH YOU";
            col = 0xFFFF8A3D;
        } else if (scrapeTime > 0.05f) {
            // Covers the fork spire too, which is neither the roof nor the floor.
            cap = scrapeHigh ? "SCRAPING THE ROOF - EASE OFF" : "SCRAPING ROCK - PULL UP";
            col = BAD;
        } else if (nextFork != null) {
            cap = String.format(java.util.Locale.US, "FORK IN %d m  ·  %s",
                    Math.max(0, Math.round(nextFork.start - x)),
                    nextFork.slot ? "CLIMB FOR THE STARS, HOLD STEADY FOR THE SLOT, SETTLE FOR THE LOW"
                            : "CLIMB FOR THE HIGH ROAD, SETTLE FOR THE LOW");
            col = 0xFFF5C518;
        } else if (roofGap < dp(28f)) {
            cap = "ROOF CLOSING - EASE OFF";
            col = WARN;
        } else if (floorGap < dp(28f)) {
            cap = "ROCK BELOW - PULL UP";
            col = WARN;
        } else if (next != null) {
            float diff = next.speed - speed;
            cap = Math.abs(diff) < 0.5f ? "LINED UP - HOLD IT"
                    : diff > 0 ? String.format(java.util.Locale.US, "CLIMB  +%.1f m/s  ·  %d m", diff, Math.round(next.at - x))
                    : String.format(java.util.Locale.US, "DIVE  %.1f m/s  ·  %d m", diff, Math.round(next.at - x));
            col = Math.abs(diff) < 0.5f ? ACCENT : WARN;
        } else {
            cap = "CLEAR SKIES";
        }
        if (cap.length() > 0) {
            bold(c, cap, w / 2f, h - dp(16f), 12f, col, Paint.Align.CENTER);
        }
        if (finished) {
            drawPhotoFinish(c, w, h);
        }
    }

    /** The shutter, then the print. */
    private void drawPhotoFinish(Canvas c, float w, float h) {
        float age = (float) (sessionSeconds - finishAt);
        float flash = Math.max(0f, 1f - age / 0.45f);
        map.photoFinish(c, w, h, flash, finishTitle, finishMedal, finishTimeText, finishLine1, finishLine2,
                ACCENT, 0xFFE8557F, finishMargin, finishSeconds);
    }

    /**
     * The light along the canyon route, shared by FLY and DRIVE: afternoon to golden hour to
     * sunset to dusk as the run gets longer. Progress is quantised so the sky gradient is rebuilt a
     * few dozen times a run, not every frame.
     */
    static final class Daylight {
        /** Metres of run at which the sun reaches the rim. */
        private static final double SUNSET_METRES = 1800.0;
        private static final float MAX_T = 1.3f;
        private static final float[] KEY_T = {0f, 0.45f, 0.8f, 1.0f, 1.3f};
        private static final int[] TOP = {0xFF3A6EA5, 0xFF2D5486, 0xFF1B3358, 0xFF2A2352, 0xFF0E0C24};
        private static final int[] HORIZON = {0xFFF2D09A, 0xFFF0A860, 0xFFE07A3F, 0xFFD9553A, 0xFF6A2E4C};
        private static final int[] SUN = {0xFFFFF4D6, 0xFFFFE2A8, 0xFFFFC27A, 0xFFFF8A4A, 0xFFC0452E};
        private static final String[] PHASES = {"AFTERNOON", "GOLDEN HOUR", "SUNSET", "DUSK"};

        private Daylight() {
        }

        /** 0 at the start of a run, 1 at sunset, up to 1.3 at dusk; quantised to 1/40. */
        static float progress(double metres) {
            float t = (float) Math.min(MAX_T, Math.max(0.0, metres / SUNSET_METRES));
            return Math.round(t * 40f) / 40f;
        }

        static int top(float t) {
            return keyed(TOP, t);
        }

        static int horizon(float t) {
            return keyed(HORIZON, t);
        }

        static int sun(float t) {
            return keyed(SUN, t);
        }

        /** How far the sun has sunk: 0 high, 1 at the rim, past 1 behind it. */
        static float sunDrop(float t) {
            return Math.min(1.25f, t);
        }

        /** 0..1: how much the land is darkened toward dusk. */
        static float shade(float t) {
            return Math.min(1f, Math.max(0f, (t - 0.3f) / 1.0f)) * 0.75f;
        }

        /** Star brightness, 0 until just after sunset. */
        static float stars(float t) {
            return Math.min(1f, Math.max(0f, (t - 1.0f) / 0.25f));
        }

        static int phaseIndex(float t) {
            return t < 0.4f ? 0 : t < 0.8f ? 1 : t < 1.08f ? 2 : 3;
        }

        static String phaseName(int i) {
            return PHASES[Math.max(0, Math.min(PHASES.length - 1, i))];
        }

        private static int keyed(int[] cols, float t) {
            for (int i = 1; i < KEY_T.length; i++) {
                if (t <= KEY_T[i]) {
                    return blend(cols[i - 1], cols[i], (t - KEY_T[i - 1]) / (KEY_T[i] - KEY_T[i - 1]));
                }
            }
            return cols[cols.length - 1];
        }

        static int blend(int a, int b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
            int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
            int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
    }
}
