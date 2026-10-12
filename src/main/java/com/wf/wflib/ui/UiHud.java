package com.wf.wflib.ui;

import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.WFLib;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hosts every HUD {@link UiInstance} in two GUI layers: {@link Layer#BACK} under all vanilla layers
 * (full-screen sight overlays), {@link Layer#MAIN} under chat. Skipped while the GUI is hidden.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class UiHud {
    public static final ResourceLocation BACK_LAYER = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "hud_back");
    public static final ResourceLocation MAIN_LAYER = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "hud");

    public enum Layer {
        BACK,
        MAIN
    }

    /** Called every frame; adds the instances to draw now, in order. */
    @FunctionalInterface
    public interface Source {
        void collect(Collector out);
    }

    public interface Collector {
        void add(UiInstance instance);
    }

    private static final CopyOnWriteArrayList<Source> BACK = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Source> MAIN = new CopyOnWriteArrayList<>();
    private static final Drawer DRAWER = new Drawer();
    private static long frame;

    private UiHud() {
    }

    public static void addSource(Layer layer, Source source) {
        (layer == Layer.BACK ? BACK : MAIN).add(source);
    }

    @SubscribeEvent
    static void onRegisterLayers(RegisterGuiLayersEvent event) {
        event.registerBelowAll(BACK_LAYER, (graphics, delta) -> render(graphics, delta, BACK));
        event.registerBelow(VanillaGuiLayers.CHAT, MAIN_LAYER, (graphics, delta) -> render(graphics, delta, MAIN));
    }

    /** Increments once per rendered frame; per-frame memo key. */
    public static long frame() {
        return frame;
    }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        frame++;
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(UiSignals::register);
    }

    private static void render(GuiGraphics graphics, DeltaTracker delta, CopyOnWriteArrayList<Source> sources) {
        if (sources.isEmpty() || Minecraft.getInstance().options.hideGui) {
            return;
        }
        UiStats.begin();
        DRAWER.graphics = graphics;
        DRAWER.width = graphics.guiWidth();
        DRAWER.height = graphics.guiHeight();
        DRAWER.partialTick = delta.getGameTimeDeltaPartialTick(true);
        try {
            for (int i = 0; i < sources.size(); i++) {
                sources.get(i).collect(DRAWER);
            }
        } finally {
            UiStats.mark(UiStats.COLLECT);
            if (DRAWER.canvas != null) {
                DRAWER.canvas.end();
                DRAWER.canvas = null;
            }
            DRAWER.graphics = null;
            UiStats.end();
        }
    }

    /** Begins the canvas at the first instance so a frame with nothing to draw touches no GL. */
    private static final class Drawer implements Collector {
        GuiGraphics graphics;
        Canvas canvas;
        float width;
        float height;
        float partialTick;

        @Override
        public void add(UiInstance instance) {
            UiStats.mark(UiStats.COLLECT);
            if (canvas == null) {
                canvas = Canvas.shared().begin(graphics);
                UiStats.mark(UiStats.BEGIN);
            }
            instance.render(canvas, graphics, 0, 0, width, height, partialTick);
        }
    }
}
