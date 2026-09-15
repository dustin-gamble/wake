package com.codex.waterrowerdiagnostic;

/**
 * The emulator's simulated monitor has to look like this machine to the real protocol, or every
 * screenshot taken with it tests a game against numbers the rower never produces.
 */
public class DemoRowerTest {

    private static int failures;

    public static void main(String[] args) {
        DemoRower demo = new DemoRower(130);
        S4Protocol protocol = new S4Protocol((packet, status) -> { });
        StringBuilder out = new StringBuilder();
        // 38 s of the steady part of the script, in 25 ms steps.
        for (int i = 0; i < 38 * 40; i++) {
            out.setLength(0);
            demo.step25(out);
            byte[] bytes = out.toString().getBytes();
            protocol.accept(bytes, bytes.length);
        }
        S4Protocol.Status s = protocol.snapshot();
        check("strokes near 25 spm x 38 s", demo.strokes() >= 14 && demo.strokes() <= 17, demo.strokes());
        check("stroke watts near 130", Math.abs(demo.lastStrokeWatts() - 130) < 20, demo.lastStrokeWatts());
        check("paddle speed in the measured 3-4.2 m/s band", demo.speed() > 3.0 && demo.speed() < 4.4, demo.speed());
        check("protocol decodes watts", Math.abs(s.watts - 130) < 20, s.watts);
        check("protocol decodes strokes", s.strokes == demo.strokes(), s.strokes);
        check("protocol sees pulses", s.pulsesSeen > 1000, s.pulsesSeen);
        check("monitor connected", s.monitorConnected, s.monitorConnected);

        // Run on into the stop (72-80 s): no drive, so the paddle must be coasting down. Water drag
        // is quadratic and slow at the tail, so it will not be still yet - just well below cruise.
        double cruise = demo.speed();
        for (int i = 0; i < (79 - 38) * 40; i++) {
            out.setLength(0);
            demo.step25(out);
        }
        check("paddle coasting down in the rest", demo.speed() < cruise * 0.5, demo.speed());
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll demo rower checks passed");
    }

    static void check(String name, boolean ok, Object value) {
        System.out.println((ok ? "ok   " : "FAIL ") + name + " (" + value + ")");
        if (!ok) failures++;
    }
}
