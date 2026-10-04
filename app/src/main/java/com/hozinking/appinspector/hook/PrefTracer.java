package com.hozinking.appinspector.hook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Pref access tracer: setiap read/write SharedPreferences dicatat BESERTA
 * class pemicunya (dari stack trace) — "kode Java/class mana yang nanganin sharedprefs".
 * - WRITE: EditorImpl.putString/putInt/putLong/putFloat/putBoolean/putStringSet/remove/clear/commit/apply
 * - READ: SharedPreferencesImpl.getString/getInt/getLong/getFloat/getBoolean/getStringSet/contains
 * - OPEN: ContextImpl.getSharedPreferences(name, mode) => nama file pref
 * Batasan: EncryptedSharedPreferences terbaca key-nya saja, value terenkripsi.
 */
public class PrefTracer {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        // Nama file pref yang dibuka
        try {
            XposedHelpers.findAndHookMethod("android.app.ContextImpl", cl,
                    "getSharedPreferences", String.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                HiLog.i("PREF", "open file=\"" + param.args[0]
                                        + "\" by " + HiLog.firstAppFrame());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            HiLog.i("ERR", "getSharedPreferences hook gagal: " + t);
        }

        // ---- WRITE via Editor ----
        try {
            Class<?> editorCls = XposedHelpers.findClass(
                    "android.app.SharedPreferencesImpl$EditorImpl", cl);

            String[] putMethods = {"putString", "putInt", "putLong", "putFloat", "putBoolean"};
            for (final String m : putMethods) {
                try {
                    XposedBridge.hookAllMethods(editorCls, m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                HiLog.i("PREF", "WRITE " + m + " key=\"" + param.args[0]
                                        + "\" value=" + HiLog.safe(param.args[1], 200)
                                        + " by " + HiLog.firstAppFrame());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            try {
                XposedBridge.hookAllMethods(editorCls, "putStringSet", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            HiLog.i("PREF", "WRITE putStringSet key=\"" + param.args[0]
                                    + "\" value=" + HiLog.safe(param.args[1], 200)
                                    + " by " + HiLog.firstAppFrame());
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                XposedBridge.hookAllMethods(editorCls, "remove", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            HiLog.i("PREF", "WRITE remove key=\"" + param.args[0]
                                    + "\" by " + HiLog.firstAppFrame());
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                XposedBridge.hookAllMethods(editorCls, "clear", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            HiLog.i("PREF", "WRITE clear by " + HiLog.firstAppFrame());
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            XC_MethodHook commitApply = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        HiLog.i("PREF", "WRITE " + param.method.getName()
                                + " by " + HiLog.firstAppFrame());
                    } catch (Throwable ignored) {
                    }
                }
            };
            try {
                XposedBridge.hookAllMethods(editorCls, "commit", commitApply);
            } catch (Throwable ignored) {
            }
            try {
                XposedBridge.hookAllMethods(editorCls, "apply", commitApply);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            HiLog.i("ERR", "EditorImpl hook gagal: " + t);
        }

        // ---- READ ----
        try {
            Class<?> spCls = XposedHelpers.findClass("android.app.SharedPreferencesImpl", cl);

            String[] getMethods = {"getString", "getInt", "getLong", "getFloat", "getBoolean"};
            for (final String m : getMethods) {
                try {
                    XposedBridge.hookAllMethods(spCls, m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                HiLog.i("PREF", "READ " + m + " key=\"" + param.args[0]
                                        + "\" -> " + HiLog.safe(param.getResult(), 200)
                                        + " by " + HiLog.firstAppFrame());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                } catch (Throwable ignored) {
                }
            }

            try {
                XposedBridge.hookAllMethods(spCls, "getStringSet", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            HiLog.i("PREF", "READ getStringSet key=\"" + param.args[0]
                                    + "\" -> " + HiLog.safe(param.getResult(), 200)
                                    + " by " + HiLog.firstAppFrame());
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }

            try {
                XposedBridge.hookAllMethods(spCls, "contains", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            HiLog.i("PREF", "READ contains key=\"" + param.args[0]
                                    + "\" -> " + param.getResult()
                                    + " by " + HiLog.firstAppFrame());
                        } catch (Throwable ignored) {
                        }
                    }
                });
            } catch (Throwable ignored) {
            }
            HiLog.i("PREF", "pref tracer armed");
        } catch (Throwable t) {
            HiLog.i("ERR", "SharedPreferencesImpl hook gagal: " + t);
        }
    }
}
