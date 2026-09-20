package com.wf.wflib.door;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Every block of every door. */
public class DoorBlock extends BaseEntityBlock {

    public static final MapCodec<DoorBlock> CODEC =
            simpleCodec(props -> new DoorBlock(props, DoorType.QE_SLIDING_DOOR));

    public static final EnumProperty<DoorRole> ROLE = EnumProperty.create("role", DoorRole.class);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    private final DoorType type;

    public DoorBlock(Properties props, DoorType type) {
        super(props);
        this.type = type;
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(ROLE, DoorRole.DUMMY)
                .setValue(FACING, Direction.NORTH));
    }

    public DoorType type() {
        return type;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ROLE, FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(ROLE) == DoorRole.CORE ? new DoorBlockEntity(pos, state) : null;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> given) {
        return state.getValue(ROLE) == DoorRole.CORE
                ? createTickerHelper(given, ModDoors.DOOR.get(), DoorBlockEntity::tick)
                : null;
    }

    /** The whole door is drawn by one visual on the core; nothing here has a block model. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    // --- placement -------------------------------------------------------------------------------

    /** Turns the single block the player placed into the whole door. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer,
                            ItemStack stack) {
        if (level.isClientSide) {
            return;
        }
        Direction facing = placer == null ? Direction.NORTH : placer.getDirection().getOpposite();
        BlockPos core = pos.relative(facing.getOpposite(), type.blockOffset());

        if (!hasRoom(level, pos, facing.getOpposite())) {
            level.removeBlock(pos, false);
            popResource(level, pos, new ItemStack(this));
            return;
        }

        DoorFrame.structural(() -> {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_INVISIBLE);
            level.setBlock(core, defaultBlockState()
                    .setValue(ROLE, DoorRole.CORE)
                    .setValue(FACING, facing), Block.UPDATE_ALL);
            DoorFrame.fill(level, core, type.dimensions(), facing, this);
            for (int[] extra : type.extraDimensions()) {
                DoorFrame.fill(level, core, extra, facing, this);
            }
        });

        if (level.getBlockEntity(core) instanceof DoorBlockEntity door) {
            door.onPlaced(stack);
        }
    }

    /** Whether a door of this type placed against {@code pos} by a player facing {@code look} has room. */
    public boolean hasRoom(Level level, BlockPos pos, Direction look) {
        Direction facing = look.getOpposite();
        BlockPos core = pos.relative(facing.getOpposite(), type.blockOffset());
        if (!DoorFrame.clear(level, core, type.dimensions(), pos, facing)) {
            return false;
        }
        for (int[] extra : type.extraDimensions()) {
            if (!DoorFrame.clear(level, core, extra, pos, facing)) {
                return false;
            }
        }
        return true;
    }

    // --- teardown --------------------------------------------------------------------------------

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos core = DoorFrame.findCore(level, pos, this);
            if (core != null) {
                Direction facing = level.getBlockState(core).getValue(FACING);
                DoorFrame.structural(() -> DoorFrame.clearAll(level, core, type, facing, this));
                if (!player.getAbilities().instabuild) {
                    popResource(level, core, new ItemStack(this));
                }
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /**
     * A placeholder destroyed by anything but the player's pick (an explosion, another mod) pulls the block next to
     * it toward the core, which cascades until the core goes and orphans the rest.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement,
                            boolean moving) {
        if (!level.isClientSide && !DoorFrame.rebuilding() && !replacement.is(this)
                && state.getValue(ROLE) != DoorRole.CORE) {
            BlockPos inward = pos.relative(state.getValue(FACING));
            if (level.getBlockState(inward).is(this)) {
                level.removeBlock(inward, false);
            }
        }
        super.onRemove(state, level, pos, replacement, moving);
    }

    /**
     * A block of the door going away any other way (an explosion, a piston, another mod) takes the door with it.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos neighbourPos, boolean moving) {
        if (level.isClientSide || DoorFrame.rebuilding()) {
            return;
        }
        BlockPos core = DoorFrame.findCore(level, pos, this);
        if (core == null) {
            if (state.getValue(ROLE) != DoorRole.CORE) {
                level.removeBlock(pos, false);
            }
            return;
        }
        if (level.getBlockEntity(core) instanceof DoorBlockEntity door) {
            door.updateRedstone(pos);
        }
    }

    // --- interaction -----------------------------------------------------------------------------

    /** No bare-handed path, by design. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        return InteractionResult.PASS;
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, net.minecraft.world.InteractionHand hand,
                                              BlockHitResult hit) {
        BlockPos core = DoorFrame.findCore(level, pos, this);
        if (core == null || !(level.getBlockEntity(core) instanceof DoorBlockEntity door)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return door.useItem(stack, player, hand);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(this);
    }

    // --- shape -----------------------------------------------------------------------------------

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
        return shape(state, level, pos, false);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext ctx) {
        return shape(state, level, pos, true);
    }

    /** These render nothing and the originals were see-through, so light goes straight past. */
    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.empty();
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType kind) {
        return state.getValue(ROLE).passable();
    }

    private VoxelShape shape(BlockState state, BlockGetter level, BlockPos pos, boolean forCollision) {
        BlockPos core = DoorFrame.findCore(level, pos, this);
        if (core == null) {
            return Shapes.block();
        }
        BlockState coreState = level.getBlockState(core);
        if (!coreState.is(this)) {
            return Shapes.block();
        }
        Direction facing = coreState.getValue(FACING);

        boolean open = state.getValue(ROLE).passable()
                || (level.getBlockEntity(pos) instanceof DoorBlockEntity door && !door.shut());

        BlockPos local = DoorFrame.rotate(
                new BlockPos(pos.getX() - core.getX(), pos.getY() - core.getY(), pos.getZ() - core.getZ()),
                DoorFrame.intoDoorFrame(facing));
        AABB box = type.blockBound(local.getX(), local.getY(), local.getZ(), open, forCollision);
        return toWorld(box, facing);
    }

    /** The door-frame box turned back into the block's own 0..1 space for the facing it was built at. */
    private static VoxelShape toWorld(AABB box, Direction facing) {
        double x0;
        double x1;
        double z0;
        double z1;
        switch (facing) {
            case NORTH -> {
                x0 = 1 - box.minX;
                x1 = 1 - box.maxX;
                z0 = 1 - box.minZ;
                z1 = 1 - box.maxZ;
            }
            case WEST -> {
                x0 = 1 - box.minZ;
                x1 = 1 - box.maxZ;
                z0 = box.minX;
                z1 = box.maxX;
            }
            case EAST -> {
                x0 = box.minZ;
                x1 = box.maxZ;
                z0 = 1 - box.maxX;
                z1 = 1 - box.minX;
            }
            default -> {
                x0 = box.minX;
                x1 = box.maxX;
                z0 = box.minZ;
                z1 = box.maxZ;
            }
        }
        double lowX = Math.min(x0, x1);
        double highX = Math.max(x0, x1);
        double lowZ = Math.min(z0, z1);
        double highZ = Math.max(z0, z1);
        if (highX - lowX <= 1.0e-6 || box.maxY - box.minY <= 1.0e-6 || highZ - lowZ <= 1.0e-6) {
            return Shapes.empty();
        }
        return Shapes.box(lowX, box.minY, lowZ, highX, box.maxY, highZ);
    }
}
