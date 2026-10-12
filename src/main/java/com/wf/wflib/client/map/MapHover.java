package com.wf.wflib.client.map;

/** Cursor over a shape. Two shapes under one cursor both get events: {@code exit} may follow another's {@code enter}. */
public interface MapHover {

    void enter();

    void exit();
}
