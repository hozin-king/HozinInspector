package com.hozinking.appinspector.hook;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Layout dump on-demand: saat dumpUi=true di config, setiap Activity yang
 * mendapat window focus akan di-dump view tree-nya (class, id, text).
 * Matikan flag dumpUi di config untuk berhenti.
 */
public class LayoutDumper {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", cl,
                    "onWindowFocusChanged", boolean.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                boolean hasFocus = (Boolean) param.args[0];
                                if (!hasFocus) return;
                                Activity act = (Activity) param.thisObject;
                                View root = act.getWindow().getDecorView();
                                HiLog.i("LAYOUT", "=== dump: " + act.getClass().getName() + " ===");
                                dump(root, 0);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            HiLog.i("LAYOUT", "layout dumper armed (dump tiap window focus)");
        } catch (Throwable t) {
            HiLog.i("ERR", "LayoutDumper hook gagal: " + t);
        }
    }

    private static void dump(View v, int depth) {
        if (v == null || depth > 15) return;
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < depth; i++) sb.append("  ");
            sb.append(v.getClass().getSimpleName());
            try {
                int id = v.getId();
                if (id != View.NO_ID) {
                    try {
                        sb.append(" #").append(v.getResources().getResourceEntryName(id));
                    } catch (Exception e) {
                        sb.append(" #0x").append(Integer.toHexString(id));
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                if (v instanceof TextView) {
                    CharSequence cs = ((TextView) v).getText();
                    if (cs != null && cs.length() > 0) {
                        sb.append(" \"").append(HiLog.safe(cs.toString(), 60)).append("\"");
                    }
                }
            } catch (Throwable ignored) {
            }
            HiLog.i("LAYOUT", sb.toString());
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                int n = g.getChildCount();
                for (int i = 0; i < n; i++) {
                    try {
                        dump(g.getChildAt(i), depth + 1);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
