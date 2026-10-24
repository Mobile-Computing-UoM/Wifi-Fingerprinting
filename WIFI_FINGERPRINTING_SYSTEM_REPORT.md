# CS4473 Mobile Computing — Group Research Project Proposal
**Indoor Wi-Fi Location Fingerprinting: Empirical Evaluation of $k$-NN & Weighted $k$-NN, Signal Variability, and Physical Radio Dynamics**

**Selected Option:** Option E — Wi-Fi Location Fingerprinting  
**Group:** Group E-01  
**Members:** S. Vithurshan (`220672G`) & S. Pratheep (`220489C`)  
**Department of Computer Science and Engineering, University of Moratuwa**

---

## 1. Cover Information & Project Overview

Satellite GNSS/GPS performs poorly indoors because reinforced concrete structures heavily attenuate microwave signals. Ambient 802.11 Wi-Fi Access Points (APs), however, produce spatially varying Received Signal Strength Indication (RSSI) signatures across indoor environments. By recording reference RSSI vectors at known calibration spots during an offline survey phase, a mobile device can estimate its location online. This project proposes to investigate how calibration sample size ($N$), nearest-neighbour count ($k$) under both standard and distance-weighted $k$-NN (WKNN), multi-day temporal drift, cross-device survey transfer, and physical radio propagation characteristics govern indoor localization accuracy and metric distance error in metres under real Android platform constraints.

---

## 2. Research Questions & Hypotheses

**Research Questions:**  
> 1. *How accurately can an Android smartphone determine its physical reference location across an indoor testbed using Wi-Fi RSSI fingerprinting, and how do calibration sample size ($N \in \{10, 30, 50\}$), the nearest-neighbour parameter ($k \in \{1, 3, 5\}$), distance-weighted voting (WKNN), and multi-day temporal drift affect spot classification accuracy and localization error in metres?*  
> 2. *Is cross-device localization feasible when a fingerprint database surveyed on one smartphone model is transferred directly to a different smartphone model without re-surveying, and how do hardware-specific RSSI offsets impact accuracy?*

**Expected Findings & Hypotheses:**
- **Sample Convergence ($N$):** We hypothesize that localization accuracy will improve as the number of calibration scans per spot increases from $N = 10$ to $N \approx 30\text{--}50$, followed by diminishing returns at larger $N$ because each spot's empirical sample mean and RSSI distribution converge toward its local stationary distribution (whereas Android scan throttling limits *how fast* survey scans can be collected rather than accuracy itself).
- **Distance-Weighted $k$-NN (WKNN) & Missing-AP Penalty:** We expect inverse-distance Weighted $k$-NN (WKNN) combined with a missing-AP overlap penalty to outperform uniform majority voting at $k \in \{3, 5\}$ by giving closer matches higher voting power and preventing distant spots that share only one coincidental AP from winning.
- **Independent Visits & Temporal Drift:** When testing in separate walk-in sessions with the phone held naturally in hand, we expect later-day tests (24+ hours after surveying) to exhibit lower spot accuracy and higher metric error in metres than same-day tests due to human body orientation, obstacle shifts, and environmental multipath changes.
- **Cross-Device Portability Hypothesis (Phone A Survey $\to$ Phone B Test):** We hypothesize that transferring a survey radio map collected on one smartphone (**Phone A**) directly onto a second heterogeneous smartphone (**Phone B**) is feasible for coarse spot discrimination where multi-AP signal gradients are large, but will exhibit measurable accuracy degradation and higher distance error compared to same-device testing due to hardware differences in antenna gain, chassis attenuation, and Wi-Fi chipset RSSI calibration offsets.
- **Core Wi-Fi Signal Behaviour (LOS vs. NLOS):** Using unthrottled ESP32-C3 hardware baseline scans, we expect direct Line-of-Sight (LOS) RSSI distributions to be approximately bell-shaped (with mild skewness from integer dBm quantization and multipath), whereas Non-Line-of-Sight (NLOS) paths through interior walls will exhibit measurable attenuation and wider variance that we will quantify empirically.

---

## 3. Proposed System Architecture

Our experimental platform consists of two distinct components designed to separate mobile positioning from raw physical radio profiling:

1. **Self-Contained Mobile Positioning System (Android Smartphone):**
   - The indoor positioning system runs entirely on the smartphone without external servers or attached hardware.
   - **Offline Survey Mode:** Collects $N$ consecutive Wi-Fi scans at each labelled reference spot with a real-time progress bar, holding a dynamic wake lock only during active surveying so the radio and CPU do not sleep, and persisting all raw scan measurements locally on the device.
   - **Online Locate Mode:** Matches live Wi-Fi scans against the on-device reference database using $k$-NN and Distance-Weighted $k$-NN (WKNN) to output ranked candidate spots, confidence scores, and distance error estimates.
2. **Independent Standalone RF Study (ESP32-C3 Microcontroller):**
   - Completely separate from the smartphone application, we conduct an independent physical radio measurement study using a standalone **ESP32-C3 microcontroller**.
   - This experiment is designed specifically to observe raw physical Wi-Fi signal reception in the air while **excluding Android OS constraints** (such as background scan throttling, power-saving sleep cycles, and vendor baseband filtering).
   - Streaming high-density unthrottled RSSI measurements ($N \ge 1{,}500$ to $10{,}000$ samples) over USB serial provides a clean physical benchmark to examine core RF propagation behaviour (Line-of-Sight vs. Non-Line-of-Sight multipath fading, $20\text{ MHz}$ vs. $40\text{ MHz}$ channel bandwidth effects, and sample mean convergence).
3. **Offline Statistical & Evaluation Pipeline:**
   - Processes exported survey and test logs from the smartphones alongside data from the standalone ESP32-C3 experiment to compute sample mean ($\mu$), median, standard deviation ($\sigma$), empirical confidence bands, distribution histograms, and localization accuracy/error metrics.

### Android Platform APIs, Permissions & Privacy
- **Active Wi-Fi Scanning & Broadcast Filtering:** Uses Android's `WifiManager` APIs (`startScan()` and `getScanResults()`) together with a `BroadcastReceiver` listening for `SCAN_RESULTS_AVAILABLE_ACTION` (`EXTRA_RESULTS_UPDATED`) so cached OS callbacks are rejected and every recorded iteration represents a fresh physical radio sweep.
- **Permissions & Location Services Requirement:** Requests `ACCESS_FINE_LOCATION` (and `NEARBY_WIFI_DEVICES` on Android 13+) and verifies that system Location Services are enabled. Android mandates Location Services to be active before delivering Wi-Fi scan results because visible BSSIDs can be used to infer a user's physical location (this is an OS privacy gate on Wi-Fi APIs, not a GPS satellite fix).
- **Privacy Compliance:** In accordance with the assignment guidelines, our primary experiments track our controlled project APs, while any third-party/neighbouring BSSIDs encountered are anonymised via one-way SHA-256 hashing with their SSIDs discarded.

<!--
============================================================================
COMMENTED OUT FOR PROPOSAL (Exploratory Fuzzy Matrix Strategy excluded per review)
## Exploratory Strategy: 3D Discrete Fuzzy Membership Matrix
In an experimental branch, we also explore a non-parametric 3D Fuzzy Matrix strategy
over (Spot, AP, RSSI) with explicit Not-Seen (NS) probability mass and neighbour smoothing.
============================================================================
-->

---

## 4. Experiment Plan

### 4.1 High-Level Spatial Setup & Ground Truth
To keep our experimental methodology general and applicable to any indoor building environment:
- We will establish **at least 8 discrete indoor reference spots** ($L_1, L_2, \dots, L_M$, with $M \ge 8$) spaced approximately **$2.0\text{ m}$ apart** along an indoor corridor and adjacent rooms.
- Each reference spot $L_i$ will be physically measured using a tape measure relative to a chosen reference origin $(0.0, 0.0)\text{ m}$ to record its ground-truth 2D coordinates $(x_i, y_i)$ in metres. This enables us to evaluate both discrete spot classification accuracy (%) and physical localization error in metres.

### 4.2 Controlled Experimental Factors & Localization Methodology
Following the assignment brief, we plan to systematically conduct experiments across four key factors:

1. **Penalized Distance Metric & Distance-Weighted $k$-NN (WKNN) ($k \in \{1, 3, 5\}$):**
   - **Penalized Mutual RMSE with Missing-AP Penalty:** Let $A_c = A_{\text{live}} \cap A_{\text{survey}}$ denote the $M_c = |A_c|$ mutually visible APs between a live scan $\mathbf{q}$ and reference fingerprint $\mathbf{r}_j$, and let $M_{\max} = \max(|A_{\text{live}}|, |A_{\text{survey}}|)$. To prevent a distant spot sharing only one weak AP from falsely winning, we combine mutual RMSE with a missing-AP overlap penalty:
     $$D_{\text{pen}}(L_j, \mathbf{q}) = \sqrt{\frac{1}{M_c} \sum_{a \in A_c} \left(q_a - r_{j,a}\right)^2} \;+\; \alpha \left(1 - \frac{M_c}{M_{\max}}\right)$$
     (and compare against fixed noise-floor imputation at $-100\text{ dBm}$ for undetected APs).
   - **Uniform vs. Distance-Weighted $k$-NN Voting ($k \in \{1, 3, 5\}$):** Among the top-$k$ nearest candidates, we will evaluate both standard majority voting and **Distance-Weighted $k$-NN (WKNN)**, where each candidate $m \in \{1, \dots, k\}$ receives an inverse-distance weight:
     $$w_m = \frac{1}{D_{\text{pen},(m)} + \epsilon}, \qquad \hat{\vec{x}} = \frac{\sum_{m=1}^{k} w_m \vec{x}_{(m)}}{\sum_{m=1}^{k} w_m}$$
     Closer neighbours contribute higher voting weight, yielding both a predicted spot with normalized confidence (%) and a weighted 2D coordinate estimate $\hat{\vec{x}}$ to compute distance error $\|\vec{x}_{\text{true}} - \hat{\vec{x}}\|_2$ in metres.
2. **Survey Sample Count ($N \in \{10, 30, 50\}$):**
   - During the offline survey phase, we will record $50$ scans per spot (with Android Developer Option *"Wi-Fi scan throttling"* toggled off as noted in the brief) and evaluate $N \in \{10, 30, 50\}$ by subsampling from the collected survey scans to quantify how accuracy and metric error scale with survey effort.
3. **Independent Test Visits & Multi-Day Temporal Drift:**
   - Survey and test data will be kept strictly independent: test scans will **never** be split from stationary survey scans.
   - All test queries will be collected during **separate walk-in visits** with the smartphone held naturally in hand, comparing:
     - **Session 1 (Same-Day Walk-In Test):** Conducted in a separate pass after completing the offline survey.
     - **Session 2 (Later-Day Drift Test, +24h):** Conducted at the same reference spots on a subsequent day (24+ hours later) to quantify temporal RSSI drift.
4. **Cross-Device Transfer Experiment (Phone A Survey $\to$ Phone B Locate):**
   - To test whether a fingerprint database surveyed on one device can be used directly on another device without re-surveying, we will record the offline reference survey on **Phone A (Xiaomi Redmi Note 14 5G)** and transfer that database onto **Phone B (Xiaomi Redmi Note 11)**.
   - Both phones will then perform independent online localization trials across the surveyed reference spots to compare **Same-Device Accuracy** vs. **Cross-Device Accuracy** and analyze inter-device RSSI offsets.

### 4.3 Evaluation Metrics, Repetitions & Test Hardware
- **Metrics to be Reported:**
  1. **Fraction of Correct Spots (%):** Percentage of independent test queries where the predicted spot matches the true spot $L_i$.
  2. **Average Localization Error in Metres:** Mean and standard deviation of $\|\vec{x}_{\text{true}} - \hat{\vec{x}}\|_2$ (in metres) across all test trials.
- **Repetitions:** At least **5 independent locate trials per spot** across each test condition.
- **Smartphones Used:**
  1. **Xiaomi Redmi Note 14 5G** (Model `24115RA8EG`, **Android 14** / HyperOS) — primary survey and test handset.
  2. **Xiaomi Redmi Note 11** (Model `2201117TG`, **Android 13** / MIUI 14) — secondary handset for cross-device evaluation.

---

## 5. Our Own Twist (Proposal Item 5)

To distinguish our study from a baseline Option E project, we incorporate three focused extensions:

1. **Standalone ESP32-C3 RF Signal Study (Excluding Android OS):**
   - Smartphone Wi-Fi scanning is rate-limited by the Android OS and filtered by mobile baseband drivers. By conducting an independent standalone physical measurement study with an ESP32-C3 microcontroller (separate from the phone and Android OS), we capture dense, unthrottled 802.11 RSSI time-series ($N \ge 1{,}500$ to $10{,}000$ samples) without any OS interference. This allows us to observe pure physical Wi-Fi signal reception in the air—excluding Android OS constraints (background scan throttling, power-saving duty cycling, driver filtering), contrasting direct Line-of-Sight (LOS) vs. obstacle-attenuated Non-Line-of-Sight (NLOS) distributions, analyzing channel bandwidth effects ($20\text{ MHz}$ vs. $40\text{ MHz}$), and empirically verifying sample mean convergence over $N$.
2. **Distance-Weighted $k$-NN (WKNN) with Missing-AP Overlap Penalty:**
   - Beyond basic $k$-NN, we formulate and evaluate a penalized mutual RMSE distance metric paired with inverse-distance weighted voting to handle missing APs and boundary ties gracefully.
3. **Cross-Device Smartphone Transfer & Small-Scale Motion Analysis:**
   - We evaluate cross-device map portability between two distinct smartphones (**Redmi Note 14 5G** and **Redmi Note 11**) to study how receiver hardware differences shift RSSI readings, and optionally examine how small-scale movement across the $2.4\text{ GHz}$ half-wavelength ($\lambda/2 \approx 6.25\text{ cm}$) affects RSSI variance.

---

## 6. Plan of Deliverables (Application, Results, Analysis & Conclusions)

For the final demonstration and submission, we plan to deliver:
- **D1 — Working Mobile Application & Survey Dataset:** Live demonstration of the native Android application (Survey and Locate modes with $k$-NN / Weighted $k$-NN, missing-AP handling, and BSSID privacy anonymisation) and the collected multi-spot survey dataset.
- **D2 — Experimental Results on $N$, $k$, and Weighted Voting:** Quantitative tables and plots reporting spot classification accuracy (%) and mean/std localization error in metres as a function of calibration sample count ($N \in \{10, 30, 50\}$) and neighbour count ($k \in \{1, 3, 5\}$) for standard and Weighted $k$-NN.
- **D3 — Multi-Day Temporal Drift & Cross-Device Transfer Analysis:** Comparative evaluation of same-day vs. later-day (+24h) walk-in localization accuracy, alongside our cross-device experiment testing Phone A's survey database on Phone B and analyzing hardware RSSI offsets.
- **D4 — Standalone ESP32 RF Analysis, Conclusions & Attribution Log:** Empirical Line-of-Sight vs. Non-Line-of-Sight signal distribution analysis from the standalone ESP32-C3 study (evaluating channel bandwidth effects and convergence without OS interference) alongside Android scans, final conclusions on practical indoor Wi-Fi fingerprinting trade-offs, and a complete AI-usage and source attribution log.
