package com.truesim.common.state;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;
import java.util.Objects;

/**
 * A tree of values whose nodes keep their identity, e.g. the model behind a tree view: a consumer keeps one row per
 * node, keyed by its {@link Node#id() id}, and updates only what changed:
 *
 * <ul>
 *   <li>{@link Node#version()}: when the node's value was last set or {@linkplain Node#touch() touched};
 *   <li>{@link Node#childrenVersion()}: when the node's children were last added, removed, moved or reordered;
 *   <li>{@link #structureVersion()}: when nodes were last added, removed or moved anywhere;
 *   <li>{@link #version()}: when anything changed.
 * </ul>
 *
 * <pre>{@code
 * long since = cursor.mark();
 * if (tree.structureVersion() > since) {
 *     syncRows(tree.root(), since);  // compares childrenVersion() per node, so unchanged branches are skipped
 * }
 * if (!tree.changesSince(since, node -> updateRow(node.id(), node.value()))) {
 *     updateAllRows();                // first time, or the log overflowed
 * }
 * }</pre>
 *
 * <p>Value changes are logged per node, and as node identity is stable, unlike list indices, adding, removing or
 * moving nodes does not invalidate the log. Removing a node detaches it with its subtree: the detached nodes stay
 * readable but refuse changes with {@link IllegalStateException}, and their ids are not reused. Setting an equal value
 * (by {@code equals}) is not a change.
 *
 * @param <T> the value type
 */
public final class VersionedTree<T> implements Versioned {

    private final ChangeClock clock;
    private final ChangeLog log;
    /** The attached nodes by id; removed ones are null. */
    private Object[] nodes = new Object[16];
    private int nextId;
    private int size;
    private long version;
    private long structureVersion;
    private final Node<T> root;

    VersionedTree(ChangeClock clock, T rootValue, int logCapacity) {
        this.clock = clock;
        this.log = new ChangeLog(logCapacity, false);
        this.version = clock.stamp();
        this.structureVersion = version;
        log.reset(version);
        this.root = register(rootValue, version);
    }

    /** A new attached node with the next id, created at {@code stamp}. */
    @AllowAllocation("a node per node added, and growing the table by id, amortized")
    private Node<T> register(T value, long stamp) {
        if (nextId == nodes.length) {
            nodes = Arrays.copyOf(nodes, 2 * nodes.length);
        }
        Node<T> node = new Node<>(this, nextId, value, stamp);
        nodes[nextId++] = node;
        size++;
        return node;
    }

    /** Detaches {@code node} and its subtree. */
    private void detach(Node<T> node) {
        node.attached = false;
        nodes[node.id] = null;
        size--;
        for (int i = 0; i < node.childCount; i++) {
            detach(node.child(i));
        }
    }

    /** Stamps a change of structure. */
    private long structureChanged() {
        return version = structureVersion = clock.stamp();
    }

    /** Stamps a change of the value of {@code node} and logs it. */
    private void valueChanged(Node<T> node) {
        node.version = version = clock.stamp();
        log.addIndex(version, node.id);
    }

    /** The root node, there from the start; it cannot be removed or moved. */
    public Node<T> root() {
        return root;
    }

    /** The clock that stamps this tree's changes. */
    public ChangeClock clock() {
        return clock;
    }

    /** The number of attached nodes, the root included. */
    public int size() {
        return size;
    }

    /** The attached node with id {@code id}, or {@code null} if there is none (never was, or removed). */
    @SuppressWarnings("unchecked")
    public Node<T> node(int id) {
        return id >= 0 && id < nextId ? (Node<T>) nodes[id] : null;
    }

    /** When nodes were last added, removed or moved: rows of the affected parents need a sync. */
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public long version() {
        return version;
    }

    /**
     * Visits the attached nodes whose values changed (set or touched) after {@code since}, each once, in the order of
     * their latest changes, and returns {@code true}; or returns {@code false} without visiting when that cannot be
     * told and the consumer should update every node: the log overflowed, or {@code since} is from before the tree
     * existed. Nodes removed since are not visited, and changes of structure are not reported here: compare
     * {@link #structureVersion()} and {@link Node#childrenVersion()}.
     */
    @SuppressWarnings("unchecked")
    public boolean changesSince(long since, NodeVisitor<T> visitor) {
        if (!log.covers(since)) {
            return false;
        }
        for (int i = log.firstAfter(since); i < log.size(); i++) {
            Node<T> node = (Node<T>) nodes[log.index(i)];
            if (node != null && node.version == log.stamp(i)) {
                visitor.changed(node); // its latest change, and still attached
            }
        }
        return true;
    }

    /**
     * A node of a {@link VersionedTree}: a value, an ordered list of children and a parent. Its identity is stable
     * while it moves around the tree, and its {@link #id()} is unique in the tree, so consumers can key per-node state
     * by either. Its {@link #version()} tells changes of its value.
     *
     * @param <T> the value type
     */
    public static final class Node<T> implements Versioned {

        private static final Object[] NO_CHILDREN = {};

        private final VersionedTree<T> tree;
        private final int id;
        private T value;
        private long version;
        private long childrenVersion;
        private Node<T> parent;
        private Object[] children = NO_CHILDREN;
        private int childCount;
        private boolean attached = true;

        private Node(VersionedTree<T> tree, int id, T value, long stamp) {
            this.tree = tree;
            this.id = id;
            this.value = value;
            this.version = stamp;
            this.childrenVersion = stamp;
        }

        /** The tree this node was created in. */
        public VersionedTree<T> tree() {
            return tree;
        }

        /**
         * The node's id: the root's is 0, every node added takes the next one, and ids are not reused after removal,
         * so they suit as indices of per-node arrays; see {@link VersionedTree#node(int)}.
         */
        public int id() {
            return id;
        }

        public T value() {
            return value;
        }

        /**
         * Sets the value; returns whether that was a change (the new value differs by {@code equals}).
         *
         * @throws IllegalStateException if this node was removed from its tree
         */
        public boolean value(T value) {
            tree.clock.check(); // before anything changes
            requireAttached();
            if (Objects.equals(this.value, value)) {
                return false;
            }
            this.value = value;
            tree.valueChanged(this);
            return true;
        }

        /**
         * Stamps a change without a new value, e.g. after mutating the value in place.
         *
         * @throws IllegalStateException if this node was removed from its tree
         */
        public void touch() {
            tree.clock.check();
            requireAttached();
            tree.valueChanged(this);
        }

        /** When the value was last set or touched (or the node added). */
        @Override
        public long version() {
            return version;
        }

        /** When children of this node were last added, removed, moved in or out, or reordered (or the node added). */
        public long childrenVersion() {
            return childrenVersion;
        }

        /** The parent; {@code null} for the root, and for a node removed from the tree (the top of its subtree). */
        public Node<T> parent() {
            return parent;
        }

        /** Whether this node is in its tree: true until it, or an ancestor, was removed. */
        public boolean attached() {
            return attached;
        }

        /**
         * The number of ancestors: 0 for the root, 1 for its children, and so on; counted from the top of the removed
         * subtree for a removed node.
         */
        public int depth() {
            int depth = 0;
            for (Node<T> node = parent; node != null; node = node.parent) {
                depth++;
            }
            return depth;
        }

        public int childCount() {
            return childCount;
        }

        @SuppressWarnings("unchecked")
        public Node<T> child(int index) {
            return (Node<T>) children[Objects.checkIndex(index, childCount)];
        }

        /** The index of {@code node} among this node's children, or {@code -1} if it is not one of them. */
        public int indexOf(Node<?> node) {
            for (int i = 0; i < childCount; i++) {
                if (children[i] == node) {
                    return i;
                }
            }
            return -1;
        }

        /** Whether this node is the parent of {@code node}, or the parent's parent, and so on; not of itself. */
        public boolean isAncestorOf(Node<?> node) {
            for (Node<?> ancestor = node.parent; ancestor != null; ancestor = ancestor.parent) {
                if (ancestor == this) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Adds a child with {@code value} after the existing ones; returns it.
         *
         * @throws IllegalStateException if this node was removed from its tree
         */
        public Node<T> add(T value) {
            return add(childCount, value);
        }

        /**
         * Inserts a child with {@code value} at {@code index}; returns it. A change of structure and of this node's
         * children; the new node's value is not logged as a change.
         *
         * @throws IllegalStateException if this node was removed from its tree
         */
        public Node<T> add(int index, T value) {
            tree.clock.check();
            requireAttached();
            Objects.checkIndex(index, childCount + 1);
            long stamp = tree.structureChanged();
            Node<T> child = tree.register(value, stamp);
            insert(index, child);
            childrenVersion = stamp;
            return child;
        }

        /**
         * Removes this node, with its subtree, from the tree: they stay readable, refuse changes and no longer count
         * in {@link VersionedTree#size()}.
         *
         * @throws IllegalStateException if this is the root, or the node was removed already
         */
        public void remove() {
            tree.clock.check();
            requireAttached();
            Node<T> from = requireParent();
            from.removeChild(from.indexOf(this));
            parent = null;
            tree.detach(this);
            from.childrenVersion = tree.structureChanged();
        }

        /**
         * Moves this node, with its subtree, to child {@code index} of {@code newParent}, counted after taking it out
         * of its current parent: {@code move(parent(), parent().childCount() - 1)} moves it to the end among its
         * siblings. Returns whether that was a change: moving to where it is is none.
         *
         * @throws IllegalStateException if this is the root, or the node was removed from its tree
         * @throws IllegalArgumentException if {@code newParent} is not attached to this node's tree, or is this node or
         *     one in its subtree
         */
        public boolean move(Node<T> newParent, int index) {
            tree.clock.check();
            requireAttached();
            Node<T> from = requireParent();
            if (newParent.tree != tree || !newParent.attached) {
                throw new IllegalArgumentException("The new parent is not a node of this tree");
            }
            if (newParent == this || isAncestorOf(newParent)) {
                throw new IllegalArgumentException("A node cannot move into its own subtree");
            }
            int oldIndex = from.indexOf(this);
            boolean sameParent = newParent == from;
            Objects.checkIndex(index, sameParent ? newParent.childCount : newParent.childCount + 1);
            if (sameParent && index == oldIndex) {
                return false;
            }
            from.removeChild(oldIndex);
            newParent.insert(index, this);
            from.childrenVersion = newParent.childrenVersion = tree.structureChanged();
            return true;
        }

        private void requireAttached() {
            if (!attached) {
                throw new IllegalStateException("The node was removed from its tree");
            }
        }

        /** The parent of an attached node; only the root has none. */
        private Node<T> requireParent() {
            if (parent == null) {
                throw new IllegalStateException("The root stays the root");
            }
            return parent;
        }

        private void insert(int index, Node<T> child) {
            if (childCount == children.length) {
                grow();
            }
            System.arraycopy(children, index, children, index + 1, childCount - index);
            children[index] = child;
            childCount++;
            child.parent = this;
        }

        @AllowAllocation("growing, amortized")
        private void grow() {
            children = Arrays.copyOf(children, children.length == 0 ? 4 : 2 * children.length);
        }

        private void removeChild(int index) {
            System.arraycopy(children, index + 1, children, index, childCount - index - 1);
            children[--childCount] = null;
        }
    }
}
