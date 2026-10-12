package com.wf.wflib.ui.expr;

public final class ExprException extends RuntimeException {
    public ExprException(String message) {
        super(message);
    }

    static ExprException at(String source, int position, String message) {
        return new ExprException(message + " at " + position + " in `" + source + "`");
    }
}
