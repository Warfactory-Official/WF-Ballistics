package com.wf.wflib.ui.expr;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser with type checking and constant folding.
 *
 * <pre>
 * expr    := or ('?' expr ':' expr)?
 * or      := and ('||' and)*
 * and     := cmp ('&&' cmp)*
 * cmp     := add (('<' | '<=' | '>' | '>=' | '==' | '!=') add)?
 * add     := mul (('+' | '-') mul)*
 * mul     := unary (('*' | '/' | '%') unary)*
 * unary   := ('-' | '!') unary | power
 * power   := primary ('^' unary)?
 * primary := number unit? | '#'hex6 | '#'hex8 | true | false | pi | name ('(' args ')')? | '(' expr ')'
 * unit    := 'px' | 'gu' | 'vw' | 'vh'
 * name    := ident ('.' ident)*
 * </pre>
 *
 * {@code %} is floored modulo. {@code #RRGGBB} = opaque ARGB, {@code #AARRGGBB} literal. Units: {@code px}
 * framebuffer pixels ({@code n * ui.px}), {@code gu} host units / GUI pixels ({@code n / ui.scale}),
 * {@code vw}/{@code vh} percent of the box ({@code n * ui.width / 100}).
 */
public final class ExprParser {
    private final String source;
    private final Symbols symbols;
    private int pos;

    private ExprParser(String source, Symbols symbols) {
        this.source = source;
        this.symbols = symbols;
    }

    public static Node parse(String source, Symbols symbols) {
        ExprParser parser = new ExprParser(source, symbols);
        Node node = parser.expression();
        parser.skipSpace();
        if (parser.pos != source.length()) {
            throw parser.error("unexpected `" + source.charAt(parser.pos) + "`");
        }
        return node;
    }

    public static Node parse(String source, Symbols symbols, ExprType expected) {
        Node node = parse(source, symbols);
        if (node.type() != expected) {
            throw new ExprException("`" + source + "` is " + node.type() + ", expected " + expected);
        }
        return node;
    }

    private ExprException error(String message) {
        return ExprException.at(source, pos, message);
    }

    private Node expression() {
        Node condition = or();
        if (!accept('?')) {
            return condition;
        }
        require(condition, ExprType.BOOL, "condition");
        Node then = expression();
        expect(':');
        Node otherwise = expression();
        if (then.type() != otherwise.type()) {
            throw error("ternary branches differ: " + then.type() + " vs " + otherwise.type());
        }
        return fold(new Node.Ternary(condition, then, otherwise));
    }

    private Node or() {
        Node left = and();
        while (accept("||")) {
            left = logical(Node.Op.OR, left, and());
        }
        return left;
    }

    private Node and() {
        Node left = comparison();
        while (accept("&&")) {
            left = logical(Node.Op.AND, left, comparison());
        }
        return left;
    }

    private Node logical(Node.Op op, Node left, Node right) {
        require(left, ExprType.BOOL, op.name());
        require(right, ExprType.BOOL, op.name());
        return fold(new Node.Binary(op, left, right));
    }

    private Node comparison() {
        Node left = additive();
        Node.Op op;
        if (accept("<=")) {
            op = Node.Op.LE;
        } else if (accept(">=")) {
            op = Node.Op.GE;
        } else if (accept("==")) {
            op = Node.Op.EQ;
        } else if (accept("!=")) {
            op = Node.Op.NE;
        } else if (accept('<')) {
            op = Node.Op.LT;
        } else if (accept('>')) {
            op = Node.Op.GT;
        } else {
            return left;
        }
        Node right = additive();
        if (op == Node.Op.EQ || op == Node.Op.NE) {
            if (left.type() != right.type()) {
                throw error(op.name() + " operands differ: " + left.type() + " vs " + right.type());
            }
        } else {
            require(left, ExprType.NUM, op.name());
            require(right, ExprType.NUM, op.name());
        }
        return fold(new Node.Binary(op, left, right));
    }

    private Node additive() {
        Node left = multiplicative();
        while (true) {
            if (accept('+')) {
                left = arithmetic(Node.Op.ADD, left, multiplicative());
            } else if (peekMinus()) {
                pos++;
                left = arithmetic(Node.Op.SUB, left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private boolean peekMinus() {
        skipSpace();
        return pos < source.length() && source.charAt(pos) == '-';
    }

    private Node multiplicative() {
        Node left = unary();
        while (true) {
            if (accept('*')) {
                left = arithmetic(Node.Op.MUL, left, unary());
            } else if (accept('/')) {
                left = arithmetic(Node.Op.DIV, left, unary());
            } else if (accept('%')) {
                left = arithmetic(Node.Op.MOD, left, unary());
            } else {
                return left;
            }
        }
    }

    private Node arithmetic(Node.Op op, Node left, Node right) {
        require(left, ExprType.NUM, op.name());
        require(right, ExprType.NUM, op.name());
        return fold(new Node.Binary(op, left, right));
    }

    private Node unary() {
        if (accept('-')) {
            Node operand = unary();
            require(operand, ExprType.NUM, "negation");
            return fold(new Node.Unary(Node.Op.NEG, operand));
        }
        skipSpace();
        if (pos < source.length() && source.charAt(pos) == '!' && !source.startsWith("!=", pos)) {
            pos++;
            Node operand = unary();
            require(operand, ExprType.BOOL, "!");
            return fold(new Node.Unary(Node.Op.NOT, operand));
        }
        return power();
    }

    private Node power() {
        Node base = primary();
        if (accept('^')) {
            return arithmetic(Node.Op.POW, base, unary());
        }
        return base;
    }

    private Node primary() {
        skipSpace();
        if (pos >= source.length()) {
            throw error("unexpected end");
        }
        char c = source.charAt(pos);
        if (accept('(')) {
            Node inner = expression();
            expect(')');
            return inner;
        }
        if (c == '#') {
            return colour();
        }
        if (Character.isDigit(c) || c == '.') {
            return number();
        }
        if (Character.isLetter(c) || c == '_') {
            int start = pos;
            String name = name();
            skipSpace();
            if (pos < source.length() && source.charAt(pos) == '(') {
                pos++;
                return call(name, start);
            }
            return switch (name) {
                case "true" -> new Node.Const(1, ExprType.BOOL);
                case "false" -> new Node.Const(0, ExprType.BOOL);
                case "pi" -> new Node.Const(Math.PI, ExprType.NUM);
                default -> {
                    Node resolved = symbols.resolve(name);
                    if (resolved == null) {
                        pos = start;
                        throw error("unknown name `" + name + "`");
                    }
                    yield resolved;
                }
            };
        }
        throw error("unexpected `" + c + "`");
    }

    private Node call(String name, int start) {
        List<Node> args = new ArrayList<>();
        if (!accept(')')) {
            do {
                args.add(expression());
            } while (accept(','));
            expect(')');
        }
        Fn fn = Fn.byName(name);
        if (fn == null) {
            pos = start;
            throw error("unknown function `" + name + "`");
        }
        for (Node arg : args) {
            require(arg, fn == Fn.SINCE ? ExprType.BOOL : ExprType.NUM, name + " argument");
        }
        if ((fn == Fn.MIN || fn == Fn.MAX) && args.size() > 2) {
            Node acc = args.get(0);
            for (int i = 1; i < args.size(); i++) {
                acc = fold(new Node.Call(fn, new Node[]{acc, args.get(i)}, -1));
            }
            return acc;
        }
        if (args.size() != fn.arity) {
            pos = start;
            throw error(name + " takes " + fn.arity + " argument(s), got " + args.size());
        }
        int cell = fn.stateCells() > 0 ? symbols.allocateState(fn.stateCells(), name) : -1;
        return fold(new Node.Call(fn, args.toArray(Node[]::new), cell));
    }

    private Node colour() {
        int start = ++pos;
        while (pos < source.length() && Character.digit(source.charAt(pos), 16) >= 0) {
            pos++;
        }
        int digits = pos - start;
        if (digits != 6 && digits != 8) {
            throw error("colour needs 6 or 8 hex digits");
        }
        long value = Long.parseLong(source, start, pos, 16);
        if (digits == 6) {
            value |= 0xFF000000L;
        }
        return new Node.Const(value, ExprType.NUM);
    }

    private Node number() {
        int start = pos;
        while (pos < source.length() && (Character.isDigit(source.charAt(pos)) || source.charAt(pos) == '.')) {
            pos++;
        }
        if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
            int mark = pos++;
            if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                while (pos < source.length() && Character.isDigit(source.charAt(pos))) {
                    pos++;
                }
            } else {
                pos = mark;
            }
        }
        Node value;
        try {
            value = new Node.Const(Double.parseDouble(source.substring(start, pos)), ExprType.NUM);
        } catch (NumberFormatException e) {
            pos = start;
            throw error("bad number");
        }
        int unitAt = pos;
        while (pos < source.length() && Character.isLetter(source.charAt(pos))) {
            pos++;
        }
        if (pos == unitAt) {
            return value;
        }
        String unit = source.substring(unitAt, pos);
        return switch (unit) {
            case "px" -> fold(new Node.Binary(Node.Op.MUL, value, unitSymbol("ui.px", unitAt)));
            case "gu" -> fold(new Node.Binary(Node.Op.DIV, value, unitSymbol("ui.scale", unitAt)));
            case "vw", "vh" -> fold(new Node.Binary(Node.Op.MUL, value, new Node.Binary(Node.Op.DIV,
                    unitSymbol(unit.equals("vw") ? "ui.width" : "ui.height", unitAt), new Node.Const(100, ExprType.NUM))));
            default -> {
                pos = unitAt;
                throw error("unknown unit `" + unit + "`");
            }
        };
    }

    private Node unitSymbol(String name, int at) {
        Node node = symbols.resolve(name);
        if (node == null) {
            pos = at;
            throw error("units need `" + name + "`");
        }
        return node;
    }

    private String name() {
        int start = pos;
        while (true) {
            while (pos < source.length() && (Character.isLetterOrDigit(source.charAt(pos)) || source.charAt(pos) == '_')) {
                pos++;
            }
            if (pos + 1 < source.length() && source.charAt(pos) == '.'
                    && (Character.isLetter(source.charAt(pos + 1)) || source.charAt(pos + 1) == '_')) {
                pos++;
                continue;
            }
            return source.substring(start, pos);
        }
    }

    private static Node fold(Node node) {
        if (node.constant() && !(node instanceof Node.Const)) {
            return new Node.Const(node.eval(null, null), node.type());
        }
        return node;
    }

    private void require(Node node, ExprType type, String where) {
        if (node.type() != type) {
            throw error(where + " needs " + type + ", got " + node.type());
        }
    }

    private void skipSpace() {
        while (pos < source.length() && Character.isWhitespace(source.charAt(pos))) {
            pos++;
        }
    }

    private boolean accept(char c) {
        skipSpace();
        if (pos < source.length() && source.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean accept(String token) {
        skipSpace();
        if (source.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private void expect(char c) {
        if (!accept(c)) {
            throw error("expected `" + c + "`");
        }
    }
}
