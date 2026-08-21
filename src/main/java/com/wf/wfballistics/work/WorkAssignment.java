package com.wf.wfballistics.work;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * What one drone is currently doing for a {@link WorkJob}, as the AI is allowed to see it.
 *
 * <p>One record rather than five fields on {@code DroneSnapshot}, because that record is already long enough
 * that adding to it means editing seven copy constructors.
 *
 * <p>Everything here is <b>sampled on the world thread</b> and read off-thread, which is why the queue itself
 * never appears: a handler must not be able to claim, complete or even ask about an order. It flies to
 * {@link #order} and says it has arrived; the world thread does the rest. See {@code BuildPilot}.
 *
 * <p>Exactly one of {@link #order} and {@link #station} is normally set, and which one is the drone's whole
 * current intention: an order means "go and do this block", a station means "go and pick up materials" or
 * "go and drop off what you recovered". Both null means the drone is between assignments and should be
 * heading home.
 *
 * @param orderId  the claim's id within the job's queue. Meaningless outside it, and the reason completing is
 *                 a single integer rather than a position lookup
 * @param data     the order's payload: a palette index for a construction
 * @param siteOpen false when the job has been suspended out from under this drone, so it stops rather than
 *                 pressing on. Sampled because a worker cannot ask WarForge anything
 */
public record WorkAssignment(UUID jobId, ResourceLocation kind, int orderId, @Nullable BlockPos order,
                             int data, @Nullable Vec3 station, boolean siteOpen) {

    public boolean hasOrder() {
        return this.order != null;
    }

    public boolean hasStation() {
        return this.station != null;
    }

    /**
     * @return true if there is nothing to fly to. The drone should go home.
     */
    public boolean idle() {
        return this.target() == null;
    }

    /**
     * @return where this drone is heading, or null if nowhere.
     */
    @Nullable
    public Vec3 target() {
        if (this.order != null && this.siteOpen) {
            return Vec3.atCenterOf(this.order);
        }
        return this.station;
    }

    public WorkAssignment withOrder(@Nullable BlockPos order, int orderId, int data) {
        return new WorkAssignment(this.jobId, this.kind, orderId, order, data, this.station, this.siteOpen);
    }

    public WorkAssignment withStation(@Nullable Vec3 station) {
        return new WorkAssignment(this.jobId, this.kind, this.orderId, this.order, this.data, station,
                this.siteOpen);
    }

    public WorkAssignment withSiteOpen(boolean siteOpen) {
        return new WorkAssignment(this.jobId, this.kind, this.orderId, this.order, this.data, this.station,
                siteOpen);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Job", this.jobId);
        tag.putString("Kind", this.kind.toString());
        tag.putInt("OrderId", this.orderId);
        if (this.order != null) {
            tag.putLong("Order", this.order.asLong());
        }
        tag.putInt("Data", this.data);
        if (this.station != null) {
            tag.putDouble("StationX", this.station.x);
            tag.putDouble("StationY", this.station.y);
            tag.putDouble("StationZ", this.station.z);
        }
        return tag;
    }

    /**
     * @return the assignment in this tag, or null if there is not one.
     *
     * <p>{@code siteOpen} is deliberately not saved and comes back true: it is a sample of the world, and a
     * stale one is worse than none. It is re-derived on the next tick.
     */
    @Nullable
    public static WorkAssignment load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("Job")) {
            return null;
        }
        ResourceLocation kind = ResourceLocation.tryParse(tag.getString("Kind"));
        return new WorkAssignment(tag.getUUID("Job"),
                kind != null ? kind : ResourceLocation.withDefaultNamespace("unknown"),
                tag.getInt("OrderId"),
                tag.contains("Order") ? BlockPos.of(tag.getLong("Order")) : null,
                tag.getInt("Data"),
                tag.contains("StationX") ? new Vec3(tag.getDouble("StationX"), tag.getDouble("StationY"),
                        tag.getDouble("StationZ")) : null,
                true);
    }
}
