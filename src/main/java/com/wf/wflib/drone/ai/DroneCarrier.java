package com.wf.wflib.drone.ai;

import com.wf.wflib.drone.nav.DronePath;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/** Something the AI can fly. */
public interface DroneCarrier {

    UUID droneId();

    /**
     * @return the squad this drone belongs to, or 0 when it flies alone.
     */
    long squadId();

    boolean leader();

    /** Take over the squad. */
    void promote();

    ResourceLocation formationId();

    /**
     * @return how far apart this drone's squad flies its slots. Read from the leader when the squad is
     *      assembled, so one member's setting governs the whole shape.
     */
    double formationSpacing();

    /**
     * @return which {@code CoordinationModel} this drone's squad holds its shape with. Read from the leader
     *      for the same reason the shape and the spacing are: a squad whose members disagreed about what they are
     *      measuring themselves against would not be flying one formation.
     */
    ResourceLocation coordinationId();

    /**
     * @return false once the carrier is gone (entity removed, sim record dropped) and should be skipped.
     */
    boolean carrierAlive();

    /**
     * Capture this drone's state for the planner, sampling anything it needs from the world <em>now</em>.
     */
    DroneSnapshot snapshot(ServerLevel level, long gameTime);

    /**
     * Apply a finished plan: move, change state, run its actions.
     */
    void apply(ServerLevel level, DronePlan plan);

    /**
     * Called instead of {@link #apply} when no fresh plan was ready this tick.
     */
    void coast(ServerLevel level);

    /** Adopt a route from a background search. */
    default void adoptPath(DronePath path) {
    }
}
