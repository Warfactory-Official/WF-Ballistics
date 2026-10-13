package com.wf.wflib.pda.client;

import com.wf.wflib.client.screen.EmbeddedScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.CommonComponents;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.function.Consumer;

/**
 * One-line text entry; Enter / Done => {@code done}, then back to the screen it opened over (from an
 * {@link EmbeddedScreen}: the world, PDA still raised).
 */
public final class TextPromptScreen extends Screen implements EmbeddedScreen.Standalone {
    private final int maxLength;
    private final String initial;
    private final Consumer<String> done;
    @Nullable
    private final Screen parent;
    private EditBox box;

    private TextPromptScreen(Component title, int maxLength, String initial, Consumer<String> done,
                             @Nullable Screen parent) {
        super(title);
        this.maxLength = maxLength;
        this.initial = initial;
        this.done = done;
        this.parent = parent;
    }

    public static void open(Component title, int maxLength, String initial, Consumer<String> done) {
        Minecraft mc = Minecraft.getInstance();
        Screen parent = EmbeddedScreen.current() != null ? null : mc.screen;
        mc.setScreen(new TextPromptScreen(title, maxLength, initial, done, parent));
    }

    @Override
    protected void init() {
        box = new EditBox(font, width / 2 - 100, height / 2 - 10, 200, 20, title);
        box.setMaxLength(maxLength);
        box.setValue(initial);
        addRenderableWidget(box);
        setInitialFocus(box);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit())
                .bounds(width / 2 - 100, height / 2 + 16, 98, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 2, height / 2 + 16, 98, 20).build());
    }

    private void submit() {
        String text = box.getValue().strip();
        if (!text.isEmpty()) {
            done.accept(text);
        }
        onClose();
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 26, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
