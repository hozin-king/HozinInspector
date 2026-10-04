package com.hozinking.appinspector.ui;

import android.os.Bundle;
import android.util.Base64;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Encoder/Decoder Lab — Base64, URL, Hex, HTML entities, JWT decode,
 * dan Hash identifier (tebak jenis hash dari pola). 100% lokal/offline.
 */
public class EncoderLabActivity extends PentestToolActivity {

    private static final String[] MODES = {
            "Base64 Encode", "Base64 Decode",
            "URL Encode", "URL Decode",
            "Hex Encode", "Hex Decode",
            "HTML Escape", "HTML Unescape",
            "JWT Decode", "Hash Identifier"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Encoder/Decoder Lab",
                "Encode/decode Base64, URL, Hex, HTML, JWT + identifikasi hash. Offline.");
        setInput("Teks input", "tulis/paste teks di sini");
        setModes("Operasi", MODES);
    }

    @Override
    protected void onRun() {
        String in = etInput.getText().toString();
        String m = mode();
        setStatus("Mode: " + m);
        String out;
        try {
            switch (m) {
                case "Base64 Encode":
                    out = Base64.encodeToString(in.getBytes(StandardCharsets.UTF_8),
                            Base64.NO_WRAP);
                    break;
                case "Base64 Decode":
                    out = new String(Base64.decode(in.trim(), Base64.DEFAULT),
                            StandardCharsets.UTF_8);
                    break;
                case "URL Encode":
                    out = URLEncoder.encode(in, "UTF-8");
                    break;
                case "URL Decode":
                    out = URLDecoder.decode(in, "UTF-8");
                    break;
                case "Hex Encode":
                    out = hexEncode(in.getBytes(StandardCharsets.UTF_8));
                    break;
                case "Hex Decode":
                    out = new String(hexDecode(in.replaceAll("\\s", "")),
                            StandardCharsets.UTF_8);
                    break;
                case "HTML Escape":
                    out = htmlEscape(in);
                    break;
                case "HTML Unescape":
                    out = htmlUnescape(in);
                    break;
                case "JWT Decode":
                    out = jwtDecode(in.trim());
                    break;
                case "Hash Identifier":
                    out = hashId(in.trim());
                    break;
                default:
                    out = "?";
            }
        } catch (Exception e) {
            out = "Gagal: " + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "")
                    + "\n\n(Pastikan format input sesuai operasi yang dipilih.)";
        }
        setResult(out);
        setStatus("Selesai.");
    }

    // ---------- helpers ----------

    private String hexEncode(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private byte[] hexDecode(String s) throws Exception {
        if (s.length() % 2 != 0) throw new Exception("panjang hex harus genap");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    private String htmlUnescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#x27;", "'")
                .replace("&amp;", "&");
    }

    private String jwtDecode(String token) throws Exception {
        String[] parts = token.split("\\.");
        if (parts.length < 2) throw new Exception("format JWT harus header.payload[.signature]");
        String header = new String(Base64.decode(pad(parts[0]), Base64.URL_SAFE),
                StandardCharsets.UTF_8);
        String payload = new String(Base64.decode(pad(parts[1]), Base64.URL_SAFE),
                StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("— Header —\n").append(pretty(header)).append("\n\n");
        sb.append("— Payload —\n").append(pretty(payload)).append("\n");
        try {
            JSONObject p = new JSONObject(payload);
            if (p.has("exp")) {
                long exp = p.getLong("exp") * 1000L;
                SimpleDateFormat fmt = new SimpleDateFormat(
                        "dd MMM yyyy HH:mm:ss", Locale.US);
                fmt.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
                sb.append("\nExpired: ").append(fmt.format(new Date(exp)))
                        .append(" WIB");
                sb.append(exp < System.currentTimeMillis()
                        ? " (KEDALUWARSA)" : " (masih berlaku)");
            }
            if (p.has("iat")) {
                long iat = p.getLong("iat") * 1000L;
                SimpleDateFormat fmt = new SimpleDateFormat(
                        "dd MMM yyyy HH:mm:ss", Locale.US);
                fmt.setTimeZone(TimeZone.getTimeZone("Asia/Jakarta"));
                sb.append("\nIssued : ").append(fmt.format(new Date(iat))).append(" WIB");
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private String pad(String s) {
        int m = s.length() % 4;
        if (m == 2) return s + "==";
        if (m == 3) return s + "=";
        return s;
    }

    private String pretty(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return o.toString(2);
        } catch (Exception e) {
            return json;
        }
    }

    private String hashId(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append("Input: ").append(s.length()).append(" karakter\n\n");
        boolean found = false;
        if (s.matches("^[a-fA-F0-9]{32}$")) {
            sb.append("• MD5 (32 hex)\n"); found = true;
        }
        if (s.matches("^[a-fA-F0-9]{40}$")) {
            sb.append("• SHA-1 (40 hex)\n"); found = true;
        }
        if (s.matches("^[a-fA-F0-9]{56}$")) {
            sb.append("• SHA-224 (56 hex)\n"); found = true;
        }
        if (s.matches("^[a-fA-F0-9]{64}$")) {
            sb.append("• SHA-256 (64 hex)\n"); found = true;
        }
        if (s.matches("^[a-fA-F0-9]{96}$")) {
            sb.append("• SHA-384 (96 hex)\n"); found = true;
        }
        if (s.matches("^[a-fA-F0-9]{128}$")) {
            sb.append("• SHA-512 (128 hex)\n"); found = true;
        }
        if (s.matches("^\\$2[aby]\\$\\d{2}\\$.{53}$")) {
            sb.append("• bcrypt ($2a$/$2b$/$2y$)\n"); found = true;
        }
        if (s.matches("^\\$1\\$.{1,8}\\$.{22}$")) {
            sb.append("• MD5-crypt ($1$)\n"); found = true;
        }
        if (s.matches("^\\$5\\$.{1,16}\\$.{43}$")) {
            sb.append("• SHA-256-crypt ($5$)\n"); found = true;
        }
        if (s.matches("^\\$6\\$.{1,16}\\$.{86}$")) {
            sb.append("• SHA-512-crypt ($6$)\n"); found = true;
        }
        if (!found) {
            sb.append("Tidak cocok pola hash umum.\n");
            sb.append("(Catatan: identifikasi dari pola panjang/format saja —\n");
            sb.append(" tidak 100% pasti, mis. 32 hex bisa MD5 atau NTLM.)\n");
        } else {
            sb.append("\n(Catatan: ini tebakan pola, bukan verifikasi.)\n");
        }
        return sb.toString();
    }
}
