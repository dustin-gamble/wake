package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Canyon Chase: a pursuer on your tail through a twisting canyon, drawn in perspective.
 *
 * <p>The canyon snakes; a racing line runs down the middle of it. Scrape a wall and you lose
 * speed and the hunter closes. Bank angle comes from how fast you are crossing the canyon, so the
 * horizon rolls into every turn.
 *
 * <p>Steering (the rower's upgrade list): with the WitMotion handle sensor connected, you steer
 * by tilting the handle, and the bends push you toward their outside so you have to lean into
 * them. <b>That sensor is untested hardware</b> - see HandleSensor - so the pace fallback is what
 * actually gets played today, and it was reworked rather than left as an afterthought:
 * <ul>
 *   <li>The craft follows the canyon's bends by itself; pace moves you <i>across</i> the racing
 *   line, not across the world. Speed can change once a stroke, a bend comes every few seconds,
 *   so asking pace to follow bends was asking the impossible.</li>
 *   <li>The centre is your own pace (half the profile's typical, half the last 20 s), and the
 *   width is the profile's low-high spread, so "right" means a little above what you hold.</li>
 *   <li>Pace is averaged over ~1.2 s, so the coast between two strokes does not swing the craft.</li>
 *   <li>A position bar at the bottom shows where you are, the walls, and the fork spires.</li>
 * </ul>
 *
 * <p>Forks: every ~400 m rock splits the canyon. LEFT is a squeezed shortcut that gains 12 m on the
 * hunter; RIGHT is the wide road. Once the SLOT ROUTE is unlocked the rock comes as two spires with
 * a gap between them: thread the middle and you gain 26 m. The side you are on when the rock starts
 * is the road you take, and inside, the rock is a wall like any other.
 *
 * <p>The race upgrades (this build), shared with FLY through {@link CanyonRouteMap}:
 * <ul>
 *   <li><b>Time-trial gates.</b> A timing arch every {@link #CHECK_METRES}; each split is medalled
 *   against the rower's own speeds, and so is the whole run to the exit ({@code chase.exit}).</li>
 *   <li><b>A rival</b> runs the same canyon ahead of you - the thing you are chasing while the
 *   hunter chases you - rubber-banded so the race is live at the line, and it taunts on every lead
 *   change.</li>
 *   <li><b>Boost</b> is earned by taking a timing gate clean (no rock touched since the last one).
 *   Spending it throws you down the canyon, pushes the hunter back and makes you rock-proof while
 *   it burns.</li>
 *   <li><b>A route map</b> top left, with the branches still locked and what they cost.</li>
 *   <li><b>A photo finish</b> at the canyon exit against the rival.</li>
 * </ul>
 *
 * <p>The light moves from afternoon to dusk along the run ({@link CanyonFlightGame.Daylight}).
 *
 * <p>Perspective is a simple pinhole: a point {@code z} metres ahead projects to
 * {@code scale = FOCAL / z}, so near segments are wide and far ones converge on the horizon.
 */
final class CanyonChaseGame extends GameView {

    private static final int SEGMENTS = 44;
    private static final float SEG_LEN = 7f;          // metres per drawn segment
    private static final float CANYON_HALF = 16f;     // metres from centreline to wall
    private static final float FOCAL = 26f;
    /** Metres from the wall at which the craft scrapes. */
    private static final float WALL_MARGIN = 3f;
    private static final float SPIRE = 3f;            // half-width of a single fork spire
    /**
     * With the slot open: two spires this far either side of the line, this wide. Sized against
     * the 24 m of usable canyon so all three roads are real - about 4 m of open lane either side
     * of the rock and an 8 m slot down the middle. A slot fork does NOT squeeze the left wall;
     * with the squeeze as well the shortcut lane came out 1.5 m wide, which is not a road.
     */
    private static final float SLOT_OFFSET = 6.5f;
    private static final float SLOT_HALF = 1.5f;
    /** How close to rock the craft may come. */
    private static final float ROCK_MARGIN = 1f;
    private static final float SQUEEZE = 2f;          // how far the shortcut's outer wall closes in
    private static final float FORK_LEN = 90f;
    private static final float SHORTCUT_GAIN = 12f;
    private static final float SLOT_GAIN = 26f;
    /** The canyon has an end: reach it and the chase is over, with a photo at the line. */
    private static final float EXIT_METRES = 2000f;
    private static final float CHECK_METRES = 400f;
    private static final float BOOST_PER_CHARGE = 1.3f;
    /** Metres the gap must actually swing before the lead is called changed. */
    private static final float LEAD_HYSTERESIS = 5f;

    private static final int LEFT_ROAD = 0;
    private static final int RIGHT_ROAD = 1;
    private static final int SLOT_ROAD = 2;
    private static final String[] ROAD_NAMES = {"SHORTCUT", "WIDE", "SLOT"};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final java.util.Random rng = new java.util.Random();
    private final CanyonRouteMap map;
    private final int[] forkChoices = new int[12];
    private int forksTaken;
    // 3.19.5 scenery: mesas on the horizon, vultures, wall strata, boulders, dust behind the craft.
    private double dustClock;

    private boolean started;
    private boolean over;
    private boolean finished;
    private double runStart;
    /** Session clock at the first stroke: the hunter speeds up with run time, not session time. */
    private double runStartSeconds;
    private double x;                 // metres travelled
    /** Metres from the racing line (not the world), smoothed. */
    private float lateral;
    private float lateralVel;
    private float bank;
    private double hunterGap = 55;    // metres behind
    private double scrapeUntil;
    private int scrapes;
    private int shortcuts;
    private int score;
    /** Pace averaged over ~1.2 s, and over ~20 s. */
    private float smoothSpeed;
    private float recentSpeed;

    private double forkStart;
    private double forkEnd;
    /** True when this fork is the two-spire version with a slot down the middle. */
    private boolean forkSlot;
    /** -1 undecided, else LEFT_ROAD / RIGHT_ROAD / SLOT_ROAD. */
    private int forkRoad = -1;

    /** Spires at one distance: centres and half width, written by {@link #bands}. */
    private final float[] bandC = new float[2];
    private float bandHalf;
    private int bandCount;

    private String popupText = "";
    private int popupColor = TEXT;
    private double popupUntil;
    private int lastPhase;

    private LinearGradient skyShader;
    private float skyT = -1f;
    private float skyHorizon;

    /* ---------- the race ---------- */

    private int tier;
    /**
     * The score {@link #tier} was decided from, read once at the start of the run. The route map
     * is drawn from this rather than the live score: points earned mid-run do not put a slot in
     * the rock until the next run, so a padlock opening on screen would be a lie.
     */
    private float unlockScore;
    private int pointMul = 1;
    private float dayOffset;

    private float nextCheckAt;
    private int checkIndex;
    private double lastCheckSeconds;
    private boolean cleanSinceCheck = true;
    private int golds;
    private int silvers;
    private int bronzes;

    private int boostCharges;
    private int boostCapacity = 3;
    private double boostUntil;
    private double boostFullSince;
    private double boostMetres;
    private float boostGlow;
    private float boostHitX;
    private float boostHitY;
    private float boostHitW;
    private float boostHitH;

    /** The rival craft: the thing ahead, while the hunter is the thing behind. */
    private double rivalX;
    private float rivalSpeed;
    private double rivalSurgeUntil;
    private double rivalNextSurge;
    private String rivalTaunt = "";
    private double rivalTauntUntil;
    private boolean rivalWasAhead;
    private int tauntSeq;

    private double finishSeconds;
    private float finishMargin;
    private int finishMedal = CanyonRouteMap.NO_MEDAL;
    private double finishAt;
    private boolean photo;
    /**
     * The finish card's text and the footer's best time, built once rather than reformatted every
     * frame: {@link CanyonRouteMap#time} runs a Formatter, and the footer drew one 60 times a
     * second for a figure that only changes when a run ends.
     */
    private String finishTitle = "";
    private String finishTimeText = "";
    private String finishLine1 = "";
    private String finishLine2 = "";
    private String bestExitText = "--";

    CanyonChaseGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.map = new CanyonRouteMap(context.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        finished = false;
        x = 0;
        lateral = 0f;
        lateralVel = 0f;
        bank = 0f;
        hunterGap = 55;
        scrapes = 0;
        shortcuts = 0;
        score = 0;
        smoothSpeed = 0f;
        recentSpeed = (float) profile.typicalSpeed();
        rng.setSeed(31);
        unlockScore = bests.get("chase.score", 0f);
        tier = CanyonRouteMap.tiers(unlockScore);
        boostCapacity = tier >= 2 ? 4 : 3;
        pointMul = tier >= 3 ? 2 : 1;
        dayOffset = tier >= 3 ? 760f : 0f;
        boostCharges = 0;
        boostUntil = 0;
        boostFullSince = 0;
        boostMetres = 0;
        boostGlow = 0f;
        forkStart = 260;
        forkEnd = forkStart + FORK_LEN;
        forkSlot = tier >= 1;
        forkRoad = -1;
        forksTaken = 0;
        for (int i = 0; i < forkChoices.length; i++) {
            forkChoices[i] = -1;
        }
        nextCheckAt = CHECK_METRES;
        checkIndex = 0;
        lastCheckSeconds = 0;
        cleanSinceCheck = true;
        golds = 0;
        silvers = 0;
        bronzes = 0;
        rivalX = 60;
        rivalSpeed = (float) profile.typicalSpeed();
        rivalSurgeUntil = 0;
        rivalNextSurge = 18;
        rivalTauntUntil = 0;
        rivalWasAhead = true;
        tauntSeq = 0;
        photo = false;
        finishTitle = "";
        finishTimeText = "";
        finishLine1 = "";
        finishLine2 = "";
        bestExitText = bests.has("chase.exit") ? CanyonRouteMap.time(bests.get("chase.exit", 0f)) : "--";
        popupUntil = 0;
        lastPhase = 0;
        map.collapse();
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
            runStart = sessionMeters;
            runStartSeconds = sessionSeconds;
            recentSpeed = Math.max(boat.value(), (float) profile.lowSpeed());
            rivalX = 60;
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

    /** Canyon centreline at a given distance: two sines so the bends never repeat predictably. */
    private float centreAt(double metres) {
        return (float) (Math.sin(metres / 46.0) * 9.5 + Math.sin(metres / 17.0) * 3.2);
    }

    /** 0 outside the fork, rising to 1 over 12 m at each end, so the rock is a wedge. */
    private float forkRamp(double m) {
        if (m < forkStart || m > forkEnd) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) Math.min(m - forkStart, forkEnd - m) / 12f));
    }

    /** The spires at a distance: one centred, or two with the slot between them. */
    private void bands(double m) {
        float ramp = forkRamp(m);
        if (ramp <= 0f) {
            bandCount = 0;
            bandHalf = 0f;
            return;
        }
        if (forkSlot) {
            bandC[0] = -SLOT_OFFSET;
            bandC[1] = SLOT_OFFSET;
            bandHalf = SLOT_HALF * ramp;
            bandCount = 2;
        } else {
            bandC[0] = 0f;
            bandHalf = SPIRE * ramp;
            bandCount = 1;
        }
    }

    /** Distance from the line to the left wall; the plain shortcut squeezes it, the slot does not. */
    private float leftWall(double m) {
        return CANYON_HALF - (forkSlot ? 0f : SQUEEZE) * forkRamp(m);
    }

    /** Where the middle of each road through the fork sits, in metres off the racing line. */
    private float roadCentre(int road) {
        float leftLimit = -(CANYON_HALF - (forkSlot ? 0f : SQUEEZE) - WALL_MARGIN);
        float rightLimit = CANYON_HALF - WALL_MARGIN;
        if (road == SLOT_ROAD) {
            return 0f;
        }
        float edge = (forkSlot ? SLOT_OFFSET + SLOT_HALF : SPIRE) + ROCK_MARGIN;
        return road == LEFT_ROAD ? (leftLimit - edge) / 2f : (rightLimit + edge) / 2f;
    }

    /** The pace that puts you on the line: half the profile's typical, half what you have held lately. */
    private float paceCentre() {
        return 0.5f * (float) profile.typicalSpeed() + 0.5f * recentSpeed;
    }

    /** Pace that takes you from the line to a wall's edge. */
    private float paceSpread() {
        return (float) Math.max(0.3, (profile.highSpeed() - profile.lowSpeed()) / 2.0);
    }

    /**
     * Where you want to sit, in metres from the racing line.
     *
     * <p>With a handle tilt sensor this is where you steered, less the bend pushing you to its
     * outside. Without one it is pace against your own centre, following the bends for you.
     */
    private float lateralFor(float bendAhead) {
        if (hasSteering()) {
            return steering() * (CANYON_HALF - 2.5f) - bendAhead * 0.45f;
        }
        float f = (smoothSpeed - paceCentre()) / paceSpread();
        f = Math.max(-1.25f, Math.min(1.25f, f));
        // Pace is still building for the first ~40 m of a run (about ten strokes); steering eases
        // in over that stretch so a standing start does not throw the craft into the left wall.
        float warm = started ? Math.max(0f, Math.min(1f, (float) x / 40f)) : 0f;
        return f * (CANYON_HALF - 2.5f) * warm;
    }

    private void popup(String text, int color) {
        popupText = text;
        popupColor = color;
        popupUntil = sessionSeconds + 1.6;
    }

    /* ---------- boost ---------- */

    private void fireBoost() {
        if (boostCharges <= 0 || boosting() || !started || over || finished) {
            return;
        }
        boostUntil = sessionSeconds + BOOST_PER_CHARGE * boostCharges;
        hunterGap = Math.min(90, hunterGap + 4 * boostCharges);
        boostCharges = 0;
        boostFullSince = 0;
        shake.kick(dp(6f));
        popup("BOOST", 0xFFFF8A3D);
    }

    private boolean boosting() {
        return sessionSeconds < boostUntil;
    }

    private float boostSpeed() {
        return (float) profile.typicalSpeed() * (tier >= 2 ? 1.5f : 1.1f);
    }

    /* ---------- the rival ---------- */

    private void stepRival(float dt) {
        if (sessionSeconds > rivalNextSurge) {
            rivalSurgeUntil = sessionSeconds + 7;
            rivalNextSurge = sessionSeconds + 20 + rng.nextFloat() * 12f;
            rivalTaunt = CanyonRouteMap.taunt(CanyonRouteMap.TAUNT_SURGE_KIND, tauntSeq++);
            rivalTauntUntil = sessionSeconds + 2.6;
        }
        float base = (float) profile.typicalSpeed() * 0.97f;
        float target = base * (sessionSeconds < rivalSurgeUntil ? 1.2f : 1f);
        target += (float) Math.max(-0.7, Math.min(0.7, (x - rivalX) * 0.012));
        rivalSpeed += (target - rivalSpeed) * Math.min(1f, dt * 1.2f);
        rivalX += rivalSpeed * dt;
        // A Schmitt trigger, not a bare comparison: the rubber band keeps the two of you within a
        // metre or two, and your distance comes from a coasted speed that dips between strokes, so
        // a plain `rivalX > x` flips several times a stroke near parity - and each flip paid +10.
        boolean ahead = rivalWasAhead ? rivalX > x - LEAD_HYSTERESIS : rivalX > x + LEAD_HYSTERESIS;
        if (ahead != rivalWasAhead) {
            rivalWasAhead = ahead;
            rivalTaunt = CanyonRouteMap.taunt(ahead ? CanyonRouteMap.TAUNT_AHEAD_KIND
                    : CanyonRouteMap.TAUNT_BEHIND_KIND, tauntSeq++);
            rivalTauntUntil = sessionSeconds + 2.8;
            if (!ahead) {
                score += 10 * pointMul;
                popup("PAST THE RIVAL  +" + (10 * pointMul), ACCENT);
            } else {
                popup("THE RIVAL IS PAST YOU", 0xFFE8557F);
            }
        }
    }

    private void finishRun() {
        finished = true;
        finishSeconds = Math.max(0.1, activeSeconds);
        finishMedal = CanyonRouteMap.medal(finishSeconds, EXIT_METRES, profile);
        finishMargin = (float) (x - rivalX);
        photo = Math.abs(finishMargin) <= 12f;
        finishAt = sessionSeconds;
        score += 20 * pointMul;
        if (finishMargin > 0) {
            score += 20 * pointMul;
        }
        shake.kick(dp(8f));
        bests.recordLowest("chase.exit", (float) finishSeconds);
        saveRecords();
        // The card's text, built once: it is a still picture, not an animation.
        boolean won = finishMargin > 0;
        bestExitText = bests.has("chase.exit") ? CanyonRouteMap.time(bests.get("chase.exit", 0f)) : "--";
        finishTitle = photo ? "PHOTO FINISH" : won ? "YOU BEAT THE RIVAL OUT" : "THE RIVAL GOT OUT FIRST";
        finishTimeText = CanyonRouteMap.time(finishSeconds);
        finishLine1 = scrapes + " scrapes  ·  " + shortcuts + " shortcuts  ·  " + golds + " gold  ·  score " + score;
        String best = bests.has("chase.exit") ? "  ·  best " + bestExitText : "";
        finishLine2 = (won ? "won by " : "lost by ")
                + String.format(java.util.Locale.US, "%.1f m", Math.abs(finishMargin))
                + best + "  ·  tap to run it again";
    }

    private void saveRecords() {
        bests.recordHighest("chase.distance", (float) x);
        bests.recordHighest("chase.score", score);
        bests.recordHighest("chase.gold", golds);
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
            recentSpeed += (speed - recentSpeed) * Math.min(1f, dt / 20f);
        }
        boostGlow += ((boosting() ? 1f : 0f) - boostGlow) * Math.min(1f, dt * 5f);
        smoothSpeed += (speed - smoothSpeed) * Math.min(1f, dt / 1.2f);

        // Lateral: eased toward the target, so it feels like a craft, not a cursor.
        float canyonBend = (centreAt(x + 24) - centreAt(x)) * 0.9f;
        float targetLat = lateralFor(canyonBend);
        float prevLat = lateral;
        lateral += (targetLat - lateral) * Math.min(1f, (hasSteering() ? 6.0f : 2.0f) * dt);
        lateralVel = dt > 0 ? (lateral - prevLat) / dt : 0f;
        // Bank into the turn: your own drift plus the canyon bending under you.
        float targetBank = Math.max(-28f, Math.min(28f, lateralVel * 2.4f - canyonBend * 1.1f));
        bank += (targetBank - bank) * Math.min(1f, 4f * dt);

        float centre = centreAt(x);
        float offLine = lateral;
        float leftLimit = -(leftWall(x) - WALL_MARGIN);
        float rightLimit = CANYON_HALF - WALL_MARGIN;
        boolean wallHit = offLine < leftLimit || offLine > rightLimit;
        bands(x);
        boolean spireHit = false;
        int hitBand = -1;
        for (int i = 0; i < bandCount; i++) {
            if (Math.abs(offLine - bandC[i]) < bandHalf + ROCK_MARGIN) {
                spireHit = true;
                hitBand = i;
                break;
            }
        }
        boolean scraping = (wallHit || spireHit) && !boosting();
        // Clamped to 1: pinned past a wall this ran over 1, and the alpha built from it
        // ((int)(n*8)*16) then overflowed past 255 and wrapped the warning glow back to faint.
        float nearWall = Math.max(0f, Math.min(1f,
                1f - Math.min(offLine - leftLimit, rightLimit - offLine) / 4f));

        if (live) {
            stepRival(dt);
            if (boostCharges >= boostCapacity && !boosting()) {
                if (boostFullSince == 0) {
                    boostFullSince = sessionSeconds;
                } else if (sessionSeconds - boostFullSince > 5) {
                    fireBoost();
                }
            } else if (boostCharges < boostCapacity) {
                boostFullSince = 0;
            }
            // Fork: the road you are lined up with when the rock begins is the road you take.
            if (forkRoad < 0 && x >= forkStart) {
                int roads = forkSlot ? 3 : 2;
                int best = LEFT_ROAD;
                float bestGapM = Float.MAX_VALUE;
                for (int r = 0; r < roads; r++) {
                    float gap = Math.abs(roadCentre(r) - offLine);
                    if (gap < bestGapM) {
                        bestGapM = gap;
                        best = r;
                    }
                }
                forkRoad = best;
                if (forksTaken < forkChoices.length) {
                    forkChoices[forksTaken++] = best;
                }
                popup(best == SLOT_ROAD ? "THE SLOT  -  THREAD IT"
                                : best == LEFT_ROAD ? "SHORTCUT  -  MIND THE WALLS" : "WIDE ROAD",
                        best == RIGHT_ROAD ? ACCENT : 0xFFF5C518);
            }
            if (forkRoad >= 0 && x > forkEnd) {
                if (forkRoad == SLOT_ROAD) {
                    hunterGap += SLOT_GAIN;
                    shortcuts++;
                    score += 12 * pointMul;
                    popup("THREADED THE SLOT  +" + Math.round(SLOT_GAIN) + " m", 0xFFFF8A3D);
                    fx.burst(w / 2f, h * 0.7f, 30, dp(200f), 0.6f, dp(3f), 0xFFFF8A3D, false);
                } else if (forkRoad == LEFT_ROAD) {
                    hunterGap += SHORTCUT_GAIN;
                    shortcuts++;
                    score += 5 * pointMul;
                    popup("SHORTCUT  +" + Math.round(SHORTCUT_GAIN) + " m", 0xFFF5C518);
                    fx.burst(w / 2f, h * 0.7f, 26, dp(180f), 0.6f, dp(3f), 0xFFF5C518, false);
                } else {
                    popup("THROUGH THE WIDE ROAD", ACCENT);
                }
                forkStart = forkEnd + 300 + rng.nextFloat() * 200;
                forkEnd = forkStart + FORK_LEN;
                forkSlot = tier >= 1;
                forkRoad = -1;
            }
            if (scraping && sessionSeconds > scrapeUntil) {
                scrapeUntil = sessionSeconds + 0.6;
                scrapes++;
                cleanSinceCheck = false;
                hunterGap -= 7;
                shake.kick(dp(12f));
                // Knocked back off the rock, toward the nearest open lane.
                float away = spireHit ? (offLine > bandC[Math.max(0, hitBand)] ? 1f : -1f)
                        : (offLine > 0 ? -1f : 1f);
                lateral += away * 2.5f;
                fx.burst(w * 0.5f + away * w * (spireHit ? 0.08f : 0.34f), h * 0.62f, 22, dp(170f), 0.5f,
                        dp(3.5f), 0xFFD89A3A, false);
            }
            // Timing gates: the split medalled against this rower's own speeds, and a clean one
            // (no rock since the last gate) charges the boost.
            while (x >= nextCheckAt && nextCheckAt < EXIT_METRES) {
                double split = Math.max(0.1, activeSeconds - lastCheckSeconds);
                lastCheckSeconds = activeSeconds;
                checkIndex++;
                int medal = CanyonRouteMap.medal(split, CHECK_METRES, profile);
                if (medal == CanyonRouteMap.GOLD) {
                    golds++;
                    score += 6 * pointMul;
                } else if (medal == CanyonRouteMap.SILVER) {
                    silvers++;
                    score += 4 * pointMul;
                } else if (medal == CanyonRouteMap.BRONZE) {
                    bronzes++;
                    score += 2 * pointMul;
                }
                String extra = "";
                if (cleanSinceCheck && boostCharges < boostCapacity) {
                    boostCharges++;
                    extra = "  ·  CLEAN, BOOST " + boostCharges + "/" + boostCapacity;
                }
                cleanSinceCheck = true;
                popup("CP " + checkIndex + "  " + CanyonRouteMap.MEDAL_NAME[medal] + "  "
                        + CanyonRouteMap.time(split) + extra, CanyonRouteMap.MEDAL_COLOR[medal]);
                fx.burst(w / 2f, h * 0.66f, 22, dp(170f), 0.6f, dp(3f), CanyonRouteMap.MEDAL_COLOR[medal], false);
                nextCheckAt += CHECK_METRES;
            }
            // The hunter holds a little under the rower's own low pace and creeps faster.
            float hunterSpeed = (float) (profile.lowSpeed() * 0.85) + (float) (sessionSeconds - runStartSeconds) / 240f;
            hunterGap += (speed + (boosting() ? boostSpeed() : 0f) - hunterSpeed) * dt;
            hunterGap = Math.min(90, hunterGap);
            if (hunterGap <= 0) {
                over = true;
                saveRecords();
            } else if (x >= EXIT_METRES) {
                finishRun();
            }
        }
        shake.step(dt);
        fx.step(dt, 0f);

        float danger = (float) Math.max(0, 1 - hunterGap / 26.0);
        float horizon = h * 0.30f;
        float dayT = CanyonFlightGame.Daylight.progress(x + dayOffset);
        float dusk = CanyonFlightGame.Daylight.shade(dayT);
        int phase = CanyonFlightGame.Daylight.phaseIndex(dayT);
        if (phase != lastPhase) {
            if (started && phase > lastPhase) {
                popup(CanyonFlightGame.Daylight.phaseName(phase), 0xFFFFC27A);
            }
            lastPhase = phase;
        }

        c.save();
        c.translate(shake.dx, shake.dy);
        // Roll the world against the bank so turns feel banked rather than slid.
        c.rotate(-bank * 0.35f, w / 2f, h * 0.8f);

        // Sky, sun and haze: cached per step of the light, never per frame.
        if (skyShader == null || skyT != dayT || skyHorizon != horizon) {
            skyT = dayT;
            skyHorizon = horizon;
            skyShader = new LinearGradient(0, 0, 0, horizon, CanyonFlightGame.Daylight.top(dayT),
                    CanyonFlightGame.Daylight.horizon(dayT), Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(-w, -h, w * 2, horizon, paint);
        paint.setShader(null);
        drawSun(c, w, horizon, dayT);
        drawHorizonLife(c, w, horizon, dusk);

        // Canyon: walk segments from far to near so nearer geometry paints over farther.
        // Camera kept inside the walls. Drawing only: scraping and the hunter use the real offLine.
        float camLat = centre + Math.max(-(CANYON_HALF - 1.5f), Math.min(CANYON_HALF - 1.5f, offLine));
        int nightFloor = 0xFF1C1220;
        for (int i = SEGMENTS - 1; i >= 1; i--) {
            float zFar = i * SEG_LEN;
            float zNear = (i - 1) * SEG_LEN;
            // Sub-segment scroll so the world slides smoothly between whole segments.
            float frac = (float) ((x % SEG_LEN) / SEG_LEN);
            zFar -= frac * SEG_LEN;
            zNear -= frac * SEG_LEN;
            if (zNear < 1.2f) {
                zNear = 1.2f;
            }
            float sFar = FOCAL / zFar;
            float sNear = FOCAL / zNear;
            float yFar = horizon + sFar * h * 0.34f;
            float yNear = horizon + sNear * h * 0.34f;
            float cFar = centreAt(x + zFar) - camLat;
            float cNear = centreAt(x + zNear) - camLat;
            float k = w * 0.05f;

            float lFar = w / 2f + (cFar - leftWall(x + zFar)) * sFar * k;
            float rFar = w / 2f + (cFar + CANYON_HALF) * sFar * k;
            float lNear = w / 2f + (cNear - leftWall(x + zNear)) * sNear * k;
            float rNear = w / 2f + (cNear + CANYON_HALF) * sNear * k;

            float depth = i / (float) SEGMENTS;
            int floor = CanyonFlightGame.Daylight.blend(blend(0xFF6B4A2F, 0xFFC08A5A, depth), nightFloor, dusk);
            int wall = CanyonFlightGame.Daylight.blend(blend(0xFF3A2418, 0xFF8A5C3A, depth), nightFloor, dusk);

            // Floor quad.
            paint.setColor(i % 2 == 0 ? floor : blend(floor, 0xFF000000, 0.06f));
            path.reset();
            path.moveTo(lNear, yNear);
            path.lineTo(rNear, yNear);
            path.lineTo(rFar, yFar);
            path.lineTo(lFar, yFar);
            path.close();
            c.drawPath(path, paint);

            // Walls rising from the floor edges.
            float wallHNear = sNear * h * 0.9f;
            float wallHFar = sFar * h * 0.9f;
            paint.setColor(blend(wall, 0xFF000000, 0.18f));
            path.reset();
            path.moveTo(lNear, yNear);
            path.lineTo(lFar, yFar);
            path.lineTo(lFar, yFar - wallHFar);
            path.lineTo(lNear, yNear - wallHNear);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(wall);
            path.reset();
            path.moveTo(rNear, yNear);
            path.lineTo(rFar, yFar);
            path.lineTo(rFar, yFar - wallHFar);
            path.lineTo(rNear, yNear - wallHNear);
            path.close();
            c.drawPath(path, paint);

            float rampN = forkRamp(x + zNear);
            float rampF = forkRamp(x + zFar);
            // Racing line down the middle: dashes you can aim at. Not through a spire.
            if (i % 2 == 0 && rampN <= 0f && rampF <= 0f) {
                paint.setColor(0x99FFE28A);
                float mNear = w / 2f + cNear * sNear * k;
                float mFar = w / 2f + cFar * sFar * k;
                paint.setStrokeWidth(Math.max(1f, sNear * dp(7f)));
                c.drawLine(mNear, yNear, mFar, yFar, paint);
            }
            // Strata: a darker band part-way up each wall, so the walls read as rock.
            paint.setColor(0x33000000);
            float band = 0.45f;
            path.reset();
            path.moveTo(lNear, yNear - wallHNear * band);
            path.lineTo(lFar, yFar - wallHFar * band);
            path.lineTo(lFar, yFar - wallHFar * (band + 0.08f));
            path.lineTo(lNear, yNear - wallHNear * (band + 0.08f));
            path.close();
            c.drawPath(path, paint);
            path.reset();
            path.moveTo(rNear, yNear - wallHNear * band);
            path.lineTo(rFar, yFar - wallHFar * band);
            path.lineTo(rFar, yFar - wallHFar * (band + 0.08f));
            path.lineTo(rNear, yNear - wallHNear * (band + 0.08f));
            path.close();
            c.drawPath(path, paint);
            // Boulders near the walls, placed by segment so they stay put as you pass.
            int seg = (int) Math.floor((x + zNear) / SEG_LEN);
            if ((seg * 2654435761L & 7) < 3 && zNear > 2f) {
                boolean leftSide = ((seg * 40503) & 1) == 0;
                float along = leftSide ? -leftWall(x + zNear) + 3f : CANYON_HALF - 3f;
                float bx = w / 2f + (cNear + along) * sNear * k;
                float br = Math.max(dp(2f), sNear * dp(20f));
                paint.setColor(CanyonFlightGame.Daylight.blend(blend(0xFF4A3322, 0xFF9A7050, depth), nightFloor, dusk));
                c.drawOval(bx - br * 1.4f, yNear - br * 1.3f, bx + br * 1.4f, yNear + br * 0.2f, paint);
                paint.setColor(0x33FFFFFF);
                c.drawOval(bx - br * 0.9f, yNear - br * 1.15f, bx - br * 0.1f, yNear - br * 0.6f, paint);
            }
            // The fork's rock: one slab, or two with the slot between them.
            if (rampN > 0.02f || rampF > 0.02f) {
                int slabs = forkSlot ? 2 : 1;
                float halfN = (forkSlot ? SLOT_HALF : SPIRE) * rampN;
                float halfF = (forkSlot ? SLOT_HALF : SPIRE) * rampF;
                for (int sb = 0; sb < slabs; sb++) {
                    float off = forkSlot ? (sb == 0 ? -SLOT_OFFSET : SLOT_OFFSET) : 0f;
                    float aLN = w / 2f + (cNear + off - halfN) * sNear * k;
                    float aRN = w / 2f + (cNear + off + halfN) * sNear * k;
                    float aLF = w / 2f + (cFar + off - halfF) * sFar * k;
                    float aRF = w / 2f + (cFar + off + halfF) * sFar * k;
                    float hN = wallHNear * 0.8f;
                    float hF = wallHFar * 0.8f;
                    if (cNear + off - halfN > 0) {
                        paint.setColor(blend(wall, 0xFF000000, 0.3f));
                        path.reset();
                        path.moveTo(aLN, yNear);
                        path.lineTo(aLF, yFar);
                        path.lineTo(aLF, yFar - hF);
                        path.lineTo(aLN, yNear - hN);
                        path.close();
                        c.drawPath(path, paint);
                    }
                    if (cNear + off + halfN < 0) {
                        paint.setColor(blend(wall, 0xFF000000, 0.1f));
                        path.reset();
                        path.moveTo(aRN, yNear);
                        path.lineTo(aRF, yFar);
                        path.lineTo(aRF, yFar - hF);
                        path.lineTo(aRN, yNear - hN);
                        path.close();
                        c.drawPath(path, paint);
                    }
                    paint.setColor(blend(wall, 0xFFFFD9A8, 0.25f * (1f - dusk)));
                    path.reset();
                    path.moveTo(aLN, yNear - hN);
                    path.lineTo(aLF, yFar - hF);
                    path.lineTo(aRF, yFar - hF);
                    path.lineTo(aRN, yNear - hN);
                    path.close();
                    c.drawPath(path, paint);
                }
            }
        }
        drawCheckpoints(c, w, h, horizon, camLat);
        drawForkSigns(c, w, h, horizon, camLat);
        drawRival(c, w, h, horizon, camLat);

        // Your craft near the bottom, banking.
        // Clamped: pinned against a wall the craft used to be drawn far off-screen (seen on the
        // emulator at surge speed), so you could not see what was scraping.
        float shipX = Math.max(w * 0.12f, Math.min(w * 0.88f,
                w / 2f + offLine * (FOCAL / 6f) * w * 0.05f * 0.35f));
        float shipY = h * 0.80f;
        c.save();
        c.rotate(bank, shipX, shipY);
        Fx.glow(c, shipX, shipY, dp(52f), boostGlow > 0.2f ? 0x66FF8A3D : 0x3535D0BA);
        paint.setColor(scraping ? BAD : boostGlow > 0.2f ? 0xFFFFD36A : ACCENT);
        path.reset();
        path.moveTo(shipX, shipY - dp(26f));
        path.lineTo(shipX - dp(34f), shipY + dp(14f));
        path.lineTo(shipX - dp(11f), shipY + dp(6f));
        path.lineTo(shipX, shipY + dp(16f));
        path.lineTo(shipX + dp(11f), shipY + dp(6f));
        path.lineTo(shipX + dp(34f), shipY + dp(14f));
        path.close();
        c.drawPath(path, paint);
        // Thrust, longer with speed and much longer on boost.
        paint.setColor(0xFFFFD36A);
        float thrust = dp(8f) + speed * dp(9f) + boostGlow * dp(60f);
        c.drawRect(shipX - dp(6f), shipY + dp(14f), shipX + dp(6f), shipY + dp(14f) + thrust, paint);
        paint.setColor(0xFFFF7A3D);
        c.drawRect(shipX - dp(3f), shipY + dp(14f), shipX + dp(3f), shipY + dp(14f) + thrust * 0.6f, paint);
        c.restore();
        // Dust thrown up behind the craft, more with speed.
        dustClock += dt;
        if (live && speed > 1.5f && dustClock > 0.05) {
            dustClock = 0;
            for (int j = 0; j < 2; j++) {
                fx.spawn(shipX + (float) (Math.random() - 0.5) * dp(40f), shipY + dp(20f),
                        (float) (Math.random() - 0.5) * dp(80f), dp(40f) + speed * dp(20f),
                        0.7f, dp(4f) + (float) Math.random() * dp(4f), 0x88C89A6A, false);
            }
        }
        fx.draw(c);
        c.restore();

        // Close to a wall: its side of the screen warms, before the scrape.
        if (live && nearWall > 0.05f) {
            boolean rightSide = rightLimit - offLine < offLine - leftLimit;
            Fx.glow(c, rightSide ? w : 0f, h * 0.6f, w * 0.3f, ((int) (nearWall * 15) * 16 << 24) | 0xF0655D);
        }
        Fx.speedLines(c, paint, w, h, speed + boostGlow * 3f, sessionSeconds, dp(1f));
        Fx.vignette(c, w, h, 0.3f + danger * 0.6f, danger > 0.15f ? 0x7A0A0A : 0x000000);

        // Rear-view mirror: the hunter, larger as it closes.
        float mw = dp(150f);
        float mh = dp(58f);
        float mx = w / 2f - mw / 2f;
        float my = dp(8f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xCC0A0E14);
        c.drawRoundRect(mx, my, mx + mw, my + mh, dp(6f), dp(6f), paint);
        paint.setColor(danger > 0.4f ? BAD : 0xFF2A3648);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        c.drawRoundRect(mx, my, mx + mw, my + mh, dp(6f), dp(6f), paint);
        paint.setStyle(Paint.Style.FILL);
        float hs = dp(6f) + (float) Math.max(0, (90 - hunterGap)) / 90f * dp(26f);
        float hx = mx + mw / 2f - offLine * dp(1.6f);
        float hy = my + mh * 0.62f;
        paint.setColor(blend(0xFF6B3A8A, 0xFFFF3B5C, danger));
        path.reset();
        path.moveTo(hx, hy - hs);
        path.lineTo(hx - hs * 1.3f, hy + hs * 0.6f);
        path.lineTo(hx, hy + hs * 0.2f);
        path.lineTo(hx + hs * 1.3f, hy + hs * 0.6f);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFFF5C518);
        c.drawCircle(hx - hs * 0.35f, hy - hs * 0.25f, Math.max(dp(1.5f), hs * 0.16f), paint);
        c.drawCircle(hx + hs * 0.35f, hy - hs * 0.25f, Math.max(dp(1.5f), hs * 0.16f), paint);
        // The rival shows in the mirror too once you are past it.
        if (!rivalWasAhead && started) {
            float rs = dp(5f);
            float rx = mx + mw * 0.2f;
            paint.setColor(0xFFE8557F);
            path.reset();
            path.moveTo(rx, hy - rs);
            path.lineTo(rx - rs * 1.2f, hy + rs * 0.6f);
            path.lineTo(rx + rs * 1.2f, hy + rs * 0.6f);
            path.close();
            c.drawPath(path, paint);
        }
        label(c, "BEHIND YOU", mx + mw / 2f, my + dp(11f), 7.5f, FAINT, Paint.Align.CENTER);

        // HUD.
        String big;
        int col;
        if (!started) {
            big = "SOMETHING IS FOLLOWING";
            col = DIM;
        } else if (over) {
            big = "CAUGHT";
            col = BAD;
        } else if (finished) {
            big = "";
            col = TEXT;
        } else {
            big = Math.round(hunterGap) + " m";
            col = hunterGap > 40 ? ACCENT : hunterGap > 18 ? WARN : BAD;
        }
        if (big.length() > 0) {
            bold(c, big, w / 2f, my + mh + dp(26f), started && !over ? 34f : 20f, col, Paint.Align.CENTER);
        }
        boolean sensor = hasSteering();
        double toFork = forkStart - x;
        String cap;
        int capCol;
        if (!started) {
            cap = sensor ? "take a stroke - tilt the handle to steer" : "take a stroke - your pace steers you across the canyon";
            capCol = FAINT;
        } else if (over) {
            cap = "ran " + Math.round(x) + " m  ·  " + scrapes + " scrapes  ·  " + shortcuts + " shortcuts  ·  tap to run again";
            capCol = BAD;
        } else if (finished) {
            cap = "";
            capCol = TEXT;
        } else if (boosting()) {
            cap = "BOOST  -  NOTHING CAN TOUCH YOU";
            capCol = 0xFFFF8A3D;
        } else if (scraping) {
            cap = spireHit ? "ON THE ROCK" : "SCRAPING THE WALL";
            capCol = BAD;
        } else if (forkRoad < 0 && toFork < 90) {
            cap = "FORK IN " + Math.max(0, Math.round(toFork)) + " m  ·  "
                    + (forkSlot
                    ? (sensor ? "LEFT SHORTCUT, CENTRE SLOT, RIGHT WIDE" : "EASE OFF FOR THE SHORTCUT, HOLD YOUR PACE FOR THE SLOT")
                    : (sensor ? "STEER LEFT FOR THE SHORTCUT, RIGHT FOR THE WIDE ROAD"
                    : "EASE OFF FOR THE SHORTCUT, PULL FOR THE WIDE ROAD"));
            capCol = 0xFFF5C518;
        } else if (forkRoad == SLOT_ROAD) {
            cap = sensor ? "THE SLOT - DEAD STRAIGHT" : "THE SLOT - HOLD EXACTLY THIS PACE";
            capCol = 0xFFFF8A3D;
        } else if (forkRoad == LEFT_ROAD) {
            cap = sensor ? "SHORTCUT - HOLD IT STRAIGHT" : "SHORTCUT - HOLD THIS EASY PACE";
            capCol = 0xFFF5C518;
        } else if (Math.abs(offLine) < 3f || forkRoad == RIGHT_ROAD) {
            cap = forkRoad == RIGHT_ROAD ? "WIDE ROAD - KEEP RIGHT OF THE ROCK" : "ON THE LINE";
            capCol = ACCENT;
        } else if (sensor) {
            cap = offLine > 0 ? "STEER LEFT" : "STEER RIGHT";
            capCol = FAINT;
        } else {
            cap = offLine > 0 ? "EASE OFF TO COME LEFT" : "PULL HARDER TO GO RIGHT";
            capCol = FAINT;
        }
        if (cap.length() > 0) {
            bold(c, cap, w / 2f, my + mh + dp(42f), 11f, capCol, Paint.Align.CENTER);
        }
        // The race against the rival, and how much canyon is left.
        if (started && !finished && !over) {
            float lead = (float) (x - rivalX);
            label(c, (lead >= 0 ? "LEADING BY " + Math.round(lead) + " m" : "RIVAL AHEAD BY " + Math.round(-lead) + " m")
                            + "   ·   " + Math.max(0, Math.round(EXIT_METRES - x)) + " m TO THE EXIT   ·   "
                            + golds + "G " + silvers + "S " + bronzes + "B",
                    w / 2f, my + mh + dp(58f), 9f, lead >= 0 ? ACCENT : 0xFFE8557F, Paint.Align.CENTER);
        }
        if (sessionSeconds < rivalTauntUntil) {
            bold(c, rivalTaunt, w / 2f, h * 0.34f, 16f, 0xFFFF9ABF, Paint.Align.CENTER);
        }
        if (sessionSeconds < popupUntil) {
            float rise = (float) (1.6 - (popupUntil - sessionSeconds)) * dp(24f);
            bold(c, popupText, w / 2f, h * 0.5f - rise, 24f, popupColor, Paint.Align.CENTER);
        }

        drawPositionBar(c, w, h, offLine, leftLimit, rightLimit, sensor);
        drawBoostMeter(c, w, h);
        float mapW = Math.min(dp(230f), w * 0.28f);
        map.draw(c, dp(16f), dp(20f), mapW, (float) (x / EXIT_METRES), forkChoices,
                Math.max(5, forksTaken), ROAD_NAMES, unlockScore,
                (int) (EXIT_METRES / CHECK_METRES) - 1, checkIndex, golds);

        float fy = h - dp(12f);
        float col3 = w / 3f;
        stat(c, col3 * 0.5f, fy, Math.round(x) + " m", "RUN");
        stat(c, col3 * 1.5f, fy, String.valueOf(score), "SCORE");
        stat(c, col3 * 2.5f, fy, bestExitText, "BEST EXIT");
        label(c, CanyonFlightGame.Daylight.phaseName(phase) + "  ·  " + clock(activeSeconds),
                w - dp(14f), dp(22f), 8.5f, 0xFFFFC27A, Paint.Align.RIGHT);

        if (finished) {
            float age = (float) (sessionSeconds - finishAt);
            map.photoFinish(c, w, h, Math.max(0f, 1f - age / 0.45f), finishTitle, finishMedal,
                    finishTimeText, finishLine1, finishLine2,
                    ACCENT, 0xFFE8557F, finishMargin, finishSeconds);
        }
    }

    /** The boost meter: charges from clean timing gates, tap to spend them. */
    private void drawBoostMeter(Canvas c, float w, float h) {
        float bw = dp(168f);
        float bh = dp(16f);
        float bx = dp(16f);
        float by = h - dp(104f);
        boostHitX = bx;
        boostHitY = by;
        boostHitW = bw;
        boostHitH = bh;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA0A0E14);
        c.drawRoundRect(bx - dp(8f), by - dp(16f), bx + bw + dp(8f), by + bh + dp(8f), dp(6f), dp(6f), paint);
        float cell = (bw - dp(4f) * (boostCapacity - 1)) / boostCapacity;
        for (int i = 0; i < boostCapacity; i++) {
            float cx = bx + i * (cell + dp(4f));
            boolean lit = i < boostCharges;
            paint.setColor(lit ? 0xFFFF8A3D : 0x332A3648);
            c.drawRoundRect(cx, by, cx + cell, by + bh, dp(3f), dp(3f), paint);
            if (lit) {
                Fx.glow(c, cx + cell / 2f, by + bh / 2f, dp(24f), 0x55FF8A3D);
            }
        }
        if (boosting()) {
            float left = (float) ((boostUntil - sessionSeconds) / (BOOST_PER_CHARGE * boostCapacity));
            paint.setColor(0xFFFFD36A);
            c.drawRoundRect(bx, by, bx + bw * Math.max(0f, Math.min(1f, left)), by + bh, dp(3f), dp(3f), paint);
        }
        String hint = boosting() ? "BOOSTING" : boostCharges > 0 ? "TAP TO BOOST"
                : cleanSinceCheck ? "CLEAN - THE NEXT GATE CHARGES THE BOOST" : "ROCK TOUCHED - NO CHARGE THIS GATE";
        label(c, hint, bx, by - dp(4f), 8.5f,
                boosting() ? 0xFFFFD36A : boostCharges > 0 ? 0xFFFF8A3D : cleanSinceCheck ? ACCENT : FAINT,
                Paint.Align.LEFT);
    }

    /**
     * Where you are across the canyon, as a bar: walls, the rock when a fork is near, your
     * craft, and for pace steering the speed that would put you on the line.
     */
    private void drawPositionBar(Canvas c, float w, float h, float offLine, float leftLimit, float rightLimit,
                                 boolean sensor) {
        float bw = dp(300f);
        float bx = w / 2f - bw / 2f;
        float by = h - dp(58f);
        float span = CANYON_HALF * 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA0A0E14);
        c.drawRoundRect(bx - dp(6f), by - dp(9f), bx + bw + dp(6f), by + dp(9f), dp(6f), dp(6f), paint);
        // Safe band between the scrape limits.
        float sl = bx + (leftLimit + CANYON_HALF) / span * bw;
        float sr = bx + (rightLimit + CANYON_HALF) / span * bw;
        paint.setColor(0x4435D0BA);
        c.drawRect(sl, by - dp(4f), sr, by + dp(4f), paint);
        paint.setColor(0xFFD89A3A);
        c.drawRect(bx, by - dp(6f), sl, by + dp(6f), paint);
        c.drawRect(sr, by - dp(6f), bx + bw, by + dp(6f), paint);
        // The rock, from 120 m before a fork to its end.
        if (forkRoad >= 0 || forkStart - x < 120) {
            int slabs = forkSlot ? 2 : 1;
            float half = (forkSlot ? SLOT_HALF : SPIRE) + ROCK_MARGIN;
            paint.setColor(forkRoad < 0 ? 0xAAF5C518 : 0xFFD89A3A);
            for (int i = 0; i < slabs; i++) {
                float off = forkSlot ? (i == 0 ? -SLOT_OFFSET : SLOT_OFFSET) : 0f;
                c.drawRect(bx + (off - half + CANYON_HALF) / span * bw, by - dp(6f),
                        bx + (off + half + CANYON_HALF) / span * bw, by + dp(6f), paint);
            }
        }
        paint.setColor(0x88FFE28A);
        c.drawRect(w / 2f - dp(1f), by - dp(8f), w / 2f + dp(1f), by + dp(8f), paint);
        float px = bx + Math.max(0f, Math.min(1f, (offLine + CANYON_HALF) / span)) * bw;
        paint.setColor(TEXT);
        c.drawCircle(px, by, dp(6f), paint);
        paint.setColor(ACCENT);
        c.drawCircle(px, by, dp(3.5f), paint);
        String hint = sensor ? "HANDLE STEERING"
                : String.format(java.util.Locale.US, "PACE STEERING  ·  %.2f m/s holds the line", paceCentre());
        label(c, hint, w / 2f, by - dp(13f), 8f, FAINT, Paint.Align.CENTER);
    }

    /** Timing arches down the canyon, and the chequered exit at the end of it. */
    private void drawCheckpoints(Canvas c, float w, float h, float horizon, float camLat) {
        float k = w * 0.05f;
        for (int i = 1; i * CHECK_METRES <= EXIT_METRES; i++) {
            float at = i * CHECK_METRES;
            float z = (float) (at - x);
            if (z < 2f || z > SEGMENTS * SEG_LEN * 0.85f) {
                continue;
            }
            boolean exit = at >= EXIT_METRES - 0.5f;
            float s = FOCAL / z;
            float y = horizon + s * h * 0.34f;
            float cc = centreAt(at) - camLat;
            float lx = w / 2f + (cc - leftWall(at)) * s * k;
            float rx = w / 2f + (cc + CANYON_HALF) * s * k;
            float top = y - s * h * 0.62f;
            paint.setStyle(Paint.Style.FILL);
            int col = exit ? 0xFFF2F2EE : 0xFFF5C518;
            // Posts and lintel.
            float post = Math.max(dp(2f), s * dp(9f));
            paint.setColor(col);
            c.drawRect(lx - post, top, lx + post, y, paint);
            c.drawRect(rx - post, top, rx + post, y, paint);
            c.drawRect(lx, top - post * 2f, rx, top + post, paint);
            // A light curtain to fly through.
            paint.setColor((col & 0x00FFFFFF) | 0x22000000);
            c.drawRect(lx, top, rx, y, paint);
            if (exit) {
                for (int q = 0; q * (rx - lx) / 10f < rx - lx; q++) {
                    paint.setColor((q % 2 == 0) ? 0xFFF2F2EE : 0xFF1B1526);
                    float qx = lx + q * (rx - lx) / 10f;
                    c.drawRect(qx, top - post * 4f, qx + (rx - lx) / 10f, top - post * 2f, paint);
                }
                if (s * 26f > 7f) {
                    bold(c, "CANYON EXIT", (lx + rx) / 2f, top - post * 6f, Math.min(20f, s * 26f), 0xFFF2F2EE,
                            Paint.Align.CENTER);
                }
            } else if (s * 22f > 7f) {
                bold(c, "CP " + i, (lx + rx) / 2f, top - post * 3f, Math.min(18f, s * 22f), col, Paint.Align.CENTER);
            }
        }
    }

    /** The rival craft ahead of you, drawn in perspective, with whatever it is shouting back. */
    private void drawRival(Canvas c, float w, float h, float horizon, float camLat) {
        float z = (float) (rivalX - x);
        if (z < 2.5f || z > SEGMENTS * SEG_LEN * 0.9f) {
            return;
        }
        float s = FOCAL / z;
        float k = w * 0.05f;
        // It runs the racing line, weaving a little.
        float lat = (float) Math.sin(rivalX / 33.0) * 4.5f;
        float rx = w / 2f + (centreAt(rivalX) + lat - camLat) * s * k;
        float ry = horizon + s * h * 0.34f - s * h * 0.06f;
        float size = Math.max(dp(5f), s * dp(34f));
        paint.setStyle(Paint.Style.FILL);
        Fx.glow(c, rx, ry, size * 2.2f, 0x44E8557F);
        paint.setColor(0xFFE8557F);
        path.reset();
        path.moveTo(rx, ry - size);
        path.lineTo(rx - size * 1.3f, ry + size * 0.55f);
        path.lineTo(rx - size * 0.4f, ry + size * 0.22f);
        path.lineTo(rx, ry + size * 0.62f);
        path.lineTo(rx + size * 0.4f, ry + size * 0.22f);
        path.lineTo(rx + size * 1.3f, ry + size * 0.55f);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFFFFD36A);
        c.drawRect(rx - size * 0.22f, ry + size * 0.55f, rx + size * 0.22f, ry + size * 1.1f, paint);
        // Dust it kicks up.
        paint.setColor(0x55C89A6A);
        c.drawOval(rx - size * 1.6f, ry + size * 0.5f, rx + size * 1.6f, ry + size * 1.4f, paint);
        if (size > dp(9f)) {
            label(c, Math.round(z) + " m", rx, ry - size * 1.4f, 9f, 0xFFFF9ABF, Paint.Align.CENTER);
        }
    }

    /** Signs at the mouth of the next fork, drawn in perspective as it approaches. */
    private void drawForkSigns(Canvas c, float w, float h, float horizon, float camLat) {
        if (forkRoad >= 0) {
            return;
        }
        float z = (float) (forkStart - x);
        if (z < 3f || z > SEGMENTS * SEG_LEN * 0.8f) {
            return;
        }
        float s = FOCAL / z;
        float k = w * 0.05f;
        float y = horizon + s * h * 0.34f - s * h * 0.35f;
        float cx = w / 2f + (centreAt(forkStart) - camLat) * s * k;
        float size = Math.max(7f, Math.min(20f, s * 26f));
        float bwid = dp(size * 7f);
        float bht = dp(size * 1.9f);
        int roads = forkSlot ? 3 : 2;
        for (int r = 0; r < roads; r++) {
            float sx = cx + roadCentre(r) * s * k;
            int col = r == LEFT_ROAD ? 0xFFF5C518 : r == SLOT_ROAD ? 0xFFFF8A3D : ACCENT;
            String text = r == LEFT_ROAD ? "< SHORTCUT +12m"
                    : r == SLOT_ROAD ? "SLOT +26m" : "WIDE ROAD >";
            float bwr = r == SLOT_ROAD ? bwid * 0.62f : bwid;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xDD1A1016);
            c.drawRoundRect(sx - bwr / 2f, y - bht / 2f, sx + bwr / 2f, y + bht / 2f, dp(4f), dp(4f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(size * 0.12f)));
            paint.setColor(col);
            c.drawRoundRect(sx - bwr / 2f, y - bht / 2f, sx + bwr / 2f, y + bht / 2f, dp(4f), dp(4f), paint);
            paint.setStyle(Paint.Style.FILL);
            bold(c, text, sx, y + dp(size * 0.35f), r == SLOT_ROAD ? size * 0.85f : size, col, Paint.Align.CENTER);
        }
    }

    /** The sun sinks toward the rim with the run; stars come out after it sets. */
    private void drawSun(Canvas c, float w, float horizon, float t) {
        float stars = CanyonFlightGame.Daylight.stars(t);
        if (stars > 0f) {
            for (int i = 0; i < 26; i++) {
                float sx = ((i * 7919) % 1000) / 1000f * w;
                float sy = ((i * 104729) % 1000) / 1000f * horizon * 0.85f;
                float tw = 0.6f + 0.4f * (float) Math.sin(sessionSeconds * 2 + i);
                paint.setColor(((int) (stars * tw * 220) << 24) | 0xFFFFFF);
                c.drawCircle(sx, sy, dp(1.1f + (i % 3) * 0.5f), paint);
            }
        }
        float drop = CanyonFlightGame.Daylight.sunDrop(t);
        float r = dp(26f + 8f * Math.min(1f, t));
        float sunX = w * 0.62f;
        float sunY = horizon * (0.22f + 0.72f * Math.min(1f, drop)) + Math.max(0f, drop - 1f) * r * 4f;
        int sun = CanyonFlightGame.Daylight.sun(t);
        Fx.glow(c, sunX, Math.min(sunY, horizon), w * 0.3f, (sun & 0x00FFFFFF) | 0x66000000);
        c.save();
        c.clipRect(-w, -horizon * 2f, w * 2f, horizon);
        paint.setColor(sun);
        c.drawCircle(sunX, sunY, r, paint);
        c.restore();
    }

    /** Flat-topped mesas along the horizon and a pair of vultures circling above the canyon. */
    private void drawHorizonLife(Canvas c, float w, float horizon, float dusk) {
        float shift = (float) ((x * 0.4) % (w * 1.2));
        paint.setColor(CanyonFlightGame.Daylight.blend(0xFF6A4638, 0xFF241626, dusk));
        for (int k = -1; k < 5; k++) {
            float mx = k * w * 0.3f - shift * 0.3f;
            float mh = dp(28f) + ((k * 7 + 21) % 3) * dp(14f);
            float mw = dp(70f) + ((k * 5 + 15) % 3) * dp(30f);
            path.reset();
            path.moveTo(mx - mw, horizon + dp(2f));
            path.lineTo(mx - mw * 0.7f, horizon - mh);
            path.lineTo(mx + mw * 0.7f, horizon - mh);
            path.lineTo(mx + mw, horizon + dp(2f));
            path.close();
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(0xCC1A1010);
        for (int v = 0; v < 2; v++) {
            double a = sessionSeconds * (0.6 + v * 0.25) + v * 2;
            float vx = w * (0.35f + v * 0.3f) + (float) Math.cos(a) * dp(60f);
            float vy = horizon * (0.45f + v * 0.12f) + (float) Math.sin(a) * dp(18f);
            float flap = (float) Math.sin(sessionSeconds * 3 + v) * dp(4f);
            c.drawLine(vx - dp(14f), vy - flap, vx, vy, paint);
            c.drawLine(vx, vy, vx + dp(14f), vy - flap, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static int blend(int a, int b, float t) {
        return CanyonFlightGame.Daylight.blend(a, b, t);
    }

    private void stat(Canvas c, float px, float py, String value, String caption) {
        bold(c, value, px, py - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, px, py + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
