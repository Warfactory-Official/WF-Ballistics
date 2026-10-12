package com.wf.wflib.ui;

/**
 * Immutable description of a widget; {@link #create} compiles its properties through the binder and
 * returns a fresh runtime node. Implement this to add a widget type.
 */
public interface WidgetDef {
    Widget create(Binder binder);
}
