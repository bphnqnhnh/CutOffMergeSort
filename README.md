# CutOffMergeSort: tìm giá trị CUTOFF tối ưu cho Mergesort

Dự án môn **Cấu trúc dữ liệu và giải thuật**, Trường Đại học Công nghệ, ĐHQG Hà Nội. Giáo trình: *Algorithms, 4th ed.* (Sedgewick & Wayne).

Mergesort và Quicksort có chung một cải tiến: dùng insertion sort cho các mảng con ngắn (CUTOFF). Giáo trình nêu giá trị 7 (bản cũ) và khoảng 10 (bản mới). Dự án này **đo thực nghiệm** để tìm CUTOFF phù hợp nhất cho cấu hình máy của tác giả và cài đặt mergesort dựng từ bài giảng.

- **Báo cáo đầy đủ:** [`report/report.pdf`](report/report.pdf) (report/report.tex)
- **Repo:** https://github.com/bphnqnhnh/CutOffMergeSort

---

## Kết quả chính

Đo trên AMD Ryzen AI 7 H 350, Java 25, phần tử `Double`, dữ liệu ngẫu nhiên, 30 trial cho mỗi cấu hình:

| | V1: chỉ có cutoff | V2: đủ 3 cải tiến |
|---|---|---|
| Mức tăng tốc tại cutoff tốt nhất | 10,3–27,0% | 3,4–14,4% |
| CUTOFF dùng chung tốt nhất cho mọi n | **≈ 26** (vùng 24–29), chậm hơn tối ưu từng n tối đa 3,6% | **10** (vùng 10–14), chậm hơn tối ưu từng n tối đa 2,7% |
| 7 hay 10? | 10 nhanh hơn có ý nghĩa ở 3/6 kích thước, các kích thước còn lại không khác biệt | 10 nhanh hơn có ý nghĩa ở 2/6 kích thước, các kích thước còn lại không khác biệt |

- Thời gian là **hàm bậc thang** của CUTOFF. Mergesort chia đôi, nên CUTOFF chỉ có tác dụng qua độ dài các lá; mọi CUTOFF cho cùng tập độ dài lá là cùng một thuật toán. Vì vậy L\* được báo cáo dưới dạng **một vùng**.
- L\* phụ thuộc vào phiên bản cài đặt (bỏ bước sao chép làm L\* giảm mạnh), vào phân phối dữ liệu (REVERSED cho lá tối ưu ngắn nhất, SORTED dài nhất) và vào n (qua cấu trúc lá và trạng thái cache).

---

## Cấu trúc thư mục

```
CutOffMergeSort/
├── src/
│   ├── sort/                    # CÀI ĐẶT MERGESORT (sinh viên tự viết theo bài giảng)
│   │   ├── InsertionSort.java   #   insertion sort theo đoạn [lo, hi]
│   │   ├── MergeCutOff.java     #   V1: mergesort top-down + cutoff
│   │   └── MergeOptimized.java  #   V2: cutoff + stop-if-sorted + bỏ sao chép (đổi vai trò mảng)
│   ├── test/
│   │   └── SortTest.java        # kiểm thử tính đúng: thứ tự, insertion theo đoạn, tính ổn định, tham số lỗi
│   └── bench/
│       ├── DataGenerator.java   # sinh dữ liệu theo seed, checksum SHA-256, manifest
│       └── Experiment.java      # harness đo thời gian
├── scripts/
│   └── run_all.ps1              # chạy toàn bộ thí nghiệm (Windows PowerShell)
├── data/                        # BỘ DỮ LIỆU THÍ NGHIỆM
│   ├── seeds.txt                #   sơ đồ seed và tham số bộ dữ liệu (tự sinh)
│   ├── manifest.csv             #   seed + checksum của 900 mảng (5 phân phối x 6 kích thước x 30 trial)
│   └── samples/                 #   15 file dữ liệu mẫu (trial 0, n <= 100 000)
├── results/
│   ├── machine.txt              # cấu hình máy đo
│   └── raw/                     # DỮ LIỆU ĐO GỐC (không sửa tay)
│       ├── 20261108_0403_pilot/ #   lần chạy thử
│       ├── affinity/            #   phép thử ghim nhóm lõi CPU (AFF_LO: CPU 0-7, AFF_HI: CPU 8-15)
│       ├── 20261108_0426/       #   lần chạy thật: TN1 (6 kích thước) + TN2 (4 phân phối)
│       └── TN3/                 #   thí nghiệm xác nhận, n = 200 000
├── analysis/
│   ├── pilot_check.py           # kiểm tra chất lượng phép đo (A/A test, độ nhiễu, trôi, GC, loại lõi)
│   ├── analyze.py               # thống kê, kiểm định, vẽ hình
│   ├── out/                     # bảng kết quả: summary, lstar, tests, leaf_table, loglog_fit (.csv), report.txt
│   └── figures/                 # các hình trong báo cáo
├── report/
│   └── report.pdf               # BÁO CÁO THÍ NGHIỆM
└── README.md
```

Mỗi file CSV trong `results/raw/` có một dòng cho mỗi lần đo, gồm các cột `exp, version, cutoff, n, dist, trial, seed, checksum, order_idx, k, total_ns, ns_per_sort`. Đi kèm mỗi file CSV là `*.meta.txt` (phiên bản JVM, cờ JVM, GC, tham số chạy) và `*.gc.log`.

---

## Yêu cầu

- **JDK 17+** (đã dùng Oracle Java SE 25.0.2 LTS)
- **Python 3.10+** với `pandas`, `numpy`, `matplotlib`, `scipy`:
  ```bash
  pip install pandas numpy matplotlib scipy
  ```
- Windows PowerShell 5.1+ để chạy `run_all.ps1`

---

## Tái lập thí nghiệm

Mọi lệnh chạy từ **thư mục gốc** của repo.

### 1. Biên dịch và kiểm thử tính đúng

```powershell
javac -encoding UTF-8 -d out src\sort\*.java src\test\*.java src\bench\*.java
java -cp out test.SortTest
```
Kết quả mong đợi:
```
PASS [InsertionSort] B=4000
PASS [MergeCutOff] A=56334 C=56334 D=12
PASS [MergeOptimized] A=56334 C=56334 D=12
```

### 2. Sinh bộ dữ liệu

```powershell
java -cp out bench.DataGenerator
```
Lệnh này tự kiểm tra (`SELFTEST PASS`), rồi ghi `data/seeds.txt`, `data/manifest.csv` và `data/samples/`. Bộ sinh là tất định: chạy lại xong, `git status data` phải không báo thay đổi nào.

`Experiment` sinh dữ liệu trong bộ nhớ bằng **cùng** hàm `DataGenerator.generate(...)` và ghi checksum của từng mảng vào file kết quả, nên có thể đối chiếu với `manifest.csv`.

### 3. Chuẩn bị máy trước khi đo

- Cắm sạc, bật chế độ hiệu năng cao (Lenovo: Fn+Q → Performance; Windows: Power mode → Best performance).
- Đóng mọi ứng dụng khác, kể cả IDE.
- Chạy từ cửa sổ PowerShell riêng và không dùng máy trong lúc đo.
- `run_all.ps1` tự biên dịch lại toàn bộ mã nguồn và chạy `SortTest` trước; nếu kiểm thử thất bại, script dừng và không đo.

### 4. Chạy thử (hiệu chỉnh phép đo)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run_all.ps1 -Pilot
```
Chạy với 5 trial, n ∈ {10³, 10⁵, 10⁶}, mất khoảng 3 phút. Phép thử ghim nhóm lõi CPU:
```powershell
cmd /c start /affinity FF   /wait /b java -Xms4g -Xmx4g -XX:+UseSerialGC -XX:+AlwaysPreTouch -cp out bench.Experiment --exp AFF_LO --n 100000 --trials 5 --out results/raw/affinity/AFF_LO.csv
cmd /c start /affinity FF00 /wait /b java -Xms4g -Xmx4g -XX:+UseSerialGC -XX:+AlwaysPreTouch -cp out bench.Experiment --exp AFF_HI --n 100000 --trials 5 --out results/raw/affinity/AFF_HI.csv
python analysis\pilot_check.py results\raw\<thư_mục>_pilot results\raw\affinity
```

### 5. Chạy thật: TN1 + TN2 (khoảng 40 phút)

```powershell
powershell -ExecutionPolicy Bypass -File scripts\run_all.ps1 -AffinityMask 0xFF -Cutoffs "0-40,44-80:4"
```
- **TN1:** RANDOM, n ∈ {10³, 10⁴, 10⁵, 3·10⁵, 7·10⁵, 10⁶}
- **TN2:** SORTED, REVERSED, FEW_KEYS, SAWTOOTH với n = 10⁵
- Mỗi lần chạy gồm 2 phiên bản × 51 cutoff × 30 trial. Mỗi cặp (thí nghiệm, n, phân phối) chạy trong một JVM riêng.
- `-AffinityMask 0xFF` ghim JVM vào CPU logic 0–7 (lõi Zen 5). Trên máy này, CPU 8–15 (Zen 5c) chậm hơn khoảng 47%.
- Danh sách cutoff **phải đặt trong ngoặc kép**, vì PowerShell hiểu dấu phẩy là mảng.

Tham số của script:

| Tham số | Mặc định | Ý nghĩa |
|---|---|---|
| `-Pilot` | tắt | chạy thử 5 trial |
| `-Gc` | `Serial` | `Serial` hoặc `G1` |
| `-AffinityMask` | `0` (không ghim) | mặt nạ CPU, ví dụ `0xFF` |
| `-Cutoffs` | `"0-40"` | danh sách cutoff: `a`, `a-b` hoặc `a-b:bước`, cách nhau bởi dấu phẩy |

Kết quả được ghi vào `results/raw/<yyyyMMdd_HHmm>/`, gồm `*.csv`, `*.csv.meta.txt` (JVM, cờ, tham số), `*.gc.log` và `run.log`.

### 6. TN3: xác nhận trên n = 200 000

```powershell
cmd /c start /affinity FF /wait /b java -Xms4g -Xmx4g -XX:+UseSerialGC -XX:+AlwaysPreTouch -cp out bench.Experiment --exp TN3 --n 200000 --dist RANDOM --trials 30 --cutoffs "0-40,44-80:4" --out results/raw/TN3/TN3_RANDOM_n200000.csv
```

### 7. Phân tích

```powershell
python analysis\pilot_check.py results\raw\20261108_0426
python analysis\analyze.py results\raw\20261108_0426 results\raw\TN3
```
`analyze.py` ghi bảng vào `analysis/out/` và hình vào `analysis/figures/`:

| Hình | Nội dung |
|---|---|
| `fig1_tn1_curves.png` | tỉ số thời gian theo CUTOFF, từng n (TN1) |
| `fig2_tn2_curves.png` | các phân phối khác, n = 10⁵ (TN2) |
| `fig3_lstar_vs_n.png` | L\* và vùng gần tối ưu theo n |
| `fig4_7_vs_10.png` | so sánh ghép cặp cutoff 7 và 10 |
| `fig5_loglog.png` | T(n)/(n lg n) theo n |
| `fig6_tn3_curves.png` | thí nghiệm xác nhận n = 200 000 |

---

## Phương pháp tóm tắt

- **Định nghĩa CUTOFF** theo slide: mảng con là lá khi `hi <= lo + CUTOFF - 1`, tức độ dài ≤ CUTOFF. Định nghĩa này khác `MergeX.java`, nơi độ dài ≤ CUTOFF + 1.
- **Thiết kế ghép cặp:** trong một trial, mọi cấu hình (phiên bản × cutoff) dùng chung một mảng đầu vào, và thứ tự các cấu hình được xáo ngẫu nhiên.
- **Kiểm soát nhiễu:**
  - warm-up JIT;
  - đo theo lô sao cho mỗi lần đo kéo dài ít nhất khoảng 50 ms;
  - Serial GC, heap cố định 4 GB, `System.gc()` ngoài vùng đo;
  - ghim nhóm lõi CPU;
  - biến `sink` để JIT không loại bỏ phần sắp xếp như mã chết.
- **Quy tắc ra quyết định**, ghi trước khi phân tích:
  - Thước đo là tỉ số ghép cặp r = t(cutoff) / t(không cutoff) trong cùng trial.
  - L\* là cutoff có median r nhỏ nhất.
  - Vùng gần tối ưu gồm các cutoff có median r không quá 1% so với tại L\*.
  - So sánh 7 và 10 bằng kiểm định Wilcoxon ghép cặp, hiệu chỉnh Holm, α = 0,05.
- **Đối chứng:** cutoff 0 và cutoff 1 là cùng một thuật toán (A/A test, dùng để đo nhiễu nền). Các cutoff cùng lớp lá là đối chứng âm cho kiểm định.

Chi tiết: Mục 4 của báo cáo.

---

## Sản phẩm nộp theo đề bài

| Yêu cầu | Vị trí |
|---|---|
| Báo cáo thí nghiệm (mô tả, bảng thống kê, đồ thị, phân tích) | `report/report.pdf`; bảng số trong `analysis/out/`, hình trong `analysis/figures/` |
| Cài đặt mergesort và các script chạy thí nghiệm | `src/sort/` (cài đặt); `src/test/`, `src/bench/`, `scripts/`, `analysis/*.py` (kiểm thử, đo, phân tích) |
| Bộ dữ liệu thí nghiệm | `data/` (manifest, seed, mẫu) + `DataGenerator` để sinh lại toàn bộ; dữ liệu đo gốc trong `results/raw/` |

---

## Sử dụng AI

Chi tiết: Mục B của báo cáo

## Hạn chế chính

- Chỉ đo trên một máy, một JDK, một kiểu phần tử (`Double` đóng hộp).
- Với V1, vùng gần tối ưu chạm biên 80 ở nhiều kích thước, nên tối ưu thật có thể còn lớn hơn 80.
- TN3 được chạy tay, nhiễu lớn hơn TN1 và TN2.

Chi tiết: Mục 8 của báo cáo.
