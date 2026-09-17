package com.wf.wfballistics.mixin;

import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.Path;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Charges vanilla pathfinding to {@link SwarmProfiler} for glyphids. */
@Mixin(PathNavigation.class)
public abstract class MixinPathNavigation {

    @Shadow
    @Final
    protected Mob mob;

    @Unique
    private long wfballistics$navStart;
    @Unique
    private long wfballistics$navPathBase;
    @Unique
    private long wfballistics$pathStart;
    @Unique
    private long wfballistics$astarStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void wfballistics$navBegin(CallbackInfo ci) {
        wfballistics$navStart = wfballistics$profiled() ? SwarmProfiler.begin() : 0L;
        wfballistics$navPathBase = SwarmProfiler.accrued(SwarmProfiler.Phase.PATH);
    }

    /**
     * Path following is reported exclusive of any search it triggers: {@code tick} recomputes a stale path through
     * {@code createPath}, so charging both in full would count that search twice and make the two navigation phases
     * add up to more than the AI step that contains them.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void wfballistics$navEnd(CallbackInfo ci) {
        SwarmProfiler.endExcluding(SwarmProfiler.Phase.NAV, wfballistics$navStart,
                SwarmProfiler.Phase.PATH, wfballistics$navPathBase);
        wfballistics$navStart = 0L;
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"))
    private void wfballistics$pathBegin(CallbackInfoReturnable<Path> cir) {
        wfballistics$pathStart = wfballistics$profiled() ? SwarmProfiler.begin() : 0L;
        SwarmProfiler.setSearching(wfballistics$pathStart != 0L);
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("RETURN"))
    private void wfballistics$pathEnd(CallbackInfoReturnable<Path> cir) {
        if (wfballistics$pathStart != 0L) {
            SwarmProfiler.count(SwarmProfiler.Counter.SEARCHES, 1L);
        }
        SwarmProfiler.endPath(wfballistics$pathStart);
        SwarmProfiler.setSearching(false);
        wfballistics$pathStart = 0L;
    }

    /** The A* proper, bracketed apart from the chunk snapshot that {@code createPath} builds for it first. */
    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                    target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath"
                            + "(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
                            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
    private void wfballistics$astarBegin(CallbackInfoReturnable<Path> cir) {
        wfballistics$astarStart = wfballistics$pathStart != 0L ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath"
                            + "(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
                            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
    private void wfballistics$astarEnd(CallbackInfoReturnable<Path> cir) {
        SwarmProfiler.end(SwarmProfiler.Phase.PATH_ASTAR, wfballistics$astarStart);
        wfballistics$astarStart = 0L;
    }

    @Unique
    private boolean wfballistics$profiled() {
        return SwarmProfiler.enabled() && mob instanceof EntityGlyphid;
    }
}
