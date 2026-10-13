package com.wf.wflib.ui.input;

/**
 * Receiver of {@link UiInput} events for boxes it {@link UiInput#hit}s. Coordinates are local units
 * relative to the box's top-left (NaN when a capture is cancelled); {@code item} = the hit's item.
 */
public interface Interactive {
    default void press(Object item, float x, float y) {
    }

    /** Pointer moved or held while captured; may lie outside the box. */
    default void drag(Object item, float x, float y) {
    }

    /** {@code inside} => released over the same hit: a click. */
    default void release(Object item, float x, float y, boolean inside) {
    }

    /** Secondary button pressed over the box (no capture). */
    default void secondary(Object item, float x, float y) {
    }

    /** True = consumed; otherwise passes to the next hit below the pointer. */
    default boolean wheel(Object item, float amount) {
        return false;
    }
}
