package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.logging.LogUtils;
import com.wf.wflib.config.WFClientConfig;
import com.wf.wflib.drone.cam.CameraMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Weapon-sight picture: an {@link OffscreenView} pass into a caller-owned target, then the mode's
 * {@link CameraMode#sightChain} over it (thermal: entity heat mask from the pass's own depth). Same call rules as
 * {@link OffscreenView#render}.
 */
public final class SightSensor implements AutoCloseable {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final OffscreenView view;
    @Nullable
    private PostChain chain;
    @Nullable
    private CameraMode chainMode;
    @Nullable
    private RenderTarget chainTarget;
    private int width;
    private int height;

    public SightSensor(OffscreenView view) {
        this.view = view;
    }

    /** @param mode null or {@link CameraMode#OPTICAL} => plain picture; other arguments as {@link OffscreenView#render} */
    public boolean render(Entity anchor, Vec3 pos, float yaw, float pitch, float roll, float fovDeg,
                          RenderTarget target, DeltaTracker delta, @Nullable CameraMode mode) {
        if (mode == CameraMode.OPTICAL || !WFClientConfig.CAMERA_EFFECTS.get()) {
            mode = null;
        }
        boolean thermal = mode == CameraMode.THERMAL;
        if (!this.view.render(anchor, pos, yaw, pitch, roll, fovDeg, target, delta, mode)) {
            return false;
        }
        if (mode == null) {
            closeChain();
            return true;
        }
        ensureChain(target, mode);
        if (this.chain == null) {
            return true;
        }
        Minecraft mc = Minecraft.getInstance();
        float partial = delta.getGameTimeDeltaPartialTick(true);
        GlMarkers.push("wf sight sensor " + mode.id());
        try {
            if (thermal) {
                CameraHeatMask.render(this.chain, target, this.view.camera(), this.view.frustum(),
                        this.view.projection(), partial);
            }
            this.chain.setUniform("Degrade", 0.0f);
            this.chain.setUniform("Exposure", CameraTarget.exposure(mode, partial));
            this.chain.setUniform("FeedTime", (mc.level.getGameTime() % 24000L) + partial);
            this.chain.process(partial);
        } finally {
            mc.getMainRenderTarget().bindWrite(true);
            GlMarkers.pop();
        }
        return true;
    }

    private void ensureChain(RenderTarget target, CameraMode mode) {
        if (this.chainMode == mode && this.chainTarget == target) {
            if (this.chain != null && (target.width != this.width || target.height != this.height)) {
                this.width = target.width;
                this.height = target.height;
                this.chain.resize(this.width, this.height);
            }
            return;
        }
        closeChain();
        this.chainMode = mode;
        this.chainTarget = target;
        Minecraft mc = Minecraft.getInstance();
        try {
            this.chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), target, mode.sightChain());
            this.width = target.width;
            this.height = target.height;
            this.chain.resize(this.width, this.height);
        } catch (Exception e) {
            // Retried on the next mode or target change only.
            LOGGER.error("sight sensor: no usable post chain for {} ({})", mode.id(), e.toString());
        }
    }

    private void closeChain() {
        if (this.chain != null) {
            this.chain.close();
            this.chain = null;
        }
        this.chainMode = null;
        this.chainTarget = null;
    }

    @Override
    public void close() {
        closeChain();
        this.view.close();
    }
}
