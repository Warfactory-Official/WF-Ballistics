package com.wf.wfballistics.probe.client;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Models a probe panel may draw that are not an item and not a block. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class ProbeModels {

    private static final Set<ResourceLocation> WANTED = new LinkedHashSet<>();
    private static final Map<ResourceLocation, float[]> BOUNDS = new ConcurrentHashMap<>();
    private static final RandomSource RANDOM = RandomSource.create();

    private ProbeModels() {
    }

    /** Declare a model a probe will want to draw. Call at client setup; later is too late to bake. */
    public static void declare(ResourceLocation model) {
        WANTED.add(model);
    }

    /** @return everything declared here, in declaration order. For the model gallery to enumerate. */
    public static Set<ResourceLocation> declared() {
        return Collections.unmodifiableSet(WANTED);
    }

    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event) {
        for (ResourceLocation model : WANTED) {
            event.register(ModelResourceLocation.standalone(model));
        }
    }

    @SubscribeEvent
    public static void onBakingComplete(ModelEvent.BakingCompleted event) {
        BOUNDS.clear();
    }

    @Nullable
    public static BakedModel baked(ResourceLocation model) {
        BakedModel baked = Minecraft.getInstance().getModelManager()
                .getModel(ModelResourceLocation.standalone(model));
        return baked == null || baked == Minecraft.getInstance().getModelManager().getMissingModel()
                ? null : baked;
    }

    /** {@code {minX, minY, minZ, maxX, maxY, maxZ}} of a baked model, in block units. */
    public static float[] bounds(ResourceLocation id, BakedModel model) {
        return BOUNDS.computeIfAbsent(id, key -> measure(model));
    }

    private static float[] measure(BakedModel model) {
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        accumulate(box, model.getQuads(null, null, RANDOM));
        for (net.minecraft.core.Direction face : net.minecraft.core.Direction.values()) {
            accumulate(box, model.getQuads(null, face, RANDOM));
        }
        if (box[0] > box[3]) {
            return new float[] {0, 0, 0, 1, 1, 1};
        }
        return box;
    }

    private static void accumulate(float[] box, Iterable<BakedQuad> quads) {
        Vector3f corner = new Vector3f();
        for (BakedQuad quad : quads) {
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++) {
                int base = i * stride;
                corner.set(Float.intBitsToFloat(vertices[base]),
                        Float.intBitsToFloat(vertices[base + 1]),
                        Float.intBitsToFloat(vertices[base + 2]));
                box[0] = Math.min(box[0], corner.x);
                box[1] = Math.min(box[1], corner.y);
                box[2] = Math.min(box[2], corner.z);
                box[3] = Math.max(box[3], corner.x);
                box[4] = Math.max(box[4], corner.y);
                box[5] = Math.max(box[5], corner.z);
            }
        }
    }
}
