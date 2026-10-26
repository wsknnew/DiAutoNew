# DiAuto v1.2 (Custom Edition)

**Android Auto receiver for Android Car Head Units. Wireless or USB.**

> [!NOTE]
> **Fork & Upstream Attribution:**
> This repository is an independent custom fork of [**DiAuto by Shihab Al-Amri**](https://github.com/shihabal3amri/DiAuto), which is in turn based on [**Open Headunit**](https://github.com/andreknieriem/open-headunit) by Andre Knieriem and the original protocol work by [**Michael Reid**](https://github.com/mikereidis/headunit).
> 
> *Disclaimer: This custom edition is developed and tested on standard Android Car Head Units (such as AC8227L and similar automotive Android platforms). It has **NOT** been tested on BYD vehicles or BYD-specific cluster/HUD features. For original BYD DiLink specific builds, documentation, and cluster integration, please refer to the original upstream project: [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto).*

---

## 🛠️ Modifications & Enhancements in this Fork

This fork focuses on real-world aftermarket Android head unit reliability, automotive UX improvements, and standalone in-car updates:

### 1. Dual 1-Tap Cockpit Dashboard (Wireless & USB)
- **Side-by-Side 1-Tap Connection Hero Cards:** The primary standby cockpit dashboard presents Wireless Android Auto (Head Unit Server on port 5277 / Wi-Fi / BT) and USB Android Auto side-by-side with large, driver-friendly 1-tap connect buttons.
- **Glance USB Bar:** Live USB status indicator and phone model detection (Pixel, Samsung Galaxy, etc.) right on the home dashboard.
- **Zero Annoying Repeated USB Approval Popups:** Silenced recurring Android OS USB permission popups when connecting USB cables or re-enumerating into AOA mode.
- **Enhanced Buffering & Reconnection Resilience:** 1 MB socket buffer to absorb Wi-Fi hotspot packet jitter; 64 KB bulk transfer USB buffer; automatic reconnect on unexpected dropouts.
- **Driving-Friendly Disconnect UI:** High-contrast 28sp connection lost screen with a prominent 60dp red 1-tap disconnect button for effortless dashboard exit while driving.

### 2. In-App GitHub OTA Updater
- **Direct In-Car Updates:** Built-in OTA updater checks [wsknnew/DiAutoNew Releases](https://github.com/wsknnew/DiAutoNew/releases/latest) directly on the car display with a visual download progress bar.
- **Hands-Free Installation:** Automatically uses silent root install (`pm install -r`) when root or Shizuku is available, or standard Android `PackageInstaller` via `FileProvider`.
- **Automated CI/CD:** GitHub Actions release workflow (`.github/workflows/release.yml`) builds and publishes APKs upon tag push (`v*`).

### 2. Automotive Cockpit UI & Dark Theme
- **High-Contrast Dark Theme:** Tuned for high-glare daytime and OLED/LCD nighttime driving (`da_background`, `da_surface`, `da_accent`).
- **Cockpit Home Screen:** Redesigned connection cards, instant audio routing status, and quick mode toggles.
- **Live DPI Scale Preview:** Screen calibration tool with real-time preview (120–200 DPI) for widescreen and rotating screens.

### 3. Smart Wireless & Phone Hotspot Mode
- **Phone Hotspot Mode:** Connects to Android Auto when the head unit joins your phone's personal Wi-Fi hotspot.
- **Instant Gateway Scan (<300ms):** Direct DHCP gateway and custom phone IP prioritization, eliminating slow 254-IP subnet scans.
- **In-App Bluetooth Device Selector:** Grouped device list (Paired vs Nearby Unpaired) with direct in-app pairing and PIN dialog without leaving the app.

### 4. Hardware & MediaCodec Hardening (25+ Stability Fixes)
- **MediaCodec Buffer Slot Protection:** Guaranteed return of dequeued input and output buffers during network jitter, eliminating hardware decoder buffer starvation (`NO_INPUT_BUFFER`).
- **AudioFlinger Effect Leak Prevention:** Cleanly retains and releases native `Equalizer` effect instances on session teardown, avoiding Android OS effect slot exhaustion (~32 max).
- **Engine Starter Crank Immunity:** Cancellable teardown delays prevent transient 12V voltage drops from killing recovering sessions.
- **Main-Thread ANR Elimination:** Removed synchronous network operations and sleeps from Android's Main thread during service destruction.
- **PiP Display Guard:** Prevents Picture-in-Picture window downscaling from overwriting native 1080p/720p display preferences.
- **Touch Coordinate NaN Guard:** Division-by-zero and non-finite coordinate protection during dynamic surface resizing.

### 5. Companion Tool: Wireless ADB for AC8227L Head Units
- Includes [`wireless-adb-headunit/`](wireless-adb-headunit/README.md), an automotive companion utility ensuring persistent Wireless ADB across car reboots on MediaTek AC8227L head units.

---

## 📦 Building from Source

Requires JDK 17, Android SDK 36, NDK `29.0.14206865`, and CMake `3.22.1`.

```sh
# Build debug APK
./gradlew :app:assembleGithubDebug

# Build release APK
./gradlew :app:assembleGithubRelease
```

Generated APKs are located at `app/build/outputs/apk/github/`.

---

## 📜 Credits & License

DiAuto is released under the [GNU Affero General Public License v3.0 (AGPL-3.0)](LICENSE).
- **Original Upstream:** [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto)
- **Foundation:** [andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit)
- **Protocol & Concept:** [mikereidis/headunit](https://github.com/mikereidis/headunit)

*Android Auto is a trademark of Google LLC. This project is independent and not affiliated with or endorsed by Google LLC or BYD Auto.*
