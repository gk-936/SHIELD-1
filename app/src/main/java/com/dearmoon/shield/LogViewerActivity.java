package com.dearmoon.shield;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import org.json.JSONObject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LogViewerActivity extends AppCompatActivity {
    private static final String TAG = "LogViewerActivity";

    private RecyclerView recyclerView;
    private LogAdapter logAdapter;
    private TextView tvEventCount;
    private TextView tvGraphTitle;
    private LineChart eventLineChart;
    private LinearLayout detailedLogsContainer;
    private Button btnShowDetailedLogs;
    private LinearLayout filterChipGroup;

    private List<LogEntry> allEvents = new ArrayList<>();
    private List<LogEntry> filteredEvents = new ArrayList<>();

    private String currentFilter = "ALL";
    private android.content.BroadcastReceiver updateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // ── Edge-to-Edge Immersive Status Bar ───────────────────────────────
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        androidx.core.view.WindowInsetsControllerCompat insetsController =
                new androidx.core.view.WindowInsetsControllerCompat(
                        getWindow(), getWindow().getDecorView());
        insetsController.setAppearanceLightStatusBars(false);
        insetsController.setAppearanceLightNavigationBars(false);
        // ───────────────────────────────────────────────────────────────────

        setContentView(R.layout.activity_log_viewer);

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbarLogs);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }

        // Push AppBar down by status bar height in Edge-to-Edge mode
        com.google.android.material.appbar.AppBarLayout appBar =
                (com.google.android.material.appbar.AppBarLayout) toolbar.getParent();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(appBar, (v, windowInsets) -> {
            androidx.core.graphics.Insets insets = windowInsets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, insets.top, 0, 0);
            return windowInsets;
        });

        initializeViews();
        loadAllLogs();
        applyFilter();
        updateGraph();
    }

    private void initializeViews() {
        recyclerView = findViewById(R.id.recyclerViewLogs);
        tvEventCount = findViewById(R.id.tvEventCount);
        tvGraphTitle = findViewById(R.id.tvGraphTitle);
        eventLineChart = findViewById(R.id.eventLineChart);
        detailedLogsContainer = findViewById(R.id.detailedLogsContainer);
        btnShowDetailedLogs = findViewById(R.id.btnShowDetailedLogs);
        filterChipGroup = findViewById(R.id.filterChipGroup);

        // Setup filter chips
        String[] filters = { "ALL", "FILE_SYSTEM", "HONEYFILE_ACCESS", "NETWORK", "DETECTION", "ACCESSIBILITY" };
        setupChips(filters);

        com.google.android.material.appbar.AppBarLayout appBar = findViewById(R.id.appBarLayout);

        // Show/Hide detailed logs button
        btnShowDetailedLogs.setOnClickListener(v -> {
            boolean isVisible = detailedLogsContainer.getVisibility() == View.VISIBLE;
            if (!isVisible) {
                detailedLogsContainer.setVisibility(View.VISIBLE);
                btnShowDetailedLogs.setText("Hide Detailed Logs");
                appBar.setExpanded(false, true); // smoothly scroll up
            } else {
                detailedLogsContainer.setVisibility(View.GONE);
                btnShowDetailedLogs.setText("View Detailed Logs");
                appBar.setExpanded(true, true); // smoothly scroll down
            }
        });

        findViewById(R.id.btnClearAllLogs).setOnClickListener(v -> clearAllLogs());

        findViewById(R.id.btnRefreshLogs).setOnClickListener(v -> {
            Toast.makeText(this, "Refreshing logs...", Toast.LENGTH_SHORT).show();
            loadAllLogs();
            applyFilter();
            updateGraph();
        });

        // Setup RecyclerView last so that it does not block the main setup
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        logAdapter = new LogAdapter(filteredEvents);
        recyclerView.setAdapter(logAdapter);

        // Setup charts
        setupLineChart();

        // Setup dynamic updates
        updateReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if ("com.dearmoon.shield.DATA_UPDATED".equals(intent.getAction())) {
                    loadAllLogs();
                    applyFilter();
                    updateGraph();
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter("com.dearmoon.shield.DATA_UPDATED");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        if (updateReceiver != null) {
            unregisterReceiver(updateReceiver);
        }
        super.onDestroy();
    }

    private void setupChips(String[] filters) {
        filterChipGroup.removeAllViews();
        float density = getResources().getDisplayMetrics().density;
        
        for (String filter : filters) {
            TextView chip = new TextView(this);
            String displayName = filter.replace("_", " ");
            chip.setText(displayName);
            chip.setTextSize(12f);
            
            int pdH = (int)(16 * density);
            int pdV = (int)(8 * density);
            chip.setPadding(pdH, pdV, pdH, pdV);
            
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.setMarginEnd((int)(8 * density));
            chip.setLayoutParams(lp);
            
            updateChipStyle(chip, filter.equals(currentFilter));
            
            chip.setOnClickListener(v -> {
                currentFilter = filter;
                setupChips(filters);
                applyFilter();
                updateGraph();
            });
            
            filterChipGroup.addView(chip);
        }
    }

    private void updateChipStyle(TextView chip, boolean isSelected) {
        float density = getResources().getDisplayMetrics().density;
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(16 * density);
        
        if (isSelected) {
            bg.setColor(Color.parseColor("#00E5FF")); 
            chip.setTextColor(Color.parseColor("#0A183D")); 
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
        } else {
            bg.setColor(Color.parseColor("#1AFFFFFF"));
            bg.setStroke((int)(1 * density), Color.parseColor("#33FFFFFF"));
            chip.setTextColor(0xFF94A3B8);
            chip.setTypeface(null, android.graphics.Typeface.NORMAL);
        }
        chip.setBackground(bg);
    }

    private void setupLineChart() {
        eventLineChart.getDescription().setText("Time (Hourly Buckets)");
        eventLineChart.getDescription().setTextColor(0xFF94A3B8);
        eventLineChart.getDescription().setEnabled(true);
        eventLineChart.setTouchEnabled(true);
        eventLineChart.setDragEnabled(true);
        eventLineChart.setScaleEnabled(true);
        eventLineChart.setPinchZoom(true);
        eventLineChart.setDrawGridBackground(false);
        eventLineChart.setBackgroundColor(Color.TRANSPARENT);

        XAxis xAxis = eventLineChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(0xFF94A3B8);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);

        YAxis leftAxis = eventLineChart.getAxisLeft();
        leftAxis.setTextColor(0xFF94A3B8);
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(0x33FFFFFF);
        leftAxis.setAxisMinimum(0f); // Fix: Start from 0

        YAxis rightAxis = eventLineChart.getAxisRight();
        rightAxis.setEnabled(false);

        eventLineChart.getLegend().setTextColor(0xFF94A3B8);
    }



    private void loadAllLogs() {
        allEvents.clear();

        Log.i(TAG, "=== Starting to load logs ===");
        loadTelemetryEvents();
        loadDetectionResults();

        Collections.sort(allEvents, (a, b) -> Long.compare(b.timestamp, a.timestamp));

        Log.i(TAG, "=== Loaded " + allEvents.size() + " total events from SQLite ===");

        // Show toast with event count for debugging
        Toast.makeText(this, "Loaded " + allEvents.size() + " events from database",
                Toast.LENGTH_SHORT).show();
    }

    private void loadTelemetryEvents() {
        try {
            com.dearmoon.shield.data.EventDatabase database = com.dearmoon.shield.data.EventDatabase.getInstance(this);

            Log.d(TAG, "Loading telemetry events from database...");
            List<JSONObject> events = database.getAllEvents("ALL", 1000);
            Log.i(TAG, "Database returned " + events.size() + " telemetry events");

            for (JSONObject json : events) {
                try {
                    LogEntry entry = parseTelemetryEvent(json);
                    if (entry != null) {
                        allEvents.add(entry);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing event: " + json.toString(), e);
                }
            }

            Log.i(TAG, "Successfully parsed " + allEvents.size() + " telemetry events");
        } catch (Exception e) {
            Log.e(TAG, "Error reading telemetry from SQLite", e);
            Toast.makeText(this, "Error loading telemetry: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private LogEntry parseTelemetryEvent(JSONObject json) throws Exception {
        String eventType = json.getString("eventType");
        long timestamp = json.getLong("timestamp");

        LogEntry entry = new LogEntry();
        entry.timestamp = timestamp;
        entry.type = eventType;

        switch (eventType) {
            case "FILE_SYSTEM":
                String operation = json.optString("operation", "UNKNOWN");
                String filePath = json.optString("filePath", "Unknown");
                String displayName = json.optString("displayName", "");
                String relativePath = json.optString("relativePath", "");
                String fileUri = json.optString("fileUri", "");
                String resolvedPath = json.optString("resolvedPath", "");

                String bestDisplayPath =
                        (!relativePath.isEmpty() && !displayName.isEmpty()) ? (relativePath + displayName)
                                : (!displayName.isEmpty()) ? displayName
                                : (!filePath.isEmpty() && !"Unknown".equals(filePath)) ? filePath
                                : (!fileUri.isEmpty()) ? fileUri
                                : "Unknown";

                String fileName;
                if (!displayName.isEmpty()) {
                    fileName = displayName;
                } else {
                    // Avoid treating content:// URIs as filesystem paths just to "getName"
                    int slash = bestDisplayPath.lastIndexOf('/');
                    fileName = slash >= 0 ? bestDisplayPath.substring(slash + 1) : bestDisplayPath;
                }
                String extension = json.optString("fileExtension", "N/A");
                long size = json.optLong("fileSizeAfter", 0);

                entry.title = fileName;
                entry.details = String.format(
                        "Operation: %s\nFile: %s\nResolved Path: %s\nUri: %s\nExtension: %s\nSize: %d bytes\nApp: %s (%s)\nUID: %d",
                        operation,
                        bestDisplayPath,
                        resolvedPath.isEmpty() ? "N/A" : resolvedPath,
                        fileUri.isEmpty() ? "N/A" : fileUri,
                        extension,
                        size,
                        json.optString("appLabel", "unknown"),
                        json.optString("packageName", "unknown"),
                        json.optInt("uid", -1));
                entry.severity = getSeverityForOperation(operation);
                break;

            case "HONEYFILE_ACCESS":
                entry.title = "HONEYFILE ACCESSED";
                entry.details = String.format("Access Type: %s\nFile: %s\nApp: %s\nPackage: %s\nUID: %d",
                        json.optString("accessType", "UNKNOWN"),
                        json.optString("filePath", "Unknown"),
                        json.optString("appLabel", "unknown"),
                        json.optString("packageName", "unknown"),
                        json.optInt("uid", -1));
                entry.severity = "HIGH";
                break;

            case "NETWORK":
                entry.title = "Network Event";
                entry.details = String.format(
                        "Protocol: %s\nDestination: %s:%d\nBytes Sent: %d\nBytes Received: %d\nApp: %s\nPackage: %s\nUID: %d",
                        json.optString("protocol", "UNKNOWN"),
                        json.optString("destinationIp", "0.0.0.0"),
                        json.optInt("destinationPort", 0),
                        json.optLong("bytesSent", 0),
                        json.optLong("bytesReceived", 0),
                        json.optString("appLabel", "unknown"),
                        json.optString("packageName", "unknown"),
                        json.optInt("uid", -1));
                entry.severity = "INFO";
                break;

            case "ACCESSIBILITY":
                entry.title = "Accessibility Event";
                entry.details = String.format("Package: %s\nClass: %s\nEvent Type: %d",
                        json.optString("packageName", "Unknown"),
                        json.optString("className", "Unknown"),
                        json.optInt("eventTypeCode", -1));
                entry.severity = "INFO";
                break;

            default:
                entry.title = "Unknown Event";
                entry.details = json.toString(2);
                entry.severity = "INFO";
        }

        return entry;
    }

    private void loadDetectionResults() {
        try {
            com.dearmoon.shield.data.EventDatabase database = com.dearmoon.shield.data.EventDatabase.getInstance(this);

            List<JSONObject> results = database.getAllDetectionResults(1000);

            for (JSONObject json : results) {
                try {
                    LogEntry entry = parseDetectionResult(json);
                    if (entry != null) {
                        allEvents.add(entry);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing detection result: " + json.toString(), e);
                }
            }

            Log.i(TAG, "Loaded " + results.size() + " detection results from SQLite");
        } catch (Exception e) {
            Log.e(TAG, "Error reading detection results from SQLite", e);
            Toast.makeText(this, "Error loading detections: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private LogEntry parseDetectionResult(JSONObject json) throws Exception {
        LogEntry entry = new LogEntry();
        entry.timestamp = json.getLong("timestamp");
        entry.type = "DETECTION";

        int confidenceScore = json.getInt("confidence_score");
        String sprtState = json.getString("sprt_state");
        String filePath = json.optString("file_path", "Unknown");
        String fileName;
        if (filePath == null || filePath.isEmpty() || "Unknown".equals(filePath)) {
            fileName = "Unknown";
        } else {
            int slash = filePath.lastIndexOf('/');
            fileName = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        }

        entry.title = "Detection: " + fileName;
        entry.details = String.format(
                "Full Path: %s\n\nEntropy: %s\nKL-Divergence: %s\nSPRT State: %s\nConfidence Score: %d/100\n\nRisk Level: %s",
                filePath,
                json.optString("entropy", "N/A"),
                json.optString("kl_divergence", "N/A"),
                sprtState,
                confidenceScore,
                confidenceScore >= 70 ? "HIGH RISK" : confidenceScore >= 40 ? "MEDIUM" : "LOW");

        if (confidenceScore >= 70) {
            entry.severity = "CRITICAL";
        } else if (confidenceScore >= 40) {
            entry.severity = "MEDIUM";
        } else {
            entry.severity = "LOW";
        }

        return entry;
    }

    private String getSeverityForOperation(String operation) {
        switch (operation) {
            case "DELETED":
                return "HIGH";
            case "MODIFY":
                return "MEDIUM";
            case "COMPRESSED":
                return "MEDIUM";
            default:
                return "INFO";
        }
    }

    private void applyFilter() {
        filteredEvents.clear();

        for (LogEntry entry : allEvents) {
            if (currentFilter.equals("ALL")) {
                filteredEvents.add(entry);
            } else if (entry.type.equals(currentFilter)) {
                filteredEvents.add(entry);
            }
        }

        logAdapter.notifyDataSetChanged();
        updateEventCount();
    }

    private void updateEventCount() {
        String countText = String.format(Locale.US, "Showing %d of %d events",
                filteredEvents.size(), allEvents.size());
        tvEventCount.setText(countText);
    }

    private void updateGraph() {
        // Update graph title based on filter
        String graphTitle = currentFilter.equals("ALL") ? "Event Activity Overview"
                : currentFilter.replace("_", " ") + " Activity";
        tvGraphTitle.setText(graphTitle);

        eventLineChart.setVisibility(View.VISIBLE);
        updateLineChart();
    }

    private void updateLineChart() {
        // Prepare data for graph
        Map<Long, Integer> eventCounts = new HashMap<>();

        // Group events by hour
        for (LogEntry entry : filteredEvents) {
            long hourTimestamp = (entry.timestamp / 3600000) * 3600000; // Round to hour
            eventCounts.put(hourTimestamp, eventCounts.getOrDefault(hourTimestamp, 0) + 1);
        }

        // Convert to chart entries - always start from 0
        List<Entry> entries = new ArrayList<>();

        // Add starting point at 0 if we have data
        if (!eventCounts.isEmpty()) {
            entries.add(new Entry(0, 0)); // Start from baseline
        }

        List<Long> timestamps = new ArrayList<>(eventCounts.keySet());
        Collections.sort(timestamps);

        // Add data points (offset by 1 because we added starting point at 0)
        for (int i = 0; i < timestamps.size(); i++) {
            long timestamp = timestamps.get(i);
            int count = eventCounts.get(timestamp);
            entries.add(new Entry(i + 1, count));
        }

        // If no data, show baseline
        if (entries.isEmpty()) {
            entries.add(new Entry(0, 0));
            entries.add(new Entry(1, 0));
        }

        // Create dataset
        LineDataSet dataSet = new LineDataSet(entries, "Events");
        dataSet.setColor(Color.parseColor("#00E5FF")); // Neon Cyan
        dataSet.setLineWidth(2.5f);
        
        dataSet.setCircleColor(Color.parseColor("#00C8FF"));
        dataSet.setCircleRadius(5f);
        dataSet.setDrawCircleHole(true);
        dataSet.setCircleHoleColor(Color.parseColor("#0A183D"));
        dataSet.setCircleHoleRadius(2.5f);
        dataSet.setDrawCircles(true); 
        
        dataSet.setValueTextSize(9f);
        dataSet.setValueTextColor(0xFF94A3B8);
        
        // Beautiful Area Fill (Gradient)
        dataSet.setDrawFilled(true);
        android.graphics.drawable.GradientDrawable fillGradient = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#7000E5FF"), Color.TRANSPARENT} // Alpha-cyan to transparent
        );
        dataSet.setFillDrawable(fillGradient);
        
        // Smooth spline curve for a futuristic look
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        LineData lineData = new LineData(dataSet);
        eventLineChart.setData(lineData);
        eventLineChart.animateX(500);

        // Dynamic scaling: ensure we see the latest data
        if (!entries.isEmpty()) {
            eventLineChart.moveViewToX(entries.get(entries.size() - 1).getX());
        }

        eventLineChart.invalidate();
    }



    private void clearAllLogs() {
        com.dearmoon.shield.data.EventDatabase database = com.dearmoon.shield.data.EventDatabase.getInstance(this);
        database.clearAllEvents();

        // Also delete legacy JSON files if they exist
        new File(getFilesDir(), "modeb_telemetry.json").delete();
        new File(getFilesDir(), "detection_results.json").delete();

        allEvents.clear();
        filteredEvents.clear();
        logAdapter.notifyDataSetChanged();
        updateEventCount();
        updateGraph();
        Toast.makeText(this, "All logs cleared", Toast.LENGTH_SHORT).show();
    }

    // Inner class for log entries
    public static class LogEntry {
        public long timestamp;
        public String type;
        public String title;
        public String details;
        public String severity; // INFO, LOW, MEDIUM, HIGH, CRITICAL

        public String getFormattedTime() {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            return sdf.format(new Date(timestamp));
        }

        public int getSeverityColor() {
            switch (severity) {
                case "CRITICAL":
                    return 0xFFD32F2F; // Red
                case "HIGH":
                    return 0xFFFF6F00; // Orange
                case "MEDIUM":
                    return 0xFFFFA000; // Amber
                case "LOW":
                    return 0xFF1976D2; // Blue
                default:
                    return 0xFF757575; // Gray
            }
        }
    }
}
