package com.wf.wfballistics.entity.glyphid.brain;

import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Everything {@link GlyphidBrain} is allowed to know about one glyphid, captured on the world thread.
 *
 * <p>Holds no {@code Level}, {@code Entity} or {@code Path} — only values, so a record or a worker thread can
 * fill one in. The three lookups that are neither free nor world-free (line of sight, melee reach, whether
 * the navigator still has a path) are sampled here rather than asked for inside the brain.
 *
 * @param tickCount     the entity's own age, not the game time, so the repath stagger spreads bugs spawned
 *                      together
 * @param taskY         already pulled onto real terrain by the caller, since a heightmap read is a world read
 * @param charge        bench switch, snapshotted so the brain has no static dependencies at all
 * @param flowStep      the next column the shared flow field says to walk into, or null where there is no
 *                      field, it has not flooded this far, or nothing is marching
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
        boolean stagger,
        @Nullable Vec3 flowStep) {

    public boolean hasTarget() {
        return targetPosition != null;
    }
}
