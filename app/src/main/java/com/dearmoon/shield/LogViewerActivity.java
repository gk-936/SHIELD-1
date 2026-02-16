package com.dearmoon.shield;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class LogViewerActivity extends AppCompatActivity {
    private static final String TAG = "LogViewerActivity";

    private RecyclerView recyclerView;
    private LogAdapter logAdapter;
    private TextView tvEventCount;
    private Spinner spinnerFilter;

    private List<LogEntry> allEvents = new ArrayList<>();
    private List<LogEntry> filteredEvents = new ArrayList<>();

    private String currentFilter = "ALL";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log_viewer);

        // Force status bar to black
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(0xFF000000);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        com.google.android.material.appbar.MaterialToolbar toolbar = findViewById(R.id.toolbarLogs);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            toolbar.setNavigationOnClickListener(v -> onBackPressed());
        }

        initializeViews();
        loadAllLogs();
        applyFilter();
    }

    private void initializeViews() {
        recyclerView = findViewById(R.id.recyclerViewLogs);
        tvEventCount = findViewById(R.id.tvEventCount);
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
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        findViewById(R.id.btnClearAllLogs).setOnClickListener(v -> clearAllLogs());
    }

    private void loadAllLogs() {
        allEvents.clear();

        loadTelemetryEvents();
        loadDetectionResults();

        Collections.sort(allEvents, (a, b) -> Long.compare(b.timestamp, a.timestamp));

        Log.i(TAG, "Loaded " + allEvents.size() + " total events from SQLite");
    }

    private void loadTelemetryEvents() {
        try {
            com.dearmoon.shield.data.EventDatabase database = 
                com.dearmoon.shield.data.EventDatabase.getInstance(this);
            
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
                        "Operation: %s\nFull Path: %s\nExtension: %s\nSize: %d bytes",
                        operation,
                        filePath,
                        extension,
                        size);
                entry.severity = getSeverityForOperation(operation);
                break;

            case "HONEYFILE_ACCESS":
                entry.title = "⚠️ HONEYFILE ACCESSED";
                entry.details = String.format("Access Type: %s\nFile: %s\nCalling UID: %d\nPackage: %s",
                        json.optString("accessType", "UNKNOWN"),
                        json.optString("filePath", "Unknown"),
                        json.optInt("callingUid", -1),
                        json.optString("packageName", "unknown"));
                entry.severity = "HIGH";
                break;

            case "NETWORK":
                entry.title = "Network Event";
                entry.details = String.format(
                        "Protocol: %s\nDestination: %s:%d\nBytes Sent: %d\nBytes Received: %d\nApp UID: %d",
                        json.optString("protocol", "UNKNOWN"),
                        json.optString("destinationIp", "0.0.0.0"),
                        json.optInt("destinationPort", 0),
                        json.optLong("bytesSent", 0),
                        json.optLong("bytesReceived", 0),
                        json.optInt("appUid", -1));
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
            com.dearmoon.shield.data.EventDatabase database = 
                com.dearmoon.shield.data.EventDatabase.getInstance(this);
            
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
                confidenceScore >= 70 ? "⚠️ HIGH RISK" : confidenceScore >= 40 ? "MEDIUM" : "LOW");

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

    private void clearAllLogs() {
        com.dearmoon.shield.data.EventDatabase database = 
            com.dearmoon.shield.data.EventDatabase.getInstance(this);
        database.clearAllEvents();
        
        // Also delete legacy JSON files if they exist
        new File(getFilesDir(), "modeb_telemetry.json").delete();
        new File(getFilesDir(), "detection_results.json").delete();
        
        allEvents.clear();
        filteredEvents.clear();
        logAdapter.notifyDataSetChanged();
        updateEventCount();
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
            SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yy HH:mm", Locale.US);
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
