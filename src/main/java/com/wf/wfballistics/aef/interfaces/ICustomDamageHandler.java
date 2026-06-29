package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.world.entity.Entity;

/**
 * An extra, non-lethal payload applied to every entity the blast damages — e.g. setting things on fire,
 * applying a status effect, or irradiating the target. Runs in addition to the normal blast damage, once
 * per affected entity.
 */
public interface ICustomDamageHandler {

    /**
     * @param distanceScaled the target's distance from the blast centre as a fraction of the blast radius,
     *                       in {@code [0, 1]} (0 = epicentre); use it to fall the payload off with distance
     */
    void handleAttack(ExplosionAEF explosion, Entity entity, double distanceScaled);
}
