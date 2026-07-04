package com.wf.wfballistics.fire;

import com.wf.wfballistics.client.particle.WFParticles;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class FlameCreator {

    private FlameCreator() {
    }

    public static void compose(Level level, double x, double y, double z, FireType type) {
        if (level instanceof ServerLevel server) {
            server.sendParticles(particleFor(type), x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    public static void composeClient(Level level, double x, double y, double z, FireType type) {
        if (level.isClientSide) {
            level.addParticle(particleFor(type), x, y, z, 0.0, 0.0, 0.0);
        }
    }

    private static SimpleParticleType particleFor(FireType type) {
        return WFParticles.FLAME.get();
    }
}
