package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;

/**
 * Canyon Flight: your boat speed is your altitude. Ring gates come at you at different heights;
 * fly through them or lose a life. Gates step up and down, so you are changing gear every hundred
 * metres - it is intervals wearing a wingsuit.
 *
 * <p>Made easier in 3.14.0 after "too hard to get inside the ring given our method of capturing
 * row": altitude follows a rolling average of speed with a small dead zone, so one weak stroke no
 * longer drops you; rings are 60% taller and pull you toward their centre in the last 30 m; the
 * altitude scale spans the rower's own range; and each ring is placed around the speed you have
 * actually been holding, rather than on a fixed random walk.
 *
 * <p>3.19.5, from the emulator: the sunset sky was being drawn at the alpha of whatever colour the
 * HUD set last (a lost-life dot is 20% white), so the whole game looked dark grey. Also a proper
 * little plane that pitches with the climb, a sun, drifting clouds, a flock of birds, rings that
 * look like rings (front and back halves around the plane, a lit core), "+1" popups and a streak
 * counter that glows.
 *
 * <p>Canyon upgrades (the rower's list):
 * <ul>
 *   <li><b>Pickups in the rings.</b> Every ring carries a gem at its heart (+2), every sixth a heart
 *   (a life back, or +2 at full health). Take it by going through the middle. Clipping the outer
 *   edge of a ring and still getting through is a <b>close shave</b>, +2.</li>
 *   <li><b>The canyon narrows.</b> A rock roof comes down and the floor comes up over the first
 *   ~2 km of a run, closing to the rower's own low-to-high speed band (from {@link RowerProfile}).
 *   Touching rock throws sparks; staying on it for most of a second costs a life. So both easing
 *   off and overcooking it have a price.</li>
 *   <li><b>Forks.</b> Every ~600 m a rock spire splits the canyon. Be above its middle when you
 *   reach it and you take the HIGH ROAD (harder pace, two star rings worth +5 each); below it and
 *   you take the LOW ROAD (easy pace, a gem and a heart). Once in, the spire is rock like the rest.</li>
 *   <li><b>The light moves toward sunset</b> with distance flown: afternoon, golden hour, sunset,
 *   dusk with stars. {@link Daylight} is shared with DRIVE.</li>
 * </ul>
 */
final class CanyonFlightGame extends GameView {

    private static final int LIVES = 3;
    /** Half-height of a ring as a share of the sky: was 0.10. */
    private static final float RING_BAND = 0.16f;
    /** Within this many metres of a ring, altitude is pulled toward its centre. */
    private static final float MAGNET_METRES = 30f;
    /** Metres of run over which the canyon closes to its narrowest. */
    private static final float NARROW_START = 150f;
    private static final float NARROW_SPAN = 1800f;
    private static final float FORK_LEN = 170f;
    /** Seconds of continuous rock contact that costs a life. */
    private static final float SCRAPE_LIMIT = 0.9f;

    private static final int NONE = -1;
    private static final int GEM = 0;
    private static final int HEART = 1;
    private static final int STAR = 2;

    private static final class Fork {
        final float start;
        final float end;
        /** -1 undecided, 0 low road, 1 high road. */
        int chosen = -1;

        Fork(float start, float end) {
            this.start = start;
            this.end = end;
        }
    }

    private static final class Gate {
        final float at;        // metres
        final float speed;     // centre of the band
        final int pickup;
        final Fork fork;
        final int branch;      // -1 not in a fork, 0 low road, 1 high road
        boolean resolved;
        boolean passed;
        boolean collected;
        boolean skipped;       // on the road not taken

        Gate(float at, float speed, int pickup, Fork fork, int branch) {
            this.at = at;
            this.speed = speed;
            this.pickup = pickup;
            this.fork = fork;
            this.branch = branch;
        }
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final java.util.List<Gate> gates = new java.util.ArrayList<>();
    private final java.util.List<Fork> forks = new java.util.ArrayList<>();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();

    private boolean started;
    private boolean over;
    private int lives;
    private int passed;
    private int score;
    private int gems;
    private int shaves;
    private int ringCount;
    private double runStart;
    private double x;
    private float altitude;      // 0..1
    private double hurtUntil;
    private float minSpeed = 1.2f;
    private float maxSpeed = 4.6f;
    /** Speed the altitude follows: a rolling average with a dead zone. */
    private float flightSpeed;
    /** What the rower has been holding lately, for placing the next ring. */
    private float recentSpeed;
    private float nextGateAt;
    private float nextForkAt;
    private final java.util.Random rng = new java.util.Random();
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;
    private float skyT = -1f;
    private int streak;
    private double popupUntil;
    private float popupY;
    private String popupText = "+1";
    private int popupColor = 0xFFF5C518;
    private double bannerUntil;
    private String bannerText = "";
    private int bannerColor = TEXT;
    private int lastPhase;
    private float lastYouY;
    private float pitch;
    private float scrapeTime;
    /** Which way the rock being touched is: true roof or spire above, false floor or spire below. */
    private boolean scrapeHigh;
    private double graceUntil;
    private double sparkClock;
    /** Screen rows of the sky, set each frame. */
    private float skyTopY;
    private float skyBottomY;
    /** Output of {@link #corridor}: walls in speed units at one distance. */
    private float cFloor;
    private float cCeil;
    private float cRockLo;
    private float cRockHi;
    private Fork cFork;

    CanyonFlightGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        streak = 0;
        lives = LIVES;
        passed = 0;
        score = 0;
        gems = 0;
        shaves = 0;
        ringCount = 0;
        x = 0;
        altitude = 0f;
        scrapeTime = 0f;
        graceUntil = 0;
        lastPhase = 0;
        bannerUntil = 0;
        gates.clear();
        forks.clear();
        // The altitude scale covers this rower: from well below their low speed to a little above
        // their high, so a change in effort is a visible change in height.
        minSpeed = (float) Math.max(0.8, profile.lowSpeed() - 1.2);
        maxSpeed = (float) profile.highSpeed() + 0.6f;
        flightSpeed = 0f;
        recentSpeed = (float) profile.typicalSpeed();
        nextGateAt = 80f;
        nextForkAt = 380f;
        rng.setSeed(77);
    }

    /* ---------- the canyon's shape ---------- */

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    /**
     * The flyable corridor at {@code m} metres, in speed units (so it is tuned to this rower): the
     * roof and floor close from off-screen to the rower's own low/high band, and inside a fork a
     * spire sits across the middle. Writes {@link #cFloor}, {@link #cCeil}, {@link #cRockLo},
     * {@link #cRockHi} and {@link #cFork} - fields, so the per-column drawing allocates nothing.
     */
    private void corridor(double m) {
        float narrow = clamp01((float) (m - NARROW_START) / NARROW_SPAN);
        float lo = (float) profile.lowSpeed() - 0.3f;
        float hi = (float) profile.highSpeed() + 0.3f;
        cFloor = (minSpeed - 0.6f) + (lo - (minSpeed - 0.6f)) * narrow;
        cCeil = (maxSpeed + 0.6f) + (hi - (maxSpeed + 0.6f)) * narrow;
        cFork = null;
        cRockLo = 0f;
        cRockHi = 0f;
        for (int i = 0; i < forks.size(); i++) {
            Fork f = forks.get(i);
            if (m >= f.start && m <= f.end) {
                cFork = f;
                float ramp = clamp01((float) Math.min(m - f.start, f.end - m) / 25f);
                float vlo = Math.max(cFloor, minSpeed);
                float vhi = Math.min(cCeil, maxSpeed);
                float mid = (vlo + vhi) / 2f;
                float half = (vhi - vlo) * 0.13f * ramp;
                cRockLo = mid - half;
                cRockHi = mid + half;
                break;
            }
        }
    }

    /** Screen y of a speed, NOT clamped to the sky, so rock can sit partly off-screen. */
    private float yOf(float speed) {
        return skyBottomY - (skyBottomY - skyTopY) * (speed - minSpeed) / (maxSpeed - minSpeed);
    }

    /** Rings are made just ahead of the rower, around the speed they have been holding. */
    private void spawnGates() {
        float low = (float) profile.lowSpeed() - 0.4f;
        float high = (float) profile.highSpeed() + 0.2f;
        while (nextGateAt < x + 200f) {
            if (nextGateAt + 40f >= nextForkAt) {
                // A fork: two rings on each road, placed in the middle of each branch.
                Fork f = new Fork(nextForkAt, nextForkAt + FORK_LEN);
                forks.add(f);
                for (int k = 0; k < 2; k++) {
                    float at = f.start + (k == 0 ? 60f : 125f);
                    corridor(at);
                    float upper = (cRockHi + Math.min(cCeil, maxSpeed)) / 2f;
                    float lower = (Math.max(cFloor, minSpeed) + cRockLo) / 2f;
                    gates.add(new Gate(at, upper, STAR, f, 1));
                    gates.add(new Gate(at, lower, k == 0 ? GEM : HEART, f, 0));
                }
                nextGateAt = f.end + 60f + rng.nextFloat() * 40f;
                nextForkAt = nextGateAt + 420f + rng.nextFloat() * 200f;
                continue;
            }
            float centre = recentSpeed + ((float) profile.typicalSpeed() - recentSpeed) * 0.1f;
            float target = centre + (rng.nextFloat() - 0.5f) * 0.8f;
            target = Math.max(Math.max(minSpeed + 0.3f, low), Math.min(Math.min(maxSpeed - 0.3f, high), target));
            corridor(nextGateAt);
            float cLo = cFloor + 0.3f;
            float cHi = cCeil - 0.3f;
            if (cLo < cHi) {
                target = Math.max(cLo, Math.min(cHi, target));
            }
            ringCount++;
            gates.add(new Gate(nextGateAt, target, ringCount % 6 == 0 ? HEART : GEM, null, -1));
            nextGateAt += 90f + rng.nextFloat() * 60f;
        }
        // Forget rings well behind, so the list does not grow for a whole session.
        while (gates.size() > 12 && gates.get(0).resolved && gates.get(0).at < x - 80f) {
            gates.remove(0);
        }
        while (!forks.isEmpty() && forks.get(0).end < x - 80f) {
            forks.remove(0);
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
            runStart = sessionMeters;
            // Rings start from the speed actually being rowed, not the profile's typical: on the tablet
            // the first ring sat at 4.0 m/s against a 2.6 m/s row.
            recentSpeed = Math.max(boat.value(), (float) profile.lowSpeed() - 0.3f);
            gates.clear();
            forks.clear();
            ringCount = 0;
            nextGateAt = (float) (sessionMeters - runStart) + 80f;
            nextForkAt = nextGateAt + 300f;
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

    private void popup(String text, int color, float y) {
        popupText = text;
        popupColor = color;
        popupY = y;
        popupUntil = sessionSeconds + 1.1;
    }

    private void banner(String text, int color) {
        bannerText = text;
        bannerColor = color;
        bannerUntil = sessionSeconds + 2.4;
    }

    private void loseLife(float youX, float youY) {
        lives--;
        streak = 0;
        hurtUntil = sessionSeconds + 1.2;
        shake.kick(dp(10f));
        fx.burst(youX, youY, 24, dp(160f), 0.6f, dp(3.5f), 0xFFF0655D, false);
        if (lives <= 0) {
            over = true;
            bests.recordHighest("canyon.gates", passed);
            bests.recordHighest("canyon.score", score);
        }
    }

    /* ---------- drawing ---------- */

    /** A spinning pickup at the heart of a ring. */
    private void drawPickup(Canvas c, float px, float py, int kind, float phase) {
        float s = dp(1f);
        float spin = Math.max(0.25f, Math.abs((float) Math.cos(sessionSeconds * 2.6 + phase)));
        float bob = (float) Math.sin(sessionSeconds * 3 + phase) * dp(3f);
        py += bob;
        paint.setStyle(Paint.Style.FILL);
        if (kind == GEM) {
            Fx.glow(c, px, py, dp(28f), 0x6635D0BA);
            path.reset();
            path.moveTo(px, py - 13 * s);
            path.lineTo(px + 10 * s * spin, py - 3 * s);
            path.lineTo(px, py + 13 * s);
            path.lineTo(px - 10 * s * spin, py - 3 * s);
            path.close();
            paint.setColor(0xFF4FE3D0);
            c.drawPath(path, paint);
            paint.setColor(0xAAFFFFFF);
            c.drawRect(px - 2 * s * spin, py - 9 * s, px + 2 * s * spin, py - 2 * s, paint);
        } else if (kind == HEART) {
            Fx.glow(c, px, py, dp(28f), 0x66F0655D);
            float r = 6 * s;
            float sx = spin;
            paint.setColor(0xFFF0655D);
            c.drawCircle(px - r * 0.9f * sx, py - r * 0.4f, r, paint);
            c.drawCircle(px + r * 0.9f * sx, py - r * 0.4f, r, paint);
            path.reset();
            path.moveTo(px - r * 1.85f * sx, py - r * 0.1f);
            path.lineTo(px + r * 1.85f * sx, py - r * 0.1f);
            path.lineTo(px, py + r * 2.1f);
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0x88FFFFFF);
            c.drawCircle(px - r * 1.1f * sx, py - r * 0.7f, r * 0.3f, paint);
        } else if (kind == STAR) {
            Fx.glow(c, px, py, dp(34f), 0x77F5C518);
            path.reset();
            float outer = 15 * s;
            float inner = 6.5f * s;
            for (int k = 0; k < 10; k++) {
                double a = -Math.PI / 2 + k * Math.PI / 5;
                float rr = (k % 2 == 0) ? outer : inner;
                float vx = px + (float) Math.cos(a) * rr * spin;
                float vy = py + (float) Math.sin(a) * rr;
                if (k == 0) {
                    path.moveTo(vx, vy);
                } else {
                    path.lineTo(vx, vy);
                }
            }
            path.close();
            paint.setColor(0xFFF5C518);
            c.drawPath(path, paint);
            paint.setColor(0xAAFFFFFF);
            c.drawCircle(px - 3 * s * spin, py - 3 * s, 2.5f * s, paint);
        }
    }

    /** Half of every ring on screen: back halves before the plane, front halves after it. */
    private void drawGates(Canvas c, float w, float youX, float ppm, float band, boolean front) {
        for (Gate g : gates) {
            float gx = youX + (g.at - (float) x) * ppm;
            if (gx < -dp(60f) || gx > w + dp(60f)) {
                continue;
            }
            float gy = skyBottomY - (skyBottomY - skyTopY) * altFor(g.speed);
            int col = g.skipped ? 0x44FFFFFF
                    : g.resolved ? (g.passed ? ACCENT : BAD)
                    : g.pickup == STAR ? 0xFFFFB02E : 0xFFF5C518;
            float rx = dp(22f);
            if (!front) {
                if (!g.resolved) {
                    Fx.glow(c, gx, gy, band * 1.4f, 0x55F5C518);
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(7f));
                paint.setColor((col & 0x00FFFFFF) | (g.skipped ? 0x22000000 : 0x99000000));
                c.drawArc(gx - rx, gy - band, gx + rx, gy + band, 90, 180, false, paint);
                paint.setStyle(Paint.Style.FILL);
                // The prize sits inside the ring, between its back and front halves.
                if (!g.collected && !g.skipped && g.pickup != NONE) {
                    drawPickup(c, gx, gy, g.pickup, g.at);
                }
            } else {
                if (g.skipped) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(6f));
                    paint.setColor(col);
                    c.drawArc(gx - rx, gy - band, gx + rx, gy + band, -90, 180, false, paint);
                    paint.setStyle(Paint.Style.FILL);
                    continue;
                }
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(8f));
                paint.setColor(col);
                c.drawArc(gx - rx, gy - band, gx + rx, gy + band, -90, 180, false, paint);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(0xCCFFFFFF);
                c.drawArc(gx - rx + dp(3f), gy - band + dp(3f), gx + rx - dp(3f), gy + band - dp(3f), -80, 160, false, paint);
                paint.setStyle(Paint.Style.FILL);
                // Pennants top and bottom.
                paint.setColor(col);
                c.drawRect(gx - dp(1.5f), gy - band - dp(22f), gx + dp(1.5f), gy - band, paint);
                float flap = (float) Math.sin(sessionSeconds * 6 + g.at) * dp(3f);
                path.reset();
                path.moveTo(gx + dp(1.5f), gy - band - dp(22f));
                path.lineTo(gx + dp(20f), gy - band - dp(17f) + flap);
                path.lineTo(gx + dp(1.5f), gy - band - dp(12f));
                path.close();
                c.drawPath(path, paint);
                if (!g.resolved) {
                    bold(c, String.format(java.util.Locale.US, "%.1f m/s", g.speed), gx, gy - band - dp(28f),
                            11f, TEXT, Paint.Align.CENTER);
                }
            }
        }
    }

    /**
     * The narrowing canyon: a rock roof and a rock floor, following {@link #corridor}, jagged by
     * a hash of world position so the rock stays put as you fly past it.
     */
    private void drawCorridor(Canvas c, float w, float h, float youX, float ppm, float dusk) {
        float stepPx = dp(36f);
        float stepM = stepPx / ppm;
        double left = x - youX / ppm - stepM;
        double right = x + (w - youX) / ppm + stepM;
        long k0 = (long) Math.floor(left / stepM);
        int rock = Daylight.blend(0xFF3A2026, 0xFF140A14, dusk);
        int rim = (int) (0x55 * (1f - dusk * 0.7f)) << 24 | 0xFFD9A8;
        for (int side = 0; side < 2; side++) {
            path.reset();
            float edgeY = side == 0 ? -dp(40f) : h + dp(40f);
            path.moveTo(-dp(40f), edgeY);
            boolean visible = false;
            for (long k = k0; k * stepM <= right; k++) {
                double m = k * stepM;
                corridor(m);
                float px = youX + (float) (m - x) * ppm;
                int hash = (int) ((k * 2654435761L) >>> 7) & 7;
                float jag = hash * dp(2f);
                float py = side == 0 ? yOf(cCeil) + jag : yOf(cFloor) - jag;
                if (side == 0 ? py > 0 : py < h) {
                    visible = true;
                }
                path.lineTo(px, py);
            }
            path.lineTo(w + dp(40f), edgeY);
            path.close();
            if (!visible) {
                continue;
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(rock);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(rim);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /** The spire that splits a fork, and the two road signs at its mouth. */
    private void drawForks(Canvas c, float w, float youX, float ppm, float dusk) {
        double left = x - youX / ppm;
        double right = x + (w - youX) / ppm;
        int rock = Daylight.blend(0xFF452630, 0xFF1A0E18, dusk);
        for (int i = 0; i < forks.size(); i++) {
            Fork f = forks.get(i);
            if (f.end < left || f.start > right + 40) {
                continue;
            }
            // Sample points snapped to a world grid, so the jag pattern stays on the rock instead
            // of crawling with the camera.
            double step = 3.0;
            double a = Math.max(f.start, Math.floor((left - 4) / step) * step);
            double b = Math.min(f.end, Math.ceil((right + 4) / step) * step);
            if (b > a) {
                path.reset();
                // Top edge forward, bottom edge back.
                boolean first = true;
                for (double m = a; m <= b + 0.001; m += step) {
                    corridor(m);
                    int hash = (int) ((Math.round(m / step) * 40503L) >>> 3) & 3;
                    float px = youX + (float) (m - x) * ppm;
                    float py = yOf(cRockHi) - hash * dp(2f);
                    if (first) {
                        path.moveTo(px, py);
                        first = false;
                    } else {
                        path.lineTo(px, py);
                    }
                }
                for (double m = b; m >= a - 0.001; m -= step) {
                    corridor(m);
                    int hash = (int) ((Math.round(m / step) * 7919L) >>> 3) & 3;
                    path.lineTo(youX + (float) (m - x) * ppm, yOf(cRockLo) + hash * dp(2f));
                }
                path.close();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(rock);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(0x55FFD9A8);
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if (f.chosen < 0) {
                // Signs at the fork mouth, one per road.
                float sx = youX + (f.start - 6f - (float) x) * ppm;
                corridor(f.start + 60f);
                float upY = yOf((cRockHi + Math.min(cCeil, maxSpeed)) / 2f);
                float lowY = yOf((Math.max(cFloor, minSpeed) + cRockLo) / 2f);
                float midY = yOf((cRockLo + cRockHi) / 2f);
                paint.setColor(0xFF5A3A28);
                c.drawRect(sx - dp(2f), upY, sx + dp(2f), lowY, paint);
                signBoard(c, sx, upY, "HIGH ROAD", "2 STARS  +5 each", 0xFFF5C518);
                signBoard(c, sx, lowY, "LOW ROAD", "easy  ·  gem + heart", ACCENT);
                paint.setColor(0xCCF5C518);
                c.drawCircle(sx, midY, dp(4f), paint);
            }
        }
    }

    private void signBoard(Canvas c, float sx, float sy, String title, String sub, int col) {
        float bw = dp(128f);
        float bh = dp(36f);
        paint.setColor(0xDD1A1016);
        c.drawRoundRect(sx - bw / 2f, sy - bh / 2f, sx + bw / 2f, sy + bh / 2f, dp(5f), dp(5f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(col);
        c.drawRoundRect(sx - bw / 2f, sy - bh / 2f, sx + bw / 2f, sy + bh / 2f, dp(5f), dp(5f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, title, sx, sy - dp(2f), 12f, col, Paint.Align.CENTER);
        label(c, sub, sx, sy + dp(12f), 8.5f, TEXT, Paint.Align.CENTER);
    }

    /** A small high-wing plane pointing right, pitched with the climb, prop spinning. */
    private void drawPlane(Canvas c, float px, float py, float speed) {
        c.save();
        c.rotate((float) Math.toDegrees(-pitch * 0.6f), px, py);
        float s = dp(1f);
        paint.setStyle(Paint.Style.FILL);
        // Fuselage.
        paint.setColor(0xFFF4F4F4);
        c.drawRoundRect(px - 34 * s, py - 7 * s, px + 30 * s, py + 7 * s, 7 * s, 7 * s, paint);
        paint.setColor(ACCENT);
        c.drawRect(px - 30 * s, py - 1.5f * s, px + 26 * s, py + 2.5f * s, paint);
        // Tail.
        path.reset();
        path.moveTo(px - 30 * s, py - 5 * s);
        path.lineTo(px - 42 * s, py - 22 * s);
        path.lineTo(px - 24 * s, py - 6 * s);
        path.close();
        c.drawPath(path, paint);
        // Wing (seen edge-on, slightly below the top) and strut.
        paint.setColor(0xFFDDE6EE);
        c.drawRoundRect(px - 12 * s, py - 12 * s, px + 14 * s, py - 6 * s, 3 * s, 3 * s, paint);
        // Cockpit.
        paint.setColor(0xFF6FB8E8);
        c.drawRoundRect(px + 8 * s, py - 6 * s, px + 20 * s, py - 1 * s, 3 * s, 3 * s, paint);
        // Propeller blur.
        paint.setColor(0x88FFFFFF);
        float blade = (float) Math.abs(Math.sin(sessionSeconds * (20 + speed * 10))) * 14 * s + 4 * s;
        c.drawOval(px + 30 * s, py - blade, px + 35 * s, py + blade, paint);
        paint.setColor(0xFF333333);
        c.drawCircle(px + 32 * s, py, 2.5f * s, paint);
        c.restore();
    }

    /** Sun (sinking with the route), stars at dusk, drifting clouds and a flock of birds. */
    private void drawSkyLife(Canvas c, float w, float h, float ppm, float t) {
        float stars = Daylight.stars(t);
        if (stars > 0f) {
            for (int i = 0; i < 40; i++) {
                float sx = ((i * 7919) % 1000) / 1000f * w;
                float sy = ((i * 104729) % 1000) / 1000f * h * 0.5f;
                float tw = 0.6f + 0.4f * (float) Math.sin(sessionSeconds * 2 + i);
                paint.setColor(((int) (stars * tw * 220) << 24) | 0xFFFFFF);
                c.drawCircle(sx, sy, dp(1.1f + (i % 3) * 0.5f), paint);
            }
        }
        float sunY = h * (0.2f + 0.52f * Daylight.sunDrop(t));
        int sun = Daylight.sun(t);
        Fx.glow(c, w * 0.78f, sunY, dp(160f), (sun & 0x00FFFFFF) | 0x88000000);
        paint.setColor(sun);
        c.drawCircle(w * 0.78f, sunY, dp(40f + 10f * Math.min(1f, t)), paint);
        int cloud = Daylight.blend(0xFFFFFFFF, 0xFFFF9A7A, Math.min(1f, t));
        paint.setColor((cloud & 0x00FFFFFF) | 0xB3000000);
        for (int i = 0; i < 6; i++) {
            float span = w + dp(400f);
            float cx = (float) (((i * 377 + 90) - x * ppm * (0.05 + i * 0.02)) % span);
            if (cx < -dp(200f)) {
                cx += span;
            }
            float cy = h * (0.12f + (i % 3) * 0.1f);
            float sc = 0.7f + (i % 3) * 0.25f;
            c.drawOval(cx - dp(70f) * sc, cy - dp(12f) * sc, cx + dp(70f) * sc, cy + dp(12f) * sc, paint);
            c.drawOval(cx - dp(34f) * sc, cy - dp(26f) * sc, cx + dp(36f) * sc, cy + dp(4f) * sc, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xCC2A1A2A);
        float flockX = (float) (w - ((x * ppm * 0.3 + sessionSeconds * dp(30f)) % (w + dp(300f))));
        for (int b = 0; b < 5; b++) {
            float bx = flockX + b * dp(26f);
            float by = h * 0.28f + (b % 2) * dp(12f) + (float) Math.sin(sessionSeconds * 1.3 + b) * dp(4f);
            float flap = (float) Math.sin(sessionSeconds * 9 + b) * dp(5f);
            c.drawLine(bx - dp(8f), by - flap, bx, by, paint);
            c.drawLine(bx, by, bx + dp(8f), by - flap, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * Mesas between the far wall and the near one. Flat-topped on purpose: the walls either side
     * are jagged, so repeating that shape a third time reads as one wall, not as distance. Hazed
     * toward the sky colour and scrolled at 0.4 - between the far 0.25 and the near 0.6.
     */
    private void drawMesas(Canvas c, float w, float h, float ppm, float dusk) {
        float step = dp(210f);
        float off = (float) ((x * ppm * 0.4) % step);
        int base = (int) ((x * ppm * 0.4) / step);
        int mesaA = Daylight.blend(0xFF3E2329, 0xFF1E1226, dusk);
        int mesaB = Daylight.blend(0xFF452830, 0xFF22142A, dusk);
        int rim = ((int) (0x33 * (1f - dusk * 0.8f)) << 24) | 0xFFD9A8;
        for (int k = -1; k * step - off <= w + step; k++) {
            float px = k * step - off;
            int seed = base + k;
            float top = h * (0.44f + ((seed * 6421) & 3) * 0.05f);
            float wide = step * (0.46f + ((seed * 3571) & 3) * 0.1f);
            paint.setColor(((seed & 1) == 0) ? mesaA : mesaB);
            path.reset();
            path.moveTo(px, h);
            path.lineTo(px + step * 0.1f, top);
            path.lineTo(px + wide, top);
            path.lineTo(px + wide + step * 0.13f, h);
            path.close();
            c.drawPath(path, paint);
            // A lit rim on the sunward side, so the flat top is not a silhouette slab.
            paint.setColor(rim);
            c.drawRect(px + step * 0.1f, top, px + wide, top + dp(3f), paint);
        }
    }

    private float altFor(float speed) {
        return Math.max(0f, Math.min(1f, (speed - minSpeed) / (maxSpeed - minSpeed)));
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
        skyTopY = h * 0.10f;
        skyBottomY = h * 0.86f;
        spawnGates();
        // Altitude follows a rolling average of speed (about 1.5 s) and ignores changes under
        // 0.1 m/s, so the dip between two strokes no longer sends the wing through the floor.
        float averaged = flightSpeed + (speed - flightSpeed) * Math.min(1f, dt / 1.5f);
        if (Math.abs(averaged - flightSpeed) > 0.1f * dt || flightSpeed == 0f) {
            flightSpeed = averaged;
        }
        if (started && !over) {
            recentSpeed += (speed - recentSpeed) * Math.min(1f, dt / 8f);
        }
        altitude += (altFor(flightSpeed) - altitude) * Math.min(1f, 2.2f * dt);
        shake.step(dt);
        fx.step(dt, 0f);

        float skyTop = skyTopY;
        float skyBottom = skyBottomY;
        float youX = w * 0.28f;
        float ppm = w / 60f;
        float youY = skyBottom - (skyBottom - skyTop) * altitude;
        float band = (skyBottom - skyTop) * RING_BAND;
        // Magnet: close to the next ring and roughly lined up, drift toward its centre.
        for (Gate g : gates) {
            if (g.resolved) {
                continue;
            }
            float ahead = g.at - (float) x;
            // Down to a little behind the ring: it is judged on the first frame past it, and a
            // magnet that let go on exactly that frame never helped the pass it was built for.
            if (ahead > -5f && ahead < MAGNET_METRES) {
                float gy = skyBottom - (skyBottom - skyTop) * altFor(g.speed);
                if (Math.abs(youY - gy) < band * 2.2f) {
                    float pull = 0.45f * (1f - Math.max(0f, ahead) / MAGNET_METRES);
                    youY += (gy - youY) * pull;
                }
            }
            break;
        }

        if (started && !over) {
            // Forks: which side of the spire you are on when you reach it is the road you take.
            for (int i = 0; i < forks.size(); i++) {
                Fork f = forks.get(i);
                if (f.chosen >= 0 || x < f.start) {
                    continue;
                }
                corridor(f.start);
                f.chosen = youY < yOf(cRockLo) ? 1 : 0;
                for (Gate g : gates) {
                    if (g.fork == f && g.branch != f.chosen) {
                        g.resolved = true;
                        g.skipped = true;
                    }
                }
                if (f.chosen == 1) {
                    banner("HIGH ROAD  -  HOLD THE PACE FOR THE STARS", 0xFFF5C518);
                } else {
                    banner("LOW ROAD  -  EASY PACE, STAY UNDER THE SPIRE", ACCENT);
                }
            }
            for (Gate g : gates) {
                if (g.resolved || g.at > x) {
                    continue;
                }
                g.resolved = true;
                float gy = skyBottom - (skyBottom - skyTop) * altFor(g.speed);
                float dy = Math.abs(youY - gy);
                if (dy <= band) {
                    g.passed = true;
                    passed++;
                    streak++;
                    score++;
                    fx.burst(youX, youY, 18, dp(120f), 0.5f, dp(3f), 0xFF35D0BA, false);
                    String text = streak >= 3 ? "+1  STREAK x" + streak : "+1";
                    int col = 0xFFF5C518;
                    if (g.pickup != NONE && dy <= band * 0.4f) {
                        g.collected = true;
                        if (g.pickup == STAR) {
                            score += 5;
                            gems++;
                            text = "STAR  +5";
                            fx.burst(youX, youY, 30, dp(200f), 0.7f, dp(3.5f), 0xFFF5C518, false);
                        } else if (g.pickup == HEART && lives < LIVES) {
                            lives++;
                            text = "LIFE BACK";
                            col = 0xFFF0655D;
                            fx.burst(youX, youY, 24, dp(150f), 0.6f, dp(3.5f), 0xFFF0655D, false);
                        } else {
                            score += 2;
                            gems++;
                            text = g.pickup == HEART ? "HEART  +2" : "GEM  +2";
                            col = 0xFF4FE3D0;
                            fx.burst(youX, youY, 22, dp(160f), 0.6f, dp(3f), 0xFF4FE3D0, false);
                        }
                    } else if (dy > band * 0.7f) {
                        // Through, but only just: the rower asked for near misses to pay.
                        score += 2;
                        shaves++;
                        text = "CLOSE SHAVE  +2";
                        col = 0xFFFF8A3D;
                        shake.kick(dp(4f));
                        fx.burst(youX, gy + Math.signum(youY - gy) * band, 16, dp(140f), 0.4f, dp(2.5f), 0xFFFFD36A, false);
                    }
                    popup(text, col, youY);
                } else {
                    loseLife(youX, youY);
                    if (over) {
                        break;
                    }
                }
            }
            // Rock: the roof, the floor, and inside a fork the spire on the side you did not take.
            if (!over) {
                corridor(x);
                float slack = dp(7f);
                boolean high = youY < yOf(cCeil) + slack;
                boolean low = youY > yOf(cFloor) - slack;
                if (cFork != null && cFork.chosen >= 0 && cRockHi > cRockLo + 0.01f) {
                    if (cFork.chosen == 1 && youY > yOf(cRockHi) - slack) {
                        low = true;
                    } else if (cFork.chosen == 0 && youY < yOf(cRockLo) + slack) {
                        high = true;
                    }
                }
                scrapeHigh = high;
                if ((high || low) && sessionSeconds > graceUntil) {
                    scrapeTime += dt;
                    shake.kick(dp(3f));
                    sparkClock += dt;
                    if (sparkClock > 0.04) {
                        sparkClock = 0;
                        fx.spawn(youX + dp(10f), youY + (high ? -dp(8f) : dp(8f)),
                                -dp(80f) - (float) Math.random() * dp(120f),
                                (high ? -1 : 1) * (float) Math.random() * dp(60f),
                                0.4f, dp(2.5f), 0xFFFFD36A, false);
                    }
                    if (scrapeTime > SCRAPE_LIMIT) {
                        scrapeTime = 0f;
                        graceUntil = sessionSeconds + 2.5;
                        popup(high ? "HIT THE ROOF" : "HIT THE ROCK", BAD, youY);
                        loseLife(youX, youY);
                    }
                } else {
                    scrapeTime = Math.max(0f, scrapeTime - dt * 1.5f);
                }
            }
        }

        // The light: afternoon to dusk along the run.
        float dayT = Daylight.progress(x);
        float dusk = Daylight.shade(dayT);
        int phase = Daylight.phaseIndex(dayT);
        if (phase != lastPhase) {
            if (started && phase > lastPhase) {
                banner(Daylight.phaseName(phase), 0xFFFFC27A);
            }
            lastPhase = phase;
        }

        c.save();
        c.translate(shake.dx, shake.dy);
        if (skyShader == null || skyHeight != h || skyT != dayT) {
            skyHeight = h;
            skyT = dayT;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, h, Daylight.top(dayT), Daylight.horizon(dayT),
                    android.graphics.Shader.TileMode.CLAMP);
        }
        // Opaque first: a shader is drawn at the paint's alpha, and the HUD leaves it at 20%.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(skyShader);
        c.drawRect(-dp(20f), -dp(20f), w + dp(20f), h + dp(20f), paint);
        paint.setShader(null);
        drawSkyLife(c, w, h, ppm, dayT);
        // Canyon walls: jagged silhouettes scrolling, one far, one near.
        for (int layer = 0; layer < 2; layer++) {
            float speedMul = layer == 0 ? 0.25f : 0.6f;
            paint.setColor(layer == 0 ? Daylight.blend(0xFF4A2A2A, 0xFF1C1024, dusk)
                    : Daylight.blend(0xFF2B1717, 0xFF100812, dusk));
            float step = dp(90f);
            float off = (float) ((x * ppm * speedMul) % step);
            path.reset();
            path.moveTo(0, h);
            for (float px = -off; px <= w + step; px += step) {
                int k = (int) ((px + off + x * ppm * speedMul) / step);
                float peak = h * (0.55f + ((k * 7919) % 5) * 0.08f) - layer * h * 0.1f;
                path.lineTo(px, peak);
                path.lineTo(px + step * 0.5f, peak + h * 0.06f);
            }
            path.lineTo(w + step, h);
            path.close();
            c.drawPath(path, paint);
            if (layer == 0) {
                // Between the two walls, so the canyon has a middle distance.
                drawMesas(c, w, h, ppm, dusk);
            }
        }
        // Speed lanes on the left as a subtle altitude scale.
        for (float sp = (float) Math.ceil(minSpeed * 2f) / 2f; sp <= maxSpeed; sp += 0.5f) {
            float ly = skyBottom - (skyBottom - skyTop) * altFor(sp);
            paint.setColor(0x22FFFFFF);
            c.drawLine(0, ly, w, ly, paint);
            label(c, String.format(java.util.Locale.US, "%.1f", sp), dp(6f), ly - dp(3f), 8f, 0x66FFFFFF,
                    Paint.Align.LEFT);
        }
        // The closing rock and any fork spires, in front of the far scenery, behind the plane.
        drawCorridor(c, w, h, youX, ppm, dusk);
        drawForks(c, w, youX, ppm, dusk);
        // Gates: the back half of each ring now, the front half after the plane, so you fly through.
        drawGates(c, w, youX, ppm, band, false);
        // You: a little plane, pitched by the climb, with a contrail and prop blur.
        float climb = dt > 0 && lastYouY != 0f ? (lastYouY - youY) / dt : 0f;
        lastYouY = youY;
        pitch += (Math.max(-0.5f, Math.min(0.5f, climb / dp(160f))) - pitch) * Math.min(1f, 4f * dt);
        Fx.glow(c, youX, youY, dp(60f), streak >= 3 ? 0x66F5C518 : 0x4035D0BA);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int k = 0; k < 3; k++) {
            paint.setColor(k == 0 ? 0x88FFFFFF : k == 1 ? 0x55FFFFFF : 0x33FFFFFF);
            paint.setStrokeWidth(dp(4f - k));
            float tx = youX - dp(40f) - k * speed * dp(14f);
            c.drawLine(youX - dp(30f), youY + pitch * dp(20f), tx - speed * dp(10f), youY + pitch * dp(20f) + k * dp(3f), paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        boolean hurt = sessionSeconds < hurtUntil;
        boolean grace = sessionSeconds < graceUntil;
        if ((!hurt && !grace) || ((int) (sessionSeconds * 10) % 2 == 0)) {
            drawPlane(c, youX, youY, speed);
        }
        drawGates(c, w, youX, ppm, band, true);
        fx.draw(c);
        if (sessionSeconds < popupUntil) {
            float rise = (float) (1.1 - (popupUntil - sessionSeconds)) * dp(50f);
            bold(c, popupText, youX + dp(40f), popupY - dp(30f) - rise, 20f, popupColor, Paint.Align.LEFT);
        }
        c.restore();
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        if (hurt) {
            Fx.vignette(c, w, h, 0.7f, 0x8A1010);
        } else if (scrapeTime > 0.05f) {
            Fx.vignette(c, w, h, 0.25f + scrapeTime / SCRAPE_LIMIT * 0.5f, 0x8A4A10);
        }

        // HUD.
        for (int i = 0; i < LIVES; i++) {
            paint.setColor(i < lives ? ACCENT : 0x33FFFFFF);
            c.drawCircle(dp(18f) + i * dp(18f), dp(20f), dp(6f), paint);
        }
        label(c, gems + " prizes  ·  " + shaves + " shaves", dp(12f), dp(42f), 8.5f, FAINT, Paint.Align.LEFT);
        bold(c, String.valueOf(score), w / 2f, dp(30f), 24f, TEXT, Paint.Align.CENTER);
        label(c, streak >= 2 ? "SCORE  ·  " + passed + " GATES  ·  STREAK x" + streak : "SCORE  ·  " + passed + " GATES",
                w / 2f, dp(44f), 8.5f, streak >= 3 ? 0xFFF5C518 : FAINT, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.1f m/s", speed), w - dp(16f), dp(30f), 18f, ACCENT,
                Paint.Align.RIGHT);
        label(c, Daylight.phaseName(phase), w - dp(16f), dp(44f), 8.5f, 0xFFFFC27A, Paint.Align.RIGHT);
        if (sessionSeconds < bannerUntil) {
            bold(c, bannerText, w / 2f, h * 0.2f, 18f, bannerColor, Paint.Align.CENTER);
        }
        Gate next = null;
        for (Gate g : gates) {
            if (!g.resolved) {
                next = g;
                break;
            }
        }
        Fork nextFork = null;
        for (int i = 0; i < forks.size(); i++) {
            Fork f = forks.get(i);
            if (f.chosen < 0 && f.start - x < 70f) {
                nextFork = f;
                break;
            }
        }
        corridor(x);
        float roofGap = youY - yOf(cCeil);
        float floorGap = yOf(cFloor) - youY;
        String cap;
        int col = FAINT;
        if (!started) {
            cap = "TAKE A STROKE TO TAKE OFF";
        } else if (over) {
            cap = "DOWN  ·  score " + score + "  ·  " + passed + " gates, " + Math.round(x) + " m  ·  tap to fly again";
            col = BAD;
        } else if (scrapeTime > 0.05f) {
            // Covers the fork spire too, which is neither the roof nor the floor.
            cap = scrapeHigh ? "SCRAPING THE ROOF - EASE OFF" : "SCRAPING ROCK - PULL UP";
            col = BAD;
        } else if (nextFork != null) {
            cap = String.format(java.util.Locale.US, "FORK IN %d m  ·  CLIMB FOR THE HIGH ROAD, SETTLE FOR THE LOW",
                    Math.max(0, Math.round(nextFork.start - x)));
            col = 0xFFF5C518;
        } else if (roofGap < dp(28f)) {
            cap = "ROOF CLOSING - EASE OFF";
            col = WARN;
        } else if (floorGap < dp(28f)) {
            cap = "ROCK BELOW - PULL UP";
            col = WARN;
        } else if (next != null) {
            float diff = next.speed - speed;
            cap = Math.abs(diff) < 0.5f ? "LINED UP - HOLD IT"
                    : diff > 0 ? String.format(java.util.Locale.US, "CLIMB  +%.1f m/s  ·  %d m", diff, Math.round(next.at - x))
                    : String.format(java.util.Locale.US, "DIVE  %.1f m/s  ·  %d m", diff, Math.round(next.at - x));
            col = Math.abs(diff) < 0.5f ? ACCENT : WARN;
        } else {
            cap = "CLEAR SKIES";
        }
        bold(c, cap, w / 2f, h - dp(16f), 12f, col, Paint.Align.CENTER);
    }

    /**
     * The light along the canyon route, shared by FLY and DRIVE: afternoon to golden hour to
     * sunset to dusk as the run gets longer. Progress is quantised so the sky gradient is rebuilt a
     * few dozen times a run, not every frame.
     */
    static final class Daylight {
        /** Metres of run at which the sun reaches the rim. */
        private static final double SUNSET_METRES = 1800.0;
        private static final float MAX_T = 1.3f;
        private static final float[] KEY_T = {0f, 0.45f, 0.8f, 1.0f, 1.3f};
        private static final int[] TOP = {0xFF3A6EA5, 0xFF2D5486, 0xFF1B3358, 0xFF2A2352, 0xFF0E0C24};
        private static final int[] HORIZON = {0xFFF2D09A, 0xFFF0A860, 0xFFE07A3F, 0xFFD9553A, 0xFF6A2E4C};
        private static final int[] SUN = {0xFFFFF4D6, 0xFFFFE2A8, 0xFFFFC27A, 0xFFFF8A4A, 0xFFC0452E};
        private static final String[] PHASES = {"AFTERNOON", "GOLDEN HOUR", "SUNSET", "DUSK"};

        private Daylight() {
        }

        /** 0 at the start of a run, 1 at sunset, up to 1.3 at dusk; quantised to 1/40. */
        static float progress(double metres) {
            float t = (float) Math.min(MAX_T, Math.max(0.0, metres / SUNSET_METRES));
            return Math.round(t * 40f) / 40f;
        }

        static int top(float t) {
            return keyed(TOP, t);
        }

        static int horizon(float t) {
            return keyed(HORIZON, t);
        }

        static int sun(float t) {
            return keyed(SUN, t);
        }

        /** How far the sun has sunk: 0 high, 1 at the rim, past 1 behind it. */
        static float sunDrop(float t) {
            return Math.min(1.25f, t);
        }

        /** 0..1: how much the land is darkened toward dusk. */
        static float shade(float t) {
            return Math.min(1f, Math.max(0f, (t - 0.3f) / 1.0f)) * 0.75f;
        }

        /** Star brightness, 0 until just after sunset. */
        static float stars(float t) {
            return Math.min(1f, Math.max(0f, (t - 1.0f) / 0.25f));
        }

        static int phaseIndex(float t) {
            return t < 0.4f ? 0 : t < 0.8f ? 1 : t < 1.08f ? 2 : 3;
        }

        static String phaseName(int i) {
            return PHASES[Math.max(0, Math.min(PHASES.length - 1, i))];
        }

        private static int keyed(int[] cols, float t) {
            for (int i = 1; i < KEY_T.length; i++) {
                if (t <= KEY_T[i]) {
                    return blend(cols[i - 1], cols[i], (t - KEY_T[i - 1]) / (KEY_T[i] - KEY_T[i - 1]));
                }
            }
            return cols[cols.length - 1];
        }

        static int blend(int a, int b, float t) {
            t = Math.max(0f, Math.min(1f, t));
            int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
            int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
            int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | bl;
        }
    }
}
