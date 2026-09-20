package com.wf.wflib.client.cam;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/** A section grid of a feed's own, centred on the drone instead of the player. */
public final class FeedViewArea extends ViewArea {

    /** Every feed grid in play. */
    private static final List<FeedViewArea> LIVE = new ArrayList<>(4);

    FeedViewArea(SectionRenderDispatcher dispatcher, Level level, int viewDistance, LevelRenderer renderer) {
        super(dispatcher, level, viewDistance, renderer);
        LIVE.add(this);
    }

    public static void dirtyAll(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread) {
        for (int i = 0; i < LIVE.size(); i++) {
            LIVE.get(i).setDirtyIfPresent(sectionX, sectionY, sectionZ, reRenderOnMainThread);
        }
    }

    /** Mark a section dirty, but only if this grid is actually holding it. */
    private void setDirtyIfPresent(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread) {
        if (getRenderSectionAt(new BlockPos(SectionPos.sectionToBlockCoord(sectionX),
                SectionPos.sectionToBlockCoord(sectionY),
                SectionPos.sectionToBlockCoord(sectionZ))) != null) {
            setDirty(sectionX, sectionY, sectionZ, reRenderOnMainThread);
        }
    }

    /** Give the GPU buffers back and stop listening. */
    void release() {
        LIVE.remove(this);
        releaseAllBuffers();
    }
}
