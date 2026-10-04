package com.hozinking.appinspector.ui;

import android.app.Activity;
import android.content.Context;
import android.util.TypedValue;

import androidx.annotation.AttrRes;
import androidx.annotation.DrawableRes;

import com.hozinking.appinspector.R;

/**
 * Sistem tema kaca Hozin Tools: Glossy (default) vs Matte/Dop.
 * Pilihan tersimpan di SharedPreferences "hozininspector" key "ui_theme"
 * (state lama tetap terbaca — key ini baru dan tidak menabrak key lain).
 * Diterapkan via BaseActivity.onCreate() ke SEMUA layar.
 */
public final class ThemeHelper {

    public static final String PREFS = "hozininspector";
    public static final String KEY_THEME = "ui_theme";
    public static final String THEME_GLOSSY = "glossy";
    public static final String THEME_MATTE = "matte";

    private ThemeHelper() {
    }

    public static boolean isMatte(Context c) {
        return THEME_MATTE.equals(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_THEME, THEME_GLOSSY));
    }

    public static void setMatte(Context c, boolean matte) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_THEME, matte ? THEME_MATTE : THEME_GLOSSY)
                .apply();
    }

    /**
     * WAJIB dipanggil sebelum super.onCreate() agar tema pilihan user dipakai
     * saat layout di-inflate.
     */
    public static void applyTheme(Activity a) {
        a.setTheme(isMatte(a) ? R.style.Theme_HozinTools_Matte : R.style.Theme_HozinTools);
    }

    @DrawableRes
    public static int resolveDrawable(Context c, @AttrRes int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    /** Pill status HIJAU (granted / modul aktif) sesuai tema aktif. */
    @DrawableRes
    public static int pillOk(Context c) {
        return resolveDrawable(c, R.attr.hiPillOk);
    }

    /** Pill status MERAH (belum granted) sesuai tema aktif. */
    @DrawableRes
    public static int pillBad(Context c) {
        return resolveDrawable(c, R.attr.hiPillBad);
    }

    /** Pill status NETRAL (abu) sesuai tema aktif. */
    @DrawableRes
    public static int pillNeutral(Context c) {
        return resolveDrawable(c, R.attr.hiPillNeutral);
    }
}
