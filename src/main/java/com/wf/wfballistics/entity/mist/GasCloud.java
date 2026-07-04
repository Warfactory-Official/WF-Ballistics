package com.wf.wfballistics.entity.mist;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.entity.MistEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.List;

/**
 * Builds a wall-respecting gas cloud out of many static {@link MistEntity} box cells. The reachable air from
 * the detonation point is flood-filled and decomposed into a small set of maximal boxes by
 * {@link VolumetricFill}; one box-mode gas entity is spawned per box, so a big volume becomes a handful of
 * static cells that never move or path.
 */
public final class GasCloud {

    /**
     * Flood-fill reach (blocks) from the origin along each axis; the cloud is at most a (2r+1) cube.
     */
    public static final int DEFAULT_RADIUS = 6;
    /**
     * Safety cap only. The fill is normally bounded by the radius cube; this is kept well above
     * {@code (2*radius+1)^3} so an open-air burst fills its whole cube.
     */
    public static final int DEFAULT_MAX_CELLS = 8192;
    /**
     * Cloud lifetime in ticks (~15s).
     */
    public static final int DEFAULT_DURATION = 300;
    /**
     * Each spawned cell processes entities every N ticks.
     */
    public static final int DEFAULT_EFFECT_INTERVAL = 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    /**
     * Hard cap on spawned box entities; excess filled volume is left uncovered (and logged).
     */
    private static final int MAX_BOXES = 220;

    private GasCloud() {
    }

    /**
     * Convenience: fill and spawn with the default tuning.
     */
    public static int spawn(Level level, Fluid fluid, Vec3 center) {
        return spawn(level, fluid, center, DEFAULT_RADIUS, DEFAULT_MAX_CELLS, DEFAULT_DURATION);
    }

    /**
     * Flood-fills reachable air from {@code center}, reconstructs it as box-mode gas cells and spawns them.
     *
     * @return the number of gas cell entities spawned
     */
    public static int spawn(Level level, Fluid fluid, Vec3 center, int radius, int maxCells, int duration) {
        if (level.isClientSide) {
            return 0;
        }
        VolumetricFill.Fill fill = VolumetricFill.flood(level, BlockPos.containing(center), radius, maxCells);
        if (fill == null || fill.count() == 0) {
            return 0;
        }

        List<int[]> boxes = VolumetricFill.decompose(fill);
        if (boxes.size() > MAX_BOXES) {
            LOGGER.warn("Gas cloud at {} decomposed to {} boxes (> {} cap); spawning the largest {}.",
                    BlockPos.containing(center), boxes.size(), MAX_BOXES, MAX_BOXES);
            boxes.sort((p, q) -> Long.compare(VolumetricFill.volume(q), VolumetricFill.volume(p)));
            boxes = boxes.subList(0, MAX_BOXES);
        }

        for (int[] b : boxes) {
            double wx = fill.minX() + b[0];
            double wy = fill.minY() + b[1];
            double wz = fill.minZ() + b[2];
            float sx = b[3] - b[0] + 1;
            float sy = b[4] - b[1] + 1;
            float sz = b[5] - b[2] + 1;
            MistEntity.spawnBox(level, fluid, wx, wy, wz, sx, sy, sz, duration, DEFAULT_EFFECT_INTERVAL);
        }
        return boxes.size();
    }
}
