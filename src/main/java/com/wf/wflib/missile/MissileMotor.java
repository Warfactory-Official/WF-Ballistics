package com.wf.wflib.missile;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.MissileEntity.FuelType;
import com.wf.wflib.MissileEntity.Phase;
import com.wf.wflib.kinetic.KineticPreset;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Propellant, speed changes, evasion sprint. */
public final class MissileMotor {

    private static final double DIVE_ACCELERATION = 1.5;
    static final double BOOST_SPEED_MULT = 2.0;
    static final int BOOST_DURATION = 8;
    static final int BOOST_FUEL_COST = 150;
    private static final double JINK_DEFLECT = 0.6;
    /** Guided glide ends below this speed: ballistic fall. */
    static final double GLIDE_MIN_SPEED = 1.0;

    private final MissileEntity missile;

    FuelType fuelType = FuelType.SOLID;
    int fuelCapacity = MissileEntity.DEFAULT_FUEL_TICKS;
    int fuel = MissileEntity.DEFAULT_FUEL_TICKS;
    double acceleration = MissileEntity.DEFAULT_ACCELERATION;
    double deceleration = MissileEntity.DEFAULT_DECELERATION;
    /** Quadratic drag, per block: dv = -k v^2 per tick. 0 = kinematic (speed held by the stages). */
    double dragK;
    /** Burnt out: keep guiding on momentum until {@link #GLIDE_MIN_SPEED} (air-to-air coast). */
    boolean glides;
    private int boostTicks;
    @Nullable
    private Vec3 boostManeuver;

    public MissileMotor(MissileEntity missile) {
        this.missile = missile;
    }

    void burn() {
        this.fuel--;
    }

    void cut() {
        this.fuel = 0;
    }

    /** Spend {@link #BOOST_FUEL_COST} for a {@link #BOOST_DURATION}-tick sprint; null maneuver = straight. */
    void startBoost(@Nullable Vec3 maneuver) {
        this.fuel -= BOOST_FUEL_COST;
        this.boostTicks = BOOST_DURATION;
        this.boostManeuver = maneuver;
    }

    /** Sprint (and jink) while a boost runs. */
    Vec3 boost(ServerLevel level, Vec3 velocity) {
        if (this.boostTicks <= 0) {
            this.boostManeuver = null;
            return velocity;
        }
        this.boostTicks--;
        velocity = velocity.scale(BOOST_SPEED_MULT);
        if (this.boostManeuver != null) {
            double sp = velocity.length();
            if (sp > 1.0E-6) {
                Vec3 deflected = velocity.scale(1.0 / sp).add(this.boostManeuver.scale(JINK_DEFLECT));
                double dlen = deflected.length();
                if (dlen > 1.0E-6) {
                    velocity = deflected.scale(sp / dlen);
                }
            }
        }
        level.sendParticles(ParticleTypes.FLAME, this.missile.getX(), this.missile.getY(), this.missile.getZ(),
                6, 0.3, 0.3, 0.3, 0.02);
        return velocity;
    }

    /** Keep {@code desired}'s heading; ramp actual speed toward its speed at the accel/decel limit. */
    Vec3 applyThrust(Vec3 desired) {
        double targetSpeed = desired.length();
        Vec3 cur = this.missile.getDeltaMovement();
        double curSpeed = cur.length();
        double rate = curSpeed <= targetSpeed ? this.effectiveAcceleration() : this.deceleration;
        double diff = targetSpeed - curSpeed;
        double newSpeed = Math.abs(diff) <= rate ? targetSpeed : curSpeed + Math.copySign(rate, diff);
        Vec3 dir;
        if (targetSpeed > 1.0E-8) {
            dir = desired.scale(1.0 / targetSpeed);
        } else if (curSpeed > 1.0E-8) {
            dir = cur.scale(1.0 / curSpeed);
        } else {
            dir = new Vec3(0.0, 1.0, 0.0);
        }
        return dir.scale(newSpeed);
    }

    private double effectiveAcceleration() {
        boolean fast = this.missile.flight().getPhase() == Phase.ATTACK || this.boostTicks > 0;
        return fast ? Math.max(this.acceleration, DIVE_ACCELERATION) : this.acceleration;
    }

    /** Burnt out but still flying guided. */
    boolean gliding(double speed) {
        return this.fuel <= 0 && this.glides && speed > GLIDE_MIN_SPEED;
    }

    /** Next-tick speed of an unpowered airframe: quadratic drag, gravity along the path ({@code climb} = unit dir y). */
    double glideSpeed(double speed, double climb) {
        return Math.max(0.0, speed - this.dragK * speed * speed - KineticPreset.DEFAULT_GRAVITY * climb);
    }

    public FuelType getFuelType() {
        return this.fuelType;
    }

    public void setFuel(int fuel) {
        this.fuel = fuel;
    }

    public int getFuel() {
        return this.fuel;
    }

    public int getFuelCapacity() {
        return this.fuelCapacity;
    }

    public double getAcceleration() {
        return this.acceleration;
    }

    public double getDeceleration() {
        return this.deceleration;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("FuelType", this.fuelType.name());
        tag.putInt("Fuel", this.fuel);
        tag.putInt("FuelCapacity", this.fuelCapacity);
        tag.putDouble("Acceleration", this.acceleration);
        tag.putDouble("Deceleration", this.deceleration);
        tag.putDouble("DragK", this.dragK);
        tag.putBoolean("Glides", this.glides);
        return tag;
    }

    public void load(CompoundTag tag) {
        this.fuelType = FuelType.valueOf(tag.getString("FuelType"));
        this.fuel = tag.getInt("Fuel");
        this.fuelCapacity = tag.getInt("FuelCapacity");
        this.acceleration = tag.getDouble("Acceleration");
        this.deceleration = tag.getDouble("Deceleration");
        this.dragK = tag.getDouble("DragK");
        this.glides = tag.getBoolean("Glides");
    }
}
