package com.wf.wfballistics.block;

import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import com.wf.wfballistics.block.entity.MissileDispenserBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/**
 * Debug block: right-click to open a simple GUI that configures and launches a {@link com.wf.wfballistics.MissileEntity}
 * from the block's position. The configured launch parameters persist on the block entity, and a redstone rising edge
 * re-fires the stored configuration.
 */
public class MissileDispenserBlock extends BaseEntityBlock {
    public MissileDispenserBlock(Properties props) {
        super(props);
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
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof MenuProvider provider && player instanceof ServerPlayer serverPlayer) {
                // Hands the block pos to the client menu constructor (read back in MissileDispenserMenu).
                NetworkHooks.openScreen(serverPlayer, provider, pos);
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
