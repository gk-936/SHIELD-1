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
    private final java.util.Set<String> flowCache = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // Ransomware C2 domains
    private static final String[] C2_DOMAINS = {
        "pastebin.com",
        "api.telegram.org",
        "discord.com",
        "paste.ee",
        "hastebin.com",
        "transfer.sh",
        "webhook.site"
    };
    private long lastCacheClear = System.currentTimeMillis();
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
            // Start foreground service
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

            try (FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
                 FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor())) {
                
                ByteBuffer packet = ByteBuffer.allocate(32767);

                while (isRunning && !Thread.interrupted()) {
                    int length = in.read(packet.array());
                    if (length > 0) {
                        packet.limit(length);
                        
                        // Analyze packet
                        boolean shouldBlock = analyzePacket(packet);
                        
                        if (!shouldBlock) {
                            // Pass-through network packet
                            out.write(packet.array(), 0, length);
                        } else {
                            Log.w(TAG, "BLOCKED suspicious packet");
                        }
                        
                        packet.clear();
                    }
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
            // Use Quad9 DNS
            builder.addDnsServer("9.9.9.9");          // Quad9 primary
            builder.addDnsServer("149.112.112.112");  // Quad9 secondary
            builder.setMtu(1500);
            builder.setSession("NetworkGuard");
            // legacy port blocking

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

    // Analyze IPv6 packet
    private boolean analyzePacket(ByteBuffer packet) {
        if (packet.remaining() < 20)
            return false;

        byte versionAndIHL = packet.get(0);
        int version = (versionAndIHL >> 4) & 0x0F;
        
        if (version == 4) {
            return analyzeIPv4Packet(packet);
        } else if (version == 6) {
            return analyzeIPv6Packet(packet);
        }
        
        // Unknown IP version, allow
        return false;
    }
    
    // Analyze IPv4 packet
    private boolean analyzeIPv4Packet(ByteBuffer packet) {
        if (packet.remaining() < 20)
            return false;

        int protocol = packet.get(9) & 0xFF;
        String protoName = protocol == 6 ? "TCP" : protocol == 17 ? "UDP" : "OTHER";

        // Convert IP string
        int destIpOffset = 16;
        String destIp = (packet.get(destIpOffset) & 0xFF) + "." +
                        (packet.get(destIpOffset + 1) & 0xFF) + "." +
                        (packet.get(destIpOffset + 2) & 0xFF) + "." +
                        (packet.get(destIpOffset + 3) & 0xFF);

        int destPort = 0;
        if (packet.remaining() >= 24) {
            destPort = packet.getShort(22) & 0xFFFF;
        }

        // Performance: Connection-based logging using flow cache
        String flowKey = destIp + ":" + destPort + ":" + protoName;
        if (shouldLogFlow(flowKey)) {
            if (storage != null) {
                NetworkEvent netEvent = new NetworkEvent(
                        destIp, destPort, protoName, packet.remaining(), 0, android.os.Process.myUid());
                storage.store(netEvent);
            }
        }
        
        // Check DNS
        if (isDnsQueryForC2Domain(packet)) return true;
        return shouldBlockConnection(destIp, destPort, protoName);
    }

    private boolean shouldLogFlow(String flowKey) {
        long now = System.currentTimeMillis();
        // Clear flow cache
        if (now - lastCacheClear > 300000) {
            flowCache.clear();
            lastCacheClear = now;
        }
        return flowCache.add(flowKey);
    }
    
    // Analyze IPv6
    private boolean analyzeIPv6Packet(ByteBuffer packet) {
        if (packet.remaining() < 40)  // IPv6 header is 40 bytes
            return false;

        int nextHeader = packet.get(6) & 0xFF;  // Protocol (TCP=6, UDP=17)
        String protoName = nextHeader == 6 ? "TCP" : nextHeader == 17 ? "UDP" : "OTHER";

        // Convert IPv6 string
        StringBuilder ipv6 = new StringBuilder(39);
        for (int i = 24; i < 40; i += 2) {
            if (i > 24) ipv6.append(":");
            int b1 = packet.get(i) & 0xFF;
            int b2 = packet.get(i + 1) & 0xFF;
            ipv6.append(Integer.toHexString((b1 << 8) | b2));
        }
        String destIp = ipv6.toString();

        int destPort = 0;
        if (packet.remaining() >= 42) {  // 40-byte header + 2 bytes for port
            destPort = packet.getShort(42) & 0xFFFF;
        }

        // Performance: Connection-based logging using flow cache
        String flowKey = destIp + ":" + destPort + ":" + protoName;
        if (shouldLogFlow(flowKey)) {
            if (storage != null) {
                NetworkEvent netEvent = new NetworkEvent(
                        destIp, destPort, protoName + "_IPv6", packet.remaining(), 0, android.os.Process.myUid());
                storage.store(netEvent);
            }
        }
        
        // Check block
        return shouldBlockIPv6Connection(destIp, destPort, protoName);
    }
    
    // IPv6 blocking
    private boolean shouldBlockIPv6Connection(String destIp, int destPort, String protocol) {
        // User blocking
        if (!blockingEnabled && !blockAllTraffic) {
            return false;
        }
        
        // Emergency kill switch - block ALL traffic if ransomware detected
        if (blockAllTraffic) {
            Log.e(TAG, "EMERGENCY MODE: Blocking IPv6 traffic");
            return true;
        }
        
        // Block malicious ports
        if (destPort == 4444 || destPort == 5555 || destPort == 6666 || destPort == 7777) {
            Log.w(TAG, "BLOCKED: Suspicious IPv6 port " + destPort);
            return true;
        }
        
        // Allow localhost traffic
        if (destIp.equals("::1") || destIp.startsWith("0000:0000:0000:0000:0000:0000:0000:0001")) {
            return false;  // Local traffic, allow
        }
        
        // Allow link-local traffic
        if (destIp.startsWith("fe80:") || destIp.startsWith("fe90:") || destIp.startsWith("fea0:") || destIp.startsWith("feb0:")) {
            return false;  // Link-local, allow
        }
        
        // Block multicast traffic
        if (destIp.startsWith("ff")) {
            return true;
        }
        
        // Allow IPv6 traffic
        return false;
    }

    // Check C2 DNS
    private boolean isDnsQueryForC2Domain(ByteBuffer packet) {
        if (!blockingEnabled && !blockAllTraffic) return false;
        // Minimum DNS length
        if (packet.remaining() < 40) return false;
        // Check UDP protocol
        if ((packet.get(9) & 0xFF) != 17) return false;
        // Check IHL length
        int ihl = (packet.get(0) & 0x0F) * 4;
        if (ihl < 20 || packet.remaining() < ihl + 20) return false;
        // Check destination port
        int dstPort = packet.getShort(ihl + 2) & 0xFFFF;
        if (dstPort != 53) return false;
        // Check DNS payload
        int dnsOffset = ihl + 8;
        if (packet.remaining() < dnsOffset + 12) return false;
        // Intercept DNS queries
        if ((packet.get(dnsOffset + 2) & 0x80) != 0) return false;
        // Reconstruct DNS QNAME
        int offset = dnsOffset + 12;
        StringBuilder domain = new StringBuilder(64);
        try {
            while (offset < packet.limit()) {
                int labelLen = packet.get(offset++) & 0xFF;
                if (labelLen == 0) break;
                if (labelLen > 63 || offset + labelLen > packet.limit()) return false;
                if (domain.length() > 0) domain.append('.');
                for (int i = 0; i < labelLen; i++) {
                    domain.append((char) (packet.get(offset++) & 0xFF));
                }
            }
        } catch (Exception e) {
            return false;
        }

        String queried = domain.toString().toLowerCase(java.util.Locale.ROOT);
        for (String c2 : C2_DOMAINS) {
            if (queried.equals(c2) || queried.endsWith("." + c2)) {
                Log.w(TAG, "BLOCKED: DNS query for C2 domain " + queried);
                return true;
            }
        }
        return false;
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
        // Check user blocking
        if (!blockingEnabled && !blockAllTraffic) {
            return false;
        }
        
        // Emergency kill switch - block ALL traffic if ransomware detected
        if (blockAllTraffic) {
            Log.e(TAG, "EMERGENCY MODE: Blocking all traffic");
            return true;
        }
        
        // Block malicious ports
        if (destPort == 4444 || destPort == 5555 || destPort == 6666 || destPort == 7777) {
            Log.w(TAG, "BLOCKED: Suspicious port " + destPort);
            return true;
        }
        
        // Block Tor nodes
        if (isTorExitNode(destIp)) {
            Log.w(TAG, "BLOCKED: Tor exit node " + destIp);
            return true;
        }
        
        // Block suspicious IPs
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
        // Allow local traffic
        if (ip.startsWith("127.") || ip.startsWith("169.254.")) {
            return false; // Local traffic, allow
        }
        
        // Allow private networks
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
        
        // Block multicast traffic
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
            // Fallback notification
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
