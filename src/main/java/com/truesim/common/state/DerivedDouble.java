package com.truesim.common.state;

import com.truesim.annotations.AllowAllocation;
import java.util.Objects;
import java.util.function.DoubleSupplier;

/**
 * A {@link Derived} double without boxing: recomputing allocates nothing, so it suits values derived every frame,
 * such as a health fraction driving a bar. An equal result (bit for bit) is not a change.
 */
public final class DerivedDouble implements Versioned {

    private final DoubleSupplier compute;
    private final Versioned[] inputs;
    private double value;
    private long version = -1;
    private long inputsSeen = -1;

    private DerivedDouble(DoubleSupplier compute, Versioned[] inputs) {
        this.compute = compute;
        this.inputs = inputs;
    }

    /** A value computed by {@code compute} from {@code inputs}. */
    @AllowAllocation("created once")
    public static DerivedDouble of(DoubleSupplier compute, Versioned... inputs) {
        return new DerivedDouble(Objects.requireNonNull(compute, "compute"), inputs.clone());
    }

    /** The value, recomputed first if an input changed since the last computation. */
    public double get() {
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
        double next = compute.getAsDouble();
        inputsSeen = newest;
        if (version < 0 || Double.doubleToLongBits(next) != Double.doubleToLongBits(value)) {
            value = next;
            version = newest;
        }
    }
}
