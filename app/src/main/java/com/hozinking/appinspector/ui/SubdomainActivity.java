package com.hozinking.appinspector.ui;

import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Subdomain Finder — enumerasi subdomain pasif via crt.sh
 * (Certificate Transparency logs). Tanpa API key.
 * Pola: GET https://crt.sh/?q=%25.<domain>&output=json -> name_value.
 */
public class SubdomainActivity extends PentestToolActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Subdomain Finder",
                "Cari subdomain via Certificate Transparency (crt.sh). 100% pasif.");
        setInput("Domain", "mis. example.com (tanpa https://)");
    }

    @Override
    protected void onRun() {
        String domain = input().toLowerCase()
                .replaceAll("(?i)^https?://", "").split("/")[0].trim();
        if (!domain.contains(".")) {
            setResult("Domain tidak valid.");
            return;
        }
        setStatus("Query crt.sh untuk %." + domain + " ... (bisa 10–30 dtk)");
        StringBuilder sb = new StringBuilder();
        sb.append("Domain: ").append(domain).append("\n\n");
        try {
            String url = "https://crt.sh/?q=%25."
                    + URLEncoder.encode(domain, "UTF-8") + "&output=json";
            Map<String, String> hp = new HashMap<>();
            hp.put("Accept", "application/json");
            Fetch f = fetch(url, hp, 45000, 4 * 1024 * 1024);
            if (f.error != null) {
                sb.append("Gagal query crt.sh:\n").append(f.error)
                        .append("\n\ncrt.sh kadang rate-limit (429) — coba lagi nanti.");
                setResult(sb.toString());
                setStatus("Gagal.");
                return;
            }
            if (f.code == 429 || f.code >= 500) {
                sb.append("crt.sh merespons HTTP ").append(f.code)
                        .append(" — coba lagi beberapa menit.");
                setResult(sb.toString());
                setStatus("Gagal.");
                return;
            }
            Set<String> subs = new HashSet<>();
            JSONArray arr = new JSONArray(f.body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String nv = o.optString("name_value", "");
                for (String line : nv.split("\n")) {
                    String s = line.trim().toLowerCase();
                    if (s.startsWith("*.")) s = s.substring(2);
                    if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
                    if (!s.isEmpty() && (s.equals(domain) || s.endsWith("." + domain))) {
                        subs.add(s);
                    }
                }
            }
            List<String> sorted = new ArrayList<>(subs);
            Collections.sort(sorted);
            sb.append("Ditemukan ").append(sorted.size())
                    .append(" nama unik:\n\n");
            for (String s : sorted) {
                sb.append("• ").append(s).append("\n");
            }
            if (sorted.isEmpty()) {
                sb.append("(tidak ada — domain mungkin belum punya sertifikat publik)\n");
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getClass().getSimpleName())
                    .append(e.getMessage() != null ? ": " + e.getMessage() : "").append("\n");
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }
}
