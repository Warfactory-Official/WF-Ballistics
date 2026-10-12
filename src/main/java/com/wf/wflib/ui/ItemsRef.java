package com.wf.wflib.ui;

import java.util.List;

/** Bound list signal; {@link #item} is the subject each element becomes inside its scope. */
public final class ItemsRef<E> {
    private final int slot;
    private final Subject<E> item;
    private final int itemIndex;

    ItemsRef(int slot, Subject<E> item, int itemIndex) {
        this.slot = slot;
        this.item = item;
        this.itemIndex = itemIndex;
    }

    public List<?> get(UiEval ev) {
        return ev.lists[slot];
    }

    public Subject<E> item() {
        return item;
    }

    int itemIndex() {
        return itemIndex;
    }
}
