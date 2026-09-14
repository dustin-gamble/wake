package com.codex.waterrowerdiagnostic;

/** Offline checks for levels, the weekly goal and the forgiving streak. */
public class ProgressTest {

    private static int failures;

    public static void main(String[] args) {
        levelsFollowTheCurve();
        xpCountsMetresAndMinutes();
        weekRunsMondayToSunday();
        streakCountsConsecutiveDays();
        oneRestDayIsForgiven();
        twoMissedDaysBreakIt();
        todayNotYetRowedDoesNotBreakIt();
        roundTrips();
        if (failures > 0) {
            System.out.println("\n" + failures + " check(s) FAILED");
            System.exit(1);
        }
        System.out.println("\nAll progress checks passed");
    }

    private static void levelsFollowTheCurve() {
        check("0 XP is level 1", Progress.levelFor(0), 1);
        check("49 XP still level 1", Progress.levelFor(49), 1);
        check("50 XP is level 2", Progress.levelFor(50), 2);
        check("200 XP is level 3", Progress.levelFor(200), 3);
        check("level 3 needs 200 XP", Progress.xpForLevel(3), 200.0);
    }

    private static void xpCountsMetresAndMinutes() {
        Progress p = new Progress();
        p.addRowing(1000, 600, 2500);   // 10 minutes, 2500 m
        check("10 min and 2500 m is 270 XP", Math.round(p.totalXp()), 270L);
    }

    private static void weekRunsMondayToSunday() {
        // Epoch day 4 was Monday 5 Jan 1970.
        check("Monday starts its own week", Progress.weekStart(4), 4L);
        check("Sunday belongs to the week before", Progress.weekStart(10), 4L);
        Progress p = new Progress();
        p.addRowing(3, 1200, 0);    // Sunday of the previous week
        p.addRowing(4, 600, 0);     // Monday
        p.addRowing(6, 900, 0);     // Wednesday
        check("week minutes count only this week", Math.round(p.weekMinutes(6)), 25);
    }

    private static void streakCountsConsecutiveDays() {
        Progress p = new Progress();
        for (long d = 100; d <= 104; d++) p.addRowing(d, 600, 1000);
        check("five days in a row", p.streak(104), 5);
        p.addRowing(106, 120, 300);   // a 2-minute day does not count
        check("a token day is not a rowing day", p.minutesOn(106) < Progress.STREAK_DAY_MINUTES, true);
    }

    private static void oneRestDayIsForgiven() {
        Progress p = new Progress();
        for (long d = 200; d <= 203; d++) p.addRowing(d, 600, 1000);
        // rest on 204
        for (long d = 205; d <= 207; d++) p.addRowing(d, 600, 1000);
        check("a rest day keeps the streak (7 rowing days)", p.streak(207), 7);
    }

    private static void twoMissedDaysBreakIt() {
        Progress p = new Progress();
        for (long d = 300; d <= 303; d++) p.addRowing(d, 600, 1000);
        for (long d = 306; d <= 307; d++) p.addRowing(d, 600, 1000);
        check("two missed days in a row break it", p.streak(307), 2);
    }

    private static void todayNotYetRowedDoesNotBreakIt() {
        Progress p = new Progress();
        for (long d = 400; d <= 402; d++) p.addRowing(d, 600, 1000);
        check("streak still 3 on the morning of day 403", p.streak(403), 3);
    }

    private static void roundTrips() {
        Progress p = new Progress();
        p.setGoalMinutes(90);
        p.addRowing(500, 1800, 7000);
        p.addRowing(501, 900, 3200);
        Progress q = new Progress();
        check("decodes its encoding", q.decode(p.encode()), true);
        check("goal survives", Math.round(q.goalMinutes()), 90);
        check("XP survives", Math.round(q.totalXp()), Math.round(p.totalXp()));
        check("rejects garbage", new Progress().decode("nope"), false);
    }

    private static void check(String label, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) failures++;
        System.out.printf("%s %s%s%n", ok ? "PASS" : "FAIL", label, ok ? "" : "  (got " + actual + ")");
    }
}
