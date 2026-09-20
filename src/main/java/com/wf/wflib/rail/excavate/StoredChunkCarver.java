package com.wf.wflib.rail.excavate;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/** Applies a {@link CarveVolume} to one chunk's stored NBT, off-thread. */
public final class StoredChunkCarver {

    public static final String SECTIONS = "sections";
    public static final String SECTION_Y = "Y";
    public static final String BLOCK_ENTITIES = "block_entities";
    public static final String HEIGHTMAPS = "Heightmaps";
    public static final String IS_LIGHT_ON = "isLightOn";

    private StoredChunkCarver() {
    }

    /**
     * @param cellsCleared blocks turned to air
     * @param sectionsTouched sections actually rewritten
     * @param blockEntitiesRemoved orphaned block entities pruned
     * @param refusals sections left alone because their stored layout was not understood
     */
    public record Outcome(int cellsCleared, int sectionsTouched, int blockEntitiesRemoved, List<String> refusals) {

        /** @return whether anything changed and the tag is worth storing. */
        public boolean changed() {
            return this.cellsCleared > 0 || this.blockEntitiesRemoved > 0;
        }
    }

    /**
     * Carve this chunk's stored tag.
     *
     * @param chunkTag the chunk's NBT, mutated in place
     * @param pos which chunk it is, so the volume can be tested in world coordinates
     */
    public static Outcome carve(CompoundTag chunkTag, ChunkPos pos, CarveVolume volume, LightingPolicy lighting) {
        BoundingBox box = volume.bounds();
        int originX = pos.getMinBlockX();
        int originZ = pos.getMinBlockZ();
        if (box.maxX() < originX || box.minX() > originX + 15
                || box.maxZ() < originZ || box.minZ() > originZ + 15) {
            return new Outcome(0, 0, 0, List.of());
        }

        ListTag sections = chunkTag.getList(SECTIONS, Tag.TAG_COMPOUND);
        int cleared = 0;
        int touched = 0;
        List<String> refusals = new ArrayList<>();

        for (int i = 0; i < sections.size(); i++) {
            CompoundTag section = sections.getCompound(i);
            if (!section.contains(SECTION_Y)) {
                continue;
            }
            int baseY = section.getByte(SECTION_Y) * 16;
            if (baseY + 15 < box.minY() || baseY > box.maxY()) {
                continue;
            }
            SectionNbt.Result result = SectionNbt.clear(section, mask(volume, originX, baseY, originZ));
            if (result.refusal() != null) {
                refusals.add("section y=" + section.getByte(SECTION_Y) + ": " + result.refusal());
                continue;
            }
            if (result.rewritten()) {
                cleared += result.cleared();
                touched++;
            }
        }

        int pruned = pruneBlockEntities(chunkTag, volume);

        if (cleared > 0 || pruned > 0) {
            // Heightmaps now describe terrain that is gone; absent ones are recomputed when the chunk loads.
            chunkTag.remove(HEIGHTMAPS);
            if (lighting.invalidates()) {
                chunkTag.putBoolean(IS_LIGHT_ON, false);
            }
        }
        return new Outcome(cleared, touched, pruned, refusals);
    }

    /** Translate a section's local cells into world coordinates for the volume test. */
    private static SectionNbt.CellMask mask(CarveVolume volume, int originX, int baseY, int originZ) {
        return (x, y, z) -> volume.contains(originX + x, baseY + y, originZ + z);
    }

    /** Drop block entities whose block was carved away. */
    private static int pruneBlockEntities(CompoundTag chunkTag, CarveVolume volume) {
        if (!chunkTag.contains(BLOCK_ENTITIES, Tag.TAG_LIST)) {
            return 0;
        }
        ListTag list = chunkTag.getList(BLOCK_ENTITIES, Tag.TAG_COMPOUND);
        int removed = 0;
        for (int i = list.size() - 1; i >= 0; i--) {
            CompoundTag be = list.getCompound(i);
            if (volume.contains(be.getInt("x"), be.getInt("y"), be.getInt("z"))) {
                list.remove(i);
                removed++;
            }
        }
        return removed;
    }
}
