package com.wf.wflib.rail.build;

import com.wf.wflib.drone.WorldThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Puts one block of track in the world.
 *
 * <p>Vanilla track, which is the only kind this game has without Immersive Railroading. IR's track is
 * not a block at all but a multi-block structure registered into a graph that stock is positioned along,
 * so laying it means driving IR's own builder rather than writing blocks; see RAIL-PLAN §5.1. Nothing
 * here would produce runnable IR track and it does not pretend to.</p>
 *
 * <p>Every rail is written twice, and the second write is the point of this class. Placing a rail runs
 * vanilla's own connection logic, which reshapes both the new piece and its neighbours to whatever it
 * thinks they should join to: laying into a junction, or across an older line, that guess is wrong and
 * silently so. Re-asserting the shape the route worked out costs one block write and makes the finished
 * track a function of the survey rather than of what happened to be nearby.</p>
 */
public final class TrackLayer {

    private TrackLayer() {
    }

    /** What laying one piece did, so a machine can report tonnage rather than tick counts. */
    public record Laid(int rails, int boosters, int sleepers) {

        public static final Laid NOTHING = new Laid(0, 0, 0);

        public Laid plus(Laid other) {
            return new Laid(this.rails + other.rails, this.boosters + other.boosters,
                    this.sleepers + other.sleepers);
        }

        public boolean any() {
            return this.rails > 0;
        }
    }

    /**
     * Lay one piece of track, and the sleeper under it if there is nothing to lay it on.
     *
     * @param boost whether this piece should be a powered rail with a block of redstone beneath it.
     *              Only ever honoured on a straight: a powered rail has no corner state, and asking for
     *              one on a bend would quietly straighten the curve.
     * @param bed what to put underneath when the ground will not hold a rail, normally the tunnel lining
     */
    public static Laid lay(ServerLevel level, RailPath path, int index, int floorY, boolean boost,
                           BlockState bed) {
        WorldThread.assertOn("laying track");
        RailPath.Cell cell = path.cells().get(index);
        BlockPos pos = new BlockPos(cell.x(), floorY, cell.z());
        if (!level.isLoaded(pos)) {
            return Laid.NOTHING;
        }
        boolean powered = boost && cell.straight();
        BlockPos below = pos.below();
        int sleepers = 0;
        if (powered) {
            // The whole of a powered rail's power supply, one block thick, inside the lining where it
            // cannot be seen and cannot be walked into. A line that needs a redstone circuit to run is
            // a line nobody will build twice.
            if (!level.getBlockState(below).is(Blocks.REDSTONE_BLOCK)) {
                level.setBlock(below, Blocks.REDSTONE_BLOCK.defaultBlockState(), Block.UPDATE_ALL);
                sleepers++;
            }
        } else if (!Block.canSupportRigidBlock(level, below)) {
            level.setBlock(below, bed, Block.UPDATE_ALL);
            sleepers++;
        }

        BlockState want = powered
                ? Blocks.POWERED_RAIL.defaultBlockState().setValue(PoweredRailBlock.SHAPE, cell.shape())
                : Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE, cell.shape());
        BlockState existing = level.getBlockState(pos);
        if (existing != want) {
            level.setBlock(pos, want, Block.UPDATE_ALL);
        }
        // This piece, and the one behind it that placing this one may have just re-pointed.
        assertShape(level, pos, cell.shape());
        if (index > 0) {
            RailPath.Cell back = path.cells().get(index - 1);
            assertShape(level, new BlockPos(back.x(), floorY, back.z()), back.shape());
        }
        return new Laid(1, powered ? 1 : 0, sleepers);
    }

    /**
     * Put a rail back the way the route says it lies.
     *
     * <p>A property-only write, so the block never changes and the placement logic that caused the
     * problem does not run again. Its powered state is left exactly as the world computed it.</p>
     *
     * <p>The two shape properties are different objects - a powered rail's cannot hold a corner - so
     * which one a block has is what says which kind of rail it is.</p>
     */
    private static void assertShape(ServerLevel level, BlockPos pos, RailShape shape) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(RailBlock.SHAPE)) {
            if (state.getValue(RailBlock.SHAPE) != shape) {
                level.setBlock(pos, state.setValue(RailBlock.SHAPE, shape), Block.UPDATE_CLIENTS);
            }
            return;
        }
        boolean straight = shape == RailShape.NORTH_SOUTH || shape == RailShape.EAST_WEST;
        if (straight && state.hasProperty(PoweredRailBlock.SHAPE)
                && state.getValue(PoweredRailBlock.SHAPE) != shape) {
            level.setBlock(pos, state.setValue(PoweredRailBlock.SHAPE, shape), Block.UPDATE_CLIENTS);
        }
    }
}
