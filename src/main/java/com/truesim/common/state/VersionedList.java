package com.truesim.common.state;

import com.truesim.annotations.AllowAllocation;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * A list that stamps changes per element as well as for the whole list, so a consumer can update only what changed
 * instead of rebuilding:
 *
 * <ul>
 *   <li>{@link #version(int)}: when element {@code i} was last set or {@linkplain #touch(int) touched};
 *   <li>{@link #structureVersion()}: when elements were last added or removed (indices may have shifted);
 *   <li>{@link #version()}: when anything changed.
 * </ul>
 *
 * <pre>{@code
 * long since = cursor.mark();
 * if (items.structureVersion() > since) {
 *     rebuildRows();
 * } else {
 *     for (int i = 0; i < items.size(); i++) {
 *         if (items.version(i) > since) {
 *             updateRow(i);
 *         }
 *     }
 * }
 * }</pre>
 *
 * <p>A {@link java.util.List} in every other way; setting an element to an equal one is not a change.
 *
 * @param <T> the element type
 */
public final class VersionedList<T> extends AbstractList<T> implements RandomAccess, Versioned {

    private final ChangeClock clock;
    private Object[] elements = new Object[8];
    private long[] stamps = new long[8];
    private int size;
    private long version;
    private long structureVersion;
    private final ChangeLog log;

    VersionedList(ChangeClock clock, int logCapacity) {
        this.clock = clock;
        this.log = new ChangeLog(logCapacity, false);
        this.version = clock.stamp();
        this.structureVersion = version;
        log.reset(version);
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T get(int index) {
        return (T) elements[Objects.checkIndex(index, size)];
    }

    @Override
    public T set(int index, T element) {
        clock.check(); // before anything changes
        T old = get(index);
        if (!Objects.equals(old, element)) {
            elements[index] = element;
            stamps[index] = version = clock.stamp();
            log.addIndex(version, index);
        }
        return old;
    }

    /** Stamps element {@code index} as changed, e.g. after mutating it in place. */
    public void touch(int index) {
        clock.check();
        Objects.checkIndex(index, size);
        stamps[index] = version = clock.stamp();
        log.addIndex(version, index);
    }

    @Override
    public void add(int index, T element) {
        clock.check();
        Objects.checkIndex(index, size + 1);
        if (size == elements.length) {
            grow();
        }
        System.arraycopy(elements, index, elements, index + 1, size - index);
        System.arraycopy(stamps, index, stamps, index + 1, size - index);
        elements[index] = element;
        size++;
        modCount++;
        stamps[index] = version = structureVersion = clock.stamp();
        if (index == size - 1) {
            log.addIndex(version, index); // appended: no index shifted, followers see the new one as changed
        } else {
            log.reset(version); // indices shifted: earlier entries no longer apply
        }
    }

    @AllowAllocation("growing, amortized")
    private void grow() {
        elements = Arrays.copyOf(elements, 2 * elements.length);
        stamps = Arrays.copyOf(stamps, 2 * stamps.length);
    }

    @Override
    public T remove(int index) {
        T old = get(index);
        removeRange(index, index + 1);
        return old;
    }

    @Override
    protected void removeRange(int from, int to) {
        clock.check();
        System.arraycopy(elements, to, elements, from, size - to);
        System.arraycopy(stamps, to, stamps, from, size - to);
        Arrays.fill(elements, size - (to - from), size, null);
        size -= to - from;
        modCount++;
        version = structureVersion = clock.stamp();
        log.reset(version);
    }

    @Override
    public void clear() {
        removeRange(0, size);
    }

    /** When element {@code index} was last set, added or touched. */
    public long version(int index) {
        return stamps[Objects.checkIndex(index, size)];
    }

    /**
     * Visits the indices whose elements changed after {@code since}, each once, in the order of their latest changes,
     * and returns {@code true}; or returns {@code false} without visiting when that cannot be told and the consumer
     * should rebuild: elements were inserted before the end or removed since, the log overflowed, or {@code since} is
     * from before the list existed. Elements appended are visited as changed. Only what changed is visited, so a large
     * list with few changes costs little.
     */
    public boolean changesSince(long since, IndexVisitor visitor) {
        if (!log.covers(since)) {
            return false;
        }
        for (int i = log.firstAfter(since); i < log.size(); i++) {
            int index = log.index(i);
            if (stamps[index] == log.stamp(i)) {
                visitor.changed(index); // its latest change; earlier entries of the same index are skipped
            }
        }
        return true;
    }

    /** When elements were last added or removed: indices of later elements may have changed. */
    public long structureVersion() {
        return structureVersion;
    }

    @Override
    public long version() {
        return version;
    }
}
