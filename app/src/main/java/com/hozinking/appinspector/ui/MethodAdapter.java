package com.hozinking.appinspector.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.hozinking.appinspector.R;

import java.util.ArrayList;
import java.util.List;

/** Adapter daftar method: signature + dex index. */
public class MethodAdapter extends RecyclerView.Adapter<MethodAdapter.VH> {

    private final List<DexParser.MethodInfo> data = new ArrayList<>();

    public MethodAdapter(List<DexParser.MethodInfo> initial) {
        data.addAll(initial);
    }

    public void setData(List<DexParser.MethodInfo> d) {
        data.clear();
        data.addAll(d);
        notifyDataSetChanged();
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvSig, tvIdx;
        VH(View v) {
            super(v);
            tvSig = v.findViewById(R.id.tvMethodSig);
            tvIdx = v.findViewById(R.id.tvMethodIdx);
        }
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.row_method, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DexParser.MethodInfo m = data.get(position);
        h.tvSig.setText(m.signature);
        h.tvIdx.setText("dex method index: #" + m.dexIndex);
    }

    @Override
    public int getItemCount() {
        return data.size();
    }
}
