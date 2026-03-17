/**
 * SHIELD RANSOMWARE SIMULATOR — SECURITY RESEARCH ONLY
 * =====================================================
 * Package: com.dearmoon.shield.ransim
 *
 * SAFETY CONSTRAINTS:
 * - All file operations confined to sandbox directory only
 * - XOR cipher only (key 0x5A) — NOT real encryption  
 * - Locker overlay always shows password (TEST PASSWORD: 1234)
 * - STOP TEST button always accessible, no password needed
 * - Network simulation targets localhost only (127.0.0.1)
 * - Cleanup/restore runs automatically on stop or app exit
 *
 * SANDBOX PATH:
 * /sdcard/Android/data/com.dearmoon.shield.ransim/shield_ransim_sandbox/
 *
 * TO FORCE CLEANUP IF APP CRASHES:
 * adb shell rm -rf /sdcard/Android/data/com.dearmoon.shield.ransim/
 *
 * FILTER LOGS:
 * adb logcat -s SHIELD_RANSIM
 */
package com.dearmoon.shield.ransim;

import android.content.*;
import android.os.*;
import android.util.Log;
import android.view.View;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class RansomwareSimulator {
    private static final byte XOR_KEY = 0x5A;
    private static final String TAG = "SHIELD_RANSIM";

    // Seed test files in sandbox if not present
    public void seedTestFiles(File sandboxRoot, LogCallback log) {
        File docs = new File(sandboxRoot, "documents");
        File photos = new File(sandboxRoot, "photos");
        File notes = new File(sandboxRoot, "notes");
        docs.mkdirs(); photos.mkdirs(); notes.mkdirs();
        try {
            createFileIfMissing(new File(docs, "report_q1.txt"),
                "Q1 2024 Business Report\nExecutive Summary\nThis report covers the financial performance of the organization\nfor the first quarter of 2024. Revenue increased by 12% compared\nto the same period last year, driven primarily by expansion into\nnew markets in Southeast Asia.\n\nKey Metrics\nTotal Revenue: $2,847,000\nOperating Expenses: $1,923,000  \nNet Profit: $924,000\nEmployee Count: 147\n\nOutlook\nThe board has approved a budget increase of 15% for Q2 to support\nthe planned product launch scheduled for May 2024.");
            createFileIfMissing(new File(docs, "invoice_march.txt"),
                "INVOICE #INV-2024-0847\nDate: March 15, 2024\nDue Date: April 15, 2024\n\nBill To:\nAcme Corporation\n123 Business Street\nChennai, Tamil Nadu 600001\n\nDescription                    Qty    Rate      Amount\nSoftware Development Services   40   2500.00   100000.00\nUI/UX Design Consultation        8   3000.00    24000.00\nServer Infrastructure Setup      1  15000.00    15000.00\n\nSubtotal:  139000.00\nGST (18%):  25020.00\nTotal:     164020.00\n\nBank: HDFC Bank | Account: 50100123456789 | IFSC: HDFC0001234");
            createFileIfMissing(new File(docs, "contract_draft.txt"),
                "SERVICE AGREEMENT\nThis agreement is entered into as of January 1, 2024 between\nTechCorp Solutions Pvt Ltd (Service Provider) and ClientCo\nIndustries (Client).\n\n1. SERVICES: Provider agrees to deliver software development\nservices as outlined in Schedule A attached hereto.\n\n2. PAYMENT: Client agrees to pay monthly retainer of INR 150,000\npayable within 30 days of invoice receipt.\n\n3. TERM: This agreement commences January 1, 2024 and continues\nfor a period of twelve (12) months unless terminated earlier.");
            createFileIfMissing(new File(photos, "vacation_photo.jpg.txt"),
                "JFIF_SIMULATED_HEADER_FF_D8_FF_E0\n[Simulated JPEG binary data for testing — not a real image]\nLocation: Ooty, Tamil Nadu\nDate: December 2023\nCamera: Pixel 7 Pro\nResolution: 4032x3024\nFile size: 3.2MB\nDescription: Family vacation photo at Ooty botanical gardens");
            createFileIfMissing(new File(photos, "profile_picture.png.txt"),
                "PNG_SIMULATED_HEADER_89_50_4E_47\n[Simulated PNG binary data for testing — not a real image]\nDimensions: 512x512\nColor depth: 32-bit RGBA\nCreated: 2024-01-15\nDescription: Professional profile photo");
            createFileIfMissing(new File(notes, "credentials_backup.txt"),
                "Personal Account Backup (TEST DATA — NOT REAL)\nEmail: testuser@example.com / TestPass2024!\nNetflix: test_account@gmail.com / Netflix#456\nBanking App: testbank_user / BankTest789\nWiFi Home: MyHomeNetwork / WifiPass2024\nNote: Change all passwords quarterly");
            createFileIfMissing(new File(notes, "todo_list.txt"),
                "Personal TODO List — March 2024\n\nWork:\n[x] Submit Q1 report to manager\n[ ] Review pull requests from dev team\n[ ] Schedule client meeting for product demo\n[ ] Update project documentation\n\nPersonal:\n[ ] Pay electricity bill (due 20th)\n[ ] Book train tickets to Bangalore\n[ ] Doctor appointment — annual checkup\n[ ] Call mom on Sunday\n\nShopping:\n[ ] Groceries — milk, bread, eggs, vegetables\n[ ] New laptop charger (original broke)\n[ ] Birthday gift for Rahul (birthday 25th)");
            log.log("Test files seeded");
        } catch (Exception e) {
            log.log("[ERROR] Seeding files: " + e.getMessage());
        }
    }

    public void clearSandbox(File sandboxRoot, LogCallback log) {
        if (sandboxRoot.exists()) {
            deleteRecursive(sandboxRoot);
            log.log("Sandbox cleared");
        }
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            for (File child : fileOrDirectory.listFiles()) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private void createFileIfMissing(File f, String content) throws IOException {
        if (!f.exists()) {
            try (FileOutputStream fos = new FileOutputStream(f)) {
                fos.write(content.getBytes());
            }
        }
    }

    public List<File> collectTargetFiles(File sandboxRoot) {
        List<File> files = new ArrayList<>();
        collectFilesRecursive(sandboxRoot, files);
        files.sort(Comparator.comparing(File::getAbsolutePath));
        return files;
    }

    private void collectFilesRecursive(File dir, List<File> files) {
        if (dir == null || !dir.exists()) return;
        if (dir.isFile()) {
            if (!dir.getName().endsWith(".enc") && !dir.getName().equals("RANSOM_NOTE.txt"))
                files.add(dir);
        } else {
            File[] list = dir.listFiles();
            if (list == null) return;
            for (File f : list) collectFilesRecursive(f, files);
        }
    }

    public byte[] readFileBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int read = fis.read(data);
            if (read != data.length) throw new IOException("Read error");
            return data;
        }
    }

    public void xorEncryptToFile(byte[] input, File outFile) throws IOException {
        byte[] out = new byte[input.length];
        for (int i = 0; i < input.length; i++) out[i] = (byte) (input[i] ^ XOR_KEY);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(out);
        }
    }

    public void writeRansomNote(File f) throws IOException {
        String note = "YOUR FILES HAVE BEEN ENCRYPTED\n\n[SHIELD SECURITY TEST — THIS IS SIMULATED]\n\nAll your documents, photos, and notes have been encrypted\nwith military-grade encryption. To recover your files you\nmust pay 0.05 BTC to the following address:\n\n1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf\n\nYou have 48 hours. After that the decryption key will be\npermanently deleted.\n\nDO NOT: restart your device, contact police, use antivirus\n\n[THIS IS A SHIELD RANSOMWARE SIMULATOR TEST FILE]\n[No real encryption was performed. Tap STOP ALL to restore.]";
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(note.getBytes());
        }
    }

    public void simulateC2(LogCallback log) {
        int[] ports = {4444}; // Only one port for Crypto scenario
        for (int port : ports) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                log.log("SIM_C2_ATTEMPT port=" + port + " result=connected");
            } catch (IOException e) {
                log.log("SIM_C2_ATTEMPT port=" + port + " result=refused");
            }
        }
    }

    public interface LogCallback {
        void log(String msg);
    }
}
