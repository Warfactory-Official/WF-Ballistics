package com.wf.wfballistics.compat;

import com.wf.wfballistics.compat.warforge.WarforgeApi;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import java.util.Collection;
import java.util.UUID;

public final class WarforgeCompat {

    public static final String MODID = "warforge";

    private static final boolean LOADED = ModList.get().isLoaded(MODID);
    private static boolean factionFoFEnabled = true;
    private static boolean claimProtectionEnabled = true;

    private WarforgeCompat() {
    }

    public static void setFactionFoFEnabled(boolean enabled) {
        factionFoFEnabled = enabled;
    }

    public static void setClaimProtectionEnabled(boolean enabled) {
        claimProtectionEnabled = enabled;
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

    public static boolean areFactionsFriendly(UUID a, UUID b) {
        if (!factionFoFEnabled || !LOADED || a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return WarforgeApi.areFactionsFriendly(a, b);
    }

    public static void filterClaimProtected(Level level, Collection<BlockPos> positions) {
        if (!claimProtectionEnabled || !LOADED || level == null || positions == null || positions.isEmpty()) {
            return;
        }
        WarforgeApi.filterClaimProtected(level, positions);
    }
}
