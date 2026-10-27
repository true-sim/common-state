package com.truesim.common.state;

/**
 * A {@code double} whose changes are stamped, without boxing: setting the same value is not a change. Values compare
 * bit for bit, so NaN equals NaN and -0.0 differs from 0.0.
 */
public final class VersionedDouble implements Versioned {

    private final ChangeClock clock;
    private double value;
    private long version;

    VersionedDouble(ChangeClock clock, double initial) {
        this.clock = clock;
        this.value = initial;
        this.version = clock.stamp();
    }

    public double get() {
        return value;
    }

    /** Sets the value; returns whether that was a change. */
    public boolean set(double value) {
        if (Double.doubleToLongBits(this.value) == Double.doubleToLongBits(value)) {
            return false;
        }
        this.value = value;
        version = clock.stamp();
        return true;
    }

    @Override
    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
