package com.wf.wfballistics.drone;

import com.wf.wfballistics.item.MinePresetRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A rack of mines slung under a drone: which mine, how many are left, how many it started with, and how far apart
 * to put them down.
 */
public record MineLoad(ResourceLocation preset, int remaining, int capacity, double spacing) {

    /** Most mines one drone will carry. */
    public static final int MAX_MINES = 24;
    /** Mines per drone when nobody said. */
    public static final int DEFAULT_MINES = 8;
    /** Blocks between mines along the lane when nobody said. */
    public static final double DEFAULT_SPACING = 6.0;
    public static final double MIN_SPACING = 2.0;
    public static final double MAX_SPACING = 32.0;

    public MineLoad {
        capacity = Mth.clamp(capacity, 1, MAX_MINES);
        remaining = Mth.clamp(remaining, 0, capacity);
        spacing = Mth.clamp(spacing, MIN_SPACING, MAX_SPACING);
    }

    public static MineLoad of(ResourceLocation preset, int count, double spacing) {
        int wanted = Mth.clamp(count, 1, MAX_MINES);
        return new MineLoad(preset, wanted, wanted, spacing);
    }

    public static MineLoad of(ResourceLocation preset, int count) {
        return of(preset, count, DEFAULT_SPACING);
    }

    public boolean empty() {
        return this.remaining <= 0;
    }

    /** @return how many of the rack have already gone down. */
    public int laid() {
        return this.capacity - this.remaining;
    }

    /** @return the rack with one fewer mine in it. */
    public MineLoad afterLaying() {
        return new MineLoad(this.preset, this.remaining - 1, this.capacity, this.spacing);
    }

    /** @return end-to-end length of the strip this rack lays, blocks. */
    public double laneLength() {
        return (this.capacity - 1) * this.spacing;
    }

    /**
     * @return where mine {@code index} should land, as a signed distance along the run-in heading from the
     *      ordered lay point. Negative is short of it.
     */
    public double alongTrack(int index) {
        return (index - (this.capacity - 1) * 0.5) * this.spacing;
    }

    /**
     * @return how many of the rack should be on the ground by the time the drone's release solution has
     *      reached {@code alongTrack} blocks past the lay point, never more than the rack holds.
     */
    public int dueBy(double alongTrack) {
        if (this.capacity <= 0 || this.spacing <= 0.0) {
            return 0;
        }
        int due = (int) Math.floor((alongTrack - alongTrack(0)) / this.spacing) + 1;
        return Mth.clamp(due, 0, this.capacity);
    }

    /** @return a short readout for telemetry and the drone list: {@code 6/8 scatter_ap}. */
    public String label() {
        return this.remaining + "/" + this.capacity + " " + this.preset.getPath();
    }

    /** @return the far ends of the lane through {@code layPoint} on heading {@code runIn}, for display. */
    public Vec3 laneEnd(Vec3 layPoint, Vec3 runIn) {
        return layPoint.add(runIn.scale(alongTrack(this.capacity - 1)));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeResourceLocation(this.preset);
        buf.writeVarInt(this.remaining);
        buf.writeVarInt(this.capacity);
        buf.writeDouble(this.spacing);
    }

    public static MineLoad read(FriendlyByteBuf buf) {
        return new MineLoad(buf.readResourceLocation(), buf.readVarInt(), buf.readVarInt(), buf.readDouble());
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Preset", this.preset.toString());
        tag.putInt("Remaining", this.remaining);
        tag.putInt("Capacity", this.capacity);
        tag.putDouble("Spacing", this.spacing);
        return tag;
    }

    @Nullable
    public static MineLoad load(CompoundTag tag) {
        if (!tag.contains("Preset")) {
            return null;
        }
        return new MineLoad(MinePresetRegistry.parse(tag.getString("Preset")),
                tag.getInt("Remaining"), tag.getInt("Capacity"), tag.getDouble("Spacing"));
    }
}
