package com.wf.wflib.ui.widget;

/** Nine reference points of a box. */
public enum Point {
    TOP_LEFT(Align.START, Align.START),
    TOP(Align.CENTER, Align.START),
    TOP_RIGHT(Align.END, Align.START),
    LEFT(Align.START, Align.CENTER),
    CENTER(Align.CENTER, Align.CENTER),
    RIGHT(Align.END, Align.CENTER),
    BOTTOM_LEFT(Align.START, Align.END),
    BOTTOM(Align.CENTER, Align.END),
    BOTTOM_RIGHT(Align.END, Align.END);

    public final Align x;
    public final Align y;

    Point(Align x, Align y) {
        this.x = x;
        this.y = y;
    }

    /** Sign pointing into the box from this point's edge: START => +1, END => -1, CENTER => 0. */
    static float inward(Align a) {
        return a == Align.START ? 1 : a == Align.END ? -1 : 0;
    }
}
