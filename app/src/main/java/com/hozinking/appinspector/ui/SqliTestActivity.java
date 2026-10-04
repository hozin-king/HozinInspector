package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQLi Tester — untuk tiap parameter kirim varian `'` dan `'"` (NON-dekstruktif:
 * tanpa DROP/DELETE/UPDATE/UNION), pindai response terhadap signature error
 * DB (MySQL, PostgreSQL, MSSQL, Oracle, SQLite).
 *
 * Batasan keras: maks ~10 request, jeda 400ms, hanya GET.
 */
public class SqliTestActivity extends PentestToolActivity {

    private static final int MAX_REQ = 10;
    private static final int DELAY_MS = 400;

    private static class Sig {
        String db, pattern;
        Sig(String d, String p) { db = d; pattern = p; }
    }

    private static final Sig[] DB_ERRORS = {
            new Sig("MySQL", "you have an error in your sql syntax"),
            new Sig("MySQL", "mysql_fetch"),
            new Sig("MySQL", "mysqli_"),
            new Sig("MySQL", "mysql_num_rows"),
            new Sig("PostgreSQL", "pg_query"),
            new Sig("PostgreSQL", "postgresql"),
            new Sig("PostgreSQL", "unterminated quoted string"),
            new Sig("PostgreSQL", "psql:"),
            new Sig("MSSQL", "odbc sql server driver"),
            new Sig("MSSQL", "sqlexception"),
            new Sig("MSSQL", "unclosed quotation mark"),
            new Sig("Oracle", "ora-"),
            new Sig("Oracle", "oracle error"),
            new Sig("SQLite", "sqlite3::"),
            new Sig("SQLite", "sqlite_error"),
            new Sig("SQLite", "sqlite3.operationalerror"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("SQLi Tester",
                "Uji error-based SQLi heuristik (tanda kutip saja, non-destruktif).\n"
                        + "Hanya untuk target milik sendiri / ada izin testing.");
        setInput("URL target", "mis. https://example.com/item?id=1");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append("\n");
        sb.append("Metode: error-based heuristik (probe `'` dan `'\"`)\n\n");

        // Enumerasi parameter query
        Map<String, String> params = new LinkedHashMap<>();
        try {
            String q = new java.net.URL(url).getQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    String[] p = kv.split("=", 2);
                    if (!p[0].isEmpty()) {
                        params.put(p[0], p.length > 1 ? p[1] : "");
                    }
                }
            }
        } catch (Exception e) {
            sb.append("URL tidak valid: ").append(e.getMessage()).append("\n");
            setResult(sb.toString());
            setStatus("Gagal.");
            return;
        }
        if (params.isEmpty()) {
            sb.append("Tidak ada parameter query di URL.\n");
            sb.append("Contoh: https://target/item?id=1&cat=books\n");
            setResult(sb.toString());
            setStatus("Selesai.");
            return;
        }
        sb.append("Parameter: ").append(params.size()).append("\n\n");

        String[] probes = {"'", "'\""};
        int tested = 0, hits = 0;
        List<String> names = new ArrayList<>(params.keySet());
        for (String name : names) {
            for (String probe : probes) {
                if (tested >= MAX_REQ) {
                    sb.append("\n(dibatasi ").append(MAX_REQ)
                            .append(" request — sisanya dilewati)\n");
                    break;
                }
                tested++;
                setStatus("Menguji " + name + " [" + tested + "/" + MAX_REQ + "] ...");
                try {
                    Thread.sleep(DELAY_MS);
                } catch (InterruptedException e) {
                    break;
                }
                String testUrl = inject(url, name, params.get(name) + probe);
                if (testUrl == null) continue;
                Fetch f = fetch(testUrl, null, 12000, 300 * 1024);
                if (f.error != null) {
                    sb.append("• ").append(name).append(" probe '").append(probe)
                            .append("' → error: ").append(f.error).append("\n");
                    continue;
                }
                String low = f.body.toLowerCase();
                boolean hit = false;
                for (Sig s : DB_ERRORS) {
                    if (low.contains(s.pattern)) {
                        hits++;
                        hit = true;
                        sb.append("• ").append(name).append(" probe '").append(probe)
                                .append("' → ⚠ INDIKASI ").append(s.db).append("\n");
                        sb.append("  signature: \"").append(s.pattern).append("\"\n");
                        sb.append("  cuplikan: ").append(snippet(f.body, s.pattern)).append("\n");
                        break;
                    }
                }
                if (!hit) {
                    sb.append("• ").append(name).append(" probe '").append(probe)
                            .append("' → tidak ada error DB\n");
                }
            }
            if (tested >= MAX_REQ) break;
        }

        sb.append("\nRingkasan: ").append(tested).append(" probe, ")
                .append(hits).append(" indikasi error DB.\n");
        sb.append("Catatan: error DB ≠ pasti exploitable — bisa false positive\n");
        sb.append("(mis. halaman error generik). Verifikasi manual sebelum\n");
        sb.append("menyimpulkan. Tidak ada payload destruktif yang dikirim.\n");
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    /** Ganti nilai satu parameter dengan nilai baru (URL-encoded). */
    private String inject(String url, String name, String newVal) {
        try {
            String enc = URLEncoder.encode(newVal, "UTF-8");
            return url.replaceAll("([?&]" + Pattern.quote(name) + "=)[^&]*", "$1" + enc);
        } catch (Exception e) {
            return null;
        }
    }

    private String snippet(String body, String pattern) {
        try {
            Matcher m = Pattern.compile("(?i).{0,60}" + Pattern.quote(pattern) + ".{0,60}")
                    .matcher(body.replaceAll("\\s+", " "));
            if (m.find()) {
                String s = m.group().trim();
                return s.length() > 140 ? s.substring(0, 140) + "..." : s;
            }
        } catch (Exception ignored) {
        }
        return "(tidak bisa ambil cuplikan)";
    }
}
