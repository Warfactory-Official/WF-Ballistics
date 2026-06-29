package com.wf.wfballistics.aef.interfaces;

import com.wf.wfballistics.aef.ExplosionAEF;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Stage 4 of the explosion pipeline: consumes the knockback map produced by the {@link IEntityProcessor}.
 * <p>
 * The server cannot reliably move a player by writing to its motion (the client is authoritative over its
 * own position), so the standard implementation sends each impulse to the owning client as a packet which
 * adds it locally. Split out as its own stage so the knockback transport can be swapped without touching
 * the damage maths.
 */
public interface IPlayerProcessor {

    void process(ExplosionAEF explosion, Level level, double x, double y, double z, Map<Player, Vec3> affectedPlayers);
}
