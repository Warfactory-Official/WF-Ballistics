package com.wf.wflib.rail;

import net.neoforged.fml.ModList;

/** Optional Immersive Railroading integration entry point. */
public final class RailCompat {

    public static final String MODID = "immersiverailroading";

    /** UniversalModCore - IR's abstraction layer, no longer shaded into IR as of 1.11.0. */
    public static final String UMC_MODID = "universalmodcore";

    /** The track graph IR positions stock along; a separate hard dependency of IR's. */
    public static final String TRACKAPI_MODID = "trackapi";

    /** Development override: pretend IR is installed. */
    private static final String FORCE_PROPERTY = "wflib.railForce";

    private static final boolean LOADED = detect();
    private static final boolean FORCED = "true".equals(System.getProperty(FORCE_PROPERTY));

    private RailCompat() {
    }

    private static boolean detect() {
        ModList list = ModList.get();
        return list != null && list.isLoaded(MODID);
    }

    /**
     * @return whether Immersive Railroading is present. False disables every rail feature.
     */
    public static boolean isActive() {
        return LOADED || FORCED;
    }

    /** @return whether the package is running on the dev override rather than on a real IR install. */
    public static boolean isForced() {
        return FORCED && !LOADED;
    }

    /** @return whether the features that read IR's track graph can run. */
    public static boolean trackGraphAvailable() {
        ModList list = ModList.get();
        return LOADED && list != null && list.isLoaded(TRACKAPI_MODID);
    }
}
