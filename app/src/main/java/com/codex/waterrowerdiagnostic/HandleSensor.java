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
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Locale;
import java.util.UUID;

/**
 * A tilt sensor on the rowing handle, over Bluetooth LE.
 *
 * <p>Steering. Roll the handle like a motorbike grip and the boat turns. The sensor is a WitMotion
 * WT901-class device, chosen because it runs its own Kalman filter and reports <em>angles</em>
 * rather than raw acceleration - which matters more here than anywhere else, because the handle
 * travels about 1.2m per stroke and a bare accelerometer cannot tell gravity from that motion. The
 * gyro carries orientation through the drive; the fusion happens in the sensor, not here.
 *
 * <p><b>The UUIDs below are the documented ones for this module family, but they are unverified
 * against the actual unit.</b> Rather than guess and fail silently, {@link #onServicesDiscovered}
 * logs every service and characteristic it finds and falls back to the first characteristic that
 * supports notifications. The first successful connection tells us what is really there; correct
 * the constants then and delete the fallback if it turns out to be unnecessary.
 *
 * <p>Packet format (WitMotion standard): {@code 0x55 <type> <8 data bytes> <checksum>}, little
 * endian int16s. Type {@code 0x53} is the angle packet, and angle = raw / 32768 * 180 degrees.
 */
class HandleSensor {

    /** Told about connection state and fresh angles, on the UI thread. */
    interface Listener {
        void onHandleState(String state);

        /** Roll, pitch and yaw in degrees. */
        void onHandleAngles(float roll, float pitch, float yaw);

        /** Anything worth seeing from the laptop - discovered services, parse failures. */
        void onHandleReport(String stage, String detail);
    }

    /** Documented for the WT901BLE family. Unverified on hardware; see the class note. */
    private static final UUID SERVICE = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb");
    private static final UUID NOTIFY = UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9a34fb");
    /** Names these modules advertise under. Matched case-insensitively as a prefix. */
    private static final String[] NAME_HINTS = {"WT901", "WT9011", "WitMotion", "HC-08", "JDY"};
    private static final long SCAN_TIMEOUT_MS = 20000;

    private final Context context;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private BluetoothGatt gatt;
    private boolean wanted;
    private final byte[] frame = new byte[11];
    private int framed;

    HandleSensor(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    boolean isConnected() {
        return gatt != null;
    }

    /**
     * Start looking for the handle sensor.
     *
     * <p>Caller must hold location permission: Android 9 refuses a BLE scan without it, silently
     * returning no results rather than an error, which is a memorable afternoon to lose.
     */
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
        state("Scanning for the handle sensor");
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result.getDevice();
                String name = device != null ? device.getName() : null;
                if (name == null || !looksRight(name)) {
                    return;
                }
                listener.onHandleReport("handle-found", name + " " + device.getAddress());
                stopScan();
                connect(device);
            }

            @Override
            public void onScanFailed(int errorCode) {
                state("Scan failed (" + errorCode + ")");
                listener.onHandleReport("scan-failed", String.valueOf(errorCode));
            }
        };
        try {
            scanner.startScan(scanCallback);
        } catch (SecurityException e) {
            state("Scan refused: " + e.getMessage());
            return;
        }
        ui.postDelayed(() -> {
            if (wanted && gatt == null) {
                stopScan();
                state("No handle sensor found - is it switched on?");
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
        state("Handle sensor off");
    }

    private static boolean looksRight(String name) {
        for (String hint : NAME_HINTS) {
            if (name.toUpperCase(Locale.US).startsWith(hint.toUpperCase(Locale.US))) {
                return true;
            }
        }
        return false;
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
                    state("Connected - finding services");
                    g.discoverServices();
                } else if (newState == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                    state(wanted ? "Handle sensor dropped - reconnecting" : "Handle sensor off");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt g, int status) {
                // Log the whole tree: the UUIDs above are documented rather than verified, and
                // this is what turns a silent failure into a one-line fix.
                StringBuilder found = new StringBuilder();
                BluetoothGattCharacteristic chosen = null;
                for (BluetoothGattService service : g.getServices()) {
                    found.append(service.getUuid().toString().substring(4, 8)).append(':');
                    for (BluetoothGattCharacteristic ch : service.getCharacteristics()) {
                        boolean notifies = (ch.getProperties()
                                & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                        found.append(' ').append(ch.getUuid().toString().substring(4, 8))
                                .append(notifies ? "(n)" : "");
                        if (NOTIFY.equals(ch.getUuid())) {
                            chosen = ch;
                        } else if (chosen == null && notifies) {
                            chosen = ch;   // fallback: the first characteristic that can notify
                        }
                    }
                    found.append(" | ");
                }
                listener.onHandleReport("handle-services", found.toString());
                if (chosen == null) {
                    state("Sensor has nothing to subscribe to");
                    return;
                }
                g.setCharacteristicNotification(chosen, true);
                BluetoothGattDescriptor cccd = chosen.getDescriptor(CCCD);
                if (cccd != null) {
                    cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(cccd);
                }
                state("Handle sensor live");
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
                byte[] value = ch.getValue();
                if (value != null) {
                    consume(value);
                }
            }
        });
    }

    /**
     * Reassemble 11-byte WitMotion frames from arbitrary BLE chunks and pull out the angles.
     *
     * <p>Notifications do not respect message boundaries, exactly like the serial link to the
     * rower - and getting that wrong there cost this project a fortnight. Same discipline: buffer
     * until a frame is whole, verify the checksum, then decode.
     */
    private void consume(byte[] chunk) {
        for (byte b : chunk) {
            if (framed == 0) {
                if ((b & 0xFF) != 0x55) {
                    continue;              // not a frame start; keep looking
                }
            }
            frame[framed++] = b;
            if (framed < frame.length) {
                continue;
            }
            framed = 0;
            int sum = 0;
            for (int i = 0; i < frame.length - 1; i++) {
                sum += frame[i] & 0xFF;
            }
            if ((sum & 0xFF) != (frame[frame.length - 1] & 0xFF)) {
                continue;                  // corrupt frame, drop it silently
            }
            if ((frame[1] & 0xFF) != 0x53) {
                continue;                  // not the angle packet
            }
            float roll = angle(frame[2], frame[3]);
            float pitch = angle(frame[4], frame[5]);
            float yaw = angle(frame[6], frame[7]);
            ui.post(() -> listener.onHandleAngles(roll, pitch, yaw));
        }
    }

    /** Little-endian signed 16-bit, scaled to degrees. */
    private static float angle(byte low, byte high) {
        short raw = (short) (((high & 0xFF) << 8) | (low & 0xFF));
        return raw / 32768f * 180f;
    }

    private void state(String text) {
        ui.post(() -> listener.onHandleState(text));
    }
}
