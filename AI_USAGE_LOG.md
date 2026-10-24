# CS4473 Mobile Computing — AI Usage & Code Attribution Log

**Project Option:** Option E — Wi-Fi Location Fingerprinting  
**Group:** Group E-01  
**Members:**  
- **S. Vithurshan** (`220672G`)  
- **S. Pratheep** (`220489C`)  

---

## 1. Source Code & Library Attribution

In compliance with Section 6 (*"Using AI Tools and Existing Code"*) of the CS4473 assignment brief, the table below lists every component of the codebase and explicitly distinguishes our own original work, standard SDK/library APIs, and AI-assisted code:

| Component / File | Path | Origin / Attribution | How Used & Verified by Group |
| :--- | :--- | :--- | :--- |
| **Android Wi-Fi Scanning & Permissions** | `app/src/main/java/com/example/wififingerprint/MainActivity.kt` | Android SDK (`WifiManager`, `BroadcastReceiver`, `LocationManagerCompat`) + Group E-01 + AI Assistant | Uses official Android Wi-Fi scan broadcast APIs (`SCAN_RESULTS_AVAILABLE_ACTION`), runtime `ACCESS_FINE_LOCATION` / `NEARBY_WIFI_DEVICES` checks, and SHA-256 BSSID anonymisation for non-project APs. |
| **Strategy 1: Scan-Level $k$-NN Classifier & Metric Coordinate Estimator** | `app/src/main/java/com/example/wififingerprint/strategy/KnnStrategy.kt` | Group E-01 + AI Assistant (informed by Bahl & Padmanabhan, *RADAR*, INFOCOM 2000) | Stores individual survey scan vectors ($8 \times N$), applies `-100 dBm` missing-AP floor, computes Euclidean distance across all registered APs, performs top-$k$ majority voting ($k \in \{1, 3, 5\}$), and averages 2D floor-plan coordinates $(x_i, y_i)$ in metres. Verified via unit tests (`StrategyPatternUnitTest.kt`). |
| **Strategy 2: 3D Fuzzy Membership Matrix** | `app/src/main/java/com/example/wififingerprint/strategy/FuzzyMatrixStrategy.kt` | Group E-01 + AI Assistant | Implements discrete empirical RSSI frequency histograms $\mu_{s,a}(r)$ with triangular neighbour kernel smoothing ($\pm 3\text{ dB}$) and explicit `Not Seen (NS)` probability scoring. Verified with manual calculation examples (`Query D`) in `StrategyPatternUnitTest.kt`. |
| **ESP32-C3 Stationary RSSI Logger** | `esp32_surveyor/esp32_surveyor.ino`, `esp32_tool.py` | Arduino `WiFi.h` library + Group E-01 + AI Assistant | Collects unthrottled baseline RSSI distributions over USB serial (`115200` baud) to compare against Android throttled scans. |
| **Python Statistical & Plotting Scripts** | `analyze_fingerprints.py`, `generate_plots.py`, `devices/analyze_devices.py`, `make_architecture_diagram.py` | `pandas`, `numpy`, `matplotlib`, `seaborn` + Group E-01 + AI Assistant | Used to generate empirical RSSI boxplots, floor-plan & cluster separability plots, cross-device RSSI bias analysis, and $N$/$k$ sensitivity metrics. |

---

## 2. Chronological Log of AI Tool Usage

| # | AI Tool Used | Purpose / Prompt Focus | How the Output Was Used, Modified & Verified |
| :- | :--- | :--- | :--- |
| **1** | **Antigravity (Google DeepMind Agentic Coding Assistant)** | **Theory Guide & Mathematical Formulation:** Clarifying how scan-level $k$-NN (RADAR) differs from nearest-centroid ($k=1$ on means) and formulating the 3D Fuzzy Membership Matrix with `Not Seen (NS)` mass and neighbour smoothing. | Used to structure the Strategy Pattern interface (`LocalizationStrategy.kt`), refine `KnnStrategy.kt` to store individual scan vectors ($8 \times N$) rather than only spot means, and write deterministic JUnit tests (`StrategyPatternUnitTest.kt`) that we verified by hand calculation. |
| **2** | **Antigravity Assistant** | **Android UI & Survey Workflow Engineering:** Adding a 3-tab interface (`Scanner`, `Survey Accuracy`, `Live History`), configurable train/test split, Auto-Calibration extension loop, Survey `Stop` button, and seamless continuation for existing spot labels. | Integrated into `MainActivity.kt`, `SurveyCalibrationAdapter.kt`, `FeedbackHistoryAdapter.kt`, and XML layouts (`activity_main.xml`, `dialog_settings.xml`). Tested directly on Xiaomi Redmi Note 14 5G (`24115RA8EG`) and Redmi Note 11 (`2201117TG`) via ADB. |
| **3** | **Antigravity Assistant** | **Cross-Device & Empirical Data Analysis:** Pulling `localization_feedback.csv` and `wifi_fingerprints.csv` from both phones via ADB and analysing the accuracy gap and per-AP RSSI offset between Redmi Note 14 5G and Redmi Note 11. | Used to write `devices/analyze_devices.py` and generate `device_accuracy_comparison.png` and `cross_device_rssi_bias.png`. Crucially, we identified and documented that the survey database was collected on the Redmi Note 14 5G (creating a hardware-match advantage) and that the cross-device RSSI gap varies non-linearly by signal level ($+3.9\text{ dB}$ mean, up to $10\text{ dB}$ in `Room 2`). |
| **4** | **Antigravity Assistant** | **Critical Review Alignment & Method Tightening:** Reviewing our initial proposal and report against the CS4473 brief (Option E requirements D1–D4) to fix methodological gaps: (a) inflating accuracy by evaluating on training scans, (b) nearest-centroid vs. true scan-level $k$-NN, (c) missing `-100 dBm` vs. intersection-only contradiction, (d) adding 8 spots with 2D floor-plan coordinates in metres, and (e) hashing third-party BSSIDs for privacy. | Rewrote `KnnStrategy.kt` to perform true scan-level $k$-NN voting ($k \in \{1, 3, 5\}$) and 2D coordinate estimation in metres across all 8 surveyed spots ($L_1$–$L_8$); added SHA-256 BSSID anonymisation (`anonymiseApIfNeeded`) in `MainActivity.kt`; and updated both `proposal_latex/main.tex` and `WIFI_FINGERPRINTING_SYSTEM_REPORT.md`. |

---

## 3. Group Verification & Interview Readiness Statement

Both group members (**S. Vithurshan**, `220672G` and **S. Pratheep**, `220489C`) have reviewed, tested, and verified every file in the Android codebase and Python analysis pipeline:
1. We can walk through and explain the exact execution trace of `wifiScanReceiver`, `processAndSaveAveragedFingerprint()`, `KnnStrategy.infer()`, and `FuzzyMatrixStrategy.infer()` line by line.
2. We can derive by hand both the Euclidean distance calculation with the `-100 dBm` noise floor and the smoothed fuzzy membership score $\tilde{\mu}_{s,a}(r)$ for any live scan vector.
3. We understand and can explain the experimental limitations observed in our data, including why stationary holdout splits ($98.3\%$) overestimate independent walk-in accuracy ($52.4\%$), how survey-device matching confounds cross-phone comparisons, and why 3 APs create narrow decision boundaries between adjacent rooms (`Hall` vs. `Hallway 2`).
