package com.wf.wfballistics.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;

/**
 * Where the water is around a blast, so foam knows what surface it broke.
 */
public final class WaterSurface {

    /** How far a water column is followed up to its surface, and how far below a blast is looked for. */
    private static final int UP = 32;
    private static final int DOWN = 6;

    private WaterSurface() {
    }

    /**
     * @return the Y of the top of the water column through this point, or {@link Double#NaN} if there is
     *         no water at it or within a few blocks under it
     */
    public static double find(BlockGetter level, double x, double y, double z) {
        BlockPos.MutableBlockPos scan = BlockPos.containing(x, y, z).mutable();

        if (!isWater(level, scan)) {
            // A charge fusing just above the surface still churns it.
            for (int i = 0; i < DOWN && !isWater(level, scan); i++) {
                scan.move(Direction.DOWN);
            }
            if (!isWater(level, scan)) {
                return Double.NaN;
            }
        }

        for (int i = 0; i < UP; i++) {
            scan.move(Direction.UP);
            if (!isWater(level, scan)) {
                break;
            }
        }
        return scan.getY();
    }

    private static boolean isWater(BlockGetter level, BlockPos pos) {
        return level.getFluidState(pos).is(FluidTags.WATER);
    }
}
