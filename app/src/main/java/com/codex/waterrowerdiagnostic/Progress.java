package com.codex.waterrowerdiagnostic;

/**
 * Reasons to come back tomorrow: a level, a weekly goal and a streak, from a simple daily log.
 *
 * <p>Each day keeps minutes actually rowed and metres. XP is a point per 10 m plus two per minute,
 * so both steady long rows and hard short ones earn. Levels need 50 x (level - 1)^2 XP, a curve that
 * is quick early and slows down. The week runs Monday to Sunday. A streak counts days with at least
 * {@link #STREAK_DAY_MINUTES} of rowing and forgives one missed day in every seven, so a rest day -
 * which training needs - does not cost the streak.
 *
 * <p>Pure Java; the activity persists it as a string. Tested by {@code ProgressTest}.
 */
final class Progress {

    static final float DEFAULT_GOAL_MINUTES = 60f;
    static final float STREAK_DAY_MINUTES = 5f;
    private static final int KEEP_DAYS = 400;

    /** day -> {minutes, metres} */
    private final java.util.TreeMap<Long, float[]> days = new java.util.TreeMap<>();
    private float goalMinutes = DEFAULT_GOAL_MINUTES;

    synchronized void addRowing(long day, float seconds, float metres) {
        float[] d = days.get(day);
        if (d == null) {
            d = new float[2];
            days.put(day, d);
        }
        d[0] += Math.max(0f, seconds) / 60f;
        d[1] += Math.max(0f, metres);
        while (days.size() > KEEP_DAYS) {
            days.pollFirstEntry();
        }
    }

    synchronized float minutesOn(long day) {
        float[] d = days.get(day);
        return d == null ? 0f : d[0];
    }

    synchronized float metresOn(long day) {
        float[] d = days.get(day);
        return d == null ? 0f : d[1];
    }

    synchronized void setGoalMinutes(float minutes) {
        goalMinutes = Math.max(10f, minutes);
    }

    synchronized float goalMinutes() {
        return goalMinutes;
    }

    synchronized double totalXp() {
        double xp = 0;
        for (float[] d : days.values()) {
            xp += d[1] / 10.0 + d[0] * 2.0;
        }
        return xp;
    }

    static int levelFor(double xp) {
        return (int) Math.floor(Math.sqrt(Math.max(0, xp) / 50.0)) + 1;
    }

    static double xpForLevel(int level) {
        return 50.0 * (level - 1) * (level - 1);
    }

    /**
     * Monday of the week containing {@code day}. Epoch day 0 was a Thursday.
     *
     * <p>Plain remainder arithmetic on purpose: {@code Math.floorMod(long, int)} only exists from
     * Android 13, and the tablet runs 9. The build tool happened to backport it, but nothing should
     * depend on that.
     */
    static long weekStart(long day) {
        long offset = ((day + 3) % 7 + 7) % 7;
        return day - offset;
    }

    synchronized float weekMinutes(long today) {
        float sum = 0f;
        for (long d = weekStart(today); d <= today; d++) {
            sum += minutesOn(d);
        }
        return sum;
    }

    /**
     * Days in a row with real rowing, ending today - or yesterday, so a streak is not lost before
     * today's row. One missed day in each seven is forgiven.
     */
    synchronized int streak(long today) {
        if (days.isEmpty()) {
            return 0;
        }
        long first = days.firstKey();
        long d = minutesOn(today) >= STREAK_DAY_MINUTES ? today : today - 1;
        int streak = 0;
        int graceLeft = 1;
        int walked = 0;
        while (d >= first) {
            if (minutesOn(d) >= STREAK_DAY_MINUTES) {
                streak++;
            } else if (graceLeft > 0 && streak > 0 && minutesOn(d - 1) >= STREAK_DAY_MINUTES) {
                graceLeft--;
            } else {
                break;
            }
            d--;
            walked++;
            if (walked % 7 == 0) {
                graceLeft = 1;
            }
        }
        return streak;
    }

    synchronized String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append("g=").append(Math.round(goalMinutes)).append(';');
        boolean first = true;
        for (java.util.Map.Entry<Long, float[]> e : days.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append(':')
                    .append(String.format(java.util.Locale.US, "%.1f", e.getValue()[0])).append(':')
                    .append(Math.round(e.getValue()[1]));
        }
        return sb.toString();
    }

    synchronized boolean decode(String text) {
        if (text == null || !text.startsWith("g=")) {
            return false;
        }
        int semi = text.indexOf(';');
        if (semi < 0) {
            return false;
        }
        try {
            float goal = Float.parseFloat(text.substring(2, semi));
            java.util.TreeMap<Long, float[]> parsed = new java.util.TreeMap<>();
            String body = text.substring(semi + 1);
            if (!body.isEmpty()) {
                for (String entry : body.split(",")) {
                    String[] p = entry.split(":");
                    if (p.length == 3) {
                        parsed.put(Long.parseLong(p[0]), new float[]{Float.parseFloat(p[1]), Float.parseFloat(p[2])});
                    }
                }
            }
            goalMinutes = goal;
            days.clear();
            days.putAll(parsed);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
