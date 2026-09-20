package com.wf.wflib.block;

import com.mojang.serialization.MapCodec;
import com.wf.wflib.block.entity.DronePadBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Launch and recovery pad for delivery drones. */
public class DronePadBlock extends BaseEntityBlock {

    public static final MapCodec<DronePadBlock> CODEC = simpleCodec(DronePadBlock::new);

    public DronePadBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DronePadBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 net.minecraft.world.level.block.entity.BlockEntityType<T> type) {
        return level.isClientSide ? null
                : createTickerHelper(type, ModBlockEntities.DRONE_PAD.get(), DronePadBlockEntity::serverTick);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DronePadBlockEntity pad) {
            if (player.isShiftKeyDown()) {
                pad.openCargo(player);
            } else if (player instanceof ServerPlayer serverPlayer) {
                // Hands the block pos to the client menu constructor (read back in DronePadMenu).
                serverPlayer.openMenu(pad, buf -> buf.writeBlockPos(pos));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DronePadBlockEntity pad) {
            pad.initPowered(level.hasNeighborSignal(pos));
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                pad.stationCode(serverLevel);
            }
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos fromPos, boolean moved) {
        super.neighborChanged(state, level, pos, neighborBlock, fromPos, moved);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof DronePadBlockEntity pad) {
            pad.onRedstone(level.hasNeighborSignal(pos));
        }
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof DronePadBlockEntity pad) {
            pad.spillCargo();
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
