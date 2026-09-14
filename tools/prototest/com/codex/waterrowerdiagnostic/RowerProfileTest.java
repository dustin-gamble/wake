package com.codex.waterrowerdiagnostic;

/** Offline checks for the learned rower profile that games tune themselves to. */
public class RowerProfileTest {

    private static int failures;

    public static void main(String[] args) {
        startsFromTheMeasuredEnvelope();
        ignoresShortSessionsAndNonRowing();
        movesTowardAStrongerRower();
        adaptsGraduallyNotWildly();
        roundTripsThroughItsStoredForm();
        interpolatesBetweenLowTypicalHigh();
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll rower profile checks passed");
    }

    private static void row(RowerProfile p, int seconds, double watts, double speed, double rate) {
        for (int i = 0; i < seconds; i++) {
            double wobble = (i % 10 - 4.5) / 4.5;   // a spread around the typical value
            p.sample(watts * (1 + 0.15 * wobble), speed * (1 + 0.05 * wobble), rate + wobble);
        }
    }

    private static void startsFromTheMeasuredEnvelope() {
        RowerProfile p = new RowerProfile();
        check("default typical watts 129", p.typicalWatts(), 129.0);
        check("default typical split about 2:10", Math.abs(p.typicalSplit() - 130) < 2, true);
    }

    private static void ignoresShortSessionsAndNonRowing() {
        RowerProfile p = new RowerProfile();
        row(p, 120, 300, 5, 30);                      // two minutes only
        check("a two-minute session does not count", p.commitSession(), false);
        RowerProfile q = new RowerProfile();
        for (int i = 0; i < 600; i++) q.sample(0, 0.2, 0);   // sitting on the machine
        check("no rowing, no samples", q.sessionSamples(), 0);
    }

    private static void movesTowardAStrongerRower() {
        RowerProfile p = new RowerProfile();
        row(p, 30 * 60, 220, 4.4, 28);
        check("a 30-minute session counts", p.commitSession(), true);
        System.out.printf("   after one strong session: typical %.0f W, %.2f m/s, %.1f spm%n",
                p.typicalWatts(), p.typicalSpeed(), p.typicalRate());
        check("typical watts moved up toward 220", p.typicalWatts() > 170 && p.typicalWatts() < 220, true);
        for (int s = 0; s < 10; s++) {
            row(p, 30 * 60, 220, 4.4, 28);
            p.commitSession();
        }
        // Compare against the session's own median, not the nominal 220: the synthetic spread is
        // asymmetric around the middle sample, so its median is a little under 220.
        RowerProfile scratch = new RowerProfile();
        row(scratch, 30 * 60, 220, 4.4, 28);
        float[] w = new float[30 * 60];
        for (int i = 0; i < w.length; i++) {
            double wobble = (i % 10 - 4.5) / 4.5;
            w[i] = (float) (220 * (1 + 0.15 * wobble));
        }
        double sessionMedian = RowerProfile.percentiles(w, w.length)[1];
        System.out.printf("   after 11 sessions: typical %.1f W vs session median %.1f W%n", p.typicalWatts(), sessionMedian);
        check("after many sessions it settles on the rower", Math.abs(p.typicalWatts() - sessionMedian) / sessionMedian < 0.03, true);
        check("split follows speed", Math.abs(p.typicalSplit() - 500 / p.typicalSpeed()) < 1e-9, true);
    }

    private static void adaptsGraduallyNotWildly() {
        RowerProfile p = new RowerProfile();
        for (int s = 0; s < 8; s++) {
            row(p, 30 * 60, 130, 3.85, 25);
            p.commitSession();
        }
        double before = p.typicalWatts();
        row(p, 4 * 60, 60, 2.8, 18);                   // one short easy day
        p.commitSession();
        System.out.printf("   an easy 4-minute day moved typical watts %.0f -> %.0f%n", before, p.typicalWatts());
        check("one easy short day moves it by under 20%", (before - p.typicalWatts()) / before < 0.20, true);
    }

    private static void roundTripsThroughItsStoredForm() {
        RowerProfile p = new RowerProfile();
        row(p, 20 * 60, 180, 4.1, 27);
        p.commitSession();
        RowerProfile q = new RowerProfile();
        check("decodes what it encoded", q.decode(p.encode()), true);
        check("same typical watts after a round trip", Math.abs(q.typicalWatts() - p.typicalWatts()) < 0.1, true);
        check("rejects garbage", new RowerProfile().decode("nonsense"), false);
    }

    private static void interpolatesBetweenLowTypicalHigh() {
        RowerProfile p = new RowerProfile();
        check("fraction 0 is low", p.wattsAt(0), p.lowWatts());
        check("fraction 0.5 is typical", p.wattsAt(0.5), p.typicalWatts());
        check("fraction 1 is high", p.wattsAt(1), p.highWatts());
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) failures++;
        System.out.printf("%s %s%s%n", ok ? "PASS" : "FAIL", label, ok ? "" : "  (got " + actual + ")");
    }
}
