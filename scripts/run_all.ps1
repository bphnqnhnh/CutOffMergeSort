# =====================================================================================
# run_all.ps1 - Chạy toàn bộ thí nghiệm CUTOFF (TN1 + TN2)
#
# Chạy từ bất kỳ đâu (script tự chuyển về thư mục gốc repo):
#   powershell -ExecutionPolicy Bypass -File scripts\run_all.ps1 -Pilot        # chạy thử (Bước 7)
#   powershell -ExecutionPolicy Bypass -File scripts\run_all.ps1               # chạy đầy đủ (Bước 8)
#   ... -Gc G1                  # dùng G1 thay cho Serial GC
#   ... -AffinityMask 0xFF      # ghim JVM vào CPU logic 0-7 (nhóm lõi nhanh, xác định ở Bước 7)
#   ... -Cutoffs "0-40,44-80:4" # danh sách cutoff (mặc định 0-40)
#
# Mỗi cặp (thí nghiệm, n, dist) chạy trong MỘT JVM RIÊNG, có warm-up riêng.
# Kết quả: results/raw/<thời điểm>[_pilot]/  gồm *.csv, *.csv.meta.txt, *.gc.log, run.log
# =====================================================================================
param(
    [switch]$Pilot,
    [ValidateSet("Serial", "G1")] [string]$Gc = "Serial",
    [Int64]$AffinityMask = 0,
    [string]$Cutoffs = "0-40"
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

# ---- 1. Biên dịch lại TOÀN BỘ (tránh chạy nhầm file .class cũ) ----
$sources = (Get-ChildItem src -Recurse -Filter *.java).FullName
javac -encoding UTF-8 -d out $sources
if ($LASTEXITCODE -ne 0) { throw "Compile failed" }

# ---- 2. Kiểm thử đúng đắn: không qua thì KHÔNG đo ----
java -cp out test.SortTest
if ($LASTEXITCODE -ne 0) { throw "SortTest FAILED - dừng, không chạy thí nghiệm" }

# ---- 3. Chuẩn bị thư mục kết quả + log ----
$stamp  = Get-Date -Format "yyyyMMdd_HHmm"
$suffix = if ($Pilot) { "_pilot" } else { "" }
$outDir = "results/raw/$stamp$suffix"
New-Item -ItemType Directory -Force $outDir | Out-Null
Start-Transcript -Path "$outDir/run.log" | Out-Null
Copy-Item results/machine.txt "$outDir/machine.txt" -ErrorAction SilentlyContinue

$gcFlag = if ($Gc -eq "G1") { "-XX:+UseG1GC" } else { "-XX:+UseSerialGC" }
$trials = if ($Pilot) { 5 } else { 30 }
Write-Host "GC=$Gc  trials=$trials  affinity=0x$('{0:X}' -f $AffinityMask)  cutoffs=$Cutoffs  out=$outDir"

# ---- 4. Danh sách lần chạy ----
# Mỗi lần chạy là MỘT đối tượng (Exp, N, Dist). Không dùng mảng lồng nhau vì PowerShell
# hay tự "dàn phẳng" chúng khi đi qua pipeline.
$runs = [System.Collections.Generic.List[object]]::new()
if ($Pilot) {
    foreach ($n in 1000, 100000, 1000000) { $runs.Add([pscustomobject]@{ Exp = "PILOT"; N = $n; Dist = "RANDOM" }) }
} else {
    # TN1: dữ liệu ngẫu nhiên, mọi kích thước (khớp DataGenerator.SIZES)
    foreach ($n in 1000, 10000, 100000, 300000, 700000, 1000000) { $runs.Add([pscustomobject]@{ Exp = "TN1"; N = $n; Dist = "RANDOM" }) }
    # TN2: các phân phối khác, n = 100 000
    foreach ($d in "SORTED", "REVERSED", "FEW_KEYS", "SAWTOOTH") { $runs.Add([pscustomobject]@{ Exp = "TN2"; N = 100000; Dist = $d }) }
}
# Xáo thứ tự các lần chạy (seed cố định => tái lập được) để nhiệt độ máy tăng dần
# không trùng với thứ tự tăng của n.
$rng  = [System.Random]::new(20261108)
$runs = @($runs | Sort-Object { $rng.Next() })
Write-Host ("Run order: " + (($runs | ForEach-Object { "$($_.Exp)_$($_.Dist)_n$($_.N)" }) -join ", "))

# ---- 5. Chạy từng cấu hình trong một JVM riêng ----
function Invoke-Exp([string]$exp, [int]$n, [string]$dist) {
    $name = "${exp}_${dist}_n$n"
    $argsList = @(
        "-Xms4g", "-Xmx4g", $gcFlag, "-XX:+AlwaysPreTouch",
        "-Xlog:gc:file=$outDir/$name.gc.log",
        "-cp", "out", "bench.Experiment",
        "--exp", $exp, "--n", "$n", "--dist", $dist,
        "--trials", "$trials", "--cutoffs", $Cutoffs, "--out", "$outDir/$name.csv"
    )
    Write-Host ""
    Write-Host ">>> $name   ($(Get-Date -Format 'HH:mm:ss'))"
    $p = Start-Process -FilePath "java" -ArgumentList $argsList -NoNewWindow -PassThru
    $null = $p.Handle                                  # cần để đọc được ExitCode sau này
    if ($AffinityMask -ne 0) { $p.ProcessorAffinity = [IntPtr]$AffinityMask }
    $p.WaitForExit()
    if ($p.ExitCode -ne 0) { throw "$name FAILED (exit code $($p.ExitCode))" }
}

$t0 = Get-Date
foreach ($r in $runs) { Invoke-Exp $r.Exp $r.N $r.Dist }
Write-Host ""
Write-Host "ALL DONE in $([int]((Get-Date) - $t0).TotalMinutes) min -> $outDir"
Stop-Transcript | Out-Null