package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robots & Sitemap — fetch /robots.txt (+ fallback http), tampilkan rapi,
 * daftar path Disallowed, ikuti Sitemap: dan /sitemap.xml (50 URL pertama).
 */
public class RobotsActivity extends PentestToolActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Robots & Sitemap",
                "Ambil robots.txt + sitemap.xml, daftar path yang disallow.");
        setInput("Host", "mis. example.com");
    }

    @Override
    protected void onRun() {
        String host = hostOf(input());
        if (host.isEmpty()) {
            setResult("Host tidak valid.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Host: ").append(host).append("\n\n");

        // --- robots.txt (https dulu, fallback http) ---
        setStatus("Mengambil robots.txt ...");
        Fetch r = fetch("https://" + host + "/robots.txt", null, 15000, 200 * 1024);
        if ((r.error != null || r.code == 404) && r.error != null) {
            Fetch r2 = fetch("http://" + host + "/robots.txt", null, 15000, 200 * 1024);
            if (r2.error == null && r2.code == 200) r = r2;
        }
        List<String> disallowed = new ArrayList<>();
        List<String> sitemaps = new ArrayList<>();
        if (r.error != null) {
            sb.append("— robots.txt —\nGagal: ").append(r.error).append("\n\n");
        } else if (r.code != 200) {
            sb.append("— robots.txt —\nHTTP ").append(r.code)
                    .append(" (tidak ada / tidak boleh diakses)\n\n");
        } else {
            sb.append("— robots.txt (").append(r.finalUrl).append(") —\n");
            for (String line : r.body.split("\n")) {
                String t = line.trim();
                if (t.toLowerCase().startsWith("disallow:")) {
                    String p = t.substring(9).trim();
                    if (!p.isEmpty()) disallowed.add(p);
                } else if (t.toLowerCase().startsWith("sitemap:")) {
                    sitemaps.add(t.substring(8).trim());
                }
            }
            sb.append("\nPath Disallowed (").append(disallowed.size()).append("):\n");
            if (disallowed.isEmpty()) sb.append("(tidak ada)\n");
            for (String d : disallowed) sb.append("• ").append(d).append("\n");
            sb.append("\n");
        }

        // --- sitemap.xml ---
        setStatus("Mengambil sitemap ...");
        List<String> smUrls = new ArrayList<>(sitemaps);
        if (smUrls.isEmpty()) smUrls.add("https://" + host + "/sitemap.xml");
        boolean gotMap = false;
        for (String sm : smUrls) {
            Fetch s = fetch(sm, null, 15000, 500 * 1024);
            if (s.error != null || s.code != 200) continue;
            gotMap = true;
            sb.append("— sitemap (").append(s.finalUrl).append(") —\n");
            Matcher m = Pattern.compile("<loc>([^<]+)</loc>").matcher(s.body);
            int n = 0;
            while (m.find() && n < 50) {
                sb.append("• ").append(m.group(1).trim()).append("\n");
                n++;
            }
            if (n == 0) sb.append("(tidak ada <loc> terbaca)\n");
            else if (n == 50) sb.append("... (dibatasi 50 pertama)\n");
            sb.append("\n");
            break;
        }
        if (!gotMap) {
            sb.append("— sitemap —\nTidak ditemukan/tidak bisa diakses.\n");
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }
}
