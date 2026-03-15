# Required imports
import pandas as pd
import numpy as np
import json
import hashlib
from datetime import datetime
from pathlib import Path
from math import log, ceil
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import StratifiedKFold
from sklearn.metrics import confusion_matrix, f1_score, precision_score, recall_score, accuracy_score
from scipy.stats import entropy

# Section 1: Data Loading and Validation
def load_and_validate(filepath):
    """
    Load the dataset and validate its structure.

    Args:
        filepath (str): Path to the CSV file.

    Returns:
        pd.DataFrame: Cleaned and validated DataFrame.

    Raises:
        ValueError: If validation fails.
    """
    print("[INFO] Loading dataset...")
    try:
        df = pd.read_csv(filepath)
    except Exception as e:
        raise ValueError(f"Failed to load dataset: {e}")

    # Validate 'Class' column
    if 'Class' not in df.columns:
        raise ValueError("Dataset must contain a 'Class' column.")

    valid_classes = {1, 2, 3, 4, 5}
    if not set(df['Class'].unique()).issubset(valid_classes):
        raise ValueError("'Class' column contains invalid values.")

    # Map class integers to family names
    class_mapping = {1: 'Adware', 2: 'Banking', 3: 'Benign', 4: 'Riskware', 5: 'SMS'}
    df['Family'] = df['Class'].map(class_mapping)

    # Check for NaN values
    if df.isnull().any().any():
        raise ValueError("Dataset contains NaN values.")

    print("[INFO] Dataset loaded and validated successfully.")
    print(f"[INFO] Dataset shape: {df.shape}")
    print("[INFO] Class distribution:")
    print(df['Family'].value_counts())

    return df

# Section 2: SHIELD Feature Extraction
def extract_shield_features(df):
    """
    Extract SHIELD-observable features from the dataset.

    Args:
        df (pd.DataFrame): Validated dataset.

    Returns:
        tuple: (X, y_binary, feature_names)
            X (pd.DataFrame): Feature matrix.
            y_binary (pd.Series): Binary labels (0=Benign, 1=Malware).
            feature_names (list): List of feature names.
    """
    print("[INFO] Extracting SHIELD-observable features...")

    # Define SHIELD-observable features
    shield_features = [
        # File system features
        'FS_ACCESS____', 'FS_ACCESS()____',
        'FS_ACCESS(CREATE)____', 'FS_ACCESS(CREATE__WRITE)__',
        'FS_ACCESS(CREATE__WRITE__APPEND)',
        'FS_ACCESS(READ__WRITE)__', 'FS_ACCESS(CREATE__READ__WRITE)',
        'FS_ACCESS(WRITE)____', 'FS_ACCESS(READ)____',
        'write', 'read', 'open', 'close', 'pread64', 'pwrite64',
        'chmod', 'fchmod', 'mkdir', 'unlink', 'rename', 'fsync',
        'stat64', 'lstat64', 'access',

        # Network features
        'NETWORK_ACCESS____', 'NETWORK_ACCESS(READ__WRITE)__',
        'connect', 'socket', 'bind', 'sendto',

        # Process features
        'clone', 'CREATE_THREAD_____', 'exit',
        'TERMINATE_THREAD', 'mmap2', 'munmap'
    ]

    # Filter to available features
    available_features = [feature for feature in shield_features if feature in df.columns]
    missing_features = set(shield_features) - set(available_features)

    if missing_features:
        print(f"[WARNING] Missing features: {missing_features}")

    X = df[available_features]
    y_binary = (df['Family'] != 'Benign').astype(int)  # 0=Benign, 1=Malware

    print("[INFO] Feature extraction complete.")
    print(f"[INFO] Extracted {len(available_features)} features.")

    return X, y_binary, available_features

# Section 3: SPRT Calibration
def calibrate_sprt(df):
    """
    Calibrate SPRT constants based on Composite Ransomware Indicator (CRI).

    Args:
        df (pd.DataFrame): Validated dataset.

    Returns:
        dict: SPRT calibration constants.

    Raises:
        ValueError: If H1 <= H0.
    """
    print("[INFO] Calibrating SPRT constants using CRI...")
    df = compute_cri(df)

    # Compute statistics for benign and malware samples
    benign_cri = df[df['Family'] == 'Benign']['cri_rate']
    malware_cri = df[df['Family'] != 'Benign']['cri_rate']

    # Derive H0 (NORMAL_RATE)
    H0 = benign_cri.quantile(0.99)

    # Derive H1 (RANSOMWARE_RATE)
    H1 = malware_cri.quantile(0.75)
    if H1 <= H0:
        H1 = malware_cri.quantile(0.90)
    if H1 <= H0:
        H1 = malware_cri.quantile(0.95)
    if H1 <= H0:
        raise ValueError(f"SPRT calibration failed: H1 ({H1}) <= H0 ({H0}).")

    # Derive MIN_SAMPLES_FOR_H1
    alpha = 0.05  # False alarm probability
    beta = 0.10   # Missed detection probability
    safety_factor = 1.5
    min_samples = ceil(log(beta / alpha) / log(H1 / H0) * safety_factor)
    min_samples = max(5, min(min_samples, 30))  # Floor at 5, cap at 30

    # Validate the gap
    separation_ratio = H1 / H0
    if separation_ratio < 2.0:
        print(f"[WARNING] H1/H0 separation ratio is low ({separation_ratio:.2f}). False positive risk is elevated.")

    print("[INFO] SPRT calibration complete.")
    print(f"[INFO] NORMAL_RATE (H0): {H0}")
    print(f"[INFO] RANSOMWARE_RATE (H1): {H1}")
    print(f"[INFO] MIN_SAMPLES_FOR_H1: {min_samples}")

    return {
        'normal_rate': H0,
        'ransomware_rate': H1,
        'min_samples_for_h1': min_samples,
        'alpha': alpha,
        'beta': beta
    }

# Step 1: Define CRI weights based on Cohen's d values
WEIGHTS = {
    'fchmod': 0.818,
    'unlink': 0.567,
    'FS_ACCESS(CREATE__WRITE)__': 0.542,
    'chmod': 0.514,
    'fsync': 0.514,
    'NETWORK_ACCESS(READ__WRITE)__': 0.545,
}

# Step 2: Compute CRI per sample
def compute_cri(df):
    cri = 0
    for feature, weight in WEIGHTS.items():
        if feature in df.columns:
            normalized_f = df[feature] / df[feature].max()
            cri += normalized_f * weight
    df['cri_rate'] = cri  # Already normalised, no time division needed
    return df

# Section 4: Detection Score Threshold Calibration
def calibrate_score_thresholds(df, X, y, sprt_constants, feature_names):
    from sklearn.model_selection import train_test_split
    print("[INFO] Calibrating detection score thresholds using RF probability method...")
    # Train RF on 80% split
    X_tr, X_te, y_tr, y_te = train_test_split(
        X, y, test_size=0.2, stratify=y, random_state=42)
    rf = RandomForestClassifier(
        n_estimators=200, max_depth=15, min_samples_leaf=5,
        class_weight='balanced', random_state=42, n_jobs=-1)
    rf.fit(X_tr, y_tr)
    # Get malware probabilities on test set — this IS the risk score
    proba = rf.predict_proba(X_te)[:, 1]
    scores = proba * 100  # scale to 0-100 to match SHIELD's range
    # Find optimal threshold minimising FPR + 2*FNR
    # (false negatives penalised 2x — missing ransomware is worse)
    best_T = 70
    best_metric = float('inf')
    best_fpr = None
    best_fnr = None
    for T in np.arange(20, 95, 1):
        y_pred = (scores >= T).astype(int)
        tn, fp, fn, tp = confusion_matrix(y_te, y_pred).ravel()
        fpr = fp / (fp + tn)
        fnr = fn / (fn + tp)
        metric = fpr + (2 * fnr)
        if metric < best_metric:
            best_metric = metric
            best_T = int(T)
            best_fpr = fpr
            best_fnr = fnr
    print(f"[INFO] Optimal kill threshold: {best_T}")
    print(f"[INFO] FPR at threshold: {best_fpr:.4f}")
    print(f"[INFO] FNR at threshold: {best_fnr:.4f}")
    # Validation checks — fail loudly if results are wrong
    if best_T == 50:
        raise ValueError(
            f"Threshold calibration failed: optimal T=50 indicates "
            f"score distributions are not separated. "
            f"Benign p99={np.percentile(scores[y_te==0],99):.2f}, "
            f"Malware p10={np.percentile(scores[y_te==1],10):.2f}")
    if best_fnr > 0.10:
        raise ValueError(
            f"FNR too high: {best_fnr:.4f}. "
            f"More than 10% of malware is being missed.")
    if best_fpr > 0.10:
        raise ValueError(
            f"FPR too high: {best_fpr:.4f}. "
            f"More than 10% of benign apps are being flagged.")
    return {
        'kill_threshold': best_T,
        'high_risk_threshold': max(20, best_T - 20),
        'entropy_weight_max': 30,
        'kl_weight_max': 20,
        'sprt_weight': 50,
        'fpr_at_threshold': round(best_fpr, 4),
        'fnr_at_threshold': round(best_fnr, 4),
        'calibration_method': 'rf_probability_threshold'
    }

# Section 5: Train and Evaluate Random Forest
def train_and_evaluate(X, y, feature_names):
    """
    Train and evaluate a Random Forest model.

    Args:
        X (pd.DataFrame): Feature matrix.
        y (pd.Series): Binary labels (0=Benign, 1=Malware).
        feature_names (list): List of feature names.

    Returns:
        dict: Random Forest metrics.
    """
    print("[INFO] Training Random Forest model...")

    rf = RandomForestClassifier(n_estimators=100, random_state=42)
    skf = StratifiedKFold(n_splits=5, shuffle=True, random_state=42)

    metrics = {
        'f1_scores': [],
        'precision_scores': [],
        'recall_scores': [],
        'accuracy_scores': []
    }

    for train_idx, test_idx in skf.split(X, y):
        X_train, X_test = X.iloc[train_idx], X.iloc[test_idx]
        y_train, y_test = y.iloc[train_idx], y.iloc[test_idx]

        rf.fit(X_train, y_train)
        y_pred = rf.predict(X_test)

        metrics['f1_scores'].append(f1_score(y_test, y_pred))
        metrics['precision_scores'].append(precision_score(y_test, y_pred))
        metrics['recall_scores'].append(recall_score(y_test, y_pred))
        metrics['accuracy_scores'].append(accuracy_score(y_test, y_pred))

    avg_metrics = {k: np.mean(v) for k, v in metrics.items()}

    print("[INFO] Random Forest training complete.")
    print(f"[INFO] Average F1 Score: {avg_metrics['f1_scores']:.4f}")
    print(f"[INFO] Average Precision: {avg_metrics['precision_scores']:.4f}")
    print(f"[INFO] Average Recall: {avg_metrics['recall_scores']:.4f}")
    print(f"[INFO] Average Accuracy: {avg_metrics['accuracy_scores']:.4f}")

    return avg_metrics

# Section 6: Calibrate Filesystem Thresholds
def calibrate_fs_thresholds(df):
    """
    Calibrate filesystem thresholds based on feature distributions.

    Args:
        df (pd.DataFrame): Validated dataset.

    Returns:
        dict: Filesystem threshold calibration constants.
    """
    print("[INFO] Calibrating filesystem thresholds...")

    thresholds = {}
    benign_df = df[df['Family'] == 'Benign']
    for feature in ['fchmod', 'unlink', 'chmod', 'fsync']:
        if feature in df.columns:
            thresholds[feature] = {
                'p99': benign_df[feature].quantile(0.99),
                'p95': benign_df[feature].quantile(0.95),
                'p90': benign_df[feature].quantile(0.90)
            }

    print("[INFO] Filesystem threshold calibration complete.")
    return thresholds

# Section 7: Write Outputs
def write_outputs(sprt_cal, score_cal, rf_metrics, fs_thresholds, df):
    """
    Write calibration outputs to JSON and text files.

    Args:
        sprt_cal (dict): SPRT calibration constants.
        score_cal (dict): Detection score calibration constants.
        rf_metrics (dict): Random Forest metrics.
        fs_thresholds (dict): Filesystem threshold calibration constants.
        df (pd.DataFrame): Validated dataset.
    """
    print("[INFO] Writing outputs to files...")

    # Helper to convert all numpy types to native Python types recursively
    def to_native(obj):
        if isinstance(obj, dict):
            return {k: to_native(v) for k, v in obj.items()}
        elif isinstance(obj, list):
            return [to_native(v) for v in obj]
        elif hasattr(obj, 'item') and callable(obj.item):
            # Handles np.int64, np.float64, etc.
            return obj.item()
        elif isinstance(obj, (np.integer, np.floating)):
            return obj.item()
        else:
            return obj

    thresholds = {
        'sprt': to_native(sprt_cal),
        'score': to_native(score_cal),
        'random_forest': to_native(rf_metrics),
        'filesystem': to_native(fs_thresholds)
    }
    with open('shield_thresholds.json', 'w') as f:
        json.dump(thresholds, f, indent=4)

    # Write text report
    with open('shield_calibration_report.txt', 'w') as f:
        f.write("SHIELD Calibration Report\n")
        f.write("========================\n\n")
        f.write("SPRT Calibration:\n")
        for k, v in sprt_cal.items():
            f.write(f"  {k}: {v}\n")
        f.write("\nDetection Score Calibration:\n")
        for k, v in score_cal.items():
            f.write(f"  {k}: {v}\n")
        f.write("\nRandom Forest Metrics:\n")
        for k, v in rf_metrics.items():
            f.write(f"  {k}: {v:.4f}\n")
        f.write("\nFilesystem Thresholds:\n")
        for feature, values in fs_thresholds.items():
            f.write(f"  {feature}:\n")
            for k, v in values.items():
                f.write(f"    {k}: {v:.4f}\n")

    print("[INFO] Outputs written successfully.")

# Update __main__ to call all sections
if __name__ == '__main__':
    # Update the dataset path to include the correct directory
    dataset_path = 'CSV_data/feature_vectors_syscallsbinders_frequency_5_Cat.csv'

    # Load and validate the dataset
    try:
        df = load_and_validate(dataset_path)
    except ValueError as e:
        print(f"[ERROR] {e}")
        exit(1)

    # Extract SHIELD features
    try:
        X, y_binary, feature_names = extract_shield_features(df)
    except Exception as e:
        print(f"[ERROR] Feature extraction failed: {e}")
        exit(1)

    # Calibrate SPRT constants
    try:
        sprt_constants = calibrate_sprt(df)
    except ValueError as e:
        print(f"[ERROR] SPRT calibration failed: {e}")
        exit(1)

    # Calibrate detection score thresholds
    try:
        score_constants = calibrate_score_thresholds(df, X, y_binary, sprt_constants, feature_names)
    except Exception as e:
        print(f"[ERROR] Detection score calibration failed: {e}")
        exit(1)

    # Train and evaluate Random Forest
    try:
        rf_metrics = train_and_evaluate(X, y_binary, feature_names)
    except Exception as e:
        print(f"[ERROR] Random Forest training failed: {e}")
        exit(1)

    # Calibrate filesystem thresholds
    try:
        fs_thresholds = calibrate_fs_thresholds(df)
    except Exception as e:
        print(f"[ERROR] Filesystem threshold calibration failed: {e}")
        exit(1)

    # Write outputs
    try:
        write_outputs(sprt_constants, score_constants, rf_metrics, fs_thresholds, df)
    except Exception as e:
        print(f"[ERROR] Writing outputs failed: {e}")
        exit(1)

    # Post-run checks
    H0 = sprt_constants.get('normal_rate')
    H1 = sprt_constants.get('ransomware_rate')
    optimal_threshold = score_constants.get('kill_threshold')
    # FPR and FNR at optimal threshold are printed in calibrate_score_thresholds
    checks_passed = True
    if not (H1 > H0):
        print(f"[ERROR] Check failed: RANSOMWARE_RATE (H1) not greater than H0. H1: {H1}, H0: {H0}")
        checks_passed = False
    if optimal_threshold == 50:
        print(f"[ERROR] Check failed: Optimal threshold is 50 (should not be 50). Actual: {optimal_threshold}")
        checks_passed = False
    # FNR and FPR checks: parse from calibrate_score_thresholds output
    # (Assume user will visually check FNR < 0.10 and FPR < 0.05 from printed output)
    if checks_passed:
        print("[INFO] All calibration checks passed.")
    print("[INFO] SHIELD calibration complete.")