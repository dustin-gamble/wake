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
                n++;
            }
        }
        return n;
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

        if (driving && watts > 0) {
            concrete += watts * dt;
            while (concrete >= BLOCK_COST) {
                concrete -= BLOCK_COST;
                int[] plot = lowestPlot();
                if (height[plot[0]][plot[1]] + pendingFor(plot[0], plot[1]) < MAX_HEIGHT) {
                    Falling f = new Falling();
                    f.gx = plot[0];
                    f.gy = plot[1];
                    f.target = height[plot[0]][plot[1]] + pendingFor(plot[0], plot[1]);
                    f.z = f.target + 9f;
                    f.color = tint[plot[0]][plot[1]];
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
                height[f.gx][f.gy]++;
                lifetime++;
                placedThisSession++;
                recomputeTallest();
                falling.remove(i);
                float[] p = project(f.gx, f.gy, f.target + 1, cx, cy, tw, th, bh, cos, sin, mid);
                fx.burst(p[0], p[1], 14, dp(90f), 0.45f, dp(3f), 0xCCD8C9A8, true);
                shake.kick(dp(2.5f));
                continue;
            }
            drawBlock(c, f.gx, f.gy, f.z, f.color, cx, cy, tw, th, bh, cos, sin, mid, true);
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

        // Concrete meter: the next block.
        float meterW = w * 0.34f;
        float mx = w * 0.5f - meterW / 2f;
        float my = h - dp(40f);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(mx, my, mx + meterW, my + dp(10f), dp(5f), dp(5f), paint);
        paint.setColor(WARN);
        c.drawRoundRect(mx, my, mx + meterW * (concrete / BLOCK_COST), my + dp(10f), dp(5f), dp(5f), paint);
        label(c, driving ? "NEXT BLOCK" : "CRANES IDLE - ROW TO BUILD", w * 0.5f, my - dp(6f), 9f,
                driving ? FAINT : WARN, Paint.Align.CENTER);

        float fy = h - dp(12f);
        float col = w / 3f;
        stat(c, col * 0.5f, fy, String.valueOf(placedThisSession), "THIS SESSION");
        stat(c, col * 1.5f, fy, tallest + " floors", "TALLEST");
        stat(c, col * 2.5f, fy, falling.isEmpty() ? "--" : String.valueOf(falling.size()), "IN THE AIR");
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
                boolean lit = ((seed >>> ((f * 2 + k) % 60)) & 1L) == 1L;
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
