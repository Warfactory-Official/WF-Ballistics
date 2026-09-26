package com.wf.wflib.rail.excavate;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The attended carve: ordinary world edits in a chunk that is loaded, spread over as many ticks as the
 * budget needs.
 *
 * <p>One {@link CarvePlan.Stage} per carver. The ordering that keeps water out is between whole passes
 * over the whole tunnel rather than inside a column, because this is the path that really does change
 * the world a few blocks at a time with fluid ticking between them, and a column that is lined, cut and
 * dry next to a column still full of lake is a column that floods through the join.</p>
 */
public final class LoadedChunkCarver {

    /**
     * Notify clients, and tell neighbours.
     *
     * <p>Deliberately the ordinary flags rather than a silent write. Suppressing neighbour updates would
     * stop water noticing a gap in the lining, which would hide exactly the defect this ordering exists
     * to prevent: a leak would sit there looking sealed until something unrelated poked it.</p>
     */
    private static final int LINING_FLAGS = Block.UPDATE_ALL;


    /**
     * Tell the clients, tell nobody else.
     *
     * <p>Used only for taking fluid out of the inside of a sealed tunnel. Notifying the neighbours there
     * schedules the rest of the pool to flow into the cells this sweep has already cleared, behind its
     * own back, and the tunnel finishes with water in the half the machine passed first.</p>
     */
    private static final int FLUID_FLAGS = Block.UPDATE_CLIENTS;


    private final CarvePlan plan;
    /** The block this pass puts down, or null for a pass that only removes. */
    private final BlockState placing;
    private final BoundingBox box;
    private final int width;
    private final int height;
    private final int depth;
    private int cursor;
    private int broken;
    private int placed;
    private int dried;

    /** @param pos the chunk to carve; the plan is clipped to it. */
    public LoadedChunkCarver(CarvePlan plan, ChunkPos pos, ServerLevel level) {
        this.plan = plan;
        this.placing = plan.blockFor() == null ? null : plan.placedState();
        BoundingBox v = plan.bounds();
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

    /** @return how many lining blocks this carver has placed. */
    public int placed() {
        return this.placed;
    }

    /**
     * @return fluid found inside the bore after it was already cut.
     *
     * <p>Should be zero. Anything else means fluid reached the inside of a sealed tunnel while it was
     * being cut, which is the one failure the lining exists to prevent, so it is worth a number rather
     * than being quietly mopped up.</p>
     */
    public int dried() {
        return this.dried;
    }

    /**
     * Do up to {@code budget} blocks of work, lining before boring.
     *
     * @return true once the whole plan in this chunk has been carried out
     */
    public boolean advance(ServerLevel level, int budget, boolean drop) {
        WorldThread.assertOn("changing blocks for an attended carve");
        int cells = this.width * this.height * this.depth;
        if (cells <= 0) {
            return true;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int spent = 0;
        while (spent < budget) {
            if (this.cursor >= cells) {
                return true;
            }
            int i = this.cursor++;
            int x = this.box.minX() + i % this.width;
            int y = this.box.minY() + (i / this.width) % this.height;
            int z = this.box.minZ() + i / (this.width * this.height);
            at.set(x, y, z);
            spent += switch (this.plan.stage()) {
                case LINE -> place(level, at, x, y, z, false);
                case DEWATER -> dry(level, at, x, y, z);
                case BORE -> bore(level, at, x, y, z, drop);
                // A torch only ever goes into air. If a section put one somewhere that turned out to be
                // wall, the tunnel stays dark there rather than gaining a hole in its lining.
                case LIGHT -> place(level, at, x, y, z, true);
            };
        }
        return false;
    }

    private int place(ServerLevel level, BlockPos at, int x, int y, int z, boolean onlyIntoAir) {
        CarveVolume where = this.plan.volumeFor();
        if (where == null || this.placing == null || !where.contains(x, y, z)) {
            return 0;
        }
        BlockState existing = level.getBlockState(at);
        if (existing == this.placing || (onlyIntoAir && !existing.isAir())) {
            return 0;
        }
        level.setBlock(at, this.placing, LINING_FLAGS);
        this.placed++;
        return 1;
    }

    private int bore(ServerLevel level, BlockPos at, int x, int y, int z, boolean drop) {
        if (!this.plan.bore().contains(x, y, z)) {
            return 0;
        }
        BlockState state = level.getBlockState(at);
        if (state.isAir()) {
            return 0;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLUID_FLAGS);
            this.broken++;
            return 1;
        }
        // destroyBlock, not setBlock: block entities need emptying and neighbours need telling.
        level.destroyBlock(at, drop);
        // destroyBlock replaces a block with its own fluid state, so breaking water gives water back and
        // breaking a waterlogged stair gives water. Left alone, a bore through an aquifer clears to
        // water rather than to air and every count says it worked.
        if (!level.getBlockState(at).isAir()) {
            level.setBlock(at, Blocks.AIR.defaultBlockState(), FLUID_FLAGS);
        }
        this.broken++;
        return 1;
    }

    /** Anything wet inside a tunnel that is already sealed, taken out without telling its neighbours. */
    private int dry(ServerLevel level, BlockPos at, int x, int y, int z) {
        if (!this.plan.bore().contains(x, y, z) || level.getBlockState(at).getFluidState().isEmpty()) {
            return 0;
        }
        level.setBlock(at, Blocks.AIR.defaultBlockState(), FLUID_FLAGS);
        this.dried++;
        return 1;
    }
}
