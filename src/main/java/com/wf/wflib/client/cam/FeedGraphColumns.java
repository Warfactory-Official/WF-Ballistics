package com.wf.wflib.client.cam;

/** Per-graph flag on {@code SectionOcclusionGraph}; read on the graph's update thread. */
public interface FeedGraphColumns {

    /**
     * true => neighbours kept at any height (vanilla drops those > viewDistance*16 blocks above/below the camera).
     * Ortho spot lens sits ~250 over ground > feed bubble height.
     */
    void wflib$setColumns(boolean columns);

    boolean wflib$columns();
}
