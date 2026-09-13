package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The water paddle, spinning at the flywheel's measured rate.
 *
 * <p>Angle is advanced by elapsed wall time rather than per frame, so the spin stays true to the
 * measured rate regardless of how often the view redraws.
 */
final class PaddleView extends View {

    private static final int BLADES = 8;
    /** Visible motion as soon as the flywheel turns, before 14A catches up. */
    private static final double MOVING_FLOOR = 0.8;
    /** Degrees per second per m/s. Tuned so a hard pull clearly outruns a gentle one. */
    private static final double DEGREES_PER_MPS = 105.0;
    /**
     * Quadratic drag constant for the coast-down. The monitor stops reporting the instant you stop
     * pulling, so the wheel's wind-down is modelled: v(t) = v0 / (1 + k*v0*t).
     */
    /**
     * Water drag, quadratic in speed. On this machine the paddle in the tank is the entire
     * resistance, so nothing else is modelled: v(t) = v0 / (1 + DRAG*v0*t).
     */
    private volatile double drag = 0.12;

    private final Paint bladePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    /** Metres per second the water is moving, as last reported. */
    private double targetSpeed;
    /** False once pulses stop: the drive has ended even though 14A still reports its average. */
    private boolean driving;
    /** What the wheel is actually doing: coasts down on its own when reports stop. */
    private double shownSpeed;
    private float angleDeg;
    private long lastFrameMs;
    private long lastUpdateMs;

    PaddleView(Context context) {
        super(context);
        bladePaint.setStyle(Paint.Style.FILL);
        hubPaint.setColor(Color.parseColor("#35D0BA"));
        waterPaint.setStyle(Paint.Style.STROKE);
        waterPaint.setColor(Color.parseColor("#6F8CFF"));
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setColor(Color.parseColor("#212B3B"));
        labelPaint.setColor(Color.parseColor("#5D6B80"));
        valuePaint.setColor(Color.parseColor("#E6EDF7"));
        valuePaint.setFakeBoldText(true);
    }

    /**
     * @param metresPerSecond speed from address 14A
     * @param flywheelMoving  pulses arriving right now. 14A is only polled every ~1.5s, so the
     *                        pulse stream is what makes the wheel react the moment you pull.
     */
    void setDrag(double k) {
        this.drag = k;
    }

    void setSpeed(double metresPerSecond, boolean driving) {
        double speed = Math.max(0, metresPerSecond);
        if (driving && speed < MOVING_FLOOR) {
            speed = MOVING_FLOOR;
        }
        this.targetSpeed = speed;
        this.driving = driving;
        this.lastUpdateMs = System.currentTimeMillis();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - dp(14f);
        if (radius <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        float dt = lastFrameMs > 0 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;

        // Water carries its own momentum. Track new readings quickly, but once reports stop -
        // a stalled poll, or the rower simply at rest - coast down instead of freezing.
        boolean stale = lastUpdateMs == 0 || now - lastUpdateMs > 1500;
        boolean coasting = stale || !driving;
        double aim = coasting ? 0 : targetSpeed;
        if (coasting && shownSpeed > 0) {
            // Drive has stopped; the paddle keeps turning and water drag slows it.
            shownSpeed -= drag * shownSpeed * shownSpeed * dt;
        } else if (aim >= shownSpeed) {
            // Readings land about once a second, so climbing too fast produces a staircase:
            // jump, hold, jump. A gentler attack glides between them while still feeling live.
            shownSpeed += (aim - shownSpeed) * Math.min(1.0, 4.5 * dt);
        } else {
            // Never fall quicker than drag allows, whatever the monitor suddenly reports.
            shownSpeed = Math.max(aim, shownSpeed - drag * shownSpeed * shownSpeed * dt);
        }
        if (shownSpeed < 0.02) {
            shownSpeed = 0;
        }

        if (dt > 0 && shownSpeed > 0) {
            // ~55 deg/s per m/s: fast enough to read as motion, slow enough not to strobe.
            angleDeg = (float) ((angleDeg + shownSpeed * DEGREES_PER_MPS * dt) % 360);
        }

        rimPaint.setStrokeWidth(dp(1.5f));
        canvas.drawCircle(cx, cy, radius, rimPaint);

        // Water swirl: arcs that thicken and brighten as the flywheel speeds up.
        float intensity = (float) Math.min(1.0, shownSpeed / 4.5);
        waterPaint.setStrokeWidth(dp(1.5f + 2.5f * intensity));
        waterPaint.setAlpha((int) (50 + 150 * intensity));
        for (int i = 0; i < 3; i++) {
            float r = radius * (0.62f + i * 0.14f);
            oval.set(cx - r, cy - r, cx + r, cy + r);
            canvas.drawArc(oval, angleDeg * (1.4f + i * 0.3f), 55f + 40f * intensity, false, waterPaint);
        }

        canvas.save();
        canvas.rotate(angleDeg, cx, cy);
        for (int i = 0; i < BLADES; i++) {
            float a = (float) Math.toRadians(i * 360f / BLADES);
            float inner = radius * 0.26f;
            float outer = radius * 0.86f;
            float bx = cx + (float) Math.cos(a) * (inner + outer) / 2f;
            float by = cy + (float) Math.sin(a) * (inner + outer) / 2f;
            bladePaint.setColor(Color.parseColor("#35D0BA"));
            bladePaint.setAlpha((int) (110 + 145 * intensity));
            canvas.save();
            canvas.rotate(i * 360f / BLADES, cx, cy);
            oval.set(bx - dp(4f), by - (outer - inner) / 2f, bx + dp(4f), by + (outer - inner) / 2f);
            canvas.drawRoundRect(oval, dp(3f), dp(3f), bladePaint);
            canvas.restore();
        }
        canvas.restore();

        canvas.drawCircle(cx, cy, radius * 0.2f, hubPaint);

        labelPaint.setTextSize(dp(9f));
        valuePaint.setTextSize(dp(13f));
        String value = shownSpeed > 0.02
                ? String.format(java.util.Locale.US, "%.1f", shownSpeed)
                : "--";
        String label = "m/s";
        canvas.drawText(value, cx - valuePaint.measureText(value) / 2f, h - dp(13f), valuePaint);
        canvas.drawText(label, cx - labelPaint.measureText(label) / 2f, h - dp(3f), labelPaint);

        // Keep animating while the wheel still carries speed, so the coast-down is visible.
        if (shownSpeed > 0) {
            postInvalidateOnAnimation();
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
