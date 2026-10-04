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
        final Set<String> exact = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        exact.addAll(cfg.hookClasses);
        final boolean rateLimit = cfg.rateLimit;
        TRACE_HOOK_LIMITED = rateLimit;
        TRACE_LOG_ARGS = cfg.logArgs;
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
                            if (!matches(name, prefixes, exact)) return;
                            hookClass(cls);
                        } catch (Throwable ignored) {
                        }
                    }
                });
        HiLog.i("MT", "method tracer armed, prefixes=" + prefixes.size()
                + " exact=" + exact.size() + " rateLimit=" + rateLimit);
    }

    private static volatile boolean TRACE_HOOK_LIMITED = true;
    /** Bila true, log CALL/RET menyertakan argumen + return value. */
    private static volatile boolean TRACE_LOG_ARGS = true;

    /** String aman untuk satu argumen: Tipe=nilai (array diringkas). */
    private static String argStr(Object o) {
        if (o == null) return "null";
        try {
            Class<?> c = o.getClass();
            if (c.isArray()) {
                Class<?> comp = c.getComponentType();
                int len = java.lang.reflect.Array.getLength(o);
                return (comp != null ? comp.getSimpleName() : "?") + "[](len=" + len + ")";
            }
            return c.getSimpleName() + "=" + HiLog.safe(o, 120);
        } catch (Throwable t) {
            return "<?>";
        }
    }

    private static boolean matches(String name, List<String> prefixes, Set<String> exact) {
        if (name.startsWith("com.hozinking.appinspector.")) return false;
        if (name.startsWith("de.robv.")) return false;
        if (exact.contains(name)) return true;
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
                if (TRACE_HOOK_LIMITED && !RateLimiter.allow("MT")) return;
                StringBuilder sb = new StringBuilder();
                sb.append("CALL ")
                        .append(HiLog.shortName(param.method.getDeclaringClass().getName()))
                        .append(".").append(param.method.getName());
                if (TRACE_LOG_ARGS) {
                    sb.append("(");
                    Object[] args = param.args;
                    for (int i = 0; i < args.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(argStr(args[i]));
                    }
                    sb.append(")");
                } else {
                    sb.append("()");
                }
                sb.append(" [").append(Thread.currentThread().getName()).append("]");
                HiLog.i("MT", sb.toString());
            } catch (Throwable ignored) {
            }
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) {
            try {
                if (TRACE_HOOK_LIMITED && !RateLimiter.allow("MT")) return;
                String tag = "RET "
                        + HiLog.shortName(param.method.getDeclaringClass().getName())
                        + "." + param.method.getName();
                if (!TRACE_LOG_ARGS) {
                    HiLog.i("MT", tag);
                    return;
                }
                String ret;
                if (param.hasThrowable()) {
                    ret = "THROW " + HiLog.safe(param.getThrowable(), 120);
                } else if (param.method instanceof Method
                        && ((Method) param.method).getReturnType() == void.class) {
                    ret = "-> <void>";
                } else {
                    ret = "-> " + HiLog.safe(param.getResult(), 120);
                }
                HiLog.i("MT", tag + " " + ret);
            } catch (Throwable ignored) {
            }
        }
    };
}
