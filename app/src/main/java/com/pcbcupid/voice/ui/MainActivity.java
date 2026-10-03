package com.pcbcupid.voice.ui;

import android.Manifest;
import android.app.*;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.pcbcupid.voice.BuildConfig;
import com.pcbcupid.voice.R;
import com.pcbcupid.voice.audio.AudioReceiver;
import com.pcbcupid.voice.core.VoiceState;
import com.pcbcupid.voice.data.ConversationStore.Entry;
import com.pcbcupid.voice.network.LocalEndpoint;
import com.pcbcupid.voice.service.VoiceService;
import com.pcbcupid.voice.ai.*;
import java.text.DateFormat;
import java.util.*;

public final class MainActivity extends ComponentActivity {
    private VoiceController controller;
    private VoiceService.LocalBinder service;
    private boolean bound, started;
    private TextView connection, status, transcript, modelStatus, diagnostics, backgroundStatus;
    private Button connect, retryModel;
    private ProgressBar progress;
    private DrawerLayout drawers;
    private TranscriptScrollView transcriptScroll;
    private ObjectAnimator summaryPulse;
    private final Handler summaryUi = new Handler(Looper.getMainLooper());
    private final Runnable summaryTick = this::renderSummaryStatus;
    private long renderedViewRevision = Long.MIN_VALUE;
    private boolean resetTextScroll;
    private boolean previousLiveRender;
    private HistoryAdapter conversations, summaries;
    private long renderedSequence;
    private String pendingAddress;
    private Entry pendingSummaryConsent;
    private ActivityResultLauncher<String> notificationPermission;
    private ActivityResultLauncher<Intent> batteryPermission;
    private AlertDialog details;
    private AlertDialog prompt;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = (VoiceService.LocalBinder) binder;
            controller = service.controller();
            if (started) {
                controller.observe(MainActivity.this::render);
                controller.historyOpen = anyDrawerVisible();
                if (controller.historyOpen) controller.refreshHistory();
                if (pendingSummaryConsent != null && notificationsAvailable() && batteryExempt()) startSummaryService();
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (controller != null) controller.observe(() -> {});
            controller = null; service = null;
            connect.setEnabled(false);
            status.setText("Connection service stopped. Reopen the app to reconnect; saved words remain in history.");
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);
        if (savedInstanceState != null) pendingAddress = savedInstanceState.getString("pendingAddress");
        ViewCompat.setAccessibilityHeading(findViewById(R.id.voice_title), true);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        connection = findViewById(R.id.connection); status = findViewById(R.id.status);
        transcript = findViewById(R.id.transcript); modelStatus = findViewById(R.id.model_status);
        transcriptScroll = findViewById(R.id.transcript_scroll);
        diagnostics = findViewById(R.id.diagnostics); backgroundStatus = findViewById(R.id.background_status);
        connect = findViewById(R.id.connect); retryModel = findViewById(R.id.retry_model);
        progress = findViewById(R.id.progress);
        connect.setEnabled(false);
        findViewById(R.id.copy).setEnabled(false);
        findViewById(R.id.summarize).setEnabled(false);
        setupDrawers();
        notificationPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                if (pendingSummaryConsent != null) startSummaryService(); else requestBatteryThenConnect();
            } else { pendingSummaryConsent = null; notificationDenied(); }
        });
        batteryPermission = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (batteryExempt()) {
                if (pendingSummaryConsent != null) startSummaryService(); else startPendingConnection();
            }
            else {
                pendingAddress = null;
                pendingSummaryConsent = null;
                showDetails("Background permission not granted", "Android is still optimizing this app. Allow unrestricted battery/background activity in app settings, then connect again. A locked screen and multitasking both need this setup.");
            }
            if (controller != null) render();
        });
        findViewById(R.id.conversations).setOnClickListener(v -> openDrawer(false));
        findViewById(R.id.summaries).setOnClickListener(v -> openDrawer(true));
        findViewById(R.id.copy).setOnClickListener(v -> copyTranscript());
        findViewById(R.id.summarize).setOnClickListener(v -> {
            if (controller != null) requestSummary(controller.summarySource());
        });
        findViewById(R.id.show_current).setOnClickListener(v -> { if (controller != null) controller.showOriginalOrCurrent(); });
        findViewById(R.id.licenses).setOnClickListener(v -> showDetails("About Glyph Voice", getString(R.string.model_attribution)));
        findViewById(R.id.api_settings).setOnClickListener(v -> showApiSettings());
        findViewById(R.id.speech_settings).setOnClickListener(v -> {
            if (controller != null) showPrompt(SpeechSettingsDialog.create(this, controller));
        });
        findViewById(R.id.cancel_summary).setOnClickListener(v -> { if (controller != null) controller.cancelSummary(); });
        findViewById(R.id.cancel_inline_summary).setOnClickListener(v -> { if (controller != null) controller.cancelSummary(); });
        backgroundStatus.setOnClickListener(v -> showBatteryHelp());
        retryModel.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.backgroundActive) controller.retryModelSetup(); else showConnect();
        });
        connect.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.backgroundActive && service != null) service.stopSession(); else showConnect();
        });
        findViewById(R.id.brand).setOnLongClickListener(v -> {
            if (!BuildConfig.DEBUG) return false;
            diagnostics.setVisibility(diagnostics.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (controller != null) render();
            return true;
        });
    }
    private void setupDrawers() {
        drawers = findViewById(R.id.drawers);
        ViewCompat.setAccessibilityHeading(findViewById(R.id.conversations_title), true);
        ViewCompat.setAccessibilityHeading(findViewById(R.id.summaries_title), true);
        drawers.setDrawerTitle(Gravity.LEFT, "Conversations");
        drawers.setDrawerTitle(Gravity.RIGHT, "Summaries");
        int width = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(56));
        for (int id : new int[]{R.id.left_drawer, R.id.right_drawer}) {
            View pane = findViewById(id);
            pane.getLayoutParams().width = width;
            ViewCompat.setAccessibilityPaneTitle(pane, id == R.id.left_drawer ? "Conversations" : "Summaries");
        }
        conversations = new HistoryAdapter(false); summaries = new HistoryAdapter(true);
        ListView rawList = findViewById(R.id.conversation_list), summaryList = findViewById(R.id.summary_list);
        rawList.setAdapter(conversations); summaryList.setAdapter(summaries);
        rawList.setEmptyView(findViewById(R.id.conversations_empty));
        summaryList.setEmptyView(findViewById(R.id.summaries_empty));
        findViewById(R.id.close_left).setOnClickListener(v -> drawers.closeDrawer(Gravity.LEFT));
        findViewById(R.id.close_right).setOnClickListener(v -> drawers.closeDrawer(Gravity.RIGHT));
        OnBackPressedCallback back = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { drawers.closeDrawers(); }
        };
        getOnBackPressedDispatcher().addCallback(this, back);
        drawers.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override public void onDrawerOpened(View drawerView) {
                back.setEnabled(true);
                if (controller != null) { controller.historyOpen = true; controller.refreshHistory(); }
            }
            @Override public void onDrawerClosed(View drawerView) {
                back.setEnabled(anyDrawerVisible());
                if (controller != null) controller.historyOpen = anyDrawerVisible();
            }
        });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private boolean anyDrawerVisible() {
        return drawers.isDrawerVisible(Gravity.LEFT) || drawers.isDrawerVisible(Gravity.RIGHT);
    }
    private void openDrawer(boolean summary) {
        drawers.closeDrawer(summary ? Gravity.LEFT : Gravity.RIGHT);
        drawers.openDrawer(summary ? Gravity.RIGHT : Gravity.LEFT);
    }
    private void showDetails(String title, String text) {
        if (details != null) details.dismiss();
        details = new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Close", null).create();
        details.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        details.show();
    }
    private void showApiSettings() {
        if (controller == null) return;
        if (controller.summaryBusy) { showDetails("Summary in progress", "Wait for the summary or cancel it before changing API settings."); return; }
        controller.apiSetupNeeded = false;
        showPrompt(ApiSettingsDialog.create(this, controller));
    }
    private void requestSummary(Entry source) {
        if (controller == null) return;
        if (controller.recordingBusy()) {
            controller.stopAndSummarize(); return;
        }
        if (source.text.trim().isEmpty()) return;
        if (controller.summaryBusy) return;
        if (!controller.api.hasKey(controller.api.provider())) { showApiSettings(); return; }
        if (source.text.length() > SummaryClient.MAX_TEXT_CHARS) {
            showDetails("Conversation too long", "Summaries currently support up to 120,000 characters. Nothing was sent; the full transcript remains saved."); return;
        }
        // Tapping Summarize is the explicit send action; no extra confirmation.
        // Setup/footer disclose the text upload. Android permission prompts remain.
        pendingSummaryConsent = source;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        else startSummaryService();
    }
    private void startSummaryService() {
        if (controller == null || pendingSummaryConsent == null) return;
        if (!batteryExempt()) { requestBatteryThenConnect(); return; }
        Entry source = pendingSummaryConsent; pendingSummaryConsent = null;
        if (!notificationsAvailable()) { notificationDenied(); return; }
        if (!controller.queueSummary(source)) { showDetails("Please wait", "Finish the current recording or summary first."); return; }
        try {
            ContextCompat.startForegroundService(this, new Intent(this, VoiceService.class).setAction(VoiceService.SUMMARIZE));
            drawers.closeDrawers();
        } catch (RuntimeException e) {
            controller.clearQueuedSummary();
            showDetails("Summary not started", "Android couldn't start background work. Keep the app open and retry.");
        }
    }
    private void showSummary(Entry entry) {
        if (controller == null) return;
        if ("Complete".equals(entry.status)) {
            if (controller.selectSummary(entry)) drawers.closeDrawers();
            else Toast.makeText(this, "Finish recording before opening a summary.", Toast.LENGTH_LONG).show();
            return;
        }
        if (details != null) details.dismiss();
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("Complete".equals(entry.status) ? "Summary · " + entry.provider : "Summary needs attention")
                .setMessage("Complete".equals(entry.status) ? entry.text : entry.status + "\n\nYour source text is kept. No completed summary is available.")
                .setNegativeButton("Close", null)
                .setNeutralButton("Source", (d, w) -> showDetails("Original conversation", entry.source));
        if ("Complete".equals(entry.status)) builder.setPositiveButton("Copy", (d, w) -> copyText(entry.text));
        else if (!"Summarizing".equals(entry.status)) builder.setPositiveButton("Try again", (d, w) ->
                requestSummary(new Entry(entry.sourceId, entry.source, "", entry.created)));
        details = builder.create(); details.show(); details.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void openHistoryEntry(Entry entry, boolean summary) {
        if (controller == null) return;
        if (summary) showSummary(entry);
        else if (controller.selectConversation(entry)) drawers.closeDrawers();
        else Toast.makeText(this, "Stop recording and wait for final words before opening history.", Toast.LENGTH_LONG).show();
    }
    private void deleteHistoryEntry(Entry entry, boolean summary) {
        if (controller == null) return;
        String explanation = summary ? "Delete this summary and its saved source snapshot from this phone? An in-progress request will be cancelled, but text already sent cannot be retracted. The original conversation is kept."
                : "Delete this conversation from this phone? Existing summaries keep their own source copies. If this recording is still running, it will no longer be displayed or saved; start a new recording to keep more words.";
        showPrompt(new AlertDialog.Builder(this).setTitle(summary ? "Delete summary?" : "Delete conversation?")
                .setMessage(explanation + " This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> { if (controller != null) controller.deleteEntry(entry, summary); })
                .setNegativeButton("Cancel", null).create());
    }
    private void showConnect() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(24), dp(8), dp(24), 0);
        TextView help = new TextView(this);
        help.setText("Use the same Wi-Fi as your Glyph, or the phone hotspot it joined. Copy your board's recording IP from USB Serial Monitor (115200 baud). No Bluetooth or scanning.");
        form.addView(help);
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setHint("192.168.0.126:8080");
        address.setContentDescription("Glyph address");
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(getSharedPreferences("glyph", 0).getString("address", ""));
        form.addView(address);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connect your Glyph")
                .setView(form).setPositiveButton("Connect", null).setNegativeButton("Cancel", null)
                .setNeutralButton("Wi-Fi setup help", (d, w) -> showDetails("Set up Glyph Wi-Fi",
                        "Reset with BOOT released, then tap BOOT during the 3-second countdown. In a write-capable USB serial monitor (115200), choose a board hotspot label and password when prompted. Join the printed GLYPH-name-suffix Wi-Fi with that password, open http://192.168.4.1 and enter your router's 2.4 GHz Wi-Fi settings. After restart, return to that network and copy the recording IP from serial. LED blinking = no Wi-Fi IP; steady = Wi-Fi connected. Older firmware may use GLYPH-Setup / glyphvoice. Only one app can connect to a board at a time."))
                .create();
        showPrompt(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = address.getText().toString().trim();
            try {
                LocalEndpoint.url(value);
                getSharedPreferences("glyph", 0).edit().putString("address", value).apply();
                dialog.dismiss();
                beginConnection(value);
            } catch (IllegalArgumentException e) { address.setError(e.getMessage()); }
        });
    }
    private void beginConnection(String address) {
        pendingAddress = address;
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        else if (!notificationsAvailable()) notificationDenied();
        else requestBatteryThenConnect();
    }
    private boolean notificationsAvailable() {
        NotificationChannel channel = getSystemService(NotificationManager.class).getNotificationChannel(VoiceService.CHANNEL);
        return NotificationManagerCompat.from(this).areNotificationsEnabled()
                && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    private void notificationDenied() {
        pendingAddress = null;
        pendingSummaryConsent = null;
        showPrompt(new AlertDialog.Builder(this).setTitle("Enable connection notifications")
                .setMessage("Glyph Voice requires its visible connection notification and stop/disconnect controls before starting a background session. Enable notifications, including the Glyph connection category, then tap Connect again.")
                .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())))
                .setNegativeButton("Not now", null).create());
    }
    private boolean batteryExempt() { return getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName()); }
    private boolean standbyRestricted() {
        PowerManager power = getSystemService(PowerManager.class);
        return Build.VERSION.SDK_INT >= 33 && power.isLowPowerStandbyEnabled()
                && (Build.VERSION.SDK_INT < 34 || !power.isExemptFromLowPowerStandby());
    }
    private void requestBatteryThenConnect() {
        if (batteryExempt()) {
            if (pendingSummaryConsent != null) startSummaryService(); else startPendingConnection();
            return;
        }
        showPrompt(new AlertDialog.Builder(this).setTitle("Allow locked-screen and background use")
                .setMessage("Allow battery optimization exemption so the foreground service can hold its CPU wake lock and receive Glyph audio while locked or multitasking. This uses more battery. Also allow background activity in your phone's app battery settings. Local transcription does not need internet or the phone microphone.")
                .setPositiveButton("Allow", (d, w) -> launchBatteryPermission())
                .setNegativeButton("Cancel", (d, w) -> { pendingAddress = null; pendingSummaryConsent = null; })
                .setOnCancelListener(d -> { pendingAddress = null; pendingSummaryConsent = null; }).create());
    }
    private void showPrompt(AlertDialog dialog) {
        if (prompt != null) prompt.dismiss();
        prompt = dialog;
        dialog.show();
    }
    private void launchBatteryPermission() {
        try {
            batteryPermission.launch(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            pendingAddress = null;
            pendingSummaryConsent = null;
            showDetails("Battery settings", "Open Android Settings → Apps → Glyph Voice → Battery and allow unrestricted/background use, then connect again.");
        }
    }
    private void showBatteryHelp() {
        if (!batteryExempt()) { requestBatteryThenConnect(); return; }
        showPrompt(new AlertDialog.Builder(this).setTitle("Locked-screen and background use")
                .setMessage((standbyRestricted() ? "Low Power Standby is enabled and can block Wi-Fi and CPU wake locks while locked. Disable it in the phone's battery settings.\n\n" : "")
                        + "Android battery exemption is enabled. In App settings → Battery, select Unrestricted / Allow background activity. Disable hotspot auto-off and Battery Saver while recording. Some manufacturers also require Auto-start or keeping this app in Recent apps. Locking the screen should not stop the service; force-stop does. A foreground notification must remain visible.")
                .setPositiveButton("App settings", (d, w) -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("Close", null).create());
    }
    private void startPendingConnection() {
        if (pendingAddress == null) return;
        if (!batteryExempt()) { requestBatteryThenConnect(); return; }
        if (!notificationsAvailable()) { notificationDenied(); return; }
        String address = pendingAddress;
        pendingAddress = null;
        try {
            ContextCompat.startForegroundService(this, new Intent(this, VoiceService.class)
                    .setAction(VoiceService.CONNECT).putExtra(VoiceService.ADDRESS, address));
        } catch (RuntimeException e) {
            showDetails("Connection not started", "Android couldn't start the background session. Keep the app open, check permissions and retry.");
        }
    }
    @Override protected void onStart() {
        super.onStart();
        started = true;
        bound = bindService(new Intent(this, VoiceService.class), serviceConnection, BIND_AUTO_CREATE);
    }
    @Override protected void onResume() {
        super.onResume();
        if (controller != null) render();
    }
    @Override protected void onStop() {
        started = false;
        summaryUi.removeCallbacks(summaryTick);
        stopSummaryAnimation();
        transcript.animate().cancel(); transcript.setAlpha(1f); transcript.setTranslationY(0f);
        // Key-entry dialogs must not keep a stale service/controller or retain a
        // secret edit buffer after leaving the activity.
        if (prompt != null) prompt.dismiss();
        if (details != null) details.dismiss();
        if (controller != null) { controller.observe(() -> {}); controller.historyOpen = false; }
        if (bound) { unbindService(serviceConnection); bound = false; }
        controller = null; service = null;
        // Deliberately no disconnect, pause, model disposal or screen-on flag here.
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (details != null) details.dismiss();
        if (prompt != null) prompt.dismiss();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("pendingAddress", pendingAddress);
        super.onSaveInstanceState(out);
    }
    private void render() {
        if (controller == null) return;
        if (controller.state.recordingSequence != renderedSequence) {
            renderedSequence = controller.state.recordingSequence;
            drawers.closeDrawers();
        }
        resetTextScroll = renderedViewRevision != controller.viewRevision();
        renderedViewRevision = controller.viewRevision();
        renderState(controller.state, controller.displayedText());
        findViewById(R.id.copy).setEnabled(!controller.displayedText().isEmpty());
        boolean capturing = controller.state.phase == VoiceState.Phase.RECEIVING && controller.recordingBusy();
        findViewById(R.id.summarize).setEnabled(capturing ? !controller.stopRequested
                : !controller.displayedText().isEmpty() && !controller.summaryBusy && !controller.recordingBusy());
        ((Button) findViewById(R.id.summarize)).setText(capturing
                ? (controller.stopRequested ? R.string.stopping : R.string.stop_and_summarize) : R.string.summarize);
        findViewById(R.id.show_current).setVisibility(controller.viewingHistory() ? View.VISIBLE : View.GONE);
        ((Button) findViewById(R.id.show_current)).setText(controller.viewingSummary() ? R.string.show_original
                : controller.viewingOriginal() ? R.string.back_to_summary : R.string.show_current);
        if (controller.summarizingInPlace()) ((TextView) findViewById(R.id.transcript_label)).setText(R.string.summarizing_label);
        else if (controller.viewingSummary()) ((TextView) findViewById(R.id.transcript_label)).setText(R.string.summarized_label);
        else if (controller.viewingOriginal()) ((TextView) findViewById(R.id.transcript_label)).setText(R.string.original_label);
        else if (controller.viewingHistory()) ((TextView) findViewById(R.id.transcript_label))
                .setText("SAVED CONVERSATION · " + controller.selectedStatus());
        if (!controller.summaryMessage.isEmpty() && !controller.recordingBusy()) status.setText(controller.summaryMessage);
        if (controller.currentDeleted() && !controller.viewingHistory())
            status.setText("Conversation deleted. This recording is no longer displayed or saved. Click BOOT to stop, then start a new recording when ready.");
        findViewById(R.id.summary_spinner).setVisibility(controller.summarizingInPlace() ? View.VISIBLE : View.GONE);
        findViewById(R.id.cancel_inline_summary).setVisibility(controller.summarizingInPlace() ? View.VISIBLE : View.GONE);
        updateSummaryAnimation(controller.summarizingInPlace());
        if (resetTextScroll) {
            transcript.animate().cancel(); transcript.setAlpha(1f); transcript.setTranslationY(0f);
            if (controller.viewingSummary() && ValueAnimator.areAnimatorsEnabled()) {
                transcript.setAlpha(0.15f); transcript.setTranslationY(dp(8));
                transcript.animate().alpha(1f).translationY(0).setDuration(260).start();
            }
        }
        if (!controller.storageMessage.isEmpty()) status.append("\n" + controller.storageMessage);
        if (!controller.serviceMessage.isEmpty()) status.append("\n" + controller.serviceMessage);
        if (!controller.stopMessage.isEmpty()) status.setText(controller.stopMessage);
        conversations.update(controller.conversations); summaries.update(controller.summaries);
        renderSummaryStatus();
        findViewById(R.id.cancel_summary).setVisibility(controller.summaryBusy ? View.VISIBLE : View.GONE);
        findViewById(R.id.api_settings).setEnabled(!controller.summaryBusy);
        modelStatus.setText(controller.modelMessage);
        retryModel.setVisibility(controller.modelSetupAttempted && !controller.modelReady && !controller.modelBusy ? View.VISIBLE : View.GONE);
        progress.setVisibility(controller.modelBusy || controller.state.phase == VoiceState.Phase.PROCESSING ? View.VISIBLE : View.GONE);
        connect.setEnabled(!controller.modelBusy || controller.backgroundActive);
        connect.setText(controller.backgroundActive ? R.string.disconnect : R.string.connect_glyph);
        findViewById(R.id.speech_settings).setEnabled(!controller.backgroundActive && !controller.modelBusy && !controller.recordingBusy());
        backgroundStatus.setText(controller.backgroundActive
                ? (standbyRestricted() ? "Low Power Standby may block locked-screen use · tap to fix"
                : batteryExempt() ? "Background service active · tap for battery settings" : "Background battery permission missing · tap to fix")
                : "Locked-screen & background setup · tap for details");
        resetTextScroll = false;
        if (started && controller.apiSetupNeeded && !controller.recordingBusy() && !controller.summaryBusy
                && (prompt == null || !prompt.isShowing()) && (details == null || !details.isShowing())) {
            controller.apiSetupNeeded = false;
            // Never launch UI from the background service; setup appears on return.
            VoiceController target = controller;
            transcript.post(() -> {
                if (started && controller == target && !target.recordingBusy() && !target.summaryBusy) showApiSettings();
                else target.apiSetupNeeded = true;
            });
        }
    }
    private void renderSummaryStatus() {
        summaryUi.removeCallbacks(summaryTick);
        if (controller == null || !started) return;
        String message = controller.summaryMessage;
        if (controller.summaryBusy) {
            long seconds = Math.max(0, (SystemClock.elapsedRealtime() - controller.summaryStartedAt) / 1000);
            message += " · " + seconds + "s elapsed";
        }
        TextView inline = findViewById(R.id.inline_summary_status);
        inline.setAccessibilityLiveRegion(controller.summaryBusy ? View.ACCESSIBILITY_LIVE_REGION_NONE : View.ACCESSIBILITY_LIVE_REGION_POLITE);
        inline.setText(message);
        inline.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        ((TextView) findViewById(R.id.summary_status)).setText(message);
        if (controller.summaryBusy) summaryUi.postDelayed(summaryTick, 1000);
    }
    private void updateSummaryAnimation(boolean busy) {
        if (!busy || !started || !ValueAnimator.areAnimatorsEnabled()) { stopSummaryAnimation(); return; }
        if (summaryPulse != null) return;
        summaryPulse = ObjectAnimator.ofFloat(findViewById(R.id.transcript_label), View.ALPHA, 1f, 0.45f);
        summaryPulse.setDuration(650); summaryPulse.setRepeatCount(ValueAnimator.INFINITE);
        summaryPulse.setRepeatMode(ValueAnimator.REVERSE); summaryPulse.start();
    }
    private void stopSummaryAnimation() {
        if (summaryPulse != null) { summaryPulse.cancel(); summaryPulse = null; }
        View label = findViewById(R.id.transcript_label);
        if (label != null) label.setAlpha(1f);
    }
    /** Snapshot renderer also used by instrumentation; safe before service binding completes. */
    public void renderState(VoiceState state) {
        renderState(state, state.text);
    }
    private void renderState(VoiceState state, String visibleText) {
        String label;
        switch (state.connection) {
            case CONNECTED: label = "● Glyph connected"; break;
            case CONNECTING: label = "◌ Connecting…"; break;
            case RECONNECTING: label = "◌ Reconnecting…"; break;
            default: label = "○ Disconnected";
        }
        connection.setText(label);
        status.setText(state.message);
        boolean changed = !transcript.getText().toString().equals(visibleText);
        boolean live = (state.phase == VoiceState.Phase.RECEIVING || state.phase == VoiceState.Phase.PROCESSING)
                && (controller == null || !controller.viewingHistory());
        boolean nearBottom = transcriptScroll.getScrollY() + transcriptScroll.getHeight() >= transcript.getHeight() - dp(40);
        boolean selecting = transcript.getSelectionStart() >= 0 && transcript.getSelectionStart() != transcript.getSelectionEnd();
        boolean followLive = (live || previousLiveRender) && (controller == null || !controller.viewingHistory());
        previousLiveRender = live;
        if (changed) transcript.setText(visibleText);
        if (resetTextScroll || (changed && followLive && nearBottom && !selecting)) {
            boolean bottom = resetTextScroll ? live : followLive;
            transcriptScroll.post(() -> {
                if (!transcript.getText().toString().equals(visibleText)) return;
                // scrollTo changes only this panel, not focus or the outer page.
                transcriptScroll.scrollTo(0, bottom ? Math.max(0, transcript.getHeight() - transcriptScroll.getHeight()) : 0);
            });
        }
        ((TextView) findViewById(R.id.transcript_label)).setText(
                state.phase == VoiceState.Phase.RECEIVING || state.phase == VoiceState.Phase.PROCESSING
                        ? R.string.transcript_live_label : R.string.transcript_label);
        progress.setVisibility(state.phase == VoiceState.Phase.PROCESSING ? View.VISIBLE : View.GONE);
        diagnostics.setText(String.format(Locale.ROOT,
                "Developer diagnostics\nConnection: %s\nState: %s\nModel loaded: %s\nPackets: %d\nBytes: %d\nDuration: %.2f s\nInput: %d Hz, mono PCM16 LE\nSTT: %d ms\nStatus: %s",
                state.connection, state.phase, controller != null && controller.modelReady, state.packets, state.bytes,
                state.duration, state.sampleRate, state.processingMillis, state.message));
        if (controller != null) diagnostics.append(String.format(Locale.ROOT,
                "\nBuffered audio: %.1f s\nBattery exempt: %s\nBattery saver: %s\nDevice locked: %s",
                controller.bufferedSeconds(), batteryExempt(), getSystemService(PowerManager.class).isPowerSaveMode(),
                getSystemService(KeyguardManager.class).isDeviceLocked()));
        if (service != null) diagnostics.append("\n" + service.diagnostics());
        if (controller != null) diagnostics.append(String.format(Locale.ROOT,
                "\nRecognition work/audio ratio: %.2f (>1 means slower than live)\nCPU performance hints: %s\nLow Power Standby restricted: %s\nThermal status: %s",
                controller.recognitionWorkRatio(), controller.performanceHintsActive(), standbyRestricted(),
                Build.VERSION.SDK_INT >= 29 ? getSystemService(PowerManager.class).getCurrentThermalStatus() : "unavailable"));
    }
    private void copyTranscript() {
        if (controller == null || controller.displayedText().isEmpty()) return;
        copyText(controller.displayedText());
    }
    private void copyText(String text) {
        ClipData clip = ClipData.newPlainText("Glyph text", text);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean("android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show();
    }
    private final class HistoryAdapter extends BaseAdapter {
        private final boolean summary;
        HistoryAdapter(boolean summary) { this.summary = summary; }
        private List<Entry> entries = Collections.emptyList();
        void update(List<Entry> values) {
            if (values == entries) return;
            entries = values; notifyDataSetChanged();
        }
        public int getCount() { return entries.size(); }
        public Entry getItem(int position) { return entries.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View recycled, android.view.ViewGroup parent) {
            View row = recycled != null ? recycled : getLayoutInflater().inflate(R.layout.history_row, parent, false);
            Entry entry = getItem(position);
            TextView title = row.findViewById(android.R.id.text1), subtitle = row.findViewById(android.R.id.text2);
            title.setText((entry.text.isEmpty() ? entry.source : entry.text).replace('\n', ' ')); title.setTextSize(16); title.setMaxLines(2);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setTextColor(getColor(R.color.ink));
            subtitle.setText(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(new Date(entry.created)) + " · " + entry.status);
            subtitle.setTextSize(12); subtitle.setTextColor(getColor(R.color.muted));
            View open = row.findViewById(R.id.history_open);
            open.setOnClickListener(v -> openHistoryEntry(entry, summary));
            open.setContentDescription((summary ? "Open summary: " : "Open conversation: ") + title.getText());
            View delete = row.findViewById(R.id.history_delete);
            delete.setContentDescription((summary ? "Delete summary from " : "Delete conversation from ")
                    + DateFormat.getDateTimeInstance().format(new Date(entry.created)));
            delete.setOnClickListener(v -> deleteHistoryEntry(entry, summary));
            return row;
        }
    }
}
