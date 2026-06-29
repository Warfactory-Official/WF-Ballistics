package com.wf.wfballistics.client.fx;

import com.wf.wfballistics.WFSounds;
import com.wf.wfballistics.client.particle.AshParticle;
import com.wf.wfballistics.client.particle.ExplosionSmallParticle;
import com.wf.wfballistics.client.particle.WFParticleSprites;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client-side registry of named, multi-particle effects. {@link com.wf.wfballistics.network.AuxParticlePacket}
 * carries an effect key + an NBT parameter blob; {@link #dispatch} fans that out into the actual particles.
 * Adding a new composite effect is one {@code case} here plus a server-side packet send — no new packet
 * class, mirroring HBM's {@code HbmEffectNT} dispatcher.
 */
public final class WFEffects {

    private WFEffects() { }

    public static void dispatch(String effect, ClientLevel level, double x, double y, double z, CompoundTag data) {
        switch (effect) {
            case "explosion_small" -> explosionSmall(level, x, y, z, data);
            case "instanced_smoke" -> instancedSmoke(level, x, y, z, data);
            case "ashes" -> ashes(level, x, y, z, data);
            case "skeleton" -> skeleton(level, x, y, z, data);
            default -> { /* unknown effect id — ignore */ }
        }
    }

    /**
     * Same blast as {@link #explosionSmall}, but the smoke cloud is rendered as Flywheel GPU instances when
     * the Flywheel backend is active (one instanced draw for the whole cloud). Falls back to vanilla
     * particles when it isn't, so the effect always shows.
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

    /** A compact blast: a cluster of hot puffs plus a spray of block debris from a nearby surface. */
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

    /** Block-shrapnel particles from a nearby surface plus the distance-picked explosion crack. */
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

        // Distinct "near" (punchy) vs "far" (rumble) recording, chosen client-side by listener distance.
        Player player = Minecraft.getInstance().player;
        if (player != null) {
            boolean near = player.distanceToSqr(x, y, z) < 80 * 80;
            SoundEvent sound = (near ? WFSounds.EXPLOSION_SMALL_NEAR : WFSounds.EXPLOSION_SMALL_FAR).get();
            level.playLocalSound(x, y, z, sound, SoundSource.BLOCKS, near ? 1.0F : 4.0F, 1.0F, false);
        }
    }

    /** The cremation burst: a sphere of settling ash flakes laced with vanilla flame. */
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

    /** First non-air block touching the point, used to texture explosion debris. */
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
