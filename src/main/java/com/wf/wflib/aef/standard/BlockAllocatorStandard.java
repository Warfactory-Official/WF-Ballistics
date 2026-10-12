package com.wf.wflib.aef.standard;

import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.aef.interfaces.IBlockAllocator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.HashSet;
import java.util.Set;

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
                    // Shell cells only; interior would retrace existing rays.
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
                    boolean broke = false;

                    for (float step = 0.3F; power > 0.0F; power -= step * 0.75F) {
                        cursor.set(Mth.floor(cx), Mth.floor(cy), Mth.floor(cz));
                        BlockState state = level.getBlockState(cursor);

                        if (!state.isAir()) {
                            power -= (blockResistance(explosion, level, cursor, state, power) + 0.3F) * step;
                        }

                        if (power > 0.0F && !state.isAir() && canDestroy(explosion, level, cursor, state, power)) {
                            affectedBlocks.add(cursor.immutable());
                            broke = true;
                        }

                        cx += dx * step;
                        cy += dy * step;
                        cz += dz * step;
                    }

                    com.wf.wflib.debug.ExplosionTrace.ray(x, y, z, cx, cy, cz, broke);
                }
            }
        }

        return affectedBlocks;
    }

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
