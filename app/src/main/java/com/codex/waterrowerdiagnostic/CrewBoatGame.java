package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;

/**
 * CREW BOAT: you are the stroke seat of an eight, and seven rowers follow your rhythm.
 *
 * <p>Steady, even strokes pull the crew into time - the oars go in together and the boat surges,
 * which rowers call swing. Uneven strokes break it: the crew catches at different moments, blades
 * clash and splash, and the boat slows. The reward is for exactly what makes long sessions easier,
 * so it teaches pacing rather than sprinting.
 *
 * <p>Timing comes from the pulse meter's stroke detection, so it is measured at the drive, not a
 * second late off the monitor's counter. The crew's own tempo follows yours slowly, so changing
 * rate deliberately is fine; it is the stroke-to-stroke wobble that costs.
 */
final class CrewBoatGame extends GameView {

    private enum Phase { READY, RACING, DONE }

    private static final int RACE_METERS = 1000;
    private static final int SEATS = 8;

    private final PersonalBests bests;
    private final RiverRenderer river;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Fx.Particles fx = new Fx.Particles();
    private final float[] seatLag = new float[SEATS];

    private Phase phase = Phase.READY;
    private double raceStart;
    private double yourMeters;
    private double rivalMeters;
    private double finishTime;
    private boolean won;
    private float sync = 0.5f;
    private float syncSum;
    private int syncStrokes;
    private int swing;
    private double crewInterval;
    private double lastStrokeAt = -1;
    private PulseMeter.Stroke lastSeen;

    CrewBoatGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.river = new RiverRenderer(getResources().getDisplayMetrics().density);
        java.util.Random r = new java.util.Random(8);
        for (int i = 1; i < SEATS; i++) {
            seatLag[i] = (r.nextFloat() - 0.5f) * 2f;
        }
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        yourMeters = 0;
        rivalMeters = 0;
        sync = 0.5f;
        syncSum = 0;
        syncStrokes = 0;
        swing = 0;
        crewInterval = 60.0 / Math.max(14, profile.typicalRate());
        lastStrokeAt = -1;
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastSeen) {
            lastSeen = stroke;
            strokeLanded();
        }
    }

    @Override
    protected void onStroke(int watts) {
        if (status != null && status.meter.strokes == 0) {
            strokeLanded();
        }
    }

    private void strokeLanded() {
        if (phase == Phase.READY) {
            phase = Phase.RACING;
            raceStart = sessionSeconds;
        }
        double now = sessionSeconds;
        if (lastStrokeAt >= 0 && phase == Phase.RACING) {
            double interval = now - lastStrokeAt;
            if (interval > 0.8 && interval < 8) {
                double deviation = Math.abs(interval - crewInterval) / crewInterval;
                float target = (float) Math.max(0, Math.min(1, 1 - deviation / 0.12));
                sync += (target - sync) * 0.35f;
                crewInterval += (interval - crewInterval) * 0.25;
                swing = sync > 0.85f ? swing + 1 : 0;
                syncSum += sync;
                syncStrokes++;
                if (sync < 0.5f) {
                    fx.burst(getWidth() * 0.5f, getHeight() * 0.62f, 26, dp(160f), 0.6f, dp(3f), 0xDDBFE3FF, true);
                }
            }
        }
        lastStrokeAt = now;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.DONE) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        fx.step(dt, dp(260f));
        float speed = boat.value();
        double sinceStroke = lastStrokeAt < 0 ? 99 : sessionSeconds - lastStrokeAt;
        if (sinceStroke > crewInterval * 2.2) {
            sync = Math.max(0f, sync - dt * 0.08f);
            swing = 0;
        }
        // Sync is worth 15% either way, and swing adds a little more: the crew moves the boat.
        float factor = 0.85f + 0.3f * sync + (swing >= 6 ? 0.05f : 0f);
        if (phase == Phase.RACING) {
            yourMeters += speed * factor * dt;
            rivalMeters += profile.typicalSpeed() * 1.02 * dt;
            if (yourMeters >= RACE_METERS) {
                phase = Phase.DONE;
                finishTime = sessionSeconds - raceStart;
                won = yourMeters - rivalMeters >= 0;
                bests.recordLowest("crew.time." + RACE_METERS, (float) finishTime);
                if (syncStrokes > 10) {
                    bests.recordHighest("crew.sync", 100f * syncSum / syncStrokes);
                }
            }
        }

        float waterTop = h * 0.30f;
        float waterBottom = h * 0.88f;
        float ppm = w / 70f;
        river.advance(phase == Phase.RACING ? speed * factor : 0f, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);

        // The rival crew in the far lane, placed by the gap.
        double gap = yourMeters - rivalMeters;
        float rivalX = Math.max(dp(60f), Math.min(w - dp(60f), w * 0.5f - (float) gap * ppm));
        river.drawBoat(c, rivalX, waterTop + (waterBottom - waterTop) * 0.2f, dp(180f), BLUE,
                phase == Phase.RACING ? (float) profile.typicalSpeed() : 0f, true);

        drawEight(c, w, waterTop + (waterBottom - waterTop) * 0.62f, sinceStroke);
        fx.draw(c);
        if (swing >= 6) {
            Fx.vignette(c, w, h, 0.25f, 0x1A6A5A);
        }

        // HUD: sync meter, swing, gap.
        float cx = w / 2f;
        bold(c, Math.round(sync * 100) + "%", cx, dp(44f), 38f, sync > 0.85f ? ACCENT : sync > 0.6f ? WARN : BAD, Paint.Align.CENTER);
        label(c, swing >= 6 ? "SWING  ·  " + swing + " STROKES IN TIME" : "CREW SYNC", cx, dp(62f), 10f,
                swing >= 6 ? ACCENT : FAINT, Paint.Align.CENTER);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(cx - dp(160f), dp(72f), cx + dp(160f), dp(80f), dp(4f), dp(4f), paint);
        paint.setColor(sync > 0.85f ? ACCENT : sync > 0.6f ? WARN : BAD);
        c.drawRoundRect(cx - dp(160f), dp(72f), cx - dp(160f) + dp(320f) * sync, dp(80f), dp(4f), dp(4f), paint);
        String status;
        if (phase == Phase.READY) {
            status = "TAKE A STROKE - THE CREW FOLLOWS YOUR RHYTHM";
        } else if (phase == Phase.DONE) {
            status = (won ? "YOUR CREW WON  ·  " : "BEATEN  ·  ") + clock(finishTime) + "  ·  tap to race again";
        } else {
            status = String.format(java.util.Locale.US, "%s%.0f m  ·  %d of %d m  ·  %s", gap >= 0 ? "+" : "−",
                    Math.abs(gap), Math.round(yourMeters), RACE_METERS, clock(sessionSeconds - raceStart));
        }
        bold(c, status, cx, h - dp(14f), 12f, phase == Phase.DONE && won ? ACCENT : TEXT, Paint.Align.CENTER);
        label(c, "EVEN STROKES = SYNC  ·  RATE " + Math.round(60 / crewInterval), dp(16f), dp(30f), 10f, FAINT, Paint.Align.LEFT);
    }

    /** The eight, side on: rowers lean and slide, oars sweep and dip - in time or not. */
    private void drawEight(Canvas c, float w, float waterY, double sinceStroke) {
        float len = w * 0.62f;
        float cx = w * 0.5f;
        float beam = dp(16f);
        paint.setColor(0xFFE8E2D0);
        c.drawRoundRect(cx - len / 2f, waterY - beam, cx + len / 2f, waterY + beam * 0.3f, beam, beam, paint);
        paint.setColor(0xFF2F6E93);
        c.drawRect(cx - len / 2f, waterY - beam * 0.25f, cx + len / 2f, waterY + beam * 0.3f, paint);
        float spacing = len * 0.8f / SEATS;
        for (int i = 0; i < SEATS; i++) {
            // Stroke seat (i = 0) is at the stern, on the right, facing the stern like a real crew.
            float seatX = cx + len * 0.36f - i * spacing;
            float lag = (1f - sync) * seatLag[i] * 0.28f;
            float phaseT = (float) ((sinceStroke / crewInterval) + lag);
            phaseT = phaseT - (float) Math.floor(phaseT);
            float drive = phaseT < 0.35f ? phaseT / 0.35f : 1f - (phaseT - 0.35f) / 0.65f;
            float slide = drive * spacing * 0.3f;
            float bodyX = seatX - slide;
            paint.setColor(i == 0 ? ACCENT : 0xFF3A5BD9);
            c.drawRect(bodyX - dp(5f), waterY - beam - dp(26f), bodyX + dp(5f), waterY - beam, paint);
            paint.setColor(0xFFF1C27D);
            c.drawCircle(bodyX + (drive - 0.5f) * dp(6f), waterY - beam - dp(32f), dp(6f), paint);
            float bladeX = seatX + (drive - 0.5f) * spacing * 0.9f;
            boolean inWater = phaseT < 0.35f;
            float bladeY = waterY + (inWater ? dp(14f) : -dp(8f));
            paint.setColor(0xFFCBB38A);
            paint.setStrokeWidth(dp(3f));
            c.drawLine(seatX, waterY - beam - dp(8f), bladeX, bladeY, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawOval(bladeX - dp(10f), bladeY - dp(4f), bladeX + dp(10f), bladeY + dp(4f), paint);
        }
        // Cox at the stern.
        paint.setColor(WARN);
        c.drawCircle(cx + len * 0.46f, waterY - beam - dp(10f), dp(7f), paint);
    }
}
