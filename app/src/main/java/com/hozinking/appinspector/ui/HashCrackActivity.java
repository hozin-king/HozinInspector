package com.hozinking.appinspector.ui;

import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Hash & JWT Cracker (offline) — dictionary attack 100% lokal:
 * MD5 / SHA-1 / SHA-256, dan bruteforce secret JWT HMAC (HS256/384/512).
 * Wordlist bawaan kecil (+ opsi custom). Tidak ada data yang dikirim keluar.
 *
 * Hanya untuk hash/token milik sendiri (mis. hasil lab / CTF).
 */
public class HashCrackActivity extends PentestToolActivity {

    private static final String[] MODES = {"MD5", "SHA-1", "SHA-256", "JWT HMAC secret"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Hash & JWT Cracker",
                "Dictionary attack OFFLINE (MD5/SHA-1/SHA-256, secret JWT).\n"
                        + "100% lokal — tidak ada yang dikirim ke internet.");
        setInput("Hash / token JWT", "hash hex atau token jwt...");
        setModes("Tipe", MODES);
        setExtra("Wordlist custom (opsional)", "satu kata per baris");
        etExtra.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etExtra.setSingleLine(false);
        etExtra.setMinLines(2);
    }

    @Override
    protected void onRun() {
        String target = input().trim();
        String m = mode();
        List<String> words = loadWordlist();
        StringBuilder sb = new StringBuilder();
        sb.append("Tipe: ").append(m).append("\n");
        sb.append("Wordlist: ").append(words.size()).append(" kata\n\n");

        if (m.equals("JWT HMAC secret")) {
            crackJwt(sb, target, words);
        } else {
            crackHash(sb, target, m, words);
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    private void crackHash(StringBuilder sb, String target, String algo,
                           List<String> words) {
        String want = target.toLowerCase().replaceAll("\\s", "");
        if (!want.matches("^[a-f0-9]+$")) {
            sb.append("Hash harus hex (huruf a-f + angka).\n");
            return;
        }
        int i = 0;
        for (String w : words) {
            i++;
            if (i % 50 == 0) setStatus("Progres: " + i + "/" + words.size());
            try {
                MessageDigest md = MessageDigest.getInstance(algo);
                byte[] d = md.digest(w.getBytes(StandardCharsets.UTF_8));
                if (hex(d).equals(want)) {
                    sb.append("✓ COCOK!\n\nPlaintext: ").append(w).append("\n");
                    sb.append("Algoritma: ").append(algo).append("\n");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        sb.append("✗ Tidak ketemu di ").append(words.size()).append(" kata.\n");
        sb.append("(Coba wordlist custom yang lebih besar.)\n");
    }

    private void crackJwt(StringBuilder sb, String token, List<String> words) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            sb.append("Token JWT harus berbentuk header.payload.signature\n");
            return;
        }
        String alg;
        try {
            String hj = new String(Base64.decode(pad(parts[0]), Base64.URL_SAFE),
                    StandardCharsets.UTF_8);
            alg = new JSONObject(hj).optString("alg", "HS256");
        } catch (Exception e) {
            sb.append("Gagal parse header JWT.\n");
            return;
        }
        if (!alg.startsWith("HS")) {
            sb.append("Algoritma ").append(alg).append(" bukan HMAC — tool ini\n");
            sb.append("hanya mendukung HS256/HS384/HS512.\n");
            return;
        }
        String macAlgo = "HmacSHA" + alg.substring(2); // HS256 -> HmacSHA256
        String signingInput = parts[0] + "." + parts[1];
        String wantSig = parts[2];
        int i = 0;
        for (String secret : words) {
            i++;
            if (i % 50 == 0) setStatus("Progres: " + i + "/" + words.size());
            try {
                Mac mac = Mac.getInstance(macAlgo);
                mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlgo));
                byte[] raw = mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
                String sig = Base64.encodeToString(raw,
                        Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                if (sig.equals(wantSig)) {
                    sb.append("✓ COCOK!\n\nSecret: ").append(secret).append("\n");
                    sb.append("Algoritma: ").append(alg).append("\n");
                    sb.append("\nDengan secret ini, token JWT bisa dipalsukan —\n");
                    sb.append("pastikan secret server cukup kuat & tidak bocor.\n");
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        sb.append("✗ Secret tidak ketemu di ").append(words.size()).append(" kata.\n");
    }

    private String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private String pad(String s) {
        int m = s.length() % 4;
        if (m == 2) return s + "==";
        if (m == 3) return s + "=";
        return s;
    }

    private List<String> loadWordlist() {
        List<String> out = new ArrayList<>();
        String custom = extra();
        if (!custom.isEmpty()) {
            for (String line : custom.split("\n")) {
                String w = line.trim();
                if (!w.isEmpty() && out.size() < 5000) out.add(w);
            }
            return out;
        }
        InputStream in = null;
        try {
            in = getResources().openRawResource(
                    getResources().getIdentifier("crack_wordlist", "raw", getPackageName()));
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
