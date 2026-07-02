package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IExplosionSFX;
import com.wf.wfballistics.fx.ExplosionSmallCreator;
import net.minecraft.world.level.Level;

public class ExplosionEffectTiny implements IExplosionSFX {

    @Override
    public void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        if (level.isClientSide) {
            return;
        }
        ExplosionSmallCreator.composeEffect(level, x, y, z, 1, 1.5F, 0.3F);
    }
}
