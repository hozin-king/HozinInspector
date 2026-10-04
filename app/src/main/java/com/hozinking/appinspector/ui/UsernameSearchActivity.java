package com.hozinking.appinspector.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * Username Search (Hozin Tools) — cek keberadaan username di puluhan situs
 * (database Sherlock Project, MIT) untuk keperluan OSINT.
 *
 * - Database situs dari res/raw/sherlock_sites.json (sekali load).
 * - Tiap situs dicek 1 task dalam thread pool 6 worker, timeout 10 detik,
 *   jeda 200ms sebelum tiap request (hormati rate-limit situs).
 * - Deteksi mengikuti skema Sherlock asli (status_code / message / response_url).
 * - Hasil tampil bertahap (per hasil, bukan tunggu semua selesai).
 * - Semua network di background thread; UI di-update via Handler.
 */
public class UsernameSearchActivity extends BaseActivity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int TIMEOUT_MS = 10000;
    private static final int MAX_BODY = 200 * 1024; // 200KB

    private enum Status { FOUND, UNKNOWN, NOT_FOUND }

    private static class SiteInfo {
        String name, url, urlMain, errorType, errorUrl, regexCheck;
        Integer errorCode;
        List<String> errorMsgs = new ArrayList<>();
    }

    private static class Result {
        SiteInfo site;
        Status status;
        String reason; // hanya untuk UNKNOWN
        String finalUrl;

        Result(SiteInfo site, Status status, String reason, String finalUrl) {
            this.site = site;
            this.status = status;
            this.reason = reason;
            this.finalUrl = finalUrl;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private EditText etUsername;
    private Button btnCheck, btnCancel;
    private ProgressBar progressScan;
    private TextView tvProgressStatus, tvResultSummary;
    private RecyclerView rvSites;
    private SiteAdapter adapter;

    private final List<SiteInfo> sites = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();

    private ExecutorService pool;
    private int total, done;
    private boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_username_search);

        etUsername = findViewById(R.id.etUsername);
        btnCheck = findViewById(R.id.btnCheck);
        btnCancel = findViewById(R.id.btnCancel);
        progressScan = findViewById(R.id.progressScan);
        tvProgressStatus = findViewById(R.id.tvProgressStatus);
        tvResultSummary = findViewById(R.id.tvResultSummary);
        rvSites = findViewById(R.id.rvSites);

        adapter = new SiteAdapter(results);
        rvSites.setLayoutManager(new LinearLayoutManager(this));
        rvSites.setAdapter(adapter);

        new Thread(() -> {
            final boolean ok = loadSites();
            handler.post(() -> {
                btnCheck.setEnabled(ok);
                if (!ok) tvProgressStatus.setText("Gagal membaca database situs.");
            });
        }).start();

        btnCheck.setOnClickListener(v -> startScan());
        btnCancel.setOnClickListener(v -> cancelScan());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (pool != null) pool.shutdownNow();
    }

    // ---------- database situs ----------

    private boolean loadSites() {
        try {
            InputStream in = getResources().openRawResource(R.raw.sherlock_sites);
            byte[] data = readAll(in);
            in.close();
            JSONArray arr = new JSONArray(new String(data, StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                SiteInfo s = new SiteInfo();
                s.name = o.optString("name", "?");
                s.url = o.optString("url", "");
                s.urlMain = o.optString("urlMain", "");
                s.errorType = o.optString("errorType", "status_code");
                s.errorUrl = o.isNull("errorUrl") ? null : o.optString("errorUrl", null);
                s.regexCheck = o.isNull("regexCheck") ? null : o.optString("regexCheck", null);
                if (!o.isNull("errorCode")) {
                    s.errorCode = o.optInt("errorCode");
                }
                if (!o.isNull("errorMsg")) {
                    Object m = o.get("errorMsg");
                    if (m instanceof String) {
                        s.errorMsgs.add((String) m);
                    } else if (m instanceof JSONArray) {
                        JSONArray ja = (JSONArray) m;
                        for (int j = 0; j < ja.length(); j++) {
                            s.errorMsgs.add(ja.optString(j, ""));
                        }
                    }
                }
                sites.add(s);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    // ---------- scan ----------

    private void startScan() {
        String u = etUsername.getText().toString().replace(" ", "").trim();
        etUsername.setText(u);
        if (u.length() < 1 || u.length() > 39 || !u.matches("[A-Za-z0-9._-]+")) {
            toast("Username tidak valid — 1–39 karakter, huruf/angka/._- saja");
            return;
        }
        if (running) return;

        results.clear();
        adapter.notifyDataSetChanged();
        total = sites.size();
        done = 0;
        running = true;

        progressScan.setMax(total);
        progressScan.setProgress(0);
        progressScan.setVisibility(View.VISIBLE);
        tvProgressStatus.setText("0/" + total + " ...");
        tvResultSummary.setText("Memeriksa \"" + u + "\" ...");
        btnCheck.setEnabled(false);
        btnCancel.setVisibility(View.VISIBLE);

        final String username = u;
        pool = Executors.newFixedThreadPool(6);
        for (final SiteInfo site : sites) {
            // regexCheck: situs dilewati bila username tidak cocok pola situs
            if (site.regexCheck != null && !site.regexCheck.isEmpty()) {
                try {
                    if (!Pattern.compile(site.regexCheck).matcher(username).find()) {
                        onSkipped();
                        continue;
                    }
                } catch (Exception e) {
                    // regex rusak di database → perlakukan sebagai tidak cocok
                    onSkipped();
                    continue;
                }
            }
            pool.execute(() -> scanSite(site, username));
        }
    }

    private void scanSite(SiteInfo site, String username) {
        // Jeda 200ms antar request — hormati rate-limit situs.
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            return;
        }
        if (Thread.currentThread().isInterrupted() || !running) return;
        final Result r = probe(site, username);
        if (Thread.currentThread().isInterrupted() || !running) return;
        handler.post(() -> {
            if (!running) return;
            onResult(r);
        });
    }

    private void onSkipped() {
        handler.post(() -> {
            if (!running) return;
            done++;
            progressScan.setProgress(done);
            tvProgressStatus.setText(done + "/" + total + " ...");
            if (done >= total) finishScan("Selesai.");
        });
    }

    private void onResult(Result r) {
        done++;
        results.add(r);
        Collections.sort(results, new Comparator<Result>() {
            @Override
            public int compare(Result a, Result b) {
                return rank(a.status) - rank(b.status);
            }
            private int rank(Status s) {
                switch (s) {
                    case FOUND: return 0;
                    case UNKNOWN: return 1;
                    default: return 2;
                }
            }
        });
        adapter.notifyDataSetChanged();
        progressScan.setProgress(done);
        tvProgressStatus.setText(done + "/" + total + " ...");
        updateSummary();
        if (done >= total) finishScan("Selesai.");
    }

    private void updateSummary() {
        int found = 0, unknown = 0;
        for (Result r : results) {
            if (r.status == Status.FOUND) found++;
            else if (r.status == Status.UNKNOWN) unknown++;
        }
        tvResultSummary.setText("Ditemukan di " + found + " situs"
                + (unknown > 0 ? " • " + unknown + " belum pasti" : ""));
    }

    private void finishScan(String msg) {
        running = false;
        progressScan.setVisibility(View.GONE);
        tvProgressStatus.setText(msg);
        btnCheck.setEnabled(true);
        btnCancel.setVisibility(View.GONE);
        if (pool != null) pool.shutdownNow();
    }

    private void cancelScan() {
        if (pool != null) pool.shutdownNow();
        finishScan("Dibatalkan pengguna — " + done + "/" + total + " selesai.");
    }

    // ---------- engine: 1 request per situs ----------

    private Result probe(SiteInfo site, String username) {
        final String profileUrl;
        try {
            profileUrl = site.url.replace("{}",
                    URLEncoder.encode(username, StandardCharsets.UTF_8.name()));
        } catch (Exception e) {
            return new Result(site, Status.UNKNOWN, "URL tidak valid", "");
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(profileUrl).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            String finalUrl = c.getURL().toString();
            String body = readBody(c, MAX_BODY);
            return classify(site, code, finalUrl, body, profileUrl);
        } catch (SocketTimeoutException e) {
            return new Result(site, Status.UNKNOWN, "timeout", profileUrl);
        } catch (UnknownHostException e) {
            return new Result(site, Status.UNKNOWN, "host tidak ditemukan", profileUrl);
        } catch (SSLException e) {
            return new Result(site, Status.UNKNOWN, "gagal TLS", profileUrl);
        } catch (Exception e) {
            return new Result(site, Status.UNKNOWN, e.getClass().getSimpleName(), profileUrl);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readBody(HttpURLConnection c, int maxBytes) {
        InputStream in = null;
        try {
            try {
                in = c.getInputStream();
            } catch (Exception e) {
                in = c.getErrorStream();
            }
            if (in == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                if (total + n > maxBytes) {
                    bos.write(buf, 0, maxBytes - total);
                    break;
                }
                bos.write(buf, 0, n);
                total += n;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Deteksi sesuai skema Sherlock (jujur: tidak menebak di luar aturan).
     * UNKNOWN dipakai bila bukti tidak cukup / respons tak terduga.
     */
    private Result classify(SiteInfo s, int code, String finalUrl, String body,
                            String profileUrl) {
        String shown = (finalUrl != null && !finalUrl.isEmpty()) ? finalUrl : profileUrl;
        if (code == 429) {
            return new Result(s, Status.UNKNOWN, "HTTP 429 — dibatasi situs", shown);
        }
        if (code == 403) {
            return new Result(s, Status.UNKNOWN, "HTTP 403 — ditolak (bot-check?)", shown);
        }
        switch (s.errorType) {
            case "status_code":
                if (s.errorCode != null) {
                    if (code == s.errorCode) return nf(s, shown);
                    return okRange(code) ? ok(s, shown) : unk(s, "HTTP " + code, shown);
                }
                if (okRange(code)) return ok(s, shown);
                if (code == 404 || code == 410) return nf(s, shown);
                return unk(s, "HTTP " + code, shown);
            case "message":
                if (body == null) return unk(s, "body tidak terbaca", shown);
                for (String m : s.errorMsgs) {
                    if (m != null && !m.isEmpty() && body.contains(m)) {
                        return nf(s, shown);
                    }
                }
                if (okRange(code)) return ok(s, shown);
                return unk(s, "HTTP " + code, shown);
            case "response_url":
                if (s.errorUrl != null && !s.errorUrl.isEmpty()
                        && finalUrl != null && finalUrl.contains(s.errorUrl)) {
                    return nf(s, shown);
                }
                if (okRange(code)) return ok(s, shown);
                return unk(s, "HTTP " + code, shown);
            default:
                return unk(s, "errorType tak dikenal", shown);
        }
    }

    private boolean okRange(int code) {
        return code >= 200 && code <= 299;
    }

    private Result ok(SiteInfo s, String shown) {
        return new Result(s, Status.FOUND, null, shown);
    }

    private Result nf(SiteInfo s, String shown) {
        return new Result(s, Status.NOT_FOUND, null, shown);
    }

    private Result unk(SiteInfo s, String reason, String shown) {
        return new Result(s, Status.UNKNOWN, reason, shown);
    }

    // ---------- adapter ----------

    private class SiteAdapter extends RecyclerView.Adapter<SiteAdapter.H> {
        private final List<Result> items;

        SiteAdapter(List<Result> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_site, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            Result r = items.get(pos);
            h.name.setText(r.site.name);
            h.url.setText(r.finalUrl);
            switch (r.status) {
                case FOUND:
                    h.badge.setText("DITEMUKAN");
                    h.badge.setTextColor(0xFF4CAF50);
                    break;
                case NOT_FOUND:
                    h.badge.setText("TIDAK ADA");
                    h.badge.setTextColor(0xFF9E9E9E);
                    break;
                default:
                    h.badge.setText("UNKNOWN" + (r.reason != null ? " • " + r.reason : ""));
                    h.badge.setTextColor(0xFFFFC107);
                    break;
            }
            h.itemView.setOnClickListener(v -> {
                if (r.status == Status.FOUND && r.finalUrl != null
                        && !r.finalUrl.isEmpty()) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse(r.finalUrl)));
                    } catch (Exception e) {
                        toast("Tidak bisa membuka: " + e.getMessage());
                    }
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class H extends RecyclerView.ViewHolder {
            TextView name, url, badge;

            H(View v) {
                super(v);
                name = v.findViewById(R.id.tvSiteName);
                url = v.findViewById(R.id.tvSiteUrl);
                badge = v.findViewById(R.id.tvSiteBadge);
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
