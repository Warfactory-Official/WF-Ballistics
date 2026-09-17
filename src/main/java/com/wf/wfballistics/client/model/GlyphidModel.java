package com.wf.wfballistics.client.model;

import com.mojang.logging.LogUtils;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.AnimationDrive;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.texture.VariantUv;
import com.wf.wfballistics.WFBallistics;
import com.wf.wfballistics.entity.glyphid.EntityGlyphid;
import com.wf.wfballistics.entity.glyphid.GlyphidCaste;
import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/** The one glyphid rig, wearing every caste at once. */
public final class GlyphidModel {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * The mould the infested subtype is drawn in, over whatever skin the caste already wears.
     */
    private static final ResourceLocation INFESTATION = texture("glyphid_infestation");

    /** The stitched sheet the castes are tiled into. A name, not a file: nothing reads it. */
    private static final ResourceLocation ATLAS =
            ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "atlas/glyphid");

    /**
     * Drawn at depth-equal with no depth write, which is how vanilla lays a decal over a mob: the overlay is the
     * same rig under the same pose, so its fragments land at exactly the depth the body's did and neither blending
     * nor sorting has anything to decide.
     */
    private static final Material INFESTED_MATERIAL = SimpleMaterial.builder()
            .texture(INFESTATION)
            .cutout(CutoutShaders.ONE_TENTH)
            .depthTest(DepthTest.EQUAL)
            .writeMask(WriteMask.COLOR)
            .mipmap(false)
            .build();

    /** Every caste's texture in ordinal order, which is the order the sheet is tiled in. */
    private static final List<ResourceLocation> CASTES = castes();

    /** The swarm: one rig, one sheet, a tile per caste in {@link GlyphidCaste} order. */
    private static final Entry BODY = new Entry("body", CASTES, skinMaterial(CASTES.get(0)));

    /** The infestation overlay. */
    private static final Entry INFESTED = new Entry("infested", List.of(INFESTATION), INFESTED_MATERIAL);

    private GlyphidModel() {
    }

    /**
     * Declare the handles up front, so the rigs are built when resources finish loading rather than by whichever
     * visual happens to want one first, which would be an obj parse on a flywheel task thread, mid-frame.
     */
    public static void init() {
        body();
        infested();
    }

    /**
     * A caste's model together with everything the visual needs to pose it: the two drives that turn gameplay
     * quantities into clip-local time, and the armour clips indexed by the bits they answer to.
     *
     * @param armor one clip per combination of plates still attached; null at
     *      {@link EntityGlyphid#FULL_ARMOR}, which is the common case and needs no layer at all
     */
    public record Skin(GemRenderGltfModel model, AnimationDrive walk, AnimationDrive bite,
                       GltfAnimation[] armor) {

        /** @return the armour layer for these bits, or null when the animal has all its plates */
        @Nullable
        public GltfAnimation armor(byte bits) {
            int index = bits & EntityGlyphid.FULL_ARMOR;
            return index >= armor.length ? null : armor[index];
        }

        /** Which tile of the sheet this caste wears, for {@code instance.variant(...)}. */
        public VariantUv variant(GlyphidCaste caste) {
            return model.variant(caste.ordinal());
        }
    }

    /**
     * @return the rig every glyphid is drawn from, or null while the mesh has yet to load or has failed
     */
    @Nullable
    public static Skin body() {
        return BODY.get();
    }

    /**
     * @return the infestation overlay, or null if it could not be built. Posed from the body's palette
     *      rather than its own, so nothing here needs to know how a glyphid moves.
     */
    @Nullable
    public static Skin infested() {
        return INFESTED.get();
    }

    private static List<ResourceLocation> castes() {
        List<ResourceLocation> skins = new ArrayList<>(GlyphidCaste.VALUES.length);
        for (GlyphidCaste caste : GlyphidCaste.VALUES) {
            skins.add(caste.skin());
        }
        return List.copyOf(skins);
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "textures/entity/" + name + ".png");
    }

    private static Material skinMaterial(ResourceLocation texture) {
        return SimpleMaterial.builder()
                .texture(texture)
                .mipmap(false)
                .build();
    }

    /** One rig's handle plus the {@link Skin} derived from whatever is currently behind it. */
    private static final class Entry {

        private final ModelCache.Handle<GemRenderGltfModel> handle;

        /**
         * Volatile rather than synchronised: flywheel asks for this from several visual threads at once, and two of
         * them racing to build the same Skin costs one wasted derivation and nothing else.
         */
        private volatile Skin skin;

        private Entry(String name, List<ResourceLocation> skins, Material material) {
            String rig = "glyphid/" + name;
            this.handle = GemRenderModels.built(
                    ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, "rig/" + rig),
                    id -> build(rig, skins, material));
        }

        /** Builds the rig, and falls back to the first skin alone if the sheet will not stitch. */
        private static GemRenderGltfModel build(String rig, List<ResourceLocation> skins,
                                                Material material) throws Exception {
            try {
                return GlyphidRig.build(rig, material, ATLAS, skins);
            } catch (IllegalArgumentException e) {
                LOGGER.error("Could not stitch the {} glyphid skins into one sheet, so every "
                        + "caste will wear {}. All of them have to be the same size.", skins.size(),
                        skins.get(0), e);
                return GlyphidRig.build(rig, material, null, List.of());
            }
        }

        @Nullable
        private Skin get() {
            GemRenderGltfModel model = handle.get();
            if (model == null) {
                return null;
            }

            Skin current = skin;
            if (current != null && current.model() == model) {
                return current;
            }

            GltfAnimation[] armor = new GltfAnimation[EntityGlyphid.FULL_ARMOR + 1];
            for (int bits = 0; bits < EntityGlyphid.FULL_ARMOR; bits++) {
                armor[bits] = model.animation(GlyphidRig.armour(bits));
            }

            current = new Skin(model,
                    AnimationDrive.cyclic(model.animation(GlyphidRig.WALK), (float) (Math.PI * 2.0)),
                    // The attack animation already arrives as 0 to 1, which is exactly the clip.
                    AnimationDrive.ranged(model.animation(GlyphidRig.BITE), 0.0f, 1.0f),
                    armor);
            skin = current;
            return current;
        }
    }
}
