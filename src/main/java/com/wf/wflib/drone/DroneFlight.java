package com.wf.wflib.drone;

import com.wf.wflib.drone.ai.TerrainSampler;
import com.wf.wflib.drone.flight.Contacts;
import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Flight state machine, attitude (synced), cruise envelope, and moving the body through the world. */
public final class DroneFlight {

    public static final double DEFAULT_CRUISE_SPEED = 0.75;
    public static final double DEFAULT_CRUISE_ALTITUDE = 40.0;
    public static final double DEFAULT_CLIMB_RATE = 0.35;
    /**
     * Speed an attack run is flown at. Faster than cruise: the payload's throw comes from the airframe.
     */
    public static final double DEFAULT_RELEASE_SPEED = 1.2;
    /**
     * Byte units per radian of lean, and per unit of throttle.
     */
    public static final float TILT_QUANTUM = 90.0f;
    public static final float THROTTLE_QUANTUM = 50.0f;
    /**
     * Downward speed applied to a grounded drone until it is resting, so one that finishes a landing a fraction of
     * a block high settles instead of hovering.
     */
    private static final double SETTLE_SPEED = 0.08;
    /** How far above the surface a drone under power is held. */
    private static final double SKIM_CLEARANCE = 0.5;

    private final DroneEntity drone;
    private DroneState state = DroneState.IDLE;
    private int stateTicks;
    /** How the airframe is leaning and how hard the rotors are working. */
    private FlightAttitude attitude = FlightAttitude.STOPPED;
    private double cruiseSpeed = DEFAULT_CRUISE_SPEED;
    private double cruiseAltitude = DEFAULT_CRUISE_ALTITUDE;
    private double climbRate = DEFAULT_CLIMB_RATE;
    private double releaseSpeed = DEFAULT_RELEASE_SPEED;
    /** Surface height under the drone as of the last snapshot. */
    private double lastGroundY = Double.NaN;

    DroneFlight(DroneEntity drone) {
        this.drone = drone;
    }

    void tick() {
        this.stateTicks++;
    }

    public int stateTicks() {
        return this.stateTicks;
    }

    void noteGround(double groundY) {
        this.lastGroundY = groundY;
    }

    public DroneState getDroneState() {
        return this.state;
    }

    public void setState(DroneState state) {
        if (this.state == state) {
            return;
        }
        this.state = state;
        this.stateTicks = 0;
        this.drone.getEntityData().set(DroneEntity.STATE, (byte) state.ordinal());
        if (state.airborne()) {
            this.drone.onAirborne();
        }
    }

    /** Client: the synced byte is the only writer of the state there. */
    void syncState(byte ordinal) {
        DroneState[] states = DroneState.values();
        this.state = ordinal >= 0 && ordinal < states.length ? states[ordinal] : DroneState.IDLE;
    }

    public FlightAttitude getAttitude() {
        return this.attitude;
    }

    /**
     * Store the attitude the flight model settled on and publish it to whoever is watching.
     */
    public void setAttitude(FlightAttitude attitude) {
        this.attitude = attitude;
        float yaw = this.drone.headingRadians();
        this.drone.getEntityData().set(DroneEntity.ROLL, quantise(attitude.roll(yaw), TILT_QUANTUM));
        this.drone.getEntityData().set(DroneEntity.PITCH, quantise(attitude.pitch(yaw), TILT_QUANTUM));
        this.drone.getEntityData().set(DroneEntity.THROTTLE, quantise(attitude.throttle(), THROTTLE_QUANTUM));
    }

    private static byte quantise(double value, float quantum) {
        return (byte) Math.max(-127, Math.min(127, Math.round(value * quantum)));
    }

    /**
     * @return lean about the nose axis, radians, as the client sees it.
     */
    public float getRoll() {
        return this.drone.getEntityData().get(DroneEntity.ROLL) / TILT_QUANTUM;
    }

    /**
     * @return lean about the wing axis, radians: positive is nose down.
     */
    public float getPitch() {
        return this.drone.getEntityData().get(DroneEntity.PITCH) / TILT_QUANTUM;
    }

    /**
     * @return thrust as a multiple of an unladen hover, so 1.0 is holding station and 0 is a dead rotor.
     */
    public float getThrottle() {
        return this.drone.getEntityData().get(DroneEntity.THROTTLE) / THROTTLE_QUANTUM;
    }

    public double getCruiseSpeed() {
        return this.cruiseSpeed;
    }

    public void setCruiseSpeed(double cruiseSpeed) {
        this.cruiseSpeed = cruiseSpeed;
    }

    public double getCruiseAltitude() {
        return this.cruiseAltitude;
    }

    public void setCruiseAltitude(double cruiseAltitude) {
        this.cruiseAltitude = cruiseAltitude;
    }

    public double getClimbRate() {
        return this.climbRate;
    }

    public double getReleaseSpeed() {
        return this.releaseSpeed;
    }

    public void setReleaseSpeed(double releaseSpeed) {
        this.releaseSpeed = releaseSpeed;
    }

    void moveWith(Vec3 velocity) {
        DroneEntity d = this.drone;
        if (!this.state.airborne()) {
            d.setDeltaMovement(Vec3.ZERO);
            double rest = this.lastGroundY;
            if (!Double.isNaN(rest) && d.getY() > rest) {
                d.setPos(d.getX(), Math.max(rest, d.getY() - SETTLE_SPEED), d.getZ());
            }
            d.refitBounds();
            this.resolveContacts();
            return;
        }
        d.setDeltaMovement(velocity);
        d.hasImpulse = true;
        d.move(MoverType.SELF, velocity);
        this.resolveContacts();
        this.clampAboveGround();
        d.refitBounds();
    }

    /** Push out of any drone this one ended the tick inside of. */
    private void resolveContacts() {
        DroneEntity d = this.drone;
        AABB box = d.getBoundingBox();
        List<DroneEntity> touching = d.level().getEntitiesOfClass(DroneEntity.class, box,
                other -> other != d && other.isAlive());
        if (touching.isEmpty()) {
            return;
        }
        Vec3 escape = Vec3.ZERO;
        for (DroneEntity other : touching) {
            escape = escape.add(Contacts.escape(box, other.getBoundingBox(), d.getId() < other.getId()));
        }
        Vec3 step = Contacts.step(escape);
        if (step.lengthSqr() < 1.0E-12) {
            return;
        }
        d.setPos(d.getX() + step.x, d.getY() + step.y, d.getZ() + step.z);
        d.refitBounds();
    }

    /** Refuse to end a tick inside the ground. */
    private void clampAboveGround() {
        DroneEntity d = this.drone;
        if (!(d.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        double ground = TerrainSampler.measure(serverLevel, d.getX(), d.getZ());
        if (Double.isNaN(ground)) {
            return;
        }
        this.lastGroundY = ground;
        double floor = ground + (this.state.followsTerrain() ? SKIM_CLEARANCE : 0.0);
        if (d.getY() >= floor) {
            return;
        }
        d.setPos(d.getX(), floor, d.getZ());
        Vec3 motion = d.getDeltaMovement();
        if (motion.y < 0.0) {
            d.setDeltaMovement(motion.x, 0.0, motion.z);
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("State", this.state.name());
        tag.putInt("StateTicks", this.stateTicks);
        tag.putDouble("CruiseSpeed", this.cruiseSpeed);
        tag.putDouble("CruiseAltitude", this.cruiseAltitude);
        tag.putDouble("ClimbRate", this.climbRate);
        tag.putDouble("ReleaseSpeed", this.releaseSpeed);
        return tag;
    }

    void load(CompoundTag tag) {
        DroneState loaded;
        try {
            loaded = DroneState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            loaded = DroneState.IDLE;
        }
        this.state = loaded;
        this.drone.getEntityData().set(DroneEntity.STATE, (byte) loaded.ordinal());
        this.stateTicks = tag.getInt("StateTicks");
        this.cruiseSpeed = tag.contains("CruiseSpeed") ? tag.getDouble("CruiseSpeed") : DEFAULT_CRUISE_SPEED;
        this.cruiseAltitude = tag.contains("CruiseAltitude") ? tag.getDouble("CruiseAltitude") : DEFAULT_CRUISE_ALTITUDE;
        this.climbRate = tag.contains("ClimbRate") ? tag.getDouble("ClimbRate") : DEFAULT_CLIMB_RATE;
        this.releaseSpeed = tag.contains("ReleaseSpeed") ? tag.getDouble("ReleaseSpeed") : DEFAULT_RELEASE_SPEED;
    }
}
