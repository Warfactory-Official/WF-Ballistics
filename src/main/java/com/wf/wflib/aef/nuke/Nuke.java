package com.wf.wflib.aef.nuke;

import com.wf.wflib.entity.EntityNukeExplosionMK5;
import com.wf.wflib.entity.EntityNukeTorex;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class Nuke {

    private Nuke() {
    }

    /** {@code radius}: not the literal block radius; also scales speed + cloud size. */
    public static void detonate(Level level, Vec3 center, int radius) {
        if (level.isClientSide) return;
        level.addFreshEntity(EntityNukeExplosionMK5.statFac(level, radius, center));
        level.addFreshEntity(new EntityNukeTorex(level, center.add(0.0D, 4.5D, 0.0D), radius));
    }
}
