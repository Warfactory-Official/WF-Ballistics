package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.interfaces.IPlayerProcessor;
import com.wf.wflib.network.ExplosionKnockbackPacket;
import com.wf.wflib.network.WFNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Ships the knockback impulses computed by the entity processor to each affected player's client, which applies
 * them locally.
 */
public class PlayerProcessorStandard implements IPlayerProcessor {

    @Override
    public void process(ExplosionAEF explosion, Level level, double x, double y, double z, Map<Player, Vec3> affectedPlayers) {
        for (Map.Entry<Player, Vec3> entry : affectedPlayers.entrySet()) {
            if (entry.getKey() instanceof ServerPlayer player) {
                WFNetwork.sendToPlayer(player, new ExplosionKnockbackPacket(entry.getValue()));
            }
        }
    }
}
