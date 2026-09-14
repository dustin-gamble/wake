package com.codex.waterrowerdiagnostic;

/**
 * Turns the monitor's 40-per-second pulse counts into real mechanical measurements: paddle rate,
 * the shape of every stroke, the water's drag, and the work the rower actually did.
 *
 * <p>Each {@code Pxx} packet is the number of paddle pulses in a fixed 25 ms window, so the count
 * is the paddle's angular speed at 25 ms resolution. Four packets make a 100 ms bin; single packets
 * (counts of 1-13) are too coarse to differentiate.
 *
 * <p><b>The physics.</b> The paddle is a flywheel in water. With {@code w} in pulses per second,
 * {@code I} its inertia and {@code c} the drag-to-inertia ratio, the power going in is
 *
 * <pre>  P = I * w * (c * w^2 + dw/dt)</pre>
 *
 * During a coast nobody is pulling, so {@code dw/dt = -c w^2}: {@code 1/w} rises in a straight
 * line with slope {@code c}. Every recovery is therefore a free measurement of {@code c}, and a
 * rower produces hundreds of them. Integrated over time the power is
 * {@code I * (c * integral(w^3 dt) + change in w^2 / 2)} - no need to find where the drive ends,
 * which makes the energy robust against the coarse counts.
 *
 * <p>That leaves one scale, {@code I}. Until the rower calibrates it with a load scale (see the
 * calibration screen), it is matched to the monitor's own power readings, and {@link Reading#source}
 * says which is in use.
 *
 * <p>Pure Java, no Android, tested by {@code PulseMeterTest} against a simulated paddle.
 * Every public method is synchronized: pulses arrive on the reader thread, calibration is set
 * from the UI.
 */
final class PulseMeter {

    static final double PACKET_S = 0.025;
    static final int PACKETS_PER_BIN = 4;
    static final double BIN_S = PACKET_S * PACKETS_PER_BIN;
    /** Pulses stop when the paddle stops. This much silence means it has. */
    static final long GAP_MS = 600;
    /** Pulses per second below which the paddle is creeping rather than being rowed. */
    static final double MIN_ROWING_RATE = 20;
    /** Human muscle turns about a quarter of food energy into mechanical work. */
    static final double HUMAN_EFFICIENCY = 0.25;
    static final double JOULES_PER_KCAL = 4184.0;
    /** Paddle turns per boat metre, counted by the rower on this machine (see PaddleView). */
    static final double PADDLE_TURNS_PER_METRE = 0.65;
    static final int TRACE_BINS = 60;

    private static final int MAX_STROKE_BINS = 80;
    private static final int FIT_RING = 31;
    /** Joules of monitor-reported work before its scale is trusted for the auto match. */
    private static final double AUTO_MATCH_MIN_J = 3000;

    enum EnergySource {
        /** No coast measured yet: integrating the monitor's own watts. */
        MONITOR_WATTS,
        /** Measured from pulses; overall scale matched to the monitor's watts. */
        PULSES_MONITOR_SCALED,
        /** Measured from pulses; scale set with a load scale. */
        PULSES_CALIBRATED
    }

    /** One complete stroke: drive then recovery. Immutable, safe to hand to the UI. */
    static final class Stroke {
        final int index;
        final double driveSeconds;
        final double recoverySeconds;
        final long drivePulses;
        final long pulses;
        final double peakRate;
        /** Median rate across the steady part of the drive - what a load-scale pull is read against. */
        final double plateauRate;
        /** NaN until energy is available. */
        final double workJoules;
        final double averagePowerW;
        final double peakPowerW;
        /** NaN until the handle has been calibrated. */
        final double driveLengthM;
        final double peakForceN;
        /** Paddle rate through the drive, one value per 100 ms. */
        final float[] driveRates;
        /** Power through the drive, one value per 100 ms; null until energy is available. */
        final float[] drivePower;

        Stroke(int index, double driveSeconds, double recoverySeconds, long drivePulses, long pulses,
               double peakRate, double plateauRate, double workJoules, double averagePowerW,
               double peakPowerW, double driveLengthM, double peakForceN, float[] driveRates,
               float[] drivePower) {
            this.index = index;
            this.driveSeconds = driveSeconds;
            this.recoverySeconds = recoverySeconds;
            this.drivePulses = drivePulses;
            this.pulses = pulses;
            this.peakRate = peakRate;
            this.plateauRate = plateauRate;
            this.workJoules = workJoules;
            this.averagePowerW = averagePowerW;
            this.peakPowerW = peakPowerW;
            this.driveLengthM = driveLengthM;
            this.peakForceN = peakForceN;
            this.driveRates = driveRates;
            this.drivePower = drivePower;
        }

        /** Drive time over recovery time. Rowing coaches aim for roughly 1:2. */
        double ratio() {
            return recoverySeconds > 0 ? driveSeconds / recoverySeconds : 0;
        }
    }

    /** Everything the UI needs, captured at one moment. Immutable. */
    static final class Reading {
        /** Paddle rate, pulses per second, smoothed over 300 ms. 0 once the paddle stops. */
        final double paddleRate;
        /** Boat speed from pulses; NaN until pulses-per-metre is known. */
        final double speedMps;
        final long totalPulses;
        final double workJoules;
        final EnergySource source;
        final int strokes;
        final Stroke lastStroke;
        final double dragPerInertia;
        final double inertia;
        final double pulsesPerMetre;
        final boolean pulsesPerMetreMeasured;
        final double handleMetresPerPulse;
        final int coastFits;
        /** The last {@link #TRACE_BINS} paddle rates, oldest first, one per 100 ms. */
        final float[] trace;

        Reading(double paddleRate, double speedMps, long totalPulses, double workJoules,
                EnergySource source, int strokes, Stroke lastStroke, double dragPerInertia,
                double inertia, double pulsesPerMetre, boolean pulsesPerMetreMeasured,
                double handleMetresPerPulse, int coastFits, float[] trace) {
            this.paddleRate = paddleRate;
            this.speedMps = speedMps;
            this.totalPulses = totalPulses;
            this.workJoules = workJoules;
            this.source = source;
            this.strokes = strokes;
            this.lastStroke = lastStroke;
            this.dragPerInertia = dragPerInertia;
            this.inertia = inertia;
            this.pulsesPerMetre = pulsesPerMetre;
            this.pulsesPerMetreMeasured = pulsesPerMetreMeasured;
            this.handleMetresPerPulse = handleMetresPerPulse;
            this.coastFits = coastFits;
            this.trace = trace;
        }

        /** Food calories burned for this much mechanical work, at {@link #HUMAN_EFFICIENCY}. */
        double kcal() {
            return kcalForWork(workJoules);
        }
    }

    /** Stored when nothing has been measured: an empty reading, so the UI never sees null. */
    static final Reading EMPTY = new Reading(0, Double.NaN, 0, 0, EnergySource.MONITOR_WATTS, 0,
            null, 0, 0, 0, false, 0, 0, new float[TRACE_BINS]);

    // ---- raw counting ----
    private long totalPulses;
    private int binPackets;
    private int binSum;
    private long lastPacketMs;
    private int watts;

    // ---- smoothing and trace ----
    private final double[] smoothRing = new double[3];
    private int smoothHead;
    private int smoothFilled;
    private double rate;
    private double smooth;
    private double prevSmooth;
    private final float[] trace = new float[TRACE_BINS];
    private int traceHead;
    private float[] traceCopy = new float[TRACE_BINS];
    private boolean traceDirty;

    // ---- calibration ----
    private final double[] fits = new double[FIT_RING];
    private int fitCount;
    private int fitHead;
    private double dragSeed;
    private double inertiaCalibrated;
    private double inertiaAuto;
    private double handleMetresPerPulse;
    private double pulsesPerMetre;
    private boolean pulsesPerMetreMeasured;

    // ---- energy ----
    private double omega3Integral;
    private double monitorJoules;
    private double matchOmega3;
    private double matchMonitorJ;

    // ---- distance ratio ----
    private long distBasePulses;
    private int distBaseMetres = -1;

    // ---- strokes ----
    private final double[] strokeRates = new double[MAX_STROKE_BINS];
    private final double[] strokeSmooth = new double[MAX_STROKE_BINS];
    private int strokeBins;
    private boolean inStroke;
    private boolean driving;
    private int driveEndBin = -1;
    /** First bin the coast fit may use: after the fall was confirmed, not merely after the peak. */
    private int coastFitStart;
    private int lastNearPeakBin;
    private double peak;
    /** Lowest smoothed rate since the last drive ended or the paddle stopped. */
    private double trough = Double.MAX_VALUE;
    private int risingBins;
    private int fallingBins;
    private double strokeStartRate;
    private int strokeCount;
    private Stroke lastStroke;

    /* ------------------------------------------------------------------ input */

    /**
     * One {@code Pxx} packet.
     *
     * @param count        pulses in the 25 ms window
     * @param atMs         when it arrived, wall clock
     * @param monitorWatts the monitor's latest power reading, for the auto scale
     */
    synchronized void onPulse(int count, long atMs, int monitorWatts) {
        if (lastPacketMs > 0 && atMs - lastPacketMs > GAP_MS) {
            stopped();
        }
        lastPacketMs = atMs;
        watts = Math.max(0, monitorWatts);
        int n = Math.max(0, count);
        totalPulses += n;
        binSum += n;
        if (++binPackets == PACKETS_PER_BIN) {
            closeBin();
        }
    }

    /** The monitor's distance register, in metres. Used only to learn pulses per metre. */
    synchronized void onDistance(int metres) {
        if (metres <= 0) {
            return;
        }
        if (distBaseMetres < 0 || metres < distBaseMetres) {
            distBaseMetres = metres;
            distBasePulses = totalPulses;
            return;
        }
        int dm = metres - distBaseMetres;
        // Long windows: the register is polled slowly and lags the paddle by seconds, and over
        // 100 m that lag is a few percent at most - less once windows are blended.
        if (dm >= 100) {
            double measured = (totalPulses - distBasePulses) / (double) dm;
            if (measured > 1) {
                pulsesPerMetre = pulsesPerMetreMeasured
                        ? pulsesPerMetre * 0.7 + measured * 0.3
                        : measured;
                pulsesPerMetreMeasured = true;
            }
            distBaseMetres = metres;
            distBasePulses = totalPulses;
        }
    }

    /* ------------------------------------------------------------ calibration */

    /** Drag-to-inertia ratio saved from an earlier session, used until live coasts replace it. */
    synchronized void setDragSeed(double c) {
        dragSeed = c > 0 ? c : 0;
    }

    /** Inertia from the load-scale test. 0 goes back to matching the monitor. */
    synchronized void setInertia(double inertia) {
        inertiaCalibrated = inertia > 0 ? inertia : 0;
    }

    /** The monitor-matched inertia saved from an earlier session. */
    synchronized void setInertiaAutoSeed(double inertia) {
        if (inertiaAuto <= 0 && inertia > 0) {
            inertiaAuto = inertia;
        }
    }

    /** Metres of handle travel per pulse, from the pull-distance test. */
    synchronized void setHandleMetresPerPulse(double metres) {
        handleMetresPerPulse = metres > 0 ? metres : 0;
    }

    /** Pulses per boat metre saved from an earlier session, until this session measures it. */
    synchronized void setPulsesPerMetreSeed(double ppm) {
        if (!pulsesPerMetreMeasured && ppm > 0) {
            pulsesPerMetre = ppm;
        }
    }

    synchronized double dragPerInertia() {
        if (fitCount >= 3) {
            return medianOf(fits, fitCount);
        }
        return dragSeed;
    }

    synchronized double inertiaAuto() {
        return inertiaAuto;
    }

    /* --------------------------------------------------------------- output */

    synchronized Reading reading(long nowMs) {
        if (lastPacketMs > 0 && nowMs - lastPacketMs > GAP_MS && (inStroke || smooth > 0)) {
            stopped();
        }
        if (traceDirty) {
            float[] copy = new float[TRACE_BINS];
            for (int i = 0; i < TRACE_BINS; i++) {
                copy[i] = trace[(traceHead + i) % TRACE_BINS];
            }
            traceCopy = copy;
            traceDirty = false;
        }
        double c = dragPerInertia();
        double inertia = inertiaCalibrated > 0 ? inertiaCalibrated : inertiaAuto;
        EnergySource source;
        double work;
        if (c > 0 && inertia > 0) {
            work = inertia * (c * omega3Integral + 0.5 * smooth * smooth);
            source = inertiaCalibrated > 0 ? EnergySource.PULSES_CALIBRATED
                    : EnergySource.PULSES_MONITOR_SCALED;
        } else {
            work = monitorJoules;
            source = EnergySource.MONITOR_WATTS;
        }
        return new Reading(smooth,
                pulsesPerMetre > 0 ? smooth / pulsesPerMetre : Double.NaN,
                totalPulses, work, source, strokeCount, lastStroke, c, inertia, pulsesPerMetre,
                pulsesPerMetreMeasured, handleMetresPerPulse, fitCount, traceCopy);
    }

    /* ---------------------------------------------------------------- helpers */

    static double kcalForWork(double joules) {
        return joules / JOULES_PER_KCAL / HUMAN_EFFICIENCY;
    }

    /**
     * Inertia from one steady load-scale pull. At a steady pull {@code dw/dt} is zero, so the
     * power the hand puts in, force times handle speed, equals the drag: {@code F * h * w = I c w^3}.
     */
    static double inertiaFromPull(double forceN, double plateauRate, double handleMetresPerPulse,
                                  double dragPerInertia) {
        if (forceN <= 0 || plateauRate <= 0 || handleMetresPerPulse <= 0 || dragPerInertia <= 0) {
            return 0;
        }
        return forceN * handleMetresPerPulse / (dragPerInertia * plateauRate * plateauRate);
    }

    /** How well force follows the square of paddle rate across pulls: 1 is a perfect water law. */
    static double squareLawFit(double[] forces, double[] rates, int n) {
        if (n < 2) {
            return 0;
        }
        // Least squares through the origin, F = a * w^2, and the share of variance it explains.
        double sxy = 0;
        double sxx = 0;
        double mean = 0;
        for (int i = 0; i < n; i++) {
            double x = rates[i] * rates[i];
            sxy += x * forces[i];
            sxx += x * x;
            mean += forces[i];
        }
        mean /= n;
        double a = sxy / sxx;
        double ssRes = 0;
        double ssTot = 0;
        for (int i = 0; i < n; i++) {
            double e = forces[i] - a * rates[i] * rates[i];
            ssRes += e * e;
            ssTot += (forces[i] - mean) * (forces[i] - mean);
        }
        return ssTot > 0 ? Math.max(0, 1 - ssRes / ssTot) : 1;
    }

    static double medianOf(double[] values, int n) {
        if (n <= 0) {
            return 0;
        }
        double[] copy = java.util.Arrays.copyOf(values, n);
        java.util.Arrays.sort(copy);
        return n % 2 == 1 ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2;
    }

    /* --------------------------------------------------------------- internals */

    private void closeBin() {
        double r = binSum / BIN_S;
        binSum = 0;
        binPackets = 0;
        rate = r;

        smoothRing[smoothHead] = r;
        smoothHead = (smoothHead + 1) % smoothRing.length;
        if (smoothFilled < smoothRing.length) {
            smoothFilled++;
        }
        prevSmooth = smooth;
        smooth = (smoothRing[0] + smoothRing[1] + smoothRing[2]) / smoothFilled;

        trace[traceHead] = (float) smooth;
        traceHead = (traceHead + 1) % TRACE_BINS;
        traceDirty = true;

        omega3Integral += r * r * r * BIN_S;
        if (r >= MIN_ROWING_RATE) {
            monitorJoules += watts * BIN_S;
            double c = dragPerInertia();
            if (c > 0) {
                matchOmega3 += r * r * r * BIN_S;
                matchMonitorJ += watts * BIN_S;
                if (matchMonitorJ >= AUTO_MATCH_MIN_J) {
                    inertiaAuto = matchMonitorJ / (c * matchOmega3);
                }
            }
        }
        segment();
    }

    private void segment() {
        double s = smooth;
        if (driving) {
            addStrokeBin();
            if (s > peak) {
                peak = s;
            }
            if (s >= 0.97 * peak) {
                lastNearPeakBin = strokeBins - 1;
            }
            fallingBins = s < prevSmooth ? fallingBins + 1 : 0;
            if (s < 0.92 * peak && fallingBins >= 2) {
                driving = false;
                driveEndBin = lastNearPeakBin;
                // The drive's tapering tail still pushes a little between the peak and here, which
                // made the coast look slower than drag: c came out 10% low on the simulated paddle.
                coastFitStart = strokeBins + 1;
                trough = s;
                risingBins = 0;
            }
            return;
        }

        if (inStroke) {
            addStrokeBin();
        }
        trough = Math.min(trough, s);
        risingBins = s > prevSmooth ? risingBins + 1 : 0;
        boolean driveStarts = risingBins >= 2 && s >= MIN_ROWING_RATE && s > trough * 1.08 + 6;
        if (driveStarts) {
            if (inStroke) {
                // The smoothed rise is only confirmed a few bins into the new drive. Everything from
                // the raw low point on belongs to the new stroke: left in the old recovery, those
                // accelerating bins made the coast look slower than drag (c came out 10-15% low).
                int earliest = Math.max(driveEndBin + 1, strokeBins - 8);
                int low = strokeBins - 1;
                for (int i = strokeBins - 1; i >= earliest; i--) {
                    if (strokeRates[i] <= strokeRates[low]) {
                        low = i;
                    }
                }
                double[] carried = java.util.Arrays.copyOfRange(strokeRates, low, strokeBins);
                double[] carriedSmooth = java.util.Arrays.copyOfRange(strokeSmooth, low, strokeBins);
                finishStroke(low);
                beginStroke(carried, carriedSmooth);
            } else {
                beginStroke(new double[]{rate}, new double[]{s});
            }
            return;
        }
        if (inStroke && strokeBins >= MAX_STROKE_BINS) {
            // A long coast after the last stroke: close it and wait for the next drive.
            finishStroke(strokeBins);
        }
    }

    private void beginStroke(double[] rates, double[] smooths) {
        inStroke = true;
        driving = true;
        strokeBins = 0;
        driveEndBin = -1;
        coastFitStart = 0;
        peak = 0;
        lastNearPeakBin = 0;
        fallingBins = 0;
        risingBins = 0;
        strokeStartRate = strokeRates.length > 0 && rates.length > 0 ? rates[0] : 0;
        for (int i = 0; i < rates.length; i++) {
            strokeRates[strokeBins] = rates[i];
            strokeSmooth[strokeBins] = smooths[i];
            peak = Math.max(peak, smooths[i]);
            strokeBins++;
        }
        lastNearPeakBin = strokeBins - 1;
    }

    private void addStrokeBin() {
        if (strokeBins < MAX_STROKE_BINS) {
            strokeRates[strokeBins] = rate;
            strokeSmooth[strokeBins] = smooth;
            strokeBins++;
        }
    }

    private void stopped() {
        if (inStroke) {
            finishStroke(strokeBins);
        }
        binSum = 0;
        binPackets = 0;
        rate = 0;
        smooth = 0;
        prevSmooth = 0;
        smoothFilled = 0;
        java.util.Arrays.fill(smoothRing, 0);
        trace[traceHead] = 0f;
        traceHead = (traceHead + 1) % TRACE_BINS;
        traceDirty = true;
        driving = false;
        risingBins = 0;
        trough = Double.MAX_VALUE;
    }

    private void finishStroke(int bins) {
        inStroke = false;
        boolean wasDriving = driving;
        driving = false;
        int driveBins = wasDriving || driveEndBin < 0 ? bins : Math.min(bins, driveEndBin + 1);
        int recoveryBins = bins - driveBins;

        double peakRate = 0;
        long pulses = 0;
        for (int i = 0; i < bins; i++) {
            pulses += Math.round(strokeRates[i] * BIN_S);
            peakRate = Math.max(peakRate, strokeSmooth[i]);
        }
        if (driveBins < 2) {
            strokeBins = 0;
            return;
        }

        int fitFrom = Math.max(driveBins + 1, coastFitStart);
        fitCoast(fitFrom, bins - 1, peakRate);

        // Where the drive really ended. The 97%-of-peak rule runs long - the hand is still pushing
        // after the peak, and the smoothing lags a bin - so it read 28% too many drive pulses on
        // the simulated paddle. With drag known there is a physical answer: once nobody pushes,
        // the paddle follows its coast curve exactly. Fit that curve to this recovery, trace it
        // backwards, and the drive ended at the last moment the paddle was clearly below it.
        double drivePulsesExact = -1;
        double c0 = dragPerInertia();
        if (c0 > 0 && fitFrom < bins - 1) {
            double floor = Math.max(MIN_ROWING_RATE, 0.3 * peakRate);
            double sum = 0;
            int n = 0;
            for (int i = fitFrom; i < bins - 1 && strokeRates[i] >= floor; i++) {
                sum += 1.0 / strokeRates[i] - c0 * (i + 0.5) * BIN_S;
                n++;
            }
            if (n >= 3) {
                double intercept = sum / n;
                // Scan back from the coast for the last bin clearly below the coast curve. "Clearly"
                // scales with count resolution - at 320 pulses/s one count is 3% of a bin, and a
                // fixed 2% let a single noisy coast bin end the drive 2.5 s late - and needs two
                // bins in a row.
                int end = -1;
                for (int i = fitFrom - 1; i >= 2; i--) {
                    if (belowCoast(i, intercept, c0) && belowCoast(i - 1, intercept, c0)) {
                        end = i;
                        break;
                    }
                }
                if (end >= 2) {
                    // The hand let go where the drive's own trajectory meets the coast curve. A
                    // line through the last three drive bins, intersected with the coast, finds it
                    // between bins; for a steady pull it is exactly the release.
                    double t0 = (end - 2 + 0.5) * BIN_S;
                    double sx = 0, sy = 0, sxx = 0, sxy = 0;
                    for (int i = end - 2; i <= end; i++) {
                        double x = (i + 0.5) * BIN_S - t0;
                        sx += x;
                        sy += strokeRates[i];
                        sxx += x * x;
                        sxy += x * strokeRates[i];
                    }
                    double slope = (3 * sxy - sx * sy) / (3 * sxx - sx * sx);
                    double level = (sy - slope * sx) / 3;
                    double lo = (end + 0.5) * BIN_S;
                    double hi = (fitFrom + 0.5) * BIN_S;
                    double release = (end + 1) * BIN_S;
                    if (gap(lo, level, slope, t0, intercept, c0) * gap(hi, level, slope, t0, intercept, c0) < 0) {
                        for (int k = 0; k < 40; k++) {
                            double mid = (lo + hi) / 2;
                            if (gap(lo, level, slope, t0, intercept, c0) * gap(mid, level, slope, t0, intercept, c0) <= 0) {
                                hi = mid;
                            } else {
                                lo = mid;
                            }
                        }
                        release = (lo + hi) / 2;
                    }
                    double whole = 0;
                    for (int i = 0; i <= end; i++) {
                        whole += strokeRates[i] * BIN_S;
                    }
                    double from = (end + 1) * BIN_S;
                    if (release > from) {
                        // Integrate the drive line from the end of the last whole bin to the release.
                        double a = level + slope * (from - t0);
                        double b = level + slope * (release - t0);
                        whole += (a + b) / 2 * (release - from);
                    } else {
                        release = from;
                    }
                    drivePulsesExact = whole;
                    driveBins = Math.max(2, Math.min(bins, (int) Math.round(release / BIN_S)));
                }
            }
        }
        long drivePulses = 0;
        if (drivePulsesExact >= 0) {
            drivePulses = Math.round(drivePulsesExact);
        } else {
            for (int i = 0; i < driveBins; i++) {
                drivePulses += Math.round(strokeRates[i] * BIN_S);
            }
        }
        recoveryBins = bins - driveBins;
        if (drivePulses < 10) {
            strokeBins = 0;
            return;
        }

        double[] plateau = new double[driveBins];
        int plateauN = 0;
        for (int i = 0; i < driveBins; i++) {
            if (strokeSmooth[i] >= 0.9 * peakRate) {
                plateau[plateauN++] = strokeRates[i];
            }
        }
        double plateauRate = medianOf(plateau, plateauN);

        double c = dragPerInertia();
        double inertia = inertiaCalibrated > 0 ? inertiaCalibrated : inertiaAuto;
        boolean energy = c > 0 && inertia > 0;
        double h = handleMetresPerPulse;

        float[] driveRates = new float[driveBins];
        float[] drivePower = energy ? new float[driveBins] : null;
        double peakPower = 0;
        double peakForce = 0;
        for (int i = 0; i < driveBins; i++) {
            double w = strokeRates[i];
            driveRates[i] = (float) w;
            if (energy) {
                double before = i > 0 ? strokeRates[i - 1] : w;
                double after = i + 1 < bins ? strokeRates[i + 1] : w;
                double dwdt = (after - before) / ((i > 0 && i + 1 < bins ? 2 : 1) * BIN_S);
                double p = Math.max(0, inertia * w * (c * w * w + dwdt));
                drivePower[i] = (float) p;
                peakPower = Math.max(peakPower, p);
                if (h > 0 && w > 0) {
                    peakForce = Math.max(peakForce, p / (w * h));
                }
            }
        }

        double work = Double.NaN;
        double avgPower = Double.NaN;
        if (energy) {
            double w3 = 0;
            for (int i = 0; i < bins; i++) {
                double w = strokeRates[i];
                w3 += w * w * w * BIN_S;
            }
            double endRate = strokeRates[bins - 1];
            work = Math.max(0, inertia * (c * w3 + 0.5 * (endRate * endRate - strokeStartRate * strokeStartRate)));
            avgPower = work / (bins * BIN_S);
        }

        strokeCount++;
        lastStroke = new Stroke(strokeCount, driveBins * BIN_S, recoveryBins * BIN_S, drivePulses,
                pulses, peakRate, plateauRate, work, avgPower, energy ? peakPower : Double.NaN,
                h > 0 ? drivePulses * h : Double.NaN, energy && h > 0 ? peakForce : Double.NaN,
                driveRates, drivePower);
        strokeBins = 0;
    }

    /** A drive bin sits below the fitted coast curve by more than count noise could explain. */
    private boolean belowCoast(int i, double intercept, double c) {
        double coast = 1.0 / (intercept + c * (i + 0.5) * BIN_S);
        double tolerance = Math.max(0.02, 2.0 / Math.max(1.0, coast * BIN_S));
        return 1.0 - strokeRates[i] / coast > tolerance;
    }

    /** Drive line minus coast curve at time t: zero where the hand let go. */
    private static double gap(double t, double level, double slope, double t0, double intercept, double c) {
        return level + slope * (t - t0) - 1.0 / (intercept + c * t);
    }

    /**
     * Fits {@code 1/w = 1/w0 + c t} to the recovery. Only the part above 30% of the stroke's peak:
     * at a crawl, bearing friction and count quantisation are no longer negligible.
     */
    private void fitCoast(int from, int to, double peakRate) {
        double floor = Math.max(MIN_ROWING_RATE, 0.3 * peakRate);
        int n = 0;
        double sx = 0;
        double sy = 0;
        double sxx = 0;
        double sxy = 0;
        double syy = 0;
        for (int i = from; i < to; i++) {
            double w = strokeRates[i];
            if (w < floor) {
                break;
            }
            double x = i * BIN_S;
            double y = 1.0 / w;
            n++;
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
            syy += y * y;
        }
        if (n < 5) {
            return;
        }
        double denom = n * sxx - sx * sx;
        if (denom <= 0) {
            return;
        }
        double slope = (n * sxy - sx * sy) / denom;
        double varY = n * syy - sy * sy;
        double r2 = varY > 0 ? (n * sxy - sx * sy) * (n * sxy - sx * sy) / (denom * varY) : 0;
        if (slope > 0 && r2 > 0.6) {
            fits[fitHead] = slope;
            fitHead = (fitHead + 1) % FIT_RING;
            if (fitCount < FIT_RING) {
                fitCount++;
            }
        }
    }
}
