package com.truesim.common.state;

/**
 * A consumer's view of one {@link Versioned}: {@link #update()} says whether it changed since the last update, so a
 * UI element can rebuild only when its data did. Create once, check every frame; checking allocates nothing.
 */
public final class Watch {

    private final Versioned target;
    private long seen = -1;

    Watch(Versioned target) {
        this.target = target;
    }

    /** The watched object. */
    public Versioned target() {
        return target;
    }

    /** Whether the target changed since the last update (always true the first time); then marks it seen. */
    public boolean update() {
        long version = target.version();
        if (version == seen) {
            return false;
        }
        seen = version;
        return true;
    }

    /** Whether {@link #update()} would return true, without marking anything seen. */
    public boolean isChanged() {
        return target.version() != seen;
    }
}
