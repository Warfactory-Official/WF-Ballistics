package com.wf.wfballistics;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Registered {@link SoundEvent}s. */
public final class WFSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, WFBallistics.MODID);

    public static final RegistryObject<SoundEvent> NUCLEAR_EXPLOSION = register("weapon.nuclear_explosion");
    public static final RegistryObject<SoundEvent> FIRE_DISINTEGRATION = register("weapon.fire.disintegration");
    public static final RegistryObject<SoundEvent> EXPLOSION_SMALL_NEAR = register("weapon.explosion_small_near");
    public static final RegistryObject<SoundEvent> EXPLOSION_SMALL_FAR = register("weapon.explosion_small_far");
    public static final RegistryObject<SoundEvent> EXPLOSION_LARGE_NEAR = register("weapon.explosion_large_near");

    private WFSounds() { }

    private static RegistryObject<SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(WFBallistics.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
