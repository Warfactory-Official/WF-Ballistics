package com.wf.wfballistics.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

/**
 * The hydrophone probe. The only one that is waterlogged, because it is the only one that has to be in the
 * water to do anything, and mounting it mid-column rather than on the bottom is how a player picks which side
 * of the thermocline it listens from.
 *
 * <p>A subclass rather than a property on {@link ReconProbeBlock} so the other four keep the blockstate files
 * they already have.
 */
public class SonarProbeBlock extends ReconProbeBlock implements SimpleWaterloggedBlock {

    public static final MapCodec<SonarProbeBlock> CODEC =
            simpleCodec(props -> new SonarProbeBlock(props, ProbeKind.SONAR));
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;

    public SonarProbeBlock(Properties props, ProbeKind kind) {
        super(props, kind);
        this.registerDefaultState(this.stateDefinition.any().setValue(ONLINE, false).setValue(WATERLOGGED, false));
    }

    @Override
    protected MapCodec<? extends net.minecraft.world.level.block.BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(
            StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(ONLINE, WATERLOGGED);
    }

    @Override
    @Nullable
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return this.defaultBlockState().setValue(WATERLOGGED,
                context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                     LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }
}
