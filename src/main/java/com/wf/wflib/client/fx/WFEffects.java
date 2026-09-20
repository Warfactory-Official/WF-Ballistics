package com.wf.wflib.client.fx;

import com.wf.wflib.WFSounds;
import com.wf.wflib.client.particle.*;
import com.wf.wflib.client.wiaj.Debris;
import com.wf.wflib.client.wiaj.JarModels;
import com.wf.wflib.client.wiaj.DebrisManager;
import com.wf.wflib.client.wiaj.WorldInAJar;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Client-side registry of named, multi-particle effects. */
public final class WFEffects {

    private WFEffects() {
    }

    public static void dispatch(String effect, ClientLevel level, double x, double y, double z, CompoundTag data) {
        switch (effect) {
            case "explosion_small" -> explosionSmall(level, x, y, z, data);
            case "explosion_large" -> explosionLarge(level, x, y, z, data);
            case "sonic_boom" -> sonicBoom(level, x, y, z, data);
            case "instanced_smoke" -> instancedSmoke(level, x, y, z, data);
            case "ashes" -> ashes(level, x, y, z, data);
            case "skeleton" -> skeleton(level, x, y, z, data);
            case "emp" -> emp(level, x, y, z, data);
            case "emp_stun" -> empStun(level, x, y, z, data);
            case "mining_blast" -> miningBlast(level, x, y, z, data);
            default -> { /* unknown effect id, ignore */ }
        }
    }

    /**
     * Same blast as {@link #explosionSmall}, but the smoke cloud is rendered as Flywheel GPU instances when the
     * Flywheel backend is active (one instanced draw for the whole cloud).
     */
    /** How many instanced puffs to draw for each puff the vanilla fallback would have drawn. */
    private static final int INSTANCED_SMOKE_DENSITY = 9;

    /** The same for the fireball a large blast throws up. */
    private static final int INSTANCED_CLOUD_DENSITY = 5;

    /** How far below a surface a blast still vents through it. */
    private static final int FOAM_MAX_DEPTH = 24;

    /** Foam particles per block of the blast's reach, in the column and in the surge. */
    private static final int FOAM_PLUME_PER_BLOCK = 26;
    private static final int FOAM_SURGE_PER_BLOCK = 20;

    /** How many instanced chunks of debris to throw for each one the hand-ticked fallback would have thrown. */
    private static final int INSTANCED_DEBRIS_DENSITY = 3;

    /** Distinct jars one blast cuts its debris from. */
    private static final int DEBRIS_JARS = 4;

    private static void instancedSmoke(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int count = data.getInt("count");
        float scale = data.contains("scale") ? data.getFloat("scale") : 2F;
        float speed = data.contains("speed") ? data.getFloat("speed") : 0.5F;

        if (com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level)) {
            if (!waterFoam(level, x, y, z, scale * 1.5F)) {
                com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                        new com.wf.wflib.client.flywheel.InstancedParticleEffect(
                                level, x, y, z, count * INSTANCED_SMOKE_DENSITY, scale, speed));
            }
            spawnDebrisAndSound(level, x, y, z, data); // debris + audio still via the normal path
        } else {
            explosionSmall(level, x, y, z, data);
        }
    }

    /**
     * The foam a blast throws off a water surface, if it broke one: a column of spray and the surge that spreads
     * out from its foot.
     *
     * @param reach the blast's reach in blocks, which sets how much water it moves
     * @return whether the charge itself went off under water, where there is no fireball to draw
     */
    private static boolean waterFoam(ClientLevel level, double x, double y, double z, float reach) {
        if (!com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level)) {
            return false;
        }
        double surface = WaterSurface.find(level, x, y, z);
        if (Double.isNaN(surface)) {
            return false;
        }

        double depth = surface - y;
        if (depth > FOAM_MAX_DEPTH) {
            return false;
        }

        // A deep charge vents less of itself through the surface than one at the waterline.
        float vented = reach * (float) Mth.clamp(1.0 - depth / (FOAM_MAX_DEPTH * 1.5), 0.35, 1.0);
        int plume = Math.max(24, Math.round(vented * FOAM_PLUME_PER_BLOCK));
        int surge = Math.max(20, Math.round(vented * FOAM_SURGE_PER_BLOCK));

        com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                com.wf.wflib.client.flywheel.WFBursts.foamPlume(level, x, surface, z, plume, vented,
                        level.random));
        com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                com.wf.wflib.client.flywheel.WFBursts.foamSurge(level, x, surface, z, surge, vented,
                        level.random));
        return depth > 0.2;
    }

    /**
     * A compact blast: a cluster of hot puffs plus a spray of block debris from a nearby surface.
     */
    private static void explosionSmall(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int count = data.getInt("count");
        float scale = data.contains("scale") ? data.getFloat("scale") : 2F;
        float speed = data.contains("speed") ? data.getFloat("speed") : 0.5F;

        boolean submerged = waterFoam(level, x, y, z, scale * 1.5F);

        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        if (!submerged && WFParticleSprites.explosionSmall != null) {
            for (int i = 0; i < count; i++) {
                ExplosionSmallParticle particle = new ExplosionSmallParticle(level, x, y, z, scale, speed);
                particle.pickSprite(WFParticleSprites.explosionSmall);
                engine.add(particle);
            }
        }

        spawnDebrisAndSound(level, x, y, z, data);
    }

    private static void explosionLarge(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int cloudCount = data.getInt("cloudCount");
        float cloudScale = data.contains("cloudScale") ? data.getFloat("cloudScale") : 5F;
        float cloudSpeed = data.contains("cloudSpeed") ? data.getFloat("cloudSpeed") : 1F;
        float waveScale = data.contains("waveScale") ? data.getFloat("waveScale") : 45F;
        int debrisCount = data.getInt("debrisCount");
        float debrisVelocity = data.contains("debrisVelocity") ? data.getFloat("debrisVelocity") : 1F;
        float debrisHDev = data.contains("debrisHDev") ? data.getFloat("debrisHDev") : 3F;
        float debrisVOff = data.contains("debrisVOff") ? data.getFloat("debrisVOff") : -2F;
        float soundRange = data.contains("soundRange") ? data.getFloat("soundRange") : 300F;

        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        RandomSource rand = level.random;

        if (waveScale > 0F) {
            int waveAge = Math.max(1, (int) (25F * waveScale / 45F));
            engine.add(new ShockwaveParticle(level, x, y + 2, z, waveScale, waveAge));
        }

        boolean submerged = waterFoam(level, x, y, z, (float) Mth.clamp(waveScale * 0.2, 3.0, 24.0));

        if (submerged) {
            // Nothing to draw above the water: the fireball is a bubble, and the foam is what is seen of it.
        } else if (com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level)) {
            com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                    com.wf.wflib.client.flywheel.WFBursts.cloud(level, x, y, z,
                            cloudCount * INSTANCED_CLOUD_DENSITY, cloudScale, cloudSpeed, rand));
        } else if (WFParticleSprites.rocketFlame != null) {
            for (int i = 0; i < cloudCount; i++) {
                RocketFlameParticle p = new RocketFlameParticle(level, x, y, z, cloudScale);
                p.setLifetime(70 + rand.nextInt(20));
                p.setCollision(false);
                p.setParticleSpeed(rand.nextGaussian() * 0.5 * cloudSpeed,
                        rand.nextDouble() * 3.0 * cloudSpeed,
                        rand.nextGaussian() * 0.5 * cloudSpeed);
                p.pickSprite(WFParticleSprites.rocketFlame);
                engine.add(p);
            }
        }

        int debrisSize = data.contains("debrisSize") ? data.getInt("debrisSize") : 8;
        int debrisRetry = data.contains("debrisRetry") ? data.getInt("debrisRetry") : 20;
        if (com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level)) {
            instancedDebris(level, x, y, z, debrisCount * INSTANCED_DEBRIS_DENSITY, debrisSize, debrisRetry,
                    debrisVelocity, debrisHDev, debrisVOff, rand);
        } else {
            handTickedDebris(level, x, y, z, debrisCount, debrisSize, debrisRetry, debrisVelocity, debrisHDev,
                    debrisVOff, rand);
        }

        Player player = Minecraft.getInstance().player;
        if (player != null) {
            double dist = Math.sqrt(player.distanceToSqr(x, y, z));
            if (dist <= soundRange) {
                boolean near = dist <= soundRange * 0.4;
                SoundEvent sound = (near ? WFSounds.EXPLOSION_LARGE_NEAR : WFSounds.EXPLOSION_LARGE_FAR).get();
                float pitch = 0.9F + rand.nextFloat() * 0.2F;
                ClientSoundScheduler.playDelayed(x, y, z, sound, SoundSource.BLOCKS,
                        near ? 8.0F : 16.0F, pitch, ClientSoundScheduler.soundDelay(dist));
            }
        }
    }

    /** A mining charge's smoke and flying chunks. */
    private static void miningBlast(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int radius = data.getInt("radius");
        int[] debris = data.getIntArray("debris");
        RandomSource rand = RandomSource.create(Double.doubleToLongBits(x) * 31L
                + Double.doubleToLongBits(y) * 17L + Double.doubleToLongBits(z));

        int plumes = 30 + radius * 12;
        boolean instanced = com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level);

        if (instanced) {
            com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                    com.wf.wflib.client.flywheel.WFBursts.plume(level, x, y, z, plumes, radius, rand));
        } else {
            handTickedPlume(level, x, y, z, plumes, radius, rand);
        }

        if (debris.length == 0) {
            return;
        }

        float spread = radius * 0.55F;
        int perType = Math.max(120, 110 / debris.length);

        com.wf.gemrender.particle.ParticleCollision.Probe probe = instanced
                ? com.wf.gemrender.particle.LevelContactProbe.of(level)
                : null;

        for (int id : debris) {
            BlockState state = Block.stateById(id);
            if (state.isAir()) {
                continue;
            }

            if (instanced) {
                com.wf.wflib.client.flywheel.InstancedBurst burst =
                        com.wf.wflib.client.flywheel.WFBursts.blockChunks(level, x, y, z, perType,
                                spread, 0.45F, state, rand, probe);
                if (burst != null) {
                    com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(burst);
                    continue;
                }
            }
            handTickedChunks(level, x, y, z, perType, spread, state, rand);
        }
    }

    /** The same smoke on the CPU, for when the Flywheel backend is off. */
    private static void handTickedPlume(ClientLevel level, double x, double y, double z, int count,
                                        float radius, RandomSource rand) {
        for (int i = 0; i < count; i++) {
            double dx = rand.nextGaussian();
            double dy = rand.nextGaussian();
            double dz = rand.nextGaussian();
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
            double dist = rand.nextDouble() * radius * 0.6;
            double speed = 0.45 + rand.nextDouble() * 0.4;
            double vy = uy < 0.0 ? uy * 0.2 : uy;

            level.addParticle(WFParticles.SMOKE_PLUME.get(), true,
                    x + ux * dist, y + uy * dist, z + uz * dist, ux * speed, vy * speed, uz * speed);
        }
    }

    /** The same chunks on the CPU, for when the Flywheel backend is off. */
    private static void handTickedChunks(ClientLevel level, double x, double y, double z, int count,
                                         float spread, BlockState state, RandomSource rand) {
        BlockParticleOption option = new BlockParticleOption(WFParticles.BLOCK_DEBRIS.get(), state);
        for (int i = 0; i < count; i++) {
            level.addParticle(option, true,
                    x + rand.nextGaussian() * spread,
                    y + 0.6 + rand.nextGaussian() * spread * 0.5,
                    z + rand.nextGaussian() * spread,
                    rand.nextGaussian() * 0.45, rand.nextGaussian() * 0.45, rand.nextGaussian() * 0.45);
        }
    }

    private static void sonicBoom(ClientLevel level, double x, double y, double z, CompoundTag data) {
        float waveScale = data.contains("waveScale") ? data.getFloat("waveScale") : 12F;
        int waveAge = Math.max(1, (int) (12F * waveScale / 45F));
        Minecraft.getInstance().particleEngine.add(new ShockwaveParticle(level, x, y, z, waveScale, waveAge));

        Player player = Minecraft.getInstance().player;
        if (player != null) {
            double dist = Math.sqrt(player.distanceToSqr(x, y, z));
            if (dist <= 256) {
                ClientSoundScheduler.playDelayed(x, y, z, WFSounds.SONIC_BOOM.get(), SoundSource.BLOCKS,
                        6.0F, 0.8F + level.random.nextFloat() * 0.2F, ClientSoundScheduler.soundDelay(dist));
            }
        }
    }

    /** The cremation skeleton: a biped bone pile rendered as Flywheel instances ({@link SkeletonBoneEffect}). */
    private static void skeleton(ClientLevel level, double x, double y, double z, CompoundTag data) {
        if (!com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level)) return;
        float bodyYaw = data.getFloat("bodyYaw");
        float headYaw = data.getFloat("headYaw");
        float height = data.contains("height") ? data.getFloat("height") : 1.8F;
        float brightness = data.contains("brightness") ? data.getFloat("brightness") : 0.85F;
        com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                com.wf.wflib.client.flywheel.SkeletonBoneEffect.biped(level, x, y, z, bodyYaw, headYaw, height, brightness));
    }

    /**
     * Block-shrapnel particles from a nearby surface plus the distance-picked explosion crack.
     */
    /** Chunks of ground through GemRender's particle instancer. */
    private static void instancedDebris(ClientLevel level, double x, double y, double z, int count, int size,
                                        int retry, float velocity, float hDev, float vOff, RandomSource rand) {
        int jars = Math.min(DEBRIS_JARS, count);
        if (jars <= 0) {
            return;
        }

        com.wf.gemrender.particle.ParticleCollision.Probe probe =
                com.wf.gemrender.particle.LevelContactProbe.of(level);

        for (int j = 0; j < jars; j++) {
            int share = count / jars + (j < count % jars ? 1 : 0);
            if (share <= 0) {
                continue;
            }

            int cX = (int) Math.floor(x + rand.nextGaussian() * hDev + 0.5);
            int cY = (int) Math.floor(y + vOff + 0.5);
            int cZ = (int) Math.floor(z + rand.nextGaussian() * hDev + 0.5);

            WorldInAJar jar = WorldInAJar.fromLevel(level, cX, cY, cZ, size, retry, rand);
            JarModels.Rubble rubble = JarModels.bake(jar);
            if (rubble == null) {
                continue; // the sample was all air, which happens over water and off a cliff edge
            }

            com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                    new com.wf.wflib.client.flywheel.InstancedDebrisEffect(level, x, y, z, rubble,
                            share, velocity, rand, probe));
        }
    }

    /** The same debris on the CPU, for when the Flywheel backend is off. */
    private static void handTickedDebris(ClientLevel level, double x, double y, double z, int count, int size,
                                         int retry, float velocity, float hDev, float vOff, RandomSource rand) {
        for (int i = 0; i < count; i++) {
            int cX = (int) Math.floor(x + rand.nextGaussian() * hDev + 0.5);
            int cY = (int) Math.floor(y + vOff + 0.5);
            int cZ = (int) Math.floor(z + rand.nextGaussian() * hDev + 0.5);

            double elev = Math.toRadians(45.0 + rand.nextDouble() * 25.0);
            double az = rand.nextDouble() * Math.PI * 2.0;
            double horiz = velocity * Math.cos(elev);
            double vx = horiz * Math.cos(az);
            double vy = velocity * Math.sin(elev);
            double vz = -horiz * Math.sin(az);

            WorldInAJar jar = WorldInAJar.fromLevel(level, cX, cY, cZ, size, retry, rand);
            DebrisManager.add(new Debris(rand, x, y, z, vx, vy, vz, jar));
        }
    }

    private static void spawnDebrisAndSound(ClientLevel level, double x, double y, double z, CompoundTag data) {
        BlockState surface = nearbySurface(level, x, y, z);
        if (surface != null && !surface.isAir()) {
            int debris = data.contains("debris") ? data.getInt("debris") : 15;
            if (com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level)) {
                com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                        com.wf.wflib.client.flywheel.WFBursts.shrapnel(level, x, y, z, debris, surface,
                                level.random));
            } else {
                ParticleEngine engine = Minecraft.getInstance().particleEngine;
                for (int i = 0; i < debris; i++) {
                    double vx = level.random.nextGaussian() * 0.25;
                    double vy = 0.35 + level.random.nextDouble() * 0.5;
                    double vz = level.random.nextGaussian() * 0.25;
                    engine.add(new BlockShrapnelParticle(level, x, y + 0.1, z, vx, vy, vz, surface));
                }
            }
        }

        Player player = Minecraft.getInstance().player;
        if (player != null) {
            double dist = Math.sqrt(player.distanceToSqr(x, y, z));
            if (dist <= 200) {
                boolean near = dist < 80;
                SoundEvent sound = (near ? WFSounds.EXPLOSION_SMALL_NEAR : WFSounds.EXPLOSION_SMALL_FAR).get();
                ClientSoundScheduler.playDelayed(x, y, z, sound, SoundSource.BLOCKS,
                        near ? 1.0F : 4.0F, 1.0F, ClientSoundScheduler.soundDelay(dist));
            }
        }
    }

    private static void emp(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int radius = data.contains("radius") ? data.getInt("radius") : 48;
        EMPBeams.spawnBurst(x, y, z, radius, level.random);

        for (int i = 0; i < 40; i++) {
            double a = level.random.nextDouble() * Math.PI * 2.0;
            double rr = level.random.nextDouble() * 3.0;
            level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                    x + Math.cos(a) * rr, y + (level.random.nextDouble() - 0.5) * 4.0, z + Math.sin(a) * rr,
                    Math.cos(a) * 0.3, level.random.nextGaussian() * 0.1, Math.sin(a) * 0.3);
        }
    }

    private static void empStun(ClientLevel level, double x, double y, double z, CompoundTag data) {
        EMPBeams.spawnStun(x, y, z, level.random);
        for (int i = 0; i < 6; i++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK,
                    x + (level.random.nextDouble() - 0.5) * 0.8,
                    y + (level.random.nextDouble() - 0.5) * 0.8,
                    z + (level.random.nextDouble() - 0.5) * 0.8,
                    (level.random.nextDouble() - 0.5) * 0.2,
                    level.random.nextDouble() * 0.15,
                    (level.random.nextDouble() - 0.5) * 0.2);
        }
    }

    /**
     * The cremation burst: a sphere of settling ash flakes laced with vanilla flame.
     */
    private static void ashes(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int count = data.getInt("count");
        float scale = data.contains("scale") ? data.getFloat("scale") : 0.125F;

        boolean instanced = com.wf.wflib.client.flywheel.FlywheelEffectManager.isAvailable(level);
        if (instanced) {
            com.wf.wflib.client.flywheel.FlywheelEffectManager.spawn(
                    com.wf.wflib.client.flywheel.WFBursts.ash(level, x, y, z, count, scale,
                            level.random));
        }

        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        for (int i = 0; i < count; i++) {
            double ox = x + (level.random.nextDouble() - 0.5) * 0.8;
            double oy = y + (level.random.nextDouble() - 0.5) * 0.8;
            double oz = z + (level.random.nextDouble() - 0.5) * 0.8;

            if (!instanced && WFParticleSprites.ash != null) {
                AshParticle ash = new AshParticle(level, ox, oy, oz, scale);
                ash.setParticleSpeed(
                        (level.random.nextDouble() - 0.5) * 0.2,
                        level.random.nextDouble() * 0.25,
                        (level.random.nextDouble() - 0.5) * 0.2);
                ash.pickSprite(WFParticleSprites.ash);
                engine.add(ash);
            }

            level.addParticle(ParticleTypes.FLAME, ox, oy, oz,
                    (level.random.nextDouble() - 0.5) * 0.1,
                    level.random.nextDouble() * 0.1,
                    (level.random.nextDouble() - 0.5) * 0.1);
        }
    }

    /**
     * A block near the point to texture explosion debris: first a non-air neighbour, otherwise the nearest ground
     * within a few blocks below.
     */
    private static BlockState nearbySurface(ClientLevel level, double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        for (Direction dir : Direction.values()) {
            BlockState state = level.getBlockState(base.relative(dir));
            if (isSolid(state)) return state;
        }
        BlockPos.MutableBlockPos scan = base.mutable();
        for (int i = 0; i < 8; i++) {
            scan.move(Direction.DOWN);
            BlockState state = level.getBlockState(scan);
            if (isSolid(state)) return state;
        }
        return null;
    }

    /** Chips come off something solid: a liquid is not a surface to break one off. */
    private static boolean isSolid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }
}
