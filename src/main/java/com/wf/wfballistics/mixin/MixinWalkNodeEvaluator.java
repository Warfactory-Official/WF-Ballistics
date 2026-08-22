package com.wf.wfballistics.mixin;

import com.wf.wfballistics.debug.SwarmProfiler;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times the inner loop of the A*, so "pathfinding is slow" can be answered with what kind of slow.
 *
 * <p>{@code getNeighbors} runs once per node the search pops, and is where the search actually touches the
 * world: for each candidate step it reads block states out of the chunk snapshot and classifies them. It is
 * the memory-bound part, and the only part that grows with how much terrain the search has to look at —
 * everything else in the A* is heap operations on nodes it has already paid for.
 *
 * <p>Also counts the nodes, because milliseconds alone cannot distinguish a tick that ran more searches from
 * a tick that ran deeper ones, and those want opposite fixes.
 *
 * <p>Charges nothing unless the search is one a glyphid asked for; see {@code MixinPathNavigation}.
 */
@Mixin(WalkNodeEvaluator.class)
public abstract class MixinWalkNodeEvaluator {

    @Unique
    private long wfballistics$neighborStart;

    @Inject(method = "getNeighbors", at = @At("HEAD"))
    private void wfballistics$neighborsBegin(Node[] outputArray, Node node, CallbackInfoReturnable<Integer> cir) {
        wfballistics$neighborStart = SwarmProfiler.searching() ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "getNeighbors", at = @At("RETURN"))
    private void wfballistics$neighborsEnd(Node[] outputArray, Node node, CallbackInfoReturnable<Integer> cir) {
        if (wfballistics$neighborStart != 0L) {
            SwarmProfiler.count(SwarmProfiler.Counter.NODES, 1L);
        }
        SwarmProfiler.end(SwarmProfiler.Phase.PATH_NEIGHBORS, wfballistics$neighborStart);
        wfballistics$neighborStart = 0L;
    }
}
