package com.wf.wflib.ui;

import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.expr.CompiledExpr;

/**
 * Compiled text template.
 *
 * <pre>
 * template := (literal | '{{' | '}}' | '{' part '}')*
 * part     := 'tr:' key                       translated once at bind
 *           | 'tr:' key '(' part (',' part)* ')'      translation's %s/%d/%n$s filled by the parts
 *           | 'key:' key.mapping.name             bound key's display name, max 32 chars
 *           | text.signal ('|' ('upper' | 'lower'))?
 *           | choice.signal ('|' ('upper' | 'tr:' key.prefix))?   value name, or translation of prefix + name
 *           | num-expr ('|' numfmt)?
 * numfmt   := '+'? '0'? width? ('.' decimals)? | '0'? width? 'X' | 'clock' | 'clockh'
 * </pre>
 *
 * No format => 0 decimals. The format separator is the last single {@code |} (not part of {@code ||}).
 * Output buffers are sized once from {@link #capacity()}; text signals declare their maximum length.
 */
public final class TextProp {
    static final int LITERAL = 0;
    static final int NUMBER = 1;
    static final int TEXT = 2;
    static final int CLOCK = 3;
    static final int HEX = 4;
    static final int CHOICE = 5;
    static final int KEY = 6;

    static final int CASE_NONE = 0;
    static final int CASE_UPPER = 1;
    static final int CASE_LOWER = 2;

    private final int[] kinds;
    private final String[] literals;
    private final CompiledExpr[] exprs;
    private final int[] slots;
    private final int[] decimals;
    private final int[] widths;
    private final boolean[] zeroPad;
    private final boolean[] plus;
    private final int[] cases;
    private final String[][] tables;
    private final KeyName[] keys;
    private final String constant;
    private final int capacity;

    TextProp(int[] kinds, String[] literals, CompiledExpr[] exprs, int[] slots, int[] decimals, int[] widths,
             boolean[] zeroPad, boolean[] plus, int[] cases, String[][] tables, KeyName[] keys, int capacity) {
        this.capacity = capacity;
        this.kinds = kinds;
        this.literals = literals;
        this.exprs = exprs;
        this.slots = slots;
        this.decimals = decimals;
        this.widths = widths;
        this.zeroPad = zeroPad;
        this.plus = plus;
        this.cases = cases;
        this.tables = tables;
        this.keys = keys;
        boolean allLiteral = true;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i] != LITERAL) {
                allLiteral = false;
                break;
            }
            text.append(literals[i]);
        }
        constant = allLiteral ? text.toString() : null;
    }

    /** Longest output: literals + number widths + declared text-signal maxima. */
    public int capacity() {
        return capacity;
    }

    /** A buffer sized for this template. */
    public TextBuffer newBuffer() {
        return new TextBuffer(capacity);
    }

    /** Non-null => template has no placeholders. */
    public String constant() {
        return constant;
    }

    public TextBuffer write(UiEval ev, TextBuffer out) {
        out.clear();
        if (constant != null) {
            return out.append(constant);
        }
        for (int i = 0; i < kinds.length; i++) {
            switch (kinds[i]) {
                case LITERAL -> out.append(literals[i]);
                case NUMBER -> out.append(exprs[i].eval(ev.values, ev.state), decimals[i], widths[i],
                        zeroPad[i] ? '0' : ' ', plus[i]);
                case CLOCK -> out.appendClock(exprs[i].eval(ev.values, ev.state), decimals[i] != 0);
                case HEX -> out.appendHex((long) exprs[i].eval(ev.values, ev.state), widths[i]);
                case CHOICE -> {
                    double value = ev.values[slots[i]];
                    if (!Double.isNaN(value)) {
                        out.append(tables[i][(int) value]);
                    }
                }
                case KEY -> {
                    String name = keys[i].get();
                    out.append(name, 0, Math.min(name.length(), KeyName.MAX_LENGTH));
                }
                default -> {
                    CharSequence text = ev.texts[slots[i]];
                    if (text == null) {
                        continue;
                    }
                    int start = out.length();
                    out.append(text);
                    if (cases[i] != CASE_NONE) {
                        char[] chars = out.array();
                        for (int c = start; c < out.length(); c++) {
                            chars[c] = cases[i] == CASE_UPPER ? Character.toUpperCase(chars[c])
                                    : Character.toLowerCase(chars[c]);
                        }
                    }
                }
            }
        }
        return out;
    }
}
