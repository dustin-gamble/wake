package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Skyline: you row, a city gets built.
 *
 * <p>Power buys concrete; when enough has accumulated a block is craned in over the lowest plot
 * and falls, at a speed set by how hard you are pulling. Blocks stack into towers, towers gain
 * windows that light as evening comes in, and the whole thing is drawn in isometric with the
 * camera panning slowly around it so you see the skyline from every side.
 *
 * <p>No fail state on purpose - this is the one to row to when a chase would be exhausting. The
 * city is persistent: every block you have ever placed is still standing.
 *
 * <p>3.15.0, the block factory: each drive fills the crane's hopper, and a strong stroke - 10% or
 * 30% above the rower's typical power - drops a two- or three-floor block at a discount, so pulling
 * hard visibly builds faster. At night the city's windows light up in proportion to the energy put
 * in this session, and a "today" tower on the right grows with this session's floors.
 *
 * <p>Towers are drawn as single extruded prisms rather than stacks of cubes: 25 plots of up to 14
 * blocks would be 350 sorted quads a frame, where 25 prisms is nothing.
 */
final class SkylineGame extends GameView {

    private static final int GRID = 5;
    private static final int MAX_HEIGHT = 14;
    /** Watt-seconds per block. About one block every few strokes at a steady 130 W. */
    private static final float BLOCK_COST = 260f;

    private static final class Falling {
        int gx;
        int gy;
        float z;          // current height in block units
        float target;
        int color;
        int units = 1;    // floors in this block
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();

    private final int[][] height = new int[GRID][GRID];
    private final int[][] tint = new int[GRID][GRID];
    private final java.util.List<Falling> falling = new java.util.ArrayList<>();
    private final long[] windowSeed = new long[GRID * GRID];

    private float angle;
    private float concrete;
    private int placedThisSession;
    private int lifetime;
    private int tallest;
    /** Floors the next block will have, from the last stroke's power. */
    private int nextUnits = 1;
    private PulseMeter.Stroke lastStrokeSeen;
    private double workAtStart = -1;
    /** Share of windows lit tonight: the energy put in this session. */
    private float litShare;

    private static final int[] PALETTE = {
            0xFF4C6EA8, 0xFF3E8C7E, 0xFF8A6BB0, 0xFF9A6B4F, 0xFF5E7A90, 0xFF7A8A4F,
    };

    SkylineGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        java.util.Random r = new java.util.Random(31);
        for (int i = 0; i < windowSeed.length; i++) {
            windowSeed[i] = r.nextLong();
        }
    }

    @Override
    protected void onStart() {
        concrete = 0f;
        placedThisSession = 0;
        nextUnits = 1;
        workAtStart = -1;
        litShare = 0f;
        falling.clear();
        angle = 0.6f;
        lifetime = Math.round(bests.get("city.blocks", 0f));
        // Rebuild the standing city from the lifetime total, filling plots evenly.
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                height[gx][gy] = 0;
                tint[gx][gy] = PALETTE[(gx * GRID + gy) % PALETTE.length];
            }
        }
        for (int i = 0; i < lifetime && i < GRID * GRID * MAX_HEIGHT; i++) {
            int[] plot = lowestPlot();
            height[plot[0]][plot[1]]++;
        }
        recomputeTallest();
    }

    @Override
    protected void onStop() {
        bests.recordHighest("city.blocks", lifetime);
        bests.recordHighest("city.tallest", tallest);
    }

    private int[] lowestPlot() {
        int bestX = 0;
        int bestY = 0;
        int best = Integer.MAX_VALUE;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                int hh = height[gx][gy] + pendingFor(gx, gy);
                // Centre plots are favoured slightly so the skyline peaks in the middle.
                int bias = Math.abs(gx - GRID / 2) + Math.abs(gy - GRID / 2);
                int score = hh * 4 + bias;
                if (hh < MAX_HEIGHT && score < best) {
                    best = score;
                    bestX = gx;
                    bestY = gy;
                }
            }
        }
        return new int[] {bestX, bestY};
    }

    private int pendingFor(int gx, int gy) {
        int n = 0;
        for (Falling f : falling) {
            if (f.gx == gx && f.gy == gy) {
                n += f.units;
            }
        }
        return n;
    }

    /** Watt-seconds for a block of this many floors: bigger blocks are cheaper per floor. */
    private static float cost(int units) {
        return BLOCK_COST * (1f + 0.75f * (units - 1));
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (workAtStart < 0) {
            workAtStart = s.meter.workJoules;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastStrokeSeen) {
            lastStrokeSeen = stroke;
            double power = !Double.isNaN(stroke.averagePowerW) ? stroke.averagePowerW : s.watts;
            double ratio = power / Math.max(1.0, profile.typicalWatts());
            nextUnits = ratio >= 1.3 ? 3 : ratio >= 1.1 ? 2 : 1;
        }
        // Ten minutes of typical work lights every window.
        double work = Math.max(0, s.meter.workJoules - Math.max(0, workAtStart));
        litShare = (float) Math.min(1.0, work / Math.max(1.0, profile.typicalWatts() * 600.0));
    }

    private void recomputeTallest() {
        tallest = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                tallest = Math.max(tallest, height[gx][gy]);
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
        float speed = boat.value();

        // The camera pans a little faster while you are working, so effort feels like momentum.
        angle += dt * (0.10f + speed * 0.035f);
        shake.step(dt);
        fx.step(dt, dp(220f));

        // Build whenever the rowing clock runs. The first version needed `driving`, which is only true
        // briefly after each speed reading changes - on the tablet the crane said "idle" mid-row and
        // placed nothing.
        boolean building = isClockRunning() && watts > 0;
        if (building) {
            concrete += watts * dt;
            while (concrete >= cost(nextUnits)) {
                int[] plot = lowestPlot();
                int room = MAX_HEIGHT - height[plot[0]][plot[1]] - pendingFor(plot[0], plot[1]);
                int units = Math.min(nextUnits, room);
                concrete -= cost(Math.max(1, units));
                if (units > 0) {
                    Falling f = new Falling();
                    f.gx = plot[0];
                    f.gy = plot[1];
                    f.target = height[plot[0]][plot[1]] + pendingFor(plot[0], plot[1]);
                    f.z = f.target + 9f;
                    f.color = tint[plot[0]][plot[1]];
                    f.units = units;
                    falling.add(f);
                }
            }
        }

        // Geometry. Tile size shrinks as the city grows so it stays on screen.
        float tallScale = 1f - Math.min(0.35f, tallest / (float) MAX_HEIGHT * 0.35f);
        float tw = Math.min(w, h) * 0.105f * tallScale;
        float th = tw * 0.52f;
        float bh = tw * 0.62f;
        float cx = w * 0.5f;
        float cy = h * 0.70f;
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float mid = (GRID - 1) / 2f;

        // Sky: a slow day-night cycle so a long row visibly passes time.
        float tod = (float) ((Math.sin(activeSeconds / 150.0 - Math.PI / 2) + 1) / 2);   // 0 night..1 day
        c.save();
        c.translate(shake.dx, shake.dy);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(new LinearGradient(0, 0, 0, h,
                blend(0xFF0B1430, 0xFF2E6FB0, tod), blend(0xFF2A1C38, 0xFFBFD9EE, tod),
                Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        if (tod < 0.5f) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) ((0.5f - tod) * 2 * 200));
            for (int i = 0; i < 40; i++) {
                c.drawCircle((i * 197 % (int) w), (i * 131 % (int) (h * 0.5f)), dp(1.1f), paint);
            }
            paint.setAlpha(255);
        }
        // Sun or moon arcing across.
        float sunX = w * (0.15f + 0.7f * ((float) ((activeSeconds / 150.0) % 1.0)));
        float sunY = h * (0.40f - 0.26f * (float) Math.sin(Math.PI * ((activeSeconds / 150.0) % 1.0)));
        Fx.glow(c, sunX, sunY, dp(60f), tod > 0.5f ? 0x66FFE8A8 : 0x55BFD9EE);
        paint.setColor(tod > 0.5f ? 0xFFFFE8A8 : 0xFFE9EEF5);
        c.drawCircle(sunX, sunY, dp(20f), paint);
        drawSkyTraffic(c, w, h, tod);

        // Ground plate.
        path.reset();
        for (int i = 0; i < 4; i++) {
            float gx = (i == 0 || i == 3) ? -0.6f : GRID - 0.4f;
            float gy = (i < 2) ? -0.6f : GRID - 0.4f;
            float rx = (gx - mid) * cos - (gy - mid) * sin;
            float ry = (gx - mid) * sin + (gy - mid) * cos;
            float sx = cx + (rx - ry) * tw;
            float sy = cy + (rx + ry) * th;
            if (i == 0) {
                path.moveTo(sx, sy);
            } else {
                path.lineTo(sx, sy);
            }
        }
        path.close();
        paint.setColor(blend(0xFF15202E, 0xFF3F4A56, tod));
        c.drawPath(path, paint);

        // Towers, far to near.
        Integer[] order = new Integer[GRID * GRID];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        final float fcos = cos;
        final float fsin = sin;
        final float fmid = mid;
        java.util.Arrays.sort(order, (a, b) -> {
            float da = depth(a % GRID, a / GRID, fcos, fsin, fmid);
            float db = depth(b % GRID, b / GRID, fcos, fsin, fmid);
            return Float.compare(da, db);
        });
        for (int idx : order) {
            int gx = idx % GRID;
            int gy = idx / GRID;
            if (height[gx][gy] > 0) {
                drawTower(c, gx, gy, height[gx][gy], tint[gx][gy], cx, cy, tw, th, bh, cos, sin, mid,
                        tod, idx);
            }
        }

        // Falling blocks, and landings.
        for (int i = falling.size() - 1; i >= 0; i--) {
            Falling f = falling.get(i);
            float fall = 4.5f + speed * 2.6f;       // rowing harder brings them down faster
            f.z -= fall * dt;
            if (f.z <= f.target) {
                height[f.gx][f.gy] = Math.min(MAX_HEIGHT, height[f.gx][f.gy] + f.units);
                lifetime += f.units;
                placedThisSession += f.units;
                recomputeTallest();
                falling.remove(i);
                float[] p = project(f.gx, f.gy, f.target + 1, cx, cy, tw, th, bh, cos, sin, mid);
                fx.burst(p[0], p[1], 14, dp(90f), 0.45f, dp(3f), 0xCCD8C9A8, true);
                shake.kick(dp(2.5f));
                continue;
            }
            for (int u = f.units - 1; u >= 0; u--) {
                drawBlock(c, f.gx, f.gy, f.z + u, f.color, cx, cy, tw, th, bh, cos, sin, mid, true);
            }
            // Guide line down to the plot so you can see where it is going.
            float[] from = project(f.gx, f.gy, f.z, cx, cy, tw, th, bh, cos, sin, mid);
            float[] to = project(f.gx, f.gy, f.target, cx, cy, tw, th, bh, cos, sin, mid);
            paint.setColor(0x55FFE28A);
            paint.setStrokeWidth(dp(1.5f));
            c.drawLine(from[0], from[1], to[0], to[1], paint);
        }
        fx.draw(c);
        c.restore();

        // HUD.
        bold(c, String.valueOf(lifetime), w * 0.5f, dp(34f), 34f, ACCENT, Paint.Align.CENTER);
        label(c, "BLOCKS IN THE CITY", w * 0.5f, dp(48f), 9f, FAINT, Paint.Align.CENTER);

        drawCrane(c, w, h);
        drawTodayTower(c, w, h);

        float fy = h - dp(12f);
        float col = w / 3f;
        stat(c, col * 0.5f, fy, String.valueOf(placedThisSession), "THIS SESSION");
        stat(c, col * 1.5f, fy, tallest + " floors", "TALLEST");
        stat(c, col * 2.5f, fy, falling.isEmpty() ? "--" : String.valueOf(falling.size()), "IN THE AIR");
    }

    /** The crane and its hopper, top left: every drive pours concrete in. */
    /**
     * 3.19.5: the sky was empty. Clouds drift by day, a plane crosses with blinking lights and a
     * contrail, a flock passes, and at night searchlights sweep and the odd shooting star falls.
     */
    private void drawSkyTraffic(Canvas c, float w, float h, float tod) {
        double t = sessionSeconds;
        int cloudAlpha = (int) (60 + 150 * tod);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {
            float span = w + dp(400f);
            float cx = (float) (((i * 431 + 60) + t * dp(6f + i * 2f)) % span) - dp(200f);
            float cy = h * (0.10f + (i % 3) * 0.08f);
            float sc = 0.7f + (i % 2) * 0.4f;
            paint.setColor((cloudAlpha << 24) | 0xFFFFFF);
            c.drawOval(cx - dp(80f) * sc, cy - dp(12f) * sc, cx + dp(80f) * sc, cy + dp(12f) * sc, paint);
            c.drawOval(cx - dp(38f) * sc, cy - dp(28f) * sc, cx + dp(40f) * sc, cy + dp(4f) * sc, paint);
        }
        // A plane every 24 s, left to right, high up.
        double lap = (t % 24) / 24;
        float px = (float) (-dp(80f) + (w + dp(160f)) * lap);
        float py = h * 0.08f + (float) lap * h * 0.04f;
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0x55FFFFFF);
        c.drawLine(px - dp(160f), py + dp(3f), px - dp(20f), py, paint);
        paint.setColor(tod > 0.4f ? 0xFFE9EEF5 : 0xFF3A4660);
        c.drawRoundRect(px - dp(18f), py - dp(3f), px + dp(18f), py + dp(3f), dp(3f), dp(3f), paint);
        c.drawRect(px - dp(4f), py - dp(10f), px + dp(4f), py + dp(10f), paint);
        if (((int) (t * 2)) % 2 == 0) {
            paint.setColor(0xFFFF4A4A);
            c.drawCircle(px - dp(1f), py - dp(10f), dp(2.5f), paint);
            paint.setColor(0xFF4AFF7A);
            c.drawCircle(px - dp(1f), py + dp(10f), dp(2.5f), paint);
        }
        if (tod > 0.3f) {
            // Birds by day.
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xAA1A2230);
            float fx = (float) (w - ((t * dp(40f)) % (w + dp(300f))));
            for (int b = 0; b < 6; b++) {
                float bx = fx + b * dp(22f) + (b % 2) * dp(8f);
                float by = h * 0.22f + (b % 3) * dp(9f);
                float flap = (float) Math.sin(t * 8 + b) * dp(4f);
                c.drawLine(bx - dp(7f), by - flap, bx, by, paint);
                c.drawLine(bx, by, bx + dp(7f), by - flap, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else {
            // Searchlights from the city, and a shooting star now and then.
            for (int k = 0; k < 2; k++) {
                double a = -Math.PI / 2 + Math.sin(t * 0.5 + k * 2.2) * 0.5;
                float ox = w * (k == 0 ? 0.38f : 0.62f);
                float oy = h * 0.72f;
                float len = h * 0.9f;
                float tx = ox + (float) Math.cos(a) * len;
                float ty = oy + (float) Math.sin(a) * len;
                float nx = (float) -Math.sin(a) * dp(40f);
                float ny = (float) Math.cos(a) * dp(40f);
                path.reset();
                path.moveTo(ox, oy);
                path.lineTo(tx + nx, ty + ny);
                path.lineTo(tx - nx, ty - ny);
                path.close();
                paint.setColor(0x1ADFEBFF);
                c.drawPath(path, paint);
            }
            double star = (t % 9) / 9;
            if (star < 0.12) {
                float f = (float) (star / 0.12);
                float sx = w * 0.2f + f * w * 0.35f;
                float sy = h * 0.06f + f * h * 0.14f;
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (255 * (1 - f)) << 24) | 0xFFFFFF);
                c.drawLine(sx - dp(60f), sy - dp(24f), sx, sy, paint);
            }
        }
    }

    private void drawCrane(Canvas c, float w, float h) {
        float baseX = dp(40f);
        float baseY = h * 0.62f;
        float topY = h * 0.14f;
        paint.setColor(0xFFF0B132);
        c.drawRect(baseX - dp(4f), topY, baseX + dp(4f), baseY, paint);
        for (float y = topY + dp(12f); y < baseY; y += dp(18f)) {
            paint.setStrokeWidth(dp(1.5f));
            c.drawLine(baseX - dp(4f), y, baseX + dp(4f), y + dp(12f), paint);
        }
        c.drawRect(baseX - dp(10f), topY - dp(4f), baseX + dp(150f), topY + dp(4f), paint);
        float hookX = baseX + dp(120f);
        paint.setColor(0xFF9AA5B1);
        paint.setStrokeWidth(dp(1.5f));
        c.drawLine(hookX, topY + dp(4f), hookX, topY + dp(46f), paint);
        // Hopper: fills with concrete toward the next block.
        float hopW = dp(64f);
        float hopH = dp(54f);
        float hx = hookX - hopW / 2f;
        float hy = topY + dp(46f);
        paint.setColor(0x55FFFFFF);
        c.drawRect(hx, hy, hx + hopW, hy + hopH, paint);
        float full = Math.min(1f, concrete / cost(nextUnits));
        paint.setColor(nextUnits >= 3 ? ACCENT : nextUnits == 2 ? BLUE : WARN);
        c.drawRect(hx, hy + hopH * (1f - full), hx + hopW, hy + hopH, paint);
        bold(c, nextUnits + (nextUnits == 1 ? " FLOOR" : " FLOORS"), hookX, hy + hopH + dp(16f), 11f,
                nextUnits > 1 ? ACCENT : TEXT, Paint.Align.CENTER);
        boolean working = isClockRunning();
        label(c, working ? "pull harder for bigger blocks" : "CRANES IDLE - ROW TO BUILD", hookX,
                hy + hopH + dp(30f), 8.5f, working ? FAINT : WARN, Paint.Align.CENTER);
    }

    /** This session's floors as their own tower on the right, windows lit by today's energy. */
    private void drawTodayTower(Canvas c, float w, float h) {
        float floorH = dp(9f);
        float bw = dp(48f);
        float right = w - dp(22f);
        float bottom = h - dp(56f);
        int floors = Math.min(placedThisSession, (int) ((bottom - h * 0.12f) / floorH));
        paint.setColor(0xFF2A3648);
        c.drawRect(right - bw - dp(6f), bottom, right + dp(6f), bottom + dp(4f), paint);
        for (int f = 0; f < floors; f++) {
            float y = bottom - (f + 1) * floorH;
            paint.setColor(0xFF3E5A7E);
            c.drawRect(right - bw, y, right, y + floorH - dp(1f), paint);
            boolean lit = ((f * 7919) % 100) / 100f < 0.2f + 0.8f * litShare;
            paint.setColor(lit ? 0xFFFFE8A8 : 0xFF22324A);
            c.drawRect(right - bw + dp(8f), y + dp(2f), right - bw + dp(18f), y + floorH - dp(3f), paint);
            c.drawRect(right - dp(18f), y + dp(2f), right - dp(8f), y + floorH - dp(3f), paint);
        }
        bold(c, "+" + placedThisSession, right - bw / 2f, bottom - floors * floorH - dp(8f), 13f, ACCENT,
                Paint.Align.CENTER);
        label(c, "TODAY", right - bw / 2f, bottom + dp(16f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    private float depth(int gx, int gy, float cos, float sin, float mid) {
        float rx = (gx - mid) * cos - (gy - mid) * sin;
        float ry = (gx - mid) * sin + (gy - mid) * cos;
        return rx + ry;
    }

    private float[] project(int gx, int gy, float z, float cx, float cy, float tw, float th, float bh,
                            float cos, float sin, float mid) {
        float rx = (gx - mid) * cos - (gy - mid) * sin;
        float ry = (gx - mid) * sin + (gy - mid) * cos;
        return new float[] {cx + (rx - ry) * tw, cy + (rx + ry) * th - z * bh};
    }

    /** One extruded prism from the ground to {@code floors}, with lit windows. */
    private void drawTower(Canvas c, int gx, int gy, int floors, int color, float cx, float cy,
                           float tw, float th, float bh, float cos, float sin, float mid, float tod,
                           int seedIdx) {
        float[] top = project(gx, gy, floors, cx, cy, tw, th, bh, cos, sin, mid);
        float sx = top[0];
        float sy = top[1];
        float bodyH = floors * bh;

        int left = blend(color, 0xFF000000, 0.42f);
        int right = blend(color, 0xFF000000, 0.18f);
        int roof = blend(color, 0xFFFFFFFF, 0.16f);

        // Left face.
        paint.setColor(left);
        path.reset();
        path.moveTo(sx - tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bodyH);
        path.lineTo(sx - tw, sy + bodyH);
        path.close();
        c.drawPath(path, paint);
        // Right face.
        paint.setColor(right);
        path.reset();
        path.moveTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bodyH);
        path.lineTo(sx + tw, sy + bodyH);
        path.close();
        c.drawPath(path, paint);
        // Roof.
        paint.setColor(roof);
        path.reset();
        path.moveTo(sx, sy - th);
        path.lineTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx - tw, sy);
        path.close();
        c.drawPath(path, paint);

        // Windows: one row per floor, lit by a per-plot pattern as night falls.
        float night = 1f - tod;
        long seed = windowSeed[seedIdx];
        for (int f = 0; f < floors; f++) {
            float wy = sy + bodyH - f * bh - bh * 0.55f;
            for (int k = 0; k < 2; k++) {
                // More of the city lights up the more energy has gone in this session.
                long bits = seed >>> ((f * 3 + k * 5) % 56);
                boolean lit = ((bits & 0xFFL) / 255f) < 0.2f + 0.8f * litShare;
                int wcol = lit && night > 0.25f
                        ? blend(0xFF3A3A2A, 0xFFFFE8A8, Math.min(1f, night * 1.4f))
                        : blend(color, 0xFF000000, 0.55f);
                paint.setColor(wcol);
                float ox = tw * (0.32f + k * 0.34f);
                // Left face windows.
                c.drawRect(sx - ox - tw * 0.1f, wy + th * (1 - ox / tw) * 0.5f,
                        sx - ox + tw * 0.1f, wy + th * (1 - ox / tw) * 0.5f + bh * 0.28f, paint);
                // Right face windows.
                c.drawRect(sx + ox - tw * 0.1f, wy + th * (1 - ox / tw) * 0.5f,
                        sx + ox + tw * 0.1f, wy + th * (1 - ox / tw) * 0.5f + bh * 0.28f, paint);
            }
        }
        // A mast with a blinking light on the tall ones.
        if (floors >= 8) {
            paint.setColor(0xFF9AA5B1);
            c.drawRect(sx - dp(1.5f), sy - th - dp(22f), sx + dp(1.5f), sy - th, paint);
            boolean on = ((int) (activeSeconds * 1.5) % 2) == 0;
            paint.setColor(on ? 0xFFFF4D4D : 0x66FF4D4D);
            c.drawCircle(sx, sy - th - dp(24f), dp(3f), paint);
        }
    }

    private void drawBlock(Canvas c, int gx, int gy, float z, int color, float cx, float cy, float tw,
                           float th, float bh, float cos, float sin, float mid, boolean shadow) {
        float[] p = project(gx, gy, z, cx, cy, tw, th, bh, cos, sin, mid);
        float sx = p[0];
        float sy = p[1];
        paint.setColor(blend(color, 0xFF000000, 0.42f));
        path.reset();
        path.moveTo(sx - tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bh);
        path.lineTo(sx - tw, sy + bh);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(blend(color, 0xFF000000, 0.18f));
        path.reset();
        path.moveTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bh);
        path.lineTo(sx + tw, sy + bh);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(blend(color, 0xFFFFFFFF, 0.22f));
        path.reset();
        path.moveTo(sx, sy - th);
        path.lineTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx - tw, sy);
        path.close();
        c.drawPath(path, paint);
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
