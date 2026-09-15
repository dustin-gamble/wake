package com.codex.waterrowerdiagnostic;

/** Offline checks for SHUFFLE's order: complete rounds, never the same game twice running. */
public class ShuffleBagTest {

    private static int failures;

    public static void main(String[] args) {
        everyGameOncePerRound();
        neverTwiceInARow();
        peekMatchesNextWithinARound();
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll shuffle checks passed");
    }

    private static void everyGameOncePerRound() {
        ShuffleBag bag = new ShuffleBag(15, new java.util.Random(1));
        boolean ok = true;
        for (int round = 0; round < 20; round++) {
            boolean[] seen = new boolean[15];
            for (int i = 0; i < 15; i++) {
                int g = bag.next();
                if (seen[g]) ok = false;
                seen[g] = true;
            }
        }
        check("each of 15 games once per round, 20 rounds", ok, true);
    }

    private static void neverTwiceInARow() {
        boolean ok = true;
        for (long seed = 0; seed < 300; seed++) {
            ShuffleBag bag = new ShuffleBag(4, new java.util.Random(seed));
            int prev = -1;
            for (int i = 0; i < 40; i++) {
                int g = bag.next();
                if (g == prev) ok = false;
                prev = g;
            }
        }
        check("never the same game twice running, across round boundaries", ok, true);
    }

    private static void peekMatchesNextWithinARound() {
        ShuffleBag bag = new ShuffleBag(6, new java.util.Random(7));
        bag.next();
        int peek = bag.peek();
        check("peek shows what comes next", bag.next(), peek);
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) failures++;
        System.out.printf("%s %s%s%n", ok ? "PASS" : "FAIL", label, ok ? "" : "  (got " + actual + ")");
    }
}
