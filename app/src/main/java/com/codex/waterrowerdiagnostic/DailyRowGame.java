package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * DAILY ROW: one short challenge a day, the same for everyone on that date, and a streak for doing it.
 *
 * <p>Six kinds rotate by date: most metres in four minutes, a 500 m time trial, ten powerful
 * strokes, three minutes held steady at your typical speed, a stroke-rate ladder, and a 500 m
 * negative split. Targets come from the rower's own profile, so the challenge is a stretch for
 * anyone. Completing it marks the day; consecutive days make the streak.
 *
 * <p>3.19.5 (the emulator screenshot was text and a grey bar on black): drifting sparkles, a big
 * progress ring with a medal that fills and glows, a stamp and confetti when the day is won, ticks
 * on completed calendar days and a flame on the streak.
 *
 * <p>Upgrades:
 * <ul>
 * <li><b>Sunday boss.</b> On Sundays the day's challenge is harder and THE KRAKEN sits top right
 * with a health bar. It takes hits while you are on pace and swings at you while you are not.
 * Beating it earns a boss medal and a streak saver.</li>
 * <li><b>Month view.</b> The MONTH chip swaps the 28-day strip for a calendar month with a medal on
 * every day won - bronze, silver or gold by the margin, purple for a boss, a blue shield for a day
 * a streak saver covered. Arrows step back through earlier months.</li>
 * <li><b>Rate ladder and negative split.</b> Two new challenge types.</li>
 * <li><b>Streak saver.</b> One token, held at most one at a time, earned by beating a Sunday boss
 * or by reaching a multiple of seven days. If you miss exactly one day it is spent automatically
 * the next time you open Daily Row and the missed day is shielded.</li>
 * <li><b>Result card.</b> After a challenge, RESULT CARD draws a shareable summary over the screen;
 * the camera button saves it.</li>
 * </ul>
 *
 * <p>3.23.0 adds the reward economy the rower asked for. Every win pays <b>points</b>, and the five
 * pieces all feed the same pot:
 * <ul>
 * <li><b>Difficulty.</b> Four chips on the ready screen - EASY / STANDARD / HARD / EPIC - move the
 * target through the rower's own range (never a constant: EASY asks for their typical pace, EPIC
 * asks for their 90th percentile and beyond) and pay x0.7 to x2.4. HARD floors the medal at silver,
 * EPIC at gold, so a hard day is worth more in every currency.</li>
 * <li><b>Weekly streak rewards.</b> A Monday-start week strip with reward stars at 3, 5 and 7 days
 * won: +15, +25 and a streak saver, +50 and a PERFECT WEEK. Claimed milestones persist as a mask so
 * a week cannot be farmed twice.</li>
 * <li><b>Double or nothing.</b> Once a day, after the day is banked, DOUBLE OR NOTHING replays the
 * challenge one difficulty step harder with today's points as the stake: win and they double and the
 * medal steps up, lose and they are gone. The day itself and the streak are never at risk.</li>
 * <li><b>Monthly badge.</b> A rosette for winning 40% / 60% / 80% of a calendar month's days, worth
 * +40 / +80 / +150, drawn in the reward panel and beside the month grid.</li>
 * <li><b>Tomorrow's preview.</b> On finishing, a panel shows tomorrow's challenge, its target at the
 * chosen difficulty and an animated glyph - a bobbing Kraken when tomorrow is a Sunday.</li>
 * </ul>
 *
 * <p>3.25.0 graphics pass (look only - no challenge, date, streak, points or boss logic changed): a
 * gradient backdrop with a drifting nebula, a lit plaque behind the day's name, the ring as a groove
 * in a metal bezel with dial ticks and a comet fill whose leading edge glows and sheds sparks, every
 * medal struck in real metal (bronze / silver / gold / boss, steel while unearned, tarnished on a
 * miss) with a sweeping glint, a glossy progress bar with a lit head, hand-stamped calendar days,
 * and a layered animated streak flame with embers (an unlit wick at zero). All gradients are cached
 * by size; nothing is allocated per frame.
 */
final class DailyRowGame extends GameView {

    enum Challenge {
        DISTANCE_4MIN("MOST METRES IN 4 MINUTES"),
        TRIAL_500("500 m TIME TRIAL"),
        POWER_10("10 POWERFUL STROKES"),
        STEADY_3MIN("3 MINUTES STEADY"),
        RATE_LADDER("STROKE RATE LADDER"),
        NEG_SPLIT("500 m NEGATIVE SPLIT");

        final String title;

        Challenge(String title) {
            this.title = title;
        }
    }

    private enum Phase { READY, ACTIVE, DONE }

    /** Medal tiers stored per day in {@code daily.medals}. */
    private static final int BRONZE = 1;
    private static final int SILVER = 2;
    private static final int GOLD = 3;
    private static final int BOSS_MEDAL = 4;
    /** Seconds on each rung of the rate ladder; the first few of each are not scored. */
    private static final double RUNG_SECONDS = 45;
    private static final double RUNG_GRACE = 6;
    private static final int BOSS_COLOR = 0xFFB06CFF;
    private static final int[] CONFETTI = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};
    private static final String[] WEEKDAYS = {"M", "T", "W", "T", "F", "S", "S"};

    /* ---------- the reward economy (3.23.0) ---------- */

    private static final String[] DIFF_NAMES = {"EASY", "STANDARD", "HARD", "EPIC"};
    /** What a win pays, as a multiple of the medal's base points. */
    private static final double[] DIFF_REWARD = {0.7, 1.0, 1.6, 2.4};
    /** Points a medal is worth before the difficulty multiplier. */
    private static final int[] TIER_POINTS = {0, 10, 14, 20, 30};
    /** Days won in a Monday-start week that pay a reward, and what each pays. */
    private static final int[] WEEK_STEPS = {3, 5, 7};
    private static final int[] WEEK_POINTS = {15, 25, 50};
    /** Fraction of a calendar month's days needed for a bronze / silver / gold rosette. */
    private static final double[] MONTH_SHARE = {0.40, 0.60, 0.80};
    private static final int[] MONTH_POINTS = {40, 80, 150};
    private static final String[] MONTH_BADGE_NAMES = {"BRONZE MONTH", "SILVER MONTH", "GOLD MONTH"};

    // Per-difficulty target tables. Every one is anchored to the rower's profile, never a constant.
    private static final double[] D4_SPEED = {1.00, 1.03, 1.07, 1.11};
    private static final double[] T5_TIME = {1.00, 0.97, 0.935, 0.90};
    /** Fraction of the rower's low..high watts band asked of ten strokes; above 1 goes past p90. */
    private static final double[] P10_FRACTION = {0.75, 1.00, 1.15, 1.30};
    private static final double[] STEADY_PCT = {55, 70, 82, 90};
    private static final double[] STEADY_BAND = {0.075, 0.060, 0.050, 0.040};
    private static final double[] LADDER_PCT = {45, 55, 68, 78};
    private static final double[] LADDER_BAND = {2.0, 1.5, 1.5, 1.0};
    private static final int[][] LADDER_OFFSETS = {
        {-6, -4, -2, 0}, {-4, -2, 0, 2}, {-4, -2, 0, 2, 4}, {-2, 0, 2, 4, 6}};
    private static final double[] NEG_SECONDS = {0.0, 0.5, 1.8, 3.0};
    private static final double[] NEG_CAP = {1.10, 1.04, 1.00, 0.97};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.TreeSet<Long> days = new java.util.TreeSet<>();
    /** Days covered by a streak saver: they count for the streak but are not wins. */
    private final java.util.TreeSet<Long> saved = new java.util.TreeSet<>();
    private final java.util.TreeMap<Long, Integer> medals = new java.util.TreeMap<>();

    private Challenge challenge;
    private long today;
    private boolean boss;
    private Phase phase = Phase.READY;
    private double startSeconds;
    private double startMeters;
    private double target;
    private double result;
    private boolean success;
    private int strokes;
    private double powerSum;
    private double lastStrokeW;
    private double steadySeconds;
    private PulseMeter.Stroke lastSeen;
    private final Fx.Particles fx = new Fx.Particles();
    private final android.graphics.Path shape = new android.graphics.Path();
    private double doneAt = -10;
    private float ringShown;

    // Rate ladder.
    private final int[] ladderRates = new int[5];
    private int rungs;
    private double ladderIn;
    private double ladderCounted;
    private int lastRung = -1;
    private double rungFlashAt = -10;

    // Negative split: time at every 10 m of the first half, the ghost you race in the second.
    private final double[] firstHalfAt = new double[26];
    private int nextMark;
    private double halfTime = -1;
    private double negCap;
    private double negTotal;

    // Live "something at stake": are you on the target's pace right now?
    private boolean onTrack;
    private String paceText = "";

    // Boss.
    private float bossHp = 1f;
    private float bossHpShown = 1f;
    private float flinch;
    private double hitTimer;
    private float attack;

    // Streak saver.
    private boolean token;
    private String banner = "";
    private int bannerColor = BLUE;
    private double bannerAt = -10;

    // Month view and result card.
    private boolean monthView;
    private int monthOffset;
    private long monthFirst;
    private int monthLen;
    private int monthLead;
    private String monthTitle = "";
    private final int[] monthTier = new int[31];
    private boolean showCard;
    private int todayTier;
    private String dateText = "";
    private String weekdayText = "";
    private final RectF monthChip = new RectF();
    private final RectF cardChip = new RectF();
    private final RectF prevArrow = new RectF();
    private final RectF nextArrow = new RectF();

    // Difficulty.
    private int diff = 1;
    private double steadyBand = STEADY_BAND[1];
    private double ladderBand = LADDER_BAND[1];
    private final RectF[] diffChips = new RectF[DIFF_NAMES.length];

    // Points.
    private float points;
    private float pointsShown;
    private int todayPoints;
    private int floatPoints;
    private double floatAt = -10;

    // Double or nothing.
    private boolean doubling;
    private boolean pendingDouble;
    private boolean doubleUsed;
    /** 0 no double resolved this attempt, +1 doubled, -1 lost. */
    private int doubleResult;
    private final RectF doubleChip = new RectF();

    // Weekly rewards.
    private long weekStart;
    private int weekMask;
    private int weekWon;
    private final boolean[] weekDone = new boolean[7];
    private int weekFlash = -1;
    private double weekFlashAt = -10;

    // Monthly badge.
    private final java.util.TreeMap<Integer, Integer> badges = new java.util.TreeMap<>();
    private int curMonthWon;
    private int curMonthLen = 30;
    private int curMonthTier;
    private int curMonthNeed;
    private String curMonthName = "";
    private double badgeFlashAt = -10;
    /** The displayed month's rosette tier, recomputed with the grid rather than per frame. */
    private int gridMonthTier;
    private int gridMonthWon;

    // Tomorrow's preview.
    private Challenge tomorrowChallenge = Challenge.DISTANCE_4MIN;
    private boolean tomorrowBoss;
    private String tomorrowLine = "";
    private final RectF panel = new RectF();

    // Banner queue: several rewards can land on one stroke, and each deserves its own line.
    private final java.util.ArrayDeque<String> bannerQueue = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Integer> bannerTints = new java.util.ArrayDeque<>();

    /* ---------- 3.25.0 graphics: cached shaders, built on size change and never per frame ---------- */

    /** Gradient fills get their own paint, so a leftover alpha or shader on {@link #paint} cannot leak. */
    private final Paint gp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Matrix shineMatrix = new android.graphics.Matrix();
    /** Metal shader sets by palette and rounded radius: a handful of keys, made once each. */
    private final android.util.SparseArray<Metal> metals = new android.util.SparseArray<>();
    /** Calendar cell gloss by kind and rounded height. */
    private final android.util.SparseArray<android.graphics.LinearGradient> cellShaders =
            new android.util.SparseArray<>();
    private android.graphics.LinearGradient bgShader;
    private float bgShaderH;
    private boolean bgShaderBoss;
    private android.graphics.LinearGradient plaqueShader;
    private float plaqueShaderH;
    private android.graphics.LinearGradient barFill;
    private android.graphics.LinearGradient barFillWarn;
    private float barShaderH;
    private android.graphics.LinearGradient bezelShader;
    private float bezelShaderR;
    private android.graphics.LinearGradient flameShader;
    private int flameShaderKey;
    /** Ring fills, comet-shaped: dark at the tail, bright at the leading edge. Size-independent. */
    private final android.graphics.SweepGradient ringAccent;
    private final android.graphics.SweepGradient ringWarn;
    private final android.graphics.Path flame = new android.graphics.Path();

    // Metal palettes, indexed by medal tier: 0 unearned steel, 1 bronze, 2 silver, 3 gold, 4 boss,
    // 5 tarnished (a missed day). hi is the specular, deep the shadowed far side.
    private static final int PAL_STEEL = 0;
    private static final int PAL_TARNISHED = 5;
    private static final int[] PAL_HI = {0xFF8C99AC, 0xFFFFD9B0, 0xFFFFFFFF, 0xFFFFF6C8, 0xFFF0DCFF, 0xFF9C8A74};
    private static final int[] PAL_FACE = {0xFF3A4658, 0xFFCD7F32, 0xFFC9D3DE, 0xFFF5C518, 0xFFB06CFF, 0xFF4A3F36};
    private static final int[] PAL_DEEP = {0xFF161E2B, 0xFF6A3A12, 0xFF5E6A7A, 0xFF8A6206, 0xFF4A2490, 0xFF221B15};
    private static final int[] PAL_RIM_LIGHT = {0xFF5E6B7E, 0xFFEBB07A, 0xFFF4F8FC, 0xFFFFE38A, 0xFFDDBEFF, 0xFF7E6E5C};
    private static final int[] PAL_RIM_DARK = {0xFF0E141E, 0xFF3E1F06, 0xFF3C4552, 0xFF5E4204, 0xFF2A1058, 0xFF16110D};
    private static final int[] PAL_STAR = {0xFF56647A, 0xFFF2B47C, 0xFFF4F8FC, 0xFFFFE89A, 0xFFE6CCFF, 0xFF7A6A58};
    private static final int[] PAL_STAR_SHADOW = {0xFF121A26, 0xFF5A300C, 0xFF56616F, 0xFF8A6206, 0xFF3A1A78, 0xFF1A1410};

    /** One medal's shaders, centred on the origin and drawn under a canvas translate. */
    private static final class Metal {
        final android.graphics.RadialGradient face;
        final android.graphics.LinearGradient rim;
        final android.graphics.LinearGradient shine;

        Metal(int pal, float r) {
            face = new android.graphics.RadialGradient(-r * 0.32f, -r * 0.38f, r * 1.3f,
                    new int[] {PAL_HI[pal], PAL_FACE[pal], PAL_DEEP[pal]}, new float[] {0f, 0.42f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            rim = new android.graphics.LinearGradient(-r, -r, r, r,
                    new int[] {PAL_RIM_LIGHT[pal], PAL_FACE[pal], PAL_RIM_DARK[pal]}, new float[] {0f, 0.45f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            // A narrow diagonal band of light; moved across the face with a local matrix.
            shine = new android.graphics.LinearGradient(-r, -r, r, r,
                    new int[] {0x00FFFFFF, 0x00FFFFFF, 0xC8FFFFFF, 0x00FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.42f, 0.5f, 0.58f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        }
    }

    DailyRowGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        for (int i = 0; i < diffChips.length; i++) {
            diffChips[i] = new RectF();
        }
        gp.setDither(true);
        // Stops: bright across the wrap (so the round cap past the tip never meets a hard edge), a
        // quick fall to dark just after it, then a long rise to the leading edge at 0.96. The arc is
        // drawn rotated so its tip sits on 0.96, and its tail lands at 0.96 minus the fraction filled.
        float[] stops = {0f, 0.015f, 0.08f, 0.70f, 0.96f, 1f};
        ringAccent = new android.graphics.SweepGradient(0, 0,
                new int[] {0xFFB4FFF3, 0xFF8AF5E6, 0xFF0F5E55, 0xFF2BB8A4, 0xFFB4FFF3, 0xFFB4FFF3}, stops);
        ringWarn = new android.graphics.SweepGradient(0, 0,
                new int[] {0xFFFFE6A8, 0xFFFFD27A, 0xFF6A4A0E, 0xFFD69A22, 0xFFFFE6A8, 0xFFFFE6A8}, stops);
    }

    @Override
    protected void onStart() {
        today = RegattaGame.today();
        challenge = challengeFor(today);
        boss = bossFor(today);
        doubling = pendingDouble;
        pendingDouble = false;
        doubleResult = 0;
        readDays(days, "daily.days");
        readDays(saved, "daily.saved");
        medals.clear();
        String m = bests.getString("daily.medals");
        if (m != null) {
            for (String s : m.split(",")) {
                int colon = s.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                try {
                    medals.put(Long.parseLong(s.substring(0, colon)), Integer.parseInt(s.substring(colon + 1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        token = "1".equals(bests.getString("daily.token"));
        banner = "";
        bannerAt = -10;
        bannerQueue.clear();
        bannerTints.clear();
        // start() rewinds sessionSeconds to 0, so every animation stamp taken in the previous
        // attempt is now in the future. Left alone, a reward star or rosette from the last run
        // computes a "flash" of 100+ and fills the screen. Rewind them with the clock.
        doneAt = -10;
        floatAt = -10;
        floatPoints = 0;
        weekFlash = -1;
        weekFlashAt = -10;
        badgeFlashAt = -10;
        rungFlashAt = -10;
        readDifficulty();
        readPoints();
        if (doubling) {
            // The chance is spent the moment the attempt starts, so walking out mid-attempt cannot
            // be used to re-roll it. Only a finished attempt moves the points.
            doubleUsed = true;
            writeToday();
        }
        readBadges();
        readWeek();
        pointsShown = points;
        spendTokenIfNeeded();
        computeTargets();
        refreshWeek(false);
        refreshMonthBadge(false);
        refreshTomorrow();
        phase = Phase.READY;
        strokes = 0;
        powerSum = 0;
        lastStrokeW = 0;
        steadySeconds = 0;
        result = 0;
        success = false;
        ladderIn = 0;
        ladderCounted = 0;
        lastRung = -1;
        nextMark = 0;
        halfTime = -1;
        negTotal = 0;
        bossHp = 1f;
        bossHpShown = 1f;
        flinch = 0;
        attack = 0;
        hitTimer = 0;
        showCard = false;
        paceText = "";
        onTrack = false;
        Integer t = medals.get(today);
        todayTier = t != null ? t : 0;

        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US);
        f.setTimeZone(utc);
        dateText = f.format(new java.util.Date(today * 86400000L));
        java.text.SimpleDateFormat wf = new java.text.SimpleDateFormat("EEEE", java.util.Locale.US);
        wf.setTimeZone(utc);
        weekdayText = wf.format(new java.util.Date(today * 86400000L)).toUpperCase(java.util.Locale.US);
        buildMonth();
    }

    private void readDays(java.util.TreeSet<Long> into, String key) {
        into.clear();
        String saved = bests.getString(key);
        if (saved != null) {
            for (String s : saved.split(",")) {
                try {
                    into.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
    }

    private void writeDays(java.util.TreeSet<Long> from, String key) {
        while (from.size() > 400) {
            from.pollFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (long d : from) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(d);
        }
        bests.putString(key, sb.toString());
    }

    private void writeMedals() {
        while (medals.size() > 400) {
            medals.pollFirstEntry();
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Long, Integer> e : medals.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        bests.putString("daily.medals", sb.toString());
    }

    private boolean counts(long day) {
        return days.contains(day) || saved.contains(day);
    }

    /* ---------- the day's challenge, for any date ---------- */

    /** The challenge on {@code day}: a pure function of the date, so tomorrow can be previewed. */
    private static Challenge challengeFor(long day) {
        long mix = day * 2654435761L;
        return Challenge.values()[(int) (((mix >>> 16) & 0x7fffffff) % Challenge.values().length)];
    }

    /** Day 0 (1 Jan 1970) was a Thursday, so (day + 4) % 7 is 0 on a Sunday. */
    private static boolean bossFor(long day) {
        return (day + 4) % 7 == 0;
    }

    /* ---------- difficulty ---------- */

    private void readDifficulty() {
        String s = bests.getString("daily.diff");
        int d = 1;
        if (s != null) {
            try {
                d = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                d = 1;
            }
        }
        diff = Math.max(0, Math.min(DIFF_NAMES.length - 1, d));
    }

    private void setDifficulty(int d) {
        d = Math.max(0, Math.min(DIFF_NAMES.length - 1, d));
        if (d == diff) {
            return;
        }
        diff = d;
        bests.putString("daily.diff", String.valueOf(diff));
        computeTargets();
        refreshTomorrow();
    }

    /**
     * Targets for the day's challenge at the chosen difficulty, a double-or-nothing attempt counting
     * as one step harder. Everything is read off the rower's own profile: EASY asks for their typical
     * figure, EPIC for their 90th percentile and a little beyond.
     */
    private void computeTargets() {
        int d = doubling ? Math.min(DIFF_NAMES.length - 1, diff + 1) : diff;
        // Already at EPIC with a double on: nothing harder in the table, so push past it.
        boolean extra = doubling && diff >= DIFF_NAMES.length - 1;
        double speed = profile.typicalSpeed();
        // The boss tightens the band as well as raising the percentage - it did before the difficulty
        // table landed (0.060 -> 0.045), and 0.75 restores that exactly at STANDARD.
        steadyBand = STEADY_BAND[d] * (boss ? 0.75 : 1.0) * (extra ? 0.9 : 1.0);
        ladderBand = LADDER_BAND[d] * (extra ? 0.85 : 1.0);
        switch (challenge) {
            case DISTANCE_4MIN:
                target = speed * 240 * (D4_SPEED[d] + (boss ? 0.03 : 0)) * (extra ? 1.04 : 1.0);
                break;
            case TRIAL_500:
                target = 500 / speed * (T5_TIME[d] - (boss ? 0.03 : 0)) * (extra ? 0.96 : 1.0);
                break;
            case POWER_10: {
                double f = P10_FRACTION[d] + (boss ? 0.08 : 0) + (extra ? 0.06 : 0);
                // wattsAt() walks the rower's low/typical/high band; past 1 keeps climbing off p90.
                target = f <= 1 ? profile.wattsAt(f) : profile.highWatts() * (1 + (f - 1) * 0.6);
                break;
            }
            case RATE_LADDER: {
                int base = (int) Math.round(profile.typicalRate());
                int[] offsets = LADDER_OFFSETS[d];
                rungs = Math.min(ladderRates.length, offsets.length);
                for (int i = 0; i < rungs; i++) {
                    ladderRates[i] = Math.max(16, base + offsets[i] + (boss ? 1 : 0));
                }
                target = Math.min(92, LADDER_PCT[d] + (boss ? 8 : 0) + (extra ? 5 : 0));
                break;
            }
            case NEG_SPLIT:
                // Seconds the second 250 m must beat the first by, with the whole 500 m inside a cap
                // so a crawl through the first half cannot buy the win.
                target = NEG_SECONDS[d] + (boss ? 2.0 : 0) + (extra ? 1.5 : 0);
                negCap = 500 / speed * (NEG_CAP[d] - (boss ? 0.04 : 0)) * (extra ? 0.97 : 1.0);
                break;
            default:
                target = Math.min(94, STEADY_PCT[d] + (boss ? 6 : 0) + (extra ? 4 : 0));
                break;
        }
    }

    /* ---------- points ---------- */

    private void readPoints() {
        points = Math.max(0f, bests.get("daily.points", 0f));
        todayPoints = 0;
        doubleUsed = false;
        String s = bests.getString("daily.day");
        if (s != null) {
            String[] p = s.split(":");
            try {
                if (p.length >= 3 && Long.parseLong(p[0]) == today) {
                    todayPoints = Math.max(0, Integer.parseInt(p[1]));
                    doubleUsed = "1".equals(p[2]);
                }
            } catch (NumberFormatException ignored) {
                todayPoints = 0;
            }
        }
    }

    private void writeToday() {
        bests.putString("daily.day", today + ":" + todayPoints + ":" + (doubleUsed ? 1 : 0));
        bests.putFloat("daily.points", points);
    }

    /** Adds points to the lifetime pot and floats the figure up beside the counter. */
    private void addPoints(int amount) {
        if (amount == 0) {
            return;
        }
        points = Math.max(0f, points + amount);
        floatPoints = amount;
        floatAt = sessionSeconds;
        bests.putFloat("daily.points", points);
    }

    /**
     * A lost double has forfeited the day's pot. The day is banked with the double spent and nothing
     * left on it, which can only be the losing side of the wager: a win always pays at least
     * {@code TIER_POINTS[BRONZE] * DIFF_REWARD[0]} = 7 points.
     */
    private boolean forfeited() {
        return doubleUsed && todayPoints == 0 && days.contains(today);
    }

    /** The day pays for its best result: a better medal tops up what today already paid. */
    private void payForWin(int tier) {
        // Without this, "or nothing" costs nothing: the rower loses the stake, rows the challenge
        // again and is paid the day's points a second time, so the wager has no downside.
        if (forfeited()) {
            return;
        }
        int worth = (int) Math.round(TIER_POINTS[Math.max(0, Math.min(TIER_POINTS.length - 1, tier))]
                * DIFF_REWARD[diff]);
        if (worth <= todayPoints) {
            return;
        }
        int delta = worth - todayPoints;
        todayPoints = worth;
        addPoints(delta);
        writeToday();
    }

    /* ---------- weekly rewards ---------- */

    /** The Monday that starts {@code day}'s week. Day 0 was a Thursday. */
    private static long weekStartOf(long day) {
        return day - ((day + 3) % 7);
    }

    private void readWeek() {
        weekStart = weekStartOf(today);
        weekMask = 0;
        String s = bests.getString("daily.week");
        if (s != null) {
            int colon = s.indexOf(':');
            if (colon > 0) {
                try {
                    if (Long.parseLong(s.substring(0, colon)) == weekStart) {
                        weekMask = Integer.parseInt(s.substring(colon + 1));
                    }
                } catch (NumberFormatException ignored) {
                    weekMask = 0;
                }
            }
        }
    }

    /**
     * Recounts the week and, when {@code award} is set, pays any milestone crossed. The mask is
     * persisted per week start, so last week's stars cannot be claimed again.
     */
    private void refreshWeek(boolean award) {
        weekWon = 0;
        for (int i = 0; i < 7; i++) {
            weekDone[i] = days.contains(weekStart + i);
            if (weekDone[i]) {
                weekWon++;
            }
        }
        bests.recordHighest("daily.week.best", weekWon);
        if (!award) {
            return;
        }
        for (int i = 0; i < WEEK_STEPS.length; i++) {
            int bit = 1 << i;
            if (weekWon < WEEK_STEPS[i] || (weekMask & bit) != 0) {
                continue;
            }
            weekMask |= bit;
            bests.putString("daily.week", weekStart + ":" + weekMask);
            addPoints(WEEK_POINTS[i]);
            weekFlash = i;
            weekFlashAt = sessionSeconds;
            // Five days in a week is the second way to earn a saver, alongside beating the Kraken.
            boolean grantToken = WEEK_STEPS[i] >= 5 && !token;
            if (grantToken) {
                token = true;
                bests.putString("daily.token", "1");
            }
            queueBanner((WEEK_STEPS[i] >= 7 ? "PERFECT WEEK  ·  seven days"
                            : WEEK_STEPS[i] + " DAYS THIS WEEK")
                            + "  ·  +" + WEEK_POINTS[i] + " pts"
                            + (grantToken ? " and a streak saver" : ""),
                    WEEK_STEPS[i] >= 7 ? 0xFFF5C518 : grantToken ? BLUE : ACCENT);
        }
    }

    /* ---------- the monthly badge ---------- */

    private void readBadges() {
        badges.clear();
        String s = bests.getString("daily.badges");
        if (s == null) {
            return;
        }
        for (String part : s.split(",")) {
            int colon = part.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                // Clamped to 0..3: the tier indexes MONTH_BADGE_NAMES, so anything else thrown at
                // the preference by a restore or a hand edit would be an index out of bounds.
                int tier = Math.max(0, Math.min(MONTH_SHARE.length,
                        Integer.parseInt(part.substring(colon + 1))));
                badges.put(Integer.parseInt(part.substring(0, colon)), tier);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
    }

    private void writeBadges() {
        while (badges.size() > 60) {
            badges.pollFirstEntry();
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Integer, Integer> e : badges.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        bests.putString("daily.badges", sb.toString());
    }

    /** Days needed in a month of {@code len} days for badge tier 1..3. */
    private static int badgeNeed(int len, int tier) {
        return (int) Math.ceil(len * MONTH_SHARE[tier - 1]);
    }

    private static int badgeTier(int won, int len) {
        for (int t = 3; t >= 1; t--) {
            if (won >= badgeNeed(len, t)) {
                return t;
            }
        }
        return 0;
    }

    /** yyyymm for a day number, the key the badges map is stored under. */
    private static int monthKey(long day) {
        java.util.Calendar cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US);
        cal.setTimeInMillis(day * 86400000L);
        return cal.get(java.util.Calendar.YEAR) * 100 + cal.get(java.util.Calendar.MONTH) + 1;
    }

    private int daysWonBetween(long from, long toInclusive) {
        int n = 0;
        for (Long d : days.subSet(from, true, toInclusive, true)) {
            if (d != null) {
                n++;
            }
        }
        return n;
    }

    /** Recounts this calendar month and pays the rosette when a new tier is reached. */
    private void refreshMonthBadge(boolean award) {
        java.util.Calendar cal = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US);
        cal.setTimeInMillis(today * 86400000L);
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        long first = cal.getTimeInMillis() / 86400000L;
        curMonthLen = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        curMonthWon = daysWonBetween(first, first + curMonthLen - 1);
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMMM", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        curMonthName = f.format(cal.getTime()).toUpperCase(java.util.Locale.US);
        int tier = badgeTier(curMonthWon, curMonthLen);
        int key = monthKey(today);
        Integer held = badges.get(key);
        int heldTier = held != null ? held : 0;
        if (tier > heldTier) {
            badges.put(key, tier);
            writeBadges();
            if (award) {
                addPoints(MONTH_POINTS[tier - 1]);
                badgeFlashAt = sessionSeconds;
                queueBanner(MONTH_BADGE_NAMES[tier - 1] + "  ·  " + curMonthWon + " days in "
                        + curMonthName + "  ·  +" + MONTH_POINTS[tier - 1] + " pts", 0xFFF5C518);
            }
            heldTier = tier;
        }
        curMonthTier = Math.max(tier, heldTier);
        // Against the tier actually shown, not the one just counted: a held badge that outranks the
        // count would otherwise be captioned "N more for GOLD" with N measured toward silver.
        curMonthNeed = curMonthTier >= MONTH_SHARE.length ? 0
                : Math.max(0, badgeNeed(curMonthLen, curMonthTier + 1) - curMonthWon);
    }

    /* ---------- tomorrow ---------- */

    private void refreshTomorrow() {
        long t = today + 1;
        tomorrowChallenge = challengeFor(t);
        tomorrowBoss = bossFor(t);
        tomorrowLine = previewLine(tomorrowChallenge, tomorrowBoss);
    }

    /**
     * One line describing a challenge at the chosen difficulty, without touching the live target
     * fields - the preview must never disturb the challenge being rowed.
     */
    private String previewLine(Challenge ch, boolean bossDay) {
        int d = diff;
        double speed = profile.typicalSpeed();
        switch (ch) {
            case DISTANCE_4MIN:
                return Math.round(speed * 240 * (D4_SPEED[d] + (bossDay ? 0.03 : 0))) + " m in 4 minutes";
            case TRIAL_500:
                return "500 m under " + clock(500 / speed * (T5_TIME[d] - (bossDay ? 0.03 : 0)));
            case POWER_10: {
                double f = P10_FRACTION[d] + (bossDay ? 0.08 : 0);
                double w = f <= 1 ? profile.wattsAt(f) : profile.highWatts() * (1 + (f - 1) * 0.6);
                return "ten strokes averaging " + Math.round(w) + " W";
            }
            case RATE_LADDER: {
                int base = (int) Math.round(profile.typicalRate()) + (bossDay ? 1 : 0);
                int[] offsets = LADDER_OFFSETS[d];
                return offsets.length + " rungs, " + Math.max(16, base + offsets[0]) + " to "
                        + Math.max(16, base + offsets[offsets.length - 1]) + " spm";
            }
            case NEG_SPLIT:
                return "2nd 250 m faster by "
                        + String.format(java.util.Locale.US, "%.1f",
                        NEG_SECONDS[d] + (bossDay ? 2.0 : 0)) + " s";
            default:
                return Math.round(Math.min(94, STEADY_PCT[d] + (bossDay ? 6 : 0)))
                        + "% of 3 minutes at " + pace(speed) + " /500";
        }
    }

    /* ---------- banners ---------- */

    private void queueBanner(String text, int color) {
        bannerQueue.addLast(text);
        bannerTints.addLast(color);
    }

    /** Shows the next queued reward line once the current one has had its five seconds. */
    private void stepBanner() {
        if (bannerQueue.isEmpty() || (banner.length() > 0 && sessionSeconds - bannerAt < 5)) {
            return;
        }
        banner = bannerQueue.pollFirst();
        Integer tint = bannerTints.pollFirst();
        bannerColor = tint != null ? tint : BLUE;
        bannerAt = sessionSeconds;
    }

    /**
     * A held saver covers a single missed day: yesterday empty, the day before it done. It is spent
     * on opening Daily Row, so the rower sees the save happen rather than finding it later.
     */
    private void spendTokenIfNeeded() {
        if (!token || counts(today - 1) || !counts(today - 2)) {
            return;
        }
        saved.add(today - 1);
        writeDays(saved, "daily.saved");
        token = false;
        bests.putString("daily.token", "0");
        queueBanner("STREAK SAVED  ·  your saver covered yesterday", BLUE);
    }

    /** Recomputes the month grid; called on entry and when the arrows change month, never per frame. */
    private void buildMonth() {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US);
        cal.setTimeInMillis(today * 86400000L);
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        cal.add(java.util.Calendar.MONTH, monthOffset);
        monthFirst = cal.getTimeInMillis() / 86400000L;
        monthLen = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        // Monday-first, as the weekly goal is: Sunday, the boss day, is the last column.
        monthLead = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        monthTitle = f.format(cal.getTime()).toUpperCase(java.util.Locale.US);
        for (int i = 0; i < monthTier.length; i++) {
            long day = monthFirst + i;
            Integer t = medals.get(day);
            monthTier[i] = i >= monthLen ? 0 : t != null ? t : days.contains(day) ? BRONZE : saved.contains(day) ? -1 : 0;
        }
        // The badge for the month on screen, which may be an earlier one the arrows stepped back to.
        gridMonthWon = daysWonBetween(monthFirst, monthFirst + monthLen - 1);
        Integer held = badges.get(monthKey(monthFirst));
        gridMonthTier = Math.max(badgeTier(gridMonthWon, monthLen), held != null ? held : 0);
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f && !showCard) {
            phase = Phase.ACTIVE;
            startSeconds = sessionSeconds;
            startMeters = sessionMeters;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastSeen) {
            lastSeen = stroke;
            if (phase == Phase.ACTIVE && challenge == Challenge.POWER_10) {
                strokes++;
                lastStrokeW = !Double.isNaN(stroke.averagePowerW) ? stroke.averagePowerW : s.watts;
                powerSum += lastStrokeW;
                if (boss && lastStrokeW >= target) {
                    flinch = 1f;
                    hitTimer = 0;
                }
                if (strokes >= 10) {
                    finish(powerSum / strokes);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(e);
        }
        float x = e.getX();
        float y = e.getY();
        if (showCard) {
            showCard = false;
            return true;
        }
        if (monthChip.contains(x, y)) {
            monthView = !monthView;
            monthOffset = 0;
            buildMonth();
            return true;
        }
        if (monthView && prevArrow.contains(x, y)) {
            monthOffset = Math.max(-12, monthOffset - 1);
            buildMonth();
            return true;
        }
        if (monthView && nextArrow.contains(x, y) && monthOffset < 0) {
            monthOffset++;
            buildMonth();
            return true;
        }
        if (phase == Phase.READY) {
            for (int i = 0; i < diffChips.length; i++) {
                if (diffChips[i].contains(x, y)) {
                    setDifficulty(i);
                    return true;
                }
            }
        }
        if (phase == Phase.DONE && cardChip.contains(x, y)) {
            showCard = true;
            return true;
        }
        if (phase == Phase.DONE && doubleAvailable() && doubleChip.contains(x, y)) {
            // The stake is today's points; the day itself is already banked and stays banked.
            pendingDouble = true;
            start();
            return true;
        }
        if (phase == Phase.DONE) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void finish(double value) {
        phase = Phase.DONE;
        result = value;
        doneAt = sessionSeconds;
        if (challenge == Challenge.TRIAL_500) {
            success = value <= target;
        } else if (challenge == Challenge.NEG_SPLIT) {
            success = value >= target && negTotal <= negCap;
        } else {
            success = value >= target;
        }
        if (boss) {
            bossHp = success ? 0f : Math.max(0.05f, 1f - (float) Math.max(0, Math.min(0.99, scoreRatio())));
        }
        String key = "daily.best." + challenge.name().toLowerCase(java.util.Locale.US);
        if (challenge == Challenge.TRIAL_500) {
            bests.recordLowest(key, (float) value);
        } else {
            bests.recordHighest(key, (float) value);
        }
        if (doubling) {
            resolveDouble();
            return;
        }
        if (!success) {
            return;
        }
        boolean firstWin = !days.contains(today);
        if (firstWin) {
            days.add(today);
            saved.remove(today);
            writeDays(days, "daily.days");
            bests.recordHighest("daily.streak", streak());
        }
        int tier = medalTier();
        Integer old = medals.get(today);
        if (old == null || tier > old) {
            if (tier == BOSS_MEDAL && (old == null || old != BOSS_MEDAL)) {
                bests.putFloat("daily.bosses", bests.get("daily.bosses", 0f) + 1f);
            }
            medals.put(today, tier);
            writeMedals();
        }
        todayTier = Math.max(todayTier, tier);
        if (firstWin && !token && (boss || streak() % 7 == 0)) {
            token = true;
            bests.putString("daily.token", "1");
            queueBanner(boss ? "+1 STREAK SAVER  ·  the Kraken is beaten"
                    : "+1 STREAK SAVER  ·  " + streak() + " days in a row", BLUE);
        }
        payForWin(todayTier);
        refreshWeek(true);
        refreshMonthBadge(true);
        buildMonth();
    }

    /**
     * A double-or-nothing attempt settling. The day and the streak are never at risk here - only the
     * points today has already paid, and the medal, which steps up on a win.
     */
    private void resolveDouble() {
        doubleUsed = true;
        if (success) {
            doubleResult = 1;
            int gain = todayPoints;
            todayPoints += gain;
            addPoints(gain);
            int tier = boss ? BOSS_MEDAL : Math.min(GOLD, Math.max(BRONZE, todayTier) + 1);
            Integer old = medals.get(today);
            if (old == null || tier > old) {
                medals.put(today, tier);
                writeMedals();
            }
            todayTier = Math.max(todayTier, tier);
            queueBanner("DOUBLED  ·  today is worth " + todayPoints + " pts", 0xFFF5C518);
        } else {
            doubleResult = -1;
            int lost = todayPoints;
            todayPoints = 0;
            addPoints(-lost);
            queueBanner("NOTHING  ·  " + lost + " pts gone, the day still counts", BAD);
        }
        writeToday();
        buildMonth();
    }

    private int medalTier() {
        if (boss) {
            return BOSS_MEDAL;
        }
        // Harder difficulties floor the medal: a HARD win is never worse than silver.
        int floor = diff >= 3 ? GOLD : diff == 2 ? SILVER : BRONZE;
        if (challenge == Challenge.NEG_SPLIT) {
            double margin = result - target;
            return Math.max(floor, margin >= 4 ? GOLD : margin >= 2 ? SILVER : BRONZE);
        }
        double ratio = scoreRatio();
        return Math.max(floor, ratio >= 1.10 ? GOLD : ratio >= 1.05 ? SILVER : BRONZE);
    }

    /** How far the result went toward the target: 1 is exactly on it. */
    private double scoreRatio() {
        switch (challenge) {
            case TRIAL_500:
                return target / Math.max(1e-6, result);
            case NEG_SPLIT:
                // Scored as seconds gained against a 4 s scale, so a small miss still shows progress.
                return Math.max(0, (result + 4) / (target + 4));
            default:
                return result / Math.max(1e-6, target);
        }
    }

    /** Consecutive completed (or saved) days ending today, or yesterday if today is not done yet. */
    private int streak() {
        long d = counts(today) ? today : today - 1;
        int n = 0;
        while (counts(d)) {
            n++;
            d--;
        }
        return n;
    }

    private double rateNow() {
        if (status == null) {
            return 0;
        }
        return status.strokeRatePrecise > 0 ? status.strokeRatePrecise : status.strokeRate;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        drawBackdrop(c, w, h);
        // Slow sparkles drifting up the background; every sixth one is a four-point twinkle.
        int sparkle = boss ? 0xB06CFF : 0x35D0BA;
        for (int i = 0; i < 36; i++) {
            float sx = ((i * 0.137f + 0.05f) % 1f) * w;
            float sy = h - (float) (((sessionSeconds * dp(12f + i % 5 * 4f)) + i * dp(97f)) % (h + dp(40f)));
            float tw = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 2 + i);
            paint.setColor(((int) (30 + 60 * tw) << 24) | sparkle);
            c.drawCircle(sx, sy, dp(1.5f) + tw * dp(1.5f), paint);
            if (i % 6 == 0) {
                float arm = dp(4f) + tw * dp(5f);
                paint.setStrokeWidth(dp(1.2f));
                paint.setColor(((int) (20 + 70 * tw) << 24) | sparkle);
                c.drawLine(sx - arm, sy, sx + arm, sy, paint);
                c.drawLine(sx, sy - arm, sx, sy + arm, paint);
            }
        }
        fx.step(dt, dp(260f));
        double elapsed = phase == Phase.ACTIVE ? sessionSeconds - startSeconds : 0;
        double metres = phase == Phase.ACTIVE ? sessionMeters - startMeters : 0;
        float progress = 0f;
        float bossProgress = 0f;
        String live = "";
        paceText = "";
        if (phase == Phase.ACTIVE) {
            switch (challenge) {
                case DISTANCE_4MIN: {
                    progress = (float) Math.min(1, elapsed / 240);
                    bossProgress = (float) (metres / target);
                    double ahead = metres - target * elapsed / 240;
                    onTrack = ahead >= 0;
                    paceText = (onTrack ? "AHEAD OF TARGET  +" : "BEHIND TARGET  ") + Math.round(ahead) + " m";
                    live = Math.round(metres) + " m  ·  " + clock(Math.max(0, 240 - elapsed)) + " left";
                    if (elapsed >= 240) {
                        finish(metres);
                    }
                    break;
                }
                case TRIAL_500: {
                    progress = (float) Math.min(1, metres / 500);
                    bossProgress = progress;
                    double ahead = metres - 500 * elapsed / target;
                    onTrack = ahead >= 0;
                    paceText = (onTrack ? "AHEAD OF TARGET  +" : "BEHIND TARGET  ") + Math.round(ahead) + " m";
                    live = clock(elapsed) + "  ·  " + Math.round(metres) + " of 500 m";
                    if (metres >= 500) {
                        finish(elapsed);
                    }
                    break;
                }
                case POWER_10:
                    progress = strokes / 10f;
                    bossProgress = (float) (progress * Math.min(1, strokes > 0 ? powerSum / strokes / target : 0));
                    onTrack = strokes > 0 && lastStrokeW >= target;
                    paceText = strokes == 0 ? "PULL HARD  ·  " + Math.round(target) + " W A STROKE"
                            : "LAST STROKE " + Math.round(lastStrokeW) + " W" + (onTrack ? "  ·  ON TARGET" : "  ·  PULL HARDER");
                    live = strokes + " of 10 strokes  ·  " + (strokes > 0 ? Math.round(powerSum / strokes) : 0) + " W average";
                    break;
                case RATE_LADDER: {
                    double total = rungs * RUNG_SECONDS;
                    int rung = (int) Math.min(rungs - 1, elapsed / RUNG_SECONDS);
                    if (rung != lastRung) {
                        lastRung = rung;
                        rungFlashAt = sessionSeconds;
                    }
                    double inRung = elapsed - rung * RUNG_SECONDS;
                    double rate = rateNow();
                    int want = ladderRates[rung];
                    boolean inBand = Math.abs(rate - want) <= ladderBand;
                    if (inRung >= RUNG_GRACE) {
                        ladderCounted += dt;
                        if (inBand) {
                            ladderIn += dt;
                        }
                    }
                    onTrack = inBand;
                    paceText = inBand ? "IN BAND  ·  " + String.format(java.util.Locale.US, "%.1f", rate) + " spm"
                            : (rate < want ? "TOO SLOW  ·  " : "TOO FAST  ·  ")
                            + String.format(java.util.Locale.US, "%.1f", rate) + " spm, want " + want;
                    progress = (float) Math.min(1, elapsed / total);
                    bossProgress = (float) (ladderIn / (target / 100.0 * Math.max(1, total - rungs * RUNG_GRACE)));
                    live = "RUNG " + (rung + 1) + " of " + rungs + "  ·  hold " + want + " spm  ·  "
                            + clock(Math.max(0, RUNG_SECONDS - inRung)) + "  ·  "
                            + Math.round(100 * ladderIn / Math.max(1, ladderCounted)) + "% in band";
                    if (elapsed >= total) {
                        finish(100 * ladderIn / Math.max(1, ladderCounted));
                    }
                    break;
                }
                case NEG_SPLIT: {
                    progress = (float) Math.min(1, metres / 500);
                    bossProgress = progress;
                    while (nextMark <= 25 && metres >= nextMark * 10) {
                        firstHalfAt[nextMark] = elapsed;
                        nextMark++;
                    }
                    if (halfTime < 0 && metres >= 250) {
                        halfTime = firstHalfAt[25];
                    }
                    if (halfTime < 0) {
                        double pace500 = elapsed * 500 / Math.max(1, metres);
                        onTrack = metres < 20 || pace500 <= negCap;
                        paceText = "FIRST HALF  ·  bank a pace you can beat  ·  " + clock(elapsed);
                        live = Math.round(metres) + " of 250 m  ·  first half";
                    } else {
                        double m2 = Math.min(250, metres - 250);
                        double e2 = elapsed - halfTime;
                        double ghost = ghostTimeAt(m2);
                        double ahead = ghost - e2;
                        onTrack = ahead >= 0;
                        paceText = (onTrack ? "BEATING YOUR FIRST HALF  +" : "BEHIND YOUR FIRST HALF  ")
                                + String.format(java.util.Locale.US, "%.1f", ahead) + " s";
                        live = "first half " + clock(halfTime) + "  ·  " + Math.round(metres) + " of 500 m";
                    }
                    if (metres >= 500) {
                        negTotal = elapsed;
                        finish(2 * halfTime - elapsed);
                    }
                    break;
                }
                default: {
                    float speed = boat.value();
                    double typical = profile.typicalSpeed();
                    boolean inBand = Math.abs(speed - typical) <= typical * steadyBand;
                    if (inBand) {
                        steadySeconds += dt;
                    }
                    onTrack = inBand;
                    paceText = inBand ? "IN THE BAND" : speed < typical ? "TOO SLOW  ·  lift it" : "TOO FAST  ·  ease off";
                    progress = (float) Math.min(1, elapsed / 180);
                    bossProgress = (float) (steadySeconds / (target / 100.0 * 180));
                    live = Math.round(100 * steadySeconds / Math.max(1, elapsed)) + "% steady  ·  hold "
                            + pace(typical) + " /500  ·  " + clock(Math.max(0, 180 - elapsed)) + " left";
                    if (elapsed >= 180) {
                        finish(100 * steadySeconds / 180);
                    }
                    break;
                }
            }
        }
        if (boss && phase == Phase.ACTIVE) {
            bossHp = 1f - Math.max(0f, Math.min(1f, bossProgress));
        } else if (boss && phase == Phase.READY) {
            bossHp = 1f;
        }

        stepBanner();
        float cx = w / 2f;
        String targetHead = "TARGET  " + targetLine();
        {
            // A lit plaque behind the day's name, sized to its text and kept clear of the reward
            // HUD on the left and the Kraken on the right.
            float tw = Math.max(textWidth(challenge.title, 30f) * 1.1f, textWidth(targetHead, 13f) * 1.3f);
            float pw = Math.min(w * 0.5f, Math.max(dp(420f), tw + dp(80f)));
            drawPlaque(c, cx - pw / 2, dp(16f), cx + pw / 2, dp(132f), boss ? BOSS_COLOR : ACCENT);
        }
        boolean atStake = doubling && doubleResult == 0;
        label(c, atStake ? "DOUBLE OR NOTHING  ·  " + todayPoints + " PTS AT STAKE"
                        : doubleResult > 0 ? "DOUBLE WON  ·  " + todayPoints + " PTS TODAY"
                        : doubleResult < 0 ? "DOUBLE LOST  ·  the day still counts"
                        : boss ? "SUNDAY BOSS  ·  THE KRAKEN" : "TODAY'S DAILY ROW",
                cx, dp(40f), 11f, atStake || doubleResult > 0 ? 0xFFF5C518
                        : doubleResult < 0 ? BAD : boss ? BOSS_COLOR : FAINT, Paint.Align.CENTER);
        bold(c, challenge.title, cx, dp(78f), 30f, boss ? BOSS_COLOR : ACCENT, Paint.Align.CENTER);
        label(c, targetHead, cx, dp(102f), 13f, TEXT, Paint.Align.CENTER);
        // The difficulty the target was set at, and what a win pays at it.
        label(c, DIFF_NAMES[diff] + (doubling ? " +1 STEP" : "") + "  ·  reward x"
                        + String.format(java.util.Locale.US, "%.1f", DIFF_REWARD[diff] * (doubling ? 2 : 1)),
                cx, dp(120f), 10f, diffColor(diff), Paint.Align.CENTER);
        drawRewards(c, w, dt);

        float barY = h * 0.34f;
        // A progress ring around a medal, left of the live readout.
        if (phase == Phase.DONE) {
            // A win fills the ring; a miss shows how close it came - a miss must not look like a win.
            progress = success ? 1f : (float) Math.max(0, Math.min(0.99, scoreRatio()));
        }
        ringShown += (progress - ringShown) * Math.min(1f, dt * 4f);
        float rcx = w * 0.17f;
        float rcy = barY + dp(20f);
        float rr = Math.min(dp(70f), h * 0.12f);
        boolean won = phase == Phase.DONE && success;
        boolean lost = phase == Phase.DONE && !success;
        // A soft pool of light under the ring, so it sits on the stage rather than on black.
        Fx.glow(c, rcx, rcy, rr * 2.4f, boss ? 0x20B06CFF : 0x2035D0BA);
        // The halo follows the outcome too: a teal glow around a dulled medal read as a win.
        int haloTint = (lost ? WARN : ACCENT) & 0x00FFFFFF;
        Fx.glow(c, rcx, rcy, rr * (1.2f + 0.3f * ringShown),
                won ? 0x88F5C518 : ((int) (40 + 60 * ringShown) << 24) | haloTint);
        // The track is a groove cut into a metal bezel: a dark bed, a top wall in shadow and a
        // bottom wall catching the light (light comes from the top left everywhere on this screen).
        c.save();
        c.translate(rcx, rcy);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(16f));
        paint.setColor(0xFF03070C);
        c.drawCircle(0, 0, rr, paint);
        paint.setStrokeWidth(dp(12f));
        paint.setColor(0xFF172231);
        c.drawCircle(0, 0, rr, paint);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0x88000000);
        c.drawArc(-rr - dp(5f), -rr - dp(5f), rr + dp(5f), rr + dp(5f), 160f, 200f, false, paint);
        c.drawArc(-rr + dp(5f), -rr + dp(5f), rr - dp(5f), rr - dp(5f), -20f, 200f, false, paint);
        paint.setColor(0x24FFFFFF);
        c.drawArc(-rr - dp(5f), -rr - dp(5f), rr + dp(5f), rr + dp(5f), -20f, 200f, false, paint);
        c.drawArc(-rr + dp(5f), -rr + dp(5f), rr - dp(5f), rr - dp(5f), 160f, 200f, false, paint);
        // Dial ticks between the groove and the medal: one per 5%, every quarter longer.
        for (int k = 0; k < 20; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 10;
            float ca = (float) Math.cos(a);
            float sa = (float) Math.sin(a);
            boolean major = k % 5 == 0;
            boolean passed = k / 20f <= ringShown;
            paint.setStrokeWidth(major ? dp(2f) : dp(1.2f));
            paint.setColor(passed ? (lost ? 0x99F0B132 : 0x9935D0BA) : 0x40FFFFFF);
            float r0 = rr - dp(10f);
            float r1 = rr - (major ? dp(17f) : dp(14f));
            c.drawLine(ca * r0, sa * r0, ca * r1, sa * r1, paint);
        }
        // The bezel: a thin bright-to-dark ring just outside the groove.
        float bez = rr + dp(8.5f);
        if (bezelShader == null || bezelShaderR != bez) {
            bezelShaderR = bez;
            bezelShader = new android.graphics.LinearGradient(-bez, -bez, bez, bez,
                    new int[] {0xFF6F8098, 0xFF2A3648, 0xFF0A1018}, new float[] {0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(dp(2f));
        gp.setColor(0xFFFFFFFF);
        gp.setShader(bezelShader);
        c.drawCircle(0, 0, bez, gp);
        gp.setShader(null);
        c.restore();
        if (ringShown > 0.003f) {
            // The fill is a comet: dark at its tail, brightest at the leading edge. On a win the
            // full ring keeps turning, so the bright head laps it.
            float sweep = 360f * Math.min(1f, ringShown);
            float tipA = -90f + sweep;
            if (won && ringShown > 0.995f) {
                tipA = -90f + (float) ((sessionSeconds * 120) % 360);
            }
            float rot = tipA - 0.96f * 360f;
            c.save();
            c.translate(rcx, rcy);
            c.rotate(rot);
            gp.setStyle(Paint.Style.STROKE);
            gp.setStrokeWidth(dp(12f));
            gp.setStrokeCap(Paint.Cap.ROUND);
            gp.setColor(0xFFFFFFFF);
            gp.setShader(lost ? ringWarn : ringAccent);
            c.drawArc(-rr, -rr, rr, rr, -90f - rot, sweep, false, gp);
            gp.setShader(null);
            gp.setStrokeCap(Paint.Cap.BUTT);
            // A thin tube highlight along the outer, lit side of the fill.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(0x38FFFFFF);
            float hr = rr + dp(3f);
            c.drawArc(-hr, -hr, hr, hr, -90f - rot, Math.max(0f, sweep - 4f), false, paint);
            c.restore();
            paint.setStyle(Paint.Style.FILL);
            double ta = Math.toRadians(tipA);
            float tx = rcx + (float) Math.cos(ta) * rr;
            float ty = rcy + (float) Math.sin(ta) * rr;
            Fx.glow(c, tx, ty, dp(30f), lost ? 0xA0F0B132 : 0xA035D0BA);
            paint.setColor(0xE6FFFFFF);
            c.drawCircle(tx, ty, dp(4.5f), paint);
            // Sparks shed off the head while it is moving forward.
            if (phase == Phase.ACTIVE && onTrack && Math.random() < dt * 10) {
                float vx = (float) Math.cos(ta) * dp(50f);
                float vy = (float) Math.sin(ta) * dp(50f);
                fx.spawn(tx, ty, vx + (float) (Math.random() - 0.5) * dp(40f),
                        vy + (float) (Math.random() - 0.5) * dp(40f), 0.5f, dp(2f), 0xFFB4FFF3, false);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
        // The medal: struck metal. Steel until the ring fills it with gold; tarnished on a miss.
        float mr = rr * 0.55f;
        paint.setColor(0x70000000);
        c.drawCircle(rcx + mr * 0.07f, rcy + mr * 0.12f, mr, paint);
        if (won) {
            drawMetal(c, rcx, rcy, mr, GOLD, 255, 1f, 0f, false);
        } else if (lost) {
            drawMetal(c, rcx, rcy, mr, PAL_TARNISHED, 255, 0f, 0f, false);
        } else {
            drawMetal(c, rcx, rcy, mr, PAL_STEEL, 255, 0.25f, 0f, false);
            if (ringShown > 0.01f) {
                drawMetal(c, rcx, rcy, mr, GOLD, (int) (255 * Math.min(1f, ringShown)),
                        0.3f + 0.7f * ringShown, 0f, false);
            }
        }
        bold(c, Math.round(ringShown * 100) + "%", rcx, rcy + rr + dp(26f), 14f, TEXT, Paint.Align.CENTER);
        // The bar for the live readout: a sunken track and a glossy fill with a lit leading edge.
        float barL = w * 0.30f;
        float barR = w * 0.86f;
        float bh = dp(18f);
        paint.setColor(0xFF03070C);
        c.drawRoundRect(barL - dp(2f), barY - dp(2f), barR + dp(2f), barY + bh + dp(2f), dp(11f), dp(11f), paint);
        paint.setColor(0xFF172231);
        c.drawRoundRect(barL, barY, barR, barY + bh, dp(9f), dp(9f), paint);
        paint.setColor(0x70000000);
        c.drawRect(barL + dp(9f), barY, barR - dp(9f), barY + dp(3f), paint);
        paint.setColor(0x1CFFFFFF);
        c.drawRect(barL + dp(9f), barY + bh - dp(1.5f), barR - dp(9f), barY + bh - dp(0.5f), paint);
        if (barFill == null || barShaderH != bh) {
            barShaderH = bh;
            barFill = new android.graphics.LinearGradient(0, 0, 0, bh,
                    new int[] {0xFF9AF8EA, 0xFF35D0BA, 0xFF16786B}, new float[] {0f, 0.45f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
            barFillWarn = new android.graphics.LinearGradient(0, 0, 0, bh,
                    new int[] {0xFFFFE19A, 0xFFF0B132, 0xFF8A5E10}, new float[] {0f, 0.45f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        // Same rule as the ring: a miss must not be drawn in the colour of a win.
        float fillR = barL + (barR - barL) * progress;
        if (progress > 0f) {
            c.save();
            c.translate(0, barY);
            gp.setStyle(Paint.Style.FILL);
            gp.setColor(0xFFFFFFFF);
            gp.setShader(lost ? barFillWarn : barFill);
            c.drawRoundRect(barL, 0, fillR, bh, dp(9f), dp(9f), gp);
            gp.setShader(null);
            if (fillR - barL > dp(16f)) {
                paint.setColor(0x3CFFFFFF);
                c.drawRoundRect(barL + dp(5f), dp(2.5f), fillR - dp(5f), dp(7f), dp(3f), dp(3f), paint);
            }
            c.restore();
            if (progress < 0.999f && progress > 0.01f) {
                Fx.glow(c, fillR, barY + bh / 2, dp(28f), lost ? 0x90F0B132 : 0x9035D0BA);
                paint.setColor(0xCCFFFFFF);
                c.drawRoundRect(fillR - dp(3.5f), barY + dp(3f), fillR - dp(0.5f), barY + bh - dp(3f),
                        dp(1.5f), dp(1.5f), paint);
            }
        }
        if (progress > 0.02f) {
            float sheen = barL + (barR - barL) * progress * (float) ((sessionSeconds * 0.6) % 1.0);
            paint.setColor(0x55FFFFFF);
            c.drawRoundRect(sheen - dp(20f), barY + dp(3f), sheen + dp(20f), barY + dp(8f), dp(3f), dp(3f), paint);
        }
        drawBarMarks(c, barL, barR, barY, elapsed);
        if (boss) {
            drawBoss(c, w, h, dt);
        }
        if (won) {
            if (sessionSeconds - doneAt < 3 && Math.random() < 0.8) {
                fx.spawn((float) Math.random() * w, -dp(10f), (float) (Math.random() - 0.5) * dp(80f),
                        dp(30f), 3f, dp(3.5f), CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
            }
            // A rubber stamp that thumps down and settles.
            float t = (float) Math.min(1.0, (sessionSeconds - doneAt) * 4);
            float scale = 2.2f - 1.2f * t;
            int stamp = boss ? BOSS_COLOR & 0x00FFFFFF : 0xF5C518;
            c.save();
            c.rotate(-8f, w * 0.58f, barY - dp(40f));
            c.scale(scale, scale, w * 0.58f, barY - dp(40f));
            float sxc = w * 0.58f;
            int ta = (int) (255 * t);
            // Ink wash, then a heavy outer rule and a fine inner one, like a rubber stamp.
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (40 * t) << 24) | stamp);
            c.drawRoundRect(sxc - dp(90f), barY - dp(68f), sxc + dp(90f), barY - dp(14f), dp(8f), dp(8f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4f));
            paint.setColor((ta << 24) | stamp);
            c.drawRoundRect(sxc - dp(90f), barY - dp(68f), sxc + dp(90f), barY - dp(14f), dp(8f), dp(8f), paint);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(((int) (170 * t) << 24) | stamp);
            c.drawRoundRect(sxc - dp(83f), barY - dp(61f), sxc + dp(83f), barY - dp(21f), dp(5f), dp(5f), paint);
            paint.setStyle(Paint.Style.FILL);
            // A star at each end of the stamp, outside the frame so they never touch the words.
            paint.setColor((ta << 24) | stamp);
            for (int s = -1; s <= 1; s += 2) {
                star(sxc + s * dp(106f), barY - dp(41f), dp(9f), dp(4f));
                c.drawPath(shape, paint);
            }
            bold(c, boss ? "BOSS DOWN" : "DAY WON", w * 0.58f, barY - dp(30f), 26f, ((int) (255 * t) << 24) | stamp, Paint.Align.CENTER);
            c.restore();
        }
        fx.draw(c);
        String head = doubleResult > 0 ? "DOUBLED  ·  " : doubleResult < 0 ? "LOST THE DOUBLE  ·  "
                : success ? "DONE  ·  " : "NOT TODAY  ·  ";
        int headColor = doubleResult > 0 ? 0xFFF5C518 : doubleResult < 0 ? BAD : success ? ACCENT : WARN;
        String big = phase == Phase.READY ? "ROW TO START"
                : phase == Phase.DONE ? head + resultText() : live;
        bold(c, big, cx, barY + dp(58f), phase == Phase.DONE ? 26f : 20f,
                phase == Phase.DONE ? headColor : TEXT, Paint.Align.CENTER);
        cardChip.setEmpty();
        doubleChip.setEmpty();
        for (int i = 0; i < diffChips.length; i++) {
            diffChips[i].setEmpty();
        }
        if (phase == Phase.DONE) {
            boolean dbl = doubleAvailable();
            label(c, dbl ? "tap anywhere else to try again" : "tap to try again",
                    cx, barY + dp(80f), 10f, FAINT, Paint.Align.CENTER);
            if (dbl) {
                cardChip.set(cx - dp(210f), barY + dp(92f), cx - dp(50f), barY + dp(124f));
                doubleChip.set(cx - dp(34f), barY + dp(92f), cx + dp(210f), barY + dp(124f));
                // The stake pulses, because that is the decision in front of the rower right now.
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 4);
                Fx.glow(c, doubleChip.centerX(), doubleChip.centerY(), dp(120f),
                        ((int) (30 + 50 * pulse) << 24) | 0xF5C518);
                chip(c, doubleChip, "DOUBLE OR NOTHING", 0xFFF5C518);
                label(c, "stake today's " + todayPoints + " pts  ·  one step harder  ·  win to double them",
                        cx, barY + dp(142f), 10f, WARN, Paint.Align.CENTER);
            } else {
                cardChip.set(cx - dp(80f), barY + dp(92f), cx + dp(80f), barY + dp(124f));
                if (todayPoints > 0) {
                    label(c, "today is worth " + todayPoints + " pts"
                                    + (doubleUsed ? "  ·  double spent" : ""),
                            cx, barY + dp(142f), 10f, FAINT, Paint.Align.CENTER);
                } else if (forfeited()) {
                    // Say it, or a re-row that pays nothing reads as a bug rather than the wager.
                    label(c, "today's points went on the double  ·  the day still counts",
                            cx, barY + dp(142f), 10f, WARN, Paint.Align.CENTER);
                }
            }
            chip(c, cardChip, "RESULT CARD", success ? ACCENT : WARN);
        } else if (phase == Phase.READY) {
            drawDifficultyChips(c, cx, barY);
        } else if (phase == Phase.ACTIVE && paceText.length() > 0) {
            // The stake in the next ten seconds: are you on the target's pace right now?
            float pw = textWidth(paceText, 13f) + dp(28f);
            int col = onTrack ? ACCENT : BAD;
            paint.setColor((0x33 << 24) | (col & 0x00FFFFFF));
            c.drawRoundRect(cx - pw / 2, barY + dp(72f), cx + pw / 2, barY + dp(100f), dp(14f), dp(14f), paint);
            bold(c, paceText, cx, barY + dp(91f), 13f, col, Paint.Align.CENTER);
        }

        if (monthView) {
            drawMonth(c, w, h);
        } else {
            int cells = 28;
            float cell = Math.min(dp(40f), (w * 0.8f) / cells);
            float calL = cx - cell * cells / 2f;
            float calY = h * 0.66f;
            drawStrip(c, calL, calY, cell, cells, true);
            label(c, "LAST 28 DAYS", calL, calY - dp(8f), 9f, FAINT, Paint.Align.LEFT);
            label(c, "TODAY", calL + cells * cell, calY - dp(8f), 9f, FAINT, Paint.Align.RIGHT);
            // The flame stands on the streak line's baseline, beside the words.
            drawStreak(c, cx, calY + cell + dp(40f), w / 2f - dp(150f), calY + cell + dp(38f));
        }
        monthChip.set(w - dp(128f), h * 0.58f - dp(34f), w - dp(16f), h * 0.58f - dp(4f));
        chip(c, monthChip, monthView ? "28 DAYS" : "MONTH", DIM);

        if (sessionSeconds - bannerAt < 5 && banner.length() > 0) {
            float a = (float) Math.min(1, Math.min((sessionSeconds - bannerAt) * 4, (5 - (sessionSeconds - bannerAt)) * 2));
            float by = dp(138f) + dp(10f) * (1 - a);
            // Measured, not estimated from the character count, so the shield never sits on the text.
            float bw = textWidth(banner, 15f) + dp(72f);
            paint.setColor(((int) (220 * a) << 24) | 0x0E1A2A);
            c.drawRoundRect(cx - bw / 2, by, cx + bw / 2, by + dp(40f), dp(20f), dp(20f), paint);
            drawShield(c, cx - bw / 2 + dp(26f), by + dp(20f), dp(12f), ((int) (255 * a) << 24) | (bannerColor & 0x00FFFFFF));
            bold(c, banner, cx - bw / 2 + dp(48f), by + dp(26f), 15f, ((int) (255 * a) << 24) | (bannerColor & 0x00FFFFFF), Paint.Align.LEFT);
        }

        if (phase == Phase.DONE) {
            drawTomorrow(c, w, h, barY);
        }

        if (showCard) {
            drawCard(c, w, h);
        }
    }

    /** A double is offered once a day, after the day is banked and while there is a stake to risk. */
    private boolean doubleAvailable() {
        return !doubling && !doubleUsed && todayPoints > 0 && days.contains(today);
    }

    private static int diffColor(int d) {
        return d == 0 ? ACCENT : d == 1 ? BLUE : d == 2 ? WARN : BAD;
    }

    /** The difficulty picker, drawn on the ready screen only; the target follows the choice. */
    private void drawDifficultyChips(Canvas c, float cx, float barY) {
        label(c, "PICK YOUR DIFFICULTY  ·  harder targets pay more", cx, barY + dp(84f), 10f, FAINT,
                Paint.Align.CENTER);
        float cw = dp(112f);
        float gap = dp(8f);
        float total = cw * diffChips.length + gap * (diffChips.length - 1);
        float x = cx - total / 2;
        for (int i = 0; i < diffChips.length; i++) {
            diffChips[i].set(x, barY + dp(94f), x + cw, barY + dp(126f));
            boolean on = i == diff;
            int col = diffColor(i);
            if (on) {
                float pulse = 0.6f + 0.4f * (float) Math.sin(sessionSeconds * 3 + i);
                Fx.glow(c, diffChips[i].centerX(), diffChips[i].centerY(), dp(70f),
                        ((int) (40 + 40 * pulse) << 24) | (col & 0x00FFFFFF));
            }
            chip(c, diffChips[i], DIFF_NAMES[i], on ? col : DIM);
            label(c, "x" + String.format(java.util.Locale.US, "%.1f", DIFF_REWARD[i]),
                    diffChips[i].centerX(), barY + dp(142f), 10f, on ? col : FAINT, Paint.Align.CENTER);
            x += cw + gap;
        }
    }

    /**
     * The reward HUD in the top-left: the points pot, this week's seven days with their reward stars,
     * and the month's rosette. Scaled down on a short screen so it can never reach the progress ring.
     */
    private void drawRewards(Canvas c, float w, float dt) {
        float s = Math.min(1f, getHeight() / dp(900f));
        float x0 = dp(20f) * s;
        pointsShown += (points - pointsShown) * Math.min(1f, dt * 3f);
        if (Math.abs(points - pointsShown) < 0.5f) {
            pointsShown = points;
        }
        String pts = String.valueOf(Math.round(pointsShown));
        bold(c, pts, x0, dp(44f) * s, 24f * s, 0xFFF5C518, Paint.Align.LEFT);
        textPaint.setTextSize(dp(24f * s));
        float pw = textPaint.measureText(pts);
        label(c, "PTS", x0 + pw + dp(6f) * s, dp(44f) * s, 11f * s, FAINT, Paint.Align.LEFT);
        // The award floats up out of the counter, so a reward is seen as well as counted.
        double ago = sessionSeconds - floatAt;
        if (ago >= 0 && ago < 1.6 && floatPoints != 0) {
            float a = (float) Math.max(0, 1 - ago / 1.6);
            bold(c, (floatPoints > 0 ? "+" : "") + floatPoints, x0 + pw + dp(30f) * s,
                    dp(44f) * s - (float) ago * dp(26f) * s, 16f * s,
                    ((int) (255 * a) << 24) | (floatPoints > 0 ? 0xF5C518 : 0xF0655D), Paint.Align.LEFT);
        }

        // This week: seven cells, Monday first, with the reward stars under 3, 5 and 7.
        label(c, "THIS WEEK", x0, dp(70f) * s, 9f * s, FAINT, Paint.Align.LEFT);
        float cell = dp(34f) * s;
        float cy0 = dp(78f) * s;
        float ch0 = dp(24f) * s;
        int todayCol = (int) (today - weekStart);
        for (int i = 0; i < 7; i++) {
            float l = x0 + i * cell;
            paint.setStyle(Paint.Style.FILL);
            if (weekDone[i]) {
                // Same glossy tile as the calendar's won days.
                c.save();
                c.translate(l + dp(2f) * s, cy0);
                gp.setStyle(Paint.Style.FILL);
                gp.setColor(0xFFFFFFFF);
                gp.setShader(cellShader(0, ch0));
                c.drawRoundRect(0, 0, cell - dp(4f) * s, ch0, dp(4f) * s, dp(4f) * s, gp);
                gp.setShader(null);
                paint.setColor(0x66FFFFFF);
                c.drawRect(dp(4f) * s, dp(1f), cell - dp(8f) * s, dp(2f), paint);
                c.restore();
            } else {
                drawSunkCell(c, l + dp(2f) * s, cy0, l + cell - dp(2f) * s, cy0 + ch0,
                        i == todayCol ? 0xFF2A3648 : saved.contains(weekStart + i) ? 0xFF24345C : 0xFF141D2A);
            }
            label(c, WEEKDAYS[i], l + cell / 2, cy0 + ch0 * 0.68f, 10f * s,
                    weekDone[i] ? 0xFF08111C : i == 6 ? BOSS_COLOR : FAINT, Paint.Align.CENTER);
            if (i == todayCol && !weekDone[i]) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 3);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.5f) * s);
                paint.setColor(((int) (80 + 150 * pulse) << 24) | 0x35D0BA);
                c.drawRoundRect(l + dp(2f) * s, cy0, l + cell - dp(2f) * s, cy0 + ch0, dp(4f) * s, dp(4f) * s, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        for (int k = 0; k < WEEK_STEPS.length; k++) {
            float sx = x0 + (WEEK_STEPS[k] - 0.5f) * cell;
            float sy = cy0 + ch0 + dp(14f) * s;
            boolean claimed = (weekMask & (1 << k)) != 0;
            // Clamped at both ends: a stamp from a previous attempt must not grow the star, and
            // Fx.glow caches by rounded radius, so a runaway radius would churn the cache too.
            float grow = weekFlash == k
                    ? (float) Math.max(0, Math.min(1, 1 - (sessionSeconds - weekFlashAt) / 1.2)) : 0f;
            float r = dp(8f) * s * (1 + grow * 0.8f);
            if (claimed) {
                Fx.glow(c, sx, sy, r * 2.4f, ((int) (60 + 120 * grow) << 24) | 0xF5C518);
                // A star lighting up throws a little confetti, so the reward is seen landing.
                if (grow > 0.7f && Math.random() < 0.5) {
                    fx.spawn(sx, sy, (float) (Math.random() - 0.5) * dp(130f),
                            -dp(40f) - (float) Math.random() * dp(70f), 1.2f, dp(2.5f),
                            CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
                }
            }
            paint.setStyle(Paint.Style.FILL);
            if (claimed) {
                // Gold with a shadowed underside and a lit core, rather than one flat yellow.
                paint.setColor(0xFF7A5406);
                star(sx + r * 0.08f, sy + r * 0.12f, r, r * 0.45f);
                c.drawPath(shape, paint);
                paint.setColor(0xFFF5C518);
                star(sx, sy, r, r * 0.45f);
                c.drawPath(shape, paint);
                paint.setColor(0xFFFFF0B0);
                star(sx - r * 0.06f, sy - r * 0.08f, r * 0.45f, r * 0.2f);
                c.drawPath(shape, paint);
            } else {
                paint.setColor(0xFF0A111B);
                star(sx + r * 0.06f, sy + r * 0.1f, r, r * 0.45f);
                c.drawPath(shape, paint);
                paint.setColor(0xFF2A3648);
                star(sx, sy, r, r * 0.45f);
                c.drawPath(shape, paint);
            }
            label(c, "+" + WEEK_POINTS[k], sx, sy + dp(20f) * s, 8f * s, claimed ? WARN : FAINT,
                    Paint.Align.CENTER);
        }
        label(c, weekWon + " of 7 days won", x0, cy0 + ch0 + dp(48f) * s, 9f * s, DIM, Paint.Align.LEFT);

        // The month's rosette, with an arc showing the run to the next tier.
        float rx = x0 + dp(26f) * s;
        float ry = dp(176f) * s;
        float rr = dp(24f) * s;
        float flash = (float) Math.max(0, Math.min(1, 1 - (sessionSeconds - badgeFlashAt) / 1.5));
        int need = curMonthTier >= 3 ? curMonthLen : badgeNeed(curMonthLen, curMonthTier + 1);
        drawRosette(c, rx, ry, rr * (1 + flash * 0.25f), curMonthTier,
                Math.min(1f, curMonthWon / (float) Math.max(1, need)), flash);
        if (flash > 0.2f && Math.random() < 0.6) {
            fx.burst(rx, ry, 2, dp(150f), 1.1f, dp(2.5f),
                    CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
        }
        bold(c, curMonthName + (curMonthTier > 0 ? "  " + MONTH_BADGE_NAMES[curMonthTier - 1].charAt(0) : ""),
                rx + dp(34f) * s, ry - dp(6f) * s, 13f * s,
                curMonthTier > 0 ? rosetteFace(curMonthTier) : DIM, Paint.Align.LEFT);
        label(c, curMonthWon + " of " + curMonthLen + " days won", rx + dp(34f) * s, ry + dp(10f) * s,
                9f * s, DIM, Paint.Align.LEFT);
        label(c, curMonthTier >= 3 ? "gold month - the highest badge"
                        : curMonthNeed + " more for " + MONTH_BADGE_NAMES[curMonthTier],
                rx + dp(34f) * s, ry + dp(24f) * s, 9f * s, FAINT, Paint.Align.LEFT);
    }

    private static int rosetteFace(int tier) {
        return tier >= 3 ? 0xFFF5C518 : tier == 2 ? 0xFFC9D3DE : 0xFFCD7F32;
    }

    /** The monthly badge: a pleated rosette, greyed with a progress arc until it is earned. */
    private void drawRosette(Canvas c, float x, float y, float r, int tier, float progress, float flash) {
        int face = tier > 0 ? rosetteFace(tier) : 0xFF2A3648;
        int rim = tier >= 3 ? 0xFFB8890B : tier == 2 ? 0xFF7F8A99 : tier == 1 ? 0xFF8A5220 : 0xFF3A4658;
        paint.setStyle(Paint.Style.FILL);
        if (tier > 0) {
            Fx.glow(c, x, y, r * 2.2f, ((int) (40 + 140 * flash) << 24) | (face & 0x00FFFFFF));
        }
        // Petals: twelve, turning slowly so the badge is never a dead disc.
        float spin = (float) (sessionSeconds * 0.35);
        paint.setColor(rim);
        for (int k = 0; k < 12; k++) {
            double a = spin + k * Math.PI / 6;
            c.drawCircle(x + (float) Math.cos(a) * r * 0.78f, y + (float) Math.sin(a) * r * 0.78f,
                    r * 0.3f, paint);
        }
        // A lighter fold on each pleat, offset toward the light, so the petals have a front.
        paint.setColor(blend(rim, tier > 0 ? face : 0xFF5A6678, 0.4f));
        for (int k = 0; k < 12; k++) {
            double a = spin + k * Math.PI / 6;
            c.drawCircle(x + (float) Math.cos(a) * r * 0.74f - r * 0.04f,
                    y + (float) Math.sin(a) * r * 0.74f - r * 0.05f, r * 0.18f, paint);
        }
        if (tier > 0) {
            // Earned: a struck metal centre in the badge's own metal.
            drawMetal(c, x, y, r * 0.72f, Math.min(GOLD, tier), 255, 0.6f, x * 0.01f, false);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(face);
        } else {
            paint.setColor(face);
            c.drawCircle(x, y, r * 0.72f, paint);
            paint.setColor(rim);
            c.drawCircle(x, y, r * 0.58f, paint);
        }
        if (tier > 0) {
            // One pip per tier around the foot of the badge.
            for (int k = 0; k < tier; k++) {
                c.drawCircle(x + (k - (tier - 1) / 2f) * r * 0.34f, y + r * 0.95f, r * 0.1f, paint);
            }
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(r * 0.16f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(ACCENT);
            c.drawArc(x - r * 0.58f, y - r * 0.58f, x + r * 0.58f, y + r * 0.58f, -90,
                    360 * Math.max(0.02f, progress), false, paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /**
     * Tomorrow's challenge, shown as soon as today's is finished: what it is, what it will ask at the
     * chosen difficulty, and a glyph that moves. A Sunday brings the Kraken, and it says so.
     */
    private void drawTomorrow(Canvas c, float w, float h, float barY) {
        float pw = dp(470f);
        float ph = dp(92f);
        float l;
        float t;
        if (monthView) {
            // Above the grid on the right, clear of the month chip and the boss.
            pw = dp(430f);
            l = w - dp(150f) - pw;
            t = h * 0.58f - ph - dp(30f);
        } else {
            l = w / 2f - pw / 2;
            // Never above the RESULT CARD / DOUBLE chips and their stake line, which end at
            // barY + 152dp; the pull up toward the calendar is a preference, not a licence.
            float minTop = barY + dp(160f);
            t = Math.max(minTop, h * 0.50f);
            if (t + ph > h * 0.64f) {
                t = Math.max(minTop, h * 0.64f - ph);
            }
        }
        float in = (float) Math.min(1, Math.max(0, (sessionSeconds - doneAt - 0.4) * 2));
        if (in <= 0) {
            return;
        }
        t += (1 - in) * dp(14f);
        int alpha = (int) (255 * in);
        int edge = tomorrowBoss ? BOSS_COLOR : BLUE;
        panel.set(l, t, l + pw, t + ph);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(((int) (215 * in) << 24) | (tomorrowBoss ? 0x140E24 : 0x0E1A2A));
        c.drawRoundRect(panel, dp(14f), dp(14f), paint);
        // A slow sheen crossing the card, so the preview reads as something arriving.
        c.save();
        c.clipRect(l, t, l + pw, t + ph);
        float sweep = l - dp(120f) + (float) ((sessionSeconds * 0.5) % 1.0) * (pw + dp(240f));
        c.rotate(18f, sweep, t + ph / 2);
        paint.setColor(((int) (26 * in) << 24) | 0xFFFFFF);
        c.drawRect(sweep - dp(26f), t - ph, sweep + dp(26f), t + ph * 2, paint);
        c.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor((alpha << 24) | (edge & 0x00FFFFFF));
        c.drawRoundRect(panel, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.FILL);

        float gx = l + dp(48f);
        float gy = t + ph / 2;
        drawChallengeGlyph(c, gx, gy, dp(26f), tomorrowChallenge, tomorrowBoss, alpha);
        float tx = l + dp(92f);
        label(c, tomorrowBoss ? "TOMORROW  ·  SUNDAY BOSS" : "TOMORROW", tx, t + dp(24f), 9f,
                (alpha << 24) | (tomorrowBoss ? BOSS_COLOR & 0x00FFFFFF : 0x5D6B80), Paint.Align.LEFT);
        bold(c, tomorrowChallenge.title, tx, t + dp(50f), 17f,
                (alpha << 24) | ((tomorrowBoss ? BOSS_COLOR : TEXT) & 0x00FFFFFF), Paint.Align.LEFT);
        label(c, tomorrowLine, tx, t + dp(72f), 11f, (alpha << 24) | (DIM & 0x00FFFFFF), Paint.Align.LEFT);
        label(c, "come back", l + pw - dp(16f), t + dp(24f), 9f, (alpha << 24) | 0x5D6B80,
                Paint.Align.RIGHT);
    }

    /** A small animated emblem for a challenge - the preview's moving part. */
    private void drawChallengeGlyph(Canvas c, float x, float y, float r, Challenge ch, boolean bossDay,
                                    int alpha) {
        float t = (float) sessionSeconds;
        int tint = (alpha << 24) | ((bossDay ? BOSS_COLOR : ACCENT) & 0x00FFFFFF);
        if (bossDay) {
            drawMiniKraken(c, x, y, r, alpha, t);
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(tint);
        switch (ch) {
            case DISTANCE_4MIN: {
                // A dot going round a track.
                c.drawOval(x - r, y - r * 0.6f, x + r, y + r * 0.6f, paint);
                paint.setStyle(Paint.Style.FILL);
                double a = t * 1.6;
                c.drawCircle(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r * 0.6f, dp(4f), paint);
                break;
            }
            case TRIAL_500: {
                // A stopwatch with a sweeping hand.
                c.drawCircle(x, y + r * 0.1f, r * 0.85f, paint);
                c.drawLine(x - r * 0.3f, y - r * 0.9f, x + r * 0.3f, y - r * 0.9f, paint);
                double a = -Math.PI / 2 + (t % 2) * Math.PI;
                c.drawLine(x, y + r * 0.1f, x + (float) Math.cos(a) * r * 0.6f,
                        y + r * 0.1f + (float) Math.sin(a) * r * 0.6f, paint);
                break;
            }
            case POWER_10: {
                // A bolt that flares on a beat.
                float k = 0.85f + 0.3f * (float) Math.abs(Math.sin(t * 3));
                paint.setStyle(Paint.Style.FILL);
                shape.rewind();
                shape.moveTo(x + r * 0.15f * k, y - r * k);
                shape.lineTo(x - r * 0.45f * k, y + r * 0.1f * k);
                shape.lineTo(x - r * 0.05f * k, y + r * 0.1f * k);
                shape.lineTo(x - r * 0.15f * k, y + r * k);
                shape.lineTo(x + r * 0.5f * k, y - r * 0.15f * k);
                shape.lineTo(x + r * 0.08f * k, y - r * 0.15f * k);
                shape.close();
                c.drawPath(shape, paint);
                break;
            }
            case RATE_LADDER: {
                // Rungs with a dot climbing them.
                for (int i = 0; i < 4; i++) {
                    float ry = y + r * 0.75f - i * r * 0.5f;
                    c.drawLine(x - r * 0.7f, ry, x + r * 0.7f, ry, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                int step = (int) (t * 1.2) % 4;
                c.drawCircle(x, y + r * 0.75f - step * r * 0.5f, dp(4f), paint);
                break;
            }
            case NEG_SPLIT: {
                // Two bars, the second overtaking the first.
                float g = 0.5f + 0.5f * (float) Math.sin(t * 2);
                c.drawLine(x - r * 0.8f, y - r * 0.35f, x + r * 0.2f, y - r * 0.35f, paint);
                paint.setColor((alpha << 24) | 0xF5C518);
                c.drawLine(x - r * 0.8f, y + r * 0.35f, x - r * 0.8f + r * (0.4f + 1.2f * g),
                        y + r * 0.35f, paint);
                break;
            }
            default: {
                // A steady wave scrolling through.
                shape.rewind();
                for (int i = 0; i <= 12; i++) {
                    float px = x - r + i * (r * 2 / 12f);
                    float py = y + (float) Math.sin(t * 2 + i * 0.6) * r * 0.45f;
                    if (i == 0) {
                        shape.moveTo(px, py);
                    } else {
                        shape.lineTo(px, py);
                    }
                }
                c.drawPath(shape, paint);
                break;
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** The Kraken in miniature, bobbing, for a Sunday in the preview. */
    private void drawMiniKraken(Canvas c, float x, float y, float r, int alpha, float t) {
        float bob = (float) Math.sin(t * 1.8) * r * 0.12f;
        y += bob;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(r * 0.2f);
        paint.setColor((alpha << 24) | 0x3E2270);
        for (int k = 0; k < 3; k++) {
            float base = x + (k - 1) * r * 0.5f;
            float wave = (float) Math.sin(t * 2.4 + k * 1.3) * r * 0.35f;
            shape.rewind();
            shape.moveTo(base, y + r * 0.4f);
            shape.quadTo(base + wave, y + r * 0.8f, base - wave * 0.6f, y + r * 1.15f);
            c.drawPath(shape, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((alpha << 24) | 0x6A3FB5);
        c.drawOval(x - r * 0.85f, y - r * 0.9f, x + r * 0.85f, y + r * 0.5f, paint);
        boolean blink = (t % 4.3f) < 0.12f;
        for (int s = -1; s <= 1; s += 2) {
            float ex = x + s * r * 0.32f;
            paint.setColor((alpha << 24) | 0xFFFFFF);
            if (blink) {
                c.drawRect(ex - r * 0.16f, y - r * 0.14f, ex + r * 0.16f, y - r * 0.08f, paint);
                continue;
            }
            c.drawCircle(ex, y - r * 0.12f, r * 0.17f, paint);
            paint.setColor((alpha << 24) | 0x1A0F2E);
            c.drawCircle(ex - r * 0.05f, y - r * 0.09f, r * 0.08f, paint);
        }
    }

    /** Rate-ladder rungs and the negative split's ghost, drawn on the progress bar. */
    private void drawBarMarks(Canvas c, float barL, float barR, float barY, double elapsed) {
        if (challenge == Challenge.RATE_LADDER) {
            float seg = (barR - barL) / rungs;
            for (int i = 0; i < rungs; i++) {
                float x0 = barL + seg * i;
                boolean current = phase == Phase.ACTIVE && i == lastRung;
                if (i > 0) {
                    paint.setColor(0x88000000);
                    c.drawRect(x0 - dp(1.5f), barY - dp(4f), x0 + dp(1.5f), barY + dp(22f), paint);
                }
                // Each rung steps up, like the ladder it is.
                float step = dp(6f) * i;
                float flash = current ? (float) Math.max(0, 1 - (sessionSeconds - rungFlashAt)) : 0f;
                bold(c, ladderRates[i] + " spm", x0 + seg / 2, barY - dp(8f) - step - flash * dp(8f),
                        current ? 16f + flash * 6f : 12f, current ? (onTrack ? ACCENT : BAD) : DIM, Paint.Align.CENTER);
            }
            if (phase == Phase.ACTIVE && lastRung >= 0) {
                // Your rate against the rung: a dot riding above the bar, left when slow, right when fast.
                double off = Math.max(-3, Math.min(3, rateNow() - ladderRates[lastRung]));
                float mid = barL + seg * (lastRung + 0.5f);
                float dx = (float) (off / 3) * seg * 0.45f;
                paint.setColor(onTrack ? ACCENT : BAD);
                c.drawCircle(mid + dx, barY + dp(32f), dp(6f), paint);
                paint.setColor(0x55FFFFFF);
                c.drawRect(mid - seg * 0.45f * 0.5f, barY + dp(31f), mid + seg * 0.45f * 0.5f, barY + dp(33f), paint);
            }
        } else if (challenge == Challenge.NEG_SPLIT) {
            float half = (barL + barR) / 2;
            paint.setColor(0xAAFFFFFF);
            c.drawRect(half - dp(1.5f), barY - dp(6f), half + dp(1.5f), barY + dp(24f), paint);
            label(c, "250 m", half, barY - dp(10f), 10f, DIM, Paint.Align.CENTER);
            if (phase == Phase.ACTIVE && halfTime >= 0) {
                // The first half, replayed as a ghost you have to pass.
                double gm = 250 + ghostMetresAt(elapsed - halfTime);
                float gx = barL + (barR - barL) * (float) Math.min(1, gm / 500);
                paint.setColor(0xCCB0C4DE);
                shape.rewind();
                shape.moveTo(gx, barY - dp(2f));
                shape.lineTo(gx - dp(8f), barY - dp(16f));
                shape.lineTo(gx + dp(8f), barY - dp(16f));
                shape.close();
                c.drawPath(shape, paint);
                label(c, "1st half", gx, barY - dp(20f), 9f, 0xFFB0C4DE, Paint.Align.CENTER);
            }
        }
    }

    /** Time the first half took to reach {@code m} metres, interpolated between 10 m marks. */
    private double ghostTimeAt(double m) {
        double i = Math.max(0, Math.min(25, m / 10));
        int lo = (int) Math.floor(i);
        int hi = Math.min(25, lo + 1);
        return firstHalfAt[lo] + (firstHalfAt[hi] - firstHalfAt[lo]) * (i - lo);
    }

    /** Metres the first half had covered after {@code t} seconds. */
    private double ghostMetresAt(double t) {
        if (t <= 0) {
            return 0;
        }
        for (int i = 1; i <= 25; i++) {
            if (firstHalfAt[i] >= t) {
                double span = Math.max(1e-6, firstHalfAt[i] - firstHalfAt[i - 1]);
                return (i - 1 + (t - firstHalfAt[i - 1]) / span) * 10;
            }
        }
        return 250;
    }

    /** THE KRAKEN: loses health while you are on pace, swings a tentacle while you are not. */
    private void drawBoss(Canvas c, float w, float h, float dt) {
        float bx = w * 0.87f;
        float r = Math.min(dp(56f), h * 0.085f);
        float by = dp(150f);
        bossHpShown += (bossHp - bossHpShown) * Math.min(1f, dt * 3f);
        boolean ko = phase == Phase.DONE && success;
        boolean gloat = phase == Phase.DONE && !success;
        if (phase == Phase.ACTIVE) {
            if (onTrack) {
                hitTimer += dt;
                attack = Math.max(0f, attack - dt * 2f);
                if (hitTimer >= 1.2) {
                    hitTimer = 0;
                    flinch = 1f;
                }
            } else {
                hitTimer = 0;
                attack = Math.min(1f, attack + dt * 1.5f);
            }
        } else {
            attack = Math.max(0f, attack - dt * 2f);
        }
        if (flinch >= 0.999f) {
            fx.burst(bx, by, 10, dp(160f), 0.6f, dp(3f), 0xFFFFE28A, false);
        }
        flinch = Math.max(0f, flinch - dt * 3f);
        float t = (float) sessionSeconds;
        float sink = ko ? (float) Math.min(1, (sessionSeconds - doneAt) / 2.5) : 0f;
        float bob = (float) Math.sin(t * 1.6f) * dp(5f) + (gloat ? (float) Math.abs(Math.sin(t * 9)) * -dp(6f) : 0f);
        float shakeX = flinch * (float) Math.sin(t * 60) * dp(6f);
        float x = bx + shakeX;
        float y = by + bob + sink * dp(90f);
        int alpha = (int) (255 * (1 - sink * 0.85f));
        int body = (alpha << 24) | (flinch > 0.3f ? 0xE08AFF : 0x6A3FB5);
        int dark = (alpha << 24) | 0x3E2270;
        Fx.glow(c, x, y, r * 2.0f, attack > 0.3f ? 0x44F0655D : 0x33B06CFF);
        // Tentacles: five waving curves; the outer right one rises to swing when you fall off pace.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(r * 0.22f);
        paint.setColor(dark);
        for (int k = 0; k < 5; k++) {
            float base = x + (k - 2) * r * 0.4f;
            float wave = (float) Math.sin(t * 2.2f + k * 1.3f) * r * 0.35f;
            shape.rewind();
            shape.moveTo(base, y + r * 0.5f);
            if (k == 0 && attack > 0.05f) {
                float sw = (float) Math.sin(t * 7) * r * 0.3f;
                shape.quadTo(base - r * 0.9f, y - r * 0.2f * attack, base - r * 1.2f + sw, y - r * 1.3f * attack);
            } else {
                shape.quadTo(base + wave, y + r * 1.0f, base - wave * 0.6f, y + r * 1.5f);
            }
            c.drawPath(shape, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        // Head: a dome.
        paint.setColor(body);
        c.drawOval(x - r, y - r * 1.1f, x + r, y + r * 0.65f, paint);
        paint.setColor((alpha << 24) | 0x8A5AD8);
        c.drawCircle(x - r * 0.4f, y - r * 0.6f, r * 0.12f, paint);
        c.drawCircle(x + r * 0.2f, y - r * 0.8f, r * 0.08f, paint);
        // Eyes: crosses when knocked out, otherwise tracking the progress bar.
        float ex = r * 0.38f;
        float ey = y - r * 0.15f;
        if (ko) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3f));
            paint.setColor((alpha << 24) | 0xFFFFFF);
            for (int s = -1; s <= 1; s += 2) {
                float cxe = x + s * ex;
                c.drawLine(cxe - r * 0.14f, ey - r * 0.14f, cxe + r * 0.14f, ey + r * 0.14f, paint);
                c.drawLine(cxe - r * 0.14f, ey + r * 0.14f, cxe + r * 0.14f, ey - r * 0.14f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else {
            boolean blink = (t % 4.3f) < 0.12f;
            for (int s = -1; s <= 1; s += 2) {
                float cxe = x + s * ex;
                paint.setColor(0xFFFFFFFF);
                if (blink) {
                    c.drawRect(cxe - r * 0.2f, ey - dp(1.5f), cxe + r * 0.2f, ey + dp(1.5f), paint);
                    continue;
                }
                c.drawCircle(cxe, ey, r * 0.2f, paint);
                paint.setColor(attack > 0.3f ? BAD : 0xFF1A0F2E);
                c.drawCircle(cxe - r * 0.07f, ey + r * 0.04f, r * 0.1f, paint);
            }
            // Brows angle down as it attacks.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3f));
            paint.setColor(dark);
            float tilt = r * (0.05f + 0.15f * attack);
            c.drawLine(x - ex - r * 0.2f, ey - r * 0.3f - tilt, x - ex + r * 0.2f, ey - r * 0.3f + tilt, paint);
            c.drawLine(x + ex - r * 0.2f, ey - r * 0.3f + tilt, x + ex + r * 0.2f, ey - r * 0.3f - tilt, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // Mouth: a grin when gloating or attacking, a wobble when hit.
        paint.setColor(0xFF1A0F2E);
        float mouth = gloat || attack > 0.3f ? r * 0.18f : r * 0.07f + flinch * r * 0.12f;
        c.drawOval(x - r * 0.25f, y + r * 0.22f - mouth * 0.3f, x + r * 0.25f, y + r * 0.22f + mouth, paint);
        if (gloat) {
            bold(c, "HA HA", x, y - r * 1.3f - (float) Math.abs(Math.sin(t * 4)) * dp(6f), 16f, BAD, Paint.Align.CENTER);
        } else if (phase == Phase.ACTIVE && attack > 0.6f) {
            bold(c, "IT'S WINNING", x, y - r * 1.3f, 12f, BAD, Paint.Align.CENTER);
        }
        // Health bar.
        float hbW = r * 2.4f;
        float hbY = by - r * 1.1f - dp(40f);
        paint.setColor(0x44FFFFFF);
        c.drawRoundRect(bx - hbW / 2, hbY, bx + hbW / 2, hbY + dp(10f), dp(5f), dp(5f), paint);
        paint.setColor(bossHpShown > 0.5f ? BOSS_COLOR : bossHpShown > 0.2f ? WARN : BAD);
        c.drawRoundRect(bx - hbW / 2, hbY, bx - hbW / 2 + hbW * bossHpShown, hbY + dp(10f), dp(5f), dp(5f), paint);
        label(c, "THE KRAKEN  " + Math.round(bossHpShown * 100) + "%", bx, hbY - dp(5f), 10f, BOSS_COLOR, Paint.Align.CENTER);
    }

    private void drawStreak(Canvas c, float tx, float ty, float fxc, float fyc) {
        int streak = streak();
        if (streak > 0) {
            // A flame beside the streak, taller the longer it runs.
            float fh = dp(18f) + Math.min(10, streak) * dp(2f);
            drawFlame(c, fxc, fyc + dp(4f), fh);
        } else {
            drawColdFlame(c, fxc, fyc + dp(4f), dp(18f));
        }
        bold(c, streak + " DAY STREAK", tx, ty, 26f, streak > 0 ? WARN : DIM, Paint.Align.CENTER);
        label(c, "best streak " + Math.round(bests.get("daily.streak", 0f)) + " days", tx, ty + dp(18f), 10f, FAINT, Paint.Align.CENTER);
        if (token) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(sessionSeconds * 3);
            String ready = "STREAK SAVER READY  ·  covers one missed day";
            float left = tx - (textWidth(ready, 11f) + dp(26f)) / 2;
            drawShield(c, left + dp(10f), ty + dp(38f), dp(10f) * pulse, BLUE);
            label(c, ready, left + dp(26f), ty + dp(42f), 11f, BLUE, Paint.Align.LEFT);
        } else {
            label(c, "beat a Sunday boss or row 7 days in a row to earn a streak saver", tx, ty + dp(40f), 10f, FAINT, Paint.Align.CENTER);
        }
    }

    /** The 28-day strip: a tick on each day won, purple for a boss, a shield where a saver covered. */
    private void drawStrip(Canvas c, float calL, float calY, float cell, int cells, boolean pulseToday) {
        float cw = cell - dp(4f);
        float ch = cell - dp(4f);
        // Today's stamp thumps down when the day is won, the same beat as the big DAY WON stamp.
        float thump = phase == Phase.DONE && success
                ? (float) Math.max(0, Math.min(1.0, (sessionSeconds - doneAt - 0.25) * 3)) : 1f;
        for (int i = 0; i < cells; i++) {
            long day = today - (cells - 1 - i);
            boolean done = days.contains(day);
            boolean cover = !done && saved.contains(day);
            Integer tier = done ? medals.get(day) : null;
            boolean bossDay = tier != null && tier == BOSS_MEDAL;
            float l = calL + i * cell + dp(2f);
            float cx0 = calL + (i + 0.5f) * cell;
            float cy0 = calY + ch / 2f;
            if (done) {
                // A stamp on every day you did it.
                drawStampedCell(c, l, calY, cw, ch, day, bossDay ? 1 : 0, day == today ? thump : 1f);
            } else {
                drawSunkCell(c, l, calY, l + cw, calY + ch,
                        cover ? 0xFF24345C : day == today ? 0xFF2A3648 : 0xFF141D2A);
            }
            if (cover) {
                drawShield(c, cx0, cy0, cell * 0.28f, BLUE);
            } else if (!done && day == today && pulseToday) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 3);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (80 + 150 * pulse) << 24) | 0x35D0BA);
                c.drawRoundRect(calL + i * cell + dp(1f), calY - dp(1f), calL + (i + 1) * cell - dp(1f), calY + cell - dp(3f), dp(6f), dp(6f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if ((day + 4) % 7 == 0) {
                // Sundays are boss days: a small purple dot underneath.
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x99B06CFF);
                c.drawCircle(cx0, calY + cell + dp(1f), dp(2f), paint);
            }
        }
    }

    /** A calendar month with a medal on each day won. */
    private void drawMonth(Canvas c, float w, float h) {
        float top = h * 0.58f;
        float bottom = h - dp(8f);
        float rowH = (bottom - top - dp(46f)) / 6f;
        float cellW = Math.min(rowH * 1.35f, dp(66f));
        float gx = w * 0.58f - cellW * 3.5f;
        bold(c, monthTitle, w * 0.58f, top - dp(6f), 16f, TEXT, Paint.Align.CENTER);
        // The month's own rosette beside its name, greyed with a progress arc until it is earned.
        float badgeNeeded = gridMonthTier >= 3 ? monthLen : badgeNeed(monthLen, gridMonthTier + 1);
        drawRosette(c, w * 0.58f + textWidth(monthTitle, 16f) / 2 + dp(26f), top - dp(12f), dp(14f),
                gridMonthTier, Math.min(1f, gridMonthWon / Math.max(1f, badgeNeeded)), 0f);
        prevArrow.set(gx - dp(4f), top - dp(34f), gx + dp(40f), top + dp(4f));
        nextArrow.set(gx + cellW * 7 - dp(40f), top - dp(34f), gx + cellW * 7 + dp(4f), top + dp(4f));
        bold(c, "<", prevArrow.centerX(), top - dp(6f), 20f, DIM, Paint.Align.CENTER);
        if (monthOffset < 0) {
            bold(c, ">", nextArrow.centerX(), top - dp(6f), 20f, DIM, Paint.Align.CENTER);
        } else {
            nextArrow.setEmpty();
        }
        for (int k = 0; k < 7; k++) {
            label(c, WEEKDAYS[k], gx + (k + 0.5f) * cellW, top + dp(16f), 10f, k == 6 ? BOSS_COLOR : FAINT, Paint.Align.CENTER);
        }
        float gridTop = top + dp(24f);
        int won = 0;
        for (int i = 0; i < monthLen; i++) {
            int slot = monthLead + i;
            int col = slot % 7;
            int row = slot / 7;
            float x0 = gx + col * cellW;
            float y0 = gridTop + row * rowH;
            long day = monthFirst + i;
            drawSunkCell(c, x0 + dp(2f), y0 + dp(2f), x0 + cellW - dp(2f), y0 + rowH - dp(2f),
                    day == today ? 0xFF2A3648 : day > today ? 0xFF0C1520 : 0xFF141D2A);
            label(c, String.valueOf(i + 1), x0 + dp(6f), y0 + dp(13f), 9f, FAINT, Paint.Align.LEFT);
            int tier = monthTier[i];
            float mr = Math.min(cellW, rowH) * 0.28f;
            float mx = x0 + cellW / 2 + dp(4f);
            float my = y0 + rowH / 2 + dp(2f);
            if (tier > 0) {
                won++;
                drawMedal(c, mx, my, mr, tier, day == today && phase == Phase.DONE && success);
            } else if (tier < 0) {
                drawShield(c, mx, my, mr * 0.9f, BLUE);
            }
            if (day == today) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 3);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (80 + 150 * pulse) << 24) | 0x35D0BA);
                c.drawRoundRect(x0 + dp(1f), y0 + dp(1f), x0 + cellW - dp(1f), y0 + rowH - dp(1f), dp(6f), dp(6f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        // Streak and legend on the left of the grid.
        float lx = w * 0.2f;
        drawStreak(c, lx, top + dp(90f), lx, top + dp(40f));
        float ly = top + dp(170f);
        if (ly + dp(20f) < bottom) {
            label(c, won + (won == 1 ? " MEDAL" : " MEDALS") + " IN " + monthTitle, lx, ly, 11f, TEXT, Paint.Align.CENTER);
            float lr = dp(8f);
            float lxs = lx - dp(150f);
            int[] tiers = LEGEND_TIERS;
            for (int k = 0; k < tiers.length; k++) {
                float x = lxs + k * dp(76f);
                if (tiers[k] > 0) {
                    drawMedal(c, x, ly + dp(24f), lr, tiers[k], false);
                } else {
                    drawShield(c, x, ly + dp(24f), lr, BLUE);
                }
                label(c, LEGEND_NAMES[k], x + dp(12f), ly + dp(28f), 9f, FAINT, Paint.Align.LEFT);
            }
            if (ly + dp(52f) < bottom) {
                label(c, gridMonthWon + " of " + monthLen + " days won"
                                + (gridMonthTier > 0 ? "  ·  " + MONTH_BADGE_NAMES[gridMonthTier - 1]
                                : "  ·  " + (badgeNeed(monthLen, 1) - gridMonthWon) + " more for a badge"),
                        lx, ly + dp(50f), 10f,
                        gridMonthTier > 0 ? rosetteFace(gridMonthTier) : FAINT, Paint.Align.CENTER);
            }
        }
    }

    private static final int[] LEGEND_TIERS = {BRONZE, SILVER, GOLD, BOSS_MEDAL, -1};
    private static final String[] LEGEND_NAMES = {"won", "+5%", "+10%", "boss", "saved"};

    /** The shareable result card, drawn over everything; the camera button photographs it. */
    private void drawCard(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xDD03070D);
        c.drawRect(0, 0, w, h, paint);
        float cw = Math.min(w * 0.62f, h * 1.25f);
        float ch = Math.min(h * 0.86f, cw * 0.62f);
        float l = (w - cw) / 2;
        float t = (h - ch) / 2;
        int edge = !success ? WARN : boss ? BOSS_COLOR : ACCENT;
        Fx.glow(c, w / 2, h / 2, cw * 0.55f, (0x30 << 24) | (edge & 0x00FFFFFF));
        paint.setColor(boss ? 0xFF140E24 : 0xFF0E1A2A);
        c.drawRoundRect(l, t, l + cw, t + ch, dp(18f), dp(18f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3f));
        paint.setColor(edge);
        c.drawRoundRect(l, t, l + cw, t + ch, dp(18f), dp(18f), paint);
        paint.setStyle(Paint.Style.FILL);
        float pad = dp(28f);
        label(c, "WAKE  ·  DAILY ROW", l + pad, t + dp(36f), 12f, FAINT, Paint.Align.LEFT);
        label(c, weekdayText + "  " + dateText, l + cw - pad, t + dp(36f), 12f, DIM, Paint.Align.RIGHT);
        if (boss) {
            bold(c, "SUNDAY BOSS  ·  THE KRAKEN", l + pad, t + dp(64f), 13f, BOSS_COLOR, Paint.Align.LEFT);
        }
        bold(c, challenge.title, l + pad, t + dp(100f), 26f, TEXT, Paint.Align.LEFT);
        // Medal on the right: the day's tier on a win, a dulled medal on a miss.
        float mr = ch * 0.16f;
        float mx = l + cw - pad - mr;
        float my = t + ch * 0.42f;
        if (success) {
            Fx.glow(c, mx, my, mr * 1.8f, 0x66F5C518);
            drawMedal(c, mx, my, mr, Math.max(BRONZE, todayTier), false);
        } else {
            paint.setColor(0x66000000);
            c.drawCircle(mx + mr * 0.07f, my + mr * 0.12f, mr, paint);
            drawMetal(c, mx, my, mr, PAL_TARNISHED, 255, 0f, 0f, false);
        }
        bold(c, success ? (boss ? "BOSS DOWN" : "DAY WON") : "NOT TODAY", l + pad, t + ch * 0.36f, 40f, edge, Paint.Align.LEFT);
        bold(c, resultText(), l + pad, t + ch * 0.36f + dp(44f), 30f, TEXT, Paint.Align.LEFT);
        label(c, "target " + targetLine(), l + pad, t + ch * 0.36f + dp(68f), 12f, DIM, Paint.Align.LEFT);
        int streak = streak();
        bold(c, streak + " DAY STREAK", l + pad, t + ch * 0.36f + dp(104f), 20f, streak > 0 ? WARN : DIM, Paint.Align.LEFT);
        if (token) {
            drawShield(c, l + pad + dp(220f), t + ch * 0.36f + dp(98f), dp(9f), BLUE);
            label(c, "saver ready", l + pad + dp(234f), t + ch * 0.36f + dp(102f), 11f, BLUE, Paint.Align.LEFT);
        }
        // The reward line: what the day paid, at which difficulty, and the month's badge.
        bold(c, todayPoints + " PTS", l + cw - pad, t + ch * 0.36f + dp(104f), 20f,
                todayPoints > 0 ? 0xFFF5C518 : DIM, Paint.Align.RIGHT);
        label(c, DIFF_NAMES[diff] + "  ·  x" + String.format(java.util.Locale.US, "%.1f", DIFF_REWARD[diff])
                        + (doubleResult > 0 ? "  ·  DOUBLED" : doubleResult < 0 ? "  ·  DOUBLE LOST" : ""),
                l + cw - pad, t + ch * 0.36f + dp(122f), 11f, diffColor(diff), Paint.Align.RIGHT);
        label(c, weekWon + " of 7 this week  ·  " + curMonthWon + " days in " + curMonthName
                        + (curMonthTier > 0 ? "  ·  " + MONTH_BADGE_NAMES[curMonthTier - 1] : ""),
                l + cw - pad, t + ch * 0.36f + dp(140f), 10f, DIM, Paint.Align.RIGHT);
        int cells = 28;
        float cell = Math.min(dp(28f), (cw - pad * 2) / cells);
        float stripY = t + ch - pad - cell - dp(10f);
        label(c, "LAST 28 DAYS", l + pad, stripY - dp(8f), 9f, FAINT, Paint.Align.LEFT);
        label(c, "TOMORROW  ·  " + tomorrowChallenge.title + (tomorrowBoss ? "  ·  SUNDAY BOSS" : ""),
                l + cw - pad, stripY - dp(8f), 9f, tomorrowBoss ? BOSS_COLOR : BLUE, Paint.Align.RIGHT);
        drawStrip(c, l + pad, stripY, cell, cells, false);
        label(c, "tap to close  ·  camera button saves it", w / 2, t + ch + dp(26f), 11f, FAINT, Paint.Align.CENTER);
    }

    private void drawMedal(Canvas c, float x, float y, float r, int tier, boolean shine) {
        // Ribbon tails, each with a darker fold down its inner half so they read as cloth.
        int ribbon = tier == BOSS_MEDAL ? 0xFFF0655D : 0xFF6F8CFF;
        int fold = tier == BOSS_MEDAL ? 0xFFA83A34 : 0xFF3A4FA8;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ribbon);
        shape.rewind();
        shape.moveTo(x - r * 0.7f, y - r * 1.25f);
        shape.lineTo(x - r * 0.1f, y - r * 1.25f);
        shape.lineTo(x + r * 0.2f, y - r * 0.5f);
        shape.lineTo(x - r * 0.4f, y - r * 0.5f);
        shape.close();
        shape.moveTo(x + r * 0.7f, y - r * 1.25f);
        shape.lineTo(x + r * 0.1f, y - r * 1.25f);
        shape.lineTo(x - r * 0.2f, y - r * 0.5f);
        shape.lineTo(x + r * 0.4f, y - r * 0.5f);
        shape.close();
        c.drawPath(shape, paint);
        paint.setColor(fold);
        shape.rewind();
        shape.moveTo(x - r * 0.1f, y - r * 1.25f);
        shape.lineTo(x - r * 0.3f, y - r * 1.25f);
        shape.lineTo(x - r * 0.02f, y - r * 0.5f);
        shape.lineTo(x + r * 0.2f, y - r * 0.5f);
        shape.close();
        c.drawPath(shape, paint);
        // Contact shadow, down and right of the light.
        paint.setColor(0x66000000);
        c.drawCircle(x + r * 0.07f, y + r * 0.12f, r, paint);
        // Every won medal catches the light now and then, staggered by position so a month of
        // them glints one at a time; today's sweeps at full strength.
        float phase = ((x * 0.0137f + y * 0.0071f) % 1f + 1f) % 1f;
        drawMetal(c, x, y, r, Math.max(1, Math.min(4, tier)), 255, shine ? 1f : 0.45f,
                shine ? 0f : phase * 6f, tier == BOSS_MEDAL);
    }

    /**
     * A struck metal disc: bevelled rim lit from the top left, a domed face, an embossed star (or a
     * crown), and a band of light that sweeps across it. {@code shine} 0..1 sets that band's
     * strength; {@code delay} offsets its timing. Gradients are cached by palette and radius.
     */
    private void drawMetal(Canvas c, float x, float y, float r, int pal, int alpha, float shine,
                           float delay, boolean crown) {
        if (alpha <= 0 || r < 1f) {
            return;
        }
        int ri = Math.max(2, Math.min(1023, Math.round(r)));
        int key = pal * 1024 + ri;
        Metal m = metals.get(key);
        if (m == null) {
            m = new Metal(pal, ri);
            metals.put(key, m);
        }
        float k = r / ri;
        c.save();
        c.translate(x, y);
        c.scale(k, k);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0xFFFFFFFF);
        gp.setAlpha(alpha);
        gp.setShader(m.rim);
        c.drawCircle(0, 0, ri, gp);
        gp.setShader(m.face);
        c.drawCircle(0, 0, ri * 0.84f, gp);
        // The inner bevel is the rim's gradient turned half round: dark top left, lit bottom right.
        gp.setShader(m.rim);
        gp.setStyle(Paint.Style.STROKE);
        gp.setStrokeWidth(ri * 0.07f);
        c.rotate(180f);
        c.drawCircle(0, 0, ri * 0.84f, gp);
        c.rotate(180f);
        gp.setStyle(Paint.Style.FILL);
        gp.setShader(null);
        // Emblem: shadow offset away from the light, then the lit face.
        paint.setStyle(Paint.Style.FILL);
        for (int pass = 0; pass < 2; pass++) {
            float o = pass == 0 ? ri * 0.05f : 0f;
            paint.setColor(withAlpha(pass == 0 ? PAL_STAR_SHADOW[pal] : PAL_STAR[pal], alpha));
            if (crown) {
                shape.rewind();
                shape.moveTo(o - ri * 0.45f, o + ri * 0.3f);
                shape.lineTo(o - ri * 0.45f, o - ri * 0.3f);
                shape.lineTo(o - ri * 0.22f, o);
                shape.lineTo(o, o - ri * 0.4f);
                shape.lineTo(o + ri * 0.22f, o);
                shape.lineTo(o + ri * 0.45f, o - ri * 0.3f);
                shape.lineTo(o + ri * 0.45f, o + ri * 0.3f);
                shape.close();
            } else {
                star(o, o, ri * 0.52f, ri * 0.23f);
            }
            c.drawPath(shape, paint);
        }
        // Sweep: a pass of about a second, then a rest, so it reads as a glint rather than a strobe.
        if (shine > 0.02f) {
            float u = (float) (((sessionSeconds + delay) * 0.42) % 1.6);
            if (u < 1f) {
                float d = (-1.5f + 3f * u) * ri;
                shineMatrix.setTranslate(d, d);
                m.shine.setLocalMatrix(shineMatrix);
                gp.setColor(0xFFFFFFFF);
                gp.setAlpha((int) (alpha * Math.min(1f, shine)));
                gp.setShader(m.shine);
                c.drawCircle(0, 0, ri * 0.84f, gp);
                gp.setShader(null);
            }
        }
        // A fixed specular point where the light hits the dome.
        paint.setColor(withAlpha(0x99FFFFFF, alpha));
        c.drawCircle(-ri * 0.36f, -ri * 0.42f, ri * 0.08f, paint);
        c.restore();
    }

    private static int withAlpha(int argb, int alpha) {
        int a = ((argb >>> 24) * Math.max(0, Math.min(255, alpha))) / 255;
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Backdrop: a deep vertical gradient and one slow nebula of light, so the screen has a stage. */
    private void drawBackdrop(Canvas c, float w, float h) {
        if (bgShader == null || bgShaderH != h || bgShaderBoss != boss) {
            bgShaderH = h;
            bgShaderBoss = boss;
            bgShader = new android.graphics.LinearGradient(0, 0, 0, h,
                    boss ? new int[] {0xFF1A1030, 0xFF100A1C, 0xFF07050E}
                            : new int[] {0xFF0D1A2C, 0xFF08111C, 0xFF04080E},
                    new float[] {0f, 0.5f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        }
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0xFFFFFFFF);
        gp.setShader(bgShader);
        c.drawRect(0, 0, w, h, gp);
        gp.setShader(null);
        float nx = w * (0.56f + 0.14f * (float) Math.sin(sessionSeconds * 0.05));
        float ny = h * (0.46f + 0.08f * (float) Math.cos(sessionSeconds * 0.07));
        Fx.glow(c, nx, ny, w * 0.34f, boss ? 0x20B06CFF : 0x206F8CFF);
    }

    /** The plaque behind the challenge name: a rounded, lit panel in the app's HUD family. */
    private void drawPlaque(Canvas c, float l, float t, float r, float b, int edge) {
        float ph = b - t;
        if (plaqueShader == null || plaqueShaderH != ph) {
            plaqueShaderH = ph;
            plaqueShader = new android.graphics.LinearGradient(0, 0, 0, ph,
                    new int[] {0xE6142032, 0xD90D1420, 0xCC080E18}, new float[] {0f, 0.5f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        float rad = dp(18f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        c.drawRoundRect(l + dp(2f), t + dp(5f), r + dp(2f), b + dp(5f), rad, rad, paint);
        c.save();
        c.translate(0, t);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0xFFFFFFFF);
        gp.setShader(plaqueShader);
        c.drawRoundRect(l, 0, r, ph, rad, rad, gp);
        gp.setShader(null);
        c.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor((0x48 << 24) | (edge & 0x00FFFFFF));
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setStyle(Paint.Style.FILL);
        // The lit top edge.
        paint.setColor(0x30FFFFFF);
        c.drawRect(l + rad, t + dp(1f), r - rad, t + dp(2f), paint);
    }

    /** Glossy vertical fill for a calendar cell; kind 0 a won day, 1 a boss day. */
    private android.graphics.LinearGradient cellShader(int kind, float ch) {
        int key = kind * 4096 + Math.max(1, Math.min(4095, Math.round(ch)));
        android.graphics.LinearGradient g = cellShaders.get(key);
        if (g == null) {
            g = new android.graphics.LinearGradient(0, 0, 0, Math.round(ch),
                    kind == 1 ? new int[] {0xFFDDB8FF, 0xFFB06CFF, 0xFF6A38B8}
                            : new int[] {0xFF8AF7E7, 0xFF35D0BA, 0xFF1A8576},
                    new float[] {0f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP);
            cellShaders.put(key, g);
        }
        return g;
    }

    /**
     * A won calendar cell: a glossy tile with an inked stamp on it - a broken ring and a tick, set at
     * a slant that is fixed per day, so the strip looks hand-stamped rather than printed.
     */
    private void drawStampedCell(Canvas c, float l, float t, float cw, float ch, long day, int kind,
                                 float thump) {
        float rad = Math.min(dp(5f), cw * 0.2f);
        c.save();
        c.translate(l, t);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0xFFFFFFFF);
        gp.setShader(cellShader(kind, ch));
        c.drawRoundRect(0, 0, cw, ch, rad, rad, gp);
        gp.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66FFFFFF);
        c.drawRect(rad, dp(1f), cw - rad, dp(2f), paint);
        long hsh = day * 2654435761L;
        float slant = (float) (((hsh >>> 9) & 31) - 15);
        float sc = 1f + 1.3f * (1f - thump);
        c.translate(cw / 2f, ch / 2f);
        c.rotate(slant);
        c.scale(sc, sc);
        int ink = withAlpha(0xE00A1626, (int) (255 * thump));
        float sr = Math.min(cw, ch) * 0.34f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(dp(1.5f), sr * 0.16f));
        paint.setColor(ink);
        float gap = (float) ((hsh >>> 17) & 255) * 1.4f;
        c.drawArc(-sr, -sr, sr, sr, gap, 300f, false, paint);
        paint.setStrokeWidth(Math.max(dp(2f), sr * 0.2f));
        c.drawLine(-sr * 0.5f, 0, -sr * 0.12f, sr * 0.4f, paint);
        c.drawLine(-sr * 0.12f, sr * 0.4f, sr * 0.55f, -sr * 0.42f, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** An empty calendar cell, sunk into the panel: dark top lip, faint lit bottom edge. */
    private void drawSunkCell(Canvas c, float l, float t, float r, float b, int fill) {
        float rad = Math.min(dp(5f), (r - l) * 0.2f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        c.drawRoundRect(l, t, r, b, rad, rad, paint);
        paint.setColor(0x70000000);
        c.drawRect(l + rad, t, r - rad, t + dp(2f), paint);
        paint.setColor(0x16FFFFFF);
        c.drawRect(l + rad, b - dp(1.5f), r - rad, b - dp(0.5f), paint);
    }

    /**
     * The streak flame: four nested tongues (red, orange, yellow, a white heart) that sway and
     * stretch on unrelated sines so it never loops visibly, two side licks, and embers lifting off.
     * {@code base} is the bottom of the flame.
     */
    private void drawFlame(Canvas c, float x, float base, float fh) {
        float t = (float) sessionSeconds;
        Fx.glow(c, x, base - fh * 0.4f, fh * 1.5f, 0x50FF6A2A);
        Fx.glow(c, x, base - fh * 0.1f, fh * 0.7f, 0x60FFB347);
        int fkey = Math.round(fh);
        if (flameShader == null || flameShaderKey != fkey) {
            flameShaderKey = fkey;
            flameShader = new android.graphics.LinearGradient(0, 0, 0, -fh * 1.1f,
                    new int[] {0xFFE0401E, 0xFFFF6A2A, 0x30FF3A10}, new float[] {0f, 0.55f, 1f},
                    android.graphics.Shader.TileMode.CLAMP);
        }
        float sway = fh * (0.10f * (float) Math.sin(t * 6.3f) + 0.04f * (float) Math.sin(t * 15.7f));
        float stretch = 1f + 0.07f * (float) Math.sin(t * 9.1f) + 0.04f * (float) Math.sin(t * 21.3f);
        c.save();
        c.translate(x, base);
        // Side licks first, so the main tongue covers their roots.
        paint.setStyle(Paint.Style.FILL);
        for (int s = -1; s <= 1; s += 2) {
            float lick = 0.5f + 0.5f * (float) Math.sin(t * (7.7f + s * 1.3f) + s);
            c.save();
            c.translate(s * fh * 0.2f, -fh * 0.04f);
            c.rotate(s * (14f + 8f * lick));
            flamePath(fh * 0.17f, fh * (0.38f + 0.18f * lick), sway * 0.6f);
            paint.setColor(0xCCFF5A24);
            c.drawPath(flame, paint);
            c.restore();
        }
        flamePath(fh * 0.46f, fh * 1.08f * stretch, sway);
        gp.setStyle(Paint.Style.FILL);
        gp.setColor(0xFFFFFFFF);
        gp.setShader(flameShader);
        c.drawPath(flame, gp);
        gp.setShader(null);
        flamePath(fh * 0.33f, fh * 0.80f * stretch, sway * 0.8f);
        paint.setColor(0xFFFF9A3D);
        c.drawPath(flame, paint);
        float s2 = 1f + 0.08f * (float) Math.sin(t * 12.4f + 1f);
        flamePath(fh * 0.21f, fh * 0.52f * s2, sway * 0.55f);
        paint.setColor(0xFFFFD36A);
        c.drawPath(flame, paint);
        flamePath(fh * 0.10f, fh * 0.26f * s2, sway * 0.3f);
        paint.setColor(0xFFFFF6DA);
        c.drawPath(flame, paint);
        c.restore();
        // Embers: deterministic, a handful, fading before they get far.
        for (int i = 0; i < 5; i++) {
            float ph = (t * 0.55f + i * 0.21f) % 1f;
            float ex = x + fh * (0.22f * (float) Math.sin(i * 1.7f + t * 1.9f) + (i - 2) * 0.05f);
            float ey = base - fh * (0.75f + 0.4f * ph);
            paint.setColor(((int) (210 * (1f - ph)) << 24) | (i % 2 == 0 ? 0xFFC14A : 0xFF7A2E));
            c.drawCircle(ex, ey, dp(1.4f) + (i % 3) * dp(0.5f), paint);
        }
    }

    /** One flame tongue into {@link #flame}, base at the origin: round belly, pointed swaying tip. */
    private void flamePath(float wb, float hh, float sway) {
        flame.rewind();
        flame.moveTo(0, 0);
        flame.cubicTo(wb * 0.95f, 0, wb * 1.05f, -hh * 0.36f, wb * 0.55f + sway * 0.3f, -hh * 0.6f);
        flame.quadTo(wb * 0.22f + sway * 0.7f, -hh * 0.84f, sway, -hh);
        flame.quadTo(-wb * 0.28f + sway * 0.7f, -hh * 0.8f, -wb * 0.55f + sway * 0.3f, -hh * 0.58f);
        flame.cubicTo(-wb * 1.05f, -hh * 0.36f, -wb * 0.95f, 0, 0, 0);
        flame.close();
    }

    /** No streak yet: an unlit wick in slate, with the faintest warmth, waiting for today. */
    private void drawColdFlame(Canvas c, float x, float base, float fh) {
        float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 1.4);
        Fx.glow(c, x, base - fh * 0.15f, fh * 0.6f, pulse > 0.5f ? 0x20FF7A2E : 0x10FF7A2E);
        c.save();
        c.translate(x, base);
        flamePath(fh * 0.36f, fh * 0.85f, 0f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF1C2736);
        c.drawPath(flame, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0xFF34445A);
        c.drawPath(flame, paint);
        paint.setStyle(Paint.Style.FILL);
        c.restore();
        paint.setColor(((int) (60 + 80 * pulse) << 24) | 0xFF7A2E);
        c.drawCircle(x, base - dp(2f), dp(2.5f), paint);
    }

    private void drawShield(Canvas c, float x, float y, float r, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        shape.rewind();
        shape.moveTo(x, y - r);
        shape.lineTo(x + r * 0.85f, y - r * 0.65f);
        shape.quadTo(x + r * 0.8f, y + r * 0.5f, x, y + r);
        shape.quadTo(x - r * 0.8f, y + r * 0.5f, x - r * 0.85f, y - r * 0.65f);
        shape.close();
        c.drawPath(shape, paint);
        paint.setColor(0xCCFFFFFF);
        c.drawRect(x - r * 0.08f, y - r * 0.55f, x + r * 0.08f, y + r * 0.55f, paint);
        c.drawRect(x - r * 0.4f, y - r * 0.15f, x + r * 0.4f, y + r * 0.01f, paint);
    }

    /** A five-pointed star into {@link #shape}. */
    private void star(float x, float y, float outer, float inner) {
        shape.rewind();
        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5;
            float r2 = (k & 1) == 0 ? outer : inner;
            float px = x + (float) Math.cos(a) * r2;
            float py = y + (float) Math.sin(a) * r2;
            if (k == 0) {
                shape.moveTo(px, py);
            } else {
                shape.lineTo(px, py);
            }
        }
        shape.close();
    }

    private void chip(Canvas c, RectF r, String text, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((0x2A << 24) | (color & 0x00FFFFFF));
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(color);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, text, r.centerX(), r.centerY() + dp(5f), 13f, color, Paint.Align.CENTER);
    }

    /** Width of {@code text} at a size; measured with the bold paint, so it bounds a label too. */
    private float textWidth(String text, float sizeDp) {
        textPaint.setTextSize(dp(sizeDp));
        return textPaint.measureText(text);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** The target as a sentence for the header and the card. */
    private String targetLine() {
        switch (challenge) {
            case RATE_LADDER: {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rungs; i++) {
                    if (i > 0) {
                        sb.append(" > ");
                    }
                    sb.append(ladderRates[i]);
                }
                return sb + " spm, " + Math.round(RUNG_SECONDS) + " s each  ·  " + Math.round(target) + "% in band";
            }
            case NEG_SPLIT:
                return "2nd 250 m faster by " + targetText(target) + "  ·  500 m under " + clock(negCap);
            default:
                return targetText(target);
        }
    }

    /** The result; a negative split also shows its 500 m time, since the cap can decide it. */
    private String resultText() {
        if (challenge == Challenge.NEG_SPLIT) {
            return targetText(result) + "  ·  500 m in " + clock(negTotal);
        }
        return targetText(result);
    }

    private String targetText(double v) {
        switch (challenge) {
            case DISTANCE_4MIN:
                return Math.round(v) + " m";
            case TRIAL_500:
                return clock(v);
            case POWER_10:
                return Math.round(v) + " W";
            case RATE_LADDER:
                return Math.round(v) + "% in band";
            case NEG_SPLIT:
                return (v >= 0 ? "+" : "") + String.format(java.util.Locale.US, "%.1f", v) + " s";
            default:
                return Math.round(v) + "% steady";
        }
    }
}
