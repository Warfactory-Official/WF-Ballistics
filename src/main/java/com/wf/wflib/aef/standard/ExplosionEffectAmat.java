package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.interfaces.IExplosionSFX;
import com.wf.wflib.fx.ExplosionCreator;
import net.minecraft.world.level.Level;


public class ExplosionEffectAmat implements IExplosionSFX {

    @Override
    public void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        if (level.isClientSide) {
            return;
        }
        ExplosionCreator.composeEffectLarge(level, x, y, z);
    }
}
