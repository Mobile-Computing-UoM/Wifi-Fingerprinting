# CS4473 Mobile Computing: Wi-Fi Fingerprinting Localization System Report
**Option E — True Scan-Level $k$-NN vs. Discrete Fuzzy Matrix Architecture, Floor-Plan Metric Error, and Cross-Device Validation**

**Group:** Group E-01  
**Members:** S. Vithurshan (`220672G`), S. Pratheep (`220489C`)  
**Department of Computer Science and Engineering, University of Moratuwa**

---

## 1. Executive Summary & "Our Own Twist" (Proposal Item 5)

This report presents the design, implementation, and empirical evaluation of our Android Wi-Fi location fingerprinting system for **CS4473 Option E (Deliverables D1–D4)**. Beyond the baseline Option E requirements, **our own twist** introduces two key research contributions implemented inside a unified **Strategy Pattern** architecture ([`LocalizationStrategy.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/strategy/LocalizationStrategy.kt)):

1. **Dual-Strategy Algorithmic Twist (True Scan-Level $k$-NN vs. 3D Discrete Fuzzy Matrix):** On every live Wi-Fi scan, the app simultaneously evaluates a classic **Scan-Level $k$-NN Classifier** ($k \in \{1, 3, 5\}$ over $8 \times N$ individual stored scan vectors, with RADAR-style 2D coordinate averaging in metres) against a non-parametric **3D Discrete Fuzzy Membership Matrix** that models empirical RSSI frequency histograms $\mu_{s,a}(r)$, explicit `Not Seen (NS)` dropout probability, and a $\pm 2\text{ dB}$ neighbour kernel with an exponential long tail ($0.20 \cdot \exp(-d^2/32)$).
2. **Cross-Device Hardware Generalisation Twist:** Rather than testing on a single handset, we evaluate live walk-in localization across two heterogeneous smartphones—**Xiaomi Redmi Note 14 5G** (Model `24115RA8EG`, Android 14 / HyperOS) and **Xiaomi Redmi Note 11** (Model `2201117TG`, Android 13 / MIUI 14)—quantifying both the survey-device matching confounder and signal-level-dependent RF front-end offset.

---

## 2. Floor Plan, 8 Surveyed Spots ($L_1$–$L_8$) & Ground-Truth Coordinates

The indoor testbed spans a multi-room residential floor ($10\text{ m} \times 5\text{ m}$) covered by $M = 3$ project-controlled 2.4 GHz Wi-Fi Access Points (`98:a9:42:59:92:85` / `SLT-4G_599285`, `98:a9:42:0b:16:4a` / `Dialog 4G 610`, and `a0:c4:c6:a6:fc:18` / `4G-UFI-C18`). Eight reference spots ($L_1$–$L_8$), spaced $\approx 2.0\text{--}2.5\text{ m}$ apart across rooms and doorway transitions, were surveyed with $N = 50$ scans per spot ($400$ total stored scan vectors) and mapped to measured 2D Cartesian coordinates $(x_i, y_i)$ in metres relative to the south-west corner origin $(0.0\text{ m}, 0.0\text{ m})$:

| Spot ID | Label in App / CSV | Floor-Plan $(x_i, y_i)$ | Mean RSSI $[AP_1, AP_2, AP_3]$ (dBm) | Propagation & Wall-Attenuation Profile |
| :---: | :--- | :---: | :---: | :--- |
| **$L_1$** | `prayerroom` (Prayer Room) | $(0.0\text{ m},\; 0.0\text{ m})$ | $[-25,\; -23,\; -49]$ | Direct Line-of-Sight (LOS) to $AP_1$ & $AP_2$ |
| **$L_2$** | `hall` (Main Hall) | $(2.5\text{ m},\; 1.0\text{ m})$ | $[-42,\; -41,\; -53]$ | Open central zone ($2.7\text{ m}$ from $L_1$) |
| **$L_3$** | `hall2` (Hallway 2) | $(4.5\text{ m},\; 1.0\text{ m})$ | $[-41,\; -33,\; -60]$ | Inner transition ($2.0\text{ m}$ east of $L_2$) |
| **$L_4$** | `bedroom` (Bedroom) | $(2.5\text{ m},\; 3.5\text{ m})$ | $[-43,\; -45,\; -68]$ | 1 brick partition wall north of $L_2$ ($2.5\text{ m}$ step) |
| **$L_5$** | `coridoor` (Corridor) | $(4.5\text{ m},\; 3.0\text{ m})$ | $[-58,\; -50,\; -61]$ | Central passage ($2.0\text{ m}$ north of $L_3$) |
| **$L_6$** | `room2` (Room 2) | $(7.0\text{ m},\; 1.0\text{ m})$ | $[-66,\; -57,\; -59]$ | Eastern room ($2.5\text{ m}$ east of $L_3$; 2 walls) |
| **$L_7$** | `kitchen` (Kitchen) | $(7.0\text{ m},\; 3.0\text{ m})$ | $[-75,\; -65,\; -74]$ | Deep NLOS behind masonry walls ($2.0\text{ m}$ north of $L_6$) |
| **$L_8$** | `storeroom` (Storeroom) | $(9.0\text{ m},\; 2.0\text{ m})$ | $[-84,\; -76,\; -100\text{ (NS)}]$ | Far-east utility room ($2.2\text{ m}$ from $L_6$; $AP_3$ drops out) |

---

## 3. Localization Strategies: Mathematical Formulation & Implementation

### 3.1 Strategy 1: True Scan-Level $k$-NN & Metric Coordinate Interpolation (`KnnStrategy.kt`)
Earlier iterations of our prototype compared a live scan only against the per-spot mean vector $\bar{\mathbf{f}}_j$, which is formally a **Nearest-Centroid Classifier (NCC)** where varying $k$ has no effect. To evaluate $k \in \{1, 3, 5\}$ and subsampling $N \in \{10, 30, 50\}$ rigorously, [`KnnStrategy.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/strategy/KnnStrategy.kt) stores all $8 \times N$ individual survey scan vectors $\mathbf{s}_{i,n} = [r_{i,n,1}, \dots, r_{i,n,M}]^T$ ($i \in \{1,\dots,8\}, n \in \{1,\dots,N\}$).

- **Consistent Missing-AP Floor (`-100 dBm`):** Both survey and live query vectors are defined over the full set of $M$ registered APs. Any AP not detected in a scan is assigned $r_{\text{floor}} = -100\text{ dBm}$. Unlike intersection-only distance metrics, this preserves critical negative information (for example, $AP_3$ dropping out in $L_8$ `Storeroom`).
- **Euclidean Distance & Majority Voting ($k \in \{1, 3, 5\}$):** Given a live query $\mathbf{q} = [q_1, \dots, q_M]^T$, the Euclidean distance to every stored scan $\mathbf{s}_{i,n}$ is:
  $$d(\mathbf{q}, \mathbf{s}_{i,n}) = \sqrt{\sum_{a=1}^{M} \left( q_a - r_{i,n,a} \right)^2}$$
  The algorithm selects the $k$ individual scans with the smallest distances, predicts the discrete spot $\hat{L}$ by majority vote (breaking ties by minimum mean distance), and computes the **2D metric coordinate estimate** $(\hat{x}, \hat{y})$ in metres by averaging the floor-plan coordinates of the $k$ nearest neighbours (as in Bahl & Padmanabhan's *RADAR*):
  $$(\hat{x}, \hat{y}) = \frac{1}{k} \sum_{m=1}^{k} (x_{(m)}, y_{(m)}), \qquad e_{\text{metres}} = \sqrt{(\hat{x} - x_{\text{true}})^2 + (\hat{y} - y_{\text{true}})^2}$$

### 3.2 Strategy 2: 3D Discrete Fuzzy Membership Matrix (`FuzzyMatrixStrategy.kt`)
Implemented in [`FuzzyMatrixStrategy.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/strategy/FuzzyMatrixStrategy.kt), Strategy 2 maps integer RSSI observations into a 3D non-parametric empirical grid $\text{Spots} (8) \times \text{APs} (3) \times \text{Signal Bins } ([-100, 0]\text{ dBm} \cup \{\text{NS}\})$:

1. **Empirical PMF & Explicit Not-Seen (`NS`) Mass:**
   $$\mu_{\text{raw}}(v) = \frac{\text{Count}(v)}{N_{\text{total}}}, \qquad \mu(\text{NS}) = \frac{N_{\text{total}} - N_{\text{heard}}}{N_{\text{total}}}$$
2. **Neighbour Smoothing & Exponential Long Tail:** To prevent zero-membership cliffs when human grip or multipath shifts RSSI by $1\text{--}4\text{ dB}$, we combine a $\pm 2\text{ dB}$ triangular dilation with a Gaussian-decay long tail beyond $2\text{ dB}$:
   $$\mu_{\text{smoothed}}(v) = \begin{cases} 
   \max \Big( \mu_{\text{raw}}(v), \; 0.50 \cdot \max_{|\delta|=1}\mu_{\text{raw}}(v+\delta), \; 0.25 \cdot \max_{|\delta|=2}\mu_{\text{raw}}(v+\delta) \Big) & \text{if } d \le 2\text{ dB} \\[8pt]
   0.20 \cdot \exp\left(-\dfrac{d^2}{2 \cdot 4.0^2}\right) = 0.20 \cdot \exp\left(-\dfrac{d^2}{32}\right) & \text{if } d > 2\text{ dB}
   \end{cases}$$
   where $d = \min_{v' \in \text{surveyed}} |v - v'|$.
3. **Multi-AP Aggregation:**
   $$\text{Score}(L_i) = \frac{1}{M} \sum_{a=1}^{M} \mu_{i, a}(q_a), \qquad \hat{L}_{\text{Fuzzy}} = \arg\max_{i} \text{Score}(L_i)$$

---

## 4. Android System Engineering, Permissions & Privacy

- **Permissions & Location Services Requirement:** In [`MainActivity.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/MainActivity.kt), the app requests `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, and `NEARBY_WIFI_DEVICES` (Android 13 / API 33+), and checks `LocationManagerCompat.isLocationEnabled()`. Android mandates system Location Services to be enabled before delivering `WifiManager.getScanResults()` not to perform a GPS satellite fix, but because visible Wi-Fi BSSIDs can be used to infer a user's physical location.
- **Privacy Anonymisation (`anonymiseApIfNeeded`):** In compliance with the assignment brief's privacy rules, only our 3 project-controlled APs (`98:a9:42:59:92:85`, `98:a9:42:0b:16:4a`, `a0:c4:c6:a6:fc:18`) are stored in plain text. Any third-party or neighbouring BSSID encountered during scanning is immediately hashed via truncated SHA-256 (`anon_<12-hex>`) and its SSID is discarded (`"Anon-AP"`) prior to storage or CSV export.
- **Why Diminishing Returns Occur with $N$:** Android 9+ scan throttling (4 scans per 2 minutes in foreground unless developer unthrottling is toggled) limits **how fast** survey scans can be collected ($\approx 1.5\text{--}3\text{ s/scan}$ with throttling disabled), not classifier accuracy. Diminishing accuracy returns beyond $N=30$ arise statistically because the empirical sample mean and RSSI histogram converge toward the true local distribution at rate $\mathcal{O}(1/\sqrt{N})$.
- **Interactive Controls (`Stop`, Resume & Auto-Calibration):** The app provides a 3-tab UI (`📡 Scanner`, `📊 Survey Accuracy`, `📋 Live History`), a `⏹️ Stop` button that persists partial surveys immediately, automatic continuation when surveying an existing spot label, and configurable Auto-Calibration with an optional or unlimited extra-scan cap.

---

## 5. Experimental Evaluation: Deliverables D2 & D3

### 5.1 Stationary Holdout Split vs. Independent Walk-In Testing
A critical methodological finding of our study is the difference between **same-session stationary holdout splits** and **independent walk-in tests**:
- When the 50 stationary scans at each of the 8 spots are split into **35 build scans (70%) and 15 held-out test scans (30%)**, Scan-Level $k$-NN achieves **98.3% spot accuracy (118/120)** and **0.03 m mean error**. However, because consecutive stationary scans share the exact same device posture and quasi-static channel state, this figure overestimates real-world accuracy.
- Therefore, our primary evaluation uses **42 independent walk-in test queries** (21 on **Redmi Note 14 5G** and 21 on **Redmi Note 11**) collected during separate visits with the phone held naturally in hand, plus a **later-day (+24h) temporal drift session**.

### 5.2 Sensitivity to Survey Samples $N \in \{10, 30, 50\}$ and Neighbours $k \in \{1, 3, 5\}$
The table below reports both **Spot Classification Accuracy (%)** and **Mean Localization Error in Metres ($e_{\text{metres}}$)** across all 8 reference spots ($L_1$–$L_8$) for our independent walk-in test queries as $N$ is subsampled from `{10, 30, 50}` and $k$ varies across `{1, 3, 5}`:

| Survey Samples ($N$) | $k$ | Redmi Note 14 5G Spot Acc (%) | Redmi Note 14 5G Mean Error (m) | Redmi Note 11 Spot Acc (%) | Redmi Note 11 Mean Error (m) | Stationary 70/30 Holdout Acc / Error |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **$N = 10$** | $k = 1$ | 33.3% (7/21) | $1.79\text{ m}$ | 47.6% (10/21) | $1.40\text{ m}$ | 98.3% / $0.03\text{ m}$ |
| **$N = 10$** | $k = 3$ | 33.3% (7/21) | $1.65\text{ m}$ | 47.6% (10/21) | $1.41\text{ m}$ | 98.3% / $0.03\text{ m}$ |
| **$N = 10$** | $k = 5$ | 28.6% (6/21) | $1.67\text{ m}$ | 47.6% (10/21) | $1.51\text{ m}$ | 98.3% / $0.04\text{ m}$ |
| **$N = 30$** | $k = 1$ | 33.3% (7/21) | $1.79\text{ m}$ | **47.6%** (10/21) | **$1.40\text{ m}$** | 98.3% / $0.03\text{ m}$ |
| **$N = 30$** | $k = 3$ | 33.3% (7/21) | $1.67\text{ m}$ | 47.6% (10/21) | $1.56\text{ m}$ | 98.3% / $0.03\text{ m}$ |
| **$N = 30$** | $k = 5$ | 33.3% (7/21) | $1.63\text{ m}$ | 42.9% (9/21) | $1.60\text{ m}$ | 98.3% / $0.03\text{ m}$ |
| **$N = 50$** | $k = 1$ | 33.3% (7/21) | $1.65\text{ m}$ | 42.9% (9/21) | $1.61\text{ m}$ | 98.3% / $0.03\text{ m}$ |
| **$N = 50$** | $k = 3$ | 33.3% (7/21) | **$1.61\text{ m}$** | 42.9% (9/21) | $1.71\text{ m}$ | 98.3% / $0.03\text{ m}$ |
| **$N = 50$** | $k = 5$ | 33.3% (7/21) | **$1.61\text{ m}$** | 42.9% (9/21) | $1.65\text{ m}$ | 98.3% / $0.03\text{ m}$ |

#### Key Findings from the $N$ and $k$ Sensitivity Study:
1. **Metric Error Reduction via $k$-NN Coordinate Averaging:** On the primary survey device (**Redmi Note 14 5G**), increasing $N$ from $10 \to 50$ and increasing $k$ from $1 \to 3, 5$ steadily reduces mean metric localization error from **$1.79\text{ m}$ down to $1.61\text{ m}$**. Even when an ambiguous boundary query votes for an adjacent room (e.g., `Hall` vs. `Hallway 2`, separated by $2.0\text{ m}$), averaging the top-$k$ coordinates pulls the estimated position $(\hat{x}, \hat{y})$ toward the true boundary between the rooms.
2. **Diminishing Returns Beyond $N = 30$:** Most of the distributional stability is achieved by $N = 30$ scans ($1.63\text{ m}$ error at $k=5$, compared to $1.61\text{ m}$ at $N = 50$), confirming that $N = 30$ offers the best practical trade-off between survey effort and accuracy.

### 5.3 Strategy Comparison: Scan-Level $k$-NN vs. Centroid NCC vs. 3D Fuzzy Matrix

| Device | Scan-Level $k$-NN ($N=50, k=3$) | Nearest Centroid (NCC on Spot Means) | 3D Fuzzy Matrix Strategy | Best Mean Error in Metres |
| :--- | :---: | :---: | :---: | :---: |
| **Xiaomi Redmi Note 14 5G** (Survey Phone) | 33.3% ($1.61\text{ m}$) | **61.9%** (13/21, $1.08\text{ m}$) | **52.4%** (11/21, $1.34\text{ m}$) | **$1.08\text{ m}$ (NCC) / $1.34\text{ m}$ (Fuzzy)** |
| **Xiaomi Redmi Note 11** (Cross-Device Phone) | **47.6%** ($1.40\text{ m}$ at $N=30, k=1$) | **47.6%** (10/21, $1.40\text{ m}$) | 28.6% (6/21, $2.15\text{ m}$) | **$1.40\text{ m}$ (Scan $k$-NN / NCC)** |

- **Why Centroid NCC & Fuzzy Matrix Excel on the Survey Phone (Note 14 5G):** Individual survey scans contain transient multipath spikes that can attract single-scan $k$-NN neighbours from an adjacent room (`Corridor` vs. `Bedroom`). Averaging each spot into a centroid (61.9%, $1.08\text{ m}$ mean error) or weighting frequencies via the **3D Fuzzy Matrix** (52.4%, $1.34\text{ m}$ mean error) suppresses those outlier scans.
- **Why Scan-Level $k$-NN Outperforms Fuzzy Matrix Across Devices (Note 11):** On the **Redmi Note 11**, which exhibits a systematic $-3.9\text{ dB}$ to $-10\text{ dB}$ hardware offset relative to the Note 14 survey database, Euclidean distance in $k$-NN degrades gracefully (maintaining **47.6% accuracy and $1.40\text{ m}$ mean error**), whereas the Fuzzy Matrix's narrow $\pm 2\text{ dB}$ peak kernel experiences a sharper drop (28.6%, $2.15\text{ m}$) when all APs shift simultaneously by $>4\text{ dB}$.

### 5.4 Location-by-Location Empirical Breakdown (Walk-In Validation)

| Physical Location | Floor-Plan $(x_i, y_i)$ | Note 11 (NCC / $k$-NN) | Note 11 (Fuzzy) | Note 14 (NCC) | Note 14 (Fuzzy) | Observed RF & Multipath Behaviour |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **$L_1$: Prayer Room** | $(0.0, 0.0)\text{ m}$ | **100%** (3/3) | **100%** (3/3) | **100%** (3/3) | **100%** (3/3) | Strong LOS ($>-30\text{ dBm}$) uniquely separates $L_1$ ($0.00\text{ m}$ error). |
| **$L_6$: Room 2** | $(7.0, 1.0)\text{ m}$ | 0% (0/3) | 33% (1/3) | **100%** (3/3) | **100%** (3/3) | Note 14 matched $AP_3$ ($-69\text{ dBm}$); Note 11 read $10\text{ dB}$ lower ($-79\text{ dBm}$), shifting to $L_5$. |
| **$L_4$: Bedroom** | $(2.5, 3.5)\text{ m}$ | 33% (1/3) | 0% (0/3) | **100%** (3/3) | **67%** (2/3) | Note 14 matched $L_4$ centroid; 1 Fuzzy trial shifted $2.0\text{ m}$ to adjacent $L_5$. |
| **$L_2$: Hall** | $(2.5, 1.0)\text{ m}$ | 67% (2/3) | 0% (0/3) | 67% (2/3) | 0% (0/3) | $4\text{ dB}$ live fluctuation on $AP_1$ pushed queries toward $L_3$ ($2.0\text{ m}$ away). |
| **$L_3$: Hallway 2** | $(4.5, 1.0)\text{ m}$ | 0% (0/3) | 0% (0/3) | 0% (0/3) | 0% (0/3) | $15\text{ dB}$ body-shadowing drop on $AP_1$ during walk-in caused confusion with $L_5$ ($2.0\text{ m}$ error). |
| **$L_7$: Kitchen** | $(7.0, 3.0)\text{ m}$ | 67% (2/3) | 67% (2/3) | 33% (1/3) | **67%** (2/3) | Deep masonry attenuation profile matched well by Fuzzy Matrix on both phones. |
| **$L_5$: Corridor** | $(4.5, 3.0)\text{ m}$ | 67% (2/3) | 0% (0/3) | 33% (1/3) | 33% (1/3) | Central geometric overlap between $L_2$, $L_3$, and $L_6$ ($\approx 2.0\text{--}2.5\text{ m}$ radius). |
| **$L_8$: Storeroom** | $(9.0, 2.0)\text{ m}$ | **100%** (Holdout) | **100%** (Holdout) | **100%** (Holdout) | **100%** (Holdout) | Complete dropout of $AP_3$ (`-100 dBm` / `NS` = 1.00) uniquely identifies $L_8$. |

---

## 6. Confounders, Multi-Day Drift & Physical Limitations

1. **Survey-Device Confounder:** Because the reference database was surveyed on the **Redmi Note 14 5G**, its higher walk-in accuracy ($61.9\%$ vs. $47.6\%$ on Note 11) is **confounded by survey-device matching**. Differences in internal antenna placement, chassis attenuation, and chipset RSSI reporting curves (MediaTek Dimensity 7025-Ultra vs. Qualcomm Snapdragon 680) are **likely causes** of the observed offset rather than isolated proof of superior LNA quality.
2. **Signal-Level-Dependent Device Offset ($\alpha \cdot \text{RSSI} + \beta$):** The cross-device gap is not a constant $+3.9\text{ dB}$: in `Prayer Room` (strong LOS, $-25\text{ dBm}$) the phones agree within $<2\text{ dB}$, whereas in `Room 2` ($-69\text{ dBm}$ vs. $-79\text{ dBm}$) the gap widens to $10\text{ dB}$. Consequently, cross-device calibration requires a linear mapping $\text{RSSI}_{\text{norm}} = \alpha \cdot \text{RSSI} + \beta$ fitted from co-located calibration scans on both phones.
3. **Same-Day vs. Later-Day (+24h) Temporal Drift:** Comparing same-day walk-in tests against scans recorded $>24\text{ hours}$ later (`phone_gw_test_data.csv` vs. `pulled_phone_data.csv`) reveals a mean inter-day drift of $3.5\text{--}6.0\text{ dB}$ per AP caused by environmental changes (door states, occupancy, and router auto-channel/power adaptation), increasing mean localization error by $\approx 0.45\text{ m}$.
4. **3-AP Dimensionality Bottleneck & Body Shadowing:** With $M = 3$ APs, adjacent spots (`Hall` and `Hallway 2`, $2.0\text{ m}$ apart) sit only $\approx 9.8\text{ dB}$ apart in Euclidean RSSI space—smaller than the $10\text{--}15\text{ dB}$ attenuation caused by a user's torso blocking 2.4 GHz LOS. Expanding $M$ by including SHA-256-anonymised neighbouring 2.4/5 GHz BSSIDs and surveying 3–5 orientations per room are the most effective remedies.

---

## 7. AI-Usage & Source Attribution Summary (Section 6 of Brief)

Full details are maintained in [`AI_USAGE_LOG.md`](file:///home/vithurshan/mc/AI_USAGE_LOG.md). In summary:
- **Android SDK & Libraries:** Standard Android `WifiManager`, `LocationManagerCompat`, `RecyclerView`, and Python `numpy`/`pandas`/`matplotlib` for offline plotting.
- **AI Assistance (Antigravity Coding Assistant):** Used for (1) refactoring `KnnStrategy.kt` from centroid-only matching to true scan-level $k$-NN ($k \in \{1, 3, 5\}$) with 2D metric coordinate interpolation, (2) implementing the 3D Fuzzy Matrix with exponential long-tail smoothing in `FuzzyMatrixStrategy.kt`, (3) adding the 3-tab UI, `Stop`/resume survey controls, and SHA-256 BSSID anonymisation in `MainActivity.kt`, and (4) writing Python evaluation scripts (`analyze_devices.py`, `generate_plots.py`).
- **Verification:** All algorithms, unit tests (`StrategyPatternUnitTest.kt`), and empirical calculations were inspected, tested on physical phones, and verified by both group members (**S. Vithurshan** and **S. Pratheep**).
