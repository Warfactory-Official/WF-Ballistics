package com.wf.wfballistics.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.wf.gemrender.rig.RigGeometry;
import com.wf.gemrender.rig.WavefrontObj;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.probe.ProbeElement;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Wavefront geometry drawn straight, against textures bound rather than stitched. */
@EventBusSubscriber(modid = WFBallistics.MODID, value = Dist.CLIENT)
public final class ObjMeshes {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<ResourceLocation, Map<String, RigGeometry>> MESHES = new ConcurrentHashMap<>();
    private static final Map<List<ProbeElement.Mesh.Part>, float[]> BOUNDS = new ConcurrentHashMap<>();

    private ObjMeshes() {
    }

    @SubscribeEvent
    public static void onBakingComplete(ModelEvent.BakingCompleted event) {
        MESHES.clear();
        BOUNDS.clear();
    }

    /** Draws the parts into a buffer source, in whatever frame the pose is already in. */
    public static void draw(PoseStack.Pose pose, MultiBufferSource buffers,
                            List<ProbeElement.Mesh.Part> parts, int light, int overlay) {
        for (ProbeElement.Mesh.Part part : parts) {
            List<RigGeometry> pieces = geometry(part);
            if (pieces.isEmpty()) {
                continue;
            }
            VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(part.texture()));
            for (RigGeometry piece : pieces) {
                emit(buffer, pose, piece, light, overlay);
            }
        }
    }

    private static void emit(VertexConsumer buffer, PoseStack.Pose pose, RigGeometry geometry, int light,
                             int overlay) {
        float[] positions = geometry.positions();
        float[] normals = geometry.normals();
        float[] uvs = geometry.texCoords();
        int[] indices = geometry.indices();
        for (int i = 0; i < indices.length; i += 3) {
            for (int corner = 0; corner < 4; corner++) {
                int index = indices[i + Math.min(corner, 2)];
                int p = index * 3;
                int t = index * 2;
                buffer.addVertex(pose, positions[p], positions[p + 1], positions[p + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(uvs[t], uvs[t + 1])
                        .setOverlay(overlay)
                        .setLight(light)
                        .setNormal(pose, normals == null ? 0.0f : normals[p],
                                normals == null ? 1.0f : normals[p + 1],
                                normals == null ? 0.0f : normals[p + 2]);
            }
        }
    }

    /** The geometry one part names: a single group, or every group in the obj laid end to end. */
    public static List<RigGeometry> geometry(ProbeElement.Mesh.Part part) {
        Map<String, RigGeometry> groups = groups(part.obj());
        if (part.group() == null) {
            return List.copyOf(groups.values());
        }
        RigGeometry one = groups.get(part.group());
        return one == null ? List.of() : List.of(one);
    }

    private static Map<String, RigGeometry> groups(ResourceLocation obj) {
        return MESHES.computeIfAbsent(obj, id -> {
            try {
                return WavefrontObj.load(id);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("probe mesh {} could not be read", id, e);
                return Map.of();
            }
        });
    }

    /**
     * {@code {minX, minY, minZ, maxX, maxY, maxZ}} across every part, so {@code fit} scales the whole assembly as
     * one thing and its parts keep their places relative to each other.
     *
     * @return null while nothing in the mesh has loaded, so a caller can draw nothing rather than a dot
     */
    @Nullable
    public static float[] bounds(ProbeElement.Mesh mesh) {
        return bounds(mesh.parts());
    }

    /** @see #bounds(ProbeElement.Mesh) */
    @Nullable
    public static float[] bounds(List<ProbeElement.Mesh.Part> wanted) {
        float[] box = BOUNDS.computeIfAbsent(wanted, parts -> {
            float[] measured = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                    -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            List<RigGeometry> all = new ArrayList<>();
            for (ProbeElement.Mesh.Part part : parts) {
                all.addAll(geometry(part));
            }
            for (RigGeometry geometry : all) {
                float[] positions = geometry.positions();
                for (int i = 0; i < positions.length; i += 3) {
                    measured[0] = Math.min(measured[0], positions[i]);
                    measured[1] = Math.min(measured[1], positions[i + 1]);
                    measured[2] = Math.min(measured[2], positions[i + 2]);
                    measured[3] = Math.max(measured[3], positions[i]);
                    measured[4] = Math.max(measured[4], positions[i + 1]);
                    measured[5] = Math.max(measured[5], positions[i + 2]);
                }
            }
            return measured;
        });
        return box[0] > box[3] ? null : box;
    }
}
