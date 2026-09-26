package com.wf.wflib.client.cam;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.KHRDebug;

/** KHR_debug groups: name feed work in apitrace / RenderDoc captures. No-op without the extension (macOS). */
final class GlMarkers {

    private static Boolean available;

    private GlMarkers() {
    }

    static void push(String name) {
        if (available()) {
            KHRDebug.glPushDebugGroup(KHRDebug.GL_DEBUG_SOURCE_APPLICATION, 0, name);
        }
    }

    static void pop() {
        if (available()) {
            KHRDebug.glPopDebugGroup();
        }
    }

    private static boolean available() {
        if (available == null) {
            available = GL.getCapabilities().GL_KHR_debug;
        }
        return available;
    }
}
