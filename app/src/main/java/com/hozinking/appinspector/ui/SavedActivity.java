package com.hozinking.appinspector.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.hozinking.appinspector.R;

import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Layar "TERSIMPAN" (Hozin Tools): daftar artikel offline dari SavedArticleDb.
 * - Tap item → buka ReaderActivity dengan EXTRA_SAVED_ID.
 * - Long-press → dialog: Hapus / Buka URL asli di browser.
 * - HAPUS SEMUA → dialog konfirmasi.
 */
public class SavedActivity extends BaseActivity {

    private RecyclerView rv;
    private TextView tvEmpty;
    private Button btnClear;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SavedAdapter adapter = new SavedAdapter();
    private final SimpleDateFormat df =
            new SimpleDateFormat("dd MMM yyyy HH:mm", new Locale("id", "ID"));

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_saved);

        rv = findViewById(R.id.rvSaved);
        tvEmpty = findViewById(R.id.tvSavedEmpty);
        btnClear = findViewById(R.id.btnClearAll);

        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        btnClear.setOnClickListener(v -> confirmClearAll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        new Thread(() -> {
            List<SavedArticleDb.SavedItem> items;
            try {
                items = SavedArticleDb.getInstance(SavedActivity.this).list();
            } catch (Exception e) {
                items = new ArrayList<>();
            }
            final List<SavedArticleDb.SavedItem> data = items;
            handler.post(() -> {
                adapter.set(data);
                boolean empty = data.isEmpty();
                tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                rv.setVisibility(empty ? View.GONE : View.VISIBLE);
                btnClear.setEnabled(!empty);
            });
        }).start();
    }

    private void confirmClearAll() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Hapus semua?")
                .setMessage("Semua artikel tersimpan akan dihapus dari perangkat. Lanjutkan?")
                .setPositiveButton("Hapus semua", (d, w) -> {
                    new Thread(() -> {
                        try {
                            SavedArticleDb.getInstance(SavedActivity.this).clear();
                        } catch (Exception ignored) {
                        }
                        handler.post(() -> {
                            reload();
                            Toast.makeText(SavedActivity.this,
                                    "Semua artikel dihapus", Toast.LENGTH_SHORT).show();
                        });
                    }).start();
                })
                .setNegativeButton("Batal", null)
                .show();
    }

    private void openReader(long id) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.EXTRA_SAVED_ID, id);
        // artikel tersimpan lain sebagai "Artikel terkait" di reader
        List<ReaderActivity.Related> rel = new ArrayList<>();
        for (SavedArticleDb.SavedItem it : adapter.getData()) {
            if (it.id == id) continue;
            ReaderActivity.Related r = new ReaderActivity.Related();
            r.title = it.title;
            r.url = it.url;
            r.thumb = it.thumbPath;
            r.savedId = it.id;
            rel.add(r);
            if (rel.size() >= 40) break;
        }
        i.putExtra(ReaderActivity.EXTRA_RELATED_JSON,
                ReaderActivity.buildRelatedJson(rel));
        startActivity(i);
    }

    private void onItemLongPress(SavedArticleDb.SavedItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(item.title != null ? item.title : "Artikel")
                .setItems(new CharSequence[]{"Hapus", "Buka URL asli di browser"},
                        (d, which) -> {
                            if (which == 0) {
                                new Thread(() -> {
                                    try {
                                        SavedArticleDb.getInstance(SavedActivity.this).delete(item.id);
                                    } catch (Exception ignored) {
                                    }
                                    handler.post(() -> {
                                        reload();
                                        Toast.makeText(SavedActivity.this,
                                                "Dihapus", Toast.LENGTH_SHORT).show();
                                    });
                                }).start();
                            } else {
                                try {
                                    startActivity(new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(item.url)));
                                } catch (Exception e) {
                                    Toast.makeText(SavedActivity.this,
                                            "Tidak bisa membuka browser", Toast.LENGTH_SHORT).show();
                                }
                            }
                        })
                .show();
    }

    // ------------------------------------------------------------- adapter

    private class SavedAdapter extends RecyclerView.Adapter<SavedAdapter.VH> {

        private final List<SavedArticleDb.SavedItem> data = new ArrayList<>();

        void set(List<SavedArticleDb.SavedItem> items) {
            data.clear();
            if (items != null) data.addAll(items);
            notifyDataSetChanged();
        }

        List<SavedArticleDb.SavedItem> getData() {
            return data;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.row_saved, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            SavedArticleDb.SavedItem it = data.get(position);
            h.tvTitle.setText(it.title != null && !it.title.isEmpty() ? it.title : "(tanpa judul)");
            if (it.excerpt != null && !it.excerpt.isEmpty()) {
                h.tvExcerpt.setText(it.excerpt);
                h.tvExcerpt.setVisibility(View.VISIBLE);
            } else {
                h.tvExcerpt.setVisibility(View.GONE);
            }
            h.tvDate.setText(df.format(new Date(it.savedAt)));

            // badge host/subdomain
            String host = hostOf(it.url);
            if (!host.isEmpty()) {
                h.tvHost.setText(host);
                h.tvHost.setVisibility(View.VISIBLE);
            } else {
                h.tvHost.setVisibility(View.GONE);
            }
            // URL bisa di-tap → buka di browser
            if (it.url != null && !it.url.isEmpty()) {
                h.tvUrl.setText(it.url);
                h.tvUrl.setPaintFlags(h.tvUrl.getPaintFlags()
                        | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
                h.tvUrl.setVisibility(View.VISIBLE);
                h.tvUrl.setOnClickListener(v -> openBrowser(it.url));
            } else {
                h.tvUrl.setVisibility(View.GONE);
            }

            h.ivThumb.setImageBitmap(null);
            h.ivThumb.setTag(it.thumbPath);
            if (it.thumbPath != null && !it.thumbPath.isEmpty()) {
                new Thread(() -> {
                    final Bitmap bmp = BitmapFactory.decodeFile(it.thumbPath);
                    handler.post(() -> {
                        // pastikan holder masih untuk item yang sama
                        if (it.thumbPath.equals(h.ivThumb.getTag()) && bmp != null) {
                            h.ivThumb.setImageBitmap(bmp);
                        }
                    });
                }).start();
            }

            h.itemView.setOnClickListener(v -> openReader(it.id));
            h.itemView.setOnLongClickListener(v -> {
                onItemLongPress(it);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class VH extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final TextView tvTitle, tvExcerpt, tvDate, tvHost, tvUrl;

            VH(@NonNull View v) {
                super(v);
                ivThumb = v.findViewById(R.id.ivSavedThumb);
                tvTitle = v.findViewById(R.id.tvSavedTitle);
                tvExcerpt = v.findViewById(R.id.tvSavedExcerpt);
                tvDate = v.findViewById(R.id.tvSavedDate);
                tvHost = v.findViewById(R.id.tvSavedHost);
                tvUrl = v.findViewById(R.id.tvSavedUrl);
            }
        }
    }

    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            String h = new URL(url).getHost();
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "";
        }
    }

    private void openBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "Tidak bisa membuka browser",
                    Toast.LENGTH_SHORT).show();
        }
    }
}
