package sort;

public class MergeOptimized {
    public static void sort(Comparable[] a, int cutoff) {
        if (cutoff < 0) throw new IllegalArgumentException("CutOff must be >= 0, got " + cutoff);
        Comparable[] aux = a.clone();
        sort(aux, a, 0, a.length-1, cutoff);
    }

    private static void sort(Comparable[] src, Comparable[] dst, int lo, int hi, int cutoff) {
        if (hi <= lo) return;

        if (hi <= lo + cutoff - 1) {
            InsertionSort.sort(dst, lo, hi);
            return;
        }
        
        int mid = lo + (hi - lo) / 2;
        sort(dst, src, lo, mid, cutoff);
        sort(dst, src, mid+1, hi, cutoff);

        if (!less(src[mid+1], src[mid])) {
            System.arraycopy(src, lo, dst, lo, hi-lo+1);
            return;
        }
        merge(src, dst, lo, mid, hi);
    }

    private static void merge(Comparable[] src, Comparable[] dst, int lo, int mid, int hi) {
        int i = lo;
        int j = mid + 1;

        for (int k = lo; k <= hi; k++) {
            if (i > mid)                dst[k] = src[j++];
            else if (j > hi)            dst[k] = src[i++];
            else if (less(src[j], src[i]))  dst[k] = src[j++];
            else                        dst[k] = src[i++];
        }
    }

    private static boolean less(Comparable v, Comparable w) {
        return v.compareTo(w) < 0;
    }
}