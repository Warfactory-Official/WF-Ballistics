package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidBrain;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidPlan;
import com.wf.wfballistics.entity.glyphid.brain.GlyphidSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/**
 * Runs {@link GlyphidBrain} for one live glyphid and carries out what it decides.
 *
 * <p>Replaces the separate melee and march goals. They arbitrated by goal priority — bite at 3, walk at 4 —
 * which works only because there is a goal selector to do the arbitrating, and a warband record has no goal
 * selector. The choice is now {@link GlyphidBrain#errand}, which is the same rule written down somewhere a
 * body without a brain stem can read it.
 *
 * <p>What is left here is the parts that are genuinely the goal selector's: holding {@code MOVE} so idle
 * wandering cannot run at the same time as a march, and releasing it again when there is nowhere to be.
 */
public class GlyphidBrainGoal extends Goal {

    private final EntityGlyphid glyphid;

    public GlyphidBrainGoal(EntityGlyphid glyphid) {
        this.glyphid = glyphid;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return errand() != GlyphidBrain.ERRAND_NONE;
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
