package com.dearmoon.shield.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.dearmoon.shield.MainActivity;
import com.dearmoon.shield.R;
import com.dearmoon.shield.data.NetworkEvent;
import com.dearmoon.shield.data.TelemetryStorage;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

public class NetworkGuardService extends VpnService {
    private static final String TAG = "NetworkGuardService";
    public static final String ACTION_STOP = "com.dearmoon.shield.STOP";

    private Thread vpnThread;
    private ParcelFileDescriptor vpnInterface;
    private TelemetryStorage storage;
    private volatile boolean isRunning = false;
    private volatile boolean blockAllTraffic = false;
    private volatile boolean blockingEnabled = false;
    private EmergencyModeReceiver emergencyReceiver;
    private BlockingToggleReceiver blockingToggleReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            storage = new TelemetryStorage(this);
            
            blockingEnabled = getSharedPreferences("ShieldPrefs", Context.MODE_PRIVATE)
                    .getBoolean("blocking_enabled", false);
            
            createNotificationChannel();
            
            emergencyReceiver = new EmergencyModeReceiver();
            android.content.IntentFilter filter = new android.content.IntentFilter("com.dearmoon.shield.EMERGENCY_MODE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(emergencyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(emergencyReceiver, filter);
            }
            
            blockingToggleReceiver = new BlockingToggleReceiver();
            android.content.IntentFilter toggleFilter = new android.content.IntentFilter("com.dearmoon.shield.TOGGLE_BLOCKING");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(blockingToggleReceiver, toggleFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(blockingToggleReceiver, toggleFilter);
            }
            
            Log.d(TAG, "NetworkGuardService created successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreate", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            // Start foreground immediately to prevent crash
            startForeground(2, createNotification());
            
            if (intent != null && ACTION_STOP.equals(intent.getAction())) {
                stopRunning();
                stopSelf();
                return START_NOT_STICKY;
            }

            if (!isRunning) {
                isRunning = true;
                vpnThread = new Thread(this::runVpnLoop, "VpnThread");
                vpnThread.start();
                Log.d(TAG, "VPN thread started");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in onStartCommand", e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void stopRunning() {
        isRunning = false;
        if (vpnThread != null) {
            vpnThread.interrupt();
        }
        closeVpnInterface();
    }

    private void runVpnLoop() {
        try {
            Log.d(TAG, "Establishing VPN interface...");
            vpnInterface = establishVpnInterface();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                isRunning = false;
                stopSelf();
                return;
            }
            Log.d(TAG, "VPN interface established successfully");

            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
            ByteBuffer packet = ByteBuffer.allocate(32767);

            while (isRunning && !Thread.interrupted()) {
                int length = in.read(packet.array());
                if (length > 0) {
                    packet.limit(length);
                    
                    // Analyze and decide whether to block
                    boolean shouldBlock = analyzePacket(packet);
                    
                    if (!shouldBlock) {
                        // Pass-through: write only the read bytes
                        out.write(packet.array(), 0, length);
                    } else {
                        Log.w(TAG, "BLOCKED suspicious packet");
                    }
                    
                    packet.clear();
                }
            }
        } catch (Exception e) {
            if (isRunning) {
                Log.e(TAG, "VPN loop error", e);
            }
        } finally {
            closeVpnInterface();
            isRunning = false;
            Log.d(TAG, "VPN loop terminated");
        }
    }

    private ParcelFileDescriptor establishVpnInterface() {
        try {
            Builder builder = new Builder();
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.addDnsServer("8.8.8.8");
            builder.addDnsServer("8.8.4.4");
            builder.setMtu(1500);
            builder.setSession("NetworkGuard");

            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
            builder.setConfigureIntent(pendingIntent);

            return builder.establish();
        } catch (Exception e) {
            Log.e(TAG, "Error establishing VPN interface", e);
            return null;
        }
    }

    private boolean analyzePacket(ByteBuffer packet) {
        if (packet.remaining() < 20)
            return false;

        byte versionAndIHL = packet.get(0);
        int version = (versionAndIHL >> 4) & 0x0F;
        if (version != 4)
            return false; // IPv4 only

        int protocol = packet.get(9) & 0xFF;
        String protoName = protocol == 6 ? "TCP" : protocol == 17 ? "UDP" : "OTHER";

        byte[] destIpBytes = new byte[4];
        packet.position(16);
        packet.get(destIpBytes);
        String destIp = String.format("%d.%d.%d.%d",
                destIpBytes[0] & 0xFF, destIpBytes[1] & 0xFF,
                destIpBytes[2] & 0xFF, destIpBytes[3] & 0xFF);

        int destPort = 0;
        if (packet.remaining() >= 24) {
            destPort = packet.getShort(22) & 0xFFFF;
        }

        // Log network event
        if (storage != null) {
            NetworkEvent netEvent = new NetworkEvent(
                    destIp, destPort, protoName, packet.remaining(), 0, android.os.Process.myUid());
            storage.store(netEvent);
        }
        
        // Check if should block
        return shouldBlockConnection(destIp, destPort, protoName);
    }

    private void closeVpnInterface() {
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
        }
    }

    private boolean shouldBlockConnection(String destIp, int destPort, String protocol) {
        // If blocking disabled by user, allow all traffic
        if (!blockingEnabled && !blockAllTraffic) {
            return false;
        }
        
        // Emergency kill switch - block ALL traffic if ransomware detected
        if (blockAllTraffic) {
            Log.e(TAG, "EMERGENCY MODE: Blocking all traffic");
            return true;
        }
        
        // Block known malicious ports
        if (destPort == 4444 || destPort == 5555 || destPort == 6666 || destPort == 7777) {
            Log.w(TAG, "BLOCKED: Suspicious port " + destPort);
            return true;
        }
        
        // Block Tor exit nodes (common C2 infrastructure)
        if (isTorExitNode(destIp)) {
            Log.w(TAG, "BLOCKED: Tor exit node " + destIp);
            return true;
        }
        
        // Block private/suspicious IP ranges
        if (isSuspiciousIp(destIp)) {
            Log.w(TAG, "BLOCKED: Suspicious IP " + destIp);
            return true;
        }
        
        // Allow all other traffic
        return false;
    }
    
    public void enableEmergencyMode() {
        blockAllTraffic = true;
        Log.e(TAG, "EMERGENCY MODE ACTIVATED - All network traffic blocked");
    }
    
    public void disableEmergencyMode() {
        blockAllTraffic = false;
        Log.i(TAG, "Emergency mode deactivated");
    }
    
    private boolean isTorExitNode(String ip) {
        // Known Tor exit nodes and C2 infrastructure
        String[] torNodes = {
            "185.220.101", "185.220.102", "185.100.86", "185.100.87",
            "45.61.185", "45.61.186", "45.61.187",  // Bulletproof hosting
            "185.141.25", "185.141.26",  // Known C2 ranges
            "91.219.236", "91.219.237"   // Malicious infrastructure
        };
        for (String prefix : torNodes) {
            if (ip.startsWith(prefix)) return true;
        }
        return false;
    }
    
    private boolean isSuspiciousIp(String ip) {
        // Allow localhost and link-local (not external destinations anyway)
        if (ip.startsWith("127.") || ip.startsWith("169.254.")) {
            return false; // Local traffic, allow
        }
        
        // Allow private networks (home/office networks)
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) {
            return false; // Local network, allow
        }
        
        if (ip.startsWith("172.")) {
            String[] parts = ip.split("\\.");
            if (parts.length >= 2) {
                try {
                    int secondOctet = Integer.parseInt(parts[1]);
                    if (secondOctet >= 16 && secondOctet <= 31) {
                        return false; // Private range 172.16-31.x, allow
                    }
                } catch (NumberFormatException e) {
                    // Invalid IP, block
                }
            }
        }
        
        // Block multicast/broadcast
        if (ip.startsWith("224.") || ip.startsWith("255.")) {
            return true;
        }
        
        return false;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "network_guard_channel",
                    "Network Guard",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Network monitoring and protection");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

            return new NotificationCompat.Builder(this, "network_guard_channel")
                    .setContentTitle("Network Guard Active")
                    .setContentText(blockingEnabled ? "Blocking: ON" : "Monitoring only")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pendingIntent)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "Error creating notification", e);
            // Return minimal notification as fallback
            return new NotificationCompat.Builder(this, "network_guard_channel")
                    .setContentTitle("Network Guard")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build();
        }
    }

    @Override
    public void onDestroy() {
        stopRunning();
        try {
            if (emergencyReceiver != null) {
                unregisterReceiver(emergencyReceiver);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering emergency receiver", e);
        }
        try {
            if (blockingToggleReceiver != null) {
                unregisterReceiver(blockingToggleReceiver);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering blocking toggle receiver", e);
        }
        super.onDestroy();
    }
    
    private class EmergencyModeReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if ("com.dearmoon.shield.EMERGENCY_MODE".equals(intent.getAction())) {
                enableEmergencyMode();
            }
        }
    }
    
    private class BlockingToggleReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if ("com.dearmoon.shield.TOGGLE_BLOCKING".equals(intent.getAction())) {
                blockingEnabled = intent.getBooleanExtra("enabled", false);
                Log.i(TAG, "Blocking " + (blockingEnabled ? "enabled" : "disabled"));
            }
        }
    }
}
