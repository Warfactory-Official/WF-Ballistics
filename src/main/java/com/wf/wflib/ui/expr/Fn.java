package com.wf.wflib.ui.expr;

import java.util.HashMap;
import java.util.Map;

/** Built-in functions. {@code min}/{@code max} accept 2+ arguments (folded pairwise by the parser). */
public enum Fn {
    ABS("abs", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "abs", a -> Math.abs(a[0])),
    SIGN("sign", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "signum", a -> Math.signum(a[0])),
    FLOOR("floor", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "floor", a -> Math.floor(a[0])),
    CEIL("ceil", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "ceil", a -> Math.ceil(a[0])),
    TRUNC("trunc", 1, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "trunc", a -> ExprRuntime.trunc(a[0])),
    ROUND("round", 1, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "round", a -> ExprRuntime.round(a[0])),
    FRACT("fract", 1, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "fract", a -> ExprRuntime.fract(a[0])),
    SQRT("sqrt", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "sqrt", a -> Math.sqrt(a[0])),
    SIN("sin", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "sin", a -> Math.sin(a[0])),
    COS("cos", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "cos", a -> Math.cos(a[0])),
    TAN("tan", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "tan", a -> Math.tan(a[0])),
    ASIN("asin", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "asin", a -> Math.asin(a[0])),
    ACOS("acos", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "acos", a -> Math.acos(a[0])),
    ATAN("atan", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "atan", a -> Math.atan(a[0])),
    EXP("exp", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "exp", a -> Math.exp(a[0])),
    LOG("log", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "log", a -> Math.log(a[0])),
    DEG("deg", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "toDegrees", a -> Math.toDegrees(a[0])),
    RAD("rad", 1, Kind.PURE, ExprType.NUM, "java/lang/Math", "toRadians", a -> Math.toRadians(a[0])),
    ISNAN("isnan", 1, Kind.PURE, ExprType.BOOL, "com/wf/wflib/ui/expr/ExprRuntime", "isnan", a -> ExprRuntime.isnan(a[0])),
    ATAN2("atan2", 2, Kind.PURE, ExprType.NUM, "java/lang/Math", "atan2", a -> Math.atan2(a[0], a[1])),
    POW("pow", 2, Kind.PURE, ExprType.NUM, "java/lang/Math", "pow", a -> Math.pow(a[0], a[1])),
    HYPOT("hypot", 2, Kind.PURE, ExprType.NUM, "java/lang/Math", "hypot", a -> Math.hypot(a[0], a[1])),
    MIN("min", 2, Kind.PURE, ExprType.NUM, "java/lang/Math", "min", a -> Math.min(a[0], a[1])),
    MAX("max", 2, Kind.PURE, ExprType.NUM, "java/lang/Math", "max", a -> Math.max(a[0], a[1])),
    MOD("mod", 2, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "mod", a -> ExprRuntime.mod(a[0], a[1])),
    STEP("step", 2, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "step", a -> ExprRuntime.step(a[0], a[1])),
    NZ("nz", 2, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "nz", a -> ExprRuntime.nz(a[0], a[1])),
    ALPHA("alpha", 2, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "alpha", a -> ExprRuntime.alpha(a[0], a[1])),
    CLAMP("clamp", 3, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "clamp", a -> ExprRuntime.clamp(a[0], a[1], a[2])),
    LERP("lerp", 3, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "lerp", a -> ExprRuntime.lerp(a[0], a[1], a[2])),
    INVLERP("invlerp", 3, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "invlerp", a -> ExprRuntime.invlerp(a[0], a[1], a[2])),
    SMOOTHSTEP("smoothstep", 3, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "smoothstep", a -> ExprRuntime.smoothstep(a[0], a[1], a[2])),
    WRAP("wrap", 3, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "wrap", a -> ExprRuntime.wrap(a[0], a[1], a[2])),
    RGB("rgb", 3, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "rgb", a -> ExprRuntime.rgb(a[0], a[1], a[2])),
    MIXC("mixc", 3, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "mixc", a -> ExprRuntime.mixc(a[0], a[1], a[2])),
    RGBA("rgba", 4, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "rgba", a -> ExprRuntime.rgba(a[0], a[1], a[2], a[3])),
    REMAP("remap", 5, Kind.PURE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "remap", a -> ExprRuntime.remap(a[0], a[1], a[2], a[3], a[4])),
    BLINK("blink", 1, Kind.TIME, ExprType.BOOL, "com/wf/wflib/ui/expr/ExprRuntime", "blink", null),
    SMOOTH("smooth", 2, Kind.STATE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "smooth", null),
    RATE("rate", 1, Kind.STATE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "rate", null),
    SINCE("since", 1, Kind.STATE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "since", null),
    AGE("age", 1, Kind.STATE, ExprType.NUM, "com/wf/wflib/ui/expr/ExprRuntime", "age", null);

    /** PURE: f(args). TIME: f(args, values[0]). STATE: f(state, cell, args, values[1]). */
    public enum Kind {
        PURE,
        TIME,
        STATE
    }

    @FunctionalInterface
    interface Pure {
        double apply(double[] args);
    }


    private static final Map<String, Fn> BY_NAME = new HashMap<>();

    static {
        for (Fn fn : values()) {
            BY_NAME.put(fn.id, fn);
        }
    }

    final String id;
    final int arity;
    final Kind kind;
    final ExprType result;
    final String owner;
    final String method;
    final Pure pure;

    Fn(String id, int arity, Kind kind, ExprType result, String owner, String method, Pure pure) {
        this.id = id;
        this.arity = arity;
        this.kind = kind;
        this.result = result;
        this.owner = owner;
        this.method = method;
        this.pure = pure;
    }

    public static Fn byName(String name) {
        return BY_NAME.get(name);
    }

    int stateCells() {
        return switch (this) {
            case RATE, AGE -> 2;
            case SMOOTH, SINCE -> 1;
            default -> 0;
        };
    }

    String descriptor() {
        StringBuilder d = new StringBuilder("(");
        if (kind == Kind.STATE) {
            d.append("[DI");
        }
        d.append("D".repeat(arity));
        if (kind != Kind.PURE) {
            d.append('D');
        }
        return d.append(")D").toString();
    }

    double apply(double[] args, double[] values, double[] state, int cell) {
        return switch (kind) {
            case PURE -> pure.apply(args);
            case TIME -> ExprRuntime.blink(args[0], values[0]);
            case STATE -> switch (this) {
                case SMOOTH -> ExprRuntime.smooth(state, cell, args[0], args[1], values[1]);
                case RATE -> ExprRuntime.rate(state, cell, args[0], values[1]);
                case SINCE -> ExprRuntime.since(state, cell, args[0], values[1]);
                default -> ExprRuntime.age(state, cell, args[0], values[1]);
            };
        };
    }
}
