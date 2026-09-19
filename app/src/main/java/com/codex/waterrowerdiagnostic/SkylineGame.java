package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;

import java.io.File;
import java.io.FileOutputStream;

/**
 * Skyline: you row, a city gets built.
 *
 * <p>Power buys concrete; when enough has accumulated a block is craned in over the lowest plot
 * and falls, at a speed set by how hard you are pulling. Blocks stack into towers, towers gain
 * windows that light as evening comes in, and the whole thing is drawn in isometric with the
 * camera panning slowly around it so you see the skyline from every side.
 *
 * <p>No fail state on purpose - this is the one to row to when a chase would be exhausting. The
 * city is persistent: every block you have ever placed is still standing.
 *
 * <p>3.15.0, the block factory: each drive fills the crane's hopper, and a strong stroke - 10% or
 * 30% above the rower's typical power - drops a two- or three-floor block at a discount, so pulling
 * hard visibly builds faster. At night the city's windows light up in proportion to the energy put
 * in this session, and a "today" tower on the right grows with this session's floors.
 *
 * <p>Towers are drawn as single extruded prisms rather than stacks of cubes: 25 plots of up to 14
 * blocks would be 350 sorted quads a frame, where 25 prisms is nothing.
 *
 * <p>3.20, five additions the rower approved:
 * <ul>
 *   <li><b>Neighbourhoods.</b> A park opens at {@link #PARK_AT} lifetime blocks (lawn, pond with a
 *   fountain, trees that grow in, lamps, strollers) and a river with a suspension bridge at
 *   {@link #BRIDGE_AT} (cars crossing, walkers, and a rowing shell on the water that moves with the
 *   rower's own metres and strokes). A rower already past a milestone gets the opening ceremony once,
 *   tracked by {@code city.districts}.</li>
 *   <li><b>Night lights.</b> After dusk each tower switches on in a staggered wave: floors built this
 *   session burn gold, older floors light by session energy, roofs get a lit crown and a glow.</li>
 *   <li><b>Residents.</b> Every block that lands sends a mover with a box walking in from the street,
 *   the park or over the bridge to that tower's door; on arrival the population rises and the door
 *   glows. Walkers faster when you row harder.</li>
 *   <li><b>Weekly landmark.</b> One of four (clock tower, ferris wheel, lighthouse, observatory) per
 *   Monday-start week rises on the plaza, built from this week's rowing work across sessions;
 *   target is 40 minutes at the rower's typical watts, hard strokes lay a gold stone bonus.</li>
 *   <li><b>Photo mode.</b> Hides the HUD, stops the auto-pan, drag to turn the city, shutter renders
 *   the city at half resolution with a caption into the app's own files and an in-game album.</li>
 * </ul>
 */
final class SkylineGame extends GameView {

    private static final int GRID = 5;
    private static final int MAX_HEIGHT = 14;
    /** Watt-seconds per block. About one block every few strokes at a steady 130 W. */
    private static final float BLOCK_COST = 260f;

    /**
     * Lifetime blocks at which the neighbourhoods open. At the median 129 W a floor lands about every
     * two seconds of rowing, so the park is two or three sessions' work for a new city and the
     * bridge roughly the halfway point of the 350-floor grid.
     */
    private static final int PARK_AT = 60;
    private static final int BRIDGE_AT = 180;
    /** Minutes of rowing at the rower's typical watts that one weekly landmark takes. */
    private static final double LANDMARK_MINUTES = 40.0;

    private static final String[] LANDMARK_NAMES = {
            "CLOCK TOWER", "FERRIS WHEEL", "LIGHTHOUSE", "OBSERVATORY",
    };
    /** Landmark heights in tile widths. */
    private static final float[] LANDMARK_H = {4.4f, 4.6f, 4.4f, 3.0f};
    private static final float PLAZA_X = 5.4f;
    private static final float PLAZA_Y = 2.0f;
    private static final float BRIDGE_X = 2.0f;

    /** Trees: the first six stand in the park, the rest on the river's far bank. */
    private static final float[] TREE_X = {-1.8f, -1.6f, -1.85f, -1.55f, -0.8f, -0.8f,
            -0.2f, 0.9f, 3.3f, 4.4f, 5.6f};
    private static final float[] TREE_Y = {0.1f, 1.1f, 3.2f, 4.1f, 2.6f, 4.3f,
            6.1f, 6.15f, 6.1f, 6.15f, 6.1f};
    private static final int PARK_TREES = 6;
    private static final float[] LAMP_Y = {1.0f, 3.6f};

    private static final int K_TOWER = 0;
    private static final int K_TREE = 1;
    private static final int K_LAMP = 2;
    private static final int K_FOUNTAIN = 3;
    private static final int K_LANDMARK = 4;
    private static final int K_BRIDGE = 5;
    private static final int K_SHELL = 6;
    private static final int K_RESIDENT = 7;

    private static final int R_MOVER = 0;
    private static final int R_PARK = 1;
    private static final int R_BRIDGE = 2;
    private static final int R_PLAZA = 3;

    private static final class Falling {
        int gx;
        int gy;
        float z;          // current height in block units
        float target;
        int color;
        int units = 1;    // floors in this block
    }

    private static final class Resident {
        boolean active;
        int kind;
        float x;
        float y;
        final float[] wx = new float[4];
        final float[] wy = new float[4];
        int legs;
        int leg;
        float wait;
        float speed;
        float phase;
        int shirt;
        int skin;
        int plotX;
        int plotY;
        int family;
    }

    private static final class Photo {
        File file;
        Bitmap thumb;
        Bitmap full;
    }

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Fx.Particles fx = new Fx.Particles();
    private final Fx.Shake shake = new Fx.Shake();
    private final java.util.Random rng = new java.util.Random();

    private final int[][] height = new int[GRID][GRID];
    private final int[][] tint = new int[GRID][GRID];
    /** Floors on each plot that were built this session: they burn gold after dark. */
    private final int[][] sessionFloors = new int[GRID][GRID];
    /** A warm glow at a tower's door when a family has just moved in, decaying to 0. */
    private final float[][] doorGlow = new float[GRID][GRID];
    private final java.util.List<Falling> falling = new java.util.ArrayList<>();
    private final long[] windowSeed = new long[GRID * GRID];
    private final Resident[] residents = new Resident[18];

    private float angle;
    private float concrete;
    private int placedThisSession;
    private int lifetime;
    private int tallest;
    /** Floors the next block will have, from the last stroke's power. */
    private int nextUnits = 1;
    private PulseMeter.Stroke lastStrokeSeen;
    private double workAtStart = -1;
    /** Share of windows lit tonight: the energy put in this session. */
    private float litShare;
    private int population;

    // Neighbourhoods.
    private boolean parkOpen;
    private boolean bridgeOpen;
    private float parkReveal;
    private float bridgeReveal;
    private int districtsSeen;
    private float ceremonyDelay;
    private String banner;
    private float bannerT;

    // Weekly landmark.
    private long weekIndex;
    private int landmarkKind;
    private double landmarkWork;
    private double landmarkTarget = 1;
    private boolean landmarkDone;
    private int landmarksBuilt;
    private boolean stoneFlash;
    private float wheelAngle;
    private float beamAngle;
    private float hourAngle;
    private float minuteAngle;
    private double clockUpdatedAt = -10;
    private java.util.TimeZone zone = java.util.TimeZone.getDefault();

    // Geometry for this frame.
    private float gCx;
    private float gCy;
    private float gTw;
    private float gTh;
    private float gBh;
    private float gCos;
    private float gSin;
    private float ctrX;
    private float ctrY;
    private float minX;
    private float maxX;
    private float maxY;
    private float px;
    private float py;
    private int lowX;
    private int lowY;
    private float shellX;

    // Depth sort, preallocated.
    private final int[] itemKind = new int[80];
    private final int[] itemRef = new int[80];
    private final float[] itemDepth = new float[80];
    private int itemCount;

    // Cached sky.
    private LinearGradient skyShader;
    private int skyKey = -1;

    // Photo mode.
    private boolean photoMode;
    private final java.util.ArrayList<Photo> album = new java.util.ArrayList<>();
    private boolean albumLoaded;
    private Bitmap viewing;
    private float flash;
    private String photoNote;
    private float photoNoteT;
    private final RectF photoBtn = new RectF();
    private final RectF doneBtn = new RectF();
    private final RectF viewRect = new RectF();
    private final RectF[] thumbRects = new RectF[6];
    private float snapX;
    private float snapY;
    private float snapR;
    private float downX;
    private float lastTouchX;
    private boolean dragging;

    private static final int[] PALETTE = {
            0xFF4C6EA8, 0xFF3E8C7E, 0xFF8A6BB0, 0xFF9A6B4F, 0xFF5E7A90, 0xFF7A8A4F,
    };
    private static final int[] SHIRTS = {
            0xFFE0582E, 0xFF35D0BA, 0xFF6F8CFF, 0xFFF0B132, 0xFFE06BA8, 0xFF8BD05A, 0xFFE6EDF7,
    };
    private static final int[] SKINS = {0xFFF1C27D, 0xFFC68642, 0xFF8D5524, 0xFFFFDBAC};

    SkylineGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        java.util.Random r = new java.util.Random(31);
        for (int i = 0; i < windowSeed.length; i++) {
            windowSeed[i] = r.nextLong();
        }
        for (int i = 0; i < residents.length; i++) {
            residents[i] = new Resident();
        }
        for (int i = 0; i < thumbRects.length; i++) {
            thumbRects[i] = new RectF();
        }
        paint.setFilterBitmap(true);
    }

    @Override
    protected void onStart() {
        concrete = 0f;
        placedThisSession = 0;
        nextUnits = 1;
        workAtStart = -1;
        litShare = 0f;
        falling.clear();
        angle = 0.6f;
        photoMode = false;
        viewing = null;
        banner = null;
        bannerT = 0f;
        zone = java.util.TimeZone.getDefault();
        lifetime = Math.round(bests.get("city.blocks", 0f));
        // Rebuild the standing city from the lifetime total, filling plots evenly.
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                height[gx][gy] = 0;
                sessionFloors[gx][gy] = 0;
                doorGlow[gx][gy] = 0f;
                tint[gx][gy] = PALETTE[(gx * GRID + gy) % PALETTE.length];
            }
        }
        for (int i = 0; i < lifetime && i < GRID * GRID * MAX_HEIGHT; i++) {
            lowestPlot();
            height[lowX][lowY]++;
        }
        recomputeTallest();
        population = lifetime * 4;

        // Districts: open silently what has already been celebrated, stage a ceremony for the rest.
        districtsSeen = Math.round(bests.get("city.districts", 0f));
        parkOpen = lifetime >= PARK_AT && districtsSeen >= 1;
        bridgeOpen = lifetime >= BRIDGE_AT && districtsSeen >= 2;
        parkReveal = parkOpen ? 1f : 0f;
        bridgeReveal = bridgeOpen ? 1f : 0f;
        ceremonyDelay = 1.5f;

        // This week's landmark.
        long now = System.currentTimeMillis();
        long localDays = (now + zone.getOffset(now)) / 86400000L;
        weekIndex = (localDays + 3) / 7;   // 1970-01-01 was a Thursday: weeks start on Monday
        landmarkKind = (int) (weekIndex % LANDMARK_NAMES.length);
        landmarkTarget = Math.max(1.0, profile.typicalWatts() * 60.0 * LANDMARK_MINUTES);
        landmarksBuilt = Math.round(bests.get("city.landmarks", 0f));
        if (Math.round(bests.get("city.landmark.week", -1f)) == weekIndex) {
            landmarkWork = bests.get("city.landmark.work", 0f);
        } else {
            landmarkWork = 0;
        }
        landmarkDone = landmarkWork >= landmarkTarget;

        for (Resident r : residents) {
            r.active = false;
        }
        for (int i = 0; i < 2; i++) {
            spawnResident(R_PLAZA);
        }
        if (parkOpen) {
            spawnStrollers(R_PARK, 3);
        }
        if (bridgeOpen) {
            spawnStrollers(R_BRIDGE, 2);
        }
    }

    @Override
    protected void onStop() {
        bests.recordHighest("city.blocks", lifetime);
        bests.recordHighest("city.tallest", tallest);
        saveLandmark();
    }

    private void saveLandmark() {
        bests.putFloat("city.landmark.week", weekIndex);
        bests.putFloat("city.landmark.work", (float) landmarkWork);
    }

    /** Sets {@link #lowX}/{@link #lowY} to the plot the next block should go on. */
    private void lowestPlot() {
        int best = Integer.MAX_VALUE;
        lowX = 0;
        lowY = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                int hh = height[gx][gy] + pendingFor(gx, gy);
                // Centre plots are favoured slightly so the skyline peaks in the middle.
                int bias = Math.abs(gx - GRID / 2) + Math.abs(gy - GRID / 2);
                int score = hh * 4 + bias;
                if (hh < MAX_HEIGHT && score < best) {
                    best = score;
                    lowX = gx;
                    lowY = gy;
                }
            }
        }
    }

    private int pendingFor(int gx, int gy) {
        int n = 0;
        for (int i = 0; i < falling.size(); i++) {
            Falling f = falling.get(i);
            if (f.gx == gx && f.gy == gy) {
                n += f.units;
            }
        }
        return n;
    }

    /** Watt-seconds for a block of this many floors: bigger blocks are cheaper per floor. */
    private static float cost(int units) {
        return BLOCK_COST * (1f + 0.75f * (units - 1));
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (workAtStart < 0) {
            workAtStart = s.meter.workJoules;
        }
        PulseMeter.Stroke stroke = s.meter.lastStroke;
        if (stroke != null && stroke != lastStrokeSeen) {
            lastStrokeSeen = stroke;
            double power = !Double.isNaN(stroke.averagePowerW) ? stroke.averagePowerW : s.watts;
            double ratio = power / Math.max(1.0, profile.typicalWatts());
            nextUnits = ratio >= 1.3 ? 3 : ratio >= 1.1 ? 2 : 1;
            // A hard stroke lays a gold stone on this week's landmark: 1.5 s of its own power extra.
            if (ratio >= 1.1 && !landmarkDone && isClockRunning()) {
                landmarkWork += power * 1.5;
                stoneFlash = true;
            }
        }
        // Ten minutes of typical work lights every window.
        double work = Math.max(0, s.meter.workJoules - Math.max(0, workAtStart));
        litShare = (float) Math.min(1.0, work / Math.max(1.0, profile.typicalWatts() * 600.0));
    }

    private void recomputeTallest() {
        tallest = 0;
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                tallest = Math.max(tallest, height[gx][gy]);
            }
        }
    }

    /* ---------------------------------------------------------------- frame ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        step(dt, w, h);

        c.save();
        if (!photoMode) {
            c.translate(shake.dx, shake.dy);
        }
        drawWorld(c, w, h, false);
        c.restore();

        if (photoMode) {
            drawPhotoUi(c, w, h);
        } else {
            drawHud(c, w, h);
        }
        drawBanner(c, w, h);
        if (flash > 0f) {
            paint.setColor(((int) (Math.min(1f, flash) * 230) << 24) | 0xFFFFFF);
            c.drawRect(0, 0, w, h, paint);
        }
        if (viewing != null) {
            drawViewer(c, w, h);
        }
    }

    private void step(float dt, float w, float h) {
        int watts = status == null ? 0 : status.watts;
        float speed = boat.value();

        // The camera pans a little faster while you are working, so effort feels like momentum.
        // Photo mode holds it still so the rower can frame the shot by dragging.
        if (!photoMode) {
            angle += dt * (0.10f + speed * 0.035f);
        }
        shake.step(dt);
        fx.step(dt, dp(220f));
        flash = Math.max(0f, flash - dt * 3f);
        photoNoteT = Math.max(0f, photoNoteT - dt);
        bannerT = Math.max(0f, bannerT - dt);

        // Build whenever the rowing clock runs. The first version needed `driving`, which is only true
        // briefly after each speed reading changes - on the tablet the crane said "idle" mid-row and
        // placed nothing.
        boolean building = isClockRunning() && watts > 0;
        if (building) {
            concrete += watts * dt;
            while (concrete >= cost(nextUnits)) {
                lowestPlot();
                int room = MAX_HEIGHT - height[lowX][lowY] - pendingFor(lowX, lowY);
                int units = Math.min(nextUnits, room);
                concrete -= cost(Math.max(1, units));
                if (units > 0) {
                    Falling f = new Falling();
                    f.gx = lowX;
                    f.gy = lowY;
                    f.target = height[lowX][lowY] + pendingFor(lowX, lowY);
                    f.z = f.target + 9f;
                    f.color = tint[lowX][lowY];
                    f.units = units;
                    falling.add(f);
                }
            }
            if (!landmarkDone) {
                landmarkWork += watts * dt;
            }
        }

        computeGeometry(w, h);

        // Falling blocks, and landings.
        for (int i = falling.size() - 1; i >= 0; i--) {
            Falling f = falling.get(i);
            float fall = 4.5f + speed * 2.6f;       // rowing harder brings them down faster
            f.z -= fall * dt;
            if (f.z <= f.target) {
                int before = lifetime;
                height[f.gx][f.gy] = Math.min(MAX_HEIGHT, height[f.gx][f.gy] + f.units);
                sessionFloors[f.gx][f.gy] += f.units;
                lifetime += f.units;
                placedThisSession += f.units;
                recomputeTallest();
                falling.remove(i);
                project(f.gx, f.gy, f.target + 1);
                fx.burst(px, py, 14, dp(90f), 0.45f, dp(3f), 0xCCD8C9A8, true);
                shake.kick(dp(2.5f));
                spawnMover(f.gx, f.gy, f.units);
                if (before < PARK_AT && lifetime >= PARK_AT) {
                    ceremonyDelay = Math.min(ceremonyDelay, 0.4f);
                }
                if (before < BRIDGE_AT && lifetime >= BRIDGE_AT) {
                    ceremonyDelay = Math.min(ceremonyDelay, 0.4f);
                }
            }
        }

        // District opening ceremonies, one at a time.
        ceremonyDelay -= dt;
        if (ceremonyDelay <= 0f) {
            if (!parkOpen && lifetime >= PARK_AT) {
                openPark();
                ceremonyDelay = 4.5f;
            } else if (parkOpen && !bridgeOpen && lifetime >= BRIDGE_AT) {
                openBridge();
                ceremonyDelay = 4.5f;
            } else {
                ceremonyDelay = 1f;
            }
        }
        if (parkOpen) {
            parkReveal = Math.min(1f, parkReveal + dt / 2.5f);
        }
        if (bridgeOpen) {
            bridgeReveal = Math.min(1f, bridgeReveal + dt / 3f);
        }

        // Landmark.
        if (!landmarkDone && landmarkWork >= landmarkTarget) {
            landmarkDone = true;
            landmarksBuilt++;
            bests.recordHighest("city.landmarks", landmarksBuilt);
            saveLandmark();
            showBanner(LANDMARK_NAMES[landmarkKind] + " COMPLETE");
            project(PLAZA_X, PLAZA_Y, 0f);
            float top = py - gTw * LANDMARK_H[landmarkKind];
            for (int k = 0; k < 5; k++) {
                fx.burst(px + (k - 2) * gTw * 0.6f, top + (k % 2) * gTw * 0.4f, 22, dp(170f), 1.1f,
                        dp(3f), SHIRTS[k % SHIRTS.length], true);
            }
            shake.kick(dp(7f));
        }
        if (stoneFlash) {
            stoneFlash = false;
            project(PLAZA_X, PLAZA_Y, 0f);
            float top = py - gTw * 0.12f - (gTw * LANDMARK_H[landmarkKind] - gTw * 0.12f)
                    * landmarkFraction();
            fx.burst(px, top, 12, dp(70f), 0.6f, dp(2.5f), 0xFFFFD24A, true);
        }
        if (landmarkDone) {
            wheelAngle += dt * (0.15f + speed * 0.09f);
        }
        beamAngle += dt * 1.1f;
        if (sessionSeconds - clockUpdatedAt >= 1.0) {
            clockUpdatedAt = sessionSeconds;
            long now = System.currentTimeMillis();
            long local = now + zone.getOffset(now);
            float minutes = (local / 60000L) % 60 + ((local / 1000L) % 60) / 60f;
            float hours = (local / 3600000L) % 12 + minutes / 60f;
            minuteAngle = (float) (minutes / 60.0 * Math.PI * 2);
            hourAngle = (float) (hours / 12.0 * Math.PI * 2);
        }

        // The rower's shell on the river covers the metres actually rowed.
        float span = Math.max(1f, (maxX - minX) - 0.8f);
        shellX = minX + 0.4f + (float) ((sessionMeters * 0.03) % span);

        stepResidents(dt, speed);
        for (int gx = 0; gx < GRID; gx++) {
            for (int gy = 0; gy < GRID; gy++) {
                if (doorGlow[gx][gy] > 0f) {
                    doorGlow[gx][gy] = Math.max(0f, doorGlow[gx][gy] - dt * 0.4f);
                }
            }
        }
    }

    private void computeGeometry(float w, float h) {
        minX = parkOpen ? -2.2f : -0.6f;
        maxX = 6.2f;
        float minY = -0.6f;
        maxY = bridgeOpen ? 6.4f : GRID - 0.4f;
        ctrX = (minX + maxX) / 2f;
        ctrY = (minY + maxY) / 2f;
        float hx = (maxX - minX) / 2f;
        float hy = (maxY - minY) / 2f;
        // Worst-case projected half-extent over a full turn, so the zoom does not breathe as it pans.
        float ext = 1.414f * (float) Math.sqrt(hx * hx + hy * hy);
        float fit = Math.min(1f, 5.8f / ext);
        // Tile size shrinks as the city grows so it stays on screen.
        float tallScale = 1f - Math.min(0.35f, tallest / (float) MAX_HEIGHT * 0.35f);
        gTw = Math.min(w, h) * 0.105f * tallScale * fit;
        gTh = gTw * 0.52f;
        gBh = gTw * 0.62f;
        gCx = w * 0.5f;
        gCy = h * 0.64f;
        gCos = (float) Math.cos(angle);
        gSin = (float) Math.sin(angle);
    }

    /* ---------------------------------------------------------------- districts ---------- */

    private void openPark() {
        parkOpen = true;
        parkReveal = 0f;
        showBanner("THE PARK IS OPEN");
        project(-1.4f, 2f, 0f);
        fx.burst(px, py, 30, dp(160f), 1.0f, dp(3f), 0xFF8BD05A, true);
        fx.burst(px, py, 20, dp(120f), 1.0f, dp(3f), 0xFFE06BA8, true);
        shake.kick(dp(4f));
        spawnStrollers(R_PARK, 3);
        if (districtsSeen < 1) {
            districtsSeen = 1;
            bests.recordHighest("city.districts", 1);
        }
    }

    private void openBridge() {
        bridgeOpen = true;
        bridgeReveal = 0f;
        showBanner("THE BRIDGE IS OPEN");
        project(BRIDGE_X, 5.2f, 1f);
        fx.burst(px, py, 30, dp(160f), 1.0f, dp(3f), 0xFF6F8CFF, true);
        fx.burst(px, py, 20, dp(120f), 1.0f, dp(3f), 0xFFF0B132, true);
        shake.kick(dp(4f));
        spawnStrollers(R_BRIDGE, 2);
        if (districtsSeen < 2) {
            districtsSeen = 2;
            bests.recordHighest("city.districts", 2);
        }
    }

    private void showBanner(String text) {
        banner = text;
        bannerT = 3.5f;
    }

    private float landmarkFraction() {
        return (float) Math.max(0.0, Math.min(1.0, landmarkWork / landmarkTarget));
    }

    /* ---------------------------------------------------------------- residents ---------- */

    private Resident freeResident() {
        for (Resident r : residents) {
            if (!r.active) {
                return r;
            }
        }
        return null;
    }

    private void spawnStrollers(int kind, int n) {
        for (int i = 0; i < n; i++) {
            spawnResident(kind);
        }
    }

    private void spawnResident(int kind) {
        Resident r = freeResident();
        if (r == null) {
            return;
        }
        r.active = true;
        r.kind = kind;
        r.shirt = SHIRTS[rng.nextInt(SHIRTS.length)];
        r.skin = SKINS[rng.nextInt(SKINS.length)];
        r.speed = 0.22f + rng.nextFloat() * 0.1f;
        r.phase = rng.nextFloat() * 6f;
        r.wait = rng.nextFloat() * 2f;
        if (kind == R_PARK) {
            r.x = -2.0f + rng.nextFloat() * 1.2f;
            r.y = -0.4f + rng.nextFloat() * 4.8f;
        } else if (kind == R_BRIDGE) {
            r.x = BRIDGE_X + (rng.nextBoolean() ? 0.1f : -0.1f);
            r.y = 4.4f + rng.nextFloat() * 1.7f;
        } else {
            double a = rng.nextDouble() * Math.PI * 2;
            r.x = PLAZA_X + (float) Math.cos(a) * 0.62f;
            r.y = PLAZA_Y + (float) Math.sin(a) * 0.62f;
        }
        pickStrollTarget(r);
    }

    private void pickStrollTarget(Resident r) {
        r.legs = 1;
        r.leg = 0;
        if (r.kind == R_PARK) {
            r.wx[0] = -2.05f + rng.nextFloat() * 1.3f;
            r.wy[0] = -0.45f + rng.nextFloat() * 4.9f;
        } else if (r.kind == R_BRIDGE) {
            r.wx[0] = r.x;
            r.wy[0] = r.y > 5.2f ? 4.35f : 6.15f;
        } else {
            double a = rng.nextDouble() * Math.PI * 2;
            r.wx[0] = PLAZA_X + (float) Math.cos(a) * 0.62f;
            r.wy[0] = PLAZA_Y + (float) Math.sin(a) * 0.62f;
        }
    }

    /**
     * A family moves into the floors that just landed: a walker with a box comes in from the street,
     * the park or over the bridge and walks the gap between plots to the tower. If every walker is
     * busy the family is counted straight away, so the population never loses anyone.
     */
    private void spawnMover(int gx, int gy, int units) {
        Resident r = freeResident();
        if (r == null) {
            population += units * 4;
            doorGlow[gx][gy] = 1f;
            return;
        }
        r.active = true;
        r.kind = R_MOVER;
        r.shirt = SHIRTS[rng.nextInt(SHIRTS.length)];
        r.skin = SKINS[rng.nextInt(SKINS.length)];
        r.speed = 0.55f + rng.nextFloat() * 0.15f;
        r.phase = 0f;
        r.wait = 0f;
        r.plotX = gx;
        r.plotY = gy;
        r.family = units * 4;
        r.leg = 0;
        float lane = gx + 0.5f;
        int entries = 1 + (parkOpen ? 1 : 0) + (bridgeOpen ? 1 : 0);
        int pick = rng.nextInt(entries);
        if (pick == 1 && parkOpen) {
            // From the park, along the gap between rows.
            float row = gy + 0.5f;
            r.x = -1.6f;
            r.y = row;
            r.wx[0] = -0.55f;
            r.wy[0] = row;
            r.wx[1] = gx;
            r.wy[1] = row;
            r.legs = 2;
        } else if (pick >= 1 && bridgeOpen) {
            // Over the bridge from the far bank.
            r.x = BRIDGE_X;
            r.y = 6.25f;
            r.wx[0] = BRIDGE_X;
            r.wy[0] = GRID - 0.45f;
            r.wx[1] = lane;
            r.wy[1] = GRID - 0.45f;
            r.wx[2] = lane;
            r.wy[2] = gy;
            r.legs = 3;
        } else {
            // In from the street on the near edge.
            r.x = lane;
            r.y = -0.58f;
            r.wx[0] = lane;
            r.wy[0] = gy;
            r.legs = 1;
        }
    }

    private void stepResidents(float dt, float speed) {
        // The city bustles when you row: walkers pick up with boat speed.
        float hurry = 1f + Math.max(0f, speed) * 0.12f;
        for (Resident r : residents) {
            if (!r.active) {
                continue;
            }
            if (r.wait > 0f) {
                r.wait -= dt;
                continue;
            }
            float tx = r.wx[r.leg];
            float ty = r.wy[r.leg];
            float dx = tx - r.x;
            float dy = ty - r.y;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            float stepLen = r.speed * hurry * dt;
            r.phase += dt * 9f * hurry;
            if (dist > stepLen) {
                r.x += dx / dist * stepLen;
                r.y += dy / dist * stepLen;
                continue;
            }
            r.x = tx;
            r.y = ty;
            r.leg++;
            if (r.leg < r.legs) {
                continue;
            }
            if (r.kind == R_MOVER) {
                r.active = false;
                population += r.family;
                doorGlow[r.plotX][r.plotY] = 1f;
                project(r.x, r.y, 0.3f);
                fx.burst(px, py - gTw * 0.25f, 10, dp(55f), 0.7f, dp(2.5f), 0xFFFF7AA8, false);
            } else {
                r.wait = r.kind == R_BRIDGE ? 0.4f + rng.nextFloat() : 0.6f + rng.nextFloat() * 2.4f;
                pickStrollTarget(r);
            }
        }
    }

    /* ---------------------------------------------------------------- world ---------- */

    /** Everything but the HUD: this is also what photo mode renders into a bitmap. */
    private void drawWorld(Canvas c, float w, float h, boolean forPhoto) {
        // Sky: a slow day-night cycle so a long row visibly passes time.
        float tod = timeOfDay();
        int key = (int) (tod * 48) * 100000 + (int) h;
        if (skyShader == null || key != skyKey) {
            float q = (int) (tod * 48) / 48f;
            skyShader = new LinearGradient(0, 0, 0, h,
                    blend(0xFF0B1430, 0xFF2E6FB0, q), blend(0xFF2A1C38, 0xFFBFD9EE, q),
                    Shader.TileMode.CLAMP);
            skyKey = key;
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        if (tod < 0.5f) {
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha((int) ((0.5f - tod) * 2 * 200));
            for (int i = 0; i < 40; i++) {
                c.drawCircle((i * 197 % Math.max(1, (int) w)), (i * 131 % Math.max(1, (int) (h * 0.5f))), dp(1.1f), paint);
            }
            paint.setAlpha(255);
        }
        // Sun or moon arcing across.
        float sunX = w * (0.15f + 0.7f * ((float) ((activeSeconds / 150.0) % 1.0)));
        float sunY = h * (0.40f - 0.26f * (float) Math.sin(Math.PI * ((activeSeconds / 150.0) % 1.0)));
        Fx.glow(c, sunX, sunY, dp(60f), tod > 0.5f ? 0x66FFE8A8 : 0x55BFD9EE);
        paint.setColor(tod > 0.5f ? 0xFFFFE8A8 : 0xFFE9EEF5);
        c.drawCircle(sunX, sunY, dp(20f), paint);
        drawSkyTraffic(c, w, h, tod);
        drawSiteLife(c, w, h, tod);

        drawGround(c, tod, forPhoto);

        // Everything that stands up, far to near.
        itemCount = 0;
        for (int idx = 0; idx < GRID * GRID; idx++) {
            int gx = idx % GRID;
            int gy = idx / GRID;
            if (height[gx][gy] > 0) {
                addItem(K_TOWER, idx, gx, gy);
            }
        }
        if (parkOpen) {
            for (int i = 0; i < PARK_TREES; i++) {
                addItem(K_TREE, i, TREE_X[i], TREE_Y[i]);
            }
            for (int i = 0; i < LAMP_Y.length; i++) {
                addItem(K_LAMP, i, -0.95f, LAMP_Y[i]);
            }
            addItem(K_FOUNTAIN, 0, -1.7f, 2.1f);
        }
        if (bridgeOpen) {
            for (int i = PARK_TREES; i < TREE_X.length; i++) {
                if (TREE_X[i] >= minX) {
                    addItem(K_TREE, i, TREE_X[i], TREE_Y[i]);
                }
            }
            addItem(K_BRIDGE, 0, BRIDGE_X, 5.2f);
            addItem(K_SHELL, 0, shellX, 5.35f);
        }
        addItem(K_LANDMARK, 0, PLAZA_X, PLAZA_Y);
        float bridgeDepth = depth(BRIDGE_X, 5.2f);
        for (int i = 0; i < residents.length; i++) {
            Resident r = residents[i];
            if (r.active) {
                addItem(K_RESIDENT, i, r.x, r.y);
                // The bridge is one sorted item, so a walker on its far half would sort behind the
                // deck and vanish mid-crossing. Anyone on the deck draws after it.
                if (bridgeOpen && onBridge(r) && itemCount > 0 && itemRef[itemCount - 1] == i
                        && itemKind[itemCount - 1] == K_RESIDENT) {
                    itemDepth[itemCount - 1] = Math.max(itemDepth[itemCount - 1], bridgeDepth + 0.05f);
                }
            }
        }
        // Insertion sort: under 80 items, no allocation.
        for (int i = 1; i < itemCount; i++) {
            float d = itemDepth[i];
            int k = itemKind[i];
            int r = itemRef[i];
            int j = i - 1;
            while (j >= 0 && itemDepth[j] > d) {
                itemDepth[j + 1] = itemDepth[j];
                itemKind[j + 1] = itemKind[j];
                itemRef[j + 1] = itemRef[j];
                j--;
            }
            itemDepth[j + 1] = d;
            itemKind[j + 1] = k;
            itemRef[j + 1] = r;
        }
        for (int i = 0; i < itemCount; i++) {
            int ref = itemRef[i];
            switch (itemKind[i]) {
                case K_TOWER:
                    drawTower(c, ref % GRID, ref / GRID, tod, ref);
                    break;
                case K_TREE:
                    drawTree(c, ref, tod);
                    break;
                case K_LAMP:
                    drawLamp(c, ref, tod);
                    break;
                case K_FOUNTAIN:
                    drawFountain(c, tod);
                    break;
                case K_LANDMARK:
                    drawLandmark(c, tod);
                    break;
                case K_BRIDGE:
                    drawBridge(c, tod);
                    break;
                case K_SHELL:
                    drawShell(c, tod);
                    break;
                default:
                    drawResident(c, residents[ref]);
                    break;
            }
        }

        // Falling blocks, with a guide line down to the plot so you can see where each is going.
        for (int i = falling.size() - 1; i >= 0; i--) {
            Falling f = falling.get(i);
            for (int u = f.units - 1; u >= 0; u--) {
                drawBlock(c, f.gx, f.gy, f.z + u, f.color);
            }
            project(f.gx, f.gy, f.z);
            float fx0 = px;
            float fy0 = py;
            project(f.gx, f.gy, f.target);
            paint.setColor(0x55FFE28A);
            paint.setStrokeWidth(dp(1.5f));
            c.drawLine(fx0, fy0, px, py, paint);
        }
        fx.draw(c);
    }

    private float timeOfDay() {
        return (float) ((Math.sin(activeSeconds / 150.0 - Math.PI / 2) + 1) / 2);   // 0 night..1 day
    }

    private void addItem(int kind, int ref, float gx, float gy) {
        if (itemCount >= itemKind.length) {
            return;
        }
        itemKind[itemCount] = kind;
        itemRef[itemCount] = ref;
        // Small things sort a touch forward so a walker at a tower's foot is not swallowed by it.
        itemDepth[itemCount] = depth(gx, gy) + (kind == K_RESIDENT ? 0.3f : 0f);
        itemCount++;
    }

    private void drawGround(Canvas c, float tod, boolean forPhoto) {
        paint.setStyle(Paint.Style.FILL);
        plate(c, -0.6f, -0.6f, GRID - 0.4f, GRID - 0.4f, blend(0xFF15202E, 0xFF3F4A56, tod));
        // The plaza where the weekly landmark goes up.
        plate(c, GRID - 0.4f, 1.2f, 6.2f, 2.8f, blend(0xFF1E2532, 0xFF7C7F88, tod));
        ellipse(c, PLAZA_X, PLAZA_Y, 0.62f, blend(0xFF283040, 0xFF9A9CA4, tod));

        if (parkOpen) {
            plate(c, -2.2f, -0.6f, -0.6f, GRID - 0.4f, blend(0xFF10261A, 0xFF4E8A4A, tod));
            plate(c, -1.2f, -0.6f, -1.0f, GRID - 0.4f, blend(0xFF3A3526, 0xFFC9B98A, tod));
            ellipse(c, -1.7f, 2.1f, 0.34f * Math.max(0.2f, parkReveal), blend(0xFF0E2238, 0xFF4C8FC4, tod));
        } else if (!forPhoto) {
            outline(c, -2.2f, -0.6f, -0.6f, GRID - 0.4f);
            project(-1.4f, 2f, 0f);
            bold(c, "PARK", px, py, 11f, 0x88E6EDF7, Paint.Align.CENTER);
            label(c, "AT " + PARK_AT + " BLOCKS", px, py + dp(13f), 8.5f, 0x88E6EDF7, Paint.Align.CENTER);
        }

        if (bridgeOpen) {
            plate(c, minX, GRID - 0.4f, maxX, 5.8f, blend(0xFF0A1A2C, 0xFF3F7FB8, tod));
            plate(c, minX, 5.8f, maxX, 6.4f, blend(0xFF13241A, 0xFF5B7F4C, tod));
            // Ripples drifting downstream.
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor(tod > 0.4f ? 0x66FFFFFF : 0x33BFD9EE);
            float span = (maxX - minX) - 0.35f;
            for (int i = 0; i < 10; i++) {
                float x = minX + (float) ((i * 0.83 + sessionSeconds * 0.15) % span);
                float y = 4.75f + (i % 4) * 0.28f;
                project(x, y, 0f);
                float x0 = px;
                float y0 = py;
                project(x + 0.3f, y, 0f);
                c.drawLine(x0, y0, px, py, paint);
            }
        } else if (!forPhoto) {
            outline(c, -0.6f, GRID - 0.4f, maxX, 5.8f);
            project(BRIDGE_X, 5.2f, 0f);
            bold(c, "RIVER AND BRIDGE", px, py, 11f, 0x88E6EDF7, Paint.Align.CENTER);
            label(c, "AT " + BRIDGE_AT + " BLOCKS", px, py + dp(13f), 8.5f, 0x88E6EDF7, Paint.Align.CENTER);
        }
    }

    private void plate(Canvas c, float x0, float y0, float x1, float y1, int color) {
        quadPath(x0, y0, x1, y1, 0f);
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private void outline(Canvas c, float x0, float y0, float x1, float y1) {
        quadPath(x0, y0, x1, y1, 0f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0x44E6EDF7);
        c.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void quadPath(float x0, float y0, float x1, float y1, float z) {
        path.reset();
        project(x0, y0, z);
        path.moveTo(px, py);
        project(x1, y0, z);
        path.lineTo(px, py);
        project(x1, y1, z);
        path.lineTo(px, py);
        project(x0, y1, z);
        path.lineTo(px, py);
        path.close();
    }

    /** A flat circle on the ground, drawn in the rotating plane so it turns with the city. */
    private void ellipse(Canvas c, float gx, float gy, float r, int color) {
        path.reset();
        for (int k = 0; k < 14; k++) {
            double a = k * Math.PI * 2 / 14;
            project(gx + (float) Math.cos(a) * r, gy + (float) Math.sin(a) * r, 0f);
            if (k == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.close();
        paint.setColor(color);
        c.drawPath(path, paint);
    }

    private void drawTree(Canvas c, int i, float tod) {
        float grow = i < PARK_TREES ? parkReveal : bridgeReveal;
        // Staggered so the park fills in tree by tree.
        float g = Math.max(0f, Math.min(1f, grow * 1.6f - (i % PARK_TREES) * 0.1f));
        if (g <= 0f) {
            return;
        }
        project(TREE_X[i], TREE_Y[i], 0f);
        float s = gTw * g;
        float sway = (float) Math.sin(sessionSeconds * 1.3 + i) * s * 0.03f;
        paint.setColor(blend(0xFF2A1E14, 0xFF6B4A2E, tod));
        c.drawRect(px - s * 0.05f, py - s * 0.32f, px + s * 0.05f, py, paint);
        int leaf = blend(0xFF12301C, (i % 2 == 0) ? 0xFF4FA048 : 0xFF6BB45A, tod);
        paint.setColor(leaf);
        c.drawCircle(px + sway, py - s * 0.45f, s * 0.22f, paint);
        paint.setColor(blend(leaf, 0xFFFFFFFF, 0.12f));
        c.drawCircle(px + sway - s * 0.07f, py - s * 0.55f, s * 0.13f, paint);
    }

    private void drawLamp(Canvas c, int i, float tod) {
        if (parkReveal < 0.5f) {
            return;
        }
        project(-0.95f, LAMP_Y[i], 0f);
        float s = gTw;
        paint.setColor(0xFF2A3446);
        c.drawRect(px - s * 0.02f, py - s * 0.55f, px + s * 0.02f, py, paint);
        boolean night = tod < 0.45f;
        if (night) {
            Fx.glow(c, px, py - s * 0.58f, s * 0.45f, 0x66FFE6A8);
        }
        paint.setColor(night ? 0xFFFFE6A8 : 0xFF9AA5B1);
        c.drawCircle(px, py - s * 0.58f, s * 0.06f, paint);
    }

    private void drawFountain(Canvas c, float tod) {
        if (parkReveal < 0.6f) {
            return;
        }
        project(-1.7f, 2.1f, 0f);
        float s = gTw;
        paint.setColor(blend(0xFF3A4052, 0xFFB9BCC4, tod));
        c.drawRect(px - s * 0.06f, py - s * 0.18f, px + s * 0.06f, py, paint);
        // Jets arc up and fall back into the pond.
        paint.setColor(tod > 0.4f ? 0xCCE9F4FF : 0x99BFD9EE);
        double t = sessionSeconds;
        for (int k = 0; k < 10; k++) {
            float life = (float) ((t * 0.9 + k * 0.1) % 1.0);
            float dir = (k % 2 == 0 ? 1f : -1f) * (0.3f + (k % 5) * 0.08f);
            float x = px + dir * s * 0.45f * life;
            float y = py - s * 0.2f - s * 0.55f * (life * (1 - life)) * 4f * 0.5f;
            c.drawCircle(x, y, s * 0.028f, paint);
        }
        if (tod < 0.45f) {
            Fx.glow(c, px, py - s * 0.25f, s * 0.5f, 0x3380C8FF);
        }
    }

    private void drawBridge(Canvas c, float tod) {
        float g = bridgeReveal;
        float x0 = BRIDGE_X - 0.26f;
        float x1 = BRIDGE_X + 0.26f;
        float y0 = GRID - 0.55f;
        float y1 = 5.95f;
        float deck = 0.18f;
        // Deck.
        quadPath(x0, y0, x1, y1, deck);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(0xFF2A303C, 0xFF8D939E, tod));
        c.drawPath(path, paint);
        // Pylons, rising in as the bridge opens.
        float top = deck + 1.6f * g;
        paint.setStrokeWidth(Math.max(dp(2f), gTw * 0.05f));
        paint.setColor(blend(0xFF5A2E2A, 0xFFC0503E, tod));
        for (int side = 0; side < 2; side++) {
            float sx = side == 0 ? x0 : x1;
            for (int p = 0; p < 2; p++) {
                float sy = p == 0 ? 4.85f : 5.55f;
                project(sx, sy, 0f);
                float bx = px;
                float by = py;
                project(sx, sy, top);
                c.drawLine(bx, by, px, py, paint);
            }
        }
        // Cables: pylon tops to the deck ends and a sag between the pylons, bulbs along them at night.
        paint.setStrokeWidth(dp(1.2f));
        boolean night = tod < 0.45f;
        for (int side = 0; side < 2; side++) {
            float sx = side == 0 ? x0 : x1;
            paint.setColor(blend(0xFF6A3A34, 0xFFD8705C, tod));
            project(sx, y0, deck);
            float ax = px;
            float ay = py;
            project(sx, 4.85f, top);
            float bx = px;
            float by = py;
            project(sx, 5.55f, top);
            float cx2 = px;
            float cy2 = py;
            project(sx, y1, deck);
            float dx = px;
            float dy = py;
            project(sx, 5.2f, deck + 0.35f * g);
            float mx = px;
            float my = py;
            c.drawLine(ax, ay, bx, by, paint);
            c.drawLine(cx2, cy2, dx, dy, paint);
            paint.setStyle(Paint.Style.STROKE);
            path.reset();
            path.moveTo(bx, by);
            path.quadTo(2 * mx - (bx + cx2) / 2f, 2 * my - (by + cy2) / 2f, cx2, cy2);
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            if (night && g >= 1f) {
                paint.setColor(0xFFFFE6A8);
                for (int k = 0; k <= 6; k++) {
                    float u = k / 6f;
                    // Point on the quadratic sag.
                    float qx = (1 - u) * (1 - u) * bx + 2 * (1 - u) * u * (2 * mx - (bx + cx2) / 2f)
                            + u * u * cx2;
                    float qy = (1 - u) * (1 - u) * by + 2 * (1 - u) * u * (2 * my - (by + cy2) / 2f)
                            + u * u * cy2;
                    c.drawCircle(qx, qy, dp(1.8f), paint);
                }
            }
        }
        // Traffic both ways.
        if (g >= 1f) {
            for (int k = 0; k < 3; k++) {
                float u = (float) ((sessionSeconds * 0.11 + k * 0.37) % 1.0);
                boolean north = k % 2 == 0;
                float cy3 = north ? y0 + u * (y1 - y0) : y1 - u * (y1 - y0);
                project(north ? BRIDGE_X + 0.1f : BRIDGE_X - 0.1f, cy3, deck + 0.05f);
                float s = gTw * 0.08f;
                paint.setColor(SHIRTS[(k * 3) % SHIRTS.length]);
                c.drawRoundRect(px - s * 1.3f, py - s, px + s * 1.3f, py + s * 0.4f, s * 0.4f, s * 0.4f,
                        paint);
                if (night) {
                    Fx.glow(c, px, py, gTw * 0.18f, 0x55FFF1C4);
                }
            }
        }
    }

    /** The rower's own shell on the river: it covers the metres rowed and the oars follow the stroke. */
    private void drawShell(Canvas c, float tod) {
        float y = 5.35f;
        project(shellX - 0.28f, y, 0f);
        float ax = px;
        float ay = py;
        project(shellX + 0.28f, y, 0f);
        float bx = px;
        float by = py;
        float mx = (ax + bx) / 2f;
        float my = (ay + by) / 2f;
        // Wake.
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0x55FFFFFF);
        c.drawLine(ax, ay, ax - (bx - ax) * 0.6f, ay - (by - ay) * 0.6f + dp(2f), paint);
        paint.setStrokeWidth(Math.max(dp(2.5f), gTw * 0.06f));
        paint.setColor(tod > 0.4f ? 0xFFF4F1E8 : 0xFFB9BCC4);
        c.drawLine(ax, ay, bx, by, paint);
        // Oars sweep with the real stroke: back at the catch, through at the finish.
        float sweep = (strokePhase() - 0.5f) * 0.22f;
        paint.setStrokeWidth(dp(1.3f));
        paint.setColor(0xFFE0B060);
        project(shellX + sweep, y - 0.24f, 0f);
        c.drawLine(mx, my, px, py, paint);
        project(shellX + sweep, y + 0.24f, 0f);
        c.drawLine(mx, my, px, py, paint);
        paint.setColor(0xFFE0582E);
        c.drawCircle(mx, my - gTw * 0.05f, gTw * 0.045f, paint);
    }

    private static boolean onBridge(Resident r) {
        return Math.abs(r.x - BRIDGE_X) < 0.3f && r.y > GRID - 0.55f && r.y < 5.95f;
    }

    private void drawResident(Canvas c, Resident r) {
        project(r.x, r.y, bridgeOpen && onBridge(r) ? 0.18f : 0f);
        float s = gTw * 0.1f;
        float x = px;
        float y = py;
        boolean moving = r.wait <= 0f;
        float swing = moving ? (float) Math.sin(r.phase) * s * 0.35f : 0f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A2F3A);
        c.drawRect(x - s * 0.35f + swing, y - s * 1.2f, x - s * 0.05f + swing, y, paint);
        c.drawRect(x + s * 0.05f - swing, y - s * 1.2f, x + s * 0.35f - swing, y, paint);
        paint.setColor(r.shirt);
        c.drawRect(x - s * 0.45f, y - s * 2.4f, x + s * 0.45f, y - s * 1.1f, paint);
        paint.setColor(r.skin);
        c.drawCircle(x, y - s * 2.85f, s * 0.42f, paint);
        if (r.kind == R_MOVER) {
            // Carrying a box overhead.
            float bob = moving ? Math.abs((float) Math.sin(r.phase)) * s * 0.2f : 0f;
            paint.setColor(0xFFC89B62);
            c.drawRect(x - s * 0.6f, y - s * 4.4f - bob, x + s * 0.6f, y - s * 3.35f - bob, paint);
            paint.setColor(0xFF8A6238);
            c.drawRect(x - s * 0.6f, y - s * 3.95f - bob, x + s * 0.6f, y - s * 3.8f - bob, paint);
        }
    }

    /* ---------------------------------------------------------------- landmark ---------- */

    private void drawLandmark(Canvas c, float tod) {
        project(PLAZA_X, PLAZA_Y, 0f);
        float bx = px;
        float by = py;
        float s = gTw;
        float H = s * LANDMARK_H[landmarkKind];
        float f = landmarkFraction();
        boolean night = tod < 0.45f;
        // Plinth, always there: the site is staked out from the first day of the week.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(0xFF3A3F4C, 0xFFB0B3BC, tod));
        c.drawRect(bx - s * 0.55f, by - s * 0.12f, bx + s * 0.55f, by + s * 0.06f, paint);
        float builtTop = by - s * 0.12f - (H - s * 0.12f) * f;
        if (f > 0f) {
            c.save();
            c.clipRect(bx - s * 2.6f, builtTop, bx + s * 2.6f, by + s * 0.2f);
            switch (landmarkKind) {
                case 0:
                    drawClockTower(c, bx, by, s, H, tod, night);
                    break;
                case 1:
                    drawFerrisWheel(c, bx, by, s, H, tod, night);
                    break;
                case 2:
                    drawLighthouse(c, bx, by, s, H, tod, night);
                    break;
                default:
                    drawObservatory(c, bx, by, s, H, tod, night);
                    break;
            }
            c.restore();
        }
        if (!landmarkDone) {
            // Scaffolding over the part still to build, with a work light at the build line.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(0x99F0B132);
            float half = s * (landmarkKind == 1 ? 0.9f : 0.5f);
            c.drawLine(bx - half, builtTop, bx - half, by - H, paint);
            c.drawLine(bx + half, builtTop, bx + half, by - H, paint);
            for (float yy = builtTop; yy > by - H; yy -= s * 0.35f) {
                c.drawLine(bx - half, yy, bx + half, yy, paint);
                c.drawLine(bx - half, yy, bx + half, Math.max(by - H, yy - s * 0.35f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            boolean blink = ((int) (sessionSeconds * 2)) % 2 == 0;
            paint.setColor(blink ? 0xFFFFD24A : 0x66FFD24A);
            c.drawCircle(bx + half, builtTop, dp(3f), paint);
        } else if (night) {
            Fx.glow(c, bx, by - H * 0.5f, s * 1.6f, 0x33FFE6A8);
        }
    }

    private void drawClockTower(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float bodyTop = by - H * 0.72f;
        paint.setColor(blend(0xFF4A4034, 0xFFB9A58A, tod));
        c.drawRect(bx - s * 0.32f, bodyTop, bx, by - s * 0.12f, paint);
        paint.setColor(blend(0xFF3A3228, 0xFF9C8A70, tod));
        c.drawRect(bx, bodyTop, bx + s * 0.32f, by - s * 0.12f, paint);
        path.reset();
        path.moveTo(bx - s * 0.42f, bodyTop);
        path.lineTo(bx + s * 0.42f, bodyTop);
        path.lineTo(bx, by - H);
        path.close();
        paint.setColor(blend(0xFF28324A, 0xFF5E6E8C, tod));
        c.drawPath(path, paint);
        float fy = by - H * 0.6f;
        float r = s * 0.24f;
        if (night) {
            Fx.glow(c, bx, fy, r * 2.2f, 0x66FFF1C4);
        }
        paint.setColor(night ? 0xFFFFF1C4 : 0xFFF4EEDC);
        c.drawCircle(bx, fy, r, paint);
        // The real time of day.
        paint.setColor(0xFF20242C);
        paint.setStrokeWidth(Math.max(dp(1.5f), r * 0.12f));
        c.drawLine(bx, fy, bx + (float) Math.sin(hourAngle) * r * 0.55f,
                fy - (float) Math.cos(hourAngle) * r * 0.55f, paint);
        paint.setStrokeWidth(Math.max(dp(1f), r * 0.07f));
        c.drawLine(bx, fy, bx + (float) Math.sin(minuteAngle) * r * 0.85f,
                fy - (float) Math.cos(minuteAngle) * r * 0.85f, paint);
    }

    private void drawFerrisWheel(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float R = H * 0.45f;
        float hubY = by - H + R;
        paint.setStrokeWidth(Math.max(dp(2f), s * 0.06f));
        paint.setColor(blend(0xFF3A4052, 0xFFB9BCC4, tod));
        c.drawLine(bx - s * 0.7f, by - s * 0.1f, bx, hubY, paint);
        c.drawLine(bx + s * 0.7f, by - s * 0.1f, bx, hubY, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(dp(1.5f), s * 0.04f));
        paint.setColor(blend(0xFF6A5A8A, 0xFFE06BA8, tod));
        c.drawCircle(bx, hubY, R, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeWidth(dp(1f));
        for (int k = 0; k < 10; k++) {
            double a = wheelAngle + k * Math.PI * 2 / 10;
            float ex = bx + (float) Math.cos(a) * R;
            float ey = hubY + (float) Math.sin(a) * R;
            paint.setColor(blend(0xFF4A4A5A, 0xFFDDDDE4, tod));
            c.drawLine(bx, hubY, ex, ey, paint);
            // Cabins hang below the rim.
            int col = SHIRTS[k % SHIRTS.length];
            paint.setColor(col);
            c.drawRoundRect(ex - s * 0.1f, ey, ex + s * 0.1f, ey + s * 0.16f, s * 0.04f, s * 0.04f, paint);
            if (night && landmarkDone) {
                paint.setColor(k % 2 == 0 ? 0xFFFFE6A8 : 0xFFFF7AA8);
                c.drawCircle(ex, ey, dp(2.2f), paint);
            }
        }
        paint.setColor(0xFF9AA5B1);
        c.drawCircle(bx, hubY, s * 0.08f, paint);
    }

    private void drawLighthouse(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float base = by - s * 0.12f;
        float towerTop = by - H * 0.78f;
        int bands = 5;
        for (int k = 0; k < bands; k++) {
            float t0 = k / (float) bands;
            float t1 = (k + 1) / (float) bands;
            float y0 = base + (towerTop - base) * t0;
            float y1 = base + (towerTop - base) * t1;
            float w0 = s * (0.36f - 0.14f * t0);
            float w1 = s * (0.36f - 0.14f * t1);
            path.reset();
            path.moveTo(bx - w0, y0);
            path.lineTo(bx + w0, y0);
            path.lineTo(bx + w1, y1);
            path.lineTo(bx - w1, y1);
            path.close();
            paint.setColor(k % 2 == 0 ? blend(0xFF5A2A26, 0xFFD2463A, tod) : blend(0xFF5A5C64, 0xFFF4F1E8, tod));
            c.drawPath(path, paint);
        }
        paint.setColor(0xFF2A3446);
        c.drawRect(bx - s * 0.3f, towerTop - s * 0.05f, bx + s * 0.3f, towerTop + s * 0.03f, paint);
        boolean lit = night && landmarkDone;
        paint.setColor(lit ? 0xFFFFF1C4 : 0xFF9FC4DA);
        c.drawRect(bx - s * 0.17f, towerTop - s * 0.34f, bx + s * 0.17f, towerTop - s * 0.05f, paint);
        path.reset();
        path.moveTo(bx - s * 0.24f, towerTop - s * 0.34f);
        path.lineTo(bx + s * 0.24f, towerTop - s * 0.34f);
        path.lineTo(bx, by - H);
        path.close();
        paint.setColor(0xFF2A3446);
        c.drawPath(path, paint);
        if (lit) {
            // A sweeping beam: its apparent length follows the turn of the lamp.
            float ly = towerTop - s * 0.2f;
            float reach = (float) Math.cos(beamAngle) * s * 5f;
            path.reset();
            path.moveTo(bx, ly);
            path.lineTo(bx + reach, ly - s * 0.35f);
            path.lineTo(bx + reach, ly + s * 0.35f);
            path.close();
            paint.setColor(0x33FFF1C4);
            c.drawPath(path, paint);
            Fx.glow(c, bx, ly, s * 0.7f, 0x88FFF1C4);
        }
    }

    private void drawObservatory(Canvas c, float bx, float by, float s, float H, float tod, boolean night) {
        float base = by - s * 0.12f;
        float drumTop = by - H * 0.6f;
        float r = s * 0.62f;
        paint.setColor(blend(0xFF3A3F4C, 0xFFD8D4CC, tod));
        c.drawRect(bx - r, drumTop, bx, base, paint);
        paint.setColor(blend(0xFF2E323C, 0xFFB5B0A6, tod));
        c.drawRect(bx, drumTop, bx + r, base, paint);
        paint.setColor(blend(0xFF4A5060, 0xFFE9EEF5, tod));
        c.drawArc(bx - r, drumTop - r, bx + r, drumTop + r, 180, 180, true, paint);
        // The dome turns: its slit drifts across and glows when the telescope is working at night.
        float slit = (float) Math.sin(sessionSeconds * 0.25) * r * 0.45f;
        boolean lit = night && landmarkDone;
        paint.setColor(lit ? 0xFFFFE6A8 : 0xFF20242C);
        c.drawRect(bx + slit - s * 0.07f, drumTop - r * 0.92f, bx + slit + s * 0.07f, drumTop, paint);
        if (lit) {
            paint.setColor(0xFF9AA5B1);
            paint.setStrokeWidth(Math.max(dp(2f), s * 0.06f));
            c.drawLine(bx + slit, drumTop - r * 0.5f, bx + slit + s * 0.3f, drumTop - r * 1.2f, paint);
        }
        paint.setColor(0xFF20242C);
        c.drawRect(bx - s * 0.1f, base - s * 0.3f, bx + s * 0.1f, base, paint);
    }

    /* ---------------------------------------------------------------- towers ---------- */

    private float depth(float gx, float gy) {
        float rx = (gx - ctrX) * gCos - (gy - ctrY) * gSin;
        float ry = (gx - ctrX) * gSin + (gy - ctrY) * gCos;
        return rx + ry;
    }

    /** Projects a world point into {@link #px}/{@link #py}; no allocation. */
    private void project(float gx, float gy, float z) {
        float rx = (gx - ctrX) * gCos - (gy - ctrY) * gSin;
        float ry = (gx - ctrX) * gSin + (gy - ctrY) * gCos;
        px = gCx + (rx - ry) * gTw;
        py = gCy + (rx + ry) * gTh - z * gBh;
    }

    /** One extruded prism from the ground to its height, with lit windows. */
    private void drawTower(Canvas c, int gx, int gy, float tod, int seedIdx) {
        int floors = height[gx][gy];
        int color = tint[gx][gy];
        float tw = gTw;
        float th = gTh;
        float bh = gBh;
        project(gx, gy, floors);
        float sx = px;
        float sy = py;
        float bodyH = floors * bh;

        int left = blend(color, 0xFF000000, 0.42f);
        int right = blend(color, 0xFF000000, 0.18f);
        int roof = blend(color, 0xFFFFFFFF, 0.16f);

        // Night lights switch on tower by tower as dusk falls rather than all at once.
        float night = 1f - tod;
        float stagger = ((seedIdx * 37) % 25) / 25f;
        float on = Math.max(0f, Math.min(1f, (night - 0.35f - stagger * 0.25f) * 5f));

        paint.setStyle(Paint.Style.FILL);
        if (doorGlow[gx][gy] > 0f) {
            Fx.glow(c, sx, sy + th + bodyH, tw * 0.6f, ((int) (doorGlow[gx][gy] * 0xAA) << 24) | 0xFFD27A);
        }
        // Left face.
        paint.setColor(left);
        path.reset();
        path.moveTo(sx - tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bodyH);
        path.lineTo(sx - tw, sy + bodyH);
        path.close();
        c.drawPath(path, paint);
        // Right face.
        paint.setColor(right);
        path.reset();
        path.moveTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bodyH);
        path.lineTo(sx + tw, sy + bodyH);
        path.close();
        c.drawPath(path, paint);
        // Roof.
        paint.setColor(roof);
        path.reset();
        path.moveTo(sx, sy - th);
        path.lineTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx - tw, sy);
        path.close();
        c.drawPath(path, paint);
        if (on > 0.05f) {
            // A lit crown on every tower you have built.
            int crown = blend(color, 0xFFFFFFFF, 0.65f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(((int) (on * 220) << 24) | (crown & 0xFFFFFF));
            c.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
            Fx.glow(c, sx, sy, tw * 0.9f, ((int) (on * 0x50) << 24) | (crown & 0xFFFFFF));
        }

        // Windows: one row per floor. Floors built this session burn gold after dark; the older
        // city lights up by the energy put in this session.
        long seed = windowSeed[seedIdx];
        int todayFrom = floors - sessionFloors[gx][gy];
        boolean cool = (seed & 1L) == 0;
        int litOld = cool ? 0xFFDDEBFF : 0xFFFFE8A8;
        int dark = blend(color, 0xFF000000, 0.55f);
        for (int f = 0; f < floors; f++) {
            float wy = sy + bodyH - f * bh - bh * 0.55f;
            for (int k = 0; k < 2; k++) {
                long bits = seed >>> ((f * 3 + k * 5) % 56);
                int wcol;
                if (f >= todayFrom) {
                    wcol = blend(dark, 0xFFFFC94A, Math.max(on, 0.15f));
                } else {
                    boolean lit = ((bits & 0xFFL) / 255f) < 0.45f + 0.55f * litShare;
                    wcol = lit ? blend(dark, litOld, on) : dark;
                }
                paint.setColor(wcol);
                float ox = tw * (0.32f + k * 0.34f);
                float wtop = wy + th * (1 - ox / tw) * 0.5f;
                c.drawRect(sx - ox - tw * 0.1f, wtop, sx - ox + tw * 0.1f, wtop + bh * 0.28f, paint);
                c.drawRect(sx + ox - tw * 0.1f, wtop, sx + ox + tw * 0.1f, wtop + bh * 0.28f, paint);
            }
        }
        // A mast with a blinking light on the tall ones.
        if (floors >= 8) {
            paint.setColor(0xFF9AA5B1);
            c.drawRect(sx - dp(1.5f), sy - th - dp(22f), sx + dp(1.5f), sy - th, paint);
            boolean blink = ((int) (activeSeconds * 1.5) % 2) == 0;
            paint.setColor(blink ? 0xFFFF4D4D : 0x66FF4D4D);
            c.drawCircle(sx, sy - th - dp(24f), dp(3f), paint);
        }
    }

    private void drawBlock(Canvas c, int gx, int gy, float z, int color) {
        project(gx, gy, z);
        float sx = px;
        float sy = py;
        float tw = gTw;
        float th = gTh;
        float bh = gBh;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(blend(color, 0xFF000000, 0.42f));
        path.reset();
        path.moveTo(sx - tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bh);
        path.lineTo(sx - tw, sy + bh);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(blend(color, 0xFF000000, 0.18f));
        path.reset();
        path.moveTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx, sy + th + bh);
        path.lineTo(sx + tw, sy + bh);
        path.close();
        c.drawPath(path, paint);
        paint.setColor(blend(color, 0xFFFFFFFF, 0.22f));
        path.reset();
        path.moveTo(sx, sy - th);
        path.lineTo(sx + tw, sy);
        path.lineTo(sx, sy + th);
        path.lineTo(sx - tw, sy);
        path.close();
        c.drawPath(path, paint);
    }

    /* ---------------------------------------------------------------- scenery ---------- */

    /**
     * 3.19.5: the sky was empty. Clouds drift by day, a plane crosses with blinking lights and a
     * contrail, a flock passes, and at night searchlights sweep and the odd shooting star falls.
     */
    private void drawSkyTraffic(Canvas c, float w, float h, float tod) {
        double t = sessionSeconds;
        int cloudAlpha = (int) (60 + 150 * tod);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 5; i++) {
            float span = w + dp(400f);
            float cx = (float) (((i * 431 + 60) + t * dp(6f + i * 2f)) % span) - dp(200f);
            float cy = h * (0.10f + (i % 3) * 0.08f);
            float sc = 0.7f + (i % 2) * 0.4f;
            paint.setColor((cloudAlpha << 24) | 0xFFFFFF);
            c.drawOval(cx - dp(80f) * sc, cy - dp(12f) * sc, cx + dp(80f) * sc, cy + dp(12f) * sc, paint);
            c.drawOval(cx - dp(38f) * sc, cy - dp(28f) * sc, cx + dp(40f) * sc, cy + dp(4f) * sc, paint);
        }
        // A plane every 24 s, left to right, high up.
        double lap = (t % 24) / 24;
        float px0 = (float) (-dp(80f) + (w + dp(160f)) * lap);
        float py0 = h * 0.08f + (float) lap * h * 0.04f;
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0x55FFFFFF);
        c.drawLine(px0 - dp(160f), py0 + dp(3f), px0 - dp(20f), py0, paint);
        paint.setColor(tod > 0.4f ? 0xFFE9EEF5 : 0xFF3A4660);
        c.drawRoundRect(px0 - dp(18f), py0 - dp(3f), px0 + dp(18f), py0 + dp(3f), dp(3f), dp(3f), paint);
        c.drawRect(px0 - dp(4f), py0 - dp(10f), px0 + dp(4f), py0 + dp(10f), paint);
        if (((int) (t * 2)) % 2 == 0) {
            paint.setColor(0xFFFF4A4A);
            c.drawCircle(px0 - dp(1f), py0 - dp(10f), dp(2.5f), paint);
            paint.setColor(0xFF4AFF7A);
            c.drawCircle(px0 - dp(1f), py0 + dp(10f), dp(2.5f), paint);
        }
        if (tod > 0.3f) {
            // Birds by day.
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xAA1A2230);
            float fx0 = (float) (w - ((t * dp(40f)) % (w + dp(300f))));
            for (int b = 0; b < 6; b++) {
                float bx = fx0 + b * dp(22f) + (b % 2) * dp(8f);
                float by = h * 0.22f + (b % 3) * dp(9f);
                float flap = (float) Math.sin(t * 8 + b) * dp(4f);
                c.drawLine(bx - dp(7f), by - flap, bx, by, paint);
                c.drawLine(bx, by, bx + dp(7f), by - flap, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        } else {
            // Searchlights from the city, and a shooting star now and then.
            for (int k = 0; k < 2; k++) {
                double a = -Math.PI / 2 + Math.sin(t * 0.5 + k * 2.2) * 0.5;
                float ox = w * (k == 0 ? 0.38f : 0.62f);
                float oy = h * 0.72f;
                float len = h * 0.9f;
                float tx = ox + (float) Math.cos(a) * len;
                float ty = oy + (float) Math.sin(a) * len;
                float nx = (float) -Math.sin(a) * dp(40f);
                float ny = (float) Math.cos(a) * dp(40f);
                path.reset();
                path.moveTo(ox, oy);
                path.lineTo(tx + nx, ty + ny);
                path.lineTo(tx - nx, ty - ny);
                path.close();
                paint.setColor(0x1ADFEBFF);
                c.drawPath(path, paint);
            }
            double star = (t % 9) / 9;
            if (star < 0.12) {
                float f = (float) (star / 0.12);
                float sx = w * 0.2f + f * w * 0.35f;
                float sy = h * 0.06f + f * h * 0.14f;
                paint.setStrokeWidth(dp(2f));
                paint.setColor(((int) (255 * (1 - f)) << 24) | 0xFFFFFF);
                c.drawLine(sx - dp(60f), sy - dp(24f), sx, sy, paint);
            }
        }
    }

    /**
     * 3.19.7: the left third of the tablet screen was bare beside the crane. A site fence with a
     * gate, a parked truck and a site hut fill it, a foreman walks the line, and floodlights wash
     * the ground at night - the building site the crane obviously belongs to.
     */
    private void drawSiteLife(Canvas c, float w, float h, float tod) {
        double t = activeSeconds;
        float ground = h * 0.86f;
        float left = w * 0.02f;
        float right = w * 0.30f;
        // Floodlights after dark.
        if (tod < 0.5f) {
            for (int i = 0; i < 2; i++) {
                float lx = left + dp(40f) + i * dp(120f);
                Fx.glow(c, lx, ground - dp(10f), dp(90f), 0x33FFE6A8);
                paint.setColor(0xFF2A3446);
                c.drawRect(lx - dp(2f), ground - dp(70f), lx + dp(2f), ground, paint);
                paint.setColor(0xFFFFE6A8);
                c.drawRect(lx - dp(9f), ground - dp(78f), lx + dp(9f), dp(6f) + ground - dp(78f), paint);
            }
        }
        // Site hut.
        paint.setColor(0xFF6E7684);
        c.drawRect(left, ground - dp(46f), left + dp(86f), ground, paint);
        paint.setColor(0xFF4C535E);
        c.drawRect(left, ground - dp(52f), left + dp(86f), ground - dp(44f), paint);
        paint.setColor(tod < 0.5f ? 0xFFFFE6A8 : 0xFF2A3446);
        c.drawRect(left + dp(12f), ground - dp(36f), left + dp(30f), ground - dp(20f), paint);
        paint.setColor(0xFF35404E);
        c.drawRect(left + dp(52f), ground - dp(34f), left + dp(72f), ground, paint);
        // Fence with a gap for the gate.
        paint.setColor(0xFF5A6472);
        for (float fx0 = left + dp(96f); fx0 < right; fx0 += dp(18f)) {
            if (fx0 > left + dp(150f) && fx0 < left + dp(196f)) {
                continue;
            }
            c.drawRect(fx0, ground - dp(30f), fx0 + dp(3f), ground, paint);
        }
        c.drawRect(left + dp(96f), ground - dp(30f), right, ground - dp(27f), paint);
        // Parked truck with a tipper bed.
        float tx = right - dp(74f);
        paint.setColor(0xFFE0582E);
        c.drawRoundRect(tx, ground - dp(30f), tx + dp(34f), ground - dp(8f), dp(3f), dp(3f), paint);
        paint.setColor(0xFF9AA5B1);
        c.drawRect(tx + dp(34f), ground - dp(24f), tx + dp(72f), ground - dp(8f), paint);
        paint.setColor(0xFF2A3446);
        c.drawCircle(tx + dp(12f), ground - dp(6f), dp(6f), paint);
        c.drawCircle(tx + dp(56f), ground - dp(6f), dp(6f), paint);
        // Foreman pacing the fence line, turning at each end.
        float span = right - left - dp(130f);
        float walk = (float) ((t * dp(26f)) % (span * 2));
        float fxp = left + dp(104f) + (walk < span ? walk : span * 2 - walk);
        boolean facing = walk < span;
        float step = (float) Math.sin(t * 6) * dp(4f);
        paint.setColor(0xFFF5C518);
        c.drawRect(fxp - dp(5f), ground - dp(26f), fxp + dp(5f), ground - dp(12f), paint);
        paint.setColor(0xFF2A2F3A);
        c.drawRect(fxp - dp(4f) + step, ground - dp(12f), fxp - dp(1f) + step, ground, paint);
        c.drawRect(fxp + dp(1f) - step, ground - dp(12f), fxp + dp(4f) - step, ground, paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(fxp, ground - dp(31f), dp(5f), paint);
        paint.setColor(0xFFF5C518);
        c.drawArc(fxp - dp(6f), ground - dp(39f), fxp + dp(6f), ground - dp(27f), 180, 180, true, paint);
        paint.setColor(0xFF2A2F3A);
        c.drawCircle(fxp + (facing ? dp(2f) : -dp(2f)), ground - dp(31f), dp(1.2f), paint);
    }

    /* ---------------------------------------------------------------- HUD ---------- */

    private void drawHud(Canvas c, float w, float h) {
        bold(c, String.valueOf(lifetime), w * 0.5f, dp(34f), 34f, ACCENT, Paint.Align.CENTER);
        label(c, "BLOCKS IN THE CITY", w * 0.5f, dp(48f), 9f, FAINT, Paint.Align.CENTER);
        String next = null;
        if (lifetime < PARK_AT) {
            next = "PARK OPENS IN " + (PARK_AT - lifetime) + " BLOCKS";
        } else if (lifetime < BRIDGE_AT) {
            next = "RIVER AND BRIDGE OPEN IN " + (BRIDGE_AT - lifetime) + " BLOCKS";
        } else if (lifetime >= GRID * GRID * MAX_HEIGHT) {
            next = "EVERY PLOT IS BUILT - ROW FOR THE LANDMARK";
        }
        if (next != null) {
            bold(c, next, w * 0.5f, dp(66f), 10f, WARN, Paint.Align.CENTER);
        }

        drawCrane(c, w, h);
        drawTodayTower(c, w, h);
        drawLandmarkPanel(c, w);
        drawPhotoButton(c);

        float fy = h - dp(12f);
        float col = w / 4f;
        stat(c, col * 0.5f, fy, String.valueOf(placedThisSession), "THIS SESSION");
        stat(c, col * 1.5f, fy, tallest + " floors", "TALLEST");
        stat(c, col * 2.5f, fy, String.valueOf(population), "RESIDENTS");
        stat(c, col * 3.5f, fy, falling.isEmpty() ? "--" : String.valueOf(falling.size()), "IN THE AIR");
    }

    private void drawLandmarkPanel(Canvas c, float w) {
        float x0 = w - dp(360f);
        float x1 = w - dp(100f);
        float y = dp(22f);
        label(c, "THIS WEEK'S LANDMARK", x0, y, 8.5f, FAINT, Paint.Align.LEFT);
        label(c, landmarksBuilt + " BUILT", x1, y, 8.5f, FAINT, Paint.Align.RIGHT);
        bold(c, LANDMARK_NAMES[landmarkKind], x0, y + dp(18f), 14f, TEXT, Paint.Align.LEFT);
        float f = landmarkFraction();
        float by = y + dp(26f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(x0, by, x1, by + dp(9f), dp(4.5f), dp(4.5f), paint);
        paint.setColor(landmarkDone ? ACCENT : WARN);
        c.drawRoundRect(x0, by, x0 + (x1 - x0) * f, by + dp(9f), dp(4.5f), dp(4.5f), paint);
        long now = System.currentTimeMillis();
        long localDays = (now + zone.getOffset(now)) / 86400000L;
        int daysLeft = (int) (7 - (localDays + 3) % 7);
        String line;
        if (landmarkDone) {
            line = "BUILT - A NEW ONE STARTS MONDAY";
        } else {
            double minutes = (landmarkTarget - landmarkWork) / Math.max(1.0, profile.typicalWatts()) / 60.0;
            line = Math.round(f * 100) + "%  -  ~" + (int) Math.ceil(minutes) + " MIN OF ROWING  -  "
                    + daysLeft + (daysLeft == 1 ? " DAY LEFT" : " DAYS LEFT");
        }
        label(c, line, x0, by + dp(22f), 8.5f, landmarkDone ? ACCENT : DIM, Paint.Align.LEFT);
        if (!landmarkDone) {
            label(c, "hard strokes lay gold stones", x0, by + dp(35f), 8f, FAINT, Paint.Align.LEFT);
        }
    }

    private void drawPhotoButton(Canvas c) {
        photoBtn.set(dp(14f), dp(12f), dp(118f), dp(46f));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        c.drawRoundRect(photoBtn, dp(8f), dp(8f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(0x88E6EDF7);
        c.drawRoundRect(photoBtn, dp(8f), dp(8f), paint);
        // Camera glyph.
        float gx = photoBtn.left + dp(22f);
        float gy = photoBtn.centerY();
        c.drawRoundRect(gx - dp(10f), gy - dp(7f), gx + dp(10f), gy + dp(8f), dp(2f), dp(2f), paint);
        c.drawCircle(gx, gy + dp(0.5f), dp(4f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, "PHOTO", photoBtn.left + dp(40f), gy + dp(5f), 12f, TEXT, Paint.Align.LEFT);
    }

    private void drawBanner(Canvas c, float w, float h) {
        if (banner == null || bannerT <= 0f) {
            return;
        }
        float a = Math.min(1f, bannerT / 0.6f) * Math.min(1f, (3.5f - bannerT) / 0.3f + 0.2f);
        int alpha = (int) (255 * Math.max(0f, Math.min(1f, a)));
        float y = h * 0.30f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(((alpha * 150 / 255) << 24));
        c.drawRect(0, y - dp(38f), w, y + dp(16f), paint);
        bold(c, banner, w * 0.5f, y, 30f, (alpha << 24) | (WARN & 0xFFFFFF), Paint.Align.CENTER);
    }

    /** The crane and its hopper, top left: every drive pours concrete in. */
    private void drawCrane(Canvas c, float w, float h) {
        float baseX = dp(40f);
        float baseY = h * 0.62f;
        float topY = h * 0.14f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFF0B132);
        c.drawRect(baseX - dp(4f), topY, baseX + dp(4f), baseY, paint);
        for (float y = topY + dp(12f); y < baseY; y += dp(18f)) {
            paint.setStrokeWidth(dp(1.5f));
            c.drawLine(baseX - dp(4f), y, baseX + dp(4f), y + dp(12f), paint);
        }
        c.drawRect(baseX - dp(10f), topY - dp(4f), baseX + dp(150f), topY + dp(4f), paint);
        float hookX = baseX + dp(120f);
        paint.setColor(0xFF9AA5B1);
        paint.setStrokeWidth(dp(1.5f));
        c.drawLine(hookX, topY + dp(4f), hookX, topY + dp(46f), paint);
        // Hopper: fills with concrete toward the next block.
        float hopW = dp(64f);
        float hopH = dp(54f);
        float hx = hookX - hopW / 2f;
        float hy = topY + dp(46f);
        paint.setColor(0x55FFFFFF);
        c.drawRect(hx, hy, hx + hopW, hy + hopH, paint);
        float full = Math.min(1f, concrete / cost(nextUnits));
        paint.setColor(nextUnits >= 3 ? ACCENT : nextUnits == 2 ? BLUE : WARN);
        c.drawRect(hx, hy + hopH * (1f - full), hx + hopW, hy + hopH, paint);
        bold(c, nextUnits + (nextUnits == 1 ? " FLOOR" : " FLOORS"), hookX, hy + hopH + dp(16f), 11f,
                nextUnits > 1 ? ACCENT : TEXT, Paint.Align.CENTER);
        boolean working = isClockRunning();
        label(c, working ? "pull harder for bigger blocks" : "CRANES IDLE - ROW TO BUILD", hookX,
                hy + hopH + dp(30f), 8.5f, working ? FAINT : WARN, Paint.Align.CENTER);
    }

    /** This session's floors as their own tower on the right, windows lit by today's energy. */
    private void drawTodayTower(Canvas c, float w, float h) {
        float floorH = dp(9f);
        float bw = dp(48f);
        float right = w - dp(22f);
        float bottom = h - dp(56f);
        int floors = Math.min(placedThisSession, (int) ((bottom - h * 0.12f) / floorH));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF2A3648);
        c.drawRect(right - bw - dp(6f), bottom, right + dp(6f), bottom + dp(4f), paint);
        for (int f = 0; f < floors; f++) {
            float y = bottom - (f + 1) * floorH;
            paint.setColor(0xFF3E5A7E);
            c.drawRect(right - bw, y, right, y + floorH - dp(1f), paint);
            boolean lit = ((f * 7919) % 100) / 100f < 0.2f + 0.8f * litShare;
            paint.setColor(lit ? 0xFFFFE8A8 : 0xFF22324A);
            c.drawRect(right - bw + dp(8f), y + dp(2f), right - bw + dp(18f), y + floorH - dp(3f), paint);
            c.drawRect(right - dp(18f), y + dp(2f), right - dp(8f), y + floorH - dp(3f), paint);
        }
        bold(c, "+" + placedThisSession, right - bw / 2f, bottom - floors * floorH - dp(8f), 13f, ACCENT,
                Paint.Align.CENTER);
        label(c, "TODAY", right - bw / 2f, bottom + dp(16f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 15f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }

    /* ---------------------------------------------------------------- photo mode ---------- */

    private void drawPhotoUi(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        bold(c, "PHOTO MODE", dp(18f), dp(30f), 14f, TEXT, Paint.Align.LEFT);
        label(c, "drag to turn the city, tap the shutter to snap it", dp(18f), dp(46f), 9f, DIM,
                Paint.Align.LEFT);

        float barTop = h - dp(100f);
        paint.setColor(0x88000000);
        c.drawRect(0, barTop, w, h, paint);

        // Album thumbnails, newest first.
        float tw = dp(112f);
        float th = dp(50f);
        float x = dp(18f);
        float ty = barTop + (h - barTop - th) / 2f;
        int shown = Math.min(album.size(), thumbRects.length);
        for (int i = 0; i < thumbRects.length; i++) {
            thumbRects[i].setEmpty();
        }
        float maxRight = w * 0.5f - dp(60f);
        for (int i = 0; i < shown; i++) {
            if (x + tw > maxRight) {
                break;
            }
            Photo p = album.get(i);
            thumbRects[i].set(x, ty, x + tw, ty + th);
            paint.setColor(0xFFFFFFFF);
            c.drawBitmap(p.thumb, null, thumbRects[i], paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1f));
            paint.setColor(0x88E6EDF7);
            c.drawRect(thumbRects[i], paint);
            paint.setStyle(Paint.Style.FILL);
            x += tw + dp(8f);
        }
        if (shown == 0) {
            label(c, "YOUR ALBUM IS EMPTY", dp(18f), barTop + dp(54f), 9f, FAINT, Paint.Align.LEFT);
        }

        // Shutter.
        snapX = w * 0.5f;
        snapY = barTop + (h - barTop) / 2f;
        snapR = dp(32f);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(snapX, snapY, snapR, paint);
        paint.setColor(0xFF20242C);
        c.drawCircle(snapX, snapY, snapR - dp(4f), paint);
        paint.setColor(flash > 0f ? ACCENT : 0xFFFFFFFF);
        c.drawCircle(snapX, snapY, snapR - dp(7f), paint);

        doneBtn.set(w - dp(150f), snapY - dp(20f), w - dp(24f), snapY + dp(20f));
        paint.setColor(0x33FFFFFF);
        c.drawRoundRect(doneBtn, dp(8f), dp(8f), paint);
        bold(c, "DONE", doneBtn.centerX(), doneBtn.centerY() + dp(5f), 14f, TEXT, Paint.Align.CENTER);

        if (photoNoteT > 0f && photoNote != null) {
            bold(c, photoNote, w * 0.5f, barTop - dp(14f), 12f, ACCENT, Paint.Align.CENTER);
        }
    }

    private void drawViewer(Canvas c, float w, float h) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xE6000000);
        c.drawRect(0, 0, w, h, paint);
        float maxW = w * 0.9f;
        float maxH = h * 0.82f;
        float scale = Math.min(maxW / viewing.getWidth(), maxH / viewing.getHeight());
        float vw = viewing.getWidth() * scale;
        float vh = viewing.getHeight() * scale;
        viewRect.set((w - vw) / 2f, (h - vh) / 2f - dp(10f), (w + vw) / 2f, (h + vh) / 2f - dp(10f));
        paint.setColor(0xFFFFFFFF);
        c.drawBitmap(viewing, null, viewRect, paint);
        label(c, "tap to close", w * 0.5f, viewRect.bottom + dp(22f), 10f, DIM, Paint.Align.CENTER);
    }

    private File photoDir() {
        File dir = new File(getContext().getFilesDir(), "skyline-photos");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Renders the city (no HUD) at half resolution with a caption, into the album and a PNG file. */
    private void snap() {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        final Bitmap full;
        try {
            full = Bitmap.createBitmap(w / 2, h / 2, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            photoNote = "NOT ENOUGH MEMORY FOR A PHOTO";
            photoNoteT = 2.5f;
            return;
        }
        Canvas pc = new Canvas(full);
        pc.scale(0.5f, 0.5f);
        drawWorld(pc, w, h, true);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x99000000);
        pc.drawRect(0, h - dp(44f), w, h, paint);
        String date = new java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
                .format(new java.util.Date());
        bold(pc, "SKYLINE", dp(18f), h - dp(15f), 18f, ACCENT, Paint.Align.LEFT);
        label(pc, lifetime + " blocks  -  " + population + " residents  -  tallest " + tallest
                + " floors  -  " + date, dp(120f), h - dp(15f), 13f, TEXT, Paint.Align.LEFT);

        Photo p = new Photo();
        p.full = full;
        p.thumb = Bitmap.createScaledBitmap(full, Math.max(1, w / 8), Math.max(1, h / 8), true);
        p.file = new File(photoDir(), "skyline-" + System.currentTimeMillis() + ".png");
        for (Photo old : album) {
            old.full = null;
        }
        album.add(0, p);
        while (album.size() > thumbRects.length) {
            album.remove(album.size() - 1);
        }
        flash = 1f;
        photoNote = "SNAPPED - " + album.size() + (album.size() == 1 ? " PHOTO" : " PHOTOS") + " IN THE ALBUM";
        photoNoteT = 2.5f;
        final File file = p.file;
        final File dir = file.getParentFile();
        new Thread(() -> {
            try (FileOutputStream out = new FileOutputStream(file)) {
                full.compress(Bitmap.CompressFormat.PNG, 100, out);
            } catch (Exception ignored) {
                // The in-game album still holds it for this session.
            }
            // Keep the newest dozen on the tablet.
            File[] files = dir == null ? null : dir.listFiles();
            if (files != null && files.length > 12) {
                java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
                for (int i = 12; i < files.length; i++) {
                    files[i].delete();
                }
            }
        }, "skyline-photo").start();
    }

    /** Loads the saved album once, off the UI thread. */
    private void loadAlbum() {
        if (albumLoaded) {
            return;
        }
        albumLoaded = true;
        final File dir = photoDir();
        new Thread(() -> {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            java.util.Arrays.sort(files, (a, b) -> b.getName().compareTo(a.getName()));
            final java.util.ArrayList<Photo> loaded = new java.util.ArrayList<>();
            for (int i = 0; i < files.length && loaded.size() < thumbRects.length; i++) {
                if (!files[i].getName().endsWith(".png")) {
                    continue;
                }
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap b = BitmapFactory.decodeFile(files[i].getAbsolutePath(), o);
                if (b != null) {
                    Photo p = new Photo();
                    p.file = files[i];
                    p.thumb = b;
                    loaded.add(p);
                }
            }
            post(() -> {
                for (Photo p : loaded) {
                    boolean dup = false;
                    for (Photo q : album) {
                        if (q.file != null && q.file.equals(p.file)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup && album.size() < thumbRects.length) {
                        album.add(p);
                    }
                }
            });
        }, "skyline-album").start();
    }

    private void openPhoto(Photo p) {
        if (p.full != null) {
            viewing = p.full;
            return;
        }
        Bitmap b = p.file != null && p.file.exists() ? BitmapFactory.decodeFile(p.file.getAbsolutePath()) : null;
        viewing = b != null ? b : p.thumb;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                lastTouchX = x;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                if (photoMode && viewing == null) {
                    if (Math.abs(x - downX) > dp(10f)) {
                        dragging = true;
                    }
                    if (dragging) {
                        angle += (x - lastTouchX) / dp(240f);
                    }
                }
                lastTouchX = x;
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    handleTap(x, y);
                    performClick();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void handleTap(float x, float y) {
        if (viewing != null) {
            viewing = null;
            return;
        }
        if (!photoMode) {
            if (photoBtn.contains(x, y)) {
                photoMode = true;
                loadAlbum();
            }
            return;
        }
        float dx = x - snapX;
        float dy = y - snapY;
        if (dx * dx + dy * dy <= snapR * snapR * 1.7f) {
            snap();
            return;
        }
        if (doneBtn.contains(x, y)) {
            photoMode = false;
            return;
        }
        for (int i = 0; i < thumbRects.length && i < album.size(); i++) {
            if (thumbRects[i].contains(x, y)) {
                openPhoto(album.get(i));
                return;
            }
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
