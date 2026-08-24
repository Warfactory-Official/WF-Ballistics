package com.wf.wfballistics.entity.glyphid.brain;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.entity.glyphid.GlyphidDigging;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.ai.GlyphidPathCache;
import com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowField;
import com.wf.wfballistics.entity.glyphid.nav.GlyphidFlowFields;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The half of a glyphid's AI that touches the world: reading it into a {@link GlyphidSnapshot} and carrying a
 * {@link GlyphidPlan} back out. Everything here is a world read or write; decisions belong in
 * {@link GlyphidBrain}, and that split is what lets a record run the same behaviour.
 */
public final class GlyphidBody {

    /** Ticks between jaw swings while chewing. Cosmetic: it is what drives the jaws in the model. */
    private static final int SWING_INTERVAL = 10;

    private GlyphidBody() {
    }

    /**
     * Read the world into a snapshot. Two samples are gated rather than taken every tick, since a swarm makes
     * anything per-glyphid-per-tick expensive: melee reach only when the bite cooldown is about to expire,
     * and the destination's height only when a repath could be due.
     */
    public static GlyphidSnapshot snapshot(EntityGlyphid glyphid) {
        GlyphidMind mind = glyphid.mind();

        LivingEntity target = glyphid.getTarget();
        if (target != null && (!target.isAlive() || !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target))) {
            glyphid.setTarget(null);
            target = null;
        }
        if (SwarmBench.vanillaMeleeGoal) {
            // Under the A/B switch vanilla owns the fight, so the brain must not see anything to chase.
            target = null;
        }

        Vec3 targetPosition = null;
        double targetDistanceSq = 0.0;
        boolean targetVisible = false;
        boolean targetInReach = false;
        if (target != null) {
            targetPosition = target.position();
            targetDistanceSq = glyphid.distanceToSqr(target);
            // Vanilla caches line of sight per tick, so one ask here costs what the goals paid for two.
            targetVisible = glyphid.getSensing().hasLineOfSight(target);
            targetInReach = mind.untilAttack <= 1 && glyphid.isWithinMeleeAttackRange(target);
        }

        boolean navigationDone = glyphid.getNavigation().isDone();
        boolean stagger = SwarmBench.staggerSearches;
        if (target == null && glyphid.getCurrentTask() == GlyphidTasks.TASK_FOLLOW
                && GlyphidBrain.repathDue(mind, glyphid.tickCount, glyphid.getId(), navigationDone, stagger)) {
            glyphid.resolveTaskHeight();
        }

        return new GlyphidSnapshot(
                glyphid.getId(),
                glyphid.tickCount,
                glyphid.position(),
                glyphid.getCurrentTask(),
                glyphid.getWaypoint() != null,
                glyphid.getWaypoint() != null ? glyphid.getWaypoint().radius : 0,
                glyphid.taskX,
                glyphid.taskY,
                glyphid.taskZ,
                glyphid.isAtDestination(),
                glyphid.isAirborne(),
                glyphid.canDig(),
                navigationDone,
                targetPosition,
                targetDistanceSq,
                targetVisible,
                targetInReach,
                SwarmBench.chargeMelee,
                stagger,
                flowStep(glyphid, target));
    }

    /**
     * Ask the shared field which way, for a glyphid that is marching. Marching only: a field is built to one
     * fixed destination, and a moving target would invalidate it every few seconds — the charge already
     * answers melee. Sampled every tick, at an index and eight comparisons against a 212 µs search.
     */
    private static @Nullable Vec3 flowStep(EntityGlyphid glyphid, @Nullable LivingEntity target) {
        if (target != null || glyphid.getCurrentTask() != GlyphidTasks.TASK_FOLLOW || glyphid.isAirborne()
                || glyphid.mind().chewing || !(glyphid.level() instanceof ServerLevel level)) {
            return null;
        }
        GlyphidFlowField field = GlyphidFlowFields.fieldFor(level, glyphid.taskX, glyphid.taskY, glyphid.taskZ);
        if (field == null) {
            return null;
        }
        double[] step = field.step(glyphid.getX(), glyphid.getY(), glyphid.getZ());
        return step == null ? null : new Vec3(step[0], step[1], step[2]);
    }

    public static void apply(EntityGlyphid glyphid, GlyphidPlan plan) {
        GlyphidMind mind = glyphid.mind();
        // Any tick that is not a chew should show no cracks. Here rather than at each of the five ways to
        // stop chewing.
        if (plan.move() != GlyphidPlan.Move.CHEW) {
            clearCracks(glyphid, mind);
        }

        Vec3 lookAt = plan.lookAt();
        if (lookAt != null) {
            glyphid.getLookControl().setLookAt(lookAt.x, lookAt.y, lookAt.z, 30.0F, 30.0F);
        }

        switch (plan.move()) {
            case NONE -> {
            }
            case STOP -> glyphid.getNavigation().stop();
            case CHARGE -> {
                Vec3 destination = plan.destination();
                if (destination != null) {
                    // Stopped so the navigator does not walk a stale path underneath the charge.
                    if (!glyphid.getNavigation().isDone()) {
                        glyphid.getNavigation().stop();
                    }
                    glyphid.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z,
                            glyphid.aiSpeed());
                }
            }
            case CHEW -> chew(glyphid, mind, plan);
            case FLOW -> {
                // No search and no path: the field said which column, and stepping up, jumping and turning
                // are the move control's job already.
                if (!glyphid.getNavigation().isDone()) {
                    glyphid.getNavigation().stop();
                }
                glyphid.getMoveControl().setWantedPosition(plan.hopX() + 0.5, plan.hopY(), plan.hopZ() + 0.5,
                        glyphid.aiSpeed());
                settle(glyphid, mind, plan, true);
            }
            case PATH -> settle(glyphid, mind, plan, walk(glyphid, plan));
        }

        if (plan.bite()) {
            LivingEntity target = glyphid.getTarget();
            if (target != null) {
                glyphid.swing(InteractionHand.MAIN_HAND);
                glyphid.doHurtTarget(target);
            }
        }

        if (plan.nextTask() != GlyphidPlan.KEEP_TASK) {
            glyphid.setCurrentTask(plan.nextTask(), glyphid.getWaypoint());
        }
    }

    /**
     * Age the patience and start chewing if it has run out. Only on ticks the brain decided something, or the
     * backoff would age to its limit within a second of walking.
     */
    private static void settle(EntityGlyphid glyphid, GlyphidMind mind, GlyphidPlan plan, boolean accepted) {
        if (plan.elapsed() <= 0) {
            return;
        }
        Vec3 chew = GlyphidBrain.resolve(plan, mind, accepted);
        if (chew != null) {
            pickBlockToChew(glyphid, chew);
        }
    }

    /**
     * Search to the hop the brain picked and start walking it. Charged to melee or the march by hand, since
     * the two answer to different fixes and there is only one goal left to wrap.
     *
     * @return true if a path was found and accepted
     */
    private static boolean walk(EntityGlyphid glyphid, GlyphidPlan plan) {
        SwarmProfiler.Phase bucket = glyphid.mind().errand == GlyphidBrain.ERRAND_MELEE
                ? SwarmProfiler.Phase.PATH_MELEE
                : SwarmProfiler.Phase.PATH_MARCH;
        SwarmProfiler.Phase previous = SwarmProfiler.enabled() ? SwarmProfiler.enterCaller(bucket) : null;
        try {
            int hopY = plan.hopY();
            if (plan.sampleY() && glyphid.level().hasChunk(plan.hopX() >> 4, plan.hopZ() >> 4)) {
                hopY = glyphid.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        plan.hopX(), plan.hopZ());
            }

            BlockPos hop = new BlockPos(plan.hopX(), hopY, plan.hopZ());
            BlockPos start = glyphid.blockPosition();
            Path path;
            if (SwarmBench.sharedPaths) {
                int tick = glyphid.tickCount;
                path = GlyphidPathCache.lookup(glyphid.level(), start, hop, tick);
                if (path == null) {
                    // Accuracy 1, range named explicitly: see GlyphidBrain.PATH_RANGE.
                    path = glyphid.getNavigation().createPath(hop, 1, GlyphidBrain.PATH_RANGE);
                    GlyphidPathCache.store(glyphid.level(), start, hop, path, tick);
                }
            } else {
                path = glyphid.getNavigation().createPath(hop, 1, GlyphidBrain.PATH_RANGE);
            }
            return path != null && glyphid.getNavigation().moveTo(path, glyphid.aiSpeed());
        } finally {
            if (previous != null) {
                SwarmProfiler.exitCaller(previous);
            }
        }
    }

    /**
     * Look for the block in the way and commit to eating it, if this caste's jaws are up to the material.
     * A swarm slowed by terrain rather than stopped by it is the point — but obsidian still stops a grunt.
     */
    private static boolean pickBlockToChew(EntityGlyphid glyphid, Vec3 destination) {
        if (!glyphid.canDig()) {
            return false;
        }
        Vec3 eye = glyphid.getEyePosition();
        double dx = destination.x - glyphid.getX();
        double dz = destination.z - glyphid.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 1.0E-4) {
            return false;
        }
        Vec3 ahead = eye.add(dx / distance * GlyphidBrain.CHEW_REACH, 0,
                dz / distance * GlyphidBrain.CHEW_REACH);

        BlockHitResult hit = glyphid.level().clip(new ClipContext(eye, ahead, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, glyphid));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        BlockPos obstruction = hit.getBlockPos();
        if (!edible(glyphid, obstruction)) {
            return false;
        }
        GlyphidMind mind = glyphid.mind();
        mind.beginChewing(obstruction.getX(), obstruction.getY(), obstruction.getZ());
        mind.breachX = obstruction.getX();
        mind.breachY = obstruction.getY();
        mind.breachZ = obstruction.getZ();
        return true;
    }

    /**
     * The next block of the doorway being opened, or null once it is wide enough to fit through. One chewed
     * block is a one-block hole and a glyphid is wider, so a breach is the block walked into, the one above,
     * and their neighbours along the wall — widening across the face, since that is the thin axis.
     */
    private static @Nullable BlockPos widenBreach(EntityGlyphid glyphid, Vec3 destination) {
        GlyphidMind mind = glyphid.mind();
        double dx = destination.x - glyphid.getX();
        double dz = destination.z - glyphid.getZ();
        if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
            return null;
        }
        boolean alongX = Math.abs(dx) >= Math.abs(dz);
        int sideX = alongX ? 0 : 1;
        int sideZ = alongX ? 1 : 0;

        BlockPos origin = new BlockPos(mind.breachX, mind.breachY, mind.breachZ);
        BlockPos[] doorway = {
                origin.above(),
                origin.offset(sideX, 0, sideZ),
                origin.offset(-sideX, 0, -sideZ),
                origin.offset(sideX, 1, sideZ),
                origin.offset(-sideX, 1, -sideZ),
        };
        for (BlockPos candidate : doorway) {
            if (edible(glyphid, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean edible(EntityGlyphid glyphid, BlockPos pos) {
        BlockState state = glyphid.level().getBlockState(pos);
        return !EntityGlyphid.isSpawnerBlock(state)
                && GlyphidDigging.chewable(state, glyphid.level(), pos, glyphid.getStats());
    }

    /** One tick of eating a block: bank progress, advance the cracks, break it when the material runs out. */
    private static void chew(EntityGlyphid glyphid, GlyphidMind mind, GlyphidPlan plan) {
        Level level = glyphid.level();
        BlockPos pos = new BlockPos(mind.chewX, mind.chewY, mind.chewZ);
        BlockState state = level.getBlockState(pos);
        int ticks = GlyphidDigging.ticksToChew(state, level, pos, glyphid.getStats());
        if (ticks <= 0 || EntityGlyphid.isSpawnerBlock(state)) {
            // Somebody else broke it, or it changed into something these jaws cannot open.
            clearCracks(glyphid, mind);
            mind.stopChewing();
            return;
        }

        glyphid.getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 30.0F, 30.0F);
        mind.chewProgress++;
        if (mind.chewProgress % SWING_INTERVAL == 0) {
            glyphid.swing(InteractionHand.MAIN_HAND);
        }

        if (mind.chewProgress >= ticks) {
            clearCracks(glyphid, mind);
            mind.stopChewing();
            // Drops nothing, but emits the break particles and sound -- the point of not setting air.
            level.destroyBlock(pos, false, glyphid);
            if (level instanceof ServerLevel server) {
                // A hole in a wall is a new route the field was flooded without.
                GlyphidFlowFields.invalidate(server, pos);
            }
            // Straight on to the next block rather than re-earning sixty ticks of stuck between each one.
            // Deeper first, then widening; when neither finds anything the way is open.
            Vec3 destination = plan.destination();
            if (destination != null && !pickBlockToChew(glyphid, destination)) {
                BlockPos next = widenBreach(glyphid, destination);
                if (next != null) {
                    mind.beginChewing(next.getX(), next.getY(), next.getZ());
                }
            }
            return;
        }

        if (!WFConfig.GLYPHID_DIG_OVERLAY.get()) {
            return;
        }
        int stage = Math.min(9, mind.chewProgress * 10 / ticks);
        if (stage != mind.chewStage) {
            mind.chewStage = stage;
            level.destroyBlockProgress(glyphid.getId(), pos, stage);
        }
    }

    /** Take this glyphid's cracks off whatever block had them. A negative stage drops the overlay. */
    public static void clearCracks(EntityGlyphid glyphid, GlyphidMind mind) {
        if (mind.chewStage < 0) {
            return;
        }
        glyphid.level().destroyBlockProgress(glyphid.getId(),
                new BlockPos(mind.chewX, mind.chewY, mind.chewZ), -1);
        mind.chewStage = -1;
    }
}
