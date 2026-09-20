package com.wf.wflib.rail.excavate;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The attended carve: ordinary block breaks in a chunk that is loaded, spread over as many ticks as the budget
 * needs.
 */
public final class LoadedChunkCarver {

    private final CarveVolume volume;
    private final BoundingBox box;
    private final int width;
    private final int height;
    private final int depth;
    private int cursor;
    private int broken;

    /** @param pos the chunk to carve; the volume is clipped to it. */
    public LoadedChunkCarver(CarveVolume volume, ChunkPos pos, ServerLevel level) {
        this.volume = volume;
        BoundingBox v = volume.bounds();
        this.box = new BoundingBox(
                Math.max(v.minX(), pos.getMinBlockX()),
                Math.max(v.minY(), level.getMinBuildHeight()),
                Math.max(v.minZ(), pos.getMinBlockZ()),
                Math.min(v.maxX(), pos.getMaxBlockX()),
                Math.min(v.maxY(), level.getMaxBuildHeight() - 1),
                Math.min(v.maxZ(), pos.getMaxBlockZ()));
        this.width = Math.max(0, this.box.maxX() - this.box.minX() + 1);
        this.height = Math.max(0, this.box.maxY() - this.box.minY() + 1);
        this.depth = Math.max(0, this.box.maxZ() - this.box.minZ() + 1);
    }

    /** @return how many blocks this carver has actually broken. */
    public int broken() {
        return this.broken;
    }

    /**
     * Break up to {@code budget} blocks.
     *
     * @return true once the whole volume in this chunk has been visited
     */
    public boolean advance(ServerLevel level, int budget, boolean drop) {
        WorldThread.assertOn("breaking blocks for an attended carve");
        int cells = this.width * this.height * this.depth;
        if (cells <= 0) {
            return true;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int spent = 0;
        while (this.cursor < cells && spent < budget) {
            int i = this.cursor++;
            int x = this.box.minX() + i % this.width;
            int y = this.box.minY() + (i / this.width) % this.height;
            int z = this.box.minZ() + i / (this.width * this.height);
            if (!this.volume.contains(x, y, z)) {
                continue;
            }
            at.set(x, y, z);
            if (level.getBlockState(at).isAir()) {
                continue;
            }
            // destroyBlock, not setBlock: block entities need emptying and neighbours need telling.
            level.destroyBlock(at, drop);
            this.broken++;
            spent++;
        }
        return this.cursor >= cells;
    }
}
