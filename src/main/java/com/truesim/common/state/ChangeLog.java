package com.truesim.common.state;

import java.util.Arrays;

/**
 * The recent changes of a collection, oldest first, in a ring of fixed capacity: each entry is a stamp and the index
 * or key that changed. When the ring is full the oldest entry goes, and the log remembers up to which stamp it no
 * longer has everything, so a consumer asking about an older time learns it must rebuild.
 */
final class ChangeLog {

    private final long[] stamps;
    private final int[] indices;
    private final Object[] keys;
    private int start;
    private int size;
    /** Changes up to this stamp may be missing. */
    private long incompleteThrough;

    /** A log of indices, or with {@code keyed} of keys. */
    ChangeLog(int capacity, boolean keyed) {
        if (capacity < 1) {
            throw new IllegalArgumentException("A change log needs room for at least one change: " + capacity);
        }
        stamps = new long[capacity];
        indices = keyed ? null : new int[capacity];
        keys = keyed ? new Object[capacity] : null;
    }

    /** Whether every change after {@code since} is still here. */
    boolean covers(long since) {
        return since >= incompleteThrough;
    }

    /** Forgets everything up to {@code stamp}, e.g. after a change of structure that invalidates the entries. */
    void reset(long stamp) {
        if (keys != null) {
            Arrays.fill(keys, null);
        }
        start = 0;
        size = 0;
        incompleteThrough = stamp;
    }

    boolean isFull() {
        return size == stamps.length;
    }

    /** Drops the oldest entry; the caller looked at it first if it needs to. */
    void dropOldest() {
        incompleteThrough = stamps[start];
        if (keys != null) {
            keys[start] = null;
        }
        start = (start + 1) % stamps.length;
        size--;
    }

    void addIndex(long stamp, int index) {
        int slot = slotFor();
        stamps[slot] = stamp;
        indices[slot] = index;
    }

    void addKey(long stamp, Object key) {
        int slot = slotFor();
        stamps[slot] = stamp;
        keys[slot] = key;
    }

    private int slotFor() {
        if (isFull()) {
            dropOldest();
        }
        return (start + size++) % stamps.length;
    }

    int size() {
        return size;
    }

    /** The {@code i}-th entry from the oldest. */
    long stamp(int i) {
        return stamps[(start + i) % stamps.length];
    }

    int index(int i) {
        return indices[(start + i) % stamps.length];
    }

    Object key(int i) {
        return keys[(start + i) % stamps.length];
    }

    /** The first entry newer than {@code since}, by binary search: stamps grow along the log. */
    int firstAfter(long since) {
        int low = 0;
        int high = size;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (stamp(middle) > since) {
                high = middle;
            } else {
                low = middle + 1;
            }
        }
        return low;
    }
}
