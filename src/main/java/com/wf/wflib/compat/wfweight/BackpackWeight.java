package com.wf.wflib.compat.wfweight;

import com.warfactory.ultimateweight.api.IWeightCompatProvider;
import com.warfactory.ultimateweight.api.WeightCompatRegistry;
import com.warfactory.ultimateweight.core.WeightResolutionContext;
import com.warfactory.ultimateweight.v1211.WeightViews1211;
import com.wf.wflib.backpack.BackpackItem;
import com.wf.wflib.backpack.BackpackSpec;
import com.wf.wflib.backpack.Backpacks;
import net.minecraft.world.item.ItemStack;

import java.util.OptionalDouble;

/**
 * Backpack unit weight = {@code emptyKg + contentWeight * sum(contents)}; priority above wfweight's nested provider
 * (100) and item providers (MineAssault 200). Worn slot counted as an inventory source.
 */
public final class BackpackWeight implements IWeightCompatProvider {
    static final int PRIORITY = 300;

    private BackpackWeight() {
    }

    public static void register() {
        WeightCompatRegistry.register(new BackpackWeight());
        WeightViews1211.registerInventorySource((player, out) -> {
            ItemStack worn = Backpacks.worn(player);
            if (!worn.isEmpty()) {
                out.add(worn);
            }
        });
        Backpacks.weigher = WeightViews1211::weightOf;
    }

    @Override
    public OptionalDouble getUnitWeight(Object stack) {
        if (!(stack instanceof ItemStack s) || !(s.getItem() instanceof BackpackItem item)) {
            return OptionalDouble.empty();
        }
        BackpackSpec spec = item.spec();
        int depth = WeightResolutionContext.currentDepth() + 1;
        double contents = 0;
        if (depth <= WeightViews1211.maxNestedDepth()) {
            for (ItemStack c : Backpacks.contents(s)) {
                contents += WeightViews1211.stackWeight(c, depth);
            }
        }
        return OptionalDouble.of(spec.emptyKg() + spec.contentWeight() * contents);
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }
}
