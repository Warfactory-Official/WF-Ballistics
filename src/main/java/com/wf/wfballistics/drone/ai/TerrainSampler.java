package com.wf.wfballistics.drone.ai;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/** Reads terrain heights for the planner. */
public final class TerrainSampler {

    private TerrainSampler() {
    }

    /**
     * @param fallback used when the column isn't loaded: the mission's own target height is a good choice
     * @return the surface height at this column, i.e. the Y a drone would rest on
     */
    public static double groundY(ServerLevel level, double x, double z, double fallback) {
        double measured = measure(level, x, z);
        return Double.isNaN(measured) ? fallback : measured;
    }

    /**
     * The same reading, but honest about not having one.
     *
     * @return the surface height, or {@link Double#NaN} if the column isn't loaded
     */
    public static double measure(ServerLevel level, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        LevelChunk chunk = level.getChunkSource().getChunkNow(SectionPos.blockToSectionCoord(bx),
                SectionPos.blockToSectionCoord(bz));
        if (chunk == null) {
            return Double.NaN;
        }
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz) + 1;
    }
}
