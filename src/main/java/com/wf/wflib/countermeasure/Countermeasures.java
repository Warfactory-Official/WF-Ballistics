package com.wf.wflib.countermeasure;

import com.wf.wflib.WFLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Countermeasure entity types + their launcher items. */
public final class Countermeasures {

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, WFLib.MODID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, WFLib.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<FlareEntity>> FLARE = ENTITIES.register("flare",
            () -> EntityType.Builder.<FlareEntity>of(FlareEntity::new, MobCategory.MISC).sized(0.25f, 0.25f)
                    .clientTrackingRange(16).updateInterval(1).noSave().build("flare"));
    public static final DeferredHolder<EntityType<?>, EntityType<SmokeGrenadeEntity>> SMOKE_GRENADE =
            ENTITIES.register("smoke_grenade", () -> EntityType.Builder.<SmokeGrenadeEntity>of(
                            SmokeGrenadeEntity::new, MobCategory.MISC).sized(0.3f, 0.3f).clientTrackingRange(16)
                    .updateInterval(1).noSave().build("smoke_grenade"));
    public static final DeferredHolder<EntityType<?>, EntityType<ApsGrenadeEntity>> APS_GRENADE =
            ENTITIES.register("aps_grenade", () -> EntityType.Builder.<ApsGrenadeEntity>of(
                            ApsGrenadeEntity::new, MobCategory.MISC).sized(0.3f, 0.3f).clientTrackingRange(16)
                    .updateInterval(1).noSave().build("aps_grenade"));

    public static final DeferredHolder<Item, CountermeasureItem> FLARE_CARTRIDGE = ITEMS.register(
            "flare_cartridge", () -> new CountermeasureItem(CountermeasureItem.Kind.FLARE,
                    new Item.Properties().stacksTo(64)));
    public static final DeferredHolder<Item, CountermeasureItem> SMOKE_GRENADE_ITEM = ITEMS.register(
            "smoke_grenade", () -> new CountermeasureItem(CountermeasureItem.Kind.SMOKE,
                    new Item.Properties().stacksTo(16)));
    public static final DeferredHolder<Item, CountermeasureItem> APS_GRENADE_ITEM = ITEMS.register(
            "aps_grenade", () -> new CountermeasureItem(CountermeasureItem.Kind.APS,
                    new Item.Properties().stacksTo(16)));

    private Countermeasures() {
    }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        ITEMS.register(bus);
    }
}
