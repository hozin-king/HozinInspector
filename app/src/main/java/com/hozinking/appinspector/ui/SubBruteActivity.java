package com.hozinking.appinspector.ui;

import android.os.Bundle;
import android.text.InputType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Subdomain Bruteforce — resolve satu-satu nama umum (~300 bawaan,
 * atau custom) jadi sub.domain. Pelengkap Subdomain Finder (pasif crt.sh).
 * Jeda 100ms antar query (rate-limit), hanya menampilkan yang resolve.
 *
 * Hanya untuk target milik sendiri / ada izin testing.
 */
public class SubBruteActivity extends PentestToolActivity {

    private static final int DELAY_MS = 100;
    private static final int MAX_CUSTOM = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Subdomain Bruteforce",
                "Tebak subdomain aktif via DNS resolve.\n"
                        + "Hanya untuk target milik sendiri / ada izin testing.");
        setInput("Domain", "mis. example.com");
        setExtra("Wordlist custom (opsional)", "satu nama per baris, mis.\napi2\nbeta2");
        etExtra.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etExtra.setSingleLine(false);
        etExtra.setMinLines(2);
    }

    @Override
    protected void onRun() {
        String domain = input().toLowerCase()
                .replaceAll("(?i)^https?://", "").split("/")[0].trim();
        if (!domain.contains(".")) {
            setResult("Domain tidak valid.");
            return;
        }
        List<String> words = loadWordlist();
        StringBuilder sb = new StringBuilder();
        sb.append("Domain: ").append(domain).append("\n");
        sb.append("Wordlist: ").append(words.size()).append(" nama\n\n");
        setResult(sb.toString());

        int hits = 0;
        int i = 0;
        for (String w : words) {
            i++;
            setStatus("Progres: " + i + "/" + words.size() + " • temuan: " + hits);
            try {
                Thread.sleep(DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
            String host = w + "." + domain;
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                if (addrs != null && addrs.length > 0) {
                    hits++;
                    StringBuilder ips = new StringBuilder();
                    for (InetAddress a : addrs) {
                        if (ips.length() > 0) ips.append(", ");
                        ips.append(a.getHostAddress());
                    }
                    appendResult("• " + host + "\n  → " + ips + "\n");
                }
            } catch (Exception ignored) {
                // tidak resolve = lewati
            }
        }
        setStatus("Selesai: " + hits + " subdomain aktif dari " + words.size() + ".");
        if (hits == 0) {
            appendResult("(tidak ada yang resolve)\n");
        }
    }

    private List<String> loadWordlist() {
        List<String> out = new ArrayList<>();
        String custom = extra();
        if (!custom.isEmpty()) {
            for (String line : custom.split("\n")) {
                String w = line.trim().toLowerCase().replaceAll("[^a-z0-9-]", "");
                if (!w.isEmpty() && out.size() < MAX_CUSTOM) out.add(w);
            }
            return out;
        }
        InputStream in = null;
        try {
            in = getResources().openRawResource(
                    getResources().getIdentifier("sub_wordlist", "raw", getPackageName()));
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
        } catch (Exception ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }
}
