package com.wf.wflib.client.fx;

import com.wf.wflib.client.particle.MistParticle;
import com.wf.wflib.client.particle.WFParticleSprites;
import com.wf.wflib.entity.MistEntity;
import com.wf.wflib.entity.mist.MistEffect;
import com.wf.wflib.entity.mist.MistEffects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;

/**
 * Client-only renderer of a {@link MistEntity}: fills its bounding box with tinted {@link MistParticle}s every
 * tick.
 */
public final class MistClientFX {

    /** Particles per block of cloud volume per tick, and the per-entity cap. */
    private static final double PARTICLE_DENSITY = 0.06;
    private static final int MAX_PARTICLES = 120;

    private MistClientFX() {
    }

    public static void spawn(MistEntity mist) {
        if (com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(mist.level())) return;
        if (WFParticleSprites.mist == null) return;

        ClientLevel level = (ClientLevel) mist.level();
        int color = tintFor(mist);

        var box = mist.getBoundingBox();
        ParticleEngine engine = Minecraft.getInstance().particleEngine;

        double volume = (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
        int count = Mth.clamp((int) Math.round(volume * PARTICLE_DENSITY), 1, MAX_PARTICLES);
        for (int i = 0; i < count; i++) {
            double x = box.minX + level.random.nextDouble() * (box.maxX - box.minX);
            double y = box.minY + level.random.nextDouble() * (box.maxY - box.minY);
            double z = box.minZ + level.random.nextDouble() * (box.maxZ - box.minZ);

            MistParticle particle = new MistParticle(level, x, y, z, 0.75F, color);
            particle.pickSprite(WFParticleSprites.mist);
            engine.add(particle);
        }
    }

    public static int tintFor(MistEntity mist) {
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
