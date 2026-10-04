package com.hozinking.appinspector.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.appinspector.R;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/** Menampilkan log [HozinInspector] dari logcat (butuh READ_LOGS via root). */
public class LogViewerActivity extends AppCompatActivity {

    private TextView tvLog;
    private ScrollView svLog;
    private static final int MAX_LINES = 3000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);
        tvLog = findViewById(R.id.tvLog);
        svLog = findViewById(R.id.svLog);

        findViewById(R.id.btnRefresh).setOnClickListener(v -> refresh());
        findViewById(R.id.btnClear).setOnClickListener(v -> clearLog());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copyLog());
        refresh();
    }

    private void refresh() {
        tvLog.setText(readLog());
        svLog.post(() -> svLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private String readLog() {
        List<String> lines = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "brief"});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("[HozinInspector]")) lines.add(line);
            }
            br.close();
        } catch (Exception e) {
            return "Gagal baca logcat: " + e.getMessage();
        }
        int from = Math.max(0, lines.size() - MAX_LINES);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
        if (sb.length() == 0) {
            return "(kosong — belum ada event, atau READ_LOGS belum di-grant.\n"
                    + "Tekan GRANT READ_LOGS di layar utama, lalu pakai app target.)";
        }
        return sb.toString();
    }

    private void clearLog() {
        boolean ok = false;
        try {
            ok = Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor() == 0;
        } catch (Exception ignored) {
        }
        if (!ok) {
            try {
                ok = Runtime.getRuntime().exec(new String[]{"su", "-c", "logcat -c"}).waitFor() == 0;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, ok ? "Log dibersihkan" : "Gagal clear log",
                Toast.LENGTH_SHORT).show();
        if (ok) refresh();
    }

    private void copyLog() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("hozininspector", tvLog.getText()));
            Toast.makeText(this, "Log dicopy", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Gagal copy: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
