package com.wf.wflib.client.journeymap.rail;

import journeymap.api.v2.client.fullscreen.IFullscreen;
import journeymap.api.v2.client.util.UIState;
import net.minecraft.client.Minecraft;

import java.awt.geom.Rectangle2D;

/**
 * Block coordinates to JourneyMap's fullscreen map, and back.
 *
 * <p>JourneyMap hands out several numbers and they are not all in the same space, which is the part
 * that costs an afternoon if it is assumed rather than measured. What survived measurement:</p>
 *
 * <ul>
 *   <li>{@code UIState.blockSize} is <b>framebuffer</b> pixels per block ({@code zoom / 512}).</li>
 *   <li>A mouse position on a {@code FullscreenMapEvent} is in <b>framebuffer</b> pixels, despite the
 *       javadoc calling it "scaled".</li>
 *   <li>{@link net.minecraft.client.gui.GuiGraphics} draws in <b>GUI</b> units, so input and output are
 *       in different spaces and the two directions here are not symmetric.</li>
 *   <li><b>{@code UIState.blockBounds} is not usable as an origin.</b> It lags: it still describes the
 *       previous view after a zoom, and it excludes an in-progress drag, which JourneyMap applies
 *       separately at render time. Measured against JourneyMap's own answer, it agreed exactly at one
 *       zoom and was 24.5 blocks out two zoom steps later, with no symptom but a line drawn in the
 *       wrong place.</li>
 *   <li><b>{@code UIState.displayBounds} is not needed and its unit is ambiguous</b> (it overhangs the
 *       window by 32 on each side, and fitting it to observations gave contradictory scales). The
 *       fullscreen map fills the window, so the viewport centre is the window centre, and that plus
 *       {@link IFullscreen#getCenterBlockX(boolean)} is the whole transform. Checked against three
 *       readings from a running client at two zoom levels and two window sizes.</li>
 * </ul>
 *
 * @param centreBlockX block under the centre of the viewport, drag included
 * @param centreBlockZ block under the centre of the viewport, drag included
 * @param centreFbX viewport centre in framebuffer pixels
 * @param centreFbY viewport centre in framebuffer pixels
 * @param blockSize framebuffer pixels per block
 * @param guiScale the window's GUI scale
 * @param dragX blocks of in-progress drag, kept for diagnostics
 * @param dragZ blocks of in-progress drag, kept for diagnostics
 */
public record MapTransform(double centreBlockX, double centreBlockZ, double centreFbX, double centreFbY,
                           double blockSize, double guiScale, double dragX, double dragZ) {

    /** @return the transform for the map as it is currently drawn, or null when it shows nothing. */
    public static MapTransform of(IFullscreen fullscreen) {
        if (fullscreen == null) {
            return null;
        }
        UIState state = fullscreen.getUiState();
        if (state == null || !state.active || state.blockSize <= 0.0) {
            return null;
        }
        var window = Minecraft.getInstance().getWindow();
        double guiScale = window.getGuiScale();
        if (guiScale <= 0.0) {
            guiScale = 1.0;
        }
        double centreBlockX = fullscreen.getCenterBlockX(true);
        double centreBlockZ = fullscreen.getCenterBlockZ(true);
        return new MapTransform(centreBlockX, centreBlockZ,
                window.getWidth() / 2.0, window.getHeight() / 2.0, state.blockSize, guiScale,
                centreBlockX - fullscreen.getCenterBlockX(false),
                centreBlockZ - fullscreen.getCenterBlockZ(false));
    }

    /** Block X under a mouse position from a {@code FullscreenMapEvent}. */
    public double toBlockX(double mouseX) {
        return this.centreBlockX + (mouseX - this.centreFbX) / this.blockSize;
    }

    /** Block Z under a mouse position from a {@code FullscreenMapEvent}. */
    public double toBlockZ(double mouseY) {
        return this.centreBlockZ + (mouseY - this.centreFbY) / this.blockSize;
    }

    /** Where to draw a block X, in the GUI units {@code GuiGraphics} works in. */
    public double toScreenX(double blockX) {
        return (this.centreFbX + (blockX - this.centreBlockX) * this.blockSize) / this.guiScale;
    }

    /** Where to draw a block Z, in the GUI units {@code GuiGraphics} works in. */
    public double toScreenY(double blockZ) {
        return (this.centreFbY + (blockZ - this.centreBlockZ) * this.blockSize) / this.guiScale;
    }

    /**
     * Blocks covered by one drawn pixel.
     *
     * <p>In GUI units, because this sizes things a viewer sees: a grab radius, a sample step. Nothing
     * drawn here may have a width measured in blocks, because at the fullscreen zoom floor one drawn
     * pixel covers 512 of them.</p>
     */
    public double blocksPerPixel() {
        return this.guiScale / this.blockSize;
    }

    /**
     * How far apart to sample a curve so it draws smooth without emitting more segments than there are
     * pixels to put them in.
     */
    public double sampleStep() {
        return Math.max(1.0, 2.0 * blocksPerPixel());
    }
}
