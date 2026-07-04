package com.wf.wfballistics.aef.standard;

import com.wf.wfballistics.aef.ExplosionAEF;
import com.wf.wfballistics.aef.interfaces.IBlockAllocator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashSet;
import java.util.Set;

public class BlockAllocatorBlockEntities implements IBlockAllocator {

    protected final int radius;

    public BlockAllocatorBlockEntities(int radius) {
        this.radius = radius;
    }

    @Override
    public Set<BlockPos> allocate(ExplosionAEF explosion, Level level, double x, double y, double z, float size) {
        Set<BlockPos> out = new HashSet<>();
        int r = radius > 0 ? radius : Mth.ceil(size);
        if (r <= 0) {
            return out;
        }
        double r2 = (double) r * (double) r;

        int minChunkX = SectionPos.blockToSectionCoord(Mth.floor(x - r));
        int maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(x + r));
        int minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(z - r));
        int maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(z + r));

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockPos pos : chunk.getBlockEntitiesPos()) {
                    double dx = pos.getX() + 0.5 - x;
                    double dy = pos.getY() + 0.5 - y;
                    double dz = pos.getZ() + 0.5 - z;
                    if (dx * dx + dy * dy + dz * dz <= r2) {
                        out.add(pos.immutable());
                    }
                }
            }
        }

        return out;
    }
}
