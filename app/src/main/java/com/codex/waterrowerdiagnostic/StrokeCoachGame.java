package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import java.util.Arrays;
import java.util.Locale;

/**
 * STROKE COACH: the shape of every stroke, against your best one, with one thing to work on.
 *
 * <p>Built on {@link PulseMeter}: each drive's <em>power</em> curve at 100 ms resolution. Nothing else
 * on this machine can show it - the monitor's own readings refresh once a stroke at best.
 *
 * <p>Power, not paddle speed. The first version drew speed, and on the tablet every stroke scored
 * "peak arrives late" with the peak at 100%: a paddle keeps accelerating for as long as the hand
 * pushes harder than the water drags, so its speed always peaks at the very end of the drive. The
 * coaching question - where in the drive the effort peaks - is answered by power. Before energy is
 * measured, the paddle's acceleration is used instead, which peaks at the same moment force does.
 *
 * <p>Per stroke it scores three things, 0-100: how closely the drive's shape matches your best
 * stroke, how near the drive:recovery ratio is to the coaching target of 1:2, and how consistent the
 * last eight strokes were. The best stroke is the most powerful one with a sensible ratio, kept
 * across sessions. One tip at a time, the most important first.
 *
 * <p>3.20 additions, all drawn and tapped inside this view (no header chips):
 * <ul>
 *   <li><b>Best vs latest</b> - two panels side by side that replay both drives at their real
 *   durations, a playhead sweeping catch to finish, so a slower or later-peaking drive is visible
 *   as it happens.</li>
 *   <li><b>Drills</b> - tap a drill: ten strokes, each judged pass/fail as it lands, eight to pass.
 *   The drill ends early once eight is out of reach, so every stroke matters.</li>
 *   <li><b>Stick rower</b> - legs, back and arms sequence through the drive at the pace of your own
 *   last stroke (drive time, recovery time and how the handle travel accumulated through the drive),
 *   and a short stroke shows as a figure that does not compress fully at the catch.</li>
 *   <li><b>Consistency map</b> - every stroke of the session as one column of its power curve, so a
 *   settled piece reads as smooth horizontal bands, with the stroke score underneath.</li>
 *   <li><b>Drive length</b> - with the handle calibrated ({@code cal.handle}), each stroke's length
 *   against your own reach, learned across sessions.</li>
 * </ul>
 *
 * <p>3.23 additions, all drawn and tapped inside this view:
 * <ul>
 *   <li><b>One live cue</b> - the worst fault in your stroke, held on screen until <em>three</em>
 *   clean strokes retire it, then celebrated and replaced by the next one. The old tip line changed
 *   every stroke and so could never be worked on.</li>
 *   <li><b>The challenge</b> - a sixth chip beside the drills: hold a 90% shape match for twenty
 *   strokes. One stroke under 90% and the chain breaks back to zero, which is the stake.</li>
 *   <li><b>Badges in a skill tree</b> - nine badges across five tiers, unlocked by the drills, the
 *   session's consistency and the challenge. Locked ones show what they need and how far along you
 *   are; earning one stops the screen.</li>
 *   <li><b>Weekly report card</b> - this week graded against last week, per category, with the bars
 *   growing into place and the deltas called out.</li>
 *   <li><b>Best session, not just best stroke</b> - the average shape of your best session is kept
 *   and drawn as a third curve on the latest panel, with a live TODAY vs BEST SESSION strip.</li>
 * </ul>
 * The bottom band pages between the consistency map, the skill tree and the report card.
 */
final class StrokeCoachGame extends GameView {

    private static final int SHAPE = 32;
    private static final int RECENT = 8;
    private static final int SCORES = 30;
    /** Strokes kept on the consistency map; older ones scroll off the left. */
    private static final int MAP = 240;
    /** Map rows: the power curve, one gap row, then four rows of stroke score. */
    private static final int MAP_ROWS = SHAPE + 5;

    // Drills: ten strokes, eight to pass.
    private static final int DRILL_STROKES = 10;
    private static final int DRILL_PASS = 8;
    private static final String[] DRILL_NAME = {"LEGS FIRST", "RATIO 1:2", "SAME STROKE", "PRESSURE", "FULL REACH"};
    private static final String[] DRILL_KEY = {"legs", "ratio", "same", "pressure", "reach"};
    private static final int DRILL_LEGS = 0;
    private static final int DRILL_RATIO = 1;
    private static final int DRILL_SAME = 2;
    private static final int DRILL_PRESSURE = 3;
    private static final int DRILL_REACH = 4;

    /** The challenge sits beside the drills as a sixth chip: hold the shape for twenty strokes. */
    private static final int CHALLENGE = DRILL_NAME.length;
    private static final int CHIPS = DRILL_NAME.length + 1;
    private static final int CHALLENGE_TARGET = 20;
    private static final float CHALLENGE_MATCH = 90f;

    // One live cue at a time, held until three clean strokes retire it.
    private static final int CUE_NONE = -1;
    private static final int CUE_LATE = 0;
    private static final int CUE_EARLY = 1;
    private static final int CUE_RUSH = 2;
    private static final int CUE_SHORT = 3;
    private static final int CUE_PAUSE = 4;
    private static final int CUE_VARY = 5;
    private static final int CUE_FADE = 6;
    private static final int CUES = 7;
    private static final int CUE_CLEAN_NEEDED = 3;
    private static final String[] CUE_TITLE = {
            "LEGS FIRST", "FINISH IT", "SLOW THE SLIDE", "REACH FURTHER",
            "KEEP IT MOVING", "SAME STROKE", "MORE PRESSURE"};
    private static final String[] CUE_BODY = {
            "Your power peaks late. Drive with the legs first, then swing the back, then draw the arms.",
            "Your power peaks too early. Keep pushing all the way through to the finish.",
            "You are rushing back to the catch. Let the slide take about twice as long as the drive.",
            "You are stopping short of your own reach. Come all the way up the slide at the catch.",
            "You are sitting at the catch. Take the next stroke sooner and keep the boat running.",
            "Every stroke is a different shape. Settle down and repeat the last one exactly.",
            "The pressure has dropped. Lengthen the stroke and push harder through the water."};
    private static final String[] CUE_DONE = {
            "LEGS LEADING", "DRIVING THROUGH", "SLIDE UNDER CONTROL", "FULL LENGTH",
            "BOAT MOVING", "REPEATABLE", "PRESSURE BACK"};

    // Badges: a small skill tree, five tiers deep. A badge needs its own feat and its prerequisites.
    private static final int BADGES = 9;
    private static final String[] BADGE_NAME = {
            "LEGS FIRST", "RHYTHM", "FULL REACH", "REPEATER", "PRESSURE",
            "STEADY", "FLAWLESS", "LOCKED IN", "STROKE MASTER"};
    private static final String[] BADGE_NEED = {
            "Pass the LEGS FIRST drill", "Pass the RATIO 1:2 drill", "Pass the FULL REACH drill",
            "Pass the SAME STROKE drill", "Pass the PRESSURE drill",
            "80% session consistency over 30 strokes", "Take any drill to 10 out of 10",
            "Hold a 90% shape match for 20 strokes", "Earn every other badge"};
    /** Which drill earns each badge, or -1 when the feat is something else. */
    private static final int[] BADGE_DRILL = {
            DRILL_LEGS, DRILL_RATIO, DRILL_REACH, DRILL_SAME, DRILL_PRESSURE, -1, -1, -1, -1};
    private static final int[] BADGE_TIER = {0, 0, 0, 1, 1, 2, 2, 3, 4};
    private static final int[] BADGE_PREREQ = {0, 0, 0, 1, 2, 8, 16, 32 | 64, 255};
    private static final int BADGE_TIERS = 5;
    private static final int BADGE_STEADY = 5;
    private static final int BADGE_FLAWLESS = 6;
    private static final int BADGE_LOCKED_IN = 7;
    private static final int BADGE_MASTER = 8;
    /** Session consistency and stroke count the STEADY badge asks for. */
    private static final float STEADY_PERCENT = 80f;
    private static final int STEADY_STROKES = 30;

    // Bottom band pages.
    private static final int PAGE_MAP = 0;
    private static final int PAGE_SKILLS = 1;
    private static final int PAGE_REPORT = 2;
    private static final String[] PAGE_NAME = {"MAP", "SKILLS", "REPORT"};
    /** Report card rows: shape, consistency, ratio and overall score. */
    private static final String[] REPORT_ROW = {"SHAPE MATCH", "CONSISTENCY", "RATIO 1:2", "STROKE SCORE"};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint();
    private final Path path = new Path();

    // 3.25 graphics pass: the glass-instrument look. Every gradient comes from a small cache keyed
    // by its geometry and colours, so a steady frame allocates nothing.
    private final Paint fxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ovalRect = new RectF();
    private static final int SHADER_SLOTS = 64;
    private final long[] shaderKeys = new long[SHADER_SLOTS];
    private final Shader[] shaderCache = new Shader[SHADER_SLOTS];
    private int shaderCount;
    private int shaderNext;
    private static final int GRAD_TWO = 0;
    private static final int GRAD_THREE = 1;
    private static final int GRAD_BAND = 2;
    private static final int GRAD_RADIAL = 3;
    private static final int PANEL_TOP = 0xFF142030;
    private static final int PANEL_MID = 0xFF0D1522;
    private static final int PANEL_BOT = 0xFF0A101A;
    /** The score bar, eased so a new score slides in rather than jumping. */
    private float scoreBarShown;

    private final float[] best = new float[SHAPE];
    private boolean hasBest;
    private float bestPower;
    private float bestDriveSec;
    private float bestLength = Float.NaN;
    private final float[][] recent = new float[RECENT][SHAPE];
    private int recentCount;
    private final float[] scores = new float[SCORES];
    private int scoreCount;
    private int scoreHead;
    private PulseMeter.Stroke lastSeen;

    private float lastScore;
    private float similarity;
    private float ratioScore;
    private float consistency;
    private float peakPos;
    private int strokes;
    private float scoreSum;

    // Latest stroke, as the replay and the stick rower use it.
    private float lastDriveSec;
    private float lastRecoverySec;
    private float lastPower = Float.NaN;
    private float lastLength = Float.NaN;
    /** Handle travel through the last drive, 0..1, from the paddle rates: the figure's pacing. */
    private final float[] travel = new float[SHAPE];
    private boolean hasTravel;
    private final float[] scratch = new float[SHAPE];

    // Replay of best and latest, side by side.
    private float replayClock;
    private float latestFlash;

    // Drive length against the rower's reach.
    private final float[] lengths = new float[MAP];
    private int lengthCount;
    private final float[] lengthSorted = new float[MAP];
    private float sessionReachP90 = Float.NaN;
    private float storedReach;
    private float lengthShown;

    // Consistency map.
    private final float[][] mapShapes = new float[MAP][SHAPE];
    private final float[] mapScores = new float[MAP];
    private int mapCount;
    private final float[] sessionMean = new float[SHAPE];
    private float sessionConsistency = Float.NaN;
    private final int[] mapPixels = new int[MAP * MAP_ROWS];
    private Bitmap mapBitmap;
    private boolean mapDirty;
    private final Rect mapSrc = new Rect();
    private final RectF mapDst = new RectF();
    /** Reused for every arc drawn in the skill tree - nothing is allocated in the frame loop. */
    private final RectF arcRect = new RectF();
    private final int[] heat = new int[64];

    // Drills.
    private int drill = -1;
    private int drillDone;
    private int drillPassed;
    private final boolean[] drillResults = new boolean[DRILL_STROKES];
    private String drillVerdict = "";
    private float stampTimer;
    private boolean stampPassed;
    private String stampText = "";
    private float lastJudgeTimer;
    private boolean lastJudgePass;
    private final float[] chipL = new float[CHIPS];
    private final float[] chipT = new float[CHIPS];
    private final float[] chipR = new float[CHIPS];
    private final float[] chipB = new float[CHIPS];
    private final float[] stopHit = new float[4];
    /** Drill bests and the session record, read once per session rather than from prefs every frame. */
    private final float[] drillBest = new float[DRILL_NAME.length];
    private float bestSessionScore = Float.NaN;

    // The challenge: hold a 90% shape match for twenty strokes, a miss breaks the chain.
    private boolean challengeOn;
    private int challengeRun;
    private int challengeBestRun;
    private float challengeRecord = -1f;
    private float challengeFlash;
    private float challengeBreak;
    private boolean challengeLastPass;
    private float challengeMatchShown;

    // One live cue at a time.
    private int cue = CUE_NONE;
    private int cueClean;
    private int cueStrokes;
    private float cueSeverity;
    private int cueFixedIndex = CUE_NONE;
    private float cueFixedTimer;
    private int cuesFixed;
    private float cueArrive;
    private final float[] cueSev = new float[CUES];

    // Badges.
    private int badges;
    private int badgeBanner = -1;
    private float badgeBannerTimer;
    /** A badge earned but not yet looked at, so the SKILLS tab keeps asking to be opened. */
    private boolean badgeUnseen;
    private final float[] badgeFlash = new float[BADGES];
    private final float[] badgeX = new float[BADGES];
    private final float[] badgeY = new float[BADGES];
    private final Fx.Particles confetti = new Fx.Particles();

    // Best session, not just best stroke.
    private boolean hasBestSession;
    private final float[] bestSessionShape = new float[SHAPE];
    private float bsScore;
    private float bsPower;
    private float bsDrive;
    private float bsRatio;
    private float bsConsistency;
    private int bsStrokes;
    private long bsDay;
    private float sessionMatch = Float.NaN;
    private float sessionPowerSum;
    private int sessionPowerCount;
    private float sessionRatioSum;
    private float sessionDriveSum;
    /** Eased figures for the TODAY vs BEST SESSION strip, so the bars move rather than jump. */
    private final float[] compareShown = new float[4];

    // Weekly report card.
    private long weekIndex;
    private long prevWeekIndex = Long.MIN_VALUE;
    /** strokes, score sum, shape sum, consistency sum, ratio-score sum, drills passed, minutes. */
    private final float[] week = new float[7];
    private final float[] prevWeek = new float[7];
    private float weekSavedStrokes;
    /** Eased report bars, this week then last week. */
    private final float[] reportShown = new float[REPORT_ROW.length];

    // Bottom band paging.
    private int page = PAGE_MAP;
    private float pageAnim;
    private final float[] tabL = new float[PAGE_NAME.length];
    private final float[] tabT = new float[PAGE_NAME.length];
    private final float[] tabR = new float[PAGE_NAME.length];
    private final float[] tabB = new float[PAGE_NAME.length];

    StrokeCoachGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        bitmapPaint.setFilterBitmap(false);
        // Heat ramp for the consistency map: navy, blue, teal, then warm at full power.
        int[] stops = {0xFF0B1220, 0xFF26407A, 0xFF6F8CFF, 0xFF35D0BA, 0xFFF0E27A};
        for (int i = 0; i < heat.length; i++) {
            float f = i / (heat.length - 1f) * (stops.length - 1);
            int a = Math.min(stops.length - 2, (int) f);
            heat[i] = mix(stops[a], stops[a + 1], f - a);
        }
    }

    @Override
    protected void onStart() {
        recentCount = 0;
        scoreCount = 0;
        scoreHead = 0;
        strokes = 0;
        scoreSum = 0;
        mapCount = 0;
        lengthCount = 0;
        sessionReachP90 = Float.NaN;
        sessionConsistency = Float.NaN;
        hasTravel = false;
        drill = -1;
        stampTimer = 0;
        challengeOn = false;
        challengeRun = 0;
        challengeBestRun = 0;
        challengeBreak = 0f;
        challengeFlash = 0f;
        challengeMatchShown = 0f;
        cue = CUE_NONE;
        cueClean = 0;
        cueStrokes = 0;
        cueFixedIndex = CUE_NONE;
        cueFixedTimer = 0f;
        cuesFixed = 0;
        sessionPowerSum = 0f;
        sessionPowerCount = 0;
        sessionRatioSum = 0f;
        sessionDriveSum = 0f;
        sessionMatch = Float.NaN;
        weekSavedStrokes = 0f;
        badgeBanner = -1;
        badgeBannerTimer = 0f;
        badgeUnseen = false;
        Arrays.fill(badgeFlash, 0f);
        Arrays.fill(compareShown, 0f);
        Arrays.fill(reportShown, 0f);
        page = PAGE_MAP;
        pageAnim = 0f;
        Arrays.fill(mapPixels, 0);
        mapDirty = true;
        String saved = bests.getString("coach.best.power");
        hasBest = false;
        if (saved != null) {
            String[] parts = saved.split(",");
            if (parts.length == SHAPE) {
                try {
                    for (int i = 0; i < SHAPE; i++) {
                        best[i] = Float.parseFloat(parts[i]);
                    }
                    hasBest = true;
                    bestPower = bests.get("coach.bestPower", 0f);
                } catch (NumberFormatException ignored) {
                    hasBest = false;
                }
            }
        }
        // Best strokes saved before 3.20 have no duration; 0.9 s is a typical drive on this machine.
        bestDriveSec = bests.get("coach.best.drive", 0.9f);
        // Stored as -1 when the best stroke had no measured length, so a stale length never shows.
        float savedLength = bests.get("coach.best.length", -1f);
        bestLength = savedLength > 0 ? savedLength : Float.NaN;
        for (int i = 0; i < DRILL_KEY.length; i++) {
            String key = "coach.drill." + DRILL_KEY[i];
            drillBest[i] = bests.has(key) ? bests.get(key, 0f) : -1f;
        }
        bestSessionScore = bests.has("coach.score") ? bests.get("coach.score", 0f) : Float.NaN;
        storedReach = bests.get("coach.reach", 0f);
        challengeRecord = bests.has("coach.challenge") ? bests.get("coach.challenge", 0f) : -1f;
        badges = Math.round(bests.get("coach.badges", 0f));
        loadBestSession();
        loadWeek();
        checkBadges(false);
    }

    @Override
    protected void onStop() {
        if (strokes >= 20) {
            bests.recordHighest("coach.score", scoreSum / strokes);
            saveBestSessionIfBetter();
        }
        // Learn the rower's reach slowly across sessions, from this session's 90th percentile.
        if (lengthCount >= 20 && !Float.isNaN(sessionReachP90)) {
            float reach = storedReach > 0 ? 0.7f * storedReach + 0.3f * sessionReachP90 : sessionReachP90;
            bests.putFloat("coach.reach", reach);
            storedReach = reach;
        }
        week[6] += (float) (activeSeconds / 60.0);
        saveWeek();
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke == null || stroke == lastSeen) {
            return;
        }
        lastSeen = stroke;
        if (stroke.driveRates.length < 3) {
            return;
        }
        analyse(stroke, s);
    }

    /** The effort through the drive: power when measured, else acceleration of the paddle. */
    private static float[] effortCurve(PulseMeter.Stroke stroke) {
        if (stroke.drivePower != null && stroke.drivePower.length == stroke.driveRates.length) {
            return stroke.drivePower;
        }
        float[] r = stroke.driveRates;
        float[] a = new float[r.length];
        for (int i = 0; i < r.length; i++) {
            float before = i > 0 ? r[i - 1] : r[i];
            float after = i + 1 < r.length ? r[i + 1] : r[i];
            a[i] = Math.max(0f, after - before);
        }
        return a;
    }

    private void analyse(PulseMeter.Stroke stroke, S4Protocol.Status s) {
        float[] shape = resample(effortCurve(stroke));
        int peakIndex = 0;
        for (int i = 1; i < SHAPE; i++) {
            if (shape[i] > shape[peakIndex]) {
                peakIndex = i;
            }
        }
        peakPos = peakIndex / (SHAPE - 1f);
        float ratio = stroke.driveSeconds > 0 ? (float) (stroke.recoverySeconds / stroke.driveSeconds) : 0f;
        float power = !Double.isNaN(stroke.averagePowerW) ? (float) stroke.averagePowerW : s.watts;
        float length = !Double.isNaN(stroke.driveLengthM) ? (float) stroke.driveLengthM : Float.NaN;
        float prevDev = recentCount >= 1 ? meanAbs(shape, recent[0]) : Float.NaN;

        System.arraycopy(recent, 0, recent, 1, RECENT - 1);
        recent[0] = shape;
        recentCount = Math.min(RECENT, recentCount + 1);

        lastDriveSec = (float) stroke.driveSeconds;
        lastRecoverySec = (float) stroke.recoverySeconds;
        lastPower = power;
        lastLength = length;
        computeTravel(stroke.driveRates);
        replayClock = 0f;
        latestFlash = 1f;

        similarity = hasBest ? 100f * (1f - meanAbs(shape, best) * 1.6f) : 70f;
        ratioScore = 100f - Math.min(100f, Math.abs(ratio - 2f) * 60f);
        consistency = 100f;
        if (recentCount >= 3) {
            Arrays.fill(scratch, 0f);
            for (int k = 0; k < recentCount; k++) {
                for (int i = 0; i < SHAPE; i++) {
                    scratch[i] += recent[k][i] / recentCount;
                }
            }
            float dev = 0f;
            for (int k = 0; k < recentCount; k++) {
                dev += meanAbs(recent[k], scratch);
            }
            consistency = 100f * (1f - dev / recentCount * 3f);
        }
        similarity = clamp100(similarity);
        consistency = clamp100(consistency);
        lastScore = 0.4f * similarity + 0.3f * ratioScore + 0.3f * consistency;

        scores[scoreHead] = lastScore;
        scoreHead = (scoreHead + 1) % SCORES;
        scoreCount = Math.min(SCORES, scoreCount + 1);
        strokes++;
        scoreSum += lastScore;

        if (!Float.isNaN(length)) {
            addLength(length);
        }
        addToMap(shape, lastScore);

        if (ratio >= 1.3f && ratio <= 3.5f && power > bestPower) {
            bestPower = power;
            System.arraycopy(shape, 0, best, 0, SHAPE);
            hasBest = true;
            bestDriveSec = lastDriveSec;
            bestLength = length;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < SHAPE; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.US, "%.3f", best[i]));
            }
            bests.putString("coach.best.power", sb.toString());
            bests.putFloat("coach.bestPower", bestPower);
            bests.putFloat("coach.best.drive", bestDriveSec);
            bests.putFloat("coach.best.length", Float.isNaN(length) ? -1f : length);
        }

        // Session aggregates: what the best-session comparison and the report card are built from.
        if (!Float.isNaN(power) && power > 0) {
            sessionPowerSum += power;
            sessionPowerCount++;
        }
        if (ratio > 0) {
            sessionRatioSum += ratio;
        }
        sessionDriveSum += lastDriveSec;
        if (hasBestSession && mapCount >= 3) {
            sessionMatch = clamp100(100f * (1f - meanAbs(sessionMean, bestSessionShape) * 1.6f));
        }

        week[0] += 1f;
        week[1] += lastScore;
        week[2] += similarity;
        week[3] += consistency;
        week[4] += ratioScore;
        // A prefs write every stroke would be wasteful; every 25 keeps a killed session honest.
        if (week[0] - weekSavedStrokes >= 25f) {
            weekSavedStrokes = week[0];
            saveWeek();
        }

        if (drill >= 0) {
            judgeDrill(ratio, power, length, prevDev);
        } else if (challengeOn) {
            judgeChallenge();
        }

        updateCue(ratio, power, length);
        checkBadges(true);
    }

    /* ---------- one live cue at a time ---------- */

    /**
     * Scores every fault this stroke has, keeps working on the worst one, and only retires it after
     * {@link #CUE_CLEAN_NEEDED} clean strokes in a row - so a cue can actually be worked on, where
     * the old tip line changed every stroke and none of them stuck.
     */
    private void updateCue(float ratio, float power, float length) {
        Arrays.fill(cueSev, 0f);
        float reach = reachTarget();
        if (peakPos > 0.62f) {
            cueSev[CUE_LATE] = (peakPos - 0.62f) * 260f;
        }
        if (peakPos < 0.22f) {
            cueSev[CUE_EARLY] = (0.22f - peakPos) * 260f;
        }
        if (ratio > 0 && ratio < 1.5f) {
            cueSev[CUE_RUSH] = (1.5f - ratio) * 70f;
        }
        if (ratio > 3.2f) {
            cueSev[CUE_PAUSE] = (ratio - 3.2f) * 30f;
        }
        if (!Float.isNaN(length) && !Float.isNaN(reach) && reach > 0 && length < reach * 0.93f) {
            cueSev[CUE_SHORT] = (reach * 0.93f - length) / reach * 400f;
        }
        if (consistency < 70f) {
            cueSev[CUE_VARY] = (70f - consistency) * 1.1f;
        }
        // Light strokes are judged against this rower's own easy end, never a constant.
        float light = (float) profile.wattsAt(0.3);
        if (!Float.isNaN(power) && power > 0 && light > 0 && power < light) {
            cueSev[CUE_FADE] = (light - power) / light * 90f;
        }

        int worst = CUE_NONE;
        for (int i = 0; i < CUES; i++) {
            // NaN never compares greater, so a bad reading simply drops out rather than winning.
            if (cueSev[i] > 0 && (worst < 0 || cueSev[i] > cueSev[worst])) {
                worst = i;
            }
        }

        // A NaN severity would fail "<= 0" and then leave worst at CUE_NONE below, so treat
        // anything that is not a positive number as "no fault" here.
        if (cue != CUE_NONE && !(cueSev[cue] > 0f)) {
            cueClean++;
            if (cueClean >= CUE_CLEAN_NEEDED) {
                cueFixedIndex = cue;
                cueFixedTimer = 3.2f;
                cuesFixed++;
                cue = CUE_NONE;
                cueClean = 0;
                cueStrokes = 0;
                confettiBurst();
            }
        } else if (cue != CUE_NONE) {
            cueClean = 0;
            cueStrokes++;
            cueSeverity = cueSev[cue];
            // Only a clearly worse fault interrupts the one being worked on.
            if (worst != CUE_NONE && worst != cue && cueSev[worst] > cueSev[cue] + 30f) {
                cue = worst;
                cueStrokes = 0;
                cueSeverity = cueSev[worst];
                cueArrive = 1f;
            }
        }
        if (cue == CUE_NONE && worst != CUE_NONE && cueFixedTimer <= 0f) {
            cue = worst;
            cueClean = 0;
            cueStrokes = 0;
            cueSeverity = cueSev[worst];
            cueArrive = 1f;
        }

    }

    /* ---------- the challenge ---------- */

    private void startChallenge() {
        challengeOn = true;
        challengeRun = 0;
        challengeBreak = 0f;
        challengeLastPass = true;
        stampTimer = 0f;
    }

    private void judgeChallenge() {
        boolean pass = hasBest && similarity >= CHALLENGE_MATCH;
        challengeLastPass = pass;
        if (pass) {
            challengeRun++;
            challengeFlash = 1f;
            challengeBestRun = Math.max(challengeBestRun, challengeRun);
            if (challengeRun >= CHALLENGE_TARGET) {
                stampPassed = true;
                stampText = "CHALLENGE  " + CHALLENGE_TARGET + "/" + CHALLENGE_TARGET;
                stampTimer = 4f;
                challengeOn = false;
                confettiBurst();
            }
        } else {
            challengeBreak = 1f;
            if (challengeRun > 0) {
                stampPassed = false;
                stampText = "CHAIN BROKE  " + challengeRun + "/" + CHALLENGE_TARGET;
                stampTimer = 2.2f;
            }
            challengeRun = 0;
        }
        if (challengeBestRun > challengeRecord) {
            challengeRecord = challengeBestRun;
            bests.recordHighest("coach.challenge", challengeBestRun);
        }
    }

    private void confettiBurst() {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        for (int i = 0; i < 40; i++) {
            confetti.spawn(w * 0.5f + (float) (Math.random() - 0.5) * w * 0.4f, h * 0.42f,
                    (float) (Math.random() - 0.5) * 420f, -180f - (float) Math.random() * 320f,
                    1.8f, dp(4f), i % 3 == 0 ? ACCENT : i % 3 == 1 ? WARN : BLUE, true);
        }
    }

    /* ---------- badges ---------- */

    private boolean hasBadge(int i) {
        return (badges & (1 << i)) != 0;
    }

    /** How far along a locked badge is, 0..1, so the tree shows progress rather than a blank. */
    private float badgeProgress(int i) {
        int d = BADGE_DRILL[i];
        if (d >= 0) {
            return drillBest[d] < 0 ? 0f : clamp01(drillBest[d] / DRILL_PASS);
        }
        switch (i) {
            case BADGE_STEADY:
                if (Float.isNaN(sessionConsistency)) {
                    return 0f;
                }
                return Math.min(clamp01(mapCount / (float) STEADY_STROKES),
                        clamp01(sessionConsistency / STEADY_PERCENT));
            case BADGE_FLAWLESS: {
                float top = 0f;
                for (float v : drillBest) {
                    top = Math.max(top, v);
                }
                return clamp01(top / DRILL_STROKES);
            }
            case BADGE_LOCKED_IN:
                return clamp01(Math.max(challengeRun, Math.max(challengeBestRun, challengeRecord))
                        / (float) CHALLENGE_TARGET);
            default: {
                int have = 0;
                for (int k = 0; k < BADGES - 1; k++) {
                    if (hasBadge(k)) {
                        have++;
                    }
                }
                return have / (float) (BADGES - 1);
            }
        }
    }

    /** True when the feat itself is done, prerequisites aside. */
    private boolean badgeFeatDone(int i) {
        int d = BADGE_DRILL[i];
        if (d >= 0) {
            return drillBest[d] >= DRILL_PASS;
        }
        switch (i) {
            case BADGE_STEADY:
                return mapCount >= STEADY_STROKES && !Float.isNaN(sessionConsistency)
                        && sessionConsistency >= STEADY_PERCENT;
            case BADGE_FLAWLESS:
                for (float v : drillBest) {
                    if (v >= DRILL_STROKES) {
                        return true;
                    }
                }
                return false;
            case BADGE_LOCKED_IN:
                return Math.max(challengeBestRun, challengeRecord) >= CHALLENGE_TARGET;
            default:
                for (int k = 0; k < BADGES - 1; k++) {
                    if (!hasBadge(k)) {
                        return false;
                    }
                }
                return true;
        }
    }

    /**
     * Awards anything now earned. Conditions are re-checked every time, so a badge whose feat was
     * done before its prerequisite lands is granted the moment the prerequisite does.
     */
    private void checkBadges(boolean celebrate) {
        boolean changed = false;
        // Two passes, so a tier unlocked in this call can award the tier above it.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < BADGES; i++) {
                if (hasBadge(i) || (badges & BADGE_PREREQ[i]) != BADGE_PREREQ[i]) {
                    continue;
                }
                if (!badgeFeatDone(i)) {
                    continue;
                }
                badges |= 1 << i;
                changed = true;
                if (celebrate) {
                    badgeFlash[i] = 2.5f;
                    badgeBanner = i;
                    badgeBannerTimer = 3.4f;
                    badgeUnseen = true;
                    confettiBurst();
                }
            }
        }
        if (changed) {
            bests.putFloat("coach.badges", badges);
        }
    }

    /* ---------- best session, not just best stroke ---------- */

    private void loadBestSession() {
        hasBestSession = false;
        String s = bests.getString("coach.session.best");
        if (s == null) {
            return;
        }
        String[] parts = s.split("\\|");
        if (parts.length != 8) {
            return;
        }
        try {
            bsScore = Float.parseFloat(parts[0]);
            bsPower = Float.parseFloat(parts[1]);
            bsDrive = Float.parseFloat(parts[2]);
            bsRatio = Float.parseFloat(parts[3]);
            bsConsistency = Float.parseFloat(parts[4]);
            bsStrokes = Integer.parseInt(parts[5]);
            bsDay = Long.parseLong(parts[6]);
            String[] curve = parts[7].split(",");
            if (curve.length != SHAPE) {
                return;
            }
            for (int i = 0; i < SHAPE; i++) {
                bestSessionShape[i] = Float.parseFloat(curve[i]);
            }
            hasBestSession = true;
        } catch (NumberFormatException ignored) {
            hasBestSession = false;
        }
    }

    private void saveBestSessionIfBetter() {
        float avg = scoreSum / strokes;
        if (hasBestSession && avg <= bsScore) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%.2f|%.1f|%.3f|%.3f|%.2f|%d|%d|",
                avg, sessionAvgPower(), sessionAvgDrive(), sessionAvgRatio(),
                Float.isNaN(sessionConsistency) ? 0f : sessionConsistency, strokes, todayLocal()));
        for (int i = 0; i < SHAPE; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.US, "%.3f", sessionMean[i]));
        }
        bests.putString("coach.session.best", sb.toString());
    }

    private float sessionAvgPower() {
        return sessionPowerCount > 0 ? sessionPowerSum / sessionPowerCount : 0f;
    }

    private float sessionAvgDrive() {
        return strokes > 0 ? sessionDriveSum / strokes : 0f;
    }

    private float sessionAvgRatio() {
        return strokes > 0 ? sessionRatioSum / strokes : 0f;
    }

    /* ---------- the week ---------- */

    /** Days since the epoch in the tablet's own timezone. */
    private static long todayLocal() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    /** Monday-start week number. Day 0 of the epoch was a Thursday. */
    private static long weekOf(long day) {
        long d = day + 3;
        return d >= 0 ? d / 7 : (d - 6) / 7;
    }

    private void loadWeek() {
        weekIndex = weekOf(todayLocal());
        Arrays.fill(week, 0f);
        Arrays.fill(prevWeek, 0f);
        String cur = bests.getString("coach.week");
        String prev = bests.getString("coach.week.prev");
        long curWeek = decodeWeek(cur, week);
        if (curWeek != Long.MIN_VALUE && curWeek != weekIndex) {
            // The stored week has rolled over: it becomes last week and this one starts clean.
            prev = cur;
            bests.putString("coach.week.prev", cur);
            Arrays.fill(week, 0f);
            bests.putString("coach.week", encodeWeek(weekIndex, week));
        } else if (curWeek == Long.MIN_VALUE) {
            Arrays.fill(week, 0f);
        }
        prevWeekIndex = decodeWeek(prev, prevWeek);
        if (prevWeekIndex == Long.MIN_VALUE) {
            Arrays.fill(prevWeek, 0f);
        }
    }

    /** Fills {@code into} and returns the stored week index, or {@link Long#MIN_VALUE}. */
    private static long decodeWeek(String s, float[] into) {
        if (s == null) {
            return Long.MIN_VALUE;
        }
        String[] p = s.split("\\|");
        if (p.length != into.length + 1) {
            return Long.MIN_VALUE;
        }
        try {
            long wk = Long.parseLong(p[0]);
            for (int i = 0; i < into.length; i++) {
                into[i] = Float.parseFloat(p[i + 1]);
            }
            return wk;
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static String encodeWeek(long wk, float[] v) {
        StringBuilder sb = new StringBuilder();
        sb.append(wk);
        for (float f : v) {
            sb.append('|').append(String.format(Locale.US, "%.2f", f));
        }
        return sb.toString();
    }

    private void saveWeek() {
        // Nothing to keep only when neither strokes nor minutes were added; a visit with no
        // strokes still spent minutes on the machine and the report card counts them.
        if (week[0] <= 0f && week[6] <= 0f) {
            return;
        }
        bests.putString("coach.week", encodeWeek(weekIndex, week));
    }

    /** Average of a weekly sum, 0 when that week has no strokes. */
    private static float weekAvg(float[] w, int field) {
        return w[0] > 0 ? w[field] / w[0] : 0f;
    }

    /** Cumulative paddle rotation through the drive: the handle travels in proportion to it. */
    private void computeTravel(float[] rates) {
        int n = rates.length;
        float total = 0f;
        for (float v : rates) {
            total += Math.max(0f, v);
        }
        if (total <= 0f) {
            hasTravel = false;
            return;
        }
        for (int i = 0; i < SHAPE; i++) {
            float f = i / (SHAPE - 1f) * n;   // bins completed at this point of the drive
            int whole = (int) Math.floor(f);
            float sum = 0f;
            for (int k = 0; k < whole && k < n; k++) {
                sum += Math.max(0f, rates[k]);
            }
            if (whole < n) {
                sum += Math.max(0f, rates[whole]) * (f - whole);
            }
            travel[i] = Math.min(1f, sum / total);
        }
        hasTravel = true;
    }

    private void addLength(float length) {
        if (lengthCount == MAP) {
            System.arraycopy(lengths, 1, lengths, 0, MAP - 1);
            lengthCount--;
        }
        lengths[lengthCount++] = length;
        if (lengthCount >= 5) {
            System.arraycopy(lengths, 0, lengthSorted, 0, lengthCount);
            Arrays.sort(lengthSorted, 0, lengthCount);
            sessionReachP90 = lengthSorted[Math.min(lengthCount - 1, (int) Math.floor(0.9f * (lengthCount - 1)))];
        }
    }

    /** Your full reach: learned across sessions, raised at once if you reach further today. */
    private float reachTarget() {
        float stored = storedReach > 0 ? storedReach : Float.NaN;
        if (Float.isNaN(sessionReachP90)) {
            return stored;
        }
        return Float.isNaN(stored) ? sessionReachP90 : Math.max(stored, sessionReachP90);
    }

    private boolean calibrated() {
        return status != null && status.meter.handleMetresPerPulse > 0;
    }

    /* ---------- drills ---------- */

    private String drillRule(int d) {
        switch (d) {
            case DRILL_LEGS:
                return "Power must peak in the first half of the drive - legs before back and arms.";
            case DRILL_RATIO:
                return "Recover 1.7 to 2.6 times as long as you drive.";
            case DRILL_SAME:
                return "Each drive within 12% of the shape of the one before.";
            case DRILL_PRESSURE:
                return "Every stroke at " + Math.round(pressureTarget()) + " W or more.";
            default:
                float reach = reachTarget();
                return Float.isNaN(reach) ? "Every stroke at full reach."
                        : "Every stroke " + Math.round(reach * 95) + " cm or longer (95% of your reach).";
        }
    }

    /** Between typical and high for this rower: a push, but a sustainable one. */
    private float pressureTarget() {
        return (float) profile.wattsAt(0.7);
    }

    private boolean drillAvailable(int d) {
        if (d == DRILL_REACH) {
            return calibrated() && !Float.isNaN(reachTarget());
        }
        if (d == CHALLENGE) {
            // Matching a shape needs a best stroke to match against.
            return hasBest;
        }
        return true;
    }

    private String chipName(int i) {
        return i == CHALLENGE ? "CHALLENGE" : DRILL_NAME[i];
    }

    private void startDrill(int d) {
        drill = d;
        drillDone = 0;
        drillPassed = 0;
        Arrays.fill(drillResults, false);
        drillVerdict = "";
        stampTimer = 0f;
        lastJudgeTimer = 0f;
    }

    private void judgeDrill(float ratio, float power, float length, float prevDev) {
        boolean pass;
        switch (drill) {
            case DRILL_LEGS:
                pass = peakPos >= 0.12f && peakPos <= 0.5f;
                drillVerdict = String.format(Locale.US, "peak at %d%%", Math.round(peakPos * 100));
                break;
            case DRILL_RATIO:
                pass = ratio >= 1.7f && ratio <= 2.6f;
                drillVerdict = String.format(Locale.US, "1:%.1f", ratio);
                break;
            case DRILL_SAME:
                pass = Float.isNaN(prevDev) || prevDev <= 0.12f;
                drillVerdict = Float.isNaN(prevDev) ? "first stroke sets the shape"
                        : String.format(Locale.US, "%d%% off the last", Math.round(prevDev * 100));
                break;
            case DRILL_PRESSURE:
                pass = !Float.isNaN(power) && power >= pressureTarget();
                drillVerdict = Float.isNaN(power) ? "no power" : Math.round(power) + " W";
                break;
            default:
                float reach = reachTarget();
                pass = !Float.isNaN(length) && !Float.isNaN(reach) && length >= reach * 0.95f;
                drillVerdict = Float.isNaN(length) ? "no length" : Math.round(length * 100) + " cm";
                break;
        }
        drillResults[drillDone] = pass;
        drillDone++;
        if (pass) {
            drillPassed++;
        }
        lastJudgePass = pass;
        lastJudgeTimer = 1.2f;
        int failed = drillDone - drillPassed;
        boolean lost = failed > DRILL_STROKES - DRILL_PASS;
        if (drillDone >= DRILL_STROKES || lost) {
            stampPassed = !lost && drillPassed >= DRILL_PASS;
            stampText = (stampPassed ? "PASSED  " : "FAILED  ") + drillPassed + "/" + drillDone;
            stampTimer = 4f;
            if (drillDone >= DRILL_STROKES) {
                bests.recordHighest("coach.drill." + DRILL_KEY[drill], drillPassed);
                drillBest[drill] = Math.max(drillBest[drill], drillPassed);
            }
            if (stampPassed) {
                week[5] += 1f;
                saveWeek();
                confettiBurst();
            }
            drill = -1;
            checkBadges(true);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // A long press is the screenshot gesture on local builds; only a short tap picks a drill.
        if (e.getActionMasked() == MotionEvent.ACTION_UP
                && e.getEventTime() - e.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            float x = e.getX();
            float y = e.getY();
            boolean onTab = false;
            for (int i = 0; i < PAGE_NAME.length; i++) {
                // The rects are zero until the first frame has laid them out; an empty rect must
                // not swallow a tap at the top-left corner.
                if (tabR[i] > tabL[i] && x >= tabL[i] && x <= tabR[i] && y >= tabT[i] && y <= tabB[i]) {
                    if (page != i) {
                        page = i;
                        pageAnim = 0f;
                    }
                    onTab = true;
                    break;
                }
            }
            if (onTab) {
                // handled
            } else if (drill >= 0 || challengeOn) {
                if (x >= stopHit[0] && x <= stopHit[2] && y >= stopHit[1] && y <= stopHit[3]) {
                    drill = -1;
                    challengeOn = false;
                }
            } else {
                for (int i = 0; i < CHIPS; i++) {
                    if (chipR[i] > chipL[i] && x >= chipL[i] && x <= chipR[i]
                            && y >= chipT[i] && y <= chipB[i] && drillAvailable(i)) {
                        if (i == CHALLENGE) {
                            startChallenge();
                        } else {
                            startDrill(i);
                        }
                        break;
                    }
                }
            }
            postInvalidateOnAnimation();
        }
        // Hand the event on too, so a long-press can still take a screenshot on local builds.
        super.onTouchEvent(e);
        return true;
    }

    /* ---------- consistency map ---------- */

    private void addToMap(float[] shape, float score) {
        if (mapCount == MAP) {
            float[] recycled = mapShapes[0];
            System.arraycopy(mapShapes, 1, mapShapes, 0, MAP - 1);
            mapShapes[MAP - 1] = recycled;
            System.arraycopy(mapScores, 1, mapScores, 0, MAP - 1);
            for (int row = 0; row < MAP_ROWS; row++) {
                System.arraycopy(mapPixels, row * MAP + 1, mapPixels, row * MAP, MAP - 1);
            }
            mapCount--;
        }
        int col = mapCount;
        System.arraycopy(shape, 0, mapShapes[col], 0, SHAPE);
        mapScores[col] = score;
        mapCount++;
        // Catch at the top, finish at the bottom.
        for (int i = 0; i < SHAPE; i++) {
            int h = Math.max(0, Math.min(heat.length - 1, Math.round(shape[i] * (heat.length - 1))));
            mapPixels[i * MAP + col] = heat[h];
        }
        mapPixels[SHAPE * MAP + col] = 0;
        int sc = score >= 80 ? ACCENT : score >= 60 ? WARN : BAD;
        for (int row = SHAPE + 1; row < MAP_ROWS; row++) {
            mapPixels[row * MAP + col] = sc;
        }
        mapDirty = true;

        // Session consistency: how far each stroke sits from the session's average shape.
        Arrays.fill(sessionMean, 0f);
        for (int k = 0; k < mapCount; k++) {
            for (int i = 0; i < SHAPE; i++) {
                sessionMean[i] += mapShapes[k][i] / mapCount;
            }
        }
        if (mapCount >= 3) {
            float dev = 0f;
            for (int k = 0; k < mapCount; k++) {
                dev += meanAbs(mapShapes[k], sessionMean);
            }
            sessionConsistency = clamp100(100f * (1f - dev / mapCount * 3f));
        }
    }

    /* ---------- maths ---------- */

    private static float[] resample(float[] rates) {
        float[] out = new float[SHAPE];
        float max = 1f;
        for (float v : rates) {
            max = Math.max(max, v);
        }
        for (int i = 0; i < SHAPE; i++) {
            float f = i / (SHAPE - 1f) * (rates.length - 1);
            int a = (int) Math.floor(f);
            int b = Math.min(rates.length - 1, a + 1);
            float t = f - a;
            out[i] = (rates[a] + (rates[b] - rates[a]) * t) / max;
        }
        return out;
    }

    private static float meanAbs(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < SHAPE; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum / SHAPE;
    }

    private static float clamp100(float v) {
        return Math.max(0f, Math.min(100f, v));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static float smooth(float v) {
        float x = clamp01(v);
        return x * x * (3 - 2 * x);
    }

    /** Linear lookup in a SHAPE-long curve at 0..1. */
    private static float sample(float[] curve, float f) {
        float x = clamp01(f) * (SHAPE - 1);
        int a = (int) Math.floor(x);
        int b = Math.min(SHAPE - 1, a + 1);
        return curve[a] + (curve[b] - curve[a]) * (x - a);
    }

    private static int mix(int c1, int c2, float t) {
        int a = (int) (((c1 >>> 24) & 0xFF) + ((((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t));
        int r = (int) (((c1 >> 16) & 0xFF) + ((((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t));
        int g = (int) (((c1 >> 8) & 0xFF) + ((((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t));
        int b = (int) ((c1 & 0xFF) + (((c2 & 0xFF) - (c1 & 0xFF)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /* ---------- the stick rower ---------- */

    private final float[] trace = new float[300];
    private int traceHead;
    private int traceCount;
    private float traceMax = 1f;
    private float traceClock;
    private double coachSlowRate;
    private float coachDrive;
    private boolean inDrive;
    private float phaseClock;
    private float legs;
    private float back;
    private float arms;
    private int workingPart;       // 0 legs, 1 back, 2 arms, 3 recovery
    // Joint solver output.
    private float jx;
    private float jy;

    /**
     * Places a joint between two points with two equal segments of {@code len}, bending toward the
     * side given by {@code up} (true: above the line in screen terms). Writes {@link #jx}/{@link #jy}.
     */
    private void joint(float ax, float ay, float bx, float by, float len, boolean up) {
        float dx = bx - ax;
        float dy = by - ay;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        float mx = (ax + bx) / 2f;
        float my = (ay + by) / 2f;
        if (d < 1e-3f || d >= 2 * len) {
            jx = mx;
            jy = my;
            return;
        }
        float h = (float) Math.sqrt(len * len - d * d / 4f);
        float nx = -dy / d;
        float ny = dx / d;
        if ((ny < 0) != up) {
            nx = -nx;
            ny = -ny;
        }
        jx = mx + nx * h;
        jy = my + ny * h;
    }

    /**
     * Updates legs/back/arms from the live paddle, paced by the rower's own last stroke: the drive
     * lasts as long as their last drive did and the handle travels as their paddle actually turned;
     * the recovery unwinds arms, then body, then slide, over their real recovery time.
     */
    private void stepFigure(float dt) {
        double rate = status != null ? status.meter.paddleRate : 0;
        coachSlowRate += (rate - coachSlowRate) * Math.min(1.0, dt / 0.6);
        float target = rate > 40 && rate > coachSlowRate * 1.03 ? 1f : 0f;
        coachDrive += (target - coachDrive) * Math.min(1f, dt * (target > coachDrive ? 14f : 5f));
        boolean driveNow = coachDrive > 0.5f;
        if (driveNow != inDrive) {
            inDrive = driveNow;
            phaseClock = 0f;
        }
        phaseClock += dt;
        if (inDrive) {
            float dur = lastDriveSec > 0 ? Math.max(0.4f, Math.min(1.6f, lastDriveSec)) : 0.8f;
            float p = clamp01(phaseClock / dur);
            float t = hasTravel ? sample(travel, p) : smooth(p);
            legs = Math.max(legs, smooth(t / 0.55f));
            back = Math.max(back, smooth((t - 0.3f) / 0.4f));
            arms = Math.max(arms, smooth((t - 0.62f) / 0.38f));
            workingPart = t < 0.45f ? 0 : t < 0.7f ? 1 : 2;
        } else {
            float dur = lastRecoverySec > 0 ? Math.max(0.8f, Math.min(4f, lastRecoverySec)) : 1.6f;
            float q = clamp01(phaseClock / dur);
            arms = Math.min(arms, 1f - smooth(q / 0.25f));
            back = Math.min(back, 1f - smooth((q - 0.15f) / 0.3f));
            legs = Math.min(legs, 1f - smooth((q - 0.4f) / 0.6f));
            workingPart = 3;
        }
    }

    private void drawRower(Canvas c, float l, float t, float r, float b, float dt) {
        stepFigure(dt);
        glass(c, l, t, r, b, dp(14f));
        label(c, "YOUR STROKE, PHASE BY PHASE", l + dp(14f), t + dp(20f), 10f, FAINT, Paint.Align.LEFT);
        String phase = workingPart == 0 ? "LEGS" : workingPart == 1 ? "BACK" : workingPart == 2 ? "ARMS" : "RECOVERY";
        bold(c, phase, r - dp(14f), t + dp(24f), 16f, workingPart == 3 ? BLUE : ACCENT, Paint.Align.RIGHT);

        float u = Math.min((r - l) / 300f, (b - t - dp(34f)) / 120f);
        float cx = (l + r) / 2f;
        float railY = b - 16 * u;
        float originX = cx - 90 * u;
        float footX = originX + 44 * u;
        float footY = railY - 6 * u;
        // Reach: a short last stroke keeps the figure from compressing fully at the catch.
        float reach = reachTarget();
        float reachFrac = !Float.isNaN(lastLength) && !Float.isNaN(reach) && reach > 0
                ? Math.max(0.55f, Math.min(1f, lastLength / reach)) : 1f;
        float legE = (1f - reachFrac) * 0.6f + reachFrac * legs;
        if (reachFrac < 1f) {
            legE = Math.max(legE, (1f - reachFrac) * 0.6f);
        }

        // Floor: a soft contact shadow under the whole machine, two ovals deep.
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x26000000);
        ovalRect.set(originX - 16 * u, railY - 2 * u, originX + 236 * u, railY + 15 * u);
        c.drawOval(ovalRect, paint);
        paint.setColor(0x38000000);
        ovalRect.set(originX - 4 * u, railY + 1 * u, originX + 224 * u, railY + 11 * u);
        c.drawOval(ovalRect, paint);

        // Rail: a lit metal beam with a highlight on its top face, on two small feet.
        float railL = footX - 6 * u;
        float railR = originX + 220 * u;
        paint.setColor(0xFF1A2332);
        c.drawRoundRect(railR - 8 * u, railY + 3 * u, railR - 2 * u, railY + 10 * u, u, u, paint);
        c.drawRoundRect(railL + 30 * u, railY + 3 * u, railL + 36 * u, railY + 10 * u, u, u, paint);
        c.drawRoundRect(railL, railY, railR, railY + 5 * u, 2 * u, 2 * u,
                shaded(gradient(GRAD_TWO, 0, railY, 0, railY + 5 * u, 0xFF55657D, 0xFF1F2939, 0)));
        unshade();
        paint.setColor(0x55FFFFFF);
        c.drawRect(railL + 2 * u, railY + 0.5f * u, railR - 2 * u, railY + 1.3f * u, paint);

        // Tank housing and the water tank, lit from the upper left, with the paddle turning in it.
        float wheelX = originX + 15 * u;
        float wheelY = railY - 16 * u;
        c.drawRoundRect(originX, railY - 34 * u, originX + 30 * u, railY + 5 * u, 6 * u, 6 * u,
                shaded(gradient(GRAD_TWO, originX, 0, originX + 30 * u, 0, 0xFF3D4B62, 0xFF1C2534, 0)));
        unshade();
        c.drawCircle(wheelX, wheelY, 12.5f * u, shaded(gradient(GRAD_RADIAL, wheelX - 4 * u, wheelY - 5 * u,
                16 * u, 0, 0xFF3FD8C4, 0xFF0C3A40, 0)));
        unshade();
        double spin = (sessionSeconds * (status != null ? status.meter.paddleRate : 0) * 0.05) % (Math.PI * 2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(2 * u);
        paint.setColor(0xCCE6FFFA);
        for (int k = 0; k < 2; k++) {
            double ang = spin + k * Math.PI / 2;
            float bx = (float) Math.cos(ang) * 9.5f * u;
            float by = (float) Math.sin(ang) * 9.5f * u;
            c.drawLine(wheelX - bx, wheelY - by, wheelX + bx, wheelY + by, paint);
        }
        paint.setStrokeWidth(2.2f * u);
        paint.setColor(0xFF6A7A92);
        c.drawCircle(wheelX, wheelY, 12.5f * u, paint);
        paint.setStrokeWidth(1.4f * u);
        paint.setColor(0x80FFFFFF);
        arcRect.set(wheelX - 10 * u, wheelY - 10 * u, wheelX + 10 * u, wheelY + 10 * u);
        c.drawArc(arcRect, 200f, 60f, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE6FFFA);
        c.drawCircle(wheelX, wheelY, 2 * u, paint);
        // Foot stretcher.
        paint.setColor(0xFF3A475C);
        c.drawRoundRect(footX - 4 * u, railY - 18 * u, footX + 2 * u, railY, u, u, paint);
        paint.setColor(0x40FFFFFF);
        c.drawRect(footX - 4 * u, railY - 18 * u, footX - 3 * u, railY, paint);

        // Seat: near the feet at the catch, far back at the finish.
        float seatX = footX + (46 + 74 * legE) * u;   // legs straighten exactly at the finish
        float seatY = railY - 12 * u;
        paint.setColor(0xFF2A3446);
        c.drawRoundRect(seatX - 8 * u, seatY + 6 * u, seatX + 8 * u, railY, u, u, paint);
        c.drawRoundRect(seatX - 14 * u, seatY + 2 * u, seatX + 14 * u, seatY + 8 * u, 2 * u, 2 * u,
                shaded(gradient(GRAD_TWO, 0, seatY + 2 * u, 0, seatY + 8 * u, 0xFFC4CDD8, 0xFF66717F, 0)));
        unshade();
        float hipX = seatX;
        float hipY = seatY - 2 * u;
        // Body: forward at the catch (toward the feet), past vertical at the finish.
        double lean = Math.toRadians(-32 + 56 * back);
        float torso = 54 * u;
        float shX = hipX + (float) Math.sin(lean) * torso;
        float shY = hipY - (float) Math.cos(lean) * torso;
        // Knee from the hip and foot, bent upward.
        joint(hipX, hipY, footX, footY, 60 * u, true);
        float kneeX = jx;
        float kneeY = jy;
        // Handle stays on the chain line; arms straight until the arms phase draws it in.
        float handleY = wheelY - 14 * u;
        float armLen = 50 * u;
        float dy = handleY - shY;
        float straightX = shX - (float) Math.sqrt(Math.max(0f, armLen * armLen - dy * dy)) * 0.98f;
        float chestX = shX - 12 * u;
        float handleX = straightX + (chestX - straightX) * arms;
        joint(shX, shY, handleX, handleY, armLen / 2f + 1 * u, false);
        float elbowX = jx;
        float elbowY = jy;

        int legColor = workingPart == 0 ? ACCENT : 0xFF4A5670;
        int torsoColor = workingPart == 1 ? ACCENT : workingPart == 3 ? 0xFF6F8CFF : 0xFF4A5670;
        int armColor = workingPart == 2 ? ACCENT : 0xFFF1C27D;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Chain, with a bright core.
        paint.setStrokeWidth(3 * u);
        paint.setColor(0x30BFE3FF);
        c.drawLine(wheelX, wheelY, handleX, handleY, paint);
        paint.setStrokeWidth(1.4f * u);
        paint.setColor(0xAABFE3FF);
        c.drawLine(wheelX, wheelY, handleX, handleY, paint);
        // The working part glows: a wide faint stroke of the accent under the limb.
        paint.setColor(ACCENT);
        paint.setAlpha(46);
        if (workingPart == 0) {
            paint.setStrokeWidth(20 * u);
            c.drawLine(hipX, hipY, kneeX, kneeY, paint);
            c.drawLine(kneeX, kneeY, footX, footY, paint);
        } else if (workingPart == 1) {
            paint.setStrokeWidth(24 * u);
            c.drawLine(hipX, hipY, shX, shY, paint);
        } else if (workingPart == 2) {
            paint.setStrokeWidth(16 * u);
            c.drawLine(shX, shY, elbowX, elbowY, paint);
            c.drawLine(elbowX, elbowY, handleX, handleY, paint);
        }
        paint.setAlpha(255);
        // Legs: a dark outline, the colour, and a highlight along the lit upper edge.
        limb(c, hipX, hipY, kneeX, kneeY, 8 * u, legColor, u);
        limb(c, kneeX, kneeY, footX, footY, 7 * u, legColor, u);
        // Shoe on the stretcher.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1A2230);
        c.drawRoundRect(footX - 3 * u, footY - 4 * u, footX + 7 * u, footY + 3 * u, 2 * u, 2 * u, paint);
        // Torso.
        limb(c, hipX, hipY, shX, shY, 11 * u, torsoColor, u);
        // Arms.
        limb(c, shX, shY, elbowX, elbowY, 6 * u, armColor, u);
        limb(c, elbowX, elbowY, handleX, handleY, 5.5f * u, armColor, u);
        // Handle: a bright grip with a little glow, the hand closed over it.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(4 * u);
        paint.setColor(0xFFE6EDF7);
        c.drawLine(handleX, handleY - 5 * u, handleX, handleY + 5 * u, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(armColor);
        c.drawCircle(handleX + 1.5f * u, handleY, 3.2f * u, paint);
        // Head, tipped with the body: hair at the back, a lit face, a highlight on the crown.
        float sinL = (float) Math.sin(lean);
        float cosL = (float) Math.cos(lean);
        float headX = shX + sinL * 13 * u;
        float headY = shY - cosL * 13 * u;
        paint.setColor(0xFF1E2430);
        c.drawCircle(headX + 0.5f * u, headY + 1.2f * u, 10.5f * u, paint);
        paint.setColor(0xFF4A2F22);
        c.drawCircle(headX + 2.2f * u, headY - 1.4f * u, 10 * u, paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(headX - 1.2f * u, headY + 0.8f * u, 9 * u, paint);
        paint.setColor(0x55FFFFFF);
        c.drawCircle(headX - 3.4f * u, headY - 2.4f * u, 2.6f * u, paint);

        // Phase legend: the three parts light up in order through the drive.
        float lx = l + dp(14f);
        float ly = t + dp(44f);
        phaseBar(c, lx, ly, "LEGS", legs, workingPart == 0);
        phaseBar(c, lx, ly + dp(20f), "BACK", back, workingPart == 1);
        phaseBar(c, lx, ly + dp(40f), "ARMS", arms, workingPart == 2);
        // Two short lines, kept left of the figure so they never cross the leaning torso.
        if (reachFrac < 0.97f) {
            label(c, "short at the catch", lx, ly + dp(62f), 9f, WARN, Paint.Align.LEFT);
            label(c, Math.round(reachFrac * 100) + "% of your reach", lx, ly + dp(76f), 9f, WARN, Paint.Align.LEFT);
        } else if (lastDriveSec > 0) {
            label(c, "paced by your last stroke", lx, ly + dp(62f), 9f, FAINT, Paint.Align.LEFT);
            label(c, String.format(Locale.US, "%.1f s drive  %.1f s rec", lastDriveSec, lastRecoverySec),
                    lx, ly + dp(76f), 9f, FAINT, Paint.Align.LEFT);
        }
    }

    private void phaseBar(Canvas c, float x, float y, String name, float value, boolean active) {
        label(c, name, x, y, 9f, active ? ACCENT : FAINT, Paint.Align.LEFT);
        float bx = x + dp(40f);
        float bw = dp(90f);
        track(c, bx, y - dp(9f), bx + bw, y);
        float end = bx + bw * clamp01(value);
        gloss(c, bx, y - dp(9f), end, y, active ? ACCENT : 0xFF4A5670);
        if (active && end - bx > dp(6f)) {
            Fx.glow(c, end - dp(3f), y - dp(4.5f), dp(14f), 0x7035D0BA);
        }
    }

    /**
     * One limb of the figure, given form: a dark outline a little wider than the limb, the limb's
     * colour, and a highlight shifted toward the light (up and to the left).
     */
    private void limb(Canvas c, float x0, float y0, float x1, float y1, float width, int color, float u) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width + 2.4f * u);
        paint.setColor(0xFF0A0F18);
        c.drawLine(x0, y0, x1, y1, paint);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        c.drawLine(x0, y0, x1, y1, paint);
        paint.setStrokeWidth(width * 0.32f);
        paint.setColor(mix(color, 0xFFFFFFFF, 0.45f));
        paint.setAlpha(150);
        float ox = -width * 0.18f;
        float oy = -width * 0.2f;
        c.drawLine(x0 + ox, y0 + oy, x1 + ox, y1 + oy, paint);
        paint.setAlpha(255);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /* ---------- the glass-instrument look (3.25 graphics pass) ---------- */

    /**
     * A cached gradient. Coordinates are rounded to whole pixels for the key, so a panel that sits
     * still reuses one shader for its whole life; the cache only refills when the layout changes.
     */
    private Shader gradient(int kind, float x0, float y0, float x1, float y1, int c0, int c1, int c2) {
        int ix0 = Math.round(x0);
        int iy0 = Math.round(y0);
        int ix1 = Math.round(x1);
        int iy1 = Math.round(y1);
        long k = kind + 1;
        k = k * 1000003L ^ ix0;
        k = k * 1000003L ^ iy0;
        k = k * 1000003L ^ ix1;
        k = k * 1000003L ^ iy1;
        k = k * 1000003L ^ c0;
        k = k * 1000003L ^ c1;
        k = k * 1000003L ^ c2;
        for (int i = 0; i < shaderCount; i++) {
            if (shaderKeys[i] == k) {
                return shaderCache[i];
            }
        }
        Shader s;
        if (kind == GRAD_THREE) {
            s = new LinearGradient(ix0, iy0, ix1, iy1, new int[] {c0, c1, c2}, null, Shader.TileMode.CLAMP);
        } else if (kind == GRAD_BAND) {
            // Soft-edged band: transparent edge, full centre, transparent edge.
            s = new LinearGradient(ix0, iy0, ix1, iy1, new int[] {c0, c1, c1, c0},
                    new float[] {0f, 0.34f, 0.66f, 1f}, Shader.TileMode.CLAMP);
        } else if (kind == GRAD_RADIAL) {
            s = new RadialGradient(ix0, iy0, Math.max(1, ix1), c0, c1, Shader.TileMode.CLAMP);
        } else {
            s = new LinearGradient(ix0, iy0, ix1, iy1, c0, c1, Shader.TileMode.CLAMP);
        }
        int slot;
        if (shaderCount < SHADER_SLOTS) {
            slot = shaderCount++;
        } else {
            slot = shaderNext;
            shaderNext = (shaderNext + 1) % SHADER_SLOTS;
        }
        shaderKeys[slot] = k;
        shaderCache[slot] = s;
        return s;
    }

    /** fxPaint ready to fill with {@code s}: opaque white first, or the shader draws at a stale alpha. */
    private Paint shaded(Shader s) {
        fxPaint.setStyle(Paint.Style.FILL);
        fxPaint.setColor(0xFFFFFFFF);
        fxPaint.setShader(s);
        return fxPaint;
    }

    private void unshade() {
        fxPaint.setShader(null);
    }

    /**
     * A dark glass panel: a soft drop shadow, a top-lit vertical gradient, a hairline rim and a
     * brighter 1px top edge where the light catches it. Every card on this screen uses it.
     */
    private void glass(Canvas c, float l, float t, float r, float b, float rad) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x50000000);
        c.drawRoundRect(l + dp(1f), t + dp(4f), r + dp(1f), b + dp(4f), rad, rad, paint);
        c.drawRoundRect(l, t, r, b, rad, rad,
                shaded(gradient(GRAD_THREE, 0, t, 0, b, PANEL_TOP, PANEL_MID, PANEL_BOT)));
        unshade();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0xFF1C2736);
        float hw = dp(0.5f);
        c.drawRoundRect(l + hw, t + hw, r - hw, b - hw, rad, rad, paint);
        paint.setColor(0x30FFFFFF);
        c.drawLine(l + rad, t + dp(1f), r - rad, t + dp(1f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A recessed bar track: darker than the panel, with a faint lit lower lip. */
    private void track(Canvas c, float l, float t, float r, float b) {
        float rad = (b - t) / 2f;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF070C14);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x16FFFFFF);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A glossy bar fill: the colour, a bright upper sheen and a darker lower half. */
    private void gloss(Canvas c, float l, float t, float r, float b, int color) {
        if (r - l < dp(1f)) {
            return;
        }
        float h = b - t;
        float rad = Math.min(h / 2f, (r - l) / 2f);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setColor(0x30000000);
        c.drawRoundRect(l, t + h * 0.55f, r, b, rad, rad, paint);
        if (r - l > h) {
            paint.setColor(0x55FFFFFF);
            c.drawRoundRect(l + rad * 0.6f, t + h * 0.14f, r - rad * 0.6f, t + h * 0.42f,
                    h * 0.14f, h * 0.14f, paint);
        }
    }

    /** A glossy chip: a vertical gradient from a lit top to a deep bottom, with a rim. */
    private void chip(Canvas c, float l, float t, float r, float b, float rad, int base) {
        c.drawRoundRect(l, t, r, b, rad, rad, shaded(gradient(GRAD_TWO, 0, t, 0, b,
                mix(base, 0xFFFFFFFF, 0.10f), mix(base, 0xFF000000, 0.30f), 0)));
        unshade();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x1EFFFFFF);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setColor(0x38FFFFFF);
        c.drawLine(l + rad, t + dp(1f), r - rad, t + dp(1f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * A line that glows: a wide faint halo, a tighter bloom, the coloured core and a hot near-white
     * centre. Four strokes of one reused path - no blur filter, which would drop to software.
     */
    private void glowStroke(Canvas c, Path p, int color, float core) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        paint.setAlpha(26);
        paint.setStrokeWidth(core * 4.4f);
        c.drawPath(p, paint);
        paint.setAlpha(60);
        paint.setStrokeWidth(core * 2.3f);
        c.drawPath(p, paint);
        paint.setAlpha(255);
        paint.setStrokeWidth(core);
        c.drawPath(p, paint);
        paint.setColor(mix(color, 0xFFFFFFFF, 0.65f));
        paint.setAlpha(190);
        paint.setStrokeWidth(Math.max(dp(1f), core * 0.32f));
        c.drawPath(p, paint);
        paint.setAlpha(255);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A lit dot: halo, body, and a small specular highlight up and to the left. */
    private void litDot(Canvas c, float x, float y, float rad, int color) {
        Fx.glow(c, x, y, rad * 2.4f, (color & 0x00FFFFFF) | 0x60000000);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(x, y, rad, paint);
        paint.setColor(0x40000000);
        c.drawCircle(x + rad * 0.18f, y + rad * 0.22f, rad * 0.78f, paint);
        paint.setColor(color);
        c.drawCircle(x - rad * 0.06f, y - rad * 0.06f, rad * 0.8f, paint);
        paint.setColor(0xB0FFFFFF);
        c.drawCircle(x - rad * 0.32f, y - rad * 0.34f, rad * 0.26f, paint);
    }

    /** The size {@link #label} and {@link #bold} actually draw at - GameView's curve, mirrored. */
    private static float shownDp(float sizeDp) {
        float scale;
        if (sizeDp <= 10f) {
            scale = 1.35f;
        } else if (sizeDp >= 30f) {
            scale = 1.08f;
        } else {
            scale = 1.35f + (1.08f - 1.35f) * ((sizeDp - 10f) / 20f);
        }
        return sizeDp * scale;
    }

    /** Width of {@code s} as {@link #label} draws it. */
    private float labelWidth(String s, float sizeDp) {
        dimPaint.setTextSize(dp(shownDp(sizeDp)));
        dimPaint.setFakeBoldText(false);
        return dimPaint.measureText(s);
    }

    /** Width of {@code s} as {@link #bold} draws it. */
    private float boldWidth(String s, float sizeDp) {
        textPaint.setTextSize(dp(shownDp(sizeDp)));
        return textPaint.measureText(s);
    }

    /* ---------- render ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        // Deep backdrop: a top-lit gradient and two faint pools of light behind the glass panels.
        c.drawRect(0, 0, w, h, shaded(gradient(GRAD_THREE, 0, 0, 0, h, 0xFF0B1422, 0xFF070D16, 0xFF04080E)));
        unshade();
        Fx.glow(c, w * 0.28f, h * 0.10f, w * 0.42f, 0x1035D0BA);
        Fx.glow(c, w * 0.82f, h * 0.88f, w * 0.36f, 0x106F8CFF);

        float pad = dp(20f);
        float gapX = dp(14f);
        float rowA = h * 0.47f;
        float rowB0 = rowA + dp(12f);
        float rowB1 = h * 0.74f;
        float rowC0 = rowB1 + dp(12f);
        float leftEnd = w * 0.55f;

        // Row A: best and latest, replayed side by side.
        replayClock += dt;
        float longest = Math.max(0.5f, Math.max(bestDriveSec, lastDriveSec));
        if (replayClock > longest + 1.4f) {
            replayClock = 0f;
        }
        latestFlash = Math.max(0f, latestFlash - dt * 1.5f);
        float mid = (pad + leftEnd) / 2f;
        drawReplay(c, pad, pad * 0.5f, mid - gapX / 2f, rowA, true);
        drawReplay(c, mid + gapX / 2f, pad * 0.5f, leftEnd, rowA, false);

        // Row A right: score, tip, metrics.
        float rx = leftEnd + dp(24f);
        float right = w - pad;
        drawScore(c, rx, dt);
        drawCue(c, rx + dp(140f), dp(10f), right, dp(124f), dt);
        PulseMeter.Stroke s = status == null ? null : status.meter.lastStroke;
        float col = (right - rx) / 4f;
        float gy = dp(150f);
        // Hairline dividers between the metric columns, fading out top and bottom.
        Shader divider = gradient(GRAD_THREE, 0, gy - dp(22f), 0, gy + dp(74f), 0x00FFFFFF, 0x1CFFFFFF, 0x00FFFFFF);
        for (int i = 1; i < 4; i++) {
            float dx = rx + i * col - dp(14f);
            c.drawRect(dx, gy - dp(22f), dx + dp(1f), gy + dp(74f), shaded(divider));
        }
        unshade();
        metric(c, rx, gy, strokes > 0 ? Math.round(similarity) + "%" : "--", "SHAPE MATCH");
        metric(c, rx + col, gy, strokes > 0 ? Math.round(consistency) + "%" : "--", "CONSISTENCY");
        metric(c, rx + 2 * col, gy, s != null ? String.format(Locale.US, "1:%.1f", s.driveSeconds > 0 ? s.recoverySeconds / s.driveSeconds : 0) : "--", "RATIO (AIM 1:2)");
        metric(c, rx + 3 * col, gy, strokes > 0 ? Math.round(peakPos * 100) + "%" : "--", "PEAK AT (AIM 30-55%)");
        metric(c, rx, gy + dp(60f), s != null ? String.format(Locale.US, "%.2f s", s.driveSeconds) : "--", "DRIVE");
        metric(c, rx + col, gy + dp(60f), s != null && !Double.isNaN(s.averagePowerW) ? Math.round(s.averagePowerW) + " W" : "--", "POWER");
        metric(c, rx + 2 * col, gy + dp(60f), s != null && !Double.isNaN(s.peakForceN) ? Math.round(s.peakForceN / 9.80665) + " kg" : "--", "PEAK FORCE");
        if (hasSteering()) {
            float roll = steering() * 35f;
            metric(c, rx + 3 * col, gy + dp(60f), String.format(Locale.US, "%+.0f°", roll),
                    Math.abs(roll) > 6 ? "HANDLE NOT LEVEL" : "HANDLE LEVEL");
        } else {
            metric(c, rx + 3 * col, gy + dp(60f), strokes > 0 ? String.valueOf(strokes) : "--", "STROKES COACHED");
        }
        drawCompare(c, rx - dp(8f), gy + dp(80f), right, gy + dp(142f), dt);
        drawTraining(c, rx - dp(8f), gy + dp(150f), right, rowA, dt);

        // Row B: the stick rower, drive length, live paddle trace.
        float bMid = w * 0.36f;
        drawRower(c, pad, rowB0, bMid - gapX / 2f, rowB1, dt);
        drawLength(c, bMid + gapX / 2f, rowB0, leftEnd, rowB1, dt);
        drawLiveTrace(c, leftEnd + gapX, rowB0, right, rowB1, dt);

        // Row C: the consistency map, the skill tree or the report card.
        drawBottom(c, pad, rowC0, right, h - dp(10f), dt);

        // Drill verdict stamp, over everything.
        if (stampTimer > 0f) {
            stampTimer -= dt;
            float a = Math.min(1f, stampTimer / 0.5f);
            float pop = 1f + Math.max(0f, stampTimer - 3.6f) * 1.5f;
            c.save();
            c.translate(w * 0.5f, h * 0.45f);
            c.rotate(-6f);
            c.scale(pop, pop);
            int colr = stampPassed ? ACCENT : BAD;
            paint.setShader(null);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(colr);
            paint.setAlpha((int) (45 * a));
            paint.setStrokeWidth(dp(20f));
            c.drawRoundRect(-dp(250f), -dp(60f), dp(250f), dp(50f), dp(16f), dp(16f), paint);
            paint.setStrokeWidth(dp(6f));
            paint.setAlpha((int) (230 * a));
            c.drawRoundRect(-dp(250f), -dp(60f), dp(250f), dp(50f), dp(16f), dp(16f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE0070D16);
            paint.setAlpha((int) (200 * a));
            c.drawRoundRect(-dp(246f), -dp(56f), dp(246f), dp(46f), dp(14f), dp(14f), paint);
            paint.setAlpha(255);
            bold(c, stampText, 0, dp(14f), 48f, colr, Paint.Align.CENTER);
            c.restore();
        }

        // Celebration confetti, then the badge banner above everything.
        confetti.step(dt, dp(520f));
        confetti.draw(c);
        drawBadgeBanner(c, w, h, dt);
    }

    /**
     * The stroke score on its own glass card. A new score lands with a flourish - it pops, a halo
     * of its colour flares behind it and the bar underneath slides to the new value - then settles.
     */
    private void drawScore(Canvas c, float rx, float dt) {
        float l = rx - dp(8f);
        float t = dp(10f);
        float r = rx + dp(130f);
        float b = dp(124f);
        glass(c, l, t, r, b, dp(14f));
        int color = strokes == 0 ? FAINT : lastScore >= 80 ? ACCENT : lastScore >= 60 ? WARN : BAD;
        float cx = (l + r) / 2f;
        float f = strokes > 0 ? latestFlash : 0f;
        if (strokes > 0) {
            // A steady low halo always, flaring as the score lands.
            Fx.glow(c, cx, dp(46f), dp(62f), (color & 0x00FFFFFF) | ((int) (0x28 + 0x70 * f) << 24));
        }
        float pop = 1f + 0.22f * f * f;
        c.save();
        c.scale(pop, pop, cx, dp(44f));
        bold(c, strokes > 0 ? String.valueOf(Math.round(lastScore)) : "--", cx, dp(66f), 60f, color,
                Paint.Align.CENTER);
        c.restore();
        label(c, "STROKE SCORE", cx, dp(88f), 10f, FAINT, Paint.Align.CENTER);
        float target = strokes > 0 ? clamp01(lastScore / 100f) : 0f;
        scoreBarShown += (target - scoreBarShown) * Math.min(1f, dt * 5f);
        float bx0 = l + dp(12f);
        float bx1 = r - dp(12f);
        track(c, bx0, dp(100f), bx1, dp(110f));
        if (strokes > 0) {
            float end = bx0 + (bx1 - bx0) * scoreBarShown;
            gloss(c, bx0, dp(100f), end, dp(110f), color);
            if (f > 0f) {
                Fx.glow(c, end, dp(105f), dp(16f), (color & 0x00FFFFFF) | ((int) (0xA0 * f) << 24));
            }
        }
    }

    /** A badge landing stops the screen for a moment - it is the reward the drills are for. */
    private void drawBadgeBanner(Canvas c, float w, float h, float dt) {
        if (badgeBannerTimer <= 0f || badgeBanner < 0) {
            return;
        }
        badgeBannerTimer -= dt;
        if (badgeBannerTimer <= 0f) {
            badgeBanner = -1;
            return;
        }
        float a = Math.min(1f, badgeBannerTimer / 0.6f);
        float in = 1f - clamp01((badgeBannerTimer - 3.0f) / 0.4f);
        float rise = (1f - smooth(in)) * dp(40f);
        c.save();
        c.translate(w * 0.5f, h * 0.20f + rise);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        // Bloom around the card: two wide faint rims of the accent under the sharp one.
        paint.setColor(ACCENT);
        paint.setAlpha((int) (40 * a));
        paint.setStrokeWidth(dp(14f));
        c.drawRoundRect(-dp(260f), -dp(52f), dp(260f), dp(52f), dp(16f), dp(16f), paint);
        paint.setAlpha((int) (80 * a));
        paint.setStrokeWidth(dp(7f));
        c.drawRoundRect(-dp(260f), -dp(52f), dp(260f), dp(52f), dp(16f), dp(16f), paint);
        paint.setStyle(Paint.Style.FILL);
        fxPaint.setColor(0xFFFFFFFF);
        fxPaint.setShader(gradient(GRAD_THREE, 0, -dp(52f), 0, dp(52f), 0xFF17283A, 0xFF0C1524, 0xFF08101A));
        fxPaint.setAlpha((int) (245 * a));
        c.drawRoundRect(-dp(260f), -dp(52f), dp(260f), dp(52f), dp(16f), dp(16f), fxPaint);
        unshade();
        fxPaint.setAlpha(255);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3f));
        paint.setColor(ACCENT);
        paint.setAlpha((int) (255 * a));
        c.drawRoundRect(-dp(260f), -dp(52f), dp(260f), dp(52f), dp(16f), dp(16f), paint);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x50FFFFFF);
        paint.setAlpha((int) (0x50 * a));
        c.drawLine(-dp(244f), -dp(49f), dp(244f), -dp(49f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        float spin = (float) Math.sin(sessionSeconds * 3) * 0.12f;
        c.save();
        c.translate(-dp(200f), 0);
        c.rotate((float) Math.toDegrees(spin));
        drawBadgeMedal(c, 0, 0, dp(28f), ACCENT, 1f);
        c.restore();
        bold(c, "BADGE EARNED", -dp(150f), -dp(14f), 12f, FAINT, Paint.Align.LEFT);
        bold(c, BADGE_NAME[badgeBanner], -dp(150f), dp(16f), 30f, ACCENT, Paint.Align.LEFT);
        c.restore();
    }

    /**
     * A medal: a domed disc lit from the upper left, a bright ring, a tick, and rays that turn with
     * {@code shine}. Drawn at the origin so its gradient is cached by radius - callers that animate
     * a medal's size scale the canvas instead of passing a changing radius.
     */
    private void drawBadgeMedal(Canvas c, float cx, float cy, float rad, int color, float shine) {
        Fx.glow(c, cx, cy, rad * 2f, (color & 0x00FFFFFF) | 0x60000000);
        c.save();
        c.translate(cx, cy);
        c.drawCircle(0, 0, rad, shaded(gradient(GRAD_RADIAL, -rad * 0.35f, -rad * 0.4f, rad * 1.5f, 0,
                mix(color, 0xFF0E1C28, 0.62f), 0xFF070E16, 0)));
        unshade();
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        // Ring with a darker outer lip and a bright inner edge, so it reads as raised metal.
        paint.setStrokeWidth(rad * 0.2f);
        paint.setColor(mix(color, 0xFF000000, 0.35f));
        c.drawCircle(0, 0, rad * 0.86f, paint);
        paint.setStrokeWidth(rad * 0.12f);
        paint.setColor(color);
        c.drawCircle(0, 0, rad * 0.84f, paint);
        paint.setStrokeWidth(Math.max(dp(1f), rad * 0.05f));
        paint.setColor(mix(color, 0xFFFFFFFF, 0.6f));
        arcRect.set(-rad * 0.84f, -rad * 0.84f, rad * 0.84f, rad * 0.84f);
        c.drawArc(arcRect, 190f, 80f, false, paint);
        // Tick, with a soft shadow under it.
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(rad * 0.2f);
        paint.setColor(0x60000000);
        c.drawLine(-rad * 0.36f, rad * 0.06f, -rad * 0.06f, rad * 0.38f, paint);
        c.drawLine(-rad * 0.06f, rad * 0.38f, rad * 0.46f, -rad * 0.32f, paint);
        paint.setStrokeWidth(rad * 0.18f);
        paint.setColor(color);
        c.drawLine(-rad * 0.38f, rad * 0.02f, -rad * 0.08f, rad * 0.34f, paint);
        c.drawLine(-rad * 0.08f, rad * 0.34f, rad * 0.44f, -rad * 0.36f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        c.restore();
        if (shine > 0f) {
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(rad * 0.1f);
            paint.setColor(color);
            paint.setAlpha((int) (150 * shine));
            for (int i = 0; i < 4; i++) {
                double ang = sessionSeconds * 1.2 + i * Math.PI / 2;
                float x0 = cx + (float) Math.cos(ang) * rad * 1.15f;
                float y0 = cy + (float) Math.sin(ang) * rad * 1.15f;
                float x1 = cx + (float) Math.cos(ang) * rad * 1.45f;
                float y1 = cy + (float) Math.sin(ang) * rad * 1.45f;
                c.drawLine(x0, y0, x1, y1, paint);
            }
            paint.setAlpha(255);
            paint.setStrokeCap(Paint.Cap.BUTT);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /* ---------- the live cue ---------- */

    /**
     * One cue, held until it is fixed. The three pips are the clean strokes it needs; filling them
     * retires the cue with a flash and the next fault takes its place.
     */
    private void drawCue(Canvas c, float l, float t, float r, float b, float dt) {
        cueArrive = Math.max(0f, cueArrive - dt * 2.2f);
        if (cueFixedTimer > 0f) {
            cueFixedTimer = Math.max(0f, cueFixedTimer - dt);
        }
        boolean fixed = cueFixedTimer > 0f && cueFixedIndex >= 0;
        int color = fixed ? ACCENT : cue == CUE_NONE ? ACCENT : WARN;
        float slide = smooth(cueArrive) * dp(26f);
        c.save();
        c.translate(slide, 0);
        glass(c, l, t, r, b, dp(14f));
        // The cue's colour washes in from the left edge, stronger once a fault is fixed.
        c.drawRoundRect(l, t, r, b, dp(14f), dp(14f), shaded(gradient(GRAD_TWO, l, 0, l + (r - l) * 0.6f, 0,
                (color & 0x00FFFFFF) | (fixed ? 0x34000000 : 0x1C000000), color & 0x00FFFFFF, 0)));
        unshade();
        // A pulsing edge on the side: the cue is the thing to look at. A wide faint bar under the
        // sharp one gives it a glow without a blur.
        float pulse = 0.55f + 0.45f * (float) Math.sin(sessionSeconds * (fixed ? 8 : 2.4));
        paint.setColor(color);
        paint.setAlpha((int) (30 + 50 * pulse));
        c.drawRoundRect(l + dp(1f), t + dp(4f), l + dp(14f), b - dp(4f), dp(6f), dp(6f), paint);
        paint.setAlpha((int) (90 + 140 * pulse));
        c.drawRoundRect(l + dp(2f), t + dp(8f), l + dp(7f), b - dp(8f), dp(3f), dp(3f), paint);
        paint.setAlpha(255);
        float x = l + dp(20f);
        if (fixed) {
            label(c, "FIXED", x, t + dp(20f), 10f, FAINT, Paint.Align.LEFT);
            bold(c, CUE_DONE[cueFixedIndex], x, t + dp(46f), 24f, ACCENT, Paint.Align.LEFT);
            label(c, "Three clean strokes in a row. Next cue as soon as one shows up.",
                    x, t + dp(68f), 11f, DIM, Paint.Align.LEFT);
            float mx = r - dp(44f);
            float my = (t + b) / 2f;
            float grow = 1f + 0.12f * pulse;
            c.save();
            c.scale(grow, grow, mx, my);
            drawBadgeMedal(c, mx, my, dp(24f), ACCENT, pulse);
            c.restore();
            label(c, cuesFixed + " fixed this session", x, t + dp(88f), 10f, FAINT, Paint.Align.LEFT);
            c.restore();
            return;
        }
        if (cue == CUE_NONE) {
            label(c, strokes == 0 ? "COACH" : "NOTHING TO FIX", x, t + dp(20f), 10f, FAINT, Paint.Align.LEFT);
            bold(c, strokes == 0 ? "ROW A FEW STROKES" : "HOLD THIS STROKE", x, t + dp(48f), 24f, ACCENT, Paint.Align.LEFT);
            label(c, strokes == 0 ? "Each stroke is drawn and scored here as you finish it."
                            : "Your stroke is clean. The next fault will appear here the moment it shows.",
                    x, t + dp(72f), 11f, DIM, Paint.Align.LEFT);
            if (strokes > 0) {
                label(c, cuesFixed + " fixed this session", x, t + dp(92f), 10f, FAINT, Paint.Align.LEFT);
            }
            c.restore();
            return;
        }
        label(c, "WORK ON THIS", x, t + dp(20f), 10f, FAINT, Paint.Align.LEFT);
        bold(c, CUE_TITLE[cue], x, t + dp(48f), 24f, WARN, Paint.Align.LEFT);
        wrap(c, CUE_BODY[cue], x, t + dp(70f), r - x - dp(160f), 11f, DIM);
        // The three clean strokes it takes to retire this cue.
        float px = r - dp(120f);
        float py = t + dp(34f);
        label(c, "CLEAN STROKES", r - dp(16f), t + dp(18f), 9f, FAINT, Paint.Align.RIGHT);
        for (int i = 0; i < CUE_CLEAN_NEEDED; i++) {
            float cx = px + i * dp(32f);
            if (i < cueClean) {
                litDot(c, cx, py, dp(11f), ACCENT);
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF070C14);
                c.drawCircle(cx, py, dp(11f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(i == cueClean ? WARN : 0xFF3A475C);
                if (i == cueClean) {
                    paint.setAlpha((int) (140 + 115 * pulse));
                }
                c.drawCircle(cx, py, dp(11f), paint);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        label(c, cueStrokes == 0 ? "new cue" : cueStrokes + " strokes on this cue",
                r - dp(16f), t + dp(64f), 10f, FAINT, Paint.Align.RIGHT);
        // How bad it is right now: the bar shrinks as the fault comes right.
        float bx0 = r - dp(130f);
        float bx1 = r - dp(16f);
        float by = t + dp(80f);
        track(c, bx0, by, bx1, by + dp(8f));
        gloss(c, bx0, by, bx0 + (bx1 - bx0) * clamp01(cueSeverity / 60f), by + dp(8f),
                clamp01(cueSeverity / 60f) > 0.6f ? BAD : WARN);
        label(c, "HOW FAR OFF", bx1, by + dp(22f), 9f, FAINT, Paint.Align.RIGHT);
        c.restore();
    }

    /**
     * One replay panel: the curve drawn up to a playhead that sweeps at the drive's real speed.
     * Drawn as a training display: a recessed chart well with a faint grid, the ideal-peak band
     * with soft edges, earlier strokes as dim afterglow, and the stroke itself as a glowing line
     * over a gradient fill, brighter than anything else on the chart.
     */
    private void drawReplay(Canvas c, float l, float t, float r, float b, boolean isBest) {
        glass(c, l, t, r, b, dp(14f));
        if (!isBest && latestFlash > 0f) {
            // A new stroke: the panel's rim flares and fades.
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(ACCENT);
            paint.setAlpha((int) (60 * latestFlash));
            paint.setStrokeWidth(dp(9f));
            c.drawRoundRect(l, t, r, b, dp(14f), dp(14f), paint);
            paint.setAlpha((int) (210 * latestFlash));
            paint.setStrokeWidth(dp(2.5f));
            c.drawRoundRect(l, t, r, b, dp(14f), dp(14f), paint);
            paint.setAlpha(255);
            paint.setStyle(Paint.Style.FILL);
        }
        int color = isBest ? BLUE : ACCENT;
        boolean have = isBest ? hasBest : recentCount > 0;
        float[] curve = isBest ? best : recent[0];
        float drive = isBest ? bestDriveSec : lastDriveSec;
        label(c, isBest ? "YOUR BEST STROKE" : "LATEST STROKE", l + dp(14f), t + dp(22f), 11f, color, Paint.Align.LEFT);
        if (have) {
            String caption;
            if (isBest) {
                caption = String.format(Locale.US, "%d W  ·  %.2f s", Math.round(bestPower), bestDriveSec)
                        + (!Float.isNaN(bestLength) ? "  ·  " + Math.round(bestLength * 100) + " cm" : "");
            } else {
                caption = (Float.isNaN(lastPower) ? "" : Math.round(lastPower) + " W  ·  ")
                        + String.format(Locale.US, "%.2f s", lastDriveSec)
                        + (!Float.isNaN(lastLength) ? "  ·  " + Math.round(lastLength * 100) + " cm" : "");
            }
            label(c, caption, r - dp(14f), t + dp(22f), 10f, DIM, Paint.Align.RIGHT);
        }
        float cl = l + dp(16f);
        float cr = r - dp(16f);
        float ct = t + dp(40f);
        float cb = b - dp(30f);
        // The chart well: darker glass, recessed below the panel.
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(cl - dp(6f), ct - dp(6f), cr + dp(6f), cb + dp(2f), dp(8f), dp(8f),
                shaded(gradient(GRAD_TWO, 0, ct - dp(6f), 0, cb + dp(2f), 0xFF060B13, 0xFF0A121D, 0)));
        unshade();
        paint.setColor(0x0FFFFFFF);
        for (int i = 1; i < 4; i++) {
            float gy = cb - (cb - ct) * i / 4f;
            c.drawRect(cl, gy, cr, gy + dp(1f), paint);
        }
        for (int i = 1; i < 4; i++) {
            float gx = cl + (cr - cl) * i / 4f;
            c.drawRect(gx, ct, gx + dp(1f), cb, paint);
        }
        // Ideal peak: a soft-edged column of light rather than a hard box.
        float bandL = cl + (cr - cl) * 0.30f;
        float bandR = cl + (cr - cl) * 0.55f;
        float bandPad = (bandR - bandL) * 0.22f;
        c.drawRect(bandL - bandPad, ct, bandR + bandPad, cb,
                shaded(gradient(GRAD_BAND, bandL - bandPad, 0, bandR + bandPad, 0, 0x0035D0BA, 0x2E35D0BA, 0)));
        unshade();
        // Baseline.
        paint.setColor(0x38FFFFFF);
        c.drawRect(cl, cb - dp(1f), cr, cb, paint);
        label(c, "CATCH", cl, cb + dp(18f), 9f, FAINT, Paint.Align.LEFT);
        label(c, "IDEAL PEAK", cl + (cr - cl) * 0.425f, cb + dp(18f), 9f, ACCENT, Paint.Align.CENTER);
        label(c, "FINISH", cr, cb + dp(18f), 9f, FAINT, Paint.Align.RIGHT);
        if (!have) {
            label(c, isBest ? "Your most powerful well-timed stroke lands here." : "Row a stroke - it replays here.",
                    (cl + cr) / 2f, (ct + cb) / 2f, 12f, FAINT, Paint.Align.CENTER);
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (!isBest) {
            // The strokes before this one as fading afterglow, so the latest reads against its
            // own recent history.
            int ghosts = Math.min(recentCount, 4);
            for (int k = ghosts - 1; k >= 1; k--) {
                curvePath(recent[k], cl, ct, cr, cb, 1f);
                paint.setStrokeWidth(dp(1.5f));
                paint.setColor(ACCENT);
                paint.setAlpha(46 - k * 10);
                c.drawPath(path, paint);
            }
            paint.setAlpha(255);
        }
        // On the latest panel, the best stroke and the best session's average shape as outlines:
        // one lucky stroke and the shape you actually held for a whole piece.
        float legendY = ct + dp(14f);
        if (!isBest && hasBest) {
            curvePath(best, cl, ct, cr, cb, 1f);
            paint.setStrokeWidth(dp(4f));
            paint.setColor(0x1A6F8CFF);
            c.drawPath(path, paint);
            paint.setStrokeWidth(dp(1.75f));
            paint.setColor(0x996F8CFF);
            c.drawPath(path, paint);
        }
        if (!isBest && hasBestSession) {
            curvePath(bestSessionShape, cl, ct, cr, cb, 1f);
            paint.setStrokeWidth(dp(4f));
            paint.setColor(0x1AF0B132);
            c.drawPath(path, paint);
            paint.setStrokeWidth(dp(1.75f));
            paint.setColor(0xA6F0B132);
            c.drawPath(path, paint);
        }
        // Legend in the empty top-left of the chart, each with a swatch of its line.
        if (!isBest && hasBest) {
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(BLUE);
            c.drawLine(cl + dp(2f), legendY - dp(4f), cl + dp(16f), legendY - dp(4f), paint);
            label(c, "best stroke", cl + dp(22f), legendY, 8f, BLUE, Paint.Align.LEFT);
            legendY += dp(15f);
        }
        if (!isBest && hasBestSession) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2.5f));
            paint.setColor(WARN);
            c.drawLine(cl + dp(2f), legendY - dp(4f), cl + dp(16f), legendY - dp(4f), paint);
            label(c, "best session", cl + dp(22f), legendY, 8f, WARN, Paint.Align.LEFT);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Whole curve faint, then the replayed part glowing.
        curvePath(curve, cl, ct, cr, cb, 1f);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(color);
        paint.setAlpha(64);
        c.drawPath(path, paint);
        paint.setAlpha(255);
        float f = drive > 0 ? clamp01(replayClock / drive) : 1f;
        curvePath(curve, cl, ct, cr, cb, f);
        float px = cl + (cr - cl) * f;
        float py = cb - (cb - ct) * sample(curve, f);
        path.lineTo(px, cb);
        path.lineTo(cl, cb);
        path.close();
        // Fill: the stroke's colour pooling under the line and fading toward the baseline.
        c.drawPath(path, shaded(gradient(GRAD_TWO, 0, ct, 0, cb,
                (color & 0x00FFFFFF) | 0x70000000, (color & 0x00FFFFFF) | 0x08000000, 0)));
        unshade();
        curvePath(curve, cl, ct, cr, cb, f);
        glowStroke(c, path, color, dp(3.5f));
        // Playhead: a column of light that fades out at both ends, and a lit bead on the curve.
        c.drawRect(px - dp(1f), ct, px + dp(1f), cb,
                shaded(gradient(GRAD_THREE, 0, ct, 0, cb, 0x00E6EDF7, 0x80E6EDF7, 0x10E6EDF7)));
        unshade();
        Fx.glow(c, px, py, dp(26f), (color & 0x00FFFFFF) | 0x70000000);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(px, py, dp(8f), paint);
        paint.setColor(0xFFE6EDF7);
        c.drawCircle(px, py, dp(4f), paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(px - dp(1.2f), py - dp(1.2f), dp(1.6f), paint);
        bold(c, String.format(Locale.US, "%.2f s", Math.min(replayClock, drive)), cr, ct + dp(20f), 16f,
                f >= 1f ? color : TEXT, Paint.Align.RIGHT);
    }

    /** Builds {@link #path} along a curve from the catch to fraction {@code upTo} of the drive. */
    private void curvePath(float[] curve, float cl, float ct, float cr, float cb, float upTo) {
        path.rewind();
        int last = (int) Math.floor(upTo * (SHAPE - 1));
        for (int i = 0; i <= last && i < SHAPE; i++) {
            float x = cl + (cr - cl) * i / (SHAPE - 1f);
            float y = cb - (cb - ct) * curve[i];
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        if (upTo < 1f) {
            path.lineTo(cl + (cr - cl) * upTo, cb - (cb - ct) * sample(curve, upTo));
        }
    }

    /**
     * TODAY vs BEST SESSION: the one comparison the coach was missing. A single best stroke is a
     * lucky stroke; the best session is the shape you held for a whole piece.
     */
    private void drawCompare(Canvas c, float l, float t, float r, float b, float dt) {
        glass(c, l, t, r, b, dp(14f));
        float x = l + dp(14f);
        if (!hasBestSession) {
            label(c, "TODAY vs BEST SESSION", x, t + dp(18f), 10f, FAINT, Paint.Align.LEFT);
            label(c, strokes >= 20
                            ? "No benchmark yet - this session becomes it when you leave."
                            : "Row 20 strokes and this session becomes the one to beat.",
                    x, t + dp(38f), 12f, DIM, Paint.Align.LEFT);
            label(c, strokes + " strokes", r - dp(14f), t + dp(38f), 12f, FAINT, Paint.Align.RIGHT);
            return;
        }
        long ago = todayLocal() - bsDay;
        label(c, "TODAY vs BEST SESSION  ·  " + bsStrokes + " strokes, "
                        + (ago <= 0 ? "today" : ago == 1 ? "yesterday" : ago + " days ago"),
                x, t + dp(15f), 10f, FAINT, Paint.Align.LEFT);
        float todayScore = strokes > 0 ? scoreSum / strokes : 0f;
        float todayPower = sessionAvgPower();
        float todayCons = Float.isNaN(sessionConsistency) ? 0f : sessionConsistency;
        float shapeVs = Float.isNaN(sessionMatch) ? 0f : sessionMatch;
        float[] now = compareShown;
        float ease = Math.min(1f, dt * 4f);
        now[0] += (todayScore - now[0]) * ease;
        now[1] += (shapeVs - now[1]) * ease;
        now[2] += (todayPower - now[2]) * ease;
        now[3] += (todayCons - now[3]) * ease;
        float col = (r - l - dp(28f)) / 4f;
        // A figure that does not exist yet reads as "--", never as a red zero: for the first
        // couple of strokes the session mean and the shape match are simply not computed.
        compareCell(c, x, t, col, "SCORE", now[0], bsScore, 100f, false, strokes > 0);
        compareCell(c, x + col, t, col, "SHAPE vs BEST", now[1], 100f, 100f, true,
                !Float.isNaN(sessionMatch));
        compareCell(c, x + 2 * col, t, col, "POWER", now[2], bsPower, Math.max(1f, bsPower * 1.4f),
                false, sessionPowerCount > 0);
        compareCell(c, x + 3 * col, t, col, "CONSISTENCY", now[3], bsConsistency, 100f, false,
                !Float.isNaN(sessionConsistency));
    }

    /** One comparison: today's figure, a bar, and the best session marked on it. */
    private void compareCell(Canvas c, float x, float t, float w, String name, float now, float best,
                             float scale, boolean target, boolean known) {
        boolean up = now >= best - 0.5f;
        int color = !known ? FAINT : target ? (now >= 90 ? ACCENT : now >= 75 ? WARN : BAD)
                : up ? ACCENT : WARN;
        float bx0 = x;
        float bx1 = x + w - dp(16f);
        // Name and figure share a baseline clear of the strip's header line above them.
        label(c, name, x, t + dp(34f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, known ? Math.round(now) + (target ? "%" : "") : "--", bx1, t + dp(35f), 17f, color,
                Paint.Align.RIGHT);
        float by = t + dp(40f);
        track(c, bx0, by, bx1, by + dp(7f));
        if (known) {
            float end = bx0 + (bx1 - bx0) * clamp01(now / scale);
            gloss(c, bx0, by, end, by + dp(7f), color);
            if (end - bx0 > dp(6f)) {
                Fx.glow(c, end - dp(2f), by + dp(3.5f), dp(12f), (color & 0x00FFFFFF) | 0x50000000);
            }
        }
        if (!known) {
            // The benchmark is still drawn, so the rower can see what they are chasing.
            if (!target && best > 0) {
                bestMark(c, bx0 + (bx1 - bx0) * clamp01(best / scale), by - dp(3f), by + dp(10f));
                label(c, "best " + Math.round(best), bx1, by + dp(18f), 9f, FAINT, Paint.Align.RIGHT);
            }
            label(c, "a few more strokes", bx0, by + dp(18f), 9f, FAINT, Paint.Align.LEFT);
        } else if (!target && best > 0) {
            // The best session marked on the bar: today either clears it or does not.
            bestMark(c, bx0 + (bx1 - bx0) * clamp01(best / scale), by - dp(3f), by + dp(10f));
            label(c, (up ? "+" : "") + Math.round(now - best) + " on your best",
                    bx0, by + dp(18f), 9f, up ? ACCENT : DIM, Paint.Align.LEFT);
            label(c, "best " + Math.round(best), bx1, by + dp(18f), 9f, FAINT, Paint.Align.RIGHT);
        } else {
            // Shortened from "average shape against your best session", which ran into the next cell.
            label(c, "avg shape vs best session", bx0, by + dp(18f), 9f, DIM, Paint.Align.LEFT);
        }
    }

    /** A benchmark tick on a bar: a bright needle with a dark outline so it reads over any fill. */
    private void bestMark(Canvas c, float mx, float top, float bottom) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xC0000000);
        c.drawRoundRect(mx - dp(2.5f), top - dp(1f), mx + dp(2.5f), bottom + dp(1f), dp(2f), dp(2f), paint);
        paint.setColor(0xFFE6EDF7);
        c.drawRoundRect(mx - dp(1.2f), top, mx + dp(1.2f), bottom, dp(1f), dp(1f), paint);
    }

    private void drawTraining(Canvas c, float l, float t, float r, float b, float dt) {
        glass(c, l, t, r, b, dp(14f));
        float x = l + dp(14f);
        if (b - t < dp(48f)) {
            // Nothing is drawn, so nothing may be tapped: clear the hit rects rather than leave
            // last frame's, which would start a drill from a chip that is not on screen.
            Arrays.fill(chipR, 0f);
            Arrays.fill(chipL, 0f);
            return;
        }
        if (challengeOn) {
            drawChallenge(c, l, t, r, b, dt);
            return;
        }
        if (drill < 0) {
            label(c, "DRILLS  ·  tap one: 10 strokes, pass 8        CHALLENGE  ·  hold 90% shape for 20 strokes",
                    x, t + dp(20f), 10f, FAINT, Paint.Align.LEFT);
            float cw = (r - l - dp(28f) - dp(8f) * (CHIPS - 1)) / CHIPS;
            float ct = t + dp(30f);
            float cb = Math.min(b - dp(12f), ct + dp(64f));
            for (int i = 0; i < CHIPS; i++) {
                float cl = x + i * (cw + dp(8f));
                chipL[i] = cl;
                chipT[i] = ct;
                chipR[i] = cl + cw;
                chipB[i] = cb;
                boolean ok = drillAvailable(i);
                boolean isChallenge = i == CHALLENGE;
                if (isChallenge && ok) {
                    // The challenge chip breathes, so it reads as the thing to go for.
                    float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 2.2);
                    chip(c, cl, ct, cl + cw, cb, dp(10f), 0xFF2E2642);
                    paint.setStyle(Paint.Style.STROKE);
                    // A breathing gold rim with a soft bloom under it.
                    paint.setColor(WARN);
                    paint.setAlpha((int) (20 + 40 * pulse));
                    paint.setStrokeWidth(dp(7f));
                    c.drawRoundRect(cl, ct, cl + cw, cb, dp(10f), dp(10f), paint);
                    paint.setStrokeWidth(dp(2f));
                    paint.setAlpha((int) (110 + 130 * pulse));
                    c.drawRoundRect(cl, ct, cl + cw, cb, dp(10f), dp(10f), paint);
                    paint.setAlpha(255);
                    paint.setStyle(Paint.Style.FILL);
                } else {
                    chip(c, cl, ct, cl + cw, cb, dp(10f), ok ? 0xFF183A56 : 0xFF121A26);
                }
                bold(c, chipName(i), cl + cw / 2f, ct + dp(26f), 13f,
                        ok ? (isChallenge ? WARN : TEXT) : FAINT, Paint.Align.CENTER);
                String sub;
                if (!ok) {
                    sub = isChallenge ? "row a best stroke" : calibrated() ? "row 5 strokes first" : "calibrate handle";
                } else if (isChallenge) {
                    sub = challengeRecord >= 0 ? "best " + Math.round(challengeRecord) + "/" + CHALLENGE_TARGET
                            : "20 in a row";
                } else {
                    sub = drillBest[i] >= 0 ? "best " + Math.round(drillBest[i]) + "/10" : "not tried";
                }
                label(c, sub, cl + cw / 2f, ct + dp(46f), 9f, ok ? DIM : FAINT, Paint.Align.CENTER);
            }
            return;
        }
        // Active drill: the rule, ten pips, and what is at stake on the next stroke.
        bold(c, DRILL_NAME[drill], x, t + dp(26f), 18f, WARN, Paint.Align.LEFT);
        wrap(c, drillRule(drill), x + dp(170f), t + dp(22f), r - x - dp(290f), 11f, DIM);
        stopHit[0] = r - dp(96f);
        stopHit[1] = t + dp(8f);
        stopHit[2] = r - dp(12f);
        stopHit[3] = t + dp(40f);
        chip(c, stopHit[0], stopHit[1], stopHit[2], stopHit[3], dp(8f), 0xFF44242F);
        bold(c, "STOP", (stopHit[0] + stopHit[2]) / 2f, stopHit[3] - dp(10f), 12f, BAD, Paint.Align.CENTER);
        float pipY = t + dp(62f);
        float pipR = dp(11f);
        float step = dp(30f);
        lastJudgeTimer = Math.max(0f, lastJudgeTimer - dt);
        for (int i = 0; i < DRILL_STROKES; i++) {
            float px = x + pipR + i * step;
            if (i < drillDone) {
                float grow = i == drillDone - 1 ? 1f + lastJudgeTimer * 0.4f : 1f;
                c.save();
                c.scale(grow, grow, px, pipY);
                litDot(c, px, pipY, pipR, drillResults[i] ? ACCENT : BAD);
                c.restore();
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFF070C14);
                c.drawCircle(px, pipY, pipR, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                // The next stroke pulses: it is the one that counts now.
                float pulse = i == drillDone ? 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6) : 0f;
                paint.setColor(i == drillDone ? WARN : 0xFF3A475C);
                if (i == drillDone) {
                    paint.setAlpha((int) (140 + 115 * pulse));
                }
                c.drawCircle(px, pipY, pipR, paint);
                paint.setAlpha(255);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        float tx = x + DRILL_STROKES * step + dp(10f);
        int failsLeft = DRILL_STROKES - DRILL_PASS - (drillDone - drillPassed);
        bold(c, drillPassed + " passed", tx, pipY - dp(2f), 14f, ACCENT, Paint.Align.LEFT);
        label(c, failsLeft == 0 ? "no misses left - this one must pass" : failsLeft + " misses left",
                tx, pipY + dp(14f), 10f, failsLeft == 0 ? BAD : DIM, Paint.Align.LEFT);
        if (drillDone > 0) {
            label(c, "last: " + drillVerdict + (lastJudgePass ? "  - PASS" : "  - MISS"), x, pipY + dp(32f), 11f,
                    lastJudgePass ? ACCENT : BAD, Paint.Align.LEFT);
        }
    }

    /**
     * THE CHALLENGE: twenty strokes in a row at a 90% shape match. The chain of twenty is drawn
     * link by link and a single miss breaks it back to zero, so every stroke is the one that counts.
     */
    private void drawChallenge(Canvas c, float l, float t, float r, float b, float dt) {
        challengeFlash = Math.max(0f, challengeFlash - dt * 1.6f);
        challengeBreak = Math.max(0f, challengeBreak - dt * 1.2f);
        float live = hasBest && strokes > 0 ? similarity : 0f;
        challengeMatchShown += (live - challengeMatchShown) * Math.min(1f, dt * 5f);
        float x = l + dp(14f);
        // The panel flashes red on a break and teal as the chain grows.
        if (challengeBreak > 0f) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(BAD);
            paint.setAlpha((int) (70 * challengeBreak));
            c.drawRoundRect(l, t, r, b, dp(14f), dp(14f), paint);
            paint.setAlpha(255);
        }
        bold(c, "CHALLENGE", x, t + dp(26f), 18f, WARN, Paint.Align.LEFT);
        label(c, "hold a " + Math.round(CHALLENGE_MATCH) + "% shape match for " + CHALLENGE_TARGET
                        + " strokes - one miss and the chain breaks",
                x + dp(120f), t + dp(24f), 11f, DIM, Paint.Align.LEFT);
        stopHit[0] = r - dp(96f);
        stopHit[1] = t + dp(8f);
        stopHit[2] = r - dp(12f);
        stopHit[3] = t + dp(40f);
        paint.setStyle(Paint.Style.FILL);
        chip(c, stopHit[0], stopHit[1], stopHit[2], stopHit[3], dp(8f), 0xFF44242F);
        bold(c, "STOP", (stopHit[0] + stopHit[2]) / 2f, stopHit[3] - dp(10f), 12f, BAD, Paint.Align.CENTER);

        // The chain: twenty links, the next one pulsing.
        float linkY = t + dp(58f);
        float step = (r - x - dp(150f)) / CHALLENGE_TARGET;
        float rad = Math.min(dp(10f), step * 0.42f);
        for (int i = 0; i < CHALLENGE_TARGET; i++) {
            float cx = x + rad + i * step;
            if (i < challengeRun) {
                if (i > 0) {
                    // The link to the previous one: a lit bar with a bright core.
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setColor(ACCENT);
                    paint.setAlpha(70);
                    paint.setStrokeWidth(dp(7f));
                    c.drawLine(cx - step + rad, linkY, cx - rad, linkY, paint);
                    paint.setAlpha(255);
                    paint.setStrokeWidth(dp(3f));
                    c.drawLine(cx - step + rad, linkY, cx - rad, linkY, paint);
                }
                float grow = i == challengeRun - 1 ? 1f + challengeFlash * 0.5f : 1f;
                c.save();
                c.scale(grow, grow, cx, linkY);
                litDot(c, cx, linkY, rad, ACCENT);
                c.restore();
            } else {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6);
                paint.setColor(i == challengeRun ? WARN : 0xFF3A475C);
                if (i == challengeRun) {
                    paint.setAlpha((int) (140 + 115 * pulse));
                }
                c.drawCircle(cx, linkY, rad, paint);
                paint.setAlpha(255);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        float tx = r - dp(130f);
        bold(c, challengeRun + "/" + CHALLENGE_TARGET, tx, linkY + dp(2f), 22f,
                challengeRun >= CHALLENGE_TARGET - 5 ? ACCENT : TEXT, Paint.Align.LEFT);
        label(c, challengeRun == 0 ? "start the chain"
                        : (CHALLENGE_TARGET - challengeRun) + " to go", tx, linkY + dp(18f), 10f,
                challengeRun == 0 ? DIM : ACCENT, Paint.Align.LEFT);

        // Live shape match against the 90% line: you can see the next stroke coming.
        float bx0 = x;
        float bx1 = r - dp(150f);
        float by = t + dp(84f);
        track(c, bx0, by, bx1, by + dp(12f));
        int mc = challengeMatchShown >= CHALLENGE_MATCH ? ACCENT : challengeMatchShown >= 80 ? WARN : BAD;
        gloss(c, bx0, by, bx0 + (bx1 - bx0) * clamp01(challengeMatchShown / 100f), by + dp(12f), mc);
        float gate = bx0 + (bx1 - bx0) * (CHALLENGE_MATCH / 100f);
        bestMark(c, gate, by - dp(5f), by + dp(17f));
        label(c, "LAST STROKE'S SHAPE MATCH  " + Math.round(challengeMatchShown) + "%",
                bx0, by + dp(28f), 10f, mc, Paint.Align.LEFT);
        label(c, Math.round(CHALLENGE_MATCH) + "%", gate, by + dp(28f), 9f, FAINT, Paint.Align.CENTER);
        String best = challengeBestRun > 0 ? "session best " + challengeBestRun
                : challengeRecord >= 0 ? "record " + Math.round(challengeRecord) + "/" + CHALLENGE_TARGET
                : "no record yet";
        label(c, best, bx1, by + dp(28f), 10f, FAINT, Paint.Align.RIGHT);
        if (!challengeLastPass && challengeBreak > 0f) {
            bold(c, "CHAIN BROKE", tx, by + dp(12f), 14f, BAD, Paint.Align.LEFT);
        }
    }

    /* ---------- the bottom band: map, skill tree, report card ---------- */

    private void drawBottom(Canvas c, float l, float t, float r, float b, float dt) {
        pageAnim = Math.min(1f, pageAnim + dt * 2f);
        glass(c, l, t, r, b, dp(14f));
        // The page first, then the tabs over it: the map's well now runs the full width and its
        // top edge passes under the tabs.
        float ct = t + dp(26f);
        if (page == PAGE_SKILLS) {
            badgeUnseen = false;
            drawSkills(c, l, t, r, b, ct, dt);
        } else if (page == PAGE_REPORT) {
            drawReport(c, l, t, r, b, ct, dt);
        } else {
            drawMap(c, l, t, r, b, ct);
        }
        // Tabs, top right.
        float tw = dp(92f);
        float th = dp(24f);
        float tabTop = t + dp(4f);
        for (int i = 0; i < PAGE_NAME.length; i++) {
            float tl = r - dp(10f) - (PAGE_NAME.length - i) * (tw + dp(6f)) + dp(6f);
            tabL[i] = tl;
            tabT[i] = tabTop;
            tabR[i] = tl + tw;
            tabB[i] = tabTop + th;
            boolean on = page == i;
            chip(c, tl, tabTop, tl + tw, tabTop + th, dp(8f), on ? 0xFF183A56 : 0xFF121A26);
            if (on) {
                paint.setColor(0x4035D0BA);
                c.drawRoundRect(tl + dp(6f), tabTop + th - dp(6f), tl + tw - dp(6f), tabTop + th,
                        dp(3f), dp(3f), paint);
                paint.setColor(ACCENT);
                c.drawRoundRect(tl + dp(10f), tabTop + th - dp(4f), tl + tw - dp(10f), tabTop + th - dp(2f),
                        dp(1f), dp(1f), paint);
            }
            String name = PAGE_NAME[i];
            // A dot on SKILLS while a badge is newly earned, so the tab asks to be opened.
            bold(c, name, tl + tw / 2f, tabTop + dp(16f), 11f, on ? TEXT : DIM, Paint.Align.CENTER);
            if (i == PAGE_SKILLS && badgeUnseen) {
                litDot(c, tl + tw - dp(10f), tabTop + dp(8f), dp(3.5f), ACCENT);
            }
        }
    }

    /**
     * The skill tree: nine badges over five tiers, the connectors lit once a tier is earned.
     * A locked badge shows what it needs and how far along the rower is, so nothing is a mystery.
     */
    private void drawSkills(Canvas c, float l, float t, float r, float b, float ct, float dt) {
        int have = 0;
        for (int i = 0; i < BADGES; i++) {
            if (hasBadge(i)) {
                have++;
            }
            badgeFlash[i] = Math.max(0f, badgeFlash[i] - dt);
        }
        label(c, "SKILL TREE  ·  " + have + " of " + BADGES + " earned", l + dp(14f), t + dp(18f), 9f,
                FAINT, Paint.Align.LEFT);

        float colW = (r - l - dp(28f)) / BADGE_TIERS;
        float bandTop = ct + dp(6f);
        float bandH = b - dp(8f) - bandTop;
        if (bandH < dp(40f)) {
            return;
        }
        float rowH = bandH / 3f;
        float rad = Math.min(dp(18f), rowH * 0.26f);
        // The name and requirement sit under each node, so the nodes themselves use a shorter band.
        float usable = Math.max(dp(30f), bandH - dp(28f));
        // Positions first: the connectors are drawn underneath everything.
        for (int tier = 0; tier < BADGE_TIERS; tier++) {
            int n = 0;
            for (int i = 0; i < BADGES; i++) {
                if (BADGE_TIER[i] == tier) {
                    n++;
                }
            }
            int k = 0;
            for (int i = 0; i < BADGES; i++) {
                if (BADGE_TIER[i] != tier) {
                    continue;
                }
                badgeX[i] = l + dp(14f) + tier * colW + colW / 2f;
                badgeY[i] = bandTop + usable * (k + 0.5f) / n;
                k++;
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        for (int i = 0; i < BADGES; i++) {
            for (int p = 0; p < BADGES; p++) {
                if ((BADGE_PREREQ[i] & (1 << p)) == 0) {
                    continue;
                }
                boolean lit = hasBadge(p);
                if (lit) {
                    // A lit connector glows: a wide faint pass under the line.
                    paint.setColor(ACCENT);
                    paint.setAlpha(40);
                    paint.setStrokeWidth(dp(7f));
                    c.drawLine(badgeX[p] + rad, badgeY[p], badgeX[i] - rad, badgeY[i], paint);
                    paint.setStrokeWidth(dp(2f));
                }
                paint.setColor(lit ? ACCENT : 0xFF27324A);
                paint.setAlpha(lit ? 150 : 90);
                c.drawLine(badgeX[p] + rad, badgeY[p], badgeX[i] - rad, badgeY[i], paint);
                paint.setAlpha(255);
                if (lit && !hasBadge(i)) {
                    // A spark runs the connector into the badge that is now available.
                    float f = (float) ((sessionSeconds * 0.55 + i * 0.13 + p * 0.07) % 1.0);
                    float sx = badgeX[p] + rad + (badgeX[i] - rad - badgeX[p] - rad) * f;
                    float sy = badgeY[p] + (badgeY[i] - badgeY[p]) * f;
                    litDot(c, sx, sy, dp(3f), WARN);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2f));
                }
            }
        }
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < BADGES; i++) {
            boolean earned = hasBadge(i);
            boolean open = (badges & BADGE_PREREQ[i]) == BADGE_PREREQ[i];
            float cx = badgeX[i];
            float cy = badgeY[i];
            float pop = 1f + badgeFlash[i] * 0.25f;
            if (earned) {
                c.save();
                c.scale(pop, pop, cx, cy);
                drawBadgeMedal(c, cx, cy, rad, ACCENT, badgeFlash[i] > 0f ? 1f : 0f);
                c.restore();
            } else {
                if (open) {
                    Fx.glow(c, cx, cy, rad * 1.8f, 0x30F0B132);
                }
                c.drawCircle(cx, cy, rad, shaded(gradient(GRAD_TWO, 0, cy - rad, 0, cy + rad,
                        0xFF1A2536, 0xFF0A1019, 0)));
                unshade();
                paint.setStyle(Paint.Style.FILL);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(open ? WARN : 0xFF33405A);
                c.drawCircle(cx, cy, rad, paint);
                // Progress arc around the rim.
                float prog = badgeProgress(i);
                if (open && prog > 0.01f) {
                    arcRect.set(cx - rad, cy - rad, cx + rad, cy + rad);
                    paint.setStrokeWidth(dp(3.5f));
                    paint.setColor(WARN);
                    c.drawArc(arcRect, -90f, 360f * clamp01(prog), false, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                if (!open) {
                    // A little padlock for a badge whose prerequisites are not in yet.
                    paint.setColor(0xFF4A5670);
                    c.drawRoundRect(cx - rad * 0.32f, cy - rad * 0.05f, cx + rad * 0.32f, cy + rad * 0.42f,
                            rad * 0.1f, rad * 0.1f, paint);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(rad * 0.14f);
                    arcRect.set(cx - rad * 0.2f, cy - rad * 0.42f, cx + rad * 0.2f, cy + rad * 0.1f);
                    c.drawArc(arcRect, 180f, 180f, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                } else {
                    bold(c, Math.round(badgeProgress(i) * 100) + "%", cx, cy + dp(5f), 11f, WARN, Paint.Align.CENTER);
                }
            }
            bold(c, BADGE_NAME[i], cx, cy + rad + dp(12f), 10f, earned ? ACCENT : open ? TEXT : FAINT,
                    Paint.Align.CENTER);
            if (!earned) {
                label(c, BADGE_NEED[i], cx, cy + rad + dp(23f), 8f, open ? DIM : FAINT, Paint.Align.CENTER);
            }
        }
    }

    /**
     * The weekly report card: this week graded against last week, category by category. The bars
     * grow into place when the page opens so the week reads as a result rather than a table.
     */
    private void drawReport(Canvas c, float l, float t, float r, float b, float ct, float dt) {
        boolean haveLast = prevWeekIndex != Long.MIN_VALUE && prevWeek[0] > 0f;
        long back = haveLast ? weekIndex - prevWeekIndex : 0;
        label(c, "WEEKLY REPORT CARD  ·  " + Math.round(week[0]) + " strokes this week"
                        + (haveLast ? "   vs " + Math.round(prevWeek[0]) + " "
                        + (back == 1 ? "last week" : back + " weeks ago") : "   (no earlier week yet)"),
                l + dp(14f), t + dp(18f), 9f, FAINT, Paint.Align.LEFT);
        float bandTop = ct + dp(4f);
        float bandH = b - dp(10f) - bandTop;
        if (bandH < dp(40f)) {
            return;
        }
        if (week[0] <= 0f) {
            label(c, "Row this week and it is graded here against last week.", (l + r) / 2f,
                    bandTop + bandH / 2f, 12f, FAINT, Paint.Align.CENTER);
            return;
        }
        // Overall grade, left.
        float gradeW = dp(190f);
        float overall = weekAvg(week, 1);
        float overallLast = haveLast ? weekAvg(prevWeek, 1) : Float.NaN;
        chip(c, l + dp(14f), bandTop, l + dp(14f) + gradeW, b - dp(10f), dp(12f), 0xFF141E2E);
        float pop = 1f + (1f - smooth(pageAnim)) * 0.5f;
        if (overall > 0f) {
            // The grade sits in a pool of its own colour.
            Fx.glow(c, l + dp(14f) + gradeW * 0.32f, bandTop + bandH * 0.56f, dp(56f),
                    (gradeColor(overall) & 0x00FFFFFF) | 0x40000000);
        }
        c.save();
        c.translate(l + dp(14f) + gradeW * 0.32f, bandTop + bandH * 0.56f);
        c.scale(pop, pop);
        bold(c, grade(overall), 0, dp(20f), 58f, gradeColor(overall), Paint.Align.CENTER);
        c.restore();
        label(c, "THIS WEEK", l + dp(14f) + gradeW * 0.32f, bandTop + dp(16f), 9f, FAINT, Paint.Align.CENTER);
        bold(c, String.valueOf(Math.round(overall)), l + dp(14f) + gradeW * 0.72f, bandTop + bandH * 0.45f,
                20f, TEXT, Paint.Align.CENTER);
        label(c, "AVG SCORE", l + dp(14f) + gradeW * 0.72f, bandTop + bandH * 0.45f + dp(14f), 8f, FAINT,
                Paint.Align.CENTER);
        if (haveLast) {
            float d = overall - overallLast;
            label(c, (d >= 0 ? "+" : "") + String.format(Locale.US, "%.1f", d) + " on last week",
                    l + dp(14f) + gradeW * 0.72f, bandTop + bandH * 0.75f, 9f, d >= 0 ? ACCENT : BAD,
                    Paint.Align.CENTER);
        }
        label(c, Math.round(week[5]) + " drills passed  ·  "
                        + Math.round(week[6] + activeSeconds / 60.0) + " min",
                l + dp(14f) + gradeW / 2f, b - dp(18f), 9f, DIM, Paint.Align.CENTER);

        // Four graded rows.
        float rl = l + dp(24f) + gradeW;
        float rr = r - dp(16f);
        float rowH = bandH / REPORT_ROW.length;
        for (int i = 0; i < REPORT_ROW.length; i++) {
            // shape, consistency, ratio, score -> week fields 2, 3, 4, 1.
            int field = i == 0 ? 2 : i == 1 ? 3 : i == 2 ? 4 : 1;
            float now = weekAvg(week, field);
            float was = haveLast ? weekAvg(prevWeek, field) : Float.NaN;
            reportShown[i] += (now * smooth(pageAnim) - reportShown[i]) * Math.min(1f, dt * 6f);
            float y = bandTop + rowH * i + rowH * 0.5f;
            label(c, REPORT_ROW[i], rl, y - dp(6f), 10f, DIM, Paint.Align.LEFT);
            bold(c, grade(now), rl + dp(120f), y + dp(2f), 16f, gradeColor(now), Paint.Align.CENTER);
            float bx0 = rl + dp(140f);
            float bx1 = rr - dp(150f);
            float by = y - dp(6f);
            track(c, bx0, by, bx1, by + dp(12f));
            gloss(c, bx0, by, bx0 + (bx1 - bx0) * clamp01(reportShown[i] / 100f), by + dp(12f),
                    gradeColor(now));
            if (!Float.isNaN(was) && was > 0) {
                bestMark(c, bx0 + (bx1 - bx0) * clamp01(was / 100f), by - dp(4f), by + dp(16f));
            }
            bold(c, String.valueOf(Math.round(now)), bx1 + dp(34f), y + dp(2f), 15f, TEXT, Paint.Align.RIGHT);
            if (!Float.isNaN(was) && was > 0) {
                float d = now - was;
                label(c, (d >= 0 ? "UP  +" : "DOWN  ") + String.format(Locale.US, "%.1f", d) + " vs last week",
                        bx1 + dp(44f), y + dp(2f), 9f, d >= 0 ? ACCENT : BAD, Paint.Align.LEFT);
            } else {
                label(c, "no earlier week", bx1 + dp(44f), y + dp(2f), 9f, FAINT, Paint.Align.LEFT);
            }
        }
    }

    private static String grade(float v) {
        return v >= 90 ? "A" : v >= 80 ? "B" : v >= 70 ? "C" : v >= 60 ? "D" : v >= 45 ? "E" : "F";
    }

    private static int gradeColor(float v) {
        return v >= 80 ? ACCENT : v >= 60 ? WARN : BAD;
    }

    private void drawLength(Canvas c, float l, float t, float r, float b, float dt) {
        glass(c, l, t, r, b, dp(14f));
        label(c, "DRIVE LENGTH", l + dp(14f), t + dp(20f), 10f, FAINT, Paint.Align.LEFT);
        if (!calibrated()) {
            wrap(c, "Calibrate the handle (CALIBRATE on the home screen) and every drive's length shows here against your reach.",
                    l + dp(14f), t + dp(54f), r - l - dp(28f), 13f, DIM);
            // A ghosted ruler along the bottom: what this card becomes once the handle is measured.
            float gx0 = l + dp(14f);
            float gx1 = r - dp(14f);
            float gy = b - dp(30f);
            if (gy > t + dp(120f)) {
                track(c, gx0, gy, gx1, gy + dp(14f));
                paint.setColor(0x14FFFFFF);
                for (int i = 1; i < 20; i++) {
                    float tx = gx0 + (gx1 - gx0) * i / 20f;
                    float th = i % 5 == 0 ? dp(14f) : dp(6f);
                    c.drawRect(tx, gy, tx + dp(1f), gy + th, paint);
                }
            }
            return;
        }
        float reach = reachTarget();
        if (Float.isNaN(lastLength)) {
            label(c, "Row a stroke.", l + dp(14f), t + dp(54f), 13f, DIM, Paint.Align.LEFT);
            return;
        }
        lengthShown += (lastLength - lengthShown) * Math.min(1f, dt * 6f);
        boolean haveReach = !Float.isNaN(reach) && reach > 0;
        boolean shortStroke = haveReach && lastLength < reach * 0.95f;
        int color = !haveReach ? TEXT : shortStroke ? (lastLength < reach * 0.9f ? BAD : WARN) : ACCENT;
        bold(c, Math.round(lastLength * 100) + " cm", l + dp(14f), t + dp(62f), 34f, color, Paint.Align.LEFT);
        String verdict;
        if (!haveReach) {
            verdict = "learning your reach - " + (5 - Math.min(5, lengthCount)) + " more strokes";
        } else if (shortStroke) {
            verdict = Math.round((reach - lastLength) * 100) + " cm short of your reach";
        } else {
            verdict = "full length";
        }
        label(c, verdict, r - dp(14f), t + dp(56f), 12f, color, Paint.Align.RIGHT);

        // Ruler: 0 to a little past the reach; band at 95-110% of reach, ticks for recent strokes.
        float scale = haveReach ? reach * 1.25f : Math.max(lastLength * 1.3f, 1.0f);
        float x0 = l + dp(14f);
        float x1 = r - dp(14f);
        float ry = t + dp(84f);
        float rh = Math.max(dp(16f), Math.min(dp(28f), b - ry - dp(34f)));
        track(c, x0, ry, x1, ry + rh);
        // Ruler ticks every 10 cm, every 50 cm taller.
        paint.setColor(0x1CFFFFFF);
        int tick = 1;
        for (float m = 0.1f; m < scale && tick < 40; m += 0.1f, tick++) {
            float tx = x0 + (x1 - x0) * m / scale;
            float th = tick % 5 == 0 ? rh * 0.5f : rh * 0.25f;
            c.drawRect(tx, ry + rh - th, tx + dp(1f), ry + rh, paint);
        }
        if (haveReach) {
            // The 95-110% band of light, soft at both ends.
            float zl = x0 + (x1 - x0) * reach * 0.95f / scale;
            float zr = x0 + (x1 - x0) * Math.min(1f, reach * 1.1f / scale);
            float zp = (zr - zl) * 0.3f;
            c.drawRect(zl - zp, ry, zr + zp, ry + rh,
                    shaded(gradient(GRAD_BAND, zl - zp, 0, zr + zp, 0, 0x0035D0BA, 0x4435D0BA, 0)));
            unshade();
            float rxp = x0 + (x1 - x0) * reach / scale;
            paint.setColor(0x4035D0BA);
            c.drawRect(rxp - dp(4f), ry - dp(6f), rxp + dp(4f), ry + rh + dp(6f), paint);
            paint.setColor(ACCENT);
            c.drawRect(rxp - dp(1.5f), ry - dp(6f), rxp + dp(1.5f), ry + rh + dp(6f), paint);
            label(c, "REACH " + Math.round(reach * 100), rxp, ry + rh + dp(18f), 9f, ACCENT, Paint.Align.CENTER);
        }
        float lenEnd = x0 + (x1 - x0) * clamp01(lengthShown / scale);
        gloss(c, x0, ry + rh * 0.25f, lenEnd, ry + rh * 0.75f, color);
        Fx.glow(c, lenEnd, ry + rh * 0.5f, dp(20f), (color & 0x00FFFFFF) | 0x60000000);
        // The last ten strokes as ticks, older ones fainter.
        int n = Math.min(10, lengthCount);
        for (int k = 0; k < n; k++) {
            float v = lengths[lengthCount - 1 - k];
            float tx = x0 + (x1 - x0) * clamp01(v / scale);
            paint.setColor(0xFFE6EDF7);
            paint.setAlpha(k == 0 ? 255 : 150 - k * 12);
            c.drawRect(tx - dp(1f), ry - dp(3f), tx + dp(1f), ry + rh + dp(3f), paint);
        }
        paint.setAlpha(255);
        label(c, "0", x0, ry + rh + dp(18f), 9f, FAINT, Paint.Align.LEFT);
    }

    private void drawMap(Canvas c, float l, float t, float r, float b, float ct) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        String head = "SESSION CONSISTENCY MAP  ·  one column per stroke, catch at top, bright = power";
        label(c, head, l + dp(14f), t + dp(18f), 9f, FAINT, Paint.Align.LEFT);
        String summary = Float.isNaN(sessionConsistency) ? "row 3 strokes"
                : Math.round(sessionConsistency) + "% consistent over " + mapCount + " strokes";
        // The tabs own the top right of this band, so the summary sits left of them. Placed after
        // the header's measured width: at the 3.24 text size the old fixed offset put it on top of
        // the end of the header line.
        float band = r - l;
        float headEnd = l + dp(14f) + labelWidth(head, 9f) + dp(18f);
        float sx = Math.max(headEnd, l + dp(14f) + Math.min(dp(416f), band * 0.30f));
        bold(c, summary, sx, t + dp(19f), 12f,
                Float.isNaN(sessionConsistency) ? FAINT : sessionConsistency >= 75 ? ACCENT : sessionConsistency >= 60 ? WARN : BAD,
                Paint.Align.LEFT);
        if (!Float.isNaN(bestSessionScore)) {
            float bx = Math.max(sx + boldWidth(summary, 12f) + dp(24f),
                    l + dp(14f) + Math.min(dp(686f), band * 0.50f));
            label(c, "best session score " + Math.round(bestSessionScore), bx, t + dp(18f), 9f,
                    FAINT, Paint.Align.LEFT);
        }
        float ml = l + dp(14f);
        float mr = r - dp(14f);
        float mt = ct;
        float mb = b - dp(8f);
        if (mb - mt < dp(10f)) {
            return;
        }
        // The well the map sits in, full width, with a faint slot every ten strokes so the empty
        // part of the band reads as the rest of the session still to fill.
        int shown = Math.max(mapCount, 60);
        float colW = (mr - ml) / shown;
        c.drawRoundRect(ml - dp(4f), mt - dp(3f), mr + dp(4f), mb + dp(3f), dp(6f), dp(6f),
                shaded(gradient(GRAD_TWO, 0, mt, 0, mb, 0xFF060B13, 0xFF0A121D, 0)));
        unshade();
        paint.setColor(0x0CFFFFFF);
        for (int k = 10; k < shown; k += 10) {
            float gx = ml + colW * k;
            c.drawRect(gx, mt, gx + dp(1f), mb, paint);
        }
        if (mapCount == 0) {
            label(c, "Each stroke adds a column. Steady strokes make smooth bands.", (ml + mr) / 2f, (mt + mb) / 2f + dp(4f), 11f, FAINT, Paint.Align.CENTER);
            return;
        }
        if (mapBitmap == null) {
            mapBitmap = Bitmap.createBitmap(MAP, MAP_ROWS, Bitmap.Config.ARGB_8888);
            mapDirty = true;
        }
        if (mapDirty) {
            mapBitmap.setPixels(mapPixels, 0, MAP, 0, 0, MAP, MAP_ROWS);
            mapDirty = false;
        }
        // Columns fill from the left; each is at least a few pixels wide until the map is full.
        mapSrc.set(0, 0, mapCount, MAP_ROWS);
        mapDst.set(ml, mt, ml + colW * mapCount, mb);
        c.drawBitmap(mapBitmap, mapSrc, mapDst, bitmapPaint);
        // Glass over the map: a sheen on the upper part, so it sits under the panel's surface.
        c.drawRect(ml, mt, ml + colW * mapCount, mt + (mb - mt) * 0.45f,
                shaded(gradient(GRAD_TWO, 0, mt, 0, mt + (mb - mt) * 0.45f, 0x22FFFFFF, 0x00FFFFFF, 0)));
        unshade();
        // Newest column marker: a lit edge with a bloom.
        float nx = ml + colW * mapCount;
        paint.setColor(0x30E6EDF7);
        c.drawRect(nx - dp(3f), mt, nx + dp(5f), mb, paint);
        paint.setColor(0xE6E6EDF7);
        c.drawRect(nx, mt, nx + dp(2f), mb, paint);
    }

    /**
     * A live trace of the paddle through the last few seconds: the line climbs on every drive and
     * falls away on the recovery, so the rhythm of the piece is visible at a glance.
     */
    private void drawLiveTrace(Canvas c, float l, float t, float r, float b, float dt) {
        traceClock += dt;
        if (traceClock >= 0.05f) {
            traceClock = 0f;
            float v = status == null ? 0f : (float) status.meter.paddleRate;
            traceMax = Math.max(traceMax * 0.995f, v);
            trace[traceHead] = v;
            traceHead = (traceHead + 1) % trace.length;
            traceCount = Math.min(trace.length, traceCount + 1);
        }
        glass(c, l, t, r, b, dp(14f));
        label(c, "PADDLE  ·  LAST 15 SECONDS", l + dp(14f), t + dp(20f), 10f, FAINT, Paint.Align.LEFT);
        float top = t + dp(30f);
        float bottom = b - dp(14f);
        float gl = l + dp(14f);
        float gr = r - dp(14f);
        // Scope graticule: faint lines every quarter of height and every 2.5 s of time.
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x0EFFFFFF);
        for (int i = 1; i < 4; i++) {
            float gy = bottom - (bottom - top) * i / 4f;
            c.drawRect(gl, gy, gr, gy + dp(1f), paint);
        }
        for (int i = 1; i < 6; i++) {
            float gx = gl + (gr - gl) * i / 6f;
            c.drawRect(gx, top, gx + dp(1f), bottom, paint);
        }
        paint.setColor(0x30FFFFFF);
        c.drawRect(gl, bottom - dp(1f), gr, bottom, paint);
        if (traceCount < 2 || traceMax <= 1f) {
            return;
        }
        float step = (r - l - dp(28f)) / (trace.length - 1f);
        path.rewind();
        path.moveTo(gl, bottom);
        float headX = gl;
        float headY = bottom;
        for (int i = 0; i < traceCount; i++) {
            int idx = (traceHead - traceCount + i + trace.length) % trace.length;
            float x = gl + i * step;
            float y = bottom - (bottom - top) * Math.min(1f, trace[idx] / traceMax);
            path.lineTo(x, y);
            headX = x;
            headY = y;
        }
        path.lineTo(gl + (traceCount - 1) * step, bottom);
        path.close();
        c.drawPath(path, shaded(gradient(GRAD_TWO, 0, top, 0, bottom, 0x6035D0BA, 0x0635D0BA, 0)));
        unshade();
        path.rewind();
        for (int i = 0; i < traceCount; i++) {
            int idx = (traceHead - traceCount + i + trace.length) % trace.length;
            float x = gl + i * step;
            float y = bottom - (bottom - top) * Math.min(1f, trace[idx] / traceMax);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        glowStroke(c, path, ACCENT, dp(2.5f));
        // The oldest seconds fade into the glass, so the trace reads as flowing in from the left.
        float fade = Math.min(dp(90f), (gr - gl) * 0.12f);
        c.drawRect(gl - dp(4f), top - dp(4f), gl + fade, bottom + dp(1f),
                shaded(gradient(GRAD_TWO, gl - dp(4f), 0, gl + fade, 0, 0xF00D1522, 0x000D1522, 0)));
        unshade();
        // The pen: where the paddle is right now.
        litDot(c, headX, headY, dp(5f), ACCENT);
    }

    private void metric(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y, 22f, TEXT, Paint.Align.LEFT);
        label(c, caption, x, y + dp(16f), 9f, FAINT, Paint.Align.LEFT);
    }

    // Wrapped lines, cached: the cue body, the drill rule and the drive-length note are wrapped
    // every frame, and splitting and re-measuring them allocated a dozen strings a frame.
    private static final int WRAP_SLOTS = 6;
    private final String[] wrapText = new String[WRAP_SLOTS];
    private final int[] wrapWidth = new int[WRAP_SLOTS];
    private final float[] wrapSize = new float[WRAP_SLOTS];
    private final String[][] wrapLines = new String[WRAP_SLOTS][];
    private int wrapNext;

    private void wrap(Canvas c, String text, float x, float y, float width, float sizeDp, int color) {
        int w = Math.round(width);
        String[] lines = null;
        for (int i = 0; i < WRAP_SLOTS; i++) {
            if (wrapLines[i] != null && wrapWidth[i] == w && wrapSize[i] == sizeDp && text.equals(wrapText[i])) {
                lines = wrapLines[i];
                break;
            }
        }
        if (lines == null) {
            lines = wrapLinesFor(text, width, sizeDp);
            int slot = wrapNext;
            wrapNext = (wrapNext + 1) % WRAP_SLOTS;
            wrapText[slot] = text;
            wrapWidth[slot] = w;
            wrapSize[slot] = sizeDp;
            wrapLines[slot] = lines;
        }
        float step = dp(shownDp(sizeDp)) * 1.3f;
        for (int i = 0; i < lines.length; i++) {
            bold(c, lines[i], x, y + i * step, sizeDp, color, Paint.Align.LEFT);
        }
    }

    /**
     * Splits {@code text} into lines no wider than {@code width}, measured at the size bold()
     * actually draws (the 3.24 text curve) - measured at the nominal size, lines ran long and
     * spilled out of their panels, as the cue body and the drive-length note both did.
     */
    private String[] wrapLinesFor(String text, float width, float sizeDp) {
        textPaint.setTextSize(dp(shownDp(sizeDp)));
        String[] words = text.split(" ");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (textPaint.measureText(trial) > width && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(trial);
            }
        }
        out.add(line.toString());
        return out.toArray(new String[0]);
    }
}
