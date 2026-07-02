package com.wf.wfballistics.fire;

import com.wf.wfballistics.WFSounds;
import com.wf.wfballistics.network.AuxParticlePacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;

/**
 * Turns things that die by fire into a pile of ash. When a burning entity is killed, it bursts into a cloud
 * of settling ash particles (the {@code "ashes"} effect in {@link com.wf.wfballistics.client.fx.WFEffects})
 * with a disintegration sound, instead of just keeling over.
 *
 * <p>The entity still dies and drops loot normally — this is purely the death visual. Triggered from
 * {@link FireHandler#death} for any fire death, and from the explosion damage path for charges that kill
 * while their target is alight.
 */
public final class AshHandler {

    private AshHandler() {
    }

    /**
     * Cremates the entity if it was killed by fire (custom or vanilla) or was burning when it died.
     */
    public static void decideGore(LivingEntity entity, DamageSource source) {
        if (source.is(DamageTypeTags.IS_FIRE) || entity.isOnFire()) {
            cremate(entity);
        }
    }

    /**
     * Spawns the ash burst + sound. The particle count scales with the entity's volume.
     */
    public static void cremate(LivingEntity entity) {
        Level level = entity.level();
        if (level.isClientSide) return;

        float width = entity.getBbWidth();
        float height = entity.getBbHeight();
        int count = Mth.clamp((int) (width * width * height * 25), 5, 50);

        CompoundTag data = new CompoundTag();
        data.putInt("count", count);
        data.putFloat("scale", 0.125F);

        WFNetwork.sendToTracking(level, entity.getX(), entity.getZ(),
                new AuxParticlePacket("ashes", entity.getX(), entity.getY() + height * 0.5, entity.getZ(), data));

        // Humanoid-ish things also collapse into a Flywheel-instanced skeleton (see SkeletonBoneEffect).
        if (height >= 0.9F && height <= 3.0F && width <= 1.2F) {
            CompoundTag bones = new CompoundTag();
            bones.putFloat("bodyYaw", entity.yBodyRot);
            bones.putFloat("headYaw", entity.getYHeadRot());
            bones.putFloat("height", height);
            bones.putFloat("brightness", 0.85F);
            WFNetwork.sendToTracking(level, entity.getX(), entity.getZ(),
                    new AuxParticlePacket("skeleton", entity.getX(), entity.getY(), entity.getZ(), bones));
        }

        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                WFSounds.FIRE_DISINTEGRATION.get(), SoundSource.PLAYERS,
                2.0F, 0.9F + level.random.nextFloat() * 0.2F);
    }
}
