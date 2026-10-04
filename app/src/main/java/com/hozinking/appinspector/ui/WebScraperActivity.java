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
import androidx.appcompat.app.AppCompatActivity;
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
public class WebScraperActivity extends AppCompatActivity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    private static final int MAX_ARTICLES = 200;

    private EditText etUrl, etSearch;
    private Button btnScrape, btnCopyAll, btnExport;
    private TextView tvStatus, tvPageTitle, tvPageDesc, tvPageUrl, tvArticleCount;
    private ImageView ivPageThumb;
    private RecyclerView rv;
    private View cardPageInfo, cardArticles;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingFilter;
    private String query = "";

    private static class Article {
        String title, url, thumb;
    }

    private static class PageInfo {
        String title, desc, thumb, finalUrl;
    }

    private final List<Article> all = new ArrayList<>();
    private PageInfo lastPage;

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
        tvArticleCount = findViewById(R.id.tvArticleCount);
        ivPageThumb = findViewById(R.id.ivPageThumb);
        rv = findViewById(R.id.rvArticles);
        cardPageInfo = findViewById(R.id.cardPageInfo);
        cardArticles = findViewById(R.id.cardArticles);

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
                    tvStatus.setText("Selesai — " + arts.size() + " artikel ditemukan.");
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
     * Ekstrak kartu artikel: semua &lt;a href&gt; yang berisi &lt;img&gt;.
     * Judul: alt gambar → atribut title → teks link. Dedup by URL, maks 200.
     */
    private List<Article> extractArticles(Document doc) {
        Map<String, Article> byUrl = new LinkedHashMap<>();
        Elements links = doc.select("a[href]:has(img)");
        for (Element a : links) {
            if (byUrl.size() >= MAX_ARTICLES) break;
            String href = a.attr("abs:href").trim();
            if (href.isEmpty() || href.startsWith("#")
                    || href.startsWith("javascript:")
                    || href.startsWith("mailto:")
                    || href.startsWith("tel:")) {
                continue;
            }
            if (byUrl.containsKey(href)) continue;
            Element img = a.selectFirst("img");
            String thumb = img != null ? img.attr("abs:src").trim() : "";
            String title = img != null ? img.attr("alt").trim() : "";
            if (title.isEmpty()) title = a.attr("title").trim();
            if (title.isEmpty()) title = a.text().trim().replaceAll("\\s+", " ");
            if (title.length() > 140) title = title.substring(0, 140) + "...";
            if (title.isEmpty()) title = "(tanpa judul)";
            Article art = new Article();
            art.title = title;
            art.url = href;
            art.thumb = thumb;
            byUrl.put(href, art);
        }
        return new ArrayList<>(byUrl.values());
    }

    private void renderPage(PageInfo p) {
        cardPageInfo.setVisibility(View.VISIBLE);
        tvPageTitle.setText(p.title);
        tvPageDesc.setText(p.desc.isEmpty() ? "(tidak ada deskripsi)" : p.desc);
        tvPageUrl.setText(p.finalUrl);
        loadThumb(ivPageThumb, p.thumb);
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
            loadThumb(h.thumb, a.thumb);
            h.itemView.setOnClickListener(v -> showArticleMenu(a));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class H extends RecyclerView.ViewHolder {
            ImageView thumb;
            TextView title, url;

            H(View v) {
                super(v);
                thumb = v.findViewById(R.id.ivArtThumb);
                title = v.findViewById(R.id.tvArtTitle);
                url = v.findViewById(R.id.tvArtUrl);
            }
        }
    }

    private void showArticleMenu(Article a) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(a.title)
                .setItems(new CharSequence[]{
                        "Buka di browser", "Salin URL", "Scrape URL ini"
                }, (d, which) -> {
                    if (which == 0) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse(a.url)));
                        } catch (Exception e) {
                            toast("Tidak bisa membuka: " + e.getMessage());
                        }
                    } else if (which == 1) {
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
