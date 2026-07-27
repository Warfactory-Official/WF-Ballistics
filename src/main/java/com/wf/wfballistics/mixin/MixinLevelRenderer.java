package com.wf.wfballistics.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wfballistics.client.fx.WFDynamicLight;
import com.wf.wfballistics.config.WFClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
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

    @ModifyReturnValue(
            method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"))
    private static int wfballistics$boostFireLight(int original, BlockAndTintGetter level, BlockState state, BlockPos pos) {
        return WFDynamicLight.boost(original, pos);
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
