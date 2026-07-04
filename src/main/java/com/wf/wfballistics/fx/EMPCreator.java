package com.wf.wfballistics.fx;

import com.wf.wfballistics.network.AuxParticlePacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

public final class EMPCreator {

    private EMPCreator() {
    }

    public static void compose(Level level, double x, double y, double z, int radius) {
        if (level.isClientSide) {
            return;
        }
        CompoundTag data = new CompoundTag();
        data.putInt("radius", radius);

        double range = Math.max(128.0, radius * 2.0);
        WFNetwork.sendToAllAround(level, x, y, z, range, new AuxParticlePacket("emp", x, y, z, data));
    }

    public static void composeStun(Level level, double x, double y, double z) {
        if (level.isClientSide) {
            return;
        }
        WFNetwork.sendToAllAround(level, x, y, z, 64.0, new AuxParticlePacket("emp_stun", x, y, z, new CompoundTag()));
    }
}
