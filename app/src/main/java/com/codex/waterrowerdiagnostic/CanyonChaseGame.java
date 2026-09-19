package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Canyon Chase: a pursuer on your tail through a twisting canyon, drawn in perspective.
 *
 * <p>The canyon snakes; a racing line runs down the middle of it. Scrape a wall and you lose
 * speed and the hunter closes. Bank angle comes from how fast you are crossing the canyon, so the
 * horizon rolls into every turn.
 *
 * <p>Steering (the rower's upgrade list): with the WitMotion handle sensor connected, you steer
 * by tilting the handle, and the bends push you toward their outside so you have to lean into
 * them. <b>That sensor is untested hardware</b> - see HandleSensor - so the pace fallback is what
 * actually gets played today, and it was reworked rather than left as an afterthought:
 * <ul>
 *   <li>The craft follows the canyon's bends by itself; pace moves you <i>across</i> the racing
 *   line, not across the world. Speed can change once a stroke, a bend comes every few seconds,
 *   so asking pace to follow bends was asking the impossible.</li>
 *   <li>The centre is your own pace (half the profile's typical, half the last 20 s), and the
 *   width is the profile's low-high spread, so "right" means a little above what you hold.</li>
 *   <li>Pace is averaged over ~1.2 s, so the coast between two strokes does not swing the craft.</li>
 *   <li>A position bar at the bottom shows where you are, the walls, and the fork spire.</li>
 * </ul>
 *
 * <p>Forks: every ~400 m a rock spire splits the canyon. LEFT is a squeezed shortcut that gains
 * 12 m on the hunter; RIGHT is the wide road. The side you are on when the spire starts is the
 * road you take, and inside, the spire is a wall like any other.
 *
 * <p>The light moves from afternoon to dusk along the run ({@link CanyonFlightGame.Daylight}).
 *
 * <p>Perspective is a simple pinhole: a point {@code z} metres ahead projects to
 * {@code scale = FOCAL / z}, so near segments are wide and far ones converge on the horizon.
 */
final class CanyonChaseGame extends GameView {

    private static final int SEGMENTS = 44;
    private static final float SEG_LEN = 7f;          // metres per drawn segment
    private static final float CANYON_HALF = 16f;     // metres from centreline to wall
    private static final float FOCAL = 26f;
    /** Metres from the wall at which the craft scrapes. */
    private static final float WALL_MARGIN = 3f;
    private static final float SPIRE = 3f;            // half-width of a fork's spire
    private static final float SQUEEZE = 2f;          // how far the shortcut's outer wall closes in
    private static final float FORK_LEN = 90f;
    private static final float SHORTCUT_GAIN = 12f;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final java.util.Random rng = new java.util.Random();
    // 3.19.5 scenery: mesas on the horizon, vultures, wall strata, boulders, dust behind the craft.
    private double dustClock;

    private boolean started;
    private boolean over;
    private double runStart;
    /** Session clock at the first stroke: the hunter speeds up with run time, not session time. */
    private double runStartSeconds;
    private double x;                 // metres travelled
    /** Metres from the racing line (not the world), smoothed. */
    private float lateral;
    private float lateralVel;
    private float bank;
    private double hunterGap = 55;    // metres behind
    private double scrapeUntil;
    private int scrapes;
    private int shortcuts;
    private double bestGap;
    /** Pace averaged over ~1.2 s, and over ~20 s. */
    private float smoothSpeed;
    private float recentSpeed;

    private double forkStart;
    private double forkEnd;
    /** 0 undecided, -1 shortcut (left), +1 wide road (right). */
    private int forkSide;

    private String popupText = "";
    private int popupColor = TEXT;
    private double popupUntil;
    private int lastPhase;

    private LinearGradient skyShader;
    private float skyT = -1f;
    private float skyHorizon;

    CanyonChaseGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        x = 0;
        lateral = 0f;
        lateralVel = 0f;
        bank = 0f;
        hunterGap = 55;
        scrapes = 0;
        shortcuts = 0;
        bestGap = 0;
        smoothSpeed = 0f;
        recentSpeed = (float) profile.typicalSpeed();
        rng.setSeed(31);
        forkStart = 260;
        forkEnd = forkStart + FORK_LEN;
        forkSide = 0;
        popupUntil = 0;
        lastPhase = 0;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
            runStart = sessionMeters;
            runStartSeconds = sessionSeconds;
            recentSpeed = Math.max(boat.value(), (float) profile.lowSpeed());
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && over) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    /** Canyon centreline at a given distance: two sines so the bends never repeat predictably. */
    private float centreAt(double metres) {
        return (float) (Math.sin(metres / 46.0) * 9.5 + Math.sin(metres / 17.0) * 3.2);
    }

    /** 0 outside the fork, rising to 1 over 12 m at each end, so the spire is a wedge. */
    private float forkRamp(double m) {
        if (m < forkStart || m > forkEnd) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, (float) Math.min(m - forkStart, forkEnd - m) / 12f));
    }

    /** Distance from the line to the left wall; the shortcut squeezes it. */
    private float leftWall(double m) {
        return CANYON_HALF - SQUEEZE * forkRamp(m);
    }

    /** The pace that puts you on the line: half the profile's typical, half what you have held lately. */
    private float paceCentre() {
        return 0.5f * (float) profile.typicalSpeed() + 0.5f * recentSpeed;
    }

    /** Pace that takes you from the line to a wall's edge. */
    private float paceSpread() {
        return (float) Math.max(0.3, (profile.highSpeed() - profile.lowSpeed()) / 2.0);
    }

    /**
     * Where you want to sit, in metres from the racing line.
     *
     * <p>With a handle tilt sensor this is where you steered, less the bend pushing you to its
     * outside. Without one it is pace against your own centre, following the bends for you.
     */
    private float lateralFor(float bendAhead) {
        if (hasSteering()) {
            return steering() * (CANYON_HALF - 2.5f) - bendAhead * 0.45f;
        }
        float f = (smoothSpeed - paceCentre()) / paceSpread();
        f = Math.max(-1.25f, Math.min(1.25f, f));
        // Pace is still building for the first ~40 m of a run (about ten strokes); steering eases
        // in over that stretch so a standing start does not throw the craft into the left wall.
        float warm = started ? Math.max(0f, Math.min(1f, (float) x / 40f)) : 0f;
        return f * (CANYON_HALF - 2.5f) * warm;
    }

    private void popup(String text, int color) {
        popupText = text;
        popupColor = color;
        popupUntil = sessionSeconds + 1.6;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();
        if (started && !over) {
            x = sessionMeters - runStart;
            recentSpeed += (speed - recentSpeed) * Math.min(1f, dt / 20f);
        }
        smoothSpeed += (speed - smoothSpeed) * Math.min(1f, dt / 1.2f);

        // Lateral: eased toward the target, so it feels like a craft, not a cursor.
        float canyonBend = (centreAt(x + 24) - centreAt(x)) * 0.9f;
        float targetLat = lateralFor(canyonBend);
        float prevLat = lateral;
        lateral += (targetLat - lateral) * Math.min(1f, (hasSteering() ? 6.0f : 2.0f) * dt);
        lateralVel = dt > 0 ? (lateral - prevLat) / dt : 0f;
        // Bank into the turn: your own drift plus the canyon bending under you.
        float targetBank = Math.max(-28f, Math.min(28f, lateralVel * 2.4f - canyonBend * 1.1f));
        bank += (targetBank - bank) * Math.min(1f, 4f * dt);

        float centre = centreAt(x);
        float offLine = lateral;
        float ramp = forkRamp(x);
        float spire = SPIRE * ramp;
        float leftLimit = -(leftWall(x) - WALL_MARGIN);
        float rightLimit = CANYON_HALF - WALL_MARGIN;
        boolean wallHit = offLine < leftLimit || offLine > rightLimit;
        boolean spireHit = forkSide != 0 && ramp > 0.05f
                && (forkSide < 0 ? offLine > -(spire + 1.5f) : offLine < spire + 1.5f);
        boolean scraping = wallHit || spireHit;
        float nearWall = Math.max(0f, 1f - Math.min(offLine - leftLimit, rightLimit - offLine) / 4f);

        if (started && !over) {
            // Fork: the side you are on when the spire begins is the road you take.
            if (forkSide == 0 && x >= forkStart) {
                forkSide = offLine < 0 ? -1 : 1;
                popup(forkSide < 0 ? "SHORTCUT  -  MIND THE WALLS" : "WIDE ROAD", forkSide < 0 ? 0xFFF5C518 : ACCENT);
            }
            if (forkSide != 0 && x > forkEnd) {
                if (forkSide < 0) {
                    hunterGap += SHORTCUT_GAIN;
                    shortcuts++;
                    popup("SHORTCUT  +" + Math.round(SHORTCUT_GAIN) + " m", 0xFFF5C518);
                    fx.burst(w / 2f, h * 0.7f, 26, dp(180f), 0.6f, dp(3f), 0xFFF5C518, false);
                } else {
                    popup("THROUGH THE WIDE ROAD", ACCENT);
                }
                forkStart = forkEnd + 300 + rng.nextFloat() * 200;
                forkEnd = forkStart + FORK_LEN;
                forkSide = 0;
            }
            if (scraping && sessionSeconds > scrapeUntil) {
                scrapeUntil = sessionSeconds + 0.6;
                scrapes++;
                hunterGap -= 7;
                shake.kick(dp(12f));
                // Knocked back off the rock, toward open canyon.
                float away = spireHit ? forkSide : (offLine > 0 ? -1 : 1);
                lateral += away * 2.5f;
                float side = spireHit ? -forkSide : Math.signum(offLine);
                fx.burst(w * 0.5f + side * w * (spireHit ? 0.08f : 0.34f), h * 0.62f, 22, dp(170f), 0.5f,
                        dp(3.5f), 0xFFD89A3A, false);
            }
            // The hunter holds a little under the rower's own low pace and creeps faster.
            float hunterSpeed = (float) (profile.lowSpeed() * 0.85) + (float) (sessionSeconds - runStartSeconds) / 240f;
            hunterGap += (speed - hunterSpeed) * dt;
            hunterGap = Math.min(90, hunterGap);
            bestGap = Math.max(bestGap, x);
            if (hunterGap <= 0) {
                over = true;
                bests.recordHighest("chase.distance", (float) x);
            }
        }
        shake.step(dt);
        fx.step(dt, 0f);

        float danger = (float) Math.max(0, 1 - hunterGap / 26.0);
        float horizon = h * 0.30f;
        float dayT = CanyonFlightGame.Daylight.progress(x);
        float dusk = CanyonFlightGame.Daylight.shade(dayT);
        int phase = CanyonFlightGame.Daylight.phaseIndex(dayT);
        if (phase != lastPhase) {
            if (started && phase > lastPhase) {
                popup(CanyonFlightGame.Daylight.phaseName(phase), 0xFFFFC27A);
            }
            lastPhase = phase;
        }

        c.save();
        c.translate(shake.dx, shake.dy);
        // Roll the world against the bank so turns feel banked rather than slid.
        c.rotate(-bank * 0.35f, w / 2f, h * 0.8f);

        // Sky, sun and haze: cached per step of the light, never per frame.
        if (skyShader == null || skyT != dayT || skyHorizon != horizon) {
            skyT = dayT;
            skyHorizon = horizon;
            skyShader = new LinearGradient(0, 0, 0, horizon, CanyonFlightGame.Daylight.top(dayT),
                    CanyonFlightGame.Daylight.horizon(dayT), Shader.TileMode.CLAMP);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(-w, -h, w * 2, horizon, paint);
        paint.setShader(null);
        drawSun(c, w, horizon, dayT);
        drawHorizonLife(c, w, horizon, dusk);

        // Canyon: walk segments from far to near so nearer geometry paints over farther.
        // Camera kept inside the walls. Drawing only: scraping and the hunter use the real offLine.
        float camLat = centre + Math.max(-(CANYON_HALF - 1.5f), Math.min(CANYON_HALF - 1.5f, offLine));
        int nightFloor = 0xFF1C1220;
        for (int i = SEGMENTS - 1; i >= 1; i--) {
            float zFar = i * SEG_LEN;
            float zNear = (i - 1) * SEG_LEN;
            // Sub-segment scroll so the world slides smoothly between whole segments.
            float frac = (float) ((x % SEG_LEN) / SEG_LEN);
            zFar -= frac * SEG_LEN;
            zNear -= frac * SEG_LEN;
            if (zNear < 1.2f) {
                zNear = 1.2f;
            }
            float sFar = FOCAL / zFar;
            float sNear = FOCAL / zNear;
            float yFar = horizon + sFar * h * 0.34f;
            float yNear = horizon + sNear * h * 0.34f;
            float cFar = centreAt(x + zFar) - camLat;
            float cNear = centreAt(x + zNear) - camLat;
            float k = w * 0.05f;

            float lFar = w / 2f + (cFar - leftWall(x + zFar)) * sFar * k;
            float rFar = w / 2f + (cFar + CANYON_HALF) * sFar * k;
            float lNear = w / 2f + (cNear - leftWall(x + zNear)) * sNear * k;
            float rNear = w / 2f + (cNear + CANYON_HALF) * sNear * k;

            float depth = i / (float) SEGMENTS;
            int floor = CanyonFlightGame.Daylight.blend(blend(0xFF6B4A2F, 0xFFC08A5A, depth), nightFloor, dusk);
            int wall = CanyonFlightGame.Daylight.blend(blend(0xFF3A2418, 0xFF8A5C3A, depth), nightFloor, dusk);

            // Floor quad.
            paint.setColor(i % 2 == 0 ? floor : blend(floor, 0xFF000000, 0.06f));
            path.reset();
            path.moveTo(lNear, yNear);
            path.lineTo(rNear, yNear);
            path.lineTo(rFar, yFar);
            path.lineTo(lFar, yFar);
            path.close();
            c.drawPath(path, paint);

            // Walls rising from the floor edges.
            float wallHNear = sNear * h * 0.9f;
            float wallHFar = sFar * h * 0.9f;
            paint.setColor(blend(wall, 0xFF000000, 0.18f));
            path.reset();
            path.moveTo(lNear, yNear);
            path.lineTo(lFar, yFar);
            path.lineTo(lFar, yFar - wallHFar);
            path.lineTo(lNear, yNear - wallHNear);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(wall);
            path.reset();
            path.moveTo(rNear, yNear);
            path.lineTo(rFar, yFar);
            path.lineTo(rFar, yFar - wallHFar);
            path.lineTo(rNear, yNear - wallHNear);
            path.close();
            c.drawPath(path, paint);

            float spN = SPIRE * forkRamp(x + zNear);
            float spF = SPIRE * forkRamp(x + zFar);
            // Racing line down the middle: dashes you can aim at. Not through a spire.
            if (i % 2 == 0 && spN <= 0f && spF <= 0f) {
                paint.setColor(0x99FFE28A);
                float mNear = w / 2f + cNear * sNear * k;
                float mFar = w / 2f + cFar * sFar * k;
                paint.setStrokeWidth(Math.max(1f, sNear * dp(7f)));
                c.drawLine(mNear, yNear, mFar, yFar, paint);
            }
            // Strata: a darker band part-way up each wall, so the walls read as rock.
            paint.setColor(0x33000000);
            float band = 0.45f;
            path.reset();
            path.moveTo(lNear, yNear - wallHNear * band);
            path.lineTo(lFar, yFar - wallHFar * band);
            path.lineTo(lFar, yFar - wallHFar * (band + 0.08f));
            path.lineTo(lNear, yNear - wallHNear * (band + 0.08f));
            path.close();
            c.drawPath(path, paint);
            path.reset();
            path.moveTo(rNear, yNear - wallHNear * band);
            path.lineTo(rFar, yFar - wallHFar * band);
            path.lineTo(rFar, yFar - wallHFar * (band + 0.08f));
            path.lineTo(rNear, yNear - wallHNear * (band + 0.08f));
            path.close();
            c.drawPath(path, paint);
            // Boulders near the walls, placed by segment so they stay put as you pass.
            int seg = (int) Math.floor((x + zNear) / SEG_LEN);
            if ((seg * 2654435761L & 7) < 3 && zNear > 2f) {
                boolean leftSide = ((seg * 40503) & 1) == 0;
                float along = leftSide ? -leftWall(x + zNear) + 3f : CANYON_HALF - 3f;
                float bx = w / 2f + (cNear + along) * sNear * k;
                float br = Math.max(dp(2f), sNear * dp(20f));
                paint.setColor(CanyonFlightGame.Daylight.blend(blend(0xFF4A3322, 0xFF9A7050, depth), nightFloor, dusk));
                c.drawOval(bx - br * 1.4f, yNear - br * 1.3f, bx + br * 1.4f, yNear + br * 0.2f, paint);
                paint.setColor(0x33FFFFFF);
                c.drawOval(bx - br * 0.9f, yNear - br * 1.15f, bx - br * 0.1f, yNear - br * 0.6f, paint);
            }
            // The fork's spire: a rock slab down the middle, drawn with its visible faces only.
            if (spN > 0.05f || spF > 0.05f) {
                float aLN = w / 2f + (cNear - spN) * sNear * k;
                float aRN = w / 2f + (cNear + spN) * sNear * k;
                float aLF = w / 2f + (cFar - spF) * sFar * k;
                float aRF = w / 2f + (cFar + spF) * sFar * k;
                float hN = wallHNear * 0.8f;
                float hF = wallHFar * 0.8f;
                if (cNear - spN > 0) {
                    paint.setColor(blend(wall, 0xFF000000, 0.3f));
                    path.reset();
                    path.moveTo(aLN, yNear);
                    path.lineTo(aLF, yFar);
                    path.lineTo(aLF, yFar - hF);
                    path.lineTo(aLN, yNear - hN);
                    path.close();
                    c.drawPath(path, paint);
                }
                if (cNear + spN < 0) {
                    paint.setColor(blend(wall, 0xFF000000, 0.1f));
                    path.reset();
                    path.moveTo(aRN, yNear);
                    path.lineTo(aRF, yFar);
                    path.lineTo(aRF, yFar - hF);
                    path.lineTo(aRN, yNear - hN);
                    path.close();
                    c.drawPath(path, paint);
                }
                paint.setColor(blend(wall, 0xFFFFD9A8, 0.25f * (1f - dusk)));
                path.reset();
                path.moveTo(aLN, yNear - hN);
                path.lineTo(aLF, yFar - hF);
                path.lineTo(aRF, yFar - hF);
                path.lineTo(aRN, yNear - hN);
                path.close();
                c.drawPath(path, paint);
            }
        }
        drawForkSigns(c, w, h, horizon, camLat);

        // Your craft near the bottom, banking.
        // Clamped: pinned against a wall the craft used to be drawn far off-screen (seen on the
        // emulator at surge speed), so you could not see what was scraping.
        float shipX = Math.max(w * 0.12f, Math.min(w * 0.88f,
                w / 2f + offLine * (FOCAL / 6f) * w * 0.05f * 0.35f));
        float shipY = h * 0.80f;
        c.save();
        c.rotate(bank, shipX, shipY);
        Fx.glow(c, shipX, shipY, dp(52f), 0x3535D0BA);
        paint.setColor(scraping ? BAD : ACCENT);
        path.reset();
        path.moveTo(shipX, shipY - dp(26f));
        path.lineTo(shipX - dp(34f), shipY + dp(14f));
        path.lineTo(shipX - dp(11f), shipY + dp(6f));
        path.lineTo(shipX, shipY + dp(16f));
        path.lineTo(shipX + dp(11f), shipY + dp(6f));
        path.lineTo(shipX + dp(34f), shipY + dp(14f));
        path.close();
        c.drawPath(path, paint);
        // Thrust, longer with speed.
        paint.setColor(0xFFFFD36A);
        float thrust = dp(8f) + speed * dp(9f);
        c.drawRect(shipX - dp(6f), shipY + dp(14f), shipX + dp(6f), shipY + dp(14f) + thrust, paint);
        paint.setColor(0xFFFF7A3D);
        c.drawRect(shipX - dp(3f), shipY + dp(14f), shipX + dp(3f), shipY + dp(14f) + thrust * 0.6f, paint);
        c.restore();
        // Dust thrown up behind the craft, more with speed.
        dustClock += dt;
        if (started && !over && speed > 1.5f && dustClock > 0.05) {
            dustClock = 0;
            for (int j = 0; j < 2; j++) {
                fx.spawn(shipX + (float) (Math.random() - 0.5) * dp(40f), shipY + dp(20f),
                        (float) (Math.random() - 0.5) * dp(80f), dp(40f) + speed * dp(20f),
                        0.7f, dp(4f) + (float) Math.random() * dp(4f), 0x88C89A6A, false);
            }
        }
        fx.draw(c);
        c.restore();

        // Close to a wall: its side of the screen warms, before the scrape.
        if (started && !over && nearWall > 0.05f) {
            boolean rightSide = rightLimit - offLine < offLine - leftLimit;
            Fx.glow(c, rightSide ? w : 0f, h * 0.6f, w * 0.3f, ((int) (nearWall * 8) * 16 << 24) | 0xF0655D);
        }
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        Fx.vignette(c, w, h, 0.3f + danger * 0.6f, danger > 0.15f ? 0x7A0A0A : 0x000000);

        // Rear-view mirror: the hunter, larger as it closes.
        float mw = dp(150f);
        float mh = dp(58f);
        float mx = w / 2f - mw / 2f;
        float my = dp(8f);
        paint.setColor(0xCC0A0E14);
        c.drawRoundRect(mx, my, mx + mw, my + mh, dp(6f), dp(6f), paint);
        paint.setColor(danger > 0.4f ? BAD : 0xFF2A3648);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        c.drawRoundRect(mx, my, mx + mw, my + mh, dp(6f), dp(6f), paint);
        paint.setStyle(Paint.Style.FILL);
        float hs = dp(6f) + (float) Math.max(0, (90 - hunterGap)) / 90f * dp(26f);
        float hx = mx + mw / 2f - offLine * dp(1.6f);
        float hy = my + mh * 0.62f;
        paint.setColor(blend(0xFF6B3A8A, 0xFFFF3B5C, danger));
        path.reset();
        path.moveTo(hx, hy - hs);
        path.lineTo(hx - hs * 1.3f, hy + hs * 0.6f);
        path.lineTo(hx, hy + hs * 0.2f);
        path.lineTo(hx + hs * 1.3f, hy + hs * 0.6f);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFFF5C518);
        c.drawCircle(hx - hs * 0.35f, hy - hs * 0.25f, Math.max(dp(1.5f), hs * 0.16f), paint);
        c.drawCircle(hx + hs * 0.35f, hy - hs * 0.25f, Math.max(dp(1.5f), hs * 0.16f), paint);
        label(c, "BEHIND YOU", mx + mw / 2f, my + dp(11f), 7.5f, FAINT, Paint.Align.CENTER);

        // HUD.
        String big;
        int col;
        if (!started) {
            big = "SOMETHING IS FOLLOWING";
            col = DIM;
        } else if (over) {
            big = "CAUGHT";
            col = BAD;
        } else {
            big = Math.round(hunterGap) + " m";
            col = hunterGap > 40 ? ACCENT : hunterGap > 18 ? WARN : BAD;
        }
        bold(c, big, w / 2f, my + mh + dp(26f), started && !over ? 34f : 20f, col, Paint.Align.CENTER);
        boolean sensor = hasSteering();
        double toFork = forkStart - x;
        String cap;
        int capCol;
        if (!started) {
            cap = sensor ? "take a stroke - tilt the handle to steer" : "take a stroke - your pace steers you across the canyon";
            capCol = FAINT;
        } else if (over) {
            cap = "ran " + Math.round(x) + " m  ·  " + scrapes + " scrapes  ·  " + shortcuts + " shortcuts  ·  tap to run again";
            capCol = BAD;
        } else if (scraping) {
            cap = spireHit ? "ON THE SPIRE" : "SCRAPING THE WALL";
            capCol = BAD;
        } else if (forkSide == 0 && toFork < 90) {
            cap = "FORK IN " + Math.max(0, Math.round(toFork)) + " m  ·  "
                    + (sensor ? "STEER LEFT FOR THE SHORTCUT, RIGHT FOR THE WIDE ROAD"
                    : "EASE OFF FOR THE SHORTCUT, PULL FOR THE WIDE ROAD");
            capCol = 0xFFF5C518;
        } else if (forkSide < 0) {
            cap = sensor ? "SHORTCUT - HOLD IT STRAIGHT" : "SHORTCUT - HOLD THIS EASY PACE";
            capCol = 0xFFF5C518;
        } else if (Math.abs(offLine) < 3f || forkSide > 0) {
            cap = forkSide > 0 ? "WIDE ROAD - KEEP RIGHT OF THE SPIRE" : "ON THE LINE";
            capCol = ACCENT;
        } else if (sensor) {
            cap = offLine > 0 ? "STEER LEFT" : "STEER RIGHT";
            capCol = FAINT;
        } else {
            cap = offLine > 0 ? "EASE OFF TO COME LEFT" : "PULL HARDER TO GO RIGHT";
            capCol = FAINT;
        }
        bold(c, cap, w / 2f, my + mh + dp(42f), 11f, capCol, Paint.Align.CENTER);
        if (sessionSeconds < popupUntil) {
            float rise = (float) (1.6 - (popupUntil - sessionSeconds)) * dp(24f);
            bold(c, popupText, w / 2f, h * 0.5f - rise, 24f, popupColor, Paint.Align.CENTER);
        }

        drawPositionBar(c, w, h, offLine, leftLimit, rightLimit, sensor);

        float fy = h - dp(12f);
        float col3 = w / 3f;
        stat(c, col3 * 0.5f, fy, Math.round(x) + " m", "RUN");
        stat(c, col3 * 1.5f, fy, String.valueOf(scrapes), "SCRAPES");
        stat(c, col3 * 2.5f, fy, bests.has("chase.distance")
                ? Math.round(bests.get("chase.distance", 0)) + " m" : "--", "BEST");
        label(c, CanyonFlightGame.Daylight.phaseName(phase), w - dp(14f), dp(22f), 8.5f, 0xFFFFC27A, Paint.Align.RIGHT);
    }

    /**
     * Where you are across the canyon, as a bar: walls, the spire when a fork is near, your
     * craft, and for pace steering the speed that would put you on the line.
     */
    private void drawPositionBar(Canvas c, float w, float h, float offLine, float leftLimit, float rightLimit,
                                 boolean sensor) {
        float bw = dp(300f);
        float bx = w / 2f - bw / 2f;
        float by = h - dp(58f);
        float span = CANYON_HALF * 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xAA0A0E14);
        c.drawRoundRect(bx - dp(6f), by - dp(9f), bx + bw + dp(6f), by + dp(9f), dp(6f), dp(6f), paint);
        // Safe band between the scrape limits.
        float sl = bx + (leftLimit + CANYON_HALF) / span * bw;
        float sr = bx + (rightLimit + CANYON_HALF) / span * bw;
        paint.setColor(0x4435D0BA);
        c.drawRect(sl, by - dp(4f), sr, by + dp(4f), paint);
        paint.setColor(0xFFD89A3A);
        c.drawRect(bx, by - dp(6f), sl, by + dp(6f), paint);
        c.drawRect(sr, by - dp(6f), bx + bw, by + dp(6f), paint);
        // The spire's middle, from 120 m before a fork to its end.
        if (forkSide != 0 || forkStart - x < 120) {
            float half = SPIRE + 1.5f;
            paint.setColor(forkSide == 0 ? 0xAAF5C518 : 0xFFD89A3A);
            c.drawRect(bx + (-half + CANYON_HALF) / span * bw, by - dp(6f),
                    bx + (half + CANYON_HALF) / span * bw, by + dp(6f), paint);
        }
        paint.setColor(0x88FFE28A);
        c.drawRect(w / 2f - dp(1f), by - dp(8f), w / 2f + dp(1f), by + dp(8f), paint);
        float px = bx + Math.max(0f, Math.min(1f, (offLine + CANYON_HALF) / span)) * bw;
        paint.setColor(TEXT);
        c.drawCircle(px, by, dp(6f), paint);
        paint.setColor(ACCENT);
        c.drawCircle(px, by, dp(3.5f), paint);
        String hint = sensor ? "HANDLE STEERING"
                : String.format(java.util.Locale.US, "PACE STEERING  ·  %.2f m/s holds the line", paceCentre());
        label(c, hint, w / 2f, by - dp(13f), 8f, FAINT, Paint.Align.CENTER);
    }

    /** Two signs at the mouth of the next fork, drawn in perspective as it approaches. */
    private void drawForkSigns(Canvas c, float w, float h, float horizon, float camLat) {
        if (forkSide != 0) {
            return;
        }
        float z = (float) (forkStart - x);
        if (z < 3f || z > SEGMENTS * SEG_LEN * 0.8f) {
            return;
        }
        float s = FOCAL / z;
        float k = w * 0.05f;
        float y = horizon + s * h * 0.34f - s * h * 0.35f;
        float cx = w / 2f + (centreAt(forkStart) - camLat) * s * k;
        float off = (SPIRE + 7f) * s * k;
        float size = Math.max(7f, Math.min(20f, s * 26f));
        float bwid = dp(size * 7f);
        float bht = dp(size * 1.9f);
        for (int side = -1; side <= 1; side += 2) {
            float sx = cx + side * off;
            paint.setColor(0xDD1A1016);
            c.drawRoundRect(sx - bwid / 2f, y - bht / 2f, sx + bwid / 2f, y + bht / 2f, dp(4f), dp(4f), paint);
            int col = side < 0 ? 0xFFF5C518 : ACCENT;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, dp(size * 0.12f)));
            paint.setColor(col);
            c.drawRoundRect(sx - bwid / 2f, y - bht / 2f, sx + bwid / 2f, y + bht / 2f, dp(4f), dp(4f), paint);
            paint.setStyle(Paint.Style.FILL);
            bold(c, side < 0 ? "< SHORTCUT +12m" : "WIDE ROAD >", sx, y + dp(size * 0.35f), size, col,
                    Paint.Align.CENTER);
        }
    }

    /** The sun sinks toward the rim with the run; stars come out after it sets. */
    private void drawSun(Canvas c, float w, float horizon, float t) {
        float stars = CanyonFlightGame.Daylight.stars(t);
        if (stars > 0f) {
            for (int i = 0; i < 26; i++) {
                float sx = ((i * 7919) % 1000) / 1000f * w;
                float sy = ((i * 104729) % 1000) / 1000f * horizon * 0.85f;
                float tw = 0.6f + 0.4f * (float) Math.sin(sessionSeconds * 2 + i);
                paint.setColor(((int) (stars * tw * 220) << 24) | 0xFFFFFF);
                c.drawCircle(sx, sy, dp(1.1f + (i % 3) * 0.5f), paint);
            }
        }
        float drop = CanyonFlightGame.Daylight.sunDrop(t);
        float r = dp(26f + 8f * Math.min(1f, t));
        float sunX = w * 0.62f;
        float sunY = horizon * (0.22f + 0.72f * Math.min(1f, drop)) + Math.max(0f, drop - 1f) * r * 4f;
        int sun = CanyonFlightGame.Daylight.sun(t);
        Fx.glow(c, sunX, Math.min(sunY, horizon), w * 0.3f, (sun & 0x00FFFFFF) | 0x66000000);
        c.save();
        c.clipRect(-w, -horizon * 2f, w * 2f, horizon);
        paint.setColor(sun);
        c.drawCircle(sunX, sunY, r, paint);
        c.restore();
    }

    /** Flat-topped mesas along the horizon and a pair of vultures circling above the canyon. */
    private void drawHorizonLife(Canvas c, float w, float horizon, float dusk) {
        float shift = (float) ((x * 0.4) % (w * 1.2));
        paint.setColor(CanyonFlightGame.Daylight.blend(0xFF6A4638, 0xFF241626, dusk));
        for (int k = -1; k < 5; k++) {
            float mx = k * w * 0.3f - shift * 0.3f;
            float mh = dp(28f) + ((k * 7 + 21) % 3) * dp(14f);
            float mw = dp(70f) + ((k * 5 + 15) % 3) * dp(30f);
            path.reset();
            path.moveTo(mx - mw, horizon + dp(2f));
            path.lineTo(mx - mw * 0.7f, horizon - mh);
            path.lineTo(mx + mw * 0.7f, horizon - mh);
            path.lineTo(mx + mw, horizon + dp(2f));
            path.close();
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setColor(0xCC1A1010);
        for (int v = 0; v < 2; v++) {
            double a = sessionSeconds * (0.6 + v * 0.25) + v * 2;
            float vx = w * (0.35f + v * 0.3f) + (float) Math.cos(a) * dp(60f);
            float vy = horizon * (0.45f + v * 0.12f) + (float) Math.sin(a) * dp(18f);
            float flap = (float) Math.sin(sessionSeconds * 3 + v) * dp(4f);
            c.drawLine(vx - dp(14f), vy - flap, vx, vy, paint);
            c.drawLine(vx, vy, vx + dp(14f), vy - flap, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private static int blend(int a, int b, float t) {
        return CanyonFlightGame.Daylight.blend(a, b, t);
    }

    private void stat(Canvas c, float px, float py, String value, String caption) {
        bold(c, value, px, py - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, px, py + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
