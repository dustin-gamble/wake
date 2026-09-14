package com.codex.waterrowerdiagnostic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * S4/S5 monitor protocol: line framing, request/response correlation and decode.
 *
 * <p>The monitor talks asynchronously. Stroke pulses ({@code Pxx}), handshakes ({@code _WR_}),
 * keepalives ({@code PING}) and stroke markers ({@code SS}/{@code SE}) arrive whenever the rower
 * moves, interleaved with the {@code IDx} replies to memory reads. Correlation therefore matches
 * on the address echoed back in the reply, never on "some packet arrived".
 */
final class S4Protocol {

    interface Listener {
        void onPacket(String packet, Status status);
    }

    /** How a poll request finished. */
    enum PollResult {
        /** Monitor answered with the value. */
        ANSWERED,
        /** Monitor answered {@code ERROR}: this address/size is not readable. */
        REJECTED,
        /** Nothing came back in time. */
        TIMED_OUT
    }

    static final class Status {
        final boolean monitorConnected;
        final boolean rowing;
        final int elapsedSeconds;
        final int distanceMeters;
        final int strokes;
        final int strokeRate;
        final int watts;
        final int heartRate;
        final int paceSecondsPer500m;
        /** Raw 1A9 value, shown beside the doubled figure so the x2 can be checked on the monitor. */
        final int strokeRateRaw;
        /** Integrated from average speed: address 055 is refused on this unit. */
        final int derivedDistanceMeters;
        final String lastPacket;
        final long lastPacketAgeMs;
        final long packetsSeen;
        final long pulsesSeen;
        /**
         * Pulse packet arrival rate. Measured at a near-constant ~40Hz regardless of effort, so
         * this is the monitor's reporting cadence, NOT flywheel speed. Kept for diagnostics only.
         */
        final double pulseHz;
        /** Raw {@code Pxx} payload. Correlates only weakly with speed (R^2 0.17); diagnostics only. */
        final int lastPulseValue;
        /** Water speed in m/s from address 14A: the signal that actually tracks effort. */
        final double waterSpeedMps;
        /** Distance as the monitor displays it (057/058), when this unit answers that address. */
        final int displayDistanceMeters;
        /** Raw 142: average time for a whole stroke. Units unconfirmed on this machine. */
        final int strokeAvgTimeRaw;
        /** Speed from 148 in m/s, if this unit answers it. Possibly instantaneous, unlike 14A. */
        final double instantSpeedMps;
        /** How long since the speed reading last changed. Large means the monitor is holding it. */
        final long speedUnchangedMs;
        /**
         * True while the rower is still taking strokes.
         *
         * <p>Derived from the stroke counter rather than power: the S4 reports instantaneous
         * watts, which is legitimately zero between strokes (27% of samples measured mid-row),
         * and from the paddle, which keeps spinning long after the drive ends. The counter is
         * exact and ticks once per stroke, so a gap in it is the only reliable "stopped" signal.
         */
        final boolean stillRowing;
        /** A pulse arrived within the last moment: instantaneous proof the flywheel is turning. */
        final boolean flywheelMoving;
        /** Effort from the pulse stream at 40Hz. Liveness, not a calibrated measurement. */
        final double pulseEffort;
        /** Strokes counted from pulses alone - survives a refused write path. */
        final int pulseStrokes;
        /**
         * Stroke rate smoothed for display. See {@link S4Protocol#averagedStrokeRate}; use
         * {@link #strokeRate} for anything that decides something.
         */
        final int strokeRateAverage;
        /** {@link #strokeRateAverage} before rounding, for a display that shows "25.3". */
        final double strokeRatePrecise;
        /** Measurements from the pulse stream: paddle rate, stroke shape, work. Never null. */
        final PulseMeter.Reading meter;

        Status(
                boolean monitorConnected,
                boolean rowing,
                int elapsedSeconds,
                int distanceMeters,
                int strokes,
                int strokeRate,
                int watts,
                int heartRate,
                int paceSecondsPer500m,
                int strokeRateRaw,
                int derivedDistanceMeters,
                String lastPacket,
                long lastPacketAgeMs,
                long packetsSeen,
                long pulsesSeen,
                double pulseHz,
                int lastPulseValue,
                double waterSpeedMps,
                int displayDistanceMeters,
                int strokeAvgTimeRaw,
                double instantSpeedMps,
                long speedUnchangedMs,
                boolean stillRowing,
                boolean flywheelMoving,
                int strokeRateAverage,
                double pulseEffort,
                int pulseStrokes,
                double strokeRatePrecise,
                PulseMeter.Reading meter) {
            this.monitorConnected = monitorConnected;
            this.rowing = rowing;
            this.elapsedSeconds = elapsedSeconds;
            this.distanceMeters = distanceMeters;
            this.strokes = strokes;
            this.strokeRate = strokeRate;
            this.watts = watts;
            this.heartRate = heartRate;
            this.paceSecondsPer500m = paceSecondsPer500m;
            this.strokeRateRaw = strokeRateRaw;
            this.derivedDistanceMeters = derivedDistanceMeters;
            this.lastPacket = lastPacket;
            this.lastPacketAgeMs = lastPacketAgeMs;
            this.packetsSeen = packetsSeen;
            this.pulsesSeen = pulsesSeen;
            this.pulseHz = pulseHz;
            this.lastPulseValue = lastPulseValue;
            this.waterSpeedMps = waterSpeedMps;
            this.displayDistanceMeters = displayDistanceMeters;
            this.strokeAvgTimeRaw = strokeAvgTimeRaw;
            this.instantSpeedMps = instantSpeedMps;
            this.speedUnchangedMs = speedUnchangedMs;
            this.stillRowing = stillRowing;
            this.flywheelMoving = flywheelMoving;
            this.strokeRateAverage = strokeRateAverage;
            this.pulseEffort = pulseEffort;
            this.pulseStrokes = pulseStrokes;
            this.strokeRatePrecise = strokeRatePrecise;
            this.meter = meter != null ? meter : PulseMeter.EMPTY;
        }
    }

    /**
     * One address we poll.
     *
     * <p>The width the monitor accepts for a given address varies between firmware revisions, so
     * each field carries the sizes worth attempting. A size that draws {@code ERROR} is dropped and
     * the next one is tried; the field is only retired once every size has been refused.
     */
    private static final class PollField {
        final String address;
        final String label;
        final char[] sizes;
        final int radix;
        /** Drives the live gauges, so it is read on every cycle rather than round-robin. */
        boolean hot;
        int sizeIndex;
        boolean retired;
        int consecutiveTimeouts;
        int consecutiveRejects;
        long lastValue = -1;
        /** When this address last answered at all. */
        long lastValueAtMs;
        /**
         * When the value last actually CHANGED.
         *
         * <p>The S4 holds a stale figure rather than decaying it, so "answered recently" does not
         * mean "still true". Age since the last change is what distinguishes a live reading from
         * one the monitor simply has not revised yet.
         */
        long lastChangeAtMs;

        PollField(String address, String label, int radix, char... sizes) {
            this.address = address;
            this.label = label;
            this.radix = radix;
            this.sizes = sizes;
        }

        PollField hot() {
            this.hot = true;
            return this;
        }

        char size() {
            return sizes[Math.min(sizeIndex, sizes.length - 1)];
        }

        String request() {
            return "IR" + size() + address + "\r\n";
        }

        /** @return true when no size is left to try. */
        boolean demoteSize() {
            sizeIndex++;
            if (sizeIndex >= sizes.length) {
                retired = true;
                return true;
            }
            return false;
        }
    }

    /**
     * Only addresses this Ergatta unit actually answers.
     *
     * <p>{@code 055} (distance), {@code 1A6} (pace), {@code 148} (instantaneous speed),
     * {@code 142} (stroke time), {@code 1E2} and {@code 1E3} are deliberately absent: the monitor answers {@code ERROR} to each of them at every width, and sending one is
     * what wedges the OUT endpoint - the stall always begins within ~2s of an ERROR reply, so
     * retiring after the first refusal is already too late. Do not add an address back without
     * evidence that this firmware answers it - {@code 148} and {@code 142} were added in 1.2.0 as
     * probes, were refused, and then sat in the rotation until 3.5.1 wasting a slot each cycle.
     * {@code instantSpeedMps} and {@code strokeAvgTimeRaw} therefore always read zero.
     *
     * <p>Widths are still corrected at runtime by {@link PollField#demoteSize()}, and the raw value
     * of every field is reported so the decode can be checked against the monitor's own display.
     */
    /**
     * One refusal is enough. Re-sending an address the monitor has already rejected is what wedges
     * the OUT endpoint on this unit: every observed write stall began within ~2s of an ERROR reply.
     */
    private static final int REJECTS_BEFORE_DEMOTING = 1;
    /** Below this the rower is essentially stopped and pace is meaningless. */
    private static final int MIN_PACE_SPEED_CM_S = 50;
    /** 10 minutes per 500m: anything slower is noise, not a pace. */
    private static final int MAX_PACE_SECONDS = 600;

    private final PollField[] pollFields = {
            new PollField("088", "watts", 16, 'D', 'S').hot(),
            new PollField("14A", "avgSpeedCmS", 16, 'D', 'S').hot(),
            new PollField("1A9", "strokeRateRaw", 16, 'S', 'D').hot(),
            // 057/058 are the DISPLAYED distance low/high bytes, read together as a double.
            // 055 is only the low byte of a different (metres) counter, which is why asking for
            // it never worked. Official map: tbressler/waterrower-core MemoryLocation.java
            new PollField("057", "displayDistance", 16, 'D', 'S'),
            // Hot: the stroke counter drives the coast trigger, so its change-age must reflect
            // real stroke timing rather than poll latency. Polled cold it lagged ~2.1s, which at
            // 48spm pushed the gap past the allowance and made the needle dip mid-stroke.
            new PollField("140", "strokes", 16, 'D', 'S').hot(),
            new PollField("1A0", "heartRate", 16, 'D', 'S'),
            new PollField("1E1", "clockSec", 10, 'S')
    };

    private final Map<String, PollField> fieldsByAddress = new LinkedHashMap<>();
    private final Listener listener;
    private final StringBuilder lineBuffer = new StringBuilder();
    private final Object pending = new Object();

    private PollField pendingField;
    private PollResult pendingResult;
    private int cycleIndex;
    private int coldIndex;

    private boolean monitorConnected;
    private long lastStrokeAtMs;
    private int elapsedMinutesFromWraps;
    private int elapsedSecondsPart;
    private int previousClockSec = -1;
    private int distanceMeters;
    private int strokes;
    /**
     * Recent stroke-counter tick times, for the displayed rate. Only usable since the link was
     * fixed: while strokes arrived in lumps there was no interval to measure.
     */
    private static final int STROKE_TICKS = 8;
    private final long[] strokeTickAtMs = new long[STROKE_TICKS];
    private int strokeTickCount;
    /** Strokes averaged for the displayed rate - about 15 s at a typical rating. */
    private static final int RATE_INTERVALS = 6;
    /** Plausible stroke gaps: 50 spm to 10 spm. Outside that it is noise, not rowing. */
    private static final long MIN_STROKE_MS = 1200;
    private static final long MAX_STROKE_MS = 6000;
    /** A stroke this much later than expected starts pulling the displayed rate down. */
    private static final double LATE_FACTOR = 1.35;
    private static final long STOPPED_MS = 12000;
    /** 15s of 1A9 samples at ~1Hz, for the displayed average. */
    private static final int RATE_WINDOW = 20;
    private static final long RATE_WINDOW_MS = 15000;
    private final long[] rateAtMs = new long[RATE_WINDOW];
    private final int[] rateValue = new int[RATE_WINDOW];
    private int rateWrite;
    private int strokeRate;
    private int watts;
    private int heartRate;
    private int paceSecondsPer500m;
    private int averageSpeedCmPerSecond;
    private int strokeRateRaw;
    private int displayDistanceMeters;
    private int instantSpeedCmPerSecond;
    private int strokeAvgTimeRaw;
    private double derivedDistanceMeters;
    private long lastSpeedSampleAtMs;
    private String lastPacket = "";
    private long lastPacketAtMs;
    private long packetsSeen;
    private long pulsesSeen;
    private long lastPulseAtMs;
    private double pulseIntervalMs;
    private int lastPulseValue;
    /** Smoothed Pxx payload: effort at 40Hz, independent of the poll. See pulseEffort(). */
    private double pulseSmoothed;
    private double pulseBaseline;
    private boolean pulseDriving;
    private long pulseDriveAtMs;
    private int pulseStrokes;
    /**
     * The pulse stream as measurements. Deliberately survives reset(), like pulseStrokes: the port
     * reopens on faults, and a session's work must not vanish with it.
     */
    private final PulseMeter meter = new PulseMeter();

    S4Protocol(Listener listener) {
        this.listener = listener;
        for (PollField field : pollFields) {
            fieldsByAddress.put(field.address, field);
        }
    }

    synchronized void reset() {
        lineBuffer.setLength(0);
        cycleIndex = 0;
        coldIndex = 0;
        monitorConnected = false;
        lastStrokeAtMs = 0;
        elapsedMinutesFromWraps = 0;
        elapsedSecondsPart = 0;
        previousClockSec = -1;
        distanceMeters = 0;
        strokes = 0;
        strokeTickCount = 0;
        strokeRate = 0;
        java.util.Arrays.fill(rateAtMs, 0L);
        rateWrite = 0;
        watts = 0;
        heartRate = 0;
        paceSecondsPer500m = 0;
        averageSpeedCmPerSecond = 0;
        strokeRateRaw = 0;
        displayDistanceMeters = 0;
        instantSpeedCmPerSecond = 0;
        strokeAvgTimeRaw = 0;
        derivedDistanceMeters = 0;
        lastSpeedSampleAtMs = 0;
        lastPacket = "";
        lastPacketAtMs = 0;
        packetsSeen = 0;
        pulsesSeen = 0;
        lastPulseAtMs = 0;
        pulseIntervalMs = 0;
        lastPulseValue = 0;
        pulseSmoothed = 0;
        pulseBaseline = 0;
        pulseDriving = false;
        pulseDriveAtMs = 0;
        // pulseStrokes deliberately survives: reset() runs on every port reopen, and on a link
        // that reopens every few seconds that wiped the one counter meant to outlast the fault.
        // Measured going 1 -> 0 while the monitor counted 40 real strokes.
        for (PollField field : pollFields) {
            field.sizeIndex = 0;
            field.retired = false;
            field.consecutiveTimeouts = 0;
            field.consecutiveRejects = 0;
            field.lastValue = -1;
            field.lastValueAtMs = 0;
            field.lastChangeAtMs = 0;
        }
        synchronized (pending) {
            pendingField = null;
            pendingResult = null;
            pending.notifyAll();
        }
    }

    byte[] startCommand() {
        return "USB\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    byte[] exitCommand() {
        return "EXIT\r\n".getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Claims the next live field and marks its read as outstanding.
     *
     * @return the request text, or empty when every field has been retired.
     */
    String beginNextPoll() {
        synchronized (this) {
            PollField field = chooseField();
            if (field == null) {
                return "";
            }
            synchronized (pending) {
                pendingField = field;
                pendingResult = null;
            }
            return field.request();
        }
    }

    /**
     * Hot fields on every cycle, one cold field per cycle.
     *
     * <p>A flat rotation over six addresses refreshed each one only every ~1.8s, which is far too
     * slow for a live speed or power needle. Weighting the three that drive the gauges cuts their
     * refresh without adding any extra writes.
     */
    private PollField chooseField() {
        int hotCount = 0;
        for (PollField f : pollFields) {
            if (f.hot && !f.retired) {
                hotCount++;
            }
        }
        int slot = cycleIndex % (hotCount + 1);
        cycleIndex++;

        if (slot < hotCount) {
            int seen = 0;
            for (PollField f : pollFields) {
                if (f.hot && !f.retired) {
                    if (seen == slot) {
                        return f;
                    }
                    seen++;
                }
            }
        }
        // Cold slot: advance through the non-hot fields one per cycle.
        for (int i = 0; i < pollFields.length; i++) {
            PollField f = pollFields[coldIndex % pollFields.length];
            coldIndex++;
            if (!f.hot && !f.retired) {
                return f;
            }
        }
        // Nothing cold left; fall back to any live field.
        for (PollField f : pollFields) {
            if (!f.retired) {
                return f;
            }
        }
        return null;
    }

    /** Blocks until the outstanding read is answered, refused or times out. */
    PollResult awaitPollResult(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        PollResult result;
        PollField field;
        synchronized (pending) {
            while (pendingResult == null) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                pending.wait(remaining);
            }
            result = pendingResult == null ? PollResult.TIMED_OUT : pendingResult;
            field = pendingField;
            pendingField = null;
            pendingResult = null;
        }
        // Outside the `pending` monitor: applyPollResult locks `this`, and the reader thread
        // acquires those two in the opposite order.
        if (field != null) {
            applyPollResult(field, result);
        }
        return result;
    }

    /**
     * Abandons the outstanding read after a USB-level write failure.
     *
     * <p>A failed bulk transfer says nothing about whether the monitor supports the command, so the
     * field keeps its place in the rotation and is retried.
     */
    void abandonPoll() {
        synchronized (pending) {
            pendingField = null;
            pendingResult = null;
            pending.notifyAll();
        }
    }

    private void applyPollResult(PollField field, PollResult result) {
        synchronized (this) {
            if (result == PollResult.REJECTED) {
                field.consecutiveTimeouts = 0;
                if (++field.consecutiveRejects >= REJECTS_BEFORE_DEMOTING) {
                    field.consecutiveRejects = 0;
                    field.demoteSize();
                }
            } else if (result == PollResult.TIMED_OUT) {
                // Retire only after the monitor has ignored this address repeatedly; a single miss
                // is usually a pulse-storm crowding out the reply.
                if (++field.consecutiveTimeouts >= 6) {
                    field.retired = true;
                }
            } else {
                field.consecutiveTimeouts = 0;
                field.consecutiveRejects = 0;
            }
        }
    }

    synchronized int retiredFieldCount() {
        int count = 0;
        for (PollField field : pollFields) {
            if (field.retired) {
                count++;
            }
        }
        return count;
    }

    synchronized boolean allFieldsRetired() {
        return retiredFieldCount() == pollFields.length;
    }

    /** Per-field decode state, so a wrong address or radix is visible instead of silent. */
    synchronized List<String> fieldReport() {
        List<String> report = new ArrayList<>(pollFields.length);
        long now = System.currentTimeMillis();
        for (PollField field : pollFields) {
            String value;
            if (field.retired) {
                value = "retired";
            } else if (field.lastValue < 0) {
                value = "no reply yet";
            } else {
                value = field.lastValue + " (0x" + Long.toHexString(field.lastValue).toUpperCase(Locale.US)
                        + ", " + ((now - field.lastValueAtMs) / 1000) + "s ago)";
            }
            report.add("IR" + field.size() + field.address + " " + field.label + " = " + value);
        }
        return report;
    }

    synchronized void accept(byte[] bytes, int count) {
        for (int i = 0; i < count; i++) {
            int value = bytes[i] & 0xFF;
            if (value == '\r' || value == '\n') {
                if (lineBuffer.length() > 0) {
                    handlePacket(lineBuffer.toString().trim());
                    lineBuffer.setLength(0);
                }
            } else if (value >= 32 && value <= 126) {
                if (lineBuffer.length() >= 512) {
                    lineBuffer.setLength(0);
                }
                lineBuffer.append((char) value);
            }
        }
    }

    synchronized Status snapshot() {
        long now = System.currentTimeMillis();
        boolean active = watts > 0 || strokeRate > 0
                || (lastStrokeAtMs > 0 && now - lastStrokeAtMs < 3500);
        int elapsed = elapsedMinutesFromWraps * 60 + elapsedSecondsPart;
        int pace = paceSecondsPer500m;
        if (pace <= 0 && averageSpeedCmPerSecond > 0) {
            // Dividing by a near-stopped speed produced absurd figures (16666s = 4.6 hours per
            // 500m) that polluted the capture. Below a slow walk there is no meaningful pace.
            pace = averageSpeedCmPerSecond >= MIN_PACE_SPEED_CM_S
                    ? 50000 / averageSpeedCmPerSecond
                    : 0;
        }
        if (pace > MAX_PACE_SECONDS) {
            pace = 0;
        }
        return new Status(
                monitorConnected,
                active,
                elapsed,
                displayDistanceMeters > 0 ? displayDistanceMeters : distanceMeters,
                strokes,
                strokeRate,
                watts,
                heartRate,
                pace,
                strokeRateRaw,
                (int) Math.round(derivedDistanceMeters),
                lastPacket,
                lastPacketAtMs == 0 ? -1 : now - lastPacketAtMs,
                packetsSeen,
                pulsesSeen,
                currentPulseHz(now),
                lastPulseValue,
                averageSpeedCmPerSecond / 100.0,
                displayDistanceMeters,
                strokeAvgTimeRaw,
                instantSpeedCmPerSecond / 100.0,
                unchangedMs("14A", now),
                stillRowing(now),
                lastPulseAtMs > 0 && now - lastPulseAtMs < 700,
                averagedStrokeRate(now),
                pulseEffort(),
                pulseStrokes(),
                preciseStrokeRate(now),
                meter.reading(now));
    }

    private void recordStrokeTick(long atMs) {
        // A gap longer than any real stroke means the rower stopped; start the history over so
        // the first strokes back are not averaged against the rest.
        if (strokeTickCount > 0 && atMs - strokeTickAtMs[strokeTickCount - 1] > MAX_STROKE_MS) {
            strokeTickCount = 0;
        }
        if (strokeTickCount < STROKE_TICKS) {
            strokeTickAtMs[strokeTickCount++] = atMs;
        } else {
            System.arraycopy(strokeTickAtMs, 1, strokeTickAtMs, 0, STROKE_TICKS - 1);
            strokeTickAtMs[STROKE_TICKS - 1] = atMs;
        }
    }

    /**
     * Stroke rate as displayed: timed from the strokes themselves, held until the next is due.
     *
     * <p>The rower's own description of what it should do, and the right model: once the rate is
     * known, the next stroke is expected a known interval after the last. So the figure is the
     * average of the last six stroke intervals - it only moves when a stroke lands, by a sixth of
     * the change - and between strokes it holds. Only when a stroke is clearly late does it start
     * to fall, as 60 / time-since-the-last-stroke, which is exactly what the rate has become.
     *
     * <p>This was not possible before the interface-ownership fix. While strokes arrived in lumps
     * - eleven in one update after 25 s of silence - there was no interval to time. Measured on
     * 3.10.0 every stroke now lands on its own, a median 1.8 s apart at 29-30 spm.
     *
     * <p>Until three intervals exist it falls back to the 1A9 reading. Display only: the coast
     * trigger and the games keep the raw value.
     */
    private int averagedStrokeRate(long now) {
        return (int) Math.round(preciseStrokeRate(now));
    }

    /** {@link #averagedStrokeRate} before rounding. */
    private double preciseStrokeRate(long now) {
        int intervals = strokeTickCount - 1;
        if (intervals < 3) {
            return trimmedRate(now);
        }
        int use = Math.min(RATE_INTERVALS, intervals);
        long last = strokeTickAtMs[strokeTickCount - 1];
        long first = strokeTickAtMs[strokeTickCount - 1 - use];
        double expected = (last - first) / (double) use;
        expected = Math.max(MIN_STROKE_MS, Math.min(MAX_STROKE_MS, expected));
        long elapsed = now - last;
        if (elapsed > STOPPED_MS) {
            return 0;
        }
        double rate = 60000.0 / expected;
        if (elapsed > expected * LATE_FACTOR) {
            rate = Math.min(rate, 60000.0 / elapsed);
        }
        return rate;
    }

    /**
     * Fallback displayed rate from 1A9 alone, used until enough strokes have been timed.
     *
     * <p>1A9 is steadier than it looks - measured over 2700 samples of a held 25spm it has a
     * standard deviation of 1.6spm - but it dips to 19-21 in about 9% of them, and a needle
     * falling six points reads as a stroke that did not count. The stroke counter proves nothing
     * was actually missed: 106 consecutive ticks, every delta exactly 1.
     *
     * <p>So the displayed figure is a trimmed mean over the last 15 seconds, dropping the single
     * highest and lowest samples. That removes an isolated dip outright while still following a
     * real change of cadence within a few seconds. An untrimmed mean would let one 19 pull the
     * whole figure down, which is the artefact being removed.
     *
     * <p>Display only. The coast trigger below and the games keep the raw value.
     */
    private int trimmedRate(long now) {
        int count = 0;
        int sum = 0;
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (int i = 0; i < RATE_WINDOW; i++) {
            if (rateAtMs[i] == 0 || now - rateAtMs[i] > RATE_WINDOW_MS) {
                continue;
            }
            int v = rateValue[i];
            count++;
            sum += v;
            lowest = Math.min(lowest, v);
            highest = Math.max(highest, v);
        }
        if (count == 0) {
            return strokeRate;
        }
        if (count >= 5) {
            sum -= lowest + highest;
            count -= 2;
        }
        return Math.round(sum / (float) count);
    }

    /**
     * Whether strokes are still being taken.
     *
     * <p>The allowance scales with the current rate so it cannot misfire at low ratings: at 40spm
     * strokes are 1.5s apart, at 18spm they are 3.3s apart. Measured gaps between counter ticks
     * stay under ~2s while rowing, so 2.5 stroke periods with a 4s floor leaves clear headroom.
     */
    private boolean stillRowing(long now) {
        PollField strokes = fieldsByAddress.get("140");
        if (strokes == null || strokes.lastChangeAtMs == 0) {
            return false;
        }
        // Floor covers the poll interval plus one missed stroke; the rate-scaled term covers
        // slow ratings, where strokes are legitimately far apart.
        long allowance = 5000;
        if (strokeRate > 0) {
            allowance = Math.max(allowance, (long) (3.0 * 60000 / strokeRate));
        }
        return now - strokes.lastChangeAtMs < allowance;
    }

    /** Milliseconds since this address last reported a different value. */
    private long unchangedMs(String address, long now) {
        PollField field = fieldsByAddress.get(address);
        if (field == null || field.lastChangeAtMs == 0) {
            return Long.MAX_VALUE;
        }
        return now - field.lastChangeAtMs;
    }

    private void handlePacket(String packet) {
        if (packet.isEmpty()) {
            return;
        }
        lastPacket = packet;
        lastPacketAtMs = System.currentTimeMillis();
        packetsSeen++;

        if ("_WR_".equals(packet)) {
            monitorConnected = true;
        } else if ("PING".equals(packet)) {
            monitorConnected = true;
        } else if ("SS".equals(packet) || "SE".equals(packet)) {
            monitorConnected = true;
            lastStrokeAtMs = lastPacketAtMs;
        } else if (isPulsePacket(packet)) {
            // Pxx carries flywheel pulse timing: proof of motion even when memory reads are refused,
            // and the only signal that survives a wedged write path.
            monitorConnected = true;
            lastStrokeAtMs = lastPacketAtMs;
            pulsesSeen++;
            recordPulse(packet);
        } else if ("ERROR".equals(packet)) {
            monitorConnected = true;
            settlePending(null, PollResult.REJECTED);
        } else if (packet.startsWith("ID") && packet.length() >= 8) {
            parseMemoryPacket(packet);
        }

        if (listener != null) {
            listener.onPacket(packet, snapshot());
        }
    }

    /**
     * Tracks flywheel pulse cadence.
     *
     * <p>The {@code Pxx} payload's units are not yet established, so it is recorded verbatim
     * alongside the measured arrival rate; the two can be correlated against {@code 14A} average
     * speed during windows where memory reads are answering, which fixes the scale factor.
     */
    private void recordPulse(String packet) {
        try {
            lastPulseValue = Integer.parseInt(packet.substring(1), 16);
        } catch (NumberFormatException ignored) {
            lastPulseValue = 0;
        }
        meter.onPulse(lastPulseValue, lastPacketAtMs, watts);
        // Pxx is a count per fixed 25ms interval, not a period: packets arrive at 40/s whenever
        // the flywheel turns, and the payload rises monotonically with speed and power (3.75 m/s
        // at 7, 4.53 m/s at 13, over 7330 samples). Noisy per packet, so it is smoothed - but it
        // is forty times finer than the poll and keeps arriving when writes are refused, which
        // makes it the only signal that can keep the instruments alive on a broken link.
        pulseSmoothed = pulseSmoothed == 0
                ? lastPulseValue
                : pulseSmoothed * 0.75 + lastPulseValue * 0.25;
        // A slow baseline the drive has to rise above; it recovers between strokes.
        pulseBaseline = pulseBaseline == 0
                ? pulseSmoothed
                : pulseBaseline * 0.985 + pulseSmoothed * 0.015;
        boolean above = pulseSmoothed > pulseBaseline + PULSE_DRIVE_MARGIN;
        if (above && !pulseDriving && lastPacketAtMs - pulseDriveAtMs > PULSE_MIN_STROKE_MS) {
            pulseDriving = true;
            pulseDriveAtMs = lastPacketAtMs;
            pulseStrokes++;
        } else if (!above && pulseSmoothed < pulseBaseline + PULSE_DRIVE_MARGIN * 0.4) {
            pulseDriving = false;
        }

        if (lastPulseAtMs > 0) {
            long gap = lastPacketAtMs - lastPulseAtMs;
            // Ignore absurd gaps so a stall or a reconnect does not poison the average.
            if (gap > 0 && gap < 2000) {
                pulseIntervalMs = pulseIntervalMs == 0
                        ? gap
                        : pulseIntervalMs * 0.8 + gap * 0.2;
            }
        }
        lastPulseAtMs = lastPacketAtMs;
    }

    /** Rise above the rolling baseline that counts as a drive. Needs tuning on hardware. */
    private static final double PULSE_DRIVE_MARGIN = 0.8;
    /** No two drives closer than this; even a 40spm sprint leaves 1.5s between strokes. */
    private static final long PULSE_MIN_STROKE_MS = 700;

    /**
     * Effort from the pulse stream, roughly 0 to 1, updated 40 times a second.
     *
     * <p>Not a calibrated speed: per-sample correlation with `14A` is only r = +0.24, so this is a
     * liveness and shape signal rather than a measurement. Use it to move the instruments between
     * memory reads and to catch the drive as it happens; `14A` and `088` stay authoritative when
     * they arrive.
     */
    double pulseEffort() {
        if (pulseSmoothed <= 0 || lastPulseAtMs == 0) {
            return 0;
        }
        // Decay when the pulses stop rather than holding the last figure. Measured at 0.37 while
        // flywheelMoving was already false - the exact "held value" mistake this project keeps
        // making, committed again in new code. Gone in about a second of silence.
        long quiet = System.currentTimeMillis() - lastPulseAtMs;
        if (quiet > 1200) {
            return 0;
        }
        double faded = pulseSmoothed * (1.0 - quiet / 1200.0);
        return Math.max(0, Math.min(1, (faded - 1) / 12.0));
    }

    /** For calibration: seeds and load-scale results are set on the meter from the UI thread. */
    PulseMeter meter() {
        return meter;
    }

    /** Strokes counted from the pulse stream alone, for when address 140 cannot be read. */
    int pulseStrokes() {
        return pulseStrokes;
    }

    /** Zero once the flywheel has clearly stopped, rather than holding the last rate forever. */
    private double currentPulseHz(long now) {
        if (pulseIntervalMs <= 0 || lastPulseAtMs == 0 || now - lastPulseAtMs > 1500) {
            return 0;
        }
        return 1000.0 / pulseIntervalMs;
    }

    /** {@code P01}..{@code Pff}: a flywheel pulse, not a reply to anything we asked for. */
    private static boolean isPulsePacket(String packet) {
        if (packet.length() < 2 || packet.charAt(0) != 'P') {
            return false;
        }
        for (int i = 1; i < packet.length(); i++) {
            if (Character.digit(packet.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private void parseMemoryPacket(String packet) {
        String address = packet.substring(3, 6).toUpperCase(Locale.US);
        PollField field = fieldsByAddress.get(address);
        if (field == null) {
            return;
        }

        // Trust the width the monitor actually replied with, not the width we asked for.
        int valueChars = packet.length() - 6;
        if (valueChars <= 0) {
            return;
        }

        int value;
        try {
            value = Integer.parseInt(packet.substring(6), field.radix);
        } catch (NumberFormatException ignored) {
            return;
        }

        monitorConnected = true;
        if (field.lastValue != value) {
            field.lastChangeAtMs = lastPacketAtMs;
        }
        field.lastValue = value;
        field.lastValueAtMs = lastPacketAtMs;
        settlePending(field, PollResult.ANSWERED);

        switch (address) {
            case "055":
                distanceMeters = value;
                break;
            case "140": {
                int delta = value - strokes;
                if (strokes > 0 && delta == 1) {
                    recordStrokeTick(System.currentTimeMillis());
                } else if (delta != 0) {
                    // A reset to zero on reopen, the re-read jump after it, a lumped read or a
                    // monitor restart: an interval measured across any of those is fiction.
                    strokeTickCount = 0;
                }
                strokes = value;
                break;
            }
            case "088":
                watts = value;
                break;
            case "057":
                displayDistanceMeters = value;
                meter.onDistance(value);
                break;
            case "142":
                strokeAvgTimeRaw = value;
                break;
            case "148":
                instantSpeedCmPerSecond = value;
                break;
            case "14A":
                averageSpeedCmPerSecond = value;
                // Integrate speed into distance, since 055 is refused on this unit. Gaps longer
                // than a few seconds are skipped rather than extrapolated across a stall.
                if (lastSpeedSampleAtMs > 0) {
                    long gapMs = lastPacketAtMs - lastSpeedSampleAtMs;
                    if (gapMs > 0 && gapMs < 3000) {
                        derivedDistanceMeters += value / 100.0 * (gapMs / 1000.0);
                    }
                }
                lastSpeedSampleAtMs = lastPacketAtMs;
                break;
            case "1A6":
                paceSecondsPer500m = value;
                break;
            case "1A9":
                // No multiplier. An inherited x2 produced 74 spm on this machine and 294 readings
                // above 55 spm, which is beyond human cadence (sprint records sit near 50-55).
                // The raw value peaked at 37, which is a hard but real sprint rating.
                strokeRateRaw = value;
                strokeRate = value;
                rateAtMs[rateWrite % RATE_WINDOW] = System.currentTimeMillis();
                rateValue[rateWrite % RATE_WINDOW] = value;
                rateWrite++;
                break;
            case "1A0":
                heartRate = value;
                break;
            case "1E1":
                if (value >= 0 && value < 60) {
                    if (previousClockSec >= 0 && value < previousClockSec
                            && previousClockSec - value > 30) {
                        // Seconds rolled 59 -> 0. The minute and hour registers are unreadable on
                        // this unit, so the minute count is kept here instead.
                        elapsedMinutesFromWraps++;
                    }
                    previousClockSec = value;
                    elapsedSecondsPart = value;
                }
                break;
            default:
                break;
        }
    }

    /**
     * Resolves the outstanding read.
     *
     * @param field the address that replied, or null for {@code ERROR}, which names no address and
     *              so can only be attributed to whatever is currently outstanding.
     */
    private void settlePending(PollField field, PollResult result) {
        synchronized (pending) {
            if (pendingField == null || pendingResult != null) {
                return;
            }
            if (field != null && field != pendingField) {
                // A late reply to an already-abandoned read. Its value is still recorded above.
                return;
            }
            pendingResult = result;
            pending.notifyAll();
        }
    }
}
