package com.hozinking.appinspector.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Pref Viewer (ROOT): baca ISI SharedPreferences app target.
 * /data/data/&lt;pkg&gt;/shared_prefs/*.xml -> daftar key-value (string/boolean/int/long/float/set).
 * Read-only.
 */
public class PrefViewerActivity extends AppCompatActivity {

    private String pkg = "";
    private TextView tvTitle, tvCrumb;
    private RecyclerView rv;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private static class Kv {
        String type, key, value;
    }

    private boolean inFile = false;
    private String currentFile = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pref_viewer);
        pkg = getIntent().getStringExtra("pkg");
        if (pkg == null) pkg = "";

        tvTitle = findViewById(R.id.tvPrefTitle);
        tvCrumb = findViewById(R.id.tvPrefCrumb);
        rv = findViewById(R.id.rvPref);
        rv.setLayoutManager(new LinearLayoutManager(this));
        tvTitle.setText("Pref Viewer — " + pkg);
        loadFileList();
    }

    @Override
    public void onBackPressed() {
        if (inFile) {
            inFile = false;
            loadFileList();
        } else {
            super.onBackPressed();
        }
    }

    // ---------- daftar file xml ----------

    private void loadFileList() {
        tvCrumb.setText("Memuat daftar shared_prefs...");
        new Thread(() -> {
            SuHelper.Result r = SuHelper.exec(
                    "ls " + SuHelper.q("/data/data/" + pkg + "/shared_prefs/"));
            final List<String> files = new ArrayList<>();
            if (r.code == 0) {
                for (String line : r.out.split("\n")) {
                    String t = line.trim();
                    if (t.endsWith(".xml") && !t.isEmpty()) files.add(t);
                }
            }
            final boolean ok = r.code == 0;
            handler.post(() -> {
                tvCrumb.setText("shared_prefs/ (" + files.size() + " file)");
                rv.setAdapter(new SimpleAdapter(files, pos -> {
                    inFile = true;
                    currentFile = files.get(pos);
                    loadFile(currentFile);
                }));
                if (!ok) toast("Gagal baca direktori (root?)");
                if (files.isEmpty() && ok) toast("Tidak ada file XML di shared_prefs/");
            });
        }).start();
    }

    // ---------- isi satu file ----------

    private void loadFile(final String name) {
        tvCrumb.setText("Membaca " + name + "...");
        new Thread(() -> {
            String path = "/data/data/" + pkg + "/shared_prefs/" + name;
            SuHelper.Result r = SuHelper.exec("cat " + SuHelper.q(path));
            final List<Kv> kvs = new ArrayList<>();
            String err = null;
            if (r.code == 0 && !r.out.isEmpty()) {
                try {
                    kvs.addAll(parsePrefsXml(r.out));
                } catch (Exception e) {
                    err = "Gagal parse XML: " + e.getMessage();
                }
            } else {
                err = "Gagal baca file (root?)";
            }
            final String ferr = err;
            handler.post(() -> {
                tvCrumb.setText(name + " (" + kvs.size() + " key)");
                rv.setAdapter(new KvAdapter(kvs));
                if (ferr != null) toast(ferr);
            });
        }).start();
    }

    /** Parse XML shared_prefs: map -> string/boolean/int/long/float/set. */
    private List<Kv> parsePrefsXml(String xml) throws Exception {
        List<Kv> out = new ArrayList<>();
        XmlPullParserFactory f = XmlPullParserFactory.newInstance();
        f.setNamespaceAware(false);
        XmlPullParser xpp = f.newPullParser();
        xpp.setInput(new StringReader(xml));
        int ev = xpp.getEventType();
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                String tag = xpp.getName();
                String name = xpp.getAttributeValue(null, "name");
                if (name != null) {
                    Kv kv = new Kv();
                    kv.key = name;
                    if ("string".equals(tag)) {
                        kv.type = "string";
                        kv.value = xpp.nextText();
                    } else if ("boolean".equals(tag) || "int".equals(tag)
                            || "long".equals(tag) || "float".equals(tag)) {
                        kv.type = tag;
                        kv.value = xpp.getAttributeValue(null, "value");
                    } else if ("set".equals(tag)) {
                        kv.type = "set";
                        StringBuilder sb = new StringBuilder();
                        int inner = xpp.next();
                        while (!(inner == XmlPullParser.END_TAG
                                && "set".equals(xpp.getName()))) {
                            if (inner == XmlPullParser.START_TAG
                                    && "string".equals(xpp.getName())) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(xpp.nextText());
                            }
                            inner = xpp.next();
                            if (inner == XmlPullParser.END_DOCUMENT) break;
                        }
                        kv.value = sb.toString();
                    } else {
                        kv = null;
                    }
                    if (kv != null) out.add(kv);
                }
            }
            ev = xpp.next();
        }
        return out;
    }

    // ---------- adapters ----------

    private static class SimpleAdapter extends RecyclerView.Adapter<SimpleAdapter.H> {
        interface Click { void onClick(int pos); }
        private final List<String> items;
        private final Click click;

        SimpleAdapter(List<String> items, Click click) {
            this.items = items;
            this.click = click;
        }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_simple, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            h.tv.setText(items.get(pos));
            h.itemView.setOnClickListener(v -> click.onClick(h.getAdapterPosition()));
        }

        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView tv;
            H(View v) {
                super(v);
                tv = v.findViewById(R.id.tvSimple);
            }
        }
    }

    private static class KvAdapter extends RecyclerView.Adapter<KvAdapter.H> {
        private final List<Kv> items;

        KvAdapter(List<Kv> items) { this.items = items; }

        @NonNull
        @Override
        public H onCreateViewHolder(@NonNull ViewGroup p, int v) {
            View view = LayoutInflater.from(p.getContext())
                    .inflate(R.layout.row_kv, p, false);
            return new H(view);
        }

        @Override
        public void onBindViewHolder(@NonNull H h, int pos) {
            Kv kv = items.get(pos);
            h.type.setText(kv.type);
            h.key.setText(kv.key);
            h.value.setText(kv.value == null ? "(null)" : kv.value);
            h.type.setBackgroundColor(badgeColor(kv.type));
        }

        private int badgeColor(String t) {
            if ("boolean".equals(t)) return 0xFF9C27B0;
            if ("int".equals(t) || "long".equals(t) || "float".equals(t)) return 0xFF2196F3;
            if ("set".equals(t)) return 0xFFFF9800;
            return 0xFF4CAF50; // string
        }

        @Override public int getItemCount() { return items.size(); }

        static class H extends RecyclerView.ViewHolder {
            TextView type, key, value;
            H(View v) {
                super(v);
                type = v.findViewById(R.id.tvKvType);
                key = v.findViewById(R.id.tvKvKey);
                value = v.findViewById(R.id.tvKvValue);
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
