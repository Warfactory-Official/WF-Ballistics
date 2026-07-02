package com.wf.wfballistics.client.particle;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registers the mod's {@link ParticleType}s. Even though the particles are usually spawned directly (for
 * custom scale/speed), they still need registered types so their textures get stitched into the particle
 * atlas (via {@code assets/wfballistics/particles/*.json}) and a {@code SpriteSet} can be captured.
 *
 * <p>{@link SimpleParticleType} has a protected constructor, hence the {@code (){}} anonymous subclasses.
 */
public final class WFParticles {

    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, WFBallistics.MODID);

    public static final RegistryObject<SimpleParticleType> EXPLOSION_SMALL =
            PARTICLE_TYPES.register("explosion_small", () -> new SimpleParticleType(true) {
            });
    public static final RegistryObject<SimpleParticleType> ROCKET_FLAME =
            PARTICLE_TYPES.register("rocket_flame", () -> new SimpleParticleType(true) {
            });
    public static final RegistryObject<SimpleParticleType> ASH =
            PARTICLE_TYPES.register("ash", () -> new SimpleParticleType(false) {
            });
    public static final RegistryObject<SimpleParticleType> MIST =
            PARTICLE_TYPES.register("mist", () -> new SimpleParticleType(false) {
            });

    private WFParticles() {
    }

    public static void register(IEventBus modBus) {
        PARTICLE_TYPES.register(modBus);
    }
}
