package com.hozinking.appinspector.ui;

import android.os.Bundle;

import org.json.JSONArray;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Wayback URL Finder — gali URL historis sebuah domain via
 * web.archive.org CDX API. Bisa filter kata kunci (mis. admin, api, .php).
 * Berguna menemukan endpoint lama yang terlupakan.
 */
public class WaybackActivity extends PentestToolActivity {

    private static final int LIMIT = 5000;
    private static final int SHOW_MAX = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Wayback URL Finder",
                "Daftar URL historis domain dari arsip web.archive.org.");
        setInput("Domain", "mis. example.com");
        setExtra("Filter kata kunci (opsional)", "mis. admin — kosongkan = semua");
    }

    @Override
    protected void onRun() {
        String domain = input().toLowerCase()
                .replaceAll("(?i)^https?://", "").split("/")[0].trim();
        if (!domain.contains(".")) {
            setResult("Domain tidak valid.");
            return;
        }
        String keyword = extra().toLowerCase();
        setStatus("Query arsip web.archive.org ... (bisa belasan detik)");
        StringBuilder sb = new StringBuilder();
        sb.append("Domain: ").append(domain).append("\n");
        if (!keyword.isEmpty()) sb.append("Filter: ").append(keyword).append("\n");
        sb.append("\n");
        try {
            String url = "https://web.archive.org/cdx/search/cdx?url=*."
                    + URLEncoder.encode(domain, "UTF-8") + "/*"
                    + "&output=json&fl=original&collapse=urlkey&limit=" + LIMIT;
            Map<String, String> hp = new HashMap<>();
            hp.put("Accept", "application/json");
            Fetch f = fetch(url, hp, 45000, 4 * 1024 * 1024);
            if (f.error != null) {
                sb.append("Gagal query arsip:\n").append(f.error);
                setResult(sb.toString());
                setStatus("Gagal.");
                return;
            }
            JSONArray arr = new JSONArray(f.body);
            List<String> urls = new ArrayList<>();
            for (int i = 1; i < arr.length(); i++) { // baris 0 = header
                JSONArray row = arr.getJSONArray(i);
                if (row.length() == 0) continue;
                String u = row.getString(0);
                if (!keyword.isEmpty() && !u.toLowerCase().contains(keyword)) continue;
                // buang file statis yang tidak menarik bila tanpa filter
                if (keyword.isEmpty() && u.matches("(?i).*\\.(css|js|png|jpe?g|gif|svg|ico|woff2?|ttf)(\\?.*)?$")) {
                    continue;
                }
                urls.add(u);
                if (urls.size() >= SHOW_MAX) break;
            }
            sb.append("Ditemukan ").append(urls.size())
                    .append(urls.size() >= SHOW_MAX ? "+ (dibatasi " + SHOW_MAX + ")" : "")
                    .append(" URL:\n\n");
            for (String u : urls) {
                sb.append("• ").append(u).append("\n");
            }
            if (urls.isEmpty()) {
                sb.append("(tidak ada — domain mungkin belum terarsip)\n");
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getClass().getSimpleName())
                    .append(e.getMessage() != null ? ": " + e.getMessage() : "").append("\n");
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }
}
