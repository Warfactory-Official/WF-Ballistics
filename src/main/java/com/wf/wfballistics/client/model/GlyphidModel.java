package com.wf.wfballistics.client.model;

import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * The one glyphid mesh, dressed for a caste.
 *
 * <p>Every caste is the same animal at a different size in a different skin — which is what the swarm is
 * meant to read as, and what makes it cheap: nine castes are nine textures over one set of nineteen meshes,
 * and flywheel pools mesh uploads by identity, so the geometry reaches the GPU once no matter how many
 * castes are on screen.
 *
 * <p>The part list is longer than the mesh list because the six legs are two meshes drawn three times each.
 * Those three share a {@link Model}, so they share an instancer and go out in one draw.
 */
public final class GlyphidModel {

    public static final ResourceLocation MESH =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "raw_models/glyphid.obj");

    /**
     * Which mesh each part slot draws, indexed by the slot constants in {@link GlyphidPoses}.
     */
    private static final String[] PART_MESH = {
            "Body", "ArmorFront", "ArmorLeft", "ArmorRight",
            "JawTop", "JawLeft", "JawRight",
            "ArmLeftUpper", "ArmLeftMid", "ArmLeftLower", "ArmLeftArmor",
            "ArmRightUpper", "ArmRightMid", "ArmRightLower", "ArmRightArmor",
            "LegLeftUpper", "LegLeftLower", "LegLeftUpper", "LegLeftLower", "LegLeftUpper", "LegLeftLower",
            "LegRightUpper", "LegRightLower", "LegRightUpper", "LegRightLower", "LegRightUpper",
            "LegRightLower"};

    /**
     * The mould the infested subtype is drawn in, over whatever skin the caste already wears.
     */
    private static final ResourceLocation INFESTATION = texture("glyphid_infestation");

    /**
     * Drawn at depth-equal with no depth write, which is how vanilla lays a decal over a mob: the overlay is
     * the same mesh under the same transform, so its fragments land at exactly the depth the body's did and
     * neither blending nor sorting has anything to decide.
     */
    private static final Material INFESTED_MATERIAL = SimpleMaterial.builder()
            .texture(INFESTATION)
            .cutout(CutoutShaders.ONE_TENTH)
            .depthTest(DepthTest.EQUAL)
            .writeMask(WriteMask.COLOR)
            .mipmap(false)
            .build();

    private static final RendererReloadCache<ResourceLocation, Model[]> PARTS =
            new RendererReloadCache<>(GlyphidModel::build);

    private GlyphidModel() {
    }

    /**
     * @return one model per part slot, or an empty array if the mesh could not be read
     */
    public static Model[] parts(GlyphidCaste caste) {
        return PARTS.get(caste.skin());
    }

    public static Model[] infested() {
        return PARTS.get(INFESTATION);
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "textures/entity/" + name + ".png");
    }

    private static Model[] build(ResourceLocation texture) {
        Map<String, Mesh> meshes = ObjMeshes.of(MESH);
        if (meshes.isEmpty()) {
            return new Model[0];
        }
        Material material = texture.equals(INFESTATION) ? INFESTED_MATERIAL : SimpleMaterial.builder()
                .texture(texture)
                // An 80x71 entity skin, not an atlas sprite: there are no mip levels to sample. Culled,
                // because every triangle in the mesh winds with the normal it declares.
                .mipmap(false)
                .build();

        // Keyed by mesh rather than by part so the three left legs end up sharing one model, and with it one
        // instancer and one draw.
        Map<String, Model> byMesh = new HashMap<>();
        Model[] parts = new Model[GlyphidPoses.PART_COUNT];
        for (int i = 0; i < parts.length; i++) {
            String name = PART_MESH[i];
            Model model = byMesh.computeIfAbsent(name, n -> {
                Mesh mesh = meshes.get(n);
                return mesh == null ? null : new SingleMeshModel(mesh, material);
            });
            if (model == null) {
                return new Model[0];
            }
            parts[i] = model;
        }
        return parts;
    }
}
