package com.truesim.common.state;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollectionsTest {

    private final ChangeClock clock = new ChangeClock();

    private static List<Integer> indices(Versioned collection, long since, boolean[] complete) {
        List<Integer> visited = new ArrayList<>();
        IndexVisitor visitor = visited::add;
        complete[0] = switch (collection) {
            case VersionedList<?> list -> list.changesSince(since, visitor);
            case VersionedDoubleArray array -> array.changesSince(since, visitor);
            case VersionedFloatArray array -> array.changesSince(since, visitor);
            case VersionedIntArray array -> array.changesSince(since, visitor);
            case VersionedLongArray array -> array.changesSince(since, visitor);
            default -> throw new IllegalArgumentException();
        };
        return visited;
    }

    private static <K> Map<K, Change> keys(VersionedMap<K, ?> map, long since, boolean[] complete) {
        Map<K, Change> visited = new LinkedHashMap<>();
        complete[0] = map.changesSince(since, visited::put);
        return visited;
    }

    // ---------------------------------------------------------------- the log

    @Test
    void changeLogs() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ChangeLog(0, false));
        ChangeLog log = new ChangeLog(3, false);
        log.reset(10);
        assertThat(log.covers(9)).isFalse();
        assertThat(log.covers(10)).isTrue();
        log.addIndex(11, 1);
        log.addIndex(12, 2);
        log.addIndex(13, 3);
        assertThat(log.isFull()).isTrue();
        log.addIndex(14, 4); // drops 11: changes after 10 are no longer complete
        assertThat(log.covers(10)).isFalse();
        assertThat(log.covers(11)).isTrue();
        assertThat(log.size()).isEqualTo(3);
        assertThat(log.stamp(0)).isEqualTo(12);
        assertThat(log.index(2)).isEqualTo(4);
        assertThat(log.firstAfter(0)).isZero();
        assertThat(log.firstAfter(12)).isEqualTo(1);
        assertThat(log.firstAfter(13)).isEqualTo(2);
        assertThat(log.firstAfter(99)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- lists

    @Test
    void listsVisitOnlyChangedIndices() {
        VersionedList<String> list = clock.list(8);
        boolean[] complete = {false};
        assertThat(indices(list, -1, complete)).isEmpty();
        assertThat(complete[0]).isFalse(); // first time: build everything
        for (int i = 0; i < 10; i++) {
            list.add("item " + i);
        }
        long since = clock.now();
        list.set(4, "four");
        list.touch(2);
        list.touch(4); // index 4 again: visited once, after 2
        assertThat(indices(list, since, complete)).containsExactly(2, 4);
        assertThat(complete[0]).isTrue();
        assertThat(indices(list, clock.now(), complete)).isEmpty();
        assertThat(complete[0]).isTrue();
        // Appending shifts no index: the new one is visited as changed.
        since = clock.now();
        list.add("item 10");
        list.set(1, "one");
        assertThat(indices(list, since, complete)).containsExactly(10, 1);
        assertThat(complete[0]).isTrue();
        // Inserting before the end or removing shifts indices: rebuild.
        list.add(0, "first");
        assertThat(indices(list, since, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
        since = clock.now();
        list.remove(0);
        assertThat(indices(list, since, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
        // More changes than the log holds: rebuild for old marks, still exact for recent ones.
        since = clock.now();
        for (int i = 0; i < 9; i++) {
            list.touch(i);
        }
        indices(list, since, complete);
        assertThat(complete[0]).isFalse();
        long recent = list.version(6);
        assertThat(indices(list, recent, complete)).containsExactly(7, 8);
        assertThat(complete[0]).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> clock.list(0));
    }

    // ---------------------------------------------------------------- maps

    @Test
    void mapsReportAddedChangedAndRemovedKeys() {
        VersionedMap<String, Integer> scores = clock.map();
        boolean[] complete = {false};
        keys(scores, -1, complete);
        assertThat(complete[0]).isFalse();
        assertThat(scores.put("ada", 1)).isNull();
        assertThat(scores.put("bob", 2)).isNull();
        assertThat(scores.put("cy", 3)).isNull();
        long since = clock.now();
        assertThat(scores.put("ada", 1)).isEqualTo(1); // equal: not a change
        assertThat(scores.put("bob", 20)).isEqualTo(2);
        assertThat(scores.remove("cy")).isEqualTo(3);
        assertThat(scores.remove("nobody")).isNull();
        assertThat(scores.put("dee", 4)).isNull();
        assertThat(scores.put("eve", 5)).isNull();
        assertThat(scores.remove("eve")).isEqualTo(5); // added and removed since: reported removed
        assertThat(keys(scores, since, complete)).containsExactly(
            Map.entry("bob", Change.CHANGED), Map.entry("cy", Change.REMOVED), Map.entry("dee", Change.ADDED),
            Map.entry("eve", Change.REMOVED));
        assertThat(complete[0]).isTrue();
        // Removed and added back: added, once.
        since = clock.now();
        scores.remove("bob");
        scores.put("bob", 7);
        assertThat(keys(scores, since, complete)).containsExactly(Map.entry("bob", Change.ADDED));
        // Touched in place.
        since = clock.now();
        assertThat(scores.touch("ada")).isTrue();
        assertThat(scores.touch("nobody")).isFalse();
        assertThat(keys(scores, since, complete)).containsExactly(Map.entry("ada", Change.CHANGED));
        assertThat(scores.version("ada")).isEqualTo(clock.now());
        assertThat(scores.version("nobody")).isEqualTo(-1);
        assertThat(scores.structureVersion()).isEqualTo(scores.version("bob"));
        assertThat(scores.version()).isEqualTo(clock.now());
        // Cleared: rebuild.
        since = clock.now();
        scores.clear();
        assertThat(scores).isEmpty();
        assertThat(keys(scores, since, complete)).isEmpty();
        assertThat(complete[0]).isFalse();
    }

    @Test
    void mapTombstonesLiveAsLongAsTheirLogEntries() {
        VersionedMap<Integer, String> map = clock.map(4);
        boolean[] complete = {false};
        map.put(1, "one");         // entry A
        long afterAdd = clock.now();
        map.remove(1);             // entry B: tombstone for 1
        map.put(2, "two");         // C
        map.put(3, "three");       // D
        map.put(4, "four");        // E: drops A, the add of 1; its tombstone (from B) stays
        assertThat(keys(map, afterAdd, complete)).containsExactly(
            Map.entry(1, Change.REMOVED), Map.entry(2, Change.ADDED), Map.entry(3, Change.ADDED),
            Map.entry(4, Change.ADDED));
        assertThat(complete[0]).isTrue();
        long afterRemove = map.version(2) - 1;
        map.put(5, "five");        // F: drops B, the removal, and with it the tombstone
        keys(map, afterAdd, complete);
        assertThat(complete[0]).isFalse();
        assertThat(keys(map, afterRemove, complete)).containsOnlyKeys(2, 3, 4, 5);
        assertThat(complete[0]).isTrue();
        // Adding the key back after its tombstone is gone starts fresh.
        long since = clock.now();
        map.put(1, "again");
        assertThat(keys(map, since, complete)).containsExactly(Map.entry(1, Change.ADDED));
    }

    @Test
    void mapsAreReadOnlyMapsOtherwise() {
        VersionedMap<String, Integer> map = clock.map();
        map.put("a", 1);
        map.put("b", 2);
        assertThat(map).containsOnly(Map.entry("a", 1), Map.entry("b", 2));
        assertThat(map.get("a")).isEqualTo(1);
        assertThat(map.get("z")).isNull();
        assertThat(map.containsKey("b")).isTrue();
        assertThat(map.keySet()).containsOnly("a", "b");
        assertThat(map.values()).containsOnly(1, 2);
        assertThat(map.entrySet()).hasSize(2);
        Iterator<Map.Entry<String, Integer>> iterator = map.entrySet().iterator();
        Map.Entry<String, Integer> entry = iterator.next();
        assertThat(entry).isInstanceOf(SimpleImmutableEntry.class);
        assertThatThrownBy(() -> entry.setValue(9)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(iterator::remove).isInstanceOf(UnsupportedOperationException.class);
        assertThat(map.put("a", null)).isEqualTo(1);
        assertThat(map.get("a")).isNull();
    }

    // ---------------------------------------------------------------- sets

    @Test
    void setsReportAdditionsRemovalsAndTouches() {
        VersionedSet<String> selected = clock.set();
        boolean[] complete = {false};
        assertThat(selected.add("a")).isTrue();
        assertThat(selected.add("a")).isFalse();
        selected.addAll(List.of("b", "c", "d"));
        long since = clock.now();
        assertThat(selected.remove("b")).isTrue();
        assertThat(selected.remove("b")).isFalse();
        assertThat(selected.touch("c")).isTrue();
        assertThat(selected.touch("zzz")).isFalse();
        selected.add("e");
        Map<String, Change> changes = new LinkedHashMap<>();
        complete[0] = selected.changesSince(since, changes::put);
        assertThat(complete[0]).isTrue();
        assertThat(changes).containsExactly(
            Map.entry("b", Change.REMOVED), Map.entry("c", Change.CHANGED), Map.entry("e", Change.ADDED));
        assertThat(selected).containsExactlyInAnyOrder("a", "c", "d", "e");
        assertThat(selected.contains("c")).isTrue();
        assertThat(selected.version("e")).isEqualTo(clock.now());
        assertThat(selected.version("b")).isEqualTo(-1);
        assertThat(selected.structureVersion()).isEqualTo(selected.version("e"));
        assertThat(selected.version()).isEqualTo(clock.now());
        assertThat(selected.removeAll(List.of("a", "zzz"))).isTrue();
        assertThat(selected.removeAll(List.of("zzz"))).isFalse();
        assertThat(selected.retainAll(Set.of("c", "d"))).isTrue();
        assertThat(selected).containsExactlyInAnyOrder("c", "d");
        Iterator<String> iterator = selected.iterator();
        iterator.next();
        assertThatThrownBy(iterator::remove).isInstanceOf(UnsupportedOperationException.class);
        since = clock.now();
        selected.clear();
        assertThat(selected).isEmpty();
        assertThat(selected.changesSince(since, (element, change) -> { })).isFalse();
        assertThat(clock.set(4).changesSince(-1, (element, change) -> { })).isFalse();
    }

    // ---------------------------------------------------------------- arrays

    @Test
    void primitiveArrays() {
        boolean[] complete = {false};
        VersionedDoubleArray doubles = clock.doubleArray(4, 8);
        VersionedFloatArray floats = clock.floatArray(4);
        VersionedIntArray ints = clock.intArray(4);
        VersionedLongArray longs = clock.longArray(4);
        long since = clock.now();
        assertThat(doubles.set(1, 0)).isFalse(); // the initial value
        assertThat(floats.set(1, 0f)).isFalse();
        assertThat(ints.set(1, 0)).isFalse();
        assertThat(longs.set(1, 0)).isFalse();
        assertThat(floats.set(1, Float.NaN)).isTrue();
        assertThat(floats.set(1, Float.NaN)).isFalse(); // NaN equals NaN bit for bit
        doubles.set(3, 1.5);
        doubles.set(0, 2.5);
        floats.set(2, 3.5f);
        ints.set(0, 6);
        ints.set(0, 7); // the same entry twice: visited once
        longs.set(3, 8);
        longs.set(3, 9);
        floats.set(1, 1f); // entry 1 again, after 2
        doubles.set(0, 2.25);
        assertThat(indices(doubles, since, complete)).containsExactly(3, 0);
        assertThat(doubles.get(0)).isEqualTo(2.25);
        assertThat(indices(floats, since, complete)).containsExactly(2, 1);
        assertThat(indices(ints, since, complete)).containsExactly(0);
        assertThat(indices(longs, since, complete)).containsExactly(3);
        assertThat(complete[0]).isTrue();
        assertThat(doubles.get(3)).isEqualTo(1.5);
        assertThat(floats.get(2)).isEqualTo(3.5f);
        assertThat(ints.get(0)).isEqualTo(7);
        assertThat(longs.get(3)).isEqualTo(9);
        assertThat(floats.length()).isEqualTo(4);
        assertThat(ints.length()).isEqualTo(4);
        assertThat(longs.length()).isEqualTo(4);
        assertThat(floats.version(1)).isEqualTo(floats.version());
        assertThat(ints.version(0)).isEqualTo(ints.version());
        assertThat(longs.version(3)).isEqualTo(longs.version());
        // First time, and after the log overflowed: copy everything.
        indices(ints, -1, complete);
        assertThat(complete[0]).isFalse();
        for (int i = 0; i < 9; i++) {
            doubles.set(i % 4, i);
        }
        indices(doubles, since, complete);
        assertThat(complete[0]).isFalse();
        indices(floats, -1, complete);
        assertThat(complete[0]).isFalse();
        indices(longs, -1, complete);
        assertThat(complete[0]).isFalse();
        assertThatThrownBy(() -> ints.set(4, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    // ---------------------------------------------------------------- derived primitives

    @Test
    void derivedPrimitives() {
        VersionedInt count = clock.intValue(3);
        VersionedLong total = clock.longValue(10);
        DerivedInt doubled = DerivedInt.of(() -> count.get() * 2, count);
        DerivedLong sum = DerivedLong.of(() -> total.get() + count.get(), total, count);
        DerivedBoolean many = DerivedBoolean.of(() -> count.get() > 5, count);
        assertThat(doubled.get()).isEqualTo(6);
        assertThat(sum.get()).isEqualTo(13);
        assertThat(many.get()).isFalse();
        long before = many.version();
        count.set(4); // still not many: recomputed, not a change
        assertThat(many.get()).isFalse();
        assertThat(many.version()).isEqualTo(before);
        count.set(6);
        assertThat(many.get()).isTrue();
        assertThat(many.version()).isEqualTo(count.version());
        assertThat(doubled.get()).isEqualTo(12);
        assertThat(doubled.version()).isEqualTo(count.version());
        long sumBefore = sum.version();
        total.set(9);
        count.set(7); // 9 + 7 = 16, as before: recomputed, not a change
        assertThat(sum.get()).isEqualTo(16);
        assertThat(sum.version()).isEqualTo(sumBefore);
        count.set(8); // 17: a change
        assertThat(sum.get()).isEqualTo(17);
        assertThat(sum.version()).isEqualTo(count.version());
        DerivedInt same = DerivedInt.of(() -> count.get() / 10, count);
        assertThat(same.get()).isZero();
        long first = same.version();
        count.set(9);
        assertThat(same.get()).isZero();
        assertThat(same.version()).isEqualTo(first);
    }

    // ---------------------------------------------------------------- allocation

    private long sink;

    @Test
    void visitingAndSettingAllocateNothing() {
        VersionedList<String> list = clock.list();
        for (int i = 0; i < 64; i++) {
            list.add("x");
        }
        VersionedMap<Integer, String> map = clock.map();
        Integer[] keys = new Integer[64];
        for (int i = 0; i < 64; i++) {
            keys[i] = i;
            map.put(keys[i], "x");
        }
        VersionedFloatArray floats = clock.floatArray(64);
        VersionedInt count = clock.intValue(0);
        DerivedInt doubled = DerivedInt.of(() -> count.get() * 2, count);
        IndexVisitor indexVisitor = index -> sink += index;
        KeyVisitor<Integer> keyVisitor = (key, change) -> sink += change.ordinal();
        ChangeCursor cursor = clock.cursor();
        int[] i = {0};
        assertAllocationFree(() -> {
            int k = i[0] = (i[0] + 1) & 63;
            long since = cursor.mark();
            list.touch(k);
            map.touch(keys[k]);
            floats.set(k, k + floats.get(k));
            count.set(k);
            list.changesSince(since, indexVisitor);
            map.changesSince(since, keyVisitor);
            floats.changesSince(since, indexVisitor);
            sink += doubled.get();
        });
    }
}
