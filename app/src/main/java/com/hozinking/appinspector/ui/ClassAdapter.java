package com.hozinking.appinspector.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Adapter daftar class: checkbox pilih + tap nama untuk info method. */
public class ClassAdapter extends RecyclerView.Adapter<ClassAdapter.VH> {

    public interface Listener {
        void onToggle(String cls, boolean checked);
        void onInfo(String cls);
    }

    private final List<String> data = new ArrayList<>();
    private final Set<String> selected;
    private final Listener listener;

    public ClassAdapter(List<String> initial, Set<String> selected, Listener listener) {
        this.data.addAll(initial);
        this.selected = selected;
        this.listener = listener;
    }

    public void setData(List<String> d) {
        data.clear();
        data.addAll(d);
        notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        CheckBox cb;
        TextView tv;
        VH(View v) {
            super(v);
            cb = v.findViewById(R.id.cbItem);
            tv = v.findViewById(R.id.tvClassName);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_class, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        final String cls = data.get(position);
        h.tv.setText(cls);
        h.cb.setOnCheckedChangeListener(null);
        h.cb.setChecked(selected.contains(cls));
        h.cb.setOnCheckedChangeListener((btn, checked) -> listener.onToggle(cls, checked));
        h.itemView.setOnClickListener(v -> {
            boolean now = !selected.contains(cls);
            h.cb.setChecked(now); // memicu onToggle via listener
        });
        h.tv.setOnClickListener(v -> listener.onInfo(cls));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }
}
