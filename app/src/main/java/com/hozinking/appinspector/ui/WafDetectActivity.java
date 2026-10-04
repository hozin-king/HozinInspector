package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.util.List;

/**
 * WAF Detector — deteksi WAF/CDN dari header, cookie, dan pola respons khas
 * (Cloudflare, Imperva, Akamai, Sucuri, AWS CloudFront/WAF, F5 BIG-IP).
 * Termasuk tips testing umum (edukasi, bukan bypass beneran).
 */
public class WafDetectActivity extends PentestToolActivity {

    private static class Rule {
        String waf, where, match;
        Rule(String w, String wh, String m) { waf = w; where = wh; match = m; }
    }

    private static final Rule[] RULES = {
            new Rule("Cloudflare", "header CF-Ray / Server", "cf-ray"),
            new Rule("Cloudflare", "cookie cf_clearance / __cf_bm", "cf_clearance|__cf_bm"),
            new Rule("Imperva / Incapsula", "cookie incap_ses_* / visid_incap_*", "incap_ses_|visid_incap_"),
            new Rule("Imperva / Incapsula", "header X-Iinfo", "x-iinfo"),
            new Rule("Akamai", "cookie ak_bmsc / bm_sz", "ak_bmsc|bm_sz"),
            new Rule("Akamai", "header X-Akamai-*", "x-akamai-"),
            new Rule("Sucuri", "header X-Sucuri-*", "x-sucuri-"),
            new Rule("AWS CloudFront / WAF", "header X-Amz-Cf-* / Via cloudfront", "x-amz-cf-|cloudfront"),
            new Rule("F5 BIG-IP", "cookie BigIPServer*", "bigipserver"),
            new Rule("F5 BIG-IP", "header X-WAF-Event", "x-waf-event"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("WAF Detector",
                "Deteksi WAF/CDN dari header & cookie + tips testing umum.");
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
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(f.finalUrl).append("\n");
        sb.append("HTTP: ").append(f.code).append("\n\n");

        // Kumpulkan semua header+cookie jadi satu teks untuk dicocokkan
        StringBuilder all = new StringBuilder();
        for (java.util.Map.Entry<String, List<String>> e : f.headers.entrySet()) {
            if (e.getKey() != null) {
                all.append(e.getKey()).append(": ")
                        .append(String.join(",", e.getValue())).append("\n");
            }
        }
        String blob = all.toString().toLowerCase();

        boolean found = false;
        sb.append("— Deteksi —\n");
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Rule r : RULES) {
            if (blob.matches("(?s).*" + r.match + ".*") && !seen.contains(r.waf)) {
                seen.add(r.waf);
                found = true;
                sb.append("• ").append(r.waf).append("\n");
                sb.append("  bukti: ").append(r.where).append("\n");
            }
        }
        // Server header generik (CDN tanpa signature khusus)
        String server = header(f.headers, "Server");
        if (server != null && !seen.contains("Cloudflare")
                && server.toLowerCase().contains("cloudflare")) {
            found = true;
            sb.append("• Cloudflare\n  bukti: header Server\n");
        }
        if (!found) {
            sb.append("(tidak ada signature WAF/CDN umum terdeteksi)\n");
            sb.append("Bisa berarti: tanpa WAF, atau WAF yang tidak dikenal.\n");
        }

        sb.append("\n— Tips testing umum (edukasi) —\n");
        sb.append("• Jangan andalkan 1 request: WAF kadang hanya aktif di path/param tertentu.\n");
        sb.append("• Uji dengan variasi: HTTP method berbeda, header aneh, encoding ganda.\n");
        sb.append("• Origin server kadang bocor via record DNS lama / header Via.\n");
        sb.append("• Ini panduan metodologi — bukan cara bypass. Testing hanya\n");
        sb.append("  di target milik sendiri / program bug bounty resmi.\n");
        setResult(sb.toString());
        setStatus("Selesai.");
    }
}
