package com.wf.wfballistics.compat.warforge;

import com.flansmod.warforge.common.ExplosionProtection;
import com.flansmod.warforge.common.WarForgeMod;
import com.flansmod.warforge.common.util.DimChunkPos;
import com.flansmod.warforge.server.Faction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.Collection;
import java.util.UUID;

public final class WarforgeApi {

    private static final UUID NULL_UUID = new UUID(0L, 0L);

    private WarforgeApi() {
    }

    public static UUID factionOfPlayer(UUID playerId) {
        Faction faction = WarForgeMod.FACTIONS.getFactionOfPlayer(playerId);
        return faction == null ? null : faction.uuid;
    }

    public static UUID factionClaiming(Level level, BlockPos pos) {
        UUID claim = WarForgeMod.FACTIONS.getClaim(new DimChunkPos(level.dimension(), pos));
        return (claim == null || claim.equals(NULL_UUID)) ? null : claim;
    }

    public static boolean areFactionsFriendly(UUID a, UUID b) {
        Faction fa = WarForgeMod.FACTIONS.getFaction(a);
        return fa != null && (fa.isAllyOf(b) || fa.isInTruceWith(b));
    }

    public static void filterClaimProtected(Level level, Collection<BlockPos> positions) {
        ExplosionProtection.filter(level, NULL_UUID, positions);
    }
}
