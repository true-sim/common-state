package com.truesim.common.state;

/**
 * The recent appends to a ring, oldest first, in a ring of fixed capacity: each entry is a stamp and the absolute index
 * of the first sample that append added. Appends are contiguous, so the first entry after a time tells where
 * everything appended since starts. Like {@link ChangeLog}, it remembers up to which stamp it no longer has everything.
 */
final class RangeLog {

    private final long[] stamps;
    private final long[] starts;
    private int first;
    private int size;
    /** Appends up to this stamp may be missing. */
    private long incompleteThrough;

    RangeLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A change log needs room for at least one change: " + capacity);
        }
        stamps = new long[capacity];
        starts = new long[capacity];
    }

    /** Whether every append after {@code since} is still here. */
    boolean covers(long since) {
        return since >= incompleteThrough;
    }

    /** Forgets everything up to {@code stamp}, e.g. after a clear. */
    void reset(long stamp) {
        first = 0;
        size = 0;
        incompleteThrough = stamp;
    }

    /** Logs an append at {@code stamp} whose first sample has index {@code start}, first dropping the oldest if full. */
    void add(long stamp, long start) {
        if (size == stamps.length) {
            incompleteThrough = stamps[first];
            first = (first + 1) % stamps.length;
            size--;
        }
        int slot = (first + size++) % stamps.length;
        stamps[slot] = stamp;
        starts[slot] = start;
    }

    /** Where the samples appended after {@code since} start, or {@code -1} if none were; by binary search. */
    long startAfter(long since) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (stamps[(first + middle) % stamps.length] > since) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low < size ? starts[(first + low) % stamps.length] : -1;
    }
}
