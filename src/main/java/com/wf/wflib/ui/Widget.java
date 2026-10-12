package com.wf.wflib.ui;

/**
 * Runtime node, one per {@link UiInstance}. {@link #measure} always runs immediately before
 * {@link #draw} with the same values, so draw may reuse anything measure cached.
 */
public abstract class Widget {
    protected float width;
    protected float height;

    public final float width() {
        return width;
    }

    public final float height() {
        return height;
    }

    /** Sets {@link #width}/{@link #height}. */
    public abstract void measure(UiEval ev);

    /**
     * {@code (x, y, w, h)}: box the parent assigns; widgets that do not stretch draw their measured
     * size at its top-left.
     */
    public abstract void draw(UiEval ev, float x, float y, float w, float h);
}
