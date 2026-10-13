package com.wf.wflib.ui.input;

/** Value handler (slider fraction 0..1); {@code item} as for {@link UiAction}. */
@FunctionalInterface
public interface UiSetter {
    void set(Object item, double value);
}
