package com.wf.wflib.rail.build.ir;

import cam72cam.immersiverailroading.tile.TileRail;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.math.Vec3i;
import cam72cam.mod.world.World;
import com.wf.wflib.rail.align.AlignElement;
import com.wf.wflib.rail.align.Centreline;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Takes a route's own Immersive Railroading track back out of the ground.
 *
 * <p>Needed because laying track again does not replace it. IR anchors each piece on one block and
 * refuses to put a second anchor there, so a line whose joins are in the wrong place stays that way
 * however many times it is re-laid, and the only advice a refusal can give - lay that route again with
 * its joins at the junction - is advice nobody can follow. This is what makes it followable.</p>
 *
 * <p>It removes <b>anchors</b> and nothing else. An anchor carries the curve that every sleeper around
 * it points at, so taking one out takes its whole piece with it, and the sleepers go when IR notices
 * their anchor has gone. Deleting sleepers directly would leave the anchor pointing at a line with
 * holes in it.</p>
 *
 * <p>Two tests decide whether a piece is this route's, and both are needed. <b>Position</b> alone is not
 * enough: where two lines cross, the other one's anchors are within a few blocks of this centreline for
 * as long as the crossing lasts, and clearing this route would quietly take a piece out of theirs.
 * <b>Direction</b> settles it, because two lines that cross are by definition not going the same way.</p>
 */
public final class IrTrackClear {

    /** How far a piece may point off this route's heading and still be this route's, in degrees. */
    private static final double HEADING_TOLERANCE = 25.0;

    /** How finely the centreline is walked to find the nearest point to an anchor. */
    private static final double STEP = 2.0;

    private IrTrackClear() {
    }

    /**
     * Remove every piece of this line anchored inside these cells.
     *
     * @param reach how far off the centreline an anchor may sit and still be this line's, in blocks
     * @return how many pieces were taken out
     */
    public static int remove(ServerLevel level, World world, Iterable<BlockPos> cells, Centreline line,
                             double reach) {
        int removed = 0;
        for (BlockPos pos : cells) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            Vec3i at = new Vec3i(pos.getX(), pos.getY(), pos.getZ());
            TileRail rail = world.getBlockEntity(at, TileRail.class);
            if (rail == null || !belongsTo(rail, at, line, reach)) {
                continue;
            }
            level.destroyBlock(pos, false);
            removed++;
        }
        return removed;
    }

    /** Whether the piece anchored here was built for this line, by where it is and which way it runs. */
    private static boolean belongsTo(TileRail rail, Vec3i at, Centreline line, double reach) {
        if (rail.info == null || line.isEmpty()) {
            return false;
        }
        Vec3d here = new Vec3d(at);
        Vec3d from = rail.info.placementInfo.placementPosition.add(here);
        Vec3d to = rail.info.customInfo.placementPosition.add(here);
        double bestDistance = Double.MAX_VALUE;
        double heading = 0.0;
        for (double s = 0.0; s <= line.length(); s += STEP) {
            AlignElement.Sample sample = line.at(s);
            double distance = Math.hypot(sample.x() - from.x, sample.z() - from.z);
            if (distance < bestDistance) {
                bestDistance = distance;
                heading = sample.heading();
            }
        }
        if (bestDistance > reach) {
            return false;
        }
        double along = Math.atan2(to.z - from.z, to.x - from.x);
        double off = Math.abs(Math.toDegrees(along - heading)) % 180.0;
        return Math.min(off, 180.0 - off) <= HEADING_TOLERANCE;
    }
}
