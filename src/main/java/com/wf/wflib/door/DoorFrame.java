package com.wf.wflib.door;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** The box of blocks a door fills, and how any one of them finds the core. */
public final class DoorFrame {

    /** Cap on the core walk. The tallest door in the table is 24 blocks, so this is slack, not a limit. */
    private static final int MAX_WALK = 64;

    /** Guards structural writes from the orphan sweep that a normal break has to trigger. */
    private static boolean rebuilding;

    private DoorFrame() {
    }

    /** Whether the block writes happening right now are a door rebuilding itself rather than a break. */
    public static boolean rebuilding() {
        return rebuilding;
    }

    /** Runs {@code body} with the orphan sweep suppressed. */
    public static void structural(Runnable body) {
        boolean was = rebuilding;
        rebuilding = true;
        try {
            body.run();
        } finally {
            rebuilding = was;
        }
    }

    /**
     * @return the core of the door {@code pos} belongs to, or null if the chain is broken or leaves
     *      this block entirely
     */
    @Nullable
    public static BlockPos findCore(BlockGetter level, BlockPos pos, Block door) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int step = 0; step < MAX_WALK; step++) {
            BlockState state = level.getBlockState(cursor);
            if (!state.is(door)) {
                return null;
            }
            if (state.getValue(DoorBlock.ROLE) == DoorRole.CORE) {
                return cursor.immutable();
            }
            cursor.move(state.getValue(DoorBlock.FACING));
        }
        return null;
    }

    /** The direction a placeholder at {@code offset} from the core must step to get one block closer. */
    public static Direction linkToCore(int dx, int dy, int dz) {
        if (dy < 0) {
            return Direction.UP;
        }
        if (dy > 0) {
            return Direction.DOWN;
        }
        if (dx < 0) {
            return Direction.EAST;
        }
        if (dx > 0) {
            return Direction.WEST;
        }
        if (dz < 0) {
            return Direction.SOUTH;
        }
        return Direction.NORTH;
    }

    /**
     * Door-frame extents rotated into world axes.
     *
     * @param dims {@code {up, down, north, south, west, east}} as the table states them
     * @param facing which way the door faces; {@link Direction#SOUTH} is the frame the table is in
     */
    public static int[] rotate(int[] dims, Direction facing) {
        return switch (facing) {
            case NORTH -> new int[] {dims[0], dims[1], dims[3], dims[2], dims[5], dims[4]};
            case EAST -> new int[] {dims[0], dims[1], dims[5], dims[4], dims[2], dims[3]};
            case WEST -> new int[] {dims[0], dims[1], dims[4], dims[5], dims[3], dims[2]};
            default -> dims;
        };
    }

    /**
     * Whether the box a door of these dimensions would fill is clear.
     *
     * @param origin the block the player clicked, which counts as clear because it is about to be replaced
     */
    public static boolean clear(Level level, BlockPos core, int[] dims, BlockPos origin, Direction facing) {
        int[] box = rotate(dims, facing);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = core.getX() - box[4]; x <= core.getX() + box[5]; x++) {
            for (int y = core.getY() - box[1]; y <= core.getY() + box[0]; y++) {
                for (int z = core.getZ() - box[2]; z <= core.getZ() + box[3]; z++) {
                    cursor.set(x, y, z);
                    if (cursor.equals(origin)) {
                        continue;
                    }
                    if (!level.isInWorldBounds(cursor) || !level.getBlockState(cursor).canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Fills the box with placeholders, leaving the core alone. Call inside {@link #structural}. */
    public static void fill(Level level, BlockPos core, int[] dims, Direction facing, Block door) {
        int[] box = rotate(dims, facing);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = core.getX() - box[4]; x <= core.getX() + box[5]; x++) {
            for (int y = core.getY() - box[1]; y <= core.getY() + box[0]; y++) {
                for (int z = core.getZ() - box[2]; z <= core.getZ() + box[3]; z++) {
                    if (x == core.getX() && y == core.getY() && z == core.getZ()) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    Direction link = linkToCore(x - core.getX(), y - core.getY(), z - core.getZ());
                    level.setBlock(cursor, door.defaultBlockState()
                            .setValue(DoorBlock.ROLE, DoorRole.DUMMY)
                            .setValue(DoorBlock.FACING, link), Block.UPDATE_ALL);
                }
            }
        }
    }

    /** Clears every block of the door, core included. Call inside {@link #structural}. */
    public static void clearAll(Level level, BlockPos core, DoorType type, Direction facing, Block door) {
        removeBox(level, core, type.dimensions(), facing, door);
        for (int[] extra : type.extraDimensions()) {
            removeBox(level, core, extra, facing, door);
        }
        if (level.getBlockState(core).is(door)) {
            level.removeBlock(core, false);
        }
    }

    private static void removeBox(Level level, BlockPos core, int[] dims, Direction facing, Block door) {
        int[] box = rotate(dims, facing);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = core.getX() - box[4]; x <= core.getX() + box[5]; x++) {
            for (int y = core.getY() - box[1]; y <= core.getY() + box[0]; y++) {
                for (int z = core.getZ() - box[2]; z <= core.getZ() + box[3]; z++) {
                    cursor.set(x, y, z);
                    if (cursor.equals(core)) {
                        continue;
                    }
                    if (level.getBlockState(cursor).is(door)) {
                        level.removeBlock(cursor, false);
                    }
                }
            }
        }
    }

    /** {@link Rotation} applied to a relative offset. Vanilla's own definition, spelled out. */
    public static BlockPos rotate(BlockPos pos, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new BlockPos(-pos.getZ(), pos.getY(), pos.getX());
            case CLOCKWISE_180 -> new BlockPos(-pos.getX(), pos.getY(), -pos.getZ());
            case COUNTERCLOCKWISE_90 -> new BlockPos(pos.getZ(), pos.getY(), -pos.getX());
            default -> pos;
        };
    }

    /**
     * The rotation that takes a world offset from the core into the door's own frame, which is what the per-block
     * bounds in {@link DoorType} are written in.
     */
    public static Rotation intoDoorFrame(Direction facing) {
        return facingRotation(facing).getRotated(Rotation.COUNTERCLOCKWISE_90);
    }

    /** The rotation a door facing {@code facing} applies to anything stated in its own frame. */
    public static Rotation facingRotation(Direction facing) {
        return switch (facing) {
            case SOUTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            case WEST -> Rotation.CLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }
}
