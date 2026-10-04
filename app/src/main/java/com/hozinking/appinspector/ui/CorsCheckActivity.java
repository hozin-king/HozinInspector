package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;

/**
 * CORS & Clickjacking Check — kirim request dengan Origin asing,
 * cek apakah di-reflect (misconfig CORS); cek proteksi clickjacking
 * via X-Frame-Options / CSP frame-ancestors.
 */
public class CorsCheckActivity extends PentestToolActivity {

    private static final String EVIL_ORIGIN = "https://evil-hozin-test.example";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("CORS & Clickjacking Check",
                "Uji refleksi Origin (CORS) + proteksi framing. 1 request baca saja.");
        setInput("URL target", "mis. https://example.com/api/data");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        setStatus("Mengirim request dengan Origin asing ...");
        Map<String, String> hp = new HashMap<>();
        hp.put("Origin", EVIL_ORIGIN);
        Fetch f = fetch(url, hp, 15000, 64 * 1024);
        if (f.error != null) {
            setResult("Gagal mengambil URL:\n" + f.error);
            setStatus("Gagal.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(f.finalUrl).append("\n");
        sb.append("HTTP: ").append(f.code).append("\n");
        sb.append("Origin uji: ").append(EVIL_ORIGIN).append("\n\n");

        // --- CORS ---
        sb.append("— CORS —\n");
        String acao = header(f.headers, "Access-Control-Allow-Origin");
        String acac = header(f.headers, "Access-Control-Allow-Credentials");
        if (acao == null) {
            sb.append("Tidak ada header Access-Control-Allow-Origin.\n");
            sb.append("→ CORS tidak dilonggarkan (aman dari sisi ini).\n");
        } else if (acao.equals(EVIL_ORIGIN)) {
            sb.append("⚠ BERBAHAYA: Origin asing DI-REFLECT!\n");
            sb.append("  ACAO: ").append(acao).append("\n");
            if ("true".equalsIgnoreCase(acac)) {
                sb.append("  + Allow-Credentials: true → situs lain bisa baca\n");
                sb.append("  response dengan kredensial korban!\n");
            }
        } else if (acao.equals("*")) {
            sb.append("ACAO: * (wildcard)\n");
            if ("true".equalsIgnoreCase(acac)) {
                sb.append("⚠ Wildcard + credentials=true = konfigurasi salah\n");
                sb.append("  (browser modern menolak kombinasi ini).\n");
            } else {
                sb.append("→ Data publik bisa dibaca lintas-origin (umumnya wajar\n");
                sb.append("  untuk API publik, tapi pastikan bukan data sensitif).\n");
            }
        } else {
            sb.append("ACAO: ").append(acao).append("\n");
            sb.append("→ Origin dibatasi (aman).\n");
        }

        // --- Clickjacking ---
        sb.append("\n— Clickjacking —\n");
        String xfo = header(f.headers, "X-Frame-Options");
        String csp = header(f.headers, "Content-Security-Policy");
        String fa = null;
        if (csp != null) {
            for (String dir : csp.split(";")) {
                if (dir.trim().toLowerCase().startsWith("frame-ancestors")) {
                    fa = dir.trim();
                    break;
                }
            }
        }
        if (xfo != null) {
            sb.append("X-Frame-Options: ").append(xfo).append(" → terproteksi.\n");
        } else {
            sb.append("X-Frame-Options: TIDAK ADA\n");
        }
        if (fa != null) {
            sb.append("CSP frame-ancestors: ").append(fa).append(" → terproteksi.\n");
        } else {
            sb.append("CSP frame-ancestors: tidak diset\n");
        }
        if (xfo == null && fa == null) {
            sb.append("⚠ Halaman bisa di-embed di iframe situs lain\n");
            sb.append("  (rentan clickjacking bila ada aksi sensitif).\n");
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }
}
