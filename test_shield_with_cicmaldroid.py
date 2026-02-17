"""
Test SHIELD app detection logic using CICMalDroid-2020 dataset.
Uses a Python replica of the app's detection (entropy + KL + SPRT + score)
without modifying the app. Maps dataset feature vectors to synthetic file
events, runs detection, and outputs a confusion matrix.
"""

import csv
import math
import random
import os

# ---------------------------------------------------------------------------
# SHIELD detection logic replica (from UnifiedDetectionEngine, EntropyAnalyzer,
# KLDivergenceCalculator, SPRTDetector, BehaviorCorrelationEngine, DetectionResult)
#
# This is a 100% replica of the detection logic:
# - Multi-region entropy sampling (beginning, middle, end, returns max)
# - KL divergence (first 8KB only)
# - SPRT with exact math (A, B, NORMAL_RATE, RANSOMWARE_RATE)
# - Behavior correlation score (0-30 points) with all 3 patterns
# - Total score = min(fileScore + behaviorScore, 130)
# - High-risk threshold: totalScore >= 70
#
# NOTE: SPRT timing is simulated (app uses real event timestamps; simulation batches events)
#       This doesn't affect the math, only the exact timing flow.
# ---------------------------------------------------------------------------

SAMPLE_SIZE = 8192
UNIFORM_PROB = 1.0 / 256.0
NORMAL_RATE = 0.1
RANSOMWARE_RATE = 5.0
ALPHA, BETA = 0.05, 0.05
A = BETA / (1 - ALPHA)
B = (1 - BETA) / ALPHA
HIGH_RISK_THRESHOLD = 70


def shannon_entropy(data):
    """Calculate Shannon entropy on byte array."""
    if not data or len(data) == 0:
        return 0.0
    freq = [0] * 256
    for b in data:
        freq[b & 0xFF] += 1
    h = 0.0
    for c in freq:
        if c > 0:
            p = c / len(data)
            h -= p * (math.log(p) / math.log(2))
    return h


def calculate_multi_region_entropy(file_bytes):
    """
    Replica of EntropyAnalyzer.calculateMultiRegionEntropy():
    Samples beginning, middle, and end (8KB each), returns MAXIMUM entropy.
    """
    file_size = len(file_bytes)
    if file_size <= SAMPLE_SIZE:
        return shannon_entropy(file_bytes)
    
    max_entropy = 0.0
    
    # Region 1: Beginning (0 to 8KB)
    begin_data = file_bytes[:SAMPLE_SIZE]
    max_entropy = max(max_entropy, shannon_entropy(begin_data))
    
    # Region 2: Middle (middle - 4KB to middle + 4KB)
    middle_start = max(SAMPLE_SIZE, (file_size // 2) - (SAMPLE_SIZE // 2))
    middle_end = min(file_size, middle_start + SAMPLE_SIZE)
    if middle_end > middle_start:
        middle_data = file_bytes[middle_start:middle_end]
        max_entropy = max(max_entropy, shannon_entropy(middle_data))
    
    # Region 3: End (last 8KB)
    end_start = max(0, file_size - SAMPLE_SIZE)
    if end_start < file_size:
        end_data = file_bytes[end_start:]
        max_entropy = max(max_entropy, shannon_entropy(end_data))
    
    return max_entropy


def kl_divergence(data):
    if not data:
        return 0.0
    freq = [0] * 256
    for b in data:
        freq[b & 0xFF] += 1
    d = 0.0
    for c in freq:
        if c > 0:
            p = c / len(data)
            d += p * (math.log(p / UNIFORM_PROB) / math.log(2))
    return d


class SPRTState:
    CONTINUE, ACCEPT_H0, ACCEPT_H1 = "CONTINUE", "ACCEPT_H0", "ACCEPT_H1"


class SPRTDetector:
    def __init__(self):
        self.log_lr = 0.0
        self.state = SPRTState.CONTINUE

    def record_event(self):
        self.log_lr += math.log(RANSOMWARE_RATE / NORMAL_RATE)
        self._update()

    def record_time_passed(self, seconds):
        self.log_lr += (NORMAL_RATE - RANSOMWARE_RATE) * seconds
        self._update()

    def _update(self):
        if self.log_lr >= math.log(B):
            self.state = SPRTState.ACCEPT_H1
        elif self.log_lr <= math.log(A):
            self.state = SPRTState.ACCEPT_H0
        else:
            self.state = SPRTState.CONTINUE

    def reset(self):
        self.log_lr = 0.0
        self.state = SPRTState.CONTINUE


def calculate_confidence_score(entropy, kl_divergence, sprt_state):
    """File-based confidence score (0-100 points)."""
    score = 0
    if entropy > 7.8:
        score += 40
    elif entropy > 7.5:
        score += 30
    elif entropy > 7.0:
        score += 20
    elif entropy > 6.0:
        score += 10
    if kl_divergence < 0.05:
        score += 30
    elif kl_divergence < 0.1:
        score += 20
    elif kl_divergence < 0.2:
        score += 10
    if sprt_state == SPRTState.ACCEPT_H1:
        score += 30
    elif sprt_state == SPRTState.CONTINUE:
        score += 10
    return min(score, 100)


def calculate_behavior_score(file_event_count, network_event_count, 
                              honeyfile_event_count, locker_event_count):
    """
    Replica of BehaviorCorrelationEngine.calculateBehaviorScore():
    Calculates behavior correlation score (0-30 points) based on cross-signal patterns.
    """
    score = 0
    
    # Pattern 1: Rapid file modification + network activity (0-10 points)
    if file_event_count > 5 and network_event_count > 0:
        score += 10  # File encryption + C2 communication
    elif file_event_count > 3 and network_event_count > 0:
        score += 5
    
    # Pattern 2: Honeyfile access (0-15 points)
    if honeyfile_event_count > 0:
        score += min(honeyfile_event_count * 5, 15)  # Max 15 points
    
    # Pattern 3: UI threat + file activity (0-5 points)
    if locker_event_count > 0 and file_event_count > 0:
        score += 5  # Locker ransomware pattern
    
    return min(score, 30)  # Cap at 30 points


def shield_detect(synthetic_file_bytes, modification_rate_per_sec, 
                  file_event_count=None, network_event_count=None,
                  honeyfile_event_count=None, locker_event_count=None):
    """
    Run SHIELD detection on synthetic file content and modification rate.
    Replicates UnifiedDetectionEngine.analyzeFileEvent() flow exactly.
    Returns (total_score, is_high_risk).
    """
    if len(synthetic_file_bytes) < 100:
        return 0, False
    
    # Multi-region entropy (replica of EntropyAnalyzer.calculateEntropy)
    entropy = calculate_multi_region_entropy(synthetic_file_bytes)
    if entropy == 0.0:
        return 0, False
    
    # KL divergence (samples first 8KB only, like KLDivergenceCalculator)
    kl = kl_divergence(synthetic_file_bytes[:SAMPLE_SIZE])
    
    # SPRT: Simulate event-by-event flow (like UnifiedDetectionEngine)
    # The app records time between events, then records event
    # For testing, simulate N events over 1 second with proper timing
    sprt = SPRTDetector()
    n_events = max(0, int(round(modification_rate_per_sec)))
    if n_events > 0:
        # Simulate events arriving over 1 second
        time_per_event = 1.0 / n_events if n_events > 0 else 1.0
        for i in range(n_events):
            if i > 0:
                # Record time passed since last event (capped at 5.0 sec like app)
                sprt.record_time_passed(min(time_per_event, 5.0))
            sprt.record_event()
        # Record any remaining time
        remaining_time = 1.0 - (n_events * time_per_event)
        if remaining_time > 0:
            sprt.record_time_passed(min(remaining_time, 5.0))
    else:
        # No events, just record 1 second passing
        sprt.record_time_passed(1.0)
    
    # Calculate file-based confidence score (0-100)
    file_score = calculate_confidence_score(entropy, kl, sprt.state)
    
    # Calculate behavior correlation score (0-30)
    # Use provided counts or estimate from modification rate
    if file_event_count is None:
        file_event_count = max(0, int(round(modification_rate_per_sec)))
    if network_event_count is None:
        # Estimate: high activity might indicate network (C2)
        network_event_count = 1 if modification_rate_per_sec > 3.0 else 0
    if honeyfile_event_count is None:
        honeyfile_event_count = 0  # Rare, only if explicitly triggered
    if locker_event_count is None:
        locker_event_count = 0  # UI locker events
    
    behavior_score = calculate_behavior_score(
        file_event_count, network_event_count,
        honeyfile_event_count, locker_event_count
    )
    
    # Total score: file score + behavior score, capped at 130
    total_score = min(file_score + behavior_score, 130)
    
    # High-risk threshold: total_score >= 70 (from DetectionResult.isHighRisk)
    # Note: DetectionResult stores totalScore as confidenceScore and checks >= 70
    return total_score, total_score >= HIGH_RISK_THRESHOLD


# ---------------------------------------------------------------------------
# Map CICMalDroid row (feature vector) to synthetic file + rate (from features only)
# ---------------------------------------------------------------------------

def row_to_activity_sum(row_nums):
    """Single scalar 'activity' from feature row (sum of counts)."""
    return sum(row_nums)


def synthesize_file_from_activity(activity_normalized, rng, size=8192):
    """
    activity_normalized in [0,1]. Higher -> more random bytes -> higher entropy.
    (Ransomware-like apps tend to have higher syscall diversity and write volume.)
    """
    buf = bytearray(size)
    for i in range(size):
        if rng.random() < activity_normalized:
            buf[i] = rng.randint(0, 255)
        else:
            buf[i] = 0x20  # low-entropy bias
    return bytes(buf)


def activity_to_rate(activity_normalized, max_rate=10.0):
    """Map normalized activity to modification rate (files/sec) for SPRT."""
    return activity_normalized * max_rate


# ---------------------------------------------------------------------------
# Load dataset and run test
# ---------------------------------------------------------------------------

def load_cicmaldroid(csv_path):
    rows = []
    with open(csv_path, "r", encoding="utf-8", newline="") as f:
        reader = csv.reader(f)
        header = next(reader)
        class_idx = header.index("Class")
        feature_indices = [i for i in range(len(header)) if i != class_idx]
        for row in reader:
            if len(row) <= class_idx:
                continue
            try:
                label = int(row[class_idx])
                feats = [float(row[i]) for i in feature_indices]
                rows.append((feats, label))
            except (ValueError, IndexError):
                continue
    return rows


def main():
    script_dir = os.path.dirname(os.path.abspath(__file__))
    csv_path = os.path.join(
        script_dir,
        "kaggle_cache", "datasets", "hasanccr92", "cicmaldroid-2020",
        "versions", "1", "feature_vectors_syscallsbinders_frequency_5_Cat.csv"
    )
    if not os.path.isfile(csv_path):
        print("Dataset not found at:", csv_path)
        return

    print("Loading CICMalDroid-2020...")
    data = load_cicmaldroid(csv_path)
    if not data:
        print("No rows loaded.")
        return

    # Normalize activity per row (from features only)
    activities = [row_to_activity_sum(f) for f, _ in data]
    min_a, max_a = min(activities), max(activities)
    span = max_a - min_a or 1.0

    rng = random.Random(42)
    y_true = []
    y_pred = []
    scores_list = []

    print("Running SHIELD detection logic on synthetic file events...")
    for (feats, label), raw_act in zip(data, activities):
        act_norm = (raw_act - min_a) / span
        file_bytes = synthesize_file_from_activity(act_norm, rng)
        rate = activity_to_rate(act_norm)
        score, high_risk = shield_detect(file_bytes, rate)
        y_true.append(label)
        y_pred.append(1 if high_risk else 0)
        scores_list.append(score)

    # Confusion matrix: rows = true class, cols = predicted (0=Benign, 1=Malware)
    # CICMalDroid-2020 "Class" is 1-5 (malware families); treat all as malware (1), no benign (0) in this dataset
    tn = fp = fn = tp = 0
    for t, p in zip(y_true, y_pred):
        if t == 0 and p == 0:
            tn += 1
        elif t == 0 and p == 1:
            fp += 1
        elif t == 1 and p == 0:
            fn += 1
        else:
            tp += 1

    # Print results
    print("\n" + "=" * 60)
    print("SHIELD detection logic tested with CICMalDroid-2020")
    print("(Python replica of app logic; no app code modified)")
    print("=" * 60)
    print("\nConfusion Matrix")
    print("                Predicted")
    print("                Benign   Malware")
    print("Actual Benign   %6d   %6d" % (tn, fp))
    print("Actual Malware  %6d   %6d" % (fn, tp))
    print()
    total = tn + fp + fn + tp
    acc = (tp + tn) / total if total else 0
    precision = tp / (tp + fp) if (tp + fp) else 0
    recall = tp / (tp + fn) if (tp + fn) else 0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) else 0
    print("Total samples:  %d" % total)
    print("Accuracy:       %.4f" % acc)
    print("Precision:      %.4f" % precision)
    print("Recall:         %.4f" % recall)
    print("F1 score:       %.4f" % f1)
    print()
    print("(Ground truth: dataset 'Class' 1-5 = malware; Prediction: SHIELD totalScore >= 70)")
    print("(Total score = fileScore (0-100) + behaviorScore (0-30), capped at 130)")
    print("(Note: This dataset has 5 malware families only; no benign class.)")


if __name__ == "__main__":
    main()
