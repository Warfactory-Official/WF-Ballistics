package com.wf.wflib.entity.glyphid.sim;

import com.wf.wflib.drone.WorldThread;
import com.wf.wflib.entity.glyphid.nav.GlyphidFlowFields;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/** {@link SimWorld} asked straight through to the level, on the world thread. */
public record SimWorldLive(ServerLevel level) implements SimWorld {

    @Override
    public int height(int columnX, int columnZ) {
        WorldThread.assertOn("the glyphid sim tier's heightmap read");
        if (!level.hasChunk(columnX >> 4, columnZ >> 4)) {
            return UNKNOWN;
        }
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, columnX, columnZ);
    }

    @Override
    public Destination destination(int x, int y, int z) {
        WorldThread.assertOn("the glyphid sim tier's flow field lookup");
        return new Destination(GlyphidFlowFields.fieldFor(level, x, y, z), height(x, z));
    }
}
