package com.wf.wfballistics.drone.sim;

import com.wf.wfballistics.drone.DroneEntity;
import com.wf.wfballistics.drone.DroneModels;
import com.wf.wfballistics.drone.DroneProgram;
import com.wf.wfballistics.drone.DroneState;
import com.wf.wfballistics.drone.PowerProfile;
import com.wf.wfballistics.drone.ai.DroneAction;
import com.wf.wfballistics.drone.ai.DroneBrain;
import com.wf.wfballistics.drone.ai.DroneCarrier;
import com.wf.wfballistics.drone.ai.coord.CoordinationModels;
import com.wf.wfballistics.drone.ai.DronePlan;
import com.wf.wfballistics.drone.ai.DroneNav;
import com.wf.wfballistics.drone.ai.DroneSnapshot;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.drone.flight.FlightAttitude;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * A drone flying over unloaded terrain: the same idea as {@code SimMissile}, but it keeps running the real
 * AI. Because {@link DroneSnapshot} can be built from this just as well as from a {@link DroneEntity}, an
 * offloaded drone is planned by the same brain and reaches its destination on the same schedule: there is
 * no second, simplified route model to keep in sync.
 *
 * <p>What it drops is the expensive half of being an entity: chunk tickets, collision, and client tracking.
 * It holds the altitude it had when it offloaded ({@link #simY}), since there is no terrain to measure
 * against out there.
 */
public final class SimDrone implements DroneCarrier {

    public UUID id;
    public Vec3 pos = Vec3.ZERO;
    public Vec3 velocity = Vec3.ZERO;
    public float yaw;
    /**
     * The airframe's lean and throttle. Carried out here too, because the sim runs the same flight model as a
     * real drone and the physics has to pick up next tick where it left off.
     */
    public FlightAttitude attitude = FlightAttitude.LEVEL;
    public DroneState state = DroneState.TRANSIT;
    public int stateTicks;
    @Nullable
    public Vec3 destination;
    public Vec3 exfil = Vec3.ZERO;
    public double charge;
    public double capacity;
    /**
     * The altitude held while offloaded. Stands in for a terrain sample, exactly as {@code SimMissile.simY}
     * does.
     */
    public double simY;
    public long squadId;
    public boolean leader;
    /**
     * The queued mission steps, carried across the off-world boundary intact so a drone that offloads
     * mid-program picks up exactly where it left off when it comes back.
     */
    public DroneProgram program = DroneProgram.EMPTY;
    public ResourceLocation formationId = Formations.DEFAULT;
    public double formationSpacing = Formation.DEFAULT_SPACING;
    public ResourceLocation coordinationId = CoordinationModels.DEFAULT;
    public int squadSize = 1;
    /**
     * The work job this drone was on when it offloaded.
     *
     * <p>Carried rather than dropped, and it has to be: an offloaded drone still holds a claim on a real
     * queue, and coming back without the assignment would leave that claim to time out: costing the
     * order an attempt for a journey that went perfectly well. The sim cannot <em>do</em> the work, but
     * it never has to: WORK and SUPPLY are not offloadable states, so a drone only ever offloads on the
     * leg between one block and the next.
     */
    @Nullable
    public com.wf.wfballistics.work.WorkAssignment assignment;
    public double cruiseSpeed = DroneEntity.DEFAULT_CRUISE_SPEED;
    public double cruiseAltitude = DroneEntity.DEFAULT_CRUISE_ALTITUDE;
    public double climbRate = DroneEntity.DEFAULT_CLIMB_RATE;
    public ResourceLocation modelId = DroneModels.DEFAULT;
    /**
     * The slung crate's saved contents, or null if the drone flew empty. The crate entity itself is
     * discarded on offload and rebuilt on onload, so its cargo has to ride along here.
     */
    @Nullable
    public CompoundTag cargo;
    /**
     * Warhead still slung under a strike drone, by registered id, or null
     */
    @Nullable
    public ResourceLocation payloadId;
    public double releaseSpeed = DroneEntity.DEFAULT_RELEASE_SPEED;
    public long lastGameTime;

    public static SimDrone fromEntity(DroneEntity drone, @Nullable CompoundTag cargo) {
        SimDrone sd = new SimDrone();
        sd.id = drone.getUUID();
        sd.pos = drone.position();
        sd.velocity = drone.getDeltaMovement();
        sd.yaw = drone.headingRadians();
        sd.attitude = drone.getAttitude();
        sd.state = drone.getDroneState();
        sd.destination = drone.getDestination();
        sd.exfil = drone.getExfil();
        sd.charge = drone.battery().charge();
        sd.capacity = drone.battery().capacity();
        sd.simY = drone.getY();
        sd.squadId = drone.squadId();
        sd.leader = drone.leader();
        sd.program = drone.getProgram();
        sd.formationId = drone.formationId();
        sd.formationSpacing = drone.formationSpacing();
        sd.coordinationId = drone.coordinationId();
        sd.squadSize = drone.squadSize();
        sd.assignment = drone.assignment();
        sd.cruiseSpeed = drone.getCruiseSpeed();
        sd.cruiseAltitude = drone.getCruiseAltitude();
        sd.climbRate = drone.getClimbRate();
        sd.modelId = drone.getModelId();
        sd.cargo = cargo;
        sd.payloadId = drone.getPayloadId();
        sd.releaseSpeed = drone.getReleaseSpeed();
        sd.lastGameTime = drone.level().getGameTime();
        return sd;
    }

    /**
     * Rebuild the real entity (and its crate) at {@code spawnPos}.
     */
    public DroneEntity toEntity(ServerLevel level, Vec3 spawnPos) {
        DroneEntity drone = new DroneEntity(level, spawnPos);
        drone.setUUID(this.id);
        drone.setModelId(this.modelId);
        drone.setDestination(this.destination);
        drone.setExfil(this.exfil);
        drone.setCruiseSpeed(this.cruiseSpeed);
        drone.setCruiseAltitude(this.cruiseAltitude);
        drone.battery().setCapacity(this.capacity);
        drone.battery().setCharge(this.charge);
        drone.setSquad(this.squadId, this.leader, this.formationId);
        drone.setFormationSpacing(this.formationSpacing);
        drone.setCoordinationId(this.coordinationId);
        drone.setSquadSize(this.squadSize);
        drone.setAssignment(this.assignment);
        drone.setProgram(this.program);
        drone.setPayload(this.payloadId);
        drone.setReleaseSpeed(this.releaseSpeed);
        drone.setState(this.state);
        drone.setHeadingRadians(this.yaw);
        drone.setAttitude(this.attitude);
        drone.setDeltaMovement(this.velocity);
        if (this.cargo != null) {
            drone.loadCargo(this.cargo);
        }
        return drone;
    }

    @Override
    public UUID droneId() {
        return this.id;
    }

    @Override
    public long squadId() {
        return this.squadId;
    }

    @Override
    public boolean leader() {
        return this.leader;
    }

    @Override
    public void promote() {
        this.leader = true;
    }

    @Override
    public ResourceLocation formationId() {
        return this.formationId;
    }

    @Override
    public double formationSpacing() {
        return this.formationSpacing;
    }

    @Override
    public ResourceLocation coordinationId() {
        return this.coordinationId;
    }

    @Override
    public boolean carrierAlive() {
        return true;
    }

    @Override
    public DroneSnapshot snapshot(ServerLevel level, long gameTime) {
        double groundY = this.simY - this.cruiseAltitude;
        double destGroundY = this.destination != null ? this.destination.y : groundY;
        return new DroneSnapshot(this.id, true, this.pos, this.velocity, this.yaw,
                this.attitude, Airframe.QUADCOPTER, DroneNav.NONE, this.state, this.stateTicks,
                this.destination, null, this.program, this.exfil,
                this.cargo != null, this.payloadId != null, false, true, List.of(),
                this.charge, this.capacity, PowerProfile.DEFAULT,
                this.cruiseSpeed, this.cruiseAltitude, this.climbRate, this.releaseSpeed,
                groundY, destGroundY, this.squadId, this.leader, this.squadSize, this.assignment,
                gameTime,
                this.id.getLeastSignificantBits());
    }

    @Override
    public void apply(ServerLevel level, DronePlan plan) {
        for (DroneAction action : plan.actions()) {
            if (action instanceof DroneAction.CompleteMission) {
                this.destination = null;
            } else if (action instanceof DroneAction.Retarget retarget) {
                this.destination = retarget.destination();
            } else if (action instanceof DroneAction.AdvanceTask) {
                this.program = this.program.advanced();
                this.destination = this.program.destination(this.exfil);
            } else if (action instanceof DroneAction.AbortProgram) {
                this.program = this.program.abandoned();
            }
        }
        if (plan.nextState() != null && plan.nextState() != this.state) {
            this.state = plan.nextState();
            this.stateTicks = 0;
        }
        this.yaw = plan.yaw();
        this.attitude = plan.attitude();
        this.advance(level, plan.velocity());
    }

    @Override
    public void coast(ServerLevel level) {
        this.advance(level, DroneBrain.coast(this.velocity));
    }

    private void advance(ServerLevel level, Vec3 velocity) {
        this.velocity = velocity;
        this.pos = this.pos.add(velocity);
        this.simY = this.pos.y;
        this.stateTicks++;
        boolean loaded = this.cargo != null || this.payloadId != null;
        this.charge = Math.max(0.0, this.charge - PowerProfile.DEFAULT.drain(this.state,
                this.attitude.throttle(), PowerProfile.DEFAULT.massFactor(loaded),
                velocity.horizontalDistance()));
        this.lastGameTime = level.getGameTime();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        putVec(tag, "Pos", pos);
        putVec(tag, "Vel", velocity);
        tag.putFloat("Yaw", yaw);
        tag.putDouble("TiltX", attitude.tiltX());
        tag.putDouble("TiltZ", attitude.tiltZ());
        tag.putDouble("Throttle", attitude.throttle());
        tag.putString("State", state.name());
        tag.putInt("StateTicks", stateTicks);
        if (destination != null) {
            putVec(tag, "Dest", destination);
        }
        putVec(tag, "Exfil", exfil);
        tag.putDouble("Charge", charge);
        tag.putDouble("Capacity", capacity);
        tag.putDouble("SimY", simY);
        tag.putLong("SquadId", squadId);
        tag.putBoolean("Leader", leader);
        tag.putString("Formation", formationId.toString());
        tag.putDouble("FormationSpacing", formationSpacing);
        tag.putString("Coordination", coordinationId.toString());
        tag.putInt("SquadSize", squadSize);
        if (assignment != null) {
            tag.put("Work", assignment.save());
        }
        tag.putDouble("CruiseSpeed", cruiseSpeed);
        tag.putDouble("CruiseAltitude", cruiseAltitude);
        tag.putDouble("ClimbRate", climbRate);
        tag.putString("ModelId", modelId.toString());
        if (cargo != null) {
            tag.put("Cargo", cargo);
        }
        if (payloadId != null) {
            tag.putString("Payload", payloadId.toString());
        }
        tag.putDouble("ReleaseSpeed", releaseSpeed);
        tag.putLong("LastGameTime", lastGameTime);
        if (!program.isEmpty()) {
            tag.put("Program", program.save());
        }
        return tag;
    }

    public static SimDrone load(CompoundTag tag) {
        SimDrone sd = new SimDrone();
        sd.id = tag.getUUID("Id");
        sd.pos = getVec(tag, "Pos");
        sd.velocity = getVec(tag, "Vel");
        sd.yaw = tag.getFloat("Yaw");
        sd.attitude = tag.contains("Throttle")
                ? new FlightAttitude(tag.getDouble("TiltX"), tag.getDouble("TiltZ"), tag.getDouble("Throttle"))
                : FlightAttitude.LEVEL;
        try {
            sd.state = DroneState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            sd.state = DroneState.TRANSIT;
        }
        sd.stateTicks = tag.getInt("StateTicks");
        if (tag.contains("DestX")) {
            sd.destination = getVec(tag, "Dest");
        }
        sd.exfil = getVec(tag, "Exfil");
        sd.charge = tag.getDouble("Charge");
        sd.capacity = Math.max(1.0, tag.getDouble("Capacity"));
        sd.simY = tag.getDouble("SimY");
        sd.squadId = tag.getLong("SquadId");
        sd.leader = tag.getBoolean("Leader");
        sd.formationId = Formations.parse(tag.getString("Formation"));
        sd.formationSpacing = tag.contains("FormationSpacing")
                ? Formation.clampSpacing(tag.getDouble("FormationSpacing")) : Formation.DEFAULT_SPACING;
        sd.coordinationId = tag.contains("Coordination")
                ? CoordinationModels.parse(tag.getString("Coordination")) : CoordinationModels.LEGACY;
        sd.squadSize = Math.max(1, tag.getInt("SquadSize"));
        sd.assignment = tag.contains("Work")
                ? com.wf.wfballistics.work.WorkAssignment.load(tag.getCompound("Work")) : null;
        sd.cruiseSpeed = tag.getDouble("CruiseSpeed");
        sd.cruiseAltitude = tag.getDouble("CruiseAltitude");
        sd.climbRate = tag.getDouble("ClimbRate");
        sd.modelId = DroneModels.parse(tag.getString("ModelId"));
        if (tag.contains("Cargo")) {
            sd.cargo = tag.getCompound("Cargo");
        }
        if (tag.contains("Payload")) {
            sd.payloadId = WarheadRegistry.parse(tag.getString("Payload"));
        }
        sd.releaseSpeed = tag.contains("ReleaseSpeed")
                ? tag.getDouble("ReleaseSpeed") : DroneEntity.DEFAULT_RELEASE_SPEED;
        sd.lastGameTime = tag.getLong("LastGameTime");
        sd.program = tag.contains("Program") ? DroneProgram.load(tag.getCompound("Program")) : DroneProgram.EMPTY;
        return sd;
    }

    private static void putVec(CompoundTag tag, String key, Vec3 v) {
        tag.putDouble(key + "X", v.x);
        tag.putDouble(key + "Y", v.y);
        tag.putDouble(key + "Z", v.z);
    }

    private static Vec3 getVec(CompoundTag tag, String key) {
        return new Vec3(tag.getDouble(key + "X"), tag.getDouble(key + "Y"), tag.getDouble(key + "Z"));
    }
}
