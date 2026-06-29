package com.wf.wfballistics.aef.nuke;

import com.wf.wfballistics.entity.EntityNukeExplosionMK5;
import com.wf.wfballistics.entity.EntityNukeTorex;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * One-call entry point for a full nuclear detonation: the crater-carving ray explosion plus the rising
 * Torex mushroom cloud. Spawn both, as the NTM nukes do.
 *
 * <pre>{@code Nuke.detonate(level, hitPos, 25);}</pre>
 *
 * @see EntityNukeExplosionMK5 the multi-tick ray-traced block destruction + damage
 * @see EntityNukeTorex the toroidal convection mushroom cloud effect
 */
public final class Nuke {

    private Nuke() { }

    /**
     * @param radius nuke radius parameter (drives strength/speed/crater size, and the cloud scale); the
     *               explosion's actual block radius scales with this
     */
    public static void detonate(Level level, Vec3 center, int radius) {
        if (level.isClientSide) return;
        level.addFreshEntity(EntityNukeExplosionMK5.statFac(level, radius, center));
        level.addFreshEntity(new EntityNukeTorex(level, center.add(0.0D, 4.5D, 0.0D), radius));
    }
}
