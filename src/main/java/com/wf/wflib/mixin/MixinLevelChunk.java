package com.wf.wflib.mixin;

import com.wf.wflib.mine.MineSectionWatch;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bumps the revision of any subchunk a {@link MineSectionWatch} client is resting on, so a mine can sleep through
 * every tick in which nothing under it moved.
 */
@Mixin(LevelChunk.class)
public class MixinLevelChunk {

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("RETURN"))
    private void wflib$noteSectionChange(BlockPos pos, BlockState state, boolean isMoving,
                                                CallbackInfoReturnable<BlockState> cir) {
        if (!MineSectionWatch.isWatching() || cir.getReturnValue() == null) {
            return;
        }
        MineSectionWatch.onBlockChanged(((LevelChunk) (Object) this).getLevel(), pos);
    }
}
