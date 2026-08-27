package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidBrain;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidPlan;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidSnapshot;
import com.wf.wfballistics.entity.glyphid.nav.GlyphidBridges;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Runs {@link GlyphidBrain} for one live glyphid and carries out what it decides. Replaces the separate melee
 * and march goals, which arbitrated by goal priority — something a warband record has no selector to do.
 * {@link GlyphidBrain#errand} is that rule written where a body without one can read it.
 *
 * <p>What is left here is the selector's own job: holding {@code MOVE} so idle wandering cannot run during a
 * march, and releasing it when there is nowhere to be.
 */
public class GlyphidBrainGoal extends Goal {

    private final EntityGlyphid glyphid;

    public GlyphidBrainGoal(EntityGlyphid glyphid) {
        this.glyphid = glyphid;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        // Bridge duty holds MOVE the same way an errand does, so idle wandering cannot pull an anchor out of
        // its slot.
        return glyphid.hasBridgeSlot() || errand() != GlyphidBrain.ERRAND_NONE;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void stop() {
        glyphid.getNavigation().stop();
        glyphid.setAggressive(false);
        // Forget the errand as well, so picking the goal back up restarts the walk rather than resuming one
        // whose backoff was earned against a target that is no longer there.
        glyphid.mind().errand = GlyphidBrain.ERRAND_NONE;
    }

    @Override
    public void tick() {
        if (!(glyphid.level() instanceof ServerLevel level)) {
            return;
        }
        // Both are one static int read with no bridge anywhere in the world; an anchor is the cheapest
        // glyphid there is, since neither the snapshot nor the brain runs for it.
        if (glyphid.tickBridgeSlot(level) || GlyphidBridges.recruit(level, glyphid)) {
            return;
        }
        GlyphidSnapshot self = glyphid.snapshot(level);
        GlyphidPlan plan = GlyphidBrain.plan(self, glyphid.mind());
        glyphid.apply(level, plan);
        glyphid.setAggressive(glyphid.mind().errand == GlyphidBrain.ERRAND_MELEE);
    }

    /**
     * The same question {@link GlyphidBrain#errand} answers, asked without building a snapshot: the goal
     * selector polls this on every glyphid every few ticks whether or not anything is happening.
     */
    private int errand() {
        LivingEntity target = SwarmBench.vanillaMeleeGoal ? null : glyphid.getTarget();
        return GlyphidBrain.errand(glyphid.isAirborne(), target != null && target.isAlive(),
                glyphid.getCurrentTask(), glyphid.isAtDestination());
    }
}
