package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.world.entity.Entity;

/** An extra, non-lethal payload applied to every entity the blast damages: e.g. */
public interface ICustomDamageHandler {

    /**
     * @param distanceScaled the target's distance from the blast centre as a fraction of the blast radius,
     *      in {@code [0, 1]} (0 = epicentre); use it to fall the payload off with distance
     */
    void handleAttack(ExplosionAEF explosion, Entity entity, double distanceScaled);
}
