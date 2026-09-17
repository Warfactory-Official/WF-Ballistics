package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.block.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks a colony is, once somebody is close enough for it to need any: the {@link ColonyManager.NestBuilder}
 * that ships.
 */
public final class GlyphidNest implements ColonyManager.NestBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Layers left between a nest and the bottom of the world, so it can never replace bedrock. */
    private static final int FLOOR_MARGIN = 5;

    /** How far out the ring of chambers sits, as a share of the radius: spread out, but under the dome. */
    private static final double CHAMBER_RING = 0.55;

    /**
     * What one call to {@link #place} did, for the debug command and the log line.
     *
     * @param blocks positions the mound covers
     * @param written how many of them landed in a loaded chunk; the rest are owed to {@link PendingChunkEdits}
     * @param reinforced how many were laid as hardened flesh rather than soft
     */
    public record Result(int blocks, int written, int reinforced, List<BlockPos> chambers) {
    }

    /** Hand this builder to the colony simulation. Called once at setup. */
    public static void install() {
        ColonyManager.setNestBuilder(new GlyphidNest());
    }

    @Override
    public void build(ServerLevel level, Colony colony) {
        Result result = place(level, colony);
        LOGGER.debug("[wfballistics] built {}: {} cells, {} blocks ({} owed, {} reinforced), {} chambers",
                colony, colony.buds + 1, result.blocks(), result.blocks() - result.written(),
                result.reinforced(), result.chambers().size());
    }

    @Override
    public void growCells(ServerLevel level, Colony colony) {
        int from = colony.builtBuds() + 1;
        Result result = stamp(level, colony, true, from, colony.buds);
        LOGGER.debug("[wfballistics] {} grew cells {}..{}: {} blocks ({} reinforced), {} chambers",
                colony, from, colony.buds, result.blocks(), result.reinforced(),
                result.chambers().size());
    }

    /** Stamp every mound the colony has, and tell it how many chambers it now lives on. */
    public static Result place(ServerLevel level, Colony colony) {
        // A full build lays every cell from scratch, so whatever heights a previous one recorded are stale.
        colony.budHeights.clear();
        return stamp(level, colony, true, 0, colony.buds);
    }

    /** The same measurement without writing anything. */
    public static Result survey(ServerLevel level, Colony colony) {
        return stamp(level, colony, false, 0, colony.buds);
    }

    private static Result stamp(ServerLevel level, Colony colony, boolean write, int from, int to) {
        if (!colony.hasResolvedY() || to < from) {
            return new Result(0, 0, 0, List.of());
        }
        PendingChunkEdits edits = PendingChunkEdits.get(level);
        Block flesh = ModBlocks.GLYPHID_NEST.get();
        Block hardened = ModBlocks.GLYPHID_NEST_REINFORCED.get();
        Block chamber = ModBlocks.GLYPHID_SPAWNER.get();

        int radius = colony.nestRadius();
        int height = Math.max(2, radius - 1);
        int floor = level.getMinBuildHeight() + FLOOR_MARGIN;
        // Read once for the whole pass, and this is the moment a mound's hardness is fixed; see crustDepth.
        int crust = crustDepth(ColonyRegistry.get(level).evolution(), radius);

        List<BlockPos> placedChambers = new ArrayList<>();
        LongOpenHashSet chamberKeys = new LongOpenHashSet();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int blocks = 0;
        int written = 0;
        int reinforced = 0;

        for (NestCells.Cell cell : NestCells.cells(colony, from, to)) {
            int centreY = cellY(level, colony, cell, write);
            for (BlockPos pos : chambers(level, colony, cell, centreY)) {
                placedChambers.add(pos);
                chamberKeys.add(pos.asLong());
            }

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int distSq = dx * dx + dz * dz;
                    if (distSq > radius * radius) {
                        continue;
                    }
                    int top = domeTop(distSq, radius, height);
                    // Distance in from the nearest face: rim for the skirt, top - dy for the dome.
                    int rim = radius - (int) Math.sqrt(distSq);
                    for (int dy = -radius; dy <= top; dy++) {
                        int y = centreY + dy;
                        if (y < floor || y >= level.getMaxBuildHeight()) {
                            continue;
                        }
                        cursor.set(cell.x() + dx, y, cell.z() + dz);
                        blocks++;
                        if (chamberKeys.contains(cursor.asLong())) {
                            if (!write) {
                                written += resident(level, cursor) ? 1 : 0;
                            } else if (edits.submit(level, cursor, chamber)) {
                                written++;
                            }
                            continue;
                        }

                        boolean hard = Math.min(rim, top - dy) <= crust;
                        if (hard) {
                            reinforced++;
                        }
                        if (!write) {
                            written += resident(level, cursor) ? 1 : 0;
                        } else if (edits.submit(level, cursor, hard ? hardened : flesh)) {
                            written++;
                        }
                    }
                }
            }
        }

        if (write) {
            colony.spawners = (from == 0 ? 0 : colony.spawners) + placedChambers.size();
            ColonyRegistry.get(level).setDirty();
        }
        return new Result(blocks, written, reinforced, placedChambers);
    }

    private static boolean resident(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
    }

    /** How deep into a mound the hardened flesh reaches, in blocks, or -1 for a mound that is all soft. */
    static int crustDepth(float evolution, int radius) {
        double start = ColonyConfig.reinforcedEvolution();
        if (evolution < start) {
            return -1;
        }
        double share = start >= 1.0 ? 1.0 : (evolution - start) / (1.0 - start);
        return (int) Math.floor(share * (radius + 1));
    }

    /** The height one cell sits at: remembered if it has one, sampled from the terrain if not. */
    private static int cellY(ServerLevel level, Colony colony, NestCells.Cell cell, boolean write) {
        if (cell.index() == 0) {
            return colony.y;
        }
        if (cell.index() <= colony.budHeights.size()) {
            return colony.budHeights.getInt(cell.index() - 1);
        }

        int y = colony.y;
        LevelChunk chunk = level.getChunkSource().getChunkNow(cell.x() >> 4, cell.z() >> 4);
        if (chunk != null) {
            int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    cell.x() & 15, cell.z() & 15) + 1;
            int reach = colony.nestRadius();
            y = Mth.clamp(surface, colony.y - reach, colony.y + reach);
        }
        // Only a writing pass may record, and only in order: the list's length is the count of cells built.
        if (write && cell.index() == colony.budHeights.size() + 1) {
            colony.budHeights.add(y);
        }
        return y;
    }

    /**
     * Where a colony's chambers are: one at the apex of every mound, plus a ring on the original, all buried one
     * layer under the skin.
     */
    public static List<BlockPos> chambers(ServerLevel level, Colony colony) {
        if (!colony.hasResolvedY()) {
            return List.of();
        }
        List<BlockPos> out = new ArrayList<>();
        for (NestCells.Cell cell : NestCells.cells(colony)) {
            out.addAll(chambers(level, colony, cell, cellY(level, colony, cell, false)));
        }
        return out;
    }

    /** Deduplicated: at radius 2 the ring rounds onto itself, and the colony would count a life twice. */
    private static List<BlockPos> chambers(ServerLevel level, Colony colony, NestCells.Cell cell, int centreY) {
        int radius = colony.nestRadius();
        int height = Math.max(2, radius - 1);
        int floor = level.getMinBuildHeight() + FLOOR_MARGIN;

        LongOpenHashSet seen = new LongOpenHashSet();
        List<BlockPos> out = new ArrayList<>();
        int wanted = cell.index() == 0 ? 1 + colony.tier : 1;
        for (int i = 0; i < wanted; i++) {
            int dx = 0;
            int dz = 0;
            if (i > 0) {
                double angle = 2.0 * Math.PI * (i - 1) / (wanted - 1);
                dx = (int) Math.round(Math.cos(angle) * radius * CHAMBER_RING);
                dz = (int) Math.round(Math.sin(angle) * radius * CHAMBER_RING);
            }
            int y = centreY + domeTop(dx * dx + dz * dz, radius, height) - 1;
            BlockPos pos = new BlockPos(cell.x() + dx, y, cell.z() + dz);
            if (y >= floor && y < level.getMaxBuildHeight() && seen.add(pos.asLong())) {
                out.add(pos);
            }
        }
        return out;
    }

    /**
     * @return how far above the colony's ground level the mound reaches over a column this far out
     */
    private static int domeTop(int distSq, int radius, int height) {
        double share = 1.0 - (double) distSq / (double) (radius * radius);
        return (int) Math.round(height * Math.sqrt(Math.max(0.0, share)));
    }

    private GlyphidNest() {
    }
}
