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
 * XSS Tester — enumerasi parameter query + field form (GET saja),
 * uji refleksi pakai canary AMAN (string unik, bukan payload jahat),
 * laporkan parameter yang merefleksikan canary + pola sink berbahaya (edukasi).
 *
 * Batasan keras: maks ~10 request, jeda 400ms, TIDAK submit form POST,
 * canary tidak mengeksekusi apa pun.
 */
public class XssTestActivity extends PentestToolActivity {

    private static final String CANARY = "hZXSScan9zq";
    private static final int MAX_REQ = 10;
    private static final int DELAY_MS = 400;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("XSS Tester",
                "Uji refleksi input (GET saja) dengan canary aman + deteksi sink berbahaya.\n"
                        + "Hanya untuk target milik sendiri / ada izin testing.");
        setInput("URL target", "mis. https://example.com/search?q=test");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(url).append("\n");
        sb.append("Canary: ").append(CANARY).append(" (string aman, non-eksekusi)\n\n");

        // 1. Enumerasi vektor: query param + form GET
        setStatus("Mengambil halaman & enumerasi vektor ...");
        Fetch base = fetch(url, null, 15000, 300 * 1024);
        if (base.error != null) {
            setResult(sb + "Gagal mengambil URL:\n" + base.error);
            setStatus("Gagal.");
            return;
        }
        Map<String, String> vectors = new LinkedHashMap<>();
        // query param dari URL
        try {
            String q = new java.net.URL(base.finalUrl).getQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    String k = kv.split("=", 2)[0];
                    if (!k.isEmpty()) vectors.put("query:" + k, k);
                }
            }
        } catch (Exception ignored) {
        }
        // field form GET
        for (String[] form : parseGetForms(base.body, base.finalUrl)) {
            // form = {action, name}
            if (!vectors.containsValue(form[1])) {
                vectors.put("form:" + form[1] + "@" + shortUrl(form[0]), form[1] + "|" + form[0]);
            }
        }
        if (vectors.isEmpty()) {
            sb.append("Tidak ada parameter query / field form GET ditemukan.\n");
            sb.append("(Tool ini hanya menguji vektor GET — halaman tanpa input = tidak ada yang diuji.)\n");
        } else {
            sb.append("Vektor ditemukan: ").append(vectors.size()).append("\n\n");
        }

        // 2. Uji refleksi canary (maks MAX_REQ)
        int tested = 0, reflected = 0;
        for (Map.Entry<String, String> e : vectors.entrySet()) {
            if (tested >= MAX_REQ) {
                sb.append("\n(dibatasi ").append(MAX_REQ).append(" request — vektor lain dilewati)\n");
                break;
            }
            tested++;
            setStatus("Menguji vektor " + tested + "/" + Math.min(vectors.size(), MAX_REQ) + " ...");
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException ex) {
                break;
            }
            String testUrl = buildTestUrl(base.finalUrl, e.getValue());
            if (testUrl == null) continue;
            Fetch f = fetch(testUrl, null, 12000, 300 * 1024);
            if (f.error != null) {
                sb.append("• ").append(e.getKey()).append(" → error: ").append(f.error).append("\n");
                continue;
            }
            if (f.body.contains(CANARY)) {
                reflected++;
                sb.append("• ").append(e.getKey()).append(" → ⚠ TEREFLEKSI\n");
                sb.append("  canary muncul mentah di response — kandidat XSS, verifikasi manual!\n");
            } else {
                sb.append("• ").append(e.getKey()).append(" → tidak terefleksi\n");
            }
        }

        // 3. Pola sink berbahaya (edukasi, dari HTML/JS halaman)
        sb.append("\n— Pola sink berbahaya di halaman (edukasi) —\n");
        String[] sinks = {"innerHTML\\s*=", "outerHTML\\s*=", "document\\.write\\s*\\(",
                "eval\\s*\\(", "insertAdjacentHTML", "\\.html\\s*\\("};
        boolean anySink = false;
        for (String s : sinks) {
            Matcher m = Pattern.compile(s).matcher(base.body);
            if (m.find()) {
                anySink = true;
                sb.append("• pola '").append(s.replace("\\s*", " "))
                        .append("' ditemukan — pastikan input di-escape/sanitasi.\n");
            }
        }
        if (!anySink) sb.append("(tidak ada pola sink umum terdeteksi di HTML)\n");

        sb.append("\nRingkasan: ").append(tested).append(" vektor diuji, ")
                .append(reflected).append(" merefleksikan canary.\n");
        sb.append("Catatan: terefleksi ≠ pasti exploitable — verifikasi konteks\n");
        sb.append("(apakah di dalam tag, atribut, atau JS) secara manual.\n");
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    /** Bangun URL uji: ganti nilai parameter dengan canary. value = "name" atau "name|action". */
    private String buildTestUrl(String baseUrl, String value) {
        try {
            String name, action;
            if (value.contains("|")) {
                String[] p = value.split("\\|", 2);
                name = p[0];
                action = p[1];
            } else {
                name = value;
                action = baseUrl;
            }
            String enc = URLEncoder.encode(CANARY, "UTF-8");
            if (action.contains("?")) {
                // ganti param yang sudah ada bila namanya cocok
                if (action.matches("(?i).*([?&])" + Pattern.quote(name) + "=.*")) {
                    return action.replaceAll("(?i)([?&]" + Pattern.quote(name) + "=)[^&]*",
                            "$1" + enc);
                }
                return action + "&" + name + "=" + enc;
            }
            return action + "?" + name + "=" + enc;
        } catch (Exception e) {
            return null;
        }
    }

    /** Parse form GET: kembalikan list {actionAbsolut, namaField}. */
    private List<String[]> parseGetForms(String html, String pageUrl) {
        List<String[]> out = new ArrayList<>();
        try {
            Matcher fm = Pattern.compile("<form[^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
            while (fm.find()) {
                String tag = fm.group();
                String method = attr(tag, "method");
                if (method != null && !method.equalsIgnoreCase("get")) continue; // hanya GET
                String action = attr(tag, "action");
                if (action == null || action.isEmpty()) action = pageUrl;
                else action = resolve(pageUrl, action);
                int end = html.indexOf("</form>", fm.end());
                String inner = end > 0 ? html.substring(fm.end(), end) : "";
                Matcher im = Pattern.compile("<input[^>]*>", Pattern.CASE_INSENSITIVE).matcher(inner);
                while (im.find()) {
                    String name = attr(im.group(), "name");
                    String type = attr(im.group(), "type");
                    if (name != null && !name.isEmpty()
                            && (type == null || !type.equalsIgnoreCase("submit"))) {
                        out.add(new String[]{action, name});
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private String attr(String tag, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*[\"']([^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private String resolve(String pageUrl, String rel) {
        try {
            return new java.net.URL(new java.net.URL(pageUrl), rel).toString();
        } catch (Exception e) {
            return pageUrl;
        }
    }

    private String shortUrl(String u) {
        return u.length() > 40 ? u.substring(0, 40) + "..." : u;
    }
}
