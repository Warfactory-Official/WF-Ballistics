package com.wf.wflib.build;

import com.wf.wflib.work.WorkOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a volume of world into work: one order per block to take down, ordered top-down so nothing is being stood
 * on when it goes.
 */
public final class SalvagePlan {

    private SalvagePlan() {
    }

    /**
     * @param box the volume to strip, inclusive
     * @throws IllegalArgumentException if the volume is larger than {@code Blueprint.MAX_VOLUME}
     */
    public static WorkPlan of(LevelReader level, BoundingBox box) {
        long volume = (long) box.getXSpan() * box.getYSpan() * box.getZSpan();
        if (volume > Blueprint.MAX_VOLUME) {
            throw new IllegalArgumentException("volume is " + volume + " blocks, limit is "
                    + Blueprint.MAX_VOLUME);
        }
        List<WorkOrder> orders = new ArrayList<>();
        WorkPlan.Bill bill = new WorkPlan.Bill();
        int skipped = 0;
        int unbuildable = 0;

        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    at.set(x, y, z);
                    BlockState state = level.getBlockState(at);
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.getDestroySpeed(level, at) < 0.0F) {
                        continue;
                    }
                    if (Placement.derived(state)) {
                        // Breaking the other half takes this with it, exactly as when building.
                        skipped++;
                        continue;
                    }
                    Item item = WorkPlan.itemFor(state);
                    if (item == null) {
                        unbuildable++;
                    } else {
                        bill.add(item, 1);
                    }
                    orders.add(new WorkOrder(0, at.immutable(),
                            Placement.salvageSequence(y, box.maxY(), state), 0));
                }
            }
        }
        return new WorkPlan(orders, bill.build(), box, skipped, unbuildable);
    }
}
