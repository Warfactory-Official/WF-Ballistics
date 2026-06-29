package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Stage 2 of the explosion pipeline: applies damage and knockback to entities in range.
 * <p>
 * Player knockback has to round-trip through the client to feel responsive, so the processor does not
 * apply player knockback itself; instead it returns the per-player knockback impulse and lets the
 * {@link IPlayerProcessor} ship it to the owning client.
 *
 * @see com.wf.wfballistics.aef.standard.EntityProcessorCross the recommended "nearest-surface" model
 */
public interface IEntityProcessor {

    /**
     * @return for every {@link Player} caught in the blast, the knockback impulse (world-space velocity
     *         delta) that should be forwarded to its client. Never {@code null}.
     */
    Map<Player, Vec3> process(ExplosionAEF explosion, Level level, double x, double y, double z, float size);
}
