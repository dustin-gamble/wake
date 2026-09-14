package com.codex.waterrowerdiagnostic;

/** Offline checks for the shared coast that games and gauges depend on. */
public class BoatSpeedModelTest {

    private static final float DT = 0.016f;
    private static int failures;

    public static void main(String[] args) {
        climbsToAReading();
        shapeHangsThenLandsOnZero();
        holdsThroughAStrokeGapThenLandsOnZero();
        alwaysFallingWhileCoasting();
        finishesOnceThePaddleStops();
        neverFallsFasterThanACoast();
        goesStaleWithoutReadings();
        lowerDragGlidesLonger();
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll boat model checks passed");
    }

    /** Rows at {@code v} for two seconds with fresh readings; returns the clock. */
    private static long drive(BoatSpeedModel m, double v) {
        long t = 1000;
        for (int i = 0; i < 125; i++) {
            t += 16;
            if (i % 30 == 0) m.setTarget(v, true, t);
            m.step(DT, t);
        }
        return t;
    }

    private static long run(BoatSpeedModel m, long t, float seconds) {
        int n = Math.round(seconds / DT);
        for (int i = 0; i < n; i++) {
            t += 16;
            m.step(DT, t);
        }
        return t;
    }

    private static float coastSeconds(float from) {
        return new Coast(BoatSpeedModel.COAST_BASE_S, BoatSpeedModel.COAST_PER_MPS_S).duration(from);
    }

    private static void climbsToAReading() {
        BoatSpeedModel m = new BoatSpeedModel();
        drive(m, 3.0);
        check("climbs close to 3.0 within 2s", Math.abs(m.value() - 3.0f) < 0.15f, true);
    }

    private static void shapeHangsThenLandsOnZero() {
        check("shape starts at 1", Coast.shape(0f) == 1f, true);
        check("shape ends at exactly 0", Coast.shape(1f) == 0f, true);
        check("shape hangs early (> 0.9 at 10%)", Coast.shape(0.1f) > 0.9f, true);
        boolean monotonic = true;
        for (int i = 1; i <= 100; i++) {
            if (!(Coast.shape(i / 100f) < Coast.shape((i - 1) / 100f))) monotonic = false;
        }
        check("shape falls on every step, never flat", monotonic, true);
    }

    private static void holdsThroughAStrokeGapThenLandsOnZero() {
        BoatSpeedModel m = new BoatSpeedModel();
        long t = drive(m, 3.0);
        float before = m.value();
        float total = coastSeconds(before);
        m.setTarget(3.0, false, t);   // drive ends; the monitor still reports its stale average
        t = run(m, t, 2.4f);          // one stroke gap at 25 spm
        check("still coasting flag", m.isCoasting(), true);
        check("holds most of its speed through a stroke gap", m.value() > before * 0.85f, true);
        t = run(m, t, total * 0.5f - 2.4f);
        check("slowing through the middle", m.value() > before * 0.3f && m.value() < before * 0.7f, true);
        run(m, t, total * 0.5f + 0.2f);
        // The bug this replaced: quadratic drag was still above 0.2 m/s ninety seconds later.
        check("lands on exactly zero at the end, no tail", m.value() == 0f, true);
        check("a typical coast is over within 15 s", total < 15f, true);
    }

    private static void alwaysFallingWhileCoasting() {
        BoatSpeedModel m = new BoatSpeedModel();
        long t = drive(m, 3.0);
        m.setTarget(3.0, false, t);
        float prev = m.value();
        boolean ok = true;
        for (int i = 0; i < 1000 && prev > 0f; i++) {
            t += 16;
            m.step(DT, t);
            if (!(m.value() < prev)) ok = false;
            prev = m.value();
        }
        check("falls on every frame until zero, never held flat", ok && prev == 0f, true);
    }

    private static void finishesOnceThePaddleStops() {
        BoatSpeedModel m = new BoatSpeedModel();
        long t = drive(m, 3.0);
        m.setTarget(3.0, false, t);
        t = run(m, t, 2f);
        check("coast under way before pulses stop", m.value() > 1f, true);
        m.setPaddleTurning(false);
        run(m, t, Coast.STOP_FINISH_S + 0.1f);
        check("zero within STOP_FINISH_S once the paddle stops", m.value() == 0f, true);
    }

    private static void neverFallsFasterThanACoast() {
        BoatSpeedModel m = new BoatSpeedModel();
        long t = drive(m, 3.0);
        // Monitor suddenly reports zero while we are still "driving": must not snap.
        m.setTarget(0.0, true, t);
        t += 16;
        m.step(DT, t);
        check("one frame after a zero reading, still near 3.0", m.value() > 2.8f, true);
    }

    private static void goesStaleWithoutReadings() {
        BoatSpeedModel m = new BoatSpeedModel();
        long t = drive(m, 3.0);
        float before = m.value();
        // No readings at all for 4 seconds: treated as coasting, not held flat.
        run(m, t, 4f);
        check("stale readings do not hold the value flat", m.value() < before * 0.9f, true);
        check("but the coast is not over after 4 s", m.value() > 0f, true);
    }

    private static void lowerDragGlidesLonger() {
        Coast normal = new Coast(BoatSpeedModel.COAST_BASE_S, BoatSpeedModel.COAST_PER_MPS_S);
        Coast glide = new Coast(BoatSpeedModel.COAST_BASE_S, BoatSpeedModel.COAST_PER_MPS_S);
        glide.setDrag(0.02f);
        check("drag 0.02 coasts twice as long as the default",
                Math.abs(glide.duration(3f) - 2f * normal.duration(3f)) < 0.01f, true);
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) failures++;
        System.out.printf("%s %s%n", ok ? "PASS" : "FAIL", label);
    }
}
