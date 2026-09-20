package com.wf.wflib.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.gemrender.direct.GemRenderItemRenderer;
import com.wf.gemrender.direct.ItemAppearance;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.client.model.MineRigs;
import com.wf.wflib.mine.MineModels;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a carried mine as the mine: the same imported model the laid entity uses, wherever vanilla draws an item:
 * the inventory, a hand, the ground, a frame, a head.
 */
public final class MineItemRenderers {

    /**
     * One renderer per model, held here rather than in GemRender's own name registry: before 26.1 that registry is
     * a convenience for a mod's {@code getCustomRenderer} and nothing in GemRender reads it, because an item claims
     * its renderer in Java rather than naming one in data.
     */
    private static final Map<ResourceLocation, GemRenderItemRenderer> RENDERERS = new HashMap<>();

    private MineItemRenderers() {
    }

    /** Build one renderer per registered mine model, during client setup. */
    public static void init() {
        for (ResourceLocation id : MineModels.ids()) {
            RENDERERS.put(id, new GemRenderItemRenderer(new Appearance(id)));
        }
    }

    /**
     * @return the renderer for a mine model, or null if nothing built one, which on a client means
     *      {@link #init} has yet to run.
     */
    @Nullable
    public static BlockEntityWithoutLevelRenderer get(ResourceLocation modelId) {
        return RENDERERS.get(modelId);
    }

    /** One mine's appearance: always the same model, never animated, fitted to the cell. */
    private record Appearance(ResourceLocation modelId) implements ItemAppearance {

        @Override
        @Nullable
        public GemRenderGltfModel model(ItemStack stack, ItemDisplayContext context) {
            return MineRigs.model(modelId);
        }

        @Override
        public void transform(ItemStack stack, ItemDisplayContext context, PoseStack pose) {
            Vec3 size = MineModels.size(modelId);
            Vec3 center = MineModels.center(modelId);
            float fit = (float) (1.0 / Math.max(1.0E-3, Math.max(size.x, size.y)));
            pose.scale(fit, fit, fit);
            pose.translate(-center.x, -center.y, -center.z);
        }
    }
}
