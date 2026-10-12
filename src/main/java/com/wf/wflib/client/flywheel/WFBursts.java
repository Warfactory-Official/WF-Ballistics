package com.wf.wflib.client.flywheel;

import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.LevelContactProbe;
import com.wf.gemrender.particle.ParticleCollision;
import com.wf.gemrender.particle.ParticleInstance;
import com.wf.gemrender.particle.ParticleLook;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.Direction;
import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticleQuad;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import com.wf.gemrender.particle.ParticleBuffer;
import com.wf.gemrender.particle.ParticleStyle;
import com.wf.wflib.client.fx.ParticleLight;
import com.wf.wflib.client.fx.ShatterScheduler;
import com.wf.wflib.client.wiaj.JarModels;
import org.joml.Vector3f;
import dev.engine_room.flywheel.api.instance.InstanceType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class WFBursts {

    private static final float TAU = Mth.TWO_PI;

    /** Ticks/sec: converts legacy per-tick velocities to per-second. */
    private static final double PER_SECOND = 20.0;

    private static final InstanceType<ParticleInstance> ASH = GemRenderParticleTypes.custom(
            WFParticleTextures.ASH_VERTEX, WFParticleTextures.BILLBOARD_CULL);

    private static final InstanceType<ParticleInstance> CHUNK = GemRenderParticleTypes.custom(
            WFParticleTextures.DEBRIS_VERTEX, WFParticleTextures.MESH_CULL);

    public static final InstanceType<ParticleInstance> SMOKE = GemRenderParticleTypes.custom(
            WFParticleTextures.SMOKE_VERTEX, WFParticleTextures.BILLBOARD_CULL);

    /** {@link ParticleModels#absorbance} without texture filtering: 8x8 flake keeps its pixels. */
    private static final Model ASH_MODEL = new SingleMeshModel(ParticleQuad.INSTANCE, SimpleMaterial.builder()
            .texture(WFParticleTextures.PARTICLE_BASE)
            .transparency(Transparency.ORDER_INDEPENDENT)
            .cutout(CutoutShaders.OFF)
            .shaders(ParticleModels.ABSORBANCE_SHADERS)
            .writeMask(WriteMask.COLOR)
            .useOverlay(false)
            .fog(FogShaders.NONE)
            .blur(false)
            .backfaceCulling(false)
            .cardinalLightingMode(CardinalLightingMode.OFF)
            .mipmap(false)
            .build());

    private static final InstanceType<ParticleInstance> FOAM = GemRenderParticleTypes.custom(
            WFParticleTextures.FOAM_VERTEX, WFParticleTextures.FOAM_CULL);

    private WFBursts() {
    }

    /** {@code speed} blocks/tick, {@code width} blocks wide. */
    public static InstancedBurst bloodSpray(Level level, double x, double y, double z, float width, float speed,
                                            RandomSource random) {
        int count = Mth.clamp((int) (width * 30 + speed * 25), 16, 90);
        ParticleCollision.Probe probe = LevelContactProbe.of(level);
        int look = level instanceof ClientLevel client
                ? ParticleLook.lit(client, x, y + 0.5, z,
                ParticleLook.WHITE)
                : ParticleLook.WHITE;
        return new InstancedBurst(level, GemRenderParticleTypes.BILLBOARD,
                ParticleModels.translucent(WFParticleTextures.BLOOD_DROP), WFParticleStyles.bloodDrop(), count,
                x, y, z, emitter -> {
                    float longest = 0.0f;
                    double up = 2.5 + speed * 6.0;
                    for (int i = 0; i < count; i++) {
                        float life = 4.0f + random.nextFloat() * 3.0f;
                        longest = Math.max(longest, life);
                        double a = random.nextDouble() * TAU;
                        double out = (1.0 + random.nextDouble() * 3.0) * (0.6 + speed * 1.5);
                        emitter.spawn(probe, x + Math.cos(a) * width * 0.4, y + 0.2, z + Math.sin(a) * width * 0.4,
                                Math.cos(a) * out, up * (0.3 + random.nextDouble()), Math.sin(a) * out,
                                life, 0.08f + random.nextFloat() * 0.14f, 0.0f, 1.0f, 0.0f, look);
                    }
                    return longest;
                });
    }

    /** Skips a splat where the raycast finds no up-facing surface within 3 blocks below. */
    public static InstancedBurst bloodPool(Level level, double x, double y, double z, float width,
                                           RandomSource random) {
        int count = 1 + Mth.clamp((int) (width * 4), 2, 6);
        return new InstancedBurst(level, GemRenderParticleTypes.DECAL,
                ParticleModels.decal(WFParticleTextures.BLOOD_SPLAT), WFParticleStyles.bloodDecal(), count, x, y, z,
                emitter -> {
                    float life = 90.0f;
                    for (int i = 0; i < count; i++) {
                        double r = i == 0 ? 0.0 : width * (0.5 + random.nextDouble() * 1.2);
                        double a = random.nextDouble() * TAU;
                        double px = x + Math.cos(a) * r, pz = z + Math.sin(a) * r;
                        BlockHitResult hit = level.clip(new ClipContext(
                                new Vec3(px, y + 1.0, pz),
                                new Vec3(px, y - 3.0, pz),
                                ClipContext.Block.COLLIDER,
                                ClipContext.Fluid.NONE,
                                CollisionContext.empty()));
                        if (hit.getType() == HitResult.Type.MISS
                                || hit.getDirection() != Direction.UP) {
                            continue;
                        }
                        float size = i == 0 ? Mth.clamp(width * 2.2f, 1.0f, 3.5f) : 0.3f + random.nextFloat() * 0.6f;
                        int look = level instanceof ClientLevel client
                                ? ParticleLook.lit(client, px, hit.getLocation().y + 0.5, pz,
                                ParticleLook.WHITE)
                                : ParticleLook.WHITE;
                        emitter.spawnDecal(px, hit.getLocation().y, pz, 0.0, 1.0, 0.0, life, size,
                                random.nextFloat() * TAU, look);
                    }
                    return life;
                });
    }

    /** The fireball an explosion throws up: {@code RocketFlameParticle} as spawned by {@code explosionLarge}; {@code vent}: +y -> open side. */
    public static InstancedBurst cloud(Level level, double x, double y, double z, int count, float scale,
                                       float speed, org.joml.Quaternionf vent, RandomSource random) {
        return new InstancedBurst(level, SMOKE,
                ParticleModels.glowing(WFParticleTextures.SMOKE),
                WFParticleStyles.cloud(), count, x, y, z,
                emitter -> {
                    float longest = 0.0f;
                    Vector3f v = new Vector3f();
                    for (int i = 0; i < count; i++) {
                        float life = (220.0f + random.nextInt(80)) / 20.0f;
                        longest = Math.max(longest, life);

                        vent.transform(v.set(random.nextGaussian() * 0.5, random.nextDouble() * 3.0,
                                random.nextGaussian() * 0.5).mul((float) (speed * PER_SECOND)));
                        emitter.spawn(x, y, z, v.x, v.y, v.z,
                                life, 2.0f * scale * (0.75f + random.nextFloat() * 0.5f),
                                random.nextFloat() * TAU, 0.85f + random.nextFloat() * 0.3f);
                    }
                    return longest;
                });
    }

    /** Centre rises fastest, rim goes outward: plume, not sphere. {@code radius} blocks. */
    public static InstancedBurst foamPlume(Level level, double x, double surface, double z, int count,
                                           float radius, RandomSource random) {
        int packed = ParticleLight.surface(level, x, surface + 1.0, z);
        float reach = Math.max(1.0f, radius);
        float mouth = Math.max(0.6f, reach * 0.35f);
        // Peak height picked first, launch speed derived from it => scales with blast size, not off-screen.
        double fastest = Math.sqrt(2.0 * WFParticleStyles.SPRAY_GRAVITY * reach * 2.6);

        return new InstancedBurst(level, FOAM,
                ParticleModels.absorbance(WFParticleTextures.FOAM),
                WFParticleStyles.foamPlume(LightTexture.block(packed), LightTexture.sky(packed)),
                count, x, surface, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        double angle = random.nextDouble() * TAU;
                        // Biased inward: fills the column, not its collar.
                        double dist = mouth * Math.pow(random.nextDouble(), 1.5);
                        double cos = Math.cos(angle);
                        double sin = Math.sin(angle);
                        float core = (float) (1.0 - dist / mouth);

                        double up = fastest * (0.32 + 0.68 * core);
                        double out = (0.5 + 1.8 * (1.0 - core)) * reach;

                        // Life = ballistic time of flight: dies at the water, not mid-air or hanging after landing.
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
                ParticleModels.absorbance(WFParticleTextures.FOAM),
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

        return new InstancedBurst(level, ASH, ASH_MODEL,
                WFParticleStyles.ash(LightTexture.block(packed), LightTexture.sky(packed)), count, x, y, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
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
                                // tintScale on white base => grey, not a hue
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

    /** {@code SmokePlumeParticle}'s loop, as spawned by {@code MiningExplosion}. */
    public static InstancedBurst plume(Level level, double x, double y, double z, int count, float radius,
                                       RandomSource random) {
        int packed = ParticleLight.surface(level, x, y, z);

        return new InstancedBurst(level, SMOKE,
                ParticleModels.absorbance(WFParticleTextures.SMOKE),
                WFParticleStyles.plume(LightTexture.block(packed), LightTexture.sky(packed)),
                count, x, y, z,
                emitter -> {
                    float longest = 0.0f;
                    for (int i = 0; i < count; i++) {
                        // Matches upstream's direction draw.
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
                        // Matches upstream's downward-only damping.
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

    /** {@code BlockDebrisParticle}, thrown by a mining charge; null if the block has no model to throw. */
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
