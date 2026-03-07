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
import com.github.mikephil.charting.charts.BarChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.BarData;
import com.github.mikephil.charting.data.BarDataSet;
import com.github.mikephil.charting.data.BarEntry;
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
    private BarChart eventBarChart;
    private LinearLayout detailedLogsContainer;
    private Button btnShowDetailedLogs;
    private Spinner spinnerFilter;

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
        eventBarChart = findViewById(R.id.eventBarChart);
        detailedLogsContainer = findViewById(R.id.detailedLogsContainer);
        btnShowDetailedLogs = findViewById(R.id.btnShowDetailedLogs);
        spinnerFilter = findViewById(R.id.spinnerFilter);

        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        logAdapter = new LogAdapter(filteredEvents);
        recyclerView.setAdapter(logAdapter);

        // Setup filter spinner
        String[] filters = { "ALL", "FILE_SYSTEM", "HONEYFILE_ACCESS", "NETWORK", "DETECTION", "ACCESSIBILITY" };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, filters);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFilter.setAdapter(adapter);

        spinnerFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                currentFilter = filters[position];
                applyFilter();
                updateGraph();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // Show/Hide detailed logs button
        btnShowDetailedLogs.setOnClickListener(v -> {
            if (detailedLogsContainer.getVisibility() == View.GONE) {
                detailedLogsContainer.setVisibility(View.VISIBLE);
                btnShowDetailedLogs.setText("Hide Detailed Logs");
            } else {
                detailedLogsContainer.setVisibility(View.GONE);
                btnShowDetailedLogs.setText("View Detailed Logs");
            }
        });

        findViewById(R.id.btnClearAllLogs).setOnClickListener(v -> clearAllLogs());

        // Refresh button
        findViewById(R.id.btnRefreshLogs).setOnClickListener(v -> {
            Toast.makeText(this, "Refreshing logs...", Toast.LENGTH_SHORT).show();
            loadAllLogs();
            applyFilter();
            updateGraph();
        });

        // Setup charts
        setupLineChart();
        setupBarChart();

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

    private void setupBarChart() {
        eventBarChart.getDescription().setText("Event Category Samples");
        eventBarChart.getDescription().setTextColor(0xFF94A3B8);
        eventBarChart.getDescription().setEnabled(true);
        eventBarChart.setTouchEnabled(true);
        eventBarChart.setDragEnabled(true);
        eventBarChart.setScaleEnabled(true);
        eventBarChart.setPinchZoom(true);
        eventBarChart.setDrawGridBackground(false);
        eventBarChart.setBackgroundColor(Color.TRANSPARENT);

        XAxis xAxis = eventBarChart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(0xFF94A3B8);
        xAxis.setDrawGridLines(false);
        xAxis.setGranularity(1f);

        YAxis leftAxis = eventBarChart.getAxisLeft();
        leftAxis.setTextColor(0xFF94A3B8);
        leftAxis.setDrawGridLines(true);
        leftAxis.setGridColor(0x33FFFFFF);
        leftAxis.setAxisMinimum(0f); // Fix: Start from 0

        YAxis rightAxis = eventBarChart.getAxisRight();
        rightAxis.setEnabled(false);

        eventBarChart.getLegend().setTextColor(0xFF94A3B8);
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
                String fileName = new File(filePath).getName();
                String extension = json.optString("fileExtension", "N/A");
                long size = json.optLong("fileSizeAfter", 0);

                entry.title = fileName;
                entry.details = String.format(
                        "Operation: %s\nFull Path: %s\nExtension: %s\nSize: %d bytes\nApp: %s (%s)",
                        operation,
                        filePath,
                        extension,
                        size,
                        json.optString("appLabel", "unknown"),
                        json.optString("packageName", "unknown"));
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
        String fileName = new File(filePath).getName();

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

        // Determine which chart type to use based on filter
        boolean useBarChart = shouldUseBarChart(currentFilter);

        if (useBarChart) {
            eventLineChart.setVisibility(View.GONE);
            eventBarChart.setVisibility(View.VISIBLE);
            updateBarChart();
        } else {
            eventBarChart.setVisibility(View.GONE);
            eventLineChart.setVisibility(View.VISIBLE);
            updateLineChart();
        }
    }

    private boolean shouldUseBarChart(String filter) {
        // Use bar chart for discrete security events
        // Use line chart for file system, network and detection (continuous monitoring)
        switch (filter) {
            case "ACCESSIBILITY":
            case "HONEYFILE_ACCESS":
                return true;
            case "FILE_SYSTEM":
            case "NETWORK":
            case "DETECTION":
            case "ALL":
            default:
                return false;
        }
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
        dataSet.setColor(0xFF3B82F6);
        dataSet.setCircleColor(0xFF3B82F6);
        dataSet.setLineWidth(2.5f);
        dataSet.setCircleRadius(5f);
        dataSet.setDrawCircleHole(false);
        dataSet.setDrawCircles(true); // Ensure circles are always drawn
        dataSet.setValueTextSize(9f);
        dataSet.setValueTextColor(0xFF94A3B8);
        dataSet.setDrawFilled(false); // Remove fill to match temperature graph style
        dataSet.setMode(LineDataSet.Mode.LINEAR); // Use linear mode for straight lines between points

        LineData lineData = new LineData(dataSet);
        eventLineChart.setData(lineData);
        eventLineChart.animateX(500);

        // Dynamic scaling: ensure we see the latest data
        if (!entries.isEmpty()) {
            eventLineChart.moveViewToX(entries.get(entries.size() - 1).getX());
        }

        eventLineChart.invalidate();
    }

    private void updateBarChart() {
        // Prepare data for bar graph
        Map<Long, Integer> eventCounts = new HashMap<>();

        // Group events by hour
        for (LogEntry entry : filteredEvents) {
            long hourTimestamp = (entry.timestamp / 3600000) * 3600000;
            eventCounts.put(hourTimestamp, eventCounts.getOrDefault(hourTimestamp, 0) + 1);
        }

        // Convert to chart entries - always start from 0
        List<BarEntry> entries = new ArrayList<>();

        // Add starting point at 0 if we have data
        if (!eventCounts.isEmpty()) {
            entries.add(new BarEntry(0, 0)); // Start from baseline
        }

        List<Long> timestamps = new ArrayList<>(eventCounts.keySet());
        Collections.sort(timestamps);

        // Add data points (offset by 1 because we added starting point at 0)
        for (int i = 0; i < timestamps.size(); i++) {
            long timestamp = timestamps.get(i);
            int count = eventCounts.get(timestamp);
            entries.add(new BarEntry(i + 1, count));
        }

        // If no data, show baseline
        if (entries.isEmpty()) {
            entries.add(new BarEntry(0, 0));
            entries.add(new BarEntry(1, 0));
        }

        // Create dataset
        BarDataSet dataSet = new BarDataSet(entries, "Events");
        dataSet.setColor(0xFFFF6F00); // Orange for bar charts
        dataSet.setValueTextSize(9f);
        dataSet.setValueTextColor(0xFF94A3B8);

        BarData barData = new BarData(dataSet);
        barData.setBarWidth(0.8f);
        eventBarChart.setData(barData);
        eventBarChart.animateY(1000);
        eventBarChart.invalidate();
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
