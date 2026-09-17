package com.wf.wfballistics.block;

import com.mojang.serialization.MapCodec;
import com.wf.wfballistics.block.entity.CameraMonitorBlockEntity;
import com.wf.wfballistics.client.gui.CameraScreenOpener;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** A screen showing one drone's camera, in the world. */
public class CameraMonitorBlock extends BaseEntityBlock {

    public static final MapCodec<CameraMonitorBlock> CODEC = simpleCodec(CameraMonitorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Screen size in blocks. Columns run to the viewer's right, rows upward, both from the origin. */
    public static final int WIDTH = 3;
    public static final int HEIGHT = 2;

    /** Column, from the viewer's left. */
    public static final IntegerProperty PART_X = IntegerProperty.create("part_x", 0, WIDTH - 1);
    /** Row, from the bottom. */
    public static final IntegerProperty PART_Y = IntegerProperty.create("part_y", 0, HEIGHT - 1);

    /**
     * Set while a screen is taking itself apart, so the five {@code setBlock} calls that do it don't each start a
     * dismantle of their own.
     */
    private static final ThreadLocal<Boolean> DISMANTLING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public CameraMonitorBlock(Properties props) {
        super(props);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART_X, 0)
                .setValue(PART_Y, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART_X, PART_Y);
    }

    // --- geometry -------------------------------------------------------------------------------------

    /** The direction the screen grows in: the viewer's right when standing in front of it. */
    public static Direction right(BlockState state) {
        return state.getValue(FACING).getCounterClockWise();
    }

    /** @return the bottom-left cell of the screen this block belongs to. */
    public static BlockPos originOf(BlockPos pos, BlockState state) {
        return pos.relative(right(state), -state.getValue(PART_X)).below(state.getValue(PART_Y));
    }

    public static boolean isOrigin(BlockState state) {
        return state.getValue(PART_X) == 0 && state.getValue(PART_Y) == 0;
    }

    private static BlockPos cell(BlockPos origin, Direction right, int x, int y) {
        return origin.relative(right, x).above(y);
    }

    // --- placement ------------------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockState origin = this.defaultBlockState().setValue(FACING, facing);
        Level level = context.getLevel();
        BlockPos at = context.getClickedPos();
        Direction right = facing.getCounterClockWise();
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                BlockPos pos = cell(at, right, x, y);
                if (level.isOutsideBuildHeight(pos) || !level.getBlockState(pos).canBeReplaced(context)) {
                    return null;
                }
            }
        }
        return origin;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) {
            return;
        }
        Direction right = right(state);
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                level.setBlock(cell(pos, right, x, y),
                        state.setValue(PART_X, x).setValue(PART_Y, y), Block.UPDATE_ALL);
            }
        }
    }

    // --- removal --------------------------------------------------------------------------------------

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && !player.isCreative()) {
            BlockPos origin = originOf(pos, state);
            if (!origin.equals(pos) && level.getBlockState(origin).getBlock() == this) {
                level.destroyBlock(origin, true, player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !DISMANTLING.get()) {
            DISMANTLING.set(Boolean.TRUE);
            try {
                dismantle(level, pos, state);
            } finally {
                DISMANTLING.set(Boolean.FALSE);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private void dismantle(LevelAccessor level, BlockPos pos, BlockState state) {
        BlockPos origin = originOf(pos, state);
        Direction right = right(state);
        for (int x = 0; x < WIDTH; x++) {
            for (int y = 0; y < HEIGHT; y++) {
                BlockPos cell = cell(origin, right, x, y);
                if (cell.equals(pos)) {
                    continue;
                }
                BlockState at = level.getBlockState(cell);
                if (at.getBlock() == this && originOf(cell, at).equals(origin)) {
                    level.setBlock(cell, at.getFluidState().createLegacyBlock(),
                            Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
                }
            }
        }
    }

    // --- behaviour ------------------------------------------------------------------------------------

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // One screen, one block entity, one registration with the camera net, one render.
        return isOrigin(state) ? new CameraMonitorBlockEntity(pos, state) : null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide || !isOrigin(state)) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.CAMERA_MONITOR.get(),
                (lvl, pos, st, be) -> be.serverTick());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        BlockPos origin = originOf(pos, state);
        if (!(level.getBlockEntity(origin) instanceof CameraMonitorBlockEntity monitor)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (level instanceof ServerLevel sl) {
                monitor.cycle(sl);
                boolean bound = !monitor.channels().isEmpty();
                player.displayClientMessage(monitor.feedId() == 0
                        ? Component.literal(bound
                                ? String.format("channel %d: camera unreachable",
                                monitor.selectedChannel() + 1)
                                : "no camera drone in range").withStyle(ChatFormatting.RED)
                        : Component.literal(bound
                                ? String.format("channel %d of %d: CAM-%04X",
                                monitor.selectedChannel() + 1, monitor.channels().size(),
                                monitor.feedId() & 0xFFFF)
                                : String.format("tuned to CAM-%04X", monitor.feedId() & 0xFFFF))
                        .withStyle(ChatFormatting.AQUA), true);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level.isClientSide && (monitor.feedId() != 0 || monitor.feedIds().length > 0)) {
            CameraScreenOpener.open(monitor.feedIds(), monitor.selectedChannel());
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
