package com.wf.wflib.ui;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;

/**
 * Named read of a subject. Getters run on the render thread once per frame per instance that
 * references them (lists: once per frame; item signals: once per element per pass) and must not
 * allocate in steady state. A missing subject reads NaN / false / "" / empty.
 */
public sealed interface Signal<S> {
    Subject<S> subject();

    String name();

    default String fullName() {
        return subject().name() + "." + name();
    }

    record Num<S>(Subject<S> subject, String name, ToDoubleFunction<? super S> getter) implements Signal<S> {
    }

    record Bool<S>(Subject<S> subject, String name, Predicate<? super S> getter) implements Signal<S> {
    }

    /**
     * One of {@code names}, read as its index (out of range / missing subject => NaN). Expressions may name
     * a value as {@code subject.signal.value}; templates print it as {@code {subject.signal}} or translated
     * with {@code {subject.signal|tr:key.prefix.}}.
     */
    record Choice<S>(Subject<S> subject, String name, List<String> names, ToIntFunction<? super S> getter)
            implements Signal<S> {
    }

    /** {@code maxLength}: longest text the getter returns; template buffers are sized from it once. */
    record Text<S>(Subject<S> subject, String name, int maxLength, Function<? super S, ? extends CharSequence> getter)
            implements Signal<S> {
    }

    /** Elements are bound to {@code item} inside a {@code repeat}. */
    record Items<S, E>(Subject<S> subject, String name, Function<? super S, ? extends List<? extends E>> getter,
                       Subject<E> item) implements Signal<S> {
    }
}
