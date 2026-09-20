package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.world.level.Level;

/** Final stage of the explosion pipeline: sound and particles. */
public interface IExplosionSFX {

    void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size);
}
