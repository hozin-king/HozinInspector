package com.hozinking.appinspector.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Method tracer: hook semua declared method (+constructor) dari class yang
 * namanya match prefix di config.traceClasses, via intersepsi ClassLoader.loadClass.
 * Log: CALL class.method(args) [thread] dan RET class.method -> return value.
 */
public class MethodTracer {
    private static final Set<String> hooked =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    public static void install(XC_LoadPackage.LoadPackageParam lpparam, HiConfig cfg) {
        final List<String> prefixes = cfg.traceClasses;
        XposedHelpers.findAndHookMethod(ClassLoader.class, "loadClass", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.hasThrowable()) return;
                            Object res = param.getResult();
                            if (!(res instanceof Class)) return;
                            Class<?> cls = (Class<?>) res;
                            String name = cls.getName();
                            if (!matches(name, prefixes)) return;
                            hookClass(cls);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        HiLog.i("MT", "method tracer armed, prefixes=" + prefixes);
    }

    private static boolean matches(String name, List<String> prefixes) {
        if (name.startsWith("com.hozinking.appinspector.")) return false;
        if (name.startsWith("de.robv.")) return false;
        for (String p : prefixes) {
            if (p != null && !p.isEmpty() && name.startsWith(p)) return true;
        }
        return false;
    }

    private static void hookClass(Class<?> cls) {
        String name = cls.getName();
        if (!hooked.add(name)) return; // sudah di-hook sebelumnya
        int count = 0;
        for (Method m : cls.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (Modifier.isAbstract(mod) || Modifier.isNative(mod)) continue;
            try {
                XposedBridge.hookMethod(m, TRACE_HOOK);
                count++;
            } catch (Throwable ignored) {
            }
        }
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(c, TRACE_HOOK);
                count++;
            } catch (Throwable ignored) {
            }
        }
        HiLog.i("MT", "hooked " + count + " methods of " + name);
    }

    private static final XC_MethodHook TRACE_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("CALL ")
                        .append(HiLog.shortName(param.method.getDeclaringClass().getName()))
                        .append(".").append(param.method.getName()).append("(");
                Object[] args = param.args;
                for (int i = 0; i < args.length; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(HiLog.safe(args[i], 200));
                }
                sb.append(") [").append(Thread.currentThread().getName()).append("]");
                HiLog.i("MT", sb.toString());
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                String ret = param.hasThrowable()
                        ? "THROW " + HiLog.safe(param.getThrowable(), 200)
                        : "-> " + HiLog.safe(param.getResult(), 200);
                HiLog.i("MT", "RET "
                        + HiLog.shortName(param.method.getDeclaringClass().getName())
                        + "." + param.method.getName() + " " + ret);
            } catch (Throwable ignored) {
            }
        }
    };
}
