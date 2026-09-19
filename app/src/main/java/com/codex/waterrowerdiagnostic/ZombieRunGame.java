package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Region;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Zombie Run: a horde chases you at a pace you choose. Hold above it or get eaten.
 *
 * <p>The horde creeps faster every minute and surges every so often, so you cannot settle. A
 * safe house every 500 m makes them fall back - a rest interval you have to earn by reaching it.
 * The gap is the game: it sits huge in the middle and every stroke moves it.
 *
 * <p>3.19.2, on the rower's direction ("funny speak boxes"): the horde talks. Every few seconds a
 * zombie says something that fits the moment - lurching at the start, closing in, surging, sulking at
 * a safe house, gloating when it has you - and when you are well clear, you answer back. Figures are
 * drawn twice the size they first shipped, which on the tablet were barely readable.
 *
 * <p>The five upgrades the rower approved:
 * <ul>
 * <li><b>Zombie types.</b> Runners break out of the pack in a dash that scales with how far ahead
 * you are, then tire and drop back. A brute sits in the pack, winds up with a roar you can see
 * coming, then charges. Either one reaching you is a grab, so the real distance is the gap to the
 * nearest zombie, not to the pack.</li>
 * <li><b>Safe houses</b> every 500 m now also take in the survivors you are escorting (the SAVED
 * score) and hand you a flare.</li>
 * <li><b>Flares</b>, earned three hard strokes at a time (per-stroke power from the pulse meter,
 * three quarters of the way up the rower's own profile from low to high watts). Tap FLARE, or one fires itself when you are
 * grabbed: the horde is pushed back, stunned, and the runners and brute flee.</li>
 * <li><b>Night mode</b> (the NIGHT button): black except your torch beam and the lights of safe
 * houses and survivors, with fog rolling through. The torch glances back at the horde when
 * something is closing, and in the dark you see them by their eyes.</li>
 * <li><b>Survivors</b> wait on wrecked cars along the route. Reach one with the nearest zombie at
 * least 12 m back and they join you; closer than that and they hide. They run behind you, so the
 * horde reaches them first - lose one and the horde stops for them, lose none and deliver them to
 * the next safe house.</li>
 * </ul>
 */
final class ZombieRunGame extends GameView {

    private enum Phase { READY, RUNNING, CAUGHT }

    private static final float START_GAP = 40f;
    private static final float MAX_GAP = 140f;
    private static final int SAFE_EVERY = 500;
    private static final double SAFE_SECONDS = 15;

    /** Nearest zombie must be at least this far back for a survivor to dare run to you. */
    private static final double RESCUE_CLEAR = 12;
    /** Metres between survivors running behind you. */
    private static final double SURV_SPACING = 1.8;
    private static final int MAX_GROUP = 4;
    private static final int MAX_FLARES = 3;
    private static final int PIPS_PER_FLARE = 3;
    private static final double FLARE_PUSH = 16;
    private static final double FLARE_STUN = 3.0;
    private static final double FLARE_FLIGHT = 0.45;
    /** Pb key: most survivors delivered to safe houses in one run. Not "zombie." - that prefix is a pace. */
    static final String SAVED_KEY = "zrun.saved";

    /* Figure kinds for drawFigure. */
    private static final int K_YOU = 0;
    private static final int K_SURVIVOR = 1;
    private static final int K_WALKER = 2;
    private static final int K_RUNNER = 3;
    private static final int K_BRUTE = 4;

    /* Who is speaking: -1 you, 0-5 a walker, and these. */
    private static final int WHO_YOU = -1;
    private static final int WHO_BRUTE = 10;
    private static final int WHO_RUNNER = 20;
    private static final int WHO_GROUP = 30;
    private static final int WHO_WAITING = 40;

    private static final int WALKERS = 6;
    private static final int RUNNER_SLOTS = 2;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path darkPath = new Path();
    private final Path beamPath = new Path();

    private float hordePaceSec = 150f;    // 2:30 /500
    private Phase phase = Phase.READY;
    private double gap;
    /** Seconds spent in the horde's grip; it ends the run only once this runs out. */
    private double grabbedSeconds;
    private static final double GRAB_LIMIT = 6.0;
    private double runStartMeters;
    private double runStartSeconds;
    private double nextSurgeAt;
    private double surgeUntil;
    private double safeUntil;
    private int nextSafeHouse = SAFE_EVERY;
    private double creep;
    private final Fx.Shake shake = new Fx.Shake();
    private final Fx.Particles dust = new Fx.Particles();
    private final Fx.Particles sparks = new Fx.Particles();
    private float dustAccum;
    private boolean caughtFx;

    /* Brute: 0 in the pack, 1 winding up, 2 charging, 3 lumbering back. */
    private int bruteState;
    private double bruteLead;
    private double bruteUntil;
    private double bruteBoost;
    private double nextBruteAt;

    /* Runners: 0 off, 1 dashing, 2 tired and dropping back, 3 fleeing a flare. */
    private final int[] runState = new int[RUNNER_SLOTS];
    private final double[] runLead = new double[RUNNER_SLOTS];
    private final double[] runUntil = new double[RUNNER_SLOTS];
    private final double[] runBoost = new double[RUNNER_SLOTS];
    private double nextRunnerAt;

    /* Flares. */
    private int flares;
    private int chargePips;
    private double flareFiredAt = -10;
    private float flareFromX;
    private float flareToX;
    /** Set on firing, consumed by the next frame: a tap fires between frames, so aim by flag, not by time. */
    private boolean flareAimPending;
    private double pushLeft;
    private double stunUntil;
    private double hardStrokeAt = -10;
    private int lastMeterStroke = -1;
    private boolean meterPowered;
    private int peakWatts;
    /** The counter fallback already judged a stroke in this status; the meter's first stroke is that one. */
    private boolean judgedByCounter;

    /* Night. */
    private boolean night;
    private float lookBack;

    /* Survivors: one waiting ahead, up to four running with you. */
    private static final String[] NAMES = {"MAYA", "DEV", "ROSA", "OTTO", "JUNE", "SAM", "KOFI", "LENA", "IVY", "BEN"};
    private static final int[] SHIRTS = {0xFF6F8CFF, 0xFFE0A33A, 0xFFB86FE0, 0xFF4FB86A, 0xFFE06F9A, 0xFF4FC3E0};
    private double survivorAt;
    private int survivorName;
    private int survivorShirt;
    private int nameCursor;
    private int groupCount;
    private final int[] groupName = new int[MAX_GROUP];
    private final int[] groupShirt = new int[MAX_GROUP];
    private int savedThisRun;

    /* Zombie eyes recorded while drawing, redrawn glowing over the dark in night mode. */
    private final float[] eyeX = new float[24];
    private final float[] eyeY = new float[24];
    private int eyeCount;

    /* On-canvas buttons (no header chips can be added from here). */
    private float nightL, nightT, nightR, nightB;
    private float flareL, flareT, flareR, flareB;

    /* Speech bubbles. */
    // 3.19.6: 2.0 left the runner and horde as specks on the tablet's 1920-wide screen.
    private static final float FIGURE_SCALE = 3.2f;
    private static final String[] LINES_START = {"Is he... rowing?", "Lunch is getting away!", "Walk faster, Gary!", "Fresh legs!"};
    private static final String[] LINES_CHASE = {"BRAAAINS", "Mmm, cardio-flavoured", "We never skip leg day", "Wait up!", "Is that a rowing machine?"};
    private static final String[] LINES_CLOSE = {"Just a little nibble!", "Almost... there...", "I can smell the sweat!", "So close!", "Nom nom nom?"};
    private static final String[] LINES_SURGE = {"CARDIO DAY!", "CHAAARGE!", "Sprint intervals!", "Faster, team!"};
    private static final String[] LINES_SAFE = {"Aww, not the safe house", "We'll wait. We have time.", "No fair!", "Snack break over there?"};
    private static final String[] LINES_GRABBED = {"Hold still, snack!", "Gotcha!", "Dinner is served!", "Don't wriggle!"};
    private static final String[] LINES_FAR = {"Slow down!", "No fair, you have a boat!", "My legs fell off", "Are we there yet?", "I need a nap"};
    private static final String[] LINES_YOU = {"Not today!", "Catch me if you can!", "Row, row, row your... bye!", "Is that all you've got?"};
    private static final String[] LINES_BRUTE = {"HNNNNGH!", "BRUTE... SMASH... BOAT!", "Me go FAST now", "Out of my way, Gary!"};
    private static final String[] LINES_RUNNER = {"I used to do marathons!", "Zoom zoom!", "Can't stop, won't stop!", "Personal best incoming!"};
    private static final String[] LINES_TIRED = {"Stitch... stitch...", "Need... a... sit down", "Carry me, Gary"};
    private static final String[] LINES_FLARE = {"MY EYES!", "Too bright! Too bright!", "Who turned on the sun?", "Aaah, fireworks!"};
    private static final String[] LINES_HELP = {"HELP! Over here!", "Wait for me!", "Don't leave me!"};
    private static final String[] LINES_RESCUED = {"You came back for me!", "Thank you! Go go go!", "I can row too, you know", "Don't let them get us!"};
    private static final String[] LINES_GROUP = {"Faster!!", "They're right behind us!", "Keep pulling!", "Is it much further?"};
    private static final String[] LINES_LOST = {"Got one!", "Yoink!", "One for the road!"};
    private static final String[] LINES_HOME = {"We made it!", "Home sweet safe house", "Bolt the door!"};
    private final java.util.Random chatter = new java.util.Random();
    private String bubble = "";
    private int bubbleWho;
    private double bubbleUntil;
    private double nextBubbleAt;
    private boolean wasSurging;
    private boolean wasSafe;
    private boolean wasGrabbed;
    private float spkX;
    private float spkY;

    /* Scenery that moves on its own, so the night is never still. */
    private final float[] batX = new float[5];
    private final float[] batY = new float[5];
    private final float[] batSpeed = new float[5];
    private double lightningAt = -10;
    private double nextLightning;
    private int bestMilestone;
    private String popup = "";
    private int popupColour = ACCENT;
    private double popupUntil;
    private final Fx.Particles smoke = new Fx.Particles();
    private float smokeAccum;

    /* Sky gradient, rebuilt only when its colour step or the height changes. */
    private LinearGradient skyShader;
    private int skyShaderTop;
    private float skyShaderH;

    ZombieRunGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    void setHordePace(float secondsPer500) {
        hordePaceSec = secondsPer500;
        phase = Phase.READY;
    }

    float hordePace() {
        return hordePaceSec;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        caughtFx = false;
        gap = START_GAP;
        grabbedSeconds = 0;
        nextSafeHouse = SAFE_EVERY;
        creep = 0;
        surgeUntil = 0;
        safeUntil = 0;
        bubbleUntil = 0;
        nextBubbleAt = 1.5;
        wasSurging = false;
        wasSafe = false;
        wasGrabbed = false;
        bestMilestone = 0;
        bruteState = 0;
        bruteLead = 0;
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            runState[i] = 0;
            runLead[i] = 0;
        }
        flares = 0;
        chargePips = 0;
        flareFiredAt = -10;
        flareAimPending = false;
        judgedByCounter = false;
        lostFxPending = false;
        // sessionSeconds restarts at 0: stale times from the last run would hold off the lightning.
        lightningAt = -10;
        nextLightning = 0;
        pushLeft = 0;
        stunUntil = 0;
        hardStrokeAt = -10;
        lastMeterStroke = -1;
        meterPowered = false;
        peakWatts = 0;
        lookBack = 0;
        groupCount = 0;
        savedThisRun = 0;
        survivorAt = 180;
        nameCursor = chatter.nextInt(NAMES.length);
        pickSurvivor();
        popupUntil = 0;
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < batX.length; i++) {
            batX[i] = r.nextFloat();
            batY[i] = 0.12f + r.nextFloat() * 0.3f;
            batSpeed[i] = 0.03f + r.nextFloat() * 0.05f;
        }
    }

    private void pickSurvivor() {
        survivorName = nameCursor;
        nameCursor = (nameCursor + 1) % NAMES.length;
        survivorShirt = SHIRTS[chatter.nextInt(SHIRTS.length)];
    }

    /** Sets the next survivor ahead, clear of the next safe house so the two never stack. */
    private void placeNextSurvivor() {
        double at = survivorAt + 200 + chatter.nextDouble() * 120;
        double house = nextSafeHouse;
        while (at > house) {
            house += SAFE_EVERY;
        }
        if (house - at < 70) {
            at = house + 90;
        } else if (at - (house - SAFE_EVERY) < 70) {
            at = house - SAFE_EVERY + 90;
        }
        survivorAt = at;
        pickSurvivor();
    }

    private void say(String[] lines, int who) {
        bubble = lines[chatter.nextInt(lines.length)];
        bubbleWho = who;
        bubbleUntil = sessionSeconds + 2.8;
        nextBubbleAt = sessionSeconds + 4.0 + chatter.nextDouble() * 3.0;
    }

    private void pop(String text, int colour) {
        popup = text;
        popupColour = colour;
        popupUntil = sessionSeconds + 2.0;
    }

    /** Picks a line for the moment and who says it. Surges, safe houses and grabs speak at once. */
    private void chatter(double threat) {
        boolean surge = surging() && phase == Phase.RUNNING;
        boolean inSafe = safe();
        boolean grabbed = grabbedSeconds > 0;
        boolean event = (surge && !wasSurging) || (inSafe && !wasSafe) || (grabbed && !wasGrabbed);
        wasSurging = surge;
        wasSafe = inSafe;
        wasGrabbed = grabbed;
        if (!event && sessionSeconds < nextBubbleAt) {
            return;
        }
        String[] lines;
        int who = chatter.nextInt(WALKERS);
        if (phase == Phase.CAUGHT) {
            return;
        } else if (grabbed) {
            lines = LINES_GRABBED;
        } else if (surge) {
            lines = LINES_SURGE;
        } else if (inSafe) {
            lines = LINES_SAFE;
        } else if (phase == Phase.READY) {
            lines = LINES_START;
        } else if (threat < 14) {
            lines = LINES_CLOSE;
        } else if (groupCount > 0 && chatter.nextInt(3) == 0) {
            lines = LINES_GROUP;
            who = WHO_GROUP + chatter.nextInt(groupCount);
        } else if (threat > 60 && chatter.nextInt(3) == 0) {
            lines = LINES_YOU;
            who = WHO_YOU;
        } else if (threat > 60) {
            lines = LINES_FAR;
        } else {
            lines = LINES_CHASE;
        }
        say(lines, who);
    }

    /** A comic speech bubble with a tail, pointing down at the speaker's head. */
    private void drawBubble(Canvas c, float headX, float headY, float w) {
        double left = bubbleUntil - sessionSeconds;
        if (left <= 0 || bubble.isEmpty()) {
            return;
        }
        float alpha = (float) Math.min(1.0, Math.min(left / 0.3, (2.8 - left) / 0.2 + 0.1));
        textPaint.setTextSize(dp(15f));
        float tw = textPaint.measureText(bubble);
        float padX = dp(12f);
        float bh = dp(34f);
        float bx = Math.max(dp(10f), Math.min(w - tw - padX * 2 - dp(10f), headX - tw / 2f - padX));
        float by = Math.max(dp(4f), headY - dp(22f) - bh);
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (235 * alpha));
        c.drawRoundRect(bx, by, bx + tw + padX * 2, by + bh, dp(14f), dp(14f), paint);
        path.reset();
        float tailX = Math.max(bx + dp(14f), Math.min(bx + tw + padX * 2 - dp(14f), headX));
        path.moveTo(tailX - dp(8f), by + bh - dp(1f));
        path.lineTo(tailX + dp(8f), by + bh - dp(1f));
        path.lineTo(headX, headY - dp(6f));
        path.close();
        c.drawPath(path, paint);
        paint.setAlpha(255);
        int textColour = bubbleWho == WHO_YOU ? 0xFF0E6E60
                : bubbleWho >= WHO_GROUP ? 0xFF1E3A8A : 0xFF3A1010;
        bold(c, bubble, bx + padX + tw / 2f, by + bh * 0.66f, 15f,
                (((int) (255 * alpha)) << 24) | (textColour & 0x00FFFFFF), Paint.Align.CENTER);
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.RUNNING;
            runStartMeters = sessionMeters;
            runStartSeconds = sessionSeconds;
            nextSurgeAt = sessionSeconds + 40;
            nextBruteAt = sessionSeconds + 30;
            nextRunnerAt = sessionSeconds + 50;
        }
        // Peak power is read here, never in onStroke (which fires ~1 s late, after power collapses).
        peakWatts = Math.max(peakWatts, s.watts);
        PulseMeter.Stroke st = s.meter.lastStroke;
        if (st != null && st.index != lastMeterStroke) {
            boolean first = lastMeterStroke < 0;
            lastMeterStroke = st.index;
            if (!first && !Double.isNaN(st.averagePowerW)) {
                // The pulse meter judges each stroke on its own, the moment it ends.
                boolean handover = !meterPowered && judgedByCounter;
                meterPowered = true;
                if (!handover) {
                    judgeStroke(st.averagePowerW);
                }
            }
        }
        judgedByCounter = false;
    }

    @Override
    protected void onStroke(int watts) {
        // Fallback before the meter has measured a coast: the peak reading since the last stroke.
        if (!meterPowered) {
            judgeStroke(peakWatts);
            judgedByCounter = true;
        }
        peakWatts = 0;
    }

    /** A hard stroke: three quarters of the way from the rower's typical watts toward their high (p90) watts. */
    private double hardWatts() {
        return profile.wattsAt(0.75);
    }

    private void judgeStroke(double watts) {
        if (phase != Phase.RUNNING || watts < hardWatts()) {
            return;
        }
        hardStrokeAt = sessionSeconds;
        if (flares >= MAX_FLARES) {
            chargePips = PIPS_PER_FLARE;
            return;
        }
        chargePips++;
        if (chargePips >= PIPS_PER_FLARE) {
            chargePips = 0;
            flares++;
            pop("FLARE READY", 0xFFFF7A4D);
        }
    }

    private void fireFlare() {
        if (flares <= 0 || phase != Phase.RUNNING) {
            return;
        }
        flares--;
        if (chargePips >= PIPS_PER_FLARE) {
            // Hard strokes banked while the pouch was full become the replacement flare.
            chargePips = 0;
            flares++;
        }
        flareFiredAt = sessionSeconds;
        flareAimPending = true;
        pushLeft += FLARE_PUSH;
        stunUntil = sessionSeconds + FLARE_STUN;
        grabbedSeconds = 0;
        scatterSpecials();
        shake.kick(dp(8f));
        say(LINES_FLARE, chatter.nextInt(WALKERS));
    }

    /** Runners flee back into the pack and the brute abandons its charge. */
    private void scatterSpecials() {
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] != 0) {
                runState[i] = 3;
            }
        }
        if (bruteState == 1 || bruteState == 2) {
            bruteState = 3;
            nextBruteAt = sessionSeconds + 35 + chatter.nextDouble() * 20;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) {
            float x = e.getX();
            float y = e.getY();
            if (x >= nightL && x <= nightR && y >= nightT && y <= nightB) {
                night = !night;
                return true;
            }
            if (phase == Phase.RUNNING && x >= flareL && x <= flareR && y >= flareT && y <= flareB) {
                if (flares > 0) {
                    fireFlare();
                } else {
                    pop("NO FLARES - PULL HARD", WARN);
                }
                return true;
            }
            if (phase == Phase.CAUGHT) {
                start();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    private double runMeters() {
        return phase == Phase.READY ? 0 : sessionMeters - runStartMeters;
    }

    private boolean surging() {
        return sessionSeconds < surgeUntil;
    }

    private boolean safe() {
        return sessionSeconds < safeUntil;
    }

    private boolean stunned() {
        return sessionSeconds < stunUntil;
    }

    /** Horde speed: base pace, +1.5% per minute, +30% in a surge, stunned by a flare, stopped at a safe house. */
    private float hordeSpeed() {
        if (safe()) {
            return 0f;
        }
        float base = 500f / hordePaceSec;
        return base * (float) (1 + creep) * (surging() ? 1.3f : 1f) * (stunned() ? 0.4f : 1f);
    }

    /** How far the brute or a runner has broken out ahead of the pack, metres. */
    private double maxLead() {
        double lead = bruteLead;
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] != 0) {
                lead = Math.max(lead, runLead[i]);
            }
        }
        return lead;
    }

    /** Distance from you to the nearest zombie. */
    private double threat() {
        return gap - maxLead();
    }

    private boolean runnerDashing() {
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] == 1) {
                return true;
            }
        }
        return false;
    }

    private void stepSpecials(float dt, float hs) {
        double t = sessionSeconds;
        // Brute.
        if (bruteState == 0 && !safe() && t >= nextBruteAt) {
            bruteState = 1;
            bruteUntil = t + 2.5;
            say(LINES_BRUTE, WHO_BRUTE);
        } else if (bruteState == 1) {
            shake.kick(dp(2f));
            if (safe()) {
                bruteState = 3;
            } else if (t >= bruteUntil) {
                bruteState = 2;
                bruteUntil = t + 4;
                // A charge that closes further the further ahead you are, so a banked lead still has to be defended.
                bruteBoost = 2.4 + gap * 0.02;
            }
        } else if (bruteState == 2) {
            bruteLead += bruteBoost * dt;
            if (t >= bruteUntil || safe()) {
                bruteState = 3;
            }
        } else if (bruteState == 3) {
            bruteLead -= 1.5 * dt;
            if (bruteLead <= 0) {
                bruteLead = 0;
                bruteState = 0;
                if (nextBruteAt <= t) {
                    nextBruteAt = t + 35 + chatter.nextDouble() * 20;
                }
            }
        }
        // Runners.
        if (!safe() && t >= nextRunnerAt) {
            for (int i = 0; i < RUNNER_SLOTS; i++) {
                if (runState[i] == 0) {
                    runState[i] = 1;
                    runLead[i] = 0;
                    runUntil[i] = t + 9;
                    runBoost[i] = 1.6 + gap * 0.03;
                    say(LINES_RUNNER, WHO_RUNNER + i);
                    break;
                }
            }
            nextRunnerAt = t + 30 + chatter.nextDouble() * 15;
        }
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] == 1) {
                runLead[i] += runBoost[i] * dt;
                if (t >= runUntil[i] || safe()) {
                    runState[i] = 2;
                    if (bubbleUntil < t) {
                        say(LINES_TIRED, WHO_RUNNER + i);
                    }
                }
            } else if (runState[i] == 2 || runState[i] == 3) {
                double back = runState[i] == 3 ? 6.0 : Math.max(0.8, 0.25 * hs);
                runLead[i] -= back * dt;
                if (runLead[i] <= 0) {
                    runLead[i] = 0;
                    runState[i] = 0;
                }
            }
        }
        // Nobody passes you: a zombie that reaches you stops at you.
        double cap = Math.max(0, gap);
        bruteLead = Math.min(bruteLead, cap);
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            runLead[i] = Math.min(runLead[i], cap);
        }
    }

    /** Out-rowed: whoever broke out and caught you is knocked back and gives up the chase. */
    private void shoveSpecialsBack(double maxLead) {
        double limit = Math.max(0, maxLead);
        if (bruteLead > limit) {
            bruteLead = limit;
            if (bruteState == 1 || bruteState == 2) {
                bruteState = 3;
                nextBruteAt = sessionSeconds + 35 + chatter.nextDouble() * 20;
            }
        }
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] != 0 && runLead[i] > limit) {
                runLead[i] = limit;
                if (runState[i] == 1) {
                    runState[i] = 2;
                }
            }
        }
    }

    private void loseSurvivor() {
        groupCount--;
        int name = groupName[groupCount];
        pop(NAMES[name] + " WAS GRABBED", BAD);
        gap += 8;   // more than a full group's spacing, so one grab never cascades into the next
        stunUntil = Math.max(stunUntil, sessionSeconds + 2.5);
        scatterSpecials();
        shake.kick(dp(9f));
        say(LINES_LOST, chatter.nextInt(WALKERS));
        lostFxPending = true;
    }

    private boolean lostFxPending;

    private void reachSafeHouse() {
        safeUntil = sessionSeconds + SAFE_SECONDS;
        nextSafeHouse += SAFE_EVERY;
        boolean restock = flares < MAX_FLARES;
        if (restock) {
            flares++;
        }
        if (groupCount > 0) {
            savedThisRun += groupCount;
            bests.recordHighest(SAVED_KEY, savedThisRun);
            pop("+" + groupCount + " SAVED" + (restock ? "  ·  +1 FLARE" : ""), ACCENT);
            say(LINES_HOME, WHO_GROUP);
            groupCount = 0;
        } else if (restock) {
            pop("SAFE HOUSE  ·  +1 FLARE", ACCENT);
        }
    }

    private void reachSurvivor(double threat) {
        if (threat >= RESCUE_CLEAR && groupCount < MAX_GROUP) {
            groupName[groupCount] = survivorName;
            groupShirt[groupCount] = survivorShirt;
            groupCount++;
            pop("RESCUED " + NAMES[survivorName], 0xFF8FB4FF);
            say(LINES_RESCUED, WHO_GROUP + groupCount - 1);
        } else if (groupCount >= MAX_GROUP) {
            pop("GROUP FULL - " + NAMES[survivorName] + " HIDES", WARN);
        } else {
            pop("TOO CLOSE - " + NAMES[survivorName] + " HID", WARN);
        }
        placeNextSurvivor();
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();

        if (phase == Phase.RUNNING) {
            creep = (sessionSeconds - runStartSeconds) / 60.0 * 0.015;
            if (!surging() && !safe() && sessionSeconds >= nextSurgeAt) {
                surgeUntil = sessionSeconds + 10;
                nextSurgeAt = sessionSeconds + 45 + Math.random() * 30;
            }
            if (runMeters() >= nextSafeHouse) {
                reachSafeHouse();
            }
            if (runMeters() >= survivorAt) {
                reachSurvivor(threat());
            }
            float hs = hordeSpeed();
            gap += (speed - hs) * dt;
            if (pushLeft > 0) {
                double d = Math.min(pushLeft, 14.0 * dt);
                gap += d;
                pushLeft -= d;
            }
            gap = Math.max(0, Math.min(MAX_GAP, gap));
            stepSpecials(dt, hs);
            double threat = threat();
            if (groupCount > 0 && threat <= groupCount * SURV_SPACING) {
                // They reach the survivor at the back of your group before they reach you.
                loseSurvivor();
                grabbedSeconds = 0;
            } else if (threat <= 0) {
                // Grabbed, not gone. Out-row them and you break the grip; only staying slower than
                // the horde finishes it.
                if (speed > hs) {
                    grabbedSeconds = Math.max(0, grabbedSeconds - dt * 2.5);
                    // A shove of daylight, so the escape reads on screen. The zombie holding you is
                    // shoved back - moving the whole pack instead (gap = lead + 2) handed you +2 m of
                    // pack gap every time a dashing runner re-caught you, so a runner sped you up.
                    if (gap < 2.0) {
                        gap = 2.0;
                    }
                    shoveSpecialsBack(gap - 2.0);
                } else {
                    grabbedSeconds += dt;
                }
                // Hands are on the handle: a flare in the pocket fires itself.
                if (flares > 0 && grabbedSeconds > 0.5) {
                    fireFlare();
                }
                if (grabbedSeconds >= GRAB_LIMIT) {
                    phase = Phase.CAUGHT;
                    bests.recordHighest("zombie." + Math.round(hordePaceSec), (float) runMeters());
                    if (savedThisRun > 0) {
                        bests.recordHighest(SAVED_KEY, savedThisRun);
                    }
                }
            } else {
                grabbedSeconds = 0;
            }
        }
        double threat = threat();
        boolean surgeStarting = surging() && phase == Phase.RUNNING && !wasSurging;
        chatter(threat);
        if (surgeStarting || (surging() && sessionSeconds > nextLightning)) {
            lightningAt = sessionSeconds;
            nextLightning = sessionSeconds + 2.5 + Math.random() * 3;
            shake.kick(dp(6f));
        }
        if (phase == Phase.RUNNING) {
            int milestone = (int) (gap / 50) * 50;
            if (milestone >= 50 && milestone > bestMilestone) {
                bestMilestone = milestone;
                pop("+" + milestone + " m CLEAR!", ACCENT);
            }
        }
        float danger = (float) Math.max(0, 1 - threat / 30.0);
        if (danger > 0.3f) {
            shake.kick(danger * dp(5f));
        }
        if (surging() && phase == Phase.RUNNING) {
            shake.kick(dp(1.5f));
        }
        shake.step(dt);
        dust.step(dt, dp(60f));
        sparks.step(dt, dp(90f));
        boolean closing = phase == Phase.RUNNING
                && (threat < 28 || bruteState == 1 || bruteState == 2 || runnerDashing());
        lookBack += ((closing ? 1f : 0f) - lookBack) * Math.min(1f, dt * 3f);

        // Sky reddens as they close in. Quantised so the gradient is rebuilt a handful of times, not every frame.
        float dq = Math.round(danger * 12f) / 12f;
        int skyTop = blend(0xFF1B2A44, 0xFF5A1010, dq);
        if (skyShader == null || skyTop != skyShaderTop || h != skyShaderH) {
            skyShader = new LinearGradient(0, 0, 0, h * 0.7f, skyTop, 0xFF0A0E14, Shader.TileMode.CLAMP);
            skyShaderTop = skyTop;
            skyShaderH = h;
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        c.save();
        c.translate(shake.dx, shake.dy);
        float groundY = h * 0.70f;
        eyeCount = 0;

        // Moon, low and large, redder as the horde closes.
        Fx.glow(c, w * 0.80f, h * 0.20f, dp(70f), blend(0x66DCEBF7, 0x66FF6A4D, dq));
        paint.setColor(blend(0xFFE9EEF5, 0xFFFF8A6A, danger));
        c.drawCircle(w * 0.80f, h * 0.20f, dp(26f), paint);
        float ppm = w / 90f;   // 90 m across the screen
        double scroll = sessionMeters;

        // Bats flapping across the sky.
        for (int i = 0; i < batX.length; i++) {
            batX[i] -= batSpeed[i] * dt;
            if (batX[i] < -0.05f) {
                batX[i] = 1.05f;
                batY[i] = 0.10f + (float) Math.random() * 0.3f;
            }
            float bx = batX[i] * w;
            float by = batY[i] * h + (float) Math.sin(sessionSeconds * 2 + i) * dp(10f);
            float flap = (float) Math.sin(sessionSeconds * 14 + i * 2) * dp(7f);
            paint.setColor(0xFF05070C);
            paint.setStrokeWidth(dp(2.5f));
            c.drawLine(bx, by, bx - dp(11f), by - flap, paint);
            c.drawLine(bx, by, bx + dp(11f), by - flap, paint);
            c.drawCircle(bx, by, dp(3f), paint);
        }

        // Far hills, slow parallax.
        paint.setColor(0xFF141C2B);
        path.reset();
        path.moveTo(0, groundY);
        for (float x = 0; x <= w + dp(40f); x += dp(40f)) {
            float wx = (float) ((x + scroll * ppm * 0.15) / dp(160f));
            path.lineTo(x, groundY - dp(40f) - (float) Math.abs(Math.sin(wx) * dp(50f)));
        }
        path.lineTo(w, groundY);
        path.close();
        c.drawPath(path, paint);

        // Glowing eyes blinking in the dark between the hills.
        for (int i = 0; i < 6; i++) {
            float ex = (float) (((i * 311 + 90) - scroll * ppm * 0.25) % (w + dp(200f)));
            if (ex < 0) {
                ex += w + dp(200f);
            }
            boolean open = ((int) (sessionSeconds * 1.3 + i * 0.7)) % 4 != 0;
            if (open) {
                float ey = groundY - dp(28f) - (i % 3) * dp(12f);
                paint.setColor(i % 2 == 0 ? 0xFFFFD23A : 0xFFFF4D4D);
                c.drawCircle(ex, ey, dp(2.2f), paint);
                c.drawCircle(ex + dp(8f), ey, dp(2.2f), paint);
            }
        }

        // Tombstones in a graveyard strip, mid parallax.
        float stoneGap = dp(110f);
        float soff = (float) ((scroll * ppm * 0.3) % stoneGap);
        for (float x = -soff; x < w + stoneGap; x += stoneGap) {
            int k = (int) Math.floor((x + scroll * ppm * 0.3) / stoneGap);
            float sh = dp(16f) + ((k * 7) % 3) * dp(6f);
            float sx = x + ((k * 13) % 5) * dp(8f);
            paint.setColor(0xFF2A3342);
            c.drawRoundRect(sx - dp(7f), groundY - sh, sx + dp(7f), groundY, dp(6f), dp(6f), paint);
            paint.setColor(0xFF394356);
            c.drawRect(sx - dp(1.2f), groundY - sh + dp(4f), sx + dp(1.2f), groundY - sh + dp(11f), paint);
            c.drawRect(sx - dp(4f), groundY - sh + dp(6f), sx + dp(4f), groundY - sh + dp(8.5f), paint);
        }

        // Dead trees, mid parallax.
        paint.setColor(0xFF0E1420);
        float treeGap = dp(150f);
        float toff = (float) ((scroll * ppm * 0.45) % treeGap);
        for (float x = -toff; x < w + treeGap; x += treeGap) {
            float th = dp(60f) + ((int) (x / treeGap) % 3) * dp(18f);
            c.drawRect(x - dp(3f), groundY - th, x + dp(3f), groundY, paint);
            c.drawLine(x, groundY - th * 0.7f, x + dp(18f), groundY - th * 0.95f, paint);
            c.drawLine(x, groundY - th * 0.5f, x - dp(16f), groundY - th * 0.75f, paint);
        }

        // Ground with fence posts scrolling at your speed.
        paint.setColor(0xFF1A2416);
        c.drawRect(0, groundY, w, h, paint);
        paint.setColor(0xFF2A3A22);
        float post = dp(70f);
        float off = (float) ((scroll * ppm) % post);
        for (float x = -off; x < w; x += post) {
            c.drawRect(x, groundY - dp(18f), x + dp(4f), groundY, paint);
        }

        // Fog rolling low over the ground.
        for (int i = 0; i < 5; i++) {
            float fx = (float) (((i * 420) - scroll * ppm * 0.6 - sessionSeconds * dp(12f)) % (w + dp(500f)));
            if (fx < -dp(250f)) {
                fx += w + dp(500f);
            }
            paint.setColor(0x1ECBD5E6);
            c.drawOval(fx - dp(220f), groundY - dp(26f), fx + dp(220f), groundY + dp(18f), paint);
        }

        float youX = w * 0.62f;
        spkX = youX;
        spkY = groundY - 46f * FIGURE_SCALE * dp(1f);

        // Safe house ahead, if one is in view.
        float houseX = youX + (float) (nextSafeHouse - runMeters()) * ppm;
        boolean houseVisible = phase == Phase.RUNNING && houseX < w + dp(60f);
        if (safe() && phase == Phase.RUNNING) {
            // Just reached: the house you are standing at, not the next one 500 m on.
            houseX = youX + (float) (nextSafeHouse - SAFE_EVERY - runMeters()) * ppm;
            houseVisible = houseX > -dp(60f);
        }
        if (houseVisible) {
            drawSafeHouse(c, houseX, groundY, dt);
        }
        smoke.step(dt, -dp(4f));
        smoke.draw(c);

        // A survivor waiting on a wrecked car ahead.
        float survX = youX + (float) (survivorAt - runMeters()) * ppm;
        boolean survVisible = phase == Phase.RUNNING && survX < w + dp(80f) && survX > youX + dp(20f);
        if (survVisible) {
            drawWreck(c, survX, groundY, threat);
        }

        // You, running, with a glow and dust off your heels.
        Fx.glow(c, youX, groundY - dp(20f), dp(48f), 0x4035D0BA);
        if (speed > 0.5f && phase == Phase.RUNNING) {
            dustAccum += dt * speed * 6f;
            while (dustAccum >= 1f) {
                dustAccum -= 1f;
                dust.spawn(youX - dp(6f), groundY, -dp(30f) - (float) Math.random() * dp(40f),
                        -dp(10f) - (float) Math.random() * dp(30f), 0.5f, dp(2.5f), 0xAA6B5A3A, true);
            }
        }
        dust.draw(c);
        if (!night) {
            drawTorch(c, youX, groundY);
        }

        // Survivors running behind you, between you and the horde.
        for (int i = groupCount - 1; i >= 0; i--) {
            float gx = youX - (float) ((i + 1) * SURV_SPACING) * ppm;
            drawFigure(c, gx, groundY, groupShirt[i], speed, (float) (scroll * 3.0 + i * 1.7), K_SURVIVOR, false);
            if (bubbleWho == WHO_GROUP + i) {
                spkX = gx;
                spkY = groundY - 46f * FIGURE_SCALE * 0.9f * dp(1f);
            }
        }
        if (lostFxPending) {
            lostFxPending = false;
            float lx = youX - (float) ((groupCount + 1) * SURV_SPACING) * ppm;
            sparks.burst(lx, groundY - dp(40f), 30, dp(140f), 0.8f, dp(3f), 0xFF8FB4FF, true);
        }
        drawFigure(c, youX, groundY, ACCENT, speed, (float) (scroll * 3.0), K_YOU, false);

        // The horde: walkers, then the brute, then any runners out in front.
        float hs = hordeSpeed();
        float hordeX = youX - (float) gap * ppm;
        for (int i = 0; i < WALKERS; i++) {
            float zx = hordeX - (i + 1) * dp(46f) - (i % 3) * dp(14f);
            float bob = (float) Math.sin(sessionSeconds * 6 + i) * dp(2f);
            drawFigure(c, zx, groundY + bob, i % 2 == 0 ? BAD : 0xFF9A3B32, hs,
                    (float) (scroll * 2.2 + i * 40), K_WALKER, false);
            if (i == bubbleWho) {
                spkX = zx;
            }
        }
        float bruteX = hordeX - dp(10f) + (float) bruteLead * ppm;
        float bruteShake = bruteState == 1 ? (float) Math.sin(sessionSeconds * 60) * dp(3f) : 0f;
        if (bruteState == 1 || bruteState == 2) {
            Fx.glow(c, bruteX, groundY - dp(90f), dp(110f), 0x55FF2A1A);
        }
        drawFigure(c, bruteX + bruteShake, groundY, 0xFF4A3A5A,
                bruteState == 2 ? hs + (float) bruteBoost : hs,
                (float) (scroll * (bruteState == 2 ? 2.6 : 1.4)), K_BRUTE, bruteState == 1);
        if (bubbleWho == WHO_BRUTE) {
            spkX = bruteX;
            spkY = groundY - 46f * FIGURE_SCALE * 1.5f * dp(1f);
        }
        for (int i = 0; i < RUNNER_SLOTS; i++) {
            if (runState[i] == 0) {
                continue;
            }
            float rx = hordeX + (float) runLead[i] * ppm + i * dp(12f);
            float rs = runState[i] == 1 ? hs + (float) runBoost[i] : Math.max(0.5f, hs * 0.75f);
            drawFigure(c, rx, groundY, 0xFFC7A23A, rs, (float) (scroll * 4.0 + sessionSeconds * 6 + i * 3),
                    K_RUNNER, false);
            if (bubbleWho == WHO_RUNNER + i) {
                spkX = rx;
            }
        }
        if (bubbleWho >= 0 && bubbleWho < WALKERS) {
            spkX = Math.max(dp(40f), spkX);
        }

        // The flare: an arc from your hand into the horde, then a burst of red light.
        double sinceFlare = sessionSeconds - flareFiredAt;
        float burstY = groundY - dp(70f);
        if (sinceFlare >= 0 && sinceFlare < FLARE_FLIGHT + 3.0) {
            if (flareAimPending) {
                flareAimPending = false;
                flareFromX = youX;
                flareToX = Math.max(dp(40f), hordeX - dp(40f));
            }
            if (sinceFlare < FLARE_FLIGHT) {
                float k = (float) (sinceFlare / FLARE_FLIGHT);
                float fx = flareFromX + (flareToX - flareFromX) * k;
                float fy = groundY - dp(90f) - (float) Math.sin(k * Math.PI) * dp(160f);
                Fx.glow(c, fx, fy, dp(40f), 0xCCFF6A3A);
                paint.setColor(0xFFFFE0B0);
                c.drawCircle(fx, fy, dp(5f), paint);
                sparks.spawn(fx, fy, (float) (Math.random() - 0.5) * dp(60f), dp(20f), 0.5f, dp(2.5f), 0xFFFF9A4A, true);
            } else if (sinceFlare - dt < FLARE_FLIGHT) {
                sparks.burst(flareToX, burstY, 60, dp(260f), 1.2f, dp(3.5f), 0xFFFF7A3A, true);
                sparks.burst(flareToX, burstY, 25, dp(160f), 0.9f, dp(2.5f), 0xFFFFF0B0, true);
                shake.kick(dp(10f));
            }
        }
        sparks.draw(c);

        if (night) {
            drawNight(c, w, h, youX, groundY, houseVisible ? houseX : -1, survVisible ? survX : -1, sinceFlare, burstY);
        } else if (sinceFlare >= FLARE_FLIGHT && sinceFlare < FLARE_FLIGHT + 2.0) {
            float a = (float) (1 - (sinceFlare - FLARE_FLIGHT) / 2.0);
            Fx.glow(c, flareToX, burstY, dp(260f), ((int) (150 * a) << 24) | 0xFF4A2A);
        }

        drawBubble(c, spkX, spkY, w);
        if (phase == Phase.CAUGHT && !caughtFx) {
            caughtFx = true;
            dust.burst(youX, groundY - dp(24f), 40, dp(120f), 0.9f, dp(3.5f), 0xFFB3122E, true);
            shake.kick(dp(14f));
        }
        c.restore();
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        Fx.vignette(c, w, h, 0.35f + danger * 0.65f, danger > 0.2f ? 0x7A0A0A : 0x000000);
        double sinceFlash = sessionSeconds - lightningAt;
        if (sinceFlash >= 0 && sinceFlash < 0.35) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (150 * (1 - sinceFlash / 0.35)));
            c.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
            paint.setColor(0xFFEAF2FF);
            paint.setStrokeWidth(dp(3f));
            float lx = w * (0.3f + (float) ((lightningAt * 37) % 1.0) * 0.5f);
            float ly = 0;
            for (int k = 0; k < 6; k++) {
                float nx = lx + (float) Math.sin(lightningAt * 50 + k * 3) * dp(26f);
                float ny = ly + h * 0.1f;
                c.drawLine(lx, ly, nx, ny, paint);
                lx = nx;
                ly = ny;
            }
        }
        if (sinceFlare >= FLARE_FLIGHT && sinceFlare < FLARE_FLIGHT + 0.5) {
            // The whole screen flushes red as it goes off.
            paint.setColor(0xFFFF3A1A);
            paint.setAlpha((int) (90 * (1 - (sinceFlare - FLARE_FLIGHT) / 0.5)));
            c.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }
        if (sessionSeconds < popupUntil) {
            float rise = (float) (2.0 - (popupUntil - sessionSeconds)) * dp(30f);
            bold(c, popup, w * 0.62f, h * 0.45f - rise, 26f, popupColour, Paint.Align.CENTER);
        }
        if (sessionSeconds - hardStrokeAt < 0.9) {
            float rise = (float) (sessionSeconds - hardStrokeAt) * dp(40f);
            bold(c, "HARD STROKE", youX, groundY - dp(175f) - rise, 14f, 0xFFFF9A5A, Paint.Align.CENTER);
        }

        drawHud(c, w, h, speed, threat);
    }

    private void drawHud(Canvas c, float w, float h, float speed, double threat) {
        String big;
        int col;
        if (phase == Phase.READY) {
            big = "THEY'RE " + Math.round(START_GAP) + " M BACK";
            col = DIM;
        } else if (phase == Phase.CAUGHT) {
            big = "EATEN";
            col = BAD;
        } else if (grabbedSeconds > 0) {
            big = "GRABBED - PULL!";
            col = BAD;
        } else {
            big = Math.round(threat) + " m";
            col = threat > 30 ? ACCENT : threat > 12 ? WARN : BAD;
        }
        bold(c, big, w / 2f, h * 0.17f, phase == Phase.RUNNING ? 54f : 30f, col, Paint.Align.CENTER);
        String cap;
        int capCol = FAINT;
        double toSurvivor = survivorAt - runMeters();
        if (phase == Phase.READY) {
            cap = "horde runs " + PersonalBests.formatPace(hordePaceSec) + " /500 - take a stroke";
        } else if (phase == Phase.CAUGHT) {
            cap = "survived " + Math.round(runMeters()) + " m  ·  saved " + savedThisRun + "  ·  tap to run again";
        } else if (safe()) {
            cap = "SAFE HOUSE - they fall back for " + Math.round(safeUntil - sessionSeconds) + "s";
            capCol = ACCENT;
        } else if (grabbedSeconds > 0) {
            cap = "out-row them to break free - " + Math.round(GRAB_LIMIT - grabbedSeconds) + "s";
            capCol = BAD;
        } else if (bruteState == 1) {
            cap = "THE BRUTE IS WINDING UP - PULL!";
            capCol = BAD;
        } else if (bruteState == 2) {
            cap = "BRUTE CHARGING - " + Math.max(0, Math.round(bruteUntil - sessionSeconds)) + "s";
            capCol = BAD;
        } else if (runnerDashing()) {
            cap = "RUNNER BREAKING OUT - HOLD THE GAP";
            capCol = WARN;
        } else if (surging()) {
            cap = "THEY'RE SURGING - " + Math.round(surgeUntil - sessionSeconds) + "s";
            capCol = BAD;
        } else if (sessionSeconds > nextSurgeAt - 3) {
            cap = "SOMETHING'S STIRRING...";
        } else if (toSurvivor < 90) {
            cap = "SURVIVOR IN " + Math.round(toSurvivor) + " m - arrive " + Math.round(RESCUE_CLEAR)
                    + " m clear or they hide";
            capCol = threat >= RESCUE_CLEAR ? 0xFF8FB4FF : WARN;
        } else {
            cap = "nearest zombie  ·  safe house in " + Math.round(nextSafeHouse - runMeters()) + " m";
        }
        bold(c, cap, w / 2f, h * 0.17f + dp(22f), 11f, capCol, Paint.Align.CENTER);

        // Route bar to the next safe house, with the survivor on it.
        if (phase == Phase.RUNNING) {
            float bw = w * 0.30f;
            float bx = w / 2f - bw / 2f;
            float by = h * 0.17f + dp(36f);
            double segStart = nextSafeHouse - SAFE_EVERY;
            float f = (float) Math.max(0, Math.min(1, (runMeters() - segStart) / SAFE_EVERY));
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(bx, by, bx + bw, by + dp(5f), dp(3f), dp(3f), paint);
            paint.setColor(ACCENT);
            c.drawRoundRect(bx, by, bx + bw * f, by + dp(5f), dp(3f), dp(3f), paint);
            if (survivorAt < nextSafeHouse) {
                float sf = (float) ((survivorAt - segStart) / SAFE_EVERY);
                paint.setColor(0xFF8FB4FF);
                c.drawCircle(bx + bw * sf, by + dp(2.5f), dp(5f), paint);
            }
            paint.setColor(0xFFFFD27A);
            c.drawRect(bx + bw - dp(4f), by - dp(6f), bx + bw + dp(8f), by + dp(8f), paint);
        }

        bold(c, pace(speed), dp(16f), h * 0.15f, 22f, TEXT, Paint.Align.LEFT);
        label(c, "YOU /500", dp(16f), h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        float hs = hordeSpeed();
        bold(c, hs > 0 ? PersonalBests.formatPace(500f / hs) : "--:--",
                w - dp(16f), h * 0.15f, 22f, BAD, Paint.Align.RIGHT);
        label(c, stunned() ? "HORDE /500 - STUNNED" : "HORDE /500", w - dp(16f), h * 0.15f + dp(16f), 9f,
                stunned() ? 0xFFFF9A5A : FAINT, Paint.Align.RIGHT);

        drawNightButton(c);
        drawFlareButton(c, w, h);

        float fy = h - dp(12f);
        float col4 = w / 4f;
        stat(c, col4 * 0.5f, fy, Math.round(runMeters()) + " m", "SURVIVED");
        stat(c, col4 * 1.5f, fy, savedThisRun + (groupCount > 0 ? "  (+" + groupCount + " with you)" : ""),
                bests.has(SAVED_KEY) ? "SAVED  ·  BEST " + Math.round(bests.get(SAVED_KEY, 0)) : "SAVED");
        stat(c, col4 * 2.5f, fy, status == null ? "0" : status.strokeRate + " spm", "RATE");
        String key = "zombie." + Math.round(hordePaceSec);
        stat(c, col4 * 3.5f, fy, bests.has(key) ? Math.round(bests.get(key, 0)) + " m" : "--", "BEST");
    }

    private void drawNightButton(Canvas c) {
        nightL = dp(16f);
        nightT = dp(10f);
        nightR = dp(128f);
        nightB = dp(44f);
        paint.setColor(night ? 0xFF2A3B5E : 0x33FFFFFF);
        c.drawRoundRect(nightL, nightT, nightR, nightB, dp(17f), dp(17f), paint);
        float mx = nightL + dp(20f);
        float my = (nightT + nightB) / 2f;
        paint.setColor(night ? 0xFFFFE9A8 : DIM);
        c.drawCircle(mx, my, dp(8f), paint);
        paint.setColor(night ? 0xFF2A3B5E : 0xFF3A4252);
        c.drawCircle(mx + dp(4f), my - dp(3f), dp(7f), paint);
        bold(c, night ? "NIGHT ON" : "NIGHT", nightL + dp(36f), my + dp(5f), 12f, night ? TEXT : DIM,
                Paint.Align.LEFT);
    }

    private void drawFlareButton(Canvas c, float w, float h) {
        flareR = w - dp(16f);
        flareL = flareR - dp(200f);
        flareB = h - dp(56f);
        flareT = flareB - dp(64f);
        boolean ready = flares > 0 && phase == Phase.RUNNING;
        if (ready) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 5);
            Fx.glow(c, (flareL + flareR) / 2f, (flareT + flareB) / 2f, dp(120f),
                    ((int) (60 + 60 * pulse) << 24) | 0xFF5A2A);
        }
        paint.setColor(ready ? 0xFF7A2418 : 0x66202634);
        c.drawRoundRect(flareL, flareT, flareR, flareB, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(ready ? 0xFFFF7A4D : 0x55FFFFFF);
        c.drawRoundRect(flareL, flareT, flareR, flareB, dp(14f), dp(14f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, "FLARE", flareL + dp(14f), flareT + dp(26f), 18f, ready ? 0xFFFFE0C0 : DIM, Paint.Align.LEFT);
        // Flares held: little red sticks.
        for (int i = 0; i < MAX_FLARES; i++) {
            float sx = flareL + dp(100f) + i * dp(22f);
            paint.setColor(i < flares ? 0xFFFF4A2A : 0x33FFFFFF);
            c.drawRoundRect(sx, flareT + dp(8f), sx + dp(10f), flareT + dp(30f), dp(3f), dp(3f), paint);
            if (i < flares) {
                paint.setColor(0xFFFFE0A0);
                c.drawCircle(sx + dp(5f), flareT + dp(7f), dp(3f), paint);
            }
        }
        // Charge pips: three hard strokes make a flare.
        for (int i = 0; i < PIPS_PER_FLARE; i++) {
            float px = flareL + dp(20f) + i * dp(16f);
            paint.setColor(i < chargePips ? 0xFFFF9A5A : 0x33FFFFFF);
            c.drawCircle(px, flareB - dp(15f), dp(5f), paint);
        }
        label(c, "hard strokes > " + Math.round(hardWatts()) + " W", flareL + dp(66f), flareB - dp(11f), 9f,
                FAINT, Paint.Align.LEFT);
    }

    /** Pitch dark but for the torch, the lights of the living, and the flare. Zombies are their eyes. */
    private void drawNight(Canvas c, float w, float h, float youX, float groundY, float houseX, float survX,
                           double sinceFlare, float burstY) {
        float headY = groundY - 40f * FIGURE_SCALE * dp(1f);
        darkPath.reset();
        darkPath.setFillType(Path.FillType.WINDING);
        buildBeam(darkPath, youX, headY, dp(560f));
        darkPath.addCircle(youX, groundY - dp(50f), dp(95f), Path.Direction.CW);
        if (houseX >= 0) {
            darkPath.addCircle(houseX, groundY - dp(30f), dp(120f), Path.Direction.CW);
        }
        if (survX >= 0) {
            darkPath.addCircle(survX, groundY - dp(90f), dp(80f), Path.Direction.CW);
        }
        float flareLight = 0f;
        if (sinceFlare >= FLARE_FLIGHT && sinceFlare < FLARE_FLIGHT + 3.0) {
            flareLight = (float) (1 - (sinceFlare - FLARE_FLIGHT) / 3.0);
            darkPath.addCircle(flareToX, burstY, dp(80f) + dp(520f) * flareLight, Path.Direction.CW);
        }
        double sinceFlash = sessionSeconds - lightningAt;
        int darkAlpha = sinceFlash >= 0 && sinceFlash < 0.5 ? 120 : 240;
        c.save();
        c.clipPath(darkPath, Region.Op.DIFFERENCE);
        paint.setColor((darkAlpha << 24) | 0x030509);
        c.drawRect(-dp(40f), -dp(40f), w + dp(40f), h + dp(40f), paint);
        c.restore();

        // Warm light inside the beam, and a red wash from the flare.
        beamPath.reset();
        buildBeam(beamPath, youX, headY, dp(560f));
        paint.setColor(0x1CFFE8B0);
        c.drawPath(beamPath, paint);
        if (flareLight > 0) {
            Fx.glow(c, flareToX, burstY, dp(300f), ((int) (130 * flareLight) << 24) | 0xFF3A1A);
        }

        // Fog, thicker at night, drifting through the light and the dark alike.
        for (int i = 0; i < 7; i++) {
            float fx = (float) (((i * 330) - sessionMeters * (w / 90f) * 0.5 - sessionSeconds * dp(18f))
                    % (w + dp(600f)));
            if (fx < -dp(300f)) {
                fx += w + dp(600f);
            }
            float fy = groundY - dp(30f) - (i % 3) * dp(38f);
            paint.setColor(i % 2 == 0 ? 0x26A9B6CC : 0x1C8F9CB4);
            c.drawOval(fx - dp(280f), fy - dp(34f), fx + dp(280f), fy + dp(30f), paint);
        }

        // Eyes in the dark.
        for (int i = 0; i < eyeCount; i++) {
            Fx.glow(c, eyeX[i], eyeY[i], dp(12f), 0x88FF2A2A);
            paint.setColor(0xFFFF4A4A);
            c.drawCircle(eyeX[i], eyeY[i], dp(3.2f), paint);
        }
    }

    /** The torch cone: forward along the ground, swinging back over your shoulder when something closes. */
    private void buildBeam(Path p, float x, float headY, float len) {
        double forward = 0.20;
        double back = Math.PI - 0.20;
        double a = forward + (back - forward) * lookBack;
        double spread = 0.26;
        float ox = x + (float) Math.cos(a) * dp(10f);
        p.moveTo(ox, headY);
        p.lineTo(ox + (float) Math.cos(a - spread) * len, headY + (float) Math.sin(a - spread) * len);
        p.lineTo(ox + (float) Math.cos(a + spread) * len, headY + (float) Math.sin(a + spread) * len);
        p.close();
    }

    private void drawSafeHouse(Canvas c, float houseX, float groundY, float dt) {
        paint.setColor(safe() ? ACCENT : 0xFF3B4A5E);
        c.drawRect(houseX - dp(22f), groundY - dp(46f), houseX + dp(22f), groundY, paint);
        // Roof.
        path.reset();
        path.moveTo(houseX - dp(28f), groundY - dp(46f));
        path.lineTo(houseX, groundY - dp(66f));
        path.lineTo(houseX + dp(28f), groundY - dp(46f));
        path.close();
        paint.setColor(0xFF2A3446);
        c.drawPath(path, paint);
        paint.setColor(0xFF0A0E14);
        c.drawRect(houseX - dp(6f), groundY - dp(24f), houseX + dp(6f), groundY, paint);
        label(c, "SAFE HOUSE", houseX, groundY - dp(74f), 8f, safe() ? ACCENT : FAINT, Paint.Align.CENTER);
        // Warm windows and chimney smoke: somewhere worth reaching.
        Fx.glow(c, houseX, groundY - dp(30f), dp(60f), safe() ? 0x6635D0BA : 0x44FFB45A);
        paint.setColor(0xFFFFD27A);
        c.drawRect(houseX - dp(16f), groundY - dp(40f), houseX - dp(9f), groundY - dp(33f), paint);
        c.drawRect(houseX + dp(9f), groundY - dp(40f), houseX + dp(16f), groundY - dp(33f), paint);
        paint.setColor(0xFF3B4A5E);
        c.drawRect(houseX + dp(10f), groundY - dp(64f), houseX + dp(16f), groundY - dp(52f), paint);
        smokeAccum += dt * 4f;
        while (smokeAccum >= 1f) {
            smokeAccum -= 1f;
            smoke.spawn(houseX + dp(13f), groundY - dp(66f), dp(6f), -dp(20f) - (float) Math.random() * dp(10f),
                    1.6f, dp(5f), 0x557A8494, false);
        }
    }

    /** A wrecked car with a survivor on the roof, waving, a lighter held up in the dark. */
    private void drawWreck(Canvas c, float sx, float groundY, double threat) {
        paint.setColor(0xFF4A3A34);
        c.drawRoundRect(sx - dp(46f), groundY - dp(28f), sx + dp(46f), groundY - dp(8f), dp(6f), dp(6f), paint);
        paint.setColor(0xFF3A2E2A);
        c.drawRoundRect(sx - dp(26f), groundY - dp(44f), sx + dp(20f), groundY - dp(26f), dp(6f), dp(6f), paint);
        paint.setColor(0xFF1C2230);
        c.drawRect(sx - dp(21f), groundY - dp(40f), sx - dp(4f), groundY - dp(29f), paint);
        c.drawRect(sx, groundY - dp(40f), sx + dp(15f), groundY - dp(29f), paint);
        paint.setColor(0xFF111111);
        c.drawCircle(sx - dp(28f), groundY - dp(7f), dp(8f), paint);
        c.drawCircle(sx + dp(28f), groundY - dp(7f), dp(8f), paint);
        // Hazard light blinking.
        if (((int) (sessionSeconds * 2)) % 2 == 0) {
            Fx.glow(c, sx + dp(44f), groundY - dp(22f), dp(22f), 0xAAFFB02A);
        }
        float roof = groundY - dp(44f);
        drawFigure(c, sx - dp(4f), roof, survivorShirt, 0f, 0f, K_SURVIVOR, true);
        boolean clear = threat >= RESCUE_CLEAR;
        label(c, NAMES[survivorName] + (clear ? " - COMING!" : " - TOO CLOSE, HIDING?"), sx,
                roof - 50f * FIGURE_SCALE * 0.9f * dp(1f), 10f, clear ? 0xFF8FB4FF : WARN, Paint.Align.CENTER);
        if (bubbleUntil < sessionSeconds && sessionSeconds >= nextBubbleAt - 1.5) {
            say(LINES_HELP, WHO_WAITING);
        }
        if (bubbleWho == WHO_WAITING) {
            spkX = sx;
            spkY = roof - 58f * FIGURE_SCALE * 0.9f * dp(1f);
        }
    }

    /** A torch beam thrown forward along the ground - the night ahead was flat black. */
    private void drawTorch(Canvas c, float x, float groundY) {
        float headY = groundY - 40f * FIGURE_SCALE * dp(1f);
        float flicker = 1f + (float) Math.sin(sessionSeconds * 17) * 0.05f;
        path.reset();
        path.moveTo(x + dp(8f), headY);
        path.lineTo(x + dp(300f) * flicker, groundY - dp(70f));
        path.lineTo(x + dp(300f) * flicker, groundY + dp(26f));
        path.close();
        paint.setColor(0x18FFF3C4);
        c.drawPath(path, paint);
        Fx.glow(c, x + dp(120f), groundY - dp(14f), dp(120f), 0x20FFF3C4);
    }

    /**
     * A chunky figure with legs that swing with distance covered. Walkers reach forward, runners are
     * lean and pitched almost flat, the brute is half as big again. {@code wave}: a survivor waving
     * for help, or the brute roaring with its arms up.
     */
    private void drawFigure(Canvas c, float x, float groundY, int color, float speed, float phaseIn,
                            int kind, boolean wave) {
        float mul = kind == K_BRUTE ? 1.5f : kind == K_SURVIVOR ? 0.9f : 1f;
        float s = dp(FIGURE_SCALE) * mul;
        boolean zombie = kind >= K_WALKER;
        float legSwing = speed > 0.2f ? (float) Math.sin(phaseIn) * 8f * s * (kind == K_RUNNER ? 1.3f : 1f) : 0f;
        // Legs: dark trousers on the living, so the figure reads as a person, not a block.
        paint.setColor(zombie ? color : kind == K_SURVIVOR ? 0xFF3A3F55 : 0xFF2A2F3A);
        c.drawRect(x - 5 * s + legSwing, groundY - 14 * s, x - 1 * s + legSwing, groundY, paint);
        c.drawRect(x + 1 * s - legSwing, groundY - 14 * s, x + 5 * s - legSwing, groundY, paint);
        // Body, leaning into the run.
        paint.setColor(color);
        float half = kind == K_BRUTE ? 8 * s : kind == K_RUNNER ? 4.5f * s : 6 * s;
        float lean;
        if (kind == K_RUNNER) {
            lean = 8 * s;
        } else if (kind == K_BRUTE) {
            lean = wave ? 0 : 3 * s;
        } else if (zombie) {
            lean = 4 * s;
        } else {
            lean = Math.min(1f, speed / 4f) * 5 * s;
        }
        c.drawRect(x - half + lean * 0.5f, groundY - 34 * s, x + half + lean, groundY - 14 * s, paint);
        // Arms.
        if (kind == K_BRUTE && wave) {
            float shake = (float) Math.sin(sessionSeconds * 30) * s;
            c.drawRect(x - half - 4 * s, groundY - 48 * s + shake, x - half, groundY - 30 * s, paint);
            c.drawRect(x + half, groundY - 48 * s - shake, x + half + 4 * s, groundY - 30 * s, paint);
        } else if (kind == K_BRUTE) {
            c.drawRect(x + half + lean - 2 * s, groundY - 31 * s, x + half + lean + 12 * s, groundY - 25 * s, paint);
        } else if (kind == K_RUNNER) {
            float arm = (float) Math.sin(phaseIn) * 7f * s;
            c.drawRect(x + half + lean, groundY - 31 * s + arm, x + half + lean + 13 * s, groundY - 28 * s + arm, paint);
        } else if (zombie) {
            c.drawRect(x + 6 * s + lean, groundY - 30 * s, x + 18 * s + lean, groundY - 26 * s, paint);
        } else if (wave) {
            float sway = (float) Math.sin(sessionSeconds * 9) * 4 * s;
            c.drawRect(x + 5 * s, groundY - 50 * s, x + 8 * s, groundY - 30 * s, paint);
            c.drawCircle(x + 6.5f * s + sway, groundY - 51 * s, 2.5f * s, paint);
            // The lighter they hold up.
            Fx.glow(c, x - 8 * s, groundY - 36 * s, dp(18f), 0xAAFFC45A);
        } else {
            float arm = (float) Math.sin(phaseIn) * 6f * s;
            c.drawRect(x + 6 * s + lean, groundY - 30 * s + arm, x + 12 * s + lean, groundY - 26 * s + arm, paint);
        }
        // Head: round with a face (3.19.5 - on the emulator it was a plain white square).
        float hx = x + lean;
        float hy = groundY - 41 * s;
        int skin;
        if (kind == K_RUNNER) {
            skin = 0xFFB4C85A;
        } else if (kind == K_BRUTE) {
            skin = 0xFF5E8F66;
        } else if (zombie) {
            skin = 0xFF7FB37A;
        } else if (kind == K_SURVIVOR) {
            skin = 0xFFE8B98A;
        } else {
            skin = 0xFFF1C27D;
        }
        paint.setColor(skin);
        c.drawCircle(hx, hy, 6 * s, paint);
        if (zombie) {
            paint.setColor(0xFFFF3B3B);
            c.drawCircle(hx + 2.5f * s, hy - 1 * s, 1.3f * s, paint);
            c.drawCircle(hx - 1.5f * s, hy - 1 * s, 1.1f * s, paint);
            if (eyeCount < eyeX.length) {
                eyeX[eyeCount] = hx + 1.8f * s;
                eyeY[eyeCount] = hy - 1 * s;
                eyeCount++;
            }
            paint.setColor(0xFF2A1A1A);
            c.drawRect(hx - 1 * s, hy + 2.5f * s, hx + 4 * s, hy + 4.5f * s, paint);
            if (kind == K_BRUTE) {
                // Teeth, and a scowl.
                paint.setColor(0xFFEDE6C8);
                c.drawRect(hx, hy + 2.5f * s, hx + 1 * s, hy + 3.5f * s, paint);
                c.drawRect(hx + 2 * s, hy + 2.5f * s, hx + 3 * s, hy + 3.5f * s, paint);
                paint.setColor(0xFF2A1A1A);
                c.drawRect(hx - 3 * s, hy - 3.5f * s, hx + 4 * s, hy - 2.5f * s, paint);
            } else if (kind == K_RUNNER) {
                // Wild hair streaming back.
                paint.setColor(0xFF3A3A2A);
                c.drawRect(hx - 9 * s, hy - 6 * s, hx + 2 * s, hy - 4 * s, paint);
            } else {
                paint.setColor(0xFF5E8A5A);
                c.drawRect(hx - 6 * s, hy - 6 * s, hx - 2 * s, hy - 4.5f * s, paint);
            }
        } else {
            paint.setColor(kind == K_SURVIVOR ? 0xFF6A3A1A : 0xFF3A2A1A);
            c.drawArc(hx - 6 * s, hy - 7 * s, hx + 6 * s, hy + 3 * s, 180, 180, true, paint);
            if (kind == K_YOU) {
                paint.setColor(ACCENT);
                c.drawRect(hx - 6.2f * s, hy - 3.2f * s, hx + 6.2f * s, hy - 1.6f * s, paint);
                c.drawRect(hx - 9 * s, hy - 3.2f * s, hx - 6 * s, hy - 2.2f * s, paint);
            }
            paint.setColor(0xFF1A1A1A);
            c.drawCircle(hx + 3 * s, hy + 0.5f * s, 1.1f * s, paint);
        }
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
