package com.dearmoon.shield;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.dearmoon.shield.data.FileSystemEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FileAccessAdapter extends RecyclerView.Adapter<FileAccessAdapter.ViewHolder> {
    private List<FileSystemEvent> events = new ArrayList<>();
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault());

    public void setEvents(List<FileSystemEvent> events) {
        this.events = events;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file_access, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileSystemEvent event = events.get(position);
        
        String fileName = event.getFilePath();
        if (fileName.contains("/")) {
            fileName = fileName.substring(fileName.lastIndexOf("/") + 1);
        }
        
        holder.tvFileName.setText(fileName);
        holder.tvOperation.setText(event.getOperation());
        holder.tvTime.setText(dateFormat.format(new Date(event.getTimestamp())));
        holder.tvPath.setText(event.getFilePath());

        int color;
        String op = event.getOperation();
        if (op.contains("DELETE")) {
            color = 0xFFEF4444;
        } else if (op.contains("MODIFY") || op.contains("CLOSE_WRITE")) {
            color = 0xFFF59E0B;
        } else if (op.contains("OPEN")) {
            color = 0xFF10B981;
        } else {
            color = 0xFF6B7280;
        }
        holder.tvOperation.setTextColor(color);
    }

    @Override
    public int getItemCount() {
        return events.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFileName, tvOperation, tvTime, tvPath;

        ViewHolder(View view) {
            super(view);
            tvFileName = view.findViewById(R.id.tvFileName);
            tvOperation = view.findViewById(R.id.tvOperation);
            tvTime = view.findViewById(R.id.tvTime);
            tvPath = view.findViewById(R.id.tvPath);
        }
    }
}
