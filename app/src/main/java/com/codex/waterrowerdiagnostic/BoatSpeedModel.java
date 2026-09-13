package com.codex.waterrowerdiagnostic;

/**
 * The boat's speed as it should be shown: rising through each drive, decaying under water drag
 * after it. Pure Java so games and tests can use it without a View.
 *
 * <p>Same physics as the speed gauge: quadratic drag, {@code dv/dt = -k v^2}, giving
 * {@code v(t) = v0 / (1 + k v0 t)}. See CLAUDE.md, "the decay IS the instrument": never hold a
 * value flat because the monitor has not refreshed it.
 */
final class BoatSpeedModel {

    /** Default drag; matches the instrument default and is retuned from the drawer. */
    static final float DEFAULT_DRAG = 0.12f;

    private float drag = DEFAULT_DRAG;
    private float attackPerSecond = 4.0f;
    private float target;
    private float shown;
    private boolean driving;
    private long lastUpdateMs;

    void setDrag(float k) {
        this.drag = Math.max(0f, k);
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

    /** Advances the model by {@code dt} seconds. */
    void step(float dt, long nowMs) {
        if (dt <= 0f) {
            return;
        }
        boolean stale = lastUpdateMs == 0 || nowMs - lastUpdateMs > 1200;
        boolean coasting = stale || !driving;
        if (coasting && shown > 0f) {
            shown -= drag * shown * shown * dt;
        } else {
            float aim = stale ? 0f : target;
            if (aim >= shown) {
                shown += (aim - shown) * Math.min(1f, attackPerSecond * dt);
            } else {
                // Falling: never quicker than drag allows. A reading that drops suddenly is the
                // monitor's average catching up, not the boat stopping dead.
                shown = Math.max(aim, shown - drag * shown * shown * dt);
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
    }
}
