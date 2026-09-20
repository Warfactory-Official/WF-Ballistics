package com.wf.wflib.probe.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.wflib.WFLib;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/** Scrolling the action list, and running the chosen one. */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class ProbeInput {

    /** {@code -PprobeTrace} logs every scroll the probe sees and what it made of the modifier. */
    private static final boolean TRACE = System.getProperty("wflib.probeInputTrace") != null;

    private ProbeInput() {
    }

    /** Whether the modifier is down right now, which is also what makes the list read as live. */
    public static boolean scrolling() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getWindow() == null) {
            return false;
        }
        KeyMapping modifier = ProbeKeys.MODIFIER;
        if (modifier.isUnbound()) {
            return false;
        }
        InputConstants.Key key = modifier.getKey();
        if (key.getType() != InputConstants.Type.KEYSYM) {
            return modifier.isDown();
        }
        long window = mc.getWindow().getWindow();
        if (InputConstants.isKeyDown(window, key.getValue())) {
            return true;
        }
        int twin = twin(key.getValue());
        return twin != InputConstants.UNKNOWN.getValue() && InputConstants.isKeyDown(window, twin);
    }

    /** The other half of a modifier pair, or unknown for an ordinary key. */
    private static int twin(int code) {
        return switch (code) {
            case GLFW.GLFW_KEY_LEFT_ALT -> GLFW.GLFW_KEY_RIGHT_ALT;
            case GLFW.GLFW_KEY_RIGHT_ALT -> GLFW.GLFW_KEY_LEFT_ALT;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> GLFW.GLFW_KEY_RIGHT_CONTROL;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> GLFW.GLFW_KEY_LEFT_CONTROL;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> GLFW.GLFW_KEY_RIGHT_SHIFT;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> GLFW.GLFW_KEY_LEFT_SHIFT;
            case GLFW.GLFW_KEY_LEFT_SUPER -> GLFW.GLFW_KEY_RIGHT_SUPER;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> GLFW.GLFW_KEY_LEFT_SUPER;
            default -> InputConstants.UNKNOWN.getValue();
        };
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (TRACE) {
            Minecraft mc = Minecraft.getInstance();
            long w = mc.getWindow().getWindow();
            org.slf4j.LoggerFactory.getLogger("wfb-probe").info(
                    "scroll dy={} scrolling={} lalt={} ralt={} actions={}",
                    event.getScrollDeltaY(), scrolling(),
                    InputConstants.isKeyDown(w, GLFW.GLFW_KEY_LEFT_ALT),
                    InputConstants.isKeyDown(w, GLFW.GLFW_KEY_RIGHT_ALT),
                    ProbeSelection.actions().size());
        }
        if (!ProbeConfig.ENABLED.get() || !scrolling() || ProbeSelection.actions().size() < 2) {
            return;
        }
        double delta = event.getScrollDeltaY();
        if (delta == 0.0) {
            return;
        }
        // Scrolling down moves down the list, which is the direction the rows fall away in.
        ProbeSelection.scroll(delta > 0 ? -1 : 1);
        // Consumed, or the hotbar changes underneath the player at the same time.
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        if (!ProbeConfig.ENABLED.get()) {
            return;
        }
        boolean acted = false;
        while (ProbeKeys.ACTIVATE.consumeClick()) {
            if (!acted) {
                acted = ProbeSelection.activate();
            }
        }
    }
}
