#!/usr/bin/env python3
"""
Cross-Device Hardware Transfer & Calibration Analysis Script
============================================================
Evaluates indoor Wi-Fi positioning transfer across heterogeneous smartphone hardware:
  - Phone A (Survey & Baseline Device): Samsung Galaxy A20e (SM-A202F, Android 13)
  - Phone B (Testbed Target Device): OPPO A16 (CPH2273, Android 12)

Compares 3 experimental conditions across 8 reference spots (S1 - S8):
  1. Phone A Baseline: Same-device positioning (Samsung Map -> Samsung Test)
  2. Phone B Uncalibrated: Direct cross-device transfer without calibration (Samsung Map -> OPPO Test)
  3. Phone B Calibrated: Cross-device transfer with one-spot antenna offset calibration

Outputs:
  - readings/cross_device/cross_device_summary.csv
  - readings/cross_device/plots/1_cross_device_accuracy_comparison.png
  - readings/cross_device/plots/2_cross_device_distance_error.png
  - readings/cross_device/plots/3_cross_device_cdf_comparison.png
  - readings/cross_device/plots/4_per_spot_error_breakdown.png
  - readings/cross_device/plots/5_hardware_rssi_offset_by_ap.png
"""

import os
import sys
import csv
import math
from collections import defaultdict
import numpy as np
import matplotlib.pyplot as plt

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
MAP_FILE = os.path.join(SCRIPT_DIR, "map.csv")
A_BASELINE_FILE = os.path.join(SCRIPT_DIR, "a_baseline.csv")
B_UNCAL_FILE = os.path.join(SCRIPT_DIR, "b_uncalibrated.csv")
B_CAL_FILE = os.path.join(SCRIPT_DIR, "b_calibrated.csv")
PLOTS_DIR = os.path.join(SCRIPT_DIR, "plots")
SUMMARY_CSV = os.path.join(SCRIPT_DIR, "cross_device_summary.csv")


def parse_signals(sig_str):
    res = {}
    if not sig_str:
        return res
    for pair in sig_str.split(";"):
        if "=" in pair:
            b, v = pair.split("=")
            res[b.strip()] = int(v.strip())
    return res


def load_dataset(filepath, name):
    if not os.path.exists(filepath):
        print(f"❌ Error: Missing file {filepath}")
        return None

    with open(filepath, "r", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))

    knn_c = [int(r["knn_correct"]) for r in rows]
    fuzzy_c = [int(r["fuzzy_correct"]) for r in rows]
    knn_err = [float(r["knn_error_m"]) for r in rows]
    fuzzy_err = [float(r["fuzzy_error_m"]) for r in rows]

    per_spot = defaultdict(lambda: {
        "knn_c": [],
        "fuzzy_c": [],
        "knn_err": [],
        "fuzzy_err": [],
        "knn_preds": [],
        "fuzzy_preds": [],
        "signals": []
    })

    for r in rows:
        s = r["actual_spot"].strip()
        per_spot[s]["knn_c"].append(int(r["knn_correct"]))
        per_spot[s]["fuzzy_c"].append(int(r["fuzzy_correct"]))
        per_spot[s]["knn_err"].append(float(r["knn_error_m"]))
        per_spot[s]["fuzzy_err"].append(float(r["fuzzy_error_m"]))
        per_spot[s]["knn_preds"].append(r["knn_pred_spot"].strip())
        per_spot[s]["fuzzy_preds"].append(r["fuzzy_pred_spot"].strip())
        per_spot[s]["signals"].append(parse_signals(r["raw_signals"]))

    return {
        "name": name,
        "trials": len(rows),
        "knn_acc": float(np.mean(knn_c) * 100),
        "fuzzy_acc": float(np.mean(fuzzy_c) * 100),
        "knn_mean_err": float(np.mean(knn_err)),
        "knn_std_err": float(np.std(knn_err)),
        "knn_median_err": float(np.median(knn_err)),
        "knn_75_err": float(np.percentile(knn_err, 75)),
        "knn_90_err": float(np.percentile(knn_err, 90)),
        "knn_max_err": float(np.max(knn_err)),
        "fuzzy_mean_err": float(np.mean(fuzzy_err)),
        "fuzzy_std_err": float(np.std(fuzzy_err)),
        "fuzzy_median_err": float(np.median(fuzzy_err)),
        "fuzzy_90_err": float(np.percentile(fuzzy_err, 90)),
        "fuzzy_max_err": float(np.max(fuzzy_err)),
        "raw_knn_errs": knn_err,
        "raw_fuzzy_errs": fuzzy_err,
        "per_spot": per_spot,
        "rows": rows
    }


def main():
    print("=" * 80)
    print("🔬 CROSS-DEVICE LOCALIZATION & ANTENNA OFFSET CALIBRATION ANALYSIS")
    print("=" * 80)

    ds_a = load_dataset(A_BASELINE_FILE, "Phone A Baseline (Samsung)")
    ds_b_uncal = load_dataset(B_UNCAL_FILE, "Phone B Uncalibrated (OPPO)")
    ds_b_cal = load_dataset(B_CAL_FILE, "Phone B Calibrated (OPPO + Offset)")

    if not ds_a or not ds_b_uncal or not ds_b_cal:
        print("❌ Error loading one or more cross-device datasets.")
        sys.exit(1)

    datasets = [ds_a, ds_b_uncal, ds_b_cal]
    spots = [f"S{i}" for i in range(1, 9)]

    # --- 1. Terminal Report ---
    print("\n### 1. Overall Performance Comparison Across Conditions")
    print(f"{'Condition':<32}{'Trials':<8}{'k-NN Acc':<12}{'Mean Err (m)':<16}{'Median Err':<12}{'90th Pct':<10}{'Fuzzy Acc'}")
    print("-" * 100)
    for d in datasets:
        print(f"{d['name']:<32}{d['trials']:<8}{d['knn_acc']:<12.1f}{d['knn_mean_err']:<5.2f} ± {d['knn_std_err']:<8.2f}{d['knn_median_err']:<12.2f}{d['knn_90_err']:<10.2f}{d['fuzzy_acc']:<10.1f}")

    print("\n### 2. Per-Spot Accuracy & Distance Error Breakdown")
    print(f"{'Spot':<6}{'Phone A Acc':<14}{'Phone A Err':<14}{'Phone B Uncal Acc':<20}{'Phone B Uncal Err':<20}{'Phone B Cal Acc':<18}{'Phone B Cal Err'}")
    print("-" * 100)
    for s in spots:
        a_acc = np.mean(ds_a["per_spot"][s]["knn_c"]) * 100 if s in ds_a["per_spot"] else 0.0
        a_err = np.mean(ds_a["per_spot"][s]["knn_err"]) if s in ds_a["per_spot"] else 0.0
        bu_acc = np.mean(ds_b_uncal["per_spot"][s]["knn_c"]) * 100 if s in ds_b_uncal["per_spot"] else 0.0
        bu_err = np.mean(ds_b_uncal["per_spot"][s]["knn_err"]) if s in ds_b_uncal["per_spot"] else 0.0
        bc_acc = np.mean(ds_b_cal["per_spot"][s]["knn_c"]) * 100 if s in ds_b_cal["per_spot"] else 0.0
        bc_err = np.mean(ds_b_cal["per_spot"][s]["knn_err"]) if s in ds_b_cal["per_spot"] else 0.0
        print(f"{s:<6}{a_acc:<14.1f}{a_err:<14.2f}{bu_acc:<20.1f}{bu_err:<20.2f}{bc_acc:<18.1f}{bc_err:<.2f}")

    # --- 3. Compute Hardware RSSI Offsets ---
    diffs_by_ap = defaultdict(list)
    ap_names = {
        "anon_e7b30a6479a2": "Galaxy A01",
        "anon_bc94dc8280b1": "G6_6044",
        "anon_5ad1640ec6bb": "Dialog 4G 592",
        "anon_44ac57c371fc": "Lab AP 44ac",
        "anon_d8564ab3671a": "Edge AP d856"
    }

    for s in spots:
        a_ap_means = defaultdict(list)
        for sig in ds_a["per_spot"][s]["signals"]:
            for b, v in sig.items():
                a_ap_means[b].append(v)

        b_ap_means = defaultdict(list)
        for sig in ds_b_uncal["per_spot"][s]["signals"]:
            for b, v in sig.items():
                b_ap_means[b].append(v)

        for b in a_ap_means:
            if b in b_ap_means:
                m_a = np.mean(a_ap_means[b])
                m_b = np.mean(b_ap_means[b])
                diffs_by_ap[b].append(m_b - m_a)

    print("\n### 3. Hardware Antenna RSSI Offsets (OPPO A16 minus Samsung A20e)")
    print("-" * 75)
    all_diffs = []
    ap_summary = {}
    for b, d_list in sorted(diffs_by_ap.items()):
        label = ap_names.get(b, b[-8:])
        m_diff = float(np.mean(d_list))
        s_diff = float(np.std(d_list))
        all_diffs.extend(d_list)
        ap_summary[b] = (label, m_diff, s_diff)
        print(f"  • {label:<16} ({b}): Mean Offset = {m_diff:+.2f} dB (Std = {s_diff:.2f} dB, range: {np.min(d_list):+.1f} to {np.max(d_list):+.1f} dB)")

    overall_offset = float(np.mean(all_diffs))
    overall_median = float(np.median(all_diffs))
    print(f"\n>> Overall Measured Antenna Sensitivity Offset: {overall_offset:+.2f} dB (Median: {overall_median:+.2f} dB)")
    print("=" * 80)

    # --- 4. Export Summary CSV ---
    os.makedirs(os.path.dirname(SUMMARY_CSV), exist_ok=True)
    with open(SUMMARY_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow([
            "condition", "trials", "knn_acc_pct", "knn_mean_err_m", "knn_std_err_m", "knn_median_err_m",
            "knn_90_err_m", "knn_max_err_m", "fuzzy_acc_pct", "fuzzy_mean_err_m", "fuzzy_median_err_m"
        ])
        for d in datasets:
            writer.writerow([
                d["name"], d["trials"],
                f"{d['knn_acc']:.1f}", f"{d['knn_mean_err']:.3f}", f"{d['knn_std_err']:.3f}", f"{d['knn_median_err']:.3f}",
                f"{d['knn_90_err']:.3f}", f"{d['knn_max_err']:.3f}",
                f"{d['fuzzy_acc']:.1f}", f"{d['fuzzy_mean_err']:.3f}", f"{d['fuzzy_median_err']:.3f}"
            ])
    print(f"💾 Saved summary CSV: {SUMMARY_CSV}")

    # --- 5. Generate Publication Plots ---
    os.makedirs(PLOTS_DIR, exist_ok=True)
    cond_labels = ["Phone A Baseline\n(Samsung A20e)", "Phone B Uncalibrated\n(OPPO A16)", "Phone B Calibrated\n(OPPO + Offset)"]
    cond_colors = ["#1976D2", "#D32F2F", "#388E3C"]

    # Plot 1: Accuracy Comparison
    plt.figure(figsize=(8.5, 5))
    x = np.arange(len(cond_labels))
    width = 0.35
    k_accs = [d["knn_acc"] for d in datasets]
    f_accs = [d["fuzzy_acc"] for d in datasets]
    rects1 = plt.bar(x - width/2, k_accs, width, label="k-NN Accuracy (%)", color="#1976D2")
    rects2 = plt.bar(x + width/2, f_accs, width, label="Fuzzy Matrix Accuracy (%)", color="#FFA000")

    for rect in rects1:
        h = rect.get_height()
        plt.text(rect.get_x() + rect.get_width()/2., h + 1.0, f"{h:.1f}%", ha="center", va="bottom", fontweight="bold")
    for rect in rects2:
        h = rect.get_height()
        plt.text(rect.get_x() + rect.get_width()/2., h + 1.0, f"{h:.1f}%", ha="center", va="bottom", fontweight="bold")

    plt.xticks(x, cond_labels, fontsize=10, fontweight="bold")
    plt.ylabel("Classification Accuracy (%)", fontsize=11)
    plt.title("Cross-Device Transfer Impact: Classification Accuracy", fontsize=13, fontweight="bold")
    plt.ylim(0, 60)
    plt.grid(axis="y", linestyle="--", alpha=0.7)
    plt.legend(fontsize=10)
    plt.tight_layout()
    p1 = os.path.join(PLOTS_DIR, "1_cross_device_accuracy_comparison.png")
    plt.savefig(p1, dpi=300)
    plt.close()
    print(f"📈 Saved: {p1}")

    # Plot 2: Distance Error Comparison
    plt.figure(figsize=(8.5, 5))
    mean_errs = [d["knn_mean_err"] for d in datasets]
    med_errs = [d["knn_median_err"] for d in datasets]
    rects1 = plt.bar(x - width/2, mean_errs, width, label="k-NN Mean Error (m)", color="#0288D1")
    rects2 = plt.bar(x + width/2, med_errs, width, label="k-NN Median Error (m)", color="#7B1FA2")

    for rect in rects1:
        h = rect.get_height()
        plt.text(rect.get_x() + rect.get_width()/2., h + 0.08, f"{h:.2f}m", ha="center", va="bottom", fontweight="bold")
    for rect in rects2:
        h = rect.get_height()
        plt.text(rect.get_x() + rect.get_width()/2., h + 0.08, f"{h:.2f}m", ha="center", va="bottom", fontweight="bold")

    plt.axhline(y=2.0, color="gray", linestyle="--", alpha=0.7, label="2.0m Baseline Grid")
    plt.xticks(x, cond_labels, fontsize=10, fontweight="bold")
    plt.ylabel("Distance Error in Metres", fontsize=11)
    plt.title("Cross-Device Transfer: Physical Metric Distance Error", fontsize=13, fontweight="bold")
    plt.ylim(0, 4.0)
    plt.grid(axis="y", linestyle="--", alpha=0.7)
    plt.legend(fontsize=10)
    plt.tight_layout()
    p2 = os.path.join(PLOTS_DIR, "2_cross_device_distance_error.png")
    plt.savefig(p2, dpi=300)
    plt.close()
    print(f"📈 Saved: {p2}")

    # Plot 3: Error CDF Overlay
    plt.figure(figsize=(9, 5.5))
    for d, col, ls in zip(datasets, cond_colors, ["-", "--", "-."]):
        errs = np.sort(d["raw_knn_errs"])
        cdf = np.arange(1, len(errs) + 1) / len(errs)
        plt.plot(errs, cdf, label=f"{d['name']} (Mean: {d['knn_mean_err']:.2f}m)", color=col, linestyle=ls, linewidth=2.3)

    plt.axvline(x=2.0, color="black", linestyle=":", alpha=0.7, label="1 Spot Spacing (2.0m)")
    plt.axhline(y=0.5, color="red", linestyle=":", alpha=0.5, label="50% (Median)")
    plt.title("Cumulative Distribution Function (CDF) Across Cross-Device Conditions", fontsize=13, fontweight="bold")
    plt.xlabel("Localization Distance Error (Metres)", fontsize=11)
    plt.ylabel("Cumulative Probability (CDF)", fontsize=11)
    plt.xlim(0, 10.5)
    plt.ylim(0, 1.05)
    plt.grid(True, linestyle="--", alpha=0.6)
    plt.legend(fontsize=9, loc="lower right")
    plt.tight_layout()
    p3 = os.path.join(PLOTS_DIR, "3_cross_device_cdf_comparison.png")
    plt.savefig(p3, dpi=300)
    plt.close()
    print(f"📈 Saved: {p3}")

    # Plot 4: Per-Spot Error Comparison (S1 - S8)
    plt.figure(figsize=(10, 5))
    x_s = np.arange(len(spots))
    w_s = 0.26
    err_a = [np.mean(ds_a["per_spot"][s]["knn_err"]) for s in spots]
    err_bu = [np.mean(ds_b_uncal["per_spot"][s]["knn_err"]) for s in spots]
    err_bc = [np.mean(ds_b_cal["per_spot"][s]["knn_err"]) for s in spots]

    plt.bar(x_s - w_s, err_a, w_s, label="Phone A Baseline", color="#1976D2")
    plt.bar(x_s, err_bu, w_s, label="Phone B Uncalibrated", color="#D32F2F")
    plt.bar(x_s + w_s, err_bc, w_s, label="Phone B Calibrated", color="#388E3C")

    plt.axhline(y=2.0, color="black", linestyle="--", alpha=0.6, label="2.0m Grid")
    plt.xticks(x_s, spots, fontweight="bold")
    plt.ylabel("Mean Metric Error (Metres)", fontsize=11)
    plt.title("Per-Spot Metric Error Across Cross-Device Conditions (S1 - S8)", fontsize=13, fontweight="bold")
    plt.ylim(0, 9.5)
    plt.grid(axis="y", linestyle="--", alpha=0.7)
    plt.legend(fontsize=10)
    plt.tight_layout()
    p4 = os.path.join(PLOTS_DIR, "4_per_spot_error_breakdown.png")
    plt.savefig(p4, dpi=300)
    plt.close()
    print(f"📈 Saved: {p4}")

    # Plot 5: Hardware RSSI Offset by AP
    plt.figure(figsize=(9, 5))
    ap_labels = [info[0] for info in ap_summary.values()]
    ap_offsets = [info[1] for info in ap_summary.values()]
    ap_stds = [info[2] for info in ap_summary.values()]
    bars = plt.bar(ap_labels, ap_offsets, yerr=ap_stds, capsize=5, color=["#1976D2" if o >= 0 else "#D32F2F" for o in ap_offsets], width=0.45)

    for bar in bars:
        h = bar.get_height()
        va = "bottom" if h >= 0 else "top"
        plt.text(bar.get_x() + bar.get_width()/2., h + (0.3 if h >= 0 else -0.8), f"{h:+.2f} dB", ha="center", va=va, fontweight="bold")

    plt.axhline(y=0.0, color="black", linewidth=1.0)
    plt.axhline(y=overall_offset, color="purple", linestyle="--", label=f"Overall Offset ({overall_offset:+.2f} dB)")
    plt.ylabel("Hardware RSSI Offset (dB)", fontsize=11)
    plt.title("Hardware Antenna Sensitivity Offsets per Access Point (OPPO vs Samsung)", fontsize=13, fontweight="bold")
    plt.ylim(-8.5, 6.0)
    plt.grid(axis="y", linestyle="--", alpha=0.7)
    plt.legend(fontsize=10)
    plt.tight_layout()
    p5 = os.path.join(PLOTS_DIR, "5_hardware_rssi_offset_by_ap.png")
    plt.savefig(p5, dpi=300)
    plt.close()
    print(f"📈 Saved: {p5}")

    print("\n✅ All cross-device plots and summary tables generated successfully!")


if __name__ == "__main__":
    main()

