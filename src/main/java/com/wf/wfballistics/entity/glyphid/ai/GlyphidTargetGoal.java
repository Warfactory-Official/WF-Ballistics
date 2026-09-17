package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/** Keeps a glyphid pointed at something to bite. */
public class GlyphidTargetGoal extends Goal {

    /** Ticks between re-checks once a target is already held. */
    private static final int RECHECK_INTERVAL = 100;

    /** Ticks between searches while the glyphid has nothing to attack. */
    private static final int ACQUIRE_INTERVAL = 20;

    private final EntityGlyphid glyphid;

    public GlyphidTargetGoal(EntityGlyphid glyphid) {
        this.glyphid = glyphid;
        setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        return true;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        int id = glyphid.getId();
        if (glyphid.getTarget() == null) {
            if ((id + glyphid.tickCount) % ACQUIRE_INTERVAL == 0) {
                acquire();
            }
            return;
        }

        if (id % 3 > 0 && (id + glyphid.tickCount) % RECHECK_INTERVAL == 0) {
            acquire();
        }
    }

    private void acquire() {
        @Nullable LivingEntity found = glyphid.findTargetCandidate();
        if (found != null) {
            glyphid.setTarget(found);
        }
    }
}
