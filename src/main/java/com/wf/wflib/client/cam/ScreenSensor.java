package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import com.wf.wflib.WFLib;
import com.wf.wflib.config.WFClientConfig;
import com.wf.wflib.drone.cam.CameraMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * A feed's {@link CameraMode} chain over the player's own view: vehicle sights, UAV pilot views.
 * Runs at {@code AFTER_LEVEL} => hand and GUI stay clean. Gated by {@link WFClientConfig#CAMERA_EFFECTS}.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ScreenSensor {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Nullable
    private static CameraMode mode;
    private static float link = 1.0f;

    @Nullable
    private static PostChain chain;
    @Nullable
    private static CameraMode chainMode;
    private static int width;
    private static int height;

    private ScreenSensor() {
    }

    /**
     * @param mode null => off, chain freed
     * @param link 0..1 datalink quality, as a feed's; 1 for a wired sight
     */
    public static void set(@Nullable CameraMode mode, float link) {
        ScreenSensor.mode = mode;
        ScreenSensor.link = Mth.clamp(link, 0.0f, 1.0f);
        if (mode == null) {
            close();
        }
    }

    @Nullable
    public static CameraMode mode() {
        return mode;
    }

    /** Main pass drawn under thermal. Feed passes answer {@link FeedPass#thermal()} instead. */
    public static boolean thermal() {
        return mode == CameraMode.THERMAL && WFClientConfig.CAMERA_EFFECTS.get();
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        CameraMode m = mode;
        Minecraft mc = Minecraft.getInstance();
        if (m == null || mc.level == null || !WFClientConfig.CAMERA_EFFECTS.get()) {
            close();
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        ensureChain(mc, main, m);
        if (chain == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        GlMarkers.push("wf screen sensor " + m.id());
        try {
            if (m == CameraMode.THERMAL) {
                CameraHeatMask.render(chain, main, event.getCamera(), event.getModelViewMatrix(),
                        event.getProjectionMatrix(), partial);
            }
            chain.setUniform("Degrade", CameraTarget.degrade(link, m));
            chain.setUniform("FeedTime", (mc.level.getGameTime() % 24000L) + partial);
            chain.process(partial);
        } finally {
            main.bindWrite(true);
            GlMarkers.pop();
        }
    }

    private static void ensureChain(Minecraft mc, RenderTarget main, CameraMode m) {
        if (chainMode == m) {
            if (chain != null && (main.width != width || main.height != height)) {
                width = main.width;
                height = main.height;
                chain.resize(width, height);
            }
            return;
        }
        close();
        chainMode = m;
        try {
            chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), main, m.chain());
            width = main.width;
            height = main.height;
            chain.resize(width, height);
        } catch (Exception e) {
            // Retried on the next mode change only.
            LOGGER.error("screen sensor: no usable post chain for {} ({})", m.id(), e.toString());
        }
    }

    private static void close() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        chainMode = null;
    }
}
