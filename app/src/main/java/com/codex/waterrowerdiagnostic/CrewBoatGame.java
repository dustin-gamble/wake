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
 * <p>Two seats. At <b>STROKE</b> the crew follows your rhythm, so changing rate deliberately is
 * fine and only the stroke-to-stroke wobble costs. At <b>BOW</b> you follow the crew: the stroke
 * seat sets the rate, changes it through the piece, and you have to go with it - harder.
 *
 * <p>The cox talks to you: calls a POWER TEN (ten strokes that must beat your own recent effort),
 * reacts to your real rate going up or down, and answers the other crew's moves.
 *
 * <p>Timing comes from the pulse meter's stroke detection, measured at the drive, not a second late
 * off the monitor's counter. Stroke effort for the ten comes from the same stroke record (its
 * measured drive power, or the paddle's peak rate cubed before energy is known), never from the
 * watts register inside onStroke.
 */
final class CrewBoatGame extends GameView {

    private enum Phase { READY, RACING, DONE }

    private enum Seat { STROKE, BOW }

    private static final int RACE_METERS = 1000;
    private static final int SEATS = 8;
    /** An eight is ~17.5 m; a "seat" of margin is an eighth of that, which is how coxes count it. */
    private static final double EIGHT_METRES = 17.5;
    private static final double SEAT_METRES = EIGHT_METRES / SEATS;
    /** A stroke in the ten must beat your own recent average effort by this much to count. */
    private static final double TEN_RATIO = 1.08;
    private static final int PLAYER_CREW = 0xFF3A5BD9;
    private static final int RIVAL_CREW = 0xFFD9453A;
    private static final int[] SHIRTS = {0xFFF0655D, 0xFFF0B132, 0xFF6F8CFF, 0xFFFFFFFF, 0xFF35D0BA};
    /** Distances (rival's metres) at which the other crew makes a move; the last one runs to the line. */
    private static final int[] RIVAL_MOVES = {280, 620, 860};

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
    private final RectF bowBtn = new RectF();
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
    private double crewInterval;
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
    private double crewTargetRate;
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
        if ("BOW".equals(saved)) {
            seat = Seat.BOW;
        }
    }

    @Override
    protected void onStart() {
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
            say(seat == Seat.STROKE ? "ATTENTION... GO! THEY'RE ON YOU, STROKE!" : "ATTENTION... GO! FOLLOW STROKE, BOW!", 2.4, 1);
            rivalCall("GO! GO! GO!", 1.8);
        }
        if (seat == Seat.BOW && resumed) {
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
                    dev = 0.6 * intervalDev + 0.4 * Math.abs(off - offsetEma);
                    tol = 0.10;
                    // The crew gives a little: a bow seat can nudge a boat, not steer it.
                    crewPhase -= (float) (off - offsetEma) * 0.15f;
                }
                float target = (float) Math.max(0, Math.min(1, 1 - dev / tol));
                sync += (target - sync) * 0.35f;
                swing = sync > 0.85f ? swing + 1 : 0;
                syncSum += sync;
                syncStrokes++;
                if (sync < 0.5f) {
                    fx.burst(getWidth() * 0.5f, getHeight() * 0.62f, 26, dp(160f), 0.6f, dp(3f), 0xDDBFE3FF, true);
                }
                updateFatigue();
                reactToRate(60.0 / interval);
            }
        }
        lastStrokeAt = now;
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

    /** Timing falling apart wears the crew down; rowing in time brings them back. */
    private void updateFatigue() {
        if (sync < 0.6f) {
            fatigue += (0.6f - sync) * 0.18f;
        } else if (sync > 0.8f) {
            fatigue -= 0.035f;
        }
        fatigue = Math.max(0f, Math.min(1f, fatigue));
        if (!tiredCalled && fatigue > 0.5f) {
            tiredCalled = true;
            say("THEY'RE TIRING - SETTLE IT, FIND THE RHYTHM!", 2.6, 2);
            rivalCall("THEY'RE FALLING APART - GO!", 2.0);
        } else if (tiredCalled && fatigue < 0.2f) {
            tiredCalled = false;
            say("THAT'S IT - THEY'RE BACK WITH YOU!", 2.4, 2);
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
            if (seat == Seat.BOW) {
                int crew = (int) Math.round(60.0 / crewInterval);
                double diff = rateFast - 60.0 / crewInterval;
                if (diff >= 1.5) {
                    say(String.format(java.util.Locale.US, "BOW, YOU'RE RUSHING - %d, NOT %d!", now, crew), 2.6, 2);
                    lastRateCallAt = sessionSeconds;
                } else if (diff <= -1.5) {
                    say(String.format(java.util.Locale.US, "BOW, YOU'RE LATE - UP TO %d!", crew), 2.6, 2);
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
                }
            }
            return;
        }
        effortBaseline = baselineN == 0 ? effort : effortBaseline + (effort - effortBaseline) * 0.2;
        baselineN++;
        strokesSinceTen++;
        if (baselineN >= 6 && strokesSinceTen >= 30 && yourMeters < RACE_METERS - 60) {
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

        if (seat == Seat.BOW) {
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
        float factor = 0.85f + 0.3f * sync + (swing >= 6 ? 0.05f : 0f) - 0.10f * fatigue + pushBoost;
        if (phase == Phase.RACING) {
            yourMeters += speed * factor * dt;
            stepRival(dt);
            boolean lead = yourMeters > rivalMeters;
            if (lead != youLead && Math.abs(yourMeters - rivalMeters) > 1) {
                youLead = lead;
                say(lead ? "WE'VE GOT THEIR BOW - KEEP GOING!" : "THEY'RE THROUGH US - RESPOND!", 2.4, 2);
                rivalCall(lead ? "DON'T LET THEM GO!" : "WE'RE THROUGH! AGAIN!", 1.8);
            }
            if (yourMeters >= RACE_METERS) {
                phase = Phase.DONE;
                tenActive = false;
                finishTime = sessionSeconds - raceStart;
                won = yourMeters - rivalMeters >= 0;
                String key = seat == Seat.STROKE ? "crew.time." + RACE_METERS : "crew.time.bow." + RACE_METERS;
                newBest = bests.recordLowest(key, (float) finishTime);
                if (syncStrokes > 10) {
                    bests.recordHighest("crew.sync", 100f * syncSum / syncStrokes);
                }
                say(won ? "WE WON IT! EASY ALL!" : "EASY ALL... NEXT TIME.", 4, 4);
                rivalCall(won ? "WELL ROWED." : "YES! WE'VE GOT IT!", 3);
                if (won) {
                    fx.burst(w * 0.5f, h * 0.5f, 60, dp(320f), 1.2f, dp(4f), 0xFFF0B132, true);
                }
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
            youSeat = SEATS - 1;
        }
        drawEight(c, w * 0.5f, w * 0.62f, eightY, 1f, PLAYER_CREW, 0xFF2F6E93, youSeat, sync, seatLag,
                basePhase, youPhase, fatigue, bladeWasIn, true);
        fx.draw(c);
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
        double toGo = RACE_METERS - yourMeters;
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

    /** The other eight: a steady crew at your typical pace that makes three moves and sprints home. */
    private void stepRival(float dt) {
        if (rivalMoveIndex < RIVAL_MOVES.length && rivalMeters >= RIVAL_MOVES[rivalMoveIndex]) {
            boolean sprint = rivalMoveIndex == RIVAL_MOVES.length - 1;
            rivalMoveEnd = sprint ? RACE_METERS + 1 : rivalMeters + 70;
            rivalMoveIndex++;
            rivalCall(sprint ? "SPRINT! TAKE IT HOME!" : "MOVE NOW! TEN IN TWO!", 2.2);
            if (!tenActive && baselineN >= 4 && yourMeters < RACE_METERS - 60) {
                startTen(sprint ? "THEY'RE SPRINTING - POWER TEN, NOW!" : "THEY'RE MOVING - POWER TEN, NOW!");
            }
        }
        boolean moving = rivalMeters < rivalMoveEnd;
        rivalSurge += ((moving ? 1f : 0f) - rivalSurge) * Math.min(1f, dt * 0.8f);
        // A crew that is well clear eases a little and one well behind digs in, so it stays a race.
        double gap = yourMeters - rivalMeters;
        double press = Math.max(-0.03, Math.min(0.03, gap / 400.0));
        rivalMeters += profile.typicalSpeed() * 1.02 * (1 + 0.06 * rivalSurge + press) * dt;
    }

    private void drawHud(Canvas c, float w, float h, double gap) {
        // HUD: sync meter, swing, gap.
        float cx = w / 2f;
        // 3.19.5: the HUD sits on the light sky, so it gets dark pills (seen unreadable on the emulator).
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRoundRect(cx - dp(170f), dp(6f), cx + dp(170f), dp(86f), dp(14f), dp(14f), paint);
        c.drawRoundRect(dp(8f), dp(12f), dp(300f), dp(64f), dp(10f), dp(10f), paint);
        c.drawRoundRect(w - dp(320f), dp(12f), w - dp(8f), dp(64f), dp(10f), dp(10f), paint);
        bold(c, Math.round(sync * 100) + "%", cx, dp(44f), 38f, sync > 0.85f ? ACCENT : sync > 0.6f ? WARN : BAD, Paint.Align.CENTER);
        label(c, swing >= 6 ? "SWING  ·  " + swing + " STROKES IN TIME" : "CREW SYNC", cx, dp(62f), 10f,
                swing >= 6 ? ACCENT : FAINT, Paint.Align.CENTER);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(cx - dp(160f), dp(72f), cx + dp(160f), dp(80f), dp(4f), dp(4f), paint);
        paint.setColor(sync > 0.85f ? ACCENT : sync > 0.6f ? WARN : BAD);
        c.drawRoundRect(cx - dp(160f), dp(72f), cx - dp(160f) + dp(320f) * sync, dp(80f), dp(4f), dp(4f), paint);

        // Left: seat, rate, and the crew's legs.
        int crewRate = (int) Math.round(60 / crewInterval);
        String rateText = seat == Seat.STROKE
                ? "STROKE SEAT  ·  CREW FOLLOWS YOU  ·  RATE " + crewRate
                : "BOW SEAT  ·  FOLLOW STROKE AT " + crewRate + (rateFast > 0 ? "  ·  YOU " + Math.round(rateFast) : "");
        label(c, rateText, dp(16f), dp(30f), 10f, DIM, Paint.Align.LEFT);
        label(c, fatigue > 0.5f ? "CREW TIRING" : "CREW LEGS", dp(16f), dp(52f), 10f, fatigue > 0.5f ? BAD : DIM, Paint.Align.LEFT);
        float barL = dp(110f);
        float barR = dp(288f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, dp(44f), barR, dp(52f), dp(4f), dp(4f), paint);
        float legs = 1f - fatigue;
        paint.setColor(legs > 0.6f ? ACCENT : legs > 0.35f ? WARN : BAD);
        c.drawRoundRect(barL, dp(44f), barL + (barR - barL) * legs, dp(52f), dp(4f), dp(4f), paint);

        // Right: the whole course, both boats, and the margin in seats or lengths.
        float cl = w - dp(308f);
        float cr = w - dp(20f);
        float cy = dp(46f);
        paint.setColor(0x44FFFFFF);
        c.drawRect(cl, cy - dp(1f), cr, cy + dp(1f), paint);
        paint.setColor(0xFFF2F2F2);
        c.drawRect(cr - dp(2f), cy - dp(10f), cr + dp(2f), cy + dp(10f), paint);
        float yx = cl + (cr - cl) * (float) Math.min(1, yourMeters / RACE_METERS);
        float rx = cl + (cr - cl) * (float) Math.min(1, rivalMeters / RACE_METERS);
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
            drawSeatButtons(c, cx, h);
        }

        String status;
        if (phase == Phase.READY) {
            status = seat == Seat.STROKE ? "TAKE A STROKE - THE CREW FOLLOWS YOUR RHYTHM"
                    : "TAKE A STROKE - THEN MATCH THE STROKE SEAT'S RHYTHM";
        } else if (phase == Phase.DONE) {
            status = (won ? "YOUR CREW WON  ·  " : "BEATEN  ·  ") + clock(finishTime)
                    + (newBest ? "  ·  NEW BEST" : "") + (bestTen > 0 ? "  ·  BEST TEN " + bestTen + "/10" : "")
                    + "  ·  tap to race again";
        } else {
            status = String.format(java.util.Locale.US, "%s%.0f m  ·  %d of %d m  ·  %s", gap >= 0 ? "+" : "−",
                    Math.abs(gap), Math.round(yourMeters), RACE_METERS, clock(sessionSeconds - raceStart));
        }
        bold(c, status, cx, h - dp(14f), 12f, phase == Phase.DONE && won ? ACCENT : TEXT, Paint.Align.CENTER);
    }

    /** Ten pips, one per stroke of the ten: gold if it beat your average, red if it did not. */
    private void drawTenPanel(Canvas c, float cx) {
        float top = dp(94f);
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

    private void drawSeatButtons(Canvas c, float cx, float h) {
        float top = h * 0.30f - dp(84f);
        float bw = dp(250f);
        float bh = dp(58f);
        strokeBtn.set(cx - bw - dp(10f), top, cx - dp(10f), top + bh);
        bowBtn.set(cx + dp(10f), top, cx + bw + dp(10f), top + bh);
        label(c, "CHOOSE YOUR SEAT", cx, top - dp(8f), 11f, TEXT, Paint.Align.CENTER);
        drawSeatButton(c, strokeBtn, "STROKE", "crew follows you", seat == Seat.STROKE);
        drawSeatButton(c, bowBtn, "BOW  ·  HARDER", "you follow the crew", seat == Seat.BOW);
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
        bold(c, title, r.centerX(), r.top + dp(26f), 17f, on ? 0xFF0A1420 : TEXT, Paint.Align.CENTER);
        label(c, sub, r.centerX(), r.top + dp(46f), 11f, on ? 0xFF0A1420 : DIM, Paint.Align.CENTER);
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
        boolean roar = sync > 0.8f || tenActive || (phase == Phase.RACING && Math.abs(yourMeters - rivalMeters) < SEAT_METRES * 2);
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
                // Out of time, or exhausted, the crew spreads out: some early, some late.
                float lag = ((1f - crewSync) * 0.28f + t * 0.22f) * lags[i];
                phaseT = basePhase + lag;
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
