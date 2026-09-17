package com.wf.wfballistics.mixin;

import com.wf.wfballistics.debug.PathTypeCacheSize;
import com.wf.wfballistics.debug.ResizablePathTypeCache;
import com.wf.wfballistics.debug.SwarmProfiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Gives vanilla's shared path-type cache a size that suits a swarm. */
@Mixin(PathTypeCache.class)
public abstract class MixinPathTypeCache implements ResizablePathTypeCache {

    @Shadow
    @Final
    @Mutable
    private long[] positions;

    @Shadow
    @Final
    @Mutable
    private PathType[] pathTypes;

    /** Counts how much of the search's block-reading actually goes through this cache. */
    @Inject(method = "getOrCompute", at = @At("HEAD"))
    private void wfballistics$countQuery(BlockGetter level, BlockPos pos, CallbackInfoReturnable<PathType> cir) {
        if (SwarmProfiler.searching()) {
            SwarmProfiler.count(SwarmProfiler.Counter.TYPE_QUERY, 1L);
        }
    }

    @Inject(method = "compute", at = @At("HEAD"))
    private void wfballistics$countMiss(BlockGetter level, BlockPos pos, int index, long packedPos,
                                        CallbackInfoReturnable<PathType> cir) {
        if (SwarmProfiler.searching()) {
            SwarmProfiler.count(SwarmProfiler.Counter.TYPE_MISS, 1L);
        }
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void wfballistics$widen(CallbackInfo ci) {
        wfballistics$resize(PathTypeCacheSize.entries());
    }

    /**
     * @author wfballistics
     *      @reason The index must mask against the real table size; the vanilla constant would strand every slot
     *      past 4096. See the class comment for why this is an overwrite and not an injection.
     */
    @Overwrite
    private static int index(long pos) {
        return (int) HashCommon.mix(pos) & PathTypeCacheSize.mask();
    }

    @Override
    public void wfballistics$resize(int entries) {
        if (positions != null && positions.length == entries) {
            return;
        }
        positions = new long[entries];
        pathTypes = new PathType[entries];
    }

    @Override
    public int wfballistics$capacity() {
        return positions == null ? 0 : positions.length;
    }
}
