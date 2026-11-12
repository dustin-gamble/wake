package com.codex.waterrowerdiagnostic;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;

/**
 * Small effects toolkit shared by the games: particles, screen shake, glow and vignette.
 * Everything is drawn with primitives - there are no image assets anywhere in this app.
 */
final class Fx {

    /** A pool of simple particles: position, velocity, life, size, colour. */
    static final class Particles {
        private static final int MAX = 240;
        private final float[] x = new float[MAX];
        private final float[] y = new float[MAX];
        private final float[] vx = new float[MAX];
        private final float[] vy = new float[MAX];
        private final float[] life = new float[MAX];
        private final float[] maxLife = new float[MAX];
        private final float[] size = new float[MAX];
        private final int[] color = new int[MAX];
        private final boolean[] gravity = new boolean[MAX];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int next;

        void spawn(float px, float py, float pvx, float pvy, float seconds, float radius, int argb,
                   boolean falls) {
            int i = next;
            next = (next + 1) % MAX;
            x[i] = px;
            y[i] = py;
            vx[i] = pvx;
            vy[i] = pvy;
            life[i] = seconds;
            maxLife[i] = seconds;
            size[i] = radius;
            color[i] = argb;
            gravity[i] = falls;
        }

        /** A burst of {@code n} particles from a point, spread in every direction. */
        void burst(float px, float py, int n, float speed, float seconds, float radius, int argb,
                   boolean falls) {
            for (int k = 0; k < n; k++) {
                double a = Math.random() * Math.PI * 2;
                float s = speed * (0.4f + (float) Math.random() * 0.6f);
                spawn(px, py, (float) Math.cos(a) * s, (float) Math.sin(a) * s,
                        seconds * (0.6f + (float) Math.random() * 0.4f), radius, argb, falls);
            }
        }

        void step(float dt, float gravityPx) {
            for (int i = 0; i < MAX; i++) {
                if (life[i] <= 0) {
                    continue;
                }
                life[i] -= dt;
                x[i] += vx[i] * dt;
                y[i] += vy[i] * dt;
                if (gravity[i]) {
                    vy[i] += gravityPx * dt;
                }
                vx[i] *= 0.98f;
            }
        }

        void draw(Canvas c) {
            for (int i = 0; i < MAX; i++) {
                if (life[i] <= 0) {
                    continue;
                }
                float f = life[i] / maxLife[i];
                paint.setColor(color[i]);
                paint.setAlpha((int) (((color[i] >>> 24) & 0xFF) * f));
                c.drawCircle(x[i], y[i], size[i] * (0.4f + 0.6f * f), paint);
            }
        }
    }

    /** Decaying random offset for the whole scene. */
    static final class Shake {
        private float amount;
        float dx;
        float dy;

        void kick(float px) {
            amount = Math.max(amount, px);
        }

        void step(float dt) {
            amount = Math.max(0f, amount - amount * 5f * dt - 0.5f * dt);
            dx = (float) (Math.random() - 0.5) * 2f * amount;
            dy = (float) (Math.random() - 0.5) * 2f * amount;
        }
    }

    private static final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Gradients for {@link #glow}, centred on the origin and reused. 3.19.5: the scenery pass calls
     * glow ~70 times a frame in places (Mega Pull's bulbs), and a new RadialGradient each call is
     * garbage the tablet's older hardware would stutter on. The emulator could not show that.
     *
     * <p>3.25.0: the cache was an {@code LruCache<Long, ...>} of 96. Two things were wrong with it,
     * both reported independently by several of the graphics-pass agents: every lookup boxed its
     * key, so a cache HIT still allocated - ~70 objects a frame in Mega Pull alone - and 96 slots
     * were fewer than one game uses (Night Grid ~80, Zone Row ~50), so games evicted each other.
     * It is now a direct-mapped table of 512 primitive keys: a hit allocates nothing, and a miss
     * only overwrites the one slot its key hashes to.
     */
    private static final int GLOW_SLOTS = 512;
    private static final long[] glowKeys = new long[GLOW_SLOTS];
    private static final RadialGradient[] glowShaders = new RadialGradient[GLOW_SLOTS];

    /** Soft radial glow, e.g. behind the player or around a coin. */
    static void glow(Canvas c, float cx, float cy, float radius, int argb) {
        int r = Math.max(1, Math.round(radius));
        // Alpha quantised to 16 steps so a continuously fading glow reuses a handful of shaders.
        int colour = (argb & 0x00FFFFFF) | ((((argb >>> 24) & 0xFF) & 0xF0) << 24);
        long key = ((long) r << 32) ^ (colour & 0xFFFFFFFFL);
        long mixed = key * 0x9E3779B97F4A7C15L;
        int slot = (int) (mixed >>> 55) & (GLOW_SLOTS - 1);
        RadialGradient g = glowShaders[slot];
        if (g == null || glowKeys[slot] != key) {
            g = new RadialGradient(0, 0, r, colour, colour & 0x00FFFFFF, Shader.TileMode.CLAMP);
            glowShaders[slot] = g;
            glowKeys[slot] = key;
        }
        c.save();
        c.translate(cx, cy);
        glowPaint.setShader(g);
        c.drawCircle(0, 0, r, glowPaint);
        glowPaint.setShader(null);
        c.restore();
    }

    /**
     * One vignette gradient per screen size and tint, built at FULL edge opacity. The strength is
     * then applied through the paint's alpha, which a shader is multiplied by - the same behaviour
     * that once dimmed two skies by accident, used on purpose. Before 3.25.0 this built a new
     * RadialGradient on every call, and Zombie Run, Crew Boat and Night Grid call it every frame.
     */
    private static final int VIGNETTE_SLOTS = 4;
    private static final long[] vignetteKeys = new long[VIGNETTE_SLOTS];
    private static final RadialGradient[] vignetteShaders = new RadialGradient[VIGNETTE_SLOTS];
    private static int vignetteNext;

    /** Darkens the edges; {@code strength} 0..1, tinted so danger can read as red. */
    static void vignette(Canvas c, float w, float h, float strength, int tint) {
        if (strength <= 0.01f) {
            return;
        }
        int a = (int) (Math.min(1f, strength) * 200);
        int rgb = tint & 0x00FFFFFF;
        int iw = Math.round(w);
        int ih = Math.round(h);
        // Width and height in 20 bits each, tint in 24: unique for any real screen.
        long key = ((long) (iw & 0xFFFFF) << 44) | ((long) (ih & 0xFFFFF) << 24) | rgb;
        RadialGradient g = null;
        for (int i = 0; i < VIGNETTE_SLOTS; i++) {
            if (vignetteShaders[i] != null && vignetteKeys[i] == key) {
                g = vignetteShaders[i];
                break;
            }
        }
        if (g == null) {
            g = new RadialGradient(w / 2f, h / 2f, Math.max(w, h) * 0.75f,
                    new int[] {0x00000000, 0x00000000, 0xFF000000 | rgb}, new float[] {0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP);
            vignetteShaders[vignetteNext] = g;
            vignetteKeys[vignetteNext] = key;
            vignetteNext = (vignetteNext + 1) % VIGNETTE_SLOTS;
        }
        vignettePaint.setColor(0xFFFFFFFF);
        vignettePaint.setAlpha(a);
        vignettePaint.setShader(g);
        c.drawRect(0, 0, w, h, vignettePaint);
        vignettePaint.setShader(null);
        vignettePaint.setAlpha(255);
    }

    /** Horizontal speed lines that thicken with speed, for a sense of pace. */
    static void speedLines(Canvas c, Paint p, float w, float h, float speed, double t, float density) {
        float strength = Math.max(0f, Math.min(1f, (speed - 2.2f) / 2.5f));
        if (strength <= 0f) {
            return;
        }
        p.setColor(0xFFFFFFFF);
        p.setStrokeWidth(1.5f * density);
        for (int i = 0; i < 14; i++) {
            float y = h * ((i * 0.071f + 0.03f) % 1f);
            float len = (40f + (i % 5) * 30f) * density * strength;
            float x = (float) ((w + len) - ((t * (900 + i * 140) * density) % (w + len)));
            p.setAlpha((int) (70 * strength));
            c.drawLine(x, y, x + len, y, p);
        }
    }
}
