package com.wf.wfballistics.drone.ai.coord;

import com.wf.wfballistics.WFBallistics;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of {@link CoordinationModel}s, keyed by id in the same shape as {@code Formations}: add an architecture
 * by implementing the interface and calling {@link #register}.
 */
public final class CoordinationModels {

    public static final ResourceLocation DEFAULT = rl(VirtualStructure.INSTANCE.id());
    /**
     * What a squad saved before coordination models existed is read back as: the architecture it was actually
     * flying.
     */
    public static final ResourceLocation LEGACY = rl(LeaderFollower.INSTANCE.id());

    private static final Map<ResourceLocation, CoordinationModel> BY_ID = new LinkedHashMap<>();

    static {
        register(VirtualStructure.INSTANCE);
        register(LeaderFollower.INSTANCE);
        register(Consensus.INSTANCE);
        register(Flocking.INSTANCE);
    }

    private CoordinationModels() {
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(WFBallistics.MODID, path);
    }

    public static void register(CoordinationModel model) {
        BY_ID.put(rl(model.id()), model);
    }

    public static CoordinationModel get(ResourceLocation id) {
        CoordinationModel model = id == null ? null : BY_ID.get(id);
        return model != null ? model : BY_ID.get(DEFAULT);
    }

    public static ResourceLocation parse(String id) {
        if (id == null || id.isEmpty()) {
            return DEFAULT;
        }
        ResourceLocation parsed = id.indexOf(':') >= 0 ? ResourceLocation.tryParse(id) : rl(id);
        return parsed != null && BY_ID.containsKey(parsed) ? parsed : DEFAULT;
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(BY_ID.keySet());
    }
}
