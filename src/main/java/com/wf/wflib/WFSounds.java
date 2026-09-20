package com.wf.wflib;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registered {@link SoundEvent}s.
 */
public final class WFSounds {

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, WFLib.MODID);

    public static final DeferredHolder<SoundEvent, SoundEvent> NUCLEAR_EXPLOSION = register("weapon.nuclear_explosion");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRE_DISINTEGRATION = register("weapon.fire.disintegration");
    public static final DeferredHolder<SoundEvent, SoundEvent> EXPLOSION_SMALL_NEAR = register("weapon.explosion_small_near");
    public static final DeferredHolder<SoundEvent, SoundEvent> EXPLOSION_SMALL_FAR = register("weapon.explosion_small_far");
    public static final DeferredHolder<SoundEvent, SoundEvent> EXPLOSION_LARGE_NEAR = register("weapon.explosion_large_near");
    public static final DeferredHolder<SoundEvent, SoundEvent> EXPLOSION_LARGE_FAR = register("weapon.explosion_large_far");
    public static final DeferredHolder<SoundEvent, SoundEvent> SONIC_BOOM = register("weapon.sonic_boom");
    public static final DeferredHolder<SoundEvent, SoundEvent> MISSILE_FLIGHT = register("weapon.missile_flight");
    /** A quadcopter holding an unladen hover, looped. */
    public static final DeferredHolder<SoundEvent, SoundEvent> DRONE_ROTOR_LOOP = register("drone.rotor_loop");

    /** The demolition set, moved across with the mining charges themselves. */
    public static final DeferredHolder<SoundEvent, SoundEvent> MINING_CHARGE_BLAST =
            register("mining_charge_blast");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINING_CHARGE_BLAST_DISTANT =
            register("mining_charge_blast_distant");
    public static final DeferredHolder<SoundEvent, SoundEvent> DETONATOR_ARM = register("detonator_arm");
    public static final DeferredHolder<SoundEvent, SoundEvent> DETONATOR_DETONATE =
            register("detonator_detonate");

    private WFSounds() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUND_EVENTS.register(name,
                () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(WFLib.MODID, name)));
    }

    public static void register(IEventBus modBus) {
        SOUND_EVENTS.register(modBus);
    }
}
