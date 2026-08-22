package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;

/**
 * Idle wandering, suppressed whenever the bug is under colony orders: a glyphid on its way to a waypoint
 * should not be strolling off to look at something else.
 */
public class GlyphidWanderGoal extends RandomStrollGoal {

    private final EntityGlyphid glyphid;

    public GlyphidWanderGoal(EntityGlyphid glyphid, double speedModifier) {
        super(glyphid, speedModifier);
        this.glyphid = glyphid;
    }

    @Override
    public boolean canUse() {
        return glyphid.getCurrentTask() == GlyphidTasks.TASK_IDLE && super.canUse();
    }
}
