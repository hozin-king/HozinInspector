package com.hozinking.appinspector.ui;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Helper eksekusi perintah root via "su -c".
 * Dipakai fitur ROOT TOOLS — semuanya READ-ONLY terhadap app target
 * (ls/cat/pidof/sqlite3 read). Tidak pernah menulis ke /data/data target.
 */
public class SuHelper {

    public static class Result {
        public final int code;
        public final String out;

        public Result(int code, String out) {
            this.code = code;
            this.out = out;
        }
    }

    /** Jalankan perintah sebagai root, kembalikan exit code + stdout. */
    public static Result exec(String cmd, long timeoutSec) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thread drainer = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] tmp = new byte[8192];
                    int n;
                    while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
                } catch (Exception ignored) {
                }
            });
            drainer.start();
            boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
            drainer.join(3000);
            if (!done) {
                p.destroyForcibly();
                return new Result(-1, "");
            }
            return new Result(p.exitValue(), buf.toString("UTF-8"));
        } catch (Exception e) {
            return new Result(-1, "");
        }
    }

    public static Result exec(String cmd) {
        return exec(cmd, 30);
    }

    /** Cek akses root tersedia (uid=0). */
    public static boolean hasRoot() {
        Result r = exec("id", 10);
        return r.code == 0 && r.out.contains("uid=0");
    }

    /** Bungkus string agar aman dipakai di shell (single-quote escape). */
    public static String q(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
