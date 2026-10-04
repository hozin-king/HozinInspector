package com.hozinking.appinspector.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hozinking.appinspector.R;

import net.dankito.readability4j.Readability4J;
import net.dankito.readability4j.Article;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Layar baca artikel (Hozin Tools).
 *
 * Kontrak intent extras:
 * - EXTRA_URL ("url"): fetch live dari URL ini.
 * - EXTRA_SAVED_ID ("saved_id", long, default -1): bila >= 0 baca dari
 *   SavedArticleDb (mode offline, tanpa network sama sekali).
 * - EXTRA_HTML ("html", opsional): HTML yang sudah di-fetch pemanggil,
 *   dipakai bila ada agar hemat 1 request.
 *
 * Fetch + parse Readability4J jalan di background thread; UI di-update
 * via Handler(Looper.getMainLooper()).
 */
public class ReaderActivity extends BaseActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_SAVED_ID = "saved_id";
    public static final String EXTRA_HTML = "html";
    /**
     * EXTRA_RELATED_JSON: JSONArray string [{t:title,u:url,h:thumb,s:savedId}].
     * Dipakai untuk section "Artikel terkait" di bawah reader.
     */
    public static final String EXTRA_RELATED_JSON = "related_json";

    /** Satu kandidat artikel terkait. */
    public static class Related {
        public String title, url, thumb;
        public long savedId = -1;
    }

    /** Bangun JSON related dari list (dipakai pemanggil sebelum startActivity). */
    public static String buildRelatedJson(List<Related> items) {
        try {
            JSONArray arr = new JSONArray();
            for (Related r : items) {
                if (r == null) continue;
                JSONObject o = new JSONObject();
                o.put("t", r.title != null ? r.title : "");
                o.put("u", r.url != null ? r.url : "");
                o.put("h", r.thumb != null ? r.thumb : "");
                o.put("s", r.savedId);
                arr.put(o);
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ProgressBar progress;
    private TextView tvError;
    private View cardReader;
    private TextView tvTitle, tvByline, tvExcerpt, tvBody, tvOfflineInfo, tvUrl;
    private ImageView ivHero;
    private Button btnSave, btnExport;
    private View cardRelated;
    private RecyclerView rvRelated;
    private View svImages;
    private LinearLayout llArticleImages;
    private TextView tvImagesLabel;

    // Artikel yang sedang tampil — dipakai tombol SIMPAN OFFLINE & EKSPOR.
    private String artTitle, artUrl, artByline, artExcerpt, artText, artHeroUrl;
    /** Path thumbnail lokal (mode offline) — untuk image viewer. */
    private String artHeroPath;
    /** URL gambar di dalam isi artikel (mode live) — untuk strip + viewer. */
    private List<String> artImages = new ArrayList<>();
    private long artDate;
    private String relatedJson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        progress = findViewById(R.id.progressReader);
        tvError = findViewById(R.id.tvReaderError);
        cardReader = findViewById(R.id.cardReader);
        tvTitle = findViewById(R.id.tvReaderTitle);
        tvByline = findViewById(R.id.tvReaderByline);
        tvExcerpt = findViewById(R.id.tvReaderExcerpt);
        tvBody = findViewById(R.id.tvReaderBody);
        tvOfflineInfo = findViewById(R.id.tvOfflineInfo);
        tvUrl = findViewById(R.id.tvReaderUrl);
        ivHero = findViewById(R.id.ivReaderHero);
        btnSave = findViewById(R.id.btnSaveOffline);
        btnExport = findViewById(R.id.btnExportArticle);
        cardRelated = findViewById(R.id.cardRelated);
        rvRelated = findViewById(R.id.rvRelated);
        rvRelated.setLayoutManager(new LinearLayoutManager(this));
        svImages = findViewById(R.id.svArticleImages);
        llArticleImages = findViewById(R.id.llArticleImages);
        tvImagesLabel = findViewById(R.id.tvImagesLabel);

        btnSave.setOnClickListener(v -> saveOffline());
        btnExport.setOnClickListener(v -> showExportDialog());
        tvUrl.setOnClickListener(v -> openBrowser(artUrl));
        // Tap gambar hero -> mode view full-screen (zoom)
        ivHero.setOnClickListener(v -> openHeroViewer());

        relatedJson = getIntent().getStringExtra(EXTRA_RELATED_JSON);

        long savedId = getIntent().getLongExtra(EXTRA_SAVED_ID, -1);
        if (savedId >= 0) {
            loadOffline(savedId);
        } else {
            loadLive(getIntent().getStringExtra(EXTRA_URL),
                    getIntent().getStringExtra(EXTRA_HTML));
        }
    }

    /** Buka URL di browser eksternal. */
    private void openBrowser(String url) {
        if (url == null || url.trim().isEmpty()) {
            Toast.makeText(this, "URL kosong", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak bisa membuka browser",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ live

    private void loadLive(String url, String htmlExtra) {
        progress.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        cardReader.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);

        new Thread(() -> {
            String err = null;
            try {
                if (url == null || url.trim().isEmpty()) {
                    throw new IllegalArgumentException("URL kosong");
                }
                String html = htmlExtra;
                if (html == null || html.trim().isEmpty()) {
                    Document doc = Jsoup.connect(url)
                            .userAgent(UA)
                            .timeout(15000)
                            .get();
                    html = doc.html();
                }
                parseHtml(url, html);
            } catch (IllegalArgumentException e) {
                err = "URL artikel tidak diberikan.";
            } catch (HttpStatusException e) {
                int s = e.getStatusCode();
                if (s == 404) {
                    err = "Halaman tidak ditemukan (404). Mungkin URL sudah berubah atau dihapus.";
                } else {
                    err = "Server menolak permintaan (HTTP " + s + "). Coba lagi nanti.";
                }
            } catch (SocketTimeoutException e) {
                err = "Koneksi timeout. Periksa internet kamu lalu coba lagi.";
            } catch (UnknownHostException e) {
                err = "Tidak ada koneksi internet. Periksa jaringan lalu coba lagi.";
            } catch (Exception e) {
                String m = e.getMessage();
                err = "Gagal memuat artikel" + (m != null && !m.isEmpty() ? ": " + m : ".");
            }

            final String ferr = err;
            handler.post(() -> {
                progress.setVisibility(View.GONE);
                if (ferr != null) {
                    tvError.setText(ferr);
                    tvError.setVisibility(View.VISIBLE);
                } else {
                    showArticle(false, 0);
                }
            });
        }).start();
    }

    /** Parse HTML: Readability4J dulu, fallback ke teks body yang dibersihkan. */
    private void parseHtml(String url, String html) {
        String title = null, byline = null, excerpt = null, text = null;
        String contentHtml = null;

        try {
            Article a = new Readability4J(url, html).parse();
            if (a != null && a.getTextContent() != null
                    && a.getTextContent().trim().length() >= 200) {
                title = nz(a.getTitle());
                byline = nz(a.getByline());
                excerpt = nz(a.getExcerpt());
                text = a.getTextContent().trim();
                contentHtml = a.getContent();
            }
        } catch (Throwable ignored) {
            // jatuh ke fallback di bawah
        }

        Document doc = Jsoup.parse(html, url);

        // Hero image dari og:image (bila ada).
        String hero = null;
        Element og = doc.selectFirst("meta[property=og:image]");
        if (og != null) {
            hero = og.attr("abs:content");
            if (hero != null && hero.trim().isEmpty()) hero = null;
        }

        if (text == null || text.isEmpty()) {
            doc.select("script, style, nav, header, footer, aside, iframe, "
                    + "noscript, form, button, .ad, .ads, .share, .related").remove();
            title = doc.title();
            Element body = doc.body();
            text = body != null ? body.text().replaceAll("\\s+", " ").trim() : "";
        }
        if (title == null || title.trim().isEmpty()) {
            title = url;
        }

        artTitle = title;
        artUrl = url;
        artByline = byline;
        artExcerpt = excerpt;
        artText = text;
        artHeroUrl = hero;
        artHeroPath = null;
        artDate = System.currentTimeMillis();
        // Gambar di dalam isi artikel (dari HTML readability).
        artImages = extractImages(contentHtml, url, hero);
    }

    // ---------------------------------------------------------------- offline

    /** Mode offline: baca dari DB, tanpa network. */
    private void loadOffline(long savedId) {
        progress.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        cardReader.setVisibility(View.GONE);
        btnSave.setVisibility(View.GONE);

        new Thread(() -> {
            SavedArticleDb.SavedItem it;
            try {
                it = SavedArticleDb.getInstance(this).get(savedId);
            } catch (Exception e) {
                it = null;
            }
            final SavedArticleDb.SavedItem item = it;
            handler.post(() -> {
                progress.setVisibility(View.GONE);
                if (item == null) {
                    tvError.setText("Artikel tersimpan tidak ditemukan. Mungkin sudah dihapus.");
                    tvError.setVisibility(View.VISIBLE);
                    return;
                }
                artTitle = item.title;
                artUrl = item.url;
                artByline = null;
                artExcerpt = item.excerpt;
                artText = item.content;
                artHeroUrl = null;
                artHeroPath = item.thumbPath;
                artImages = new ArrayList<>();
                artDate = item.savedAt;
                showArticle(true, item.savedAt);
                // thumbnail lokal bila ada
                if (item.thumbPath != null && !item.thumbPath.isEmpty()) {
                    new Thread(() -> {
                        final Bitmap bmp = BitmapFactory.decodeFile(item.thumbPath);
                        handler.post(() -> {
                            if (bmp != null) {
                                ivHero.setImageBitmap(bmp);
                                ivHero.setVisibility(View.VISIBLE);
                            }
                        });
                    }).start();
                }
            });
        }).start();
    }

    // ------------------------------------------------------------------ tampil

    private void showArticle(boolean offline, long savedAt) {
        tvTitle.setText(artTitle != null ? artTitle : "");

        String host = hostOf(artUrl);
        if (artByline != null && !artByline.isEmpty()) {
            tvByline.setText(artByline + " • " + host);
        } else {
            tvByline.setText(host);
        }

        // URL artikel — bisa di-tap untuk buka di browser
        if (artUrl != null && !artUrl.isEmpty()) {
            tvUrl.setText(artUrl);
            tvUrl.setPaintFlags(tvUrl.getPaintFlags()
                    | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            tvUrl.setVisibility(View.VISIBLE);
        } else {
            tvUrl.setVisibility(View.GONE);
        }

        if (artExcerpt != null && !artExcerpt.isEmpty()) {
            tvExcerpt.setText(artExcerpt);
            tvExcerpt.setVisibility(View.VISIBLE);
        } else {
            tvExcerpt.setVisibility(View.GONE);
        }

        tvBody.setText(artText != null && !artText.isEmpty()
                ? artText : "(Isi artikel kosong.)");
        // URL di dalam teks bisa di-tap
        Linkify.addLinks(tvBody, Linkify.WEB_URLS);
        tvBody.setMovementMethod(LinkMovementMethod.getInstance());

        cardReader.setVisibility(View.VISIBLE);
        btnExport.setVisibility(View.VISIBLE);

        // Strip gambar dalam artikel (bisa di-tap -> viewer)
        buildImageStrip(offline);

        // Artikel terkait (dari halaman yang sama)
        showRelated();

        if (offline) {
            btnSave.setVisibility(View.GONE);
            String tgl = new SimpleDateFormat("dd MMM yyyy HH:mm", new Locale("id", "ID"))
                    .format(new Date(savedAt));
            tvOfflineInfo.setText("dibaca offline • " + tgl);
            tvOfflineInfo.setVisibility(View.VISIBLE);
        } else {
            tvOfflineInfo.setVisibility(View.GONE);
            btnSave.setVisibility(View.VISIBLE);
            // Hero image live: unduh di background bila mudah.
            if (artHeroUrl != null && !artHeroUrl.isEmpty()) {
                ivHero.setVisibility(View.GONE);
                new Thread(() -> {
                    final Bitmap bmp = fetchBitmap(artHeroUrl);
                    handler.post(() -> {
                        if (bmp != null && !isFinishing()) {
                            ivHero.setImageBitmap(bmp);
                            ivHero.setVisibility(View.VISIBLE);
                        }
                    });
                }).start();
            }
        }
    }

    // ------------------------------------------- image viewer (zoom)

    /** Tap gambar hero -> buka mode view full-screen. */
    private void openHeroViewer() {
        if (artHeroPath != null && !artHeroPath.isEmpty()) {
            openImageViewer(null, artHeroPath);
        } else if (artHeroUrl != null && !artHeroUrl.isEmpty()) {
            openImageViewer(artHeroUrl, null);
        }
    }

    private void openImageViewer(String url, String path) {
        Intent i = new Intent(this, ImageViewerActivity.class);
        if (url != null) i.putExtra(ImageViewerActivity.EXTRA_IMG_URL, url);
        if (path != null) i.putExtra(ImageViewerActivity.EXTRA_IMG_PATH, path);
        startActivity(i);
    }

    /**
     * Strip "Gambar dalam artikel": thumbnail horizontal di bawah hero,
     * masing-masing bisa di-tap -> viewer full-screen. Hanya mode live
     * (offline tidak menyimpan gambar inline).
     */
    private void buildImageStrip(boolean offline) {
        llArticleImages.removeAllViews();
        if (offline || artImages == null || artImages.isEmpty()) {
            tvImagesLabel.setVisibility(View.GONE);
            svImages.setVisibility(View.GONE);
            return;
        }
        tvImagesLabel.setVisibility(View.VISIBLE);
        svImages.setVisibility(View.VISIBLE);

        float d = getResources().getDisplayMetrics().density;
        int sizePx = (int) (96 * d);
        int mPx = (int) (8 * d);
        for (String src : artImages) {
            ImageView iv = new ImageView(this);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMargins(0, 0, mPx, 0);
            iv.setLayoutParams(lp);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setBackgroundColor(0xFF1A2B3C);
            iv.setTag(src);
            iv.setOnClickListener(v -> openImageViewer(src, null));
            llArticleImages.addView(iv);
            new Thread(() -> {
                final Bitmap bmp = fetchBitmap(src);
                handler.post(() -> {
                    if (!isFinishing() && src.equals(iv.getTag())
                            && bmp != null) {
                        iv.setImageBitmap(bmp);
                    }
                });
            }).start();
        }
    }

    /**
     * Ambil URL gambar dari HTML konten readability (maks 20).
     * Skip data URI, pixel tracking, dan duplikat hero image.
     */
    private static List<String> extractImages(String contentHtml,
                                              String baseUrl, String heroUrl) {
        List<String> out = new ArrayList<>();
        if (contentHtml == null || contentHtml.isEmpty()) return out;
        try {
            Document cdoc = Jsoup.parse(contentHtml, baseUrl);
            Set<String> seen = new HashSet<>();
            for (Element img : cdoc.select("img[src]")) {
                String src = img.attr("abs:src").trim();
                if (src.isEmpty() || src.startsWith("data:")) continue;
                String low = src.toLowerCase(Locale.US);
                if (low.contains("pixel") || low.contains("1x1")
                        || low.contains("spacer") || low.contains("blank.")
                        || low.contains("tracking")
                        || low.contains("transparent")) {
                    continue;
                }
                if (heroUrl != null && src.equals(heroUrl)) continue;
                if (seen.add(src)) out.add(src);
                if (out.size() >= 20) break;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------- artikel terkait

    /**
     * Tampilkan "Artikel terkait": dari EXTRA_RELATED_JSON, filter domain sama
     * + judul mirip (skor overlap kata), buang URL yang sedang dibaca.
     * Berjalan di background karena bisa ratusan kandidat.
     */
    private void showRelated() {
        cardRelated.setVisibility(View.GONE);
        if (relatedJson == null || relatedJson.trim().isEmpty()
                || relatedJson.trim().equals("[]")) {
            return;
        }
        final String curUrl = artUrl;
        final String curTitle = artTitle;
        final String json = relatedJson;
        new Thread(() -> {
            List<Related> picked = pickRelated(json, curUrl, curTitle);
            handler.post(() -> {
                if (isFinishing() || picked.isEmpty()) return;
                rvRelated.setAdapter(new RelatedAdapter(picked));
                cardRelated.setVisibility(View.VISIBLE);
            });
        }).start();
    }

    private static List<Related> pickRelated(String json, String curUrl, String curTitle) {
        List<Related> out = new ArrayList<>();
        try {
            String curHost = hostOfStatic(curUrl);
            Set<String> curWords = words(curTitle);
            JSONArray arr = new JSONArray(json);
            List<int[]> scored = new ArrayList<>(); // {index, score}
            List<Related> cands = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("u", "");
                if (u.isEmpty() || urlsEqual(u, curUrl)) continue;
                Related r = new Related();
                r.title = o.optString("t", "");
                r.url = u;
                r.thumb = o.optString("h", "");
                r.savedId = o.optLong("s", -1);
                if (r.title.isEmpty()) continue;
                // skor: domain sama + overlap kata judul
                int score = 0;
                if (!curHost.isEmpty() && curHost.equals(hostOfStatic(u))) score += 1000;
                Set<String> w = words(r.title);
                int overlap = 0;
                for (String s : w) if (curWords.contains(s)) overlap++;
                score += overlap * 10;
                cands.add(r);
                scored.add(new int[]{cands.size() - 1, score});
            }
            scored.sort((a, b) -> Integer.compare(b[1], a[1]));
            for (int i = 0; i < Math.min(8, scored.size()); i++) {
                out.add(cands.get(scored.get(i)[0]));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static boolean urlsEqual(String a, String b) {
        if (a == null || b == null) return false;
        String na = a.trim().toLowerCase(Locale.US);
        String nb = b.trim().toLowerCase(Locale.US);
        if (na.endsWith("/")) na = na.substring(0, na.length() - 1);
        if (nb.endsWith("/")) nb = nb.substring(0, nb.length() - 1);
        return na.equals(nb);
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(new Locale("id", "ID")).split("[^a-z0-9]+")) {
            if (w.length() >= 4) out.add(w);
        }
        return out;
    }

    private static String hostOfStatic(String url) {
        if (url == null) return "";
        try {
            String h = new URL(url).getHost();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    private class RelatedAdapter extends RecyclerView.Adapter<RelatedAdapter.H> {
        private final List<Related> items;

        RelatedAdapter(List<Related> items) {
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
            Related r = items.get(pos);
            h.title.setText(r.title);
            h.url.setText(r.url);
            String host = hostOfStatic(r.url);
            if (!host.isEmpty()) {
                h.host.setText(host);
                h.host.setVisibility(View.VISIBLE);
            } else {
                h.host.setVisibility(View.GONE);
            }
            loadRelatedThumb(h.thumb, r.thumb);
            h.itemView.setOnClickListener(v -> openRelated(r));
            h.url.setOnClickListener(v -> openBrowser(r.url));
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

    private void openRelated(Related r) {
        Intent i = new Intent(this, ReaderActivity.class);
        if (r.savedId >= 0) {
            i.putExtra(EXTRA_SAVED_ID, r.savedId);
        } else {
            i.putExtra(EXTRA_URL, r.url);
        }
        i.putExtra(EXTRA_RELATED_JSON, relatedJson);
        startActivity(i);
    }

    /** Thumb related: dukung URL http(s) maupun path file lokal (offline). */
    private void loadRelatedThumb(ImageView iv, String thumb) {
        if (thumb == null || thumb.isEmpty() || thumb.startsWith("data:")) {
            iv.setImageDrawable(null);
            iv.setTag(null);
            return;
        }
        iv.setTag(thumb);
        iv.setImageDrawable(null);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                if (thumb.startsWith("/")) {
                    bmp = BitmapFactory.decodeFile(thumb);
                } else {
                    bmp = fetchBitmap(thumb);
                }
            } catch (Exception ignored) {
            }
            final Bitmap fbm = bmp;
            handler.post(() -> {
                if (!isFinishing() && thumb.equals(iv.getTag()) && fbm != null) {
                    iv.setImageBitmap(fbm);
                }
            });
        }).start();
    }

    // ------------------------------------------------------------------ simpan

    private void saveOffline() {
        btnSave.setEnabled(false);
        Toast.makeText(this, "Menyimpan...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String msg;
            try {
                SavedArticleDb db = SavedArticleDb.getInstance(ReaderActivity.this);
                long id = db.save(artTitle, artUrl, artExcerpt, artText, null);
                if (artHeroUrl != null && !artHeroUrl.isEmpty()) {
                    String p = SavedArticleDb.downloadThumb(ReaderActivity.this, artHeroUrl, id);
                    if (p != null) db.setThumbPath(id, p);
                }
                msg = "Tersimpan";
            } catch (Exception e) {
                msg = "Gagal menyimpan: " + (e.getMessage() != null ? e.getMessage() : "");
            }
            final String fmsg = msg;
            handler.post(() -> {
                Toast.makeText(ReaderActivity.this, fmsg, Toast.LENGTH_SHORT).show();
                btnSave.setEnabled(true);
            });
        }).start();
    }

    // ------------------------------------------------------------------ ekspor

    /** Dialog pilihan ekspor: JSON / PDF / salin teks. */
    private void showExportDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Ekspor artikel")
                .setItems(new CharSequence[]{
                        "JSON (judul, URL, tanggal, teks)",
                        "PDF (judul + isi)",
                        "Salin teks ke clipboard"
                }, (d, which) -> {
                    if (which == 0) {
                        exportJson();
                    } else if (which == 1) {
                        exportPdf();
                    } else {
                        copyText();
                    }
                })
                .show();
    }

    private void copyText() {
        StringBuilder sb = new StringBuilder();
        if (artTitle != null) sb.append(artTitle).append("\n");
        if (artUrl != null) sb.append(artUrl).append("\n");
        sb.append("\n");
        if (artText != null) sb.append(artText);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("artikel", sb.toString().trim()));
        Toast.makeText(this, "Teks artikel disalin", Toast.LENGTH_SHORT).show();
    }

    private void exportJson() {
        btnExport.setEnabled(false);
        Toast.makeText(this, "Menyiapkan JSON...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            File outFile = null;
            try {
                JSONObject o = new JSONObject();
                o.put("title", artTitle != null ? artTitle : "");
                o.put("url", artUrl != null ? artUrl : "");
                o.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                        Locale.US).format(new Date(artDate)));
                o.put("text", artText != null ? artText : "");

                File dir = new File(getExternalFilesDir("exports"), "");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new Exception("gagal buat folder export");
                }
                outFile = new File(dir, "artikel_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date()) + ".json");
                Files.write(outFile.toPath(),
                        o.toString(2).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ferr = err;
            final File ffile = outFile;
            handler.post(() -> {
                btnExport.setEnabled(true);
                if (ferr != null) {
                    Toast.makeText(this, "Export gagal: " + ferr,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                shareFile(ffile, "application/json", "Bagikan JSON");
            });
        }).start();
    }

    /** Ekspor PDF sederhana via PdfDocument bawaan (tanpa library tambahan). */
    private void exportPdf() {
        btnExport.setEnabled(false);
        Toast.makeText(this, "Membuat PDF...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            File outFile = null;
            PdfDocument doc = new PdfDocument();
            try {
                Paint titlePaint = new Paint();
                titlePaint.setColor(Color.BLACK);
                titlePaint.setTextSize(20);
                titlePaint.setTypeface(
                        Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                Paint metaPaint = new Paint();
                metaPaint.setColor(Color.DKGRAY);
                metaPaint.setTextSize(11);
                Paint bodyPaint = new Paint();
                bodyPaint.setColor(Color.BLACK);
                bodyPaint.setTextSize(12);

                PdfWriter w = new PdfWriter(doc);
                w.drawPara(artTitle != null ? artTitle : "(tanpa judul)",
                        titlePaint, 30);
                String meta = hostOf(artUrl) + " • "
                        + new SimpleDateFormat("dd MMM yyyy HH:mm",
                        new Locale("id", "ID")).format(new Date(artDate));
                w.drawPara(meta, metaPaint, 18);
                if (artUrl != null && !artUrl.isEmpty()) {
                    w.drawPara(artUrl, metaPaint, 18);
                }
                w.drawPara("", bodyPaint, 12);
                String body = artText != null ? artText : "";
                for (String para : body.split("\n")) {
                    String p = para.trim();
                    if (!p.isEmpty()) w.drawPara(p, bodyPaint, 20);
                }
                w.finish();

                File dir = new File(getExternalFilesDir("exports"), "");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new Exception("gagal buat folder export");
                }
                outFile = new File(dir, "artikel_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(new Date()) + ".pdf");
                FileOutputStream fos = new FileOutputStream(outFile);
                doc.writeTo(fos);
                fos.close();
            } catch (Exception e) {
                err = e.getMessage();
            } finally {
                doc.close();
            }
            final String ferr = err;
            final File ffile = outFile;
            handler.post(() -> {
                btnExport.setEnabled(true);
                if (ferr != null) {
                    Toast.makeText(this, "Export gagal: " + ferr,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                shareFile(ffile, "application/pdf", "Bagikan PDF");
            });
        }).start();
    }

    /** Bagikan file via FileProvider (pola yang sudah ada). */
    private void shareFile(File f, String mime, String chooserTitle) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", f);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, chooserTitle));
        } catch (Exception e) {
            Toast.makeText(this, "Tersimpan: " + f.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Penulis PDF sederhana: teks di-wrap per paragraf dengan pagination
     * otomatis (A4 595x842pt, margin 48pt).
     */
    private static class PdfWriter {
        private final PdfDocument doc;
        private PdfDocument.Page page;
        private int y;
        private static final int PW = 595, PH = 842, M = 48;

        PdfWriter(PdfDocument doc) {
            this.doc = doc;
            newPage();
        }

        private void newPage() {
            if (page != null) doc.finishPage(page);
            PdfDocument.PageInfo pi =
                    new PdfDocument.PageInfo.Builder(PW, PH, 1).create();
            page = doc.startPage(pi);
            y = M;
        }

        void drawPara(String text, Paint paint, int lineH) {
            if (text == null) text = "";
            float maxW = PW - 2 * M;
            int start = 0;
            boolean drew = false;
            while (start < text.length()) {
                int count = paint.breakText(text, start, text.length(),
                        true, maxW, null);
                if (count <= 0) break;
                int end = start + count;
                if (end < text.length()) {
                    int sp = text.lastIndexOf(' ', end);
                    if (sp > start) end = sp + 1;
                }
                if (y + lineH > PH - M) newPage();
                page.getCanvas().drawText(text, start, end, M, y, paint);
                y += lineH;
                drew = true;
                start = end;
                while (start < text.length() && text.charAt(start) == ' ') start++;
            }
            if (!drew) y += lineH / 2; // paragraf kosong = spasi kecil
            else y += lineH / 3;       // jeda antar paragraf
        }

        void finish() {
            if (page != null) {
                doc.finishPage(page);
                page = null;
            }
        }
    }

    // ------------------------------------------------------------------ util

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = new URL(url).getHost();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return url;
        }
    }

    /** Unduh bitmap sederhana (downscale maks 800px), null bila gagal. */
    private static Bitmap fetchBitmap(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            if (conn.getResponseCode() / 100 != 2) return null;

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 8 * 1024 * 1024) return null;
                bos.write(buf, 0, n);
            }
            in.close();
            byte[] data = bos.toByteArray();
            if (data.length == 0) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 800) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
