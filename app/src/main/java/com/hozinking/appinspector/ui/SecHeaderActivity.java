package com.hozinking.appinspector.ui;

import android.os.Bundle;

/**
 * Security Header Checker — cek 6 header keamanan penting,
 * kasih grade A–F + penjelasan tiap header yang hilang (Bahasa Indonesia).
 */
public class SecHeaderActivity extends PentestToolActivity {

    private static class Hdr {
        String name, desc;
        Hdr(String n, String d) { name = n; desc = d; }
    }

    private static final Hdr[] CHECKS = {
            new Hdr("Strict-Transport-Security",
                    "HSTS: memaksa browser selalu pakai HTTPS. Tanpa ini, user rentan downgrade ke HTTP."),
            new Hdr("Content-Security-Policy",
                    "CSP: membatasi dari mana script/gambar boleh dimuat. Pertahanan utama anti-XSS."),
            new Hdr("X-Frame-Options",
                    "Mencegah situs di-embed dalam iframe situs lain (anti-clickjacking)."),
            new Hdr("X-Content-Type-Options",
                    "Dengan nilai 'nosniff': cegah browser menebak tipe konten (anti MIME-sniffing)."),
            new Hdr("Referrer-Policy",
                    "Mengatur seberapa banyak URL asal dibocorkan ke situs lain via header Referer."),
            new Hdr("Permissions-Policy",
                    "Membatasi akses fitur browser sensitif (kamera, mic, geolocation) oleh halaman/iframe."),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Security Header Checker",
                "Cek header keamanan HTTP & beri nilai (grade).");
        setInput("URL target", "mis. https://example.com");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        setStatus("Mengambil header " + url + " ...");
        Fetch f = fetch(url, null, 15000, 64 * 1024);
        if (f.error != null) {
            setResult("Gagal mengambil URL:\n" + f.error);
            setStatus("Gagal.");
            return;
        }

        int ok = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(f.finalUrl).append("\n");
        sb.append("HTTP: ").append(f.code).append("\n\n");

        for (Hdr h : CHECKS) {
            String v = header(f.headers, h.name);
            if (v != null) {
                ok++;
                sb.append("[ADA] ").append(h.name).append("\n");
                sb.append("      ").append(trunc(v, 120)).append("\n\n");
            } else {
                sb.append("[HILANG] ").append(h.name).append("\n");
                sb.append("      → ").append(h.desc).append("\n\n");
            }
        }

        String grade;
        switch (ok) {
            case 6: grade = "A"; break;
            case 5: grade = "B"; break;
            case 4: grade = "C"; break;
            case 3: grade = "D"; break;
            case 2: grade = "E"; break;
            default: grade = "F"; break;
        }
        sb.insert(0, "GRADE KEAMANAN: " + grade + " (" + ok + "/6 header ada)\n\n");

        // Info tambahan: Server header (bocor info versi = kurang bagus)
        String server = header(f.headers, "Server");
        if (server != null && server.matches(".*\\d+\\.\\d+.*")) {
            sb.append("Catatan: header Server membocorkan versi (")
                    .append(server).append(") — sebaiknya disamarkan.\n");
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    private String trunc(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
