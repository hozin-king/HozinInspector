package com.hozinking.appinspector.ui;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.appinspector.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Layar utama Hozin Tools (glassmorphism UI).
 * - State UI (target, checkbox, filter, class) disimpan di SharedPreferences tiap berubah
 *   dan di-restore saat dibuka (tidak ke-reset pas app di-close).
 * - SEMUA operasi berat (query app, tulis config via su, grant) di background thread.
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREFS = "hozininspector";
    private static final int REQ_PICK_CLASS = 1001;
    private static final int REQ_PICK_APP = 1002;
    private static final int WARN_HOOK_LIMIT = 20;

    private TextView tvTarget, tvPrefixes, tvStatus, tvHookClasses, tvHookWarn,
            tvGrantStatus, tvModuleStatus, tvClassSummary;
    private EditText etPrefix;
    private CheckBox cbMethod, cbUrl, cbUi, cbPref, cbDump, cbRate, cbLogArgs;
    private Button btnSave;
    private String targetPackage = "";
    private final List<String> prefixes = new ArrayList<>();
    private final List<String> hookClasses = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTarget = findViewById(R.id.tvTarget);
        tvPrefixes = findViewById(R.id.tvPrefixes);
        tvStatus = findViewById(R.id.tvStatus);
        tvHookClasses = findViewById(R.id.tvHookClasses);
        tvHookWarn = findViewById(R.id.tvHookWarn);
        tvGrantStatus = findViewById(R.id.tvGrantStatus);
        tvModuleStatus = findViewById(R.id.tvModuleStatus);
        tvClassSummary = findViewById(R.id.tvClassSummary);
        etPrefix = findViewById(R.id.etPrefix);
        cbMethod = findViewById(R.id.cbMethod);
        cbUrl = findViewById(R.id.cbUrl);
        cbUi = findViewById(R.id.cbUi);
        cbPref = findViewById(R.id.cbPref);
        cbDump = findViewById(R.id.cbDump);
        cbRate = findViewById(R.id.cbRate);
        cbLogArgs = findViewById(R.id.cbLogArgs);
        btnSave = findViewById(R.id.btnSave);

        restoreUiState();
        renderPrefixes();
        renderHookClasses();
        if (!targetPackage.isEmpty()) updateClassSummary();

        // setiap perubahan checkbox langsung disimpan (anti-reset)
        CheckBox[] boxes = {cbMethod, cbUrl, cbUi, cbPref, cbDump, cbRate, cbLogArgs};
        for (CheckBox cb : boxes) {
            cb.setOnCheckedChangeListener((b, checked) -> persistUiState());
        }

        findViewById(R.id.btnPick).setOnClickListener(v ->
                startActivityForResult(new Intent(this, AppPickerActivity.class), REQ_PICK_APP));
        findViewById(R.id.btnAdd).setOnClickListener(v -> addPrefix());
        findViewById(R.id.btnDelLast).setOnClickListener(v -> {
            if (!prefixes.isEmpty()) prefixes.remove(prefixes.size() - 1);
            renderPrefixes();
            persistUiState();
        });
        findViewById(R.id.btnDelAll).setOnClickListener(v -> {
            prefixes.clear();
            renderPrefixes();
            persistUiState();
        });
        findViewById(R.id.btnPickClass).setOnClickListener(v -> pickClass());
        btnSave.setOnClickListener(v -> saveConfig());
        findViewById(R.id.btnGrant).setOnClickListener(v -> grantLogs());
        findViewById(R.id.cardLog).setOnClickListener(v ->
                startActivity(new Intent(this, LogViewerActivity.class)));
        findViewById(R.id.cardManifest).setOnClickListener(v -> {
            Intent i = new Intent(this, ManifestActivity.class);
            i.putExtra("pkg", targetPackage);
            startActivity(i);
        });
        findViewById(R.id.cardWebScraper).setOnClickListener(v ->
                startActivity(new Intent(this, WebScraperActivity.class)));
        findViewById(R.id.cardOsint).setOnClickListener(v ->
                startActivity(new Intent(this, UsernameSearchActivity.class)));
        findViewById(R.id.cardSaved).setOnClickListener(v ->
                startActivity(new Intent(this, SavedActivity.class)));
        findViewById(R.id.cardRootTools).setOnClickListener(v -> {
            if (targetPackage.isEmpty()) {
                toast("Pilih app target dulu");
                return;
            }
            Intent i = new Intent(this, RootToolsActivity.class);
            i.putExtra(RootToolsActivity.EXTRA_PKG, targetPackage);
            startActivity(i);
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGrantStatus();
        updateModuleStatus();
        updateSavedCount();
    }

    /** Tampilkan jumlah artikel offline di kartu Tersimpan. */
    private void updateSavedCount() {
        TextView tv = findViewById(R.id.tvSavedCount);
        if (tv == null) return;
        new Thread(() -> {
            int n = 0;
            try {
                n = SavedArticleDb.getInstance(this).count();
            } catch (Exception ignored) {
            }
            final int count = n;
            runOnUiThread(() ->
                    tv.setText(count + " artikel offline"));
        }).start();
    }

    // ---------- state persistence (BUG 3) ----------

    private void persistUiState() {
        try {
            JSONArray pre = new JSONArray();
            for (String p : prefixes) pre.put(p);
            JSONArray hooks = new JSONArray();
            for (String h : hookClasses) hooks.put(h);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("target", targetPackage)
                    .putBoolean("cb_method", cbMethod.isChecked())
                    .putBoolean("cb_url", cbUrl.isChecked())
                    .putBoolean("cb_ui", cbUi.isChecked())
                    .putBoolean("cb_pref", cbPref.isChecked())
                    .putBoolean("cb_dump", cbDump.isChecked())
                    .putBoolean("cb_rate", cbRate.isChecked())
                    .putBoolean("cb_logargs", cbLogArgs.isChecked())
                    .putString("prefixes", pre.toString())
                    .putString("hookClasses", hooks.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void restoreUiState() {
        try {
            SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
            targetPackage = sp.getString("target", "");
            if (!targetPackage.isEmpty()) tvTarget.setText(targetPackage);
            cbMethod.setChecked(sp.getBoolean("cb_method", false));
            cbUrl.setChecked(sp.getBoolean("cb_url", false));
            cbUi.setChecked(sp.getBoolean("cb_ui", false));
            cbPref.setChecked(sp.getBoolean("cb_pref", false));
            cbDump.setChecked(sp.getBoolean("cb_dump", false));
            cbRate.setChecked(sp.getBoolean("cb_rate", true));
            cbLogArgs.setChecked(sp.getBoolean("cb_logargs", true));
            prefixes.clear();
            JSONArray pre = new JSONArray(sp.getString("prefixes", "[]"));
            for (int i = 0; i < pre.length(); i++) {
                String s = pre.optString(i, "");
                if (!s.isEmpty()) prefixes.add(s);
            }
            hookClasses.clear();
            JSONArray hooks = new JSONArray(sp.getString("hookClasses", "[]"));
            for (int i = 0; i < hooks.length(); i++) {
                String s = hooks.optString(i, "");
                if (!s.isEmpty()) hookClasses.add(s);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- status indicators (BUG 4 + modul aktif) ----------

    private void updateGrantStatus() {
        boolean granted = checkSelfPermission(Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        tvGrantStatus.setText(granted ? "READ_LOGS: GRANTED" : "READ_LOGS: BELUM");
        tvGrantStatus.setBackgroundColor(granted ? 0xFF4CAF50 : 0xFFF44336);
    }

    private void updateModuleStatus() {
        boolean active = false;
        try {
            File f = new File(getFilesDir(), "xposed_active.flag");
            if (f.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(f));
                long ts = Long.parseLong(br.readLine().trim());
                br.close();
                active = System.currentTimeMillis() - ts < 15 * 60 * 1000;
            }
        } catch (Exception ignored) {
        }
        tvModuleStatus.setText(active ? "MODUL: AKTIF" : "MODUL: BELUM AKTIF");
        tvModuleStatus.setBackgroundColor(active ? 0xFF4CAF50 : 0xFF616161);
    }

    // ---------- pilih app via AppPickerActivity ----------

    /**
     * Setelah app target dipilih: parse dex dari sourceDir-nya di background
     * thread dan tampilkan ringkasan jumlah class di layar utama.
     */
    private void updateClassSummary() {
        tvClassSummary.setText("Menghitung class dari APK...");
        final String target = targetPackage;
        new Thread(() -> {
            int count = -1;
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(target, 0);
                count = DexParser.listClasses(ai.sourceDir).size();
            } catch (Exception ignored) {
            }
            final int c = count;
            handler.post(() -> {
                if (c >= 0) {
                    tvClassSummary.setText(String.format("%,d", c).replace(',', '.')
                            + " class tersedia — tap PILIH CLASS untuk memilih yang di-hook");
                } else {
                    tvClassSummary.setText("Gagal baca class dari APK");
                }
            });
        }).start();
    }

    // ---------- pilih class ----------

    private void pickClass() {
        if (targetPackage.isEmpty()) {
            toast("Pilih app target dulu");
            return;
        }
        Intent i = new Intent(this, ClassPickerActivity.class);
        i.putExtra(ClassPickerActivity.EXTRA_PKG, targetPackage);
        i.putStringArrayListExtra(ClassPickerActivity.EXTRA_SELECTED,
                new ArrayList<>(hookClasses));
        startActivityForResult(i, REQ_PICK_CLASS);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_APP && resultCode == RESULT_OK && data != null) {
            String picked = data.getStringExtra(AppPickerActivity.RESULT_PKG);
            if (picked != null && !picked.isEmpty()) {
                targetPackage = picked;
                tvTarget.setText(targetPackage);
                persistUiState();
                updateClassSummary();
            }
        }
        if (requestCode == REQ_PICK_CLASS && resultCode == RESULT_OK && data != null) {
            ArrayList<String> sel =
                    data.getStringArrayListExtra(ClassPickerActivity.RESULT_SELECTED);
            hookClasses.clear();
            if (sel != null) hookClasses.addAll(sel);
            renderHookClasses();
            persistUiState();
            if (hookClasses.size() > WARN_HOOK_LIMIT) {
                toast("Peringatan: " + hookClasses.size()
                        + " class — kebanyakan hook bisa bikin target lag/FC/ANR!");
            }
        }
    }

    private void renderHookClasses() {
        tvHookClasses.setText(hookClasses.size() + " class dipilih"
                + (hookClasses.isEmpty() ? "" : " — tap PILIH CLASS untuk edit"));
        tvHookWarn.setVisibility(
                hookClasses.size() > WARN_HOOK_LIMIT ? View.VISIBLE : View.GONE);
    }

    // ---------- prefix ----------

    private void addPrefix() {
        String p = etPrefix.getText().toString().trim();
        if (p.isEmpty()) {
            toast("Isi prefix dulu");
            return;
        }
        if (!prefixes.contains(p)) prefixes.add(p);
        etPrefix.setText("");
        renderPrefixes();
        persistUiState();
    }

    private void renderPrefixes() {
        if (prefixes.isEmpty()) {
            tvPrefixes.setText("(kosong)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : prefixes) sb.append("• ").append(p).append('\n');
        tvPrefixes.setText(sb.toString().trim());
    }

    // ---------- simpan config (background thread!) ----------

    private void saveConfig() {
        if (targetPackage.isEmpty()) {
            toast("Pilih app target dulu");
            return;
        }
        persistUiState();
        btnSave.setEnabled(false);
        tvStatus.setText("Menyimpan config...");
        final boolean method = cbMethod.isChecked();
        final boolean url = cbUrl.isChecked();
        final boolean ui = cbUi.isChecked();
        final boolean pref = cbPref.isChecked();
        final boolean dump = cbDump.isChecked();
        final boolean rate = cbRate.isChecked();
        final boolean logArgs = cbLogArgs.isChecked();
        final List<String> preCopy = new ArrayList<>(prefixes);
        final List<String> hookCopy = new ArrayList<>(hookClasses);
        final String target = targetPackage;

        new Thread(() -> {
            String status;
            try {
                JSONObject o = new JSONObject();
                o.put("targetPackage", target);
                o.put("methodTrace", method);
                JSONArray arr = new JSONArray();
                for (String p : preCopy) arr.put(p);
                o.put("traceClasses", arr);
                JSONArray hooks = new JSONArray();
                for (String h : hookCopy) hooks.put(h);
                o.put("hookClasses", hooks);
                o.put("rateLimit", rate);
                o.put("logArgs", logArgs);
                o.put("urlTrack", url);
                o.put("uiTrace", ui);
                o.put("prefTrace", pref);
                o.put("dumpUi", dump);
                String json = o.toString(2);

                boolean okSd = false;
                try {
                    File dir = new File("/sdcard/HozinInspector");
                    dir.mkdirs();
                    Files.write(new File(dir, "config.json").toPath(),
                            json.getBytes(StandardCharsets.UTF_8));
                    okSd = true;
                } catch (Exception ignored) {
                }

                boolean okRoot = writeViaSu(json);
                status = "Config tersimpan.\n"
                        + "- /data/local/tmp: " + (okRoot ? "OK (root)" : "GAGAL — butuh root")
                        + "\n- /sdcard: " + (okSd ? "OK" : "gagal (wajar di Android 10+)")
                        + "\n\nLangkah lanjut:\n1. Aktifkan modul di LSPosed Manager\n"
                        + "2. Centang scope = " + target
                        + "\n3. Force-stop & buka ulang app target";
            } catch (Exception e) {
                status = "Gagal simpan: " + e.getMessage();
            }
            final String msg = status;
            handler.post(() -> {
                btnSave.setEnabled(true);
                tvStatus.setText(msg);
            });
        }).start();
    }

    private boolean writeViaSu(String json) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "mkdir -p /data/local/tmp/HozinInspector"
                            + " && cat > /data/local/tmp/HozinInspector/config.json"
                            + " && chmod 644 /data/local/tmp/HozinInspector/config.json"});
            try {
                java.io.OutputStream os = p.getOutputStream();
                os.write(json.getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.close();
            } catch (Exception ignored) {
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- grant (background thread!) ----------

    private void grantLogs() {
        tvStatus.setText("Meminta akses root...");
        new Thread(() -> {
            final boolean ok = runSu("pm grant " + getPackageName()
                    + " android.permission.READ_LOGS");
            handler.post(() -> {
                updateGrantStatus();
                tvStatus.setText(ok
                        ? "READ_LOGS granted. Sekarang buka Lihat Log."
                        : "Gagal — pastikan HP sudah root dan akses root diberikan ke aplikasi ini.");
            });
        }).start();
    }

    private boolean runSu(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
