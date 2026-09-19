package com.codex.waterrowerdiagnostic;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.Locale;

/**
 * Coast Flight: a Cesium globe flown down the California coast by the rower, running inside the
 * app rather than in a browser.
 *
 * <p>It is a {@link GameView} like every other screen, which is the point: it gets the coasted
 * {@link BoatSpeedModel} speed, the smoothed session distance and the 15-second rowing clock for
 * free, and the shared vitals strip sits above it. The globe itself is WebGL, so the visible part
 * is a {@link WebView} stacked on top of this view; this view only draws the pre-load and failure
 * states, which is all the user ever sees of it.
 *
 * <p>Data does not go near the network. {@link #render} pushes a small object into the page about
 * twelve times a second with values that are already correct - in particular the coasted speed,
 * not the monitor's held average. Persistence goes the other way through {@link Bridge} into
 * SharedPreferences, so the trip accumulates with the laptop switched off.
 *
 * <p>Two things are fetched from the internet and nothing else: the CesiumJS library and map
 * tiles. The user asked for real maps; see CLAUDE.md.
 */
class CoastFlightGame extends GameView {

    /** What the flight needs from the activity. */
    interface Host {
        /** The Cesium Ion token, or empty - the page then offers OpenStreetMap only. */
        String ionToken();

        /** The page asked to go back. */
        void onFlightHome();

        /** Something worth seeing from the laptop: the engine in use, or a failure. */
        void onFlightReport(String stage, String detail);
    }

    private static final String PAGE = "file:///android_asset/coastflight/fly.html";
    static final String TRIP_KEY = "flight.along";
    private static final String MAP_KEY = "flight.map";
    /** Last session's flight, one sample per rowing second, raced as a ghost bird. Not a record. */
    static final String GHOST_KEY = "flight.ghost";
    /** Best stars per postcard stop, "id:stars,id:stars". Not a record; the total is. */
    static final String CARDS_KEY = "flight.cards";
    /** Records: the best stars summed over every postcard stop, and the most ridge lift in a session. */
    static final String POSTCARDS_KEY = "coast.postcards";
    static final String LIFT_KEY = "coast.lift";
    /** A ghost is at most an hour of per-second samples; anything larger is refused, not trimmed. */
    private static final int MAX_GHOST_CHARS = 120000;
    /** ~12Hz. The page eases between samples, so pushing every frame would only burn CPU. */
    private static final long PUSH_INTERVAL_MS = 80;
    private static final int MAX_CONSOLE_REPORTS = 12;
    /** Keeps the frame loop alive; see {@link #tick}. */
    private static final long TICK_MS = 100;

    private final WebView web;
    private final PersonalBests bests;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean pageReady;
    private String notice = "Starting the globe…";
    private boolean loadStarted;
    private boolean failed;
    private long lastPushMs;
    private int consoleReports;
    private boolean ticking;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    CoastFlightGame(Context context, PersonalBests bests, Host host) {
        super(context);
        this.bests = bests;
        this.host = host;
        setBackgroundColor(Color.parseColor("#03080F"));

        web = new WebView(context);
        web.setBackgroundColor(Color.parseColor("#03080F"));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        // The page is our own asset on a file:// origin, but CesiumJS and every map tile come
        // from https. Without universal access the library cannot fetch its own workers and the
        // globe stays black. Nothing remote is executed except CesiumJS itself, over TLS.
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // Let the WebView cache keep the 4MB library between entries.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setHorizontalScrollBarEnabled(false);
        web.setVerticalScrollBarEnabled(false);
        web.addJavascriptInterface(new Bridge(this), "WakeNative");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                // There is no emulator for this tablet, so a page error has to be visible from
                // the laptop or it is invisible full stop. Errors only, and capped.
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                        && consoleReports < MAX_CONSOLE_REPORTS) {
                    consoleReports++;
                    report("console", message.message() + " @" + message.lineNumber());
                }
                return true;
            }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    fail("The flight page did not load.");
                    report("page-error", String.valueOf(error != null ? error.getDescription() : ""));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // The page never navigates. Anything trying to is a link, and a link would
                // strand the user in a browser on a tablet with no app switcher.
                return true;
            }
        });
    }

    /**
     * Start the page over. Used when the Ion token arrives after the page loaded without one:
     * the map tiers are decided at load time, so a reload is what promotes OSM to satellite.
     */
    void reloadPage() {
        pageReady = false;
        failed = false;
        notice = "Loading the maps\u2026";
        web.setVisibility(View.VISIBLE);
        web.loadUrl(PAGE);
    }

    /** The visible globe. The activity places this above the view in a stack. */
    View webView() {
        return web;
    }

    @Override
    protected void onStart() {
        pageReady = false;
        consoleReports = 0;
        notice = "Starting the globe…";
        web.setVisibility(View.VISIBLE);
        if (!ticking) {
            ticking = true;
            ui.post(tick);
        }
        if (!loadStarted || failed) {
            loadStarted = true;
            failed = false;
            web.loadUrl(PAGE);
        } else {
            web.onResume();
        }
    }

    @Override
    protected void onStop() {
        ticking = false;
        ui.removeCallbacks(tick);
        // Bank the trip before the screen goes; the page saves periodically but not on the way out.
        web.evaluateJavascript("window.wakeSave&&window.wakeSave();", null);
        web.onPause();
        pageReady = false;
    }

    /**
     * Keep this view invalidating.
     *
     * <p>Its frame loop is not decoration: {@link GameView#onDraw} is what steps the coast
     * physics, the session distance and the rowing clock, and {@link #render} is what pushes them
     * into the page. But this view is completely covered by an opaque WebView, and relying on a
     * covered sibling still being drawn every frame would put the whole instrument on an
     * assumption. A timer costs nothing and settles it.
     */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!ticking) {
                return;
            }
            postInvalidateOnAnimation();
            ui.postDelayed(this, TICK_MS);
        }
    };

    /**
     * The page owns every pixel once it is up, so this draws only while it is not. The push is
     * driven from here so it always uses values stepped in the same frame.
     */
    @Override
    protected void render(Canvas canvas, float dt) {
        trackDrive(dt);
        long now = System.currentTimeMillis();
        if (now - lastPushMs >= PUSH_INTERVAL_MS) {
            lastPushMs = now;
            pushFeed();
        }
        if (pageReady) {
            return;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        bold(canvas, "COAST FLIGHT", cx, cy - dp(10), 20, ACCENT, Paint.Align.CENTER);
        label(canvas, notice, cx, cy + dp(14), 13, DIM, Paint.Align.CENTER);
    }

    /** 0..1, how much the paddle is being driven right now (it is accelerating). */
    private float drive;
    private double slowRate;

    /**
     * The drive phase from the pulse meter, for wings that beat on the drive and glide on the
     * recovery (the rower's ask). The paddle rate rises only while the handle is being pulled, so
     * "clearly above its recent average" is the drive; the stroke counter would be a second late.
     */
    private void trackDrive(float dt) {
        double rate = status != null ? status.meter.paddleRate : 0;
        slowRate += (rate - slowRate) * Math.min(1.0, dt / 0.6);
        float target = rate > 40 && rate > slowRate * 1.03 ? 1f : 0f;
        drive += (target - drive) * Math.min(1f, dt * (target > drive ? 14f : 5f));
    }

    private void pushFeed() {
        if (!pageReady) {
            return;
        }
        int watts = status != null ? status.watts : 0;
        int rate = status != null ? status.strokeRate : 0;
        int strokes = status != null ? status.strokes : 0;
        // The page must never re-derive speed or the clock: `s` is the coasted needle value and
        // `t` already carries the 15-second pause rule. Holding a value flat is the one mistake
        // this project keeps making.
        // `p` is the rower's typical power: level flight is set from it, not from a fixed 60 W.
        String js = String.format(Locale.US,
                "window.wakeFeed&&window.wakeFeed({w:%d,r:%d,s:%.3f,t:%.1f,m:%.0f,k:%d,p:%.0f,d:%.2f});",
                watts, rate, boat.value(), activeSeconds, sessionMeters, strokes, profile.typicalWatts(), drive);
        web.evaluateJavascript(js, null);
    }

    private void fail(String message) {
        ui.post(() -> {
            pageReady = false;
            failed = true;
            notice = message;
            web.setVisibility(View.GONE);
            postInvalidateOnAnimation();
        });
    }

    private void report(String stage, String detail) {
        ui.post(() -> host.onFlightReport(stage, detail));
    }

    /**
     * The JS side of the bridge. Public and static on purpose: {@code addJavascriptInterface}
     * reaches these by reflection, which does not reliably work on anonymous or private classes.
     * Every method here runs on a WebView thread, not the UI thread.
     */
    public static final class Bridge {
        private final CoastFlightGame flight;

        Bridge(CoastFlightGame flight) {
            this.flight = flight;
        }

        @JavascriptInterface
        public String token() {
            String t = flight.host.ionToken();
            return t == null ? "" : t;
        }

        @JavascriptInterface
        public String mapMode() {
            String m = flight.bests.getString(MAP_KEY);
            return m == null ? "" : m;
        }

        /** Last session's flight, or empty when there is none yet. */
        @JavascriptInterface
        public String ghost() {
            String g = flight.bests.getString(GHOST_KEY);
            return g == null ? "" : g;
        }

        /**
         * This session's flight, kept for the next one to race. The page only calls this with
         * 30+ seconds of rowing recorded, so a quick look at the screen never wipes a good ghost.
         */
        @JavascriptInterface
        public void saveGhost(String samples) {
            if (samples == null || samples.isEmpty() || samples.length() > MAX_GHOST_CHARS) {
                return;
            }
            for (int i = 0; i < samples.length(); i++) {
                char ch = samples.charAt(i);
                if ((ch < '0' || ch > '9') && ch != ',' && ch != ';' && ch != 'v' && ch != '-') {
                    return;
                }
            }
            flight.bests.putString(GHOST_KEY, samples);
        }

        /** Best stars per postcard so far, "id:stars,...". */
        @JavascriptInterface
        public String cards() {
            String c = flight.bests.getString(CARDS_KEY);
            return c == null ? "" : c;
        }

        /**
         * A postcard was taken. Keeps the best stars per stop and records their sum.
         *
         * @return true when this beat the stop's previous best
         */
        @JavascriptInterface
        public boolean saveCard(String id, int stars) {
            if (id == null || !id.matches("[a-z]{1,24}") || stars < 0 || stars > 3) {
                return false;
            }
            synchronized (flight.bests) {
                java.util.LinkedHashMap<String, Integer> best = new java.util.LinkedHashMap<>();
                String stored = flight.bests.getString(CARDS_KEY);
                if (stored != null) {
                    for (String part : stored.split(",")) {
                        int colon = part.indexOf(':');
                        if (colon > 0) {
                            try {
                                best.put(part.substring(0, colon),
                                        Integer.parseInt(part.substring(colon + 1)));
                            } catch (NumberFormatException ignored) {
                                // a damaged entry is dropped and rewritten below
                            }
                        }
                    }
                }
                Integer previous = best.get(id);
                if (previous != null && previous >= stars) {
                    return false;
                }
                best.put(id, stars);
                StringBuilder out = new StringBuilder();
                int total = 0;
                for (java.util.Map.Entry<String, Integer> e : best.entrySet()) {
                    if (out.length() > 0) {
                        out.append(',');
                    }
                    out.append(e.getKey()).append(':').append(e.getValue());
                    total += e.getValue();
                }
                flight.bests.putString(CARDS_KEY, out.toString());
                flight.bests.recordHighest(POSTCARDS_KEY, total);
                return true;
            }
        }

        /** Ridge lift caught this session, in metres; kept when it is the most ever. */
        @JavascriptInterface
        public boolean saveLift(double metres) {
            if (metres < 1 || metres > 1.0e6) {
                return false;
            }
            return flight.bests.recordHighest(LIFT_KEY, (float) Math.round(metres));
        }

        @JavascriptInterface
        public void setMapMode(String mode) {
            flight.bests.putString(MAP_KEY, mode == null ? "" : mode);
        }

        @JavascriptInterface
        public double trip() {
            return flight.bests.get(TRIP_KEY, 0f);
        }

        @JavascriptInterface
        public void saveTrip(double metres) {
            if (metres >= 0 && metres < 1.0e9) {
                flight.bests.putFloat(TRIP_KEY, (float) metres);
            }
        }

        /** The page is up and listening; the feed can start. */
        @JavascriptInterface
        public void ready() {
            flight.pageReady = true;
            flight.ui.post(flight::postInvalidateOnAnimation);
        }

        /**
         * Reload from Java rather than from the page.
         *
         * <p>The map tiers need different viewer construction, so switching map means starting
         * over - but the page's own location.reload() has to pass shouldOverrideUrlLoading, which
         * this client blocks wholesale to stop a stray link stranding the user in a browser. So
         * the page asks, and Java reloads.
         */
        @JavascriptInterface
        public void reload() {
            flight.ui.post(flight::reloadPage);
        }

        @JavascriptInterface
        public void home() {
            flight.ui.post(flight.host::onFlightHome);
        }

        @JavascriptInterface
        public void report(String stage, String detail) {
            flight.report(stage, detail);
        }
    }
}
