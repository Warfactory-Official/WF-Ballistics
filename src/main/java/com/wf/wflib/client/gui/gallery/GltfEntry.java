package com.wf.wflib.client.gui.gallery;

import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.direct.DirectRenderer;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfPose;
import com.wf.gemrender.gltf.skin.SkinnedBounds;
import com.wf.gemrender.texture.VariantUv;
import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** A model GemRender imported: a mine, an airframe, a piece of a broken one, a door, a glyphid. */
public final class GltfEntry implements GalleryEntry {

    /** How far in front of the panel a model is drawn. */
    static final float DEPTH = 200.0f;

    private static final int CORNERS = 8;

    /**
     * A model's own idea of how big it is and where its middle is, in model units.
     *
     * @param longest the longest axis of its box: what the gallery divides by so the model spans the cell
     */
    public record Fit(Vec3 center, double longest) {
    }

    private final ResourceLocation id;
    private final String label;
    private final Supplier<GemRenderGltfModel> model;
    private final Supplier<List<Clip>> clips;
    private final Supplier<List<Variant>> variants;
    private final Supplier<List<Component>> info;
    @Nullable
    private final Supplier<Matrix4f> baseSource;
    @Nullable
    private final Supplier<Fit> declared;

    /** Resolved alongside the fit, because a base transform is only readable once the model has loaded. */
    @Nullable
    private Matrix4f base;

    /** The model the kept fit was measured from, so a resource reload re-measures rather than drifts. */
    @Nullable
    private GemRenderGltfModel fittedFor;
    private final Vector3f center = new Vector3f();
    private float extent = 1.0f;

    private GltfEntry(Builder builder) {
        this.id = builder.id;
        this.label = builder.label != null ? builder.label : builder.id.getPath();
        this.model = builder.model;
        this.clips = builder.clips;
        this.variants = builder.variants;
        this.info = builder.info;
        this.baseSource = builder.base;
        this.declared = builder.declared;
    }

    public static Builder of(ResourceLocation id, Supplier<GemRenderGltfModel> model) {
        return new Builder(id, model);
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
    public boolean ready() {
        return this.model.get() != null;
    }

    @Override
    public List<Clip> clips() {
        return this.clips.get();
    }

    @Override
    public List<Variant> variants() {
        return this.variants.get();
    }

    /** The registry's own figures, plus what the imported asset turned out to contain. */
    @Override
    public List<Component> info() {
        List<Component> lines = new ArrayList<>(this.info.get());
        GemRenderGltfModel loaded = this.model.get();
        if (loaded == null) {
            lines.add(Component.literal("not loaded")
                    .withStyle(ChatFormatting.RED));
            return lines;
        }
        lines.add(Component.literal(String.format("rig  %d joints, %d variant(s), %d texture(s)",
                        loaded.jointCount(), loaded.variantCount(), loaded.textures()
                                .size()))
                .withStyle(ChatFormatting.GRAY));
        if (!loaded.animations()
                .isEmpty()) {
            lines.add(Component.literal("imported clips  " + String.join(", ", loaded.animations()
                            .keySet()))
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    @Override
    public void draw(Request request) {
        GemRenderGltfModel loaded = this.model.get();
        if (loaded == null) {
            return;
        }
        refit(loaded);

        List<Clip> available = clips();
        Clip clip = available.isEmpty() ? null
                : available.get(Math.floorMod(request.clip(), available.size()));

        List<Variant> finishes = variants();
        VariantUv uv = VariantUv.NONE;
        if (!finishes.isEmpty()) {
            uv = loaded.variant(finishes.get(Math.floorMod(request.variant(), finishes.size()))
                    .index());
        }

        Matrix4f matrix = new Matrix4f(request.graphics()
                .pose()
                .last()
                .pose())
                .translate(request.x(), request.y(), DEPTH)
                .scale(request.size(), -request.size(), request.size())
                .rotateX((float) Math.toRadians(request.pitch()))
                .rotateY((float) Math.toRadians(request.yaw()))
                .scale(1.0f / this.extent)
                .translate(-this.center.x, -this.center.y, -this.center.z);
        if (this.base != null) {
            matrix.mul(this.base);
        }

        DirectRenderer.submit(loaded, clip == null ? null : clip.animation(),
                clip == null ? 0.0f : clip.timeAt(request.phase()), matrix,
                LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.GUI, uv);
    }

    /** Measures the model once, and again only if a reload put a different one behind the same id. */
    private void refit(GemRenderGltfModel loaded) {
        if (this.fittedFor == loaded) {
            return;
        }
        this.fittedFor = loaded;
        this.base = this.baseSource == null ? null : this.baseSource.get();

        Fit given = this.declared == null ? null : this.declared.get();
        if (given != null && given.longest() > 1.0E-4) {
            this.center.set((float) given.center()
                    .x, (float) given.center()
                    .y, (float) given.center()
                    .z);
            this.extent = (float) given.longest();
        } else {
            measure(loaded);
        }

        if (this.base != null) {
            this.base.transformPosition(this.center);
        }
        this.extent = Math.max(1.0E-4f, this.extent);
    }

    /** The model's axis-aligned box at rest, through its own bone matrices. */
    private void measure(GemRenderGltfModel loaded) {
        SkinnedBounds bounds = loaded.bounds();
        Matrix4f[] palette = loaded.newPalette();
        GltfPose.evaluate(loaded.layout(), null, 0.0f, palette);

        float[] box = new float[6];
        Vector3f corner = new Vector3f();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;

        for (int i = 0; i < bounds.size(); i++) {
            bounds.box(i, box);
            Matrix4f bone = palette[bounds.slot(i)];
            for (int c = 0; c < CORNERS; c++) {
                corner.set(box[(c & 1) != 0 ? 3 : 0], box[1 + ((c & 2) != 0 ? 3 : 0)],
                        box[2 + ((c & 4) != 0 ? 3 : 0)]);
                bone.transformPosition(corner);
                minX = Math.min(minX, corner.x);
                minY = Math.min(minY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxX = Math.max(maxX, corner.x);
                maxY = Math.max(maxY, corner.y);
                maxZ = Math.max(maxZ, corner.z);
            }
        }

        if (minX > maxX) {
            // A model with no bounded bones at all. Nothing to centre on, so draw it where it was authored.
            this.center.set(0.0f, 0.0f, 0.0f);
            this.extent = 1.0f;
            return;
        }

        this.center.set((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f);
        this.extent = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
    }

    /** Assembles an entry. Only the model supplier is required; everything else has a sane nothing. */
    public static final class Builder {

        private final ResourceLocation id;
        private final Supplier<GemRenderGltfModel> model;
        @Nullable
        private String label;
        private Supplier<List<Clip>> clips = List::of;
        private Supplier<List<Variant>> variants = List::of;
        private Supplier<List<Component>> info = List::of;
        @Nullable
        private Supplier<Matrix4f> base;
        @Nullable
        private Supplier<Fit> declared;

        private Builder(ResourceLocation id, Supplier<GemRenderGltfModel> model) {
            this.id = id;
            this.model = model;
        }

        public Builder label(String label) {
            this.label = label;
            return this;
        }

        public Builder clips(Supplier<List<Clip>> clips) {
            this.clips = clips;
            return this;
        }

        public Builder variants(Supplier<List<Variant>> variants) {
            this.variants = variants;
            return this;
        }

        public Builder info(Supplier<List<Component>> info) {
            this.info = info;
            return this;
        }

        /** The fixed transform this model's own renderer applies before drawing it, if it has one. */
        public Builder base(Supplier<Matrix4f> base) {
            this.base = base;
            return this;
        }

        /** The registry's own size and centre, for a model the gallery must not measure itself. */
        public Builder fit(Supplier<Fit> fit) {
            this.declared = fit;
            return this;
        }

        public GltfEntry build() {
            return new GltfEntry(this);
        }
    }
}
