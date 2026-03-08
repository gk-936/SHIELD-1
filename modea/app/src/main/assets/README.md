# SHIELD Mode-A — APK Assets

Place the two native binaries here before building the APK with Android Studio:

| File | Built by |
|---|---|
| `shield_modea_daemon` | `build_real.sh` / `deploy.sh` (WSL, step 3) |
| `shield_bpf.o` | `build_real.sh` / `build/build_bpf.sh` (WSL, step 2) |

`build_real.sh` copies them here automatically after a successful build.

For quick testing you can also push directly to the device (skips bundling):
```bash
adb push out/shield_bpf.o        /data/local/tmp/shield_bpf.o
adb push out/shield_modea_daemon /data/local/tmp/shield_modea_daemon
adb shell chmod 755              /data/local/tmp/shield_modea_daemon
```
`ModeAController.deployBinaries()` detects pre-pushed files and skips the asset copy.
