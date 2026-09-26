package com.wf.wflib.round.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.wflib.round.RocketEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Look of a round, rocket or missile drawn by its owner mod instead of a WFLib glTF. {@link RoundRenderers}: rounds and
 * rockets by preset id, missiles by model id.
 */
public interface RoundRenderer {

    /** {@code pose} origin = the projectile, world axes. {@code nose} unit. Missiles: {@code burning} false. */
    void render(PoseStack pose, MultiBufferSource buffers, int light, Vec3 nose, boolean burning);

    /** Missiles: false => the airframe glTF draws instead (checked each frame). */
    default boolean hasLook() {
        return true;
    }

    /** Rocket, each client tick while burning. */
    default void exhaust(ClientLevel level, RocketEntity rocket) {
        level.addParticle(ParticleTypes.CLOUD, rocket.xo, rocket.yo, rocket.zo, 0.0, 0.0, 0.0);
    }

    /** Rocket, first client tick. */
    default void launched(RocketEntity rocket) {
    }

    /** Entity-less round, each client tick after its step. */
    default void tick(long key, Vec3 at, Vec3 velocity, boolean resting) {
    }

    /** Entity-less round gone (ended, expired, logout). */
    default void ended(long key) {
    }
}
