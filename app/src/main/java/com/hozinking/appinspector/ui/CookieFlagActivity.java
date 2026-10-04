package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.util.List;

/**
 * Cookie Flag Checker — cek flag HttpOnly / Secure / SameSite
 * dari tiap Set-Cookie yang dikirim sebuah URL.
 */
public class CookieFlagActivity extends PentestToolActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Cookie Flag Checker",
                "Periksa flag keamanan (HttpOnly, Secure, SameSite) tiap cookie.");
        setInput("URL target", "mis. https://example.com");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        setStatus("Mengambil " + url + " ...");
        Fetch f = fetch(url, null, 15000, 64 * 1024);
        if (f.error != null) {
            setResult("Gagal mengambil URL:\n" + f.error);
            setStatus("Gagal.");
            return;
        }
        List<String> cookies = headerAll(f.headers, "Set-Cookie");
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(f.finalUrl).append("\n");
        sb.append("HTTP: ").append(f.code).append("\n\n");
        if (cookies.isEmpty()) {
            sb.append("Tidak ada header Set-Cookie dari server.\n");
        } else {
            sb.append("Ditemukan ").append(cookies.size()).append(" cookie:\n\n");
            for (String c : cookies) {
                String name = c.split(";", 2)[0].trim();
                if (name.length() > 40) name = name.substring(0, 40) + "...";
                boolean httpOnly = c.toLowerCase().contains("httponly");
                boolean secure = c.toLowerCase().contains("secure");
                String sameSite = sameSite(c);
                sb.append("• ").append(name).append("\n");
                sb.append("  HttpOnly : ").append(ok(httpOnly))
                        .append(httpOnly ? "" : " → cookie bisa dibaca JavaScript (risiko XSS)").append("\n");
                sb.append("  Secure   : ").append(ok(secure))
                        .append(secure ? "" : " → cookie bisa dikirim via HTTP polos").append("\n");
                sb.append("  SameSite : ").append(sameSite != null ? sameSite : "TIDAK DISET")
                        .append(sameSite != null ? "" : " → risiko CSRF").append("\n\n");
            }
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    private String sameSite(String cookie) {
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.toLowerCase().startsWith("samesite=")) {
                return p.substring(9).trim();
            }
        }
        return null;
    }

    private String ok(boolean b) {
        return b ? "YA ✓" : "TIDAK ✗";
    }
}
