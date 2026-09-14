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
        paint.setColor(0xFF08111C);
        c.drawRect(0, 0, w, h, paint);
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

        float barL = w * 0.18f;
        float barR = w * 0.82f;
        float barY = h * 0.34f;
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, barY, barR, barY + dp(18f), dp(9f), dp(9f), paint);
        paint.setColor(ACCENT);
        c.drawRoundRect(barL, barY, barL + (barR - barL) * progress, barY + dp(18f), dp(9f), dp(9f), paint);
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
        }
        label(c, "LAST 28 DAYS", calL, calY - dp(8f), 9f, FAINT, Paint.Align.LEFT);
        label(c, "TODAY", calL + cells * cell, calY - dp(8f), 9f, FAINT, Paint.Align.RIGHT);
        int streak = streak();
        bold(c, streak + (streak == 1 ? " DAY STREAK" : " DAY STREAK"), cx, calY + cell + dp(40f), 26f,
                streak > 0 ? WARN : DIM, Paint.Align.CENTER);
        label(c, "best streak " + Math.round(bests.get("daily.streak", 0f)) + " days", cx, calY + cell + dp(58f), 10f, FAINT, Paint.Align.CENTER);
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
