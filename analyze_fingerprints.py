#!/usr/bin/env python3
"""
Wi-Fi Signal Distribution & Statistical Analysis Script
======================================================
Reads `wifi_fingerprints.csv` from the mobile app, computes:
  - Mean (avg), Median, Standard Deviation (sigma)
  - 1st, 2nd, and 3rd Standard Deviation bands (68%, 95%, 99.7%)
Plots frequency distributions and Gaussian fits for each AP per spot in one consolidated image.
"""

import os
import re
import pandas as pd
import numpy as np
import matplotlib.pyplot as plt

CSV_PATH = "wifi_fingerprints.csv"
OUTPUT_DIR = "distribution_plots"

def extract_samples_from_row(row):
    """Robustly extracts all raw integer samples from whichever column contains the bracketed array."""
    best_samples = []
    for col in ["raw_samples", "filtered_samples", "samples"]:
        if col in row and isinstance(row[col], str) and "[" in row[col]:
            clean_str = row[col].strip('"[] ')
            nums = [int(p) for p in re.split(r'[;, ]+', clean_str) if p.lstrip('-+').isdigit()]
            if len(nums) > len(best_samples):
                best_samples = nums
    if not best_samples and "avg_rssi" in row and pd.notna(row["avg_rssi"]):
        best_samples = [int(row["avg_rssi"])]
    return best_samples

def main():
    if not os.path.exists(CSV_PATH):
        print(f"❌ Error: '{CSV_PATH}' not found in current directory.")
        print("💡 Pull it from your phone first: adb pull /sdcard/Android/data/com.example.wififingerprint/files/wifi_fingerprints.csv .")
        return

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    df = pd.read_csv(CSV_PATH)
    print(f"📂 Loaded '{CSV_PATH}' with {len(df)} total records.\n")

    df["parsed_samples"] = df.apply(extract_samples_from_row, axis=1)

    # Filter: Only consider records where iteration/sample count > 30
    initial_count = len(df)
    df = df[df["parsed_samples"].apply(lambda s: len(s) > 30)].copy()
    filtered_count = len(df)
    print(f"🔍 Filtered for records with > 30 iterations: {filtered_count} valid records kept (discarded {initial_count - filtered_count} older/low-sample records).\n")

    # Clean existing plot images to ensure only current valid spots exist
    for f in os.listdir(OUTPUT_DIR):
        if f.endswith(".png"):
            os.remove(os.path.join(OUTPUT_DIR, f))

    spots = df["spot_label"].unique()

    for spot in spots:
        spot_df = df[df["spot_label"] == spot]
        ap_count = len(spot_df)
        if ap_count == 0:
            continue

        print(f"==================================================")
        print(f"📍 Spot: '{spot}' ({ap_count} APs detected)")
        print(f"==================================================")

        # Create subplots for all APs under this spot
        cols = 2 if ap_count > 1 else 1
        rows = (ap_count + cols - 1) // cols
        fig, axes = plt.subplots(rows, cols, figsize=(7 * cols, 4 * rows), squeeze=False)
        fig.suptitle(f"Wi-Fi Signal Distribution — Spot: '{spot}'", fontsize=16, fontweight="bold")

        for idx, (_, row) in enumerate(spot_df.iterrows()):
            ax = axes[idx // cols][idx % cols]
            samples = np.array(row["parsed_samples"])
            ssid = row.get("ssid", "Unknown")
            bssid = row.get("bssid", "Unknown")

            if len(samples) < 2:
                ax.text(0.5, 0.5, f"Not enough samples ({len(samples)})", ha='center', va='center')
                ax.set_title(f"{ssid} ({bssid})")
                continue

            # Statistical calculations
            mean_val = np.mean(samples)
            median_val = np.median(samples)
            std_val = np.std(samples, ddof=1) if len(samples) > 1 else 0.0

            # 1st, 2nd, and 3rd Standard Deviations
            sd1_low, sd1_high = mean_val - std_val, mean_val + std_val
            sd2_low, sd2_high = mean_val - 2 * std_val, mean_val + 2 * std_val
            sd3_low, sd3_high = mean_val - 3 * std_val, mean_val + 3 * std_val

            print(f"  • {ssid} ({bssid}) [N = {len(samples)}]:")
            print(f"      Mean: {mean_val:.2f} dBm | Median: {median_val:.1f} dBm | Std Dev (σ): {std_val:.2f} dBm")
            print(f"      ±1σ (68.2%): [{sd1_low:.2f}, {sd1_high:.2f}] dBm")
            print(f"      ±2σ (95.4%): [{sd2_low:.2f}, {sd2_high:.2f}] dBm")
            print(f"      ±3σ (99.7%): [{sd3_low:.2f}, {sd3_high:.2f}] dBm")

            # 1. Frequency Distribution Histogram
            bins = np.arange(min(samples) - 1.5, max(samples) + 2.5, 1)
            counts, _, _ = ax.hist(samples, bins=bins, density=True, alpha=0.55, color="#1976D2", edgecolor="black", label="Frequency Density")

            # 2. Gaussian Fit Curve
            if std_val > 0:
                x_axis = np.linspace(min(samples) - 4, max(samples) + 4, 200)
                pdf_curve = np.exp(-0.5 * ((x_axis - mean_val) / std_val) ** 2) / (std_val * np.sqrt(2 * np.pi))
                ax.plot(x_axis, pdf_curve, color="#0D47A1", lw=2, label="Gaussian Fit")

                # Shaded standard deviation bands
                ax.axvspan(sd1_low, sd1_high, alpha=0.15, color="green", label="±1σ (68%)")
                ax.axvspan(sd2_low, sd2_high, alpha=0.08, color="orange", label="±2σ (95%)")

            # 3. Reference lines
            ax.axvline(mean_val, color="red", linestyle="--", lw=2, label=f"Mean: {mean_val:.1f}")
            ax.axvline(median_val, color="darkgreen", linestyle=":", lw=2, label=f"Median: {median_val:.1f}")

            ax.set_title(f"{ssid}\n[{bssid}] (N={len(samples)}, σ={std_val:.2f})", fontsize=11)
            ax.set_xlabel("RSSI (dBm)", fontsize=10)
            ax.set_ylabel("Density / Frequency", fontsize=10)
            ax.legend(fontsize=8, loc="upper right")
            ax.grid(True, linestyle="--", alpha=0.5)

        # Hide any unused subplots in the grid
        for idx in range(ap_count, rows * cols):
            axes[idx // cols][idx % cols].axis("off")

        plt.tight_layout()
        output_file = os.path.join(OUTPUT_DIR, f"{spot.replace(' ', '_')}_distribution.png")
        plt.savefig(output_file, dpi=200)
        plt.close()
        print(f"  🖼️ Distribution plot saved to: {output_file}\n")

    print(f"✅ All distributions and statistics generated in '{OUTPUT_DIR}/'.")

if __name__ == "__main__":
    main()
