package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Personal bests, kept on the tablet so games work with the laptop switched off.
 *
 * <p>Keys are free-form strings such as {@code time.1000} (seconds for 1000m) or
 * {@code run.score}. Lower-is-better records use {@link #recordLowest}, higher-is-better use
 * {@link #recordHighest}; both return whether a new best was set.
 */
final class PersonalBests {

    private static final String PREFS = "wake-personal-bests";

    private final SharedPreferences prefs;

    PersonalBests(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    float get(String key, float fallback) {
        return prefs.getFloat(key, fallback);
    }

    boolean has(String key) {
        return prefs.contains(key);
    }

    boolean recordLowest(String key, float value) {
        if (!prefs.contains(key) || value < prefs.getFloat(key, Float.MAX_VALUE)) {
            prefs.edit().putFloat(key, value).apply();
            return true;
        }
        return false;
    }

    boolean recordHighest(String key, float value) {
        if (!prefs.contains(key) || value > prefs.getFloat(key, -Float.MAX_VALUE)) {
            prefs.edit().putFloat(key, value).apply();
            return true;
        }
        return false;
    }

    /**
     * Overwrites, unlike the record* helpers. For a running total that can also be reset - the
     * flight's trip goes back to zero when the user restarts at the Golden Gate.
     */
    void putFloat(String key, float value) {
        prefs.edit().putFloat(key, value).apply();
    }

    void putString(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    String getString(String key) {
        return prefs.getString(key, null);
    }

    /** Every stored record, for the Records screen. */
    java.util.Map<String, ?> all() {
        return prefs.getAll();
    }

    static String formatTime(float seconds) {
        int total = Math.round(seconds);
        int m = total / 60;
        int s = total % 60;
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    static String formatPace(float secondsPer500) {
        if (secondsPer500 <= 0 || secondsPer500 > 600) {
            return "--:--";
        }
        int total = Math.round(secondsPer500);
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60);
    }
}
