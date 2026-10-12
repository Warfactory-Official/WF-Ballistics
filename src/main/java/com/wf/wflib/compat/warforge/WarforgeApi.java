package com.wf.wflib.compat.warforge;

import com.flansmod.warforge.api.WarforgeAPI;
import com.flansmod.warforge.common.ExplosionProtection;
import com.flansmod.warforge.common.WarForgeMod;
import com.flansmod.warforge.common.util.DimChunkPos;
import com.flansmod.warforge.server.Faction;
import com.flansmod.warforge.server.FactionStorage;
import com.wf.wflib.compat.TerritoryVerdict;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** Can a drone owned by {@code faction} edit blocks here. */
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
            // No faction => no standing against any claim.
            return TerritoryVerdict.FOREIGN_CLAIM;
        }
        if (claim.equals(faction) || areFactionsFriendly(faction, claim)) {
            return TerritoryVerdict.ALLOWED;
        }
        return TerritoryVerdict.FOREIGN_CLAIM;
    }

    /** Batched owner lookup for a route re-asked every edit. Unclaimed chunk: absent, not null-mapped. */
    public static Map<ChunkPos, UUID> ownersAlong(Level level, Collection<ChunkPos> chunks) {
        Map<ChunkPos, UUID> out = new LinkedHashMap<>();
        for (ChunkPos chunk : chunks) {
            UUID owner = WarforgeAPI.ownerOf(new DimChunkPos(level.dimension(), chunk.x, chunk.z));
            if (owner != null) {
                out.put(chunk, owner);
            }
        }
        return out;
    }

    /** Same rule the block-place event cancels on. */
    public static boolean mayPlaceAt(Level level, UUID playerId, ChunkPos chunk, Block block) {
        return WarforgeAPI.mayPlace(playerId, new DimChunkPos(level.dimension(), chunk.x, chunk.z), block);
    }

    /** 0xRRGGBB; {@code fallback} if no such faction. */
    public static int factionColour(UUID factionId, int fallback) {
        return WarforgeAPI.factionColour(factionId, fallback);
    }

    /** Null: no such faction. */
    public static String factionName(UUID factionId) {
        return WarforgeAPI.factionName(factionId);
    }

    /** Same faction or allied; truce != ally, no map sharing on a ceasefire. */
    public static boolean areFactionsAllied(UUID a, UUID b) {
        return WarforgeAPI.areAllied(a, b);
    }

    public static boolean maySeeFactionContent(UUID playerId, UUID ownerFaction) {
        return WarforgeAPI.maySeeFactionContent(playerId, ownerFaction);
    }

    public static boolean areFactionsFriendly(UUID a, UUID b) {
        Faction fa = WarForgeMod.FACTIONS.getFaction(a);
        return fa != null && (fa.isAllyOf(b) || fa.isInTruceWith(b));
    }

    public static boolean claimProtected(Level level, UUID player, BlockPos pos) {
        return ExplosionProtection.isProtected(level, player, pos);
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
