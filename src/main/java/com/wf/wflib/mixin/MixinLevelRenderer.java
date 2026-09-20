package com.wf.wflib.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.wf.wflib.client.fx.WFDynamicLight;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Level renderer tweaks. */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer {

    @ModifyReturnValue(
            method = "getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I",
            at = @At("RETURN"))
    private static int wflib$boostFireLight(int original, BlockAndTintGetter level, BlockState state, BlockPos pos) {
        return WFDynamicLight.boost(original, pos);
    }
}
