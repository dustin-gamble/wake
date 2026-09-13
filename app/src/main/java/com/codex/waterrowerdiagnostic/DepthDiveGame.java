package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

/**
 * Depth Dive: cumulative work lowers a submersible past named depths. Purely additive, no fail
 * state - for long steady sessions where a race would be exhausting.
 *
 * <p>Depth is energy: joules = watts x seconds, so it rewards sustained output rather than a
 * burst. 1 kJ per metre makes a steady 150 W dive about 9 m a minute.
 */
final class DepthDiveGame extends GameView {

    private static final String[] MARKS = {
            "Surface", "Snorkel limit", "Recreational scuba", "Deepest free dive", "Deepest scuba",
            "Sunlight ends", "Blue whale dive", "Sperm whale dive", "Titanic", "Midnight zone"};
    private static final int[] MARK_M = {0, 10, 40, 214, 332, 200, 500, 2250, 3800, 4000};

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private double joules;
    private double sessionStartJoules;
    private float bubble;

    DepthDiveGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        joules = bests.get("dive.joules", 0f);
        sessionStartJoules = joules;
    }

    @Override
    protected void onStop() {
        bests.recordHighest("dive.joules", (float) joules);
    }

    private double depth() {
        return joules / 1000.0;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        if (watts > 0 && driving) {
            joules += watts * dt;
        }
        double d = depth();

        // Sea getting darker with depth: the window scrolls so the sub stays mid-screen.
        float shade = (float) Math.min(1.0, d / 1000.0);
        int top = blend(0xFF1B5E8A, 0xFF03080F, shade);
        int bottom = blend(0xFF0F2438, 0xFF000000, shade);
        paint.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // Depth scale: 60 m visible, sub at 40% down.
        float visible = 60f;
        float ppm = h / visible;
        float subY = h * 0.40f;
        float topDepth = (float) d - subY / ppm;
        paint.setColor(0x33E6EDF7);
        paint.setStrokeWidth(dp(1f));
        float firstMark = (float) Math.ceil(topDepth / 10f) * 10f;
        for (float m = firstMark; m < topDepth + visible; m += 10f) {
            float y = (m - topDepth) * ppm;
            c.drawLine(dp(12f), y, dp(28f), y, paint);
            label(c, Math.round(m) + " m", dp(34f), y + dp(4f), 8.5f, FAINT, Paint.Align.LEFT);
        }
        // Named marks in view.
        for (int i = 0; i < MARKS.length; i++) {
            float y = (MARK_M[i] - topDepth) * ppm;
            if (y > -dp(20f) && y < h + dp(20f)) {
                paint.setColor(d >= MARK_M[i] ? ACCENT : 0x66E6EDF7);
                c.drawLine(w * 0.55f, y, w - dp(12f), y, paint);
                label(c, MARKS[i].toUpperCase(java.util.Locale.US), w - dp(12f), y - dp(4f), 8.5f,
                        d >= MARK_M[i] ? ACCENT : DIM, Paint.Align.RIGHT);
            }
        }

        // Submersible.
        float sx = w * 0.42f;
        paint.setColor(WARN);
        c.drawRoundRect(sx - dp(30f), subY - dp(11f), sx + dp(30f), subY + dp(11f), dp(11f), dp(11f), paint);
        paint.setColor(0xFF0A0E14);
        c.drawCircle(sx + dp(10f), subY, dp(6f), paint);
        paint.setColor(WARN);
        c.drawRect(sx - dp(6f), subY - dp(20f), sx + dp(4f), subY - dp(10f), paint);
        // Bubbles while driving.
        bubble += dt * (watts > 0 ? 40f : 10f);
        paint.setColor(0x88E6EDF7);
        for (int i = 0; i < 4; i++) {
            float phase = (bubble + i * 23f) % 80f;
            c.drawCircle(sx - dp(34f) + (i % 2) * dp(6f), subY - phase * dp(0.6f), dp(2f + i * 0.6f), paint);
        }

        bold(c, String.format(java.util.Locale.US, "%.1f m", d), w * 0.42f, h * 0.20f, 44f, TEXT,
                Paint.Align.CENTER);
        label(c, "DEPTH  ·  " + Math.round(joules / 1000.0) + " kJ OF WORK", w * 0.42f,
                h * 0.20f + dp(20f), 10f, FAINT, Paint.Align.CENTER);

        String next = "";
        double toNext = 0;
        for (int i = 0; i < MARKS.length; i++) {
            if (MARK_M[i] > d) {
                next = MARKS[i];
                toNext = MARK_M[i] - d;
                break;
            }
        }
        float fy = h - dp(14f);
        float col = w / 3f;
        stat(c, col * 0.5f, fy, watts + " W", "NOW");
        stat(c, col * 1.5f, fy, String.format(java.util.Locale.US, "%.1f m",
                (joules - sessionStartJoules) / 1000.0), "THIS SESSION");
        stat(c, col * 2.5f, fy, next.isEmpty() ? "--" : String.format(java.util.Locale.US, "%.0f m", toNext),
                next.isEmpty() ? "BOTTOM" : "TO " + next.toUpperCase(java.util.Locale.US));
    }

    private static int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
