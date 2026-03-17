# RanSim Features & Specification

RanSim is a controlled ransomware simulator designed for security research and anti-ransomware testing. It implements safe, reversible ransomware behaviors within a strictly defined scope.

## 1. Crypto-Simulation (File Encryption)
Simulates the behavior of crypto-ransomware through file transformation and replacement.

- **Reversible XOR Engine**: Uses a fixed XOR key (`0x5A`) for all file transformations, ensuring 100% data recoverability.
- **Multi-Threaded Burst Mode**: High Speed Mode utilizes 4 parallel worker threads to simulate modern ransomware's rapid encryption behavior.
- **Sequential Delay Mode**: Standard mode introduces random 20-50ms delays between file operations to simulate gradual or stealthy encryption.
- **Atomic Operations**: Creates a `.locked` encypted copy before deleting the original file to prevent data loss on failure.
- **Scope Restriction**: Hard-coded to operate exclusively within `/sdcard/ransom_test` to prevent accidental damage to user data.

## 2. Locker-Simulation (Screen Locking)
Simulates locker-style ransomware that denies user access to the device interface.

- **Fullscreen Overlay**: Uses `LockerActivity` with `FLAG_FULLSCREEN` and `FLAG_KEEP_SCREEN_ON` to visually take over the device.
- **Navigation Blocking**: Supplements visual overlay with back-button blocking logic and system UI flag overrides.
- **Safety Fallbacks**:
    - **Visual Unlock**: A prominent "UNLOCK" button for immediate manual dismissal.
    - **Auto-Dismiss**: A 60-second watchdog timer that automatically closes the locker activity.

## 3. Data Restoration
Allows investigators to return the test environment to its pristine state.

- **Integrated Decryption**: Reverses the XOR transformation on all `.locked` files.
- **Filename Recovery**: Restores original filenames and deletes the `.locked` artifacts.

## 4. UI & Monitoring
Provides visibility into the simulation state during testing.

- **In-App Logs**: A dedicated terminal-style view in `MainActivity` shows real-time progress of crypto and restore operations.
- **Telemetry Tagging**: All major events are logged to Android Logcat with the tag `RANSOM_SIM` for easy external monitoring.

## 5. Security & Compliance
- **Permission Gating**: Validates `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE` before any file operations.
- **Confirmation Flow**: Requires manual confirmation via system dialogs before starting any simulation or restoration process.
