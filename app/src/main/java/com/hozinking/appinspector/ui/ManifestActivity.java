package com.hozinking.appinspector.ui;

import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.appinspector.R;

/** Menampilkan info manifest app target via PackageManager. */
public class ManifestActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manifest);
        TextView tv = findViewById(R.id.tvManifest);
        tv.setText(buildInfo(getIntent().getStringExtra("pkg")));
    }

    private String buildInfo(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "Belum ada app target dipilih.";
        try {
            PackageManager pm = getPackageManager();
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                    | PackageManager.GET_PERMISSIONS;
            PackageInfo pi = pm.getPackageInfo(pkg, flags);
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            StringBuilder sb = new StringBuilder();
            sb.append("Package: ").append(pkg).append('\n');
            sb.append("Label: ").append(pm.getApplicationLabel(ai)).append('\n');
            sb.append("Version: ").append(pi.versionName)
                    .append(" (").append(pi.versionCode).append(")\n\n");
            appendSection(sb, "ACTIVITIES", pi.activities);
            appendSection(sb, "SERVICES", pi.services);
            appendSection(sb, "RECEIVERS", pi.receivers);
            appendSection(sb, "PROVIDERS", pi.providers);
            sb.append("PERMISSIONS:\n");
            if (pi.requestedPermissions != null) {
                for (String p : pi.requestedPermissions) sb.append("  ").append(p).append('\n');
            } else {
                sb.append("  (tidak ada)\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Gagal baca package " + pkg + ":\n" + e.getMessage();
        }
    }

    private void appendSection(StringBuilder sb, String title, ComponentInfo[] arr) {
        sb.append(title).append(" (").append(arr == null ? 0 : arr.length).append("):\n");
        if (arr != null) {
            for (ComponentInfo c : arr) sb.append("  ").append(c.name).append('\n');
        }
        sb.append('\n');
    }
}
