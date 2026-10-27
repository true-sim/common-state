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
 * and hand over results from other threads at a fixed point of the frame.
 */
public final class ChangeClock {

    private long now;

    /** The time of the latest change. */
    public long now() {
        return now;
    }

    /** Takes the next time, for a change happening now. */
    public long stamp() {
        return ++now;
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

    public <T> VersionedList<T> list() {
        return new VersionedList<>(this);
    }

    public VersionedDoubleArray doubleArray(int length) {
        return new VersionedDoubleArray(this, length);
    }

    /** A cursor for a consumer that processes changes across many objects. */
    public ChangeCursor cursor() {
        return new ChangeCursor(this);
    }
}
