package com.wf.wflib.client.map.xaero;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.client.map.MapHost;
import com.wf.wflib.client.map.MapPainter;
import com.wf.wflib.client.map.MapShape;
import com.wf.wflib.client.map.WFMaps;
import com.wf.wflib.mixin.xaero.minimap.AccessorMinimapElementMapRendererHandler;
import com.wf.wflib.mixin.xaero.minimap.AccessorMinimapFBORenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import xaero.common.HudMod;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

import java.util.List;

/**
 * Xaero's Minimap: non-fullscreen layers inside the minimap frame (clipped by its framebuffer). Display only.
 * Safe mode (no framebuffer) => nothing drawn.
 */
public final class XaeroMinimapHost implements MapHost {

    private final MapPainter painter = new MapPainter();
    @Nullable
    private MinimapElementMapRendererHandler handler;

    private XaeroMinimapHost() {
    }

    public static void attach() {
        XaeroMinimapHost host = new XaeroMinimapHost();
        WFMaps.addHost(host);
        NeoForge.EVENT_BUS.register(host);
    }

    @Override
    public String name() {
        return "xaerominimap";
    }

    @Override
    public void show(String layer, ResourceKey<Level> dimension, List<MapShape> shapes) {
        this.painter.show(layer, dimension, shapes);
    }

    /** The in-map handler is built with the minimap's framebuffers and again on each reload of them. */
    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        HudMod mod = HudMod.INSTANCE;
        if (mod == null || mod.getMinimap() == null || mod.getMinimap().getMinimapFBORenderer() == null) {
            return;
        }
        MinimapElementMapRendererHandler current =
                ((AccessorMinimapFBORenderer) mod.getMinimap().getMinimapFBORenderer()).wflib$inMapHandler();
        if (current != null && current != this.handler) {
            current.add(new Renderer(this));
            this.handler = current;
        }
    }

    /**
     * Element pose: framebuffer px, origin = player, axes unrotated. Offsets per Xaero's
     * {@code transformAndRenderForRenderer}: {@code X = (ps*dx - pc*dz)*zoom}, {@code Y = (pc*dx + ps*dz)*zoom}.
     */
    private void render(GuiGraphics graphics, MinimapElementRenderInfo info) {
        AccessorMinimapElementMapRendererHandler h = (AccessorMinimapElementMapRendererHandler) this.handler;
        if (h == null) {
            return;
        }
        double ps = h.wflib$ps() * h.wflib$zoom();
        double pc = h.wflib$pc() * h.wflib$zoom();
        double rx = info.renderPos.x;
        double rz = info.renderPos.z;
        MapPainter.Affine affine = new MapPainter.Affine(ps, -pc, -(ps * rx - pc * rz), pc, ps, -(pc * rx + ps * rz));
        double half = h.wflib$halfView();
        Canvas canvas = Canvas.shared().begin(graphics);
        try {
            this.painter.paint(canvas, info.mapDimension, affine, false, -half, -half, half, half, Double.NaN,
                    Double.NaN);
        } finally {
            canvas.end();
        }
    }

    private static final class Renderer extends MinimapElementRenderer<XaeroMinimapHost, double[]> {

        Renderer(XaeroMinimapHost host) {
            super(new Reader(), new Provider(host), new double[2]);
        }

        /** Below radar and waypoints. */
        @Override
        public int getOrder() {
            return -1000;
        }

        @Override
        public boolean renderElement(XaeroMinimapHost host, boolean highlighted, boolean outOfBounds,
                                     double optionalDepth, float optionalScale, double partialX, double partialY,
                                     MinimapElementRenderInfo info, GuiGraphics graphics,
                                     MultiBufferSource.BufferSource buffers) {
            host.render(graphics, info);
            return false;
        }

        @Override
        public void preRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                              MultiTextureRenderTypeRendererProvider provider) {
            this.context[0] = info.renderPos.x;
            this.context[1] = info.renderPos.z;
        }

        @Override
        public void postRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                               MultiTextureRenderTypeRendererProvider provider) {
        }

        @Override
        public boolean shouldRender(MinimapElementRenderLocation location) {
            return location == MinimapElementRenderLocation.IN_MINIMAP;
        }
    }

    private static final class Provider extends MinimapElementRenderProvider<XaeroMinimapHost, double[]> {

        private final XaeroMinimapHost host;
        private boolean given;

        Provider(XaeroMinimapHost host) {
            this.host = host;
        }

        @Override
        public void begin(MinimapElementRenderLocation location, double[] context) {
            this.given = false;
        }

        @Override
        public boolean hasNext(MinimapElementRenderLocation location, double[] context) {
            return !this.given;
        }

        @Override
        public XaeroMinimapHost getNext(MinimapElementRenderLocation location, double[] context) {
            this.given = true;
            return this.host;
        }

        @Override
        public void end(MinimapElementRenderLocation location, double[] context) {
        }
    }

    /** One element at the render position ({@code preRender}): zero offset, coordinate scale = the background's. */
    private static final class Reader extends MinimapElementReader<XaeroMinimapHost, double[]> {

        @Override
        public boolean isHidden(XaeroMinimapHost host, double[] context) {
            return false;
        }

        @Override
        public double getCoordinateScale(XaeroMinimapHost host, double[] context, MinimapElementRenderInfo info) {
            return info.backgroundCoordinateScale;
        }

        @Override
        public double getRenderX(XaeroMinimapHost host, double[] context, float partialTicks) {
            return context[0];
        }

        @Override
        public double getRenderY(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0.0;
        }

        @Override
        public double getRenderZ(XaeroMinimapHost host, double[] context, float partialTicks) {
            return context[1];
        }

        @Override
        public int getInteractionBoxLeft(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxRight(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxTop(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxBottom(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxLeft(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxRight(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxTop(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxBottom(XaeroMinimapHost host, double[] context, float partialTicks) {
            return 0;
        }

        @Override
        public int getLeftSideLength(XaeroMinimapHost host, Minecraft mc) {
            return 0;
        }

        @Override
        public String getMenuName(XaeroMinimapHost host) {
            return "";
        }

        @Override
        public String getFilterName(XaeroMinimapHost host) {
            return "";
        }

        @Override
        public int getMenuTextFillLeftPadding(XaeroMinimapHost host) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(XaeroMinimapHost host) {
            return 0;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return false;
        }
    }
}
