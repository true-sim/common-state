package com.truesim.common.state;

/** A {@code long} whose changes are stamped, without boxing: setting the same value is not a change. */
public final class VersionedLong implements Versioned {

    private final ChangeClock clock;
    private long value;
    private long version;

    VersionedLong(ChangeClock clock, long initial) {
        this.clock = clock;
        this.value = initial;
        this.version = clock.stamp();
    }

    public long get() {
        return value;
    }

    /** Sets the value; returns whether that was a change. */
    public boolean set(long value) {
        clock.check(); // before anything changes
        if (this.value == value) {
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
