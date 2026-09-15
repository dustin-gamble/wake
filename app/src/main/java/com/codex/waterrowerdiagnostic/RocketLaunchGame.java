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
    // Was {130, 105, 80, 58} W, then {95, 82, 68, 54} W tuned to one rower's 129 W median. Now a
    // share of each rower's own typical power (74% is that 95 W for the original rower), so lift-off
    // wants a firm pull from anyone, and the ladder still eases as it climbs.
    private static final float[] STAGE_HOVER_SHARE = {0.74f, 0.64f, 0.53f, 0.42f};
    /** The power test: how high can you get in one minute. */
    private static final float TEST_SECONDS = 60f;
    private static final String[] LAYER = {"TROPOSPHERE", "STRATOSPHERE", "MESOSPHERE", "THERMOSPHERE"};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    // 3.19.5: smoke trail puffs, and the spent booster tumbling away after a stage.
    private final float[] smokeX = new float[40];
    private final float[] smokeY = new float[40];
    private final float[] smokeLife = new float[40];
    private int smokeNext;
    private double smokeClock;
    private float boosterY;
    private float boosterSpin;
    private double boosterUntil;
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
    private boolean testMode;
    private boolean testDone;
    private double testSeconds;
    private double testWattSeconds;

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
        testDone = false;
        testSeconds = 0;
        testWattSeconds = 0;
    }

    /** A 60-second power test instead of the climb to orbit. Restarts the launch. */
    void setTestMode(boolean test) {
        testMode = test;
        start();
    }

    boolean testMode() {
        return testMode;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && s.watts > 0) {
            started = true;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && (over || orbit || testDone)) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private float hoverWatts() {
        return (float) profile.typicalWatts() * STAGE_HOVER_SHARE[Math.min(stage, STAGE_HOVER_SHARE.length - 1)];
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;

        if (started && !over && !orbit && testMode) {
            testSeconds += dt;
            testWattSeconds += watts * dt;
            if (testSeconds >= TEST_SECONDS) {
                over = true;
                testDone = true;
                bests.recordHighest("rocket.test60", (float) maxAltitude);
            }
        }
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
                boosterY = h * 0.58f + dp(44f);
                boosterSpin = 0f;
                boosterUntil = sessionSeconds + 3.0;
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
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
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
        // 3.19.5: the ground drops away within the first few km. At 0.012 px/m the rocket still sat
        // beside its launch tower at 2.4 km on the emulator.
        float groundY = rocketY + dp(60f) + Math.min(h, altitude * 0.25f);
        if (groundY < h + dp(400f)) {
            paint.setColor(0xFF2E6B35);
            if (altitude < 20_000f) {
                c.drawRect(0, groundY, w, h + dp(400f), paint);
                paint.setColor(0xFF20532A);
                float scroll = (altitude * 0.6f) % dp(60f);
                for (float gx = -scroll; gx < w; gx += dp(60f)) {
                    c.drawRect(gx, groundY, gx + dp(3f), h, paint);
                }
                // Launch pad and its tower, which the rocket leaves behind.
                paint.setColor(0xFF4A4A55);
                c.drawRect(w * 0.5f - dp(40f), groundY - dp(6f), w * 0.5f + dp(40f), groundY, paint);
                float towerX = w * 0.5f - dp(48f);
                paint.setColor(0xFFB8452F);
                c.drawRect(towerX - dp(6f), groundY - dp(120f), towerX, groundY - dp(6f), paint);
                c.drawRect(towerX - dp(14f), groundY - dp(120f), towerX - dp(10f), groundY - dp(6f), paint);
                paint.setStrokeWidth(dp(1.5f));
                for (float ty = groundY - dp(114f); ty < groundY - dp(8f); ty += dp(14f)) {
                    c.drawLine(towerX - dp(14f), ty, towerX, ty + dp(14f), paint);
                }
                c.drawRect(towerX - dp(14f), groundY - dp(80f), w * 0.5f - dp(16f), groundY - dp(76f), paint);
                if (((int) (sessionSeconds * 2)) % 2 == 0) {
                    paint.setColor(0xFFFF3B3B);
                    c.drawCircle(towerX - dp(7f), groundY - dp(126f), dp(3f), paint);
                }
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

        // Clouds drifting down past you while in the troposphere: puffy, several sizes.
        if (altitude < 16_000f) {
            int cloudA = (int) (190 * (1f - altitude / 16_000f));
            for (int i = 0; i < 7; i++) {
                float cy = ((i * 151f * dp(1f)) + altitude * 0.05f) % (h + dp(160f)) - dp(80f);
                if (cy > groundY - dp(30f)) {
                    continue;   // clouds belong in the sky, not drifting across the field
                }
                float cx = ((i * 0.29f + 0.1f) % 1f) * w + (float) Math.sin(i + sessionSeconds * 0.2) * dp(20f);
                float sc = 0.7f + (i % 3) * 0.35f;
                paint.setColor((cloudA << 24) | 0xFFFFFF);
                c.drawOval(cx - dp(70f) * sc, cy - dp(12f) * sc, cx + dp(70f) * sc, cy + dp(14f) * sc, paint);
                c.drawOval(cx - dp(40f) * sc, cy - dp(30f) * sc, cx + dp(20f) * sc, cy + dp(6f) * sc, paint);
                c.drawOval(cx - dp(4f) * sc, cy - dp(22f) * sc, cx + dp(46f) * sc, cy + dp(8f) * sc, paint);
            }
        }
        drawAltitudeLife(c, w, h);
        drawSmoke(c, w, h, dt);
        if (sessionSeconds < boosterUntil) {
            // The spent stage falls away, tumbling.
            boosterY += dp(160f) * dt;
            boosterSpin += 220f * dt;
            c.save();
            c.rotate(boosterSpin, w * 0.5f + dp(26f), boosterY);
            paint.setColor(0xFFCFD6DE);
            c.drawRoundRect(w * 0.5f + dp(18f), boosterY - dp(22f), w * 0.5f + dp(34f), boosterY + dp(22f), dp(4f), dp(4f), paint);
            paint.setColor(0xFFD8453C);
            c.drawRect(w * 0.5f + dp(18f), boosterY + dp(12f), w * 0.5f + dp(34f), boosterY + dp(22f), paint);
            c.restore();
        }

        // Exhaust plume, then the rocket.
        float rx = w * 0.5f;
        if (plume > 0.05f && (!over || testDone) && !testDone) {
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
        if (!over || testDone) {
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
            big = testMode ? "60-SECOND POWER TEST" : "HOLD " + Math.round(hoverWatts()) + " W TO LIFT OFF";
            col = DIM;
        } else if (testDone) {
            big = String.format(java.util.Locale.US, "%.1f km", maxAltitude / 1000f);
            col = ACCENT;
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
            cap = testMode ? "climb as high as you can in one minute - lift-off at " + Math.round(hoverWatts()) + " W"
                    : "thrust is your watts - gravity never stops pulling";
        } else if (testDone) {
            cap = "TEST COMPLETE  ·  average " + Math.round(testWattSeconds / TEST_SECONDS) + " W"
                    + (bests.has("rocket.test60") ? "  ·  best " + String.format(java.util.Locale.US, "%.1f km", bests.get("rocket.test60", 0f) / 1000f) : "")
                    + "  ·  tap to test again";
        } else if (orbit) {
            cap = "you made the Karman line  ·  tap to launch again";
        } else if (over) {
            cap = "peak " + String.format(java.util.Locale.US, "%.1f km", maxAltitude / 1000f)
                    + "  ·  tap to launch again";
        } else if (stageFlash > 0) {
            cap = "STAGE " + (stage + 1) + " - MASS SHED, HOVER NOW " + Math.round(hoverWatts()) + " W";
        } else if (testMode) {
            cap = clock(Math.max(0, TEST_SECONDS - testSeconds)) + " LEFT  ·  " + (watts >= hoverWatts() ? "CLIMBING" : "NEED " + Math.round(hoverWatts()) + " W");
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

    /** Birds and a jet low down, a weather balloon higher, then satellites, the moon and a station. */
    private void drawAltitudeLife(Canvas c, float w, float h) {
        double t = sessionSeconds;
        if (altitude < 6_000f) {
            float by = h * 0.35f + (altitude * 0.08f) % (h * 0.6f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(0xAA1A2230);
            float bx0 = (float) (w - ((t * dp(50f)) % (w + dp(300f))));
            for (int b = 0; b < 5; b++) {
                float bx = bx0 + b * dp(22f);
                float yy = by + (b % 2) * dp(9f);
                float flap = (float) Math.sin(t * 8 + b) * dp(4f);
                c.drawLine(bx - dp(7f), yy - flap, bx, yy, paint);
                c.drawLine(bx, yy, bx + dp(7f), yy - flap, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        if (altitude > 3_000f && altitude < 14_000f) {
            float jy = h * 0.2f + ((altitude - 3_000f) * 0.06f) % (h * 0.7f);
            float jx = (float) (((t * dp(120f)) % (w + dp(400f))) - dp(200f));
            paint.setStrokeWidth(dp(3f));
            paint.setColor(0x88FFFFFF);
            c.drawLine(jx - dp(220f), jy, jx - dp(20f), jy, paint);
            paint.setColor(0xFFE9EEF5);
            c.drawRoundRect(jx - dp(22f), jy - dp(4f), jx + dp(22f), jy + dp(4f), dp(4f), dp(4f), paint);
            c.drawRect(jx - dp(6f), jy - dp(14f), jx + dp(4f), jy + dp(14f), paint);
        }
        if (altitude > 18_000f && altitude < 40_000f) {
            float gy = h * 0.3f + ((altitude - 18_000f) * 0.02f) % (h * 0.6f);
            float gx = w * 0.2f + (float) Math.sin(t * 0.3) * dp(20f);
            paint.setColor(0xDDF4F4F4);
            c.drawCircle(gx, gy, dp(18f), paint);
            paint.setStrokeWidth(dp(1f));
            paint.setColor(0x99FFFFFF);
            c.drawLine(gx, gy + dp(18f), gx, gy + dp(50f), paint);
            paint.setColor(0xFFF5C518);
            c.drawRect(gx - dp(4f), gy + dp(50f), gx + dp(4f), gy + dp(58f), paint);
        }
        float space = Math.min(1f, altitude / 80_000f);
        if (space > 0.5f) {
            int a = (int) (255 * Math.min(1f, (space - 0.5f) * 3f));
            Fx.glow(c, w * 0.18f, h * 0.18f, dp(70f), (Math.min(a, 90) << 24) | 0xE9EEF5);
            paint.setColor((a << 24) | 0xE9EEF5);
            c.drawCircle(w * 0.18f, h * 0.18f, dp(30f), paint);
            paint.setColor((Math.min(a, 60) << 24) | 0x9AA5B1);
            c.drawCircle(w * 0.18f - dp(8f), h * 0.18f - dp(6f), dp(6f), paint);
            c.drawCircle(w * 0.18f + dp(10f), h * 0.18f + dp(8f), dp(4f), paint);
            for (int k = 0; k < 2; k++) {
                float sx = (float) (((t * dp(26f + k * 18f) + k * 700) % (w + dp(200f))) - dp(100f));
                float sy = h * (0.3f + k * 0.25f);
                paint.setColor((a << 24) | 0xC8D2DC);
                c.drawRect(sx - dp(5f), sy - dp(5f), sx + dp(5f), sy + dp(5f), paint);
                paint.setColor((a << 24) | 0x3A6EA5);
                c.drawRect(sx - dp(24f), sy - dp(3f), sx - dp(7f), sy + dp(3f), paint);
                c.drawRect(sx + dp(7f), sy - dp(3f), sx + dp(24f), sy + dp(3f), paint);
                if (((int) (t * 2 + k)) % 2 == 0) {
                    paint.setColor((a << 24) | 0xFF4A4A);
                    c.drawCircle(sx, sy - dp(8f), dp(2f), paint);
                }
            }
        }
    }

    /** Smoke puffs shed from the nozzle while thrusting, growing and fading as they fall behind. */
    private void drawSmoke(Canvas c, float w, float h, float dt) {
        float rocketY = h * 0.58f;
        smokeClock += dt;
        if (started && !over && plume > 0.2f && smokeClock > 0.06) {
            smokeClock = 0;
            smokeX[smokeNext] = w * 0.5f + (float) (Math.random() - 0.5) * dp(10f);
            smokeY[smokeNext] = rocketY + dp(40f) + plume * dp(60f);
            smokeLife[smokeNext] = 1f;
            smokeNext = (smokeNext + 1) % smokeX.length;
        }
        float space = Math.min(1f, altitude / 80_000f);
        for (int i = 0; i < smokeX.length; i++) {
            if (smokeLife[i] <= 0) {
                continue;
            }
            smokeLife[i] -= dt * 0.6f;
            smokeY[i] += dp(120f) * dt * (0.5f + velocity * 0.02f);
            smokeX[i] += (float) Math.sin(i + smokeLife[i] * 6) * dp(10f) * dt;
            float r = dp(8f) + (1f - smokeLife[i]) * dp(34f);
            int a = (int) (smokeLife[i] * 150 * (1f - space));
            paint.setColor((Math.max(0, a) << 24) | 0xE6E6EA);
            c.drawCircle(smokeX[i], smokeY[i], r, paint);
        }
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
