package com.wf.wfballistics.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

import java.util.Collection;
import java.util.UUID;

/**
 * Optional WarForge Factions integration entry point.
 *
 * <p>TODO(port): the WarForge-facing implementation ({@code compat.warforge.WarforgeApi}) is temporarily
 * excluded from compilation because WarForge for NeoForge 1.21.1 is not yet on the build classpath (its
 * Maven coordinate is unconfirmed). This stub force-disables the integration so the mod builds and runs
 * without WarForge. Re-enable by restoring the compile-only dependency in build.gradle, un-excluding
 * {@code compat/warforge/**}, and delegating the methods below back to {@code WarforgeApi}.
 */
public final class WarforgeCompat {

    public static final String MODID = "warforge";

    // Force-disabled until the WarForge 1.21.1 integration is re-enabled (see class javadoc).
    private static final boolean LOADED = false && ModList.get().isLoaded(MODID);
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
        // TODO(port): return WarforgeApi.factionOfPlayer(playerId);
        return null;
    }

    public static UUID factionClaiming(Level level, BlockPos pos) {
        // TODO(port): return WarforgeApi.factionClaiming(level, pos);
        return null;
    }

    public static boolean areFactionsFriendly(UUID a, UUID b) {
        if (a != null && a.equals(b)) {
            return true;
        }
        // TODO(port): return WarforgeApi.areFactionsFriendly(a, b);
        return false;
    }

    public static void filterClaimProtected(Level level, UUID igniterFaction, Collection<BlockPos> positions) {
        // TODO(port): WarforgeApi.filterClaimProtected(level, igniterFaction, positions);
    }
}
