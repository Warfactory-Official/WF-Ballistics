package com.wf.wflib;

import com.wf.wflib.block.ModBlocks;
import com.wf.wflib.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The mod's dedicated creative tab, collecting every WFLib item, blocks/machines and the preset missiles,
 * onto one page.
 */
public final class WFCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WFLib.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> WFLIB = TABS.register("wflib", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wflib"))
                    .icon(() -> new ItemStack(ModBlocks.MISSILE_DISPENSER_ITEM.get()))
                    .displayItems((params, output) -> {
                        // Blocks & machines.
                        output.accept(ModBlocks.MISSILE_DISPENSER_ITEM.get());
                        output.accept(ModBlocks.MISSILE_LISTENER_DEBUG_ITEM.get());
                        output.accept(ModBlocks.DRONE_PAD_ITEM.get());
                        output.accept(ModBlocks.TURRET_CIWS_ITEM.get());
                        output.accept(ModBlocks.TURRET_INTERCEPTOR_ITEM.get());
                        output.accept(ModBlocks.TURRET_INTERCEPTOR_SUPERSONIC_ITEM.get());
                        output.accept(ModBlocks.GLYPHID_NEST_ITEM.get());
                        output.accept(ModBlocks.GLYPHID_NEST_REINFORCED_ITEM.get());
                        output.accept(ModBlocks.GLYPHID_SPAWNER_ITEM.get());
                        output.accept(com.wf.wflib.fluid.WFFluids.KEROSENE_BUCKET.get());
                        output.accept(ModBlocks.RADAR_SURVEILLANCE_ITEM.get());
                        output.accept(ModBlocks.RADAR_SCOPE_ITEM.get());
                        output.accept(ModBlocks.CAMERA_MONITOR_ITEM.get());
                        output.accept(ModBlocks.SECURITY_CAMERA_ITEM.get());
                        output.accept(ModItems.CAMERA_LINKER.get());
                        output.accept(ModItems.CAMERA_TABLET.get());
                        output.accept(ModItems.DECOY_LAUNCHER.get());
                        output.accept(ModBlocks.MINING_CHARGE_ITEM.get());
                        output.accept(ModBlocks.DEEP_MINING_CHARGE_ITEM.get());
                        output.accept(ModItems.DETONATOR.get());
                        output.accept(ModBlocks.RECON_HUB_ITEM.get());
                        ModBlocks.PROBE_ITEMS.values().forEach(item -> output.accept(item.get()));
                        output.accept(ModBlocks.GRID_POWER_CELL_ITEM.get());
                        // The NTM door roster, one entry per skin. See com.wf.wflib.door.
                        com.wf.wflib.door.ModDoors.creativeStacks().forEach(output::accept);
                        output.accept(com.wf.wflib.door.ModDoors.DOOR_LOCK.get());
                        output.accept(com.wf.wflib.door.ModDoors.DOOR_KEY.get());
                        output.accept(ModBlocks.LANDING_PAD_ITEM.get());
                        output.accept(ModBlocks.ORBITAL_JAMMER_ITEM.get());
                        output.accept(ModBlocks.CARGO_SHUTTLE_ITEM.get());
                        output.accept(ModItems.GRID_KEY.get());
                        // Preset missiles (registration order).
                        ModItems.missileItems().forEach(item -> output.accept(item.get()));
                        // Loadable kinetic rounds (registration order).
                        ModItems.shellItems().forEach(item -> output.accept(item.get()));
                        // Preset mines (registration order).
                        ModItems.mineItems().forEach(item -> output.accept(item.get()));
                    })
                    .build());

    private WFCreativeTabs() {
    }

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
