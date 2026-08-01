package com.wf.wfballistics.api;

import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public record MissileData(UUID id, boolean simulated, String model, String phase, Vec3 pos, Vec3 target,
                          double speed, int fuel, int fuelCapacity) {

    public double horizontalDistance() {
        double dx = target.x - pos.x;
        double dz = target.z - pos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public double poweredRange() {
        return fuel * speed;
    }

    public boolean canReach() {
        return poweredRange() >= horizontalDistance();
    }

    public int etaTicks() {
        if (speed <= 1.0E-4) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.round(horizontalDistance() / speed);
    }

    public int fuelPercent() {
        return fuelCapacity > 0 ? Math.round(100.0f * fuel / fuelCapacity) : 0;
    }
}
