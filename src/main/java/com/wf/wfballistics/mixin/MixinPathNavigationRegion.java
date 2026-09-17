package com.wf.wfballistics.mixin;

import com.wf.wfballistics.debug.SwarmProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts the block-state reads a path search makes. */
@Mixin(PathNavigationRegion.class)
public abstract class MixinPathNavigationRegion {

    @Inject(method = "getBlockState", at = @At("HEAD"))
    private void wfballistics$countBlockRead(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (SwarmProfiler.searching()) {
            SwarmProfiler.count(SwarmProfiler.Counter.BLOCK_READS, 1L);
        }
    }
}
