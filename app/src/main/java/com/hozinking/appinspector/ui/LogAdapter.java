package com.hozinking.appinspector.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adapter log per baris: badge TAG berwarna + pesan monospace. */
public class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {

    /** Kategori untuk chip filter. */
    public enum Cat { ALL, METHOD, URL, UI, PREF, LAYOUT, OTHER }

    public static class Line {
        public final Cat cat;
        public final String badge;   // teks badge: METHOD/URL/UI/PREF/LAYOUT/ERR/INIT/...
        public final int badgeColor;
        public final String msg;
        public Line(Cat c, String b, int bc, String m) {
            cat = c;
            badge = b;
            badgeColor = bc;
            msg = m;
        }
    }

    private static final Pattern P =
            Pattern.compile("\\[HozinInspector\\]\\[([A-Za-z]+)\\]\\s?(.*)");

    private final List<Line> data = new ArrayList<>();

    public void setData(List<Line> d) {
        data.clear();
        data.addAll(d);
        notifyDataSetChanged();
    }

    /** Parse satu baris logcat -> Line. */
    public static Line parse(String raw) {
        Matcher m = P.matcher(raw);
        if (!m.find()) {
            return new Line(Cat.OTHER, "?", 0xFF616161, raw);
        }
        String tag = m.group(1).toUpperCase();
        String msg = m.group(2);
        switch (tag) {
            case "MT":
                return new Line(Cat.METHOD, "METHOD", 0xFF2196F3, msg);
            case "URL":
                return new Line(Cat.URL, "URL", 0xFF4CAF50, msg);
            case "UI":
                return new Line(Cat.UI, "UI", 0xFF9C27B0, msg);
            case "PREF":
                return new Line(Cat.PREF, "PREF", 0xFFFF9800, msg);
            case "LAYOUT":
                return new Line(Cat.LAYOUT, "LAYOUT", 0xFF009688, msg);
            case "ERR":
                return new Line(Cat.OTHER, "ERR", 0xFFF44336, msg);
            case "INIT":
                return new Line(Cat.OTHER, "INIT", 0xFF616161, msg);
            default:
                return new Line(Cat.OTHER, tag, 0xFF616161, msg);
        }
    }

    public static boolean matchCat(Line l, Cat filter) {
        return filter == Cat.ALL || l.cat == filter;
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView badge, msg;
        VH(View v) {
            super(v);
            badge = v.findViewById(R.id.tvBadge);
            msg = v.findViewById(R.id.tvMsg);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_log, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Line l = data.get(position);
        h.badge.setText(l.badge);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(l.badgeColor);
        bg.setCornerRadius(8f);
        h.badge.setBackground(bg);
        h.msg.setText(l.msg);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    /** Ambil semua pesan yang sedang tampil (untuk copy). */
    public String visibleText() {
        StringBuilder sb = new StringBuilder();
        for (Line l : data) sb.append('[').append(l.badge).append("] ").append(l.msg).append('\n');
        return sb.toString();
    }
}
