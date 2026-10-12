package com.wf.wflib.ui;

/** Supplies subjects for one instance; null => subject's fallback, else absent. */
@FunctionalInterface
public interface UiContext {
    UiContext EMPTY = subject -> null;

    Object subject(Subject<?> subject);
}
