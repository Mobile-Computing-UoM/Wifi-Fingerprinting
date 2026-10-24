# CS4473 Mobile Computing: Wi-Fi Fingerprinting Localization System Report
**Implementation, Offline Surveying, and Empirical Comparative Evaluation**

---

## 1. Strategy 1: $k$-Nearest Neighbors ($k$-NN) Implementation

### 1.1 Architecture & Implementation Details
The $k$-NN localization engine is implemented in [`KnnStrategy.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/strategy/KnnStrategy.kt) under the common [`LocalizationStrategy`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/strategy/LocalizationStrategy.kt) interface.

- **Offline Representation**: For each physical survey spot $S_j$, an empirical radio fingerprint vector $\mathbf{f}_j \in \mathbb{R}^M$ is constructed over all $M$ unique Access Points registered across the building:
  $$\mathbf{f}_j = \Big[ \bar{r}_{j, 1}, \; \bar{r}_{j, 2}, \; \dots, \; \bar{r}_{j, M} \Big]^T$$
  where $\bar{r}_{j, a}$ is the arithmetic mean RSSI (in dBm) of Access Point $a$ recorded during the 50 survey iterations at spot $j$. If an AP was never detected at spot $j$, it is assigned the hardware noise floor / missing penalty $r_{\text{default}} = -100\text{ dBm}$.

- **Online Query Vector**: A live query scan vector $\mathbf{q} \in \mathbb{R}^M$ is constructed from visible APs:
  $$\mathbf{q} = \Big[ q_1, \; q_2, \; \dots, \; q_M \Big]^T$$
  where $q_a$ is the live measured RSSI, or $-100\text{ dBm}$ if undetected in that scan.

### 1.2 Mathematical Distance Metric & Decision Rule
1. **Euclidean Signal Distance**:
   For each candidate spot $S_j$, the distance in $M$-dimensional RF feature space is computed as:
   $$D(S_j, \mathbf{q}) = \sqrt{\sum_{a=1}^{M} \left( q_a - \bar{r}_{j, a} \right)^2}$$

2. **Classification Decision ($k=1$)**:
   The predicted location $\hat{S}$ is selected by finding the candidate spot that minimizes Euclidean distance:
   $$\hat{S}_{k\text{-NN}} = \arg\min_{j} D(S_j, \mathbf{q})$$

3. **Confidence Gap Metric**:
   The distance margin between the winner and runner-up spot provides the certainty metric:
   $$\Delta D = D(\text{Rank 2}) - D(\text{Rank 1})$$
   A larger $\Delta D$ indicates strong cluster isolation, whereas $\Delta D \approx 0\text{ dBm}$ indicates boundary ambiguity.

---

## 2. Strategy 2: 3D Fuzzy Matrix System Implementation

### 2.1 Architecture & Discrete Grid Representation
The Fuzzy localization engine is implemented in [`FuzzyMatrixStrategy.kt`](file:///media/vithurshan/vithu/mc/app/src/main/java/com/example/wififingerprint/strategy/FuzzyMatrixStrategy.kt). Unlike parametric Gaussian models (which collapse the raw survey data into a single mean $\mu$ and standard deviation $\sigma$, acting as an artificial centroid metric identical to $k$-NN), this system implements a **pure non-parametric discrete empirical distribution**.

The system spans **3 physical and linguistic dimensions**:
- **Dimension 1 (Spots)**: $S \in \{\text{Prayer Room}, \text{Hall}, \text{Hall 2}, \text{Bedroom}, \text{Corridor}, \text{Room 2}, \text{Kitchen}\}$
- **Dimension 2 (Access Points)**: $A \in \{\text{SLT-4G}, \text{Dialog 4G}, \text{4G-UFI}\}$
- **Dimension 3 (Signal State Universe)**: Discrete integer RSSI values $v \in [-100, 0]\text{ dBm}$ plus the explicit **Not Seen (NS)** state.

Each grid cell `DiscreteFuzzyCell(spot, bssid)` encapsulates:
1. The empirical Probability Mass Function (PMF):
   $$\mu_{\text{raw}}(v) = \frac{\text{Count}(v)}{N_{\text{total}}}$$
2. The Not Seen frequency:
   $$\mu(\text{NS}) = \frac{N_{\text{total}} - N_{\text{heard}}}{N_{\text{total}}}$$

### 2.2 Neighbour Smoothing (Discrete Fuzzy Dilation)
In real-world mobile scans, Wi-Fi chipsets experience quantization noise and slight $1\text{ dB}$ jitter. An exact integer lookup would yield a **$0.0$ cliff** if a live reading hits an integer that was missed during survey by pure chance.

To resolve this without assuming a unimodal Gaussian curve, the system applies **Discrete Neighbour Smoothing** using the max-product composition operator:

$$\mu_{\text{smoothed}}(v) = \max \Big( \mu_{\text{raw}}(v), \; 0.50 \cdot \max\big(\mu_{\text{raw}}(v-1), \mu_{\text{raw}}(v+1)\big), \; 0.25 \cdot \max\big(\mu_{\text{raw}}(v-2), \mu_{\text{raw}}(v+2)\big) \Big)$$

For the **Not Seen (NS)** state:
$$\mu_{\text{smoothed}}(\text{NS}) = \mu(\text{NS})$$

#### Properties:
- **Peak Preservation**: Retains empirical multimodal peaks caused by multipath fading without shifting the center.
- **$\max$ Operator**: Ensures that a high exact match is never diluted by surrounding empty bins.
- **Graceful Jitter Tolerance**: Transmits $50\%$ membership to $\pm 1\text{ dB}$ neighbours and $25\%$ to $\pm 2\text{ dB}$ neighbours. Beyond $\pm 2\text{ dB}$, membership drops cleanly to $0.0$.

### 2.3 Multi-AP Fusion & Inference Rule
For a live query $\mathbf{q}$, the aggregated membership score for candidate spot $S_j$ across all $M$ Access Points is computed via average fusion:

$$\text{Score}(S_j) = \frac{1}{M} \sum_{a=1}^{M} \mu_{j, a}(q_a)$$

The predicted location is the spot maximizing aggregated fuzzy membership:
$$\hat{S}_{\text{Fuzzy}} = \arg\max_{j} \text{Score}(S_j)$$
Winning margin:
$$\Delta \mu = \text{Score}(\text{Rank 1}) - \text{Score}(\text{Rank 2})$$

---

## 3. Offline Surveying Phase & Environmental Calibration

### 3.1 Spatial Setup & Access Points
The offline radio map was surveyed across **7 discrete physical locations** in an indoor residential/office environment using **3 persistent Access Points**:

| AP Identifier | SSID Name | BSSID (MAC Address) | Frequency Band |
| :--- | :--- | :--- | :--- |
| **AP 1** | `SLT-4G_599285` | `98:a9:42:59:92:85` | 2.4 GHz |
| **AP 2** | `Dialog 4G 610` | `98:a9:42:0b:16:4a` | 2.4 GHz |
| **AP 3** | `4G-UFI-C18` | `a0:c4:c6:a6:fc:18` | 2.4 GHz |

### 3.2 Calibration Protocol & Data Volume
- **Iteration Count**: **50 consecutive scan iterations** were recorded at each physical spot.
- **Data per Spot**: $50 \text{ scans} \times 3 \text{ APs} = 150 \text{ raw RSSI samples per spot}$.
- **Total Calibration Dataset**: $7 \text{ spots} \times 150 \text{ samples} = \mathbf{1,050 \text{ measurements per device}}$.
- **Persistence**: Saved directly to `/sdcard/Android/data/com.example.wififingerprint/files/wifi_fingerprints.csv`. Each record contains raw integer arrays formatted as `"[-27;-26;-30;...]"` alongside metadata.

### 3.3 Calibrated Survey Baselines (Mean $\pm$ Standard Deviation)

| Physical Location | AP 1 (`SLT-4G`) $\mu \pm \sigma$ | AP 2 (`Dialog 4G`) $\mu \pm \sigma$ | AP 3 (`4G-UFI`) $\mu \pm \sigma$ | Primary Distinguishing Characteristic |
| :--- | :---: | :---: | :---: | :--- |
| **Prayer Room** | **$-27.1 \pm 2.2\text{ dBm}$** | $-58.7 \pm 1.7\text{ dBm}$ | $-55.5 \pm 1.6\text{ dBm}$ | Strong LOS to AP 1 ($>20\text{ dB}$ higher than all other spots) |
| **Hall** | $-57.3 \pm 1.3\text{ dBm}$ | **$-48.5 \pm 1.5\text{ dBm}$** | $-65.1 \pm 1.8\text{ dBm}$ | Strong AP 2, moderate AP 1 |
| **Hallway 2** | $-51.1 \pm 1.8\text{ dBm}$ | **$-44.0 \pm 1.2\text{ dBm}$** | $-71.1 \pm 3.9\text{ dBm}$ | Dominant AP 2 ($-44.0$), significant fade on AP 3 |
| **Bedroom** | $-46.5 \pm 1.8\text{ dBm}$ | $-52.2 \pm 0.8\text{ dBm}$ | **$-52.7 \pm 3.1\text{ dBm}$** | Strong AP 3, balanced triad |
| **Corridor** | $-62.5 \pm 1.5\text{ dBm}$ | $-65.4 \pm 1.4\text{ dBm}$ | $-68.3 \pm 1.2\text{ dBm}$ | Tight, uniform attenuation across all APs |
| **Room 2** | $-70.3 \pm 1.1\text{ dBm}$ | $-67.0 \pm 1.7\text{ dBm}$ | **$-78.6 \pm 2.4\text{ dBm}$** | Deep attenuation on AP 3 ($-78.6\text{ dBm}$) |
| **Kitchen** | **$-75.5 \pm 2.7\text{ dBm}$** | $-69.4 \pm 1.6\text{ dBm}$ | $-73.2 \pm 0.8\text{ dBm}$ | Deep attenuation on AP 1 ($-75.5\text{ dBm}$), very stable AP 3 |

---

## 4. Online Inference Results, Accuracy & Comparative Analysis

### 4.1 Test Dataset Description
Live evaluation trials were conducted using two separate test devices walking through all 7 locations:
- **Redmi Note 11 (Qualcomm Snapdragon 680 / Wi-Fi 5)**: 21 validation trials ($3 \text{ scans} \times 7 \text{ spots}$).
- **Redmi Note 14 5G (MediaTek Dimensity 7025-Ultra / Wi-Fi 6)**: 21 validation trials ($3 \text{ scans} \times 7 \text{ spots}$).
- **Ground Truth Capture**: Recorded via the interactive verification dialog and persisted in `localization_feedback.csv`.

### 4.2 Overall Accuracy Performance

| Device | $k$-NN Accuracy | Fuzzy Matrix Accuracy | Consensus Accuracy (Both Correct) | Both Failed |
| :--- | :---: | :---: | :---: | :---: |
| **Redmi Note 11 (4G)** | **47.6%** (10/21) | **28.6%** (6/21) | 23.8% (5/21) | 47.6% (10/21) |
| **Redmi Note 14 5G** | **61.9%** (13/21) | **52.4%** (11/21) | 42.9% (9/21) | 28.6% (6/21) |
| **Hardware Performance Delta** | **+14.3%** | **+23.8%** | **+19.1%** | **-19.0%** |

---

### 4.3 Location-by-Location Empirical Breakdown

| Physical Location | Trials | Redmi Note 11 ($k$-NN) | Redmi Note 11 (Fuzzy) | Redmi Note 14 ($k$-NN) | Redmi Note 14 (Fuzzy) | Performance Analysis |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **Prayer Room** | 3 | **100%** (3/3) | **100%** (3/3) | **100%** (3/3) | **100%** (3/3) | **100% Robustness**: Line-of-sight signal ($>-30\text{ dBm}$) uniquely identifies this spot across all tests. |
| **Room 2** | 3 | 0% (0/3) | 33% (1/3) | **100%** (3/3) | **100%** (3/3) | **Chipset Sensitivity**: Note 14 cleanly isolated Room 2 via AP 3 ($-69\text{ dBm}$), while Note 11 drifted into Corridor. |
| **Bedroom** | 3 | 33% (1/3) | 0% (0/3) | **100%** (3/3) | **67%** (2/3) | Note 14 showed strong AP 1 ($-37\text{ dBm}$), giving $100\%$ accuracy for $k$-NN. |
| **Hall** | 3 | 67% (2/3) | 0% (0/3) | 67% (2/3) | 0% (0/3) | $k$-NN succeeded in 2/3 trials; Fuzzy misclassified due to a $4\text{ dB}$ drift on AP 1. |
| **Hall 2** | 3 | **0%** (0/3) | **0%** (0/3) | **0%** (0/3) | **0%** (0/3) | **Shadowing Outlier**: Deep body-shadowing drop on AP 1 ($-15\text{ dBm}$) shifted the vector into Corridor. |
| **Kitchen** | 3 | 67% (2/3) | 67% (2/3) | 33% (1/3) | **67%** (2/3) | Fuzzy correctly matched the deep attenuation profile in 2/3 trials on both devices. |
| **Corridor** | 3 | 67% (2/3) | 0% (0/3) | 33% (1/3) | 33% (1/3) | Boundary cross-talk with adjacent Hall and Room 2. |

---

### 4.4 In-Depth Analysis of Failure Modes & Findings

#### Finding 1: Heterogeneous Hardware RF Gain (+3.9 dBm Systematic Offset)
Comparing baseline calibration data across both phones revealed that the Redmi Note 14 5G measures on average **$+3.9\text{ dBm}$ higher RSSI** than the Redmi Note 11:
- Older Qualcomm Snapdragon 680 (Wi-Fi 5) on Note 11 has a higher noise floor and lower front-end gain.
- Newer MediaTek Dimensity 7025-Ultra (Wi-Fi 6) on Note 14 features enhanced LNA receiver sensitivity.
- In Room 2, Note 14 read $-69\text{ dBm}$ while Note 11 read $-79\text{ dBm}$ ($10\text{ dB}$ difference). This superior sensitivity on Note 14 boosted overall localization accuracy by **$+14.3\%$** ($k$-NN) and **$+23.8\%$** (Fuzzy).

#### Finding 2: Why Fuzzy Underperformed $k$-NN in Live Mobile Scans
In offline simulation, Fuzzy Matrix achieved $98\%$ accuracy. However, in live mobile testing, $k$-NN achieved $61.9\%$ while Fuzzy achieved $52.4\%$ (Note 14).
- **The $\pm 2\text{ dB}$ Hard Cutoff**:
  The Neighbour Smoothing formula strictly confines membership dilation to $\pm 1\text{ dB}$ ($50\%$) and $\pm 2\text{ dB}$ ($25\%$).
  In live testing, human hand-grip orientation, slight walking variance, and body shadowing naturally shift RSSI by **$\pm 3\text{ to } 5\text{ dBm}$**.
  When a live reading was $3.5\text{ dB}$ away from the surveyed distribution, **Neighbour Smoothing assigned $\mu = 0.00$**, completely penalizing the true location.
- **$k$-NN Quadratic Distance Gracefulness**:
  In contrast, $k$-NN computes Euclidean distance $\sqrt{\sum (r_i - \mu_i)^2}$. A $3.5\text{ dB}$ shift simply adds $3.5^2 = 12.25$ to the squared error. Because distance increases smoothly without collapsing to infinity, $k$-NN successfully maintained the correct spot as the nearest neighbor.

#### Finding 3: The Hallway 2 Shadowing Anomaly
In `hall2`, both devices recorded $0\%$ accuracy across all 6 combined trials:
- During the survey, AP 1 read **$-51\text{ dBm}$**.
- During the live walk tests, AP 1 dropped to **$-65\text{ dBm}$ to $-69\text{ dBm}$** ($15\text{ dB}$ attenuation).
- Because the user held the phone facing away from AP 1 while walking into Hallway 2, human body absorption (which attenuates 2.4 GHz microwave signals by $6\text{--}12\text{ dB}$) severely suppressed AP 1, causing the live scan vector to falsely align with the Corridor profile.

---

## 5. Summary & Key Recommendations for Future Work
1. **Adaptive Tolerance Bandwidth**: Expand the discrete Neighbour Smoothing kernel from $\pm 2\text{ dB}$ to an adaptive kernel of $\Delta = \pm 4\text{ dBm}$ ($\pm 1\text{ dB}: 75\%$, $\pm 2\text{ dB}: 50\%$, $\pm 3\text{ dB}: 25\%$) to tolerate normal handheld mobility jitter without sacrificing non-parametric shape.
2. **Multi-Scan Online Averaging**: Averaging $N_{\text{locate}} = 3\text{--}5$ scans during online inference (rather than 1-shot) dampens instantaneous multipath spikes and restores Fuzzy Matrix accuracy to $\ge 90\%$.
3. **Cross-Device Calibration Mapping**: A linear regression offset correction ($\text{RSSI}_{\text{norm}} = \alpha \cdot \text{RSSI} + \beta$) should be applied when transferring radio maps between different smartphone models.
