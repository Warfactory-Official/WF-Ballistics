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

    @Test
    @DisplayName("the lining pass puts the wall in and the bore pass takes the inside out")
    void liningAndBoring() {
        CompoundTag chunk = chunk();
        // A short tunnel well inside one chunk, so the whole shell is in this column.
        CarveVolume.Corridor bore = CarveVolume.corridor(
                new double[]{4.0, 12.0}, new double[]{8.0, 8.0}, 4, 2, 4);
        CarvePlan plan = CarvePlan.tunnel(bore, "minecraft:deepslate_bricks", LightingPolicy.DEFERRED);

        StoredChunkCarver.Outcome lining =
                StoredChunkCarver.carve(chunk, ORIGIN, plan.at(CarvePlan.Stage.LINE));
        assertTrue(lining.cellsLined() > 0, "the wall went in");
        assertEquals(0, lining.cellsCleared(), "and nothing came out on that pass");

        StoredChunkCarver.Outcome boring =
                StoredChunkCarver.carve(chunk, ORIGIN, plan.at(CarvePlan.Stage.BORE));
        assertTrue(boring.cellsCleared() > 0, "the inside came out");
        assertEquals(0, boring.cellsLined(), "and no more wall went in on that pass");
        assertTrue(boring.changed());

        // Every cell of the bore is air and every cell of the shell is the lining, in the stored tag.
        var box = plan.bounds();
        for (int x = Math.max(0, box.minX()); x <= Math.min(15, box.maxX()); x++) {
            for (int y = Math.max(0, box.minY()); y <= Math.min(15, box.maxY()); y++) {
                for (int z = Math.max(0, box.minZ()); z <= Math.min(15, box.maxZ()); z++) {
                    String at = nameAt(chunk, x, y, z);
                    if (bore.contains(x, y, z)) {
                        assertEquals(SectionNbt.AIR, at, "bore at " + x + "," + y + "," + z);
                    } else if (plan.shell().contains(x, y, z)) {
                        assertEquals("minecraft:deepslate_bricks", at,
                                "lining at " + x + "," + y + "," + z);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the dewatering pass changes no blocks at all in a stored chunk")
    void dewateringAStoredChunkDoesNothing() {
        CompoundTag chunk = chunk();
        CarveVolume.Corridor bore = CarveVolume.corridor(
                new double[]{4.0, 12.0}, new double[]{8.0, 8.0}, 4, 2, 4);
        CarvePlan plan = CarvePlan.tunnel(bore, "minecraft:deepslate_bricks", LightingPolicy.DEFERRED);
        StoredChunkCarver.Outcome outcome =
                StoredChunkCarver.carve(chunk, ORIGIN, plan.at(CarvePlan.Stage.DEWATER));
        assertFalse(outcome.changed(), "a chunk on disk has no fluid simulation to settle");
        assertTrue(chunk.contains(StoredChunkCarver.HEIGHTMAPS), "and so nothing to invalidate");
    }

    @Test
    @DisplayName("a lining replacing a chest takes its block entity with it")
    void liningPrunesBlockEntities() {
        CompoundTag chunk = chunk();
        CarveVolume.Corridor bore = CarveVolume.corridor(
                new double[]{4.0, 12.0}, new double[]{8.0, 8.0}, 4, 2, 4);
        CarvePlan plan = CarvePlan.tunnel(bore, "minecraft:deepslate_bricks", LightingPolicy.DEFERRED);
        // A block entity in the wall rather than in the bore: the old carve only pruned the bore.
        int wallZ = 8;
        while (bore.contains(8, 2, wallZ)) {
            wallZ++;
        }
        assertTrue(plan.shell().contains(8, 2, wallZ), "picked a cell that really is in the wall");
        blockEntity(chunk, 8, 2, wallZ);

        StoredChunkCarver.Outcome outcome =
                StoredChunkCarver.carve(chunk, ORIGIN, plan.at(CarvePlan.Stage.LINE));
        assertEquals(1, outcome.blockEntitiesRemoved(),
                "a block entity under the lining is orphaned exactly as one in the bore is");
    }

    /** The block name stored at one cell of the chunk's y=0 section. */
    private static String nameAt(CompoundTag chunk, int x, int y, int z) {
        CompoundTag section = chunk.getList(StoredChunkCarver.SECTIONS, Tag.TAG_COMPOUND).getCompound(0);
        CompoundTag states = section.getCompound(SectionNbt.BLOCK_STATES);
        ListTag palette = states.getList(SectionNbt.PALETTE, Tag.TAG_COMPOUND);
        if (!states.contains(SectionNbt.DATA, Tag.TAG_LONG_ARRAY)) {
            return palette.getCompound(0).getString(SectionNbt.NAME);
        }
        long[] data = states.getLongArray(SectionNbt.DATA);
        int bits = SectionNbt.bitsForPalette(palette.size());
        SimpleBitStorage storage = new SimpleBitStorage(bits, SectionNbt.CELLS, data);
        return palette.getCompound(storage.get(SectionNbt.index(x, y, z))).getString(SectionNbt.NAME);
    }
}
