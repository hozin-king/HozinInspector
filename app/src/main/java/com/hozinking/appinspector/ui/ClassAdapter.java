package com.hozinking.appinspector.ui;

import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adapter daftar class dengan GROUPING inner class.
 * Class seperti CiciService$1, CiciService$2 digabung di bawah outer class
 * CiciService: satu baris header + badge "+N inner", bisa di-expand.
 * Centang header = centang semua member (hookClasses tetap menyimpan nama exact).
 */
public class ClassAdapter extends RecyclerView.Adapter<ClassAdapter.VH> {

    /** Satu grup = outer class + inner class-nya ($...). */
    public static class Group {
        public final String outer;
        public final List<String> members = new ArrayList<>(); // outer dulu (bila ada), lalu inner
        public Group(String outer) { this.outer = outer; }
        /** Jumlah inner class (tidak termasuk outer itu sendiri bila ada). */
        public int innerCount() {
            int n = 0;
            for (String m : members) if (!m.equals(outer)) n++;
            return n;
        }
    }

    /** Satu baris tampilan: header grup atau satu member. */
    public static class Row {
        public final boolean header;
        public final boolean expanded; // hanya relevan untuk header
        public final Group group;
        public final String cls;       // header -> outer; member -> nama exact class
        public Row(boolean header, boolean expanded, Group group, String cls) {
            this.header = header;
            this.expanded = expanded;
            this.group = group;
            this.cls = cls;
        }
    }

    public interface Listener {
        void onToggle(String cls, boolean checked);
        void onToggleGroup(Group g, boolean checked);
        void onInfo(List<String> classes); // 1 class (tap member) atau semua member (long-press header)
        void onExpand(Group g);
    }

    /** Nama outer: potong dari '$' pertama. com.a.B$1$2 -> com.a.B */
    public static String outerName(String c) {
        int d = c.indexOf('$');
        return d >= 0 ? c.substring(0, d) : c;
    }

    /** Kelompokkan daftar class per outer, urutan kemunculan dipertahankan. */
    public static List<Group> buildGroups(List<String> classes) {
        Map<String, Group> map = new LinkedHashMap<>();
        for (String c : classes) {
            String outer = outerName(c);
            Group g = map.get(outer);
            if (g == null) {
                g = new Group(outer);
                map.put(outer, g);
            }
            if (!g.members.contains(c)) g.members.add(c);
        }
        for (Group g : map.values()) {
            Collections.sort(g.members, (a, b) -> {
                boolean ao = a.equals(g.outer), bo = b.equals(g.outer);
                if (ao != bo) return ao ? -1 : 1;
                return a.compareTo(b);
            });
        }
        return new ArrayList<>(map.values());
    }

    private final List<Row> data = new ArrayList<>();
    private final Set<String> selected;
    private final Listener listener;

    public ClassAdapter(Set<String> selected, Listener listener) {
        this.selected = selected;
        this.listener = listener;
    }

    public void setRows(List<Row> rows) {
        data.clear();
        data.addAll(rows);
        notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        CheckBox cb;
        TextView tv, badge;
        VH(View v) {
            super(v);
            cb = v.findViewById(R.id.cbItem);
            tv = v.findViewById(R.id.tvClassName);
            badge = v.findViewById(R.id.tvBadge);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_class, parent, false);
        return new VH(v);
    }

    private static void styleBadge(TextView badge, String text) {
        badge.setVisibility(View.VISIBLE);
        badge.setText(text);
        badge.setTextColor(0xFF06281C);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF00E5A0);
        bg.setCornerRadius(999f);
        badge.setBackground(bg);
    }

    private static int dp(View v, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                v.getResources().getDisplayMetrics());
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final Row row = data.get(position);
        h.cb.setOnCheckedChangeListener(null);

        if (row.header) {
            // ---- baris header grup ----
            h.tv.setText(row.group.outer);
            h.tv.setPadding(dp(h.tv, 8), dp(h.tv, 8), dp(h.tv, 8), dp(h.tv, 8));
            int n = row.group.innerCount();
            if (n > 0) {
                styleBadge(h.badge, "+" + n + " inner " + (row.expanded ? "▾" : "▸"));
            } else {
                h.badge.setVisibility(View.GONE);
            }
            boolean allChecked = true;
            for (String m : row.group.members) {
                if (!selected.contains(m)) { allChecked = false; break; }
            }
            h.cb.setChecked(allChecked && !row.group.members.isEmpty());
            h.cb.setOnCheckedChangeListener((btn, checked) ->
                    listener.onToggleGroup(row.group, checked));
            View.OnClickListener expand = v -> listener.onExpand(row.group);
            h.itemView.setOnClickListener(expand);
            h.tv.setOnClickListener(expand);
            h.tv.setOnLongClickListener(v -> {
                listener.onInfo(new ArrayList<>(row.group.members));
                return true;
            });
            h.badge.setOnClickListener(expand);
        } else {
            // ---- baris member (inner class) ----
            h.tv.setText(row.cls);
            h.tv.setPadding(dp(h.tv, 40), dp(h.tv, 8), dp(h.tv, 8), dp(h.tv, 8));
            h.badge.setVisibility(View.GONE);
            h.cb.setChecked(selected.contains(row.cls));
            h.cb.setOnCheckedChangeListener((btn, checked) ->
                    listener.onToggle(row.cls, checked));
            h.itemView.setOnClickListener(v -> {
                boolean now = !selected.contains(row.cls);
                h.cb.setChecked(now); // memicu onToggle via listener
            });
            h.tv.setOnClickListener(v ->
                    listener.onInfo(Collections.singletonList(row.cls)));
            h.tv.setOnLongClickListener(null);
        }
    }

    @Override
    public int getItemCount() {
        return data.size();
    }
}
