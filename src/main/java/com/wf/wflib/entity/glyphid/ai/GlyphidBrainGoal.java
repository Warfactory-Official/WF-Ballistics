package com.wf.wflib.entity.glyphid.ai;

import com.wf.wflib.debug.SwarmBench;
import com.wf.wflib.entity.glyphid.EntityGlyphid;
import com.wf.wflib.entity.glyphid.brain.GlyphidBrain;
import com.wf.wflib.entity.glyphid.brain.GlyphidPlan;
import com.wf.wflib.entity.glyphid.brain.GlyphidSnapshot;
import com.wf.wflib.entity.glyphid.nav.GlyphidBridges;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;

import java.util.EnumSet;

/** Runs {@link GlyphidBrain} for one live glyphid and carries out what it decides. */
public class GlyphidBrainGoal extends Goal {

    private final EntityGlyphid glyphid;

    public GlyphidBrainGoal(EntityGlyphid glyphid) {
        this.glyphid = glyphid;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
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
        glyphid.mind().errand = GlyphidBrain.ERRAND_NONE;
    }

    @Override
    public void tick() {
        if (!(glyphid.level() instanceof ServerLevel level)) {
            return;
        }
        if (glyphid.tickBridgeSlot(level) || GlyphidBridges.recruit(level, glyphid)) {
            return;
        }
        GlyphidSnapshot self = glyphid.snapshot(level);
        GlyphidPlan plan = GlyphidBrain.plan(self, glyphid.mind());
        glyphid.apply(level, plan);
        glyphid.setAggressive(glyphid.mind().errand == GlyphidBrain.ERRAND_MELEE);
    }

    /**
     * The same question {@link GlyphidBrain#errand} answers, asked without building a snapshot: the goal selector
     * polls this on every glyphid every few ticks whether or not anything is happening.
     */
    private int errand() {
        LivingEntity target = SwarmBench.vanillaMeleeGoal ? null : glyphid.getTarget();
        return GlyphidBrain.errand(glyphid.isAirborne(), target != null && target.isAlive(),
                glyphid.getCurrentTask(), glyphid.isAtDestination());
    }
}
