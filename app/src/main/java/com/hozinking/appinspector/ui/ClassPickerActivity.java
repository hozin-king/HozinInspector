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

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Daftar SEMUA class dari APK target (parse dex), dikelompokkan per outer class:
 * inner class ($1, $2, ...) gabung di bawah outer-nya.
 * Tap header = expand/collapse, tahan nama = lihat semua method grup,
 * centang header = hook semua member.
 */
public class ClassPickerActivity extends BaseActivity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_SELECTED = "selected";
    public static final String RESULT_SELECTED = "result_selected";
    private static final int MAX_ROWS = 5000;

    private RecyclerView rv;
    private EditText etSearch;
    private TextView tvCount;
    private ClassAdapter adapter;
    private final List<String> all = new ArrayList<>();
    private final List<ClassAdapter.Group> groups = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();
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
        adapter = new ClassAdapter(selected, new ClassAdapter.Listener() {
            @Override
            public void onToggle(String cls, boolean checked) {
                if (checked) selected.add(cls);
                else selected.remove(cls);
                adapter.notifyDataSetChanged(); // refresh state checkbox header grup
                updateTitle();
            }

            @Override
            public void onToggleGroup(ClassAdapter.Group g, boolean checked) {
                if (checked) selected.addAll(g.members);
                else selected.removeAll(g.members);
                // refresh tampilan checkbox header
                adapter.notifyDataSetChanged();
                updateTitle();
            }

            @Override
            public void onInfo(List<String> classes) {
                Intent i = new Intent(ClassPickerActivity.this, MethodListActivity.class);
                i.putExtra(MethodListActivity.EXTRA_PKG, pkg);
                i.putStringArrayListExtra(MethodListActivity.EXTRA_CLASSES,
                        new ArrayList<>(classes));
                startActivity(i);
            }

            @Override
            public void onExpand(ClassAdapter.Group g) {
                if (expanded.contains(g.outer)) expanded.remove(g.outer);
                else expanded.add(g.outer);
                applyFilter(currentQuery);
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
                + (groups.isEmpty() ? "" : " • " + groups.size() + " grup"));
    }

    private void loadClasses() {
        tvCount.setText("Memuat daftar class...");
        new Thread(() -> {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                final List<String> list = DexParser.listClasses(ai.sourceDir);
                final List<ClassAdapter.Group> gs = ClassAdapter.buildGroups(list);
                handler.post(() -> {
                    all.clear();
                    all.addAll(list);
                    groups.clear();
                    groups.addAll(gs);
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
            final List<ClassAdapter.Row> rows = new ArrayList<>();
            for (ClassAdapter.Group g : groups) {
                boolean outerHit = query.isEmpty() || g.outer.toLowerCase().contains(query);
                List<String> hitMembers = new ArrayList<>();
                for (String m : g.members) {
                    if (query.isEmpty() || m.toLowerCase().contains(query)) hitMembers.add(m);
                }
                if (!outerHit && hitMembers.isEmpty()) continue;
                boolean showMembers;
                List<String> shown;
                if (query.isEmpty()) {
                    showMembers = expanded.contains(g.outer);
                    shown = g.members;
                } else if (outerHit) {
                    showMembers = true;
                    shown = g.members;
                } else {
                    showMembers = true;
                    shown = hitMembers;
                }
                rows.add(new ClassAdapter.Row(true, showMembers, g, g.outer));
                if (showMembers) {
                    for (String m : shown) {
                        if (m.equals(g.outer)) continue; // outer sudah jadi header
                        rows.add(new ClassAdapter.Row(false, false, g, m));
                        if (rows.size() >= MAX_ROWS) break;
                    }
                }
                if (rows.size() >= MAX_ROWS) break;
            }
            final boolean capped = rows.size() >= MAX_ROWS;
            final int shownRows = rows.size();
            handler.post(() -> {
                adapter.setRows(rows);
                tvCount.setText(selected.size() + " class dipilih • menampilkan "
                        + shownRows + " baris dari " + groups.size() + " grup"
                        + (capped ? " (dibatasi)" : "")
                        + (query.isEmpty() ? "" : " • filter: \"" + q.trim() + "\""));
            });
        }).start();
    }
}
