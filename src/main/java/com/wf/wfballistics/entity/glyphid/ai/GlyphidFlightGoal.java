package com.wf.wfballistics.entity.glyphid.ai;

import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidTasks;
import com.wf.wfballistics.entity.glyphid.flight.GlyphidFlight;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Flies a winged glyphid to where its orders point, and puts it back on the ground to fight.
 *
 * <p>This is the flying counterpart of {@link GlyphidTaskMoveGoal}, and it replaces that goal rather than
 * layering on it: a glyphid in the air does no pathfinding at all. Since path searching is the largest single
 * cost in a marching swarm, a warband that flies is cheaper than one that walks, not dearer — the wings are a
 * performance feature as much as a gameplay one.
 *
 * <p>It lands to fight. Melee, digging and the colony task system all assume something standing on a surface,
 * and a bug that hovers just out of reach chewing a wall is neither frightening nor fair. So flight is for
 * crossing ground, and arriving means coming down.
 */
public class GlyphidFlightGoal extends Goal {

    /**
     * Closer than this to the destination and it is not worth taking off for.
     */
    private static final double TAKEOFF_RANGE = 24.0;
    /**
     * A target this close is fought on the ground, whatever the glyphid was doing.
     */
    private static final double ENGAGE_RANGE = 12.0;
    /**
     * How far above its landing spot a glyphid switches from cruising to descending.
     */
    private static final double LANDING_ALTITUDE = 2.0;

    private final EntityGlyphid glyphid;

    public GlyphidFlightGoal(EntityGlyphid glyphid) {
        this.glyphid = glyphid;
        setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        if (!glyphid.canFly() || glyphid.isInWater()) {
            return false;
        }
        if (nearTarget()) {
            return false;
        }
        return glyphid.getCurrentTask() == GlyphidTasks.TASK_FOLLOW
                && !glyphid.isAtDestination()
                && destinationDistance() > TAKEOFF_RANGE;
    }

    @Override
    public boolean canContinueToUse() {
        if (!glyphid.canFly() || nearTarget()) {
            return false;
        }
        // Once up, it stays up until it is over the destination -- the take-off range is a threshold for
        // starting, not for continuing, or a glyphid would land the moment it got within sight and walk the
        // rest.
        return glyphid.getCurrentTask() == GlyphidTasks.TASK_FOLLOW && !glyphid.isAtDestination();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        glyphid.setAirborne(true);
    }

    @Override
    public void stop() {
        glyphid.setAirborne(false);
        glyphid.setFlightTarget(null, false);
    }

    @Override
    public void tick() {
        glyphid.resolveTaskHeight();
        Vec3 destination = new Vec3(glyphid.taskX + 0.5, glyphid.taskY, glyphid.taskZ + 0.5);
        double horizontal = destinationDistance();

        // Once over the spot the descent is committed to, rather than being re-decided against the current
        // altitude every tick: mixing "hold cruise height" with "come down" leaves a glyphid bouncing between
        // the two a couple of blocks above the ground, never landing and never leaving.
        boolean landing = horizontal < GlyphidFlight.ARRIVAL_RANGE;
        glyphid.setFlightTarget(destination, landing);

        if (landing && (glyphid.onGround() || glyphid.getY() <= groundBelow() + LANDING_ALTITUDE)) {
            glyphid.setAirborne(false);
        }
    }

    /**
     * @return whether something worth biting is close enough to come down for.
     */
    private boolean nearTarget() {
        LivingEntity target = glyphid.getTarget();
        return target != null && target.distanceToSqr(glyphid) < ENGAGE_RANGE * ENGAGE_RANGE;
    }

    private double destinationDistance() {
        double dx = glyphid.taskX + 0.5 - glyphid.getX();
        double dz = glyphid.taskZ + 0.5 - glyphid.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private int groundBelow() {
        int x = glyphid.getBlockX();
        int z = glyphid.getBlockZ();
        if (!glyphid.level().hasChunk(x >> 4, z >> 4)) {
            return glyphid.getBlockY();
        }
        return glyphid.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }
}
