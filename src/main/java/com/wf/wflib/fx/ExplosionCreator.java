package com.wf.wflib.fx;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.Blasts;
import com.wf.wflib.audio.WfAudio;
import com.wf.wflib.network.AuxParticlePacket;
import com.wf.wflib.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;


public final class ExplosionCreator {

    private ExplosionCreator() {
    }

    /**
     * @param cloudCount                number of smoke-plume puffs
     * @param cloudScale                puff size
     * @param cloudSpeedMult            puff velocity multiplier
     * @param waveScale                 shockwave ring radius (blocks)
     * @param debrisCount               debris chunk cap (x3 client side); chunks = removed blocks only
     * @param debrisVelocity            debris launch speed (blocks/tick)
     * @param soundRange                near layer reach ({@link Blasts#large}); FX broadcast at least 300
     */
    public static void composeEffect(Level level, double x, double y, double z,
                                     int cloudCount, float cloudScale, float cloudSpeedMult, float waveScale,
                                     int debrisCount, float debrisVelocity, float soundRange) {
        if (level.isClientSide) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt("cloudCount", cloudCount);
        data.putFloat("cloudScale", cloudScale);
        data.putFloat("cloudSpeed", cloudSpeedMult);
        data.putFloat("waveScale", waveScale);
        data.putInt("debrisCount", debrisCount);
        data.putFloat("debrisVelocity", debrisVelocity);
        data.putFloat("soundRange", soundRange);

        double range = Math.max(300.0, soundRange);
        WFNetwork.sendToAllAround(level, x, y, z, range, new AuxParticlePacket("explosion_large", x, y, z, data));
        WfAudio.at((ServerLevel) level, new Vec3(x, y, z), AudioCategory.EXPLOSIONS, Blasts.large(soundRange));
    }

    /**
     * @param waveScale ring radius (blocks); scale it with speed for a bigger crack at higher Mach
     */
    public static void sonicBoom(Level level, double x, double y, double z, float waveScale) {
        if (level.isClientSide) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putFloat("waveScale", waveScale);
        WFNetwork.sendToAllAround(level, x, y, z, 256.0, new AuxParticlePacket("sonic_boom", x, y, z, data));
        WfAudio.at((ServerLevel) level, new Vec3(x, y, z), AudioCategory.EXPLOSIONS, Blasts.sonicBoom());
    }

    /**
     * Compact large-explosion preset (HBM {@code composeEffectSmall}).
     */
    public static void composeEffectSmall(Level level, double x, double y, double z) {
        composeEffect(level, x, y, z, 10, 2F, 0.5F, 25F, 5, 0.75F, 150F);
    }

    /**
     * Standard large-explosion preset (HBM {@code composeEffectStandard}).
     */
    public static void composeEffectStandard(Level level, double x, double y, double z) {
        composeEffect(level, x, y, z, 15, 5F, 1F, 45F, 10, 1F, 200F);
    }

    /**
     * Big large-explosion preset (HBM {@code composeEffectLarge}).
     */
    public static void composeEffectLarge(Level level, double x, double y, double z) {
        composeEffect(level, x, y, z, 30, 6.5F, 2F, 65F, 25, 1.25F, 350F);
    }
}
