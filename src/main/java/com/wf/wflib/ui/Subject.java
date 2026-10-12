package com.wf.wflib.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A kind of object signals read from; its {@link #name()} is the first segment of every signal name
 * ({@code vehicle.airspeed}). The host's {@link UiContext} supplies the object each frame; a subject
 * with a {@code fallback} needs no context entry.
 */
public final class Subject<S> {
    private static final Map<String, Subject<?>> BY_NAME = new HashMap<>();
    private static final List<Subject<?>> ALL = new ArrayList<>();

    private final String name;
    private final Class<S> type;
    private final Supplier<? extends S> fallback;
    private final int ordinal;

    private Subject(String name, Class<S> type, Supplier<? extends S> fallback, int ordinal) {
        this.name = name;
        this.type = type;
        this.fallback = fallback;
        this.ordinal = ordinal;
    }

    public static synchronized <S> Subject<S> register(String name, Class<S> type) {
        return register(name, type, null);
    }

    public static synchronized <S> Subject<S> register(String name, Class<S> type, Supplier<? extends S> fallback) {
        if (!name.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("Subject name must be [a-z_][a-z0-9_]*: " + name);
        }
        if (BY_NAME.containsKey(name)) {
            throw new IllegalStateException("Subject already registered: " + name);
        }
        Subject<S> subject = new Subject<>(name, type, fallback, ALL.size());
        BY_NAME.put(name, subject);
        ALL.add(subject);
        Signals.registerPresence(subject);
        return subject;
    }

    public static synchronized Subject<?> byName(String name) {
        return BY_NAME.get(name);
    }

    static synchronized List<Subject<?>> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL));
    }

    public String name() {
        return name;
    }

    public Class<S> type() {
        return type;
    }

    int ordinal() {
        return ordinal;
    }

    Object fallback() {
        return fallback == null ? null : fallback.get();
    }

    @Override
    public String toString() {
        return "Subject[" + name + "]";
    }
}
