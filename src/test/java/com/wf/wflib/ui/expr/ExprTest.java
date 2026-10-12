package com.wf.wflib.ui.expr;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExprTest {
    /** a, b, c: NUM slots 2..4; on: BOOL slot 5. */
    private static final class TestSymbols implements Symbols {
        int state;

        @Override
        public Node resolve(String name) {
            return switch (name) {
                case "a" -> new Node.Slot(2, ExprType.NUM, name);
                case "b" -> new Node.Slot(3, ExprType.NUM, name);
                case "v.c" -> new Node.Slot(4, ExprType.NUM, name);
                case "on" -> new Node.Slot(5, ExprType.BOOL, name);
                case "ui.px" -> new Node.Slot(2, ExprType.NUM, name);
                case "ui.scale" -> new Node.Slot(3, ExprType.NUM, name);
                case "ui.width" -> new Node.Slot(4, ExprType.NUM, name);
                case "ui.height" -> new Node.Slot(2, ExprType.NUM, name);
                default -> null;
            };
        }

        @Override
        public int allocateState(int count, String function) {
            int at = state;
            state += count;
            return at;
        }
    }

    private static double both(String source, double... slots) {
        TestSymbols symbols = new TestSymbols();
        Node node = ExprParser.parse(source, symbols);
        double[] values = new double[6];
        System.arraycopy(slots, 0, values, 0, slots.length);
        double[] s1 = new double[Math.max(symbols.state, 1)];
        double[] s2 = new double[s1.length];
        Arrays.fill(s1, Double.NaN);
        Arrays.fill(s2, Double.NaN);
        double interpreted = node.eval(values, s1);
        double compiled = ExprCompiler.compile(node).eval(values, s2);
        assertEquals(Double.doubleToLongBits(interpreted), Double.doubleToLongBits(compiled),
                () -> source + ": interpreted " + interpreted + " compiled " + compiled);
        return compiled;
    }

    @Test
    void arithmeticAndPrecedence() {
        assertEquals(7, both("1 + 2 * 3"));
        assertEquals(-8, both("-2 ^ 3"));
        assertEquals(1, both("-1 % 2"));
        assertEquals(10.5, both("a * 3 + b", 0, 0, 3, 1.5));
        assertEquals(9, both("v.c ^ 2", 0, 0, 0, 0, 3));
        assertEquals(2.5e-3, both("2.5e-3"));
    }

    @Test
    void booleansAndTernary() {
        assertEquals(1, both("a > 1 && !on", 0, 0, 2, 0, 0, 0));
        assertEquals(0, both("a > 1 && !on", 0, 0, 2, 0, 0, 1));
        assertEquals(5, both("on ? a : b", 0, 0, 5, 6, 0, 1));
        assertEquals(1, both("on == (a < b)", 0, 0, 1, 2, 0, 1));
        assertEquals(0, both("a < b", 0, 0, Double.NaN, 1));
        assertEquals(1, both("a != b", 0, 0, Double.NaN, 1));
        assertEquals(0, both("a >= b || a <= b", 0, 0, Double.NaN, 1));
    }

    @Test
    void units() {
        assertEquals(1.5, both("3px", 0, 0, 0.5));
        assertEquals(4, both("2gu", 0, 0, 0, 0.5));
        assertEquals(30, both("10vw + 1px * 0", 0, 0, 7, 0, 300));
        assertEquals(10, both("50vh / 2", 0, 0, 40));
        assertThrows(ExprException.class, () -> ExprParser.parse("3em", new TestSymbols()));
    }

    @Test
    void sinceCountsHeldSecondsAndResets() {
        double[] state = {Double.NaN};
        assertEquals(0, ExprRuntime.since(state, 0, 1, 0.5));
        assertEquals(0.5, ExprRuntime.since(state, 0, 1, 0.5));
        assertEquals(0, ExprRuntime.since(state, 0, 0, 0.5));
        assertEquals(0, ExprRuntime.since(state, 0, 1, 0.5));
        assertEquals(0.25, both("since(on)", 0, 0.25, 0, 0, 0, 1) + 0.25);
    }

    @Test
    void ageCountsSecondsSinceChange() {
        double[] state = {Double.NaN, Double.NaN};
        assertEquals(0, ExprRuntime.age(state, 0, 3, 0.1));
        assertEquals(0.1, ExprRuntime.age(state, 0, 3, 0.1), 1e-12);
        assertEquals(0, ExprRuntime.age(state, 0, 4, 0.1));
        assertEquals(0.1, ExprRuntime.age(state, 0, 4, 0.1), 1e-12);
        assertEquals(0, ExprRuntime.age(state, 0, Double.NaN, 0.1));
        assertEquals(0.1, ExprRuntime.age(state, 0, Double.NaN, 0.1), 1e-12);
    }

    @Test
    void constantsFold() {
        assertInstanceOf(Node.Const.class, ExprParser.parse("clamp(5, 0, 2) * pi", new TestSymbols()));
        assertEquals(0xFFFF8000L, (long) both("#ff8000"));
        assertEquals(0x80FF8000L, (long) both("alpha(#ff8000, 0.5)"));
        assertEquals(4, both("max(1, 4, 2)"));
    }

    @Test
    void statefulAndTime() {
        assertEquals(1, both("blink(2)", 0.5));
        assertEquals(0, both("blink(2)", 1.5));
        assertEquals(3, both("smooth(a, 1)", 0, 0.05, 3));
        assertEquals(0, both("rate(a)", 0, 0.05, 3));
    }

    @Test
    void typeErrors() {
        assertThrows(ExprException.class, () -> ExprParser.parse("on + 1", new TestSymbols()));
        assertThrows(ExprException.class, () -> ExprParser.parse("a ? 1 : 2", new TestSymbols()));
        assertThrows(ExprException.class, () -> ExprParser.parse("on ? 1 : on", new TestSymbols()));
        assertThrows(ExprException.class, () -> ExprParser.parse("nope + 1", new TestSymbols()));
        assertThrows(ExprException.class, () -> ExprParser.parse("clamp(1, 2)", new TestSymbols()));
        assertThrows(ExprException.class, () -> ExprParser.parse("1 +", new TestSymbols()));
        assertThrows(ExprException.class, () -> ExprParser.parse("#fff", new TestSymbols()));
    }

    @Test
    void randomTreesAgree() {
        Random random = new Random(42);
        for (int i = 0; i < 3000; i++) {
            String source = num(random, 4);
            double[] slots = {random.nextDouble() * 10, 0.05, random.nextGaussian() * 5, random.nextGaussian() * 5,
                    random.nextInt(4) == 0 ? Double.NaN : random.nextGaussian(), random.nextBoolean() ? 1 : 0};
            both(source, slots);
        }
    }

    private static final String[] FNS1 = {"abs", "floor", "sqrt", "sin", "round", "fract", "sign", "deg"};
    private static final String[] FNS3 = {"clamp", "lerp", "wrap", "smoothstep", "invlerp"};

    private static String num(Random r, int depth) {
        if (depth == 0 || r.nextInt(4) == 0) {
            return switch (r.nextInt(5)) {
                case 0 -> "a";
                case 1 -> "b";
                case 2 -> "v.c";
                default -> Double.toString(Math.round(r.nextGaussian() * 100) / 10.0);
            };
        }
        return switch (r.nextInt(7)) {
            case 0 -> "(" + num(r, depth - 1) + " " + "+-*/%".charAt(r.nextInt(5)) + " " + num(r, depth - 1) + ")";
            case 1 -> "-" + num(r, depth - 1);
            case 2 -> FNS1[r.nextInt(FNS1.length)] + "(" + num(r, depth - 1) + ")";
            case 3 -> FNS3[r.nextInt(FNS3.length)] + "(" + num(r, depth - 1) + ", " + num(r, depth - 1) + ", "
                    + num(r, depth - 1) + ")";
            case 4 -> "(" + bool(r, depth - 1) + " ? " + num(r, depth - 1) + " : " + num(r, depth - 1) + ")";
            case 5 -> "max(" + num(r, depth - 1) + ", " + num(r, depth - 1) + ", " + num(r, depth - 1) + ")";
            default -> "(" + num(r, depth - 1) + " ^ 2)";
        };
    }

    private static String bool(Random r, int depth) {
        if (depth == 0 || r.nextInt(4) == 0) {
            return r.nextBoolean() ? "on" : "(" + num(r, 0) + " < " + num(r, 0) + ")";
        }
        return switch (r.nextInt(5)) {
            case 0 -> "(" + bool(r, depth - 1) + " && " + bool(r, depth - 1) + ")";
            case 1 -> "(" + bool(r, depth - 1) + " || " + bool(r, depth - 1) + ")";
            case 2 -> "!" + bool(r, depth - 1);
            case 3 -> "(" + num(r, depth - 1) + " " + new String[]{"<", "<=", ">", ">=", "==", "!="}[r.nextInt(6)] + " "
                    + num(r, depth - 1) + ")";
            default -> "isnan(" + num(r, depth - 1) + ")";
        };
    }
}
