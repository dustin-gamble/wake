package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Analog dial with a sweeping needle.
 *
 * <p>The needle carries momentum: it rises quickly to a new reading but falls away slowly, so a
 * gauge fed by a rower winds down the way the flywheel does rather than dropping to zero the
 * instant a reading stops arriving.
 */
final class GaugeView extends View {

    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private final String label;
    private final String unit;
    private final int accent;
    private final int decimals;

    private float scaleMax;
    private final float initialMax;
    private float target;
    private float shown;
    private long lastFrameMs;
    private long lastUpdateMs;
    private float attackPerSecond = 6f;
    private float decayPerSecond = 1.1f;
    private boolean coastToZero;
    private float dragCoefficient;
    private boolean driving = true;

    GaugeView(Context context, String label, String unit, int accent, float scaleMax, int decimals) {
        super(context);
        this.label = label;
        this.unit = unit;
        this.accent = accent;
        this.scaleMax = scaleMax;
        this.initialMax = scaleMax;
        this.decimals = decimals;

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(Color.parseColor("#18202C"));

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(accent);

        tickPaint.setColor(Color.parseColor("#2A3648"));
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        needlePaint.setColor(Color.parseColor("#E6EDF7"));
        needlePaint.setStrokeCap(Paint.Cap.ROUND);

        hubPaint.setColor(accent);

        valuePaint.setColor(Color.parseColor("#E6EDF7"));
        valuePaint.setFakeBoldText(true);
        valuePaint.setTextAlign(Paint.Align.CENTER);

        unitPaint.setColor(Color.parseColor("#8D9BB0"));
        unitPaint.setTextAlign(Paint.Align.CENTER);

        labelPaint.setColor(Color.parseColor("#5D6B80"));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
        labelPaint.setLetterSpacing(0.1f);
    }

    /** Makes the needle fall away on its own when readings stop, like a coasting flywheel. */
    GaugeView coasting(float attackPerSecond, float decayPerSecond) {
        this.attackPerSecond = attackPerSecond;
        this.decayPerSecond = decayPerSecond;
        this.coastToZero = true;
        return this;
    }

    /**
     * Coasts down under quadratic fluid drag once the drive stops.
     *
     * <p>The S4 reports speed 0 and stops sending pulses the moment you stop pulling, even though
     * the paddle is still turning, so the wind-down cannot be measured and is modelled instead.
     * Drag on a paddle in water goes as v^2, giving v(t) = v0 / (1 + k*v0*t): a quick initial drop
     * with a long tail, which is how the real flywheel behaves.
     *
     * @param k larger slows the wheel sooner; 0.09 takes roughly 4 m/s down to 1 m/s in ~8s.
     */
    /**
     * Coasts down under water drag once the drive stops.
     *
     * <p>On a water rower essentially all the resistance is the paddle dragging through the tank,
     * so the wind-down is pure quadratic drag: {@code dv/dt = -k*v^2}, giving
     * {@code v(t) = v0 / (1 + k*v0*t)}. Bearing friction is negligible here and is deliberately
     * not modelled - an earlier build added a linear term, which only compensated for a drag
     * coefficient that was set far too low.
     *
     * <p>Applied in the gauge's own units, so k is per-unit and must suit the scale.
     */
    GaugeView waterDrag(float k) {
        this.dragCoefficient = k;
        this.coastToZero = true;
        return this;
    }

    /**
     * Retunes the drag while running.
     *
     * <p>The monitor reports nothing at all once the drive stops - no pulses, no speed - and the
     * one address that might have carried instantaneous speed (148) is refused by this firmware.
     * So the coefficient cannot be fitted from data and is set by how the paddle actually looks.
     */
    void setDrag(float k) {
        this.dragCoefficient = k;
        postInvalidateOnAnimation();
    }

    /**
     * How fast the needle climbs to a new reading.
     *
     * <p>Addresses only refresh about once a second, so a fast attack makes the needle jump to each
     * reading and then sit still - a staircase. A slower attack glides between readings instead.
     */
    GaugeView attack(float perSecond) {
        this.attackPerSecond = perSecond;
        return this;
    }

    void setValue(float value) {
        setValue(value, true);
    }

    /**
     * @param driving whether the rower is still applying force - NOT whether the paddle is
     *               turning. The paddle keeps spinning for many seconds after the drive ends, so
     *               that signal never starts the coast. Address 14A is an average and holds its
     *               last value for ~10s after you stop, which parked the needle. Power collapsing
     *               to zero is what actually marks the end of the drive.
     */
    void setValue(float value, boolean driving) {
        this.driving = driving;
        this.target = Math.max(0f, value);
        this.lastUpdateMs = System.currentTimeMillis();
        if (target > scaleMax) {
            scaleMax = target * 1.15f;
        }
        postInvalidateOnAnimation();
    }

    /** Current needle position, for verifying the coast from captured telemetry. */
    float shownValue() {
        return shown;
    }

    /** True while the needle is winding down under drag rather than tracking a reading. */
    boolean isCoasting() {
        return dragCoefficient > 0f && !driving && shown > 0f;
    }

    void reset() {
        target = 0f;
        shown = 0f;
        scaleMax = initialMax;
        lastUpdateMs = 0;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float size = Math.min(w, h);
        float radius = size / 2f - dp(10f);
        float cy = h / 2f + dp(4f);
        if (radius <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        float dt = lastFrameMs > 0 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;

        boolean stale = coastToZero && (lastUpdateMs == 0 || now - lastUpdateMs > 1200);
        boolean coasting = dragCoefficient > 0f && (stale || !driving);

        if (coasting && shown > 0f) {
            shown -= dragCoefficient * shown * shown * dt;
        } else {
            float aim = stale ? 0f : target;
            if (aim >= shown) {
                shown += (aim - shown) * Math.min(1f, attackPerSecond * dt);
            } else if (dragCoefficient > 0f) {
                // Below the reading: still drag. The paddle slows between every stroke, not only
                // when the session ends, so the needle must never sit flat waiting for a verdict.
                // Falling: never quicker than water drag allows. A reading that drops suddenly is
                // the monitor's average catching up, not the paddle actually stopping dead.
                shown = Math.max(aim, shown - dragCoefficient * shown * shown * dt);
            } else {
                shown += (aim - shown) * Math.min(1f, decayPerSecond * dt);
            }
            if (Math.abs(aim - shown) < 0.001f) {
                shown = aim;
            }
        }
        // Drag approaches zero asymptotically; below a fraction of a percent of full scale the
        // needle is already at the pin, so this is display quantisation rather than physics.
        if (shown < scaleMax * 0.005f && (coasting || target <= 0f)) {
            shown = 0f;
        }

        float fraction = scaleMax > 0 ? Math.min(1f, shown / scaleMax) : 0f;
        float stroke = radius * 0.14f;

        trackPaint.setStrokeWidth(stroke);
        arcPaint.setStrokeWidth(stroke);
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius);
        canvas.drawArc(oval, START_ANGLE, SWEEP, false, trackPaint);
        if (fraction > 0.003f) {
            canvas.drawArc(oval, START_ANGLE, SWEEP * fraction, false, arcPaint);
        }

        tickPaint.setStrokeWidth(dp(1.5f));
        for (int i = 0; i <= 10; i++) {
            double a = Math.toRadians(START_ANGLE + SWEEP * i / 10f);
            float inner = radius - stroke * 0.85f;
            float outer = inner - (i % 5 == 0 ? dp(7f) : dp(4f));
            canvas.drawLine(
                    cx + (float) Math.cos(a) * inner, cy + (float) Math.sin(a) * inner,
                    cx + (float) Math.cos(a) * outer, cy + (float) Math.sin(a) * outer,
                    tickPaint);
        }

        double needleAngle = Math.toRadians(START_ANGLE + SWEEP * fraction);
        needlePaint.setStrokeWidth(dp(2.6f));
        float needleLen = radius - stroke * 1.3f;
        canvas.drawLine(cx, cy,
                cx + (float) Math.cos(needleAngle) * needleLen,
                cy + (float) Math.sin(needleAngle) * needleLen,
                needlePaint);
        canvas.drawCircle(cx, cy, dp(4.5f), hubPaint);

        valuePaint.setTextSize(radius * 0.46f);
        unitPaint.setTextSize(radius * 0.18f);
        labelPaint.setTextSize(radius * 0.17f);
        String text = decimals > 0
                ? String.format(java.util.Locale.US, "%." + decimals + "f", shown)
                : String.valueOf(Math.round(shown));
        canvas.drawText(text, cx, cy + radius * 0.16f, valuePaint);
        canvas.drawText(unit, cx, cy + radius * 0.42f, unitPaint);
        canvas.drawText(label, cx, cy + radius * 0.86f, labelPaint);

        // Keep animating while the needle is still moving, coast included.
        if (Math.abs(target - shown) > 0.001f || (shown > 0f && dragCoefficient > 0f)) {
            postInvalidateOnAnimation();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
