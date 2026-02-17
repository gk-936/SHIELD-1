"""
Test SHIELD app detection logic using AndMal2020 ransomware dataset.
Uses a Python replica of the app's detection (entropy + KL + SPRT + behavior score)
without modifying the app. Maps dataset feature vectors to synthetic file events,
runs detection, and outputs a confusion matrix.
"""

import csv
import math
import random
import os

# Import SHIELD detection logic from existing test script
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from test_shield_with_cicmaldroid import (
    SAMPLE_SIZE, UNIFORM_PROB, NORMAL_RATE, RANSOMWARE_RATE,
    ALPHA, BETA, A, B, HIGH_RISK_THRESHOLD,
    shannon_entropy, kl_divergence, calculate_multi_region_entropy,
    SPRTState, SPRTDetector,
    calculate_confidence_score, calculate_behavior_score, shield_detect
)

# ---------------------------------------------------------------------------
# Map AndMal2020 features to synthetic file events
# ---------------------------------------------------------------------------

def extract_fileio_activity(row_dict):
    """Extract file I/O activity from AndMal2020 features."""
    fileio_keys = [
        'API_FileIO_libcore.io.IoBridge_open',
        'API_FileIO_android.content.ContextWrapper_openFileInput',
        'API_FileIO_android.content.ContextWrapper_openFileOutput',
        'API_FileIO_android.content.ContextWrapper_deleteFile'
    ]
    total = 0
    for key in fileio_keys:
        try:
            total += float(row_dict.get(key, 0))
        except (ValueError, TypeError):
            pass
    return int(total)

def extract_network_activity(row_dict):
    """Extract network activity from AndMal2020 features."""
    network_keys = [
        'API_Network_java.net.URL_openConnection',
        'API_Network_org.apache.http.impl.client.AbstractHttpClient_execute',
        'API_Network_com.android.okhttp.internal.huc.HttpURLConnectionImpl_getInputStream',
        'API_Network_com.android.okhttp.internal.http.HttpURLConnectionImpl_getInputStream',
        'Network_TotalReceivedBytes',
        'Network_TotalTransmittedBytes'
    ]
    total = 0
    for key in network_keys:
        try:
            val = float(row_dict.get(key, 0))
            # Normalize bytes to count (1MB = 1 event)
            if 'Bytes' in key:
                val = val / (1024 * 1024)
            total += val
        except (ValueError, TypeError):
            pass
    return max(0, int(total))

def extract_crypto_activity(row_dict):
    """Extract crypto activity (encryption indicators)."""
    crypto_keys = [
        'API_Crypto_javax.crypto.spec.SecretKeySpec_$init',
        'API_Crypto_javax.crypto.Cipher_doFinal',
        'API_Crypto-Hash_java.security.MessageDigest_digest',
        'API_Crypto-Hash_java.security.MessageDigest_update'
    ]
    total = 0
    for key in crypto_keys:
        try:
            total += float(row_dict.get(key, 0))
        except (ValueError, TypeError):
            pass
    return int(total)

def extract_total_activity(row_dict):
    """Extract total activity level from all API calls."""
    total = 0
    for key, value in row_dict.items():
        if key.startswith('API_') and key not in ['Category', 'Family', 'Hash']:
            try:
                total += float(value)
            except (ValueError, TypeError):
                pass
    return total

def synthesize_file_from_features(row_dict, rng, size=8192):
    """
    Synthesize file content based on AndMal2020 features.
    Higher crypto/file activity -> more random bytes -> higher entropy.
    """
    crypto_activity = extract_crypto_activity(row_dict)
    fileio_activity = extract_fileio_activity(row_dict)
    total_api = extract_total_activity(row_dict)
    
    # Normalize activity (use crypto + fileio as primary indicators)
    activity_score = (crypto_activity * 2 + fileio_activity) / max(1, total_api / 100)
    activity_normalized = min(1.0, activity_score / 50.0)  # Cap at 1.0
    
    buf = bytearray(size)
    for i in range(size):
        if rng.random() < activity_normalized:
            buf[i] = rng.randint(0, 255)
        else:
            buf[i] = 0x20  # Low-entropy bias
    return bytes(buf)

def features_to_modification_rate(row_dict):
    """Map file I/O activity to modification rate (files/sec)."""
    fileio = extract_fileio_activity(row_dict)
    # Scale: 0-100 fileio -> 0-10 files/sec
    rate = min(10.0, fileio / 10.0)
    return max(0.1, rate)  # Minimum 0.1 files/sec

# ---------------------------------------------------------------------------
# Load AndMal2020 ransomware dataset
# ---------------------------------------------------------------------------

def load_andmal2020_ransomware(csv_path):
    """Load ransomware samples from AndMal2020 CSV."""
    rows = []
    with open(csv_path, "r", encoding="utf-8", errors="ignore", newline="") as f:
        reader = csv.DictReader(f)
        for row in reader:
            category = row.get('Category', '').strip()
            if category.lower() == 'ransomware':
                rows.append(row)
    return rows

def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    
    # Load both ransomware CSV files
    csv_before = os.path.join(script_dir, "AndMal2020-dynamic-BeforeAndAfterReboot", 
                              "Ransomware_before_reboot_Cat.csv")
    csv_after = os.path.join(script_dir, "AndMal2020-dynamic-BeforeAndAfterReboot",
                             "Ransomware_after_reboot_Cat.csv")
    
    print("Loading AndMal2020 ransomware dataset...")
    data = []
    
    if os.path.exists(csv_before):
        print(f"Loading: {csv_before}")
        data.extend(load_andmal2020_ransomware(csv_before))
        print(f"  Loaded {len(data)} samples")
    
    if os.path.exists(csv_after):
        print(f"Loading: {csv_after}")
        after_data = load_andmal2020_ransomware(csv_after)
        data.extend(after_data)
        print(f"  Loaded {len(after_data)} additional samples")
    
    if not data:
        print("No ransomware samples found!")
        return
    
    print(f"\nTotal ransomware samples: {len(data)}")
    
    # Get unique families
    families = set()
    for row in data:
        fam = row.get('Family', 'unknown')
        families.add(fam)
    print(f"Ransomware families: {len(families)} ({', '.join(sorted(families)[:10])}...)")
    
    rng = random.Random(42)
    y_true = []
    y_pred = []
    scores_list = []
    families_list = []
    
    print("\nRunning SHIELD detection logic on synthetic file events...")
    for idx, row in enumerate(data):
        if (idx + 1) % 500 == 0:
            print(f"  Processed {idx + 1}/{len(data)} samples...")
        
        # Synthesize file content and modification rate from features
        file_bytes = synthesize_file_from_features(row, rng)
        modification_rate = features_to_modification_rate(row)
        
        # Extract event counts for behavior correlation
        file_event_count = extract_fileio_activity(row)
        network_event_count = extract_network_activity(row)
        honeyfile_event_count = 0  # Not available in this dataset
        locker_event_count = 1 if 'lockscreen' in row.get('Family', '').lower() else 0
        
        # Run SHIELD detection
        score, high_risk = shield_detect(
            file_bytes, modification_rate,
            file_event_count, network_event_count,
            honeyfile_event_count, locker_event_count
        )
        
        # All samples are ransomware (ground truth = 1)
        y_true.append(1)
        y_pred.append(1 if high_risk else 0)
        scores_list.append(score)
        families_list.append(row.get('Family', 'unknown'))
    
    # Confusion matrix: All samples are ransomware
    # TP = predicted malware, actual malware
    # FN = predicted benign, actual malware
    tp = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 1)
    fn = sum(1 for t, p in zip(y_true, y_pred) if t == 1 and p == 0)
    tn = 0  # No benign samples
    fp = 0  # No benign samples
    
    # Print results
    print("\n" + "=" * 60)
    print("SHIELD detection logic tested with AndMal2020 Ransomware")
    print("(Python replica of app logic; no app code modified)")
    print("=" * 60)
    print("\nConfusion Matrix")
    print("                Predicted")
    print("                Benign   Malware")
    print("Actual Benign   %6d   %6d" % (tn, fp))
    print("Actual Malware  %6d   %6d" % (fn, tp))
    print()
    total = tp + fn
    if total > 0:
        recall = tp / total  # True Positive Rate (detection rate)
        precision = tp / (tp + fp) if (tp + fp) > 0 else 1.0
        f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0
        
        print("Total ransomware samples:  %d" % total)
        print("Detection Rate (Recall):   %.4f (%d/%d)" % (recall, tp, total))
        print("False Negative Rate:       %.4f (%d/%d)" % (fn/total, fn, total))
        print("Precision:                 %.4f" % precision)
        print("F1 score:                  %.4f" % f1)
        print()
        print("Average detection score:   %.2f" % (sum(scores_list) / len(scores_list)))
        print("Min score:                 %d" % min(scores_list))
        print("Max score:                 %d" % max(scores_list))
        print()
        
        # Detection by family
        family_stats = {}
        for fam, pred, score in zip(families_list, y_pred, scores_list):
            if fam not in family_stats:
                family_stats[fam] = {'total': 0, 'detected': 0, 'scores': []}
            family_stats[fam]['total'] += 1
            if pred == 1:
                family_stats[fam]['detected'] += 1
            family_stats[fam]['scores'].append(score)
        
        print("Detection by Ransomware Family:")
        print("-" * 60)
        for fam in sorted(family_stats.keys()):
            stats = family_stats[fam]
            rate = stats['detected'] / stats['total'] if stats['total'] > 0 else 0
            avg_score = sum(stats['scores']) / len(stats['scores']) if stats['scores'] else 0
            print("  %-20s: %3d/%3d (%.1f%%) detected, avg score: %.1f" % 
                  (fam[:20], stats['detected'], stats['total'], rate*100, avg_score))
    
    print()
    print("(Ground truth: All samples are ransomware; Prediction: SHIELD totalScore >= 70)")
    print("(Total score = fileScore (0-100) + behaviorScore (0-30), capped at 130)")

if __name__ == "__main__":
    main()
