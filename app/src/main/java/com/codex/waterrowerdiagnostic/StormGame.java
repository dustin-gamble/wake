package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;

/**
 * Storm: hold power above a rising threshold to keep the boat level. Drop below and it takes on
 * water; fill up and you sink. Survive as long as you can.
 *
 * <p>The ramp is forgiving early so a warm-up does not sink you, then escalates. Water drains
 * while you are above the line, so a lapse is recoverable if you answer it.
 */
final class StormGame extends GameView {

    private enum Phase { READY, STORM, SUNK }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();

    private int baseWatts = 100;
    private Phase phase = Phase.READY;
    private double stormSeconds;
    private double water;         // 0..1, 1 = sunk
    private double survived;
    private float wavePhase;
    private float tilt;

    StormGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    void setBaseWatts(int watts) {
        this.baseWatts = watts;
        phase = Phase.READY;
    }

    int baseWatts() {
        return baseWatts;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        stormSeconds = 0;
        water = 0;
        survived = 0;
        tilt = 0f;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.STORM;
            stormSeconds = 0;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.SUNK) {
            start();
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** Required watts rises 1.5% every 10 seconds after a 30 second grace. */
    private float requiredWatts() {
        double grace = Math.max(0, stormSeconds - 30);
        return baseWatts * (float) (1.0 + 0.015 * (grace / 10.0));
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        float required = requiredWatts();
        boolean holding = watts >= required;

        if (phase == Phase.STORM) {
            stormSeconds += dt;
            survived = stormSeconds;
            // Take water at a rate proportional to how far below the line you are; drain when
            // above it. A 30% shortfall sinks you in ~20s, which is enough time to answer.
            if (holding) {
                water = Math.max(0, water - 0.06 * dt);
            } else {
                float shortfall = required > 0 ? (required - watts) / required : 1f;
                water = Math.min(1, water + shortfall * 0.16 * dt);
            }
            if (water >= 1.0) {
                phase = Phase.SUNK;
                bests.recordHighest("storm." + baseWatts, (float) survived);
            }
        }
        wavePhase += dt * (1.5f + (float) water * 3f);
        float targetTilt = holding ? 0f : (float) water * 12f;
        tilt += (targetTilt - tilt) * Math.min(1f, 3f * dt);

        // Sky darkens with the storm's intensity.
        int storm = (int) Math.min(255, 40 + stormSeconds * 0.6);
        paint.setColor(0xFF000000 | (Math.max(0, 14 - storm / 20) << 16) | (Math.max(0, 22 - storm / 12) << 8) | 32);
        c.drawRect(0, 0, w, h, paint);

        // Sea: a wave whose amplitude grows with intensity.
        float seaLevel = h * 0.58f;
        float amp = dp(6f) + (float) Math.min(dp(26f), stormSeconds * 0.15f) + (float) water * dp(10f);
        wave.reset();
        wave.moveTo(0, h);
        wave.lineTo(0, seaLevel);
        for (float x = 0; x <= w; x += dp(8f)) {
            float y = seaLevel + (float) Math.sin(x / dp(60f) + wavePhase) * amp
                    + (float) Math.sin(x / dp(23f) - wavePhase * 1.7f) * amp * 0.35f;
            wave.lineTo(x, y);
        }
        wave.lineTo(w, h);
        wave.close();
        paint.setColor(0xFF0F2438);
        c.drawPath(wave, paint);

        // Boat, tilting as it takes water and sinking with it.
        float bx = w * 0.5f;
        float by = seaLevel + (float) water * dp(40f)
                + (float) Math.sin(wavePhase * 1.3f) * amp * 0.3f;
        c.save();
        c.rotate(tilt, bx, by);
        paint.setColor(holding ? ACCENT : BAD);
        float half = dp(70f);
        float beam = dp(14f);
        Path hull = new Path();
        hull.moveTo(bx + half, by);
        hull.quadTo(bx + half * 0.5f, by - beam, bx - half * 0.3f, by - beam);
        hull.lineTo(bx - half, by - beam * 0.5f);
        hull.lineTo(bx - half * 0.85f, by + beam * 0.7f);
        hull.lineTo(bx + half * 0.7f, by + beam * 0.7f);
        hull.close();
        c.drawPath(hull, paint);
        paint.setColor(TEXT);
        c.drawCircle(bx - half * 0.05f, by - beam * 0.4f, beam * 0.5f, paint);
        c.restore();

        // Water in the boat: a rising fill drawn over the hull outline.
        if (water > 0.02) {
            paint.setColor(0xAA1C4B7A);
            c.drawRect(bx - half * 0.85f, by + beam * 0.7f - (float) water * beam * 1.4f,
                    bx + half * 0.7f, by + beam * 0.7f, paint);
        }

        // HUD.
        String big;
        int color;
        switch (phase) {
            case READY:
                big = "STORM COMING";
                color = DIM;
                break;
            case SUNK:
                big = "SUNK";
                color = BAD;
                break;
            default:
                big = clock(survived);
                color = holding ? ACCENT : WARN;
        }
        bold(c, big, w / 2f, h * 0.20f, phase == Phase.STORM ? 56f : 30f, color, Paint.Align.CENTER);
        label(c, phase == Phase.READY ? "hold " + baseWatts + " W to stay afloat - take a stroke to start"
                : phase == Phase.SUNK ? "tap to bail out and try again"
                : (holding ? "HOLDING" : "TAKING WATER - PULL"),
                w / 2f, h * 0.20f + dp(20f), 10f, holding || phase != Phase.STORM ? FAINT : BAD,
                Paint.Align.CENTER);

        bold(c, watts + " W", dp(16f), h * 0.16f, 24f, holding ? ACCENT : BAD, Paint.Align.LEFT);
        label(c, "YOU", dp(16f), h * 0.16f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, Math.round(required) + " W", w - dp(16f), h * 0.16f, 24f, TEXT, Paint.Align.RIGHT);
        label(c, "NEEDED", w - dp(16f), h * 0.16f + dp(16f), 9f, FAINT, Paint.Align.RIGHT);

        // Bilge gauge.
        float gx = dp(16f);
        float gy = h - dp(40f);
        paint.setColor(0xFF18202C);
        c.drawRoundRect(gx, gy, w - dp(16f), gy + dp(10f), dp(5f), dp(5f), paint);
        paint.setColor(water > 0.7 ? BAD : water > 0.35 ? WARN : BLUE);
        c.drawRoundRect(gx, gy, gx + (w - dp(32f)) * (float) water, gy + dp(10f), dp(5f), dp(5f), paint);
        label(c, "WATER IN THE BOAT", gx, gy - dp(6f), 8.5f, FAINT, Paint.Align.LEFT);
        label(c, bests.has("storm." + baseWatts)
                ? "best " + clock(bests.get("storm." + baseWatts, 0)) : "no best yet",
                w - dp(16f), gy - dp(6f), 8.5f, FAINT, Paint.Align.RIGHT);

        if (phase == Phase.SUNK) {
            label(c, "survived " + clock(survived), w / 2f, h * 0.20f + dp(36f), 12f, DIM,
                    Paint.Align.CENTER);
        }
    }
}
