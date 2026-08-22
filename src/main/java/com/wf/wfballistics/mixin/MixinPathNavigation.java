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

/**
 * Charges vanilla pathfinding to {@link SwarmProfiler} for glyphids.
 *
 * <p>Needed because the two halves of navigation cost are not separable from outside: following an existing
 * path and searching for a new one both happen inside {@code Mob#serverAiStep}, and telling them apart is the
 * whole question the swarm work turns on. Splitting them here means the report can say whether a swarm is
 * expensive because it is <em>searching</em> or merely because it is <em>walking</em>, which point at
 * completely different fixes.
 *
 * <p>Scoped to glyphids so the numbers are not diluted by every other mob on the server, and gated on
 * {@link SwarmProfiler#enabled()} before the {@code instanceof} so that with profiling off this costs every
 * mob in the game one static boolean read.
 */
@Mixin(PathNavigation.class)
public abstract class MixinPathNavigation {

    @Shadow
    @Final
    protected Mob mob;

    @Unique
    private long wfballistics$navStart;
    @Unique
    private long wfballistics$pathStart;

    @Inject(method = "tick", at = @At("HEAD"))
    private void wfballistics$navBegin(CallbackInfo ci) {
        wfballistics$navStart = wfballistics$profiled() ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void wfballistics$navEnd(CallbackInfo ci) {
        SwarmProfiler.end(SwarmProfiler.Phase.NAV, wfballistics$navStart);
        wfballistics$navStart = 0L;
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("HEAD"))
    private void wfballistics$pathBegin(CallbackInfoReturnable<Path> cir) {
        wfballistics$pathStart = wfballistics$profiled() ? SwarmProfiler.begin() : 0L;
    }

    @Inject(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
            at = @At("RETURN"))
    private void wfballistics$pathEnd(CallbackInfoReturnable<Path> cir) {
        SwarmProfiler.end(SwarmProfiler.Phase.PATH, wfballistics$pathStart);
        wfballistics$pathStart = 0L;
    }

    @Unique
    private boolean wfballistics$profiled() {
        return SwarmProfiler.enabled() && mob instanceof EntityGlyphid;
    }
}
