package com.wf.wflib.mine;

import com.wf.wflib.WFLib;
import com.wf.wflib.util.GltfBounds;
import com.wf.wflib.util.ObjBounds;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of the models a {@link MineEntity} can render with, keyed by a stable {@link ResourceLocation} that is
 * persisted and synced on the entity, exactly as {@link com.wf.wflib.drone.DroneModels} does for airframes.
 */
public final class MineModels {

    /** Id used when a requested one is unknown or unset. */
    public static final ResourceLocation DEFAULT = rl("ap");

    private static final Map<ResourceLocation, Model> MODELS = new LinkedHashMap<>();

    static {
        reg("ap", 0.06);
        reg("bounding", 0.15);
        reg("claymore", 0.0);
        reg("heat", 0.09);
        reg("naval", 0.0);

        reg("scatter_ap", 0.0);
        reg("scatter_at", 0.0);
        reg("dispenser_ap", 0.0);
        reg("dispenser_at", 0.0);
    }

    private MineModels() {
    }

    /**
     * Register a mine model.
     *
     * @param proud how much of the mine stands clear of the ground when it is dug in, in blocks
     */
    public static void reg(String id, double proud) {
        ResourceLocation asset = ResourceLocation.fromNamespaceAndPath(WFLib.MODID,
                "raw_models/mines/" + id + ".gltf");
        ObjBounds.Bounds box = GltfBounds.of(asset).bounds();
        Vec3 size = box.any()
                ? new Vec3(Math.max(box.size().x, box.size().z), box.size().y,
                Math.max(box.size().x, box.size().z))
                : new Vec3(1.0, 1.0, 1.0);
        Vec3 center = box.any() ? box.center() : new Vec3(0.0, 0.5, 0.0);
        double sunk = size.y > 1.0E-3 ? 1.0 - Math.min(proud, size.y) / size.y : 0.0;
        MODELS.put(rl(id), new Model(asset, size, center, Math.max(0.0, sunk),
                numbered(asset, CANISTER_NODE)));
    }

    /** The three bones a rack's tube is made of, less the two-digit number that ends each of them. */
    public static final String CANISTER_NODE = "03_CANISTER_";

    /** The cap over a tube's mouth. See {@link #CANISTER_NODE}. */
    public static final String TUBE_CAP_NODE = "09_TUBE_CAP_";

    /** The mine sitting in a tube. See {@link #CANISTER_NODE}. */
    public static final String PAYLOAD_NODE = "10_STOWED_MINE_";

    /**
     * @return how many bones of one numbered family this model has.
     */
    private static int numbered(ResourceLocation asset, String prefix) {
        int found = 0;
        for (String name : GltfBounds.of(asset)
                .names()) {
            if (name.length() == prefix.length() + 2
                    && name.startsWith(prefix)
                    && Character.isDigit(name.charAt(name.length() - 2))
                    && Character.isDigit(name.charAt(name.length() - 1))) {
                found++;
            }
        }
        return found;
    }

    /** @return how many canisters this model can draw, and therefore how many a rack built on it may hold. */
    public static int canisterNodes(ResourceLocation id) {
        return model(id).canisterNodes();
    }

    /**
     * @return how many of this model's tubes carry a mine that can be drawn leaving, or 0 for a rack
     *      whose tubes are solid and can only be collapsed whole.
     */
    public static int payloadNodes(ResourceLocation id) {
        return numbered(model(id).asset(), PAYLOAD_NODE);
    }

    /** @return how many of this model's tubes have a cap that can be drawn coming off. */
    public static int tubeCapNodes(ResourceLocation id) {
        return numbered(model(id).asset(), TUBE_CAP_NODE);
    }

    public static ResourceLocation rl(String id) {
        return ResourceLocation.fromNamespaceAndPath(WFLib.MODID, id);
    }

    public static ResourceLocation defaultId() {
        return DEFAULT;
    }

    /**
     * Resolve a persisted/typed id string to a model key: a bare path is taken under the mod namespace, a {@code
     * namespace:path} string is parsed as-is, and anything that does not name a registered model falls back to
     * {@link #DEFAULT}.
     */
    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null && MODELS.containsKey(parsed) ? parsed : DEFAULT;
    }

    public static boolean exists(ResourceLocation id) {
        return MODELS.containsKey(id);
    }

    /** @return where a model's glTF lives. One asset per model; there is nothing else to a mine. */
    public static ResourceLocation asset(ResourceLocation id) {
        return model(id).asset();
    }

    /**
     * @return the model's body size in blocks: a square footprint wide enough to hold it, and the height
     *      it stands, measured up from the ground it sits on.
     */
    public static Vec3 size(ResourceLocation id) {
        return model(id).size();
    }

    /**
     * @return the centre of the model's own box, in model units relative to its origin.
     */
    public static Vec3 center(ResourceLocation id) {
        return model(id).center();
    }

    /**
     * @return the fraction of a mine that goes below the surface when it is dug in, 0 for one that does
     *      not dig in at all. The complement of the {@code proud} height it was registered with.
     */
    public static double buryFraction(ResourceLocation id) {
        return model(id).buryFraction();
    }

    /** @return all registered ids, in registration order. */
    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(MODELS.keySet());
    }

    private static Model model(ResourceLocation id) {
        Model found = MODELS.get(id);
        return found != null ? found : MODELS.get(DEFAULT);
    }

    /** One registered mine: the asset it draws as, how big that asset measured, and how deep it digs. */
    private record Model(ResourceLocation asset, Vec3 size, Vec3 center, double buryFraction,
                         int canisterNodes) {
    }
}
