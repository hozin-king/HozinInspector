package com.hozinking.appinspector.ui;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pilih app target: daftar nama + ikon + package, search/filter,
 * toggle user/system app. Loading di background thread.
 * Hasil: extra "pkg" (package name) via setResult.
 */
public class AppPickerActivity extends AppCompatActivity {

    public static final String RESULT_PKG = "pkg";

    private RecyclerView rv;
    private EditText etSearch;
    private CheckBox cbSystem;
    private TextView tvCount;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;
    private String query = "";
    private boolean includeSystem = false;

    private static class App {
        String label, pkg;
        Drawable icon;
        boolean system;
    }

    private final List<App> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        rv = findViewById(R.id.rvApps);
        etSearch = findViewById(R.id.etAppSearch);
        cbSystem = findViewById(R.id.cbIncludeSystem);
        tvCount = findViewById(R.id.tvAppCount);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(new AppAdapter(new ArrayList<>(), this::pick));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                if (pendingFilter != null) handler.removeCallbacks(pendingFilter);
                pendingFilter = AppPickerActivity.this::applyFilter;
                handler.postDelayed(pendingFilter, 300);
            }
        });
        cbSystem.setOnCheckedChangeListener((b, checked) -> {
            includeSystem = checked;
            applyFilter();
        });

        loadApps();
    }

    private void pick(App app) {
        Intent r = new Intent();
        r.putExtra(RESULT_PKG, app.pkg);
        setResult(RESULT_OK, r);
        finish();
    }

    /** Query launcher apps + ikon di background thread (bisa lambat). */
    private void loadApps() {
        tvCount.setText("Memuat daftar aplikasi...");
        new Thread(() -> {
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
                final List<App> list = new ArrayList<>();
                String self = getPackageName();
                for (ResolveInfo ri : ris) {
                    if (ri.activityInfo == null) continue;
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(self)) continue;
                    App a = new App();
                    a.pkg = pkg;
                    try {
                        ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                        CharSequence label = pm.getApplicationLabel(ai);
                        a.label = label == null ? pkg : label.toString();
                        a.icon = pm.getApplicationIcon(ai);
                        a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                                && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                    } catch (Exception e) {
                        CharSequence label = ri.loadLabel(pm);
                        a.label = label == null ? pkg : label.toString();
                        a.icon = null;
                        a.system = false;
                    }
                    list.add(a);
                }
                Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
                handler.post(() -> {
                    all.clear();
                    all.addAll(list);
                    applyFilter();
                    if (list.isEmpty()) {
                        Toast.makeText(this, "Tidak ada aplikasi ditemukan",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (final Exception e) {
                handler.post(() -> {
                    tvCount.setText("Gagal memuat: " + e.getMessage());
                });
            }
        }).start();
    }

    private void applyFilter() {
        String q = query.toLowerCase().trim();
        List<App> shown = new ArrayList<>();
        for (App a : all) {
            if (a.system && !includeSystem) continue;
            if (!q.isEmpty()
                    && !a.label.toLowerCase().contains(q)
                    && !a.pkg.toLowerCase().contains(q)) continue;
            shown.add(a);
        }
        rv.setAdapter(new AppAdapter(shown, this::pick));
        tvCount.setText(shown.size() + " aplikasi"
                + (includeSystem ? " (termasuk system)" : " (user)"));
    }

    // ---------- adapter ----------

    private static class AppAdapter extends RecyclerView.Adapter<AppAdapter.H> {
        interface Click { void onClick(App app); }
        private final List<App> items;
        private final Click click;

        AppAdapter(List<App> items, Click click) {
            this.items = items;
            this.click = click;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_app, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            App a = items.get(pos);
            h.name.setText(a.label);
            h.pkg.setText(a.pkg + (a.system ? "  [system]" : ""));
            if (a.icon != null) h.icon.setImageDrawable(a.icon);
            else h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            h.itemView.setOnClickListener(v -> click.onClick(items.get(h.getAdapterPosition())));
        }

        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name, pkg;
            H(View v) {
                super(v);
                icon = v.findViewById(R.id.ivAppIcon);
                name = v.findViewById(R.id.tvAppName);
                pkg = v.findViewById(R.id.tvAppPkg);
            }
        }
    }
}
