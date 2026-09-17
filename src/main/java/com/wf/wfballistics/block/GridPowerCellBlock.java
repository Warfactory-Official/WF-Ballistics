package com.wf.wfballistics.block;

import com.mojang.serialization.MapCodec;
import com.wf.wfballistics.block.entity.GridPowerCellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Creative FE source for the probe grid. See {@link GridPowerCellBlockEntity} for why it exists.
 */
public class GridPowerCellBlock extends BaseEntityBlock {

    public static final MapCodec<GridPowerCellBlock> CODEC = simpleCodec(GridPowerCellBlock::new);

    public GridPowerCellBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GridPowerCellBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.GRID_POWER_CELL.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }
}
