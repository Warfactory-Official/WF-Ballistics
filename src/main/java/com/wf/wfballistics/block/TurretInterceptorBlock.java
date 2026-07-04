package com.wf.wfballistics.block;

import com.wf.wfballistics.block.entity.TurretInterceptorBlockEntity;
import com.wf.wfballistics.block.entity.TurretInterceptorNormalBlockEntity;
import com.wf.wfballistics.block.entity.TurretInterceptorSupersonicBlockEntity;
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
 * Auto-defense interceptor battery block: hosts a {@link TurretInterceptorBlockEntity} that automatically
 * launches guided interceptor missiles at nearby hostile missiles. Passive/automatic — no player interaction.
 * The {@code supersonic} flag selects the target class + interceptor (see the block entity).
 */
public class TurretInterceptorBlock extends BaseEntityBlock {

    private final boolean supersonic;

    public TurretInterceptorBlock(Properties props, boolean supersonic) {
        super(props);
        this.supersonic = supersonic;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return this.supersonic
                ? new TurretInterceptorSupersonicBlockEntity(pos, state)
                : new TurretInterceptorNormalBlockEntity(pos, state);
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
        return this.supersonic
                ? createTickerHelper(type, ModBlockEntities.TURRET_INTERCEPTOR_SUPERSONIC.get(),
                (lvl, pos, st, be) -> be.serverTick())
                : createTickerHelper(type, ModBlockEntities.TURRET_INTERCEPTOR.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }
}
