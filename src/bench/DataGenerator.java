package bench;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Random;

/**
 * Bộ sinh dữ liệu thí nghiệm có thể tái lập.
 *
 * Nguyên tắc "một nguồn sự thật": Experiment và main() của lớp này đều gọi
 * CÙNG hàm generate(dist, n, seed). Mỗi mảng được định danh bằng (dist, n, trial)
 * -> seedFor(...) -> generate(...) -> checksum(...).
 *
 * Chạy:  java -cp out bench.DataGenerator [thư_mục_data]   (mặc định: data)
 */
public class DataGenerator {

    public enum Dist { RANDOM, SORTED, REVERSED, FEW_KEYS, SAWTOOTH }

    // ---------------- Tham số của bộ dữ liệu (ghi vào báo cáo) ----------------
    public static final long BASE_SEED    = 20261108L;
    public static final int  FEW_KEYS_K   = 10;      // số giá trị khác nhau của FEW_KEYS
    public static final int  SAWTOOTH_RUN = 1000;    // độ dài mỗi khúc tăng dần của SAWTOOTH

    public static final int[] SIZES = {1_000, 10_000, 100_000, 300_000, 700_000, 1_000_000};
    public static final int   TRIALS = 30;            // số trial có trong manifest
    public static final int   SAMPLE_MAX_N = 100_000; // chỉ ghi file mẫu cho n <= giá trị này

    // ---------------- Sinh dữ liệu ----------------

    /** Sinh mảng Double[] độ dài n theo phân phối d; cùng (d, n, seed) => cùng mảng. */
    public static Double[] generate(Dist d, int n, long seed) {
        if (n < 0) throw new IllegalArgumentException("n must be >= 0, got " + n);
        Random rnd = new Random(seed);
        double[] x = new double[n];
        switch (d) {
            case RANDOM -> fillUniform(x, rnd);
            case SORTED -> {
                fillUniform(x, rnd);
                Arrays.sort(x);                       // chỉ dùng để SINH dữ liệu, không phải cài đặt được đo
            }
            case REVERSED -> {
                fillUniform(x, rnd);
                Arrays.sort(x);
                reverse(x);
            }
            case FEW_KEYS -> {
                for (int i = 0; i < n; i++) x[i] = rnd.nextInt(FEW_KEYS_K) / (double) FEW_KEYS_K;
            }
            case SAWTOOTH -> {
                fillUniform(x, rnd);
                for (int lo = 0; lo < n; lo += SAWTOOTH_RUN)
                    Arrays.sort(x, lo, Math.min(n, lo + SAWTOOTH_RUN));
            }
        }
        Double[] a = new Double[n];
        for (int i = 0; i < n; i++) a[i] = x[i];      // autoboxing: mỗi phần tử là một đối tượng Double mới
        return a;
    }

    private static void fillUniform(double[] x, Random rnd) {
        for (int i = 0; i < x.length; i++) x[i] = rnd.nextDouble();   // [0, 1)
    }

    private static void reverse(double[] x) {
        for (int i = 0, j = x.length - 1; i < j; i++, j--) {
            double t = x[i]; x[i] = x[j]; x[j] = t;
        }
    }

    // ---------------- Seed ----------------

    /**
     * Seed của một mảng chỉ phụ thuộc (dist, n, trial) - KHÔNG phụ thuộc cutoff, phiên bản
     * thuật toán hay thứ tự chạy. Dùng d.name() thay vì d.ordinal() để thêm/sắp lại enum
     * không làm đổi seed cũ.
     */
    public static long seedFor(Dist d, int n, int trial) {
        long h = mix64(BASE_SEED ^ d.name().hashCode());
        h = mix64(h ^ n);
        h = mix64(h ^ trial);
        return h;
    }

    /** Hàm trộn bit SplitMix64: đầu vào gần nhau -> đầu ra khác xa nhau, tránh trùng seed. */
    private static long mix64(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ---------------- Checksum ----------------

    /** SHA-256 trên dãy bit IEEE-754 của từng phần tử (không phụ thuộc cách in ra chuỗi). */
    public static String checksum(Double[] a) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            ByteBuffer buf = ByteBuffer.allocate(8 * 4096);
            for (Double v : a) {
                if (!buf.hasRemaining()) { md.update(buf.array(), 0, buf.position()); buf.clear(); }
                buf.putLong(Double.doubleToLongBits(v));
            }
            md.update(buf.array(), 0, buf.position());
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------- Đọc / ghi file ----------------
    // Định dạng: dòng 1 = n, sau đó n dòng, mỗi dòng một số (Double.toString, đọc lại chính xác).

    public static void write(Double[] a, Path p) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
            w.write(Integer.toString(a.length));
            w.write('\n');                             // '\n' cố định, không phụ thuộc hệ điều hành
            for (Double v : a) {
                w.write(Double.toString(v));
                w.write('\n');
            }
        }
    }

    public static Double[] read(Path p) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            String first = r.readLine();
            if (first == null) throw new IOException(p + ": empty file");
            int n = Integer.parseInt(first.trim());
            Double[] a = new Double[n];
            for (int i = 0; i < n; i++) {
                String line = r.readLine();
                if (line == null) throw new IOException(p + ": expected " + n + " values, got " + i);
                a[i] = Double.parseDouble(line.trim());
            }
            return a;
        }
    }

    // ---------------- Tự kiểm tra ----------------

    static void selfTest() throws IOException {
        int n = 5_000;
        for (Dist d : Dist.values()) {
            long s1 = seedFor(d, n, 0), s2 = seedFor(d, n, 1);
            Double[] a = generate(d, n, s1);

            // 1. Cùng seed -> cùng checksum
            check(checksum(a).equals(checksum(generate(d, n, s1))), d + ": same seed, different data");
            // 2. Khác seed -> khác checksum
            check(!checksum(a).equals(checksum(generate(d, n, s2))), d + ": different seeds, same data");
            // 3. Ghi rồi đọc lại -> cùng checksum
            Path tmp = Files.createTempFile("dg-", ".txt");
            try {
                write(a, tmp);
                check(checksum(a).equals(checksum(read(tmp))), d + ": write/read round trip changed data");
            } finally {
                Files.deleteIfExists(tmp);
            }
            // 4. Đúng tính chất của phân phối
            for (Double v : a) check(v >= 0.0 && v < 1.0, d + ": value out of [0,1): " + v);
            switch (d) {
                case SORTED   -> { for (int i = 1; i < n; i++) check(a[i-1] <= a[i], d + ": not ascending at " + i); }
                case REVERSED -> { for (int i = 1; i < n; i++) check(a[i-1] >= a[i], d + ": not descending at " + i); }
                case FEW_KEYS -> check(new HashSet<>(Arrays.asList(a)).size() <= FEW_KEYS_K, d + ": too many distinct keys");
                case SAWTOOTH -> {
                    for (int i = 1; i < n; i++)
                        if (i % SAWTOOTH_RUN != 0) check(a[i-1] <= a[i], d + ": run not ascending at " + i);
                }
                default -> { }
            }
        }
        // 5. seedFor không trùng trên toàn bộ không gian dùng tới
        HashSet<Long> seen = new HashSet<>();
        for (Dist d : Dist.values())
            for (int sz : SIZES)
                for (int t = 0; t < TRIALS; t++)
                    check(seen.add(seedFor(d, sz, t)), "seed collision at " + d + " n=" + sz + " t=" + t);
        System.out.println("SELFTEST PASS");
    }

    private static void check(boolean ok, String msg) {
        if (!ok) throw new IllegalStateException("SELFTEST FAIL: " + msg);
    }

    // ---------------- main: ghi file mẫu + manifest + mô tả seed ----------------

    public static void main(String[] args) throws IOException {
        long t0 = System.nanoTime();
        selfTest();

        Path dataDir = Path.of(args.length > 0 ? args[0] : "data");
        Files.createDirectories(dataDir);
        writeSeedsDoc(dataDir.resolve("seeds.txt"));

        int samples = 0, rows = 0;
        try (BufferedWriter w = Files.newBufferedWriter(dataDir.resolve("manifest.csv"), StandardCharsets.UTF_8)) {
            w.write("dist,n,trial,seed,checksum_sha256,file\n");
            for (Dist d : Dist.values())
                for (int n : SIZES)
                    for (int t = 0; t < TRIALS; t++) {
                        long seed = seedFor(d, n, t);
                        Double[] a = generate(d, n, seed);
                        String cs = checksum(a);
                        String file = "";
                        if (t == 0 && n <= SAMPLE_MAX_N) {
                            file = "samples/" + d + "_n" + n + "_t0.txt";
                            Path p = dataDir.resolve(file);
                            write(a, p);
                            check(checksum(read(p)).equals(cs), "sample file mismatch " + p);
                            samples++;
                        }
                        w.write(d + "," + n + "," + t + "," + seed + "," + cs + "," + file + "\n");
                        rows++;
                    }
        }
        System.out.printf("Wrote %d manifest rows, %d sample files to %s (%.1fs)%n",
                rows, samples, dataDir.toAbsolutePath(), (System.nanoTime() - t0) / 1e9);
    }

    private static void writeSeedsDoc(Path p) throws IOException {
        String doc = String.join("\n",
                "# Sơ đồ seed của bộ dữ liệu (sinh tự động bởi bench.DataGenerator)",
                "BASE_SEED    = " + BASE_SEED,
                "seed(d,n,t)  = mix64( mix64( mix64(BASE_SEED ^ d.name().hashCode()) ^ n ) ^ t )",
                "mix64        = SplitMix64 finalizer",
                "RNG          = java.util.Random(seed); RANDOM dùng nextDouble() trong [0,1)",
                "FEW_KEYS_K   = " + FEW_KEYS_K + "   (giá trị = nextInt(K)/K)",
                "SAWTOOTH_RUN = " + SAWTOOTH_RUN + " (khúc liên tiếp độ dài cố định, mỗi khúc được sắp tăng)",
                "SIZES        = " + Arrays.toString(SIZES),
                "TRIALS       = " + TRIALS,
                "Seed KHÔNG phụ thuộc cutoff hay phiên bản thuật toán: mọi cutoff, V1 và V2",
                "trong cùng một trial dùng đúng một mảng đầu vào (thiết kế ghép cặp).",
                "");
        Files.writeString(p, doc, StandardCharsets.UTF_8);
    }
}