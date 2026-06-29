package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IExplosionSFX;
import com.wf.wfballistics.network.AuxParticlePacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * A compact, weapon-scale blast effect (the {@code "explosion_small"} effect): a tunable cluster of hot
 * puffs plus block debris, with none of the screen-filling smoke of {@link ExplosionEffectStandard}. Good
 * for grenades, shells and rockets where many can be on screen at once.
 *
 * <pre>{@code new ExplosionEffectWeapon(10, 2f, 0.5f)}</pre>
 */
public class ExplosionEffectWeapon implements IExplosionSFX {

    private final int cloudCount;
    private final float cloudScale;
    private final float cloudSpeedMult;

    public ExplosionEffectWeapon(int cloudCount, float cloudScale, float cloudSpeedMult) {
        this.cloudCount = cloudCount;
        this.cloudScale = cloudScale;
        this.cloudSpeedMult = cloudSpeedMult;
    }

    @Override
    public void doEffect(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        if (level.isClientSide) return;

        CompoundTag data = new CompoundTag();
        data.putInt("count", cloudCount);
        data.putFloat("scale", cloudScale);
        data.putFloat("speed", cloudSpeedMult);
        data.putInt("debris", 15);

        WFNetwork.sendToTracking(level, x, z, new AuxParticlePacket("instanced_smoke", x, y, z, data));
    }
}
