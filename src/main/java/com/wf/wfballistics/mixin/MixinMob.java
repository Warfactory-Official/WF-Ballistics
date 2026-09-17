package com.wf.wfballistics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.ai.GlyphidGoalSelector;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * Charges the goal/sensing layer to {@link SwarmProfiler} for glyphids, and gives them a goal selector that
 * arbitrates over an array rather than a linked hash set.
 */
@Mixin(Mob.class)
public abstract class MixinMob {

    @Unique
    private long wfballistics$aiStart;

    /** Swap in {@link GlyphidGoalSelector} for glyphids. */
    @WrapOperation(method = "<init>",
            at = @At(value = "NEW",
                    target = "(Ljava/util/function/Supplier;)Lnet/minecraft/world/entity/ai/goal/GoalSelector;"))
    private GoalSelector wfballistics$flatSelector(Supplier<ProfilerFiller> profiler,
                                                   Operation<GoalSelector> original) {
        if ((Object) this instanceof EntityGlyphid) {
            return new GlyphidGoalSelector(profiler);
        }
        return original.call(profiler);
    }

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
