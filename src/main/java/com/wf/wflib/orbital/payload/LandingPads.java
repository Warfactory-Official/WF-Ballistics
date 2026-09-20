package com.wf.wflib.orbital.payload;

import com.wf.wflib.recon.ReconNet;
import com.wf.wflib.recon.grid.GridNode;
import com.wf.wflib.recon.grid.ReconGrid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

public final class LandingPads {

    public static final String LABEL = "pad";

    private LandingPads() {
    }

    @Nullable
    public static Vec3 nearest(ServerLevel level, long netId, double x, double z, double range) {
        ReconGrid grid = ReconNet.grid(level, netId);
        if (grid == null) {
            return null;
        }
        Vec3 best = null;
        double bestSq = range * range;
        for (GridNode node : grid.nodes()) {
            if (!LABEL.equals(node.label()) || !node.online()) {
                continue;
            }
            double dx = node.pos().getX() + 0.5 - x;
            double dz = node.pos().getZ() + 0.5 - z;
            double distSq = dx * dx + dz * dz;
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = new Vec3(node.pos().getX() + 0.5, node.pos().getY() + 1.0, node.pos().getZ() + 0.5);
            }
        }
        return best;
    }
}
