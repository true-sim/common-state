package com.truesim.common.state;

/**
 * Something whose changes are stamped with times from a {@link ChangeClock}: {@link #version()} is the time of the last
 * change, so a consumer that remembers a version knows whether anything changed since, with one comparison and no
 * listeners.
 */
public interface Versioned {

    /** The {@link ChangeClock} time of the last change. Grows with every change; never decreases. */
    long version();

    /** Whether it changed after clock time {@code since}, e.g. a {@link ChangeCursor#mark()}. */
    default boolean changedSince(long since) {
        return version() > since;
    }

    /** A new watch on this, for consumers that check it every frame. Its first {@link Watch#update()} is true. */
    default Watch watch() {
        return new Watch(this);
    }
}
