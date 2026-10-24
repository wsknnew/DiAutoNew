# Wireless ADB Tool for AC8227L Android Head Unit

A lightweight, automotive-optimized Android application designed specifically for **AC8227L (JCAC10003)** and similar Android car head units to enable and persist **Wireless ADB (ADB over TCP/IP)** without requiring a PC USB cable.

---

## 🚗 Why AC8227L Head Units Fail at Wireless ADB by Default

1. **Stripped Car Settings:** Chinese car head units replace standard Android AOSP settings with a custom "Car Settings" app. The native "Developer Options" and "Wireless Debugging" menus are hidden or stripped.
2. **USB Host Mode:** The 4-pin and 6-pin USB ports on the back of the head unit are configured as USB Host (for flash drives and dongles). Connecting a laptop via USB-A to USB-A cable will not work without switching USB modes in factory settings.
3. **Ignition Reset:** Android resets the system property `service.adb.tcp.port` to `-1` whenever the car reboots or enters deep sleep. Even if enabled once, it vanishes on the next drive.

---

## ✨ Features of this App

- **1-Tap Wireless ADB Activation:** Sets `service.adb.tcp.port 5555` and cleanly restarts `adbd`. Supports root shell execution (`su`), unprivileged permissive SELinux fallback, and system properties reflection.
- **Accurate Real-Time Verification:** Doesn't just guess—it actively probes `127.0.0.1:5555` using a local TCP socket test to confirm `adbd` is listening and accepting connections.
- **Auto-Enable on Car Boot:** Listens for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, and MediaTek fast boot (`QUICKBOOT_POWERON`) so wireless ADB remains active every time you start your car.
- **Ready-to-Copy Connect Command:** Automatically reads your Wi-Fi/Hotspot IP address and formats the exact command:
  ```bash
  adb connect 192.168.X.X:5555
  ```
  with a one-click clipboard copy button.
- **Car Head Unit Quick Tools:**
  - **Developer Options:** Direct launcher for hidden AOSP developer settings.
  - **MediaTek Engineer Mode:** Built-in MTK hardware and debugging interface for AC8227L.
  - **Android System Settings:** Standard Android settings launcher.
  - **Factory PIN Codes Helper:** Common passcodes (`8888`, `1617`, `7890`, etc.).
- **Automotive Landscape UI:** Designed with high contrast, large touch targets, and dark theme for in-car dashboard use.
- **Live Terminal & Diagnostics:** View real-time logs, SELinux enforcement, and system properties with one-click report copying.

---

## 📦 Ready-to-Install APK Locations

The application has been compiled and is ready for installation:

- [`WirelessADB_AC8227L.apk`](file:///Users/user/WSKN%20DATA/CODING%20PROJECT/DiAuto%20-%20Android%20Auto/WirelessADB_AC8227L.apk) (in project root)
- [`../Android Head Unit/WirelessADB_AC8227L.apk`](file:///Users/user/WSKN%20DATA/CODING%20PROJECT/Android%20Head%20Unit/WirelessADB_AC8227L.apk)
- [`wireless-adb-headunit/app/build/outputs/apk/release/app-release.apk`](file:///Users/user/WSKN%20DATA/CODING%20PROJECT/DiAuto%20-%20Android%20Auto/wireless-adb-headunit/app/build/outputs/apk/release/app-release.apk)

---

## 🛠️ Step-by-Step Installation & Setup

### Step 1: Install on the Head Unit
1. Copy `WirelessADB_AC8227L.apk` onto a USB flash drive (FAT32 formatted).
2. Plug the USB drive into one of your head unit's USB ports in the car.
3. Open the head unit's **File Manager** (or **ApkInstaller**).
4. Tap `WirelessADB_AC8227L.apk` and confirm the installation.

### Step 2: Connect to Wi-Fi
1. Ensure the head unit is connected to the same Wi-Fi network as your computer (or turn on your phone's Wi-Fi Hotspot and connect both your computer and head unit to it).

### Step 3: Enable Wireless ADB
1. Open **Wireless ADB for Head Unit** on the stereo display.
2. Tap the large **ENABLE WIRELESS ADB** button.
3. Verify that the badge switches to green: **"Wireless ADB is ACTIVE"**.
4. Leave **"Auto-enable on Car Boot"** checked so it stays active on subsequent drives.

### Step 4: Connect from Your PC / Mac / Laptop
1. Open Terminal (macOS/Linux) or Command Prompt (Windows).
2. Run the command shown on the head unit screen:
   ```bash
   adb connect 192.168.X.X:5555
   ```
3. Verify connection:
   ```bash
   adb devices
   ```
   You should see:
   ```text
   List of devices attached
   192.168.X.X:5555    device
   ```
