package com.wf.wfballistics.fx;

import com.wf.wfballistics.network.AuxParticlePacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;


public final class ExplosionSmallCreator {

    private static final int DEBRIS = 15;

    private ExplosionSmallCreator() { }

    /**
     * @param cloudCount     number of fireball puffs
     * @param cloudScale     puff size
     * @param cloudSpeedMult puff velocity multiplier
     */
    public static void composeEffect(Level level, double x, double y, double z,
                                     int cloudCount, float cloudScale, float cloudSpeedMult) {
        if (level.isClientSide) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt("count", cloudCount);
        data.putFloat("scale", cloudScale);
        data.putFloat("speed", cloudSpeedMult);
        data.putInt("debris", DEBRIS);

        WFNetwork.sendToAllAround(level, x, y, z, 200.0, new AuxParticlePacket("explosion_small", x, y, z, data));
    }
}
