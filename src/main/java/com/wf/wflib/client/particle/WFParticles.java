package com.wf.wflib.client.particle;

import com.wf.wflib.WFLib;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class WFParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, WFLib.MODID);

    public static final DeferredHolder<ParticleType<?>, SimpleParticleType>EXPLOSION_SMALL =
            PARTICLE_TYPES.register("explosion_small", () -> new SimpleParticleType(true) {
            });
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType>ROCKET_FLAME =
            PARTICLE_TYPES.register("rocket_flame", () -> new SimpleParticleType(true) {
            });
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType>FLAME =
            PARTICLE_TYPES.register("flame", () -> new SimpleParticleType(true) {
            });
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType>ASH =
            PARTICLE_TYPES.register("ash", () -> new SimpleParticleType(false) {
            });
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType>MIST =
            PARTICLE_TYPES.register("mist", () -> new SimpleParticleType(false) {
            });

    /** Tumbling copy of its source block; a blast throws actual ore, not grey confetti. */
    public static final DeferredHolder<ParticleType<?>, ParticleType<BlockParticleOption>> BLOCK_DEBRIS =
            PARTICLE_TYPES.register("block_debris", () -> new ParticleType<BlockParticleOption>(false) {

                @Override
                public MapCodec<BlockParticleOption> codec() {
                    return BlockParticleOption.codec(this);
                }

                @Override
                public StreamCodec<? super RegistryFriendlyByteBuf, BlockParticleOption> streamCodec() {
                    return BlockParticleOption.streamCodec(this);
                }
            });

    /** The heavy dark smoke a demolition charge leaves hanging over the hole. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE_PLUME =
            PARTICLE_TYPES.register("smoke_plume", () -> new SimpleParticleType(false) {
            });

    /** Smoke-grenade screen: white, slow. */
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> SMOKE_SCREEN =
            PARTICLE_TYPES.register("smoke_screen", () -> new SimpleParticleType(true) {
            });

    private WFParticles() {
    }

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }
}
