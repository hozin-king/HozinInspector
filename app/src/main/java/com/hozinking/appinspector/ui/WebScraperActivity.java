package com.hozinking.appinspector.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hozinking.appinspector.R;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLException;

/**
 * Web Scraper generik (Hozin Tools).
 * - Fetch HTML via jsoup di background thread (timeout 15 dtk, UA browser).
 * - Tampilkan judul/deskripsi/thumbnail/URL final di glass card.
 * - Mode daftar artikel: ekstrak semua &lt;a&gt; yang berisi &lt;img&gt;
 *   → thumbnail + judul + URL (RecyclerView + search).
 * - Tap item: buka di browser / salin URL / scrape URL ini.
 * - Salin semua + Export JSON (share via FileProvider, tanpa permission).
 */
public class WebScraperActivity extends BaseActivity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int MAX_ARTICLES = 200;

    private EditText etUrl, etSearch;
    private Button btnScrape, btnCopyAll, btnExport;
    private TextView tvStatus, tvPageTitle, tvPageDesc, tvPageUrl, tvPageHost,
            tvArticleCount, tvSavedCountWs;
    private ImageView ivPageThumb;
    private RecyclerView rv;
    private View cardPageInfo, cardArticles;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;
    private String query = "";

    private static class Article {
        String title, url, thumb;
        int score;
    }

    private static class PageInfo {
        String title, desc, thumb, finalUrl;
    }

    /** Pola URL yang jelas BUKAN artikel (navigasi/tag/iklan/dll). */
    private static final String[] URL_BLACKLIST = {
            "/tag/", "/tags/", "/kategori/", "/category/", "/categories/",
            "/kanal/", "/channel/", "/topik/", "/topic/", "/penulis/",
            "/author/", "/redaksi/", "/iklan", "/ads/", "/advertorial/",
            "/search", "/cari/", "/page/", "/halaman/", "/arsip/",
    };

    /** Timestamp / waktu relatif (Indonesia + Inggris) di sekitar kartu. */
    private static final java.util.regex.Pattern TIMESTAMP_RE =
            java.util.regex.Pattern.compile(
                    "(\\d{1,2}[\\s/.:-]\\d{1,2}[\\s/.:-]\\d{2,4})"
                            + "|(\\d+\\s*(menit|jam|hari|minggu|bulan|tahun)\\s*(yang\\s*)?lalu)"
                            + "|(baru\\s*saja|kemarin|today|yesterday|\\d+\\s*(minutes?|hours?|days?)\\s*ago)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** Judul generik yang biasanya bukan artikel. */
    private static final String[] GENERIC_TITLES = {
            "baca juga", "selengkapnya", "read more", "lanjutkan membaca",
            "lihat semua", "selengkapnya di sini", "klik di sini",
    };

    private final List<Article> all = new ArrayList<>();
    private PageInfo lastPage;
    // statistik scrape terakhir (transparansi)
    private int skipBlacklist, skipDup, skipEmpty;

    // cache thumbnail (8 MB), shared antar bind
    private static final LruCache<String, Bitmap> IMG_CACHE =
            new LruCache<String, Bitmap>(8 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount();
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_web_scraper);

        etUrl = findViewById(R.id.etScrapeUrl);
        etSearch = findViewById(R.id.etArticleSearch);
        btnScrape = findViewById(R.id.btnScrape);
        btnCopyAll = findViewById(R.id.btnCopyAll);
        btnExport = findViewById(R.id.btnExportJson);
        tvStatus = findViewById(R.id.tvScrapeStatus);
        tvPageTitle = findViewById(R.id.tvPageTitle);
        tvPageDesc = findViewById(R.id.tvPageDesc);
        tvPageUrl = findViewById(R.id.tvPageUrl);
        tvPageHost = findViewById(R.id.tvPageHost);
        tvArticleCount = findViewById(R.id.tvArticleCount);
        ivPageThumb = findViewById(R.id.ivPageThumb);
        rv = findViewById(R.id.rvArticles);
        cardPageInfo = findViewById(R.id.cardPageInfo);
        cardArticles = findViewById(R.id.cardArticles);
        tvSavedCountWs = findViewById(R.id.tvSavedCountWs);

        findViewById(R.id.btnOpenSaved).setOnClickListener(v ->
                startActivity(new Intent(this, SavedActivity.class)));

        rv.setLayoutManager(new LinearLayoutManager(this));

        btnScrape.setOnClickListener(v -> doScrape(etUrl.getText().toString()));
        btnCopyAll.setOnClickListener(v -> copyAll());
        btnExport.setOnClickListener(v -> exportJson());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                query = s.toString();
                if (pendingFilter != null) handler.removeCallbacks(pendingFilter);
                pendingFilter = WebScraperActivity.this::applyFilter;
                handler.postDelayed(pendingFilter, 300);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSavedCount();
    }

    private void refreshSavedCount() {
        new Thread(() -> {
            int n = 0;
            try {
                n = SavedArticleDb.getInstance(this).count();
            } catch (Exception ignored) {
            }
            final int count = n;
            handler.post(() -> tvSavedCountWs.setText(count + " artikel"));
        }).start();
    }

    // ---------- scrape ----------

    private void doScrape(String rawUrl) {
        final String url = normalize(rawUrl);
        if (url == null) {
            tvStatus.setText("URL tidak valid — contoh: https://detik.com");
            return;
        }
        btnScrape.setEnabled(false);
        tvStatus.setText("Mengambil " + url + " ...");
        cardPageInfo.setVisibility(View.GONE);
        cardArticles.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                Document doc = Jsoup.connect(url)
                        .userAgent(UA)
                        .timeout(15000)
                        .followRedirects(true)
                        .get();
                final PageInfo page = extractPage(doc);
                final List<Article> arts = extractArticles(doc);
                handler.post(() -> {
                    btnScrape.setEnabled(true);
                    lastPage = page;
                    renderPage(page);
                    all.clear();
                    all.addAll(arts);
                    applyFilter();
                    String skipped = "";
                    int totalSkip = skipBlacklist + skipDup + skipEmpty;
                    if (totalSkip > 0) {
                        skipped = " (" + totalSkip + " di-skip: "
                                + skipBlacklist + " navigasi/tag, "
                                + skipDup + " duplikat, "
                                + skipEmpty + " kosong)";
                    }
                    tvStatus.setText("Selesai — " + arts.size()
                            + " artikel ditemukan" + skipped + ".");
                });
            } catch (Exception e) {
                handler.post(() -> {
                    btnScrape.setEnabled(true);
                    tvStatus.setText(friendlyError(e));
                });
            }
        }).start();
    }

    private String normalize(String raw) {
        String u = raw == null ? "" : raw.trim();
        if (u.isEmpty()) return null;
        if (!u.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*")) {
            u = "https://" + u;
        }
        try {
            new URL(u);
            return u;
        } catch (MalformedURLException e) {
            return null;
        }
    }

    private PageInfo extractPage(Document doc) {
        PageInfo p = new PageInfo();
        Element ogTitle = doc.selectFirst("meta[property=og:title]");
        p.title = ogTitle != null && !ogTitle.attr("content").isEmpty()
                ? ogTitle.attr("content") : doc.title();
        Element ogDesc = doc.selectFirst("meta[property=og:description]");
        if (ogDesc == null) ogDesc = doc.selectFirst("meta[name=description]");
        if (ogDesc == null) ogDesc = doc.selectFirst("meta[name=twitter:description]");
        p.desc = ogDesc != null ? ogDesc.attr("content") : "";
        Element ogImg = doc.selectFirst("meta[property=og:image]");
        if (ogImg == null) ogImg = doc.selectFirst("meta[name=twitter:image]");
        p.thumb = ogImg != null ? ogImg.attr("abs:content") : "";
        p.finalUrl = doc.location();
        if (p.title == null || p.title.isEmpty()) p.title = "(tanpa judul)";
        return p;
    }

    /**
     * Ekstrak kartu artikel dengan SKORING per &lt;a href&gt; yang berisi &lt;img&gt;.
     * Skor: (a) panjang teks anchor, (b) timestamp/penulis di sekitarnya,
     * (c) blacklist pola URL navigasi/tag/iklan, (d) dedup URL ternormalisasi.
     * Hasil diurutkan by skor (tertinggi dulu), maks 200.
     */
    private List<Article> extractArticles(Document doc) {
        skipBlacklist = 0;
        skipDup = 0;
        skipEmpty = 0;
        Map<String, Article> byUrl = new LinkedHashMap<>();
        List<Article> scored = new ArrayList<>();
        Elements links = doc.select("a[href]:has(img)");
        for (Element a : links) {
            String href = a.attr("abs:href").trim();
            if (href.isEmpty() || href.startsWith("#")
                    || href.startsWith("javascript:")
                    || href.startsWith("mailto:")
                    || href.startsWith("tel:")) {
                skipEmpty++;
                continue;
            }
            String low = href.toLowerCase(Locale.US);
            boolean bad = false;
            for (String pat : URL_BLACKLIST) {
                if (low.contains(pat)) {
                    bad = true;
                    break;
                }
            }
            if (bad) {
                skipBlacklist++;
                continue;
            }
            String norm = normalizeUrl(href);
            if (byUrl.containsKey(norm)) {
                skipDup++;
                continue;
            }

            Element img = a.selectFirst("img");
            String thumb = img != null ? img.attr("abs:src").trim() : "";
            String title = img != null ? img.attr("alt").trim() : "";
            if (title.isEmpty()) title = a.attr("title").trim();
            String text = a.text().trim().replaceAll("\\s+", " ");
            if (title.isEmpty()) title = text;
            if (title.length() > 140) title = title.substring(0, 140) + "...";
            if (title.isEmpty()) {
                skipEmpty++;
                continue;
            }

            // ---- skoring ----
            int score = 0;
            // (a) panjang teks anchor: kartu artikel biasanya punya teks deskriptif
            score += Math.min(text.length(), 120);
            if (!text.isEmpty() && !title.equals(text)) score += 10;
            // (b) timestamp / penulis di konteks sekitar (parent)
            Element ctx = a.parent() != null ? a.parent() : a;
            String ctxText = ctx.text();
            if (ctxText.length() > a.text().length() + 20) {
                if (TIMESTAMP_RE.matcher(ctxText).find()) score += 40;
                if (ctx.select("[class*=author],[class*=penulis],[class*=writer],"
                        + "[class*=byline],[class*=tanggal],[class*=date],[class*=time]")
                        .first() != null) {
                    score += 25;
                }
            }
            // gambar bermakna (bukan tracker 1px / icon)
            if (!thumb.isEmpty()) {
                String tl = thumb.toLowerCase(Locale.US);
                if (tl.endsWith(".gif") || tl.contains("tracker")
                        || tl.contains("pixel") || tl.contains("1x1")) {
                    score -= 20;
                } else {
                    score += 15;
                }
            }
            // penalti judul generik / terlalu pendek
            String tlow = title.toLowerCase(Locale.US);
            for (String g : GENERIC_TITLES) {
                if (tlow.equals(g) || tlow.startsWith(g + " ")) {
                    score -= 40;
                    break;
                }
            }
            if (title.length() < 12) score -= 30;
            // bonus: URL terlihat seperti URL artikel (ada slug kata)
            String path = low.replaceFirst("^https?://[^/]+", "");
            if (path.split("-").length >= 3 && path.length() > 20) score += 10;

            Article art = new Article();
            art.title = title;
            art.url = href;
            art.thumb = thumb;
            art.score = score;
            byUrl.put(norm, art);
            scored.add(art);
        }
        // urut skor tertinggi dulu, potong 200
        scored.sort((x, y) -> Integer.compare(y.score, x.score));
        if (scored.size() > MAX_ARTICLES) {
            scored = scored.subList(0, MAX_ARTICLES);
        }
        return scored;
    }

    /** Normalisasi URL untuk dedup: buang fragment + param tracking umum. */
    private String normalizeUrl(String href) {
        try {
            URL u = new URL(href);
            String query = u.getQuery();
            String kept = "";
            if (query != null) {
                StringBuilder sb = new StringBuilder();
                for (String kv : query.split("&")) {
                    String k = kv.split("=", 2)[0].toLowerCase(Locale.US);
                    if (k.startsWith("utm_") || k.equals("fbclid") || k.equals("gclid")
                            || k.equals("_ga") || k.equals("ref") || k.equals("output")) {
                        continue;
                    }
                    if (sb.length() > 0) sb.append('&');
                    sb.append(kv);
                }
                kept = sb.toString();
            }
            String path = u.getPath();
            if (path.endsWith("/") && path.length() > 1) {
                path = path.substring(0, path.length() - 1);
            }
            return (u.getProtocol() + "://" + u.getHost()
                    + (u.getPort() != -1 ? ":" + u.getPort() : "")
                    + path + (kept.isEmpty() ? "" : "?" + kept))
                    .toLowerCase(Locale.US);
        } catch (Exception e) {
            return href.toLowerCase(Locale.US);
        }
    }

    private void renderPage(PageInfo p) {
        cardPageInfo.setVisibility(View.VISIBLE);
        tvPageTitle.setText(p.title);
        tvPageDesc.setText(p.desc.isEmpty() ? "(tidak ada deskripsi)" : p.desc);
        tvPageUrl.setText(p.finalUrl);
        tvPageUrl.setPaintFlags(tvPageUrl.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        // URL halaman bisa di-tap → buka di browser
        tvPageUrl.setOnClickListener(v -> openBrowser(p.finalUrl));
        // badge subdomain/host, mis. finance.detik.com
        String host = hostOf(p.finalUrl);
        if (!host.isEmpty()) {
            tvPageHost.setText(host);
            tvPageHost.setVisibility(View.VISIBLE);
        } else {
            tvPageHost.setVisibility(View.GONE);
        }
        loadThumb(ivPageThumb, p.thumb);
    }

    /** Host dari URL (tanpa www.), "" bila tidak valid. */
    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = new URL(url).getHost();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    /** Buka URL di browser eksternal. */
    private void openBrowser(String url) {
        if (url == null || url.trim().isEmpty()) {
            toast("URL kosong");
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            toast("Tidak bisa membuka browser");
        }
    }

    // ---------- filter + list ----------

    private void applyFilter() {
        String q = query.toLowerCase(Locale.US).trim();
        List<Article> shown = new ArrayList<>();
        for (Article a : all) {
            if (!q.isEmpty()
                    && !a.title.toLowerCase(Locale.US).contains(q)
                    && !a.url.toLowerCase(Locale.US).contains(q)) {
                continue;
            }
            shown.add(a);
        }
        cardArticles.setVisibility(View.VISIBLE);
        tvArticleCount.setText(shown.size() + " artikel"
                + (q.isEmpty() ? "" : " (filter: " + query.trim() + ")"));
        rv.setAdapter(new ArticleAdapter(shown));
    }

    private class ArticleAdapter extends RecyclerView.Adapter<ArticleAdapter.H> {
        private final List<Article> items;

        ArticleAdapter(List<Article> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_article, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            Article a = items.get(pos);
            h.title.setText(a.title);
            h.url.setText(a.url);
            h.url.setPaintFlags(h.url.getPaintFlags()
                    | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            // badge host/subdomain per item, mis. finance.detik.com
            String host = hostOf(a.url);
            if (!host.isEmpty()) {
                h.host.setText(host);
                h.host.setVisibility(View.VISIBLE);
            } else {
                h.host.setVisibility(View.GONE);
            }
            loadThumb(h.thumb, a.thumb);
            // tap baris = buka di Reader; tap URL = buka di browser;
            // tahan = menu (browser/salin/scrape)
            h.itemView.setOnClickListener(v -> openReader(a));
            h.url.setOnClickListener(v -> openBrowser(a.url));
            h.itemView.setOnLongClickListener(v -> {
                showArticleMenu(a);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class H extends RecyclerView.ViewHolder {
            ImageView thumb;
            TextView title, url, host;

            H(View v) {
                super(v);
                thumb = v.findViewById(R.id.ivArtThumb);
                title = v.findViewById(R.id.tvArtTitle);
                url = v.findViewById(R.id.tvArtUrl);
                host = v.findViewById(R.id.tvArtHost);
            }
        }
    }

    private void showArticleMenu(Article a) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(a.title)
                .setItems(new CharSequence[]{
                        "Buka di Reader", "Buka di browser", "Salin URL", "Scrape URL ini"
                }, (d, which) -> {
                    if (which == 0) {
                        openReader(a);
                    } else if (which == 1) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(a.url)));
                        } catch (Exception e) {
                            toast("Tidak bisa membuka: " + e.getMessage());
                        }
                    } else if (which == 2) {
                        ClipboardManager cm = (ClipboardManager)
                                getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("url", a.url));
                        toast("URL disalin");
                    } else {
                        etUrl.setText(a.url);
                        doScrape(a.url);
                    }
                })
                .show();
    }

    /** Buka artikel di ReaderActivity (ekstrak isi bersih via readability4j). */
    private void openReader(Article a) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.EXTRA_URL, a.url);
        // kirim daftar artikel untuk section "Artikel terkait" di reader
        i.putExtra(ReaderActivity.EXTRA_RELATED_JSON, buildRelatedJson());
        startActivity(i);
    }

    /** Bangun JSON related dari hasil scrape (maks 60, hemat ukuran intent). */
    private String buildRelatedJson() {
        List<ReaderActivity.Related> list = new ArrayList<>();
        int n = Math.min(all.size(), 60);
        for (int k = 0; k < n; k++) {
            Article x = all.get(k);
            ReaderActivity.Related r = new ReaderActivity.Related();
            r.title = x.title;
            r.url = x.url;
            r.thumb = x.thumb;
            r.savedId = -1;
            list.add(r);
        }
        return ReaderActivity.buildRelatedJson(list);
    }

    // ---------- thumbnail loader (background, LruCache) ----------

    private void loadThumb(ImageView iv, String url) {
        if (url == null || url.isEmpty() || url.startsWith("data:")) {
            iv.setImageDrawable(null);
            iv.setTag(null);
            return;
        }
        Bitmap cached = IMG_CACHE.get(url);
        if (cached != null) {
            iv.setTag(url);
            iv.setImageBitmap(cached);
            return;
        }
        iv.setTag(url);
        iv.setImageDrawable(null);
        new Thread(() -> {
            try {
                HttpURLConnection c =
                        (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", UA);
                c.setInstanceFollowRedirects(true);
                Bitmap bm = BitmapFactory.decodeStream(c.getInputStream());
                c.disconnect();
                if (bm == null) return;
                // downscale bila raksasa (hemat memori)
                int w = bm.getWidth(), h = bm.getHeight();
                if (w > 1024 || h > 1024) {
                    float s = Math.min(1024f / w, 1024f / h);
                    Bitmap small = Bitmap.createScaledBitmap(bm,
                            Math.max(1, (int) (w * s)),
                            Math.max(1, (int) (h * s)), true);
                    if (small != bm) bm.recycle();
                    bm = small;
                }
                IMG_CACHE.put(url, bm);
                final Bitmap fbm = bm;
                handler.post(() -> {
                    if (url.equals(iv.getTag())) iv.setImageBitmap(fbm);
                });
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ---------- salin semua ----------

    private void copyAll() {
        if (all.isEmpty()) {
            toast("Belum ada hasil — scrape dulu");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Article a : all) {
            sb.append(a.title).append('\n').append(a.url).append("\n\n");
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("scrape", sb.toString().trim()));
        toast(all.size() + " artikel disalin");
    }

    // ---------- export JSON (share via FileProvider, tanpa permission) ----------

    private void exportJson() {
        if (lastPage == null && all.isEmpty()) {
            toast("Belum ada hasil — scrape dulu");
            return;
        }
        btnExport.setEnabled(false);
        tvStatus.setText("Menyiapkan JSON...");
        final PageInfo page = lastPage;
        final List<Article> arts = new ArrayList<>(all);
        new Thread(() -> {
            try {
                JSONObject root = new JSONObject();
                root.put("scraped_at", new SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
                if (page != null) {
                    root.put("url", page.finalUrl);
                    root.put("title", page.title);
                    root.put("description", page.desc);
                    root.put("thumbnail", page.thumb);
                }
                JSONArray arr = new JSONArray();
                for (Article a : arts) {
                    JSONObject o = new JSONObject();
                    o.put("title", a.title);
                    o.put("url", a.url);
                    o.put("thumbnail", a.thumb == null ? "" : a.thumb);
                    arr.put(o);
                }
                root.put("articles", arr);

                File dir = new File(getExternalFilesDir("scrapes"), "");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new Exception("gagal buat folder export");
                }
                String name = "scrape_" + new SimpleDateFormat(
                        "yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".json";
                File f = new File(dir, name);
                Files.write(f.toPath(),
                        root.toString(2).getBytes(StandardCharsets.UTF_8));

                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", f);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/json");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                handler.post(() -> {
                    btnExport.setEnabled(true);
                    tvStatus.setText("JSON tersimpan: " + f.getAbsolutePath());
                    startActivity(Intent.createChooser(share, "Bagikan JSON"));
                });
            } catch (Exception e) {
                handler.post(() -> {
                    btnExport.setEnabled(true);
                    tvStatus.setText("Export gagal: " + e.getMessage());
                });
            }
        }).start();
    }

    // ---------- error mapping ----------

    private String friendlyError(Exception e) {
        if (e instanceof UnknownHostException) {
            return "Host tidak ditemukan — cek URL / koneksi internet.";
        }
        if (e instanceof SocketTimeoutException) {
            return "Timeout 15 detik — server lambat / tidak merespons.";
        }
        if (e instanceof HttpStatusException) {
            int c = ((HttpStatusException) e).getStatusCode();
            String extra = c == 403 ? " — akses ditolak (anti-scrape?)"
                    : c == 404 ? " — halaman tidak ada"
                    : "";
            return "HTTP " + c + extra;
        }
        if (e instanceof UnsupportedMimeTypeException) {
            return "Bukan halaman HTML: "
                    + ((UnsupportedMimeTypeException) e).getMimeType();
        }
        if (e instanceof SSLException) {
            return "Gagal TLS/SSL: " + e.getMessage();
        }
        if (e instanceof MalformedURLException) {
            return "URL tidak valid.";
        }
        if (e instanceof IllegalArgumentException) {
            return "URL tidak valid: " + e.getMessage();
        }
        String msg = e.getMessage();
        return "Gagal: " + e.getClass().getSimpleName()
                + (msg != null ? " — " + msg : "");
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
