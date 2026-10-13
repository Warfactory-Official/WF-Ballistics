package com.wf.wflib.client.screen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.wf.wflib.WFLib;
import com.wf.wflib.client.cam.FeedTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * Another mod's {@link Screen} drawn into a texture of the host's size instead of the window, driven by a virtual
 * pointer (e.g. a map mod's fullscreen map inside the PDA). While it draws or takes input ({@link #current}) it is the
 * window: {@code Window} size / GUI scale / scaled size, {@code getMainRenderTarget}, {@code MouseHandler.xpos/ypos},
 * {@code minecraft.screen}; in-world blur skipped; every {@code setScreen} it makes is captured as its next screen
 * ({@code null} = ends; {@link Standalone} => opened for real after it returns, window-sized).
 * Render thread.
 */
@EventBusSubscriber(modid = WFLib.MODID, value = Dist.CLIENT)
public final class EmbeddedScreen {
    /** Screens that must open for real even when an embedded screen asks (text entry). */
    public interface Standalone {
    }

    @Nullable
    private static EmbeddedScreen current;
    @Nullable
    private static EmbeddedScreen shown;
    private static int counter;
    /** Vanilla's 320x240 floor, 16:9 => wide hosts keep the window's scale. */
    private static final int MIN_GUI_WIDTH = 320;
    private static final int MIN_GUI_HEIGHT = 180;

    private final ResourceLocation id = ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
            "embedded_screen/" + counter++);
    private final FeedTexture texture = new FeedTexture();
    @Nullable
    private TextureTarget target;
    @Nullable
    private Screen screen;
    /** {@link Standalone} asked for inside {@link #run}; opened for real once it returns. */
    @Nullable
    private Screen deferred;
    private int pixelWidth = 1;
    private int pixelHeight = 1;
    private int guiScale = 1;
    /** Virtual pointer, px. */
    private double mouseX;
    private double mouseY;
    private long requestedFrame = -1;
    private long frame;

    public EmbeddedScreen() {
        Minecraft.getInstance().getTextureManager().register(id, texture);
    }

    /** The embedded screen running code now, if any (mixin hooks). */
    @Nullable
    public static EmbeddedScreen current() {
        return current;
    }

    public int pixelWidth() {
        return pixelWidth;
    }

    public int pixelHeight() {
        return pixelHeight;
    }

    public int guiScale() {
        return guiScale;
    }

    public int guiWidth() {
        return Mth.positiveCeilDiv(pixelWidth, guiScale);
    }

    public int guiHeight() {
        return Mth.positiveCeilDiv(pixelHeight, guiScale);
    }

    /**
     * Texture px; GUI scale = largest <= the window's leaving >= {@link #MIN_GUI_WIDTH} x {@link #MIN_GUI_HEIGHT}.
     * Change => hosted screen resized.
     */
    public void size(int width, int height) {
        int scale = (int) Math.max(1, Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
        while (scale > 1 && (width / scale < MIN_GUI_WIDTH || height / scale < MIN_GUI_HEIGHT)) {
            scale--;
        }
        if (width == pixelWidth && height == pixelHeight && scale == guiScale) {
            return;
        }
        pixelWidth = width;
        pixelHeight = height;
        guiScale = scale;
        Screen s = screen;
        if (s != null) {
            run(() -> s.resize(Minecraft.getInstance(), guiWidth(), guiHeight()));
        }
    }

    @Nullable
    public RenderTarget target() {
        return target;
    }

    public double mouseX() {
        return mouseX;
    }

    public double mouseY() {
        return mouseY;
    }

    public boolean open() {
        return screen != null;
    }

    @Nullable
    public Screen screen() {
        return screen;
    }

    /** Runs {@code opener}; the screen it opens becomes this one's. */
    public void open(Runnable opener) {
        run(opener);
    }

    public void close() {
        if (screen != null) {
            Screen old = screen;
            screen = null;
            run(old::removed);
        }
    }

    /** Free the texture; {@link #close} first. */
    public void release() {
        close();
        Minecraft.getInstance().getTextureManager().release(id);
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    /** Draw it this frame (and keep ticking); texture valid from the next frame. */
    public ResourceLocation request() {
        requestedFrame = frame;
        if (shown == null || shown == this) {
            shown = this;
        }
        return id;
    }

    // --- input, GUI px of the screen ------------------------------------------------------------------

    public void pointer(double guiX, double guiY) {
        mouseX = guiX * guiScale;
        mouseY = guiY * guiScale;
        if (screen != null) {
            Screen s = screen;
            run(() -> s.mouseMoved(guiX, guiY));
        }
    }

    public void press(int button) {
        Screen s = screen;
        if (s != null) {
            double x = guiX();
            double y = guiY();
            run(() -> s.mouseClicked(x, y, button));
        }
    }

    public void release(int button) {
        Screen s = screen;
        if (s != null) {
            double x = guiX();
            double y = guiY();
            run(() -> s.mouseReleased(x, y, button));
        }
    }

    public void drag(int button, double dx, double dy) {
        Screen s = screen;
        if (s != null) {
            double x = guiX();
            double y = guiY();
            run(() -> s.mouseDragged(x, y, button, dx, dy));
        }
    }

    public void scroll(double amount) {
        Screen s = screen;
        if (s != null) {
            double x = guiX();
            double y = guiY();
            run(() -> s.mouseScrolled(x, y, 0, amount));
        }
    }

    private double guiX() {
        return mouseX / guiScale;
    }

    private double guiY() {
        return mouseY / guiScale;
    }

    /**
     * Runs as the current screen: map mods test {@code minecraft.screen == this} (JourneyMap drag, Xaero tooltips).
     * Direct {@code minecraft.screen} writes (JourneyMap {@code LayeredScreen} popups, no {@code setScreen}) => swap.
     */
    private void run(Runnable action) {
        Minecraft mc = Minecraft.getInstance();
        EmbeddedScreen outer = current;
        Screen real = mc.screen;
        Screen installed = screen;
        current = this;
        if (installed != null) {
            mc.screen = installed;
        }
        try {
            action.run();
        } finally {
            if (installed != null && mc.screen != installed && screen == installed) {
                screen = mc.screen;
            }
            current = outer;
            mc.screen = real;
        }
        if (outer == null && deferred != null) {
            Screen next = deferred;
            deferred = null;
            mc.setScreen(next);
        }
    }

    private void adopt(@Nullable Screen next) {
        if (screen != null && screen != next) {
            screen.removed();
        }
        screen = next;
        if (next != null) {
            next.init(Minecraft.getInstance(), guiWidth(), guiHeight());
        }
    }

    // --- frame ------------------------------------------------------------------------------------------

    private boolean wanted() {
        return screen != null && frame - requestedFrame <= 2;
    }

    private void draw(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        int w = pixelWidth;
        int h = pixelHeight;
        if (target == null || target.width != w || target.height != h) {
            if (target == null) {
                target = new TextureTarget(w, h, true, Minecraft.ON_OSX);
            } else {
                target.resize(w, h, Minecraft.ON_OSX);
            }
            target.setClearColor(0, 0, 0, 1);
            texture.adopt(target.getColorTextureId());
        }
        // clear() ends unbound => clear before binding.
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        VertexSorting sorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, (float) w / guiScale, (float) h / guiScale, 0,
                1000, 21000), VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.translation(0, 0, -11000);
        RenderSystem.applyModelViewMatrix();
        Lighting.setupFor3DItems();
        GuiGraphics graphics = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
        Screen s = screen;
        int gx = (int) guiX();
        int gy = (int) guiY();
        try {
            run(() -> s.renderWithTooltip(graphics, gx, gy, partialTick));
            graphics.flush();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            mc.getMainRenderTarget().bindWrite(true);
        }
    }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        EmbeddedScreen s = shown;
        if (s == null) {
            return;
        }
        s.frame++;
        if (s.wanted()) {
            s.draw(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        } else if (s.frame - s.requestedFrame > 2) {
            shown = null;
        }
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        EmbeddedScreen s = shown;
        if (s != null && s.screen != null) {
            Screen screen = s.screen;
            s.run(screen::tick);
        }
    }

    /**
     * {@code Minecraft.setScreen} from inside a running embedded screen => becomes its next screen.
     * @return true = taken (caller cancels the real setScreen)
     */
    public static boolean capture(@Nullable Screen next) {
        EmbeddedScreen s = current;
        if (s == null) {
            return false;
        }
        if (next instanceof Standalone) {
            s.deferred = next;
        } else {
            s.adopt(next);
        }
        return true;
    }
}
