package com.codex.waterrowerdiagnostic;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Collections;
import java.util.UUID;

/**
 * A chest-strap heart-rate monitor, straight to the tablet over Bluetooth LE.
 *
 * <p>The S4 monitor's heart-rate register (1A0) always reads 0 on this machine - no strap pairs with
 * it. The tablet has its own Bluetooth, though, and every mainstream strap (Polar, Garmin, Wahoo,
 * Coospo...) speaks the standard Heart Rate service, so no vendor code is needed.
 *
 * <p>Standard GATT: service 0x180D, measurement characteristic 0x2A37 (notify). Byte 0 is flags;
 * bit 0 set means the rate is a uint16, clear means a uint8. Untested on the tablet at time of
 * writing - there was no strap - so every step reports to the laptop.
 */
class HeartRateSensor {

    interface Listener {
        void onHeartState(String state);

        void onHeartRate(int bpm);

        void onHeartReport(String stage, String detail);
    }

    private static final UUID SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9a34fb");
    private static final UUID MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9a34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9a34fb");
    private static final long SCAN_TIMEOUT_MS = 25000;

    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private boolean wanted;

    HeartRateSensor(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean isConnected() {
        return gatt != null;
    }

    /** Caller must hold location permission; Android 9 returns an empty scan without it. */
    void start() {
        wanted = true;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            state("No Bluetooth adapter on this tablet");
            return;
        }
        if (!adapter.isEnabled()) {
            state("Bluetooth is off - turn it on in Settings");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            state("No BLE scanner available");
            return;
        }
        state("Scanning for a heart-rate strap - wear it so it wakes up");
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                if (device == null) {
                    return;
                }
                listener.onHeartReport("heart-found", String.valueOf(device.getName()));
                stopScan();
                connect(device);
            }

            @Override
            public void onScanFailed(int errorCode) {
                state("Scan failed (" + errorCode + ")");
                listener.onHeartReport("scan-failed", String.valueOf(errorCode));
            }
        };
        ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (SecurityException e) {
            state("Scan refused: " + e.getMessage());
            return;
        }
        ui.postDelayed(() -> {
            if (wanted && gatt == null) {
                stopScan();
                state("No heart-rate strap found - is it on and damp?");
            }
        }, SCAN_TIMEOUT_MS);
    }

    void stop() {
        wanted = false;
        stopScan();
        if (gatt != null) {
            gatt.close();
            gatt = null;
        }
        state("Heart strap off");
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null) {
            try {
                scanner.stopScan(scanCallback);
            } catch (SecurityException | IllegalStateException e) {
                // Adapter turned off mid-scan; nothing to clean up.
            }
            scanCallback = null;
        }
    }

    private void connect(BluetoothDevice device) {
        state("Connecting to " + device.getName());
        gatt = device.connectGatt(context, true, new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
                if (newState == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                    state("Connected - finding the heart-rate service");
                    g.discoverServices();
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    ui.post(() -> listener.onHeartRate(0));
                    state(wanted ? "Heart strap dropped - reconnecting" : "Heart strap off");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                BluetoothGattService service = g.getService(SERVICE);
                BluetoothGattCharacteristic ch = service == null ? null : service.getCharacteristic(MEASUREMENT);
                if (ch == null) {
                    state("That device has no heart-rate measurement");
                    listener.onHeartReport("heart-no-service", String.valueOf(g.getServices().size()));
                    return;
                }
                g.setCharacteristicNotification(ch, true);
                BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
                state("Heart strap live");
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
                int bpm = parse(ch.getValue());
                if (bpm > 0) {
                    ui.post(() -> listener.onHeartRate(bpm));
                }
            }
        });
    }

    /** Heart Rate Measurement: flags byte, then the rate as uint8 or (flag bit 0) uint16 LE. */
    static int parse(byte[] value) {
        if (value == null || value.length < 2) {
            return 0;
        }
        boolean wide = (value[0] & 0x01) != 0;
        if (wide) {
            return value.length >= 3 ? ((value[2] & 0xFF) << 8) | (value[1] & 0xFF) : 0;
        }
        return value[1] & 0xFF;
    }

    private void state(String text) {
        ui.post(() -> listener.onHeartState(text));
    }
}
