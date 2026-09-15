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
    private final android.graphics.Path path = new android.graphics.Path();
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
    private double scenery;
    private String coxCall = "";
    private double coxUntil;
    private int strokesSinceCall;
    private static final String[] CALLS_GOOD = {"IN TIME!", "SWING IT!", "BEAUTIFUL!", "HOLD THAT RHYTHM!", "SHE'S FLYING!"};
    private static final String[] CALLS_BAD = {"TOGETHER!", "WATCH STROKE!", "FIND THE RHYTHM!", "CATCH TOGETHER!"};
    private static final String[] CALLS_PUSH = {"LEGS, LEGS, LEGS!", "PUSH NOW!", "TEN BIG ONES!", "SQUEEZE!"};

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
        if (phase == Phase.RACING && ++strokesSinceCall >= 4) {
            strokesSinceCall = 0;
            String[] calls = sync > 0.8f ? CALLS_GOOD : sync < 0.5f ? CALLS_BAD : CALLS_PUSH;
            coxCall = calls[(int) (Math.random() * calls.length)];
            coxUntil = sessionSeconds + 2.2;
        }
        // Every catch throws water, more of it when the crew is ragged.
        fx.burst(getWidth() * 0.5f, getHeight() * 0.70f, sync > 0.8f ? 10 : 22, dp(120f), 0.5f, dp(2.5f), 0xCCBFE3FF, true);
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
        float moving = phase == Phase.RACING ? speed * factor : 0f;
        scenery += moving * dt;
        drawBank(c, w, h, waterTop, ppm);
        river.advance(moving, dt, ppm);
        river.drawWater(c, waterTop, waterBottom, w);
        drawBuoys(c, w, waterTop, waterBottom, ppm);

        // The rival crew in the far lane, placed by the gap.
        double gap = yourMeters - rivalMeters;
        float rivalX = Math.max(dp(60f), Math.min(w - dp(60f), w * 0.5f - (float) gap * ppm));
        river.drawBoat(c, rivalX, waterTop + (waterBottom - waterTop) * 0.2f, dp(180f), BLUE,
                phase == Phase.RACING ? (float) profile.typicalSpeed() : 0f, true);

        float eightY = waterTop + (waterBottom - waterTop) * 0.62f;
        if (swing >= 6) {
            // Swing: a glowing wake and streaks - the boat running away underneath the crew.
            paint.setStrokeWidth(dp(3f));
            for (int k = 0; k < 7; k++) {
                float sx = (float) ((w * 0.8f - (sessionSeconds * dp(420f) + k * dp(160f)) % (w * 1.2f)));
                paint.setColor(0x6635D0BA);
                c.drawLine(sx, eightY + dp(24f) + k * dp(6f), sx - dp(90f), eightY + dp(24f) + k * dp(6f), paint);
            }
            Fx.glow(c, w * 0.5f, eightY, w * 0.35f, 0x2235D0BA);
        }
        drawEight(c, w, eightY, sinceStroke);
        fx.draw(c);
        if (swing >= 6) {
            Fx.vignette(c, w, h, 0.25f, 0x1A6A5A);
            if (((int) (sessionSeconds * 3)) % 2 == 0) {
                bold(c, "SWING!", w * 0.5f, eightY - dp(90f), 30f, ACCENT, Paint.Align.CENTER);
            }
        }
        if (sessionSeconds < coxUntil) {
            drawCoxCall(c, w * 0.5f + w * 0.62f * 0.46f, eightY - dp(24f), w);
        }
        double toGo = RACE_METERS - yourMeters;
        if (phase == Phase.RACING && toGo < 150) {
            float fx0 = w * 0.5f + (float) toGo * ppm;
            if (fx0 < w + dp(40f)) {
                paint.setColor(0xFFF2F2F2);
                c.drawRect(fx0 - dp(3f), waterTop - dp(80f), fx0 + dp(3f), waterBottom, paint);
                for (int k = 0; k < 8; k++) {
                    paint.setColor(k % 2 == 0 ? 0xFFF0655D : 0xFFFFFFFF);
                    c.drawRect(fx0 + dp(3f), waterTop - dp(80f) + k * dp(8f), fx0 + dp(60f), waterTop - dp(72f) + k * dp(8f), paint);
                }
                bold(c, "FINISH", fx0 + dp(32f), waterTop - dp(88f), 12f, TEXT, Paint.Align.CENTER);
            }
        }

        // HUD: sync meter, swing, gap.
        float cx = w / 2f;
        // 3.19.5: the HUD sits on the light sky, so it gets dark pills (seen unreadable on the emulator).
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x990A1420);
        c.drawRoundRect(cx - dp(120f), dp(6f), cx + dp(120f), dp(72f), dp(14f), dp(14f), paint);
        c.drawRoundRect(dp(8f), dp(16f), dp(300f), dp(38f), dp(10f), dp(10f), paint);
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
        label(c, "EVEN STROKES = SYNC  ·  RATE " + Math.round(60 / crewInterval), dp(16f), dp(30f), 10f, DIM, Paint.Align.LEFT);
    }

    /** Sky, a far bank of trees and a crowd along it with flags, scrolling with the boat. */
    private void drawBank(Canvas c, float w, float h, float waterTop, float ppm) {
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(new android.graphics.LinearGradient(0, 0, 0, waterTop, 0xFF3D78B8, 0xFFBFDDF2,
                android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, waterTop, paint);
        paint.setShader(null);
        // Clouds.
        paint.setColor(0xCCFFFFFF);
        for (int i = 0; i < 4; i++) {
            float cx = (float) (((i * 520 + 100) - scenery * ppm * 0.08) % (w + dp(260f)));
            if (cx < -dp(130f)) {
                cx += w + dp(260f);
            }
            float cy = waterTop * (0.25f + (i % 2) * 0.18f);
            c.drawOval(cx - dp(60f), cy - dp(14f), cx + dp(60f), cy + dp(14f), paint);
            c.drawOval(cx - dp(30f), cy - dp(26f), cx + dp(34f), cy + dp(6f), paint);
        }
        // Trees on the far bank.
        float bankY = waterTop - dp(4f);
        paint.setColor(0xFF3F7A45);
        c.drawRect(0, bankY - dp(10f), w, waterTop, paint);
        float treeGap = dp(70f);
        float off = (float) ((scenery * ppm * 0.4) % treeGap);
        for (float x = -off; x < w + treeGap; x += treeGap) {
            int k = (int) Math.floor((x + scenery * ppm * 0.4) / treeGap);
            float r = dp(22f) + ((k * 7) % 3) * dp(6f);
            paint.setColor(((k & 1) == 0) ? 0xFF2F6B3A : 0xFF3A7D44);
            c.drawCircle(x, bankY - dp(18f) - r * 0.4f, r, paint);
        }
        // The crowd along the bank: heads bobbing, flags waving.
        float fanGap = dp(18f);
        float foff = (float) ((scenery * ppm * 0.9) % fanGap);
        int[] shirts = {0xFFF0655D, 0xFFF0B132, 0xFF6F8CFF, 0xFFFFFFFF, 0xFF35D0BA};
        for (float x = -foff; x < w + fanGap; x += fanGap) {
            int k = (int) Math.floor((x + scenery * ppm * 0.9) / fanGap);
            float cheer = sync > 0.8f ? (float) Math.abs(Math.sin(sessionSeconds * 8 + k)) * dp(6f) : 0f;
            paint.setColor(shirts[Math.abs(k) % shirts.length]);
            c.drawRect(x - dp(5f), bankY - dp(20f) - cheer, x + dp(5f), bankY - dp(6f), paint);
            paint.setColor(0xFFF1C27D);
            c.drawCircle(x, bankY - dp(25f) - cheer, dp(4f), paint);
            if (k % 5 == 0) {
                paint.setColor(0xFF9AA5B1);
                c.drawRect(x + dp(4f), bankY - dp(44f) - cheer, x + dp(5.5f), bankY - dp(20f) - cheer, paint);
                float wave = (float) Math.sin(sessionSeconds * 6 + k) * dp(4f);
                paint.setColor(shirts[(Math.abs(k) + 2) % shirts.length]);
                path.reset();
                path.moveTo(x + dp(5.5f), bankY - dp(44f) - cheer);
                path.lineTo(x + dp(22f), bankY - dp(40f) - cheer + wave);
                path.lineTo(x + dp(5.5f), bankY - dp(34f) - cheer);
                path.close();
                c.drawPath(path, paint);
            }
        }
    }

    /** Lane buoys every 25 m, red and white, bobbing past. */
    private void drawBuoys(Canvas c, float w, float waterTop, float waterBottom, float ppm) {
        float gapPx = 25f * ppm;
        float off = (float) ((yourMeters * ppm) % gapPx);
        for (int lane = 0; lane < 2; lane++) {
            float y = waterTop + (waterBottom - waterTop) * (lane == 0 ? 0.40f : 0.88f);
            for (float x = -off; x < w + gapPx; x += gapPx) {
                int k = (int) Math.floor((x + yourMeters * ppm) / gapPx);
                float bob = (float) Math.sin(sessionSeconds * 3 + k) * dp(2f);
                paint.setColor((k & 1) == 0 ? 0xFFF0655D : 0xFFFFFFFF);
                c.drawCircle(x, y + bob, dp(5f), paint);
            }
        }
    }

    private void drawCoxCall(Canvas c, float x, float y, float w) {
        textPaint.setTextSize(dp(16f));
        float tw = textPaint.measureText(coxCall);
        float bx = Math.min(w - tw - dp(40f), x - tw / 2f - dp(12f));
        float by = y - dp(36f);
        paint.setColor(0xF2FFFFFF);
        c.drawRoundRect(bx, by, bx + tw + dp(24f), by + dp(32f), dp(12f), dp(12f), paint);
        path.reset();
        path.moveTo(bx + tw * 0.7f, by + dp(31f));
        path.lineTo(bx + tw * 0.7f + dp(14f), by + dp(31f));
        path.lineTo(x, y);
        path.close();
        c.drawPath(path, paint);
        bold(c, coxCall, bx + dp(12f) + tw / 2f, by + dp(22f), 16f, 0xFF3A2A06, Paint.Align.CENTER);
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
