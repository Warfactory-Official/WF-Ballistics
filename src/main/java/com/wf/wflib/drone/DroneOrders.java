package com.wf.wflib.drone;

import com.wf.wflib.api.WFEventType;
import com.wf.wflib.exchange.ExchangeManager;
import com.wf.wflib.work.WorkAssignment;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** What the drone was told to do: the program queue, an arranged exchange, a work assignment, surveillance. */
public final class DroneOrders {

    /** How far around the point it was sent to watch a surveillance drone reports contacts from. */
    public static final double WATCH_RADIUS = 48.0;

    private final DroneEntity drone;
    /** The queued mission steps. */
    private DroneProgram program = DroneProgram.EMPTY;
    /**
     * Who a watching drone has already called in, so the same player standing in the same field is reported once
     * rather than every tick.
     */
    private final Set<UUID> seenContacts = new HashSet<>();
    /**
     * True on a mission that is trying to hide: no telemetry is kept, and the listing redacts it.
     */
    private boolean classified;
    /**
     * True if this drone is going to its destination to pick a crate up rather than put one down.
     */
    private boolean collecting;
    /**
     * The exchange this drone is flying for, if any, and the station that sent it.
     */
    @Nullable
    private UUID exchangeId;
    @Nullable
    private String stationCode;
    /**
     * What this drone is doing for a construction or salvage job, or null if it is not on one.
     */
    @Nullable
    private WorkAssignment assignment;

    DroneOrders(DroneEntity drone) {
        this.drone = drone;
    }

    public DroneProgram getProgram() {
        return this.program;
    }

    /** Give this drone a queue to fly, and send it to the first step. */
    public void setProgram(DroneProgram program) {
        this.program = program == null ? DroneProgram.EMPTY : program;
        Vec3 first = this.program.destination(this.drone.route().getExfil());
        if (first != null) {
            this.drone.route().setDestination(first);
        }
    }

    /** Step the queue on and take the next destination with it. */
    void advanceTask() {
        this.program = this.program.advanced();
        this.seenContacts.clear();
        this.drone.route().setDestination(this.program.destination(this.drone.route().getExfil()));
    }

    void abandonProgram(String reason) {
        this.program = this.program.abandoned();
        this.drone.recordEvent(WFEventType.MISSION_COMPLETE, "program abandoned: " + reason);
    }

    public boolean isClassified() {
        return this.classified;
    }

    public boolean isCollecting() {
        return this.collecting;
    }

    @Nullable
    public UUID getExchangeId() {
        return this.exchangeId;
    }

    @Nullable
    public String getStationCode() {
        return this.stationCode;
    }

    /**
     * Fly this as part of an arranged exchange: no telemetry, a dogleg out and back, and either dropping or
     * collecting at the far end.
     *
     * @param approach staging waypoints to fly before the destination
     * @param egress staging waypoints to fly after the drop, installed when the cargo changes hands
     */
    public void setExchange(@Nullable UUID exchangeId, @Nullable String stationCode, boolean classified,
                            boolean collecting, List<Vec3> approach, List<Vec3> egress) {
        this.exchangeId = exchangeId;
        this.stationCode = stationCode;
        this.classified = classified;
        this.collecting = collecting;
        this.drone.route().setLegs(approach, egress);
    }

    /**
     * Abandon the exchange and go home with whatever is aboard.
     */
    public void abortExchange() {
        this.exchangeId = null;
        this.drone.route().setDestination(null);
        this.drone.route().beginEgress();
    }

    @Nullable
    public WorkAssignment assignment() {
        return this.assignment;
    }

    public void setAssignment(@Nullable WorkAssignment assignment) {
        this.assignment = assignment;
    }

    /**
     * @return false if anyone is standing near the drop point.
     */
    boolean dropZoneClear(ServerLevel level) {
        Vec3 destination = this.drone.route().getDestination();
        if (!this.classified || destination == null) {
            return true;
        }
        double radiusSqr = ExchangeManager.WATCH_RADIUS * ExchangeManager.WATCH_RADIUS;
        for (Player player : level.players()) {
            if (player.isSpectator() || player.isDeadOrDying()) {
                continue;
            }
            if (player.distanceToSqr(destination) <= radiusSqr) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return everyone who has come into view of a watching drone since the last time it looked.
     */
    List<String> sweepForContacts(ServerLevel level) {
        Vec3 destination = this.drone.route().getDestination();
        if (this.drone.flight().getDroneState() != DroneState.SURVEIL || destination == null) {
            if (!this.seenContacts.isEmpty()) {
                this.seenContacts.clear();
            }
            return List.of();
        }
        double radiusSqr = WATCH_RADIUS * WATCH_RADIUS;
        List<String> fresh = null;
        for (Player player : level.players()) {
            if (player.isSpectator() || player.isDeadOrDying()) {
                continue;
            }
            if (player.distanceToSqr(destination) > radiusSqr) {
                continue;
            }
            if (this.seenContacts.add(player.getUUID())) {
                if (fresh == null) {
                    fresh = new ArrayList<>(2);
                }
                fresh.add(player.getGameProfile().getName());
            }
        }
        return fresh == null ? List.of() : fresh;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Classified", this.classified);
        tag.putBoolean("Collecting", this.collecting);
        if (this.exchangeId != null) {
            tag.putUUID("Exchange", this.exchangeId);
        }
        if (this.stationCode != null) {
            tag.putString("Station", this.stationCode);
        }
        if (this.assignment != null) {
            tag.put("Work", this.assignment.save());
        }
        if (!this.program.isEmpty()) {
            tag.put("Program", this.program.save());
        }
        return tag;
    }

    void load(CompoundTag tag) {
        this.classified = tag.getBoolean("Classified");
        this.collecting = tag.getBoolean("Collecting");
        this.exchangeId = tag.hasUUID("Exchange") ? tag.getUUID("Exchange") : null;
        this.stationCode = tag.contains("Station") ? tag.getString("Station") : null;
        this.assignment = tag.contains("Work") ? WorkAssignment.load(tag.getCompound("Work")) : null;
        this.program = tag.contains("Program") ? DroneProgram.load(tag.getCompound("Program")) : DroneProgram.EMPTY;
    }
}
