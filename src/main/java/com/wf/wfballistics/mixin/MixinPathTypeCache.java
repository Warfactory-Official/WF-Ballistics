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

/**
 * Gives vanilla's shared path-type cache a size that suits a swarm.
 *
 * <p>See {@link PathTypeCacheSize} for why 4096 entries is not enough. Widening the arrays alone would
 * achieve nothing, because the index function masks with a hard-coded 4095 and would simply never address the
 * new slots — so the mask has to move with the table.
 *
 * <p>{@code index} is replaced rather than wrapped. It is called tens of thousands of times a tick, and every
 * callback-based injector either allocates a {@code CallbackInfo} on that path or can only post-process a
 * return value that has already had the old mask applied to it — by which point the high bits are gone.
 * The trade is compatibility: if another mod also overwrites this method, mixin will refuse to apply and say
 * so, which is the failure mode we want rather than two patches silently fighting.
 */
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

    /**
     * Counts how much of the search's block-reading actually goes through this cache. Resizing it 64x moved
     * reads per node only 8%, which either means the misses are compulsory or means most reads never consult
     * the cache at all -- and those want completely different fixes.
     */
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
     * @reason The index must mask against the real table size; the vanilla constant would strand every slot
     *         past 4096. See the class comment for why this is an overwrite and not an injection.
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
        // Dropped rather than rehashed: entries are recomputed on demand from blocks that are still there,
        // and rehashing a direct-mapped table is not obviously cheaper than just missing once.
        positions = new long[entries];
        pathTypes = new PathType[entries];
    }

    @Override
    public int wfballistics$capacity() {
        return positions == null ? 0 : positions.length;
    }
}
