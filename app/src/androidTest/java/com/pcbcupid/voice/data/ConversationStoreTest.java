package com.pcbcupid.voice.data;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.pcbcupid.voice.audio.AudioReceiver.Connection;
import com.pcbcupid.voice.core.VoiceState;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ConversationStoreTest {
    @Test public void deletedRecordingCannotBeResurrectedByLaterPartials() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "delete-recording-test-" + UUID.randomUUID() + ".db";
        try (ConversationStore store = new ConversationStore(context, name)) {
            store.save(state(1, "Live words", false, false));
            store.delete(store.list(false).get(0), false);
            assertFalse(store.save(state(1, "Live words and later text", false, false)));
            assertFalse(store.save(state(1, "Final text", true, false)));
            assertTrue(store.list(false).isEmpty());
            assertTrue(store.save(state(2, "New recording", true, false)));
            assertEquals(1, store.list(false).size());
        } finally { context.deleteDatabase(name); }
    }
    @Test public void deletedSummaryCannotBeResurrectedByLateHttpResponse() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "delete-summary-test-" + UUID.randomUUID() + ".db";
        try (ConversationStore store = new ConversationStore(context, name)) {
            store.save(state(1, "Source words", true, false));
            ConversationStore.Entry source = store.list(false).get(0);
            ConversationStore.Entry pending = store.beginSummary(source, "DeepSeek", "deepseek-flash");
            store.delete(pending, true);
            assertFalse(store.finishSummary(pending, "Late result", "Complete"));
            assertTrue(store.list(true).isEmpty());
            assertEquals("Source words", store.list(false).get(0).text);
            ConversationStore.Entry retry = store.beginSummary(source, "DeepSeek", "deepseek-flash");
            assertTrue(store.finishSummary(retry, "Explicit new request", "Complete"));
        } finally { context.deleteDatabase(name); }
    }
    @Test public void englishPromptDoesNotReuseOldLanguageSummary() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "prompt-version-test-" + UUID.randomUUID() + ".db";
        try (ConversationStore store = new ConversationStore(context, name)) {
            ConversationStore.Entry source = new ConversationStore.Entry("r1", "Original words", "Complete", 1);
            String oldIdentity = "r1\nDeepSeek\ndeepseek-flash\nOriginal words";
            String oldId = UUID.nameUUIDFromBytes(oldIdentity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            store.getWritableDatabase().execSQL("INSERT INTO summaries VALUES (?,?,?,?,?,?,?,?)",
                    new Object[]{oldId, "旧摘要", "Complete", 1, "r1", source.text, "DeepSeek", "deepseek-flash"});
            String templateId = UUID.nameUUIDFromBytes(("english-summary-v2\n" + oldIdentity)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
            store.getWritableDatabase().execSQL("INSERT INTO summaries VALUES (?,?,?,?,?,?,?,?)",
                    new Object[]{templateId, "Overview: Old template", "Complete", 2, "r1", source.text, "DeepSeek", "deepseek-flash"});
            ConversationStore.Entry request = store.beginSummary(source, "DeepSeek", "deepseek-flash");
            assertEquals("Summarizing", request.status);
            assertNotEquals(oldId, request.id);
            assertNotEquals(templateId, request.id);
            assertEquals(3, store.list(true).size());
        } finally { context.deleteDatabase(name); }
    }
    @Test public void upgradePreservesRawHistoryAndConvertsLegacyPendingSnapshots() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "migration-test-" + UUID.randomUUID() + ".db";
        try {
            try (android.database.sqlite.SQLiteDatabase db = context.openOrCreateDatabase(name, 0, null)) {
                db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, text TEXT NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL)");
                db.execSQL("CREATE TABLE summaries (id TEXT PRIMARY KEY, text TEXT NOT NULL, status TEXT NOT NULL, created INTEGER NOT NULL)");
                db.execSQL("INSERT INTO conversations VALUES ('r1','Keep my conversation','Complete',1)");
                db.execSQL("INSERT INTO summaries VALUES ('r1','Keep my conversation','Pending · no summary generated',1)");
                db.setVersion(1);
            }
            try (ConversationStore store = new ConversationStore(context, name)) {
                assertEquals("Keep my conversation", store.list(false).get(0).text);
                ConversationStore.Entry pending = store.list(true).get(0);
                assertEquals("Keep my conversation", pending.source);
                assertEquals("r1", pending.sourceId);
                assertEquals("", pending.text);
                assertEquals("Not generated", pending.status);
            }
        } finally { context.deleteDatabase(name); }
    }
    @Test public void summaryDeduplicatesSnapshotsWithoutOverwritingPriorResults() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "summary-test-" + UUID.randomUUID() + ".db";
        try {
            try (ConversationStore store = new ConversationStore(context, name)) {
                ConversationStore.Entry source = new ConversationStore.Entry("r1", "Original words", "Complete", 1);
                ConversationStore.Entry pending = store.beginSummary(source, "DeepSeek", "deepseek-flash");
                store.finishSummary(pending, "Generated summary", "Complete");
                ConversationStore.Entry repeated = store.beginSummary(source, "DeepSeek", "deepseek-flash");
                assertEquals("Complete", repeated.status);
                assertEquals("Generated summary", repeated.text);
                assertEquals(1, store.list(true).size());
                store.beginSummary(new ConversationStore.Entry("r1", "Changed words", "Complete", 1), "DeepSeek", "deepseek-flash");
                assertEquals(2, store.list(true).size());
                store.recoverInterrupted();
                assertEquals(1, store.list(true).stream().filter(e -> e.status.startsWith("Interrupted")).count());
                assertEquals(1, store.list(true).stream().filter(e -> e.text.equals("Generated summary")).count());
            }
        } finally { context.deleteDatabase(name); }
    }
    private VoiceState state(long sequence, String text, boolean complete, boolean interrupted) {
        return new VoiceState(Connection.CONNECTED, VoiceState.Phase.RESULT, text, "",
                1, 3200, 16000, .1, 1, sequence, complete, interrupted);
    }
    @Test public void checkpointsAndUnsentLegacyRequestsPersistWithoutDuplicates() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "history-test-" + UUID.randomUUID() + ".db";
        try {
            try (ConversationStore store = new ConversationStore(context, name)) {
                store.recoverInterrupted();
                assertTrue(store.list(false).isEmpty());
                assertFalse(store.save(state(0, "", false, false)));
                store.save(state(1, "First words", false, false));
                store.save(state(1, "First words complete", true, false));
                assertEquals(1, store.list(false).size());
                ConversationStore.Entry first = store.list(false).get(0);
                assertEquals("Complete", first.status);
                store.requestSummary(first);
                store.requestSummary(first);
                assertEquals(1, store.list(true).size());
                assertEquals("", store.list(true).get(0).text);
                assertEquals("First words complete", store.list(true).get(0).source);
                assertEquals("Not generated", store.list(true).get(0).status);
                store.save(state(2, "", false, false));
                assertEquals(1, store.list(false).size());
                store.save(state(2, "Interrupted words", false, true));
                store.save(state(3, "Process killed mid recording", false, false));
            }
            try (ConversationStore reopened = new ConversationStore(context, name)) {
                reopened.recoverInterrupted();
                assertEquals(3, reopened.list(false).size());
                assertEquals(1, reopened.list(true).size());
                assertEquals(2, reopened.list(false).stream().filter(e -> e.status.equals("Interrupted")).count());
                // IDs cannot collide when firmware or the app restarts its sequence counter.
                reopened.save(state(1, "New run", true, false));
                assertEquals(4, reopened.list(false).size());
            }
        } finally { context.deleteDatabase(name); }
    }
}
