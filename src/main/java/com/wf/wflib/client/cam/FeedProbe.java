package com.wf.wflib.client.cam;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code /wffeed cull skip|evict}: forces a feed cull on the next frame and records frames
 * {@code F-1..F+2}: driver GL state vs {@code GlStateManager} cache and {@code RenderSystem} state at
 * {@code pre} (after feed passes) and {@code post} (after the player's frame), plus the main target as PNG.
 * Output: {@code <gamedir>/wflib-feedprobe/<run>/}. The forced frame carries a "wf forced cull" KHR_debug group.
 */
public final class FeedProbe {

    public enum Mode { SKIP, EVICT }

    private static final int BEFORE = 1;
    private static final int AFTER = 2;

    private static Mode pending;
    private static Mode active;
    private static long forcedFrame = Long.MIN_VALUE;
    private static Path out;
    private static int runs;

    private FeedProbe() {
    }

    public static void arm(Mode mode) {
        pending = mode;
    }

    /** @return this frame's forced cull, or null. Called at the start of the feed pass. */
    static Mode begin(long frame) {
        if (pending != null && active == null) {
            active = pending;
            pending = null;
            // Recording starts on this frame as F-1; the cull lands on the next.
            forcedFrame = frame + BEFORE;
            out = FMLPaths.GAMEDIR.get().resolve("wflib-feedprobe").resolve((++runs) + "-" + active.name().toLowerCase());
            try {
                Files.createDirectories(out);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return active != null && frame == forcedFrame ? active : null;
    }

    static void checkpoint(long frame, String phase) {
        if (active == null || frame < forcedFrame - BEFORE) {
            return;
        }
        String name = String.format("%+d-%s", frame - forcedFrame, phase);
        Map<String, String> state = capture();
        StringBuilder text = new StringBuilder();
        state.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
        try {
            Files.writeString(out.resolve(name + ".txt"), text);
            if (phase.equals("post")) {
                try (NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget())) {
                    image.writeToFile(out.resolve(name + ".png"));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (phase.equals("post") && frame >= forcedFrame + AFTER) {
            active = null;
        }
    }

    /** Driver values; {@code key!=} marks a {@code GlStateManager} cache that disagrees with the driver. */
    private static Map<String, String> capture() {
        Map<String, String> s = new LinkedHashMap<>();
        Minecraft mc = Minecraft.getInstance();
        s.put("fbo.draw", "" + GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING));
        s.put("fbo.read", "" + GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING));
        s.put("fbo.main", "" + mc.getMainRenderTarget().frameBufferId);
        s.put("viewport", ints(GL11.GL_VIEWPORT, 4));
        s.put("scissor.box", ints(GL11.GL_SCISSOR_BOX, 4));
        s.put("depth.range", floats(GL11.GL_DEPTH_RANGE, 2));
        s.put("depth.clear", floats(GL11.GL_DEPTH_CLEAR_VALUE, 1));
        s.put("blend.eq", GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB) + "/" + GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA));
        s.put("front.face", "" + GL11.glGetInteger(GL11.GL_FRONT_FACE));
        s.put("polygon.mode", ints(GL11.GL_POLYGON_MODE, 2));
        s.put("stencil.test", "" + GL11.glIsEnabled(GL11.GL_STENCIL_TEST));
        s.put("program", "" + GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        s.put("vao", "" + GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING));
        s.put("vbo", "" + GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        s.put("ibo", "" + GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING));
        s.put("srgb", "" + GL11.glIsEnabled(GL30.GL_FRAMEBUFFER_SRGB));

        cached(s, "blend", GL11.glIsEnabled(GL11.GL_BLEND), GlStateManager.BLEND.mode.enabled);
        cached(s, "blend.func", GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB) + "," + GL11.glGetInteger(GL14.GL_BLEND_DST_RGB) + ","
                        + GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA) + "," + GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA),
                GlStateManager.BLEND.srcRgb + "," + GlStateManager.BLEND.dstRgb + ","
                        + GlStateManager.BLEND.srcAlpha + "," + GlStateManager.BLEND.dstAlpha);
        cached(s, "depth.test", GL11.glIsEnabled(GL11.GL_DEPTH_TEST), GlStateManager.DEPTH.mode.enabled);
        cached(s, "depth.func", GL11.glGetInteger(GL11.GL_DEPTH_FUNC), GlStateManager.DEPTH.func);
        cached(s, "depth.mask", GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), GlStateManager.DEPTH.mask);
        cached(s, "cull", GL11.glIsEnabled(GL11.GL_CULL_FACE), GlStateManager.CULL.enable.enabled);
        cached(s, "cull.mode", GL11.glGetInteger(GL11.GL_CULL_FACE_MODE), GlStateManager.CULL.mode);
        cached(s, "poly.offset", GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL) + " " + floats(GL11.GL_POLYGON_OFFSET_FACTOR, 1)
                        + "," + floats(GL11.GL_POLYGON_OFFSET_UNITS, 1),
                GlStateManager.POLY_OFFSET.fill.enabled + " " + GlStateManager.POLY_OFFSET.factor + "," + GlStateManager.POLY_OFFSET.units);
        cached(s, "scissor", GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), GlStateManager.SCISSOR.mode.enabled);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var mask = stack.malloc(4);
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
            cached(s, "color.mask", mask.get(0) + "," + mask.get(1) + "," + mask.get(2) + "," + mask.get(3),
                    b(GlStateManager.COLOR_MASK.red) + "," + b(GlStateManager.COLOR_MASK.green) + ","
                            + b(GlStateManager.COLOR_MASK.blue) + "," + b(GlStateManager.COLOR_MASK.alpha));
        }
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        cached(s, "texture.active", active - GL13.GL_TEXTURE0, GlStateManager.activeTexture);
        for (int unit = 0; unit < GlStateManager.TEXTURE_COUNT; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            cached(s, "texture." + unit, GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), GlStateManager.TEXTURES[unit].binding);
            s.put("sampler." + unit, "" + GL11.glGetInteger(GL33.GL_SAMPLER_BINDING));
            s.put("shader.texture." + unit, "" + RenderSystem.getShaderTexture(unit));
        }
        GL13.glActiveTexture(active);

        s.put("rs.projection", RenderSystem.getProjectionMatrix().toString().replace('\n', ' '));
        s.put("rs.sorting", "" + RenderSystem.getVertexSorting());
        s.put("rs.modelview", RenderSystem.getModelViewMatrix().toString().replace('\n', ' '));
        s.put("rs.fog", RenderSystem.getShaderFogStart() + ".." + RenderSystem.getShaderFogEnd() + " "
                + RenderSystem.getShaderFogShape() + " " + java.util.Arrays.toString(RenderSystem.getShaderFogColor()));
        s.put("rs.color", java.util.Arrays.toString(RenderSystem.getShaderColor()));
        s.put("panoramic", "" + mc.gameRenderer.isPanoramicMode());
        s.put("feed.pass", "" + FeedPass.active());
        return s;
    }

    private static void cached(Map<String, String> s, String key, Object driver, Object cache) {
        String d = String.valueOf(driver instanceof Byte v ? (int) v : driver instanceof Boolean v ? b(v) : driver);
        String c = String.valueOf(cache instanceof Boolean v ? b(v) : cache);
        if (d.equals(c)) {
            s.put(key, d);
        } else {
            s.put(key + "!", "driver " + d + " cache " + c);
        }
    }

    private static int b(boolean v) {
        return v ? 1 : 0;
    }

    private static String ints(int pname, int n) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(16);
            GL11.glGetIntegerv(pname, buf);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < n; i++) {
                out.append(i == 0 ? "" : ",").append(buf.get(i));
            }
            return out.toString();
        }
    }

    private static String floats(int pname, int n) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            GL11.glGetFloatv(pname, buf);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < n; i++) {
                out.append(i == 0 ? "" : ",").append(buf.get(i));
            }
            return out.toString();
        }
    }
}
