package com.dearmoon.shield;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;
import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
    private List<LogViewerActivity.LogEntry> logEntries;

    public LogAdapter(List<LogViewerActivity.LogEntry> logEntries) {
        this.logEntries = logEntries;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log_entry, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogViewerActivity.LogEntry entry = logEntries.get(position);

        holder.tvTitle.setText(entry.title);
        holder.tvTimestamp.setText(entry.getFormattedTime());
        holder.tvDetails.setText(entry.details);
        holder.tvType.setText(entry.type);

        // Set severity color
        holder.viewSeverityIndicator.setBackgroundColor(entry.getSeverityColor());

        // Set card background based on severity (Slate colors)
        if (entry.severity.equals("CRITICAL")) {
            holder.cardView.setCardBackgroundColor(0x33EF4444); // 20% opacity Red
        } else if (entry.severity.equals("HIGH")) {
            holder.cardView.setCardBackgroundColor(0x33F59E0B); // 20% opacity Amber
        } else {
            holder.cardView.setCardBackgroundColor(0xFF1E293B); // Slate 800
        }
    }

    @Override
    public int getItemCount() {
        return logEntries.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        View viewSeverityIndicator;
        TextView tvTitle;
        TextView tvTimestamp;
        TextView tvType;
        TextView tvDetails;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = (MaterialCardView) itemView;
            viewSeverityIndicator = itemView.findViewById(R.id.viewSeverityIndicator);
            tvTitle = itemView.findViewById(R.id.tvLogTitle);
            tvTimestamp = itemView.findViewById(R.id.tvLogTimestamp);
            tvType = itemView.findViewById(R.id.tvLogTypeBadge);
            tvDetails = itemView.findViewById(R.id.tvLogDetails);
        }
    }
}
