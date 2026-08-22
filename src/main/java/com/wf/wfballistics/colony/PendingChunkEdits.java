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
            level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
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
     * @return how many blocks were written
     */
    public int drain(ServerLevel level, ChunkPos chunkPos) {
        List<Edit> queue = byChunk.remove(chunkPos.toLong());
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        setDirty();

        int written = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Edit edit : queue) {
            cursor.set(BlockPos.of(edit.pos()));
            // UPDATE_CLIENTS without neighbour updates: a nest materialising should not set off a cascade
            // of block updates across a chunk that has only just come back.
            level.setBlock(cursor, edit.state(), Block.UPDATE_CLIENTS);
            written++;
        }
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
