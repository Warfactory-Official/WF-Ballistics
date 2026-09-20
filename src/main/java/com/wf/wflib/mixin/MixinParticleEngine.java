package com.wf.wflib.mixin;

import com.wf.wflib.client.cam.FeedPass;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

/** No particles on a thermal feed. */
@Mixin(ParticleEngine.class)
public class MixinParticleEngine {

    @Inject(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            at = @At("HEAD"), cancellable = true)
    private void wfCamSkipParticlesInThermal(LightTexture lightTexture, Camera camera, float partialTick,
                                             Frustum frustum, Predicate<ParticleRenderType> filter,
                                             CallbackInfo ci) {
        if (FeedPass.thermal()) {
            ci.cancel();
        }
    }
}
