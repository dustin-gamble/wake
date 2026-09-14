package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** A small drawn glyph for each game card. Primitives only; no assets. */
final class GameIconView extends View {

    enum Kind {
        GAUGES, ZOMBIE, RUNNER, BOSS, PACE, GHOST, RUN, INTERVALS, JOURNEY, STORM, ZONES,
        LADDER, TUG, COLLECTOR, DIVE, HEADRACE, CANYON, MEGAPULL, CHASE, ROCKET, CITY, SURF,
        FLY, ZONEROW
    }

    private final Kind kind;
    private final int color;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    GameIconView(Context context, Kind kind, int color) {
        super(context);
        this.kind = kind;
        this.color = color;
    }

    private float u(float v) {
        // Unit scale: icon designed on a 0..24 grid.
        return v / 24f * Math.min(getWidth(), getHeight());
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float ox = (w - Math.min(w, h)) / 2f;
        float oy = (h - Math.min(w, h)) / 2f;
        c.translate(ox, oy);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(u(2f));
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (kind) {
            case GAUGES:
                p.setStyle(Paint.Style.STROKE);
                r.set(u(3), u(4), u(21), u(22));
                c.drawArc(r, 150, 240, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(u(12), u(13), u(1.6f), p);
                c.drawLine(u(12), u(13), u(17), u(7), p);
                break;
            case ZOMBIE:
                c.drawRect(u(8), u(3), u(16), u(11), p);       // head
                c.drawRect(u(7), u(11), u(17), u(19), p);      // body
                c.drawRect(u(17), u(12), u(23), u(14), p);     // arm out
                c.drawRect(u(8), u(19), u(11), u(23), p);
                c.drawRect(u(13), u(19), u(16), u(23), p);
                p.setColor(0xFF0A0E14);
                c.drawRect(u(9.5f), u(5), u(11.5f), u(7), p);
                c.drawRect(u(13), u(5), u(15), u(7), p);
                break;
            case RUNNER:
                c.drawRect(u(10), u(2), u(15), u(6), p);       // cap
                c.drawRect(u(10), u(6), u(14), u(9), p);       // head
                c.drawRect(u(8), u(9), u(16), u(15), p);       // body
                c.drawLine(u(9), u(15), u(5), u(22), p);       // legs mid-stride
                c.drawLine(u(14), u(15), u(19), u(21), p);
                c.drawLine(u(16), u(11), u(21), u(8), p);      // arm
                break;
            case BOSS:
                r.set(u(3), u(9), u(21), u(22));
                c.drawOval(r, p);
                for (int i = 0; i < 4; i++) {
                    c.drawLine(u(6 + i * 4), u(11), u(4 + i * 4.5f), u(3), p);
                }
                p.setColor(0xFFF5C518);
                c.drawCircle(u(9), u(14), u(1.8f), p);
                c.drawCircle(u(15), u(14), u(1.8f), p);
                break;
            case PACE:
            case GHOST:
            case HEADRACE:
                hull(c, u(12), u(9), u(18), color);
                hull(c, u(12), u(17), u(18), kind == Kind.GHOST ? (color & 0x66FFFFFF) : 0xFF6F8CFF);
                break;
            case RUN:
                path.reset();
                path.moveTo(u(3), u(18));
                path.quadTo(u(8), u(4), u(12), u(14));
                path.quadTo(u(16), u(22), u(21), u(6));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.5f));
                c.drawPath(path, p);
                break;
            case INTERVALS:
                for (int i = 0; i < 5; i++) {
                    float hgt = (i % 2 == 0) ? u(16) : u(6);
                    c.drawRect(u(3 + i * 4), u(21) - hgt, u(6 + i * 4), u(21), p);
                }
                break;
            case JOURNEY:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.5f));
                path.reset();
                path.moveTo(u(3), u(19));
                path.cubicTo(u(8), u(19), u(8), u(6), u(13), u(10));
                path.cubicTo(u(18), u(14), u(17), u(4), u(21), u(4));
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(u(21), u(4), u(2.5f), p);
                break;
            case STORM:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.5f));
                for (int i = 0; i < 3; i++) {
                    path.reset();
                    path.moveTo(u(2), u(8 + i * 5));
                    path.quadTo(u(7), u(3 + i * 5), u(12), u(8 + i * 5));
                    path.quadTo(u(17), u(13 + i * 5), u(22), u(8 + i * 5));
                    c.drawPath(path, p);
                }
                break;
            case ZONES:
                int[] cols = {0xFF3B4A5E, 0xFF35D0BA, 0xFF6F8CFF, 0xFFF0B132, 0xFFF0655D};
                for (int i = 0; i < 5; i++) {
                    p.setColor(cols[i]);
                    c.drawRect(u(3 + i * 3.8f), u(21) - u(6 + i * 3), u(6 + i * 3.8f), u(21), p);
                }
                break;
            case LADDER:
                c.drawRect(u(6), u(2), u(8), u(22), p);
                c.drawRect(u(16), u(2), u(18), u(22), p);
                for (int i = 0; i < 4; i++) {
                    c.drawRect(u(6), u(5 + i * 5), u(18), u(7 + i * 5), p);
                }
                break;
            case TUG:
                c.drawRect(u(2), u(11), u(22), u(13), p);
                c.drawCircle(u(12), u(12), u(3.5f), p);
                c.drawRect(u(2), u(6), u(4), u(18), p);
                p.setColor(0xFFF0655D);
                c.drawRect(u(20), u(6), u(22), u(18), p);
                break;
            case COLLECTOR:
                c.drawCircle(u(7), u(8), u(3.5f), p);
                p.setColor(0xFF6F8CFF);
                c.drawCircle(u(17), u(8), u(3.5f), p);
                p.setColor(0xFFF0B132);
                c.drawCircle(u(12), u(16), u(3.5f), p);
                break;
            case DIVE:
                r.set(u(4), u(9), u(20), u(17));
                c.drawRoundRect(r, u(4), u(4), p);
                c.drawRect(u(9), u(5), u(13), u(9), p);
                p.setColor(0xFF0A0E14);
                c.drawCircle(u(16), u(13), u(1.8f), p);
                break;
            case CANYON:
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.2f));
                r.set(u(4), u(4), u(20), u(20));
                c.drawOval(r, p);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(u(3), u(12));
                path.lineTo(u(14), u(9));
                path.lineTo(u(11), u(12));
                path.lineTo(u(14), u(15));
                path.close();
                c.drawPath(path, p);
                break;
            case ZONEROW:
                // Two lane bars, the upper one filled to a marker, the lower one shorter.
                r.set(u(3), u(7), u(21), u(11));
                p.setAlpha(70);
                c.drawRoundRect(r, u(2), u(2), p);
                p.setAlpha(255);
                r.set(u(3), u(7), u(15), u(11));
                c.drawRoundRect(r, u(2), u(2), p);
                c.drawRect(u(14.5f), u(4.5f), u(16.2f), u(13.5f), p);
                p.setColor(0xFF6F8CFF);
                r.set(u(3), u(15.5f), u(12), u(18.5f));
                c.drawRoundRect(r, u(1.5f), u(1.5f), p);
                break;
            case FLY:
                // A gliding bird over a coastline.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.2f));
                path.reset();
                path.moveTo(u(2), u(9));
                path.quadTo(u(7), u(4), u(12), u(9));
                path.quadTo(u(17), u(4), u(22), u(9));
                c.drawPath(path, p);
                p.setStrokeWidth(u(1.6f));
                path.reset();
                path.moveTo(u(2), u(19));
                path.quadTo(u(8), u(16), u(13), u(19));
                path.quadTo(u(18), u(22), u(22), u(19));
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(u(12), u(9.5f), u(1.6f), p);
                break;
            case CITY:
                // Three towers of different heights with a lit window each.
                c.drawRect(u(3), u(12), u(9), u(22), p);
                c.drawRect(u(10), u(6), u(16), u(22), p);
                c.drawRect(u(17), u(15), u(22), u(22), p);
                p.setColor(0xFFF5C518);
                c.drawRect(u(5), u(14), u(7), u(16), p);
                c.drawRect(u(12), u(9), u(14), u(11), p);
                c.drawRect(u(18.5f), u(17), u(20.5f), u(19), p);
                break;
            case SURF:
                // A curling wave with a board on the face.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.4f));
                path.reset();
                path.moveTo(u(2), u(20));
                path.cubicTo(u(9), u(20), u(12), u(5), u(19), u(7));
                path.cubicTo(u(22), u(8), u(21), u(13), u(17), u(12));
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFF5C518);
                c.save();
                c.rotate(28f, u(10), u(15));
                c.drawRoundRect(u(6), u(14), u(14), u(16), u(1), u(1), p);
                c.restore();
                break;
            case CHASE:
                // Canyon walls converging to a vanishing point, with a craft in the gap.
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(u(1), u(22));
                path.lineTo(u(9), u(9));
                path.lineTo(u(9), u(22));
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(u(23), u(22));
                path.lineTo(u(15), u(9));
                path.lineTo(u(15), u(22));
                path.close();
                c.drawPath(path, p);
                p.setColor(0xFF35D0BA);
                path.reset();
                path.moveTo(u(12), u(13));
                path.lineTo(u(8), u(20));
                path.lineTo(u(12), u(18));
                path.lineTo(u(16), u(20));
                path.close();
                c.drawPath(path, p);
                break;
            case ROCKET:
                path.reset();
                path.moveTo(u(12), u(2));
                path.lineTo(u(16), u(10));
                path.lineTo(u(16), u(17));
                path.lineTo(u(8), u(17));
                path.lineTo(u(8), u(10));
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(u(8), u(13));
                path.lineTo(u(4), u(19));
                path.lineTo(u(8), u(17));
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(u(16), u(13));
                path.lineTo(u(20), u(19));
                path.lineTo(u(16), u(17));
                path.close();
                c.drawPath(path, p);
                p.setColor(0xFFF5C518);
                path.reset();
                path.moveTo(u(9.5f), u(17));
                path.lineTo(u(14.5f), u(17));
                path.lineTo(u(12), u(23));
                path.close();
                c.drawPath(path, p);
                break;
            case MEGAPULL:
                c.drawRect(u(11), u(4), u(13), u(22), p);
                c.drawRect(u(6), u(21), u(18), u(23), p);
                c.drawRect(u(9), u(8), u(15), u(10), p);
                p.setColor(0xFFF5C518);
                c.drawCircle(u(12), u(4), u(2.5f), p);
                break;
            default:
        }
    }

    private void hull(Canvas c, float cx, float cy, float len, int col) {
        p.setColor(col);
        p.setStyle(Paint.Style.FILL);
        path.reset();
        path.moveTo(cx + len / 2, cy);
        path.quadTo(cx, cy - u(2.2f), cx - len / 2, cy - u(1f));
        path.lineTo(cx - len / 2, cy + u(1f));
        path.quadTo(cx, cy + u(2.2f), cx + len / 2, cy);
        path.close();
        c.drawPath(path, p);
    }
}
