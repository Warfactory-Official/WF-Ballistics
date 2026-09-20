package com.wf.wflib.build;

import com.wf.wflib.work.WorkOrder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a {@link Blueprint} into work: one order per block to place, ordered so that nothing is asked for before
 * whatever holds it up exists.
 */
public final class ConstructionPlan {

    private ConstructionPlan() {
    }

    /**
     * @param origin where the blueprint's minimum corner goes in the world
     */
    public static WorkPlan of(Blueprint blueprint, BlockPos origin) {
        List<WorkOrder> orders = new ArrayList<>();
        WorkPlan.Bill bill = new WorkPlan.Bill();
        int skipped = 0;
        int unbuildable = 0;

        for (int y = 0; y < blueprint.size().getY(); y++) {
            for (int z = 0; z < blueprint.size().getZ(); z++) {
                for (int x = 0; x < blueprint.size().getX(); x++) {
                    int cell = blueprint.cells()[blueprint.index(x, y, z)];
                    if (cell == 0) {
                        continue;
                    }
                    BlockState state = blueprint.palette().get(cell);
                    if (Placement.derived(state)) {
                        skipped++;
                        continue;
                    }
                    Item item = WorkPlan.itemFor(state);
                    if (item == null) {
                        unbuildable++;
                        continue;
                    }
                    bill.add(item, 1);
                    orders.add(new WorkOrder(0, origin.offset(x, y, z),
                            Placement.buildSequence(y, state), cell));
                }
            }
        }

        BoundingBox bounds = new BoundingBox(origin.getX(), origin.getY(), origin.getZ(),
                origin.getX() + blueprint.size().getX() - 1,
                origin.getY() + blueprint.size().getY() - 1,
                origin.getZ() + blueprint.size().getZ() - 1);
        return new WorkPlan(orders, bill.build(), bounds, skipped, unbuildable);
    }
}
