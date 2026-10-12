package com.wf.wflib.probe.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.ui.Canvas;
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
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.renderer.texture.TextureAtlas;
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

    /**
     * {@code alpha} multiplies sprites and text (action-list fade); 3D elements take it through
     * {@code GuiGraphics.setColor}.
     */
    public static void draw(Canvas canvas, GuiGraphics graphics, ProbeElement element, int x, int y, int maxWidth,
                            float partialTick, float alpha) {
        switch (element) {
            case ProbeElement.Text text -> {
                int colour = fade(ProbeText.opaque(text.colour()), alpha);
                for (var line : font().split(text.text(), maxWidth)) {
                    if (text.shadow()) {
                        ProbeText.draw(canvas, line, x, y, colour);
                    } else {
                        canvas.text(line, x, y, colour, false);
                    }
                    y += font().lineHeight;
                }
            }
            case ProbeElement.Sprite sprite -> canvas.sprite(sprite.texture(), x, y, x + sprite.width(), y + sprite.height(),
                    sprite.u() / (float) sprite.textureWidth(), sprite.v() / (float) sprite.textureHeight(),
                    (sprite.u() + sprite.sourceWidth()) / (float) sprite.textureWidth(),
                    (sprite.v() + sprite.sourceHeight()) / (float) sprite.textureHeight(), fade(sprite.tint(), alpha));
            case ProbeElement.AtlasSprite sprite -> canvas.sprite(Minecraft.getInstance()
                            .getTextureAtlas(sprite.atlas()).apply(sprite.sprite()), x, y, x + sprite.width(),
                    y + sprite.height(), fade(sprite.tint(), alpha));
            case ProbeElement.Icon icon -> {
                if (enterVanilla(canvas, graphics, alpha)) {
                    drawIcon(graphics, icon, x, y);
                    leaveVanilla(canvas, graphics);
                }
            }
            case ProbeElement.Block block -> {
                if (enterVanilla(canvas, graphics, alpha)) {
                    drawBlock(graphics, block, x, y, partialTick);
                    leaveVanilla(canvas, graphics);
                }
            }
            case ProbeElement.Model model -> {
                if (enterVanilla(canvas, graphics, alpha)) {
                    drawModel(graphics, model, x, y, partialTick);
                    leaveVanilla(canvas, graphics);
                }
            }
            case ProbeElement.Mesh mesh -> {
                if (enterVanilla(canvas, graphics, alpha)) {
                    drawMesh(graphics, mesh, x, y, partialTick);
                    leaveVanilla(canvas, graphics);
                }
            }
            case ProbeElement.Bar bar -> drawBar(canvas, bar, x, y);
            case ProbeElement.Space ignored -> {
            }
            case ProbeElement.Custom custom ->
                    custom.drawable().draw(canvas, x, y, custom.width(), custom.height(), partialTick);
            case ProbeElement.Row row -> drawRow(canvas, graphics, row, x, y, maxWidth, partialTick, alpha);
            case ProbeElement.Column column -> drawColumn(canvas, graphics, column, x, y, maxWidth, partialTick, alpha);
        }
    }

    /** Item, block and model previews draw through vanilla in the canvas's current transform. */
    private static boolean enterVanilla(Canvas canvas, GuiGraphics graphics, float alpha) {
        if (!ProbeConfig.SHOW_MODELS.get()) {
            return false;
        }
        canvas.suspend();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        canvas.applyTo(pose);
        graphics.setColor(1.0f, 1.0f, 1.0f, alpha);
        return true;
    }

    private static void leaveVanilla(Canvas canvas, GuiGraphics graphics) {
        graphics.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        graphics.flush();
        graphics.pose().popPose();
        canvas.resume();
    }

    private static int fade(int argb, float alpha) {
        if (alpha >= 1) {
            return argb;
        }
        return Math.round((argb >>> 24) * alpha) << 24 | argb & 0xFFFFFF;
    }

    private static void drawRow(Canvas canvas, GuiGraphics graphics, ProbeElement.Row row, int x, int y, int maxWidth,
                                float partialTick, float alpha) {
        int tallest = rowHeight(row, maxWidth);
        int cursor = x;
        for (ProbeElement child : row.children()) {
            int childHeight = height(child, maxWidth);
            int offset = switch (row.align()) {
                case TOP -> 0;
                case BOTTOM -> tallest - childHeight;
                case CENTRE -> (tallest - childHeight) / 2;
            };
            draw(canvas, graphics, child, cursor, y + offset, maxWidth - (cursor - x), partialTick, alpha);
            cursor += width(child, maxWidth) + row.gap();
        }
    }

    private static void drawColumn(Canvas canvas, GuiGraphics graphics, ProbeElement.Column column, int x, int y,
                                   int maxWidth, float partialTick, float alpha) {
        int cursor = y;
        for (ProbeElement child : column.children()) {
            draw(canvas, graphics, child, x, cursor, maxWidth, partialTick, alpha);
            cursor += height(child, maxWidth) + column.gap();
        }
    }

    private static void drawBar(Canvas canvas, ProbeElement.Bar bar, int x, int y) {
        canvas.fill(x, y, x + bar.width(), y + bar.height(), bar.background());
        int filled = Math.round(Math.max(0.0f, Math.min(1.0f, bar.progress())) * (bar.width() - 2));
        canvas.fill(x + 1, y + 1, x + 1 + filled, y + bar.height() - 1, bar.fill());
        if (bar.label() != null) {
            int textWidth = font().width(bar.label());
            ProbeText.draw(canvas, bar.label(), x + (bar.width() - textWidth) / 2, y + (bar.height() - 8) / 2, 0xFFFFFFFF);
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
}
