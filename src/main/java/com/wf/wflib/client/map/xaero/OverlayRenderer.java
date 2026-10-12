package com.wf.wflib.client.map.xaero;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * One element at the camera covering the screen: Xaero translates each element to its position, culls by its
 * box. Never hovered or menu-listed.
 */
final class OverlayRenderer extends ElementRenderer<XaeroWorldMapHost, OverlayRenderer.Camera, OverlayRenderer> {

    /** Xaero's renderPos, read back by the reader. */
    static final class Camera {
        double x;
        double z;
    }

    /** Box half-size, px: past any screen. */
    private static final int BOX = 1 << 20;

    OverlayRenderer(XaeroWorldMapHost host) {
        super(new Camera(), new Provider(host), new Reader());
    }

    /** Below waypoints and players. */
    @Override
    public int getOrder() {
        return -1000;
    }

    @Override
    public boolean shouldBeDimScaled() {
        return false;
    }

    @Override
    public boolean shouldRenderHovered(boolean pre) {
        return false;
    }

    @Override
    public void preRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                          MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
        this.context.x = info.renderPos.x;
        this.context.z = info.renderPos.z;
    }

    @Override
    public void postRender(ElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                           MultiTextureRenderTypeRendererProvider provider, boolean shadow) {
    }

    @Override
    public void renderElementShadow(XaeroWorldMapHost host, boolean hovered, float optionalScale, double partialX,
                                    double partialY, ElementRenderInfo info, GuiGraphics graphics,
                                    MultiBufferSource.BufferSource buffers,
                                    MultiTextureRenderTypeRendererProvider provider) {
    }

    @Override
    public boolean renderElement(XaeroWorldMapHost host, boolean hovered, double optionalDepth, float optionalScale,
                                 double partialX, double partialY, ElementRenderInfo info, GuiGraphics graphics,
                                 MultiBufferSource.BufferSource buffers,
                                 MultiTextureRenderTypeRendererProvider provider) {
        host.render(graphics, info);
        return false;
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean shadow) {
        return location == ElementRenderLocation.WORLD_MAP && !shadow;
    }

    private static final class Provider extends ElementRenderProvider<XaeroWorldMapHost, Camera> {

        private final XaeroWorldMapHost host;
        private boolean given;

        Provider(XaeroWorldMapHost host) {
            this.host = host;
        }

        @Override
        public void begin(ElementRenderLocation location, Camera camera) {
            this.given = false;
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Camera camera) {
            return !this.given;
        }

        @Override
        public XaeroWorldMapHost getNext(ElementRenderLocation location, Camera camera) {
            this.given = true;
            return this.host;
        }

        @Override
        public void end(ElementRenderLocation location, Camera camera) {
        }
    }

    private static final class Reader extends ElementReader<XaeroWorldMapHost, Camera, OverlayRenderer> {

        @Override
        public boolean isHidden(XaeroWorldMapHost host, Camera camera) {
            return false;
        }

        @Override
        public double getRenderX(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return camera.x;
        }

        @Override
        public double getRenderZ(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return camera.z;
        }

        @Override
        public int getInteractionBoxLeft(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxRight(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxTop(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxBottom(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxLeft(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return -BOX;
        }

        @Override
        public int getRenderBoxRight(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return BOX;
        }

        @Override
        public int getRenderBoxTop(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return -BOX;
        }

        @Override
        public int getRenderBoxBottom(XaeroWorldMapHost host, Camera camera, float partialTicks) {
            return BOX;
        }

        @Override
        public int getLeftSideLength(XaeroWorldMapHost host, Minecraft mc) {
            return 0;
        }

        @Override
        public String getMenuName(XaeroWorldMapHost host) {
            return "";
        }

        @Override
        public String getFilterName(XaeroWorldMapHost host) {
            return "";
        }

        @Override
        public int getMenuTextFillLeftPadding(XaeroWorldMapHost host) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(XaeroWorldMapHost host) {
            return 0;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return false;
        }
    }
}
