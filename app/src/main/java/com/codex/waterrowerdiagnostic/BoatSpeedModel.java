package com.codex.waterrowerdiagnostic;

/**
 * The boat's speed as it should be shown: rising through each drive, then winding down with the
 * shared {@link Coast} after it - holding, easing off, landing on zero. Pure Java so games and
 * tests can use it without a View.
 *
 * <p>Was quadratic drag until 3.12.0, which dropped fast and then hovered above zero for minutes.
 * See {@link Coast}. Still never holds a value flat because the monitor has not refreshed it
 * (CLAUDE.md, "the decay IS the instrument").
 */
final class BoatSpeedModel {

    /** Default drag; matches the instrument default and is retuned from the drawer. */
    static final float DEFAULT_DRAG = Coast.REFERENCE_DRAG;
    /**
     * Coast length at the default drag: this many seconds from a crawl, plus
     * {@link #COAST_PER_MPS_S} per m/s. From a typical 2.6 m/s that is 10.5 s to zero.
     */
    static final float COAST_BASE_S = 4f;
    static final float COAST_PER_MPS_S = 2.5f;

    private final Coast coast = new Coast(COAST_BASE_S, COAST_PER_MPS_S);
    private float drag = DEFAULT_DRAG;
    private float attackPerSecond = 4.0f;
    private float target;
    private float shown;
    private boolean driving;
    private boolean paddleTurning = true;
    private long lastUpdateMs;

    void setDrag(float k) {
        this.drag = Math.max(0f, k);
        coast.setDrag(drag);
    }

    float drag() {
        return drag;
    }

    /**
     * @param metresPerSecond latest reported speed
     * @param driving         whether strokes are still pushing the boat; false starts the coast
     *                        even if the reported average has not caught up yet
     */
    void setTarget(double metresPerSecond, boolean driving, long nowMs) {
        this.target = (float) Math.max(0, metresPerSecond);
        this.driving = driving;
        this.lastUpdateMs = nowMs;
    }

    /** Pulses still arriving. Once they stop, the rest of a coast finishes promptly. */
    void setPaddleTurning(boolean turning) {
        this.paddleTurning = turning;
    }

    /** Advances the model by {@code dt} seconds. */
    void step(float dt, long nowMs) {
        if (dt <= 0f) {
            return;
        }
        boolean stale = lastUpdateMs == 0 || nowMs - lastUpdateMs > 1200;
        boolean coasting = stale || !driving;
        if (coasting) {
            shown = coast.step(shown, dt, !paddleTurning);
        } else {
            coast.cancel();
            if (target >= shown) {
                shown += (target - shown) * Math.min(1f, attackPerSecond * dt);
            } else {
                shown = coast.fallToward(shown, target, dt);
            }
        }
        if (shown < 0.02f && (coasting || target <= 0f)) {
            shown = 0f;
        }
    }

    /** Metres per second the boat is doing right now, coast included. */
    float value() {
        return shown;
    }

    boolean isCoasting() {
        return !driving && shown > 0f;
    }

    void reset() {
        target = 0f;
        shown = 0f;
        driving = false;
        lastUpdateMs = 0;
        coast.cancel();
    }
}
