package com.hozinking.appinspector.ui;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Penyimpanan artikel offline (Hozin Tools) — SQLite manual, tanpa Room.
 * Database: hozin_tools.db, tabel: saved_articles.
 *
 * Semua operasi DB ringan (insert/query) boleh dipanggil dari background
 * thread. Jangan panggil dari UI thread bila daftarnya besar.
 */
public class SavedArticleDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "hozin_tools.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "saved_articles";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static volatile SavedArticleDb instance;

    public static synchronized SavedArticleDb getInstance(Context ctx) {
        if (instance == null) {
            instance = new SavedArticleDb(ctx.getApplicationContext());
        }
        return instance;
    }

    private SavedArticleDb(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "title TEXT, "
                + "url TEXT UNIQUE, "
                + "excerpt TEXT, "
                + "content TEXT, "
                + "thumb_path TEXT, "
                + "saved_at INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1: tidak ada migrasi
    }

    /** Satu artikel tersimpan. */
    public static class SavedItem {
        public long id;
        public String title, url, excerpt, content, thumbPath;
        public long savedAt;
    }

    /**
     * Simpan (atau timpa bila url sudah ada). Mengembalikan row id.
     */
    public long save(String title, String url, String excerpt, String content, String thumbPath) {
        ContentValues cv = new ContentValues();
        cv.put("title", title);
        cv.put("url", url);
        cv.put("excerpt", excerpt);
        cv.put("content", content);
        cv.put("thumb_path", thumbPath);
        cv.put("saved_at", System.currentTimeMillis());
        return getWritableDatabase().insertWithOnConflict(
                TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Perbarui path thumbnail sebuah baris. */
    public void setThumbPath(long id, String thumbPath) {
        ContentValues cv = new ContentValues();
        cv.put("thumb_path", thumbPath);
        getWritableDatabase().update(TABLE, cv, "id=?", new String[]{String.valueOf(id)});
    }

    /** Semua artikel, terbaru dulu. */
    public List<SavedItem> list() {
        List<SavedItem> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE, null, null, null,
                    null, null, "saved_at DESC");
            while (c != null && c.moveToNext()) {
                out.add(fromCursor(c));
            }
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** Ambil satu artikel, atau null bila tidak ada. */
    public SavedItem get(long id) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE, null, "id=?",
                    new String[]{String.valueOf(id)}, null, null, null);
            if (c != null && c.moveToFirst()) {
                return fromCursor(c);
            }
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public void delete(long id) {
        getWritableDatabase().delete(TABLE, "id=?", new String[]{String.valueOf(id)});
    }

    public void clear() {
        getWritableDatabase().delete(TABLE, null, null);
    }

    public int count() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            if (c != null && c.moveToFirst()) {
                return c.getInt(0);
            }
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private static SavedItem fromCursor(Cursor c) {
        SavedItem it = new SavedItem();
        it.id = c.getLong(c.getColumnIndexOrThrow("id"));
        it.title = c.getString(c.getColumnIndexOrThrow("title"));
        it.url = c.getString(c.getColumnIndexOrThrow("url"));
        it.excerpt = c.getString(c.getColumnIndexOrThrow("excerpt"));
        it.content = c.getString(c.getColumnIndexOrThrow("content"));
        it.thumbPath = c.getString(c.getColumnIndexOrThrow("thumb_path"));
        it.savedAt = c.getLong(c.getColumnIndexOrThrow("saved_at"));
        return it;
    }

    /**
     * Unduh gambar thumbnail via HttpURLConnection (timeout 10 dtk, UA browser),
     * downscale sisi terpanjang maks 800px, simpan JPEG kualitas 80 ke
     * getFilesDir()/saved_thumbs/&lt;id&gt;.jpg.
     *
     * @return absolute path file, atau null bila gagal.
     *         WAJIB dipanggil dari background thread.
     */
    public static String downloadThumb(Context ctx, String url, long id) {
        if (ctx == null || url == null || url.trim().isEmpty()) return null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            if (conn.getResponseCode() / 100 != 2) return null;

            byte[] data = readAll(conn.getInputStream(), 8 * 1024 * 1024);
            if (data == null || data.length == 0) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 800) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp == null) return null;

            File dir = new File(ctx.getFilesDir(), "saved_thumbs");
            if (!dir.exists() && !dir.mkdirs()) {
                bmp.recycle();
                return null;
            }
            File out = new File(dir, id + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)) {
                    bmp.recycle();
                    return null;
                }
            }
            bmp.recycle();
            return out.getAbsolutePath();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Baca stream ke byte[], dibatasi maxBytes (null bila melebihi). */
    private static byte[] readAll(InputStream in, int maxBytes) {
        try (InputStream autoClose = in;
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = autoClose.read(buf)) != -1) {
                total += n;
                if (total > maxBytes) return null;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
