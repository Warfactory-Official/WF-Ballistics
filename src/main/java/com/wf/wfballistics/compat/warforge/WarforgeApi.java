package com.wf.wfballistics.compat.warforge;

import com.flansmod.warforge.common.ExplosionProtection;
import com.flansmod.warforge.common.WarForgeMod;
import com.flansmod.warforge.common.util.DimChunkPos;
import com.flansmod.warforge.server.Faction;
import com.flansmod.warforge.server.FactionStorage;
import com.wf.wfballistics.compat.TerritoryVerdict;
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

    /**
     * Whether {@code faction} may have a drone change blocks in this chunk.
     *
     * <p>Asked in the order the answers matter. A live siege beats everything, including a claim the asking
     * faction owns, the ground being fought over is precisely the ground nobody should be quietly rebuilding,
     * and it is asked first because {@code getClaim} would otherwise report the defender's ordinary claim
     * and read as friendly to the defender. After that it is the claim: nobody's is fair game, the neutral
     * pseudo-factions are not, and a real claim comes down to standing.
     */
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

    // Drop the blocks the owning faction is not allowed to blow up under the real WarForge chunk rules,
    // leaving the rest for the blast. igniterFaction is the blast's owning faction (a missile's teamId), or
    // null for an unattributed blast. This respects siege/war/safe zones and faction standing from that
    // faction's perspective (it may breach a claim it is besieging, is stopped by claims it may not touch)
    // instead of blindly stopping at any claim.
    public static void filterClaimProtected(Level level, UUID igniterFaction, Collection<BlockPos> positions) {
        ExplosionProtection.filter(level, actingPlayerFor(igniterFaction), positions);
    }

    // WarForge evaluates explosion protection from an acting *player's* perspective (it resolves that player
    // to their faction internally). Map the blast's owning faction to one of its members, its leader, so
    // the real chunk rules are applied as if that faction set the blast off. Falls back to the null igniter
    // (an unattributed foe, protected everywhere but active siege zones) when the blast has no faction, the
    // faction no longer exists, or it has no members.
    private static UUID actingPlayerFor(UUID igniterFaction) {
        if (igniterFaction == null || igniterFaction.equals(NULL_UUID)) {
            return NULL_UUID;
        }
        Faction faction = WarForgeMod.FACTIONS.getFaction(igniterFaction);
        if (faction == null) {
            return NULL_UUID;
        }
        // getLeaderId() returns Faction.nullUuid (not Java null) when the faction has no leader; either way
        // an empty/leaderless faction falls back to the unattributed igniter.
        UUID leader = faction.getLeaderId();
        return (leader == null || leader.equals(NULL_UUID)) ? NULL_UUID : leader;
    }
}
