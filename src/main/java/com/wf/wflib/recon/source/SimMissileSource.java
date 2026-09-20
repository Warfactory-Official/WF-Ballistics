package com.wf.wflib.recon.source;

import com.wf.wflib.recon.ContactClass;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.TargetSink;
import com.wf.wflib.recon.TargetSource;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import com.wf.wflib.sim.SimMissile;
import com.wf.wflib.sim.SimMissileRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Missiles that are off the world, flying as records rather than entities. */
public final class SimMissileSource implements TargetSource {

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        List<SimMissile> missiles = SimMissileRegistry.get(level).view();
        for (int i = 0; i < missiles.size(); i++) {
            SimMissile missile = missiles.get(i);
            Vec3 pos = missile.pos;
            if (pos == null) {
                continue;
            }
            // simY is the altitude the record is actually flying at; pos.y is the ground track it follows.
            double y = missile.simY;
            if (!volume.contains(pos.x, y, pos.z)) {
                continue;
            }
            Vec3 heading = heading(missile);
            sink.accept(TargetSnapshot.of(SourceIds.of(missile.id), ContactClass.MISSILE,
                    pos.x, y, pos.z,
                    heading.x * missile.speed, heading.y * missile.speed, heading.z * missile.speed,
                    ReconSignatures.missile(missile.rcs),
                    missile.teamId == null ? 0L : SourceIds.of(missile.teamId),
                    false));
        }
    }

    /**
     * A sim missile stores where it is going rather than which way it is pointing, so heading is the unit vector to
     * its target.
     */
    private static Vec3 heading(SimMissile missile) {
        if (missile.target == null) {
            return Vec3.ZERO;
        }
        Vec3 delta = missile.target.subtract(missile.pos);
        double length = delta.length();
        return length < 1.0E-6 ? Vec3.ZERO : delta.scale(1.0 / length);
    }
}
