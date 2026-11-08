"""
analyze.py - Phân tích kết quả thí nghiệm CUTOFF (Bước 9).

Chạy:  python analysis/analyze.py results/raw/<thư_mục_chạy_thật> [results/raw/TN3 ...]
       (có thể truyền nhiều thư mục; mọi file .csv trong đó được gộp lại)
Cần:   pandas, numpy, matplotlib, scipy   (pip install pandas numpy matplotlib scipy)

QUY TẮC RA QUYẾT ĐỊNH (ghi trước khi nhìn kết quả - xem LAB_NOTEBOOK.md):
  R1  Thước đo chính: tỉ số ghép cặp  r = t(cutoff) / t_base  tính TRONG CÙNG MỘT TRIAL,
      với t_base = trung bình của cutoff 0 và cutoff 1 (hai cấu hình là cùng một thuật toán).
      -> loại bỏ dao động giữa các trial (vd. máy chậm hơn ở vài trial đầu của n = 10^6).
  R2  L* = cutoff có MEDIAN của r nhỏ nhất (theo từng phân phối, n, phiên bản).
  R3  Vùng gần tối ưu = các cutoff có median(r) <= median(r tại L*) * (1 + X), X = NEAR_PCT.
  R4  "7 hay 10?": kiểm định Wilcoxon ghép cặp trên 30 trial, alpha = 0.05,
      hiệu chỉnh Holm trong mỗi họ 3 so sánh {7 vs 10, L* vs 7, L* vs 10}.

Kết quả:
  analysis/out/summary.csv       thống kê từng (dist, n, version, cutoff)
  analysis/out/lstar.csv         L*, vùng gần tối ưu, mức tăng tốc
  analysis/out/tests.csv         kiểm định Wilcoxon
  analysis/out/leaf_table.csv    các độ dài lá có thể có của từng n (hiệu ứng bậc thang)
  analysis/out/loglog_fit.csv    hệ số mũ b của T(N) ~ a N^b (đồ thị log-log, slide W2)
  analysis/out/report.txt        tóm tắt bằng chữ + đối chiếu giả thuyết H1-H5
  analysis/figures/fig1_tn1_curves.png ... fig5_loglog.png
"""
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

try:
    from scipy.stats import wilcoxon
except ImportError:
    sys.exit("Thiếu scipy: chạy  pip install scipy")

NEAR_PCT = 0.01          # R3: X = 1% (mức nhiễu nền từ A/A test phần lớn < 1%)
ALPHA = 0.05             # R4
N_BOOT = 2000            # số lần bootstrap cho khoảng tin cậy của median
RNG = np.random.default_rng(20261108)

# Màu: palette tham chiếu đã kiểm định (V1 xanh, V2 cam) + nét liền / nét đứt làm mã hoá phụ
STYLE = {"V1": dict(color="#2a78d6", ls="-", marker="o"),
         "V2": dict(color="#eb6834", ls="--", marker="s")}
INK, INK2, GRID = "#0b0b0b", "#52514e", "#e4e3df"
plt.rcParams.update({
    "axes.edgecolor": INK2, "axes.labelcolor": INK, "xtick.color": INK2, "ytick.color": INK2,
    "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6,
    "axes.spines.top": False, "axes.spines.right": False, "font.size": 9,
})


# ----------------------------------------------------------------------------------------
# 1. Đọc dữ liệu và tính tỉ số ghép cặp
# ----------------------------------------------------------------------------------------
def load(folders) -> pd.DataFrame:
    files = sorted(f for folder in folders for f in Path(folder).glob("*.csv"))
    if not files:
        sys.exit(f"Không có file .csv trong {folders}")
    df = pd.concat([pd.read_csv(f) for f in files], ignore_index=True)
    key = ["exp", "dist", "n", "version", "trial"]
    base = (df[df["cutoff"].isin([0, 1])]
            .groupby(key)["ns_per_sort"].mean().rename("t_base"))
    df = df.join(base, on=key)
    df["r"] = df["ns_per_sort"] / df["t_base"]
    return df


# ----------------------------------------------------------------------------------------
# 2. Hiệu ứng bậc thang: độ dài lá có thể có, và "lớp lá" của mỗi cutoff
# ----------------------------------------------------------------------------------------
def leaf_sizes(n: int, limit: int = 128) -> list:
    """Mọi độ dài mảng con <= limit xuất hiện khi chia đôi top-down (mid = lo + (hi-lo)/2)."""
    seen, frontier = set(), {n}
    while frontier:
        nxt = set()
        for m in frontier:
            if m <= limit:
                seen.add(m)
            if m > 1:
                nxt |= {m - m // 2, m // 2}
        frontier = nxt
    return sorted(seen)


def leaf_class(n: int, cutoff: int) -> str:
    """Tập độ dài lá thực tế khi dùng CUTOFF (định nghĩa slide: lá nếu độ dài <= CUTOFF).
    Hai cutoff cùng lớp lá => cùng một cây đệ quy => cùng một thuật toán."""
    L = max(cutoff, 1)
    memo = {}

    def go(m):
        if m in memo:
            return memo[m]
        res = frozenset([m]) if m <= L else go(m - m // 2) | go(m // 2)
        memo[m] = res
        return res
    return "{" + ",".join(map(str, sorted(go(n)))) + "}"


# ----------------------------------------------------------------------------------------
# 3. Thống kê
# ----------------------------------------------------------------------------------------
def boot_ci_median(x: np.ndarray):
    idx = RNG.integers(0, len(x), size=(N_BOOT, len(x)))
    meds = np.median(x[idx], axis=1)
    return np.percentile(meds, [2.5, 97.5])


def summarize(df: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for (exp, dist, n, v, c), g in df.groupby(["exp", "dist", "n", "version", "cutoff"]):
        t, r = g["ns_per_sort"].to_numpy(), g["r"].to_numpy()
        lo, hi = boot_ci_median(r)
        rows.append({"exp": exp, "dist": dist, "n": n, "version": v, "cutoff": c,
                     "trials": len(t),
                     "median_ns": np.median(t), "q1_ns": np.percentile(t, 25), "q3_ns": np.percentile(t, 75),
                     "mean_ns": t.mean(), "sd_ns": t.std(ddof=1), "cv_%": 100 * t.std(ddof=1) / t.mean(),
                     "median_r": np.median(r), "r_ci95_lo": lo, "r_ci95_hi": hi,
                     "speedup_%": 100 * (1 - np.median(r)),
                     "leaf_class": leaf_class(n, c)})
    return pd.DataFrame(rows)


def ranges(values) -> str:
    """[8, 9, 10, 14, 15, 44, 48] -> '8-10, 14-15, 44, 48' (lưới thưa sau 40 vẫn liệt kê riêng)."""
    values = sorted(values)
    out, start, prev = [], values[0], values[0]
    for v in values[1:]:
        if v == prev + 1:
            prev = v
            continue
        out.append(f"{start}-{prev}" if start != prev else f"{start}")
        start = prev = v
    out.append(f"{start}-{prev}" if start != prev else f"{start}")
    return ", ".join(out)


def find_lstar(summary: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for (exp, dist, n, v), g in summary.groupby(["exp", "dist", "n", "version"]):
        best = g.loc[g["median_r"].idxmin()]
        near = g[g["median_r"] <= best["median_r"] * (1 + NEAR_PCT)]
        at = g.set_index("cutoff")
        rows.append({"exp": exp, "dist": dist, "n": n, "version": v,
                     "L*": int(best["cutoff"]), "leaf_class_L*": best["leaf_class"],
                     "speedup_L*_%": best["speedup_%"],
                     "speedup_7_%": at.loc[7, "speedup_%"], "speedup_10_%": at.loc[10, "speedup_%"],
                     "near_optimal": ranges(near["cutoff"]),
                     "near_min": int(near["cutoff"].min()), "near_max": int(near["cutoff"].max()),
                     "max_cutoff_tested": int(g["cutoff"].max())})
    return pd.DataFrame(rows)


def holm(pvals):
    order = np.argsort(pvals)
    m, adj, running = len(pvals), np.empty(len(pvals)), 0.0
    for rank, i in enumerate(order):
        running = max(running, (m - rank) * pvals[i])
        adj[i] = min(1.0, running)
    return adj


def tests(df: pd.DataFrame, lstar: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for _, L in lstar.iterrows():
        g = df[(df["exp"] == L["exp"]) & (df["dist"] == L["dist"]) &
               (df["n"] == L["n"]) & (df["version"] == L["version"])]
        wide = g.pivot(index="trial", columns="cutoff", values="ns_per_sort")
        pairs = [(7, 10), (L["L*"], 7), (L["L*"], 10)]
        res = []
        for a, b in pairs:
            if a == b:
                res.append((a, b, np.nan, 1.0))
                continue
            d = wide[a] / wide[b] - 1                     # >0: a chậm hơn b
            p = wilcoxon(wide[a], wide[b]).pvalue
            res.append((a, b, 100 * np.median(d), p))
        adj = holm(np.array([r[3] for r in res]))
        for (a, b, med, p), pa in zip(res, adj):
            rows.append({"exp": L["exp"], "dist": L["dist"], "n": L["n"], "version": L["version"],
                         "compare": f"{a} vs {b}",
                         "same_leaf_class": leaf_class(int(L["n"]), int(a)) == leaf_class(int(L["n"]), int(b)),
                         "median_paired_diff_%": med,
                         "p_raw": p, "p_holm": pa,
                         "verdict": ("không khác biệt có ý nghĩa" if pa >= ALPHA else
                                     (f"{a} nhanh hơn" if med < 0 else f"{b} nhanh hơn"))})
    return pd.DataFrame(rows)


def loglog(summary: pd.DataFrame, lstar: pd.DataFrame) -> pd.DataFrame:
    rows = []
    s = summary[summary["exp"] == "TN1"]
    lstar = lstar[lstar["exp"] == "TN1"]
    for v in ("V1", "V2"):
        for label, pick in (("cutoff 0", lambda n: 0), ("cutoff 10", lambda n: 10),
                            ("L* của từng n", lambda n: int(lstar[(lstar["dist"] == "RANDOM") & (lstar["n"] == n) &
                                                                   (lstar["version"] == v)]["L*"].iloc[0]))):
            pts = [(n, s[(s["n"] == n) & (s["version"] == v) & (s["cutoff"] == pick(n))]["median_ns"].iloc[0])
                   for n in sorted(s["n"].unique())]
            x, y = np.log2([p[0] for p in pts]), np.log2([p[1] for p in pts])
            b, a = np.polyfit(x, y, 1)
            rows.append({"version": v, "config": label, "b (lg T ~ b lg N)": b})
    return pd.DataFrame(rows)


# ----------------------------------------------------------------------------------------
# 4. Đồ thị
# ----------------------------------------------------------------------------------------
def curve_panel(ax, summary, lstar, exp, dist, n, show_leaves=True):
    for v in ("V1", "V2"):
        g = summary[(summary["exp"] == exp) & (summary["dist"] == dist) &
                    (summary["n"] == n) & (summary["version"] == v)].sort_values("cutoff")
        st = STYLE[v]
        ax.fill_between(g["cutoff"], g["r_ci95_lo"], g["r_ci95_hi"], color=st["color"], alpha=0.18, lw=0)
        ax.plot(g["cutoff"], g["median_r"], color=st["color"], ls=st["ls"], lw=1.6, label=v)
        L = lstar[(lstar["exp"] == exp) & (lstar["dist"] == dist) & (lstar["n"] == n) & (lstar["version"] == v)].iloc[0]
        y = g.set_index("cutoff").loc[L["L*"], "median_r"]
        ax.plot([L["L*"]], [y], marker=st["marker"], ms=8, color=st["color"],
                markeredgecolor="white", markeredgewidth=1.5, zorder=5)
        ax.annotate(f"L*={L['L*']}", (L["L*"], y), textcoords="offset points",
                    xytext=(0, -14 if v == "V1" else 8), ha="center", fontsize=8, color=INK)
    if show_leaves:
        for s in leaf_sizes(n, 80):
            if s >= 2:
                ax.axvline(s, color=INK2, lw=0.5, ls=":", alpha=0.6)
    ax.axhline(1, color=INK2, lw=0.8)
    for c in (7, 10):
        ax.axvline(c, color=INK, lw=0.8, alpha=0.35)
    ax.set_title(f"{dist}, n = {n:,}".replace(",", " "), color=INK)
    ax.set_xlabel("CUTOFF")
    ax.set_ylabel("t / t(không cutoff)")


def fig_curves(summary, lstar, exp, cases, path, title):
    cols = 3 if len(cases) > 4 else 2
    rows = int(np.ceil(len(cases) / cols))
    fig, axes = plt.subplots(rows, cols, figsize=(5.2 * cols, 3.8 * rows), squeeze=False)
    for ax in axes.flat[len(cases):]:
        ax.axis("off")
    for ax, (dist, n) in zip(axes.flat, cases):
        curve_panel(ax, summary, lstar, exp, dist, n)
    handles, labels = axes.flat[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="upper right", frameon=False, ncol=2)
    fig.suptitle(title + "\n(đường = median 30 trial; dải = KTC 95% bootstrap; vạch chấm = độ dài lá; "
                 "vạch đậm = cutoff 7 và 10)", fontsize=10, color=INK)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def fig_lstar_vs_n(summary, lstar, path):
    """Mỗi cutoff thuộc vùng gần tối ưu là một chấm nhỏ (vùng có thể không liền mạch); chấm to = L*."""
    d = lstar[lstar["exp"] == "TN1"]
    s = summary[summary["exp"] == "TN1"]
    ns = sorted(d["n"].unique())
    fig, ax = plt.subplots(figsize=(7.5, 4.4))
    for i, v in enumerate(("V1", "V2")):
        st = STYLE[v]
        for j, n in enumerate(ns):
            g = s[(s["n"] == n) & (s["version"] == v)]
            near = g[g["median_r"] <= g["median_r"].min() * (1 + NEAR_PCT)]["cutoff"]
            x = j + (i - 0.5) * 0.22
            ax.plot([x] * len(near), near, "_", color=st["color"], ms=9, mew=2, alpha=0.6)
            L = d[(d["n"] == n) & (d["version"] == v)]["L*"].iloc[0]
            ax.plot([x], [L], st["marker"], color=st["color"], ms=8, markeredgecolor="white",
                    markeredgewidth=1.5, label=v if j == 0 else None)
    ax.plot([], [], "_", color=INK2, ms=9, mew=2, label=f"vùng gần tối ưu (≤ L* + {NEAR_PCT:.0%})")
    ax.set_xticks(range(len(ns)), [f"{n:,}".replace(",", " ") for n in ns])
    ax.axhspan(7, 10, color=INK2, alpha=0.10)
    ax.text(len(ns) - 0.55, 8.5, "7-10 (giáo trình)", fontsize=7, color=INK2, va="center", ha="right")
    ax.set(xlabel="n", ylabel="CUTOFF", title="L* (chấm to) và vùng gần tối ưu theo n - dữ liệu RANDOM")
    ax.legend(frameon=False, fontsize=8, loc="upper center", bbox_to_anchor=(0.5, -0.15), ncol=3)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def fig_7_vs_10(df, path):
    d = df[df["exp"] == "TN1"]
    fig, ax = plt.subplots(figsize=(7, 3.8))
    ns = sorted(d["n"].unique())
    for i, v in enumerate(("V1", "V2")):
        data = []
        for n in ns:
            w = d[(d["n"] == n) & (d["version"] == v)].pivot(index="trial", columns="cutoff", values="ns_per_sort")
            data.append(100 * (w[10] / w[7] - 1))
        pos = np.arange(len(ns)) + (i - 0.5) * 0.3
        bp = ax.boxplot(data, positions=pos, widths=0.25, patch_artist=True, showfliers=False)
        for box in bp["boxes"]:
            box.set(facecolor=STYLE[v]["color"], alpha=0.45, edgecolor=STYLE[v]["color"])
        for med in bp["medians"]:
            med.set(color=INK)
        ax.plot([], [], "s", color=STYLE[v]["color"], alpha=0.6, label=v)
    ax.axhline(0, color=INK2, lw=0.8)
    ax.set_xticks(range(len(ns)), [f"{n:,}".replace(",", " ") for n in ns])
    ax.set(xlabel="n", ylabel="t(10)/t(7) − 1  (%)",
           title="Cutoff 10 so với 7, ghép cặp theo trial (< 0: 10 nhanh hơn)")
    ax.legend(frameon=False)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


def fig_loglog(summary, path):
    s = summary[(summary["exp"] == "TN1")]
    fig, ax = plt.subplots(figsize=(6.5, 4))
    for v in ("V1", "V2"):
        for c, alpha in ((0, 0.45), (10, 1.0)):
            g = s[(s["version"] == v) & (s["cutoff"] == c)].sort_values("n")
            norm = g["median_ns"] / (g["n"] * np.log2(g["n"]))
            ax.plot(g["n"], norm, color=STYLE[v]["color"], ls=STYLE[v]["ls"], marker=STYLE[v]["marker"],
                    alpha=alpha, label=f"{v}, cutoff {c}")
    ax.set_xscale("log")
    ax.set(xlabel="n (thang log)", ylabel="T(n) / (n lg n)   (ns)",
           title="Chi phí trên mỗi đơn vị n lg n (phẳng = đúng mô hình ~ n lg n)")
    ax.legend(frameon=False, fontsize=8)
    fig.tight_layout()
    fig.savefig(path, dpi=150)
    plt.close(fig)


# ----------------------------------------------------------------------------------------
# 5. Báo cáo chữ
# ----------------------------------------------------------------------------------------
def write_report(path, lstar, tst, fit):
    L = lstar.copy()
    lines = ["KẾT QUẢ PHÂN TÍCH (tự sinh bởi analysis/analyze.py)", "",
             f"Quy tắc: L* = argmin median(r); vùng gần tối ưu = median(r) <= min*(1+{NEAR_PCT:.0%}); "
             f"Wilcoxon ghép cặp, Holm, alpha={ALPHA}", "",
             "== L* và mức tăng tốc so với không cutoff =="]
    for _, r in L.sort_values(["exp", "dist", "n", "version"]).iterrows():
        edge = "  (!) L* = biên khoảng đo" if r["L*"] == r["max_cutoff_tested"] else ""
        lines.append(f"{r['exp']} {r['dist']:<8} n={r['n']:>8} {r['version']}: L*={r['L*']:>2} "
                     f"lá {r['leaf_class_L*']:<10} tăng tốc {r['speedup_L*_%']:5.1f}% "
                     f"(cutoff 7: {r['speedup_7_%']:5.1f}%, 10: {r['speedup_10_%']:5.1f}%)  "
                     f"gần tối ưu: {r['near_optimal']}{edge}")
    lines += ["", "== Cutoff 7 so với 10 (Wilcoxon, p đã hiệu chỉnh Holm) =="]
    t7 = tst[tst["compare"] == "7 vs 10"]
    for _, r in t7.sort_values(["exp", "dist", "n", "version"]).iterrows():
        lines.append(f"{r['exp']} {r['dist']:<8} n={r['n']:>8} {r['version']}: "
                     f"t7/t10-1 = {r['median_paired_diff_%']:+5.2f}%  p_holm={r['p_holm']:.2g}  -> {r['verdict']}"
                     + ("   [7 và 10 CÙNG lớp lá: cùng một thuật toán ở n này]" if r["same_leaf_class"] else ""))
    lines += ["", "== Hệ số mũ log-log (slide W2: T ~ a N^b; N lg N cho b hơi lớn hơn 1) =="]
    for _, r in fit.iterrows():
        lines.append(f"{r['version']} {r['config']:<14} b = {r['b (lg T ~ b lg N)']:.3f}")

    R = L[L["exp"] == "TN1"]
    lines += ["", "== Đối chiếu giả thuyết (dữ liệu RANDOM) =="]
    lines.append(f"H1 (cutoff giúp 10-15%): tăng tốc tại L* từ {R['speedup_L*_%'].min():.1f}% "
                 f"đến {R['speedup_L*_%'].max():.1f}%")
    in_range = R[(R["L*"] >= 5) & (R["L*"] <= 15)]
    lines.append(f"H2 (L* trong 5-15): {len(in_range)}/{len(R)} trường hợp")
    for v in ("V1", "V2"):
        lines.append(f"H3 (L* không phụ thuộc n), {v}: L* theo n = "
                     + ", ".join(f"{n}:{l}" for n, l in R[R['version'] == v][['n', 'L*']].values))
    lines.append("H4 (đường cong phẳng quanh tối ưu): xem độ rộng vùng gần tối ưu ở trên và fig1")
    piv = R.pivot(index="n", columns="version", values="L*")
    lines.append("H5 (L* của V2 < L* của V1): " +
                 ", ".join(f"n={n}: V1={row['V1']}, V2={row['V2']}" for n, row in piv.iterrows()))
    Path(path).write_text("\n".join(lines), encoding="utf-8")
    return "\n".join(lines)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out, figs = Path("analysis/out"), Path("analysis/figures")
    out.mkdir(parents=True, exist_ok=True)
    figs.mkdir(parents=True, exist_ok=True)

    df = load(sys.argv[1:])
    summary = summarize(df)
    lstar = find_lstar(summary)
    tst = tests(df, lstar)
    fit = loglog(summary, lstar)
    leaf = pd.DataFrame([{"n": n, "leaf_sizes_<=128": " ".join(map(str, leaf_sizes(n)))}
                         for n in sorted(df["n"].unique())])

    summary.to_csv(out / "summary.csv", index=False)
    lstar.to_csv(out / "lstar.csv", index=False)
    tst.to_csv(out / "tests.csv", index=False)
    leaf.to_csv(out / "leaf_table.csv", index=False)
    fit.to_csv(out / "loglog_fit.csv", index=False)

    tn1 = [("RANDOM", n) for n in sorted(df[df["exp"] == "TN1"]["n"].unique())]
    if not tn1:
        sys.exit("Cần dữ liệu TN1 (truyền thư mục chạy thật làm tham số đầu tiên)")
    tn2 = [(d, n) for d, n in df[df["exp"] == "TN2"][["dist", "n"]].drop_duplicates().itertuples(index=False)]
    fig_curves(summary, lstar, "TN1", tn1, figs / "fig1_tn1_curves.png", "TN1 - dữ liệu ngẫu nhiên")
    if tn2:
        fig_curves(summary, lstar, "TN2", sorted(tn2), figs / "fig2_tn2_curves.png",
                   "TN2 - các phân phối khác, n = 100 000")
    tn3 = [(d, n) for d, n in df[df["exp"] == "TN3"][["dist", "n"]].drop_duplicates().itertuples(index=False)]
    if tn3:
        fig_curves(summary, lstar, "TN3", sorted(tn3), figs / "fig6_tn3_curves.png", "TN3 - xác nhận lại")
    fig_lstar_vs_n(summary, lstar, figs / "fig3_lstar_vs_n.png")
    fig_7_vs_10(df, figs / "fig4_7_vs_10.png")
    fig_loglog(summary, figs / "fig5_loglog.png")

    print(write_report(out / "report.txt", lstar, tst, fit))
    print(f"\nĐã ghi bảng vào {out}/ và hình vào {figs}/")


if __name__ == "__main__":
    main()