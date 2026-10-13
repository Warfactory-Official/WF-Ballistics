package com.wf.wflib.ui.input;

import net.minecraft.client.KeyMapping;
import org.jetbrains.annotations.Nullable;

/**
 * Keyboard focus for widgets drawn outside a {@code Screen} (PDA FBO): while focused, every key and character goes
 * to the target, none to key mappings ({@code MixinKeyboardHandler}). Only with no Screen open. Render thread.
 */
public final class UiKeyboard {
    /** One focused text sink. */
    public interface Target {
        /** GLFW press / repeat. */
        void key(int key, int scan, int modifiers);

        void character(int codepoint, int modifiers);

        /** Focus left (another target, {@link #blur}, host hidden). */
        default void blurred() {
        }
    }

    @Nullable
    private static Target focused;

    private UiKeyboard() {
    }

    @Nullable
    public static Target focused() {
        return focused;
    }

    public static boolean focused(Target target) {
        return focused == target;
    }

    /** Held movement keys released: no walking on while typing. */
    public static void focus(Target target) {
        if (focused == target) {
            return;
        }
        blur();
        focused = target;
        KeyMapping.releaseAll();
    }

    public static void blur() {
        Target old = focused;
        focused = null;
        if (old != null) {
            old.blurred();
        }
    }
}
