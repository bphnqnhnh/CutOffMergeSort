package test;

import java.util.Arrays;
import java.util.Random;
import java.util.TreeSet;

public class SortTest {
    interface Sorter { void sort(Comparable[] a, int cutoff); }

    enum Dist { RANDOM, FEW_KEYS, SORTED, REVERSED, ALL_EQUAL, SAWTOOTH }

    private static final long SEED = 20261107L;
    private static final int MAX_CUTOFF = 40;
    private static final int[] SIZES = sizes();

    private static long countA, countB, countC, countD;

    public static void main(String[] args) {
        boolean ok = insertionRangeTest();
        if (ok) System.out.println("PASS [InsertionSort] B=" + countB);

        ok = ok && runAll("MergeCutOff", sort.MergeCutOff::sort);
        ok = ok && runAll("MergeOptimized", sort.MergeOptimized::sort);

        if (!ok) System.exit(1);
    }

    static boolean runAll(String name, Sorter s) {
        countA = countC = countD = 0;
        long t0 = System.nanoTime();

        boolean ok = orderTest(name, s) && stabilityTest(name, s) && badArgTest(name, s);
        if (ok) {
            System.out.printf("PASS [%s] A=%d C=%d D=%d (%.1fs)%n",
                    name, countA, countC, countD, (System.nanoTime() - t0) / 1e9);
        }
        return ok;
    }

    // ---- A: output matches Arrays.sort ----

    private static boolean orderTest(String name, Sorter s) {
        for (int n : SIZES) {
            for (int cutoff = 0; cutoff <= MAX_CUTOFF; cutoff++) {
                for (Dist d : Dist.values()) {
                    long seed = seed('A', n, cutoff, d);
                    Integer[] a = boxed(keys(d, n, new Random(seed)));
                    Integer[] expected = a.clone();
                    Arrays.sort(expected);

                    try {
                        s.sort(a, cutoff);
                    } catch (Throwable t) { // StackOverflowError is not an Exception
                        return fail(name, 'A', n, cutoff, d, seed, t.toString());
                    }
                    if (!Arrays.equals(a, expected)) {
                        return fail(name, 'A', n, cutoff, d, seed, "differs from Arrays.sort");
                    }
                    countA++;
                }
            }
        }
        return true;
    }

    // ---- B: InsertionSort.sort(a, lo, hi) only touches [lo, hi] ----

    private static boolean insertionRangeTest() {
        final String name = "InsertionSort";
        for (int n = 1; n <= 200; n++) {
            for (int trial = 0; trial < 20; trial++) {
                long seed = seed('B', n, trial, Dist.RANDOM);
                Random rnd = new Random(seed);
                Integer[] a = boxed(keys(Dist.RANDOM, n, rnd));
                Integer[] orig = a.clone();

                int lo, hi;
                if (trial == 0) {
                    lo = 0;
                    hi = n - 1;
                } else if (trial == 1) {
                    lo = hi = rnd.nextInt(n);
                } else {
                    lo = rnd.nextInt(n);
                    hi = lo + rnd.nextInt(n - lo);
                }
                String where = "lo=" + lo + " hi=" + hi;

                try {
                    sort.InsertionSort.sort(a, lo, hi);
                } catch (Throwable t) {
                    return fail(name, 'B', n, "-", Dist.RANDOM, seed, where + ", " + t);
                }

                for (int i = 0; i < n; i++) {
                    if ((i < lo || i > hi) && a[i] != orig[i]) {
                        return fail(name, 'B', n, "-", Dist.RANDOM, seed,
                                where + ", touched a[" + i + "]");
                    }
                }

                Integer[] expected = Arrays.copyOfRange(orig, lo, hi + 1);
                Arrays.sort(expected);
                if (!Arrays.equals(Arrays.copyOfRange(a, lo, hi + 1), expected)) {
                    return fail(name, 'B', n, "-", Dist.RANDOM, seed, where + ", range not sorted");
                }
                countB++;
            }
        }
        return true;
    }

    // ---- C: equal keys keep their original order ----

    static class Item implements Comparable<Item> {
        final int key, idx;

        Item(int key, int idx) {
            this.key = key;
            this.idx = idx;
        }

        @Override
        public int compareTo(Item o) {
            return Integer.compare(key, o.key);
        }

        @Override
        public String toString() {
            return "(" + key + "," + idx + ")";
        }
    }

    private static boolean stabilityTest(String name, Sorter s) {
        for (int n : SIZES) {
            for (int cutoff = 0; cutoff <= MAX_CUTOFF; cutoff++) {
                for (Dist d : Dist.values()) {
                    long seed = seed('C', n, cutoff, d);
                    int[] k = keys(d, n, new Random(seed));
                    Item[] a = new Item[n];
                    for (int i = 0; i < n; i++) a[i] = new Item(k[i], i);

                    try {
                        s.sort(a, cutoff);
                    } catch (Throwable t) {
                        return fail(name, 'C', n, cutoff, d, seed, t.toString());
                    }

                    for (int i = 1; i < n; i++) {
                        Item prev = a[i - 1], cur = a[i];
                        if (prev.key > cur.key || (prev.key == cur.key && prev.idx > cur.idx)) {
                            return fail(name, 'C', n, cutoff, d, seed,
                                    "at i=" + i + ": " + prev + " before " + cur);
                        }
                    }
                    countC++;
                }
            }
        }
        return true;
    }

    // ---- D: negative cutoff must throw ----

    private static boolean badArgTest(String name, Sorter s) {
        for (int n : new int[] {0, 1, 10, 100}) {
            for (int cutoff : new int[] {-1, -5, Integer.MIN_VALUE}) {
                long seed = seed('D', n, cutoff, Dist.RANDOM);
                Integer[] a = boxed(keys(Dist.RANDOM, n, new Random(seed)));
                try {
                    s.sort(a, cutoff);
                } catch (IllegalArgumentException e) {
                    countD++;
                    continue;
                } catch (Throwable t) {
                    return fail(name, 'D', n, cutoff, Dist.RANDOM, seed,
                            "expected IllegalArgumentException, got " + t);
                }
                return fail(name, 'D', n, cutoff, Dist.RANDOM, seed, "no exception thrown");
            }
        }
        return true;
    }

    // ---- helpers ----

    private static int[] sizes() {
        TreeSet<Integer> set = new TreeSet<>();
        for (int n = 0; n <= 200; n++) set.add(n);
        for (int k = 1; k <= 16; k++) {
            int p = 1 << k;
            set.add(p - 1);
            set.add(p);
            set.add(p + 1);
        }
        set.add(100_000);
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    private static int[] keys(Dist d, int n, Random rnd) {
        int[] k = new int[n];
        switch (d) {
            case RANDOM:
                randomPermutation(k, rnd);
                break;
            case FEW_KEYS:
                for (int i = 0; i < n; i++) k[i] = rnd.nextInt(5);
                break;
            case SORTED:
                for (int i = 0; i < n; i++) k[i] = i;
                break;
            case REVERSED:
                for (int i = 0; i < n; i++) k[i] = n - 1 - i;
                break;
            case ALL_EQUAL:
                Arrays.fill(k, 7);
                break;
            case SAWTOOTH:
                // sorted runs of random length laid end to end
                randomPermutation(k, rnd);
                int maxRun = Math.max(2, n / 4);
                for (int lo = 0; lo < n; ) {
                    int hi = Math.min(n, lo + 1 + rnd.nextInt(maxRun));
                    Arrays.sort(k, lo, hi);
                    lo = hi;
                }
                break;
        }
        return k;
    }

    private static void randomPermutation(int[] k, Random rnd) {
        for (int i = 0; i < k.length; i++) k[i] = i;
        for (int i = k.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int tmp = k[i];
            k[i] = k[j];
            k[j] = tmp;
        }
    }

    private static Integer[] boxed(int[] k) {
        Integer[] a = new Integer[k.length];
        for (int i = 0; i < k.length; i++) a[i] = k[i];
        return a;
    }

    private static long seed(char group, int n, int x, Dist d) {
        long h = SEED;
        h = 31 * h + group;
        h = 31 * h + n;
        h = 31 * h + x;
        h = 31 * h + d.ordinal();
        return h;
    }

    private static boolean fail(String name, char group, int n, Object cutoff,
                                Dist d, long seed, String msg) {
        System.out.printf("FAIL [%s] group=%c dist=%s n=%d cutoff=%s seed=%d: %s%n",
                name, group, d, n, cutoff, seed, msg);
        return false;
    }
}