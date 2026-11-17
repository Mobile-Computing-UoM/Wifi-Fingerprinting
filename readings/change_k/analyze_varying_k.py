#!/usr/bin/env python3
"""
Varying-k Empirical Analysis & Evaluation Pipeline
=================================================
Analyzes the impact of nearest neighbor parameter k (k in {1, 2, 3, 4, 5, 7, 9, 11, 15})
and distance-weighted voting (WKNN) using:
  - Radio Map: readings/base/map/wifi_fingerprints.csv (50 calibration scans x 10 spots = 500 vectors)
  - Day 01 Testbed Trials: readings/base/day-01/experiment_results.csv (50 trials)
  - Day 02 Testbed Trials: readings/base/day-02/experiment_results.csv (50 trials)

Outputs:
  - readings/change_k/varying_k_summary.csv
  - readings/change_k/plots/accuracy_vs_k.png
  - readings/change_k/plots/distance_error_vs_k.png
  - readings/change_k/plots/cdf_error_by_k.png
  - readings/change_k/plots/per_spot_error_k1_k3_k5.png
"""

import os
import sys
import csv
import math
from collections import defaultdict, Counter
import numpy as np
import matplotlib.pyplot as plt

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
BASE_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", "base"))
MAP_FILE = os.path.join(BASE_DIR, "map", "wifi_fingerprints.csv")
DAY01_FILE = os.path.join(BASE_DIR, "day-01", "experiment_results.csv")
DAY02_FILE = os.path.join(BASE_DIR, "day-02", "experiment_results.csv")
PLOTS_DIR = os.path.join(SCRIPT_DIR, "plots")
OUTPUT_CSV = os.path.join(SCRIPT_DIR, "varying_k_summary.csv")

MISSING_RSSI_FLOOR = -100

def load_radio_map(filepath):
    """
    Loads all individual scan vectors from the survey map.
    Returns:
      spots_coords: dict of spot_label -> (x, y)
      flat_scans: list of (spot_label, scan_idx, {bssid: rssi})
      all_bssids: set of unique BSSIDs
    """
    spots_coords = {}
    scan_buffers = defaultdict(lambda: defaultdict(dict))
    all_bssids = set()

    with open(filepath, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            spot = row["spot_label"].strip()
            x = float(row["coord_x"])
            y = float(row["coord_y"])
            spots_coords[spot] = (x, y)
            bssid = row["bssid"].strip()
            all_bssids.add(bssid)
            
            raw_samples = row["samples"].strip().strip('"').strip("[]")
            if not raw_samples:
                continue
            samples = [int(s) for s in raw_samples.split(";") if s.strip()]
            for scan_idx, rssi in enumerate(samples):
                scan_buffers[scan_idx][spot][bssid] = rssi

    flat_scans = []
    for scan_idx in sorted(scan_buffers.keys()):
        for spot, ap_dict in scan_buffers[scan_idx].items():
            flat_scans.append((spot, scan_idx, ap_dict))

    return spots_coords, flat_scans, all_bssids

def load_test_trials(filepath):
    trials = []
    with open(filepath, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            act_spot = row["actual_spot"].strip()
            act_x = float(row["actual_x"])
            act_y = float(row["actual_y"])
            trial_idx = int(row["trial_idx"])
            sig_dict = {}
            raw_sigs = row["raw_signals"].strip()
            for pair in raw_sigs.split(";"):
                if "=" in pair:
                    b, val = pair.split("=")
                    sig_dict[b.strip()] = int(val.strip())
            trials.append({
                "actual_spot": act_spot,
                "actual_x": act_x,
                "actual_y": act_y,
                "trial_idx": trial_idx,
                "signals": sig_dict
            })
    return trials

def evaluate_trial_knn(trial, flat_scans, spots_coords, all_bssids, k, missing_floor=MISSING_RSSI_FLOOR):
    """
    Computes Euclidean distances to all individual scans and evaluates:
      1. Standard uniform k-NN (majority vote spot + uniform centroid (x,y))
      2. Distance-weighted k-NN (WKNN) (accumulated inverse distance spot + weighted centroid (x,y))
    """
    test_sigs = trial["signals"]
    act_spot = trial["actual_spot"]
    act_x = trial["actual_x"]
    act_y = trial["actual_y"]

    matches = []
    for s_label, s_idx, s_readings in flat_scans:
        sum_sq = 0.0
        for b in all_bssids:
            r_map = s_readings.get(b, missing_floor)
            r_test = test_sigs.get(b, missing_floor)
            diff = r_map - r_test
            sum_sq += diff * diff
        dist = math.sqrt(sum_sq)
        matches.append((dist, s_label, s_idx))

    matches.sort(key=lambda m: m[0])
    top_k = matches[:k]

    # --- 1. Standard Uniform k-NN ---
    votes = Counter([m[1] for m in top_k])
    # Tie-break: highest vote count, then lowest average distance among tied scans
    best_spot_knn = sorted(
        votes.keys(),
        key=lambda s: (-votes[s], np.mean([m[0] for m in top_k if m[1] == s]))
    )[0]
    knn_correct = (best_spot_knn.lower() == act_spot.lower())

    top_coords = [spots_coords[m[1]] for m in top_k]
    knn_pred_x = float(np.mean([c[0] for c in top_coords]))
    knn_pred_y = float(np.mean([c[1] for c in top_coords]))
    knn_error = math.sqrt((act_x - knn_pred_x)**2 + (act_y - knn_pred_y)**2)

    # --- 2. Distance-Weighted k-NN (WKNN) ---
    weights = [1.0 / max(m[0], 0.001) for m in top_k]
    total_w = sum(weights)
    norm_w = [w / total_w for w in weights]

    wknn_pred_x = float(sum(w * c[0] for w, c in zip(norm_w, top_coords)))
    wknn_pred_y = float(sum(w * c[1] for w, c in zip(norm_w, top_coords)))
    wknn_error = math.sqrt((act_x - wknn_pred_x)**2 + (act_y - wknn_pred_y)**2)

    spot_accum_w = defaultdict(float)
    for m, w in zip(top_k, norm_w):
        spot_accum_w[m[1]] += w
    best_spot_wknn = max(spot_accum_w.keys(), key=lambda s: spot_accum_w[s])
    wknn_correct = (best_spot_wknn.lower() == act_spot.lower())

    return {
        "knn_spot": best_spot_knn,
        "knn_correct": knn_correct,
        "knn_error": knn_error,
        "wknn_spot": best_spot_wknn,
        "wknn_correct": wknn_correct,
        "wknn_error": wknn_error
    }

def run_evaluation_across_k(trials, flat_scans, spots_coords, all_bssids, k_list):
    summary = {}
    per_trial_results = defaultdict(list)

    for k in k_list:
        knn_corr = []
        wknn_corr = []
        knn_errs = []
        wknn_errs = []

        for t in trials:
            res = evaluate_trial_knn(t, flat_scans, spots_coords, all_bssids, k)
            knn_corr.append(1 if res["knn_correct"] else 0)
            wknn_corr.append(1 if res["wknn_correct"] else 0)
            knn_errs.append(res["knn_error"])
            wknn_errs.append(res["wknn_error"])
            per_trial_results[k].append(res)

        n = len(trials)
        summary[k] = {
            "k": k,
            "trials": n,
            "knn_acc": float(np.mean(knn_corr) * 100),
            "knn_mean_err": float(np.mean(knn_errs)),
            "knn_std_err": float(np.std(knn_errs)),
            "knn_median_err": float(np.median(knn_errs)),
            "knn_75_err": float(np.percentile(knn_errs, 75)),
            "knn_90_err": float(np.percentile(knn_errs, 90)),
            "knn_max_err": float(np.max(knn_errs)),
            "wknn_acc": float(np.mean(wknn_corr) * 100),
            "wknn_mean_err": float(np.mean(wknn_errs)),
            "wknn_std_err": float(np.std(wknn_errs)),
            "wknn_median_err": float(np.median(wknn_errs)),
            "wknn_75_err": float(np.percentile(wknn_errs, 75)),
            "wknn_90_err": float(np.percentile(wknn_errs, 90)),
            "wknn_max_err": float(np.max(wknn_errs)),
            "knn_raw_errs": knn_errs,
            "wknn_raw_errs": wknn_errs
        }
    return summary, per_trial_results

def main():
    print("=" * 70)
    print("🔬 VARYING-K SYSTEMATIC EXPERIMENTAL EVALUATION")
    print("=" * 70)

    if not os.path.exists(MAP_FILE):
        print(f"❌ Error: Radio map not found at {MAP_FILE}")
        sys.exit(1)

    spots_coords, flat_scans, all_bssids = load_radio_map(MAP_FILE)
    print(f"📍 Loaded Radio Map: {len(spots_coords)} reference spots, {len(flat_scans)} total scan vectors, {len(all_bssids)} APs")

    d1_trials = load_test_trials(DAY01_FILE) if os.path.exists(DAY01_FILE) else []
    d2_trials = load_test_trials(DAY02_FILE) if os.path.exists(DAY02_FILE) else []
    combined_trials = d1_trials + d2_trials

    print(f"📦 Test Datasets: Day 01 = {len(d1_trials)} trials | Day 02 = {len(d2_trials)} trials | Combined = {len(combined_trials)} trials")

    k_values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15]

    print("\nEvaluating across k in", k_values, "...")
    d1_summary, _ = run_evaluation_across_k(d1_trials, flat_scans, spots_coords, all_bssids, k_values)
    d2_summary, _ = run_evaluation_across_k(d2_trials, flat_scans, spots_coords, all_bssids, k_values)
    comb_summary, comb_trial_res = run_evaluation_across_k(combined_trials, flat_scans, spots_coords, all_bssids, k_values)

    # Save CSV Summary
    os.makedirs(os.path.dirname(OUTPUT_CSV), exist_ok=True)
    with open(OUTPUT_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            "dataset", "k", "knn_acc_pct", "knn_mean_err_m", "knn_std_err_m", "knn_median_err_m", "knn_90_err_m",
            "wknn_acc_pct", "wknn_mean_err_m", "wknn_std_err_m", "wknn_median_err_m", "wknn_90_err_m"
        ])
        for tag, s_dict in [("Day 01 (Same-Day)", d1_summary), ("Day 02 (24h Later)", d2_summary), ("Combined (All 100 Trials)", comb_summary)]:
            for k in k_values:
                row = s_dict[k]
                writer.writerow([
                    tag, k,
                    f"{row['knn_acc']:.1f}", f"{row['knn_mean_err']:.3f}", f"{row['knn_std_err']:.3f}", f"{row['knn_median_err']:.3f}", f"{row['knn_90_err']:.3f}",
                    f"{row['wknn_acc']:.1f}", f"{row['wknn_mean_err']:.3f}", f"{row['wknn_std_err']:.3f}", f"{row['wknn_median_err']:.3f}", f"{row['wknn_90_err']:.3f}"
                ])
    print(f"💾 Results saved to CSV: {OUTPUT_CSV}")

    # Generate Publication Plots
    os.makedirs(PLOTS_DIR, exist_ok=True)

    # --- Plot 1: Accuracy vs k ---
    plt.figure(figsize=(9, 5.5))
    plt.plot(k_values, [d1_summary[k]["knn_acc"] for k in k_values], marker="o", label="k-NN (Day 01 Same-Day)", color="#1976D2", linewidth=2.2)
    plt.plot(k_values, [d1_summary[k]["wknn_acc"] for k in k_values], marker="s", linestyle="--", label="WKNN (Day 01 Same-Day)", color="#0D47A1", linewidth=2.0)
    plt.plot(k_values, [d2_summary[k]["knn_acc"] for k in k_values], marker="^", label="k-NN (Day 02 24h Later)", color="#E64A19", linewidth=2.2)
    plt.plot(k_values, [d2_summary[k]["wknn_acc"] for k in k_values], marker="d", linestyle="--", label="WKNN (Day 02 24h Later)", color="#BF360C", linewidth=2.0)
    plt.plot(k_values, [comb_summary[k]["knn_acc"] for k in k_values], marker="*", label="k-NN (Combined 100 Trials)", color="#2E7D32", linewidth=2.5)
    plt.title("Spot Classification Accuracy vs. Nearest Neighbour Count (k)", fontsize=13, fontweight="bold")
    plt.xlabel("k (Number of Nearest Scan Neighbours)", fontsize=11)
    plt.ylabel("Classification Accuracy (%)", fontsize=11)
    plt.xticks(k_values)
    plt.grid(True, linestyle="--", alpha=0.6)
    plt.legend(fontsize=9, loc="best")
    plt.tight_layout()
    p1 = os.path.join(PLOTS_DIR, "accuracy_vs_k.png")
    plt.savefig(p1, dpi=300)
    plt.close()
    print(f"📈 Saved: {p1}")

    # --- Plot 2: Distance Error vs k ---
    plt.figure(figsize=(9, 5.5))
    plt.plot(k_values, [comb_summary[k]["knn_mean_err"] for k in k_values], marker="o", label="k-NN Mean Error (Combined)", color="#1976D2", linewidth=2.2)
    plt.plot(k_values, [comb_summary[k]["knn_median_err"] for k in k_values], marker="v", linestyle=":", label="k-NN Median Error (Combined)", color="#0288D1", linewidth=2.0)
    plt.plot(k_values, [comb_summary[k]["wknn_mean_err"] for k in k_values], marker="s", label="WKNN Mean Error (Combined)", color="#E64A19", linewidth=2.2)
    plt.plot(k_values, [comb_summary[k]["wknn_median_err"] for k in k_values], marker="^", linestyle=":", label="WKNN Median Error (Combined)", color="#F57C00", linewidth=2.0)
    plt.axhline(y=2.0, color="gray", linestyle="--", alpha=0.7, label="Grid Spacing (2.0 m)")
    plt.title("Physical Localization Distance Error vs. k (100 Trials)", fontsize=13, fontweight="bold")
    plt.xlabel("k (Number of Nearest Scan Neighbours)", fontsize=11)
    plt.ylabel("Distance Error in Metres", fontsize=11)
    plt.xticks(k_values)
    plt.grid(True, linestyle="--", alpha=0.6)
    plt.legend(fontsize=9, loc="upper right")
    plt.tight_layout()
    p2 = os.path.join(PLOTS_DIR, "distance_error_vs_k.png")
    plt.savefig(p2, dpi=300)
    plt.close()
    print(f"📈 Saved: {p2}")

    # --- Plot 3: Error CDF for k = 1, 3, 5, WKNN ---
    plt.figure(figsize=(9, 5.5))
    for k_val, col in [(1, "#1976D2"), (3, "#7B1FA2"), (5, "#388E3C")]:
        errs = np.sort(comb_summary[k_val]["knn_raw_errs"])
        cdf = np.arange(1, len(errs) + 1) / len(errs)
        plt.plot(errs, cdf, label=f"k-NN (k={k_val})", color=col, linewidth=2.2)
    
    wknn_errs_k3 = np.sort(comb_summary[3]["wknn_raw_errs"])
    cdf_wknn = np.arange(1, len(wknn_errs_k3) + 1) / len(wknn_errs_k3)
    plt.plot(wknn_errs_k3, cdf_wknn, label="WKNN (k=3, Distance-Weighted)", color="#D84315", linewidth=2.4, linestyle="--")

    plt.axvline(x=2.0, color="black", linestyle=":", alpha=0.6, label="2.0 m Baseline")
    plt.axhline(y=0.5, color="red", linestyle=":", alpha=0.5, label="50% (Median)")
    plt.title("Cumulative Distribution Function (CDF) Across k Parameters", fontsize=13, fontweight="bold")
    plt.xlabel("Localization Distance Error (Metres)", fontsize=11)
    plt.ylabel("Cumulative Probability (CDF)", fontsize=11)
    plt.xlim(0, 8.5)
    plt.ylim(0, 1.05)
    plt.grid(True, linestyle="--", alpha=0.6)
    plt.legend(fontsize=9, loc="lower right")
    plt.tight_layout()
    p3 = os.path.join(PLOTS_DIR, "cdf_error_by_k.png")
    plt.savefig(p3, dpi=300)
    plt.close()
    print(f"📈 Saved: {p3}")

    # --- Plot 4: Per-Spot Mean Error for k in {1, 3, 5} ---
    spots = [f"S{i}" for i in range(1, 11)]
    spot_err_k1 = []
    spot_err_k3 = []
    spot_err_k5 = []

    for s in spots:
        errs_1 = [comb_trial_res[1][i]["knn_error"] for i, t in enumerate(combined_trials) if t["actual_spot"] == s]
        errs_3 = [comb_trial_res[3][i]["knn_error"] for i, t in enumerate(combined_trials) if t["actual_spot"] == s]
        errs_5 = [comb_trial_res[5][i]["knn_error"] for i, t in enumerate(combined_trials) if t["actual_spot"] == s]
        spot_err_k1.append(np.mean(errs_1))
        spot_err_k3.append(np.mean(errs_3))
        spot_err_k5.append(np.mean(errs_5))

    x = np.arange(len(spots))
    width = 0.25

    plt.figure(figsize=(10, 5))
    plt.bar(x - width, spot_err_k1, width, label="k = 1", color="#42A5F5")
    plt.bar(x, spot_err_k3, width, label="k = 3", color="#AB47BC")
    plt.bar(x + width, spot_err_k5, width, label="k = 5", color="#66BB6A")
    plt.axhline(y=2.0, color="black", linestyle="--", alpha=0.6, label="2.0 m Grid")
    plt.xticks(x, spots, fontweight="bold")
    plt.ylabel("Mean Metric Error (Metres)", fontsize=11)
    plt.title("Per-Spot Localization Error for k ∈ {1, 3, 5} (100 Trials)", fontsize=13, fontweight="bold")
    plt.ylim(0, 6.5)
    plt.grid(axis="y", linestyle="--", alpha=0.7)
    plt.legend(fontsize=10)
    plt.tight_layout()
    p4 = os.path.join(PLOTS_DIR, "per_spot_error_k1_k3_k5.png")
    plt.savefig(p4, dpi=300)
    plt.close()
    print(f"📈 Saved: {p4}")

    print("\n" + "=" * 70)
    print("📋 SUMMARY TABLE: COMBINED 100 TRIALS (DAY 01 + DAY 02)")
    print("=" * 70)
    print(f"{'k':<4}{'k-NN Acc':<12}{'Mean Err (m)':<16}{'Median Err':<12}{'WKNN Acc':<12}{'WKNN Mean Err'}")
    print("-" * 65)
    for k in [1, 2, 3, 4, 5, 7, 9]:
        r = comb_summary[k]
        print(f"{k:<4}{r['knn_acc']:<12.1f}{r['knn_mean_err']:<5.2f} ± {r['knn_std_err']:<8.2f}{r['knn_median_err']:<12.2f}{r['wknn_acc']:<12.1f}{r['wknn_mean_err']:<5.2f} ± {r['wknn_std_err']:<8.2f}")
    print("=" * 70)

if __name__ == "__main__":
    main()
