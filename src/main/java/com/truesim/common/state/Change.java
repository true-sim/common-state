package com.truesim.common.state;

/** What happened to a key of a {@link VersionedMap} or {@link VersionedSet} since a consumer last looked. */
public enum Change {
    /** Added since then (it may also have changed after being added). */
    ADDED,
    /** Present before and since, with a new value or touched. */
    CHANGED,
    /** Removed since then (it may have been added after the consumer looked, and removed again). */
    REMOVED
}
