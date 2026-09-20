package com.wf.wflib.compat.warforge;

import com.flansmod.warforge.common.ExplosionProtection;
import com.flansmod.warforge.common.WarForgeMod;
import com.flansmod.warforge.common.util.DimChunkPos;
import com.flansmod.warforge.server.Faction;
import com.flansmod.warforge.server.FactionStorage;
import com.wf.wflib.compat.TerritoryVerdict;
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

    /** Whether {@code faction} may have a drone change blocks in this chunk. */
    public static TerritoryVerdict buildVerdict(Level level, UUID faction, BlockPos pos) {
        DimChunkPos chunk = new DimChunkPos(level.dimension(), pos);
        FactionStorage.SiegeZoneResult siege = WarForgeMod.FACTIONS.getSiegeZone(chunk);
        if (siege.zone == FactionStorage.SiegeZone.SIEGED) {
            return TerritoryVerdict.SIEGE;
        }
        if (siege.zone == FactionStorage.SiegeZone.WAR) {
            return TerritoryVerdict.WAR_ZONE;
        }
        UUID claim = WarForgeMod.FACTIONS.getClaim(chunk);
        if (claim == null || claim.equals(NULL_UUID) || claim.equals(Faction.nullUuid)) {
            return TerritoryVerdict.ALLOWED;
        }
        if (claim.equals(FactionStorage.SAFE_ZONE_ID)) {
            return TerritoryVerdict.SAFE_ZONE;
        }
        if (claim.equals(FactionStorage.WAR_ZONE_ID)) {
            return TerritoryVerdict.WAR_ZONE;
        }
        if (faction == null || faction.equals(NULL_UUID)) {
            // Somebody with no faction, on somebody's claim. There is no standing to appeal to.
            return TerritoryVerdict.FOREIGN_CLAIM;
        }
        if (claim.equals(faction) || areFactionsFriendly(faction, claim)) {
            return TerritoryVerdict.ALLOWED;
        }
        return TerritoryVerdict.FOREIGN_CLAIM;
    }

    public static boolean areFactionsFriendly(UUID a, UUID b) {
        Faction fa = WarForgeMod.FACTIONS.getFaction(a);
        return fa != null && (fa.isAllyOf(b) || fa.isInTruceWith(b));
    }

    public static void filterClaimProtected(Level level, UUID igniterFaction, Collection<BlockPos> positions) {
        ExplosionProtection.filter(level, actingPlayerFor(igniterFaction), positions);
    }

    private static UUID actingPlayerFor(UUID igniterFaction) {
        if (igniterFaction == null || igniterFaction.equals(NULL_UUID)) {
            return NULL_UUID;
        }
        Faction faction = WarForgeMod.FACTIONS.getFaction(igniterFaction);
        if (faction == null) {
            return NULL_UUID;
        }
        UUID leader = faction.getLeaderId();
        return (leader == null || leader.equals(NULL_UUID)) ? NULL_UUID : leader;
    }
}
