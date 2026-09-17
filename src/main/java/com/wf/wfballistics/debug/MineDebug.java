package com.wf.wfballistics.debug;

/**
 * Toggle for the mine overlay: the trigger volume each mine is actually watching, the shorter one a crouching
 * approach gets, and (for a directional fuse) the wedge and the way it is pointing.
 */
public final class MineDebug {

    private static volatile boolean renderAreas = false;

    private MineDebug() {
    }

    public static boolean renderAreas() {
        return renderAreas;
    }

    public static void setRenderAreas(boolean value) {
        renderAreas = value;
    }
}
