package com.wf.wfballistics.entity.glyphid.brain;

import com.wf.wfballistics.debug.SwarmBench;
import com.wf.wfballistics.debug.SwarmProfiler;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.config.WFConfig;
import com.wf.wfballistics.entity.glyphid.GlyphidDigging;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.ai.GlyphidPathCache;
import net.minecraft.core.BlockPos;
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
 * The half of a glyphid's AI that has to touch the world: reading it into a {@link GlyphidSnapshot}, and
 * carrying a {@link GlyphidPlan} back out again.
 *
 * <p>Kept out of {@code EntityGlyphid} because it is the entity-shaped implementation of a thing that will
 * shortly have a record-shaped one too. Everything here is a world read or a world write; anything that is a
 * decision belongs in {@link GlyphidBrain} instead, and the split is what lets the same behaviour run on a
 * body that has no chunk under it.
 */
public final class GlyphidBody {

    /**
     * Ticks between jaw swings while chewing. Cosmetic: it is what drives the jaws in the model.
     */
    private static final int SWING_INTERVAL = 10;

    private GlyphidBody() {
    }

    /**
     * Read the world into a snapshot.
     *
     * <p>Two of the samples are gated rather than taken every tick, because both are reads the old goals only
     * paid for when they were about to use the answer, and a swarm makes anything per-glyphid-per-tick
     * expensive:
     *
     * <ul>
     *   <li>melee reach is only asked when the bite cooldown is about to expire</li>
     *   <li>the destination's real height is only pulled off the heightmap when a repath could be due</li>
     * </ul>
     */
    public static GlyphidSnapshot snapshot(EntityGlyphid glyphid) {
        GlyphidMind mind = glyphid.mind();

        LivingEntity target = glyphid.getTarget();
        if (target != null && (!target.isAlive() || !EntitySelector.NO_CREATIVE_OR_SPECTATOR.test(target))) {
            glyphid.setTarget(null);
            target = null;
        }
        if (SwarmBench.vanillaMeleeGoal) {
            // Under the A/B switch vanilla owns the fight, so the brain must not see anything to chase or it
            // would fight the vanilla goal for the same body.
            target = null;
        }

        Vec3 targetPosition = null;
        double targetDistanceSq = 0.0;
        boolean targetVisible = false;
        boolean targetInReach = false;
        if (target != null) {
            targetPosition = target.position();
            targetDistanceSq = glyphid.distanceToSqr(target);
            // Vanilla caches line of sight per tick, so asking once here costs what the goals paid to ask twice.
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
                stagger);
    }

    public static void apply(EntityGlyphid glyphid, GlyphidPlan plan) {
        GlyphidMind mind = glyphid.mind();
        // Any tick that is not a chew is a tick the cracks should not still be showing. Done here rather than
        // wherever chewing stops because there are five ways to stop and one place to notice.
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
                    // The navigator is stopped so it does not keep walking a stale path underneath the charge.
                    if (!glyphid.getNavigation().isDone()) {
                        glyphid.getNavigation().stop();
                    }
                    glyphid.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z,
                            glyphid.aiSpeed());
                }
            }
            case CHEW -> chew(glyphid, mind, plan);
            case PATH -> {
                boolean accepted = walk(glyphid, plan);
                Vec3 chew = GlyphidBrain.resolve(plan, mind, accepted);
                if (chew != null) {
                    pickBlockToChew(glyphid, chew);
                }
            }
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
     * Search to the hop the brain picked and start walking it.
     *
     * <p>Charged to melee or to the march by hand. The two answer to completely different fixes and the report
     * used to lump them together; it stayed separable while there were two goals to wrap, and this is what
     * replaces that now there is one.
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
     * Look for the block standing between this glyphid and where it is going, and commit to eating it if this
     * caste's jaws are up to the material. That a swarm is slowed by terrain rather than stopped by it is the
     * whole reason it is frightening — but only for terrain it can actually open. A wall of obsidian stops a
     * grunt outright, and that is what makes the material worth building out of.
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
     * The next block of the doorway this glyphid is opening, or null once there is one it can fit through.
     *
     * <p>One chewed block is a one-block hole, and a glyphid is wider than that — which is why the old
     * blast-shaped bite worked and eating a single block did not. So a breach is a doorway: the block that was
     * walked into, the one above it, and their neighbours to either side along the wall. Widening runs across
     * the face rather than into it, because the axis a wall is thin on is the one the glyphid is travelling
     * down.
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

    /**
     * One tick of eating a block: bank a tick against it, advance the cracks, and break it when the material
     * runs out.
     */
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
            // Drops nothing, but does emit the break particles and the sound, which is the whole point of
            // going through this rather than setting the block to air.
            level.destroyBlock(pos, false, glyphid);
            // Straight on to the next block of the breach rather than stopping here. Making it re-earn sixty
            // ticks of being stuck between every block turns a two-block wall into a minute of standing still.
            // Deeper wall first, then widening the doorway; when neither finds anything the way is open.
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

    /**
     * Take this glyphid's cracks off whatever block it had them on. A negative stage is what tells a client to
     * drop the overlay entirely.
     */
    public static void clearCracks(EntityGlyphid glyphid, GlyphidMind mind) {
        if (mind.chewStage < 0) {
            return;
        }
        glyphid.level().destroyBlockProgress(glyphid.getId(),
                new BlockPos(mind.chewX, mind.chewY, mind.chewZ), -1);
        mind.chewStage = -1;
    }
}
