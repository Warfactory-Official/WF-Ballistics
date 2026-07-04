package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.ICustomDamageHandler;
import com.wf.wfballistics.fire.FireType;
import com.wf.wfballistics.fire.WFFire;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;


public class CustomDamageHandlerAmat implements ICustomDamageHandler {

    /**
     * Balefire duration (ticks) applied at the epicentre; falls off to 0 at the blast edge.
     */
    private final float strength;

    public CustomDamageHandlerAmat(float strength) {
        this.strength = strength;
    }

    @Override
    public void handleAttack(ExplosionAEF explosion, Entity entity, double distanceScaled) {
        if (entity instanceof LivingEntity living) {
            int ticks = (int) Mth.clamp(this.strength * (1.0 - distanceScaled), 0.0, this.strength);
            if (ticks > 0) {
                WFFire.ignite(living, FireType.PHOSPHORUS, ticks);
            }
        }
    }
}
