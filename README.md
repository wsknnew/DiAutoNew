# DiAuto (Custom Edition)

**Android Auto on your BYD & Android head unit display. Wireless or USB.**

> [!NOTE]
> **Fork & Project Attribution:**
> This repository is an independent custom fork of [**DiAuto by Shihab Al-Amri**](https://github.com/shihabal3amri/DiAuto).
> - **Original Upstream:** [shihabal3amri/DiAuto](https://github.com/shihabal3amri/DiAuto) (Specialized BYD DiLink Android Auto Receiver)
> - **Upstream Foundation:** [andreknieriem/open-headunit](https://github.com/andreknieriem/open-headunit) by Andre Knieriem and contributors
> - **Original Protocol & Concept:** [mikereidis/headunit](https://github.com/mikereidis/headunit) by Michael Reid
>
> All original upstream copyrights, licenses (AGPL-3.0), and documentation remain intact.

---

## 🚀 Enhancements in this Fork (`wsknnew/DiAutoNew`)

This fork introduces deep automotive reliability hardening, UI/UX modernizations, and standalone over-the-air management:

### 1. In-App GitHub OTA Updater
- **1-Tap In-Car Updates:** Built-in OTA updater checks [wsknnew/DiAutoNew Releases](https://github.com/wsknnew/DiAutoNew/releases/latest) directly on the car display with a visual download progress bar.
- **Hands-Free Silent Install:** Automatically uses `pm install -r` if root or Shizuku is present; gracefully falls back to Android's `PackageInstaller` via `FileProvider`.
- **Automated CI/CD:** GitHub Actions release workflow (`.github/workflows/release.yml`) builds and publishes APKs upon tag push (`v*`).

### 2. Automotive Cockpit UI & Dark Theme
- **High-Contrast Dark Theme:** Tuned for high-glare daytime and OLED/LCD nighttime driving (`da_background`, `da_surface`, `da_accent`).
- **Cockpit Home Screen:** Redesigned connection cards, instant audio routing status, and quick mode toggles.
- **Display & DPI Tuning:** Live DPI scale preview with quick presets (120, 140, 160, 180, 200 DPI) for widescreen and portrait rotating screens.

### 3. Smart Wireless & Phone Hotspot Mode
- **Phone Hotspot Mode:** Connects to Android Auto when the head unit joins your phone's personal Wi-Fi hotspot.
- **Instant Gateway Scan (<300ms):** Direct DHCP gateway and custom phone IP prioritization, bypassing slow 254-IP subnet scans.
- **In-App Bluetooth Device Selector:** Grouped list (Paired vs Nearby Unpaired) with direct in-app pairing and PIN dialog—no need to exit to system car settings.

### 4. Hardware & MediaCodec Hardening (25+ Stability Fixes)
- **MediaCodec Buffer Slot Protection:** Guaranteed return of dequeued input and output buffers during network jitter, eliminating hardware decoder starvation (`NO_INPUT_BUFFER`).
- **AudioFlinger Effect Leak Prevention:** Cleanly retains and releases native `Equalizer` effect instances on session teardown, avoiding Android OS effect slot exhaustion (~32 max).
- **Engine Starter Crank Immunity:** Cancellable teardown delays prevent transient 12V voltage drops from killing recovering sessions.
- **Main-Thread ANR Elimination:** Removed synchronous network operations and sleeps from Android's Main thread during service destruction.
- **PiP Display Guard:** Prevents Picture-in-Picture window downscaling from overwriting native 1080p/720p display preferences.
- **Touch Coordinate NaN Guard:** Division-by-zero and non-finite coordinate protection during dynamic surface resizing.

### 5. Companion Tool: Wireless ADB for AC8227L
- Includes [`wireless-adb-headunit/`](wireless-adb-headunit/README.md), an automotive companion utility ensuring persistent Wireless ADB across car reboots on MediaTek AC8227L head units.

---

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.

[Original Upstream Releases](https://github.com/shihabal3amri/DiAuto/releases/latest) ·
[Fork Releases](https://github.com/wsknnew/DiAutoNew/releases/latest)

[English](https://shihabal3amri.github.io/DiAuto/) · [العربية](https://shihabal3amri.github.io/DiAuto/ar/) · [Русский](https://shihabal3amri.github.io/DiAuto/ru/) · [Español](https://shihabal3amri.github.io/DiAuto/es/) · [简体中文](https://shihabal3amri.github.io/DiAuto/zh-Hans/)

Install DiAuto **on the car**, then connect a compatible Android phone using its built-in
Android Auto support. **No additional phone companion app, dongle, external hardware,
root or firmware modification is required.** An Android phone is still required;
this is not Apple CarPlay.

![Android Auto with the current music card and a freely panned map, without a destination](site/assets/projection-music.png)

## What’s new in 0.3.12

- Android Auto’s map on the instrument cluster (Google Maps, Waze) with DiPlay’s turn card, following the cluster theme by itself.
- Windshield HUD arrows appear as soon as a route starts and stay on, and numbered roundabouts show their exit.
- Reconnecting during a route no longer ends in “Connection lost”, and a reconnect loop on the car’s Wi-Fi is fixed.
- Exiting Android Auto from the phone no longer reconnects by itself, and a phone removed from auto-start stays removed.
- Hiding the status bar no longer drops the picture to 800x480, and head units without a verified HEVC decoder get 1080p.
- DiLink 3.0: HUD and cluster guidance, and a long press on the voice button opens the phone’s assistant.

## Features

- Native wireless Android Auto, with USB data-cable support.
- BYD windshield navigation arrows, distance and street names on verified firmware, without ADB.
- Improved recovery when the Wi-Fi Direct interface appears late; explicit Static BSSID remains supported.
- Automatic Wi-Fi Direct address recovery on supported DiLink firmware, without ADB setup.
- Car-friendly home screen, simplified settings and redesigned option dialogs.
- Music-through-car-Bluetooth mode to avoid competing media audio focus.
- Automatic preference for the car's existing non-DFS 5 GHz Wi-Fi channel when supported,
  with normal band selection as a fallback.
- Bounded SurfaceView frame pacing, full-screen 1080p and corrected touch alignment.

## Compatibility and setup

Tested on **BYD DiLink 5.1 with Android 13**, using wireless and physical USB connections.
Other models and firmware versions are not yet verified. A compatible Android phone
with functioning Android Auto is required. The app and website support English, Simplified Chinese, Arabic, Russian and Spanish. Other existing community translations remain available.

See [BYD HUD compatibility and cleanup limits](docs/BYD_NAVIGATION.md).

See the [installation guide](docs/INSTALL.md), including permissions, Static BSSID and
upgrading from private previews. APK installation must be allowed by your head unit.
Set up while parked.

The tested panel exposes approximately 58 Hz. Recent wireless map-drag samples reached
47–58 displayed FPS after Wi-Fi channel alignment, with remaining variation and occasional
stalls. **Constant 60 FPS is not promised.** See [validation notes](docs/REVIEW.md).

## Build

Requires JDK 17, Android SDK 36, NDK `29.0.14206865`, and CMake `3.22.1`.
Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir=...`.

```sh
./gradlew :app:testGithubDebugUnitTest :app:assembleGithubDebug :app:assembleGithubRelease
```

APKs are under `app/build/outputs/apk/github/`. Release builds use R8/resource shrinking.
They are unsigned unless an untracked `key.properties` provides:

```properties
storeFile=/absolute/path/to/your-release-keystore.p12
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_PRIVATE_PASSWORD
```

Never commit signing keys or passwords. Your self-built APK will not upgrade a public
release unless signed with the same key. The public release signing key is held privately
by the maintainer. Application ID: `com.andrerinas.headunitrevived`.
Only the `github` flavor is covered by this fork's CI.

## Website

Five static language editions live under `site/`. Edit `site/content.json`, then run
`python3 scripts/build_site.py`. GitHub Actions deploys only `site/` to GitHub Pages.

## Credits and license

DiAuto is an independent fork of [Open Headunit](https://github.com/andreknieriem/open-headunit),
based on work by its contributors and [Michael Reid](https://github.com/mikereidis/headunit).
The local DiLink fork began from upstream commit `562c8dc`; the first public snapshot
includes the DiAuto modifications developed from that fork.

See [LICENSE](LICENSE) (AGPL-3.0), original copyright notices, in-app credits and
[third-party notices](docs/THIRD_PARTY.md). This repository contains the source for the
published app. Android Auto is a Google trademark. This project is not affiliated with
or endorsed by Google or BYD.
