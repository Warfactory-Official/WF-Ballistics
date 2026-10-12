package com.wf.wflib.ui;

import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.expr.CompiledExpr;
import com.wf.wflib.ui.expr.ExprException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TemplateParser {
    private static final Pattern NUMBER_FORMAT = Pattern.compile("(\\+)?(0)?(\\d+)?(?:\\.(\\d))?");
    private static final Pattern HEX_FORMAT = Pattern.compile("0?(\\d+)?X");
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)+");

    private final List<Integer> kinds = new ArrayList<>();
    private final List<String> literals = new ArrayList<>();
    private final List<CompiledExpr> exprs = new ArrayList<>();
    private final List<Integer> slots = new ArrayList<>();
    private final List<Integer> decimals = new ArrayList<>();
    private final List<Integer> widths = new ArrayList<>();
    private final List<Boolean> zeroPad = new ArrayList<>();
    private final List<Boolean> plus = new ArrayList<>();
    private final List<Integer> cases = new ArrayList<>();
    private final List<String[]> tables = new ArrayList<>();
    private final List<KeyName> keys = new ArrayList<>();
    private int capacity;

    private TemplateParser() {
    }

    static TextProp parse(String template, Binder binder) {
        TemplateParser p = new TemplateParser();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{' && i + 1 < template.length() && template.charAt(i + 1) == '{') {
                literal.append('{');
                i += 2;
            } else if (c == '}' && i + 1 < template.length() && template.charAt(i + 1) == '}') {
                literal.append('}');
                i += 2;
            } else if (c == '{') {
                int close = template.indexOf('}', i);
                if (close < 0) {
                    throw new ExprException("unclosed `{` in template `" + template + "`");
                }
                String part = template.substring(i + 1, close).trim();
                if (part.startsWith("tr:") && part.indexOf('(') < 0) {
                    literal.append(binder.translate(part.substring(3).trim()));
                } else if (part.startsWith("tr:")) {
                    literal = p.translated(part, template, binder, literal);
                } else {
                    if (!literal.isEmpty()) {
                        p.literal(literal.toString());
                        literal.setLength(0);
                    }
                    p.part(part, template, binder);
                }
                i = close + 1;
            } else if (c == '}') {
                throw new ExprException("stray `}` in template `" + template + "`");
            } else {
                literal.append(c);
                i++;
            }
        }
        if (!literal.isEmpty() || p.kinds.isEmpty()) {
            p.literal(literal.toString());
        }
        return p.build();
    }

    /**
     * {@code tr:key(arg, arg, ...)}: the translation's {@code %s}/{@code %d}/{@code %n$s} placeholders
     * take the parts in order (or by index); {@code %%} => {@code %}. Returns the pending literal.
     */
    private StringBuilder translated(String part, String template, Binder binder, StringBuilder literal) {
        int open = part.indexOf('(');
        if (!part.endsWith(")")) {
            throw new ExprException("`" + part + "` needs a closing `)` in `" + template + "`");
        }
        String key = part.substring(3, open).trim();
        List<String> args = splitArgs(part.substring(open + 1, part.length() - 1));
        String text = binder.translate(key);
        int next = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '%' || i + 1 >= text.length()) {
                literal.append(c);
                continue;
            }
            int j = i + 1;
            int index = -1;
            int digitsEnd = j;
            while (digitsEnd < text.length() && Character.isDigit(text.charAt(digitsEnd))) {
                digitsEnd++;
            }
            if (digitsEnd > j && digitsEnd < text.length() && text.charAt(digitsEnd) == '$') {
                index = Integer.parseInt(text, j, digitsEnd, 10) - 1;
                j = digitsEnd + 1;
            }
            char conversion = j < text.length() ? text.charAt(j) : 0;
            if (conversion == '%') {
                literal.append('%');
                i = j;
                continue;
            }
            if (conversion != 's' && conversion != 'd') {
                literal.append(c);
                continue;
            }
            int arg = index >= 0 ? index : next++;
            if (arg >= args.size()) {
                throw new ExprException(key + " wants argument " + (arg + 1) + ", got " + args.size() + " in `"
                        + template + "`");
            }
            if (!literal.isEmpty()) {
                literal(literal.toString());
                literal.setLength(0);
            }
            part(args.get(arg), template, binder);
            i = j;
        }
        return literal;
    }

    /** Top-level commas only; commas inside parentheses belong to function calls. */
    private static List<String> splitArgs(String body) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(body.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (!body.isBlank()) {
            args.add(body.substring(start).trim());
        }
        return args;
    }

    private void literal(String text) {
        capacity += text.length();
        add(TextProp.LITERAL, text, null, -1, 0, 0, false, false, TextProp.CASE_NONE);
    }

    private void part(String part, String template, Binder binder) {
        if (part.startsWith("key:")) {
            KeyName key = KeyName.of(part.substring(4).trim());
            capacity += KeyName.MAX_LENGTH;
            add(TextProp.KEY, null, null, -1, 0, 0, false, false, TextProp.CASE_NONE);
            keys.set(keys.size() - 1, key);
            return;
        }
        int bar = formatSeparator(part);
        String body = (bar < 0 ? part : part.substring(0, bar)).trim();
        String format = bar < 0 ? "" : part.substring(bar + 1).trim();
        if (NAME.matcher(body).matches()) {
            Signal.Choice<?> choice = binder.choiceSignal(body);
            if (choice != null) {
                String[] table = new String[choice.names().size()];
                int longest = 0;
                for (int i = 0; i < table.length; i++) {
                    String name = choice.names().get(i);
                    table[i] = switch (format) {
                        case "" -> name;
                        case "upper" -> name.toUpperCase(java.util.Locale.ROOT);
                        default -> {
                            if (!format.startsWith("tr:")) {
                                throw new ExprException("choice format `" + format + "` in `" + template + "`");
                            }
                            yield binder.translate(format.substring(3).trim() + name);
                        }
                    };
                    longest = Math.max(longest, table[i].length());
                }
                capacity += longest;
                add(TextProp.CHOICE, null, null, binder.choiceSlot(choice), 0, 0, false, false, TextProp.CASE_NONE);
                tables.set(tables.size() - 1, table);
                return;
            }
            Signal.Text<?> signal = binder.textSignal(body);
            if (signal != null) {
                int slot = binder.textSlot(signal);
                capacity += signal.maxLength();
                int letterCase = switch (format) {
                    case "" -> TextProp.CASE_NONE;
                    case "upper" -> TextProp.CASE_UPPER;
                    case "lower" -> TextProp.CASE_LOWER;
                    default -> throw new ExprException("text format `" + format + "` in `" + template + "`");
                };
                add(TextProp.TEXT, null, null, slot, 0, 0, false, false, letterCase);
                return;
            }
        }
        CompiledExpr expr = binder.compileNum(body);
        if (format.equals("clock") || format.equals("clockh")) {
            capacity += 24;
            add(TextProp.CLOCK, null, expr, -1, format.equals("clockh") ? 1 : 0, 0, false, false, TextProp.CASE_NONE);
            return;
        }
        Matcher hex = HEX_FORMAT.matcher(format);
        if (hex.matches()) {
            int width = hex.group(1) == null ? 0 : Integer.parseInt(hex.group(1));
            capacity += Math.max(width, 16);
            add(TextProp.HEX, null, expr, -1, 0, width, true, false, TextProp.CASE_NONE);
            return;
        }
        Matcher m = NUMBER_FORMAT.matcher(format);
        if (!m.matches()) {
            throw new ExprException("number format `" + format + "` in `" + template + "`");
        }
        int decimals = m.group(4) == null ? 0 : Integer.parseInt(m.group(4));
        int width = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        capacity += TextBuffer.numberCapacity(decimals, width);
        add(TextProp.NUMBER, null, expr, -1, decimals, width, m.group(2) != null, m.group(1) != null,
                TextProp.CASE_NONE);
    }

    private static int formatSeparator(String part) {
        for (int i = part.length() - 1; i >= 0; i--) {
            if (part.charAt(i) == '|' && (i == 0 || part.charAt(i - 1) != '|')
                    && (i + 1 == part.length() || part.charAt(i + 1) != '|')) {
                return i;
            }
        }
        return -1;
    }

    private void add(int kind, String literal, CompiledExpr expr, int slot, int decimals, int width, boolean zero,
                     boolean sign, int letterCase) {
        kinds.add(kind);
        literals.add(literal);
        exprs.add(expr);
        slots.add(slot);
        this.decimals.add(decimals);
        widths.add(width);
        zeroPad.add(zero);
        plus.add(sign);
        cases.add(letterCase);
        tables.add(null);
        keys.add(null);
    }

    private TextProp build() {
        int n = kinds.size();
        int[] k = new int[n];
        int[] s = new int[n];
        int[] d = new int[n];
        int[] w = new int[n];
        boolean[] z = new boolean[n];
        boolean[] p = new boolean[n];
        int[] c = new int[n];
        for (int i = 0; i < n; i++) {
            k[i] = kinds.get(i);
            s[i] = slots.get(i);
            d[i] = decimals.get(i);
            w[i] = widths.get(i);
            z[i] = zeroPad.get(i);
            p[i] = plus.get(i);
            c[i] = cases.get(i);
        }
        return new TextProp(k, literals.toArray(String[]::new), exprs.toArray(CompiledExpr[]::new), s, d, w, z, p, c,
                tables.toArray(String[][]::new), keys.toArray(KeyName[]::new), capacity);
    }
}
