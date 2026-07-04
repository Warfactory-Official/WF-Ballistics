package com.wf.wfballistics.fire;

import com.wf.wfballistics.entity.FireLingeringEntity;
import com.wf.wfballistics.entity.mist.VolumetricFill;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class FireUtil {

    public static final int DEFAULT_RADIUS = 6;
    public static final float DEFAULT_HEIGHT = 2.0F;
    public static final int DEFAULT_LINGER = 200;

    private static final int MAX_CELLS = 8192;
    private static final int MAX_BOXES = 220;

    private FireUtil() {
    }

    public static int spawn(Level level, Vec3 center) {
        return spawn(level, center, DEFAULT_RADIUS, DEFAULT_HEIGHT, DEFAULT_LINGER, FireLingeringEntity.TYPE_DIESEL);
    }

    //TODO:Enumify fire
    public static int spawn(Level level, Vec3 center, int radius, float fireHeight, int tickLinger, int type) {
        if (level.isClientSide) {
            return 0;
        }
        VolumetricFill.Fill fill = VolumetricFill.flood(level, BlockPos.containing(center), radius, MAX_CELLS, false);
        if (fill == null || fill.count() == 0) {
            return 0;
        }
        VolumetricFill.Fill floor = fill.floorView();
        if (floor.count() == 0) {
            return 0;
        }
        List<int[]> boxes = VolumetricFill.decompose(floor);
        if (boxes.size() > MAX_BOXES) {
            boxes.sort((p, q) -> Long.compare(VolumetricFill.volume(q), VolumetricFill.volume(p)));
            boxes = boxes.subList(0, MAX_BOXES);
        }
        for (int[] b : boxes) {
            double wx = fill.minX() + b[0];
            double wy = fill.minY() + b[1];
            double wz = fill.minZ() + b[2];
            float sx = b[3] - b[0] + 1;
            float sz = b[5] - b[2] + 1;
            FireLingeringEntity.spawnBox(level, wx, wy, wz, sx, fireHeight, sz, tickLinger, type);
        }
        return boxes.size();
    }
}
