package com.wf.wflib.build;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * A volume of block states to build, in whatever shape it was on disk before a {@link BlueprintFormat} got hold of
 * it.
 *
 * @param name what to call it, from the file's own metadata where it has any
 * @param size extents, always positive
 * @param palette distinct states, index 0 being air
 * @param cells one palette index per cell, in {@link #index} order
 * @param blockEntities how many block entities the source held. Recorded rather than kept: a chest placed
 *      with its contents is item duplication, so nothing here reproduces them, and the count
 *      exists so {@code blueprint info} can say so out loud instead of quietly dropping them
 * @param unresolved palette entries naming a block this server does not have. They load as air, and a
 *      build with holes in it should be able to explain why
 */
public record Blueprint(String name, Vec3i size, List<BlockState> palette, int[] cells,
                        int blockEntities, int unresolved) {

    /** Most cells one blueprint may have. */
    public static final int MAX_VOLUME = 256 * 256 * 256;
    /** Most distinct states one blueprint may have. */
    public static final int MAX_PALETTE = 4096;

    public Blueprint {
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0) {
            throw new IllegalArgumentException("blueprint size must be positive, got " + size);
        }
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (volume != cells.length) {
            throw new IllegalArgumentException("blueprint " + size + " needs " + volume
                    + " cells, got " + cells.length);
        }
        if (palette.isEmpty() || !palette.get(0).isAir()) {
            throw new IllegalArgumentException("blueprint palette index 0 must be air");
        }
        palette = List.copyOf(palette);
    }

    /**
     * @return where cell {@code (x, y, z)} lives in {@link #cells}. Y-major, then Z, then X: Litematica's own
     *      order, kept so the commonest loader can copy a region in without reshuffling it
     */
    public int index(int x, int y, int z) {
        return (y * this.size.getZ() + z) * this.size.getX() + x;
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0
                && x < this.size.getX() && y < this.size.getY() && z < this.size.getZ();
    }

    /**
     * @return the state at this cell, or air if it is empty or outside the volume.
     */
    public BlockState at(int x, int y, int z) {
        if (!this.contains(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        return this.palette.get(this.cells[this.index(x, y, z)]);
    }

    public int volume() {
        return this.cells.length;
    }

    /**
     * @return how many cells actually have something in them.
     */
    public int blocks() {
        int count = 0;
        for (int cell : this.cells) {
            if (cell != 0) {
                count++;
            }
        }
        return count;
    }
}
