package bench;

import bench.DataGenerator.Dist;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Harness đo thời gian mergesort theo CUTOFF.
 *
 * Mỗi lần chạy JVM xử lý danh sách (n, dist) truyền qua tham số. Khuyến nghị: script gọi
 * MỘT JVM cho MỖI cặp (n, dist) để trạng thái JIT/heap của cấu hình trước không ảnh hưởng
 * cấu hình sau. Truyền nhiều giá trị vào --n / --dist vẫn chạy được (tất cả trong một JVM).
 *
 * Ví dụ:
 *   java -Xms4g -Xmx4g -XX:+UseSerialGC -cp out bench.Experiment ^
 *        --exp TN1 --n 100000 --dist RANDOM --out results/raw/TN1_RANDOM_n100000.csv
 *
 * Tham số (đều có mặc định):
 *   --exp NAME          tên thí nghiệm ghi vào CSV                 (mặc định TN1)
 *   --n  a,b,c          các kích thước                             (mặc định 100000)
 *   --dist A,B          các phân phối                              (mặc định RANDOM)
 *   --versions V1,V2    phiên bản thuật toán                       (mặc định V1,V2)
 *   --cutoffs LIST      danh sách cutoff, các phần cách nhau bởi dấu phẩy; mỗi phần là
 *                       "a", "a-b" hoặc "a-b:bước". Ví dụ "0-40,44-80:4"   (mặc định 0-40)
 *   --trials R          số trial                                   (mặc định 30)
 *   --warmup W          số lượt warm-up                            (mặc định 10)
 *   --target-ms T       thời gian tối thiểu của một lần đo (lô)    (mặc định 50)
 *   --out FILE          file CSV kết quả                           (bắt buộc)
 */
public class Experiment {

    /** Cùng chữ ký với SortTest.Sorter: sort(mảng, cutoff). */
    interface Sorter { void sort(Comparable[] a, int cutoff); }

    /** Một cấu hình được đo = (phiên bản, cutoff). */
    record Config(String version, Sorter sorter, int cutoff) { }

    // Giới hạn bộ nhớ cho k bản sao: k * n tham chiếu <= MAX_REFS (~80-160 MB).
    static final long MAX_REFS = 20_000_000L;
    // Warm-up luôn chạy trên mảng 100 000 phần tử: đủ nhiều phép tính để JIT biên dịch xong (kể cả khi n nhỏ),
    // nhưng không quá tốn thời gian khi n lớn.
    static final int WARMUP_N = 100_000;

    static long sink = 0;   // chống loại bỏ code chết (Y9): cộng dồn một giá trị từ kết quả, in ra cuối

    public static void main(String[] args) throws IOException {
        Args a = Args.parse(args);
        List<Config> configs = buildConfigs(a.versions, a.cutoffs);

        Path out = Path.of(a.out);
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        writeMeta(Path.of(a.out + ".meta.txt"), a, configs.size());

        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            w.write("exp,version,cutoff,n,dist,trial,seed,checksum,order_idx,k,total_ns,ns_per_sort\n");
            for (int n : a.ns)
                for (Dist d : a.dists)
                    runOne(w, a, configs, n, d);
        }
        System.out.println("done. sink=" + sink);   // in ra để JIT không thể bỏ phần tính sink
    }

    // ---------------------------------------------------------------------------------------
    // Một cặp (n, dist): warm-up -> chọn k -> R trial, mỗi trial đo mọi cấu hình theo thứ tự xáo
    // ---------------------------------------------------------------------------------------
    static void runOne(BufferedWriter w, Args a, List<Config> configs, int n, Dist d) throws IOException {
        System.out.printf("== %s n=%d dist=%s: %d configs x %d trials%n",
                a.exp, n, d, configs.size(), a.trials);

        // ---- Y1. Warm-up: dùng mảng KHÔNG thuộc dữ liệu đo (trial âm), kết quả bị bỏ ----
        int wn = WARMUP_N;
        long tw = System.nanoTime();
        for (int r = 0; r < a.warmup; r++) {
            Double[] input = DataGenerator.generate(d, wn, DataGenerator.seedFor(d, wn, -1 - r));
            List<Config> order = new ArrayList<>(configs);
            Collections.shuffle(order, new Random(r));
            for (Config c : order) {
                Comparable[] x = input.clone();
                c.sorter().sort(x, c.cutoff());
                consume(x);
            }
        }
        System.out.printf("   warm-up %d rounds on n=%d: %.1fs%n", a.warmup, wn, (System.nanoTime() - tw) / 1e9);

        // ---- Y3. Chọn k MỘT lần cho cả (n, dist): mọi cấu hình dùng cùng k ----
        int k = chooseK(configs, n, d, a.targetMs);
        System.out.printf("   k = %d (target %d ms per measurement)%n", k, a.targetMs);

        // ---- Y5, Y6. R trial; mỗi trial một mảng đầu vào dùng chung cho mọi cấu hình ----
        for (int t = 0; t < a.trials; t++) {
            long seed = DataGenerator.seedFor(d, n, t);
            Double[] input = DataGenerator.generate(d, n, seed);
            String checksum = DataGenerator.checksum(input);

            // ---- Y4. Xáo thứ tự cấu hình (tái lập được nhờ seed) ----
            List<Config> order = new ArrayList<>(configs);
            Collections.shuffle(order, new Random(seed ^ 0x5DEECE66DL));

            for (int idx = 0; idx < order.size(); idx++) {
                Config c = order.get(idx);
                long total = measure(c, input, k);
                w.write(String.join(",",
                        a.exp, c.version(), Integer.toString(c.cutoff()), Integer.toString(n), d.name(),
                        Integer.toString(t), Long.toString(seed), checksum, Integer.toString(idx),
                        Integer.toString(k), Long.toString(total), Double.toString((double) total / k)));
                w.write('\n');
            }
            w.flush();   // ghi xuống đĩa sau mỗi trial: nếu dừng giữa chừng vẫn còn dữ liệu
            System.out.printf("   trial %d/%d done%n", t + 1, a.trials);
        }
    }

    /**
     * Đo MỘT cấu hình trên MỘT mảng đầu vào: trả về tổng ns để sắp xếp k bản sao.
     * Mọi việc chuẩn bị và kiểm tra nằm NGOÀI vùng bấm giờ (Y2).
     */
    static long measure(Config c, Double[] input, int k) {
        Comparable[][] copies = new Comparable[k][];
        for (int i = 0; i < k; i++) copies[i] = input.clone();   // ngoài vùng bấm giờ
        System.gc();                                             // Y8: dọn rác của lần đo trước, ngoài vùng bấm giờ

        long t0 = System.nanoTime();
        for (int i = 0; i < k; i++) c.sorter().sort(copies[i], c.cutoff());
        long t1 = System.nanoTime();

        for (Comparable[] x : copies) {                          // Y9: kiểm tra kết quả, ngoài vùng bấm giờ
            if (!isSorted(x))
                throw new IllegalStateException("NOT SORTED: " + c.version() + " cutoff=" + c.cutoff());
            consume(x);
        }
        return t1 - t0;
    }

    /**
     * Chọn k sao cho một lần đo (k lần sắp xếp) kéo dài >= targetMs.
     * Hiệu chỉnh bằng cấu hình chậm nhất có thể đoán trước (V1, cutoff nhỏ nhất) trên trial 0,
     * lấy median của 5 lần đo đơn lẻ. Giới hạn k theo bộ nhớ (MAX_REFS).
     */
    static int chooseK(List<Config> configs, int n, Dist d, int targetMs) {
        Config ref = configs.get(0);   // buildConfigs xếp V1 + cutoff nhỏ nhất lên đầu
        Double[] input = DataGenerator.generate(d, n, DataGenerator.seedFor(d, n, 0));
        long[] ts = new long[5];
        for (int i = 0; i < ts.length; i++) ts[i] = measure(ref, input, 1);
        Arrays.sort(ts);
        long single = Math.max(1, ts[ts.length / 2]);
        long k = (long) Math.ceil(targetMs * 1_000_000.0 / single);
        long kMax = Math.max(1, MAX_REFS / Math.max(1, n));
        return (int) Math.max(1, Math.min(k, kMax));
    }

    // ---------------------------------------------------------------------------------------
    // Tiện ích
    // ---------------------------------------------------------------------------------------
    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean isSorted(Comparable[] x) {
        for (int i = 1; i < x.length; i++)
            if (x[i].compareTo(x[i - 1]) < 0) return false;
        return true;
    }

    static void consume(Comparable[] x) {
        if (x.length > 0) sink += x[x.length / 2].hashCode();
    }

    static List<Config> buildConfigs(List<String> versions, List<Integer> cutoffs) {
        List<Config> list = new ArrayList<>();
        for (String v : versions) {
            Sorter s = switch (v) {
                case "V1" -> sort.MergeCutOff::sort;
                case "V2" -> sort.MergeOptimized::sort;
                default -> throw new IllegalArgumentException("unknown version " + v);
            };
            for (int c : cutoffs) list.add(new Config(v, s, c));
        }
        return list;
    }

    /** Ghi metadata của lần chạy: JVM, cờ, máy, tham số, độ phân giải đồng hồ (Y11). */
    static void writeMeta(Path p, Args a, int nConfigs) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("timestamp      = " + LocalDateTime.now());
        lines.add("java.version   = " + System.getProperty("java.version"));
        lines.add("java.vm.name   = " + System.getProperty("java.vm.name"));
        lines.add("os             = " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        lines.add("cpus(logical)  = " + Runtime.getRuntime().availableProcessors());
        lines.add("max heap (MB)  = " + Runtime.getRuntime().maxMemory() / (1024 * 1024));
        lines.add("jvm args       = " + ManagementFactory.getRuntimeMXBean().getInputArguments());
        lines.add("gc             = " + ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(b -> b.getName()).toList());
        lines.add("nanoTime res   = ~" + timerResolutionNs() + " ns");
        lines.add("args           = " + String.join(" ", a.raw));
        lines.add("configs        = " + nConfigs);
        lines.add("dataset        = BASE_SEED=" + DataGenerator.BASE_SEED
                + " FEW_KEYS_K=" + DataGenerator.FEW_KEYS_K + " SAWTOOTH_RUN=" + DataGenerator.SAWTOOTH_RUN);
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    /** Ước lượng độ phân giải của System.nanoTime(): bước nhảy nhỏ nhất khác 0 quan sát được. */
    static long timerResolutionNs() {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 1000; i++) {
            long t0 = System.nanoTime(), t1;
            do { t1 = System.nanoTime(); } while (t1 == t0);
            best = Math.min(best, t1 - t0);
        }
        return best;
    }

    // ---------------------------------------------------------------------------------------
    // Đọc tham số dòng lệnh
    // ---------------------------------------------------------------------------------------
    static class Args {
        String[] raw;
        String exp = "TN1";
        List<Integer> ns = List.of(100_000);
        List<Dist> dists = List.of(Dist.RANDOM);
        List<String> versions = List.of("V1", "V2");
        List<Integer> cutoffs = parseCutoffs("0-40");
        int trials = 30, warmup = 10, targetMs = 50;
        String out;

        static Args parse(String[] args) {
            Args a = new Args();
            a.raw = args;
            for (int i = 0; i < args.length; i++) {
                String key = args[i];
                if (i + 1 >= args.length) throw new IllegalArgumentException("missing value for " + key);
                String v = args[++i];
                switch (key) {
                    case "--exp"       -> a.exp = v;
                    case "--n"         -> a.ns = Arrays.stream(v.split(",")).map(s -> Integer.parseInt(s.trim())).toList();
                    case "--dist"      -> a.dists = Arrays.stream(v.split(",")).map(s -> Dist.valueOf(s.trim())).toList();
                    case "--versions"  -> a.versions = Arrays.stream(v.split(",")).map(String::trim).toList();
                    case "--cutoffs"   -> a.cutoffs = parseCutoffs(v);
                    case "--trials"    -> a.trials = Integer.parseInt(v);
                    case "--warmup"    -> a.warmup = Integer.parseInt(v);
                    case "--target-ms" -> a.targetMs = Integer.parseInt(v);
                    case "--out"       -> a.out = v;
                    default -> throw new IllegalArgumentException("unknown option " + key);
                }
            }
            if (a.out == null) throw new IllegalArgumentException("--out FILE is required");
            if (a.trials < 1 || a.warmup < 0 || a.targetMs < 1) throw new IllegalArgumentException("bad numbers");
            return a;
        }

        /** "0-40,44-80:4" -> [0,1,...,40,44,48,...,80]; tăng dần, không trùng. */
        static List<Integer> parseCutoffs(String spec) {
            java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
            for (String part : spec.split(",")) {
                part = part.trim();
                int step = 1;
                if (part.contains(":")) {
                    String[] ps = part.split(":");
                    part = ps[0];
                    step = Integer.parseInt(ps[1].trim());
                }
                int lo, hi;
                if (part.contains("-")) {
                    String[] ps = part.split("-");
                    lo = Integer.parseInt(ps[0].trim());
                    hi = Integer.parseInt(ps[1].trim());
                } else {
                    lo = hi = Integer.parseInt(part);
                }
                if (lo < 0 || hi < lo || step < 1) throw new IllegalArgumentException("bad --cutoffs part: " + part);
                for (int c = lo; c <= hi; c += step) set.add(c);
            }
            return new ArrayList<>(set);
        }
    }
}