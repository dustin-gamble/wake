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
    /** Shared wind-down; null for a gauge that simply eases (the rate dial). */
    private Coast coast;
    private boolean paddleTurning = true;
    private boolean driving = true;

    /** Best stroke's speed at this moment after the catch, drawn as a faint needle; NaN hides it. */
    private float ghost = Float.NaN;
    private float shownGhost = Float.NaN;
    /** A target on the dial (the pace the rower set), NaN for none, and the arc's current colour. */
    private float targetMark = Float.NaN;
    private int arcColor;
    private final Paint ghostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

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
        arcColor = accent;

        ghostPaint.setStyle(Paint.Style.STROKE);
        ghostPaint.setStrokeCap(Paint.Cap.ROUND);
        ghostPaint.setColor(Color.argb(110, 230, 237, 247));

        targetPaint.setStyle(Paint.Style.STROKE);
        targetPaint.setStrokeCap(Paint.Cap.ROUND);
        targetPaint.setColor(Color.parseColor("#E6EDF7"));

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
     * Winds down with the shared {@link Coast} once the drive stops: holds, eases off, lands on
     * zero. Replaced quadratic drag in 3.12.0, which dropped fast and then hovered above zero.
     *
     * @param baseSeconds    coast length from a crawl
     * @param secondsPerUnit added per unit of this gauge's value when the coast starts
     */
    GaugeView coastDown(float baseSeconds, float secondsPerUnit) {
        this.coast = new Coast(baseSeconds, secondsPerUnit);
        this.coastToZero = true;
        return this;
    }

    /** Retunes the coast length from the drawer's drag setting: lower drag glides longer. */
    void setDrag(float k) {
        if (coast != null) {
            coast.setDrag(k);
        }
        postInvalidateOnAnimation();
    }

    /**
     * Whether the paddle's pulses are still arriving. Once they stop the paddle has stopped, so
     * the rest of a coast finishes promptly instead of hovering above zero.
     */
    void setPaddleTurning(boolean turning) {
        this.paddleTurning = turning;
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

    /**
     * Where the best stroke of the session was at this same moment after its catch. Drawn as a
     * faint second needle: when the live one falls behind it, this stroke's run is dying sooner.
     */
    void setGhost(float value) {
        if (Float.isNaN(value) != Float.isNaN(ghost) || Math.abs(value - ghost) > 0.0005f) {
            ghost = value;
            postInvalidateOnAnimation();
        }
    }

    /**
     * A target marked on the dial, and the colour the arc takes to say whether you are on it.
     *
     * @param value NaN for no target
     * @param color the arc colour; the gauge's own accent when there is no target
     */
    void setTarget(float value, int color) {
        // Compare against the colour that will actually be used, so "no target" with a caller
        // colour other than the accent does not count as a change on every tick.
        int effective = Float.isNaN(value) ? accent : color;
        boolean changed = effective != arcColor
                || Float.isNaN(value) != Float.isNaN(targetMark)
                || (!Float.isNaN(value) && Math.abs(value - targetMark) > 0.0005f);
        if (!changed) {
            return;
        }
        targetMark = value;
        arcColor = effective;
        arcPaint.setColor(arcColor);
        hubPaint.setColor(arcColor);
        if (!Float.isNaN(value) && value * 1.1f > scaleMax) {
            scaleMax = value * 1.15f;
        }
        postInvalidateOnAnimation();
    }

    /** Current needle position, for verifying the coast from captured telemetry. */
    float shownValue() {
        return shown;
    }

    /** True while the needle is winding down under drag rather than tracking a reading. */
    boolean isCoasting() {
        return coast != null && !driving && shown > 0f;
    }

    void reset() {
        target = 0f;
        shown = 0f;
        scaleMax = initialMax;
        if (coast != null) {
            coast.cancel();
        }
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
        boolean coasting = coast != null && (stale || !driving);

        if (coasting) {
            shown = coast.step(shown, dt, !paddleTurning);
        } else {
            if (coast != null) {
                coast.cancel();
            }
            float aim = stale ? 0f : target;
            if (aim >= shown) {
                shown += (aim - shown) * Math.min(1f, attackPerSecond * dt);
            } else if (coast != null) {
                // Below the reading: fall no quicker than a coast from here. The paddle slows
                // between every stroke, so the needle must never sit flat waiting for a verdict,
                // but a reading that drops suddenly is the average catching up, not a dead stop.
                shown = coast.fallToward(shown, aim, dt);
            } else {
                shown += (aim - shown) * Math.min(1f, decayPerSecond * dt);
            }
            if (Math.abs(aim - shown) < 0.001f) {
                shown = aim;
            }
        }
        // The coast lands on zero by itself; this only tidies the last sliver of an eased fall.
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

        // The target: a bright notch across the track, so the needle has something to reach.
        if (!Float.isNaN(targetMark) && scaleMax > 0) {
            double ta = Math.toRadians(START_ANGLE + SWEEP * Math.min(1f, targetMark / scaleMax));
            float in = radius - stroke * 0.9f;
            float out = radius + stroke * 0.75f;
            targetPaint.setStrokeWidth(dp(3f));
            canvas.drawLine(cx + (float) Math.cos(ta) * in, cy + (float) Math.sin(ta) * in,
                    cx + (float) Math.cos(ta) * out, cy + (float) Math.sin(ta) * out, targetPaint);
        }

        // Ghost needle: eased so it glides with the live one rather than stepping per sample.
        if (Float.isNaN(ghost)) {
            shownGhost = Float.NaN;
        } else {
            shownGhost = Float.isNaN(shownGhost) ? ghost : shownGhost + (ghost - shownGhost) * Math.min(1f, 10f * dt);
            if (scaleMax > 0) {
                double ga = Math.toRadians(START_ANGLE + SWEEP * Math.min(1f, shownGhost / scaleMax));
                float gLen = radius - stroke * 1.3f;
                ghostPaint.setStrokeWidth(dp(2f));
                canvas.drawLine(cx + (float) Math.cos(ga) * gLen * 0.35f, cy + (float) Math.sin(ga) * gLen * 0.35f,
                        cx + (float) Math.cos(ga) * gLen, cy + (float) Math.sin(ga) * gLen, ghostPaint);
            }
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
        if (Math.abs(target - shown) > 0.001f || (shown > 0f && coast != null)
                || (!Float.isNaN(ghost) && Math.abs(ghost - shownGhost) > 0.001f)) {
            postInvalidateOnAnimation();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
