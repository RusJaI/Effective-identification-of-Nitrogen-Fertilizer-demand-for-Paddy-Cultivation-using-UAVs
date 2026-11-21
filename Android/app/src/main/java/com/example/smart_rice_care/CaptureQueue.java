package com.example.smart_rice_care;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Small on-device outbox. Rows are removed only after prediction and Firestore write succeed. */
public final class CaptureQueue extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "capture-outbox.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE = "captures";
    private static final String[] COLUMNS = {
            "id", "image_path", "request_id", "officer_id", "latitude", "longitude", "timestamp"
    };

    public CaptureQueue(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (id TEXT PRIMARY KEY, image_path TEXT NOT NULL, " +
                "request_id TEXT NOT NULL, officer_id TEXT, latitude REAL NOT NULL, longitude REAL NOT NULL, " +
                "timestamp INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Keep queued captures across app updates. Add explicit migrations when the schema changes.
    }

    public synchronized void add(CaptureRecord capture) {
        ContentValues values = new ContentValues();
        values.put("id", capture.id);
        values.put("image_path", capture.imagePath);
        values.put("request_id", capture.requestId);
        values.put("officer_id", capture.officerId);
        values.put("latitude", capture.latitude);
        values.put("longitude", capture.longitude);
        values.put("timestamp", capture.timestamp);
        getWritableDatabase().insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized CaptureRecord get(String id) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, COLUMNS, "id = ?", new String[]{id}, null, null, null)) {
            return cursor.moveToFirst() ? read(cursor) : null;
        }
    }

    public synchronized List<CaptureRecord> getPending() {
        List<CaptureRecord> captures = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, COLUMNS, null, null, null, null, "timestamp ASC")) {
            while (cursor.moveToNext()) captures.add(read(cursor));
        }
        return captures;
    }

    public synchronized List<CaptureRecord> getPendingForRequest(String requestId) {
        List<CaptureRecord> captures = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, COLUMNS, "request_id = ?",
                new String[]{requestId}, null, null, "timestamp ASC")) {
            while (cursor.moveToNext()) captures.add(read(cursor));
        }
        return captures;
    }

    public synchronized void remove(String id) {
        getWritableDatabase().delete(TABLE, "id = ?", new String[]{id});
    }

    public synchronized String removeForImage(String requestId, long timestamp) {
        String id = null;
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{"id"},
                "request_id = ? AND timestamp = ?", new String[]{requestId, Long.toString(timestamp)},
                null, null, null)) {
            if (cursor.moveToFirst()) id = cursor.getString(0);
        }
        getWritableDatabase().delete(TABLE, "request_id = ? AND timestamp = ?",
                new String[]{requestId, Long.toString(timestamp)});
        return id;
    }

    private CaptureRecord read(Cursor cursor) {
        return new CaptureRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                cursor.getString(3), cursor.getDouble(4), cursor.getDouble(5), cursor.getLong(6));
    }
}
