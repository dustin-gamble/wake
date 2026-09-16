package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * Head Race: you against three boats with different race plans, all calibrated to your own best
 * time over the distance. The Flyer goes out hard and fades, the Metronome holds even splits,
 * the Closer negative-splits. Teaches race craft: where you lose the race is as telling as whether.
 *
 * <p>3.15.0 fills the empty space with instruments that move ("head race is good, but I need more
 * gauges to help show my efforts"): a gap graph to each crew over the last two minutes, one power
 * bar per stroke against your own average, a distance-to-go ribbon with every crew on it, and the
 * shape of your last drive from the pulse meter.
 *
 * <p>3.19.4 makes the course a place: a bank with a crowd that roars when you take the lead, lane
 * buoys, 250 m boards, a finish line that sails in over the last 80 m, a splash on every catch,
 * "PASSED" callouts as the order changes, and confetti when you win.
 */
final class HeadRaceGame extends GameView {

    private enum Phase { READY, RACING, DONE }

    /** Speed = base x (1 + a x (0.5 - t/T)); a > 0 fades, a < 0 closes. Integrates to exactly D. */
    private static final class Rival {
        final String name;
        final int color;
        final float shape;
        final float finishFactor;
        double finishTime;

        Rival(String name, int color, float shape, float finishFactor) {
            this.name = name;
            this.color = color;
            this.shape = shape;
            this.finishFactor = finishFactor;
        }

        double distanceAt(double t, int meters) {
            double T = finishTime;
            double base = meters / T;
            double tt = Math.min(t, T);
            return base * (tt + shape * (0.5 * tt - tt * tt / (2 * T)));
        }

        float speedAt(double t) {
            double T = finishTime;
            if (t >= T) {
                return 0f;
            }
            return (float) ((finishTime > 0 ? 1 : 0) * (1 + shape * (0.5 - t / T)));
        }
    }

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Rival[] rivals = {
            new Rival("FLYER", WARN, 0.30f, 0.99f),
            new Rival("METRONOME", BLUE, 0f, 1.00f),
            new Rival("CLOSER", 0xFFB48CFF, -0.30f, 1.01f),
    };
    private final float[] rivalX = new float[3];

    /** Gap to each rival, one sample a second, the last two minutes. */
    private static final int GAP_SAMPLES = 120;
    private final float[][] gaps = new float[3][GAP_SAMPLES];
    private int gapCount;
    private int gapHead;
    private int lastGapSecond = -1;

    /** Measured average power of each recent stroke. */
    private static final int STROKE_BARS = 28;
    private final float[] strokePower = new float[STROKE_BARS];
    private int strokeCount;
    private int strokeHead;
    private PulseMeter.Stroke lastSeenStroke;
    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boardText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private int lastAhead = -1;
    private String callout = "";
    private int calloutColor = ACCENT;
    private double calloutUntil;
    private double confettiUntil;
    private float cheer;
    private android.graphics.LinearGradient skyShader;
    private float skyHeight;
    private final android.graphics.Path trace = new android.graphics.Path();

    private int raceMeters = 2000;
    private Phase phase = Phase.READY;
    private double raceStartSeconds;
    private double raceStartMeters;
    private double finishTime;
    private int placing;

    HeadRaceGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
        boardText.setTextSize(dp(11f));
        boardText.setFakeBoldText(true);
    }

    void setRaceMeters(int m) {
        raceMeters = m;
        phase = Phase.READY;
    }

    int raceMeters() {
        return raceMeters;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        placing = 0;
        gapCount = 0;
        gapHead = 0;
        lastGapSecond = -1;
        strokeCount = 0;
        strokeHead = 0;
        lastAhead = -1;
        calloutUntil = 0;
        confettiUntil = 0;
        // The field is set from your best; without one, a 2:15 pace boat's time.
        float reference = bests.has("time." + raceMeters)
                ? bests.get("time." + raceMeters, 0f)
                : raceMeters / (500f / 135f);
        for (Rival r : rivals) {
            r.finishTime = reference * r.finishFactor;
        }
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.RACING;
            raceStartSeconds = sessionSeconds;
            raceStartMeters = sessionMeters;
        }
        // A stroke's power is read when the pulse meter closes it - never from onStroke, which
        // lands a second late when instantaneous power has already collapsed.
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (phase == Phase.RACING && stroke != null && stroke != lastSeenStroke) {
            float power = !Double.isNaN(stroke.averagePowerW) ? (float) stroke.averagePowerW : s.watts;
            strokePower[strokeHead] = power;
            strokeHead = (strokeHead + 1) % STROKE_BARS;
            strokeCount = Math.min(STROKE_BARS, strokeCount + 1);
            // The catch: a splash off each blade at your boat.
            float bx = getWidth() * 0.40f;
            float by = getHeight() * (0.26f + 0.48f * 0.875f);
            for (int side = -1; side <= 1; side += 2) {
                for (int k = 0; k < 10; k++) {
                    fx.spawn(bx - dp(10f) + (float) Math.random() * dp(20f), by + side * dp(20f),
                            (float) (Math.random() - 0.7) * dp(60f), -dp(40f) - (float) Math.random() * dp(70f),
                            0.7f, dp(3.5f), 0xEEDDF2FF, true);
                }
            }
        }
        lastSeenStroke = stroke;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.DONE) {
            start();
            return true;
        }
        return super.onTouchEvent(event);
    }

    private double raceTime() {
        return phase == Phase.READY ? 0 : sessionSeconds - raceStartSeconds;
    }

    private double raceDistance() {
        return phase == Phase.READY ? 0 : sessionMeters - raceStartMeters;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        double t = raceTime();
        double you = raceDistance();
        if (phase == Phase.RACING && you >= raceMeters) {
            phase = Phase.DONE;
            finishTime = t;
            placing = 1;
            for (Rival r : rivals) {
                if (r.finishTime < finishTime) {
                    placing++;
                }
            }
            bests.recordLowest("time." + raceMeters, (float) finishTime);
            confettiUntil = sessionSeconds + (placing == 1 ? 4.0 : 1.5);
            callout = placing == 1 ? "YOU WIN!" : ordinal(placing) + " PLACE";
            calloutColor = placing == 1 ? 0xFFF5C518 : WARN;
            calloutUntil = sessionSeconds + 3.0;
        }

        float waterTop = h * 0.26f;
        float waterBottom = h * 0.74f;
        if (phase == Phase.RACING) {
            int second = (int) t;
            if (second != lastGapSecond) {
                lastGapSecond = second;
                for (int i = 0; i < 3; i++) {
                    gaps[i][gapHead] = (float) (you - rivals[i].distanceAt(t, raceMeters));
                }
                gapHead = (gapHead + 1) % GAP_SAMPLES;
                gapCount = Math.min(GAP_SAMPLES, gapCount + 1);
            }
        }
        // Sky above the bank, so the header reads as a place rather than a dashboard.
        float bankTop = waterTop - dp(46f);
        if (skyShader == null || skyHeight != bankTop) {
            skyHeight = bankTop;
            skyShader = new android.graphics.LinearGradient(0, 0, 0, bankTop, 0xFF0B1322, 0xFF2A4E74,
                    android.graphics.Shader.TileMode.CLAMP);
        }
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        panel.setShader(skyShader);
        c.drawRect(0, 0, w, bankTop, panel);
        panel.setShader(null);
        Fx.glow(c, w * 0.86f, bankTop - dp(10f), dp(120f), 0x44FFC98A);
        panel.setColor(0xFF1C3350);
        float hillScroll = (float) ((raceDistance() * (w / 80f) * 0.08) % (w * 0.5f));
        for (int k = -1; k < 4; k++) {
            float hx = k * w * 0.5f - hillScroll;
            c.drawOval(hx - w * 0.3f, bankTop - dp(34f), hx + w * 0.3f, bankTop + dp(40f), panel);
        }
        float ppm = w / 80f;
        float speed = boat.value();
        river.advance(phase == Phase.RACING ? speed : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        fx.step(dt, dp(260f));

        float laneH = (waterBottom - waterTop) / 4f;
        float yourX = w * 0.40f;
        // The course, all of it scrolling with your metres.
        scenery.drawBank(c, w, waterTop - dp(46f), waterTop, you, ppm, sessionSeconds, cheer);
        for (int lane = 1; lane < 4; lane++) {
            scenery.drawBuoys(c, w, waterTop + laneH * lane, you, ppm, sessionSeconds, 10f);
        }
        drawWaterLife(c, w, waterTop, waterBottom, you, ppm, sessionSeconds);
        for (int mark = 250; mark < raceMeters; mark += 250) {
            float mx = yourX + (float) (mark - you) * ppm;
            if (mx > -dp(40f) && mx < w + dp(40f)) {
                scenery.drawBoard(c, mx, waterTop, (raceMeters - mark) + " m", boardText);
            }
        }
        float finishX = yourX + dp(62f) + (float) (raceMeters - you) * ppm;
        if (finishX < w + dp(40f)) {
            scenery.drawFinishLine(c, finishX, waterTop, waterBottom, sessionSeconds);
        }
        // Live placing: count rivals ahead of you.
        int ahead = 0;
        for (int i = 0; i < 3; i++) {
            Rival r = rivals[i];
            double d = r.distanceAt(t, raceMeters);
            if (d > you) {
                ahead++;
            }
            float target = Math.max(dp(36f), Math.min(w - dp(36f), yourX + (float) (d - you) * ppm));
            rivalX[i] += (target - rivalX[i]) * Math.min(1f, 6f * dt);
            if (rivalX[i] == 0f) {
                rivalX[i] = target;
            }
            float ly = waterTop + laneH * (i + 0.5f);
            // Each crew rows to its own rhythm, a little off yours and off each other's. Sharing
            // one phase put all four boats at the catch on the same frame, which reads as four
            // identical bars through the fleet rather than as a race.
            river.setStrokePhase((float) (0.5 + 0.5 * Math.sin(sessionSeconds * (2.4 + i * 0.18)
                    + i * 1.9)));
            river.drawBoat(c, rivalX[i], ly, dp(150f), r.color,
                    phase == Phase.RACING ? r.speedAt(t) * (raceMeters / (float) r.finishTime) : 0f, true);
            label(c, r.name + String.format(java.util.Locale.US, "  %+.0f m", d - you), rivalX[i],
                    ly - dp(40f), 11f, d > you ? r.color : DIM, Paint.Align.CENTER);
        }
        if (phase == Phase.RACING) {
            if (lastAhead >= 0 && ahead != lastAhead) {
                boolean gained = ahead < lastAhead;
                callout = gained ? (ahead == 0 ? "YOU TAKE THE LEAD!" : "PASSED ONE!")
                        : "YOU'VE BEEN PASSED";
                calloutColor = gained ? ACCENT : BAD;
                calloutUntil = sessionSeconds + 1.8;
                if (gained) {
                    fx.burst(yourX, waterTop + laneH * 3.5f, 26, dp(160f), 0.9f, dp(3f), 0xFFF5C518, true);
                }
            }
            lastAhead = ahead;
        }
        // The crowd follows the race: loud when you lead, quiet when you trail.
        float cheerTarget = phase == Phase.DONE ? (placing == 1 ? 1f : 0.3f)
                : phase == Phase.RACING ? (ahead == 0 ? 1f : ahead == 1 ? 0.5f : 0.15f) : 0f;
        cheer += (cheerTarget - cheer) * Math.min(1f, 2f * dt);
        float yourY = waterTop + laneH * 3.5f;
        if (phase != Phase.READY && ahead == 0) {
            Fx.glow(c, yourX, yourY, dp(90f), 0x44F5C518);
        }
        river.bowSpray(yourX + dp(54f), yourY, speed, dt);
        // Your own oars follow your own stroke - this was inheriting whatever phase the last rival
        // was drawn with, so the one boat that should track the rower did not.
        river.setStrokePhase(strokePhase());
        river.drawBoat(c, yourX, yourY, dp(156f), ACCENT, speed, false);
        river.drawSpray(c);
        bold(c, "YOU", yourX, yourY + dp(48f), 11f, ACCENT, Paint.Align.CENTER);
        if (sessionSeconds < confettiUntil && Math.random() < 0.7) {
            int[] colors = {0xFFF5C518, 0xFFF0655D, 0xFF35D0BA, 0xFF6F8CFF, 0xFFFFFFFF};
            fx.spawn((float) Math.random() * w, waterTop - dp(40f), (float) (Math.random() - 0.5) * dp(80f),
                    dp(20f), 2.2f, dp(3f), colors[(int) (Math.random() * colors.length)], true);
        }
        fx.draw(c);
        if (sessionSeconds < calloutUntil) {
            double left = calloutUntil - sessionSeconds;
            float pop = (float) Math.min(1.0, (1.8 - Math.min(1.8, left)) * 8 + 0.6);
            bold(c, callout, w / 2f, waterTop + (waterBottom - waterTop) * 0.5f, 30f * Math.min(1f, pop),
                    calloutColor, Paint.Align.CENTER);
        }

        String big;
        int col;
        if (phase == Phase.READY) {
            big = raceMeters + " m";
            col = DIM;
        } else if (phase == Phase.DONE) {
            big = ordinal(placing);
            col = placing == 1 ? ACCENT : placing == 4 ? BAD : WARN;
        } else {
            big = ordinal(ahead + 1);
            col = ahead == 0 ? ACCENT : ahead == 3 ? BAD : WARN;
        }
        bold(c, big, w / 2f, h * 0.15f, 44f, col, Paint.Align.CENTER);
        label(c, phase == Phase.READY ? "field set to your best - take a stroke"
                : phase == Phase.DONE ? clock(finishTime) + "  ·  tap to race again"
                : clock(t) + "  ·  " + Math.round(you) + " of " + raceMeters + " m",
                w / 2f, h * 0.15f + dp(20f), 10f, FAINT, Paint.Align.CENTER);
        bold(c, pace(speed), dp(16f), h * 0.15f, 22f, TEXT, Paint.Align.LEFT);
        label(c, "PACE /500", dp(16f), h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, status == null ? "0" : status.strokeRate + " spm", w - dp(16f), h * 0.15f, 22f, TEXT,
                Paint.Align.RIGHT);
        label(c, status == null ? "" : status.watts + " W", w - dp(16f), h * 0.15f + dp(16f), 9f,
                FAINT, Paint.Align.RIGHT);

        drawRibbon(c, w, waterTop - dp(56f), t, you);
        // Anchored to the top-right corner: floating in the middle of the sky it read as a
        // sticker dropped on the scene rather than part of the instrument.
        drawStrokeShape(c, w - dp(208f), dp(6f), w - dp(16f), h * 0.15f - dp(30f));
        float panelTop = waterBottom + dp(10f);
        float panelBottom = h - dp(8f);
        drawGapGraph(c, dp(10f), panelTop, w * 0.5f - dp(6f), panelBottom);
        drawStrokeBars(c, w * 0.5f + dp(6f), panelTop, w - dp(10f), panelBottom);
    }

    /**
     * Life on the water itself. The hulls already carry their own wake from RiverRenderer, so this
     * adds none: what the middle of the course was missing is surface texture and something moving
     * that is not a competitor. Chop at four depths, drifting foam, and a pair of ducks working
     * down the course - all scrolling with your metres, all kept out of the four racing lanes.
     */
    private void drawWaterLife(Canvas c, float w, float waterTop, float waterBottom, double you,
                               float ppm, double time) {
        float span = waterBottom - waterTop;
        panel.setStyle(Paint.Style.STROKE);
        panel.setStrokeCap(Paint.Cap.ROUND);
        // Chop: short strokes on the lane boundaries and the open water between them. Nearer rows
        // scroll faster, which is what gives the flat navy field any sense of depth at all.
        for (int row = 0; row < 8; row++) {
            float y = waterTop + span * (0.05f + row * 0.125f);
            float depth = 0.3f + row * 0.1f;
            float step = dp(150f);
            float off = (float) ((you * ppm * depth * 0.6) % step);
            panel.setStrokeWidth(dp(1.2f) + depth * dp(0.8f));
            panel.setColor((Math.round(20 + 30 * depth) << 24) | 0x00BFE3FF);
            for (float x = -off; x < w + step; x += step) {
                float bob = (float) Math.sin(time * 1.5 + x * 0.015 + row) * dp(2.5f);
                c.drawLine(x, y + bob, x + dp(30f) * depth, y + bob, panel);
                c.drawLine(x + dp(62f), y + bob + dp(6f), x + dp(62f) + dp(20f) * depth,
                        y + bob + dp(6f), panel);
            }
        }
        panel.setStyle(Paint.Style.FILL);
        // Foam and drift carried down the course.
        float driftStep = dp(260f);
        float driftOff = (float) ((you * ppm * 0.85) % driftStep);
        for (int k = -1; k < (int) (w / driftStep) + 2; k++) {
            float x = k * driftStep - driftOff;
            float y = waterTop + span * (0.2f + ((k * 5081) & 3) * 0.2f);
            panel.setColor(0x26DCEBF7);
            c.drawOval(x, y, x + dp(34f), y + dp(7f), panel);
            c.drawOval(x + dp(40f), y + dp(9f), x + dp(58f), y + dp(14f), panel);
        }
        // Two ducks in the clear strip above the first lane, bobbing as they paddle.
        float duckStep = dp(520f);
        float duckOff = (float) ((you * ppm * 0.95) % duckStep);
        for (int k = -1; k < (int) (w / duckStep) + 2; k++) {
            float dx = k * duckStep - duckOff + dp(60f);
            for (int n = 0; n < 2; n++) {
                float x = dx + n * dp(26f);
                float y = waterTop + span * 0.035f + (float) Math.sin(time * 2.1 + k + n) * dp(1.8f);
                panel.setColor(0xFF2B3A46);
                c.drawOval(x, y, x + dp(15f), y + dp(8f), panel);          // body
                c.drawOval(x + dp(10f), y - dp(6f), x + dp(17f), y + dp(2f), panel);  // head
                panel.setColor(0xFFF0B132);
                c.drawRect(x + dp(16f), y - dp(3f), x + dp(19f), y - dp(1.5f), panel); // bill
                panel.setColor(0x33DCEBF7);
                c.drawOval(x - dp(5f), y + dp(6f), x + dp(15f), y + dp(10f), panel);   // its ripple
            }
        }
    }

    /** Every crew's progress on one strip above the water, with metres to go. */
    private void drawRibbon(Canvas c, float w, float y, double t, double you) {
        float left = dp(16f);
        float right = w - dp(16f);
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0x33FFFFFF);
        c.drawRoundRect(left, y - dp(3f), right, y + dp(3f), dp(3f), dp(3f), panel);
        for (int i = 0; i < 3; i++) {
            float f = (float) Math.min(1.0, rivals[i].distanceAt(t, raceMeters) / raceMeters);
            panel.setColor(rivals[i].color);
            c.drawCircle(left + (right - left) * f, y, dp(5f), panel);
        }
        float mine = (float) Math.min(1.0, you / raceMeters);
        panel.setColor(ACCENT);
        c.drawRoundRect(left, y - dp(3f), left + (right - left) * mine, y + dp(3f), dp(3f), dp(3f), panel);
        c.drawCircle(left + (right - left) * mine, y, dp(7f), panel);
        label(c, Math.max(0, Math.round(raceMeters - you)) + " m to go", right, y - dp(8f), 9f, TEXT, Paint.Align.RIGHT);
    }

    /** Your gap to each crew over the last two minutes: above the line you lead. */
    private void drawGapGraph(Canvas c, float l, float t, float r, float b) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(10f), dp(10f), panel);
        label(c, "GAP TO EACH CREW  ·  LAST 2 MIN", l + dp(10f), t + dp(16f), 8.5f, FAINT, Paint.Align.LEFT);
        float top = t + dp(24f);
        float bottom = b - dp(8f);
        float mid = (top + bottom) / 2f;
        float max = 10f;
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < gapCount; k++) {
                max = Math.max(max, Math.abs(gaps[i][k]));
            }
        }
        panel.setColor(0x33FFFFFF);
        c.drawRect(l + dp(8f), mid - dp(0.5f), r - dp(8f), mid + dp(0.5f), panel);
        label(c, "+" + Math.round(max) + " m", r - dp(10f), top + dp(8f), 8f, FAINT, Paint.Align.RIGHT);
        label(c, "-" + Math.round(max) + " m", r - dp(10f), bottom - dp(2f), 8f, FAINT, Paint.Align.RIGHT);
        if (gapCount < 2) {
            return;
        }
        panel.setStyle(Paint.Style.STROKE);
        panel.setStrokeWidth(dp(2.2f));
        float step = (r - l - dp(16f)) / (GAP_SAMPLES - 1f);
        for (int i = 0; i < 3; i++) {
            trace.rewind();
            for (int k = 0; k < gapCount; k++) {
                int idx = (gapHead - gapCount + k + GAP_SAMPLES) % GAP_SAMPLES;
                float x = r - dp(8f) - (gapCount - 1 - k) * step;
                float y = mid - (bottom - top) / 2f * gaps[i][idx] / max;
                if (k == 0) {
                    trace.moveTo(x, y);
                } else {
                    trace.lineTo(x, y);
                }
            }
            panel.setColor(rivals[i].color);
            c.drawPath(trace, panel);
        }
        panel.setStyle(Paint.Style.FILL);
    }

    /** One bar per stroke, measured power, coloured against your own recent average. */
    private void drawStrokeBars(Canvas c, float l, float t, float r, float b) {
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(10f), dp(10f), panel);
        float sum = 0f;
        float max = 50f;
        for (int k = 0; k < strokeCount; k++) {
            sum += strokePower[k];
            max = Math.max(max, strokePower[k]);
        }
        float avg = strokeCount > 0 ? sum / strokeCount : 0f;
        label(c, strokeCount > 0 ? "POWER PER STROKE  ·  AVERAGE " + Math.round(avg) + " W" : "POWER PER STROKE",
                l + dp(10f), t + dp(16f), 8.5f, FAINT, Paint.Align.LEFT);
        float top = t + dp(24f);
        float bottom = b - dp(8f);
        float slot = (r - l - dp(16f)) / STROKE_BARS;
        for (int k = 0; k < strokeCount; k++) {
            int idx = (strokeHead - strokeCount + k + STROKE_BARS) % STROKE_BARS;
            float v = strokePower[idx];
            float x = r - dp(8f) - (strokeCount - k) * slot;
            float barTop = bottom - (bottom - top) * v / (max * 1.1f);
            panel.setColor(v >= avg * 1.03f ? ACCENT : v <= avg * 0.93f ? WARN : BLUE);
            c.drawRoundRect(x + slot * 0.15f, barTop, x + slot * 0.85f, bottom, dp(2f), dp(2f), panel);
        }
        if (strokeCount > 0) {
            float ay = bottom - (bottom - top) * avg / (max * 1.1f);
            panel.setColor(0x88FFFFFF);
            c.drawRect(l + dp(8f), ay - dp(0.75f), r - dp(8f), ay + dp(0.75f), panel);
        }
    }

    /** The shape of your last drive: paddle speed through the stroke, from the pulse meter. */
    private void drawStrokeShape(Canvas c, float l, float t, float r, float b) {
        if (status == null || status.meter.lastStroke == null || b - t < dp(30f)) {
            return;
        }
        float[] rates = status.meter.lastStroke.driveRates;
        if (rates.length < 2) {
            return;
        }
        float max = 1f;
        for (float v : rates) {
            max = Math.max(max, v);
        }
        panel.setStyle(Paint.Style.FILL);
        panel.setColor(0xFF0D1420);
        c.drawRoundRect(l, t, r, b, dp(8f), dp(8f), panel);
        trace.rewind();
        float left = l + dp(8f);
        float width = r - l - dp(16f);
        float top = t + dp(16f);
        float bottom = b - dp(6f);
        trace.moveTo(left, bottom);
        for (int i = 0; i < rates.length; i++) {
            trace.lineTo(left + width * i / (rates.length - 1f), bottom - (bottom - top) * rates[i] / max);
        }
        trace.lineTo(left + width, bottom);
        trace.close();
        panel.setColor(0x6635D0BA);
        c.drawPath(trace, panel);
        label(c, "LAST DRIVE", l + dp(8f), t + dp(12f), 7.5f, FAINT, Paint.Align.LEFT);
    }

    private static String ordinal(int n) {
        return n == 1 ? "1ST" : n == 2 ? "2ND" : n == 3 ? "3RD" : n + "TH";
    }
}
