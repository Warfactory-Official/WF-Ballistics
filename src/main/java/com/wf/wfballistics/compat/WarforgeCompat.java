package com.wf.wfballistics.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.UUID;

public final class WarforgeCompat {

    public static final String MODID = "warforge";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final UUID NULL_UUID = new UUID(0L, 0L);

    private static boolean initialized = false;
    private static boolean active = false;
    // Config gates (set by WFConfig): let a server op turn either integration off independently.
    private static boolean factionFoFEnabled = true;
    private static boolean claimProtectionEnabled = true;

    public static void setFactionFoFEnabled(boolean enabled) {
        factionFoFEnabled = enabled;
    }

    public static void setClaimProtectionEnabled(boolean enabled) {
        claimProtectionEnabled = enabled;
    }

    // Resolved handles
    private static Object factions;            // WarForgeMod.FACTIONS (FactionStorage instance)
    private static Method mGetFactionOfPlayer; // FactionStorage#getFactionOfPlayer(UUID) -> Faction
    private static Method mGetClaimChunk;      // FactionStorage#getClaim(DimChunkPos) -> UUID
    private static Method mGetFaction;         // FactionStorage#getFaction(UUID) -> Faction
    private static Field fFactionUuid;         // Faction#uuid (UUID)
    private static Method mIsAllyOf;           // Faction#isAllyOf(UUID) -> boolean
    private static Method mIsInTruceWith;      // Faction#isInTruceWith(UUID) -> boolean
    private static Constructor<?> cDimChunkPos;// DimChunkPos(ResourceKey<Level>, BlockPos)
    private static Method mFilter;             // ExplosionProtection#filter(Level, UUID, Collection<BlockPos>)

    private WarforgeCompat() {
    }

    /**
     * @return true if WarForge is loaded and its faction/claim API resolved successfully.
     */
    public static boolean isActive() {
        init();
        return active;
    }

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        if (!ModList.get().isLoaded(MODID)) {
            return;
        }
        try {
            Class<?> warForgeMod = Class.forName("com.flansmod.warforge.common.WarForgeMod");
            factions = warForgeMod.getField("FACTIONS").get(null);
            Class<?> factionStorage = factions.getClass();
            Class<?> faction = Class.forName("com.flansmod.warforge.server.Faction");
            Class<?> dimChunkPos = Class.forName("com.flansmod.warforge.common.util.DimChunkPos");
            Class<?> explosionProtection = Class.forName("com.flansmod.warforge.common.ExplosionProtection");

            mGetFactionOfPlayer = factionStorage.getMethod("getFactionOfPlayer", UUID.class);
            mGetClaimChunk = factionStorage.getMethod("getClaim", dimChunkPos);
            mGetFaction = factionStorage.getMethod("getFaction", UUID.class);
            fFactionUuid = faction.getField("uuid");
            mIsAllyOf = faction.getMethod("isAllyOf", UUID.class);
            mIsInTruceWith = faction.getMethod("isInTruceWith", UUID.class);
            cDimChunkPos = dimChunkPos.getConstructor(net.minecraft.resources.ResourceKey.class, BlockPos.class);
            mFilter = explosionProtection.getMethod("filter", Level.class, UUID.class, Collection.class);

            active = true;
            LOGGER.info("[wfballistics] WarForge detected — faction FoF + claim-aware explosions enabled.");
        } catch (Throwable t) {
            active = false;
            LOGGER.warn("[wfballistics] WarForge is present but its API could not be resolved; "
                    + "faction/claim integration is disabled. ({})", t.toString());
        }
    }

    /**
     * @return the faction UUID of the given player, or null if they are factionless / WarForge is inactive.
     */
    public static UUID factionOfPlayer(UUID playerId) {
        if (!isActive() || playerId == null) {
            return null;
        }
        try {
            Object f = mGetFactionOfPlayer.invoke(factions, playerId);
            return f == null ? null : (UUID) fFactionUuid.get(f);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return the faction UUID claiming the chunk containing {@code pos}, or null if unclaimed / inactive.
     * Used to give a launcher/battery the team of the faction whose land it sits on.
     */
    public static UUID factionClaiming(Level level, BlockPos pos) {
        if (!isActive() || level == null || pos == null) {
            return null;
        }
        try {
            Object dcp = cDimChunkPos.newInstance(level.dimension(), pos);
            UUID claim = (UUID) mGetClaimChunk.invoke(factions, dcp);
            return (claim == null || claim.equals(NULL_UUID)) ? null : claim;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return true if two faction UUIDs are the same faction, allied, or in a post-alliance truce. Any null
     * (factionless) or an inactive integration yields false — the caller then falls back to its own FoF.
     */
    public static boolean areFactionsFriendly(UUID a, UUID b) {
        if (!factionFoFEnabled || !isActive() || a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        try {
            Object fa = mGetFaction.invoke(factions, a);
            if (fa == null) {
                return false;
            }
            return (boolean) mIsAllyOf.invoke(fa, b) || (boolean) mIsInTruceWith.invoke(fa, b);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Removes claim-protected block positions from {@code positions} in place (an explosion should not damage
     * protected land). WarForge's own logic already lets destruction through in active siege zones, so this
     * respects sieges automatically. No-op when WarForge is inactive.
     */
    public static void filterClaimProtected(Level level, Collection<BlockPos> positions) {
        if (!claimProtectionEnabled || !isActive() || level == null || positions == null || positions.isEmpty()) {
            return;
        }
        try {
            mFilter.invoke(null, level, NULL_UUID, positions);
        } catch (Throwable ignored) {
        }
    }
}
