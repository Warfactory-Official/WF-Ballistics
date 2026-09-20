package com.wf.wflib.mixin;

import com.wf.wflib.debug.SwarmProfiler;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Times the inner loop of the A*, so "pathfinding is slow" can be answered with what kind of slow. */
@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator {

    @Unique
    private long wflib$neighborStart;

    @Inject(method = "getNeighbors", at = @At("HEAD"))
    private void wflib$neighborsBegin(Node[] outputArray, Node node, CallbackInfoReturnable<Integer> cir) {
        wflib$neighborStart = SwarmProfiler.searching() ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "getNeighbors", at = @At("RETURN"))
    private void wflib$neighborsEnd(Node[] outputArray, Node node, CallbackInfoReturnable<Integer> cir) {
        if (wflib$neighborStart != 0L) {
            SwarmProfiler.count(SwarmProfiler.Counter.NODES, 1L);
        }
        SwarmProfiler.end(SwarmProfiler.Phase.PATH_NEIGHBORS, wflib$neighborStart);
        wflib$neighborStart = 0L;
    }
}
