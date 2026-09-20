package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * The coach layer over the gauges: the five things the rower asked for that the dials alone cannot
 * say.
 *
 * <ol>
 *   <li><b>ON FOR A RECORD</b> - a banner at the top while the split being held (the rolling last
 *       500 m once there is one, the piece average before that) is under the best 500 m the rower
 *       has ever held AND the split on the dial is still under it. Ease off and it goes, which is
 *       the point.</li>
 *   <li><b>Negative split</b> - at the halfway of a real distance (500 m of a 1000, 1000 m of a
 *       2000, 2500 m of a 5000) a card gives the second-half target, then a live chip says whether
 *       the second half is up or down on the first, and the full distance gets a verdict.</li>
 *   <li><b>Effort bank</b> - fills while the power on the dial is above the rower's own typical
 *       watts, drains while below. Full, it can be spent on a POWER MINUTE: sixty seconds against a
 *       target line with a live count of the seconds held.</li>
 *   <li><b>Warm-up</b> - three minutes of steady rowing at the rower's own rate and power brings up
 *       an offer of a timed piece; a tap opens it.</li>
 *   <li><b>Weekly goal ring</b> - this week's minutes against the goal, closing while you row, with
 *       a flare the moment it closes.</li>
 * </ol>
 *
 * <p>Everything is drawn: the view is not clickable, and {@link #onTouchEvent} claims a touch only
 * when it lands on a live button, so every other tap falls through to the gauges underneath.
 * Nothing is allocated in {@link #onDraw} - text is rebuilt only when the number behind it changes.
 */
final class GaugeCoachView extends View {

    /** What the coach needs from the app: the rower's own numbers, and two records. */
    interface Host {
        /** Best 500 m the rower has held, in seconds, or 0 when there is none. */
        float bestSplitSeconds();

        /** A new best 500 m. */
        void saveBestSplit(float seconds);

        /** @return whether this power minute beat the best one */
        boolean savePowerMinute(float averageWatts);

        double typicalWatts();

        /** The power a hard minute should be held at - the top of the rower's own range. */
        double pushWatts();

        double typicalRate();

        /** Opens a timed piece of this many minutes. */
        void openPiece(int minutes);
    }

    /* Halfway marks, and the distance each one is the half of. */
    private static final float[] HALF_AT = {500f, 1000f, 2500f};
    private static final float[] HALF_OF = {1000f, 2000f, 5000f};
    /** A second-half split this much faster than the first half is the target. */
    private static final float NEGATIVE_SPLIT_GAIN = 2f;
    private static final int MARKS = 64;          // one every 10 m: 640 m, enough for a 500 m window
    private static final int MARK_METRES = 10;
    private static final int WINDOW_MARKS = 500 / MARK_METRES;
    private static final float POWER_MINUTE_SECONDS = 60f;
    private static final float WARM_UP_SECONDS = 180f;
    /** Rowing stops counting as one piece after this long without a stroke. */
    private static final float PIECE_GAP_SECONDS = 12f;

    private final Host host;

    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint value = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final RectF arc = new RectF();

    private final RectF bankRect = new RectF();
    private final RectF warmTenRect = new RectF();
    private final RectF warmTwentyRect = new RectF();
    private final RectF warmCloseRect = new RectF();
    private final RectF negRect = new RectF();

    private final int good = Color.parseColor("#35D0BA");
    private final int warn = Color.parseColor("#F0B132");
    private final int bad = Color.parseColor("#F0655D");
    private final int blue = Color.parseColor("#6F8CFF");
    private final int purple = Color.parseColor("#B48CFF");
    private final int text = Color.parseColor("#E6EDF7");
    private final int dim = Color.parseColor("#8D9BB0");
    private final int track = Color.parseColor("#18202C");

    /* ---------- the piece in progress ---------- */
    private boolean pieceOn;
    private float pieceSeconds;
    private float pieceMetres;
    private float pieceStartMetres = -1;
    private float idleSeconds;
    private int lastMark = -1;
    private final float[] markSeconds = new float[MARKS];

    /* ---------- record watch ---------- */
    private float bestSplit;
    private float rollingSplit;
    private float projectedSplit;
    private boolean onForRecord;
    private float onForRecordAge = 99f;
    private float recordSetAge = 99f;
    private String recordLine = "";
    private int recordLineKey = Integer.MIN_VALUE;
    private String recordSetText = "";

    /* ---------- negative split ---------- */
    private int negIndex;
    private boolean negArmed;
    private float negFirstHalfSeconds;
    private float negFirstSplit;
    private float negTargetSplit;
    private float negSecondSplit;
    private float negCardAge = 99f;
    private float negVerdictAge = 99f;
    private boolean negVerdictWon;
    private float negVerdictBy;
    private String negTitle = "";
    private String negTargetText = "";
    private String negChipTarget = "";
    private String negPaceText = "--:--";
    private String negLiveText = "";
    private int negLiveKey = Integer.MIN_VALUE;
    private String negSplitText = "--:--";
    private int negSplitKey = Integer.MIN_VALUE;

    /* ---------- effort bank and the power minute ---------- */
    private float bank;
    private float bankShown;
    private float minuteLeft;
    private float minuteWattSum;
    private float minuteHeldSeconds;
    private float minuteResultAge = 99f;
    private float minuteResultWatts;
    private boolean minuteResultBest;
    private String minuteClock = "";
    private int minuteClockKey = Integer.MIN_VALUE;
    private String minuteHeldText = "";
    private int minuteHeldKey = Integer.MIN_VALUE;
    private String minuteTargetText = "";
    private int minuteTargetKey = Integer.MIN_VALUE;
    private String minuteResultText = "";
    private String minuteResultHeld = "";
    private String liveWattsText = "";
    private int liveWattsKey = Integer.MIN_VALUE;
    private String bankPctText = "0%";
    private int bankPctKey = Integer.MIN_VALUE;
    private String bankHintText = "";
    private int bankHintKey = Integer.MIN_VALUE;
    private String warmSteadyText = "";
    private int warmSteadyKey = Integer.MIN_VALUE;

    /* ---------- warm-up offer ---------- */
    private float steadySeconds;
    private float warmOfferAge = -1;      // negative: no offer on screen
    private float warmNextOfferAt = WARM_UP_SECONDS;

    /* ---------- weekly ring ---------- */
    private float weekMinutes;
    private float weekGoal = 60f;
    private int weekStreak;
    private float weekShown;
    private boolean weekClosed;
    private float weekFlareAge = 99f;
    private String weekText = "";
    private int weekTextKey = Integer.MIN_VALUE;
    private String weekStreakText = "WEEK MIN";
    private int weekStreakKey = Integer.MIN_VALUE;

    /** How far the dock is lifted so it clears the Diagnostics button underneath. */
    private float bottomInset;

    private float shownWatts;
    private float shownPace;
    private boolean rowing;
    private float clock;                  // seconds since the view woke, for the animations
    private long lastStatusMs;
    private long lastFrameMs;

    GaugeCoachView(Context context, Host host) {
        super(context);
        this.host = host;
        setClickable(false);
        setFocusable(false);
        card.setColor(Color.argb(232, 15, 21, 31));
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        big.setFakeBoldText(true);
        big.setTextAlign(Paint.Align.CENTER);
        label.setFakeBoldText(true);
        label.setLetterSpacing(0.14f);
        value.setFakeBoldText(true);
        value.setTypeface(android.graphics.Typeface.MONOSPACE);
        bestSplit = host.bestSplitSeconds();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /* ================= feeding ================= */

    /**
     * A reading. {@code shownPace} and {@code shownWatts} are the eased figures on the tiles, not
     * the raw poll, so the coach judges exactly what the rower is looking at.
     */
    void onStatus(S4Protocol.Status status, float shownPace, float shownWatts) {
        long now = System.currentTimeMillis();
        float dt = lastStatusMs > 0 ? Math.min(0.5f, (now - lastStatusMs) / 1000f) : 0f;
        lastStatusMs = now;
        this.shownPace = shownPace;
        this.shownWatts = shownWatts;
        rowing = status.stillRowing;

        float metres = status.distanceMeters > 0 ? status.distanceMeters : status.derivedDistanceMeters;
        trackPiece(metres, dt);
        trackBank(dt);
        trackPowerMinute(dt);
        trackWarmUp(status, dt);
        invalidate();
    }

    /**
     * How much room to leave at the bottom. The dock would otherwise sit on top of the Diagnostics
     * button, hiding it and - while the bank is full - swallowing taps meant for it.
     */
    void setBottomInset(float px) {
        float want = Math.max(0f, px);
        if (Math.abs(want - bottomInset) > 0.5f) {
            bottomInset = want;
            invalidate();
        }
    }

    /** Once a second from the app's tick: this week's minutes against the goal. */
    void setWeek(float minutes, float goal, int streak) {
        weekMinutes = minutes;
        weekGoal = Math.max(1f, goal);
        weekStreak = streak;
        if (!weekClosed && weekMinutes >= weekGoal) {
            weekClosed = true;
            weekFlareAge = 0f;
        }
        invalidate();
    }

    private void trackPiece(float metres, float dt) {
        if (!rowing) {
            idleSeconds += dt;
            if (pieceOn && idleSeconds > PIECE_GAP_SECONDS) {
                endPiece();
            }
            return;
        }
        idleSeconds = 0f;
        if (!pieceOn || pieceStartMetres < 0 || metres < pieceStartMetres) {
            // First stroke of a piece, or the monitor's counter was reset under us.
            pieceOn = true;
            pieceStartMetres = metres;
            pieceSeconds = 0f;
            pieceMetres = 0f;
            lastMark = -1;
            rollingSplit = 0f;
            negIndex = 0;
            negArmed = false;
            negVerdictAge = 99f;
            bestSplit = host.bestSplitSeconds();
        }
        pieceSeconds += dt;
        pieceMetres = Math.max(0f, metres - pieceStartMetres);

        int mark = (int) (pieceMetres / MARK_METRES);
        if (mark > lastMark) {
            // A lumped distance read can jump hundreds of marks at once; filling more than one turn
            // of the ring only overwrites what it just wrote, so stop at one turn.
            int from = Math.max(lastMark + 1, mark - MARKS + 1);
            for (int m = from; m <= mark; m++) {
                markSeconds[((m % MARKS) + MARKS) % MARKS] = pieceSeconds;
            }
            lastMark = mark;
            checkRollingSplit(mark);
        }
        projectedSplit = pieceMetres > 1 ? pieceSeconds / pieceMetres * 500f : 0f;
        trackRecordWatch();
        trackNegativeSplit();
    }

    /** The last 500 m as a split, and a new best when it is one. */
    private void checkRollingSplit(int mark) {
        if (mark < WINDOW_MARKS) {
            rollingSplit = 0f;
            return;
        }
        float then = markSeconds[(((mark - WINDOW_MARKS) % MARKS) + MARKS) % MARKS];
        float split = pieceSeconds - then;
        if (split < 60f || split > 600f) {
            return;         // not a plausible 500 m: a lumped distance read, or a crawl
        }
        rollingSplit = split;
        if (bestSplit <= 0 || split < bestSplit) {
            bestSplit = split;
            host.saveBestSplit(split);
            recordSetAge = 0f;
            recordSetText = PersonalBests.formatPace(bestSplit) + " /500";
            recordLineKey = Integer.MIN_VALUE;
        }
    }

    /**
     * The banner is on while the split being held is under the best 500 m on record and the split on
     * the dial right now is still under it. Past 500 m the figure watched is the rolling last 500 m -
     * the same thing the record is measured over - and before that it is the piece's own average.
     */
    private void trackRecordWatch() {
        boolean was = onForRecord;
        float live = shownPace;
        float watch = rollingSplit > 0 ? rollingSplit : projectedSplit;
        onForRecord = bestSplit > 0 && pieceMetres >= 150f && watch > 0 && watch < bestSplit
                && live > 30f && live < bestSplit + 2f;
        if (onForRecord && !was) {
            onForRecordAge = 0f;
        }
        if (onForRecord) {
            int key = Math.round(watch * 10f) * 1000 + Math.round(bestSplit);
            if (key != recordLineKey) {
                recordLineKey = key;
                recordLine = PersonalBests.formatPace(watch) + " /500   ·   BEST "
                        + PersonalBests.formatPace(bestSplit);
            }
        }
    }

    private void trackNegativeSplit() {
        if (!negArmed && negIndex < HALF_AT.length && pieceMetres >= HALF_AT[negIndex]) {
            negArmed = true;
            negFirstHalfSeconds = pieceSeconds;
            negFirstSplit = pieceSeconds / HALF_AT[negIndex] * 500f;
            negTargetSplit = Math.max(60f, negFirstSplit - NEGATIVE_SPLIT_GAIN);
            negCardAge = 0f;
            negSecondSplit = 0f;
            negLiveKey = Integer.MIN_VALUE;
            negTitle = "HALFWAY OF " + Math.round(HALF_OF[negIndex]) + " m";
            negTargetText = "SECOND HALF AT " + PersonalBests.formatPace(negTargetSplit)
                    + "  ·  FIRST HALF " + PersonalBests.formatPace(negFirstSplit);
            negChipTarget = "TARGET " + PersonalBests.formatPace(negTargetSplit);
            negLiveText = "";
            negSplitText = "--:--";
            negSplitKey = Integer.MIN_VALUE;
            negPaceText = PersonalBests.formatPace(negTargetSplit);
        }
        if (!negArmed) {
            return;
        }
        float half = HALF_AT[negIndex];
        float run = pieceMetres - half;
        if (run > 5f) {
            negSecondSplit = (pieceSeconds - negFirstHalfSeconds) / run * 500f;
            int key = Math.round((negSecondSplit - negFirstSplit) * 10f);
            if (key != negLiveKey) {
                negLiveKey = key;
                float delta = negFirstSplit - negSecondSplit;
                negLiveText = (delta >= 0 ? "UP " : "DOWN ") + String.format(java.util.Locale.US,
                        "%.1f s", Math.abs(delta)) + " ON THE FIRST HALF";
            }
            int splitKey = Math.round(negSecondSplit);
            if (splitKey != negSplitKey) {
                negSplitKey = splitKey;
                negSplitText = PersonalBests.formatPace(negSecondSplit) + " /500";
            }
        }
        if (pieceMetres >= HALF_OF[negIndex]) {
            negVerdictBy = negFirstSplit - negSecondSplit;
            negVerdictWon = negVerdictBy >= NEGATIVE_SPLIT_GAIN;
            negVerdictAge = 0f;
            negArmed = false;
            negIndex++;
        }
    }

    private void endPiece() {
        pieceOn = false;
        pieceStartMetres = -1;
        negArmed = false;
        onForRecord = false;
        steadySeconds = 0f;
        warmNextOfferAt = WARM_UP_SECONDS;
        if (warmOfferAge >= 0) {
            warmOfferAge = -1;
        }
    }

    /**
     * The bank fills on power above the rower's own typical watts and drains below it, so a full
     * bank always means roughly a minute of genuinely hard work - whatever "hard" is for them.
     */
    private void trackBank(float dt) {
        if (minuteLeft > 0) {
            return;         // the bank is already spent and running
        }
        double typical = Math.max(40, host.typicalWatts());
        double rel = (shownWatts - typical) / typical;
        if (!rowing) {
            rel = Math.min(rel, -0.15);
        }
        bank += (float) (dt * (rel > 0 ? rel * 0.05 : rel * 0.02));
        bank = Math.max(0f, Math.min(1f, bank));
    }

    private void trackPowerMinute(float dt) {
        if (minuteLeft <= 0) {
            return;
        }
        minuteLeft -= dt;
        minuteWattSum += shownWatts * dt;
        if (shownWatts >= host.pushWatts()) {
            minuteHeldSeconds += dt;
        }
        if (minuteLeft <= 0) {
            minuteLeft = 0;
            float seconds = Math.max(1f, POWER_MINUTE_SECONDS);
            minuteResultWatts = minuteWattSum / seconds;
            minuteResultBest = host.savePowerMinute(minuteResultWatts);
            minuteResultAge = 0f;
            minuteResultText = Math.round(minuteResultWatts) + " W";
            minuteResultHeld = Math.round(minuteHeldSeconds) + " of 60 s at or above target";
        }
    }

    private void trackWarmUp(S4Protocol.Status status, float dt) {
        if (!rowing) {
            return;
        }
        double typicalW = Math.max(40, host.typicalWatts());
        double typicalR = Math.max(16, host.typicalRate());
        boolean steady = shownWatts >= typicalW * 0.7
                && Math.abs(status.strokeRatePrecise - typicalR) <= 6;
        steadySeconds += steady ? dt : -dt * 0.5f;
        steadySeconds = Math.max(0f, steadySeconds);
        if (warmOfferAge < 0 && steadySeconds >= warmNextOfferAt) {
            warmOfferAge = 0f;
        }
    }

    /* ================= touch ================= */

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            return hit(x, y) != 0;
        }
        if (event.getAction() != MotionEvent.ACTION_UP) {
            return false;
        }
        int what = hit(x, y);
        switch (what) {
            case 1:
                bank = 0f;
                minuteLeft = POWER_MINUTE_SECONDS;
                minuteWattSum = 0f;
                minuteHeldSeconds = 0f;
                minuteResultAge = 99f;
                minuteClockKey = Integer.MIN_VALUE;
                minuteHeldKey = Integer.MIN_VALUE;
                invalidate();
                return true;
            case 2:
                warmOfferAge = -1;
                host.openPiece(10);
                return true;
            case 3:
                warmOfferAge = -1;
                host.openPiece(20);
                return true;
            case 4:
                warmOfferAge = -1;
                warmNextOfferAt = steadySeconds + 600f;
                invalidate();
                return true;
            case 5:
                negCardAge = 99f;       // dismiss the big card early; the chip stays
                invalidate();
                return true;
            default:
                return false;
        }
    }

    /** 0 when the touch belongs to the gauges underneath. */
    private int hit(float x, float y) {
        if (bank >= 1f && minuteLeft <= 0 && bankRect.contains(x, y)) {
            return 1;
        }
        if (warmOfferAge >= 0) {
            if (warmCloseRect.contains(x, y)) {
                return 4;
            }
            if (warmTenRect.contains(x, y)) {
                return 2;
            }
            if (warmTwentyRect.contains(x, y)) {
                return 3;
            }
        }
        if (negCardAge < 7f && negRect.contains(x, y)) {
            return 5;
        }
        return 0;
    }

    /* ================= drawing ================= */

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        float dt = lastFrameMs > 0 && now - lastFrameMs < 400 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;
        clock += dt;
        onForRecordAge += dt;
        recordSetAge += dt;
        negCardAge += dt;
        negVerdictAge += dt;
        minuteResultAge += dt;
        weekFlareAge += dt;
        if (warmOfferAge >= 0) {
            warmOfferAge += dt;
        }
        bankShown += (bank - bankShown) * Math.min(1f, dt * 4f);
        float weekShare = Math.min(1.35f, weekMinutes / weekGoal);
        weekShown += (weekShare - weekShown) * Math.min(1f, dt * 3f);

        float dockW = Math.min(w - dp(32f), dp(560f));
        float dockH = dp(104f);
        float dockRight = w - dp(16f);
        // Lifted clear of the Diagnostics button: it must stay visible and tappable underneath.
        float dockBottom = Math.max(dockH + dp(12f), h - dp(12f) - bottomInset);
        float dockTop = dockBottom - dockH;
        rect.set(dockRight - dockW, dockTop, dockRight, dockBottom);
        // drawDock hands `rect` on to the bank, which reuses it - so keep the top in a local.
        drawDock(c, rect);

        float stackBottom = dockTop - dp(10f);
        if (negArmed || negVerdictAge < 4f) {
            float cw = Math.min(w - dp(32f), dp(430f));
            float chipTop = stackBottom - dp(46f);
            rect.set(dockRight - cw, chipTop, dockRight, stackBottom);
            drawNegChip(c, rect);
            stackBottom = chipTop - dp(8f);
        }
        if (warmOfferAge >= 0) {
            float cw = Math.min(w - dp(32f), dp(430f));
            rect.set(dockRight - cw, stackBottom - dp(128f), dockRight, stackBottom);
            drawWarmUp(c, rect);
        }

        if (onForRecord || recordSetAge < 4f) {
            drawRecordBanner(c, w);
        }
        if (negCardAge < 7f) {
            drawNegCard(c, w, h);
        } else {
            negRect.setEmpty();
        }

        if (needsFrames()) {
            postInvalidateOnAnimation();
        } else {
            lastFrameMs = 0;
        }
    }

    private boolean needsFrames() {
        // A dead link leaves `rowing` stuck at whatever the last reading said; without this the
        // coach would spin at 60 fps for the rest of the session with nothing to show.
        boolean live = rowing && System.currentTimeMillis() - lastStatusMs < 3000;
        return live || minuteLeft > 0 || onForRecord || recordSetAge < 4f || negCardAge < 7f
                || negVerdictAge < 4f || minuteResultAge < 6f || weekFlareAge < 4f
                || warmOfferAge >= 0 || Math.abs(bank - bankShown) > 0.002f;
    }

    /* ---------- 1: the record banner ---------- */

    private void drawRecordBanner(Canvas c, float w) {
        boolean set = recordSetAge < 4f;
        float in = Math.min(1f, (set ? recordSetAge : onForRecordAge) / 0.3f);
        float out = set && recordSetAge > 3.4f ? Math.max(0f, 1f - (recordSetAge - 3.4f) / 0.6f) : 1f;
        float a = in * out;
        float bw = Math.min(w - dp(24f), dp(440f));
        float cx = w / 2f;
        float top = dp(8f) + (1f - in) * -dp(16f);
        rect.set(cx - bw / 2f, top, cx + bw / 2f, top + dp(46f));
        card.setAlpha((int) (232 * a));
        c.drawRoundRect(rect, dp(10f), dp(10f), card);

        int accent = set ? warn : good;
        // A moving edge of light, so the banner reads as live rather than a label.
        float pulse = 0.55f + 0.45f * (float) Math.sin(clock * 3.2);
        stroke.setColor(accent);
        stroke.setAlpha((int) (255 * a * (0.4f + 0.6f * pulse)));
        stroke.setStrokeWidth(dp(2f));
        c.drawRoundRect(rect, dp(10f), dp(10f), stroke);

        // Chevrons running toward the finish.
        fill.setColor(accent);
        for (int i = 0; i < 3; i++) {
            float t = (clock * 0.9f + i * 0.33f) % 1f;
            fill.setAlpha((int) (200 * a * (1f - Math.abs(t - 0.5f) * 2f)));
            float cxx = rect.left + dp(16f) + t * dp(26f);
            float cyy = rect.centerY();
            c.drawRect(cxx, cyy - dp(6f), cxx + dp(3f), cyy + dp(6f), fill);
        }

        label.setColor(accent);
        label.setAlpha((int) (255 * a));
        label.setTextAlign(Paint.Align.LEFT);
        label.setTextSize(dp(14f));
        c.drawText(set ? "NEW 500 m RECORD" : "ON FOR A RECORD", rect.left + dp(52f),
                rect.centerY() + dp(5f), label);
        value.setColor(text);
        value.setAlpha((int) (255 * a));
        value.setTextAlign(Paint.Align.RIGHT);
        value.setTextSize(dp(15f));
        c.drawText(set ? recordSetText : recordLine, rect.right - dp(16f), rect.centerY() + dp(5f), value);
    }

    /* ---------- 2: the negative split ---------- */

    private void drawNegCard(Canvas c, float w, float h) {
        float in = Math.min(1f, negCardAge / 0.3f);
        float out = negCardAge > 6.3f ? Math.max(0f, 1f - (negCardAge - 6.3f) / 0.7f) : 1f;
        float a = in * out;
        float cw = Math.min(w - dp(60f), dp(620f));
        float ch = dp(170f);
        float cx = w / 2f;
        float cy = h * 0.42f + (1f - in) * dp(24f);
        negRect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f);
        card.setAlpha((int) (236 * a));
        c.drawRoundRect(negRect, dp(14f), dp(14f), card);
        stroke.setColor(blue);
        stroke.setAlpha((int) (200 * a));
        stroke.setStrokeWidth(dp(2f));
        c.drawRoundRect(negRect, dp(14f), dp(14f), stroke);

        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(blue);
        label.setAlpha((int) (255 * a));
        label.setTextSize(dp(13f));
        c.drawText(negTitle, cx, negRect.top + dp(30f), label);

        big.setColor(text);
        big.setAlpha((int) (255 * a));
        big.setTextSize(dp(40f) * (0.86f + 0.14f * in));
        c.drawText(negPaceText, cx, negRect.top + dp(84f), big);

        label.setColor(dim);
        label.setAlpha((int) (255 * a));
        label.setTextSize(dp(11f));
        c.drawText(negTargetText, cx, negRect.top + dp(112f), label);
        c.drawText("GO OUT HARDER THAN YOU CAME IN  ·  TAP TO CLOSE", cx, negRect.bottom - dp(20f), label);
        label.setTextAlign(Paint.Align.LEFT);
    }

    private void drawNegChip(Canvas c, RectF r) {
        boolean verdict = negVerdictAge < 4f;
        float delta = verdict ? negVerdictBy : negFirstSplit - negSecondSplit;
        int accent = verdict ? (negVerdictWon ? good : bad) : delta >= 0 ? good : warn;
        card.setAlpha(232);
        c.drawRoundRect(r, dp(10f), dp(10f), card);
        // A bar from the middle: right of centre means the second half is the faster one.
        float mid = r.centerX();
        float span = (r.width() - dp(28f)) / 2f;
        fill.setColor(track);
        fill.setAlpha(255);
        c.drawRect(r.left + dp(14f), r.bottom - dp(11f), r.right - dp(14f), r.bottom - dp(7f), fill);
        float reach = Math.max(-1f, Math.min(1f, delta / 6f)) * span;
        fill.setColor(accent);
        if (reach >= 0) {
            c.drawRect(mid, r.bottom - dp(12f), mid + reach, r.bottom - dp(6f), fill);
        } else {
            c.drawRect(mid + reach, r.bottom - dp(12f), mid, r.bottom - dp(6f), fill);
        }
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(accent);
        label.setAlpha(255);
        label.setTextSize(dp(11f));
        c.drawText(verdict ? (negVerdictWon ? "NEGATIVE SPLIT" : "MISSED THE SECOND HALF")
                : "SECOND HALF", r.left + dp(14f), r.top + dp(19f), label);
        value.setTextAlign(Paint.Align.RIGHT);
        value.setColor(text);
        value.setAlpha(255);
        value.setTextSize(dp(13f));
        c.drawText(negSplitText, r.right - dp(14f), r.top + dp(20f), value);
        label.setColor(dim);
        label.setTextSize(dp(10f));
        c.drawText(negLiveText.isEmpty() ? negChipTarget : negLiveText,
                r.left + dp(14f), r.top + dp(34f), label);
    }

    /* ---------- 3 and 5: the dock ---------- */

    private void drawDock(Canvas c, RectF r) {
        card.setAlpha(228);
        c.drawRoundRect(r, dp(12f), dp(12f), card);

        float ringR = dp(34f);
        float ringCx = r.right - dp(22f) - ringR;
        drawWeekRing(c, ringCx, r.centerY(), ringR);

        bankRect.set(r.left + dp(12f), r.top + dp(10f), ringCx - ringR - dp(28f), r.bottom - dp(10f));
        if (minuteLeft > 0) {
            drawPowerMinute(c, bankRect);
        } else if (minuteResultAge < 6f) {
            drawMinuteResult(c, bankRect);
        } else {
            drawBank(c, bankRect);
        }
    }

    private void drawBank(Canvas c, RectF r) {
        boolean full = bank >= 1f;
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(full ? warn : dim);
        label.setAlpha(255);
        label.setTextSize(dp(11f));
        c.drawText(full ? "EFFORT BANK FULL" : "EFFORT BANK", r.left, r.top + dp(14f), label);

        float barTop = r.top + dp(26f);
        float barBottom = barTop + dp(20f);
        rect.set(r.left, barTop, r.right, barBottom);
        fill.setColor(track);
        fill.setAlpha(255);
        c.drawRoundRect(rect, dp(6f), dp(6f), fill);
        float end = r.left + (r.width()) * Math.max(0f, Math.min(1f, bankShown));
        if (end > r.left + dp(2f)) {
            rect.set(r.left, barTop, end, barBottom);
            fill.setColor(full ? warn : blue);
            c.drawRoundRect(rect, dp(6f), dp(6f), fill);
            // A sheen sliding along the filled part: the bank is earning, not sitting.
            float sheen = r.left + ((clock * 0.45f) % 1f) * (end - r.left);
            fill.setColor(0xFFFFFFFF);
            fill.setAlpha(60);
            c.drawRect(sheen, barTop, Math.min(end, sheen + dp(26f)), barBottom, fill);
            fill.setAlpha(255);
        }

        if (full) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(clock * 5.0);
            stroke.setColor(warn);
            stroke.setStrokeWidth(dp(2f));
            stroke.setAlpha((int) (120 + 135 * pulse));
            rect.set(r.left - dp(3f), barTop - dp(3f), r.right + dp(3f), barBottom + dp(3f));
            c.drawRoundRect(rect, dp(8f), dp(8f), stroke);
            label.setColor(warn);
            label.setTextSize(dp(13f));
            label.setAlpha((int) (180 + 75 * pulse));
            c.drawText("TAP HERE  ·  SPEND IT ON A POWER MINUTE", r.left, r.bottom - dp(4f), label);
            label.setAlpha(255);
        } else {
            int hint = (int) Math.round(host.typicalWatts());
            if (hint != bankHintKey) {
                bankHintKey = hint;
                bankHintText = "ROW ABOVE " + hint + " W TO FILL IT";
            }
            label.setColor(dim);
            label.setTextSize(dp(10f));
            c.drawText(bankHintText, r.left, r.bottom - dp(4f), label);
            int pct = Math.round(bankShown * 100f);
            if (pct != bankPctKey) {
                bankPctKey = pct;
                bankPctText = pct + "%";
            }
            value.setTextAlign(Paint.Align.RIGHT);
            value.setColor(text);
            value.setAlpha(255);
            value.setTextSize(dp(12f));
            c.drawText(bankPctText, r.right, r.top + dp(14f), value);
        }
    }

    private void drawPowerMinute(Canvas c, RectF r) {
        float target = (float) host.pushWatts();
        boolean holding = shownWatts >= target;
        int accent = holding ? good : bad;
        int targetKey = Math.round(target);
        if (targetKey != minuteTargetKey) {
            minuteTargetKey = targetKey;
            minuteTargetText = "POWER MINUTE  ·  HOLD " + targetKey + " W";
        }
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(accent);
        label.setAlpha(255);
        label.setTextSize(dp(12f));
        c.drawText(minuteTargetText, r.left, r.top + dp(14f), label);

        int secs = (int) Math.ceil(minuteLeft);
        if (secs != minuteClockKey) {
            minuteClockKey = secs;
            minuteClock = "0:" + (secs < 10 ? "0" : "") + secs;
        }
        value.setTextAlign(Paint.Align.LEFT);
        value.setColor(text);
        value.setAlpha(255);
        // The last ten seconds get bigger, once a second, so the end is felt.
        float grow = minuteLeft <= 10f ? 1f + 0.12f * (1f - (minuteLeft - (float) Math.floor(minuteLeft))) : 1f;
        value.setTextSize(dp(30f) * grow);
        c.drawText(minuteClock, r.left, r.bottom - dp(6f), value);

        // Held bar: the share of the minute spent at or above the target.
        float barLeft = r.left + dp(96f);
        float barTop = r.bottom - dp(30f);
        rect.set(barLeft, barTop, r.right, barTop + dp(16f));
        fill.setColor(track);
        fill.setAlpha(255);
        c.drawRoundRect(rect, dp(5f), dp(5f), fill);
        float share = Math.max(0f, Math.min(1f, minuteHeldSeconds / POWER_MINUTE_SECONDS));
        if (share > 0.004f) {
            rect.set(barLeft, barTop, barLeft + (r.right - barLeft) * share, barTop + dp(16f));
            fill.setColor(good);
            c.drawRoundRect(rect, dp(5f), dp(5f), fill);
        }
        // The live watts as a marker on the same scale, so easing off is visible instantly.
        float nowShare = Math.max(0f, Math.min(1.2f, shownWatts / Math.max(1f, target)));
        float markX = barLeft + (r.right - barLeft) * Math.min(1f, nowShare / 1.2f);
        fill.setColor(accent);
        c.drawRect(markX - dp(1.5f), barTop - dp(5f), markX + dp(1.5f), barTop + dp(21f), fill);

        int held = Math.round(minuteHeldSeconds);
        if (held != minuteHeldKey) {
            minuteHeldKey = held;
            minuteHeldText = held + " s HELD";
        }
        label.setColor(dim);
        label.setTextSize(dp(10f));
        c.drawText(minuteHeldText, barLeft, barTop - dp(8f), label);
        int live = Math.round(shownWatts);
        if (live != liveWattsKey) {
            liveWattsKey = live;
            liveWattsText = live + " W";
        }
        value.setTextAlign(Paint.Align.RIGHT);
        value.setColor(accent);
        value.setTextSize(dp(15f));
        c.drawText(liveWattsText, r.right, barTop - dp(8f), value);
    }

    private void drawMinuteResult(Canvas c, RectF r) {
        float a = minuteResultAge > 5.2f ? Math.max(0f, 1f - (minuteResultAge - 5.2f) / 0.8f) : 1f;
        int accent = minuteResultBest ? warn : good;
        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(accent);
        label.setAlpha((int) (255 * a));
        label.setTextSize(dp(12f));
        c.drawText(minuteResultBest ? "POWER MINUTE  ·  NEW BEST" : "POWER MINUTE DONE",
                r.left, r.top + dp(14f), label);
        value.setTextAlign(Paint.Align.LEFT);
        value.setColor(text);
        value.setAlpha((int) (255 * a));
        float pop = 1f + 0.18f * Math.max(0f, 1f - minuteResultAge * 3f);
        value.setTextSize(dp(30f) * pop);
        c.drawText(minuteResultText, r.left, r.bottom - dp(20f), value);
        label.setColor(dim);
        label.setAlpha((int) (255 * a));
        label.setTextSize(dp(10f));
        c.drawText(minuteResultHeld, r.left, r.bottom - dp(4f), label);
    }

    private void drawWeekRing(Canvas c, float cx, float cy, float r) {
        stroke.setStrokeWidth(dp(7f));
        stroke.setColor(track);
        stroke.setAlpha(255);
        arc.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(arc, 0, 360, false, stroke);
        float share = Math.max(0f, Math.min(1f, weekShown));
        stroke.setColor(weekShown >= 1f ? warn : good);
        c.drawArc(arc, -90, 360f * share, false, stroke);
        if (share > 0.002f && share < 1f) {
            // A head on the arc so the ring reads as moving even a minute at a time.
            double ang = Math.toRadians(-90 + 360f * share);
            fill.setColor(good);
            fill.setAlpha((int) (140 + 115 * (0.5f + 0.5f * Math.sin(clock * 4.0))));
            c.drawCircle(cx + (float) Math.cos(ang) * r, cy + (float) Math.sin(ang) * r, dp(4.5f), fill);
            fill.setAlpha(255);
        }
        if (weekFlareAge < 4f) {
            float t = Math.min(1f, weekFlareAge / 0.9f);
            stroke.setColor(warn);
            stroke.setAlpha((int) (200 * (1f - t)));
            stroke.setStrokeWidth(dp(3f));
            float rr = r + t * dp(22f);
            arc.set(cx - rr, cy - rr, cx + rr, cy + rr);
            c.drawArc(arc, 0, 360, false, stroke);
        }
        int key = Math.round(weekMinutes) * 1000 + Math.round(weekGoal);
        if (key != weekTextKey) {
            weekTextKey = key;
            weekText = Math.round(weekMinutes) + "/" + Math.round(weekGoal);
        }
        big.setColor(text);
        big.setAlpha(255);
        big.setTextSize(dp(15f));
        c.drawText(weekText, cx, cy + dp(2f), big);
        if (weekStreak != weekStreakKey) {
            weekStreakKey = weekStreak;
            weekStreakText = weekStreak > 0 ? weekStreak + " DAY STREAK" : "WEEK MIN";
        }
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(weekFlareAge < 4f ? warn : dim);
        label.setAlpha(255);
        label.setTextSize(dp(8.5f));
        c.drawText(weekFlareAge < 4f ? "WEEK DONE" : weekStreakText, cx, cy + dp(16f), label);
        label.setTextAlign(Paint.Align.LEFT);
    }

    /* ---------- 4: the warm-up offer ---------- */

    private void drawWarmUp(Canvas c, RectF r) {
        float in = Math.min(1f, warmOfferAge / 0.35f);
        float slide = (1f - in) * dp(60f);
        rect.set(r.left, r.top + slide, r.right, r.bottom + slide);
        card.setAlpha((int) (236 * in));
        c.drawRoundRect(rect, dp(12f), dp(12f), card);
        stroke.setColor(purple);
        stroke.setAlpha((int) (210 * in));
        stroke.setStrokeWidth(dp(2f));
        c.drawRoundRect(rect, dp(12f), dp(12f), stroke);

        label.setTextAlign(Paint.Align.LEFT);
        label.setColor(purple);
        label.setAlpha((int) (255 * in));
        label.setTextSize(dp(13f));
        c.drawText("WARMED UP", rect.left + dp(16f), rect.top + dp(26f), label);
        int mins = Math.round(steadySeconds / 60f);
        if (mins != warmSteadyKey) {
            warmSteadyKey = mins;
            warmSteadyText = mins + " min steady at your own rate  ·  ready for a piece?";
        }
        label.setColor(dim);
        label.setTextSize(dp(11f));
        c.drawText(warmSteadyText, rect.left + dp(16f), rect.top + dp(46f), label);

        float bw = (rect.width() - dp(44f)) / 2f;
        warmTenRect.set(rect.left + dp(16f), rect.bottom - dp(56f), rect.left + dp(16f) + bw,
                rect.bottom - dp(16f));
        warmTwentyRect.set(warmTenRect.right + dp(12f), warmTenRect.top, warmTenRect.right + dp(12f) + bw,
                warmTenRect.bottom);
        drawButton(c, warmTenRect, "10 MIN PIECE", good, in);
        drawButton(c, warmTwentyRect, "20 MIN PIECE", blue, in);
        warmCloseRect.set(rect.right - dp(40f), rect.top + dp(6f), rect.right - dp(6f), rect.top + dp(40f));
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(dim);
        label.setTextSize(dp(15f));
        c.drawText("✕", warmCloseRect.centerX(), warmCloseRect.centerY() + dp(5f), label);
        label.setTextAlign(Paint.Align.LEFT);
    }

    private void drawButton(Canvas c, RectF r, String text, int accent, float alpha) {
        fill.setColor(accent);
        fill.setAlpha((int) (46 * alpha));
        c.drawRoundRect(r, dp(8f), dp(8f), fill);
        stroke.setColor(accent);
        stroke.setAlpha((int) (200 * alpha));
        stroke.setStrokeWidth(dp(1.5f));
        c.drawRoundRect(r, dp(8f), dp(8f), stroke);
        label.setTextAlign(Paint.Align.CENTER);
        label.setColor(accent);
        label.setAlpha((int) (255 * alpha));
        label.setTextSize(dp(12f));
        c.drawText(text, r.centerX(), r.centerY() + dp(4f), label);
        label.setTextAlign(Paint.Align.LEFT);
    }
}
