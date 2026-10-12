package com.wf.wflib.build;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Decoded form of whatever {@link BlueprintFormat} read it; {@code palette[0]} always air.
 *
 * @param blockEntities count only, never placed: chest contents would duplicate items.
 * @param unresolved palette entries for blocks this server lacks; load as air.
 */
public record Blueprint(String name, Vec3i size, List<BlockState> palette, int[] cells,
                        int blockEntities, int unresolved) {

    public static final int MAX_VOLUME = 256 * 256 * 256;
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

    /** Y-major, then Z, then X: Litematica's own order, so the commonest loader needs no reshuffling. */
    public int index(int x, int y, int z) {
        return (y * this.size.getZ() + z) * this.size.getX() + x;
    }

    public boolean contains(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0
                && x < this.size.getX() && y < this.size.getY() && z < this.size.getZ();
    }

    public BlockState at(int x, int y, int z) {
        if (!this.contains(x, y, z)) {
            return Blocks.AIR.defaultBlockState();
        }
        return this.palette.get(this.cells[this.index(x, y, z)]);
    }

    public int volume() {
        return this.cells.length;
    }

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
