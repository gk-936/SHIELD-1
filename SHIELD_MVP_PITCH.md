# SHIELD — MVP Pitch Presentation

> **Subtitle:** Intelligent Ransomware Protection for Android

---

## Slide 1: Title

**SHIELD**
*The first Android security app designed to silently detect and stop ransomware before it encrypts your files.*

- **Team:** DearMoon Security Research
- **Stage:** MVP / Research Prototype
- **Platform:** Android (API 26+)

---

## Slide 2: The Problem

### Ransomware is a Growing Mobile Threat

- Mobile ransomware continues to be a top threat to individual and enterprise data
- Two attack types dominate:
  - **Crypto Ransomware** — silently encrypts your files and demands payment
  - **Locker Ransomware** — locks your entire screen, making the device unusable
- **Existing antivirus tools** rely on static signature databases — they miss zero-day and novel threats
- Once files are encrypted, **recovery is almost impossible** without paying the ransom

### The Gap in the Market
> There is no Android-native, behavior-based ransomware detection tool available to end-users today.

---

## Slide 3: The Solution

### Introducing SHIELD

SHIELD is an **Android security application** that uses **real-time behavioral analysis** to detect ransomware *in the act* — before significant damage occurs.

It doesn't look for known malware signatures. It watches **how files and apps behave**.

**Three pillars of protection:**
1. 🔍 **Behavioral Detection** — Statistical analysis of file activity patterns
2. 🛡️ **LockerGuard** — Zero-inconvenience locker ransomware detection & bypass
3. 🌐 **NetworkGuard** — VPN-based network monitor to cut off C2 communication

---

## Slide 4: How It Works — Crypto Detection

### Multi-Signal Detection Engine

SHIELD runs continuously as a **foreground service**, monitoring your file system in real time.

| Signal | Method | What It Detects |
|---|---|---|
| **Entropy Analysis** | Shannon entropy (multi-region sampling) | High-randomness = encrypted files |
| **KL Divergence** | Kullback-Leibler byte uniformity | Uniform byte distribution = encryption |
| **SPRT Detector** | Sequential Probability Ratio Test (Poisson) | Abnormal file modification *rate* |
| **Honeyfiles** | Decoy files in monitored directories | Any unauthorized access = immediate alert |

**Confidence Score (0–100):**
- Score ≥ 70 → **HIGH RISK** → Auto-triggers network block + alert
- Combines all three signals for accuracy

---

## Slide 5: How It Works — LockerGuard

### Zero-Inconvenience Locker Protection

Unlike clunky legacy tools, SHIELD's **LockerGuard** is built on **passive surveillance**:

- ✅ Watches for apps that persist as fullscreen overlays over the Android Launcher
- ✅ Only triggers if the overlay persists for **2 seconds** or **3 refresh cycles**
- ✅ Completely silent during normal app usage — zero false-positive interruptions

**Instant Recovery:**
- **Vol Up + Vol Down** simultaneously → bypass the locker in one physical gesture
- **Notification button ("BYPASS / STOP")** → accessible from the notification shade even when the screen is locked out

---

## Slide 6: How It Works — NetworkGuard

### VPN-Based Network Monitor

SHIELD intercepts both **IPv4 and IPv6** traffic to:

- Log connection metadata (destination IP, port, protocol, packet size)
- Block connections to **known malicious ports** (4444, 5555, 6666, 7777)
- Block **Tor exit nodes** used for anonymous C2 communication
- **Emergency Mode**: When ransomware is detected (score ≥ 70), SHIELD automatically cuts **ALL** outbound traffic to prevent data exfiltration

> The user remains in full control — network blocking is OFF by default and can be toggled at any time.

---

## Slide 7: Key Features Summary

| Feature | Description |
|---|---|
| **Real-Time File Monitoring** | FileObserver on 6+ critical directories (Downloads, Documents, DCIM…) |
| **Statistical Detection (SPRT)** | Poisson-based hypothesis testing, 5% error rate |
| **Entropy + KL Analysis** | Multi-region sampling to detect partial encryption |
| **Honeyfile Traps** | Decoy files trigger immediate alert on first access |
| **LockerGuard** | Passive overlay detection — zero disruption to legitimate apps |
| **Physical Bypass** | Vol Up + Vol Down gesture to escape locker overlays |
| **VPN Network Guard** | Monitors & optionally blocks suspicious traffic |
| **Emergency Network Kill** | Auto-blocks ALL traffic on confirmed ransomware detection |
| **Auto-Restart** | Survives device reboots and service crashes |
| **RASP Security** | Detects debuggers, emulators, root, hooks, and APK tampering |
| **Snapshot & Recovery** | File snapshot system for post-attack recovery |
| **Log Viewer** | Color-coded real-time event log with severity filtering |

---

## Slide 8: Technology Stack

- **Platform:** Android (Java, API 26+)
- **Background Service:** Foreground Service with persistent notification
- **File Monitoring:** Android `FileObserver` API (recursive, depth 8)
- **Network Monitoring:** Android VPN Service (`VpnService`)
- **Statistics:** SPRT with Poisson arrival model (α = β = 0.05)
- **Storage:** SQLite (`shield_events.db`) — thread-safe, timestamp-indexed
- **Security:** Runtime Application Self-Protection (RASP) — anti-debug, anti-hook, cert pinning
- **Locker Detection:** Accessibility Service (`AccessibilityService`) + Window Stack Inspection

---

## Slide 9: Market Opportunity

### Target Users
1. **Individual Users** — Anyone concerned about data loss on personal devices
2. **SMBs** — Employees using Android devices with access to company data
3. **Enterprise MDM** — IT teams managing large Android device fleets
4. **Researchers & Security Teams** — Studying mobile ransomware behavior

### Market Size
- **4 billion+ Android devices** globally
- Mobile security market is experiencing significant growth as digital reliance increases
- Ransomware is now the **#1 category of cyber insurance claims**

### Differentiation
| Feature | SHIELD | Traditional AV |
|---|---|---|
| Behavior-based detection | ✅ | ❌ |
| Zero-day ransomware catch | ✅ | ❌ |
| Locker bypass mechanism | ✅ | ❌ |
| Network kill-switch | ✅ | ❌ |
| File recovery / snapshots | ✅ | ⚠️ |

---

## Slide 10: Traction & Validation

### Research Prototype Status
- ✅ Successfully built and installed on physical Android devices
- ✅ **RanSim** — Custom ransomware simulator used for validation testing
  - Simulates both **Crypto** and **Locker** ransomware behavior
  - SHIELD identifies locker events within the **2-second behavioral detection window**
- ✅ Honeyfile detection triggers an **immediate kill** response
- ✅ All statistical detection algorithms (SPRT, Entropy, KL) validated against real encryption behavior
- ✅ LockerGuard passive detection confirmed to produce **no false positives** in tests against standard apps

---

## Slide 11: Roadmap

### Phase 1 — MVP (Current)
- Core detection engine (Crypto + Locker)
- LockerGuard with dual bypass
- NetworkGuard with emergency kill
- File snapshot & recovery

### Phase 2 — Refinement
- Machine Learning integration for adaptive scoring
- Cloud threat intelligence feed for IP blocklists
- Battery and performance optimization pass
- UX redesign for consumer release

### Phase 3 — Scale
- MDM / Enterprise API (`ModeA` — eBPF kernel-level telemetry; requires rooted device)
- Play Store publication
- Managed detection & response (MDR) dashboard

---

## Slide 12: The Ask

### What We Need

| Resource | Purpose |
|---|---|
| **Research Funding / Grant** | Continued development and academic publication |
| **Security Lab Access** | Real-world ransomware sample testing |
| **Industry Partnership** | Integration with MDM/Enterprise platforms |
| **Cloud Infrastructure** | Threat intelligence aggregation pipeline |

### Why Now?
- Mobile ransomware is accelerating
- No behavior-based Android solution exists in the market
- SHIELD has a working MVP with a proven detection engine

---

## Slide 13: Team

- **Core Research:** Behavioral ransomware detection on Android
- **Engineering:** Full-stack Android development (Java, Gradle)
- **Security Focus:** RASP, anti-tampering, supply chain integrity
- **Domain:** Statistical detection theory, entropy analysis, Poisson modeling

---

## Slide 14: Closing

### Why SHIELD?

> "SHIELD doesn't wait for ransomware to be *known*. It watches for it to *behave*."

- First-of-its-kind behavior-based ransomware detector for Android
- Zero-inconvenience user experience — stays silent unless a real threat is detected
- Proven detection against simulated crypto and locker ransomware attacks
- Extensible architecture ready for kernel-level (`eBPF`) and enterprise deployment

**Thank you.**

---

*For technical details, see [`README.md`](./README.md)*
*Contact: [your email or institution here]*
