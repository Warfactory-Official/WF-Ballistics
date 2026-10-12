package com.wf.wflib.client.map.xaero;

import com.wf.wflib.client.map.MapPainter;
import com.wf.wflib.client.map.MapView;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Xaero's world map as last rendered. {@code GuiMap}: framebuffer px, centre = integer half of the window,
 * {@code scale} = framebuffer px/block; GUI = framebuffer / {@code guiScale}.
 *
 * @param centreX {@code getWidth() / 2}, integer division as Xaero's
 */
record XaeroMapView(ResourceKey<Level> dimension, double cameraX, double cameraZ, double scale, double guiScale,
                    int centreX, int centreY) implements MapView {

    @Override
    public double toScreenX(double blockX) {
        return ((blockX - this.cameraX) * this.scale + this.centreX) / this.guiScale;
    }

    @Override
    public double toScreenY(double blockZ) {
        return ((blockZ - this.cameraZ) * this.scale + this.centreY) / this.guiScale;
    }

    @Override
    public double blocksPerPixel() {
        return this.guiScale / this.scale;
    }

    double toBlockX(double guiX) {
        return this.cameraX + (guiX * this.guiScale - this.centreX) / this.scale;
    }

    double toBlockZ(double guiY) {
        return this.cameraZ + (guiY * this.guiScale - this.centreY) / this.scale;
    }

    MapPainter.Affine affine() {
        double k = this.scale / this.guiScale;
        return new MapPainter.Affine(k, 0.0, (this.centreX - this.cameraX * this.scale) / this.guiScale,
                0.0, k, (this.centreY - this.cameraZ * this.scale) / this.guiScale);
    }
}
