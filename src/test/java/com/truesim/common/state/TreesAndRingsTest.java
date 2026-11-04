package com.truesim.common.state;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.state.VersionedTree.Node;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TreesAndRingsTest {

    private final ChangeClock clock = new ChangeClock();

    private static List<Node<String>> nodes(VersionedTree<String> tree, long since, boolean[] complete) {
        List<Node<String>> visited = new ArrayList<>();
        complete[0] = tree.changesSince(since, visited::add);
        return visited;
    }

    private static List<Node<String>> children(Node<String> node) {
        List<Node<String>> children = new ArrayList<>();
        for (int i = 0; i < node.childCount(); i++) {
            children.add(node.child(i));
        }
        return children;
    }

    private static List<Long> ranges(Versioned ring, long since, boolean[] complete) {
        List<Long> visited = new ArrayList<>();
        RangeVisitor visitor = (from, to) -> {
            visited.add(from);
            visited.add(to);
        };
        complete[0] = switch (ring) {
            case VersionedDoubleRing doubles -> doubles.changesSince(since, visitor);
            case VersionedFloatRing floats -> floats.changesSince(since, visitor);
            default -> throw new IllegalArgumentException();
        };
        return visited;
    }

    /** What {@code change} threw on another thread, or null. */
    private static Throwable onOtherThread(Runnable change) throws InterruptedException {
        Throwable[] thrown = new Throwable[1];
        Thread other = Thread.ofPlatform().name("worker").start(() -> {
            try {
                change.run();
            } catch (IllegalStateException e) {
                thrown[0] = e;
            }
        });
        other.join();
        return thrown[0];
    }

    // ---------------------------------------------------------------- trees

    @Test
    void treesStampValuesChildrenAndStructure() {
        VersionedTree<String> tree = clock.tree("root");
        Node<String> root = tree.root();
        long created = clock.now();
        assertThat(tree.clock()).isSameAs(clock);
        assertThat(tree.version()).isEqualTo(created);
        assertThat(tree.structureVersion()).isEqualTo(created);
        assertThat(tree.size()).isEqualTo(1);
        assertThat(root.tree()).isSameAs(tree);
        assertThat(root.id()).isZero();
        assertThat(root.value()).isEqualTo("root");
        assertThat(root.parent()).isNull();
        assertThat(root.depth()).isZero();
        assertThat(root.attached()).isTrue();
        assertThat(root.childCount()).isZero();
        assertThat(root.version()).isEqualTo(created);
        assertThat(root.childrenVersion()).isEqualTo(created);
        // Adding: a change of structure and of the parent's children.
        Node<String> a = root.add("a");
        Node<String> c = root.add("c");
        Node<String> b = root.add(1, "b");
        assertThat(children(root)).containsExactly(a, b, c);
        assertThat(a.id()).isEqualTo(1);
        assertThat(c.id()).isEqualTo(2);
        assertThat(b.id()).isEqualTo(3);
        assertThat(b.parent()).isSameAs(root);
        assertThat(b.depth()).isEqualTo(1);
        assertThat(b.version()).isEqualTo(clock.now());
        assertThat(root.childrenVersion()).isEqualTo(clock.now());
        assertThat(tree.structureVersion()).isEqualTo(clock.now());
        assertThat(tree.version()).isEqualTo(clock.now());
        assertThat(a.childrenVersion()).isLessThan(root.childrenVersion());
        long rootChildren = root.childrenVersion();
        Node<String> a1 = a.add("a1");
        assertThat(a1.id()).isEqualTo(4);
        assertThat(a1.depth()).isEqualTo(2);
        assertThat(a.childrenVersion()).isEqualTo(tree.structureVersion());
        assertThat(root.childrenVersion()).isEqualTo(rootChildren); // only the parent's children changed
        assertThat(tree.size()).isEqualTo(5);
        assertThat(tree.node(3)).isSameAs(b);
        assertThatThrownBy(() -> root.add(4, "x")).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> root.child(3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(tree.size()).isEqualTo(5);
        // Values.
        long structure = tree.structureVersion();
        long since = clock.now();
        assertThat(a.value("a")).isFalse(); // equal: no change
        assertThat(tree.version()).isEqualTo(since);
        assertThat(a.value("A")).isTrue();
        assertThat(a.value()).isEqualTo("A");
        assertThat(a.version()).isEqualTo(clock.now());
        assertThat(tree.version()).isEqualTo(clock.now());
        assertThat(tree.structureVersion()).isEqualTo(structure);
        b.touch();
        a1.value("A1");
        a.touch(); // a again: visited once, last
        boolean[] complete = {false};
        assertThat(nodes(tree, since, complete)).containsExactly(b, a1, a);
        assertThat(complete[0]).isTrue();
        assertThat(nodes(tree, clock.now(), complete)).isEmpty();
        assertThat(complete[0]).isTrue();
        assertThat(nodes(tree, created - 1, complete)).isEmpty();
        assertThat(complete[0]).isFalse(); // first time: build everything
        // Indices and ancestors.
        assertThat(root.indexOf(b)).isEqualTo(1);
        assertThat(root.indexOf(a1)).isEqualTo(-1);
        assertThat(root.isAncestorOf(a1)).isTrue();
        assertThat(a.isAncestorOf(a1)).isTrue();
        assertThat(a1.isAncestorOf(a)).isFalse();
        assertThat(a.isAncestorOf(a)).isFalse();
        assertThat(b.isAncestorOf(a1)).isFalse();
        // Moving: a change of structure and of both parents' children, not of the value.
        c.value("C");
        long cVersion = c.version();
        assertThat(c.move(root, 2)).isFalse(); // where it is
        assertThat(tree.structureVersion()).isEqualTo(structure);
        assertThat(c.move(root, 0)).isTrue();
        assertThat(children(root)).containsExactly(c, a, b);
        assertThat(root.childrenVersion()).isEqualTo(clock.now());
        assertThat(tree.structureVersion()).isEqualTo(clock.now());
        assertThat(c.move(a, 1)).isTrue();
        assertThat(children(root)).containsExactly(a, b);
        assertThat(children(a)).containsExactly(a1, c);
        assertThat(c.parent()).isSameAs(a);
        assertThat(c.depth()).isEqualTo(2);
        assertThat(root.childrenVersion()).isEqualTo(clock.now());
        assertThat(a.childrenVersion()).isEqualTo(clock.now());
        assertThat(c.version()).isEqualTo(cVersion);
        // Value changes survive changes of structure: node identity is stable.
        assertThat(nodes(tree, since, complete)).containsExactly(b, a1, a, c);
        assertThat(complete[0]).isTrue();
        // Refused moves change nothing.
        structure = tree.structureVersion();
        assertThatIllegalStateException().isThrownBy(() -> root.move(a, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> a.move(a1, 0)).withMessageContaining("own subtree");
        assertThatIllegalArgumentException().isThrownBy(() -> a.move(a, 0)).withMessageContaining("own subtree");
        assertThatThrownBy(() -> c.move(root, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> c.move(a, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        VersionedTree<String> other = clock.tree("other");
        assertThatIllegalArgumentException().isThrownBy(() -> a1.move(other.root(), 0));
        assertThat(tree.structureVersion()).isEqualTo(structure);
        assertThat(children(a)).containsExactly(a1, c);
        assertThat(c.move(a, 0)).isTrue(); // reordered within its parent
        assertThat(children(a)).containsExactly(c, a1);
        assertThat(a.childrenVersion()).isEqualTo(clock.now());
        assertThat(root.childrenVersion()).isLessThan(clock.now());
    }

    @Test
    void removedNodesAreDetachedWithTheirSubtrees() {
        VersionedTree<String> tree = clock.tree("root");
        Node<String> root = tree.root();
        Node<String> a = root.add("a");
        Node<String> b = root.add("b");
        Node<String> a1 = a.add("a1");
        Node<String> a2 = a.add("a2");
        Node<String> a21 = a2.add("a21");
        long since = clock.now();
        a1.value("A1");
        b.value("B");
        a21.touch();
        long aVersion = a.version();
        a.remove();
        // The subtree stays readable, but is no longer in the tree.
        assertThat(tree.size()).isEqualTo(2);
        assertThat(children(root)).containsExactly(b);
        assertThat(root.childrenVersion()).isEqualTo(clock.now());
        assertThat(tree.structureVersion()).isEqualTo(clock.now());
        assertThat(a.version()).isEqualTo(aVersion);
        for (Node<String> node : List.of(a, a1, a2, a21)) {
            assertThat(node.attached()).isFalse();
            assertThat(tree.node(node.id())).isNull();
        }
        assertThat(a.parent()).isNull();
        assertThat(a21.parent()).isSameAs(a2);
        assertThat(a21.depth()).isEqualTo(2); // from the top of the removed subtree
        assertThat(children(a)).containsExactly(a1, a2);
        assertThat(a1.value()).isEqualTo("A1");
        assertThat(tree.node(0)).isSameAs(root);
        assertThat(tree.node(b.id())).isSameAs(b);
        assertThat(tree.node(-1)).isNull();
        assertThat(tree.node(6)).isNull(); // never was
        // Removed nodes are not visited.
        boolean[] complete = {false};
        assertThat(nodes(tree, since, complete)).containsExactly(b);
        assertThat(complete[0]).isTrue();
        // Removed nodes refuse changes, and moves into them.
        long version = tree.version();
        assertThatIllegalStateException().isThrownBy(() -> a.value("x")).withMessageContaining("removed");
        assertThatIllegalStateException().isThrownBy(a1::touch);
        assertThatIllegalStateException().isThrownBy(() -> a.add("x"));
        assertThatIllegalStateException().isThrownBy(a2::remove);
        assertThatIllegalStateException().isThrownBy(a::remove);
        assertThatIllegalStateException().isThrownBy(() -> a21.move(root, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> b.move(a, 0)).withMessageContaining("not a node");
        assertThatIllegalStateException().isThrownBy(root::remove).withMessageContaining("root");
        assertThat(tree.version()).isEqualTo(version);
        assertThat(a1.value()).isEqualTo("A1");
        // Ids are not reused; tables grow.
        Node<String> d = root.add("d");
        assertThat(d.id()).isEqualTo(6);
        for (int i = 0; i < 20; i++) {
            Node<String> child = d.add("d" + i);
            assertThat(child.id()).isEqualTo(7 + i);
        }
        assertThat(d.childCount()).isEqualTo(20);
        assertThat(d.child(19).value()).isEqualTo("d19");
        assertThat(tree.node(26)).isSameAs(d.child(19));
        assertThat(tree.size()).isEqualTo(23);
        d.child(5).remove();
        assertThat(tree.size()).isEqualTo(22);
        assertThat(d.child(5).value()).isEqualTo("d6");
        assertThat(tree.node(12)).isNull();
    }

    @Test
    void treeLogsOverflow() {
        VersionedTree<String> tree = clock.tree("root", 2);
        Node<String> root = tree.root();
        Node<String> a = root.add("a");
        Node<String> b = root.add("b");
        long since = clock.now();
        a.value("A");
        long recent = clock.now();
        b.value("B");
        root.value("R"); // drops a's change
        boolean[] complete = {false};
        assertThat(nodes(tree, since, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
        assertThat(nodes(tree, recent, complete)).containsExactly(b, root);
        assertThat(complete[0]).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> clock.tree("root", 0));
    }

    // ---------------------------------------------------------------- rings

    @Test
    void doubleRingsHoldTheLatestSamples() {
        VersionedDoubleRing ring = clock.doubleRing(4, 3);
        long created = clock.now();
        assertThat(ring.capacity()).isEqualTo(4);
        assertThat(ring.size()).isZero();
        assertThat(ring.appended()).isZero();
        assertThat(ring.first()).isZero();
        assertThat(ring.version()).isEqualTo(created);
        assertThat(ring.structureVersion()).isEqualTo(created);
        assertThatThrownBy(ring::latest).isInstanceOf(IndexOutOfBoundsException.class);
        boolean[] complete = {false};
        assertThat(ranges(ring, created - 1, complete)).isEmpty();
        assertThat(complete[0]).isFalse(); // first time: copy everything
        assertThat(ranges(ring, created, complete)).isEmpty();
        assertThat(complete[0]).isTrue(); // nothing appended
        // Appending one and many.
        ring.append(1);
        long afterOne = clock.now();
        ring.append(new double[] {9, 2, 9}, 1, 2);
        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.appended()).isEqualTo(2);
        assertThat(ring.latest()).isEqualTo(2);
        assertThat(ring.get(0)).isEqualTo(1);
        assertThat(ranges(ring, created, complete)).containsExactly(0L, 2L);
        assertThat(complete[0]).isTrue();
        long since = clock.now();
        ring.append(new double[] {3, 4, 5}, 0, 3); // wraps around: drops sample 0
        assertThat(ring.version()).isEqualTo(clock.now());
        assertThat(ring.structureVersion()).isEqualTo(created);
        assertThat(ring.size()).isEqualTo(4);
        assertThat(ring.appended()).isEqualTo(5);
        assertThat(ring.first()).isEqualTo(1);
        assertThat(ring.get(1)).isEqualTo(2);
        assertThat(ring.get(4)).isEqualTo(5);
        assertThatThrownBy(() -> ring.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.get(5)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(ranges(ring, since, complete)).containsExactly(2L, 5L);
        assertThat(ranges(ring, created, complete)).containsExactly(1L, 5L); // clipped to what is held
        assertThat(complete[0]).isTrue();
        // Appending none is no change; an equal sample is one.
        long version = ring.version();
        ring.append(new double[] {7}, 1, 1);
        assertThat(ring.version()).isEqualTo(version);
        assertThatThrownBy(() -> ring.append(new double[] {7}, 1, 2)).isInstanceOf(IndexOutOfBoundsException.class);
        ring.append(5); // a full ring drops the oldest; the log drops the first append
        assertThat(ring.version()).isGreaterThan(version);
        assertThat(ring.first()).isEqualTo(2);
        assertThat(ranges(ring, created, complete)).isEmpty();
        assertThat(complete[0]).isFalse(); // the log overflowed
        assertThat(ranges(ring, afterOne, complete)).containsExactly(2L, 6L);
        assertThat(complete[0]).isTrue();
        // More samples than the ring holds: the last ones stay.
        ring.append(new double[] {10, 11, 12, 13, 14, 15}, 0, 6);
        assertThat(ring.appended()).isEqualTo(12);
        assertThat(ring.first()).isEqualTo(8);
        assertThat(ring.get(8)).isEqualTo(12);
        assertThat(ring.latest()).isEqualTo(15);
        ring.append(16);
        // Copying, across the wrap.
        double[] out = new double[6];
        ring.copy(9, 13, out, 1);
        assertThat(out).containsExactly(0, 13, 14, 15, 16, 0);
        ring.copy(10, 11, out, 0);
        assertThat(out[0]).isEqualTo(14);
        ring.copy(11, 11, out, 6);
        assertThatThrownBy(() -> ring.copy(8, 10, out, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.copy(9, 14, out, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.copy(11, 10, out, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.copy(9, 13, out, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        // Clearing: a change of structure; indices go on.
        since = clock.now();
        ring.clear();
        assertThat(ring.structureVersion()).isEqualTo(clock.now());
        assertThat(ring.version()).isEqualTo(clock.now());
        assertThat(ring.size()).isZero();
        assertThat(ring.appended()).isEqualTo(13);
        assertThat(ring.first()).isEqualTo(13);
        assertThatThrownBy(ring::latest).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(ranges(ring, since, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
        long cleared = clock.now();
        ring.append(17);
        assertThat(ranges(ring, cleared, complete)).containsExactly(13L, 14L);
        assertThat(complete[0]).isTrue();
        assertThat(ring.get(13)).isEqualTo(17);
        assertThatIllegalArgumentException().isThrownBy(() -> clock.doubleRing(0));
        assertThatIllegalArgumentException().isThrownBy(() -> clock.doubleRing(4, 0));
    }

    @Test
    void floatRingsHoldTheLatestSamples() {
        VersionedFloatRing ring = clock.floatRing(4, 3);
        long created = clock.now();
        assertThat(ring.capacity()).isEqualTo(4);
        assertThat(ring.size()).isZero();
        assertThat(ring.appended()).isZero();
        assertThat(ring.first()).isZero();
        assertThat(ring.version()).isEqualTo(created);
        assertThat(ring.structureVersion()).isEqualTo(created);
        assertThatThrownBy(ring::latest).isInstanceOf(IndexOutOfBoundsException.class);
        boolean[] complete = {false};
        assertThat(ranges(ring, created - 1, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
        assertThat(ranges(ring, created, complete)).isEmpty();
        assertThat(complete[0]).isTrue();
        ring.append(1);
        long afterOne = clock.now();
        ring.append(new float[] {9, 2, 9}, 1, 2);
        assertThat(ring.size()).isEqualTo(2);
        assertThat(ring.latest()).isEqualTo(2);
        assertThat(ring.get(0)).isEqualTo(1);
        long since = clock.now();
        ring.append(new float[] {3, 4, 5}, 0, 3);
        assertThat(ring.first()).isEqualTo(1);
        assertThat(ring.get(4)).isEqualTo(5);
        assertThatThrownBy(() -> ring.get(0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.get(5)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThat(ranges(ring, since, complete)).containsExactly(2L, 5L);
        assertThat(ranges(ring, created, complete)).containsExactly(1L, 5L);
        long version = ring.version();
        ring.append(new float[] {7}, 1, 1);
        assertThat(ring.version()).isEqualTo(version);
        ring.append(5);
        assertThat(ranges(ring, created, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
        assertThat(ranges(ring, afterOne, complete)).containsExactly(2L, 6L);
        ring.append(new float[] {10, 11, 12, 13, 14, 15}, 0, 6);
        assertThat(ring.first()).isEqualTo(8);
        assertThat(ring.get(8)).isEqualTo(12);
        ring.append(16);
        float[] out = new float[6];
        ring.copy(9, 13, out, 1);
        assertThat(out).containsExactly(0, 13, 14, 15, 16, 0);
        ring.copy(10, 11, out, 0);
        assertThat(out[0]).isEqualTo(14);
        assertThatThrownBy(() -> ring.copy(8, 10, out, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.copy(9, 14, out, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> ring.copy(11, 10, out, 0)).isInstanceOf(IndexOutOfBoundsException.class);
        since = clock.now();
        ring.clear();
        assertThat(ring.structureVersion()).isEqualTo(clock.now());
        assertThat(ring.size()).isZero();
        assertThat(ring.first()).isEqualTo(13);
        assertThat(ranges(ring, since, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> clock.floatRing(0));
    }

    // ---------------------------------------------------------------- owner thread

    @Test
    void ownedClocksRefuseTreeAndRingChangesFromOtherThreads() throws InterruptedException {
        VersionedTree<String> tree = clock.tree("root");
        Node<String> a = tree.root().add("a");
        Node<String> b = tree.root().add("b");
        VersionedDoubleRing doubles = clock.doubleRing(4);
        VersionedFloatRing floats = clock.floatRing(4);
        clock.ownedBy(Thread.currentThread());
        long now = clock.now();
        for (Runnable change : List.<Runnable>of(() -> a.value("x"), a::touch, () -> a.add("x"), a::remove,
            () -> a.move(b, 0), () -> doubles.append(1), () -> doubles.append(new double[] {1}, 0, 1),
            doubles::clear, () -> floats.append(1), () -> floats.append(new float[] {1}, 0, 1), floats::clear)) {
            assertThat(onOtherThread(change)).hasMessageContaining("not on worker");
        }
        assertThat(clock.now()).isEqualTo(now);
        assertThat(a.value()).isEqualTo("a");
        assertThat(tree.size()).isEqualTo(3);
        assertThat(doubles.appended()).isZero();
        a.value("owner"); // the owner
        clock.ownedBy(null);
    }

    // ---------------------------------------------------------------- allocation

    private double sink;

    @Test
    void treeAndRingChecksAllocateNothing() {
        VersionedTree<String> tree = clock.tree("root");
        Node<String> left = tree.root().add("left");
        Node<String> right = tree.root().add("right");
        List<Node<String>> leaves = new ArrayList<>();
        String[] values = new String[16];
        for (int i = 0; i < values.length; i++) {
            leaves.add(left.add("leaf"));
            values[i] = "value " + i;
        }
        for (Node<String> leaf : leaves) {
            leaf.move(right, 0); // grows the children of right once, before measuring
        }
        for (Node<String> leaf : leaves) {
            leaf.move(left, 0);
        }
        VersionedDoubleRing doubles = clock.doubleRing(64);
        VersionedFloatRing floats = clock.floatRing(64);
        double[] doubleBatch = {1, 2, 3, 4, 5};
        float[] floatBatch = {1, 2, 3, 4, 5};
        double[] doubleOut = new double[64];
        float[] floatOut = new float[64];
        NodeVisitor<String> nodeVisitor = node -> sink += node.id();
        RangeVisitor doubleCopy = (from, to) -> doubles.copy(from, to, doubleOut, 0);
        RangeVisitor floatCopy = (from, to) -> floats.copy(from, to, floatOut, 0);
        ChangeCursor cursor = clock.cursor();
        int[] i = {0};
        assertAllocationFree(() -> {
            int k = i[0] = (i[0] + 1) & 15;
            long since = cursor.mark();
            Node<String> leaf = leaves.get(k);
            leaf.value(values[(k + 1) & 15]);
            leaf.touch();
            leaf.move(leaf.parent() == left ? right : left, 0);
            tree.changesSince(since, nodeVisitor);
            doubles.append(k);
            doubles.append(doubleBatch, 0, 5);
            floats.append(k);
            floats.append(floatBatch, 0, 5);
            doubles.changesSince(since, doubleCopy);
            floats.changesSince(since, floatCopy);
            sink += doubles.latest() + floats.get(floats.first()) + tree.node(k).depth() + doubleOut[0] + floatOut[0];
        });
    }
}
