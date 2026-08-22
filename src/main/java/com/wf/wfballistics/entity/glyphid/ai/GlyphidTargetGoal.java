package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Keeps a glyphid pointed at something to bite.
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

    /**
     * Ticks between searches while the glyphid has nothing to attack.
     *
     * <p>Searching used to be a walk of the player list, which is free on an empty server and cheap on a busy
     * one, so it ran every tick. It is now a box query for anything alive nearby, which at three hundred
     * glyphids searching every tick would cost more than the rest of the swarm put together. Staggered by
     * entity id, so the cost is spread rather than landing on one tick, at the price of up to a second before
     * a bug notices a cow.
     */
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
