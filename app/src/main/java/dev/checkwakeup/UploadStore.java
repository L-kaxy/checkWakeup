package dev.checkwakeup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** All access is serialized by UploadManager. No unbounded refresh/event upload log. */
final class UploadStore extends SQLiteOpenHelper {
    static final int MAX_DAYS = 31;

    static final class Day {
        String key, zone, target, label, windows, payload, signal;
        long reference, end;
        boolean finalized;
    }

    UploadStore(Context context) { super(context, "uploads.db", null, 2); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE days (day_key TEXT PRIMARY KEY, reference INTEGER NOT NULL,"
                + "end_at INTEGER NOT NULL, zone TEXT NOT NULL,target TEXT NOT NULL,label TEXT NOT NULL,"
                + "windows TEXT NOT NULL,payload TEXT NOT NULL,revision INTEGER NOT NULL,"
                + "finalized INTEGER NOT NULL,dirty INTEGER NOT NULL,signal TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE outbox (id INTEGER PRIMARY KEY CHECK(id=1),body TEXT NOT NULL)");
        ContentValues initial = new ContentValues();
        initial.put("name", "device_code");
        initial.put("value", UUID.randomUUID().toString());
        db.insertOrThrow("metadata", null, initial);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE days ADD COLUMN signal TEXT NOT NULL DEFAULT ''");
            return;
        }
        throw new IllegalStateException("Unsupported upload database migration");
    }

    String text(String key, String fallback) {
        try (Cursor cursor = getReadableDatabase().query("metadata", new String[]{"value"},
                "name=?", new String[]{key}, null, null, null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : fallback;
        }
    }

    long number(String key) { return Long.parseLong(text(key, "0")); }

    void putMeta(String key, Object value) {
        ContentValues row = new ContentValues();
        row.put("name", key);
        row.put("value", String.valueOf(value));
        getWritableDatabase().insertWithOnConflict("metadata", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    long nextSequence() {
        long current = number("sequence");
        if (current >= 9_007_199_254_740_990L) throw new IllegalStateException("Sequence exhausted");
        long next = current + 1;
        putMeta("sequence", next);
        return next;
    }

    List<Day> unfinished(long now) {
        List<Day> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT day_key,reference,end_at,zone,target,label,windows,payload,finalized,signal "
                        + "FROM days WHERE finalized=0 AND end_at<=? ORDER BY end_at",
                new String[]{Long.toString(now)})) {
            while (cursor.moveToNext()) result.add(readDay(cursor));
        }
        return result;
    }

    private Day readDay(Cursor cursor) {
        Day day = new Day();
        day.key = cursor.getString(0);
        day.reference = cursor.getLong(1);
        day.end = cursor.getLong(2);
        day.zone = cursor.getString(3);
        day.target = cursor.getString(4);
        day.label = cursor.getString(5);
        day.windows = cursor.getString(6);
        day.payload = cursor.getString(7);
        day.finalized = cursor.getInt(8) != 0;
        day.signal = cursor.getString(9);
        return day;
    }

    void put(Day day, long revision) {
        ContentValues row = new ContentValues();
        row.put("day_key", day.key);
        row.put("reference", day.reference);
        row.put("end_at", day.end);
        row.put("zone", day.zone);
        row.put("target", day.target);
        row.put("label", day.label);
        row.put("windows", day.windows);
        row.put("payload", day.payload);
        row.put("revision", revision);
        row.put("finalized", day.finalized ? 1 : 0);
        row.put("dirty", 1);
        row.put("signal", day.signal);
        getWritableDatabase().insertWithOnConflict("days", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void boundQueue() {
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.rawQuery("SELECT day_key,dirty FROM days "
                + "ORDER BY end_at DESC,revision DESC LIMIT -1 OFFSET " + MAX_DAYS, null)) {
            while (cursor.moveToNext()) {
                if (cursor.getInt(1) != 0) putMeta("expired_days", number("expired_days") + 1);
                db.delete("days", "day_key=?", new String[]{cursor.getString(0)});
            }
        }
    }

    int pending() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM days WHERE dirty=1", null)) {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        }
    }

    List<String> pendingDays() {
        List<String> result = new ArrayList<>();
        int bytes = 0;
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT payload FROM days WHERE dirty=1 ORDER BY end_at", null)) {
            while (cursor.moveToNext() && result.size() < MAX_DAYS) {
                String payload = cursor.getString(0);
                int length = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                // Reserve space for root fields and JSON delimiters.
                if (length > 2 * 1024 * 1024 - 8192) {
                    throw new IllegalArgumentException("单日数据超过服务器限制");
                }
                if (bytes + length > 2 * 1024 * 1024 - 8192) break;
                bytes += length + 1;
                result.add(payload);
            }
        }
        return result;
    }

    String outbox() {
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT body FROM outbox WHERE id=1", null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    void outbox(String body) {
        ContentValues row = new ContentValues();
        row.put("id", 1);
        row.put("body", body);
        getWritableDatabase().insertWithOnConflict("outbox", null, row, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void acknowledge(JSONObject report, long now) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            JSONArray days = report.getJSONArray("days");
            for (int i = 0; i < days.length(); i++) {
                JSONObject day = days.getJSONObject(i);
                ContentValues row = new ContentValues();
                row.put("dirty", 0);
                db.update("days", row, "day_key=? AND revision=?", new String[]{
                        day.getString("business_date") + "|" + day.getString("timezone"),
                        Long.toString(day.getLong("revision"))});
            }
            db.delete("outbox", null, null);
            putMeta("last_success", now);
            resetFailure();
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    void resetFailure() {
        putMeta("error", "");
        putMeta("blocked", 0);
        putMeta("attempt", 0);
        putMeta("retry_at", 0);
    }

    void clearPending() {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("days", null, null);
            db.delete("outbox", null, null);
            resetFailure();
            putMeta("last_success", 0);
            putMeta("expired_days", 0);
            putMeta("last_signal", "");
            putMeta("last_queued_at", 0);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
