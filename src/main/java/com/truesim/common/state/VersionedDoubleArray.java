package com.truesim.common.state;

import java.util.Arrays;
import java.util.Objects;

/**
 * A fixed number of doubles, each with its own change stamp, e.g. per-entity values a HUD or a render buffer mirrors:
 * a consumer copies only the entries with {@code version(i) > since}. Setting an entry to the same value (bit for bit)
 * is not a change.
 */
public final class VersionedDoubleArray implements Versioned {

    private final ChangeClock clock;
    private final double[] values;
    private final long[] stamps;
    private long version;

    VersionedDoubleArray(ChangeClock clock, int length) {
        this.clock = clock;
        this.values = new double[length];
        this.stamps = new long[length];
        this.version = clock.stamp();
        Arrays.fill(stamps, version);
    }

    public int length() {
        return values.length;
    }

    public double get(int index) {
        return values[index];
    }

    /** Sets entry {@code index}; returns whether that was a change. */
    public boolean set(int index, double value) {
        if (Double.doubleToLongBits(values[Objects.checkIndex(index, values.length)]) == Double.doubleToLongBits(value)) {
            return false;
        }
        values[index] = value;
        stamps[index] = version = clock.stamp();
        return true;
    }

    /** When entry {@code index} last changed. */
    public long version(int index) {
        return stamps[index];
    }

    @Override
    public long version() {
        return version;
    }
}
