package com.hozinking.appinspector.hook;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate limiter log per tag: maks 50 log/detik per tag.
 * Selebihnya di-drop; jumlah yang di-drop dilaporkan sekali saat window berganti.
 * Ringan: satu synchronized kecil per tag, tanpa alokasi di path panas.
 */
public class RateLimiter {
    private static final int MAX_PER_SEC = 50;

    private static class Slot {
        long windowStart;
        int count;
        int dropped;
    }

    private static final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();

    /** @return true bila log boleh ditulis, false bila di-drop karena rate limit. */
    public static boolean allow(String tag) {
        long now = System.currentTimeMillis();
        Slot s = slots.get(tag);
        if (s == null) {
            Slot ns = new Slot();
            ns.windowStart = now;
            ns.count = 1;
            Slot prev = slots.putIfAbsent(tag, ns);
            s = (prev != null) ? prev : ns;
            if (prev == null) return true;
        }
        synchronized (s) {
            if (now - s.windowStart >= 1000) {
                int dropped = s.dropped;
                s.windowStart = now;
                s.count = 1;
                s.dropped = 0;
                if (dropped > 0) {
                    // laporkan di luar lock agar tidak rekursi
                    reportDropped(tag, dropped);
                }
                return true;
            }
            if (s.count < MAX_PER_SEC) {
                s.count++;
                return true;
            }
            s.dropped++;
            return false;
        }
    }

    private static void reportDropped(final String tag, final int n) {
        try {
            // langsung via XposedBridge agar tidak kena rate limit lagi
            de.robv.android.xposed.XposedBridge.log(
                    HiLog.PREFIX + "[" + tag + "] ... +" + n + " log di-drop (rate limit 50/dtk)");
        } catch (Throwable ignored) {
        }
    }
}
