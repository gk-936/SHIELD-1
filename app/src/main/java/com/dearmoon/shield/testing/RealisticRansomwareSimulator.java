package com.dearmoon.shield.testing;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Realistic Ransomware Simulator - Redesigned
 * 
 * Simulates actual ransomware behavior patterns:
 * - Multi-stage attack flow
 * - Reconnaissance → C2 → Encryption → Ransom note
 * - File renaming patterns
 * - Burst behavior
 * - Safe (uses dedicated test directory)
 * - Fully trackable and cleanable
 */
public class RealisticRansomwareSimulator {
    private static final String TAG = "RealisticRansomware";
    
    private final Context context;
    private final TestFileManager fileManager;
    private volatile boolean running = false;
    private Thread simulatorThread;
    
    public RealisticRansomwareSimulator(Context context) {
        this.context = context;
        this.fileManager = new TestFileManager(context);
    }
    
    /**
     * Test 1: Multi-Stage Ransomware Attack (REALISTIC)
     * Simulates complete attack flow with all stages
     */
    public void testMultiStageAttack() {
        running = true;
        simulatorThread = new Thread(() -> {
            try {
                Log.i(TAG, "=== MULTI-STAGE RANSOMWARE ATTACK ===");
                
                // Stage 1: Reconnaissance
                Log.i(TAG, "Stage 1: Reconnaissance");
                List<File> targetFiles = performReconnaissance();
                Thread.sleep(500);
                
                // Stage 2: C2 Communication
                Log.i(TAG, "Stage 2: C2 Communication");
                attemptC2Connection();
                Thread.sleep(500);
                
                // Stage 3: Honeyfile Test (Safe - creates fake honeyfiles)
                Log.i(TAG, "Stage 3: Honeyfile Probing");
                createAndAccessFakeHoneyfiles();
                Thread.sleep(500);
                
                // Stage 4: Encryption Burst
                Log.i(TAG, "Stage 4: Encryption Burst");
                performEncryptionBurst(targetFiles);
                Thread.sleep(500);
                
                // Stage 5: File Renaming
                Log.i(TAG, "Stage 5: File Renaming");
                renameFilesToEncrypted(targetFiles);
                Thread.sleep(500);
                
                // Stage 6: Ransom Note
                Log.i(TAG, "Stage 6: Ransom Note Creation");
                createRansomNote();
                
                Log.i(TAG, "=== ATTACK COMPLETE ===");
                Log.i(TAG, "Expected: HIGH RISK (score ≥70), Emergency mode triggered");
                
            } catch (Exception e) {
                Log.e(TAG, "Multi-stage attack failed", e);
            }
        });
        simulatorThread.start();
    }
    
    /**
     * Stage 1: Reconnaissance - Scan directories and identify targets
     */
    private List<File> performReconnaissance() {
        List<File> targets = new ArrayList<>();
        File testRoot = fileManager.getTestRootDir();
        
        // Create sample files to "discover"
        String[] fileTypes = {".txt", ".jpg", ".pdf", ".docx", ".xlsx"};
        for (int i = 0; i < 10; i++) {
            String ext = fileTypes[i % fileTypes.length];
            File file = new File(testRoot, "document_" + i + ext);
            try {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(("Sample content " + i).getBytes());
                fos.close();
                targets.add(file);
                fileManager.trackFile(file);
                Log.d(TAG, "Discovered target: " + file.getName());
            } catch (Exception e) {
                Log.e(TAG, "Failed to create target file", e);
            }
        }
        
        return targets;
    }
    
    /**
     * Stage 2: C2 Communication
     */
    private void attemptC2Connection() {
        String[] maliciousPorts = {"4444", "6666"};
        for (String port : maliciousPorts) {
            try {
                URL url = new URL("http://example.com:" + port);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(1000);
                conn.connect();
                conn.disconnect();
            } catch (Exception e) {
                Log.d(TAG, "C2 connection blocked (expected): " + port);
            }
        }
    }
    
    /**
     * Stage 3: Create and access fake honeyfiles (SAFE - doesn't touch real ones)
     */
    private void createAndAccessFakeHoneyfiles() {
        File testRoot = fileManager.getTestRootDir();
        String[] honeyfileNames = {"IMPORTANT_BACKUP.txt", "PASSWORDS.txt", "PRIVATE_KEYS.dat"};
        
        for (String name : honeyfileNames) {
            File honeyfile = new File(testRoot, name);
            try {
                // Create fake honeyfile
                FileOutputStream fos = new FileOutputStream(honeyfile);
                fos.write("Fake honeyfile content".getBytes());
                fos.close();
                fileManager.trackFile(honeyfile);
                
                // Access it (triggers detection)
                FileInputStream fis = new FileInputStream(honeyfile);
                fis.read();
                fis.close();
                
                Log.d(TAG, "Accessed fake honeyfile: " + name);
            } catch (Exception e) {
                Log.e(TAG, "Failed to access fake honeyfile", e);
            }
        }
    }
    
    /**
     * Stage 4: Encryption Burst - Rapid file modification
     */
    private void performEncryptionBurst(List<File> targets) {
        Random rand = new Random();
        int count = 0;
        
        for (File file : targets) {
            if (!running) break;
            
            try {
                // Simulate encryption: overwrite with high-entropy data
                FileOutputStream fos = new FileOutputStream(file);
                byte[] encrypted = new byte[8192];
                rand.nextBytes(encrypted);
                fos.write(encrypted);
                fos.close();
                
                count++;
                Log.d(TAG, "Encrypted file " + count + "/" + targets.size() + ": " + file.getName());
                
                Thread.sleep(100); // ~10 files/sec (triggers SPRT)
            } catch (Exception e) {
                Log.e(TAG, "Failed to encrypt file", e);
            }
        }
    }
    
    /**
     * Stage 5: File Renaming - Add .encrypted extension
     */
    private void renameFilesToEncrypted(List<File> targets) {
        for (File file : targets) {
            if (!file.exists()) continue;
            
            File renamed = new File(file.getParent(), file.getName() + ".encrypted");
            if (file.renameTo(renamed)) {
                fileManager.trackFile(renamed);
                Log.d(TAG, "Renamed: " + file.getName() + " → " + renamed.getName());
            }
        }
    }
    
    /**
     * Stage 6: Ransom Note Creation
     */
    private void createRansomNote() {
        File testRoot = fileManager.getTestRootDir();
        File ransomNote = new File(testRoot, "README_RESTORE_FILES.txt");
        
        try {
            FileOutputStream fos = new FileOutputStream(ransomNote);
            String note = "YOUR FILES HAVE BEEN ENCRYPTED\\n" +
                         "To restore your files, contact: ransomware@test.com\\n" +
                         "Payment required: 1 BTC\\n" +
                         "\\n[THIS IS A TEST - NO ACTUAL ENCRYPTION]";
            fos.write(note.getBytes());
            fos.close();
            fileManager.trackFile(ransomNote);
            Log.i(TAG, "Ransom note created: " + ransomNote.getName());
        } catch (Exception e) {
            Log.e(TAG, "Failed to create ransom note", e);
        }
    }
    
    /**
     * Test 2: Rapid File Modification (SPRT Trigger)
     */
    public void testRapidFileModification() {
        running = true;
        simulatorThread = new Thread(() -> {
            try {
                File testRoot = fileManager.getTestRootDir();
                Random rand = new Random();
                
                for (int i = 0; i < 20 && running; i++) {
                    File file = new File(testRoot, "rapid_" + i + ".dat");
                    FileOutputStream fos = new FileOutputStream(file);
                    byte[] data = new byte[5000];
                    rand.nextBytes(data);
                    fos.write(data);
                    fos.close();
                    fileManager.trackFile(file);
                    
                    Log.d(TAG, "Modified file " + (i + 1) + "/20");
                    Thread.sleep(100); // 10 files/sec
                }
                
                Log.i(TAG, "SPRT test complete - Expected: ACCEPT_H1");
            } catch (Exception e) {
                Log.e(TAG, "Rapid modification test failed", e);
            }
        });
        simulatorThread.start();
    }
    
    /**
     * Test 3: High Entropy Files
     */
    public void testHighEntropyFiles() {
        running = true;
        simulatorThread = new Thread(() -> {
            try {
                File testRoot = fileManager.getTestRootDir();
                Random rand = new Random();
                
                for (int i = 0; i < 5 && running; i++) {
                    File file = new File(testRoot, "entropy_" + i + ".dat");
                    FileOutputStream fos = new FileOutputStream(file);
                    byte[] data = new byte[8192];
                    rand.nextBytes(data);
                    fos.write(data);
                    fos.close();
                    fileManager.trackFile(file);
                    
                    Log.d(TAG, "Created high-entropy file: " + file.getName());
                    Thread.sleep(500);
                }
                
                Log.i(TAG, "Entropy test complete - Expected: Entropy >7.5");
            } catch (Exception e) {
                Log.e(TAG, "Entropy test failed", e);
            }
        });
        simulatorThread.start();
    }
    
    /**
     * Test 4: Network Activity
     */
    public void testNetworkActivity() {
        running = true;
        simulatorThread = new Thread(() -> {
            try {
                String[] ports = {"4444", "5555", "6666", "7777"};
                for (String port : ports) {
                    try {
                        URL url = new URL("http://example.com:" + port);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setConnectTimeout(2000);
                        conn.connect();
                        conn.disconnect();
                    } catch (Exception e) {
                        Log.d(TAG, "Connection blocked: " + port);
                    }
                    Thread.sleep(500);
                }
                
                Log.i(TAG, "Network test complete");
            } catch (Exception e) {
                Log.e(TAG, "Network test failed", e);
            }
        });
        simulatorThread.start();
    }
    
    /**
     * Test 5: Benign Activity (False Positive Check)
     */
    public void testBenignActivity() {
        running = true;
        simulatorThread = new Thread(() -> {
            try {
                File testRoot = fileManager.getTestRootDir();
                
                for (int i = 0; i < 5 && running; i++) {
                    File file = new File(testRoot, "benign_" + i + ".txt");
                    FileOutputStream fos = new FileOutputStream(file);
                    String text = "This is normal text content. ".repeat(100);
                    fos.write(text.getBytes());
                    fos.close();
                    fileManager.trackFile(file);
                    
                    Log.d(TAG, "Created benign file: " + file.getName());
                    Thread.sleep(3000); // Slow: 0.33 files/sec
                }
                
                Log.i(TAG, "Benign test complete - Expected: LOW RISK");
            } catch (Exception e) {
                Log.e(TAG, "Benign test failed", e);
            }
        });
        simulatorThread.start();
    }
    
    /**
     * Stop simulation
     */
    public void stopSimulation() {
        running = false;
        if (simulatorThread != null) {
            simulatorThread.interrupt();
        }
    }
    
    /**
     * Cleanup all test files
     */
    public TestFileManager.CleanupResult cleanupTestFiles() {
        return fileManager.clearAllTestFiles();
    }
    
    /**
     * Get file manager
     */
    public TestFileManager getFileManager() {
        return fileManager;
    }
    
    /**
     * Check if running
     */
    public boolean isRunning() {
        return running;
    }
}
