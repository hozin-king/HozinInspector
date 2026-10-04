package com.hozinking.appinspector.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Konfigurasi dibaca dari file JSON yang ditulis app UI.
 * Primer: /data/local/tmp/HozinInspector/config.json (ditulis via root, terbaca semua UID)
 * Fallback: /sdcard/HozinInspector/config.json
 */
public class HiConfig {
    public String targetPackage = "";
    public boolean methodTrace = false;
    public List<String> traceClasses = new ArrayList<>();
    public boolean urlTrack = false;
    public boolean uiTrace = false;
    public boolean prefTrace = false;
    public boolean dumpUi = false;

    private static final String[] PATHS = {
            "/data/local/tmp/HozinInspector/config.json",
            "/sdcard/HozinInspector/config.json"
    };

    public static HiConfig load() {
        HiConfig cfg = new HiConfig();
        for (String p : PATHS) {
            try {
                File f = new File(p);
                if (!f.exists()) continue;
                byte[] bytes = Files.readAllBytes(f.toPath());
                JSONObject o = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                cfg.targetPackage = o.optString("targetPackage", "");
                cfg.methodTrace = o.optBoolean("methodTrace", false);
                cfg.urlTrack = o.optBoolean("urlTrack", false);
                cfg.uiTrace = o.optBoolean("uiTrace", false);
                cfg.prefTrace = o.optBoolean("prefTrace", false);
                cfg.dumpUi = o.optBoolean("dumpUi", false);
                JSONArray arr = o.optJSONArray("traceClasses");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i, "");
                        if (!s.isEmpty()) cfg.traceClasses.add(s);
                    }
                }
                break; // pakai file pertama yang ketemu
            } catch (Exception ignored) {
            }
        }
        return cfg;
    }
}
