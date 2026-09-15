package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * Zombie Run: a horde chases you at a pace you choose. Hold above it or get eaten.
 *
 * <p>The horde creeps faster every minute and surges every so often, so you cannot settle. A
 * safe house every 500 m makes them fall back - a rest interval you have to earn by reaching it.
 * The gap is the game: it sits huge in the middle and every stroke moves it.
 *
 * <p>3.19.2, on the rower's direction ("funny speak boxes"): the horde talks. Every few seconds a
 * zombie says something that fits the moment - lurching at the start, closing in, surging, sulking at
 * a safe house, gloating when it has you - and when you are well clear, you answer back. Figures are
 * drawn twice the size they first shipped, which on the tablet were barely readable.
 */
final class ZombieRunGame extends GameView {

    private enum Phase { READY, RUNNING, CAUGHT }

    private static final float START_GAP = 40f;
    private static final float MAX_GAP = 140f;
    private static final int SAFE_EVERY = 500;
    private static final double SAFE_SECONDS = 15;

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private float hordePaceSec = 150f;    // 2:30 /500
    private Phase phase = Phase.READY;
    private double gap;
    /** Seconds spent in the horde's grip; it ends the run only once this runs out. */
    private double grabbedSeconds;
    private static final double GRAB_LIMIT = 6.0;
    private double runStartMeters;
    private double runStartSeconds;
    private double nextSurgeAt;
    private double surgeUntil;
    private double safeUntil;
    private int nextSafeHouse = SAFE_EVERY;
    private double creep;
    private final Fx.Shake shake = new Fx.Shake();
    private final Fx.Particles dust = new Fx.Particles();
    private float dustAccum;
    private boolean caughtFx;

    /* Speech bubbles. Which figure speaks: 0-6 a zombie, -1 you. */
    private static final float FIGURE_SCALE = 2.0f;
    private static final String[] LINES_START = {"Is he... rowing?", "Lunch is getting away!", "Walk faster, Gary!", "Fresh legs!"};
    private static final String[] LINES_CHASE = {"BRAAAINS", "Mmm, cardio-flavoured", "We never skip leg day", "Wait up!", "Is that a rowing machine?"};
    private static final String[] LINES_CLOSE = {"Just a little nibble!", "Almost... there...", "I can smell the sweat!", "So close!", "Nom nom nom?"};
    private static final String[] LINES_SURGE = {"CARDIO DAY!", "CHAAARGE!", "Sprint intervals!", "Faster, team!"};
    private static final String[] LINES_SAFE = {"Aww, not the safe house", "We'll wait. We have time.", "No fair!", "Snack break over there?"};
    private static final String[] LINES_GRABBED = {"Hold still, snack!", "Gotcha!", "Dinner is served!", "Don't wriggle!"};
    private static final String[] LINES_FAR = {"Slow down!", "No fair, you have a boat!", "My legs fell off", "Are we there yet?", "I need a nap"};
    private static final String[] LINES_YOU = {"Not today!", "Catch me if you can!", "Row, row, row your... bye!", "Is that all you've got?"};
    private final java.util.Random chatter = new java.util.Random();
    private String bubble = "";
    private int bubbleWho;
    private double bubbleUntil;
    private double nextBubbleAt;
    private boolean wasSurging;
    private boolean wasSafe;
    private boolean wasGrabbed;

    ZombieRunGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    void setHordePace(float secondsPer500) {
        hordePaceSec = secondsPer500;
        phase = Phase.READY;
    }

    float hordePace() {
        return hordePaceSec;
    }

    @Override
    protected void onStart() {
        phase = Phase.READY;
        caughtFx = false;
        gap = START_GAP;
        grabbedSeconds = 0;
        nextSafeHouse = SAFE_EVERY;
        creep = 0;
        surgeUntil = 0;
        safeUntil = 0;
        bubbleUntil = 0;
        nextBubbleAt = 1.5;
        wasSurging = false;
        wasSafe = false;
        wasGrabbed = false;
    }

    /** Picks a line for the moment and who says it. Surges, safe houses and grabs speak at once. */
    private void chatter() {
        boolean surge = surging() && phase == Phase.RUNNING;
        boolean inSafe = safe();
        boolean grabbed = grabbedSeconds > 0;
        boolean event = (surge && !wasSurging) || (inSafe && !wasSafe) || (grabbed && !wasGrabbed);
        wasSurging = surge;
        wasSafe = inSafe;
        wasGrabbed = grabbed;
        if (!event && sessionSeconds < nextBubbleAt) {
            return;
        }
        String[] lines;
        int who = chatter.nextInt(7);
        if (phase == Phase.CAUGHT) {
            return;
        } else if (grabbed) {
            lines = LINES_GRABBED;
        } else if (surge) {
            lines = LINES_SURGE;
        } else if (inSafe) {
            lines = LINES_SAFE;
        } else if (phase == Phase.READY) {
            lines = LINES_START;
        } else if (gap < 14) {
            lines = LINES_CLOSE;
        } else if (gap > 60 && chatter.nextInt(3) == 0) {
            lines = LINES_YOU;
            who = -1;
        } else if (gap > 60) {
            lines = LINES_FAR;
        } else {
            lines = LINES_CHASE;
        }
        bubble = lines[chatter.nextInt(lines.length)];
        bubbleWho = who;
        bubbleUntil = sessionSeconds + 2.8;
        nextBubbleAt = sessionSeconds + 4.0 + chatter.nextDouble() * 3.0;
    }

    /** A comic speech bubble with a tail, pointing down at the speaker's head. */
    private void drawBubble(Canvas c, float headX, float headY, float w) {
        double left = bubbleUntil - sessionSeconds;
        if (left <= 0 || bubble.isEmpty()) {
            return;
        }
        float alpha = (float) Math.min(1.0, Math.min(left / 0.3, (2.8 - left) / 0.2 + 0.1));
        textPaint.setTextSize(dp(15f));
        float tw = textPaint.measureText(bubble);
        float padX = dp(12f);
        float bh = dp(34f);
        float bx = Math.max(dp(10f), Math.min(w - tw - padX * 2 - dp(10f), headX - tw / 2f - padX));
        float by = headY - dp(22f) - bh;
        paint.setColor(0xFFFFFFFF);
        paint.setAlpha((int) (235 * alpha));
        c.drawRoundRect(bx, by, bx + tw + padX * 2, by + bh, dp(14f), dp(14f), paint);
        path.reset();
        float tailX = Math.max(bx + dp(14f), Math.min(bx + tw + padX * 2 - dp(14f), headX));
        path.moveTo(tailX - dp(8f), by + bh - dp(1f));
        path.lineTo(tailX + dp(8f), by + bh - dp(1f));
        path.lineTo(headX, headY - dp(6f));
        path.close();
        c.drawPath(path, paint);
        paint.setAlpha(255);
        int textColour = bubbleWho < 0 ? 0xFF0E6E60 : 0xFF3A1010;
        bold(c, bubble, bx + padX + tw / 2f, by + bh * 0.66f, 15f,
                (((int) (255 * alpha)) << 24) | (textColour & 0x00FFFFFF), Paint.Align.CENTER);
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.RUNNING;
            runStartMeters = sessionMeters;
            runStartSeconds = sessionSeconds;
            nextSurgeAt = sessionSeconds + 40;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN && phase == Phase.CAUGHT) {
            start();
            return true;
        }
        return super.onTouchEvent(e);
    }

    private double runMeters() {
        return phase == Phase.READY ? 0 : sessionMeters - runStartMeters;
    }

    private boolean surging() {
        return sessionSeconds < surgeUntil;
    }

    private boolean safe() {
        return sessionSeconds < safeUntil;
    }

    /** Horde speed: base pace, +1.5% per minute, +30% in a surge, and stopped at a safe house. */
    private float hordeSpeed() {
        if (safe()) {
            return 0f;
        }
        float base = 500f / hordePaceSec;
        return base * (float) (1 + creep) * (surging() ? 1.3f : 1f);
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float speed = boat.value();

        if (phase == Phase.RUNNING) {
            creep = (sessionSeconds - runStartSeconds) / 60.0 * 0.015;
            if (!surging() && !safe() && sessionSeconds >= nextSurgeAt) {
                surgeUntil = sessionSeconds + 10;
                nextSurgeAt = sessionSeconds + 45 + Math.random() * 30;
            }
            if (runMeters() >= nextSafeHouse) {
                safeUntil = sessionSeconds + SAFE_SECONDS;
                nextSafeHouse += SAFE_EVERY;
            }
            gap += (speed - hordeSpeed()) * dt;
            gap = Math.min(MAX_GAP, gap);
            if (gap <= 0) {
                // Grabbed, not gone. Closing the gap used to end the run on the spot, so one bad
                // patch was unrecoverable and there was no way to row your way back out - which
                // is the opposite of what the chase is for. Out-row them and you break the grip;
                // only staying slower than the horde finishes it.
                gap = 0;
                if (speed > hordeSpeed()) {
                    grabbedSeconds = Math.max(0, grabbedSeconds - dt * 2.5);
                    gap = 2.0;              // a shove of daylight, so the escape reads on screen
                } else {
                    grabbedSeconds += dt;
                }
                if (grabbedSeconds >= GRAB_LIMIT) {
                    phase = Phase.CAUGHT;
                    bests.recordHighest("zombie." + Math.round(hordePaceSec), (float) runMeters());
                }
            } else {
                grabbedSeconds = 0;
            }
        }
        chatter();
        float danger = (float) Math.max(0, 1 - gap / 30.0);
        if (danger > 0.3f) {
            shake.kick(danger * dp(5f));
        }
        if (surging() && phase == Phase.RUNNING) {
            shake.kick(dp(1.5f));
        }
        shake.step(dt);
        dust.step(dt, dp(60f));

        // Sky reddens as they close in.
        int skyTop = blend(0xFF1B2A44, 0xFF5A1010, danger);
        paint.setShader(new LinearGradient(0, 0, 0, h * 0.7f, skyTop, 0xFF0A0E14, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        c.save();
        c.translate(shake.dx, shake.dy);
        float groundY = h * 0.70f;

        // Moon, low and large, redder as the horde closes.
        Fx.glow(c, w * 0.80f, h * 0.20f, dp(70f), blend(0x66DCEBF7, 0x66FF6A4D, danger));
        paint.setColor(blend(0xFFE9EEF5, 0xFFFF8A6A, danger));
        c.drawCircle(w * 0.80f, h * 0.20f, dp(26f), paint);
        float ppm = w / 90f;   // 90 m across the screen
        double scroll = sessionMeters;

        // Far hills, slow parallax.
        paint.setColor(0xFF141C2B);
        path.reset();
        path.moveTo(0, groundY);
        for (float x = 0; x <= w + dp(40f); x += dp(40f)) {
            float wx = (float) ((x + scroll * ppm * 0.15) / dp(160f));
            path.lineTo(x, groundY - dp(40f) - (float) Math.abs(Math.sin(wx) * dp(50f)));
        }
        path.lineTo(w, groundY);
        path.close();
        c.drawPath(path, paint);

        // Dead trees, mid parallax.
        paint.setColor(0xFF0E1420);
        float treeGap = dp(150f);
        float toff = (float) ((scroll * ppm * 0.45) % treeGap);
        for (float x = -toff; x < w + treeGap; x += treeGap) {
            float th = dp(60f) + ((int) (x / treeGap) % 3) * dp(18f);
            c.drawRect(x - dp(3f), groundY - th, x + dp(3f), groundY, paint);
            c.drawLine(x, groundY - th * 0.7f, x + dp(18f), groundY - th * 0.95f, paint);
            c.drawLine(x, groundY - th * 0.5f, x - dp(16f), groundY - th * 0.75f, paint);
        }

        // Ground with fence posts scrolling at your speed.
        paint.setColor(0xFF1A2416);
        c.drawRect(0, groundY, w, h, paint);
        paint.setColor(0xFF2A3A22);
        float post = dp(70f);
        float off = (float) ((scroll * ppm) % post);
        for (float x = -off; x < w; x += post) {
            c.drawRect(x, groundY - dp(18f), x + dp(4f), groundY, paint);
        }

        // Safe house ahead, if one is in view.
        float houseX = w * 0.62f + (float) (nextSafeHouse - runMeters()) * ppm;
        if (phase == Phase.RUNNING && houseX < w + dp(60f)) {
            paint.setColor(safe() ? ACCENT : 0xFF3B4A5E);
            c.drawRect(houseX - dp(22f), groundY - dp(46f), houseX + dp(22f), groundY, paint);
            paint.setColor(0xFF0A0E14);
            c.drawRect(houseX - dp(6f), groundY - dp(24f), houseX + dp(6f), groundY, paint);
            label(c, "SAFE HOUSE", houseX, groundY - dp(54f), 8f, safe() ? ACCENT : FAINT,
                    Paint.Align.CENTER);
        }

        // You, running, with a glow and dust off your heels.
        float youX = w * 0.62f;
        Fx.glow(c, youX, groundY - dp(20f), dp(48f), 0x4035D0BA);
        if (speed > 0.5f && phase == Phase.RUNNING) {
            dustAccum += dt * speed * 6f;
            while (dustAccum >= 1f) {
                dustAccum -= 1f;
                dust.spawn(youX - dp(6f), groundY, -dp(30f) - (float) Math.random() * dp(40f),
                        -dp(10f) - (float) Math.random() * dp(30f), 0.5f, dp(2.5f), 0xAA6B5A3A, true);
            }
        }
        dust.draw(c);
        drawRunner(c, youX, groundY, ACCENT, speed, (float) (scroll * 3.0), false);

        // The horde.
        float hordeX = youX - (float) gap * ppm;
        float speakerX = youX;
        for (int i = 0; i < 7; i++) {
            float zx = hordeX - i * dp(30f) - (i % 3) * dp(9f);
            if (i == bubbleWho) {
                speakerX = zx;
            }
            float bob = (float) Math.sin(sessionSeconds * 6 + i) * dp(2f);
            drawRunner(c, zx, groundY + bob, i % 2 == 0 ? BAD : 0xFF9A3B32, hordeSpeed(),
                    (float) (scroll * 2.2 + i * 40), true);
        }
        float headY = groundY - 46f * FIGURE_SCALE * dp(1f);
        drawBubble(c, bubbleWho < 0 ? youX : Math.max(dp(40f), speakerX), headY, w);
        if (phase == Phase.CAUGHT && !caughtFx) {
            caughtFx = true;
            dust.burst(youX, groundY - dp(24f), 40, dp(120f), 0.9f, dp(3.5f), 0xFFB3122E, true);
            shake.kick(dp(14f));
        }
        c.restore();
        Fx.speedLines(c, paint, w, h, speed, sessionSeconds, dp(1f));
        Fx.vignette(c, w, h, 0.35f + danger * 0.65f, danger > 0.2f ? 0x7A0A0A : 0x000000);

        // HUD.
        String big;
        int col;
        if (phase == Phase.READY) {
            big = "THEY'RE " + Math.round(START_GAP) + " M BACK";
            col = DIM;
        } else if (phase == Phase.CAUGHT) {
            big = "EATEN";
            col = BAD;
        } else if (grabbedSeconds > 0) {
            big = "GRABBED - PULL!";
            col = BAD;
        } else {
            big = Math.round(gap) + " m";
            col = gap > 30 ? ACCENT : gap > 12 ? WARN : BAD;
        }
        bold(c, big, w / 2f, h * 0.17f, phase == Phase.RUNNING ? 54f : 30f, col, Paint.Align.CENTER);
        String cap;
        if (phase == Phase.READY) {
            cap = "horde runs " + PersonalBests.formatPace(hordePaceSec) + " /500 - take a stroke";
        } else if (phase == Phase.CAUGHT) {
            cap = "survived " + Math.round(runMeters()) + " m  ·  tap to run again";
        } else if (safe()) {
            cap = "SAFE HOUSE - they fall back for " + Math.round(safeUntil - sessionSeconds) + "s";
        } else if (grabbedSeconds > 0) {
            cap = "out-row them to break free - " + Math.round(GRAB_LIMIT - grabbedSeconds) + "s";
        } else if (surging()) {
            cap = "THEY'RE SURGING - " + Math.round(surgeUntil - sessionSeconds) + "s";
        } else if (sessionSeconds > nextSurgeAt - 3) {
            cap = "SOMETHING'S STIRRING...";
        } else {
            cap = "LEAD  ·  next safe house in " + Math.round(nextSafeHouse - runMeters()) + " m";
        }
        bold(c, cap, w / 2f, h * 0.17f + dp(22f), 11f,
                surging() && phase == Phase.RUNNING ? BAD : safe() ? ACCENT : FAINT, Paint.Align.CENTER);

        bold(c, pace(speed), dp(16f), h * 0.15f, 22f, TEXT, Paint.Align.LEFT);
        label(c, "YOU /500", dp(16f), h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.LEFT);
        bold(c, hordeSpeed() > 0 ? PersonalBests.formatPace(500f / hordeSpeed()) : "--:--",
                w - dp(16f), h * 0.15f, 22f, BAD, Paint.Align.RIGHT);
        label(c, "HORDE /500", w - dp(16f), h * 0.15f + dp(16f), 9f, FAINT, Paint.Align.RIGHT);

        float fy = h - dp(12f);
        float col3 = w / 3f;
        stat(c, col3 * 0.5f, fy, Math.round(runMeters()) + " m", "SURVIVED");
        stat(c, col3 * 1.5f, fy, status == null ? "0" : status.strokeRate + " spm", "RATE");
        String key = "zombie." + Math.round(hordePaceSec);
        stat(c, col3 * 2.5f, fy, bests.has(key) ? Math.round(bests.get(key, 0)) + " m" : "--", "BEST");
    }

    /** A chunky figure with legs that swing with distance covered. */
    private void drawRunner(Canvas c, float x, float groundY, int color, float speed, float phaseIn,
                            boolean zombie) {
        float s = dp(FIGURE_SCALE);
        float legSwing = speed > 0.2f ? (float) Math.sin(phaseIn) * 8f * s : 0f;
        paint.setColor(color);
        // Legs.
        c.drawRect(x - 5 * s + legSwing, groundY - 14 * s, x - 1 * s + legSwing, groundY, paint);
        c.drawRect(x + 1 * s - legSwing, groundY - 14 * s, x + 5 * s - legSwing, groundY, paint);
        // Body, leaning into the run.
        float lean = zombie ? 4 * s : Math.min(1f, speed / 4f) * 5 * s;
        c.drawRect(x - 6 * s + lean, groundY - 34 * s, x + 6 * s + lean, groundY - 14 * s, paint);
        // Arms: zombies reach forward.
        if (zombie) {
            c.drawRect(x + 6 * s + lean, groundY - 30 * s, x + 18 * s + lean, groundY - 26 * s, paint);
        } else {
            float arm = (float) Math.sin(phaseIn) * 6f * s;
            c.drawRect(x + 6 * s + lean, groundY - 30 * s + arm, x + 12 * s + lean, groundY - 26 * s + arm, paint);
        }
        // Head.
        paint.setColor(zombie ? 0xFF7FB37A : 0xFFE6EDF7);
        c.drawRect(x - 5 * s + lean, groundY - 46 * s, x + 5 * s + lean, groundY - 36 * s, paint);
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
