package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.interfaces.IExplosionSFX;
import com.wf.wflib.fx.ExplosionCreator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;


public class ExplosionEffectLarge implements IExplosionSFX {

    @Override
    public void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        if (level.isClientSide) {
            return;
        }

        int cloudCount = (int) Mth.clamp(size * 0.6F, 10F, 30F);
        float cloudScale = Mth.clamp(size * 0.13F, 2F, 6.5F);
        float cloudSpeed = Mth.clamp(size * 0.04F, 0.5F, 2F);
        float waveScale = Mth.clamp(size, 25F, 65F);
        int debrisCount = (int) Mth.clamp(size * 0.5F, 5F, 25F);
        float debrisVelocity = Mth.clamp(size * 0.06F, 0.75F, 1.25F);
        float soundRange = Mth.clamp(size * 4F, 150F, 350F);

        ExplosionCreator.composeEffect(level, x, y, z,
                cloudCount, cloudScale, cloudSpeed, waveScale,
                debrisCount, debrisVelocity, soundRange);
    }
}
