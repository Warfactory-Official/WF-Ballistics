package com.wf.wflib.ui;

import com.wf.wflib.ui.expr.CompiledExpr;

public final class NumProp {
    private final CompiledExpr expr;
    private final boolean constant;
    private final double value;

    NumProp(CompiledExpr expr, boolean constant, double value) {
        this.expr = expr;
        this.constant = constant;
        this.value = value;
    }

    public static NumProp of(double value) {
        return new NumProp((v, s) -> value, true, value);
    }

    public double get(UiEval ev) {
        return constant ? value : expr.eval(ev.values, ev.state);
    }

    public float getFloat(UiEval ev) {
        return (float) get(ev);
    }

    public boolean isConstant() {
        return constant;
    }
}
