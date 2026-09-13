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
 */
class PaceBoatGame extends GameView {

    enum State { READY, RACING, FINISHED }

    private final RiverRenderer river;
    protected final PersonalBests bests;

    private float targetPaceSec = 135f;   // 2:15 /500m
    protected int raceMeters = 1000;
    protected State state = State.READY;

    private double raceStartSeconds;
    private double raceStartMeters;
    protected double finishTime;
    protected boolean newBest;
    private float paceBoatX;

    PaceBoatGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
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
        river.advance(state == State.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);

        double gap = raceDistance() - opponentDistance();
        float yourX = w * 0.38f;
        float boatLen = dp(96f);
        float laneYou = waterTop + (waterBottom - waterTop) * 0.62f;
        float lanePace = waterTop + (waterBottom - waterTop) * 0.34f;

        // Pace boat sits where the gap puts it; eased so a sudden distance step does not teleport.
        float targetX = yourX + (float) gap * ppm;
        float clampedX = Math.max(dp(40f), Math.min(w - dp(40f), targetX));
        paceBoatX += (clampedX - paceBoatX) * Math.min(1f, 6f * dt);
        if (paceBoatX == 0f) {
            paceBoatX = clampedX;
        }
        river.drawBoat(c, paceBoatX, lanePace, boatLen, BLUE, opponentSpeed(), true);
        if (targetX != clampedX) {
            // Off the edge: an arrow says which way and how far.
            String far = String.format(java.util.Locale.US, "%s %.0f m",
                    targetX > clampedX ? "→" : "←", Math.abs(gap));
            bold(c, far, targetX > clampedX ? w - dp(14f) : dp(14f), lanePace - dp(22f), 13f,
                    BLUE, targetX > clampedX ? Paint.Align.RIGHT : Paint.Align.LEFT);
        }
        river.bowSpray(yourX + boatLen / 2f, laneYou, speed, dt);
        river.drawBoat(c, yourX, laneYou, boatLen, ACCENT, speed, false);
        river.drawSpray(c);
        Fx.glow(c, yourX, laneYou, dp(50f), 0x2A35D0BA);

        label(c, "YOU", yourX, laneYou + dp(30f), 9f, FAINT, Paint.Align.CENTER);
        label(c, opponentLabel(), paceBoatX, lanePace - dp(20f), 9f, FAINT, Paint.Align.CENTER);

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
