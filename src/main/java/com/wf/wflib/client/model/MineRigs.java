package com.wf.wflib.client.model;

import com.mojang.logging.LogUtils;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.gltf.PoseDriver;
import com.wf.wflib.anim.NodeHide;
import com.wf.wflib.WFLib;
import com.wf.wflib.mine.MineCamo;
import com.wf.wflib.mine.MineModels;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The mine bodies, as imported GemRender models, each wearing whatever camouflage finishes have been drawn for it.
 */
public final class MineRigs {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TEXTURES = "textures/models/mines/";

    /** The clip that empties a rack. Named, so a model may ship its own and have it used instead. */
    public static final String EMPTY = "empty";

    /**
     * What the three nodes of a tube are called, less their number, taken from {@link MineModels} so the renderer
     * and the server-side count cannot disagree about which bones are which.
     */
    private static final String CANISTER = MineModels.CANISTER_NODE;
    private static final String TUBE_CAP = MineModels.TUBE_CAP_NODE;
    private static final String PAYLOAD = MineModels.PAYLOAD_NODE;

    private static final Map<ResourceLocation, Entry> RIGS = new ConcurrentHashMap<>();

    private MineRigs() {
    }

    /** @return the body for a mine model id, or null while its asset has yet to load or has failed to. */
    @Nullable
    public static GemRenderGltfModel model(ResourceLocation id) {
        return entry(id).handle()
                .get();
    }

    /**
     * @return the clip that collapses this model's canisters, or null for a model that has none.
     */
    @Nullable
    public static GltfAnimation empty(ResourceLocation id) {
        return entry(id).empty();
    }

    /**
     * @return which variant of the model wears this finish, or 0 (the base), for one nobody has drawn.
     */
    public static int variant(ResourceLocation id, MineCamo camo) {
        return entry(id).variants()
                .getOrDefault(camo, 0);
    }

    /**
     * Declare every mine's handle up front, for the same reason {@link PartRigs#init} does: a handle declared later
     * is imported by whichever flywheel task thread first wants it, mid-frame.
     */
    public static void init() {
        RIGS.clear();
        for (ResourceLocation id : MineModels.ids()) {
            model(id);
        }
    }

    private static Entry entry(ResourceLocation id) {
        return RIGS.computeIfAbsent(id, MineRigs::declare);
    }

    /** Works out which finishes this model has artwork for, and declares the model wearing all of them. */
    private static Entry declare(ResourceLocation id) {
        String model = id.getPath();
        ResourceLocation asset = MineModels.asset(id);

        List<Map<ResourceLocation, ResourceLocation>> swaps = new ArrayList<>();
        swaps.add(Map.of());
        Map<MineCamo, Integer> variants = new EnumMap<>(MineCamo.class);
        variants.put(MineCamo.DEFAULT, 0);

        for (MineCamo camo : MineCamo.values()) {
            if (camo == MineCamo.DEFAULT) {
                continue;
            }
            Map<ResourceLocation, ResourceLocation> swap = new LinkedHashMap<>();
            paint(swap, model, "basecolor", camo);
            paint(swap, model, "orm", camo);
            if (swap.isEmpty()) {
                continue;
            }
            variants.put(camo, swaps.size());
            swaps.add(Map.copyOf(swap));
        }

        ResourceLocation set = ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
                "mines/" + model + "/" + variants.keySet()
                        .stream()
                        .map(MineCamo::id)
                        .collect(Collectors.joining(".")));

        return new Entry(id, GemRenderModels.variants(set, asset, swaps), Map.copyOf(variants));
    }

    /** Adds one map's substitution to a finish, if that finish has drawn one. */
    private static void paint(Map<ResourceLocation, ResourceLocation> swap, String model, String map,
                              MineCamo camo) {
        ResourceLocation base = texture(model + "_" + map);
        ResourceLocation painted = texture(model + "_" + map + "_" + camo.id());
        if (exists(base) && exists(painted)) {
            swap.put(base, painted);
        }
    }

    private static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, TEXTURES + name + ".png");
    }

    private static boolean exists(ResourceLocation texture) {
        return Minecraft.getInstance()
                .getResourceManager()
                .getResource(texture)
                .isPresent();
    }

    /** Writes the emptying clip for a model that has tubes. */
    @Nullable
    private static GltfAnimation emptyClip(ResourceLocation id, NodeTable table) {
        List<String> tubes = new ArrayList<>();
        for (int index = 1; index <= 99; index++) {
            String number = (index < 10 ? "0" : "") + index;
            if (table.slotOfName(CANISTER + number) < 0) {
                break;
            }
            tubes.add(number);
        }
        if (tubes.isEmpty()) {
            return null;
        }

        List<PoseDriver> drivers = new ArrayList<>(tubes.size() * 2);
        for (int index = 0; index < tubes.size(); index++) {
            String number = tubes.get(index);
            float threshold = (index + 0.5f) / tubes.size();
            if (table.slotOfName(PAYLOAD + number) >= 0) {
                // A hollow tube: the mine leaves and the cap comes off it, and the tube is left standing.
                hide(drivers, id, table, PAYLOAD + number, threshold);
                hide(drivers, id, table, TUBE_CAP + number, threshold);
            } else {
                // A solid one, which is all the older assets were: it can only go whole.
                hide(drivers, id, table, CANISTER + number, threshold);
            }
        }
        return drivers.isEmpty() ? null
                : GltfAnimation.procedural(EMPTY, 1.0f, drivers.toArray(new PoseDriver[0]));
    }

    /** Adds a driver that takes one node away at {@code threshold}, if the model has it and it can move. */
    private static void hide(List<PoseDriver> drivers, ResourceLocation id, NodeTable table, String node,
                             float threshold) {
        int slot = table.slotOfName(node);
        if (slot < 0) {
            return;
        }
        if (!table.isPosable(slot)) {
            LOGGER.warn("mine {} has node '{}' baked to a matrix rather than TRS, so it cannot be "
                    + "emptied. Re-export without 'Bake all objects'.", id, node);
            return;
        }
        drivers.add(NodeHide.at(table, slot, threshold));
    }

    /** One mine model: the handle it is drawn from, where each finish sits in its sheet, and its clip. */
    private static final class Entry {

        private final ModelCache.Handle<GemRenderGltfModel> handle;
        private final Map<MineCamo, Integer> variants;
        private final ResourceLocation id;

        /** Built on the first frame the asset is available, then kept against the model it was written for. */
        private volatile GemRenderGltfModel clipFor;
        private volatile GltfAnimation empty;

        private Entry(ResourceLocation id, ModelCache.Handle<GemRenderGltfModel> handle,
                      Map<MineCamo, Integer> variants) {
            this.id = id;
            this.handle = handle;
            this.variants = variants;
        }

        private ModelCache.Handle<GemRenderGltfModel> handle() {
            return this.handle;
        }

        private Map<MineCamo, Integer> variants() {
            return this.variants;
        }

        @Nullable
        private GltfAnimation empty() {
            GemRenderGltfModel model = this.handle.get();
            if (model == null) {
                return null;
            }
            if (this.clipFor != model) {
                GltfAnimation shipped = model.animation(EMPTY);
                this.empty = shipped != null ? shipped
                        : emptyClip(this.id, model.layout()
                                .nodeTable());
                this.clipFor = model;
            }
            return this.empty;
        }
    }
}
