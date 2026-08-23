package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Idle wandering, suppressed whenever the bug is under colony orders: a glyphid on its way to a waypoint
 * should not be strolling off to look at something else.
 *
 * <p>A nest's garrison is leashed to its mound on top of that. A defender is retained rather than despawned
 * and its colony's growth is held back for as long as it lives, so one that strolled away for long enough
 * would leave the nest undefended <em>and</em> the colony still paying for it — the same bleed retention was
 * meant to close, arriving by a slower route. Measured before the leash: one of six defenders had drifted
 * past 32 blocks inside 70 seconds.
 */
public class GlyphidWanderGoal extends RandomStrollGoal {

    /**
     * How far a garrison bug may stroll from its nest before it starts strolling back. Inside the 48-block
     * range a chamber hatches at, so a defender is always somewhere it would have been spawned.
     */
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

    /**
     * Where to stroll. Unchanged for everything except a garrison bug that has wandered off its nest, which
     * gets a destination back toward it rather than another random one.
     *
     * <p>A leash rather than a hard tether: it still wanders, and it still chases whatever it targets to
     * wherever that goes. It only stops picking somewhere further out once it is already too far.
     */
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
