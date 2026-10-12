package com.wf.wflib.client.map.xaero;

import com.wf.gemrender.ui.Canvas;
import com.mojang.blaze3d.platform.Window;
import com.wf.wflib.client.map.MapButton;
import com.wf.wflib.client.map.MapHost;
import com.wf.wflib.client.map.MapMenu;
import com.wf.wflib.client.map.MapPainter;
import com.wf.wflib.client.map.MapPointer;
import com.wf.wflib.client.map.MapShape;
import com.wf.wflib.client.map.MapTool;
import com.wf.wflib.client.map.WFMaps;
import com.wf.wflib.mixin.xaero.worldmap.AccessorGuiMap;
import com.wf.wflib.mixin.xaero.worldmap.AccessorXaeroScreenBase;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xaero.map.WorldMap;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.gui.GuiMap;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;

/**
 * Xaero's World Map. No overlay or toolbar API: layers and tool graphics draw from one element renderer (under
 * Xaero's UI), tool input comes from screen events, menu items from {@code MixinGuiMap}.
 */
public final class XaeroWorldMapHost implements MapHost {

    private static final Logger LOGGER = LoggerFactory.getLogger("wflib/xaero");
    /** {@code GuiMap} height sentinel. */
    static final int UNKNOWN_Y = 32767;
    /** Same purpose as JourneyMapTools'. */
    private static final int TRANSFORM_CHECKS = 4;
    /** Xaero snaps its hovered block to the texture level, up to 8 blocks zoomed out. */
    private static final double TRANSFORM_SLACK = 8.0;
    private static final int BUTTON_SIZE = 20;
    /** Right column above Xaero's zoom-out button ({@code height - 180}). Top right = its compass; top left = tool text. */
    private static final int BUTTON_BOTTOM = 180;
    /** {@code GuiMap} draws its UI at z 970..1000 with depth; vanilla tooltip z 400 lands under it. */
    private static final float TOOLTIP_Z = 1000.0f;

    @Nullable
    private static XaeroWorldMapHost instance;

    private final MapPainter painter = new MapPainter();
    @Nullable
    private XaeroMapView view;
    @Nullable
    private MapShape hovered;
    private double lastMouseX = Double.NaN;
    private double lastMouseY = Double.NaN;
    private final List<ToolButton> buttons = new ArrayList<>();
    private boolean registered;
    private int transformChecks;

    private XaeroWorldMapHost() {
    }

    public static void attach() {
        instance = new XaeroWorldMapHost();
        WFMaps.addHost(instance);
        NeoForge.EVENT_BUS.register(instance);
    }

    @Override
    public String name() {
        return "xaeroworldmap";
    }

    @Override
    public void show(String layer, ResourceKey<Level> dimension, List<MapShape> shapes) {
        this.painter.show(layer, dimension, shapes);
    }

    /** Xaero builds its element handler in its own client load, order against ours unknown. */
    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) {
        if (!this.registered && WorldMap.mapElementRenderHandler != null) {
            WorldMap.mapElementRenderHandler.add(new OverlayRenderer(this));
            this.registered = true;
        }
    }

    /**
     * Element pose: framebuffer px, origin = map centre. Undone to GUI px from the top left, the space
     * {@link MapTool#render} and {@link XaeroMapView} use.
     */
    void render(GuiGraphics graphics, ElementRenderInfo info) {
        Window window = Minecraft.getInstance().getWindow();
        XaeroMapView v = new XaeroMapView(info.mapDimension, info.renderPos.x, info.renderPos.z, info.scale,
                window.getGuiScale(), window.getWidth() / 2, window.getHeight() / 2);
        this.view = v;
        graphics.pose().pushPose();
        graphics.pose().translate(-v.centreX(), -v.centreY(), 0.0f);
        graphics.pose().scale((float) v.guiScale(), (float) v.guiScale(), 1.0f);
        double mouseX = v.toScreenX(info.mouseX);
        double mouseY = v.toScreenY(info.mouseZ);
        Canvas canvas = Canvas.shared().begin(graphics);
        try {
            this.hovered = this.painter.paint(canvas, v.dimension(), v.affine(), true, 0.0, 0.0,
                    graphics.guiWidth(), graphics.guiHeight(), mouseX, mouseY);
            for (MapTool tool : WFMaps.tools()) {
                if (tool.available()) {
                    tool.render(canvas, v);
                }
            }
        } finally {
            canvas.end();
        }
        if (mouseX != this.lastMouseX || mouseY != this.lastMouseY) {
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            MapPointer pointer = new MapPointer(info.mouseX, info.mouseZ, MapPointer.UNKNOWN_Y, -1, v);
            for (MapTool tool : WFMaps.tools()) {
                if (tool.available()) {
                    tool.move(pointer);
                }
            }
        }
        graphics.pose().popPose();
    }

    // -- toolbar ----------------------------------------------------------------------------------

    @SubscribeEvent
    public void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GuiMap map)) {
            return;
        }
        this.buttons.clear();
        int y = map.height - BUTTON_BOTTOM;
        for (MapTool tool : WFMaps.tools()) {
            MapButton spec = tool.button();
            if (spec == null) {
                continue;
            }
            y -= BUTTON_SIZE;
            ToolButton button = new ToolButton(map.width - BUTTON_SIZE, y, BUTTON_SIZE, tool, spec);
            this.buttons.add(button);
            event.addListener(button);
        }
    }

    @SubscribeEvent
    public void onRenderPre(ScreenEvent.Render.Pre event) {
        if (event.getScreen() instanceof GuiMap) {
            for (ToolButton button : this.buttons) {
                button.visible = button.tool().available() && !GuiMap.hiddenUI;
            }
        }
    }

    @SubscribeEvent
    public void onRenderPost(ScreenEvent.Render.Post event) {
        MapShape shape = this.hovered;
        if (!(event.getScreen() instanceof GuiMap map) || shape == null || shape.look().title().isEmpty()
                || overUi(map, event.getMouseX(), event.getMouseY())) {
            return;
        }
        List<Component> lines = Arrays.stream(shape.look().title().split("\n")).map(Component::literal)
                .map(Component.class::cast).toList();
        GuiGraphics graphics = event.getGuiGraphics();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0f, 0.0f, TOOLTIP_Z);
        graphics.renderComponentTooltip(Minecraft.getInstance().font, lines, event.getMouseX(), event.getMouseY());
        graphics.pose().popPose();
    }

    @SubscribeEvent
    public void onClosing(ScreenEvent.Closing event) {
        if (event.getScreen() instanceof GuiMap) {
            this.painter.leave();
            this.view = null;
            this.hovered = null;
            this.lastMouseX = Double.NaN;
        }
    }

    // -- input ------------------------------------------------------------------------------------

    @SubscribeEvent
    public void onPress(ScreenEvent.MouseButtonPressed.Pre event) {
        XaeroMapView v = this.view;
        if (!(event.getScreen() instanceof GuiMap map) || v == null
                || overUi(map, event.getMouseX(), event.getMouseY())) {
            return;
        }
        MapPointer pointer = pointer(map, v, event.getMouseX(), event.getMouseY(), event.getButton());
        check(map, v, pointer);
        if (dispatch(pointer, MapTool::click)) {
            event.setCanceled(true);
        }
    }

    /** Xaero pans from its own press state: a consumed press never starts a pan. */
    @SubscribeEvent
    public void onDrag(ScreenEvent.MouseDragged.Pre event) {
        XaeroMapView v = this.view;
        if (!(event.getScreen() instanceof GuiMap map) || v == null
                || overUi(map, event.getMouseX(), event.getMouseY())) {
            return;
        }
        if (dispatch(pointer(map, v, event.getMouseX(), event.getMouseY(), event.getMouseButton()), MapTool::drag)) {
            event.setCanceled(true);
        }
    }

    private static boolean dispatch(MapPointer pointer, BiPredicate<MapTool, MapPointer> handler) {
        for (MapTool tool : WFMaps.tools()) {
            if (tool.available() && handler.test(tool, pointer)) {
                return true;
            }
        }
        return false;
    }

    /** Open menu or a widget under the cursor => Xaero's input, not the map's. */
    private static boolean overUi(GuiMap map, double mouseX, double mouseY) {
        return ((AccessorXaeroScreenBase) map).wflib$openDropdown() != null
                || map.getChildAt(mouseX, mouseY).isPresent();
    }

    private static MapPointer pointer(GuiMap map, XaeroMapView v, double mouseX, double mouseY, int button) {
        int y = ((AccessorGuiMap) map).wflib$mouseBlockY();
        return new MapPointer(v.toBlockX(mouseX), v.toBlockZ(mouseY), y == UNKNOWN_Y ? MapPointer.UNKNOWN_Y : y,
                button, v);
    }

    private void check(GuiMap map, XaeroMapView v, MapPointer pointer) {
        if (this.transformChecks >= TRANSFORM_CHECKS) {
            return;
        }
        this.transformChecks++;
        AccessorGuiMap xaero = (AccessorGuiMap) map;
        double dx = Math.abs(Math.floor(pointer.blockX()) - xaero.wflib$mouseBlockX());
        double dz = Math.abs(Math.floor(pointer.blockZ()) - xaero.wflib$mouseBlockZ());
        if (dx > TRANSFORM_SLACK + v.blocksPerPixel() || dz > TRANSFORM_SLACK + v.blocksPerPixel()) {
            LOGGER.warn("[wflib] map transform disagrees with Xaero's world map by ({}, {}) blocks at {} blocks/px;"
                            + " mine=({}, {}) xaero=({}, {}) view={}", fmt(dx), fmt(dz), fmt(v.blocksPerPixel()),
                    fmt(pointer.blockX()), fmt(pointer.blockZ()), xaero.wflib$mouseBlockX(),
                    xaero.wflib$mouseBlockZ(), v);
        } else if (this.transformChecks == 1) {
            LOGGER.info("[wflib] map transform agrees with Xaero's world map to within {} block(s)",
                    fmt(Math.max(dx, dz)));
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // -- menu -------------------------------------------------------------------------------------

    /** From {@code MixinGuiMap}; {@code y} = Xaero's clicked surface or {@link #UNKNOWN_Y}. */
    public static void menu(GuiMap map, @Nullable ArrayList<RightClickOption> options, int x, int y, int z) {
        XaeroWorldMapHost host = instance;
        if (host == null || options == null || host.view == null) {
            return;
        }
        var player = Minecraft.getInstance().player;
        // Unknown surface: the player's height, the nearest thing to a guess the client has.
        int surface = y != UNKNOWN_Y ? y : player == null ? 64 : player.getBlockY();
        MapMenu menu = new XaeroMenu(map, options, map, new BlockPos(x, surface, z));
        for (MapTool tool : WFMaps.tools()) {
            if (tool.available()) {
                tool.menu(menu, host.view);
            }
        }
    }
}
