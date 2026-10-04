package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.net.URL;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

/**
 * SSL Checker — info sertifikat TLS: subject, issuer, SAN,
 * masa berlaku, sisa hari + warning bila < 30 hari / kedaluwarsa.
 */
public class SslCheckActivity extends PentestToolActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("SSL Checker",
                "Baca sertifikat TLS server: issuer, SAN, masa berlaku.");
        setInput("Host", "mis. example.com (tanpa https://)");
    }

    @Override
    protected void onRun() {
        String host = hostOf(input());
        if (host.isEmpty()) {
            setResult("Host tidak valid.");
            return;
        }
        setStatus("Handshake TLS ke " + host + ":443 ...");
        StringBuilder sb = new StringBuilder();
        sb.append("Host: ").append(host).append("\n\n");
        HttpsURLConnection c = null;
        try {
            c = (HttpsURLConnection) new URL("https://" + host + ":443/").openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", UA);
            c.connect();
            Certificate[] certs = c.getServerCertificates();
            if (certs == null || certs.length == 0 || !(certs[0] instanceof X509Certificate)) {
                sb.append("Tidak mendapat sertifikat X.509 dari server.\n");
            } else {
                X509Certificate x = (X509Certificate) certs[0];
                SimpleDateFormat fmt =
                        new SimpleDateFormat("dd MMM yyyy HH:mm", Locale.US);
                sb.append("— Sertifikat utama —\n");
                sb.append("Subject : ").append(dn(x.getSubjectDN().getName())).append("\n");
                sb.append("Issuer  : ").append(dn(x.getIssuerDN().getName())).append("\n");
                sb.append("Berlaku : ").append(fmt.format(x.getNotBefore()))
                        .append(" → ").append(fmt.format(x.getNotAfter())).append("\n");
                long days = TimeUnit.MILLISECONDS.toDays(
                        x.getNotAfter().getTime() - new Date().getTime());
                sb.append("Sisa    : ").append(days).append(" hari\n");
                if (days < 0) {
                    sb.append("⚠ SERTIFIKAT KEDALUWARSA!\n");
                } else if (days < 30) {
                    sb.append("⚠ Sertifikat hampir kedaluwarsa (< 30 hari).\n");
                }
                try {
                    Collection<List<?>> sans = x.getSubjectAlternativeNames();
                    if (sans != null && !sans.isEmpty()) {
                        sb.append("\n— Subject Alternative Names (")
                                .append(sans.size()).append(") —\n");
                        int n = 0;
                        for (List<?> san : sans) {
                            if (n++ >= 30) {
                                sb.append("... (+").append(sans.size() - 30)
                                        .append(" lainnya)\n");
                                break;
                            }
                            sb.append("• ").append(san.get(1)).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                }
                sb.append("\nCipher  : ").append(c.getCipherSuite()).append("\n");
                sb.append("Chain   : ").append(certs.length).append(" sertifikat\n");
            }
        } catch (Exception e) {
            sb.append("Gagal TLS: ").append(e.getClass().getSimpleName())
                    .append(e.getMessage() != null ? ": " + e.getMessage() : "")
                    .append("\n");
        } finally {
            if (c != null) c.disconnect();
        }
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    /** Ambil CN=... dari DN, fallback ke DN penuh bila tidak ada. */
    private String dn(String full) {
        for (String part : full.split(",")) {
            String p = part.trim();
            if (p.startsWith("CN=")) return p.substring(3);
        }
        return full;
    }
}
