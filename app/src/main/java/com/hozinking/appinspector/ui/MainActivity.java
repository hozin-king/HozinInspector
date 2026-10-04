package com.hozinking.appinspector.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.hozinking.appinspector.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * Dashboard/launcher Hozin Tools (glassmorphism UI).
 * Header ramping (ikon + 2 badge status) + grid menu: 1 tombol = 1 activity.
 * Seluruh config tracer dipindah ke TracerActivity. Logika fitur tidak diubah.
 */
public class MainActivity extends BaseActivity {

    private static final String PREFS = "hozininspector";

    private TextView tvGrantStatus, tvModuleStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvGrantStatus = findViewById(R.id.tvGrantStatus);
        tvModuleStatus = findViewById(R.id.tvModuleStatus);

        // Tap badge READ_LOGS di header = grant via root
        tvGrantStatus.setOnClickListener(v -> grantLogs());

        findViewById(R.id.cardTracer).setOnClickListener(v ->
                startActivity(new Intent(this, TracerActivity.class)));
        findViewById(R.id.cardLog).setOnClickListener(v ->
                startActivity(new Intent(this, LogViewerActivity.class)));
        findViewById(R.id.cardManifest).setOnClickListener(v -> {
            Intent i = new Intent(this, ManifestActivity.class);
            i.putExtra("pkg", getTargetPackage());
            startActivity(i);
        });
        findViewById(R.id.cardRootTools).setOnClickListener(v -> {
            String target = getTargetPackage();
            if (target.isEmpty()) {
                toast("Pilih app target dulu (di menu Tracer)");
                return;
            }
            Intent i = new Intent(this, RootToolsActivity.class);
            i.putExtra(RootToolsActivity.EXTRA_PKG, target);
            startActivity(i);
        });
        findViewById(R.id.cardWebScraper).setOnClickListener(v ->
                startActivity(new Intent(this, WebScraperActivity.class)));
        findViewById(R.id.cardOsint).setOnClickListener(v ->
                startActivity(new Intent(this, UsernameSearchActivity.class)));
        findViewById(R.id.cardSaved).setOnClickListener(v ->
                startActivity(new Intent(this, SavedActivity.class)));
        findViewById(R.id.cardSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateGrantStatus();
        updateModuleStatus();
        updateSavedCount();
    }

    /** Target app dibaca dari pref yang sama dengan TracerActivity. */
    private String getTargetPackage() {
        try {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("target", "");
        } catch (Exception e) {
            return "";
        }
    }

    private void updateGrantStatus() {
        boolean granted = checkSelfPermission(Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        tvGrantStatus.setText(granted ? "READ_LOGS: GRANTED" : "READ_LOGS: BELUM");
        tvGrantStatus.setBackgroundResource(
                granted ? ThemeHelper.pillOk(this) : ThemeHelper.pillBad(this));
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
        tvModuleStatus.setBackgroundResource(
                active ? ThemeHelper.pillOk(this) : ThemeHelper.pillNeutral(this));
    }

    /** Tampilkan jumlah artikel offline di tombol Tersimpan. */
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

    // ---------- grant READ_LOGS via root (background thread) ----------

    private void grantLogs() {
        toast("Meminta akses root...");
        new Thread(() -> {
            final boolean ok = runSu("pm grant " + getPackageName()
                    + " android.permission.READ_LOGS");
            runOnUiThread(() -> {
                updateGrantStatus();
                toast(ok ? "READ_LOGS granted."
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
