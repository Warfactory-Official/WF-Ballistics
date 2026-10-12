package com.wf.wflib.ui;

import com.wf.wflib.ui.expr.CompiledExpr;
import com.wf.wflib.ui.expr.ExprCompiler;
import com.wf.wflib.ui.expr.ExprException;
import com.wf.wflib.ui.expr.ExprParser;
import com.wf.wflib.ui.expr.ExprType;
import com.wf.wflib.ui.expr.Node;
import com.wf.wflib.ui.expr.Symbols;
import net.minecraft.locale.Language;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Compiles widget properties for one {@link LayoutPlan}. The first instance compiles and records
 * every result in call order; later instances replay the record, so {@link WidgetDef#create} must
 * request the same properties in the same order every time.
 */
public final class Binder implements Symbols {
    private final LayoutPlan plan;
    private final List<Object> record = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private int cursor;
    private boolean replaying;

    private int[] scopeIds = new int[8];
    private int[] scopeSubjects = new int[8];
    private int[] scopeIndexSlots = new int[8];
    private int depth;

    Binder(LayoutPlan plan) {
        this.plan = plan;
    }

    Widget bind(WidgetDef root) {
        cursor = 0;
        depth = 0;
        Widget widget = root.create(this);
        if (replaying && cursor != record.size()) {
            throw new IllegalStateException("bind replay requested " + cursor + " of " + record.size() + " entries");
        }
        replaying = true;
        return widget;
    }

    public Widget child(WidgetDef def) {
        return def.create(this);
    }

    @SuppressWarnings("unchecked")
    private <T> T memo(String key, Supplier<T> compute) {
        if (replaying) {
            if (cursor >= record.size() || !keys.get(cursor).equals(key)) {
                throw new IllegalStateException("bind replay diverged at " + cursor + ": " + key);
            }
            return (T) record.get(cursor++);
        }
        T value = compute.get();
        record.add(value);
        keys.add(key);
        cursor++;
        return value;
    }

    public NumProp num(String source) {
        return memo("num:" + source, () -> {
            Node node = ExprParser.parse(source, this, ExprType.NUM);
            return node instanceof Node.Const c ? NumProp.of(c.value())
                    : new NumProp(ExprCompiler.compile(node), false, 0);
        });
    }

    public NumProp num(double value) {
        return memo("num=" + value, () -> NumProp.of(value));
    }

    public BoolProp bool(String source) {
        return memo("bool:" + source, () -> {
            Node node = ExprParser.parse(source, this, ExprType.BOOL);
            return node instanceof Node.Const c ? new BoolProp((v, s) -> c.value(), true, c.value() != 0)
                    : new BoolProp(ExprCompiler.compile(node), false, false);
        });
    }

    public ColorProp color(String source) {
        return memo("color:" + source, () -> {
            Node node = ExprParser.parse(source, this, ExprType.NUM);
            return node instanceof Node.Const c ? new ColorProp((v, s) -> c.value(), true, ColorProp.toArgb(c.value()))
                    : new ColorProp(ExprCompiler.compile(node), false, 0);
        });
    }

    public TextProp text(String template) {
        return memo("text:" + template, () -> TemplateParser.parse(template, this));
    }

    /** List signal for a {@code repeat}; the caller opens its scope with {@link #enterScope}. */
    @SuppressWarnings("unchecked")
    public <E> ItemsRef<E> items(String name) {
        return memo("items:" + name, () -> {
            Signal<?> signal = Signals.byName(name);
            if (!(signal instanceof Signal.Items<?, ?> items)) {
                throw new ExprException("`" + name + "` is not a list signal");
            }
            int subject = subjectFor(signal.subject());
            int slot = plan.bind(signal, scopeOf(signal.subject()), subject);
            return new ItemsRef<>(slot, (Subject<E>) items.item(), plan.subject(items.item(), false));
        });
    }

    public <S> SubjectRef<S> subject(Subject<S> subject) {
        return memo("subject:" + subject.name(), () -> new SubjectRef<>(subject, subjectFor(subject)));
    }

    /**
     * Opens the per-element scope for {@code items}. Inside it {@code index} (0-based) and
     * {@code count} read the iteration.
     */
    public RepeatScope enterScope(ItemsRef<?> items) {
        RepeatScope scope = memo("scope:" + items.item().name(),
                () -> new RepeatScope(plan.newScope(), plan.numSlots++, plan.numSlots++));
        if (depth == scopeIds.length) {
            scopeIds = java.util.Arrays.copyOf(scopeIds, depth * 2);
            scopeSubjects = java.util.Arrays.copyOf(scopeSubjects, depth * 2);
            scopeIndexSlots = java.util.Arrays.copyOf(scopeIndexSlots, depth * 2);
        }
        scopeIds[depth] = scope.id();
        scopeSubjects[depth] = items.itemIndex();
        scopeIndexSlots[depth] = scope.indexSlot();
        depth++;
        return scope;
    }

    public void exitScope() {
        depth--;
    }

    private int scopeOf(Subject<?> subject) {
        for (int i = depth - 1; i >= 0; i--) {
            if (plan.subjects.get(scopeSubjects[i]) == subject) {
                return scopeIds[i];
            }
        }
        return 0;
    }

    private int subjectFor(Subject<?> subject) {
        boolean item = false;
        for (int i = 0; i < depth; i++) {
            if (plan.subjects.get(scopeSubjects[i]) == subject) {
                item = true;
                break;
            }
        }
        return plan.subject(subject, !item);
    }

    /** Choice signal named {@code name}, null when it is not one. */
    Signal.Choice<?> choiceSignal(String name) {
        return Signals.byName(name) instanceof Signal.Choice<?> choice ? choice : null;
    }

    int choiceSlot(Signal.Choice<?> signal) {
        return plan.bind(signal, scopeOf(signal.subject()), subjectFor(signal.subject()));
    }

    /** Text signal named {@code name}, null when it is not one. */
    Signal.Text<?> textSignal(String name) {
        return Signals.byName(name) instanceof Signal.Text<?> text ? text : null;
    }

    int textSlot(Signal.Text<?> signal) {
        return plan.bind(signal, scopeOf(signal.subject()), subjectFor(signal.subject()));
    }

    String translate(String key) {
        return Language.getInstance().getOrDefault(key);
    }

    @Override
    public Node resolve(String name) {
        if (name.equals("ui.time")) {
            return new Node.Slot(LayoutPlan.TIME, ExprType.NUM, name);
        }
        if (name.equals("ui.dt")) {
            return new Node.Slot(LayoutPlan.DT, ExprType.NUM, name);
        }
        if (name.equals("index") || name.equals("count")) {
            if (depth == 0) {
                throw new ExprException("`" + name + "` outside a repeat");
            }
            int slot = scopeIndexSlots[depth - 1] + (name.equals("count") ? 1 : 0);
            return new Node.Slot(slot, ExprType.NUM, name);
        }
        Signal<?> signal = Signals.byName(name);
        if (signal instanceof Signal.Num<?> || signal instanceof Signal.Bool<?> || signal instanceof Signal.Choice<?>) {
            int slot = plan.bind(signal, scopeOf(signal.subject()), subjectFor(signal.subject()));
            return new Node.Slot(slot, signal instanceof Signal.Bool<?> ? ExprType.BOOL : ExprType.NUM, name);
        }
        if (signal != null) {
            throw new ExprException("`" + name + "` is a " + signal.getClass().getSimpleName().toLowerCase()
                    + " signal, not a number");
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0 && Signals.byName(name.substring(0, dot)) instanceof Signal.Choice<?> choice) {
            int index = choice.names().indexOf(name.substring(dot + 1));
            if (index < 0) {
                throw new ExprException("`" + name.substring(dot + 1) + "` is not one of " + choice.fullName() + " "
                        + choice.names());
            }
            return new Node.Const(index, ExprType.NUM);
        }
        return null;
    }

    @Override
    public int allocateState(int count, String function) {
        if (depth > 0) {
            throw new ExprException(function + "(..) keeps state and is not allowed inside a repeat");
        }
        int at = plan.stateCells;
        plan.stateCells += count;
        return at;
    }

    CompiledExpr compileNum(String source) {
        return ExprCompiler.compile(ExprParser.parse(source, this, ExprType.NUM));
    }
}
