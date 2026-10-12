package com.wf.wflib.ui.json;

/** A layout/component file problem; {@code where} = file + JSON pointer. */
public final class UiJsonException extends RuntimeException {
    public UiJsonException(String where, String message) {
        super(where + ": " + message);
    }

    public UiJsonException(String where, String message, Throwable cause) {
        super(where + ": " + message, cause);
    }
}
