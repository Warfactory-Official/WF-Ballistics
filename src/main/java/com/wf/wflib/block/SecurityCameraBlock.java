package com.wf.wflib.block;

import com.mojang.serialization.MapCodec;
import com.wf.wflib.drone.cam.StaticCameraFeeds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** A fixed camera, bolted to a wall, feeding the same pipeline a drone does. */
public class SecurityCameraBlock extends Block {

    public static final MapCodec<SecurityCameraBlock> CODEC = simpleCodec(SecurityCameraBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    // The housing sits against the wall behind the lens, so the box is on the side away from FACING.
    private static final VoxelShape NORTH = Block.box(5.0, 5.0, 8.0, 11.0, 11.0, 16.0);
    private static final VoxelShape SOUTH = Block.box(5.0, 5.0, 0.0, 11.0, 11.0, 8.0);
    private static final VoxelShape WEST = Block.box(8.0, 5.0, 5.0, 16.0, 11.0, 11.0);
    private static final VoxelShape EAST = Block.box(0.0, 5.0, 5.0, 8.0, 11.0, 11.0);

    public SecurityCameraBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction face = context.getClickedFace();
        Direction facing = face.getAxis().isHorizontal() ? face : context.getHorizontalDirection();
        return this.defaultBlockState().setValue(FACING, facing);
    }

    /** Tell the feed system this camera is gone. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl) {
            StaticCameraFeeds.invalidate(sl, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
                           boolean movedByPiston) {
        if (level instanceof ServerLevel sl) {
            StaticCameraFeeds.revive(sl, pos);
        }
        super.onPlace(state, level, pos, oldState, movedByPiston);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }
}
