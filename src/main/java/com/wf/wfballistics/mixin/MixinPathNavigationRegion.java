package com.wf.wfballistics.mixin;

import com.wf.wfballistics.debug.SwarmProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Counts the block-state reads a path search makes.
 *
 * <p>Node expansion measured as ~89% of path search, which points at the world reads inside it rather than at
 * the A* algorithm — but "points at" is not a measurement. Counting them turns nanoseconds per node into
 * nanoseconds per block read, which is the number that says whether the fix is fewer searches, fewer nodes,
 * or a cheaper way to read a block.
 *
 * <p>{@code PathNavigationRegion} is the pathfinder's private chunk snapshot, so every read through it belongs
 * to a search and nothing else in the game uses it.
 */
@Mixin(PathNavigationRegion.class)
public abstract class MixinPathNavigationRegion {

    @Inject(method = "getBlockState", at = @At("HEAD"))
    private void wfballistics$countBlockRead(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (SwarmProfiler.searching()) {
            SwarmProfiler.count(SwarmProfiler.Counter.BLOCK_READS, 1L);
        }
    }
}
