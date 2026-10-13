package com.wf.wflib.pda.client;

import com.wf.wflib.client.screen.EmbeddedScreen;
import com.wf.wflib.WFLib;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.ui.UiSurfaces;
import com.wf.wflib.ui.input.UiInput;
import com.wf.wflib.ui.input.UiKeyboard;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.lwjgl.glfw.GLFW;

/**
 * Local PDA state: carried / held (main hand), focus (raised: mouse drives the screen cursor, WASD still walks)
 * and the open app. No Screen while focused: {@code setScreen} releases movement keys and the mouse grab.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class PdaClient {
    /** Raise / lower per tick, 0..1. */
    private static final float RAISE_STEP = 0.25f;
    /** Surface units per {@code LocalPlayer.turn} unit (~1 mouse count at default sensitivity). */
    private static final float CURSOR_GAIN = 0.55f;

    private static boolean carried;
    private static boolean held;
    private static boolean focused;
    /** Use key still down since the last toggle: {@code startUseItem} repeats while held. */
    private static boolean useLatch;
    private static float raise;
    private static float raiseO;
    private static float cursorX = PdaShell.WIDTH / 2;
    private static float cursorY = PdaShell.HEIGHT / 2;
    private static boolean pressed;
    /** Press seen since the last frame: a press + release between frames still reaches the screen. */
    private static boolean pressLatch;
    private static boolean secondaryLatch;
    private static float wheel;
    private static PdaApp app;
    /** Feed on the whole screen; 0 = none. */
    private static int fullscreenFeed;

    private PdaClient() {
    }

    /** Local player has a PDA anywhere in the inventory (TV keys, alerts). */
    public static boolean carried() {
        return carried;
    }

    /** Main hand: drawn two-handed in first person. */
    public static boolean held() {
        return held;
    }

    public static boolean focused() {
        return focused;
    }

    /** 0 = rest, 1 = raised. */
    public static float raise(float partialTick) {
        return raiseO + (raise - raiseO) * partialTick;
    }

    public static float cursorX() {
        return cursorX;
    }

    public static float cursorY() {
        return cursorY;
    }

    public static int fullscreenFeed() {
        return fullscreenFeed;
    }

    /** Feed {@code feedId} (linked by the caller through {@code CameraLink}) over the whole screen while focused. */
    public static void fullscreen(int feedId) {
        if (focused && feedId != 0) {
            UiKeyboard.blur();
            fullscreenFeed = feedId;
        }
    }

    /** Null = home grid. */
    public static PdaApp app() {
        return app;
    }

    public static void open(PdaApp next) {
        if (app == next) {
            return;
        }
        UiKeyboard.blur();
        if (app != null) {
            app.closed();
        }
        app = next;
        if (next != null) {
            next.opened();
        }
    }

    public static void home() {
        open(null);
    }

    /** Lowers to rest; the open app stays. */
    public static void lower() {
        fullscreenFeed = 0;
        UiKeyboard.blur();
        if (focused) {
            focused = false;
            pressed = false;
            pressLatch = false;
            wheel = 0;
            UiInput input = UiSurfaces.input(PdaRenderer.SURFACE);
            if (input != null) {
                input.reset();
            }
        }
    }

    /** Mouse turn as {@code LocalPlayer.turn} takes it. @return true while focused (cursor consumed it). */
    public static boolean steer(double yaw, double pitch) {
        if (!focused) {
            return false;
        }
        if (fullscreenFeed != 0) {
            PdaFullscreen.steer(yaw, pitch);
            return true;
        }
        cursorX = Math.max(0, Math.min(PdaShell.WIDTH - 1, cursorX + (float) yaw * CURSOR_GAIN));
        cursorY = Math.max(0, Math.min(PdaShell.HEIGHT - 1, cursorY + (float) pitch * CURSOR_GAIN));
        return true;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        raiseO = raise;
        if (player == null) {
            carried = false;
            held = false;
            lower();
            open(null);
            raise = raiseO = 0;
            return;
        }
        carried = Pda.carries(player);
        held = Pda.is(player.getMainHandItem());
        // Own prompt (marker name) keeps it raised.
        if (!held || mc.screen != null && !(mc.screen instanceof EmbeddedScreen.Standalone) || !player.isAlive()) {
            lower();
        }
        if (!mc.options.keyUse.isDown()) {
            useLatch = false;
        }
        if (!carried) {
            open(null);
        }
        raise = focused ? Math.min(1, raise + RAISE_STEP) : Math.max(0, raise - RAISE_STEP);
        PdaApps.refresh(player);
    }

    @SubscribeEvent
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!held || mc.screen != null || mc.player == null) {
            return;
        }
        if (event.isUseItem()) {
            // Sneak + use => the item's own interactions (link a target) run instead.
            if (!focused && mc.player.isShiftKeyDown()) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            if (event.getHand() != InteractionHand.MAIN_HAND || useLatch) {
                return;
            }
            useLatch = true;
            if (fullscreenFeed != 0) {
                fullscreenFeed = 0;
            } else if (focused) {
                lower();
            } else {
                focused = true;
            }
        } else if (focused) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** Primary button = pointer; never reaches attack while focused. */
    @SubscribeEvent
    static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (fullscreenFeed != 0 && event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            event.setCanceled(true);
            return;
        }
        if (!focused || Minecraft.getInstance().screen != null) {
            return;
        }
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && fullscreenFeed == 0 && app != null
                && app.ownsSecondary()) {
            secondaryLatch |= event.getAction() == GLFW.GLFW_PRESS;
            event.setCanceled(true);
            return;
        }
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        pressed = event.getAction() != GLFW.GLFW_RELEASE;
        pressLatch |= pressed;
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (focused && Minecraft.getInstance().screen == null) {
            if (fullscreenFeed != 0) {
                com.wf.wflib.client.cam.CameraLink.zoomBy(event.getScrollDeltaY());
                event.setCanceled(true);
                return;
            }
            wheel += (float) event.getScrollDeltaY();
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (focused && event.getName().equals(VanillaGuiLayers.CROSSHAIR)) {
            event.setCanceled(true);
        }
    }

    /** Esc lowers instead of pausing. */
    @SubscribeEvent
    static void onScreen(ScreenEvent.Opening event) {
        if (focused && event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            if (fullscreenFeed != 0) {
                fullscreenFeed = 0;
            } else {
                lower();
            }
        }
    }

    /** Before {@link UiSurfaces} redraws the screen at default priority. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onFrame(RenderFrameEvent.Pre event) {
        if (fullscreenFeed != 0 && !com.wf.wflib.client.cam.CameraLink.active()) {
            fullscreenFeed = 0;
        }
        if (!focused || fullscreenFeed != 0) {
            return;
        }
        UiInput input = UiSurfaces.input(PdaRenderer.SURFACE);
        if (input == null) {
            return;
        }
        input.pointer(cursorX, cursorY, (pressed || pressLatch ? UiInput.PRIMARY : 0)
                | (secondaryLatch ? UiInput.SECONDARY : 0));
        pressLatch = false;
        secondaryLatch = false;
        if (wheel != 0) {
            input.wheel(wheel);
            wheel = 0;
        }
    }
}
