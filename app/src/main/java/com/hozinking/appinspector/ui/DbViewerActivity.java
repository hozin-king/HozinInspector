package com.hozinking.appinspector.ui;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Database Viewer (ROOT): lihat database SQLite app target.
 * Copy read-only db target ke cache sendiri via su, lalu baca pakai SQLite API.
 * Tidak pernah menulis ke /data/data app target.
 */
public class DbViewerActivity extends BaseActivity {

    private static final int ROW_LIMIT = 100;

    private String pkg = "";
    private TextView tvTitle, tvCrumb, tvHeader;
    private RecyclerView rv;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // state stack: 0=db list, 1=table list, 2=rows
    private int state = 0;
    private String currentDb = "";
    private String currentTable = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_db_viewer);
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) pkg = "";

        tvTitle = findViewById(R.id.tvDbTitle);
        tvCrumb = findViewById(R.id.tvDbCrumb);
        tvHeader = findViewById(R.id.tvDbHeader);
        rv = findViewById(R.id.rvDb);
        rv.setLayoutManager(new LinearLayoutManager(this));
        tvTitle.setText("Database Viewer — " + pkg);
        loadDbList();
    }

    @Override
    public void onBackPressed() {
        if (state == 2) {
            state = 1;
            loadTableList();
        } else if (state == 1) {
            state = 0;
            loadDbList();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- daftar database ----------

    private void loadDbList() {
        state = 0;
        tvHeader.setVisibility(View.GONE);
        tvCrumb.setText("Memuat daftar database...");
        new Thread(() -> {
            SuHelper.Result r = SuHelper.exec(
                    "ls " + SuHelper.q("/data/data/" + pkg + "/databases/"));
            final List<String> dbs = new ArrayList<>();
            if (r.code == 0) {
                for (String line : r.out.split("\n")) {
                    String t = line.trim();
                    if (t.isEmpty()) continue;
                    if (t.endsWith("-journal") || t.endsWith("-wal") || t.endsWith("-shm")) continue;
                    dbs.add(t);
                }
            }
            final boolean ok = r.code == 0;
            handler.post(() -> {
                tvCrumb.setText("databases/ (" + dbs.size() + " db)");
                rv.setAdapter(new StrAdapter(dbs, pos -> {
                    currentDb = dbs.get(pos);
                    state = 1;
                    loadTableList();
                }));
                if (!ok) toast("Gagal baca direktori (root?)");
                if (dbs.isEmpty() && ok) toast("Tidak ada database");
            });
        }).start();
    }

    // ---------- daftar tabel ----------

    private void loadTableList() {
        tvHeader.setVisibility(View.GONE);
        tvCrumb.setText("Membaca " + currentDb + "...");
        new Thread(() -> {
            final List<String> tables = new ArrayList<>();
            String err = readDb(currentDb, db -> {
                Cursor c = db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table'"
                                + " AND name NOT LIKE 'sqlite_%' ORDER BY name", null);
                while (c.moveToNext()) tables.add(c.getString(0));
                c.close();
            });
            handler.post(() -> {
                tvCrumb.setText(currentDb + " (" + tables.size() + " tabel)");
                rv.setAdapter(new StrAdapter(tables, pos -> {
                    currentTable = tables.get(pos);
                    state = 2;
                    loadRows();
                }));
                if (err != null) toast(err);
            });
        }).start();
    }

    // ---------- isi tabel (100 baris pertama) ----------

    private void loadRows() {
        tvCrumb.setText("Membaca " + currentTable + "...");
        new Thread(() -> {
            final List<String> cols = new ArrayList<>();
            final List<String> rows = new ArrayList<>();
            String err = readDb(currentDb, db -> {
                String qt = "\"" + currentTable.replace("\"", "\"\"") + "\"";
                Cursor ci = db.rawQuery("PRAGMA table_info(" + qt + ")", null);
                while (ci.moveToNext()) cols.add(ci.getString(1));
                ci.close();
                Cursor c = db.rawQuery("SELECT * FROM " + qt + " LIMIT " + ROW_LIMIT, null);
                while (c.moveToNext()) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < c.getColumnCount(); i++) {
                        if (i > 0) sb.append(" | ");
                        sb.append(c.isNull(i) ? "(null)" : c.getString(i));
                    }
                    rows.add(sb.toString());
                }
                c.close();
            });
            final String ferr = err;
            handler.post(() -> {
                tvCrumb.setText(currentDb + " ▸ " + currentTable
                        + " (" + rows.size() + " baris, maks " + ROW_LIMIT + ")");
                if (!cols.isEmpty()) {
                    tvHeader.setVisibility(View.VISIBLE);
                    tvHeader.setText(join(cols));
                } else {
                    tvHeader.setVisibility(View.GONE);
                }
                rv.setAdapter(new StrAdapter(rows, null));
                if (ferr != null) toast(ferr);
            });
        }).start();
    }

    private String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    /**
     * Copy read-only db target ke cache sendiri via su, buka, jalankan worker, tutup.
     * Mengembalikan pesan error atau null bila sukses.
     */
    private String readDb(String dbName, DbWorker worker) {
        File tmp = new File(getCacheDir(), "hi_ro_tmp.db");
        if (tmp.exists()) tmp.delete();
        String src = "/data/data/" + pkg + "/databases/" + dbName;
        SuHelper.Result cp = SuHelper.exec(
                "cat " + SuHelper.q(src) + " > " + SuHelper.q(tmp.getAbsolutePath()), 60);
        if (cp.code != 0 || !tmp.exists() || tmp.length() == 0) {
            return "Gagal menyalin database (root?)";
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(tmp.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            worker.run(db);
            return null;
        } catch (Exception e) {
            return "Gagal baca database: " + e.getMessage();
        } finally {
            if (db != null) {
                try { db.close(); } catch (Exception ignored) {}
            }
            tmp.delete();
        }
    }

    private interface DbWorker {
        void run(SQLiteDatabase db) throws Exception;
    }

    // ---------- adapter string generik ----------

    private static class StrAdapter extends RecyclerView.Adapter<StrAdapter.H> {
        interface Click { void onClick(int pos); }
        private final List<String> items;
        private final Click click;

        StrAdapter(List<String> items, Click click) {
            this.items = items;
            this.click = click;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_simple, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            h.tv.setText(items.get(pos));
            if (click != null) {
                h.itemView.setOnClickListener(v -> click.onClick(h.getAdapterPosition()));
            } else {
                h.itemView.setOnClickListener(null);
            }
        }

        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView tv;
            H(View v) {
                super(v);
                tv = v.findViewById(R.id.tvSimple);
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
