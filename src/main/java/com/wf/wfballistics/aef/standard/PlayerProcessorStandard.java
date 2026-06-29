package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IPlayerProcessor;
import com.wf.wfballistics.network.ExplosionKnockbackPacket;
import com.wf.wfballistics.network.WFNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.Map;

/**
 * Ships the knockback impulses computed by the entity processor to each affected player's client, which
 * applies them locally. This is the standard fix for the long-standing issue where server-applied
 * explosion knockback feels mushy or gets eaten by client reconciliation.
 */
public class PlayerProcessorStandard implements IPlayerProcessor {

    @Override
    public void process(ExplosionAEF explosion, Level level, double x, double y, double z, Map<Player, Vec3> affectedPlayers) {
        for (Map.Entry<Player, Vec3> entry : affectedPlayers.entrySet()) {
            if (entry.getKey() instanceof ServerPlayer player) {
                WFNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new ExplosionKnockbackPacket(entry.getValue()));
            }
        }
    }
}
