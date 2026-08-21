package com.wf.wfballistics.drone.ai;

import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Reads terrain heights for the planner. Called on the world thread while building a {@link DroneSnapshot},
 * because the workers must never touch chunks themselves.
 *
 * <p>Never forces a chunk to load: a drone asking about ground it hasn't reached yet (or a destination on
 * the far side of an unloaded region) gets the caller's fallback instead of dragging chunk generation onto
 * the tick.
 */
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
     * <p>Used where guessing would be worse than doing nothing: the hard floor that stops a drone sinking
     * into the ground would, given a fallback height from somewhere else entirely, cheerfully shove it up to
     * a surface that isn't there.
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
