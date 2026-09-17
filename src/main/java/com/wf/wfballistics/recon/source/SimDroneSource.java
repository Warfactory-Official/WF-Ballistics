package com.wf.wfballistics.recon.source;

import com.wf.wfballistics.drone.sim.SimDrone;
import com.wf.wfballistics.drone.sim.SimDroneRegistry;
import com.wf.wfballistics.recon.ContactClass;
import com.wf.wfballistics.recon.EmconState;
import com.wf.wfballistics.recon.SourceIds;
import com.wf.wfballistics.recon.TargetSink;
import com.wf.wfballistics.recon.TargetSource;
import com.wf.wfballistics.recon.snapshot.TargetSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Drones that are off the world, flying as records rather than entities. */
public final class SimDroneSource implements TargetSource {

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        List<SimDrone> drones = SimDroneRegistry.get(level).view();
        for (int i = 0; i < drones.size(); i++) {
            SimDrone drone = drones.get(i);
            Vec3 pos = drone.pos;
            if (pos == null || !volume.contains(pos.x, drone.simY, pos.z)) {
                continue;
            }
            Vec3 velocity = drone.velocity == null ? Vec3.ZERO : drone.velocity;
            EmconState emcon = drone.program.isEmpty() ? EmconState.ACTIVE : EmconState.SILENT;
            sink.accept(new TargetSnapshot(SourceIds.of(drone.id), ContactClass.DRONE,
                    pos.x, drone.simY, pos.z,
                    velocity.x, velocity.y, velocity.z,
                    ReconSignatures.drone(), TargetSnapshot.NO_COUNTERMEASURES, emcon, 0L, false));
        }
    }
}
