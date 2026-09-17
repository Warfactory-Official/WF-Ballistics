package com.wf.wfballistics.orbital;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** One bird, live. */
public final class Satellite {

    private final long id;

    private ResourceLocation payloadId;
    private SatPayload payload;
    /** The payload's own tag, kept for a payload id nothing is registered under. */
    private CompoundTag orphanTag;

    private long netId;
    @Nullable
    private UUID owner;
    @Nullable
    private BlockPos station;
    private String callsign;

    private OrbitElements elements;
    private boolean parked;
    private double parkX;
    private double parkZ;

    private double fuel;
    private double fuelCapacity;
    private double power;
    private double powerCapacity;
    private double solarRate;

    private double swath;
    private double resolution;
    private int magazine;
    private int pointDefence;

    /** Ticks until something closing on this bird arrives, or {@code -1} for nothing inbound. */
    private int threatTicks = -1;

    private boolean inContact;
    private int contactHops;
    private boolean jammed;
    private String reply = "";
    private boolean alive = true;

    Satellite(long id, SatSpec spec, OrbitElements elements) {
        this.id = id;
        this.payloadId = spec.payload();
        this.payload = SatPayloads.create(spec.payload());
        this.netId = spec.netId();
        this.owner = spec.owner();
        this.station = spec.station();
        this.callsign = spec.callsign();
        this.elements = elements;
        this.fuel = spec.fuel();
        this.fuelCapacity = spec.fuelCapacity();
        this.power = spec.power();
        this.powerCapacity = spec.powerCapacity();
        this.solarRate = spec.solarRate();
        this.swath = spec.swath();
        this.resolution = spec.resolution();
        this.magazine = spec.magazine();
        this.pointDefence = spec.pointDefence();
    }

    private Satellite(long id) {
        this.id = id;
        this.payloadId = SatPayloads.INERT;
        this.payload = SatPayloads.create(SatPayloads.INERT);
        this.callsign = "SAT";
        this.elements = OrbitElements.leo(0L, 0.0, 0.0, 0.0);
    }

    public SatId satId() {
        return new SatId(id);
    }

    public long rawId() {
        return id;
    }

    public ResourceLocation payloadId() {
        return payloadId;
    }

    public SatPayload payload() {
        return payload;
    }

    public long netId() {
        return netId;
    }

    @Nullable
    public UUID owner() {
        return owner;
    }

    @Nullable
    public BlockPos station() {
        return station;
    }

    public String callsign() {
        return callsign;
    }

    public OrbitElements elements() {
        return elements;
    }

    /** Where this bird is at a tick. */
    public Vec3 position(long gameTime) {
        return parked ? new Vec3(parkX, elements.altitude(), parkZ) : elements.at(gameTime);
    }

    public boolean parked() {
        return parked;
    }

    public double parkX() {
        return parkX;
    }

    public double parkZ() {
        return parkZ;
    }

    public double fuel() {
        return fuel;
    }

    public double fuelCapacity() {
        return fuelCapacity;
    }

    public double power() {
        return power;
    }

    public double powerCapacity() {
        return powerCapacity;
    }

    public double solarRate() {
        return solarRate;
    }

    /** Footprint radius in blocks: the spec's override, or read off the altitude if it did not give one. */
    public double swath() {
        return swath > 0.0 ? swath : OrbitalConfig.swathFor(elements.altitude());
    }

    /** Fix error in blocks, same rule. Roughly constant across a footprint rather than growing with range. */
    public double resolution() {
        return resolution > 0.0 ? resolution : OrbitalConfig.resolutionFor(elements.altitude());
    }

    public int magazine() {
        return magazine;
    }

    /** @return close-in engagements left. Zero is a bird that has to run rather than shoot. */
    public int pointDefence() {
        return pointDefence;
    }

    /**
     * Fire the close-in gun at something on its way in.
     *
     * @return false if there was no round or no charge, in which case nothing was spent and the defender is
     *      down to evading.
     */
    public boolean engage() {
        if (pointDefence <= 0 || !spendPower(OrbitalConfig.POINT_DEFENCE_POWER)) {
            return false;
        }
        pointDefence--;
        return true;
    }

    /** @return ticks until something inbound arrives, or -1 for a quiet sky. */
    public int threatTicks() {
        return threatTicks;
    }

    public void warn(int ticks) {
        this.threatTicks = ticks;
    }

    public void clearWarning() {
        this.threatTicks = -1;
    }

    /** @return true if a round was available and has now been spent. Spent is spent; resupply is a launch. */
    public boolean spendRound() {
        if (magazine <= 0) {
            return false;
        }
        magazine--;
        return true;
    }

    /**
     * @return true if the bird could be talked to on the last housekeeping tick. Written by the manager rather
     *      than computed here, because contact depends on where every ground station on the network is, which is
     *      the network's business and not the satellite's.
     */
    public boolean inContact() {
        return inContact;
    }

    /** @return 0 for a direct link to a ground station, 1 through a relay. Costs the picture a hop either way. */
    public int contactHops() {
        return contactHops;
    }

    /**
     * @return true if somebody's jammer is sitting on this bird's downlink.
     */
    public boolean jammed() {
        return jammed;
    }

    void setJammed(boolean on) {
        this.jammed = on;
    }

    public boolean alive() {
        return alive;
    }

    /** The last thing this bird said, in NTM's {@code tx} shape: one string, overwritten by the next reply. */
    public String reply() {
        return reply;
    }

    /** Say something a ground station will read on the next downlink. */
    public void report(String line) {
        setReply(line);
    }

    /**
     * Take fuel for a manoeuvre or a tick of parking.
     *
     * @return false, and spends nothing, if the tank cannot cover it. A partial burn is not a thing: it would
     *      turn "out of fuel" into a slow degradation nobody can read off a gauge.
     */
    public boolean spendFuel(double amount) {
        if (amount <= 0.0) {
            return true;
        }
        if (fuel < amount) {
            return false;
        }
        fuel -= amount;
        return true;
    }

    /**
     * Take charge to do something.
     *
     * @return false, and spends nothing, if the battery cannot cover it. Same all-or-nothing rule as fuel, and
     *      the reason a flat bird stops working rather than working badly.
     */
    public boolean spendPower(double amount) {
        if (amount <= 0.0) {
            return true;
        }
        if (power < amount) {
            return false;
        }
        power -= amount;
        return true;
    }

    /**
     * Change orbit: new elements, new epoch, one tank's worth of manoeuvre spent.
     *
     * @return false if there was not enough fuel, in which case nothing moved and nothing was spent.
     */
    public boolean manoeuvre(long now, double x, double z, double heading) {
        if (!spendFuel(OrbitalConfig.MANOEUVRE_FUEL)) {
            return false;
        }
        elements = elements.manoeuvre(now, x, z, heading);
        parked = false;
        return true;
    }

    /**
     * Hold station over a point.
     *
     * @return false if the tank could not pay to get there.
     */
    public boolean park(double x, double z) {
        if (parked && parkX == x && parkZ == z) {
            return true;
        }
        if (!spendFuel(OrbitalConfig.MANOEUVRE_FUEL)) {
            return false;
        }
        parked = true;
        parkX = x;
        parkZ = z;
        return true;
    }

    /** Break station and resume orbiting from where the bird is standing. */
    public void unpark(long now) {
        if (!parked) {
            return;
        }
        parked = false;
        elements = new OrbitElements(now, elements.altitude(), parkX, parkZ, elements.heading(), 0.5,
                elements.periodTicks(), elements.driftPerPass());
    }

    /** @return an immutable snapshot for anybody outside this package. */
    public SatView view(long gameTime) {
        Vec3 at = position(gameTime);
        return new SatView(satId(), payloadId, netId, owner, callsign, elements, at.x, at.y, at.z,
                parked, parkX, parkZ, fuel, fuelCapacity, power, powerCapacity,
                swath(), resolution(), magazine, pointDefence, threatTicks, inContact, gameTime);
    }

    void setNetId(long id) {
        this.netId = id;
    }

    void setOwner(@Nullable UUID id) {
        this.owner = id;
    }

    void setCallsign(String name) {
        this.callsign = name;
    }

    void setContact(boolean contact, int hops) {
        this.inContact = contact;
        this.contactHops = hops;
    }

    void setReply(String line) {
        this.reply = line == null ? "" : line;
    }

    void kill() {
        this.alive = false;
    }

    void charge(double amount) {
        this.power = Math.min(powerCapacity, power + amount);
    }

    void drain(double amount) {
        this.power = Math.max(0.0, power - amount);
    }

    void drainFuel(double amount) {
        this.fuel = Math.max(0.0, fuel - amount);
    }

    void refuel(double amount) {
        this.fuel = Math.min(fuelCapacity, fuel + amount);
    }

    void setElements(OrbitElements updated) {
        this.elements = updated;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Id", id);
        tag.putString("Payload", payloadId.toString());
        tag.putLong("Net", netId);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (station != null) {
            tag.putLong("Station", station.asLong());
        }
        tag.putString("Callsign", callsign);
        tag.put("Elements", elements.save());
        tag.putBoolean("Parked", parked);
        tag.putDouble("ParkX", parkX);
        tag.putDouble("ParkZ", parkZ);
        tag.putDouble("Fuel", fuel);
        tag.putDouble("FuelCap", fuelCapacity);
        tag.putDouble("Power", power);
        tag.putDouble("PowerCap", powerCapacity);
        tag.putDouble("Solar", solarRate);
        tag.putDouble("Swath", swath);
        tag.putDouble("Resolution", resolution);
        tag.putInt("Magazine", magazine);
        tag.putInt("PointDefence", pointDefence);
        tag.putString("Reply", reply);
        tag.put("PayloadData", orphanTag != null ? orphanTag : payload.save());
        return tag;
    }

    static Satellite load(CompoundTag tag) {
        Satellite sat = new Satellite(tag.getLong("Id"));
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("Payload"));
        sat.payloadId = id == null ? SatPayloads.INERT : id;
        CompoundTag data = tag.getCompound("PayloadData");
        if (SatPayloads.contains(sat.payloadId)) {
            sat.payload = SatPayloads.create(sat.payloadId);
            sat.payload.load(data);
            sat.orphanTag = null;
        } else {
            sat.payload = SatPayloads.create(SatPayloads.INERT);
            sat.orphanTag = data.copy();
        }
        sat.netId = tag.getLong("Net");
        sat.owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        sat.station = tag.contains("Station") ? BlockPos.of(tag.getLong("Station")) : null;
        sat.callsign = tag.getString("Callsign");
        sat.elements = OrbitElements.load(tag.getCompound("Elements"));
        sat.parked = tag.getBoolean("Parked");
        sat.parkX = tag.getDouble("ParkX");
        sat.parkZ = tag.getDouble("ParkZ");
        sat.fuel = tag.getDouble("Fuel");
        sat.fuelCapacity = tag.getDouble("FuelCap");
        sat.power = tag.getDouble("Power");
        sat.powerCapacity = tag.getDouble("PowerCap");
        sat.solarRate = tag.getDouble("Solar");
        sat.swath = tag.getDouble("Swath");
        sat.resolution = tag.getDouble("Resolution");
        sat.magazine = tag.getInt("Magazine");
        sat.pointDefence = tag.getInt("PointDefence");
        sat.reply = tag.getString("Reply");
        return sat;
    }
}
