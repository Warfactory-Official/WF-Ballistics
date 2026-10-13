package com.wf.wflib.mixin;

import com.wf.wflib.client.cam.FeedPass;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Ortho feed pass (satellite spot, looking down): no sky dome, no clouds between lens and ground. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRendererOrtho {
    @Inject(method = {"renderSky", "renderClouds"}, at = @At("HEAD"), cancellable = true)
    private void wflib$orthoSky(CallbackInfo ci) {
        if (FeedPass.ortho()) {
            ci.cancel();
        }
    }
}
