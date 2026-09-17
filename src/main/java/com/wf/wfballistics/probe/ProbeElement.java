package com.wf.wfballistics.probe;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** One thing a probe panel can draw. */
public sealed interface ProbeElement {

    /** How a row lines its children up against each other. */
    enum Align {
        TOP,
        CENTRE,
        BOTTOM
    }

    /** A line of text. */
    record Text(Component text, int colour, boolean shadow) implements ProbeElement {
        public static Text of(Component text) {
            return new Text(text, 0xFFFFFFFF, true);
        }

        public static Text of(Component text, int colour) {
            return new Text(text, colour, true);
        }
    }

    /** A rectangle of a texture file. */
    record Sprite(ResourceLocation texture, int width, int height,
                  int u, int v, int sourceWidth, int sourceHeight,
                  int textureWidth, int textureHeight, int tint) implements ProbeElement {

        /** The whole of a texture, scaled to a box. */
        public static Sprite whole(ResourceLocation texture, int size, int textureWidth, int textureHeight) {
            return new Sprite(texture, size, size, 0, 0, textureWidth, textureHeight,
                    textureWidth, textureHeight, 0xFFFFFFFF);
        }

        public Sprite tinted(int argb) {
            return new Sprite(texture, width, height, u, v, sourceWidth, sourceHeight,
                    textureWidth, textureHeight, argb);
        }
    }

    /** A sprite off a stitched atlas: a block or item texture, a mod's own atlas. */
    record AtlasSprite(ResourceLocation atlas, ResourceLocation sprite, int width, int height,
                       int tint) implements ProbeElement {

        public static AtlasSprite blocks(ResourceLocation sprite, int size) {
            return new AtlasSprite(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS,
                    sprite, size, size, 0xFFFFFFFF);
        }
    }

    /** An item stack, drawn through the item pipeline: its real model, in three dimensions. */
    record Icon(ItemStack stack, int size, boolean count) implements ProbeElement {
        public static Icon of(ItemStack stack) {
            return new Icon(stack, 16, stack.getCount() > 1);
        }
    }

    /**
     * A block state's own model.
     *
     * @param spinDegreesPerSecond 0 to hold {@code yaw}; anything else turns from it
     */
    record Block(BlockState state, int size, float yaw, float pitch,
                 float spinDegreesPerSecond) implements ProbeElement {
        public static Block of(BlockState state, int size) {
            return new Block(state, size, 45.0f, 30.0f, 0.0f);
        }

        public static Block spinning(BlockState state, int size) {
            return new Block(state, size, 0.0f, 30.0f, 45.0f);
        }
    }

    /**
     * Any baked model by id: the obj meshes this mod ships, or a block/item model out of the model manager.
     *
     * @param fit true to scale the model so its longest side fills the box, which is what an arbitrary
     *      mesh needs; false to draw it at model scale
     */
    record Model(ResourceLocation model, int size, float yaw, float pitch, float spinDegreesPerSecond,
                 boolean fit) implements ProbeElement {
        public static Model spinning(ResourceLocation model, int size) {
            return new Model(model, size, 0.0f, 25.0f, 45.0f, true);
        }
    }

    /**
     * Raw wavefront geometry, drawn against textures of its own.
     *
     * @param fit true to scale the whole assembly so its longest side fills the box
     */
    record Mesh(List<Part> parts, int size, float yaw, float pitch, float spinDegreesPerSecond,
                boolean fit) implements ProbeElement {

        /** One obj, or one named group of one, and the sheet it is drawn against. */
        public record Part(ResourceLocation obj, @Nullable String group, ResourceLocation texture) {
            /** Every group in the obj, against one texture. */
            public static Part whole(ResourceLocation obj, ResourceLocation texture) {
                return new Part(obj, null, texture);
            }
        }

        public static Mesh spinning(List<Part> parts, int size) {
            return new Mesh(parts, size, 0.0f, 25.0f, 45.0f, true);
        }
    }

    /** A progress bar with an optional label written over it. */
    record Bar(float progress, int width, int height, int fill, int background,
               @Nullable Component label) implements ProbeElement {
        public static Bar of(float progress, int width, int fill) {
            return new Bar(progress, width, 8, fill, 0x80101010, null);
        }
    }

    /** Children side by side. */
    record Row(List<ProbeElement> children, int gap, Align align) implements ProbeElement {
        public static Row of(ProbeElement... children) {
            return new Row(List.of(children), 4, Align.CENTRE);
        }
    }

    /** Children stacked. A panel is one of these. */
    record Column(List<ProbeElement> children, int gap) implements ProbeElement {
        public static Column of(ProbeElement... children) {
            return new Column(List.of(children), 2);
        }
    }

    /** Blank space, for pushing things apart. */
    record Space(int width, int height) implements ProbeElement {
    }

    /** Anything that can draw itself into a box. The escape hatch. */
    record Custom(Drawable drawable, int width, int height) implements ProbeElement {
    }

    /** Draws into a box whose top-left corner is {@code (x, y)}. */
    @FunctionalInterface
    interface Drawable {
        void draw(GuiGraphics graphics, int x, int y, int width, int height, float partialTick);
    }
}
