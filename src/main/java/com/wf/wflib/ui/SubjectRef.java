package com.wf.wflib.ui;

/** Bound handle to a subject for native widgets; {@link #get} is an array read. */
public final class SubjectRef<S> {
    private final Subject<S> subject;
    private final int index;

    SubjectRef(Subject<S> subject, int index) {
        this.subject = subject;
        this.index = index;
    }

    public S get(UiEval ev) {
        Object value = ev.subjects[index];
        return value == null ? null : subject.type().cast(value);
    }
}
