package com.hozinking.appinspector.hook;

import android.view.View;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * UI interaction tracer: tap tombol/view di app target => ketahuan
 * class listener mana yang menangani + alur eksekusi "dari mana ke mana".
 * - setOnClickListener: catat view -> nama class listener (WeakHashMap)
 * - performClick: log view class, id resource, text, listener, + stack trace app
 */
public class UiTracer {
    private static final Map<View, String> listenerMap =
            Collections.synchronizedMap(new WeakHashMap<View, String>());

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        try {
            XposedHelpers.findAndHookMethod("android.view.View", cl, "setOnClickListener",
                    "android.view.View$OnClickListener", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View v = (View) param.thisObject;
                                Object l = param.args[0];
                                if (v != null && l != null) {
                                    listenerMap.put(v, l.getClass().getName());
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            HiLog.i("ERR", "setOnClickListener hook gagal: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod("android.view.View", cl, "performClick",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                View v = (View) param.thisObject;
                                StringBuilder sb = new StringBuilder();
                                sb.append("CLICK view=").append(v.getClass().getName());

                                try {
                                    int id = v.getId();
                                    if (id != View.NO_ID) {
                                        try {
                                            sb.append(" id=").append(v.getResources().getResourceEntryName(id));
                                        } catch (Exception e) {
                                            sb.append(" id=0x").append(Integer.toHexString(id));
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }

                                try {
                                    if (v instanceof TextView) {
                                        CharSequence cs = ((TextView) v).getText();
                                        if (cs != null && cs.length() > 0) {
                                            sb.append(" text=\"")
                                                    .append(HiLog.safe(cs.toString(), 80)).append("\"");
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }

                                String listener = listenerMap.get(v);
                                sb.append(" listener=").append(listener != null ? listener : "?");

                                HiLog.i("UI", sb.toString());
                                HiLog.i("UI", "FLOW " + HiLog.appStackTrace(15));
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            HiLog.i("UI", "ui tracer armed");
        } catch (Throwable t) {
            HiLog.i("ERR", "performClick hook gagal: " + t);
        }
    }
}
