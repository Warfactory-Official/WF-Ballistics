package com.wf.wfballistics.industry;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-level record of provocative industry: where it is, and what it is worth.
 *
 * <p>Two indexes over the same facts. {@code machines} is the ground truth, keyed by full block position;
 * {@code cells} is a derived sum per region cell, which is what the colony simulation actually reads. The
 * derived index is maintained incrementally on every add and remove, so querying regional pressure is a
 * map lookup rather than a scan.
 *
 * <p>Deliberately different from wfcore's radar registry in two ways, both of which are fine for a radar
 * and not for something that decides whether a base gets attacked:
 * <ul>
 *   <li><b>Keys are 3D.</b> The radar packs {@code (x,z)}, so a machine placed above another overwrites
 *       it and breaking the upper one deregisters the lower. A radar wants a footprint and does not care.
 *       Here it would undercount tall bases and make stacking machines a way to suppress aggression.</li>
 *   <li><b>Chunks get backfilled.</b> Block-place events miss worldgen, structures, KubeJS, AE2 and
 *       {@code /setblock}, and every machine that predates this feature. {@link #isScanned} lets
 *       {@link IndustryTracker} sweep each chunk exactly once, ever, so an existing base is not invisible.</li>
 * </ul>
 */
public final class IndustryRegistry extends SavedData {

    public static final String NAME = "wfballistics_industry";

    /**
     * Block position -> value. The ground truth; everything else is derived from it.
     */
    private final Long2IntOpenHashMap machines = new Long2IntOpenHashMap();
    /**
     * Region cell -> summed value. Derived, rebuilt whenever the cell size changes.
     */
    private final Long2IntOpenHashMap cells = new Long2IntOpenHashMap();
    /**
     * Chunks already swept for pre-existing machines, so the backfill is once per chunk forever.
     */
    private final LongOpenHashSet scanned = new LongOpenHashSet();

    /**
     * Cell size (in chunks) the {@link #cells} index was built with, so a config change can be detected.
     */
    private int cellChunks;

    public IndustryRegistry() {
        machines.defaultReturnValue(0);
        cells.defaultReturnValue(0);
        this.cellChunks = IndustryConfig.cellChunks();
    }

    public static IndustryRegistry get(ServerLevel level) {
        IndustryRegistry registry = level.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(IndustryRegistry::new, (tag, lookup) -> load(tag)), NAME);
        registry.reindexIfCellSizeChanged();
        return registry;
    }

    // --- cell maths ---

    /**
     * @return the cell a block position falls in, packed into a long.
     */
    public static long cellOf(int blockX, int blockZ, int cellChunks) {
        int span = cellChunks * 16;
        int cellX = Math.floorDiv(blockX, span);
        int cellZ = Math.floorDiv(blockZ, span);
        return packCell(cellX, cellZ);
    }

    public static long packCell(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    public static int cellX(long packed) {
        return (int) (packed >> 32);
    }

    public static int cellZ(long packed) {
        return (int) packed;
    }

    public int cellChunks() {
        return cellChunks;
    }

    /**
     * @return the world-space centre of a cell.
     */
    public int cellCenterX(long packed) {
        int span = cellChunks * 16;
        return cellX(packed) * span + span / 2;
    }

    public int cellCenterZ(long packed) {
        int span = cellChunks * 16;
        return cellZ(packed) * span + span / 2;
    }

    // --- mutation ---

    /**
     * Record a machine. Replacing an existing entry adjusts the cell sum by the difference, so a value
     * change (config reload, wfcore reporting a better number) cannot drift the derived index.
     */
    public void add(BlockPos pos, int value) {
        if (value <= 0) {
            remove(pos);
            return;
        }
        int previous = machines.put(pos.asLong(), value);
        addToCell(pos.getX(), pos.getZ(), value - previous);
        setDirty();
    }

    public void remove(BlockPos pos) {
        int previous = machines.remove(pos.asLong());
        if (previous != 0) {
            addToCell(pos.getX(), pos.getZ(), -previous);
            setDirty();
        }
    }

    private void addToCell(int blockX, int blockZ, int delta) {
        if (delta == 0) {
            return;
        }
        long cell = cellOf(blockX, blockZ, cellChunks);
        int updated = cells.get(cell) + delta;
        if (updated > 0) {
            cells.put(cell, updated);
        } else {
            cells.remove(cell);
        }
    }

    // --- queries ---

    /**
     * @return accumulated provocation in the cell containing this position.
     */
    public int pressureAt(int blockX, int blockZ) {
        return cells.get(cellOf(blockX, blockZ, cellChunks));
    }

    /**
     * @return provocation reaching this position from within {@code radiusBlocks}, falling off linearly
     * with distance.
     *
     * <p>The radius is explicit rather than "the neighbouring cells" on purpose. Sampling a fixed ring of
     * cells silently ties how far industry can be smelled to the cell size, so changing the cell size for
     * performance reasons would quietly change the gameplay — and a colony 850 blocks from a factory would
     * be oblivious for no reason a reader could see.
     */
    public int pressureWithin(int blockX, int blockZ, int radiusBlocks) {
        int span = cellChunks * 16;
        int reach = Math.max(1, (int) Math.ceil(radiusBlocks / (double) span));
        long own = cellOf(blockX, blockZ, cellChunks);
        int originX = cellX(own);
        int originZ = cellZ(own);

        double total = 0.0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int value = cells.get(packCell(originX + dx, originZ + dz));
                if (value == 0) {
                    continue;
                }
                double centreX = (originX + dx) * span + span / 2.0;
                double centreZ = (originZ + dz) * span + span / 2.0;
                double distance = Math.sqrt(Math.pow(centreX - blockX, 2) + Math.pow(centreZ - blockZ, 2));
                if (distance <= radiusBlocks) {
                    total += value * (1.0 - distance / radiusBlocks);
                }
            }
        }
        return (int) Math.round(total);
    }

    /**
     * @return every occupied cell and its value. This is what the colony simulation reads; it is orders of
     * magnitude smaller than {@link #machineCount()}, which is what makes clustering over it cheap.
     */
    public Long2IntMap cells() {
        return cells;
    }

    public int machineCount() {
        return machines.size();
    }

    public int totalValue() {
        int total = 0;
        for (Long2IntMap.Entry entry : cells.long2IntEntrySet()) {
            total += entry.getIntValue();
        }
        return total;
    }

    // --- chunk backfill bookkeeping ---

    /**
     * Drop every machine inside these chunks, and forget that they were ever swept.
     *
     * <p>One pass over the whole map rather than a lookup per chunk: a rescan usually covers hundreds of
     * chunks, and scanning the map once beats probing it once per chunk.
     */
    public int forgetChunks(LongOpenHashSet chunkPositions) {
        int removed = 0;
        var iterator = machines.long2IntEntrySet().iterator();
        while (iterator.hasNext()) {
            Long2IntMap.Entry entry = iterator.next();
            BlockPos pos = BlockPos.of(entry.getLongKey());
            long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunkPositions.contains(chunk)) {
                addToCell(pos.getX(), pos.getZ(), -entry.getIntValue());
                iterator.remove();
                removed++;
            }
        }
        scanned.removeAll(chunkPositions);
        if (removed > 0) {
            setDirty();
        }
        return removed;
    }

    public boolean isScanned(ChunkPos pos) {
        return scanned.contains(pos.toLong());
    }

    public void markScanned(ChunkPos pos) {
        if (scanned.add(pos.toLong())) {
            setDirty();
        }
    }

    // --- derived index maintenance ---

    /**
     * Rebuild the cell index if the configured cell size no longer matches the one it was built with.
     */
    private void reindexIfCellSizeChanged() {
        int configured = IndustryConfig.cellChunks();
        if (configured == cellChunks && !cells.isEmpty()) {
            return;
        }
        if (configured == cellChunks && machines.isEmpty()) {
            return;
        }
        cellChunks = configured;
        reindex();
    }

    private void reindex() {
        cells.clear();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Long2IntMap.Entry entry : machines.long2IntEntrySet()) {
            cursor.set(BlockPos.of(entry.getLongKey()));
            addToCell(cursor.getX(), cursor.getZ(), entry.getIntValue());
        }
        setDirty();
    }

    // --- persistence ---

    /**
     * Stored as parallel primitive arrays rather than a list of compounds: a megabase is tens of thousands
     * of entries, and one {@code CompoundTag} each is both far larger on disk and far slower to write.
     */
    public static IndustryRegistry load(CompoundTag tag) {
        IndustryRegistry registry = new IndustryRegistry();

        long[] positions = tag.getLongArray("positions");
        int[] values = tag.getIntArray("values");
        int count = Math.min(positions.length, values.length);
        for (int i = 0; i < count; i++) {
            if (values[i] > 0) {
                registry.machines.put(positions[i], values[i]);
            }
        }

        registry.scanned.addAll(java.util.Arrays.stream(tag.getLongArray("scanned")).boxed().toList());
        registry.cellChunks = IndustryConfig.cellChunks();
        registry.reindex();
        return registry;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        long[] positions = new long[machines.size()];
        int[] values = new int[machines.size()];
        int i = 0;
        for (Long2IntMap.Entry entry : machines.long2IntEntrySet()) {
            positions[i] = entry.getLongKey();
            values[i] = entry.getIntValue();
            i++;
        }
        tag.putLongArray("positions", positions);
        tag.putIntArray("values", values);
        tag.putLongArray("scanned", scanned.toLongArray());
        return tag;
    }
}
