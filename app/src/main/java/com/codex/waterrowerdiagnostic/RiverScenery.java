package com.codex.waterrowerdiagnostic;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

/**
 * Race-course dressing shared by the river games: a far bank with trees and a crowd, lane buoys,
 * distance boards and a finish line. Everything scrolls with the player's metres, so passing
 * scenery is itself a speed readout. Allocates nothing per frame.
 *
 * <p>3.25.0 graphics pass: the bank recedes. Hazy hills at the back barely move, a softer treeline
 * in front of them, then the near trees with a trunk and a lit canopy, striped marquees, a sunlit
 * grass bank and a crowd of people rather than coloured bars - legs, shirts, heads, the odd boater
 * hat and umbrella - with a back row fading into the haze. The bank's reflection lies in the water
 * just below the waterline. Each far layer is one path fill, so the depth costs only a few draws.
 */
final class RiverScenery {

    private static final int[] SHIRTS = {0xFFF0655D, 0xFFF0B132, 0xFF6F8CFF, 0xFFFFFFFF, 0xFF35D0BA, 0xFFB48CFF};
    private static final int[] TROUSERS = {0xFF243042, 0xFF34507A, 0xFF3B3B3B, 0xFFCFC6B0};
    /** The colour the far layers fade toward: the haze between you and the bank. */
    private static final int HAZE = 0xFF7F97AE;

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path ridge = new Path();
    /** Grass bank and the reflection band, rebuilt only when the bank moves on screen. */
    private LinearGradient grassShader;
    private LinearGradient reflectShader;
    private LinearGradient ridgeShader;
    private float shaderBottom = Float.NaN;
    private float shaderTop = Float.NaN;
    /** Reused for the chop rows; grown only when the view gets wider. */
    private float[] lines = new float[0];

    RiverScenery(float density) {
        this.density = density;
    }

    private float dp(float v) {
        return v * density;
    }

    /** Linear blend of two colours; {@code t} 0 gives {@code a}. Keeps a's alpha. */
    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (a & 0xFF000000) | (r << 16) | (g << 8) | bl;
    }

    private static int withAlpha(int argb, int alpha) {
        return (argb & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24);
    }

    /**
     * Life on the water itself, shared by every river game. The hulls already carry their own wake from RiverRenderer, so this
     * adds none: what the middle of the course was missing is surface texture and something moving
     * that is not a competitor. Chop at eight depths, drifting foam, and a pair of ducks working
     * down the course - all scrolling with your metres.
     */
    void drawWaterLife(Canvas c, float w, float waterTop, float waterBottom, double you,
                               float ppm, double time) {
        float span = waterBottom - waterTop;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Chop: short strokes laid out in perspective - rows close up and shrink toward the far
        // bank, open out and thicken toward you, and nearer rows scroll faster. One drawLines per row.
        for (int row = 0; row < 8; row++) {
            float f = row / 7f;
            float depth = 0.3f + 0.7f * f;
            float y = waterTop + span * (0.04f + 0.92f * (float) Math.pow(f, 1.3));
            float step = dp(90f) + dp(70f) * depth;
            float off = (float) ((you * ppm * depth * 0.6) % step);
            int count = (int) ((w + step) / step) + 2;
            if (lines.length < count * 8) {
                lines = new float[count * 8 + 64];   // only when the view grows, never per frame
            }
            int n = 0;
            for (float x = -off; x < w + step; x += step) {
                if (n + 8 > lines.length) {
                    break;
                }
                float bob = (float) Math.sin(time * 1.5 + x * 0.015 + row) * dp(1f + 1.5f * depth);
                lines[n++] = x;
                lines[n++] = y + bob;
                lines[n++] = x + dp(30f) * depth;
                lines[n++] = y + bob;
                float x2 = x + step * 0.42f;
                float y2 = y + bob + dp(6f) * depth;
                lines[n++] = x2;
                lines[n++] = y2;
                lines[n++] = x2 + dp(20f) * depth;
                lines[n++] = y2;
            }
            paint.setStrokeWidth(dp(1.2f) + depth * dp(0.8f));
            paint.setColor((Math.round(20 + 30 * depth) << 24) | 0x00BFE3FF);
            c.drawLines(lines, 0, n, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        // Foam and drift carried down the course.
        float driftStep = dp(260f);
        float driftOff = (float) ((you * ppm * 0.85) % driftStep);
        for (int k = -1; k < (int) (w / driftStep) + 2; k++) {
            float x = k * driftStep - driftOff;
            float y = waterTop + span * (0.2f + ((k * 5081) & 3) * 0.2f);
            paint.setColor(0x26DCEBF7);
            c.drawOval(x, y, x + dp(34f), y + dp(7f), paint);
            c.drawOval(x + dp(40f), y + dp(9f), x + dp(58f), y + dp(14f), paint);
        }
        // Two ducks in the clear strip above the first lane, bobbing as they paddle, each pushing
        // a little V of ripples behind it. Mallards: a green-headed drake leading a brown hen.
        float duckStep = dp(520f);
        float duckOff = (float) ((you * ppm * 0.95) % duckStep);
        for (int k = -1; k < (int) (w / duckStep) + 2; k++) {
            float dx = k * duckStep - duckOff + dp(60f);
            for (int n = 0; n < 2; n++) {
                float x = dx + n * dp(26f);
                float y = waterTop + span * 0.035f + (float) Math.sin(time * 2.1 + k + n) * dp(1.8f);
                boolean drake = n == 0;
                // The V of ripples, trailing behind (the ducks swim right, against the stream).
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.2f));
                paint.setColor(0x40DCEBF7);
                c.drawLine(x + dp(2f), y + dp(7f), x - dp(14f), y + dp(3f), paint);
                c.drawLine(x + dp(2f), y + dp(8f), x - dp(14f), y + dp(12f), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0x33DCEBF7);
                c.drawOval(x - dp(5f), y + dp(6f), x + dp(15f), y + dp(10f), paint);   // its ripple
                paint.setColor(drake ? 0xFF8C8478 : 0xFF7A5A3C);
                c.drawOval(x, y, x + dp(15f), y + dp(8f), paint);          // body
                paint.setColor(drake ? 0xFF6B3B2A : 0xFF9A7650);
                c.drawOval(x + dp(8f), y + dp(1f), x + dp(15f), y + dp(7f), paint);   // breast
                paint.setColor(drake ? 0xFF2F7A45 : 0xFF7A5A3C);
                c.drawOval(x + dp(10f), y - dp(6f), x + dp(17f), y + dp(2f), paint);  // head
                paint.setColor(0xFFF0B132);
                c.drawRect(x + dp(16f), y - dp(3f), x + dp(19f), y - dp(1.5f), paint); // bill
                paint.setColor(0x66FFFFFF);
                c.drawCircle(x + dp(5f), y + dp(2f), dp(1.6f), paint);                 // sheen on the back
            }
        }
    }

    /** Half-brightness, for shading: depth without a second palette. */
    private static int dim(int argb) {
        return 0xFF000000 | ((argb >>> 1) & 0x007F7F7F);
    }

    private static int hash(int k) {
        return ((k * 73) ^ (k >> 2) ^ (k * 1367)) & 0x7FFFFFFF;   // never negative, so safe as an index
    }

    private void ensureShaders(float top, float bottom) {
        if (grassShader != null && bottom == shaderBottom && top == shaderTop) {
            return;
        }
        shaderBottom = bottom;
        shaderTop = top;
        // Misty hills: darker along the ridge, paler toward their feet where the haze lies.
        ridgeShader = new LinearGradient(0, top - dp(18f), 0, bottom - dp(14f),
                0xFF4A657E, 0xFF6C869B, Shader.TileMode.CLAMP);
        grassShader = new LinearGradient(0, bottom - dp(12f), 0, bottom,
                new int[]{0xFF6AAA5E, 0xFF44813F, 0xFF2A5530}, new float[]{0f, 0.35f, 1f},
                Shader.TileMode.CLAMP);
        reflectShader = new LinearGradient(0, bottom, 0, bottom + dp(24f),
                0x80081A14, 0x00081A14, Shader.TileMode.CLAMP);
    }

    /**
     * The far bank between {@code top} and {@code bottom}: trees at a slow parallax, then a crowd
     * that jumps when {@code cheer} (0..1) is up, with flags on poles.
     *
     * <p>Tree tops and the far hills rise up to dp(20) above {@code top} so they finish in a round
     * canopy instead of being sliced flat by the band's edge, and the bank's reflection is laid
     * on the water for dp(24) below {@code bottom}. Every caller draws water (or ground) there
     * first and its own course on top afterwards.
     */
    void drawBank(Canvas c, float w, float top, float bottom, double metres, float ppm, double time, float cheer) {
        ensureShaders(top, bottom);
        c.save();
        c.clipRect(0, top - dp(20f), w, bottom);
        paint.setStyle(Paint.Style.FILL);

        // 1. Far hills, deep in the haze. Opaque, and never lower than the top of the band, so
        //    they are the band's backdrop: callers paint their sky only down to {@code top}, and
        //    the gaps between the old trees showed the bare background there as a black stripe.
        float ridgeStep = dp(20f);
        double ridgeScroll = metres * ppm * 0.06;
        ridge.rewind();
        ridge.moveTo(-ridgeStep, bottom);
        for (float x = -ridgeStep; x <= w + ridgeStep; x += ridgeStep) {
            double wx = x + ridgeScroll;
            float hgt = dp(8f) + dp(5f) * (float) Math.sin(wx * 0.0041) + dp(3f) * (float) Math.sin(wx * 0.0113 + 1.3);
            ridge.lineTo(x, top + dp(1f) - hgt);
        }
        ridge.lineTo(w + ridgeStep, bottom);
        ridge.close();
        paint.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha
        paint.setShader(ridgeShader);
        c.drawPath(ridge, paint);
        paint.setShader(null);

        // 2. A distant treeline, softer and bluer than the near one: scalloped crowns in one path.
        float lineGap = dp(26f);
        double lineScroll = metres * ppm * 0.18;
        float lineOff = (float) (lineScroll % lineGap);
        float lineBase = bottom - dp(16f);
        ridge.rewind();
        ridge.moveTo(-lineOff - lineGap, bottom);
        ridge.lineTo(-lineOff - lineGap, lineBase);
        for (float x = -lineOff - lineGap; x < w + lineGap; x += lineGap) {
            int k = (int) Math.floor((x + lineScroll) / lineGap + 0.5);
            float crown = dp(10f) + (hash(k * 5 + 3) % 5) * dp(3f);
            ridge.quadTo(x + lineGap * 0.5f, lineBase - crown * 2f, x + lineGap, lineBase);
        }
        ridge.lineTo(w + lineGap * 2f, bottom);
        ridge.close();
        paint.setColor(withAlpha(mix(0xFF2E5A4C, HAZE, 0.32f), 0xC8));
        c.drawPath(ridge, paint);

        // 3. Near trees: a trunk, a dark canopy, a lit crown up toward the sky and a highlight.
        //    Every fifth is a poplar, tall and narrow, so the line is not all lollipops.
        float treeGap = dp(58f);
        double treeScroll = metres * ppm * 0.35;
        float off = (float) (treeScroll % treeGap);
        for (float x = -off - treeGap; x < w + treeGap; x += treeGap) {
            int k = (int) Math.floor((x + treeScroll) / treeGap + 0.5);
            float r = dp(15f) + (Math.abs(k * 7) % 3) * dp(5f);
            boolean odd = (k & 1) == 0;
            int leaf = odd ? 0xFF24523A : 0xFF2F6B45;
            if (Math.abs(k) % 5 == 2) {
                float px = x + dp(6f);
                float tall = r * 2.1f;
                float base = bottom - dp(11f);
                path.rewind();
                path.moveTo(px - r * 0.42f, base);
                path.quadTo(px - r * 0.5f, base - tall * 0.6f, px, base - tall);
                path.quadTo(px + r * 0.5f, base - tall * 0.6f, px + r * 0.42f, base);
                path.close();
                paint.setColor(mix(leaf, 0xFF0A1A12, 0.35f));
                c.drawPath(path, paint);
                path.rewind();
                path.moveTo(px - r * 0.36f, base - dp(2f));
                path.quadTo(px - r * 0.42f, base - tall * 0.6f, px, base - tall + dp(2f));
                path.lineTo(px - r * 0.05f, base - dp(2f));
                path.close();
                paint.setColor(mix(leaf, 0xFF8BC37A, 0.28f));
                c.drawPath(path, paint);
                continue;
            }
            float cy = bottom - dp(14f) - r * 0.6f;
            paint.setColor(0xFF4A3524);
            c.drawRect(x - dp(2f), cy, x + dp(2f), bottom - dp(9f), paint);
            paint.setColor(mix(leaf, 0xFF0A1A12, 0.4f));
            c.drawCircle(x + r * 0.1f, cy + r * 0.08f, r, paint);
            c.drawCircle(x + r * 0.72f, bottom - dp(10f) - r * 0.3f, r * 0.7f, paint);
            paint.setColor(leaf);
            c.drawCircle(x - r * 0.1f, cy - r * 0.12f, r * 0.82f, paint);
            paint.setColor(mix(leaf, 0xFF9BD17E, 0.3f));
            c.drawCircle(x - r * 0.3f, cy - r * 0.42f, r * 0.4f, paint);
        }

        // 4. Marquees: striped regatta tents standing behind the crowd.
        float tentGap = dp(430f);
        double tentScroll = metres * ppm * 0.55;
        float toff = (float) (tentScroll % tentGap);
        for (float x = -toff - tentGap; x < w + tentGap; x += tentGap) {
            int k = (int) Math.floor((x + tentScroll) / tentGap + 0.5);
            if (hash(k * 3 + 1) % 3 == 0) {
                continue;
            }
            float tx = x + (hash(k) % 7 - 3) * dp(12f);
            float half = dp(18f) + (hash(k * 11) % 3) * dp(3f);
            float eave = bottom - dp(30f);
            float peak = bottom - dp(43f);
            int stripe = SHIRTS[hash(k * 7 + 2) % SHIRTS.length];
            if (stripe == 0xFFFFFFFF) {
                stripe = 0xFF35D0BA;
            }
            paint.setColor(0xFFDDE3E8);
            c.drawRect(tx - half + dp(2f), eave, tx + half - dp(2f), bottom - dp(11f), paint);
            paint.setColor(0xFFB9C2CB);
            c.drawRect(tx, eave, tx + half - dp(2f), bottom - dp(11f), paint);   // shaded side
            paint.setColor(0xFF2A3038);
            c.drawRect(tx - dp(5f), eave + dp(5f), tx + dp(5f), bottom - dp(11f), paint); // doorway
            path.rewind();
            path.moveTo(tx - half - dp(3f), eave);
            path.lineTo(tx, peak);
            path.lineTo(tx, eave);
            path.close();
            paint.setColor(stripe);
            c.drawPath(path, paint);
            path.rewind();
            path.moveTo(tx, peak);
            path.lineTo(tx + half + dp(3f), eave);
            path.lineTo(tx, eave);
            path.close();
            paint.setColor(0xFFF4F6F8);
            c.drawPath(path, paint);
            paint.setColor(stripe);
            c.drawRect(tx - dp(0.8f), peak - dp(7f), tx + dp(0.8f), peak, paint);
            float flap = (float) Math.sin(time * 5 + k) * dp(1.5f);
            path.rewind();
            path.moveTo(tx + dp(0.8f), peak - dp(7f));
            path.lineTo(tx + dp(9f), peak - dp(5f) + flap);
            path.lineTo(tx + dp(0.8f), peak - dp(3f));
            path.close();
            c.drawPath(path, paint);
        }

        // 5. The grass bank, lit along its top edge, with a dark lip at the waterline.
        paint.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha
        paint.setShader(grassShader);
        c.drawRect(0, bottom - dp(12f), w, bottom, paint);
        paint.setShader(null);
        paint.setColor(0xFF1B3A22);
        c.drawRect(0, bottom - dp(1.5f), w, bottom, paint);

        // 6. A crowd, not a picket fence. At dp(15) with dp(8)-wide bodies the old spacing left a
        // dp(7) gap the whole width of the screen, which reads on the tablet as a barcode stripe.
        // Three things make it a crowd instead: irregular empty stretches, per-person jitter and
        // height, and a back row fading into the haze between the front one's shoulders.
        // 3.25.0: people rather than bars - trousers, a shirt with shoulders, a head, the odd
        // boater - and a shadow where they stand.
        float fanGap = dp(22f);
        double fanScroll = metres * ppm * 0.8;
        float foff = (float) (fanScroll % fanGap);
        float ground = bottom - dp(4f);
        paint.setColor(0x38000000);
        c.drawRect(0, ground - dp(1.5f), w, ground + dp(1.5f), paint);
        for (float x = -foff - fanGap * 2f; x < w + fanGap; x += fanGap) {
            int k = (int) Math.floor((x + fanScroll) / fanGap + 0.5);
            int h = hash(k);
            if (h % 7 < 2) {
                continue;       // an irregular gap in the line - people do not stand evenly
            }
            float jump = cheer * (float) Math.abs(Math.sin(time * 7 + k * 1.7)) * dp(5f);
            float fx = x + ((h % 5) - 2) * dp(2.2f);
            float tall = dp(12f) + (h % 4) * dp(1.6f);
            if (h % 3 != 1) {
                // Back row: smaller, hazier, offset - depth without another pass of geometry.
                float bx = fx + dp(9f);
                float bjump = cheer * (float) Math.abs(Math.sin(time * 7 + k * 2.3)) * dp(4f);
                float btop = ground - dp(4f) - tall * 0.8f - bjump;
                paint.setColor(mix(SHIRTS[Math.abs(k * 3 + 1) % SHIRTS.length], HAZE, 0.5f));
                c.drawRoundRect(bx - dp(3f), btop, bx + dp(3f), ground - dp(4f) - bjump, dp(2f), dp(2f), paint);
                paint.setColor(mix((k & 3) == 0 ? 0xFF8D5524 : 0xFFF1C27D, HAZE, 0.4f));
                c.drawCircle(bx, btop - dp(2.6f), dp(2.6f), paint);
            }
            float headY = ground - tall - dp(3.2f) - jump;
            // Trousers, then the shirt over the top half with rounded shoulders.
            paint.setColor(TROUSERS[(h >> 3) % TROUSERS.length]);
            c.drawRect(fx - dp(3.2f), ground - tall * 0.45f - jump, fx + dp(3.2f), ground - jump, paint);
            int shirt = SHIRTS[Math.abs(k) % SHIRTS.length];
            paint.setColor(shirt);
            c.drawRoundRect(fx - dp(3.8f), ground - tall - jump, fx + dp(3.8f), ground - tall * 0.38f - jump,
                    dp(2.4f), dp(2.4f), paint);
            paint.setColor((k & 3) == 0 ? 0xFF8D5524 : 0xFFF1C27D);
            c.drawCircle(fx, headY, dp(3.2f), paint);
            if (h % 5 == 1) {
                // A straw boater.
                paint.setColor(0xFFE8D08A);
                c.drawOval(fx - dp(5f), headY - dp(3.2f), fx + dp(5f), headY - dp(1.2f), paint);
                c.drawRect(fx - dp(2.8f), headY - dp(5.4f), fx + dp(2.8f), headY - dp(2.2f), paint);
            }
            if (cheer > 0.5f && (k & 1) == 0) {
                // Arms up.
                paint.setColor(0xFFF1C27D);
                c.drawRect(fx - dp(5.6f), ground - tall - dp(8f) - jump,
                        fx - dp(4.2f), ground - tall * 0.5f - jump, paint);
                c.drawRect(fx + dp(4.2f), ground - tall - dp(8f) - jump,
                        fx + dp(5.6f), ground - tall * 0.5f - jump, paint);
            }
            if (Math.abs(k) % 9 == 4) {
                paint.setColor(0xFF9AA5B1);
                c.drawRect(fx + dp(3.2f), ground - dp(40f) - jump, fx + dp(4.6f), ground - dp(14f) - jump, paint);
                float wave = (float) Math.sin(time * 6 + k) * dp(3f);
                paint.setColor(SHIRTS[(Math.abs(k) + 2) % SHIRTS.length]);
                path.rewind();
                path.moveTo(fx + dp(4.6f), ground - dp(40f) - jump);
                path.lineTo(fx + dp(21f), ground - dp(35f) - jump + wave);
                path.lineTo(fx + dp(4.6f), ground - dp(29f) - jump);
                path.close();
                c.drawPath(path, paint);
            } else if (Math.abs(k) % 13 == 6) {
                // A parasol over someone who came prepared.
                paint.setColor(0xFF6B4A2B);
                c.drawRect(fx - dp(0.7f), ground - dp(32f), fx + dp(0.7f), ground - tall * 0.6f, paint);
                int canopy = SHIRTS[(Math.abs(k) + 4) % SHIRTS.length];
                paint.setColor(canopy);
                c.drawArc(fx - dp(11f), ground - dp(38f), fx + dp(11f), ground - dp(26f), 180f, 180f, true, paint);
                paint.setColor(mix(canopy, 0xFFFFFFFF, 0.45f));
                c.drawArc(fx - dp(4f), ground - dp(38f), fx + dp(4f), ground - dp(26f), 180f, 180f, true, paint);
            }
        }
        c.restore();

        // 7. The bank's reflection in the water: a dark band fading out, the near trees and a few
        // shirts broken into wobbling smears by the ripples.
        c.save();
        c.clipRect(0, bottom, w, bottom + dp(24f));
        paint.setColor(0xFFFFFFFF);   // a shader draws at the paint's alpha
        paint.setShader(reflectShader);
        c.drawRect(0, bottom, w, bottom + dp(24f), paint);
        paint.setShader(null);
        for (float x = -off - treeGap; x < w + treeGap; x += treeGap) {
            int k = (int) Math.floor((x + treeScroll) / treeGap + 0.5);
            float r = dp(15f) + (Math.abs(k * 7) % 3) * dp(5f);
            float wob = (float) Math.sin(time * 1.8 + k * 1.3) * dp(2f);
            paint.setColor(0x30245A3C);
            c.drawOval(x - r * 0.9f + wob, bottom + dp(1f), x + r * 0.9f + wob, bottom + r * 0.75f, paint);
        }
        for (float x = -foff - fanGap * 2f; x < w + fanGap; x += fanGap) {
            int k = (int) Math.floor((x + fanScroll) / fanGap + 0.5);
            int h = hash(k);
            if (h % 7 < 2 || (k % 3) != 0) {
                continue;
            }
            float fx = x + ((h % 5) - 2) * dp(2.2f) + (float) Math.sin(time * 3 + k) * dp(1.2f);
            float len = dp(4f) + (h % 4) * dp(2f);
            paint.setColor(withAlpha(SHIRTS[Math.abs(k) % SHIRTS.length], 0x26));
            c.drawRect(fx - dp(1.4f), bottom + dp(2f), fx + dp(1.4f), bottom + dp(2f) + len, paint);
        }
        c.restore();
    }

    /** A line of red and white buoys every {@code spacingM} metres, bobbing. */
    void drawBuoys(Canvas c, float w, float y, double metres, float ppm, double time, float spacingM) {
        float gapPx = spacingM * ppm;
        if (gapPx < dp(12f)) {
            return;
        }
        double scroll = metres * ppm;
        float off = (float) (scroll % gapPx);
        paint.setStyle(Paint.Style.FILL);
        for (float x = -off - gapPx; x < w + gapPx; x += gapPx) {
            int k = (int) Math.floor((x + scroll) / gapPx + 0.5);
            float bob = (float) Math.sin(time * 3 + k) * dp(1.5f);
            boolean red = (k & 1) == 0;
            // Shadow and the little ring of water it sits in.
            paint.setColor(0x40000000);
            c.drawOval(x - dp(6.5f), y + dp(1.5f), x + dp(6.5f), y + dp(5.5f), paint);
            // A ball: shadowed body, a lit cap toward the sky, and a glint.
            paint.setColor(red ? 0xFFA8362F : 0xFFA9B4BF);
            c.drawCircle(x, y + bob, dp(4.2f), paint);
            paint.setColor(red ? 0xFFF0655D : 0xFFF4F4F4);
            c.drawCircle(x - dp(0.7f), y + bob - dp(0.8f), dp(3.3f), paint);
            paint.setColor(0xB0FFFFFF);
            c.drawCircle(x - dp(1.5f), y + bob - dp(1.6f), dp(1.3f), paint);
        }
    }

    /** A floating board reading {@code text} at screen x, standing on the far bank line. */
    void drawBoard(Canvas c, float x, float waterTop, String text, Paint textPaint) {
        paint.setStyle(Paint.Style.FILL);
        float half = Math.max(dp(26f), textPaint.measureText(text) / 2f + dp(8f));
        // Post with a lit edge, and its shadow on the water.
        paint.setColor(0x40000000);
        c.drawOval(x - dp(6f), waterTop + dp(8f), x + dp(6f), waterTop + dp(12f), paint);
        paint.setColor(0xFF6B4A2B);
        c.drawRect(x - dp(1.5f), waterTop - dp(6f), x + dp(1.5f), waterTop + dp(10f), paint);
        paint.setColor(0xFF9A7350);
        c.drawRect(x - dp(1.5f), waterTop - dp(6f), x - dp(0.4f), waterTop + dp(10f), paint);
        // Board: a drop shadow, the yellow face, a darker lower band and a lit top edge.
        paint.setColor(0x50000000);
        c.drawRoundRect(x - half + dp(2f), waterTop - dp(26f), x + half + dp(2f), waterTop - dp(4f), dp(4f), dp(4f), paint);
        paint.setColor(0xFFF5C518);
        c.drawRoundRect(x - half, waterTop - dp(28f), x + half, waterTop - dp(6f), dp(4f), dp(4f), paint);
        paint.setColor(0xFFD9A80E);
        c.drawRoundRect(x - half, waterTop - dp(12f), x + half, waterTop - dp(6f), dp(4f), dp(4f), paint);
        paint.setColor(0xFFFFE27A);
        c.drawRect(x - half + dp(4f), waterTop - dp(27f), x + half - dp(4f), waterTop - dp(25.5f), paint);
        paint.setColor(0xFF111111);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        c.drawRoundRect(x - half, waterTop - dp(28f), x + half, waterTop - dp(6f), dp(4f), dp(4f), paint);
        paint.setStyle(Paint.Style.FILL);
        int old = textPaint.getColor();
        Paint.Align align = textPaint.getTextAlign();
        textPaint.setColor(0xFF111111);
        textPaint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, x, waterTop - dp(12f), textPaint);
        textPaint.setColor(old);
        textPaint.setTextAlign(align);
    }

    /** A chequered finish line across the water at screen x, with a banner post each side. */
    void drawFinishLine(Canvas c, float x, float top, float bottom, double time) {
        float cell = dp(8f);
        paint.setStyle(Paint.Style.FILL);
        // A faint bright wash either side, so the line reads as a line from the seat.
        paint.setColor(0x18FFFFFF);
        c.drawRect(x - cell - dp(6f), top, x + cell + dp(6f), bottom, paint);
        int row = 0;
        for (float y = top; y < bottom; y += cell, row++) {
            for (int col = 0; col < 2; col++) {
                paint.setColor(((row + col) & 1) == 0 ? 0xEEFFFFFF : 0xEE111111);
                c.drawRect(x - cell + col * cell, y, x + col * cell, Math.min(bottom, y + cell), paint);
            }
        }
        // Pole with a lit edge, and a two-tone pennant that flaps.
        paint.setColor(0xFF9AA3AD);
        c.drawRect(x - dp(2f), top - dp(46f), x + dp(2f), top, paint);
        paint.setColor(0xFFF4F6F8);
        c.drawRect(x - dp(2f), top - dp(46f), x - dp(0.6f), top, paint);
        paint.setColor(0xFFF0B132);
        c.drawCircle(x, top - dp(47f), dp(2.6f), paint);
        float flap = (float) Math.sin(time * 5) * dp(3f);
        path.rewind();
        path.moveTo(x + dp(2f), top - dp(46f));
        path.lineTo(x + dp(36f), top - dp(40f) + flap);
        path.lineTo(x + dp(2f), top - dp(32f));
        path.close();
        paint.setColor(0xFFF0655D);
        c.drawPath(path, paint);
        path.rewind();
        path.moveTo(x + dp(2f), top - dp(39f) + flap * 0.2f);
        path.lineTo(x + dp(36f), top - dp(40f) + flap);
        path.lineTo(x + dp(2f), top - dp(32f));
        path.close();
        paint.setColor(dim(0xFFF0655D));
        c.drawPath(path, paint);
    }
}
