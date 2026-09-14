package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;

/**
 * CALIBRATE: turns the rower's own measurements into absolute energy.
 *
 * <p>{@link PulseMeter} measures work from the paddle's pulses, but only up to one scale - the
 * paddle's inertia. Until this screen sets it, that scale is matched to the monitor's power
 * readings, which are WaterRower's own model. Four steps replace the model with measurement:
 *
 * <ol>
 *   <li><b>Boat distance</b> - automatic: pulses counted against the monitor's metres.</li>
 *   <li><b>Water drag</b> - automatic: every recovery is a free coast-down measurement.</li>
 *   <li><b>Handle travel</b> - pull a measured distance slowly and steadily; pulses per metre of
 *       handle follow. Tested at 1.6% on a simulated steady pull.</li>
 *   <li><b>Force</b> - pull steadily through a load scale; at a steady pull the hand's power
 *       equals the water's drag, so force, handle travel and drag give the inertia.</li>
 * </ol>
 *
 * <p>Results go to the activity through {@link Host}, which applies them to the meter, stores them
 * and reports them to the laptop.
 */
final class CalibrateGame extends GameView {

    interface Host {
        /** @param what "handle" (metres per pulse) or "inertia"; value 0 clears it */
        void onCalibration(String what, double value, String detail);
    }

    private static final int STEP_DISTANCE = 0;
    private static final int STEP_DRAG = 1;
    private static final int STEP_HANDLE = 2;
    private static final int STEP_FORCE = 3;
    private static final int STEP_RESULTS = 4;
    private static final String[] STEP_NAMES = {"BOAT DISTANCE", "WATER DRAG", "HANDLE TRAVEL", "FORCE", "RESULTS"};
    private static final int NEED_COASTS = 10;
    private static final int MAX_PULLS = 6;
    private static final double KG_TO_N = 9.80665;
    private static final double LB_TO_KG = 0.45359237;

    private final Host host;
    private int step = STEP_DISTANCE;
    private PulseMeter.Reading reading = PulseMeter.EMPTY;
    private PulseMeter.Stroke lastSeen;

    // handle travel
    private int handleCm = 100;
    private final long[] handlePulls = new long[MAX_PULLS];
    private int handleCount;
    private String handleNote = "";

    // force
    private double scaleReading = 15.0;
    private boolean pounds;
    private final double[] pullRates = new double[MAX_PULLS];
    private final double[] pullForces = new double[MAX_PULLS];
    private int forceCount;
    private double pendingRate;
    private String forceNote = "";

    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Typeface bold = Typeface.create("sans-serif-condensed", Typeface.BOLD);
    private final Typeface plain = Typeface.create("sans-serif", Typeface.NORMAL);

    /** Buttons laid out on the last frame: {left, top, right, bottom, id}. */
    private final float[][] buttons = new float[16][];
    private int buttonCount;

    private static final int B_STEP = 100;   // + step index
    private static final int B_MINUS = 1;
    private static final int B_PLUS = 2;
    private static final int B_SAVE = 3;
    private static final int B_CLEAR = 4;
    private static final int B_UNITS = 5;
    private static final int B_ADD = 6;
    private static final int B_RESET = 7;
    private static final int B_NEXT = 8;

    CalibrateGame(Context context, Host host) {
        super(context);
        this.host = host;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        reading = s.meter;
        PulseMeter.Stroke stroke = reading.lastStroke;
        if (stroke == null || stroke == lastSeen) {
            return;
        }
        lastSeen = stroke;
        if (step == STEP_HANDLE) {
            if (reading.dragPerInertia <= 0) {
                handleNote = "Row a few normal strokes first, so drag is known.";
            } else if (stroke.drivePulses < 40) {
                handleNote = "That pull was too short to use.";
            } else {
                if (handleCount == MAX_PULLS) {
                    System.arraycopy(handlePulls, 1, handlePulls, 0, MAX_PULLS - 1);
                    handleCount--;
                }
                handlePulls[handleCount++] = stroke.drivePulses;
                handleNote = "Pull " + handleCount + " recorded: " + stroke.drivePulses + " pulses.";
            }
        } else if (step == STEP_FORCE) {
            pendingRate = stroke.plateauRate;
            forceNote = "Pull recorded. Set what the scale read, then tap ADD.";
        }
    }

    /* ---------- calculations ---------- */

    private double handleMetresPerPulse() {
        if (handleCount < 3) {
            return 0;
        }
        double[] v = new double[handleCount];
        for (int i = 0; i < handleCount; i++) {
            v[i] = handlePulls[i];
        }
        return (handleCm / 100.0) / PulseMeter.medianOf(v, handleCount);
    }

    private double handleSpread() {
        long lo = Long.MAX_VALUE;
        long hi = 0;
        double[] v = new double[handleCount];
        for (int i = 0; i < handleCount; i++) {
            lo = Math.min(lo, handlePulls[i]);
            hi = Math.max(hi, handlePulls[i]);
            v[i] = handlePulls[i];
        }
        double median = PulseMeter.medianOf(v, handleCount);
        return median > 0 ? (hi - lo) / median : 0;
    }

    private double forceInertia() {
        double h = reading.handleMetresPerPulse;
        double c = reading.dragPerInertia;
        if (forceCount < 3 || h <= 0 || c <= 0) {
            return 0;
        }
        double[] v = new double[forceCount];
        for (int i = 0; i < forceCount; i++) {
            v[i] = PulseMeter.inertiaFromPull(pullForces[i], pullRates[i], h, c);
        }
        return PulseMeter.medianOf(v, forceCount);
    }

    private double kg() {
        return pounds ? scaleReading * LB_TO_KG : scaleReading;
    }

    /* ---------- drawing ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        buttonCount = 0;
        fill.setColor(0xFF070D16);
        c.drawRect(0, 0, w, h, fill);

        float railW = Math.min(w * 0.24f, dp(300f));
        drawRail(c, railW, h);
        float x = railW + dp(28f);
        float right = w - dp(28f);
        switch (step) {
            case STEP_DISTANCE:
                drawDistance(c, x, right, h);
                break;
            case STEP_DRAG:
                drawDrag(c, x, right, h);
                break;
            case STEP_HANDLE:
                drawHandle(c, x, right, h);
                break;
            case STEP_FORCE:
                drawForce(c, x, right, h);
                break;
            default:
                drawResults(c, x, right, h);
                break;
        }
    }

    private boolean stepDone(int s) {
        switch (s) {
            case STEP_DISTANCE:
                return reading.pulsesPerMetreMeasured;
            case STEP_DRAG:
                return reading.coastFits >= NEED_COASTS;
            case STEP_HANDLE:
                return reading.handleMetresPerPulse > 0;
            case STEP_FORCE:
                return reading.source == PulseMeter.EnergySource.PULSES_CALIBRATED;
            default:
                return false;
        }
    }

    private void drawRail(Canvas c, float railW, float h) {
        fill.setColor(0xFF0D1420);
        c.drawRect(0, 0, railW, h, fill);
        float rowH = Math.min(dp(84f), h / 6f);
        for (int i = 0; i < STEP_NAMES.length; i++) {
            float top = dp(18f) + i * rowH;
            if (i == step) {
                fill.setColor(0xFF16263B);
                rect.set(dp(10f), top, railW - dp(10f), top + rowH - dp(8f));
                c.drawRoundRect(rect, dp(10f), dp(10f), fill);
            }
            boolean done = stepDone(i);
            float cx = dp(38f);
            float cy = top + (rowH - dp(8f)) / 2f;
            fill.setColor(done ? ACCENT : i == step ? BLUE : 0xFF2A3648);
            c.drawCircle(cx, cy, dp(14f), fill);
            text(c, done ? "✓" : String.valueOf(i + 1), cx, cy + dp(6f), dp(17f),
                    0xFF04211D, Paint.Align.CENTER, bold);
            text(c, STEP_NAMES[i], cx + dp(26f), cy + dp(7f), dp(18f), i == step ? TEXT : DIM,
                    Paint.Align.LEFT, bold);
            button(dp(10f), top, railW - dp(10f), top + rowH - dp(8f), B_STEP + i);
        }
    }

    private float title(Canvas c, String title, String lead, float x, float right) {
        text(c, title, x, dp(56f), dp(34f), ACCENT, Paint.Align.LEFT, bold);
        return wrap(c, lead, x, dp(96f), right - x, dp(20f), DIM);
    }

    private void drawDistance(Canvas c, float x, float right, float h) {
        float y = title(c, "Boat distance",
                "Automatic. Row about 200 m at any pace. WAKE counts paddle pulses against the"
                        + " monitor's own metres, the way the monitor measures distance itself.", x, right);
        y += dp(30f);
        big(c, reading.pulsesPerMetre > 0 ? fmt(reading.pulsesPerMetre, 1) : "--",
                "PULSES PER METRE" + (reading.pulsesPerMetreMeasured ? "" : "  (rowing to measure)"), x, y);
        if (reading.pulsesPerMetre > 0) {
            big(c, fmt(reading.pulsesPerMetre / PulseMeter.PADDLE_TURNS_PER_METRE, 0),
                    "PULSES PER PADDLE TURN  (from your 0.65 turns per metre)", x + (right - x) / 2f, y);
        }
        nextButton(c, right, h, stepDone(STEP_DISTANCE));
    }

    private void drawDrag(Canvas c, float x, float right, float h) {
        float y = title(c, "Water drag",
                "Automatic. Row ten normal strokes. Each recovery - handle released, paddle"
                        + " coasting - shows exactly how fast the water slows the paddle.", x, right);
        y += dp(30f);
        big(c, Math.min(reading.coastFits, 99) + " / " + NEED_COASTS, "COASTS MEASURED", x, y);
        big(c, reading.dragPerInertia > 0 ? fmt(reading.dragPerInertia * 1e4, 2) : "--",
                "DRAG / INERTIA  (x 10^-4 per pulse)", x + (right - x) / 2f, y);
        drawTrace(c, x, y + dp(40f), right, y + dp(200f));
        nextButton(c, right, h, stepDone(STEP_DRAG));
    }

    private void drawHandle(Canvas c, float x, float right, float h) {
        float y = title(c, "Handle travel",
                "Sit with the handle at the catch and a tape measure beside the strap. Pull slowly"
                        + " and steadily to the distance below, then let go. Do it three to five times"
                        + " - the paddle's coast after you let go is subtracted automatically.", x, right);
        y += dp(26f);
        text(c, "PULL DISTANCE", x, y, dp(15f), FAINT, Paint.Align.LEFT, bold);
        y += dp(12f);
        stepper(c, x, y, handleCm + " cm", B_MINUS, B_PLUS);
        float listX = x + dp(360f);
        text(c, "PULLS", listX, y - dp(12f), dp(15f), FAINT, Paint.Align.LEFT, bold);
        for (int i = 0; i < handleCount; i++) {
            text(c, (i + 1) + ".  " + handlePulls[i] + " pulses", listX, y + dp(26f) + i * dp(30f),
                    dp(20f), TEXT, Paint.Align.LEFT, plain);
        }
        y += dp(96f);
        wrap(c, handleNote, x, y, dp(330f), dp(18f), WARN);
        double hmp = handleMetresPerPulse();
        if (hmp > 0) {
            double spread = handleSpread();
            big(c, fmt(hmp * 1000, 2) + " mm", "HANDLE TRAVEL PER PULSE", x, y + dp(80f));
            text(c, "Spread " + Math.round(spread * 100) + "%" + (spread > 0.08 ? " - pull more evenly and add another" : " - consistent"),
                    x, y + dp(130f), dp(17f), spread > 0.08 ? WARN : ACCENT, Paint.Align.LEFT, plain);
            actionButton(c, x, y + dp(150f), "SAVE HANDLE", B_SAVE, ACCENT);
        }
        if (handleCount > 0) {
            actionButton(c, x + dp(240f), y + dp(150f), "START OVER", B_RESET, 0xFF2A3648);
        }
        nextButton(c, right, h, stepDone(STEP_HANDLE));
    }

    private void drawForce(Canvas c, float x, float right, float h) {
        float y = title(c, "Force",
                "Hook the load scale between the handle and your hands. Pull at an even speed so the"
                        + " reading settles, and let go. Set what the scale read, then tap ADD. Use three"
                        + " or more pulls at different speeds.", x, right);
        y += dp(26f);
        if (reading.handleMetresPerPulse <= 0) {
            wrap(c, "Save the handle travel first - force needs it.", x, y + dp(20f), right - x, dp(22f), WARN);
            nextButton(c, right, h, false);
            return;
        }
        text(c, "SCALE READ", x, y, dp(15f), FAINT, Paint.Align.LEFT, bold);
        y += dp(12f);
        stepper(c, x, y, fmt(scaleReading, 1) + (pounds ? " lb" : " kg"), B_MINUS, B_PLUS);
        actionButton(c, x + dp(330f), y, pounds ? "USE KG" : "USE LB", B_UNITS, 0xFF2A3648);
        y += dp(88f);
        if (pendingRate > 0) {
            actionButton(c, x, y, "ADD PULL", B_ADD, BLUE);
        }
        wrap(c, forceNote, x + dp(220f), y + dp(34f), right - x - dp(220f), dp(18f), WARN);

        float listY = y + dp(100f);
        text(c, "PULL        FORCE        PADDLE RATE        INERTIA", x, listY, dp(15f), FAINT, Paint.Align.LEFT, bold);
        for (int i = 0; i < forceCount; i++) {
            double inertia = PulseMeter.inertiaFromPull(pullForces[i], pullRates[i],
                    reading.handleMetresPerPulse, reading.dragPerInertia);
            text(c, String.format(java.util.Locale.US, "%d.          %.0f N        %.0f /s              %.5f",
                    i + 1, pullForces[i], pullRates[i], inertia),
                    x, listY + dp(30f) * (i + 1), dp(19f), TEXT, Paint.Align.LEFT, plain);
        }
        double inertia = forceInertia();
        if (inertia > 0) {
            double fit = PulseMeter.squareLawFit(pullForces, pullRates, forceCount);
            float ry = listY + dp(30f) * (forceCount + 1) + dp(20f);
            text(c, String.format(java.util.Locale.US, "Inertia %.5f   -   force follows speed squared: %.0f%%",
                    inertia, fit * 100), x, ry, dp(19f), fit > 0.9 ? ACCENT : WARN, Paint.Align.LEFT, bold);
            actionButton(c, x, ry + dp(20f), "SAVE FORCE", B_SAVE, ACCENT);
            actionButton(c, x + dp(240f), ry + dp(20f), "START OVER", B_RESET, 0xFF2A3648);
        }
        nextButton(c, right, h, stepDone(STEP_FORCE));
    }

    private void drawResults(Canvas c, float x, float right, float h) {
        String source;
        switch (reading.source) {
            case PULSES_CALIBRATED:
                source = "Measured from pulses, calibrated with your load scale.";
                break;
            case PULSES_MONITOR_SCALED:
                source = "Measured from pulses, scaled to the monitor's power until you calibrate force.";
                break;
            default:
                source = "Monitor power readings - row a few strokes so drag can be measured.";
                break;
        }
        float y = title(c, "Results", source, x, right);
        y += dp(24f);
        float col = (right - x) / 3f;
        PulseMeter.Stroke s = reading.lastStroke;
        int monitorWatts = status == null ? 0 : status.watts;
        big(c, s != null && !Double.isNaN(s.averagePowerW) ? String.valueOf(Math.round(s.averagePowerW)) : "--",
                "MEASURED WATTS (LAST STROKE)", x, y);
        big(c, monitorWatts > 0 ? String.valueOf(monitorWatts) : "--", "MONITOR WATTS", x + col, y);
        big(c, s != null && !Double.isNaN(s.workJoules) ? String.valueOf(Math.round(s.workJoules)) : "--",
                "JOULES (LAST STROKE)", x + 2 * col, y);
        y += dp(96f);
        big(c, s != null && !Double.isNaN(s.peakForceN) ? fmt(s.peakForceN / KG_TO_N, 0) + " kg" : "--",
                "PEAK HANDLE FORCE", x, y);
        big(c, s != null && !Double.isNaN(s.driveLengthM) ? Math.round(s.driveLengthM * 100) + " cm" : "--",
                "DRIVE LENGTH", x + col, y);
        big(c, s != null ? fmt(s.ratio(), 2) : "--", "DRIVE : RECOVERY", x + 2 * col, y);
        y += dp(96f);
        big(c, String.valueOf(Math.round(reading.workJoules / 1000.0)) + " kJ", "WORK SINCE WAKE OPENED", x, y);
        big(c, String.valueOf(Math.round(reading.kcal())), "KCAL  (25% MUSCLE EFFICIENCY)", x + col, y);
        big(c, s != null && !Double.isNaN(s.averagePowerW)
                        ? String.valueOf(Math.round(PulseMeter.kcalForWork(s.averagePowerW * 3600))) : "--",
                "KCAL / HOUR AT THIS POWER", x + 2 * col, y);
        y += dp(70f);
        wrap(c, "Calories are food energy: mechanical work divided by 25%, the typical efficiency of"
                + " human muscle. Resting metabolism is not included.", x, y, right - x, dp(17f), FAINT);
        if (reading.source == PulseMeter.EnergySource.PULSES_CALIBRATED) {
            actionButton(c, x, h - dp(90f), "CLEAR FORCE CALIBRATION", B_CLEAR, 0xFF3A2020);
        }
    }

    private void drawTrace(Canvas c, float left, float top, float right, float bottom) {
        float[] t = reading.trace;
        float max = 1f;
        for (float v : t) {
            max = Math.max(max, v);
        }
        fill.setColor(0xFF0D1420);
        rect.set(left, top, right, bottom);
        c.drawRoundRect(rect, dp(8f), dp(8f), fill);
        ink.setColor(ACCENT);
        ink.setStrokeWidth(dp(2.5f));
        float step = (right - left) / (t.length - 1f);
        for (int i = 1; i < t.length; i++) {
            c.drawLine(left + (i - 1) * step, bottom - (bottom - top) * t[i - 1] / max,
                    left + i * step, bottom - (bottom - top) * t[i] / max, ink);
        }
        text(c, "PADDLE RATE, LAST 6 S", left + dp(10f), top + dp(22f), dp(14f), FAINT, Paint.Align.LEFT, bold);
    }

    /* ---------- widgets ---------- */

    private void big(Canvas c, String value, String label, float x, float y) {
        text(c, value, x, y + dp(40f), dp(44f), TEXT, Paint.Align.LEFT, bold);
        text(c, label, x, y + dp(64f), dp(14f), FAINT, Paint.Align.LEFT, bold);
    }

    private void stepper(Canvas c, float x, float y, String value, int minusId, int plusId) {
        float size = dp(64f);
        roundButton(c, x, y, size, "−", minusId);
        text(c, value, x + size + dp(90f), y + size * 0.68f, dp(36f), TEXT, Paint.Align.CENTER, bold);
        roundButton(c, x + size + dp(180f), y, size, "+", plusId);
    }

    private void roundButton(Canvas c, float x, float y, float size, String label, int id) {
        fill.setColor(0xFF16263B);
        rect.set(x, y, x + size, y + size);
        c.drawRoundRect(rect, size * 0.3f, size * 0.3f, fill);
        text(c, label, x + size / 2f, y + size * 0.68f, dp(34f), TEXT, Paint.Align.CENTER, bold);
        button(x, y, x + size, y + size, id);
    }

    private void actionButton(Canvas c, float x, float y, String label, int id, int color) {
        ink.setTypeface(bold);
        ink.setTextSize(dp(18f));
        float w = ink.measureText(label) + dp(40f);
        fill.setColor(color);
        rect.set(x, y, x + w, y + dp(56f));
        c.drawRoundRect(rect, dp(12f), dp(12f), fill);
        text(c, label, x + w / 2f, y + dp(35f), dp(18f), color == ACCENT ? 0xFF04211D : TEXT,
                Paint.Align.CENTER, bold);
        button(x, y, x + w, y + dp(56f), id);
    }

    private void nextButton(Canvas c, float right, float h, boolean done) {
        String label = done ? "NEXT  ›" : "SKIP  ›";
        ink.setTypeface(bold);
        ink.setTextSize(dp(18f));
        float w = ink.measureText(label) + dp(40f);
        actionButton(c, right - w, h - dp(90f), label, B_NEXT, done ? ACCENT : 0xFF2A3648);
    }

    private void button(float l, float t, float r, float b, int id) {
        if (buttonCount < buttons.length) {
            buttons[buttonCount++] = new float[]{l, t, r, b, id};
        }
    }

    private float wrap(Canvas c, String s, float x, float y, float width, float size, int color) {
        if (s == null || s.isEmpty()) {
            return y;
        }
        ink.setTypeface(plain);
        ink.setTextSize(size);
        String[] words = s.split(" ");
        StringBuilder line = new StringBuilder();
        float lineY = y;
        for (String word : words) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (ink.measureText(trial) > width && line.length() > 0) {
                text(c, line.toString(), x, lineY, size, color, Paint.Align.LEFT, plain);
                lineY += size * 1.45f;
                line.setLength(0);
                line.append(word);
            } else {
                line.setLength(0);
                line.append(trial);
            }
        }
        text(c, line.toString(), x, lineY, size, color, Paint.Align.LEFT, plain);
        return lineY + size * 1.45f;
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, Typeface face) {
        ink.setTypeface(face);
        ink.setTextSize(size);
        ink.setColor(color);
        ink.setTextAlign(align);
        c.drawText(s, x, y, ink);
        ink.setTextAlign(Paint.Align.LEFT);
    }

    private static String fmt(double v, int decimals) {
        return String.format(java.util.Locale.US, "%." + decimals + "f", v);
    }

    /* ---------- touch ---------- */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            for (int i = buttonCount - 1; i >= 0; i--) {
                float[] b = buttons[i];
                if (e.getX() >= b[0] && e.getX() <= b[2] && e.getY() >= b[1] && e.getY() <= b[3]) {
                    press((int) b[4]);
                    break;
                }
            }
            postInvalidateOnAnimation();
        }
        super.onTouchEvent(e);
        return true;
    }

    private void press(int id) {
        if (id >= B_STEP) {
            step = id - B_STEP;
            return;
        }
        switch (id) {
            case B_NEXT:
                step = Math.min(STEP_RESULTS, step + 1);
                break;
            case B_MINUS:
                if (step == STEP_HANDLE) {
                    handleCm = Math.max(30, handleCm - 5);
                } else {
                    scaleReading = Math.max(0.5, scaleReading - (pounds ? 1.0 : 0.5));
                }
                break;
            case B_PLUS:
                if (step == STEP_HANDLE) {
                    handleCm = Math.min(180, handleCm + 5);
                } else {
                    scaleReading = Math.min(200, scaleReading + (pounds ? 1.0 : 0.5));
                }
                break;
            case B_UNITS:
                scaleReading = pounds ? scaleReading * LB_TO_KG : scaleReading / LB_TO_KG;
                scaleReading = Math.round(scaleReading * 2) / 2.0;
                pounds = !pounds;
                break;
            case B_ADD:
                if (pendingRate > 0) {
                    if (forceCount == MAX_PULLS) {
                        System.arraycopy(pullRates, 1, pullRates, 0, MAX_PULLS - 1);
                        System.arraycopy(pullForces, 1, pullForces, 0, MAX_PULLS - 1);
                        forceCount--;
                    }
                    pullRates[forceCount] = pendingRate;
                    pullForces[forceCount] = kg() * KG_TO_N;
                    forceCount++;
                    pendingRate = 0;
                    forceNote = "Added. " + (forceCount < 3 ? (3 - forceCount) + " more, at a different speed." : "Save when the inertia settles.");
                }
                break;
            case B_RESET:
                if (step == STEP_HANDLE) {
                    handleCount = 0;
                    handleNote = "";
                } else {
                    forceCount = 0;
                    pendingRate = 0;
                    forceNote = "";
                }
                break;
            case B_SAVE:
                if (step == STEP_HANDLE && handleMetresPerPulse() > 0) {
                    host.onCalibration("handle", handleMetresPerPulse(),
                            "distanceCm=" + handleCm + " pulls=" + java.util.Arrays.toString(java.util.Arrays.copyOf(handlePulls, handleCount)));
                    handleNote = "Saved.";
                } else if (step == STEP_FORCE && forceInertia() > 0) {
                    host.onCalibration("inertia", forceInertia(), String.format(java.util.Locale.US,
                            "forcesN=%s rates=%s fit=%.3f",
                            java.util.Arrays.toString(java.util.Arrays.copyOf(pullForces, forceCount)),
                            java.util.Arrays.toString(java.util.Arrays.copyOf(pullRates, forceCount)),
                            PulseMeter.squareLawFit(pullForces, pullRates, forceCount)));
                    forceNote = "Saved. Energy is now calibrated.";
                    step = STEP_RESULTS;
                }
                break;
            case B_CLEAR:
                host.onCalibration("inertia", 0, "cleared");
                break;
            default:
                break;
        }
    }
}
