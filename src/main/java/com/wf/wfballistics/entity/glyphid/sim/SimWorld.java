package com.wf.wfballistics.entity.glyphid.sim;

import com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowField;
import org.jetbrains.annotations.Nullable;

/** Everything a {@link SimGlyphid} is allowed to know about the world, and the only way it may ask. */
public interface SimWorld {

    /**
     * Returned wherever the answer is not available: no chunk, or a column the prefetch has not been asked to fill
     * yet.
     */
    int UNKNOWN = Integer.MIN_VALUE;

    /**
     * @return surface height in a column, or {@link #UNKNOWN}.
     */
    int height(int columnX, int columnZ);

    /**
     * @return what is known about a march destination. Never null; the fields inside may be absent.
     */
    Destination destination(int x, int y, int z);

    /** The field that leads to a destination and how high its ground is. */
    record Destination(@Nullable GlyphidFlowField field, int height) {

        public static final Destination NONE = new Destination(null, UNKNOWN);
    }
}
