package com.wf.wfballistics.client.gui;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.wfballistics.MissileModels;
import com.wf.wfballistics.client.render.MissileItemRenderer;
import com.wf.wfballistics.client.render.WFRenderTypes;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

public class ModelGalleryScreen extends Screen {

    private enum Mode {TEXTURED, NORMALS}

    private static final int CELL = 64;
    private static final int LABEL_H = 10;
    private static final int PAD = 10;
    private static final int TOP = 30;
    private static final int BOTTOM = 8;

    private final List<ResourceLocation> models = new ArrayList<>(MissileModels.ids());
    private int scrollRow;
    private boolean cull = true;
    private Mode mode = Mode.TEXTURED;

    private ResourceLocation focused;
    private float yaw;
    private float pitch;
    private float zoom = 1.0f;

    public ModelGalleryScreen() {
        super(Component.literal("WF Model Gallery"));
    }

    private int columns() {
        return Math.max(1, (this.width - 2 * PAD) / CELL);
    }

    private int rowStride() {
        return CELL + LABEL_H;
    }

    private int visibleRows() {
        return Math.max(1, (this.height - TOP - BOTTOM) / rowStride());
    }

    private int totalRows() {
        return (models.size() + columns() - 1) / columns();
    }

    private int maxScrollRow() {
        return Math.max(0, totalRows() - visibleRows());
    }

    private int gridX() {
        return (this.width - columns() * CELL) / 2;
    }

    @Override
    protected void init() {
        int h = 16;
        int right = this.width - PAD;
        addRenderableWidget(Button.builder(modeLabel(), b -> {
            mode = mode == Mode.TEXTURED ? Mode.NORMALS : Mode.TEXTURED;
            b.setMessage(modeLabel());
        }).bounds(right - 110, 6, 110, h).build());
        addRenderableWidget(Button.builder(cullLabel(), b -> {
            cull = !cull;
            b.setMessage(cullLabel());
        }).bounds(right - 110 - 6 - 74, 6, 74, h).build());
        if (focused != null) {
            addRenderableWidget(Button.builder(Component.literal("< Back"), b -> {
                focused = null;
                rebuildWidgets();
            }).bounds(PAD, 6, 60, h).build());
        }
        this.scrollRow = Math.min(this.scrollRow, maxScrollRow());
    }

    private Component modeLabel() {
        return Component.literal("Mode: " + (mode == Mode.NORMALS ? "normals" : "textured"));
    }

    private Component cullLabel() {
        return Component.literal("Cull: " + (cull ? "on" : "off"));
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(gg);

        RenderSystem.enableDepthTest();
        Lighting.setupFor3DItems();
        MultiBufferSource.BufferSource buf = gg.bufferSource();

        ResourceLocation hovered = focused == null ? renderGrid(gg, buf, mouseX, mouseY) : null;
        if (focused != null) {
            renderInspect(gg, buf);
        }

        buf.endBatch();
        Lighting.setupForFlatItems();

        gg.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        if (focused == null) {
            renderGridLabels(gg);
            gg.drawString(this.font, models.size() + " models  |  rows " + (scrollRow + 1) + "-"
                    + Math.min(totalRows(), scrollRow + visibleRows()) + "/" + totalRows()
                    + "  |  click to inspect, scroll to page, N normals, C cull", PAD, 22, 0xFF9090A8, false);
        } else {
            int ty = 24;
            for (Component line : infoFor(focused)) {
                gg.drawString(this.font, line, PAD, ty, 0xFFFFFFFF, false);
                ty += 10;
            }
            gg.drawString(this.font, "drag to rotate  |  scroll to zoom  |  ESC/Back to return  |  N normals, C cull",
                    PAD, this.height - 14, 0xFF9090A8, false);
        }

        super.render(gg, mouseX, mouseY, partialTick);

        if (hovered != null) {
            gg.renderComponentTooltip(this.font, infoFor(hovered), mouseX, mouseY);
        }
    }

    private ResourceLocation renderGrid(GuiGraphics gg, MultiBufferSource.BufferSource buf, int mouseX, int mouseY) {
        int cols = columns();
        int gridX = gridX();
        int rows = visibleRows();
        ResourceLocation hovered = null;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (scrollRow + r) * cols + c;
                if (index >= models.size()) {
                    continue;
                }
                int x = gridX + c * CELL;
                int y = TOP + r * rowStride();
                boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                gg.fill(x, y, x + CELL, y + CELL, hover ? 0x40FFFFFF : 0x30000000);
                gg.renderOutline(x, y, CELL, CELL, hover ? 0xFF6688FF : 0xFF404050);
                drawModel(gg, buf, models.get(index), x + CELL / 2.0f, y + CELL / 2.0f, CELL * 0.85f,
                        ItemDisplayContext.GUI, 15.0f, 0.0f);
                if (hover) {
                    hovered = models.get(index);
                }
            }
        }
        return hovered;
    }

    private void renderGridLabels(GuiGraphics gg) {
        int cols = columns();
        int gridX = gridX();
        int rows = visibleRows();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int index = (scrollRow + r) * cols + c;
                if (index >= models.size()) {
                    continue;
                }
                int x = gridX + c * CELL;
                int y = TOP + r * rowStride();
                String label = models.get(index).getPath();
                gg.drawString(this.font, label, x + (CELL - this.font.width(label)) / 2, y + CELL + 1, 0xFFC0C0D0, false);
            }
        }
    }

    private void renderInspect(GuiGraphics gg, MultiBufferSource.BufferSource buf) {
        float size = Math.min(this.width, this.height) * 0.32f * zoom;
        drawModel(gg, buf, focused, this.width / 2.0f, this.height / 2.0f, size,
                ItemDisplayContext.FIXED, pitch, yaw);
    }

    private void drawModel(GuiGraphics gg, MultiBufferSource.BufferSource buf, ResourceLocation id,
                           float cx, float cy, float scalePx, ItemDisplayContext ctx, float pitch, float yaw) {
        PoseStack pose = gg.pose();
        pose.pushPose();
        pose.translate(cx, cy, 200.0f);
        pose.scale(scalePx, -scalePx, scalePx);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(yaw));
        pose.translate(-0.5, -0.5, -0.5);
        BakedModel baked = MissileItemRenderer.instance().applyTransform(id, ctx, pose);
        if (baked != null) {
            if (mode == Mode.NORMALS) {
                drawNormals(baked, pose, buf);
            } else {
                RenderType type = cull ? RenderType.cutout() : RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
                Minecraft.getInstance().getItemRenderer().renderModelLists(baked, ItemStack.EMPTY,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, pose, buf.getBuffer(type));
            }
        }
        pose.popPose();
    }

    private void drawNormals(BakedModel baked, PoseStack pose, MultiBufferSource.BufferSource buf) {
        VertexConsumer vc = buf.getBuffer(cull ? WFRenderTypes.NORMALS : WFRenderTypes.NORMALS_NOCULL);
        Matrix4f mat = pose.last().pose();
        RandomSource rand = RandomSource.create();
        emitQuads(vc, mat, rand, baked, null);
        for (Direction dir : Direction.values()) {
            emitQuads(vc, mat, rand, baked, dir);
        }
    }

    private static void emitQuads(VertexConsumer vc, Matrix4f mat, RandomSource rand, BakedModel baked, Direction dir) {
        rand.setSeed(42L);
        for (BakedQuad quad : baked.getQuads(null, dir, rand)) {
            int[] v = quad.getVertices();
            int stride = v.length / 4;
            for (int i = 0; i < 4; i++) {
                int b = i * stride;
                float x = Float.intBitsToFloat(v[b]);
                float y = Float.intBitsToFloat(v[b + 1]);
                float z = Float.intBitsToFloat(v[b + 2]);
                int n = v[b + 7];
                float nx = (byte) (n & 0xFF) / 127.0f;
                float ny = (byte) ((n >> 8) & 0xFF) / 127.0f;
                float nz = (byte) ((n >> 16) & 0xFF) / 127.0f;
                vc.vertex(mat, x, y, z)
                        .color(nx * 0.5f + 0.5f, ny * 0.5f + 0.5f, nz * 0.5f + 0.5f, 1.0f)
                        .endVertex();
            }
        }
    }

    private List<Component> infoFor(ResourceLocation id) {
        double len = MissileModels.length(id);
        Vec3 dim = MissileModels.dimensions(id);
        Vec3 center = MissileModels.center(id);
        double baseY = center.y - dim.y / 2.0;
        List<Component> t = new ArrayList<>();
        t.add(Component.literal(id.toString()).withStyle(ChatFormatting.WHITE));
        t.add(Component.literal(String.format("length %.3f", len)).withStyle(ChatFormatting.GRAY));
        t.add(Component.literal(String.format("dim  %.3f x %.3f x %.3f", dim.x, dim.y, dim.z)).withStyle(ChatFormatting.GRAY));
        t.add(Component.literal(String.format("center %.3f, %.3f, %.3f", center.x, center.y, center.z)).withStyle(ChatFormatting.GRAY));
        t.add(Component.literal(String.format("baseY %.3f", baseY))
                .append(Component.literal("  (0 = base on origin)").withStyle(ChatFormatting.DARK_GRAY))
                .withStyle(Math.abs(baseY) < 1.0e-3 ? ChatFormatting.GREEN : ChatFormatting.RED));
        return t;
    }

    private ResourceLocation cellAt(double mouseX, double mouseY) {
        int cols = columns();
        int gridX = gridX();
        if (mouseX < gridX || mouseY < TOP) {
            return null;
        }
        int c = (int) ((mouseX - gridX) / CELL);
        int r = (int) ((mouseY - TOP) / rowStride());
        if (c < 0 || c >= cols || r < 0 || r >= visibleRows()) {
            return null;
        }
        if ((mouseY - TOP) % rowStride() >= CELL) {
            return null;
        }
        int index = (scrollRow + r) * cols + c;
        return index >= 0 && index < models.size() ? models.get(index) : null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (focused == null && button == 0) {
            ResourceLocation id = cellAt(mouseX, mouseY);
            if (id != null) {
                focused = id;
                yaw = 25.0f;
                pitch = -10.0f;
                zoom = 1.0f;
                rebuildWidgets();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (focused != null && button == 0) {
            yaw += (float) dragX;
            pitch = Mth.clamp(pitch + (float) dragY, -90.0f, 90.0f);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (focused != null) {
            zoom = Mth.clamp(zoom * (delta > 0 ? 1.1f : 0.9f), 0.2f, 6.0f);
            return true;
        }
        this.scrollRow = Math.max(0, Math.min(maxScrollRow(), this.scrollRow - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && focused != null) {
            focused = null;
            rebuildWidgets();
            return true;
        }
        if (keyCode == 67) {
            cull = !cull;
            rebuildWidgets();
            return true;
        }
        if (keyCode == 78) {
            mode = mode == Mode.TEXTURED ? Mode.NORMALS : Mode.TEXTURED;
            rebuildWidgets();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
