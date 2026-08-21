package com.wf.wfballistics.drone;

import com.wf.wfballistics.drone.ai.PowerPolicy;
import com.wf.wfballistics.drone.ai.coord.CoordinationModels;
import com.wf.wfballistics.drone.flight.Airframe;
import com.wf.wfballistics.drone.squad.Formation;
import com.wf.wfballistics.drone.squad.Formations;
import com.wf.wfballistics.exchange.ExchangeMode;
import com.wf.wfballistics.exchange.Obfuscation;
import com.wf.wfballistics.exchange.StationCode;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A delivery order: what to fly, where to, where back, and how many. The drone counterpart of
 * {@code LaunchConfig}, and the single config both dispatch paths (the command and the drone pad) fill in.
 */
public final class DroneMission {

    /**
     * Default gap between launches, in ticks. A second: long enough that the drone ahead is well clear of the
     * pad (at the stock climb rate that is seven blocks, against a hull a little over one block tall) and
     * short enough that even a maximum flight of sixteen is fully airborne in a quarter of a minute.
     */
    public static final int DEFAULT_LAUNCH_INTERVAL = 20;
    /**
     * The range a configured interval is held to, applied where it enters the system. The ceiling is a
     * minute, past which a flight spends more battery waiting for itself than the stagger can be worth.
     */
    public static final int MAX_LAUNCH_INTERVAL = 1200;

    public ResourceLocation modelId = DroneModels.DEFAULT;
    public Vec3 destination = Vec3.ZERO;
    /**
     * Where the drones return to. Defaults to the dispatch point when left null.
     */
    @Nullable
    public Vec3 exfil;
    public int count = 1;
    public ResourceLocation formationId = Formations.DEFAULT;
    /**
     * How far apart the flight holds its slots, in blocks. The formation's shape and its size are two
     * separate choices, a wedge is a wedge whether it is flown tight or loose, so this is its own setting
     * rather than something baked into the shape.
     */
    public double formationSpacing = Formation.DEFAULT_SPACING;
    /**
     * Which architecture the flight holds its shape with: what the slots are measured <em>against</em>, as
     * opposed to where the slots are. Separate from the shape and from the spacing because it is a genuinely
     * separate decision, and the one of the three that decides how tightly the formation is actually held.
     */
    public ResourceLocation coordinationId = CoordinationModels.DEFAULT;
    /**
     * Ticks between one drone leaving the pad and the next, or 0 to put the whole flight up at once.
     *
     * <p>A flight goes up one at a time by default, and the reason is what happens afterwards rather than how
     * it looks: drones released together are three-block hulls all trying to occupy the same climb-out, and
     * the ones shouldered aside spend the first part of the mission recovering from a shove rather than
     * forming up. Released in series, each has the airspace to itself. What makes it a formation rather than
     * a straggle is that nobody leaves until everybody is up: see {@code DroneState#MUSTER}.
     *
     * <p>Zero is still honoured and is what the scenarios use, because a test that wants four drones in the
     * air on the tick it asked for should get them.
     */
    public int launchInterval = DEFAULT_LAUNCH_INTERVAL;
    public double cruiseSpeed = DroneEntity.DEFAULT_CRUISE_SPEED;
    public double cruiseAltitude = DroneEntity.DEFAULT_CRUISE_ALTITUDE;
    public double batteryCapacity = DroneBattery.DEFAULT_CAPACITY;
    /**
     * Warhead to sling under each drone, by registered id. Null makes this a cargo delivery instead of a
     * strike: the two mission kinds differ only by this field.
     */
    @Nullable
    public ResourceLocation payloadId;
    public double releaseSpeed = DroneEntity.DEFAULT_RELEASE_SPEED;
    /**
     * How hard this delivery tries to hide where it came from.
     */
    public ExchangeMode mode = ExchangeMode.DIRECT;
    /**
     * The station being traded with, for a handshake. A code, never a position: the server resolves it, and
     * the client that typed it in never learns what it resolved to.
     */
    @Nullable
    public String recipientCode;
    /**
     * The queued mission steps. Empty means the classic one-stop mission, and {@link #destination} is then
     * the whole of it; a program replaces that with as many stops as were written, and the destination
     * becomes whichever one the drone is currently flying to.
     */
    public DroneProgram program = DroneProgram.EMPTY;

    /**
     * Staging waypoints flown before the destination, and after the drop. Drawn from a cryptographic source
     * when the mission is dispatched, so neither the sender nor an observer can predict the shape of the
     * route.
     */
    public List<Vec3> approachLegs = List.of();
    public List<Vec3> egressLegs = List.of();
    /**
     * True if this drone is going to pick a crate up rather than put one down.
     */
    public boolean collecting;
    @Nullable
    public java.util.UUID exchangeId;
    @Nullable
    public String stationCode;
    /**
     * The construction or salvage job this flight is being sent to work, or null for an ordinary mission.
     *
     * <p>Server-side and never on the wire, like the fields above it. Saved, because the launch queue holds
     * the mission for the drones still to come and every one of them has to join the same job: a flight
     * half of which is building and half of which flew to the site and went home again is not a flight.
     */
    @Nullable
    public java.util.UUID jobId;

    /**
     * @return true if this mission drops an explosive rather than a crate.
     */
    public boolean isStrike() {
        return payloadId != null;
    }

    /**
     * Serialise for the wire.
     *
     * <p><b>A handshake writes no coordinates.</b> Not blanked, not zeroed: the fields are simply absent
     * from the packet, so there is nothing for a modified client to read and nothing for the server to be
     * tricked into trusting. The destination of a handshake exists only on the server, resolved from a
     * station code after this packet has been received.
     */
    public void write(FriendlyByteBuf b) {
        b.writeResourceLocation(modelId);
        b.writeEnum(mode);
        b.writeUtf(recipientCode == null ? "" : recipientCode, StationCode.LENGTH);
        if (!mode.resolvesDestination()) {
            b.writeDouble(destination.x);
            b.writeDouble(destination.y);
            b.writeDouble(destination.z);
        }
        b.writeBoolean(exfil != null);
        if (exfil != null) {
            b.writeDouble(exfil.x);
            b.writeDouble(exfil.y);
            b.writeDouble(exfil.z);
        }
        b.writeVarInt(count);
        b.writeResourceLocation(formationId);
        b.writeDouble(formationSpacing);
        b.writeResourceLocation(coordinationId);
        b.writeVarInt(launchInterval);
        b.writeDouble(cruiseSpeed);
        b.writeDouble(cruiseAltitude);
        b.writeDouble(batteryCapacity);
        b.writeBoolean(payloadId != null);
        if (payloadId != null) {
            b.writeResourceLocation(payloadId);
        }
        b.writeDouble(releaseSpeed);
        program.write(b);
    }

    public static DroneMission read(FriendlyByteBuf b) {
        DroneMission m = new DroneMission();
        m.modelId = DroneModels.parse(b.readResourceLocation().toString());
        m.mode = b.readEnum(ExchangeMode.class);
        String code = StationCode.normalise(b.readUtf(StationCode.LENGTH));
        m.recipientCode = StationCode.valid(code) ? code : null;
        m.destination = m.mode.resolvesDestination()
                ? Vec3.ZERO : new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        m.exfil = b.readBoolean() ? new Vec3(b.readDouble(), b.readDouble(), b.readDouble()) : null;
        m.count = b.readVarInt();
        m.formationId = Formations.parse(b.readResourceLocation().toString());
        m.formationSpacing = Formation.clampSpacing(b.readDouble());
        m.coordinationId = CoordinationModels.parse(b.readResourceLocation().toString());
        m.launchInterval = clampInterval(b.readVarInt());
        m.cruiseSpeed = b.readDouble();
        m.cruiseAltitude = b.readDouble();
        m.batteryCapacity = b.readDouble();
        m.payloadId = b.readBoolean() ? WarheadRegistry.parse(b.readResourceLocation().toString()) : null;
        m.releaseSpeed = b.readDouble();
        m.program = DroneProgram.read(b);
        return m;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("ModelId", modelId.toString());
        tag.putDouble("DestX", destination.x);
        tag.putDouble("DestY", destination.y);
        tag.putDouble("DestZ", destination.z);
        if (exfil != null) {
            tag.putDouble("ExfilX", exfil.x);
            tag.putDouble("ExfilY", exfil.y);
            tag.putDouble("ExfilZ", exfil.z);
        }
        tag.putInt("Count", count);
        tag.putString("Formation", formationId.toString());
        tag.putDouble("FormationSpacing", formationSpacing);
        tag.putString("Coordination", coordinationId.toString());
        tag.putInt("LaunchInterval", launchInterval);
        tag.putDouble("CruiseSpeed", cruiseSpeed);
        tag.putDouble("CruiseAltitude", cruiseAltitude);
        tag.putDouble("BatteryCapacity", batteryCapacity);
        if (payloadId != null) {
            tag.putString("Payload", payloadId.toString());
        }
        tag.putDouble("ReleaseSpeed", releaseSpeed);
        tag.putString("Mode", mode.name());
        if (recipientCode != null) {
            tag.putString("Recipient", recipientCode);
        }
        if (!program.isEmpty()) {
            tag.put("Program", program.save());
        }
        putLegs(tag, "ApproachLegs", approachLegs);
        putLegs(tag, "EgressLegs", egressLegs);
        if (collecting) {
            tag.putBoolean("Collecting", true);
        }
        if (exchangeId != null) {
            tag.putUUID("ExchangeId", exchangeId);
        }
        if (stationCode != null) {
            tag.putString("StationCode", stationCode);
        }
        if (jobId != null) {
            tag.putUUID("JobId", jobId);
        }
        return tag;
    }

    private static void putLegs(CompoundTag tag, String key, List<Vec3> legs) {
        if (legs.isEmpty()) {
            return;
        }
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Vec3 leg : legs) {
            CompoundTag one = new CompoundTag();
            one.putDouble("X", leg.x);
            one.putDouble("Y", leg.y);
            one.putDouble("Z", leg.z);
            list.add(one);
        }
        tag.put(key, list);
    }

    private static List<Vec3> readLegs(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return List.of();
        }
        net.minecraft.nbt.ListTag list = tag.getList(key, net.minecraft.nbt.Tag.TAG_COMPOUND);
        List<Vec3> legs = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            legs.add(new Vec3(one.getDouble("X"), one.getDouble("Y"), one.getDouble("Z")));
        }
        return List.copyOf(legs);
    }

    public static DroneMission load(CompoundTag tag) {
        DroneMission m = new DroneMission();
        m.modelId = DroneModels.parse(tag.getString("ModelId"));
        m.destination = new Vec3(tag.getDouble("DestX"), tag.getDouble("DestY"), tag.getDouble("DestZ"));
        if (tag.contains("ExfilX")) {
            m.exfil = new Vec3(tag.getDouble("ExfilX"), tag.getDouble("ExfilY"), tag.getDouble("ExfilZ"));
        }
        m.count = Math.max(1, tag.getInt("Count"));
        m.formationId = Formations.parse(tag.getString("Formation"));
        m.formationSpacing = tag.contains("FormationSpacing")
                ? Formation.clampSpacing(tag.getDouble("FormationSpacing")) : Formation.DEFAULT_SPACING;
        m.coordinationId = tag.contains("Coordination")
                ? CoordinationModels.parse(tag.getString("Coordination")) : CoordinationModels.LEGACY;
        m.launchInterval = tag.contains("LaunchInterval") ? clampInterval(tag.getInt("LaunchInterval")) : 0;
        m.cruiseSpeed = tag.contains("CruiseSpeed") ? tag.getDouble("CruiseSpeed") : DroneEntity.DEFAULT_CRUISE_SPEED;
        m.cruiseAltitude = tag.contains("CruiseAltitude")
                ? tag.getDouble("CruiseAltitude") : DroneEntity.DEFAULT_CRUISE_ALTITUDE;
        m.batteryCapacity = tag.contains("BatteryCapacity")
                ? tag.getDouble("BatteryCapacity") : DroneBattery.DEFAULT_CAPACITY;
        if (tag.contains("Payload")) {
            m.payloadId = WarheadRegistry.parse(tag.getString("Payload"));
        }
        m.releaseSpeed = tag.contains("ReleaseSpeed")
                ? tag.getDouble("ReleaseSpeed") : DroneEntity.DEFAULT_RELEASE_SPEED;
        m.mode = ExchangeMode.byName(tag.getString("Mode"));
        m.recipientCode = tag.contains("Recipient") ? tag.getString("Recipient") : null;
        m.program = tag.contains("Program") ? DroneProgram.load(tag.getCompound("Program")) : DroneProgram.EMPTY;
        m.approachLegs = readLegs(tag, "ApproachLegs");
        m.egressLegs = readLegs(tag, "EgressLegs");
        m.collecting = tag.getBoolean("Collecting");
        m.exchangeId = tag.hasUUID("ExchangeId") ? tag.getUUID("ExchangeId") : null;
        m.stationCode = tag.contains("StationCode") ? tag.getString("StationCode") : null;
        m.jobId = tag.hasUUID("JobId") ? tag.getUUID("JobId") : null;
        return m;
    }

    /**
     * @return the charge a single drone is short by for the outbound leg, or 0 if it can make it. A mission
     * it cannot even reach the destination on is refused rather than launched to strand halfway.
     */
    public double shortfall(Vec3 origin, boolean hasCargo) {
        return PowerPolicy.shortfallForRoute(batteryCapacity, PowerProfile.DEFAULT, Airframe.QUADCOPTER,
                cruiseSpeed, outboundDistance(origin), hasCargo);
    }

    public boolean canRoundTrip(Vec3 origin, boolean hasCargo) {
        return PowerPolicy.canRoundTripForRoute(batteryCapacity, PowerProfile.DEFAULT, Airframe.QUADCOPTER,
                cruiseSpeed, outboundDistance(origin), returnDistance(origin), hasCargo);
    }

    /**
     * @return how far the drone actually flies to reach the destination, following every staging waypoint.
     *
     * <p>The dogleg is not free, and pricing the mission on the straight-line distance would launch drones
     * that strand a third of the way round the detour. This is the difference between "indirect costs more"
     * being a design statement and it being true.
     */
    public double outboundDistance(Vec3 origin) {
        Vec3 from = origin;
        double total = 0.0;
        for (Vec3 leg : approachLegs) {
            total += from.distanceTo(leg);
            from = leg;
        }
        if (!program.isEmpty()) {
            return total + program.routeLength(from, exfilOr(origin));
        }
        return total + from.distanceTo(destination);
    }

    /**
     * @return how far the drone flies home, following the egress dogleg.
     */
    public double returnDistance(Vec3 origin) {
        Vec3 from = program.endsAt(destination, exfilOr(origin));
        double total = 0.0;
        for (Vec3 leg : egressLegs) {
            total += from.distanceTo(leg);
            from = leg;
        }
        return total + from.distanceTo(exfilOr(origin));
    }

    /**
     * Draw the staging waypoints for this mission's mode. Called server-side at dispatch, from a
     * cryptographic source.
     */
    public void planObfuscation(java.util.random.RandomGenerator rng, Vec3 origin) {
        if (mode == ExchangeMode.DIRECT) {
            approachLegs = List.of();
            egressLegs = List.of();
            return;
        }
        approachLegs = Obfuscation.approachLegs(rng, origin, destination);
        egressLegs = Obfuscation.egressLegs(rng, destination, exfilOr(origin));
    }

    public Vec3 exfilOr(Vec3 origin) {
        return exfil != null ? exfil : origin;
    }

    /**
     * Spawn and launch the drones. The first is the squad leader and carries the cargo; the rest form up on
     * it. A mission out of battery range is refused.
     *
     * @param cargo optional cargo for the leader, as saved container contents. Non-null means "a crate,
     *              even if it is empty": no crate entity is created here or at any point in flight
     */
    public Result dispatch(ServerLevel level, Vec3 origin, @Nullable CompoundTag cargo) {
        if (!program.isEmpty()) {
            Vec3 first = program.destination(exfilOr(origin));
            if (first != null) {
                destination = first;
            }
        }
        if (approachLegs.isEmpty() && egressLegs.isEmpty() && mode != ExchangeMode.DIRECT) {
            planObfuscation(OBFUSCATION_RNG, origin);
        }
        double missing = shortfall(origin, cargo != null);
        if (missing > 0.0) {
            return new Result(List.of(), 0, mode.classified()
                    ? "not enough battery for this exchange"
                    : String.format("battery is %.0f charge short of the destination (%.0f blocks away)",
                            missing, origin.distanceTo(destination)));
        }

        int wanted = Math.max(1, count);
        long squadId = wanted > 1 ? squadId(level) : 0L;
        List<DroneEntity> spawned = new ArrayList<>(wanted);

        spawned.add(spawn(level, origin, squadId, 0, wanted));
        if (launchInterval <= 0) {
            for (int i = 1; i < wanted; i++) {
                spawned.add(spawn(level, origin, squadId, i, wanted));
            }
        } else if (wanted > 1) {
            DroneLaunchQueue.get(level).enqueue(this, origin, squadId, wanted,
                    level.getGameTime() + launchInterval);
        }

        if (cargo != null) {
            spawned.get(0).loadCargo(cargo);
        }
        return new Result(List.copyOf(spawned), wanted, null);
    }

    /**
     * Put one drone of this mission on the ground and hand it over to the AI.
     *
     * <p>Separated from {@link #dispatch} because the rest of the flight is spawned later, by
     * {@link DroneLaunchQueue}, and both paths have to produce identical drones: a follower that came up a
     * second late but with a different battery or a different formation is not the same mission.
     *
     * @param index how far into the flight this drone is; 0 leads
     * @param total how many the mission ordered, which the drone has to know before the rest of them exist
     */
    public DroneEntity spawn(ServerLevel level, Vec3 origin, long squadId, int index, int total) {
        Vec3 at = origin.add((index % 2 == 0 ? -1 : 1) * ((index + 1) / 2) * DroneEntity.SPAWN_SPACING,
                0.0, 0.0);
        DroneEntity drone = new DroneEntity(level, at);
        drone.setModelId(modelId);
        drone.setDestination(destination);
        drone.setExfil(exfilOr(origin));
        drone.setCruiseSpeed(cruiseSpeed);
        drone.setCruiseAltitude(cruiseAltitude);
        drone.battery().setCapacity(batteryCapacity);
        drone.battery().recharge(batteryCapacity);
        drone.setSquad(squadId, index == 0, formationId);
        drone.setFormationSpacing(formationSpacing);
        drone.setCoordinationId(coordinationId);
        drone.setSquadSize(total);
        drone.setProgram(program);
        drone.setExchange(exchangeId, stationCode, mode.classified(), collecting,
                approachLegs, egressLegs);
        if (jobId != null) {
            drone.setAssignment(com.wf.wfballistics.build.BuildJobs.joining(jobId, level));
        }
        if (payloadId != null) {
            drone.setPayload(payloadId);
            drone.setReleaseSpeed(releaseSpeed);
        }
        level.addFreshEntity(drone);
        return drone;
    }

    /**
     * @return {@code interval} brought inside 0..{@link #MAX_LAUNCH_INTERVAL}.
     */
    public static int clampInterval(int interval) {
        return Math.max(0, Math.min(MAX_LAUNCH_INTERVAL, interval));
    }

    /**
     * Staging bearings come from a cryptographic source, not the level's seeded random. A predictable
     * detour is not a detour.
     */
    private static final java.security.SecureRandom OBFUSCATION_RNG = new java.security.SecureRandom();

    private static long squadId(ServerLevel level) {
        long id = level.getRandom().nextLong();
        return id != 0L ? id : 1L;
    }

    /**
     * @param drones  the drones that exist <em>now</em>. With a staggered launch that is just the leader:
     *                the rest are still queued, so anything counting aircraft wants {@link #ordered} instead
     * @param ordered how many the mission asked for
     * @param error   null on success, otherwise why the mission was refused
     */
    public record Result(List<DroneEntity> drones, int ordered, @Nullable String error) {
        public boolean ok() {
            return error == null;
        }
    }
}
