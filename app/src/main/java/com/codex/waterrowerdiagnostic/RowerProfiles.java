package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Who is rowing. Profiles with no passwords - tap a name to switch, exactly as the rower asked.
 *
 * <p>The profile list lives in its own preferences file ({@code wake-profiles}) so it survives an
 * app update like every other preference, and so nothing about profiles is mixed into the records
 * file.
 *
 * <p><b>The first profile keeps the existing data.</b> {@link #prefsNameFor(String)} returns the
 * records file name that {@link PersonalBests} has always used ({@code wake-personal-bests}) for
 * the primary profile, and {@code wake-personal-bests-<id>} for every profile created later.
 * Nothing is migrated, renamed or copied: the rower who has been using the tablet keeps every
 * record, ghost and journey total they already had, and simply becomes profile one. The same rule
 * is available for any other per-profile store through {@link #prefsNameFor(String, String)} -
 * that is how {@link SessionLog} namespaces its history file.
 *
 * <p>There is always at least one profile. On first run a profile named "Rower" is created, so the
 * app works unchanged for someone who never opens the profile UI.
 */
final class RowerProfiles {

    /** The preferences file holding the profile list itself. */
    static final String PREFS = "wake-profiles";

    /**
     * The records file {@link PersonalBests} uses. Kept here (rather than imported) because the
     * primary profile must land on this exact name and it is a contract, not an implementation
     * detail.
     */
    static final String RECORDS_PREFS_BASE = "wake-personal-bests";

    private static final String KEY_IDS = "ids";
    private static final String KEY_PRIMARY = "primary";
    private static final String KEY_ACTIVE = "active";
    private static final String PREFIX_NAME = "name.";
    private static final String PREFIX_COLOUR = "colour.";
    private static final String PREFIX_CREATED = "created.";

    private static final int MAX_PROFILES = 12;
    private static final int MAX_NAME_CHARS = 24;
    /** Longest id {@link #uniqueId} can mint: a 16-char slug plus a millis suffix. */
    private static final int MAX_ID_CHARS = 32;

    /** Deck colours, handed out in order as profiles are created. */
    static final int[] PALETTE = {
            0xFF4FC3F7, // water blue
            0xFFFFB74D, // amber
            0xFF81C784, // green
            0xFFE57373, // red
            0xFFBA68C8, // purple
            0xFF4DD0E1, // teal
            0xFFFFF176, // yellow
            0xFFF06292, // pink
    };

    /** One rower. Immutable; re-read from {@link #get(String)} after any change. */
    static final class Profile {
        final String id;
        final String name;
        final int colour;
        final long createdAt;

        Profile(String id, String name, int colour, long createdAt) {
            this.id = id;
            this.name = name;
            this.colour = colour;
            this.createdAt = createdAt;
        }

        /** Two letters for a round badge. */
        String initials() {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty()) {
                return "?";
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase(Locale.US);
            }
            return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase(Locale.US);
        }

        @Override
        public String toString() {
            return "Profile{" + id + " " + name + "}";
        }
    }

    /** Told after any change to the list or to which profile is active. */
    interface Listener {
        /**
         * @param profiles this store, already updated
         * @param active   the profile now active (never null)
         */
        void onProfilesChanged(RowerProfiles profiles, Profile active);
    }

    private final Context appContext;
    private final SharedPreferences prefs;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    /** Parsed {@link #KEY_IDS}; null means "re-read". See {@link #idsRef()}. */
    private volatile List<String> idCache;

    /**
     * Held in a field on purpose: {@code SharedPreferences} keeps only a weak reference to a change
     * listener, so a local one would be collected and the cache would go stale.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsWatcher =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
                    if (key == null || KEY_IDS.equals(key)) {
                        idCache = null;
                    }
                }
            };

    RowerProfiles(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.prefs.registerOnSharedPreferenceChangeListener(prefsWatcher);
        ensureOne();
    }

    // ---------------------------------------------------------------- reading

    /** Every profile, in creation order. Never empty. */
    List<Profile> list() {
        List<String> ids = idsRef();
        List<Profile> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            Profile p = read(ids.get(i));
            if (p != null) {
                out.add(p);
            }
        }
        if (out.isEmpty()) {
            // The list named ids that carry no stored name - a half-written or hand-edited file.
            // Mint a fresh profile rather than hand back a list with a null in it.
            Profile fresh = read(createInternal("Rower", true));
            out.add(fresh != null ? fresh : new Profile("rower", "Rower", PALETTE[0], 0L));
        }
        return out;
    }

    int count() {
        return idsRef().size();
    }

    /** Null if no such profile. */
    Profile get(String id) {
        if (id == null) {
            return null;
        }
        return idsRef().contains(id) ? read(id) : null;
    }

    /** The profile whose data is in use. Never null. */
    Profile active() {
        String id = prefs.getString(KEY_ACTIVE, null);
        Profile p = get(id);
        if (p != null) {
            return p;
        }
        List<Profile> all = list();
        Profile first = all.get(0);
        prefs.edit().putString(KEY_ACTIVE, first.id).apply();
        return first;
    }

    String activeId() {
        return active().id;
    }

    /** The profile that owns the original, un-namespaced data files. Never null. */
    String primaryId() {
        String id = prefs.getString(KEY_PRIMARY, null);
        if (id != null && idsRef().contains(id)) {
            return id;
        }
        List<String> ids = idsRef();
        String first = ids.isEmpty() ? createInternal("Rower", true) : ids.get(0);
        prefs.edit().putString(KEY_PRIMARY, first).apply();
        return first;
    }

    boolean isPrimary(String id) {
        return id != null && id.equals(primaryId());
    }

    // ---------------------------------------------------------------- naming

    /**
     * The {@link PersonalBests} preferences file for a profile. The primary profile gets the
     * existing name, so its records are exactly where they have always been.
     */
    String prefsNameFor(String id) {
        return prefsNameFor(id, RECORDS_PREFS_BASE);
    }

    /**
     * The same rule for any other per-profile store: {@code base} unchanged for the primary
     * profile, {@code base + "-" + id} for the rest.
     */
    String prefsNameFor(String id, String base) {
        if (base == null || base.isEmpty()) {
            base = RECORDS_PREFS_BASE;
        }
        if (id == null || id.isEmpty() || isPrimary(id)) {
            return base;
        }
        return base + "-" + safeSuffix(id);
    }

    /** Convenience: a {@link PersonalBests} bound to a profile's own records file. */
    PersonalBests recordsFor(String id) {
        return new PersonalBests(new PrefsNameContext(appContext, prefsNameFor(id)));
    }

    /** Convenience: records for whoever is rowing now. */
    PersonalBests activeRecords() {
        return recordsFor(activeId());
    }

    // ---------------------------------------------------------------- writing

    /**
     * Creates a profile. The name is trimmed and capped; the id is a slug of it, made unique.
     *
     * @return the new profile, or the existing one if the list is already full ({@value
     *         #MAX_PROFILES}) - never null.
     */
    Profile create(String displayName) {
        if (count() >= MAX_PROFILES) {
            return active();
        }
        String id = createInternal(displayName, false);
        Profile created = read(id);
        fire();
        // Contract is "never null" - callers chain .id straight off this (createAndActivate does).
        return created != null ? created : active();
    }

    /** Creates a profile and switches to it. */
    Profile createAndActivate(String displayName) {
        Profile p = create(displayName);
        setActive(p.id);
        return p;
    }

    /** @return false if there is no such profile or the name is blank. */
    boolean rename(String id, String displayName) {
        String clean = cleanName(displayName);
        if (clean.isEmpty() || get(id) == null) {
            return false;
        }
        prefs.edit().putString(PREFIX_NAME + id, clean).apply();
        fire();
        return true;
    }

    boolean setColour(String id, int colour) {
        if (get(id) == null) {
            return false;
        }
        prefs.edit().putInt(PREFIX_COLOUR + id, colour).apply();
        fire();
        return true;
    }

    /**
     * Deletes a profile and clears its own data files.
     *
     * <p>Refused for the primary profile, whose files are the original un-namespaced ones - wiping
     * those would destroy the records this app has been collecting all along. Rename it instead.
     * Also refused when it is the only profile.
     *
     * @return true if it was deleted.
     */
    boolean delete(String id) {
        if (get(id) == null || isPrimary(id) || count() <= 1) {
            return false;
        }
        for (int i = 0; i < NAMESPACED_BASES.length; i++) {
            String name = prefsNameFor(id, NAMESPACED_BASES[i]);
            // Never touch a base name; only this profile's suffixed file.
            if (!name.equals(NAMESPACED_BASES[i])) {
                appContext.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply();
            }
        }
        List<String> ids = idsCopy();
        ids.remove(id);
        SharedPreferences.Editor e = prefs.edit();
        e.putString(KEY_IDS, join(ids));
        e.remove(PREFIX_NAME + id);
        e.remove(PREFIX_COLOUR + id);
        e.remove(PREFIX_CREATED + id);
        if (id.equals(prefs.getString(KEY_ACTIVE, null))) {
            e.putString(KEY_ACTIVE, ids.isEmpty() ? primaryId() : ids.get(0));
        }
        e.apply();
        // The change listener fires on the main looper, which is too late for the read that
        // fire() is about to do - drop the cache here as well.
        idCache = null;
        fire();
        return true;
    }

    /** @return false if there is no such profile. Firing the listener is how the UI reloads. */
    boolean setActive(String id) {
        if (get(id) == null) {
            return false;
        }
        if (id.equals(prefs.getString(KEY_ACTIVE, null))) {
            return true;
        }
        prefs.edit().putString(KEY_ACTIVE, id).apply();
        fire();
        return true;
    }

    // ---------------------------------------------------------------- listeners

    void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void fire() {
        if (listeners.isEmpty()) {
            return;
        }
        Profile now = active();
        for (Listener l : listeners) {
            l.onProfilesChanged(this, now);
        }
    }

    // ---------------------------------------------------------------- internals

    /** Stores whose files get cleared when a (non-primary) profile is deleted. */
    private static final String[] NAMESPACED_BASES = {
            RECORDS_PREFS_BASE,
            SessionLog.PREFS_BASE,
    };

    private void ensureOne() {
        if (idsRef().isEmpty()) {
            createInternal("Rower", true);
        } else {
            primaryId();
            active();
        }
    }

    private String createInternal(String displayName, boolean primary) {
        List<String> ids = idsCopy();
        String id = uniqueId(slug(displayName), ids);
        String clean = cleanName(displayName);
        if (clean.isEmpty()) {
            clean = "Rower";
        }
        ids.add(id);
        SharedPreferences.Editor e = prefs.edit();
        e.putString(KEY_IDS, join(ids));
        e.putString(PREFIX_NAME + id, clean);
        e.putInt(PREFIX_COLOUR + id, PALETTE[(ids.size() - 1) % PALETTE.length]);
        e.putLong(PREFIX_CREATED + id, System.currentTimeMillis());
        if (primary || !prefs.contains(KEY_PRIMARY)) {
            e.putString(KEY_PRIMARY, id);
        }
        if (!prefs.contains(KEY_ACTIVE)) {
            e.putString(KEY_ACTIVE, id);
        }
        e.apply();
        idCache = null;
        return id;
    }

    private Profile read(String id) {
        String name = prefs.getString(PREFIX_NAME + id, null);
        if (name == null) {
            return null;
        }
        int colour = prefs.getInt(PREFIX_COLOUR + id, PALETTE[0]);
        long created = prefs.getLong(PREFIX_CREATED + id, 0L);
        return new Profile(id, name, colour, created);
    }

    /**
     * The id list, parsed once and kept.
     *
     * <p>Read-only - callers that change it use {@link #idsCopy()}. It is cached because
     * {@link #prefsNameFor} reaches it through {@link #isPrimary}, so a screen that asks for
     * {@link #active()} or {@link #activeRecords()} while drawing would otherwise split a string
     * and build a list on every frame.
     */
    private List<String> idsRef() {
        List<String> cached = idCache;
        if (cached != null) {
            return cached;
        }
        String raw = prefs.getString(KEY_IDS, "");
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                if (!p.isEmpty() && !out.contains(p)) {
                    out.add(p);
                }
            }
        }
        idCache = out;
        return out;
    }

    /** A mutable snapshot, for the two methods that rewrite the list. */
    private List<String> idsCopy() {
        return new ArrayList<>(idsRef());
    }

    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static String cleanName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.length() > MAX_NAME_CHARS) {
            s = s.substring(0, MAX_NAME_CHARS).trim();
        }
        return s;
    }

    /** Lower-case, letters and digits only, dashes between words. Never empty. */
    static String slug(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastDash = false;
        for (int i = 0; i < s.length() && sb.length() < 16; i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastDash = false;
            } else if (!lastDash && sb.length() > 0) {
                sb.append('-');
                lastDash = true;
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? "rower" : sb.toString();
    }

    /**
     * The part of a preferences file name that comes from a profile id.
     *
     * <p>A preferences name becomes a file path ({@code shared_prefs/<name>.xml}), so an id that
     * contains a slash or a dot would write outside the app's preferences directory. Ids minted by
     * {@link #createInternal} are already safe, and for those this returns the id unchanged - which
     * matters, because the returned name is where a profile's records already live and it must not
     * drift between releases. Anything else (a hand-edited or corrupted profile list) is filtered to
     * the safe charset and given a hash of the original, so two different bad ids cannot land on the
     * same file.
     */
    private static String safeSuffix(String id) {
        boolean clean = id.length() <= MAX_ID_CHARS;
        for (int i = 0; clean && i < id.length(); i++) {
            char c = id.charAt(i);
            clean = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
        }
        if (clean) {
            return id;
        }
        return slug(id) + "-" + Integer.toHexString(id.hashCode());
    }

    private static String uniqueId(String base, List<String> taken) {
        if (!taken.contains(base)) {
            return base;
        }
        for (int n = 2; n < 100; n++) {
            String candidate = base + "-" + n;
            if (!taken.contains(candidate)) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis();
    }

    /**
     * Lets an existing {@link PersonalBests} (which picks its own file name) be pointed at a
     * profile's file without touching that class. Only {@code getSharedPreferences} is redirected.
     */
    private static final class PrefsNameContext extends android.content.ContextWrapper {
        private final String name;

        PrefsNameContext(Context base, String name) {
            super(base);
            this.name = name;
        }

        @Override
        public SharedPreferences getSharedPreferences(String ignored, int mode) {
            return getBaseContext().getSharedPreferences(name, mode);
        }
    }
}
