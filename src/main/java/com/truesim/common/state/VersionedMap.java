package com.truesim.common.state;

import com.truesim.annotations.AllowAllocation;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A map that stamps changes per key, so a consumer updates only the entries that were added, changed or removed:
 *
 * <pre>{@code
 * long since = cursor.mark();
 * if (!players.changesSince(since, (id, change) -> {
 *     switch (change) {
 *         case ADDED, CHANGED -> showRow(id, players.get(id));
 *         case REMOVED -> hideRow(id);
 *     }
 * })) {
 *     rebuildAll(); // first time, after clear(), or the change log overflowed
 * }
 * }</pre>
 *
 * <p>Change it through {@link #put}, {@link #remove}, {@link #clear} and {@link #touch}; its views are read-only.
 * Putting an equal value is not a change. Keys need consistent {@code equals} and {@code hashCode}, and must not change
 * while in the map.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
@AllowAllocation("entries are created when keys are added")
public final class VersionedMap<K, V> extends AbstractMap<K, V> implements Versioned {

    /** A key's state: its value and when it was last changed and added; kept as a tombstone once removed. */
    private static final class Slot<V> {
        V value;
        long stamp;
        long added;
    }

    private final ChangeClock clock;
    private final HashMap<K, Slot<V>> slots = new HashMap<>();
    /** Removed keys whose removal is still in the change log. */
    private final HashMap<K, Slot<V>> removed = new HashMap<>();
    private final ChangeLog log;
    private final Set<Entry<K, V>> entries = new Entries();
    private long version;
    private long structureVersion;

    VersionedMap(ChangeClock clock, int logCapacity) {
        this.clock = clock;
        this.log = new ChangeLog(logCapacity, true);
        this.version = clock.stamp();
        this.structureVersion = version;
        log.reset(version);
    }

    @Override
    public int size() {
        return slots.size();
    }

    @Override
    public boolean containsKey(Object key) {
        return slots.containsKey(key);
    }

    @Override
    public V get(Object key) {
        Slot<V> slot = slots.get(key);
        return slot == null ? null : slot.value;
    }

    /** Sets the value of {@code key}: a change if the key is new or the value differs by {@code equals}. */
    @Override
    public V put(K key, V value) {
        clock.check(); // before anything changes
        Slot<V> slot = slots.get(key);
        if (slot != null) {
            V old = slot.value;
            if (!Objects.equals(old, value)) {
                slot.value = value;
                record(key, slot);
            }
            return old;
        }
        slot = removed.remove(key);
        if (slot == null) {
            slot = new Slot<>();
        }
        slot.value = value;
        slots.put(key, slot);
        record(key, slot);
        slot.added = slot.stamp;
        structureVersion = slot.stamp;
        return null;
    }

    /** Removes {@code key}; returns its value, or {@code null} if it was not present. */
    @Override
    @SuppressWarnings("unchecked")
    public V remove(Object key) {
        clock.check();
        Slot<V> slot = slots.remove(key);
        if (slot == null) {
            return null;
        }
        removed.put((K) key, slot);
        record((K) key, slot);
        structureVersion = slot.stamp;
        return slot.value;
    }

    /** Removes every key. Consumers rebuild afterwards: {@link #changesSince} returns {@code false} across a clear. */
    @Override
    public void clear() {
        clock.check();
        slots.clear();
        removed.clear();
        version = structureVersion = clock.stamp();
        log.reset(version);
    }

    /** Stamps {@code key} as changed, e.g. after mutating its value in place; returns whether it is present. */
    public boolean touch(K key) {
        clock.check();
        Slot<V> slot = slots.get(key);
        if (slot == null) {
            return false;
        }
        record(key, slot);
        return true;
    }

    /** Stamps a change of {@code key} and logs it, first dropping the oldest log entry (and its tombstone) if full. */
    private void record(K key, Slot<V> slot) {
        if (log.isFull()) {
            Object oldest = log.key(0);
            Slot<V> tombstone = removed.get(oldest);
            if (tombstone != null && tombstone.stamp == log.stamp(0)) {
                removed.remove(oldest);
            }
            log.dropOldest();
        }
        slot.stamp = version = clock.stamp();
        log.addKey(version, key);
    }

    /** When {@code key} was last added, set or touched; {@code -1} if it is not present. */
    public long version(K key) {
        Slot<V> slot = slots.get(key);
        return slot == null ? -1 : slot.stamp;
    }

    /** When keys were last added or removed. */
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public long version() {
        return version;
    }

    /**
     * Visits the keys added, changed or removed after {@code since}, each once, in the order of their latest changes,
     * and returns {@code true}; or returns {@code false} without visiting when that cannot be told and the consumer
     * should rebuild: the map was cleared since, the change log overflowed, or {@code since} is from before the map
     * existed.
     */
    @SuppressWarnings("unchecked")
    public boolean changesSince(long since, KeyVisitor<? super K> visitor) {
        if (!log.covers(since)) {
            return false;
        }
        for (int i = log.firstAfter(since); i < log.size(); i++) {
            K key = (K) log.key(i);
            long stamp = log.stamp(i);
            Slot<V> slot = slots.get(key);
            if (slot != null) {
                if (slot.stamp == stamp) {
                    visitor.changed(key, slot.added > since ? Change.ADDED : Change.CHANGED);
                }
            } else if (removed.get(key).stamp == stamp) {
                visitor.changed(key, Change.REMOVED);
            }
        }
        return true;
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return entries;
    }

    /** A read-only view of the entries. */
    private final class Entries extends AbstractSet<Entry<K, V>> {

        @Override
        public int size() {
            return slots.size();
        }

        @Override
        public Iterator<Entry<K, V>> iterator() {
            Iterator<Map.Entry<K, Slot<V>>> iterator = slots.entrySet().iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return iterator.hasNext();
                }

                @Override
                public Entry<K, V> next() {
                    Map.Entry<K, Slot<V>> next = iterator.next();
                    return new SimpleImmutableEntry<>(next.getKey(), next.getValue().value);
                }
            };
        }
    }
}
