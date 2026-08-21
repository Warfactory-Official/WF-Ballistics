package com.wf.wfballistics.drone;

import net.minecraft.nbt.CompoundTag;

/**
 * A drone's battery: the drone equivalent of {@code MissileEntity}'s fuel tank, held by both the live entity
 * and its off-world {@code SimDrone} snapshot so charge keeps draining across the offload round-trip.
 */
public final class DroneBattery {

    public static final double DEFAULT_CAPACITY = 4000.0;

    private double charge;
    private double capacity;

    public DroneBattery(double capacity) {
        this(capacity, capacity);
    }

    public DroneBattery(double charge, double capacity) {
        this.capacity = Math.max(1.0, capacity);
        this.charge = Math.clamp(charge, 0.0, this.capacity);
    }

    public static DroneBattery load(CompoundTag tag) {
        return new DroneBattery(tag.getDouble("Charge"), tag.getDouble("Capacity"));
    }

    public void save(CompoundTag tag) {
        tag.putDouble("Charge", charge);
        tag.putDouble("Capacity", capacity);
    }

    public double charge() {
        return charge;
    }

    public double capacity() {
        return capacity;
    }

    public boolean isEmpty() {
        return charge <= 0.0;
    }

    public float percent() {
        return (float) (100.0 * charge / capacity);
    }

    /**
     * Burn charge for one tick (or one simulated step). Never goes negative.
     *
     * @return true if this call emptied the battery
     */
    public boolean drain(double amount) {
        if (charge <= 0.0) {
            return false;
        }
        charge = Math.max(0.0, charge - Math.max(0.0, amount));
        return charge <= 0.0;
    }

    public void recharge(double amount) {
        charge = Math.min(capacity, charge + Math.max(0.0, amount));
    }

    /**
     * Set the remaining charge outright. Used when restoring a drone from an off-world record: recharging
     * would top up a battery that starts full rather than restoring what it had left.
     */
    public void setCharge(double value) {
        charge = Math.clamp(value, 0.0, capacity);
    }

    public void setCapacity(double value) {
        this.capacity = Math.max(1.0, value);
        this.charge = Math.min(this.charge, this.capacity);
    }
}
