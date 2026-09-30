package com.pcbcupid.voice.network;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.*;
import android.os.*;
import java.util.*;

/** One GATT operation at a time. Credentials never enter preferences, logs or intents. */
@SuppressLint("MissingPermission") // Owning activity gates every entry point with runtime BLE permissions.
public final class BleWifiProvisioner implements AutoCloseable {
    public interface Listener {
        void progress(String message);
        void ready(BleProvisioningProtocol.Status status);
        void failure(String message);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic status, command;
    private final ArrayDeque<byte[]> writes = new ArrayDeque<>();
    private boolean reading, sending, closed, registered;
    private final Runnable timeout = () -> fail("Bluetooth setup timed out. Keep Glyph nearby, confirm the board PIN, and retry.");
    private final Runnable poll = () -> safe(this::readStatus);
    private final BroadcastReceiver bond = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (gatt == null || device == null || !gatt.getDevice().equals(device)) return;
            int next = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE);
            if (next == BluetoothDevice.BOND_BONDED) safe(BleWifiProvisioner.this::readStatus);
            else if (next == BluetoothDevice.BOND_NONE && intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, 0) == BluetoothDevice.BOND_BONDING)
                fail("Pairing was cancelled or the PIN was incorrect. Use the board's private six-digit setup PIN.");
        }
    };
    public BleWifiProvisioner(Context context, Listener listener) { this.context = context; this.listener = listener; }
    public void connect(BluetoothDevice device) {
        if (closed) return;
        release();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(bond, filter, Context.RECEIVER_EXPORTED);
        else context.registerReceiver(bond, filter);
        registered = true;
        listener.progress("Connecting securely… Enter the board PIN if Android asks to pair.");
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
        main.postDelayed(timeout, 60000);
        if (gatt == null) fail("Couldn't open Bluetooth. Turn it on and retry.");
    }
    private boolean current(BluetoothGatt value) { return !closed && value == gatt; }
    private void safe(Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) { fail("Bluetooth became unavailable. Check Nearby devices permission and scan again."); }
    }
    private void postSafe(Runnable action) { main.post(() -> safe(action)); }
    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int result, int state) {
            postSafe(() -> {
                if (!current(connection)) return;
                if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) { fail("Bluetooth disconnected. Wi-Fi credentials were not confirmed; reconnect to check."); return; }
                if (state == BluetoothProfile.STATE_CONNECTED && !connection.discoverServices()) fail("Could not discover Glyph setup service.");
            });
        }
        @Override public void onServicesDiscovered(BluetoothGatt connection, int result) {
            postSafe(() -> {
                if (!current(connection)) return;
                BluetoothGattService service = connection.getService(BleProvisioningProtocol.SERVICE);
                if (result != BluetoothGatt.GATT_SUCCESS || service == null) { fail("Update Glyph firmware to transport-r7 for Bluetooth setup."); return; }
                status = service.getCharacteristic(BleProvisioningProtocol.STATUS); command = service.getCharacteristic(BleProvisioningProtocol.COMMAND);
                if (status == null || command == null) { fail("Glyph setup characteristics are missing."); return; }
                if (connection.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) readStatus();
                else if (connection.getDevice().getBondState() == BluetoothDevice.BOND_NONE && !connection.getDevice().createBond()) fail("Could not start secure pairing.");
            });
        }
        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value, int result) { received(connection, characteristic, value == null ? new byte[0] : value.clone(), result); }
        @Override public void onCharacteristicRead(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int result) { byte[] value = characteristic.getValue(); received(connection, characteristic, value == null ? new byte[0] : value.clone(), result); }
        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int result) {
            postSafe(() -> {
                if (!current(connection) || characteristic != command || !sending) return;
                byte[] sent = writes.poll(); if (sent != null) Arrays.fill(sent, (byte) 0);
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("Secure Wi-Fi transfer failed. Check pairing and retry; no plaintext fallback is used."); return; }
                writeNext();
            });
        }
    };
    private void received(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value, int result) {
        postSafe(() -> {
            if (!current(connection) || characteristic != status || !reading) return;
            reading = false;
            if (result != BluetoothGatt.GATT_SUCCESS) { fail("Could not read encrypted setup status. Pair using the board PIN, or forget an old bond and retry."); return; }
            try {
                BleProvisioningProtocol.Status response = BleProvisioningProtocol.status(value);
                if ("queued".equals(response.state) || "joining".equals(response.state)) {
                    listener.progress("Glyph is joining Wi-Fi… Keep this screen open."); main.postDelayed(poll, 800);
                } else {
                    main.removeCallbacks(timeout);
                    if ("failed".equals(response.state)) listener.failure(response.error);
                    else listener.ready(response);
                }
            } catch (Exception e) { fail("Unrecognized Glyph setup response. Update the app and firmware together."); }
        });
    }
    private void readStatus() {
        if (closed || gatt == null || status == null || reading || sending) return;
        reading = true;
        if (!gatt.readCharacteristic(status)) { reading = false; fail("Could not read Glyph status. Reconnect Bluetooth."); }
    }
    public void provision(String ssid, String password) {
        if (closed || gatt == null || status == null || command == null || sending || reading)
            throw new IllegalStateException("Wait for secure pairing before sending Wi-Fi settings.");
        writes.addAll(BleProvisioningProtocol.credentials(ssid, password));
        sending = true; main.removeCallbacks(poll); main.removeCallbacks(timeout); main.postDelayed(timeout, 45000);
        listener.progress("Sending Wi-Fi settings over encrypted Bluetooth…"); safe(this::writeNext);
    }
    private void writeNext() {
        if (gatt == null || closed) return;
        if (writes.isEmpty()) { sending = false; main.postDelayed(poll, 400); return; }
        byte[] bytes = writes.peek();
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) started = gatt.writeCharacteristic(command, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
        else { command.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT); command.setValue(bytes); started = gatt.writeCharacteristic(command); }
        if (!started) fail("Could not send Wi-Fi settings. Reconnect and retry.");
    }
    private void fail(String message) { release(); if (!closed) listener.failure(message); }
    private void release() {
        main.removeCallbacks(timeout); main.removeCallbacks(poll); reading = sending = false;
        for (byte[] bytes : writes) Arrays.fill(bytes, (byte) 0); writes.clear();
        BluetoothGatt previous = gatt; gatt = null;
        if (command != null) command.setValue(new byte[0]);
        if (previous != null) { try { previous.disconnect(); } catch (SecurityException ignored) {} try { previous.close(); } catch (SecurityException ignored) {} }
        status = command = null;
        if (registered) { context.unregisterReceiver(bond); registered = false; }
    }
    @Override public void close() { closed = true; release(); }
}
