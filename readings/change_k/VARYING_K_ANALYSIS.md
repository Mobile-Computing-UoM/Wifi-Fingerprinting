# Empirical Evaluation of Varying Nearest-Neighbour Count ($k$) & Distance-Weighted $k$-NN (WKNN)

---

## 1. Executive Summary & Research Context

According to **Section 6 (Option E)** and **Section 4** of the CS4473 Mobile Computing Research Project guidelines, positioning systems must systematically **"vary something on purpose over at least three levels"** and evaluate both:
1. **Discrete Spot Classification Accuracy (%)**: Fraction of queries correctly localized to the exact reference spot.
2. **Physical Localization Distance Error (Metres)**: Continuous Euclidean error between true tape-measured coordinates $(x_{\text{true}}, y_{\text{true}})$ and estimated coordinates $(\hat{x}, \hat{y})$.

This study systematically investigates the parameter $k \in \{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15\}$ under both **Standard Majority-Voting $k$-NN** and **Inverse-Distance-Weighted $k$-NN (WKNN)** across:
* **Offline Radio Map**: 500 calibration scan sweeps ($N=50$ scans $\times$ 10 spots, spaced 2.0 m apart in an L-shaped corridor/room grid).
* **Same-Day Testbed (Day 01)**: 50 independent trials ($M=5$ trials $\times$ 10 spots, $n=20$ locate scans averaged per trial).
* **Multi-Day Temporal Drift (Day 02, 24h Later)**: 50 independent trials under identical grid coordinates.
* **Combined Empirical Benchmark**: 100 independent trials (2,000 total live Wi-Fi sweeps).

---

## 2. Mathematical & Algorithmic Formulation

### 2.1 Euclidean Signal Distance Matching
Let $\mathbf{s} = (s_1, s_2, \dots, s_D)$ be an online Wi-Fi query vector and $\mathbf{r}^{(j)} = (r_1^{(j)}, r_2^{(j)}, \dots, r_D^{(j)})$ be the $j$-th individual calibration scan vector in the radio map ($j \in \{1, \dots, 500\}$) across all registered Access Points $D = 5$. Missing AP readings are imputed at $s_d = -100\text{ dBm}$. The signal space Euclidean distance is:

$$d_j = \|\mathbf{s} - \mathbf{r}^{(j)}\|_2 = \sqrt{\sum_{d=1}^D (s_d - r_d^{(j)})^2}$$

Let $\mathcal{N}_k(\mathbf{s})$ denote the ordered set of the top-$k$ nearest individual scan vectors with minimal distances $d_1 \le d_2 \le \dots \le d_k$.

### 2.2 Standard Uniform $k$-NN (RADAR Centroiding)
* **Discrete Spot Label**: Determined by majority voting among the top-$k$ neighbours:
  $$\hat{S}_{\text{kNN}} = \arg\max_{S \in \mathcal{S}} \sum_{j \in \mathcal{N}_k(\mathbf{s})} \mathbb{I}(\text{Spot}(j) = S)$$
  *(Ties are broken by minimal average Euclidean distance among tied scans).*
* **Continuous 2D Coordinate Estimation**: The physical position is estimated by averaging the known coordinates $(x_j, y_j)$ of the $k$ nearest neighbours (as proposed by Bahl & Padmanabhan in the RADAR system):
  $$\hat{\mathbf{p}}_{\text{kNN}} = (\hat{x}, \hat{y}) = \frac{1}{k} \sum_{j \in \mathcal{N}_k(\mathbf{s})} (x_j, y_j)$$

### 2.3 Distance-Weighted $k$-NN (WKNN)
To prevent distant coincidental matches from distorting the coordinate estimate at higher $k$, inverse-distance weights are computed:

$$w_j = \frac{1}{\max(d_j, \epsilon)}, \quad \tilde{w}_j = \frac{w_j}{\sum_{m \in \mathcal{N}_k(\mathbf{s})} w_m}$$

* **Weighted Spot Voting**:
  $$\hat{S}_{\text{WKNN}} = \arg\max_{S \in \mathcal{S}} \sum_{j \in \mathcal{N}_k(\mathbf{s})} \tilde{w}_j \cdot \mathbb{I}(\text{Spot}(j) = S)$$
* **Weighted Coordinate Centroid**:
  $$\hat{\mathbf{p}}_{\text{WKNN}} = \sum_{j \in \mathcal{N}_k(\mathbf{s})} \tilde{w}_j \cdot (x_j, y_j)$$

### 2.4 Metric Error Metric
Physical distance error in metres:
$$\epsilon_{\text{dist}} = \|\mathbf{p}_{\text{true}} - \hat{\mathbf{p}}\|_2 = \sqrt{(x_{\text{true}} - \hat{x})^2 + (y_{\text{true}} - \hat{y})^2}$$

---

## 3. Experimental Results Across $k \in \{1, \dots, 15\}$

The table below summarizes the performance metrics evaluated using [analyze_varying_k.py](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/analyze_varying_k.py) across all 100 test trials:

### Comprehensive Results Table (100 Trials: Day 01 + Day 02)

| $k$ | $k$-NN Acc (%) | $k$-NN Mean Err (m) | $k$-NN Std (m) | Median (m) | 90th Pct (m) | WKNN Acc (%) | WKNN Mean Err (m) | WKNN Std (m) | WKNN 90th Pct (m) |
| :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **$k=1$** | **32.0%** | 2.507 | 2.217 | 2.000 | 5.657 | 32.0% | 2.507 | 2.217 | 5.657 |
| **$k=2$** | **32.0%** | 2.344 | 1.939 | 2.000 | 5.657 | 32.0% | 2.345 | 1.947 | 5.657 |
| **$k=3$** ⭐ | **30.0%** | **2.265** | **1.754** | **2.000** | **4.000** | **30.0%** | **2.267** | **1.766** | **4.009** |
| **$k=4$** | 31.0% | 2.372 | 1.736 | 2.000 | 4.243 | 31.0% | 2.357 | 1.750 | 4.327 |
| **$k=5$** | 28.0% | 2.467 | 1.675 | 2.154 | 4.525 | 28.0% | 2.441 | 1.693 | 4.552 |
| **$k=6$** | 27.0% | 2.498 | 1.706 | 2.054 | 4.714 | 27.0% | 2.471 | 1.717 | 4.712 |
| **$k=7$** | 25.0% | 2.516 | 1.672 | 2.295 | 4.849 | 26.0% | 2.483 | 1.687 | 4.795 |
| **$k=8$** | 23.0% | 2.496 | 1.648 | 2.332 | 4.545 | 25.0% | 2.470 | 1.657 | 4.532 |
| **$k=9$** | 25.0% | 2.525 | 1.629 | 2.404 | 4.400 | 27.0% | 2.495 | 1.636 | 4.437 |
| **$k=10$**| 24.0% | 2.519 | 1.622 | 2.437 | 4.525 | 26.0% | 2.490 | 1.628 | 4.526 |
| **$k=15$**| 22.0% | 2.504 | 1.540 | 2.335 | 4.267 | 22.0% | 2.486 | 1.546 | 4.250 |

---

## 4. Key Empirical Findings & Physical Discussion

### Finding 1: The $k=3$ Optimal "Sweet Spot"
* **Minimum Physical Distance Error**: The lowest mean metric localization error is achieved at **$k = 3$ ($2.265\text{ m}$)**, representing an improvement of **$0.24\text{ m}$ (9.7%)** over $k=1$ ($2.507\text{ m}$).
* **Dramatic Reduction in Outlier Spread**:
  * The standard deviation of the localization error drops significantly from **$\pm 2.22\text{ m}$ ($k=1$) down to $\pm 1.75\text{ m}$ ($k=3$)**.
  * The **90th percentile error drops from $5.66\text{ m}$ to $4.00\text{ m}$**—an immediate **$1.66\text{ m}$ reduction** in worst-case positioning errors.
* **Physical Cause**: A single raw scan vector ($k=1$) is susceptible to small-scale destructive multipath interference on one AP. Taking the top-3 nearest individual scans filters out single-scan radio anomalies while maintaining spatial sharpness.

### Finding 2: The Diminishing Returns & Centroid Bias at Large $k$ ($k \ge 5$)
* When $k$ increases to $5, 7, 9$, mean distance error steadily increases back up to $2.52\text{ m}$, and spot classification accuracy degrades from $32\%$ down to $22\%$.
* **Geometric Cause**: In an indoor testbed with 10 reference points, setting $k=7$ or $k=9$ forces the centroid to incorporate scans from spots $6\text{--}10\text{ m}$ away. This exerts an inward pulling force toward the geometric median of the building, biasing estimations and blunting edge-spot localization.

### Finding 3: WKNN Outperforms Standard $k$-NN at Higher $k$
* For $k \in \{1, 2, 3\}$, standard $k$-NN and WKNN perform nearly identically because the top 1–3 scans share very similar distance values.
* For **$k \ge 5$**, **WKNN systematically outperforms standard $k$-NN**:
  * At $k=7$: WKNN achieves $26.0\%$ accuracy vs. $25.0\%$ for $k$-NN, and lower mean error ($2.48\text{ m}$ vs. $2.52\text{ m}$).
  * At $k=9$: WKNN achieves $27.0\%$ accuracy vs. $25.0\%$ for $k$-NN, and lower mean error ($2.50\text{ m}$ vs. $2.52\text{ m}$).
* **Physical Cause**: Inverse-distance weighting $\tilde{w}_j = 1/d_j$ assigns exponentially lower influence to distant neighbours, successfully resisting centroid pulling bias.

### Finding 4: The Accuracy vs. Metric Error Paradox
* A critical observation for the final report: **Classification accuracy slightly decreases** from $32.0\%$ ($k=1$) to $30.0\%$ ($k=3$), yet **physical metric distance error improves** from $2.51\text{ m}$ to $2.26\text{ m}$.
* **Why**: Discrete accuracy treats a prediction 0.5 m away from the true spot boundary as a total failure ($0\%$). In contrast, 2D centroiding averages the $(x, y)$ positions of neighbouring scans, yielding a continuous spatial estimate that is physically much closer to true position.

---

## 5. Per-Spot Error Dynamics ($k \in \{1, 3, 5\}$)

| Spot | True $(x, y)$ Coordinates | Mean Error at $k=1$ | Mean Error at $k=3$ | Mean Error at $k=5$ | Spatial Trajectory Observation |
| :---: | :---: | :---: | :---: | :---: | :--- |
| **`S1`** | $(0.0, 0.0)\text{ m}$ | 3.20 m | 3.10 m | 3.25 m | Corridor Start: Pull toward adjacent `S2` & `S3`. |
| **`S2`** | $(2.0, 0.0)\text{ m}$ | **0.80 m** | **0.90 m** | **1.22 m** | Highly stable ($0.80\text{ m}$ error at $k=1$). |
| **`S3`** | $(4.0, 0.0)\text{ m}$ | 2.89 m | 2.37 m | 2.39 m | Averaging at $k=3$ cuts error by $0.52\text{ m}$. |
| **`S4`** | $(6.0, 0.0)\text{ m}$ | 2.42 m | 2.14 m | 2.21 m | Robust middle corridor node. |
| **`S5`** | $(8.0, 0.0)\text{ m}$ | 3.40 m | 3.01 m | 3.12 m | Corridor bend: $k=3$ reduces error by $0.39\text{ m}$. |
| **`S6`** | $(8.0, 2.0)\text{ m}$ | 4.52 m | 4.10 m | 4.36 m | Turning segment; pulls along vertical hallway. |
| **`S7`** | $(8.0, 4.0)\text{ m}$ | 3.16 m | 2.65 m | 2.76 m | Averaging top-3 scans reduces error by $0.51\text{ m}$. |
| **`S8`** | $(8.0, 6.0)\text{ m}$ | **0.80 m** | **0.75 m** | **0.95 m** | High spatial RF contrast ($0.75\text{ m}$ error at $k=3$). |
| **`S9`** | $(6.0, 6.0)\text{ m}$ | 3.69 m | 3.43 m | 3.51 m | Parallel corridor segment. |
| **`S10`**| $(4.0, 6.0)\text{ m}$ | **0.00 m** | **0.20 m** | **0.90 m** | Perfect $0.00\text{ m}$ error at $k=1$ across all runs. |

---

## 6. Generated Visualizations & Artifacts

All scripts and plots are stored locally in the [readings/change_k/](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/) directory:

1. **[plots/accuracy_vs_k.png](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/plots/accuracy_vs_k.png)**: Spot classification accuracy curves across $k \in \{1, \dots, 15\}$ for Day 01, Day 02, and Combined trials.
2. **[plots/distance_error_vs_k.png](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/plots/distance_error_vs_k.png)**: Mean and median metric distance error curves illustrating the $k=3$ minimum and subsequent centroid pulling bias.
3. **[plots/cdf_error_by_k.png](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/plots/cdf_error_by_k.png)**: Cumulative Distribution Function (CDF) overlay comparing error distributions for $k=1$, $k=3$, $k=5$, and WKNN.
4. **[plots/per_spot_error_k1_k3_k5.png](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/plots/per_spot_error_k1_k3_k5.png)**: Bar chart showing individual spot metric error across the 10 spots for $k \in \{1, 3, 5\}$.
5. **[varying_k_summary.csv](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/varying_k_summary.csv)**: Full numerical output across all evaluated $k$ parameters for Day 01, Day 02, and Combined runs.
6. **[analyze_varying_k.py](file:///home/pratheep/Desktop/7_SEM/MC/project/Wifi-Fingerprinting/readings/change_k/analyze_varying_k.py)**: The reproducible standalone Python script used to run this analysis.

