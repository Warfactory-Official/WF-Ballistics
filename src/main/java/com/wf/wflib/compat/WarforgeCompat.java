package com.wf.wflib.compat;

import com.wf.wflib.compat.warforge.WarforgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Optional WarForge Factions integration entry point. */
public final class WarforgeCompat {

    public static final String MODID = "warforge";

    private static final boolean LOADED = detect();

    /**
     * @return whether WarForge is present.
     */
    private static boolean detect() {
        ModList list = ModList.get();
        return list != null && list.isLoaded(MODID);
    }
    private static boolean factionFoFEnabled = true;
    private static boolean claimProtectionEnabled = true;
    private static boolean territoryProtectionEnabled = true;

    private WarforgeCompat() {
    }

    public static void setFactionFoFEnabled(boolean enabled) {
        factionFoFEnabled = enabled;
    }

    public static void setClaimProtectionEnabled(boolean enabled) {
        claimProtectionEnabled = enabled;
    }

    /** Turn the construction territory gate off entirely, letting drones build anywhere. */
    public static void setTerritoryProtectionEnabled(boolean enabled) {
        territoryProtectionEnabled = enabled;
    }

    public static boolean territoryProtectionEnabled() {
        return territoryProtectionEnabled;
    }

    public static boolean isActive() {
        return LOADED;
    }

    public static UUID factionOfPlayer(UUID playerId) {
        if (!LOADED || playerId == null) {
            return null;
        }
        return WarforgeApi.factionOfPlayer(playerId);
    }

    public static UUID factionClaiming(Level level, BlockPos pos) {
        if (!LOADED || level == null || pos == null) {
            return null;
        }
        return WarforgeApi.factionClaiming(level, pos);
    }

    /**
     * @return whether {@code faction} may have drones change blocks at {@code pos}.
     */
    public static TerritoryVerdict buildVerdict(Level level, UUID faction, BlockPos pos) {
        if (!LOADED || !territoryProtectionEnabled || level == null || pos == null) {
            return TerritoryVerdict.ALLOWED;
        }
        return WarforgeApi.buildVerdict(level, faction, pos);
    }

    /**
     * Who owns each of these chunks, in one pass.
     *
     * @return claimed chunks only, and an empty map with no WarForge: nothing is claimed when there is
     *         no claim system
     */
    public static Map<ChunkPos, UUID> ownersAlong(Level level, Collection<ChunkPos> chunks) {
        if (!LOADED || level == null || chunks == null || chunks.isEmpty()) {
            return Map.of();
        }
        return WarforgeApi.ownersAlong(level, chunks);
    }

    /**
     * Whether this player may place this block in this chunk.
     *
     * <p>Open with no WarForge, and open when claim protection has been switched off, so a route check
     * agrees with what the game would actually do rather than with a rule nothing is enforcing.</p>
     */
    public static boolean mayPlaceAt(Level level, UUID playerId, ChunkPos chunk, Block block) {
        if (!LOADED || !claimProtectionEnabled || level == null || playerId == null || chunk == null) {
            return true;
        }
        return WarforgeApi.mayPlaceAt(level, playerId, chunk, block);
    }

    /**
     * A faction's chosen colour as 0xRRGGBB.
     *
     * @return {@code fallback} with no WarForge, no faction, or no such faction. A caller drawing
     *         something always gets a colour and never has to branch.
     */
    public static int factionColour(UUID factionId, int fallback) {
        if (!LOADED || factionId == null) {
            return fallback;
        }
        return WarforgeApi.factionColour(factionId, fallback);
    }

    /** A faction's display name, or null when there is none to give. */
    public static String factionName(UUID factionId) {
        if (!LOADED || factionId == null) {
            return null;
        }
        return WarforgeApi.factionName(factionId);
    }

    /** Allied or the same faction. Stricter than {@link #areFactionsFriendly}, which a truce satisfies. */
    public static boolean areFactionsAllied(UUID a, UUID b) {
        if (!factionFoFEnabled || !LOADED || a == null || b == null) {
            return false;
        }
        return a.equals(b) || WarforgeApi.areFactionsAllied(a, b);
    }

    /**
     * Whether a player may be shown something owned by a faction.
     *
     * <p><b>Open when WarForge is absent.</b> With no faction system there is nothing to hide behind
     * and nobody to hide from, and a closed default would make anything gated on this invisible to
     * everyone in a pack without WarForge, which reads as the feature being broken.</p>
     */
    public static boolean maySeeFactionContent(UUID playerId, UUID ownerFaction) {
        if (!LOADED || !factionFoFEnabled) {
            return true;
        }
        if (ownerFaction == null) {
            return false;
        }
        return WarforgeApi.maySeeFactionContent(playerId, ownerFaction);
    }

    public static boolean areFactionsFriendly(UUID a, UUID b) {
        if (!factionFoFEnabled || !LOADED || a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return WarforgeApi.areFactionsFriendly(a, b);
    }

    public static void filterClaimProtected(Level level, UUID igniterFaction, Collection<BlockPos> positions) {
        if (!claimProtectionEnabled || !LOADED || level == null || positions == null || positions.isEmpty()) {
            return;
        }
        WarforgeApi.filterClaimProtected(level, igniterFaction, positions);
    }
}
