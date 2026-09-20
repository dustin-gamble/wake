package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * CREW BOAT: you row in an eight, head to head against another eight over 1000 m.
 *
 * <p>Steady, even strokes pull the crew into time - the oars go in together and the boat surges,
 * which rowers call swing. Uneven strokes break it: the crew catches at different moments, blades
 * clash and splash, the rowers visibly tire, and the boat slows.
 *
 * <p>Three seats. At <b>STROKE</b> the crew follows your rhythm, so changing rate deliberately is
 * fine and only the stroke-to-stroke wobble costs. In the <b>ENGINE ROOM</b> (seat 4) you follow
 * the crew, the timing is forgiven a little, but the boat only moves if you keep pressing harder
 * than your own recent strokes. At <b>BOW</b> you follow the crew on the tightest tolerance there
 * is: the stroke seat sets the rate, changes it through the piece, and you have to go with it.
 *
 * <p>The cox talks to you: calls a POWER TEN (ten strokes that must beat your own recent effort),
 * reacts to your real rate going up or down, and answers the other crew's moves. So do the eight
 * <b>named crewmates</b>, who speak from their own seat about what you are doing to the boat.
 *
 * <p>Four things carry between sessions, all in {@code crew.state} / {@code crew.seatrace}:
 * <ul>
 *   <li><b>Crew morale</b> - earned in a race and spent by a bad one. It is worth up to 6% of boat
 *       speed either way, changes how quickly the crew tires, and sets what the crewmates say.</li>
 *   <li><b>The season calendar</b> - six training days and then a REGATTA over 1500 m against a
 *       stronger crew. Every day you row is ticked off and is worth 1% in the regatta.</li>
 *   <li><b>Seat races</b> - the finishing margin from each seat, normalised per 1000 m, so the
 *       coach (and you) can see which seat you are actually worth more in.</li>
 *   <li><b>Perfect twenties</b> - twenty consecutive strokes in time buys fifteen seconds of a
 *       flying boat, and the longest streak is a record.</li>
 * </ul>
 *
 * <p>Timing comes from the pulse meter's stroke detection, measured at the drive, not a second late
 * off the monitor's counter. Stroke effort for the ten comes from the same stroke record (its
 * measured drive power, or the paddle's peak rate cubed before energy is known), never from the
 * watts register inside onStroke.
 */
final class CrewBoatGame extends GameView {

    private enum Phase { READY, RACING, DONE }

    private enum Seat { STROKE, ENGINE, BOW }

    /** {@code values()} clones its array on every call, and the boathouse redraws at 60 fps. */
    private static final Seat[] SEAT_ORDER = Seat.values();
    /** The calendar's cell captions, so drawing it allocates no strings. */
    private static final String[] DAY_LABELS = {"1", "2", "3", "4", "5", "6", "CUP"};

    private static final int RACE_METERS = 1000;
    private static final int REGATTA_METERS = 1500;
    /** Training days in a season; the regatta is rowed on the day after the last of them. */
    private static final int SEASON_DAYS = 6;
    /** Consecutive strokes in time that buy a flying boat. */
    private static final int PERFECT = 20;
    private static final double SWING_BONUS_SECONDS = 15;
    private static final int SEATS = 8;
    /** An eight is ~17.5 m; a "seat" of margin is an eighth of that, which is how coxes count it. */
    private static final double EIGHT_METRES = 17.5;
    private static final double SEAT_METRES = EIGHT_METRES / SEATS;
    /** A stroke in the ten must beat your own recent average effort by this much to count. */
    private static final double TEN_RATIO = 1.08;
    private static final int PLAYER_CREW = 0xFF3A5BD9;
    private static final int RIVAL_CREW = 0xFFD9453A;
    private static final int[] SHIRTS = {0xFFF0655D, 0xFFF0B132, 0xFF6F8CFF, 0xFFFFFFFF, 0xFF35D0BA};
    /** Points of the course at which the other crew makes a move; the last one runs to the line. */
    private static final float[] RIVAL_MOVES = {0.28f, 0.62f, 0.86f};

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path path = new android.graphics.Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final float[] seatLag = new float[SEATS];
    private final float[] rivalLag = new float[SEATS];
    /** How quickly each of your rowers goes when the timing falls apart: 0 strong, 1 first to fade. */
    private final float[] seatWeak = new float[SEATS];
    /**
     * Oar puddles. Each blade leaves one where it came out of the water, and it drifts astern and
     * spreads as it fades - which is what a crew actually leaves behind, and it lands in the band
     * below the eight that the dead-space survey found emptiest. Drawn with real contrast on
     * purpose: the shared chop sits at alpha 20-50 and is too quiet to carry this much water.
     */
    private static final int PUDDLES = 64;
    private final float[] puddleX = new float[PUDDLES];
    private final float[] puddleY = new float[PUDDLES];
    private final float[] puddleAge = new float[PUDDLES];
    private final boolean[] bladeWasIn = new boolean[SEATS];
    private final boolean[] rivalBladeWasIn = new boolean[SEATS];
    private int puddleHead;
    /** Named `course`, not `scenery`: that name is already taken here by the scrolled metres. */
    private final RiverScenery course;
    private final RectF strokeBtn = new RectF();
    private final RectF engineBtn = new RectF();
    private final RectF bowBtn = new RectF();
    private final RectF panel = new RectF();
    private boolean buttonsShown;
    private android.graphics.LinearGradient skyShader;
    private float skyShaderTop = -1f;

    private Seat seat = Seat.STROKE;
    private Phase phase = Phase.READY;
    private double raceStart;
    private double yourMeters;
    private double rivalMeters;
    private double finishTime;
    private boolean won;
    private boolean newBest;
    private float sync = 0.5f;
    private float syncSum;
    private int syncStrokes;
    private int swing;
    /** Seeded so a frame drawn before {@link #onStart()} cannot divide by zero. */
    private double crewInterval = 60.0 / 25.0;
    private double lastStrokeAt = -1;
    private double yourInterval;
    private PulseMeter.Stroke lastSeen;
    /** False until the first reading after start: a stroke already in the meter is history, not a start. */
    private boolean seeded;
    private double scenery;

    // Cox.
    private String coxCall = "";
    private double coxUntil;
    private int coxPriority;
    private int strokesSinceCall;
    private String rivalCall = "";
    private double rivalCallUntil;
    private static final String[] CALLS_GOOD = {"IN TIME!", "SWING IT!", "BEAUTIFUL!", "HOLD THAT RHYTHM!", "SHE'S FLYING!"};
    private static final String[] CALLS_BAD = {"TOGETHER!", "WATCH STROKE!", "FIND THE RHYTHM!", "CATCH TOGETHER!"};
    private static final String[] CALLS_PUSH = {"LEGS, LEGS, LEGS!", "PUSH NOW!", "LONG AND STRONG!", "SQUEEZE!"};

    // Rate reactions (item: cox calls react to your real rate changes).
    private double rateFast;
    private double rateRef;
    private double lastRateCallAt = -99;
    private int timedStrokes;

    // Bow seat: the stroke seat (not you) sets the rhythm.
    private float crewPhase;
    private double crewTargetRate = 25;
    private double nextRateChangeAt;
    private double offsetEma;

    // Power ten.
    private boolean tenActive;
    private int tenStrokes;
    private int tenHits;
    private final boolean[] tenResults = new boolean[10];
    private double tenGapAtStart;
    private int strokesSinceTen;
    private double effortBaseline;
    private int baselineN;
    /** 0 = measured drive power (W), 1 = paddle peak rate cubed, 2 = monitor watts peak. */
    private int baselineKind = -1;
    private double windowPeakWatts;
    private float tenFlash;
    private float pushBoost;
    private int bestTen;

    // The other eight.
    private float rivalSurge;
    private int rivalMoveIndex;
    private double rivalMoveEnd = -1;
    private float rivalPhase;
    private boolean youLead;

    // Fatigue (item: the crew visibly tires when your timing drops).
    private float fatigue;
    private boolean tiredCalled;

    /* ---------- what carries between sessions ---------- */

    /** 0..1. Live during a race and written back on finish and on leaving the screen. */
    private float morale = 0.5f;
    private long seasonStart;
    /** Bit d set = you rowed this crew on day d of the season. Bits 0..5 are the training days. */
    private int seasonDays;
    private int seasonNo = 1;
    private int regattaWins;
    private int dayIndex;
    private int trainingDays;
    private boolean regattaDay;
    private boolean lastWasRegatta;
    private int raceMeters = RACE_METERS;
    private boolean dayMarked;

    // Seat races: finishing margin in metres per 1000 m, per seat.
    private final float[] seatLastMargin = new float[3];
    private final float[] seatBestMargin = new float[3];
    private final int[] seatRaces = new int[3];

    // The perfect twenty.
    private int perfectTwenties;
    private int bestSwing;
    private double swingBonusUntil = -1;
    private float twentyFlash;

    // Engine room: the boat only moves if you press harder than you have been.
    private double effortFast;
    private double effortSlow;
    private float engineDrive;

    // Named crewmates.
    private static final String[] MATE_NAMES =
            {"KAI", "MAYA", "RUBEN", "TESS", "OLLY", "NINA", "BRECK", "JUNO"};
    private static final String[] MATE_ROLE =
            {"stroke", "7 · spark", "6 · engine", "5 · engine", "4 · engine", "3 · engine", "2 · bow pair", "bow · steers"};
    private static final String[] MATE_GOOD =
            {"THAT'S THE RHYTHM!", "I CAN FEEL HER RUNNING!", "YES - KEEP IT THERE!", "GLUED TO YOU!"};
    private static final String[] MATE_BAD =
            {"I'M CATCHING EARLY ON YOU!", "FIND ME - I'M RIGHT HERE!", "WE'RE ALL OVER THE PLACE!", "EASY ON THE SLIDE!"};
    private static final String[] MATE_TIRED =
            {"LEGS ARE GOING...", "GIVE ME SOMETHING!", "I'M HANGING ON!", "I'M COOKED BACK HERE!"};
    private static final String[] MATE_TEN =
            {"TEN WITH YOU!", "SENDING IT!", "EVERY ONE OF THEM!"};
    private static final String[] MATE_TWENTY =
            {"TWENTY PERFECT - WE'RE GONE!", "BEST WE'VE EVER ROWED!", "DON'T STOP THAT!"};
    private static final String[] MATE_LEAD = {"WE'VE GOT THEIR BOW!", "I CAN SEE THEIR COX!"};
    private static final String[] MATE_BEHIND = {"THEY'RE WALKING - ANSWER IT!", "DON'T LET THEM GO!"};
    private static final String[] MATE_UP = {"BEEN WAITING FOR THIS ONE!", "LET'S HAVE THEM TODAY!"};
    private static final String[] MATE_FLAT = {"...LET'S JUST GET THROUGH IT.", "TRY NOT TO CRAB IT TODAY."};
    private static final String[] MATE_WIN = {"WELL ROWED, CREW!", "THAT'S OURS!"};
    private static final String[] MATE_LOSE = {"WE'LL GET THEM NEXT TIME.", "THAT ONE HURTS."};
    private int mateSeat = -1;
    private String mateText = "";
    private double mateUntil;
    private double mateCooldown;
    /**
     * The HUD's changing captions, rebuilt only when the number in them changes. The tablet
     * redraws at 60 fps: a concatenation here is ~250 short-lived strings a second for nothing.
     */
    private String syncLabel = "";
    private int syncLabelKey = Integer.MIN_VALUE;
    private String swingLabel = "";
    private int swingLabelKey = Integer.MIN_VALUE;
    private String rateLabel = "";
    private int rateLabelKey = Integer.MIN_VALUE;
    private String seasonLabel = "";
    private int seasonLabelKey = Integer.MIN_VALUE;
    private String twentyLabel = "";
    private int twentyLabelKey = Integer.MIN_VALUE;

    /** Where the eight was last drawn, so a crewmate's bubble points at their own seat. */
    private float eightCx;
    private float eightLen;
    private float eightYNow;

    CrewBoatGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        this.course = new RiverScenery(getResources().getDisplayMetrics().density);
        java.util.Random r = new java.util.Random(8);
        for (int i = 1; i < SEATS; i++) {
            seatLag[i] = (r.nextFloat() - 0.5f) * 2f;
        }
        for (int i = 0; i < SEATS; i++) {
            rivalLag[i] = (r.nextFloat() - 0.5f) * 2f;
            seatWeak[i] = r.nextFloat();
        }
        String saved = bests.getString("crew.seat");
        if (saved != null) {
            for (Seat s : SEAT_ORDER) {
                if (s.name().equals(saved)) {
                    seat = s;
                }
            }
        }
        loadState();
        loadSeatRaces();
    }

    /* =====================================================================
     * What carries between sessions
     * ===================================================================== */

    /** Local day number, the same arithmetic the regatta and the daily row use. */
    private static long today() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    /**
     * Morale and the season calendar. Kept as a string on purpose: the Records screen lists every
     * float it finds, and a day number or a morale fraction is state, not an achievement.
     */
    private void loadState() {
        String s = bests.getString("crew.state");
        boolean ok = false;
        if (s != null) {
            String[] p = s.split("\\|");
            if (p.length >= 6 && "1".equals(p[0])) {
                try {
                    morale = clamp01(Float.parseFloat(p[1]));
                    seasonStart = Long.parseLong(p[2]);
                    seasonDays = Integer.parseInt(p[3]);
                    seasonNo = Math.max(1, Integer.parseInt(p[4]));
                    regattaWins = Math.max(0, Integer.parseInt(p[5]));
                    ok = seasonStart > 0;
                } catch (NumberFormatException e) {
                    ok = false;
                }
            }
        }
        if (!ok) {
            morale = 0.5f;
            seasonStart = today();
            seasonDays = 0;
        }
    }

    private void saveState() {
        bests.putString("crew.state", String.format(java.util.Locale.US, "1|%.3f|%d|%d|%d|%d",
                morale, seasonStart, seasonDays, seasonNo, regattaWins));
    }

    /** Per-seat finishing margins, normalised per 1000 m so the regatta compares with a training row. */
    private void loadSeatRaces() {
        String s = bests.getString("crew.seatrace");
        if (s == null) {
            return;
        }
        String[] rows = s.split(";");
        for (String row : rows) {
            String[] p = row.split(",");
            if (p.length != 4) {
                continue;
            }
            int i = seatIndex(p[0]);
            if (i < 0) {
                continue;
            }
            try {
                seatLastMargin[i] = Float.parseFloat(p[1]);
                seatBestMargin[i] = Float.parseFloat(p[2]);
                seatRaces[i] = Math.max(0, Integer.parseInt(p[3]));
            } catch (NumberFormatException e) {
                seatRaces[i] = 0;
            }
        }
    }

    private void saveSeatRaces() {
        StringBuilder sb = new StringBuilder();
        for (Seat s : SEAT_ORDER) {
            int i = s.ordinal();
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(s.name()).append(',').append(String.format(java.util.Locale.US, "%.2f,%.2f,%d",
                    seatLastMargin[i], seatBestMargin[i], seatRaces[i]));
        }
        bests.putString("crew.seatrace", sb.toString());
    }

    private static int seatIndex(String name) {
        for (Seat s : SEAT_ORDER) {
            if (s.name().equals(name)) {
                return s.ordinal();
            }
        }
        return -1;
    }

    private static float clamp01(float v) {
        // A NaN that reached morale would be written back to prefs, reloaded as NaN, and every
        // later race would have a NaN boat speed that never reaches the finish.
        if (Float.isNaN(v)) {
            return 0.5f;
        }
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }

    private void addMorale(float delta) {
        morale = clamp01(morale + delta);
    }

    private static String moraleWord(float m) {
        return m < 0.2f ? "MUTINOUS" : m < 0.4f ? "FLAT" : m < 0.6f ? "STEADY"
                : m < 0.8f ? "UP FOR IT" : "FIRED UP";
    }

    /** True in the seats where the stroke seat, not you, owns the rhythm. */
    private boolean followsCrew() {
        return seat != Seat.STROKE;
    }

    /** Which of the eight you are sitting in: stern-most is 0. */
    private int youSeatIndex() {
        return seat == Seat.STROKE ? 0 : seat == Seat.ENGINE ? 4 : SEATS - 1;
    }

    @Override
    protected void onStart() {
        rollSeason();
        perfectTwenties = 0;
        bestSwing = 0;
        swingBonusUntil = -1;
        twentyFlash = 0;
        effortFast = 0;
        effortSlow = 0;
        engineDrive = 0;
        dayMarked = false;
        lastWasRegatta = false;
        mateUntil = 0;
        mateCooldown = 0;
        mateSeat = -1;
        phase = Phase.READY;
        yourMeters = 0;
        rivalMeters = 0;
        sync = 0.5f;
        syncSum = 0;
        syncStrokes = 0;
        swing = 0;
        crewTargetRate = Math.max(14, profile.typicalRate());
        crewInterval = 60.0 / crewTargetRate;
        yourInterval = crewInterval;
        lastStrokeAt = -1;
        seeded = false;
        newBest = false;
        coxUntil = 0;
        coxPriority = 0;
        rivalCallUntil = 0;
        strokesSinceCall = 0;
        rateFast = 0;
        rateRef = 0;
        lastRateCallAt = -99;
        timedStrokes = 0;
        crewPhase = 0;
        offsetEma = 0;
        nextRateChangeAt = 0;
        tenActive = false;
        tenStrokes = 0;
        tenHits = 0;
        strokesSinceTen = 0;
        effortBaseline = 0;
        baselineN = 0;
        baselineKind = -1;
        windowPeakWatts = 0;
        tenFlash = 0;
        pushBoost = 0;
        bestTen = 0;
        rivalSurge = 0;
        rivalMoveIndex = 0;
        rivalMoveEnd = -1;
        rivalPhase = 0;
        youLead = false;
        fatigue = 0;
        tiredCalled = false;
    }

    /**
     * Where the season stands today. Six training days, then the regatta, which stays due until it
     * is rowed. Two weeks past due and the crew has drifted apart: a new season, and morale returns
     * halfway to level.
     */
    private void rollSeason() {
        long t = today();
        if (seasonStart <= 0 || t < seasonStart) {
            seasonStart = t;
            seasonDays = 0;
        }
        long since = t - seasonStart;
        if (since > SEASON_DAYS + 13) {
            seasonNo++;
            seasonStart = t;
            seasonDays = 0;
            since = 0;
            morale += (0.5f - morale) * 0.5f;
            saveState();
        }
        dayIndex = (int) Math.min(SEASON_DAYS, since);
        regattaDay = since >= SEASON_DAYS;
        raceMeters = regattaDay ? REGATTA_METERS : RACE_METERS;
        trainingDays = Integer.bitCount(seasonDays & 0x3F);
    }

    /** One tick on the calendar for today, the moment the row is worth calling training. */
    private void markTrainedToday() {
        if (dayMarked) {
            return;
        }
        dayMarked = true;
        seasonDays |= 1 << Math.min(SEASON_DAYS, dayIndex);
        trainingDays = Integer.bitCount(seasonDays & 0x3F);
        saveState();
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        windowPeakWatts = Math.max(windowPeakWatts, s.watts);
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (!seeded) {
            // Opening the game after rowing elsewhere leaves an old stroke in the meter; it must
            // not fire the start.
            seeded = true;
            lastSeen = stroke;
            return;
        }
        if (stroke != null && stroke != lastSeen) {
            lastSeen = stroke;
            // Effort is a property of the stroke record, measured through the drive itself.
            if (!Double.isNaN(stroke.averagePowerW) && stroke.averagePowerW > 0) {
                strokeLanded(0, stroke.averagePowerW);
            } else {
                strokeLanded(1, stroke.peakRate * stroke.peakRate * stroke.peakRate);
            }
        }
    }

    @Override
    protected void onStroke(int watts) {
        // Fallback only when the pulse meter has not detected strokes; the effort is the peak
        // monitor power seen since the last stroke, not the collapsed value passed in here.
        if (status != null && status.meter.strokes == 0) {
            strokeLanded(2, windowPeakWatts);
        }
    }

    private void strokeLanded(int effortKind, double effort) {
        windowPeakWatts = 0;
        double now = sessionSeconds;
        boolean resumed = lastStrokeAt < 0 || now - lastStrokeAt > crewInterval * 2.2;
        if (phase == Phase.READY) {
            phase = Phase.RACING;
            raceStart = sessionSeconds;
            nextRateChangeAt = sessionSeconds + 30;
            String off = regattaDay ? "THIS IS THE ONE - ATTENTION... GO!"
                    : seat == Seat.STROKE ? "ATTENTION... GO! THEY'RE ON YOU, STROKE!"
                    : "ATTENTION... GO! FOLLOW STROKE, " + seatWord() + "!";
            say(off, 2.4, 1);
            rivalCall("GO! GO! GO!", 1.8);
            mateSay(morale >= 0.55f ? MATE_UP : MATE_FLAT, 2.6);
        }
        if (followsCrew() && resumed) {
            // After a stop the crew waits for you and comes forward together.
            crewPhase = 0f;
        }
        if (lastStrokeAt >= 0 && phase == Phase.RACING) {
            double interval = now - lastStrokeAt;
            if (interval > 0.8 && interval < 8) {
                yourInterval += (interval - yourInterval) * 0.5;
                double dev;
                double tol;
                if (seat == Seat.STROKE) {
                    // The crew follows you: only the wobble between strokes costs.
                    dev = Math.abs(interval - crewInterval) / crewInterval;
                    tol = 0.12;
                    crewInterval += (interval - crewInterval) * 0.25;
                } else {
                    // You follow the crew: your rate against theirs, and where you catch in their cycle.
                    // A constant detection delay lands in offsetEma and cancels; drift does not.
                    float p = crewPhase;
                    double off = p > 0.5f ? p - 1f : p;
                    offsetEma += (off - offsetEma) * 0.2;
                    double intervalDev = Math.abs(interval - crewInterval) / crewInterval;
                    // The engine room sits in the middle of the boat where a fraction of a second is
                    // forgiven; the bow seat is where it is not.
                    dev = 0.6 * intervalDev + 0.4 * Math.abs(off - offsetEma);
                    tol = seat == Seat.ENGINE ? 0.14 : 0.10;
                    // The crew gives a little: a bow seat can nudge a boat, not steer it.
                    crewPhase -= (float) (off - offsetEma) * 0.15f;
                }
                float target = (float) Math.max(0, Math.min(1, 1 - dev / tol));
                sync += (target - sync) * 0.35f;
                int wasSwing = swing;
                swing = sync > 0.85f ? swing + 1 : 0;
                if (swing > bestSwing) {
                    bestSwing = swing;
                }
                if (swing > 0 && swing % PERFECT == 0) {
                    awardTwenty();
                } else if (wasSwing >= PERFECT && swing == 0) {
                    mateSay(MATE_BAD, 2.2);
                }
                syncSum += sync;
                syncStrokes++;
                if (sync < 0.5f) {
                    fx.burst(getWidth() * 0.5f, getHeight() * 0.62f, 26, dp(160f), 0.6f, dp(3f), 0xDDBFE3FF, true);
                    if (Math.random() < 0.35) {
                        mateSay(MATE_BAD, 2.2);
                    }
                } else if (sync > 0.9f && Math.random() < 0.10) {
                    mateSay(MATE_GOOD, 2.0);
                }
                updateFatigue();
                reactToRate(60.0 / interval);
            }
        }
        lastStrokeAt = now;
        trackEngine(effortKind, effort);
        if (phase == Phase.RACING) {
            powerTen(effortKind, effort);
            if (++strokesSinceCall >= 4) {
                strokesSinceCall = 0;
                String[] calls = sync > 0.8f ? CALLS_GOOD : sync < 0.5f ? CALLS_BAD : CALLS_PUSH;
                say(calls[(int) (Math.random() * calls.length)], 2.2, 0);
            }
        }
        // Every catch throws water, more of it when the crew is ragged.
        fx.burst(getWidth() * 0.5f, getHeight() * 0.70f, sync > 0.8f ? 10 : 22, dp(120f), 0.5f, dp(2.5f), 0xCCBFE3FF, true);
    }

    /**
     * The engine room's contribution: how hard this stroke was against your own recent strokes.
     * Relative on purpose - it reads the same whether effort is measured watts or paddle rate cubed,
     * and it cannot be gamed by a rower who is simply strong.
     */
    private void trackEngine(int kind, double effort) {
        if (!(effort > 0)) {
            return;
        }
        if (kind != baselineKind || effortSlow <= 0) {
            effortSlow = effort;
            effortFast = effort;
            return;
        }
        effortFast += (effort - effortFast) * 0.4;
        effortSlow += (effort - effortSlow) * 0.06;
        double ratio = effortFast / Math.max(1e-6, effortSlow);
        engineDrive = (float) Math.max(-1, Math.min(1, (ratio - 1) * 6));
    }

    /** Twenty strokes in a row in time: the boat lifts, the crew lifts, and it is a record. */
    private void awardTwenty() {
        perfectTwenties++;
        swingBonusUntil = sessionSeconds + SWING_BONUS_SECONDS;
        twentyFlash = 1f;
        addMorale(0.06f);
        say(perfectTwenties == 1 ? "PERFECT TWENTY! SHE'S FLYING - HOLD IT!"
                : "TWENTY AGAIN! THAT'S " + perfectTwenties + " - STAY ON IT!", 3.0, 3);
        mateSay(MATE_TWENTY, 2.6);
        rivalCall("THEY'VE GOT SWING - GO WITH THEM!", 2.0);
        fx.burst(getWidth() * 0.5f, getHeight() * 0.55f, 46, dp(300f), 1.0f, dp(4f), 0xFFF0B132, true);
        fx.burst(getWidth() * 0.5f, getHeight() * 0.62f, 26, dp(220f), 0.9f, dp(3f), 0xFF35D0BA, true);
    }

    /** One of the eight speaks, from their own seat. Never you, and never over the last one. */
    private void mateSay(String[] pool, double seconds) {
        if (sessionSeconds < mateCooldown || pool.length == 0) {
            return;
        }
        int you = youSeatIndex();
        int i = (int) (Math.random() * SEATS);
        if (i == you) {
            i = (i + 1 + (int) (Math.random() * (SEATS - 1))) % SEATS;
        }
        mateSeat = i;
        // Composed once here, never in the frame loop.
        mateText = MATE_NAMES[i] + ": " + pool[(int) (Math.random() * pool.length)];
        mateUntil = sessionSeconds + seconds;
        mateCooldown = sessionSeconds + seconds + 1.6;
    }

    private String seatWord() {
        return seat == Seat.STROKE ? "STROKE" : seat == Seat.ENGINE ? "FOUR SEAT" : "BOW";
    }

    /** Timing falling apart wears the crew down; rowing in time brings them back. */
    private void updateFatigue() {
        if (sync < 0.6f) {
            // A crew that believes in you keeps going for longer on the same ragged strokes.
            fatigue += (0.6f - sync) * 0.18f * (1.25f - 0.5f * morale);
        } else if (sync > 0.8f) {
            fatigue -= 0.035f * (0.8f + 0.4f * morale);
        }
        fatigue = Math.max(0f, Math.min(1f, fatigue));
        if (fatigue > 0.7f) {
            addMorale(-0.004f);
        }
        if (!tiredCalled && fatigue > 0.5f) {
            tiredCalled = true;
            say("THEY'RE TIRING - SETTLE IT, FIND THE RHYTHM!", 2.6, 2);
            rivalCall("THEY'RE FALLING APART - GO!", 2.0);
            mateSay(MATE_TIRED, 2.6);
        } else if (tiredCalled && fatigue < 0.2f) {
            tiredCalled = false;
            say("THAT'S IT - THEY'RE BACK WITH YOU!", 2.4, 2);
            mateSay(MATE_GOOD, 2.2);
        }
    }

    /** The cox hears the rate change on the very stroke it happens, and says the number. */
    private void reactToRate(double rate) {
        timedStrokes++;
        rateFast = rateFast <= 0 ? rate : rateFast + (rate - rateFast) * 0.5;
        if (rateRef <= 0) {
            rateRef = rateFast;
        }
        boolean cooled = sessionSeconds - lastRateCallAt > 6;
        int now = (int) Math.round(rateFast);
        if (timedStrokes >= 4 && cooled) {
            if (followsCrew()) {
                int crew = (int) Math.round(60.0 / crewInterval);
                double diff = rateFast - 60.0 / crewInterval;
                if (diff >= 1.5) {
                    say(String.format(java.util.Locale.US, "%s, YOU'RE RUSHING - %d, NOT %d!", seatWord(), now, crew), 2.6, 2);
                    lastRateCallAt = sessionSeconds;
                } else if (diff <= -1.5) {
                    say(String.format(java.util.Locale.US, "%s, YOU'RE LATE - UP TO %d!", seatWord(), crew), 2.6, 2);
                    lastRateCallAt = sessionSeconds;
                }
            } else {
                double diff = rateFast - rateRef;
                if (diff >= 1.6) {
                    String text = tenActive ? "UP TO %d - THAT'S THE TEN!"
                            : sync > 0.7f ? "UP TO %d - AND STILL TOGETHER!" : "UP TO %d - DON'T RUSH THE SLIDE!";
                    say(String.format(java.util.Locale.US, text, now), 2.6, 2);
                    rateRef = rateFast;
                    lastRateCallAt = sessionSeconds;
                } else if (diff <= -1.6) {
                    boolean pressed = rivalSurge > 0.3f || rivalMeters > yourMeters;
                    String text = pressed ? "DOWN TO %d - THEY'RE GOING, BRING IT UP!" : "DOWN TO %d - LONG AND STRONG, GOOD";
                    say(String.format(java.util.Locale.US, text, now), 2.6, 2);
                    rateRef = rateFast;
                    lastRateCallAt = sessionSeconds;
                }
            }
        }
        rateRef += (rateFast - rateRef) * 0.06;
    }

    /** A power ten: ten strokes, each measured against your own recent effort. */
    private void powerTen(int kind, double effort) {
        if (kind != baselineKind) {
            // A different measure (energy became available mid-race) cannot share a baseline.
            baselineKind = kind;
            effortBaseline = 0;
            baselineN = 0;
            if (tenActive) {
                tenActive = false;
                strokesSinceTen = 20;
            }
        }
        if (!(effort > 0)) {
            return;
        }
        if (tenActive) {
            boolean hit = effort >= effortBaseline * TEN_RATIO;
            tenResults[tenStrokes++] = hit;
            if (hit) {
                tenHits++;
                pushBoost = 0.12f;
                tenFlash = 1f;
                fx.burst(getWidth() * 0.5f, getHeight() * 0.66f, 18, dp(200f), 0.6f, dp(3f), 0xFFF0B132, true);
                if (tenStrokes == 4 || tenStrokes == 8) {
                    mateSay(MATE_TEN, 2.0);
                }
            }
            if (tenStrokes >= 10) {
                tenActive = false;
                strokesSinceTen = 0;
                bestTen = Math.max(bestTen, tenHits);
                double gained = (yourMeters - rivalMeters) - tenGapAtStart;
                String text = tenHits >= 7
                        ? String.format(java.util.Locale.US, "WHAT A TEN! %d OF 10  ·  %+.1f SEATS", tenHits, gained / SEAT_METRES)
                        : String.format(java.util.Locale.US, "%d OF 10 - WE NEEDED MORE THAN THAT", tenHits);
                say(text, 3.0, 3);
                if (tenHits >= 7) {
                    rivalCall("HOLD THEM! HOLD!", 2.0);
                    addMorale(0.05f);
                    mateSay(MATE_GOOD, 2.2);
                } else if (tenHits <= 3) {
                    addMorale(-0.04f);
                    mateSay(MATE_TIRED, 2.2);
                }
            }
            return;
        }
        effortBaseline = baselineN == 0 ? effort : effortBaseline + (effort - effortBaseline) * 0.2;
        baselineN++;
        strokesSinceTen++;
        if (baselineN >= 6 && strokesSinceTen >= 30 && yourMeters < raceMeters - 60) {
            startTen("POWER TEN - ON THIS ONE!");
        }
    }

    private void startTen(String call) {
        tenActive = true;
        tenStrokes = 0;
        tenHits = 0;
        tenGapAtStart = yourMeters - rivalMeters;
        say(call, 2.6, 3);
    }

    private void say(String text, double seconds, int priority) {
        if (sessionSeconds < coxUntil && priority < coxPriority) {
            return;
        }
        coxCall = text;
        coxUntil = sessionSeconds + seconds;
        coxPriority = priority;
    }

    private void rivalCall(String text, double seconds) {
        rivalCall = text;
        rivalCallUntil = sessionSeconds + seconds;
    }

    private void chooseSeat(Seat s) {
        seat = s;
        bests.putString("crew.seat", s.name());
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && phase != Phase.RACING) {
            if (buttonsShown && strokeBtn.contains(e.getX(), e.getY())) {
                chooseSeat(Seat.STROKE);
                if (phase == Phase.DONE) {
                    start();
                }
                return true;
            }
            if (buttonsShown && engineBtn.contains(e.getX(), e.getY())) {
                chooseSeat(Seat.ENGINE);
                if (phase == Phase.DONE) {
                    start();
                }
                return true;
            }
            if (buttonsShown && bowBtn.contains(e.getX(), e.getY())) {
                chooseSeat(Seat.BOW);
                if (phase == Phase.DONE) {
                    start();
                }
                return true;
            }
            if (phase == Phase.DONE) {
                start();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        fx.step(dt, dp(260f));
        float speed = boat.value();
        double sinceStroke = lastStrokeAt < 0 ? 99 : sessionSeconds - lastStrokeAt;
        boolean rowing = sinceStroke < crewInterval * 2.2;
        if (!rowing) {
            sync = Math.max(0f, sync - dt * 0.08f);
            swing = 0;
            // A crew sitting easy gets its breath back.
            fatigue = Math.max(0f, fatigue - dt * 0.01f);
        }
        pushBoost = Math.max(0f, pushBoost - dt * 0.06f);
        tenFlash = Math.max(0f, tenFlash - dt * 2f);
        twentyFlash = Math.max(0f, twentyFlash - dt * 0.6f);
        if (!rowing) {
            engineDrive -= engineDrive * Math.min(1f, dt * 0.6f);
        }

        if (followsCrew()) {
            // The stroke seat sets the rhythm; the crew only moves while you are rowing with them.
            if (phase == Phase.RACING && rowing) {
                crewPhase += (float) (dt / crewInterval);
                crewPhase -= (float) Math.floor(crewPhase);
            }
            crewInterval += (60.0 / crewTargetRate - crewInterval) * Math.min(1.0, dt * 0.5);
            if (phase == Phase.RACING && sessionSeconds >= nextRateChangeAt) {
                double typical = Math.max(14, profile.typicalRate());
                int[] steps = RATE_STEPS;
                double next = crewTargetRate + steps[(int) (Math.random() * steps.length)];
                next = Math.max(typical - 3, Math.min(typical + 3, next));
                if (Math.round(next) != Math.round(crewTargetRate)) {
                    say(String.format(java.util.Locale.US, next > crewTargetRate
                            ? "STROKE'S TAKING IT UP TO %d - GO WITH HER!" : "STROKE'S BRINGING IT DOWN TO %d - LENGTHEN!",
                            Math.round(next)), 2.8, 2);
                }
                crewTargetRate = next;
                nextRateChangeAt = sessionSeconds + 26 + Math.random() * 12;
            }
        }

        // Sync is worth 15% either way, swing adds a little more, and a tired crew gives some back.
        // Morale is worth 6% either way; a perfect twenty is worth 8% for fifteen seconds; in the
        // engine room what you add on top of your own recent strokes is worth up to 7%; and on
        // regatta day every training day ticked off this season is worth another 1%.
        float swingBonus = sessionSeconds < swingBonusUntil ? 0.08f : 0f;
        float moraleBonus = 0.12f * (morale - 0.5f);
        float engineBonus = seat == Seat.ENGINE ? 0.07f * engineDrive : 0f;
        float trainingBonus = regattaDay ? 0.01f * trainingDays : 0f;
        float factor = 0.85f + 0.3f * sync + (swing >= 6 ? 0.05f : 0f) - 0.10f * fatigue + pushBoost
                + swingBonus + moraleBonus + engineBonus + trainingBonus;
        if (phase == Phase.RACING) {
            yourMeters += speed * factor * dt;
            stepRival(dt);
            if (!dayMarked && yourMeters > 400) {
                markTrainedToday();
            }
            boolean lead = yourMeters > rivalMeters;
            if (lead != youLead && Math.abs(yourMeters - rivalMeters) > 1) {
                youLead = lead;
                say(lead ? "WE'VE GOT THEIR BOW - KEEP GOING!" : "THEY'RE THROUGH US - RESPOND!", 2.4, 2);
                rivalCall(lead ? "DON'T LET THEM GO!" : "WE'RE THROUGH! AGAIN!", 1.8);
                mateSay(lead ? MATE_LEAD : MATE_BEHIND, 2.2);
                addMorale(lead ? 0.02f : -0.02f);
            }
            if (yourMeters >= raceMeters) {
                finishRace();
                fx.burst(w * 0.5f, h * 0.5f, won ? 60 : 24, dp(320f), 1.2f, dp(4f),
                        won ? 0xFFF0B132 : 0x99BFE3FF, true);
            }
        }
        // Sweat off a tired crew.
        if (fatigue > 0.35f && rowing && Math.random() < dt * fatigue * 6) {
            int i = (int) (Math.random() * SEATS);
            float len = w * 0.62f;
            float sx = w * 0.5f + len * 0.36f - i * len * 0.8f / SEATS;
            float sy = (h * 0.30f) + (h * 0.58f) * 0.62f - dp(52f);
            fx.spawn(sx, sy, (float) (Math.random() - 0.5) * dp(40f), -dp(40f), 0.7f, dp(2f), 0xDD9FD8FF, true);
        }

        float waterTop = h * 0.30f;
        float waterBottom = h * 0.88f;
        float ppm = w / 70f;
        float moving = phase == Phase.RACING ? speed * factor : 0f;
        scenery += moving * dt;
        drawBank(c, w, h, waterTop, ppm);
        river.advance(moving, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        drawNearShore(c, w, h, waterBottom, ppm);
        drawBuoys(c, w, waterTop, waterBottom, ppm);
        // The water below the eight was the emptiest band on any screen surveyed.
        course.drawWaterLife(c, w, waterTop, waterBottom, scenery, ppm, sessionSeconds);
        drawPuddles(c, dt, ppm, moving);

        // The other eight in the far lane, placed by the gap, drawn smaller for distance.
        double gap = yourMeters - rivalMeters;
        float rivalLen = w * 0.62f * 0.72f;
        float rawRivalX = w * 0.5f - (float) gap * ppm;
        float rivalX = Math.max(-rivalLen * 0.3f, Math.min(w + rivalLen * 0.3f, rawRivalX));
        float rivalY = waterTop + (waterBottom - waterTop) * 0.2f;
        if (phase == Phase.RACING) {
            float rate = (float) (Math.max(14, profile.typicalRate()) + 2.5f * rivalSurge);
            rivalPhase += dt * rate / 60f;
            rivalPhase -= (float) Math.floor(rivalPhase);
        }
        if (rivalSurge > 0.2f) {
            Fx.glow(c, rivalX, rivalY, rivalLen * 0.45f, 0x22F0655D);
        }
        drawEight(c, rivalX, rivalLen, rivalY, 0.72f, RIVAL_CREW, 0xFF8A2E2E, -1, 0.9f, rivalLag,
                rivalPhase, -1f, 0f, rivalBladeWasIn, false);
        if (rawRivalX != rivalX) {
            // Off screen: an arrow at the edge with the margin, so the race never disappears.
            boolean ahead = rawRivalX > rivalX;
            float ax = ahead ? w - dp(24f) : dp(24f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xCC0A1420);
            c.drawRoundRect(ax - dp(20f), rivalY - dp(46f), ax + dp(20f), rivalY + dp(8f), dp(8f), dp(8f), paint);
            paint.setColor(RIVAL_CREW);
            path.reset();
            float dir = ahead ? 1f : -1f;
            path.moveTo(ax + dir * dp(12f), rivalY - dp(26f));
            path.lineTo(ax - dir * dp(8f), rivalY - dp(38f));
            path.lineTo(ax - dir * dp(8f), rivalY - dp(14f));
            path.close();
            c.drawPath(path, paint);
            bold(c, String.valueOf(Math.round(Math.abs(gap))) + "m", ax, rivalY + dp(4f), 10f, TEXT, Paint.Align.CENTER);
        }

        float eightY = waterTop + (waterBottom - waterTop) * 0.62f;
        if (swing >= 6) {
            // Swing: a glowing wake and streaks - the boat running away underneath the crew.
            paint.setStrokeWidth(dp(3f));
            for (int k = 0; k < 7; k++) {
                float sx = (float) ((w * 0.8f - (sessionSeconds * dp(420f) + k * dp(160f)) % (w * 1.2f)));
                paint.setColor(0x6635D0BA);
                c.drawLine(sx, eightY + dp(24f) + k * dp(6f), sx - dp(90f), eightY + dp(24f) + k * dp(6f), paint);
            }
            Fx.glow(c, w * 0.5f, eightY, w * 0.35f, 0x2235D0BA);
        }
        if (tenActive) {
            Fx.glow(c, w * 0.5f, eightY, w * 0.3f, tenFlash > 0.3f ? 0x44F0B132 : 0x22F0B132);
        }
        if (sessionSeconds < swingBonusUntil) {
            // The perfect twenty's reward, under the boat where it cannot be missed.
            Fx.glow(c, w * 0.5f, eightY, w * 0.34f, 0x33F0B132);
        }
        float basePhase;
        float youPhase;
        int youSeat;
        if (seat == Seat.STROKE) {
            // Hold at the catch once a stroke is overdue, so the crew waits for you instead of
            // rowing on by itself after you stop.
            basePhase = (float) Math.min(0.98, sinceStroke / crewInterval);
            youPhase = -1f;
            youSeat = 0;
        } else {
            basePhase = crewPhase;
            youPhase = (float) Math.min(0.98, sinceStroke / Math.max(0.8, yourInterval));
            youSeat = youSeatIndex();
        }
        eightCx = w * 0.5f;
        eightLen = w * 0.62f;
        eightYNow = eightY;
        drawEight(c, w * 0.5f, w * 0.62f, eightY, 1f, PLAYER_CREW, 0xFF2F6E93, youSeat, sync, seatLag,
                basePhase, youPhase, fatigue, bladeWasIn, true);
        fx.draw(c);
        if (sessionSeconds < mateUntil && mateSeat >= 0) {
            drawMateBubble(c, w);
        }
        if (swing >= 6) {
            Fx.vignette(c, w, h, 0.25f, 0x1A6A5A);
            if (((int) (sessionSeconds * 3)) % 2 == 0) {
                bold(c, "SWING!", w * 0.5f, eightY - dp(90f), 30f, ACCENT, Paint.Align.CENTER);
            }
        } else if (fatigue > 0.5f) {
            Fx.vignette(c, w, h, 0.2f + 0.2f * fatigue, 0x5A1A1A);
        }
        if (sessionSeconds < rivalCallUntil) {
            drawCoxCall(c, rivalCall, rivalX + rivalLen * 0.46f, rivalY - dp(20f), w, 0xF2FFD9D6, 0xFF5A1410, 13f);
        }
        if (sessionSeconds < coxUntil) {
            drawCoxCall(c, coxCall, w * 0.5f + w * 0.62f * 0.46f, eightY - dp(24f), w, 0xF2FFFFFF, 0xFF3A2A06, 16f);
        }
        double toGo = raceMeters - yourMeters;
        if (phase == Phase.RACING && toGo < 150) {
            float fx0 = w * 0.5f + (float) toGo * ppm;
            if (fx0 < w + dp(40f)) {
                paint.setColor(0xFFF2F2F2);
                c.drawRect(fx0 - dp(3f), waterTop - dp(80f), fx0 + dp(3f), waterBottom, paint);
                for (int k = 0; k < 8; k++) {
                    paint.setColor(k % 2 == 0 ? 0xFFF0655D : 0xFFFFFFFF);
                    c.drawRect(fx0 + dp(3f), waterTop - dp(80f) + k * dp(8f), fx0 + dp(60f), waterTop - dp(72f) + k * dp(8f), paint);
                }
                bold(c, "FINISH", fx0 + dp(32f), waterTop - dp(88f), 12f, TEXT, Paint.Align.CENTER);
            }
        }
        drawHud(c, w, h, gap);
    }

    private static final int[] RATE_STEPS = {-2, -1, 1, 2};

    /**
     * The line: records, the seat race, the crew's morale and the season's calendar all settle here.
     * Margins are stored per 1000 m so a 1500 m regatta is comparable with a training piece.
     */
    private void finishRace() {
        phase = Phase.DONE;
        tenActive = false;
        finishTime = sessionSeconds - raceStart;
        double margin = yourMeters - rivalMeters;
        won = margin >= 0;
        lastWasRegatta = regattaDay;
        markTrainedToday();

        String key = seat == Seat.STROKE ? "crew.time." + raceMeters
                : "crew.time." + seat.name().toLowerCase(java.util.Locale.US) + "." + raceMeters;
        newBest = bests.recordLowest(key, (float) finishTime);
        if (syncStrokes > 10) {
            bests.recordHighest("crew.sync", 100f * syncSum / syncStrokes);
        }
        if (bestSwing > 0) {
            bests.recordHighest("crew.swing", bestSwing);
        }

        float per1000 = (float) (margin * 1000.0 / raceMeters);
        int si = seat.ordinal();
        if (seatRaces[si] == 0 || per1000 > seatBestMargin[si]) {
            seatBestMargin[si] = per1000;
        }
        seatLastMargin[si] = per1000;
        seatRaces[si]++;
        saveSeatRaces();
        bests.recordHighest("crew.margin." + seat.name(), per1000);

        addMorale(won ? (lastWasRegatta ? 0.25f : 0.10f) : (lastWasRegatta ? -0.12f : -0.07f));
        if (syncStrokes > 10) {
            addMorale((syncSum / syncStrokes - 0.6f) * 0.15f);
        }
        if (lastWasRegatta) {
            if (won) {
                regattaWins++;
                bests.recordHighest("crew.regatta.wins", regattaWins);
                bests.recordLowest("crew.regatta.time", (float) finishTime);
            }
            // Whatever happened, the season turns over and today counts as day one of the next.
            seasonNo++;
            seasonStart = today();
            seasonDays = 1;
            dayIndex = 0;
            trainingDays = 1;
            regattaDay = false;
            raceMeters = RACE_METERS;
        }
        saveState();

        if (lastWasRegatta) {
            say(won ? "WE'VE WON THE REGATTA! EASY ALL!" : "EASY ALL... THAT WAS OUR REGATTA.", 4, 4);
        } else {
            say(won ? "WE WON IT! EASY ALL!" : "EASY ALL... NEXT TIME.", 4, 4);
        }
        rivalCall(won ? "WELL ROWED." : "YES! WE'VE GOT IT!", 3);
        mateCooldown = 0;
        mateSay(won ? MATE_WIN : MATE_LOSE, 4);
    }

    @Override
    protected void onStop() {
        // Morale carries even out of a race that was never finished - the crew remembers the row.
        if (phase == Phase.RACING) {
            if (yourMeters > 400) {
                markTrainedToday();
            }
            // A twenty rowed in a piece that was abandoned still happened; without this the record
            // only ever lands on a race carried all the way to the line.
            if (bestSwing > 0) {
                bests.recordHighest("crew.swing", bestSwing);
            }
        }
        saveState();
        saveSeatRaces();
    }

    /** The other eight: a steady crew at your typical pace that makes three moves and sprints home. */
    private void stepRival(float dt) {
        if (rivalMoveIndex < RIVAL_MOVES.length && rivalMeters >= RIVAL_MOVES[rivalMoveIndex] * raceMeters) {
            boolean sprint = rivalMoveIndex == RIVAL_MOVES.length - 1;
            rivalMoveEnd = sprint ? raceMeters + 1 : rivalMeters + 70;
            rivalMoveIndex++;
            rivalCall(sprint ? "SPRINT! TAKE IT HOME!" : "MOVE NOW! TEN IN TWO!", 2.2);
            mateSay(MATE_BEHIND, 2.0);
            if (!tenActive && baselineN >= 4 && yourMeters < raceMeters - 60) {
                startTen(sprint ? "THEY'RE SPRINTING - POWER TEN, NOW!" : "THEY'RE MOVING - POWER TEN, NOW!");
            }
        }
        boolean moving = rivalMeters < rivalMoveEnd;
        rivalSurge += ((moving ? 1f : 0f) - rivalSurge) * Math.min(1f, dt * 0.8f);
        // A crew that is well clear eases a little and one well behind digs in, so it stays a race.
        // The regatta field is a class better than the crew you train against.
        double gap = yourMeters - rivalMeters;
        double press = Math.max(-0.03, Math.min(0.03, gap / 400.0));
        double pace = regattaDay ? 1.06 : 1.02;
        rivalMeters += profile.typicalSpeed() * pace * (1 + 0.06 * rivalSurge + press) * dt;
    }

    private void drawHud(Canvas c, float w, float h, double gap) {
        // HUD: sync meter, swing, gap.
        float cx = w / 2f;
        // 3.19.5: the HUD sits on the light sky, so it gets dark pills (seen unreadable on the emulator).
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRoundRect(cx - dp(170f), dp(6f), cx + dp(170f), dp(116f), dp(14f), dp(14f), paint);
        c.drawRoundRect(dp(8f), dp(12f), dp(330f), dp(122f), dp(10f), dp(10f), paint);
        c.drawRoundRect(w - dp(320f), dp(12f), w - dp(8f), dp(64f), dp(10f), dp(10f), paint);
        int syncPct = Math.round(sync * 100);
        if (syncPct != syncLabelKey) {
            syncLabelKey = syncPct;
            syncLabel = syncPct + "%";
        }
        bold(c, syncLabel, cx, dp(44f), 38f, sync > 0.85f ? ACCENT : sync > 0.6f ? WARN : BAD, Paint.Align.CENTER);
        label(c, swing >= 6 ? swingCaption() : "CREW SYNC", cx, dp(62f), 10f,
                swing >= 6 ? ACCENT : FAINT, Paint.Align.CENTER);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(cx - dp(160f), dp(72f), cx + dp(160f), dp(80f), dp(4f), dp(4f), paint);
        paint.setColor(sync > 0.85f ? ACCENT : sync > 0.6f ? WARN : BAD);
        c.drawRoundRect(cx - dp(160f), dp(72f), cx - dp(160f) + dp(320f) * sync, dp(80f), dp(4f), dp(4f), paint);
        drawTwentyTrack(c, cx);

        // Left: seat, rate, the crew's legs, their morale and where the season stands.
        int crewRate = (int) Math.round(Math.max(1e-3, 60 / crewInterval));
        int yourRate = rateFast > 0 ? (int) Math.round(rateFast) : -1;
        int rateKey = (seat.ordinal() * 200 + Math.min(199, Math.max(0, crewRate))) * 200
                + Math.min(199, yourRate + 1);
        if (rateKey != rateLabelKey) {
            rateLabelKey = rateKey;
            rateLabel = seat == Seat.STROKE
                    ? "STROKE SEAT  ·  CREW FOLLOWS YOU  ·  RATE " + crewRate
                    : seatWord() + "  ·  FOLLOW STROKE AT " + crewRate
                            + (yourRate > 0 ? "  ·  YOU " + yourRate : "");
        }
        label(c, rateLabel, dp(16f), dp(30f), 10f, DIM, Paint.Align.LEFT);
        label(c, fatigue > 0.5f ? "CREW TIRING" : "CREW LEGS", dp(16f), dp(52f), 10f, fatigue > 0.5f ? BAD : DIM, Paint.Align.LEFT);
        float barL = dp(120f);
        float barR = dp(318f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, dp(44f), barR, dp(52f), dp(4f), dp(4f), paint);
        float legs = 1f - fatigue;
        paint.setColor(legs > 0.6f ? ACCENT : legs > 0.35f ? WARN : BAD);
        c.drawRoundRect(barL, dp(44f), barL + (barR - barL) * legs, dp(52f), dp(4f), dp(4f), paint);
        // Morale: kept between sessions, moved by this one.
        label(c, "MORALE", dp(16f), dp(76f), 10f, DIM, Paint.Align.LEFT);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, dp(68f), barR, dp(76f), dp(4f), dp(4f), paint);
        paint.setColor(moraleColor());
        c.drawRoundRect(barL, dp(68f), barL + (barR - barL) * morale, dp(76f), dp(4f), dp(4f), paint);
        label(c, moraleWord(morale), barR, dp(64f), 10f, moraleColor(), Paint.Align.RIGHT);
        // The season.
        if (regattaDay) {
            float beat = 0.5f + 0.5f * (float) Math.abs(Math.sin(sessionSeconds * 2.4));
            paint.setColor(0xFFF0B132);
            paint.setAlpha((int) (140 + 110 * beat));
            c.drawRoundRect(dp(14f), dp(86f), dp(324f), dp(114f), dp(8f), dp(8f), paint);
            paint.setAlpha(255);
            bold(c, seasonCaption(), dp(20f), dp(105f), 11f, 0xFF10203A, Paint.Align.LEFT);
        } else {
            label(c, seasonCaption(), dp(16f), dp(98f), 10f, DIM, Paint.Align.LEFT);
            drawCalendarRow(c, dp(16f), dp(106f), dp(26f), dp(8f), false);
            if (regattaWins > 0) {
                bold(c, regattaWins + (regattaWins == 1 ? " CUP" : " CUPS"), dp(318f), dp(114f), 10f, WARN, Paint.Align.RIGHT);
            }
        }

        // Right: the whole course, both boats, and the margin in seats or lengths.
        float cl = w - dp(308f);
        float cr = w - dp(20f);
        float cy = dp(46f);
        paint.setColor(0x44FFFFFF);
        c.drawRect(cl, cy - dp(1f), cr, cy + dp(1f), paint);
        paint.setColor(0xFFF2F2F2);
        c.drawRect(cr - dp(2f), cy - dp(10f), cr + dp(2f), cy + dp(10f), paint);
        float yx = cl + (cr - cl) * (float) Math.min(1, yourMeters / raceMeters);
        float rx = cl + (cr - cl) * (float) Math.min(1, rivalMeters / raceMeters);
        paint.setColor(RIVAL_CREW);
        c.drawRoundRect(rx - dp(12f), cy - dp(9f), rx + dp(12f), cy - dp(3f), dp(3f), dp(3f), paint);
        paint.setColor(PLAYER_CREW);
        c.drawRoundRect(yx - dp(12f), cy + dp(3f), yx + dp(12f), cy + dp(9f), dp(3f), dp(3f), paint);
        String margin;
        double abs = Math.abs(gap);
        if (abs < SEAT_METRES * 0.5) {
            margin = "LEVEL";
        } else if (abs < EIGHT_METRES) {
            margin = String.format(java.util.Locale.US, "%d SEAT%s %s", Math.round(abs / SEAT_METRES),
                    Math.round(abs / SEAT_METRES) == 1 ? "" : "S", gap > 0 ? "UP" : "DOWN");
        } else {
            margin = String.format(java.util.Locale.US, "%.1f LENGTHS %s", abs / EIGHT_METRES, gap > 0 ? "UP" : "DOWN");
        }
        bold(c, margin, cl, dp(30f), 12f, gap >= 0 ? ACCENT : BAD, Paint.Align.LEFT);
        if (rivalSurge > 0.3f) {
            bold(c, "THEY'RE MOVING", cr, dp(30f), 11f, BAD, Paint.Align.RIGHT);
        }

        if (tenActive) {
            drawTenPanel(c, cx);
        }

        buttonsShown = phase != Phase.RACING;
        if (buttonsShown) {
            drawCrewRoom(c, cx, w, h);
        }

        String status;
        if (phase == Phase.READY) {
            status = seat == Seat.STROKE ? "TAKE A STROKE - THE CREW FOLLOWS YOUR RHYTHM"
                    : "TAKE A STROKE - THEN MATCH THE STROKE SEAT'S RHYTHM";
        } else if (phase == Phase.DONE) {
            status = (lastWasRegatta ? (won ? "REGATTA WON  ·  " : "REGATTA LOST  ·  ")
                    : won ? "YOUR CREW WON  ·  " : "BEATEN  ·  ") + clock(finishTime)
                    + (newBest ? "  ·  NEW BEST" : "") + (bestTen > 0 ? "  ·  BEST TEN " + bestTen + "/10" : "")
                    + (perfectTwenties > 0 ? "  ·  " + perfectTwenties + "x PERFECT 20" : "")
                    + "  ·  tap to race again";
        } else {
            status = String.format(java.util.Locale.US, "%s%.0f m  ·  %d of %d m  ·  %s", gap >= 0 ? "+" : "−",
                    Math.abs(gap), Math.round(yourMeters), raceMeters, clock(sessionSeconds - raceStart));
        }
        bold(c, status, cx, h - dp(14f), 12f, phase == Phase.DONE && won ? ACCENT : TEXT, Paint.Align.CENTER);
    }

    /** "SWING · n STROKES IN TIME", rebuilt only when n changes. */
    private String swingCaption() {
        if (swing != swingLabelKey) {
            swingLabelKey = swing;
            swingLabel = "SWING  ·  " + swing + " STROKES IN TIME";
        }
        return swingLabel;
    }

    /** Where the season stands, rebuilt only when the day, the season or the tick count changes. */
    private String seasonCaption() {
        int key = (regattaDay ? 1 : 0) + 2 * (trainingDays + 8 * (dayIndex + 8 * seasonNo));
        if (key != seasonLabelKey) {
            seasonLabelKey = key;
            int toGo = SEASON_DAYS - dayIndex;
            seasonLabel = regattaDay
                    ? "REGATTA DAY  ·  " + REGATTA_METERS + " m  ·  CREW FITNESS +" + trainingDays + "%"
                    : "SEASON " + seasonNo + "  ·  DAY " + (dayIndex + 1) + " OF " + SEASON_DAYS
                            + "  ·  REGATTA IN " + toGo + (toGo == 1 ? " DAY" : " DAYS");
        }
        return seasonLabel;
    }

    private int moraleColor() {
        return morale > 0.66f ? ACCENT : morale > 0.33f ? WARN : BAD;
    }

    /**
     * Twenty ticks, one per stroke in time. Filling the last one buys fifteen seconds of a flying
     * boat, so there is always something at stake within the next few strokes.
     */
    private void drawTwentyTrack(Canvas c, float cx) {
        int done = swing == 0 ? 0 : (swing % PERFECT == 0 ? PERFECT : swing % PERFECT);
        float tick = dp(15f);
        float x0 = cx - tick * PERFECT / 2f;
        boolean flying = sessionSeconds < swingBonusUntil;
        for (int i = 0; i < PERFECT; i++) {
            float x = x0 + i * tick;
            paint.setColor(i < done ? (twentyFlash > 0.5f ? 0xFFFFFFFF : 0xFFF0B132) : 0x2BFFFFFF);
            c.drawRoundRect(x + dp(1.5f), dp(88f), x + tick - dp(1.5f), dp(96f), dp(2f), dp(2f), paint);
        }
        int secsLeft = flying ? (int) Math.ceil(swingBonusUntil - sessionSeconds) : 0;
        int key = flying ? 1000 + secsLeft : perfectTwenties > 0 ? -1 - perfectTwenties : done;
        if (key != twentyLabelKey) {
            twentyLabelKey = key;
            twentyLabel = flying ? "FLYING  ·  +8%  ·  " + secsLeft + "s"
                    : perfectTwenties > 0 ? "PERFECT TWENTY  ·  " + perfectTwenties + " THIS RACE"
                            : "PERFECT TWENTY  ·  " + (PERFECT - done) + " TO GO";
        }
        if (flying) {
            bold(c, twentyLabel, cx, dp(110f), 11f, WARN, Paint.Align.CENTER);
        } else {
            label(c, twentyLabel, cx, dp(110f), 10f, FAINT, Paint.Align.CENTER);
        }
    }

    /** The season strip: six training days and the regatta, ticked as they are rowed. */
    private void drawCalendarRow(Canvas c, float x, float y, float cell, float h, boolean labels) {
        paint.setStyle(Paint.Style.FILL);
        for (int d = 0; d <= SEASON_DAYS; d++) {
            float left = x + d * cell;
            boolean done = (seasonDays & (1 << d)) != 0;
            boolean isRegatta = d == SEASON_DAYS;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isRegatta ? (regattaDay ? 0xFFF0B132 : 0x55F0B132) : done ? 0xFF35D0BA : 0x33FFFFFF);
            c.drawRoundRect(left + dp(2f), y, left + cell - dp(2f), y + h, dp(3f), dp(3f), paint);
            if (d == dayIndex) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(0xFFFFFFFF);
                c.drawRoundRect(left + dp(1f), y - dp(1f), left + cell - dp(1f), y + h + dp(1f), dp(4f), dp(4f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (labels) {
                label(c, DAY_LABELS[Math.min(d, SEASON_DAYS)], left + cell / 2f, y + h + dp(14f), 9f,
                        done || isRegatta ? TEXT : FAINT, Paint.Align.CENTER);
                if (done && !isRegatta) {
                    // A tick, drawn as two strokes.
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2.5f));
                    paint.setColor(0xFF0A1420);
                    float ux = left + cell / 2f;
                    float uy = y + h / 2f;
                    c.drawLine(ux - dp(6f), uy, ux - dp(1f), uy + dp(5f), paint);
                    c.drawLine(ux - dp(1f), uy + dp(5f), ux + dp(7f), uy - dp(6f), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    /** Ten pips, one per stroke of the ten: gold if it beat your average, red if it did not. */
    private void drawTenPanel(Canvas c, float cx) {
        float top = dp(126f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(tenFlash > 0 ? 0xCC3A2A06 : 0xB30A1420);
        c.drawRoundRect(cx - dp(170f), top, cx + dp(170f), top + dp(58f), dp(12f), dp(12f), paint);
        String target;
        if (baselineKind == 0 || baselineKind == 2) {
            target = "POWER TEN  ·  BEAT " + Math.round(effortBaseline * TEN_RATIO) + " W";
        } else {
            target = "POWER TEN  ·  PULL HARDER THAN YOUR LAST STROKES";
        }
        bold(c, target, cx, top + dp(20f), 12f, WARN, Paint.Align.CENTER);
        float pip = dp(26f);
        float x0 = cx - pip * 5f;
        for (int i = 0; i < 10; i++) {
            float px = x0 + i * pip + pip / 2f;
            float py = top + dp(40f);
            if (i < tenStrokes) {
                paint.setColor(tenResults[i] ? 0xFFF0B132 : BAD);
                c.drawCircle(px, py, dp(8f) + (i == tenStrokes - 1 ? tenFlash * dp(4f) : 0f), paint);
            } else {
                paint.setColor(0x44FFFFFF);
                c.drawCircle(px, py, dp(6f), paint);
            }
        }
    }

    /**
     * The boathouse, shown before and after a race: the seat you are taking, where the season has
     * got to, how the seat races have gone, and who is in the boat with you.
     */
    private void drawCrewRoom(Canvas c, float cx, float w, float h) {
        float pw = Math.min(w - dp(40f), dp(960f));
        float ph = dp(292f);
        float top = Math.max(dp(136f), h * 0.19f);
        panel.set(cx - pw / 2f, top, cx + pw / 2f, top + ph);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6081120);
        c.drawRoundRect(panel, dp(16f), dp(16f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(regattaDay ? 0xFFF0B132 : 0x5535D0BA);
        c.drawRoundRect(panel, dp(16f), dp(16f), paint);
        paint.setStyle(Paint.Style.FILL);

        bold(c, regattaDay
                        ? "REGATTA DAY  ·  " + REGATTA_METERS + " m  ·  SEASON " + seasonNo
                        : "SEASON " + seasonNo + "  ·  TRAINING DAY " + (dayIndex + 1) + "  ·  " + raceMeters + " m",
                cx, panel.top + dp(26f), 15f, regattaDay ? WARN : TEXT, Paint.Align.CENTER);

        float bw = Math.min(dp(284f), (pw - dp(70f)) / 3f);
        float bh = dp(56f);
        float by = panel.top + dp(40f);
        float gap = dp(12f);
        float x0 = cx - (bw * 3f + gap * 2f) / 2f;
        strokeBtn.set(x0, by, x0 + bw, by + bh);
        engineBtn.set(x0 + bw + gap, by, x0 + bw * 2f + gap, by + bh);
        bowBtn.set(x0 + (bw + gap) * 2f, by, x0 + bw * 3f + gap * 2f, by + bh);
        drawSeatButton(c, strokeBtn, "STROKE", "crew follows your rhythm", seat == Seat.STROKE);
        drawSeatButton(c, engineBtn, "ENGINE ROOM", "press harder, timing forgiven", seat == Seat.ENGINE);
        drawSeatButton(c, bowBtn, "BOW  ·  HARDEST", "you follow the crew", seat == Seat.BOW);

        float colY = by + bh + dp(26f);
        float colW = (pw - dp(64f)) / 3f;
        float c1 = panel.left + dp(22f);
        float c2 = c1 + colW + dp(10f);
        float c3 = c2 + colW + dp(10f);
        drawSeasonColumn(c, c1, colY, colW);
        drawSeatRaceColumn(c, c2, colY, colW);
        drawRosterColumn(c, c3, colY, colW);
    }

    private void drawSeasonColumn(Canvas c, float x, float y, float colW) {
        label(c, "THE SEASON", x, y, 10f, ACCENT, Paint.Align.LEFT);
        float cell = Math.min(dp(40f), colW / (SEASON_DAYS + 1));
        drawCalendarRow(c, x, y + dp(14f), cell, dp(26f), true);
        label(c, trainingDays + " of " + SEASON_DAYS + " days trained", x, y + dp(74f), 11f, TEXT, Paint.Align.LEFT);
        label(c, regattaDay ? "worth +" + trainingDays + "% to the boat today"
                : "each day is worth +1% in the regatta", x, y + dp(92f), 10f, DIM, Paint.Align.LEFT);
        label(c, "CUPS WON  ·  " + regattaWins, x, y + dp(116f), 11f, regattaWins > 0 ? WARN : FAINT, Paint.Align.LEFT);
        label(c, "MORALE  ·  " + moraleWord(morale), x, y + dp(136f), 11f, moraleColor(), Paint.Align.LEFT);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(x, y + dp(144f), x + colW - dp(20f), y + dp(152f), dp(4f), dp(4f), paint);
        paint.setColor(moraleColor());
        c.drawRoundRect(x, y + dp(144f), x + (colW - dp(20f)) * morale, y + dp(152f), dp(4f), dp(4f), paint);
    }

    /** Seat races: the finishing margin from each seat, per 1000 m, and which seat you are worth more in. */
    private void drawSeatRaceColumn(Canvas c, float x, float y, float colW) {
        label(c, "SEAT RACES  ·  SEATS PER 1000 m", x, y, 10f, ACCENT, Paint.Align.LEFT);
        int best = -1;
        int raced = 0;
        for (int i = 0; i < 3; i++) {
            if (seatRaces[i] > 0) {
                raced++;
                if (best < 0 || seatBestMargin[i] > seatBestMargin[best]) {
                    best = i;
                }
            }
        }
        Seat[] all = SEAT_ORDER;
        for (int i = 0; i < 3; i++) {
            float ry = y + dp(22f) + i * dp(30f);
            boolean here = seat == all[i];
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(here ? 0x3335D0BA : 0x14FFFFFF);
            c.drawRoundRect(x, ry - dp(13f), x + colW - dp(20f), ry + dp(11f), dp(6f), dp(6f), paint);
            label(c, all[i].name(), x + dp(8f), ry + dp(4f), 11f, here ? TEXT : DIM, Paint.Align.LEFT);
            if (seatRaces[i] == 0) {
                label(c, "not raced", x + colW - dp(28f), ry + dp(4f), 10f, FAINT, Paint.Align.RIGHT);
            } else {
                label(c, String.format(java.util.Locale.US, "last %+.1f  ·  best %+.1f  ·  %d",
                                seatLastMargin[i] / (float) SEAT_METRES, seatBestMargin[i] / (float) SEAT_METRES, seatRaces[i]),
                        x + colW - dp(28f), ry + dp(4f), 10f,
                        i == best ? WARN : seatLastMargin[i] >= 0 ? ACCENT : BAD, Paint.Align.RIGHT);
            }
        }
        label(c, "seats up on the rival crew at the line", x, y + dp(126f), 10f, FAINT, Paint.Align.LEFT);
        if (raced >= 2 && best >= 0) {
            int second = -1;
            for (int i = 0; i < 3; i++) {
                if (i != best && seatRaces[i] > 0 && (second < 0 || seatBestMargin[i] > seatBestMargin[second])) {
                    second = i;
                }
            }
            float diff = (seatBestMargin[best] - seatBestMargin[second]) / (float) SEAT_METRES;
            bold(c, String.format(java.util.Locale.US, "COACH: %+.1f seats better at %s", diff, all[best].name()),
                    x, y + dp(148f), 11f, WARN, Paint.Align.LEFT);
        } else {
            label(c, "race a second seat to compare them", x, y + dp(148f), 10f, DIM, Paint.Align.LEFT);
        }
    }

    /** The eight by name, with the seat you are taking marked and the crew's mood on their faces. */
    private void drawRosterColumn(Canvas c, float x, float y, float colW) {
        label(c, "YOUR CREW", x, y, 10f, ACCENT, Paint.Align.LEFT);
        int you = youSeatIndex();
        float half = (colW - dp(20f)) / 2f;
        for (int i = 0; i < SEATS; i++) {
            float rx = x + (i >= 4 ? half + dp(8f) : 0f);
            float ry = y + dp(22f) + (i % 4) * dp(30f);
            boolean isYou = i == you;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(isYou ? 0x3335D0BA : 0x14FFFFFF);
            c.drawRoundRect(rx, ry - dp(13f), rx + half, ry + dp(11f), dp(6f), dp(6f), paint);
            // A face, coloured by morale and by how easily this rower fades.
            float mood = clamp01(morale * (0.6f + 0.4f * (1f - seatWeak[i])));
            paint.setColor(mix(0xFFE0604A, 0xFFF1C27D, mood));
            c.drawCircle(rx + dp(14f), ry - dp(1f), dp(7f), paint);
            paint.setColor(0xFF0A1420);
            c.drawCircle(rx + dp(11.5f), ry - dp(3f), dp(1.2f), paint);
            c.drawCircle(rx + dp(16.5f), ry - dp(3f), dp(1.2f), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.4f));
            // The mouth turns with morale: a flat crew is a flat line.
            float curve = (mood - 0.5f) * dp(5f);
            path.reset();
            path.moveTo(rx + dp(10.5f), ry + dp(2f));
            path.quadTo(rx + dp(14f), ry + dp(2f) + curve, rx + dp(17.5f), ry + dp(2f));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            bold(c, isYou ? "YOU" : MATE_NAMES[i], rx + dp(26f), ry + dp(3f), 11f, isYou ? ACCENT : TEXT, Paint.Align.LEFT);
            label(c, MATE_ROLE[i], rx + half - dp(6f), ry + dp(3f), 9f, FAINT, Paint.Align.RIGHT);
        }
        label(c, "they'll tell you how it's going", x, y + dp(148f), 10f, FAINT, Paint.Align.LEFT);
    }

    private void drawSeatButton(Canvas c, RectF r, String title, String sub, boolean on) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? 0xE635D0BA : 0xCC0A1420);
        c.drawRoundRect(r, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(on ? 0xFFFFFFFF : 0x88FFFFFF);
        c.drawRoundRect(r, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, title, r.centerX(), r.top + dp(25f), 15f, on ? 0xFF0A1420 : TEXT, Paint.Align.CENTER);
        label(c, sub, r.centerX(), r.top + dp(44f), 10f, on ? 0xFF0A1420 : DIM, Paint.Align.CENTER);
    }

    /** Sky, a far bank of trees and a crowd along it with flags, scrolling with the boat. */
    private void drawBank(Canvas c, float w, float h, float waterTop, float ppm) {
        if (skyShader == null || skyShaderTop != waterTop) {
            skyShader = new android.graphics.LinearGradient(0, 0, 0, waterTop, 0xFF3D78B8, 0xFFBFDDF2,
                    android.graphics.Shader.TileMode.CLAMP);
            skyShaderTop = waterTop;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, waterTop, paint);
        paint.setShader(null);
        // Clouds.
        paint.setColor(0xCCFFFFFF);
        for (int i = 0; i < 4; i++) {
            float cx = (float) (((i * 520 + 100) - scenery * ppm * 0.08) % (w + dp(260f)));
            if (cx < -dp(130f)) {
                cx += w + dp(260f);
            }
            float cy = waterTop * (0.25f + (i % 2) * 0.18f);
            c.drawOval(cx - dp(60f), cy - dp(14f), cx + dp(60f), cy + dp(14f), paint);
            c.drawOval(cx - dp(30f), cy - dp(26f), cx + dp(34f), cy + dp(6f), paint);
        }
        // Trees on the far bank.
        float bankY = waterTop - dp(4f);
        paint.setColor(0xFF3F7A45);
        c.drawRect(0, bankY - dp(10f), w, waterTop, paint);
        float treeGap = dp(70f);
        float off = (float) ((scenery * ppm * 0.4) % treeGap);
        for (float x = -off; x < w + treeGap; x += treeGap) {
            int k = (int) Math.floor((x + scenery * ppm * 0.4) / treeGap);
            float r = dp(22f) + ((k * 7) % 3) * dp(6f);
            paint.setColor(((k & 1) == 0) ? 0xFF2F6B3A : 0xFF3A7D44);
            c.drawCircle(x, bankY - dp(18f) - r * 0.4f, r, paint);
        }
        // The crowd along the bank: heads bobbing, flags waving - louder in a close race or a ten.
        float fanGap = dp(18f);
        float foff = (float) ((scenery * ppm * 0.9) % fanGap);
        // Regatta day brings a crowd that is up on its feet before the start.
        boolean roar = sync > 0.8f || tenActive || regattaDay || sessionSeconds < swingBonusUntil
                || (phase == Phase.RACING && Math.abs(yourMeters - rivalMeters) < SEAT_METRES * 2);
        int[] shirts = SHIRTS;
        for (float x = -foff; x < w + fanGap; x += fanGap) {
            int k = (int) Math.floor((x + scenery * ppm * 0.9) / fanGap);
            float cheer = roar ? (float) Math.abs(Math.sin(sessionSeconds * 8 + k)) * dp(6f) : 0f;
            paint.setColor(shirts[Math.abs(k) % shirts.length]);
            c.drawRect(x - dp(5f), bankY - dp(20f) - cheer, x + dp(5f), bankY - dp(6f), paint);
            paint.setColor(0xFFF1C27D);
            c.drawCircle(x, bankY - dp(25f) - cheer, dp(4f), paint);
            if (k % 5 == 0) {
                paint.setColor(0xFF9AA5B1);
                c.drawRect(x + dp(4f), bankY - dp(44f) - cheer, x + dp(5.5f), bankY - dp(20f) - cheer, paint);
                float wave = (float) Math.sin(sessionSeconds * 6 + k) * dp(4f);
                paint.setColor(shirts[(Math.abs(k) + 2) % shirts.length]);
                path.reset();
                path.moveTo(x + dp(5.5f), bankY - dp(44f) - cheer);
                path.lineTo(x + dp(22f), bankY - dp(40f) - cheer + wave);
                path.lineTo(x + dp(5.5f), bankY - dp(34f) - cheer);
                path.close();
                c.drawPath(path, paint);
            }
        }
    }

    /**
     * The near bank, below the water. The eight sits at 0.62 of a band that runs to 0.88, so the
     * bottom third was empty by construction - puddles could not reach it and chop was too quiet
     * to carry it. Grass and reeds scroll fastest of anything on screen, which is what sells the
     * speed at this distance. Drawn before the buoys: the near lane is at waterBottom itself.
     */
    private void drawNearShore(Canvas c, float w, float h, float waterBottom, float ppm) {
        paint.setStyle(android.graphics.Paint.Style.FILL);
        paint.setColor(0xFF2F5E33);
        c.drawRect(0, waterBottom, w, h, paint);
        float gap = dp(22f);
        double scroll = scenery * ppm * 1.2;
        float off = (float) (scroll % gap);
        paint.setStrokeWidth(dp(3f));
        for (float rx = -off - gap; rx < w + gap; rx += gap) {
            int k = (int) Math.floor((rx + scroll) / gap + 0.5);
            float tall = dp(14f) + (Math.abs(k * 7) % 4) * dp(5f);
            float sway = (float) Math.sin(sessionSeconds * 2 + k) * dp(3f);
            paint.setColor((k & 1) == 0 ? 0xFF4E8F4F : 0xFF6BAA5C);
            c.drawLine(rx, waterBottom + dp(8f), rx + sway, waterBottom + dp(8f) - tall, paint);
        }
        paint.setColor(0xFF24491F);
        c.drawRect(0, waterBottom + dp(9f), w, waterBottom + dp(12f), paint);
    }

    /** Lane buoys every 25 m, red and white, bobbing past. */
    private void drawBuoys(Canvas c, float w, float waterTop, float waterBottom, float ppm) {
        float gapPx = 25f * ppm;
        float off = (float) ((yourMeters * ppm) % gapPx);
        for (int lane = 0; lane < 2; lane++) {
            float y = waterTop + (waterBottom - waterTop) * (lane == 0 ? 0.40f : 0.88f);
            for (float x = -off; x < w + gapPx; x += gapPx) {
                int k = (int) Math.floor((x + yourMeters * ppm) / gapPx);
                float bob = (float) Math.sin(sessionSeconds * 3 + k) * dp(2f);
                paint.setColor((k & 1) == 0 ? 0xFFF0655D : 0xFFFFFFFF);
                c.drawCircle(x, y + bob, dp(5f), paint);
            }
        }
    }

    private void drawCoxCall(Canvas c, String text, float x, float y, float w, int bg, int ink, float size) {
        // The rival's cox can be off screen; keep the bubble's tail pointing at something visible.
        x = Math.max(dp(20f), Math.min(w - dp(20f), x));
        textPaint.setTextSize(dp(size));
        float tw = textPaint.measureText(text);
        float bx = Math.max(dp(8f), Math.min(w - tw - dp(40f), x - tw / 2f - dp(12f)));
        float bh = dp(size * 2f);
        float by = y - bh - dp(4f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(bg);
        c.drawRoundRect(bx, by, bx + tw + dp(24f), by + bh, dp(12f), dp(12f), paint);
        path.reset();
        path.moveTo(bx + tw * 0.7f, by + bh - dp(1f));
        path.lineTo(bx + tw * 0.7f + dp(14f), by + bh - dp(1f));
        path.lineTo(x, y);
        path.close();
        c.drawPath(path, paint);
        bold(c, text, bx + dp(12f) + tw / 2f, by + bh * 0.5f + dp(size * 0.36f), size, ink, Paint.Align.CENTER);
    }

    /**
     * A crewmate speaking, from their own seat in the boat. Pale blue against the cox's white, so
     * it reads as the crew talking rather than another call.
     */
    private void drawMateBubble(Canvas c, float w) {
        float spacing = eightLen * 0.8f / SEATS;
        float x = eightCx + eightLen * 0.36f - mateSeat * spacing;
        float y = eightYNow - dp(50f);
        // A little bob so a speaking rower is easy to find among eight of them.
        float bob = (float) Math.sin(sessionSeconds * 9) * dp(2f);
        drawCoxCall(c, mateText, x, y + bob, w, 0xF2D7ECFF, 0xFF0B2438, 12f);
    }

    /** Puddles drift astern with the boat's own speed, widening and fading over about 3 s. */
    private void drawPuddles(Canvas c, float dt, float ppm, float moving) {
        for (int i = 0; i < PUDDLES; i++) {
            if (puddleAge[i] >= 3f || (puddleX[i] == 0f && puddleY[i] == 0f)) {
                continue;
            }
            puddleAge[i] += dt;
            puddleX[i] -= moving * ppm * dt;
            float k = puddleAge[i] / 3f;
            float rx = dp(9f) + k * dp(22f);
            float ry = dp(3.5f) + k * dp(7f);
            int a = (int) (135 * (1f - k));
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(0xFFDCEBF7);
            paint.setAlpha(a);
            c.drawOval(puddleX[i] - rx, puddleY[i] - ry, puddleX[i] + rx, puddleY[i] + ry, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setAlpha((int) (a * 0.45f));
            c.drawOval(puddleX[i] - rx * 0.5f, puddleY[i] - ry * 0.5f,
                    puddleX[i] + rx * 0.5f, puddleY[i] + ry * 0.5f, paint);
            paint.setAlpha(255);
        }
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    /**
     * An eight, side on: rowers lean and slide, oars sweep and dip - in time or not.
     *
     * @param s         scale, 1 for your boat and smaller for the far lane
     * @param youSeat   the seat to mark as you, or -1
     * @param basePhase the crew's place in the stroke cycle (whole cycles; fraction used)
     * @param youPhase  your own seat's cycle when it runs separately from the crew (bow), else -1
     * @param tired     0..1: tired rowers slump, rush the slide, wash out and go red in the face
     */
    private void drawEight(Canvas c, float cx, float len, float waterY, float s, int crewColor, int stripe,
                           int youSeat, float crewSync, float[] lags, float basePhase, float youPhase,
                           float tired, boolean[] wasIn, boolean player) {
        float beam = dp(16f) * s;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFE8E2D0);
        c.drawRoundRect(cx - len / 2f, waterY - beam, cx + len / 2f, waterY + beam * 0.3f, beam, beam, paint);
        paint.setColor(stripe);
        c.drawRect(cx - len / 2f, waterY - beam * 0.25f, cx + len / 2f, waterY + beam * 0.3f, paint);
        float spacing = len * 0.8f / SEATS;
        for (int i = 0; i < SEATS; i++) {
            // Stroke seat (i = 0) is at the stern, on the right, facing the stern like a real crew.
            float seatX = cx + len * 0.36f - i * spacing;
            float t = player ? tired * (0.45f + 0.55f * seatWeak[i]) : 0f;
            float phaseT;
            if (i == youSeat && youPhase >= 0f) {
                phaseT = youPhase;
            } else {
                // Out of time, or exhausted, the crew spreads out: some early, some late. A crew
                // whose morale is up sits visibly tighter on the same sync figure.
                float spread = (1f - crewSync) * 0.28f + t * 0.22f;
                if (player) {
                    spread *= 1.2f - 0.4f * morale;
                }
                phaseT = basePhase + spread * lags[i];
            }
            phaseT = phaseT - (float) Math.floor(phaseT);
            float drive = phaseT < 0.35f ? phaseT / 0.35f : 1f - (phaseT - 0.35f) / 0.65f;
            // A tired rower cuts the slide short and sags forward over the knees.
            float slide = drive * spacing * 0.3f * (1f - 0.3f * t);
            float bodyX = seatX - slide;
            float hipY = waterY - beam;
            float torso = dp(26f) * s * (1f - 0.28f * t);
            float lean = (0.5f - drive) * dp(8f) * s + t * dp(8f) * s;
            float shoulderX = bodyX + lean;
            float shoulderY = hipY - torso;
            int body = i == youSeat ? ACCENT : mix(crewColor, 0xFF6B7280, t * 0.6f);
            paint.setColor(body);
            paint.setStrokeWidth(dp(10f) * s);
            paint.setStrokeCap(Paint.Cap.BUTT);
            c.drawLine(bodyX, hipY, shoulderX, shoulderY, paint);
            paint.setColor(mix(0xFFF1C27D, 0xFFE0604A, t));
            float headDrop = t * dp(5f) * s;
            c.drawCircle(shoulderX + t * dp(3f) * s, shoulderY - dp(6f) * s + headDrop, dp(6f) * s, paint);
            if (i == youSeat) {
                label(c, "YOU", shoulderX, shoulderY - dp(18f) * s, 10f, ACCENT, Paint.Align.CENTER);
            }
            float bladeX = seatX + (drive - 0.5f) * spacing * 0.9f;
            boolean inWater = phaseT < 0.35f;
            // A tired blade washes out: it does not bury, so it pulls shallow.
            float bladeY = waterY + (inWater ? dp(14f) * s * (1f - 0.4f * t) : -dp(8f) * s);
            if (wasIn[i] && !inWater) {
                puddleX[puddleHead] = bladeX;
                puddleY[puddleHead] = waterY + dp(14f) * s;
                puddleAge[puddleHead] = 0f;
                puddleHead = (puddleHead + 1) % PUDDLES;
                if (t > 0.55f && Math.random() < 0.5) {
                    // A messy extraction: water thrown everywhere.
                    fx.burst(bladeX, waterY + dp(10f), 8, dp(110f), 0.5f, dp(2.5f), 0xDDBFE3FF, true);
                }
            }
            wasIn[i] = inWater;
            paint.setColor(0xFFCBB38A);
            paint.setStrokeWidth(dp(3f) * s);
            c.drawLine(seatX, hipY - dp(8f) * s, bladeX, bladeY, paint);
            paint.setColor(player ? 0xFFFFFFFF : crewColor);
            c.drawOval(bladeX - dp(10f) * s, bladeY - dp(4f) * s, bladeX + dp(10f) * s, bladeY + dp(4f) * s, paint);
        }
        // Cox at the stern, with a megaphone pointed at the crew.
        float coxX = cx + len * 0.46f;
        float coxY = waterY - beam - dp(10f) * s;
        paint.setColor(WARN);
        c.drawCircle(coxX, coxY, dp(7f) * s, paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(coxX, coxY - dp(10f) * s, dp(5f) * s, paint);
        boolean calling = player ? sessionSeconds < coxUntil : sessionSeconds < rivalCallUntil;
        if (calling) {
            float pulse = 1f + 0.2f * (float) Math.abs(Math.sin(sessionSeconds * 10));
            paint.setColor(0xFFE6EDF7);
            path.reset();
            path.moveTo(coxX - dp(4f) * s, coxY - dp(11f) * s);
            path.lineTo(coxX - dp(16f) * s * pulse, coxY - dp(16f) * s * pulse);
            path.lineTo(coxX - dp(16f) * s * pulse, coxY - dp(4f) * s);
            path.close();
            c.drawPath(path, paint);
        }
    }
}
