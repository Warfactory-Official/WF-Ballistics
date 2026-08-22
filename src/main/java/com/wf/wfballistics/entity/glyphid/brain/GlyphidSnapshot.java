package com.wf.wfballistics.entity.glyphid.brain;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Everything {@link GlyphidBrain} is allowed to know about one glyphid, captured on the world thread.
 *
 * <p>Holds no {@code Level}, no {@code Entity} and no {@code Path} — only values. That is the same bargain
 * {@code DroneSnapshot} makes on the drone side, and it is what makes the brain runnable somewhere other than
 * inside a live entity's goal tick: a warband record can fill one of these in, and so can a worker thread.
 *
 * <p>The three lookups that are neither free nor world-free — line of sight, melee reach, and whether the
 * navigator still has a path — are sampled here rather than asked for inside the brain. Vanilla caches line of
 * sight per tick, so asking once costs nothing extra; asking from a worker would be a data race.
 *
 * @param tickCount     the entity's own age, not the game time: the repath stagger keys off it so that bugs
 *                      spawned together still spread their searches out
 * @param taskY         already pulled onto real terrain by the caller where the column is loaded, because a
 *                      heightmap read is a world read
 * @param charge        bench switch, snapshotted rather than read inside the brain so the brain has no static
 *                      dependencies at all
 */
public record GlyphidSnapshot(
        int id,
        int tickCount,
        Vec3 position,
        int task,
        boolean hasWaypoint,
        int waypointRadius,
        int taskX,
        int taskY,
        int taskZ,
        boolean atDestination,
        boolean airborne,
        boolean canDig,
        boolean navigationDone,
        @Nullable Vec3 targetPosition,
        double targetDistanceSq,
        boolean targetVisible,
        boolean targetInReach,
        boolean charge,
        boolean stagger) {

    public boolean hasTarget() {
        return targetPosition != null;
    }
}
