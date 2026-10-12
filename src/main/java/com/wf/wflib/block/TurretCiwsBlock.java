package com.wf.wflib.block;

import com.mojang.serialization.MapCodec;
import com.wf.wflib.block.entity.TurretCiwsBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** CIWS: hosts a {@link TurretCiwsBlockEntity} that auto-shoots down nearby missiles. No player interaction. */
public class TurretCiwsBlock extends BaseEntityBlock {
    public static final MapCodec<TurretCiwsBlock> CODEC = simpleCodec(TurretCiwsBlock::new);

    public TurretCiwsBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TurretCiwsBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.TURRET_CIWS.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }
}
