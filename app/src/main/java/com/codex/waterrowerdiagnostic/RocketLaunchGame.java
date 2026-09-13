package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Rocket Launch: sustained power against gravity.
 *
 * <p>Your watts are thrust. Hold above the hover threshold and you climb; drop below and gravity
 * takes it back. Each stage you reach sheds mass, so the threshold falls and the rocket that was
 * barely flying starts to leap - the reward for a long hard effort is that it gets easier.
 * Reach the Karman line at 100 km and you are in orbit.
 *
 * <p>The counterpoint to Mega Pull: that one is a single burst, this is the long grind.
 */
final class RocketLaunchGame extends GameView {

    private static final float KARMAN = 100_000f;      // metres, the finish
    private static final float[] STAGE_ALT = {0f, 12_000f, 35_000f, 70_000f};
    // Was {130, 105, 80, 58}. Measured median power is 129 W, so the first stage asked for
    // exactly a steady effort just to hover and the rocket never left the pad. Lift-off now
    // wants a firm pull rather than a personal best, and the ladder still eases as it climbs.
    private static final float[] STAGE_HOVER = {95f, 82f, 68f, 54f};
    private static final String[] LAYER = {"TROPOSPHERE", "STRATOSPHERE", "MESOSPHERE", "THERMOSPHERE"};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final float[] starX = new float[70];
    private final float[] starY = new float[70];
    private final float[] starR = new float[70];

    private boolean started;
    private boolean over;
    private boolean orbit;
    private float altitude;
    private float velocity;           // m/s of altitude
    private int stage;
    private double stageFlash;
    private float plume;
    private double maxAltitude;

    RocketLaunchGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        java.util.Random r = new java.util.Random(9);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = r.nextFloat();
            starY[i] = r.nextFloat();
            starR[i] = 0.6f + r.nextFloat() * 1.6f;
        }
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        orbit = false;
        altitude = 0f;
        velocity = 0f;
        stage = 0;
        maxAltitude = 0;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && s.watts > 0) {
            started = true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && (over || orbit)) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private float hoverWatts() {
        return STAGE_HOVER[Math.min(stage, STAGE_HOVER.length - 1)];
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;

        if (started && !over && !orbit) {
            // Net thrust in altitude m/s^2. Thin air above 40 km helps, so the top is a payoff.
            float thin = 1f + Math.min(1.2f, altitude / 60_000f);
            float accel = (watts - hoverWatts()) * 0.055f * thin;
            velocity += accel * dt;
            velocity *= 0.985f;                    // drag, stops runaway
            altitude = Math.max(0f, altitude + velocity * dt * 26f);
            maxAltitude = Math.max(maxAltitude, altitude);
            if (altitude <= 0f && velocity < -6f) {
                over = true;
                bests.recordHighest("rocket.altitude", (float) maxAltitude);
                shake.kick(dp(20f));
                fx.burst(w * 0.5f, h * 0.74f, 60, dp(260f), 1.2f, dp(4.5f), 0xFFFF7A3D, true);
            }
            int newStage = stage;
            for (int i = STAGE_ALT.length - 1; i >= 0; i--) {
                if (altitude >= STAGE_ALT[i]) {
                    newStage = i;
                    break;
                }
            }
            if (newStage > stage) {
                stage = newStage;
                stageFlash = 1.4;
                shake.kick(dp(10f));
                fx.burst(w * 0.5f, h * 0.62f, 34, dp(200f), 0.9f, dp(4f), 0xFFBFE3FF, false);
            }
            if (altitude >= KARMAN) {
                orbit = true;
                bests.recordHighest("rocket.altitude", KARMAN);
            }
        }
        stageFlash = Math.max(0, stageFlash - dt);
        shake.step(dt);
        fx.step(dt, dp(160f));
        float throttle = Math.max(0f, Math.min(1.4f, watts / Math.max(1f, hoverWatts())));
        plume += ((throttle) - plume) * Math.min(1f, 8f * dt);

        // Sky: blue at sea level through to black at the Karman line.
        float space = Math.min(1f, altitude / 80_000f);
        c.save();
        c.translate(shake.dx, shake.dy);
        paint.setShader(new LinearGradient(0, 0, 0, h,
                blend(0xFF2F7FD0, 0xFF000308, space), blend(0xFF9ED2F5, 0xFF040A14, space),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // Stars fade in with altitude.
        if (space > 0.05f) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) (space * 230));
            for (int i = 0; i < starX.length; i++) {
                float sy = (starY[i] * h + altitude * 0.0016f) % h;
                c.drawCircle(starX[i] * w, sy, dp(starR[i]), paint);
            }
            paint.setAlpha(255);
        }

        float rocketY = h * 0.58f;
        // Ground and the curve of the earth receding.
        float groundY = rocketY + dp(60f) + Math.min(h, altitude * 0.012f);
        if (groundY < h + dp(400f)) {
            paint.setColor(0xFF2E6B35);
            if (altitude < 20_000f) {
                c.drawRect(0, groundY, w, h + dp(400f), paint);
                paint.setColor(0xFF20532A);
                float scroll = (altitude * 0.6f) % dp(60f);
                for (float gx = -scroll; gx < w; gx += dp(60f)) {
                    c.drawRect(gx, groundY, gx + dp(3f), h, paint);
                }
                // Launch pad.
                paint.setColor(0xFF4A4A55);
                c.drawRect(w * 0.5f - dp(40f), groundY - dp(6f), w * 0.5f + dp(40f), groundY, paint);
            } else {
                // High enough for the horizon to curve.
                paint.setColor(0xFF2E6B35);
                c.drawOval(-w * 1.2f, groundY, w * 2.2f, groundY + h * 2.4f, paint);
                paint.setColor(0x5599D8FF);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(6f));
                c.drawOval(-w * 1.2f, groundY, w * 2.2f, groundY + h * 2.4f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }

        // Clouds drifting down past you while in the troposphere.
        if (altitude < 16_000f) {
            paint.setColor(0xCCFFFFFF);
            for (int i = 0; i < 5; i++) {
                float cy = ((i * 173f * dp(1f)) + altitude * 0.05f) % (h + dp(120f)) - dp(60f);
                float cx = (i % 2 == 0 ? 0.22f : 0.78f) * w + (float) Math.sin(i + sessionSeconds * 0.2) * dp(20f);
                paint.setAlpha((int) (170 * (1f - altitude / 16_000f)));
                c.drawRoundRect(cx - dp(46f), cy, cx + dp(46f), cy + dp(20f), dp(10f), dp(10f), paint);
                c.drawRoundRect(cx - dp(26f), cy - dp(12f), cx + dp(22f), cy + dp(20f), dp(12f), dp(12f), paint);
            }
            paint.setAlpha(255);
        }

        // Exhaust plume, then the rocket.
        float rx = w * 0.5f;
        if (plume > 0.05f && !over) {
            float len = dp(20f) + plume * dp(90f);
            paint.setColor(0xFFFFD36A);
            path.reset();
            path.moveTo(rx - dp(11f), rocketY + dp(30f));
            path.lineTo(rx + dp(11f), rocketY + dp(30f));
            path.lineTo(rx + dp(4f), rocketY + dp(30f) + len);
            path.lineTo(rx - dp(4f), rocketY + dp(30f) + len);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFFFF7A3D);
            path.reset();
            path.moveTo(rx - dp(6f), rocketY + dp(30f));
            path.lineTo(rx + dp(6f), rocketY + dp(30f));
            path.lineTo(rx, rocketY + dp(30f) + len * 0.62f);
            path.close();
            c.drawPath(path, paint);
            Fx.glow(c, rx, rocketY + dp(40f), len * 0.8f, 0x55FF9A4D);
        }
        if (!over) {
            paint.setColor(0xFFE6EDF7);
            path.reset();
            path.moveTo(rx, rocketY - dp(40f));
            path.lineTo(rx + dp(13f), rocketY - dp(4f));
            path.lineTo(rx + dp(13f), rocketY + dp(30f));
            path.lineTo(rx - dp(13f), rocketY + dp(30f));
            path.lineTo(rx - dp(13f), rocketY - dp(4f));
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFFD8453C);
            path.reset();
            path.moveTo(rx - dp(13f), rocketY + dp(14f));
            path.lineTo(rx - dp(26f), rocketY + dp(34f));
            path.lineTo(rx - dp(13f), rocketY + dp(30f));
            path.close();
            c.drawPath(path, paint);
            path.reset();
            path.moveTo(rx + dp(13f), rocketY + dp(14f));
            path.lineTo(rx + dp(26f), rocketY + dp(34f));
            path.lineTo(rx + dp(13f), rocketY + dp(30f));
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFF6FA6D6);
            c.drawCircle(rx, rocketY - dp(8f), dp(6f), paint);
        }
        fx.draw(c);
        c.restore();

        if (stageFlash > 0) {
            Fx.vignette(c, w, h, (float) stageFlash * 0.5f, 0x1A4A8A);
        }

        // Altitude ladder on the right with the named layers.
        float ladderTop = dp(70f);
        float ladderBottom = h - dp(60f);
        float lx = w - dp(26f);
        paint.setColor(0x33FFFFFF);
        c.drawRect(lx - dp(2f), ladderTop, lx + dp(2f), ladderBottom, paint);
        for (int i = 0; i < STAGE_ALT.length; i++) {
            float f = STAGE_ALT[i] / KARMAN;
            float y = ladderBottom - (ladderBottom - ladderTop) * f;
            paint.setColor(altitude >= STAGE_ALT[i] ? ACCENT : 0x66FFFFFF);
            c.drawRect(lx - dp(8f), y - dp(1.5f), lx + dp(8f), y + dp(1.5f), paint);
            label(c, LAYER[i], lx - dp(12f), y + dp(4f), 7.5f,
                    altitude >= STAGE_ALT[i] ? ACCENT : FAINT, Paint.Align.RIGHT);
        }
        paint.setColor(0xFFF5C518);
        c.drawRect(lx - dp(10f), ladderTop - dp(2f), lx + dp(10f), ladderTop + dp(2f), paint);
        label(c, "KARMAN 100 km", lx - dp(12f), ladderTop + dp(4f), 7.5f, 0xFFF5C518, Paint.Align.RIGHT);
        float py = ladderBottom - (ladderBottom - ladderTop) * Math.min(1f, altitude / KARMAN);
        paint.setColor(ACCENT);
        c.drawCircle(lx, py, dp(6f), paint);

        // HUD.
        String big;
        int col;
        if (!started) {
            big = "HOLD " + Math.round(hoverWatts()) + " W TO LIFT OFF";
            col = DIM;
        } else if (orbit) {
            big = "ORBIT";
            col = ACCENT;
        } else if (over) {
            big = "CRASHED";
            col = BAD;
        } else {
            big = altitude < 1000 ? Math.round(altitude) + " m"
                    : String.format(java.util.Locale.US, "%.1f km", altitude / 1000f);
            col = watts >= hoverWatts() ? ACCENT : BAD;
        }
        bold(c, big, w * 0.30f, h * 0.20f, started && !over && !orbit ? 44f : 24f, col,
                Paint.Align.CENTER);
        String cap;
        if (!started) {
            cap = "thrust is your watts - gravity never stops pulling";
        } else if (orbit) {
            cap = "you made the Karman line  ·  tap to launch again";
        } else if (over) {
            cap = "peak " + String.format(java.util.Locale.US, "%.1f km", maxAltitude / 1000f)
                    + "  ·  tap to launch again";
        } else if (stageFlash > 0) {
            cap = "STAGE " + (stage + 1) + " - MASS SHED, HOVER NOW " + Math.round(hoverWatts()) + " W";
        } else if (watts >= hoverWatts()) {
            cap = "CLIMBING  ·  " + Math.round(velocity * 26f) + " m/s";
        } else {
            cap = "FALLING - NEED " + Math.round(hoverWatts()) + " W";
        }
        bold(c, cap, w * 0.30f, h * 0.20f + dp(22f), 11f,
                stageFlash > 0 ? BLUE : watts >= hoverWatts() || !started ? FAINT : BAD,
                Paint.Align.CENTER);

        // Thrust bar against the hover line.
        float barY = h - dp(34f);
        float barL = dp(16f);
        float barR = w - dp(60f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, barY, barR, barY + dp(12f), dp(6f), dp(6f), paint);
        float scaleMax = hoverWatts() * 2f;
        paint.setColor(watts >= hoverWatts() ? ACCENT : BAD);
        c.drawRoundRect(barL, barY, barL + (barR - barL) * Math.min(1f, watts / scaleMax), barY + dp(12f),
                dp(6f), dp(6f), paint);
        float hoverX = barL + (barR - barL) * (hoverWatts() / scaleMax);
        paint.setColor(TEXT);
        c.drawRect(hoverX - dp(2f), barY - dp(5f), hoverX + dp(2f), barY + dp(17f), paint);
        label(c, "HOVER " + Math.round(hoverWatts()) + " W", hoverX, barY - dp(9f), 8f, TEXT,
                Paint.Align.CENTER);
        label(c, watts + " W", barL, barY - dp(9f), 8.5f, FAINT, Paint.Align.LEFT);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
