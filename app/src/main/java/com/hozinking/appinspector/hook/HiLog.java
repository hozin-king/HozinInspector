package com.hozinking.appinspector.hook;

import de.robv.android.xposed.XposedBridge;

/** Logging + helper stack trace. Semua log difilter dengan prefix [HozinInspector]. */
public class HiLog {
    public static final String PREFIX = "[HozinInspector]";

    public static void i(String tag, String msg) {
        try {
            XposedBridge.log(PREFIX + "[" + tag + "] " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isSkipped(String c) {
        if (c.startsWith("android.") || c.startsWith("java.") || c.startsWith("kotlin.")
                || c.startsWith("kotlinx.") || c.startsWith("dalvik.")
                || c.startsWith("de.robv.") || c.startsWith("com.hozinking.appinspector.")
                || c.startsWith("com.android.") || c.startsWith("androidx.")
                || c.startsWith("sun.") || c.startsWith("jdk.")) {
            return true;
        }
        if (c.contains("$$Lambda")) return true;
        // anonymous inner class: com.foo.Bar$1
        int d = c.lastIndexOf('$');
        if (d >= 0 && d + 1 < c.length() && Character.isDigit(c.charAt(d + 1))) return true;
        return false;
    }

    /** Frame stack pertama milik app pemicu (bukan framework/xposed). */
    public static String firstAppFrame() {
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String c = e.getClassName();
                if (isSkipped(c)) continue;
                return shortName(c) + "." + e.getMethodName()
                        + "(" + e.getFileName() + ":" + e.getLineNumber() + ")";
            }
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /** Rantai pemanggilan "dari mana ke mana", ~maxFrames frame app. */
    public static String appStackTrace(int maxFrames) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try {
            for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
                String c = e.getClassName();
                if (isSkipped(c)) continue;
                if (n > 0) sb.append(" <- ");
                sb.append(shortName(c)).append(".").append(e.getMethodName());
                if (++n >= maxFrames) break;
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    public static String shortName(String className) {
        int i = className.lastIndexOf('.');
        return i >= 0 ? className.substring(i + 1) : className;
    }

    /** toString() yang aman (tidak melempar) + truncate + satu baris. */
    public static String safe(Object o, int maxLen) {
        if (o == null) return "null";
        try {
            String s = String.valueOf(o);
            if (s.length() > maxLen) s = s.substring(0, maxLen) + "...";
            return s.replace('\n', ' ').replace('\r', ' ');
        } catch (Throwable t) {
            return "<toString error>";
        }
    }
}
