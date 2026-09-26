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
     * @param cellsLined blocks turned into the tunnel lining
     * @param sectionsTouched sections actually rewritten
     * @param blockEntitiesRemoved orphaned block entities pruned
     * @param refusals sections left alone because their stored layout was not understood
     */
    public record Outcome(int cellsCleared, int cellsLined, int sectionsTouched, int blockEntitiesRemoved,
                          List<String> refusals) {

        public Outcome(int cellsCleared, int sectionsTouched, int blockEntitiesRemoved, List<String> refusals) {
            this(cellsCleared, 0, sectionsTouched, blockEntitiesRemoved, refusals);
        }

        /** @return whether anything changed and the tag is worth storing. */
        public boolean changed() {
            return this.cellsCleared > 0 || this.cellsLined > 0 || this.blockEntitiesRemoved > 0;
        }
    }

    /**
     * Carve this chunk's stored tag.
     *
     * @param chunkTag the chunk's NBT, mutated in place
     * @param pos which chunk it is, so the volume can be tested in world coordinates
     */
    public static Outcome carve(CompoundTag chunkTag, ChunkPos pos, CarveVolume volume,
                                LightingPolicy lighting) {
        return carve(chunkTag, pos, CarvePlan.open(volume, lighting));
    }

    /**
     * Carve this chunk's stored tag.
     *
     * @param chunkTag the chunk's NBT, mutated in place
     * @param pos which chunk it is, so the volume can be tested in world coordinates
     */
    public static Outcome carve(CompoundTag chunkTag, ChunkPos pos, CarvePlan plan) {
        BoundingBox box = plan.bounds();
        int originX = pos.getMinBlockX();
        int originZ = pos.getMinBlockZ();
        if (box.maxX() < originX || box.minX() > originX + 15
                || box.maxZ() < originZ || box.minZ() > originZ + 15) {
            return new Outcome(0, 0, 0, List.of());
        }

        ListTag sections = chunkTag.getList(SECTIONS, Tag.TAG_COMPOUND);
        int cleared = 0;
        int lined = 0;
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
            boolean rewritten = false;

            // One stage per submission, exactly as the attended path does it. A stored chunk has no
            // fluid simulation and could safely do both at once, but the two paths behaving differently
            // is the kind of difference nobody finds until an aquifer does.
            if (plan.blockFor() != null && plan.volumeFor() != null) {
                SectionNbt.Result placed = SectionNbt.fill(section,
                        mask(plan.volumeFor(), originX, baseY, originZ), plan.blockFor());
                if (placed.refusal() != null) {
                    refusals.add("section y=" + section.getByte(SECTION_Y) + " "
                            + plan.stage().name().toLowerCase(java.util.Locale.ROOT) + ": "
                            + placed.refusal());
                    continue;
                }
                lined += placed.cleared();
                rewritten |= placed.rewritten();
            }

            if (plan.stage() == CarvePlan.Stage.BORE) {
                SectionNbt.Result result =
                        SectionNbt.clear(section, mask(plan.bore(), originX, baseY, originZ));
                if (result.refusal() != null) {
                    refusals.add("section y=" + section.getByte(SECTION_Y) + ": " + result.refusal());
                    continue;
                }
                cleared += result.cleared();
                rewritten |= result.rewritten();
            }

            if (rewritten) {
                touched++;
            }
        }

        // The lining replaces blocks as well as the bore removing them, so a chest in a wall orphans
        // its block entity exactly as one in the bore does.
        int pruned = 0;
        if (plan.stage() == CarvePlan.Stage.BORE) {
            pruned += pruneBlockEntities(chunkTag, plan.bore());
        }
        if (plan.stage() == CarvePlan.Stage.LINE && plan.lined()) {
            pruned += pruneBlockEntities(chunkTag, plan.shell());
        }

        if (cleared > 0 || lined > 0 || pruned > 0) {
            // Heightmaps now describe terrain that is gone; absent ones are recomputed when the chunk loads.
            chunkTag.remove(HEIGHTMAPS);
            if (plan.lighting().invalidates()) {
                chunkTag.putBoolean(IS_LIGHT_ON, false);
            }
        }
        return new Outcome(cleared, lined, touched, pruned, refusals);
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
