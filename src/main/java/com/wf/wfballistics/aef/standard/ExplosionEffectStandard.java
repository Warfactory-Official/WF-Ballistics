package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IExplosionSFX;
import com.wf.wfballistics.network.ExplosionBlockFXPacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * The default explosion presentation: the bang, a central explosion puff, and a burst of smoke + debris
 * radiating from every block that was broken. This is a faithful reproduction of vanilla's explosion
 * particles, just driven from the framework's affected-block set instead of vanilla's.
 *
 * <p>The server half ({@link #doEffect}) plays the sound and broadcasts the block set; the client half
 * ({@link #performClient}) is invoked from the packet handler and does the actual particle spawning.
 */
public class ExplosionEffectStandard implements IExplosionSFX {

    @Override
    public void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        if (level.isClientSide) return;

        float pitch = (1.0F + (level.random.nextFloat() - level.random.nextFloat()) * 0.2F) * 0.7F;
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, 4.0F, pitch);

        WFNetwork.sendToTracking(level, x, z,
                new ExplosionBlockFXPacket(x, y, z, explosion.size, explosion.compat.getToBlow()));
    }

    /**
     * Spawns the explosion particles on the client. Each broken block emits a small explosion puff and a
     * puff of smoke, kicked outward from the epicentre with a speed that falls off with distance — the
     * classic "shrapnel" look.
     */
    public static void performClient(Level level, double x, double y, double z, float size, List<BlockPos> affectedBlocks) {
        if (size >= 2.0F) {
            level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1.0D, 0.0D, 0.0D);
        } else {
            level.addParticle(ParticleTypes.EXPLOSION, x, y, z, 1.0D, 0.0D, 0.0D);
        }

        for (BlockPos pos : affectedBlocks) {
            double ox = pos.getX() + level.random.nextFloat();
            double oy = pos.getY() + level.random.nextFloat();
            double oz = pos.getZ() + level.random.nextFloat();
            double dx = ox - x;
            double dy = oy - y;
            double dz = oz - z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist == 0) continue;
            dx /= dist;
            dy /= dist;
            dz /= dist;

            double speed = 0.5D / (dist / size + 0.1D);
            speed *= level.random.nextFloat() * level.random.nextFloat() + 0.3F;
            dx *= speed;
            dy *= speed;
            dz *= speed;

            level.addParticle(ParticleTypes.POOF, (ox + x) / 2.0D, (oy + y) / 2.0D, (oz + z) / 2.0D, dx, dy, dz);
            level.addParticle(ParticleTypes.SMOKE, ox, oy, oz, dx, dy, dz);
        }
    }
}
