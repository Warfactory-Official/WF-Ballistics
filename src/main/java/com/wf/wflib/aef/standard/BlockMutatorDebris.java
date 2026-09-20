package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.interfaces.IBlockMutator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Fills cleared positions that border surviving solid terrain with a "debris" block (rubble/scorched stone), giving
 * craters a ragged lining instead of a clean spherical cut.
 */
public class BlockMutatorDebris implements IBlockMutator {

    protected final BlockState debris;

    public BlockMutatorDebris(Block block) {
        this(block.defaultBlockState());
    }

    public BlockMutatorDebris(BlockState debris) {
        this.debris = debris;
    }

    /**
     * Resolves a block by registry id, falling back to stone if the id is unknown.
     */
    public BlockMutatorDebris(ResourceLocation id) {
        Block block = BuiltInRegistries.BLOCK.get(id);
        this.debris = (block != null ? block : Blocks.STONE).defaultBlockState();
    }

    @Override
    public void mutatePre(ExplosionAEF explosion, BlockState state, BlockPos pos) {
    }

    @Override
    public void mutatePost(ExplosionAEF explosion, BlockPos pos) {
        Level level = explosion.level;
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = pos.relative(dir);
            BlockState state = level.getBlockState(neighbour);
            if (state.isSolidRender(level, neighbour) && !state.is(debris.getBlock())) {
                level.setBlock(pos, debris, Block.UPDATE_ALL);
                return;
            }
        }
    }
}
