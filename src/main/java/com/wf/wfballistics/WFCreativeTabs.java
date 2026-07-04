package com.wf.wfballistics;

import com.wf.wfballistics.block.ModBlocks;
import com.wf.wfballistics.item.ModItems;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * The mod's dedicated creative tab, collecting every WF-Ballistics item — blocks/machines and the preset
 * missiles — onto one page.
 */
public final class WFCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, WFBallistics.MODID);

    public static final RegistryObject<CreativeModeTab> WFBALLISTICS = TABS.register("wfballistics", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.wfballistics"))
                    .icon(() -> new ItemStack(ModBlocks.MISSILE_DISPENSER_ITEM.get()))
                    .displayItems((params, output) -> {
                        // Blocks & machines.
                        output.accept(ModBlocks.MISSILE_DISPENSER_ITEM.get());
                        output.accept(ModBlocks.MISSILE_LISTENER_DEBUG_ITEM.get());
                        output.accept(ModBlocks.TURRET_CIWS_ITEM.get());
                        output.accept(ModBlocks.TURRET_INTERCEPTOR_ITEM.get());
                        output.accept(ModBlocks.TURRET_INTERCEPTOR_SUPERSONIC_ITEM.get());
                        output.accept(com.wf.wfballistics.fluid.WFFluids.KEROSENE_BUCKET.get());
                        // Preset missiles (registration order).
                        ModItems.missileItems().forEach(item -> output.accept(item.get()));
                    })
                    .build());

    private WFCreativeTabs() {
    }

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }
}
