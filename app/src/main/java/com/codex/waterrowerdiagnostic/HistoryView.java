package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The row history screen: headline totals, a twelve-week calendar heatmap, distance per day (per
 * week when zoomed out), average split over time with the best marked, and a scrolling list of
 * every session. Tapping a session opens its per-bucket split and watts series.
 *
 * <p>One hand-drawn {@link View}; no child views, no AndroidX, nothing allocated in
 * {@link #onDraw}. The frame loop is driven by {@link #postInvalidateOnAnimation()} and stops the
 * moment the view is detached or not shown.
 *
 * <p>Wiring, from the activity:
 * <pre>
 *   HistoryView history = new HistoryView(this);
 *   history.setOnBackListener(() -&gt; showHome());
 *   history.setProfile(profiles.active().name, profiles.active().colour);
 *   history.setLog(SessionLog.forActiveProfile(this, profiles));   // reads and animates in
 *   ...
 *   history.refresh();               // after a row is committed, or on profile switch
 *   if (history.onBackPressed()) return;   // closes an open session detail first
 * </pre>
 *
 * <p><b>The split axis is inverted</b> - a lower split is a faster row, so the fastest split sits at
 * the top of the chart.
 */
final class HistoryView extends View {

    // ------------------------------------------------------------------ public API

    /** Ranges, in chip order. */
    static final int RANGE_7_DAYS = 0;
    static final int RANGE_30_DAYS = 1;
    static final int RANGE_12_WEEKS = 2;
    static final int RANGE_ALL = 3;

    private static final String[] RANGE_LABELS = {"7 DAYS", "30 DAYS", "12 WEEKS", "ALL"};
    private static final int[] RANGE_DAYS = {7, 30, 84, 0};
    private static final String[] RANGE_TITLES = {
            "LAST 7 DAYS", "LAST 30 DAYS", "LAST 12 WEEKS", "ALL TIME"};
    /** Pre-joined so the headline caption costs no allocation per frame. */
    private static final String[] RANGE_DISTANCE_CAPTIONS = {
            "DISTANCE LAST 7 DAYS", "DISTANCE LAST 30 DAYS",
            "DISTANCE LAST 12 WEEKS", "DISTANCE ALL TIME"};

    // ------------------------------------------------------------------ palette

    private static final int BG = 0xFF0A0E14;
    private static final int SURFACE = 0xFF111722;
    private static final int SURFACE_ALT = 0xFF161D2B;
    private static final int LINE = 0xFF212B3B;
    private static final int TEXT = 0xFFE6EDF7;
    private static final int DIM = 0xFF8D9BB0;
    private static final int FAINT = 0xFF5D6B80;
    private static final int TRACK = 0xFF18202C;
    private static final int BLUE = 0xFF6F8CFF;
    private static final int WARN = 0xFFF0B132;
    private static final int BAD = 0xFFF0655D;
    private static final int DEFAULT_ACCENT = 0xFF35D0BA;

    private static final String[] MONTHS = {
            "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAY_NAMES = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    private static final int HEAT_WEEKS = 12;
    private static final int HEAT_CELLS = HEAT_WEEKS * 7;

    /** Grow-in duration, seconds. */
    private static final float ANIM_SECONDS = 0.62f;
    private static final float DETAIL_SECONDS = 0.28f;

    // ------------------------------------------------------------------ state

    private SessionLog log;
    private String profileName = "";
    private int accent = DEFAULT_ACCENT;
    private Runnable backListener;

    private int range = RANGE_30_DAYS;

    /** Every stored session, newest first. Rebuilt only on {@link #refresh()}. */
    private final ArrayList<SessionLog.Session> all = new ArrayList<>();
    /** The rows shown in the list - the in-range slice, newest first, with strings precomputed. */
    private final ArrayList<Row> rows = new ArrayList<>();

    private final SessionLog.Totals rangeTotals = new SessionLog.Totals();
    private float weekMetres;
    private float lastWeekMetres;

    // Distance chart.
    private final float[] barValue = new float[400];
    private final String[] barLabel = new String[400];
    private int barCount;
    private float barMax;
    private boolean barWeekly;

    // Split chart: one point per in-range session, oldest first.
    private final float[] splitX = new float[2048];
    private final float[] splitValue = new float[2048];
    private final int[] splitRow = new int[2048];
    private int splitCount;
    private float splitBest;
    private float splitWorst;
    private int splitBestIndex = -1;

    // Heatmap.
    private final float[] heat = new float[HEAT_CELLS];
    private float heatMax;
    private long heatStart;
    private int heatToday = -1;
    private final String[] heatMonth = new String[HEAT_WEEKS];

    private int selected = -1;
    private boolean detailOpen;

    // Animation.
    private float anim;
    private float detailAnim;
    private float drift;
    private float pulse;
    private long lastFrame;
    private boolean attached;

    // Scrolling.
    private float scrollY;
    private float maxScroll;
    private final OverScroller scroller;
    private VelocityTracker velocity;
    private final int touchSlop;
    private float downX;
    private float downY;
    private boolean dragging;
    private int downRow = -1;
    private int downChip = -1;
    private boolean downBack;
    private boolean downClose;

    // Drawing scratch - allocated once, never in onDraw.
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final RectF headerRect = new RectF();
    private final RectF backRect = new RectF();
    private final RectF[] chipRect = new RectF[RANGE_LABELS.length];
    private final RectF statsRect = new RectF();
    private final RectF listRect = new RectF();
    private final RectF chartsRect = new RectF();
    private final RectF heatRect = new RectF();
    private final RectF distRect = new RectF();
    private final RectF splitRect = new RectF();
    private final RectF closeRect = new RectF();
    private LinearGradient areaShader;
    private RadialGradient driftShader;
    private final StringBuilder sb = new StringBuilder(48);
    private final char[] cbuf = new char[96];
    private final Calendar cal = Calendar.getInstance();

    private final float density;

    HistoryView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        scroller = new OverScroller(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        for (int i = 0; i < chipRect.length; i++) {
            chipRect[i] = new RectF();
        }
        setBackgroundColor(BG);
        setClickable(true);
        setFocusable(true);
    }

    /** The screen's back chevron. The activity decides what "back" means. */
    void setOnBackListener(Runnable listener) {
        backListener = listener;
    }

    /** Name and colour of the profile whose history this is; the colour becomes the accent. */
    void setProfile(String name, int colour) {
        profileName = name == null ? "" : name;
        if ((colour >>> 24) != 0 && colour != accent) {
            accent = colour;
            // The area and backdrop gradients are baked from the accent.
            layOut(getWidth(), getHeight());
        }
        invalidate();
    }

    /** Bind the log and read it. Safe to call again on a profile switch. */
    void setLog(SessionLog log) {
        this.log = log;
        selected = -1;
        detailOpen = false;
        detailAnim = 0f;
        scrollY = 0f;
        refresh();
    }

    /** Re-read the log - call after a row is committed. Restarts the grow-in. */
    void refresh() {
        all.clear();
        if (log != null) {
            List<SessionLog.Session> stored = log.list();
            for (int i = 0; i < stored.size(); i++) {
                all.add(stored.get(i));
            }
        }
        rebuild();
        restartAnim();
    }

    int range() {
        return range;
    }

    void setRange(int newRange) {
        if (newRange < 0 || newRange >= RANGE_LABELS.length || newRange == range) {
            return;
        }
        range = newRange;
        selected = -1;
        detailOpen = false;
        detailAnim = 0f;
        scrollY = 0f;
        scroller.forceFinished(true);
        rebuild();
        restartAnim();
    }

    /**
     * @return true when the view consumed the press (an open session detail was closed), false when
     *         the activity should leave the screen.
     */
    boolean onBackPressed() {
        if (detailOpen) {
            detailOpen = false;
            invalidate();
            return true;
        }
        return false;
    }

    /** True while a session detail is open. */
    boolean detailOpen() {
        return detailOpen;
    }

    // ------------------------------------------------------------------ data shaping

    private void rebuild() {
        long now = System.currentTimeMillis();
        long from;
        long to = SessionLog.addDays(SessionLog.startOfDay(now), 1);
        int days = RANGE_DAYS[range];
        if (days <= 0) {
            long earliest = all.isEmpty() ? now : all.get(all.size() - 1).startedAt;
            from = SessionLog.startOfDay(Math.min(earliest, now));
        } else {
            from = SessionLog.addDays(SessionLog.startOfDay(now), -(days - 1));
        }

        // Row indices shift when a new session is committed (the list is newest first), so a held
        // selection is re-found by its start time rather than kept as a stale index - otherwise an
        // open detail would silently start describing a different row.
        long keep = selected >= 0 && selected < rows.size()
                ? rows.get(selected).session.startedAt : 0L;
        rows.clear();
        for (int i = 0; i < all.size(); i++) {
            SessionLog.Session s = all.get(i);
            if (s.startedAt >= from && s.startedAt < to) {
                rows.add(buildRow(s));
            }
        }
        if (keep != 0L) {
            selected = -1;
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).session.startedAt == keep) {
                    selected = i;
                    break;
                }
            }
            if (selected < 0) {
                detailOpen = false;
                detailAnim = 0f;
            }
        }
        computeTotals();
        buildDistanceSeries(from, to, days);
        buildSplitSeries(from, to);
        buildHeat(now);
        clampScroll();
    }

    private Row buildRow(SessionLog.Session s) {
        Row r = new Row();
        r.session = s;
        cal.setTimeInMillis(s.startedAt);
        int dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        r.date = String.format(Locale.US, "%s %d %s", DAY_NAMES[dow],
                cal.get(Calendar.DAY_OF_MONTH), monthName(cal.get(Calendar.MONTH)));
        r.time = String.format(Locale.US, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
        r.game = s.game == null || s.game.isEmpty() ? "ROW" : s.game;
        r.metres = SessionLog.formatMetres(s.metres);
        r.duration = SessionLog.formatDuration(s.durationSeconds);
        r.split = SessionLog.formatSplit(s.avgSplit) + " /500m";
        r.watts = Math.round(s.avgWatts) + " W";
        r.sub = r.time + "  " + r.game;
        r.stats = r.duration + "   " + r.split + "   " + r.watts;
        // The detail header joins these too - built here so onDraw never concatenates.
        r.detailSub = r.time + "   " + r.game;
        return r;
    }

    private void computeTotals() {
        SessionLog.Totals t = SessionLog.totalsOf(collect(rows));
        rangeTotals.sessions = t.sessions;
        rangeTotals.metres = t.metres;
        rangeTotals.seconds = t.seconds;
        rangeTotals.strokes = t.strokes;
        rangeTotals.joules = t.joules;
        rangeTotals.avgWatts = t.avgWatts;
        rangeTotals.bestSplit = t.bestSplit;
        rangeTotals.mostMetres = t.mostMetres;
        rangeTotals.longestSeconds = t.longestSeconds;
        rangeTotals.peakWatts = t.peakWatts;
        rangeTotals.activeDays = t.activeDays;

        long now = System.currentTimeMillis();
        long weekStart = SessionLog.startOfWeek(now);
        long prevStart = SessionLog.addDays(weekStart, -7);
        weekMetres = 0f;
        lastWeekMetres = 0f;
        for (int i = 0; i < all.size(); i++) {
            SessionLog.Session s = all.get(i);
            if (s.startedAt >= weekStart) {
                weekMetres += s.metres;
            } else if (s.startedAt >= prevStart) {
                lastWeekMetres += s.metres;
            }
        }
    }

    /** Small adapter so {@link SessionLog#totalsOf} can read the in-range slice. Build time only. */
    private static List<SessionLog.Session> collect(ArrayList<Row> rows) {
        ArrayList<SessionLog.Session> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            out.add(rows.get(i).session);
        }
        return out;
    }

    private void buildDistanceSeries(long from, long to, int days) {
        barCount = 0;
        barMax = 0f;
        barWeekly = days <= 0 || days > 31;
        if (barWeekly) {
            long start = SessionLog.startOfWeek(from);
            // ALL TIME on a long history can want more buckets than the array holds. Drop the
            // OLDEST ones - filling from the start would have charted 2015 and hidden this month.
            start = SessionLog.addDays(start, skipBuckets(start, to, 7));
            int n = 0;
            while (start < to && n < barValue.length) {
                long end = SessionLog.addDays(start, 7);
                if (end <= start) {
                    break; // A timezone that refused to advance; never spin here.
                }
                float m = 0f;
                for (int i = 0; i < rows.size(); i++) {
                    long at = rows.get(i).session.startedAt;
                    if (at >= start && at < end) {
                        m += rows.get(i).session.metres;
                    }
                }
                barValue[n] = m;
                cal.setTimeInMillis(start);
                barLabel[n] = cal.get(Calendar.DAY_OF_MONTH) + " " + monthName(cal.get(Calendar.MONTH));
                if (m > barMax) {
                    barMax = m;
                }
                n++;
                start = end;
            }
            barCount = n;
        } else {
            long start = SessionLog.startOfDay(from);
            start = SessionLog.addDays(start, skipBuckets(start, to, 1));
            int n = 0;
            while (start < to && n < barValue.length) {
                long end = SessionLog.addDays(start, 1);
                if (end <= start) {
                    break;
                }
                float m = 0f;
                for (int i = 0; i < rows.size(); i++) {
                    long at = rows.get(i).session.startedAt;
                    if (at >= start && at < end) {
                        m += rows.get(i).session.metres;
                    }
                }
                barValue[n] = m;
                cal.setTimeInMillis(start);
                int dom = cal.get(Calendar.DAY_OF_MONTH);
                barLabel[n] = (dom == 1 || n == 0)
                        ? dom + " " + monthName(cal.get(Calendar.MONTH))
                        : String.valueOf(dom);
                if (m > barMax) {
                    barMax = m;
                }
                n++;
                start = end;
            }
            barCount = n;
        }
        if (barMax <= 0f) {
            barMax = 1f;
        }
    }

    /**
     * Days to skip so the newest {@code barValue.length} buckets fit. Zero unless the span is
     * longer than the chart can hold.
     */
    private int skipBuckets(long start, long to, int daysPerBucket) {
        if (to <= start || daysPerBucket <= 0) {
            return 0;
        }
        long bucketMillis = 86400000L * daysPerBucket;
        long wanted = (to - start + bucketMillis - 1) / bucketMillis;
        long over = wanted - barValue.length;
        if (over <= 0) {
            return 0;
        }
        // Clamped so the day arithmetic below cannot overflow an int.
        return (int) Math.min(over, 100000L) * daysPerBucket;
    }

    private void buildSplitSeries(long from, long to) {
        splitCount = 0;
        splitBest = 0f;
        splitWorst = 0f;
        splitBestIndex = -1;
        float span = Math.max(1f, to - from);
        // rows are newest first; walk backwards for oldest-first plotting.
        for (int i = rows.size() - 1; i >= 0 && splitCount < splitValue.length; i--) {
            SessionLog.Session s = rows.get(i).session;
            if (s.avgSplit <= 0f || s.avgSplit > 900f) {
                continue;
            }
            splitX[splitCount] = Math.max(0f, Math.min(1f, (s.startedAt - from) / span));
            splitValue[splitCount] = s.avgSplit;
            splitRow[splitCount] = i;
            if (splitBestIndex < 0 || s.avgSplit < splitBest) {
                splitBest = s.avgSplit;
                splitBestIndex = splitCount;
            }
            if (splitCount == 0 || s.avgSplit > splitWorst) {
                splitWorst = s.avgSplit;
            }
            splitCount++;
        }
        if (splitCount > 0 && splitWorst - splitBest < 4f) {
            // A flat run still deserves a readable band.
            splitWorst = splitBest + 4f;
        }
    }

    private void buildHeat(long now) {
        long thisWeek = SessionLog.startOfWeek(now);
        heatStart = SessionLog.addDays(thisWeek, -7 * (HEAT_WEEKS - 1));
        long today = SessionLog.startOfDay(now);
        heatMax = 0f;
        heatToday = -1;
        for (int i = 0; i < HEAT_CELLS; i++) {
            heat[i] = 0f;
        }
        for (int i = 0; i < all.size(); i++) {
            SessionLog.Session s = all.get(i);
            long day = SessionLog.startOfDay(s.startedAt);
            int index = dayIndex(day);
            if (index >= 0 && index < HEAT_CELLS) {
                heat[index] += s.metres;
                if (heat[index] > heatMax) {
                    heatMax = heat[index];
                }
            }
        }
        int t = dayIndex(today);
        if (t >= 0 && t < HEAT_CELLS) {
            heatToday = t;
        }
        for (int w = 0; w < HEAT_WEEKS; w++) {
            cal.setTimeInMillis(SessionLog.addDays(heatStart, w * 7));
            int dom = cal.get(Calendar.DAY_OF_MONTH);
            heatMonth[w] = (w == 0 || dom <= 7) ? monthName(cal.get(Calendar.MONTH)) : null;
        }
        if (heatMax <= 0f) {
            heatMax = 1f;
        }
    }

    /** Column-major index into the heat grid, or -1 when the day is outside the twelve weeks. */
    private int dayIndex(long dayStart) {
        long diff = dayStart - heatStart;
        if (diff < -43200000L) {
            return -1;
        }
        // Both ends are local midnights, but a DST change makes a day 23 or 25 hours long, so a
        // plain divide floors the boundary day into the previous cell (two days in one square and
        // a hole beside it). Rounding to the nearest day absorbs the one-hour shift.
        int day = (int) ((diff + 43200000L) / 86400000L);
        if (day < 0 || day >= HEAT_CELLS) {
            return -1;
        }
        int week = day / 7;
        int dow = day % 7;
        return week * 7 + dow;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        layOut(w, h);
    }

    private void layOut(int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        float pad = dp(16f);
        float headerH = dp(58f);
        headerRect.set(0, 0, w, headerH);
        backRect.set(pad, dp(7f), pad + dp(44f), dp(51f));

        float chipW = dp(96f);
        float chipH = dp(44f);
        float chipTop = (headerH - chipH) / 2f;
        float cx = w - pad - chipRect.length * (chipW + dp(8f)) + dp(8f);
        for (int i = 0; i < chipRect.length; i++) {
            chipRect[i].set(cx, chipTop, cx + chipW, chipTop + chipH);
            cx += chipW + dp(8f);
        }

        float statsH = dp(92f);
        statsRect.set(pad, headerH, w - pad, headerH + statsH);

        float bodyTop = statsRect.bottom + dp(12f);
        float bodyBottom = h - pad;
        float listW = Math.min(dp(560f), w * 0.34f);
        listRect.set(pad, bodyTop, pad + listW, bodyBottom);
        chartsRect.set(listRect.right + dp(14f), bodyTop, w - pad, bodyBottom);

        float gap = dp(12f);
        float heatH = Math.max(dp(132f), chartsRect.height() * 0.29f);
        heatRect.set(chartsRect.left, chartsRect.top, chartsRect.right, chartsRect.top + heatH);
        float rest = chartsRect.bottom - heatRect.bottom - gap * 2f;
        distRect.set(chartsRect.left, heatRect.bottom + gap,
                chartsRect.right, heatRect.bottom + gap + rest * 0.5f);
        splitRect.set(chartsRect.left, distRect.bottom + gap, chartsRect.right, chartsRect.bottom);

        closeRect.set(chartsRect.right - dp(52f), chartsRect.top + dp(8f),
                chartsRect.right - dp(8f), chartsRect.top + dp(52f));

        areaShader = new LinearGradient(0, distRect.top, 0, distRect.bottom,
                (accent & 0x00FFFFFF) | 0x66000000, (accent & 0x00FFFFFF), Shader.TileMode.CLAMP);
        float driftR = Math.max(w, h) * 0.55f;
        driftShader = new RadialGradient(0, 0, driftR,
                (accent & 0x00FFFFFF) | 0x1A000000, (accent & 0x00FFFFFF), Shader.TileMode.CLAMP);
        clampScroll();
    }

    private float dp(float v) {
        return v * density;
    }

    private float rowHeight() {
        return dp(54f);
    }

    private void clampScroll() {
        float visible = Math.max(0f, listRect.height() - dp(34f));
        maxScroll = Math.max(0f, rows.size() * rowHeight() - visible);
        if (scrollY > maxScroll) {
            scrollY = maxScroll;
        }
        if (scrollY < 0f) {
            scrollY = 0f;
        }
    }

    // ------------------------------------------------------------------ frame loop

    private void restartAnim() {
        anim = 0f;
        lastFrame = 0L;
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        lastFrame = 0L;
        restartAnim();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        scroller.forceFinished(true);
        // A DOWN with no matching UP (the screen swapped mid-gesture) would otherwise leak this.
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
        super.onDetachedFromWindow();
    }

    /**
     * The frame loop runs only while this view is on a visible window. {@code isShown()} alone is
     * not enough: it ignores window visibility, so a backgrounded activity would keep the 60Hz
     * invalidate loop posting.
     */
    private boolean running() {
        return attached && isShown() && getWindowVisibility() == VISIBLE;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            lastFrame = 0L;
            restartAnim();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            lastFrame = 0L;
            invalidate();
        }
    }

    private void step() {
        long now = System.nanoTime();
        float dt = lastFrame == 0L ? 0f : (now - lastFrame) / 1_000_000_000f;
        lastFrame = now;
        if (dt > 0.05f) {
            dt = 0.05f;
        }
        if (dt < 0f) {
            dt = 0f;
        }
        anim += dt / ANIM_SECONDS;
        if (anim > 1f) {
            anim = 1f;
        }
        float target = detailOpen ? 1f : 0f;
        if (detailAnim < target) {
            detailAnim = Math.min(target, detailAnim + dt / DETAIL_SECONDS);
        } else if (detailAnim > target) {
            detailAnim = Math.max(target, detailAnim - dt / DETAIL_SECONDS);
        }
        drift += dt;
        pulse += dt;
        if (pulse > 1000f) {
            pulse = 0f;
        }
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.getCurrY();
            clampScroll();
            if (scrollY <= 0f || scrollY >= maxScroll) {
                scroller.forceFinished(true);
            }
        }
    }

    private static float easeOut(float t) {
        float u = 1f - t;
        return 1f - u * u * u;
    }

    // ------------------------------------------------------------------ draw

    @Override
    protected void onDraw(Canvas c) {
        step();
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }
        float ease = easeOut(anim);

        c.drawColor(BG);
        drawBackdrop(c, w, h);
        drawHeader(c, w);

        if (rows.isEmpty() && all.isEmpty()) {
            drawEmpty(c, w, h, ease);
        } else {
            drawStats(c, ease);
            drawList(c);
            if (detailAnim < 1f) {
                drawHeatmap(c);
                drawDistance(c, ease);
                drawSplit(c, ease);
            }
            if (detailAnim > 0f) {
                drawDetail(c);
            }
        }

        if (running()) {
            postInvalidateOnAnimation();
        }
    }

    /** Two slow glows and a few drifting lines - motion without noise. */
    private void drawBackdrop(Canvas c, float w, float h) {
        if (driftShader == null) {
            return;
        }
        double t = drift * 0.06;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(driftShader);
        float r = Math.max(w, h) * 0.55f;
        c.save();
        c.translate(w * (0.25f + 0.10f * (float) Math.sin(t)),
                h * (0.30f + 0.08f * (float) Math.cos(t * 0.8)));
        c.drawCircle(0, 0, r, paint);
        c.restore();
        c.save();
        c.translate(w * (0.78f + 0.09f * (float) Math.cos(t * 0.7 + 1.4)),
                h * (0.72f + 0.07f * (float) Math.sin(t * 0.9)));
        c.drawCircle(0, 0, r, paint);
        c.restore();
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(0x14FFFFFF);
        float spacing = h / 7f;
        float offset = (drift * 7f) % spacing;
        for (int i = -1; i < 8; i++) {
            float y = i * spacing + offset;
            c.drawLine(0, y, w, y - h * 0.12f, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawHeader(Canvas c, float w) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(SURFACE);
        c.drawRect(0, 0, w, headerRect.bottom, paint);
        paint.setColor(LINE);
        c.drawRect(0, headerRect.bottom - dp(1f), w, headerRect.bottom, paint);

        // Back chevron.
        paint.setColor(downBack ? accent : TEXT);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.4f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        float bx = backRect.centerX() + dp(3f);
        float by = backRect.centerY();
        c.drawLine(bx, by - dp(8f), bx - dp(8f), by, paint);
        c.drawLine(bx - dp(8f), by, bx, by + dp(8f), paint);
        paint.setStyle(Paint.Style.FILL);
        // One shared Paint: leaving a round cap on would round every gridline and chart end below.
        paint.setStrokeCap(Paint.Cap.BUTT);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setColor(TEXT);
        paint.setTextSize(dp(18f));
        float tx = backRect.right + dp(14f);
        c.drawText("ROW HISTORY", tx, headerRect.centerY() + dp(6f), paint);
        paint.setFakeBoldText(false);
        if (!profileName.isEmpty()) {
            float nameX = tx + paint.measureText("ROW HISTORY") + dp(14f);
            paint.setColor(accent);
            c.drawCircle(nameX + dp(5f), headerRect.centerY() - dp(1f), dp(5f), paint);
            paint.setColor(DIM);
            paint.setTextSize(dp(13f));
            c.drawText(profileName, nameX + dp(16f), headerRect.centerY() + dp(4f), paint);
        }

        for (int i = 0; i < chipRect.length; i++) {
            RectF r = chipRect[i];
            boolean on = i == range;
            paint.setColor(on ? accent : (downChip == i ? SURFACE_ALT : TRACK));
            c.drawRoundRect(r, dp(20f), dp(20f), paint);
            paint.setColor(on ? 0xFF06131A : DIM);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(dp(12f));
            paint.setFakeBoldText(on);
            c.drawText(RANGE_LABELS[i], r.centerX(), r.centerY() + dp(4f), paint);
            paint.setFakeBoldText(false);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawEmpty(Canvas c, float w, float h, float ease) {
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(TEXT);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(22f));
        float cy = h * 0.46f + dp(14f) * (1f - ease);
        c.drawText("No rows yet", w / 2f, cy, paint);
        paint.setFakeBoldText(false);
        paint.setColor(FAINT);
        paint.setTextSize(dp(14f));
        c.drawText("finish a row and it will appear here", w / 2f, cy + dp(26f), paint);
        paint.setTextAlign(Paint.Align.LEFT);

        // A single breathing ring so the screen is never dead.
        float r = dp(54f) + dp(4f) * (float) Math.sin(pulse * 1.6);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor((accent & 0x00FFFFFF) | 0x40000000);
        c.drawCircle(w / 2f, cy - dp(92f), r, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    // ---------------------------------------------------------------- headline

    private void drawStats(Canvas c, float ease) {
        float n = 5f;
        float gap = dp(10f);
        float tileW = (statsRect.width() - gap * (n - 1)) / n;
        float x = statsRect.left;
        for (int i = 0; i < 5; i++) {
            rect.set(x, statsRect.top, x + tileW, statsRect.bottom);
            paint.setColor(SURFACE);
            c.drawRoundRect(rect, dp(10f), dp(10f), paint);
            paint.setColor(i == 4 ? (weekMetres >= lastWeekMetres ? accent : WARN) : accent);
            c.drawRect(rect.left, rect.top, rect.left + dp(3f), rect.bottom, paint);
            drawStatTile(c, rect, i, ease);
            x += tileW + gap;
        }
    }

    private void drawStatTile(Canvas c, RectF r, int index, float ease) {
        float lx = r.left + dp(14f);
        float valueY = r.centerY() + dp(4f);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(FAINT);
        paint.setTextSize(dp(10.5f));
        paint.setFakeBoldText(false);
        String caption;
        switch (index) {
            case 0: caption = RANGE_DISTANCE_CAPTIONS[range]; break;
            case 1: caption = "ROWING TIME"; break;
            case 2: caption = "SESSIONS"; break;
            case 3: caption = "BEST SPLIT"; break;
            default: caption = "THIS WEEK"; break;
        }
        c.drawText(caption, lx, r.top + dp(20f), paint);

        paint.setColor(TEXT);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(27f));
        sb.setLength(0);
        switch (index) {
            case 0:
                appendMetres(sb, rangeTotals.metres * ease);
                break;
            case 1:
                appendDuration(sb, Math.round(rangeTotals.seconds * ease));
                break;
            case 2:
                sb.append(Math.round(rangeTotals.sessions * ease));
                break;
            case 3:
                appendSplit(sb, rangeTotals.bestSplit);
                break;
            default:
                appendMetres(sb, weekMetres * ease);
                break;
        }
        drawBuf(c, lx, valueY);
        paint.setFakeBoldText(false);

        paint.setColor(DIM);
        paint.setTextSize(dp(11f));
        sb.setLength(0);
        switch (index) {
            case 0:
                sb.append("avg ");
                sb.append(Math.round(rangeTotals.avgWatts));
                sb.append(" W");
                break;
            case 1:
                sb.append(rangeTotals.activeDays);
                sb.append(rangeTotals.activeDays == 1 ? " active day" : " active days");
                break;
            case 2:
                sb.append(rangeTotals.strokes);
                sb.append(" strokes");
                break;
            case 3:
                sb.append("peak ");
                sb.append(Math.round(rangeTotals.peakWatts));
                sb.append(" W");
                break;
            default:
                if (lastWeekMetres <= 0f) {
                    sb.append("first week logged");
                } else {
                    int pct = Math.round((weekMetres - lastWeekMetres) / lastWeekMetres * 100f);
                    sb.append(pct >= 0 ? "+" : "");
                    sb.append(pct);
                    sb.append("% vs last week (");
                    appendMetres(sb, lastWeekMetres);
                    sb.append(')');
                }
                break;
        }
        drawBuf(c, lx, r.bottom - dp(14f));

        if (index == 4 && lastWeekMetres > 0f) {
            float barTop = r.bottom - dp(9f);
            float full = r.width() - dp(28f);
            float best = Math.max(weekMetres, lastWeekMetres);
            paint.setColor(TRACK);
            c.drawRect(lx, barTop, lx + full, barTop + dp(3f), paint);
            paint.setColor(weekMetres >= lastWeekMetres ? accent : WARN);
            c.drawRect(lx, barTop, lx + full * (weekMetres / best) * ease, barTop + dp(3f), paint);
        }
    }

    // ---------------------------------------------------------------- session list

    private void drawList(Canvas c) {
        paint.setColor(SURFACE);
        c.drawRoundRect(listRect, dp(10f), dp(10f), paint);

        paint.setColor(FAINT);
        paint.setTextSize(dp(10.5f));
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("SESSIONS", listRect.left + dp(14f), listRect.top + dp(20f), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        sb.setLength(0);
        sb.append(rows.size());
        sb.append(rows.size() == 1 ? " ROW" : " ROWS");
        drawBuf(c, listRect.right - dp(14f), listRect.top + dp(20f));
        paint.setTextAlign(Paint.Align.LEFT);

        float top = listRect.top + dp(30f);
        c.save();
        c.clipRect(listRect.left, top, listRect.right, listRect.bottom);
        float rh = rowHeight();
        int first = (int) Math.max(0, Math.floor(scrollY / rh));
        int last = (int) Math.min(rows.size() - 1, Math.ceil((scrollY + listRect.height()) / rh));
        for (int i = first; i <= last; i++) {
            Row row = rows.get(i);
            float y = top + i * rh - scrollY;
            if (y > listRect.bottom || y + rh < top) {
                continue;
            }
            // Stagger the rows in on open.
            float delay = Math.min(0.5f, (i - first) * 0.035f);
            float rowEase = easeOut(Math.max(0f, Math.min(1f, (anim - delay) / (1f - delay))));
            float slide = dp(24f) * (1f - rowEase);
            int alpha = (int) (255 * rowEase);
            boolean sel = i == selected;
            if (sel) {
                paint.setColor((accent & 0x00FFFFFF) | 0x24000000);
                c.drawRect(listRect.left, y, listRect.right, y + rh - dp(1f), paint);
                paint.setColor(accent);
                c.drawRect(listRect.left, y, listRect.left + dp(3f), y + rh - dp(1f), paint);
            } else if (i == downRow) {
                paint.setColor(SURFACE_ALT);
                c.drawRect(listRect.left, y, listRect.right, y + rh - dp(1f), paint);
            }
            paint.setColor(LINE);
            c.drawRect(listRect.left + dp(12f), y + rh - dp(1f), listRect.right - dp(12f), y + rh, paint);

            float lx = listRect.left + dp(16f) + slide;
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setFakeBoldText(true);
            paint.setTextSize(dp(13.5f));
            paint.setColor(withAlpha(sel ? accent : TEXT, alpha));
            c.drawText(row.date, lx, y + dp(22f), paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(dp(10.5f));
            paint.setColor(withAlpha(FAINT, alpha));
            c.drawText(row.sub, lx, y + dp(39f), paint);

            paint.setTextAlign(Paint.Align.RIGHT);
            float rx = listRect.right - dp(16f) + slide;
            paint.setFakeBoldText(true);
            paint.setTextSize(dp(15f));
            paint.setColor(withAlpha(TEXT, alpha));
            c.drawText(row.metres, rx, y + dp(23f), paint);
            paint.setFakeBoldText(false);
            paint.setTextSize(dp(10.5f));
            paint.setColor(withAlpha(DIM, alpha));
            c.drawText(row.stats, rx, y + dp(39f), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
        c.restore();

        if (rows.isEmpty()) {
            paint.setColor(FAINT);
            paint.setTextSize(dp(12.5f));
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("No rows in this range", listRect.centerX(), listRect.centerY(), paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        // Scroll indicator.
        if (maxScroll > 0f) {
            float trackTop = top + dp(4f);
            float trackH = listRect.bottom - trackTop - dp(6f);
            float thumbH = Math.max(dp(28f), trackH * (trackH / (trackH + maxScroll)));
            float ty = trackTop + (trackH - thumbH) * (scrollY / maxScroll);
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(listRect.right - dp(6f), ty, listRect.right - dp(3f), ty + thumbH,
                    dp(2f), dp(2f), paint);
        }
    }

    // ---------------------------------------------------------------- heatmap

    private void drawHeatmap(Canvas c) {
        float alphaScale = 1f - detailAnim;
        paint.setColor(withAlpha(SURFACE, (int) (255 * alphaScale)));
        c.drawRoundRect(heatRect, dp(10f), dp(10f), paint);

        paint.setColor(withAlpha(FAINT, (int) (255 * alphaScale)));
        paint.setTextSize(dp(10.5f));
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("LAST 12 WEEKS", heatRect.left + dp(14f), heatRect.top + dp(20f), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("DARKER TO BRIGHTER = MORE METRES", heatRect.right - dp(14f),
                heatRect.top + dp(20f), paint);
        paint.setTextAlign(Paint.Align.LEFT);

        float gridLeft = heatRect.left + dp(46f);
        float gridTop = heatRect.top + dp(32f);
        float gridRight = heatRect.right - dp(14f);
        float gridBottom = heatRect.bottom - dp(18f);
        float cellW = (gridRight - gridLeft) / HEAT_WEEKS;
        float cellH = (gridBottom - gridTop) / 7f;
        float cell = Math.min(cellW, cellH);
        float pad = Math.max(dp(1.5f), cell * 0.12f);

        paint.setTextSize(dp(9.5f));
        for (int d = 0; d < 7; d += 2) {
            paint.setColor(withAlpha(FAINT, (int) (200 * alphaScale)));
            c.drawText(DAY_NAMES[d], heatRect.left + dp(14f),
                    gridTop + d * cell + cell * 0.72f, paint);
        }

        for (int wk = 0; wk < HEAT_WEEKS; wk++) {
            if (heatMonth[wk] != null) {
                paint.setColor(withAlpha(FAINT, (int) (200 * alphaScale)));
                c.drawText(heatMonth[wk], gridLeft + wk * cell, gridBottom + dp(12f), paint);
            }
            for (int d = 0; d < 7; d++) {
                int index = wk * 7 + d;
                // Wave: sweeps left to right, with a slight downward lean.
                float delay = (wk / (float) HEAT_WEEKS) * 0.55f + (d / 7f) * 0.12f;
                float p = Math.max(0f, Math.min(1f, (anim - delay) / 0.3f));
                if (p <= 0f) {
                    continue;
                }
                float x = gridLeft + wk * cell;
                float y = gridTop + d * cell;
                float value = heat[index];
                float share = value <= 0f ? 0f : Math.min(1f, value / heatMax);
                int colour;
                if (share <= 0f) {
                    colour = TRACK;
                } else {
                    int a = (int) (60 + 195 * Math.sqrt(share));
                    colour = withAlpha(accent, a);
                }
                float grow = easeOut(p);
                float inset = pad + (cell - pad * 2f) * 0.5f * (1f - grow);
                paint.setColor(withAlpha(colour, (int) (Math.min(255, (colour >>> 24)) * alphaScale)));
                c.drawRoundRect(x + inset, y + inset, x + cell - inset, y + cell - inset,
                        dp(2.5f), dp(2.5f), paint);
                if (index == heatToday) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(1.6f));
                    float ring = 0.5f + 0.5f * (float) Math.sin(pulse * 2.4);
                    paint.setColor(withAlpha(TEXT, (int) ((120 + 120 * ring) * alphaScale)));
                    c.drawRoundRect(x + pad, y + pad, x + cell - pad, y + cell - pad,
                            dp(2.5f), dp(2.5f), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
        }
    }

    // ---------------------------------------------------------------- distance chart

    private void drawDistance(Canvas c, float ease) {
        float alphaScale = 1f - detailAnim;
        paint.setColor(withAlpha(SURFACE, (int) (255 * alphaScale)));
        c.drawRoundRect(distRect, dp(10f), dp(10f), paint);

        paint.setColor(withAlpha(FAINT, (int) (255 * alphaScale)));
        paint.setTextSize(dp(10.5f));
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText(barWeekly ? "METRES PER WEEK" : "METRES PER DAY",
                distRect.left + dp(14f), distRect.top + dp(20f), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        sb.setLength(0);
        sb.append("PEAK ");
        appendMetres(sb, barMax);
        drawBuf(c, distRect.right - dp(14f), distRect.top + dp(20f));
        paint.setTextAlign(Paint.Align.LEFT);

        float left = distRect.left + dp(48f);
        float right = distRect.right - dp(14f);
        float top = distRect.top + dp(30f);
        float bottom = distRect.bottom - dp(20f);
        if (barCount <= 0 || right <= left || bottom <= top) {
            return;
        }

        // Grid and axis.
        paint.setColor(withAlpha(LINE, (int) (255 * alphaScale)));
        paint.setStrokeWidth(dp(1f));
        for (int g = 0; g <= 2; g++) {
            float y = bottom - (bottom - top) * g / 2f;
            c.drawLine(left, y, right, y, paint);
            paint.setColor(withAlpha(FAINT, (int) (255 * alphaScale)));
            paint.setTextSize(dp(9.5f));
            paint.setTextAlign(Paint.Align.RIGHT);
            sb.setLength(0);
            appendMetres(sb, barMax * g / 2f);
            drawBuf(c, left - dp(6f), y + dp(3f));
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(withAlpha(LINE, (int) (255 * alphaScale)));
        }

        float slot = (right - left) / barCount;
        float barW = Math.max(dp(2f), slot * 0.62f);

        // Area under the trace.
        path.rewind();
        path.moveTo(left, bottom);
        for (int i = 0; i < barCount; i++) {
            float x = left + slot * (i + 0.5f);
            float v = barValue[i] * ease;
            float y = bottom - (bottom - top) * (v / barMax);
            path.lineTo(x, y);
        }
        path.lineTo(left + slot * (barCount - 0.5f), bottom);
        path.close();
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (140 * alphaScale));
        paint.setShader(areaShader);
        c.drawPath(path, paint);
        paint.setShader(null);
        paint.setAlpha(255);

        // Bars.
        for (int i = 0; i < barCount; i++) {
            float v = barValue[i];
            if (v <= 0f) {
                continue;
            }
            float delay = Math.min(0.45f, (i / (float) Math.max(1, barCount)) * 0.45f);
            float p = easeOut(Math.max(0f, Math.min(1f, (anim - delay) / (1f - delay))));
            float x = left + slot * (i + 0.5f);
            float y = bottom - (bottom - top) * (v / barMax) * p;
            paint.setColor(withAlpha(accent, (int) (220 * alphaScale)));
            c.drawRoundRect(x - barW / 2f, y, x + barW / 2f, bottom, dp(2f), dp(2f), paint);
        }

        // Trace over the bars.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(withAlpha(TEXT, (int) (200 * alphaScale)));
        path.rewind();
        for (int i = 0; i < barCount; i++) {
            float x = left + slot * (i + 0.5f);
            float y = bottom - (bottom - top) * (barValue[i] * ease / barMax);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);

        // Labels, thinned so they never collide.
        paint.setTextSize(dp(9.5f));
        paint.setTextAlign(Paint.Align.CENTER);
        int every = Math.max(1, (int) Math.ceil(dp(42f) / Math.max(1f, slot)));
        for (int i = 0; i < barCount; i += every) {
            if (barLabel[i] == null) {
                continue;
            }
            paint.setColor(withAlpha(FAINT, (int) (255 * alphaScale)));
            c.drawText(barLabel[i], left + slot * (i + 0.5f), bottom + dp(13f), paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ---------------------------------------------------------------- split chart

    private void drawSplit(Canvas c, float ease) {
        float alphaScale = 1f - detailAnim;
        paint.setColor(withAlpha(SURFACE, (int) (255 * alphaScale)));
        c.drawRoundRect(splitRect, dp(10f), dp(10f), paint);

        paint.setColor(withAlpha(FAINT, (int) (255 * alphaScale)));
        paint.setTextSize(dp(10.5f));
        paint.setTextAlign(Paint.Align.LEFT);
        c.drawText("AVERAGE SPLIT PER SESSION", splitRect.left + dp(14f),
                splitRect.top + dp(20f), paint);
        paint.setTextAlign(Paint.Align.RIGHT);
        c.drawText("FASTER IS HIGHER", splitRect.right - dp(14f), splitRect.top + dp(20f), paint);
        paint.setTextAlign(Paint.Align.LEFT);

        float left = splitRect.left + dp(48f);
        float right = splitRect.right - dp(14f);
        float top = splitRect.top + dp(32f);
        float bottom = splitRect.bottom - dp(20f);
        if (splitCount <= 0 || right <= left || bottom <= top) {
            paint.setColor(withAlpha(FAINT, (int) (255 * alphaScale)));
            paint.setTextSize(dp(12f));
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("No split data in this range", splitRect.centerX(), splitRect.centerY(), paint);
            paint.setTextAlign(Paint.Align.LEFT);
            return;
        }

        // INVERTED AXIS: the best (lowest) split is pinned to the top.
        float lo = splitBest - 2f;
        float hi = splitWorst + 2f;
        float span = Math.max(1f, hi - lo);

        paint.setStrokeWidth(dp(1f));
        for (int g = 0; g <= 2; g++) {
            float value = lo + span * g / 2f;
            float y = top + (bottom - top) * (value - lo) / span;
            paint.setColor(withAlpha(LINE, (int) (255 * alphaScale)));
            c.drawLine(left, y, right, y, paint);
            paint.setColor(withAlpha(FAINT, (int) (255 * alphaScale)));
            paint.setTextSize(dp(9.5f));
            paint.setTextAlign(Paint.Align.RIGHT);
            sb.setLength(0);
            appendSplit(sb, value);
            drawBuf(c, left - dp(6f), y + dp(3f));
            paint.setTextAlign(Paint.Align.LEFT);
        }

        float reveal = left + (right - left) * ease;
        c.save();
        c.clipRect(left - dp(6f), top - dp(10f), reveal + dp(1f), bottom + dp(10f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setColor(withAlpha(BLUE, (int) (255 * alphaScale)));
        path.rewind();
        for (int i = 0; i < splitCount; i++) {
            float x = splitCount == 1 ? (left + right) / 2f : left + (right - left) * splitX[i];
            float y = top + (bottom - top) * (splitValue[i] - lo) / span;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < splitCount; i++) {
            float x = splitCount == 1 ? (left + right) / 2f : left + (right - left) * splitX[i];
            float y = top + (bottom - top) * (splitValue[i] - lo) / span;
            boolean isSelected = selected >= 0 && splitRow[i] == selected;
            boolean isBest = i == splitBestIndex;
            paint.setColor(withAlpha(isSelected ? accent : BLUE, (int) (255 * alphaScale)));
            c.drawCircle(x, y, dp(isSelected ? 5f : 3f), paint);
            if (isBest || isSelected) {
                float ring = 0.5f + 0.5f * (float) Math.sin(pulse * (isSelected ? 4.2 : 2.6));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.8f));
                paint.setColor(withAlpha(isSelected ? accent : WARN,
                        (int) ((90 + 150 * ring) * alphaScale)));
                c.drawCircle(x, y, dp(7f) + dp(4f) * ring, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
        c.restore();

        // Best marker label.
        if (splitBestIndex >= 0) {
            float x = splitCount == 1 ? (left + right) / 2f
                    : left + (right - left) * splitX[splitBestIndex];
            float y = top + (bottom - top) * (splitValue[splitBestIndex] - lo) / span;
            paint.setColor(withAlpha(WARN, (int) (255 * alphaScale)));
            paint.setTextSize(dp(10f));
            paint.setFakeBoldText(true);
            paint.setTextAlign(x > right - dp(90f) ? Paint.Align.RIGHT : Paint.Align.LEFT);
            sb.setLength(0);
            sb.append("BEST ");
            appendSplit(sb, splitBest);
            drawBuf(c, x + (x > right - dp(90f) ? -dp(12f) : dp(12f)), y - dp(10f));
            paint.setFakeBoldText(false);
            paint.setTextAlign(Paint.Align.LEFT);
        }
    }

    // ---------------------------------------------------------------- session detail

    private void drawDetail(Canvas c) {
        if (selected < 0 || selected >= rows.size()) {
            return;
        }
        Row row = rows.get(selected);
        SessionLog.Session s = row.session;
        float p = easeOut(detailAnim);
        float slide = dp(26f) * (1f - p);
        int alpha = (int) (255 * p);

        c.save();
        c.translate(0f, slide);
        rect.set(chartsRect);
        paint.setColor(withAlpha(SURFACE, alpha));
        c.drawRoundRect(rect, dp(12f), dp(12f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(withAlpha(accent, (int) (alpha * 0.55f)));
        c.drawRoundRect(rect, dp(12f), dp(12f), paint);
        paint.setStyle(Paint.Style.FILL);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(19f));
        paint.setColor(withAlpha(TEXT, alpha));
        c.drawText(row.date, rect.left + dp(18f), rect.top + dp(32f), paint);
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(12f));
        paint.setColor(withAlpha(accent, alpha));
        c.drawText(row.detailSub, rect.left + dp(18f), rect.top + dp(52f), paint);

        // Close.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.2f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(withAlpha(downClose ? accent : DIM, alpha));
        float ccx = closeRect.centerX();
        float ccy = closeRect.centerY();
        c.drawLine(ccx - dp(8f), ccy - dp(8f), ccx + dp(8f), ccy + dp(8f), paint);
        c.drawLine(ccx + dp(8f), ccy - dp(8f), ccx - dp(8f), ccy + dp(8f), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // Numbers.
        float gridTop = rect.top + dp(66f);
        float cellW = (rect.width() - dp(36f)) / 4f;
        float cellH = dp(56f);
        for (int i = 0; i < 8; i++) {
            float x = rect.left + dp(18f) + (i % 4) * cellW;
            float y = gridTop + (i / 4) * cellH;
            paint.setColor(withAlpha(FAINT, alpha));
            paint.setTextSize(dp(10f));
            c.drawText(detailCaption(i), x, y + dp(12f), paint);
            paint.setColor(withAlpha(TEXT, alpha));
            paint.setFakeBoldText(true);
            paint.setTextSize(dp(20f));
            sb.setLength(0);
            detailValue(sb, s, i, p);
            drawBuf(c, x, y + dp(38f));
            paint.setFakeBoldText(false);
        }

        drawDetailChart(c, s, rect.left + dp(18f), gridTop + cellH * 2f + dp(10f),
                rect.right - dp(18f), rect.bottom - dp(24f), p, alpha);
        c.restore();
    }

    private static String detailCaption(int i) {
        switch (i) {
            case 0: return "DISTANCE";
            case 1: return "ROWING TIME";
            case 2: return "AVG SPLIT";
            case 3: return "AVG WATTS";
            case 4: return "PEAK WATTS";
            case 5: return "STROKES";
            case 6: return "STROKE RATE";
            default: return "CALORIES";
        }
    }

    private void detailValue(StringBuilder out, SessionLog.Session s, int i, float p) {
        switch (i) {
            case 0: appendMetres(out, s.metres * p); break;
            case 1: appendDuration(out, Math.round(s.durationSeconds * p)); break;
            case 2: appendSplit(out, s.avgSplit); break;
            case 3: out.append(Math.round(s.avgWatts * p)); out.append(" W"); break;
            case 4: out.append(Math.round(s.peakWatts * p)); out.append(" W"); break;
            case 5: out.append(Math.round(s.strokes * p)); break;
            case 6: appendTenths(out, s.strokeRate()); out.append(" spm"); break;
            default: out.append(Math.round(s.calories() * p)); out.append(" kcal"); break;
        }
    }

    /** The per-bucket series: split inverted against the left edge, watts filled behind it. */
    private void drawDetailChart(Canvas c, SessionLog.Session s, float left, float top,
                                 float right, float bottom, float p, int alpha) {
        if (bottom - top < dp(40f) || right - left < dp(80f)) {
            return;
        }
        paint.setColor(withAlpha(SURFACE_ALT, alpha));
        c.drawRoundRect(left, top, right, bottom, dp(8f), dp(8f), paint);

        int n = Math.min(s.splitSeries.length, s.wattsSeries.length);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(dp(10f));
        paint.setColor(withAlpha(FAINT, alpha));
        if (n < 2) {
            paint.setTextAlign(Paint.Align.CENTER);
            c.drawText("Too short for a trace", (left + right) / 2f, (top + bottom) / 2f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            return;
        }
        sb.setLength(0);
        sb.append("SPLIT AND WATTS EVERY ");
        sb.append(s.seriesBucketSeconds);
        sb.append(" S");
        drawBuf(c, left + dp(12f), top + dp(16f));

        float cl = left + dp(12f);
        float cr = right - dp(12f);
        float ct = top + dp(24f);
        float cb = bottom - dp(18f);

        int wMax = 1;
        int sLo = Integer.MAX_VALUE;
        int sHi = 0;
        for (int i = 0; i < n; i++) {
            if (s.wattsSeries[i] > wMax) {
                wMax = s.wattsSeries[i];
            }
            int v = s.splitSeries[i];
            if (v > 0) {
                if (v < sLo) {
                    sLo = v;
                }
                if (v > sHi) {
                    sHi = v;
                }
            }
        }
        if (sLo == Integer.MAX_VALUE) {
            sLo = 0;
            sHi = 1;
        }
        if (sHi - sLo < 4) {
            sHi = sLo + 4;
        }
        float step = (cr - cl) / Math.max(1, n - 1);
        float reveal = cl + (cr - cl) * p;

        c.save();
        c.clipRect(cl - dp(2f), ct - dp(4f), reveal + dp(1f), cb + dp(4f));

        // Watts as a filled band behind.
        path.rewind();
        path.moveTo(cl, cb);
        for (int i = 0; i < n; i++) {
            float x = cl + step * i;
            float y = cb - (cb - ct) * (s.wattsSeries[i] / (float) wMax) * 0.9f;
            path.lineTo(x, y);
        }
        path.lineTo(cl + step * (n - 1), cb);
        path.close();
        paint.setColor(withAlpha(WARN, (int) (alpha * 0.28f)));
        c.drawPath(path, paint);

        // Split, inverted: the fastest bucket is highest.
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(withAlpha(accent, alpha));
        path.rewind();
        boolean started = false;
        for (int i = 0; i < n; i++) {
            int v = s.splitSeries[i];
            if (v <= 0) {
                continue;
            }
            float x = cl + step * i;
            float y = ct + (cb - ct) * (v - sLo) / (float) (sHi - sLo);
            if (!started) {
                path.moveTo(x, y);
                started = true;
            } else {
                path.lineTo(x, y);
            }
        }
        if (started) {
            c.drawPath(path, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        c.restore();

        paint.setColor(withAlpha(accent, alpha));
        paint.setTextSize(dp(9.5f));
        sb.setLength(0);
        appendSplit(sb, sLo);
        drawBuf(c, cl, ct + dp(9f));
        paint.setColor(withAlpha(WARN, alpha));
        paint.setTextAlign(Paint.Align.RIGHT);
        sb.setLength(0);
        sb.append(wMax);
        sb.append(" W PEAK BUCKET");
        drawBuf(c, cr, ct + dp(9f));
        paint.setTextAlign(Paint.Align.LEFT);
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                if (velocity == null) {
                    velocity = VelocityTracker.obtain();
                } else {
                    velocity.clear();
                }
                velocity.addMovement(event);
                downX = x;
                downY = y;
                dragging = false;
                downBack = backRect.contains(x, y);
                downChip = -1;
                downClose = detailOpen && closeRect.contains(x, y);
                for (int i = 0; i < chipRect.length; i++) {
                    if (chipRect[i].contains(x, y)) {
                        downChip = i;
                    }
                }
                downRow = rowAt(x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                if (velocity != null) {
                    velocity.addMovement(event);
                }
                if (!dragging && Math.abs(y - downY) > touchSlop
                        && listRect.contains(downX, downY)) {
                    dragging = true;
                    downRow = -1;
                    downBack = false;
                    downChip = -1;
                    downClose = false;
                }
                if (dragging) {
                    float dy = y - downY;
                    downY = y;
                    applyScroll(dy);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (velocity != null) {
                    velocity.addMovement(event);
                    velocity.computeCurrentVelocity(1000);
                }
                if (dragging) {
                    float vy = velocity == null ? 0f : velocity.getYVelocity();
                    if (Math.abs(vy) > dp(120f)) {
                        scroller.fling(0, (int) scrollY, 0, (int) -vy, 0, 0, 0, (int) maxScroll);
                    }
                } else {
                    handleTap(x, y);
                    // Keeps the accessibility click event (and lint) happy on a clickable view
                    // that does all its own hit testing.
                    performClick();
                }
                releaseTouch();
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                releaseTouch();
                invalidate();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void applyScroll(float dy) {
        scrollY -= dy;
        clampScroll();
    }

    private void releaseTouch() {
        dragging = false;
        downRow = -1;
        downBack = false;
        downChip = -1;
        downClose = false;
        if (velocity != null) {
            velocity.recycle();
            velocity = null;
        }
    }

    private void handleTap(float x, float y) {
        if (backRect.contains(x, y)) {
            if (detailOpen) {
                detailOpen = false;
            } else if (backListener != null) {
                backListener.run();
            }
            return;
        }
        for (int i = 0; i < chipRect.length; i++) {
            if (chipRect[i].contains(x, y)) {
                setRange(i);
                return;
            }
        }
        if (detailOpen && closeRect.contains(x, y)) {
            detailOpen = false;
            return;
        }
        int row = rowAt(x, y);
        if (row >= 0) {
            if (row == selected && detailOpen) {
                detailOpen = false;
            } else {
                selected = row;
                detailOpen = true;
                detailAnim = 0f;
            }
            return;
        }
        if (detailOpen && !chartsRect.contains(x, y)) {
            detailOpen = false;
        }
    }

    private int rowAt(float x, float y) {
        float top = listRect.top + dp(30f);
        if (x < listRect.left || x > listRect.right || y < top || y > listRect.bottom) {
            return -1;
        }
        int index = (int) ((y - top + scrollY) / rowHeight());
        return index >= 0 && index < rows.size() ? index : -1;
    }

    // ---------------------------------------------------------------- text helpers

    /**
     * A calendar the device's locale supplies is not guaranteed to be the Gregorian one, and a
     * thirteenth month would be an array index crash on a screen that only reads dates.
     */
    private static String monthName(int month) {
        return month >= 0 && month < MONTHS.length ? MONTHS[month] : "";
    }

    private static int withAlpha(int argb, int alpha) {
        if (alpha < 0) {
            alpha = 0;
        } else if (alpha > 255) {
            alpha = 255;
        }
        return (argb & 0x00FFFFFF) | (alpha << 24);
    }

    /** Draw and clear {@link #sb} without allocating a String. */
    private void drawBuf(Canvas c, float x, float y) {
        int n = sb.length();
        if (n > cbuf.length) {
            n = cbuf.length;
        }
        sb.getChars(0, n, cbuf, 0);
        c.drawText(cbuf, 0, n, x, y, paint);
        sb.setLength(0);
    }

    /** "12.34 km" or "840 m", built with integer maths so nothing is boxed or allocated. */
    private static void appendMetres(StringBuilder out, float metres) {
        if (metres < 0f) {
            metres = 0f;
        }
        if (metres >= 1000f) {
            int hundredths = Math.round(metres / 10f);
            out.append(hundredths / 100);
            out.append('.');
            int frac = hundredths % 100;
            if (frac < 10) {
                out.append('0');
            }
            out.append(frac);
            out.append(" km");
        } else {
            out.append(Math.round(metres));
            out.append(" m");
        }
    }

    private static void appendDuration(StringBuilder out, int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            out.append(h);
            out.append(':');
            if (m < 10) {
                out.append('0');
            }
        }
        out.append(m);
        out.append(':');
        if (s < 10) {
            out.append('0');
        }
        out.append(s);
    }

    private static void appendSplit(StringBuilder out, float secondsPer500) {
        if (secondsPer500 <= 0f || secondsPer500 > 900f) {
            out.append("--:--");
            return;
        }
        int total = Math.round(secondsPer500);
        out.append(total / 60);
        out.append(':');
        int s = total % 60;
        if (s < 10) {
            out.append('0');
        }
        out.append(s);
    }

    private static void appendTenths(StringBuilder out, float value) {
        int tenths = Math.round(Math.abs(value) * 10f);
        if (value < 0f) {
            out.append('-');
        }
        out.append(tenths / 10);
        out.append('.');
        out.append(tenths % 10);
    }

    /** One list row, with every string built once. */
    private static final class Row {
        SessionLog.Session session;
        String date;
        String time;
        String game;
        String metres;
        String duration;
        String split;
        String watts;
        /** "07:42  HEAD RACE" and "22:31   2:08 /500m   131 W", joined once. */
        String sub;
        String stats;
        /** The detail panel's "07:42   HEAD RACE", joined once so onDraw allocates nothing. */
        String detailSub;
    }
}
