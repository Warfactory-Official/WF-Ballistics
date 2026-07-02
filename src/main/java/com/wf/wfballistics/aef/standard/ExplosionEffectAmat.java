package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IExplosionSFX;
import com.wf.wfballistics.fx.ExplosionCreator;
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
