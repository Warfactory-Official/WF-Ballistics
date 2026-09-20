package com.wf.wflib.colony;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;

import java.util.UUID;

/** An attack in transit: one record standing in for however many glyphids are marching. */
public final class Warband {

    public final UUID id;
    public final UUID origin;
    public double x;
    public double z;
    public final int targetX;
    public final int targetZ;
    public int count;
    public final int tier;
    /** Whether this warband is on the wing: the tier's straight-line assumption made honest. */
    public boolean flying;
    /** Ticks since dispatch, so a warband that can never reach its target can be retired. */
    public int age;
    /** Set once the target is reached. An arrived warband waits there rather than disbanding. */
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
     * Advance toward the target.
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
        tag.putBoolean("flying", flying);
        return tag;
    }

    public static Warband load(CompoundTag tag) {
        Warband warband = new Warband(tag.getUUID("id"), tag.getUUID("origin"),
                tag.getDouble("x"), tag.getDouble("z"),
                tag.getInt("tx"), tag.getInt("tz"),
                tag.getInt("count"), tag.getInt("tier"));
        warband.age = tag.getInt("age");
        warband.arrived = tag.getBoolean("arrived");
        warband.flying = tag.getBoolean("flying");
        return warband;
    }

    @Override
    public String toString() {
        return String.format("%s of %d (T%d) at (%d, %d), %d blocks from (%d, %d)%s",
                flying ? "flight" : "warband",
                count, tier, (int) x, (int) z, (int) distanceToTarget(), targetX, targetZ,
                arrived ? " [waiting]" : "");
    }
}
