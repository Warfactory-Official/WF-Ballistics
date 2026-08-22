package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * Closes with a target and bites it.
 *
 * <p>Replaces vanilla's {@code MeleeAttackGoal}, which is written for one wolf and falls apart at three
 * hundred glyphids. Two things about it are structural rather than tunable:
 *
 * <ul>
 *   <li>Its {@code canUse} runs a <em>full A*</em> — every twenty ticks, per mob, purely to decide whether
 *       attacking is possible. Three hundred glyphids with a target pay fifteen searches a tick before any of
 *       them has moved.</li>
 *   <li>Its {@code tick} repaths every four to ten ticks, re-triggered whenever the target shifts one block.</li>
 * </ul>
 *
 * <p>Both go through {@code createPath(entity, 0)}, which takes its range from follow range — 16 for a
 * monster. Anything further away fails, and a failed search is the <em>expensive</em> one: it expands the
 * whole reachable set before giving up. A swarm whose targets are scattered over thirty blocks spends most of
 * its pathfinding budget proving it cannot get there.
 *
 * <p>Here: no pathfinding in {@code canUse} at all, and the walk is {@link GlyphidPathingGoal}'s — hops with
 * an explicit range, backing off on failure to make progress, chewing through what is in the way. A glyphid
 * that cannot route to its target digs toward it instead, which is also the answer to a target seen at 128
 * blocks and unroutable at 16.
 */
public class GlyphidMeleeGoal extends GlyphidPathingGoal {

    /**
     * Ticks between bites.
     */
    private static final int ATTACK_INTERVAL = 20;

    /**
     * How far the target may drift from the point we last aimed at before the hop is worth re-aiming.
     */
    private static final double REAIM_DISTANCE_SQ = (double) HOP * HOP;

    private int ticksUntilNextAttack;
    private double aimedX;
    private double aimedZ;

    public GlyphidMeleeGoal(EntityGlyphid glyphid, double speed) {
        super(glyphid, speed);
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = glyphid.getTarget();
        return target != null && target.isAlive() && !glyphid.isAirborne();
    }

    @Override
    public boolean canContinueToUse() {
        LivingEntity target = glyphid.getTarget();
        if (target == null || !target.isAlive() || glyphid.isAirborne()) {
            return false;
        }
        return EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target);
    }

    @Override
    public void start() {
        super.start();
        glyphid.setAggressive(true);
        ticksUntilNextAttack = 0;
        aimedX = glyphid.getX();
        aimedZ = glyphid.getZ();
    }

    @Override
    public void stop() {
        super.stop();
        LivingEntity target = glyphid.getTarget();
        if (!EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target)) {
            glyphid.setTarget(null);
        }
        glyphid.setAggressive(false);
    }

    @Override
    public void tick() {
        LivingEntity target = glyphid.getTarget();
        if (target == null) {
            return;
        }
        glyphid.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // Re-aim only when the target has left the hop we were walking to. Vanilla re-aims on one block of
        // drift, which is what makes it repath every few ticks.
        double dx = target.getX() - aimedX;
        double dz = target.getZ() - aimedZ;
        if (dx * dx + dz * dz > REAIM_DISTANCE_SQ) {
            repathNow();
        }

        advance();

        ticksUntilNextAttack = Math.max(ticksUntilNextAttack - 1, 0);
        if (ticksUntilNextAttack <= 0 && glyphid.isWithinMeleeAttackRange(target)
                && glyphid.getSensing().hasLineOfSight(target)) {
            ticksUntilNextAttack = adjustedTickDelay(ATTACK_INTERVAL);
            glyphid.swing(InteractionHand.MAIN_HAND);
            glyphid.doHurtTarget(target);
        }
    }

    @Override
    protected @Nullable Vec3 destination() {
        LivingEntity target = glyphid.getTarget();
        if (target == null) {
            return null;
        }
        aimedX = target.getX();
        aimedZ = target.getZ();
        return target.position();
    }
}
