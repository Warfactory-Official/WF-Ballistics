package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Idle wandering, suppressed whenever the bug is under colony orders: a glyphid on its way to a waypoint should not
 * be strolling off to look at something else.
 */
public class GlyphidWanderGoal extends RandomStrollGoal {

    /** How far a garrison bug may stroll before it strolls back. Inside the range a chamber hatches at. */
    private static final double LEASH = 24.0;

    private final EntityGlyphid glyphid;

    public GlyphidWanderGoal(EntityGlyphid glyphid, double speedModifier) {
        super(glyphid, speedModifier);
        this.glyphid = glyphid;
    }

    @Override
    public boolean canUse() {
        return glyphid.getCurrentTask() == GlyphidTasks.TASK_IDLE && super.canUse();
    }

    /** Where to stroll. */
    @Override
    protected @Nullable Vec3 getPosition() {
        if (!glyphid.garrison || !glyphid.hasHome) {
            return super.getPosition();
        }
        Vec3 home = new Vec3(glyphid.homeX + 0.5, glyphid.homeY, glyphid.homeZ + 0.5);
        if (glyphid.distanceToSqr(home) <= LEASH * LEASH) {
            return super.getPosition();
        }
        return DefaultRandomPos.getPosTowards(glyphid, 10, 7, home, Math.PI / 2.0);
    }
}
