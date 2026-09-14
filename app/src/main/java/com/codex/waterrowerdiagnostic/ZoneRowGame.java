package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;

/**
 * ZONE ROW: a timed piece read off two lane bars, the whole screen one instrument.
 *
 * <p>Layout follows a commercial rowing workout screen the rower likes - elapsed / target /
 * remaining across the top, a split bar across four effort zones, a stroke-rate bar with a
 * target band, and a row of figures along the bottom - with WAKE's own names, colours and zone
 * scale. Deliberately not a copy: nothing here uses another product's name, marks or type.
 *
 * <p>Zones are fixed split ranges centred on this rower's measured envelope (median 2:08/500m,
 * best 1:59): GLIDE above 2:35, CRUISE to 2:15, PUSH to 1:59, SURGE beyond. Each zone gets an
 * equal quarter of the bar, so a pace in the middle of a zone sits in the middle of its quarter.
 * Tap a zone name to make it the target; the rate bar's band and the time-in-zone clock follow.
 *
 * <p>Shown without the vitals strip, since it is the vitals. Calories come from {@link PulseMeter}'s
 * work at a 25% muscle efficiency: measured from the paddle's pulses, and absolute once the rower
 * has done the load-scale calibration (the label then reads MEASURED).
 */
final class ZoneRowGame extends GameView {

    static final String[] ZONES = {"GLIDE", "CRUISE", "PUSH", "SURGE"};
    private static final int[] ZONE_COLORS = {0xFF6F8CFF, 0xFF35D0BA, 0xFFF0B132, 0xFFF0655D};
    /** Zone edges in seconds per 500 m, slowest first: the bar runs from 3:00 to 1:45. */
    private static final float[] EDGES = {180f, 155f, 135f, 119f, 105f};
    /** Stroke-rate target band per zone, strokes per minute. */
    private static final float[][] RATE_BANDS = {{16f, 20f}, {20f, 24f}, {24f, 28f}, {28f, 34f}};
    private static final float RATE_MAX = 45f;
    private static final int[] PIECE_MINUTES = {10, 20, 30, 5};

    private static final int TRACK = 0xFF13233F;
    private static final int DIVIDER = 0xFF1F355A;
    private static final int PILL = 0xFF10203A;
    private static final int PILL_EDGE = 0xFF2A3F63;
    private static final int TRACE = 0xFFFF8A7A;

    private final PersonalBests bests;
    private int pieceIndex;
    private int targetZone = 2;

    private double pieceSeconds;
    private double pieceStartMeters;
    private double finalMeters = -1;
    private double joules;
    /** The meter's session work at the last frame, so only work done during the piece counts. */
    private double lastMeterWork = -1;
    private boolean energyMeasured;
    private double inZoneSeconds;
    private boolean paused;
    private boolean finished;
    private boolean newBest;

    private float shownSplitFrac;
    private float shownPace;
    private float shownRate;
    private float shownWatts;

    /** Three seconds of pulse effort: the shape of the last stroke or two, beside the clock. */
    private final float[] trace = new float[90];
    private int traceHead;
    private float traceClock;

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path path = new Path();
    private final RectF restartHit = new RectF();
    private final RectF pauseHit = new RectF();
    private final Typeface numbers = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface labels = Typeface.create("sans-serif-condensed", Typeface.NORMAL);

    private float shaderW = -1f;
    private float shaderH = -1f;
    private Shader background;
    private Shader splitGradient;
    private Shader rateGradient;

    /** Layout kept from the last frame for touch handling. */
    private float x0;
    private float span;
    private float zoneTop;
    private float zoneBottom;

    ZoneRowGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    int pieceMinutes() {
        return PIECE_MINUTES[pieceIndex];
    }

    void nextPieceLength() {
        pieceIndex = (pieceIndex + 1) % PIECE_MINUTES.length;
        restart();
    }

    private double pieceLength() {
        return pieceMinutes() * 60.0;
    }

    @Override
    protected void onStart() {
        restart();
    }

    private void restart() {
        pieceSeconds = 0;
        pieceStartMeters = sessionMeters;
        finalMeters = -1;
        joules = 0;
        lastMeterWork = -1;
        inZoneSeconds = 0;
        paused = false;
        finished = false;
        newBest = false;
        postInvalidateOnAnimation();
    }

    private double pieceMeters() {
        return finalMeters >= 0 ? finalMeters : Math.max(0, sessionMeters - pieceStartMeters);
    }

    /** Where a split sits along the bar, 0 (3:00 or slower) to 1 (1:45 or faster). */
    static float splitFraction(float secondsPer500) {
        if (secondsPer500 >= EDGES[0]) {
            return 0f;
        }
        if (secondsPer500 <= EDGES[EDGES.length - 1]) {
            return 1f;
        }
        for (int i = 0; i < 4; i++) {
            if (secondsPer500 <= EDGES[i] && secondsPer500 >= EDGES[i + 1]) {
                return (i + (EDGES[i] - secondsPer500) / (EDGES[i] - EDGES[i + 1])) / 4f;
            }
        }
        return 0f;
    }

    private static int zoneAt(float fraction) {
        return Math.max(0, Math.min(3, (int) (fraction * 4f)));
    }

    /* ---------- state ---------- */

    private void advance(float dt) {
        float speed = boat.value();
        float pace = speed >= 0.5f ? 500f / speed : 0f;
        int zone = pace > 0f ? zoneAt(splitFraction(pace)) : -1;

        // Work from the pulse meter: measured from the paddle, calibrated if the rower has done the
        // load-scale test. Counted only while the piece is running.
        double meterWork = status == null ? -1 : status.meter.workJoules;
        double workStep = lastMeterWork >= 0 && meterWork >= lastMeterWork ? meterWork - lastMeterWork : 0;
        lastMeterWork = meterWork;
        energyMeasured = status != null && status.meter.source == PulseMeter.EnergySource.PULSES_CALIBRATED;
        if (!paused && !finished && isClockRunning()) {
            pieceSeconds += dt;
            joules += workStep;
            if (zone == targetZone) {
                inZoneSeconds += dt;
            }
            if (pieceSeconds >= pieceLength()) {
                pieceSeconds = pieceLength();
                finished = true;
                finalMeters = Math.max(0, sessionMeters - pieceStartMeters);
                newBest = bests.recordHighest("zonerow." + pieceMinutes(), (float) finalMeters);
            }
        }

        float ease = Math.min(1f, 3f * dt);
        shownSplitFrac += ((pace > 0f ? splitFraction(pace) : 0f) - shownSplitFrac) * ease;
        shownPace = pace <= 0f ? 0f : shownPace <= 0f ? pace : shownPace + (pace - shownPace) * ease;
        float rate = status == null ? 0f : (float) status.strokeRatePrecise;
        shownRate += (rate - shownRate) * ease;
        float watts = status == null ? 0f : status.watts;
        shownWatts += (watts - shownWatts) * ease;

        traceClock += dt;
        while (traceClock >= 1f / 30f) {
            traceClock -= 1f / 30f;
            trace[traceHead] = status == null ? 0f : (float) status.pulseEffort;
            traceHead = (traceHead + 1) % trace.length;
        }
    }

    /* ---------- drawing ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        advance(dt);

        float padX = w * 0.035f;
        x0 = padX;
        span = w - 2f * padX;
        float x1 = x0 + span;
        ensureShaders(w, h, x0, x1);

        fill.setShader(background);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        drawProgress(c, w);
        drawHeader(c, w, h, x0, x1);

        zoneTop = h * 0.19f;
        zoneBottom = h * 0.30f;
        float lanesTop = h * 0.21f;
        float lanesBottom = h * 0.745f;
        int currentZone = shownPace > 0f ? zoneAt(shownSplitFrac) : -1;

        // The target quarter is tinted all the way down, so both bars read against it.
        fill.setColor(ZONE_COLORS[targetZone]);
        fill.setAlpha(22);
        c.drawRect(x0 + span * targetZone / 4f, lanesTop, x0 + span * (targetZone + 1) / 4f,
                lanesBottom, fill);
        fill.setAlpha(255);

        line.setShader(null);
        line.setColor(DIVIDER);
        line.setStrokeWidth(dp(1.5f));
        for (int i = 1; i < 4; i++) {
            float x = x0 + span * i / 4f;
            c.drawLine(x, lanesTop, x, lanesBottom, line);
        }
        for (int i = 0; i < 4; i++) {
            float cx = x0 + span * (i + 0.5f) / 4f;
            int color = i == currentZone ? ZONE_COLORS[i] : 0xFF5D6B80;
            text(c, ZONES[i], cx, h * 0.265f, h * 0.034f, color, Paint.Align.CENTER,
                    i == currentZone ? numbers : labels, 0.3f);
            if (i == targetZone) {
                fill.setColor(ZONE_COLORS[i]);
                rect.set(cx - dp(22f), h * 0.283f, cx + dp(22f), h * 0.283f + dp(3f));
                c.drawRoundRect(rect, dp(2f), dp(2f), fill);
            }
        }

        drawSplitBar(c, h, x0, x1, currentZone);
        drawRateBar(c, h, x0, x1);
        drawStats(c, w, h, x0, x1, currentZone);

        if (finished) {
            drawFinished(c, w, h);
        }
    }

    private void ensureShaders(float w, float h, float left, float right) {
        if (w == shaderW && h == shaderH) {
            return;
        }
        shaderW = w;
        shaderH = h;
        background = new LinearGradient(0, 0, 0, h, 0xFF10284A, 0xFF050D19, Shader.TileMode.CLAMP);
        splitGradient = new LinearGradient(left, 0, right, 0, ZONE_COLORS,
                new float[]{0f, 0.36f, 0.64f, 1f}, Shader.TileMode.CLAMP);
        rateGradient = new LinearGradient(left, 0, right, 0, 0xFF3A5BD9, 0xFF7FC6EE,
                Shader.TileMode.CLAMP);
    }

    private void drawProgress(Canvas c, float w) {
        float frac = (float) Math.min(1.0, pieceSeconds / pieceLength());
        fill.setColor(0xFF16294A);
        c.drawRect(0, 0, w, dp(4f), fill);
        fill.setColor(ACCENT);
        c.drawRect(0, 0, w * frac, dp(4f), fill);
    }

    private void drawHeader(Canvas c, float w, float h, float left, float right) {
        float labelY = h * 0.065f;
        float valueY = h * 0.145f;
        float labelSize = h * 0.026f;
        float valueSize = h * 0.075f;

        text(c, "ELAPSED", left, labelY, labelSize, FAINT, Paint.Align.LEFT, labels, 0.25f);
        text(c, clock(pieceSeconds), left, valueY, valueSize, TEXT, Paint.Align.LEFT, numbers, 0.02f);

        // Live pulse effort beside the label: every stroke draws its own little peak.
        ink.setTypeface(labels);
        ink.setTextSize(labelSize);
        ink.setLetterSpacing(0.25f);
        float tx = left + ink.measureText("ELAPSED") + dp(16f);
        float tw = dp(120f);
        float th = h * 0.04f;
        float base = labelY + dp(2f);
        path.rewind();
        for (int i = 0; i < trace.length; i++) {
            float v = Math.min(1f, trace[(traceHead + i) % trace.length]);
            float x = tx + tw * i / (trace.length - 1f);
            float y = base - th * v;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        line.setShader(null);
        line.setColor(TRACE);
        line.setStrokeWidth(dp(1.6f));
        c.drawPath(path, line);

        float cx = w / 2f;
        if (!hasClockStarted()) {
            text(c, "TAP A ZONE TO SET YOUR TARGET", cx, labelY, labelSize, FAINT, Paint.Align.CENTER, labels, 0.25f);
            text(c, "ROW TO START", cx, valueY, valueSize * 0.7f, ACCENT, Paint.Align.CENTER, numbers, 0.12f);
        } else {
            text(c, "IN " + ZONES[targetZone], cx, labelY, labelSize, ZONE_COLORS[targetZone],
                    Paint.Align.CENTER, numbers, 0.25f);
            text(c, clock(inZoneSeconds), cx, valueY, valueSize, TEXT, Paint.Align.CENTER, numbers, 0.02f);
        }

        text(c, paused ? "PAUSED" : "REMAINING", right, labelY, labelSize, paused ? WARN : FAINT,
                Paint.Align.RIGHT, paused ? numbers : labels, 0.25f);
        text(c, clock(Math.max(0, pieceLength() - pieceSeconds)), right, valueY, valueSize, TEXT,
                Paint.Align.RIGHT, numbers, 0.02f);
    }

    private void drawSplitBar(Canvas c, float h, float left, float right, int zone) {
        float top = h * 0.415f;
        float bottom = top + h * 0.075f;
        float radius = (bottom - top) / 2f;
        float fillX = left + (right - left) * shownSplitFrac;

        rect.set(left, top, right, bottom);
        fill.setColor(TRACK);
        c.drawRoundRect(rect, radius, radius, fill);
        // The whole bar carries its colours faintly, so the zone ahead is always visible.
        fill.setShader(splitGradient);
        fill.setAlpha(50);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setAlpha(255);
        if (fillX > left + 1f) {
            c.save();
            c.clipRect(left, top - dp(2f), fillX, bottom + dp(2f));
            c.drawRoundRect(rect, radius, radius, fill);
            c.restore();
        }
        fill.setShader(null);

        if (shownPace > 0f) {
            fill.setColor(TEXT);
            c.drawRect(fillX - dp(2f), top - dp(12f), fillX + dp(2f), bottom + dp(12f), fill);
        }

        String split = shownPace > 0f ? PersonalBests.formatPace(shownPace) : "--:--";
        float bigSize = h * 0.14f;
        ink.setTypeface(numbers);
        ink.setTextSize(bigSize);
        ink.setLetterSpacing(0.02f);
        float splitW = ink.measureText(split);
        ink.setTypeface(labels);
        ink.setTextSize(h * 0.03f);
        ink.setLetterSpacing(0.15f);
        float unitW = ink.measureText("/500M") + dp(8f);
        float cx = Math.max(left + splitW / 2f, Math.min(right - splitW / 2f - unitW, fillX));
        float baseline = top - dp(20f);
        text(c, split, cx, baseline, bigSize, zone >= 0 ? TEXT : FAINT, Paint.Align.CENTER, numbers, 0.02f);
        text(c, "/500M", cx + splitW / 2f + dp(8f), baseline - bigSize * 0.52f, h * 0.03f, FAINT,
                Paint.Align.LEFT, labels, 0.15f);
    }

    private void drawRateBar(Canvas c, float h, float left, float right) {
        float top = h * 0.585f;
        float bottom = top + h * 0.05f;
        float radius = (bottom - top) / 2f;
        float scale = (right - left) / RATE_MAX;
        float rateX = left + Math.min(RATE_MAX, Math.max(0f, shownRate)) * scale;
        float[] band = RATE_BANDS[targetZone];
        boolean inBand = shownRate >= band[0] && shownRate <= band[1];

        rect.set(left, top, right, bottom);
        fill.setColor(TRACK);
        c.drawRoundRect(rect, radius, radius, fill);

        rect.set(left + band[0] * scale, top - dp(7f), left + band[1] * scale, bottom + dp(7f));
        fill.setColor(0x553A5BD9);
        c.drawRoundRect(rect, dp(4f), dp(4f), fill);
        line.setShader(null);
        line.setColor(0xAA6F8CFF);
        line.setStrokeWidth(dp(1.5f));
        c.drawRoundRect(rect, dp(4f), dp(4f), line);

        if (rateX > left + 1f) {
            rect.set(left, top, right, bottom);
            fill.setShader(rateGradient);
            c.save();
            c.clipRect(left, top - dp(2f), rateX, bottom + dp(2f));
            c.drawRoundRect(rect, radius, radius, fill);
            c.restore();
            fill.setShader(null);
            fill.setColor(inBand ? ACCENT : TEXT);
            c.drawRect(rateX - dp(2f), top - dp(10f), rateX + dp(2f), bottom + dp(10f), fill);
        }

        String rate = shownRate >= 1f ? String.format(java.util.Locale.US, "%.1f", shownRate) : "--";
        float bigSize = h * 0.1f;
        ink.setTypeface(numbers);
        ink.setTextSize(bigSize);
        ink.setLetterSpacing(0.02f);
        float rateW = ink.measureText(rate);
        float unitW = dp(70f);
        float cx = Math.max(left + rateW / 2f, Math.min(right - rateW / 2f - unitW, rateX));
        float baseline = bottom + h * 0.125f;
        text(c, rate, cx, baseline, bigSize, inBand ? ACCENT : TEXT, Paint.Align.CENTER, numbers, 0.02f);
        text(c, "SPM", cx + rateW / 2f + dp(10f), baseline, h * 0.032f, FAINT, Paint.Align.LEFT, labels, 0.2f);
    }

    private void drawStats(Canvas c, float w, float h, float left, float right, int zone) {
        float rowTop = h * 0.80f;
        float rowBottom = h * 0.98f;
        float size = Math.min(rowBottom - rowTop, dp(88f));
        float cy = (rowTop + rowBottom) / 2f;

        line.setShader(null);
        line.setColor(DIVIDER);
        line.setStrokeWidth(dp(1f));
        c.drawLine(left, rowTop - dp(6f), right, rowTop - dp(6f), line);

        pill(c, left, cy, size, restartHit);
        drawRestartIcon(c, left + size / 2f, cy, size * 0.22f);
        pill(c, right - size, cy, size, pauseHit);
        drawPauseIcon(c, right - size / 2f, cy, size * 0.2f);

        double meters = pieceMeters();
        String avgSplit = pieceSeconds > 5 && meters > 5
                ? PersonalBests.formatPace((float) (pieceSeconds * 500.0 / meters)) : "--:--";
        int heart = status == null ? 0 : status.heartRate;
        long kcal = Math.round(PulseMeter.kcalForWork(joules));

        String[] values = {
                heart > 0 ? String.valueOf(heart) : "--",
                String.valueOf(Math.round(shownWatts)),
                shownRate >= 1f ? String.valueOf(Math.round(shownRate)) : "--",
                shownPace > 0f ? PersonalBests.formatPace(shownPace) : "--:--",
                avgSplit,
                String.valueOf(Math.round(meters)),
                String.valueOf(kcal)
        };
        String[] names = {"HEART  BPM", "POWER  W", "RATE  SPM", "SPLIT  /500M", "AVG  /500M",
                "METERS", energyMeasured ? "KCAL  MEASURED" : "KCAL  EST"};

        float statsLeft = left + size + dp(28f);
        float statsRight = right - size - dp(28f);
        float col = (statsRight - statsLeft) / values.length;
        float valueY = cy + h * 0.012f;
        float nameY = valueY + h * 0.045f;
        for (int i = 0; i < values.length; i++) {
            float x = statsLeft + col * (i + 0.5f);
            boolean isSplit = i == 3;
            int color = isSplit && zone >= 0 ? ZONE_COLORS[zone] : TEXT;
            text(c, values[i], x, valueY, h * 0.068f, color, Paint.Align.CENTER, numbers, 0.02f);
            text(c, names[i], x, nameY, h * 0.022f, isSplit && zone >= 0 ? ZONE_COLORS[zone] : FAINT,
                    Paint.Align.CENTER, labels, 0.18f);
            if (isSplit) {
                // Four ticks under the split: which zone it is in, at a glance.
                float tick = dp(14f);
                float gap = dp(4f);
                float start = x - (4 * tick + 3 * gap) / 2f;
                for (int z = 0; z < 4; z++) {
                    fill.setColor(ZONE_COLORS[z]);
                    fill.setAlpha(z == zone ? 255 : 45);
                    rect.set(start + z * (tick + gap), nameY + dp(8f),
                            start + z * (tick + gap) + tick, nameY + dp(12f));
                    c.drawRoundRect(rect, dp(2f), dp(2f), fill);
                }
                fill.setAlpha(255);
            }
        }
    }

    private void pill(Canvas c, float left, float cy, float size, RectF hit) {
        rect.set(left, cy - size / 2f, left + size, cy + size / 2f);
        hit.set(rect.left - dp(12f), rect.top - dp(12f), rect.right + dp(12f), rect.bottom + dp(12f));
        fill.setColor(PILL);
        c.drawRoundRect(rect, size * 0.3f, size * 0.3f, fill);
        line.setShader(null);
        line.setColor(PILL_EDGE);
        line.setStrokeWidth(dp(1.5f));
        c.drawRoundRect(rect, size * 0.3f, size * 0.3f, line);
    }

    private void drawRestartIcon(Canvas c, float cx, float cy, float r) {
        line.setColor(TEXT);
        line.setStrokeWidth(dp(3f));
        rect.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(rect, -60f, 300f, false, line);
        double a = Math.toRadians(-60);
        float ax = cx + (float) Math.cos(a) * r;
        float ay = cy + (float) Math.sin(a) * r;
        path.rewind();
        path.moveTo(ax + r * 0.55f, ay - r * 0.05f);
        path.lineTo(ax - r * 0.15f, ay - r * 0.55f);
        path.lineTo(ax - r * 0.1f, ay + r * 0.4f);
        path.close();
        fill.setColor(TEXT);
        c.drawPath(path, fill);
    }

    private void drawPauseIcon(Canvas c, float cx, float cy, float r) {
        fill.setColor(TEXT);
        if (paused || finished) {
            path.rewind();
            path.moveTo(cx - r * 0.7f, cy - r);
            path.lineTo(cx + r, cy);
            path.lineTo(cx - r * 0.7f, cy + r);
            path.close();
            c.drawPath(path, fill);
        } else {
            rect.set(cx - r * 0.8f, cy - r, cx - r * 0.25f, cy + r);
            c.drawRoundRect(rect, dp(2f), dp(2f), fill);
            rect.set(cx + r * 0.25f, cy - r, cx + r * 0.8f, cy + r);
            c.drawRoundRect(rect, dp(2f), dp(2f), fill);
        }
    }

    private void drawFinished(Canvas c, float w, float h) {
        fill.setColor(0xC0050D19);
        c.drawRect(0, h * 0.18f, w, h * 0.77f, fill);
        float cx = w / 2f;
        text(c, "PIECE COMPLETE", cx, h * 0.34f, h * 0.05f, ACCENT, Paint.Align.CENTER, numbers, 0.3f);
        text(c, Math.round(pieceMeters()) + " m", cx, h * 0.5f, h * 0.15f, TEXT, Paint.Align.CENTER, numbers, 0.02f);
        String sub = pieceMinutes() + " MIN  ·  " + clock(inZoneSeconds) + " IN " + ZONES[targetZone];
        if (newBest) {
            sub += "  ·  NEW BEST";
        } else if (bests.has("zonerow." + pieceMinutes())) {
            sub += "  ·  BEST " + Math.round(bests.get("zonerow." + pieceMinutes(), 0f)) + " m";
        }
        text(c, sub, cx, h * 0.6f, h * 0.035f, newBest ? WARN : DIM, Paint.Align.CENTER, labels, 0.15f);
        text(c, "TAP  ↺  TO ROW AGAIN", cx, h * 0.7f, h * 0.026f, FAINT, Paint.Align.CENTER, labels, 0.25f);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align,
                      Typeface face, float spacing) {
        ink.setTypeface(face);
        ink.setTextSize(size);
        ink.setColor(color);
        ink.setTextAlign(align);
        ink.setLetterSpacing(spacing);
        c.drawText(s, x, y, ink);
    }

    /* ---------- touch ---------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX();
            float y = e.getY();
            if (restartHit.contains(x, y)) {
                restart();
            } else if (pauseHit.contains(x, y)) {
                if (finished) {
                    restart();
                } else {
                    paused = !paused;
                }
            } else if (y >= zoneTop && y <= zoneBottom && span > 0f) {
                int z = (int) ((x - x0) / (span / 4f));
                if (z >= 0 && z < 4) {
                    targetZone = z;
                }
            }
            postInvalidateOnAnimation();
        }
        // Still hand the event on, so a long-press can take a screenshot on local builds.
        super.onTouchEvent(e);
        return true;
    }
}
