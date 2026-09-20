package com.codex.waterrowerdiagnostic;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/**
 * Race-course dressing shared by the river games: a far bank with trees and a crowd, lane buoys,
 * distance boards and a finish line. Everything scrolls with the player's metres, so passing
 * scenery is itself a speed readout. Allocates nothing per frame.
 */
final class RiverScenery {

    private static final int[] SHIRTS = {0xFFF0655D, 0xFFF0B132, 0xFF6F8CFF, 0xFFFFFFFF, 0xFF35D0BA, 0xFFB48CFF};

    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    RiverScenery(float density) {
        this.density = density;
    }

    private float dp(float v) {
        return v * density;
    }

    /**
     * Life on the water itself, shared by every river game. The hulls already carry their own wake from RiverRenderer, so this
     * adds none: what the middle of the course was missing is surface texture and something moving
     * that is not a competitor. Chop at four depths, drifting foam, and a pair of ducks working
     * down the course - all scrolling with your metres, all kept out of the four racing lanes.
     */
    void drawWaterLife(Canvas c, float w, float waterTop, float waterBottom, double you,
                               float ppm, double time) {
        float span = waterBottom - waterTop;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Chop: short strokes on the lane boundaries and the open water between them. Nearer rows
        // scroll faster, which is what gives the flat navy field any sense of depth at all.
        for (int row = 0; row < 8; row++) {
            float y = waterTop + span * (0.05f + row * 0.125f);
            float depth = 0.3f + row * 0.1f;
            float step = dp(150f);
            float off = (float) ((you * ppm * depth * 0.6) % step);
            paint.setStrokeWidth(dp(1.2f) + depth * dp(0.8f));
            paint.setColor((Math.round(20 + 30 * depth) << 24) | 0x00BFE3FF);
            for (float x = -off; x < w + step; x += step) {
                float bob = (float) Math.sin(time * 1.5 + x * 0.015 + row) * dp(2.5f);
                c.drawLine(x, y + bob, x + dp(30f) * depth, y + bob, paint);
                c.drawLine(x + dp(62f), y + bob + dp(6f), x + dp(62f) + dp(20f) * depth,
                        y + bob + dp(6f), paint);
            }
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
        // Two ducks in the clear strip above the first lane, bobbing as they paddle.
        float duckStep = dp(520f);
        float duckOff = (float) ((you * ppm * 0.95) % duckStep);
        for (int k = -1; k < (int) (w / duckStep) + 2; k++) {
            float dx = k * duckStep - duckOff + dp(60f);
            for (int n = 0; n < 2; n++) {
                float x = dx + n * dp(26f);
                float y = waterTop + span * 0.035f + (float) Math.sin(time * 2.1 + k + n) * dp(1.8f);
                paint.setColor(0xFF2B3A46);
                c.drawOval(x, y, x + dp(15f), y + dp(8f), paint);          // body
                c.drawOval(x + dp(10f), y - dp(6f), x + dp(17f), y + dp(2f), paint);  // head
                paint.setColor(0xFFF0B132);
                c.drawRect(x + dp(16f), y - dp(3f), x + dp(19f), y - dp(1.5f), paint); // bill
                paint.setColor(0x33DCEBF7);
                c.drawOval(x - dp(5f), y + dp(6f), x + dp(15f), y + dp(10f), paint);   // its ripple
            }
        }
    }

    /** Half-brightness, for the crowd's back row: depth without a second palette. */
    private static int dim(int argb) {
        return 0xFF000000 | ((argb >>> 1) & 0x007F7F7F);
    }

    /**
     * The far bank between {@code top} and {@code bottom}: trees at a slow parallax, then a crowd
     * that jumps when {@code cheer} (0..1) is up, with flags on poles.
     */
    void drawBank(Canvas c, float w, float top, float bottom, double metres, float ppm, double time, float cheer) {
        c.save();
        c.clipRect(0, top, w, bottom);
        paint.setStyle(Paint.Style.FILL);
        float treeGap = dp(58f);
        double treeScroll = metres * ppm * 0.35;
        float off = (float) (treeScroll % treeGap);
        for (float x = -off - treeGap; x < w + treeGap; x += treeGap) {
            int k = (int) Math.floor((x + treeScroll) / treeGap + 0.5);
            float r = dp(15f) + (Math.abs(k * 7) % 3) * dp(5f);
            paint.setColor((k & 1) == 0 ? 0xFF24523A : 0xFF2F6B45);
            c.drawCircle(x, bottom - dp(14f) - r * 0.6f, r, paint);
            c.drawCircle(x + r * 0.7f, bottom - dp(10f) - r * 0.3f, r * 0.7f, paint);
        }
        paint.setColor(0xFF3F7A45);
        c.drawRect(0, bottom - dp(12f), w, bottom, paint);

        // A crowd, not a picket fence. At dp(15) with dp(8)-wide bodies the old spacing left a
        // dp(7) gap the whole width of the screen, which reads on the tablet as a barcode stripe.
        // Three things make it a crowd instead: irregular empty stretches, per-person jitter and
        // height, and a dimmer back row standing between the front one's shoulders.
        float fanGap = dp(22f);
        double fanScroll = metres * ppm * 0.8;
        float foff = (float) (fanScroll % fanGap);
        float ground = bottom - dp(4f);
        for (float x = -foff - fanGap * 2f; x < w + fanGap; x += fanGap) {
            int k = (int) Math.floor((x + fanScroll) / fanGap + 0.5);
            int h = Math.abs((k * 73) ^ (k >> 2) ^ (k * 1367));
            if (h % 7 < 2) {
                continue;       // an irregular gap in the line - people do not stand evenly
            }
            float jump = cheer * (float) Math.abs(Math.sin(time * 7 + k * 1.7)) * dp(5f);
            float fx = x + ((h % 5) - 2) * dp(2.2f);
            float tall = dp(12f) + (h % 4) * dp(1.6f);
            if (h % 3 == 0) {
                // Back row: smaller, darker, offset - depth without another pass of geometry.
                float bx = fx + dp(8f);
                float bjump = cheer * (float) Math.abs(Math.sin(time * 7 + k * 2.3)) * dp(4f);
                paint.setColor(dim(SHIRTS[Math.abs(k * 3 + 1) % SHIRTS.length]));
                c.drawRect(bx - dp(3f), ground - dp(4f) - tall * 0.8f - bjump,
                        bx + dp(3f), ground - dp(4f) - bjump, paint);
                paint.setColor(dim((k & 3) == 0 ? 0xFF8D5524 : 0xFFF1C27D));
                c.drawCircle(bx, ground - dp(4f) - tall * 0.8f - dp(2.6f) - bjump, dp(2.6f), paint);
            }
            paint.setColor(SHIRTS[Math.abs(k) % SHIRTS.length]);
            c.drawRect(fx - dp(3.6f), ground - tall - jump, fx + dp(3.6f), ground - jump, paint);
            paint.setColor((k & 3) == 0 ? 0xFF8D5524 : 0xFFF1C27D);
            c.drawCircle(fx, ground - tall - dp(3.2f) - jump, dp(3.2f), paint);
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
            }
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
            paint.setColor(0x33000000);
            c.drawOval(x - dp(6f), y + dp(2f), x + dp(6f), y + dp(5f), paint);
            paint.setColor((k & 1) == 0 ? 0xFFF0655D : 0xFFF4F4F4);
            c.drawCircle(x, y + bob, dp(4.2f), paint);
            paint.setColor(0x66FFFFFF);
            c.drawCircle(x - dp(1.4f), y + bob - dp(1.4f), dp(1.4f), paint);
        }
    }

    /** A floating board reading {@code text} at screen x, standing on the far bank line. */
    void drawBoard(Canvas c, float x, float waterTop, String text, Paint textPaint) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF6B4A2B);
        c.drawRect(x - dp(1.5f), waterTop - dp(6f), x + dp(1.5f), waterTop + dp(10f), paint);
        float half = Math.max(dp(26f), textPaint.measureText(text) / 2f + dp(8f));
        paint.setColor(0xFFF5C518);
        c.drawRoundRect(x - half, waterTop - dp(28f), x + half, waterTop - dp(6f), dp(4f), dp(4f), paint);
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
        int row = 0;
        for (float y = top; y < bottom; y += cell, row++) {
            for (int col = 0; col < 2; col++) {
                paint.setColor(((row + col) & 1) == 0 ? 0xEEFFFFFF : 0xEE111111);
                c.drawRect(x - cell + col * cell, y, x + col * cell, Math.min(bottom, y + cell), paint);
            }
        }
        paint.setColor(0xFFE0E0E0);
        c.drawRect(x - dp(2f), top - dp(46f), x + dp(2f), top, paint);
        float flap = (float) Math.sin(time * 5) * dp(3f);
        path.rewind();
        path.moveTo(x + dp(2f), top - dp(46f));
        path.lineTo(x + dp(36f), top - dp(40f) + flap);
        path.lineTo(x + dp(2f), top - dp(32f));
        path.close();
        paint.setColor(0xFFF0655D);
        c.drawPath(path, paint);
    }
}
