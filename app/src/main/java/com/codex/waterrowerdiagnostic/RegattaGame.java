package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * REGATTA: a season of 500 m races, climbing from club rowing to the Olympics.
 *
 * <p>Each race is you against three crews whose speeds are set from your own typical speed and the
 * division you are in, each with a race plan - a fast starter, an even pacer, a sprinter. Win and
 * you move up a division; finish last and you drop one. One ranked race a day, so there is a reason
 * to come back tomorrow; after that, races are practice.
 */
final class RegattaGame extends GameView {

    static final String[] DIVISIONS = {"CLUB", "COUNTY", "REGIONAL", "NATIONAL", "INTERNATIONAL", "OLYMPIC"};
    private static final float[] FACTOR = {0.90f, 0.95f, 1.00f, 1.04f, 1.08f, 1.12f};
    private static final int RACE_METERS = 500;
    private static final String[] PLANS = {"FAST START", "EVEN", "SPRINTER"};
    private static final float[] SHAPE = {0.25f, 0f, -0.25f};
    private static final int[] COLORS = {0xFFF0B132, 0xFF6F8CFF, 0xFFB48CFF};

    private enum Phase { READY, RACING, DONE }

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double[] finishTimes = new double[3];
    private final float[] rivalX = new float[3];

    private Phase phase = Phase.READY;
    private int division;
    private boolean ranked;
    private double raceStart;
    private double startMeters;
    private double finishTime;
    private int placing;
    private String outcome = "";

    RegattaGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
    }

    static long today() {
        long now = System.currentTimeMillis();
        return (now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        division = Math.max(0, Math.min(DIVISIONS.length - 1, Math.round(bests.get("regatta.division", 0f))));
        ranked = Math.round(bests.get("regatta.day", -1f)) != today();
        // Crew speeds for today: the same field all day, different tomorrow.
        java.util.Random r = new java.util.Random(today() * 31 + division);
        for (int i = 0; i < 3; i++) {
            double speed = profile.typicalSpeed() * FACTOR[division] * (0.97 + r.nextDouble() * 0.06);
            finishTimes[i] = RACE_METERS / speed;
        }
        outcome = "";
    }

    /** Distance a crew has covered: speed = base x (1 + shape x (0.5 - t/T)), integrating to exactly D. */
    private double crewDistance(int i, double t) {
        double T = finishTimes[i];
        double base = RACE_METERS / T;
        double tt = Math.min(t, T);
        return base * (tt + SHAPE[i] * (0.5 * tt - tt * tt / (2 * T)));
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.RACING;
            raceStart = sessionSeconds;
            startMeters = sessionMeters;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.DONE) {
            start();
            return true;
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
        double t = phase == Phase.READY ? 0 : sessionSeconds - raceStart;
        double you = phase == Phase.READY ? 0 : sessionMeters - startMeters;
        if (phase == Phase.RACING && you >= RACE_METERS) {
            phase = Phase.DONE;
            finishTime = t;
            placing = 1;
            for (double ft : finishTimes) {
                if (ft < finishTime) {
                    placing++;
                }
            }
            bests.recordLowest("time." + RACE_METERS, (float) finishTime);
            if (ranked) {
                bests.putFloat("regatta.day", today());
                if (placing == 1 && division < DIVISIONS.length - 1) {
                    division++;
                    outcome = "PROMOTED TO " + DIVISIONS[division];
                } else if (placing == 4 && division > 0) {
                    division--;
                    outcome = "RELEGATED TO " + DIVISIONS[division];
                } else {
                    outcome = "STAYING IN " + DIVISIONS[division];
                }
                bests.putFloat("regatta.division", division);
                bests.recordHighest("regatta.best", division + 1);
            } else {
                outcome = "PRACTICE - TODAY'S RANKED RACE IS DONE";
            }
        }

        float waterTop = h * 0.24f;
        float waterBottom = h * 0.86f;
        float ppm = w / 70f;
        float speed = boat.value();
        river.advance(phase == Phase.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        float laneH = (waterBottom - waterTop) / 4f;
        float yourX = w * 0.42f;
        int ahead = 0;
        for (int i = 0; i < 3; i++) {
            double d = crewDistance(i, t);
            if (d > you) {
                ahead++;
            }
            float target = Math.max(dp(40f), Math.min(w - dp(40f), yourX + (float) (d - you) * ppm));
            rivalX[i] += (target - rivalX[i]) * Math.min(1f, 6f * dt);
            if (rivalX[i] == 0f) {
                rivalX[i] = target;
            }
            float ly = waterTop + laneH * (i + 0.5f);
            river.drawBoat(c, rivalX[i], ly, dp(120f), COLORS[i], phase == Phase.RACING ? (float) (RACE_METERS / finishTimes[i]) : 0f, true);
            label(c, PLANS[i], rivalX[i], ly - dp(18f), 8.5f, COLORS[i], Paint.Align.CENTER);
        }
        float yourY = waterTop + laneH * 3.5f;
        river.bowSpray(yourX + dp(42f), yourY, speed, dt);
        river.drawBoat(c, yourX, yourY, dp(126f), ACCENT, speed, false);
        river.drawSpray(c);

        bold(c, DIVISIONS[division] + " DIVISION", dp(18f), dp(34f), 20f, ACCENT, Paint.Align.LEFT);
        label(c, (ranked ? "TODAY'S RANKED RACE" : "PRACTICE") + "  ·  " + RACE_METERS + " m  ·  win to move up, last to drop",
                dp(18f), dp(52f), 10f, FAINT, Paint.Align.LEFT);
        String big;
        int col;
        if (phase == Phase.READY) {
            big = "TAKE A STROKE";
            col = DIM;
        } else if (phase == Phase.DONE) {
            big = placing == 1 ? "1ST" : placing == 2 ? "2ND" : placing == 3 ? "3RD" : "4TH";
            col = placing == 1 ? ACCENT : placing == 4 ? BAD : WARN;
        } else {
            big = (ahead + 1) == 1 ? "1ST" : (ahead + 1) == 2 ? "2ND" : (ahead + 1) == 3 ? "3RD" : "4TH";
            col = ahead == 0 ? ACCENT : ahead == 3 ? BAD : WARN;
        }
        bold(c, big, w * 0.5f, dp(44f), 38f, col, Paint.Align.CENTER);
        String sub = phase == Phase.DONE ? clock(finishTime) + "  ·  " + outcome + "  ·  tap to race again"
                : phase == Phase.RACING ? clock(t) + "  ·  " + Math.round(you) + " of " + RACE_METERS + " m" : "";
        bold(c, sub, w * 0.5f, h - dp(14f), 12f, phase == Phase.DONE ? col : TEXT, Paint.Align.CENTER);
        float pct = (float) Math.min(1, you / RACE_METERS);
        paint.setColor(ACCENT);
        c.drawRect(0, waterTop - dp(3f), w * pct, waterTop, paint);
        // Division ladder on the right.
        for (int i = 0; i < DIVISIONS.length; i++) {
            float y = dp(24f) + (DIVISIONS.length - 1 - i) * dp(15f);
            label(c, DIVISIONS[i], w - dp(16f), y, 8.5f, i == division ? ACCENT : i < division ? DIM : FAINT, Paint.Align.RIGHT);
        }
    }
}
