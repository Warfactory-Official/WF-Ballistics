package com.wf.wfballistics.drone.sim;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.api.WFEventType;
import com.wf.wfballistics.api.WFTelemetryService;
import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.DroneTracker;
import com.wf.wfballistics.drone.ai.DroneAiScheduler;
import com.wf.wfballistics.drone.ai.DroneCarrier;
import com.wf.wfballistics.sim.MissileListenerRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Moves drones between being real entities and being {@link SimDrone} records, the drone equivalent of
 * {@code SimMissileManager}.
 *
 * <p>The difference from missiles: a simulated drone is not advanced by a simplified straight-line model
 * here. It stays a {@link DroneCarrier}, so {@code DroneAiScheduler} keeps planning and moving it with the
 * same brain: this class only decides <em>when</em> a drone should stop being an entity and when it has to
 * become one again.
 */
public final class SimDroneManager {

    /**
     * A drone further than this from any player offloads. Deliberately shorter than the missile equivalent:
     * drones are slow and low, so there is no point keeping one as an entity out where nobody can see it.
     */
    public static final double OFFLOAD_PLAYER_RANGE = 192.0;
    /**
     * Slack between the offload and onload ranges so a drone near the boundary doesn't flicker.
     */
    public static final double ONLOAD_MARGIN = 32.0;
    /**
     * Distance from its waypoint at which a simulated drone must become real again, so the delivery, the
     * landing and the crate all happen in a loaded world.
     */
    public static final double WAYPOINT_ONLOAD_RANGE = 96.0;
    /**
     * Ticks a drone must be airborne and travelling before it may offload, so it doesn't offload during the
     * first moments of a launch.
     */
    public static final int OFFLOAD_DELAY_TICKS = 40;

    private static final Logger LOGGER = LogUtils.getLogger();

    static {
        DroneAiScheduler.addSource(SimDroneManager::collect);
    }

    /**
     * Testing switch: with this off, drones never leave the world for the simulation.
     *
     * <p>The sim deliberately has no terrain: an offloaded drone holds its altitude and flies straight,
     * which is the trade that makes it cheap. That also means the terrain following and the path planner are
     * only exercised on the parts of a route flown for real, which without a player nearby is just the last
     * hundred blocks of each leg. Turning this off keeps a drone in the world for its whole mission so the
     * navigation can actually be watched.
     */
    public static boolean offloadEnabled = true;

    private SimDroneManager() {
    }

    /**
     * Touching this class registers the off-world carrier source with the scheduler.
     */
    public static void bootstrap() {
    }

    private static void collect(ServerLevel level, List<DroneCarrier> out) {
        out.addAll(SimDroneRegistry.get(level).view());
    }

    /**
     * Run the offload/onload decisions for one dimension. Called before the scheduler each tick so a drone
     * that changes form is planned in its new form the same tick.
     */
    public static void tick(ServerLevel level) {
        offloadEligible(level);
        onloadEligible(level);
    }

    private static void offloadEligible(ServerLevel level) {
        List<DroneEntity> drones = new ArrayList<>(DroneTracker.drones(level));
        for (DroneEntity drone : drones) {
            if (!canOffload(level, drone)) {
                continue;
            }
            SimDrone sd = SimDrone.fromEntity(drone, drone.hasCargo() ? drone.saveCargo() : null);
            SimDroneRegistry.get(level).add(sd);
            drone.recordEvent(WFEventType.OFFLOAD, "to drone sim");
            LOGGER.debug("[wfballistics] drone {} offloaded to simulation at {}", sd.id, sd.pos);
            drone.discard();
        }
    }

    private static boolean canOffload(ServerLevel level, DroneEntity drone) {
        if (!offloadEnabled) {
            return false;
        }
        if (!drone.isAlive() || drone.isRemoved()) {
            return false;
        }
        DroneState state = drone.getDroneState();
        if (state != DroneState.TRANSIT && state != DroneState.EXFIL) {
            return false;
        }
        if (drone.tickCount < OFFLOAD_DELAY_TICKS) {
            return false;
        }
        Vec3 pos = drone.position();
        if (horizontalDistance(pos, waypoint(drone)) <= WAYPOINT_ONLOAD_RANGE + ONLOAD_MARGIN) {
            return false;
        }
        return !nearWatcher(level, pos, OFFLOAD_PLAYER_RANGE);
    }

    private static void onloadEligible(ServerLevel level) {
        SimDroneRegistry registry = SimDroneRegistry.get(level);
        List<SimDrone> all = new ArrayList<>(registry.view());
        for (SimDrone sd : all) {
            boolean travelling = sd.state == DroneState.TRANSIT || sd.state == DroneState.EXFIL;
            boolean nearWaypoint = horizontalDistance(sd.pos, waypoint(sd)) <= WAYPOINT_ONLOAD_RANGE;
            boolean watched = nearWatcher(level, sd.pos, OFFLOAD_PLAYER_RANGE - ONLOAD_MARGIN);
            if (travelling && !nearWaypoint && !watched) {
                continue;
            }
            respawn(level, sd);
            registry.remove(sd);
        }
    }

    private static void respawn(ServerLevel level, SimDrone sd) {
        DroneEntity drone = sd.toEntity(level, sd.pos);
        ChunkPos cp = drone.chunkPosition();
        MissileListenerRegistry.CHUNK_TICKET.forceChunk(level, drone, cp.x, cp.z, true, true);
        level.getChunk(cp.x, cp.z);
        level.addFreshEntity(drone);
        WFTelemetryService.record(sd.id, WFEventType.ONLOAD, level.getGameTime(), sd.pos, false, "from drone sim");
        LOGGER.debug("[wfballistics] simulated drone {} respawned at {}", sd.id, sd.pos);
    }

    /**
     * @return true if a player is close enough that the drone should be a real, visible entity.
     */
    private static boolean nearWatcher(ServerLevel level, Vec3 pos, double range) {
        double r2 = range * range;
        for (ServerPlayer player : level.players()) {
            if (player.position().distanceToSqr(pos) <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the waypoint of the leg currently being flown. On the way home that is the exfil point, not
     * the destination the drone still remembers: using the wrong one leaves a returning drone simulated
     * right through its own landing.
     */
    private static Vec3 waypoint(DroneState state, @Nullable Vec3 destination, Vec3 exfil) {
        return (state == DroneState.EXFIL || destination == null) ? exfil : destination;
    }

    private static Vec3 waypoint(DroneEntity drone) {
        return waypoint(drone.getDroneState(), drone.getDestination(), drone.getExfil());
    }

    private static Vec3 waypoint(SimDrone sd) {
        return waypoint(sd.state, sd.destination, sd.exfil);
    }

    private static double horizontalDistance(Vec3 a, Vec3 b) {
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

}
