package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * STROKE COACH: the shape of every stroke, against your best one, with one thing to work on.
 *
 * <p>Built on {@link PulseMeter}: each drive's <em>power</em> curve at 100 ms resolution. Nothing else
 * on this machine can show it - the monitor's own readings refresh once a stroke at best.
 *
 * <p>Power, not paddle speed. The first version drew speed, and on the tablet every stroke scored
 * "peak arrives late" with the peak at 100%: a paddle keeps accelerating for as long as the hand
 * pushes harder than the water drags, so its speed always peaks at the very end of the drive. The
 * coaching question - where in the drive the effort peaks - is answered by power. Before energy is
 * measured, the paddle's acceleration is used instead, which peaks at the same moment force does.
 *
 * <p>Per stroke it scores three things, 0-100: how closely the drive's shape matches your best
 * stroke, how near the drive:recovery ratio is to the coaching target of 1:2, and how consistent the
 * last eight strokes were. The best stroke is the most powerful one with a sensible ratio, kept
 * across sessions. One tip at a time, the most important first.
 */
final class StrokeCoachGame extends GameView {

    private static final int SHAPE = 32;
    private static final int RECENT = 8;
    private static final int SCORES = 30;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private final float[] best = new float[SHAPE];
    private boolean hasBest;
    private float bestPower;
    private final float[][] recent = new float[RECENT][SHAPE];
    private int recentCount;
    private final float[] scores = new float[SCORES];
    private int scoreCount;
    private int scoreHead;
    private PulseMeter.Stroke lastSeen;

    private float lastScore;
    private float similarity;
    private float ratioScore;
    private float consistency;
    private float peakPos;
    private String tip = "Row a few strokes - each one is drawn here as you finish it.";
    private int tipColor = DIM;
    private int strokes;
    private float scoreSum;

    StrokeCoachGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        recentCount = 0;
        scoreCount = 0;
        scoreHead = 0;
        strokes = 0;
        scoreSum = 0;
        String saved = bests.getString("coach.best.power");
        hasBest = false;
        if (saved != null) {
            String[] parts = saved.split(",");
            if (parts.length == SHAPE) {
                try {
                    for (int i = 0; i < SHAPE; i++) {
                        best[i] = Float.parseFloat(parts[i]);
                    }
                    hasBest = true;
                    bestPower = bests.get("coach.bestPower", 0f);
                } catch (NumberFormatException ignored) {
                    hasBest = false;
                }
            }
        }
    }

    @Override
    protected void onStop() {
        if (strokes >= 20) {
            bests.recordHighest("coach.score", scoreSum / strokes);
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke == null || stroke == lastSeen) {
            return;
        }
        lastSeen = stroke;
        if (stroke.driveRates.length < 3) {
            return;
        }
        analyse(stroke, s);
    }

    /** The effort through the drive: power when measured, else acceleration of the paddle. */
    private static float[] effortCurve(PulseMeter.Stroke stroke) {
        if (stroke.drivePower != null && stroke.drivePower.length == stroke.driveRates.length) {
            return stroke.drivePower;
        }
        float[] r = stroke.driveRates;
        float[] a = new float[r.length];
        for (int i = 0; i < r.length; i++) {
            float before = i > 0 ? r[i - 1] : r[i];
            float after = i + 1 < r.length ? r[i + 1] : r[i];
            a[i] = Math.max(0f, after - before);
        }
        return a;
    }

    private void analyse(PulseMeter.Stroke stroke, S4Protocol.Status s) {
        float[] shape = resample(effortCurve(stroke));
        int peakIndex = 0;
        for (int i = 1; i < SHAPE; i++) {
            if (shape[i] > shape[peakIndex]) {
                peakIndex = i;
            }
        }
        peakPos = peakIndex / (SHAPE - 1f);
        float ratio = stroke.driveSeconds > 0 ? (float) (stroke.recoverySeconds / stroke.driveSeconds) : 0f;
        float power = !Double.isNaN(stroke.averagePowerW) ? (float) stroke.averagePowerW : s.watts;

        System.arraycopy(recent, 0, recent, 1, RECENT - 1);
        recent[0] = shape;
        recentCount = Math.min(RECENT, recentCount + 1);

        similarity = hasBest ? 100f * (1f - meanAbs(shape, best) * 1.6f) : 70f;
        ratioScore = 100f - Math.min(100f, Math.abs(ratio - 2f) * 60f);
        consistency = 100f;
        if (recentCount >= 3) {
            float[] avg = new float[SHAPE];
            for (int k = 0; k < recentCount; k++) {
                for (int i = 0; i < SHAPE; i++) {
                    avg[i] += recent[k][i] / recentCount;
                }
            }
            float dev = 0f;
            for (int k = 0; k < recentCount; k++) {
                dev += meanAbs(recent[k], avg);
            }
            consistency = 100f * (1f - dev / recentCount * 3f);
        }
        similarity = clamp100(similarity);
        consistency = clamp100(consistency);
        lastScore = 0.4f * similarity + 0.3f * ratioScore + 0.3f * consistency;

        scores[scoreHead] = lastScore;
        scoreHead = (scoreHead + 1) % SCORES;
        scoreCount = Math.min(SCORES, scoreCount + 1);
        strokes++;
        scoreSum += lastScore;

        if (ratio >= 1.3f && ratio <= 3.5f && power > bestPower) {
            bestPower = power;
            System.arraycopy(shape, 0, best, 0, SHAPE);
            hasBest = true;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < SHAPE; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format(java.util.Locale.US, "%.3f", best[i]));
            }
            bests.putString("coach.best.power", sb.toString());
            bests.putFloat("coach.bestPower", bestPower);
        }

        if (peakPos > 0.62f) {
            tip = "Peak arrives late - drive with the legs first, then the back and arms.";
            tipColor = WARN;
        } else if (peakPos < 0.22f) {
            tip = "Peak comes too early - keep pushing all the way through the finish.";
            tipColor = WARN;
        } else if (ratio > 0 && ratio < 1.5f) {
            tip = String.format(java.util.Locale.US, "Rushing the recovery (1:%.1f) - slow the slide back toward 1:2.", ratio);
            tipColor = WARN;
        } else if (ratio > 3.2f) {
            tip = "A long pause between strokes - keep the boat moving.";
            tipColor = WARN;
        } else if (consistency < 70f) {
            tip = "Strokes are varying - settle into a rhythm and repeat the same stroke.";
            tipColor = WARN;
        } else {
            tip = "Smooth, consistent stroke. Hold it.";
            tipColor = ACCENT;
        }
    }

    private static float[] resample(float[] rates) {
        float[] out = new float[SHAPE];
        float max = 1f;
        for (float v : rates) {
            max = Math.max(max, v);
        }
        for (int i = 0; i < SHAPE; i++) {
            float f = i / (SHAPE - 1f) * (rates.length - 1);
            int a = (int) Math.floor(f);
            int b = Math.min(rates.length - 1, a + 1);
            float t = f - a;
            out[i] = (rates[a] + (rates[b] - rates[a]) * t) / max;
        }
        return out;
    }

    private static float meanAbs(float[] a, float[] b) {
        float sum = 0f;
        for (int i = 0; i < SHAPE; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return sum / SHAPE;
    }

    private static float clamp100(float v) {
        return Math.max(0f, Math.min(100f, v));
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        paint.setShader(null);
        paint.setColor(0xFF070D16);
        c.drawRect(0, 0, w, h, paint);

        // Chart: best stroke as a faint fill, the last three as lines, the newest brightest.
        float cl = dp(24f);
        float ct = dp(40f);
        float cr = w * 0.60f;
        float cb = h * 0.70f;
        paint.setColor(0xFF0D1420);
        c.drawRoundRect(cl - dp(12f), ct - dp(28f), cr + dp(12f), cb + dp(34f), dp(14f), dp(14f), paint);
        label(c, "DRIVE SHAPE  ·  your power from catch to finish", cl, ct - dp(8f), 10f, FAINT, Paint.Align.LEFT);
        paint.setColor(0x2235D0BA);
        c.drawRect(cl + (cr - cl) * 0.30f, ct, cl + (cr - cl) * 0.55f, cb, paint);
        label(c, "IDEAL PEAK", cl + (cr - cl) * 0.425f, cb + dp(14f), 9f, ACCENT, Paint.Align.CENTER);
        label(c, "CATCH", cl, cb + dp(14f), 9f, FAINT, Paint.Align.LEFT);
        label(c, "FINISH", cr, cb + dp(14f), 9f, FAINT, Paint.Align.RIGHT);
        if (hasBest) {
            path.rewind();
            path.moveTo(cl, cb);
            for (int i = 0; i < SHAPE; i++) {
                path.lineTo(cl + (cr - cl) * i / (SHAPE - 1f), cb - (cb - ct) * best[i]);
            }
            path.lineTo(cr, cb);
            path.close();
            paint.setColor(0x336F8CFF);
            c.drawPath(path, paint);
            label(c, "YOUR BEST STROKE", cr - dp(4f), ct + dp(14f), 9f, BLUE, Paint.Align.RIGHT);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        for (int k = Math.min(3, recentCount) - 1; k >= 0; k--) {
            path.rewind();
            for (int i = 0; i < SHAPE; i++) {
                float x = cl + (cr - cl) * i / (SHAPE - 1f);
                float y = cb - (cb - ct) * recent[k][i];
                if (i == 0) {
                    path.moveTo(x, y);
                } else {
                    path.lineTo(x, y);
                }
            }
            paint.setColor(ACCENT);
            paint.setAlpha(k == 0 ? 255 : 90 - k * 20);
            paint.setStrokeWidth(dp(k == 0 ? 4f : 2f));
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);

        // Score and tip.
        float rx = w * 0.64f;
        bold(c, strokes > 0 ? String.valueOf(Math.round(lastScore)) : "--", rx, dp(90f), 64f,
                lastScore >= 80 ? ACCENT : lastScore >= 60 ? WARN : BAD, Paint.Align.LEFT);
        label(c, "STROKE SCORE", rx, dp(110f), 10f, FAINT, Paint.Align.LEFT);
        wrap(c, tip, rx, dp(146f), w - rx - dp(20f), 15f, tipColor);

        PulseMeter.Stroke s = status == null ? null : status.meter.lastStroke;
        float gy = h * 0.38f;
        float col = (w - rx - dp(20f)) / 2f;
        metric(c, rx, gy, strokes > 0 ? Math.round(similarity) + "%" : "--", "SHAPE MATCH");
        metric(c, rx + col, gy, strokes > 0 ? Math.round(consistency) + "%" : "--", "CONSISTENCY");
        metric(c, rx, gy + dp(64f), s != null ? String.format(java.util.Locale.US, "1:%.1f", s.driveSeconds > 0 ? s.recoverySeconds / s.driveSeconds : 0) : "--", "RATIO (AIM 1:2)");
        metric(c, rx + col, gy + dp(64f), strokes > 0 ? Math.round(peakPos * 100) + "%" : "--", "POWER PEAKS AT (AIM 30-55%)");
        metric(c, rx, gy + dp(128f), s != null ? String.format(java.util.Locale.US, "%.2f s", s.driveSeconds) : "--", "DRIVE");
        metric(c, rx + col, gy + dp(128f), s != null && !Double.isNaN(s.averagePowerW) ? Math.round(s.averagePowerW) + " W" : "--", "POWER");
        String extra = s != null && !Double.isNaN(s.peakForceN) ? Math.round(s.peakForceN / 9.80665) + " kg" : "--";
        metric(c, rx, gy + dp(192f), extra, "PEAK FORCE");
        String length = s != null && !Double.isNaN(s.driveLengthM) ? Math.round(s.driveLengthM * 100) + " cm" : "--";
        metric(c, rx + col, gy + dp(192f), length, "DRIVE LENGTH");
        if (hasSteering()) {
            float roll = steering() * 35f;
            metric(c, rx, gy + dp(256f), String.format(java.util.Locale.US, "%+.0f°", roll),
                    Math.abs(roll) > 6 ? "HANDLE NOT LEVEL" : "HANDLE LEVEL");
        } else if (s != null && Double.isNaN(s.peakForceN)) {
            label(c, "Calibrate (home screen) for force and drive length.", rx, gy + dp(250f), 10f, FAINT, Paint.Align.LEFT);
        }

        // Score history.
        float bt = h * 0.80f;
        float bb = h - dp(12f);
        label(c, "LAST " + SCORES + " STROKES", cl, bt - dp(6f), 9f, FAINT, Paint.Align.LEFT);
        float slot = (w - 2 * cl) / SCORES;
        for (int i = 0; i < scoreCount; i++) {
            int idx = (scoreHead - scoreCount + i + SCORES) % SCORES;
            float v = scores[idx];
            paint.setColor(v >= 80 ? ACCENT : v >= 60 ? WARN : BAD);
            float x = cl + (SCORES - scoreCount + i) * slot;
            c.drawRect(x + slot * 0.15f, bb - (bb - bt) * v / 100f, x + slot * 0.85f, bb, paint);
        }
        if (bests.has("coach.score")) {
            label(c, "best session average " + Math.round(bests.get("coach.score", 0f)), w - cl, bt - dp(6f), 9f, FAINT, Paint.Align.RIGHT);
        }
    }

    private void metric(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y, 24f, TEXT, Paint.Align.LEFT);
        label(c, caption, x, y + dp(16f), 9f, FAINT, Paint.Align.LEFT);
    }

    private void wrap(Canvas c, String text, float x, float y, float width, float sizeDp, int color) {
        textPaint.setTextSize(dp(sizeDp));
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        float ly = y;
        for (String word : words) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (textPaint.measureText(trial) > width && line.length() > 0) {
                bold(c, line.toString(), x, ly, sizeDp, color, Paint.Align.LEFT);
                ly += dp(sizeDp) * 1.35f;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(trial);
            }
        }
        bold(c, line.toString(), x, ly, sizeDp, color, Paint.Align.LEFT);
    }
}
