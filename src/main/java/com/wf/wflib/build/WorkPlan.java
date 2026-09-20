package com.wf.wflib.build;

import com.wf.wflib.work.WorkOrder;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The result of turning something (a blueprint, a volume of world) into work.
 *
 * @param orders what to do, ready for {@code WorkQueue.of}. Ids are placeholders; the queue assigns its
 *      own
 * @param bill what it costs to build, or roughly what taking it down gives back. Exact for a
 *      construction, since the item that places a block is the item a block needs; an estimate
 *      for a demolition, since what a block actually drops is a loot table and a stone block
 *      mined without a pickaxe drops nothing at all
 * @param bounds the volume affected
 * @param skipped cells deliberately not given an order: the upper half of a door, the head of a bed, the
 *      top of a tall flower. Placing the other half creates these, and ordering them separately
 *      would have a drone fly out to build something that already exists
 * @param unbuildable cells whose block has no item to place it with. Reported rather than dropped quietly:
 *      see {@link #itemFor}
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

    /**
     * @return how many items the whole job needs, across every kind.
     */
    public int totalItems() {
        int total = 0;
        for (int count : this.bill.values()) {
            total += count;
        }
        return total;
    }

    /**
     * @return the bill as lines of {@code "64 x Stone"}, commonest first.
     */
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

    /**
     * Accumulates a bill while a plan is being built.
     */
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
