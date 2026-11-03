package com.truesim.common.state;

/** Receives the nodes whose values changed, from {@link VersionedTree#changesSince}. */
@FunctionalInterface
public interface NodeVisitor<T> {

    void changed(VersionedTree.Node<T> node);
}
