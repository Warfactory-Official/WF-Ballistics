package com.wf.wflib.ui.input;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One-line text model for {@link com.wf.wflib.ui.widget.TextField}: edit keys, Enter => {@code submit} + history,
 * Up / Down => history, Esc => blur, Ctrl+V / Ctrl+A / Ctrl+Backspace.
 */
public final class TextLine implements UiKeyboard.Target {
    private static final int HISTORY = 32;

    private final StringBuilder text = new StringBuilder();
    private final int maxLength;
    private final Consumer<String> submit;
    private final List<String> history = new ArrayList<>();
    /** history.size() = editing a new line. */
    private int recalled;
    private int cursor;

    public TextLine(int maxLength, Consumer<String> submit) {
        this.maxLength = maxLength;
        this.submit = submit;
    }

    public CharSequence text() {
        return text;
    }

    public int cursor() {
        return cursor;
    }

    public void set(String value) {
        text.setLength(0);
        text.append(value, 0, Math.min(value.length(), maxLength));
        cursor = text.length();
    }

    public void focus() {
        UiKeyboard.focus(this);
    }

    public boolean focused() {
        return UiKeyboard.focused(this);
    }

    @Override
    public void key(int key, int scan, int modifiers) {
        boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> UiKeyboard.blur();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> enter();
            case GLFW.GLFW_KEY_BACKSPACE -> {
                int from = ctrl ? wordStart() : cursor - 1;
                if (from >= 0 && cursor > 0) {
                    text.delete(from, cursor);
                    cursor = from;
                }
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (cursor < text.length()) {
                    text.deleteCharAt(cursor);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> cursor = Math.max(0, cursor - 1);
            case GLFW.GLFW_KEY_RIGHT -> cursor = Math.min(text.length(), cursor + 1);
            case GLFW.GLFW_KEY_HOME -> cursor = 0;
            case GLFW.GLFW_KEY_END -> cursor = text.length();
            case GLFW.GLFW_KEY_UP -> recall(-1);
            case GLFW.GLFW_KEY_DOWN -> recall(1);
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    insert(Minecraft.getInstance().keyboardHandler.getClipboard().replaceAll("[\\r\\n]", " "));
                }
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) {
                    cursor = text.length();
                }
            }
            default -> {
            }
        }
    }

    @Override
    public void character(int codepoint, int modifiers) {
        if (Character.isISOControl(codepoint) || text.length() >= maxLength) {
            return;
        }
        text.insert(cursor, Character.toChars(codepoint));
        cursor += Character.charCount(codepoint);
    }

    private void insert(String s) {
        int room = maxLength - text.length();
        if (room <= 0) {
            return;
        }
        String part = s.length() > room ? s.substring(0, room) : s;
        text.insert(cursor, part);
        cursor += part.length();
    }

    private int wordStart() {
        int i = cursor;
        while (i > 0 && text.charAt(i - 1) == ' ') {
            i--;
        }
        while (i > 0 && text.charAt(i - 1) != ' ') {
            i--;
        }
        return i;
    }

    private void enter() {
        String line = text.toString().strip();
        text.setLength(0);
        cursor = 0;
        if (line.isEmpty()) {
            return;
        }
        if (history.isEmpty() || !history.getLast().equals(line)) {
            history.add(line);
            if (history.size() > HISTORY) {
                history.removeFirst();
            }
        }
        recalled = history.size();
        submit.accept(line);
    }

    private void recall(int by) {
        if (history.isEmpty()) {
            return;
        }
        recalled = Math.clamp(recalled + by, 0, history.size());
        set(recalled == history.size() ? "" : history.get(recalled));
    }
}
