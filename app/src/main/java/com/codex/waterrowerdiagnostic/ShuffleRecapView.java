package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * The end of a SHUFFLE: every game played, what it scored, and its best moment - a record set in
 * it, or else its hardest stroke - with the total counting up at the top and the game that scored
 * most marked as the star. Rows arrive one after another so the recap reads like a replay.
 */
final class ShuffleRecapView extends View {

    /** One game's part of a shuffle. Filled in by MainActivity as the shuffle runs. */
    static final class Leg {
        final String title;
        int points;
        int strokes;
        float seconds;
        float bestWatts;
        float bestAtSeconds;
        int bestMultiplier = 1;
        /** Name of a record the rower beat in this game, or null. */
        String record;
        /** The record key watched for this game and its value when the game was dealt. */
        String recordKey;
        float recordBefore = Float.NaN;
        /** Whether the record check has run (after the game stopped). */
        boolean settled;
        /** Vetoed by the rower: out of the rest of this shuffle. */
        boolean vetoed;
        /** The boss round at the end of a round: double points, with a bar to empty. */
        boolean boss;
        boolean bossBeaten;
        /** The wildcard this game was dealt with, or "" for a clean one. */
        String wildcard = "";
        /** Points carried in from a combo that survived the switch into this game. */
        int carried;

        Leg(String title) {
            this.title = title;
        }

        String bestMoment() {
            if (boss) {
                return bossBeaten ? "BOSS DOWN  ·  double points" : "boss survived  ·  double points";
            }
            if (record != null) {
                return "NEW RECORD  ·  " + record;
            }
            String wild = wildcard.isEmpty() ? ""
                    : "  ·  wildcard: " + wildcard.toLowerCase(java.util.Locale.US);
            if (bestWatts <= 0) {
                return (strokes == 0 ? "skipped" : "steady strokes") + wild;
            }
            String s = Math.round(bestWatts) + " W stroke at " + PersonalBests.formatTime(bestAtSeconds);
            if (bestMultiplier > 1) {
                s += "  ·  x" + bestMultiplier + " combo";
            }
            if (carried > 0) {
                s += "  ·  +" + carried + " carried in";
            }
            return s + wild;
        }
    }

    private static final int MAX_ROWS = 12;

    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint total = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint row = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint note = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pts = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int purple = Color.parseColor("#B48CFF");
    private final int good = Color.parseColor("#35D0BA");
    private final int warn = Color.parseColor("#F0B132");
    private final int textColor = Color.parseColor("#E6EDF7");
    private final int dim = Color.parseColor("#8D9BB0");
    private final int surface = Color.parseColor("#111722");

    private Leg[] legs = new Leg[0];
    private String[] titles = new String[0];
    private String[] moments = new String[0];
    private String[] pointText = new String[0];
    private int hiddenEarlier;
    private int star = -1;
    private int maxPoints = 1;
    private int totalScore;
    private boolean newBest;
    private String subtitle = "";
    private float age;
    private long lastFrameMs;
    private int totalShownValue = -1;
    private String totalText = "0";

    ShuffleRecapView(Context context) {
        super(context);
        title.setColor(purple);
        title.setFakeBoldText(true);
        title.setLetterSpacing(0.16f);
        total.setColor(textColor);
        total.setFakeBoldText(true);
        total.setTypeface(android.graphics.Typeface.MONOSPACE);
        name.setColor(textColor);
        name.setFakeBoldText(true);
        name.setLetterSpacing(0.06f);
        note.setColor(dim);
        pts.setFakeBoldText(true);
        pts.setTypeface(android.graphics.Typeface.MONOSPACE);
        pts.setTextAlign(Paint.Align.RIGHT);
        row.setColor(surface);
    }

    void setRecap(java.util.List<Leg> all, int totalScore, boolean newBest, String subtitle) {
        int start = Math.max(0, all.size() - MAX_ROWS);
        hiddenEarlier = start;
        legs = all.subList(start, all.size()).toArray(new Leg[0]);
        titles = new String[legs.length];
        moments = new String[legs.length];
        pointText = new String[legs.length];
        maxPoints = 1;
        star = -1;
        for (int i = 0; i < legs.length; i++) {
            titles[i] = legs[i].boss ? "BOSS  \u00b7  " + legs[i].title : legs[i].title;
            moments[i] = legs[i].bestMoment();
            pointText[i] = legs[i].points + " pts";
            if (legs[i].points > maxPoints) {
                maxPoints = legs[i].points;
                star = i;
            }
        }
        this.totalScore = totalScore;
        this.newBest = newBest;
        this.subtitle = subtitle;
        age = 0f;
        lastFrameMs = 0;
        totalShownValue = -1;
        postInvalidateOnAnimation();
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
        long now = System.currentTimeMillis();
        float dt = lastFrameMs > 0 ? Math.min(0.1f, (now - lastFrameMs) / 1000f) : 0f;
        lastFrameMs = now;
        age += dt;

        float left = dp(40f);
        float right = w - dp(40f);
        title.setTextSize(dp(14f));
        c.drawText("SHUFFLE RECAP", left, dp(70f), title);
        note.setTextSize(dp(12f));
        c.drawText(subtitle, left, dp(92f), note);

        // The total counts up over the first 1.5 s.
        float tc = Math.min(1f, age / 1.5f);
        float te = 1f - (1f - tc) * (1f - tc) * (1f - tc);
        total.setTextSize(dp(46f));
        total.setTextAlign(Paint.Align.RIGHT);
        int totalShown = Math.round(totalScore * te);
        if (totalShown != totalShownValue) {
            totalShownValue = totalShown;
            totalText = String.valueOf(totalShown);
        }
        c.drawText(totalText, right, dp(84f), total);
        if (newBest && tc >= 1f) {
            float pulse = 1f + 0.08f * (float) Math.sin(age * 5.0);
            pts.setColor(warn);
            pts.setTextSize(dp(13f) * pulse);
            c.drawText("NEW BEST SHUFFLE SCORE", right, dp(104f), pts);
        }

        float top = dp(122f);
        float bottom = h - dp(20f);
        int n = legs.length + (hiddenEarlier > 0 ? 1 : 0);
        if (n == 0) {
            return;
        }
        float rowH = Math.min(dp(56f), (bottom - top) / n);
        float y = top;
        if (hiddenEarlier > 0) {
            note.setTextSize(dp(11f));
            c.drawText("+ " + hiddenEarlier + " earlier games", left, y + rowH * 0.6f, note);
            y += rowH;
        }
        boolean moving = age < 1.6f;
        for (int i = 0; i < legs.length; i++) {
            // Each row slides in 0.18 s after the one above it.
            float t = Math.max(0f, Math.min(1f, (age - 0.3f - i * 0.18f) / 0.35f));
            if (t < 1f) {
                moving = true;
            }
            if (t <= 0f) {
                y += rowH;
                continue;
            }
            float slide = (1f - t) * dp(60f);
            int alpha = (int) (255 * t);
            rect.set(left + slide, y + dp(3f), right + slide, y + rowH - dp(3f));
            row.setAlpha(alpha);
            c.drawRoundRect(rect, dp(10f), dp(10f), row);

            // Points bar along the bottom of the row, to scale with the best game.
            float barW = (rect.width() - dp(24f)) * legs[i].points / (float) maxPoints * t;
            bar.setColor(i == star ? warn : purple);
            bar.setAlpha((int) (alpha * 0.55f));
            c.drawRect(rect.left + dp(12f), rect.bottom - dp(7f), rect.left + dp(12f) + barW, rect.bottom - dp(4f), bar);

            float textSize = Math.min(dp(16f), rowH * 0.34f);
            name.setTextSize(textSize);
            // The boss round is named in amber, so a round reads as a round in the recap.
            name.setColor(legs[i].boss ? warn : textColor);
            name.setAlpha(alpha);
            c.drawText(titles[i], rect.left + dp(14f), rect.top + textSize + dp(4f), name);
            name.setColor(textColor);
            note.setTextSize(textSize * 0.78f);
            note.setColor(legs[i].record != null ? good : dim);
            note.setAlpha(alpha);
            c.drawText(moments[i], rect.left + dp(14f), rect.top + textSize * 2f + dp(6f), note);
            note.setColor(dim);
            pts.setTextSize(textSize * 1.2f);
            pts.setColor(i == star ? warn : textColor);
            pts.setAlpha(alpha);
            c.drawText(pointText[i], rect.right - dp(14f), rect.centerY() + textSize * 0.4f, pts);
            if (i == star) {
                pts.setTextSize(textSize * 0.7f);
                c.drawText("STAR GAME", rect.right - dp(14f), rect.centerY() - textSize * 0.8f, pts);
            }
            y += rowH;
        }
        // The NEW BEST pulse runs for a few seconds, then the screen stops redrawing.
        if (moving || (newBest && age < 8f)) {
            postInvalidateOnAnimation();
        }
    }
}
