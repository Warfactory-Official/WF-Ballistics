package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import com.wf.wfballistics.block.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The blocks a colony is, once somebody is close enough for it to need any.
 *
 * <p>This is the {@link ColonyManager.NestBuilder} the hook was left for. It runs at exactly two moments —
 * a scout settling on ground it is standing on, and a chunk loading under a colony founded out of sight —
 * and never otherwise, because a nest nobody can see needs no blocks (§8.1).
 *
 * <h2>Why the shape is generated rather than ported</h2>
 * Upstream's {@code GlyphidHiveFeature} stamps one fixed 11x11x5 schematic. That is the right answer for a
 * worldgen feature and the wrong one here: {@link Colony#nestRadius()} runs 2 to 6 with distance from spawn,
 * and the whole point of §8.2's distance scaling is that a frontier nest should be visibly a different
 * proposition from a starting one. A fixed schematic can only be the same mound five times over.
 *
 * <p>So a mound: a dome of radius {@code r} on a skirt {@code r} deep, which is what keeps it anchored where
 * the ground falls away under it. Chambers are buried one layer under the skin, so clearing a nest is
 * digging into it rather than walking up and breaking something.
 *
 * <h2>Why every block goes through PendingChunkEdits</h2>
 * The colony's own chunk is loaded when this runs; a radius-6 mound spans 13 blocks and reaches into
 * neighbours that may not be. {@link PendingChunkEdits#submit} writes the ones it can and owes the rest,
 * which is the whole reason that class exists — writing into an unloaded chunk directly races the chunk
 * pipeline, and loading one to write it defeats the design.
 */
public final class GlyphidNest implements ColonyManager.NestBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Layers left between the bottom of a nest and the bottom of the world, so a mound can never be asked to
     * replace bedrock. Cheaper than reading the block, and — unlike reading it — it works for the part of the
     * nest owed to a chunk that is not loaded.
     */
    private static final int FLOOR_MARGIN = 5;

    /**
     * How far out from the centre the ring of chambers sits, as a share of the nest radius. Far enough apart
     * to be separate chambers, near enough in that all of them are under the dome rather than in its rim.
     */
    private static final double CHAMBER_RING = 0.55;

    /**
     * What one call to {@link #place} did, for the debug command and the log line.
     *
     * <p>{@code chambers} carries the positions rather than a count so the command can print them. That is
     * not decoration: the chambers are the colony's life, they are buried, and without being told where they
     * are the only way to find one is to dig the mound out block by block.
     *
     * @param blocks  positions the mound covers
     * @param written how many of them landed in a loaded chunk; the rest are owed to {@link PendingChunkEdits}
     */
    public record Result(int blocks, int written, List<BlockPos> chambers) {
    }

    /**
     * Hand this builder to the colony simulation. Called once at setup; before it, colonies materialise as
     * data only and nothing about the simulation changes.
     */
    public static void install() {
        ColonyManager.setNestBuilder(new GlyphidNest());
    }

    @Override
    public void build(ServerLevel level, Colony colony) {
        Result result = place(level, colony);
        LOGGER.debug("[wfballistics] built {}: {} blocks ({} owed), {} chambers",
                colony, result.blocks(), result.blocks() - result.written(), result.chambers().size());
    }

    /**
     * Stamp the mound, and tell the colony how many chambers it now lives on.
     */
    public static Result place(ServerLevel level, Colony colony) {
        return stamp(level, colony, true);
    }

    /**
     * The same measurement without writing anything, for reporting on a nest that is already standing.
     *
     * <p>Needed because building twice is not free: the second pass would queue a second copy of every block
     * owed to an unloaded chunk, doubling what the save file carries and what {@code colony} reports owed.
     */
    public static Result survey(ServerLevel level, Colony colony) {
        return stamp(level, colony, false);
    }

    private static Result stamp(ServerLevel level, Colony colony, boolean write) {
        if (!colony.hasResolvedY()) {
            return new Result(0, 0, List.of());
        }
        PendingChunkEdits edits = PendingChunkEdits.get(level);
        Block flesh = ModBlocks.GLYPHID_NEST.get();
        Block chamber = ModBlocks.GLYPHID_SPAWNER.get();

        int radius = colony.nestRadius();
        int height = Math.max(2, radius - 1);
        int floor = level.getMinBuildHeight() + FLOOR_MARGIN;

        List<BlockPos> placedChambers = chambers(level, colony);
        LongOpenHashSet chamberKeys = new LongOpenHashSet();
        placedChambers.forEach(pos -> chamberKeys.add(pos.asLong()));
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int blocks = 0;
        int written = 0;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distSq = dx * dx + dz * dz;
                if (distSq > radius * radius) {
                    continue;
                }
                int top = domeTop(distSq, radius, height);
                for (int dy = -radius; dy <= top; dy++) {
                    int y = colony.y + dy;
                    if (y < floor || y >= level.getMaxBuildHeight()) {
                        continue;
                    }
                    cursor.set(colony.x + dx, y, colony.z + dz);
                    boolean isChamber = chamberKeys.contains(cursor.asLong());
                    blocks++;
                    if (!write) {
                        written += level.hasChunk(cursor.getX() >> 4, cursor.getZ() >> 4) ? 1 : 0;
                    } else if (edits.submit(level, cursor, isChamber ? chamber : flesh)) {
                        written++;
                    }
                }
            }
        }

        if (write) {
            // Set from what was laid out rather than from the tier, so the count the colony's life is
            // measured in is the count of chambers that exist. A mound clipped by the world floor owes fewer.
            colony.spawners = placedChambers.size();
            ColonyRegistry.get(level).setDirty();
        }
        return new Result(blocks, written, placedChambers);
    }

    /**
     * Where a colony's chambers are: one at the apex, the rest on a ring, buried one layer under the skin.
     *
     * <p>Pure geometry, so it answers for a nest that was built ten sessions ago as readily as for one that
     * is about to be. That is what lets {@code colony nest} say where the chambers are without rebuilding
     * the mound to find out.
     *
     * <p>Deduplicated because at radius 2 the ring rounds onto itself, and two chambers in one block would
     * have the colony counting a life it does not have.
     */
    public static List<BlockPos> chambers(ServerLevel level, Colony colony) {
        if (!colony.hasResolvedY()) {
            return List.of();
        }
        int radius = colony.nestRadius();
        int height = Math.max(2, radius - 1);
        int floor = level.getMinBuildHeight() + FLOOR_MARGIN;

        LongOpenHashSet seen = new LongOpenHashSet();
        List<BlockPos> out = new ArrayList<>();
        int wanted = 1 + colony.tier;
        for (int i = 0; i < wanted; i++) {
            int dx = 0;
            int dz = 0;
            if (i > 0) {
                double angle = 2.0 * Math.PI * (i - 1) / (wanted - 1);
                dx = (int) Math.round(Math.cos(angle) * radius * CHAMBER_RING);
                dz = (int) Math.round(Math.sin(angle) * radius * CHAMBER_RING);
            }
            int y = colony.y + domeTop(dx * dx + dz * dz, radius, height) - 1;
            BlockPos pos = new BlockPos(colony.x + dx, y, colony.z + dz);
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
