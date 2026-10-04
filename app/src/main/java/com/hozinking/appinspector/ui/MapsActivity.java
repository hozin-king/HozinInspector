package com.hozinking.appinspector.ui;

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory Maps (ROOT): baca /proc/&lt;pid&gt;/maps app target.
 * Tampilkan library .so yang di-load + base address + permission + path.
 * Ini menjawab kebutuhan "address" di level native (base address tiap .so).
 * Read-only.
 */
public class MapsActivity extends AppCompatActivity {

    private String pkg = "";
    private TextView tvTitle, tvInfo;
    private EditText etSearch;
    private CheckBox cbSoOnly;
    private RecyclerView rv;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;
    private String query = "";

    private static class Lib {
        String path, name;
        long base;
        String perms; // gabungan
        int segments;
    }

    private final List<Lib> all = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) pkg = "";

        tvTitle = findViewById(R.id.tvMapsTitle);
        tvInfo = findViewById(R.id.tvMapsInfo);
        etSearch = findViewById(R.id.etMapsSearch);
        cbSoOnly = findViewById(R.id.cbSoOnly);
        rv = findViewById(R.id.rvMaps);
        rv.setLayoutManager(new LinearLayoutManager(this));

        tvTitle.setText("Memory Maps — " + pkg);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                if (pendingFilter != null) handler.removeCallbacks(pendingFilter);
                pendingFilter = MapsActivity.this::applyFilter;
                handler.postDelayed(pendingFilter, 300);
            }
        });
        cbSoOnly.setOnCheckedChangeListener((b, c) -> applyFilter());

        loadMaps();
    }

    private void loadMaps() {
        tvInfo.setText("Mencari PID " + pkg + "...");
        new Thread(() -> {
            SuHelper.Result pidR = SuHelper.exec("pidof " + SuHelper.q(pkg), 10);
            String pid = pidR.out.trim().split("\\s+")[0].trim();
            if (pidR.code != 0 || pid.isEmpty() || !pid.matches("\\d+")) {
                handler.post(() -> {
                    tvInfo.setText("App target tidak sedang berjalan.\n"
                            + "Buka dulu app target, lalu refresh (tutup-buka layar ini).");
                    Toast.makeText(this, "PID tidak ditemukan", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            final String fpid = pid;
            SuHelper.Result mapsR = SuHelper.exec("cat /proc/" + fpid + "/maps", 30);
            if (mapsR.code != 0 || mapsR.out.isEmpty()) {
                handler.post(() -> {
                    tvInfo.setText("Gagal baca /proc/" + fpid + "/maps (root?)");
                });
                return;
            }
            parseMaps(mapsR.out);
            handler.post(() -> {
                tvInfo.setText("PID " + fpid + " • " + all.size()
                        + " path unik ter-mapping");
                applyFilter();
            });
        }).start();
    }

    /**
     * Parse baris maps: "start-end perms offset dev inode [path]"
     * Kelompokkan per path: base = alamat awal terendah, perms = gabungan.
     */
    private void parseMaps(String out) {
        Map<String, Lib> byPath = new LinkedHashMap<>();
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String[] f = t.split("\\s+");
            if (f.length < 5) continue;
            String[] range = f[0].split("-");
            if (range.length != 2) continue;
            long start;
            try {
                start = Long.parseLong(range[0], 16);
            } catch (NumberFormatException e) {
                continue;
            }
            String perms = f[1];
            String path = f.length > 5 ? joinFrom(f, 5) : "";
            if (path.isEmpty() || path.startsWith("[")) continue; // skip anonim/vdso/dll
            Lib lib = byPath.get(path);
            if (lib == null) {
                lib = new Lib();
                lib.path = path;
                int slash = path.lastIndexOf('/');
                lib.name = slash >= 0 ? path.substring(slash + 1) : path;
                lib.base = start;
                lib.perms = perms;
                lib.segments = 1;
                byPath.put(path, lib);
            } else {
                if (start < lib.base) lib.base = start;
                lib.perms = mergePerms(lib.perms, perms);
                lib.segments++;
            }
        }
        all.clear();
        all.addAll(byPath.values());
        // .so dulu, lalu abjad
        all.sort((a, b) -> {
            boolean aso = a.name.endsWith(".so"), bso = b.name.endsWith(".so");
            if (aso != bso) return aso ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    private String joinFrom(String[] f, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < f.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(f[i]);
        }
        return sb.toString();
    }

    private String mergePerms(String a, String b) {
        StringBuilder sb = new StringBuilder("----");
        for (int i = 0; i < 4 && i < a.length() && i < b.length(); i++) {
            char ca = a.charAt(i), cb = b.charAt(i);
            sb.setCharAt(i, ca != '-' ? ca : cb);
        }
        return sb.toString();
    }

    private void applyFilter() {
        String q = query.toLowerCase().trim();
        boolean soOnly = cbSoOnly.isChecked();
        List<Lib> shown = new ArrayList<>();
        for (Lib l : all) {
            if (soOnly && !l.name.endsWith(".so")) continue;
            if (!q.isEmpty() && !l.path.toLowerCase().contains(q)) continue;
            shown.add(l);
        }
        rv.setAdapter(new MapAdapter(shown));
        tvInfo.setText(tvInfo.getText().toString().split("\n")[0]
                + "\nMenampilkan " + shown.size() + " entri");
    }

    // ---------- adapter ----------

    private static class MapAdapter extends RecyclerView.Adapter<MapAdapter.H> {
        private final List<Lib> items;

        MapAdapter(List<Lib> items) { this.items = items; }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_map, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            Lib l = items.get(pos);
            h.name.setText(l.name);
            h.base.setText(String.format("base 0x%X • %s • %d segmen",
                    l.base, l.perms, l.segments));
            h.path.setText(l.path);
        }

        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView name, base, path;
            H(View v) {
                super(v);
                name = v.findViewById(R.id.tvMapName);
                base = v.findViewById(R.id.tvMapBase);
                path = v.findViewById(R.id.tvMapPath);
            }
        }
    }
}
