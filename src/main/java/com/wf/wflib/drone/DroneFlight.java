package com.wf.wflib.drone;

import com.wf.wflib.drone.flight.FlightAttitude;
import net.minecraft.nbt.CompoundTag;

/** Flight state machine, attitude, cruise envelope. */
public final class DroneFlight {

    public static final double DEFAULT_CRUISE_SPEED = 0.75;
    public static final double DEFAULT_CRUISE_ALTITUDE = 40.0;
    public static final double DEFAULT_CLIMB_RATE = 0.35;
    /**
     * Speed an attack run is flown at. Faster than cruise: the payload's throw comes from the airframe.
     */
    public static final double DEFAULT_RELEASE_SPEED = 1.2;

    private final ControllableDrone drone;
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

    public DroneFlight(ControllableDrone drone) {
        this.drone = drone;
    }

    public void tick() {
        this.stateTicks++;
    }

    public int stateTicks() {
        return this.stateTicks;
    }

    public void noteGround(double groundY) {
        this.lastGroundY = groundY;
    }

    public double lastGroundY() {
        return this.lastGroundY;
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
        this.drone.onStateChanged(state);
    }

    /** Client: the synced byte is the only writer of the state there. */
    public void syncState(byte ordinal) {
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
        this.drone.onAttitude(attitude);
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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("State", this.state.name());
        tag.putInt("StateTicks", this.stateTicks);
        tag.putDouble("CruiseSpeed", this.cruiseSpeed);
        tag.putDouble("CruiseAltitude", this.cruiseAltitude);
        tag.putDouble("ClimbRate", this.climbRate);
        tag.putDouble("ReleaseSpeed", this.releaseSpeed);
        return tag;
    }

    public void load(CompoundTag tag) {
        DroneState loaded;
        try {
            loaded = DroneState.valueOf(tag.getString("State"));
        } catch (IllegalArgumentException ignored) {
            loaded = DroneState.IDLE;
        }
        this.state = loaded;
        this.stateTicks = tag.getInt("StateTicks");
        this.cruiseSpeed = tag.contains("CruiseSpeed") ? tag.getDouble("CruiseSpeed") : DEFAULT_CRUISE_SPEED;
        this.cruiseAltitude = tag.contains("CruiseAltitude") ? tag.getDouble("CruiseAltitude") : DEFAULT_CRUISE_ALTITUDE;
        this.climbRate = tag.contains("ClimbRate") ? tag.getDouble("ClimbRate") : DEFAULT_CLIMB_RATE;
        this.releaseSpeed = tag.contains("ReleaseSpeed") ? tag.getDouble("ReleaseSpeed") : DEFAULT_RELEASE_SPEED;
    }
}
