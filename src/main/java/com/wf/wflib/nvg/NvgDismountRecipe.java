package com.wf.wflib.nvg;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** Lone helmet carrying an NVG => the NVG; the helmet stays in its slot without it. */
public class NvgDismountRecipe extends CustomRecipe {
    public NvgDismountRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return helmetSlot(input) >= 0;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        int slot = helmetSlot(input);
        return slot < 0 ? ItemStack.EMPTY : NvgWorn.raised(NvgWorn.mounted(input.getItem(slot)).copy());
    }

    @Override
    public NonNullList<ItemStack> getRemainingItems(CraftingInput input) {
        NonNullList<ItemStack> remaining = NonNullList.withSize(input.size(), ItemStack.EMPTY);
        int slot = helmetSlot(input);
        if (slot >= 0) {
            ItemStack helmet = input.getItem(slot).copyWithCount(1);
            helmet.remove(NvgComponents.MOUNT.get());
            remaining.set(slot, helmet);
        }
        return remaining;
    }

    private static int helmetSlot(CraftingInput input) {
        if (input.ingredientCount() != 1) {
            return -1;
        }
        for (int i = 0; i < input.size(); i++) {
            ItemStack stack = input.getItem(i);
            if (!stack.isEmpty()) {
                return NvgWorn.mounted(stack).isEmpty() ? -1 : i;
            }
        }
        return -1;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 1;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return NvgRegistry.DISMOUNT.get();
    }
}
