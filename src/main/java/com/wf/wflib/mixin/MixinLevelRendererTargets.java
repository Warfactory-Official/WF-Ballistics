package com.wf.wflib.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.wf.wflib.client.cam.FeedTargets;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererTargets implements FeedTargets {

    @Shadow
    private RenderTarget entityTarget;
    @Shadow
    private RenderTarget translucentTarget;
    @Shadow
    private RenderTarget itemEntityTarget;
    @Shadow
    private RenderTarget particlesTarget;
    @Shadow
    private RenderTarget weatherTarget;
    @Shadow
    private RenderTarget cloudsTarget;
    @Shadow
    private PostChain transparencyChain;

    @Override
    public RenderTarget[] wfCamTargets() {
        return new RenderTarget[]{this.entityTarget, this.translucentTarget, this.itemEntityTarget,
                this.particlesTarget, this.weatherTarget, this.cloudsTarget};
    }

    @Override
    public void wfCamSetTargets(RenderTarget[] targets) {
        this.entityTarget = targets[0];
        this.translucentTarget = targets[1];
        this.itemEntityTarget = targets[2];
        this.particlesTarget = targets[3];
        this.weatherTarget = targets[4];
        this.cloudsTarget = targets[5];
    }

    @Override
    public PostChain wfCamTransparencyChain() {
        return this.transparencyChain;
    }

    @Override
    public void wfCamSetTransparencyChain(PostChain chain) {
        this.transparencyChain = chain;
    }
}
