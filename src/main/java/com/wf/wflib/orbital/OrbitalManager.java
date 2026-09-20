package com.wf.wflib.orbital;

import com.wf.wflib.compat.WarforgeCompat;
import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.recon.ReconBound;
import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconNetwork;
import com.wf.wflib.recon.ReconOwners;
import com.wf.wflib.sim.SimMissile;
import com.wf.wflib.sim.SimMissileRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class OrbitalManager {

    private OrbitalManager() {
    }

    static void tick(ServerLevel level) {
        WorldThread.assertOn("orbital tick");
        long now = level.getGameTime();
        OrbitalRegistry registry = OrbitalRegistry.get(level);
        if (registry.isEmpty() && OrbitalCommandBus.pendingCount(level) == 0) {
            return;
        }
        if (now % OrbitalConfig.SLOW_TICK == 0) {
            housekeeping(level, registry, now);
        }
        OrbitalCommandBus.deliver(level, registry, now);
        descend(level);
    }

    private static void descend(ServerLevel level) {
        OrbitalDescents descents = OrbitalDescents.get(level);
        if (descents.isEmpty()) {
            return;
        }
        SimMissileRegistry missiles = SimMissileRegistry.get(level);
        for (java.util.Map.Entry<java.util.UUID, OrbitalDescents.Descent> entry : descents.entries()) {
            SimMissile flying = missiles.getById(entry.getKey());
            if (flying == null) {
                descents.remove(entry.getKey());
                continue;
            }
            OrbitalDescents.Descent descent = entry.getValue();
            double dx = flying.pos.x - descent.aimX();
            double dz = flying.pos.z - descent.aimZ();
            if (dx * dx + dz * dz > OrbitalDrop.IMPACT_RANGE * OrbitalDrop.IMPACT_RANGE) {
                continue;
            }
            missiles.remove(flying);
            descents.remove(entry.getKey());
            OrbitalImpact.apply(level, descent.aimX(), descent.aimZ(), descent.effect(),
                    descent.faction(), entry.getKey());
        }
    }

    private static void housekeeping(ServerLevel level, OrbitalRegistry registry, long now) {
        List<Satellite> birds = new ArrayList<>(registry.all());
        if (birds.isEmpty()) {
            return;
        }
        for (Satellite sat : birds) {
            capture(level, sat);
            Vec3 at = sat.position(now);
            boolean jammed = GroundStations.jammedAt(level, sat.netId(), at.x, at.z);
            sat.setJammed(jammed);
            sat.setContact(!jammed && GroundStations.inView(level, sat.netId(), at.x, at.z,
                    OrbitalConfig.contactRangeFor(sat.elements().altitude())), 0);
        }
        relayContact(birds, now);

        boolean day = level.isDay();
        double slow = OrbitalConfig.SLOW_TICK;
        for (Satellite sat : birds) {
            if (day) {
                sat.charge(sat.solarRate() * slow);
            }
            sat.drain(OrbitalConfig.IDLE_POWER_PER_TICK * slow);
            if (sat.parked() && !sat.spendFuel(OrbitalConfig.PARK_FUEL_PER_TICK * slow)) {
                sat.unpark(now);
                sat.setReply("station lost: dry tank");
            }
            sat.payload().tick(level, sat);
        }
        survey(level, registry, birds, now);
        registry.setDirty();
    }

    private static void relayContact(List<Satellite> birds, long now) {
        for (Satellite sat : birds) {
            if (sat.inContact()) {
                continue;
            }
            Vec3 at = sat.position(now);
            for (Satellite other : birds) {
                if (other == sat || other.netId() != sat.netId() || sat.jammed()
                        || !other.inContact() || other.contactHops() != 0
                        || !SatPayloads.RELAY.equals(other.payloadId())) {
                    continue;
                }
                Vec3 relay = other.position(now);
                double reach = OrbitalConfig.relayReachFor(other.elements().altitude());
                double dx = relay.x - at.x;
                double dz = relay.z - at.z;
                if (dx * dx + dz * dz <= reach * reach) {
                    sat.setContact(true, 1);
                    break;
                }
            }
        }
    }

    private static void capture(ServerLevel level, Satellite sat) {
        BlockPos station = sat.station();
        if (station == null) {
            return;
        }
        UUID claim = ReconOwners.owningAt(level, station);
        if (claim != null) {
            if (!claim.equals(sat.owner())) {
                sat.setOwner(claim);
                sat.setNetId(ReconNet.netId(claim));
                sat.setReply("station taken: now flying for " + claim);
            }
            return;
        }
        if (level.hasChunkAt(station)
                && level.getBlockEntity(station) instanceof ReconBound bound
                && bound.netId() != sat.netId()) {
            sat.setNetId(bound.netId());
        }
    }

    private static void survey(ServerLevel level, OrbitalRegistry registry, List<Satellite> birds, long now) {
        List<ReconNetwork> nets = ReconNet.networks(level);
        if (nets.isEmpty()) {
            return;
        }
        for (ReconNetwork net : nets) {
            if (net.grid().rootCount() == 0) {
                continue;
            }
            for (Satellite sat : birds) {
                if (sat.netId() == net.netId()) {
                    continue;
                }
                Vec3 at = sat.position(now);
                if (GroundStations.inView(level, net.netId(), at.x, at.z, OrbitalConfig.SURVEILLANCE_RANGE)) {
                    registry.observe(net.netId(), sat, now);
                }
            }
        }
    }
}
