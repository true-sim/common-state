package com.truesim.common.state;

import java.util.Objects;

/**
 * A value whose changes are stamped: setting an equal value is not a change. For a mutable value whose contents
 * changed in place, call {@link #touch()}.
 *
 * @param <T> the value type
 */
public final class VersionedValue<T> implements Versioned {

    private final ChangeClock clock;
    private T value;
    private long version;

    VersionedValue(ChangeClock clock, T initial) {
        this.clock = clock;
        this.value = initial;
        this.version = clock.stamp();
    }

    public T get() {
        return value;
    }

    /** Sets the value; returns whether that was a change (the new value differs by {@code equals}). */
    public boolean set(T value) {
        clock.check(); // before anything changes
        if (Objects.equals(this.value, value)) {
            return false;
        }
        this.value = value;
        version = clock.stamp();
        return true;
    }

    /** Stamps a change without a new value, e.g. after mutating the value in place. */
    public void touch() {
        clock.check();
        version = clock.stamp();
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
