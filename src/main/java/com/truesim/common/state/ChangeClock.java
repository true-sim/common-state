package com.truesim.common.state;

/**
 * The time source for change stamps: every change takes the next time, so versions from one clock are ordered and a
 * single number ("everything up to time {@code t} is processed") covers any number of watched objects. Create the
 * versioned state of one world from one clock, which is also a factory for it.
 *
 * <pre>{@code
 * ChangeClock clock = new ChangeClock();
 * VersionedDouble health = clock.doubleValue(100);
 * VersionedList<Item> inventory = clock.list();
 * Derived<String> label = Derived.of(() -> "HP " + (int) health.get(), health);
 *
 * Watch watch = label.watch();
 * // every frame:
 * if (watch.update()) {
 *     text.setText(label.get());
 * }
 * }</pre>
 *
 * <p>Like the state it stamps, a clock is not thread-safe: change state on its owning thread, e.g. the main thread,
 * and hand over results from other threads at a fixed point of the frame. Reading {@link Derived} values counts as
 * changing, as they refresh when read. Name the owner with {@link #ownedBy} and every change from another thread
 * throws, instead of corrupting stamps unnoticed.
 */
public final class ChangeClock {

    private long now;
    private Thread owner; // checked by stamp() once named

    /** The time of the latest change. */
    public long now() {
        return now;
    }

    /**
     * From now on, only {@code thread} may change state of this clock (null: any thread, the default): a change from
     * another one throws {@link IllegalStateException}. Name it once the state is built, e.g. after loading on a
     * background thread.
     */
    public ChangeClock ownedBy(Thread thread) {
        this.owner = thread;
        return this;
    }

    /** The thread that may change state of this clock, or null for any. */
    public Thread owner() {
        return owner;
    }

    /**
     * Takes the next time, for a change happening now.
     *
     * @throws IllegalStateException on a thread other than the {@link #ownedBy owner}
     */
    public long stamp() {
        check();
        return ++now;
    }

    /** Throws unless the calling thread may change state of this clock: called first, so a refused change does none. */
    void check() {
        if (owner != null && Thread.currentThread() != owner) {
            throw new IllegalStateException("State of this clock changes on " + owner.getName() + " only, not on "
                + Thread.currentThread().getName());
        }
    }

    public <T> VersionedValue<T> value(T initial) {
        return new VersionedValue<>(this, initial);
    }

    public VersionedDouble doubleValue(double initial) {
        return new VersionedDouble(this, initial);
    }

    public VersionedInt intValue(int initial) {
        return new VersionedInt(this, initial);
    }

    public VersionedLong longValue(long initial) {
        return new VersionedLong(this, initial);
    }

    public VersionedBoolean booleanValue(boolean initial) {
        return new VersionedBoolean(this, initial);
    }

    /** Default capacity of a collection's change log: changes a consumer may lag behind before it rebuilds. */
    public static final int DEFAULT_LOG_CAPACITY = 1024;

    public <T> VersionedList<T> list() {
        return list(DEFAULT_LOG_CAPACITY);
    }

    /** A list whose change log holds {@code logCapacity} changes. */
    public <T> VersionedList<T> list(int logCapacity) {
        return new VersionedList<>(this, logCapacity);
    }

    public <K, V> VersionedMap<K, V> map() {
        return map(DEFAULT_LOG_CAPACITY);
    }

    /** A map whose change log holds {@code logCapacity} changes. */
    public <K, V> VersionedMap<K, V> map(int logCapacity) {
        return new VersionedMap<>(this, logCapacity);
    }

    public <T> VersionedSet<T> set() {
        return set(DEFAULT_LOG_CAPACITY);
    }

    /** A set whose change log holds {@code logCapacity} changes. */
    public <T> VersionedSet<T> set(int logCapacity) {
        return new VersionedSet<>(this, logCapacity);
    }

    public VersionedDoubleArray doubleArray(int length) {
        return doubleArray(length, DEFAULT_LOG_CAPACITY);
    }

    /** An array whose change log holds {@code logCapacity} changes. */
    public VersionedDoubleArray doubleArray(int length, int logCapacity) {
        return new VersionedDoubleArray(this, length, logCapacity);
    }

    public VersionedFloatArray floatArray(int length) {
        return floatArray(length, DEFAULT_LOG_CAPACITY);
    }

    public VersionedFloatArray floatArray(int length, int logCapacity) {
        return new VersionedFloatArray(this, length, logCapacity);
    }

    public VersionedIntArray intArray(int length) {
        return intArray(length, DEFAULT_LOG_CAPACITY);
    }

    public VersionedIntArray intArray(int length, int logCapacity) {
        return new VersionedIntArray(this, length, logCapacity);
    }

    public VersionedLongArray longArray(int length) {
        return longArray(length, DEFAULT_LOG_CAPACITY);
    }

    public VersionedLongArray longArray(int length, int logCapacity) {
        return new VersionedLongArray(this, length, logCapacity);
    }

    /** A cursor for a consumer that processes changes across many objects. */
    public ChangeCursor cursor() {
        return new ChangeCursor(this);
    }
}
