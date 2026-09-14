package com.codex.waterrowerdiagnostic;

/**
 * Checks PulseMeter against a simulated paddle whose drag, inertia and applied power are known,
 * with the same integer 25 ms counts the monitor sends. What comes out must match what went in.
 */
public class PulseMeterTest {

    /** Chosen to look like this machine: ~130 W at ~400 pulses/s. */
    static final double C = 0.0005;
    static final double I = 0.004;
    static final double PPM = 95.0;          // simulated pulses per boat metre
    static final double HANDLE_M_PER_PULSE = 0.004;

    private static int failures;

    public static void main(String[] args) {
        countsEveryPulse();
        learnsDragFromCoasts();
        countsStrokesAndSplitsDriveFromRecovery();
        steadyPullGivesHandleTravel();
        measuresWorkWhenCalibrated();
        matchesMonitorScaleBeforeCalibration();
        learnsPulsesPerMetreFromDistance();
        reportsZeroOnceThePaddleStops();
        inertiaFromAPullRoundTrips();
        squareLawFitIsPerfectForWater();
        kcalUsesQuarterEfficiency();
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll pulse meter checks passed");
    }

    /** A simulated rower: 2.4 s strokes, a 0.8 s sine-shaped drive, then a free coast. */
    static final class Sim {
        final PulseMeter meter = new PulseMeter();
        double w = 30;
        double carry;
        double tMs = 1000;
        double workIn;
        long pulsesOut;
        double drivePulsesTrue;
        double lastStrokeDrivePulses;
        int strokes;
        double strokeWork;
        double lastStrokeWork;
        int wattsForMonitor;
        PulseMeter.Stroke pullRecord;
        private PulseMeter.Stroke seen;

        void row(int strokeCount, double peakPower) {
            for (int s = 0; s < strokeCount; s++) {
                strokeWork = 0;
                drivePulsesTrue = 0;
                for (int ms = 0; ms < 2400; ms++) {
                    double t = ms / 1000.0;
                    double p = t < 0.8 ? peakPower * Math.sin(Math.PI * t / 0.8) : 0;
                    step(p, t < 0.8);
                }
                strokes++;
                lastStrokeWork = strokeWork;
                lastStrokeDrivePulses = drivePulsesTrue;
                wattsForMonitor = (int) Math.round(strokeWork / 2.4);
            }
        }

        void step(double power, boolean drive) {
            double dt = 0.001;
            double dw = (power / (I * Math.max(w, 1)) - C * w * w) * dt;
            w = Math.max(0, w + dw);
            workIn += power * dt;
            strokeWork += power * dt;
            carry += w * dt;
            if (drive) {
                drivePulsesTrue += w * dt;
            }
            tMs += 1;
            if (((long) tMs) % 25 == 0) {
                int count = (int) Math.floor(carry);
                carry -= count;
                pulsesOut += count;
                meter.onPulse(count, (long) tMs, wattsForMonitor);
                PulseMeter.Stroke now = meter.reading((long) tMs).lastStroke;
                if (now != seen) {
                    if (now != null && now.plateauRate > 250 && now.plateauRate < 390 && now.driveSeconds > 1.2) {
                        pullRecord = now;
                    }
                    seen = now;
                }
            }
        }

        /** Stop pulling and wait for the paddle to go quiet, then a gap longer than GAP_MS. */
        void stopAndWait() {
            for (int ms = 0; ms < 8000; ms++) {
                step(0, false);
            }
            tMs += 2000;
            meter.reading((long) tMs);
        }
    }

    private static void countsEveryPulse() {
        Sim sim = new Sim();
        sim.row(10, 900);
        check("total pulses equal what the paddle produced",
                sim.meter.reading((long) sim.tMs).totalPulses == sim.pulsesOut, true);
    }

    private static void learnsDragFromCoasts() {
        Sim sim = new Sim();
        sim.row(30, 900);
        double c = sim.meter.reading((long) sim.tMs).dragPerInertia;
        System.out.printf("   drag/inertia learned %.6f vs true %.6f (%d fits)%n", c, C,
                sim.meter.reading((long) sim.tMs).coastFits);
        check("drag learned from coasts within 10%", Math.abs(c - C) / C < 0.10, true);
    }

    private static void countsStrokesAndSplitsDriveFromRecovery() {
        Sim sim = new Sim();
        sim.meter.setHandleMetresPerPulse(HANDLE_M_PER_PULSE);
        sim.row(20, 900);
        sim.stopAndWait();
        PulseMeter.Reading r = sim.meter.reading((long) sim.tMs);
        System.out.printf("   strokes %d of 20%n", r.strokes);
        check("counts every stroke", r.strokes, 20);
        sim = new Sim();
        sim.row(12, 900);
        r = sim.meter.reading((long) sim.tMs);
        PulseMeter.Stroke last = r.lastStroke;
        System.out.printf("   drive %.1f s recovery %.1f s, drive pulses %d vs true %.0f%n",
                last.driveSeconds, last.recoverySeconds, last.drivePulses, sim.lastStrokeDrivePulses);
        check("drive time about 0.8 s", Math.abs(last.driveSeconds - 0.8) <= 0.15, true);
        // A rowing stroke's force fades smoothly to nothing: over its last ~70 ms the hand pushes
        // less than the water drags, so there is no sharp release and the measured drive is the
        // effective one - 14% short of the sine's full length here. The calibration does not use
        // rowing strokes; it uses a steady pull, which is pinned at 4% below.
        check("effective drive pulses within 15% of the true drive",
                Math.abs(last.drivePulses - sim.lastStrokeDrivePulses) / sim.lastStrokeDrivePulses < 0.15, true);
    }

    /**
     * The handle calibration: pull at a steady speed, then let go. The hand holds the paddle at a
     * fixed rate (the pull), then it coasts. Drive pulses must match the pull closely, because the
     * handle travel per pulse - and through it the calibrated energy - is computed from them.
     */
    private static void steadyPullGivesHandleTravel() {
        Sim sim = new Sim();
        sim.row(15, 900);                    // learn drag first, as the calibration screen requires
        sim.stopAndWait();                   // the test is done from rest, handle at the catch
        sim.w = 0;
        double pullPulses = 0;
        double target = 320;
        for (int ms = 0; ms < 2600; ms++) {  // ramp up over 0.2 s, then hold steady
            double wanted = target * Math.min(1.0, ms / 200.0);
            if (sim.w < wanted) sim.w = wanted;
            pullPulses += sim.w * 0.001;
            sim.carry += sim.w * 0.001;
            sim.tMs += 1;
            if (((long) sim.tMs) % 25 == 0) {
                int count = (int) Math.floor(sim.carry);
                sim.carry -= count;
                sim.pulsesOut += count;
                sim.meter.onPulse(count, (long) sim.tMs, 0);
            }
        }
        for (int ms = 0; ms < 2600; ms++) sim.step(0, false);
        sim.row(1, 900);                     // the next stroke closes the pull's record
        PulseMeter.Stroke pull = null;
        // The pull is the stroke before the last.
        PulseMeter.Reading r = sim.meter.reading((long) sim.tMs);
        pull = sim.pullRecord;
        System.out.printf("   steady pull: drive pulses %s vs true %.0f%n",
                pull == null ? "none" : String.valueOf(pull.drivePulses), pullPulses);
        check("steady pull recorded", pull != null, true);
        if (pull != null) {
            check("steady pull drive pulses within 4%",
                    Math.abs(pull.drivePulses - pullPulses) / pullPulses < 0.04, true);
            check("plateau rate is the steady rate", Math.abs(pull.plateauRate - target) / target < 0.03, true);
        }
    }

    private static void measuresWorkWhenCalibrated() {
        Sim sim = new Sim();
        sim.meter.setInertia(I);
        sim.row(40, 900);
        sim.stopAndWait();
        PulseMeter.Reading r = sim.meter.reading((long) sim.tMs);
        System.out.printf("   work measured %.0f J vs true %.0f J (%s)%n", r.workJoules, sim.workIn, r.source);
        check("source is calibrated pulses", r.source, PulseMeter.EnergySource.PULSES_CALIBRATED);
        check("session work within 5% of the truth", Math.abs(r.workJoules - sim.workIn) / sim.workIn < 0.05, true);
        PulseMeter.Stroke last = r.lastStroke;
        check("a stroke carries its work and power shape",
                !Double.isNaN(last.workJoules) && last.drivePower != null, true);
    }

    private static void matchesMonitorScaleBeforeCalibration() {
        Sim sim = new Sim();
        sim.row(60, 900);
        sim.stopAndWait();
        PulseMeter.Reading r = sim.meter.reading((long) sim.tMs);
        System.out.printf("   monitor-scaled inertia %.5f vs true %.5f, work %.0f vs %.0f J%n",
                r.inertia, I, r.workJoules, sim.workIn);
        check("source is monitor-scaled pulses", r.source, PulseMeter.EnergySource.PULSES_MONITOR_SCALED);
        check("monitor-matched work within 15%", Math.abs(r.workJoules - sim.workIn) / sim.workIn < 0.15, true);
    }

    private static void learnsPulsesPerMetreFromDistance() {
        Sim sim = new Sim();
        for (int i = 0; i < 80; i++) {
            sim.row(1, 900);
            // The register lags: report the distance as it stood about two seconds ago.
            long lagged = Math.max(0, sim.pulsesOut - Math.round(2.0 * 380));
            sim.meter.onDistance((int) (lagged / PPM));
        }
        PulseMeter.Reading r = sim.meter.reading((long) sim.tMs);
        System.out.printf("   pulses per metre %.1f vs true %.1f%n", r.pulsesPerMetre, PPM);
        check("pulses per metre within 3%", Math.abs(r.pulsesPerMetre - PPM) / PPM < 0.03, true);
        check("speed reported once known", !Double.isNaN(r.speedMps), true);
    }

    private static void reportsZeroOnceThePaddleStops() {
        Sim sim = new Sim();
        sim.row(5, 900);
        check("rate is live while rowing", sim.meter.reading((long) sim.tMs).paddleRate > 0, true);
        long later = (long) sim.tMs + 1500;
        check("rate is zero after the pulses stop", sim.meter.reading(later).paddleRate, 0.0);
    }

    private static void inertiaFromAPullRoundTrips() {
        double w = 300;
        double force = I * C * w * w / HANDLE_M_PER_PULSE;   // what a scale would read at a steady pull
        double back = PulseMeter.inertiaFromPull(force, w, HANDLE_M_PER_PULSE, C);
        check("inertia recovered from a steady pull", Math.abs(back - I) / I < 1e-9, true);
    }

    private static void squareLawFitIsPerfectForWater() {
        double[] w = {200, 260, 320, 380};
        double[] f = new double[4];
        for (int i = 0; i < 4; i++) f[i] = 0.002 * w[i] * w[i];
        check("force exactly proportional to rate squared fits 1.0",
                Math.abs(PulseMeter.squareLawFit(f, w, 4) - 1.0) < 1e-9, true);
    }

    private static void kcalUsesQuarterEfficiency() {
        check("4184 J of work is 4 kcal", Math.abs(PulseMeter.kcalForWork(4184) - 4.0) < 1e-9, true);
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) failures++;
        System.out.printf("%s %s%s%n", ok ? "PASS" : "FAIL", label, ok ? "" : "  (got " + actual + ")");
    }
}
