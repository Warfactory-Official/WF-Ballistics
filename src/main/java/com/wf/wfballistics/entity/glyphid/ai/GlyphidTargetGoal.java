package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Keeps a glyphid pointed at a player.
 *
 * <p>Acquires immediately while the bug has no target, then re-checks only occasionally, staggered by entity
 * id so a swarm does not run every bug's search on the same tick. Claims no {@link Goal.Flag}s, so it never
 * competes with the movement goals: a glyphid's target and what it is walking towards are separate questions.
 */
public class GlyphidTargetGoal extends Goal {

    /**
     * Ticks between re-checks once a target is already held.
     */
    private static final int RECHECK_INTERVAL = 100;

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
        if (glyphid.getTarget() == null) {
            acquire();
            return;
        }

        int id = glyphid.getId();
        if (id % 3 > 0 && (id + glyphid.tickCount) % RECHECK_INTERVAL == 0) {
            acquire();
        }
    }

    private void acquire() {
        @Nullable Player found = glyphid.findTargetCandidate();
        if (found != null) {
            glyphid.setTarget(found);
        }
    }
}
