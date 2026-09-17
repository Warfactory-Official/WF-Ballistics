package com.wf.wfballistics.compat;

import com.wf.wfballistics.compat.warforge.WarforgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.Collection;
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
