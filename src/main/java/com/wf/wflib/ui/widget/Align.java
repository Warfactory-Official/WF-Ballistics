package com.wf.wflib.ui.widget;

public enum Align {
    START,
    CENTER,
    END;

    /** Distance from a box's start to this reference point. */
    public float pivot(float size) {
        return switch (this) {
            case START -> 0;
            case CENTER -> size * 0.5f;
            case END -> size;
        };
    }

    public float offset(float space, float size) {
        return switch (this) {
            case START -> 0;
            case CENTER -> (space - size) * 0.5f;
            case END -> space - size;
        };
    }
}
