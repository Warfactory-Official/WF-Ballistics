package com.wf.wflib.ui.expr;

/** Name resolution for one compile; implemented by the layout binder. */
public interface Symbols {
    /** Dotted name => slot or constant; null => unknown. */
    Node resolve(String name);

    /** Reserves {@code count} state cells initialised to NaN; refuses where state is not allowed. */
    int allocateState(int count, String function);
}
