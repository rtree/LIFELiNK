# Host Environment Setup Procedure

Last updated: 2026-09-25

## Why work on the host

The Coding Agent runs in a guest macOS (a VM on the Apple Virtualization Framework). The following two tasks are not practical inside the guest, so they are performed on the host macOS side instead.

- **Android Emulator**: Nesting further virtualization support (HVF-equivalent) inside the guest VM is not realistic. The host has confirmed `kern.hv_support: 1`, and on the host it runs at near-native-device speed.
- **Flashing the XIAO nRF52840**: Passing through USB serial/UF2 devices via the VM tends to be unstable. Flashing is done directly against the host's USB port.

## Connecting to the host

- The guest and host sit on the same bridge (`10.211.55.0/24`). The host's address is `10.211.55.2`, user `araki`.
- The connection is passwordless SSH (key-based, `~/.ssh/authorized_keys` already configured).
- The guest's `~/.ssh/config` already has a `beacon-host` alias configured. The host's LAN-side IP is `192.168.50.218`, but the VM-bridge-side `10.211.55.2` is preferred.

```bash
ssh araki@10.211.55.2
# or
ssh beacon-host
```

- This address can change when the VM's network is reconfigured. If it changes, run the following on the guest side to find the host-side bridge address corresponding to `en0`'s `inet` (e.g. the `.2` address of `bridge100`/`bridge101`).

```bash
ifconfig en0 | grep inet
netstat -rn -f inet | grep default
```

## Host inventory check results as of 2026-09-25

Confirmed via `ssh araki@10.211.55.2`.

| Item | Status |
| --- | --- |
| OS | macOS 26.5.2 (arm64) |
| Xcode Command Line Tools | Installed (`/Library/Developer/CommandLineTools`) |
| Homebrew | Installed (`/opt/homebrew/bin/brew`, 7.0.6). However, in a non-login SSH shell, `~/.zprofile`'s `brew shellenv` is not loaded and does not get onto `PATH`, so commands run via SSH must either be run as a login shell with `zsh -lc '...'`, or use the full path `/opt/homebrew/bin/brew` |
| Java | OpenJDK 17.0.20.1 (Temurin) at `/usr/bin/java` |
| Android SDK | Installed at `~/Library/Android/sdk` (cmdline-tools, platform-tools, emulator, system-images/android-36) |
| Android AVD | `beacon_api36` (Pixel 6, Android 16 "Baklava", `google_apis/arm64-v8a`) already created |
| Hypervisor support | `kern.hv_support: 1` (hardware acceleration for the Emulator can be enabled) |
| Android Studio.app | Not installed (cmdline-tools + emulator commands are sufficient for the scope that doesn't need a GUI) |
| arduino-cli / PlatformIO | **Not installed** (needed for flashing the XIAO) |
| git | Installed (2.50.1) |
| GitHub authentication (host→GitHub) | Not configured (SSH to `git@github.com` fails because the host key is not registered; use HTTPS clone as a substitute for read access) |
| Repository clone | **Not yet done** |

## Procedure

### 1. Environment variables (Android SDK / Java)

Since the SDK already exists, add the following to the shell startup file (e.g. `~/.zprofile`).

```bash
ssh araki@10.211.55.2 'cat >> ~/.zprofile <<'"'"'EOF'"'"'
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
EOF'
```

### 2. Clone the repository to the host (for read purposes)

Since the host doesn't have an SSH key for GitHub, clone over HTTPS (assuming the repo is public; if it's made private in the future, prepare a host-specific deploy key or `gh auth login` separately).

```bash
ssh araki@10.211.55.2 'mkdir -p ~/operations && cd ~/operations && git clone https://github.com/rtree/LIFELiNK.git'
```

From now on, to reflect commits & pushes made on the guest side onto the host, simply run `git pull` on the host side. **Do not push from the host to the remote** (pushing continues to be done from the guest side as before, to keep a single, unified trail of work evidence).

### 3. Verifying the Android Emulator works

When the Coding Agent verifies this, GUI display is the default so the user can check the screen, and `-no-window` should not be added.

```bash
ssh beacon-host 'ANDROID_HOME="$HOME/Library/Android/sdk"; nohup "$ANDROID_HOME/emulator/emulator" -avd beacon_api36 -no-audio -no-snapshot-save > /tmp/lifelink-emulator.log 2>&1 < /dev/null &'
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" wait-for-device; until [[ "$("$ADB" shell getprop sys.boot_completed | tr -d "\r")" == "1" ]]; do :; done; "$ADB" devices -l'
```

If `emulator-5554 device` appears in `List of devices attached`, the startup succeeded. Once Android-side implementation progresses further, `adb install` / `adb logcat` can also be run the same way over SSH. The window is displayed on the host's logged-in desktop.

Only for CI or automated checks that don't need a screen, add `-no-window` to the above startup command to run it headless.

### 4. XIAO nRF52840 flashing toolchain (arduino-cli)

Since the host has Homebrew installed, install `arduino-cli` via brew.

```bash
ssh araki@10.211.55.2 'zsh -lc "
brew install arduino-cli
arduino-cli config init
arduino-cli config add board_manager.additional_urls https://files.seeedstudio.com/arduino/package_seeeduino_boards_index.json
arduino-cli core update-index
arduino-cli core search nrf52
"'
```

Use the official core ID that appears in the results of `arduino-cli core search nrf52` (Seeed's nRF52 board package; the name may vary by version, so confirm it in the search results before substituting `<CORE_ID>` in the next command) to install it.

```bash
ssh araki@10.211.55.2 'zsh -lc "arduino-cli core install <CORE_ID>"'
```

The XIAO nRF52840's USB connection itself is made physically to the host (no passthrough to the VM).

```bash
ssh araki@10.211.55.2 'zsh -lc "arduino-cli board listall | grep -i xiao"'
ssh araki@10.211.55.2 'zsh -lc "arduino-cli board list"'
```

### 5. Preparing the firmware directory (implementation not started until Phase 7)

As stated in chapter 6b of [doc/en/plan.md](../en/plan.md), the GATT implementation itself will not be started until after the main line and Beacon verification are complete (Phase 7). Here, only toolchain verification (flashing the bundled Blink sample) is done on the host; the actual code for `firmware/xiao_gatt_button/` will be created in Phase 7.

## Completion criteria

- [x] `ssh araki@10.211.55.2` succeeds without a password from the guest.
- [x] `adb devices` on the host shows the `beacon_api36` emulator (`emulator-5554 device`).
- [x] `arduino-cli core search nrf52` on the host returns Seeed's nRF52 core. `Seeeduino:nrf52` has been installed.
- [x] The repo is cloned on the host and can be updated with `git pull`.

All completed on 2026-09-25.

## Additional verification on 2026-09-25: build and launch verification of android/ on the host

After the android/ project reached the host via `git pull`, it was actually built on the host, installed onto the emulator, and its startup confirmed.

```bash
ssh araki@10.211.55.2 'zsh -lc "cd ~/operations/LIFELiNK/android && ./gradlew assembleDebug"'
# BUILD SUCCESSFUL (passed with the existing Temurin 17 / Android SDK Build-Tools 35 auto-install)

ssh araki@10.211.55.2 'zsh -lc "
emulator -avd beacon_api36 -no-window -no-audio -no-boot-anim &
adb install -r ~/operations/LIFELiNK/android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.rtree.LIFELiNK/.MainActivity
"'
# Confirmed topResumedActivity=...com.rtree.LIFELiNK/.MainActivity state=RESUMED
```

Going forward, as Android-side implementation progresses, the same procedure (`git pull` → `./gradlew assembleDebug` → `adb install` → `adb shell am start`) can be used to verify behavior on the host's emulator. After verification, stop the emulator with `adb emu kill` to free up resources.

## Additional verification on 2026-09-26: running a guest build on the host emulator

The emulator inside the guest cannot start because nested HVF is unavailable. The APK built on the guest is transferred over SSH and loaded directly into the AVD on the Apple Silicon host. Normally it is started in GUI mode so the user can view the screen.

```bash
ssh beacon-host 'ANDROID_HOME="$HOME/Library/Android/sdk"; nohup "$ANDROID_HOME/emulator/emulator" -avd beacon_api36 -no-audio -no-snapshot-save > /tmp/lifelink-emulator.log 2>&1 < /dev/null &'
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" wait-for-device; until [[ "$("$ADB" shell getprop sys.boot_completed | tr -d "\r")" == "1" ]]; do :; done; "$ADB" devices -l'

scp android/app/build/outputs/apk/debug/app-debug.apk beacon-host:/tmp/lifelink-debug.apk
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" install -r /tmp/lifelink-debug.apk; "$ADB" shell am start -W -n com.rtree.LIFELiNK/.MainActivity'
```

If an old debug APK with a different signature remains and causes `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, uninstall the old package only within the test AVD, then reinstall.

```bash
ssh beacon-host 'ADB="$HOME/Library/Android/sdk/platform-tools/adb"; "$ADB" uninstall com.rtree.LIFELiNK; "$ADB" install /tmp/lifelink-debug.apk'
```

Measured results:

- Started as `emulator-5554`, `sdk_gphone64_arm64`, API 36.
- LIFELiNK 0.1.0's MainActivity displayed in 85ms, with no FATAL/ANR.
- Confirmed successful Google sign-in via Credential Manager, Firebase Auth UID issuance, and the "signed in" display.
- Added `roles/firebaseauth.admin` to the Cloud Run runtime service account, enabling ID token verification with revocation checking and custom claims updates.
- The World ID backend start API returned HTTP 200. The connector URI opened in Chrome, and since World App was not installed it redirected to `org.world.id` on Google Play.
- Cloud Run was Ready at `lifelink-backend-00013-dq4`. Completion of the World ID proof will be confirmed on a physical device with World App installed.

## Known constraints / open issues

- GitHub authentication on the host is not configured. If pushing from the host becomes necessary, issue a host-specific deploy key or PAT and treat it as a Secret (do not write it in chat or in the repository).
- The host's IP (`10.211.55.2`) is dependent on the VM environment. If it switches to a different host or network configuration, update the connection information in this document.
