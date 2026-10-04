package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Web Tech Detector — deteksi server/CMS/framework dari response header
 * + fingerprint HTML (pola ala Wappalyzer: headers, meta generator,
 * script src, cookies, HTML). Signature bawaan, tanpa library tambahan.
 */
public class WebTechActivity extends PentestToolActivity {

    private static class Sig {
        String name, cat, evidence;
        Sig(String n, String c, String e) { name = n; cat = c; evidence = e; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Web Tech Detector",
                "Deteksi teknologi web (server, CMS, framework, JS lib) dari header & HTML.");
        setInput("URL target", "mis. https://example.com");
    }

    @Override
    protected void onRun() {
        String url = normalizeUrl(input());
        setStatus("Mengambil " + url + " ...");
        Fetch f = fetch(url, null, 15000, 300 * 1024);
        if (f.error != null) {
            setResult("Gagal mengambil URL:\n" + f.error);
            setStatus("Gagal.");
            return;
        }
        setStatus("Menganalisis header & HTML ...");

        List<Sig> found = detect(f);
        StringBuilder sb = new StringBuilder();
        sb.append("URL: ").append(f.finalUrl).append("\n");
        sb.append("HTTP: ").append(f.code).append("\n\n");
        if (found.isEmpty()) {
            sb.append("Tidak ada teknologi yang terdeteksi dari signature bawaan.\n");
        } else {
            sb.append("Terdeteksi ").append(found.size()).append(" teknologi:\n\n");
            for (Sig s : found) {
                sb.append("• ").append(s.name).append(" [").append(s.cat).append("]\n");
                sb.append("  bukti: ").append(s.evidence).append("\n\n");
            }
        }
        // Info tambahan: header server mentah
        String server = header(f.headers, "Server");
        String powered = header(f.headers, "X-Powered-By");
        sb.append("— Header mentah —\n");
        sb.append("Server: ").append(server != null ? server : "-").append("\n");
        sb.append("X-Powered-By: ").append(powered != null ? powered : "-").append("\n");
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    private List<Sig> detect(Fetch f) {
        List<Sig> out = new ArrayList<>();
        String html = f.body != null ? f.body : "";
        String htmlLow = html.toLowerCase();
        String server = header(f.headers, "Server");
        String powered = header(f.headers, "X-Powered-By");
        List<String> cookies = headerAll(f.headers, "Set-Cookie");
        String cookieStr = String.join(";", cookies).toLowerCase();

        // --- server ---
        if (server != null) {
            if (server.toLowerCase().contains("cloudflare")) {
                out.add(new Sig("Cloudflare", "CDN/WAF", "header Server: " + server));
            } else if (server.toLowerCase().contains("nginx")) {
                out.add(new Sig("Nginx " + ver(server), "Web server", "header Server: " + server));
            } else if (server.toLowerCase().contains("apache")) {
                out.add(new Sig("Apache " + ver(server), "Web server", "header Server: " + server));
            } else if (server.toLowerCase().contains("microsoft-iis")) {
                out.add(new Sig("IIS " + ver(server), "Web server", "header Server: " + server));
            } else if (server.toLowerCase().contains("litespeed")) {
                out.add(new Sig("LiteSpeed", "Web server", "header Server: " + server));
            }
        }
        if (header(f.headers, "CF-Ray") != null || header(f.headers, "cf-ray") != null) {
            if (!has(out, "Cloudflare")) {
                out.add(new Sig("Cloudflare", "CDN/WAF", "header CF-Ray ada"));
            }
        }
        // --- powered-by ---
        if (powered != null) {
            String p = powered.toLowerCase();
            if (p.contains("php")) out.add(new Sig("PHP " + ver(powered), "Bahasa", "X-Powered-By: " + powered));
            if (p.contains("express")) out.add(new Sig("Express.js", "Framework", "X-Powered-By: " + powered));
            if (p.contains("asp.net")) out.add(new Sig("ASP.NET", "Framework", "X-Powered-By: " + powered));
        }
        // --- CMS ---
        String gen = metaGenerator(html);
        if (gen != null) {
            if (gen.toLowerCase().contains("wordpress")) {
                out.add(new Sig("WordPress " + ver(gen), "CMS", "meta generator: " + gen));
            } else if (gen.toLowerCase().contains("drupal")) {
                out.add(new Sig("Drupal " + ver(gen), "CMS", "meta generator: " + gen));
            } else if (gen.toLowerCase().contains("joomla")) {
                out.add(new Sig("Joomla", "CMS", "meta generator: " + gen));
            }
        }
        if (htmlLow.contains("wp-content") || htmlLow.contains("wp-includes")) {
            if (!has(out, "WordPress")) {
                out.add(new Sig("WordPress", "CMS", "pola wp-content/wp-includes di HTML"));
            }
        }
        // --- framework backend via cookie ---
        if (cookieStr.contains("laravel_session") || cookieStr.contains("xsrf-token")) {
            out.add(new Sig("Laravel", "Framework", "cookie laravel_session/XSRF-TOKEN"));
        }
        if (cookieStr.contains("csrftoken")) {
            out.add(new Sig("Django", "Framework", "cookie csrftoken"));
        }
        if (cookieStr.contains("ci_session")) {
            out.add(new Sig("CodeIgniter", "Framework", "cookie ci_session"));
        }
        // --- frontend ---
        if (html.contains("__NEXT_DATA__")) {
            out.add(new Sig("Next.js", "Framework JS", "marker __NEXT_DATA__"));
        }
        if (htmlLow.contains("data-reactroot") || htmlLow.matches("(?s).*react(\\.|\\-)\\d.*\\.js.*")) {
            out.add(new Sig("React", "Library JS", "pola react di HTML/script"));
        }
        if (htmlLow.contains("ng-version") || htmlLow.contains("angular")) {
            // hindari false positive kata "angular" biasa: butuh ng-version atau script angular
            if (html.contains("ng-version") || htmlLow.matches("(?s).*angular(\\.|\\-)\\d.*\\.js.*")) {
                out.add(new Sig("Angular", "Framework JS", "marker ng-version / script angular"));
            }
        }
        if (html.contains("data-v-") || htmlLow.matches("(?s).*vue(\\.|\\-)\\d.*\\.js.*")) {
            out.add(new Sig("Vue.js", "Framework JS", "atribut data-v- / script vue"));
        }
        if (htmlLow.matches("(?s).*jquery.*\\.js.*")) {
            out.add(new Sig("jQuery", "Library JS", "script jquery"));
        }
        if (htmlLow.matches("(?s).*bootstrap.*\\.(css|js).*")) {
            out.add(new Sig("Bootstrap", "UI framework", "asset bootstrap"));
        }
        if (htmlLow.contains("googletagmanager.com") || htmlLow.contains("google-analytics.com")) {
            out.add(new Sig("Google Analytics/GTM", "Analytics", "script googletagmanager/google-analytics"));
        }
        return out;
    }

    private boolean has(List<Sig> out, String name) {
        for (Sig s : out) {
            if (s.name.startsWith(name)) return true;
        }
        return false;
    }

    private String metaGenerator(String html) {
        Matcher m = Pattern.compile(
                "<meta[^>]+name=[\"']generator[\"'][^>]+content=[\"']([^\"']+)[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        if (m.find()) return m.group(1);
        m = Pattern.compile(
                "<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']generator[\"']",
                Pattern.CASE_INSENSITIVE).matcher(html);
        return m.find() ? m.group(1) : null;
    }

    private String ver(String s) {
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)+)").matcher(s);
        return m.find() ? m.group(1) : "";
    }
}
