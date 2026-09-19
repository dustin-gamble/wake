package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * DAILY ROW: one short challenge a day, the same for everyone on that date, and a streak for doing it.
 *
 * <p>Six kinds rotate by date: most metres in four minutes, a 500 m time trial, ten powerful
 * strokes, three minutes held steady at your typical speed, a stroke-rate ladder, and a 500 m
 * negative split. Targets come from the rower's own profile, so the challenge is a stretch for
 * anyone. Completing it marks the day; consecutive days make the streak.
 *
 * <p>3.19.5 (the emulator screenshot was text and a grey bar on black): drifting sparkles, a big
 * progress ring with a medal that fills and glows, a stamp and confetti when the day is won, ticks
 * on completed calendar days and a flame on the streak.
 *
 * <p>Upgrades:
 * <ul>
 * <li><b>Sunday boss.</b> On Sundays the day's challenge is harder and THE KRAKEN sits top right
 * with a health bar. It takes hits while you are on pace and swings at you while you are not.
 * Beating it earns a boss medal and a streak saver.</li>
 * <li><b>Month view.</b> The MONTH chip swaps the 28-day strip for a calendar month with a medal on
 * every day won - bronze, silver or gold by the margin, purple for a boss, a blue shield for a day
 * a streak saver covered. Arrows step back through earlier months.</li>
 * <li><b>Rate ladder and negative split.</b> Two new challenge types.</li>
 * <li><b>Streak saver.</b> One token, held at most one at a time, earned by beating a Sunday boss
 * or by reaching a multiple of seven days. If you miss exactly one day it is spent automatically
 * the next time you open Daily Row and the missed day is shielded.</li>
 * <li><b>Result card.</b> After a challenge, RESULT CARD draws a shareable summary over the screen;
 * the camera button saves it.</li>
 * </ul>
 */
final class DailyRowGame extends GameView {

    enum Challenge {
        DISTANCE_4MIN("MOST METRES IN 4 MINUTES"),
        TRIAL_500("500 m TIME TRIAL"),
        POWER_10("10 POWERFUL STROKES"),
        STEADY_3MIN("3 MINUTES STEADY"),
        RATE_LADDER("STROKE RATE LADDER"),
        NEG_SPLIT("500 m NEGATIVE SPLIT");

        final String title;

        Challenge(String title) {
            this.title = title;
        }
    }

    private enum Phase { READY, ACTIVE, DONE }

    /** Medal tiers stored per day in {@code daily.medals}. */
    private static final int BRONZE = 1;
    private static final int SILVER = 2;
    private static final int GOLD = 3;
    private static final int BOSS_MEDAL = 4;
    /** Seconds on each rung of the rate ladder; the first few of each are not scored. */
    private static final double RUNG_SECONDS = 45;
    private static final double RUNG_GRACE = 6;
    private static final int BOSS_COLOR = 0xFFB06CFF;
    private static final int[] CONFETTI = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};
    private static final String[] WEEKDAYS = {"M", "T", "W", "T", "F", "S", "S"};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final java.util.TreeSet<Long> days = new java.util.TreeSet<>();
    /** Days covered by a streak saver: they count for the streak but are not wins. */
    private final java.util.TreeSet<Long> saved = new java.util.TreeSet<>();
    private final java.util.TreeMap<Long, Integer> medals = new java.util.TreeMap<>();

    private Challenge challenge;
    private long today;
    private boolean boss;
    private Phase phase = Phase.READY;
    private double startSeconds;
    private double startMeters;
    private double target;
    private double result;
    private boolean success;
    private int strokes;
    private double powerSum;
    private double lastStrokeW;
    private double steadySeconds;
    private PulseMeter.Stroke lastSeen;
    private final Fx.Particles fx = new Fx.Particles();
    private final android.graphics.Path shape = new android.graphics.Path();
    private double doneAt = -10;
    private float ringShown;

    // Rate ladder.
    private final int[] ladderRates = new int[5];
    private int rungs;
    private double ladderIn;
    private double ladderCounted;
    private int lastRung = -1;
    private double rungFlashAt = -10;

    // Negative split: time at every 10 m of the first half, the ghost you race in the second.
    private final double[] firstHalfAt = new double[26];
    private int nextMark;
    private double halfTime = -1;
    private double negCap;
    private double negTotal;

    // Live "something at stake": are you on the target's pace right now?
    private boolean onTrack;
    private String paceText = "";

    // Boss.
    private float bossHp = 1f;
    private float bossHpShown = 1f;
    private float flinch;
    private double hitTimer;
    private float attack;

    // Streak saver.
    private boolean token;
    private String banner = "";
    private int bannerColor = BLUE;
    private double bannerAt = -10;

    // Month view and result card.
    private boolean monthView;
    private int monthOffset;
    private long monthFirst;
    private int monthLen;
    private int monthLead;
    private String monthTitle = "";
    private final int[] monthTier = new int[31];
    private boolean showCard;
    private int todayTier;
    private String dateText = "";
    private String weekdayText = "";
    private final RectF monthChip = new RectF();
    private final RectF cardChip = new RectF();
    private final RectF prevArrow = new RectF();
    private final RectF nextArrow = new RectF();

    DailyRowGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        today = RegattaGame.today();
        long mix = today * 2654435761L;
        challenge = Challenge.values()[(int) (((mix >>> 16) & 0x7fffffff) % Challenge.values().length)];
        // Day 0 (1 Jan 1970) was a Thursday, so (day + 4) % 7 is 0 on a Sunday.
        boss = (today + 4) % 7 == 0;
        readDays(days, "daily.days");
        readDays(saved, "daily.saved");
        medals.clear();
        String m = bests.getString("daily.medals");
        if (m != null) {
            for (String s : m.split(",")) {
                int colon = s.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                try {
                    medals.put(Long.parseLong(s.substring(0, colon)), Integer.parseInt(s.substring(colon + 1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
        token = "1".equals(bests.getString("daily.token"));
        banner = "";
        bannerAt = -10;
        spendTokenIfNeeded();

        double speed = profile.typicalSpeed();
        switch (challenge) {
            case DISTANCE_4MIN:
                target = speed * 240 * (boss ? 1.06 : 1.03);
                break;
            case TRIAL_500:
                target = 500 / speed * (boss ? 0.95 : 0.97);
                break;
            case POWER_10:
                target = profile.typicalWatts() * (boss ? 1.4 : 1.25);
                break;
            case RATE_LADDER: {
                int base = (int) Math.round(profile.typicalRate());
                int[] offsets = boss ? new int[] {-4, -2, 0, 2, 4} : new int[] {-4, -2, 0, 2};
                rungs = offsets.length;
                for (int i = 0; i < rungs; i++) {
                    ladderRates[i] = Math.max(16, base + offsets[i]);
                }
                target = boss ? 65 : 55;
                break;
            }
            case NEG_SPLIT:
                // Seconds the second 250 m must beat the first by, with the whole 500 m inside a cap
                // so a crawl through the first half cannot buy the win.
                target = boss ? 3 : 0.5;
                negCap = 500 / speed * (boss ? 1.0 : 1.04);
                break;
            default:
                target = boss ? 80 : 70;
                break;
        }
        phase = Phase.READY;
        strokes = 0;
        powerSum = 0;
        lastStrokeW = 0;
        steadySeconds = 0;
        result = 0;
        success = false;
        ladderIn = 0;
        ladderCounted = 0;
        lastRung = -1;
        nextMark = 0;
        halfTime = -1;
        negTotal = 0;
        bossHp = 1f;
        bossHpShown = 1f;
        flinch = 0;
        attack = 0;
        hitTimer = 0;
        showCard = false;
        paceText = "";
        onTrack = false;
        Integer t = medals.get(today);
        todayTier = t != null ? t : 0;

        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US);
        f.setTimeZone(utc);
        dateText = f.format(new java.util.Date(today * 86400000L));
        java.text.SimpleDateFormat wf = new java.text.SimpleDateFormat("EEEE", java.util.Locale.US);
        wf.setTimeZone(utc);
        weekdayText = wf.format(new java.util.Date(today * 86400000L)).toUpperCase(java.util.Locale.US);
        buildMonth();
    }

    private void readDays(java.util.TreeSet<Long> into, String key) {
        into.clear();
        String saved = bests.getString(key);
        if (saved != null) {
            for (String s : saved.split(",")) {
                try {
                    into.add(Long.parseLong(s));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
        }
    }

    private void writeDays(java.util.TreeSet<Long> from, String key) {
        while (from.size() > 400) {
            from.pollFirst();
        }
        StringBuilder sb = new StringBuilder();
        for (long d : from) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(d);
        }
        bests.putString(key, sb.toString());
    }

    private void writeMedals() {
        while (medals.size() > 400) {
            medals.pollFirstEntry();
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<Long, Integer> e : medals.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        bests.putString("daily.medals", sb.toString());
    }

    private boolean counts(long day) {
        return days.contains(day) || saved.contains(day);
    }

    /**
     * A held saver covers a single missed day: yesterday empty, the day before it done. It is spent
     * on opening Daily Row, so the rower sees the save happen rather than finding it later.
     */
    private void spendTokenIfNeeded() {
        if (!token || counts(today - 1) || !counts(today - 2)) {
            return;
        }
        saved.add(today - 1);
        writeDays(saved, "daily.saved");
        token = false;
        bests.putString("daily.token", "0");
        banner = "STREAK SAVED  ·  your saver covered yesterday";
        bannerColor = BLUE;
        bannerAt = 0;
    }

    /** Recomputes the month grid; called on entry and when the arrows change month, never per frame. */
    private void buildMonth() {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"), java.util.Locale.US);
        cal.setTimeInMillis(today * 86400000L);
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1);
        cal.add(java.util.Calendar.MONTH, monthOffset);
        monthFirst = cal.getTimeInMillis() / 86400000L;
        monthLen = cal.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        // Monday-first, as the weekly goal is: Sunday, the boss day, is the last column.
        monthLead = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7;
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        monthTitle = f.format(cal.getTime()).toUpperCase(java.util.Locale.US);
        for (int i = 0; i < monthTier.length; i++) {
            long day = monthFirst + i;
            Integer t = medals.get(day);
            monthTier[i] = i >= monthLen ? 0 : t != null ? t : days.contains(day) ? BRONZE : saved.contains(day) ? -1 : 0;
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f && !showCard) {
            phase = Phase.ACTIVE;
            startSeconds = sessionSeconds;
            startMeters = sessionMeters;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastSeen) {
            lastSeen = stroke;
            if (phase == Phase.ACTIVE && challenge == Challenge.POWER_10) {
                strokes++;
                lastStrokeW = !Double.isNaN(stroke.averagePowerW) ? stroke.averagePowerW : s.watts;
                powerSum += lastStrokeW;
                if (boss && lastStrokeW >= target) {
                    flinch = 1f;
                    hitTimer = 0;
                }
                if (strokes >= 10) {
                    finish(powerSum / strokes);
                }
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(e);
        }
        float x = e.getX();
        float y = e.getY();
        if (showCard) {
            showCard = false;
            return true;
        }
        if (monthChip.contains(x, y)) {
            monthView = !monthView;
            monthOffset = 0;
            buildMonth();
            return true;
        }
        if (monthView && prevArrow.contains(x, y)) {
            monthOffset = Math.max(-12, monthOffset - 1);
            buildMonth();
            return true;
        }
        if (monthView && nextArrow.contains(x, y) && monthOffset < 0) {
            monthOffset++;
            buildMonth();
            return true;
        }
        if (phase == Phase.DONE && cardChip.contains(x, y)) {
            showCard = true;
            return true;
        }
        if (phase == Phase.DONE) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private void finish(double value) {
        phase = Phase.DONE;
        result = value;
        doneAt = sessionSeconds;
        if (challenge == Challenge.TRIAL_500) {
            success = value <= target;
        } else if (challenge == Challenge.NEG_SPLIT) {
            success = value >= target && negTotal <= negCap;
        } else {
            success = value >= target;
        }
        if (boss) {
            bossHp = success ? 0f : Math.max(0.05f, 1f - (float) Math.max(0, Math.min(0.99, scoreRatio())));
        }
        String key = "daily.best." + challenge.name().toLowerCase(java.util.Locale.US);
        if (challenge == Challenge.TRIAL_500) {
            bests.recordLowest(key, (float) value);
        } else {
            bests.recordHighest(key, (float) value);
        }
        if (!success) {
            return;
        }
        boolean firstWin = !days.contains(today);
        if (firstWin) {
            days.add(today);
            saved.remove(today);
            writeDays(days, "daily.days");
            bests.recordHighest("daily.streak", streak());
        }
        int tier = medalTier();
        Integer old = medals.get(today);
        if (old == null || tier > old) {
            if (tier == BOSS_MEDAL && (old == null || old != BOSS_MEDAL)) {
                bests.putFloat("daily.bosses", bests.get("daily.bosses", 0f) + 1f);
            }
            medals.put(today, tier);
            writeMedals();
        }
        todayTier = Math.max(todayTier, tier);
        if (firstWin && !token && (boss || streak() % 7 == 0)) {
            token = true;
            bests.putString("daily.token", "1");
            banner = boss ? "+1 STREAK SAVER  ·  the Kraken is beaten" : "+1 STREAK SAVER  ·  " + streak() + " days in a row";
            bannerColor = BLUE;
            bannerAt = sessionSeconds;
        }
        buildMonth();
    }

    private int medalTier() {
        if (boss) {
            return BOSS_MEDAL;
        }
        if (challenge == Challenge.NEG_SPLIT) {
            double margin = result - target;
            return margin >= 4 ? GOLD : margin >= 2 ? SILVER : BRONZE;
        }
        double ratio = scoreRatio();
        return ratio >= 1.10 ? GOLD : ratio >= 1.05 ? SILVER : BRONZE;
    }

    /** How far the result went toward the target: 1 is exactly on it. */
    private double scoreRatio() {
        switch (challenge) {
            case TRIAL_500:
                return target / Math.max(1e-6, result);
            case NEG_SPLIT:
                // Scored as seconds gained against a 4 s scale, so a small miss still shows progress.
                return Math.max(0, (result + 4) / (target + 4));
            default:
                return result / Math.max(1e-6, target);
        }
    }

    /** Consecutive completed (or saved) days ending today, or yesterday if today is not done yet. */
    private int streak() {
        long d = counts(today) ? today : today - 1;
        int n = 0;
        while (counts(d)) {
            n++;
            d--;
        }
        return n;
    }

    private double rateNow() {
        if (status == null) {
            return 0;
        }
        return status.strokeRatePrecise > 0 ? status.strokeRatePrecise : status.strokeRate;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(boss ? 0xFF100A1C : 0xFF08111C);
        c.drawRect(0, 0, w, h, paint);
        // Slow sparkles drifting up the background.
        int sparkle = boss ? 0xB06CFF : 0x35D0BA;
        for (int i = 0; i < 36; i++) {
            float sx = ((i * 0.137f + 0.05f) % 1f) * w;
            float sy = h - (float) (((sessionSeconds * dp(12f + i % 5 * 4f)) + i * dp(97f)) % (h + dp(40f)));
            float tw = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 2 + i);
            paint.setColor(((int) (30 + 60 * tw) << 24) | sparkle);
            c.drawCircle(sx, sy, dp(1.5f) + tw * dp(1.5f), paint);
        }
        fx.step(dt, dp(260f));
        double elapsed = phase == Phase.ACTIVE ? sessionSeconds - startSeconds : 0;
        double metres = phase == Phase.ACTIVE ? sessionMeters - startMeters : 0;
        float progress = 0f;
        float bossProgress = 0f;
        String live = "";
        paceText = "";
        if (phase == Phase.ACTIVE) {
            switch (challenge) {
                case DISTANCE_4MIN: {
                    progress = (float) Math.min(1, elapsed / 240);
                    bossProgress = (float) (metres / target);
                    double ahead = metres - target * elapsed / 240;
                    onTrack = ahead >= 0;
                    paceText = (onTrack ? "AHEAD OF TARGET  +" : "BEHIND TARGET  ") + Math.round(ahead) + " m";
                    live = Math.round(metres) + " m  ·  " + clock(Math.max(0, 240 - elapsed)) + " left";
                    if (elapsed >= 240) {
                        finish(metres);
                    }
                    break;
                }
                case TRIAL_500: {
                    progress = (float) Math.min(1, metres / 500);
                    bossProgress = progress;
                    double ahead = metres - 500 * elapsed / target;
                    onTrack = ahead >= 0;
                    paceText = (onTrack ? "AHEAD OF TARGET  +" : "BEHIND TARGET  ") + Math.round(ahead) + " m";
                    live = clock(elapsed) + "  ·  " + Math.round(metres) + " of 500 m";
                    if (metres >= 500) {
                        finish(elapsed);
                    }
                    break;
                }
                case POWER_10:
                    progress = strokes / 10f;
                    bossProgress = (float) (progress * Math.min(1, strokes > 0 ? powerSum / strokes / target : 0));
                    onTrack = strokes > 0 && lastStrokeW >= target;
                    paceText = strokes == 0 ? "PULL HARD  ·  " + Math.round(target) + " W A STROKE"
                            : "LAST STROKE " + Math.round(lastStrokeW) + " W" + (onTrack ? "  ·  ON TARGET" : "  ·  PULL HARDER");
                    live = strokes + " of 10 strokes  ·  " + (strokes > 0 ? Math.round(powerSum / strokes) : 0) + " W average";
                    break;
                case RATE_LADDER: {
                    double total = rungs * RUNG_SECONDS;
                    int rung = (int) Math.min(rungs - 1, elapsed / RUNG_SECONDS);
                    if (rung != lastRung) {
                        lastRung = rung;
                        rungFlashAt = sessionSeconds;
                    }
                    double inRung = elapsed - rung * RUNG_SECONDS;
                    double rate = rateNow();
                    int want = ladderRates[rung];
                    boolean inBand = Math.abs(rate - want) <= 1.5;
                    if (inRung >= RUNG_GRACE) {
                        ladderCounted += dt;
                        if (inBand) {
                            ladderIn += dt;
                        }
                    }
                    onTrack = inBand;
                    paceText = inBand ? "IN BAND  ·  " + String.format(java.util.Locale.US, "%.1f", rate) + " spm"
                            : (rate < want ? "TOO SLOW  ·  " : "TOO FAST  ·  ")
                            + String.format(java.util.Locale.US, "%.1f", rate) + " spm, want " + want;
                    progress = (float) Math.min(1, elapsed / total);
                    bossProgress = (float) (ladderIn / (target / 100.0 * Math.max(1, total - rungs * RUNG_GRACE)));
                    live = "RUNG " + (rung + 1) + " of " + rungs + "  ·  hold " + want + " spm  ·  "
                            + clock(Math.max(0, RUNG_SECONDS - inRung)) + "  ·  "
                            + Math.round(100 * ladderIn / Math.max(1, ladderCounted)) + "% in band";
                    if (elapsed >= total) {
                        finish(100 * ladderIn / Math.max(1, ladderCounted));
                    }
                    break;
                }
                case NEG_SPLIT: {
                    progress = (float) Math.min(1, metres / 500);
                    bossProgress = progress;
                    while (nextMark <= 25 && metres >= nextMark * 10) {
                        firstHalfAt[nextMark] = elapsed;
                        nextMark++;
                    }
                    if (halfTime < 0 && metres >= 250) {
                        halfTime = firstHalfAt[25];
                    }
                    if (halfTime < 0) {
                        double pace500 = elapsed * 500 / Math.max(1, metres);
                        onTrack = metres < 20 || pace500 <= negCap;
                        paceText = "FIRST HALF  ·  bank a pace you can beat  ·  " + clock(elapsed);
                        live = Math.round(metres) + " of 250 m  ·  first half";
                    } else {
                        double m2 = Math.min(250, metres - 250);
                        double e2 = elapsed - halfTime;
                        double ghost = ghostTimeAt(m2);
                        double ahead = ghost - e2;
                        onTrack = ahead >= 0;
                        paceText = (onTrack ? "BEATING YOUR FIRST HALF  +" : "BEHIND YOUR FIRST HALF  ")
                                + String.format(java.util.Locale.US, "%.1f", ahead) + " s";
                        live = "first half " + clock(halfTime) + "  ·  " + Math.round(metres) + " of 500 m";
                    }
                    if (metres >= 500) {
                        negTotal = elapsed;
                        finish(2 * halfTime - elapsed);
                    }
                    break;
                }
                default: {
                    float speed = boat.value();
                    double typical = profile.typicalSpeed();
                    boolean inBand = Math.abs(speed - typical) <= typical * (boss ? 0.045 : 0.06);
                    if (inBand) {
                        steadySeconds += dt;
                    }
                    onTrack = inBand;
                    paceText = inBand ? "IN THE BAND" : speed < typical ? "TOO SLOW  ·  lift it" : "TOO FAST  ·  ease off";
                    progress = (float) Math.min(1, elapsed / 180);
                    bossProgress = (float) (steadySeconds / (target / 100.0 * 180));
                    live = Math.round(100 * steadySeconds / Math.max(1, elapsed)) + "% steady  ·  hold "
                            + pace(typical) + " /500  ·  " + clock(Math.max(0, 180 - elapsed)) + " left";
                    if (elapsed >= 180) {
                        finish(100 * steadySeconds / 180);
                    }
                    break;
                }
            }
        }
        if (boss && phase == Phase.ACTIVE) {
            bossHp = 1f - Math.max(0f, Math.min(1f, bossProgress));
        } else if (boss && phase == Phase.READY) {
            bossHp = 1f;
        }

        float cx = w / 2f;
        label(c, boss ? "SUNDAY BOSS  ·  THE KRAKEN" : "TODAY'S DAILY ROW", cx, dp(40f), 11f, boss ? BOSS_COLOR : FAINT, Paint.Align.CENTER);
        bold(c, challenge.title, cx, dp(78f), 30f, boss ? BOSS_COLOR : ACCENT, Paint.Align.CENTER);
        label(c, "TARGET  " + targetLine(), cx, dp(102f), 13f, TEXT, Paint.Align.CENTER);

        float barY = h * 0.34f;
        // A progress ring around a medal, left of the live readout.
        if (phase == Phase.DONE) {
            // A win fills the ring; a miss shows how close it came - a miss must not look like a win.
            progress = success ? 1f : (float) Math.max(0, Math.min(0.99, scoreRatio()));
        }
        ringShown += (progress - ringShown) * Math.min(1f, dt * 4f);
        float rcx = w * 0.17f;
        float rcy = barY + dp(20f);
        float rr = Math.min(dp(70f), h * 0.12f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(12f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x33FFFFFF);
        c.drawCircle(rcx, rcy, rr, paint);
        paint.setColor(phase == Phase.DONE && !success ? WARN : ACCENT);
        c.drawArc(rcx - rr, rcy - rr, rcx + rr, rcy + rr, -90, 360 * ringShown, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        boolean won = phase == Phase.DONE && success;
        // The halo follows the outcome too: a teal glow around a dulled medal read as a win.
        int haloTint = (phase == Phase.DONE && !success ? WARN : ACCENT) & 0x00FFFFFF;
        Fx.glow(c, rcx, rcy, rr * (1.2f + 0.3f * ringShown),
                won ? 0x88F5C518 : ((int) (40 + 60 * ringShown) << 24) | haloTint);
        boolean lost = phase == Phase.DONE && !success;
        paint.setColor(won ? 0xFFF5C518 : lost ? 0xFF4A3F36 : blend(0xFF2A3648, 0xFFB8890B, ringShown));
        c.drawCircle(rcx, rcy, rr * 0.55f, paint);
        paint.setColor(won ? 0xFFFFE28A : lost ? 0xFF7A6A58 : blend(0xFF3A4658, 0xFFF5C518, ringShown));
        star(rcx, rcy, rr * 0.38f, rr * 0.17f);
        c.drawPath(shape, paint);
        bold(c, Math.round(ringShown * 100) + "%", rcx, rcy + rr + dp(26f), 14f, TEXT, Paint.Align.CENTER);
        // The flat bar stays for the live readout, now with a moving sheen.
        float barL = w * 0.30f;
        float barR = w * 0.86f;
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(barL, barY, barR, barY + dp(18f), dp(9f), dp(9f), paint);
        // Same rule as the ring: a miss must not be drawn in the colour of a win.
        paint.setColor(lost ? WARN : ACCENT);
        c.drawRoundRect(barL, barY, barL + (barR - barL) * progress, barY + dp(18f), dp(9f), dp(9f), paint);
        if (progress > 0.02f) {
            float sheen = barL + (barR - barL) * progress * (float) ((sessionSeconds * 0.6) % 1.0);
            paint.setColor(0x55FFFFFF);
            c.drawRoundRect(sheen - dp(20f), barY + dp(3f), sheen + dp(20f), barY + dp(8f), dp(3f), dp(3f), paint);
        }
        drawBarMarks(c, barL, barR, barY, elapsed);
        if (boss) {
            drawBoss(c, w, h, dt);
        }
        if (won) {
            if (sessionSeconds - doneAt < 3 && Math.random() < 0.8) {
                fx.spawn((float) Math.random() * w, -dp(10f), (float) (Math.random() - 0.5) * dp(80f),
                        dp(30f), 3f, dp(3.5f), CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
            }
            // A rubber stamp that thumps down and settles.
            float t = (float) Math.min(1.0, (sessionSeconds - doneAt) * 4);
            float scale = 2.2f - 1.2f * t;
            int stamp = boss ? BOSS_COLOR & 0x00FFFFFF : 0xF5C518;
            c.save();
            c.rotate(-8f, w * 0.58f, barY - dp(40f));
            c.scale(scale, scale, w * 0.58f, barY - dp(40f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4f));
            paint.setColor(((int) (255 * t) << 24) | stamp);
            c.drawRoundRect(w * 0.58f - dp(90f), barY - dp(68f), w * 0.58f + dp(90f), barY - dp(14f), dp(8f), dp(8f), paint);
            paint.setStyle(Paint.Style.FILL);
            bold(c, boss ? "BOSS DOWN" : "DAY WON", w * 0.58f, barY - dp(30f), 26f, ((int) (255 * t) << 24) | stamp, Paint.Align.CENTER);
            c.restore();
        }
        fx.draw(c);
        String big = phase == Phase.READY ? "ROW TO START" : phase == Phase.DONE
                ? (success ? "DONE  ·  " : "NOT TODAY  ·  ") + resultText() : live;
        bold(c, big, cx, barY + dp(58f), phase == Phase.DONE ? 26f : 20f,
                phase == Phase.DONE ? (success ? ACCENT : WARN) : TEXT, Paint.Align.CENTER);
        cardChip.setEmpty();
        if (phase == Phase.DONE) {
            label(c, "tap to try again", cx, barY + dp(80f), 10f, FAINT, Paint.Align.CENTER);
            cardChip.set(cx - dp(80f), barY + dp(92f), cx + dp(80f), barY + dp(124f));
            chip(c, cardChip, "RESULT CARD", success ? ACCENT : WARN);
        } else if (phase == Phase.ACTIVE && paceText.length() > 0) {
            // The stake in the next ten seconds: are you on the target's pace right now?
            float pw = textWidth(paceText, 13f) + dp(28f);
            int col = onTrack ? ACCENT : BAD;
            paint.setColor((0x33 << 24) | (col & 0x00FFFFFF));
            c.drawRoundRect(cx - pw / 2, barY + dp(72f), cx + pw / 2, barY + dp(100f), dp(14f), dp(14f), paint);
            bold(c, paceText, cx, barY + dp(91f), 13f, col, Paint.Align.CENTER);
        }

        if (monthView) {
            drawMonth(c, w, h);
        } else {
            int cells = 28;
            float cell = Math.min(dp(40f), (w * 0.8f) / cells);
            float calL = cx - cell * cells / 2f;
            float calY = h * 0.66f;
            drawStrip(c, calL, calY, cell, cells, true);
            label(c, "LAST 28 DAYS", calL, calY - dp(8f), 9f, FAINT, Paint.Align.LEFT);
            label(c, "TODAY", calL + cells * cell, calY - dp(8f), 9f, FAINT, Paint.Align.RIGHT);
            drawStreak(c, cx, calY + cell + dp(40f), w / 2f - dp(150f), calY + cell + dp(30f));
        }
        monthChip.set(w - dp(128f), h * 0.58f - dp(34f), w - dp(16f), h * 0.58f - dp(4f));
        chip(c, monthChip, monthView ? "28 DAYS" : "MONTH", DIM);

        if (sessionSeconds - bannerAt < 5 && banner.length() > 0) {
            float a = (float) Math.min(1, Math.min((sessionSeconds - bannerAt) * 4, (5 - (sessionSeconds - bannerAt)) * 2));
            float by = dp(120f) + dp(10f) * (1 - a);
            // Measured, not estimated from the character count, so the shield never sits on the text.
            float bw = textWidth(banner, 15f) + dp(72f);
            paint.setColor(((int) (220 * a) << 24) | 0x0E1A2A);
            c.drawRoundRect(cx - bw / 2, by, cx + bw / 2, by + dp(40f), dp(20f), dp(20f), paint);
            drawShield(c, cx - bw / 2 + dp(26f), by + dp(20f), dp(12f), ((int) (255 * a) << 24) | (bannerColor & 0x00FFFFFF));
            bold(c, banner, cx - bw / 2 + dp(48f), by + dp(26f), 15f, ((int) (255 * a) << 24) | (bannerColor & 0x00FFFFFF), Paint.Align.LEFT);
        }

        if (showCard) {
            drawCard(c, w, h);
        }
    }

    /** Rate-ladder rungs and the negative split's ghost, drawn on the progress bar. */
    private void drawBarMarks(Canvas c, float barL, float barR, float barY, double elapsed) {
        if (challenge == Challenge.RATE_LADDER) {
            float seg = (barR - barL) / rungs;
            for (int i = 0; i < rungs; i++) {
                float x0 = barL + seg * i;
                boolean current = phase == Phase.ACTIVE && i == lastRung;
                if (i > 0) {
                    paint.setColor(0x88000000);
                    c.drawRect(x0 - dp(1.5f), barY - dp(4f), x0 + dp(1.5f), barY + dp(22f), paint);
                }
                // Each rung steps up, like the ladder it is.
                float step = dp(6f) * i;
                float flash = current ? (float) Math.max(0, 1 - (sessionSeconds - rungFlashAt)) : 0f;
                bold(c, ladderRates[i] + " spm", x0 + seg / 2, barY - dp(8f) - step - flash * dp(8f),
                        current ? 16f + flash * 6f : 12f, current ? (onTrack ? ACCENT : BAD) : DIM, Paint.Align.CENTER);
            }
            if (phase == Phase.ACTIVE && lastRung >= 0) {
                // Your rate against the rung: a dot riding above the bar, left when slow, right when fast.
                double off = Math.max(-3, Math.min(3, rateNow() - ladderRates[lastRung]));
                float mid = barL + seg * (lastRung + 0.5f);
                float dx = (float) (off / 3) * seg * 0.45f;
                paint.setColor(onTrack ? ACCENT : BAD);
                c.drawCircle(mid + dx, barY + dp(32f), dp(6f), paint);
                paint.setColor(0x55FFFFFF);
                c.drawRect(mid - seg * 0.45f * 0.5f, barY + dp(31f), mid + seg * 0.45f * 0.5f, barY + dp(33f), paint);
            }
        } else if (challenge == Challenge.NEG_SPLIT) {
            float half = (barL + barR) / 2;
            paint.setColor(0xAAFFFFFF);
            c.drawRect(half - dp(1.5f), barY - dp(6f), half + dp(1.5f), barY + dp(24f), paint);
            label(c, "250 m", half, barY - dp(10f), 10f, DIM, Paint.Align.CENTER);
            if (phase == Phase.ACTIVE && halfTime >= 0) {
                // The first half, replayed as a ghost you have to pass.
                double gm = 250 + ghostMetresAt(elapsed - halfTime);
                float gx = barL + (barR - barL) * (float) Math.min(1, gm / 500);
                paint.setColor(0xCCB0C4DE);
                shape.rewind();
                shape.moveTo(gx, barY - dp(2f));
                shape.lineTo(gx - dp(8f), barY - dp(16f));
                shape.lineTo(gx + dp(8f), barY - dp(16f));
                shape.close();
                c.drawPath(shape, paint);
                label(c, "1st half", gx, barY - dp(20f), 9f, 0xFFB0C4DE, Paint.Align.CENTER);
            }
        }
    }

    /** Time the first half took to reach {@code m} metres, interpolated between 10 m marks. */
    private double ghostTimeAt(double m) {
        double i = Math.max(0, Math.min(25, m / 10));
        int lo = (int) Math.floor(i);
        int hi = Math.min(25, lo + 1);
        return firstHalfAt[lo] + (firstHalfAt[hi] - firstHalfAt[lo]) * (i - lo);
    }

    /** Metres the first half had covered after {@code t} seconds. */
    private double ghostMetresAt(double t) {
        if (t <= 0) {
            return 0;
        }
        for (int i = 1; i <= 25; i++) {
            if (firstHalfAt[i] >= t) {
                double span = Math.max(1e-6, firstHalfAt[i] - firstHalfAt[i - 1]);
                return (i - 1 + (t - firstHalfAt[i - 1]) / span) * 10;
            }
        }
        return 250;
    }

    /** THE KRAKEN: loses health while you are on pace, swings a tentacle while you are not. */
    private void drawBoss(Canvas c, float w, float h, float dt) {
        float bx = w * 0.87f;
        float r = Math.min(dp(56f), h * 0.085f);
        float by = dp(150f);
        bossHpShown += (bossHp - bossHpShown) * Math.min(1f, dt * 3f);
        boolean ko = phase == Phase.DONE && success;
        boolean gloat = phase == Phase.DONE && !success;
        if (phase == Phase.ACTIVE) {
            if (onTrack) {
                hitTimer += dt;
                attack = Math.max(0f, attack - dt * 2f);
                if (hitTimer >= 1.2) {
                    hitTimer = 0;
                    flinch = 1f;
                }
            } else {
                hitTimer = 0;
                attack = Math.min(1f, attack + dt * 1.5f);
            }
        } else {
            attack = Math.max(0f, attack - dt * 2f);
        }
        if (flinch >= 0.999f) {
            fx.burst(bx, by, 10, dp(160f), 0.6f, dp(3f), 0xFFFFE28A, false);
        }
        flinch = Math.max(0f, flinch - dt * 3f);
        float t = (float) sessionSeconds;
        float sink = ko ? (float) Math.min(1, (sessionSeconds - doneAt) / 2.5) : 0f;
        float bob = (float) Math.sin(t * 1.6f) * dp(5f) + (gloat ? (float) Math.abs(Math.sin(t * 9)) * -dp(6f) : 0f);
        float shakeX = flinch * (float) Math.sin(t * 60) * dp(6f);
        float x = bx + shakeX;
        float y = by + bob + sink * dp(90f);
        int alpha = (int) (255 * (1 - sink * 0.85f));
        int body = (alpha << 24) | (flinch > 0.3f ? 0xE08AFF : 0x6A3FB5);
        int dark = (alpha << 24) | 0x3E2270;
        Fx.glow(c, x, y, r * 2.0f, attack > 0.3f ? 0x44F0655D : 0x33B06CFF);
        // Tentacles: five waving curves; the outer right one rises to swing when you fall off pace.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(r * 0.22f);
        paint.setColor(dark);
        for (int k = 0; k < 5; k++) {
            float base = x + (k - 2) * r * 0.4f;
            float wave = (float) Math.sin(t * 2.2f + k * 1.3f) * r * 0.35f;
            shape.rewind();
            shape.moveTo(base, y + r * 0.5f);
            if (k == 0 && attack > 0.05f) {
                float sw = (float) Math.sin(t * 7) * r * 0.3f;
                shape.quadTo(base - r * 0.9f, y - r * 0.2f * attack, base - r * 1.2f + sw, y - r * 1.3f * attack);
            } else {
                shape.quadTo(base + wave, y + r * 1.0f, base - wave * 0.6f, y + r * 1.5f);
            }
            c.drawPath(shape, paint);
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
        // Head: a dome.
        paint.setColor(body);
        c.drawOval(x - r, y - r * 1.1f, x + r, y + r * 0.65f, paint);
        paint.setColor((alpha << 24) | 0x8A5AD8);
        c.drawCircle(x - r * 0.4f, y - r * 0.6f, r * 0.12f, paint);
        c.drawCircle(x + r * 0.2f, y - r * 0.8f, r * 0.08f, paint);
        // Eyes: crosses when knocked out, otherwise tracking the progress bar.
        float ex = r * 0.38f;
        float ey = y - r * 0.15f;
        if (ko) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3f));
            paint.setColor((alpha << 24) | 0xFFFFFF);
            for (int s = -1; s <= 1; s += 2) {
                float cxe = x + s * ex;
                c.drawLine(cxe - r * 0.14f, ey - r * 0.14f, cxe + r * 0.14f, ey + r * 0.14f, paint);
                c.drawLine(cxe - r * 0.14f, ey + r * 0.14f, cxe + r * 0.14f, ey - r * 0.14f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else {
            boolean blink = (t % 4.3f) < 0.12f;
            for (int s = -1; s <= 1; s += 2) {
                float cxe = x + s * ex;
                paint.setColor(0xFFFFFFFF);
                if (blink) {
                    c.drawRect(cxe - r * 0.2f, ey - dp(1.5f), cxe + r * 0.2f, ey + dp(1.5f), paint);
                    continue;
                }
                c.drawCircle(cxe, ey, r * 0.2f, paint);
                paint.setColor(attack > 0.3f ? BAD : 0xFF1A0F2E);
                c.drawCircle(cxe - r * 0.07f, ey + r * 0.04f, r * 0.1f, paint);
            }
            // Brows angle down as it attacks.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3f));
            paint.setColor(dark);
            float tilt = r * (0.05f + 0.15f * attack);
            c.drawLine(x - ex - r * 0.2f, ey - r * 0.3f - tilt, x - ex + r * 0.2f, ey - r * 0.3f + tilt, paint);
            c.drawLine(x + ex - r * 0.2f, ey - r * 0.3f + tilt, x + ex + r * 0.2f, ey - r * 0.3f - tilt, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // Mouth: a grin when gloating or attacking, a wobble when hit.
        paint.setColor(0xFF1A0F2E);
        float mouth = gloat || attack > 0.3f ? r * 0.18f : r * 0.07f + flinch * r * 0.12f;
        c.drawOval(x - r * 0.25f, y + r * 0.22f - mouth * 0.3f, x + r * 0.25f, y + r * 0.22f + mouth, paint);
        if (gloat) {
            bold(c, "HA HA", x, y - r * 1.3f - (float) Math.abs(Math.sin(t * 4)) * dp(6f), 16f, BAD, Paint.Align.CENTER);
        } else if (phase == Phase.ACTIVE && attack > 0.6f) {
            bold(c, "IT'S WINNING", x, y - r * 1.3f, 12f, BAD, Paint.Align.CENTER);
        }
        // Health bar.
        float hbW = r * 2.4f;
        float hbY = by - r * 1.1f - dp(40f);
        paint.setColor(0x44FFFFFF);
        c.drawRoundRect(bx - hbW / 2, hbY, bx + hbW / 2, hbY + dp(10f), dp(5f), dp(5f), paint);
        paint.setColor(bossHpShown > 0.5f ? BOSS_COLOR : bossHpShown > 0.2f ? WARN : BAD);
        c.drawRoundRect(bx - hbW / 2, hbY, bx - hbW / 2 + hbW * bossHpShown, hbY + dp(10f), dp(5f), dp(5f), paint);
        label(c, "THE KRAKEN  " + Math.round(bossHpShown * 100) + "%", bx, hbY - dp(5f), 10f, BOSS_COLOR, Paint.Align.CENTER);
    }

    private void drawStreak(Canvas c, float tx, float ty, float fxc, float fyc) {
        int streak = streak();
        if (streak > 0) {
            // A flame beside the streak, taller the longer it runs.
            float fh = dp(18f) + Math.min(10, streak) * dp(2f);
            float flick = (float) Math.sin(sessionSeconds * 12) * dp(2f);
            Fx.glow(c, fxc, fyc - fh * 0.3f, fh * 1.4f, 0x55FF8A3D);
            shape.rewind();
            shape.moveTo(fxc, fyc - fh + flick);
            shape.quadTo(fxc + fh * 0.6f, fyc - fh * 0.3f, fxc, fyc + dp(4f));
            shape.quadTo(fxc - fh * 0.6f, fyc - fh * 0.3f, fxc, fyc - fh + flick);
            paint.setColor(0xFFFF8A3D);
            c.drawPath(shape, paint);
            shape.rewind();
            shape.moveTo(fxc, fyc - fh * 0.55f - flick);
            shape.quadTo(fxc + fh * 0.3f, fyc - fh * 0.1f, fxc, fyc + dp(2f));
            shape.quadTo(fxc - fh * 0.3f, fyc - fh * 0.1f, fxc, fyc - fh * 0.55f - flick);
            paint.setColor(0xFFFFD36A);
            c.drawPath(shape, paint);
        }
        bold(c, streak + " DAY STREAK", tx, ty, 26f, streak > 0 ? WARN : DIM, Paint.Align.CENTER);
        label(c, "best streak " + Math.round(bests.get("daily.streak", 0f)) + " days", tx, ty + dp(18f), 10f, FAINT, Paint.Align.CENTER);
        if (token) {
            float pulse = 0.85f + 0.15f * (float) Math.sin(sessionSeconds * 3);
            String ready = "STREAK SAVER READY  ·  covers one missed day";
            float left = tx - (textWidth(ready, 11f) + dp(26f)) / 2;
            drawShield(c, left + dp(10f), ty + dp(38f), dp(10f) * pulse, BLUE);
            label(c, ready, left + dp(26f), ty + dp(42f), 11f, BLUE, Paint.Align.LEFT);
        } else {
            label(c, "beat a Sunday boss or row 7 days in a row to earn a streak saver", tx, ty + dp(40f), 10f, FAINT, Paint.Align.CENTER);
        }
    }

    /** The 28-day strip: a tick on each day won, purple for a boss, a shield where a saver covered. */
    private void drawStrip(Canvas c, float calL, float calY, float cell, int cells, boolean pulseToday) {
        for (int i = 0; i < cells; i++) {
            long day = today - (cells - 1 - i);
            boolean done = days.contains(day);
            boolean cover = !done && saved.contains(day);
            Integer tier = done ? medals.get(day) : null;
            boolean bossDay = tier != null && tier == BOSS_MEDAL;
            paint.setColor(bossDay ? BOSS_COLOR : done ? ACCENT : cover ? 0xFF24345C : day == today ? 0xFF2A3648 : 0xFF141D2A);
            c.drawRoundRect(calL + i * cell + dp(2f), calY, calL + (i + 1) * cell - dp(2f), calY + cell - dp(4f), dp(5f), dp(5f), paint);
            float cx0 = calL + (i + 0.5f) * cell;
            float cy0 = calY + (cell - dp(4f)) / 2f;
            if (done) {
                // A tick on every day you did it.
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(0xFF08111C);
                c.drawLine(cx0 - cell * 0.2f, cy0, cx0 - cell * 0.05f, cy0 + cell * 0.15f, paint);
                c.drawLine(cx0 - cell * 0.05f, cy0 + cell * 0.15f, cx0 + cell * 0.22f, cy0 - cell * 0.15f, paint);
                paint.setStyle(Paint.Style.FILL);
            } else if (cover) {
                drawShield(c, cx0, cy0, cell * 0.28f, BLUE);
            } else if (day == today && pulseToday) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 3);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (80 + 150 * pulse) << 24) | 0x35D0BA);
                c.drawRoundRect(calL + i * cell + dp(1f), calY - dp(1f), calL + (i + 1) * cell - dp(1f), calY + cell - dp(3f), dp(6f), dp(6f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
            if ((day + 4) % 7 == 0) {
                // Sundays are boss days: a small purple dot underneath.
                paint.setColor(0x99B06CFF);
                c.drawCircle(cx0, calY + cell + dp(1f), dp(2f), paint);
            }
        }
    }

    /** A calendar month with a medal on each day won. */
    private void drawMonth(Canvas c, float w, float h) {
        float top = h * 0.58f;
        float bottom = h - dp(8f);
        float rowH = (bottom - top - dp(46f)) / 6f;
        float cellW = Math.min(rowH * 1.35f, dp(66f));
        float gx = w * 0.58f - cellW * 3.5f;
        bold(c, monthTitle, w * 0.58f, top - dp(6f), 16f, TEXT, Paint.Align.CENTER);
        prevArrow.set(gx - dp(4f), top - dp(34f), gx + dp(40f), top + dp(4f));
        nextArrow.set(gx + cellW * 7 - dp(40f), top - dp(34f), gx + cellW * 7 + dp(4f), top + dp(4f));
        bold(c, "<", prevArrow.centerX(), top - dp(6f), 20f, DIM, Paint.Align.CENTER);
        if (monthOffset < 0) {
            bold(c, ">", nextArrow.centerX(), top - dp(6f), 20f, DIM, Paint.Align.CENTER);
        } else {
            nextArrow.setEmpty();
        }
        for (int k = 0; k < 7; k++) {
            label(c, WEEKDAYS[k], gx + (k + 0.5f) * cellW, top + dp(16f), 10f, k == 6 ? BOSS_COLOR : FAINT, Paint.Align.CENTER);
        }
        float gridTop = top + dp(24f);
        int won = 0;
        for (int i = 0; i < monthLen; i++) {
            int slot = monthLead + i;
            int col = slot % 7;
            int row = slot / 7;
            float x0 = gx + col * cellW;
            float y0 = gridTop + row * rowH;
            long day = monthFirst + i;
            paint.setColor(day == today ? 0xFF2A3648 : day > today ? 0xFF0C1520 : 0xFF141D2A);
            c.drawRoundRect(x0 + dp(2f), y0 + dp(2f), x0 + cellW - dp(2f), y0 + rowH - dp(2f), dp(5f), dp(5f), paint);
            label(c, String.valueOf(i + 1), x0 + dp(6f), y0 + dp(13f), 9f, FAINT, Paint.Align.LEFT);
            int tier = monthTier[i];
            float mr = Math.min(cellW, rowH) * 0.28f;
            float mx = x0 + cellW / 2 + dp(4f);
            float my = y0 + rowH / 2 + dp(2f);
            if (tier > 0) {
                won++;
                drawMedal(c, mx, my, mr, tier, day == today && phase == Phase.DONE && success);
            } else if (tier < 0) {
                drawShield(c, mx, my, mr * 0.9f, BLUE);
            }
            if (day == today) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 3);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (80 + 150 * pulse) << 24) | 0x35D0BA);
                c.drawRoundRect(x0 + dp(1f), y0 + dp(1f), x0 + cellW - dp(1f), y0 + rowH - dp(1f), dp(6f), dp(6f), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        // Streak and legend on the left of the grid.
        float lx = w * 0.2f;
        drawStreak(c, lx, top + dp(90f), lx, top + dp(40f));
        float ly = top + dp(170f);
        if (ly + dp(20f) < bottom) {
            label(c, won + (won == 1 ? " MEDAL" : " MEDALS") + " IN " + monthTitle, lx, ly, 11f, TEXT, Paint.Align.CENTER);
            float lr = dp(8f);
            float lxs = lx - dp(150f);
            int[] tiers = LEGEND_TIERS;
            for (int k = 0; k < tiers.length; k++) {
                float x = lxs + k * dp(76f);
                if (tiers[k] > 0) {
                    drawMedal(c, x, ly + dp(24f), lr, tiers[k], false);
                } else {
                    drawShield(c, x, ly + dp(24f), lr, BLUE);
                }
                label(c, LEGEND_NAMES[k], x + dp(12f), ly + dp(28f), 9f, FAINT, Paint.Align.LEFT);
            }
        }
    }

    private static final int[] LEGEND_TIERS = {BRONZE, SILVER, GOLD, BOSS_MEDAL, -1};
    private static final String[] LEGEND_NAMES = {"won", "+5%", "+10%", "boss", "saved"};

    /** The shareable result card, drawn over everything; the camera button photographs it. */
    private void drawCard(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xDD03070D);
        c.drawRect(0, 0, w, h, paint);
        float cw = Math.min(w * 0.62f, h * 1.25f);
        float ch = Math.min(h * 0.86f, cw * 0.62f);
        float l = (w - cw) / 2;
        float t = (h - ch) / 2;
        int edge = !success ? WARN : boss ? BOSS_COLOR : ACCENT;
        Fx.glow(c, w / 2, h / 2, cw * 0.55f, (0x30 << 24) | (edge & 0x00FFFFFF));
        paint.setColor(boss ? 0xFF140E24 : 0xFF0E1A2A);
        c.drawRoundRect(l, t, l + cw, t + ch, dp(18f), dp(18f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3f));
        paint.setColor(edge);
        c.drawRoundRect(l, t, l + cw, t + ch, dp(18f), dp(18f), paint);
        paint.setStyle(Paint.Style.FILL);
        float pad = dp(28f);
        label(c, "WAKE  ·  DAILY ROW", l + pad, t + dp(36f), 12f, FAINT, Paint.Align.LEFT);
        label(c, weekdayText + "  " + dateText, l + cw - pad, t + dp(36f), 12f, DIM, Paint.Align.RIGHT);
        if (boss) {
            bold(c, "SUNDAY BOSS  ·  THE KRAKEN", l + pad, t + dp(64f), 13f, BOSS_COLOR, Paint.Align.LEFT);
        }
        bold(c, challenge.title, l + pad, t + dp(100f), 26f, TEXT, Paint.Align.LEFT);
        // Medal on the right: the day's tier on a win, a dulled medal on a miss.
        float mr = ch * 0.16f;
        float mx = l + cw - pad - mr;
        float my = t + ch * 0.42f;
        if (success) {
            Fx.glow(c, mx, my, mr * 1.8f, 0x66F5C518);
            drawMedal(c, mx, my, mr, Math.max(BRONZE, todayTier), false);
        } else {
            paint.setColor(0xFF4A3F36);
            c.drawCircle(mx, my, mr, paint);
            paint.setColor(0xFF7A6A58);
            star(mx, my, mr * 0.6f, mr * 0.27f);
            c.drawPath(shape, paint);
        }
        bold(c, success ? (boss ? "BOSS DOWN" : "DAY WON") : "NOT TODAY", l + pad, t + ch * 0.36f, 40f, edge, Paint.Align.LEFT);
        bold(c, resultText(), l + pad, t + ch * 0.36f + dp(44f), 30f, TEXT, Paint.Align.LEFT);
        label(c, "target " + targetLine(), l + pad, t + ch * 0.36f + dp(68f), 12f, DIM, Paint.Align.LEFT);
        int streak = streak();
        bold(c, streak + " DAY STREAK", l + pad, t + ch * 0.36f + dp(104f), 20f, streak > 0 ? WARN : DIM, Paint.Align.LEFT);
        if (token) {
            drawShield(c, l + pad + dp(220f), t + ch * 0.36f + dp(98f), dp(9f), BLUE);
            label(c, "saver ready", l + pad + dp(234f), t + ch * 0.36f + dp(102f), 11f, BLUE, Paint.Align.LEFT);
        }
        int cells = 28;
        float cell = Math.min(dp(28f), (cw - pad * 2) / cells);
        float stripY = t + ch - pad - cell - dp(10f);
        label(c, "LAST 28 DAYS", l + pad, stripY - dp(8f), 9f, FAINT, Paint.Align.LEFT);
        drawStrip(c, l + pad, stripY, cell, cells, false);
        label(c, "tap to close  ·  camera button saves it", w / 2, t + ch + dp(26f), 11f, FAINT, Paint.Align.CENTER);
    }

    private void drawMedal(Canvas c, float x, float y, float r, int tier, boolean shine) {
        int face = tier == BOSS_MEDAL ? BOSS_COLOR : tier == GOLD ? 0xFFF5C518 : tier == SILVER ? 0xFFC9D3DE : 0xFFCD7F32;
        int rim = tier == BOSS_MEDAL ? 0xFF6A3FB5 : tier == GOLD ? 0xFFB8890B : tier == SILVER ? 0xFF7F8A99 : 0xFF8A5220;
        // Ribbon tails.
        paint.setColor(tier == BOSS_MEDAL ? 0xFFF0655D : 0xFF6F8CFF);
        shape.rewind();
        shape.moveTo(x - r * 0.7f, y - r * 1.25f);
        shape.lineTo(x - r * 0.1f, y - r * 1.25f);
        shape.lineTo(x + r * 0.2f, y - r * 0.5f);
        shape.lineTo(x - r * 0.4f, y - r * 0.5f);
        shape.close();
        shape.moveTo(x + r * 0.7f, y - r * 1.25f);
        shape.lineTo(x + r * 0.1f, y - r * 1.25f);
        shape.lineTo(x - r * 0.2f, y - r * 0.5f);
        shape.lineTo(x + r * 0.4f, y - r * 0.5f);
        shape.close();
        c.drawPath(shape, paint);
        paint.setColor(rim);
        c.drawCircle(x, y, r, paint);
        paint.setColor(face);
        c.drawCircle(x, y, r * 0.82f, paint);
        paint.setColor(rim);
        if (tier == BOSS_MEDAL) {
            // A crown for a beaten boss.
            shape.rewind();
            shape.moveTo(x - r * 0.45f, y + r * 0.3f);
            shape.lineTo(x - r * 0.45f, y - r * 0.3f);
            shape.lineTo(x - r * 0.22f, y);
            shape.lineTo(x, y - r * 0.4f);
            shape.lineTo(x + r * 0.22f, y);
            shape.lineTo(x + r * 0.45f, y - r * 0.3f);
            shape.lineTo(x + r * 0.45f, y + r * 0.3f);
            shape.close();
        } else {
            star(x, y, r * 0.5f, r * 0.22f);
        }
        c.drawPath(shape, paint);
        if (shine) {
            float s = (float) ((sessionSeconds * 0.8) % 1.0);
            paint.setColor(0x88FFFFFF);
            c.drawCircle(x - r * 0.5f + s * r, y - r * 0.4f + s * r * 0.8f, r * 0.15f, paint);
        }
    }

    private void drawShield(Canvas c, float x, float y, float r, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        shape.rewind();
        shape.moveTo(x, y - r);
        shape.lineTo(x + r * 0.85f, y - r * 0.65f);
        shape.quadTo(x + r * 0.8f, y + r * 0.5f, x, y + r);
        shape.quadTo(x - r * 0.8f, y + r * 0.5f, x - r * 0.85f, y - r * 0.65f);
        shape.close();
        c.drawPath(shape, paint);
        paint.setColor(0xCCFFFFFF);
        c.drawRect(x - r * 0.08f, y - r * 0.55f, x + r * 0.08f, y + r * 0.55f, paint);
        c.drawRect(x - r * 0.4f, y - r * 0.15f, x + r * 0.4f, y + r * 0.01f, paint);
    }

    /** A five-pointed star into {@link #shape}. */
    private void star(float x, float y, float outer, float inner) {
        shape.rewind();
        for (int k = 0; k < 10; k++) {
            double a = -Math.PI / 2 + k * Math.PI / 5;
            float r2 = (k & 1) == 0 ? outer : inner;
            float px = x + (float) Math.cos(a) * r2;
            float py = y + (float) Math.sin(a) * r2;
            if (k == 0) {
                shape.moveTo(px, py);
            } else {
                shape.lineTo(px, py);
            }
        }
        shape.close();
    }

    private void chip(Canvas c, RectF r, String text, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((0x2A << 24) | (color & 0x00FFFFFF));
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(color);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, text, r.centerX(), r.centerY() + dp(5f), 13f, color, Paint.Align.CENTER);
    }

    /** Width of {@code text} at a size; measured with the bold paint, so it bounds a label too. */
    private float textWidth(String text, float sizeDp) {
        textPaint.setTextSize(dp(sizeDp));
        return textPaint.measureText(text);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** The target as a sentence for the header and the card. */
    private String targetLine() {
        switch (challenge) {
            case RATE_LADDER: {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < rungs; i++) {
                    if (i > 0) {
                        sb.append(" > ");
                    }
                    sb.append(ladderRates[i]);
                }
                return sb + " spm, " + Math.round(RUNG_SECONDS) + " s each  ·  " + Math.round(target) + "% in band";
            }
            case NEG_SPLIT:
                return "2nd 250 m faster by " + targetText(target) + "  ·  500 m under " + clock(negCap);
            default:
                return targetText(target);
        }
    }

    /** The result; a negative split also shows its 500 m time, since the cap can decide it. */
    private String resultText() {
        if (challenge == Challenge.NEG_SPLIT) {
            return targetText(result) + "  ·  500 m in " + clock(negTotal);
        }
        return targetText(result);
    }

    private String targetText(double v) {
        switch (challenge) {
            case DISTANCE_4MIN:
                return Math.round(v) + " m";
            case TRIAL_500:
                return clock(v);
            case POWER_10:
                return Math.round(v) + " W";
            case RATE_LADDER:
                return Math.round(v) + "% in band";
            case NEG_SPLIT:
                return (v >= 0 ? "+" : "") + String.format(java.util.Locale.US, "%.1f", v) + " s";
            default:
                return Math.round(v) + "% steady";
        }
    }
}
