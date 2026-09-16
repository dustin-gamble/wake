package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Collector: buoys drift toward you in three lanes - slow, steady, fast. Each lane's buoy is only
 * caught if your boat speed is in that lane's band when it reaches you. Makes variable-pace work
 * playful rather than tedious: the fast lane pays most, and you have to change gear to reach it.
 *
 * <p>3.19.4, from the first emulator screenshots (a dark screen with one ring on it): a sky and a
 * bank with a crowd, reeds along the near shore, coins / gems / stars with glow and spin instead of
 * rings, a burst and a floating "+N" on every catch, a combo that pays a bonus every five catches in a
 * row, ducks and jumping fish, and speed lines in the fast lane.
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
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private final android.graphics.Path shape = new android.graphics.Path();
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;
    private int combo;
    private int bestCombo;
    private String popup = "";
    private int popupColor = TEXT;
    private float popupX;
    private float popupY;
    private double popupUntil;
    private double comboUntil;
    private double fishAt = 6;
    private float fishX;
    private float fishY;

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
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
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
        combo = 0;
        bestCombo = 0;
        popupUntil = 0;
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

        float waterTop = h * 0.30f;
        float waterBottom = h * 0.84f;
        float ppm = w / 60f;
        drawSky(c, w, waterTop - dp(46f));
        river.advance(speed, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        scenery.drawBank(c, w, waterTop - dp(46f), waterTop, sessionMeters, ppm,
                sessionSeconds, Math.min(1f, combo / 10f));
        drawNearShore(c, w, h, waterBottom, ppm);
        fx.step(dt, dp(300f));
        drawFish(c, w, waterTop, waterBottom);

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
                    w - dp(14f), waterTop + laneH * i + dp(20f), 11f, i == inLane ? ACCENT : DIM,
                    Paint.Align.RIGHT);
            if (i > 0) {
                scenery.drawBuoys(c, w, waterTop + laneH * i, sessionMeters, ppm, sessionSeconds, 8f);
            }

            if (started && !over) {
                buoyX[i] -= buoySpeed[i] * dt;
                float bx = buoyX[i] * w;
                if (!caught[i] && Math.abs(bx - boatX) < dp(18f)) {
                    if (i == inLane) {
                        caught[i] = true;
                        score += POINTS[i];
                        combo++;
                        bestCombo = Math.max(bestCombo, combo);
                        float ly2 = waterTop + laneH * (i + 0.5f);
                        fx.burst(bx, ly2, 18 + i * 8, dp(150f), 0.7f, dp(3f), COLORS[i], false);
                        popup = "+" + POINTS[i];
                        popupColor = COLORS[i];
                        if (combo % 5 == 0) {
                            score += 5;
                            popup = "+" + POINTS[i] + "  COMBO x" + combo + "  +5";
                            comboUntil = sessionSeconds + 1.6;
                            fx.burst(boatX, boatY, 40, dp(240f), 1.1f, dp(3.5f), 0xFFF5C518, true);
                        }
                        popupX = bx;
                        popupY = ly2 - dp(20f);
                        popupUntil = sessionSeconds + 1.0;
                    }
                }
                if (buoyX[i] < -0.05f) {
                    if (!caught[i]) {
                        missed++;
                        combo = 0;
                    }
                    respawn(i, 1.05f + (float) Math.random() * 0.4f);
                }
            }
            float bx = buoyX[i] * w;
            if (!caught[i] && bx > -dp(30f) && bx < w + dp(30f)) {
                drawPrize(c, i, bx, ly + (float) Math.sin(sessionSeconds * 3 + i) * dp(3f));
                bold(c, "+" + POINTS[i], bx, ly - dp(30f), 12f, TEXT, Paint.Align.CENTER);
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
        if (inLane == LANES - 1) {
            paint.setColor(0x55FFFFFF);
            Fx.speedLines(c, paint, w, waterBottom, speed, sessionSeconds, getResources().getDisplayMetrics().density);
        }
        if (combo >= 3) {
            Fx.glow(c, boatX, boatY, dp(70f + Math.min(combo, 20) * 3f), 0x40F5C518);
        }
        river.setStrokePhase(strokePhase());
        river.bowSpray(boatX + dp(56f), boatY, speed, dt);
        river.drawBoat(c, boatX, boatY, dp(116f), ACCENT, speed, false);
        river.drawSpray(c);
        fx.draw(c);
        if (sessionSeconds < popupUntil) {
            float rise = (float) (1.0 - (popupUntil - sessionSeconds)) * dp(50f);
            bold(c, popup, popupX, popupY - rise, 20f, popupColor, Paint.Align.CENTER);
        }
        if (combo >= 2) {
            bold(c, "COMBO x" + combo, boatX, boatY - dp(34f), sessionSeconds < comboUntil ? 22f : 13f,
                    0xFFF5C518, Paint.Align.CENTER);
        }

        // 3.19.5: the sky is light now (the shader-alpha fix), so the HUD sits on dark pills.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRoundRect(w / 2f - dp(90f), h * 0.15f - dp(40f), w / 2f + dp(90f), h * 0.15f + dp(28f), dp(14f), dp(14f), paint);
        c.drawRoundRect(dp(6f), h * 0.15f - dp(28f), dp(190f), h * 0.15f + dp(24f), dp(12f), dp(12f), paint);
        c.drawRoundRect(w - dp(120f), h * 0.15f - dp(28f), w - dp(6f), h * 0.15f + dp(24f), dp(12f), dp(12f), paint);
        bold(c, String.valueOf(score), w / 2f, h * 0.15f, 44f, ACCENT, Paint.Align.CENTER);
        label(c, !started ? "TAKE A STROKE TO START" : over ? "TIME - TAP TO PLAY AGAIN"
                : "POINTS  ·  " + clock(GAME_LENGTH - gameSeconds) + " LEFT",
                w / 2f, h * 0.15f + dp(20f), 10f, DIM, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.1f m/s", speed), dp(16f), h * 0.15f, 22f,
                inLane >= 0 ? ACCENT : DIM, Paint.Align.LEFT);
        label(c, inLane >= 0 ? NAMES[inLane] + " LANE" : "TOO SLOW FOR A LANE", dp(16f),
                h * 0.15f + dp(16f), 9f, DIM, Paint.Align.LEFT);
        bold(c, String.valueOf(missed), w - dp(16f), h * 0.15f, 22f, DIM, Paint.Align.RIGHT);
        label(c, "MISSED", w - dp(16f), h * 0.15f + dp(16f), 9f, DIM, Paint.Align.RIGHT);

        float fy = h - dp(14f);
        float col = w / 3f;
        // The footer sits on bright grass since the sky/shore pass: give it a dark band to read on.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRect(0, fy - dp(34f), w, h, paint);
        stat(c, col * 0.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col * 1.5f, fy, status == null ? "0" : String.valueOf(status.watts), "WATTS");
        stat(c, col * 2.5f, fy, bests.has("collector.score")
                ? String.valueOf(Math.round(bests.get("collector.score", 0))) : "--", "BEST");
    }

    private float boatY;

    private static final int[] COLORS = {0xFFFFD34D, 0xFF4FE3FF, 0xFFFF7AE0};

    /** A coin (easy), a gem (steady) or a star (fast), spinning and glowing. */
    private void drawPrize(Canvas c, int kind, float x, float y) {
        float r = dp(14f + kind * 3f);
        Fx.glow(c, x, y, r * 2.4f, (COLORS[kind] & 0x00FFFFFF) | 0x55000000);
        float spin = (float) Math.abs(Math.cos(sessionSeconds * 3 + kind));
        paint.setStyle(Paint.Style.FILL);
        if (kind == 0) {
            float rx = r * (0.25f + 0.75f * spin);
            paint.setColor(0xFFC99A1E);
            c.drawOval(x - rx, y - r, x + rx, y + r, paint);
            paint.setColor(COLORS[0]);
            c.drawOval(x - rx * 0.8f, y - r * 0.8f, x + rx * 0.8f, y + r * 0.8f, paint);
        } else if (kind == 1) {
            float rx = r * (0.5f + 0.5f * spin);
            shape.rewind();
            shape.moveTo(x, y - r);
            shape.lineTo(x + rx, y - r * 0.25f);
            shape.lineTo(x, y + r);
            shape.lineTo(x - rx, y - r * 0.25f);
            shape.close();
            paint.setColor(COLORS[1]);
            c.drawPath(shape, paint);
            paint.setColor(0x99FFFFFF);
            c.drawRect(x - rx * 0.5f, y - r * 0.45f, x, y - r * 0.2f, paint);
        } else {
            double a0 = sessionSeconds * 2;
            shape.rewind();
            for (int k = 0; k < 10; k++) {
                double a = a0 + k * Math.PI / 5;
                float rr = (k & 1) == 0 ? r : r * 0.45f;
                float px = x + (float) Math.cos(a) * rr;
                float py = y + (float) Math.sin(a) * rr;
                if (k == 0) {
                    shape.moveTo(px, py);
                } else {
                    shape.lineTo(px, py);
                }
            }
            shape.close();
            paint.setColor(COLORS[2]);
            c.drawPath(shape, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, r * 0.2f, paint);
        }
    }

    private void drawSky(Canvas c, float w, float bottom) {
        if (skyShader == null || skyHeight != bottom) {
            skyHeight = bottom;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, bottom, 0xFF3C7BC0, 0xFFBFE1F5,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, bottom, paint);
        paint.setShader(null);
        Fx.glow(c, w * 0.8f, bottom * 0.35f, dp(90f), 0x88FFF4C0);
        paint.setColor(0xFFFFF4C0);
        c.drawCircle(w * 0.8f, bottom * 0.35f, dp(26f), paint);
        paint.setColor(0xDDFFFFFF);
        for (int i = 0; i < 4; i++) {
            float span = w + dp(300f);
            float cx = (float) (((i * 530 + 120) - sessionMeters * 1.5) % span);
            if (cx < -dp(150f)) {
                cx += span;
            }
            float cy = bottom * (0.28f + (i % 2) * 0.3f);
            c.drawOval(cx - dp(60f), cy - dp(12f), cx + dp(60f), cy + dp(12f), paint);
            c.drawOval(cx - dp(28f), cy - dp(24f), cx + dp(30f), cy + dp(4f), paint);
        }
    }

    /** Reeds and grass along the near bank, scrolling faster than the far one. */
    private void drawNearShore(Canvas c, float w, float h, float top, float ppm) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2F5E33);
        c.drawRect(0, top, w, h, paint);
        paint.setColor(0xFF3F7A45);
        c.drawRect(0, top, w, top + dp(6f), paint);
        float gap = dp(22f);
        double scroll = sessionMeters * ppm * 1.2;
        float off = (float) (scroll % gap);
        paint.setStrokeWidth(dp(3f));
        for (float x = -off - gap; x < w + gap; x += gap) {
            int k = (int) Math.floor((x + scroll) / gap + 0.5);
            float tall = dp(16f) + (Math.abs(k * 7) % 4) * dp(6f);
            float sway = (float) Math.sin(sessionSeconds * 2 + k) * dp(3f);
            paint.setColor((k & 1) == 0 ? 0xFF4E8F4F : 0xFF6BAA5C);
            c.drawLine(x, top + dp(10f), x + sway, top + dp(10f) - tall, paint);
            if (Math.abs(k) % 5 == 2) {
                paint.setColor(0xFF7A4E2A);
                c.drawRoundRect(x + sway - dp(2.5f), top + dp(8f) - tall - dp(10f), x + sway + dp(2.5f),
                        top + dp(8f) - tall + dp(2f), dp(2f), dp(2f), paint);
            }
        }
    }

    /** Now and then a fish jumps across a lane, and a duck paddles by. */
    private void drawFish(Canvas c, float w, float top, float bottom) {
        if (sessionSeconds >= fishAt) {
            fishAt = sessionSeconds + 5 + Math.random() * 6;
            fishX = w * (0.4f + (float) Math.random() * 0.5f);
            fishY = top + (bottom - top) * (0.2f + (float) Math.random() * 0.6f);
        }
        double jump = 1 - (fishAt - sessionSeconds);
        paint.setStyle(Paint.Style.FILL);
        if (jump > 0 && jump < 1) {
            float px = fishX + (float) jump * dp(90f);
            float py = fishY - (float) Math.sin(jump * Math.PI) * dp(50f);
            paint.setColor(0xFFE8A04A);
            c.drawOval(px - dp(12f), py - dp(5f), px + dp(12f), py + dp(5f), paint);
            shape.rewind();
            shape.moveTo(px - dp(10f), py);
            shape.lineTo(px - dp(20f), py - dp(7f));
            shape.lineTo(px - dp(20f), py + dp(7f));
            shape.close();
            c.drawPath(shape, paint);
            if (jump < 0.1 || jump > 0.9) {
                fx.burst(px, fishY, 3, dp(60f), 0.4f, dp(2f), 0xCCDDF2FF, true);
            }
        }
        // A duck drifting with the current, so the water always has something on it.
        float span = w + dp(200f);
        float dx = (float) (w - ((sessionMeters * w / 60f * 0.9 + sessionSeconds * dp(10f)) % span));
        float dy = top + (bottom - top) * 0.62f + (float) Math.sin(sessionSeconds * 2) * dp(2f);
        paint.setColor(0xFF6B4A2B);
        c.drawOval(dx - dp(14f), dy - dp(7f), dx + dp(12f), dy + dp(6f), paint);
        paint.setColor(0xFF2E7D4F);
        c.drawCircle(dx - dp(12f), dy - dp(10f), dp(6f), paint);
        paint.setColor(0xFFF0B132);
        c.drawRect(dx - dp(22f), dy - dp(11f), dx - dp(16f), dy - dp(8f), paint);
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
