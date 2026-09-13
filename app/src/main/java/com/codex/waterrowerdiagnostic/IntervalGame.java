package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Interval Coach: structured work and rest with a power target.
 *
 * <p>Full-screen state, a big countdown, and a band that is green while you are inside the target
 * power range. Structure beats willpower: most people row longer with a plan.
 */
final class IntervalGame extends GameView {

    /** A workout: repeats of work/rest, with a power target for the work phase. */
    static final class Plan {
        final String name;
        final int reps;
        final int workSec;
        final int restSec;

        Plan(String name, int reps, int workSec, int restSec) {
            this.name = name;
            this.reps = reps;
            this.workSec = workSec;
            this.restSec = restSec;
        }

        String label() {
            return name + "  " + reps + " x " + workSec + "s / " + restSec + "s";
        }
    }

    static final Plan[] PLANS = {
            new Plan("SPRINTS", 8, 60, 60),
            new Plan("THRESHOLD", 4, 240, 120),
            new Plan("PYRAMID", 5, 120, 60),
            new Plan("TABATA", 8, 20, 10),
    };

    private enum Phase { READY, WORK, REST, DONE }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private Plan plan = PLANS[0];
    private int targetWatts = 120;
    private Phase phase = Phase.READY;
    private int rep;
    private double phaseStart;
    private double inBandSeconds;
    private double workSeconds;
    private double wattSum;
    private int wattSamples;

    IntervalGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    void setPlan(Plan p) {
        this.plan = p;
        phase = Phase.READY;
    }

    Plan plan() {
        return plan;
    }

    void setTargetWatts(int watts) {
        this.targetWatts = watts;
    }

    int targetWatts() {
        return targetWatts;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        rep = 0;
        inBandSeconds = 0;
        workSeconds = 0;
        wattSum = 0;
        wattSamples = 0;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.WORK;
            rep = 1;
            phaseStart = sessionSeconds;
        }
        if (phase == Phase.WORK && s.watts > 0) {
            wattSum += s.watts;
            wattSamples++;
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

    private double phaseElapsed() {
        return sessionSeconds - phaseStart;
    }

    private int phaseLength() {
        return phase == Phase.WORK ? plan.workSec : plan.restSec;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        boolean inBand = watts >= targetWatts * 0.9f && watts <= targetWatts * 1.15f;

        if (phase == Phase.WORK || phase == Phase.REST) {
            if (phase == Phase.WORK) {
                workSeconds += dt;
                if (inBand) {
                    inBandSeconds += dt;
                }
            }
            if (phaseElapsed() >= phaseLength()) {
                if (phase == Phase.WORK) {
                    if (rep >= plan.reps) {
                        phase = Phase.DONE;
                        float pct = workSeconds > 0 ? (float) (100 * inBandSeconds / workSeconds) : 0f;
                        bests.recordHighest("intervals." + plan.name.toLowerCase(java.util.Locale.US), pct);
                    } else {
                        phase = Phase.REST;
                        phaseStart = sessionSeconds;
                    }
                } else {
                    phase = Phase.WORK;
                    rep++;
                    phaseStart = sessionSeconds;
                }
            }
        }

        // Background tint by phase.
        int tint = phase == Phase.WORK ? (inBand ? 0x2235D0BA : 0x22F0655D)
                : phase == Phase.REST ? 0x226F8CFF : 0x00000000;
        paint.setColor(tint);
        c.drawRect(0, 0, w, h, paint);

        String big;
        String caption;
        int color;
        switch (phase) {
            case READY:
                big = plan.reps + " x " + plan.workSec + "s";
                caption = "TAKE A STROKE TO START";
                color = DIM;
                break;
            case WORK:
                big = clock(Math.max(0, phaseLength() - phaseElapsed()));
                caption = "WORK  ·  REP " + rep + " OF " + plan.reps;
                color = inBand ? ACCENT : BAD;
                break;
            case REST:
                big = clock(Math.max(0, phaseLength() - phaseElapsed()));
                caption = "REST  ·  NEXT IS REP " + (rep + 1);
                color = BLUE;
                break;
            default:
                big = "DONE";
                caption = "TAP TO GO AGAIN";
                color = ACCENT;
        }
        bold(c, big, w / 2f, h * 0.36f, phase == Phase.READY ? 36f : 72f, color, Paint.Align.CENTER);
        label(c, caption, w / 2f, h * 0.36f + dp(24f), 11f, FAINT, Paint.Align.CENTER);

        // Power band: target range highlighted, current watts as a marker.
        float bandTop = h * 0.50f;
        float bandBottom = h * 0.62f;
        float scaleMax = Math.max(targetWatts * 1.6f, 200f);
        rect.set(dp(20f), bandTop, w - dp(20f), bandBottom);
        paint.setColor(0xFF18202C);
        c.drawRoundRect(rect, dp(8f), dp(8f), paint);
        float lo = dp(20f) + (w - dp(40f)) * (targetWatts * 0.9f / scaleMax);
        float hi = dp(20f) + (w - dp(40f)) * Math.min(1f, targetWatts * 1.15f / scaleMax);
        rect.set(lo, bandTop, hi, bandBottom);
        paint.setColor(phase == Phase.WORK ? 0x6635D0BA : 0x3335D0BA);
        c.drawRect(rect, paint);
        float wx = dp(20f) + (w - dp(40f)) * Math.min(1f, watts / scaleMax);
        paint.setColor(inBand ? ACCENT : (watts > 0 ? BAD : DIM));
        c.drawRoundRect(wx - dp(4f), bandTop - dp(6f), wx + dp(4f), bandBottom + dp(6f),
                dp(3f), dp(3f), paint);
        bold(c, watts + " W", wx, bandTop - dp(12f), 14f, TEXT, Paint.Align.CENTER);
        label(c, "TARGET " + targetWatts + " W", lo + (hi - lo) / 2f, bandBottom + dp(16f), 9f,
                FAINT, Paint.Align.CENTER);

        // Rep dots.
        float dotY = h * 0.72f;
        float dotGap = Math.min(dp(28f), (w - dp(40f)) / Math.max(1, plan.reps));
        float dotX0 = w / 2f - dotGap * (plan.reps - 1) / 2f;
        for (int i = 1; i <= plan.reps; i++) {
            boolean done = i < rep || (i == rep && phase == Phase.REST) || phase == Phase.DONE;
            boolean current = i == rep && phase == Phase.WORK;
            paint.setColor(done ? ACCENT : current ? TEXT : 0xFF2A3648);
            c.drawCircle(dotX0 + (i - 1) * dotGap, dotY, current ? dp(7f) : dp(5f), paint);
        }

        // Footer.
        float fy = h - dp(12f);
        float col = w / 4f;
        float pct = workSeconds > 0 ? (float) (100 * inBandSeconds / workSeconds) : 0f;
        stat(c, col * 0.5f, fy, String.format(java.util.Locale.US, "%.0f%%", pct), "IN BAND");
        stat(c, col * 1.5f, fy, wattSamples > 0 ? String.valueOf(Math.round(wattSum / wattSamples)) : "0",
                "AVG WATTS");
        stat(c, col * 2.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 3.5f, fy, pace(boat.value()), "PACE /500");
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
