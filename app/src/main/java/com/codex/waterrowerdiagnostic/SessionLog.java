package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every completed row, kept on the tablet, per profile.
 *
 * <p>One {@link Session} per piece: when it started, how long the <b>rowing clock</b> ran, metres,
 * average and peak watts, average split, strokes, measured energy, which game it was rowed on, and
 * a per-30-second series of split and watts so the history screen can draw a chart without keeping
 * the whole sample stream.
 *
 * <p>Storage is a single JSON array in its own preferences file, one file per profile (see
 * {@link RowerProfiles#prefsNameFor(String, String)} - the first profile gets {@code wake-sessions}
 * and later ones {@code wake-sessions-<id>}). Preferences survive an app update, which is the point:
 * a new APK never costs the rower their history. The log is capped at {@value #MAX_SESSIONS}
 * sessions and the oldest are dropped past that - roughly a couple of years of daily rowing, and a
 * few hundred kilobytes.
 *
 * <p>No Android UI imports, no third-party libraries, and nothing leaves the tablet unless the
 * caller exports it.
 */
final class SessionLog {

    /** Base preferences name; namespaced per profile by {@link RowerProfiles}. */
    static final String PREFS_BASE = "wake-sessions";

    private static final String KEY_SESSIONS = "sessions";
    private static final String KEY_VERSION = "version";
    private static final int VERSION = 1;

    /** Older than this is not a row, it is a false start. */
    static final int MIN_SECONDS = 30;
    /** Below this many strokes nothing is logged either. */
    static final int MIN_STROKES = 1;

    static final int MAX_SESSIONS = 2000;

    /** Chart series bucket, seconds. */
    static final int SERIES_BUCKET_SECONDS = 30;
    /** Buckets kept per session; longer rows are decimated (60 s, then 120 s...). */
    static final int MAX_SERIES_POINTS = 160;

    /** Anything before 2015 is a tablet whose clock had not been set yet. */
    private static final long MIN_EPOCH = 1420070400000L;

    /** Longest daily series anyone can ask for - ten years, so a bad argument cannot OOM. */
    private static final int MAX_DAILY_POINTS = 3660;

    /** Import bigger than this is not a WAKE export; refuse it rather than try to parse it. */
    private static final int MAX_IMPORT_CHARS = 8 * 1024 * 1024;

    // ------------------------------------------------------------------ model

    /** One completed row. Immutable. */
    static final class Session {
        /** Epoch millis at the first stroke. */
        final long startedAt;
        /** Rowing-clock seconds (pauses excluded), not wall time. */
        final int durationSeconds;
        final float metres;
        final float avgWatts;
        /** Seconds per 500 m over the whole piece; 0 when unknown. */
        final float avgSplit;
        final int strokes;
        final float peakWatts;
        /** Mechanical work, joules (0 if the meter never produced one). */
        final double joules;
        /** Home-card title of the game it was rowed on, e.g. "HEAD RACE". Never null. */
        final String game;
        /** Split (s/500m) per {@link #SERIES_BUCKET_SECONDS}, rounded. May be empty, never null. */
        final int[] splitSeries;
        /** Average watts per bucket, rounded. Same length as {@link #splitSeries}. */
        final int[] wattsSeries;
        /** Seconds each series bucket covers - 30 normally, more for a long decimated piece. */
        final int seriesBucketSeconds;

        Session(long startedAt, int durationSeconds, float metres, float avgWatts, float avgSplit,
                int strokes, float peakWatts, double joules, String game,
                int[] splitSeries, int[] wattsSeries, int seriesBucketSeconds) {
            this.startedAt = startedAt;
            this.durationSeconds = durationSeconds;
            this.metres = metres;
            this.avgWatts = avgWatts;
            this.avgSplit = avgSplit;
            this.strokes = strokes;
            this.peakWatts = peakWatts;
            this.joules = joules;
            this.game = game == null ? "" : game;
            this.splitSeries = splitSeries == null ? EMPTY : splitSeries;
            this.wattsSeries = wattsSeries == null ? EMPTY : wattsSeries;
            this.seriesBucketSeconds = seriesBucketSeconds <= 0
                    ? SERIES_BUCKET_SECONDS : seriesBucketSeconds;
        }

        float strokeRate() {
            return durationSeconds > 0 ? strokes * 60f / durationSeconds : 0f;
        }

        float calories() {
            return (float) (joules / 4184.0 / 0.25);
        }

        /** Local midnight of the day this row started. */
        long dayStart() {
            return startOfDay(startedAt);
        }
    }

    private static final int[] EMPTY = new int[0];

    /** Totals and bests over a range. All zero when the range is empty. */
    static final class Totals {
        int sessions;
        float metres;
        int seconds;
        int strokes;
        double joules;
        float avgWatts;
        /** Lowest (fastest) average split in the range; 0 when none. */
        float bestSplit;
        float mostMetres;
        int longestSeconds;
        float peakWatts;
        /** Distinct local days with at least one session. */
        int activeDays;
    }

    // ------------------------------------------------------------------ state

    private final SharedPreferences prefs;
    private ArrayList<Session> cache;

    /**
     * @param prefsName the per-profile file name, from
     *                  {@code profiles.prefsNameFor(id, SessionLog.PREFS_BASE)}.
     */
    SessionLog(Context context, String prefsName) {
        String name = (prefsName == null || prefsName.isEmpty()) ? PREFS_BASE : prefsName;
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    /** The log for one profile. */
    static SessionLog forProfile(Context context, RowerProfiles profiles, String profileId) {
        return new SessionLog(context, profiles.prefsNameFor(profileId, PREFS_BASE));
    }

    /** The log for whoever is rowing now. */
    static SessionLog forActiveProfile(Context context, RowerProfiles profiles) {
        return forProfile(context, profiles, profiles.activeId());
    }

    // ------------------------------------------------------------------ writing

    /**
     * Appends a session, oldest dropped past the cap.
     *
     * @return true if it was stored; false if it was too short, had no strokes, or was null.
     */
    synchronized boolean add(Session session) {
        if (!isLoggable(session)) {
            return false;
        }
        ArrayList<Session> all = load();
        all.add(session);
        sortNewestFirst(all);
        while (all.size() > MAX_SESSIONS) {
            all.remove(all.size() - 1);
        }
        save(all);
        return true;
    }

    /** The rule, exposed so a caller can grey out a "save" affordance. */
    static boolean isLoggable(Session s) {
        return s != null
                && s.durationSeconds >= MIN_SECONDS
                && s.strokes >= MIN_STROKES
                && s.metres > 0f;
    }

    /** Removes one session by its start time. */
    synchronized boolean remove(long startedAt) {
        ArrayList<Session> all = load();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).startedAt == startedAt) {
                all.remove(i);
                save(all);
                return true;
            }
        }
        return false;
    }

    synchronized void clear() {
        cache = new ArrayList<>();
        prefs.edit().remove(KEY_SESSIONS).remove(KEY_VERSION).apply();
    }

    // ------------------------------------------------------------------ reading

    /** Most recent first. The returned list is a copy; mutating it changes nothing. */
    synchronized List<Session> list() {
        return new ArrayList<>(load());
    }

    synchronized int size() {
        return load().size();
    }

    /** Null when the log is empty. */
    synchronized Session latest() {
        ArrayList<Session> all = load();
        return all.isEmpty() ? null : all.get(0);
    }

    /** Most recent first, at most {@code limit}. */
    synchronized List<Session> recent(int limit) {
        ArrayList<Session> all = load();
        int n = Math.max(0, Math.min(limit, all.size()));
        return new ArrayList<>(all.subList(0, n));
    }

    /** Sessions started in {@code [fromMillis, toMillis)}, most recent first. */
    synchronized List<Session> listRange(long fromMillis, long toMillis) {
        ArrayList<Session> out = new ArrayList<>();
        ArrayList<Session> all = load();
        for (int i = 0; i < all.size(); i++) {
            Session s = all.get(i);
            if (s.startedAt >= fromMillis && s.startedAt < toMillis) {
                out.add(s);
            }
        }
        return out;
    }

    /** Everything on the local day containing {@code dayMillis}. */
    List<Session> listDay(long dayMillis) {
        long from = startOfDay(dayMillis);
        return listRange(from, addDays(from, 1));
    }

    /** The Monday-start week containing {@code millis}. */
    List<Session> listWeek(long millis) {
        long from = startOfWeek(millis);
        return listRange(from, addDays(from, 7));
    }

    /** The calendar month containing {@code millis}. */
    List<Session> listMonth(long millis) {
        long from = startOfMonth(millis);
        return listRange(from, addMonths(from, 1));
    }

    /** Totals and bests over a range. */
    Totals totals(long fromMillis, long toMillis) {
        return totalsOf(listRange(fromMillis, toMillis));
    }

    /** Totals over everything. */
    synchronized Totals totalsAll() {
        return totalsOf(load());
    }

    static Totals totalsOf(List<Session> sessions) {
        Totals t = new Totals();
        if (sessions == null || sessions.isEmpty()) {
            return t;
        }
        double workSeconds = 0;
        double wattSeconds = 0;
        // A HashSet, not a list scan: over a full log this ran 2000 x 2000 Long comparisons on
        // whatever thread the history screen asked for its totals on.
        java.util.HashSet<Long> days = new java.util.HashSet<>();
        for (int i = 0; i < sessions.size(); i++) {
            Session s = sessions.get(i);
            t.sessions++;
            t.metres += s.metres;
            t.seconds += s.durationSeconds;
            t.strokes += s.strokes;
            t.joules += s.joules;
            workSeconds += s.durationSeconds;
            wattSeconds += (double) s.avgWatts * s.durationSeconds;
            if (s.avgSplit > 0 && (t.bestSplit <= 0 || s.avgSplit < t.bestSplit)) {
                t.bestSplit = s.avgSplit;
            }
            if (s.metres > t.mostMetres) {
                t.mostMetres = s.metres;
            }
            if (s.durationSeconds > t.longestSeconds) {
                t.longestSeconds = s.durationSeconds;
            }
            if (s.peakWatts > t.peakWatts) {
                t.peakWatts = s.peakWatts;
            }
            days.add(Long.valueOf(startOfDay(s.startedAt)));
        }
        t.avgWatts = workSeconds > 0 ? (float) (wattSeconds / workSeconds) : 0f;
        t.activeDays = days.size();
        return t;
    }

    /** A day count a caller can actually have asked for: at least one, at most ten years. */
    private static int clampDays(int days) {
        return Math.max(1, Math.min(days, MAX_DAILY_POINTS));
    }

    /**
     * Metres per local day across {@code days} days ending today, oldest first - the shape a bar
     * chart wants. Index {@code days - 1} is today.
     */
    float[] dailyMetres(int days) {
        return dailyMetres(days, System.currentTimeMillis());
    }

    float[] dailyMetres(int days, long endingMillis) {
        int n = clampDays(days);
        float[] out = new float[n];
        long lastDay = startOfDay(endingMillis);
        long from = addDays(lastDay, -(n - 1));
        List<Session> range = listRange(from, addDays(lastDay, 1));
        for (int i = 0; i < range.size(); i++) {
            Session s = range.get(i);
            int index = (int) Math.round((startOfDay(s.startedAt) - from) / 86400000.0);
            if (index >= 0 && index < n) {
                out[index] += s.metres;
            }
        }
        return out;
    }

    /** Rowing-clock minutes per local day, same shape as {@link #dailyMetres(int)}. */
    float[] dailyMinutes(int days, long endingMillis) {
        int n = clampDays(days);
        float[] out = new float[n];
        long lastDay = startOfDay(endingMillis);
        long from = addDays(lastDay, -(n - 1));
        List<Session> range = listRange(from, addDays(lastDay, 1));
        for (int i = 0; i < range.size(); i++) {
            Session s = range.get(i);
            int index = (int) Math.round((startOfDay(s.startedAt) - from) / 86400000.0);
            if (index >= 0 && index < n) {
                out[index] += s.durationSeconds / 60f;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ export / import

    /** The whole log as JSON, for a backup or for the laptop dashboard. */
    synchronized String exportJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("version", VERSION);
            root.put("exportedAt", System.currentTimeMillis());
            root.put("sessions", toArray(load()));
        } catch (JSONException ignored) {
            // JSONObject.put only throws on NaN/Infinity, which toArray has already scrubbed.
        }
        return root.toString();
    }

    /**
     * Reads back an {@link #exportJson()} payload. Accepts either the wrapper object or a bare
     * array of sessions.
     *
     * @param replace true to drop what is stored first; false to merge, keeping one session per
     *                start time (the stored one wins).
     * @return how many sessions were added, or -1 if the text could not be parsed.
     */
    synchronized int importJson(String json, boolean replace) {
        if (json == null || json.trim().isEmpty()) {
            return -1;
        }
        if (json.length() > MAX_IMPORT_CHARS) {
            // A full log exports to a few hundred KB. Anything this size is corrupt or not ours,
            // and parsing it would build the whole tree in memory on a tablet.
            return -1;
        }
        JSONArray array = null;
        String trimmed = json.trim();
        try {
            if (trimmed.startsWith("[")) {
                array = new JSONArray(trimmed);
            } else {
                array = new JSONObject(trimmed).optJSONArray("sessions");
            }
        } catch (JSONException e) {
            return -1;
        }
        if (array == null) {
            return -1;
        }
        ArrayList<Session> all = replace ? new ArrayList<Session>() : load();
        int added = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            Session s = fromJson(o);
            if (s == null || !isLoggable(s)) {
                continue;
            }
            boolean duplicate = false;
            for (int j = 0; j < all.size(); j++) {
                if (all.get(j).startedAt == s.startedAt) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                all.add(s);
                added++;
            }
        }
        sortNewestFirst(all);
        while (all.size() > MAX_SESSIONS) {
            all.remove(all.size() - 1);
        }
        save(all);
        return added;
    }

    // ------------------------------------------------------------------ recorder

    /**
     * Collects a session while the rower rows, so the caller does not have to keep its own
     * accumulators.
     *
     * <p>Feed it {@link #update} on every status (12 Hz is fine - it allocates nothing per call
     * except when a 30 s bucket closes), then {@link #build}. Everything is cumulative
     * session-to-date, which is exactly what {@code GameView} already holds: the rowing clock,
     * {@code sessionMeters}, the stroke count and the meter's joules.
     */
    static final class Recorder {
        private String game;
        private long startedAt;
        private boolean started;

        private float seconds;
        private float metres;
        private int strokes;
        private double joules;
        private float peakWatts;

        private double wattSecondsTotal;
        private float lastSeconds;

        // open bucket
        private double bucketWattSeconds;
        private float bucketStartSeconds;
        private float bucketStartMetres;

        private int[] splits = new int[32];
        private int[] watts = new int[32];
        private int points;
        private int bucketSeconds = SERIES_BUCKET_SECONDS;

        Recorder(String game) {
            this.game = game == null ? "" : game;
        }

        /** Rename mid-piece - SHUFFLE switches games inside one row. */
        void setGame(String game) {
            if (game != null && !game.isEmpty()) {
                this.game = game;
            }
        }

        String game() {
            return game;
        }

        boolean started() {
            return started;
        }

        long startedAt() {
            return startedAt;
        }

        /** Throws away everything collected. */
        void reset() {
            started = false;
            startedAt = 0;
            seconds = 0;
            metres = 0;
            strokes = 0;
            joules = 0;
            peakWatts = 0;
            wattSecondsTotal = 0;
            lastSeconds = 0;
            bucketWattSeconds = 0;
            bucketStartSeconds = 0;
            bucketStartMetres = 0;
            points = 0;
            bucketSeconds = SERIES_BUCKET_SECONDS;
        }

        /**
         * @param rowingSeconds cumulative rowing clock (the paused one), seconds
         * @param totalMetres   cumulative session metres
         * @param watts         the current reading, for the average and the peak
         * @param totalStrokes  cumulative stroke count
         * @param totalJoules   cumulative measured work; pass 0 when the meter has none
         */
        void update(float rowingSeconds, float totalMetres, float watts, int totalStrokes,
                    double totalJoules) {
            if (rowingSeconds < 0 || !isFinite(rowingSeconds)) {
                return;
            }
            // A non-finite distance used to be taken at face value on the first update, which set
            // bucketStartMetres to NaN - and nothing ever cleared it, because each bucket's end
            // metres are derived from it. Measured: one NaN reading at the catch made every one of
            // that row's split buckets 0, so the whole row charted as a gap. The summary row still
            // stored (metres recovers on the next reading); it was only the series that was lost.
            if (!isFinite(totalMetres) || totalMetres < 0) {
                totalMetres = metres;
            }
            if (!isFinite(watts)) {
                watts = 0f;
            }
            if (Double.isNaN(totalJoules) || Double.isInfinite(totalJoules)) {
                totalJoules = joules;
            }
            if (!started) {
                started = true;
                startedAt = nowSane();
                lastSeconds = rowingSeconds;
                bucketStartMetres = totalMetres;
            }
            // Elapsed is accumulated from positive steps only, never read straight off the caller's
            // clock: a game restart or a reset hands back a smaller number, and a duration taken
            // from the raw value would then disagree with the watt-seconds already integrated.
            float dt = rowingSeconds - lastSeconds;
            if (dt < 0 || dt > 60f) {
                dt = 0;
            }
            lastSeconds = rowingSeconds;
            seconds += dt;

            if (totalMetres > metres) {
                metres = totalMetres;
            }
            if (totalStrokes > strokes) {
                strokes = totalStrokes;
            }
            if (totalJoules > joules) {
                joules = totalJoules;
            }
            if (watts > peakWatts && watts < 2000f) {
                peakWatts = watts;
            }
            if (watts > 0 && watts < 2000f && dt > 0) {
                wattSecondsTotal += (double) watts * dt;
                bucketWattSeconds += (double) watts * dt;
            }

            // Normally this closes at most one bucket (updates arrive many times a second). If a
            // long step did arrive, the metres AND the watt-seconds over the gap are spread across
            // it linearly rather than dumped into the first bucket - otherwise the first bucket
            // reads the whole gap's power and the ones behind it read a flat zero.
            int guard = 0;
            while (seconds - bucketStartSeconds >= bucketSeconds && guard++ < 64) {
                float spanSeconds = seconds - bucketStartSeconds;
                float spanMetres = metres - bucketStartMetres;
                float fraction = spanSeconds > 0 ? bucketSeconds / spanSeconds : 1f;
                if (fraction > 1f) {
                    fraction = 1f;
                }
                double share = bucketWattSeconds * fraction;
                bucketWattSeconds -= share;
                closeBucket(bucketStartSeconds + bucketSeconds,
                        bucketStartMetres + spanMetres * fraction, share);
            }
        }

        /** @param wattSeconds this bucket's share of the integrated power. */
        private void closeBucket(float endSeconds, float endMetres, double wattSeconds) {
            float dt = endSeconds - bucketStartSeconds;
            float dm = endMetres - bucketStartMetres;
            // 0 means "no split for this bucket" - the rower was resting. The history chart draws
            // it as a gap, so it must not be confused with a real, very fast split.
            int split = (dm > 1f && dt > 0) ? Math.round(dt / dm * 500f) : 0;
            if (split > 9999) {
                split = 9999;
            }
            int w = dt > 0 ? (int) Math.round(wattSeconds / dt) : 0;
            push(split, w < 0 ? 0 : w);
            bucketStartSeconds = endSeconds;
            bucketStartMetres = isFinite(endMetres) ? endMetres : bucketStartMetres;
        }

        private void push(int split, int w) {
            if (points == splits.length) {
                int[] s2 = new int[splits.length * 2];
                int[] w2 = new int[watts.length * 2];
                System.arraycopy(splits, 0, s2, 0, points);
                System.arraycopy(watts, 0, w2, 0, points);
                splits = s2;
                watts = w2;
            }
            splits[points] = split;
            watts[points] = w;
            points++;
            if (points >= MAX_SERIES_POINTS) {
                decimate();
            }
        }

        /** Halve the resolution so a two-hour row still fits in a few hundred bytes. */
        private void decimate() {
            int out = 0;
            for (int i = 0; i + 1 < points; i += 2) {
                splits[out] = average(splits[i], splits[i + 1]);
                watts[out] = (watts[i] + watts[i + 1]) / 2;
                out++;
            }
            if ((points & 1) == 1) {
                splits[out] = splits[points - 1];
                watts[out] = watts[points - 1];
                out++;
            }
            points = out;
            bucketSeconds *= 2;
        }

        private static int average(int a, int b) {
            if (a <= 0) {
                return b;
            }
            if (b <= 0) {
                return a;
            }
            return (a + b) / 2;
        }

        /** @return the session, or null if it is too short to log. */
        Session build() {
            if (!started) {
                return null;
            }
            int duration = Math.round(seconds);
            float avgWatts = duration > 0 ? (float) (wattSecondsTotal / duration) : 0f;
            float split = metres > 1f && duration > 0 ? duration / metres * 500f : 0f;
            int[] s = new int[points];
            int[] w = new int[points];
            System.arraycopy(splits, 0, s, 0, points);
            System.arraycopy(watts, 0, w, 0, points);
            Session session = new Session(startedAt, duration, metres, avgWatts, split, strokes,
                    peakWatts, joules, game, s, w, bucketSeconds);
            return isLoggable(session) ? session : null;
        }

        /** Builds and appends in one step. @return the stored session, or null. */
        Session commit(SessionLog log) {
            Session s = build();
            if (s != null && log != null && log.add(s)) {
                return s;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ formatting

    static String formatSplit(float secondsPer500) {
        if (secondsPer500 <= 0 || secondsPer500 > 900) {
            return "--:--";
        }
        int total = Math.round(secondsPer500);
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60);
    }

    static String formatDuration(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    static String formatMetres(float metres) {
        if (metres >= 1000f) {
            return String.format(Locale.US, "%.2f km", metres / 1000f);
        }
        return String.format(Locale.US, "%d m", Math.round(metres));
    }

    // ------------------------------------------------------------------ calendar

    /**
     * One reusable {@link Calendar} instead of one per call.
     *
     * <p>{@code Calendar.getInstance()} is not cheap - it resolves the default time zone and
     * locale - and these run once per session inside {@code totalsOf}, {@code listDay} and the
     * daily series, so a full log allocated a couple of thousand of them every time the history
     * screen recomputed. Guarded rather than thread-local because the lock is uncontended in
     * practice (the UI thread) and correctness matters more than the last nanosecond.
     */
    private static final Calendar SHARED = Calendar.getInstance();

    private static long zoneCheckedAt;

    /**
     * Keeps the shared calendar on the device's current time zone. Caller holds the lock.
     *
     * <p>A cached {@code Calendar} pins the zone it was built with, so a tablet moved (or corrected)
     * to another zone would keep drawing day boundaries in the old one until the app restarted.
     * Checked at most once a minute, which costs one small object a minute rather than one per
     * session per query. DST needs no help - the zone object handles it.
     */
    private static void syncZone() {
        long now = System.currentTimeMillis();
        if (now - zoneCheckedAt > 60000L || now < zoneCheckedAt) {
            zoneCheckedAt = now;
            java.util.TimeZone tz = java.util.TimeZone.getDefault();
            if (tz != null && !tz.getID().equals(SHARED.getTimeZone().getID())) {
                SHARED.setTimeZone(tz);
            }
        }
    }

    static long startOfDay(long millis) {
        synchronized (SHARED) {
            syncZone();
            SHARED.setTimeInMillis(millis);
            SHARED.set(Calendar.HOUR_OF_DAY, 0);
            SHARED.set(Calendar.MINUTE, 0);
            SHARED.set(Calendar.SECOND, 0);
            SHARED.set(Calendar.MILLISECOND, 0);
            return SHARED.getTimeInMillis();
        }
    }

    /** Monday-start, matching {@code Progress}. */
    static long startOfWeek(long millis) {
        long day = startOfDay(millis);
        synchronized (SHARED) {
            SHARED.setTimeInMillis(day);
            int dow = SHARED.get(Calendar.DAY_OF_WEEK); // Sunday = 1
            int back = (dow + 5) % 7; // Monday -> 0
            SHARED.add(Calendar.DAY_OF_YEAR, -back);
            return SHARED.getTimeInMillis();
        }
    }

    static long startOfMonth(long millis) {
        long day = startOfDay(millis);
        synchronized (SHARED) {
            SHARED.setTimeInMillis(day);
            SHARED.set(Calendar.DAY_OF_MONTH, 1);
            return SHARED.getTimeInMillis();
        }
    }

    /** Calendar arithmetic, not 86400000 - so a DST day is still one day. */
    static long addDays(long millis, int days) {
        synchronized (SHARED) {
            SHARED.setTimeInMillis(millis);
            SHARED.add(Calendar.DAY_OF_YEAR, days);
            return SHARED.getTimeInMillis();
        }
    }

    static long addMonths(long millis, int months) {
        synchronized (SHARED) {
            SHARED.setTimeInMillis(millis);
            SHARED.add(Calendar.MONTH, months);
            return SHARED.getTimeInMillis();
        }
    }

    // ------------------------------------------------------------------ storage

    private synchronized ArrayList<Session> load() {
        if (cache != null) {
            return cache;
        }
        ArrayList<Session> out = new ArrayList<>();
        String raw = null;
        try {
            raw = prefs.getString(KEY_SESSIONS, null);
        } catch (ClassCastException ignored) {
            // Something else wrote this key. Start clean rather than crash.
        }
        if (raw != null && raw.length() > 1) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    Session s = fromJson(array.optJSONObject(i));
                    if (s != null) {
                        out.add(s);
                    }
                }
            } catch (JSONException e) {
                // Corrupt store: keep whatever parsed, do not throw, and let the next write
                // replace it with something valid.
                out.clear();
            }
        }
        sortNewestFirst(out);
        cache = out;
        return cache;
    }

    private synchronized void save(ArrayList<Session> all) {
        cache = all;
        prefs.edit()
                .putInt(KEY_VERSION, VERSION)
                .putString(KEY_SESSIONS, toArray(all).toString())
                .apply();
    }

    private static final Comparator<Session> NEWEST_FIRST = new Comparator<Session>() {
        @Override
        public int compare(Session a, Session b) {
            return a.startedAt < b.startedAt ? 1 : (a.startedAt > b.startedAt ? -1 : 0);
        }
    };

    private static void sortNewestFirst(ArrayList<Session> all) {
        Collections.sort(all, NEWEST_FIRST);
    }

    private static JSONArray toArray(List<Session> all) {
        JSONArray array = new JSONArray();
        for (int i = 0; i < all.size(); i++) {
            JSONObject o = toJson(all.get(i));
            if (o != null) {
                array.put(o);
            }
        }
        return array;
    }

    /** Short keys: 2000 of these live in one preferences string. */
    private static JSONObject toJson(Session s) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", s.startedAt);
            o.put("d", s.durationSeconds);
            o.put("m", round1(s.metres));
            o.put("w", round1(s.avgWatts));
            o.put("sp", round1(s.avgSplit));
            o.put("st", s.strokes);
            o.put("pw", round1(s.peakWatts));
            o.put("j", Math.round(s.joules));
            o.put("g", s.game);
            o.put("bs", s.seriesBucketSeconds);
            o.put("ss", ints(s.splitSeries));
            o.put("ws", ints(s.wattsSeries));
            return o;
        } catch (JSONException e) {
            return null;
        }
    }

    private static Session fromJson(JSONObject o) {
        if (o == null) {
            return null;
        }
        long started = o.optLong("t", 0L);
        if (started < MIN_EPOCH) {
            // A row logged while the tablet's clock was unset, or a clock that has since been
            // corrected. Keep the row, pin it to now so it sorts and charts somewhere sane.
            started = nowSane();
        }
        int duration = o.optInt("d", 0);
        float metres = (float) o.optDouble("m", 0);
        float avgWatts = (float) o.optDouble("w", 0);
        float split = (float) o.optDouble("sp", 0);
        if (split <= 0 && metres > 1f && duration > 0) {
            split = duration / metres * 500f;
        }
        int strokes = o.optInt("st", 0);
        float peak = (float) o.optDouble("pw", 0);
        double joules = o.optDouble("j", 0);
        String game = o.optString("g", "");
        if ("null".equals(game)) {
            // org.json coerces a JSON null to the string "null" rather than the fallback.
            game = "";
        }
        int bucket = o.optInt("bs", SERIES_BUCKET_SECONDS);
        int[] splits = intArray(o.optJSONArray("ss"));
        int[] watts = intArray(o.optJSONArray("ws"));
        if (splits.length != watts.length) {
            int n = Math.min(splits.length, watts.length);
            splits = java.util.Arrays.copyOf(splits, n);
            watts = java.util.Arrays.copyOf(watts, n);
        }
        if (!sane(metres) || !sane(avgWatts) || !sane(split) || !sane(peak)
                || Double.isNaN(joules) || Double.isInfinite(joules)) {
            return null;
        }
        return new Session(started, duration, metres, avgWatts, split, strokes, peak, joules,
                game, splits, watts, bucket);
    }

    private static boolean sane(float f) {
        return isFinite(f) && f >= 0f;
    }

    /** {@code Float.isFinite} is API 24 and this app runs from API 23. */
    private static boolean isFinite(float f) {
        return !Float.isNaN(f) && !Float.isInfinite(f);
    }

    private static double round1(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            return 0;
        }
        return Math.round(f * 10f) / 10.0;
    }

    private static JSONArray ints(int[] values) {
        JSONArray a = new JSONArray();
        for (int i = 0; i < values.length; i++) {
            a.put(values[i]);
        }
        return a;
    }

    /** Capped: a corrupt or hostile import must not be able to ask for a huge array. */
    private static int[] intArray(JSONArray a) {
        if (a == null) {
            return EMPTY;
        }
        int n = Math.min(a.length(), MAX_SERIES_POINTS * 2);
        if (n <= 0) {
            return EMPTY;
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = a.optInt(i, 0);
        }
        return out;
    }

    /** A clock that has never been set reads 1970; anything before 2015 is not a real date. */
    private static long nowSane() {
        long now = System.currentTimeMillis();
        return now < MIN_EPOCH ? MIN_EPOCH : now;
    }
}
