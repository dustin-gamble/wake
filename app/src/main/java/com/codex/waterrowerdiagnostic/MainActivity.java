package com.codex.waterrowerdiagnostic;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.bluetooth.BluetoothAdapter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
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
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
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

public class MainActivity extends Activity
        implements CoastFlightGame.Host, HandleSensor.Listener, HeartRateSensor.Listener {
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
    // Lowered a fourth time (0.6 -> 0.3 -> 0.12 -> 0.04) on the rower's word that speed should
    // bleed away slowly. At 3.85 m/s over a 2.4 s stroke gap, 0.12 shed 53% of the speed; 0.04
    // sheds 27%.
    private static final float[] DRAG_OPTIONS = {0.02f, 0.03f, 0.04f, 0.06f, 0.08f, 0.12f};
    private static final String[] DRAG_LABELS = {
            "Longest glide", "Very long glide", "Long glide", "Medium glide", "Short glide",
            "Quick stop"};
    /**
     * Gap between S4 commands. Lower means fresher gauges; too low historically wedged the write
     * path, so it is adjustable from diagnostics and reported with every event.
     */
    private volatile long commandGapMs = 150;
    private volatile float coastDrag = 0.04f;

    /** Swappable content area: home, instruments, or a game. Drawer and scrim sit above it. */
    private FrameLayout screenHost;
    private View homeScreen;
    private View instrumentsScreen;
    private GameView currentGame;
    /** Kept for the life of the app: one WebView, reused, rather than one per entry. */
    private CoastFlightGame coastFlight;
    private TextView gameClock;
    private GaugeStripView gameStrip;
    /** Local builds only: the camera button over every screen. Null in the published build. */
    private View shotButton;
    private PersonalBests personalBests;
    private final java.util.List<TextView> pbLabels = new java.util.ArrayList<>();

    /**
     * Lifetime metres for the Journey. The monitor's own distance counter accumulates across
     * sessions and can be reset on the S4, so the app tracks its own base and re-bases on a reset.
     */
    private long sessionFirstStrokeMs;
    /** Lifetime + session metres when this session's first stroke landed. */
    private double sessionStartMetres = -1;
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
    /**
     * Ride through a write blackout instead of reacting to it. Six probes on 3.9.0 showed every
     * host-to-device transfer failing at once - bulk and control alike - with the connection alive
     * and the interface claimed, then working again 0.1-0.7s later. Retrying the same command
     * every 100ms for up to a second outlasts every blackout measured.
     */
    private static final int WRITE_RETRIES = 10;
    private static final long WRITE_RETRY_GAP_MS = 100;
    private static final long WRITE_RETRY_BUDGET_MS = 1000;
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
    private TextView controllerView;
    private TextView handleView;
    private HandleSensor handleSensor;
    private String handleState = "Handle sensor: not started";
    private HeartRateSensor heartSensor;
    private TextView heartView;
    private String heartState = "Heart strap: not started";
    /** From the Bluetooth strap; 0 without one. */
    private int bleHeartRate;
    private static final int PERM_HEART = 4712;
    /** Roll treated as straight ahead; the sensor can be strapped on at any angle. */
    private float handleZeroRoll = Float.NaN;
    private float handleRoll;
    private float steering;
    private static final int PERM_SCAN = 4711;
    /** Full lock at this much roll from neutral. A wrist rolls comfortably about this far. */
    private static final float STEER_FULL_DEG = 35f;
    private static final float STEER_DEADZONE_DEG = 3f;
    /** Latest gamepad state. Steering lives here so no game has to know about Bluetooth. */
    private String controllerName = "";
    private float padX;
    private float padY;
    private String padButton = "";
    private long padLastMs;
    private TextView logView;
    private TextView stateChip;
    private TextView connectionBanner;
    private TextView strokesValue;
    private TextView kcalValue;
    private StrokeShapeView strokeShapeView;
    private TextView workValue;
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
    /** Write-path probe: rate-limited, because it holds the I/O lock while it runs. */
    private static final long PROBE_EVERY_MS = 10000;
    // 18 reports gave one identical verdict; three a session is enough to see if it changes.
    private static final int MAX_PROBES_PER_SESSION = 4;
    private static final int PROBE_TIMEOUT_MS = 250;
    private long lastProbeMs;
    private int probesThisSession;
    /** Set once clearing an endpoint halt has been seen to recover a refused write. */
    private volatile boolean clearHaltRecovers;
    private UsbInterface claimedInterface;
    /** CDC data interface (bulk IN/OUT), kept so the claim can be re-taken after a write fails. */
    private UsbInterface dataInterface;
    /** CDC control interface (class 2). The kernel cdc_acm driver binds here, so reclaim it too. */
    private UsbInterface controlInterface;
    /** Rate limit for reclaiming, so a tug-of-war over the interface cannot spin the CPU. */
    private static final long RECLAIM_MIN_GAP_MS = 250;
    private long lastReclaimMs;
    private long lastReclaimReportMs;
    private int reclaimsThisSession;
    /** True once onStop has given the rower back, so onStart knows to take it again. */
    private boolean releasedForBackground;
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
    private DemoRower demoRower;
    /** The rower's learned range, shared by every game. See RowerProfile. */
    private final RowerProfile profile = new RowerProfile();
    /** Levels, the weekly goal and the streak. */
    private final Progress progress = new Progress();
    private HomeProgressView progressView;
    private TextView continueChip;
    private double lastProgressMetres = -1;
    /** Every card's action by title, so CONTINUE can reopen the last game played. */
    private final java.util.HashMap<String, View.OnClickListener> cardActions = new java.util.HashMap<>();
    /** Per-stroke power and rate for this run of the app, for Session Art. */
    private final java.util.ArrayList<float[]> sessionStrokes = new java.util.ArrayList<>();
    private PulseMeter.Stroke lastArtStroke;
    private boolean sessionArtShown;

    /* SHUFFLE: random games back to back, one rowing clock across them all. */
    private static final int[] SHUFFLE_MINUTES = {1, 2, 3, 5};
    private int shuffleMinutesIndex = 1;
    private boolean shuffleActive;
    /** True while SHUFFLE itself is changing screens, so showScreen does not end it. */
    private boolean shuffleSwitching;
    private double shuffleAccumSeconds;
    private ShuffleBag shuffleBag;
    private TextView shuffleNextChip;
    /** Latest status on the UI thread, for once-a-second sampling. */
    private S4Protocol.Status lastStatus;
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
        applyAutomation(getIntent());
        if (personalBests.getString("tour.done") == null) {
            personalBests.putString("tour.done", "1");
            screenHost.post(() -> showHelpPage(0));
        }
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
        applyAutomation(intent);
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

    /**
     * Take the rower again when WAKE comes back on screen - but only if onStop gave it back.
     *
     * <p>onStart also runs straight after onCreate, where auto-connect already opens the port;
     * the flag stops that turning into a double open.
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (releasedForBackground) {
            releasedForBackground = false;
            resetReopenBudget();
            if (selectedDevice != null) {
                log("WAKE is back on screen; taking the rower again");
                openSelectedDevice();
            }
        }
    }

    /**
     * Give the rower back the moment WAKE leaves the screen.
     *
     * <p>WAKE now reclaims the rower from whatever takes it while WAKE is showing. That is only
     * acceptable because it stops the instant WAKE is not: Ergatta must get the rower straight
     * back and keep working as the safe fallback. Before this, WAKE held its connection open when
     * merely backgrounded - which, with reclaiming added, would have kept stealing from Ergatta
     * out of sight.
     */
    @Override
    protected void onStop() {
        saveMeasuredCalibration();
        commitProfile(RowerProfile.MIN_MINUTES);
        saveProgress();
        if (!isChangingConfigurations() && openConnection != null) {
            releasedForBackground = true;
            publishSimpleEvent("s4-interface-released", true);
            closeCurrentConnection();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        uiTicker.removeCallbacksAndMessages(null);
        if (demoRower != null) {
            demoRower.stop();
        }
        closeCurrentConnection();
        stopUploader();
        unregisterReceiver(usbReceiver);
        super.onDestroy();
    }

    private View buildUi() {
        personalBests = new PersonalBests(this);
        autoUpload = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_STREAM, false);
        journeyLifetime = personalBests.get("journey.total", 0f);
        loadCalibration();
        profile.decode(personalBests.getString("profile"));
        progress.decode(personalBests.getString("progress"));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(getColorCompat(R.color.background));

        screenHost = new FrameLayout(this);
        frame.addView(screenHost, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        if (BuildConfig.SCREENSHOT_UPLOAD) {
            addShotButton(frame);
        }
        addDiagnosticsDrawer(frame);

        instrumentsScreen = buildInstruments();
        homeScreen = buildHome();
        showScreen(homeScreen);
        return frame;
    }

    /**
     * A camera button on the right edge of every screen, for getting screenshots off a
     * kiosk-locked tablet: tap it and the screen goes to the laptop. The long-presses were
     * invisible and had to be remembered; this is what the rower asked for.
     *
     * <p>Local builds only - the call is inside {@code BuildConfig.SCREENSHOT_UPLOAD}, so the
     * published APK never creates it. Added before the drawer, so an open drawer covers it.
     */
    private void addShotButton(FrameLayout frame) {
        if (BuildConfig.SCREENSHOT_UPLOAD) {
            TextView shot = new TextView(this);
            shot.setText("\uD83D\uDCF7");
            shot.setTextSize(20);
            shot.setGravity(Gravity.CENTER);
            GradientDrawable ring = new GradientDrawable();
            ring.setShape(GradientDrawable.OVAL);
            ring.setColor(0xCC10203A);
            ring.setStroke(dp(2), getColorCompat(R.color.primary));
            shot.setBackground(ring);
            shot.setAlpha(0.8f);
            shot.setContentDescription("Send a screenshot to the laptop");
            shot.setOnClickListener(v -> captureScreenshot(screenLabel()));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(54), dp(54),
                    Gravity.END | Gravity.CENTER_VERTICAL);
            params.rightMargin = dp(10);
            frame.addView(shot, params);
            shotButton = shot;
        }
    }

    private void showScreen(View screen) {
        if (!shuffleSwitching) {
            shuffleActive = false;   // leaving to anything else ends the shuffle
        }
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
            refreshProgress();
            if (!sessionArtShown && sessionStrokes.size() >= 60) {
                sessionArtShown = true;
                screenHost.post(this::showSessionArt);
            }
            if (lastSessionCard != null) {
                lastSessionCard.setText(lastSessionText());
            }
        }
    }

    /* ---------- automated testing (local builds only) ---------- */

    /**
     * Lets an emulator row a game with nobody on the machine, for screenshots:
     * {@code adb shell am start -S -n <pkg>/com.codex.waterrowerdiagnostic.MainActivity
     * --ez demo true --es game "ZOMBIE RUN"}. {@code demo} starts {@link DemoRower} feeding the
     * protocol the bytes a monitor would send; {@code game} taps the home card with that title.
     * Inside {@code BuildConfig.SCREENSHOT_UPLOAD}, so the public build has neither.
     */
    private void applyAutomation(Intent intent) {
        if (BuildConfig.SCREENSHOT_UPLOAD && intent != null) {
            if (intent.getBooleanExtra("demo", false) && demoRower == null) {
                personalBests.putString("tour.done", "1");
                demoRower = new DemoRower(intent.getIntExtra("demoWatts", 130));
                // After onCreate's refreshDevices, which closes the connection and its heartbeat.
                screenHost.postDelayed(() -> {
                    log("Demo rower started - simulated monitor, no USB");
                    demoRower.start(s4Protocol::accept);
                    startStatusHeartbeat();
                }, 1500);
            }
            String game = intent.getStringExtra("game");
            if (game != null) {
                screenHost.postDelayed(() -> {
                    View.OnClickListener action = cardActions.get(game);
                    if (action != null) {
                        showHome();
                        action.onClick(screenHost);
                    } else {
                        log("Automation: no card titled " + game);
                    }
                }, 2000);
            }
            // Screens that are not home cards: --es screen RECORDS | HELP | CALIBRATE | CANYON_DRIVE | SESSION_ART
            String screen = intent.getStringExtra("screen");
            if (screen != null) {
                screenHost.postDelayed(() -> {
                    showHome();
                    switch (screen) {
                        case "RECORDS":
                            showRecords();
                            break;
                        case "HELP":
                            showHelpPage(0);
                            break;
                        case "CALIBRATE":
                            openCalibrate();
                            break;
                        case "CANYON_DRIVE":
                            openCanyon(false);
                            break;
                        case "SESSION_ART":
                            showSessionArt();
                            break;
                        default:
                            log("Automation: no screen " + screen);
                    }
                }, 2000);
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
        game.setProfile(profile);
        game.setHeartRate(bleHeartRate);
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
        if (BuildConfig.SCREENSHOT_UPLOAD) {
            title.setOnLongClickListener(v -> {
                captureScreenshot("home");
                return true;
            });
        }
        header.addView(title);
        TextView sub = new TextView(this);
        sub.setText("  v" + BuildConfig.VERSION_NAME);
        sub.setTextSize(10);
        sub.setTextColor(getColorCompat(R.color.text_faint));
        header.addView(sub, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView records = chip("RECORDS");
        records.setOnClickListener(v -> showRecords());
        header.addView(records);
        TextView calibrate = chip("CALIBRATE");
        calibrate.setOnClickListener(v -> openCalibrate());
        header.addView(calibrate);
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

        // Reasons to come back: the week's goal, the level and the streak, and a way back in.
        LinearLayout progressRow = new LinearLayout(this);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        progressView = new HomeProgressView(this);
        progressView.setOnLongClickListener(v -> {
            float[] goals = {30f, 60f, 90f, 120f, 180f};
            float current = progress.goalMinutes();
            float next = goals[0];
            for (int i = 0; i < goals.length; i++) {
                if (goals[i] == current) {
                    next = goals[(i + 1) % goals.length];
                }
            }
            progress.setGoalMinutes(next);
            saveProgress();
            refreshProgress();
            toast("Weekly goal: " + Math.round(next) + " minutes");
            return true;
        });
        progressRow.addView(progressView, new LinearLayout.LayoutParams(0, dp(58), 1f));
        continueChip = chip("CONTINUE");
        continueChip.setOnClickListener(v -> {
            View.OnClickListener action = cardActions.get(personalBests.getString("last.game"));
            if (action != null) {
                action.onClick(v);
            } else {
                toast("Pick a game - CONTINUE will bring you back to it");
            }
        });
        progressRow.addView(continueChip);
        TextView artChip = chip("SESSION ART");
        artChip.setOnClickListener(v -> showSessionArt());
        progressRow.addView(artChip);
        TextView helpChip = chip("HELP");
        helpChip.setOnClickListener(v -> showHelpPage(0));
        progressRow.addView(helpChip);
        root.addView(progressRow, marginTop(dp(4)));

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
        cards.add(gridCard("SHUFFLE", GameIconView.Kind.SHUFFLE, 0xFFB48CFF, "shuffle.minutes", "min", v -> startShuffle()));
        cards.add(gridCard("ZONE ROW", GameIconView.Kind.ZONEROW, warn, "zonerow.10", "m", v -> openZoneRow()));
        cards.add(gridCard("RIVER", GameIconView.Kind.RIVER, 0xFF5AA7D6, "river.km", "km", v -> openGame(new RiverExplorerGame(this, personalBests), "RIVER EXPLORER")));
        cards.add(gridCard("COACH", GameIconView.Kind.COACH, accent, "coach.score", "score", v -> openGame(new StrokeCoachGame(this, personalBests), "STROKE COACH")));
        cards.add(gridCard("ZOMBIE RUN", GameIconView.Kind.ZOMBIE, bad, "zombie.150", "m", v -> openZombieRun()));
        cards.add(gridCard("ROW RUNNER", GameIconView.Kind.RUNNER, 0xFFE84C3D, "runner.distance", "m", v -> openRowRunner()));
        cards.add(gridCard("COAST FLIGHT", GameIconView.Kind.FLY, 0xFF7FC6EE, null, null, v -> openCoastFlight()));
        cards.add(gridCard("SKYLINE", GameIconView.Kind.CITY, 0xFF9A6BB0, "city.blocks", "blocks", v -> openSkyline()));
        cards.add(gridCard("WAVE RIDER", GameIconView.Kind.SURF, 0xFF7FC6EE, "surf.score", "pts", v -> openWaveRider()));
        cards.add(gridCard("ROCKET LAUNCH", GameIconView.Kind.ROCKET, 0xFFBFE3FF, "rocket.altitude", "", v -> openRocketLaunch()));
        cards.add(gridCard("CANYON", GameIconView.Kind.CANYON, warn, "canyon.gates", "gates", v -> openCanyon(true)));
        cards.add(gridCard("MEGA PULL", GameIconView.Kind.MEGAPULL, 0xFFF5C518, "megapull.peak", "W", v -> openMegaPull()));
        cards.add(gridCard("RACE", GameIconView.Kind.GHOST, blue, "time.1000", "1k", v -> openRace()));
        cards.add(gridCard("CREW BOAT", GameIconView.Kind.CREW, 0xFF3A5BD9, "crew.sync", "% sync", v -> openGame(new CrewBoatGame(this, personalBests), "CREW BOAT")));
        cards.add(gridCard("REGATTA", GameIconView.Kind.REGATTA, warn, "regatta.best", "", v -> openGame(new RegattaGame(this, personalBests), "REGATTA")));
        cards.add(gridCard("DAILY ROW", GameIconView.Kind.DAILY, bad, "daily.streak", "day streak", v -> openGame(new DailyRowGame(this, personalBests), "DAILY ROW")));
        cards.add(gridCard("NIGHT GRID", GameIconView.Kind.GRID, 0xFFF5C518, "grid.houses", "houses", v -> openGame(new NightGridGame(this, personalBests), "NIGHT GRID")));
        cards.add(gridCard("HEAD RACE", GameIconView.Kind.HEADRACE, warn, "time.2000", "2k", v -> openHeadRace()));
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
        int chromeDp = 160;  // header, progress row, last-session line and the paddings
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
        cardActions.put(title, onTap);
        card.setOnClickListener(v -> {
            if (!"GAUGES".equals(title)) {
                personalBests.putString("last.game", title);
            }
            onTap.onClick(v);
        });

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
                    : key.equals("regatta.best") ? RegattaGame.DIVISIONS[Math.max(0, Math.min(RegattaGame.DIVISIONS.length - 1, Math.round(v) - 1))]
                    : key.equals("river.km") ? String.format(Locale.US, "%.1f", v)
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
                sessionStartMetres = journeyLifetime + journeySession;
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

    /**
     * Metres rowed since this session's first stroke. Not journeySession: that is folded into the
     * lifetime total every minute, so on the tablet "Last session" read 46 m and Session Art 0 m.
     */
    private double sessionMetres() {
        return sessionStartMetres < 0 ? 0 : Math.max(0, journeyLifetime + journeySession - sessionStartMetres);
    }

    /** Remembers this session's headline numbers for the home screen. */
    private void saveLastSession() {
        if (sessionFirstStrokeMs == 0 || sessionMetres() < 20) {
            return;
        }
        float seconds = (System.currentTimeMillis() - sessionFirstStrokeMs) / 1000f;
        personalBests.putString("last.session", String.format(Locale.US, "%d|%d|%d",
                Math.round(sessionMetres()), Math.round(seconds),
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

    /* ---------- pace boat ---------- */

    // Horde paces for Zombie Run, around the measured 2:08 median (3221 samples; best 1:59).
    private static final float[] PACE_CHOICES = {145f, 138f, 132f, 126f, 120f, 114f};
    private static final int[] DISTANCE_CHOICES = {500, 1000, 2000, 5000};

    /** A game with no controls of its own: the standard chrome and vitals strip. */
    private void openGame(GameView game, String title) {
        showGame(game, gameScreen(title, game, null));
    }

    /** RACE: one card, four opponents. Pace choices sit around the rower's own typical split. */
    private void openRace() {
        GhostRaceGame game = new GhostRaceGame(this, personalBests);
        game.setProfile(profile);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView who = chip("VS " + game.opponent().label);
        TextView pace = chip(PersonalBests.formatPace(game.targetPace()) + " /500");
        who.setOnClickListener(v -> {
            game.nextOpponent();
            who.setText("VS " + game.opponent().label);
            pace.setVisibility(game.opponent() == GhostRaceGame.Opponent.PACE ? View.VISIBLE : View.GONE);
        });
        row.addView(who);
        int typical = (int) Math.round(profile.typicalSplit());
        final float[] choices = {typical + 12, typical + 6, typical + 2, typical - 2, typical - 6, typical - 12};
        pace.setOnClickListener(v -> {
            int i = 0;
            for (int k = 0; k < choices.length; k++) {
                if (choices[k] == game.targetPace()) {
                    i = (k + 1) % choices.length;
                }
            }
            game.choosePace(choices[i]);
            pace.setText(PersonalBests.formatPace(choices[i]) + " /500");
            game.start();
        });
        pace.setVisibility(View.GONE);
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
        TextView share = chip("SHARE");
        share.setOnClickListener(v -> shareRecording(game));
        row.addView(share);
        TextView importChip = chip("IMPORT");
        importChip.setOnClickListener(v -> importRecording(game));
        row.addView(importChip);
        showGame(game, gameScreen("RACE", game, row));
    }

    /** CANYON: fly through rings on speed, or drive the gorge (steered by the handle sensor). */
    private void openCanyon(boolean fly) {
        GameView game = fly ? new CanyonFlightGame(this, personalBests) : new CanyonChaseGame(this, personalBests);
        TextView mode = chip(fly ? "MODE: FLY" : "MODE: DRIVE");
        mode.setOnClickListener(v -> openCanyon(!fly));
        showGame(game, gameScreen("CANYON", game, mode));
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

    /* ---------- shuffle ---------- */

    private interface GameMaker {
        GameView make();
    }

    private static final String[] SHUFFLE_TITLES = {
            "ZOMBIE RUN", "ROW RUNNER", "SKYLINE", "WAVE RIDER", "CANYON", "ROCKET LAUNCH", "MEGA PULL",
            "RACE", "CREW BOAT", "NIGHT GRID", "HEAD RACE", "TUG OF WAR", "COLLECTOR", "RIVER EXPLORER",
            "STROKE COACH"};

    /**
     * The games SHUFFLE deals from. Left out on purpose: Coast Flight (its map takes a while to load
     * and needs internet), Zone Row (its own timed piece, without the vitals strip), and Regatta and
     * Daily Row (once a day - a random visit would spend the ranked race or the day's challenge).
     */
    private GameView shuffleGame(int i) {
        GameMaker[] makers = {
                () -> new ZombieRunGame(this, personalBests),
                () -> new RowRunnerGame(this, personalBests),
                () -> new SkylineGame(this, personalBests),
                () -> new WaveRiderGame(this, personalBests),
                () -> new CanyonFlightGame(this, personalBests),
                () -> new RocketLaunchGame(this, personalBests),
                () -> new MegaPullGame(this, personalBests),
                () -> new GhostRaceGame(this, personalBests),
                () -> new CrewBoatGame(this, personalBests),
                () -> new NightGridGame(this, personalBests),
                () -> new HeadRaceGame(this, personalBests),
                () -> new TugOfWarGame(this, personalBests),
                () -> new CollectorGame(this, personalBests),
                () -> new RiverExplorerGame(this, personalBests),
                () -> new StrokeCoachGame(this, personalBests)};
        return makers[i].make();
    }

    private void startShuffle() {
        shuffleAccumSeconds = 0;
        shuffleBag = new ShuffleBag(SHUFFLE_TITLES.length, new java.util.Random());
        shuffleActive = true;
        dealShuffle(0f);
    }

    /** Next game from the bag, with the speed needle carried over from the last one. */
    private void dealShuffle(float carrySpeed) {
        int pick = shuffleBag.next();
        GameView game = shuffleGame(pick);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        shuffleNextChip = chip("NEXT IN " + SHUFFLE_MINUTES[shuffleMinutesIndex] + ":00");
        row.addView(shuffleNextChip);
        TextView skip = chip("SKIP  \u25B6");
        skip.setOnClickListener(v -> skipShuffle());
        row.addView(skip);
        TextView length = chip(SHUFFLE_MINUTES[shuffleMinutesIndex] + " MIN EACH");
        length.setOnClickListener(v -> {
            shuffleMinutesIndex = (shuffleMinutesIndex + 1) % SHUFFLE_MINUTES.length;
            length.setText(SHUFFLE_MINUTES[shuffleMinutesIndex] + " MIN EACH");
        });
        row.addView(length);
        shuffleSwitching = true;
        try {
            showGame(game, gameScreen("SHUFFLE  \u00b7  " + SHUFFLE_TITLES[pick], game, row));
        } finally {
            shuffleSwitching = false;
        }
        shuffleActive = true;
        game.seedSpeed(carrySpeed);
    }

    private void skipShuffle() {
        if (!shuffleActive || currentGame == null) {
            return;
        }
        shuffleAccumSeconds += currentGame.activeSeconds();
        dealShuffle(currentGame.boatSpeed());
    }

    /** Once a second: the countdown, the switch, and the record for the longest shuffle. */
    private void shuffleTick() {
        if (!shuffleActive || currentGame == null || shuffleNextChip == null) {
            return;
        }
        double left = SHUFFLE_MINUTES[shuffleMinutesIndex] * 60.0 - currentGame.activeSeconds();
        String upcoming = shuffleBag.peek() >= 0 ? SHUFFLE_TITLES[shuffleBag.peek()] : "A SURPRISE";
        shuffleNextChip.setText(left <= 6
                ? "NEXT: " + upcoming + "  " + Math.max(0, (int) Math.ceil(left))
                : "NEXT IN " + PersonalBests.formatTime((float) Math.max(0, left)));
        double total = shuffleAccumSeconds + currentGame.activeSeconds();
        if (total >= 60) {
            personalBests.recordHighest("shuffle.minutes", (float) (total / 60.0));
        }
        if (left <= 0) {
            skipShuffle();
        }
    }

    /* ---------- progress, session art, help, sharing ---------- */

    /** Once a second: rowing time (only while strokes land) and metres to today's log. */
    private void logProgressSecond() {
        double total = journeyLifetime + journeySession;
        double metres = lastProgressMetres < 0 ? 0 : Math.max(0, total - lastProgressMetres);
        lastProgressMetres = total;
        boolean rowing = lastStatus != null && lastStatus.stillRowing;
        if (rowing || metres > 0) {
            progress.addRowing(RegattaGame.today(), rowing ? 1f : 0f, (float) Math.min(metres, 20));
        }
    }

    private void saveProgress() {
        if (personalBests != null) {
            personalBests.putString("progress", progress.encode());
        }
    }

    private void refreshProgress() {
        if (progressView == null) {
            return;
        }
        long today = RegattaGame.today();
        progressView.set(progress.totalXp(), progress.weekMinutes(today), progress.goalMinutes(), progress.streak(today));
        String last = personalBests.getString("last.game");
        continueChip.setText(last == null ? "CONTINUE" : "CONTINUE  " + last + "  \u25B6");
    }

    /** The finished session as a poster: one spoke per stroke. */
    private void showSessionArt() {
        if (sessionStrokes.size() < 10) {
            toast("Row a session first - the art is drawn from your strokes");
            return;
        }
        int n = sessionStrokes.size();
        float[] power = new float[n];
        float[] rate = new float[n];
        for (int i = 0; i < n; i++) {
            power[i] = sessionStrokes.get(i)[0];
            rate[i] = sessionStrokes.get(i)[1];
        }
        float seconds = sessionFirstStrokeMs > 0 ? (System.currentTimeMillis() - sessionFirstStrokeMs) / 1000f : 0f;
        double kcal = lastStatus != null ? lastStatus.meter.kcal() : 0;
        SessionArtView art = new SessionArtView(this);
        art.setSession(power, rate,
                java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date()),
                Math.round(sessionMetres()) + " m", PersonalBests.formatTime(seconds),
                (sessionWattSamples > 0 ? Math.round(sessionWattSum / sessionWattSamples) : 0) + " W avg",
                Math.round(kcal) + " kcal");

        FrameLayout frame = new FrameLayout(this);
        frame.addView(art, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        TextView back = chip("\u2039  HOME");
        back.setOnClickListener(v -> showHome());
        bar.addView(back);
        if (BuildConfig.SCREENSHOT_UPLOAD) {
            TextView send = chip("SEND TO LAPTOP");
            send.setOnClickListener(v -> captureScreenshot("session-art"));
            bar.addView(send);
        }
        frame.addView(bar, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        showScreen(frame);
    }

    /** First-run tour and HELP: a few short pages, the last ones about numbers and getting back. */
    private void showHelpPage(int page) {
        String[][] pages = {
                {"Welcome to WAKE",
                        "WAKE reads your rowing monitor and turns every stroke into gauges and games.\n\n"
                                + "Take a stroke now: the needles rise with the drive and ease off after it, "
                                + "the way the boat runs."},
                {"Pick anything",
                        "Every game tunes itself to you. WAKE learns your typical power, speed and stroke "
                                + "rate as you row, so targets stay a stretch, not a wall.\n\n"
                                + "CONTINUE on the home screen takes you straight back to your last game."},
                {"What your numbers mean",
                        String.format(Locale.US, "Split: time per 500 m - lower is faster. Yours is typically %s.\n"
                                        + "Stroke rate: strokes per minute - yours is about %d.\n"
                                        + "Watts: power you put in - typically %d W for you.\n"
                                        + "Drive : recovery: coaches aim for 1 : 2.\n"
                                        + "kcal: food energy, from measured work at 25%% muscle efficiency.",
                                PersonalBests.formatPace((float) profile.typicalSplit()),
                                Math.round(profile.typicalRate()), Math.round(profile.typicalWatts()))},
                {"Real calories",
                        "Tap CALIBRATE with a luggage scale and a tape measure (about 10 minutes) and WAKE "
                                + "measures your work from the paddle itself instead of the monitor's formula."},
                {"Back to Ergatta",
                        "Exit WAKE with the X at the top right. If Ergatta does not read your strokes "
                                + "afterwards, restart the tablet - that always hands the rower back."}
        };
        if (page < 0 || page >= pages.length) {
            return;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(pages[page][0] + "  (" + (page + 1) + "/" + pages.length + ")")
                .setMessage(pages[page][1])
                .setNegativeButton("Close", (d, w) -> d.dismiss());
        if (page + 1 < pages.length) {
            dialog.setPositiveButton("Next", (d, w) -> showHelpPage(page + 1));
        } else {
            dialog.setPositiveButton("Row", (d, w) -> d.dismiss());
        }
        dialog.show();
    }

    /** Uploads your best recording at the race distance to the laptop, to hand to a friend. */
    private void shareRecording(GhostRaceGame game) {
        String samples = game.bestRecording();
        if (samples == null || game.bestTime() <= 0) {
            toast("Finish a " + game.raceMeters() + " m race first - your best is what gets shared");
            return;
        }
        if (TextUtils.isEmpty(serverUrl)) {
            toast("Sharing goes through the laptop dashboard - start it first");
            return;
        }
        String name = personalBests.getString("rower.name");
        if (name == null) {
            android.widget.EditText input = new android.widget.EditText(this);
            input.setHint("Your rower name");
            new AlertDialog.Builder(this)
                    .setTitle("Name on your recording")
                    .setView(input)
                    .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                    .setPositiveButton("Share", (d, w) -> {
                        String typed = input.getText().toString().trim();
                        personalBests.putString("rower.name", typed.isEmpty() ? "WAKE rower" : typed);
                        shareRecording(game);
                    })
                    .show();
            return;
        }
        final String base = serverUrl;
        final int meters = game.raceMeters();
        final float time = game.bestTime();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                JSONObject body = new JSONObject();
                body.put("name", name);
                body.put("meters", meters);
                body.put("time", time);
                body.put("samples", samples);
                byte[] bytes = body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                connection = (HttpURLConnection) new URL(base + "/api/ghosts").openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(6000);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = connection.getOutputStream();
                out.write(bytes);
                out.close();
                int code = connection.getResponseCode();
                runOnUiThread(() -> toast(code == 200
                        ? "Shared to the laptop: data/ghosts - copy it to a friend's WAKE laptop"
                        : "Share refused: " + code));
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> toast("Share failed: " + e.getMessage()));
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "share-recording").start();
    }

    /** Lists the recordings on the laptop and races the one picked. */
    private void importRecording(GhostRaceGame game) {
        if (TextUtils.isEmpty(serverUrl)) {
            toast("Importing goes through the laptop dashboard - start it first");
            return;
        }
        final String base = serverUrl;
        new Thread(() -> {
            try {
                JSONObject list = new JSONObject(httpGet(base + "/api/ghosts"));
                JSONArray ghosts = list.optJSONArray("ghosts");
                if (ghosts == null || ghosts.length() == 0) {
                    runOnUiThread(() -> toast("No recordings on the laptop yet - SHARE one, or copy a friend's into data/ghosts"));
                    return;
                }
                String[] labels = new String[ghosts.length()];
                String[] ids = new String[ghosts.length()];
                for (int i = 0; i < ghosts.length(); i++) {
                    JSONObject g = ghosts.getJSONObject(i);
                    ids[i] = g.optString("id");
                    labels[i] = g.optString("name") + "  \u00b7  " + g.optInt("meters") + " m  \u00b7  "
                            + PersonalBests.formatTime((float) g.optDouble("time"));
                }
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Race a recording")
                        .setItems(labels, (d, which) -> new Thread(() -> {
                            try {
                                JSONObject g = new JSONObject(httpGet(base + "/api/ghosts/" + ids[which]));
                                String name = g.optString("name", "Friend");
                                int meters = g.optInt("meters");
                                float time = (float) g.optDouble("time");
                                String samples = g.optString("samples");
                                runOnUiThread(() -> {
                                    game.importFriend(name, meters, time, samples);
                                    toast("Racing " + name + " over " + meters + " m");
                                });
                            } catch (IOException | JSONException e) {
                                runOnUiThread(() -> toast("Import failed: " + e.getMessage()));
                            }
                        }, "import-recording").start())
                        .setNegativeButton("Cancel", (dd, w) -> dd.dismiss())
                        .show());
            } catch (IOException | JSONException e) {
                runOnUiThread(() -> toast("Could not reach the laptop: " + e.getMessage()));
            }
        }, "list-recordings").start();
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(6000);
            java.io.InputStream in = connection.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            in.close();
            return out.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    /* ---------- calibration ---------- */

    /**
     * Applies stored calibration to the pulse meter at launch, so measured energy works from the
     * first stroke. Measured values (drag, pulses per metre, the monitor-matched scale) are seeds the
     * meter refines; the handle travel and load-scale inertia are the rower's own test results.
     */
    /**
     * Blends the session into the stored profile once it holds enough rowing.
     *
     * @param minMinutes commit only with at least this much rowing - the minute tick waits for ten,
     *                   so a long session is folded in as it goes rather than only at the end
     */
    private void commitProfile(double minMinutes) {
        if (personalBests != null && profile.sessionSamples() >= minMinutes * 60 && profile.commitSession()) {
            personalBests.putString("profile", profile.encode());
        }
    }

    private void loadCalibration() {
        PulseMeter meter = s4Protocol.meter();
        meter.setDragSeed(personalBests.get("cal.drag", 0f));
        meter.setPulsesPerMetreSeed(personalBests.get("cal.ppm", 0f));
        meter.setInertiaAutoSeed(personalBests.get("cal.inertiaAuto", 0f));
        meter.setHandleMetresPerPulse(personalBests.get("cal.handle", 0f));
        meter.setInertia(personalBests.get("cal.inertia", 0f));
    }

    /** Keeps what this session measured, once there is enough of it to trust. */
    private void saveMeasuredCalibration() {
        if (personalBests == null) {
            return;
        }
        PulseMeter.Reading r = s4Protocol.meter().reading(System.currentTimeMillis());
        if (r.coastFits >= 10 && r.dragPerInertia > 0) {
            personalBests.putFloat("cal.drag", (float) r.dragPerInertia);
        }
        if (r.pulsesPerMetreMeasured) {
            personalBests.putFloat("cal.ppm", (float) r.pulsesPerMetre);
        }
        double auto = s4Protocol.meter().inertiaAuto();
        if (auto > 0) {
            personalBests.putFloat("cal.inertiaAuto", (float) auto);
        }
    }

    private void openCalibrate() {
        CalibrateGame game = new CalibrateGame(this, (what, value, detail) -> {
            PulseMeter meter = s4Protocol.meter();
            if ("handle".equals(what)) {
                meter.setHandleMetresPerPulse(value);
                personalBests.putFloat("cal.handle", (float) value);
            } else if ("inertia".equals(what)) {
                meter.setInertia(value);
                personalBests.putFloat("cal.inertia", (float) value);
            }
            saveMeasuredCalibration();
            try {
                PulseMeter.Reading r = meter.reading(System.currentTimeMillis());
                JSONObject payload = new JSONObject();
                payload.put("what", what);
                payload.put("value", value);
                payload.put("detail", detail);
                payload.put("dragPerInertia", r.dragPerInertia);
                payload.put("coastFits", r.coastFits);
                payload.put("pulsesPerMetre", r.pulsesPerMetre);
                payload.put("handleMetresPerPulse", r.handleMetresPerPulse);
                payload.put("inertia", r.inertia);
                payload.put("source", r.source.name());
                // Forced: a calibration is rare and is exactly what needs checking on the laptop.
                publishEvent("calibration", payload, true);
            } catch (JSONException e) {
                setUploadStatus("Calibration event failed: " + e.getMessage());
            }
            toast("Calibration saved");
        });
        showGame(game, gameScreen("CALIBRATE", game, null, null, false));
    }

    /** A timed piece on two lane bars. Full-screen instrument, so no vitals strip. */
    private void openZoneRow() {
        ZoneRowGame game = new ZoneRowGame(this, personalBests);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView plan = chip(game.plan().name());
        TextView length = chip(game.lengthLabel());
        plan.setOnClickListener(v -> {
            game.nextPlan();
            plan.setText(game.plan().name());
            length.setText(game.lengthLabel());
        });
        length.setOnClickListener(v -> {
            game.nextPieceLength();
            length.setText(game.lengthLabel());
        });
        row.addView(plan);
        row.addView(length);
        showGame(game, gameScreen("ZONE ROW", game, row, null, false));
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
            if (key.startsWith("ghost.") || key.startsWith("cal.") || key.startsWith("flight.")
                    || key.startsWith("timelast.") || key.startsWith("timefriend.") || key.equals("regatta.day")
                    || key.equals("regatta.division") || key.equals("hr.max")) {
                continue;   // a recording or a calibration constant, not a record
            }
            Object raw = e.getValue();
            if (!(raw instanceof Float)) {
                continue;
            }
            float v = (Float) raw;
            // crew.time.* and the 500 m daily trial are times too (Crew Boat showed "285" on the emulator).
            String shown = key.startsWith("time.") || key.startsWith("run.streak")
                    || key.startsWith("storm.") || key.startsWith("tug.")
                    || key.startsWith("crew.time.") || key.equals("daily.best.trial_500")
                    ? PersonalBests.formatTime(v)
                    : key.startsWith("intervals.") ? Math.round(v) + "%"
                    : key.equals("journey.total") ? String.format(Locale.US, "%.1f km", v / 1000f)
                    : key.equals("dive.joules") ? String.format(Locale.US, "%.1f m deep", v / 1000f)
                    : key.equals("rocket.altitude") || key.equals("rocket.test60") ? String.format(Locale.US, "%.1f km", v / 1000f)
                    : key.equals("city.tallest") ? Math.round(v) + " floors"
                    : key.equals("surf.ride") ? PersonalBests.formatTime(v)
                    : String.valueOf(Math.round(v));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // Local builds float a camera button over the right edge; keep the values clear of it.
            row.setPadding(dp(12), dp(10), BuildConfig.SCREENSHOT_UPLOAD ? dp(64) : dp(12), dp(10));
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
        if (key.startsWith("zonerow.plan.")) return "Zone Row - " + key.substring(13) + " on schedule";
        if (key.equals("zonerow.streak")) return "Zone Row - longest streak";
        if (key.startsWith("zonerow.")) return "Zone Row - most metres in " + key.substring(8) + " min";
        if (key.equals("rocket.test60")) return "Rocket - 60 s power test";
        if (key.equals("shuffle.minutes")) return "Shuffle - longest session (minutes)";
        if (key.equals("river.km")) return "River Explorer - km explored";
        if (key.equals("river.landmarks")) return "River Explorer - landmarks found";
        if (key.equals("river.along")) return "River Explorer - metres up the river";
        if (key.equals("coach.score")) return "Stroke Coach - best session average";
        if (key.equals("coach.bestPower")) return "Stroke Coach - best stroke power (W)";
        if (key.equals("crew.sync")) return "Crew Boat - best crew sync %";
        if (key.startsWith("crew.time.")) return "Crew Boat - " + key.substring(10) + " m fastest";
        if (key.equals("grid.houses")) return "Night Grid - houses in the town";
        if (key.equals("grid.percent")) return "Night Grid - best % lit";
        if (key.equals("grid.joules")) return "Night Grid - lifetime energy (J)";
        if (key.equals("regatta.best")) return "Regatta - highest division (1 club - 6 Olympic)";
        if (key.equals("daily.streak")) return "Daily Row - longest streak (days)";
        if (key.startsWith("daily.best.")) return "Daily Row - best " + key.substring(11).replace('_', ' ');
        if (key.startsWith("timelast.")) return key.substring(9) + " m - last race";
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

    /* ---------- HandleSensor.Listener ---------- */

    @Override
    public void onHandleState(String state) {
        handleState = "Handle: " + state;
        log(handleState);
    }

    /**
     * Turn handle roll into a steering axis.
     *
     * <p>Neutral is wherever the handle sat when the first reading arrived, because the sensor
     * can be strapped on at any angle - re-zero from diagnostics after remounting it. A small
     * dead zone keeps the boat straight when you are just rowing.
     */
    @Override
    public void onHandleAngles(float roll, float pitch, float yaw) {
        handleRoll = roll;
        if (Float.isNaN(handleZeroRoll)) {
            handleZeroRoll = roll;
        }
        float offset = roll - handleZeroRoll;
        if (offset > 180f) {
            offset -= 360f;
        } else if (offset < -180f) {
            offset += 360f;
        }
        if (Math.abs(offset) < STEER_DEADZONE_DEG) {
            offset = 0f;
        }
        steering = Math.max(-1f, Math.min(1f, offset / STEER_FULL_DEG));
        if (currentGame != null) {
            currentGame.setSteeringLive(true);
            currentGame.setSteering(steering);
        }
    }

    @Override
    public void onHandleReport(String stage, String detail) {
        log("Handle " + stage + ": " + detail);
        try {
            JSONObject payload = new JSONObject();
            payload.put("stage", stage);
            payload.put("detail", detail == null ? "" : detail);
            publishEvent("handle-sensor", payload, true);
        } catch (JSONException e) {
            setUploadStatus("Event failed: " + e.getMessage());
        }
    }

    /* ---------- HeartRateSensor.Listener ---------- */

    @Override
    public void onHeartState(String state) {
        heartState = "Heart strap: " + state;
        if (heartView != null) {
            heartView.setText(heartState);
        }
        log(heartState);
    }

    @Override
    public void onHeartRate(int bpm) {
        bleHeartRate = bpm;
        if (currentGame != null) {
            currentGame.setHeartRate(bpm);
        }
    }

    @Override
    public void onHeartReport(String stage, String detail) {
        log("Heart " + stage + ": " + detail);
        try {
            JSONObject payload = new JSONObject();
            payload.put("stage", stage);
            payload.put("detail", detail == null ? "" : detail);
            publishEvent("heart-sensor", payload, true);
        } catch (JSONException e) {
            setUploadStatus("Event failed: " + e.getMessage());
        }
    }

    private void startHeartSensor() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.ACCESS_FINE_LOCATION},
                    PERM_HEART);
            return;
        }
        if (heartSensor == null) {
            heartSensor = new HeartRateSensor(this, this);
        }
        heartSensor.start();
    }

    /** Android 9 returns an empty BLE scan without location permission, and no error with it. */
    private void startHandleSensor() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {android.Manifest.permission.ACCESS_FINE_LOCATION},
                    PERM_SCAN);
            return;
        }
        if (handleSensor == null) {
            handleSensor = new HandleSensor(this, this);
        }
        handleZeroRoll = Float.NaN;
        handleSensor.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == PERM_HEART) {
            if (results.length > 0
                    && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startHeartSensor();
            } else {
                onHeartState("location permission refused, cannot scan");
            }
            return;
        }
        if (requestCode == PERM_SCAN) {
            if (results.length > 0
                    && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startHandleSensor();
            } else {
                handleState = "Handle: location permission refused, cannot scan";
                log(handleState);
            }
            return;
        }
        super.onRequestPermissionsResult(requestCode, permissions, results);
    }

    private String handleLine() {
        if (handleSensor == null) {
            return handleState + "  (tap Find Handle)";
        }
        return String.format(Locale.US, "%s  |  roll %+.1f\u00b0  steer %+.2f",
                handleState, handleRoll, steering);
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
        // Wrapped rather than guarded with an early return: SCREENSHOT_UPLOAD is a
        // compile-time constant, so with it false javac omits this whole block and the
        // published APK does not contain the upload path at all - verifiable with
        // `strings` on the dex, which is the point.
        if (BuildConfig.SCREENSHOT_UPLOAD) {
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
            // Never photograph the camera button itself. An INVISIBLE child is skipped by the draw
            // pass immediately, with no layout, so hiding it just for this call is enough.
            if (shotButton != null) {
                shotButton.setVisibility(View.INVISIBLE);
            }
            root.draw(new Canvas(shot));
            if (shotButton != null) {
                shotButton.setVisibility(View.VISIBLE);
            }
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
    }

    /**
     * A Bluetooth gamepad arrives as ordinary Android input, so there is no Bluetooth code here
     * and none is needed: pair it in Settings and the events simply show up. That is the whole
     * argument for a controller over a handle-mounted IMU - no firmware, no sensor fusion, and
     * an absolute stick position instead of a tilt that has to be told apart from acceleration.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                && event.getAction() == MotionEvent.ACTION_MOVE) {
            padX = axis(event, MotionEvent.AXIS_X);
            padY = axis(event, MotionEvent.AXIS_Y);
            if (padX == 0f && padY == 0f) {
                // Some pads (a sideways Joy-Con among them) report the stick on the hat or the
                // second axis pair instead.
                padX = axis(event, MotionEvent.AXIS_Z);
                padY = axis(event, MotionEvent.AXIS_RZ);
            }
            InputDevice device = event.getDevice();
            controllerName = device != null ? device.getName() : "gamepad";
            padLastMs = System.currentTimeMillis();
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event != null && (event.getSource() & InputDevice.SOURCE_GAMEPAD)
                == InputDevice.SOURCE_GAMEPAD && keyCode != KeyEvent.KEYCODE_BACK) {
            padButton = KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "");
            InputDevice device = event.getDevice();
            controllerName = device != null ? device.getName() : "gamepad";
            padLastMs = System.currentTimeMillis();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** Axis value with the device's own dead zone applied, so a worn stick does not drift. */
    private static float axis(MotionEvent event, int which) {
        InputDevice device = event.getDevice();
        float value = event.getAxisValue(which);
        if (device != null) {
            InputDevice.MotionRange range = device.getMotionRange(which, event.getSource());
            if (range != null && Math.abs(value) <= range.getFlat()) {
                return 0f;
            }
        }
        return Math.abs(value) < 0.08f ? 0f : value;
    }

    /** What the diagnostics drawer shows about the controller, if there is one. */
    private String controllerLine() {
        StringBuilder found = new StringBuilder();
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null) {
                continue;
            }
            int sources = device.getSources();
            boolean pad = (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            if (pad) {
                if (found.length() > 0) {
                    found.append(", ");
                }
                found.append(device.getName());
            }
        }
        if (found.length() == 0) {
            return "Controller: none paired";
        }
        boolean live = System.currentTimeMillis() - padLastMs < 3000;
        return "Controller: " + found
                + (live ? String.format(Locale.US, "  |  X %+.2f  Y %+.2f  %s", padX, padY, padButton)
                        : "  |  paired, no input yet - move a stick");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        log(message);
    }

    /**
     * Report what this tablet could talk to besides USB.
     *
     * <p>Sent once at startup so the question "can this thing take a Bluetooth sensor at all" is
     * answered from the capture rather than by buying hardware first. Reading the adapter needs
     * only the normal BLUETOOTH permission; nothing is scanned, paired or connected.
     */
    private void publishCapabilities() {
        try {
            JSONObject payload = new JSONObject();
            boolean le = getPackageManager().hasSystemFeature(
                    android.content.pm.PackageManager.FEATURE_BLUETOOTH_LE);
            BluetoothAdapter adapter = null;
            boolean enabled = false;
            try {
                adapter = BluetoothAdapter.getDefaultAdapter();
                enabled = adapter != null && adapter.isEnabled();
            } catch (RuntimeException e) {
                // Some locked-down builds refuse the adapter outright; that is itself the answer.
                payload.put("bluetoothError", String.valueOf(e.getMessage()));
            }
            payload.put("bluetoothLe", le);
            payload.put("bluetoothAdapter", adapter != null);
            payload.put("bluetoothEnabled", enabled);
            payload.put("androidSdk", Build.VERSION.SDK_INT);
            payload.put("controllers", controllerLine());
            payload.put("screenWidthPx", getResources().getDisplayMetrics().widthPixels);
            payload.put("screenHeightPx", getResources().getDisplayMetrics().heightPixels);
            payload.put("density", getResources().getDisplayMetrics().density);
            publishEvent("device-capabilities", payload, true);
            log("Bluetooth LE " + (le ? "supported" : "NOT supported")
                    + ", adapter " + (adapter != null ? (enabled ? "on" : "off") : "absent"));
        } catch (JSONException e) {
            setUploadStatus("Capability report failed: " + e.getMessage());
        }
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

    private void openRocketLaunch() {
        RocketLaunchGame game = new RocketLaunchGame(this, personalBests);
        TextView mode = chip("LAUNCH");
        mode.setOnClickListener(v -> {
            game.setTestMode(!game.testMode());
            mode.setText(game.testMode() ? "60 S TEST" : "LAUNCH");
        });
        showGame(game, gameScreen("ROCKET LAUNCH", game, mode));
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
        return gameScreen(title, game, controls, overlay, true);
    }

    /** @param vitals false for a screen that is itself a full instrument panel (ZONE ROW). */
    private View gameScreen(String title, GameView game, View controls, View overlay,
                            boolean vitals) {
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

        if (vitals) {
            // Vitals across the top of every game: stopwatch, speed, power, pace, rate, distance.
            gameStrip = new GaugeStripView(this);
            // Long-press anywhere on the strip to photograph the screen. Deliberately invisible:
            // every game already has the strip, and none of them need another control.
            if (BuildConfig.SCREENSHOT_UPLOAD) {
                gameStrip.setOnLongClickListener(v -> {
                    captureScreenshot(screenLabel());
                    return true;
                });
            }
            // 62dp -> 74dp -> 92dp: this strip is the instrument during a game, and the games
            // themselves lose very little by it.
            LinearLayout.LayoutParams stripParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(92));
            stripParams.topMargin = dp(4);
            root.addView(gameStrip, stripParams);
        } else {
            gameStrip = null;
            // No strip to long-press, so the screenshot gesture moves onto the game itself.
            if (BuildConfig.SCREENSHOT_UPLOAD) {
                game.setOnLongClickListener(v -> {
                    captureScreenshot(screenLabel());
                    return true;
                });
            }
        }

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
                getColorCompat(R.color.primary), 5f, 1).coastDown(BoatSpeedModel.COAST_BASE_S, BoatSpeedModel.COAST_PER_MPS_S).attack(4.0f);
        // Power winds down with the same shape as speed: 3 s plus 0.03 s per watt, ~7 s from 130 W.
        powerGauge = new GaugeView(this, "POWER", "watts",
                getColorCompat(R.color.accent_blue), 250f, 0).coastDown(3f, 0.03f).attack(4.5f);
        rateGauge = new GaugeView(this, "RATE", "str/min",
                getColorCompat(R.color.warn), 45f, 0).coasting(1.8f, 1.2f);
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

        // Stroke shape between the wheel and the trace: the last four drives from the pulse meter.
        strokeShapeView = new StrokeShapeView(this);
        LinearLayout shapeWrap = new LinearLayout(this);
        shapeWrap.setOrientation(LinearLayout.VERTICAL);
        shapeWrap.setPadding(dp(10), dp(8), dp(10), dp(8));
        shapeWrap.setBackgroundColor(getColorCompat(R.color.surface));
        shapeWrap.addView(cardLabel("STROKE SHAPE"));
        shapeWrap.addView(strokeShapeView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams shapeParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f);
        shapeParams.rightMargin = dp(3);
        traceRow.addView(shapeWrap, shapeParams);

        sparkline = new SparklineView(this);
        LinearLayout sparkWrap = new LinearLayout(this);
        sparkWrap.setOrientation(LinearLayout.VERTICAL);
        sparkWrap.setPadding(dp(10), dp(8), dp(10), dp(8));
        sparkWrap.setBackgroundColor(getColorCompat(R.color.surface));
        sparkWrap.addView(cardLabel("BOAT SPEED / POWER - LAST 60 SECONDS"));
        sparkWrap.addView(sparkline, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        traceRow.addView(sparkWrap,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2.0f));

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
        // A gap between the two: on the tablet the power bar's value ran straight into the rate
        // bar's label ("59 WSTROKE RATE").
        LinearLayout.LayoutParams powerParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        powerParams.rightMargin = dp(32);
        bars.addView(powerBar, powerParams);
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

    /** Two rows of four, so nothing is orphaned on its own line. */
    private View buildMetricGrid() {
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);

        LinearLayout top = metricRow();
        elapsedValue = addMetric(top, "TIME", "0:00", true);
        // Deliberately out of step with the other tiles: this is the figure being watched.
        elapsedValue.setTextSize(38);
        distanceValue = addMetric(top, "DISTANCE", "0 m", false);
        paceValue = addMetric(top, "PACE /500", "--:--", true);
        kcalValue = addMetric(top, "KCAL", "0", false);
        grid.addView(top);

        LinearLayout bottom = metricRow();
        strokeRateValue = addMetric(bottom, "STROKES/MIN", "0", false);
        wattsValue = addMetric(bottom, "POWER", "0 W", false);
        strokesValue = addMetric(bottom, "STROKES", "0", false);
        workValue = addMetric(bottom, "WORK", "0 kJ", false);
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

        if (BuildConfig.SCREENSHOT_UPLOAD) {
            Button shotButton = button("Screenshot");
            shotButton.setOnClickListener(v -> {
                setDiagnosticsOpen(false);
                // Let the drawer finish sliding out, or it ends up in the picture.
                screenHost.postDelayed(() -> captureScreenshot(screenLabel()), 450);
            });
            uploadControls.addView(shotButton, weightParams());
        }

        Button snapshotButton = button("Snapshot");
        snapshotButton.setOnClickListener(v -> sendSnapshot("manual", true));
        uploadControls.addView(snapshotButton, weightParams());
        panel.addView(uploadControls, marginTop(dp(4)));

        handleView = new TextView(this);
        handleView.setText(handleState);
        handleView.setTextColor(getColorCompat(R.color.text_faint));
        handleView.setTextSize(11);
        panel.addView(handleView, marginTop(dp(6)));

        LinearLayout handleControls = new LinearLayout(this);
        handleControls.setOrientation(LinearLayout.HORIZONTAL);
        Button findHandle = button("Find Handle");
        findHandle.setOnClickListener(v -> startHandleSensor());
        handleControls.addView(findHandle, weightParams());
        Button zeroHandle = button("Centre Steering");
        zeroHandle.setOnClickListener(v -> {
            handleZeroRoll = handleRoll;
            steering = 0f;
            toast("Steering centred at " + Math.round(handleRoll) + "\u00b0");
        });
        handleControls.addView(zeroHandle, weightParams());
        panel.addView(handleControls, marginTop(dp(4)));

        heartView = new TextView(this);
        heartView.setText(heartState);
        heartView.setTextColor(getColorCompat(R.color.text_faint));
        heartView.setTextSize(11);
        panel.addView(heartView, marginTop(dp(6)));
        LinearLayout heartControls = new LinearLayout(this);
        heartControls.setOrientation(LinearLayout.HORIZONTAL);
        Button findHeart = button("Find Heart Strap");
        findHeart.setOnClickListener(v -> startHeartSensor());
        heartControls.addView(findHeart, weightParams());
        Button maxHeart = button("Max HR " + Math.round(personalBests.get("hr.max", 185f)));
        maxHeart.setOnClickListener(v -> {
            int next = Math.round(personalBests.get("hr.max", 185f)) + 5;
            if (next > 200) {
                next = 170;
            }
            personalBests.putFloat("hr.max", next);
            maxHeart.setText("Max HR " + next);
        });
        heartControls.addView(maxHeart, weightParams());
        panel.addView(heartControls, marginTop(dp(4)));

        controllerView = new TextView(this);
        controllerView.setText("Controller: none paired");
        controllerView.setTextColor(getColorCompat(R.color.text_faint));
        controllerView.setTextSize(11);
        panel.addView(controllerView, marginTop(dp(6)));

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
            controlInterface = findControlInterface(device);
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

    /** The CDC communication (control) interface - class 2 - the one a kernel ACM driver binds. */
    private static UsbInterface findControlInterface(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface candidate = device.getInterface(i);
            if (candidate.getInterfaceClass() == UsbConstants.USB_CLASS_COMM) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Take the rower's interfaces back from whatever grabbed them.
     *
     * <p>Measured on 3.9.3: about 2.7s after WAKE opens the port, another owner takes the
     * interface - a non-forcing claim fails - and both directions die together. The owner is most
     * likely the kernel cdc_acm driver re-binding: a forcing claim can detach a kernel driver but
     * not another app's handle, and forcing claims succeeded 18 of 18 times on 3.9.1.
     *
     * <p>The user chose to have WAKE take the rower back while WAKE is on screen. That is only
     * acceptable because {@link #onStop} gives it straight back the moment WAKE is not, so Ergatta
     * keeps working as the fallback.
     *
     * <p>Control interface first, then data: the order the library claims them in at open, and
     * the kernel driver binds the control interface and claims data through it.
     */
    private boolean reclaimInterfaces(String reason) {
        UsbDeviceConnection connection = openConnection;
        if (connection == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - lastReclaimMs < RECLAIM_MIN_GAP_MS) {
            return false;
        }
        lastReclaimMs = now;
        boolean control;
        boolean data;
        synchronized (ioLock) {
            control = controlInterface != null && connection.claimInterface(controlInterface, true);
            data = dataInterface != null && connection.claimInterface(dataInterface, true);
        }
        reclaimsThisSession++;
        if (reclaimsThisSession <= 3 || now - lastReclaimReportMs > 5000) {
            lastReclaimReportMs = now;
            try {
                JSONObject payload = new JSONObject();
                payload.put("reason", reason);
                payload.put("controlClaimed", control);
                payload.put("dataClaimed", data);
                payload.put("reclaimsThisSession", reclaimsThisSession);
                publishEvent("s4-interface-reclaimed", payload, true);
            } catch (JSONException e) {
                setUploadStatus("Reclaim report failed: " + e.getMessage());
            }
        }
        return data;
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

                    byte[] commandBytes = command.getBytes(StandardCharsets.US_ASCII);
                    IOException writeError = null;
                    boolean written = false;
                    long retryStart = System.currentTimeMillis();
                    for (int attempt = 0; attempt < WRITE_RETRIES && protocolPolling.get(); attempt++) {
                        try {
                            synchronized (ioLock) {
                                port.write(commandBytes, WRITE_TIMEOUT_MS);
                            }
                            written = true;
                            break;
                        } catch (IOException e) {
                            writeError = e;
                            // Report what the USB stack says on the first refusal; if its raw
                            // transfer went through, the command was delivered.
                            if (attempt == 0 && probeWritePath(port, command, e.getMessage())) {
                                written = true;
                                break;
                            }
                            // The probe has already recorded who owned the interface; now take it
                            // back and retry, rather than waiting for a write that cannot land.
                            reclaimInterfaces("write-refused");
                            if (System.currentTimeMillis() - retryStart > WRITE_RETRY_BUDGET_MS) {
                                break;
                            }
                            Thread.sleep(WRITE_RETRY_GAP_MS);
                        }
                    }

                    if (written) {
                        if (consecutiveWriteFailures > 0) {
                            log("USB write path recovered after " + consecutiveWriteFailures + " failures");
                            publishSimpleEvent("s4-write-recovered", false);
                        }
                        consecutiveWriteFailures = 0;
                        writePathStalled = false;
                    } else {
                        s4Protocol.abandonPoll();
                        consecutiveWriteFailures++;
                        String error = writeError == null ? "" : writeError.getMessage();
                        if (consecutiveWriteFailures <= 3
                                || consecutiveWriteFailures % WRITE_FAILURES_BEFORE_COOLDOWN == 0) {
                            publishWriteFailure(lastS4Command, error, consecutiveWriteFailures);
                        }
                        log("USB write refused on " + lastS4Command + " through a full second of "
                                + "retries (attempt " + consecutiveWriteFailures + "): " + error);
                        // No cooldown and no port teardown while the connection is alive. Both
                        // were here before, and they are what turned a sub-second blackout into
                        // the dead-then-spike gauges: a 1.5s pause, then a reopen with a 4s settle
                        // that cannot help - every probe showed the descriptor valid and the
                        // interface claimed - and which tore down the reader, fragmenting the
                        // pulse stream as well. Only a genuinely dead connection is reopened.
                        UsbDeviceConnection live = openConnection;
                        if (live == null || live.getFileDescriptor() < 0) {
                            log("USB connection is gone; reopening the serial port");
                            publishSimpleEvent("s4-port-reopen", true);
                            scheduleReopen();
                            break;
                        }
                        Thread.sleep(Math.min(600L, 100L * consecutiveWriteFailures));
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
        controlInterface = null;
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
        if (handleSensor != null) {
            handleSensor.stop();
        }
        if (heartSensor != null) {
            heartSensor.stop();
        }
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

    /**
     * Ask the USB stack what is actually failing, rather than guessing a sixth time.
     *
     * <p>On 2026-09-13 the write path failed with rc=-1 after 0msec on every open, and five
     * theories were refuted by experiment: the build, relaunch timing, pulse load, the cable and
     * monitor state, and Ergatta holding the device. The USB descriptors were identical to the
     * morning session that worked, and so was every saved preference. This probe replaces theory
     * with four direct questions, asked at the moment a write fails:
     *
     * <ol>
     *   <li>Is the connection's file descriptor still valid?</li>
     *   <li>Does a raw bulkTransfer to the same endpoint work when the driver's write() did not?
     *       If so, the driver's own state is the fault and USB is fine.</li>
     *   <li>Does CLEAR_FEATURE(ENDPOINT_HALT) followed by a retry work? A stalled bulk OUT
     *       endpoint fails instantly and stays stalled until cleared - which fits rc=-1 at 0msec
     *       exactly. Every USB device must accept this request; it is what the Linux kernel
     *       itself does on a stalled pipe, and it changes nothing on the monitor.</li>
     *   <li>Is the data interface still claimable?</li>
     * </ol>
     *
     * <p>If clearing the halt recovers the write, it is repeated on every later failure instead of
     * tearing the port down - so this can be the fix and not just the diagnosis. Rate-limited,
     * because it holds the I/O lock for up to three short timeouts.
     *
     * @return true if the write went through
     */
    private boolean probeWritePath(UsbSerialPort port, String command, String driverError) {
        UsbDeviceConnection connection = openConnection;
        UsbEndpoint endpoint;
        try {
            endpoint = port.getWriteEndpoint();
        } catch (RuntimeException e) {
            endpoint = null;
        }
        if (connection == null || endpoint == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean report = probesThisSession < MAX_PROBES_PER_SESSION
                && now - lastProbeMs > PROBE_EVERY_MS;
        if (!report && !clearHaltRecovers) {
            return false;
        }
        byte[] bytes = command.getBytes(StandardCharsets.US_ASCII);
        int fd = connection.getFileDescriptor();
        int direct;
        int clear = Integer.MIN_VALUE;
        int afterClear = Integer.MIN_VALUE;
        boolean ownershipChecked = false;
        boolean stillOurs = false;
        synchronized (ioLock) {
            direct = connection.bulkTransfer(endpoint, bytes, bytes.length, PROBE_TIMEOUT_MS);
            if (direct < 0) {
                // requestType 0x02: host-to-device, standard, recipient endpoint.
                // request 0x01 CLEAR_FEATURE, value 0 ENDPOINT_HALT, index = endpoint address.
                clear = connection.controlTransfer(0x02, 0x01, 0x00, endpoint.getAddress(),
                        null, 0, PROBE_TIMEOUT_MS);
                afterClear = connection.bulkTransfer(endpoint, bytes, bytes.length, PROBE_TIMEOUT_MS);
            }
            if (report && dataInterface != null) {
                // Non-forcing, and that is the whole point. claimInterface(iface, false) succeeds
                // when this process still owns the interface and fails when another process has
                // taken it - so it answers "is something else holding the rower?" without taking
                // it back. The forcing variant this replaces answered true every time precisely
                // because it steals, which told us nothing. Changes nothing for any other app.
                ownershipChecked = true;
                stillOurs = connection.claimInterface(dataInterface, false);
            }
            // No claimInterface(force=true) here any more. It answered "claimed" all 18 times it
            // ran, so it had nothing left to tell us, and a forced claim can disrupt transfers
            // already in flight on the interface. Every one of the five mid-row pulse dropouts
            // seen on 3.9.1 contained a probe - suggestive rather than proven, since no rowing
            // happened after the probes ran out - so the invasive part goes, and a controlled
            // 15-stroke row on 3.9.2 settles whether it was the cause.
        }
        boolean recovered = direct >= 0 || afterClear >= 0;
        if (direct < 0 && afterClear >= 0) {
            clearHaltRecovers = true;
        }
        if (report) {
            lastProbeMs = now;
            probesThisSession++;
            try {
                JSONObject payload = new JSONObject();
                payload.put("command", command.trim());
                payload.put("driverError", driverError == null ? "" : driverError);
                payload.put("fileDescriptor", fd);
                payload.put("endpointAddress", String.format(Locale.US, "0x%02X", endpoint.getAddress()));
                payload.put("endpointMaxPacket", endpoint.getMaxPacketSize());
                payload.put("rawBulkTransferRc", direct);
                payload.put("clearHaltRc", clear == Integer.MIN_VALUE ? JSONObject.NULL : clear);
                payload.put("afterClearHaltRc", afterClear == Integer.MIN_VALUE ? JSONObject.NULL : afterClear);
                payload.put("interfaceStillOurs", ownershipChecked ? stillOurs : JSONObject.NULL);
                // Both directions died together on 3.9.2 - reads 2.9s after open, writes at 2.68s
                // median - so record how long the reader has been silent at this moment too.
                payload.put("msSinceLastPacket", s4Protocol.snapshot().lastPacketAgeMs);
                payload.put("recovered", recovered);
                payload.put("verdict", ownershipChecked && !stillOurs
                        ? "interface-taken: another process owns the rower's interface"
                        : direct >= 0
                        ? "driver-state: raw transfer works, driver write does not"
                        : afterClear >= 0
                                ? "endpoint-halt: clearing the halt recovers it"
                                : fd < 0 ? "connection-dead: file descriptor invalid"
                                        : "unrecovered: raw transfer and halt-clear both fail");
                publishEvent("s4-write-probe", payload, true);
            } catch (JSONException e) {
                setUploadStatus("Probe report failed: " + e.getMessage());
            }
        }
        return recovered;
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
        payload.put("pulseEffort", Math.round(status.pulseEffort * 100) / 100.0);
        payload.put("pulseStrokes", status.pulseStrokes);
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
            lastStatus = status;
            boolean rowing = status.rowing;
            stateChip.setText(status.monitorConnected ? (rowing ? "ROWING" : "READY") : "WAITING");
            stateChip.setTextColor(getColorCompat(status.monitorConnected
                    ? (rowing ? R.color.primary : R.color.text_primary)
                    : R.color.text_faint));

            // Time and stroke count are exact counters: step them, never interpolate.
            elapsedValue.setText(formatElapsed(status.elapsedSeconds));
            strokesValue.setText(String.valueOf(status.strokes));
            // Measured from the paddle's pulses (see PulseMeter); "~" until the load-scale test.
            boolean measured = status.meter.source == PulseMeter.EnergySource.PULSES_CALIBRATED;
            kcalValue.setText((measured ? "" : "~") + Math.round(status.meter.kcal()));
            workValue.setText(Math.round(status.meter.workJoules / 1000.0) + " kJ");

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
            // The pulse stream arrives at 40Hz unsolicited, so it is the one signal that keeps
            // working when the write path is refused. When memory reads have gone stale but the
            // flywheel is clearly turning, drive the instruments from it rather than showing a
            // frozen needle - a held value is the one thing this project must never display.
            boolean readsStale = status.speedUnchangedMs > 4000;
            boolean pulseAlive = status.flywheelMoving && status.pulseEffort > 0.02;
            boolean onPulses = readsStale && pulseAlive;
            if (onPulses) {
                driving = true;
            }
            trackJourney(status);
            if (gameStrip != null && currentGame != null) {
                gameStrip.update(status, currentGame.boatSpeed());
            }
            if (currentGame != null) {
                currentGame.onStatus(status, driving);
            }
            // Pxx is not calibrated speed (r = +0.24 per sample), so it is scaled onto the
            // dial only to show motion and shape, never presented as a measurement.
            float shownSpeed = onPulses
                    ? (float) (0.8 + status.pulseEffort * 3.6)
                    : (float) status.waterSpeedMps;
            // Pulses stopping means the paddle really has stopped: finish the coast promptly
            // rather than leave a needle hovering above zero.
            speedGauge.setPaddleTurning(status.flywheelMoving);
            powerGauge.setPaddleTurning(status.flywheelMoving);
            paddleView.setPaddleTurning(status.flywheelMoving);
            speedGauge.setValue(shownSpeed, driving);
            powerGauge.setValue(status.watts, driving);
            rateGauge.setValue(status.strokeRateAverage, driving);

            powerBar.setValue(status.watts, status.watts + " W");
            rateBar.setValue(status.strokeRateAverage, status.strokeRateAverage + " spm");

            paddleView.setSpeed(onPulses ? shownSpeed : status.waterSpeedMps, driving);
            strokeShapeView.update(status.meter);
            PulseMeter.Stroke artStroke = status.meter.lastStroke;
            if (artStroke != null && artStroke != lastArtStroke && sessionStrokes.size() < 4000) {
                lastArtStroke = artStroke;
                float power = !Double.isNaN(artStroke.averagePowerW) ? (float) artStroke.averagePowerW : status.watts;
                sessionStrokes.add(new float[]{power, (float) status.strokeRatePrecise});
            }

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
                // One profile sample a second, only while strokes are actually being taken.
                if (tickCount % 30 == 0 && lastStatus != null && lastStatus.stillRowing) {
                    profile.sample(lastStatus.watts, lastStatus.waterSpeedMps, lastStatus.strokeRatePrecise);
                }
                if (tickCount % 30 == 0) {
                    logProgressSecond();
                    shuffleTick();
                }
                if (tickCount % 1800 == 0 && tickCount > 0) {
                    commitJourney();
                    saveMeasuredCalibration();
                    commitProfile(10.0);
                    saveProgress();
                }
                if (gameStrip != null && currentGame != null) {
                    // In SHUFFLE the strip's clock is the whole session, not just this game.
                    double carried = shuffleActive ? shuffleAccumSeconds : 0;
                    gameStrip.setClock(carried + currentGame.activeSeconds(), currentGame.isClockRunning(),
                            currentGame.hasClockStarted() || carried > 0);
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
                if (controllerView != null && diagnosticsOpen) {
                    controllerView.setText(controllerLine());
                    handleView.setText(handleLine());
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
                runOnUiThread(this::publishCapabilities);
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
