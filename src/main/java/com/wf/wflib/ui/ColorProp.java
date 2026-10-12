package com.wf.wflib.ui;

import com.wf.wflib.ui.expr.CompiledExpr;

/** ARGB from a numeric expression ({@code #RRGGBB}, {@code rgb(..)}, ...); NaN => transparent. */
public final class ColorProp {
    private final CompiledExpr expr;
    private final boolean constant;
    private final int value;

    ColorProp(CompiledExpr expr, boolean constant, int value) {
        this.expr = expr;
        this.constant = constant;
        this.value = value;
    }

    public static ColorProp of(int argb) {
        return new ColorProp((v, s) -> argb & 0xFFFFFFFFL, true, argb);
    }

    public int get(UiEval ev) {
        return constant ? value : toArgb(expr.eval(ev.values, ev.state));
    }

    static int toArgb(double value) {
        return Double.isNaN(value) ? 0 : (int) (long) value;
    }
}
