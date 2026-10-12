package com.wf.wflib.fx;

import com.wf.wflib.network.AuxParticlePacket;
import com.wf.wflib.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Mob killed by a blast: thrown along its knockback, death removal held while airborne (<= {@link #MAX_AIR_TICKS}),
 * landing => client {@code splatter} effect + corpse removed. Players, NoAI mobs (never move), burning corpses
 * (cremated): not thrown.
 */
public final class BlastCorpses {
    static final int MAX_AIR_TICKS = 100;
    /** Launch, blocks/tick: {@code SPEED + SPEED_PER_KNOCKBACK * k} along the push, + {@code LIFT + LIFT_PER * k} up. */
    static final double SPEED = 0.5, SPEED_PER_KNOCKBACK = 1.6, LIFT = 0.45, LIFT_PER_KNOCKBACK = 0.6;

    /** Corpse -> ticks airborne; server thread. */
    private static final Map<LivingEntity, int[]> FLYING = new WeakHashMap<>();
    /** Harness. */
    public static int launched, splattered;
    public static double splatX, splatY, splatZ;

    private BlastCorpses() {
    }

    /** @param push unit direction away from the blast x knockback (0..1) */
    public static void launch(LivingEntity corpse, Vec3 push) {
        if (corpse instanceof Player || corpse instanceof Mob mob && mob.isNoAi() || corpse.isOnFire()
                || corpse.level().isClientSide) {
            return;
        }
        double k = Math.min(1.0, push.length());
        Vec3 dir = k < 1.0e-6 ? Vec3.ZERO : push.scale(1.0 / push.length());
        corpse.setDeltaMovement(dir.scale(SPEED + SPEED_PER_KNOCKBACK * k)
                .add(0.0, LIFT + LIFT_PER_KNOCKBACK * k, 0.0));
        corpse.hasImpulse = true;
        corpse.hurtMarked = true;
        FLYING.put(corpse, new int[1]);
        launched++;
    }

    /** {@code LivingEntity.tickDeath} head, server: true => skip it this tick. */
    public static boolean holdDeath(LivingEntity corpse) {
        int[] air = FLYING.get(corpse);
        if (air == null) {
            return false;
        }
        air[0]++;
        if (air[0] > 2 && (corpse.onGround() || corpse.isInWater())) {
            FLYING.remove(corpse);
            splatter(corpse);
            splattered++;
            splatX = corpse.getX();
            splatY = corpse.getY();
            splatZ = corpse.getZ();
            corpse.remove(Entity.RemovalReason.KILLED);
            return true;
        }
        if (air[0] > MAX_AIR_TICKS) {
            FLYING.remove(corpse);
            return false;
        }
        return true;
    }

    private static void splatter(LivingEntity corpse) {
        CompoundTag data = new CompoundTag();
        data.putFloat("width", corpse.getBbWidth());
        data.putFloat("height", corpse.getBbHeight());
        data.putFloat("speed", (float) corpse.getDeltaMovement().length());
        data.putBoolean("water", corpse.isInWater());
        WFNetwork.sendToTracking(corpse.level(), corpse.getX(), corpse.getZ(),
                new AuxParticlePacket("splatter", corpse.getX(), corpse.getY(), corpse.getZ(), data));
        corpse.level().playSound(null, corpse.getX(), corpse.getY(), corpse.getZ(), SoundEvents.SLIME_BLOCK_BREAK,
                SoundSource.HOSTILE, 1.2F, 0.55F + corpse.getRandom().nextFloat() * 0.15F);
    }
}
