package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * NIGHT GRID: a valley town after dark, lit by your power.
 *
 * <p>A water wheel at the river turns with the boat and feeds the grid. Stay above the town's demand
 * and street by street the lights come on, nearest the wheel first; fall below it and the town goes
 * dark from the edges in. Demand is set from the rower's typical power and swells in the evening
 * peak, so holding a steady effort is what keeps the lights on - stopping has a visible cost.
 *
 * <p>The town grows across sessions: one new house for every 20 kJ ever rowed here.
 */
final class NightGridGame extends GameView {

    private static final int MAX_HOUSES = 140;
    private static final double JOULES_PER_HOUSE = 20000;

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

    private double lifetimeJoules;
    private double lastWork = -1;
    private int houses;
    private float lit;          // houses lit, eased
    private float supply;
    private double litSeconds;
    private double runSeconds;
    private float wheel;
    private float flow;

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
        lit = 0f;
        supply = 0f;
        litSeconds = 0;
        runSeconds = 0;
        lastWork = -1;
    }

    @Override
    protected void onStop() {
        bests.putFloat("grid.joules", (float) lifetimeJoules);
        bests.recordHighest("grid.houses", houses);
        if (runSeconds > 120) {
            bests.recordHighest("grid.percent", (float) (100 * litSeconds / runSeconds));
        }
    }

    private static int housesFor(double joules) {
        return (int) Math.min(MAX_HOUSES, 24 + joules / JOULES_PER_HOUSE);
    }

    private float demand() {
        // An evening peak every few minutes: 60% of typical power, swelling by a tenth. Was 80%, and
        // on the tablet an easy 32 W row against 106 W demand lit nothing at all - it looked dead.
        return (float) (profile.typicalWatts() * 0.6 * (1 + 0.1 * Math.sin(sessionSeconds / 45.0)));
    }

    /** 3.19.5: shooting stars and a slow cloud over the moon, so the big sky moves. */
    private void drawNightSky(Canvas c, float w, float h) {
        double t = sessionSeconds;
        double star = (t % 7) / 7;
        if (star < 0.1) {
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
        for (int i = 0; i < 14; i++) {
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
            lifetimeJoules += work - lastWork;
            int grown = housesFor(lifetimeJoules);
            if (grown > houses) {
                houses = grown;
            }
        }
        lastWork = work;
        boolean rowing = isClockRunning();
        // The share of the town lit follows supply over demand, so easy rowing lights part of it
        // and meeting demand lights it all. (Was all-or-nothing around the demand line.)
        float targetShare = Math.max(0f, Math.min(1f, supply / demand));
        if (!rowing && supply < 5) {
            targetShare = 0f;
        }
        lit += (targetShare * houses - lit) * Math.min(1f, dt * 0.5f);
        if (rowing) {
            runSeconds += dt;
            litSeconds += dt * (lit / Math.max(1, houses));
        }
        wheel += boat.value() * dt * 1.4f;
        flow += (supply / 40f) * dt;

        // Night sky, moon and stars.
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(new LinearGradient(0, 0, 0, h, 0xFF050B1E, 0xFF14213D, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0xCCFFFFFF);
        for (int i = 0; i < 70; i++) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 2 + i);
            paint.setAlpha((int) (80 + 120 * twinkle));
            c.drawCircle((i * 197) % (int) w, (i * 89) % (int) (h * 0.45f), dp(1.1f), paint);
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
            path.lineTo(x, h * 0.50f + (float) Math.sin(x / dp(160f)) * dp(26f));
        }
        path.lineTo(w, h);
        path.lineTo(0, h);
        path.close();
        c.drawPath(path, paint);
        drawTrain(c, w, h);

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
        paint.setColor(supply >= demand ? 0xFFF5C518 : 0xFFF0655D);
        for (int d = 0; d < 14; d++) {
            float f = (float) (((flow + d / 14f) % 1.0 + 1.0) % 1.0);
            float px = w * 0.26f + f * w * 0.72f;
            float py = lineY + f * dp(10f);
            c.drawCircle(px, py, dp(2.5f), paint);
        }

        // Houses, far rows first.
        int litCount = Math.round(lit);
        // Far rows first; a house exists once the town has grown to its place in the lighting order.
        // (Was a search of the whole order for every house, every frame.)
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
            // Walls and roof in moonlit tones, so the unlit town still reads as a town.
            paint.setColor(on[i] ? 0xFF3A3F52 : 0xFF2A3346);
            c.drawRect(hx - s * 0.5f, hy - s * 0.6f, hx + s * 0.5f, hy, paint);
            path.reset();
            path.moveTo(hx - s * 0.62f, hy - s * 0.6f);
            path.lineTo(hx, hy - s * 1.02f);
            path.lineTo(hx + s * 0.62f, hy - s * 0.6f);
            path.close();
            paint.setColor(on[i] ? 0xFF5A3A36 : 0xFF3A2E33);
            c.drawPath(path, paint);
            paint.setColor(0xFF1A2030);
            c.drawRect(hx - s * 0.08f, hy - s * 0.28f, hx + s * 0.08f, hy, paint);   // door
            if (on[i]) {
                float flicker = 0.85f + 0.15f * (float) Math.sin(sessionSeconds * 3 + i);
                Fx.glow(c, hx, hy - s * 0.3f, s * 1.6f, ((int) (0x66 * flicker) << 24) | 0xFFD37A);
                paint.setColor(0xFFFFE08A);
                c.drawRect(hx - s * 0.38f, hy - s * 0.48f, hx - s * 0.14f, hy - s * 0.26f, paint);
                c.drawRect(hx + s * 0.14f, hy - s * 0.48f, hx + s * 0.38f, hy - s * 0.26f, paint);
                // A street lamp beside every lit house.
                paint.setColor(0xFF8A93A6);
                c.drawRect(hx + s * 0.72f, hy - s * 0.9f, hx + s * 0.76f, hy, paint);
                Fx.glow(c, hx + s * 0.74f, hy - s * 0.92f, s * 0.6f, 0x88FFE8A0);
                paint.setColor(0xFFFFF2C0);
                c.drawCircle(hx + s * 0.74f, hy - s * 0.92f, s * 0.07f, paint);
            } else {
                paint.setColor(0xFF141A26);
                c.drawRect(hx - s * 0.38f, hy - s * 0.48f, hx - s * 0.14f, hy - s * 0.26f, paint);
                c.drawRect(hx + s * 0.14f, hy - s * 0.48f, hx + s * 0.38f, hy - s * 0.26f, paint);
            }
        }
        if (supply < demand * 0.8f && rowing) {
            Fx.vignette(c, w, h, 0.5f, 0x050510);
        }

        // HUD: supply against demand.
        float share = houses > 0 ? lit / houses : 0f;
        bold(c, Math.round(share * 100) + "% LIT", dp(18f), dp(40f), 30f, share > 0.8f ? ACCENT : share > 0.4f ? WARN : BAD, Paint.Align.LEFT);
        label(c, litCount + " of " + houses + " houses  ·  the town grows one house per 20 kJ", dp(18f), dp(58f), 10f, FAINT, Paint.Align.LEFT);
        float bx = w * 0.5f;
        float bw = w * 0.36f;
        float by = dp(26f);
        float max = Math.max(demand * 1.6f, supply * 1.1f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(bx, by, bx + bw, by + dp(14f), dp(7f), dp(7f), paint);
        paint.setColor(supply >= demand ? ACCENT : BAD);
        c.drawRoundRect(bx, by, bx + bw * Math.min(1f, supply / max), by + dp(14f), dp(7f), dp(7f), paint);
        float dx = bx + bw * demand / max;
        paint.setColor(TEXT);
        c.drawRect(dx - dp(2f), by - dp(6f), dx + dp(2f), by + dp(20f), paint);
        label(c, Math.round(supply) + " W SUPPLY", bx, by + dp(32f), 10f, TEXT, Paint.Align.LEFT);
        label(c, "DEMAND " + Math.round(demand) + " W", dx, by - dp(10f), 9f, FAINT, Paint.Align.CENTER);
        if (!rowing) {
            bold(c, "THE TOWN IS DARK - ROW TO LIGHT IT", w / 2f, h * 0.36f, 20f, WARN, Paint.Align.CENTER);
        }
    }
}
