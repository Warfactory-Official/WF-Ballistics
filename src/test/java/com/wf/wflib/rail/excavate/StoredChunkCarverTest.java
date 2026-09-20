package com.wf.wflib.rail.excavate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chunk tag holds several structures describing the same blocks, and the ones that are not block data are
 * where a carve turns into a corrupt save: an orphaned block entity comes back as a ghost block, and a
 * heightmap left behind describes terrain that is no longer there.
 */
class StoredChunkCarverTest {

    private static final ChunkPos ORIGIN = new ChunkPos(0, 0);

    /** A chunk tag with one all-stone section at y=0, heightmaps, and a lit flag. */
    private static CompoundTag chunk() {
        CompoundTag states = new CompoundTag();
        ListTag palette = new ListTag();
        CompoundTag stone = new CompoundTag();
        stone.putString(SectionNbt.NAME, "minecraft:stone");
        palette.add(stone);
        CompoundTag air = new CompoundTag();
        air.putString(SectionNbt.NAME, SectionNbt.AIR);
        palette.add(air);
        states.put(SectionNbt.PALETTE, palette);
        SimpleBitStorage storage = new SimpleBitStorage(4, SectionNbt.CELLS);
        states.put(SectionNbt.DATA, new LongArrayTag(storage.getRaw()));

        CompoundTag section = new CompoundTag();
        section.putByte(StoredChunkCarver.SECTION_Y, (byte) 0);
        section.put(SectionNbt.BLOCK_STATES, states);

        ListTag sections = new ListTag();
        sections.add(section);

        CompoundTag chunk = new CompoundTag();
        chunk.put(StoredChunkCarver.SECTIONS, sections);
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.put("MOTION_BLOCKING", new LongArrayTag(new long[37]));
        chunk.put(StoredChunkCarver.HEIGHTMAPS, heightmaps);
        chunk.putBoolean(StoredChunkCarver.IS_LIGHT_ON, true);
        return chunk;
    }

    private static void blockEntity(CompoundTag chunk, int x, int y, int z) {
        ListTag list = chunk.contains(StoredChunkCarver.BLOCK_ENTITIES, Tag.TAG_LIST)
                ? chunk.getList(StoredChunkCarver.BLOCK_ENTITIES, Tag.TAG_COMPOUND)
                : new ListTag();
        CompoundTag be = new CompoundTag();
        be.putString("id", "minecraft:chest");
        be.putInt("x", x);
        be.putInt("y", y);
        be.putInt("z", z);
        list.add(be);
        chunk.put(StoredChunkCarver.BLOCK_ENTITIES, list);
    }

    private static CarveVolume box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        return CarveVolume.box(new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ));
    }

    @Test
    @DisplayName("a carve clears the cells inside it and counts them")
    void clearsInsideOnly() {
        CompoundTag chunk = chunk();
        StoredChunkCarver.Outcome outcome =
                StoredChunkCarver.carve(chunk, ORIGIN, box(0, 0, 0, 3, 3, 3), LightingPolicy.DEFERRED);
        assertEquals(64, outcome.cellsCleared());
        assertEquals(1, outcome.sectionsTouched());
        assertTrue(outcome.refusals().isEmpty());
        assertTrue(outcome.changed());
    }

    @Test
    @DisplayName("block entities inside the volume are pruned, and ones outside are kept")
    void prunesOnlyEnclosedBlockEntities() {
        CompoundTag chunk = chunk();
        blockEntity(chunk, 2, 2, 2);
        blockEntity(chunk, 9, 9, 9);
        StoredChunkCarver.Outcome outcome =
                StoredChunkCarver.carve(chunk, ORIGIN, box(0, 0, 0, 3, 3, 3), LightingPolicy.DEFERRED);
        assertEquals(1, outcome.blockEntitiesRemoved());
        ListTag left = chunk.getList(StoredChunkCarver.BLOCK_ENTITIES, Tag.TAG_COMPOUND);
        assertEquals(1, left.size());
        assertEquals(9, left.getCompound(0).getInt("x"));
    }

    @Test
    @DisplayName("heightmaps are dropped so they are recomputed on load")
    void dropsHeightmaps() {
        CompoundTag chunk = chunk();
        StoredChunkCarver.carve(chunk, ORIGIN, box(0, 0, 0, 3, 3, 3), LightingPolicy.DEFERRED);
        assertFalse(chunk.contains(StoredChunkCarver.HEIGHTMAPS));
    }

    @Test
    @DisplayName("deferred lighting marks the chunk for relight, keep does not")
    void lightingPolicyDecidesTheLitFlag() {
        CompoundTag deferred = chunk();
        StoredChunkCarver.carve(deferred, ORIGIN, box(0, 0, 0, 3, 3, 3), LightingPolicy.DEFERRED);
        assertFalse(deferred.getBoolean(StoredChunkCarver.IS_LIGHT_ON));

        CompoundTag kept = chunk();
        StoredChunkCarver.carve(kept, ORIGIN, box(0, 0, 0, 3, 3, 3), LightingPolicy.KEEP);
        assertTrue(kept.getBoolean(StoredChunkCarver.IS_LIGHT_ON));
    }

    @Test
    @DisplayName("a volume that misses this chunk leaves the tag completely alone")
    void chunkOutsideTheVolumeIsUntouched() {
        CompoundTag chunk = chunk();
        StoredChunkCarver.Outcome outcome =
                StoredChunkCarver.carve(chunk, ORIGIN, box(64, 0, 64, 70, 3, 70), LightingPolicy.DEFERRED);
        assertFalse(outcome.changed());
        assertTrue(chunk.contains(StoredChunkCarver.HEIGHTMAPS));
        assertTrue(chunk.getBoolean(StoredChunkCarver.IS_LIGHT_ON));
    }

    @Test
    @DisplayName("a carve that clears nothing does not invalidate anything either")
    void carvingAirChangesNothing() {
        CompoundTag chunk = chunk();
        // Clear it once, then carve the same volume again: the second pass finds air and must be a no-op.
        StoredChunkCarver.carve(chunk, ORIGIN, box(0, 0, 0, 3, 3, 3), LightingPolicy.DEFERRED);
        chunk.putBoolean(StoredChunkCarver.IS_LIGHT_ON, true);
        CompoundTag heightmaps = new CompoundTag();
        heightmaps.put("MOTION_BLOCKING", new LongArrayTag(new long[37]));
        chunk.put(StoredChunkCarver.HEIGHTMAPS, heightmaps);

        StoredChunkCarver.Outcome again =
                StoredChunkCarver.carve(chunk, ORIGIN, box(0, 0, 0, 3, 3, 3), LightingPolicy.DEFERRED);
        assertFalse(again.changed());
        assertTrue(chunk.contains(StoredChunkCarver.HEIGHTMAPS));
        assertTrue(chunk.getBoolean(StoredChunkCarver.IS_LIGHT_ON));
    }

    @Test
    @DisplayName("sections outside the volume's y range are skipped, not walked")
    void sectionsOutsideTheYRangeAreSkipped() {
        CompoundTag chunk = chunk();
        StoredChunkCarver.Outcome outcome =
                StoredChunkCarver.carve(chunk, ORIGIN, box(0, 64, 0, 3, 70, 3), LightingPolicy.DEFERRED);
        assertEquals(0, outcome.sectionsTouched());
        assertFalse(outcome.changed());
    }
}
