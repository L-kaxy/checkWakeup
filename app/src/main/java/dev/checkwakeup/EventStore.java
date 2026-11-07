package dev.checkwakeup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import dev.checkwakeup.core.UsageEvent;

import java.util.ArrayList;
import java.util.List;

final class EventStore extends SQLiteOpenHelper {
    private static EventStore instance;

    static synchronized EventStore get(Context context) {
        if (instance == null) instance = new EventStore(context.getApplicationContext());
        return instance;
    }

    private EventStore(Context context) { super(context, "events.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, time INTEGER NOT NULL, "
                + "type INTEGER NOT NULL, package TEXT NOT NULL, activity TEXT NOT NULL, "
                + "UNIQUE(time,type,package,activity))");
        db.execSQL("CREATE INDEX event_time ON events(time)");
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY, value INTEGER NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("No migration from " + oldVersion + " to " + newVersion);
    }

    synchronized int save(List<UsageEvent> events, long syncedAt, long requestedStart) {
        SQLiteDatabase db = getWritableDatabase();
        int inserted = 0;
        db.beginTransaction();
        try {
            for (UsageEvent event : events) {
                ContentValues values = new ContentValues();
                values.put("time", event.time);
                values.put("type", event.type);
                values.put("package", event.packageName);
                values.put("activity", event.activity);
                if (db.insertWithOnConflict("events", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1) inserted++;
            }
            putMeta(db, "synced_at", syncedAt);
            if (meta("requested_start") == 0) putMeta(db, "requested_start", requestedStart);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return inserted;
    }

    private void putMeta(SQLiteDatabase db, String key, long value) {
        ContentValues values = new ContentValues();
        values.put("name", key);
        values.put("value", value);
        db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    synchronized long meta(String key) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT value FROM metadata WHERE name=?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    synchronized List<UsageEvent> read(long begin, long end) {
        List<UsageEvent> events = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT time,type,package,activity FROM events WHERE time>=? AND time<=? "
                        + "ORDER BY time,id", new String[]{Long.toString(begin), Long.toString(end)})) {
            while (cursor.moveToNext()) {
                events.add(new UsageEvent(cursor.getLong(0), cursor.getInt(1),
                        cursor.getString(2), cursor.getString(3)));
            }
        }
        return events;
    }

    synchronized void prune(long before) {
        getWritableDatabase().delete("events", "time<?", new String[]{Long.toString(before)});
    }
}
