package com.wf.wflib.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.wf.wflib.client.cam.FeedPass;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Feed pass: "the main target" = the feed's framebuffer; see {@link FeedPass#target}. */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftFeedTarget {

    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void wflib$feedTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget target = FeedPass.target();
        if (target != null) {
            cir.setReturnValue(target);
        }
    }
}
