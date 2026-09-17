package com.wf.wfballistics.client.gui.gallery;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.wfballistics.ModModels;
import com.wf.wfballistics.client.render.MissileItemRenderer;
import com.wf.wfballistics.client.render.WFRenderTypes;
import com.wf.wfballistics.probe.client.ProbeModels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Supplier;

/**
 * A model the vanilla model manager baked: a missile airframe, a rotor disc, one of the loose props the effects
 * draw.
 */
public final class BakedEntry implements GalleryEntry {

    private static final RandomSource RANDOM = RandomSource.create();

    /** Places a resolved model inside the unit cube the gallery has set up, and hands it back. */
    @FunctionalInterface
    public interface Placement {

        @Nullable
        BakedModel place(ResourceLocation id, PoseStack pose);
    }

    private final ResourceLocation id;
    private final String label;
    private final Placement placement;
    private final Supplier<List<Component>> info;
    private final Supplier<Boolean> ready;

    private BakedEntry(ResourceLocation id, String label, Placement placement,
                       Supplier<List<Component>> info, Supplier<Boolean> ready) {
        this.id = id;
        this.label = label;
        this.placement = placement;
        this.info = info;
        this.ready = ready;
    }

    /** A missile airframe, placed the way its item renderer places one. */
    public static BakedEntry missile(ResourceLocation id, Supplier<List<Component>> info) {
        return new BakedEntry(id, id.getPath(), (key, pose) -> {
            pose.translate(-0.5, -0.5, -0.5);
            return MissileItemRenderer.instance()
                    .applyTransform(key, ItemDisplayContext.FIXED, pose);
        }, info, () -> ModModels.baked(id));
    }

    /** Any other baked model, fitted to the square by measuring it. */
    public static BakedEntry measured(ResourceLocation id, String label, Supplier<BakedModel> model,
                                      Supplier<List<Component>> info) {
        return new BakedEntry(id, label, (key, pose) -> {
            BakedModel baked = usable(model.get());
            if (baked == null) {
                return null;
            }
            float[] box = ProbeModels.bounds(key, baked);
            float longest = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
            float fit = 1.0f / Math.max(1.0E-3f, longest);
            pose.scale(fit, fit, fit);
            pose.translate(-(box[0] + box[3]) * 0.5f, -(box[1] + box[4]) * 0.5f, -(box[2] + box[5]) * 0.5f);
            return baked;
        }, info, () -> usable(model.get()) != null);
    }

    /** @return the model, or null for one that is missing or has yet to bake, which look the same here. */
    @Nullable
    public static BakedModel usable(@Nullable BakedModel baked) {
        return baked == null || baked == Minecraft.getInstance()
                .getModelManager()
                .getMissingModel() ? null : baked;
    }

    @Override
    public ResourceLocation id() {
        return this.id;
    }

    @Override
    public String label() {
        return this.label;
    }

    @Override
    public List<Component> info() {
        return this.info.get();
    }

    @Override
    public boolean ready() {
        return this.ready.get();
    }

    @Override
    public void draw(Request request) {
        PoseStack pose = request.graphics()
                .pose();
        pose.pushPose();
        pose.translate(request.x(), request.y(), GltfEntry.DEPTH);
        // Negative Y because a GUI counts pixels downward and a model counts them up.
        pose.scale(request.size(), -request.size(), request.size());
        pose.mulPose(Axis.XP.rotationDegrees(request.pitch()));
        pose.mulPose(Axis.YP.rotationDegrees(request.yaw()));

        BakedModel baked = this.placement.place(this.id, pose);
        if (baked != null) {
            if (request.normals()) {
                normals(baked, pose, request.buffers(), request.cull());
            } else {
                RenderType type = request.cull() ? RenderType.cutout()
                        : RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS);
                Minecraft.getInstance()
                        .getItemRenderer()
                        .renderModelLists(baked, ItemStack.EMPTY, LightTexture.FULL_BRIGHT,
                                OverlayTexture.NO_OVERLAY, pose, request.buffers()
                                        .getBuffer(type));
            }
        }
        pose.popPose();
    }

    private static void normals(BakedModel baked, PoseStack pose, MultiBufferSource.BufferSource buffers,
                                boolean cull) {
        VertexConsumer consumer = buffers.getBuffer(cull ? WFRenderTypes.NORMALS
                : WFRenderTypes.NORMALS_NOCULL);
        Matrix4f matrix = pose.last()
                .pose();
        emit(consumer, matrix, baked, null);
        for (Direction direction : Direction.values()) {
            emit(consumer, matrix, baked, direction);
        }
    }

    private static void emit(VertexConsumer consumer, Matrix4f matrix, BakedModel baked,
                             @Nullable Direction face) {
        RANDOM.setSeed(42L);
        for (BakedQuad quad : baked.getQuads(null, face, RANDOM)) {
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                float x = Float.intBitsToFloat(vertices[base]);
                float y = Float.intBitsToFloat(vertices[base + 1]);
                float z = Float.intBitsToFloat(vertices[base + 2]);
                int packed = vertices[base + 7];
                float nx = (byte) (packed & 0xFF) / 127.0f;
                float ny = (byte) ((packed >> 8) & 0xFF) / 127.0f;
                float nz = (byte) ((packed >> 16) & 0xFF) / 127.0f;
                consumer.addVertex(matrix, x, y, z)
                        .setColor(nx * 0.5f + 0.5f, ny * 0.5f + 0.5f, nz * 0.5f + 0.5f, 1.0f);
            }
        }
    }
}
