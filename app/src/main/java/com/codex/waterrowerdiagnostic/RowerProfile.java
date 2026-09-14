package com.codex.waterrowerdiagnostic;

/**
 * The rower's own range - low, typical and high power, speed, stroke rate and split - learned from
 * their sessions, so games set targets from the person rowing rather than from a constant.
 *
 * <p>Every "the bar won't move" complaint on this project traced to a threshold set without real
 * numbers (Rocket Launch asked for 130 W to hover against a 129 W median). The fix then was to tune
 * by hand to one rower. This does it for anyone: a session with at least {@link #MIN_MINUTES} of
 * rowing blends its 10th, 50th and 90th percentiles into the stored profile, weighted by how much
 * rowing it contained.
 *
 * <p>Starts from the measured envelope of the rower this was built with (CLAUDE.md, "Tune games to
 * THIS envelope"). Pure Java; persisted by the activity as a string.
 */
final class RowerProfile {

    static final double MIN_MINUTES = 3.0;
    private static final int MAX_SAMPLES = 7200;   // two hours at one sample a second
    /** Minutes of history a new session is weighed against, so the profile adapts but not wildly. */
    private static final double INERTIA_MINUTES = 20.0;

    /** 10th / 50th / 90th percentile. */
    final double[] watts = {93, 129, 162};
    final double[] speed = {3.0, 3.85, 4.06};
    final double[] rate = {23, 25, 26};
    /**
     * Seconds per 500 m at the low, typical and high speed - always derived from {@link #speed}, so
     * the two can never disagree. The first version hard-coded 128 s against 3.85 m/s (129.9 s).
     */
    final double[] split = new double[3];
    double minutes;

    RowerProfile() {
        deriveSplits();
    }

    private void deriveSplits() {
        for (int i = 0; i < 3; i++) {
            split[i] = 500.0 / speed[i];
        }
    }

    private final float[] sWatts = new float[MAX_SAMPLES];
    private final float[] sSpeed = new float[MAX_SAMPLES];
    private final float[] sRate = new float[MAX_SAMPLES];
    private int samples;

    /** One sample per second of actual rowing. Ignores readings that are not rowing. */
    synchronized void sample(double wattsNow, double speedNow, double rateNow) {
        if (speedNow < 1.0 || rateNow < 10 || samples >= MAX_SAMPLES) {
            return;
        }
        sWatts[samples] = (float) Math.max(0, wattsNow);
        sSpeed[samples] = (float) speedNow;
        sRate[samples] = (float) rateNow;
        samples++;
    }

    synchronized int sessionSamples() {
        return samples;
    }

    /**
     * Blends this session into the profile and starts a new one.
     *
     * @return true when the session had enough rowing to count
     */
    synchronized boolean commitSession() {
        double sessionMinutes = samples / 60.0;
        if (sessionMinutes < MIN_MINUTES) {
            return false;
        }
        double alpha = Math.min(0.6, sessionMinutes / (sessionMinutes + Math.max(INERTIA_MINUTES, Math.min(minutes, 120))));
        blend(watts, percentiles(sWatts, samples), alpha);
        double[] sp = percentiles(sSpeed, samples);
        blend(speed, sp, alpha);
        blend(rate, percentiles(sRate, samples), alpha);
        deriveSplits();
        minutes += sessionMinutes;
        samples = 0;
        return true;
    }

    /* ---------- what games ask for ---------- */

    double typicalWatts() {
        return watts[1];
    }

    double lowWatts() {
        return watts[0];
    }

    double highWatts() {
        return watts[2];
    }

    double typicalSpeed() {
        return speed[1];
    }

    double lowSpeed() {
        return speed[0];
    }

    double highSpeed() {
        return speed[2];
    }

    double typicalRate() {
        return rate[1];
    }

    /** Seconds per 500 m at the rower's typical speed. */
    double typicalSplit() {
        return split[1];
    }

    /** A fraction of the way from the rower's low to high watts; 0.5 is roughly typical. */
    double wattsAt(double fraction) {
        return lerp3(watts, fraction);
    }

    double speedAt(double fraction) {
        return lerp3(speed, fraction);
    }

    /* ---------- persistence ---------- */

    synchronized String encode() {
        return String.format(java.util.Locale.US, "1;%.1f,%.1f,%.1f;%.3f,%.3f,%.3f;%.2f,%.2f,%.2f;%.1f",
                watts[0], watts[1], watts[2], speed[0], speed[1], speed[2], rate[0], rate[1], rate[2], minutes);
    }

    synchronized boolean decode(String text) {
        if (text == null) {
            return false;
        }
        String[] parts = text.split(";");
        if (parts.length != 5 || !"1".equals(parts[0])) {
            return false;
        }
        try {
            double[] w = triple(parts[1]);
            double[] s = triple(parts[2]);
            double[] r = triple(parts[3]);
            double m = Double.parseDouble(parts[4]);
            if (s[0] <= 0 || s[1] <= 0 || s[2] <= 0) {
                return false;
            }
            System.arraycopy(w, 0, watts, 0, 3);
            System.arraycopy(s, 0, speed, 0, 3);
            System.arraycopy(r, 0, rate, 0, 3);
            deriveSplits();
            minutes = m;
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /* ---------- helpers ---------- */

    static double[] percentiles(float[] values, int n) {
        float[] copy = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(copy);
        return new double[]{copy[(int) (0.1 * (n - 1))], copy[(int) (0.5 * (n - 1))], copy[(int) (0.9 * (n - 1))]};
    }

    private static void blend(double[] into, double[] session, double alpha) {
        for (int i = 0; i < 3; i++) {
            into[i] = into[i] * (1 - alpha) + session[i] * alpha;
        }
    }

    private static double lerp3(double[] v, double f) {
        if (f <= 0.5) {
            return v[0] + (v[1] - v[0]) * Math.max(0, f) / 0.5;
        }
        return v[1] + (v[2] - v[1]) * Math.min(1, (f - 0.5) / 0.5);
    }

    private static double[] triple(String text) {
        String[] p = text.split(",");
        return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1]), Double.parseDouble(p[2])};
    }
}
