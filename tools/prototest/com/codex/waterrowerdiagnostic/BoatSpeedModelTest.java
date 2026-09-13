package com.codex.waterrowerdiagnostic;

/** Offline checks for the shared coast model that games and gauges depend on. */
public class BoatSpeedModelTest {

    private static int failures;

    public static void main(String[] args) {
        climbsToAReading();
        coastsWhenDrivingStops();
        neverFallsFasterThanDrag();
        goesStaleWithoutReadings();
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll boat model checks passed");
    }

    private static BoatSpeedModel run(BoatSpeedModel m, long fromMs, float seconds) {
        long t = fromMs;
        for (int i = 0; i < seconds * 60; i++) {
            t += 16;
            m.step(1 / 60f, t);
        }
        return m;
    }

    private static void climbsToAReading() {
        BoatSpeedModel m = new BoatSpeedModel();
        m.setTarget(3.0, true, 1000);
        // Keep the reading fresh while it climbs.
        long t = 1000;
        for (int i = 0; i < 120; i++) {
            t += 16;
            if (i % 30 == 0) m.setTarget(3.0, true, t);
            m.step(1 / 60f, t);
        }
        check("climbs close to 3.0 within 2s", Math.abs(m.value() - 3.0f) < 0.15f, true);
    }

    private static void coastsWhenDrivingStops() {
        BoatSpeedModel m = new BoatSpeedModel();
        m.setDrag(0.12f);
        long t = 1000;
        for (int i = 0; i < 120; i++) { t += 16; if (i % 30 == 0) m.setTarget(3.0, true, t); m.step(1 / 60f, t); }
        float before = m.value();
        // Drive ends: keep reporting the stale 3.0 average, but driving=false.
        m.setTarget(3.0, false, t);
        run(m, t, 3f);
        check("still coasting flag", m.isCoasting(), true);
        check("decays even though reported speed is unchanged", m.value() < before * 0.6f, true);
        // Analytic: v = v0 / (1 + k v0 t) with k=0.12, v0~3, t=3 -> ~1.44
        check("follows quadratic drag curve", Math.abs(m.value() - 1.44f) < 0.25f, true);
    }

    private static void neverFallsFasterThanDrag() {
        BoatSpeedModel m = new BoatSpeedModel();
        m.setDrag(0.12f);
        long t = 1000;
        for (int i = 0; i < 120; i++) { t += 16; if (i % 30 == 0) m.setTarget(3.0, true, t); m.step(1 / 60f, t); }
        // Monitor suddenly reports zero while we are still "driving": must not snap.
        m.setTarget(0.0, true, t);
        t += 16;
        m.step(1 / 60f, t);
        check("one frame after a zero reading, still near 3.0", m.value() > 2.8f, true);
    }

    private static void goesStaleWithoutReadings() {
        BoatSpeedModel m = new BoatSpeedModel();
        long t = 1000;
        for (int i = 0; i < 120; i++) { t += 16; if (i % 30 == 0) m.setTarget(3.0, true, t); m.step(1 / 60f, t); }
        // No readings at all for 4 seconds: treated as coasting, not held flat.
        run(m, t, 4f);
        check("stale readings do not hold the value flat", m.value() < 2.0f, true);
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) failures++;
        System.out.printf("%s %s%n", ok ? "PASS" : "FAIL", label);
    }
}
