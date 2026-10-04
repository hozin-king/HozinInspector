package com.hozinking.appinspector.ui;

import android.os.Bundle;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Port Scanner — TCP connect scan ke daftar port umum (atau custom),
 * thread pool, timeout 1,5 dtk/port. Klasifikasi: TERBUKA / tertutup / timeout.
 */
public class PortScanActivity extends PentestToolActivity {

    private static final int TIMEOUT_MS = 1500;
    private static final int[] COMMON_PORTS = {
            21, 22, 23, 25, 53, 80, 110, 143, 443, 445,
            993, 995, 3306, 3389, 5432, 5900, 6379, 8080, 8443, 27017
    };

    private static final Map<Integer, String> SERVICES = new LinkedHashMap<>();
    static {
        SERVICES.put(21, "FTP"); SERVICES.put(22, "SSH"); SERVICES.put(23, "Telnet");
        SERVICES.put(25, "SMTP"); SERVICES.put(53, "DNS"); SERVICES.put(80, "HTTP");
        SERVICES.put(110, "POP3"); SERVICES.put(143, "IMAP"); SERVICES.put(443, "HTTPS");
        SERVICES.put(445, "SMB"); SERVICES.put(993, "IMAPS"); SERVICES.put(995, "POP3S");
        SERVICES.put(3306, "MySQL"); SERVICES.put(3389, "RDP");
        SERVICES.put(5432, "PostgreSQL"); SERVICES.put(5900, "VNC");
        SERVICES.put(6379, "Redis"); SERVICES.put(8080, "HTTP-alt");
        SERVICES.put(8443, "HTTPS-alt"); SERVICES.put(27017, "MongoDB");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupTool("Port Scanner",
                "TCP connect scan. Timeout 1,5 dtk/port, 8 worker paralel.");
        setInput("Host target", "mis. example.com atau 93.184.216.34");
        setExtra("Port custom (opsional)", "mis. 80,443,8080 — kosongkan = 20 port umum");
    }

    @Override
    protected void onRun() {
        String host = input().replaceAll("(?i)^https?://", "").split("/")[0].trim();
        if (host.isEmpty()) {
            setResult("Host tidak valid.");
            return;
        }
        int[] ports = parsePorts(extra());
        if (ports == null) {
            setResult("Format port salah — pakai angka dipisah koma, mis. 80,443,8080");
            return;
        }

        setStatus("Memindai " + ports.length + " port di " + host + " ...");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<String> open = new ArrayList<>();
        List<String> closed = new ArrayList<>();
        AtomicInteger timeouts = new AtomicInteger(0);
        AtomicInteger done = new AtomicInteger(0);
        final int total = ports.length;

        for (int port : ports) {
            final int p = port;
            pool.execute(() -> {
                int res = probe(host, p); // 0=open, 1=closed, 2=timeout
                synchronized (open) {
                    String svc = SERVICES.getOrDefault(p, "unknown");
                    if (res == 0) open.add(p + " (" + svc + ") — TERBUKA");
                    else if (res == 1) closed.add(p + " (" + svc + ") — tertutup");
                    else timeouts.incrementAndGet();
                }
                int d = done.incrementAndGet();
                setStatus("Progres: " + d + "/" + total);
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(3, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            setStatus("Dibatalkan.");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Host: ").append(host).append("\n");
        sb.append("Port dipindai: ").append(total).append("\n\n");
        sb.append("— TERBUKA (").append(open.size()).append(") —\n");
        if (open.isEmpty()) sb.append("(tidak ada)\n");
        for (String s : open) sb.append("• ").append(s).append("\n");
        sb.append("\n— Tertutup: ").append(closed.size())
                .append(" • Timeout/filtered: ").append(timeouts.get()).append("\n");
        setResult(sb.toString());
        setStatus("Selesai.");
    }

    /** @return 0=open, 1=closed(refused), 2=timeout/error */
    private int probe(String host, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            return 0;
        } catch (SocketTimeoutException e) {
            return 2;
        } catch (ConnectException e) {
            return 1; // connection refused = port tertutup
        } catch (Exception e) {
            return 2;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private int[] parsePorts(String raw) {
        if (raw.isEmpty()) return COMMON_PORTS;
        try {
            String[] parts = raw.split(",");
            int[] out = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                int p = Integer.parseInt(parts[i].trim());
                if (p < 1 || p > 65535) return null;
                out[i] = p;
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
