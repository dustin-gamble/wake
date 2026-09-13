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
 * <p>The canyon snakes; a racing line runs down the middle of it. Your lateral position is set by
 * your speed - pull harder and you swing right, ease off and you drift left - so holding the line
 * through a bend means finding and holding a specific pace. Scrape a wall and you lose speed and
 * the hunter closes. Bank angle comes from how fast you are crossing the canyon, so the horizon
 * rolls into every turn.
 *
 * <p>Perspective is a simple pinhole: a point {@code z} metres ahead projects to
 * {@code scale = FOCAL / z}, so near segments are wide and far ones converge on the horizon.
 */
final class CanyonChaseGame extends GameView {

    private static final float MIN_SPEED = 1.0f;
    private static final float MAX_SPEED = 4.8f;
    private static final int SEGMENTS = 44;
    private static final float SEG_LEN = 7f;          // metres per drawn segment
    private static final float CANYON_HALF = 16f;     // metres from centreline to wall
    private static final float FOCAL = 26f;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();

    private boolean started;
    private boolean over;
    private double runStart;
    private double x;                 // metres travelled
    private float lateral;            // metres from centreline, smoothed
    private float lateralVel;
    private float bank;
    private double hunterGap = 55;    // metres behind
    private double scrapeUntil;
    private int scrapes;
    private double bestGap;

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
        bestGap = 0;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
            runStart = sessionMeters;
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

    /**
     * Where you sit across the canyon.
     *
     * <p>With a handle tilt sensor this is simply where you steered, which is what the game was
     * always meant to be. Without one it falls back to speed - and that fallback was unplayable:
     * it mapped 1.0-4.8 m/s across the full width, while this machine is rowed at 3.0-4.2, so the
     * craft sat pinned against the right wall and the only way to turn left was to stop rowing.
     * The fallback band now spans the speeds actually produced.
     */
    private static final float FALLBACK_LO = 2.6f;
    private static final float FALLBACK_HI = 4.2f;

    private float lateralFor(float speed) {
        if (hasSteering()) {
            return steering() * (CANYON_HALF - 2.5f);
        }
        float f = Math.max(0f, Math.min(1f, (speed - FALLBACK_LO) / (FALLBACK_HI - FALLBACK_LO)));
        return (f - 0.5f) * 2f * (CANYON_HALF - 2.5f);
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
        }

        // Lateral: eased toward where speed says, so it feels like a craft, not a cursor.
        float targetLat = lateralFor(speed);
        float prevLat = lateral;
        lateral += (targetLat - lateral) * Math.min(1f, (hasSteering() ? 6.0f : 2.0f) * dt);
        lateralVel = dt > 0 ? (lateral - prevLat) / dt : 0f;
        // Bank into the turn: your own drift plus the canyon bending under you.
        float canyonBend = (centreAt(x + 24) - centreAt(x)) * 0.9f;
        float targetBank = Math.max(-28f, Math.min(28f, lateralVel * 2.4f - canyonBend * 1.1f));
        bank += (targetBank - bank) * Math.min(1f, 4f * dt);

        float centre = centreAt(x);
        float offLine = lateral - centre;
        boolean scraping = Math.abs(offLine) > CANYON_HALF - 3f;

        if (started && !over) {
            if (scraping && sessionSeconds > scrapeUntil) {
                scrapeUntil = sessionSeconds + 0.6;
                scrapes++;
                hunterGap -= 7;
                shake.kick(dp(12f));
                fx.burst(w * 0.5f + Math.signum(offLine) * w * 0.34f, h * 0.62f, 22, dp(170f), 0.5f,
                        dp(3.5f), 0xFFD89A3A, false);
            }
            // The hunter matches a fixed pace and creeps faster; clean fast rowing pulls away.
            float hunterSpeed = 2.55f + (float) (sessionSeconds - 0) / 240f;
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

        c.save();
        c.translate(shake.dx, shake.dy);
        // Roll the world against the bank so turns feel banked rather than slid.
        c.rotate(-bank * 0.35f, w / 2f, h * 0.8f);

        // Sky and haze.
        paint.setShader(new LinearGradient(0, 0, 0, horizon, 0xFF243E63, 0xFFE9A15C, Shader.TileMode.CLAMP));
        c.drawRect(-w, -h, w * 2, horizon, paint);
        paint.setShader(null);
        Fx.glow(c, w * 0.5f, horizon, w * 0.38f, 0x55FFD9A0);

        // Canyon: walk segments from far to near so nearer geometry paints over farther.
        float camLat = lateral;
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

            float lFar = w / 2f + (cFar - CANYON_HALF) * sFar * w * 0.05f;
            float rFar = w / 2f + (cFar + CANYON_HALF) * sFar * w * 0.05f;
            float lNear = w / 2f + (cNear - CANYON_HALF) * sNear * w * 0.05f;
            float rNear = w / 2f + (cNear + CANYON_HALF) * sNear * w * 0.05f;

            float depth = i / (float) SEGMENTS;
            int floor = blend(0xFF6B4A2F, 0xFFC08A5A, depth);
            int wall = blend(0xFF3A2418, 0xFF8A5C3A, depth);

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

            // Racing line down the middle: dashes you can aim at.
            if (i % 2 == 0) {
                paint.setColor(0x99FFE28A);
                float mNear = w / 2f + cNear * sNear * w * 0.05f;
                float mFar = w / 2f + cFar * sFar * w * 0.05f;
                paint.setStrokeWidth(Math.max(1f, sNear * dp(7f)));
                c.drawLine(mNear, yNear, mFar, yFar, paint);
            }
        }

        // Your craft near the bottom, banking.
        float shipX = w / 2f + offLine * (FOCAL / 6f) * w * 0.05f * 0.35f;
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
        fx.draw(c);
        c.restore();

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
        String cap;
        if (!started) {
            cap = "take a stroke - your speed steers you across the canyon";
        } else if (over) {
            cap = "ran " + Math.round(x) + " m  ·  " + scrapes + " scrapes  ·  tap to run again";
        } else if (scraping) {
            cap = "SCRAPING THE WALL";
        } else if (Math.abs(offLine) < 3f) {
            cap = "ON THE LINE";
        } else {
            cap = offLine > 0 ? "EASE OFF TO COME LEFT" : "PULL HARDER TO GO RIGHT";
        }
        bold(c, cap, w / 2f, my + mh + dp(42f), 11f,
                scraping ? BAD : Math.abs(offLine) < 3f ? ACCENT : FAINT, Paint.Align.CENTER);

        float fy = h - dp(12f);
        float col3 = w / 3f;
        stat(c, col3 * 0.5f, fy, Math.round(x) + " m", "RUN");
        stat(c, col3 * 1.5f, fy, String.valueOf(scrapes), "SCRAPES");
        stat(c, col3 * 2.5f, fy, bests.has("chase.distance")
                ? Math.round(bests.get("chase.distance", 0)) + " m" : "--", "BEST");
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void stat(Canvas c, float px, float py, String value, String caption) {
        bold(c, value, px, py - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, px, py + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
