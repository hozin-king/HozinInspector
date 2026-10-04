package com.hozinking.appinspector.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.appinspector.R;

import net.dankito.readability4j.Readability4J;
import net.dankito.readability4j.Article;

import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
public class ReaderActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_SAVED_ID = "saved_id";
    public static final String EXTRA_HTML = "html";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ProgressBar progress;
    private TextView tvError;
    private View cardReader;
    private TextView tvTitle, tvByline, tvExcerpt, tvBody, tvOfflineInfo;
    private ImageView ivHero;
    private Button btnSave;

    // Artikel yang sedang tampil — dipakai tombol SIMPAN OFFLINE.
    private String artTitle, artUrl, artByline, artExcerpt, artText, artHeroUrl;

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
        ivHero = findViewById(R.id.ivReaderHero);
        btnSave = findViewById(R.id.btnSaveOffline);

        btnSave.setOnClickListener(v -> saveOffline());

        long savedId = getIntent().getLongExtra(EXTRA_SAVED_ID, -1);
        if (savedId >= 0) {
            loadOffline(savedId);
        } else {
            loadLive(getIntent().getStringExtra(EXTRA_URL),
                    getIntent().getStringExtra(EXTRA_HTML));
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

        try {
            Article a = new Readability4J(url, html).parse();
            if (a != null && a.getTextContent() != null
                    && a.getTextContent().trim().length() >= 200) {
                title = nz(a.getTitle());
                byline = nz(a.getByline());
                excerpt = nz(a.getExcerpt());
                text = a.getTextContent().trim();
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

        if (artExcerpt != null && !artExcerpt.isEmpty()) {
            tvExcerpt.setText(artExcerpt);
            tvExcerpt.setVisibility(View.VISIBLE);
        } else {
            tvExcerpt.setVisibility(View.GONE);
        }

        tvBody.setText(artText != null && !artText.isEmpty()
                ? artText : "(Isi artikel kosong.)");

        cardReader.setVisibility(View.VISIBLE);

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
