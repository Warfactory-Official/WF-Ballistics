package com.wf.wflib.orbital;

import com.wf.wflib.orbital.payload.InertPayload;
import com.wf.wflib.orbital.payload.ReconPayload;
import com.wf.wflib.orbital.payload.RelayPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** The payload registry: a name to a constructor, and nothing about order. */
public final class SatPayloads {

    /** Ballast. What an unrecognised payload id becomes, so a bird with a missing mod is mass, not a crash. */
    public static final ResourceLocation INERT =
            ResourceLocation.fromNamespaceAndPath("wflib", "inert");
    /** Carries a recon picture between two networks that cannot see each other. */
    public static final ResourceLocation RELAY =
            ResourceLocation.fromNamespaceAndPath("wflib", "relay");
    /** An orbital radar: contacts over a footprint, into the same picture the ground radars feed. */
    public static final ResourceLocation RECON_RADAR =
            ResourceLocation.fromNamespaceAndPath("wflib", "recon_radar");
    /** Asteroid mining, and a shuttle to get the hold down. */
    public static final ResourceLocation MINER =
            ResourceLocation.fromNamespaceAndPath("wflib", "miner");
    /** Kinetic bombardment: ammunition with a flight time, not a cooldown. */
    public static final ResourceLocation RODS =
            ResourceLocation.fromNamespaceAndPath("wflib", "rods");
    /** The death ray: a long charge, a pass window, and weather that can refuse the shot. */
    public static final ResourceLocation LASER =
            ResourceLocation.fromNamespaceAndPath("wflib", "laser");
    /** Wide-area survey: a raster built over passes, stale by design, and readable as four products. */
    public static final ResourceLocation IMAGERY =
            ResourceLocation.fromNamespaceAndPath("wflib", "imagery");
    /** A cheap co-orbital killer. Individually poor; the point is that there are six of them. */
    public static final ResourceLocation HUNTER =
            ResourceLocation.fromNamespaceAndPath("wflib", "hunter");

    private static final Map<ResourceLocation, Supplier<SatPayload>> FACTORIES = new LinkedHashMap<>();

    private SatPayloads() {
    }

    /** Add a payload kind. */
    public static void register(ResourceLocation id, Supplier<SatPayload> factory) {
        FACTORIES.put(id, factory);
    }

    /**
     * @return true if this id has a factory. Use this rather than testing {@link #create} against null; see
     *      the class note about {@code BuiltInRegistries.get}.
     */
    public static boolean contains(ResourceLocation id) {
        return FACTORIES.containsKey(id);
    }

    /**
     * @return a fresh payload for this id, or an {@link InertPayload} if nothing is registered under it. Never
     *      null: a bird whose mod has been removed is still a bird, and its payload tag is held untouched in case
     *      that mod comes back.
     */
    public static SatPayload create(ResourceLocation id) {
        Supplier<SatPayload> factory = FACTORIES.get(id);
        return factory == null ? new InertPayload() : factory.get();
    }

    public static Set<ResourceLocation> ids() {
        return Collections.unmodifiableSet(FACTORIES.keySet());
    }

    /**
     * Register what this mod ships. Called once at mod construction, beside {@code ReconTargets.bootstrap}.
     */
    public static void bootstrap() {
        register(INERT, InertPayload::new);
        register(RELAY, RelayPayload::new);
        register(RECON_RADAR, ReconPayload::new);
        register(MINER, com.wf.wflib.orbital.payload.MinerPayload::new);
        register(RODS, com.wf.wflib.orbital.payload.RodsPayload::new);
        register(LASER, com.wf.wflib.orbital.payload.LaserPayload::new);
        register(HUNTER, com.wf.wflib.orbital.payload.HunterPayload::new);
        register(IMAGERY, com.wf.wflib.orbital.payload.ImageryPayload::new);
    }
}
