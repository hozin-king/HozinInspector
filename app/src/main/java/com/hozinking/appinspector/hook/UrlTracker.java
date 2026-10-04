package com.hozinking.appinspector.hook;

import java.net.Proxy;
import java.net.URL;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * URL tracker: mencatat semua request HTTP yang keluar dari app target.
 * - java.net.URL.openConnection() (+ varian Proxy) => URL
 * - java.net.HttpURLConnection.setRequestMethod() => method (GET/POST/...)
 * - okhttp3.OkHttpClient.newCall() => method + url (bila app pakai OkHttp/Retrofit)
 * - android.webkit.WebView.loadUrl() => URL
 * Catatan: isi body HTTPS tetap terenkripsi (hanya URL/method yang kelihatan).
 */
public class UrlTracker {

    public static void install(XC_LoadPackage.LoadPackageParam lpparam) {
        final ClassLoader cl = lpparam.classLoader;

        // 1. URL.openConnection() — mencakup HttpURLConnection & HttpsURLConnection
        try {
            XC_MethodHook hook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        URL u = (URL) param.thisObject;
                        HiLog.i("URL", "openConnection " + u.toString());
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod(URL.class, "openConnection", hook);
            try {
                XposedHelpers.findAndHookMethod(URL.class, "openConnection", Proxy.class, hook);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            HiLog.i("ERR", "URL.openConnection hook gagal: " + t);
        }

        // 2. HttpURLConnection.setRequestMethod()
        try {
            XposedHelpers.findAndHookMethod("java.net.HttpURLConnection", cl,
                    "setRequestMethod", String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                HiLog.i("URL", "setRequestMethod " + param.args[0]);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
        } catch (Throwable t) {
            HiLog.i("ERR", "setRequestMethod hook gagal: " + t);
        }

        // 3. OkHttp (dipakai langsung / via Retrofit) — guard bila tidak ada
        try {
            Class<?> clientCls = Class.forName("okhttp3.OkHttpClient", false, cl);
            XposedBridge.hookAllMethods(clientCls, "newCall", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object req = param.args[0];
                        Object url = XposedHelpers.callMethod(req, "url");
                        Object method = XposedHelpers.callMethod(req, "method");
                        HiLog.i("URL", "okhttp " + method + " " + url);
                    } catch (Throwable ignored) {
                    }
                }
            });
            HiLog.i("URL", "okhttp hook terpasang");
        } catch (Throwable ignored) {
            // app tidak pakai OkHttp — lewati diam-diam
        }

        // 4. WebView.loadUrl (kedua overload)
        try {
            XC_MethodHook wvHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        HiLog.i("URL", "webview.loadUrl " + HiLog.safe(param.args[0], 300));
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod("android.webkit.WebView", cl,
                    "loadUrl", String.class, wvHook);
            XposedHelpers.findAndHookMethod("android.webkit.WebView", cl,
                    "loadUrl", String.class, Map.class, wvHook);
        } catch (Throwable t) {
            HiLog.i("ERR", "WebView hook gagal: " + t);
        }

        HiLog.i("URL", "url tracker armed");
    }
}
