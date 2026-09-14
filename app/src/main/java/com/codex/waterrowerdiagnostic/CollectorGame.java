package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Collector: buoys drift toward you in three lanes - slow, steady, fast. Each lane's buoy is only
 * caught if your boat speed is in that lane's band when it reaches you. Makes variable-pace work
 * playful rather than tedious: the fast lane pays most, and you have to change gear to reach it.
 */
final class CollectorGame extends GameView {

    private static final int LANES = 3;
    // Measured speed while rowing: p10 3.0, median 3.85, p90 4.06 m/s. The old bands topped
    // out at 3.4, so the boat sat in the fast lane permanently and changing lane meant nearly
    // stopping - which is what read as lag. These three straddle the real working range.
    // 3.15.0: set from the rower's profile in onStart - the same three bands for a rower at
    // 3.0 / 3.85 / 4.06 m/s (low / typical / high), and the right ones for anyone else.
    private final float[] bandLo = {2.5f, 3.4f, 4.0f};
    private final float[] bandHi = {3.4f, 4.0f, 9.0f};
    /** Lane boundaries are sticky, so a speed sitting on a band edge cannot flicker. */
    private static final float BAND_STICK = 0.08f;
    private static final int[] POINTS = {1, 2, 4};
    private static final String[] NAMES = {"EASY", "STEADY", "FAST"};

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final float[] buoyX = new float[LANES];   // 0..1 across the screen, moving left
    private final float[] buoySpeed = new float[LANES];
    private final boolean[] caught = new boolean[LANES];
    private int score;
    private int missed;
    private double gameSeconds;
    private boolean started;
    /** Speed the lane follows: averaged over ~1.2 s, so a single stroke does not change lanes. */
    private float laneSpeed;
    private int lane = -1;
    private static final double GAME_LENGTH = 180;

    CollectorGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onStart() {
        score = 0;
        missed = 0;
        gameSeconds = 0;
        started = false;
        float low = (float) profile.lowSpeed();
        float typical = (float) profile.typicalSpeed();
        float high = (float) profile.highSpeed();
        bandLo[0] = low - 0.5f;
        bandHi[0] = (low + typical) / 2f;
        bandLo[1] = bandHi[0];
        bandHi[1] = (typical + high) / 2f + 0.05f;
        bandLo[2] = bandHi[1];
        bandHi[2] = 9f;
        laneSpeed = 0f;
        lane = -1;
        for (int i = 0; i < LANES; i++) {
            respawn(i, 1.2f + i * 0.35f);
        }
    }

    private void respawn(int lane, float at) {
        buoyX[lane] = at;
        buoySpeed[lane] = 0.09f + lane * 0.03f + (float) Math.random() * 0.03f;
        caught[lane] = false;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && gameSeconds >= GAME_LENGTH) {
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
        float speed = boat.value();
        boolean over = gameSeconds >= GAME_LENGTH;
        if (started && !over) {
            gameSeconds += dt;
            if (gameSeconds >= GAME_LENGTH) {
                bests.recordHighest("collector.score", score);
            }
        }

        float waterTop = h * 0.26f;
        float waterBottom = h * 0.80f;
        float ppm = w / 60f;
        river.advance(speed, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);

        float boatX = w * 0.22f;
        float laneH = (waterBottom - waterTop) / LANES;
        // Lanes follow an averaged speed with sticky edges ("smoothly move between zones, leveraging
        // some averaging"): the boat glides as the average moves, and only changes lane once the
        // average is clearly past a band edge.
        laneSpeed += (speed - laneSpeed) * Math.min(1f, dt / 1.2f);
        if (lane < 0 || laneSpeed < bandLo[lane] - BAND_STICK || laneSpeed >= bandHi[lane] + BAND_STICK) {
            lane = -1;
            for (int i = 0; i < LANES; i++) {
                if (laneSpeed >= bandLo[i] && laneSpeed < bandHi[i]) {
                    lane = i;
                }
            }
        }
        int inLane = lane;

        for (int i = 0; i < LANES; i++) {
            float ly = waterTop + laneH * (i + 0.5f);
            // Lane band label and highlight when you are in it.
            paint.setColor(i == inLane ? 0x2235D0BA : 0x00000000);
            c.drawRect(0, waterTop + laneH * i, w, waterTop + laneH * (i + 1), paint);
            label(c, NAMES[i] + "  " + String.format(java.util.Locale.US, "%.1f-%s m/s",
                    bandLo[i], i == LANES - 1 ? "" : String.format(java.util.Locale.US, "%.1f", bandHi[i])),
                    w - dp(12f), waterTop + laneH * i + dp(14f), 8.5f, i == inLane ? ACCENT : FAINT,
                    Paint.Align.RIGHT);

            if (started && !over) {
                buoyX[i] -= buoySpeed[i] * dt;
                float bx = buoyX[i] * w;
                if (!caught[i] && Math.abs(bx - boatX) < dp(18f)) {
                    if (i == inLane) {
                        caught[i] = true;
                        score += POINTS[i];
                    }
                }
                if (buoyX[i] < -0.05f) {
                    if (!caught[i]) {
                        missed++;
                    }
                    respawn(i, 1.05f + (float) Math.random() * 0.4f);
                }
            }
            float bx = buoyX[i] * w;
            if (!caught[i]) {
                paint.setColor(i == 0 ? BLUE : i == 1 ? ACCENT : WARN);
                c.drawCircle(bx, ly, dp(11f), paint);
                paint.setColor(0xFF0A0E14);
                c.drawCircle(bx, ly, dp(5f), paint);
                bold(c, "+" + POINTS[i], bx, ly - dp(16f), 10f, TEXT, Paint.Align.CENTER);
            }
        }

        // The boat's height is continuous in the averaged speed - part-way through a band it sits
        // part-way across the lane - so it drifts rather than jumping lane to lane.
        float laneCoord = 0.5f;
        for (int i = 0; i < LANES; i++) {
            float hi = i == LANES - 1 ? bandLo[i] + 0.6f : bandHi[i];
            if (laneSpeed >= bandLo[i]) {
                laneCoord = i + Math.min(1f, (laneSpeed - bandLo[i]) / (hi - bandLo[i]));
            }
        }
        laneCoord = Math.max(0.5f, Math.min(LANES - 0.5f, laneCoord));
        float targetLaneY = waterTop + laneH * laneCoord;
        boatY += (targetLaneY - boatY) * Math.min(1f, 3f * dt);
        if (boatY == 0f) {
            boatY = targetLaneY;
        }
        river.bowSpray(boatX + dp(40f), boatY, speed, dt);
        river.drawBoat(c, boatX, boatY, dp(80f), ACCENT, speed, false);
        river.drawSpray(c);

        bold(c, String.valueOf(score), w / 2f, h * 0.15f, 44f, ACCENT, Paint.Align.CENTER);
        label(c, !started ? "TAKE A STROKE TO START" : over ? "TIME - TAP TO PLAY AGAIN"
                : "POINTS  ·  " + clock(GAME_LENGTH - gameSeconds) + " LEFT",
                w / 2f, h * 0.15f + dp(20f), 10f, FAINT, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.1f m/s", speed), dp(16f), h * 0.15f, 22f,
                inLane >= 0 ? ACCENT : DIM, Paint.Align.LEFT);
        label(c, inLane >= 0 ? NAMES[inLane] + " LANE" : "TOO SLOW FOR A LANE", dp(16f),
                h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, String.valueOf(missed), w - dp(16f), h * 0.15f, 22f, DIM, Paint.Align.RIGHT);
        label(c, "MISSED", w - dp(16f), h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.RIGHT);

        float fy = h - dp(14f);
        float col = w / 3f;
        stat(c, col * 0.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 1.5f, fy, status == null ? "0" : String.valueOf(status.watts), "WATTS");
        stat(c, col * 2.5f, fy, bests.has("collector.score")
                ? String.valueOf(Math.round(bests.get("collector.score", 0))) : "--", "BEST");
    }

    private float boatY;

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
