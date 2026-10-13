package com.wf.wflib.nvg;

import com.wf.wflib.WFLib;
import com.wf.wflib.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.List;

public final class NvgRegistry {
    /** Item paths; optics {@code wflib:nvg/<path>.json}, model {@code wflib:models/nvg/<path>.glb}. */
    public static final List<String> GOGGLES = List.of("nvg_gen1", "nvg_pvs7", "nvg_pvs14", "nvg_pvs31", "nvg_gpnvg18");

    public static final List<DeferredHolder<Item, NvgItem>> ITEMS = GOGGLES.stream()
            .map(path -> ModItems.ITEMS.register(path, () -> new NvgItem(new Item.Properties().stacksTo(1))))
            .toList();

    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, WFLib.MODID);

    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<NvgDismountRecipe>> DISMOUNT =
            SERIALIZERS.register("nvg_dismount", () -> new SimpleCraftingRecipeSerializer<>(NvgDismountRecipe::new));

    private NvgRegistry() {
    }

    public static void register(IEventBus bus) {
        NvgComponents.register(bus);
        SERIALIZERS.register(bus);
    }
}
