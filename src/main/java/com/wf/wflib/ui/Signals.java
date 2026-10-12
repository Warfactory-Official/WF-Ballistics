package com.wf.wflib.ui;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/** Global signal registry; names unique, duplicates refused. Register during client setup. */
public final class Signals {
    private static final Map<String, Signal<?>> BY_NAME = new HashMap<>();

    private Signals() {
    }

    public static <S> Signal.Num<S> num(Subject<S> subject, String name, ToDoubleFunction<? super S> getter) {
        return add(new Signal.Num<>(subject, name, getter));
    }

    public static <S> Signal.Bool<S> bool(Subject<S> subject, String name, Predicate<? super S> getter) {
        return add(new Signal.Bool<>(subject, name, getter));
    }

    /** {@code names}: lower_snake, index = value. */
    public static <S> Signal.Choice<S> choice(Subject<S> subject, String name, List<String> names,
                                              ToIntFunction<? super S> getter) {
        for (String value : names) {
            if (!value.matches("[a-z_][a-z0-9_]*")) {
                throw new IllegalArgumentException("Choice value must be lower_snake: " + value);
            }
        }
        return add(new Signal.Choice<>(subject, name, List.copyOf(names), getter));
    }

    /** Names = lower-cased constants. */
    public static <S, E extends Enum<E>> Signal.Choice<S> choice(Subject<S> subject, String name, Class<E> type,
                                                                 java.util.function.Function<? super S, E> getter) {
        E[] values = type.getEnumConstants();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].name().toLowerCase(java.util.Locale.ROOT);
        }
        return choice(subject, name, List.of(names), s -> {
            E value = getter.apply(s);
            return value == null ? -1 : value.ordinal();
        });
    }

    /** Longer texts are cut to {@code maxLength} when drawn. */
    public static <S> Signal.Text<S> text(Subject<S> subject, String name, int maxLength,
                                          Function<? super S, ? extends CharSequence> getter) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength " + maxLength + " for " + subject.name() + "." + name);
        }
        return add(new Signal.Text<>(subject, name, maxLength, getter));
    }

    public static <S, E> Signal.Items<S, E> items(Subject<S> subject, String name,
                                                  Function<? super S, ? extends List<? extends E>> getter,
                                                  Subject<E> item) {
        return add(new Signal.Items<>(subject, name, getter, item));
    }

    static void registerPresence(Subject<?> subject) {
        add(new Signal.Bool<>(subject, "present", s -> true));
    }

    public static synchronized Signal<?> byName(String fullName) {
        return BY_NAME.get(fullName);
    }

    private static synchronized <T extends Signal<?>> T add(T signal) {
        if (!signal.name().matches("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)*")) {
            throw new IllegalArgumentException("Signal name must be lower_snake (dots allowed): " + signal.name());
        }
        if (BY_NAME.putIfAbsent(signal.fullName(), signal) != null) {
            throw new IllegalStateException("Signal already registered: " + signal.fullName());
        }
        return signal;
    }
}
