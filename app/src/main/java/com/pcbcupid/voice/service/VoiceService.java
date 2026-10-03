package com.pcbcupid.voice.service;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.*;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import com.pcbcupid.voice.R;
import com.pcbcupid.voice.core.VoiceState;
import com.pcbcupid.voice.network.LocalEndpoint;
import com.pcbcupid.voice.ui.MainActivity;
import com.pcbcupid.voice.ui.VoiceController;

/** Started + bound: screen-off/unbinding never ends an explicitly started connection. */
public final class VoiceService extends Service {
    public static final String CONNECT = "com.pcbcupid.voice.CONNECT";
    public static final String STOP = "com.pcbcupid.voice.STOP";
    public static final String SUMMARIZE = "com.pcbcupid.voice.SUMMARIZE";
    public static final String FINISH_RECORDING = "com.pcbcupid.voice.FINISH_RECORDING";
    public static final String ADDRESS = "address";
    public static final String CHANNEL = "glyph_connection";
    private static final int NOTIFICATION_ID = 10;
    private final ViewModelStore store = new ViewModelStore();
    private final IBinder binder = new LocalBinder();
    private VoiceController controller;
    private PowerManager.WakeLock cpu;
    private WifiManager.WifiLock wifi;
    private boolean active;
    private String lastNotification = "";
    private int foregroundTypes;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable renewCpuLease = new Runnable() {
        @Override public void run() {
            if (!active && !controller.summaryBusy) return;
            cpu.acquire(10 * 60 * 1000L);
            main.postDelayed(this, 5 * 60 * 1000L);
        }
    };

    public final class LocalBinder extends Binder {
        public VoiceController controller() { return controller; }
        public void stopSession() { stopConnection(); }
        public String diagnostics() {
            return "CPU wake lock: " + (cpu != null && cpu.isHeld())
                    + "\nWi-Fi lock: " + (wifi != null && wifi.isHeld())
                    + "\nForeground types: " + foregroundTypes;
        }
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Glyph connection", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Local transcription while locked or multitasking, and requested AI summaries");
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        controller = new ViewModelProvider(store,
                new ViewModelProvider.AndroidViewModelFactory(getApplication())).get(VoiceController.class);
        controller.observeService(this::updateNotification);
        controller.setSummaryStarter(this::startSummaryWork);
        cpu = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphVoice:connection");
        cpu.setReferenceCounted(false);
        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (manager != null) {
            // Useful for older Android Wi-Fi clients; newer OS/OEM hotspot policy still applies.
            wifi = manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GlyphVoice:wifi");
            wifi.setReferenceCounted(false);
        }
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        controller.observeService(this::updateNotification);
        if (intent == null || STOP.equals(intent.getAction())) {
            stopConnection();
            return START_NOT_STICKY;
        }
        if (SUMMARIZE.equals(intent.getAction())) {
            startSummaryWork();
            return START_NOT_STICKY;
        }
        if (FINISH_RECORDING.equals(intent.getAction())) {
            controller.stopAndSummarize();
            return START_NOT_STICKY;
        }
        if (!CONNECT.equals(intent.getAction())) { stopSelf(startId); return START_NOT_STICKY; }
        if (active) return START_NOT_STICKY; // Double taps must not cancel an active recording.
        try {
            String address = intent.getStringExtra(ADDRESS);
            LocalEndpoint.url(address == null ? "" : address);
            // Promote promptly, before connecting or waiting for the large local model.
            active = true;
            promote(notification("Preparing speech recognition…"), controller.summaryBusy);
            // Keep CPU available for screen-off BOOT packets. Bounded leases protect
            // against a stalled owner; only an active user-started session renews them.
            main.removeCallbacks(renewCpuLease);
            renewCpuLease.run();
            if (wifi != null) wifi.acquire();
            controller.setBackgroundActive(true, "");
            controller.prepareModel();
            controller.connect(address);
            updateNotification();
        } catch (RuntimeException e) {
            stopConnection();
            controller.setBackgroundActive(false,
                    "Couldn't start background connection. Check notification and battery settings, then reconnect.");
        }
        // No silent restart after force-stop/process death; committed history survives.
        return START_NOT_STICKY;
    }
    private void startSummaryWork() {
        try {
            promote(notification("Preparing your summary…"), true);
            controller.startQueuedSummary();
            main.removeCallbacks(renewCpuLease);
            renewCpuLease.run();
            updateNotification();
        } catch (RuntimeException e) {
            controller.clearQueuedSummary();
            controller.cancelSummary();
            controller.summaryMessage = "Couldn't start summary in background. Open the app and tap Summarize to retry.";
            updateNotification();
        }
    }
    private void promote(Notification notification, boolean summary) {
        int types = 0;
        if (Build.VERSION.SDK_INT >= 29) {
            if (active) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            if (summary) types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types);
        foregroundTypes = types;
    }
    private Notification notification(String status) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VoiceService.class).setAction(STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent finish = PendingIntent.getService(this, 2, new Intent(this, VoiceService.class).setAction(FINISH_RECORDING),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Never include transcript, IP, or speech content on the lock screen.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_voice_notification).setContentTitle("Glyph Voice is active")
                .setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(0, "Disconnect", stop);
        if (controller.state.phase == VoiceState.Phase.RECEIVING)
            builder.addAction(0, "Stop & summarize", finish);
        return builder.build();
    }
    private void updateNotification() {
        if (!active && !controller.summaryBusy) {
            releaseLocks(); foregroundTypes = 0; lastNotification = "";
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            return;
        }
        String status;
        VoiceState state = controller.state;
        if (controller.summaryBusy && !active) status = "Summarizing selected text with your AI provider";
        else if (controller.modelBusy) status = "Preparing speech recognition…";
        else if (!controller.modelReady) status = "Speech setup needs attention. Open the app.";
        else if (state.phase == VoiceState.Phase.RECEIVING) status = controller.speech.cloud() ? "Cloud transcription · click BOOT to stop" : "Transcribing locally · click BOOT to stop";
        else if (state.phase == VoiceState.Phase.PROCESSING) status = controller.speech.cloud() ? "Finalizing with your cloud provider" : "Finalizing your recording locally";
        else switch (state.connection) {
            case CONNECTED: status = "Ready while locked · click BOOT to speak"; break;
            case CONNECTING: status = "Connecting to Glyph…"; break;
            default: status = "Reconnecting to Glyph · saved words are safe";
        }
        if (controller.summaryBusy && active) status += " · summary in progress";
        int expectedTypes = Build.VERSION.SDK_INT < 29 ? 0 :
                (active ? ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE : 0)
                | (controller.summaryBusy ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);
        if (!status.equals(lastNotification) || foregroundTypes != expectedTypes) {
            lastNotification = status;
            promote(notification(status), controller.summaryBusy);
        }
    }
    private void stopConnection() {
        active = false;
        lastNotification = "";
        controller.disconnect();
        controller.cancelSummary();
        controller.setBackgroundActive(false, "");
        updateNotification();
    }
    private void releaseLocks() {
        main.removeCallbacks(renewCpuLease);
        if (wifi != null && wifi.isHeld()) wifi.release();
        if (cpu != null && cpu.isHeld()) cpu.release();
    }
    @Override public void onDestroy() {
        active = false;
        controller.observeService(() -> {});
        releaseLocks();
        store.clear();
        super.onDestroy();
    }
    @Override public void onTimeout(int startId, int fgsType) {
        // Android 15+ dataSync limit: don't keep the service alive past its deadline.
        controller.observeService(() -> {});
        active = false;
        controller.cancelSummary(); controller.disconnect();
        controller.setBackgroundActive(false, "Android stopped background work. Open the app to reconnect.");
        releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
}
