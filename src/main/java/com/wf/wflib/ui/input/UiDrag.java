package com.wf.wflib.ui.input;

/** Pan/zoom handler of a drag area; deltas in local units, {@code item} as for {@link UiAction}. */
public interface UiDrag {
    void drag(Object item, float dx, float dy);

    /** True = consumed. */
    default boolean wheel(Object item, float amount) {
        return false;
    }
}
