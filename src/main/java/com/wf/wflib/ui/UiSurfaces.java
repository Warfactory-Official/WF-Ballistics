package com.wf.wflib.ui;

import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.CanvasTarget;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.FeedTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Layouts drawn into owned textures for in-world screens, MFDs and anything else that samples a
 * texture. A renderer {@link #request}s its surface every frame it is seen; surfaces redraw at frame
 * start, at most {@code maxFps}, and are freed {@link #IDLE_FRAMES} frames after the last request.
 * Texture colour is premultiplied by alpha; rows are upright for standard (v0 = top) UVs.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class UiSurfaces {
    static final int IDLE_FRAMES = 120;

    private static final Map<Object, Surface> SURFACES = new HashMap<>();
    private static int counter;

    private UiSurfaces() {
    }

    private static final class Surface {
        final ResourceLocation id;
        final FeedTexture texture = new FeedTexture();
        final UiInstance instance;
        final CanvasTarget target;
        UiContext context;
        float unitsW;
        float unitsH;
        int maxFps;
        int clear;
        long requestedFrame;
        long drawnNanos = Long.MIN_VALUE / 2;

        Surface(UiLayout layout, int pixelW, int pixelH) {
            id = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "ui_surface/" + counter++);
            instance = layout.instantiate();
            target = new CanvasTarget(pixelW, pixelH);
        }
    }

    /**
     * @param key        identity of the screen (block position, entity + slot, ...)
     * @param pixelWidth texture resolution; pick from on-screen size, it is re-applied each request
     * @param clearArgb  background, ARGB
     * @return texture to sample this frame (last drawn picture until the next redraw)
     */
    public static ResourceLocation request(Object key, UiLayout layout, UiContext context, int pixelWidth,
                                           int pixelHeight, float unitsWidth, float unitsHeight, int maxFps,
                                           int clearArgb) {
        Surface surface = SURFACES.get(key);
        if (surface == null || surface.instance.layout() != layout) {
            if (surface != null) {
                free(surface);
            }
            surface = new Surface(layout, pixelWidth, pixelHeight);
            Minecraft.getInstance().getTextureManager().register(surface.id, surface.texture);
            SURFACES.put(key, surface);
        }
        surface.target.resize(pixelWidth, pixelHeight);
        surface.context = context;
        surface.unitsW = unitsWidth;
        surface.unitsH = unitsHeight;
        surface.maxFps = maxFps;
        surface.clear = clearArgb;
        surface.requestedFrame = UiHud.frame();
        return surface.id;
    }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        if (SURFACES.isEmpty()) {
            return;
        }
        long frame = UiHud.frame();
        long now = System.nanoTime();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Iterator<Surface> it = SURFACES.values().iterator();
        while (it.hasNext()) {
            Surface surface = it.next();
            if (frame - surface.requestedFrame > IDLE_FRAMES) {
                free(surface);
                it.remove();
                continue;
            }
            if (surface.maxFps > 0 && now - surface.drawnNanos < 1_000_000_000L / surface.maxFps) {
                continue;
            }
            surface.drawnNanos = now;
            surface.instance.context(surface.context);
            Canvas canvas = Canvas.shared().begin(surface.target, surface.unitsW, surface.unitsH, surface.clear);
            try {
                surface.instance.render(canvas, 0, 0, surface.unitsW, surface.unitsH, partialTick);
            } finally {
                canvas.end();
            }
            surface.texture.adopt(surface.target.texture());
        }
    }

    private static void free(Surface surface) {
        Minecraft.getInstance().getTextureManager().release(surface.id);
        surface.target.close();
    }
}
