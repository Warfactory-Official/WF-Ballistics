package com.wf.wflib.ammo.client;

import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.rig.RigBuilder;
import com.wf.gemrender.rig.RigGeometry;
import com.wf.gemrender.asset.ModelCache;
import com.wf.wflib.WFLib;
import com.wf.wflib.ammo.Ammo;
import com.wf.wflib.ammo.AmmoVariant;
import com.wf.wflib.ammo.Calibre;
import com.wf.wflib.ammo.CaseMaterial;
import com.wf.wflib.ammo.ProjectileLook;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GemRender models per variant: cartridge (item) + projectile look (procedural or pack glTF) + motor flame.
 * Palette built at client setup from every registered colour; models built lazily by GemRender off-thread.
 */
public final class AmmoModels {
    static final ResourceLocation PALETTE = ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "dynamic/ammo_palette");
    /** Bullet jacket. */
    static final int JACKET = ProjectileLook.COPPER;
    private static AmmoPalette palette;
    private static Material material;
    private static boolean anyTrail;
    private static final Map<ResourceLocation, ModelCache.Handle<GemRenderGltfModel>> CARTRIDGES = new HashMap<>();
    private static final Map<ResourceLocation, ModelCache.Handle<GemRenderGltfModel>> LOOKS = new HashMap<>();
    private static final Map<ResourceLocation, ModelCache.Handle<GemRenderGltfModel>> FLAMES = new HashMap<>();

    private AmmoModels() {
    }

    /** Render thread, after {@link Ammo#freeze}. */
    static void init() {
        List<Integer> rgbs = new ArrayList<>(List.of(JACKET, ProjectileLook.OLIVE, ProjectileLook.COPPER,
                ProjectileLook.STEEL, ProjectileLook.DARK, ProjectileLook.ALUMINIUM, AmmoMeshes.FLAME_CORE,
                AmmoMeshes.FLAME, AmmoMeshes.FLAME_TIP));
        for (CaseMaterial m : CaseMaterial.values()) {
            rgbs.add(m.rgb);
        }
        for (AmmoVariant v : Ammo.variants()) {
            ProjectileLook l = Ammo.look(v.id());
            rgbs.add(l.body());
            rgbs.add(l.band());
            if (l.tip() >= 0) {
                rgbs.add(l.tip());
            }
        }
        palette = new AmmoPalette(rgbs);
        palette.upload(PALETTE);
        material = SimpleMaterial.builder().texture(PALETTE).mipmap(false).build();
        for (AmmoVariant v : Ammo.variants()) {
            Calibre c = Ammo.calibre(v.calibre());
            ProjectileLook look = Ammo.look(v.id());
            anyTrail |= look.visible() && look.trail() && v.motor() == null;
            CARTRIDGES.put(v.id(), GemRenderModels.built(id(v.id(), "cartridge"),
                    id -> rig(id, AmmoMeshes.cartridge(c, look, JACKET, palette))));
            LOOKS.put(v.id(), look.model() != null ? GemRenderModels.handle(look.model())
                    : GemRenderModels.built(id(v.id(), "projectile"),
                    id -> rig(id, AmmoMeshes.projectile(look, palette))));
            if (look.flame()) {
                FLAMES.put(v.id(), GemRenderModels.built(id(v.id(), "flame"), id -> rig(id, AmmoMeshes.flame(look,
                        palette))));
            }
        }
    }

    private static ResourceLocation id(ResourceLocation variant, String kind) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, "ammo/" + kind + "/" + variant.getNamespace()
                + "/" + variant.getPath());
    }

    private static GemRenderGltfModel rig(ResourceLocation id, RigGeometry geometry) {
        RigBuilder rig = new RigBuilder(id.getPath());
        int body = rig.bone("body", RigBuilder.ROOT, 0, 0, 0);
        rig.table();
        rig.attach(body, geometry);
        return rig.build(material, Map.of());
    }

    /** Some entity-less (motorless) round draws a trail. */
    static boolean anyTrail() {
        return anyTrail;
    }

    /** Null while building. */
    @Nullable
    public static GemRenderGltfModel cartridge(ResourceLocation variant) {
        ModelCache.Handle<GemRenderGltfModel> h = CARTRIDGES.get(variant);
        return h == null ? null : h.get();
    }

    /** Null while building or no flame. */
    @Nullable
    public static GemRenderGltfModel flame(ResourceLocation variant) {
        ModelCache.Handle<GemRenderGltfModel> h = FLAMES.get(variant);
        return h == null ? null : h.get();
    }

    @Nullable
    public static GemRenderGltfModel projectile(ResourceLocation variant) {
        ModelCache.Handle<GemRenderGltfModel> h = LOOKS.get(variant);
        return h == null ? null : h.get();
    }
}
