package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.entity.glyphid.GlyphidDigging;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The bite a glyphid takes out of terrain: a spherical ray-march like {@link BlockAllocatorStandard}, but priced on
 * what the bug can chew rather than on blast power.
 */
public class BlockAllocatorGlyphidDig extends BlockAllocatorStandard {

    private static final float STEP = 0.3F;

    /** Hardness ceiling: anything above it stops the ray dead. */
    protected final double maximum;
    protected final Predicate<BlockState> stopAt;

    public BlockAllocatorGlyphidDig(double maximum) {
        this(maximum, 16, state -> false);
    }

    public BlockAllocatorGlyphidDig(double maximum, Predicate<BlockState> stopAt) {
        this(maximum, 16, stopAt);
    }

    public BlockAllocatorGlyphidDig(double maximum, int resolution, Predicate<BlockState> stopAt) {
        super(resolution);
        this.maximum = maximum;
        this.stopAt = stopAt;
    }

    @Override
    public Set<BlockPos> allocate(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        Set<BlockPos> affectedBlocks = new HashSet<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int i = 0; i < resolution; ++i) {
            for (int j = 0; j < resolution; ++j) {
                for (int k = 0; k < resolution; ++k) {
                    // Only walk the shell of the cube; interior cells would just re-trace the same rays.
                    if (i != 0 && i != resolution - 1 && j != 0 && j != resolution - 1 && k != 0 && k != resolution - 1) {
                        continue;
                    }

                    double dx = (i / (resolution - 1.0F) * 2.0F - 1.0F);
                    double dy = (j / (resolution - 1.0F) * 2.0F - 1.0F);
                    double dz = (k / (resolution - 1.0F) * 2.0F - 1.0F);
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= length;
                    dy /= length;
                    dz /= length;

                    double cx = x, cy = y, cz = z;

                    for (double dist = 0.0; dist <= size; ) {
                        cursor.set(Mth.floor(cx), Mth.floor(cy), Mth.floor(cz));
                        BlockState state = level.getBlockState(cursor);

                        if (!state.isAir()) {
                            if (GlyphidDigging.stopsABite(state, level, cursor, maximum) || stopAt.test(state)) {
                                break;
                            }
                            if (canDestroy(explosion, level, cursor, state, size)) {
                                affectedBlocks.add(cursor.immutable());
                            }
                        }

                        cx += dx * STEP;
                        cy += dy * STEP;
                        cz += dz * STEP;

                        double deltaX = cx - x;
                        double deltaY = cy - y;
                        double deltaZ = cz - z;
                        dist = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
                    }
                }
            }
        }

        return affectedBlocks;
    }
}
