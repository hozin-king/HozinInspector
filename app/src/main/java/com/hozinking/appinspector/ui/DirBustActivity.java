package com.hozinking.appinspector.ui;

import android.os.Bundle;
import android.text.InputType;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dir Buster — enumerasi path/direktori dengan wordlist (~200 path umum
 * bawaan, atau custom via kolom tambahan). Request satu-satu dengan jeda
 * 250ms (rate-limit). Laporkan 200/301/302/403/401; 404 dilewati.
 *
 * Hanya untuk target milik sendiri / ada izin testing.
 */
public class DirBustActivity extends PentestToolActivity {

    private static final int DELAY_MS = 250;
    private static final int MAX_CUSTOM = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Dir Buster",
                "Tebak path tersembunyi (admin, backup, .env, dll.) dengan wordlist.\n"
                        + "Hanya untuk target milik sendiri / ada izin testing.");
        setInput("URL dasar", "mis. https://example.com");
        setExtra("Wordlist custom (opsional)", "satu path per baris, mis.\ninternal\nrahasia");
        etExtra.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etExtra.setSingleLine(false);
        etExtra.setMinLines(2);
    }

    @Override
    protected void onRun() {
        String base = normalizeUrl(input()).replaceAll("/+$", "");
        List<String> words = loadWordlist();
        if (words.isEmpty()) {
            setResult("Wordlist kosong / gagal dibaca.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Target: ").append(base).append("\n");
        sb.append("Wordlist: ").append(words.size()).append(" path\n\n");
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
            Fetch f = fetch(base + "/" + w, null, 8000, 16 * 1024);
            if (f.error != null || f.code == 404 || f.code == 400) continue;
            String tag;
            switch (f.code) {
                case 200: tag = "ADA (200)"; break;
                case 301:
                case 302:
                case 307:
                case 308: tag = "redirect (" + f.code + ")"; break;
                case 401: tag = "butuh auth (401)"; break;
                case 403: tag = "ada tapi forbidden (403)"; break;
                default:
                    if (f.code >= 200 && f.code < 300) tag = "ADA (" + f.code + ")";
                    else continue;
            }
            hits++;
            appendResult("• /" + w + " → " + tag + "\n");
        }
        // hasil sudah di-append bertahap; tulis ringkasan di status
        setStatus("Selesai: " + hits + " temuan dari " + words.size() + " path.");
        if (hits == 0) {
            appendResult("(tidak ada path menarik — semua 404/timeout)\n");
        }
    }

    /** Wordlist custom (extra) bila diisi, else bawaan dari res/raw. */
    private List<String> loadWordlist() {
        List<String> out = new ArrayList<>();
        String custom = extra();
        if (!custom.isEmpty()) {
            for (String line : custom.split("\n")) {
                String w = line.trim().replaceAll("^/+", "");
                if (!w.isEmpty() && out.size() < MAX_CUSTOM) out.add(w);
            }
            return out;
        }
        InputStream in = null;
        try {
            in = getResources().openRawResource(
                    getResources().getIdentifier("dir_wordlist", "raw", getPackageName()));
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
