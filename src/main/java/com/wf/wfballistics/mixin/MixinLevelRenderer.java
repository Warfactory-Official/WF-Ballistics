package com.wf.wfballistics.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wfballistics.config.WFClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {

    @Inject(
            method = "renderChunkLayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderType;setupRenderState()V",
                    shift = At.Shift.AFTER))
    private void wfballistics$solidifyTranslucent(RenderType renderType, PoseStack poseStack,
                                                  double camX, double camY, double camZ, Matrix4f projection,
                                                  CallbackInfo ci) {
        if ((renderType != RenderType.translucent() && renderType != RenderType.tripwire())
                || !wfballistics$solidTranslucentActive()) {
            return;
        }
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
    }

    @Unique
    private boolean wfballistics$solidTranslucentActive() {
        if (Minecraft.useShaderTransparency()) {
            return false; // Fabulous keeps real transparency
        }
        try {
            return WFClientConfig.SOLID_TRANSLUCENT.get();
        } catch (IllegalStateException configNotLoaded) {
            return false;
        }
    }
}
