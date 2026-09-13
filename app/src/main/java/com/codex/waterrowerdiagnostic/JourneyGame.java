package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;

/**
 * Distance Journey: every metre from every session moves you along a chain of real courses.
 *
 * <p>Short midweek rows add up to something: "you are 4.4km along the Boat Race course, 1.3km to
 * Barnes Bridge". Distance comes from the monitor via the activity's journey total, so rows in
 * the gauge screen and in other games all count.
 */
final class JourneyGame extends GameView {

    static final class Route {
        final String name;
        final int meters;
        final String[] landmarks;
        final int[] landmarkMeters;

        Route(String name, int meters, String[] landmarks, int[] landmarkMeters) {
            this.name = name;
            this.meters = meters;
            this.landmarks = landmarks;
            this.landmarkMeters = landmarkMeters;
        }
    }

    static final Route[] ROUTES = {
            new Route("THE BOAT RACE  ·  Putney to Mortlake", 6779,
                    new String[] {"Putney", "Fulham", "Hammersmith Br", "Chiswick Eyot", "Barnes Br", "Mortlake"},
                    new int[] {0, 800, 3000, 4400, 5700, 6779}),
            new Route("HENLEY  ·  Temple Island to the finish", 2112,
                    new String[] {"Temple Island", "Fawley", "Remenham", "Finish"},
                    new int[] {0, 700, 1400, 2112}),
            new Route("WINDERMERE  ·  end to end", 18000,
                    new String[] {"Ambleside", "Wray", "Belle Isle", "Storrs", "Lakeside"},
                    new int[] {0, 4000, 8500, 13000, 18000}),
            new Route("THE CHANNEL  ·  Dover to Calais", 33800,
                    new String[] {"Dover", "Shipping lane", "Mid-channel", "Cap Gris-Nez", "Calais"},
                    new int[] {0, 9000, 16900, 27000, 33800}),
            new Route("LOCH NESS  ·  Fort Augustus to Dores", 36300,
                    new String[] {"Fort Augustus", "Invermoriston", "Urquhart Castle", "Dores"},
                    new int[] {0, 9000, 22000, 36300}),
    };

    private final Paint routePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint donePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path routePath = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] pos = new float[2];

    private double totalMeters;
    private double sessionStart;
    private int lastW = -1;

    JourneyGame(Context context) {
        super(context);
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(dp(5f));
        routePaint.setStrokeCap(Paint.Cap.ROUND);
        routePaint.setColor(0xFF1F2E44);
        donePaint.setStyle(Paint.Style.STROKE);
        donePaint.setStrokeWidth(dp(5f));
        donePaint.setStrokeCap(Paint.Cap.ROUND);
        donePaint.setColor(ACCENT);
    }

    /** Fed by the activity: lifetime metres including this session so far. */
    void setTotalMeters(double total) {
        if (sessionStart == 0 && total > 0) {
            sessionStart = total;
        }
        totalMeters = total;
        postInvalidateOnAnimation();
    }

    private int routeIndex() {
        double remaining = totalMeters;
        int i = 0;
        while (i < ROUTES.length - 1 && remaining >= ROUTES[i].meters) {
            remaining -= ROUTES[i].meters;
            i++;
        }
        return i;
    }

    private double metersIntoRoute() {
        double remaining = totalMeters;
        for (int i = 0; i < routeIndex(); i++) {
            remaining -= ROUTES[i].meters;
        }
        return Math.min(remaining, ROUTES[routeIndex()].meters);
    }

    private void buildPath(float w, float h) {
        // A lazy S-curve across the screen: reads as a river, cheap to measure along.
        float left = dp(40f);
        float right = w - dp(40f);
        float top = h * 0.30f;
        float bottom = h * 0.62f;
        routePath.reset();
        routePath.moveTo(left, bottom);
        routePath.cubicTo(w * 0.30f, bottom, w * 0.25f, top, w * 0.5f, top + (bottom - top) * 0.45f);
        routePath.cubicTo(w * 0.75f, bottom, w * 0.70f, top, right, top);
        measure.setPath(routePath, false);
        lastW = (int) w;
    }

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if ((int) w != lastW) {
            buildPath(w, h);
        }
        Route route = ROUTES[routeIndex()];
        double into = metersIntoRoute();
        float frac = (float) Math.min(1.0, into / route.meters);
        float len = measure.getLength();

        label(c, "ROUTE " + (routeIndex() + 1) + " OF " + ROUTES.length, w / 2f, dp(22f), 9f, FAINT,
                Paint.Align.CENTER);
        bold(c, route.name, w / 2f, dp(44f), 15f, TEXT, Paint.Align.CENTER);

        c.drawPath(routePath, routePaint);
        if (frac > 0.002f) {
            Path done = new Path();
            measure.getSegment(0, len * frac, done, true);
            c.drawPath(done, donePaint);
        }

        // Landmarks along the path.
        for (int i = 0; i < route.landmarks.length; i++) {
            float f = (float) route.landmarkMeters[i] / route.meters;
            measure.getPosTan(len * f, pos, null);
            boolean passed = into >= route.landmarkMeters[i];
            dotPaint.setColor(passed ? ACCENT : 0xFF2A3648);
            c.drawCircle(pos[0], pos[1], dp(6f), dotPaint);
            dotPaint.setColor(0xFF0A0E14);
            c.drawCircle(pos[0], pos[1], dp(3f), dotPaint);
            label(c, route.landmarks[i], pos[0], pos[1] + (i % 2 == 0 ? dp(20f) : -dp(14f)), 8.5f,
                    passed ? DIM : FAINT, Paint.Align.CENTER);
        }

        // You.
        measure.getPosTan(len * frac, pos, null);
        dotPaint.setColor(ACCENT);
        c.drawCircle(pos[0], pos[1], dp(11f), dotPaint);
        dotPaint.setColor(0xFFE6EDF7);
        c.drawCircle(pos[0], pos[1], dp(5f), dotPaint);

        // Next landmark and progress.
        String next = "";
        double toNext = 0;
        for (int i = 0; i < route.landmarks.length; i++) {
            if (route.landmarkMeters[i] > into) {
                next = route.landmarks[i];
                toNext = route.landmarkMeters[i] - into;
                break;
            }
        }
        float y = h * 0.74f;
        bold(c, String.format(java.util.Locale.US, "%.1f km", into / 1000.0), w * 0.25f, y, 26f,
                ACCENT, Paint.Align.CENTER);
        label(c, "ALONG THIS ROUTE", w * 0.25f, y + dp(16f), 9f, FAINT, Paint.Align.CENTER);
        if (!next.isEmpty()) {
            bold(c, String.format(java.util.Locale.US, "%.0f m", toNext), w * 0.75f, y, 26f, TEXT,
                    Paint.Align.CENTER);
            label(c, "TO " + next.toUpperCase(java.util.Locale.US), w * 0.75f, y + dp(16f), 9f,
                    FAINT, Paint.Align.CENTER);
            float speed = boat.value();
            if (speed > 0.5f) {
                label(c, "about " + clock(toNext / speed) + " at this pace", w * 0.75f,
                        y + dp(30f), 9f, DIM, Paint.Align.CENTER);
            }
        } else {
            bold(c, "ROUTE COMPLETE", w * 0.75f, y, 18f, WARN, Paint.Align.CENTER);
        }

        float fy = h - dp(12f);
        float col = w / 3f;
        stat(c, col * 0.5f, fy, String.format(java.util.Locale.US, "%.1f km", totalMeters / 1000.0),
                "LIFETIME");
        stat(c, col * 1.5f, fy, String.format(java.util.Locale.US, "%.0f m",
                Math.max(0, totalMeters - sessionStart)), "THIS SESSION");
        stat(c, col * 2.5f, fy, pace(boat.value()), "PACE /500");
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
