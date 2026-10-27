package com.truesim.common.state;

/** A {@code boolean} whose changes are stamped, without boxing: setting the same value is not a change. */
public final class VersionedBoolean implements Versioned {

    private final ChangeClock clock;
    private boolean value;
    private long version;

    VersionedBoolean(ChangeClock clock, boolean initial) {
        this.clock = clock;
        this.value = initial;
        this.version = clock.stamp();
    }

    public boolean get() {
        return value;
    }

    /** Sets the value; returns whether that was a change. */
    public boolean set(boolean value) {
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
