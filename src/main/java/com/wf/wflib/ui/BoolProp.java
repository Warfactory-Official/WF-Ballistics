package com.wf.wflib.ui;

import com.wf.wflib.ui.expr.CompiledExpr;

public final class BoolProp {
    public static final BoolProp TRUE = new BoolProp((v, s) -> 1, true, true);

    private final CompiledExpr expr;
    private final boolean constant;
    private final boolean value;

    BoolProp(CompiledExpr expr, boolean constant, boolean value) {
        this.expr = expr;
        this.constant = constant;
        this.value = value;
    }

    public boolean get(UiEval ev) {
        return constant ? value : expr.eval(ev.values, ev.state) != 0;
    }

    public boolean isConstant() {
        return constant;
    }
}
