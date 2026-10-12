package com.wf.wflib.aef.standard;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.Blasts;
import com.wf.wflib.audio.WfAudio;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.interfaces.IExplosionSFX;
import com.wf.wflib.network.ExplosionBlockFXPacket;
import com.wf.wflib.network.WFNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.Level;

import java.util.List;

/** Default explosion FX: bang, central puff, debris radiating from each broken block. */
public class ExplosionEffectStandard implements IExplosionSFX {

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

    @Override
    public void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        if (level.isClientSide) return;

        WfAudio.at((ServerLevel) level, new Vec3(x, y, z), AudioCategory.EXPLOSIONS, Blasts.small());

        WFNetwork.sendToTracking(level, x, z,
                new ExplosionBlockFXPacket(x, y, z, explosion.size, explosion.compat.getToBlow()));
    }
}
