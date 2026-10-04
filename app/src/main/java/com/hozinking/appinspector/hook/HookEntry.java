package com.hozinking.appinspector.hook;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Entry point modul LSPosed (didaftarkan via assets/xposed_init).
 * Hanya aktif di package target sesuai config.json — proses lain diabaikan.
 */
public class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Tandai modul aktif: tulis flag di files dir sendiri (UID sendiri, tanpa root).
            // MainActivity membaca flag ini untuk indikator "MODUL: AKTIF".
            if ("com.hozinking.appinspector".equals(lpparam.packageName)) {
                markSelfActive(lpparam);
                return;
            }
            HiConfig cfg = HiConfig.load();
            if (cfg.targetPackage == null || cfg.targetPackage.isEmpty()) return;
            if (!lpparam.packageName.equals(cfg.targetPackage)) return;
            if ("android".equals(lpparam.packageName)) return;

            HiLog.i("INIT", "attached to " + lpparam.packageName);

            if (cfg.methodTrace && (!cfg.traceClasses.isEmpty() || !cfg.hookClasses.isEmpty())) {
                try {
                    MethodTracer.install(lpparam, cfg);
                } catch (Throwable t) {
                    HiLog.i("ERR", "MethodTracer: " + t);
                }
            }
            if (cfg.urlTrack) {
                try {
                    UrlTracker.install(lpparam);
                } catch (Throwable t) {
                    HiLog.i("ERR", "UrlTracker: " + t);
                }
            }
            if (cfg.uiTrace) {
                try {
                    UiTracer.install(lpparam);
                } catch (Throwable t) {
                    HiLog.i("ERR", "UiTracer: " + t);
                }
            }
            if (cfg.prefTrace) {
                try {
                    PrefTracer.install(lpparam);
                } catch (Throwable t) {
                    HiLog.i("ERR", "PrefTracer: " + t);
                }
            }
            if (cfg.dumpUi) {
                try {
                    LayoutDumper.install(lpparam);
                } catch (Throwable t) {
                    HiLog.i("ERR", "LayoutDumper: " + t);
                }
            }
        } catch (Throwable ignored) {
            // jangan pernah jatuhkan proses target
        }
    }

    /** Tulis timestamp ke files dir sendiri saat proses app inspector sendiri di-hook. */
    private static void markSelfActive(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                    "android.app.Application", lpparam.classLoader, "onCreate",
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.content.Context ctx =
                                        (android.content.Context) param.thisObject;
                                java.io.File f = new java.io.File(
                                        ctx.getFilesDir(), "xposed_active.flag");
                                java.io.FileWriter w = new java.io.FileWriter(f, false);
                                w.write(String.valueOf(System.currentTimeMillis()));
                                w.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable ignored) {
        }
    }
}
