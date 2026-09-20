package com.wf.wflib.orbital;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an asteroid miner comes back with, as a weighted pool anybody can add to. */
public final class CargoTable {

    /** Slots a shuttle can carry home. One chest, which is also what the block on the ground is. */
    public static final int SLOTS = 27;

    private static final Map<ItemStack, Integer> POOL = new LinkedHashMap<>();
    private static int totalWeight;

    private CargoTable() {
    }

    /**
     * Add an entry.
     *
     * @param prototype one unit of this cargo. Copied on every roll, so the caller keeps ownership.
     */
    public static void register(ItemStack prototype, int weight) {
        if (prototype.isEmpty() || weight <= 0) {
            return;
        }
        POOL.put(prototype.copy(), weight);
        totalWeight += weight;
    }

    /** Drop the whole table, including the shipped default. For a pack replacing the economy outright. */
    public static void clear() {
        POOL.clear();
        totalWeight = 0;
    }

    public static boolean isEmpty() {
        return POOL.isEmpty();
    }

    public static int entryCount() {
        return POOL.size();
    }

    /**
     * Roll a manifest.
     *
     * @param units how much cargo the hold accrued.
     */
    public static List<ItemStack> roll(RandomSource random, int units) {
        List<ItemStack> out = new ArrayList<>();
        if (POOL.isEmpty() || units <= 0) {
            return out;
        }
        for (int i = 0; i < units; i++) {
            ItemStack picked = pick(random);
            if (picked == null) {
                continue;
            }
            ItemStack merged = null;
            for (ItemStack held : out) {
                if (ItemStack.isSameItemSameComponents(held, picked)
                        && held.getCount() + picked.getCount() <= held.getMaxStackSize()) {
                    held.grow(picked.getCount());
                    merged = held;
                    break;
                }
            }
            if (merged == null && out.size() < SLOTS) {
                out.add(picked.copy());
            }
        }
        return out;
    }

    private static ItemStack pick(RandomSource random) {
        int roll = random.nextInt(Math.max(1, totalWeight));
        for (Map.Entry<ItemStack, Integer> entry : POOL.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The shipped default: what a rock in space is actually made of, in vanilla terms. */
    public static void bootstrap() {
        register(new ItemStack(Items.RAW_IRON), 30);
        register(new ItemStack(Items.COBBLESTONE, 4), 24);
        register(new ItemStack(Items.RAW_COPPER), 18);
        register(new ItemStack(Items.COAL), 12);
        register(new ItemStack(Items.RAW_GOLD), 6);
        register(new ItemStack(Items.REDSTONE, 3), 6);
        register(new ItemStack(Items.LAPIS_LAZULI, 2), 3);
        register(new ItemStack(Items.DIAMOND), 1);
    }
}
