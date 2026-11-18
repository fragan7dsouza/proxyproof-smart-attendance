package com.example.smartattendance.util;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.smartattendance.R;

import java.util.ArrayList;
import java.util.List;

/** One list row style for the whole app: optional initials, a title, a subtitle and a right-hand value. */
public class RowAdapter extends RecyclerView.Adapter<RowAdapter.Holder> {

    public static class Row {
        public final String id;
        public final CharSequence title;
        public final CharSequence subtitle;
        public CharSequence trailing;
        public int trailingColor;
        public String avatar;

        public Row(String id, CharSequence title, CharSequence subtitle) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
        }

        public Row trailing(CharSequence text, int color) {
            trailing = text;
            trailingColor = color;
            return this;
        }

        public Row avatar(String initials) {
            avatar = initials;
            return this;
        }
    }

    public interface Listener {
        void onRow(Row row);
    }

    private final List<Row> rows = new ArrayList<>();
    private Listener onClick;
    private Listener onLongClick;

    public RowAdapter onClick(Listener l) {
        onClick = l;
        return this;
    }

    public RowAdapter onLongClick(Listener l) {
        onLongClick = l;
        return this;
    }

    public void submit(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Row row = rows.get(position);
        h.title.setText(row.title);
        h.subtitle.setText(row.subtitle);
        h.subtitle.setVisibility(row.subtitle == null || row.subtitle.length() == 0 ? View.GONE : View.VISIBLE);
        h.trailing.setText(row.trailing);
        h.trailing.setVisibility(row.trailing == null ? View.GONE : View.VISIBLE);
        if (row.trailing != null) h.trailing.setTextColor(row.trailingColor);
        h.avatar.setText(row.avatar);
        h.avatar.setVisibility(row.avatar == null ? View.GONE : View.VISIBLE);
        h.itemView.setOnClickListener(onClick == null ? null : v -> onClick.onRow(row));
        h.itemView.setClickable(onClick != null);
        h.itemView.setOnLongClickListener(onLongClick == null ? null : v -> {
            onLongClick.onRow(row);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView avatar, title, subtitle, trailing;

        Holder(View v) {
            super(v);
            avatar = v.findViewById(R.id.row_avatar);
            title = v.findViewById(R.id.row_title);
            subtitle = v.findViewById(R.id.row_subtitle);
            trailing = v.findViewById(R.id.row_trailing);
        }
    }
}
