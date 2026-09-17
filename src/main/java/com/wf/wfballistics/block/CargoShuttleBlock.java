package com.wf.wfballistics.block;

import com.mojang.serialization.MapCodec;
import com.wf.wfballistics.block.entity.CargoShuttleBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public class CargoShuttleBlock extends BaseEntityBlock {

    public static final MapCodec<CargoShuttleBlock> CODEC = simpleCodec(CargoShuttleBlock::new);

    public CargoShuttleBlock(Properties props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new CargoShuttleBlockEntity(pos, state);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof CargoShuttleBlockEntity shuttle) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    String.format(Locale.ROOT, "Cargo shuttle: %d/%d slot(s) - pipe it, hopper it, or take it",
                            shuttle.occupiedSlots(), shuttle.items().getSlots())), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof CargoShuttleBlockEntity shuttle) {
            for (int i = 0; i < shuttle.items().getSlots(); i++) {
                ItemStack stack = shuttle.items().getStackInSlot(i);
                if (!stack.isEmpty()) {
                    Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), stack);
                }
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
