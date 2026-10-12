package com.wf.wflib.build;

import com.wf.wflib.work.WorkOrder;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Output of {@link ConstructionPlan#of} or {@link SalvagePlan#of}.
 *
 * @param orders ids are placeholders; {@code WorkQueue.of} assigns its own.
 * @param bill exact for construction (item placed = item needed); an estimate for demolition (loot table,
 *      pickaxe-less stone drops nothing).
 * @param skipped {@link Placement#derived} cells: the other half already creates them.
 * @param unbuildable reported, not dropped: see {@link #itemFor}.
 */
public record WorkPlan(List<WorkOrder> orders, Map<Item, Integer> bill, BoundingBox bounds,
                       int skipped, int unbuildable) {

    public WorkPlan {
        orders = List.copyOf(orders);
        bill = Map.copyOf(bill);
    }

    public int size() {
        return this.orders.size();
    }

    public int totalItems() {
        int total = 0;
        for (int count : this.bill.values()) {
            total += count;
        }
        return total;
    }

    public List<String> billLines() {
        List<Map.Entry<Item, Integer>> entries = new ArrayList<>(this.bill.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        List<String> lines = new ArrayList<>(entries.size());
        for (Map.Entry<Item, Integer> entry : entries) {
            lines.add(entry.getValue() + " x " + entry.getKey().getDescription().getString());
        }
        return lines;
    }

    /** The item a block state is placed from, or null if there is not one. */
    public static Item itemFor(net.minecraft.world.level.block.state.BlockState state) {
        Item item = state.getBlock().asItem();
        return item == net.minecraft.world.item.Items.AIR ? null : item;
    }

    public static final class Bill {
        private final Map<Item, Integer> counts = new LinkedHashMap<>();

        public void add(Item item, int amount) {
            this.counts.merge(item, amount, Integer::sum);
        }

        public Map<Item, Integer> build() {
            return this.counts;
        }
    }
}
