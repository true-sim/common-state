package com.truesim.common.state;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * A value computed from other versioned values, recomputed only when one of them changed, and only when read: pull
 * based, so nothing is computed for values nobody reads, and there is no dependency graph to maintain. Derived values
 * can feed other derived values.
 *
 * <pre>{@code
 * Derived<String> label = Derived.of(() -> "HP " + (int) health.get() + "/" + (int) maximum.get(), health, maximum);
 * }</pre>
 *
 * <p>Its {@link #version()} is the version of the input change that last changed its value (an equal result is not a
 * change), so derived values line up with {@link ChangeCursor} marks like any other state. List the inputs the
 * computation reads; reading others gives stale results.
 *
 * @param <T> the value type
 */
public final class Derived<T> implements Versioned {

    private final Supplier<? extends T> compute;
    private final Versioned[] inputs;
    private T value;
    private long version = -1;
    private long inputsSeen = -1;

    private Derived(Supplier<? extends T> compute, Versioned[] inputs) {
        this.compute = compute;
        this.inputs = inputs;
    }

    /** A value computed by {@code compute} from {@code inputs}. */
    @AllowAllocation("created once")
    public static <T> Derived<T> of(Supplier<? extends T> compute, Versioned... inputs) {
        return new Derived<>(Objects.requireNonNull(compute, "compute"), inputs.clone());
    }

    /** The value, recomputed first if an input changed since the last computation. */
    public T get() {
        refresh();
        return value;
    }

    @Override
    public long version() {
        refresh();
        return version;
    }

    private void refresh() {
        long newest = 0;
        for (Versioned input : inputs) {
            newest = Math.max(newest, input.version());
        }
        if (newest <= inputsSeen) {
            return;
        }
        T next = compute.get();
        inputsSeen = newest;
        if (version < 0 || !Objects.equals(next, value)) {
            value = next;
            version = newest;
        }
    }
}
