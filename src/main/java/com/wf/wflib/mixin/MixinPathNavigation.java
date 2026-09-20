package com.wf.wflib.mixin;

import com.wf.wflib.debug.SwarmProfiler;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
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
    private long wflib$navStart;
    @Unique
    private long wflib$navPathBase;
    @Unique
    private long wflib$pathStart;
    @Unique
    private long wflib$astarStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void wflib$navBegin(CallbackInfo ci) {
        wflib$navStart = wflib$profiled() ? SwarmProfiler.begin() : 0L;
        wflib$navPathBase = SwarmProfiler.accrued(SwarmProfiler.Phase.PATH);
    }

    /**
     * Path following is reported exclusive of any search it triggers: {@code tick} recomputes a stale path through
     * {@code createPath}, so charging both in full would count that search twice and make the two navigation phases
     * add up to more than the AI step that contains them.
     */
    @Inject(method = "tick", at = @At("RETURN"))
    private void wflib$navEnd(CallbackInfo ci) {
        SwarmProfiler.endExcluding(SwarmProfiler.Phase.NAV, wflib$navStart,
                SwarmProfiler.Phase.PATH, wflib$navPathBase);
        wflib$navStart = 0L;
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"))
    private void wflib$pathBegin(CallbackInfoReturnable<Path> cir) {
        wflib$pathStart = wflib$profiled() ? SwarmProfiler.begin() : 0L;
        SwarmProfiler.setSearching(wflib$pathStart != 0L);
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("RETURN"))
    private void wflib$pathEnd(CallbackInfoReturnable<Path> cir) {
        if (wflib$pathStart != 0L) {
            SwarmProfiler.count(SwarmProfiler.Counter.SEARCHES, 1L);
        }
        SwarmProfiler.endPath(wflib$pathStart);
        SwarmProfiler.setSearching(false);
        wflib$pathStart = 0L;
    }

    /** The A* proper, bracketed apart from the chunk snapshot that {@code createPath} builds for it first. */
    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE,
                    target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath"
                            + "(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
                            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
    private void wflib$astarBegin(CallbackInfoReturnable<Path> cir) {
        wflib$astarStart = wflib$pathStart != 0L ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At(value = "INVOKE", shift = At.Shift.AFTER,
                    target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath"
                            + "(Lnet/minecraft/world/level/PathNavigationRegion;Lnet/minecraft/world/entity/Mob;"
                            + "Ljava/util/Set;FIF)Lnet/minecraft/world/level/pathfinder/Path;"))
    private void wflib$astarEnd(CallbackInfoReturnable<Path> cir) {
        SwarmProfiler.end(SwarmProfiler.Phase.PATH_ASTAR, wflib$astarStart);
        wflib$astarStart = 0L;
    }

    @Unique
    private boolean wflib$profiled() {
        return SwarmProfiler.enabled() && mob instanceof EntityGlyphid;
    }
}
