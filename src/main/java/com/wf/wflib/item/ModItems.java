package com.wf.wflib.item;

import com.wf.wflib.WFLib;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.kinetic.KineticShellItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registers one {@link MissileItem} per registered {@link MissilePreset}, one {@link KineticShellItem} per
 * registered {@link KineticPreset}, and one {@link MineItem} per registered {@link MinePreset}.
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, WFLib.MODID);

    private static final Map<ResourceLocation, DeferredHolder<Item, MissileItem>> MISSILE_ITEMS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, DeferredHolder<Item, MineItem>> MINE_ITEMS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, DeferredHolder<Item, KineticShellItem>> SHELL_ITEMS = new LinkedHashMap<>();

    static {
        // Presets must exist before we enumerate them into items (both happen before the registry freezes).
        MissilePresetRegistry.bootstrap();
        for (MissilePreset preset : MissilePresetRegistry.all()) {
            MISSILE_ITEMS.put(preset.id(), ITEMS.register("missile_" + preset.id().getPath(),
                    () -> new MissileItem(preset, new Item.Properties().stacksTo(16))));
        }
        KineticPresetRegistry.bootstrap();
        for (KineticPreset preset : KineticPresetRegistry.all()) {
            SHELL_ITEMS.put(preset.id(), ITEMS.register("shell_" + preset.id().getPath(),
                    () -> new KineticShellItem(preset, new Item.Properties().stacksTo(preset.stackSize()))));
        }
        MinePresetRegistry.bootstrap();
        for (MinePreset preset : MinePresetRegistry.all()) {
            MINE_ITEMS.put(preset.id(), ITEMS.register("mine_" + preset.id().getPath(),
                    () -> new MineItem(preset, new Item.Properties().stacksTo(16))));
        }
    }

    /**
     * Showcase content for the sensor net: scatters a cloud of radar decoys. No recipe, creative tab only.
     */
    public static final DeferredHolder<Item, Item> DECOY_LAUNCHER =
            ITEMS.register("decoy_launcher", () -> new DecoyLauncherItem(new Item.Properties().stacksTo(1)));

    /** Links and fires mining charges. See {@link com.wf.wflib.demolition.DetonatorItem}. */
    public static final DeferredHolder<Item, Item> DETONATOR =
            ITEMS.register("detonator", () -> new com.wf.wflib.demolition.DetonatorItem(
                    new Item.Properties().stacksTo(1)));

    /** Carries a sensor network from one block to another. See {@link GridKeyItem}. */
    public static final DeferredHolder<Item, Item> GRID_KEY =
            ITEMS.register("grid_key", () -> new GridKeyItem(new Item.Properties().stacksTo(1)));

    /** Picks a camera up and puts it down on a monitor. See {@link CameraLinkerItem}. */
    public static final DeferredHolder<Item, Item> CAMERA_LINKER =
            ITEMS.register("camera_linker", () -> new CameraLinkerItem(new Item.Properties().stacksTo(1)));

    /** A monitor you can carry: the same bounded channel list, in the hand. */
    public static final DeferredHolder<Item, Item> CAMERA_TABLET =
            ITEMS.register("camera_tablet", () -> new CameraTabletItem(new Item.Properties().stacksTo(1)));

    private ModItems() {
    }

    public static Collection<DeferredHolder<Item, MissileItem>> missileItems() {
        return MISSILE_ITEMS.values();
    }

    /** @return the item a missile preset is launched from, or empty if that preset was registered too late. */
    public static Optional<DeferredHolder<Item, MissileItem>> missileItem(ResourceLocation presetId) {
        return Optional.ofNullable(MISSILE_ITEMS.get(presetId));
    }

    public static Collection<DeferredHolder<Item, KineticShellItem>> shellItems() {
        return SHELL_ITEMS.values();
    }

    /** @return the item a kinetic round is loaded from, or empty if that preset was registered too late. */
    public static Optional<DeferredHolder<Item, KineticShellItem>> shellItem(ResourceLocation presetId) {
        return Optional.ofNullable(SHELL_ITEMS.get(presetId));
    }

    public static Collection<DeferredHolder<Item, MineItem>> mineItems() {
        return MINE_ITEMS.values();
    }

    /** @return the item a mine preset is laid from, or empty if that preset was registered too late. */
    public static Optional<DeferredHolder<Item, MineItem>> mineItem(ResourceLocation presetId) {
        return Optional.ofNullable(MINE_ITEMS.get(presetId));
    }

    public static void register(IEventBus bus) {
        ITEMS.register(bus);
    }
}
