package com.wf.wfballistics.block;

import com.mojang.serialization.MapCodec;
import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Debug block: right-click to open a simple GUI that configures and launches a {@link
 * com.wf.wfballistics.MissileEntity} from the block's position.
 */
public class MissileDispenserBlock extends BaseEntityBlock {
    public static final MapCodec<MissileDispenserBlock> CODEC = simpleCodec(MissileDispenserBlock::new);

    public MissileDispenserBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MissileDispenserBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MenuProvider provider && player instanceof ServerPlayer serverPlayer) {
                // Hands the block pos to the client menu constructor (read back in MissileDispenserMenu).
                serverPlayer.openMenu(provider, buf -> buf.writeBlockPos(pos));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MissileDispenserBlockEntity dispenser) {
            dispenser.initPowered(level.hasNeighborSignal(pos));
        }
    }

    @Override
    public void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock,
                                BlockPos fromPos, boolean moved) {
        super.neighborChanged(state, level, pos, neighborBlock, fromPos, moved);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MissileDispenserBlockEntity dispenser) {
            dispenser.onRedstone(level.hasNeighborSignal(pos));
        }
    }
}
