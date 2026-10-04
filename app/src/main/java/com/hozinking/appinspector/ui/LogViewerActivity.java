package com.hozinking.appinspector.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.hozinking.appinspector.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Log viewer per kategori: badge TAG berwarna + chip filter.
 * Baca logcat SELALU di background thread; UI pakai ring buffer maks 1000 baris.
 */
public class LogViewerActivity extends AppCompatActivity {

    private static final int MAX_UI_LINES = 1000;

    private RecyclerView rv;
    private TextView tvCount;
    private ChipGroup chipGroup;
    private LogAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Semua baris (sudah di-parse), dibatasi ring buffer. */
    private final Deque<LogAdapter.Line> all = new ArrayDeque<>();
    private LogAdapter.Cat filter = LogAdapter.Cat.ALL;
    private String searchQuery = "";
    private Runnable pendingSearch;
    private final List<Chip> chips = new ArrayList<>();

    private static final LogAdapter.Cat[] CATS = {
            LogAdapter.Cat.ALL, LogAdapter.Cat.METHOD, LogAdapter.Cat.URL,
            LogAdapter.Cat.UI, LogAdapter.Cat.PREF, LogAdapter.Cat.LAYOUT,
            LogAdapter.Cat.OTHER
    };
    private static final String[] LABELS = {
            "Semua", "Method", "URL", "UI", "Pref", "Layout", "Lainnya"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        rv = findViewById(R.id.rvLog);
        tvCount = findViewById(R.id.tvLogCount);
        chipGroup = findViewById(R.id.chipGroup);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LogAdapter();
        rv.setAdapter(adapter);

        for (int i = 0; i < CATS.length; i++) {
            Chip c = new Chip(this);
            c.setText(LABELS[i]);
            c.setCheckable(true);
            c.setChecked(i == 0);
            final LogAdapter.Cat cat = CATS[i];
            c.setOnCheckedChangeListener((btn, checked) -> {
                if (!checked) return;
                filter = cat;
                for (Chip o : chips) if (o != btn) o.setChecked(false);
                applyFilter();
            });
            chips.add(c);
            chipGroup.addView(c);
        }

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearLog());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyLog());

        EditText etSearch = findViewById(R.id.etLogSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
                pendingSearch = () -> {
                    searchQuery = etSearch.getText().toString();
                    applyFilter();
                };
                handler.postDelayed(pendingSearch, 300);
            }
        });
        refresh();
    }

    private void refresh() {
        tvCount.setText("Memuat log...");
        // JANGAN baca logcat di main thread — bisa ANR
        new Thread(() -> {
            final List<LogAdapter.Line> lines = readLog();
            handler.post(() -> {
                all.clear();
                for (LogAdapter.Line l : lines) {
                    all.addLast(l);
                    while (all.size() > MAX_UI_LINES) all.removeFirst();
                }
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        List<LogAdapter.Line> f = new ArrayList<>();
        String q = searchQuery.trim().toLowerCase();
        for (LogAdapter.Line l : all) {
            if (!LogAdapter.matchCat(l, filter)) continue;
            if (!q.isEmpty()) {
                String hay = (l.badge + " " + l.msg).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            f.add(l);
        }
        adapter.setData(f);
        rv.scrollToPosition(Math.max(0, f.size() - 1));
        tvCount.setText("Menampilkan " + f.size() + " / " + all.size()
                + " baris (maks " + MAX_UI_LINES + " di UI)"
                + (q.isEmpty() ? "" : " • cari: \"" + searchQuery.trim() + "\""));
    }

    private List<LogAdapter.Line> readLog() {
        List<LogAdapter.Line> lines = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "brief"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("[HozinInspector]")) {
                    lines.add(LogAdapter.parse(line));
                }
            }
            br.close();
        } catch (Exception e) {
            lines.add(new LogAdapter.Line(LogAdapter.Cat.OTHER, "ERR", 0xFFF44336,
                    "Gagal baca logcat: " + e.getMessage()));
        }
        // ambil 1000 terakhir saja (ring buffer)
        int from = Math.max(0, lines.size() - MAX_UI_LINES);
        return new ArrayList<>(lines.subList(from, lines.size()));
    }

    private void clearLog() {
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor() == 0;
            } catch (Exception ignored) {
            }
            if (!ok) {
                try {
                    ok = Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -c"})
                            .waitFor() == 0;
                } catch (Exception ignored) {
                }
            }
            final boolean res = ok;
            handler.post(() -> {
                Toast.makeText(this, res ? "Log dibersihkan" : "Gagal clear log",
                        Toast.LENGTH_SHORT).show();
                if (res) refresh();
            });
        }).start();
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("hozininspector", adapter.visibleText()));
            Toast.makeText(this, "Log dicopy", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal copy: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
