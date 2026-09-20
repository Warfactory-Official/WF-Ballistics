package com.wf.wflib.aef.interfaces;

import com.wf.wflib.aef.ExplosionAEF;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Stage 2 of the explosion pipeline: applies damage and knockback to entities in range.
 *
 * @see com.wf.wflib.aef.standard.EntityProcessorCross the recommended "nearest-surface" model
 */
public interface IEntityProcessor {

    /**
     * @return for every {@link Player} caught in the blast, the knockback impulse (world-space velocity
     *      delta) that should be forwarded to its client. Never {@code null}.
     */
    Map<Player, Vec3> process(ExplosionAEF explosion, Level level, double x, double y, double z, float size);
}
