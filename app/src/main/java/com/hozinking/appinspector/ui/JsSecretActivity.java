package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JS Secret Scanner — ambil halaman + semua file .js yang di-load,
 * pindai dengan regex: API key umum, Telegram bot, Discord webhook,
 * AWS key, private key. Secret dimask sebagian saat ditampilkan.
 *
 * Hanya untuk target milik sendiri / ada izin testing.
 */
public class JsSecretActivity extends PentestToolActivity {

    private static final int MAX_JS_FILES = 15;
    private static final int MAX_JS_BYTES = 500 * 1024;

    private static class Rule {
        String label, regex;
        Rule(String l, String r) { label = l; regex = r; }
    }

    private static final Rule[] RULES = {
            new Rule("Google API key", "AIza[0-9A-Za-z_\\-]{35}"),
            new Rule("Stripe live key", "sk_live_[0-9a-zA-Z]{16,}"),
            new Rule("GitHub token", "ghp_[0-9A-Za-z]{36}"),
            new Rule("Slack token", "xox[bap]-[0-9A-Za-z\\-]{10,}"),
            new Rule("AWS access key", "AKIA[0-9A-Z]{16}"),
            new Rule("Telegram bot token", "api\\.telegram\\.org/bot\\d+:[\\w\\-]{20,}"),
            new Rule("Discord webhook", "discord\\.com/api/webhooks/\\d+/[\\w\\-]+"),
            new Rule("Private key", "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            new Rule("AWS secret (assignment)", "aws_secret_access_key[\"']?\\s*[:=]\\s*[\"'][^\"']{8,}[\"']"),
            new Rule("Generic secret (assignment)",
                    "(?:api[_-]?key|apikey|secret[_-]?key|client[_-]?secret)[\"']?\\s*[:=]\\s*[\"'][^\"']{8,}[\"']"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("JS Secret Scanner",
                "Pindai file JavaScript halaman untuk API key / token yang bocor.\n"
                        + "Hanya untuk target milik sendiri / ada izin testing.");
        setInput("URL target", "mis. https://example.com");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        setStatus("Mengambil halaman ...");
        Fetch page = fetch(url, null, 15000, 300 * 1024);
        if (page.error != null) {
            setResult("Gagal mengambil URL:\n" + page.error);
            setStatus("Gagal.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(page.finalUrl).append("\n\n");

        // Kumpulkan URL script: inline + src
        List<String> jsUrls = new ArrayList<>();
        List<String> inline = new ArrayList<>();
        Matcher sm = Pattern.compile(
                "<script[^>]+src=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
                .matcher(page.body);
        while (sm.find() && jsUrls.size() < MAX_JS_FILES) {
            String abs = resolve(page.finalUrl, sm.group(1).trim());
            if (abs.startsWith("http") && !jsUrls.contains(abs)) jsUrls.add(abs);
        }
        Matcher im = Pattern.compile("<script(?![^>]*src=)[^>]*>(.*?)</script>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(page.body);
        while (im.find()) inline.add(im.group(1));

        sb.append("File JS eksternal: ").append(jsUrls.size()).append("\n");
        sb.append("Script inline: ").append(inline.size()).append("\n\n");

        int total = 0;
        // scan inline dulu
        for (int i = 0; i < inline.size(); i++) {
            total += scanSource(sb, inline.get(i), "[inline script #" + (i + 1) + "]");
        }
        // lalu file eksternal
        int n = 0;
        for (String jsUrl : jsUrls) {
            n++;
            setStatus("Memindai JS " + n + "/" + jsUrls.size() + " ...");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                break;
            }
            Fetch f = fetch(jsUrl, null, 12000, MAX_JS_BYTES);
            if (f.error != null || f.code != 200) {
                sb.append("• ").append(shortUrl(jsUrl)).append(" → gagal diambil\n");
                continue;
            }
            int found = scanSource(sb, f.body, jsUrl);
            total += found;
            if (found == 0) sb.append("• ").append(shortUrl(jsUrl)).append(" → bersih\n");
        }

        sb.append("\nRingkasan: ").append(total).append(" temuan.\n");
        sb.append("Catatan: temuan = pola mencurigakan, belum tentu secret aktif.\n");
        sb.append("Jangan gunakan secret orang lain — laporkan ke pemilik situs.\n");
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    /** Pindai satu sumber JS; kembalikan jumlah temuan. */
    private int scanSource(StringBuilder sb, String src, String where) {
        int count = 0;
        String[] lines = src.split("\n");
        for (Rule r : RULES) {
            Pattern p = Pattern.compile(r.regex);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = p.matcher(lines[i]);
                while (m.find()) {
                    count++;
                    sb.append("• [").append(r.label).append("] ").append(shortUrl(where))
                            .append(" : baris ").append(i + 1).append("\n");
                    sb.append("  ").append(mask(m.group())).append("\n");
                }
                if (count > 60) return count; // batasi output
            }
        }
        return count;
    }

    /** Mask sebagian secret: tampilkan 8 karakter pertama + ***. */
    private String mask(String s) {
        String t = s.length() > 80 ? s.substring(0, 80) + "..." : s;
        if (t.length() > 12) return t.substring(0, 8) + "***" + " (" + s.length() + " char)";
        return "***";
    }

    private String resolve(String pageUrl, String rel) {
        try {
            return new java.net.URL(new java.net.URL(pageUrl), rel).toString();
        } catch (Exception e) {
            return rel;
        }
    }

    private String shortUrl(String u) {
        return u.length() > 60 ? "..." + u.substring(u.length() - 60) : u;
    }
}
