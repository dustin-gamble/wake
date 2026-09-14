package com.codex.waterrowerdiagnostic;

/**
 * The wind-down every instrument shares once the drive stops: it holds near its speed, eases
 * off, then lands on zero. The speed and power gauges, the paddle and the games' boat all use it,
 * so they cannot disagree about when the boat has stopped.
 *
 * <p>Replaced pure quadratic drag, {@code v0 / (1 + k v0 t)}, in 3.12.0. That curve is steepest
 * the moment the drive ends and never reaches zero. Measured on the tablet at the default drag:
 * the needle fell from 2.6 to 0.8 m/s while the monitor still read 2.6, then took another 90 s to
 * crawl from 0.66 to 0.21 and was still above zero - 11 rpm on a paddle at rest. In the rower's
 * words it "drops fast and then is near, above zero for a long time". What they asked for - hang
 * on to speed a little longer, slow down slowly, then go to zero - is this shape.
 *
 * <p>Time-based rather than a drag law: a coast lasts {@link #duration} seconds, longer from a
 * higher speed, and the value follows {@link #shape} across it. Never flat, which this project
 * forbids (CLAUDE.md, "the decay IS the instrument"): {@link #LEAN} keeps it falling from the
 * first frame, just gently. Once the paddle's pulses stop the paddle really has stopped, and
 * whatever is left of the coast finishes within {@link #STOP_FINISH_S}.
 *
 * <p>Pure Java, no Android, so it runs under plain javac with the model tests.
 */
final class Coast {

    /** The drawer's default drag. Durations passed to the constructor are tuned at this value. */
    static final float REFERENCE_DRAG = 0.04f;
    /** Share of the fall that is a straight line, so the value never sits flat. */
    static final float LEAN = 0.2f;
    /** Once pulses stop, the rest of the coast takes no longer than this. */
    static final float STOP_FINISH_S = 1.5f;
    /** Steepest slope of {@link #shape}, at its midpoint: LEAN + (1 - LEAN) * 1.5. */
    private static final float PEAK_SLOPE = LEAN + (1f - LEAN) * 1.5f;

    private final float baseSeconds;
    private final float secondsPerUnit;
    private float scale = 1f;

    private float from;
    private float duration;
    /** Progress through the current coast, 0..1; negative when not coasting. */
    private float progress = -1f;
    private float stopRate;

    /**
     * @param baseSeconds    coast length from a crawl
     * @param secondsPerUnit added per unit of the value the coast starts from (per m/s, per watt)
     */
    Coast(float baseSeconds, float secondsPerUnit) {
        this.baseSeconds = baseSeconds;
        this.secondsPerUnit = secondsPerUnit;
    }

    /** Retunes from the drawer's drag setting: lower drag glides longer. */
    void setDrag(float drag) {
        scale = REFERENCE_DRAG / Math.max(0.005f, drag);
    }

    /** How long a coast starting from {@code startValue} lasts. */
    float duration(float startValue) {
        return scale * (baseSeconds + secondsPerUnit * Math.max(0f, startValue));
    }

    /**
     * Fraction of the starting value left at progress {@code p}: 1 at the start, 0 at the end.
     * Mostly a smoothstep - gentle at first, steepest in the middle, settling at the end - with a
     * straight-line share so it is never perfectly flat.
     */
    static float shape(float p) {
        if (p <= 0f) {
            return 1f;
        }
        if (p >= 1f) {
            return 0f;
        }
        float smooth = p * p * (3f - 2f * p);
        return 1f - (LEAN * p + (1f - LEAN) * smooth);
    }

    /**
     * Advances one frame of coasting and returns the new value.
     *
     * @param shown         the value on screen now; a coast starts from it
     * @param paddleStopped the paddle's pulses have stopped arriving
     */
    float step(float shown, float dt, boolean paddleStopped) {
        if (shown <= 0f) {
            return 0f;
        }
        if (progress < 0f) {
            from = shown;
            duration = duration(shown);
            progress = 0f;
            stopRate = 0f;
        }
        float rate = 1f / Math.max(0.2f, duration);
        if (paddleStopped && stopRate == 0f) {
            stopRate = Math.max(rate, (1f - progress) / STOP_FINISH_S);
        }
        progress = Math.min(1f, progress + Math.max(rate, stopRate) * dt);
        return Math.min(shown, from * shape(progress));
    }

    /** The drive is back: the next coast starts afresh from wherever the value is then. */
    void cancel() {
        progress = -1f;
        stopRate = 0f;
    }

    /**
     * While still driving, falls toward a lower live reading no quicker than a coast from here
     * would. A reading that drops suddenly is the monitor's average catching up, not the paddle
     * stopping dead.
     */
    float fallToward(float shown, float aim, float dt) {
        float seconds = Math.max(0.2f, duration(shown));
        return Math.max(aim, shown - shown * PEAK_SLOPE / seconds * dt);
    }
}
