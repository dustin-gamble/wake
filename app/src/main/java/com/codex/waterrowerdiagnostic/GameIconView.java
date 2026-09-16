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
        FLY, ZONEROW, RIVER, COACH, CREW, GRID, REGATTA, DAILY, SHUFFLE
    }

    /**
     * 3.19.7, the rower's ask for animated cards: every icon moves, slowly. One clock for all of
     * them, ticking about 12 times a second - 21 cards repainting at 60 fps would cost the tablet
     * far more than the effect is worth, and these motions are meant to be gentle anyway.
     */
    private static final long FRAME_MS = 80;
    private long startedAtMs;
    private boolean ticking;

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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startedAtMs = System.currentTimeMillis();
        ticking = true;
        postInvalidateDelayed(FRAME_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        ticking = false;
        super.onDetachedFromWindow();
    }

    /** Seconds since this icon appeared, offset per kind so the grid does not pulse in unison. */
    private float t() {
        return (System.currentTimeMillis() - startedAtMs) / 1000f + kind.ordinal() * 0.37f;
    }

    private static float wave(float t, float period) {
        return (float) Math.sin(t / period * Math.PI * 2);
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
        if (ticking && isShown()) {
            postInvalidateDelayed(FRAME_MS);
        }
        float t = t();
        // A slow breath under every glyph, so even the still ones are alive.
        float breathe = 1f + wave(t, 6.5f) * 0.02f;
        c.scale(breathe, breathe, u(12), u(12));
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(u(2f));
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (kind) {
            case GAUGES: {
                p.setStyle(Paint.Style.STROKE);
                r.set(u(3), u(4), u(21), u(22));
                c.drawArc(r, 150, 240, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(u(12), u(13), u(1.6f), p);
                // The needle sweeps its range and settles, the way the real one does.
                float sweep = 150 + 240 * (0.5f + 0.42f * wave(t, 5.5f));
                double a = Math.toRadians(sweep);
                c.drawLine(u(12), u(13), u(12) + (float) Math.cos(a) * u(8),
                        u(13) + (float) Math.sin(a) * u(8), p);
                break;
            }
            case ZOMBIE: {
                // A slow shamble: it lurches about its feet, reaches, and drags one leg then the other.
                float shamble = wave(t, 2.6f);
                float step = wave(t, 1.3f) * u(1.1f);
                c.rotate(shamble * 4f, u(12), u(22));
                c.drawRect(u(8), u(3), u(16), u(11), p);       // head
                c.drawRect(u(7), u(11), u(17), u(19), p);      // body
                c.drawRect(u(17), u(12) + shamble * u(0.8f), u(22.8f),
                        u(14) + shamble * u(0.8f), p);         // arm out, reaching
                c.drawRect(u(8) + step, u(19), u(11) + step, u(23), p);
                c.drawRect(u(13) - step, u(19), u(16) - step, u(23), p);
                p.setColor(0xFF0A0E14);
                c.drawRect(u(9.5f), u(5), u(11.5f), u(7), p);
                c.drawRect(u(13), u(5), u(15), u(7), p);
                break;
            }
            case RUNNER: {
                // A running cycle: legs scissor, the arm pumps against them, the body bobs.
                float stride = wave(t, 1.4f);
                c.translate(0, -Math.abs(stride) * u(0.7f));
                c.drawRect(u(10), u(2), u(15), u(6), p);       // cap
                c.drawRect(u(10), u(6), u(14), u(9), p);       // head
                c.drawRect(u(8), u(9), u(16), u(15), p);       // body
                c.drawLine(u(9), u(15), u(7) - stride * u(3.5f), u(22), p);
                c.drawLine(u(14), u(15), u(17) + stride * u(3.5f), u(21), p);
                c.drawLine(u(16), u(11), u(21) - stride * u(2f), u(8) + stride * u(1.4f), p);
                break;
            }
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
            case HEADRACE: {
                // The two boats trade a small lead, each riding its own swell.
                float lead = wave(t, 6f) * u(1.6f);
                hull(c, u(12) + lead, u(9) + wave(t, 3.1f) * u(0.4f), u(18), color);
                hull(c, u(12) - lead, u(17) - wave(t, 3.7f) * u(0.4f), u(18),
                        kind == Kind.GHOST ? (color & 0x66FFFFFF) : 0xFF6F8CFF);
                break;
            }
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
            case TUG: {
                // The marker is hauled one way, then the other, and never quite settles.
                float pull = wave(t, 5.5f) * u(3.2f) + wave(t, 1.9f) * u(0.5f);
                c.drawRect(u(2), u(11), u(22), u(13), p);
                c.drawRect(u(2), u(6), u(4), u(18), p);
                p.setColor(0xFFF0655D);
                c.drawRect(u(20), u(6), u(22), u(18), p);
                p.setColor(color);
                c.drawCircle(u(12) + pull, u(12), u(3.5f), p);
                break;
            }
            case COLLECTOR:
                // Three buoys bobbing out of step with each other.
                c.drawCircle(u(7), u(8) + wave(t, 3.4f) * u(1.2f), u(3.5f), p);
                p.setColor(0xFF6F8CFF);
                c.drawCircle(u(17), u(8) + wave(t, 4.1f) * u(1.2f), u(3.5f), p);
                p.setColor(0xFFF0B132);
                c.drawCircle(u(12), u(16) + wave(t, 2.9f) * u(1.2f), u(3.5f), p);
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
                // The craft banks through the gate, left and right.
                c.rotate(wave(t, 5f) * 12f, u(12), u(12));
                path.reset();
                path.moveTo(u(3), u(12));
                path.lineTo(u(14), u(9));
                path.lineTo(u(11), u(12));
                path.lineTo(u(14), u(15));
                path.close();
                c.drawPath(path, p);
                break;
            case SHUFFLE:
                // Two crossing arrows, sliding along their own path.
                c.translate(wave(t, 3.2f) * u(1.2f), 0);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.4f));
                path.reset();
                path.moveTo(u(3), u(7));
                path.lineTo(u(8), u(7));
                path.cubicTo(u(13), u(7), u(12), u(17), u(17), u(17));
                path.lineTo(u(20), u(17));
                c.drawPath(path, p);
                path.reset();
                path.moveTo(u(3), u(17));
                path.lineTo(u(8), u(17));
                path.cubicTo(u(13), u(17), u(12), u(7), u(17), u(7));
                path.lineTo(u(20), u(7));
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(u(22), u(7));
                path.lineTo(u(18), u(4));
                path.lineTo(u(18), u(10));
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(u(22), u(17));
                path.lineTo(u(18), u(14));
                path.lineTo(u(18), u(20));
                path.close();
                c.drawPath(path, p);
                break;
            case RIVER:
                // A winding river with a boat on it.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(3.2f));
                path.reset();
                path.moveTo(u(4), u(22));
                path.cubicTo(u(4), u(14), u(18), u(16), u(15), u(9));
                path.cubicTo(u(13), u(5), u(19), u(3), u(21), u(2));
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFF5C518);
                // The boat works its way up the river and starts again.
                float along = (t % 7f) / 7f;
                c.drawCircle(u(4) + (u(17) - u(4)) * along * 0.85f + wave(t, 1.6f) * u(0.6f),
                        u(22) - (u(20)) * along, u(2.2f), p);
                break;
            case COACH:
                // A drive curve over a faint best-stroke outline.
                p.setAlpha(70);
                path.reset();
                path.moveTo(u(3), u(20));
                path.cubicTo(u(7), u(4), u(13), u(4), u(21), u(20));
                path.close();
                c.drawPath(path, p);
                p.setAlpha(255);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.4f));
                // Each drive builds to its peak and eases off against the faint best stroke.
                float drive = 0.5f + 0.5f * wave(t, 4.2f);
                path.reset();
                path.moveTo(u(3), u(20));
                path.cubicTo(u(8), u(11) - u(5) * drive, u(12), u(10) - u(5) * drive, u(21), u(20));
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                break;
            case CREW: {
                // Four oars sweeping in time, and the hull surging on each drive.
                float sweep = wave(t, 3.6f);
                c.translate(sweep * u(0.8f), 0);
                r.set(u(2), u(12), u(22), u(15));
                c.drawRoundRect(r, u(1.5f), u(1.5f), p);
                p.setStrokeWidth(u(1.6f));
                for (int i = 0; i < 4; i++) {
                    float x = u(5 + i * 4.5f);
                    c.drawLine(x, u(12), x - u(3) + sweep * u(2.6f),
                            u(20) - Math.abs(sweep) * u(1.2f), p);
                    c.drawCircle(x, u(10), u(1.4f), p);
                }
                break;
            }
            case GRID:
                // A house with a lit window and a spark.
                path.reset();
                path.moveTo(u(3), u(12));
                path.lineTo(u(10), u(6));
                path.lineTo(u(17), u(12));
                path.close();
                c.drawPath(path, p);
                c.drawRect(u(4.5f), u(12), u(15.5f), u(21), p);
                // The window glows up and down and the spark flickers over it.
                p.setColor(0xFFFFE08A);
                p.setAlpha(Math.round(140 + 115 * (0.5f + 0.5f * wave(t, 3.3f))));
                c.drawRect(u(8), u(14), u(12), u(18), p);
                p.setAlpha(Math.round(90 + 165 * (0.5f + 0.5f * wave(t, 1.7f))));
                path.reset();
                path.moveTo(u(20), u(3));
                path.lineTo(u(17), u(10));
                path.lineTo(u(20), u(10));
                path.lineTo(u(18), u(16));
                path.lineTo(u(23), u(8));
                path.lineTo(u(20), u(8));
                path.close();
                c.drawPath(path, p);
                p.setAlpha(255);
                break;
            case REGATTA: {
                // The pennant ripples on its pole.
                float ripple = wave(t, 2.4f);
                c.drawRect(u(6), u(3), u(7.5f), u(21), p);
                path.reset();
                path.moveTo(u(7.5f), u(3));
                path.quadTo(u(13), u(4.5f) + ripple * u(1.4f), u(19), u(6.5f) + ripple * u(0.8f));
                path.quadTo(u(13), u(8.5f) - ripple * u(1.4f), u(7.5f), u(10));
                path.close();
                c.drawPath(path, p);
                p.setAlpha(110);
                c.drawRect(u(3), u(15), u(22), u(16), p);
                c.drawRect(u(3), u(19), u(22), u(20), p);
                p.setAlpha(255);
                break;
            }
            case DAILY: {
                // The tick draws itself onto the page, holds the day, and starts over.
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2f));
                r.set(u(3), u(5), u(21), u(21));
                c.drawRoundRect(r, u(2), u(2), p);
                c.drawLine(u(3), u(9.5f), u(21), u(9.5f), p);
                p.setStrokeWidth(u(2.6f));
                float drawn = Math.min(1f, ((t % 5f) / 5f) / 0.45f);
                path.reset();
                path.moveTo(u(7.5f), u(15));
                if (drawn <= 0.5f) {
                    float k = drawn / 0.5f;
                    path.lineTo(u(7.5f) + (u(11) - u(7.5f)) * k, u(15) + (u(18) - u(15)) * k);
                } else {
                    float k = (drawn - 0.5f) / 0.5f;
                    path.lineTo(u(11), u(18));
                    path.lineTo(u(11) + (u(17) - u(11)) * k, u(18) - (u(18) - u(12)) * k);
                }
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                break;
            }
            case ZONEROW: {
                // The split works toward its marker and the rate bar breathes under it.
                float split = 0.62f + 0.22f * wave(t, 4.8f);
                float rate = 0.5f + 0.18f * wave(t, 3.3f);
                r.set(u(3), u(7), u(21), u(11));
                p.setAlpha(70);
                c.drawRoundRect(r, u(2), u(2), p);
                p.setAlpha(255);
                r.set(u(3), u(7), u(3) + u(18) * split, u(11));
                c.drawRoundRect(r, u(2), u(2), p);
                c.drawRect(u(14.5f), u(4.5f), u(16.2f), u(13.5f), p);
                p.setColor(0xFF6F8CFF);
                r.set(u(3), u(15.5f), u(3) + u(18) * rate, u(18.5f));
                c.drawRoundRect(r, u(1.5f), u(1.5f), p);
                break;
            }
            case FLY: {
                // The wings beat slowly and the whole bird rises and falls with them.
                float flap = wave(t, 1.8f);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(u(2.2f));
                c.translate(0, flap * u(0.5f));
                path.reset();
                path.moveTo(u(2), u(9) + flap * u(1.1f));
                path.quadTo(u(7), u(4) + flap * u(3.2f), u(12), u(9));
                path.quadTo(u(17), u(4) + flap * u(3.2f), u(22), u(9) + flap * u(1.1f));
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
            }
            case CITY:
                // Three towers of different heights with a lit window each.
                c.drawRect(u(3), u(12), u(9), u(22), p);
                c.drawRect(u(10), u(6), u(16), u(22), p);
                c.drawRect(u(17), u(15), u(22), u(22), p);
                // The windows come up and go down at their own hours.
                p.setColor(0xFFF5C518);
                p.setAlpha(Math.round(110 + 145 * (0.5f + 0.5f * wave(t, 3.1f))));
                c.drawRect(u(5), u(14), u(7), u(16), p);
                p.setAlpha(Math.round(110 + 145 * (0.5f + 0.5f * wave(t, 4.3f))));
                c.drawRect(u(12), u(9), u(14), u(11), p);
                p.setAlpha(Math.round(110 + 145 * (0.5f + 0.5f * wave(t, 2.7f))));
                c.drawRect(u(18.5f), u(17), u(20.5f), u(19), p);
                p.setAlpha(255);
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
                // The board rides up and down the face.
                float ride = wave(t, 4.5f);
                c.translate(ride * u(1.8f), -ride * u(1.2f));
                c.rotate(28f + ride * 6f, u(10), u(15));
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
                // Lifts a little, and the flame flickers.
                c.translate(0, wave(t, 3.8f) * u(0.9f));
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
                path.lineTo(u(12), u(23) + wave(t, 0.5f) * u(1.4f));
                path.close();
                c.drawPath(path, p);
                break;
            case MEGAPULL: {
                // The puck is driven up the tower and sinks back for the next pull.
                float pull = 0.5f + 0.5f * wave(t, 4.4f);
                c.drawRect(u(11), u(4), u(13), u(22), p);
                c.drawRect(u(6), u(21), u(18), u(23), p);
                c.drawRect(u(9), u(8), u(15), u(10), p);   // the record line
                p.setColor(0xFFF5C518);
                c.drawCircle(u(12), u(19) - u(15) * pull, u(2.5f), p);
                break;
            }
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
