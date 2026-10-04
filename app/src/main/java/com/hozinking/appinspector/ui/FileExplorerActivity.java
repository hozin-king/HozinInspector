package com.hozinking.appinspector.ui;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * File Explorer (ROOT): jelajahi /data/data/&lt;pkg&gt;/ secara read-only.
 * Daftar direktori/file + ukuran + permission; tap file teks kecil (&lt;200KB)
 * untuk lihat isi; tap file lain untuk info. Navigasi naik/turun direktori.
 */
public class FileExplorerActivity extends BaseActivity {

    private static final long TEXT_MAX = 200 * 1024;

    private String pkg = "";
    private String rootDir = "";
    private TextView tvTitle, tvPath;
    private RecyclerView rv;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<String> dirStack = new ArrayList<>();

    private static class Entry {
        boolean dir;
        String name, perms;
        long size;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_explorer);
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) pkg = "";
        rootDir = "/data/data/" + pkg;

        tvTitle = findViewById(R.id.tvFeTitle);
        tvPath = findViewById(R.id.tvFePath);
        rv = findViewById(R.id.rvFe);
        rv.setLayoutManager(new LinearLayoutManager(this));
        tvTitle.setText("File Explorer — " + pkg);

        dirStack.add(rootDir);
        loadDir(rootDir);
    }

    @Override
    public void onBackPressed() {
        if (dirStack.size() > 1) {
            dirStack.remove(dirStack.size() - 1);
            loadDir(dirStack.get(dirStack.size() - 1));
        } else {
            super.onBackPressed();
        }
    }

    private void loadDir(final String dir) {
        tvPath.setText(dir + "\nMemuat...");
        new Thread(() -> {
            SuHelper.Result r = SuHelper.exec("ls -la " + SuHelper.q(dir));
            final List<Entry> entries = new ArrayList<>();
            if (r.code == 0) {
                for (String line : r.out.split("\n")) {
                    Entry e = parseLsLine(line);
                    if (e != null && !e.name.equals(".") && !e.name.equals("..")) {
                        entries.add(e);
                    }
                }
                Collections.sort(entries, (a, b) -> {
                    if (a.dir != b.dir) return a.dir ? -1 : 1;
                    return a.name.compareToIgnoreCase(b.name);
                });
            }
            final boolean ok = r.code == 0;
            handler.post(() -> {
                tvPath.setText(dir);
                rv.setAdapter(new FileAdapter(entries, pos ->
                        onEntryTap(dir, entries.get(pos))));
                if (!ok) toast("Gagal baca direktori (root?)");
            });
        }).start();
    }

    /** Parse satu baris "ls -la". Format umum toybox:
     *  drwxrwx--x 4 u0_a1 u0_a1 4096 2026-10-04 18:00 nama */
    private Entry parseLsLine(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("total")) return null;
        String[] f = t.split("\\s+");
        if (f.length < 8) return null;
        char kind = f[0].charAt(0);
        if (kind != 'd' && kind != '-' && kind != 'l') return null;
        Entry e = new Entry();
        e.dir = kind == 'd';
        e.perms = f[0];
        try {
            e.size = Long.parseLong(f[4]);
        } catch (NumberFormatException ex) {
            e.size = 0;
        }
        // nama mulai setelah kolom tanggal/waktu: "2026-10-04 18:00" -> indeks 8,
        // fallback indeks 7 bila format beda
        int nameIdx = 8;
        if (f.length > 6 && f[5].contains("-") && f[6].contains(":")) {
            nameIdx = 7;
            if (f.length > 7 && f[7].matches("\\d{2}:\\d{2}(:\\d{2})?")) nameIdx = 8;
            else if (f.length == 7) nameIdx = 7;
        } else if (f.length == 7) {
            nameIdx = 6;
        }
        StringBuilder nb = new StringBuilder();
        for (int i = nameIdx; i < f.length; i++) {
            if (i > nameIdx) nb.append(' ');
            nb.append(f[i]);
        }
        e.name = nb.toString();
        // symlink: "nama -> target"
        int arrow = e.name.indexOf(" -> ");
        if (kind == 'l' && arrow > 0) e.name = e.name.substring(0, arrow);
        if (e.name.isEmpty()) return null;
        return e;
    }

    private void onEntryTap(String dir, Entry e) {
        String full = dir.endsWith("/") ? dir + e.name : dir + "/" + e.name;
        if (e.dir) {
            dirStack.add(full);
            loadDir(full);
            return;
        }
        if (e.size > TEXT_MAX) {
            showInfo(e, full, "File terlalu besar untuk pratinjau teks (>200KB).");
            return;
        }
        // coba baca sebagai teks
        new Thread(() -> {
            SuHelper.Result r = SuHelper.exec("cat " + SuHelper.q(full), 15);
            final boolean isText = r.code == 0 && r.out.indexOf('\0') < 0;
            final String content = r.out;
            handler.post(() -> {
                if (isText) showText(e.name, content);
                else showInfo(e, full, "Bukan file teks (biner).");
            });
        }).start();
    }

    private void showText(String name, String content) {
        TextView tv = new TextView(this);
        tv.setText(content.isEmpty() ? "(file kosong)" : content);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12);
        tv.setTextIsSelectable(true);
        tv.setPadding(24, 24, 24, 24);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setView(sv)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private void showInfo(Entry e, String full, String note) {
        String info = "Path: " + full
                + "\nUkuran: " + humanSize(e.size)
                + "\nPermission: " + e.perms
                + "\n\n" + note;
        new AlertDialog.Builder(this)
                .setTitle(e.name)
                .setMessage(info)
                .setPositiveButton("Tutup", null)
                .show();
    }

    private String humanSize(long s) {
        if (s < 1024) return s + " B";
        if (s < 1024 * 1024) return String.format("%.1f KB", s / 1024.0);
        return String.format("%.1f MB", s / 1048576.0);
    }

    // ---------- adapter ----------

    private static class FileAdapter extends RecyclerView.Adapter<FileAdapter.H> {
        interface Click { void onClick(int pos); }
        private final List<Entry> items;
        private final Click click;

        FileAdapter(List<Entry> items, Click click) {
            this.items = items;
            this.click = click;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_file, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            Entry e = items.get(pos);
            h.icon.setText(e.dir ? "📁" : "📄");
            h.name.setText(e.name);
            h.meta.setText((e.dir ? "dir" : humanStatic(e.size)) + "  " + e.perms);
            h.itemView.setOnClickListener(v -> click.onClick(h.getAdapterPosition()));
        }

        private static String humanStatic(long s) {
            if (s < 1024) return s + " B";
            if (s < 1024 * 1024) return String.format("%.1f KB", s / 1024.0);
            return String.format("%.1f MB", s / 1048576.0);
        }

        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView icon, name, meta;
            H(View v) {
                super(v);
                icon = v.findViewById(R.id.tvFileIcon);
                name = v.findViewById(R.id.tvFileName);
                meta = v.findViewById(R.id.tvFileMeta);
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
