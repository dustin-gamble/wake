package com.codex.waterrowerdiagnostic;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Draws water and boats. Shared by Pace Boat, Ghost Race and anything else set on a river.
 *
 * <p>The water scrolls at the player's speed so effort reads as motion. Boats are drawn as hull
 * paths with a wake whose length follows speed; there are no image assets in this app.
 *
 * <p>3.25.0 graphics pass. The water has depth now - paler where it reflects the sky at the far
 * bank, deep and dark at the near edge, ripple rows that close up with distance, specular glints
 * that drift past with the boat (more of them the harder you row) and rings spreading where a fish
 * rose. Hulls have form: a contact shadow on the water, a cylindrical shade from a cached
 * gradient in unit space (so it works for any crew colour with no allocation), a lit gunwale, a
 * bow deck with its splash box, a cockpit, and a sculler with legs, torso, arms and a cap who slides and swings
 * through the stroke. Blades are spoons in the crew colour, squared in the water and feathered out
 * of it. Light comes from the top of the screen everywhere - the sky side.
 *
 * <p>Nothing here allocates per frame: the water gradient is rebuilt only when the band moves, the
 * hull, deck, blade and hull shade are built once in unit space and placed with a canvas
 * translate/scale, and the ripple rows go out through one preallocated {@code drawLines} buffer
 * instead of hundreds of separate line calls.
 */
final class RiverRenderer {

    private static final float TWO_PI = (float) (Math.PI * 2);

    /** Far (sky reflected at a grazing angle) to near (looking down into deep water). */
    private static final int[] WATER_COLOURS = {0xFF285172, 0xFF1A3D5B, 0xFF112F4A, 0xFF0B2135, 0xFF07172A};
    private static final float[] WATER_STOPS = {0f, 0.08f, 0.35f, 0.72f, 1f};

    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hullPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hullEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint oarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bladePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wakePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rowerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path hull = new Path();
    private final Path gunwale = new Path();
    private final RectF rect = new RectF();
    /**
     * Fills drawn in unit space under a canvas translate+scale, so the paths and the shade are
     * built once and never touched again: x is in half-lengths (bow at +1), y in beams.
     * (Repositioning a shared shader with setLocalMatrix would recreate its native object - and a
     * Java cleaner - on every boat, every frame.)
     */
    private final Path unitHull = new Path();
    private final Path unitDeck = new Path();
    /** Blade in its own unit space: x 0..1 from neck to tip, y -0.5..0.5 across. */
    private final Path unitBlade = new Path();
    /** Wake fan in unit space: x 0 at the stern to -1 at the end of the wake, y in beams. */
    private final Path unitFan = new Path();
    /** Fades the fan out along its length; unit space, built once. */
    private final LinearGradient fanShade;
    /** Unit-space (y -1..1) shade laid over any hull colour: lit top, dark keel side. */
    private final LinearGradient hullShade;

    private LinearGradient waterShader;
    private float waterShaderTop = Float.NaN;
    private float waterShaderBottom = Float.NaN;
    /** Reused for every ripple row; grown only when the view gets wider. */
    private float[] lines = new float[0];

    private float scroll;
    private float clock;
    private float speedSmoothed;
    private final float density;
    private final Fx.Particles spray = new Fx.Particles();
    private float sprayAccum;

    /** Rings spreading where a fish rose. Fixed slots, respawned from a hash - no Random. */
    private static final int RINGS = 8;
    private final float[] ringX = new float[RINGS];
    private final float[] ringY = new float[RINGS];
    private final float[] ringAge = new float[RINGS];
    private final float[] ringLife = new float[RINGS];
    private final float[] ringScroll = new float[RINGS];
    private int ringSeed = 17;

    RiverRenderer(float density) {
        this.density = density;
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeCap(Paint.Cap.ROUND);
        ripplePaint.setColor(Color.parseColor("#2A4A6B"));
        glintPaint.setStyle(Paint.Style.STROKE);
        glintPaint.setStrokeCap(Paint.Cap.ROUND);
        hullPaint.setStyle(Paint.Style.FILL);
        shadePaint.setStyle(Paint.Style.FILL);
        hullEdge.setStyle(Paint.Style.STROKE);
        hullEdge.setStrokeJoin(Paint.Join.ROUND);
        hullEdge.setStrokeWidth(dp(1.5f));
        wakePaint.setStyle(Paint.Style.STROKE);
        wakePaint.setStrokeCap(Paint.Cap.ROUND);
        rowerPaint.setColor(Color.parseColor("#E6EDF7"));
        rowerPaint.setStrokeCap(Paint.Cap.ROUND);
        oarPaint.setStyle(Paint.Style.STROKE);
        oarPaint.setStrokeCap(Paint.Cap.ROUND);
        oarPaint.setColor(Color.parseColor("#E8E3D3"));
        hullShade = new LinearGradient(0, -1, 0, 1,
                new int[]{0x70FFFFFF, 0x26FFFFFF, 0x00FFFFFF, 0x2A000000, 0x8C000000},
                new float[]{0f, 0.22f, 0.48f, 0.68f, 1f}, Shader.TileMode.CLAMP);
        shadePaint.setShader(hullShade);
        unitHull.moveTo(1f, 0f);
        unitHull.quadTo(0.55f, -1f, -0.2f, -1f);
        unitHull.lineTo(-0.8f, -0.6f);
        unitHull.quadTo(-1f, 0f, -0.8f, 0.6f);
        unitHull.lineTo(-0.2f, 1f);
        unitHull.quadTo(0.55f, 1f, 1f, 0f);
        unitHull.close();
        unitFan.moveTo(0f, -0.4f);
        unitFan.lineTo(-1f, -2f);
        unitFan.lineTo(-1f, 2f);
        unitFan.lineTo(0f, 0.4f);
        unitFan.close();
        fanShade = new LinearGradient(0, 0, -1, 0, 0x40BFE3FF, 0x00BFE3FF, Shader.TileMode.CLAMP);
        unitDeck.moveTo(0.97f, 0f);
        unitDeck.quadTo(0.66f, -0.66f, 0.36f, -0.62f);
        unitDeck.lineTo(0.36f, 0.62f);
        unitDeck.quadTo(0.66f, 0.66f, 0.97f, 0f);
        unitDeck.close();
        unitBlade.moveTo(-0.04f, -0.16f);
        unitBlade.quadTo(0.35f, -0.56f, 1f, -0.42f);
        unitBlade.quadTo(1.1f, 0f, 1f, 0.42f);
        unitBlade.quadTo(0.35f, 0.56f, -0.04f, 0.16f);
        unitBlade.close();
        for (int k = 0; k < RINGS; k++) {
            respawnRing(k);
            ringAge[k] = k / (float) RINGS;   // staggered so they do not all bloom at once
        }
    }

    private float dp(float v) {
        return v * density;
    }

    /** A cheap deterministic hash to 0..1, so scenery is stable and nothing calls Random. */
    private static float hash01(int n) {
        int h = n * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        return (h & 0xFFFFFF) / (float) 0x1000000;
    }

    /** Linear blend of two opaque colours; {@code t} 0 gives {@code a}. Keeps a's alpha. */
    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    private void respawnRing(int k) {
        ringSeed++;
        ringX[k] = -0.05f + 1.1f * hash01(ringSeed * 3 + 1);
        ringY[k] = 0.1f + 0.85f * hash01(ringSeed * 7 + 2);
        ringLife[k] = 2.2f + 1.8f * hash01(ringSeed * 11 + 5);
        ringAge[k] = 0f;
        ringScroll[k] = scroll;
    }

    /** Advances the scrolling water. Call once per frame with the player's speed. */
    void advance(float metresPerSecond, float dt, float pixelsPerMetre) {
        scroll += metresPerSecond * dt * pixelsPerMetre;
        if (dt > 0f && dt < 1f) {
            clock += dt;
            speedSmoothed += (metresPerSecond - speedSmoothed) * Math.min(1f, dt * 1.5f);
            for (int k = 0; k < RINGS; k++) {
                ringAge[k] += dt / ringLife[k];
                if (ringAge[k] >= 1f) {
                    respawnRing(k);
                }
            }
        }
        spray.step(dt, dp(40f));
    }

    /** Throws spray off a bow; call each frame for the player's boat. */
    void bowSpray(float bowX, float waterY, float speed, float dt) {
        if (speed < 1.2f) {
            return;
        }
        sprayAccum += dt * speed * 14f;
        while (sprayAccum >= 1f) {
            sprayAccum -= 1f;
            spray.spawn(bowX, waterY, -dp(20f) - (float) Math.random() * dp(60f) * speed / 4f,
                    -dp(30f) - (float) Math.random() * dp(40f), 0.5f, dp(2.2f), 0xCCBFE3FF, true);
        }
    }

    void drawSpray(Canvas c) {
        spray.draw(c);
    }

    private void ensureLines(int floats) {
        if (lines.length < floats) {
            lines = new float[floats + 64];   // only when the view grows, never per frame
        }
    }

    void drawWater(Canvas c, float top, float bottom, float width) {
        float span = bottom - top;
        if (span <= 0f || width <= 0f) {
            return;
        }
        if (waterShader == null || top != waterShaderTop || bottom != waterShaderBottom) {
            waterShaderTop = top;
            waterShaderBottom = bottom;
            waterShader = new LinearGradient(0, top, 0, bottom, WATER_COLOURS, WATER_STOPS,
                    Shader.TileMode.CLAMP);
        }
        rect.set(0, top, width, bottom);
        waterPaint.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha
        waterPaint.setShader(waterShader);
        c.drawRect(rect, waterPaint);
        waterPaint.setShader(null);

        // Rolling ripples in perspective: rows close up toward the far bank, and get longer,
        // taller and faster toward the near edge. A mid-tone ridge, a dark trough under it and a
        // bright crest on top - light from the sky side. Each layer is one drawLines call.
        int rows = 9;
        for (int row = 0; row < rows; row++) {
            float depth = (row + 0.6f) / rows;                     // 0 far .. 1 near
            float y0 = top + span * (float) Math.pow(depth, 1.3);
            float wave = dp(34f) + depth * dp(66f);
            float amp = dp(1.1f) + depth * dp(3.1f);
            float speedMul = 0.35f + 0.85f * depth;
            float phase = (scroll * speedMul) / wave * TWO_PI;
            float step = dp(7f) + depth * dp(6f);
            int segs = (int) (width / step) + 2;
            ensureLines(segs * 4);

            // Ridge line.
            int n = 0;
            float prevX = 0f;
            float prevY = y0 + (float) Math.sin(-phase) * amp;
            for (int i = 1; i <= segs; i++) {
                float x = i * step;
                float y = y0 + (float) Math.sin(x / wave * TWO_PI - phase) * amp;
                lines[n++] = prevX;
                lines[n++] = prevY;
                lines[n++] = x;
                lines[n++] = y;
                prevX = x;
                prevY = y;
            }
            ripplePaint.setStrokeWidth(dp(0.9f) + depth * dp(1.1f));
            ripplePaint.setColor(0xFF2E5578);
            ripplePaint.setAlpha(Math.round(40 + 85 * depth));
            c.drawLines(lines, 0, n, ripplePaint);

            // Trough shadow just under it: the side of the wave turned away from the sky.
            c.save();
            c.translate(0f, amp * 0.9f + dp(1f));
            ripplePaint.setColor(0xFF030D18);
            ripplePaint.setAlpha(Math.round(30 + 70 * depth));
            c.drawLines(lines, 0, n, ripplePaint);
            c.restore();

            // Crest highlights: short bright dashes where the wave peaks.
            n = 0;
            for (int i = 0; i <= segs; i++) {
                float x = i * step;
                float s = (float) Math.sin(x / wave * TWO_PI - phase);
                if (s > 0.55f) {
                    float y = y0 + s * amp - amp * 0.7f - dp(1f);
                    lines[n++] = x;
                    lines[n++] = y;
                    lines[n++] = x + step * 0.8f;
                    lines[n++] = y;
                }
            }
            if (n > 0) {
                ripplePaint.setStrokeWidth(dp(1f) + depth * dp(0.9f));
                ripplePaint.setColor(0xFF9CCBF0);
                ripplePaint.setAlpha(Math.round(22 + 55 * depth));
                c.drawLines(lines, 0, n, ripplePaint);
            }
        }

        // Rings where a fish rose, carried downstream with the water.
        ripplePaint.setColor(0xFFBFDDF5);
        for (int k = 0; k < RINGS; k++) {
            float age = ringAge[k];
            float depth = ringY[k];
            float mul = 0.35f + 0.85f * depth;
            float x = ringX[k] * width - (scroll - ringScroll[k]) * mul;
            if (x < -dp(80f) || x > width + dp(80f)) {
                continue;
            }
            float y = top + span * (float) Math.pow(depth, 1.3);
            float scale = 0.45f + 0.8f * depth;
            float r = (dp(5f) + age * dp(32f)) * scale;
            float fade = (1f - age) * (1f - age);
            ripplePaint.setStrokeWidth((dp(1f) + depth * dp(1.2f)) * (1f - age * 0.5f));
            ripplePaint.setAlpha(Math.round(fade * (70 + 90 * depth)));
            c.drawOval(x - r, y - r * 0.3f, x + r, y + r * 0.3f, ripplePaint);
            if (age > 0.18f) {
                float r2 = r * 0.55f;
                ripplePaint.setAlpha(Math.round(fade * (50 + 60 * depth)));
                c.drawOval(x - r2, y - r2 * 0.3f, x + r2, y + r2 * 0.3f, ripplePaint);
            }
        }

        // Specular glints: the sky caught on the surface, twinkling as the facets turn, drifting
        // past with the boat. Rowing harder brings out more of them and stretches them.
        float effort = Math.max(0f, Math.min(1f, speedSmoothed / 4f));
        int count = 22 + Math.round(30 * effort);
        float tile = width + dp(160f);
        for (int i = 0; i < count; i++) {
            float hu = hash01(i * 7 + 3);
            float hv = hash01(i * 13 + 5);
            float hr = hash01(i * 31 + 11);
            float tw = (float) Math.sin(clock * (1.1f + 2.4f * hr) + hu * 40f);
            if (tw <= 0.25f) {
                continue;
            }
            float a = (tw - 0.25f) / 0.75f;
            float depth = 0.03f + 0.94f * (float) Math.pow(hv, 1.3);
            float mul = 0.35f + 0.85f * depth;
            float x = (hu * tile - scroll * mul) % tile;
            if (x < 0f) {
                x += tile;
            }
            x -= dp(80f);
            float y = top + span * (float) Math.pow(depth, 1.3) + (float) Math.sin(clock * 1.7f + i) * dp(1f);
            float len = (dp(3f) + depth * dp(11f)) * (1f + 0.8f * effort);
            glintPaint.setStrokeWidth(dp(1.1f) + depth * dp(1.6f));
            glintPaint.setColor(0xFFF2FAFF);
            glintPaint.setAlpha(Math.round(a * (150 + 90 * (1f - depth))));
            c.drawLine(x - len * 0.5f, y, x + len * 0.5f, y, glintPaint);
            if (a > 0.8f && depth > 0.35f) {
                // The brightest few flare at the centre.
                glintPaint.setStyle(Paint.Style.FILL);
                c.drawCircle(x, y, dp(1.2f) + depth * dp(1.4f), glintPaint);
                glintPaint.setStyle(Paint.Style.STROKE);
            }
        }
    }

    /**
     * 0 at the catch, 1 at the finish. Games set this from the pulse meter each frame so the oars
     * sweep with the actual drive instead of a free-running animation.
     */
    private float stroke;

    void setStrokePhase(float phase) {
        stroke = Math.max(0f, Math.min(1f, phase));
    }

    private void buildHull(float cx, float cy, float half, float beam) {
        hull.rewind();
        hull.moveTo(cx + half, cy);
        hull.quadTo(cx + half * 0.55f, cy - beam, cx - half * 0.2f, cy - beam);
        hull.lineTo(cx - half * 0.8f, cy - beam * 0.6f);
        hull.quadTo(cx - half, cy, cx - half * 0.8f, cy + beam * 0.6f);
        hull.lineTo(cx - half * 0.2f, cy + beam);
        hull.quadTo(cx + half * 0.55f, cy + beam, cx + half, cy);
        hull.close();
    }

    /**
     * Draws a boat pointing right.
     *
     * @param cx     centre x of the hull
     * @param cy     waterline y
     * @param length hull length in px
     * @param color  hull colour
     * @param speed  m/s, drives wake length and rower lean
     * @param ghost  draw translucent
     */
    void drawBoat(Canvas c, float cx, float cy, float length, int color, float speed,
                  boolean ghost) {
        float half = length / 2f;
        float beam = length * 0.11f;
        int alpha = ghost ? 120 : 255;
        int opaque = color | 0xFF000000;

        buildHull(cx, cy, half, beam);

        // Contact shadow on the water, cast away from the sky.
        hullPaint.setColor(0xFF020A12);
        hullPaint.setAlpha(ghost ? 38 : 85);
        c.save();
        c.translate(cx + beam * 0.3f, cy + beam * 0.7f);
        c.scale(half, beam);
        c.drawPath(unitHull, hullPaint);
        c.restore();

        // Wake: a faint fan of disturbed water between two trailing arms, longer and brighter with
        // speed, with foam streaks flowing back through it.
        float wakeLen = Math.min(length * 2.2f, dp(40f) + speed * dp(34f));
        if (wakeLen > dp(6f)) {
            float strength = Math.min(1f, speed / 4f);
            float sternX = cx - half * 0.7f;
            c.save();
            c.translate(sternX, cy);
            c.scale(wakeLen, beam);
            shadePaint.setShader(fanShade);
            shadePaint.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha
            shadePaint.setAlpha((int) (strength * 255) * alpha / 255);
            c.drawPath(unitFan, shadePaint);
            shadePaint.setShader(hullShade);
            c.restore();

            int segs = 7;
            for (int i = 0; i < segs; i++) {
                float f0 = i / (float) segs;
                float f1 = (i + 1) / (float) segs;
                int a = (int) (strength * 170 * (1f - f0)) * alpha / 255;
                wakePaint.setColor(0xFFBFE3FF);
                wakePaint.setAlpha(a);
                wakePaint.setStrokeWidth(dp(3.5f) * (1f - f0 * 0.7f));
                float x0 = sternX - wakeLen * f0;
                float x1 = sternX - wakeLen * f1;
                float spread0 = beam * (0.4f + 1.6f * f0);
                float spread1 = beam * (0.4f + 1.6f * f1);
                c.drawLine(x0, cy + spread0, x1, cy + spread1, wakePaint);
                c.drawLine(x0, cy - spread0, x1, cy - spread1, wakePaint);
            }
            // Foam streaks flowing aft through the fan - they move faster the faster you row.
            for (int i = 0; i < 4; i++) {
                float f = (i / 4f + clock * (0.25f + speed * 0.12f)) % 1f;
                float spread = beam * (0.3f + 1.3f * f) * (((i & 1) == 0) ? 0.5f : -0.5f);
                wakePaint.setColor(0xFFDCEBF7);
                wakePaint.setAlpha((int) (strength * 110 * (1f - f)) * alpha / 255);
                wakePaint.setStrokeWidth(dp(2f) * (1f - f * 0.5f));
                float x = sternX - wakeLen * f;
                c.drawLine(x, cy + spread, x - dp(8f) - speed * dp(3f), cy + spread, wakePaint);
            }
            // Foam patch right behind the stern.
            wakePaint.setColor(0xFFDCEBF7);
            wakePaint.setAlpha((int) (strength * 90) * alpha / 255);
            wakePaint.setStrokeWidth(beam * 1.2f);
            c.drawLine(cx - half * 0.75f, cy, cx - half * 0.75f - dp(10f) - speed * dp(4f), cy, wakePaint);
        }

        // Hull: the crew colour, then a cylindrical shade over it so it reads round.
        c.save();
        c.translate(cx, cy);
        c.scale(half, beam);
        hullPaint.setColor(opaque);
        hullPaint.setAlpha(alpha);
        c.drawPath(unitHull, hullPaint);
        shadePaint.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha
        shadePaint.setAlpha(alpha);
        c.drawPath(unitHull, shadePaint);
        // Bow deck, lighter, with its V splash box drawn below.
        hullPaint.setColor(mix(opaque, 0xFFFFFFFF, 0.42f));
        hullPaint.setAlpha(alpha * 200 / 255);
        c.drawPath(unitDeck, hullPaint);
        c.restore();
        wakePaint.setColor(mix(opaque, 0xFF000000, 0.55f));
        wakePaint.setAlpha(alpha);
        wakePaint.setStrokeWidth(Math.max(dp(1f), beam * 0.12f));
        c.drawLine(cx + half * 0.34f, cy - beam * 0.6f, cx + half * 0.42f, cy, wakePaint);
        c.drawLine(cx + half * 0.42f, cy, cx + half * 0.34f, cy + beam * 0.6f, wakePaint);
        c.drawLine(cx + half * 0.42f, cy, cx + half * 0.9f, cy, wakePaint);

        // Lit gunwale along the sky side, and a dark outline to separate it from the water.
        gunwale.rewind();
        gunwale.moveTo(cx + half * 0.96f, cy - beam * 0.06f);
        gunwale.quadTo(cx + half * 0.55f, cy - beam * 0.9f, cx - half * 0.2f, cy - beam * 0.9f);
        gunwale.lineTo(cx - half * 0.78f, cy - beam * 0.52f);
        hullEdge.setColor(mix(opaque, 0xFFFFFFFF, 0.7f));
        hullEdge.setAlpha(alpha * 230 / 255);
        hullEdge.setStrokeWidth(Math.max(dp(1.2f), beam * 0.11f));
        c.drawPath(gunwale, hullEdge);
        hullEdge.setColor(mix(opaque, 0xFF05080D, 0.7f));
        hullEdge.setAlpha(alpha);
        hullEdge.setStrokeWidth(dp(1.3f));
        c.drawPath(hull, hullEdge);

        // Oars, mirrored about the hull. The blade is the far end of the same shaft so it cannot
        // detach. 3.19.6 normalised the direction vector *after* folding the side into it, which
        // cancelled the mirroring and put both oars on the same side of the boat.
        //
        // 3.25.0: the sweep ran the wrong way round. The angle went from -55 to +40 degrees, which
        // with +x toward the bow put the blade on the STERN side at the catch and swung it toward
        // the bow through the drive - a blade fixed in the water moving forward past the boat,
        // which is backing it down. The comment always said bow-side at the catch; the sign now
        // agrees with it. Same arc, same timing, mirrored.
        float riggerX = cx - half * 0.05f;
        double angle = Math.toRadians(55 - 95 * stroke);   // bow-side at the catch, stern-side at the finish
        boolean water = stroke > 0.03f && stroke < 0.97f;
        float shaft = length * 0.46f;
        // Direction in boat space: +x toward the bow, +y outboard. Mirrored per side below.
        // The 0.55 is foreshortening: the water is seen at a shallow angle, so travel across the
        // boat covers less screen than travel along it. It must be applied AFTER normalising.
        // Dividing by hypot() when uy has already been compressed simply restores it to full
        // length, so at the perpendicular part of the sweep the 0.55 was cancelled outright and
        // both oars drew as one vertical mast through the hull - measured at 73 px past the hull
        // edge, a span 5.3x the hull depth. Applied in the right order that becomes 41 px / 3.4x,
        // while the blade still swings the full arc (-59 px at the catch to +46 px at the finish).
        // This is the same mistake as 3.19.6's cancelled mirroring, in the surviving half.
        float ux = (float) Math.sin(angle);
        float uy = (float) Math.cos(angle);
        float ulen = (float) Math.hypot(ux, uy);
        ux /= ulen;
        uy /= ulen;
        uy *= 0.55f;
        float inboard = shaft * 0.32f;

        // The sculler, in boat space. Faces the stern: the seat slides toward the bow through the
        // drive while the back swings from leaning toward the stern to leaning past upright.
        float s = stroke;
        float hipX = riggerX + beam * (0.2f + 0.7f * s);
        float shoulderX = hipX + beam * (-0.8f + 1.2f * s);
        float feetX = riggerX - beam * 1.45f;
        float kneeX = (hipX + feetX) * 0.5f + (1f - s) * beam * 0.25f;
        float kneeSplay = beam * (0.2f + 0.26f * (1f - s));
        // Kit a shade lighter than the hull, so the body stands out of the dark cockpit.
        int kit = mix(opaque, 0xFFFFFFFF, 0.3f);

        // Cockpit opening under the sculler.
        hullPaint.setColor(mix(opaque, 0xFF05080D, 0.62f));
        hullPaint.setAlpha(alpha);
        rect.set(riggerX - beam * 1.75f, cy - beam * 0.5f, riggerX + beam * 1.9f, cy + beam * 0.5f);
        c.drawRoundRect(rect, beam * 0.5f, beam * 0.5f, hullPaint);

        // Legs: hips to knees to the footstretcher.
        rowerPaint.setStyle(Paint.Style.STROKE);
        rowerPaint.setColor(0xFF2A3A52);
        rowerPaint.setAlpha(alpha);
        rowerPaint.setStrokeWidth(beam * 0.26f);
        for (int side = -1; side <= 1; side += 2) {
            float hy = cy + side * beam * 0.16f;
            float ky = cy + side * kneeSplay;
            float fy = cy + side * beam * 0.2f;
            c.drawLine(hipX, hy, kneeX, ky, rowerPaint);
            c.drawLine(kneeX, ky, feetX, fy, rowerPaint);
        }

        float handleLX = 0f, handleLY = 0f, handleRX = 0f, handleRY = 0f;
        for (int side = -1; side <= 1; side += 2) {
            float pivotY = cy + side * beam * 1.1f;
            float tipX = riggerX + ux * shaft;
            float tipY = pivotY + side * uy * shaft;
            float neckX = riggerX + ux * shaft * 0.72f;
            float neckY = pivotY + side * uy * shaft * 0.72f;
            float handleX = riggerX - ux * inboard;
            float handleY = pivotY - side * uy * inboard;
            if (side < 0) {
                handleLX = handleX;
                handleLY = handleY;
            } else {
                handleRX = handleX;
                handleRY = handleY;
            }
            // Rigger: a wishbone from the hull out to the pin.
            oarPaint.setColor(0xFFB8C2CC);
            oarPaint.setAlpha(alpha);
            oarPaint.setStrokeWidth(Math.max(dp(1f), beam * 0.13f));
            c.drawLine(riggerX - beam * 0.55f, cy + side * beam * 0.5f, riggerX, pivotY, oarPaint);
            c.drawLine(riggerX + beam * 0.55f, cy + side * beam * 0.5f, riggerX, pivotY, oarPaint);
            // Shaft, handle through the pin out to the blade neck.
            oarPaint.setColor(0xFFE8E3D3);
            oarPaint.setAlpha(alpha);
            oarPaint.setStrokeWidth(Math.max(dp(1.5f), beam * 0.2f));
            c.drawLine(handleX, handleY, neckX, neckY, oarPaint);
            // The gate on the pin.
            oarPaint.setColor(0xFF2A333F);
            oarPaint.setAlpha(alpha);
            c.drawPoint(riggerX, pivotY, oarPaint);

            // Blade: a spoon in the crew colour, squared in the water, feathered (edge-on) out of it.
            float bladeLen = shaft * 0.3f;
            float bw = beam * (water ? 0.95f : 0.34f);
            float deg = (float) Math.toDegrees(Math.atan2(tipY - neckY, tipX - neckX));
            float rim = Math.max(dp(1f), beam * 0.08f);
            bladePaint.setStyle(Paint.Style.FILL);
            c.save();
            c.translate(neckX, neckY);
            c.rotate(deg);
            // A dark rim: the same spoon a little larger, underneath.
            c.save();
            c.translate(-rim * 0.5f, 0f);
            c.scale(bladeLen + rim * 1.5f, bw + rim * 2f);
            bladePaint.setColor(mix(opaque, 0xFF05080D, 0.6f));
            bladePaint.setAlpha(alpha);
            c.drawPath(unitBlade, bladePaint);
            c.restore();
            c.scale(bladeLen, bw);
            bladePaint.setColor(opaque);
            bladePaint.setAlpha(alpha);
            c.drawPath(unitBlade, bladePaint);
            // A white club band near the tip, and a soft sheen on the sky side.
            bladePaint.setColor(0xFFF4F7FB);
            bladePaint.setAlpha(alpha * 220 / 255);
            c.drawRect(0.68f, -0.45f, 0.8f, 0.45f, bladePaint);
            bladePaint.setColor(0xFFFFFFFF);
            bladePaint.setAlpha(alpha * 70 / 255);
            c.drawRect(0.1f, -0.36f, 0.66f, -0.14f, bladePaint);
            c.restore();

            if (water) {
                // The puddle: white water at the blade and a ring spreading round it.
                float bite = 1f - Math.abs(0.5f - stroke) * 1.5f;
                wakePaint.setColor(0xFFDCEBF7);
                wakePaint.setAlpha((int) (120 * bite) * alpha / 255);
                wakePaint.setStrokeWidth(beam * 0.45f);
                c.drawLine(tipX - beam * 0.45f, tipY, tipX + beam * 0.45f, tipY, wakePaint);
                float pr = beam * (0.7f + 0.9f * stroke);
                wakePaint.setStrokeWidth(Math.max(dp(1f), beam * 0.1f));
                wakePaint.setAlpha((int) (90 * bite) * alpha / 255);
                c.drawOval(tipX - pr, tipY - pr * 0.4f, tipX + pr, tipY + pr * 0.4f, wakePaint);
            }
        }

        // Arms to the handles, then the torso and the cap on top.
        rowerPaint.setColor(0xFFE2B27E);
        rowerPaint.setAlpha(alpha);
        rowerPaint.setStrokeWidth(beam * 0.2f);
        c.drawLine(shoulderX, cy - beam * 0.36f, handleLX, handleLY, rowerPaint);
        c.drawLine(shoulderX, cy + beam * 0.36f, handleRX, handleRY, rowerPaint);
        rowerPaint.setColor(kit);
        rowerPaint.setAlpha(alpha);
        rowerPaint.setStrokeWidth(beam * 0.8f);
        c.drawLine(hipX, cy, shoulderX, cy, rowerPaint);
        rowerPaint.setColor(mix(opaque, 0xFFFFFFFF, 0.7f));
        rowerPaint.setAlpha(alpha * 170 / 255);
        rowerPaint.setStrokeWidth(beam * 0.16f);
        c.drawLine(hipX, cy - beam * 0.24f, shoulderX, cy - beam * 0.24f, rowerPaint);

        float body = (float) Math.sin(Math.PI * stroke);
        float headX = shoulderX + beam * (-0.2f + 0.4f * s);
        float headY = cy - beam * (0.05f + 0.1f * body);
        rowerPaint.setStyle(Paint.Style.FILL);
        rowerPaint.setColor(0xFFE6EDF7);
        rowerPaint.setAlpha(alpha);
        c.drawCircle(headX, headY, beam * 0.46f, rowerPaint);
        rowerPaint.setColor(0xFFFFFFFF);
        rowerPaint.setAlpha(alpha * 200 / 255);
        c.drawCircle(headX - beam * 0.12f, headY - beam * 0.14f, beam * 0.17f, rowerPaint);
    }
}
