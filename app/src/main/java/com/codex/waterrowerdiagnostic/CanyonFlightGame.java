package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;

/**
 * Canyon Flight: your boat speed is your altitude. Ring gates come at you at different heights;
 * fly through them or lose a life. Gates step up and down, so you are changing gear every hundred
 * metres - it is intervals wearing a wingsuit. Altitude follows speed with a glide, not a snap.
 */
final class CanyonFlightGame extends GameView {

    private static final int LIVES = 3;
    private static final float MIN_SPEED = 1.2f;
    private static final float MAX_SPEED = 4.6f;

    private static final class Gate {
        final float at;        // metres
        final float speed;     // centre of the band
        boolean resolved;
        boolean passed;

        Gate(float at, float speed) {
            this.at = at;
            this.speed = speed;
        }
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final java.util.List<Gate> gates = new java.util.ArrayList<>();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();

    private boolean started;
    private boolean over;
    private int lives;
    private int passed;
    private double runStart;
    private double x;
    private float altitude;      // 0..1
    private double hurtUntil;

    CanyonFlightGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        started = false;
        over = false;
        lives = LIVES;
        passed = 0;
        x = 0;
        altitude = 0f;
        gates.clear();
        java.util.Random r = new java.util.Random(77);
        float at = 80f;
        float target = 2.6f;
        while (at < 8000f) {
            // Gates wander: a step of up to 0.8 m/s, biased back toward the middle.
            target += (r.nextFloat() - 0.5f) * 1.6f + (2.8f - target) * 0.25f;
            target = Math.max(MIN_SPEED + 0.3f, Math.min(MAX_SPEED - 0.3f, target));
            gates.add(new Gate(at, target));
            at += 90f + r.nextFloat() * 60f;
        }
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

    private float altFor(float speed) {
        return Math.max(0f, Math.min(1f, (speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED)));
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
        // Glide: altitude eases toward where speed says it should be.
        altitude += (altFor(speed) - altitude) * Math.min(1f, 2.2f * dt);
        shake.step(dt);
        fx.step(dt, 0f);

        float skyTop = h * 0.10f;
        float skyBottom = h * 0.86f;
        float youX = w * 0.28f;
        float ppm = w / 60f;
        float youY = skyBottom - (skyBottom - skyTop) * altitude;
        float band = (skyBottom - skyTop) * 0.10f;   // half-height of a ring: ~0.34 m/s each way

        if (started && !over) {
            for (Gate g : gates) {
                if (g.resolved || g.at > x) {
                    continue;
                }
                g.resolved = true;
                float gy = skyBottom - (skyBottom - skyTop) * altFor(g.speed);
                if (Math.abs(youY - gy) <= band) {
                    g.passed = true;
                    passed++;
                    fx.burst(youX, youY, 18, dp(120f), 0.5f, dp(3f), 0xFF35D0BA, false);
                } else {
                    lives--;
                    hurtUntil = sessionSeconds + 1.2;
                    shake.kick(dp(10f));
                    fx.burst(youX, youY, 24, dp(160f), 0.6f, dp(3.5f), 0xFFF0655D, false);
                    if (lives <= 0) {
                        over = true;
                        bests.recordHighest("canyon.gates", passed);
                    }
                }
            }
        }

        c.save();
        c.translate(shake.dx, shake.dy);
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF1B3358, 0xFFE07A3F,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // Canyon walls: jagged silhouettes scrolling, one far, one near.
        for (int layer = 0; layer < 2; layer++) {
            float speedMul = layer == 0 ? 0.25f : 0.6f;
            paint.setColor(layer == 0 ? 0xFF4A2A2A : 0xFF2B1717);
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
        }
        // Speed lanes on the left as a subtle altitude scale.
        for (float sp = 1.5f; sp <= 4.5f; sp += 0.5f) {
            float ly = skyBottom - (skyBottom - skyTop) * altFor(sp);
            paint.setColor(0x22FFFFFF);
            c.drawLine(0, ly, w, ly, paint);
            label(c, String.format(java.util.Locale.US, "%.1f", sp), dp(6f), ly - dp(3f), 8f, 0x66FFFFFF,
                    Paint.Align.LEFT);
        }
        // Gates.
        for (Gate g : gates) {
            float gx = youX + (g.at - (float) x) * ppm;
            if (gx < -dp(60f) || gx > w + dp(60f)) {
                continue;
            }
            float gy = skyBottom - (skyBottom - skyTop) * altFor(g.speed);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(5f));
            paint.setColor(g.resolved ? (g.passed ? ACCENT : BAD) : 0xFFF5C518);
            c.drawOval(gx - dp(14f), gy - band, gx + dp(14f), gy + band, paint);
            paint.setStyle(Paint.Style.FILL);
            if (!g.resolved) {
                Fx.glow(c, gx, gy, band * 1.3f, 0x44F5C518);
                label(c, String.format(java.util.Locale.US, "%.1f m/s", g.speed), gx, gy - band - dp(6f),
                        8.5f, TEXT, Paint.Align.CENTER);
            }
        }
        // You: a small wing with a contrail.
        Fx.glow(c, youX, youY, dp(40f), 0x4035D0BA);
        paint.setColor(0x66BFE3FF);
        paint.setStrokeWidth(dp(3f));
        c.drawLine(youX - dp(18f), youY, youX - dp(18f) - speed * dp(20f), youY + dp(2f), paint);
        boolean hurt = sessionSeconds < hurtUntil;
        if (!hurt || ((int) (sessionSeconds * 10) % 2 == 0)) {
            paint.setColor(ACCENT);
            path.reset();
            path.moveTo(youX + dp(20f), youY);
            path.lineTo(youX - dp(14f), youY - dp(9f));
            path.lineTo(youX - dp(6f), youY);
            path.lineTo(youX - dp(14f), youY + dp(9f));
            path.close();
            c.drawPath(path, paint);
        }
        fx.draw(c);
        c.restore();
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        if (hurt) {
            Fx.vignette(c, w, h, 0.7f, 0x8A1010);
        }

        // HUD.
        for (int i = 0; i < LIVES; i++) {
            paint.setColor(i < lives ? ACCENT : 0x33FFFFFF);
            c.drawCircle(dp(18f) + i * dp(18f), dp(20f), dp(6f), paint);
        }
        bold(c, String.valueOf(passed), w / 2f, dp(30f), 24f, TEXT, Paint.Align.CENTER);
        label(c, "GATES", w / 2f, dp(44f), 8.5f, FAINT, Paint.Align.CENTER);
        bold(c, String.format(java.util.Locale.US, "%.1f m/s", speed), w - dp(16f), dp(30f), 18f, ACCENT,
                Paint.Align.RIGHT);
        Gate next = null;
        for (Gate g : gates) {
            if (!g.resolved) {
                next = g;
                break;
            }
        }
        String cap;
        int col = FAINT;
        if (!started) {
            cap = "TAKE A STROKE TO TAKE OFF";
        } else if (over) {
            cap = "DOWN  ·  " + passed + " gates, " + Math.round(x) + " m  ·  tap to fly again";
            col = BAD;
        } else if (next != null) {
            float diff = next.speed - speed;
            cap = Math.abs(diff) < 0.35f ? "LINED UP - HOLD IT"
                    : diff > 0 ? String.format(java.util.Locale.US, "CLIMB  +%.1f m/s  ·  %d m", diff, Math.round(next.at - x))
                    : String.format(java.util.Locale.US, "DIVE  %.1f m/s  ·  %d m", diff, Math.round(next.at - x));
            col = Math.abs(diff) < 0.35f ? ACCENT : WARN;
        } else {
            cap = "CLEAR SKIES";
        }
        bold(c, cap, w / 2f, h - dp(16f), 12f, col, Paint.Align.CENTER);
    }
}
