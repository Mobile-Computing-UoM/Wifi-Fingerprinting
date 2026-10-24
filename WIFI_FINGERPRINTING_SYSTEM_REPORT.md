# CS4473 Mobile Computing — Group Research Project Proposal
**Indoor Wi-Fi Location Fingerprinting: Empirical Evaluation of Scan-Level $k$-NN, Signal Variability, and Physical Radio Dynamics**

**Selected Option:** Option E — Wi-Fi Location Fingerprinting  
**Group:** Group E-01  
**Members:** S. Vithurshan (`220672G`) & S. Pratheep (`220489C`)  
**Department of Computer Science and Engineering, University of Moratuwa**

---

## 1. Cover Information & Project Overview

Satellite GNSS/GPS performs poorly indoors because reinforced concrete structures heavily attenuate microwave signals. Ambient 802.11 Wi-Fi Access Points (APs), however, produce spatially varying Received Signal Strength Indication (RSSI) signatures across indoor environments. By recording reference RSSI vectors at known calibration spots during an offline survey phase, a mobile device can estimate its location online. This project investigates how calibration sample size ($N$), nearest-neighbour count ($k$), multi-day temporal drift, and physical radio propagation characteristics govern indoor localization accuracy and metric distance error in metres under real Android platform constraints.

---

## 2. Research Question & Expected Findings

**Research Question:**  
> *How accurately can an Android smartphone determine its physical reference location across an 8-spot indoor testbed using Wi-Fi RSSI fingerprinting, and how do calibration sample size ($N \in \{10, 30, 50\}$), the $k$-NN parameter ($k \in \{1, 3, 5\}$), and multi-day temporal drift affect spot classification accuracy and localization error in metres? Additionally, is cross-device localization feasible when a fingerprint database surveyed on one smartphone model is transferred directly to a different smartphone model without re-surveying?*

**Expected Findings & Hypotheses:**
- **Sample Convergence ($N$):** We expect $k$-NN localization accuracy to improve as the number of calibration scans per spot increases from $N = 10$ to $N \approx 30\text{--}50$, followed by diminishing returns at larger $N$ because each spot's empirical sample mean and RSSI distribution converge toward its local stationary distribution (whereas Android scan throttling limits *how fast* survey scans can be collected rather than accuracy itself).
- **Independent Visits & Temporal Drift:** When testing in separate walk-in sessions with the phone held naturally in hand, we expect later-day tests (24+ hours after surveying) to exhibit lower spot accuracy and higher metric error in metres than same-day tests due to human body orientation, obstacle shifts, and environmental multipath changes.
- **Cross-Device Portability Hypothesis (Phone A Survey $\to$ Phone B Test):** We hypothesize that transferring a survey radio map collected on one smartphone (**Phone A**) directly onto a second heterogeneous smartphone (**Phone B**) is feasible for coarse room/spot discrimination where multi-AP signal gradients are large, but will exhibit measurable accuracy degradation and higher distance error compared to same-device testing due to hardware-specific differences in antenna gain, chassis attenuation, and Wi-Fi chipset RSSI calibration offsets.
- **Core Wi-Fi Signal Behaviour (LOS vs. NLOS):** Using unthrottled ESP32-C3 hardware baseline scans, we expect direct Line-of-Sight (LOS) RSSI distributions to be approximately bell-shaped (with mild skewness from integer dBm quantization and multipath), whereas Non-Line-of-Sight (NLOS) paths through interior walls will exhibit measurable attenuation and wider variance that we will quantify empirically.

---

## 3. High-Level Prototype Architecture (Core Implementation)

Our core implementation consists of three integrated components built without third-party positioning SDKs or cloud dependencies:

1. **Native Android Application (Kotlin — [`MainActivity.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/MainActivity.kt)):**
   - **Survey Mode:** Collects $N$ consecutive Wi-Fi scans at each labelled reference spot with a live progress bar, holding a dynamic `WakeLock` only during active surveying so the radio and CPU do not sleep, and persisting all raw scan samples to `wifi_fingerprints.csv`.
   - **Locate Mode:** Performs online scan-level $k$-NN matching ([`KnnStrategy.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/strategy/KnnStrategy.kt)) against the stored survey database and reports top-$k$ nearest neighbours, predicted spot, and metric distance error.
2. **ESP32-C3 Wi-Fi Signal Profiler ([`esp32_surveyor.ino`](file:///media/vithurshan/vithu/mc/esp32_surveyor/esp32_surveyor.ino) & [`esp32_tool.py`](file:///media/vithurshan/vithu/mc/esp32_tool.py)):**
   - Because Android enforces OS-level Wi-Fi scan throttling (limiting apps to 4 scans every 2 minutes in standard mode) and chipset firmware filtering, smartphone scans alone collect slowly and can obscure rapid multipath fluctuations.
   - We therefore pair our Android prototype with a dedicated **ESP32-C3 microcontroller** that continuously scans 2.4 GHz 802.11 beacons without OS throttling and streams raw RSSI data over USB serial (`115200` baud) to `esp32_fingerprints.csv`. This provides a high-density ($N \ge 1{,}500$ scans) hardware baseline to study core Wi-Fi signal behaviour, LOS vs. NLOS distribution shapes, and sample convergence.
3. **Python Offline Statistical Suite ([`analyze_fingerprints.py`](file:///media/vithurshan/vithu/mc/analyze_fingerprints.py)):**
   - Parses raw CSV logs from both the Android phone and ESP32-C3 logger to compute sample mean ($\mu$), median, standard deviation ($\sigma$), empirical $\pm 1\sigma / 2\sigma / 3\sigma$ confidence bands, and distribution histograms.

### Android Platform APIs, Permissions & Privacy
- **`WifiManager.startScan()` & `getScanResults()`:** Triggers active 802.11 scans and retrieves visible BSSIDs, SSIDs, and raw integer RSSI levels (in dBm).
- **`BroadcastReceiver` (`SCAN_RESULTS_AVAILABLE_ACTION` with `EXTRA_RESULTS_UPDATED`):** Filters out cached OS callbacks so every recorded iteration represents a fresh physical radio sweep.
- **Permissions & Location Services Requirement:** Requests `ACCESS_FINE_LOCATION` (and `NEARBY_WIFI_DEVICES` on Android 13+) and verifies `LocationManagerCompat.isLocationEnabled()`. Android mandates Location Services to be enabled before delivering Wi-Fi scan results because visible BSSIDs can be used to infer a user's physical location (this is an OS privacy gate on Wi-Fi APIs, not a GPS satellite fix).
- **Privacy Compliance:** In accordance with Section 4 of the assignment brief, our primary experiments track our controlled project APs, while any third-party/neighbouring BSSIDs encountered are anonymised via one-way SHA-256 hashing with their SSIDs discarded.

<!--
============================================================================
COMMENTED OUT FOR PROPOSAL (Exploratory Fuzzy Matrix Strategy excluded per review)
## Exploratory Strategy: 3D Discrete Fuzzy Membership Matrix
In an experimental branch, we also formulated a non-parametric 3D Fuzzy Matrix strategy
(`FuzzyMatrixStrategy.kt`) over (Spot, AP, RSSI) with explicit Not-Seen (NS) probability mass
and neighbour smoothing. Per current scope, this is commented out so the proposal focuses
strictly on the core Option E k-NN implementation and ESP32 physical signal baseline.
============================================================================
-->

---

## 4. Experiment Plan

### 4.1 High-Level Spatial Setup & Ground Truth (At Least 8 Reference Spots)
To keep our experimental methodology general and applicable to any indoor building environment:
- We will establish **at least 8 discrete indoor reference spots** ($L_1, L_2, \dots, L_8$) spaced approximately **$2.0\text{ m}$ apart** along an indoor corridor and adjacent rooms.
- Each reference spot $L_i$ will be physically measured using a tape measure relative to a chosen origin $L_1 = (0.0, 0.0)\text{ m}$ to record its ground-truth 2D coordinates $(x_i, y_i)$ in metres. This allows us to compute both discrete spot classification accuracy (%) and physical localization error in metres.

### 4.2 Controlled Factors & True Scan-Level $k$-NN Methodology
Following Section 4 of the assignment guidelines, we will systematically conduct the experiment across three primary factors:

1. **True Scan-Level $k$-NN Parameter ($k \in \{1, 3, 5\}$):**
   - Rather than comparing a live query only against a single mean centroid per spot (where $k > 1$ cannot vote within a spot), the system stores all $N$ individual survey scan vectors $\mathbf{r}_{s,i}$ per spot ($8 \times N$ stored reference scans in total).
   - For a live query $\mathbf{q} \in \mathbb{R}^M$ across all $M$ known APs, the Euclidean distance to each stored scan $\mathbf{r}_{s,i}$ is computed as:
     $$d(\mathbf{q}, \mathbf{r}_{s,i}) = \sqrt{\sum_{a=1}^{M} \left(q_a - r_{s,i,a}\right)^2}$$
   - **Missing-AP Rule (`-100 dBm`):** Any AP not detected in a survey or live scan is consistently assigned the hardware noise-floor value of **$-100\text{ dBm}$**, preserving "Not Seen" signal dropout information.
   - **Voting & Metric Error in Metres:** Among the $k$ nearest individual scan vectors ($k \in \{1, 3, 5\}$):
     - *Predicted Spot:* Selected by majority vote among the $k$ neighbours (breaking ties by minimum distance).
     - *Estimated Coordinates & Distance Error:* Following RADAR, we average the coordinates of the $k$ nearest scans, $\hat{\vec{x}} = \frac{1}{k}\sum_{j=1}^{k} \vec{x}_{(j)}$, and compute the physical localization error $\|\vec{x}_{\text{true}} - \hat{\vec{x}}\|_2$ in metres.
2. **Survey Sample Count ($N \in \{10, 30, 50\}$):**
   - During the offline survey phase, we will record $50$ scans per spot (with Android Developer Option *"Wi-Fi scan throttling"* toggled off as noted in the brief) and evaluate $N \in \{10, 30, 50\}$ by subsampling the first $N$ stored scans per spot.
3. **Independent Test Visits & Multi-Day Temporal Drift:**
   - Survey and test data will be kept strictly independent: test scans will **never** be split from stationary survey scans.
   - Instead, all test queries will be collected during **separate walk-in visits** with the smartphone held naturally in hand, comparing:
     - **Session 1 (Same-Day Walk-In Test):** Conducted in a separate pass after completing the offline survey.
     - **Session 2 (Later-Day Drift Test, +24h):** Conducted at the same 8 reference spots on a subsequent day (24+ hours later) to quantify temporal RSSI drift.
4. **Cross-Device Transfer Experiment (Survey on Phone A $\to$ Locate on Phone B):**
   - To test whether a fingerprint map built on one device can be used by another device without re-surveying, we will record the offline reference survey (`wifi_fingerprints.csv`) using **Phone A (Xiaomi Redmi Note 14 5G)** and copy that exact CSV database onto **Phone B (Xiaomi Redmi Note 11)**.
   - Both phones will then perform independent online localization trials across all 8 spots ($L_1$–$L_8$). We will compare **Same-Device Accuracy** (Note 14 tested against Note 14's survey) vs. **Cross-Device Accuracy** (Note 11 tested against Note 14's survey) and analyze per-AP RSSI offsets between the two devices to determine when cross-device localization succeeds and when hardware bias causes misclassifications.

### 4.3 Evaluation Metrics, Repetitions & Test Hardware
- **Metrics Reported:**
  1. **Fraction of Correct Spots (%):** Percentage of independent test queries where the predicted spot matches the true spot $L_i$.
  2. **Average Localization Error in Metres:** Mean and standard deviation of $\|\vec{x}_{\text{true}} - \hat{\vec{x}}\|_2$ (in metres) across all test trials.
- **Repetitions:** At least **5 independent locate trials per spot** across each test condition.
- **Smartphones Used:**
  1. **Xiaomi Redmi Note 14 5G** (Model `24115RA8EG`, **Android 14** / HyperOS) — primary survey and test device.
  2. **Xiaomi Redmi Note 11** (Model `2201117TG`, **Android 13** / MIUI 14) — secondary handset used to evaluate cross-device generalisation.

---

## 5. Our Own Twist (Proposal Item 5)

To differentiate our work from a standard Option E implementation, we integrate two hardware-and-propagation extensions:

1. **Unthrottled ESP32-C3 Hardware Profiling of Core Wi-Fi Signal Behaviour:**
   - Smartphone Wi-Fi scanning is rate-limited by the Android OS and filtered by mobile baseband drivers. By deploying our custom **ESP32-C3 Wi-Fi logger** ([`esp32_surveyor.ino`](file:///media/vithurshan/vithu/mc/esp32_surveyor/esp32_surveyor.ino)) alongside the smartphone, we capture dense, unthrottled RSSI series ($N \ge 1{,}500$ samples) to examine core physical Wi-Fi signal behaviour—comparing direct Line-of-Sight (LOS) vs. wall-attenuated Non-Line-of-Sight (NLOS) distributions and empirically demonstrating why sample statistics stabilise around $N \approx 30\text{--}50$ scans.
2. **Cross-Device Smartphone Comparison & Small-Scale Motion Analysis:**
   - We test the fingerprint map across two different smartphones (**Redmi Note 14 5G** and **Redmi Note 11**) to study how receiver hardware differences (and survey-device matching) shift RSSI readings, and optionally examine how small-scale movement across the $2.4\text{ GHz}$ half-wavelength ($\lambda/2 \approx 6.25\text{ cm}$) affects RSSI variance.

---

## 6. Checkable Deliverables for Demonstration (D1–D4)

- **D1 (Android Prototype & 8-Spot Survey Dataset):** Live demonstration of the native Kotlin Android app surveying 8 reference spots ($L_1$–$L_8$, spaced $\approx 2.0\text{ m}$ apart), handling `-100 dBm` missing APs and BSSID privacy hashing, and exporting raw CSV datasets.
- **D2 ($k$-NN Sensitivity over $N$ and $k$):** Empirical tables and plots reporting spot classification accuracy (%) and mean/std localization error in metres across $L_1$–$L_8$ for $N \in \{10, 30, 50\}$ and $k \in \{1, 3, 5\}$.
- **D3 (Same-Day vs. Later-Day Temporal Drift):** Quantitative comparison of spot accuracy and metric distance error (m) between independent walk-in sessions on Day 1 and Day 2 (+24h).
- **D4 (ESP32 Core Signal Baseline, Dual-Phone Study & AI Log):** Empirical distribution plots from the unthrottled ESP32-C3 logger vs. Android smartphone scans (LOS vs. NLOS signal behaviour), cross-device evaluation (**Redmi Note 14 5G** vs. **Redmi Note 11**), and a complete AI-usage and source attribution log ([`AI_USAGE_LOG.md`](file:///home/vithurshan/mc/AI_USAGE_LOG.md)).
