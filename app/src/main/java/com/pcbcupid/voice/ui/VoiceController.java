package com.pcbcupid.voice.ui;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import com.pcbcupid.voice.BuildConfig;
import com.pcbcupid.voice.audio.*;
import com.pcbcupid.voice.core.*;
import com.pcbcupid.voice.network.*;
import com.pcbcupid.voice.speech.*;
import com.pcbcupid.voice.data.ConversationStore;
import com.pcbcupid.voice.data.ConversationStore.Entry;
import com.pcbcupid.voice.ai.*;
import java.util.function.Consumer;
import java.io.*;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

/** Service-owned state; the activity is only an observer, never the session owner. */
public final class VoiceController extends AndroidViewModel {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> new Thread(() -> {
        // Keep the inference owner (and native threads created during load) out
        // of background-priority scheduling when the activity is not visible.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE);
        task.run();
    }, "Glyph-inference"));
    private final ExecutorService historyWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService summaryWorker = Executors.newSingleThreadExecutor();
    public final ApiCredentials api;
    private final SummaryClient summaryClient = new SummaryClient();
    private Entry pendingSummary;
    private long pendingSummaryViewRevision;
    private final AutoSummaryQueue automaticSummaries = new AutoSummaryQueue();
    private Runnable summaryStarter = () -> {};
    public boolean apiSetupNeeded, stopRequested;
    public String stopMessage = "";
    private final Set<String> deletedRawIds = new HashSet<>();
    private final Runnable stopTimeout = () -> {
        if (!this.closed && stopRequested) {
            stopRequested = false;
            stopMessage = "Glyph hasn't confirmed stop. Tap Stop & summarize to retry; don't start another recording yet.";
            publish();
        }
    };
    public boolean summaryBusy;
    public String summaryMessage = "";
    private final ConversationStore history;
    private final SpeechEngine recognizer;
    public final SpeechSettings speech;
    private GlyphDiscovery discovery;
    private final java.util.Map<String, GlyphAnnouncement> boards = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Long> boardSeen = new java.util.HashMap<>();
    public List<GlyphAnnouncement> discoveredBoards = Collections.emptyList();
    private String selectedBoard = "";
    private String activeAddress = "";
    private long discoveryStarted;
    private String discoveryError = "";
    private final Runnable discoveryTick = new Runnable() {
        @Override public void run() {
            if (closed || !wanted || discovery == null) return;
            if (!discoveryError.isEmpty()) { serviceMessage = discoveryError; publish(); return; }
            long now = android.os.SystemClock.elapsedRealtime();
            boards.keySet().removeIf(id -> now - boardSeen.getOrDefault(id, 0L) > 8000);
            boardSeen.keySet().retainAll(boards.keySet());
            discoveredBoards = new java.util.ArrayList<>(boards.values());
            if (selectedBoard.isEmpty() && now - discoveryStarted >= 3000 && boards.size() == 1)
                selectBoard(discoveredBoards.get(0));
            if (selectedBoard.isEmpty()) serviceMessage = boards.size() > 1
                    ? "Several Glyph boards found. Tap Choose Glyph."
                    : "Finding Glyph… Enable your 2.4 GHz hotspot and power on the configured board.";
            else if (!boards.containsKey(selectedBoard)) serviceMessage = boards.isEmpty()
                    ? "Finding your saved Glyph… Check its power and hotspot settings."
                    : "Your saved Glyph is unavailable. Tap Choose Glyph to select another board.";
            publish();
            main.postDelayed(this, 1000);
        }
    };
    private final ModelManager models;
    private final StreamingTranscriptionSession session;
    private final AudioReceiver receiver;
    private final SharedPreferences preferences;
    private Runnable observer = () -> {};
    private Runnable serviceObserver = () -> {};
    private boolean wanted;
    private volatile boolean closed;
    public boolean modelReady, modelBusy, modelSetupAttempted;
    public String modelMessage = "Bundled Parakeet · prepares when you connect";
    public VoiceState state;
    public List<Entry> conversations = Collections.emptyList(), summaries = Collections.emptyList();
    public String storageMessage = "";
    public String serviceMessage = "";
    public boolean backgroundActive;
    public boolean historyOpen;
    private Entry selected;
    private Entry summaryForOriginal;
    private boolean selectedSummary;
    private long viewRevision, summaryViewRevision = -1, hiddenRecordingSequence = -1;
    private volatile String activeSummaryId = "";
    private final Set<String> deletedSummaryIds = new HashSet<>(); // Main-thread view guard.
    private volatile VoiceState lastCheckpoint;
    private final LatestStateDelivery<VoiceState> stateDelivery = new LatestStateDelivery<>(
            task -> main.post(task), this::applyLatestState);

    public VoiceController(Application app) {
        super(app);
        recognizer = new SpeechEngine(app);
        speech = new SpeechSettings(app);
        if (speech.cloud()) modelMessage = "Cloud speech configured · connects when you do";
        api = new ApiCredentials(app);
        history = new ConversationStore(app);
        historyWorker.execute(() -> {
            try { history.recoverInterrupted(); }
            catch (RuntimeException e) { storageFailed(); }
        });
        models = new LocalModelManager(new File(app.getNoBackupFilesDir(), "speech"));
        preferences = app.getSharedPreferences("glyph", 0);
        receiver = new WebSocketAudioReceiver(new WifiClientFactory(app));
        session = new StreamingTranscriptionSession(recognizer, worker);
        state = session.snapshot();
        session.observe(value -> {
            stateDelivery.submit(value);
            // Queue the checkpoint before publishing a cleared/new recording to the UI.
            // This executor is independent of model inference and drains on close.
            boolean checkpoint = lastCheckpoint == null || value.recordingSequence != lastCheckpoint.recordingSequence
                    || !value.text.equals(lastCheckpoint.text) || value.complete != lastCheckpoint.complete
                    || value.interrupted != lastCheckpoint.interrupted;
            lastCheckpoint = value;
            if (checkpoint) historyWorker.execute(() -> {
                try {
                    if (history.save(value)) main.post(() -> {
                        if (closed) return;
                        storageMessage = "";
                        if (historyOpen) refreshHistory();
                        publish();
                    });
                } catch (RuntimeException e) { lastCheckpoint = null; storageFailed(); }
            });
            AutoSummaryQueue.Offer offer = automaticSummaries.offer(value);
            if (offer != AutoSummaryQueue.Offer.IGNORED) main.post(() -> {
                if (closed) return;
                if (offer == AutoSummaryQueue.Offer.FULL) {
                    summaryMessage = "Summary queue full. Words saved; summarize this conversation manually later.";
                    publish();
                } else pumpAutomaticSummaries();
            });
        });
    }
    private void applyLatestState(VoiceState value) {
        if (closed) return;
        if (state.recordingSequence != value.recordingSequence) {
            selected = null; selectedSummary = false; summaryForOriginal = null; viewRevision++;
            summaryMessage = "";
            stopMessage = "";
            stopRequested = false;
            main.removeCallbacks(stopTimeout);
        }
        state = value;
        if (value.phase != VoiceState.Phase.RECEIVING) {
            stopRequested = false;
            main.removeCallbacks(stopTimeout);
            stopMessage = "";
        }
        publish();
        if (!automaticSummaries.isEmpty() && !session.isBusy()) main.post(this::pumpAutomaticSummaries);
    }
    public void setSummaryStarter(Runnable starter) { summaryStarter = starter; }
    private void pumpAutomaticSummaries() {
        if (closed || !backgroundActive || summaryBusy || pendingSummary != null || session.isBusy()) return;
        stateDelivery.deliverLatest();
        VoiceState completed;
        while ((completed = automaticSummaries.poll()) != null) {
            String id = history.idFor(completed.recordingSequence);
            if (deletedRawIds.contains(id)) continue;
            if (!api.hasKey(api.provider())) {
                apiSetupNeeded = true;
                summaryMessage = "Recording saved. Connect your API to summarize; no text was sent.";
                automaticSummaries.clear(); publish(); return;
            }
            if (completed.text.length() > SummaryClient.MAX_TEXT_CHARS) {
                summaryMessage = "Recording saved, but exceeds the 120,000-character summary limit. Nothing sent.";
                publish(); continue;
            }
            pendingSummary = new Entry(id, completed.text, "Complete", System.currentTimeMillis());
            pendingSummaryViewRevision = state.recordingSequence == completed.recordingSequence && selected == null
                    ? viewRevision : -1;
            summaryStarter.run();
            return;
        }
    }
    /** Start remains hardware-only. End acknowledgment, not this tap, finalizes STT. */
    public void stopAndSummarize() {
        stateDelivery.deliverLatest();
        if (state.phase != VoiceState.Phase.RECEIVING || !session.isBusy() || stopRequested) return;
        AudioReceiver.StopResult result = receiver.requestStop();
        if (result == AudioReceiver.StopResult.SENT) {
            stopRequested = true;
            stopMessage = "Stopping Glyph… Final words will be summarized automatically.";
            main.removeCallbacks(stopTimeout);
            main.postDelayed(stopTimeout, 5000);
        } else if (result == AudioReceiver.StopResult.UNSUPPORTED) {
            stopMessage = "Update Glyph firmware for phone-stop support. Click BOOT to stop this recording and summarize.";
        } else if (result == AudioReceiver.StopResult.NOT_RECORDING) {
            stopMessage = "Glyph has stopped. Waiting for final words…";
        } else stopMessage = "Couldn't send stop to Glyph. Click BOOT to stop; words are kept.";
        publish();
    }
    public void prepareModel() { if (!modelBusy && !modelReady) loadModel(false); }
    public boolean needsBoardChoice() { return wanted && (selectedBoard.isEmpty() ? discoveredBoards.size() > 1
            : !boards.containsKey(selectedBoard) && !discoveredBoards.isEmpty()); }
    public void selectBoard(GlyphAnnouncement board) {
        if (!wanted || !boards.containsKey(board.id)) return;
        selectedBoard = board.id;
        preferences.edit().putString("board_id", board.id).apply();
        useBoard(board);
    }
    private void useBoard(GlyphAnnouncement board) {
        if (!wanted || !selectedBoard.equals(board.id)) return;
        if (!board.address.equals(activeAddress) && !session.isBusy()) {
            receiver.disconnect();
            activeAddress = board.address;
            preferences.edit().putString("address", activeAddress).apply();
        }
        serviceMessage = "Detected " + board + (speech.cloud() ? " · Cloud speech enabled" : "");
        maybeConnect();
        publish();
    }
    public void saveSpeech(boolean cloud, String endpoint, String model, String key, boolean clear, Consumer<String> done) {
        if (backgroundActive || session.isBusy() || modelBusy) { done.accept("Disconnect Glyph before changing speech settings."); return; }
        modelBusy = true;
        publish();
        worker.execute(() -> {
            String failure = "";
            try { speech.save(cloud, endpoint, model, key, clear); recognizer.close(); }
            catch (IllegalArgumentException e) { failure = e.getMessage(); }
            catch (Exception e) { failure = "Could not save speech settings. Re-enter the key and try again."; }
            final String message = failure;
            main.post(() -> {
                if (closed) return;
                modelBusy = false;
                if (message.isEmpty()) {
                    modelReady = false; modelSetupAttempted = false; session.setReady(false);
                    modelMessage = speech.cloud() ? "Cloud speech configured · connects when you do" : "Bundled Parakeet · prepares when you connect";
                }
                publish(); done.accept(message);
            });
        });
    }
    private void storageFailed() {
        main.post(() -> {
            if (closed) return;
            storageMessage = "Couldn't save history. Free phone storage; copy the visible words before leaving.";
            publish();
        });
    }
    public void refreshHistory() {
        historyWorker.execute(() -> {
            try {
                List<Entry> raw = history.list(false), pending = history.list(true);
                main.post(() -> {
                    if (closed) return;
                    conversations = raw; summaries = pending;
                    publish();
                });
            } catch (RuntimeException e) { storageFailed(); }
        });
    }
    public boolean selectConversation(Entry entry) {
        if (session.isBusy()) return false;
        selected = entry;
        summaryForOriginal = null;
        selectedSummary = false; viewRevision++;
        publish();
        return true;
    }
    public boolean selectSummary(Entry entry) {
        if (session.isBusy() || !"Complete".equals(entry.status) || deletedSummaryIds.contains(entry.id)) return false;
        selected = entry; selectedSummary = true; summaryForOriginal = null; viewRevision++;
        publish(); return true;
    }
    public void showCurrent() { selected = null; selectedSummary = false; summaryForOriginal = null; viewRevision++; publish(); }
    public void showOriginalOrCurrent() {
        if (selectedSummary && selected != null) {
            summaryForOriginal = selected;
            selected = new Entry(selected.sourceId, selected.source, "Original", selected.created);
            selectedSummary = false; viewRevision++; publish();
        } else if (summaryForOriginal != null && !deletedSummaryIds.contains(summaryForOriginal.id)) {
            Entry summary = summaryForOriginal;
            selectSummary(summary);
        } else showCurrent();
    }
    public String displayedText() {
        return selected == null ? (currentDeleted() ? "" : state.text) : selected.text;
    }
    public boolean currentDeleted() { return state.recordingSequence == hiddenRecordingSequence; }
    public boolean viewingSummary() { return selected != null && selectedSummary; }
    public boolean viewingOriginal() { return selected != null && !selectedSummary && summaryForOriginal != null; }
    public boolean summarizingInPlace() { return summaryBusy && summaryViewRevision == viewRevision; }
    public long viewRevision() { return viewRevision; }
    public boolean viewingHistory() { return selected != null; }
    public String selectedStatus() { return selected == null ? "" : selected.status; }
    public boolean recordingBusy() { return session.isBusy(); }
    public double bufferedSeconds() { return session.bufferedSeconds(); }
    public double recognitionWorkRatio() { return session.recognitionWorkRatio(); }
    public boolean performanceHintsActive() { return recognizer.performanceHintsActive(); }
    public Entry summarySource() {
        if (viewingSummary()) return new Entry(selected.sourceId, selected.source, "", selected.created);
        String text = displayedText();
        return selected != null ? selected : new Entry(history.idFor(state.recordingSequence),
                text, "", System.currentTimeMillis());
    }
    public void saveApi(AiProvider provider, String key, String model, boolean remove, Consumer<String> result) {
        if (closed) { result.accept("App session changed. Reopen Connect your API."); return; }
        if (summaryBusy) { result.accept("Wait for the current summary or cancel it first."); return; }
        summaryWorker.execute(() -> {
            String error = "";
            try { if (remove) api.remove(provider); else api.save(provider, key, model); }
            catch (IllegalArgumentException e) { error = e.getMessage(); }
            catch (Exception e) { error = "Couldn't update the encrypted key. Check device storage and try again."; }
            final String message = error;
            main.post(() -> { if (!closed) result.accept(message); });
        });
    }
    public boolean queueSummary(Entry source) {
        if (summaryBusy || pendingSummary != null || source.text.trim().isEmpty() || session.isBusy()) return false;
        // An explicit NEW request may intentionally recreate a deleted summary.
        // No prior job can still publish: summaryBusy is cleared on its final UI callback.
        deletedSummaryIds.clear();
        pendingSummaryViewRevision = viewRevision;
        pendingSummary = source; return true;
    }
    public void clearQueuedSummary() { pendingSummary = null; }
    /** Called only AFTER the running VoiceService promotes the summary work. */
    public void startQueuedSummary() {
        if (summaryBusy || pendingSummary == null) return;
        Entry source = pendingSummary; pendingSummary = null;
        summaryBusy = true;
        summaryViewRevision = pendingSummaryViewRevision;
        final long targetView = pendingSummaryViewRevision;
        summaryClient.prepare();
        AiProvider provider = api.provider();
        String model = api.model(provider);
        summaryMessage = "Summarizing with " + provider.label + "…";
        publish();
        summaryWorker.execute(() -> {
            Entry pending = null;
            Entry completed = null;
            String message;
            try {
                if (source.text.length() > SummaryClient.MAX_TEXT_CHARS)
                    throw new IOException("This conversation exceeds the 120,000-character summary limit. Nothing was sent.");
                String key;
                try { key = api.key(provider); }
                catch (Exception e) { throw new IOException("API key unavailable. Open Connect your API and re-enter it."); }
                pending = historyWorker.submit(() -> history.beginSummary(source, provider.label, model)).get();
                activeSummaryId = pending.id;
                main.post(() -> { if (!closed) refreshHistory(); });
                if (!"Complete".equals(pending.status)) {
                    String summary = summaryClient.summarize(provider, key, model, source.text);
                    Entry row = pending;
                    boolean kept = historyWorker.submit(() -> history.finishSummary(row, summary, "Complete")).get();
                    if (kept) completed = new Entry(row.id, summary, "Complete", row.created, row.sourceId, row.source, row.provider, row.model);
                } else {
                    completed = pending;
                }
                message = completed == null ? "Summary was deleted; result discarded." : "English summary saved on this phone · " + provider.label;
            } catch (Exception e) {
                message = e instanceof IOException ? e.getMessage() : "Couldn't save or finish summary. Check storage and retry.";
                if (pending != null && !closed) {
                    Entry row = pending;
                    String failure = message;
                    try { historyWorker.submit(() -> history.finishSummary(row, "", "Failed · " + failure)).get(); }
                    catch (Exception ignored) { storageFailed(); }
                }
            }
            final String status = message;
            final Entry result = completed;
            main.post(() -> {
                if (closed) return;
                summaryBusy = false; summaryMessage = status;
                activeSummaryId = "";
                boolean deleted = result != null && deletedSummaryIds.contains(result.id);
                if (deleted) summaryMessage = "Summary deleted from this phone.";
                // A reply for an older recording must never replace a new live
                // recording, a different history selection, or a deleted view.
                if (result != null && !deleted && viewRevision == targetView && !session.isBusy()) {
                    selected = result; selectedSummary = true; summaryForOriginal = null; viewRevision++;
                }
                refreshHistory(); publish(); pumpAutomaticSummaries();
            });
        });
    }
    public void cancelSummary() { pendingSummary = null; automaticSummaries.clear(); summaryClient.cancel(); }
    public void deleteEntry(Entry entry, boolean summary) {
        if (summary) deletedSummaryIds.add(entry.id);
        else deletedRawIds.add(entry.id);
        // Invalidate the visible request immediately, before the storage worker:
        // its completion callback may already be waiting on the main thread.
        boolean visible = selected != null && selectedSummary == summary && selected.id.equals(entry.id);
        boolean current = !summary && entry.id.equals(history.idFor(state.recordingSequence));
        boolean running = summary && entry.id.equals(activeSummaryId);
        if (visible || current || running) viewRevision++;
        if (running) summaryClient.cancel();
        historyWorker.execute(() -> {
            try {
                history.delete(entry, summary);
                main.post(() -> {
                    if (closed) return;
                    if (selected != null && selectedSummary == summary && selected.id.equals(entry.id)) {
                        selected = null; selectedSummary = false; summaryForOriginal = null;
                    }
                    if (summary && summaryForOriginal != null && summaryForOriginal.id.equals(entry.id)) summaryForOriginal = null;
                    if (!summary && entry.id.equals(history.idFor(state.recordingSequence))) hiddenRecordingSequence = state.recordingSequence;
                    storageMessage = summary ? "Summary deleted from this phone." : "Conversation deleted. Existing summaries keep their own source copies.";
                    refreshHistory(); publish();
                });
            } catch (RuntimeException e) {
                main.post(() -> { if (summary) deletedSummaryIds.remove(entry.id); else deletedRawIds.remove(entry.id); });
                storageFailed();
            }
        });
    }
    public String savedAddress() { return preferences.getString("address", ""); }
    public void observe(Runnable observer) {
        this.observer = () -> {};
        // Reopening never replays partial snapshots posted while the UI was away.
        stateDelivery.deliverLatest();
        this.observer = observer;
        observer.run();
    }
    public void observeService(Runnable observer) { serviceObserver = observer; }
    private void publish() { observer.run(); serviceObserver.run(); }
    public void setBackgroundActive(boolean active, String message) {
        backgroundActive = active;
        serviceMessage = message;
        publish();
    }
    private void maybeConnect() {
        if (backgroundActive && wanted && modelReady && !activeAddress.isEmpty()
                && session.snapshot().connection == AudioReceiver.Connection.DISCONNECTED) {
            try { receiver.connect(activeAddress, session); }
            catch (IllegalArgumentException e) { session.onError(e.getMessage()); }
        }
    }
    public void connect(String address) {
        try {
            if (session.isBusy()) { session.onError("Wait for the current recording to finish."); return; }
            wanted = true;
            if ("auto".equals(address)) {
                activeAddress = "";
                selectedBoard = preferences.getString("board_id", "");
                boards.clear(); boardSeen.clear(); discoveredBoards = Collections.emptyList();
                if (discovery != null) discovery.close();
                GlyphDiscovery current = new GlyphDiscovery(); discovery = current;
                discoveryStarted = android.os.SystemClock.elapsedRealtime();
                discoveryError = "";
                serviceMessage = "Finding Glyph on your hotspot…";
                current.start(board -> main.post(() -> {
                    if (closed || !wanted || discovery != current) return;
                    // Bound unsolicited LAN traffic; an existing board may always refresh itself.
                    if (boards.size() >= 16 && !boards.containsKey(board.id)) return;
                    boards.put(board.id, board); boardSeen.put(board.id, android.os.SystemClock.elapsedRealtime());
                    if (selectedBoard.equals(board.id)) useBoard(board);
                }), error -> main.post(() -> { if (!closed && discovery == current) { discoveryError = error; serviceMessage = error; publish(); } }));
                main.removeCallbacks(discoveryTick); main.post(discoveryTick);
            } else {
                LocalEndpoint.url(address);
                activeAddress = address.trim();
                preferences.edit().putString("address", activeAddress).apply();
            }
            // A started service may be recreated after permission/settings screens.
            // Queue the address until its local model is ready.
            maybeConnect();
        } catch (IllegalArgumentException e) { session.onError(e.getMessage()); }
    }
    public void disconnect() {
        wanted = false; main.removeCallbacks(discoveryTick);
        if (discovery != null) { discovery.close(); discovery = null; }
        boards.clear(); boardSeen.clear(); discoveredBoards = Collections.emptyList(); activeAddress = "";
        automaticSummaries.clear(); receiver.disconnect(); session.pause();
    }
    public void forgetBoard() { selectedBoard = ""; preferences.edit().remove("board_id").apply(); }
    public void retryModelSetup() {
        if (modelBusy || session.isBusy()) return;
        receiver.disconnect();
        loadModel(true);
    }
    private void loadModel(boolean reinstall) {
        modelSetupAttempted = true;
        modelBusy = true;
        modelReady = false;
        session.setReady(false);
        modelMessage = speech.cloud() ? "Preparing cloud speech recognition…" : "Preparing Parakeet speech recognition…";
        publish();
        worker.execute(() -> {
            boolean ready = false;
            String message;
            try {
                if (speech.cloud()) {
                    recognizer.loadCloud(speech);
                    ready = true;
                    message = "Cloud speech ready · audio sent in 15-second chunks";
                } else {
                if (reinstall || !models.isInstalled()) {
                    recognizer.close();
                    // The checksum-pinned model ships inside every APK.
                    // Extract only once, entirely offline, on the recognition worker.
                    try (InputStream input = getApplication().getAssets().open("models/" + LocalModelManager.MODEL_NAME + ".zip")) {
                        models.install(input, percent -> main.post(() -> {
                            if (closed) return;
                            modelMessage = "Preparing bundled Parakeet model… " + percent + "%";
                            publish();
                        }));
                    }
                }
                main.post(() -> {
                    if (closed) return;
                    modelMessage = "Loading Parakeet on your phone…";
                    publish();
                });
                android.app.ActivityManager.MemoryInfo memory = new android.app.ActivityManager.MemoryInfo();
                getApplication().getSystemService(android.app.ActivityManager.class).getMemoryInfo(memory);
                if (memory.lowMemory || memory.availMem < 1200L * 1024 * 1024)
                    throw new InsufficientMemoryException();
                recognizer.load(models.modelDirectory());
                ready = true;
                message = "Live speech ready · Parakeet Unified · On device";
                }
            } catch (InsufficientMemoryException | OutOfMemoryError e) {
                message = "Not enough free memory for Parakeet. Close other apps, then tap Retry setup.";
            } catch (Exception | LinkageError e) {
                message = speech.cloud() ? "Couldn't prepare cloud speech. Disconnect and check Speech recognition settings."
                        : "Couldn't prepare Parakeet. Free at least 2 GB storage and tap Retry setup.";
                if (BuildConfig.DEBUG) message += " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
            }
            final boolean loaded = ready;
            final String status = message;
            main.post(() -> {
                if (closed) return;
                modelBusy = false;
                modelReady = loaded;
                modelMessage = status;
                session.setReady(loaded);
                publish();
                maybeConnect();
            });
        });
    }
    private static final class InsufficientMemoryException extends Exception { }
    @Override protected void onCleared() {
        closed = true;
        main.removeCallbacks(discoveryTick);
        if (discovery != null) discovery.close();
        main.removeCallbacks(stopTimeout);
        automaticSummaries.clear(); summaryStarter = () -> {};
        stateDelivery.close();
        observer = () -> {};
        serviceObserver = () -> {};
        receiver.close();
        session.close();
        summaryClient.close();
        summaryWorker.shutdownNow();
        historyWorker.execute(history::close);
        historyWorker.shutdown();
    }
}
