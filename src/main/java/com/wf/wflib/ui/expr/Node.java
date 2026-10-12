package com.wf.wflib.ui.expr;

/**
 * Typed expression tree. {@link #eval} is the reference semantics the compiler must reproduce
 * bit-for-bit; it is also the {@code -Dwflib.ui.interpret=true} fallback.
 */
public sealed interface Node {
    ExprType type();

    double eval(double[] values, double[] state);

    /** No slot reads and no state => foldable. */
    boolean constant();

    enum Op {
        NEG, NOT,
        ADD, SUB, MUL, DIV, MOD, POW,
        LT, LE, GT, GE, EQ, NE,
        AND, OR
    }

    record Const(double value, ExprType type) implements Node {
        @Override
        public double eval(double[] values, double[] state) {
            return value;
        }

        @Override
        public boolean constant() {
            return true;
        }
    }

    record Slot(int index, ExprType type, String name) implements Node {
        @Override
        public double eval(double[] values, double[] state) {
            return values[index];
        }

        @Override
        public boolean constant() {
            return false;
        }
    }

    record Unary(Op op, Node operand) implements Node {
        @Override
        public ExprType type() {
            return op == Op.NOT ? ExprType.BOOL : ExprType.NUM;
        }

        @Override
        public double eval(double[] values, double[] state) {
            double x = operand.eval(values, state);
            return op == Op.NOT ? (x != 0 ? 0 : 1) : -x;
        }

        @Override
        public boolean constant() {
            return operand.constant();
        }
    }

    record Binary(Op op, Node left, Node right) implements Node {
        @Override
        public ExprType type() {
            return switch (op) {
                case ADD, SUB, MUL, DIV, MOD, POW -> ExprType.NUM;
                default -> ExprType.BOOL;
            };
        }

        @Override
        public double eval(double[] values, double[] state) {
            if (op == Op.AND) {
                return left.eval(values, state) != 0 && right.eval(values, state) != 0 ? 1 : 0;
            }
            if (op == Op.OR) {
                return left.eval(values, state) != 0 || right.eval(values, state) != 0 ? 1 : 0;
            }
            double l = left.eval(values, state);
            double r = right.eval(values, state);
            return switch (op) {
                case ADD -> l + r;
                case SUB -> l - r;
                case MUL -> l * r;
                case DIV -> l / r;
                case MOD -> ExprRuntime.mod(l, r);
                case POW -> Math.pow(l, r);
                case LT -> l < r ? 1 : 0;
                case LE -> l <= r ? 1 : 0;
                case GT -> l > r ? 1 : 0;
                case GE -> l >= r ? 1 : 0;
                case EQ -> l == r ? 1 : 0;
                case NE -> l != r ? 1 : 0;
                default -> throw new IllegalStateException(op.name());
            };
        }

        @Override
        public boolean constant() {
            return left.constant() && right.constant();
        }
    }

    record Ternary(Node condition, Node then, Node otherwise) implements Node {
        @Override
        public ExprType type() {
            return then.type();
        }

        @Override
        public double eval(double[] values, double[] state) {
            return condition.eval(values, state) != 0 ? then.eval(values, state) : otherwise.eval(values, state);
        }

        @Override
        public boolean constant() {
            return condition.constant() && then.constant() && otherwise.constant();
        }
    }

    /** {@code cell} = first state cell, -1 for stateless functions. */
    final class Call implements Node {
        final Fn fn;
        final Node[] args;
        final int cell;
        private final double[] scratch;

        Call(Fn fn, Node[] args, int cell) {
            this.fn = fn;
            this.args = args;
            this.cell = cell;
            this.scratch = new double[args.length];
        }

        public Fn fn() {
            return fn;
        }

        @Override
        public ExprType type() {
            return fn.result;
        }

        @Override
        public double eval(double[] values, double[] state) {
            for (int i = 0; i < args.length; i++) {
                scratch[i] = args[i].eval(values, state);
            }
            return fn.apply(scratch, values, state, cell);
        }

        @Override
        public boolean constant() {
            if (fn.kind != Fn.Kind.PURE) {
                return false;
            }
            for (Node arg : args) {
                if (!arg.constant()) {
                    return false;
                }
            }
            return true;
        }
    }
}
