package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Walks a glyphid to the destination its orders name.
 *
 * <p>Upstream has no goal for this: movement under orders is issued by its async decision layer, which this
 * port replaced. Without something here a glyphid handed a destination simply stands in it, so a materialised
 * warband would arrive and never attack.
 *
 * <p>All the walking is {@link GlyphidPathingGoal}; what is left here is only where to walk.
 */
public class GlyphidTaskMoveGoal extends GlyphidPathingGoal {

    public GlyphidTaskMoveGoal(EntityGlyphid glyphid, double speed) {
        super(glyphid, speed);
    }

    @Override
    public boolean canUse() {
        return !glyphid.isAirborne()
                && glyphid.getCurrentTask() == GlyphidTasks.TASK_FOLLOW
                && !glyphid.isAtDestination();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        advance();
    }

    @Override
    protected @Nullable Vec3 destination() {
        glyphid.resolveTaskHeight();
        return new Vec3(glyphid.taskX + 0.5, glyphid.taskY, glyphid.taskZ + 0.5);
    }
}
