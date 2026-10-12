package com.wf.wflib.client.map.journeymap;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.map.MapButton;
import com.wf.wflib.client.map.MapMenu;
import com.wf.wflib.client.map.MapPointer;
import com.wf.wflib.client.map.MapTool;
import com.wf.wflib.client.map.WFMaps;
import journeymap.api.v2.client.event.FullscreenDisplayEvent;
import journeymap.api.v2.client.event.FullscreenMapEvent;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
import journeymap.api.v2.client.event.PopupMenuEvent;
import journeymap.api.v2.client.fullscreen.IThemeButton;
import journeymap.api.v2.client.fullscreen.ModPopupMenu;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Fullscreen events -> {@link WFMaps#tools()}. One subscriber per event for the whole mod: JM keys plugin
 * state by mod id. Input is taken at {@code PRE}, the only cancellable stage.
 */
final class JourneyMapTools {

    private static final Logger LOGGER = LoggerFactory.getLogger("wflib/journeymap");

    /**
     * Clicks checked against {@code ClickEvent.getLocation()}. Several: an error proportional to position
     * reads zero at the origin. First transform was 32 blocks out at one zoom with no other symptom.
     */
    private static final int TRANSFORM_CHECKS = 4;

    private final Map<MapTool, IThemeButton> buttons = new IdentityHashMap<>();
    private MapTransform transform;
    private int transformChecks;

    void attach() {
        String mod = WFLib.MODID;
        FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(mod, this::onToolbar);
        FullscreenEventRegistry.FULLSCREEN_MAP_CLICK_EVENT.subscribe(mod, this::onClick);
        FullscreenEventRegistry.FULLSCREEN_MAP_DRAG_EVENT.subscribe(mod, this::onDrag);
        FullscreenEventRegistry.FULLSCREEN_MAP_MOVE_EVENT.subscribe(mod, this::onMove);
        FullscreenEventRegistry.FULLSCREEN_POPUP_MENU_EVENT.subscribe(mod, this::onPopupMenu);
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(mod, this::onRender);
    }

    private void onToolbar(FullscreenDisplayEvent.AddonButtonDisplayEvent event) {
        this.buttons.clear();
        for (MapTool tool : WFMaps.tools()) {
            MapButton spec = tool.button();
            if (spec == null || !tool.available()) {
                continue;
            }
            IThemeButton button = event.getThemeButtonDisplay().addThemeToggleButton(spec.onLabel(),
                    spec.offLabel(), spec.icon(), tool.armed(), pressed -> {
                        tool.setArmed(!tool.armed());
                        pressed.setToggled(tool.armed());
                    });
            button.setTooltip(spec.tooltip().toArray(String[]::new));
            this.buttons.put(tool, button);
        }
    }

    private void onClick(FullscreenMapEvent.ClickEvent event) {
        MapTransform t = this.transform;
        if (event.getStage() != FullscreenMapEvent.Stage.PRE || t == null) {
            return;
        }
        MapPointer pointer = new MapPointer(t.toBlockX(event.getMouseX()), t.toBlockZ(event.getMouseY()),
                event.getLocation().getY(), event.getButton(), t);
        check(t, event, pointer);
        dispatch(pointer, event, MapTool::click);
    }

    private void onDrag(FullscreenMapEvent.MouseDraggedEvent event) {
        MapTransform t = this.transform;
        if (event.getStage() != FullscreenMapEvent.Stage.PRE || t == null) {
            return;
        }
        dispatch(new MapPointer(t.toBlockX(event.getMouseX()), t.toBlockZ(event.getMouseY()),
                event.getLocation().getY(), event.getButton(), t), event, MapTool::drag);
    }

    private void dispatch(MapPointer pointer, FullscreenMapEvent event,
                          java.util.function.BiPredicate<MapTool, MapPointer> handler) {
        for (MapTool tool : WFMaps.tools()) {
            if (tool.available() && handler.test(tool, pointer)) {
                event.cancel();
                return;
            }
        }
    }

    private void onMove(FullscreenMapEvent.MouseMoveEvent event) {
        MapTransform t = this.transform;
        if (t == null) {
            return;
        }
        MapPointer pointer = new MapPointer(t.toBlockX(event.getMouseX()), t.toBlockZ(event.getMouseY()),
                MapPointer.UNKNOWN_Y, -1, t);
        for (MapTool tool : WFMaps.tools()) {
            if (tool.available()) {
                tool.move(pointer);
            }
        }
    }

    private void onPopupMenu(PopupMenuEvent.FullscreenPopupMenuEvent event) {
        MapTransform t = this.transform;
        if (t == null) {
            return;
        }
        MapMenu menu = wrap(event.getPopupMenu());
        for (MapTool tool : WFMaps.tools()) {
            if (tool.available()) {
                tool.menu(menu, t);
            }
        }
    }

    private static MapMenu wrap(ModPopupMenu popup) {
        return new MapMenu() {
            @Override
            public void item(String label, Consumer<BlockPos> action) {
                popup.addMenuItem(label, action::accept);
            }

            @Override
            public MapMenu sub(String label) {
                return wrap(popup.createSubItemList(label));
            }
        };
    }

    private void onRender(FullscreenRenderEvent event) {
        MapTransform t = MapTransform.of(event.getFullscreen());
        this.transform = t;
        if (t == null) {
            return;
        }
        this.buttons.forEach((tool, button) -> {
            if (button.getToggled() != tool.armed()) {
                button.setToggled(tool.armed());
            }
        });
        Canvas canvas = Canvas.shared().begin(event.getGraphics());
        try {
            for (MapTool tool : WFMaps.tools()) {
                if (tool.available()) {
                    tool.render(canvas, t);
                }
            }
        } finally {
            canvas.end();
        }
    }

    private void check(MapTransform t, FullscreenMapEvent.ClickEvent event, MapPointer pointer) {
        if (this.transformChecks >= TRANSFORM_CHECKS) {
            return;
        }
        this.transformChecks++;
        double slack = Math.max(1.0, t.blocksPerPixel());
        double dx = Math.abs(pointer.blockX() - event.getLocation().getX());
        double dz = Math.abs(pointer.blockZ() - event.getLocation().getZ());
        if (dx > slack || dz > slack) {
            LOGGER.warn("[wflib] map transform disagrees with JourneyMap by ({}, {}) blocks at {} blocks/px;"
                            + " mouse=({}, {}) jm={} mine=({}, {}) centre=({}, {}) drag=({}, {}) blockSize={} gui={}",
                    fmt(dx), fmt(dz), fmt(t.blocksPerPixel()), event.getMouseX(), event.getMouseY(),
                    event.getLocation().toShortString(), fmt(pointer.blockX()), fmt(pointer.blockZ()),
                    t.centreBlockX(), t.centreBlockZ(), t.dragX(), t.dragZ(), t.blockSize(), t.guiScale());
        } else if (this.transformChecks == 1) {
            LOGGER.info("[wflib] map transform agrees with JourneyMap to within {} block(s)", fmt(Math.max(dx, dz)));
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
