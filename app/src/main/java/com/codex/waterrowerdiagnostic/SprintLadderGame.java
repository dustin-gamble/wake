package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Sprint Ladder: rungs of shorter sprints with less rest, each demanding a higher peak power than
 * the last. Fall off when you cannot hit the target. Sessions stay short; the peak is the chase.
 */
final class SprintLadderGame extends GameView {

    private enum Phase { READY, SPRINT, REST, FELL, TOPPED }

    private static final int RUNGS = 8;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Measured p90 is 162 W and the peak 205 W. Starting at 120 with 12% steps put rung 4
    // beyond anything reachable, so the bar stopped responding partway up.
    private int startWatts = 105;
    private Phase phase = Phase.READY;
    private int rung;
    private double phaseStart;
    private int peakThisRung;
    private int bestRung;

    SprintLadderGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    void setStartWatts(int w) {
        startWatts = w;
        phase = Phase.READY;
    }

    int startWatts() {
        return startWatts;
    }

    private int targetFor(int r) {
        return Math.round(startWatts * (1f + 0.08f * r));
    }

    private int sprintSeconds(int r) {
        return Math.max(10, 30 - r * 2);
    }

    private int restSeconds(int r) {
        return Math.max(15, 45 - r * 4);
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        rung = 0;
        peakThisRung = 0;
        bestRung = Math.round(bests.get("ladder." + startWatts, 0f));
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.SPRINT;
            rung = 1;
            phaseStart = sessionSeconds;
            peakThisRung = 0;
        }
        if (phase == Phase.SPRINT) {
            peakThisRung = Math.max(peakThisRung, s.watts);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && (phase == Phase.FELL || phase == Phase.TOPPED)) {
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
        int watts = status == null ? 0 : status.watts;
        double elapsed = sessionSeconds - phaseStart;

        if (phase == Phase.SPRINT && elapsed >= sprintSeconds(rung)) {
            if (peakThisRung >= targetFor(rung)) {
                if (rung >= RUNGS) {
                    phase = Phase.TOPPED;
                    bests.recordHighest("ladder." + startWatts, rung);
                } else {
                    phase = Phase.REST;
                    phaseStart = sessionSeconds;
                }
            } else {
                phase = Phase.FELL;
                bests.recordHighest("ladder." + startWatts, rung - 1);
            }
        } else if (phase == Phase.REST && elapsed >= restSeconds(rung)) {
            phase = Phase.SPRINT;
            rung++;
            phaseStart = sessionSeconds;
            peakThisRung = 0;
        }

        // Ladder on the left: rungs bottom to top, lit as they are cleared.
        float lx0 = dp(24f);
        float lx1 = w * 0.34f;
        float top = h * 0.12f;
        float bottom = h * 0.86f;
        float gap = (bottom - top) / RUNGS;
        paint.setStrokeWidth(dp(4f));
        paint.setColor(0xFF2A3648);
        c.drawLine(lx0, top, lx0, bottom, paint);
        c.drawLine(lx1, top, lx1, bottom, paint);
        for (int r = 1; r <= RUNGS; r++) {
            float y = bottom - gap * (r - 0.5f);
            boolean cleared = r < rung || (r == rung && phase == Phase.REST) || phase == Phase.TOPPED;
            boolean current = r == rung && phase == Phase.SPRINT;
            paint.setColor(cleared ? ACCENT : current ? TEXT : 0xFF2A3648);
            paint.setStrokeWidth(current ? dp(7f) : dp(4f));
            c.drawLine(lx0, y, lx1, y, paint);
            label(c, targetFor(r) + " W", lx1 + dp(10f), y + dp(4f), 9f,
                    cleared ? ACCENT : current ? TEXT : FAINT, Paint.Align.LEFT);
        }

        // Right side: the state.
        float cx = w * 0.68f;
        String big;
        String cap;
        int col;
        switch (phase) {
            case READY:
                big = targetFor(1) + " W";
                cap = "FIRST RUNG - TAKE A STROKE";
                col = DIM;
                break;
            case SPRINT:
                big = clock(Math.max(0, sprintSeconds(rung) - elapsed));
                cap = "SPRINT  ·  HIT " + targetFor(rung) + " W";
                col = peakThisRung >= targetFor(rung) ? ACCENT : WARN;
                break;
            case REST:
                big = clock(Math.max(0, restSeconds(rung) - elapsed));
                cap = "REST  ·  NEXT RUNG " + targetFor(rung + 1) + " W";
                col = BLUE;
                break;
            case FELL:
                big = "RUNG " + (rung - 1);
                cap = "FELL OFF - TAP TO CLIMB AGAIN";
                col = BAD;
                break;
            default:
                big = "TOP";
                cap = "ALL " + RUNGS + " RUNGS - TAP TO GO AGAIN";
                col = ACCENT;
        }
        bold(c, big, cx, h * 0.34f, phase == Phase.SPRINT || phase == Phase.REST ? 60f : 34f, col,
                Paint.Align.CENTER);
        label(c, cap, cx, h * 0.34f + dp(22f), 10f, FAINT, Paint.Align.CENTER);

        bold(c, watts + " W", cx, h * 0.58f, 30f, TEXT, Paint.Align.CENTER);
        label(c, "NOW", cx, h * 0.58f + dp(16f), 9f, FAINT, Paint.Align.CENTER);
        if (phase == Phase.SPRINT) {
            bold(c, "peak " + peakThisRung + " W", cx, h * 0.72f, 16f,
                    peakThisRung >= targetFor(rung) ? ACCENT : DIM, Paint.Align.CENTER);
            label(c, peakThisRung >= targetFor(rung) ? "RUNG CLEARED - HOLD ON" : "NOT YET",
                    cx, h * 0.72f + dp(14f), 9f, FAINT, Paint.Align.CENTER);
        }
        label(c, bestRung > 0 ? "best rung " + bestRung + " from " + startWatts + " W" : "no best yet",
                cx, h - dp(14f), 9f, FAINT, Paint.Align.CENTER);
    }
}
