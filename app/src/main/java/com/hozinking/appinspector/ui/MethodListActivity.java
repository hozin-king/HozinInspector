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

/** Daftar method milik satu class (dari dex): signature + dex method index. */
public class MethodListActivity extends AppCompatActivity {

    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_CLASS = "cls";

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_method_list);

        String cls = getIntent().getStringExtra(EXTRA_CLASS);
        String pkg = getIntent().getStringExtra(EXTRA_PKG);
        TextView tvClass = findViewById(R.id.tvMethodClass);
        TextView tvCount = findViewById(R.id.tvMethodCount);
        tvClass.setText(cls == null ? "?" : cls);

        RecyclerView rv = findViewById(R.id.rvMethods);
        rv.setLayoutManager(new LinearLayoutManager(this));
        MethodAdapter adapter = new MethodAdapter(new ArrayList<>());
        rv.setAdapter(adapter);

        // parse dex di background thread — jangan pernah di main thread
        new Thread(() -> {
            try {
                ApplicationInfo ai = getPackageManager().getApplicationInfo(pkg, 0);
                final List<DexParser.MethodInfo> list =
                        DexParser.listMethods(ai.sourceDir, cls);
                handler.post(() -> {
                    adapter.setData(list);
                    tvCount.setText(list.size() + " method");
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
