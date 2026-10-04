package com.hozinking.appinspector.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;


import com.hozinking.appinspector.R;

/**
 * Entry point fitur ROOT TOOLS — inspeksi mendalam app target memakai akses root.
 * Semua fitur read-only terhadap /data/data app target.
 */
public class RootToolsActivity extends BaseActivity {

    public static final String EXTRA_PKG = "pkg";

    private String pkg = "";
    private TextView tvRootStatus, tvRootTarget;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_root_tools);

        pkg = getIntent().getStringExtra(EXTRA_PKG);
        if (pkg == null) pkg = "";

        tvRootStatus = findViewById(R.id.tvRootStatus);
        tvRootTarget = findViewById(R.id.tvRootTarget);
        tvRootTarget.setText("Target: " + (pkg.isEmpty() ? "(belum dipilih)" : pkg));

        setupCard(R.id.btnPrefViewer, PrefViewerActivity.class, "Pref Viewer");
        setupCard(R.id.btnDbViewer, DbViewerActivity.class, "Database Viewer");
        setupCard(R.id.btnFileExplorer, FileExplorerActivity.class, "File Explorer");
        setupCard(R.id.btnMaps, MapsActivity.class, "Memory Maps");

        checkRoot();
    }

    private void setupCard(int btnId, final Class<?> cls, final String name) {
        Button b = findViewById(btnId);
        b.setEnabled(false);
        b.setOnClickListener(v -> {
            if (pkg.isEmpty()) {
                Toast.makeText(this, "Pilih app target dulu di layar utama",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, cls);
            i.putExtra("pkg", pkg);
            startActivity(i);
        });
        b.setTag(name);
    }

    private void setButtonsEnabled(boolean on) {
        int[] ids = {R.id.btnPrefViewer, R.id.btnDbViewer,
                R.id.btnFileExplorer, R.id.btnMaps};
        for (int id : ids) findViewById(id).setEnabled(on);
    }

    private void checkRoot() {
        tvRootStatus.setText("ROOT: mengecek...");
        new Thread(() -> {
            final boolean ok = SuHelper.hasRoot();
            handler.post(() -> {
                tvRootStatus.setText(ok ? "ROOT: TERSEDIA ✅" : "ROOT: TIDAK TERSEDIA ❌");
                tvRootStatus.setBackgroundColor(ok ? 0xFF4CAF50 : 0xFFF44336);
                setButtonsEnabled(ok && !pkg.isEmpty());
                if (!ok) {
                    Toast.makeText(this,
                            "HP tidak root / akses root ditolak — fitur ROOT TOOLS butuh root",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
