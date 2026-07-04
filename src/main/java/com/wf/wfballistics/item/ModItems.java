package com.wf.wfballistics.item;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registers one {@link MissileItem} per registered {@link MissilePreset}. The items are named
 * {@code missile_<presetId>} and stack to 16.
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, WFBallistics.MODID);

    private static final Map<ResourceLocation, RegistryObject<MissileItem>> MISSILE_ITEMS = new LinkedHashMap<>();

    static {
        // Presets must exist before we enumerate them into items (both happen before the registry freezes).
        MissilePresetRegistry.bootstrap();
        for (MissilePreset preset : MissilePresetRegistry.all()) {
            MISSILE_ITEMS.put(preset.id(), ITEMS.register("missile_" + preset.id().getPath(),
                    () -> new MissileItem(preset, new Item.Properties().stacksTo(16))));
        }
    }

    private ModItems() {
    }

    public static Collection<RegistryObject<MissileItem>> missileItems() {
        return MISSILE_ITEMS.values();
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
