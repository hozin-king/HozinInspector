package com.hozinking.appinspector.ui;

import android.os.Bundle;
import android.widget.TextView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.hozinking.appinspector.R;

/**
 * Pengaturan Hozin Tools.
 * - Tema kaca: Glossy (default) vs Matte/Dop. Tersimpan di SharedPreferences,
 *   langsung diterapkan ke layar ini dan ke semua layar saat dibuka.
 */
public class SettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialButtonToggleGroup group = findViewById(R.id.tgTheme);
        boolean matte = ThemeHelper.isMatte(this);
        group.check(matte ? R.id.btnThemeMatte : R.id.btnThemeGlossy);
        group.addOnButtonCheckedListener((g, id, checked) -> {
            if (!checked) return;
            boolean nowMatte = (id == R.id.btnThemeMatte);
            if (nowMatte != ThemeHelper.isMatte(this)) {
                ThemeHelper.setMatte(this, nowMatte);
                recreate();
            }
        });

        TextView tvVer = findViewById(R.id.tvAboutVer);
        try {
            tvVer.setText("Hozin Tools v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName
                    + " — UI glassmorphism");
        } catch (Exception ignored) {
        }
    }
}
