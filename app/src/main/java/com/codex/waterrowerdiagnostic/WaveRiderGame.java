package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Wave Rider: match the wave, not beat it.
 *
 * <p>Every other game here rewards pulling harder. This one punishes both ends. Your position on
 * the face is the running integral of your speed minus the wave's, so matching its pace holds you
 * still: fall behind and the lip breaks over you, push too far ahead and you run out onto the flat
 * shoulder and lose the wave. The pocket - just ahead of the curl - scores double, and the wave
 * changes pace in sets so the target keeps moving.
 *
 * <p>Barrel sections throw the lip right over you; hold the pocket through one for a big score.
 */
final class WaveRiderGame extends GameView {

    private enum Phase { WAITING, RIDING, WIPEOUT, KICKOUT }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles spray = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();

    private Phase phase = Phase.WAITING;
    private float position;          // -1 caught by the lip .. +1 out on the shoulder
    private double rideSeconds;
    private double bestRide;
    private double score;
    private int rides;
    private int barrels;
    private double barrelUntil;
    private double nextBarrelAt;
    private double endedAt;
    private float sprayAccum;
    /* Life around the wave. */
    private final float[] gullX = {0.2f, 0.55f, 0.8f};
    private double dolphinAt = 8;
    private boolean wasPocket;
    private String popup = "";
    private double popupUntil;
    private int lastScoreMark;

    WaveRiderGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        phase = Phase.WAITING;
        position = 0f;
        rideSeconds = 0;
        bestRide = 0;
        score = 0;
        rides = 0;
        barrels = 0;
        barrelUntil = 0;
        nextBarrelAt = 18;
        lastScoreMark = 0;
        wasPocket = false;
    }

    @Override
    protected void onStop() {
        bests.recordHighest("surf.ride", (float) bestRide);
        bests.recordHighest("surf.score", (float) score);
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.WAITING && driving && boat.value() > 1.0f) {
            phase = Phase.RIDING;
            position = 0f;
            rideSeconds = 0;
            rides++;
            nextBarrelAt = sessionSeconds + 14 + Math.random() * 12;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN
                && (phase == Phase.WIPEOUT || phase == Phase.KICKOUT)) {
            phase = Phase.WAITING;
            return true;
        }
        return super.onTouchEvent(e);
    }

    /**
     * The wave's own pace: a base that drifts, plus sets that push it along. Scaled to the rower's
     * typical speed (the old fixed 2.5 m/s base was 65% of the original rower's 3.85).
     */
    private float waveSpeed() {
        double t = sessionSeconds;
        float typical = (float) profile.typicalSpeed();
        float base = typical * 0.65f;
        float drift = (float) Math.sin(t / 11.0) * typical * 0.12f;
        float set = (float) Math.sin(t / 37.0) * typical * 0.14f;
        return base + drift + set + (inBarrel() ? typical * 0.09f : 0f);
    }

    private boolean inBarrel() {
        return sessionSeconds < barrelUntil;
    }

    private boolean inPocket() {
        return Math.abs(position) < 0.35f;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();
        float wave = waveSpeed();

        if (phase == Phase.RIDING) {
            rideSeconds += dt;
            // Position is the integral of the speed difference: match the wave and you hold. The first
            // seconds of a ride are forgiven - on the tablet a rower still getting up to speed was
            // wiped out after five seconds - and the drift is gentler than it first shipped (0.42).
            float gain = rideSeconds < 6 ? 0.08f : 0.3f;
            position += (speed - wave) * dt * gain;
            // With the handle sensor, leaning carves along the face - a small correction, not a
            // substitute for matching the wave's pace.
            if (hasSteering()) {
                position += steering() * 0.18f * dt;
            }
            score += dt * (inPocket() ? 2.0 : 1.0) * (inBarrel() ? 3.0 : 1.0);
            if (!inBarrel() && sessionSeconds >= nextBarrelAt) {
                barrelUntil = sessionSeconds + 6;
                nextBarrelAt = sessionSeconds + 22 + Math.random() * 14;
            }
            if (inBarrel() && inPocket()) {
                // Counted once per barrel, on the way out.
                if (sessionSeconds > barrelUntil - dt * 2) {
                    barrels++;
                }
            }
            if (position <= -1f) {
                phase = Phase.WIPEOUT;
                endedAt = sessionSeconds;
                bestRide = Math.max(bestRide, rideSeconds);
                shake.kick(dp(18f));
                spray.burst(w * 0.62f, h * 0.55f, 60, dp(260f), 1.0f, dp(4f), 0xDDFFFFFF, true);
            } else if (position >= 1f) {
                phase = Phase.KICKOUT;
                endedAt = sessionSeconds;
                bestRide = Math.max(bestRide, rideSeconds);
            }
        }
        shake.step(dt);
        spray.step(dt, dp(260f));

        // ---------- scene ----------
        float horizon = h * 0.26f;
        float lipX = w * 0.74f;
        float crestY = h * 0.30f;
        float troughY = h * 0.86f;
        float faceRun = w * 0.62f;

        c.save();
        c.translate(shake.dx, shake.dy);
        // Sunset sky.
        paint.setShader(new LinearGradient(0, 0, 0, horizon, 0xFF2B3F70, 0xFFF3A469,
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizon + dp(2f), paint);
        paint.setShader(null);
        Fx.glow(c, w * 0.22f, horizon - dp(6f), dp(70f), 0x77FFD9A0);
        paint.setColor(0xFFFFE0A8);
        c.drawCircle(w * 0.22f, horizon - dp(6f), dp(24f), paint);

        // Seagulls gliding over, wings flexing.
        paint.setColor(0xFF2A2F3A);
        paint.setStrokeWidth(dp(2.2f));
        for (int i = 0; i < gullX.length; i++) {
            gullX[i] += dt * (0.018f + i * 0.006f);
            if (gullX[i] > 1.08f) {
                gullX[i] = -0.08f;
            }
            float gx = gullX[i] * w;
            float gy = horizon * (0.35f + i * 0.17f) + (float) Math.sin(sessionSeconds + i) * dp(8f);
            float flex = (float) Math.sin(sessionSeconds * 5 + i) * dp(4f);
            c.drawLine(gx - dp(12f), gy - flex, gx, gy, paint);
            c.drawLine(gx, gy, gx + dp(12f), gy - flex, paint);
        }

        // Open ocean beyond the wave.
        paint.setShader(new LinearGradient(0, horizon, 0, h, 0xFF1E5C86, 0xFF0A2A44,
                Shader.TileMode.CLAMP));
        c.drawRect(0, horizon, w, h, paint);
        paint.setShader(null);
        // Sailboats on the horizon, and the sun glinting on the water.
        for (int i = 0; i < 2; i++) {
            float sx = (float) ((w * (0.35f + i * 0.3f) + sessionSeconds * dp(4f) * (i + 1)) % w);
            paint.setColor(0xFFF2EAD8);
            path.reset();
            path.moveTo(sx, horizon - dp(18f));
            path.lineTo(sx + dp(10f), horizon - dp(2f));
            path.lineTo(sx, horizon - dp(2f));
            path.close();
            c.drawPath(path, paint);
            paint.setColor(0xFF3A3F4A);
            c.drawRect(sx - dp(8f), horizon - dp(2f), sx + dp(12f), horizon + dp(1f), paint);
        }
        paint.setColor(0xFFFFF1C8);
        for (int i = 0; i < 26; i++) {
            float gx = w * 0.22f + (float) Math.sin(i * 12.9898) * w * 0.18f;
            float gy = horizon + dp(8f) + (i % 7) * dp(9f);
            if (Math.sin(sessionSeconds * 3 + i * 1.7) > 0.4) {
                c.drawRect(gx - dp(5f), gy, gx + dp(5f), gy + dp(1.6f), paint);
            }
        }

        // The wave face: a curve from the lip down-left into the trough.
        path.reset();
        path.moveTo(lipX + w * 0.3f, crestY);
        path.lineTo(lipX, crestY);
        for (float px = lipX; px >= lipX - faceRun; px -= dp(10f)) {
            path.lineTo(px, faceY(px, lipX, crestY, troughY, faceRun));
        }
        path.lineTo(lipX - faceRun, h);
        path.lineTo(w, h);
        path.close();
        paint.setShader(new LinearGradient(lipX - faceRun, crestY, lipX, troughY,
                0xFF1B6FA8, 0xFF0D3C5E, Shader.TileMode.CLAMP));
        c.drawPath(path, paint);
        paint.setShader(null);

        // Lip: the curl, thrown further over during a barrel.
        float throwOver = inBarrel() ? w * 0.30f : w * 0.10f;
        paint.setColor(0xFF7FC6EE);
        path.reset();
        path.moveTo(lipX, crestY);
        path.cubicTo(lipX + dp(10f), crestY - dp(46f), lipX - throwOver * 0.5f, crestY - dp(54f),
                lipX - throwOver, crestY + dp(18f));
        path.cubicTo(lipX - throwOver * 0.6f, crestY - dp(16f), lipX - dp(6f), crestY + dp(12f),
                lipX, crestY + dp(30f));
        path.close();
        c.drawPath(path, paint);
        // Whitewater under the curl.
        paint.setColor(0xEEFFFFFF);
        for (int i = 0; i < 16; i++) {
            float fx0 = lipX - throwOver * (i / 16f);
            float fy0 = crestY + dp(20f) + (float) Math.sin(i * 1.7 + sessionSeconds * 8) * dp(7f);
            c.drawCircle(fx0, fy0, dp(7f) + (i % 3) * dp(3f), paint);
        }

        // Surfer: position maps along the face, pocket sitting just left of the curl.
        float t = (position + 1f) / 2f;                 // 0 = in the curl, 1 = far shoulder
        float surfX = lipX - dp(26f) - t * faceRun * 0.82f;
        float surfY = faceY(surfX, lipX, crestY, troughY, faceRun);
        if (phase == Phase.RIDING) {
            sprayAccum += dt * (6f + speed * 8f);
            while (sprayAccum >= 1f) {
                sprayAccum -= 1f;
                spray.spawn(surfX, surfY, dp(30f) + (float) Math.random() * dp(70f),
                        -dp(20f) - (float) Math.random() * dp(50f), 0.45f, dp(2.4f), 0xCCEAF6FF, true);
            }
        }
        spray.draw(c);
        if (phase != Phase.WIPEOUT) {
            drawSurfer(c, surfX, surfY, inPocket());
        }
        // A dolphin leaps in the foreground now and then.
        double sinceDolphin = sessionSeconds - dolphinAt;
        if (sinceDolphin > 0 && sinceDolphin < 1.4) {
            float f = (float) (sinceDolphin / 1.4);
            float dx = w * (0.15f + 0.3f * f);
            float dy = h * 0.92f - (float) Math.sin(Math.PI * f) * h * 0.16f;
            c.save();
            c.rotate(-60 + 120 * f, dx, dy);
            paint.setColor(0xFF6E8FA8);
            c.drawOval(dx - dp(26f), dy - dp(8f), dx + dp(26f), dy + dp(8f), paint);
            path.reset();
            path.moveTo(dx - dp(2f), dy - dp(7f));
            path.lineTo(dx + dp(8f), dy - dp(18f));
            path.lineTo(dx + dp(10f), dy - dp(6f));
            path.close();
            c.drawPath(path, paint);
            c.restore();
            if (f < 0.08f || f > 0.92f) {
                spray.burst(dx, h * 0.92f, 6, dp(90f), 0.5f, dp(2.5f), 0xCCEAF6FF, true);
            }
        } else if (sinceDolphin >= 1.4) {
            dolphinAt = sessionSeconds + 12 + Math.random() * 14;
        }
        // Barrel light: rays through the curl.
        if (inBarrel() && phase == Phase.RIDING) {
            paint.setStrokeWidth(dp(14f));
            for (int k = 0; k < 5; k++) {
                paint.setColor(0x14FFFFFF);
                float ox = lipX - w * 0.05f * k;
                c.drawLine(ox, crestY, ox - w * 0.25f, h, paint);
            }
        }
        c.restore();

        // Barrel framing: the lip arcs right over the top of the view.
        if (inBarrel() && phase == Phase.RIDING) {
            paint.setColor(0xCC0A1A26);
            path.reset();
            path.moveTo(0, 0);
            path.lineTo(w, 0);
            path.lineTo(w, h * 0.16f);
            path.cubicTo(w * 0.6f, h * 0.34f, w * 0.3f, h * 0.10f, 0, h * 0.22f);
            path.close();
            c.drawPath(path, paint);
            Fx.vignette(c, w, h, 0.55f, 0x08243A);
        }
        // Pops: entering the pocket, and every 25 points.
        if (phase == Phase.RIDING) {
            if (inPocket() && !wasPocket) {
                popup = inBarrel() ? "3x IN THE BARREL!" : "2x POCKET!";
                popupUntil = sessionSeconds + 1.4;
                spray.burst(surfX, surfY, 26, dp(160f), 0.7f, dp(3f), 0xDDEAF6FF, true);
            }
            int mark = (int) (score / 25);
            if (mark > lastScoreMark) {
                lastScoreMark = mark;
                popup = "+" + (mark * 25) + " POINTS";
                popupUntil = sessionSeconds + 1.4;
            }
        }
        wasPocket = inPocket();
        if (sessionSeconds < popupUntil) {
            float rise = (float) (1.4 - (popupUntil - sessionSeconds)) * dp(40f);
            bold(c, popup, w * 0.45f, h * 0.42f - rise, 26f, 0xFFF5C518, Paint.Align.CENTER);
        }
        if (!inPocket() && phase == Phase.RIDING) {
            Fx.vignette(c, w, h, 0.25f + Math.abs(position) * 0.5f,
                    position < 0 ? 0x8A1010 : 0x1A3A5A);
        }

        // ---------- HUD ----------
        // The band: where you are on the face, with the pocket marked.
        float bandW = w * 0.52f;
        float bx = w * 0.5f - bandW / 2f;
        float by = h - dp(52f);
        paint.setColor(0x44000000);
        c.drawRoundRect(bx, by, bx + bandW, by + dp(14f), dp(7f), dp(7f), paint);
        paint.setColor(0x3335D0BA);
        c.drawRect(bx + bandW * 0.325f, by, bx + bandW * 0.675f, by + dp(14f), paint);
        float px = bx + bandW * ((position + 1f) / 2f);
        paint.setColor(inPocket() ? ACCENT : position < 0 ? BAD : WARN);
        c.drawRoundRect(px - dp(4f), by - dp(4f), px + dp(4f), by + dp(18f), dp(3f), dp(3f), paint);
        label(c, "LIP", bx, by + dp(28f), 8.5f, BAD, Paint.Align.LEFT);
        label(c, "POCKET", w * 0.5f, by + dp(28f), 8.5f, ACCENT, Paint.Align.CENTER);
        label(c, "SHOULDER", bx + bandW, by + dp(28f), 8.5f, WARN, Paint.Align.RIGHT);

        // Sweet-spot bar up the right edge: where you are on the face, the pocket in green, and an
        // arrow for which way you are drifting - up when you are faster than the wave, down when
        // slower. Readable at a glance, mid-stroke, without looking for the surfer.
        float sx = w - dp(30f);
        float sTop = h * 0.16f;
        float sBottom = h * 0.70f;
        float sMid = (sTop + sBottom) / 2f;
        float half = (sBottom - sTop) / 2f;
        paint.setColor(0x66000000);
        c.drawRoundRect(sx - dp(10f), sTop, sx + dp(10f), sBottom, dp(10f), dp(10f), paint);
        paint.setColor(0x6635D0BA);
        c.drawRect(sx - dp(10f), sMid - half * 0.35f, sx + dp(10f), sMid + half * 0.35f, paint);
        float marker = sMid - half * Math.max(-1f, Math.min(1f, position));
        paint.setColor(inPocket() ? ACCENT : position < 0 ? BAD : WARN);
        c.drawRoundRect(sx - dp(16f), marker - dp(5f), sx + dp(16f), marker + dp(5f), dp(4f), dp(4f), paint);
        if (phase == Phase.RIDING && Math.abs(speed - wave) > 0.08f) {
            boolean up = speed > wave;
            float ay = marker + (up ? -dp(14f) : dp(14f));
            path.reset();
            path.moveTo(sx - dp(8f), ay + (up ? dp(6f) : -dp(6f)));
            path.lineTo(sx + dp(8f), ay + (up ? dp(6f) : -dp(6f)));
            path.lineTo(sx, ay - (up ? dp(6f) : -dp(6f)));
            path.close();
            c.drawPath(path, paint);
        }
        label(c, "SHOULDER", sx, sTop - dp(8f), 8f, WARN, Paint.Align.CENTER);
        label(c, "LIP", sx, sBottom + dp(16f), 8f, BAD, Paint.Align.CENTER);
        label(c, "POCKET", sx - dp(18f), sMid + dp(3f), 8f, ACCENT, Paint.Align.RIGHT);

        String big;
        String cap;
        int col;
        switch (phase) {
            case WAITING:
                big = "PADDLE FOR IT";
                cap = "get above 1.0 m/s to catch the wave";
                col = DIM;
                break;
            case WIPEOUT:
                big = "WIPEOUT";
                cap = "the lip got you after " + clock(rideSeconds) + "  ·  tap for another";
                col = BAD;
                break;
            case KICKOUT:
                big = "LOST THE WAVE";
                cap = "too far ahead - rode " + clock(rideSeconds) + "  ·  tap for another";
                col = WARN;
                break;
            default:
                big = clock(rideSeconds);
                if (inBarrel()) {
                    cap = inPocket() ? "IN THE BARREL - TRIPLE SCORE" : "BARREL - GET IN THE POCKET";
                    col = inPocket() ? ACCENT : WARN;
                } else if (inPocket()) {
                    cap = "IN THE POCKET - DOUBLE SCORE";
                    col = ACCENT;
                } else if (position < 0) {
                    cap = String.format(java.util.Locale.US, "PULL HARDER - WAVE IS DOING %.1f m/s", wave);
                    col = BAD;
                } else {
                    cap = String.format(java.util.Locale.US, "EASE OFF - WAVE IS DOING %.1f m/s", wave);
                    col = WARN;
                }
        }
        bold(c, big, w * 0.5f, dp(36f), phase == Phase.RIDING ? 40f : 24f, col, Paint.Align.CENTER);
        bold(c, cap, w * 0.5f, dp(54f), 11f, col, Paint.Align.CENTER);

        float fy = h - dp(12f);
        float colw = w / 4f;
        stat(c, colw * 0.5f, fy, String.valueOf(Math.round(score)), "POINTS");
        stat(c, colw * 1.5f, fy, clock(bestRide), "LONGEST RIDE");
        stat(c, colw * 2.5f, fy, String.valueOf(barrels), "BARRELS");
        stat(c, colw * 3.5f, fy, bests.has("surf.score")
                ? String.valueOf(Math.round(bests.get("surf.score", 0))) : "--", "BEST");
    }

    /** Height of the wave face at a given x. Exponent shapes the concave face. */
    private float faceY(float x, float lipX, float crestY, float troughY, float faceRun) {
        float f = Math.max(0f, Math.min(1f, (lipX - x) / faceRun));
        return crestY + (troughY - crestY) * (float) Math.pow(f, 0.72);
    }

    private void drawSurfer(Canvas c, float x, float y, boolean pocket) {
        float s = dp(1f);
        Fx.glow(c, x, y, dp(34f), pocket ? 0x4435D0BA : 0x22FFFFFF);
        // Board, angled along the face.
        paint.setColor(0xFFF5C518);
        c.save();
        c.rotate(24f, x, y);
        c.drawRoundRect(x - 20 * s, y - 3 * s, x + 20 * s, y + 3 * s, 3 * s, 3 * s, paint);
        c.restore();
        // Rider: a crouched figure leaning into the face.
        paint.setColor(pocket ? ACCENT : 0xFFE6EDF7);
        c.drawRect(x - 4 * s, y - 20 * s, x + 4 * s, y - 6 * s, paint);      // torso
        c.drawRect(x - 7 * s, y - 8 * s, x - 1 * s, y - 2 * s, paint);       // back leg
        c.drawRect(x + 2 * s, y - 8 * s, x + 8 * s, y - 2 * s, paint);       // front leg
        c.drawRect(x + 4 * s, y - 18 * s, x + 14 * s, y - 15 * s, paint);    // lead arm
        paint.setColor(0xFFF1C27D);
        c.drawCircle(x, y - 24 * s, 4.5f * s, paint);
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
