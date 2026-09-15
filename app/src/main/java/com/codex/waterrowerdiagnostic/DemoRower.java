package com.codex.waterrowerdiagnostic;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A simulated S4 monitor for automated testing on an emulator, where there is no rower.
 *
 * <p>Produces the same bytes the monitor sends - {@code Pxx} pulse counts every 25 ms and
 * unsolicited {@code IDx} memory replies - from the flywheel model {@code PulseMeterTest} uses, so
 * everything downstream (the protocol, the pulse meter, the coast, every game) runs unchanged.
 * Local builds only; it is started by an adb intent extra and never by the app itself.
 *
 * <p>The rowing follows a 90 s script so screenshots catch steady rowing, a surge, easy strokes
 * and a short stop: {@link #intensityAt(double)}.
 */
final class DemoRower {

    interface Sink {
        void accept(byte[] bytes, int count);
    }

    /** The same paddle the pulse meter test uses: ~130 W at ~400 pulses/s. */
    static final double C = 0.0005;
    static final double I = 0.004;
    static final double PULSES_PER_METRE = 100.0;
    static final double DRIVE_S = 0.8;

    private final double baseWatts;
    private double w;
    private double carry;
    private double t;
    private double strokeT;
    private double strokeWork;
    private double lastStrokeWatts;
    private int strokes;
    private double metres;
    private double avgSpeed;
    private int replySlot;
    private double nextReplyT;
    private volatile boolean running;
    private Thread thread;

    DemoRower(double baseWatts) {
        this.baseWatts = baseWatts <= 0 ? 130 : baseWatts;
    }

    /** 1 is the base effort. Steady, surge, easy, stop, repeat every 90 s. */
    static double intensityAt(double seconds) {
        double p = seconds % 90;
        if (p < 40) return 1.0 + 0.08 * Math.sin(p * 0.7);
        if (p < 52) return 1.7;
        if (p < 72) return 0.65;
        if (p < 80) return 0;
        return 1.0;
    }

    static double rateAt(double seconds) {
        double p = seconds % 90;
        if (p >= 40 && p < 52) return 31;
        if (p >= 52 && p < 72) return 20;
        return 25;
    }

    void start(Sink sink) {
        running = true;
        thread = new Thread(() -> {
            long started = System.currentTimeMillis();
            long ticks = 0;
            StringBuilder out = new StringBuilder();
            while (running) {
                out.setLength(0);
                step25(out);
                if (out.length() > 0) {
                    byte[] bytes = out.toString().getBytes(StandardCharsets.US_ASCII);
                    sink.accept(bytes, bytes.length);
                }
                ticks++;
                long due = started + ticks * 25;
                long wait = due - System.currentTimeMillis();
                if (wait > 0) {
                    try {
                        Thread.sleep(wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "demo-rower");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /** Advances 25 ms in 1 ms steps and appends whatever the monitor would have sent. */
    void step25(StringBuilder out) {
        double intensity = intensityAt(t);
        double period = 60.0 / rateAt(t);
        // Average drive power is peak x 2/pi x drive/period, so solve for the peak.
        double peak = baseWatts * intensity * period / (DRIVE_S * 2 / Math.PI);
        for (int ms = 0; ms < 25; ms++) {
            double dt = 0.001;
            double power = 0;
            if (intensity > 0 && strokeT < DRIVE_S) {
                power = peak * Math.sin(Math.PI * strokeT / DRIVE_S);
            }
            double dw = (power / (I * Math.max(w, 1)) - C * w * w) * dt;
            w = Math.max(0, w + dw);
            if (w < 2 && power == 0) {
                w = 0;
            }
            carry += w * dt;
            strokeWork += power * dt;
            t += dt;
            strokeT += dt;
            if (strokeT >= period) {
                if (strokeWork > 1) {
                    strokes++;
                    lastStrokeWatts = strokeWork / period;
                }
                strokeWork = 0;
                strokeT = 0;
            }
        }
        double speed = w / PULSES_PER_METRE;
        avgSpeed += (speed - avgSpeed) * 0.02;
        metres += speed * 0.025;
        if (intensity == 0 && w == 0) {
            lastStrokeWatts = 0;
        }

        int count = (int) Math.floor(carry);
        carry -= count;
        if (w > 0 || count > 0) {
            out.append(String.format(Locale.US, "P%02X\r\n", Math.min(count, 255)));
        }
        if (t >= nextReplyT) {
            nextReplyT = t + 0.15;
            appendReply(out, replySlot++ % 7);
        }
    }

    private void appendReply(StringBuilder out, int slot) {
        switch (slot) {
            case 0:
                out.append(String.format(Locale.US, "IDD088%04X\r\n", (int) Math.round(lastStrokeWatts)));
                break;
            case 1:
                out.append(String.format(Locale.US, "IDD14A%04X\r\n", (int) Math.round(avgSpeed * 100)));
                break;
            case 2:
                out.append(String.format(Locale.US, "IDS1A9%02X\r\n",
                        w > 0 ? (int) Math.round(rateAt(t)) : 0));
                break;
            case 3:
                out.append(String.format(Locale.US, "IDD140%04X\r\n", strokes));
                break;
            case 4:
                out.append(String.format(Locale.US, "IDD057%04X\r\n", (int) metres));
                break;
            case 5:
                out.append(String.format(Locale.US, "IDS1E1%02d\r\n", ((int) t) % 60));
                break;
            default:
                out.append("IDD1A00000\r\n");
                break;
        }
    }

    // For the offline test.
    int strokes() { return strokes; }
    double metres() { return metres; }
    double lastStrokeWatts() { return lastStrokeWatts; }
    double speed() { return w / PULSES_PER_METRE; }
}
