package com.wf.wflib.debug;

/** Mine overlay: per-mine trigger volume, crouch-shrunk volume, directional-fuse wedge + facing. */
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
