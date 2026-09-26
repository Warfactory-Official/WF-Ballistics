package com.wf.wflib.rail.excavate;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where a tunnel's lights go.
 *
 * <p>Worked out along the route rather than per cell, because "one torch every eight blocks" is a
 * statement about distance along the line and a block does not know how far along it is. Each position
 * keeps the chainage it was struck at, so a machine lighting as it advances can place them in the order
 * it reaches them instead of searching a list.</p>
 *
 * <p>Positions that do not land inside the bore are dropped rather than placed. A section whose torch
 * cell falls in the wall on a tight curve leaves that stretch dark, which is a great deal better than a
 * hole in the lining.</p>
 */
public final class TorchPlan {

    private TorchPlan() {
    }

    /** One light, and how far along the route it is. */
    public record Torch(BlockPos at, double chainage) {
    }

    public static List<Torch> along(CarveVolume.Corridor path, TunnelProfile profile, int floorY,
                                    CarveVolume bore) {
        List<Torch> out = new ArrayList<>();
        int spacing = profile.torchSpacing();
        List<int[]> cells = profile.torchCells();
        if (spacing <= 0 || cells.isEmpty()) {
            return out;
        }
        Set<BlockPos> seen = new HashSet<>();
        double halfWidth = profile.width() / 2.0;
        // Half a spacing in, so the first torch is inside the tunnel rather than in the portal.
        for (double s = spacing / 2.0; s < path.length(); s += spacing) {
            double[] at = path.pointAt(s);
            // The left-hand normal of the heading, which is the direction a positive offset means.
            double nx = -at[3];
            double nz = at[2];
            for (int[] cell : cells) {
                double offset = cell[0] + 0.5 - halfWidth;
                BlockPos pos = BlockPos.containing(at[0] + nx * offset, floorY + cell[1],
                        at[1] + nz * offset);
                if (bore.contains(pos.getX(), pos.getY(), pos.getZ()) && seen.add(pos)) {
                    out.add(new Torch(pos, s));
                }
            }
        }
        return out;
    }

    /** Just the positions, for a carve that only needs to know which cells to fill. */
    public static List<BlockPos> positions(List<Torch> torches) {
        List<BlockPos> out = new ArrayList<>(torches.size());
        for (Torch torch : torches) {
            out.add(torch.at());
        }
        return out;
    }
}
