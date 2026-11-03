package com.truesim.common.state;

/**
 * Receives the samples appended, from {@code changesSince} of a {@link VersionedDoubleRing} or
 * {@link VersionedFloatRing}: absolute indices {@code from} (inclusive) to {@code to} (exclusive).
 */
@FunctionalInterface
public interface RangeVisitor {

    void changed(long from, long to);
}
