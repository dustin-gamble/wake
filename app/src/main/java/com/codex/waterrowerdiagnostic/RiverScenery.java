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

        float fanGap = dp(15f);
        double fanScroll = metres * ppm * 0.8;
        float foff = (float) (fanScroll % fanGap);
        float ground = bottom - dp(4f);
        for (float x = -foff - fanGap; x < w + fanGap; x += fanGap) {
            int k = (int) Math.floor((x + fanScroll) / fanGap + 0.5);
            if (Math.abs(k * 13) % 7 == 0) {
                continue;
            }
            float jump = cheer * (float) Math.abs(Math.sin(time * 7 + k * 1.7)) * dp(5f);
            paint.setColor(SHIRTS[Math.abs(k) % SHIRTS.length]);
            c.drawRect(x - dp(4f), ground - dp(14f) - jump, x + dp(4f), ground - jump, paint);
            paint.setColor((k & 3) == 0 ? 0xFF8D5524 : 0xFFF1C27D);
            c.drawCircle(x, ground - dp(18f) - jump, dp(3.4f), paint);
            if (cheer > 0.5f && (k & 1) == 0) {
                // Arms up.
                paint.setColor(0xFFF1C27D);
                c.drawRect(x - dp(6f), ground - dp(24f) - jump, x - dp(4.5f), ground - dp(12f) - jump, paint);
                c.drawRect(x + dp(4.5f), ground - dp(24f) - jump, x + dp(6f), ground - dp(12f) - jump, paint);
            }
            if (Math.abs(k) % 9 == 4) {
                paint.setColor(0xFF9AA5B1);
                c.drawRect(x + dp(3.5f), ground - dp(40f) - jump, x + dp(5f), ground - dp(14f) - jump, paint);
                float wave = (float) Math.sin(time * 6 + k) * dp(3f);
                paint.setColor(SHIRTS[(Math.abs(k) + 2) % SHIRTS.length]);
                path.rewind();
                path.moveTo(x + dp(5f), ground - dp(40f) - jump);
                path.lineTo(x + dp(22f), ground - dp(35f) - jump + wave);
                path.lineTo(x + dp(5f), ground - dp(29f) - jump);
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
