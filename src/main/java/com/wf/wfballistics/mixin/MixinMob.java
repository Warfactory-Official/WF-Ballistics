package com.wf.wfballistics.mixin;

import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Charges the goal/sensing layer to {@link SwarmProfiler} for glyphids.
 *
 * <p>A mixin rather than an override because {@code Mob#serverAiStep} is final. It is the parent of both
 * navigation phases, so without it the pathfinding numbers have nothing to be a share <em>of</em>.
 *
 * <p>Gated on {@link SwarmProfiler#enabled()} before the {@code instanceof}, so with profiling off this
 * costs every mob on the server one static boolean read.
 */
@Mixin(Mob.class)
public abstract class MixinMob {

    @Unique
    private long wfballistics$aiStart;

    @Inject(method = "serverAiStep", at = @At("HEAD"))
    private void wfballistics$aiBegin(CallbackInfo ci) {
        wfballistics$aiStart = SwarmProfiler.enabled() && (Object) this instanceof EntityGlyphid
                ? SwarmProfiler.begin()
                : 0L;
    }

    @Inject(method = "serverAiStep", at = @At("RETURN"))
    private void wfballistics$aiEnd(CallbackInfo ci) {
        SwarmProfiler.end(SwarmProfiler.Phase.AI, wfballistics$aiStart);
        wfballistics$aiStart = 0L;
    }
}
