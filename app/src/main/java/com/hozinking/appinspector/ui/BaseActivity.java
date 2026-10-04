package com.hozinking.appinspector.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Base untuk semua Activity Hozin Tools: menerapkan tema kaca pilihan user
 * (Glossy/Matte) sebelum layout di-inflate. Tidak menyentuh logika fitur.
 */
public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
    }
}
