package com.hozinking.appinspector.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.appinspector.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Layar utama: pilih target, toggle fitur, tulis config.json, grant log, buka viewer. */
public class MainActivity extends AppCompatActivity {

    private TextView tvTarget, tvPrefixes, tvStatus;
    private EditText etPrefix;
    private CheckBox cbMethod, cbUrl, cbUi, cbPref, cbDump;
    private String targetPackage = "";
    private final List<String> prefixes = new ArrayList<>();
    private static final String PREFS = "hozininspector";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvTarget = findViewById(R.id.tvTarget);
        tvPrefixes = findViewById(R.id.tvPrefixes);
        tvStatus = findViewById(R.id.tvStatus);
        etPrefix = findViewById(R.id.etPrefix);
        cbMethod = findViewById(R.id.cbMethod);
        cbUrl = findViewById(R.id.cbUrl);
        cbUi = findViewById(R.id.cbUi);
        cbPref = findViewById(R.id.cbPref);
        cbDump = findViewById(R.id.cbDump);

        targetPackage = getSharedPreferences(PREFS, MODE_PRIVATE).getString("target", "");
        if (!targetPackage.isEmpty()) tvTarget.setText(targetPackage);

        findViewById(R.id.btnPick).setOnClickListener(v -> pickApp());
        findViewById(R.id.btnAdd).setOnClickListener(v -> addPrefix());
        findViewById(R.id.btnDelLast).setOnClickListener(v -> {
            if (!prefixes.isEmpty()) prefixes.remove(prefixes.size() - 1);
            renderPrefixes();
        });
        findViewById(R.id.btnDelAll).setOnClickListener(v -> {
            prefixes.clear();
            renderPrefixes();
        });
        findViewById(R.id.btnSave).setOnClickListener(v -> saveConfig());
        findViewById(R.id.btnGrant).setOnClickListener(v -> grantLogs());
        findViewById(R.id.btnLog).setOnClickListener(v ->
                startActivity(new Intent(this, LogViewerActivity.class)));
        findViewById(R.id.btnManifest).setOnClickListener(v -> {
            Intent i = new Intent(this, ManifestActivity.class);
            i.putExtra("pkg", targetPackage);
            startActivity(i);
        });
    }

    private void pickApp() {
        try {
            PackageManager pm = getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
            List<String[]> pairs = new ArrayList<>();
            for (ResolveInfo ri : ris) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                CharSequence label = ri.loadLabel(pm);
                pairs.add(new String[]{label == null ? pkg : label.toString(), pkg});
            }
            Collections.sort(pairs, (a, b) -> a[0].compareToIgnoreCase(b[0]));
            String[] items = new String[pairs.size()];
            for (int i = 0; i < pairs.size(); i++) {
                items[i] = pairs.get(i)[0] + "\n" + pairs.get(i)[1];
            }
            new AlertDialog.Builder(this)
                    .setTitle("Pilih app target")
                    .setItems(items, (d, which) -> {
                        targetPackage = pairs.get(which)[1];
                        tvTarget.setText(targetPackage);
                    })
                    .show();
        } catch (Exception e) {
            toast("Gagal: " + e.getMessage());
        }
    }

    private void addPrefix() {
        String p = etPrefix.getText().toString().trim();
        if (p.isEmpty()) {
            toast("Isi prefix dulu");
            return;
        }
        if (!prefixes.contains(p)) prefixes.add(p);
        etPrefix.setText("");
        renderPrefixes();
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

    private void saveConfig() {
        if (targetPackage.isEmpty()) {
            toast("Pilih app target dulu");
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("targetPackage", targetPackage);
            o.put("methodTrace", cbMethod.isChecked());
            JSONArray arr = new JSONArray();
            for (String p : prefixes) arr.put(p);
            o.put("traceClasses", arr);
            o.put("urlTrack", cbUrl.isChecked());
            o.put("uiTrace", cbUi.isChecked());
            o.put("prefTrace", cbPref.isChecked());
            o.put("dumpUi", cbDump.isChecked());
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
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("target", targetPackage).apply();

            tvStatus.setText("Config tersimpan.\n"
                    + "- /data/local/tmp: " + (okRoot ? "OK (root)" : "GAGAL — butuh root")
                    + "\n- /sdcard: " + (okSd ? "OK" : "gagal (wajar di Android 10+)")
                    + "\n\nLangkah lanjut:\n1. Aktifkan modul di LSPosed Manager\n"
                    + "2. Centang scope = " + targetPackage
                    + "\n3. Force-stop & buka ulang app target");
        } catch (Exception e) {
            tvStatus.setText("Gagal simpan: " + e.getMessage());
        }
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

    private void grantLogs() {
        boolean ok = runSu("pm grant " + getPackageName() + " android.permission.READ_LOGS");
        tvStatus.setText(ok
                ? "READ_LOGS granted. Sekarang buka Lihat Log."
                : "Gagal — pastikan HP sudah root dan akses root diberikan ke aplikasi ini.");
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
