package com.wf.wfballistics.fx;

import com.wf.wfballistics.network.AuxParticlePacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;


public final class ExplosionCreator {

    private ExplosionCreator() {
    }

    /**
     * @param cloudCount                number of smoke-plume puffs
     * @param cloudScale                puff size
     * @param cloudSpeedMult            puff velocity multiplier
     * @param waveScale                 shockwave ring radius (blocks)
     * @param debrisCount               number of flung debris chunks
     * @param debrisSize                debris chunk size (carried in NBT for a future voxel-chunk renderer)
     * @param debrisRetry               debris sampling retries (ditto)
     * @param debrisVelocity            debris launch speed (blocks/tick)
     * @param debrisHorizontalDeviation debris spawn scatter around the center
     * @param debrisVerticalOffset      debris spawn Y offset relative to the blast
     * @param soundRange                max distance the boom is heard; broadcast range is at least 300
     */
    public static void composeEffect(Level level, double x, double y, double z,
                                     int cloudCount, float cloudScale, float cloudSpeedMult, float waveScale,
                                     int debrisCount, int debrisSize, int debrisRetry, float debrisVelocity,
                                     float debrisHorizontalDeviation, float debrisVerticalOffset, float soundRange) {
        if (level.isClientSide) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt("cloudCount", cloudCount);
        data.putFloat("cloudScale", cloudScale);
        data.putFloat("cloudSpeed", cloudSpeedMult);
        data.putFloat("waveScale", waveScale);
        data.putInt("debrisCount", debrisCount);
        data.putInt("debrisSize", debrisSize);   // carried for a future voxel-chunk debris renderer
        data.putInt("debrisRetry", debrisRetry); // ditto
        data.putFloat("debrisVelocity", debrisVelocity);
        data.putFloat("debrisHDev", debrisHorizontalDeviation);
        data.putFloat("debrisVOff", debrisVerticalOffset);
        data.putFloat("soundRange", soundRange);

        double range = Math.max(300.0, soundRange);
        WFNetwork.sendToAllAround(level, x, y, z, range, new AuxParticlePacket("explosion_large", x, y, z, data));
    }

    /**
     * Compact large-explosion preset (HBM {@code composeEffectSmall}).
     */
    public static void composeEffectSmall(Level level, double x, double y, double z) {
        composeEffect(level, x, y, z, 10, 2F, 0.5F, 25F, 5, 8, 20, 0.75F, 1F, -2F, 150F);
    }

    /**
     * Standard large-explosion preset (HBM {@code composeEffectStandard}).
     */
    public static void composeEffectStandard(Level level, double x, double y, double z) {
        composeEffect(level, x, y, z, 15, 5F, 1F, 45F, 10, 16, 50, 1F, 3F, -2F, 200F);
    }

    /**
     * Big large-explosion preset (HBM {@code composeEffectLarge}).
     */
    public static void composeEffectLarge(Level level, double x, double y, double z) {
        composeEffect(level, x, y, z, 30, 6.5F, 2F, 65F, 25, 16, 50, 1.25F, 3F, -2F, 350F);
    }
}
