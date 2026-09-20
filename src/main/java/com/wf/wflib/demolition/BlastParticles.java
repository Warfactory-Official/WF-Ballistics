package com.wf.wflib.demolition;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Sends a blast's particles far enough away to actually be seen. */
public final class BlastParticles {

    /** How far a blast's particles are sent, in blocks. Vanilla's unforced limit is 32. */
    public static final double RANGE = 256.0;

    private BlastParticles() {
    }

    /**
     * A burst of {@code count} particles spread over the given box, exactly as the vanilla broadcast overload would
     * spawn them, but visible out to {@link #RANGE}.
     */
    public static void burst(ServerLevel level, ParticleOptions type, double x, double y, double z,
                             int count, double spreadX, double spreadY, double spreadZ, double speed) {
        send(level, new ClientboundLevelParticlesPacket(type, true, x, y, z,
                (float) spreadX, (float) spreadY, (float) spreadZ, (float) speed, count), x, y, z);
    }

    /** One particle with a velocity of its own. */
    public static void directed(ServerLevel level, ParticleOptions type, double x, double y, double z,
                                Vec3 velocity, double speed) {
        send(level, new ClientboundLevelParticlesPacket(type, true, x, y, z,
                (float) velocity.x, (float) velocity.y, (float) velocity.z, (float) speed, 0), x, y, z);
    }

    private static void send(ServerLevel level, ClientboundLevelParticlesPacket packet,
                             double x, double y, double z) {
        double rangeSq = RANGE * RANGE;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) <= rangeSq) {
                player.connection.send(packet);
            }
        }
    }
}
