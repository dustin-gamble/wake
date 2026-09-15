package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * DAILY ROW: one short challenge a day, the same for everyone on that date, and a streak for doing it.
 *
 * <p>Four kinds rotate by date: most metres in four minutes, a 500 m time trial, ten powerful
 * strokes, and three minutes held steady at your typical speed. Targets come from the rower's own
 * profile, so the challenge is a stretch for anyone. Completing it marks the day on a 28-day
 * calendar; consecutive days make the streak.
 *
 * <p>3.19.5 (the emulator screenshot was text and a grey bar on black): drifting sparkles, a big
 * progress ring with a medal that fills and glows, a stamp and confetti when the day is won, ticks
 * on completed calendar days and a flame on the streak.
 */
final class DailyRowGame extends GameView {

    enum Challenge {
        DISTANCE_4MIN("MOST METRES IN 4 MINUTES"),
        TRIAL_500("500 m TIME TRIAL"),
        POWER_10("10 POWERFUL STROKES"),
        STEADY_3MIN("3 MINUTES STEADY");

        final String title;

        Challenge(String title) {
            this.title = title;
        }
    }

    private enum Phase { READY, ACTIVE, DONE }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.TreeSet<Long> days = new java.util.TreeSet<>();

    private Challenge challenge;
    private long today;
    private Phase phase = Phase.READY;
    private double startSeconds;
    private double startMeters;
    private double target;
    private double result;
    private boolean success;
    private int strokes;
    private double powerSum;
    private double steadySeconds;
    private PulseMeter.Stroke lastSeen;
    private final Fx.Particles fx = new Fx.Particles();
    private final android.graphics.Path shape = new android.graphics.Path();
    private double doneAt = -10;
    private float ringShown;

    DailyRowGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        today = RegattaGame.today();
        long mix = today * 2654435761L;
        challenge = Challenge.values()[(int) (((mix >>> 16) & 0x7fffffff) % Challenge.values().length)];
        days.clear();
        String saved = bests.getString("daily.days");
        if (saved != null) {
            for (String s : saved.split(",")) {
                try {
                    days.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        switch (challenge) {
            case DISTANCE_4MIN:
                target = profile.typicalSpeed() * 240 * 1.03;
                break;
            case TRIAL_500:
                target = 500 / profile.typicalSpeed() * 0.97;
                break;
            case POWER_10:
                target = profile.typicalWatts() * 1.25;
                break;
            default:
                target = 70;
                break;
        }
        phase = Phase.READY;
        strokes = 0;
        powerSum = 0;
        steadySeconds = 0;
        result = 0;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.ACTIVE;
            startSeconds = sessionSeconds;
            startMeters = sessionMeters;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastSeen) {
            lastSeen = stroke;
            if (phase == Phase.ACTIVE && challenge == Challenge.POWER_10) {
                strokes++;
                powerSum += !Double.isNaN(stroke.averagePowerW) ? stroke.averagePowerW : s.watts;
                if (strokes >= 10) {
                    finish(powerSum / strokes);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.DONE) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void finish(double value) {
        phase = Phase.DONE;
        result = value;
        doneAt = sessionSeconds;
        success = challenge == Challenge.TRIAL_500 ? value <= target : value >= target;
        String key = "daily.best." + challenge.name().toLowerCase(java.util.Locale.US);
        if (challenge == Challenge.TRIAL_500) {
            bests.recordLowest(key, (float) value);
        } else {
            bests.recordHighest(key, (float) value);
        }
        if (success && !days.contains(today)) {
            days.add(today);
            while (days.size() > 120) {
                days.pollFirst();
            }
            StringBuilder sb = new StringBuilder();
            for (long d : days) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(d);
            }
            bests.putString("daily.days", sb.toString());
            bests.recordHighest("daily.streak", streak());
        }
    }

    /** Consecutive completed days ending today, or yesterday if today is not done yet. */
    private int streak() {
        long d = days.contains(today) ? today : today - 1;
        int n = 0;
        while (days.contains(d)) {
            n++;
            d--;
        }
        return n;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF08111C);
        c.drawRect(0, 0, w, h, paint);
        // Slow sparkles drifting up the background.
        for (int i = 0; i < 36; i++) {
            float sx = ((i * 0.137f + 0.05f) % 1f) * w;
            float sy = h - (float) (((sessionSeconds * dp(12f + i % 5 * 4f)) + i * dp(97f)) % (h + dp(40f)));
            float tw = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 2 + i);
            paint.setColor(((int) (30 + 60 * tw) << 24) | 0x35D0BA);
            c.drawCircle(sx, sy, dp(1.5f) + tw * dp(1.5f), paint);
        }
        fx.step(dt, dp(260f));
        double elapsed = phase == Phase.ACTIVE ? sessionSeconds - startSeconds : 0;
        double metres = phase == Phase.ACTIVE ? sessionMeters - startMeters : 0;
        float progress = 0f;
        String live = "";
        if (phase == Phase.ACTIVE) {
            switch (challenge) {
                case DISTANCE_4MIN:
                    progress = (float) Math.min(1, elapsed / 240);
                    live = Math.round(metres) + " m  ·  " + clock(Math.max(0, 240 - elapsed)) + " left";
                    if (elapsed >= 240) {
                        finish(metres);
                    }
                    break;
                case TRIAL_500:
                    progress = (float) Math.min(1, metres / 500);
                    live = clock(elapsed) + "  ·  " + Math.round(metres) + " of 500 m";
                    if (metres >= 500) {
                        finish(elapsed);
                    }
                    break;
                case POWER_10:
                    progress = strokes / 10f;
                    live = strokes + " of 10 strokes  ·  " + (strokes > 0 ? Math.round(powerSum / strokes) : 0) + " W average";
                    break;
                default:
                    float speed = boat.value();
                    double typical = profile.typicalSpeed();
                    if (Math.abs(speed - typical) <= typical * 0.06) {
                        steadySeconds += dt;
                    }
                    progress = (float) Math.min(1, elapsed / 180);
                    live = Math.round(100 * steadySeconds / Math.max(1, elapsed)) + "% steady  ·  hold "
                            + pace(typical) + " /500  ·  " + clock(Math.max(0, 180 - elapsed)) + " left";
                    if (elapsed >= 180) {
                        finish(100 * steadySeconds / 180);
                    }
                    break;
            }
        }

        float cx = w / 2f;
        label(c, "TODAY'S DAILY ROW", cx, dp(40f), 11f, FAINT, Paint.Align.CENTER);
        bold(c, challenge.title, cx, dp(78f), 30f, ACCENT, Paint.Align.CENTER);
        label(c, "TARGET  " + targetText(target), cx, dp(102f), 13f, TEXT, Paint.Align.CENTER);

        float barY = h * 0.34f;
        // A progress ring around a medal, left of the live readout.
        if (phase == Phase.DONE) {
            // A win fills the ring; a miss shows how close it came - a miss must not look like a win.
            double ratio = challenge == Challenge.TRIAL_500 ? target / Math.max(1e-6, result) : result / Math.max(1e-6, target);
            progress = success ? 1f : (float) Math.max(0, Math.min(0.99, ratio));
        }
        ringShown += (progress - ringShown) * Math.min(1f, dt * 4f);
        float rcx = w * 0.17f;
        float rcy = barY + dp(20f);
        float rr = Math.min(dp(70f), h * 0.12f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(12f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x33FFFFFF);
        c.drawCircle(rcx, rcy, rr, paint);
        paint.setColor(phase == Phase.DONE && !success ? WARN : ACCENT);
        c.drawArc(rcx - rr, rcy - rr, rcx + rr, rcy + rr, -90, 360 * ringShown, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        boolean won = phase == Phase.DONE && success;
        Fx.glow(c, rcx, rcy, rr * (1.2f + 0.3f * ringShown), won ? 0x88F5C518 : ((int) (40 + 60 * ringShown) << 24) | 0x35D0BA);
        boolean lost = phase == Phase.DONE && !success;
        paint.setColor(won ? 0xFFF5C518 : lost ? 0xFF4A3F36 : blend(0xFF2A3648, 0xFFB8890B, ringShown));
        c.drawCircle(rcx, rcy, rr * 0.55f, paint);
        paint.setColor(won ? 0xFFFFE28A : lost ? 0xFF7A6A58 : blend(0xFF3A4658, 0xFFF5C518, ringShown));
        shape.rewind();
        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5;
            float r2 = (k & 1) == 0 ? rr * 0.38f : rr * 0.17f;
            float px = rcx + (float) Math.cos(a) * r2;
            float py = rcy + (float) Math.sin(a) * r2;
            if (k == 0) {
                shape.moveTo(px, py);
            } else {
                shape.lineTo(px, py);
            }
        }
        shape.close();
        c.drawPath(shape, paint);
        bold(c, Math.round(ringShown * 100) + "%", rcx, rcy + rr + dp(26f), 14f, TEXT, Paint.Align.CENTER);
        // The flat bar stays for the live readout, now with a moving sheen.
        float barL = w * 0.30f;
        float barR = w * 0.86f;
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, barY, barR, barY + dp(18f), dp(9f), dp(9f), paint);
        paint.setColor(ACCENT);
        c.drawRoundRect(barL, barY, barL + (barR - barL) * progress, barY + dp(18f), dp(9f), dp(9f), paint);
        if (progress > 0.02f) {
            float sheen = barL + (barR - barL) * progress * (float) ((sessionSeconds * 0.6) % 1.0);
            paint.setColor(0x55FFFFFF);
            c.drawRoundRect(sheen - dp(20f), barY + dp(3f), sheen + dp(20f), barY + dp(8f), dp(3f), dp(3f), paint);
        }
        if (won) {
            if (sessionSeconds - doneAt < 3 && Math.random() < 0.8) {
                int[] colors = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};
                fx.spawn((float) Math.random() * w, -dp(10f), (float) (Math.random() - 0.5) * dp(80f),
                        dp(30f), 3f, dp(3.5f), colors[(int) (Math.random() * colors.length)], true);
            }
            // A rubber stamp that thumps down and settles.
            float t = (float) Math.min(1.0, (sessionSeconds - doneAt) * 4);
            float scale = 2.2f - 1.2f * t;
            c.save();
            c.rotate(-8f, w * 0.58f, barY - dp(40f));
            c.scale(scale, scale, w * 0.58f, barY - dp(40f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4f));
            paint.setColor(((int) (255 * t) << 24) | 0xF5C518);
            c.drawRoundRect(w * 0.58f - dp(90f), barY - dp(68f), w * 0.58f + dp(90f), barY - dp(14f), dp(8f), dp(8f), paint);
            paint.setStyle(Paint.Style.FILL);
            bold(c, "DAY WON", w * 0.58f, barY - dp(30f), 26f, ((int) (255 * t) << 24) | 0xF5C518, Paint.Align.CENTER);
            c.restore();
        }
        fx.draw(c);
        String big = phase == Phase.READY ? "ROW TO START" : phase == Phase.DONE
                ? (success ? "DONE  ·  " : "NOT TODAY  ·  ") + targetText(result) : live;
        bold(c, big, cx, barY + dp(58f), phase == Phase.DONE ? 26f : 20f,
                phase == Phase.DONE ? (success ? ACCENT : WARN) : TEXT, Paint.Align.CENTER);
        if (phase == Phase.DONE) {
            label(c, "tap to try again", cx, barY + dp(80f), 10f, FAINT, Paint.Align.CENTER);
        }

        // Calendar: the last 28 days, today on the right.
        int cells = 28;
        float cell = Math.min(dp(40f), (w * 0.8f) / cells);
        float calL = cx - cell * cells / 2f;
        float calY = h * 0.66f;
        for (int i = 0; i < cells; i++) {
            long day = today - (cells - 1 - i);
            boolean done = days.contains(day);
            paint.setColor(done ? ACCENT : day == today ? 0xFF2A3648 : 0xFF141D2A);
            c.drawRoundRect(calL + i * cell + dp(2f), calY, calL + (i + 1) * cell - dp(2f), calY + cell - dp(4f), dp(5f), dp(5f), paint);
            if (done) {
                // A tick on every day you did it.
                float cx0 = calL + (i + 0.5f) * cell;
                float cy0 = calY + (cell - dp(4f)) / 2f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(0xFF08111C);
                c.drawLine(cx0 - cell * 0.2f, cy0, cx0 - cell * 0.05f, cy0 + cell * 0.15f, paint);
                c.drawLine(cx0 - cell * 0.05f, cy0 + cell * 0.15f, cx0 + cell * 0.22f, cy0 - cell * 0.15f, paint);
                paint.setStyle(Paint.Style.FILL);
            } else if (day == today) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 3);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (80 + 150 * pulse) << 24) | 0x35D0BA);
                c.drawRoundRect(calL + i * cell + dp(1f), calY - dp(1f), calL + (i + 1) * cell - dp(1f), calY + cell - dp(3f), dp(6f), dp(6f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        label(c, "LAST 28 DAYS", calL, calY - dp(8f), 9f, FAINT, Paint.Align.LEFT);
        label(c, "TODAY", calL + cells * cell, calY - dp(8f), 9f, FAINT, Paint.Align.RIGHT);
        int streak = streak();
        if (streak > 0) {
            // A flame beside the streak, taller the longer it runs.
            float fxc = w / 2f - dp(150f);
            float fyc = calY + cell + dp(30f);
            float fh = dp(18f) + Math.min(10, streak) * dp(2f);
            float flick = (float) Math.sin(sessionSeconds * 12) * dp(2f);
            Fx.glow(c, fxc, fyc - fh * 0.3f, fh * 1.4f, 0x55FF8A3D);
            shape.rewind();
            shape.moveTo(fxc, fyc - fh + flick);
            shape.quadTo(fxc + fh * 0.6f, fyc - fh * 0.3f, fxc, fyc + dp(4f));
            shape.quadTo(fxc - fh * 0.6f, fyc - fh * 0.3f, fxc, fyc - fh + flick);
            paint.setColor(0xFFFF8A3D);
            c.drawPath(shape, paint);
            shape.rewind();
            shape.moveTo(fxc, fyc - fh * 0.55f - flick);
            shape.quadTo(fxc + fh * 0.3f, fyc - fh * 0.1f, fxc, fyc + dp(2f));
            shape.quadTo(fxc - fh * 0.3f, fyc - fh * 0.1f, fxc, fyc - fh * 0.55f - flick);
            paint.setColor(0xFFFFD36A);
            c.drawPath(shape, paint);
        }
        bold(c, streak + (streak == 1 ? " DAY STREAK" : " DAY STREAK"), cx, calY + cell + dp(40f), 26f,
                streak > 0 ? WARN : DIM, Paint.Align.CENTER);
        label(c, "best streak " + Math.round(bests.get("daily.streak", 0f)) + " days", cx, calY + cell + dp(58f), 10f, FAINT, Paint.Align.CENTER);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private String targetText(double v) {
        switch (challenge) {
            case DISTANCE_4MIN:
                return Math.round(v) + " m";
            case TRIAL_500:
                return clock(v);
            case POWER_10:
                return Math.round(v) + " W";
            default:
                return Math.round(v) + "% steady";
        }
    }
}
