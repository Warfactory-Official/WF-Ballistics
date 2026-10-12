package com.wf.wflib.client.fx;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;

/** Water surface near a point, so foam knows what it broke through. */
public final class WaterSurface {

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
            // Charge fusing just above the surface still counts as having churned it.
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
