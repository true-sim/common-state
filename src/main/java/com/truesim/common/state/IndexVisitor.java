package com.truesim.common.state;

/** Receives the indices that changed, from {@code changesSince} of a versioned list or array. */
@FunctionalInterface
public interface IndexVisitor {

    void changed(int index);
}
