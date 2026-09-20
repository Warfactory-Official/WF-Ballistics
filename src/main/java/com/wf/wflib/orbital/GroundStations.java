package com.wf.wflib.orbital;

import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.ReconNetwork;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.ReconGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

final class GroundStations {

    static final String JAMMER_LABEL = "jammer";

    private GroundStations() {
    }

    static List<BlockPos> of(ServerLevel level, long netId) {
        ReconGrid grid = ReconNet.grid(level, netId);
        if (grid == null) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        for (GridNode node : grid.nodes()) {
            if (node.root() && node.online()) {
                out.add(node.pos());
            }
        }
        return out;
    }

    static boolean inView(ServerLevel level, long netId, double x, double z, double range) {
        ReconGrid grid = ReconNet.grid(level, netId);
        if (grid == null) {
            return false;
        }
        double rangeSq = range * range;
        for (GridNode node : grid.nodes()) {
            if (!node.root() || !node.online()) {
                continue;
            }
            double dx = node.pos().getX() + 0.5 - x;
            double dz = node.pos().getZ() + 0.5 - z;
            if (dx * dx + dz * dz <= rangeSq) {
                return true;
            }
        }
        return false;
    }

    static boolean jammedAt(ServerLevel level, long netId, double x, double z) {
        for (ReconNetwork net : ReconNet.networks(level)) {
            if (net.netId() == netId) {
                continue;
            }
            for (GridNode node : net.grid().nodes()) {
                if (!JAMMER_LABEL.equals(node.label()) || !node.online()) {
                    continue;
                }
                double dx = node.pos().getX() + 0.5 - x;
                double dz = node.pos().getZ() + 0.5 - z;
                if (dx * dx + dz * dz <= OrbitalConfig.JAMMER_RANGE * OrbitalConfig.JAMMER_RANGE) {
                    return true;
                }
            }
        }
        return false;
    }

    static double nearest(ServerLevel level, long netId, double x, double z) {
        ReconGrid grid = ReconNet.grid(level, netId);
        if (grid == null) {
            return Double.MAX_VALUE;
        }
        double best = Double.MAX_VALUE;
        for (GridNode node : grid.nodes()) {
            if (!node.root() || !node.online()) {
                continue;
            }
            double dx = node.pos().getX() + 0.5 - x;
            double dz = node.pos().getZ() + 0.5 - z;
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        return best;
    }
}
