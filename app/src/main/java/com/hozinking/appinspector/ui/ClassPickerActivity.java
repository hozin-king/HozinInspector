package com.hozinking.appinspector.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daftar SEMUA class dari APK target (parse dex) + checkbox pilih yang di-hook.
 * Tap nama class (bukan checkbox) -> lihat daftar method-nya.
 * Class bisa puluhan ribu: filter teks + RecyclerView, tampilan dibatasi 5000 baris.
 */
public class ClassPickerActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_SELECTED = "selected";
    public static final String RESULT_SELECTED = "result_selected";
    private static final int MAX_ROWS = 5000;

    private RecyclerView rv;
    private EditText etSearch;
    private TextView tvCount;
    private ClassAdapter adapter;
    private final List<String> all = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private String pkg = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;
    private String currentQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_class_picker);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        ArrayList<String> pre = getIntent().getStringArrayListExtra(EXTRA_SELECTED);
        if (pre != null) selected.addAll(pre);

        rv = findViewById(R.id.rvClasses);
        etSearch = findViewById(R.id.etSearch);
        tvCount = findViewById(R.id.tvClassCount);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ClassAdapter(new ArrayList<>(), selected, new ClassAdapter.Listener() {
            @Override
            public void onToggle(String cls, boolean checked) {
                if (checked) selected.add(cls);
                else selected.remove(cls);
                updateTitle();
            }

            @Override
            public void onInfo(String cls) {
                Intent i = new Intent(ClassPickerActivity.this, MethodListActivity.class);
                i.putExtra(MethodListActivity.EXTRA_PKG, pkg);
                i.putExtra(MethodListActivity.EXTRA_CLASS, cls);
                startActivity(i);
            }
        });
        rv.setAdapter(adapter);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                currentQuery = s.toString();
                if (pendingFilter != null) handler.removeCallbacks(pendingFilter);
                pendingFilter = () -> applyFilter(currentQuery);
                handler.postDelayed(pendingFilter, 300);
            }
        });

        Button btnSave = findViewById(R.id.btnSaveClasses);
        btnSave.setOnClickListener(v -> {
            Intent r = new Intent();
            r.putStringArrayListExtra(RESULT_SELECTED, new ArrayList<>(selected));
            setResult(RESULT_OK, r);
            finish();
        });

        updateTitle();
        loadClasses();
    }

    private void updateTitle() {
        tvCount.setText(selected.size() + " class dipilih"
                + (all.isEmpty() ? "" : " • total " + all.size() + " class"));
    }

    private void loadClasses() {
        tvCount.setText("Memuat daftar class...");
        new Thread(() -> {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                final List<String> list = DexParser.listClasses(ai.sourceDir);
                handler.post(() -> {
                    all.clear();
                    all.addAll(list);
                    applyFilter(currentQuery);
                    updateTitle();
                    if (list.isEmpty()) {
                        Toast.makeText(this, "Tidak ada class terbaca dari APK",
                                Toast.LENGTH_LONG).show();
                    }
                });
            } catch (final Exception e) {
                handler.post(() -> {
                    tvCount.setText("Gagal: " + e.getMessage());
                    Toast.makeText(this, "Gagal parse dex: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void applyFilter(final String q) {
        new Thread(() -> {
            final String query = q.trim().toLowerCase();
            final List<String> f = new ArrayList<>();
            for (String c : all) {
                if (query.isEmpty() || c.toLowerCase().contains(query)) {
                    f.add(c);
                    if (f.size() >= MAX_ROWS) break;
                }
            }
            final boolean capped = f.size() >= MAX_ROWS && all.size() > MAX_ROWS;
            handler.post(() -> {
                adapter.setData(f);
                tvCount.setText(selected.size() + " class dipilih • menampilkan "
                        + f.size() + " dari " + all.size() + " class"
                        + (capped ? " (dibatasi)" : "")
                        + (query.isEmpty() ? "" : " • filter: \"" + q.trim() + "\""));
            });
        }).start();
    }
}
