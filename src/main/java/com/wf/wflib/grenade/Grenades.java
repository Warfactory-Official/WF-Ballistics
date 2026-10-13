package com.wf.wflib.grenade;

import com.wf.wflib.WFLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Hand-charge entity type + sounds. */
public final class Grenades {

    private static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, WFLib.MODID);
    private static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, WFLib.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<ThrownCharge>> CHARGE = ENTITIES.register(
            "thrown_charge", () -> EntityType.Builder.<ThrownCharge>of(ThrownCharge::new, MobCategory.MISC)
                    .sized(0.15f, 0.15f).clientTrackingRange(8).updateInterval(1).noSummon()
                    .build("thrown_charge"));

    public static final DeferredHolder<SoundEvent, SoundEvent> BOUNCE = sound("grenade.bounce");
    public static final DeferredHolder<SoundEvent, SoundEvent> PIN = sound("grenade.pin");
    public static final DeferredHolder<SoundEvent, SoundEvent> SPOON = sound("grenade.spoon");
    public static final DeferredHolder<SoundEvent, SoundEvent> FLASHBANG = sound("grenade.flashbang");
    public static final DeferredHolder<SoundEvent, SoundEvent> FLASHBANG_FAR = sound("grenade.flashbang_far");
    public static final DeferredHolder<SoundEvent, SoundEvent> THERMITE_IGNITE = sound("grenade.thermite_ignite");
    public static final DeferredHolder<SoundEvent, SoundEvent> THERMITE_LOOP = sound("grenade.thermite_loop");
    public static final DeferredHolder<SoundEvent, SoundEvent> SMOKE_HISS = sound("grenade.smoke_hiss");
    public static final DeferredHolder<SoundEvent, SoundEvent> TINNITUS = sound("grenade.tinnitus");

    private Grenades() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, path);
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(id));
    }

    public static void register(IEventBus bus) {
        ENTITIES.register(bus);
        SOUNDS.register(bus);
    }
}
