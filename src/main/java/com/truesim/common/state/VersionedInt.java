package com.truesim.common.state;

/** An {@code int} whose changes are stamped, without boxing: setting the same value is not a change. */
public final class VersionedInt implements Versioned {

    private final ChangeClock clock;
    private int value;
    private long version;

    VersionedInt(ChangeClock clock, int initial) {
        this.clock = clock;
        this.value = initial;
        this.version = clock.stamp();
    }

    public int get() {
        return value;
    }

    /** Sets the value; returns whether that was a change. */
    public boolean set(int value) {
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
