package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.client.particle.MistParticle;
import com.wf.wfballistics.client.particle.WFParticleSprites;
import com.wf.wfballistics.entity.MistEntity;
import com.wf.wfballistics.entity.mist.MistEffect;
import com.wf.wfballistics.entity.mist.MistEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Client-only renderer of a {@link MistEntity}: fills its bounding box with tinted {@link MistParticle}s
 * every tick. Kept out of the entity class proper so the dedicated server never touches client code.
 *
 * <p>The cloud's tint comes from its {@link MistEffect} (if it overrides {@code color}), otherwise from the
 * fluid's own render colour.
 */
public final class MistClientFX {

    /**
     * Particles spawned per cloud per tick. Five times HBM's original two-per-tick — a much denser cloud,
     * as requested.
     */
    private static final int PARTICLES_PER_TICK = 10;

    private MistClientFX() { }

    public static void spawn(MistEntity mist) {
        if (WFParticleSprites.mist == null) return;

        ClientLevel level = (ClientLevel) mist.level();
        int color = tintFor(mist);

        var box = mist.getBoundingBox();
        ParticleEngine engine = Minecraft.getInstance().particleEngine;

        for (int i = 0; i < PARTICLES_PER_TICK; i++) {
            double x = box.minX + level.random.nextDouble() * (box.maxX - box.minX);
            double y = box.minY + level.random.nextDouble() * (box.maxY - box.minY);
            double z = box.minZ + level.random.nextDouble() * (box.maxZ - box.minZ);

            MistParticle particle = new MistParticle(level, x, y, z, 0.75F, color);
            particle.pickSprite(WFParticleSprites.mist);
            engine.add(particle);
        }
    }

    private static int tintFor(MistEntity mist) {
        MistEffect effect = MistEffects.get(mist.getFluid());
        if (effect != null) {
            int c = effect.color(mist);
            if (c != -1) return c & 0xFFFFFF;
        }
        return fluidTint(mist.getFluid());
    }

    private static int fluidTint(Fluid fluid) {
        int argb = IClientFluidTypeExtensions.of(fluid).getTintColor();
        int rgb = argb & 0xFFFFFF;
        return rgb == 0xFFFFFF ? 0xC0C0C0 : rgb; // grey fallback for untinted fluids
    }
}
