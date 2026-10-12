package com.wf.wflib.client.map;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** A fullscreen map as currently shown. Screen = GUI units ({@code GuiGraphics}). */
public interface MapView {

    ResourceKey<Level> dimension();

    double toScreenX(double blockX);

    double toScreenY(double blockZ);

    /** GUI px; sizes grab radius, sample step. JourneyMap floor 512 blocks/px => size nothing drawn in blocks. */
    double blocksPerPixel();

    /** Step >= 1 block, ~2px worth: no more segments than there are pixels for them. */
    default double sampleStep() {
        return Math.max(1.0, 2.0 * blocksPerPixel());
    }
}
