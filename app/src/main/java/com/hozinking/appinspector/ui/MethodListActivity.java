package com.hozinking.appinspector.ui;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Daftar method dari satu class ATAU satu grup class (dari dex):
 * signature + dex method index + label kecil class asalnya (mode grup).
 */
public class MethodListActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_CLASS = "cls"; // single (lama)
    public static final String EXTRA_CLASSES = "classes"; // grup (baru)

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_method_list);

        ArrayList<String> classes = getIntent().getStringArrayListExtra(EXTRA_CLASSES);
        if (classes == null || classes.isEmpty()) {
            String single = getIntent().getStringExtra(EXTRA_CLASS);
            classes = new ArrayList<>();
            if (single != null) classes.add(single);
        }
        final List<String> targetClasses = classes;
        final boolean groupMode = targetClasses.size() > 1;

        TextView tvClass = findViewById(R.id.tvMethodClass);
        TextView tvCount = findViewById(R.id.tvMethodCount);
        if (groupMode) {
            tvClass.setText("Grup: " + ClassAdapter.outerName(targetClasses.get(0))
                    + " (" + targetClasses.size() + " class)");
        } else {
            tvClass.setText(targetClasses.isEmpty() ? "?" : targetClasses.get(0));
        }

        RecyclerView rv = findViewById(R.id.rvMethods);
        rv.setLayoutManager(new LinearLayoutManager(this));
        MethodAdapter adapter = new MethodAdapter(new ArrayList<>(), groupMode);
        rv.setAdapter(adapter);

        // parse dex di background thread — jangan pernah di main thread
        new Thread(() -> {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(
                        getIntent().getStringExtra(EXTRA_PKG), 0);
                final List<DexParser.MethodInfo> all = new ArrayList<>();
                for (String cls : targetClasses) {
                    try {
                        List<DexParser.MethodInfo> list =
                                DexParser.listMethods(ai.sourceDir, cls);
                        for (DexParser.MethodInfo mi : list) mi.owner = cls;
                        all.addAll(list);
                    } catch (Exception ignored) {
                    }
                }
                handler.post(() -> {
                    adapter.setData(all);
                    tvCount.setText(all.size() + " method dari "
                            + targetClasses.size() + " class");
                });
            } catch (final Exception e) {
                handler.post(() -> {
                    tvCount.setText("Gagal: " + e.getMessage());
                    Toast.makeText(this, "Gagal parse dex: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
