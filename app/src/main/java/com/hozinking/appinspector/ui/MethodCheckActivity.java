package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * HTTP Method Checker — kirim OPTIONS/TRACE/PUT/DELETE, laporkan method
 * yang diizinkan. Flag: TRACE aktif (risiko XST) / PUT-DELETE terbuka.
 * Request baca saja (tanpa body).
 */
public class MethodCheckActivity extends PentestToolActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("HTTP Method Checker",
                "Cek method HTTP yang diizinkan server (OPTIONS/TRACE/PUT/DELETE).");
        setInput("URL target", "mis. https://example.com");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append("\n\n");

        // 1. OPTIONS -> header Allow
        setStatus("Mengirim OPTIONS ...");
        String allow = null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod("OPTIONS");
            c.setRequestProperty("User-Agent", UA);
            c.connect();
            int code = c.getResponseCode();
            allow = c.getHeaderField("Allow");
            if (allow == null) allow = c.getHeaderField("allow");
            sb.append("OPTIONS → HTTP ").append(code).append("\n");
            sb.append("Header Allow: ").append(allow != null ? allow : "(tidak ada)").append("\n\n");
        } catch (Exception e) {
            sb.append("OPTIONS → error: ").append(e.getClass().getSimpleName()).append("\n\n");
        } finally {
            if (c != null) c.disconnect();
        }

        // 2. Uji method satu-satu
        sb.append("— Uji method —\n");
        String[] methods = {"TRACE", "PUT", "DELETE", "PATCH"};
        for (String m : methods) {
            setStatus("Mengirim " + m + " ...");
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                break;
            }
            int code = sendMethod(url, m);
            String verdict;
            if (code == -1) {
                verdict = "error/koneksi gagal";
            } else if (code >= 200 && code < 300) {
                verdict = "⚠ DIIZINKAN (HTTP " + code + ")";
            } else {
                verdict = "ditolak (HTTP " + code + ")";
            }
            sb.append("• ").append(m).append(" → ").append(verdict).append("\n");
            if (m.equals("TRACE") && code >= 200 && code < 300) {
                sb.append("  → TRACE aktif = risiko XST (Cross-Site Tracing).\n");
                sb.append("    Sebaiknya dimatikan di konfigurasi server.\n");
            }
            if ((m.equals("PUT") || m.equals("DELETE")) && code >= 200 && code < 300) {
                sb.append("  → Method tulis terbuka = server bisa diubah dari luar!\n");
            }
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    /** Kirim 1 request dengan method tsb (tanpa body); kembalikan HTTP code / -1. */
    private int sendMethod(String urlStr, String method) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod(method);
            c.setRequestProperty("User-Agent", UA);
            c.connect();
            return c.getResponseCode();
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
