package com.pcbcupid.voice.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import com.pcbcupid.voice.R;
import com.pcbcupid.voice.network.*;
import java.util.*;

/** Foreground-only onboarding. The voice service and recording UI remain untouched. */
@SuppressLint("MissingPermission")
public final class GlyphSetupActivity extends ComponentActivity {
    public static final String ADDRESS = "glyph_address", BOARD_ID = "glyph_board_id";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, ScanResult> devices = new LinkedHashMap<>();
    private final Map<String, Long> seen = new HashMap<>();
    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private BleWifiProvisioner provisioner;
    private GlyphRadarView radar;
    private LinearLayout deviceList, wifiForm;
    private TextView status;
    private EditText ssid, password;
    private Button scan, send;
    private boolean scanning, resumed;
    private BleProvisioningProtocol.Status pendingConnected;
    private ActivityResultLauncher<String[]> permissions;
    private ActivityResultLauncher<Intent> enableBluetooth;
    private final Runnable stopScan = () -> {
        stopScanning();
        status.setText(devices.isEmpty() ? "No Glyph found. Power-cycle it or hold BOOT for five seconds while no audio app is connected, then scan again." : "Tap your Glyph below to connect securely.");
    };
    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (!scanning) return;
            long now = SystemClock.elapsedRealtime();
            int previousCount = devices.size();
            devices.keySet().removeIf(id -> now - seen.getOrDefault(id, 0L) > 10000);
            seen.keySet().retainAll(devices.keySet());
            if (devices.size() != previousCount) renderDevices();
            main.postDelayed(this, 1000);
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        permissions = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
            if (hasPermissions()) scan(); else status.setText("Nearby-device permission is needed for Bluetooth setup. You can still use Wi-Fi setup below.");
        });
        enableBluetooth = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (hasPermissions() && adapter != null && adapter.isEnabled()) scan(); else status.setText("Turn Bluetooth on to find your Glyph.");
        });
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(24), dp(20), dp(24), dp(24));
        root.setBackgroundColor(getColor(R.color.background)); scroll.addView(root); setContentView(scroll);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        Button back = button("‹ Back to voice", root); back.setOnClickListener(v -> finish());
        TextView heading = text("Find your Glyph", 30, root); ViewCompat.setAccessibilityHeading(heading, true);
        text("A nearby connection. A simpler start.", 15, root);
        radar = new GlyphRadarView(this); root.addView(radar, new LinearLayout.LayoutParams(-1, dp(240)));
        status = text("Tap Scan nearby. Only compatible Glyph boards appear—radar positions are decorative, not physical locations.", 14, root);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        scan = button("Scan nearby", root); scan.setOnClickListener(v -> scan());
        deviceList = new LinearLayout(this); deviceList.setOrientation(LinearLayout.VERTICAL); root.addView(deviceList);
        wifiForm = new LinearLayout(this); wifiForm.setOrientation(LinearLayout.VERTICAL); wifiForm.setVisibility(View.GONE); root.addView(wifiForm);
        text("Connect Glyph to Wi-Fi", 22, wifiForm);
        text("Enable your phone hotspot first, or use a shared 2.4 GHz Wi-Fi network. Android cannot read its saved password; enter it once here. It is saved only on the Glyph after a successful connection.", 13, wifiForm);
        ssid = input("Wi-Fi / hotspot name", false, wifiForm); password = input("Wi-Fi password", true, wifiForm);
        send = button("Send securely & connect", wifiForm); send.setOnClickListener(v -> {
            try {
                if (provisioner == null) throw new IllegalStateException("Select your Glyph first.");
                provisioner.provision(ssid.getText().toString(), password.getText().toString());
                password.setText(""); send.setEnabled(false); scan.setEnabled(false);
            } catch (RuntimeException e) { status.setText(e.getMessage()); }
        });
        text("Pairing uses the private six-digit PIN printed by this board on USB during startup (or on your kit label). Never use a shared default PIN. BLE setup closes after five minutes or when audio connects.", 12, root);
        Button fallback = button("iPhone / Wi-Fi setup fallback", root); fallback.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Wi-Fi setup fallback").setMessage("For a new board, join GLYPH-Setup-xxxxxx (password glyphvoice) and open http://192.168.4.1 in your browser. For a configured board, hold BOOT for five seconds while no audio app is connected. Save your 2.4 GHz network settings, rejoin that network, and choose Find via Wi-Fi in the voice app. The fallback uses a shared setup AP password; use it only in a trusted environment.")
                .setPositiveButton("OK", null).show());
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, LinearLayout parent) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(getColor(size >= 20 ? R.color.ink : R.color.muted));
        view.setPadding(0, dp(8), 0, dp(8)); parent.addView(view); return view;
    }
    private Button button(String value, LinearLayout parent) {
        Button view = new Button(this); view.setText(value); view.setAllCaps(false); view.setMinHeight(dp(48)); parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    private EditText input(String hint, boolean secret, LinearLayout parent) {
        text(hint, 12, parent);
        EditText view = new EditText(this); view.setHint(hint); view.setContentDescription(hint); view.setSingleLine(true); view.setSaveEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        view.setInputType(InputType.TYPE_CLASS_TEXT | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS));
        parent.addView(view, new LinearLayout.LayoutParams(-1, dp(56))); return view;
    }
    private String[] requiredPermissions() {
        return Build.VERSION.SDK_INT >= 31 ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }
    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    private void scan() {
        if (adapter == null) { status.setText("Bluetooth LE is unavailable. Use Wi-Fi setup instead."); return; }
        if (!hasPermissions()) { permissions.launch(requiredPermissions()); return; }
        if (!adapter.isEnabled()) { enableBluetooth.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
        if (Build.VERSION.SDK_INT < 31) {
            LocationManager location = getSystemService(LocationManager.class);
            if (location != null && !location.isProviderEnabled(LocationManager.GPS_PROVIDER) && !location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                status.setText("Android 8–11 requires Location services enabled for BLE scanning. Enable Location in system settings, then scan again. No location is collected."); return;
            }
        }
        if (provisioner != null) { provisioner.close(); provisioner = null; }
        pendingConnected = null; stopScanning(); devices.clear(); seen.clear(); wifiForm.setVisibility(View.GONE); password.setText("");
        scanner = adapter.getBluetoothLeScanner(); if (scanner == null) { status.setText("Bluetooth is not ready. Try again."); return; }
        try {
            scanning = true; radar.scanning(true); renderDevices();
            callback = newCallback();
            scanner.startScan(Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(BleProvisioningProtocol.SERVICE)).build()),
                    new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback);
            status.setText("Searching nearby… Keep your Glyph powered on and close to this phone."); scan.setText("Scan again");
            main.postDelayed(stopScan, 20000); main.postDelayed(expire, 1000);
        } catch (RuntimeException e) { stopScanning(); status.setText("Bluetooth scan could not start. Check Nearby devices permission and Bluetooth, then retry."); }
    }
    private ScanCallback newCallback() { return new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) { main.post(() -> { if (callback == this) found(result); }); }
        @Override public void onBatchScanResults(List<ScanResult> results) { main.post(() -> { if (callback == this) for (ScanResult result : results) found(result); }); }
        @Override public void onScanFailed(int code) { main.post(() -> { if (callback == this) { stopScanning(); status.setText("Bluetooth scan failed (" + code + "). Wait briefly and try again."); } }); }
    }; }
    private void found(ScanResult result) {
        if (!scanning || !hasPermissions()) return;
        String id = result.getDevice().getAddress();
        if (devices.size() >= 16 && !devices.containsKey(id)) return;
        boolean added = !devices.containsKey(id);
        devices.put(id, result); seen.put(id, SystemClock.elapsedRealtime()); if (added) renderDevices();
    }
    private void renderDevices() {
        radar.devices(devices.keySet()); deviceList.removeAllViews();
        for (ScanResult result : devices.values()) {
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (name == null || name.isEmpty()) name = "Glyph setup device";
            Button row = button(name + "  ·  " + result.getRssi() + " dBm  →", deviceList);
            row.setContentDescription("Connect to " + name + ", signal " + result.getRssi() + " dBm");
            row.setOnClickListener(v -> select(result.getDevice()));
        }
    }
    private void select(BluetoothDevice device) {
        stopScanning(); deviceList.removeAllViews(); password.setText(""); scan.setEnabled(false);
        if (provisioner != null) provisioner.close();
        provisioner = new BleWifiProvisioner(this, new BleWifiProvisioner.Listener() {
            @Override public void progress(String message) { status.setText(message); }
            @Override public void failure(String message) { status.setText(message + " Scan again if Bluetooth disconnected."); wifiForm.setVisibility(View.VISIBLE); scan.setEnabled(true); send.setEnabled(true); }
            @Override public void ready(BleProvisioningProtocol.Status value) {
                if ("connected".equals(value.state)) { pendingConnected = value; completeIfVisible(); }
                else { status.setText("Securely paired. Enter your Wi-Fi details below."); wifiForm.setVisibility(View.VISIBLE); send.setEnabled(true); scan.setEnabled(true); }
            }
        });
        try { provisioner.connect(device); } catch (RuntimeException e) { provisioner.close(); status.setText("Bluetooth permission or pairing failed. Scan again to retry."); scan.setEnabled(true); }
    }
    private void completeIfVisible() {
        if (!resumed || pendingConnected == null) return;
        setResult(RESULT_OK, new Intent().putExtra(ADDRESS, pendingConnected.address).putExtra(BOARD_ID, pendingConnected.id));
        password.setText(""); finish();
    }
    private void stopScanning() {
        scanning = false; main.removeCallbacks(stopScan); main.removeCallbacks(expire); if (radar != null) radar.scanning(false);
        if (scanner != null) { try { scanner.stopScan(callback); } catch (RuntimeException ignored) {} scanner = null; }
        callback = null;
    }
    @Override protected void onResume() { super.onResume(); resumed = true; completeIfVisible(); }
    @Override protected void onStop() { resumed = false; stopScanning(); password.setText(""); super.onStop(); }
    @Override protected void onDestroy() { stopScanning(); if (provisioner != null) provisioner.close(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
}
