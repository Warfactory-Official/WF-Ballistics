package com.wf.wflib.ui.input;

/** Click handler; {@code item} = element of the innermost enclosing repeat, null outside one. */
@FunctionalInterface
public interface UiAction {
    void run(Object item);
}
