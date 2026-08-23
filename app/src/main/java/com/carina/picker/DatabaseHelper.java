package com.carina.picker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DatabaseHelper extends SQLiteOpenHelper {
    static final String DB_NAME = "carina_picker.db";
    private static final int DB_VERSION = 1;

    DatabaseHelper(Context context) { super(context, DB_NAME, null, DB_VERSION); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE pick_data (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "order_no TEXT NOT NULL, branch TEXT NOT NULL, sku TEXT NOT NULL," +
                "qty INTEGER NOT NULL, location TEXT NOT NULL," +
                "picked INTEGER NOT NULL DEFAULT 0, damage INTEGER NOT NULL DEFAULT 0," +
                "not_found INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'PENDING'," +
                "seq INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_pick_order ON pick_data(order_no, seq)");
        db.execSQL("CREATE TABLE pick_log (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "date_text TEXT,time_text TEXT,order_no TEXT,branch TEXT,location TEXT,sku TEXT," +
                "action TEXT,action_qty INTEGER,required_qty INTEGER,picked_qty INTEGER," +
                "damage_qty INTEGER,not_found_qty INTEGER,status TEXT)");
        db.execSQL("CREATE TABLE exceptions_log (id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "date_text TEXT,time_text TEXT,order_no TEXT,branch TEXT,location TEXT,sku TEXT," +
                "required_qty INTEGER,picked_qty INTEGER,damage_qty INTEGER,not_found_qty INTEGER," +
                "reason TEXT,final_status TEXT,line_seq INTEGER)");
        db.execSQL("CREATE TABLE settings (key_name TEXT PRIMARY KEY, value_text TEXT)");
        setSetting(db, "picker_started", "NO");
        setSetting(db, "current_id", "0");
        setSetting(db, "location_confirmed", "NO");
        setSetting(db, "current_order", "");
        setSetting(db, "current_location", "");
        setSetting(db, "order_start_ms", "0");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    void insertImportedLines(List<Models.PickLine> lines) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int base = getMaxSeq(db);
            for (Models.PickLine l : lines) {
                ContentValues cv = new ContentValues();
                cv.put("order_no", l.orderNo); cv.put("branch", l.branch); cv.put("sku", l.sku);
                cv.put("qty", l.qty); cv.put("location", l.location);
                cv.put("picked", 0); cv.put("damage", 0); cv.put("not_found", 0);
                cv.put("status", "PENDING"); cv.put("seq", ++base);
                db.insertOrThrow("pick_data", null, cv);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private int getMaxSeq(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(seq),0) FROM pick_data", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    Set<String> existingOrders() {
        Set<String> out = new HashSet<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT DISTINCT order_no FROM pick_data", null)) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    Models.PickLine line(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM pick_data WHERE id=?", new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    Models.PickLine firstOpenLine() {
        String sql = "SELECT * FROM pick_data WHERE (picked+damage+not_found)<qty " +
                "ORDER BY CASE WHEN (picked+damage+not_found)>0 OR status='PICKING' THEN 0 ELSE 1 END, seq LIMIT 1";
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    Models.PickLine firstOpenLineForOrder(String orderNo) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM pick_data WHERE order_no=? AND (picked+damage+not_found)<qty ORDER BY seq LIMIT 1",
                new String[]{orderNo})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    Models.PickLine firstOpenExcludingOrder(String orderNo) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT * FROM pick_data WHERE order_no<>? AND (picked+damage+not_found)<qty ORDER BY seq LIMIT 1",
                new String[]{orderNo})) {
            return c.moveToFirst() ? fromCursor(c) : null;
        }
    }

    boolean isOrderComplete(String orderNo) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM pick_data WHERE order_no=? AND (picked+damage+not_found)<qty",
                new String[]{orderNo})) {
            return c.moveToFirst() && c.getInt(0) == 0;
        }
    }

    void incrementPicked(long id) {
        Models.PickLine l = line(id); if (l == null || l.remaining() <= 0) return;
        ContentValues cv = new ContentValues();
        cv.put("picked", l.picked + 1);
        cv.put("status", buildStatus(l.qty, l.picked + 1, l.damage, l.notFound));
        getWritableDatabase().update("pick_data", cv, "id=?", new String[]{String.valueOf(id)});
    }

    void incrementDamage(long id) {
        Models.PickLine l = line(id); if (l == null || l.remaining() <= 0) return;
        ContentValues cv = new ContentValues();
        cv.put("damage", l.damage + 1);
        cv.put("status", buildStatus(l.qty, l.picked, l.damage + 1, l.notFound));
        getWritableDatabase().update("pick_data", cv, "id=?", new String[]{String.valueOf(id)});
    }

    int markRemainingNotFound(long id) {
        Models.PickLine l = line(id); if (l == null) return 0;
        int rem = l.remaining(); if (rem <= 0) return 0;
        ContentValues cv = new ContentValues();
        cv.put("not_found", l.notFound + rem);
        cv.put("status", buildStatus(l.qty, l.picked, l.damage, l.notFound + rem));
        getWritableDatabase().update("pick_data", cv, "id=?", new String[]{String.valueOf(id)});
        return rem;
    }

    void setPicking(long id) {
        Models.PickLine l = line(id); if (l == null || l.complete()) return;
        ContentValues cv = new ContentValues(); cv.put("status", "PICKING");
        getWritableDatabase().update("pick_data", cv, "id=?", new String[]{String.valueOf(id)});
    }

    String buildStatus(Models.PickLine l) { return buildStatus(l.qty,l.picked,l.damage,l.notFound); }
    private String buildStatus(int req, int picked, int damage, int nf) {
        int classified = picked + damage + nf;
        if (classified < req) return "PICKING";
        if (damage > 0 && nf > 0) return "COMPLETED - DAMAGE & NOT FOUND";
        if (damage > 0) return "COMPLETED - DAMAGE";
        if (nf > 0) return "COMPLETED - NOT FOUND";
        return "COMPLETED";
    }

    void writeLog(Models.PickLine l, String action, int actionQty) {
        Models.PickLine now = line(l.id); if (now == null) now = l;
        ContentValues cv = baseLog(now);
        cv.put("action", action); cv.put("action_qty", actionQty); cv.put("status", buildStatus(now));
        getWritableDatabase().insert("pick_log", null, cv);
    }

    void writeException(Models.PickLine l, int qty, String reason) {
        Models.PickLine now = line(l.id); if (now == null) now = l;
        ContentValues cv = baseLog(now);
        cv.put("reason", reason); cv.put("final_status", buildStatus(now)); cv.put("line_seq", now.seq);
        getWritableDatabase().insert("exceptions_log", null, cv);
    }

    private ContentValues baseLog(Models.PickLine l) {
        Date now = new Date();
        ContentValues cv = new ContentValues();
        cv.put("date_text", new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(now));
        cv.put("time_text", new SimpleDateFormat("hh:mm:ss a", Locale.US).format(now));
        cv.put("order_no", l.orderNo); cv.put("branch", l.branch); cv.put("location", l.location); cv.put("sku", l.sku);
        cv.put("required_qty", l.qty); cv.put("picked_qty", l.picked); cv.put("damage_qty", l.damage); cv.put("not_found_qty", l.notFound);
        return cv;
    }

    Models.OrderSummary summary(String orderNo, long elapsedMs) {
        Models.OrderSummary s = new Models.OrderSummary(); s.orderNo = orderNo; s.elapsedMs = Math.max(0,elapsedMs);
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT branch,qty,picked,damage,not_found FROM pick_data WHERE order_no=? ORDER BY seq", new String[]{orderNo})) {
            while (c.moveToNext()) {
                if (s.branch.isEmpty()) s.branch = c.getString(0);
                s.required += c.getInt(1); s.picked += c.getInt(2); s.damage += c.getInt(3); s.notFound += c.getInt(4);
                s.totalLines++; if (c.getInt(2)+c.getInt(3)+c.getInt(4) >= c.getInt(1)) s.doneLines++;
            }
        }
        return s;
    }

    List<Models.PickLine> allLines() {
        List<Models.PickLine> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM pick_data ORDER BY seq", null)) {
            while(c.moveToNext()) out.add(fromCursor(c));
        }
        return out;
    }

    String dumpTable(String table, int limit) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM " + table + " ORDER BY id DESC LIMIT " + limit, null)) {
            String[] cols = c.getColumnNames();
            while (c.moveToNext()) {
                for (int i=0;i<cols.length;i++) {
                    if (i>0) sb.append(" | ");
                    sb.append(cols[i]).append(": ").append(c.isNull(i)?"":c.getString(i));
                }
                sb.append('\n');
            }
        }
        return sb.length()==0 ? "No data." : sb.toString();
    }

    void resetSession() {
        setSetting("picker_started","NO"); setSetting("current_id","0"); setSetting("location_confirmed","NO");
        setSetting("current_order",""); setSetting("current_location",""); setSetting("order_start_ms","0");
    }

    void clearHistory() {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("pick_data",null,null); db.delete("pick_log",null,null); db.delete("exceptions_log",null,null);
            resetSession(); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    String getSetting(String key) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT value_text FROM settings WHERE key_name=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }
    long getSettingLong(String key) { try { return Long.parseLong(getSetting(key)); } catch(Exception e){ return 0; } }
    void setSetting(String key, String value) { setSetting(getWritableDatabase(),key,value); }
    private void setSetting(SQLiteDatabase db, String key, String value) {
        ContentValues cv = new ContentValues(); cv.put("key_name",key); cv.put("value_text",value);
        db.insertWithOnConflict("settings",null,cv,SQLiteDatabase.CONFLICT_REPLACE);
    }

    private Models.PickLine fromCursor(Cursor c) {
        Models.PickLine l = new Models.PickLine();
        l.id=c.getLong(c.getColumnIndexOrThrow("id")); l.orderNo=c.getString(c.getColumnIndexOrThrow("order_no"));
        l.branch=c.getString(c.getColumnIndexOrThrow("branch")); l.sku=c.getString(c.getColumnIndexOrThrow("sku"));
        l.qty=c.getInt(c.getColumnIndexOrThrow("qty")); l.location=c.getString(c.getColumnIndexOrThrow("location"));
        l.picked=c.getInt(c.getColumnIndexOrThrow("picked")); l.damage=c.getInt(c.getColumnIndexOrThrow("damage"));
        l.notFound=c.getInt(c.getColumnIndexOrThrow("not_found")); l.status=c.getString(c.getColumnIndexOrThrow("status"));
        l.seq=c.getInt(c.getColumnIndexOrThrow("seq")); return l;
    }
}
