package com.wf.wflib.rail.align;

import com.wf.wflib.compat.WarforgeCompat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Walks a route and asks who owns the ground under it.
 *
 * <p>Server-side, and nothing here loads a chunk: claim ownership and the protection rule are both map
 * lookups over data the server already holds, which is what makes it cheap enough to re-run while a
 * point is being dragged.</p>
 */
public final class RightOfWaySurvey {

    /**
     * The block the check is made against.
     *
     * <p>A stand-in for "an ordinary building block", because the protection rule is per-block through
     * a whitelist and a blacklist and there is no rail block to name without Immersive Railroading
     * loaded. Stone is on neither list in any stock configuration, so it reports the general rule
     * rather than an exception to it.</p>
     */
    private static final Block PROXY_BLOCK = Blocks.STONE;

    /** Neutral grey for ground nobody has claimed. */
    private static final int UNCLAIMED_COLOUR = 0x9AA0A6;

    /** How often to sample the line. Fine enough that a single claimed chunk is not stepped over. */
    private static final double SAMPLE_STEP = 8.0;

    private RightOfWaySurvey() {
    }

    /**
     * @param player whose standing decides what counts as blocked
     * @return where the route crosses whose land, and where it may not be built
     */
    public static RightOfWay survey(ServerPlayer player, ServerLevel level, Centreline centreline,
                                    DesignClass designClass) {
        if (centreline.isEmpty()) {
            return RightOfWay.EMPTY;
        }
        var samples = centreline.sample(SAMPLE_STEP);
        int count = samples.size();

        double[] chainage = new double[count];
        String[] owners = new String[count];
        int[] colours = new int[count];
        boolean[] blocked = new boolean[count];

        // The corridor is as wide as the clearance, not a single line of blocks, so a claim the route
        // only clips still counts. Half-width either side of the centreline.
        double half = designClass.clearanceWidth() / 2.0;

        Map<ChunkPos, UUID> ownerCache = new HashMap<>();
        Map<ChunkPos, Boolean> placeCache = new HashMap<>();
        Set<ChunkPos> crossed = new LinkedHashSet<>();
        Set<ChunkPos> blockedChunks = new HashSet<>();

        double walked = 0.0;
        for (int i = 0; i < count; i++) {
            var sample = samples.get(i);
            if (i > 0) {
                var previous = samples.get(i - 1);
                walked += Math.hypot(sample.x() - previous.x(), sample.z() - previous.z());
            }
            chainage[i] = walked;

            // The worst verdict anywhere across the corridor at this chainage is the verdict here: a
            // route half of which is on someone's claim is still on their claim.
            UUID owner = null;
            boolean refused = false;
            for (double side = -half; side <= half + 1.0e-9; side += Math.max(1.0, half)) {
                double nx = -Math.sin(sample.heading());
                double nz = Math.cos(sample.heading());
                ChunkPos chunk = new ChunkPos(
                        (int) Math.floor((sample.x() + nx * side)) >> 4,
                        (int) Math.floor((sample.z() + nz * side)) >> 4);
                crossed.add(chunk);

                UUID at = ownerCache.computeIfAbsent(chunk,
                        c -> WarforgeCompat.ownersAlong(level, Set.of(c)).get(c));
                boolean may = placeCache.computeIfAbsent(chunk,
                        c -> WarforgeCompat.mayPlaceAt(level, player.getUUID(), c, PROXY_BLOCK));
                if (at != null && owner == null) {
                    owner = at;
                }
                if (!may) {
                    refused = true;
                    blockedChunks.add(chunk);
                }
            }

            owners[i] = owner == null ? "" : nameOf(owner);
            colours[i] = owner == null ? UNCLAIMED_COLOUR
                    : WarforgeCompat.factionColour(owner, UNCLAIMED_COLOUR);
            blocked[i] = refused;
        }

        if (System.getProperty("wflib.rowDebug") != null) {
            com.mojang.logging.LogUtils.getLogger().info(
                    "[wflib] ROW DIAG warforge={} chunks={} owned={} blocked={} firstChunk={} ownerOfFirst={}",
                    WarforgeCompat.isActive(), crossed.size(),
                    ownerCache.values().stream().filter(java.util.Objects::nonNull).count(),
                    blockedChunks.size(),
                    crossed.isEmpty() ? "none" : crossed.iterator().next(),
                    crossed.isEmpty() ? "none"
                            : String.valueOf(WarforgeCompat.ownersAlong(level, crossed).size()));
        }
        return RightOfWay.fold(chainage, owners, colours, blocked, centreline.length(),
                crossed.size(), blockedChunks.size());
    }

    private static String nameOf(UUID faction) {
        String name = WarforgeCompat.factionName(faction);
        // A claim whose faction has gone is still a claim, and still blocks: say so rather than
        // reporting it as unclaimed ground, which would be the more comforting lie.
        return name == null || name.isEmpty() ? "unknown faction" : name;
    }
}
