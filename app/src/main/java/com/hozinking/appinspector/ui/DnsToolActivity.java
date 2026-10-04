package com.hozinking.appinspector.ui;

import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

/**
 * DNS Toolkit — lookup A/AAAA (via InetAddress) dan MX/TXT/NS/PTR
 * (via DNS-over-HTTPS dns.google, tanpa API key).
 */
public class DnsToolActivity extends PentestToolActivity {

    private static final String[] MODES = {"A", "AAAA", "MX", "TXT", "NS", "PTR (reverse IP)"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("DNS Toolkit",
                "Lookup record DNS. A/AAAA lokal; MX/TXT/NS/PTR via DNS-over-HTTPS.");
        setInput("Domain atau IP", "mis. example.com (PTR: isi IP)");
        setModes("Tipe record", MODES);
    }

    @Override
    protected void onRun() {
        String target = input();
        String m = mode();
        setStatus("Query " + m + " untuk " + target + " ...");
        StringBuilder sb = new StringBuilder();
        sb.append("Target: ").append(target).append("\n");
        sb.append("Tipe: ").append(m).append("\n\n");

        try {
            if (m.equals("A") || m.equals("AAAA")) {
                boolean v6 = m.equals("AAAA");
                InetAddress[] addrs = InetAddress.getAllByName(target);
                int n = 0;
                for (InetAddress a : addrs) {
                    String ip = a.getHostAddress();
                    boolean isV6 = ip != null && ip.contains(":");
                    if (isV6 == v6) {
                        sb.append("• ").append(ip).append("\n");
                        n++;
                    }
                }
                if (n == 0) sb.append("(tidak ada record ").append(m).append(")\n");
            } else if (m.startsWith("PTR")) {
                // reverse: 1.2.3.4 -> 4.3.2.1.in-addr.arpa
                String[] parts = target.split("\\.");
                if (parts.length != 4) {
                    sb.append("PTR butuh IPv4, mis. 8.8.8.8\n");
                } else {
                    String rev = parts[3] + "." + parts[2] + "." + parts[1] + "."
                            + parts[0] + ".in-addr.arpa";
                    sb.append(doh(rev, "PTR"));
                }
            } else {
                sb.append(doh(target, m));
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getClass().getSimpleName())
                    .append(e.getMessage() != null ? ": " + e.getMessage() : "").append("\n");
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    /** Query DNS-over-HTTPS (dns.google), kembalikan jawaban sebagai teks. */
    private String doh(String name, String type) {
        StringBuilder sb = new StringBuilder();
        try {
            String url = "https://dns.google/resolve?name="
                    + java.net.URLEncoder.encode(name, "UTF-8") + "&type=" + type;
            Map<String, String> hp = new HashMap<>();
            hp.put("Accept", "application/json");
            Fetch f = fetch(url, hp, 15000, 64 * 1024);
            if (f.error != null) return "Gagal DoH: " + f.error + "\n";
            JSONObject o = new JSONObject(f.body);
            if (!o.has("Answer")) return "(tidak ada jawaban)\n";
            JSONArray arr = o.getJSONArray("Answer");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.getJSONObject(i);
                sb.append("• ").append(a.optString("data", "?")).append("\n");
            }
            if (arr.length() == 0) sb.append("(tidak ada jawaban)\n");
        } catch (Exception e) {
            sb.append("Error DoH: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }
}
