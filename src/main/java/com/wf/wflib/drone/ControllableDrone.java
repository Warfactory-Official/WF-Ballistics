package com.wf.wflib.drone;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.drone.ai.DroneCarrier;
import com.wf.wflib.drone.flight.FlightAttitude;
import com.wf.wflib.drone.nav.DronePath;
import com.wf.wflib.telemetry.TelemetryReporter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A body the drone autonomy can fly: {@link DroneEntity}, or one another mod supplies and registers with
 * {@link DroneTracker#track}. Mission state lives in the components; the body owns motion and anything slung.
 */
public interface ControllableDrone extends DroneCarrier, TelemetryReporter {

    Entity asEntity();

    DroneFlight flight();

    DroneRoute route();

    DroneOrders orders();

    DroneSquadRole squad();

    String callsign();

    void setCallsign(@Nullable String callsign);

    @Nullable
    UUID getTeamId();

    void setTeamId(@Nullable UUID teamId);

    @Nullable
    UUID getOperatorId();

    void setOperatorId(@Nullable UUID operatorId);

    void recordEvent(WFEventType type, String detail);

    /** 0..1. */
    double chargeFraction();

    /** Program steps outside this set are refused at dispatch. */
    boolean supports(DroneTask.Kind kind);

    /** {@link DroneFlight#setState} hook: sync, chunk tickets. */
    void onStateChanged(DroneState state);

    /** {@link DroneFlight#setAttitude} hook. */
    void onAttitude(FlightAttitude attitude);

    /** @return false if unpowered: nothing to fly home with. */
    default boolean recall(Vec3 home) {
        if (!this.flight().getDroneState().powered()) {
            return false;
        }
        this.route().setExfil(home);
        this.route().setDestination(null);
        this.flight().setState(DroneState.EXFIL);
        return true;
    }

    @Override
    default UUID droneId() {
        return this.asEntity().getUUID();
    }

    @Override
    default long squadId() {
        return this.squad().squadId();
    }

    @Override
    default boolean leader() {
        return this.squad().leader();
    }

    @Override
    default void promote() {
        this.squad().promote();
    }

    @Override
    default ResourceLocation formationId() {
        return this.squad().formationId();
    }

    @Override
    default double formationSpacing() {
        return this.squad().formationSpacing();
    }

    @Override
    default ResourceLocation coordinationId() {
        return this.squad().coordinationId();
    }

    @Override
    default boolean carrierAlive() {
        Entity e = this.asEntity();
        return e.isAlive() && !e.isRemoved();
    }

    @Override
    default void adoptPath(DronePath path) {
        this.route().adoptPath(path);
    }
}
