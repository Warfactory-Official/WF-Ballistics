package com.wf.wflib.recon.fc;

import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.recon.track.Track;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/** Turning a contact back into something you can shoot. */
public final class FireControl {

    /** Blocks of search box even for a perfect track. */
    private static final double MIN_BOX = 2.0;

    private FireControl() {
    }

    /**
     * @param maxError the widest search this sensor will accept. Past it the track is not a firing solution,
     *      and returning null is the correct answer rather than a failure.
     * @return the best candidate at the track's predicted position, or null if the track cannot be resolved.
     */
    @Nullable
    public static <T extends Entity> T resolve(ServerLevel level, Class<T> type, Track track,
                                               double maxError, Predicate<T> filter) {
        WorldThread.assertOn("fire control resolution");
        long now = level.getGameTime();
        double error = track.predictedError(now);
        if (error > maxError) {
            return null;
        }
        double x = track.predictedX(now);
        double y = track.predictedY(now);
        double z = track.predictedZ(now);
        double box = Math.max(MIN_BOX, error);

        List<T> candidates = level.getEntitiesOfClass(type,
                new AABB(x - box, y - box, z - box, x + box, y + box, z + box), filter);
        T best = null;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            T candidate = candidates.get(i);
            double dx = candidate.getX() - x;
            double dy = candidate.getY() - y;
            double dz = candidate.getZ() - z;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq < bestSq) {
                bestSq = distSq;
                best = candidate;
            }
        }
        return best;
    }
}
