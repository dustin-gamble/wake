package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;

/**
 * RIVER EXPLORER: row up a river that never ends, seen over your own bow, with a map that fills in.
 *
 * <p>The rower asked for "a game which simulates you actually rowing, first person view, with a mini
 * map, match row with speed". The river is generated, but deterministically: every stretch is
 * seeded by the choices that led to it, so the same branch is the same river next week. Every
 * {@link #SEGS_PER_FORK} stretches of {@link #SEG_LEN} metres it forks - tap a side (or lean the
 * handle sensor) to choose, and an unexplored branch is picked by default so rowing on discovers.
 *
 * <p>Persistent: the route taken, how far along it you are, and every stretch ever explored (drawn
 * on the minimap) are kept, so the journey spans sessions. Replaces the Journey card.
 *
 * <p>The view is a classic scanline projection: each horizontal strip of the screen is a distance
 * ahead, the river's centre at that distance comes from integrating the curvature, and banks, trees
 * and landmarks are placed on that frame. No 3D engine, so it runs on the tablet's Canvas.
 */
final class RiverExplorerGame extends GameView {

    static final float SEG_LEN = 400f;
    static final int SEGS_PER_FORK = 3;
    private static final float HALF_WIDTH = 15f;
    private static final int LOOK = 300;
    private static final float CAM_H = 2.4f;
    private static final float TREE_STEP = 16f;
    private static final int MAX_EXPLORED = 1500;
    private static final float FORK_ANGLE = 0.45f;

    private static final String[] LANDMARKS = {
            "HERON", "STONE BRIDGE", "WATERFALL", "LIGHTHOUSE", "WINDMILL", "OTTERS", "BOATHOUSE", "OLD MILL"};
    private static final String[] NAME_A = {
            "Heron", "Willow", "Mill", "Kingfisher", "Otter", "Alder", "Reed", "Swan", "Fern", "Bramble", "Lantern", "Salmon"};
    private static final String[] NAME_B = {
            "Reach", "Bend", "Run", "Cut", "Water", "Narrows", "Pool", "Stream", "Channel", "Race"};

    /** One stretch of river: where it starts, how it bends, and what stands beside it. */
    private static final class Seg {
        final String prefix;
        final int index;
        final long seed;
        final float h0;
        final float curvA;
        final float curvB;
        final int landmark;
        final float landmarkAt;
        final boolean landmarkLeft;
        final float[] xs = new float[21];
        final float[] ys = new float[21];
        float h1;

        Seg(String prefix, int index, long seed, float x0, float y0, float h0) {
            this.prefix = prefix;
            this.index = index;
            this.seed = seed;
            this.h0 = h0;
            java.util.Random r = new java.util.Random(seed);
            curvA = (r.nextFloat() - 0.5f) * 0.005f;
            curvB = (r.nextFloat() - 0.5f) * 0.004f;
            landmark = r.nextFloat() < 0.55f ? r.nextInt(LANDMARKS.length) : -1;
            landmarkAt = 80f + r.nextFloat() * (SEG_LEN - 160f);
            landmarkLeft = r.nextBoolean();
            float x = x0;
            float y = y0;
            float h = h0;
            xs[0] = x;
            ys[0] = y;
            for (int i = 1; i <= 20; i++) {
                float s = (i - 0.5f) * 20f;
                h += curvature(s) * 20f;
                x += (float) Math.sin(h) * 20f;
                y += (float) Math.cos(h) * 20f;
                xs[i] = x;
                ys[i] = y;
            }
            h1 = h;
        }

        float curvature(float s) {
            return curvA * (float) Math.sin(Math.PI * s / SEG_LEN) + curvB * (float) Math.sin(2 * Math.PI * s / SEG_LEN);
        }
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final java.util.HashMap<String, Seg> cache = new java.util.HashMap<>();
    private final java.util.LinkedHashSet<String> explored = new java.util.LinkedHashSet<>();
    private final float[] offsets = new float[LOOK + 2];
    private final float[] angles = new float[LOOK + 2];

    private String route = "";
    private double along;
    private int landmarksFound;
    private char pendingChoice;
    private float lateral;
    private float oarPhase = 2f;
    private PulseMeter.Stroke lastStrokeSeen;
    private double lastMeters = -1;
    private String banner = "";
    private double bannerUntil;
    private double lastSaveAt;
    private boolean started;

    private Bitmap map;
    private int mapExploredCount = -1;
    private float mapMinX;
    private float mapMaxX;
    private float mapMinY;
    private float mapMaxY;
    private float mapScale;
    private int mapW;
    private int mapH;

    RiverExplorerGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
    }

    @Override
    protected void onStart() {
        String saved = bests.getString("river.route");
        route = saved == null ? "" : saved;
        along = bests.get("river.along", 0f);
        landmarksFound = Math.round(bests.get("river.landmarks", 0f));
        explored.clear();
        String keys = bests.getString("river.explored");
        if (keys != null && !keys.isEmpty()) {
            for (String k : keys.split(",")) {
                if (!k.isEmpty()) {
                    explored.add(k);
                }
            }
        }
        // A saved position beyond the choices made (an older save) is pulled back to the last fork.
        int maxIndex = (route.length() + 1) * SEGS_PER_FORK - 1;
        along = Math.min(along, (maxIndex + 1) * SEG_LEN - 1);
        pendingChoice = 0;
        lastMeters = -1;
        started = false;
        mapExploredCount = -1;
        markExplored(segIndex(along));
    }

    @Override
    protected void onStop() {
        save();
    }

    private void save() {
        bests.putString("river.route", route);
        bests.putFloat("river.along", (float) along);
        StringBuilder sb = new StringBuilder();
        for (String k : explored) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k);
        }
        bests.putString("river.explored", sb.toString());
        bests.putFloat("river.landmarks", landmarksFound);
        bests.recordHighest("river.km", explored.size() * SEG_LEN / 1000f);
    }

    /* ---------- the river as data ---------- */

    private static int segIndex(double s) {
        return (int) Math.floor(Math.max(0, s) / SEG_LEN);
    }

    /** The fork choices governing a stretch, using the pending choice for the next fork. */
    private String prefixFor(int index) {
        int forks = index / SEGS_PER_FORK;
        StringBuilder p = new StringBuilder(route.length() >= forks ? route.substring(0, forks) : route);
        while (p.length() < forks) {
            p.append(p.length() == route.length() ? choiceForNextFork() : 'L');
        }
        return p.toString();
    }

    private static long seedFor(String prefix, int index) {
        long h = 1125899906842597L;
        for (int i = 0; i < prefix.length(); i++) {
            h = 31 * h + prefix.charAt(i);
        }
        h = 31 * h + index;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return h;
    }

    private Seg geom(String prefix, int index) {
        String key = prefix + ":" + index;
        Seg s = cache.get(key);
        if (s != null) {
            return s;
        }
        if (index == 0) {
            s = new Seg(prefix, 0, seedFor(prefix, 0), 0f, 0f, 0f);
        } else if (index % SEGS_PER_FORK == 0) {
            Seg parent = geom(prefix.substring(0, prefix.length() - 1), index - 1);
            float turn = prefix.charAt(prefix.length() - 1) == 'L' ? -FORK_ANGLE : FORK_ANGLE;
            s = new Seg(prefix, index, seedFor(prefix, index), parent.xs[20], parent.ys[20], parent.h1 + turn);
        } else {
            Seg parent = geom(prefix, index - 1);
            s = new Seg(prefix, index, seedFor(prefix, index), parent.xs[20], parent.ys[20], parent.h1);
        }
        if (cache.size() > 4000) {
            cache.clear();
        }
        cache.put(key, s);
        return s;
    }

    /** Default for the next fork: a branch not yet explored, else keep left. */
    private char choiceForNextFork() {
        if (pendingChoice != 0) {
            return pendingChoice;
        }
        int nextIndex = (route.length() + 1) * SEGS_PER_FORK;
        String left = route + "L:" + nextIndex;
        String right = route + "R:" + nextIndex;
        if (!explored.contains(left)) {
            return 'L';
        }
        if (!explored.contains(right)) {
            return 'R';
        }
        return 'L';
    }

    private String reachName(String prefix, int index) {
        long seed = seedFor(prefix, (index / SEGS_PER_FORK) * SEGS_PER_FORK);
        int a = (int) ((seed >>> 8) & 0x7fffffff) % NAME_A.length;
        int b = (int) ((seed >>> 24) & 0x7fffffff) % NAME_B.length;
        return NAME_A[a] + " " + NAME_B[b];
    }

    /** @return true when this stretch had never been rowed before */
    private boolean markExplored(int index) {
        String key = prefixFor(index) + ":" + index;
        if (explored.contains(key)) {
            return false;
        }
        if (explored.size() >= MAX_EXPLORED) {
            java.util.Iterator<String> it = explored.iterator();
            it.next();
            it.remove();
        }
        explored.add(key);
        return true;
    }

    /* ---------- live ---------- */

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (!started && driving && boat.value() > 0.3f) {
            started = true;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastStrokeSeen) {
            lastStrokeSeen = stroke;
            oarPhase = 0f;
        }
    }

    @Override
    protected void onStroke(int watts) {
        if (status != null && status.meter.strokes == 0) {
            oarPhase = 0f;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP && distanceToFork() < 450f) {
            pendingChoice = e.getX() < getWidth() / 2f ? 'L' : 'R';
            postInvalidateOnAnimation();
        }
        super.onTouchEvent(e);
        return true;
    }

    private double distanceToFork() {
        double forkAt = (route.length() + 1) * SEGS_PER_FORK * SEG_LEN;
        return forkAt - along;
    }

    private void advance(float dt) {
        if (lastMeters < 0) {
            lastMeters = sessionMeters;
        }
        double moved = Math.max(0, sessionMeters - lastMeters);
        lastMeters = sessionMeters;
        if (!started || moved <= 0) {
            return;
        }
        int before = segIndex(along);
        along += moved;
        int after = segIndex(along);
        for (int idx = before + 1; idx <= after; idx++) {
            if (idx % SEGS_PER_FORK == 0 && idx / SEGS_PER_FORK > route.length()) {
                char choice = choiceForNextFork();
                route += choice;
                pendingChoice = 0;
                showBanner("ENTERING " + reachName(route, idx).toUpperCase(java.util.Locale.US));
            }
            markExplored(idx);
        }
        // Landmarks are found the first time a stretch is rowed past them.
        int idx = segIndex(along);
        Seg seg = geom(prefixFor(idx), idx);
        double inSeg = along - idx * SEG_LEN;
        String foundKey = "river.found." + prefixFor(idx) + ":" + idx;
        if (seg.landmark >= 0 && inSeg >= seg.landmarkAt && inSeg - moved < seg.landmarkAt
                && bests.getString(foundKey) == null) {
            bests.putString(foundKey, "1");
            landmarksFound++;
            showBanner("DISCOVERED  " + LANDMARKS[seg.landmark] + "  ·  " + reachName(prefixFor(idx), idx).toUpperCase(java.util.Locale.US));
            fx.burst(getWidth() * 0.5f, getHeight() * 0.45f, 40, dp(220f), 1.0f, dp(3.5f), 0xFFF5C518, true);
        }
        if (sessionSeconds - lastSaveAt > 10) {
            lastSaveAt = sessionSeconds;
            save();
        }
    }

    private void showBanner(String text) {
        banner = text;
        bannerUntil = sessionSeconds + 4;
    }

    /* ---------- drawing ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        advance(dt);
        fx.step(dt, dp(300f));
        float speed = boat.value();

        // Curvature ahead, integrated to a centre-line offset relative to where the boat points.
        // The view spans at most two stretches (LOOK < SEG_LEN), so look each up once per frame. The
        // first version called curvatureAt() for every metre - 300 string keys and map lookups a
        // frame, thousands of short-lived objects a second on a tablet that stutters on garbage.
        angles[0] = 0f;
        offsets[0] = 0f;
        int firstIdx = segIndex(along);
        Seg near = geom(prefixFor(firstIdx), firstIdx);
        Seg far = geom(prefixFor(firstIdx + 1), firstIdx + 1);
        double nearEnd = (firstIdx + 1) * SEG_LEN;
        for (int i = 0; i <= LOOK; i++) {
            double s = along + i;
            float k = s < nearEnd ? near.curvature((float) (s - firstIdx * SEG_LEN))
                    : far.curvature((float) (s - nearEnd));
            angles[i + 1] = angles[i] + k;
            offsets[i + 1] = offsets[i] + (float) Math.sin(angles[i]);
        }
        if (hasSteering()) {
            lateral += steering() * 7f * dt;
        } else {
            lateral += (0f - lateral) * Math.min(1f, dt);
        }
        lateral = Math.max(-HALF_WIDTH * 0.7f, Math.min(HALF_WIDTH * 0.7f, lateral));
        float yaw = angles[25] * 0.6f;

        float horizon = h * 0.40f;
        float focal = h * 0.9f;
        int idxNow = segIndex(along);
        Seg segNow = geom(prefixFor(idxNow), idxNow);
        // Heading of this stretch where it began: enough to slide the far hills as the river turns.
        float worldHeading = segNow.h0;

        // Sky and far hills, which slide with the river's heading.
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(new LinearGradient(0, 0, 0, horizon, 0xFF3A6EA5, 0xFFF2C39A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, horizon + 1, paint);
        paint.setShader(null);
        Fx.glow(c, w * 0.7f, horizon - dp(30f), dp(90f), 0x55FFE3B0);
        float hillShift = (float) ((worldHeading + yaw) * w * 0.8f);
        for (int layer = 0; layer < 2; layer++) {
            paint.setColor(layer == 0 ? 0xFF6E86A0 : 0xFF4E6B5C);
            path.reset();
            path.moveTo(0, horizon + 1);
            float step = dp(70f);
            float shift = ((hillShift * (layer == 0 ? 0.4f : 0.8f)) % (step * 8) + step * 8) % (step * 8);
            for (float x = -step * 8; x <= w + step * 8; x += step) {
                float px = x + shift;
                int k = (int) Math.floor((x) / step);
                float peak = horizon - dp(18f + ((k * 7919 + layer * 131) % 7 + 7) % 7 * 6f) - layer * dp(4f);
                path.lineTo(px, peak);
            }
            path.lineTo(w, horizon + 1);
            path.close();
            c.drawPath(path, paint);
        }

        drawSkyLife(c, w, horizon, hillShift);

        // Ground: one strip per few pixels, each a distance ahead.
        double forkDist = distanceToFork();
        char choice = choiceForNextFork();
        for (float y = h; y > horizon + 2; y -= dp(3f)) {
            float z = CAM_H * focal / (y - horizon);
            if (z > LOOK) {
                break;
            }
            int zi = (int) z;
            float fog = Math.min(1f, z / LOOK);
            float cx = w / 2f + (offsets[zi] - lateral - yaw * z) * focal / z;
            float half = HALF_WIDTH * focal / z;
            boolean grassBand = ((int) ((along + z) / 10)) % 2 == 0;
            paint.setColor(blend(grassBand ? 0xFF4F8A3C : 0xFF487F36, 0xFFB9C7B0, fog * 0.7f));
            c.drawRect(0, y - dp(3f), w, y + 1, paint);
            boolean ripple = ((int) ((along + z) / 4)) % 2 == 0;
            int water = blend(ripple ? 0xFF2F6E93 : 0xFF2A6488, 0xFFB9C7B0, fog * 0.6f);
            if (forkDist < LOOK && z > forkDist) {
                float spread = (float) (z - forkDist) * FORK_ANGLE * focal / z;
                for (int side = -1; side <= 1; side += 2) {
                    boolean chosen = (side < 0) == (choice == 'L');
                    paint.setColor(chosen ? water : blend(water, 0xFF1A2A33, 0.35f));
                    c.drawRect(cx + side * spread - half * 0.8f, y - dp(3f), cx + side * spread + half * 0.8f, y + 1, paint);
                }
            } else {
                paint.setColor(water);
                c.drawRect(cx - half, y - dp(3f), cx + half, y + 1, paint);
            }
            paint.setColor(blend(0xFF8A7A55, 0xFFB9C7B0, fog * 0.7f));
            c.drawRect(cx - half - half * 0.08f, y - dp(3f), cx - half, y + 1, paint);
            c.drawRect(cx + half, y - dp(3f), cx + half + half * 0.08f, y + 1, paint);
        }

        // Trees along both banks, far to near, and landmarks where they stand.
        long firstTree = (long) Math.ceil((along + 3) / TREE_STEP);
        long lastTree = (long) Math.floor((along + LOOK - 1) / TREE_STEP);
        for (long n = lastTree; n >= firstTree; n--) {
            float z = (float) (n * TREE_STEP - along);
            int zi = Math.max(0, Math.min(LOOK, (int) z));
            long hsh = n * 2654435761L;
            boolean left = ((hsh >>> 7) & 1) == 0;
            float out = HALF_WIDTH + 5f + ((hsh >>> 12) & 15);
            float xw = offsets[zi] + (left ? -out : out);
            float sx = w / 2f + (xw - lateral - yaw * z) * focal / z;
            float sy = horizon + CAM_H * focal / z;
            float size = 9f * focal / z;
            float fog = Math.min(1f, z / LOOK);
            paint.setColor(blend(0xFF5A3E2B, 0xFFB9C7B0, fog * 0.7f));
            c.drawRect(sx - size * 0.06f, sy - size * 0.5f, sx + size * 0.06f, sy, paint);
            paint.setColor(blend(((hsh >>> 20) & 1) == 0 ? 0xFF2E5E2A : 0xFF3C7033, 0xFFB9C7B0, fog * 0.7f));
            c.drawCircle(sx, sy - size * 0.62f, size * 0.32f, paint);
        }
        drawLandmarks(c, w, horizon, focal);

        drawBow(c, w, h, speed, dt);
        fx.draw(c);
        drawMinimap(c, w, h);
        drawHud(c, w, h, speed, forkDist, choice);
    }

    /**
     * 3.19.5: a still sky read as a painting. Clouds drift and slide with the river's heading, a
     * flock crosses, and a hot-air balloon hangs over the far hills.
     */
    private void drawSkyLife(Canvas c, float w, float horizon, float hillShift) {
        double t = sessionSeconds;
        float span = w + dp(500f);
        for (int i = 0; i < 5; i++) {
            float cx = (float) ((((i * 523 + hillShift * 0.25f + t * dp(5f + i)) % span) + span) % span) - dp(250f);
            float cy = horizon * (0.18f + (i % 3) * 0.17f);
            float sc = 0.6f + (i % 3) * 0.3f;
            paint.setColor(0xCCFFFFFF);
            c.drawOval(cx - dp(80f) * sc, cy - dp(12f) * sc, cx + dp(80f) * sc, cy + dp(12f) * sc, paint);
            c.drawOval(cx - dp(36f) * sc, cy - dp(28f) * sc, cx + dp(40f) * sc, cy + dp(4f) * sc, paint);
        }
        float bx = (float) ((((w * 0.3f + hillShift * 0.3f + t * dp(4f)) % span) + span) % span) - dp(250f);
        float by = horizon * 0.42f + (float) Math.sin(t * 0.4) * dp(8f);
        paint.setColor(0xFFE8573C);
        c.drawOval(bx - dp(16f), by - dp(20f), bx + dp(16f), by + dp(16f), paint);
        paint.setColor(0xFFF5C518);
        c.drawRect(bx - dp(4f), by - dp(20f), bx + dp(4f), by + dp(16f), paint);
        paint.setColor(0xFF6B4A2B);
        c.drawRect(bx - dp(4f), by + dp(22f), bx + dp(4f), by + dp(28f), paint);
        paint.setStrokeWidth(dp(1f));
        c.drawLine(bx - dp(10f), by + dp(12f), bx - dp(4f), by + dp(22f), paint);
        c.drawLine(bx + dp(10f), by + dp(12f), bx + dp(4f), by + dp(22f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xAA2A3340);
        float fx = (float) (w - ((t * dp(35f)) % (w + dp(300f))));
        for (int b = 0; b < 5; b++) {
            float x0 = fx + b * dp(24f);
            float y0 = horizon * 0.30f + (b % 2) * dp(10f);
            float flap = (float) Math.sin(t * 8 + b) * dp(4f);
            c.drawLine(x0 - dp(7f), y0 - flap, x0, y0, paint);
            c.drawLine(x0, y0, x0 + dp(7f), y0 - flap, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLandmarks(Canvas c, float w, float horizon, float focal) {
        for (int k = 1; k >= 0; k--) {
            int idx = segIndex(along) + k;
            Seg seg = geom(prefixFor(idx), idx);
            if (seg.landmark < 0) {
                continue;
            }
            float z = (float) (idx * SEG_LEN + seg.landmarkAt - along);
            if (z < 6f || z > LOOK) {
                continue;
            }
            int zi = (int) z;
            float side = seg.landmarkLeft ? -1f : 1f;
            float baseX = w / 2f + (offsets[zi] - lateral - (angles[25] * 0.6f) * z) * focal / z;
            float sy = horizon + CAM_H * focal / z;
            float u = focal / z;   // pixels per metre at that distance
            float bank = baseX + side * (HALF_WIDTH + 4f) * u;
            switch (seg.landmark) {
                case 0: // heron
                    paint.setColor(0xFFDDE3E8);
                    c.drawOval(bank - 0.6f * u, sy - 1.6f * u, bank + 0.6f * u, sy - 0.8f * u, paint);
                    paint.setStrokeWidth(Math.max(1f, 0.08f * u));
                    c.drawLine(bank, sy - 1.5f * u, bank + 0.3f * u, sy - 2.4f * u, paint);
                    c.drawLine(bank - 0.1f * u, sy - 0.8f * u, bank - 0.1f * u, sy, paint);
                    break;
                case 1: // stone bridge across the river
                    paint.setColor(0xFF8C8577);
                    float half = (HALF_WIDTH + 3f) * u;
                    c.drawRect(baseX - half, sy - 5f * u, baseX + half, sy - 3.8f * u, paint);
                    paint.setColor(0xFF6E685C);
                    c.drawRect(baseX - half, sy - 3.8f * u, baseX - half + 2f * u, sy, paint);
                    c.drawRect(baseX + half - 2f * u, sy - 3.8f * u, baseX + half, sy, paint);
                    break;
                case 2: // waterfall on the bank
                    paint.setColor(0xFF6B6F66);
                    c.drawRect(bank - 3f * u, sy - 7f * u, bank + 3f * u, sy, paint);
                    paint.setColor(0xEEFFFFFF);
                    c.drawRect(bank - 1.6f * u, sy - 7f * u, bank + 1.6f * u, sy, paint);
                    break;
                case 3: // lighthouse
                    paint.setColor(0xFFF2F2F2);
                    c.drawRect(bank - 0.9f * u, sy - 12f * u, bank + 0.9f * u, sy, paint);
                    paint.setColor(0xFFD8453C);
                    for (int b = 1; b < 12; b += 3) {
                        c.drawRect(bank - 0.9f * u, sy - (b + 1.5f) * u, bank + 0.9f * u, sy - b * u, paint);
                    }
                    Fx.glow(c, bank, sy - 12.5f * u, 3f * u, 0x88FFF2A0);
                    break;
                case 4: // windmill
                    paint.setColor(0xFFB8A58A);
                    c.drawRect(bank - 1.2f * u, sy - 7f * u, bank + 1.2f * u, sy, paint);
                    paint.setColor(0xFF5A4B3A);
                    paint.setStrokeWidth(Math.max(1f, 0.3f * u));
                    double rot = sessionSeconds * 1.2;
                    for (int b = 0; b < 4; b++) {
                        double a = rot + b * Math.PI / 2;
                        c.drawLine(bank, sy - 7f * u, bank + (float) Math.cos(a) * 5f * u, sy - 7f * u + (float) Math.sin(a) * 5f * u, paint);
                    }
                    break;
                case 5: // otters in the water
                    paint.setColor(0xFF5C4430);
                    for (int o = 0; o < 3; o++) {
                        float ox = baseX + side * (HALF_WIDTH * 0.5f + o * 1.3f) * u;
                        float bob = (float) Math.sin(sessionSeconds * 3 + o) * 0.15f * u;
                        c.drawOval(ox - 0.5f * u, sy - 0.4f * u + bob, ox + 0.5f * u, sy + 0.1f * u + bob, paint);
                    }
                    break;
                case 6: // boathouse
                    paint.setColor(0xFF7A5230);
                    c.drawRect(bank - 4f * u, sy - 4f * u, bank + 4f * u, sy, paint);
                    paint.setColor(0xFF4A2F1B);
                    path.reset();
                    path.moveTo(bank - 4.5f * u, sy - 4f * u);
                    path.lineTo(bank, sy - 6.5f * u);
                    path.lineTo(bank + 4.5f * u, sy - 4f * u);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                default: // old mill with a wheel
                    paint.setColor(0xFFB09B7C);
                    c.drawRect(bank - 3f * u, sy - 5f * u, bank + 3f * u, sy, paint);
                    paint.setColor(0xFF4A3A2A);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(Math.max(1f, 0.25f * u));
                    c.drawCircle(bank - side * 3.6f * u, sy - 1.8f * u, 1.8f * u, paint);
                    paint.setStyle(Paint.Style.FILL);
                    break;
            }
        }
    }

    /** The bow and two oars, sweeping back on the drive and forward on the recovery. */
    private void drawBow(Canvas c, float w, float h, float speed, float dt) {
        oarPhase = Math.min(2f, oarPhase + dt / (oarPhase < 1f ? 0.8f : 1.6f));
        float cx = w / 2f;
        float deckTop = h * 0.80f;
        paint.setColor(0xFF8B5A2B);
        path.reset();
        path.moveTo(cx, deckTop);
        path.lineTo(cx + w * 0.16f, h);
        path.lineTo(cx - w * 0.16f, h);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(0xFFB07A45);
        path.reset();
        path.moveTo(cx, deckTop + dp(8f));
        path.lineTo(cx + w * 0.11f, h);
        path.lineTo(cx - w * 0.11f, h);
        path.close();
        c.drawPath(path, paint);
        // Oar sweep: 0 at the catch (blades forward), 1 at the finish (blades back).
        float sweep = oarPhase < 1f ? oarPhase : 2f - oarPhase;
        boolean inWater = oarPhase < 1f;
        for (int side = -1; side <= 1; side += 2) {
            float pivotX = cx + side * w * 0.14f;
            float pivotY = h * 0.93f;
            float angle = (float) Math.toRadians(-60 + sweep * 50);
            float bladeX = pivotX + side * (float) Math.cos(angle) * w * 0.3f;
            float bladeY = pivotY + (float) Math.sin(angle) * h * 0.12f + (inWater ? dp(10f) : -dp(14f));
            paint.setColor(0xFFE0C9A0);
            paint.setStrokeWidth(dp(6f));
            c.drawLine(pivotX, pivotY, bladeX, bladeY, paint);
            paint.setColor(0xFFF2F2F2);
            c.drawOval(bladeX - dp(22f), bladeY - dp(9f), bladeX + dp(22f), bladeY + dp(9f), paint);
            if (inWater && oarPhase < 0.1f && speed > 0.5f) {
                fx.burst(bladeX, bladeY, 8, dp(90f), 0.4f, dp(3f), 0xCCBFE3FF, true);
            }
        }
    }

    private void drawMinimap(Canvas c, float w, float h) {
        int mw = (int) Math.min(w * 0.24f, dp(300f));
        int mh = (int) Math.min(h * 0.34f, dp(240f));
        float left = w - mw - dp(12f);
        float top = dp(12f);
        if (map == null || map.getWidth() != mw || map.getHeight() != mh || mapExploredCount != explored.size()) {
            rebuildMap(mw, mh);
        }
        c.drawBitmap(map, left, top, null);
        // You: a dot and a heading tick.
        int idx = segIndex(along);
        Seg seg = geom(prefixFor(idx), idx);
        float f = (float) ((along - idx * SEG_LEN) / 20f);
        int i0 = Math.max(0, Math.min(19, (int) f));
        float t = Math.max(0f, Math.min(1f, f - i0));
        float wx = seg.xs[i0] + (seg.xs[i0 + 1] - seg.xs[i0]) * t;
        float wy = seg.ys[i0] + (seg.ys[i0 + 1] - seg.ys[i0]) * t;
        float px = left + dp(8f) + (wx - mapMinX) * mapScale;
        float py = top + mh - dp(8f) - (wy - mapMinY) * mapScale;
        Fx.glow(c, px, py, dp(14f), 0x8835D0BA);
        paint.setColor(ACCENT);
        c.drawCircle(px, py, dp(4.5f), paint);
        label(c, "MAP  ·  " + String.format(java.util.Locale.US, "%.1f km explored", explored.size() * SEG_LEN / 1000f),
                left + dp(8f), top + mh + dp(14f), 9f, FAINT, Paint.Align.LEFT);
    }

    private void rebuildMap(int mw, int mh) {
        if (map == null || map.getWidth() != mw || map.getHeight() != mh) {
            map = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888);
        }
        mapExploredCount = explored.size();
        Canvas mc = new Canvas(map);
        mc.drawColor(0xCC0A121C);
        java.util.List<Seg> segs = new java.util.ArrayList<>();
        mapMinX = Float.MAX_VALUE;
        mapMaxX = -Float.MAX_VALUE;
        mapMinY = Float.MAX_VALUE;
        mapMaxY = -Float.MAX_VALUE;
        for (String key : explored) {
            int colon = key.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            try {
                Seg s = geom(key.substring(0, colon), Integer.parseInt(key.substring(colon + 1)));
                segs.add(s);
                for (int i = 0; i <= 20; i += 5) {
                    mapMinX = Math.min(mapMinX, s.xs[i]);
                    mapMaxX = Math.max(mapMaxX, s.xs[i]);
                    mapMinY = Math.min(mapMinY, s.ys[i]);
                    mapMaxY = Math.max(mapMaxY, s.ys[i]);
                }
            } catch (NumberFormatException ignored) {
                // a malformed key from an older save is skipped
            }
        }
        if (segs.isEmpty()) {
            mapMinX = -500;
            mapMaxX = 500;
            mapMinY = -100;
            mapMaxY = 900;
        }
        float spanX = Math.max(800f, mapMaxX - mapMinX);
        float spanY = Math.max(800f, mapMaxY - mapMinY);
        mapScale = Math.min((mw - dp(16f)) / spanX, (mh - dp(16f)) / spanY);
        mapMinX -= (spanX - (mapMaxX - mapMinX)) / 2f;
        mapMinY -= (spanY - (mapMaxY - mapMinY)) / 2f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(2.5f));
        p.setColor(0xFF5AA7D6);
        for (Seg s : segs) {
            for (int i = 0; i < 20; i++) {
                mc.drawLine(dp(8f) + (s.xs[i] - mapMinX) * mapScale, mh - dp(8f) - (s.ys[i] - mapMinY) * mapScale,
                        dp(8f) + (s.xs[i + 1] - mapMinX) * mapScale, mh - dp(8f) - (s.ys[i + 1] - mapMinY) * mapScale, p);
            }
            if (s.landmark >= 0 && bests.getString("river.found." + s.prefix + ":" + s.index) != null) {
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setColor(0xFFF5C518);
                int k = (int) (s.landmarkAt / 20f);
                mc.drawCircle(dp(8f) + (s.xs[k] - mapMinX) * mapScale, mh - dp(8f) - (s.ys[k] - mapMinY) * mapScale, dp(3f), dot);
            }
        }
    }

    private void drawHud(Canvas c, float w, float h, float speed, double forkDist, char choice) {
        int idx = segIndex(along);
        String reach = reachName(prefixFor(idx), idx);
        bold(c, reach.toUpperCase(java.util.Locale.US), dp(18f), dp(36f), 22f, TEXT, Paint.Align.LEFT);
        label(c, String.format(java.util.Locale.US, "%.2f km up the river  ·  %d landmarks found  ·  %s /500",
                along / 1000.0, landmarksFound, pace(speed)), dp(18f), dp(54f), 10f, DIM, Paint.Align.LEFT);
        if (!started) {
            bold(c, "ROW TO SET OFF", w / 2f, h * 0.30f, 26f, ACCENT, Paint.Align.CENTER);
        }
        if (forkDist < 450f) {
            int nextIndex = (route.length() + 1) * SEGS_PER_FORK;
            String leftName = reachName(route + "L", nextIndex);
            String rightName = reachName(route + "R", nextIndex);
            boolean leftNew = !explored.contains(route + "L:" + nextIndex);
            boolean rightNew = !explored.contains(route + "R:" + nextIndex);
            float y = h * 0.20f;
            bold(c, "FORK IN " + Math.max(0, Math.round(forkDist)) + " m  ·  TAP A SIDE" + (hasSteering() ? " OR LEAN" : ""),
                    w / 2f, y, 14f, WARN, Paint.Align.CENTER);
            bold(c, "◀  " + leftName.toUpperCase(java.util.Locale.US) + (leftNew ? "  (NEW)" : ""), w * 0.30f, y + dp(26f), 15f,
                    choice == 'L' ? ACCENT : FAINT, Paint.Align.CENTER);
            bold(c, rightName.toUpperCase(java.util.Locale.US) + (rightNew ? "  (NEW)" : "") + "  ▶", w * 0.62f, y + dp(26f), 15f,
                    choice == 'R' ? ACCENT : FAINT, Paint.Align.CENTER);
            if (hasSteering() && Math.abs(steering()) > 0.6f) {
                pendingChoice = steering() < 0 ? 'L' : 'R';
            }
        }
        if (sessionSeconds < bannerUntil) {
            bold(c, banner, w / 2f, h * 0.36f, 20f, 0xFFF5C518, Paint.Align.CENTER);
        }
    }

    private static int blend(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
