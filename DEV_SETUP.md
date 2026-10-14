# Developer Setup & Operating Guide: Wi-Fi Fingerprint Positioning System

This guide is designed to help any peer developer set up, build, test, and run the **Wi-Fi Fingerprint Indoor Positioning System** (Mobile Computing Assignment 2).

---

## 📌 1. Project Overview & Architecture

The system consists of two tightly integrated components:

```
┌────────────────────────────────────────────────────────┐
│               Android Application (Kotlin)             │
│  - Configurable Multi-Scan Surveying (N iterations)   │
│  - Real-time Horizontal Progress Bar (Current/Total %) │
│  - Online k-NN Positioning (RMSE on Available APs)    │
│  - Raw Signal Logging to CSV (No Mobile-Side Loss)     │
└───────────────────────────┬────────────────────────────┘
                            │
              adb pull wifi_fingerprints.csv
                            │
                            ▼
┌────────────────────────────────────────────────────────┐
│             Python Offline Statistical Suite           │
│  - N > 30 High-Sample Record Filtering                 │
│  - Mean (μ), Median, Sample Std Dev (σ)               │
│  - 1st (68.2%), 2nd (95.4%), 3rd (99.7%) σ Bands       │
│  - Consolidated Multi-AP Gaussian Distribution Plots   │
└────────────────────────────────────────────────────────┘
```

---

## 💻 2. Prerequisites & Tools

### A. Development Machine (PC)
- **Operating System**: Linux (Ubuntu/Debian tested), macOS, or Windows 10/11.
- **Java Development Kit (JDK)**: OpenJDK 17 or 21.
- **Android Studio**: Iguana / Jellyfish / Ladybug (or Android SDK Command-line Tools).
  - Android SDK Platform API 34+ installed.
  - Build-Tools 34.0.0+ installed.
- **ADB (Android Debug Bridge)**: Included with Android SDK platform-tools.
- **Python**: Version 3.9+ with `pandas`, `numpy`, and `matplotlib` (or [`uv`](https://github.com/astral-sh/uv) package runner).

### B. Android Test Device
- Physical Android phone running **Android 8.0 (API 26) through Android 14 (API 34+)**.
  *(Emulators cannot scan physical Wi-Fi access points; a real device is strictly required).*

---

## 📱 3. Mandatory Device Settings (CRITICAL)

Before testing or surveying, you **MUST** configure these two device settings, otherwise Android OS will block Wi-Fi scans:

### Step 1: Disable Wi-Fi Scan Throttling
Android OS limits apps to only 4 scans every 2 minutes if throttling is enabled.
1. Open **Settings** → **About Phone**.
2. Tap **Build Number** 7 times rapidly until you see *"You are now a developer!"*.
3. Go back to **Settings** → **Developer Options** (under System or Additional Settings).
4. Scroll to **Networking** section.
5. Locate **Wi-Fi scan throttling** and toggle it **OFF**.

### Step 2: Enable Location Services (GPS) & App Permissions
Android's security architecture treats Wi-Fi BSSID/RSSI scanning as location data:
1. Turn **ON** device **Location (GPS)**.
2. When launching the app, grant:
   - **Location Permission**: "While using the app" / "Precise".
   - **Nearby Wi-Fi Devices Permission** (Android 13+): "Allow".

---

## 🛠️ 4. Building & Running the Android App

### Option A: Using Android Studio
1. Open Android Studio → **Open Project** → Select the root repository directory (`/media/vithurshan/vithu/mc`).
2. Allow Gradle sync to complete automatically.
3. Connect your Android phone via USB and enable **USB Debugging**.
4. Select your connected device from the device dropdown and click **Run 'app'** (`Shift + F10`).

### Option B: Using the Command Line
```bash
# 1. Navigate to the project root
cd /media/vithurshan/vithu/mc

# 2. Build the Debug APK
./gradlew assembleDebug

# 3. Ensure your phone is recognized
adb devices

# 4. Install APK directly onto the phone
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 🎮 5. App Usage Guide

### ⚙️ Configuring Parameters
Tap the **⚙️ Settings** button in the top right:
- **Survey Sample Count ($N$)**: Total Wi-Fi scans collected per spot (e.g. `50`).
- **Locate Sample Count**: Number of live scans averaged when determining position (e.g. `10`).
- **k-NN $k$**: Number of nearest neighbors for majority voting (e.g. `1` or `3`).
- Tap **Save** (settings persist across app restarts).

---

### 📍 Step 1: Surveying Fingerprints (Offline Phase)
1. Stand at a chosen physical location (e.g., `bedroom`).
2. Type the location label in the **Enter Spot Label** input box (e.g. `bedroom`, `hall`, `kitchen`).
3. Tap **1. Record Survey Sample**.
4. **Watch the Horizontal Progress Bar**:
   - The UI displays: `Surveying 'bedroom'...  24/50 (48% completed)`.
   - The app pauses 1.2 seconds between scans to allow the phone's Wi-Fi radio to refresh fresh beacon frames.
   - Buttons are locked during scanning to prevent race conditions.
5. Once 100% is reached:
   - All raw measurements `[-52; -50; -51; ...]` are stored in the CSV file.
   - The averaged vector is stored in active memory for immediate localization.
   - A summary table is printed in the log view.
6. Move to the next spot and repeat.

---

### 🔎 Step 2: Real-time Location Matching (Online Phase)
1. Walk to any spot (either one of your surveyed spots or an in-between spot).
2. Tap **2. Where Am I? (k-NN Match)**.
3. The app collects the configured locate sample count (e.g., 10 iterations) and averages visible APs.
4. **Available Dimension Matching**:
   - If an AP present in the offline survey is not visible during locating (or vice-versa), the algorithm dynamically projects the Euclidean distance onto only the mutually available dimensions.
   - Computes RMSE (Root Mean Square Error):
     $$\text{RMSE}(\vec{s}, \vec{f}) = \sqrt{\frac{1}{M}\sum_{i=1}^{M} (s_i - f_i)^2}$$
5. The closest $k$ neighbors are evaluated, and the final predicted spot is displayed in the log view along with ranking metrics.

---

### 🗑️ Resetting / Clearing Data
- Tap **Clear All Saved Data (DB)**.
- Confirms deletion via a safety dialog, wipes in-memory fingerprints, and deletes the local CSV file.

---

## 📊 6. Offline Statistical Analysis & Plotting (Python)

All raw, unfiltered measurement iterations are preserved in the mobile device's app storage.

### Step 1: Pull the CSV from the Phone
Connect your phone with USB debugging enabled and run:
```bash
# Pull the CSV into your current working directory
adb pull /sdcard/Android/data/com.example.wififingerprint/files/wifi_fingerprints.csv .
```

### Step 2: Run the Analysis Script
Ensure Python 3.9+ is installed:
```bash
# Option 1: Using 'uv' (recommended, no virtualenv activation needed)
uv run --with pandas --with numpy --with matplotlib python analyze_fingerprints.py

# Option 2: Using standard pip & python3
pip install pandas numpy matplotlib
python3 analyze_fingerprints.py
```

### What the Script Outputs:
1. **Filters Data ($N > 30$)**: Automatically drops any short preliminary trial records ($\le 30$ samples) and only analyzes dense statistical survey runs.
2. **Prints Statistical Table**:
   - Sample Count ($N$)
   - Mean ($\mu$) RSSI (dBm)
   - Median RSSI (dBm)
   - Sample Standard Deviation ($\sigma$)
   - $\pm 1\sigma$ band (68.2% empirical confidence)
   - $\pm 2\sigma$ band (95.4% empirical confidence)
   - $\pm 3\sigma$ band (99.7% empirical confidence)
3. **Consolidated Multi-AP Distribution Plots**:
   - Generates side-by-side subplot figures for all APs detected under each spot.
   - Overlays normalized frequency density histograms, fitted Gaussian PDF curves, mean/median vertical lines, and shaded $\pm 1\sigma$ / $\pm 2\sigma$ regions.
   - Saved under `distribution_plots/<spot_name>_distribution.png`.

---

## 📂 7. Project Directory Structure

```
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Permissions (Location, Nearby Wi-Fi)
│   │   ├── java/com/example/wififingerprint/
│   │   │   └── MainActivity.kt          # Core engine: Survey, Locate, k-NN, CSV I/O, UI
│   │   └── res/
│   │       ├── layout/
│   │       │   ├── activity_main.xml    # Main UI: Progress bar, buttons, log viewer
│   │       │   └── dialog_settings.xml  # Parameter dialog (Survey, Locate, k-NN)
│   │       └── values/strings.xml       # Text resources
│   └── build.gradle.kts                 # Module build settings (minSdk 26, targetSdk 37)
├── analyze_fingerprints.py              # Offline Python statistical & plotting script
├── distribution_plots/                  # Generated Gaussian distribution figures
├── build.gradle.kts                     # Root project build settings
├── settings.gradle.kts                  # Gradle settings
├── DEV_SETUP.md                         # This developer documentation
└── README.md                            # Quickstart project overview
```

---

## ❓ 8. Troubleshooting FAQ

| Problem | Cause | Solution |
| :--- | :--- | :--- |
| **"Scan throttled by Android OS"** | Android OS Wi-Fi scan throttling is active | Go to `Settings → Developer Options → Wi-Fi scan throttling` and turn it **OFF**. |
| **0 APs Visible / Empty Scan** | Location services disabled or permission denied | Turn ON device GPS Location in Android quick settings, and grant "Fine Location" permission. |
| **`adb: error: failed to get feature set`** | ADB connection stale or unauthorized | Run `adb kill-server && adb start-server`, then check device screen to accept the RSA key prompt. |
| **`No such file: wifi_fingerprints.csv`** | No survey has been performed yet | Enter a spot name and tap `1. Record Survey Sample` at least once before pulling the CSV. |
| **Plots show `Not enough samples`** | Survey count set too low ($< 2$) | Increase Survey Iterations in Settings (recommended $\ge 30-50$ for reliable statistics). |

---

*Authored for CS4473 Mobile Computing Research Project Assignment 2.*
