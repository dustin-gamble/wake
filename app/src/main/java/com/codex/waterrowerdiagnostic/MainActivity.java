package com.codex.waterrowerdiagnostic;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Typeface;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.DhcpInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity implements CoastFlightGame.Host {
    private static final String ACTION_USB_PERMISSION =
            "com.codex.waterrowerdiagnostic.USB_PERMISSION";
    private static final String APP_NAME = "WAKE";
    private static final String PREFS_NAME = "diagnostic-settings";
    private static final String PREF_SERVER_URL = "server-url";
    private static final String PREF_STREAM = "stream-to-laptop";
    /** Cached from the laptop so the flight's maps survive the laptop being switched off. */
    private static final String PREF_ION_TOKEN = "cesium-ion-token";
    private static final int DISCOVERY_PORT = 8788;
    private static final String DISCOVERY_REQUEST = "ERGATTA_ROW_DIAG_DISCOVER_V1";
    private static final String DISCOVERY_RESPONSE = "ERGATTA_ROW_DIAG_V1 ";
    /** The S4 needs a breather between commands or it silently drops them. */
    /** Text refresh floor. The gauges animate at display rate regardless of this. */
    private static final long UI_REFRESH_MS = 60;
    private static final int[] COMMAND_GAPS_MS = {60, 100, 150, 250, 400};
    /** Coast drag options, slowest wind-down first. Labelled by feel, not by unit. */
    // Measured by feel on the machine: 0.6 was much too fast, 0.3 still too fast. A real paddle
    // holds its run far longer than a naive drag constant suggests.
    private static final float[] DRAG_OPTIONS = {0.04f, 0.08f, 0.12f, 0.20f, 0.35f};
    private static final String[] DRAG_LABELS = {
            "Very long glide", "Long glide", "Standard", "Short glide", "Stops quickly"};
    /**
     * Gap between S4 commands. Lower means fresher gauges; too low historically wedged the write
     * path, so it is adjustable from diagnostics and reported with every event.
     */
    private volatile long commandGapMs = 150;
    private volatile float coastDrag = 0.12f;

    /** Swappable content area: home, instruments, or a game. Drawer and scrim sit above it. */
    private FrameLayout screenHost;
    private View homeScreen;
    private View instrumentsScreen;
    private GameView currentGame;
    /** Kept for the life of the app: one WebView, reused, rather than one per entry. */
    private CoastFlightGame coastFlight;
    private TextView gameClock;
    private GaugeStripView gameStrip;
    private PersonalBests personalBests;
    private final java.util.List<TextView> pbLabels = new java.util.ArrayList<>();

    /**
     * Lifetime metres for the Journey. The monitor's own distance counter accumulates across
     * sessions and can be reset on the S4, so the app tracks its own base and re-bases on a reset.
     */
    private long sessionFirstStrokeMs;
    private double sessionWattSum;
    private int sessionWattSamples;
    private TextView lastSessionCard;
    private double journeyLifetime;
    private int journeyBase = -1;
    private double journeySession;
    private int tickCount;
    private static final long RESPONSE_TIMEOUT_MS = 900;
    /** Failures return instantly; a long timeout only stalls the poller before failing anyway. */
    private static final int WRITE_TIMEOUT_MS = 800;
    /**
     * Short enough that the reader releases {@link #ioLock} promptly; a blocking read on the same
     * UsbDeviceConnection is what wedges the OUT endpoint once pulse traffic ramps up.
     */
    private static final int READ_TIMEOUT_MS = 120;
    private static final int WRITE_FAILURES_BEFORE_COOLDOWN = 4;
    private static final long WRITE_COOLDOWN_MS = 1500;
    /** Measured on this unit: cooldowns never clear a wedged pipe, only a reopen does. */
    private static final int WRITE_FAILURES_BEFORE_REOPEN = 10;
    private static final int START_COMMAND_ATTEMPTS = 4;
    /** Stop thrashing the port if reopening is not restoring writes either. */
    private static final int MAX_REOPENS_PER_SESSION = 4;
    /** Let the device settle before reopening; back-to-back reopens just thrash the port. */
    private static final long REOPEN_SETTLE_BASE_MS = 4000;
    private static final int[] BAUD_RATES = {
            1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200
    };

    private UsbManager usbManager;
    private PendingIntent permissionIntent;
    private LinearLayout deviceList;
    private TextView statusView;
    private TextView uploadStatusView;
    private TextView logView;
    private TextView stateChip;
    private TextView connectionBanner;
    private TextView strokesValue;
    private Button diagnosticsToggle;
    private LinearLayout diagnosticsPanel;
    private View diagnosticsScrim;
    private LinearLayout diagnosticsDrawer;
    private int diagnosticsDrawerWidth;
    private boolean diagnosticsOpen;
    private SparklineView sparkline;
    private PaddleView paddleView;
    private GaugeView speedGauge;
    private GaugeView powerGauge;
    private GaugeView rateGauge;
    private BarMeterView powerBar;
    private BarMeterView rateBar;
    private TextView elapsedValue;
    private TextView distanceValue;
    private TextView paceValue;
    private TextView strokeRateValue;
    private TextView wattsValue;
    private TextView s4StatusValue;
    private EditText serverUrlInput;
    private Spinner baudSpinner;

    private final List<UsbDevice> devices = new ArrayList<>();
    private UsbDevice selectedDevice;
    private UsbSerialPort openSerialPort;
    private UsbDeviceConnection openConnection;
    private UsbInterface claimedInterface;
    /** CDC data interface (bulk IN/OUT), kept so the claim can be re-taken after a write fails. */
    private UsbInterface dataInterface;
    private Thread readerThread;
    private Thread protocolWriterThread;
    private Thread statusHeartbeatThread;
    private final AtomicBoolean reading = new AtomicBoolean(false);
    private final AtomicBoolean protocolPolling = new AtomicBoolean(false);
    private final AtomicBoolean statusHeartbeatRunning = new AtomicBoolean(false);
    private final AtomicBoolean discovering = new AtomicBoolean(false);
    /** Serializes reads and writes: this device wedges when both run on the connection at once. */
    private final Object ioLock = new Object();
    private final AtomicBoolean reopenScheduled = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger reopenCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private final S4Protocol s4Protocol = new S4Protocol(this::handleS4Packet);
    // Bounded so a slow or absent laptop drops old telemetry instead of growing without limit.
    private final BlockingQueue<String> uploadQueue = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean uploading = new AtomicBoolean(false);
    private Thread uploadThread;
    private int selectedBaud = 19200;
    private volatile String serverUrl = "";
    private volatile String lastS4Command = "";
    private volatile boolean autoUpload = false;
    private CheckBox streamCheckBox;
    private TextView streamToggle;
    private volatile long lastRowingStatusUploadMs;
    private volatile long lastRawUploadMs;
    private volatile long droppedUploads;
    private volatile long lastUiUpdateMs;
    private volatile boolean writePathStalled;
    /** Metric tiles ease toward their readings instead of jumping when a poll lands. */
    private final Handler uiTicker = new Handler(Looper.getMainLooper());
    private float targetDistance;
    private float shownDistance;
    private float targetPace;
    private float shownPace;
    private float targetWatts;
    private float shownWatts;
    private float targetRate;
    private float shownRate;
    private volatile boolean autoConnect = true;
    /** Set when the user taps Close, so auto-connect does not immediately undo them. */
    private volatile boolean userClosedConnection;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (device != null) {
                    log(granted
                            ? "Permission granted for " + friendlyDeviceName(device)
                            : "Permission denied for " + friendlyDeviceName(device));
                    publishDeviceEvent(granted ? "permission-granted" : "permission-denied", device, true);
                    refreshDevices();
                    if (granted && !autoConnect) {
                        openSelectedDevice();
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                log("USB device attached");
                publishSimpleEvent("usb-attached", false);
                refreshDevices();
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (selectedDevice != null && device != null
                        && selectedDevice.getDeviceId() == device.getDeviceId()) {
                    log("Selected USB device detached");
                    closeCurrentConnection();
                }
                publishSimpleEvent("usb-detached", false);
                refreshDevices();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        String incomingServerUrl = normalizedServerUrl(serverUrlFromIntent(getIntent()));
        String savedServerUrl = normalizedServerUrl(
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_SERVER_URL, ""));
        // Saved beats the compile-time default: the laptop's DHCP address changes, the APK does not.
        serverUrl = firstNonEmpty(incomingServerUrl, savedServerUrl, BuildConfig.DEFAULT_SERVER_URL);
        saveServerUrl(serverUrl);
        permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                new Intent(ACTION_USB_PERMISSION),
                pendingIntentFlags());

        setContentView(buildUi());
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbReceiver, filter);

        startUploader();
        startTileAnimator();
        log("Diagnostic app started. Launch this app manually; it does not replace Ergatta.");
        if (!TextUtils.isEmpty(serverUrl)) {
            log("Laptop dashboard set to " + serverUrl);
            publishSimpleEvent("app-started", true);
        }
        discoverLaptop();
        refreshDevices();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String incomingServerUrl = normalizedServerUrl(serverUrlFromIntent(intent));
        if (!TextUtils.isEmpty(incomingServerUrl)) {
            serverUrl = incomingServerUrl;
            saveServerUrl(serverUrl);
            serverUrlInput.setText(serverUrl);
            log("Updated laptop dashboard URL to " + serverUrl);
            publishSimpleEvent("app-linked", true);
            sendSnapshot("linked-from-browser", true);
        }
    }

    @Override
    protected void onDestroy() {
        uiTicker.removeCallbacksAndMessages(null);
        closeCurrentConnection();
        stopUploader();
        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }

    private View buildUi() {
        personalBests = new PersonalBests(this);
        autoUpload = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_STREAM, false);
        journeyLifetime = personalBests.get("journey.total", 0f);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(getColorCompat(R.color.background));

        screenHost = new FrameLayout(this);
        frame.addView(screenHost, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addDiagnosticsDrawer(frame);

        instrumentsScreen = buildInstruments();
        homeScreen = buildHome();
        showScreen(homeScreen);
        return frame;
    }

    private void showScreen(View screen) {
        if (currentGame != null) {
            currentGame.stop();
            currentGame = null;
        }
        screenHost.removeAllViews();
        screenHost.addView(screen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (screen == homeScreen) {
            saveLastSession();
            refreshPersonalBests();
            if (lastSessionCard != null) {
                lastSessionCard.setText(lastSessionText());
            }
        }
    }

    private void showHome() {
        showScreen(homeScreen);
    }

    private void showInstruments() {
        showScreen(instrumentsScreen);
    }

    private void showGame(GameView game, View screen) {
        showScreen(screen);
        currentGame = game;
        game.setDrag(coastDrag);
        game.start();
    }

    /* ---------- home ---------- */

    private View buildHome() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));

        // Header: name, records, streaming, exit. Kept to one line so the grid gets the height.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(APP_NAME);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(24);
        title.setLetterSpacing(0.2f);
        title.setTextColor(getColorCompat(R.color.primary));
        title.setOnLongClickListener(v -> {
            captureScreenshot("home");
            return true;
        });
        header.addView(title);
        TextView sub = new TextView(this);
        sub.setText("  v" + BuildConfig.VERSION_NAME);
        sub.setTextSize(10);
        sub.setTextColor(getColorCompat(R.color.text_faint));
        header.addView(sub, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView records = chip("RECORDS");
        records.setOnClickListener(v -> showRecords());
        header.addView(records);
        streamToggle = chip(autoUpload ? "STREAM ON" : "STREAM OFF");
        streamToggle.setOnClickListener(v -> setStreaming(!autoUpload));
        header.addView(streamToggle);
        TextView exit = new TextView(this);
        exit.setText("✕");
        exit.setTextSize(18);
        exit.setPadding(dp(14), dp(6), dp(6), dp(6));
        exit.setTextColor(getColorCompat(R.color.text_faint));
        exit.setOnClickListener(v -> confirmExit());
        header.addView(exit);
        root.addView(header);

        // Every game on one screen: a fixed grid sized from the display, no scrolling.
        int widthDp = (int) (getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        // Fewer columns than before so each card - and its icon - is bigger.
        int cols = widthDp >= 1280 ? 6 : widthDp >= 980 ? 5 : widthDp >= 700 ? 4 : 3;
        java.util.List<View> cards = new java.util.ArrayList<>();
        int accent = getColorCompat(R.color.primary);
        int blue = getColorCompat(R.color.accent_blue);
        int warn = getColorCompat(R.color.warn);
        int bad = getColorCompat(R.color.bad);
        cards.add(gridCard("GAUGES", GameIconView.Kind.GAUGES, accent, null, null, v -> showInstruments()));
        cards.add(gridCard("ZOMBIE RUN", GameIconView.Kind.ZOMBIE, bad, "zombie.150", "m", v -> openZombieRun()));
        cards.add(gridCard("ROW RUNNER", GameIconView.Kind.RUNNER, 0xFFE84C3D, "runner.distance", "m", v -> openRowRunner()));
        cards.add(gridCard("COAST FLIGHT", GameIconView.Kind.FLY, 0xFF7FC6EE, null, null, v -> openCoastFlight()));
        cards.add(gridCard("SKYLINE", GameIconView.Kind.CITY, 0xFF9A6BB0, "city.blocks", "blocks", v -> openSkyline()));
        cards.add(gridCard("WAVE RIDER", GameIconView.Kind.SURF, 0xFF7FC6EE, "surf.score", "pts", v -> openWaveRider()));
        cards.add(gridCard("CANYON CHASE", GameIconView.Kind.CHASE, 0xFFFF7A3D, "chase.distance", "m", v -> openCanyonChase()));
        cards.add(gridCard("ROCKET LAUNCH", GameIconView.Kind.ROCKET, 0xFFBFE3FF, "rocket.altitude", "", v -> openRocketLaunch()));
        cards.add(gridCard("CANYON FLIGHT", GameIconView.Kind.CANYON, warn, "canyon.gates", "gates", v -> openCanyonFlight()));
        cards.add(gridCard("MEGA PULL", GameIconView.Kind.MEGAPULL, 0xFFF5C518, "megapull.peak", "W", v -> openMegaPull()));
        cards.add(gridCard("PACE BOAT", GameIconView.Kind.PACE, accent, "time.1000", "1k", v -> openPaceBoat()));
        cards.add(gridCard("GHOST RACE", GameIconView.Kind.GHOST, blue, "time.2000", "2k", v -> openGhostRace()));
        cards.add(gridCard("HEAD RACE", GameIconView.Kind.HEADRACE, warn, "time.2000", "2k", v -> openHeadRace()));
        cards.add(gridCard("THE RUN", GameIconView.Kind.RUN, accent, "run.streak", "streak", v -> openTheRun()));
        cards.add(gridCard("INTERVALS", GameIconView.Kind.INTERVALS, blue, "intervals.sprints", "in band", v -> openIntervals()));
        cards.add(gridCard("JOURNEY", GameIconView.Kind.JOURNEY, accent, "journey.total", "", v -> openJourney()));
        cards.add(gridCard("SPRINT LADDER", GameIconView.Kind.LADDER, warn, "ladder.120", "rung", v -> openSprintLadder()));
        cards.add(gridCard("TUG OF WAR", GameIconView.Kind.TUG, bad, "tug.2", "held", v -> openTugOfWar()));
        cards.add(gridCard("COLLECTOR", GameIconView.Kind.COLLECTOR, accent, "collector.score", "pts", v -> openCollector()));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        int rows = (cards.size() + cols - 1) / cols;
        for (int rIdx = 0; rIdx < rows; rIdx++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int cIdx = 0; cIdx < cols; cIdx++) {
                int i = rIdx * cols + cIdx;
                View cell = i < cards.size() ? cards.get(i) : new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                row.addView(cell, lp);
            }
            grid.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        // Grow every icon to fill the cell the grid actually produced. A fixed 58dp left the
        // cards mostly empty, and the right size depends on the screen and the card count, not
        // on a constant - removing four games alone made every cell taller.
        int heightDp = (int) (getResources().getDisplayMetrics().heightPixels
                / getResources().getDisplayMetrics().density);
        int chromeDp = 96;   // header, last-session line and the paddings around the grid
        int cellDp = Math.max(76, (heightDp - chromeDp) / Math.max(1, rows));
        // What is left after the title and the personal-best line underneath.
        int iconDp = Math.max(58, Math.min(132, cellDp - 46));
        for (View card : cards) {
            View icon = ((LinearLayout) card).getChildAt(0);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) icon.getLayoutParams();
            lp.width = dp(iconDp);
            lp.height = dp(iconDp);
            icon.setLayoutParams(lp);
        }

        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        gridParams.topMargin = dp(8);
        root.addView(grid, gridParams);

        lastSessionCard = new TextView(this);
        lastSessionCard.setText(lastSessionText());
        lastSessionCard.setTextSize(10);
        lastSessionCard.setTextColor(getColorCompat(R.color.text_faint));
        lastSessionCard.setPadding(dp(4), dp(6), 0, 0);
        root.addView(lastSessionCard);
        return root;
    }

    /** A grid card: icon, name, and the personal best underneath. */
    private View gridCard(String title, GameIconView.Kind icon, int color, String pbKey,
                          String pbCaption, View.OnClickListener onTap) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(6), dp(6), dp(6), dp(6));
        card.setBackgroundColor(getColorCompat(R.color.surface));
        card.setClickable(true);
        card.setOnClickListener(onTap);

        GameIconView iconView = new GameIconView(this, icon, color);
        card.addView(iconView, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(11f);
        t.setLetterSpacing(0.06f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(getColorCompat(R.color.text_primary));
        t.setPadding(0, dp(5), 0, 0);
        card.addView(t);

        TextView pb = new TextView(this);
        pb.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pb.setTextSize(11);
        pb.setGravity(Gravity.CENTER);
        pb.setTextColor(color);
        pb.setTag(pbKey == null ? "" : pbKey + "|" + pbCaption);
        card.addView(pb);
        pbLabels.add(pb);
        return card;
    }

    private void refreshPersonalBests() {
        for (TextView pb : pbLabels) {
            String tag = String.valueOf(pb.getTag());
            if (tag.isEmpty()) {
                pb.setText("");
                continue;
            }
            String key = tag.substring(0, tag.indexOf('|'));
            String caption = tag.substring(tag.indexOf('|') + 1);
            if (!personalBests.has(key)) {
                pb.setText("\u2014");
                pb.setTextColor(getColorCompat(R.color.text_faint));
                continue;
            }
            float v = personalBests.get(key, 0);
            String shown = key.startsWith("time.") || key.startsWith("run.streak")
                    ? PersonalBests.formatTime(v)
                    : key.startsWith("intervals.") ? Math.round(v) + "%"
                    : key.equals("journey.total")
                            ? String.format(Locale.US, "%.1f km", v / 1000f)
                    : key.startsWith("storm.") || key.startsWith("tug.")
                            ? PersonalBests.formatTime(v)
                    : key.equals("dive.joules") ? String.format(Locale.US, "%.0f m", v / 1000f)
                    : key.equals("rocket.altitude") ? String.format(Locale.US, "%.0f km", v / 1000f)
                    : key.startsWith("zombie.") || key.equals("runner.distance")
                            ? String.valueOf(Math.round(v))
                    : String.valueOf(Math.round(v));
            pb.setText(caption.isEmpty() ? shown : shown + " " + caption);
        }
    }

    /** Streaming costs CPU the graphics need; off by default, remembered when changed. */
    private void setStreaming(boolean on) {
        autoUpload = on;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_STREAM, on).apply();
        if (streamCheckBox != null && streamCheckBox.isChecked() != on) {
            streamCheckBox.setChecked(on);
        }
        if (streamToggle != null) {
            streamToggle.setText(on ? "STREAM ON" : "STREAM OFF");
        }
        log("Streaming to laptop " + (on ? "on" : "off"));
    }

    private String streamLabel() {
        return autoUpload
                ? "Streaming to laptop: ON  \u00b7  tap to turn off for smoother graphics"
                : "Streaming to laptop: OFF  \u00b7  tap to turn on";
    }

    private void trackJourney(S4Protocol.Status status) {
        if (status.watts > 0) {
            if (sessionFirstStrokeMs == 0) {
                sessionFirstStrokeMs = System.currentTimeMillis();
            }
            sessionWattSum += status.watts;
            sessionWattSamples++;
        }
        int d = status.distanceMeters > 0 ? status.distanceMeters : 0;
        if (d <= 0) {
            return;
        }
        if (journeyBase < 0 || d < journeyBase) {
            // First reading, or the monitor was reset: bank what we had and start a new base.
            commitJourney();
            journeyBase = d;
            journeySession = 0;
            return;
        }
        journeySession = d - journeyBase;
    }

    /** Remembers this session's headline numbers for the home screen. */
    private void saveLastSession() {
        if (sessionFirstStrokeMs == 0 || journeySession < 20) {
            return;
        }
        float seconds = (System.currentTimeMillis() - sessionFirstStrokeMs) / 1000f;
        personalBests.putString("last.session", String.format(Locale.US, "%d|%d|%d",
                Math.round(journeySession), Math.round(seconds),
                sessionWattSamples > 0 ? Math.round(sessionWattSum / sessionWattSamples) : 0));
    }

    private String lastSessionText() {
        String raw = personalBests.getString("last.session");
        if (raw == null) {
            return "No session yet. Quick Row or a game will fill this in.";
        }
        String[] p = raw.split("\\|");
        if (p.length < 3) {
            return "";
        }
        return String.format(Locale.US, "Last session:  %s m  \u00b7  %s  \u00b7  %s W average",
                p[0], PersonalBests.formatTime(Float.parseFloat(p[1])), p[2]);
    }

    /** Folds the current session into the lifetime total and persists it. */
    private void commitJourney() {
        if (journeySession > 0) {
            journeyLifetime += journeySession;
            journeySession = 0;
            if (journeyBase >= 0) {
                journeyBase = -1;
            }
            personalBests.recordHighest("journey.total", (float) journeyLifetime);
        }
    }

    private void openJourney() {
        JourneyGame game = new JourneyGame(this);
        game.setTotalMeters(journeyLifetime + journeySession);
        showGame(game, gameScreen("JOURNEY", game, journeyChips(game)));
    }

    /** The journey is a lifetime total, so the only control it needs is a way to start again. */
    private View journeyChips(JourneyGame game) {
        TextView reset = chip("RESTART");
        reset.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Restart the journey?")
                .setMessage("This clears the lifetime distance and starts the route again from the"
                        + " beginning. Your other records are untouched.")
                .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                .setPositiveButton("Restart", (d, w) -> {
                    journeyLifetime = 0;
                    journeySession = 0;
                    personalBests.putFloat("journey.total", 0f);
                    game.setTotalMeters(0);
                    game.start();
                    toast("Journey restarted");
                })
                .show());
        return reset;
    }

    /* ---------- pace boat ---------- */

    // Re-centred on measured ability: 3221 samples of real rowing gave a median pace of
    // 128 s/500m and a best of 119. The old set started at 150 and defaulted to 135, so the
    // pace boat was slower than the rower and simply fell away - no race at all.
    private static final float[] PACE_CHOICES = {145f, 138f, 132f, 126f, 120f, 114f};
    private static final int[] DISTANCE_CHOICES = {500, 1000, 2000, 5000};

    private void openPaceBoat() {
        PaceBoatGame game = new PaceBoatGame(this, personalBests);
        showGame(game, gameScreen("PACE BOAT", game, gameChips(game)));
    }

    private void openGhostRace() {
        GhostRaceGame game = new GhostRaceGame(this, personalBests);
        showGame(game, gameScreen("GHOST RACE", game, ghostChips(game)));
    }

    /** Distance chip only: the ghost sets its own pace. */
    private View ghostChips(GhostRaceGame game) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView dist = chip(game.raceMeters() + " m");
        dist.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < DISTANCE_CHOICES.length; k++) {
                if (DISTANCE_CHOICES[k] == game.raceMeters()) {
                    i = (k + 1) % DISTANCE_CHOICES.length;
                }
            }
            game.setRaceMeters(DISTANCE_CHOICES[i]);
            dist.setText(DISTANCE_CHOICES[i] + " m");
            game.start();
        });
        row.addView(dist);
        return row;
    }

    /** A single watts chip that cycles through a range in steps of 20. */
    private interface IntSetter {
        void accept(int value);
    }

    private View wattsChip(String prefix, int initial, int min, int max, IntSetter onChange) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView chip = chip(prefix + initial + " W");
        final int[] current = {initial};
        chip.setOnClickListener(v -> {
            current[0] += 20;
            if (current[0] > max) {
                current[0] = min;
            }
            onChange.accept(current[0]);
            chip.setText(prefix + current[0] + " W");
        });
        row.addView(chip);
        return row;
    }

    private void openSprintLadder() {
        SprintLadderGame game = new SprintLadderGame(this, personalBests);
        showGame(game, gameScreen("SPRINT LADDER", game, wattsChip("start ", game.startWatts(), 80,
                200, game::setStartWatts)));
    }

    private void openTugOfWar() {
        TugOfWarGame game = new TugOfWarGame(this, personalBests);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView lvl = chip("level " + game.level());
        lvl.setOnClickListener(v -> {
            int next = game.level() % 5 + 1;
            game.setLevel(next);
            lvl.setText("level " + next);
            game.start();
        });
        row.addView(lvl);
        showGame(game, gameScreen("TUG OF WAR", game, row));
    }

    private void openHeadRace() {
        HeadRaceGame game = new HeadRaceGame(this, personalBests);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView dist = chip(game.raceMeters() + " m");
        dist.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < DISTANCE_CHOICES.length; k++) {
                if (DISTANCE_CHOICES[k] == game.raceMeters()) {
                    i = (k + 1) % DISTANCE_CHOICES.length;
                }
            }
            game.setRaceMeters(DISTANCE_CHOICES[i]);
            dist.setText(DISTANCE_CHOICES[i] + " m");
            game.start();
        });
        row.addView(dist);
        showGame(game, gameScreen("HEAD RACE", game, row));
    }

    private void openCollector() {
        CollectorGame game = new CollectorGame(this, personalBests);
        showGame(game, gameScreen("COLLECTOR", game, null));
    }

        /** All personal bests as a plain list. Rebuilt each time it is opened. */
    private void showRecords() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("\u2039  HOME");
        back.setTextSize(13);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(dp(4), dp(8), dp(14), dp(8));
        back.setTextColor(getColorCompat(R.color.text_secondary));
        back.setOnClickListener(v -> showHome());
        header.addView(back);
        TextView t = new TextView(this);
        t.setText("RECORDS");
        t.setTextSize(15);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColorCompat(R.color.primary));
        header.addView(t);
        root.addView(header);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>(personalBests.all());
        if (sorted.isEmpty()) {
            list.addView(bodyText("No records yet. Finish a game to set one."));
        }
        for (java.util.Map.Entry<String, Object> e : sorted.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("ghost.")) {
                continue;   // a recording, not a number anyone wants to read
            }
            Object raw = e.getValue();
            if (!(raw instanceof Float)) {
                continue;
            }
            float v = (Float) raw;
            String shown = key.startsWith("time.") || key.startsWith("run.streak")
                    || key.startsWith("storm.") || key.startsWith("tug.")
                    ? PersonalBests.formatTime(v)
                    : key.startsWith("intervals.") ? Math.round(v) + "%"
                    : key.equals("journey.total") ? String.format(Locale.US, "%.1f km", v / 1000f)
                    : key.equals("dive.joules") ? String.format(Locale.US, "%.1f m deep", v / 1000f)
                    : key.equals("rocket.altitude") ? String.format(Locale.US, "%.1f km", v / 1000f)
                    : key.equals("city.tallest") ? Math.round(v) + " floors"
                    : key.equals("surf.ride") ? PersonalBests.formatTime(v)
                    : String.valueOf(Math.round(v));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(getColorCompat(R.color.surface));
            TextView k = new TextView(this);
            k.setText(recordName(key));
            k.setTextSize(13);
            k.setTextColor(getColorCompat(R.color.text_secondary));
            row.addView(k, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            TextView val = new TextView(this);
            val.setText(shown);
            val.setTextSize(15);
            val.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            val.setTextColor(getColorCompat(R.color.primary));
            row.addView(val);
            list.addView(row, marginTop(dp(4)));
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        showScreen(root);
    }

    private static String recordName(String key) {
        if (key.startsWith("time.")) return key.substring(5) + " m fastest";
        if (key.equals("run.streak")) return "The Run - longest streak";
        if (key.equals("run.score")) return "The Run - most run metres";
        if (key.startsWith("intervals.")) return "Intervals - " + key.substring(10) + " in band";
        if (key.equals("journey.total")) return "Journey - lifetime distance";
        if (key.startsWith("storm.")) return "Storm - survived at " + key.substring(6) + " W";
        if (key.startsWith("ladder.")) return "Sprint Ladder - rung from " + key.substring(7) + " W";
        if (key.startsWith("tug.")) return "Tug of War - held level " + key.substring(4);
        if (key.equals("collector.score")) return "Collector - best score";
        if (key.equals("dive.joules")) return "Depth Dive - deepest";
        if (key.startsWith("zombie.")) return "Zombie Run - survived vs " + PersonalBests.formatPace(Float.parseFloat(key.substring(7))) + " horde";
        if (key.equals("runner.distance")) return "Row Runner - furthest run";
        if (key.equals("runner.coins")) return "Row Runner - most coins";
        if (key.equals("boss.level")) return "Boss Fight - bosses beaten";
        if (key.equals("canyon.gates")) return "Canyon Flight - most gates";
        if (key.equals("megapull.peak")) return "Mega Pull - peak watts";
        if (key.equals("chase.distance")) return "Canyon Chase - furthest run";
        if (key.equals("rocket.altitude")) return "Rocket Launch - highest altitude";
        if (key.equals("city.blocks")) return "Skyline - blocks placed";
        if (key.equals("city.tallest")) return "Skyline - tallest tower";
        if (key.equals("surf.score")) return "Wave Rider - best session points";
        if (key.equals("surf.ride")) return "Wave Rider - longest ride";
        return key;
    }

    /**
     * Coast Flight: a Cesium globe flown down the California coast, in this app.
     *
     * <p>It used to hand off to Chrome and read the laptop's event stream, which meant the data
     * went tablet -> laptop -> browser to draw something the tablet already knew, and left the
     * user stranded in a browser on a device with no app switcher. Now the globe is a WebView
     * inside a normal game screen, fed straight from the coasted boat speed, and the laptop is
     * only ever needed to hand over the Ion token once.
     */
    private void openCoastFlight() {
        if (coastFlight == null) {
            coastFlight = new CoastFlightGame(this, personalBests, this);
        }
        // Cheap if it is already cached; the page reloads itself if a token lands late.
        fetchIonToken();
        showGame(coastFlight, gameScreen("COAST FLIGHT", coastFlight, null, coastFlight.webView()));
        publishSimpleEvent("coast-flight-opened", true);
    }

    /* ---------- CoastFlightGame.Host ---------- */

    @Override
    public String ionToken() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_ION_TOKEN, "");
    }

    @Override
    public void onFlightHome() {
        showHome();
    }

    /**
     * Forced onto the wire even with streaming off. There is no emulator for this tablet, so a
     * page failure that does not reach the laptop cannot be seen at all; these are rare and
     * capped at the source.
     */
    @Override
    public void onFlightReport(String stage, String detail) {
        log("Coast Flight " + stage + ": " + detail);
        try {
            JSONObject payload = new JSONObject();
            payload.put("stage", stage);
            payload.put("detail", detail == null ? "" : detail);
            publishEvent("coast-flight", payload, true);
        } catch (JSONException e) {
            setUploadStatus("Event failed: " + e.getMessage());
        }
    }

    /**
     * Photograph the app's own screen and send it to the laptop.
     *
     * <p>This tablet is kiosk-locked: no file manager, no app switcher, and Android's own
     * screenshot lands in a gallery that cannot be opened. Since the app already knows where the
     * laptop is, the shortest path to a usable image is to draw the view hierarchy into a bitmap
     * and POST it.
     *
     * <p>Caveat worth knowing: this renders the <em>views</em>. Every screen here is a custom
     * Canvas view and comes out exactly as seen, but Coast Flight is a WebView drawing WebGL on
     * the GPU, and that surface is not in the view draw pass - it will come out blank. Use the
     * hardware screenshot for that one.
     */
    private void captureScreenshot(String label) {
        if (TextUtils.isEmpty(serverUrl)) {
            toast("No laptop found - screenshot needs the dashboard running");
            return;
        }
        View root = getWindow().getDecorView().getRootView();
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            return;
        }
        Bitmap shot;
        try {
            shot = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            toast("Not enough memory for a screenshot");
            return;
        }
        root.draw(new Canvas(shot));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        shot.compress(Bitmap.CompressFormat.PNG, 100, out);
        shot.recycle();
        final byte[] png = out.toByteArray();
        final String base = serverUrl;
        toast("Screenshot " + (png.length / 1024) + " kB - sending");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + "/api/screenshot").openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(8000);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "image/png");
                connection.setRequestProperty("X-Shot-Name", label);
                connection.setFixedLengthStreamingMode(png.length);
                connection.setDoOutput(true);
                OutputStream stream = connection.getOutputStream();
                stream.write(png);
                stream.close();
                final int code = connection.getResponseCode();
                runOnUiThread(() -> toast(code == 200 ? "Screenshot saved on the laptop"
                        : "Screenshot rejected: " + code));
            } catch (IOException e) {
                runOnUiThread(() -> toast("Screenshot failed: " + e.getMessage()));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "screenshot").start();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        log(message);
    }

    /** Which screen is on show, for naming the saved file. */
    private String screenLabel() {
        if (currentGame != null) {
            return currentGame.getClass().getSimpleName().replace("Game", "");
        }
        if (screenHost != null && screenHost.getChildCount() > 0
                && screenHost.getChildAt(0) == instrumentsScreen) {
            return "gauges";
        }
        return "home";
    }

    /**
     * Fetch the Cesium Ion token from the laptop and cache it.
     *
     * <p>The token is never compiled into the app: the laptop holds it in CESIUM_ION_TOKEN or
     * server/cesium.local.json, and this caches it in app prefs so the maps keep working with the
     * laptop off afterwards. Only its length is ever logged.
     */
    private void fetchIonToken() {
        if (TextUtils.isEmpty(serverUrl)) {
            return;
        }
        final String base = serverUrl;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(base + "/api/cesium-token").openConnection();
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);
                connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != 200) {
                    return;
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
                reader.close();
                final String token = new JSONObject(body.toString()).optString("ionToken", "");
                if (token.length() < 20) {
                    return;
                }
                final String had = ionToken();
                if (token.equals(had)) {
                    return;
                }
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        .putString(PREF_ION_TOKEN, token).apply();
                runOnUiThread(() -> {
                    log("Cesium Ion token cached from the laptop (" + token.length() + " chars)");
                    // Arrived while looking at the globe with no token: the map tiers are decided
                    // at page load, so start it over to pick up satellite.
                    if (coastFlight != null && currentGame == coastFlight && TextUtils.isEmpty(had)) {
                        coastFlight.reloadPage();
                    }
                });
            } catch (IOException | JSONException | RuntimeException e) {
                // No token to be had; the flight falls back to OpenStreetMap and says so.
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "ion-token").start();
    }

    private void openSkyline() {
        SkylineGame game = new SkylineGame(this, personalBests);
        showGame(game, gameScreen("SKYLINE", game, null));
    }

    private void openWaveRider() {
        WaveRiderGame game = new WaveRiderGame(this, personalBests);
        showGame(game, gameScreen("WAVE RIDER", game, null));
    }

    private void openCanyonChase() {
        CanyonChaseGame game = new CanyonChaseGame(this, personalBests);
        showGame(game, gameScreen("CANYON CHASE", game, null));
    }

    private void openRocketLaunch() {
        RocketLaunchGame game = new RocketLaunchGame(this, personalBests);
        showGame(game, gameScreen("ROCKET LAUNCH", game, null));
    }

    private void openCanyonFlight() {
        CanyonFlightGame game = new CanyonFlightGame(this, personalBests);
        showGame(game, gameScreen("CANYON FLIGHT", game, null));
    }

    private void openMegaPull() {
        MegaPullGame game = new MegaPullGame(this, personalBests);
        showGame(game, gameScreen("MEGA PULL", game, null));
    }

    private void openZombieRun() {
        ZombieRunGame game = new ZombieRunGame(this, personalBests);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView pace = chip("horde " + PersonalBests.formatPace(game.hordePace()));
        pace.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < PACE_CHOICES.length; k++) {
                if (PACE_CHOICES[k] == game.hordePace()) {
                    i = (k + 1) % PACE_CHOICES.length;
                }
            }
            game.setHordePace(PACE_CHOICES[i]);
            pace.setText("horde " + PersonalBests.formatPace(PACE_CHOICES[i]));
            game.start();
        });
        row.addView(pace);
        showGame(game, gameScreen("ZOMBIE RUN", game, row));
    }

    private void openRowRunner() {
        RowRunnerGame game = new RowRunnerGame(this, personalBests);
        showGame(game, gameScreen("ROW RUNNER", game, null));
    }

        private void openTheRun() {
        TheRunGame game = new TheRunGame(this, personalBests);
        showGame(game, gameScreen("THE RUN", game, null));
    }

    private void openIntervals() {
        IntervalGame game = new IntervalGame(this, personalBests);
        showGame(game, gameScreen("INTERVALS", game, intervalChips(game)));
    }

    /** Plan and power-target chips for the interval coach. */
    private View intervalChips(IntervalGame game) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView planChip = chip(game.plan().label());
        planChip.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < IntervalGame.PLANS.length; k++) {
                if (IntervalGame.PLANS[k] == game.plan()) {
                    i = (k + 1) % IntervalGame.PLANS.length;
                }
            }
            game.setPlan(IntervalGame.PLANS[i]);
            planChip.setText(IntervalGame.PLANS[i].label());
            game.start();
        });
        row.addView(planChip);
        TextView wattsChip = chip(game.targetWatts() + " W");
        wattsChip.setOnClickListener(v -> {
            int next = game.targetWatts() + 20;
            if (next > 260) {
                next = 80;
            }
            game.setTargetWatts(next);
            wattsChip.setText(next + " W");
        });
        row.addView(wattsChip);
        return row;
    }

    /** Standard game chrome: a header with back, then the game filling the rest. */
    private View gameScreen(String title, GameView game, View controls) {
        return gameScreen(title, game, controls, null);
    }

    /**
     * As above, but with {@code overlay} stacked on top of the game view.
     *
     * <p>For a screen whose visible surface is not a canvas - Coast Flight's WebView. The
     * GameView stays underneath at full size so its frame loop keeps running, because that loop
     * is what advances the coast physics and pushes data into the page.
     */
    private View gameScreen(String title, GameView game, View controls, View overlay) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹  HOME");
        back.setTextSize(13);
        back.setLetterSpacing(0.08f);
        back.setTypeface(Typeface.DEFAULT_BOLD);
        back.setPadding(dp(4), dp(8), dp(14), dp(8));
        back.setTextColor(getColorCompat(R.color.text_secondary));
        back.setOnClickListener(v -> showHome());
        header.addView(back);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setLetterSpacing(0.14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(getColorCompat(R.color.primary));
        header.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        gameClock = null;   // the stopwatch lives in the vitals strip now
        if (controls != null) {
            header.addView(controls);
        }
        root.addView(header);

        // Vitals across the top of every game: stopwatch, speed, power, pace, rate, distance.
        gameStrip = new GaugeStripView(this);
        // Long-press anywhere on the strip to photograph the screen. Deliberately invisible:
        // every game already has the strip, and none of them need another control.
        gameStrip.setOnLongClickListener(v -> {
            captureScreenshot(screenLabel());
            return true;
        });
        // 62dp -> 74dp -> 92dp: this strip is the instrument during a game, and the games
        // themselves lose very little by it.
        LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(92));
        stripParams.topMargin = dp(4);
        root.addView(gameStrip, stripParams);

        LinearLayout.LayoutParams gameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        gameParams.topMargin = dp(4);
        if (overlay == null) {
            root.addView(game, gameParams);
        } else {
            FrameLayout stack = new FrameLayout(this);
            stack.addView(game, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            // The overlay outlives the screen it was last in, so detach it first.
            if (overlay.getParent() instanceof ViewGroup) {
                ((ViewGroup) overlay.getParent()).removeView(overlay);
            }
            stack.addView(overlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            root.addView(stack, gameParams);
        }
        return root;
    }

    /** Pace and distance selectors as tappable chips; tapping cycles to the next option. */
    private View gameChips(PaceBoatGame game) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView pace = chip(PersonalBests.formatPace(game.targetPace()) + " /500");
        pace.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < PACE_CHOICES.length; k++) {
                if (PACE_CHOICES[k] == game.targetPace()) {
                    i = (k + 1) % PACE_CHOICES.length;
                }
            }
            game.setTargetPace(PACE_CHOICES[i]);
            pace.setText(PersonalBests.formatPace(PACE_CHOICES[i]) + " /500");
            game.start();
        });
        row.addView(pace);
        TextView dist = chip(game.raceMeters() + " m");
        dist.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < DISTANCE_CHOICES.length; k++) {
                if (DISTANCE_CHOICES[k] == game.raceMeters()) {
                    i = (k + 1) % DISTANCE_CHOICES.length;
                }
            }
            game.setRaceMeters(DISTANCE_CHOICES[i]);
            dist.setText(DISTANCE_CHOICES[i] + " m");
            game.start();
        });
        row.addView(dist);
        return row;
    }

    private TextView chip(String text) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextSize(12);
        c.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        c.setPadding(dp(12), dp(6), dp(12), dp(6));
        c.setTextColor(getColorCompat(R.color.text_primary));
        c.setBackgroundColor(getColorCompat(R.color.surface_alt));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        c.setLayoutParams(params);
        return c;
    }


    private View buildInstruments() {
        int pad = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, dp(8));

        root.addView(buildHeader());
        root.addView(buildConnectionBanner(), marginTop(dp(8)));

        // Gauges carry the session; everything else supports them.
        LinearLayout gauges = new LinearLayout(this);
        gauges.setOrientation(LinearLayout.HORIZONTAL);
        speedGauge = new GaugeView(this, "SPEED", "m/s",
                getColorCompat(R.color.primary), 5f, 1).waterDrag(0.12f).attack(4.0f);
        powerGauge = new GaugeView(this, "POWER", "watts",
                getColorCompat(R.color.accent_blue), 250f, 0).waterDrag(0.012f).attack(4.5f);
        rateGauge = new GaugeView(this, "RATE", "str/min",
                getColorCompat(R.color.warn), 45f, 0).waterDrag(0.06f).attack(4.5f);
        gauges.addView(gaugeCell(speedGauge, 1.25f));
        gauges.addView(gaugeCell(powerGauge, 1f));
        gauges.addView(gaugeCell(rateGauge, 1f));
        LinearLayout.LayoutParams gaugeRow = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 2.6f);
        gaugeRow.topMargin = dp(8);
        root.addView(gauges, gaugeRow);

        // Water wheel beside the trace.
        LinearLayout traceRow = new LinearLayout(this);
        traceRow.setOrientation(LinearLayout.HORIZONTAL);

        paddleView = new PaddleView(this);
        LinearLayout paddleWrap = new LinearLayout(this);
        paddleWrap.setOrientation(LinearLayout.VERTICAL);
        paddleWrap.setPadding(dp(10), dp(8), dp(10), dp(8));
        paddleWrap.setBackgroundColor(getColorCompat(R.color.surface));
        paddleWrap.addView(cardLabel("WATER"));
        paddleWrap.addView(paddleView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams paddleParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        paddleParams.rightMargin = dp(3);
        traceRow.addView(paddleWrap, paddleParams);

        sparkline = new SparklineView(this);
        LinearLayout sparkWrap = new LinearLayout(this);
        sparkWrap.setOrientation(LinearLayout.VERTICAL);
        sparkWrap.setPadding(dp(10), dp(8), dp(10), dp(8));
        sparkWrap.setBackgroundColor(getColorCompat(R.color.surface));
        sparkWrap.addView(cardLabel("BOAT SPEED / POWER - LAST 60 SECONDS"));
        sparkWrap.addView(sparkline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        traceRow.addView(sparkWrap,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.4f));

        LinearLayout.LayoutParams traceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.7f);
        traceParams.topMargin = dp(6);
        root.addView(traceRow, traceParams);

        root.addView(buildMetricGrid(), marginTop(dp(6)));

        powerBar = new BarMeterView(this, "POWER", getColorCompat(R.color.primary), 250f);
        rateBar = new BarMeterView(this, "STROKE RATE", getColorCompat(R.color.accent_blue), 40f);
        LinearLayout bars = new LinearLayout(this);
        bars.setOrientation(LinearLayout.HORIZONTAL);
        bars.setPadding(dp(12), dp(8), dp(12), dp(8));
        bars.setBackgroundColor(getColorCompat(R.color.surface));
        bars.addView(powerBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bars.addView(rateBar, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bars, marginTop(dp(4)));

        // Diagnostics live at the bottom, out of the way until asked for.
        diagnosticsToggle = button("Diagnostics");
        diagnosticsToggle.setOnClickListener(v -> setDiagnosticsOpen(true));
        root.addView(diagnosticsToggle, marginTop(dp(6)));

        return root;
    }

    /** Diagnostics slides in over the instruments, full height and scrollable. */
    private void addDiagnosticsDrawer(FrameLayout frame) {
        diagnosticsScrim = new View(this);
        diagnosticsScrim.setBackgroundColor(0xAA000000);
        diagnosticsScrim.setVisibility(View.GONE);
        diagnosticsScrim.setOnClickListener(v -> setDiagnosticsOpen(false));
        frame.addView(diagnosticsScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(getColorCompat(R.color.surface));
        drawer.setPadding(dp(14), dp(12), dp(14), dp(12));
        // Swallow taps so they do not fall through to the scrim and close the drawer.
        drawer.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("DIAGNOSTICS");
        title.setTextSize(12);
        title.setLetterSpacing(0.14f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(getColorCompat(R.color.text_faint));
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextSize(17);
        close.setPadding(dp(14), dp(8), dp(6), dp(8));
        close.setTextColor(getColorCompat(R.color.text_secondary));
        close.setOnClickListener(v -> setDiagnosticsOpen(false));
        header.addView(close);
        drawer.addView(header);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        diagnosticsPanel = buildDiagnosticsPanel();
        scroll.addView(diagnosticsPanel);
        drawer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        int width = Math.min(dp(460), (int) (getResources().getDisplayMetrics().widthPixels * 0.62f));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                width, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
        frame.addView(drawer, params);
        drawer.setTranslationX(width);
        drawer.setVisibility(View.GONE);
        diagnosticsDrawer = drawer;
        diagnosticsDrawerWidth = width;
    }

    private void setDiagnosticsOpen(boolean open) {
        if (diagnosticsDrawer == null || open == diagnosticsOpen) {
            return;
        }
        diagnosticsOpen = open;
        if (open) {
            diagnosticsDrawer.setVisibility(View.VISIBLE);
            diagnosticsScrim.setVisibility(View.VISIBLE);
            diagnosticsDrawer.animate().translationX(0).setDuration(220).start();
        } else {
            diagnosticsDrawer.animate().translationX(diagnosticsDrawerWidth).setDuration(180)
                    .withEndAction(() -> {
                        diagnosticsDrawer.setVisibility(View.GONE);
                        diagnosticsScrim.setVisibility(View.GONE);
                    }).start();
        }
    }

    private View gaugeCell(GaugeView gauge, float weight) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(6), dp(6), dp(6), dp(6));
        cell.setBackgroundColor(getColorCompat(R.color.surface));
        cell.addView(gauge, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight);
        params.rightMargin = dp(3);
        return wrap(cell, params);
    }

    private View wrap(View view, LinearLayout.LayoutParams params) {
        view.setLayoutParams(params);
        return view;
    }

    private TextView cardLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(9);
        label.setLetterSpacing(0.12f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(getColorCompat(R.color.text_faint));
        return label;
    }

    private LinearLayout.LayoutParams sparkParams(LinearLayout.LayoutParams params) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT) {{
            topMargin = params.topMargin;
        }};
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(APP_NAME);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(26);
        title.setLetterSpacing(0.18f);
        title.setTextColor(getColorCompat(R.color.primary));
        titles.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Ergatta rower link  ·  v" + BuildConfig.VERSION_NAME);
        subtitle.setTextColor(getColorCompat(R.color.text_faint));
        subtitle.setTextSize(10);
        titles.addView(subtitle);

        header.addView(titles, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stateChip = new TextView(this);
        stateChip.setText("WAITING");
        stateChip.setTypeface(Typeface.DEFAULT_BOLD);
        stateChip.setTextSize(13);
        stateChip.setLetterSpacing(0.08f);
        stateChip.setPadding(dp(14), dp(7), dp(14), dp(7));
        stateChip.setTextColor(getColorCompat(R.color.text_faint));
        stateChip.setBackgroundColor(getColorCompat(R.color.surface));
        header.addView(stateChip);

        TextView exitIcon = new TextView(this);
        exitIcon.setText("\u2715");
        exitIcon.setTextSize(16);
        exitIcon.setGravity(Gravity.CENTER);
        exitIcon.setTextColor(getColorCompat(R.color.text_faint));
        exitIcon.setBackgroundColor(getColorCompat(R.color.surface));
        // Small target, but still big enough to hit with a sweaty hand mid-row.
        exitIcon.setPadding(dp(12), dp(8), dp(12), dp(8));
        exitIcon.setContentDescription("Exit");
        exitIcon.setText("\u2039  HOME");
        exitIcon.setTextSize(12);
        exitIcon.setLetterSpacing(0.08f);
        exitIcon.setTypeface(Typeface.DEFAULT_BOLD);
        exitIcon.setOnClickListener(v -> showHome());
        LinearLayout.LayoutParams exitParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        exitParams.leftMargin = dp(6);
        header.addView(exitIcon, exitParams);

        return header;
    }

    private View buildConnectionBanner() {
        connectionBanner = new TextView(this);
        connectionBanner.setText("USB not connected. Tap Open in diagnostics.");
        connectionBanner.setTextSize(12);
        connectionBanner.setPadding(dp(12), dp(9), dp(12), dp(9));
        connectionBanner.setTextColor(getColorCompat(R.color.text_secondary));
        connectionBanner.setBackgroundColor(getColorCompat(R.color.surface_alt));
        return connectionBanner;
    }

    /** Two rows of three, so nothing is orphaned on its own line. */
    private View buildMetricGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = metricRow();
        elapsedValue = addMetric(top, "TIME", "0:00", true);
        // Deliberately out of step with the other tiles: this is the figure being watched.
        elapsedValue.setTextSize(38);
        distanceValue = addMetric(top, "DISTANCE", "0 m", false);
        paceValue = addMetric(top, "PACE /500", "--:--", true);
        grid.addView(top);

        LinearLayout bottom = metricRow();
        strokeRateValue = addMetric(bottom, "STROKES/MIN", "0", false);
        wattsValue = addMetric(bottom, "POWER", "0 W", false);
        strokesValue = addMetric(bottom, "STROKES", "0", false);
        grid.addView(bottom, marginTop(dp(2)));

        return grid;
    }

    private LinearLayout metricRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private View card(View content, String heading) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(12), dp(10), dp(12), dp(10));
        wrapper.setBackgroundColor(getColorCompat(R.color.surface));

        TextView label = new TextView(this);
        label.setText(heading);
        label.setTextSize(10);
        label.setLetterSpacing(0.12f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(getColorCompat(R.color.text_faint));
        wrapper.addView(label);

        wrapper.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(96)));
        return wrapper;
    }

    private LinearLayout buildDiagnosticsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        s4StatusValue = bodyText("Latest packet: waiting");
        s4StatusValue.setTypeface(Typeface.MONOSPACE);
        s4StatusValue.setTextSize(10);
        s4StatusValue.setPadding(dp(10), dp(8), dp(10), dp(8));
        s4StatusValue.setBackgroundColor(getColorCompat(R.color.surface));
        panel.addView(s4StatusValue);

        serverUrlInput = new EditText(this);
        serverUrlInput.setSingleLine(true);
        serverUrlInput.setText(serverUrl);
        serverUrlInput.setHint("Laptop URL, for example http://192.168.1.25:8787");
        serverUrlInput.setTextSize(13);
        serverUrlInput.setTextColor(getColorCompat(R.color.text_primary));
        serverUrlInput.setSelectAllOnFocus(false);
        serverUrlInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                serverUrl = normalizedServerUrl(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                // Persist once the field settles, not on every keystroke.
                saveServerUrl(normalizedServerUrl(s.toString()));
            }
        });
        panel.addView(serverUrlInput, marginTop(dp(8)));

        LinearLayout uploadControls = new LinearLayout(this);
        uploadControls.setOrientation(LinearLayout.HORIZONTAL);
        uploadControls.setGravity(Gravity.CENTER_VERTICAL);

        CheckBox autoConnectCheckBox = new CheckBox(this);
        autoConnectCheckBox.setText("Auto-connect");
        autoConnectCheckBox.setTextSize(12);
        autoConnectCheckBox.setTextColor(getColorCompat(R.color.text_primary));
        autoConnectCheckBox.setChecked(true);
        autoConnectCheckBox.setOnCheckedChangeListener((v, checked) -> {
            autoConnect = checked;
            if (checked) {
                userClosedConnection = false;
            }
        });
        panel.addView(autoConnectCheckBox);

        CheckBox autoUploadCheckBox = new CheckBox(this);
        autoUploadCheckBox.setText("Stream to laptop");
        autoUploadCheckBox.setTextSize(12);
        autoUploadCheckBox.setTextColor(getColorCompat(R.color.text_primary));
        autoUploadCheckBox.setChecked(autoUpload);
        autoUploadCheckBox.setOnCheckedChangeListener((v, checked) -> setStreaming(checked));
        streamCheckBox = autoUploadCheckBox;
        uploadControls.addView(autoUploadCheckBox, weightParams());

        Button testUpload = button("Test");
        testUpload.setOnClickListener(v -> publishSimpleEvent("manual-test", true));
        uploadControls.addView(testUpload, weightParams());

        Button findLaptop = button("Find Laptop");
        findLaptop.setOnClickListener(v -> discoverLaptop());
        uploadControls.addView(findLaptop, weightParams());

        Button shotButton = button("Screenshot");
        shotButton.setOnClickListener(v -> {
            setDiagnosticsOpen(false);
            // Let the drawer finish sliding out, or it ends up in the picture.
            screenHost.postDelayed(() -> captureScreenshot(screenLabel()), 450);
        });
        uploadControls.addView(shotButton, weightParams());

        Button snapshotButton = button("Snapshot");
        snapshotButton.setOnClickListener(v -> sendSnapshot("manual", true));
        uploadControls.addView(snapshotButton, weightParams());
        panel.addView(uploadControls, marginTop(dp(4)));

        uploadStatusView = new TextView(this);
        uploadStatusView.setText("Laptop upload: waiting");
        uploadStatusView.setTextColor(getColorCompat(R.color.text_faint));
        uploadStatusView.setTextSize(11);
        panel.addView(uploadStatusView);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        Button refresh = button("Refresh");
        refresh.setOnClickListener(v -> refreshDevices());
        controls.addView(refresh, weightParams());

        Button open = button("Open");
        open.setOnClickListener(v -> requestOrOpenSelected());
        controls.addView(open, weightParams());

        Button close = button("Close");
        close.setOnClickListener(v -> {
            userClosedConnection = true;
            closeCurrentConnection();
        });
        controls.addView(close, weightParams());

        Button clear = button("Clear");
        clear.setOnClickListener(v -> logView.setText(""));
        controls.addView(clear, weightParams());
        panel.addView(controls, marginTop(dp(4)));

        baudSpinner = new Spinner(this);
        List<String> baudLabels = new ArrayList<>();
        for (int baud : BAUD_RATES) {
            baudLabels.add(baud + " baud");
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, baudLabels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        baudSpinner.setAdapter(adapter);
        baudSpinner.setSelection(4);
        baudSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedBaud = BAUD_RATES[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedBaud = 19200;
            }
        });
        panel.addView(baudSpinner);

        TextView gapLabel = new TextView(this);
        gapLabel.setText("Command gap (lower = faster gauges, may destabilise the write path)");
        gapLabel.setTextSize(10);
        gapLabel.setTextColor(getColorCompat(R.color.text_faint));
        panel.addView(gapLabel);

        Spinner gapSpinner = new Spinner(this);
        List<String> gapLabels = new ArrayList<>();
        for (int gap : COMMAND_GAPS_MS) {
            gapLabels.add(gap + " ms");
        }
        ArrayAdapter<String> gapAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, gapLabels);
        gapAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        gapSpinner.setAdapter(gapAdapter);
        gapSpinner.setSelection(2);
        gapSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                commandGapMs = COMMAND_GAPS_MS[position];
                log("Command gap set to " + commandGapMs + " ms");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                commandGapMs = 150;
            }
        });
        panel.addView(gapSpinner);

        TextView dragLabel = new TextView(this);
        dragLabel.setText("Coast feel - match it to how the paddle actually slows");
        dragLabel.setTextSize(10);
        dragLabel.setTextColor(getColorCompat(R.color.text_faint));
        panel.addView(dragLabel);

        Spinner dragSpinner = new Spinner(this);
        ArrayAdapter<String> dragAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, new ArrayList<>(
                        java.util.Arrays.asList(DRAG_LABELS)));
        dragAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dragSpinner.setAdapter(dragAdapter);
        dragSpinner.setSelection(2);
        dragSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                float k = DRAG_OPTIONS[position];
                coastDrag = k;
                speedGauge.setDrag(k);
                paddleView.setDrag(k);
                if (currentGame != null) {
                    currentGame.setDrag(k);
                }
                log("Coast drag set to " + k + " (" + DRAG_LABELS[position] + ")");
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        panel.addView(dragSpinner);

        panel.addView(sectionHeader("USB devices"));
        ScrollView devicesScroll = new ScrollView(this);
        deviceList = new LinearLayout(this);
        deviceList.setOrientation(LinearLayout.VERTICAL);
        devicesScroll.addView(deviceList);
        panel.addView(devicesScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(190)));

        statusView = new TextView(this);
        statusView.setTextColor(getColorCompat(R.color.text_faint));
        statusView.setTextSize(11);
        panel.addView(statusView);

        panel.addView(sectionHeader("Raw input / event log"));
        ScrollView logScroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(10);
        logView.setTextColor(getColorCompat(R.color.text_secondary));
        logView.setPadding(dp(8), dp(8), dp(8), dp(8));
        logView.setBackgroundColor(getColorCompat(R.color.surface));
        logScroll.addView(logView);
        panel.addView(logScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(260)));

        return panel;
    }

    private LinearLayout.LayoutParams marginTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        return params;
    }

    private void refreshDevices() {
        closeCurrentConnection();

        devices.clear();
        selectedDevice = null;
        deviceList.removeAllViews();

        HashMap<String, UsbDevice> deviceMap = usbManager.getDeviceList();
        devices.addAll(deviceMap.values());
        statusView.setText(devices.size() + " USB device(s) visible to Android");

        if (devices.isEmpty()) {
            TextView empty = bodyText("No USB devices are visible yet. Connect the rower USB cable, then tap Refresh.");
            deviceList.addView(empty);
            log("Refresh: no USB devices found");
            sendSnapshot("refresh-empty", false);
            return;
        }

        for (UsbDevice device : devices) {
            Button row = button(deviceSummary(device));
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setOnClickListener(v -> selectDevice(device));
            deviceList.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView detail = bodyText(deviceDetails(device));
            detail.setPadding(dp(8), 0, dp(8), dp(10));
            deviceList.addView(detail);
        }

        selectDevice(preferredDevice());
        log("Refresh: " + devices.size() + " USB device(s) found");
        sendSnapshot("refresh", false);

        if (autoConnect && !userClosedConnection && !isSerialOpen()) {
            log("Auto-connecting to " + friendlyDeviceName(selectedDevice));
            requestOrOpenSelected();
        }
    }

    /** Prefer a device an actual serial driver claims over whatever the device map lists first. */
    private UsbDevice preferredDevice() {
        for (UsbDevice device : devices) {
            if (UsbSerialProber.getDefaultProber().probeDevice(device) != null) {
                return device;
            }
        }
        return devices.get(0);
    }

    private void selectDevice(UsbDevice device) {
        selectedDevice = device;
        statusView.setText("Selected " + friendlyDeviceName(device)
                + " | permission: " + (usbManager.hasPermission(device) ? "yes" : "no"));
        log("Selected " + deviceSummary(device));
        publishDeviceEvent("device-selected", device, false);
    }

    private boolean isSerialOpen() {
        return openSerialPort != null;
    }

    private void requestOrOpenSelected() {
        resetReopenBudget();
        userClosedConnection = false;
        if (selectedDevice == null) {
            log("Open requested, but no device is selected");
            publishSimpleEvent("open-without-device", false);
            return;
        }

        if (!usbManager.hasPermission(selectedDevice)) {
            log("Requesting USB permission for " + friendlyDeviceName(selectedDevice));
            publishDeviceEvent("permission-requested", selectedDevice, false);
            usbManager.requestPermission(selectedDevice, permissionIntent);
            return;
        }

        openSelectedDevice();
    }

    private void openSelectedDevice() {
        if (selectedDevice == null) {
            return;
        }

        closeCurrentConnection();

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(selectedDevice);
        if (driver != null && !driver.getPorts().isEmpty()) {
            openSerial(selectedDevice, driver);
        } else {
            openRawUsb(selectedDevice);
        }
    }

    private void resetReopenBudget() {
        reopenCount.set(0);
        writePathStalled = false;
    }

    private void openSerial(UsbDevice device, UsbSerialDriver driver) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            log("Could not open USB device connection");
            publishDeviceEvent("open-failed", device, false);
            return;
        }

        UsbSerialPort port = driver.getPorts().get(0);
        try {
            port.open(connection);
            port.setParameters(selectedBaud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            try {
                port.setDTR(true);
                port.setRTS(true);
            } catch (IOException e) {
                log("Serial control line setup skipped: " + e.getMessage());
            }
            openConnection = connection;
            openSerialPort = port;
            dataInterface = findBulkInterface(device);
            log("Opened serial driver " + driver.getClass().getSimpleName()
                    + " at " + selectedBaud + " 8N1");
            publishDeviceEvent("serial-opened", device, true);
            startSerialReader(port);
            startS4Polling(port);
            startStatusHeartbeat();
        } catch (IOException e) {
            log("Serial open failed: " + e.getMessage());
            publishErrorEvent("serial-open-failed", e.getMessage(), true);
            safeClose(port);
            connection.close();
        }
    }

    /** The interface holding the bulk OUT endpoint we write commands to. */
    private static UsbInterface findBulkInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint endpoint = candidate.getEndpoint(e);
                if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void openRawUsb(UsbDevice device) {
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            log("Could not open raw USB device connection");
            publishDeviceEvent("open-failed", device, false);
            return;
        }

        UsbInterface usbInterface = null;
        UsbEndpoint inputEndpoint = null;

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint endpoint = candidate.getEndpoint(e);
                boolean input = endpoint.getDirection() == UsbConstants.USB_DIR_IN;
                boolean readableType = endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        || endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_INT;
                if (input && readableType) {
                    usbInterface = candidate;
                    inputEndpoint = endpoint;
                    break;
                }
            }
            if (inputEndpoint != null) {
                break;
            }
        }

        if (usbInterface == null || inputEndpoint == null) {
            connection.close();
            log("No readable bulk/interrupt input endpoint found on this USB device");
            publishDeviceEvent("no-readable-endpoint", device, false);
            return;
        }

        if (!connection.claimInterface(usbInterface, true)) {
            connection.close();
            log("Could not claim USB interface " + usbInterface.getId());
            publishDeviceEvent("claim-interface-failed", device, false);
            return;
        }

        openConnection = connection;
        claimedInterface = usbInterface;
        log("Opened raw USB interface " + usbInterface.getId()
                + ", endpoint 0x" + hex2(inputEndpoint.getAddress()));
        publishDeviceEvent("raw-usb-opened", device, false);
        startRawUsbReader(connection, inputEndpoint);
    }

    private void startSerialReader(UsbSerialPort port) {
        reading.set(true);
        readerThread = new Thread(() -> {
            byte[] buffer = new byte[512];
            log("Serial reader started");
            publishSimpleEvent("serial-reader-started", false);
            while (reading.get()) {
                try {
                    int count;
                    synchronized (ioLock) {
                        count = port.read(buffer, READ_TIMEOUT_MS);
                    }
                    if (count > 0) {
                        logBytes("SER", buffer, count);
                    }
                } catch (IOException e) {
                    if (reading.get()) {
                        log("Serial read stopped: " + e.getMessage());
                        publishErrorEvent("serial-read-stopped", e.getMessage(), true);
                    }
                    break;
                }
            }
            log("Serial reader stopped");
            publishSimpleEvent("serial-reader-stopped", false);
        }, "serial-reader");
        readerThread.start();
    }

    private void startS4Polling(UsbSerialPort port) {
        s4Protocol.reset();
        updateRowingStatus(s4Protocol.snapshot(), true);
        protocolPolling.set(true);
        protocolWriterThread = new Thread(() -> {
            int consecutiveWriteFailures = 0;
            try {
                lastS4Command = "USB";
                if (!sendStartCommand(port)) {
                    writePathStalled = true;
                    log("S4 did not accept the USB start command; reopening");
                    publishSimpleEvent("s4-start-failed", true);
                    scheduleReopen();
                    return;
                }
                Thread.sleep(commandGapMs);

                while (protocolPolling.get()) {
                    String command = s4Protocol.beginNextPoll();
                    if (TextUtils.isEmpty(command)) {
                        log("Monitor refused every memory address; staying open to read pulses only");
                        publishSimpleEvent("s4-polling-paused", false);
                        break;
                    }
                    lastS4Command = command.trim();

                    try {
                        synchronized (ioLock) {
                            port.write(command.getBytes(StandardCharsets.US_ASCII), WRITE_TIMEOUT_MS);
                        }
                        if (consecutiveWriteFailures > 0) {
                            log("USB write path recovered after " + consecutiveWriteFailures + " failures");
                            publishSimpleEvent("s4-write-recovered", false);
                        }
                        consecutiveWriteFailures = 0;
                        writePathStalled = false;
                    } catch (IOException e) {
                        // No interface reclaim here. 0.5.1 measured reclaimed=true with
                        // writeSucceededAfterReclaim=false 7 times out of 7: the claim is never
                        // the problem, and the retry only burned a full 800ms write timeout on
                        // every failure. Recovery comes from the backoff below.
                        // A failed bulk transfer is a USB stall, not a refused command: keep the
                        // field in rotation and back off so the pipe can recover.
                        s4Protocol.abandonPoll();
                        consecutiveWriteFailures++;
                        if (consecutiveWriteFailures <= 3
                                || consecutiveWriteFailures % WRITE_FAILURES_BEFORE_COOLDOWN == 0) {
                            publishWriteFailure(lastS4Command, e.getMessage(), consecutiveWriteFailures);
                        }
                        log("USB write stalled on " + lastS4Command + " (attempt "
                                + consecutiveWriteFailures + "): " + e.getMessage());
                        if (consecutiveWriteFailures >= WRITE_FAILURES_BEFORE_REOPEN) {
                            log("USB write path wedged; reopening the serial port");
                            publishSimpleEvent("s4-port-reopen", true);
                            scheduleReopen();
                            break;
                        }
                        if (consecutiveWriteFailures % WRITE_FAILURES_BEFORE_COOLDOWN == 0) {
                            // The OUT pipe is wedged. Stop writing entirely for a few seconds; the
                            // reader stays live, so pulses and stroke markers keep coming through.
                            log("USB write path wedged; pausing writes for "
                                    + (WRITE_COOLDOWN_MS / 1000) + "s");
                            publishSimpleEvent("s4-write-cooldown", true);
                            Thread.sleep(WRITE_COOLDOWN_MS);
                        } else {
                            Thread.sleep(Math.min(1500L, 120L * consecutiveWriteFailures));
                        }
                        continue;
                    }

                    S4Protocol.PollResult result = s4Protocol.awaitPollResult(RESPONSE_TIMEOUT_MS);
                    if (result == S4Protocol.PollResult.REJECTED) {
                        log("Monitor refused " + lastS4Command + "; trying a narrower read next");
                        publishS4MonitorError(lastS4Command);
                    }
                    // The S4 drops commands that arrive back to back; give it room to answer.
                    Thread.sleep(commandGapMs);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                protocolPolling.set(false);
                s4Protocol.abandonPoll();
            }
        }, "s4-polling");
        protocolWriterThread.start();
    }

    /** Retries the USB handshake; the monitor ignores it while the pipe is still settling. */
    private boolean sendStartCommand(UsbSerialPort port) throws InterruptedException {
        for (int attempt = 1; attempt <= START_COMMAND_ATTEMPTS; attempt++) {
            try {
                synchronized (ioLock) {
                    port.write(s4Protocol.startCommand(), WRITE_TIMEOUT_MS);
                }
                log("Sent S4 USB start command");
                return true;
            } catch (IOException e) {
                log("USB start command attempt " + attempt + " failed: " + e.getMessage());
                Thread.sleep(200L * attempt);
            }
        }
        return false;
    }

    /** Reopens the port from the UI thread; the poller thread is about to exit. */
    private void scheduleReopen() {
        if (!reopenScheduled.compareAndSet(false, true)) {
            return;
        }
        int attempt = reopenCount.incrementAndGet();
        if (attempt > MAX_REOPENS_PER_SESSION) {
            // Reopening is not clearing it. Stop touching the port: the reader still delivers
            // pulses, and only a physical reconnect resets the device from here.
            writePathStalled = true;
            log("Reopening is not clearing the write path. Unplug and reconnect the rower USB "
                    + "cable, then tap Open.");
            publishSimpleEvent("s4-reopen-limit", true);
            reopenScheduled.set(false);
            return;
        }
        long settleMs = REOPEN_SETTLE_BASE_MS * attempt;
        new Thread(() -> {
            try {
                Thread.sleep(settleMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reopenScheduled.set(false);
                return;
            }
            runOnUiThread(() -> {
                try {
                    if (selectedDevice != null) {
                        openSelectedDevice();
                    }
                } finally {
                    reopenScheduled.set(false);
                }
            });
        }, "serial-reopen").start();
    }

    private void startRawUsbReader(UsbDeviceConnection connection, UsbEndpoint endpoint) {
        reading.set(true);
        readerThread = new Thread(() -> {
            byte[] buffer = new byte[Math.max(64, endpoint.getMaxPacketSize())];
            log("Raw USB reader started");
            publishSimpleEvent("raw-usb-reader-started", false);
            while (reading.get()) {
                int count = connection.bulkTransfer(endpoint, buffer, buffer.length, 1000);
                if (count > 0) {
                    logBytes("USB", buffer, count);
                } else if (count < 0) {
                    log("Raw USB read returned " + count + "; retrying");
                    publishSimpleEvent("raw-usb-read-empty", false);
                }
            }
            log("Raw USB reader stopped");
            publishSimpleEvent("raw-usb-reader-stopped", false);
        }, "raw-usb-reader");
        readerThread.start();
    }

    private void closeCurrentConnection() {
        reading.set(false);
        protocolPolling.set(false);
        statusHeartbeatRunning.set(false);
        s4Protocol.abandonPoll();

        if (statusHeartbeatThread != null) {
            try {
                statusHeartbeatThread.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            statusHeartbeatThread = null;
        }

        if (protocolWriterThread != null) {
            try {
                protocolWriterThread.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            protocolWriterThread = null;
        }

        if (readerThread != null) {
            try {
                readerThread.join(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readerThread = null;
        }

        if (openSerialPort != null) {
            try {
                synchronized (ioLock) {
                    openSerialPort.write(s4Protocol.exitCommand(), 250);
                }
            } catch (IOException ignored) {
            }
            safeClose(openSerialPort);
            openSerialPort = null;
        }

        dataInterface = null;
        if (openConnection != null && claimedInterface != null) {
            openConnection.releaseInterface(claimedInterface);
            claimedInterface = null;
        }

        if (openConnection != null) {
            openConnection.close();
            openConnection = null;
            log("USB connection closed");
            publishSimpleEvent("connection-closed", true);
        }
    }

    private void confirmExit() {
        int pending = uploadQueue.size();
        String message = isSerialOpen()
                ? "The rower is still connected" + (pending > 0
                        ? " and " + pending + " event(s) have not reached the laptop yet." : ".")
                        + "\n\nExit anyway?"
                : "Exit the diagnostic app?";
        new AlertDialog.Builder(this)
                .setTitle("Exit diagnostic?")
                .setMessage(message)
                .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Exit", (dialog, which) -> exitApp())
                .show();
    }

    /** Back should not kill a running capture without asking either. */
    @Override
    public void onBackPressed() {
        if (diagnosticsOpen) {
            setDiagnosticsOpen(false);
            return;
        }
        if (screenHost.getChildCount() > 0 && screenHost.getChildAt(0) != homeScreen) {
            showHome();
            return;
        }
        confirmExit();
    }

    private void exitApp() {
        saveLastSession();
        commitJourney();
        publishSimpleEvent("app-exit-requested", true);
        closeCurrentConnection();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    private void sendSnapshot(String reason, boolean force) {
        try {
            JSONArray deviceArray = new JSONArray();
            for (UsbDevice device : devices) {
                deviceArray.put(deviceToJson(device));
            }
            JSONObject payload = new JSONObject();
            payload.put("reason", reason);
            payload.put("deviceCount", devices.size());
            payload.put("selectedDeviceId", selectedDevice == null ? JSONObject.NULL : selectedDevice.getDeviceId());
            payload.put("devices", deviceArray);
            publishEvent("usb-snapshot", payload, force);
        } catch (JSONException e) {
            setUploadStatus("Snapshot failed: " + e.getMessage());
        }
    }

    private void publishDeviceEvent(String type, UsbDevice device, boolean force) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("device", deviceIdentity(device));
            publishEvent(type, payload, force);
        } catch (JSONException e) {
            setUploadStatus("Event failed: " + e.getMessage());
        }
    }

    /** Short device identity for routine events; {@link #deviceToJson} is for snapshots only. */
    private JSONObject deviceIdentity(UsbDevice device) throws JSONException {
        JSONObject object = new JSONObject();
        if (device == null) {
            return object;
        }
        object.put("deviceId", device.getDeviceId());
        object.put("friendlyName", friendlyDeviceName(device));
        object.put("vidPid", hex4(device.getVendorId()) + ":" + hex4(device.getProductId()));
        object.put("hasPermission", usbManager.hasPermission(device));
        return object;
    }

    private void publishErrorEvent(String type, String error, boolean force) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("error", error == null ? "" : error);
            publishEvent(type, payload, force);
        } catch (JSONException e) {
            setUploadStatus("Event failed: " + e.getMessage());
        }
    }

    private void publishSimpleEvent(String type, boolean force) {
        try {
            publishEvent(type, new JSONObject(), force);
        } catch (JSONException e) {
            setUploadStatus("Event failed: " + e.getMessage());
        }
    }

    private void publishRawBytes(String prefix, byte[] bytes) {
        if (!autoUpload) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("source", prefix);
            payload.put("count", bytes.length);
            payload.put("hex", toHex(bytes));
            payload.put("ascii", toPrintableAscii(bytes));
            publishEvent("raw-bytes", payload, false);
        } catch (JSONException e) {
            setUploadStatus("Raw upload failed: " + e.getMessage());
        }
    }

    private void handleS4Packet(String packet, S4Protocol.Status status) {
        updateRowingStatus(status, false);
        long now = System.currentTimeMillis();
        if (now - lastRowingStatusUploadMs < 120) {
            return;
        }
        lastRowingStatusUploadMs = now;
        publishRowingStatus(status, false);
    }

    private void publishRowingStatus(S4Protocol.Status status, boolean heartbeat) {
        if (!autoUpload) {
            return;
        }
        try {
            JSONObject payload = rowingStatusToJson(status);
            payload.put("packet", status.lastPacket);
            payload.put("heartbeat", heartbeat);
            publishEvent("rowing-status", payload, false);
        } catch (JSONException e) {
            setUploadStatus("Rowing status upload failed: " + e.getMessage());
        }
    }

    private void publishS4MonitorError(String command) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("lastCommand", command);
            payload.put("retiredFieldCount", s4Protocol.retiredFieldCount());
            publishEvent("s4-monitor-error", payload, false);
        } catch (JSONException e) {
            setUploadStatus("S4 error upload failed: " + e.getMessage());
        }
    }

    private void publishWriteFailure(String command, String error, int attempt) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("command", command);
            payload.put("error", error == null ? "" : error);
            payload.put("consecutiveFailures", attempt);
            publishEvent("s4-write-failed", payload, false);
        } catch (JSONException e) {
            setUploadStatus("Write failure upload failed: " + e.getMessage());
        }
    }

    private JSONObject rowingStatusToJson(S4Protocol.Status status) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("monitorConnected", status.monitorConnected);
        payload.put("rowing", status.rowing);
        payload.put("elapsedSeconds", status.elapsedSeconds);
        payload.put("distanceMeters", status.distanceMeters);
        payload.put("strokes", status.strokes);
        payload.put("strokeRate", status.strokeRate);
        payload.put("watts", status.watts);
        payload.put("heartRate", status.heartRate);
        payload.put("paceSecondsPer500m", status.paceSecondsPer500m);
        payload.put("strokeRateRaw", status.strokeRateRaw);
        payload.put("displayDistanceMeters", status.displayDistanceMeters);
        payload.put("strokeAvgTimeRaw", status.strokeAvgTimeRaw);
        payload.put("instantSpeedMps", Math.round(status.instantSpeedMps * 100) / 100.0);
        payload.put("derivedDistanceMeters", status.derivedDistanceMeters);
        payload.put("lastPacket", status.lastPacket);
        payload.put("lastPacketAgeMs", status.lastPacketAgeMs);
        payload.put("lastCommand", lastS4Command);
        payload.put("retiredFieldCount", s4Protocol.retiredFieldCount());
        payload.put("packetsSeen", status.packetsSeen);
        payload.put("pulsesSeen", status.pulsesSeen);
        payload.put("pulseHz", Math.round(status.pulseHz * 100) / 100.0);
        payload.put("lastPulseValue", status.lastPulseValue);
        payload.put("flywheelMoving", status.flywheelMoving);
        if (speedGauge != null) {
            // What the needle is actually showing, so the coast can be verified from the capture
            // instead of reasoned about.
            payload.put("gaugeShown", Math.round(speedGauge.shownValue() * 100) / 100.0);
            payload.put("gaugeCoasting", speedGauge.isCoasting());
            payload.put("driving", status.stillRowing);
            payload.put("speedUnchangedMs", Math.min(status.speedUnchangedMs, 99999));
        }
        payload.put("waterSpeedMps", Math.round(status.waterSpeedMps * 100) / 100.0);
        payload.put("fields", new JSONArray(s4Protocol.fieldReport()));
        payload.put("droppedUploads", droppedUploads);
        payload.put("commandGapMs", commandGapMs);
        payload.put("coastDrag", coastDrag);
        payload.put("screen", currentGame != null ? currentGame.getClass().getSimpleName()
                : screenHost != null && screenHost.getChildCount() > 0
                        && screenHost.getChildAt(0) == instrumentsScreen ? "instruments" : "home");
        return payload;
    }

    private void updateRowingStatus(S4Protocol.Status status, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastUiUpdateMs < UI_REFRESH_MS) {
            return;
        }
        lastUiUpdateMs = now;
        runOnUiThread(() -> {
            boolean rowing = status.rowing;
            stateChip.setText(status.monitorConnected ? (rowing ? "ROWING" : "READY") : "WAITING");
            stateChip.setTextColor(getColorCompat(status.monitorConnected
                    ? (rowing ? R.color.primary : R.color.text_primary)
                    : R.color.text_faint));

            // Time and stroke count are exact counters: step them, never interpolate.
            elapsedValue.setText(formatElapsed(status.elapsedSeconds));
            strokesValue.setText(String.valueOf(status.strokes));

            targetDistance = status.distanceMeters > 0
                    ? status.distanceMeters
                    : status.derivedDistanceMeters;
            targetPace = status.paceSecondsPer500m;
            targetWatts = status.watts;
            // Averaged: a lone 19 among 25s made the needle drop as if a stroke had not
            // counted. The raw value stays in diagnostics and in the memory map.
            targetRate = status.strokeRateAverage;

            // Coast once strokes stop, not when power dips: watts reads zero between strokes
            // (27% of mid-row samples), and the paddle keeps turning long after you let go.
            // Between strokes the paddle is already slowing, so the needle should be falling even
            // while the session continues. Driving means a stroke is actively pushing the wheel:
            // the reading rose, or it changed within the last stroke period.
            boolean driving = status.stillRowing && status.speedUnchangedMs < 1200;
            trackJourney(status);
            if (gameStrip != null && currentGame != null) {
                gameStrip.update(status, currentGame.boatSpeed());
            }
            if (currentGame != null) {
                currentGame.onStatus(status, driving);
                if (currentGame instanceof JourneyGame) {
                    ((JourneyGame) currentGame).setTotalMeters(journeyLifetime + journeySession);
                }
            }
            speedGauge.setValue((float) status.waterSpeedMps, driving);
            powerGauge.setValue(status.watts, driving);
            rateGauge.setValue(status.strokeRateAverage, driving);

            powerBar.setValue(status.watts, status.watts + " W");
            rateBar.setValue(status.strokeRateAverage, status.strokeRateAverage + " spm");

            paddleView.setSpeed(status.waterSpeedMps, driving);

            connectionBanner.setText(connectionSummary(status));
            connectionBanner.setTextColor(getColorCompat(status.monitorConnected
                    ? R.color.text_secondary
                    : R.color.warn));

            if (diagnosticsOpen) {
                s4StatusValue.setText("Latest packet: " + fallback(status.lastPacket)
                        + "\nPacket age: " + formatAge(status.lastPacketAgeMs)
                        + "\nLast command: " + fallback(lastS4Command)
                        + "\nPackets " + status.packetsSeen + " | pulses " + status.pulsesSeen
                        + " | retired " + s4Protocol.retiredFieldCount()
                        + "\n" + joinLines(s4Protocol.fieldReport()));
            }
        });
    }

    /**
     * Eases the numeric tiles toward their latest readings at display rate.
     *
     * <p>Addresses refresh roughly once a second, so writing them straight to the tiles made the
     * numbers jump. Rising values track quickly; falling ones ease off, matching the gauges.
     */
    private void startTileAnimator() {
        uiTicker.post(new Runnable() {
            @Override
            public void run() {
                // Trace the needle, not the poll: 5Hz of the coasted value shows each stroke's
                // rise and run, where per-poll sampling would draw a once-a-second staircase.
                if (tickCount % 1800 == 0 && tickCount > 0) {
                    commitJourney();
                }
                if (gameStrip != null && currentGame != null) {
                    gameStrip.setClock(currentGame.activeSeconds(), currentGame.isClockRunning(),
                            currentGame.hasClockStarted());
                }
                if (sparkline != null && ++tickCount % 6 == 0) {
                    sparkline.addSample(speedGauge.shownValue(), Math.round(shownWatts));
                }
                shownDistance = ease(shownDistance, targetDistance, 0.16f);
                shownPace = ease(shownPace, targetPace, 0.12f);
                shownWatts = ease(shownWatts, targetWatts, 0.18f);
                shownRate = ease(shownRate, targetRate, 0.18f);

                if (distanceValue != null) {
                    distanceValue.setText(Math.round(shownDistance) + " m");
                    paceValue.setText(formatPace(Math.round(shownPace)));
                    wattsValue.setText(Math.round(shownWatts) + " W");
                    strokeRateValue.setText(String.valueOf(Math.round(shownRate)));
                }
                uiTicker.postDelayed(this, 33);
            }
        });
    }

    private static float ease(float shown, float target, float rising) {
        // Fall away more gently than values climb, so a hard effort reads instantly but the
        // wind-down stays legible.
        float rate = target > shown ? rising : rising * 0.35f;
        float next = shown + (target - shown) * rate;
        return Math.abs(target - next) < 0.05f ? target : next;
    }

    private String connectionSummary(S4Protocol.Status status) {
        if (!status.monitorConnected) {
            return "Waiting for the S4 monitor. Open the USB connection in diagnostics.";
        }
        if (writePathStalled) {
            return "Reading pulses only - the monitor is refusing commands. "
                    + "Unplug and reconnect the rower USB cable to clear it.";
        }
        return String.format(Locale.US,
                "Monitor live - %d packets, %d pulses, water %.1f m/s",
                status.packetsSeen, status.pulsesSeen, status.waterSpeedMps);
    }

    private void startStatusHeartbeat() {
        if (!statusHeartbeatRunning.compareAndSet(false, true)) {
            return;
        }
        statusHeartbeatThread = new Thread(() -> {
            while (statusHeartbeatRunning.get()) {
                try {
                    Thread.sleep(1000);
                    S4Protocol.Status snapshot = s4Protocol.snapshot();
                    updateRowingStatus(snapshot, true);
                    publishRowingStatus(snapshot, true);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "status-heartbeat");
        statusHeartbeatThread.start();
    }

    private void publishEvent(String type, JSONObject payload, boolean force) throws JSONException {
        if (!force && !autoUpload) {
            return;
        }

        if (TextUtils.isEmpty(serverUrl)) {
            setUploadStatus("Laptop upload: finding laptop");
            discoverLaptop();
            return;
        }

        JSONObject event = new JSONObject();
        event.put("type", type);
        event.put("clientTimeMs", System.currentTimeMillis());
        event.put("appVersion", BuildConfig.VERSION_NAME);
        event.put("androidSdk", Build.VERSION.SDK_INT);
        event.put("deviceModel", Build.MANUFACTURER + " " + Build.MODEL);
        event.put("payload", payload);

        if (!uploadQueue.offer(event.toString())) {
            // Full queue means the laptop is unreachable or slow. Shed the oldest sample so live
            // data keeps flowing rather than backing up behind stale history.
            uploadQueue.poll();
            uploadQueue.offer(event.toString());
            droppedUploads++;
        }
    }

    private void startUploader() {
        if (!uploading.compareAndSet(false, true)) {
            return;
        }
        uploadThread = new Thread(() -> {
            while (uploading.get()) {
                String body;
                try {
                    body = uploadQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                String url = serverUrl;
                if (TextUtils.isEmpty(url)) {
                    continue;
                }
                postJson(url + "/api/event", body);
            }
        }, "dashboard-uploader");
        uploadThread.setDaemon(true);
        uploadThread.start();
    }

    private void stopUploader() {
        uploading.set(false);
        if (uploadThread != null) {
            uploadThread.interrupt();
            uploadThread = null;
        }
    }

    private void postJson(String endpoint, String body) {
        HttpURLConnection connection = null;
        try {
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(data.length);
            connection.setDoOutput(true);
            OutputStream outputStream = connection.getOutputStream();
            outputStream.write(data);
            outputStream.close();
            int code = connection.getResponseCode();
            setUploadStatus("Laptop upload: " + code
                    + (droppedUploads > 0 ? " (" + droppedUploads + " dropped)" : "")
                    + " | queue " + uploadQueue.size());
        } catch (IOException e) {
            setUploadStatus("Laptop upload failed: " + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void discoverLaptop() {
        if (!discovering.compareAndSet(false, true)) {
            return;
        }

        setUploadStatus("Laptop upload: finding laptop");
        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                socket.setBroadcast(true);
                socket.setSoTimeout(1800);

                byte[] requestBytes = DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
                for (InetAddress address : discoveryBroadcastAddresses()) {
                    DatagramPacket request = new DatagramPacket(
                            requestBytes,
                            requestBytes.length,
                            address,
                            DISCOVERY_PORT);
                    socket.send(request);
                }

                byte[] responseBytes = new byte[512];
                DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length);
                socket.receive(response);
                String message = new String(
                        response.getData(),
                        response.getOffset(),
                        response.getLength(),
                        StandardCharsets.UTF_8).trim();

                if (!message.startsWith(DISCOVERY_RESPONSE)) {
                    throw new IOException("unexpected discovery response");
                }

                String discoveredUrl = normalizedServerUrl(message.substring(DISCOVERY_RESPONSE.length()));
                if (!discoveredUrl.startsWith("http://") && !discoveredUrl.startsWith("https://")) {
                    throw new IOException("invalid dashboard URL");
                }

                serverUrl = discoveredUrl;
                saveServerUrl(discoveredUrl);
                runOnUiThread(() -> serverUrlInput.setText(discoveredUrl));
                log("Found laptop dashboard at " + discoveredUrl);
                publishSimpleEvent("laptop-discovered", true);
                fetchIonToken();
                sendSnapshot("laptop-discovered", true);
            } catch (SocketTimeoutException e) {
                if (TextUtils.isEmpty(serverUrl)) {
                    setUploadStatus("Laptop upload: laptop not found");
                } else {
                    setUploadStatus("Laptop upload: using " + serverUrl);
                }
            } catch (IOException e) {
                setUploadStatus("Laptop discovery failed: " + e.getMessage());
            } finally {
                if (socket != null) {
                    socket.close();
                }
                discovering.set(false);
            }
        }, "laptop-discovery").start();
    }

    private Set<InetAddress> discoveryBroadcastAddresses() throws IOException {
        Set<InetAddress> addresses = new LinkedHashSet<>();
        addresses.add(InetAddress.getByName("255.255.255.255"));

        WifiManager wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        DhcpInfo dhcp = wifiManager == null ? null : wifiManager.getDhcpInfo();
        if (dhcp != null && dhcp.ipAddress != 0 && dhcp.netmask != 0) {
            int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            byte[] addressBytes = new byte[] {
                    (byte) (broadcast & 0xFF),
                    (byte) ((broadcast >> 8) & 0xFF),
                    (byte) ((broadcast >> 16) & 0xFF),
                    (byte) ((broadcast >> 24) & 0xFF)
            };
            addresses.add(InetAddress.getByAddress(addressBytes));
        }
        return addresses;
    }

    private void saveServerUrl(String value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        preferences.edit().putString(PREF_SERVER_URL, value).apply();
    }

    private JSONObject deviceToJson(UsbDevice device) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("deviceId", device.getDeviceId());
        object.put("deviceName", device.getDeviceName());
        object.put("friendlyName", friendlyDeviceName(device));
        object.put("vendorId", device.getVendorId());
        object.put("vendorIdHex", hex4(device.getVendorId()));
        object.put("productId", device.getProductId());
        object.put("productIdHex", hex4(device.getProductId()));
        object.put("deviceClass", usbClassName(device.getDeviceClass()));
        object.put("deviceSubclass", device.getDeviceSubclass());
        object.put("deviceProtocol", device.getDeviceProtocol());
        object.put("hasPermission", usbManager.hasPermission(device));
        object.put("manufacturerName", fallback(safeUsbString(() -> device.getManufacturerName())));
        object.put("productName", fallback(safeUsbString(() -> device.getProductName())));
        object.put("serialNumber", fallback(safeUsbString(() -> device.getSerialNumber())));

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        object.put("serialDriver", driver == null ? JSONObject.NULL : driver.getClass().getSimpleName());

        JSONArray interfaces = new JSONArray();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            JSONObject iface = new JSONObject();
            iface.put("index", i);
            iface.put("id", usbInterface.getId());
            iface.put("className", usbClassName(usbInterface.getInterfaceClass()));
            iface.put("classCode", usbInterface.getInterfaceClass());
            iface.put("subclass", usbInterface.getInterfaceSubclass());
            iface.put("protocol", usbInterface.getInterfaceProtocol());

            JSONArray endpoints = new JSONArray();
            for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(e);
                JSONObject ep = new JSONObject();
                ep.put("index", e);
                ep.put("address", endpoint.getAddress());
                ep.put("addressHex", hex2(endpoint.getAddress()));
                ep.put("direction", endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT");
                ep.put("type", endpointTypeName(endpoint.getType()));
                ep.put("maxPacketSize", endpoint.getMaxPacketSize());
                endpoints.put(ep);
            }
            iface.put("endpoints", endpoints);
            interfaces.put(iface);
        }
        object.put("interfaces", interfaces);
        return object;
    }

    private String deviceSummary(UsbDevice device) {
        return friendlyDeviceName(device)
                + "  VID:PID "
                + hex4(device.getVendorId())
                + ":"
                + hex4(device.getProductId())
                + "  permission "
                + (usbManager.hasPermission(device) ? "yes" : "no");
    }

    private String deviceDetails(UsbDevice device) {
        List<String> lines = new ArrayList<>();
        lines.add("deviceName=" + device.getDeviceName()
                + " id=" + device.getDeviceId()
                + " class=" + usbClassName(device.getDeviceClass())
                + "/" + device.getDeviceSubclass()
                + "/" + device.getDeviceProtocol());

        String manufacturer = safeUsbString(() -> device.getManufacturerName());
        String product = safeUsbString(() -> device.getProductName());
        String serial = safeUsbString(() -> device.getSerialNumber());
        if (!TextUtils.isEmpty(manufacturer) || !TextUtils.isEmpty(product) || !TextUtils.isEmpty(serial)) {
            lines.add("strings manufacturer=" + fallback(manufacturer)
                    + " product=" + fallback(product)
                    + " serial=" + fallback(serial));
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        lines.add("serialDriver=" + (driver == null ? "none detected" : driver.getClass().getSimpleName()));

        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface usbInterface = device.getInterface(i);
            lines.add("interface " + i
                    + " id=" + usbInterface.getId()
                    + " class=" + usbClassName(usbInterface.getInterfaceClass())
                    + "/" + usbInterface.getInterfaceSubclass()
                    + "/" + usbInterface.getInterfaceProtocol()
                    + " endpoints=" + usbInterface.getEndpointCount());

            for (int e = 0; e < usbInterface.getEndpointCount(); e++) {
                UsbEndpoint endpoint = usbInterface.getEndpoint(e);
                lines.add("  endpoint " + e
                        + " addr=0x" + hex2(endpoint.getAddress())
                        + " " + (endpoint.getDirection() == UsbConstants.USB_DIR_IN ? "IN" : "OUT")
                        + " " + endpointTypeName(endpoint.getType())
                        + " maxPacket=" + endpoint.getMaxPacketSize());
            }
        }

        return joinLines(lines);
    }

    private String friendlyDeviceName(UsbDevice device) {
        String product = safeUsbString(() -> device.getProductName());
        if (!TextUtils.isEmpty(product)) {
            return product;
        }
        String manufacturer = safeUsbString(() -> device.getManufacturerName());
        if (!TextUtils.isEmpty(manufacturer)) {
            return manufacturer + " USB device";
        }
        return "USB device " + device.getDeviceId();
    }

    private void logBytes(String prefix, byte[] buffer, int count) {
        byte[] copy = new byte[count];
        System.arraycopy(buffer, 0, copy, 0, count);
        log(prefix + " " + count + " byte(s)"
                + " | hex " + toHex(copy)
                + " | ascii " + toPrintableAscii(copy));
        s4Protocol.accept(copy, count);
        long now = System.currentTimeMillis();
        if (now - lastRawUploadMs >= 250) {
            lastRawUploadMs = now;
            publishRawBytes(prefix, copy);
        }
    }

    private void log(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        runOnUiThread(() -> {
            logView.append(timestamp + "  " + message + "\n");
            int maxChars = 24000;
            if (logView.length() > maxChars) {
                CharSequence tail = logView.getText().subSequence(logView.length() - maxChars, logView.length());
                logView.setText(tail);
            }
        });
    }

    private void setUploadStatus(String message) {
        runOnUiThread(() -> uploadStatusView.setText(message));
    }

    private static String serverUrlFromIntent(Intent intent) {
        if (intent == null) {
            return "";
        }
        Uri data = intent.getData();
        if (data == null) {
            return "";
        }
        String server = data.getQueryParameter("server");
        return server == null ? "" : server;
    }

    private static String normalizedServerUrl(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!TextUtils.isEmpty(trimmed)
                && !trimmed.startsWith("http://")
                && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        return trimmed;
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            String normalized = normalizedServerUrl(value);
            if (!TextUtils.isEmpty(normalized)) {
                return normalized;
            }
        }
        return "";
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 3);
        for (byte value : bytes) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(hex2(value & 0xFF));
        }
        return builder.toString();
    }

    private static String toPrintableAscii(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int b = value & 0xFF;
            if (b >= 32 && b <= 126) {
                builder.append((char) b);
            } else if (b == '\r') {
                builder.append("\\r");
            } else if (b == '\n') {
                builder.append("\\n");
            } else {
                builder.append('.');
            }
        }
        return builder.toString();
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(getColorCompat(R.color.text_primary));
        return button;
    }

    private TextView addMetric(LinearLayout row, String label, String initialValue, boolean accent) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setPadding(dp(10), dp(9), dp(10), dp(9));
        cell.setBackgroundColor(getColorCompat(R.color.surface));

        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(9);
        caption.setLetterSpacing(0.1f);
        caption.setTypeface(Typeface.DEFAULT_BOLD);
        caption.setTextColor(getColorCompat(R.color.text_faint));
        cell.addView(caption);

        TextView value = new TextView(this);
        value.setText(initialValue);
        value.setTextSize(24);
        value.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        value.setTextColor(getColorCompat(accent ? R.color.primary : R.color.text_primary));
        cell.addView(value);

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = dp(2);
        row.addView(cell, params);
        return value;
    }

    private static String formatElapsed(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private static String formatPace(int secondsPer500m) {
        if (secondsPer500m <= 0 || secondsPer500m > 3599) {
            return "--:--";
        }
        return String.format(Locale.US, "%d:%02d", secondsPer500m / 60, secondsPer500m % 60);
    }

    private static String formatAge(long ageMs) {
        if (ageMs < 0) {
            return "none";
        }
        if (ageMs < 1000) {
            return ageMs + " ms";
        }
        return String.format(Locale.US, "%.1f s", ageMs / 1000f);
    }

    private TextView sectionHeader(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextSize(16);
        header.setTextColor(getColorCompat(R.color.text_primary));
        header.setPadding(0, dp(14), 0, dp(6));
        return header;
    }

    private TextView bodyText(String text) {
        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(13);
        body.setTextColor(getColorCompat(R.color.text_secondary));
        body.setTypeface(Typeface.MONOSPACE);
        return body;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int id) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(id);
        }
        return getResources().getColor(id);
    }

    private int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private static void safeClose(UsbSerialPort port) {
        try {
            port.close();
        } catch (IOException ignored) {
        }
    }

    private static String safeUsbString(UsbStringGetter getter) {
        try {
            return getter.get();
        } catch (SecurityException ignored) {
            return "";
        }
    }

    private static String fallback(String value) {
        return TextUtils.isEmpty(value) ? "n/a" : value;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(lines.get(i));
        }
        return builder.toString();
    }

    private static String usbClassName(int usbClass) {
        switch (usbClass) {
            case UsbConstants.USB_CLASS_APP_SPEC:
                return "app-specific";
            case UsbConstants.USB_CLASS_AUDIO:
                return "audio";
            case UsbConstants.USB_CLASS_CDC_DATA:
                return "cdc-data";
            case UsbConstants.USB_CLASS_COMM:
                return "communication";
            case UsbConstants.USB_CLASS_CONTENT_SEC:
                return "content-security";
            case UsbConstants.USB_CLASS_CSCID:
                return "smart-card";
            case UsbConstants.USB_CLASS_HID:
                return "hid";
            case UsbConstants.USB_CLASS_HUB:
                return "hub";
            case UsbConstants.USB_CLASS_MASS_STORAGE:
                return "mass-storage";
            case UsbConstants.USB_CLASS_MISC:
                return "misc";
            case UsbConstants.USB_CLASS_PER_INTERFACE:
                return "per-interface";
            case UsbConstants.USB_CLASS_PHYSICA:
                return "physical";
            case UsbConstants.USB_CLASS_PRINTER:
                return "printer";
            case UsbConstants.USB_CLASS_STILL_IMAGE:
                return "still-image";
            case UsbConstants.USB_CLASS_VENDOR_SPEC:
                return "vendor-specific";
            case UsbConstants.USB_CLASS_VIDEO:
                return "video";
            case UsbConstants.USB_CLASS_WIRELESS_CONTROLLER:
                return "wireless-controller";
            default:
                return "class-" + usbClass;
        }
    }

    private static String endpointTypeName(int type) {
        switch (type) {
            case UsbConstants.USB_ENDPOINT_XFER_BULK:
                return "bulk";
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL:
                return "control";
            case UsbConstants.USB_ENDPOINT_XFER_INT:
                return "interrupt";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC:
                return "isochronous";
            default:
                return "type-" + type;
        }
    }

    private static String hex4(int value) {
        return String.format(Locale.US, "%04X", value & 0xFFFF);
    }

    private static String hex2(int value) {
        return String.format(Locale.US, "%02X", value & 0xFF);
    }

    private interface UsbStringGetter {
        String get();
    }
}
