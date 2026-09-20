package com.wf.wflib.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.wflib.client.render.ObjMeshes;
import com.wf.wflib.probe.ProbeElement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.util.FastColor;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import com.mojang.blaze3d.platform.Lighting;

/** Measuring and drawing, one element at a time. */
public final class ProbeRender {

    private ProbeRender() {
    }

    private static Font font() {
        return Minecraft.getInstance().font;
    }

    // --- measuring -------------------------------------------------------------------------------

    public static int width(ProbeElement element, int maxWidth) {
        return switch (element) {
            case ProbeElement.Text text -> Math.min(maxWidth, font().width(text.text()));
            case ProbeElement.Sprite sprite -> sprite.width();
            case ProbeElement.AtlasSprite sprite -> sprite.width();
            case ProbeElement.Icon icon -> icon.size();
            case ProbeElement.Block block -> block.size();
            case ProbeElement.Model model -> model.size();
            case ProbeElement.Mesh mesh -> mesh.size();
            case ProbeElement.Bar bar -> bar.width();
            case ProbeElement.Space space -> space.width();
            case ProbeElement.Custom custom -> custom.width();
            case ProbeElement.Row row -> rowWidth(row, maxWidth);
            case ProbeElement.Column column -> columnWidth(column, maxWidth);
        };
    }

    public static int height(ProbeElement element, int maxWidth) {
        return switch (element) {
            case ProbeElement.Text text -> font().wordWrapHeight(text.text(), maxWidth);
            case ProbeElement.Sprite sprite -> sprite.height();
            case ProbeElement.AtlasSprite sprite -> sprite.height();
            case ProbeElement.Icon icon -> icon.size();
            case ProbeElement.Block block -> block.size();
            case ProbeElement.Model model -> model.size();
            case ProbeElement.Mesh mesh -> mesh.size();
            case ProbeElement.Bar bar -> bar.height();
            case ProbeElement.Space space -> space.height();
            case ProbeElement.Custom custom -> custom.height();
            case ProbeElement.Row row -> rowHeight(row, maxWidth);
            case ProbeElement.Column column -> columnHeight(column, maxWidth);
        };
    }

    private static int rowWidth(ProbeElement.Row row, int maxWidth) {
        int total = 0;
        for (int i = 0; i < row.children().size(); i++) {
            total += width(row.children().get(i), maxWidth) + (i == 0 ? 0 : row.gap());
        }
        return Math.min(maxWidth, total);
    }

    private static int rowHeight(ProbeElement.Row row, int maxWidth) {
        int tallest = 0;
        for (ProbeElement child : row.children()) {
            tallest = Math.max(tallest, height(child, maxWidth));
        }
        return tallest;
    }

    private static int columnWidth(ProbeElement.Column column, int maxWidth) {
        int widest = 0;
        for (ProbeElement child : column.children()) {
            widest = Math.max(widest, width(child, maxWidth));
        }
        return widest;
    }

    private static int columnHeight(ProbeElement.Column column, int maxWidth) {
        int total = 0;
        for (int i = 0; i < column.children().size(); i++) {
            total += height(column.children().get(i), maxWidth) + (i == 0 ? 0 : column.gap());
        }
        return total;
    }

    // --- drawing ---------------------------------------------------------------------------------

    public static void draw(GuiGraphics graphics, ProbeElement element, int x, int y, int maxWidth,
                            float partialTick) {
        switch (element) {
            case ProbeElement.Text text -> {
                for (var line : font().split(text.text(), maxWidth)) {
                    if (text.shadow()) {
                        ProbeText.draw(graphics, font(), line, x, y, text.colour());
                    } else {
                        graphics.drawString(font(), line, x, y, text.colour(), false);
                    }
                    y += font().lineHeight;
                }
            }
            case ProbeElement.Sprite sprite -> {
                graphics.setColor(red(sprite.tint()), green(sprite.tint()), blue(sprite.tint()),
                        alpha(sprite.tint()));
                graphics.blit(sprite.texture(), x, y, sprite.width(), sprite.height(),
                        sprite.u(), sprite.v(), sprite.sourceWidth(), sprite.sourceHeight(),
                        sprite.textureWidth(), sprite.textureHeight());
                graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
            case ProbeElement.AtlasSprite sprite -> {
                TextureAtlasSprite atlas = Minecraft.getInstance()
                        .getTextureAtlas(sprite.atlas()).apply(sprite.sprite());
                graphics.setColor(red(sprite.tint()), green(sprite.tint()), blue(sprite.tint()),
                        alpha(sprite.tint()));
                graphics.blit(x, y, 0, sprite.width(), sprite.height(), atlas);
                graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            }
            case ProbeElement.Icon icon -> drawIcon(graphics, icon, x, y);
            case ProbeElement.Block block -> drawBlock(graphics, block, x, y, partialTick);
            case ProbeElement.Model model -> drawModel(graphics, model, x, y, partialTick);
            case ProbeElement.Mesh mesh -> drawMesh(graphics, mesh, x, y, partialTick);
            case ProbeElement.Bar bar -> drawBar(graphics, bar, x, y);
            case ProbeElement.Space ignored -> {
            }
            case ProbeElement.Custom custom ->
                    custom.drawable().draw(graphics, x, y, custom.width(), custom.height(), partialTick);
            case ProbeElement.Row row -> drawRow(graphics, row, x, y, maxWidth, partialTick);
            case ProbeElement.Column column -> drawColumn(graphics, column, x, y, maxWidth, partialTick);
        }
    }

    private static void drawRow(GuiGraphics graphics, ProbeElement.Row row, int x, int y, int maxWidth,
                                float partialTick) {
        int tallest = rowHeight(row, maxWidth);
        int cursor = x;
        for (ProbeElement child : row.children()) {
            int childHeight = height(child, maxWidth);
            int offset = switch (row.align()) {
                case TOP -> 0;
                case BOTTOM -> tallest - childHeight;
                case CENTRE -> (tallest - childHeight) / 2;
            };
            draw(graphics, child, cursor, y + offset, maxWidth - (cursor - x), partialTick);
            cursor += width(child, maxWidth) + row.gap();
        }
    }

    private static void drawColumn(GuiGraphics graphics, ProbeElement.Column column, int x, int y,
                                   int maxWidth, float partialTick) {
        int cursor = y;
        for (ProbeElement child : column.children()) {
            draw(graphics, child, x, cursor, maxWidth, partialTick);
            cursor += height(child, maxWidth) + column.gap();
        }
    }

    private static void drawBar(GuiGraphics graphics, ProbeElement.Bar bar, int x, int y) {
        graphics.fill(x, y, x + bar.width(), y + bar.height(), bar.background());
        int filled = Math.round(Math.max(0.0f, Math.min(1.0f, bar.progress())) * (bar.width() - 2));
        graphics.fill(x + 1, y + 1, x + 1 + filled, y + bar.height() - 1, bar.fill());
        if (bar.label() != null) {
            int textWidth = font().width(bar.label());
            ProbeText.draw(graphics, font(), bar.label(), x + (bar.width() - textWidth) / 2,
                    y + (bar.height() - 8) / 2, 0xFFFFFFFF);
        }
    }

    private static void drawIcon(GuiGraphics graphics, ProbeElement.Icon icon, int x, int y) {
        if (!ProbeConfig.SHOW_MODELS.get() || icon.stack().isEmpty()) {
            return;
        }
        PoseStack pose = graphics.pose();
        pose.pushPose();
        float scale = icon.size() / 16.0f;
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1.0f);
        graphics.renderItem(icon.stack(), 0, 0);
        if (icon.count()) {
            graphics.renderItemDecorations(font(), icon.stack(), 0, 0);
        }
        pose.popPose();
    }

    private static void drawBlock(GuiGraphics graphics, ProbeElement.Block block, int x, int y,
                                  float partialTick) {
        if (!ProbeConfig.SHOW_MODELS.get()) {
            return;
        }
        BlockState state = block.state();
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + block.size() / 2.0, y + block.size() / 2.0, 150.0);
        pose.scale(block.size(), -block.size(), block.size());
        pose.mulPose(Axis.XP.rotationDegrees(block.pitch()));
        pose.mulPose(Axis.YP.rotationDegrees(spin(block.yaw(), block.spinDegreesPerSecond(), partialTick)));
        pose.translate(-0.5, -0.5, -0.5);

        Lighting.setupFor3DItems();
        blocks.renderSingleBlock(state, pose, buffers, 0x00F000F0, OverlayTexture.NO_OVERLAY);
        buffers.endBatch();
        Lighting.setupForFlatItems();
        pose.popPose();
    }

    /** Raw obj geometry against textures of its own; see {@link ObjMeshes} for why that route exists. */
    private static void drawMesh(GuiGraphics graphics, ProbeElement.Mesh mesh, int x, int y,
                                 float partialTick) {
        if (!ProbeConfig.SHOW_MODELS.get()) {
            return;
        }
        float[] box = ObjMeshes.bounds(mesh);
        if (box == null) {
            return;
        }
        float span = Math.max(1.0e-3f, Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2])));
        float fit = mesh.fit() ? 1.0f / span : 1.0f;

        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + mesh.size() / 2.0, y + mesh.size() / 2.0, 150.0);
        pose.scale(mesh.size(), -mesh.size(), mesh.size());
        pose.mulPose(Axis.XP.rotationDegrees(mesh.pitch()));
        pose.mulPose(Axis.YP.rotationDegrees(spin(mesh.yaw(), mesh.spinDegreesPerSecond(), partialTick)));
        pose.scale(fit, fit, fit);
        pose.translate(-(box[0] + box[3]) * 0.5f, -(box[1] + box[4]) * 0.5f, -(box[2] + box[5]) * 0.5f);

        Lighting.setupFor3DItems();
        ObjMeshes.draw(pose.last(), buffers, mesh.parts(), 0x00F000F0, OverlayTexture.NO_OVERLAY);
        buffers.endBatch();
        Lighting.setupForFlatItems();
        pose.popPose();
    }

    private static void drawModel(GuiGraphics graphics, ProbeElement.Model model, int x, int y,
                                  float partialTick) {
        if (!ProbeConfig.SHOW_MODELS.get()) {
            return;
        }
        BakedModel baked = ProbeModels.baked(model.model());
        if (baked == null) {
            return;
        }
        float[] box = ProbeModels.bounds(model.model(), baked);
        float span = Math.max(1.0e-3f, Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2])));
        float fit = model.fit() ? 1.0f / span : 1.0f;
        float centreX = (box[0] + box[3]) * 0.5f;
        float centreY = (box[1] + box[4]) * 0.5f;
        float centreZ = (box[2] + box[5]) * 0.5f;

        ItemRenderer items = Minecraft.getInstance().getItemRenderer();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();

        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(x + model.size() / 2.0, y + model.size() / 2.0, 150.0);
        pose.scale(model.size(), -model.size(), model.size());
        pose.mulPose(Axis.XP.rotationDegrees(model.pitch()));
        pose.mulPose(Axis.YP.rotationDegrees(spin(model.yaw(), model.spinDegreesPerSecond(), partialTick)));
        pose.scale(fit, fit, fit);
        pose.translate(-centreX, -centreY, -centreZ);

        Lighting.setupFor3DItems();
        items.renderModelLists(baked, ItemStack.EMPTY, 0x00F000F0, OverlayTexture.NO_OVERLAY, pose,
                buffers.getBuffer(RenderType.cutout()));
        buffers.endBatch();
        Lighting.setupForFlatItems();
        pose.popPose();
    }

    /** A turn a viewer can read, driven off the world clock so every preview on screen agrees. */
    private static float spin(float base, float degreesPerSecond, float partialTick) {
        if (degreesPerSecond == 0.0f) {
            return base;
        }
        var level = Minecraft.getInstance().level;
        float seconds = level == null ? 0.0f : (level.getGameTime() + partialTick) / 20.0f;
        return base + seconds * degreesPerSecond;
    }

    static float red(int argb) {
        return FastColor.ARGB32.red(argb) / 255.0f;
    }

    static float green(int argb) {
        return FastColor.ARGB32.green(argb) / 255.0f;
    }

    static float blue(int argb) {
        return FastColor.ARGB32.blue(argb) / 255.0f;
    }

    static float alpha(int argb) {
        return FastColor.ARGB32.alpha(argb) / 255.0f;
    }
}
