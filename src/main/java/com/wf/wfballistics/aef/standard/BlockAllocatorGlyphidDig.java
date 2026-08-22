package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.entity.glyphid.GlyphidDigging;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The bite a glyphid takes out of terrain: a spherical ray-march like {@link BlockAllocatorStandard}, but
 * priced on what the bug can chew rather than on blast power.
 *
 * <p>Two differences from a blast, and both are what make it read as digging:
 * <ul>
 *   <li><b>Rays stop at anything too tough, they do not weaken.</b> A standard blast spends power per block
 *       and eventually peters out, so a strong charge always gets a little way into a hard wall. A glyphid
 *       either can chew a material or cannot, so a ray hitting anything above {@code maximum} hardness ends
 *       there and everything behind it survives. Reinforced walls hold completely instead of eroding.</li>
 *   <li><b>Range is fixed.</b> The march runs to the explosion radius rather than until power runs out, so
 *       one bite is the same size every time.</li>
 * </ul>
 *
 * <p>{@code stopAt} guards blocks the colony must not eat regardless of how soft they are: its own spawners,
 * most obviously, which a digger would otherwise happily demolish on its way past.
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
                            // Air is never collected: it costs a BlockPos allocation per step and the block
                            // processor would only discard it. Matters here more than for a blast, since a
                            // swarm of diggers runs this far more often than anything fires a missile.
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
