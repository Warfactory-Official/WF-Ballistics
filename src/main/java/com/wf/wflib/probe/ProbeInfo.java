package com.wf.wflib.probe;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What one probe panel says: a title and a column of {@link ProbeElement}s, built up by whichever providers had
 * something to add about the thing being looked at.
 */
public final class ProbeInfo {

    private final List<ProbeElement> elements = new ArrayList<>();
    private final List<ProbeAction> actions = new ArrayList<>();

    @Nullable
    private Component title;
    @Nullable
    private ProbeElement titleIcon;
    private int titleColour = 0xFFFFFF55;
    /** Set when the title on hand is only a fallback, so the first real one may still replace it. */
    private boolean titleIsFallback;
    private boolean titleIconIsFallback;
    private int accent = 0xFF55FFFF;

    /** The panel's heading. The first provider to set one wins, so a general fallback cannot shout over it. */
    public ProbeInfo title(Component text) {
        return title(text, titleColour);
    }

    public ProbeInfo title(Component text, int colour) {
        if (title == null || titleIsFallback) {
            title = text;
            titleColour = colour;
            titleIsFallback = false;
        }
        return this;
    }

    /** A heading to use only if nothing better turns up: the block's own name, say. */
    public ProbeInfo titleFallback(Component text) {
        if (title == null) {
            title = text;
            titleIsFallback = true;
        }
        return this;
    }

    /** A small element drawn beside the heading. */
    public ProbeInfo titleIcon(ProbeElement element) {
        if (titleIcon == null || titleIconIsFallback) {
            titleIcon = element;
            titleIconIsFallback = false;
        }
        return this;
    }

    /** An icon to use only if nothing better turns up. @see #titleFallback */
    public ProbeInfo titleIconFallback(ProbeElement element) {
        if (titleIcon == null) {
            titleIcon = element;
            titleIconIsFallback = true;
        }
        return this;
    }

    @Nullable
    public ProbeElement titleIconElement() {
        return titleIcon;
    }

    /** The colour a provider should reach for when it wants to pick something out. */
    public int accent() {
        return accent;
    }

    public ProbeInfo accent(int argb) {
        accent = argb;
        return this;
    }

    public ProbeInfo add(ProbeElement element) {
        elements.add(element);
        return this;
    }

    public ProbeInfo text(Component text) {
        return add(ProbeElement.Text.of(text));
    }

    public ProbeInfo text(Component text, int colour) {
        return add(ProbeElement.Text.of(text, colour));
    }

    public ProbeInfo text(Component text, ChatFormatting colour) {
        Integer value = colour.getColor();
        return text(text, 0xFF000000 | (value == null ? 0xFFFFFF : value));
    }

    /** A label and a value on one line, the value in the accent colour. */
    public ProbeInfo entry(Component label, Component value) {
        return add(ProbeElement.Row.of(
                ProbeElement.Text.of(label, 0xFFAAAAAA),
                ProbeElement.Text.of(value, accent)));
    }

    public ProbeInfo icon(ItemStack stack) {
        return add(ProbeElement.Icon.of(stack));
    }

    /** An item and a line of text side by side, which is the shape most rows want. */
    public ProbeInfo iconRow(ItemStack stack, Component text) {
        return add(ProbeElement.Row.of(ProbeElement.Icon.of(stack), ProbeElement.Text.of(text)));
    }

    public ProbeInfo block(BlockState state, int size) {
        return add(ProbeElement.Block.of(state, size));
    }

    public ProbeInfo model(ResourceLocation model, int size) {
        return add(ProbeElement.Model.spinning(model, size));
    }

    public ProbeInfo sprite(ProbeElement.Sprite sprite) {
        return add(sprite);
    }

    public ProbeInfo bar(float progress, int width, int fill) {
        return add(ProbeElement.Bar.of(progress, width, fill));
    }

    public ProbeInfo bar(float progress, int width, int fill, Component label) {
        return add(new ProbeElement.Bar(progress, width, 9, fill, 0x80101010, label));
    }

    public ProbeInfo row(ProbeElement... children) {
        return add(ProbeElement.Row.of(children));
    }

    public ProbeInfo space(int height) {
        return add(new ProbeElement.Space(0, height));
    }

    /** Offers something the player can do to this target. */
    public ProbeInfo action(ProbeAction action) {
        actions.add(action);
        return this;
    }

    public List<ProbeAction> actions() {
        return actions;
    }

    /** Draws anything at all into a box of this size. */
    public ProbeInfo custom(int width, int height, ProbeElement.Drawable drawable) {
        return add(new ProbeElement.Custom(drawable, width, height));
    }

    @Nullable
    public Component titleText() {
        return title;
    }

    public int titleColour() {
        return titleColour;
    }

    public List<ProbeElement> elements() {
        return elements;
    }

    /** How much has been said so far, so a caller can tell whether a provider added anything. */
    public int size() {
        return elements.size() + actions.size() + (title == null ? 0 : 1);
    }

    public boolean isEmpty() {
        return title == null && elements.isEmpty() && actions.isEmpty();
    }
}
