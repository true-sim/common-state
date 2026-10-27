package com.truesim.common.state;

/** Receives the keys that changed, from {@code changesSince} of a {@link VersionedMap} or {@link VersionedSet}. */
@FunctionalInterface
public interface KeyVisitor<K> {

    void changed(K key, Change change);
}
