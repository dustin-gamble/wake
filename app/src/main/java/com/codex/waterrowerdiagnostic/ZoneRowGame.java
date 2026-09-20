package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;

/**
 * ZONE ROW: a timed piece read off two lane bars, the whole screen one instrument.
 *
 * <p>Layout follows a commercial rowing workout screen the rower likes - elapsed / target /
 * remaining across the top, a split bar across four effort zones, a stroke-rate bar with a
 * target band, and a row of figures along the bottom - with WAKE's own names, colours and zone
 * scale. Deliberately not a copy: nothing here uses another product's name, marks or type.
 *
 * <p>Zones are split ranges set from the rower's learned profile at the start of every piece: 50,
 * 25 and 7 seconds slower than their typical split, then 9 and 23 faster (for a 2:08 rower, 3:00 /
 * 2:35 / 2:15 / 1:59 / 1:45). Each zone gets an equal quarter of the bar. In FREE and STREAK, tap a
 * zone name to make it the target; the rate bar's band follows.
 *
 * <p>Plans replace the old Intervals, Sprint Ladder and The Run cards. PYRAMID, LADDER and SPRINTS
 * are schedules of {zone, seconds} segments drawn as a coloured timeline along the top, scored by
 * the share of time spent in each segment's zone. STREAK counts the longest run at the target zone
 * or faster, with three seconds' grace for a stroke that dips.
 *
 * <p>CUSTOM is the rower's own schedule, built in an editor drawn over the bars (up to twelve
 * segments of 30 s - 10 min, saved as {@code zonerow.custom}); the piece waits for START.
 *
 * <p>LOCK runs in every plan but HEART: split in the target zone and rate in its band together, as
 * shown on the bars. Every 20 s held raises a multiplier to x5, points accrue at the multiplier per
 * second, and a dip is forgiven for 2.5 s. Longest lock is {@code zonerow.lock}.
 *
 * <p>Every finished piece is saved as metres every 10 s under {@code zonerow.trace.<seconds>}. The
 * next piece of that length shows it as a LAST post on the split bar (its split at this moment, and
 * the gap in metres), and the finish projection compares against its total. The finish screen charts
 * time in each zone.
 *
 * <p>3.23.0 adds five things, all built on the above:
 * <ul>
 *   <li><b>COACH</b> builds a plan at the start of every piece from the zone the rower is furthest
 *       behind on - the zone the last piece asked for and got least of ({@code zonerow.last}), or
 *       failing that the one thinnest against this week's share. Intervals at that zone, long and
 *       few at the bottom of the scale, short and many at the top.</li>
 *   <li><b>WEEK IN ZONE</b> keeps this week's seconds per zone ({@code zonerow.week}, Monday to
 *       Sunday) against the rower's own weekly goal split {@link #WEEK_SHARE}, and the bars fill as
 *       they row.</li>
 *   <li><b>SPLIT RESCUE</b>: a lock worth keeping does not die when it slips. A 30 s window opens,
 *       chevrons point at the zone that wins it back, and 1.5 s back on target hands the whole lock
 *       back at the level it broke at. Most rescues in a piece is {@code zonerow.rescues}.</li>
 *   <li><b>PAST PIECES</b>: the last dozen scores of this kind of piece as a sparkline, the last one
 *       dashed across it as the mark to beat, this piece's live score running along the right.</li>
 *   <li><b>COOL DOWN</b>: three descending rate steps drawn as a staircase with a rower walking down
 *       it, over the live rate bar. Share held in band is {@code zonerow.cool}.</li>
 * </ul>
 *
 * <p>Shown without the vitals strip, since it is the vitals. Calories come from {@link PulseMeter}'s
 * work at a 25% muscle efficiency: measured from the paddle's pulses, and absolute once the rower
 * has done the load-scale calibration (the label then reads MEASURED).
 */
final class ZoneRowGame extends GameView {

    static final String[] ZONES = {"GLIDE", "CRUISE", "PUSH", "SURGE"};
    private static final int[] ZONE_COLORS = {0xFF6F8CFF, 0xFF35D0BA, 0xFFF0B132, 0xFFF0655D};
    /** Zone edges in seconds per 500 m, slowest first. Set from the profile in applyProfile(). */
    private final float[] edges = {180f, 155f, 135f, 119f, 105f};
    /** Stroke-rate target band per zone, strokes per minute, around the rower's typical rate. */
    private final float[][] rateBands = {{16f, 20f}, {20f, 24f}, {24f, 28f}, {28f, 34f}};

    /** Workout plans. Each segment is {zone, seconds}; FREE and STREAK use the length chip. */
    enum Plan {
        FREE(null),
        /** Built at the start of every piece from the zone the rower is furthest behind on. */
        COACH(null),
        PYRAMID(new int[][]{{0, 120}, {1, 120}, {2, 120}, {3, 60}, {2, 120}, {1, 120}, {0, 60}}),
        LADDER(new int[][]{{1, 180}, {0, 60}, {2, 180}, {0, 60}, {3, 120}, {0, 60}, {2, 180}, {1, 120}}),
        SPRINTS(sprints()),
        /** The rower's own schedule, built in the in-canvas editor and kept as zonerow.custom. */
        CUSTOM(null),
        STREAK(null),
        /** Zones by heart rate from a Bluetooth strap, as a share of max heart rate. */
        HEART(null);

        final int[][] segments;

        Plan(int[][] segments) {
            this.segments = segments;
        }

        int seconds() {
            int total = 0;
            if (segments != null) {
                for (int[] s : segments) {
                    total += s[1];
                }
            }
            return total;
        }

        /** Three minutes CRUISE, eight 30 s SURGE / 90 s GLIDE, two minutes CRUISE. */
        private static int[][] sprints() {
            int[][] plan = new int[18][];
            plan[0] = new int[]{1, 180};
            for (int i = 0; i < 8; i++) {
                plan[1 + i * 2] = new int[]{3, 30};
                plan[2 + i * 2] = new int[]{0, 90};
            }
            plan[17] = new int[]{1, 120};
            return plan;
        }
    }

    private static final float RATE_MAX = 45f;
    private static final int[] PIECE_MINUTES = {10, 20, 30, 5};

    private static final int TRACK = 0xFF13233F;
    private static final int DIVIDER = 0xFF1F355A;
    private static final int PILL = 0xFF10203A;
    private static final int PILL_EDGE = 0xFF2A3F63;
    private static final int TRACE = 0xFFFF8A7A;

    private final PersonalBests bests;
    private int pieceIndex;
    private int targetZone = 2;
    private Plan plan = Plan.FREE;
    private final double[] segmentInZone = new double[18];
    private double streak;
    private double bestStreak;
    private double streakGrace;
    /** Max heart rate for the HEART plan's zones. */
    private float maxHeart = 185f;
    private float shownHeart;
    private static final float[][] HEART_BANDS = {{0.55f, 0.65f}, {0.65f, 0.75f}, {0.75f, 0.85f}, {0.85f, 0.95f}};

    private double pieceSeconds;
    private double pieceStartMeters;
    private double finalMeters = -1;
    private double joules;
    /** The meter's session work at the last frame, so only work done during the piece counts. */
    private double lastMeterWork = -1;
    private boolean energyMeasured;
    private double inZoneSeconds;
    private boolean paused;
    private boolean finished;
    private boolean newBest;

    private float shownSplitFrac;
    private float shownPace;
    private float shownRate;
    private float shownWatts;

    /** Three seconds of pulse effort: the shape of the last stroke or two, beside the clock. */
    private final float[] trace = new float[90];
    private int traceHead;
    private float traceClock;

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final RectF restartHit = new RectF();
    private final RectF pauseHit = new RectF();
    private final Typeface numbers = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface labels = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    /* ---------- the custom plan and its editor ---------- */

    private static final String CUSTOM_KEY = "zonerow.custom";
    private static final int CUSTOM_MAX = 12;
    private static final int CUSTOM_STEP = 30;
    private static final int CUSTOM_MIN_SECONDS = 30;
    private static final int CUSTOM_MAX_SECONDS = 600;
    private static final String[] EDIT_BUTTONS = {"PREV", "ZONE", "\u2212 30 S", "+ 30 S", "ADD", "REMOVE", "NEXT"};
    /** The rower's own schedule. Replaced (not mutated in place) on every edit, so it is never torn. */
    private int[][] custom = {{1, 240}, {3, 60}, {0, 120}, {3, 60}, {0, 120}, {1, 240}};
    /** The schedule being rowed: the plan's own, the custom one, or null for FREE, STREAK and HEART. */
    private int[][] segs;
    /** The editor is up: the piece does not run until the rower taps START. */
    private boolean editing;
    private int editIndex;
    private final RectF[] editHits = new RectF[EDIT_BUTTONS.length];
    private final RectF startHit = new RectF();
    private float blocksLeft;
    private float blocksWidth;
    private float blocksTop;
    private float blocksBottom;

    /* ---------- lock: the zone and the rate held together ---------- */

    /** Each 20 s of lock raises the multiplier, up to x5. */
    private static final float LOCK_STEP = 20f;
    private static final int LOCK_MAX_LEVEL = 5;
    /** A stroke that dips out of the zone or band is forgiven for this long. */
    private static final float LOCK_GRACE = 2.5f;
    private static final String LOCK_KEY = "zonerow.lock";
    /** When the target zone changes (a new segment, or a tapped zone) the rower gets this long to get there. */
    private static final float LOCK_CHANGE = 8f;
    private int lockTarget = -1;
    private double lockSeconds;
    private double lockGrace;
    private double bestLock;
    private double lockPoints;
    private int lockLevel = 1;
    private float lockFlash;
    private float lockBreak;

    /* ---------- projection and the last piece ---------- */

    private static final int TRACE_STEP = 10;
    /** Slow average of the coasted speed, for the finish projection. */
    private double projSpeed;
    /** Piece metres every ten seconds, recorded as it is rowed and saved when it finishes. */
    private final int[] pieceTrace = new int[CUSTOM_MAX * CUSTOM_MAX_SECONDS / TRACE_STEP + 2];
    private int pieceTraceCount;
    /** The last finished piece of this length, the same shape. Null if there is none. */
    private int[] lastTrace;
    private float shownLastFrac = -1f;

    /* ---------- time in each zone ---------- */

    private final double[] zoneSeconds = new double[4];
    private float chartAnim;

    /* ---------- COACH: a plan built from the zone the rower is furthest behind on ---------- */

    /** Last finished piece as "got:asked" seconds per zone, which is what COACH reads. */
    private static final String LAST_KEY = "zonerow.last";
    private int[][] coach = {{1, 120}, {2, 120}, {0, 60}, {2, 120}, {0, 60}, {0, 120}};
    private int coachZone = 2;
    private String coachWhy = "";
    private final double[] zoneAsked = new double[4];
    private final double[] lastGot = new double[4];
    private final double[] lastAsked = new double[4];

    /* ---------- weekly minutes in each zone ---------- */

    private static final String WEEK_KEY = "zonerow.week";
    /**
     * How the weekly goal is split across the zones: mostly easy volume, a little at the top. The
     * total comes from the rower's own weekly goal (Progress), not from a constant.
     */
    private static final float[] WEEK_SHARE = {0.34f, 0.30f, 0.22f, 0.14f};
    private final float[] weekSeconds = new float[4];
    private final float[] weekGoal = new float[4];
    private final float[] weekShown = new float[4];
    private long weekDay;
    private float weekFlush;
    /** "12 / 20" per zone, rebuilt only when a whole minute turns over - it is drawn every frame. */
    private final String[] weekLabel = new String[4];
    private final int[] weekLabelDone = {-1, -1, -1, -1};
    private final int[] weekLabelGoal = {-1, -1, -1, -1};

    /* ---------- split rescue: 30 s to take a broken lock back ---------- */

    private static final float RESCUE_WINDOW = 30f;
    /** Back in the zone and the band for this long, and the lock is handed back. */
    private static final float RESCUE_HOLD = 1.5f;
    /** Below this a broken lock is not worth rescuing - the window would be noise. */
    private static final float RESCUE_MIN = 8f;
    private static final String RESCUE_KEY = "zonerow.rescues";
    private float rescueLeft;
    private double rescueSeconds;
    private int rescueLevel;
    private float rescueHold;
    private float rescueFlash;
    private float rescuePulse;
    private int rescuesMade;

    /* ---------- the sparkline of past pieces ---------- */

    private static final int HISTORY_MAX = 12;
    private final float[] history = new float[HISTORY_MAX];
    private int historyCount;
    private float historyAnim;
    /** Eased live score, so the sparkline's leading point moves rather than steps. */
    private float shownLive;
    /** "BEAT 1842 M" changes once a piece, so it is built once rather than every frame. */
    private String beatLabel;
    private float beatLabelFor = Float.NaN;

    /* ---------- the guided cool-down rate ladder ---------- */

    private static final int COOL_STEPS = 3;
    private static final float COOL_STEP_SECONDS = 45f;
    private static final String COOL_KEY = "zonerow.cool";
    /** The rate band the bar shows right now: the zone's, or the cool-down step's. */
    private final float[] coolBand = new float[2];
    private boolean cooling;
    private boolean coolDone;
    private int coolStep;
    private float coolLeft;
    private double coolInBand;
    private double coolElapsed;
    private float coolBob;
    private float coolGlow;
    private final RectF coolHit = new RectF();
    private final RectF skipHit = new RectF();

    private float shaderW = -1f;
    private float shaderH = -1f;
    private Shader background;
    private Shader splitGradient;
    private Shader rateGradient;

    /** Layout kept from the last frame for touch handling. */
    private float x0;
    private float span;
    private float zoneTop;
    private float zoneBottom;

    ZoneRowGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        for (int i = 0; i < editHits.length; i++) {
            editHits[i] = new RectF();
        }
        int[][] saved = parseCustom(bests.getString(CUSTOM_KEY));
        if (saved != null) {
            custom = saved;
        }
    }

    /** "zone:seconds,zone:seconds", or null if missing or malformed. */
    private static int[][] parseCustom(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 1 || parts.length > CUSTOM_MAX) {
            return null;
        }
        int[][] out = new int[parts.length][];
        try {
            for (int i = 0; i < parts.length; i++) {
                String[] zs = parts[i].split(":");
                int zone = Integer.parseInt(zs[0].trim());
                int seconds = Integer.parseInt(zs[1].trim());
                if (zone < 0 || zone > 3 || seconds < CUSTOM_MIN_SECONDS || seconds > CUSTOM_MAX_SECONDS) {
                    return null;
                }
                out[i] = new int[]{zone, seconds};
            }
        } catch (RuntimeException e) {
            return null;
        }
        return out;
    }

    private void saveCustom() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < custom.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(custom[i][0]).append(':').append(custom[i][1]);
        }
        bests.putString(CUSTOM_KEY, b.toString());
    }

    int pieceMinutes() {
        return PIECE_MINUTES[pieceIndex];
    }

    void nextPieceLength() {
        if (!usesLengthChip()) {
            return;
        }
        pieceIndex = (pieceIndex + 1) % PIECE_MINUTES.length;
        restart();
    }

    Plan plan() {
        return plan;
    }

    void nextPlan() {
        Plan[] all = Plan.values();
        plan = all[(plan.ordinal() + 1) % all.length];
        if (plan == Plan.STREAK) {
            targetZone = 2;
        }
        restart();
    }

    boolean usesLengthChip() {
        return scheduleFor(plan) == null;
    }

    String lengthLabel() {
        int[][] schedule = scheduleFor(plan);
        if (schedule == null) {
            return pieceMinutes() + " MIN";
        }
        int seconds = secondsOf(schedule);
        return seconds % 60 == 0 ? seconds / 60 + " MIN" : clock(seconds);
    }

    private double pieceLength() {
        return segs != null ? secondsOf(segs) : pieceMinutes() * 60.0;
    }

    private int[][] scheduleFor(Plan p) {
        return p == Plan.CUSTOM ? custom : p == Plan.COACH ? coach : p.segments;
    }

    private static int secondsOf(int[][] schedule) {
        int total = 0;
        for (int[] seg : schedule) {
            total += seg[1];
        }
        return total;
    }

    /** Zones and rate bands from the rower's learned range. */
    private void applyProfile() {
        float m = (float) profile.typicalSplit();
        float[] offsets = {50f, 25f, 7f, -9f, -23f};
        for (int i = 0; i < 5; i++) {
            edges[i] = m + offsets[i];
        }
        float r = (float) profile.typicalRate();
        float[] lows = {-9f, -5f, -1f, 3f};
        float[] highs = {-5f, -1f, 3f, 9f};
        for (int i = 0; i < 4; i++) {
            rateBands[i][0] = Math.max(8f, r + lows[i]);
            rateBands[i][1] = r + highs[i];
        }
    }

    /** The heart-rate zone right now, or -1 without a strap. */
    private int heartZone() {
        int hr = heartRate();
        if (hr <= 0) {
            return -1;
        }
        float share = hr / maxHeart;
        return share < 0.65f ? 0 : share < 0.75f ? 1 : share < 0.85f ? 2 : 3;
    }

    private int segmentAt(double seconds) {
        if (segs == null) {
            return -1;
        }
        int t = 0;
        for (int i = 0; i < segs.length; i++) {
            t += segs[i][1];
            if (seconds < t) {
                return i;
            }
        }
        return segs.length - 1;
    }

    private double segmentRemaining(double seconds) {
        int t = 0;
        for (int[] s : segs) {
            t += s[1];
            if (seconds < t) {
                return t - seconds;
            }
        }
        return 0;
    }

    /** Share of the whole plan spent in each segment's zone, as a percentage. */
    private double planScore() {
        double in = 0;
        for (double v : segmentInZone) {
            in += v;
        }
        double total = segs != null ? secondsOf(segs) : 0;
        return total > 0 ? 100.0 * in / total : 0;
    }

    @Override
    protected void onStart() {
        restart();
    }

    private void restart() {
        applyProfile();
        if (weekDay != 0) {
            saveWeek();   // bank what this piece added before the week is read back
        }
        loadWeek();
        if (plan == Plan.COACH) {
            coachZone = weakestZone();
            coach = buildCoach();
        }
        segs = scheduleFor(plan);
        loadHistory();
        historyAnim = 0f;
        shownLive = 0f;
        // The kind of piece may have changed, so the units behind the cached label may have too.
        beatLabel = null;
        rescueLeft = 0f;
        rescueHold = 0f;
        rescueFlash = 0f;
        rescueSeconds = 0;
        rescueLevel = 1;
        rescuesMade = 0;
        cooling = false;
        coolDone = false;
        coolStep = 0;
        coolLeft = COOL_STEP_SECONDS;
        coolInBand = 0;
        coolElapsed = 0;
        java.util.Arrays.fill(zoneAsked, 0);
        editing = plan == Plan.CUSTOM;
        editIndex = Math.max(0, Math.min(custom.length - 1, editIndex));
        lockSeconds = 0;
        lockGrace = 0;
        bestLock = 0;
        lockPoints = 0;
        lockLevel = 1;
        lockFlash = 0f;
        lockBreak = 0f;
        lockTarget = -1;
        projSpeed = 0;
        pieceTraceCount = 1;
        pieceTrace[0] = 0;
        lastTrace = loadTrace();
        shownLastFrac = -1f;
        java.util.Arrays.fill(zoneSeconds, 0);
        chartAnim = 0f;
        pieceSeconds = 0;
        pieceStartMeters = sessionMeters;
        finalMeters = -1;
        joules = 0;
        lastMeterWork = -1;
        inZoneSeconds = 0;
        applyProfile();
        java.util.Arrays.fill(segmentInZone, 0);
        streak = 0;
        bestStreak = 0;
        streakGrace = 0;
        if (segs != null) {
            targetZone = segs[0][0];
        }
        paused = false;
        finished = false;
        newBest = false;
        postInvalidateOnAnimation();
    }

    private double pieceMeters() {
        return finalMeters >= 0 ? finalMeters : Math.max(0, sessionMeters - pieceStartMeters);
    }

    /** Where a split sits along the bar, 0 (slowest edge or slower) to 1 (fastest or faster). */
    float splitFraction(float secondsPer500) {
        if (secondsPer500 >= edges[0]) {
            return 0f;
        }
        if (secondsPer500 <= edges[edges.length - 1]) {
            return 1f;
        }
        for (int i = 0; i < 4; i++) {
            if (secondsPer500 <= edges[i] && secondsPer500 >= edges[i + 1]) {
                return (i + (edges[i] - secondsPer500) / (edges[i] - edges[i + 1])) / 4f;
            }
        }
        return 0f;
    }

    private String planKey() {
        return "zonerow.plan." + plan.name().toLowerCase(java.util.Locale.US);
    }

    private static int zoneAt(float fraction) {
        return Math.max(0, Math.min(3, (int) (fraction * 4f)));
    }

    /* ---------- COACH ---------- */

    /** Seconds held / seconds asked per zone in the last finished piece. False if there is none. */
    private boolean loadLastPiece() {
        String raw = bests.getString(LAST_KEY);
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String[] parts = raw.split(",");
        if (parts.length != 4) {
            return false;
        }
        try {
            for (int i = 0; i < 4; i++) {
                String[] pair = parts[i].split(":");
                lastGot[i] = Double.parseDouble(pair[0].trim());
                lastAsked[i] = Double.parseDouble(pair[1].trim());
            }
        } catch (RuntimeException e) {
            return false;
        }
        return true;
    }

    private void saveLastPiece() {
        StringBuilder b = new StringBuilder();
        for (int z = 0; z < 4; z++) {
            if (z > 0) {
                b.append(',');
            }
            b.append(Math.round(zoneSeconds[z])).append(':').append(Math.round(zoneAsked[z]));
        }
        bests.putString(LAST_KEY, b.toString());
    }

    /**
     * The zone to work on: the one the last piece asked for and got least of, and failing that the
     * one furthest behind its share of this week's minutes. Sets {@link #coachWhy} to say which.
     */
    private int weakestZone() {
        double worst = Double.MAX_VALUE;
        int fromPiece = -1;
        if (loadLastPiece()) {
            for (int z = 0; z < 4; z++) {
                if (lastAsked[z] < 20) {
                    continue;   // barely asked for: no evidence either way
                }
                double attained = lastGot[z] / lastAsked[z];
                if (attained < worst) {
                    worst = attained;
                    fromPiece = z;
                }
            }
        }
        if (fromPiece >= 0 && worst < 0.85) {
            coachWhy = "LAST PIECE HELD " + Math.round(worst * 100) + "% OF " + ZONES[fromPiece];
            return fromPiece;
        }
        int thinnest = 0;
        double least = Double.MAX_VALUE;
        for (int z = 0; z < 4; z++) {
            double share = weekGoal[z] > 0 ? weekSeconds[z] / weekGoal[z] : 1;
            if (share < least) {
                least = share;
                thinnest = z;
            }
        }
        coachWhy = (fromPiece >= 0 ? "LAST PIECE ON SCHEDULE  ·  " : "")
                + ZONES[thinnest] + " IS THIS WEEK'S THINNEST AT " + Math.round(least * 100) + "%";
        return thinnest;
    }

    /**
     * Intervals at the weak zone with easy rowing between, long and few at the bottom of the scale,
     * short and many at the top. Warm-up one zone below, cool-down in GLIDE.
     */
    private int[][] buildCoach() {
        int w = coachZone;
        int rest = w == 0 ? 1 : 0;
        int reps = w == 3 ? 6 : w == 2 ? 4 : 3;
        int work = w == 3 ? 45 : w == 2 ? 120 : w == 1 ? 180 : 240;
        int easy = w == 3 ? 75 : 60;
        int[][] out = new int[2 + reps * 2][];
        out[0] = new int[]{w == 0 ? 1 : w - 1, 120};
        for (int i = 0; i < reps; i++) {
            out[1 + i * 2] = new int[]{w, work};
            out[2 + i * 2] = new int[]{rest, easy};
        }
        out[out.length - 1] = new int[]{0, 120};
        return out;
    }

    /* ---------- weekly minutes in each zone ---------- */

    /**
     * This week's seconds per zone, reset when the week turns over. Goals are the rower's own
     * weekly goal (from Progress, Monday to Sunday) split by {@link #WEEK_SHARE}; the goal is read
     * straight off the stored string rather than decoding 400 days of history for one number.
     */
    private void loadWeek() {
        weekDay = Progress.weekStart(RegattaGame.today());
        java.util.Arrays.fill(weekSeconds, 0f);
        String raw = bests.getString(WEEK_KEY);
        if (raw != null) {
            String[] p = raw.split(",");
            if (p.length == 5) {
                try {
                    if (Long.parseLong(p[0].trim()) == weekDay) {
                        for (int i = 0; i < 4; i++) {
                            weekSeconds[i] = Math.max(0f, Float.parseFloat(p[i + 1].trim()));
                        }
                    }
                } catch (RuntimeException e) {
                    java.util.Arrays.fill(weekSeconds, 0f);
                }
            }
        }
        float goal = Progress.DEFAULT_GOAL_MINUTES;
        String stored = bests.getString("progress");
        if (stored != null && stored.startsWith("g=")) {
            int semi = stored.indexOf(';');
            if (semi > 2) {
                try {
                    goal = Math.max(10f, Float.parseFloat(stored.substring(2, semi)));
                } catch (NumberFormatException e) {
                    goal = Progress.DEFAULT_GOAL_MINUTES;
                }
            }
        }
        for (int z = 0; z < 4; z++) {
            weekGoal[z] = goal * 60f * WEEK_SHARE[z];
        }
        System.arraycopy(weekSeconds, 0, weekShown, 0, 4);
        weekFlush = 0f;
    }

    private void saveWeek() {
        if (weekDay == 0) {
            // loadWeek() has never run, so weekSeconds are all zero and weekDay is not a real
            // Monday. Writing that would blank a week the rower has already banked - stop() can
            // reach here without start(), and the zeroes would win.
            return;
        }
        StringBuilder b = new StringBuilder();
        b.append(weekDay);
        for (int z = 0; z < 4; z++) {
            b.append(',').append(Math.round(weekSeconds[z]));
        }
        bests.putString(WEEK_KEY, b.toString());
    }

    /* ---------- the history of past pieces ---------- */

    /** One list per kind of piece, so a 10 min row is never charted against a SPRINTS score. */
    private String historyKey() {
        if (segs != null) {
            return "zonerow.hist." + plan.name().toLowerCase(java.util.Locale.US);
        }
        if (plan == Plan.STREAK) {
            return "zonerow.hist.streak";
        }
        // HEART keeps its own list: its metres are rowed to a different instruction.
        return "zonerow.hist." + (plan == Plan.HEART ? "heart" : "min") + pieceMinutes();
    }

    private void loadHistory() {
        historyCount = 0;
        String raw = bests.getString(historyKey());
        if (raw == null || raw.isEmpty()) {
            return;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (historyCount >= HISTORY_MAX) {
                break;
            }
            try {
                history[historyCount++] = Float.parseFloat(part.trim());
            } catch (NumberFormatException e) {
                historyCount = 0;
                return;
            }
        }
    }

    private void saveHistory(float score) {
        StringBuilder b = new StringBuilder();
        int from = historyCount >= HISTORY_MAX ? historyCount - HISTORY_MAX + 1 : 0;
        for (int i = from; i < historyCount; i++) {
            b.append(Math.round(history[i] * 10f) / 10f).append(',');
        }
        b.append(Math.round(score * 10f) / 10f);
        bests.putString(historyKey(), b.toString());
    }

    /** The score this piece is being judged on: schedule share, streak seconds, or metres. */
    private float liveScore() {
        if (segs != null) {
            return (float) planScore();
        }
        if (plan == Plan.STREAK) {
            return (float) bestStreak;
        }
        double projected = finished ? pieceMeters() : projectedMeters();
        return (float) (projected >= 0 ? projected : pieceMeters());
    }

    /** The score to beat: the last piece of this kind. -1 when this is the first. */
    private float beatTarget() {
        return historyCount > 0 ? history[historyCount - 1] : -1f;
    }

    private String scoreText(float v) {
        if (segs != null) {
            return Math.round(v) + "%";
        }
        return plan == Plan.STREAK ? clock(v) : Math.round(v) + " M";
    }

    /* ---------- the cool-down ladder ---------- */

    /** Descending rate steps around the rower's own typical rate, never below a slow paddle. */
    private float coolTargetRate(int step) {
        float base = (float) profile.typicalRate();
        return Math.max(13f, base - 4f - 3f * step);
    }

    /** The band the rate bar is drawing against right now. */
    private float[] activeRateBand() {
        if (cooling && !coolDone) {
            float target = coolTargetRate(coolStep);
            coolBand[0] = target - 1.5f;
            coolBand[1] = target + 1.5f;
            return coolBand;
        }
        return rateBands[targetZone];
    }

    /* ---------- state ---------- */

    private void advance(float dt) {
        float speed = boat.value();
        float pace = speed >= 0.5f ? 500f / speed : 0f;
        int zone = plan == Plan.HEART ? heartZone() : pace > 0f ? zoneAt(splitFraction(pace)) : -1;
        maxHeart = bests.get("hr.max", 185f);
        shownHeart += (heartRate() - shownHeart) * Math.min(1f, 3f * dt);

        // Work from the pulse meter: measured from the paddle, calibrated if the rower has done the
        // load-scale test. Counted only while the piece is running.
        double meterWork = status == null ? -1 : status.meter.workJoules;
        double workStep = lastMeterWork >= 0 && meterWork >= lastMeterWork ? meterWork - lastMeterWork : 0;
        lastMeterWork = meterWork;
        energyMeasured = status != null && status.meter.source == PulseMeter.EnergySource.PULSES_CALIBRATED;
        // The zone the rower sees on the bar (eased), which is what the lock and the chart count.
        int shownZone = shownPace > 0f ? zoneAt(shownSplitFrac) : -1;
        // The week's minutes count all real rowing on this screen, the cool-down included.
        if (!paused && !editing && isClockRunning()) {
            int weekZone = plan == Plan.HEART ? zone : shownZone;
            if (weekZone >= 0) {
                weekSeconds[weekZone] += dt;
            }
            if ((weekFlush += dt) >= 10f) {
                weekFlush = 0f;
                saveWeek();
            }
        }
        for (int z = 0; z < 4; z++) {
            weekShown[z] += (weekSeconds[z] - weekShown[z]) * Math.min(1f, 4f * dt);
        }
        if (cooling) {
            advanceCooldown(dt);
        }
        if (!paused && !finished && !editing && isClockRunning()) {
            pieceSeconds += dt;
            joules += workStep;
            projSpeed = projSpeed <= 0 ? speed : projSpeed + (speed - projSpeed) * Math.min(1.0, dt / 8.0);
            while (pieceTraceCount < pieceTrace.length && pieceTraceCount * TRACE_STEP <= pieceSeconds) {
                pieceTrace[pieceTraceCount++] = (int) Math.round(pieceMeters());
            }
            int chartZone = plan == Plan.HEART ? zone : shownZone;
            if (chartZone >= 0) {
                zoneSeconds[chartZone] += dt;
            }
            if (plan != Plan.HEART) {
                advanceLock(dt, shownZone);
            }
            int seg = segmentAt(pieceSeconds);
            if (seg >= 0) {
                targetZone = segs[seg][0];
                if (zone == targetZone) {
                    segmentInZone[seg] += dt;
                }
            }
            // What the piece asked of each zone, against what it got: this is what COACH reads.
            zoneAsked[targetZone] += dt;
            if (zone == targetZone) {
                inZoneSeconds += dt;
            }
            if (plan == Plan.STREAK) {
                if (zone >= targetZone) {
                    streak += dt;
                    streakGrace = 0;
                    bestStreak = Math.max(bestStreak, streak);
                } else if ((streakGrace += dt) > 3.0) {
                    streak = 0;
                }
            }
            if (pieceSeconds >= pieceLength()) {
                pieceSeconds = pieceLength();
                finished = true;
                rescueLeft = 0f;
                finalMeters = Math.max(0, sessionMeters - pieceStartMeters);
                saveTrace();
                saveLastPiece();
                saveWeek();
                saveHistory(liveScore());
                if (rescuesMade > 0) {
                    bests.recordHighest(RESCUE_KEY, rescuesMade);
                }
                if (bestLock >= 1.0) {
                    bests.recordHighest(LOCK_KEY, (float) bestLock);
                }
                if (segs != null) {
                    newBest = bests.recordHighest(planKey(), (float) planScore());
                } else if (plan == Plan.STREAK) {
                    newBest = bests.recordHighest("zonerow.streak", (float) bestStreak);
                } else {
                    newBest = bests.recordHighest("zonerow." + pieceMinutes(), (float) finalMeters);
                }
            }
        }

        lockFlash = Math.max(0f, lockFlash - 1.6f * dt);
        lockBreak = Math.max(0f, lockBreak - 1.2f * dt);
        rescueFlash = Math.max(0f, rescueFlash - 0.8f * dt);
        rescuePulse += dt;
        historyAnim = Math.min(1f, historyAnim + 0.9f * dt);
        float live = liveScore();
        shownLive += (live - shownLive) * Math.min(1f, 2.5f * dt);
        if (finished) {
            chartAnim = Math.min(1f, chartAnim + 1.3f * dt);
        }
        float lastSplit = lastSplitAt(pieceSeconds);
        if (lastSplit > 0f) {
            float target = splitFraction(lastSplit);
            shownLastFrac = shownLastFrac < 0f ? target : shownLastFrac + (target - shownLastFrac) * Math.min(1f, 2f * dt);
        } else {
            shownLastFrac = -1f;
        }

        float ease = Math.min(1f, 3f * dt);
        shownSplitFrac += ((pace > 0f ? splitFraction(pace) : 0f) - shownSplitFrac) * ease;
        shownPace = pace <= 0f ? 0f : shownPace <= 0f ? pace : shownPace + (pace - shownPace) * ease;
        float rate = status == null ? 0f : (float) status.strokeRatePrecise;
        shownRate += (rate - shownRate) * ease;
        float watts = status == null ? 0f : status.watts;
        shownWatts += (watts - shownWatts) * ease;

        traceClock += dt;
        while (traceClock >= 1f / 30f) {
            traceClock -= 1f / 30f;
            trace[traceHead] = status == null ? 0f : (float) status.pulseEffort;
            traceHead = (traceHead + 1) % trace.length;
        }
    }

    /**
     * The lock runs while the split sits in the target zone (or faster, in STREAK) and the rate in
     * the target band, both as shown on the bars. Every 20 s held raises the multiplier; points
     * accrue at the multiplier per second. A dip is forgiven for 2.5 s, then the lock breaks.
     */
    private void advanceLock(float dt, int shownZone) {
        if (targetZone != lockTarget) {
            // A new target: a held lock survives the change if the rower gets there in time.
            if (lockTarget >= 0 && lockSeconds > 0) {
                lockGrace = -LOCK_CHANGE;
            }
            lockTarget = targetZone;
        }
        float[] band = rateBands[targetZone];
        boolean zoneOk = plan == Plan.STREAK ? shownZone >= targetZone : shownZone == targetZone;
        boolean rateOk = shownRate >= band[0] && shownRate <= band[1];
        advanceRescue(dt, zoneOk && rateOk);
        if (zoneOk && rateOk) {
            lockSeconds += dt;
            lockGrace = 0;
            int level = Math.min(LOCK_MAX_LEVEL, 1 + (int) (lockSeconds / LOCK_STEP));
            if (level > lockLevel) {
                lockFlash = 1f;
            }
            lockLevel = level;
            lockPoints += dt * level;
            bestLock = Math.max(bestLock, lockSeconds);
        } else if (lockSeconds > 0 && (lockGrace += dt) > LOCK_GRACE) {
            if (lockSeconds >= LOCK_STEP) {
                lockBreak = 1f;
            }
            // The lock is not gone yet: it goes into the rescue window, where it can be won back.
            if (lockSeconds >= RESCUE_MIN) {
                rescueLeft = RESCUE_WINDOW;
                rescueSeconds = lockSeconds;
                rescueLevel = lockLevel;
                rescueHold = 0f;
            }
            lockSeconds = 0;
            lockGrace = 0;
            lockLevel = 1;
        }
    }

    /**
     * SPLIT RESCUE. A lock worth keeping does not die the moment it slips: a 30 s window opens, and
     * getting the split back into the zone and the rate back into the band for a second and a half
     * hands the whole lock back at the level it broke at. Let the window run out and it is gone.
     * That is the thing at stake in the next ten seconds whenever the rower drifts.
     */
    private void advanceRescue(float dt, boolean onTarget) {
        if (rescueLeft <= 0f) {
            return;
        }
        if (onTarget) {
            rescueHold += dt;
            if (rescueHold >= RESCUE_HOLD) {
                lockSeconds = rescueSeconds;
                lockLevel = Math.min(LOCK_MAX_LEVEL, Math.max(1, rescueLevel));
                lockGrace = 0;
                rescuesMade++;
                rescueFlash = 1f;
                rescueLeft = 0f;
                rescueHold = 0f;
                lockBreak = 0f;
                return;
            }
        } else {
            rescueHold = Math.max(0f, rescueHold - dt * 2f);
        }
        rescueLeft = Math.max(0f, rescueLeft - dt);
    }

    /**
     * The cool-down ladder: three 45 s steps at descending rates around the rower's own typical
     * rate. The clock only runs while they are actually rowing, so stopping holds the step rather
     * than skipping it, and the share of it held in band is the score.
     */
    private void advanceCooldown(float dt) {
        coolGlow += dt;
        if (coolDone) {
            return;
        }
        if (paused || !isClockRunning()) {
            return;
        }
        coolBob += dt * (1f + Math.max(0f, shownRate) / 20f);
        coolElapsed += dt;
        float[] band = activeRateBand();
        if (shownRate >= band[0] && shownRate <= band[1]) {
            coolInBand += dt;
        }
        coolLeft -= dt;
        if (coolLeft <= 0f) {
            coolStep++;
            coolLeft = COOL_STEP_SECONDS;
            if (coolStep >= COOL_STEPS) {
                coolStep = COOL_STEPS - 1;
                coolDone = true;
                coolLeft = 0f;
                if (coolElapsed > 30) {
                    bests.recordHighest(COOL_KEY, (float) (100.0 * coolInBand / coolElapsed));
                }
            }
        }
    }

    private String traceKey() {
        return "zonerow.trace." + (int) Math.round(pieceLength());
    }

    private int[] loadTrace() {
        String raw = bests.getString(traceKey());
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        String[] parts = raw.split(",");
        if (parts.length < 2) {
            return null;
        }
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i].trim());
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    /** Only a finished piece is saved, so LAST is always a whole piece of the same length. */
    private void saveTrace() {
        int end = (int) Math.round(pieceLength()) / TRACE_STEP;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i <= end; i++) {
            if (i > 0) {
                b.append(',');
            }
            int m = i < pieceTraceCount && i < end ? pieceTrace[i] : (int) Math.round(finalMeters);
            b.append(m);
        }
        bests.putString(traceKey(), b.toString());
    }

    /** Metres the last piece had covered at this point, interpolated; -1 without one. */
    private double lastMetersAt(double seconds) {
        if (lastTrace == null) {
            return -1;
        }
        double idx = seconds / TRACE_STEP;
        int i = (int) Math.floor(idx);
        if (i >= lastTrace.length - 1) {
            return lastTrace[lastTrace.length - 1];
        }
        if (i < 0) {
            return 0;
        }
        return lastTrace[i] + (lastTrace[i + 1] - lastTrace[i]) * (idx - i);
    }

    private double lastFinalMeters() {
        return lastTrace == null ? -1 : lastTrace[lastTrace.length - 1];
    }

    /** The last piece's split over the 30 s around this moment, 0 if unknown. */
    private float lastSplitAt(double seconds) {
        if (lastTrace == null) {
            return 0f;
        }
        double end = (lastTrace.length - 1) * (double) TRACE_STEP;
        double t1 = Math.max(0, Math.min(end - 30, seconds - 15));
        double t2 = Math.min(end, t1 + 30);
        double dm = lastMetersAt(t2) - lastMetersAt(t1);
        return dm > 1 ? (float) ((t2 - t1) * 500.0 / dm) : 0f;
    }

    /** Metres at the finish at the recent pace, or -1 until there is a pace to project from. */
    private double projectedMeters() {
        if (pieceSeconds < 10 || projSpeed <= 0.3) {
            return -1;
        }
        return pieceMeters() + Math.max(0, pieceLength() - pieceSeconds) * projSpeed;
    }

    /* ---------- drawing ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        advance(dt);

        float padX = w * 0.035f;
        x0 = padX;
        span = w - 2f * padX;
        float x1 = x0 + span;
        ensureShaders(w, h, x0, x1);

        fill.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha
        fill.setShader(background);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        drawProgress(c, w);
        drawHeader(c, w, h, x0, x1);

        zoneTop = h * 0.19f;
        zoneBottom = h * 0.30f;
        float lanesTop = h * 0.21f;
        float lanesBottom = h * 0.745f;
        int currentZone = shownPace > 0f ? zoneAt(shownSplitFrac) : -1;

        // The target quarter is tinted all the way down, so both bars read against it.
        fill.setColor(ZONE_COLORS[targetZone]);
        fill.setAlpha(22);
        c.drawRect(x0 + span * targetZone / 4f, lanesTop, x0 + span * (targetZone + 1) / 4f,
                lanesBottom, fill);
        fill.setAlpha(255);

        line.setShader(null);
        line.setColor(DIVIDER);
        line.setStrokeWidth(dp(1.5f));
        for (int i = 1; i < 4; i++) {
            float x = x0 + span * i / 4f;
            c.drawLine(x, lanesTop, x, lanesBottom, line);
        }
        for (int i = 0; i < 4; i++) {
            float cx = x0 + span * (i + 0.5f) / 4f;
            int color = i == currentZone ? ZONE_COLORS[i] : 0xFF5D6B80;
            text(c, ZONES[i], cx, h * 0.265f, h * 0.034f, color, Paint.Align.CENTER,
                    i == currentZone ? numbers : labels, 0.3f);
            if (i == targetZone) {
                fill.setColor(ZONE_COLORS[i]);
                rect.set(cx - dp(22f), h * 0.283f, cx + dp(22f), h * 0.283f + dp(3f));
                c.drawRoundRect(rect, dp(2f), dp(2f), fill);
            }
        }

        drawSplitBar(c, h, x0, x1, currentZone);
        drawRateBar(c, h, x0, x1);
        drawPanels(c, h, x0, x1);
        drawMiddle(c, h, x0, x1);
        drawStats(c, w, h, x0, x1, currentZone);
        drawRescue(c, h, x0, x1);

        coolHit.setEmpty();
        skipHit.setEmpty();
        if (editing) {
            drawEditor(c, w, h, x0, x1);
        } else if (cooling) {
            drawCooldown(c, w, h, x0, x1);
        } else if (finished) {
            drawFinished(c, w, h);
        }
    }

    private void ensureShaders(float w, float h, float left, float right) {
        if (w == shaderW && h == shaderH) {
            return;
        }
        shaderW = w;
        shaderH = h;
        background = new LinearGradient(0, 0, 0, h, 0xFF10284A, 0xFF050D19, Shader.TileMode.CLAMP);
        splitGradient = new LinearGradient(left, 0, right, 0, ZONE_COLORS,
                new float[]{0f, 0.36f, 0.64f, 1f}, Shader.TileMode.CLAMP);
        rateGradient = new LinearGradient(left, 0, right, 0, 0xFF3A5BD9, 0xFF7FC6EE,
                Shader.TileMode.CLAMP);
    }

    private void drawProgress(Canvas c, float w) {
        float frac = (float) Math.min(1.0, pieceSeconds / pieceLength());
        if (segs != null) {
            // The schedule as a coloured timeline: faint ahead, full colour behind, a marker at now.
            float total = secondsOf(segs);
            float x = 0f;
            for (int[] seg : segs) {
                float segW = w * seg[1] / total;
                float right = x + segW - dp(2f);
                fill.setColor(ZONE_COLORS[seg[0]]);
                fill.setAlpha(60);
                c.drawRect(x, 0, right, dp(9f), fill);
                fill.setAlpha(255);
                float done = Math.min(right, w * frac);
                if (done > x) {
                    c.drawRect(x, 0, done, dp(9f), fill);
                }
                x += segW;
            }
            fill.setColor(TEXT);
            c.drawRect(w * frac - dp(1.5f), 0, w * frac + dp(1.5f), dp(14f), fill);
            return;
        }
        fill.setColor(0xFF16294A);
        c.drawRect(0, 0, w, dp(4f), fill);
        fill.setColor(ACCENT);
        c.drawRect(0, 0, w * frac, dp(4f), fill);
    }

    private void drawHeader(Canvas c, float w, float h, float left, float right) {
        float labelY = h * 0.065f;
        float valueY = h * 0.145f;
        float labelSize = h * 0.026f;
        float valueSize = h * 0.075f;

        text(c, "ELAPSED", left, labelY, labelSize, FAINT, Paint.Align.LEFT, labels, 0.25f);
        text(c, clock(pieceSeconds), left, valueY, valueSize, TEXT, Paint.Align.LEFT, numbers, 0.02f);

        // Live pulse effort beside the label: every stroke draws its own little peak.
        ink.setTypeface(labels);
        ink.setTextSize(labelSize);
        ink.setLetterSpacing(0.25f);
        float tx = left + ink.measureText("ELAPSED") + dp(16f);
        float tw = dp(120f);
        float th = h * 0.04f;
        float base = labelY + dp(2f);
        path.rewind();
        for (int i = 0; i < trace.length; i++) {
            float v = Math.min(1f, trace[(traceHead + i) % trace.length]);
            float x = tx + tw * i / (trace.length - 1f);
            float y = base - th * v;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        line.setShader(null);
        line.setColor(TRACE);
        line.setStrokeWidth(dp(1.6f));
        c.drawPath(path, line);

        float cx = w / 2f;
        if (editing) {
            text(c, "CUSTOM  ·  " + segs.length + " SEGMENTS  ·  " + lengthLabel(), cx, labelY, labelSize,
                    FAINT, Paint.Align.CENTER, labels, 0.25f);
            text(c, "BUILD YOUR PLAN", cx, valueY, valueSize * 0.7f, ACCENT, Paint.Align.CENTER, numbers, 0.12f);
        } else if (!hasClockStarted() || pieceSeconds <= 0) {
            String hint = plan == Plan.COACH
                    ? "COACH  ·  MORE " + ZONES[coachZone] + "  ·  " + coachWhy
                    : segs != null
                    ? plan.name() + "  ·  " + segs.length + " SEGMENTS  ·  " + lengthLabel()
                    : plan == Plan.STREAK ? "STREAK  ·  TAP A ZONE TO SET THE BAR"
                    : plan == Plan.HEART ? (heartRate() > 0 ? "HEART ZONES  ·  TAP A ZONE" : "CONNECT A HEART STRAP IN DIAGNOSTICS")
                    : "TAP A ZONE TO SET YOUR TARGET";
            text(c, hint, cx, labelY, labelSize, FAINT, Paint.Align.CENTER, labels, 0.25f);
            text(c, "ROW TO START", cx, valueY, valueSize * 0.7f, ACCENT, Paint.Align.CENTER, numbers, 0.12f);
        } else if (segs != null) {
            int seg = segmentAt(pieceSeconds);
            text(c, "SEGMENT " + (seg + 1) + "/" + segs.length + "  ·  " + ZONES[targetZone],
                    cx, labelY, labelSize, ZONE_COLORS[targetZone], Paint.Align.CENTER, numbers, 0.2f);
            text(c, clock(segmentRemaining(pieceSeconds)), cx, valueY, valueSize, TEXT, Paint.Align.CENTER, numbers, 0.02f);
        } else if (plan == Plan.STREAK) {
            text(c, "STREAK  ·  " + ZONES[targetZone] + " OR FASTER", cx, labelY, labelSize,
                    ZONE_COLORS[targetZone], Paint.Align.CENTER, numbers, 0.2f);
            text(c, clock(streak), cx, valueY, valueSize, streakGrace > 0 ? WARN : TEXT, Paint.Align.CENTER, numbers, 0.02f);
        } else {
            text(c, "IN " + ZONES[targetZone], cx, labelY, labelSize, ZONE_COLORS[targetZone],
                    Paint.Align.CENTER, numbers, 0.25f);
            text(c, clock(inZoneSeconds), cx, valueY, valueSize, TEXT, Paint.Align.CENTER, numbers, 0.02f);
        }

        text(c, paused ? "PAUSED" : "REMAINING", right, labelY, labelSize, paused ? WARN : FAINT,
                Paint.Align.RIGHT, paused ? numbers : labels, 0.25f);
        text(c, clock(Math.max(0, pieceLength() - pieceSeconds)), right, valueY, valueSize, TEXT,
                Paint.Align.RIGHT, numbers, 0.02f);
    }

    private void drawSplitBar(Canvas c, float h, float left, float right, int zone) {
        float top = h * 0.415f;
        float bottom = top + h * 0.075f;
        float radius = (bottom - top) / 2f;
        float fillX = left + (right - left) * shownSplitFrac;

        rect.set(left, top, right, bottom);
        fill.setColor(TRACK);
        c.drawRoundRect(rect, radius, radius, fill);
        // The whole bar carries its colours faintly, so the zone ahead is always visible.
        fill.setColor(0xFFFFFFFF);
        fill.setShader(splitGradient);
        fill.setAlpha(50);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setAlpha(255);
        if (fillX > left + 1f) {
            c.save();
            c.clipRect(left, top - dp(2f), fillX, bottom + dp(2f));
            c.drawRoundRect(rect, radius, radius, fill);
            c.restore();
        }
        fill.setShader(null);

        if (shownLastFrac >= 0f && rescueLeft <= 0f) {
            // The last piece of this length, at the same moment: a hollow post and a tag beneath.
            // Hidden during a rescue: the rescue strip lives in the same band and matters more.
            float lx = left + (right - left) * shownLastFrac;
            line.setShader(null);
            line.setColor(DIM);
            line.setStrokeWidth(dp(2f));
            rect.set(lx - dp(4f), top - dp(8f), lx + dp(4f), bottom + dp(8f));
            c.drawRoundRect(rect, dp(3f), dp(3f), line);
            double gap = pieceSeconds > 0 ? pieceMeters() - lastMetersAt(pieceSeconds) : 0;
            String tag = pieceSeconds < 5 ? "LAST" : "LAST  " + (gap >= 0 ? "+" : "\u2212") + Math.round(Math.abs(gap)) + " M";
            int tagColor = pieceSeconds < 5 ? DIM : gap >= 0 ? ACCENT : BAD;
            ink.setTypeface(numbers);
            ink.setTextSize(h * 0.022f);
            ink.setLetterSpacing(0.15f);
            float tagW = ink.measureText(tag) + dp(16f);
            float tx = Math.max(left + tagW / 2f, Math.min(right - tagW / 2f, lx));
            rect.set(tx - tagW / 2f, bottom + dp(10f), tx + tagW / 2f, bottom + dp(10f) + h * 0.03f);
            fill.setColor(PILL);
            c.drawRoundRect(rect, dp(6f), dp(6f), fill);
            line.setColor(tagColor);
            line.setStrokeWidth(dp(1.2f));
            c.drawRoundRect(rect, dp(6f), dp(6f), line);
            text(c, tag, tx, rect.bottom - h * 0.008f, h * 0.022f, tagColor, Paint.Align.CENTER, numbers, 0.15f);
        }

        if (shownPace > 0f) {
            fill.setColor(TEXT);
            c.drawRect(fillX - dp(2f), top - dp(12f), fillX + dp(2f), bottom + dp(12f), fill);
        }

        if (rescueLeft > 0f) {
            // Chevrons marching from the marker toward the zone that would win the lock back.
            float targetCx = left + (right - left) * (targetZone + 0.5f) / 4f;
            float dir = targetCx > fillX ? 1f : targetCx < fillX ? -1f : 0f;
            if (dir != 0f) {
                float cy = (top + bottom) / 2f;
                float size = (bottom - top) * 0.32f;
                line.setShader(null);
                line.setColor(rescueLeft < 10f ? BAD : WARN);
                line.setStrokeWidth(dp(3f));
                for (int i = 0; i < 3; i++) {
                    float t = ((rescuePulse * 1.4f + i * 0.34f) % 1f);
                    float cxc = fillX + dir * (dp(22f) + t * dp(78f));
                    line.setAlpha((int) (230 * (1f - t)));
                    path.rewind();
                    path.moveTo(cxc - dir * size, cy - size);
                    path.lineTo(cxc, cy);
                    path.lineTo(cxc - dir * size, cy + size);
                    c.drawPath(path, line);
                }
                line.setAlpha(255);
            }
        }

        String split = shownPace > 0f ? PersonalBests.formatPace(shownPace) : "--:--";
        float bigSize = h * 0.14f;
        ink.setTypeface(numbers);
        ink.setTextSize(bigSize);
        ink.setLetterSpacing(0.02f);
        float splitW = ink.measureText(split);
        ink.setTypeface(labels);
        ink.setTextSize(h * 0.03f);
        ink.setLetterSpacing(0.15f);
        float unitW = ink.measureText("/500M") + dp(8f);
        float cx = Math.max(left + splitW / 2f, Math.min(right - splitW / 2f - unitW, fillX));
        float baseline = top - dp(20f);
        text(c, split, cx, baseline, bigSize, zone >= 0 ? TEXT : FAINT, Paint.Align.CENTER, numbers, 0.02f);
        text(c, "/500M", cx + splitW / 2f + dp(8f), baseline - bigSize * 0.52f, h * 0.03f, FAINT,
                Paint.Align.LEFT, labels, 0.15f);
    }

    private void drawRateBar(Canvas c, float h, float left, float right) {
        if (plan == Plan.HEART) {
            drawHeartBar(c, h, left, right);
            return;
        }
        float top = h * 0.585f;
        float bottom = top + h * 0.05f;
        float radius = (bottom - top) / 2f;
        float scale = (right - left) / RATE_MAX;
        float rateX = left + Math.min(RATE_MAX, Math.max(0f, shownRate)) * scale;
        float[] band = activeRateBand();
        boolean inBand = shownRate >= band[0] && shownRate <= band[1];

        rect.set(left, top, right, bottom);
        fill.setColor(TRACK);
        c.drawRoundRect(rect, radius, radius, fill);

        rect.set(left + band[0] * scale, top - dp(7f), left + band[1] * scale, bottom + dp(7f));
        fill.setColor(cooling ? 0x5535D0BA : 0x553A5BD9);
        c.drawRoundRect(rect, dp(4f), dp(4f), fill);
        line.setShader(null);
        line.setColor(cooling ? 0xAA35D0BA : 0xAA6F8CFF);
        line.setStrokeWidth(dp(1.5f));
        c.drawRoundRect(rect, dp(4f), dp(4f), line);

        if (rateX > left + 1f) {
            rect.set(left, top, right, bottom);
            fill.setColor(0xFFFFFFFF);
            fill.setShader(rateGradient);
            c.save();
            c.clipRect(left, top - dp(2f), rateX, bottom + dp(2f));
            c.drawRoundRect(rect, radius, radius, fill);
            c.restore();
            fill.setShader(null);
            fill.setColor(inBand ? ACCENT : TEXT);
            c.drawRect(rateX - dp(2f), top - dp(10f), rateX + dp(2f), bottom + dp(10f), fill);
        }

        String rate = shownRate >= 1f ? String.format(java.util.Locale.US, "%.1f", shownRate) : "--";
        // Sits below the two panels added in 3.23.0, so the figure clears them at any rate.
        float bigSize = h * 0.088f;
        ink.setTypeface(numbers);
        ink.setTextSize(bigSize);
        ink.setLetterSpacing(0.02f);
        float rateW = ink.measureText(rate);
        float unitW = dp(70f);
        float cx = Math.max(left + rateW / 2f, Math.min(right - rateW / 2f - unitW, rateX));
        float baseline = bottom + h * 0.142f;
        text(c, rate, cx, baseline, bigSize, inBand ? ACCENT : TEXT, Paint.Align.CENTER, numbers, 0.02f);
        text(c, cooling ? "SPM  ·  COOL" : "SPM", cx + rateW / 2f + dp(10f), baseline, h * 0.032f,
                cooling ? ACCENT : FAINT, Paint.Align.LEFT, labels, 0.2f);
    }

    /** In the HEART plan the lower bar is heart rate, with the target zone's band of beats. */
    private void drawHeartBar(Canvas c, float h, float left, float right) {
        float top = h * 0.585f;
        float bottom = top + h * 0.05f;
        float radius = (bottom - top) / 2f;
        float lo = 60f;
        float hi = 200f;
        float scale = (right - left) / (hi - lo);
        float bandLo = maxHeart * HEART_BANDS[targetZone][0];
        float bandHi = maxHeart * HEART_BANDS[targetZone][1];
        float hx = left + Math.max(0f, Math.min(hi - lo, shownHeart - lo)) * scale;
        boolean inBand = shownHeart >= bandLo && shownHeart <= bandHi;
        rect.set(left, top, right, bottom);
        fill.setColor(TRACK);
        c.drawRoundRect(rect, radius, radius, fill);
        rect.set(left + (bandLo - lo) * scale, top - dp(7f), left + (bandHi - lo) * scale, bottom + dp(7f));
        fill.setColor(0x55F0655D);
        c.drawRoundRect(rect, dp(4f), dp(4f), fill);
        if (shownHeart > lo) {
            fill.setColor(0xFFF0655D);
            rect.set(left, top, hx, bottom);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setColor(inBand ? ACCENT : TEXT);
            c.drawRect(hx - dp(2f), top - dp(10f), hx + dp(2f), bottom + dp(10f), fill);
        }
        String value = heartRate() > 0 ? String.valueOf(Math.round(shownHeart)) : "--";
        float baseline = bottom + h * 0.142f;
        text(c, value, Math.max(left + dp(60f), Math.min(right - dp(140f), hx)), baseline, h * 0.088f,
                inBand ? ACCENT : TEXT, Paint.Align.CENTER, numbers, 0.02f);
        text(c, "BPM  ·  MAX " + Math.round(maxHeart), Math.max(left + dp(60f), Math.min(right - dp(140f), hx)) + dp(70f),
                baseline, h * 0.03f, FAINT, Paint.Align.LEFT, labels, 0.2f);
    }

    /**
     * The band between the rate bar and the figures: past pieces on the left with the score to beat,
     * this week's minutes in each zone on the right.
     */
    private void drawPanels(Canvas c, float h, float left, float right) {
        float top = h * 0.645f;
        float bottom = h * 0.706f;
        float width = right - left;
        drawHistory(c, h, left, left + width * 0.30f, top, bottom);
        float weekLeft = left + width * 0.34f;
        text(c, "WEEK IN ZONE", weekLeft, top + h * 0.021f, h * 0.022f, FAINT, Paint.Align.LEFT,
                labels, 0.25f);
        drawWeekZones(c, h, weekLeft + dp(118f), right, top, bottom);
    }

    /**
     * The last dozen pieces of this kind as a sparkline, with the last one drawn as the line to beat
     * and this piece's live score running along the right of it. The whole thing draws itself in
     * over the first second, and the live point pulses, so it is never a still picture.
     */
    private void drawHistory(Canvas c, float h, float left, float right, float top, float bottom) {
        float textRight = right;
        // Never let the plot collapse or invert on a narrow panel: step below would go negative
        // and the sparkline would draw backwards out of the band.
        float plotRight = Math.max(left + dp(24f), right - dp(104f));
        float plotTop = top + h * 0.026f;
        float plotBottom = bottom;
        float live = Math.max(0f, shownLive);
        float target = beatTarget();

        text(c, "PAST PIECES", left, top + h * 0.021f, h * 0.022f, FAINT, Paint.Align.LEFT, labels, 0.25f);

        if (historyCount == 0) {
            text(c, "FIRST ONE", textRight, top + h * 0.021f, h * 0.024f, DIM, Paint.Align.RIGHT, numbers, 0.15f);
            text(c, "SET THE MARK  ·  " + scoreText(live), textRight, bottom, h * 0.024f, ACCENT,
                    Paint.Align.RIGHT, numbers, 0.1f);
            return;
        }

        // The range comes from the past pieces, NOT from the live score. The live score starts at
        // zero every piece (metres rowed, or share of the schedule held), so letting it set the
        // floor crushed the whole history and the line to beat into the top two pixels of a plot
        // this short for the first half of every piece - the panel read as a flat line exactly
        // when the rower is looking at it. It may raise the ceiling, so a piece beating everything
        // still grows the plot, and the live point is clamped into the band until it comes up.
        float lo = history[0];
        float hi = history[0];
        for (int i = 1; i < historyCount; i++) {
            lo = Math.min(lo, history[i]);
            hi = Math.max(hi, history[i]);
        }
        hi = Math.max(hi, live);
        if (hi - lo < 1f) {
            hi = lo + 1f;
        }
        float pad = (hi - lo) * 0.2f;
        lo -= pad;
        hi += pad;
        float scale = (plotBottom - plotTop) / (hi - lo);

        // The line to beat: the last piece of this kind, dashed across the whole plot.
        float targetY = plotBottom - (target - lo) * scale;
        line.setShader(null);
        line.setColor(WARN);
        line.setAlpha(150);
        line.setStrokeWidth(dp(1.4f));
        float dash = dp(12f);
        float ink6 = dp(6f);
        for (float x = left; x < plotRight; x += dash) {
            c.drawLine(x, targetY, Math.min(plotRight, x + ink6), targetY, line);
        }
        line.setAlpha(255);

        int points = historyCount + 1;
        float step = (plotRight - left) / (points - 1);
        int shown = Math.max(2, (int) Math.ceil(historyAnim * points));
        path.rewind();
        for (int i = 0; i < Math.min(shown, points); i++) {
            float v = i < historyCount ? history[i] : live;
            float x = left + step * i;
            float y = Math.max(plotTop, Math.min(plotBottom, plotBottom - (v - lo) * scale));
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        line.setColor(DIM);
        line.setStrokeWidth(dp(2f));
        c.drawPath(path, line);

        for (int i = 0; i < Math.min(shown, historyCount); i++) {
            float x = left + step * i;
            float y = plotBottom - (history[i] - lo) * scale;
            fill.setColor(i == historyCount - 1 ? WARN : FAINT);
            c.drawCircle(x, y, dp(i == historyCount - 1 ? 3.5f : 2.5f), fill);
        }

        boolean ahead = live >= target;
        float liveY = Math.max(plotTop, Math.min(plotBottom, plotBottom - (live - lo) * scale));
        float pulse = 0.5f + 0.5f * (float) Math.sin(rescuePulse * 3.4);
        fill.setColor(ahead ? ACCENT : BAD);
        fill.setAlpha((int) (70 + 70 * pulse));
        c.drawCircle(plotRight, liveY, dp(5f) + dp(3f) * pulse, fill);
        fill.setAlpha(255);
        c.drawCircle(plotRight, liveY, dp(3.5f), fill);

        if (beatLabel == null || beatLabelFor != target) {
            beatLabelFor = target;
            beatLabel = "BEAT " + scoreText(target);
        }
        text(c, beatLabel, textRight, top + h * 0.021f, h * 0.024f, WARN,
                Paint.Align.RIGHT, numbers, 0.1f);
        float delta = live - target;
        String note = (delta >= 0 ? "+" : "−") + scoreText(Math.abs(delta));
        text(c, note, textRight, bottom, h * 0.026f, ahead ? ACCENT : BAD, Paint.Align.RIGHT, numbers, 0.1f);
    }

    /** This week's minutes in each zone against its share of the rower's own weekly goal. */
    private void drawWeekZones(Canvas c, float h, float left, float right, float top, float bottom) {
        float cell = (right - left) / 4f;
        float barTop = top + h * 0.030f;
        float barH = dp(9f);
        for (int z = 0; z < 4; z++) {
            float cx = left + cell * z;
            float cr = cx + cell - dp(14f);
            float goal = Math.max(60f, weekGoal[z]);
            float done = weekShown[z];
            float frac = Math.min(1f, done / goal);
            boolean met = done >= goal;
            text(c, ZONES[z], cx, top + h * 0.021f, h * 0.022f, met ? ZONE_COLORS[z] : DIM,
                    Paint.Align.LEFT, met ? numbers : labels, 0.15f);
            int doneMin = Math.round(done / 60f);
            int goalMin = Math.round(goal / 60f);
            if (weekLabel[z] == null || weekLabelDone[z] != doneMin || weekLabelGoal[z] != goalMin) {
                weekLabelDone[z] = doneMin;
                weekLabelGoal[z] = goalMin;
                weekLabel[z] = doneMin + " / " + goalMin;
            }
            text(c, weekLabel[z], cr, top + h * 0.021f,
                    h * 0.022f, met ? ZONE_COLORS[z] : FAINT, Paint.Align.RIGHT, numbers, 0.05f);
            rect.set(cx, barTop, cr, barTop + barH);
            fill.setColor(TRACK);
            c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
            if (frac > 0.005f) {
                rect.right = cx + Math.max(barH, (cr - cx) * frac);
                fill.setColor(ZONE_COLORS[z]);
                // The zone being rowed right now glows; the rest sit flat.
                fill.setAlpha(z == targetZone ? 255 : 170);
                c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
                fill.setAlpha(255);
            }
            if (met) {
                fill.setColor(ZONE_COLORS[z]);
                c.drawCircle(cr + dp(7f), barTop + barH / 2f, dp(3.5f), fill);
            }
        }
    }

    /**
     * SPLIT RESCUE: the window that opens when a lock worth keeping slips. A strip under the split
     * bar drains away in real time, and the chevrons on the bar itself point at the zone that wins
     * it back.
     */
    private void drawRescue(Canvas c, float h, float left, float right) {
        if (rescueLeft <= 0f && rescueFlash <= 0f) {
            return;
        }
        boolean won = rescueLeft <= 0f;
        float top = h * 0.497f;
        float bottom = h * 0.545f;
        float pulse = 0.5f + 0.5f * (float) Math.sin(rescuePulse * 7.0);
        int color = won ? ACCENT : rescueLeft < 10f ? BAD : WARN;

        rect.set(left, top, right, bottom);
        fill.setColor(PILL);
        c.drawRoundRect(rect, dp(10f), dp(10f), fill);
        rect.right = left + Math.max(dp(24f),
                (right - left) * (won ? rescueFlash : rescueLeft / RESCUE_WINDOW));
        fill.setColor(color);
        fill.setAlpha(won ? (int) (110 * rescueFlash) : (int) (55 + 70 * pulse));
        c.drawRoundRect(rect, dp(10f), dp(10f), fill);
        fill.setAlpha(255);
        rect.set(left, top, right, bottom);
        line.setShader(null);
        line.setColor(color);
        line.setStrokeWidth(dp(2f));
        c.drawRoundRect(rect, dp(10f), dp(10f), line);

        float[] band = rateBands[targetZone];
        String msg = won
                ? "RESCUED  ·  LOCK ×" + lockLevel + " BACK  ·  " + rescuesMade + " THIS PIECE"
                : "SPLIT RESCUE  " + (int) Math.ceil(rescueLeft) + " S  ·  BACK TO " + ZONES[targetZone]
                        + " AT " + Math.round(band[0]) + "–" + Math.round(band[1]) + " SPM";
        text(c, msg, (left + right) / 2f, bottom - h * 0.014f, h * 0.026f, won ? ACCENT : TEXT,
                Paint.Align.CENTER, numbers, 0.15f);

        if (!won) {
            // How much of the hold has been banked: fill it and the lock comes straight back.
            float hw = dp(70f);
            float hx = right - hw - dp(14f);
            float hy = (top + bottom) / 2f;
            rect.set(hx, hy - dp(5f), hx + hw, hy + dp(5f));
            fill.setColor(TRACK);
            c.drawRoundRect(rect, dp(5f), dp(5f), fill);
            rect.right = hx + hw * Math.min(1f, rescueHold / RESCUE_HOLD);
            fill.setColor(ACCENT);
            c.drawRoundRect(rect, dp(5f), dp(5f), fill);
            text(c, "×" + rescueLevel, hx - dp(10f), hy + h * 0.011f, h * 0.026f, color,
                    Paint.Align.RIGHT, numbers, 0.05f);
        }
    }

    /**
     * Between the bars: the lock on the left (multiplier pips, time to the next level, points) and
     * the finish projection on the right.
     */
    private void drawMiddle(Canvas c, float h, float left, float right) {
        float y = h * 0.568f;
        float size = h * 0.03f;
        if (plan != Plan.HEART) {
            int color = ZONE_COLORS[targetZone];
            boolean holding = lockSeconds > 0;
            boolean slipping = holding && lockGrace > 0;
            float grow = 1f + 0.45f * lockFlash;
            String mult = "\u00D7" + (holding ? lockLevel : 1);
            text(c, "LOCK", left, y, size, holding ? color : FAINT, Paint.Align.LEFT, numbers, 0.25f);
            ink.setTypeface(numbers);
            ink.setTextSize(size);
            ink.setLetterSpacing(0.25f);
            float x = left + ink.measureText("LOCK") + dp(12f);
            text(c, mult, x, y, size * 1.25f * grow, holding ? (slipping ? WARN : TEXT) : FAINT,
                    Paint.Align.LEFT, numbers, 0.02f);
            x += size * 1.6f + dp(10f);
            // Five pips: filled to the level, the next one filling as the lock holds.
            float pipW = dp(30f);
            float pipH = dp(10f);
            float pipTop = y - size * 0.5f - pipH / 2f;
            float progress = holding && lockLevel < LOCK_MAX_LEVEL
                    ? (float) ((lockSeconds % LOCK_STEP) / LOCK_STEP) : 0f;
            for (int i = 0; i < LOCK_MAX_LEVEL; i++) {
                float px = x + i * (pipW + dp(6f));
                rect.set(px, pipTop, px + pipW, pipTop + pipH);
                fill.setColor(TRACK);
                c.drawRoundRect(rect, dp(3f), dp(3f), fill);
                float part = !holding ? 0f : i < lockLevel ? 1f : i == lockLevel ? progress : 0f;
                if (part > 0f) {
                    fill.setColor(slipping ? WARN : color);
                    rect.right = px + pipW * part;
                    c.drawRoundRect(rect, dp(3f), dp(3f), fill);
                }
            }
            if (lockBreak > 0f) {
                fill.setColor(BAD);
                fill.setAlpha((int) (140 * lockBreak));
                rect.set(x - dp(4f), pipTop - dp(4f), x + LOCK_MAX_LEVEL * (pipW + dp(6f)) - dp(2f), pipTop + pipH + dp(4f));
                c.drawRoundRect(rect, dp(5f), dp(5f), fill);
                fill.setAlpha(255);
            }
            x += LOCK_MAX_LEVEL * (pipW + dp(6f)) + dp(14f);
            float[] band = rateBands[targetZone];
            String note;
            int noteColor = FAINT;
            if (holding && lockGrace < 0) {
                note = "CHANGE TO " + ZONES[targetZone];
                noteColor = color;
            } else if (slipping) {
                note = "HOLD IT";
                noteColor = WARN;
            } else if (holding && lockLevel < LOCK_MAX_LEVEL) {
                note = "\u00D7" + (lockLevel + 1) + " IN " + (int) Math.ceil(LOCK_STEP - lockSeconds % LOCK_STEP) + " S";
                noteColor = DIM;
            } else if (holding) {
                note = "MAXED";
                noteColor = color;
            } else if (lockBreak > 0f) {
                note = "LOCK LOST";
                noteColor = BAD;
            } else {
                note = ZONES[targetZone] + (plan == Plan.STREAK ? "+" : "") + " AT " + Math.round(band[0])
                        + "\u2013" + Math.round(band[1]) + " SPM";
            }
            if (lockPoints >= 1) {
                note += "   " + Math.round(lockPoints) + " PTS";
            }
            text(c, note, x, y, size * 0.85f, noteColor, Paint.Align.LEFT, labels, 0.2f);
        }

        double projected = finished ? -1 : projectedMeters();
        String value = projected >= 0 ? Math.round(projected) + " M" : "--";
        text(c, value, right, y, size * 1.25f, projected >= 0 ? TEXT : FAINT, Paint.Align.RIGHT, numbers, 0.05f);
        ink.setTypeface(numbers);
        ink.setTextSize(size * 1.25f);
        ink.setLetterSpacing(0.05f);
        float vx = right - ink.measureText(value) - dp(12f);
        double last = lastFinalMeters();
        if (last >= 0 && projected >= 0) {
            double d = projected - last;
            String vs = (d >= 0 ? "+" : "\u2212") + Math.round(Math.abs(d)) + " VS LAST";
            text(c, vs, vx, y, size * 0.85f, d >= 0 ? ACCENT : BAD, Paint.Align.RIGHT, numbers, 0.2f);
            ink.setTypeface(numbers);
            ink.setTextSize(size * 0.85f);
            ink.setLetterSpacing(0.2f);
            vx -= ink.measureText(vs) + dp(14f);
        }
        text(c, "ON PACE FOR", vx, y, size * 0.85f, FAINT, Paint.Align.RIGHT, labels, 0.2f);
    }

    private void drawStats(Canvas c, float w, float h, float left, float right, int zone) {
        float rowTop = h * 0.80f;
        float rowBottom = h * 0.98f;
        float size = Math.min(rowBottom - rowTop, dp(88f));
        float cy = (rowTop + rowBottom) / 2f;

        line.setShader(null);
        line.setColor(DIVIDER);
        line.setStrokeWidth(dp(1f));
        c.drawLine(left, rowTop - dp(6f), right, rowTop - dp(6f), line);

        pill(c, left, cy, size, restartHit);
        drawRestartIcon(c, left + size / 2f, cy, size * 0.22f);
        pill(c, right - size, cy, size, pauseHit);
        drawPauseIcon(c, right - size / 2f, cy, size * 0.2f);

        double meters = pieceMeters();
        String avgSplit = pieceSeconds > 5 && meters > 5
                ? PersonalBests.formatPace((float) (pieceSeconds * 500.0 / meters)) : "--:--";
        int heart = heartRate();
        long kcal = Math.round(PulseMeter.kcalForWork(joules));

        String[] values = {
                heart > 0 ? String.valueOf(heart) : "--",
                String.valueOf(Math.round(shownWatts)),
                shownRate >= 1f ? String.valueOf(Math.round(shownRate)) : "--",
                shownPace > 0f ? PersonalBests.formatPace(shownPace) : "--:--",
                avgSplit,
                String.valueOf(Math.round(meters)),
                String.valueOf(kcal)
        };
        String[] names = {"HEART  BPM", "POWER  W", "RATE  SPM", "SPLIT  /500M", "AVG  /500M",
                "METERS", energyMeasured ? "KCAL  MEASURED" : "KCAL  EST"};

        float statsLeft = left + size + dp(28f);
        float statsRight = right - size - dp(28f);
        float col = (statsRight - statsLeft) / values.length;
        float valueY = cy + h * 0.012f;
        float nameY = valueY + h * 0.045f;
        for (int i = 0; i < values.length; i++) {
            float x = statsLeft + col * (i + 0.5f);
            boolean isSplit = i == 3;
            int color = isSplit && zone >= 0 ? ZONE_COLORS[zone] : TEXT;
            text(c, values[i], x, valueY, h * 0.068f, color, Paint.Align.CENTER, numbers, 0.02f);
            text(c, names[i], x, nameY, h * 0.022f, isSplit && zone >= 0 ? ZONE_COLORS[zone] : FAINT,
                    Paint.Align.CENTER, labels, 0.18f);
            if (isSplit) {
                // Four ticks under the split: which zone it is in, at a glance.
                float tick = dp(14f);
                float gap = dp(4f);
                float start = x - (4 * tick + 3 * gap) / 2f;
                for (int z = 0; z < 4; z++) {
                    fill.setColor(ZONE_COLORS[z]);
                    fill.setAlpha(z == zone ? 255 : 45);
                    rect.set(start + z * (tick + gap), nameY + dp(8f),
                            start + z * (tick + gap) + tick, nameY + dp(12f));
                    c.drawRoundRect(rect, dp(2f), dp(2f), fill);
                }
                fill.setAlpha(255);
            }
        }
    }

    private void pill(Canvas c, float left, float cy, float size, RectF hit) {
        rect.set(left, cy - size / 2f, left + size, cy + size / 2f);
        hit.set(rect.left - dp(12f), rect.top - dp(12f), rect.right + dp(12f), rect.bottom + dp(12f));
        fill.setColor(PILL);
        c.drawRoundRect(rect, size * 0.3f, size * 0.3f, fill);
        line.setShader(null);
        line.setColor(PILL_EDGE);
        line.setStrokeWidth(dp(1.5f));
        c.drawRoundRect(rect, size * 0.3f, size * 0.3f, line);
    }

    private void drawRestartIcon(Canvas c, float cx, float cy, float r) {
        line.setColor(TEXT);
        line.setStrokeWidth(dp(3f));
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, -60f, 300f, false, line);
        double a = Math.toRadians(-60);
        float ax = cx + (float) Math.cos(a) * r;
        float ay = cy + (float) Math.sin(a) * r;
        path.rewind();
        path.moveTo(ax + r * 0.55f, ay - r * 0.05f);
        path.lineTo(ax - r * 0.15f, ay - r * 0.55f);
        path.lineTo(ax - r * 0.1f, ay + r * 0.4f);
        path.close();
        fill.setColor(TEXT);
        c.drawPath(path, fill);
    }

    private void drawPauseIcon(Canvas c, float cx, float cy, float r) {
        fill.setColor(TEXT);
        if (paused || finished) {
            path.rewind();
            path.moveTo(cx - r * 0.7f, cy - r);
            path.lineTo(cx + r, cy);
            path.lineTo(cx - r * 0.7f, cy + r);
            path.close();
            c.drawPath(path, fill);
        } else {
            rect.set(cx - r * 0.8f, cy - r, cx - r * 0.25f, cy + r);
            c.drawRoundRect(rect, dp(2f), dp(2f), fill);
            rect.set(cx + r * 0.25f, cy - r, cx + r * 0.8f, cy + r);
            c.drawRoundRect(rect, dp(2f), dp(2f), fill);
        }
    }

    /**
     * The custom plan editor, over the bars until the rower taps START. Segments are equal-width
     * blocks, taller for a harder zone; the top timeline shows them at their true lengths.
     */
    private void drawEditor(Canvas c, float w, float h, float left, float right) {
        fill.setColor(0xE6050D19);
        c.drawRect(0, h * 0.18f, w, h * 0.77f, fill);
        float cx = w / 2f;
        text(c, "TAP A BLOCK TO SELECT IT  ·  TAP IT AGAIN TO CHANGE ZONE", cx, h * 0.235f, h * 0.024f,
                FAINT, Paint.Align.CENTER, labels, 0.2f);

        int n = custom.length;
        blocksLeft = left;
        blocksWidth = (right - left) / CUSTOM_MAX;
        blocksTop = h * 0.26f;
        blocksBottom = h * 0.47f;
        float slot = blocksWidth;
        float gap = dp(6f);
        // Centre the row of blocks.
        float rowLeft = left + (right - left - slot * n) / 2f;
        blocksLeft = rowLeft;
        for (int i = 0; i < n; i++) {
            int zone = custom[i][0];
            float bx = rowLeft + i * slot;
            float bh = (blocksBottom - blocksTop - h * 0.05f) * (0.35f + 0.65f * zone / 3f);
            float bTop = blocksBottom - h * 0.05f - bh;
            rect.set(bx + gap / 2f, bTop, bx + slot - gap / 2f, blocksBottom - h * 0.05f);
            fill.setColor(ZONE_COLORS[zone]);
            fill.setAlpha(i == editIndex ? 255 : 150);
            c.drawRoundRect(rect, dp(6f), dp(6f), fill);
            fill.setAlpha(255);
            if (i == editIndex) {
                line.setShader(null);
                line.setColor(TEXT);
                line.setStrokeWidth(dp(3f));
                rect.inset(-dp(4f), -dp(4f));
                c.drawRoundRect(rect, dp(8f), dp(8f), line);
            }
            float mx = bx + slot / 2f;
            text(c, clock(custom[i][1]), mx, blocksBottom - h * 0.018f, h * 0.026f,
                    i == editIndex ? TEXT : DIM, Paint.Align.CENTER, numbers, 0.05f);
            text(c, ZONES[zone], mx, bTop - dp(8f), h * 0.02f, ZONE_COLORS[zone], Paint.Align.CENTER,
                    i == editIndex ? numbers : labels, 0.15f);
        }

        // The control row.
        float btnTop = h * 0.52f;
        float btnH = h * 0.075f;
        float btnGap = dp(14f);
        float btnW = (right - left - btnGap * (EDIT_BUTTONS.length - 1)) / EDIT_BUTTONS.length;
        for (int i = 0; i < EDIT_BUTTONS.length; i++) {
            float bx = left + i * (btnW + btnGap);
            rect.set(bx, btnTop, bx + btnW, btnTop + btnH);
            editHits[i].set(rect.left, rect.top - dp(6f), rect.right, rect.bottom + dp(6f));
            boolean enabled = editEnabled(i);
            fill.setColor(PILL);
            c.drawRoundRect(rect, dp(12f), dp(12f), fill);
            line.setShader(null);
            line.setColor(i == 1 && enabled ? ZONE_COLORS[custom[editIndex][0]] : PILL_EDGE);
            line.setStrokeWidth(dp(1.5f));
            c.drawRoundRect(rect, dp(12f), dp(12f), line);
            text(c, EDIT_BUTTONS[i], bx + btnW / 2f, btnTop + btnH * 0.64f, h * 0.03f,
                    enabled ? TEXT : FAINT, Paint.Align.CENTER, numbers, 0.15f);
        }

        float startW = dp(300f);
        float startTop = h * 0.635f;
        rect.set(cx - startW / 2f, startTop, cx + startW / 2f, startTop + h * 0.09f);
        startHit.set(rect.left - dp(10f), rect.top - dp(10f), rect.right + dp(10f), rect.bottom + dp(10f));
        fill.setColor(ACCENT);
        c.drawRoundRect(rect, dp(16f), dp(16f), fill);
        text(c, "START  ·  " + lengthLabel(), cx, startTop + h * 0.061f, h * 0.036f, 0xFF05101E,
                Paint.Align.CENTER, numbers, 0.15f);
    }

    private boolean editEnabled(int button) {
        switch (button) {
            case 0: return editIndex > 0;
            case 2: return custom[editIndex][1] > CUSTOM_MIN_SECONDS;
            case 3: return custom[editIndex][1] < CUSTOM_MAX_SECONDS;
            case 4: return custom.length < CUSTOM_MAX;
            case 5: return custom.length > 1;
            case 6: return editIndex < custom.length - 1;
            default: return true;
        }
    }

    /** Applies one editor button. Edits replace the schedule array and are saved at once. */
    private void applyEdit(int button) {
        if (!editEnabled(button)) {
            return;
        }
        int n = custom.length;
        int[][] next = null;
        switch (button) {
            case 0:
                editIndex--;
                break;
            case 6:
                editIndex++;
                break;
            case 1:
                next = copyCustom(n);
                next[editIndex][0] = (next[editIndex][0] + 1) % 4;
                break;
            case 2:
                next = copyCustom(n);
                next[editIndex][1] -= CUSTOM_STEP;
                break;
            case 3:
                next = copyCustom(n);
                next[editIndex][1] += CUSTOM_STEP;
                break;
            case 4:
                next = new int[n + 1][];
                for (int i = 0, j = 0; i <= n; i++) {
                    next[i] = new int[]{custom[j][0], custom[j][1]};
                    if (i != editIndex) {
                        j++;
                    }
                }
                editIndex++;
                break;
            case 5:
                next = new int[n - 1][];
                for (int i = 0, j = 0; i < n; i++) {
                    if (i != editIndex) {
                        next[j++] = new int[]{custom[i][0], custom[i][1]};
                    }
                }
                editIndex = Math.min(editIndex, n - 2);
                break;
            default:
                break;
        }
        if (next != null) {
            setCustom(next);
        }
    }

    private int[][] copyCustom(int n) {
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            out[i] = new int[]{custom[i][0], custom[i][1]};
        }
        return out;
    }

    private void setCustom(int[][] next) {
        custom = next;
        segs = custom;
        targetZone = custom[0][0];
        lastTrace = loadTrace();   // a new length has its own LAST
        shownLastFrac = -1f;
        saveCustom();
    }

    /**
     * The guided cool-down: three descending rate steps drawn as a staircase with a rower walking
     * down it, over the live rate bar so the band below is the one to sit in. Only the top of the
     * screen is covered - the bar, the panels and the figures keep working underneath.
     */
    private void drawCooldown(Canvas c, float w, float h, float left, float right) {
        fill.setColor(0xF2050D19);
        c.drawRect(0, h * 0.18f, w, h * 0.578f, fill);
        float cx = w / 2f;
        float target = coolTargetRate(coolStep);
        float[] band = activeRateBand();
        boolean inBand = shownRate >= band[0] && shownRate <= band[1];

        text(c, coolDone ? "COOL DOWN COMPLETE" : "COOL DOWN  ·  STEP " + (coolStep + 1) + " / " + COOL_STEPS,
                cx, h * 0.232f, h * 0.032f, ACCENT, Paint.Align.CENTER, numbers, 0.3f);
        text(c, coolDone
                        ? Math.round(100.0 * coolInBand / Math.max(1.0, coolElapsed)) + "% HELD IN BAND"
                        : "EASE THE RATE DOWN - THE BAR BELOW IS THE STEP",
                cx, h * 0.268f, h * 0.024f, DIM, Paint.Align.CENTER, labels, 0.2f);

        // The staircase, one block per step, descending left to right. It stops above the button
        // row: with a taller floor the two buttons were drawn straight over the blocks, hiding the
        // middle step's rate and countdown entirely.
        float stairsLeft = left + (right - left) * 0.18f;
        float stairsRight = right - (right - left) * 0.18f;
        float stepW = (stairsRight - stairsLeft) / COOL_STEPS;
        float topY = h * 0.345f;
        float dropY = h * 0.036f;
        float floorY = h * 0.486f;
        for (int i = 0; i < COOL_STEPS; i++) {
            float sx = stairsLeft + stepW * i;
            float sy = topY + dropY * i;
            rect.set(sx + dp(6f), sy, sx + stepW - dp(6f), floorY);
            boolean done = i < coolStep || (coolDone && i <= coolStep);
            boolean now = i == coolStep && !coolDone;
            fill.setColor(now ? ACCENT : done ? 0xFF1E6F66 : TRACK);
            fill.setAlpha(now ? 255 : done ? 220 : 255);
            c.drawRoundRect(rect, dp(10f), dp(10f), fill);
            fill.setAlpha(255);
            if (now) {
                line.setShader(null);
                line.setColor(ACCENT);
                line.setAlpha((int) (90 + 90 * (0.5f + 0.5f * (float) Math.sin(coolGlow * 4.0))));
                line.setStrokeWidth(dp(3f));
                rect.inset(-dp(5f), -dp(5f));
                c.drawRoundRect(rect, dp(13f), dp(13f), line);
                line.setAlpha(255);
            }
            float mx = sx + stepW / 2f;
            text(c, Math.round(coolTargetRate(i)) + " SPM", mx, sy + h * 0.034f, h * 0.032f,
                    now ? 0xFF05101E : done ? TEXT : DIM, Paint.Align.CENTER, numbers, 0.1f);
            text(c, i < coolStep || coolDone ? "DONE" : i == coolStep ? clock(Math.ceil(coolLeft)) : "0:45",
                    mx, sy + h * 0.064f, h * 0.026f, now ? 0xFF05101E : FAINT, Paint.Align.CENTER,
                    labels, 0.15f);
        }

        // The rower on the current step: bobs with the stroke, green when the rate is in band.
        float px = stairsLeft + stepW * (coolStep + 0.5f);
        float py = topY + dropY * coolStep - dp(8f) - (float) Math.abs(Math.sin(coolBob * 2.0)) * dp(6f);
        drawCoolRower(c, px, py, h, inBand);

        float btnW = dp(230f);
        float btnH = h * 0.048f;
        float btnY = h * 0.505f;
        rect.set(cx - btnW - dp(10f), btnY, cx - dp(10f), btnY + btnH);
        skipHit.set(rect.left, rect.top - dp(8f), rect.right, rect.bottom + dp(8f));
        fill.setColor(PILL);
        c.drawRoundRect(rect, dp(12f), dp(12f), fill);
        line.setShader(null);
        line.setColor(PILL_EDGE);
        line.setStrokeWidth(dp(1.5f));
        c.drawRoundRect(rect, dp(12f), dp(12f), line);
        text(c, coolDone ? "ROW AGAIN" : "SKIP STEP", cx - btnW / 2f - dp(10f), btnY + btnH * 0.66f,
                h * 0.028f, TEXT, Paint.Align.CENTER, numbers, 0.15f);

        rect.set(cx + dp(10f), btnY, cx + dp(10f) + btnW, btnY + btnH);
        coolHit.set(rect.left, rect.top - dp(8f), rect.right, rect.bottom + dp(8f));
        fill.setColor(coolDone ? ACCENT : PILL);
        c.drawRoundRect(rect, dp(12f), dp(12f), fill);
        if (!coolDone) {
            line.setColor(PILL_EDGE);
            c.drawRoundRect(rect, dp(12f), dp(12f), line);
        }
        text(c, coolDone ? "FINISH" : "END COOL DOWN", cx + dp(10f) + btnW / 2f, btnY + btnH * 0.66f,
                h * 0.028f, coolDone ? 0xFF05101E : TEXT, Paint.Align.CENTER, numbers, 0.15f);

        // The live rate, under the buttons and directly above the rate bar it is read off.
        text(c, shownRate >= 1f ? String.format(java.util.Locale.US, "%.1f", shownRate) + " SPM  ·  TARGET "
                        + Math.round(target) : "TARGET " + Math.round(target) + " SPM",
                cx, h * 0.574f, h * 0.026f, inBand ? ACCENT : WARN, Paint.Align.CENTER, numbers, 0.15f);

        if (!isClockRunning() && !coolDone) {
            text(c, "KEEP ROWING - THE STEP HOLDS WHILE YOU REST", cx, h * 0.31f, h * 0.024f, WARN,
                    Paint.Align.CENTER, numbers, 0.2f);
        }
    }

    /** A little rower on the staircase: head, body, and an oar that sweeps with the real stroke. */
    private void drawCoolRower(Canvas c, float x, float footY, float h, boolean inBand) {
        float unit = h * 0.018f;
        int body = inBand ? ACCENT : TEXT;
        float lean = (strokePhase() - 0.5f) * unit * 1.1f;
        fill.setColor(body);
        c.drawCircle(x + lean, footY - unit * 3.4f, unit * 0.78f, fill);
        line.setShader(null);
        line.setColor(body);
        line.setStrokeWidth(unit * 0.5f);
        c.drawLine(x, footY, x + lean, footY - unit * 2.6f, line);
        // Legs, planted, knees bending a little with the bob.
        c.drawLine(x, footY, x - unit * 0.8f, footY - unit * 0.1f, line);
        c.drawLine(x, footY, x + unit * 0.9f, footY - unit * 0.1f, line);
        // The oar: sweeps through the drive, feathers back through the recovery.
        float angle = (float) Math.toRadians(-20 + 70 * strokePhase());
        float ox = (float) Math.cos(angle) * unit * 2.6f;
        float oy = (float) Math.sin(angle) * unit * 2.6f;
        line.setColor(inBand ? ACCENT : DIM);
        line.setStrokeWidth(unit * 0.35f);
        c.drawLine(x + lean - ox, footY - unit * 2.1f - oy, x + lean + ox, footY - unit * 2.1f + oy, line);
        fill.setColor(inBand ? ACCENT : DIM);
        c.drawCircle(x + lean + ox, footY - unit * 2.1f + oy, unit * 0.42f, fill);
    }

    private void drawFinished(Canvas c, float w, float h) {
        fill.setColor(0xD8050D19);
        c.drawRect(0, h * 0.18f, w, h * 0.77f, fill);
        float cx = w * 0.29f;
        text(c, "PIECE COMPLETE", cx, h * 0.28f, h * 0.045f, ACCENT, Paint.Align.CENTER, numbers, 0.3f);
        String headline;
        String sub;
        String best = "";
        if (segs != null) {
            headline = Math.round(planScore()) + "%";
            sub = plan.name() + " ON SCHEDULE  ·  " + Math.round(pieceMeters()) + " M";
            if (!newBest && bests.has(planKey())) {
                best = "  ·  BEST " + Math.round(bests.get(planKey(), 0f)) + "%";
            }
        } else if (plan == Plan.STREAK) {
            headline = clock(bestStreak);
            sub = "LONGEST AT " + ZONES[targetZone] + "  ·  " + Math.round(pieceMeters()) + " M";
            if (!newBest && bests.has("zonerow.streak")) {
                best = "  ·  BEST " + clock(bests.get("zonerow.streak", 0f));
            }
        } else {
            headline = Math.round(pieceMeters()) + " M";
            sub = pieceMinutes() + " MIN  ·  " + clock(inZoneSeconds) + " IN " + ZONES[targetZone];
            if (!newBest && bests.has("zonerow." + pieceMinutes())) {
                best = "  ·  BEST " + Math.round(bests.get("zonerow." + pieceMinutes(), 0f)) + " M";
            }
        }
        if (newBest) {
            best = "  ·  NEW BEST";
        }
        text(c, headline, cx, h * 0.43f, h * 0.14f, TEXT, Paint.Align.CENTER, numbers, 0.02f);
        text(c, sub + best, cx, h * 0.51f, h * 0.03f, newBest ? WARN : DIM, Paint.Align.CENTER, labels, 0.15f);
        if (plan != Plan.HEART) {
            text(c, "LOCK  " + Math.round(lockPoints) + " PTS  ·  LONGEST " + clock(bestLock), cx, h * 0.575f,
                    h * 0.03f, ZONE_COLORS[targetZone], Paint.Align.CENTER, numbers, 0.15f);
        }
        // Against the previous piece of this length. lastTrace is still the one loaded at the start.
        double last = lastFinalMeters();
        if (last >= 0) {
            double d = pieceMeters() - last;
            text(c, "LAST " + Math.round(last) + " M  ·  " + (d >= 0 ? "+" : "\u2212") + Math.round(Math.abs(d)) + " M",
                    cx, h * 0.635f, h * 0.03f, d >= 0 ? ACCENT : BAD, Paint.Align.CENTER, numbers, 0.15f);
        }
        drawZoneChart(c, w, h);

        // The cool-down is offered here rather than started for them: the piece is theirs to end.
        float btnW = dp(300f);
        float btnH = h * 0.058f;
        float btnTop = h * 0.665f;
        rect.set(cx - btnW / 2f, btnTop, cx + btnW / 2f, btnTop + btnH);
        coolHit.set(rect.left - dp(10f), rect.top - dp(10f), rect.right + dp(10f), rect.bottom + dp(10f));
        fill.setColor(coolDone ? PILL : ACCENT);
        c.drawRoundRect(rect, dp(14f), dp(14f), fill);
        if (coolDone) {
            line.setShader(null);
            line.setColor(PILL_EDGE);
            line.setStrokeWidth(dp(1.5f));
            c.drawRoundRect(rect, dp(14f), dp(14f), line);
        }
        String coolLabel = coolDone
                ? "COOL DOWN DONE  \u00B7  " + Math.round(100.0 * coolInBand / Math.max(1.0, coolElapsed)) + "% IN BAND"
                : "COOL DOWN  \u00B7  " + COOL_STEPS + " RATE STEPS";
        text(c, coolLabel, cx, btnTop + btnH * 0.66f, h * 0.030f, coolDone ? DIM : 0xFF05101E,
                Paint.Align.CENTER, numbers, 0.15f);
        if (rescuesMade > 0) {
            text(c, rescuesMade + (rescuesMade == 1 ? " RESCUE" : " RESCUES"), cx, h * 0.745f, h * 0.026f,
                    WARN, Paint.Align.CENTER, numbers, 0.2f);
        }
        text(c, "TAP  \u21BA  TO ROW AGAIN", w / 2f, h * 0.762f, h * 0.024f, FAINT, Paint.Align.CENTER, labels, 0.25f);
    }

    /** Time in each zone, as bars that grow in once the piece ends. */
    private void drawZoneChart(Canvas c, float w, float h) {
        float left = w * 0.56f;
        float right = w * 0.95f;
        text(c, "TIME IN ZONE", left, h * 0.28f, h * 0.03f, DIM, Paint.Align.LEFT, numbers, 0.3f);
        double total = 0;
        double most = 1;
        for (double v : zoneSeconds) {
            total += v;
            most = Math.max(most, v);
        }
        float labelW = w * 0.075f;
        float valueW = w * 0.11f;
        float barLeft = left + labelW;
        float barRight = right - valueW;
        float rowH = h * 0.085f;
        float ease = 1f - (1f - chartAnim) * (1f - chartAnim);
        for (int z = 0; z < 4; z++) {
            float top = h * 0.32f + z * rowH;
            float barH = rowH * 0.55f;
            float cy = top + barH / 2f;
            // The zones the piece asked for are marked, so the chart reads against the plan.
            boolean asked = segs != null ? planUses(z) : z == targetZone;
            text(c, ZONES[z], left, cy + h * 0.011f, h * 0.026f, asked ? ZONE_COLORS[z] : FAINT,
                    Paint.Align.LEFT, asked ? numbers : labels, 0.15f);
            rect.set(barLeft, top, barRight, top + barH);
            fill.setColor(TRACK);
            c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
            float frac = (float) (zoneSeconds[z] / most) * ease;
            if (frac > 0.005f) {
                rect.right = barLeft + Math.max(barH, (barRight - barLeft) * frac);
                fill.setColor(ZONE_COLORS[z]);
                c.drawRoundRect(rect, barH / 2f, barH / 2f, fill);
            }
            int pct = total > 0 ? (int) Math.round(100.0 * zoneSeconds[z] / total) : 0;
            text(c, clock(zoneSeconds[z] * ease) + "  " + pct + "%", right, cy + h * 0.011f, h * 0.026f,
                    TEXT, Paint.Align.RIGHT, numbers, 0.05f);
        }
    }

    private boolean planUses(int zone) {
        for (int[] seg : segs) {
            if (seg[0] == zone) {
                return true;
            }
        }
        return false;
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align,
                      Typeface face, float spacing) {
        ink.setTypeface(face);
        ink.setTextSize(size);
        ink.setColor(color);
        ink.setTextAlign(align);
        ink.setLetterSpacing(spacing);
        c.drawText(s, x, y, ink);
    }

    /* ---------- touch ---------- */

    private boolean handleEditorTap(float x, float y) {
        if (startHit.contains(x, y)) {
            editing = false;
            paused = false;
            targetZone = custom[0][0];
            lockTarget = targetZone;
            // Metres rowed while the editor was up are not part of the piece.
            pieceStartMeters = sessionMeters;
            return true;
        }
        for (int i = 0; i < editHits.length; i++) {
            if (editHits[i].contains(x, y)) {
                applyEdit(i);
                return true;
            }
        }
        if (y >= blocksTop && y <= blocksBottom && blocksWidth > 0f) {
            int i = (int) Math.floor((x - blocksLeft) / blocksWidth);
            if (i >= 0 && i < custom.length) {
                if (i == editIndex) {
                    applyEdit(1);
                } else {
                    editIndex = i;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * The two cool-down buttons. On the finish screen the right-hand one starts it; inside the
     * cool-down the left one skips a step (or rows again once it is done) and the right one ends it.
     */
    private void handleCooldownTap(boolean skip) {
        if (!cooling) {
            if (!skip) {
                cooling = true;
                coolDone = false;
                coolStep = 0;
                coolLeft = COOL_STEP_SECONDS;
                coolInBand = 0;
                coolElapsed = 0;
            }
            return;
        }
        if (skip) {
            if (coolDone) {
                restart();
                return;
            }
            coolStep++;
            coolLeft = COOL_STEP_SECONDS;
            if (coolStep >= COOL_STEPS) {
                coolStep = COOL_STEPS - 1;
                coolDone = true;
                coolLeft = 0f;
            }
            return;
        }
        if (!coolDone && coolElapsed > 30) {
            bests.recordHighest(COOL_KEY, (float) (100.0 * coolInBand / coolElapsed));
        }
        cooling = false;
    }

    @Override
    protected void onStop() {
        saveWeek();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX();
            float y = e.getY();
            if (editing && handleEditorTap(x, y)) {
                postInvalidateOnAnimation();
            } else if (!editing && (coolHit.contains(x, y) || skipHit.contains(x, y))) {
                handleCooldownTap(skipHit.contains(x, y));
            } else if (restartHit.contains(x, y)) {
                restart();
            } else if (pauseHit.contains(x, y)) {
                if (editing) {
                    // Nothing to pause yet: START begins the piece.
                } else if (finished) {
                    restart();
                } else {
                    paused = !paused;
                }
            } else if (!editing && !cooling && !finished && y >= zoneTop && y <= zoneBottom
                    && span > 0f && segs == null) {
                // Only when the zone names are actually on screen. The editor, the cool-down and
                // the finish card all cover this band, and a miss on one of them used to change
                // the target zone invisibly - which moves the week bar, the lock colour and the
                // finish card's "LONGEST AT" line.
                int z = (int) ((x - x0) / (span / 4f));
                if (z >= 0 && z < 4) {
                    targetZone = z;
                }
            }
            postInvalidateOnAnimation();
        }
        // Still hand the event on, so a long-press can take a screenshot on local builds.
        super.onTouchEvent(e);
        return true;
    }
}
