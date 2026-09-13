package com.codex.waterrowerdiagnostic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/**
 * Offline checks for the S4 decoder, driven by packets captured from the Ergatta unit.
 * S4Protocol has no Android dependencies, so it compiles and runs under plain javac.
 */
public class S4ProtocolTest {

    private static int failures;

    public static void main(String[] args) throws Exception {
        feedsPacketsAndDecodes();
        derivesMinutesFromSecondsRollover();
        ignoresPulsePacketsAsMemoryReplies();
        splitsPacketsAcrossReadBoundaries();
        rejectsOnlyTheOutstandingRequest();
        clampsNonsensePace();
        reportsStrokeRateUnmultiplied();
        rateRisesAtOnceAndFallsSlowly();

        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll checks passed");
    }

    /* ---------- checks ---------- */

    private static void feedsPacketsAndDecodes() throws Exception {
        Object p = newProtocol();
        feed(p, "_WR_\r\nIDD140001C\r\nIDD0880016\r\n");
        Object s = snapshot(p);
        check("monitorConnected after _WR_", getBool(s, "monitorConnected"), true);
        check("strokes from IDD140001C (0x1C)", getInt(s, "strokes"), 28);
        check("watts from IDD0880016 (0x16)", getInt(s, "watts"), 22);
    }

    /** 1E2/1E3 are unreadable on this unit, so minutes come from watching 1E1 wrap. */
    private static void derivesMinutesFromSecondsRollover() throws Exception {
        Object p = newProtocol();
        feed(p, "IDS1E158\r\n");
        check("elapsed at 58s", getInt(snapshot(p), "elapsedSeconds"), 58);

        feed(p, "IDS1E159\r\nIDS1E100\r\nIDS1E101\r\n");
        check("elapsed after one rollover", getInt(snapshot(p), "elapsedSeconds"), 61);

        feed(p, "IDS1E159\r\nIDS1E105\r\n");
        check("elapsed after two rollovers", getInt(snapshot(p), "elapsedSeconds"), 125);
    }

    /** A pulse must never be mistaken for the reply to an outstanding memory read. */
    private static void ignoresPulsePacketsAsMemoryReplies() throws Exception {
        Object p = newProtocol();
        String request = (String) call(p, "beginNextPoll");
        check("first poll is a live address", request.startsWith("IR"), true);

        feed(p, "P01\r\nP0F\r\nPING\r\n");
        Object result = call(p, "awaitPollResult", new Class<?>[] { long.class }, 120L);
        check("pulses do not satisfy a pending read", result.toString(), "TIMED_OUT");
        check("pulses counted", getLong(snapshot(p), "pulsesSeen"), 2L);
    }

    /** USB reads split lines arbitrarily; framing must survive it. */
    private static void splitsPacketsAcrossReadBoundaries() throws Exception {
        Object p = newProtocol();
        feed(p, "IDD14");
        feed(p, "0001C");
        feed(p, "\r\n");
        check("strokes reassembled across reads", getInt(snapshot(p), "strokes"), 28);
    }

    /** ERROR names no address, so it may only settle the request actually outstanding. */
    private static void rejectsOnlyTheOutstandingRequest() throws Exception {
        Object p = newProtocol();
        call(p, "beginNextPoll");
        feed(p, "ERROR\r\n");
        Object result = call(p, "awaitPollResult", new Class<?>[] { long.class }, 120L);
        check("ERROR rejects the outstanding read", result.toString(), "REJECTED");

        feed(p, "ERROR\r\n");
        check("stray ERROR with nothing pending is harmless", true, true);
    }

    /** A near-stopped flywheel divided into 50000 produced hours-per-500m figures. */
    private static void clampsNonsensePace() throws Exception {
        Object p = newProtocol();
        feed(p, "IDD14A0003\r\n");   // 3 cm/s: barely moving
        check("crawling speed reports no pace", getInt(snapshot(p), "paceSecondsPer500m"), 0);

        Object q = newProtocol();
        feed(q, "IDD14A0173\r\n");   // 0x173 = 371 cm/s, a real pace
        check("real speed yields a sane pace", getInt(snapshot(q), "paceSecondsPer500m"), 134);
    }

    /** 1A9 is the stroke rate directly; doubling it produced impossible cadences. */
    private static void reportsStrokeRateUnmultiplied() throws Exception {
        Object p = newProtocol();
        feed(p, "IDS1A925\r\n");          // 0x25 = 37, the observed sprint peak
        Object s = snapshot(p);
        check("1A9 reported as-is", getInt(s, "strokeRate"), 37);
        check("raw kept for comparison", getInt(s, "strokeRateRaw"), 37);
    }

    /**
     * The displayed rate must rise at once and fall slowly.
     *
     * <p>Real capture: 1A9 sat on 25 and dipped to 19-21 in about 9% of samples, which looked like
     * a stroke that had not counted - while the counter showed 106 consecutive ticks, every delta
     * exactly 1. But the first fix, a trimmed mean, also threw away genuine hard strokes. No
     * symmetric filter can do both, so this one is asymmetric.
     */
    private static void rateRisesAtOnceAndFallsSlowly() throws Exception {
        Object p = newProtocol();
        for (int i = 0; i < 6; i++) {
            feed(p, "IDS1A919\r\n");     // 0x19 = 25spm, held
        }
        check("settles on the held rate", getInt(snapshot(p), "strokeRateAverage"), 25);

        feed(p, "IDS1A91C\r\n");         // 0x1C = 28spm, a real hard stroke
        check("a hard stroke shows immediately", getInt(snapshot(p), "strokeRateAverage"), 28);

        // An isolated dip must barely move it. Snapshots here are microseconds apart, so almost
        // no fall time has elapsed and the figure should still be near 28.
        feed(p, "IDS1A913\r\n");         // 0x13 = 19spm, the artefact
        Object s = snapshot(p);
        check("raw still shows the dip", getInt(s, "strokeRate"), 19);
        int shown = getInt(s, "strokeRateAverage");
        if (shown >= 27) {
            System.out.println("PASS an isolated dip barely moves the display (" + shown + ")");
        } else {
            check("an isolated dip barely moves the display", shown, 28);
        }

        // A sustained drop must actually arrive, given time.
        Thread.sleep(1200);
        feed(p, "IDS1A913\r\n");
        int later = getInt(snapshot(p), "strokeRateAverage");
        if (later < shown) {
            System.out.println("PASS a sustained drop falls through (" + shown + " -> " + later + ")");
        } else {
            check("a sustained drop falls through", later, 19);
        }
    }

    /* ---------- harness ---------- */

    private static Object newProtocol() throws Exception {
        Class<?> cls = S4Protocol.class;
        Constructor<?> ctor = cls.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(new Object[] { null });
    }

    private static void feed(Object protocol, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        Method accept = protocol.getClass().getDeclaredMethod("accept", byte[].class, int.class);
        accept.setAccessible(true);
        accept.invoke(protocol, bytes, bytes.length);
    }

    private static Object snapshot(Object protocol) throws Exception {
        return call(protocol, "snapshot");
    }

    private static Object call(Object target, String name) throws Exception {
        return call(target, name, new Class<?>[0]);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... args)
            throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static int getInt(Object status, String field) throws Exception {
        return status.getClass().getDeclaredField(field).getInt(status);
    }

    private static long getLong(Object status, String field) throws Exception {
        return status.getClass().getDeclaredField(field).getLong(status);
    }

    private static boolean getBool(Object status, String field) throws Exception {
        return status.getClass().getDeclaredField(field).getBoolean(status);
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) {
            failures++;
        }
        System.out.printf("%s %s%n  expected %s, got %s%n",
                ok ? "PASS" : "FAIL", label, expected, actual);
    }
}
