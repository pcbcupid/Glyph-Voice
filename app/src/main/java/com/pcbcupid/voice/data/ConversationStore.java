package com.pcbcupid.voice.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.pcbcupid.voice.core.VoiceState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import com.pcbcupid.voice.ai.SummaryClient;

/** Private, local-only text storage. All calls belong on the controller's history executor. */
public final class ConversationStore extends SQLiteOpenHelper {
    public static final class Entry {
        public final String id, text, status, sourceId, source, provider, model;
        public final long created;
        public Entry(String id, String text, String status, long created) {
            this(id, text, status, created, id, "", "", "");
        }
        public Entry(String id, String text, String status, long created, String sourceId, String source, String provider, String model) {
            this.id = id; this.text = text; this.status = status; this.created = created;
            this.sourceId = sourceId; this.source = source; this.provider = provider; this.model = model;
        }
    }
    private final String runId = UUID.randomUUID().toString();
    private String lastId = "", lastText = "", lastStatus = "";
    private long created;
    // Recording IDs include a per-process UUID. Suppress late checkpoints for
    // deleted recordings for the rest of this run; they cannot recur after restart.
    private final Set<String> deletedRecordings = new HashSet<>();

    public ConversationStore(Context context) { this(context, "conversations.db"); }
    ConversationStore(Context context, String name) { super(context, name, null, 2); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE conversations (id TEXT PRIMARY KEY, text TEXT NOT NULL, "
                + "status TEXT NOT NULL, created INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE summaries (id TEXT PRIMARY KEY, text TEXT NOT NULL, "
                + "status TEXT NOT NULL, created INTEGER NOT NULL, source_id TEXT NOT NULL, "
                + "source TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            for (String column : new String[]{"source_id", "source", "provider", "model"})
                db.execSQL("ALTER TABLE summaries ADD COLUMN " + column + " TEXT NOT NULL DEFAULT ''");
            // Old rows were source snapshots, NOT AI results. Preserve them honestly.
            db.execSQL("UPDATE summaries SET source_id=id, source=text, text='', status='Not generated'");
        }
    }
    public void recoverInterrupted() {
        getWritableDatabase().execSQL("UPDATE conversations SET status='Interrupted' WHERE status='Recording'");
        getWritableDatabase().execSQL("UPDATE summaries SET status='Interrupted — tap to retry' WHERE status='Summarizing'");
    }
    public String idFor(long sequence) { return runId + ":" + sequence; }

    /** Checkpoint each changed partial; update the SAME row on completion or interruption. */
    public boolean save(VoiceState state) {
        if (state.recordingSequence == 0 || state.text.isEmpty()) return false;
        String id = idFor(state.recordingSequence);
        if (deletedRecordings.contains(id)) return false;
        String status = state.interrupted ? "Interrupted" : state.complete ? "Complete" : "Recording";
        if (id.equals(lastId) && state.text.equals(lastText) && status.equals(lastStatus)) return false;
        if (!id.equals(lastId)) created = System.currentTimeMillis();
        put("conversations", new Entry(id, state.text, status, created));
        // Only advance the checkpoint AFTER SQLite succeeds; a failed write can be retried.
        lastId = id; lastText = state.text; lastStatus = status;
        return true;
    }
    public void requestSummary(Entry source) {
        // Legacy/test caller; preserve the snapshot without representing it as a summary.
        put("summaries", new Entry(source.id, "", "Not generated", source.created,
                source.id, source.text, "", ""));
    }
    public Entry beginSummary(Entry source, String provider, String model) {
        // Same source snapshot/provider/model reuses its result; a different one
        // gets a separate row and cannot overwrite an earlier successful summary.
        String identity = SummaryClient.PROMPT_VERSION + "\n" + source.id + "\n" + provider + "\n" + model + "\n" + source.text;
        String id = UUID.nameUUIDFromBytes(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        try (Cursor c = getReadableDatabase().query("summaries", null, "id=?", new String[]{id}, null, null, null)) {
            if (c.moveToFirst() && "Complete".equals(c.getString(c.getColumnIndexOrThrow("status"))))
                return readSummary(c);
        }
        Entry entry = new Entry(id, "", "Summarizing", System.currentTimeMillis(), source.id, source.text, provider, model);
        put("summaries", entry);
        return entry;
    }
    public boolean finishSummary(Entry pending, String text, String status) {
        ContentValues values = new ContentValues();
        values.put("text", text); values.put("status", status);
        // UPDATE, never REPLACE: a late HTTP response must not resurrect a deleted row.
        return getWritableDatabase().update("summaries", values, "id=?", new String[]{pending.id}) == 1;
    }
    public void delete(Entry entry, boolean summary) {
        getWritableDatabase().delete(summary ? "summaries" : "conversations", "id=?", new String[]{entry.id});
        if (!summary) deletedRecordings.add(entry.id);
    }
    private void put(String table, Entry entry) {
        ContentValues values = new ContentValues();
        values.put("id", entry.id); values.put("text", entry.text);
        values.put("status", entry.status); values.put("created", entry.created);
        if ("summaries".equals(table)) {
            values.put("source_id", entry.sourceId); values.put("source", entry.source);
            values.put("provider", entry.provider); values.put("model", entry.model);
        }
        getWritableDatabase().replaceOrThrow(table, null, values);
    }
    public List<Entry> list(boolean summaries) {
        List<Entry> entries = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(summaries ? "summaries" : "conversations",
                null, null, null, null, null, "created DESC, id DESC")) {
            while (cursor.moveToNext()) entries.add(summaries ? readSummary(cursor) :
                    new Entry(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)));
        }
        return entries;
    }
    private static Entry readSummary(Cursor c) {
        return new Entry(c.getString(c.getColumnIndexOrThrow("id")), c.getString(c.getColumnIndexOrThrow("text")),
                c.getString(c.getColumnIndexOrThrow("status")), c.getLong(c.getColumnIndexOrThrow("created")),
                c.getString(c.getColumnIndexOrThrow("source_id")), c.getString(c.getColumnIndexOrThrow("source")),
                c.getString(c.getColumnIndexOrThrow("provider")), c.getString(c.getColumnIndexOrThrow("model")));
    }
}
