package com.truesim.common.state;

/**
 * A consumer's position on a {@link ChangeClock}, for processing changes across many objects with one number instead of
 * one watch each:
 *
 * <pre>{@code
 * long since = cursor.mark(); // everything up to here was processed last time
 * for (int i = 0; i < items.size(); i++) {
 *     if (items.version(i) > since) {
 *         redraw(i);
 *     }
 * }
 * }</pre>
 */
public final class ChangeCursor {

    private final ChangeClock clock;
    private long mark = -1;

    ChangeCursor(ChangeClock clock) {
        this.clock = clock;
    }

    /**
     * Marks the clock's current time and returns the previous mark ({@code -1} the first time): whatever has a version
     * above the returned time changed since the last mark.
     */
    public long mark() {
        long previous = mark;
        mark = clock.now();
        return previous;
    }

    /** The current mark, without moving it. */
    public long lastMark() {
        return mark;
    }
}
