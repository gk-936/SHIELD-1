package com.dearmoon.shield.analysis

import android.graphics.Color

/**
 * Classifies the ransomware family detected by SHIELD.
 * Each entry provides human-readable labels, CERT-In category mapping,
 * and a severity colour used in both the in-app report and the PDF export.
 */
enum class AttackFamily(
    val displayName: String,
    val description: String,
    val certInCategory: String,
    val severityColor: Int
) {

    CRYPTO_RANSOMWARE(
        displayName = "Crypto Ransomware",
        description = "Encrypts user files and demands payment for the decryption key.",
        certInCategory = "Malicious Code - Ransomware",
        severityColor = Color.parseColor("#FF3B3B")   // RED
    ),

    LOCKER_RANSOMWARE(
        displayName = "Locker Ransomware",
        description = "Locks the device screen to deny access without encrypting files.",
        certInCategory = "Malicious Code - Ransomware",
        severityColor = Color.parseColor("#FF6D00")   // ORANGE
    ),

    HYBRID(
        displayName = "Hybrid Ransomware",
        description = "Combines screen locking and file encryption for maximum damage.",
        certInCategory = "Malicious Code - Ransomware",
        severityColor = Color.parseColor("#B71C1C")   // DARK_RED
    ),

    RECONNAISSANCE(
        displayName = "Reconnaissance",
        description = "Probes file system and network resources ahead of a ransomware payload.",
        certInCategory = "Malicious Code - Spyware / Information Stealer",
        severityColor = Color.parseColor("#FFB300")   // AMBER / YELLOW
    ),

    UNKNOWN(
        displayName = "Unknown Threat",
        description = "Suspicious behaviour detected but insufficient signals for classification.",
        certInCategory = "Malicious Code - Unknown",
        severityColor = Color.parseColor("#8892A4")   // GREY
    )
}
