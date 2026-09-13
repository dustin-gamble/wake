package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The vitals bar that sits above every game: stopwatch plus the numbers that matter, with small
 * arc gauges for speed and power so they can be read at a glance without looking away from play.
 *
 * <p>Fed the same coasted speed the games use, so the needle behaves identically to the full
 * gauge screen - it rises with the drive and decays under water drag rather than stepping once a
 * second. See CLAUDE.md, "the decay IS the instrument".
 */
final class GaugeStripView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();

    private static final int ACCENT = Color.parseColor("#35D0BA");
    private static final int BLUE = Color.parseColor("#6F8CFF");
    private static final int WARN = Color.parseColor("#F0B132");
    private static final int TEXT = Color.parseColor("#E6EDF7");
    private static final int FAINT = Color.parseColor("#5D6B80");
    private static final int TRACK = Color.parseColor("#18202C");

    private S4Protocol.Status status;
    private float speed;
    private double clockSeconds;
    private boolean clockRunning;
    private boolean clockStarted;

    GaugeStripView(Context context) {
        super(context);
        setBackgroundColor(Color.parseColor("#111722"));
    }

    void update(S4Protocol.Status s, float coastedSpeed) {
        this.status = s;
        this.speed = coastedSpeed;
        postInvalidateOnAnimation();
    }

    void setClock(double seconds, boolean running, boolean started) {
        this.clockSeconds = seconds;
        this.clockRunning = running;
        this.clockStarted = started;
        postInvalidateOnAnimation();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        // Averaged for the same reason as the gauges screen: an isolated dip in 1A9 reads
        // as a missed stroke. Raw stays in diagnostics.
        int spm = status == null ? 0 : status.strokeRateAverage;
        int strokes = status == null ? 0 : status.strokes;
        int dist = status == null ? 0 : (status.distanceMeters > 0
                ? status.distanceMeters : status.derivedDistanceMeters);

        // Stopwatch takes the left, then two arc gauges, then plain figures.
        float x = dp(14f);
        float clockW = dp(168f);
        drawClock(c, x, h, clockW);
        x += clockW;

        float gaugeW = dp(96f);
        drawArcGauge(c, x, h, gaugeW, speed / 5f, String.format(java.util.Locale.US, "%.1f", speed),
                "m/s", ACCENT);
        x += gaugeW;
        drawArcGauge(c, x, h, gaugeW, watts / 280f, String.valueOf(watts), "watts", BLUE);
        x += gaugeW + dp(6f);

        // Remaining width split between the read-only figures.
        float rest = w - x - dp(10f);
        float cell = rest / 4f;
        drawFigure(c, x + cell * 0.5f, h, pace(speed), "PACE /500", WARN);
        drawFigure(c, x + cell * 1.5f, h, String.valueOf(spm), "STROKES/MIN", TEXT);
        drawFigure(c, x + cell * 2.5f, h, dist + " m", "DISTANCE", TEXT);
        drawFigure(c, x + cell * 3.5f, h, String.valueOf(strokes), "STROKES", TEXT);

        paint.setColor(Color.parseColor("#212B3B"));
        c.drawRect(0, h - dp(1f), w, h, paint);
    }

    private void drawClock(Canvas c, float x, float h, float width) {
        int col = clockRunning ? ACCENT : clockStarted ? WARN : FAINT;
        paint.setColor(col);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(52f));
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        c.drawText(PersonalBests.formatTime((float) clockSeconds), x, h * 0.60f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(10f));
        paint.setColor(FAINT);
        c.drawText(clockStarted && !clockRunning ? "PAUSED - ROW TO RESUME"
                : clockStarted ? "ROWING TIME" : "STARTS ON FIRST STROKE",
                x, h * 0.60f + dp(13f), paint);
    }

    /** A 180-degree arc with a needle; compact enough to read peripherally. */
    private void drawArcGauge(Canvas c, float x, float h, float width, float fraction, String value,
                              String label, int color) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        float cx = x + width / 2f;
        float cy = h * 0.66f;
        float r = Math.min(width * 0.40f, h * 0.34f);
        float stroke = r * 0.30f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(stroke);
        arc.set(cx - r, cy - r, cx + r, cy + r);
        paint.setColor(TRACK);
        c.drawArc(arc, 180, 180, false, paint);
        paint.setColor(color);
        c.drawArc(arc, 180, 180 * fraction, false, paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextSize(dp(21f));
        paint.setColor(TEXT);
        c.drawText(value, cx, cy - dp(1f), paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(10f));
        paint.setColor(FAINT);
        c.drawText(label, cx, cy + dp(12f), paint);
    }

    private void drawFigure(Canvas c, float cx, float h, String value, String label, int color) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setFakeBoldText(true);
        paint.setTypeface(android.graphics.Typeface.MONOSPACE);
        paint.setTextSize(dp(25f));
        paint.setColor(color);
        c.drawText(value, cx, h * 0.55f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(10f));
        paint.setColor(FAINT);
        c.drawText(label, cx, h * 0.55f + dp(13f), paint);
    }

    private static String pace(float mps) {
        if (mps < 0.5f) {
            return "--:--";
        }
        return PersonalBests.formatPace(500f / mps);
    }
}
