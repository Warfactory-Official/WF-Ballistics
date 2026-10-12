package com.wf.wflib.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiled form of a {@link UiLayout}: slot tables, signal bindings per scope, and the compiled
 * properties the {@link Binder} replays for every further instance. Scope 0 = once per frame; each
 * {@code repeat} opens a scope evaluated once per element.
 */
final class LayoutPlan {
    static final int TIME = 0;
    static final int DT = 1;

    final UiLayout layout;
    final Binder binder;

    final List<Subject<?>> subjects = new ArrayList<>();
    private final Map<Subject<?>, Integer> subjectIndex = new HashMap<>();
    final List<Boolean> contextSubject = new ArrayList<>();

    int numSlots = 2;
    int textSlots;
    int listSlots;
    int stateCells;

    private final Map<String, Integer> bound = new HashMap<>();
    private final List<List<Binding>> scopeBindings = new ArrayList<>();

    private Binding[][] scopes;
    private int[] rootSubjects;
    private boolean built;

    record Binding(Signal<?> signal, int slot, int subject) {
    }

    LayoutPlan(UiLayout layout) {
        this.layout = layout;
        this.binder = new Binder(this);
        scopeBindings.add(new ArrayList<>());
    }

    UiInstance instantiate() {
        Widget root;
        try {
            root = binder.bind(layout.root());
        } catch (RuntimeException e) {
            throw new IllegalStateException("Layout " + layout.id() + ": " + e.getMessage(), e);
        }
        if (!built) {
            scopes = new Binding[scopeBindings.size()][];
            for (int i = 0; i < scopes.length; i++) {
                scopes[i] = scopeBindings.get(i).toArray(Binding[]::new);
            }
            int roots = 0;
            for (boolean b : contextSubject) {
                if (b) {
                    roots++;
                }
            }
            rootSubjects = new int[roots];
            for (int i = 0, n = 0; i < contextSubject.size(); i++) {
                if (contextSubject.get(i)) {
                    rootSubjects[n++] = i;
                }
            }
            built = true;
        }
        double[] state = new double[stateCells];
        Arrays.fill(state, Double.NaN);
        UiEval ev = new UiEval(this, new double[numSlots], state, new CharSequence[textSlots], new List<?>[listSlots],
                new Object[subjects.size()]);
        return new UiInstance(this, root, ev);
    }

    int subject(Subject<?> subject, boolean fromContext) {
        Integer index = subjectIndex.get(subject);
        if (index == null) {
            index = subjects.size();
            subjects.add(subject);
            subjectIndex.put(subject, index);
            contextSubject.add(fromContext);
        } else if (fromContext && !contextSubject.get(index)) {
            contextSubject.set(index, true);
        }
        return index;
    }

    int newScope() {
        scopeBindings.add(new ArrayList<>());
        return scopeBindings.size() - 1;
    }

    /** Slot for {@code signal} evaluated in {@code scope}; one binding per pair. */
    int bind(Signal<?> signal, int scope, int subject) {
        String key = scope + ":" + signal.fullName();
        Integer existing = bound.get(key);
        if (existing != null) {
            return existing;
        }
        int slot = switch (signal) {
            case Signal.Num<?> n -> numSlots++;
            case Signal.Bool<?> b -> numSlots++;
            case Signal.Choice<?> c -> numSlots++;
            case Signal.Text<?> t -> textSlots++;
            case Signal.Items<?, ?> i -> listSlots++;
        };
        scopeBindings.get(scope).add(new Binding(signal, slot, subject));
        bound.put(key, slot);
        return slot;
    }

    void refreshSubjects(UiEval ev, UiContext context) {
        for (int index : rootSubjects) {
            Subject<?> subject = subjects.get(index);
            Object value;
            if (subject == UiSignals.UI) {
                value = ev;
            } else {
                value = context.subject(subject);
                if (value == null) {
                    value = subject.fallback();
                }
            }
            ev.subjects[index] = value;
        }
    }

    void eval(int scope, UiEval ev) {
        for (Binding binding : scopes[scope]) {
            if (UiStats.detail) {
                long before = UiStats.allocated();
                eval(binding, ev);
                UiStats.signal(binding.signal, before);
            } else {
                eval(binding, ev);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void eval(Binding binding, UiEval ev) {
        Object subject = ev.subjects[binding.subject];
        switch (binding.signal) {
            case Signal.Num n -> ev.values[binding.slot] = subject == null ? Double.NaN
                    : n.getter().applyAsDouble(subject);
            case Signal.Bool b -> ev.values[binding.slot] = subject != null && b.getter().test(subject) ? 1 : 0;
            case Signal.Choice c -> {
                int value = subject == null ? -1 : c.getter().applyAsInt(subject);
                ev.values[binding.slot] = value >= 0 && value < c.names().size() ? value : Double.NaN;
            }
            case Signal.Text t -> ev.texts[binding.slot] = subject == null ? null
                    : (CharSequence) t.getter().apply(subject);
            case Signal.Items i -> {
                List<?> list = subject == null ? null : (List<?>) i.getter().apply(subject);
                ev.lists[binding.slot] = list == null ? List.of() : list;
            }
        }
    }
}
