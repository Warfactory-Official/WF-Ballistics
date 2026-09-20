package com.wf.wflib.industry;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/** Inspection for the industry model. */
public final class IndustryDebug {

    private IndustryDebug() {
    }

    public static int status(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        IndustryRegistry registry = IndustryRegistry.get(level);
        List<IndustryCluster> clusters = IndustryClusters.clusters(level);
        long age = IndustryClusters.ageTicks(level);

        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d machines tracked, %d total provocation across %d cells of %d chunks",
                registry.machineCount(), registry.totalValue(), registry.cells().size(),
                registry.cellChunks())), false);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%d base(s) detected; last scan %s%s; whitelist has %d entries",
                clusters.size(),
                age < 0 ? "never run" : age + " ticks ago",
                IndustryClusters.scanning(level) ? ", one in flight" : "",
                IndustryValues.size())), false);
        return 1;
    }

    public static int bases(CommandSourceStack source) {
        List<IndustryCluster> clusters = IndustryClusters.clusters(source.getLevel());
        if (clusters.isEmpty()) {
            source.sendSuccess(() -> Component.literal(
                    "No bases detected. Either nothing whitelisted is placed, or no scan has run yet."), false);
            return 0;
        }
        for (IndustryCluster cluster : clusters) {
            source.sendSuccess(() -> Component.literal("  " + cluster), false);
        }
        return clusters.size();
    }

    /**
     * What the cell under the caller is worth, and which base it belongs to.
     */
    public static int here(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        int pressure = IndustryRegistry.get(level).pressureAt((int) pos.x, (int) pos.z);
        IndustryCluster nearest = IndustryClusters.nearest(level, pos.x, pos.z);

        source.sendSuccess(() -> Component.literal(
                "Cell provocation here: " + pressure
                        + (nearest == null ? "; no base known"
                        : "; nearest " + nearest + " at "
                        + (int) Math.sqrt(nearest.distanceSqTo(pos.x, pos.z)) + " blocks")), false);
        return pressure;
    }

    /** Re-sweep the chunks around the caller, forgetting what was recorded there first. */
    public static int rescan(CommandSourceStack source, int radiusChunks) {
        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        ChunkPos center = new ChunkPos(BlockPos.containing(pos));

        LongOpenHashSet targets = new LongOpenHashSet();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                targets.add(ChunkPos.asLong(center.x + dx, center.z + dz));
            }
        }

        IndustryRegistry registry = IndustryRegistry.get(level);
        int forgotten = registry.forgetChunks(targets);

        int found = 0;
        for (long packed : targets) {
            ChunkPos chunkPos = new ChunkPos(packed);
            ChunkAccess chunk = level.getChunk(chunkPos.x, chunkPos.z);
            registry.markScanned(chunkPos);
            for (BlockPos blockPos : chunk.getBlockEntitiesPos()) {
                int value = IndustryValues.valueOf(chunk.getBlockState(blockPos));
                if (value > 0) {
                    registry.add(blockPos, value);
                    found++;
                }
            }
        }
        IndustryClusters.markDirty(level);

        int scanned = targets.size();
        int finalFound = found;
        source.sendSuccess(() -> Component.literal(
                "Rescanned " + scanned + " chunks: forgot " + forgotten + ", found " + finalFound
                        + ". Bases re-detect after " + IndustryConfig.recomputeDelayTicks() + " quiet ticks."), false);
        return found;
    }

    /**
     * The occupied cells themselves, for when a base count looks wrong and the question is whether the cells or the
     * clustering are at fault.
     */
    public static int cells(CommandSourceStack source) {
        IndustryRegistry registry = IndustryRegistry.get(source.getLevel());
        if (registry.cells().isEmpty()) {
            source.sendSuccess(() -> Component.literal("No occupied cells."), false);
            return 0;
        }
        for (Long2IntMap.Entry entry : registry.cells().long2IntEntrySet()) {
            long cell = entry.getLongKey();
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "  cell (%d, %d) centre (%d, %d) value %d",
                    IndustryRegistry.cellX(cell), IndustryRegistry.cellZ(cell),
                    registry.cellCenterX(cell), registry.cellCenterZ(cell), entry.getIntValue())), false);
        }
        return registry.cells().size();
    }
}
