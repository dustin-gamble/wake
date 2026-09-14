package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The home screen's reasons to come back: this week's minutes against the goal as a ring, the level
 * with progress to the next, and the streak.
 */
final class HomeProgressView extends View {

    private int level = 1;
    private float levelShare;
    private int xpToNext;
    private float weekMinutes;
    private float goalMinutes = 60f;
    private int streak;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final int accent = Color.parseColor("#35D0BA");
    private final int warn = Color.parseColor("#F0B132");
    private final int text = Color.parseColor("#E6EDF7");
    private final int faint = Color.parseColor("#5D6B80");
    private final int track = Color.parseColor("#1A2433");

    HomeProgressView(Context context) {
        super(context);
    }

    void set(double xp, float weekMinutes, float goalMinutes, int streak) {
        level = Progress.levelFor(xp);
        double from = Progress.xpForLevel(level);
        double to = Progress.xpForLevel(level + 1);
        levelShare = (float) ((xp - from) / Math.max(1, to - from));
        xpToNext = (int) Math.ceil(to - xp);
        this.weekMinutes = weekMinutes;
        this.goalMinutes = goalMinutes;
        this.streak = streak;
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float cy = h / 2f;
        // Weekly ring.
        float r = h * 0.38f;
        float rx = dp(8f) + r;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(6f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(track);
        arc.set(rx - r, cy - r, rx + r, cy + r);
        c.drawArc(arc, 0, 360, false, paint);
        float share = Math.min(1f, weekMinutes / Math.max(1f, goalMinutes));
        paint.setColor(share >= 1f ? warn : accent);
        c.drawArc(arc, -90, 360 * share, false, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setFakeBoldText(true);
        paint.setColor(text);
        paint.setTextSize(dp(15f));
        float tx = rx + r + dp(12f);
        c.drawText(Math.round(weekMinutes) + " / " + Math.round(goalMinutes) + " min", tx, cy - dp(2f), paint);
        paint.setFakeBoldText(false);
        paint.setColor(faint);
        paint.setTextSize(dp(10f));
        c.drawText(share >= 1f ? "WEEKLY GOAL MET" : "THIS WEEK", tx, cy + dp(13f), paint);

        // Level bar.
        float lx = w * 0.36f;
        float lw = w * 0.34f;
        paint.setFakeBoldText(true);
        paint.setColor(text);
        paint.setTextSize(dp(15f));
        c.drawText("LEVEL " + level, lx, cy - dp(6f), paint);
        paint.setFakeBoldText(false);
        paint.setColor(track);
        c.drawRoundRect(lx, cy + dp(2f), lx + lw, cy + dp(10f), dp(4f), dp(4f), paint);
        paint.setColor(accent);
        c.drawRoundRect(lx, cy + dp(2f), lx + lw * Math.max(0.02f, Math.min(1f, levelShare)), cy + dp(10f), dp(4f), dp(4f), paint);
        paint.setColor(faint);
        paint.setTextSize(dp(10f));
        c.drawText(xpToNext + " XP to level " + (level + 1), lx, cy + dp(24f), paint);

        // Streak.
        float sx = w * 0.76f;
        paint.setColor(streak > 0 ? warn : faint);
        paint.setFakeBoldText(true);
        paint.setTextSize(dp(22f));
        c.drawText(String.valueOf(streak), sx, cy + dp(4f), paint);
        float numW = paint.measureText(String.valueOf(streak));
        paint.setFakeBoldText(false);
        paint.setTextSize(dp(11f));
        paint.setColor(text);
        c.drawText(streak == 1 ? "DAY STREAK" : "DAY STREAK", sx + numW + dp(8f), cy - dp(2f), paint);
        paint.setColor(faint);
        paint.setTextSize(dp(9.5f));
        c.drawText("1 rest day a week is free", sx + numW + dp(8f), cy + dp(12f), paint);
    }
}
