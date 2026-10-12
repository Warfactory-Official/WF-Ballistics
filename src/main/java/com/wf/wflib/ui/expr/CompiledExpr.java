package com.wf.wflib.ui.expr;

/**
 * Evaluates against a layout's value slots and its per-instance state. {@code values[0]} = seconds of
 * client time, {@code values[1]} = frame delta seconds (both written by the host every frame).
 */
@FunctionalInterface
public interface CompiledExpr {
    double eval(double[] values, double[] state);
}
