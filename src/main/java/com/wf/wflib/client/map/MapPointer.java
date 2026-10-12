package com.wf.wflib.client.map;

/**
 * A mouse event on a fullscreen map, in blocks. {@code button}: GLFW mouse button, -1 = move.
 * {@code surfaceY}: the host's ground height there (its map data covers unloaded chunks), or {@link #UNKNOWN_Y}.
 */
public record MapPointer(double blockX, double blockZ, int surfaceY, int button, MapView view) {

    public static final int UNKNOWN_Y = Integer.MIN_VALUE;
}
