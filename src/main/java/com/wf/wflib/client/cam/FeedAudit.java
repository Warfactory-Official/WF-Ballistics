package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.Set;

/** Checks the depth state and framebuffer the player's frame is drawn against, either side of it. */
final class FeedAudit {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** One line per distinct fault, not per frame. The first occurrence is the one that locates the bug. */
    private static final Set<String> REPORTED = new HashSet<>();
    /** What the frame was doing, so the first report of each fault carries the situation that produced it. */
    private static String context = "";

    private FeedAudit() {
    }

    /**
     * Before the player's frame, after any feed passes.
     *
     * @param rendered how many feeds drew this frame
     * @param evicted how many framebuffers were released this frame: the "FBO culled" case
     */
    static void handback(int rendered, int evicted) {
        check("pre", rendered + " feed render(s), " + evicted + " evicted");
    }

    /** After the player's frame, so anything drawn during it has had its chance. */
    static void frameEnd() {
        if (CameraFeedCache.liveTargets() > 0) {
            check("post", CameraFeedCache.liveTargets() + " live target(s)");
        }
    }

    private static void check(String phase, String when) {
        context = when;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();

        int bound = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        if (bound != main.frameBufferId) {
            report(phase, "framebuffer", "bound=" + bound + " but the main target is " + main.frameBufferId);
        } else if (main.useDepth) {
            // Only meaningful with the main target actually bound, since the query reads whatever is.
            int attached = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_FRAMEBUFFER,
                    GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (attached != main.getDepthTextureId()) {
                report(phase, "depth-attachment", "the main target's depth attachment is texture " + attached
                        + ", not its own " + main.getDepthTextureId()
                        + ": something re-attached a borrowed or recycled buffer");
            }
        }

        if (!GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) {
            report(phase, "depth-test", "GL_DEPTH_TEST is off");
        }
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        if (depthFunc != GL11.GL_LEQUAL && depthFunc != GL11.GL_LESS) {
            report(phase, "depth-func", "glDepthFunc is 0x" + Integer.toHexString(depthFunc));
        }

        RenderSystem.depthMask(true);
        if (GL11.glGetInteger(GL11.GL_DEPTH_WRITEMASK) == 0) {
            report(phase, "depth-cache-desync", "RenderSystem.depthMask(true) left GL_DEPTH_WRITEMASK at 0 "
                    + "-- GlStateManager's cache has drifted from the driver");
        }

        if (mc.gameRenderer.isPanoramicMode()) {
            report(phase, "panoramic", "panoramic mode is still set, so useShaderTransparency() is lying");
        }
        if (mc.levelRenderer instanceof FeedRenderState state && Minecraft.useShaderTransparency()) {
            // Only meaningful on a Fabulous client. On Fancy these are legitimately null and always were.
            for (RenderTarget target : state.wfCamFabulousTargets()) {
                if (target == null) {
                    report(phase, "fabulous-targets", "a Fabulous auxiliary target is null while shader "
                            + "transparency is on");
                    break;
                }
            }
        }
    }

    private static void report(String phase, String key, String what) {
        if (REPORTED.add(phase + "/" + key)) {
            LOGGER.error("drone camera audit [{} {}]: {} ({})", phase, key, what, context);
        }
    }

    static void reset() {
        REPORTED.clear();
    }
}
