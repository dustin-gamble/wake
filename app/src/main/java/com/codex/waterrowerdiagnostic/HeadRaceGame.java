package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Head Race: you against three boats with different race plans, all calibrated to your own best
 * time over the distance. The Flyer goes out hard and fades, the Metronome holds even splits,
 * the Closer negative-splits. Teaches race craft: where you lose the race is as telling as whether.
 */
final class HeadRaceGame extends GameView {

    private enum Phase { READY, RACING, DONE }

    /** Speed = base x (1 + a x (0.5 - t/T)); a > 0 fades, a < 0 closes. Integrates to exactly D. */
    private static final class Rival {
        final String name;
        final int color;
        final float shape;
        final float finishFactor;
        double finishTime;

        Rival(String name, int color, float shape, float finishFactor) {
            this.name = name;
            this.color = color;
            this.shape = shape;
            this.finishFactor = finishFactor;
        }

        double distanceAt(double t, int meters) {
            double T = finishTime;
            double base = meters / T;
            double tt = Math.min(t, T);
            return base * (tt + shape * (0.5 * tt - tt * tt / (2 * T)));
        }

        float speedAt(double t) {
            double T = finishTime;
            if (t >= T) {
                return 0f;
            }
            return (float) ((finishTime > 0 ? 1 : 0) * (1 + shape * (0.5 - t / T)));
        }
    }

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Rival[] rivals = {
            new Rival("FLYER", WARN, 0.30f, 0.99f),
            new Rival("METRONOME", BLUE, 0f, 1.00f),
            new Rival("CLOSER", 0xFFB48CFF, -0.30f, 1.01f),
    };
    private final float[] rivalX = new float[3];

    private int raceMeters = 2000;
    private Phase phase = Phase.READY;
    private double raceStartSeconds;
    private double raceStartMeters;
    private double finishTime;
    private int placing;

    HeadRaceGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
    }

    void setRaceMeters(int m) {
        raceMeters = m;
        phase = Phase.READY;
    }

    int raceMeters() {
        return raceMeters;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        placing = 0;
        // The field is set from your best; without one, a 2:15 pace boat's time.
        float reference = bests.has("time." + raceMeters)
                ? bests.get("time." + raceMeters, 0f)
                : raceMeters / (500f / 135f);
        for (Rival r : rivals) {
            r.finishTime = reference * r.finishFactor;
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.RACING;
            raceStartSeconds = sessionSeconds;
            raceStartMeters = sessionMeters;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.DONE) {
            start();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private double raceTime() {
        return phase == Phase.READY ? 0 : sessionSeconds - raceStartSeconds;
    }

    private double raceDistance() {
        return phase == Phase.READY ? 0 : sessionMeters - raceStartMeters;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        double t = raceTime();
        double you = raceDistance();
        if (phase == Phase.RACING && you >= raceMeters) {
            phase = Phase.DONE;
            finishTime = t;
            placing = 1;
            for (Rival r : rivals) {
                if (r.finishTime < finishTime) {
                    placing++;
                }
            }
            bests.recordLowest("time." + raceMeters, (float) finishTime);
        }

        float waterTop = h * 0.26f;
        float waterBottom = h * 0.80f;
        float ppm = w / 80f;
        float speed = boat.value();
        river.advance(phase == Phase.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);

        float laneH = (waterBottom - waterTop) / 4f;
        float yourX = w * 0.40f;
        // Live placing: count rivals ahead of you.
        int ahead = 0;
        for (int i = 0; i < 3; i++) {
            Rival r = rivals[i];
            double d = r.distanceAt(t, raceMeters);
            if (d > you) {
                ahead++;
            }
            float target = Math.max(dp(36f), Math.min(w - dp(36f), yourX + (float) (d - you) * ppm));
            rivalX[i] += (target - rivalX[i]) * Math.min(1f, 6f * dt);
            if (rivalX[i] == 0f) {
                rivalX[i] = target;
            }
            float ly = waterTop + laneH * (i + 0.5f);
            river.drawBoat(c, rivalX[i], ly, dp(120f), r.color,
                    phase == Phase.RACING ? r.speedAt(t) * (raceMeters / (float) r.finishTime) : 0f, true);
            label(c, r.name + String.format(java.util.Locale.US, "  %+.0f m", d - you), rivalX[i],
                    ly - dp(18f), 8.5f, d > you ? r.color : FAINT, Paint.Align.CENTER);
        }
        float yourY = waterTop + laneH * 3.5f;
        river.bowSpray(yourX + dp(42f), yourY, speed, dt);
        river.drawBoat(c, yourX, yourY, dp(124f), ACCENT, speed, false);
        river.drawSpray(c);
        label(c, "YOU", yourX, yourY + dp(28f), 9f, FAINT, Paint.Align.CENTER);

        String big;
        int col;
        if (phase == Phase.READY) {
            big = raceMeters + " m";
            col = DIM;
        } else if (phase == Phase.DONE) {
            big = ordinal(placing);
            col = placing == 1 ? ACCENT : placing == 4 ? BAD : WARN;
        } else {
            big = ordinal(ahead + 1);
            col = ahead == 0 ? ACCENT : ahead == 3 ? BAD : WARN;
        }
        bold(c, big, w / 2f, h * 0.15f, 44f, col, Paint.Align.CENTER);
        label(c, phase == Phase.READY ? "field set to your best - take a stroke"
                : phase == Phase.DONE ? clock(finishTime) + "  ·  tap to race again"
                : clock(t) + "  ·  " + Math.round(you) + " of " + raceMeters + " m",
                w / 2f, h * 0.15f + dp(20f), 10f, FAINT, Paint.Align.CENTER);
        bold(c, pace(speed), dp(16f), h * 0.15f, 22f, TEXT, Paint.Align.LEFT);
        label(c, "PACE /500", dp(16f), h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, status == null ? "0" : status.strokeRate + " spm", w - dp(16f), h * 0.15f, 22f, TEXT,
                Paint.Align.RIGHT);
        label(c, status == null ? "" : status.watts + " W", w - dp(16f), h * 0.15f + dp(16f), 9f,
                FAINT, Paint.Align.RIGHT);

        float pct = Math.min(1f, (float) (you / raceMeters));
        accentPaint.setColor(ACCENT);
        accentPaint.setStrokeWidth(dp(3f));
        c.drawLine(0, waterTop, w * pct, waterTop, accentPaint);

        float fy = h - dp(12f);
        label(c, "FLYER fades  ·  METRONOME even  ·  CLOSER comes home fast", w / 2f, fy, 9f,
                FAINT, Paint.Align.CENTER);
    }

    private static String ordinal(int n) {
        return n == 1 ? "1ST" : n == 2 ? "2ND" : n == 3 ? "3RD" : n + "TH";
    }
}
