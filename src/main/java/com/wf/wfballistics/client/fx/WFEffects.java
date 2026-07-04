package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFSounds;
import com.wf.wfballistics.client.particle.*;
import com.wf.wfballistics.client.wiaj.Debris;
import com.wf.wfballistics.client.wiaj.DebrisManager;
import com.wf.wfballistics.client.wiaj.WorldInAJar;
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
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-side registry of named, multi-particle effects. {@link com.wf.wfballistics.network.AuxParticlePacket}
 * carries an effect key + an NBT parameter blob; {@link #dispatch} fans that out into the actual particles.
 * Adding a new composite effect is one {@code case} here plus a server-side packet send — no new packet
 * class, mirroring HBM's {@code HbmEffectNT} dispatcher.
 */
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
            default -> { /* unknown effect id, ignore */ }
        }
    }

    /**
     * Same blast as {@link #explosionSmall}, but the smoke cloud is rendered as Flywheel GPU instances when
     * the Flywheel backend is active (one instanced draw for the whole cloud). Falls back to vanilla
     * particles when it isn't.
     */
    private static void instancedSmoke(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int count = data.getInt("count");
        float scale = data.contains("scale") ? data.getFloat("scale") : 2F;
        float speed = data.contains("speed") ? data.getFloat("speed") : 0.5F;

        if (com.wf.wfballistics.client.flywheel.FlywheelEffectManager.isAvailable(level)) {
            com.wf.wfballistics.client.flywheel.FlywheelEffectManager.spawn(
                    new com.wf.wfballistics.client.flywheel.InstancedParticleEffect(level, x, y, z, count, scale, speed));
            spawnDebrisAndSound(level, x, y, z, data); // debris + audio still via the normal path
        } else {
            explosionSmall(level, x, y, z, data);
        }
    }

    /**
     * A compact blast: a cluster of hot puffs plus a spray of block debris from a nearby surface.
     */
    private static void explosionSmall(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int count = data.getInt("count");
        float scale = data.contains("scale") ? data.getFloat("scale") : 2F;
        float speed = data.contains("speed") ? data.getFloat("speed") : 0.5F;

        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        if (WFParticleSprites.explosionSmall != null) {
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

        if (WFParticleSprites.rocketFlame != null) {
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
        for (int i = 0; i < debrisCount; i++) {
            int cX = (int) Math.floor(x + rand.nextGaussian() * debrisHDev + 0.5);
            int cY = (int) Math.floor(y + debrisVOff + 0.5);
            int cZ = (int) Math.floor(z + rand.nextGaussian() * debrisHDev + 0.5);

            double elev = Math.toRadians(45.0 + rand.nextDouble() * 25.0);
            double az = rand.nextDouble() * Math.PI * 2.0;
            double horiz = debrisVelocity * Math.cos(elev);
            double vx = horiz * Math.cos(az);
            double vy = debrisVelocity * Math.sin(elev);
            double vz = -horiz * Math.sin(az);

            WorldInAJar jar = WorldInAJar.fromLevel(level, cX, cY, cZ, debrisSize, debrisRetry, rand);
            DebrisManager.add(new Debris(rand, x, y, z, vx, vy, vz, jar));
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

    /**
     * The cremation skeleton: a biped bone pile rendered as Flywheel instances ({@link SkeletonBoneEffect}).
     * Instanced-only — if the Flywheel backend is off there is no bone render (the ash burst still plays).
     */
    private static void skeleton(ClientLevel level, double x, double y, double z, CompoundTag data) {
        if (!com.wf.wfballistics.client.flywheel.FlywheelEffectManager.isAvailable(level)) return;
        float bodyYaw = data.getFloat("bodyYaw");
        float headYaw = data.getFloat("headYaw");
        float height = data.contains("height") ? data.getFloat("height") : 1.8F;
        float brightness = data.contains("brightness") ? data.getFloat("brightness") : 0.85F;
        com.wf.wfballistics.client.flywheel.FlywheelEffectManager.spawn(
                com.wf.wfballistics.client.flywheel.SkeletonBoneEffect.biped(level, x, y, z, bodyYaw, headYaw, height, brightness));
    }

    /**
     * Block-shrapnel particles from a nearby surface plus the distance-picked explosion crack.
     */
    private static void spawnDebrisAndSound(ClientLevel level, double x, double y, double z, CompoundTag data) {
        BlockState surface = adjacentSurface(level, x, y, z);
        if (surface != null) {
            int debris = data.contains("debris") ? data.getInt("debris") : 15;
            for (int i = 0; i < debris; i++) {
                level.addParticle(new BlockParticleOption(ParticleTypes.BLOCK, surface),
                        x, y + 0.1, z,
                        level.random.nextGaussian() * 0.2,
                        0.5 + level.random.nextDouble() * 0.7,
                        level.random.nextGaussian() * 0.2);
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

    /**
     * The cremation burst: a sphere of settling ash flakes laced with vanilla flame.
     */
    private static void ashes(ClientLevel level, double x, double y, double z, CompoundTag data) {
        int count = data.getInt("count");
        float scale = data.contains("scale") ? data.getFloat("scale") : 0.125F;

        ParticleEngine engine = Minecraft.getInstance().particleEngine;
        for (int i = 0; i < count; i++) {
            double ox = x + (level.random.nextDouble() - 0.5) * 0.8;
            double oy = y + (level.random.nextDouble() - 0.5) * 0.8;
            double oz = z + (level.random.nextDouble() - 0.5) * 0.8;

            if (WFParticleSprites.ash != null) {
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
     * First non-air block touching the point, used to texture explosion debris.
     */
    private static BlockState adjacentSurface(ClientLevel level, double x, double y, double z) {
        BlockPos base = BlockPos.containing(x, y, z);
        for (Direction dir : Direction.values()) {
            BlockPos pos = base.relative(dir);
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) return state;
        }
        return null;
    }
}
