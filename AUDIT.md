# SHIELD — Comprehensive Security & Architecture Audit

**Audit Date:** March 3, 2026  
**Audited By:** GitHub Copilot (Claude Sonnet 4.6)  
**Scope:** Full codebase review — detection logic, cryptographic subsystems, test evaluation, resilience mechanisms, and MVP readiness

---

## Understood Intent

SHIELD is a **rootless Android ransomware detection and mitigation system**. Its core loop is: monitor → detect → isolate → restore. It targets both file-encrypting ransomware (via entropy + SPRT + honeyfiles) and screen-locker ransomware (via Accessibility Service), with hardware-backed backup integrity as the recovery guarantee. The goal is an MVP that works on stock Android 7+ with no root, no cloud dependency, and no server infrastructure.

---

## STRENGTHS

**1. Multi-signal detection pipeline is architecturally sound.**
Combining Shannon entropy, KL-divergence, SPRT, honeyfile triggers, and behavior correlation is the right design. No single signal fires alone - you need at least two to hit the 70-point threshold. That's a real strength.

**2. Multi-region sampling for entropy is a smart bypass prevention.**
Sampling head/middle/tail and taking the maximum is a direct countermeasure to ransomware that only encrypts the first 4KB of files to save time. Most research-grade detectors miss this.

**3. Three-tier network blocking model is excellent.**
OFF → ON → EMERGENCY is exactly the right UX progression. Automatically escalating to block all traffic on confidence ≥ 70 without user interaction is the correct decision under time pressure.

**4. Cryptographic snapshot architecture is professional-grade.**
AES-256-GCM per-file encryption + per-file key wrapping via Android Keystore + hash chain integrity = genuinely publication-quality work. The `BackupEncryptionManager` design is one of the strongest parts of the project.

**5. TEE-anchored APK integrity gate is novel for an academic APK.**
Using HMAC-SHA256 with a hardware-backed key to detect APK tampering before the service initializes is a real contribution. Most student projects skip this entirely.

**6. LockerGuard covers a distinct attack vector most detectors ignore.**
File-encrypting ransomware gets all the research attention. Having a parallel Accessibility-Service-based locker detector for screen-ransom families is a meaningful differentiator.

**7. SPRT statistical testing uses correct Poisson math.**
The Wald sequential test with λ₀ = 0.1 and λ₁ = 5.0 event rates is mathematically correct. Most competing academic implementations use a simpler sliding-window counter.

---

## BLINDSPOTS AND WEAKNESSES

### Critical (Will break in real use or invalidate claims)

**1. Process attribution is fundamentally broken.**
In `UnifiedDetectionEngine.java`, the call to `correlationEngine.correlateFileEvent(filePath, event.getTimestamp(), android.os.Process.myUid())` passes **SHIELD's own UID** as the attacker UID. `Process.myUid()` returns the calling process's UID — which is SHIELD, not the app that modified the file. `BehaviorCorrelationEngine` then queries the database for network/file/locker events attributed to that UID and builds a profile of SHIELD's own behavior. The entire "Pseudo-Kernel" behavior attribution column is correlating against the wrong actor. This is the single most critical flaw in the detection pipeline. FileObserver does not expose which process caused a file event — there is no clean fix without root or a kernel eBPF probe.

**2. `killMaliciousProcess()` almost certainly does nothing.**
The code iterates `getRunningAppProcesses()`, finds the PID of the ransomware package, logs it, and then calls `am.killBackgroundProcesses(packageName)`. Look at the comment in the loop: *"We attempt to kill the process"* — but there is no actual `Process.killProcess(info.pid)` call inside the loop. The only active call is `killBackgroundProcesses()`, which only works on background processes. Any ransomware actively encrypting files is a foreground process (`START_STICKY` service with a notification), so this call silently does nothing. The detection fires, the "kill" call runs, ransomware continues encrypting, and your automated restore races against ongoing encryption.

**3. The test evaluation has zero visibility into false positives.**
Both test scripts (`test_shield_with_andmal2020.py`, `test_shield_with_cicmaldroid.py`) load **only ransomware samples**. The confusion matrix never has a benign column. A detector that marks every file event as ransomware would score 100% recall on your tests. You need benign samples — normal Android app file I/O, WhatsApp downloads, Spotify cache writes, Google Photos sync — to validate that the confidence score doesn't fire constantly during normal phone use. Right now this is your biggest evaluation gap.

**4. High-entropy legitimate file types will generate constant false positives.**
The entropy analyzer has no file extension allowlist. `.zip`, `.jpg`, `.mp4`, `.png`, `.pdf`, `.apk`, `.aac`, `.opus` — all of these have naturally high Shannon entropy (>7.5) because they are already compressed or encoded. When any of these files are modified (e.g., WhatsApp receiving a photo, a download completing), SHIELD will score 40 entropy points + 30 KL points = 70 total, instantly crossing the high-risk threshold and triggering emergency network isolation. This is a showstopper for daily usability.

**5. `EXPECTED_SIGNATURE_HASH = null` in production.**
In `SecurityUtils.java`, the expected APK signature hash is hardcoded to `null`. The `verifySignature()` method will always either return `false` (failing all security checks) or silently skip verification depending on its null-check logic. This was a placeholder that was never filled in. The RASP protection is neutered until this is set.

**6. Snapshot retention policy deletes the exactly wrong files during an active attack.**
The retention policy kicks in after every backup operation. When ransomware encrypts 150 files in a burst, the retention limit (100 files) forces deletion of the **oldest backups** — which are the original clean copies of the files encrypted earliest in the attack. You are systematically deleting the files you need most for recovery. The retention policy needs to be suspended during an active attack window, or at minimum should not purge files that are marked with an active `attack_id`.

---

### Significant (Limits MVP quality or real-world validity)

**7. SPRT rate parameters will false-positive on normal Android behavior.**
λ₁ = 5.0 files/second as the ransomware threshold means any app that writes 5+ files per second triggers H1. Google Photos backing up a burst of photos, a downloader completing a batch, a music app caching, or a game saving state would all exceed this rate. The 5.0 files/sec threshold comes from academic literature on PC ransomware — it has not been calibrated against real Android baseline behavior. You need to run the detection on a benign device for 24 hours and plot the actual file modification rate distribution before setting this parameter.

**8. Automated restore races against active encryption.**
`finalizeMitigationAndRestore()` waits 1 second after detection, then restores files. But if `killMaliciousProcess()` is ineffective (see point 2), the ransomware is still running during that 1 second, encrypting more files. The restore and the encryption are operating concurrently on the same file paths. Files restored will be immediately re-encrypted. The correct order is: kill or isolate the process first, verify it is gone, then restore.

**9. Test scripts test a Python reimplementation, not the Android code.**
The test scripts synthesize "file bytes" from API call count features in CICMalDroid by setting bytes randomly at a probability proportional to `crypto_activity`. This has no faithful correspondence to what real ransomware files look like on disk. You are testing whether a specific random seed produces high-entropy bytes, then detecting those synthetic bytes with your Python replica of the entropy algorithm. This tells you nothing about whether the Android `EntropyAnalyzer.java` behaves correctly on real ransomware-encrypted files.

**10. VPN blocklist is static and immediately outdated.**
The hardcoded Tor exit node ranges and ports (4444, 5555, 6666, 7777) are classic Android RAT ports from circa 2014–2018. Modern Android ransomware uses HTTPS over port 443 to standard cloud hosts (Pastebin, Telegram bots, GitHub Gists) as C2 channels. Your blocklist would never intercept these. The emergency all-traffic-block mode is the only effective network mitigation — the specific-port blocking gives a false sense of granular control.

**11. MediaStoreCollector disabled = blind spot for MSC-API ransomware.**
The README acknowledges it was disabled to prevent duplicate telemetry. But some Android ransomware families exclusively use `ContentResolver` to enumerate and modify media files, bypassing `FileObserver` entirely. Re-enabling it with proper deduplication (e.g., a timestamp-keyed LRU cache to deduplicate events with the same path within 500ms) would close this detection gap.

**12. `SharedPreferences` for the HMAC integrity baseline is manipulable on rooted devices.**
`ShieldIntegrityManager` stores the APK SHA-256 hash and HMAC in a private `SharedPreferences` XML file. On rooted devices (which are a realistic attack scenario since you explicitly check for root), an attacker can `adb shell` into `/data/data/com.dearmoon.shield/shared_prefs/shield_integrity.xml` and rewrite the stored hash to match a tampered APK. The HMAC protects against this only if the Keystore key is intact — but an attacker with root can also delete the Keystore entry, which triggers `KEY_INVALIDATED` / `TEE_KEY_MISSING`, which your code handles by simply re-initializing the baseline with the tampered APK. The TEE system provides real protection on non-rooted devices only.

**13. Score threshold of 70 is an unvalidated arbitrary value.**
There is no ROC curve, no precision-recall curve, no validation study showing that 70/130 is the optimal operating point. Moving it to 60 or 80 can dramatically change your precision-recall tradeoff. Before calling this an MVP, plot the distribution of detection scores across benign samples and ransomware samples and pick the threshold at their intersection.

---

### Minor (Polish / completeness gaps)

**14. `ServiceRestartReceiver` may violate Play Store policies.**
Auto-resurrection after force-stop is explicitly restricted on Android 8+ via the stopped-process state bit. While it may still work via `BOOT_COMPLETED`, the explicit self-restart-on-destroy loop could flag your app during Play Store review. Document this explicitly in your research context.

**15. No Doze Mode / App Standby accommodation.**
`FileObserver` event delivery can be delayed when the device enters Doze mode. The SPRT's inter-event timing (`deltaSeconds = (currentTime - lastEventTimestamp) / 1000.0`) will produce a large spike the next time the device wakes up, potentially triggering false H1 decisions. The 5-second cap on `deltaSeconds` is a partial mitigation, but not a full solution.

**16. The `RecoveryActivity` UI has no feedback during restore.**
Restore operations run on a background thread, but there is no progress indicator, spinner, or cancel mechanism in the UI. For MVP user experience, this is a visible gap.

**17. No explanation to the user of WHY a detection fired.**
The high-risk alert shows the file name and score but doesn't tell the user which signal fired (entropy? SPRT? honeyfile?). For any security tool, explainability is a core UX requirement.

---

## WHERE TO FOCUS FOR MVP

Priority order, highest impact first:

| Priority | What to fix | Why |
|---|---|---|
| 1 | Add a **file extension allowlist** to `EntropyAnalyzer` — skip `.zip`, `.jpg`, `.mp4`, `.png`, `.apk` etc. | Eliminates the dominant source of false positives before the app is usable |
| 2 | Collect **benign test data** and validate your false positive rate | Without this, all detection accuracy claims are half-statements |
| 3 | Fix **snapshot retention during active attack** — freeze deletions while `activeAttackId > 0` | Without this, automated recovery is unreliable by design |
| 4 | Make `killMaliciousProcess` honest — either **actually implement process stopping** (call `stopService` via intent, use `DevicePolicyManager` if available) or document clearly it is `killBackgroundProcesses` only and your recovery assumes the process continues | This is a correctness claim in your documentation that is not true |
| 5 | Set `EXPECTED_SIGNATURE_HASH` to the actual release signing hash or remove the claim from documentation | A null production value is a false security claim |
| 6 | Validate and tune the **SPRT rate parameters** (λ₀, λ₁) against real Android device baseline data | The current parameters will fire on any file-intensive Android app |
| 7 | Add a **benign/ransomware confusion matrix** with real samples to the test scripts | Completes the evaluation story |
| 8 | Replace the **process attribution** comment "PSEUDO-KERNEL" with an honest documentation note about the limitation, and remove the misleading UID parameter | This is a documentation honesty issue for academic submission |

---

## Summary

The features that are already at MVP quality and don't need more work: the cryptographic snapshot system, the TEE integrity gate, the three-tier network blocking, LockerGuard, and the auto-restart resilience mechanisms. Spend remaining time on the usability and evaluation correctness items listed above.
