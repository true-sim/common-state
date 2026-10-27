package com.truesim.common.state;

import com.truesim.annotations.AllowAllocation;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A set that stamps additions, removals and {@linkplain #touch touches} per element, so a consumer updates only those:
 * {@link #changesSince} visits each changed element once as {@link Change#ADDED}, {@link Change#CHANGED} (touched) or
 * {@link Change#REMOVED}. Change it through its own methods; iterators are read-only. Elements need consistent
 * {@code equals} and {@code hashCode}, and must not change while in the set.
 *
 * @param <T> the element type
 */
@AllowAllocation("entries are created when elements are added")
public final class VersionedSet<T> extends AbstractSet<T> implements Versioned {

    private final VersionedMap<T, Boolean> map;

    VersionedSet(ChangeClock clock, int logCapacity) {
        this.map = new VersionedMap<>(clock, logCapacity);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public boolean contains(Object element) {
        return map.containsKey(element);
    }

    /** Adds {@code element}; returns whether it was new (a change). */
    @Override
    public boolean add(T element) {
        return map.put(element, Boolean.TRUE) == null;
    }

    /** Removes {@code element}; returns whether it was present (a change). */
    @Override
    public boolean remove(Object element) {
        return map.remove(element) != null;
    }

    @Override
    public boolean removeAll(Collection<?> elements) {
        boolean changed = false;
        for (Object element : elements) {
            changed |= remove(element);
        }
        return changed;
    }

    @Override
    public boolean retainAll(Collection<?> keep) {
        List<T> drop = new ArrayList<>();
        for (T element : this) {
            if (!keep.contains(element)) {
                drop.add(element);
            }
        }
        return removeAll(drop);
    }

    /** Removes every element; consumers rebuild afterwards. */
    @Override
    public void clear() {
        map.clear();
    }

    /** Stamps {@code element} as changed, e.g. after state it stands for changed; returns whether it is present. */
    public boolean touch(T element) {
        return map.touch(element);
    }

    /** When {@code element} was last added or touched; {@code -1} if it is not present. */
    public long version(T element) {
        return map.version(element);
    }

    /** When elements were last added or removed. */
    public long structureVersion() {
        return map.structureVersion();
    }

    @Override
    public long version() {
        return map.version();
    }

    /** Like {@link VersionedMap#changesSince}: each element that changed after {@code since}, once; or {@code false}. */
    public boolean changesSince(long since, KeyVisitor<? super T> visitor) {
        return map.changesSince(since, visitor);
    }

    @Override
    public Iterator<T> iterator() {
        Iterator<Map.Entry<T, Boolean>> entries = map.entrySet().iterator();
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return entries.hasNext();
            }

            @Override
            public T next() {
                return entries.next().getKey();
            }
        };
    }
}
