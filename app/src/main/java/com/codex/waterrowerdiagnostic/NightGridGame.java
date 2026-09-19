package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * NIGHT GRID: a valley town after dark, lit by your power.
 *
 * <p>A water wheel at the river turns with the boat and feeds the grid. Stay above the town's demand
 * and street by street the lights come on, nearest the wheel first; fall below it and the town goes
 * dark from the edges in. Demand is set from the rower's typical power and swells in the evening
 * peak, so holding a steady effort is what keeps the lights on - stopping has a visible cost.
 *
 * <p>The town grows across sessions, like Skyline: one new house for every 20 kJ ever rowed here,
 * then second storeys once every plot is built, and four landmarks (church, water tower, town hall,
 * lighthouse) at lifetime milestones. Saved in {@link #onStop()}.
 *
 * <p>Events run on the rowing clock, so resting does not skip them: a STORM, in which every surge
 * (a stroke clearly harder than your recent ones) is a lightning strike on the mast that charges
 * the battery bank, then a BLACKOUT, in which you must hold power above the line or the reserve
 * drains - the battery you charged in the storm is spent first - and if it empties the grid goes
 * down. Hold it and the town lets off fireworks and cheers from its windows.
 *
 * <p>Decorations follow the real season (tap the season chip, top right, to preview the others).
 */
final class NightGridGame extends GameView {

    private static final int MAX_HOUSES = 140;
    private static final double JOULES_PER_HOUSE = 20000;
    private static final int START_HOUSES = 24;

    /** Landmarks, built at lifetime energy milestones and lit once enough of the town is lit. */
    private static final String[] LANDMARK = {"CHURCH", "WATER TOWER", "TOWN HALL", "LIGHTHOUSE"};
    private static final double[] LANDMARK_J = {300e3, 800e3, 1500e3, 2500e3};
    private static final float[] LANDMARK_LIT = {0.25f, 0.45f, 0.65f, 0.15f};

    /* Event cycle, in seconds of the rowing clock. */
    private static final double CYCLE = 140;
    private static final double STORM_START = 40;
    private static final double STORM_END = 68;
    private static final double WARN_START = 95;
    private static final double BLACKOUT_START = 98;
    private static final double BLACKOUT_END = 113;
    private static final int CALM = 0;
    private static final int STORM = 1;
    private static final int WARNING = 2;
    private static final int BLACKOUT = 3;

    private static final int WINTER = 0;
    private static final int SPRING = 1;
    private static final int SUMMER = 2;
    private static final int AUTUMN = 3;
    private static final String[] SEASON = {"WINTER", "SPRING", "SUMMER", "AUTUMN"};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float[] houseX = new float[MAX_HOUSES];
    private final float[] houseY = new float[MAX_HOUSES];
    private final float[] houseS = new float[MAX_HOUSES];
    private final int[] order = new int[MAX_HOUSES];
    /** rank[i]: where house i comes in the lighting order. Built once. */
    private final int[] rank = new int[MAX_HOUSES];
    private final boolean[] on = new boolean[MAX_HOUSES];
    /** Session time a house was built this session, for its rise-out-of-the-ground; -1 otherwise. */
    private final float[] bornAt = new float[MAX_HOUSES];
    private final Fx.Particles fx = new Fx.Particles();
    private final float[] bolt = new float[24];
    private final java.util.Random rnd = new java.util.Random();
    private final RectF seasonHit = new RectF();

    private LinearGradient sky;
    private float skyH;

    private double lifetimeJoules;
    private double lastWork = -1;
    private int houses;
    private int storeys;
    private float lit;          // houses lit, eased
    private float supply;
    private double litSeconds;
    private double runSeconds;
    private float wheel;
    private float flow;
    private float turbine;

    // Events.
    private double eventClock;
    private int phase = CALM;
    private float stormAmt;
    private float reserve = 1f;
    private float battery;
    private boolean blackoutFailed;
    private float darkTimer;
    private float cheer;
    private float fireworks;
    private float fireworkNext;
    private int heldTotal;
    private int heldSession;
    private int lostSession;
    private float boltLife;
    private float flash;
    private double ambientNext;
    private boolean boltCharges;
    private int lastStrokeIndex = -1;
    private double basePower;
    private double baseRate;
    private int baseCount;
    private int strikes;

    private String banner;
    private int bannerColor;
    private float bannerTime;

    private int monthSeason;
    private int seasonOverride = -1;
    private long tzOffsetMs;

    NightGridGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        java.util.Random r = new java.util.Random(404);
        for (int i = 0; i < MAX_HOUSES; i++) {
            float row = r.nextFloat();
            houseY[i] = 0.52f + row * 0.36f;
            houseX[i] = 0.24f + r.nextFloat() * 0.74f;
            houseS[i] = 1.0f + row * 1.1f;   // bigger than first shipped: dark houses were invisible
        }
        // Lit in order of distance from the wheel at the bottom left.
        Integer[] idx = new Integer[MAX_HOUSES];
        for (int i = 0; i < MAX_HOUSES; i++) {
            idx[i] = i;
        }
        java.util.Arrays.sort(idx, (a, b) -> Float.compare(dist(a), dist(b)));
        for (int i = 0; i < MAX_HOUSES; i++) {
            order[i] = idx[i];
            rank[idx[i]] = i;
        }
    }

    private float dist(int i) {
        float dx = houseX[i] - 0.14f;
        float dy = houseY[i] - 0.84f;
        return dx * dx + dy * dy;
    }

    @Override
    protected void onStart() {
        lifetimeJoules = bests.get("grid.joules", 0f);
        houses = housesFor(lifetimeJoules);
        storeys = storeysFor(lifetimeJoules);
        heldTotal = Math.round(bests.get("grid.held", 0f));
        battery = Math.max(0f, Math.min(1f, bests.get("grid.battery", 0f)));
        lit = 0f;
        supply = 0f;
        litSeconds = 0;
        runSeconds = 0;
        lastWork = -1;
        eventClock = 0;
        phase = CALM;
        stormAmt = 0f;
        reserve = 1f;
        blackoutFailed = false;
        darkTimer = 0f;
        cheer = 0f;
        fireworks = 0f;
        heldSession = 0;
        lostSession = 0;
        boltLife = 0f;
        flash = 0f;
        lastStrokeIndex = -1;
        baseCount = 0;
        strikes = 0;
        banner = null;
        java.util.Arrays.fill(bornAt, -1f);
        long now = System.currentTimeMillis();
        tzOffsetMs = java.util.TimeZone.getDefault().getOffset(now);
        int month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH); // 0 = January
        monthSeason = month == 11 || month <= 1 ? WINTER : month <= 4 ? SPRING : month <= 7 ? SUMMER : AUTUMN;
    }

    @Override
    protected void onStop() {
        bests.putFloat("grid.joules", (float) lifetimeJoules);
        bests.recordHighest("grid.houses", houses);
        bests.putFloat("grid.held", heldTotal);
        bests.putFloat("grid.battery", battery);
        if (runSeconds > 120) {
            bests.recordHighest("grid.percent", (float) (100 * litSeconds / runSeconds));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && seasonHit.contains(e.getX(), e.getY())) {
            seasonOverride = (season() + 1) % 4;
            return true;
        }
        return super.onTouchEvent(e);
    }

    private int season() {
        return seasonOverride >= 0 ? seasonOverride : monthSeason;
    }

    private static int housesFor(double joules) {
        return (int) Math.min(MAX_HOUSES, START_HOUSES + joules / JOULES_PER_HOUSE);
    }

    /** Once every plot is built, each further 20 kJ adds a second storey, nearest the wheel first. */
    private static int storeysFor(double joules) {
        double beyond = START_HOUSES + joules / JOULES_PER_HOUSE - MAX_HOUSES;
        return (int) Math.max(0, Math.min(MAX_HOUSES, beyond));
    }

    private float demand() {
        // An evening peak every few minutes: 60% of typical power, swelling by a tenth. Was 80%, and
        // on the tablet an easy 32 W row against 106 W demand lit nothing at all - it looked dead.
        return (float) Math.max(10.0, profile.typicalWatts() * 0.6 * (1 + 0.1 * Math.sin(sessionSeconds / 45.0)));
    }

    /** The line a blackout asks you to hold: a quarter above ordinary demand, ~75% of typical. */
    private float blackoutNeed() {
        return demand() * 1.25f;
    }

    private float hillY(float x, float h) {
        return h * 0.50f + (float) Math.sin(x / dp(160f)) * dp(26f);
    }

    /**
     * Glow with its radius rounded to 4 dp. 140 houses each with their own radius was more distinct
     * gradients than Fx's cache holds, so every frame rebuilt them; bucketed, they all stay cached.
     */
    private void glow(Canvas c, float x, float y, float r, int argb) {
        float step = dp(4f);
        Fx.glow(c, x, y, Math.max(step, Math.round(r / step) * step), argb);
    }

    private void say(String text, int color) {
        banner = text;
        bannerColor = color;
        bannerTime = 2.4f;
    }

    /* ---------------- events ---------------- */

    private int phaseAt(double clock) {
        double p = clock % CYCLE;
        if (p >= STORM_START && p < STORM_END) {
            return STORM;
        }
        if (p >= WARN_START && p < BLACKOUT_START) {
            return WARNING;
        }
        if (p >= BLACKOUT_START && p < BLACKOUT_END) {
            return BLACKOUT;
        }
        return CALM;
    }

    private void stepEvents(float dt, boolean rowing, float w, float h) {
        if (rowing) {
            eventClock += dt;
        }
        int now = phaseAt(eventClock);
        if (now != phase) {
            if (now == STORM) {
                say("STORM - SURGE TO CHARGE THE BATTERY", BLUE);
                ambientNext = sessionSeconds + 2;
            } else if (now == WARNING) {
                blackoutFailed = false;
                say("BLACKOUT COMING - GET ON IT", BAD);
            } else if (now == BLACKOUT) {
                reserve = 1f;
            } else if (phase == BLACKOUT && now == CALM && !blackoutFailed) {
                heldSession++;
                heldTotal++;
                cheer = 5f;
                fireworks = 3.5f;
                fireworkNext = 0f;
                say("LIGHTS HELD!", ACCENT);
            }
            phase = now;
        }
        stormAmt += ((phase == STORM ? 1f : 0f) - stormAmt) * Math.min(1f, dt / 2.5f);

        if (phase == BLACKOUT && !blackoutFailed) {
            float need = blackoutNeed();
            float deficit = Math.max(0f, need - supply) / Math.max(1f, need);
            float drain = deficit * dt / 3f;
            float take = Math.min(battery, drain);
            battery -= take;
            reserve -= drain - take;
            if (deficit <= 0f) {
                reserve = Math.min(1f, reserve + dt * 0.04f);
            }
            if (reserve <= 0f) {
                reserve = 0f;
                blackoutFailed = true;
                lostSession++;
                darkTimer = 10f;
                say("GRID DOWN - THE TOWN WENT DARK", BAD);
            }
        }
        if (darkTimer > 0f) {
            darkTimer = Math.max(0f, darkTimer - dt);
        }
        cheer = Math.max(0f, cheer - dt);

        // Surges, judged per stroke against your own recent strokes (never read in onStroke: power
        // has collapsed by the time the counter ticks). Average drive power when the meter has
        // energy, else peak paddle rate; power goes as rate cubed, so 15% power ~ 5% rate.
        PulseMeter.Stroke s = status == null ? null : status.meter.lastStroke;
        if (s != null && s.index != lastStrokeIndex) {
            boolean usePower = !Double.isNaN(s.averagePowerW) && s.averagePowerW > 0;
            double v = usePower ? s.averagePowerW : s.peakRate;
            double base = usePower ? basePower : baseRate;
            boolean surge = baseCount >= 3 && base > 0 && v > base * (usePower ? 1.15 : 1.05);
            if (lastStrokeIndex >= 0) {
                if (surge && phase == STORM) {
                    battery = Math.min(1f, battery + 0.2f);
                    strikes++;
                    strike(w, h, true);
                    say("SURGE! +20% CHARGE", WARN_COLOR);
                }
                if (usePower) {
                    basePower = basePower <= 0 ? v : basePower + (v - basePower) * 0.25;
                } else {
                    baseRate = baseRate <= 0 ? v : baseRate + (v - baseRate) * 0.25;
                }
                baseCount++;
            }
            lastStrokeIndex = s.index;
        }
        if (phase == STORM && sessionSeconds > ambientNext) {
            strike(w, h, false);
            ambientNext = sessionSeconds + 3 + rnd.nextFloat() * 4;
        }
        boltLife = Math.max(0f, boltLife - dt);
        flash = Math.max(0f, flash - dt * 3f);

        if (fireworks > 0f) {
            fireworks -= dt;
            fireworkNext -= dt;
            if (fireworkNext <= 0f) {
                fireworkNext = 0.3f;
                int[] cols = FIREWORK;
                fx.burst(w * (0.3f + rnd.nextFloat() * 0.65f), h * (0.12f + rnd.nextFloat() * 0.22f),
                        26, dp(160f), 1.4f, dp(2.6f), cols[rnd.nextInt(cols.length)], true);
            }
        }
    }

    private static final int WARN_COLOR = 0xFFF0B132;
    private static final int[] FIREWORK = {0xFFFFD27A, 0xFF7FE6C8, 0xFFFF7A9C, 0xFF9FB8FF, 0xFFFFFFFF};

    /** A lightning bolt: onto the battery mast when {@code charges}, else into the far hills. */
    private void strike(float w, float h, boolean charges) {
        float tx;
        float ty;
        if (charges) {
            tx = w * 0.06f;
            ty = h * 0.50f;
        } else {
            tx = w * (0.3f + rnd.nextFloat() * 0.65f);
            ty = hillY(tx, h) + dp(6f);
        }
        float x = tx + (rnd.nextFloat() - 0.5f) * dp(160f);
        int n = bolt.length / 2;
        for (int k = 0; k < n; k++) {
            float f = k / (float) (n - 1);
            float jitter = k == 0 || k == n - 1 ? 0f : (rnd.nextFloat() - 0.5f) * dp(46f);
            bolt[k * 2] = x + (tx - x) * f + jitter;
            bolt[k * 2 + 1] = f * ty;
        }
        boltLife = charges ? 0.45f : 0.3f;
        boltCharges = charges;
        flash = charges ? 1f : 0.55f;
        if (charges) {
            fx.burst(tx, ty, 22, dp(140f), 0.8f, dp(2.4f), 0xFFBFE4FF, true);
        }
    }

    /* ---------------- scenery ---------------- */

    /** 3.19.5: shooting stars and a slow cloud over the moon, so the big sky moves. */
    private void drawNightSky(Canvas c, float w, float h) {
        double t = sessionSeconds;
        double star = (t % 7) / 7;
        if (star < 0.1 && stormAmt < 0.5f) {
            float f = (float) (star / 0.1);
            float sx = w * (0.15f + 0.4f * (float) ((Math.floor(t / 7) * 0.37) % 1.0)) + f * w * 0.25f;
            float sy = h * 0.05f + f * h * 0.12f;
            paint.setStrokeWidth(dp(2.2f));
            paint.setColor(((int) (255 * (1 - f)) << 24) | 0xFFFFFF);
            c.drawLine(sx - dp(70f), sy - dp(26f), sx, sy, paint);
        }
        float span = w + dp(500f);
        for (int i = 0; i < 3; i++) {
            float cx = (float) (((i * 613) + t * dp(8f + i * 3f)) % span) - dp(250f);
            float cy = h * (0.12f + i * 0.07f);
            paint.setColor(0x55223350);
            c.drawOval(cx - dp(110f), cy - dp(14f), cx + dp(110f), cy + dp(14f), paint);
            c.drawOval(cx - dp(50f), cy - dp(30f), cx + dp(56f), cy + dp(6f), paint);
        }
        // Storm clouds roll in over everything and hide the stars.
        if (stormAmt > 0.02f) {
            int a = (int) (230 * stormAmt);
            for (int i = 0; i < 7; i++) {
                float cx = (float) (((i * 331) + t * dp(40f)) % (w + dp(400f))) - dp(200f);
                float cy = h * (0.06f + (i % 3) * 0.06f);
                paint.setColor((a << 24) | (i % 2 == 0 ? 0x1A2233 : 0x232C3E));
                c.drawOval(cx - dp(220f), cy - dp(46f), cx + dp(220f), cy + dp(46f), paint);
                c.drawOval(cx - dp(100f), cy - dp(80f), cx + dp(120f), cy + dp(20f), paint);
            }
        }
    }

    /** A night train along the hills, every window lit - more lights to power in spirit. */
    private void drawTrain(Canvas c, float w, float h) {
        double t = sessionSeconds;
        float span = w + dp(900f);
        float head = (float) ((t * dp(70f)) % span) - dp(450f);
        for (int car = 0; car < 6; car++) {
            float x0 = head - car * dp(62f);
            if (x0 < -dp(70f) || x0 > w + dp(70f)) {
                continue;
            }
            // On the hillside below the power line, not riding on it.
            float ground = h * 0.50f + (float) Math.sin((x0 + dp(28f)) / dp(160f)) * dp(26f) + dp(46f);
            paint.setColor(0xFF1B2638);
            c.drawRoundRect(x0, ground - dp(22f), x0 + dp(56f), ground - dp(4f), dp(4f), dp(4f), paint);
            paint.setColor(0xFFFFD27A);
            for (int win = 0; win < 4; win++) {
                c.drawRect(x0 + dp(6f) + win * dp(12f), ground - dp(18f), x0 + dp(13f) + win * dp(12f), ground - dp(11f), paint);
            }
            if (car == 0) {
                Fx.glow(c, x0 + dp(60f), ground - dp(12f), dp(40f), 0x66FFF1B0);
            }
        }
    }

    /**
     * Turbines that turn with your stroke rate, a lit road across the valley, and an aurora that
     * comes out once the whole town is lit - the right half of the screen had nothing in it.
     */
    private void drawValleyLife(Canvas c, float w, float h, float share) {
        double t = sessionSeconds;
        // Aurora above the hills once the town is lit.
        if (share > 0.6f && stormAmt < 0.5f) {
            paint.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < 3; i++) {
                // Wide and faint: hard ribbons looked pasted over the stars on the tablet.
                paint.setStrokeWidth(dp(26f + i * 18f));
                paint.setColor((((int) (14 * (share - 0.6f) / 0.4f * (1 - stormAmt))) << 24) | (i == 1 ? 0x7FE6C8 : 0x6FD8FF));
                path.reset();
                for (float x = -dp(40f); x <= w + dp(40f); x += dp(60f)) {
                    float y = h * (0.22f + i * 0.04f) + (float) Math.sin(x / dp(220f) + t * 0.4 + i) * dp(26f);
                    if (x <= -dp(40f)) {
                        path.moveTo(x, y);
                    } else {
                        path.lineTo(x, y);
                    }
                }
                c.drawPath(path, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
        // Wind farm on the ridge to the right: blades turn with the rate you are pulling, and race
        // in a storm.
        for (int i = 0; i < 3; i++) {
            float tx = w * (0.62f + i * 0.13f);
            float base = h * 0.50f + (float) Math.sin(tx / dp(160f)) * dp(26f) + dp(30f);
            float hubY = base - dp(64f) - i * dp(8f);
            paint.setColor(0xFF2A3446);
            c.drawRect(tx - dp(2.5f), hubY, tx + dp(2.5f), base, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3f));
            paint.setColor(0xFF3E4A60);
            for (int b = 0; b < 3; b++) {
                double a = turbine * 0.6 + b * Math.PI * 2 / 3 + i;
                c.drawLine(tx, hubY, tx + (float) Math.cos(a) * dp(26f), hubY + (float) Math.sin(a) * dp(26f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFFF4A4A);
            if (((int) (t * 1.5 + i)) % 2 == 0) {
                c.drawCircle(tx, hubY - dp(3f), dp(2f), paint);
            }
        }
        // A road along the valley with a car's headlights sweeping across.
        float roadY = h * 0.80f;
        paint.setColor(0xFF1B2433);
        path.reset();
        path.moveTo(-dp(4f), roadY + dp(18f));
        path.lineTo(w + dp(4f), roadY - dp(8f));
        path.lineTo(w + dp(4f), roadY + dp(8f));
        path.lineTo(-dp(4f), roadY + dp(34f));
        path.close();
        c.drawPath(path, paint);
        // Centre line, dashed, following the same slope.
        paint.setColor(0x33FFF1B0);
        for (float rx = -dp(20f); rx < w; rx += dp(70f)) {
            float ry = roadY + dp(26f) - (rx / w) * dp(26f);
            c.drawRect(rx, ry - dp(1.5f), rx + dp(30f), ry + dp(1.5f), paint);
        }
        float carX = (float) ((t * dp(70f)) % (w + dp(200f))) - dp(100f);
        float carY = roadY + dp(22f) - (carX / w) * dp(26f);
        Fx.glow(c, carX + dp(26f), carY, dp(40f), 0x66FFF1B0);
        paint.setColor(0xFFE8EDF5);
        c.drawRoundRect(carX - dp(14f), carY - dp(7f), carX + dp(14f), carY + dp(3f), dp(3f), dp(3f), paint);
        c.drawRoundRect(carX - dp(7f), carY - dp(13f), carX + dp(7f), carY - dp(5f), dp(3f), dp(3f), paint);
        paint.setColor(0xFF1B2433);
        c.drawCircle(carX - dp(8f), carY + dp(3f), dp(3f), paint);
        c.drawCircle(carX + dp(8f), carY + dp(3f), dp(3f), paint);
        paint.setColor(0xFFFFF1B0);
        c.drawRect(carX + dp(11f), carY - dp(4f), carX + dp(15f), carY - dp(1f), paint);
        paint.setColor(0xFFFF6B6B);
        c.drawRect(carX - dp(12f), carY - dp(4f), carX - dp(9f), carY - dp(1f), paint);
    }

    /** Fireflies over the bank and the moon broken up in the river. */
    private void drawRiverLife(Canvas c, float w, float h) {
        double t = sessionSeconds;
        for (int i = 0; i < 8; i++) {
            float ry = h * 0.915f + (i % 4) * dp(5f);
            float len = dp(30f) + (float) Math.abs(Math.sin(t * 1.5 + i)) * dp(30f);
            float rx = w * 0.82f + (float) Math.sin(t * 0.8 + i * 1.3) * dp(12f);
            paint.setColor(0x66E9EEF5);
            c.drawRect(rx - len / 2f, ry, rx + len / 2f, ry + dp(1.5f), paint);
        }
        if (season() == WINTER || stormAmt > 0.5f) {
            return; // no fireflies in the snow or the rain
        }
        int n = season() == SUMMER ? 26 : 14;
        for (int i = 0; i < n; i++) {
            float fx = (float) ((i * 0.13 + Math.sin(t * 0.3 + i) * 0.03) % 1.0) * w;
            float fy = h * 0.84f + (float) Math.sin(t * 0.9 + i * 2) * dp(18f);
            float glow = 0.5f + 0.5f * (float) Math.sin(t * 3 + i * 1.7);
            if (glow > 0.3f) {
                Fx.glow(c, fx, fy, dp(10f), ((int) (glow * 150) << 24) | 0xDFFF7A);
                paint.setColor(0xFFF4FFB0);
                c.drawCircle(fx, fy, dp(1.8f), paint);
            }
        }
    }

    /** The battery bank and its lightning mast, on the hill above the wheel. */
    private void drawBattery(Canvas c, float w, float h) {
        float x0 = w * 0.025f;
        float top = h * 0.64f;
        float cellW = dp(20f);
        float cellH = dp(36f);
        float gap = dp(6f);
        float mastX = w * 0.06f;
        // Mast.
        paint.setColor(0xFF4A5468);
        c.drawRect(mastX - dp(2f), h * 0.50f, mastX + dp(2f), top, paint);
        paint.setStrokeWidth(dp(1.5f));
        c.drawLine(mastX - dp(10f), top, mastX, h * 0.56f, paint);
        c.drawLine(mastX + dp(10f), top, mastX, h * 0.56f, paint);
        paint.setColor(boltLife > 0 && boltCharges ? 0xFFBFE4FF : 0xFF8A93A6);
        c.drawCircle(mastX, h * 0.50f, dp(3.5f), paint);
        // Cable to the generator shed.
        paint.setColor(0xFF2A3648);
        paint.setStrokeWidth(dp(2f));
        c.drawLine(x0 + 4 * (cellW + gap), top + cellH * 0.5f, w * 0.12f + dp(40f), h * 0.84f - dp(30f), paint);
        for (int k = 0; k < 4; k++) {
            float cx = x0 + k * (cellW + gap);
            paint.setColor(0xFF1E2636);
            c.drawRoundRect(cx, top, cx + cellW, top + cellH, dp(3f), dp(3f), paint);
            paint.setColor(0xFF4A5468);
            c.drawRect(cx + cellW * 0.3f, top - dp(4f), cx + cellW * 0.7f, top, paint);
            float fill = Math.max(0f, Math.min(1f, battery * 4 - k));
            if (fill > 0) {
                paint.setColor(phase == BLACKOUT && !blackoutFailed ? 0xFFF0B132 : 0xFF6FD8FF);
                c.drawRect(cx + dp(3f), top + cellH - dp(3f) - (cellH - dp(6f)) * fill, cx + cellW - dp(3f), top + cellH - dp(3f), paint);
            }
        }
        if (battery > 0.02f) {
            glow(c, x0 + 2 * (cellW + gap), top + cellH * 0.5f, dp(60f), ((int) (40 + 60 * battery) << 24) | 0x6FD8FF);
        }
        label(c, "BATTERY " + Math.round(battery * 100) + "%", x0, top + cellH + dp(14f), 10f,
                battery > 0 ? 0xFF9FD8FF : FAINT, Paint.Align.LEFT);
    }

    /** Landmarks on the hill, built at lifetime energy milestones. */
    private void drawLandmarks(Canvas c, float w, float h, int litEff) {
        double t = sessionSeconds;
        for (int k = 0; k < LANDMARK.length; k++) {
            if (lifetimeJoules < LANDMARK_J[k]) {
                continue;
            }
            boolean lamp = litEff > 0 && litEff >= houses * LANDMARK_LIT[k];
            int wall = lamp ? 0xFF3A3F52 : 0xFF263045;
            int glowCol = 0xFFFFE08A;
            if (k == 0) {
                // Church: nave, tower, spire, cross, a glowing rose window.
                float x = w * 0.31f;
                float b = hillY(x, h) + dp(18f);
                paint.setColor(wall);
                c.drawRect(x - dp(26f), b - dp(40f), x + dp(22f), b, paint);
                c.drawRect(x + dp(10f), b - dp(70f), x + dp(30f), b, paint);
                path.reset();
                path.moveTo(x + dp(8f), b - dp(70f));
                path.lineTo(x + dp(20f), b - dp(104f));
                path.lineTo(x + dp(32f), b - dp(70f));
                path.close();
                c.drawPath(path, paint);
                path.reset();
                path.moveTo(x - dp(30f), b - dp(40f));
                path.lineTo(x - dp(4f), b - dp(58f));
                path.lineTo(x + dp(22f), b - dp(40f));
                path.close();
                paint.setColor(lamp ? 0xFF5A3A36 : 0xFF3A2E33);
                c.drawPath(path, paint);
                paint.setColor(0xFF8A93A6);
                c.drawRect(x + dp(19f), b - dp(116f), x + dp(21f), b - dp(102f), paint);
                c.drawRect(x + dp(15f), b - dp(112f), x + dp(25f), b - dp(110f), paint);
                if (lamp) {
                    glow(c, x - dp(4f), b - dp(24f), dp(40f), 0x66FFD37A);
                    paint.setColor(glowCol);
                    c.drawCircle(x - dp(4f), b - dp(26f), dp(7f), paint);
                    c.drawRect(x + dp(16f), b - dp(60f), x + dp(24f), b - dp(48f), paint);
                } else {
                    paint.setColor(0xFF141A26);
                    c.drawCircle(x - dp(4f), b - dp(26f), dp(7f), paint);
                }
            } else if (k == 1) {
                // Water tower: legs, tank, a ring of bulbs.
                float x = w * 0.44f;
                float b = hillY(x, h) + dp(16f);
                paint.setColor(0xFF4A5468);
                paint.setStrokeWidth(dp(3f));
                c.drawLine(x - dp(20f), b, x - dp(14f), b - dp(62f), paint);
                c.drawLine(x + dp(20f), b, x + dp(14f), b - dp(62f), paint);
                c.drawLine(x - dp(17f), b - dp(30f), x + dp(17f), b - dp(30f), paint);
                paint.setColor(wall);
                c.drawRoundRect(x - dp(28f), b - dp(92f), x + dp(28f), b - dp(60f), dp(8f), dp(8f), paint);
                path.reset();
                path.moveTo(x - dp(30f), b - dp(92f));
                path.lineTo(x, b - dp(110f));
                path.lineTo(x + dp(30f), b - dp(92f));
                path.close();
                c.drawPath(path, paint);
                if (lamp) {
                    glow(c, x, b - dp(76f), dp(56f), 0x44FFD37A);
                    for (int bulb = 0; bulb < 7; bulb++) {
                        boolean twinkle = ((int) (t * 3) + bulb) % 4 != 0;
                        paint.setColor(twinkle ? glowCol : 0xFFB08A40);
                        c.drawCircle(x - dp(24f) + bulb * dp(8f), b - dp(76f), dp(2.2f), paint);
                    }
                }
            } else if (k == 2) {
                // Town hall: columns, pediment, and a clock showing the real time.
                float x = w * 0.535f;
                float b = hillY(x, h) + dp(18f);
                paint.setColor(wall);
                c.drawRect(x - dp(44f), b - dp(44f), x + dp(44f), b, paint);
                c.drawRect(x - dp(13f), b - dp(80f), x + dp(13f), b - dp(44f), paint);
                path.reset();
                path.moveTo(x - dp(50f), b - dp(44f));
                path.lineTo(x, b - dp(62f));
                path.lineTo(x + dp(50f), b - dp(44f));
                path.close();
                c.drawPath(path, paint);
                for (int win = 0; win < 5; win++) {
                    float wx = x - dp(36f) + win * dp(16f);
                    paint.setColor(lamp ? glowCol : 0xFF141A26);
                    c.drawRect(wx, b - dp(34f), wx + dp(8f), b - dp(14f), paint);
                }
                float cy = b - dp(64f);
                if (lamp) {
                    glow(c, x, cy, dp(30f), 0x66FFF1C0);
                }
                paint.setColor(lamp ? 0xFFFFF4D6 : 0xFF6A7488);
                c.drawCircle(x, cy, dp(9f), paint);
                long local = System.currentTimeMillis() + tzOffsetMs;
                double minutes = (local / 60000.0) % 60;
                double hours = (local / 3600000.0) % 12;
                double ma = minutes / 60 * Math.PI * 2 - Math.PI / 2;
                double ha = hours / 12 * Math.PI * 2 - Math.PI / 2;
                paint.setColor(0xFF1A2030);
                paint.setStrokeWidth(dp(1.6f));
                c.drawLine(x, cy, x + (float) Math.cos(ma) * dp(7f), cy + (float) Math.sin(ma) * dp(7f), paint);
                paint.setStrokeWidth(dp(2.2f));
                c.drawLine(x, cy, x + (float) Math.cos(ha) * dp(4.5f), cy + (float) Math.sin(ha) * dp(4.5f), paint);
            } else {
                // Lighthouse on the far point above the wheel, its beam sweeping the valley.
                float x = w * 0.205f;
                float b = hillY(x, h) + dp(22f);
                float top = b - dp(120f);
                if (lamp) {
                    double a = t * 0.9;
                    float reach = (float) Math.cos(a);
                    float len = dp(520f) * reach;
                    int alpha = (int) (60 * Math.abs(reach));
                    paint.setColor((alpha << 24) | 0xFFF1B0);
                    path.reset();
                    path.moveTo(x, top - dp(10f));
                    path.lineTo(x + len, top - dp(10f) - dp(40f));
                    path.lineTo(x + len, top - dp(10f) + dp(34f));
                    path.close();
                    c.drawPath(path, paint);
                }
                path.reset();
                path.moveTo(x - dp(13f), b);
                path.lineTo(x - dp(8f), top);
                path.lineTo(x + dp(8f), top);
                path.lineTo(x + dp(13f), b);
                path.close();
                paint.setColor(0xFFD8DCE4);
                c.drawPath(path, paint);
                paint.setColor(0xFFB23A3A);
                for (int band = 0; band < 3; band++) {
                    float y0 = b - dp(20f) - band * dp(36f);
                    float inset = dp(1.5f) + band * dp(1.5f);
                    c.drawRect(x - dp(13f) + inset + dp(1f), y0 - dp(14f), x + dp(13f) - inset - dp(1f), y0, paint);
                }
                paint.setColor(0xFF2A3446);
                c.drawRect(x - dp(10f), top - dp(18f), x + dp(10f), top, paint);
                paint.setColor(lamp ? 0xFFFFF4C0 : 0xFF3A4254);
                c.drawRect(x - dp(7f), top - dp(15f), x + dp(7f), top - dp(4f), paint);
                paint.setColor(0xFFB23A3A);
                path.reset();
                path.moveTo(x - dp(12f), top - dp(18f));
                path.lineTo(x, top - dp(30f));
                path.lineTo(x + dp(12f), top - dp(18f));
                path.close();
                c.drawPath(path, paint);
                if (lamp) {
                    glow(c, x, top - dp(10f), dp(36f), 0x88FFF1B0);
                }
            }
        }
    }

    /** A silhouette standing in a lit window; arms up when the town is cheering. */
    private void drawPerson(Canvas c, float cx, float bottom, float ww, float wh, boolean arms) {
        paint.setColor(0xDD3A2618);
        float r = wh * 0.17f;
        float headY = bottom - wh * 0.55f;
        c.drawCircle(cx, headY, r, paint);
        c.drawRoundRect(cx - ww * 0.28f, bottom - wh * 0.36f, cx + ww * 0.28f, bottom + r, r, r, paint);
        if (arms) {
            paint.setStrokeWidth(Math.max(1f, r * 0.7f));
            float wave = (float) Math.sin(sessionSeconds * 10) * r;
            c.drawLine(cx - ww * 0.24f, bottom - wh * 0.32f, cx - ww * 0.36f + wave, headY - r * 1.8f, paint);
            c.drawLine(cx + ww * 0.24f, bottom - wh * 0.32f, cx + ww * 0.36f - wave, headY - r * 1.8f, paint);
        }
    }

    /** Whether someone is standing in this window right now: people come and go every few seconds. */
    private boolean occupied(int house, int window) {
        double period = 5 + (house % 5);
        int slot = (int) ((sessionSeconds + house * 3.1 + window * 1.7) / period);
        int hash = house * 73856093 ^ slot * 19349663 ^ window * 83492791;
        return ((hash >>> 5) % 5) < 2;
    }

    private void drawWindow(Canvas c, int i, int win, float x0, float y0, float ww, float wh, boolean litHouse) {
        if (!litHouse) {
            paint.setColor(0xFF141A26);
            c.drawRect(x0, y0, x0 + ww, y0 + wh, paint);
            return;
        }
        paint.setColor(0xFFFFE08A);
        c.drawRect(x0, y0, x0 + ww, y0 + wh, paint);
        boolean arms = cheer > 0f;
        if (arms || occupied(i, win)) {
            float sway = (float) Math.sin(sessionSeconds * 0.7 + i + win) * ww * 0.18f;
            c.save();
            c.clipRect(x0, y0, x0 + ww, y0 + wh);
            drawPerson(c, x0 + ww * 0.5f + sway, y0 + wh, ww, wh, arms);
            c.restore();
        }
        if (season() == SPRING) {
            // Window box of flowers under each lit window.
            paint.setColor(0xFF4A3A2A);
            c.drawRect(x0 - ww * 0.08f, y0 + wh, x0 + ww * 1.08f, y0 + wh * 1.22f, paint);
            for (int f = 0; f < 3; f++) {
                paint.setColor(f == 1 ? 0xFFFFD1E0 : 0xFFFF7A9C);
                c.drawCircle(x0 + ww * (0.15f + f * 0.35f), y0 + wh * 1.02f, ww * 0.12f, paint);
            }
        }
    }

    /** Season decorations on one house: snow and holiday bulbs, pumpkins. */
    private void decorateHouse(Canvas c, int i, float hx, float hy, float s, float wallTop, boolean litHouse) {
        int season = season();
        if (season == WINTER) {
            paint.setColor(0xFFE8EEF8);
            path.reset();
            path.moveTo(hx - s * 0.62f, wallTop);
            path.lineTo(hx, wallTop - s * 0.42f);
            path.lineTo(hx + s * 0.62f, wallTop);
            path.lineTo(hx + s * 0.48f, wallTop - s * 0.04f);
            path.lineTo(hx, wallTop - s * 0.34f);
            path.lineTo(hx - s * 0.48f, wallTop - s * 0.04f);
            path.close();
            c.drawPath(path, paint);
            if (litHouse) {
                for (int b = 0; b < 6; b++) {
                    float f = b / 5f;
                    float bx = hx - s * 0.58f + f * s * 1.16f;
                    float by = wallTop - (1f - Math.abs(f - 0.5f) * 2f) * s * 0.40f + s * 0.02f;
                    boolean twinkle = ((int) (sessionSeconds * 2.5) + b + i) % 3 != 0;
                    paint.setColor(!twinkle ? 0xFF6A5A40 : (b % 3 == 0 ? 0xFFFF5A5A : b % 3 == 1 ? 0xFF6AE68A : 0xFFFFD27A));
                    c.drawCircle(bx, by, Math.max(dp(1.4f), s * 0.05f), paint);
                }
            }
        } else if (season == AUTUMN) {
            float px = hx - s * 0.26f;
            float pr = s * 0.11f;
            if (litHouse) {
                glow(c, px, hy - pr, s * 0.45f, 0x66FF8A1E);
            }
            paint.setColor(0xFFE0701E);
            c.drawOval(px - pr * 1.25f, hy - pr * 2f, px + pr * 1.25f, hy, paint);
            paint.setColor(0xFF4A6A2A);
            c.drawRect(px - pr * 0.15f, hy - pr * 2.5f, px + pr * 0.15f, hy - pr * 1.9f, paint);
            paint.setColor(litHouse ? 0xFFFFE08A : 0xFF7A3A10);
            c.drawCircle(px - pr * 0.45f, hy - pr * 1.2f, pr * 0.2f, paint);
            c.drawCircle(px + pr * 0.45f, hy - pr * 1.2f, pr * 0.2f, paint);
            c.drawRect(px - pr * 0.5f, hy - pr * 0.65f, px + pr * 0.5f, hy - pr * 0.45f, paint);
        }
    }

    /** Snow, blossom, or leaves drifting across the town; paper lanterns in summer. */
    private void drawSeasonAir(Canvas c, float w, float h, float share) {
        double t = sessionSeconds;
        int season = season();
        if (season == SUMMER) {
            // Strings of paper lanterns across the town, lit left to right with the town.
            for (int s = 0; s < 3; s++) {
                float x0 = w * (0.26f + s * 0.25f);
                float x1 = x0 + w * 0.22f;
                float y0 = h * (0.60f + (s % 2) * 0.08f);
                paint.setColor(0xFF4A5468);
                paint.setStrokeWidth(dp(1f));
                float px = x0;
                float py = y0;
                for (int k = 1; k <= 10; k++) {
                    float f = k / 10f;
                    float x = x0 + (x1 - x0) * f;
                    float y = y0 + (float) Math.sin(f * Math.PI) * dp(22f);
                    c.drawLine(px, py, x, y, paint);
                    px = x;
                    py = y;
                }
                for (int k = 1; k < 10; k++) {
                    float f = k / 10f;
                    float x = x0 + (x1 - x0) * f;
                    float y = y0 + (float) Math.sin(f * Math.PI) * dp(22f) + dp(6f)
                            + (float) Math.sin(t * 2 + k + s) * dp(1.5f);
                    boolean litLantern = share > (s * 9 + k) / 30f;
                    int col = k % 3 == 0 ? 0xFFFF7A5A : k % 3 == 1 ? 0xFFFFD27A : 0xFFFF9FC8;
                    if (litLantern) {
                        Fx.glow(c, x, y, dp(14f), 0x55FFD27A);
                    }
                    paint.setColor(litLantern ? col : 0xFF3A3040);
                    c.drawOval(x - dp(4f), y - dp(5f), x + dp(4f), y + dp(5f), paint);
                }
            }
            return;
        }
        int n = season == WINTER ? 70 : 28;
        for (int i = 0; i < n; i++) {
            float speed = season == WINTER ? dp(34f + (i % 5) * 6f) : dp(28f + (i % 4) * 8f);
            float y = (float) ((i * 53.7 * dp(1f) + t * speed) % (h + dp(20f))) - dp(10f);
            float drift = season == WINTER ? dp(10f) : dp(-30f);
            float x = (float) (((i * 0.1379 * w) + t * drift + Math.sin(t * 1.2 + i) * dp(14f)) % w);
            if (x < 0) {
                x += w;
            }
            if (season == WINTER) {
                paint.setColor(0xCCFFFFFF);
                c.drawCircle(x, y, dp(1.2f + (i % 3) * 0.6f), paint);
            } else {
                float tumble = (float) Math.sin(t * 3 + i) * dp(3f);
                paint.setColor(season == SPRING ? (i % 2 == 0 ? 0xDDFFC8DA : 0xDDFFE6EE)
                        : (i % 3 == 0 ? 0xFFE0701E : i % 3 == 1 ? 0xFFC9452A : 0xFFE0A92A));
                c.drawOval(x - dp(4f), y - Math.abs(tumble) - dp(1f), x + dp(4f), y + Math.abs(tumble) + dp(1f), paint);
            }
        }
    }

    private void drawRain(Canvas c, float w, float h) {
        if (stormAmt < 0.03f) {
            return;
        }
        double t = sessionSeconds;
        paint.setStrokeWidth(dp(1.3f));
        paint.setColor(((int) (110 * stormAmt) << 24) | 0xA8C4E8);
        for (int i = 0; i < 120; i++) {
            float x = (float) (((i * 0.0831 * w * 7) + t * dp(120f)) % (w + dp(80f))) - dp(40f);
            float y = (float) ((i * 91.3 + t * dp(900f + (i % 5) * 60f)) % (h + dp(40f))) - dp(20f);
            c.drawLine(x, y, x - dp(6f), y + dp(20f), paint);
        }
    }

    private void drawBolt(Canvas c) {
        if (boltLife <= 0f) {
            return;
        }
        float a = Math.min(1f, boltLife / 0.2f);
        int n = bolt.length / 2;
        paint.setStrokeWidth(dp(9f));
        paint.setColor(((int) (70 * a) << 24) | 0x9FC8FF);
        for (int k = 1; k < n; k++) {
            c.drawLine(bolt[k * 2 - 2], bolt[k * 2 - 1], bolt[k * 2], bolt[k * 2 + 1], paint);
        }
        paint.setStrokeWidth(dp(3f));
        paint.setColor(((int) (255 * a) << 24) | 0xF4F8FF);
        for (int k = 1; k < n; k++) {
            c.drawLine(bolt[k * 2 - 2], bolt[k * 2 - 1], bolt[k * 2], bolt[k * 2 + 1], paint);
        }
    }

    /* ---------------- frame ---------------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        supply += (watts - supply) * Math.min(1f, dt / 4f);
        float demand = demand();
        double work = status == null ? -1 : status.meter.workJoules;
        if (lastWork >= 0 && work > lastWork) {
            double before = lifetimeJoules;
            lifetimeJoules += work - lastWork;
            int grown = housesFor(lifetimeJoules);
            while (grown > houses) {
                int i = order[houses];
                bornAt[i] = (float) sessionSeconds;
                houses++;
                fx.burst(houseX[i] * w, houseY[i] * h - dp(10f), 18, dp(110f), 1.0f, dp(2.4f), 0xFFFFD27A, true);
                say("NEW HOUSE - THE TOWN HAS " + houses, 0xFFFFD27A);
            }
            int up = storeysFor(lifetimeJoules);
            if (up > storeys) {
                storeys = up;
                say("A HOUSE GAINED A STOREY", 0xFFFFD27A);
            }
            for (int k = 0; k < LANDMARK_J.length; k++) {
                if (before < LANDMARK_J[k] && lifetimeJoules >= LANDMARK_J[k]) {
                    say("NEW LANDMARK: " + LANDMARK[k], ACCENT);
                    fireworks = 2.5f;
                }
            }
        }
        lastWork = work;
        boolean rowing = isClockRunning();
        stepEvents(dt, rowing, w, h);
        // The share of the town lit follows supply over demand, so easy rowing lights part of it
        // and meeting demand lights it all. (Was all-or-nothing around the demand line.)
        float targetShare = Math.max(0f, Math.min(1f, supply / demand));
        if (!rowing && supply < 5) {
            targetShare = 0f;
        }
        if (darkTimer > 0f) {
            targetShare = 0f;
        }
        lit += (targetShare * houses - lit) * Math.min(1f, dt * (darkTimer > 0f ? 3f : 0.5f));
        // In a blackout the reserve decides how much of the lit town survives; the warning flickers.
        float factor = 1f;
        if (phase == BLACKOUT && !blackoutFailed) {
            factor = 0.2f + 0.8f * reserve;
        } else if (phase == WARNING) {
            factor = ((int) (sessionSeconds * 9)) % 3 == 0 ? 0.6f : 1f;
        }
        int litCount = Math.round(lit * factor);
        if (rowing) {
            runSeconds += dt;
            litSeconds += dt * (litCount / (float) Math.max(1, houses));
        }
        wheel += boat.value() * dt * 1.4f;
        flow += (supply / 40f) * dt;
        turbine += dt * (status == null ? 6 : 6 + status.strokeRate * 1.2f) * (1 + stormAmt * 1.5f);
        fx.step(dt, dp(60f));
        bannerTime = Math.max(0f, bannerTime - dt);
        float share = houses > 0 ? litCount / (float) houses : 0f;

        // Night sky, moon and stars.
        if (sky == null || skyH != h) {
            sky = new LinearGradient(0, 0, 0, h, 0xFF050B1E, 0xFF14213D, Shader.TileMode.CLAMP);
            skyH = h;
        }
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(sky);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0xCCFFFFFF);
        int starW = Math.max(1, (int) w);
        int starH = Math.max(1, (int) (h * 0.45f));
        for (int i = 0; i < 70; i++) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 2 + i);
            paint.setAlpha((int) ((80 + 120 * twinkle) * (1 - stormAmt)));
            c.drawCircle((i * 197) % starW, (i * 89) % starH, dp(1.1f), paint);
        }
        paint.setAlpha(255);
        Fx.glow(c, w * 0.82f, h * 0.14f, dp(80f), 0x44E9EEF5);
        paint.setColor(0xFFE9EEF5);
        c.drawCircle(w * 0.82f, h * 0.14f, dp(26f), paint);
        drawNightSky(c, w, h);

        // Hills.
        paint.setColor(0xFF0B1426);
        path.reset();
        path.moveTo(0, h * 0.58f);
        for (float x = 0; x <= w; x += dp(40f)) {
            path.lineTo(x, hillY(x, h));
        }
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        c.drawPath(path, paint);
        if (season() == WINTER) {
            // A dusting of snow along the ridge.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3f));
            paint.setColor(0x66E8EEF8);
            path.reset();
            path.moveTo(0, hillY(0, h));
            for (float x = dp(40f); x <= w; x += dp(40f)) {
                path.lineTo(x, hillY(x, h));
            }
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        drawLandmarks(c, w, h, litCount);
        drawTrain(c, w, h);
        // 3.19.6: the right half of the tablet screen was bare. A wind farm turning with your rate,
        // a road with headlights, and an aurora when the town is fully lit.
        drawValleyLife(c, w, h, share);
        drawBattery(c, w, h);

        // River and the water wheel generator.
        paint.setColor(0xFF12304A);
        c.drawRect(0, h * 0.90f, w, h, paint);
        drawRiverLife(c, w, h);
        float wx = w * 0.12f;
        float wy = h * 0.84f;
        float wr = dp(40f);
        paint.setColor(0xFF5A4B3A);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(4f));
        c.drawCircle(wx, wy, wr, paint);
        for (int s = 0; s < 8; s++) {
            double a = wheel + s * Math.PI / 4;
            c.drawLine(wx, wy, wx + (float) Math.cos(a) * wr, wy + (float) Math.sin(a) * wr, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3A3A44);
        c.drawRect(wx + wr * 0.6f, wy - dp(40f), wx + wr * 1.8f, wy + dp(10f), paint);

        // Power line to the town with current flowing along it.
        float lineY = h * 0.48f;
        paint.setColor(0xFF2A3648);
        paint.setStrokeWidth(dp(2f));
        c.drawLine(wx + wr * 1.2f, wy - dp(40f), w * 0.26f, lineY, paint);
        c.drawLine(w * 0.26f, lineY, w * 0.98f, lineY + dp(10f), paint);
        float need = phase == BLACKOUT || phase == WARNING ? blackoutNeed() : demand;
        paint.setColor(supply >= need ? 0xFFF5C518 : 0xFFF0655D);
        for (int d = 0; d < 14; d++) {
            float f = (float) (((flow + d / 14f) % 1.0 + 1.0) % 1.0);
            float px = w * 0.26f + f * w * 0.72f;
            float py = lineY + f * dp(10f);
            c.drawCircle(px, py, dp(2.5f), paint);
        }

        // Houses, far rows first; a house exists once the town has grown to its place in the
        // lighting order. (Was a search of the whole order for every house, every frame.)
        for (int i = 0; i < MAX_HOUSES; i++) {
            on[i] = rank[i] < Math.min(litCount, houses);
        }
        for (int pass = 0; pass < MAX_HOUSES; pass++) {
            int i = order[MAX_HOUSES - 1 - pass];
            if (rank[i] >= houses) {
                continue;
            }
            float hx = houseX[i] * w;
            float hy = houseY[i] * h;
            float s = houseS[i] * dp(22f);
            boolean tall = rank[i] < storeys;
            float grow = bornAt[i] >= 0 ? Math.min(1f, (float) (sessionSeconds - bornAt[i]) / 1.2f) : 1f;
            if (grow < 1f) {
                c.save();
                c.scale(1f, grow * (2f - grow), hx, hy); // rises out of the ground, easing in
            }
            float wallTop = hy - s * (tall ? 1.0f : 0.6f);
            // Walls and roof in moonlit tones, so the unlit town still reads as a town.
            paint.setColor(on[i] ? 0xFF3A3F52 : 0xFF2A3346);
            c.drawRect(hx - s * 0.5f, wallTop, hx + s * 0.5f, hy, paint);
            path.reset();
            path.moveTo(hx - s * 0.62f, wallTop);
            path.lineTo(hx, wallTop - s * 0.42f);
            path.lineTo(hx + s * 0.62f, wallTop);
            path.close();
            paint.setColor(on[i] ? 0xFF5A3A36 : 0xFF3A2E33);
            c.drawPath(path, paint);
            paint.setColor(on[i] ? 0xFF6A4A30 : 0xFF1A2030);
            c.drawRect(hx - s * 0.08f, hy - s * 0.28f, hx + s * 0.08f, hy, paint);   // door
            if (on[i]) {
                float flicker = 0.85f + 0.15f * (float) Math.sin(sessionSeconds * 3 + i);
                glow(c, hx, hy - s * 0.3f, s * 1.6f, ((int) (0x66 * flicker) << 24) | 0xFFD37A);
            }
            float ww = s * 0.28f;
            float wh = s * 0.26f;
            drawWindow(c, i, 0, hx - s * 0.42f, hy - s * 0.54f, ww, wh, on[i]);
            drawWindow(c, i, 1, hx + s * 0.14f, hy - s * 0.54f, ww, wh, on[i]);
            if (tall) {
                drawWindow(c, i, 2, hx - s * 0.42f, hy - s * 0.92f, ww, wh, on[i]);
                drawWindow(c, i, 3, hx + s * 0.14f, hy - s * 0.92f, ww, wh, on[i]);
            }
            if (on[i]) {
                // A street lamp beside every lit house.
                paint.setColor(0xFF8A93A6);
                c.drawRect(hx + s * 0.72f, hy - s * 0.9f, hx + s * 0.76f, hy, paint);
                glow(c, hx + s * 0.74f, hy - s * 0.92f, s * 0.6f, 0x88FFE8A0);
                paint.setColor(0xFFFFF2C0);
                c.drawCircle(hx + s * 0.74f, hy - s * 0.92f, s * 0.07f, paint);
            }
            decorateHouse(c, i, hx, hy, s, wallTop, on[i]);
            if (grow < 1f) {
                c.restore();
            }
        }
        drawSeasonAir(c, w, h, share);
        drawRain(c, w, h);
        drawBolt(c);
        fx.draw(c);
        if (flash > 0f) {
            paint.setColor(((int) (120 * flash) << 24) | 0xDDE8FF);
            c.drawRect(0, 0, w, h, paint);
        }
        if (phase == BLACKOUT && !blackoutFailed) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 6);
            Fx.vignette(c, w, h, 0.35f + 0.35f * (1 - reserve) * pulse, 0x400505);
        } else if (darkTimer > 0f || (supply < demand * 0.8f && rowing)) {
            Fx.vignette(c, w, h, 0.5f, 0x050510);
        }

        // HUD: supply against demand.
        bold(c, Math.round(share * 100) + "% LIT", dp(18f), dp(40f), 30f, share > 0.8f ? ACCENT : share > 0.4f ? WARN : BAD, Paint.Align.LEFT);
        label(c, litCount + " of " + houses + " houses  ·  " + growthLine(), dp(18f), dp(58f), 10f, FAINT, Paint.Align.LEFT);
        float bx = w * 0.5f;
        float bw = w * 0.33f;
        float by = dp(26f);
        float max = Math.max(need * 1.6f, supply * 1.1f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(bx, by, bx + bw, by + dp(14f), dp(7f), dp(7f), paint);
        paint.setColor(supply >= need ? ACCENT : BAD);
        c.drawRoundRect(bx, by, bx + bw * Math.min(1f, supply / max), by + dp(14f), dp(7f), dp(7f), paint);
        float dx = bx + bw * need / max;
        paint.setColor(phase == BLACKOUT || phase == WARNING ? BAD : TEXT);
        c.drawRect(dx - dp(2f), by - dp(6f), dx + dp(2f), by + dp(20f), paint);
        label(c, Math.round(supply) + " W SUPPLY", bx, by + dp(32f), 10f, TEXT, Paint.Align.LEFT);
        label(c, (phase == BLACKOUT || phase == WARNING ? "HOLD " : "DEMAND ") + Math.round(need) + " W", dx, by - dp(10f), 9f,
                phase == BLACKOUT || phase == WARNING ? BAD : FAINT, Paint.Align.CENTER);

        // Season chip, top right: tap to preview the other seasons.
        float cw = dp(92f);
        seasonHit.set(w - cw - dp(12f), dp(14f), w - dp(12f), dp(42f));
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(seasonHit, dp(14f), dp(14f), paint);
        label(c, SEASON[season()] + (seasonOverride >= 0 ? " *" : ""), seasonHit.centerX(), seasonHit.centerY() + dp(4f), 11f, TEXT, Paint.Align.CENTER);
        label(c, heldTotal + " blackouts held", seasonHit.centerX(), seasonHit.bottom + dp(14f), 9f, FAINT, Paint.Align.CENTER);

        drawEventPanel(c, w, h, rowing);

        if (bannerTime > 0f && banner != null) {
            int a = (int) (255 * Math.min(1f, bannerTime / 0.5f));
            bold(c, banner, w / 2f, h * 0.44f, 22f, (a << 24) | (bannerColor & 0xFFFFFF), Paint.Align.CENTER);
        }
        if (!rowing) {
            bold(c, "THE TOWN IS DARK - ROW TO LIGHT IT", w / 2f, h * 0.36f, 20f, WARN, Paint.Align.CENTER);
        }
    }

    private String growthLine() {
        for (int k = 0; k < LANDMARK_J.length; k++) {
            if (lifetimeJoules < LANDMARK_J[k]) {
                return "next landmark: " + LANDMARK[k] + " in " + Math.round((LANDMARK_J[k] - lifetimeJoules) / 1000) + " kJ";
            }
        }
        if (houses < MAX_HOUSES) {
            return "a new house every 20 kJ";
        }
        return storeys < MAX_HOUSES ? storeys + " two-storey homes, one more every 20 kJ" : "the town is complete";
    }

    /** What is coming, and what is at stake right now. */
    private void drawEventPanel(Canvas c, float w, float h, boolean rowing) {
        float cx = w * 0.5f;
        float y = dp(96f);
        double p = eventClock % CYCLE;
        if (phase == STORM) {
            int left = (int) Math.ceil(STORM_END - p);
            bold(c, "STORM  " + left + "s", cx, y, 20f, BLUE, Paint.Align.CENTER);
            label(c, "surge - pull a stroke harder than the last few - to strike the mast (+20% battery)  ·  "
                    + strikes + " strikes", cx, y + dp(18f), 10f, TEXT, Paint.Align.CENTER);
        } else if (phase == WARNING) {
            int left = (int) Math.ceil(BLACKOUT_START - p);
            bold(c, "BLACKOUT IN " + left, cx, y, 22f, BAD, Paint.Align.CENTER);
            label(c, "hold " + Math.round(blackoutNeed()) + " W for 15 s  ·  the battery covers you first",
                    cx, y + dp(18f), 10f, TEXT, Paint.Align.CENTER);
        } else if (phase == BLACKOUT) {
            if (blackoutFailed) {
                bold(c, "GRID DOWN", cx, y, 22f, BAD, Paint.Align.CENTER);
                label(c, darkTimer > 0 ? "restarting the grid..." : "keep rowing to relight the town",
                        cx, y + dp(18f), 10f, TEXT, Paint.Align.CENTER);
                return;
            }
            int left = (int) Math.ceil(BLACKOUT_END - p);
            bold(c, "BLACKOUT - HOLD " + Math.round(blackoutNeed()) + " W  " + left + "s", cx, y, 22f, BAD, Paint.Align.CENTER);
            float bw = w * 0.30f;
            float bx = cx - bw / 2f;
            float by = y + dp(10f);
            paint.setColor(0x33FFFFFF);
            c.drawRoundRect(bx, by, bx + bw, by + dp(12f), dp(6f), dp(6f), paint);
            paint.setColor(reserve > 0.5f ? ACCENT : reserve > 0.25f ? WARN : BAD);
            c.drawRoundRect(bx, by, bx + bw * reserve, by + dp(12f), dp(6f), dp(6f), paint);
            label(c, "GRID RESERVE" + (battery > 0.01f && supply < blackoutNeed() ? "  ·  battery covering" : ""),
                    cx, by + dp(26f), 10f, TEXT, Paint.Align.CENTER);
        } else if (rowing) {
            double toStorm = p < STORM_START ? STORM_START - p : CYCLE - p + STORM_START;
            double toBlackout = p < WARN_START ? WARN_START - p : CYCLE - p + WARN_START;
            String next = toStorm < toBlackout
                    ? "storm in " + (int) Math.ceil(toStorm) + "s - get ready to surge"
                    : "blackout in " + (int) Math.ceil(toBlackout) + "s" + (battery < 0.2f ? " - battery low" : "");
            label(c, next, cx, y, 12f, toStorm < toBlackout ? 0xFF9FB8FF : 0xFFF0A09A, Paint.Align.CENTER);
        }
    }
}
