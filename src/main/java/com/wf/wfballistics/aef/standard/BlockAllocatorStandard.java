package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IBlockAllocator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashSet;
import java.util.Set;

/**
 * The vanilla explosion ray-march, reproduced exactly: fire rays from the centre through every cell on the
 * surface of a {@code resolution³} cube and march each ray outward in 0.3-block steps, draining the ray's
 * (randomised) power by each block's explosion resistance until it runs out. Every non-air block a
 * surviving ray passes through is marked for destruction.
 *
 * <p>{@code resolution} is the classic vanilla 16; raising it produces a smoother, more spherical blast at
 * a roughly quadratic cost (only the cube's shell is iterated, so it scales with {@code resolution²}).
 *
 * <p>This allocator only collects positions: see {@link BlockProcessorStandard} for what happens to them.
 */
public class BlockAllocatorStandard implements IBlockAllocator {

    protected final int resolution;

    public BlockAllocatorStandard() {
        this(16);
    }

    public BlockAllocatorStandard(int resolution) {
        this.resolution = resolution;
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
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= dist;
                    dy /= dist;
                    dz /= dist;

                    float power = size * (0.7F + level.random.nextFloat() * 0.6F);
                    double cx = x, cy = y, cz = z;

                    for (float step = 0.3F; power > 0.0F; power -= step * 0.75F) {
                        cursor.set(Mth.floor(cx), Mth.floor(cy), Mth.floor(cz));
                        BlockState state = level.getBlockState(cursor);

                        if (!state.isAir()) {
                            power -= (blockResistance(explosion, level, cursor, state, power) + 0.3F) * step;
                        }

                        // Only collect real blocks: skipping air means a blast in open space doesn't
                        // allocate a throwaway BlockPos for every step of every ray (mid-air, that was
                        // hundreds of thousands of short-lived objects per blast: a GC spike), and the
                        // block processor no longer has to re-scan and discard air positions afterwards.
                        if (power > 0.0F && !state.isAir() && canDestroy(explosion, level, cursor, state, power)) {
                            affectedBlocks.add(cursor.immutable());
                        }

                        cx += dx * step;
                        cy += dy * step;
                        cz += dz * step;
                    }
                }
            }
        }

        return affectedBlocks;
    }

    /**
     * Resistance the exploder sees for this block (lets a custom exploder override per-block resistance).
     */
    /**
     * How much this block resists being blown up.
     *
     * <p>The exploder hook is an <em>adjustment</em>, not a source: {@code Entity.getBlockExplosionResistance}
     * returns its last argument unchanged by default, and vanilla feeds it the block's own resistance so an
     * entity can raise or lower it. Feeding it the remaining blast power instead made it hand that power
     * straight back, so any explosion with an exploder set never consulted the material at all and chewed
     * bedrock exactly as fast as dirt. Every glyphid dig is such an explosion; the warheads are not, which is
     * why it only ever showed up as bugs eating through the world.
     */
    protected float blockResistance(ExplosionAEF explosion, Level level, BlockPos pos, BlockState state, float power) {
        FluidState fluid = state.getFluidState();
        float resistance = Math.max(state.getExplosionResistance(level, pos, explosion.compat),
                fluid.getExplosionResistance(level, pos, explosion.compat));
        return explosion.exploder != null
                ? explosion.exploder.getBlockExplosionResistance(explosion.compat, level, pos, state, fluid, resistance)
                : resistance;
    }

    protected boolean canDestroy(ExplosionAEF explosion, Level level, BlockPos pos, BlockState state, float power) {
        return explosion.exploder == null || explosion.exploder.shouldBlockExplode(explosion.compat, level, pos, state, power);
    }
}
