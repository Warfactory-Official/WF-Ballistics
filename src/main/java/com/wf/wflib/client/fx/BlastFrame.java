package com.wf.wflib.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Blast vent frame: local +y -> open side (26 rays, free fraction of {@link #STEPS} samples each; embedded centre
 * still reads). Flat ground => +y exactly (symmetric rays); open air or weak bias => +y.
 */
public final class BlastFrame {
    private static final int STEPS = 8;
    private static final double STEP = 0.5;
    /** |sum| on flat ground ~6.1; below this (lone block, open air) => +y. */
    private static final float MIN_BIAS = 1.5f;
    private static final Vector3f[] RAYS = new Vector3f[26];

    static {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        RAYS[n++] = new Vector3f(dx, dy, dz).normalize();
                    }
                }
            }
        }
    }

    private BlastFrame() {
    }

    public static Quaternionf of(Level level, double x, double y, double z) {
        Vector3f open = new Vector3f();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Vector3f ray : RAYS) {
            int free = 0;
            for (int k = 1; k <= STEPS; k++) {
                double d = k * STEP;
                pos.set(Mth.floor(x + ray.x * d), Mth.floor(y + ray.y * d), Mth.floor(z + ray.z * d));
                if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                    free++;
                }
            }
            open.fma((float) free / STEPS, ray);
        }
        float bias = open.length();
        if (bias < MIN_BIAS) {
            return new Quaternionf();
        }
        open.div(bias);
        return new Quaternionf().rotationTo(0, 1, 0, open.x, open.y, open.z);
    }
}
