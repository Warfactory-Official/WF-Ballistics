package com.wf.wfballistics.colony;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/**
 * An attack in transit: one record standing in for however many glyphids are marching.
 *
 * <p>This is the tier that makes the whole thing scale. Five hundred glyphids crossing two thousand blocks
 * is <em>this object</em>, moving in a straight line, not five hundred entities and not five hundred sim
 * records. It costs one position update per tick regardless of {@link #count}.
 *
 * <p>It becomes real only where somebody can see it. Until then there is no terrain to walk around, because
 * there is no terrain loaded and nobody to notice that it went through a hill — the same trade
 * {@code SimDrone} makes by holding its altitude and flying straight.
 */
public final class Warband {

    public final UUID id;
    public final UUID origin;
    public double x;
    public double z;
    public final int targetX;
    public final int targetZ;
    public int count;
    public final int tier;
    /**
     * Ticks since dispatch, so a warband that can never reach its target can be retired.
     */
    public int age;
    /**
     * Set once the target is reached. An arrived warband stops travelling but does not disband: the base it
     * came for is simply offline, so it waits there for somebody to show up and see it.
     */
    public boolean arrived;

    public Warband(UUID id, UUID origin, double x, double z, int targetX, int targetZ, int count, int tier) {
        this.id = id;
        this.origin = origin;
        this.x = x;
        this.z = z;
        this.targetX = targetX;
        this.targetZ = targetZ;
        this.count = count;
        this.tier = tier;
    }

    public double distanceToTarget() {
        double dx = targetX - x;
        double dz = targetZ - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Advance toward the target. Straight-line: see the class note.
     *
     * @return true once the target is reached
     */
    public boolean advance(double speed) {
        age++;
        if (arrived) {
            return true;
        }
        double dx = targetX - x;
        double dz = targetZ - z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= speed) {
            x = targetX;
            z = targetZ;
            arrived = true;
            return true;
        }
        x += dx / distance * speed;
        z += dz / distance * speed;
        return false;
    }

    public ChunkPos chunk() {
        return new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("origin", origin);
        tag.putDouble("x", x);
        tag.putDouble("z", z);
        tag.putInt("tx", targetX);
        tag.putInt("tz", targetZ);
        tag.putInt("count", count);
        tag.putInt("tier", tier);
        tag.putInt("age", age);
        tag.putBoolean("arrived", arrived);
        return tag;
    }

    public static Warband load(CompoundTag tag) {
        Warband warband = new Warband(tag.getUUID("id"), tag.getUUID("origin"),
                tag.getDouble("x"), tag.getDouble("z"),
                tag.getInt("tx"), tag.getInt("tz"),
                tag.getInt("count"), tag.getInt("tier"));
        warband.age = tag.getInt("age");
        warband.arrived = tag.getBoolean("arrived");
        return warband;
    }

    @Override
    public String toString() {
        return String.format("warband of %d (T%d) at (%d, %d), %d blocks from (%d, %d)%s",
                count, tier, (int) x, (int) z, (int) distanceToTarget(), targetX, targetZ,
                arrived ? " [waiting]" : "");
    }
}
