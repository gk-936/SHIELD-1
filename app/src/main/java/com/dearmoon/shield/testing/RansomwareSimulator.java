package com.dearmoon.shield.testing;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * Safe ransomware simulator for testing SHIELD detection capabilities.
 * Mimics ransomware behavior WITHOUT actually encrypting or destroying files.
 */
public class RansomwareSimulator {
    private static final String TAG = "RansomwareSimulator";
    private final Context context;
    private volatile boolean running = false;
    private Thread simulatorThread;

    public RansomwareSimulator(Context context) {
        this.context = context;
    }

    /**
     * Test 1: Rapid File Modification (SPRT Detector)
     * Simulates ransomware encrypting multiple files quickly
     */
    public void testRapidFileModification() {
        running = true;
        simulatorThread = new Thread(() -> {
            Log.d(TAG, "[TEST 1] Starting rapid file modification test...");
            File testDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (testDir == null || !testDir.exists()) {
                Log.e(TAG, "[TEST 1] FAILED: Documents directory not accessible");
                return;
            }
            File testSubDir = new File(testDir, "shield_test");
            testSubDir.mkdirs();

            try {
                // Create and modify 20 files in 2 seconds (10 files/sec - triggers SPRT)
                for (int i = 0; i < 20 && running; i++) {
                    File testFile = new File(testSubDir, "test_file_" + i + ".txt");
                    
                    // Write random data (simulates encryption)
                    FileOutputStream fos = new FileOutputStream(testFile);
                    byte[] data = new byte[5000]; // 5KB
                    new Random().nextBytes(data);
                    fos.write(data);
                    fos.close();
                    
                    Log.d(TAG, "[TEST 1] Modified file " + (i + 1) + "/20: " + testFile.getName());
                    Thread.sleep(100); // 10 files/sec
                }
                Log.d(TAG, "[TEST 1] COMPLETED - Should trigger SPRT detector (H1 acceptance)");
            } catch (Exception e) {
                Log.e(TAG, "[TEST 1] FAILED: " + e.getMessage());
            }
        });
        simulatorThread.start();
    }

    /**
     * Test 2: High Entropy File Creation (Entropy Analyzer)
     * Creates files with high randomness (>7.5 entropy)
     */
    public void testHighEntropyFiles() {
        running = true;
        simulatorThread = new Thread(() -> {
            Log.d(TAG, "[TEST 2] Starting high entropy file test...");
            File testDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (testDir == null || !testDir.exists()) {
                Log.e(TAG, "[TEST 2] FAILED: Downloads directory not accessible");
                return;
            }
            File testSubDir = new File(testDir, "shield_test");
            testSubDir.mkdirs();

            try {
                for (int i = 0; i < 5 && running; i++) {
                    File testFile = new File(testSubDir, "encrypted_" + i + ".dat");
                    
                    // Write highly random data (entropy ~8.0)
                    FileOutputStream fos = new FileOutputStream(testFile);
                    byte[] randomData = new byte[8192]; // 8KB
                    new Random().nextBytes(randomData);
                    fos.write(randomData);
                    fos.close();
                    
                    Log.d(TAG, "[TEST 2] Created high-entropy file: " + testFile.getName());
                    Thread.sleep(500);
                }
                Log.d(TAG, "[TEST 2] COMPLETED - Should trigger entropy analyzer (>7.5)");
            } catch (Exception e) {
                Log.e(TAG, "[TEST 2] FAILED: " + e.getMessage());
            }
        });
        simulatorThread.start();
    }

    /**
     * Test 3: Uniform Byte Distribution (KL-Divergence)
     * Creates files with uniform byte distribution (low KL-divergence)
     */
    public void testUniformByteDistribution() {
        running = true;
        simulatorThread = new Thread(() -> {
            Log.d(TAG, "[TEST 3] Starting uniform byte distribution test...");
            File testDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            if (testDir == null || !testDir.exists()) {
                Log.e(TAG, "[TEST 3] FAILED: Pictures directory not accessible");
                return;
            }
            File testSubDir = new File(testDir, "shield_test");
            testSubDir.mkdirs();

            try {
                for (int i = 0; i < 5 && running; i++) {
                    File testFile = new File(testSubDir, "uniform_" + i + ".bin");
                    
                    // Write uniformly distributed bytes (KL < 0.1)
                    FileOutputStream fos = new FileOutputStream(testFile);
                    byte[] uniformData = new byte[8192];
                    Random rand = new Random();
                    for (int j = 0; j < uniformData.length; j++) {
                        uniformData[j] = (byte) rand.nextInt(256); // All bytes equally likely
                    }
                    fos.write(uniformData);
                    fos.close();
                    
                    Log.d(TAG, "[TEST 3] Created uniform distribution file: " + testFile.getName());
                    Thread.sleep(500);
                }
                Log.d(TAG, "[TEST 3] COMPLETED - Should trigger KL-divergence detector (<0.1)");
            } catch (Exception e) {
                Log.e(TAG, "[TEST 3] FAILED: " + e.getMessage());
            }
        });
        simulatorThread.start();
    }

    /**
     * Test 4: Honeyfile Access (Honeyfile Collector)
     * Attempts to modify honeyfiles to trigger detection
     */
    public void testHoneyfileAccess() {
        running = true;
        simulatorThread = new Thread(() -> {
            Log.d(TAG, "[TEST 4] Starting honeyfile access test...");
            
            try {
                // Search for honeyfiles in monitored directories
                File[] searchDirs = {
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                };

                int honeyfilesFound = 0;
                for (File dir : searchDirs) {
                    if (dir != null && dir.exists()) {
                        File[] files = dir.listFiles();
                        if (files != null) {
                            for (File file : files) {
                                if (file.getName().contains("IMPORTANT") || 
                                    file.getName().contains("BACKUP") ||
                                    file.getName().contains("PRIVATE") ||
                                    file.getName().contains("CREDENTIALS") ||
                                    file.getName().contains("PASSWORDS")) {
                                    
                                    Log.d(TAG, "[TEST 4] Found honeyfile: " + file.getAbsolutePath());
                                    
                                    // Attempt to MODIFY (triggers FileObserver)
                                    try {
                                        FileOutputStream fos = new FileOutputStream(file, true);
                                        fos.write("RANSOMWARE TEST ACCESS\n".getBytes());
                                        fos.close();
                                        Log.d(TAG, "[TEST 4] Modified honeyfile: " + file.getName());
                                        honeyfilesFound++;
                                    } catch (Exception e) {
                                        Log.d(TAG, "[TEST 4] Could not modify: " + file.getName());
                                    }
                                    
                                    Thread.sleep(500);
                                }
                            }
                        }
                    }
                }
                
                if (honeyfilesFound > 0) {
                    Log.d(TAG, "[TEST 4] COMPLETED - Modified " + honeyfilesFound + " honeyfiles");
                } else {
                    Log.d(TAG, "[TEST 4] WARNING - No honeyfiles found. Start ShieldProtectionService first.");
                }
            } catch (Exception e) {
                Log.e(TAG, "[TEST 4] FAILED: " + e.getMessage());
            }
        });
        simulatorThread.start();
    }

    /**
     * Test 5: Suspicious Network Activity (Network Guard)
     * Simulates C2 communication to malicious IPs/ports
     */
    public void testSuspiciousNetworkActivity() {
        running = true;
        simulatorThread = new Thread(() -> {
            Log.d(TAG, "[TEST 5] Starting suspicious network activity test...");
            
            try {
                // Test malicious ports
                String[] maliciousPorts = {"4444", "5555", "6666", "7777"};
                for (String port : maliciousPorts) {
                    try {
                        Log.d(TAG, "[TEST 5] Attempting connection to malicious port: " + port);
                        URL url = new URL("http://example.com:" + port);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(2000);
                        conn.connect();
                        conn.disconnect();
                    } catch (Exception e) {
                        Log.d(TAG, "[TEST 5] Connection blocked/failed (expected): " + port);
                    }
                    Thread.sleep(500);
                }

                // Test Tor exit node IPs (will be blocked if VPN enabled)
                String[] torNodes = {
                    "185.220.101.1",
                    "45.61.185.1",
                    "185.141.25.1"
                };
                for (String ip : torNodes) {
                    try {
                        Log.d(TAG, "[TEST 5] Attempting connection to Tor node: " + ip);
                        URL url = new URL("http://" + ip);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(2000);
                        conn.connect();
                        conn.disconnect();
                    } catch (Exception e) {
                        Log.d(TAG, "[TEST 5] Connection blocked/failed (expected): " + ip);
                    }
                    Thread.sleep(500);
                }
                
                Log.d(TAG, "[TEST 5] COMPLETED - Check telemetry for network events");
            } catch (Exception e) {
                Log.e(TAG, "[TEST 5] FAILED: " + e.getMessage());
            }
        });
        simulatorThread.start();
    }

    /**
     * Test 6: Combined Attack (All Detectors)
     * Simulates full ransomware attack sequence
     */
    public void testFullRansomwareSimulation() {
        running = true;
        simulatorThread = new Thread(() -> {
            Log.d(TAG, "[TEST 6] ========================================");
            Log.d(TAG, "[TEST 6] FULL RANSOMWARE SIMULATION STARTING");
            Log.d(TAG, "[TEST 6] ========================================");
            
            try {
                // Phase 1: Network C2 communication
                Log.d(TAG, "[TEST 6] Phase 1: Establishing C2 connection...");
                try {
                    URL url = new URL("http://example.com:4444");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(2000);
                    conn.connect();
                    conn.disconnect();
                } catch (Exception e) {
                    Log.d(TAG, "[TEST 6] C2 connection blocked (expected)");
                }
                Thread.sleep(1000);

                // Phase 2: Honeyfile reconnaissance and modification
                Log.d(TAG, "[TEST 6] Phase 2: Scanning and modifying valuable files...");
                File docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                if (docsDir != null && docsDir.exists()) {
                    File[] files = docsDir.listFiles();
                    if (files != null) {
                        for (File file : files) {
                            if (file.getName().contains("IMPORTANT") || file.getName().contains("PRIVATE")) {
                                try {
                                    FileOutputStream fos = new FileOutputStream(file, true);
                                    fos.write("ENCRYPTED".getBytes());
                                    fos.close();
                                    Log.d(TAG, "[TEST 6] Modified target file: " + file.getName());
                                } catch (Exception ignored) {}
                                break;
                            }
                        }
                    }
                }
                Thread.sleep(1000);

                // Phase 3: Rapid encryption simulation
                Log.d(TAG, "[TEST 6] Phase 3: Encrypting files...");
                File testDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
                if (testDir == null || !testDir.exists()) {
                    Log.e(TAG, "[TEST 6] FAILED: DCIM directory not accessible");
                    return;
                }
                File testSubDir = new File(testDir, "shield_test");
                testSubDir.mkdirs();
                
                Random rand = new Random();
                for (int i = 0; i < 15 && running; i++) {
                    File testFile = new File(testSubDir, "victim_file_" + i + ".encrypted");
                    
                    // High entropy + uniform distribution + rapid modification
                    FileOutputStream fos = new FileOutputStream(testFile);
                    byte[] encryptedData = new byte[8192];
                    rand.nextBytes(encryptedData);
                    fos.write(encryptedData);
                    fos.close();
                    
                    Log.d(TAG, "[TEST 6] Encrypted file " + (i + 1) + "/15");
                    Thread.sleep(150); // ~6-7 files/sec
                }

                Log.d(TAG, "[TEST 6] ========================================");
                Log.d(TAG, "[TEST 6] SIMULATION COMPLETED");
                Log.d(TAG, "[TEST 6] Expected: HIGH RISK detection (score ≥70)");
                Log.d(TAG, "[TEST 6] Expected: Emergency network blocking triggered");
                Log.d(TAG, "[TEST 6] ========================================");
                
            } catch (Exception e) {
                Log.e(TAG, "[TEST 6] FAILED: " + e.getMessage());
            }
        });
        simulatorThread.start();
    }

    /**
     * Test 7: Benign Activity (False Positive Check)
     * Simulates normal app behavior that should NOT trigger detection
     */
    public void testBenignActivity() {
        running = true;
        simulatorThread = new Thread(() -> {
            Log.d(TAG, "[TEST 7] Starting benign activity test (should NOT trigger)...");
            File testDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (testDir == null || !testDir.exists()) {
                Log.e(TAG, "[TEST 7] FAILED: Documents directory not accessible");
                return;
            }
            File testSubDir = new File(testDir, "shield_benign_test");
            testSubDir.mkdirs();

            try {
                // Slow file creation with low entropy
                for (int i = 0; i < 5 && running; i++) {
                    File testFile = new File(testSubDir, "normal_document_" + i + ".txt");
                    
                    // Write structured text (low entropy ~4.5)
                    FileOutputStream fos = new FileOutputStream(testFile);
                    String normalText = "This is a normal text document. ".repeat(100);
                    fos.write(normalText.getBytes());
                    fos.close();
                    
                    Log.d(TAG, "[TEST 7] Created normal file: " + testFile.getName());
                    Thread.sleep(3000); // Slow: 0.33 files/sec
                }
                Log.d(TAG, "[TEST 7] COMPLETED - Should NOT trigger detection (low risk)");
            } catch (Exception e) {
                Log.e(TAG, "[TEST 7] FAILED: " + e.getMessage());
            }
        });
        simulatorThread.start();
    }

    /**
     * Stop all running tests
     */
    public void stopSimulation() {
        running = false;
        if (simulatorThread != null) {
            simulatorThread.interrupt();
        }
        Log.d(TAG, "Simulation stopped");
    }

    /**
     * Clean up test files
     */
    public void cleanupTestFiles() {
        String[] testDirs = {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/shield_test",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) + "/shield_benign_test",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) + "/shield_test",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES) + "/shield_test",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM) + "/shield_test"
        };
        
        for (String dirPath : testDirs) {
            File testDir = new File(dirPath);
            if (testDir.exists()) {
                File[] files = testDir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        file.delete();
                    }
                }
                testDir.delete();
            }
        }
        Log.d(TAG, "Test files cleaned up");
    }

    public boolean isRunning() {
        return running;
    }
}
