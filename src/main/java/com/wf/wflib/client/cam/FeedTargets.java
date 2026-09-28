package com.wf.wflib.client.cam;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.PostChain;
import org.jetbrains.annotations.Nullable;

/** {@code LevelRenderer}'s auxiliary framebuffers; any renderer, Sodium included. */
public interface FeedTargets {

    /** {@code {entity (outline), translucent, itemEntity, particles, weather, clouds}}. */
    RenderTarget[] wfCamTargets();

    void wfCamSetTargets(RenderTarget[] targets);

    @Nullable
    PostChain wfCamTransparencyChain();

    void wfCamSetTransparencyChain(@Nullable PostChain chain);
}
