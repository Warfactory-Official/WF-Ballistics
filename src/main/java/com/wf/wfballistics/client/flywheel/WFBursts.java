package com.wf.wfballistics.client.flywheel;

import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.LevelContactProbe;
import com.wf.gemrender.particle.ParticleCollision;
import com.wf.gemrender.particle.ParticleInstance;
import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticleBuffer;
import com.wf.gemrender.particle.ParticleStyle;
import com.wf.wfballistics.client.fx.ParticleLight;
import com.wf.wfballistics.client.fx.ShatterScheduler;
import com.wf.wfballistics.client.wiaj.JarModels;
import org.joml.Vector3f;
import dev.engine_room.flywheel.api.instance.InstanceType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The four hand-ticked particle bursts, as {@link InstancedBurst}es. */
public final class WFBursts {

    private static final float TAU = Mth.TWO_PI;

    /** Per-second velocities from per-tick ones. */
    private static final double PER_SECOND = 20.0;

    /** One instance type per kind of particle, built once. */
    private static final InstanceType<ParticleInstance> ASH = GemRenderParticleTypes.custom(
            WFParticleTextures.ASH_VERTEX, WFParticleTextures.BILLBOARD_CULL);

    private static final InstanceType<ParticleInstance> CHUNK = GemRenderParticleTypes.custom(
            WFParticleTextures.DEBRIS_VERTEX, WFParticleTextures.MESH_CULL);

    private static final InstanceType<ParticleInstance> FOAM = GemRenderParticleTypes.custom(
            WFParticleTextures.FOAM_VERTEX, WFParticleTextures.FOAM_CULL);

    private WFBursts() {
    }

    /** The fireball an explosion throws up: {@code RocketFlameParticle} as spawned by {@code explosionLarge}. */
    public static InstancedBurst cloud(Level level, double x, double y, double z, int count, float scale,
                                       float speed, RandomSource random) {
        return new InstancedBurst(level, GemRenderParticleTypes.BILLBOARD,
                ParticleModels.translucent(WFParticleTextures.MIST_SOFT),
                WFParticleStyles.cloud(), count, x, y, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        float life = (220.0f + random.nextInt(80)) / 20.0f;
                        longest = Math.max(longest, life);

                        emitter.spawn(x, y, z,
                                random.nextGaussian() * 0.5 * speed * PER_SECOND,
                                random.nextDouble() * 3.0 * speed * PER_SECOND,
                                random.nextGaussian() * 0.5 * speed * PER_SECOND,
                                life, 2.0f * scale);
                    }
                    return longest;
                });
    }

    /**
     * The column a blast throws off a water surface: droplets, launched fastest at the centre and outward at the
     * rim, which is what makes it a plume rather than a sphere.
     *
     * @param surface the water surface the blast broke, which is where the spray leaves from
     * @param radius the blast's reach in blocks; sets both how wide the column is and how high it goes
     */
    public static InstancedBurst foamPlume(Level level, double x, double surface, double z, int count,
                                           float radius, RandomSource random) {
        int packed = ParticleLight.surface(level, x, surface + 1.0, z);
        float reach = Math.max(1.0f, radius);
        float mouth = Math.max(0.6f, reach * 0.35f);
        // The column is sized by where it is meant to top out, not by a velocity: pick the height and the
        // launch speed follows, so a bigger blast throws a taller plume instead of one off the screen.
        double fastest = Math.sqrt(2.0 * WFParticleStyles.SPRAY_GRAVITY * reach * 2.6);

        return new InstancedBurst(level, FOAM,
                ParticleModels.translucent(WFParticleTextures.FOAM),
                WFParticleStyles.foamPlume(LightTexture.block(packed), LightTexture.sky(packed)),
                count, x, surface, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        double angle = random.nextDouble() * TAU;
                        // Biased inward, so most of the spray is in the column rather than on its collar.
                        double dist = mouth * Math.pow(random.nextDouble(), 1.5);
                        double cos = Math.cos(angle);
                        double sin = Math.sin(angle);
                        float core = (float) (1.0 - dist / mouth);

                        double up = fastest * (0.32 + 0.68 * core);
                        double out = (0.5 + 1.8 * (1.0 - core)) * reach;

                        // A droplet lives as long as its own arc, so it goes out at the water rather than
                        // in mid-air, and the rim does not hang after it has already come down.
                        float life = (float) (2.0 * up / WFParticleStyles.SPRAY_GRAVITY)
                                * (1.0f + random.nextFloat() * 0.35f);
                        longest = Math.max(longest, life);

                        emitter.spawn(x + cos * dist, surface + random.nextDouble() * 0.4, z + sin * dist,
                                cos * out + random.nextGaussian() * 1.1,
                                up,
                                sin * out + random.nextGaussian() * 1.1,
                                life,
                                2.0f * (0.3f + random.nextFloat() * 0.4f) * Mth.sqrt(reach),
                                random.nextFloat() * TAU,
                                0.9f + random.nextFloat() * 0.1f);
                    }
                    return longest;
                });
    }

    /** The base surge: foam driven out across the surface, which floats where it stops. */
    public static InstancedBurst foamSurge(Level level, double x, double surface, double z, int count,
                                           float radius, RandomSource random) {
        int packed = ParticleLight.surface(level, x, surface + 1.0, z);
        float reach = Math.max(1.0f, radius);

        return new InstancedBurst(level, FOAM,
                ParticleModels.translucent(WFParticleTextures.FOAM),
                WFParticleStyles.foamSurge(LightTexture.block(packed), LightTexture.sky(packed)),
                count, x, surface, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        double angle = random.nextDouble() * TAU;
                        double dist = reach * 0.5 * Math.sqrt(random.nextDouble());
                        double cos = Math.cos(angle);
                        double sin = Math.sin(angle);
                        double out = (0.9 + 2.6 * random.nextDouble()) * reach;

                        float life = 9.0f + random.nextFloat() * 7.0f;
                        longest = Math.max(longest, life);

                        emitter.spawn(x + cos * dist, surface + 0.05, z + sin * dist,
                                cos * out, random.nextDouble() * 0.6, sin * out,
                                life,
                                2.0f * (0.5f + random.nextFloat() * 0.7f) * Mth.sqrt(reach),
                                random.nextFloat() * TAU,
                                0.92f + random.nextFloat() * 0.08f);
                    }
                    return longest;
                });
    }

    /** Settling cinders: {@code AshParticle} as spawned by {@code ashes}. */
    public static InstancedBurst ash(Level level, double x, double y, double z, int count, float scale,
                                     RandomSource random) {
        int packed = ParticleLight.surface(level, x, y, z);
        ParticleCollision.Probe probe = LevelContactProbe.of(level);

        return new InstancedBurst(level, ASH,
                ParticleModels.translucent(WFParticleTextures.PARTICLE_BASE),
                WFParticleStyles.ash(LightTexture.block(packed), LightTexture.sky(packed)), count, x, y, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        // The burst is a small sphere about the point, as the old one was.
                        double ox = x + (random.nextDouble() - 0.5) * 0.8;
                        double oy = y + (random.nextDouble() - 0.5) * 0.8;
                        double oz = z + (random.nextDouble() - 0.5) * 0.8;

                        float life = (1200.0f + random.nextInt(20)) / 20.0f;
                        longest = Math.max(longest, life);

                        emitter.spawn(probe, ox, oy, oz,
                                (random.nextDouble() - 0.5) * 0.2 * PER_SECOND,
                                random.nextDouble() * 0.25 * PER_SECOND,
                                (random.nextDouble() - 0.5) * 0.2 * PER_SECOND,
                                life,
                                2.0f * (scale * 0.9f + random.nextFloat() * 0.2f),
                                random.nextFloat() * TAU,
                                // The flake's own grey, which the old particle set as a colour directly.
                                0.1f + random.nextFloat() * 0.1f,
                                0.0f);
                    }
                    return longest;
                });
    }

    /** Block chips off a surface: {@code BlockShrapnelParticle} as spawned by {@code spawnDebrisAndSound}. */
    public static InstancedBurst shrapnel(Level level, double x, double y, double z, int count,
                                          BlockState surface, RandomSource random) {
        int packed = ParticleLight.surface(level, x, y, z);
        ParticleCollision.Probe probe = LevelContactProbe.of(level);

        return new InstancedBurst(level, GemRenderParticleTypes.BILLBOARD,
                BlockChipModels.of(surface),
                WFParticleStyles.shrapnel(LightTexture.block(packed), LightTexture.sky(packed)),
                count, x, y, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        float life = (20.0f + random.nextInt(20)) / 20.0f;
                        longest = Math.max(longest, life);

                        emitter.spawn(probe, x, y + 0.1, z,
                                random.nextGaussian() * 0.25 * PER_SECOND,
                                (0.35 + random.nextDouble() * 0.5) * PER_SECOND,
                                random.nextGaussian() * 0.25 * PER_SECOND,
                                life,
                                2.0f * (0.1f + random.nextFloat() * 0.08f),
                                0.0f, 1.0f, 0.0f);
                    }
                    return longest;
                });
    }

    /**
     * The smoke a mining charge leaves hanging: {@code SmokePlumeParticle} as spawned by {@code MiningExplosion},
     * whose loop this is.
     */
    public static InstancedBurst plume(Level level, double x, double y, double z, int count, float radius,
                                       RandomSource random) {
        int packed = ParticleLight.surface(level, x, y, z);

        return new InstancedBurst(level, GemRenderParticleTypes.BILLBOARD,
                ParticleModels.translucent(WFParticleTextures.MIST_SOFT),
                WFParticleStyles.plume(LightTexture.block(packed), LightTexture.sky(packed)),
                count, x, y, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        // A uniformly random direction over the whole sphere, as the server loop drew it.
                        double dx = random.nextGaussian();
                        double dy = random.nextGaussian();
                        double dz = random.nextGaussian();
                        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (len < 1.0e-4) {
                            dx = 0.0;
                            dy = 1.0;
                            dz = 0.0;
                            len = 1.0;
                        }
                        double ux = dx / len;
                        double uy = dy / len;
                        double uz = dz / len;

                        double dist = random.nextDouble() * radius * 0.6;
                        double speed = 0.45 + random.nextDouble() * 0.4;
                        // Plumes flung downward had their Y heavily damped, so they slow almost at once.
                        double vy = uy < 0.0 ? uy * 0.2 : uy;

                        float life = (160.0f + random.nextInt(140)) / 20.0f;
                        longest = Math.max(longest, life);

                        double drift = 0.0667;

                        emitter.spawn(x + ux * dist, y + uy * dist, z + uz * dist,
                                (ux * speed + ux * drift) * PER_SECOND,
                                (vy * speed + uy * drift) * PER_SECOND,
                                (uz * speed + uz * drift) * PER_SECOND,
                                life,
                                2.0f * (0.1f + random.nextFloat() * 0.1f) * (3.0f + random.nextFloat() * 7.5f),
                                random.nextFloat() * TAU,
                                0.22f + random.nextFloat() * 0.22f);
                    }
                    return longest;
                });
    }

    /**
     * Flying copies of a broken block: {@code BlockDebrisParticle} as thrown by a mining charge.
     *
     * @return the burst, or {@code null} if the block has no model to throw
     */
    public static InstancedBurst blockChunks(Level level, double x, double y, double z, int count, float spread,
                                             float speed, BlockState state, RandomSource random,
                                             ParticleCollision.Probe probe) {
        JarModels.Rubble rubble = JarModels.ofBlock(state);
        if (rubble == null) {
            return null;
        }

        int style = WFParticleStyles.blockChunk();
        ParticleStyle curve = ParticleBuffer.getInstance()
                .style(style);

        return new InstancedBurst(level, CHUNK, rubble.model(), style, count, x, y, z,
                emitter -> {
                    Vector3f spawnAt = new Vector3f();
                    Vector3f spawnVelocity = new Vector3f();
                    Vector3f landsAt = new Vector3f();
                    float longest = 0.0f;

                    for (int i = 0; i < count; i++) {
                        double ox = x + random.nextGaussian() * spread;
                        double oy = y + 0.6 + random.nextGaussian() * spread * 0.5;
                        double oz = z + random.nextGaussian() * spread;

                        double vx = random.nextGaussian() * speed * 1.4 * PER_SECOND;
                        double vy = (random.nextGaussian() * speed + 0.3 + random.nextDouble() * 0.2)
                                * PER_SECOND;
                        double vz = random.nextGaussian() * speed * 1.4 * PER_SECOND;

                        float life = (32.0f + random.nextInt(34)) / 20.0f;
                        float size = 2.0f * (0.4f + random.nextFloat() * 0.12f);
                        longest = Math.max(longest, life);

                        ParticleCollision.Contact contact = emitter.spawn(probe, ox, oy, oz, vx, vy, vz, life,
                                size, random.nextFloat() * TAU, 1.0f, size * 0.5f);

                        if (curve == null || contact.life() >= life) {
                            continue;
                        }

                        ParticleCollision.positionAt(curve, spawnAt.set(0.0f),
                                spawnVelocity.set((float) vx, (float) vy, (float) vz),
                                contact, contact.life(), landsAt);

                        ShatterScheduler.shatterAt(ox + landsAt.x, oy + landsAt.y, oz + landsAt.z, state,
                                Math.round(contact.life() * 20.0f));
                    }
                    return longest;
                });
    }
}
