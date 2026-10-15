package com.wf.wflib.block;

import com.wf.wflib.drone.DroneEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Iron-bars-shaped net, no collision (walkers pass; hull solvers would stop a drone short of the strands). A catchable
 * body ({@link #catches}) whose box reaches a strand is pinned by its host ({@link #caught}) until the net breaks.
 */
public class DroneNetBlock extends IronBarsBlock {

    private static final List<Predicate<Entity>> CATCHES = new CopyOnWriteArrayList<>();

    static {
        CATCHES.add(e -> e instanceof DroneEntity);
    }

    public DroneNetBlock(Properties properties) {
        super(properties);
    }

    /** Other mods' small drones; any predicate true => caught. */
    public static void catches(Predicate<Entity> body) {
        CATCHES.add(body);
    }

    public static boolean catchable(Entity entity) {
        for (Predicate<Entity> p : CATCHES) {
            if (p.test(entity)) {
                return true;
            }
        }
        return false;
    }

    public static boolean caught(Level level, Entity body) {
        return caught(level, body.getBoundingBox());
    }

    /** {@code box} overlaps a net strand. Hosts pin a caught body where it first touched (velocity 0). */
    public static boolean caught(Level level, AABB box) {
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DroneNetBlock net) {
                VoxelShape shape = net.getShape(state, level, pos, CollisionContext.empty());
                for (AABB part : shape.toAabbs()) {
                    if (part.move(pos).intersects(box)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }
}
