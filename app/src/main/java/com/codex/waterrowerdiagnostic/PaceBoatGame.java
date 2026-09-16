package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Pace Boat: a target boat at a pace you choose. Stay level, fall behind, or pull ahead.
 *
 * <p>The race clock does not start until the first stroke, so sitting on the seat costs
 * nothing. The gap is the whole game: it is drawn large and every stroke moves it.
 *
 * <p>3.19.5 (the emulator screenshot was two boats on dark water): an evening sky, a crowd on the
 * bank that cheers while you lead, a buoy line between the lanes, 250 m boards, a finish line that
 * sails in, reeds on the near shore, bigger boats, a splash on every stroke, callouts when the lead
 * changes, and confetti when you win.
 */
class PaceBoatGame extends GameView {

    enum State { READY, RACING, FINISHED }

    private final RiverRenderer river;
    protected final PersonalBests bests;

    // 2:06 /500m: just inside the measured median of 2:08, so holding it is a real contest.
    private float targetPaceSec = 126f;
    protected int raceMeters = 1000;
    protected State state = State.READY;

    private double raceStartSeconds;
    private double raceStartMeters;
    protected double finishTime;
    protected boolean newBest;
    private float paceBoatX;
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private final Paint scenePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;
    private float cheer;
    private int lastLeader;
    private String callout = "";
    private int calloutColor = ACCENT;
    private double calloutUntil;
    private boolean confettiDone;
    private float lastYouX;
    private float lastYouY;

    PaceBoatGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
        boardText.setTextSize(dp(11f));
        boardText.setFakeBoldText(true);
    }

    @Override
    protected void onStroke(int watts) {
        if (state != State.RACING || lastYouX == 0f) {
            return;
        }
        // The catch: a splash off each blade.
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k < 9; k++) {
                fx.spawn(lastYouX - dp(10f) + (float) Math.random() * dp(20f), lastYouY + side * dp(22f),
                        (float) (Math.random() - 0.7) * dp(60f), -dp(40f) - (float) Math.random() * dp(70f),
                        0.7f, dp(3.2f), 0xEEDDF2FF, true);
            }
        }
    }

    void setTargetPace(float secondsPer500) {
        this.targetPaceSec = secondsPer500;
        if (state != State.RACING) {
            state = State.READY;
        }
    }

    void setRaceMeters(int meters) {
        this.raceMeters = meters;
        if (state != State.RACING) {
            state = State.READY;
        }
    }

    float targetPace() {
        return targetPaceSec;
    }

    int raceMeters() {
        return raceMeters;
    }

    @Override
    protected void onStart() {
        state = State.READY;
        newBest = false;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (state == State.READY && driving && boat.value() > 0.3f) {
            state = State.RACING;
            raceStartSeconds = sessionSeconds;
            raceStartMeters = sessionMeters;
        }
    }

    protected double raceTime() {
        return state == State.READY ? 0 : sessionSeconds - raceStartSeconds;
    }

    protected double raceDistance() {
        return state == State.READY ? 0 : sessionMeters - raceStartMeters;
    }

    /** Where the opponent is at the current race time. Pace Boat: a constant pace. */
    protected double opponentDistance() {
        return (500.0 / targetPaceSec) * raceTime();
    }

    /** Opponent speed for drawing its wake. */
    protected float opponentSpeed() {
        return state == State.RACING ? 500f / targetPaceSec : 0f;
    }

    protected String opponentLabel() {
        return "PACE BOAT  " + PersonalBests.formatPace(targetPaceSec);
    }

    protected String winText() {
        return "YOU BEAT THE PACE BOAT";
    }

    protected String loseText() {
        return "THE PACE BOAT WON";
    }

    /** Called every frame while racing, for subclasses that record the run. */
    protected void onRaceTick(double time, double meters) {
    }

    /** Called once when the finish line is crossed. */
    protected void onRaceFinished(double time, boolean won) {
    }

    /** Opponent's finish time, for the margin readout. */
    protected double opponentFinishTime() {
        return raceMeters / (500.0 / targetPaceSec);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && state == State.FINISHED) {
            start();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }

        if (state == State.RACING) {
            onRaceTick(raceTime(), raceDistance());
            if (raceDistance() >= raceMeters) {
                state = State.FINISHED;
                finishTime = raceTime();
                newBest = bests.recordLowest("time." + raceMeters, (float) finishTime);
                onRaceFinished(finishTime, raceDistance() - opponentDistance() >= 0);
            }
        }

        float hudH = h * 0.30f;
        float waterTop = hudH;
        float waterBottom = h * 0.78f;
        float ppm = w / 60f;   // 60 metres across the screen

        float speed = boat.value();
        float bankTop = waterTop - dp(46f);
        if (skyShader == null || skyHeight != bankTop) {
            skyHeight = bankTop;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, bankTop, 0xFF0B1322, 0xFF35577E,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        scenePaint.setColor(0xFFFFFFFF);
        scenePaint.setShader(skyShader);
        c.drawRect(0, 0, w, bankTop, scenePaint);
        scenePaint.setShader(null);
        Fx.glow(c, w * 0.12f, bankTop - dp(14f), dp(110f), 0x44FFC98A);
        river.advance(state == State.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        double you = raceDistance();
        scenery.drawBank(c, w, bankTop, waterTop, you, ppm, sessionSeconds, cheer);
        scenery.drawBuoys(c, w, waterTop + (waterBottom - waterTop) * 0.48f, you, ppm, sessionSeconds, 10f);
        scenery.drawWaterLife(c, w, waterTop, waterBottom, you, ppm, sessionSeconds);
        // Near shore: grass and reeds, scrolling fastest.
        scenePaint.setColor(0xFF2F5E33);
        c.drawRect(0, waterBottom, w, waterBottom + dp(26f), scenePaint);
        float reedGap = dp(22f);
        double reedScroll = you * ppm * 1.2;
        float reedOff = (float) (reedScroll % reedGap);
        scenePaint.setStrokeWidth(dp(3f));
        for (float rx = -reedOff - reedGap; rx < w + reedGap; rx += reedGap) {
            int k = (int) Math.floor((rx + reedScroll) / reedGap + 0.5);
            float tall = dp(14f) + (Math.abs(k * 7) % 4) * dp(5f);
            float sway = (float) Math.sin(sessionSeconds * 2 + k) * dp(3f);
            scenePaint.setColor((k & 1) == 0 ? 0xFF4E8F4F : 0xFF6BAA5C);
            c.drawLine(rx, waterBottom + dp(8f), rx + sway, waterBottom + dp(8f) - tall, scenePaint);
        }
        fx.step(dt, dp(260f));

        double gap = raceDistance() - opponentDistance();
        float yourX = w * 0.38f;
        float boatLen = dp(140f);
        float laneYou = waterTop + (waterBottom - waterTop) * 0.62f;
        float lanePace = waterTop + (waterBottom - waterTop) * 0.34f;

        // Pace boat sits where the gap puts it; eased so a sudden distance step does not teleport.
        for (int mark = 250; mark < raceMeters; mark += 250) {
            float mx = yourX + (float) (mark - you) * ppm;
            if (mx > -dp(40f) && mx < w + dp(40f)) {
                scenery.drawBoard(c, mx, waterTop, (raceMeters - mark) + " m", boardText);
            }
        }
        float finishX = yourX + boatLen / 2f + (float) (raceMeters - you) * ppm;
        if (finishX < w + dp(40f)) {
            scenery.drawFinishLine(c, finishX, waterTop, waterBottom, sessionSeconds);
        }
        int leader = state != State.RACING ? 0 : gap >= 0 ? 1 : -1;
        if (state == State.RACING && lastLeader != 0 && leader != lastLeader && Math.abs(gap) > 0.5) {
            callout = leader > 0 ? "YOU TAKE THE LEAD!" : "YOU'VE BEEN PASSED";
            calloutColor = leader > 0 ? ACCENT : BAD;
            calloutUntil = sessionSeconds + 1.8;
            if (leader > 0) {
                fx.burst(yourX, laneYou, 30, dp(170f), 0.9f, dp(3f), 0xFFF5C518, true);
            }
        }
        if (state == State.RACING && Math.abs(gap) > 0.5) {
            lastLeader = leader;
        }
        float cheerTarget = state == State.RACING ? (gap >= 0 ? 1f : 0.25f) : state == State.FINISHED && gap >= 0 ? 1f : 0.1f;
        cheer += (cheerTarget - cheer) * Math.min(1f, 2f * dt);
        lastYouX = yourX;
        lastYouY = laneYou;
        float targetX = yourX + (float) gap * ppm;
        float clampedX = Math.max(dp(40f), Math.min(w - dp(40f), targetX));
        paceBoatX += (clampedX - paceBoatX) * Math.min(1f, 6f * dt);
        if (paceBoatX == 0f) {
            paceBoatX = clampedX;
        }
        // The pace boat rows to its own rhythm, not yours.
        river.setStrokePhase((float) (0.5 + 0.5 * Math.sin(sessionSeconds * 2.5 + 1.3)));
        river.drawBoat(c, paceBoatX, lanePace, boatLen, BLUE, opponentSpeed(), true);
        if (targetX != clampedX) {
            // Off the edge: an arrow says which way and how far.
            String far = String.format(java.util.Locale.US, "%s %.0f m",
                    targetX > clampedX ? "→" : "←", Math.abs(gap));
            bold(c, far, targetX > clampedX ? w - dp(14f) : dp(14f), lanePace - dp(22f), 13f,
                    BLUE, targetX > clampedX ? Paint.Align.RIGHT : Paint.Align.LEFT);
        }
        river.bowSpray(yourX + boatLen / 2f, laneYou, speed, dt);
        // Your own oars follow your own stroke, set here rather than inherited.
        river.setStrokePhase(strokePhase());
        river.drawBoat(c, yourX, laneYou, boatLen, ACCENT, speed, false);
        river.drawSpray(c);
        Fx.glow(c, yourX, laneYou, dp(50f), 0x2A35D0BA);

        if (gap >= 0 && state == State.RACING) {
            Fx.glow(c, yourX, laneYou, dp(90f), 0x40F5C518);
        }
        fx.draw(c);
        bold(c, "YOU", yourX, laneYou + dp(36f), 11f, ACCENT, Paint.Align.CENTER);
        label(c, opponentLabel(), Math.max(dp(90f), Math.min(w - dp(90f), paceBoatX)), lanePace - dp(24f), 11f, DIM,
                Paint.Align.CENTER);
        if (sessionSeconds < calloutUntil) {
            bold(c, callout, w / 2f, waterTop + (waterBottom - waterTop) * 0.5f, 30f, calloutColor, Paint.Align.CENTER);
        }

        // HUD: the gap, then the two paces.
        String gapText;
        int gapColor;
        if (state == State.READY) {
            gapText = "TAKE A STROKE";
            gapColor = DIM;
        } else {
            gapText = String.format(java.util.Locale.US, "%s%.0f m", gap >= 0 ? "+" : "−",
                    Math.abs(gap));
            gapColor = gap >= 0 ? ACCENT : BAD;
        }
        bold(c, gapText, w / 2f, hudH * 0.55f, state == State.READY ? 22f : 44f, gapColor,
                Paint.Align.CENTER);
        label(c, state == State.READY ? "the clock starts when you do"
                : (gap >= 0 ? "AHEAD" : "BEHIND"),
                w / 2f, hudH * 0.55f + dp(20f), 10f, FAINT, Paint.Align.CENTER);

        bold(c, pace(speed), dp(16f), hudH * 0.5f, 22f, TEXT, Paint.Align.LEFT);
        label(c, "YOUR PACE /500", dp(16f), hudH * 0.5f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, PersonalBests.formatPace(targetPaceSec), w - dp(16f), hudH * 0.5f, 22f, BLUE,
                Paint.Align.RIGHT);
        label(c, "TARGET /500", w - dp(16f), hudH * 0.5f + dp(16f), 9f, FAINT, Paint.Align.RIGHT);

        // Footer stats.
        float fy = h - dp(14f);
        float col = w / 5f;
        stat(c, col * 0.5f, fy, String.format(java.util.Locale.US, "%.0f", raceDistance()),
                "OF " + raceMeters + " M");
        stat(c, col * 1.5f, fy, clock(raceTime()), "TIME");
        stat(c, col * 2.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 3.5f, fy, status == null ? "0" : String.valueOf(status.watts), "WATTS");
        float pct = Math.min(1f, (float) (raceDistance() / raceMeters));
        stat(c, col * 4.5f, fy, String.format(java.util.Locale.US, "%.0f%%", pct * 100), "DONE");

        // Progress bar along the top of the water.
        accentPaint.setStrokeWidth(dp(3f));
        c.drawLine(0, waterTop, w * pct, waterTop, accentPaint);

        if (state == State.FINISHED) {
            drawFinish(c, w, h, gap);
            if (gap >= 0) {
                if (Math.random() < 0.8) {
                    int[] colors = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};
                    fx.spawn((float) Math.random() * w, -dp(10f), (float) (Math.random() - 0.5) * dp(80f),
                            dp(40f), 3f, dp(3.5f), colors[(int) (Math.random() * colors.length)], true);
                }
                fx.draw(c);
            }
        } else {
            lastLeader = state == State.READY ? 0 : lastLeader;
        }
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 18f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    private void drawFinish(Canvas c, float w, float h, double gap) {
        accentPaint.setColor(0xCC0A0E14);
        c.drawRect(0, 0, w, h, accentPaint);
        accentPaint.setColor(ACCENT);
        double margin = finishTime - opponentFinishTime();
        bold(c, gap >= 0 ? winText() : loseText(), w / 2f, h * 0.36f, 24f,
                gap >= 0 ? ACCENT : BAD, Paint.Align.CENTER);
        bold(c, clock(finishTime), w / 2f, h * 0.50f, 46f, TEXT, Paint.Align.CENTER);
        label(c, String.format(java.util.Locale.US, "%s by %.1f s   ·   %s /500 average",
                margin <= 0 ? "ahead" : "behind", Math.abs(margin),
                PersonalBests.formatPace((float) (finishTime / (raceMeters / 500.0)))),
                w / 2f, h * 0.50f + dp(24f), 12f, DIM, Paint.Align.CENTER);
        if (newBest) {
            bold(c, "NEW PERSONAL BEST", w / 2f, h * 0.64f, 16f, WARN, Paint.Align.CENTER);
        } else if (bests.has("time." + raceMeters)) {
            label(c, "best " + clock(bests.get("time." + raceMeters, 0)), w / 2f, h * 0.64f, 12f,
                    FAINT, Paint.Align.CENTER);
        }
        label(c, "tap to row again", w / 2f, h * 0.80f, 11f, FAINT, Paint.Align.CENTER);
    }
}
