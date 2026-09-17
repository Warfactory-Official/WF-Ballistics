package com.wf.wfballistics.orbital;

import com.wf.wfballistics.MissileEntity;
import com.wf.wfballistics.sim.SimMissile;
import com.wf.wfballistics.sim.SimMissileRegistry;
import com.wf.wfballistics.warhead.WarheadRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

public final class OrbitalDrop {

    static final ResourceLocation CARGO_LANDING =
            ResourceLocation.fromNamespaceAndPath("wfballistics", "cargo_landing");
    static final ResourceLocation CARGO_LANDING_SOFT =
            ResourceLocation.fromNamespaceAndPath("wfballistics", "cargo_landing_soft");
    static final ResourceLocation KINETIC_ROD =
            ResourceLocation.fromNamespaceAndPath("wfballistics", "kinetic_rod");

    static final double OVERSHOOT = 2000.0;
    static final double IMPACT_RANGE = 60.0;

    private OrbitalDrop() {
    }

    static UUID release(ServerLevel level, Satellite self, double targetX, double targetZ,
                        ResourceLocation warhead, double speed, float rcs) {
        long now = level.getGameTime();
        Vec3 above = self.position(now);
        double dx = above.x - targetX;
        double dz = above.z - targetZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) {
            double heading = self.elements().heading();
            dx = -Math.cos(heading);
            dz = -Math.sin(heading);
            len = 1.0;
        }
        double ux = dx / len;
        double uz = dz / len;

        SimMissile sm = new SimMissile();
        sm.id = UUID.randomUUID();
        sm.pos = new Vec3(targetX + ux * OrbitalConfig.RELEASE_DISTANCE,
                OrbitalConfig.RELEASE_ALTITUDE,
                targetZ + uz * OrbitalConfig.RELEASE_DISTANCE);
        sm.simY = OrbitalConfig.RELEASE_ALTITUDE;
        sm.target = new Vec3(targetX - ux * OVERSHOOT, OrbitalConfig.RELEASE_ALTITUDE, targetZ - uz * OVERSHOOT);
        sm.speed = speed;
        sm.cruiseMode = MissileEntity.CruiseMode.HIGH_ALTITUDE;
        sm.cruiseAltitude = OrbitalConfig.RELEASE_ALTITUDE;
        sm.maxTurnRate = 0.0;
        sm.detonationId = WarheadRegistry.exists(warhead) ? warhead : WarheadRegistry.parse("inert");
        sm.fuel = 20000;
        sm.fuelCapacity = 20000;
        sm.rcs = rcs;
        sm.teamId = self.owner();
        sm.lastGameTime = now;
        sm.role = SimMissile.Role.NORMAL;
        SimMissileRegistry.get(level).add(sm);
        OrbitalDescents.get(level).add(sm.id,
                new OrbitalDescents.Descent(targetX, targetZ, warhead, self.owner()));
        return sm.id;
    }

    public static double groundAt(ServerLevel level, double x, double z) {
        BlockPos at = BlockPos.containing(x, 0.0, z);
        if (!level.hasChunkAt(at)) {
            return 96.0;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
    }
}
