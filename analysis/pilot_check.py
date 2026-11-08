"""
pilot_check.py - Kiểm tra chất lượng phép đo từ lần chạy thử (Bước 7).

Chạy:  python analysis/pilot_check.py <thư_mục_pilot> [<thư_mục_affinity>]
Ví dụ: python analysis/pilot_check.py results/raw/20261108_0403_pilot results/raw/affinity

Kết quả: in bảng K1-K5 ra màn hình + lưu hình vào analysis/figures/pilot_*.png

  K1  A/A test        : cutoff 0 và cutoff 1 là CÙNG thuật toán -> chênh lệch = mức nhiễu nền
  K2  Độ nhiễu        : hệ số biến thiên (CV = std/mean) giữa các trial của cùng một cấu hình
  K3  Warm-up / trôi  : thời gian có giảm dần theo trial, hay tăng/giảm theo order_idx không?
  K4  GC              : trong *.gc.log có lần GC nào KHÔNG do System.gc() gọi chủ động không?
  K5  Loại lõi        : so sánh AFF_LO (CPU 0-7) với AFF_HI (CPU 8-15)
"""
import sys
import re
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")                     # vẽ ra file, không cần cửa sổ
import matplotlib.pyplot as plt


def load_csvs(folder: Path) -> pd.DataFrame:
    files = sorted(folder.glob("*.csv"))
    if not files:
        sys.exit(f"Không tìm thấy file .csv trong {folder}")
    df = pd.concat([pd.read_csv(f) for f in files], ignore_index=True)
    # "case" = (phân phối, n). PHẢI gom nhóm theo case chứ không chỉ theo n:
    # TN1 và TN2 đều có n = 100000 nhưng là các phân phối khác nhau.
    df["case"] = df["dist"] + "_n" + df["n"].astype(str)
    return df


def k1_aa_test(df):
    """So median của cutoff 0 và 1 cho từng (version, n). Trả về % chênh lệch."""
    med = df.groupby(["version", "case", "cutoff"])["ns_per_sort"].median()
    rows = []
    for (v, n), _ in df.groupby(["version", "case"]):
        t0, t1 = med[(v, n, 0)], med[(v, n, 1)]
        rows.append({"version": v, "case": n, "median_c0_us": t0 / 1e3, "median_c1_us": t1 / 1e3,
                     "diff_%": 100 * (t1 - t0) / t0})
    return pd.DataFrame(rows)


def k2_noise(df):
    """CV (%) của từng cấu hình qua các trial; tóm tắt theo (version, n)."""
    g = df.groupby(["version", "case", "cutoff"])["ns_per_sort"]
    cv = (100 * g.std(ddof=1) / g.mean()).rename("cv_%").reset_index()
    return cv.groupby(["version", "case"])["cv_%"].describe()[["mean", "50%", "max"]] \
             .rename(columns={"mean": "cv_mean_%", "50%": "cv_median_%", "max": "cv_max_%"})


def relative_to_config_median(df):
    """Chuẩn hoá: mỗi lần đo chia cho median của chính cấu hình đó -> so được giữa các cutoff."""
    med = df.groupby(["version", "case", "cutoff"])["ns_per_sort"].transform("median")
    return df.assign(rel=df["ns_per_sort"] / med)


def k3_drift(df, figdir):
    """Trung bình của 'rel' theo trial và theo order_idx; vẽ hình."""
    d = relative_to_config_median(df)
    by_trial = d.groupby(["case", "trial"])["rel"].mean().unstack(0)
    # Hệ số góc của rel theo order_idx (đơn vị: % thay đổi trên toàn bộ 82 vị trí)
    slopes = {}
    for n, g in d.groupby("case"):
        b = np.polyfit(g["order_idx"], g["rel"], 1)[0]
        slopes[n] = 100 * b * (g["order_idx"].max())
    fig, ax = plt.subplots(1, 2, figsize=(13, 4.5))
    for n in by_trial.columns:
        ax[0].plot(by_trial.index, by_trial[n], marker="o", label=n)
    ax[0].axhline(1, color="gray", lw=0.8)
    ax[0].set(title="K3a: thời gian tương đối theo trial", xlabel="trial", ylabel="t / median cấu hình")
    ax[0].legend(fontsize=7)
    for n, g in d.groupby("case"):
        m = g.groupby("order_idx")["rel"].mean()
        ax[1].plot(m.index, m.values, ".", alpha=0.6, label=n)
    ax[1].axhline(1, color="gray", lw=0.8)
    ax[1].set(title="K3b: thời gian tương đối theo order_idx", xlabel="order_idx", ylabel="t / median cấu hình")
    ax[1].legend(fontsize=7)
    fig.tight_layout()
    fig.savefig(figdir / "pilot_K3_drift.png", dpi=130)
    plt.close(fig)
    return by_trial, slopes


def k4_gc(folder):
    """Đếm số dòng 'Pause' trong log GC, tách riêng loại KHÔNG phải System.gc()."""
    rows = []
    for f in sorted(folder.glob("*.gc.log")):
        lines = [l for l in f.read_text().splitlines() if "Pause" in l]
        other = [l for l in lines if "System.gc()" not in l]
        pauses = [float(m.group(1)) for l in lines if (m := re.search(r"([\d.]+)ms\s*$", l))]
        rows.append({"file": f.name, "pauses": len(lines), "non_System.gc": len(other),
                     "pause_median_ms": np.median(pauses) if pauses else np.nan})
    return pd.DataFrame(rows)


def k5_affinity(folder):
    files = {p.stem: p for p in folder.glob("AFF_*.csv")}
    if not {"AFF_LO", "AFF_HI"} <= files.keys():
        return None
    lo, hi = pd.read_csv(files["AFF_LO"]), pd.read_csv(files["AFF_HI"])
    rows = []
    for name, d in (("AFF_LO (CPU 0-7)", lo), ("AFF_HI (CPU 8-15)", hi)):
        g = d.groupby(["version", "cutoff"])["ns_per_sort"]
        cv = (100 * g.std(ddof=1) / g.mean()).median()
        for v, dv in d.groupby("version"):
            rows.append({"group": name, "version": v, "k": int(d["k"].iloc[0]),
                         "median_ms": dv["ns_per_sort"].median() / 1e6, "cv_median_%": cv})
    return pd.DataFrame(rows)


def curve_preview(df, figdir):
    """Xem trước đường cong median theo cutoff (CHƯA phải kết quả: chỉ 5 trial)."""
    med = df.groupby(["case", "version", "cutoff"])["ns_per_sort"].median().reset_index()
    ns = sorted(med["case"].unique())
    trials = df["trial"].nunique()
    cols = min(len(ns), 4)
    rows_ = int(np.ceil(len(ns) / cols))
    fig, axes = plt.subplots(rows_, cols, figsize=(4.5 * cols, 3.6 * rows_), squeeze=False)
    for ax in axes.flat[len(ns):]:
        ax.axis("off")
    for ax, n in zip(axes.flat, ns):
        for v, g in med[med["case"] == n].groupby("version"):
            base = g.loc[g["cutoff"] == 0, "ns_per_sort"].iloc[0]
            ax.plot(g["cutoff"], g["ns_per_sort"] / base, marker=".", label=v)
        ax.axhline(1, color="gray", lw=0.8)
        ax.set(title=f"{n} ({trials} trial)", xlabel="CUTOFF", ylabel="t / t(cutoff=0)")
        ax.legend()
    fig.tight_layout()
    fig.savefig(figdir / "pilot_curve_preview.png", dpi=130)
    plt.close(fig)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    pilot = Path(sys.argv[1])
    figdir = Path("analysis/figures")
    figdir.mkdir(parents=True, exist_ok=True)
    df = load_csvs(pilot)
    pd.set_option("display.width", 140)
    pd.set_option("display.float_format", lambda x: f"{x:,.2f}")

    print("\n=== K1. A/A test: cutoff 0 vs cutoff 1 (cùng một thuật toán) ===")
    print(k1_aa_test(df).to_string(index=False))

    print("\n=== K2. Độ nhiễu: CV giữa các trial của cùng cấu hình ===")
    print(k2_noise(df))

    print("\n=== K3. Warm-up và trôi theo thứ tự chạy ===")
    by_trial, slopes = k3_drift(df, figdir)
    print("Thời gian tương đối trung bình theo trial (1.00 = median của cấu hình):")
    print(by_trial)
    print("Thay đổi ước lượng từ order_idx 0 đến 81 (%):", {k: round(v, 2) for k, v in slopes.items()})

    print("\n=== K4. GC ===")
    print(k4_gc(pilot).to_string(index=False))

    if len(sys.argv) > 2:
        print("\n=== K5. Ghim nhóm lõi (n = 100 000) ===")
        r = k5_affinity(Path(sys.argv[2]))
        print(r.to_string(index=False) if r is not None else "thiếu AFF_LO.csv / AFF_HI.csv")
        base = df[(df["n"] == 100_000) & (df["dist"] == "RANDOM")].groupby("version")["ns_per_sort"].median() / 1e6
        print("Không ghim (pilot, n=100000), median ms:", base.round(2).to_dict())

    curve_preview(df, figdir)
    print(f"\nĐã lưu hình vào {figdir}/pilot_K3_drift.png và pilot_curve_preview.png")


if __name__ == "__main__":
    main()