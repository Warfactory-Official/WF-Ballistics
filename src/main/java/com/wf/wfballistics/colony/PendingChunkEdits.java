package com.wf.wfballistics.colony;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Block changes owed to chunks that are not loaded, applied the moment they are.
 *
 * <p>This is the answer to "the colony expanded while nobody was watching". Writing into an unloaded chunk
 * directly is the obvious approach and the wrong one: bypassing the chunk cache races anything already
 * resident in memory, and under C2ME the chunk pipeline is running in parallel, so the failure mode is a
 * corrupted region rather than a slow one. Loading the chunk to write it is safe but defeats the point,
 * which was to keep chunks unloaded.
 *
 * <p>So neither. A nest in an unloaded chunk needs no blocks, because nothing can see it — the colony record
 * is the truth and the blocks are only its representation where somebody is looking. Expansion writes an
 * intent here, costs nothing until the chunk loads on its own, and then materialises exactly once.
 *
 * <p>Edits are queued per chunk and drained in {@code ChunkEvent.Load} order. If a chunk is already loaded
 * when the edit is made, {@link #submit} applies it immediately instead of queueing.
 */
public final class PendingChunkEdits extends SavedData {

    public static final String NAME = "wfballistics_pending_edits";

    /**
     * Cap on how many edits one chunk may owe. A colony that somehow queues without bound would otherwise
     * turn into an unbounded save file; dropping the overflow loses cosmetic blocks, not colony state.
     */
    public static final int MAX_PER_CHUNK = 4096;

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Flags for the immediate write in {@link #submit}, and {@code UPDATE_KNOWN_SHAPE} is the one that matters.
     *
     * <p>Without it {@code setBlock} runs a shape update against all six neighbours, each of which reads a
     * block state, and a read that lands in an unloaded chunk <em>blocks the server thread on a chunk load</em>.
     * A nest is stamped from inside {@code ChunkEvent.Load}, where that is a deadlock: the thread waiting for
     * the chunk is the thread that would have loaded it. Not theorised — it hung a dev server solid the first
     * time a nest was built with an unloaded chunk beside it, and {@code jstack} had the whole chain in one
     * stack: {@code setBlock -> updateNeighbourShapes -> getBlockState -> getChunkBlocking}.
     *
     * <p>{@code UPDATE_CLIENTS} alone was never enough, whatever the old comment said: the flag that
     * suppresses neighbour <em>notification</em> is a different one from the flag that suppresses neighbour
     * <em>shape</em> updates, and only the first was being left out.
     */
    public static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /**
     * Chunk -> the edits it owes.
     */
    private final Long2ObjectOpenHashMap<List<Edit>> byChunk = new Long2ObjectOpenHashMap<>();

    /**
     * One owed block change.
     */
    public record Edit(long pos, ResourceLocation block) {

        public BlockState state() {
            Block resolved = BuiltInRegistries.BLOCK.getOptional(block).orElse(null);
            return resolved == null ? Blocks.AIR.defaultBlockState() : resolved.defaultBlockState();
        }
    }

    public static PendingChunkEdits get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(PendingChunkEdits::new, (tag, lookup) -> load(tag)), NAME);
    }

    /**
     * Set a block, now if the chunk is loaded and later if it is not.
     *
     * @return true if the write happened immediately
     */
    public boolean submit(ServerLevel level, BlockPos pos, Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return false;
        }
        ChunkPos chunkPos = new ChunkPos(pos);
        if (level.hasChunk(chunkPos.x, chunkPos.z)) {
            level.setBlock(pos, block.defaultBlockState(), FLAGS);
            return true;
        }

        List<Edit> queue = byChunk.computeIfAbsent(chunkPos.toLong(), k -> new ArrayList<>());
        if (queue.size() >= MAX_PER_CHUNK) {
            return false;
        }
        queue.add(new Edit(pos.asLong(), id));
        setDirty();
        return false;
    }

    /**
     * Apply and clear everything owed to a chunk. Called once as it loads.
     *
     * <p><b>Written through the chunk, not through the level</b>, and that is not a micro-optimisation.
     * {@code Level.setBlock} on the chunk currently being loaded reaches a blocking {@code getChunk} for that
     * same chunk by at least two routes — vanilla's neighbour shape update, and Lithium's hopper
     * update-suppression hook, which runs whatever flags are passed. On the server thread inside
     * {@code ChunkEvent.Load} either one is a deadlock: the thread waiting for the chunk is the thread that
     * would finish loading it. Both were caught by {@code jstack} on a dev server that hung solid, one after
     * the other, and the second is the reason the flags alone were not enough.
     *
     * <p>Writing into the {@link ChunkAccess} is what worldgen does and has none of that machinery. Nothing is
     * lost by skipping it: the chunk has not been sent to a client yet, so the block packet that carries it
     * has not been built.
     *
     * @return how many blocks were written
     */
    public int drain(ServerLevel level, ChunkAccess chunk) {
        ChunkPos chunkPos = chunk.getPos();
        List<Edit> queue = byChunk.remove(chunkPos.toLong());
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        setDirty();

        int written = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Edit edit : queue) {
            cursor.set(BlockPos.of(edit.pos()));
            chunk.setBlockState(cursor, edit.state(), false);
            written++;
        }
        chunk.setUnsaved(true);
        LOGGER.debug("[wfballistics] materialised {} pending blocks in chunk {}", written, chunkPos);
        return written;
    }

    public boolean owes(ChunkPos chunkPos) {
        return byChunk.containsKey(chunkPos.toLong());
    }

    public int pendingChunks() {
        return byChunk.size();
    }

    public int pendingBlocks() {
        int total = 0;
        for (List<Edit> queue : byChunk.values()) {
            total += queue.size();
        }
        return total;
    }

    // --- persistence ---

    public static PendingChunkEdits load(CompoundTag tag) {
        PendingChunkEdits edits = new PendingChunkEdits();
        ListTag chunks = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < chunks.size(); i++) {
            CompoundTag chunkTag = chunks.getCompound(i);
            long key = chunkTag.getLong("c");
            long[] positions = chunkTag.getLongArray("p");
            ListTag blocks = chunkTag.getList("b", Tag.TAG_STRING);

            int count = Math.min(positions.length, blocks.size());
            List<Edit> queue = new ArrayList<>(count);
            for (int j = 0; j < count; j++) {
                ResourceLocation id = ResourceLocation.tryParse(blocks.getString(j));
                if (id != null) {
                    queue.add(new Edit(positions[j], id));
                }
            }
            if (!queue.isEmpty()) {
                edits.byChunk.put(key, queue);
            }
        }
        return edits;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag chunks = new ListTag();
        for (var entry : byChunk.long2ObjectEntrySet()) {
            List<Edit> queue = entry.getValue();
            CompoundTag chunkTag = new CompoundTag();
            chunkTag.putLong("c", entry.getLongKey());

            long[] positions = new long[queue.size()];
            ListTag blocks = new ListTag();
            for (int i = 0; i < queue.size(); i++) {
                positions[i] = queue.get(i).pos();
                blocks.add(net.minecraft.nbt.StringTag.valueOf(queue.get(i).block().toString()));
            }
            chunkTag.putLongArray("p", positions);
            chunkTag.put("b", blocks);
            chunks.add(chunkTag);
        }
        tag.put("chunks", chunks);
        return tag;
    }
}
