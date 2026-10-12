package com.wf.wflib.client.map.journeymap;

import com.wf.wflib.client.map.MapView;
import journeymap.api.v2.client.fullscreen.IFullscreen;
import journeymap.api.v2.client.util.UIState;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Block coords, to and from JourneyMap's fullscreen map; JM's numbers span several unmatched spaces.
 *
 * <ul>
 *   <li>{@code UIState.blockSize}: framebuffer px/block ({@code zoom / 512}).</li>
 *   <li>{@code FullscreenMapEvent} mouse pos: framebuffer px, despite its javadoc calling it "scaled".</li>
 *   <li>{@link net.minecraft.client.gui.GuiGraphics}: GUI units => the two transform directions here
 *       are not symmetric.</li>
 *   <li>{@code UIState.blockBounds} NOT an origin: lags one zoom step behind, excludes in-progress
 *       drag (JM applies drag separately at render). Measured vs JM: exact at one zoom, 24.5 blocks
 *       off two zoom steps later, no symptom but a misplaced line.</li>
 *   <li>{@code UIState.displayBounds} unused, unit ambiguous (overhangs window 32px/side; fitting it
 *       gave contradictory scales). Fullscreen map fills the window => viewport centre = window
 *       centre; that plus {@link IFullscreen#getCenterBlockX(boolean)} is the whole transform.
 *       Checked: 3 readings, 2 zooms, 2 window sizes.</li>
 * </ul>
 *
 * @param centreBlockX block under viewport centre, drag included
 * @param centreBlockZ block under viewport centre, drag included
 * @param centreFbX viewport centre, framebuffer px
 * @param centreFbY viewport centre, framebuffer px
 * @param blockSize framebuffer px/block
 * @param dragX in-progress drag, blocks; diagnostics only
 * @param dragZ in-progress drag, blocks; diagnostics only
 */
public record MapTransform(double centreBlockX, double centreBlockZ, double centreFbX, double centreFbY,
                           double blockSize, double guiScale, double dragX, double dragZ,
                           ResourceKey<Level> dimension) implements MapView {

    /** @return null: map not currently shown. */
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
                centreBlockZ - fullscreen.getCenterBlockZ(false), state.dimension);
    }

    /** mouseX: framebuffer px ({@code FullscreenMapEvent}). */
    public double toBlockX(double mouseX) {
        return this.centreBlockX + (mouseX - this.centreFbX) / this.blockSize;
    }

    /** mouseY: framebuffer px ({@code FullscreenMapEvent}). */
    public double toBlockZ(double mouseY) {
        return this.centreBlockZ + (mouseY - this.centreFbY) / this.blockSize;
    }

    @Override
    public double toScreenX(double blockX) {
        return (this.centreFbX + (blockX - this.centreBlockX) * this.blockSize) / this.guiScale;
    }

    @Override
    public double toScreenY(double blockZ) {
        return (this.centreFbY + (blockZ - this.centreBlockZ) * this.blockSize) / this.guiScale;
    }

    @Override
    public double blocksPerPixel() {
        return this.guiScale / this.blockSize;
    }
}
